package app.box.suggest

import android.content.Context

object ServerAddress {
    private const val PREFS = "box_settings"
    private const val KEY = "server_url"

    fun load(context: Context): String {
        val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, null)
            ?.trim()
            .orEmpty()
        return saved.ifBlank { BuildConfig.SERVER_URL }
    }

    fun save(context: Context, url: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, url)
            .commit()
    }

    fun normalize(raw: String): String? {
        var text = raw.trim()
        if (text.isEmpty()) return null
        if (!text.contains("://")) text = "http://$text"
        val scheme = text.substringBefore("://").lowercase()
        if (scheme != "http" && scheme != "https") return null
        val rest = text.substringAfter("://").trim().trimEnd('/')
        if (rest.isBlank() || rest.startsWith("/")) return null
        return "$scheme://$rest"
    }
}
