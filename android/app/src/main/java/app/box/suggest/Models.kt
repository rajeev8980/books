package app.box.suggest

object Limits {
    const val VANISH_MS = 3_000L
    const val MAX_MEDIA_BYTES = 8 * 1024 * 1024
    const val MAX_TEXT = 2000
}

enum class Phase {
    Connecting,
    InRoom,
    Rejected,
    Offline,
}

enum class Kind {
    Text,
    Image,
    Gif,
}

class Suggestion(
    val id: String,
    val mine: Boolean,
    val kind: Kind,
    val text: String?,
    val bytes: ByteArray?,
    val mime: String?,
)

data class BoxUiState(
    val phase: Phase = Phase.Connecting,
    val occupancy: Int = 0,
    val notice: String? = null,
    val banner: String? = null,
    val yours: Suggestion? = null,
    val theirs: Suggestion? = null,
    val busy: Boolean = false,
    val serverLabel: String = "",
) {
    val canDrop: Boolean
        get() = phase == Phase.InRoom && yours == null && !busy
}

object Urls {
    fun webSocket(httpBase: String): String {
        val base = httpBase.trim().trimEnd('/')
        val ws = when {
            base.startsWith("https://") -> "wss://" + base.removePrefix("https://")
            base.startsWith("http://") -> "ws://" + base.removePrefix("http://")
            base.startsWith("ws://") || base.startsWith("wss://") -> base
            else -> "ws://$base"
        }
        return if (ws.endsWith("/ws")) ws else "$ws/ws"
    }

    fun media(httpBase: String): String = httpBase.trim().trimEnd('/') + "/media"

    fun label(httpBase: String): String =
        httpBase.trim().removePrefix("https://").removePrefix("http://").trimEnd('/')
}
