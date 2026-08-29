package com.example.perfectbitrate

import android.util.Log
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import kotlin.concurrent.thread

data class AudioStreamInfo(
    val url: String,
    val sampleRate: Int,
    val mimeType: String,
    val bitrate: Int
)

object YouTubeStreamHelper {

    private const val PLAYER_API_URL = "https://www.youtube.com/youtubei/v1/player?prettyPrint=false"
    const val USER_AGENT = "com.google.ios.youtube/19.29.1 (iPhone16,2; U; CPU iOS 17_5_1 like Mac OS X;)"

    fun fetchAudioStream(videoId: String, callback: (AudioStreamInfo?) -> Unit) {
        thread {
            // 1. まず最優先で暗号化のない iOS クライアントで試行
            var audioInfo = requestInnerTube(videoId, "IOS", "19.29.1")
            
            // 2. 失敗した場合は ANDROID_VR クライアントで試行
            if (audioInfo == null) {
                Log.w("BitPerfectYT", "iOS client failed, fallback to ANDROID_VR...")
                audioInfo = requestInnerTube(videoId, "ANDROID_VR", "1.60.19")
            }

            // 3. それでも失敗した場合は TV 組み込みクライアントで試行
            if (audioInfo == null) {
                Log.w("BitPerfectYT", "ANDROID_VR failed, fallback to TVHTML5...")
                audioInfo = requestInnerTube(videoId, "TVHTML5_SIMPLY_EMBEDDED_PLAYER", "2.0")
            }

            callback(audioInfo)
        }
    }

    private fun requestInnerTube(videoId: String, clientName: String, clientVersion: String): AudioStreamInfo? {
        try {
            val url = URL(PLAYER_API_URL)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.doOutput = true

            val requestJson = JSONObject().apply {
                put("videoId", videoId)
                put("context", JSONObject().apply {
                    put("client", JSONObject().apply {
                        put("clientName", clientName)
                        put("clientVersion", clientVersion)
                        put("deviceModel", if (clientName == "IOS") "iPhone16,2" else "Quest 3")
                        put("userAgent", USER_AGENT)
                        put("hl", "ja")
                        put("gl", "JP")
                    })
                    if (clientName.contains("TVHTML5")) {
                        put("thirdParty", JSONObject().apply {
                            put("embedUrl", "https://www.youtube.com")
                        })
                    }
                })
            }

            OutputStreamWriter(conn.outputStream).use { it.write(requestJson.toString()) }

            if (conn.responseCode == 200) {
                val responseStr = conn.inputStream.bufferedReader().use { it.readText() }
                val responseJson = JSONObject(responseStr)
                val streamingData = responseJson.optJSONObject("streamingData") ?: return null
                val adaptiveFormats = streamingData.optJSONArray("adaptiveFormats") ?: return null

                var bestAudio: AudioStreamInfo? = null
                var maxBitrate = 0

                for (i in 0 until adaptiveFormats.length()) {
                    val format = adaptiveFormats.getJSONObject(i)
                    val mimeType = format.optString("mimeType", "")

                    if (mimeType.startsWith("audio/")) {
                        var streamUrl = format.optString("url", "")
                        
                        // 生URLがない場合は signatureCipher から URL をパース
                        if (streamUrl.isEmpty()) {
                            val cipher = format.optString("signatureCipher", format.optString("cipher", ""))
                            if (cipher.isNotEmpty()) {
                                streamUrl = extractUrlFromCipher(cipher)
                            }
                        }

                        val sampleRate = format.optInt("audioSampleRate", 48000)
                        val bitrate = format.optInt("bitrate", 0)

                        if (streamUrl.isNotEmpty() && bitrate > maxBitrate) {
                            maxBitrate = bitrate
                            bestAudio = AudioStreamInfo(streamUrl, sampleRate, mimeType, bitrate)
                        }
                    }
                }
                if (bestAudio != null) {
                    Log.i("BitPerfectYT", "★ Stream Found: Rate=${bestAudio.sampleRate}Hz, Bitrate=${bestAudio.bitrate}, MIME=${bestAudio.mimeType}")
                    return bestAudio
                }
            } else {
                Log.e("BitPerfectYT", "HTTP Error $clientName: ${conn.responseCode}")
            }
        } catch (e: Exception) {
            Log.e("BitPerfectYT", "Error $clientName: ${e.message}")
        }
        return null
    }

    private fun extractUrlFromCipher(cipher: String): String {
        return try {
            val params = cipher.split("&")
            var url = ""
            for (p in params) {
                if (p.startsWith("url=")) {
                    url = URLDecoder.decode(p.substring(4), "UTF-8")
                    break
                }
            }
            url
        } catch (e: Exception) {
            ""
        }
    }
}