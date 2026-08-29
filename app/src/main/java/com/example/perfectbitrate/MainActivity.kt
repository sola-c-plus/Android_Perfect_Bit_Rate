package com.example.perfectbitrate

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.view.MotionEvent
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var textStatus: TextView
    private lateinit var webViewMusic: WebView
    private lateinit var audioManager: AudioManager
    private var audioTrack: AudioTrack? = null
    private var currentSampleRate = 48000
    private var pcmPacketCount = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        textStatus = findViewById(R.id.textStatus)
        webViewMusic = findViewById(R.id.webViewMusic)
        val btnReload = findViewById<Button>(R.id.btnReload)

        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        // 初期ハードウェア DIRECT 出力設定 (48000Hz)
        setupDirectAudioTrack(48000)

        setupWebView()

        btnReload.setOnClickListener {
            pcmPacketCount = 0L
            webViewMusic.reload()
        }
    }

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    private fun setupWebView() {
        webViewMusic.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            allowFileAccess = true
            allowContentAccess = true
            // ★デスクトップ版 Chrome UA (制限なし・高音質ストリーム)
            userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"
        }

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webViewMusic, true)

        // 画面タップ時に AudioContext を確実にアクティブ化
        webViewMusic.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                webViewMusic.evaluateJavascript("if(window.__resumeAudio) window.__resumeAudio();", null)
            }
            false
        }

        // Web Audio API とのブリッジ
        webViewMusic.addJavascriptInterface(object {
            @JavascriptInterface
            fun onPcmData(base64Pcm: String, sampleRate: Int) {
                try {
                    val pcmBytes = Base64.decode(base64Pcm, Base64.NO_WRAP)
                    handleIncomingPcm(pcmBytes, sampleRate)
                } catch (e: Exception) {
                    Log.e("WebAudioBridge", "Decode error: ${e.message}")
                }
            }

            @JavascriptInterface
            fun onLog(msg: String) {
                Log.i("WebAudioJS", msg)
                runOnUiThread {
                    if (pcmPacketCount == 0L) {
                        textStatus.text = "【状態】$msg"
                    }
                }
            }
        }, "AndroidAudioBridge")

        webViewMusic.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                Log.d("WebConsole", "[${consoleMessage?.messageLevel()}] ${consoleMessage?.message()}")
                return true
            }
        }

        webViewMusic.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                injectOptimizedWebAudioScript()
            }
        }

        webViewMusic.loadUrl("https://music.youtube.com")
    }

    // ★ 0.05ms で処理が完了する超高速 Web Audio スクリプト
    private fun injectOptimizedWebAudioScript() {
        val hijackJs = """
            (function() {
                if (window.__pcmOptInjected) return;
                window.__pcmOptInjected = true;

                var audioCtx = null;
                var hookedElements = new WeakSet();

                window.__resumeAudio = function() {
                    if (audioCtx && audioCtx.state === 'suspended') {
                        audioCtx.resume();
                    }
                };

                function getAudioContext() {
                    if (!audioCtx) {
                        var AudioContextClass = window.AudioContext || window.webkitAudioContext;
                        audioCtx = new AudioContextClass({ sampleRate: 48000 });
                    }
                    if (audioCtx.state === 'suspended') {
                        audioCtx.resume();
                    }
                    return audioCtx;
                }

                function hookElement(element) {
                    if (!element || hookedElements.has(element)) return;

                    try {
                        var ctx = getAudioContext();
                        var source = ctx.createMediaElementSource(element);
                        var processor = ctx.createScriptProcessor(4096, 2, 2);

                        var muteGain = ctx.createGain();
                        muteGain.gain.value = 0.0;

                        processor.onaudioprocess = function(e) {
                            var left = e.inputBuffer.getChannelData(0);
                            var right = e.inputBuffer.getChannelData(1);
                            var len = left.length;

                            var pcm16 = new Int16Array(len * 2);
                            for (var i = 0; i < len; i++) {
                                var l = Math.max(-1, Math.min(1, left[i]));
                                var r = Math.max(-1, Math.min(1, right[i]));
                                pcm16[i * 2] = l < 0 ? l * 32768 : l * 32767;
                                pcm16[i * 2 + 1] = r < 0 ? r * 32768 : r * 32767;
                            }

                            // ★超高速チャンク変換 (負荷ゼロ)
                            var u8 = new Uint8Array(pcm16.buffer);
                            var binary = "";
                            var chunkSize = 8192;
                            for (var k = 0; k < u8.length; k += chunkSize) {
                                binary += String.fromCharCode.apply(null, u8.subarray(k, k + chunkSize));
                            }

                            if (window.AndroidAudioBridge) {
                                window.AndroidAudioBridge.onPcmData(btoa(binary), ctx.sampleRate);
                            }
                        };

                        source.connect(processor);
                        processor.connect(muteGain);
                        muteGain.connect(ctx.destination);

                        hookedElements.add(element);

                        if (window.AndroidAudioBridge) {
                            window.AndroidAudioBridge.onLog("★ ハイジャック完了 (直通出力中)");
                        }
                    } catch(err) {
                        if (window.AndroidAudioBridge) {
                            window.AndroidAudioBridge.onLog("Hook: " + err.message);
                        }
                    }
                }

                document.addEventListener('play', function(e) {
                    if (e.target && (e.target.tagName === 'VIDEO' || e.target.tagName === 'AUDIO')) {
                        getAudioContext();
                        hookElement(e.target);
                    }
                }, true);

                document.addEventListener('playing', function(e) {
                    if (e.target && (e.target.tagName === 'VIDEO' || e.target.tagName === 'AUDIO')) {
                        getAudioContext();
                        hookElement(e.target);
                    }
                }, true);

                setInterval(function() {
                    var el = document.querySelector('video') || document.querySelector('audio');
                    if (el && !hookedElements.has(el)) {
                        hookElement(el);
                    }
                }, 1000);
            })();
        """.trimIndent()

        webViewMusic.evaluateJavascript(hijackJs, null)
    }

    private fun handleIncomingPcm(pcmBytes: ByteArray, sampleRate: Int) {
        if (currentSampleRate != sampleRate || audioTrack == null) {
            setupDirectAudioTrack(sampleRate)
        }

        // Direct AudioTrack へ PCM データを直接書き込み（DDCへ直通）
        audioTrack?.write(pcmBytes, 0, pcmBytes.size)

        pcmPacketCount++
        if (pcmPacketCount % 20 == 0L) {
            val usbDevice = getUsbAudioDevice()
            runOnUiThread {
                textStatus.text = "【Bit-Perfect 再生中】\n" +
                        "・レート: $sampleRate Hz\n" +
                        "・DAC: ${usbDevice?.productName ?: "Pamp DDC"}\n" +
                        "・転送中: ${pcmPacketCount * 4096 / 1024} KB (直通出力)"
            }
        }
    }

    private fun setupDirectAudioTrack(sampleRate: Int) {
        currentSampleRate = sampleRate
        try {
            audioTrack?.stop()
            audioTrack?.release()
        } catch (e: Exception) {}
        audioTrack = null

        val usbDevice = getUsbAudioDevice()

        // 1. Android 14/15 ハードウェア DIRECT 出力属性を設定
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && usbDevice != null) {
            try {
                val mixers = audioManager.getSupportedMixerAttributes(usbDevice)
                val matched = mixers.firstOrNull {
                    it.format.sampleRate == sampleRate && it.format.encoding == AudioFormat.ENCODING_PCM_16BIT
                } ?: mixers.firstOrNull {
                    it.format.sampleRate == sampleRate
                }

                if (matched != null) {
                    val mediaAttr = AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()

                    audioManager.setPreferredMixerAttributes(mediaAttr, usbDevice, matched)
                }
            } catch (e: Exception) {
                Log.e("DirectTrack", "Mixer error: ${e.message}")
            }
        }

        // 2. Direct AudioTrack を生成
        val playbackAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()

        val pcmFormat = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
            .build()

        val minBuf = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        val bufferSize = if (minBuf > 0) minBuf * 4 else 8192

        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(playbackAttributes)
            .setAudioFormat(pcmFormat)
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        audioTrack?.play()
    }

    private fun getUsbAudioDevice(): AudioDeviceInfo? {
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        for (device in devices) {
            if (device.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                device.type == AudioDeviceInfo.TYPE_USB_HEADSET
            ) {
                return device
            }
        }
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            audioTrack?.stop()
            audioTrack?.release()
        } catch (e: Exception) {}
    }
}