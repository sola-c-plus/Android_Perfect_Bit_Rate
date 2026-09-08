package com.example.perfectbitrate

import android.app.Activity
import android.util.Base64
import android.util.Log
import org.json.JSONObject
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.WebExtension

class GeckoSessionController(
    private val activity: Activity,
    private val geckoView: GeckoView,
    private val listener: Listener
) {
    interface Listener {
        fun onFlush()
        fun onPcm(pcmBytes: ByteArray, inBitMode: String)
        fun onCodec(codec: String, rate: Int)
        fun onMetadata(title: String, artist: String, artworkUrl: String)
        fun onProgress(currentMs: Long, durationMs: Long, isPlaying: Boolean)
        fun onState(isPlaying: Boolean)
        fun isDarkTheme(): Boolean
        fun isAdBlockEnabled(): Boolean
    }

    private lateinit var geckoSession: GeckoSession
    private var geckoRuntime: GeckoRuntime? = null
    private var activePort: WebExtension.Port? = null

    var canGoBack: Boolean = false
        private set

    fun canGoBack(): Boolean = canGoBack

    fun goBack(): Boolean {
        if (canGoBack) {
            geckoSession.goBack()
            return true
        }
        return false
    }

    fun init() {
        val runtimeSettings = GeckoRuntimeSettings.Builder()
            .consoleOutput(true)
            .aboutConfigEnabled(true)
            .build()

        geckoRuntime = GeckoRuntime.getDefault(activity)

        val sessionSettings = GeckoSessionSettings.Builder()
            .usePrivateMode(false)
            .userAgentMode(GeckoSessionSettings.USER_AGENT_MODE_MOBILE)
            .viewportMode(GeckoSessionSettings.VIEWPORT_MODE_MOBILE)
            .allowJavascript(true)
            .build()

        geckoSession = GeckoSession(sessionSettings)
        geckoSession.setPriorityHint(GeckoSession.PRIORITY_HIGH)

        geckoSession.navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) {
                this@GeckoSessionController.canGoBack = canGoBack
            }
        }

        geckoSession.permissionDelegate = object : GeckoSession.PermissionDelegate {
            override fun onContentPermissionRequest(
                session: GeckoSession,
                perm: GeckoSession.PermissionDelegate.ContentPermission
            ): GeckoResult<Int>? {
                if (perm.permission == GeckoSession.PermissionDelegate.PERMISSION_AUTOPLAY_AUDIBLE ||
                    perm.permission == GeckoSession.PermissionDelegate.PERMISSION_AUTOPLAY_INAUDIBLE
                ) {
                    return GeckoResult.fromValue(GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW)
                }
                return null
            }
        }

        geckoRuntime?.let { runtime ->
            geckoSession.open(runtime)
            geckoView.setSession(geckoSession)
            geckoSession.setActive(true)
            geckoSession.setFocused(true)
            geckoSession.setPriorityHint(GeckoSession.PRIORITY_HIGH)

            val extensionLocation = "resource://android/assets/yt_capture_extension/"
            val extensionId = "yt_capture@example.com"

            val messageDelegate = object : WebExtension.MessageDelegate {
                override fun onMessage(nativeApp: String, message: Any, sender: WebExtension.MessageSender): GeckoResult<Any>? {
                    safeToJson(message)?.let { handleIncomingMessage(it) }
                    return GeckoResult.fromValue(JSONObject())
                }

                override fun onConnect(port: WebExtension.Port) {
                    activePort = port
                    sendAdBlock(listener.isAdBlockEnabled())
                    sendWebTheme(listener.isDarkTheme())

                    port.setDelegate(object : WebExtension.PortDelegate {
                        override fun onPortMessage(message: Any, port: WebExtension.Port) {
                            safeToJson(message)?.let { handleIncomingMessage(it) }
                        }
                        override fun onDisconnect(port: WebExtension.Port) {
                            if (activePort == port) activePort = null
                        }
                    })
                }
            }

            runtime.webExtensionController
                .ensureBuiltIn(extensionLocation, extensionId)
                .accept({ extension ->
                    if (extension != null) {
                        activity.runOnUiThread {
                            extension.setMessageDelegate(messageDelegate, "browser")
                            geckoSession.webExtensionController.setMessageDelegate(extension, messageDelegate, "browser")
                            geckoSession.loadUri("https://music.youtube.com")
                        }
                    }
                }, { e ->
                    Log.e("GeckoController", "WebExtension error", e)
                    activity.runOnUiThread {
                        geckoSession.loadUri("https://music.youtube.com")
                    }
                })
        }
    }

    fun sendCommand(command: String) {
        try {
            activePort?.postMessage(JSONObject().apply { put("command", command) })
        } catch (e: Exception) {
            Log.e("GeckoController", "sendCommand error: $command", e)
        }
    }

    fun sendSeek(positionMs: Long) {
        try {
            activePort?.postMessage(JSONObject().apply {
                put("command", "seek")
                put("position", positionMs)
            })
        } catch (e: Exception) {
            Log.e("GeckoController", "sendSeek error", e)
        }
    }

    fun sendWebTheme(isDark: Boolean) {
        try {
            activePort?.postMessage(JSONObject().apply {
                put("command", "setWebTheme")
                put("theme", if (isDark) "dark" else "light")
            })
        } catch (e: Exception) {}
    }

    fun sendAdBlock(enabled: Boolean) {
        try {
            activePort?.postMessage(JSONObject().apply {
                put("command", "setAdBlock")
                put("enabled", enabled)
            })
        } catch (e: Exception) {}
    }

    fun reload() {
        try {
            sendCommand("resume_audio")
        } catch (e: Exception) {}
        geckoSession.reload()
    }

    fun onPause() {
        geckoSession.setActive(true)
        geckoSession.setFocused(true)
        geckoSession.setPriorityHint(GeckoSession.PRIORITY_HIGH)
    }

    fun onStop() {
        geckoSession.setActive(true)
        geckoSession.setFocused(true)
        geckoSession.setPriorityHint(GeckoSession.PRIORITY_HIGH)
    }

    fun onDestroy() {
        activePort = null
        geckoSession.close()
    }

    private fun safeToJson(msg: Any?): JSONObject? {
        return when (msg) {
            is JSONObject -> msg
            is String -> try { JSONObject(msg) } catch (e: Exception) { null }
            is Map<*, *> -> try { JSONObject(msg) } catch (e: Exception) { null }
            null -> null
            else -> try { JSONObject(msg.toString()) } catch (e: Exception) { null }
        }
    }

    private fun handleIncomingMessage(msg: JSONObject) {
        when (msg.optString("type")) {
            "flush" -> listener.onFlush()
            "pcm" -> {
                val base64Pcm = msg.optString("pcm", "")
                if (base64Pcm.isNotEmpty()) {
                    val inBitMode = msg.optString("bitMode", "float32")
                    try {
                        val pcmBytes = Base64.decode(base64Pcm, Base64.NO_WRAP)
                        if (pcmBytes != null && pcmBytes.isNotEmpty()) {
                            listener.onPcm(pcmBytes, inBitMode)
                        }
                    } catch (e: Exception) {
                        Log.e("GeckoController", "PCM decode error", e)
                    }
                }
            }
            "codec" -> {
                val codec = msg.optString("codec", "OPUS 160kbps (48k)")
                val rate = msg.optInt("sampleRate", 0)
                listener.onCodec(codec, rate)
            }
            "meta" -> {
                val title = msg.optString("title", "YouTube Music")
                val artist = msg.optString("artist", "")
                val artwork = msg.optString("artwork", "")
                listener.onMetadata(title, artist, artwork)
            }
            "progress" -> {
                val current = msg.optLong("current", 0L)
                val duration = msg.optLong("duration", 0L)
                val isPlaying = msg.optBoolean("playing", false)
                listener.onProgress(current, duration, isPlaying)
            }
            "state" -> {
                val isPlaying = msg.optBoolean("playing", true)
                listener.onState(isPlaying)
            }
        }
    }
}