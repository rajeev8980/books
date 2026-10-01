package app.box.suggest

import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

object AppUpdate {
    private val checking = AtomicBoolean(false)
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    @Volatile private var awaitingPermission = false
    @Volatile private var offeredVersion = 0L

    fun start(activity: ComponentActivity) {
        if (awaitingPermission) return
        if (!checking.compareAndSet(false, true)) return
        activity.lifecycleScope.launch(Dispatchers.IO) {
            try {
                check(activity)
            } finally {
                checking.set(false)
            }
        }
    }

    fun onResume(activity: ComponentActivity) {
        if (!awaitingPermission) return
        awaitingPermission = false
        start(activity)
    }

    private suspend fun check(activity: ComponentActivity) {
        val remote = fetchVersionCode() ?: return
        val local = installedVersion(activity)
        if (remote <= local || remote == offeredVersion) return
        if (Build.VERSION.SDK_INT >= 26 && !activity.packageManager.canRequestPackageInstalls()) {
            awaitingPermission = true
            val settings = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${activity.packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            activity.runOnUiThread { activity.startActivity(settings) }
            return
        }
        if (install(activity)) offeredVersion = remote
    }

    private suspend fun fetchVersionCode(): Long? {
        val request = Request.Builder()
            .url(BuildConfig.SERVER_URL + "/app/version")
            .header("Cache-Control", "no-cache")
            .build()
        repeat(6) { attempt ->
            try {
                http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use
                    val body = response.body?.string().orEmpty()
                    val code = JSONObject(body).optLong("versionCode", 0L)
                    if (code > 0L) return code
                }
            } catch (_: Exception) {
            }
            if (attempt < 5) delay(2000)
        }
        return null
    }

    private fun installedVersion(activity: ComponentActivity): Long {
        val info = activity.packageManager.getPackageInfo(activity.packageName, 0)
        return if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
    }

    private fun install(activity: ComponentActivity): Boolean {
        val request = Request.Builder()
            .url(BuildConfig.SERVER_URL + "/app/suggestion-box.apk")
            .header("Cache-Control", "no-cache")
            .build()
        val file = java.io.File(activity.cacheDir, "update.apk")
        try {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return false
                val body = response.body ?: return false
                file.outputStream().use { output -> body.byteStream().use { it.copyTo(output) } }
            }
            if (file.length() < 1000L) return false
            if (commit(activity, file, silent = Build.VERSION.SDK_INT >= 31)) return true
            return commit(activity, file, silent = false)
        } catch (_: Exception) {
            return false
        } finally {
            file.delete()
        }
    }

    private fun commit(activity: ComponentActivity, file: java.io.File, silent: Boolean): Boolean {
        val installer = activity.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(activity.packageName)
            setSize(file.length())
            if (silent && Build.VERSION.SDK_INT >= 31) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }
        var session: PackageInstaller.Session? = null
        return try {
            val sessionId = installer.createSession(params)
            session = installer.openSession(sessionId)
            session.openWrite("base.apk", 0, file.length()).use { output ->
                file.inputStream().use { input -> input.copyTo(output) }
                session.fsync(output)
            }
            val flags = if (Build.VERSION.SDK_INT >= 31) {
                android.app.PendingIntent.FLAG_MUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
            } else {
                android.app.PendingIntent.FLAG_UPDATE_CURRENT
            }
            val pending = android.app.PendingIntent.getBroadcast(
                activity,
                sessionId,
                Intent(activity, UpdateReceiver::class.java),
                flags,
            )
            session.commit(pending.intentSender)
            true
        } catch (_: Exception) {
            try {
                session?.abandon()
            } catch (_: Exception) {
            }
            false
        } finally {
            try {
                session?.close()
            } catch (_: Exception) {
            }
        }
    }
}
