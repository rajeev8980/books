package app.box.suggest

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Dns
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import android.util.Base64

sealed class SessionEvent {
    data class Welcome(val you: String, val occupancy: Int) : SessionEvent()
    data class Presence(val occupancy: Int) : SessionEvent()
    data class Rejected(val message: String) : SessionEvent()
    data class Incoming(
        val id: String,
        val sender: String,
        val kind: Kind,
        val text: String?,
        val bytes: ByteArray?,
        val mime: String?,
    ) : SessionEvent()
    data class Failed(val message: String) : SessionEvent()
    data object Offline : SessionEvent()
}

class BoxSession(
    var httpBase: String,
    private val scope: CoroutineScope,
    private val onEvent: (SessionEvent) -> Unit,
) {
    private val dns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            val all = Dns.SYSTEM.lookup(hostname)
            val v4 = all.filterIsInstance<Inet4Address>()
            return if (v4.isEmpty()) all else v4 + all.filter { it !is Inet4Address }
        }
    }

    private val http = OkHttpClient.Builder()
        .dns(dns)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .pingInterval(10, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val wakeHttp = http.newBuilder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(70, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .callTimeout(75, TimeUnit.SECONDS)
        .pingInterval(0, TimeUnit.MILLISECONDS)
        .build()

    private val generation = AtomicInteger(0)

    @Volatile private var socket: WebSocket? = null
    @Volatile private var wakeCall: Call? = null
    @Volatile private var clientId: String? = null
    @Volatile private var rejected = false

    fun connect() {
        socket?.cancel()
        wakeCall?.cancel()
        val gen = generation.incrementAndGet()
        rejected = false
        scope.launch(Dispatchers.IO) {
            if (!awaitServer(gen)) {
                if (gen == generation.get()) emit(SessionEvent.Offline)
                return@launch
            }
            if (gen != generation.get()) return@launch
            val request = Request.Builder().url(Urls.webSocket(httpBase)).build()
            socket = http.newWebSocket(request, Listener(gen))
        }
    }

    private fun awaitServer(gen: Int): Boolean {
        val request = Request.Builder()
            .url(httpBase.trim().trimEnd('/') + "/health")
            .header("Cache-Control", "no-cache")
            .build()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(75)
        while (gen == generation.get() && System.nanoTime() < deadline) {
            val call = wakeHttp.newCall(request)
            wakeCall = call
            try {
                call.execute().use { response ->
                    if (response.isSuccessful) return gen == generation.get()
                }
            } catch (_: IOException) {
                if (gen != generation.get()) return false
            }
            val left = deadline - System.nanoTime()
            if (left <= 0L || gen != generation.get()) return false
            try {
                Thread.sleep(minOf(400L, TimeUnit.NANOSECONDS.toMillis(left)))
            } catch (_: InterruptedException) {
                return false
            }
        }
        return false
    }

    fun sendText(id: String, text: String) {
        val payload = JSONObject()
            .put("type", "text")
            .put("id", id)
            .put("text", text)
            .toString()
        val sent = socket?.send(payload) ?: false
        if (!sent) {
            emit(SessionEvent.Failed("Couldn't share that one."))
        }
    }

    fun upload(id: String, bytes: ByteArray, filename: String, mime: String) {
        val me = clientId
        if (me == null) {
            emit(SessionEvent.Failed("You're not in the box yet."))
            return
        }
        scope.launch(Dispatchers.IO) {
            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("id", id)
                .addFormDataPart("file", filename, bytes.toRequestBody(mime.toMediaType()))
                .build()
            val request = Request.Builder()
                .url(Urls.media(httpBase))
                .header("X-Client-Id", me)
                .post(body)
                .build()
            try {
                http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        emit(SessionEvent.Failed(detail(response.body?.string())))
                    }
                }
            } catch (_: IOException) {
                emit(SessionEvent.Failed("Couldn't share that one."))
            }
        }
    }

    fun disconnect() {
        generation.incrementAndGet()
        rejected = false
        clientId = null
        wakeCall?.cancel()
        wakeCall = null
        socket?.cancel()
        socket = null
    }

    fun close() {
        disconnect()
        http.dispatcher.executorService.shutdown()
        http.connectionPool.evictAll()
        wakeHttp.dispatcher.executorService.shutdown()
        wakeHttp.connectionPool.evictAll()
    }

    private fun emit(event: SessionEvent) {
        scope.launch(Dispatchers.Main.immediate) { onEvent(event) }
    }

    private inner class Listener(private val gen: Int) : WebSocketListener() {
        private val once = AtomicBoolean(false)

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (text.contains("\"rejected\"")) rejected = true
            scope.launch(Dispatchers.IO) {
                if (gen != generation.get()) return@launch
                val event = parse(text) ?: return@launch
                if (event is SessionEvent.Rejected) rejected = true
                if (gen != generation.get()) return@launch
                withContext(Dispatchers.Main.immediate) {
                    if (gen == generation.get()) onEvent(event)
                }
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            fail()
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            fail()
        }

        private fun fail() {
            if (gen != generation.get() || rejected) return
            if (!once.compareAndSet(false, true)) return
            emit(SessionEvent.Offline)
        }
    }

    private fun parse(text: String): SessionEvent? {
        val json = try {
            JSONObject(text)
        } catch (_: Exception) {
            return SessionEvent.Failed("That wasn't a suggestion.")
        }
        return when (json.optString("type")) {
            "welcome" -> {
                val you = json.optString("you")
                clientId = you
                SessionEvent.Welcome(you, json.optInt("occupancy", 1))
            }
            "presence" -> SessionEvent.Presence(json.optInt("occupancy", 1))
            "rejected" -> SessionEvent.Rejected(
                json.optString("message").ifBlank { "This room already has four people." },
            )
            "error" -> SessionEvent.Failed(json.optString("message").ifBlank { "Couldn't share that one." })
            "suggestion" -> parseSuggestion(json)
            else -> null
        }
    }

    private fun parseSuggestion(json: JSONObject): SessionEvent? {
        val id = json.optString("id")
        val sender = json.optString("sender")
        if (id.isBlank() || sender.isBlank()) return null
        val kind = when (json.optString("kind")) {
            "gif" -> Kind.Gif
            "image" -> Kind.Image
            else -> Kind.Text
        }
        val bytes = if (json.has("data") && !json.isNull("data")) {
            try {
                Base64.decode(json.getString("data"), Base64.DEFAULT)
            } catch (_: IllegalArgumentException) {
                null
            }
        } else {
            null
        }
        val body = if (json.has("text") && !json.isNull("text")) json.optString("text") else null
        val mime = if (json.has("mime") && !json.isNull("mime")) json.optString("mime") else null
        if (kind == Kind.Text && body.isNullOrBlank()) return null
        if (kind != Kind.Text && (bytes == null || bytes.isEmpty())) return null
        return SessionEvent.Incoming(id, sender, kind, body, bytes, mime)
    }
}

private fun detail(body: String?): String {
    if (body.isNullOrBlank()) return "Couldn't share that one."
    return try {
        JSONObject(body).optString("detail").ifBlank { "Couldn't share that one." }
    } catch (_: Exception) {
        "Couldn't share that one."
    }
}
