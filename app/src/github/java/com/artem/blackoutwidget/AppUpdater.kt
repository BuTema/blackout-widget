package com.artem.blackoutwidget

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Build installed by hand from GitHub: finds out whether the "Latest build" release is newer than this
 * build, and downloads and starts the installer for it. Every CI build replaces that release and names its
 * commit in the notes ("Automatic build of abc1234"), which is compared with the commit this app was built from.
 */
object AppUpdater {
    const val enabled = true

    private const val RELEASE = "https://api.github.com/repos/BuTema/blackout-widget/releases/tags/latest"
    private const val APK = "blackout-widget.apk"
    private const val CHECK_EVERY_MS = 60L * 60L * 1000L
    private const val MIME = "application/vnd.android.package-archive"

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("updates", Context.MODE_PRIVATE)

    /** The newer build, or null when this one is the latest, was postponed, or GitHub cannot be reached. */
    suspend fun check(ctx: Context): UpdateInfo? = withContext(Dispatchers.IO) {
        val current = BuildConfig.COMMIT
        if (current == "dev") return@withContext null // a local build has nothing to compare with
        val p = prefs(ctx)
        val now = System.currentTimeMillis()
        if (now - p.getLong("checked", 0L) >= CHECK_EVERY_MS) {
            try {
                val found = fetchLatest()
                if (found != null) {
                    p.edit().putString("build", found.build).putString("url", found.url)
                        .putInt("number", found.number ?: 0).putLong("checked", now).apply()
                }
            } catch (e: Exception) {
                // Offline or rate-limited: keep what was found last time and try again later.
            }
        }
        val build = p.getString("build", null)
        val url = p.getString("url", null)
        val number = p.getInt("number", 0).takeIf { it > 0 }
        if (build == null || url == null || build == p.getString("dismissed", null)) return@withContext null
        val info = UpdateInfo(build, url, number)
        if (isNewer(info)) info else null
    }

    /** The check behind the button in Settings: asks GitHub now and tells a failure apart from "up to date". */
    suspend fun checkNow(ctx: Context): UpdateResult = withContext(Dispatchers.IO) {
        val current = BuildConfig.COMMIT
        val p = prefs(ctx)
        val found = try {
            fetchLatest()
        } catch (e: Exception) {
            null
        } ?: return@withContext UpdateResult(null, failed = true)
        p.edit().putString("build", found.build).putString("url", found.url).putInt("number", found.number ?: 0)
            .putLong("checked", System.currentTimeMillis()).remove("dismissed").apply()
        UpdateResult(if (current == "dev" || !isNewer(found)) null else found)
    }

    /**
     * Newer means a higher build number than this one (the number of the CI run, which is also the version code),
     * so a release that is older or the same never prompts; without a number in the notes the commits are compared.
     */
    private fun isNewer(info: UpdateInfo): Boolean =
        if (info.number != null) info.number > BuildConfig.VERSION_CODE else info.build != BuildConfig.COMMIT

    private fun fetchLatest(): UpdateInfo? {
        val connection = URL(RELEASE).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("User-Agent", "blackout-widget")
            if (connection.responseCode != 200) return null
            val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            val build = Regex("build of ([0-9a-f]{7,40})").find(json.optString("body"))?.groupValues?.get(1) ?: return null
            val number = Regex("run (\\d+)").find(json.optString("body"))?.groupValues?.get(1)?.toIntOrNull()
            val assets = json.optJSONArray("assets") ?: return null
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                if (asset.optString("name") == APK) return UpdateInfo(build.take(7), asset.getString("browser_download_url"), number)
            }
            return null
        } finally {
            connection.disconnect()
        }
    }

    fun dismiss(ctx: Context, info: UpdateInfo) {
        prefs(ctx).edit().putString("dismissed", info.build).apply()
    }

    /** Downloads the APK and, when it is done, opens the system installer for it. */
    fun install(ctx: Context, info: UpdateInfo) {
        val app = ctx.applicationContext
        if (!app.packageManager.canRequestPackageInstalls()) {
            Toast.makeText(app, R.string.update_allow, Toast.LENGTH_LONG).show()
            app.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + app.packageName))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return
        }
        val manager = app.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val request = DownloadManager.Request(Uri.parse(info.url))
            .setTitle(app.getString(R.string.app_name))
            .setMimeType(MIME)
            // Progress while downloading only: no "download complete" notification, the installer opens by itself.
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationInExternalFilesDir(app, Environment.DIRECTORY_DOWNLOADS, "blackout-widget-${info.build}.apk")
        val id = manager.enqueue(request)
        Toast.makeText(app, R.string.update_downloading, Toast.LENGTH_SHORT).show()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L) != id) return
                app.unregisterReceiver(this)
                val file = manager.getUriForDownloadedFile(id) ?: return
                app.startActivity(
                    Intent(Intent.ACTION_VIEW)
                        .setDataAndType(file, MIME)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION),
                )
            }
        }
        ContextCompat.registerReceiver(app, receiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), ContextCompat.RECEIVER_EXPORTED)
    }
}
