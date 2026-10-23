package de.foggymap.app.data

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import de.foggymap.app.App
import de.foggymap.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Self-update from the GitHub release "apk-latest" of this repository.
 *
 * The CI publishes FoggyMap.apk plus version.json ({versionCode, versionName, sha256}). On start the
 * app compares versionCode, downloads the APK, checks the SHA-256 and hands it to Android's
 * PackageInstaller. Android only accepts it if it is signed with the same key as the installed app.
 * The first update needs the user's confirmation (and "install unknown apps" for FoggyMap); from
 * Android 12 on, later updates can install without a prompt because FoggyMap is then the installer.
 */
class Updater(private val context: Context) {
    sealed class State {
        data object Idle : State()
        data object Checking : State()
        data object UpToDate : State()
        data class Available(val versionName: String, val versionCode: Int) : State()
        data class Downloading(val percent: Int) : State()
        data object Installing : State()
        data class NeedsPermission(val versionName: String) : State()
        data class Failed(val message: String, val fromInstall: Boolean = false) : State()
    }

    val state = MutableStateFlow<State>(State.Idle)
    private val prefs = context.getSharedPreferences("foggymap_update", Context.MODE_PRIVATE)
    private val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true).build()
    private var latest: JSONObject? = null

    val enabled get() = BuildConfig.UPDATE_BASE.isNotEmpty() && !BuildConfig.DEBUG

    /**
     * Checks at most once per [minIntervalMs]; returns true when a newer version exists.
     * [remember] = false for the background check, so the app still checks itself when it is opened.
     */
    suspend fun check(force: Boolean = false, minIntervalMs: Long = 30 * 60_000L, remember: Boolean = true): Boolean = withContext(Dispatchers.IO) {
        if (!enabled) return@withContext false
        val now = System.currentTimeMillis()
        if (!force && now - prefs.getLong("last_check", 0) < minIntervalMs) {
            return@withContext state.value is State.Available
        }
        if (remember) prefs.edit().putLong("last_check", now).apply()
        state.value = State.Checking
        try {
            val req = Request.Builder().url(BuildConfig.UPDATE_BASE + "version.json?t=$now")
                .header("Cache-Control", "no-cache").build()
            client.newCall(req).execute().use { rsp ->
                if (rsp.code == 404) {
                    state.value = State.Failed("Keine Update-Info gefunden – ist das GitHub-Repository öffentlich?")
                    return@withContext false
                }
                if (!rsp.isSuccessful) throw java.io.IOException("HTTP ${rsp.code}")
                val o = JSONObject(rsp.body!!.string())
                val code = o.getInt("versionCode")
                latest = o
                return@withContext if (code > BuildConfig.VERSION_CODE) {
                    state.value = State.Available(o.optString("versionName", "$code"), code); true
                } else { state.value = State.UpToDate; false }
            }
        } catch (e: Exception) {
            Log.w(TAG, "update check failed", e)
            state.value = State.Failed("Update-Prüfung fehlgeschlagen: ${e.message}")
            false
        }
    }

    /** Downloads and installs the available update. */
    suspend fun install() = withContext(Dispatchers.IO) {
        val info = latest ?: return@withContext
        val versionName = info.optString("versionName")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            state.value = State.NeedsPermission(versionName)
            return@withContext
        }
        try {
            val file = File(context.cacheDir, "update.apk")
            val req = Request.Builder().url(BuildConfig.UPDATE_BASE + "FoggyMap.apk").build()
            val digest = MessageDigest.getInstance("SHA-256")
            client.newCall(req).execute().use { rsp ->
                if (!rsp.isSuccessful) throw java.io.IOException("Download HTTP ${rsp.code}")
                val body = rsp.body!!
                val total = body.contentLength()
                var done = 0L
                body.byteStream().use { input ->
                    file.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n); digest.update(buf, 0, n); done += n
                            if (total > 0) state.value = State.Downloading((done * 100 / total).toInt())
                        }
                    }
                }
            }
            val sha = digest.digest().joinToString("") { "%02x".format(it) }
            val expected = info.optString("sha256")
            if (expected.isNotEmpty() && !expected.equals(sha, ignoreCase = true)) {
                file.delete()
                throw java.io.IOException("Prüfsumme stimmt nicht – Download beschädigt")
            }
            state.value = State.Installing
            val installer = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(context.packageName)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
            val id = installer.createSession(params)
            installer.openSession(id).use { session ->
                session.openWrite("FoggyMap.apk", 0, file.length()).use { out ->
                    file.inputStream().use { it.copyTo(out) }
                    session.fsync(out)
                }
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
                val pi = PendingIntent.getBroadcast(context, id, Intent(context, InstallResultReceiver::class.java), flags)
                session.commit(pi.intentSender)
            }
            // Make sure exploration resumes automatically after the update restarted the app.
            App.prefs.resumeAfterUpdate = App.prefs.trackingWanted
        } catch (e: Exception) {
            Log.w(TAG, "update failed", e)
            state.value = State.Failed("Update fehlgeschlagen: ${e.message}", fromInstall = true)
        }
    }

    fun permissionIntent(): Intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun dismiss() { state.value = State.Idle }

    companion object { const val TAG = "FoggyUpdate" }
}

/** Receives the PackageInstaller result; shows Android's confirmation screen when it is required. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                    else intent.getParcelableExtra(Intent.EXTRA_INTENT)
                confirm?.let { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
            PackageInstaller.STATUS_SUCCESS -> Log.i(Updater.TAG, "update installed")
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "Status $status"
                Log.w(Updater.TAG, "install failed: $msg")
                App.updater.state.value = Updater.State.Failed("Installation abgebrochen: $msg", fromInstall = true)
            }
        }
    }
}

/** After a self-update the process was restarted: tell the user to reopen FoggyMap to keep exploring. */
class UpdatedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val prefs = Prefs(context)
        if (!prefs.resumeAfterUpdate && !prefs.trackingWanted) return
        prefs.resumeAfterUpdate = false
        de.foggymap.app.tracking.TrackingService.createChannel(context)
        val open = PendingIntent.getActivity(context, 7,
            Intent(context, de.foggymap.app.MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = androidx.core.app.NotificationCompat.Builder(context, de.foggymap.app.tracking.TrackingService.CHANNEL)
            .setSmallIcon(de.foggymap.app.R.drawable.ic_stat_fog)
            .setContentTitle("FoggyMap wurde aktualisiert")
            .setContentText("Tippen, um die Erkundung fortzusetzen")
            .setContentIntent(open).setAutoCancel(true).build()
        runCatching { androidx.core.app.NotificationManagerCompat.from(context).notify(18, n) }
    }
}
