package de.foggymap.app.data

import android.content.Context
import de.foggymap.app.core.TravelMode
import java.security.SecureRandom

enum class BatteryMode(val label: String, val description: String) {
    PRECISE("Präzise", "Häufige GPS-Updates – am genauesten, höchster Verbrauch"),
    BALANCED("Ausgewogen", "Passt die GPS-Rate an dein Tempo an, pausiert GPS im Stillstand"),
    SAVER("Sparsam", "Positionen werden gesammelt und gebündelt geliefert, längere GPS-Pausen im Stillstand – ideal für lange Touren");
}

class Prefs(context: Context) {
    private val p = context.getSharedPreferences("foggymap", Context.MODE_PRIVATE)

    var onboardingDone: Boolean
        get() = p.getBoolean("onboarding_done", false)
        set(v) = p.edit().putBoolean("onboarding_done", v).apply()

    /** The user wants tracking to run (survives app restarts; the service restores it). */
    var trackingWanted: Boolean
        get() = p.getBoolean("tracking_wanted", false)
        set(v) = p.edit().putBoolean("tracking_wanted", v).apply()

    var batteryMode: BatteryMode
        get() = runCatching { BatteryMode.valueOf(p.getString("battery_mode", null) ?: "") }.getOrDefault(BatteryMode.BALANCED)
        set(v) = p.edit().putString("battery_mode", v.name).apply()

    var buildings3d: Boolean
        get() = p.getBoolean("buildings_3d", true)
        set(v) = p.edit().putBoolean("buildings_3d", v).apply()

    var fogAnimation: Boolean
        get() = p.getBoolean("fog_animation", true)
        set(v) = p.edit().putBoolean("fog_animation", v).apply()

    /** Exploration was running when a self-update started – remind the user to reopen the app. */
    var resumeAfterUpdate: Boolean
        get() = p.getBoolean("resume_after_update", false)
        set(v) = p.edit().putBoolean("resume_after_update", v).apply()

    /** Set automatically when hardware location batching turned out to lose fixes on this device. */
    var batchingUnreliable: Boolean
        get() = p.getBoolean("batching_unreliable", false)
        set(v) = p.edit().putBoolean("batching_unreliable", v).apply()

    var perspective: Boolean
        get() = p.getBoolean("perspective", true)
        set(v) = p.edit().putBoolean("perspective", v).apply()

    // ------------------------------------------------------------ what gets revealed

    var revealMode: de.foggymap.app.core.RevealMode
        get() = runCatching { de.foggymap.app.core.RevealMode.valueOf(p.getString("reveal_mode", "BLOCKS")!!) }.getOrDefault(de.foggymap.app.core.RevealMode.BLOCKS)
        set(v) = p.edit().putString("reveal_mode", v.name).apply()
    /** Strip width per side in the "Straßenzug" mode (m). */
    var stripWidth: Int
        get() = p.getInt("strip_width", 40)
        set(v) = p.edit().putInt("strip_width", v).apply()
    /** Radius around your path in the "Radius" mode (m). */
    var revealRadius: Int
        get() = p.getInt("reveal_radius", 60)
        set(v) = p.edit().putInt("reveal_radius", v).apply()

    fun revealSettings() = de.foggymap.app.core.RevealSettings(revealMode,
        (if (revealMode == de.foggymap.app.core.RevealMode.RADIUS) revealRadius else stripWidth).toDouble())

    // ------------------------------------------------------------ offline map area

    /** Centre/radius/time of the last completed background download of the surroundings. */
    var areaLat: Double
        get() = java.lang.Double.longBitsToDouble(p.getLong("area_lat", java.lang.Double.doubleToLongBits(Double.NaN)))
        set(v) = p.edit().putLong("area_lat", java.lang.Double.doubleToLongBits(v)).apply()
    var areaLon: Double
        get() = java.lang.Double.longBitsToDouble(p.getLong("area_lon", java.lang.Double.doubleToLongBits(Double.NaN)))
        set(v) = p.edit().putLong("area_lon", java.lang.Double.doubleToLongBits(v)).apply()
    var areaRadius: Double
        get() = p.getFloat("area_radius", 0f).toDouble()
        set(v) = p.edit().putFloat("area_radius", v.toFloat()).apply()
    var areaAt: Long
        get() = p.getLong("area_at", 0)
        set(v) = p.edit().putLong("area_at", v).apply()

    // ------------------------------------------------------------ coop

    var server: String
        get() = p.getString("server", "") ?: ""
        set(v) = p.edit().putString("server", v.trim().trimEnd('/')).apply()

    var groupCode: String
        get() = p.getString("group", "") ?: ""
        set(v) = p.edit().putString("group", v).apply()

    var groupName: String
        get() = p.getString("group_name", "") ?: ""
        set(v) = p.edit().putString("group_name", v).apply()

    var syncRevision: Long
        get() = p.getLong("sync_revision", 0)
        set(v) = p.edit().putLong("sync_revision", v).apply()

    var lastSyncMs: Long
        get() = p.getLong("last_sync", 0)
        set(v) = p.edit().putLong("last_sync", v).apply()

    var shareLiveLocation: Boolean
        get() = p.getBoolean("share_live", false)
        set(v) = p.edit().putBoolean("share_live", v).apply()

    val memberId: String
        get() = p.getString("member_id", null) ?: randomHex(16).also { p.edit().putString("member_id", it).apply() }

    /** Public, hashed member key the server derived from [memberId] (identifies "me" in member lists). */
    var memberKey: String
        get() = p.getString("member_key", "") ?: ""
        set(v) = p.edit().putString("member_key", v).apply()

    var nickname: String
        get() = p.getString("nickname", null) ?: "Entdecker ${memberId.take(4).uppercase()}"
        set(v) = p.edit().putString("nickname", v.trim().take(24)).apply()

    // ------------------------------------------------------------ statistics

    fun distance(mode: TravelMode): Double = p.getFloat("dist_${mode.name}", 0f).toDouble()
    fun addDistances(byMode: Map<TravelMode, Double>) {
        val e = p.edit()
        byMode.forEach { (m, d) -> e.putFloat("dist_${m.name}", (distance(m) + d).toFloat()) }
        e.apply()
    }

    var trackedSeconds: Long
        get() = p.getLong("tracked_seconds", 0)
        set(v) = p.edit().putLong("tracked_seconds", v).apply()

    var lastLat: Double
        get() = p.getFloat("last_lat", Float.NaN).toDouble()
        set(v) = p.edit().putFloat("last_lat", v.toFloat()).apply()
    var lastLon: Double
        get() = p.getFloat("last_lon", Float.NaN).toDouble()
        set(v) = p.edit().putFloat("last_lon", v.toFloat()).apply()

    fun resetStatistics() {
        val e = p.edit()
        TravelMode.entries.forEach { e.remove("dist_${it.name}") }
        e.remove("tracked_seconds").remove("sync_revision").apply()
    }

    companion object {
        fun randomHex(bytes: Int): String {
            val b = ByteArray(bytes).also { SecureRandom().nextBytes(it) }
            return b.joinToString("") { "%02x".format(it) }
        }
    }
}
