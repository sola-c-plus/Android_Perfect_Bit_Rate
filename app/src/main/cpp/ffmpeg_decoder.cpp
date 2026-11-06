// ★ Local Music モード用 FFmpeg デコーダの JNI ブリッジ（仕様書 §5）。
//   既存の native_audio_engine（DSP チェーン）とは完全に分離した小さなブリッジで、
//   「ファイル / content URI → float32 interleaved PCM」の供給係としてのみ機能する。
//   PCM の整形（サンプル形式変換）は swresample を使わず直接変換する（§5.2）。
//   例外は DSD 経路のみ: DSD デコーダ出力の PCM を設定レートへ変換する段でのみ
//   swresample を使う（後段のアップサンプラとは独立）。
//
//   入力は 2 経路:
//   - ファイルパス: avformat の file プロトコルで直接開く。
//   - content URI: Kotlin 側 InputStream へのコールバック（カスタム AVIOContext）
//     で読む。SAF のツリー配下など実パスが無いソースを扱うための経路。
//
//   スレッドモデル: デコードループは Kotlin 側の専用スレッドから
//   nativeDecodeLoop を呼ぶ形（ブロッキング）で回す。停止・シークは
//   atomic フラグ経由で要求し、ループ側がフレーム境界で適用する。
//   Kotlin コールバック（onPcmChunk / onDecodeEnded / avio*）は全て
//   このデコードスレッド、または nativeOpen を呼んだスレッド上で発火する。

#include <jni.h>
#include <android/log.h>
#include <atomic>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <string>
#include <vector>

extern "C" {
#include <libavcodec/avcodec.h>
#include <libavformat/avformat.h>
#include <libavutil/channel_layout.h>
#include <libavutil/dict.h>
#include <libavutil/error.h>
#include <libavutil/frame.h>
#include <libavutil/mem.h>
#include <libavutil/opt.h>
#include <libavutil/samplefmt.h>
#include <libswresample/swresample.h>
}

#define LOG_TAG "FfmpegBridge"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

namespace {

// AVIO コールバックは JNI 入口（nativeOpen / nativeDecodeLoop）を呼んだ
// スレッド上で発火するため、そのスレッドの JNIEnv をここに置いて参照する。
thread_local JNIEnv *tlsEnv = nullptr;

constexpr int AVIO_BUFFER_SIZE = 32 * 1024;
constexpr int CHUNK_FRAMES = 4096; // YT 経路のチャンク粒度に合わせる（仕様書 §4.2）

struct DecoderContext {
    // Kotlin 側 FfmpegDecoder インスタンス（コールバック先）。生成時に GlobalRef 化する。
    jobject callbackObj = nullptr;
    jmethodID midPcmChunk = nullptr;    // onPcmChunk([BJ)V
    jmethodID midDecodeEnded = nullptr; // onDecodeEnded(Z)V
    jmethodID midAvioRead = nullptr;    // avioRead([B)I
    jmethodID midAvioSeek = nullptr;    // avioSeek(JI)J
    jmethodID midAvioSize = nullptr;    // avioSize()J

    AVFormatContext *fmtCtx = nullptr;
    AVIOContext *avioCtx = nullptr;
    AVCodecContext *codecCtx = nullptr;
    SwrContext *swrCtx = nullptr; // DSD 経路専用
    int streamIndex = -1;

    // ファイル情報（nativeOpen 成功後に確定）
    int sampleRate = 0;      // デコード出力のサンプルレート（DSD は変換後）
    int channels = 0;        // 出力チャンネル数（1 or 2）
    int srcChannels = 0;
    enum AVSampleFormat srcFmt = AV_SAMPLE_FMT_NONE;
    int bitDepth = 0;        // 推定ビット深度（ロスレス系のみ意味を持つ。0=不明）
    int64_t bitRate = 0;
    std::string codecName;
    bool isDsd = false;
    int64_t durationMs = 0;

    std::atomic<bool> stopRequested{false};
    std::atomic<int64_t> seekRequestMs{-1};

    // チャンク化用アキュムレータ（float interleaved）
    std::vector<float> accum;
    int64_t lastPositionMs = 0;
};

bool clearPendingException(JNIEnv *env, const char *where) {
    if (env->ExceptionCheck()) {
        LOGE("Kotlin exception at %s", where);
        env->ExceptionDescribe();
        env->ExceptionClear();
        return true;
    }
    return false;
}

// ---- カスタム AVIO のコールバック（opaque = DecoderContext） ----

int avioReadPacket(void *opaque, uint8_t *buf, int bufSize) {
    auto *ctx = static_cast<DecoderContext *>(opaque);
    JNIEnv *env = tlsEnv;
    if (env == nullptr || ctx->callbackObj == nullptr) return AVERROR(EIO);

    jbyteArray array = env->NewByteArray(bufSize);
    if (array == nullptr) {
        env->ExceptionClear();
        return AVERROR(ENOMEM);
    }
    jint read = env->CallIntMethod(ctx->callbackObj, ctx->midAvioRead, array);
    if (clearPendingException(env, "avioRead")) {
        env->DeleteLocalRef(array);
        return AVERROR(EIO);
    }
    if (read == 0) {
        env->DeleteLocalRef(array);
        return AVERROR_EOF;
    }
    if (read < 0) {
        env->DeleteLocalRef(array);
        return AVERROR(EIO);
    }
    if (read > bufSize) read = bufSize;
    env->GetByteArrayRegion(array, 0, read, reinterpret_cast<jbyte *>(buf));
    env->DeleteLocalRef(array);
    return read;
}

int64_t avioSeekPacket(void *opaque, int64_t offset, int whence) {
    auto *ctx = static_cast<DecoderContext *>(opaque);
    JNIEnv *env = tlsEnv;
    if (env == nullptr || ctx->callbackObj == nullptr) return AVERROR(EIO);

    if ((whence & AVSEEK_SIZE) != 0) {
        jlong size = env->CallLongMethod(ctx->callbackObj, ctx->midAvioSize);
        if (clearPendingException(env, "avioSize")) return AVERROR(EIO);
        return size;
    }
    whence &= ~AVSEEK_FORCE;
    jlong result = env->CallLongMethod(ctx->callbackObj, ctx->midAvioSeek,
                                       static_cast<jlong>(offset), static_cast<jint>(whence));
    if (clearPendingException(env, "avioSeek")) return AVERROR(EIO);
    return result;
}

// ---- サンプル形式の直接変換（swresample を使わない、仕様書 §5.2） ----
//   対象は interleaved / planar の主要形式。出力は float interleaved。
//   チャンネルは先頭 2ch までを使い、モノラルは L/R へ複製する
//   （後段の DSP チェーンがステレオ前提のため）。

float readSampleAsFloat(const uint8_t *data, enum AVSampleFormat fmt, int index) {
    switch (fmt) {
        case AV_SAMPLE_FMT_U8:
            return (static_cast<float>(data[index]) - 128.0f) / 128.0f;
        case AV_SAMPLE_FMT_S16:
            return static_cast<float>(reinterpret_cast<const int16_t *>(data)[index]) / 32768.0f;
        case AV_SAMPLE_FMT_S32:
            return static_cast<float>(static_cast<double>(reinterpret_cast<const int32_t *>(data)[index]) / 2147483648.0);
        case AV_SAMPLE_FMT_S64:
            return static_cast<float>(static_cast<double>(reinterpret_cast<const int64_t *>(data)[index]) / 9223372036854775808.0);
        case AV_SAMPLE_FMT_FLT:
            return reinterpret_cast<const float *>(data)[index];
        case AV_SAMPLE_FMT_DBL:
            return static_cast<float>(reinterpret_cast<const double *>(data)[index]);
        default:
            return 0.0f;
    }
}

bool isPlanarFormat(enum AVSampleFormat fmt) {
    return av_sample_fmt_is_planar(fmt) != 0;
}

enum AVSampleFormat baseFormat(enum AVSampleFormat fmt) {
    return av_get_packed_sample_fmt(fmt);
}

// フレームを ctx->accum へ変換しながら追加する。DSD 経路（swrCtx 有効時）は
// swresample で変換レート＋float 化まで済ませる。
bool appendFrame(DecoderContext *ctx, AVFrame *frame) {
    const int inChannels = frame->ch_layout.nb_channels > 0
                               ? frame->ch_layout.nb_channels
                               : ctx->srcChannels;
    const int frames = frame->nb_samples;
    if (frames <= 0 || inChannels <= 0) return true;

    if (ctx->swrCtx != nullptr) {
        const int outRate = ctx->sampleRate;
        const int64_t delay = swr_get_delay(ctx->swrCtx, frame->sample_rate);
        const int outCapacity = static_cast<int>(av_rescale_rnd(
            delay + frames, outRate, frame->sample_rate, AV_ROUND_UP));
        std::vector<float> converted(static_cast<size_t>(outCapacity) * ctx->channels);
        uint8_t *outPtr = reinterpret_cast<uint8_t *>(converted.data());
        int outSamples = swr_convert(ctx->swrCtx,
                                     &outPtr, outCapacity,
                                     const_cast<const uint8_t **>(frame->data), frames);
        if (outSamples < 0) return false;
        converted.resize(static_cast<size_t>(outSamples) * ctx->channels);
        ctx->accum.insert(ctx->accum.end(), converted.begin(), converted.end());
        return true;
    }

    const enum AVSampleFormat fmt = static_cast<enum AVSampleFormat>(frame->format);
    const enum AVSampleFormat packed = baseFormat(fmt);
    const bool planar = isPlanarFormat(fmt);
    const size_t base = ctx->accum.size();
    ctx->accum.resize(base + static_cast<size_t>(frames) * ctx->channels);
    for (int i = 0; i < frames; i++) {
        float left;
        float right;
        if (planar) {
            left = readSampleAsFloat(frame->data[0], packed, i);
            right = inChannels > 1 ? readSampleAsFloat(frame->data[1], packed, i) : left;
        } else {
            left = readSampleAsFloat(frame->data[0], packed, i * inChannels);
            right = inChannels > 1
                        ? readSampleAsFloat(frame->data[0], packed, i * inChannels + 1)
                        : left;
        }
        ctx->accum[base + static_cast<size_t>(i) * ctx->channels] = left;
        if (ctx->channels > 1) {
            ctx->accum[base + static_cast<size_t>(i) * ctx->channels + 1] = right;
        }
    }
    return true;
}

// アキュムレータから CHUNK_FRAMES 分を切り出して Kotlin へ渡す。
// flushRemainder=true のときは端数も送る（EOF / シーク直前用）。
bool emitChunks(JNIEnv *env, DecoderContext *ctx, bool flushRemainder) {
    const size_t chunkSamples = static_cast<size_t>(CHUNK_FRAMES) * ctx->channels;
    while (ctx->accum.size() >= chunkSamples ||
           (flushRemainder && !ctx->accum.empty())) {
        const size_t samples = flushRemainder && ctx->accum.size() < chunkSamples
                                   ? ctx->accum.size()
                                   : chunkSamples;
        const int frames = static_cast<int>(samples / ctx->channels);
        if (frames <= 0) break;
        jbyteArray array = env->NewByteArray(static_cast<jsize>(frames * ctx->channels * 4));
        if (array == nullptr) {
            env->ExceptionClear();
            return false;
        }
        env->SetByteArrayRegion(array, 0, static_cast<jsize>(frames * ctx->channels * 4),
                                reinterpret_cast<const jbyte *>(ctx->accum.data()));
        env->CallVoidMethod(ctx->callbackObj, ctx->midPcmChunk, array,
                            static_cast<jlong>(ctx->lastPositionMs));
        env->DeleteLocalRef(array);
        if (clearPendingException(env, "onPcmChunk")) return false;
        ctx->accum.erase(ctx->accum.begin(),
                         ctx->accum.begin() + static_cast<ptrdiff_t>(frames * ctx->channels));
        if (ctx->stopRequested.load()) return false;
    }
    return true;
}

void closeDecoder(DecoderContext *ctx) {
    if (ctx->swrCtx != nullptr) {
        swr_free(&ctx->swrCtx);
    }
    if (ctx->codecCtx != nullptr) {
        avcodec_free_context(&ctx->codecCtx);
    }
    if (ctx->fmtCtx != nullptr) {
        // AVFMT_FLAG_CUSTOM_IO を立てているため、avformat_close_input は
        // 自前 AVIO を解放しない（demux.c の仕様）。順序: フォーマットを
        // 閉じてから AVIO を自前で解放する。
        avformat_close_input(&ctx->fmtCtx);
    }
    if (ctx->avioCtx != nullptr) {
        av_freep(&ctx->avioCtx->buffer);
        avio_context_free(&ctx->avioCtx);
    }
    ctx->streamIndex = -1;
    ctx->accum.clear();
}

DecoderContext *fromHandle(jlong handle) {
    return reinterpret_cast<DecoderContext *>(static_cast<intptr_t>(handle));
}

} // namespace

extern "C" {

// ★ デコーダ生成。コールバック先（FfmpegDecoder 自身）を保持する。
JNIEXPORT jlong JNICALL
Java_com_example_perfectbitrate_FfmpegDecoder_nativeCreate(JNIEnv *env, jobject thiz) {
    auto *ctx = new DecoderContext();
    ctx->callbackObj = env->NewGlobalRef(thiz);
    jclass cls = env->GetObjectClass(thiz);
    ctx->midPcmChunk = env->GetMethodID(cls, "onPcmChunk", "([BJ)V");
    ctx->midDecodeEnded = env->GetMethodID(cls, "onDecodeEnded", "(Z)V");
    ctx->midAvioRead = env->GetMethodID(cls, "avioRead", "([B)I");
    ctx->midAvioSeek = env->GetMethodID(cls, "avioSeek", "(JI)J");
    ctx->midAvioSize = env->GetMethodID(cls, "avioSize", "()J");
    env->DeleteLocalRef(cls);
    if (ctx->midPcmChunk == nullptr || ctx->midDecodeEnded == nullptr ||
        ctx->midAvioRead == nullptr || ctx->midAvioSeek == nullptr ||
        ctx->midAvioSize == nullptr) {
        LOGE("nativeCreate: callback method not found");
        env->ExceptionClear();
        env->DeleteGlobalRef(ctx->callbackObj);
        delete ctx;
        return 0;
    }
    return static_cast<jlong>(reinterpret_cast<intptr_t>(ctx));
}

JNIEXPORT void JNICALL
Java_com_example_perfectbitrate_FfmpegDecoder_nativeRelease(JNIEnv *env, jobject /*thiz*/, jlong handle) {
    DecoderContext *ctx = fromHandle(handle);
    if (ctx == nullptr) return;
    ctx->stopRequested.store(true);
    closeDecoder(ctx);
    if (ctx->callbackObj != nullptr) {
        env->DeleteGlobalRef(ctx->callbackObj);
        ctx->callbackObj = nullptr;
    }
    delete ctx;
}

// ★ 入力を開く。useAvio=true なら Kotlin 側ストリーム（content URI）経由、
//   false なら path をファイルとして直接開く。
//   戻り値: 0=成功, 負値=エラー（AVERROR 相当 or -1000 台の独自コード）。
JNIEXPORT jint JNICALL
Java_com_example_perfectbitrate_FfmpegDecoder_nativeOpen(JNIEnv *env, jobject /*thiz*/,
                                                         jlong handle, jboolean useAvio,
                                                         jstring path, jint dsdPcmRateHz) {
    DecoderContext *ctx = fromHandle(handle);
    if (ctx == nullptr) return -1000;
    tlsEnv = env;
    closeDecoder(ctx);
    ctx->stopRequested.store(false);
    ctx->seekRequestMs.store(-1);
    ctx->lastPositionMs = 0;

    AVFormatContext *fmt = avformat_alloc_context();
    if (fmt == nullptr) return AVERROR(ENOMEM);

    if (useAvio == JNI_TRUE) {
        uint8_t *buffer = static_cast<uint8_t *>(av_malloc(AVIO_BUFFER_SIZE));
        if (buffer == nullptr) {
            avformat_free_context(fmt);
            return AVERROR(ENOMEM);
        }
        ctx->avioCtx = avio_alloc_context(buffer, AVIO_BUFFER_SIZE, 0, ctx,
                                          &avioReadPacket, nullptr, &avioSeekPacket);
        if (ctx->avioCtx == nullptr) {
            av_free(buffer);
            avformat_free_context(fmt);
            return AVERROR(ENOMEM);
        }
        fmt->pb = ctx->avioCtx;
        fmt->flags |= AVFMT_FLAG_CUSTOM_IO;
    }

    const char *pathChars = nullptr;
    if (path != nullptr) {
        pathChars = env->GetStringUTFChars(path, nullptr);
    }
    int ret = avformat_open_input(&fmt, useAvio == JNI_TRUE ? nullptr : pathChars, nullptr, nullptr);
    if (pathChars != nullptr) {
        env->ReleaseStringUTFChars(path, pathChars);
    }
    if (ret < 0) {
        char errbuf[AV_ERROR_MAX_STRING_SIZE] = {0};
        av_strerror(ret, errbuf, sizeof(errbuf));
        LOGE("nativeOpen: avformat_open_input failed: %s", errbuf);
        // close_input は CUSTOM_IO の AVIO を解放しないため、開くのに失敗した
        // 場合はこちらで AVIO を片付ける。
        avformat_free_context(fmt);
        if (ctx->avioCtx != nullptr) {
            av_freep(&ctx->avioCtx->buffer);
            avio_context_free(&ctx->avioCtx);
        }
        return ret;
    }
    ctx->fmtCtx = fmt;

    ret = avformat_find_stream_info(fmt, nullptr);
    if (ret < 0) {
        LOGE("nativeOpen: avformat_find_stream_info failed");
        closeDecoder(ctx);
        return ret;
    }

    int streamIndex = av_find_best_stream(fmt, AVMEDIA_TYPE_AUDIO, -1, -1, nullptr, 0);
    if (streamIndex < 0) {
        LOGW("nativeOpen: no audio stream");
        closeDecoder(ctx);
        return -1001;
    }
    ctx->streamIndex = streamIndex;
    AVStream *stream = fmt->streams[streamIndex];
    AVCodecParameters *par = stream->codecpar;

    const AVCodec *codec = avcodec_find_decoder(par->codec_id);
    if (codec == nullptr) {
        LOGW("nativeOpen: decoder not found for codec %d", par->codec_id);
        closeDecoder(ctx);
        return -1002;
    }
    ctx->codecCtx = avcodec_alloc_context3(codec);
    if (ctx->codecCtx == nullptr) {
        closeDecoder(ctx);
        return AVERROR(ENOMEM);
    }
    if (avcodec_parameters_to_context(ctx->codecCtx, par) < 0 ||
        avcodec_open2(ctx->codecCtx, codec, nullptr) < 0) {
        LOGE("nativeOpen: avcodec_open2 failed");
        closeDecoder(ctx);
        return -1003;
    }

    ctx->codecName = codec->name != nullptr ? codec->name : "";
    ctx->isDsd = par->codec_id == AV_CODEC_ID_DSD_LSBF ||
                 par->codec_id == AV_CODEC_ID_DSD_MSBF ||
                 par->codec_id == AV_CODEC_ID_DSD_LSBF_PLANAR ||
                 par->codec_id == AV_CODEC_ID_DSD_MSBF_PLANAR;
    ctx->srcChannels = par->ch_layout.nb_channels > 0 ? par->ch_layout.nb_channels : 2;
    ctx->channels = ctx->srcChannels == 1 ? 2 : (ctx->srcChannels >= 2 ? 2 : ctx->srcChannels);
    if (ctx->channels <= 0) ctx->channels = 2;
    ctx->srcFmt = static_cast<enum AVSampleFormat>(par->format);
    ctx->bitDepth = par->bits_per_raw_sample > 0 ? par->bits_per_raw_sample
                                                  : par->bits_per_coded_sample;
    ctx->bitRate = par->bit_rate > 0 ? par->bit_rate : fmt->bit_rate;
    if (fmt->duration > 0) {
        ctx->durationMs = fmt->duration / 1000; // AV_TIME_BASE はマイクロ秒
    } else if (stream->duration > 0) {
        ctx->durationMs = av_rescale_q(stream->duration, stream->time_base, AVRational{1, 1000});
    } else {
        ctx->durationMs = 0;
    }

    if (ctx->isDsd) {
        // ★ DSD 経路のみ swresample で設定レートへ変換する（§5.2）。
        //   DSD デコーダは PCM（float, DSD レート/8）を出すので、それを
        //   dsdPcmRateHz へ落とす。変換先レイアウトは出力チャンネル数に合わせる。
        const int inRate = ctx->codecCtx->sample_rate;
        ctx->sampleRate = dsdPcmRateHz > 0 ? dsdPcmRateHz : 176400;
        if (inRate != ctx->sampleRate || ctx->codecCtx->sample_fmt != AV_SAMPLE_FMT_FLT) {
            AVChannelLayout inLayout;
            av_channel_layout_default(&inLayout, ctx->srcChannels);
            AVChannelLayout outLayout;
            av_channel_layout_default(&outLayout, ctx->channels);
            SwrContext *swr = swr_alloc();
            av_opt_set_chlayout(swr, "in_chlayout", &inLayout, 0);
            av_opt_set_chlayout(swr, "out_chlayout", &outLayout, 0);
            av_opt_set_int(swr, "in_sample_rate", inRate, 0);
            av_opt_set_int(swr, "out_sample_rate", ctx->sampleRate, 0);
            av_opt_set_sample_fmt(swr, "in_sample_fmt", ctx->codecCtx->sample_fmt, 0);
            av_opt_set_sample_fmt(swr, "out_sample_fmt", AV_SAMPLE_FMT_FLT, 0);
            if (swr_init(swr) < 0) {
                LOGE("nativeOpen: swr_init failed for DSD path");
                swr_free(&swr);
                av_channel_layout_uninit(&inLayout);
                av_channel_layout_uninit(&outLayout);
                closeDecoder(ctx);
                return -1004;
            }
            ctx->swrCtx = swr;
            av_channel_layout_uninit(&inLayout);
            av_channel_layout_uninit(&outLayout);
        }
    } else {
        ctx->sampleRate = ctx->codecCtx->sample_rate;
    }
    if (ctx->sampleRate <= 0) ctx->sampleRate = 48000;

    LOGI("nativeOpen: codec=%s rate=%d ch=%d bits=%d dsd=%d duration=%lldms",
         ctx->codecName.c_str(), ctx->sampleRate, ctx->channels, ctx->bitDepth,
         ctx->isDsd ? 1 : 0, static_cast<long long>(ctx->durationMs));
    return 0;
}

// ★ ストリーム/フォーマット情報の取得。
//   戻り値の String 配列（固定順）:
//   [0]=sampleRate [1]=channels [2]=bitDepth [3]=codecName [4]=bitRate
//   [5]=durationMs [6]=isDsd(0/1) [7]=title [8]=artist [9]=album [10]=track
JNIEXPORT jobjectArray JNICALL
Java_com_example_perfectbitrate_FfmpegDecoder_nativeGetInfo(JNIEnv *env, jobject /*thiz*/, jlong handle) {
    DecoderContext *ctx = fromHandle(handle);
    jclass stringCls = env->FindClass("java/lang/String");
    jobjectArray result = env->NewObjectArray(11, stringCls, nullptr);
    env->DeleteLocalRef(stringCls);
    if (ctx == nullptr || result == nullptr) return result;

    AVDictionary *meta = ctx->fmtCtx != nullptr ? ctx->fmtCtx->metadata : nullptr;
    auto getMeta = [&](const char *key) -> std::string {
        if (meta == nullptr) return "";
        AVDictionaryEntry *entry = av_dict_get(meta, key, nullptr, 0);
        return entry != nullptr && entry->value != nullptr ? entry->value : "";
    };

    std::string values[11];
    values[0] = std::to_string(ctx->sampleRate);
    values[1] = std::to_string(ctx->channels);
    values[2] = std::to_string(ctx->bitDepth);
    values[3] = ctx->codecName;
    values[4] = std::to_string(ctx->bitRate);
    values[5] = std::to_string(ctx->durationMs);
    values[6] = ctx->isDsd ? "1" : "0";
    values[7] = getMeta("title");
    values[8] = getMeta("artist");
    values[9] = getMeta("album");
    values[10] = getMeta("track");
    for (int i = 0; i < 11; i++) {
        jstring s = env->NewStringUTF(values[i].c_str());
        env->SetObjectArrayElement(result, i, s);
        env->DeleteLocalRef(s);
    }
    return result;
}

// ★ 埋め込みアートワーク（attached picture）の生データを返す。無ければ null。
JNIEXPORT jbyteArray JNICALL
Java_com_example_perfectbitrate_FfmpegDecoder_nativeGetArtwork(JNIEnv *env, jobject /*thiz*/, jlong handle) {
    DecoderContext *ctx = fromHandle(handle);
    if (ctx == nullptr || ctx->fmtCtx == nullptr) return nullptr;
    for (unsigned int i = 0; i < ctx->fmtCtx->nb_streams; i++) {
        AVStream *stream = ctx->fmtCtx->streams[i];
        if ((stream->disposition & AV_DISPOSITION_ATTACHED_PIC) != 0 &&
            stream->attached_pic.size > 0 && stream->attached_pic.data != nullptr) {
            jbyteArray array = env->NewByteArray(stream->attached_pic.size);
            if (array == nullptr) {
                env->ExceptionClear();
                return nullptr;
            }
            env->SetByteArrayRegion(array, 0, stream->attached_pic.size,
                                    reinterpret_cast<const jbyte *>(stream->attached_pic.data));
            return array;
        }
    }
    return nullptr;
}

// ★ デコードループ（ブロッキング）。Kotlin 側の専用スレッドから呼ぶ。
//   終了時は onDecodeEnded(error) を呼んでから戻る。
JNIEXPORT void JNICALL
Java_com_example_perfectbitrate_FfmpegDecoder_nativeDecodeLoop(JNIEnv *env, jobject /*thiz*/, jlong handle) {
    DecoderContext *ctx = fromHandle(handle);
    if (ctx == nullptr || ctx->fmtCtx == nullptr || ctx->codecCtx == nullptr) {
        if (ctx != nullptr && ctx->callbackObj != nullptr) {
            env->CallVoidMethod(ctx->callbackObj, ctx->midDecodeEnded, JNI_TRUE);
            clearPendingException(env, "onDecodeEnded");
        }
        return;
    }
    tlsEnv = env;
    ctx->stopRequested.store(false);

    AVPacket *packet = av_packet_alloc();
    AVFrame *frame = av_frame_alloc();
    bool error = false;
    bool eof = false;

    auto applySeekIfRequested = [&]() {
        int64_t targetMs = ctx->seekRequestMs.exchange(-1);
        if (targetMs < 0) return;
        AVStream *stream = ctx->fmtCtx->streams[ctx->streamIndex];
        int64_t targetTs = av_rescale_q(targetMs, AVRational{1, 1000}, stream->time_base);
        int ret = avformat_seek_file(ctx->fmtCtx, ctx->streamIndex,
                                     INT64_MIN, targetTs, INT64_MAX, 0);
        if (ret < 0) {
            ret = av_seek_frame(ctx->fmtCtx, ctx->streamIndex, targetTs, AVSEEK_FLAG_BACKWARD);
        }
        avcodec_flush_buffers(ctx->codecCtx);
        // DSD 経路の swr 内部バッファは破棄しない（残留は数 ms 分に留まる）。
        // シーク位置の正確さはアキュムレータ側のリセットで担保する。
        ctx->accum.clear();
        ctx->lastPositionMs = targetMs;
        if (ret < 0) {
            LOGW("decodeLoop: seek to %lldms failed", static_cast<long long>(targetMs));
        }
    };

    // デコーダからフレームを取り尽くす。戻り値: 0=継続可, 1=EOF 到達, -1=致命的
    auto drainFrames = [&]() -> int {
        while (!ctx->stopRequested.load()) {
            int recvRet = avcodec_receive_frame(ctx->codecCtx, frame);
            if (recvRet == AVERROR(EAGAIN)) return 0;
            if (recvRet == AVERROR_EOF) return 1;
            if (recvRet < 0) {
                LOGW("decodeLoop: avcodec_receive_frame failed (%d)", recvRet);
                return -1;
            }
            AVStream *stream = ctx->fmtCtx->streams[ctx->streamIndex];
            int64_t bestPts = frame->best_effort_timestamp;
            if (bestPts != AV_NOPTS_VALUE) {
                ctx->lastPositionMs = av_rescale_q(bestPts, stream->time_base, AVRational{1, 1000});
            }
            bool ok = appendFrame(ctx, frame);
            av_frame_unref(frame);
            if (!ok) return -1;
            if (!emitChunks(env, ctx, false)) {
                return ctx->stopRequested.load() ? 0 : -1;
            }
        }
        return 0;
    };

    while (!ctx->stopRequested.load()) {
        applySeekIfRequested();
        if (ctx->stopRequested.load()) break;

        int readRet = av_read_frame(ctx->fmtCtx, packet);
        if (readRet < 0) {
            // EOF 系・終端系エラーは全て「終端」として扱い、デコーダの残りを
            // 吐き出して正常終了する（一部 demuxer は終端で EIO 等を返すため）。
            eof = true;
            avcodec_send_packet(ctx->codecCtx, nullptr);
            if (drainFrames() < 0 && !ctx->stopRequested.load()) error = true;
            break;
        }
        if (packet->stream_index != ctx->streamIndex) {
            av_packet_unref(packet);
            continue;
        }
        int sendRet = avcodec_send_packet(ctx->codecCtx, packet);
        if (sendRet == AVERROR(EAGAIN)) {
            // デコーダが一杯: 先にフレームを取り尽くしてから同じパケットを再送する
            if (drainFrames() < 0) {
                av_packet_unref(packet);
                if (!ctx->stopRequested.load()) error = true;
                break;
            }
            sendRet = avcodec_send_packet(ctx->codecCtx, packet);
        }
        av_packet_unref(packet);
        if (sendRet < 0 && sendRet != AVERROR_INVALIDDATA) {
            LOGW("decodeLoop: avcodec_send_packet failed (%d)", sendRet);
        }
        int drainRet = drainFrames();
        if (drainRet < 0) {
            if (!ctx->stopRequested.load()) error = true;
            break;
        }
        if (drainRet == 1) {
            eof = true;
            break;
        }
    }

    if (!error && !ctx->stopRequested.load()) {
        // EOF: 端数を最後まで送る
        emitChunks(env, ctx, true);
    }

    av_frame_free(&frame);
    av_packet_free(&packet);

    env->CallVoidMethod(ctx->callbackObj, ctx->midDecodeEnded,
                        static_cast<jboolean>(error ? JNI_TRUE : JNI_FALSE));
    clearPendingException(env, "onDecodeEnded");
}

JNIEXPORT void JNICALL
Java_com_example_perfectbitrate_FfmpegDecoder_nativeRequestStop(JNIEnv * /*env*/, jobject /*thiz*/, jlong handle) {
    DecoderContext *ctx = fromHandle(handle);
    if (ctx != nullptr) ctx->stopRequested.store(true);
}

JNIEXPORT void JNICALL
Java_com_example_perfectbitrate_FfmpegDecoder_nativeRequestSeek(JNIEnv * /*env*/, jobject /*thiz*/,
                                                                jlong handle, jlong positionMs) {
    DecoderContext *ctx = fromHandle(handle);
    if (ctx != nullptr) ctx->seekRequestMs.store(positionMs);
}

} // extern "C"
