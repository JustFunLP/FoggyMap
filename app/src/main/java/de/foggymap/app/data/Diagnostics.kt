package de.foggymap.app.data

import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.os.Build
import android.os.PowerManager
import android.os.Process
import androidx.core.content.FileProvider
import de.foggymap.app.App
import de.foggymap.app.BuildConfig
import de.foggymap.app.core.ReportSanitizer
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Problem reports and crash reports.
 *
 *  - A crash of the app code is written to a file before the app dies; native crashes (e.g. in the map
 *    graphics) and "app not responding" are taken from Android's list of exit reasons (Android 11+).
 *    On the next start FoggyMap offers to share a report.
 *  - The report contains device, app version, settings, GPS quality and battery of the tour, map and
 *    group status and the app's own log – cleaned of positions, tile numbers, street and friend names,
 *    the group code and keys (see ReportSanitizer). The user sees everything before sharing.
 */
object Diagnostics {
    data class Problem(val timeMs: Long, val what: String, val details: String)

    /** Live statistics of the running tour (updated by the tracking service about every minute). */
    val tour = MutableStateFlow<JSONObject?>(null)

    private fun dir(c: Context) = File(c.filesDir, "diag").apply { mkdirs() }
    private fun crashFile(c: Context) = File(dir(c), "crash.txt")
    private val stamp get() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.GERMANY)

    fun install(app: Application) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching {
                val sw = java.io.StringWriter()
                e.printStackTrace(java.io.PrintWriter(sw))
                crashFile(app).writeText("${System.currentTimeMillis()}\n${stamp.format(Date())} · Thread ${thread.name}\n" +
                    sw.toString().lineSequence().take(80).joinToString("\n") + "\n\n--- Protokoll bis zum Absturz ---\n" +
                    ownLog(400).joinToString("\n"))
            }
            previous?.uncaughtException(thread, e)
        }
    }

    /** Something went wrong last time that was not yet reported or dismissed. */
    fun pendingProblem(c: Context): Problem? {
        val ack = App.prefs.exitAcknowledged
        crashFile(c).takeIf { it.exists() }?.let { f ->
            val lines = f.readLines()
            val t = lines.firstOrNull()?.toLongOrNull() ?: f.lastModified()
            if (t > ack) return Problem(t, "Absturz", lines.drop(1).joinToString("\n"))
        }
        // Being closed by the system for memory in the background is normal – only crashes and freezes count.
        return exitReasons(c).firstOrNull { it.timeMs > ack && !it.what.startsWith("Vom System") }
    }

    fun acknowledge(c: Context) {
        val newest = maxOf(crashFile(c).takeIf { it.exists() }?.lastModified() ?: 0L, exitReasons(c).maxOfOrNull { it.timeMs } ?: 0L,
            System.currentTimeMillis())
        App.prefs.exitAcknowledged = newest
    }

    /** Crashes and freezes from Android's exit history (newest first). */
    fun exitReasons(c: Context): List<Problem> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return emptyList()
        val am = c.getSystemService(ActivityManager::class.java) ?: return emptyList()
        return runCatching { am.getHistoricalProcessExitReasons(c.packageName, 0, 8) }.getOrDefault(emptyList()).mapNotNull { info ->
            val what = when (info.reason) {
                ApplicationExitInfo.REASON_CRASH -> "Absturz"
                ApplicationExitInfo.REASON_CRASH_NATIVE -> "Absturz in der Kartengrafik/Systembibliothek"
                ApplicationExitInfo.REASON_ANR -> "App reagierte nicht"
                ApplicationExitInfo.REASON_LOW_MEMORY -> "Vom System beendet (wenig Speicher)"
                else -> return@mapNotNull null
            }
            val trace = if (info.reason == ApplicationExitInfo.REASON_ANR) runCatching {
                info.traceInputStream?.bufferedReader()?.useLines { it.take(60).joinToString("\n") }
            }.getOrNull().orEmpty() else ""
            Problem(info.timestamp, what, "${info.description.orEmpty()} (Wichtigkeit ${info.importance})" + if (trace.isNotEmpty()) "\n$trace" else "")
        }
    }

    /** The app's own log (Android lets every app read its own lines, no permission needed). */
    fun ownLog(maxLines: Int = 600): List<String> = runCatching {
        val p = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-v", "time", "--pid=${Process.myPid()}", "-t", maxLines.toString()))
        p.inputStream.bufferedReader().readLines().also { p.destroy() }
    }.getOrDefault(listOf("(Protokoll nicht lesbar)"))

    // ------------------------------------------------------------------ the report

    suspend fun buildReport(c: Context, description: String, includeLog: Boolean, problem: Problem?): String {
        val prefs = App.prefs
        val code = prefs.groupCode
        fun clean(s: String) = ReportSanitizer.clean(s, code)
        val sb = StringBuilder()
        fun section(t: String) { sb.append("\n== ").append(t).append(" ==\n") }
        fun line(k: String, v: Any?) { sb.append(k).append(": ").append(v).append('\n') }

        sb.append("FoggyMap-Problembericht\n")
        line("Erstellt", stamp.format(Date()))
        line("Beschreibung", description.ifBlank { "(keine)" })

        section("App & Gerät")
        line("Version", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})${if (BuildConfig.DEBUG) " debug" else ""}")
        line("Gerät", "${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
        line("Android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        line("Bildschirm", c.resources.displayMetrics.let { "${it.widthPixels}×${it.heightPixels}, Dichte ${it.density}" })
        val lm = c.getSystemService(LocationManager::class.java)
        line("Standort an", runCatching { androidx.core.location.LocationManagerCompat.isLocationEnabled(lm) }.getOrNull())
        line("Fused-Anbieter", Build.VERSION.SDK_INT >= 31 && lm.allProviders.contains(LocationManager.FUSED_PROVIDER))
        line("Genauer Standort erlaubt", de.foggymap.app.MainActivity.hasFineLocation(c))
        line("Benachrichtigungen erlaubt", Build.VERSION.SDK_INT < 33 ||
            c.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED)
        line("Akku-Optimierung aus", c.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(c.packageName))
        line("Energiesparmodus", c.getSystemService(PowerManager::class.java)?.isPowerSaveMode)
        line("Hardware-Bündelung", if (prefs.batchingUnreliable) "abgeschaltet (lieferte nichts)" else "erlaubt")

        section("Einstellungen")
        line("Aufdecken", "${prefs.revealMode} · Breite ${prefs.stripWidth} m · Radius ${prefs.revealRadius} m")
        line("Ansicht", "3D-Gebäude ${prefs.buildings3d} · Animationen ${prefs.fogAnimation} · 3D ${prefs.perspective} · Norden oben ${prefs.northUp}")
        val sync = App.sync.state.value
        line("Koop", if (!sync.inGroup) "keine Gruppe" else "in Gruppe (${sync.members.size} Mitglieder) über " +
            (if (App.sync.usesNostr) "Relays" else "eigenen Server") + " · Live-Standort ${prefs.shareLiveLocation}")
        if (sync.inGroup) line("Sync", "${sync.status} · ${clean(sync.message)} · zuletzt ${if (sync.lastSync > 0) stamp.format(Date(sync.lastSync)) else "nie"} · " +
            "${sync.pendingUploads} warten · Live-Verbindungen ${App.live.connectedRelays}")
        line("Abzeichen", App.badges.earned.value.joinToString { it.id }.ifEmpty { "keine" })

        section("Erkundung")
        val t = App.tracking.value
        line("Läuft", t.active)
        if (t.active) line("Jetzt", "±${t.accuracy?.toInt() ?: "?"} m · ${t.mode} · ${t.powerProfile.ifEmpty { "-" }} · Karte lädt ${t.loadingMap}" +
            (t.message?.let { " · Hinweis: ${clean(it)}" } ?: ""))
        (tour.value ?: prefs.lastTour.takeIf { it.isNotEmpty() }?.let { runCatching { JSONObject(it) }.getOrNull() })?.let { s ->
            line(if (tour.value != null) "Diese Tour" else "Letzte Tour", s.toString())
        }
        val rate = if (prefs.batteryHours >= 0.15) "%.1f %%/h über %.1f h".format(Locale.GERMANY, prefs.batteryPct / prefs.batteryHours, prefs.batteryHours) else "noch zu wenig gemessen"
        val live = if (prefs.batteryLiveHours >= 0.15) "%.1f %%/h über %.1f h".format(Locale.GERMANY, prefs.batteryLivePct / prefs.batteryLiveHours, prefs.batteryLiveHours) else "-"
        line("Akku beim Erkunden (ganzes Handy)", "$rate · mit Live-Standort $live")

        section("Karte & Daten")
        val (n, bytes) = runCatching { App.tiles.cacheInfo() }.getOrDefault(0 to 0L)
        line("Kartenkacheln gespeichert", "$n (${bytes / 1_000_000} MB)")
        runCatching { App.db.counts() }.getOrNull()?.let { line("Funde", "${it.total} gesamt · ${it.roads} Abschnitte (${"%.1f".format(Locale.GERMANY, it.roadKm)} km) · ${it.blocks} Blöcke · ${it.buildings} Gebäude") }

        section("Abstürze")
        problem?.let { line("Gemeldet", "${stamp.format(Date(it.timeMs))} · ${it.what}"); sb.append(clean(it.details)).append('\n') }
        val exits = exitReasons(c)
        if (exits.isEmpty()) sb.append("keine bekannt\n")
        else exits.take(5).forEach { sb.append("${stamp.format(Date(it.timeMs))} · ${it.what} · ${clean(it.details.lineSequence().first())}\n") }

        if (includeLog) {
            section("Protokoll (ohne Orte, Straßen- und Personennamen, Codes)")
            ownLog(500).forEach { sb.append(clean(it)).append('\n') }
        }
        return sb.toString()
    }

    /** Share sheet with the report as a text file (and a short summary as text). */
    fun shareIntent(c: Context, report: String): Intent {
        val dir = File(c.cacheDir, "diag").apply { mkdirs() }
        val f = File(dir, "FoggyMap-Bericht-${SimpleDateFormat("yyyyMMdd-HHmm", Locale.GERMANY).format(Date())}.txt")
        f.writeText(report)
        val uri = FileProvider.getUriForFile(c, "${c.packageName}.files", f)
        val send = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, "FoggyMap-Problembericht ${BuildConfig.VERSION_NAME}")
            .putExtra(Intent.EXTRA_TEXT, report.lineSequence().take(4).joinToString("\n"))
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return Intent.createChooser(send, "Bericht teilen")
    }

    /** After a report went out, the crash that caused it no longer needs a prompt. */
    fun clearCrash(c: Context) { crashFile(c).delete(); acknowledge(c) }
}
