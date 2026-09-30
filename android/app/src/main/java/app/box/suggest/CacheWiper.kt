package app.box.suggest

import android.content.Context
import java.io.File

object CacheWiper {
    fun wipe(context: Context) {
        context.cacheDir.wipeChildren()
        context.externalCacheDir?.wipeChildren()
        context.databaseList().forEach { name -> context.deleteDatabase(name) }
    }
}

private fun File.wipeChildren() {
    listFiles()?.forEach { child -> child.deleteRecursively() }
}
