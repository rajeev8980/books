package app.box.suggest

import android.content.Context
import java.io.File

object CacheWiper {
    fun wipe(context: Context) {
        context.cacheDir.wipeChildren()
        context.externalCacheDir?.wipeChildren()
        context.databaseList().forEach { name -> context.deleteDatabase(name) }
        val data = context.dataDir
        for (name in listOf("app_webview", "app_webview_cache")) {
            val root = File(data, name)
            for (child in listOf("Cache", "Code Cache", "GPUCache", "Service Worker")) {
                File(root, child).deleteRecursively()
                File(File(root, "Default"), child).deleteRecursively()
            }
        }
    }
}

private fun File.wipeChildren() {
    listFiles()?.forEach { child -> child.deleteRecursively() }
}
