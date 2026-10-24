package de.foggymap.app.data

import android.content.Context
import de.foggymap.app.core.TravelMode
import java.security.SecureRandom

class Prefs(context: Context) {
    private val p = context.getSharedPreferences("foggymap", Context.MODE_PRIVATE)

    var onboardingDone: Boolean
        get() = p.getBoolean("onboarding_done", false)
        set(v) = p.edit().putBoolean("onboarding_done", v).apply()

    /** The user wants tracking to run (survives app restarts; the service restores it). */
    var trackingWanted: Boolean
        get() = p.getBoolean("tracking_wanted", false)
        set(v) = p.edit().putBoolean("tracking_wanted", v).apply()

    /** Places where you often stand still (see RestSpots); only on this phone. */
    var restSpots: String
        get() = p.getString("rest_spots", "") ?: ""
        set(v) = p.edit().putString("rest_spots", v).apply()

    /** Badges this player has (ids) and the ones already celebrated with the big animation. */
    var badgesEarned: Set<String>
        get() = p.getStringSet("badges_earned", emptySet())?.toSet() ?: emptySet()
        set(v) = p.edit().putStringSet("badges_earned", v).apply()
    var badgesCelebrated: Set<String>
        get() = p.getStringSet("badges_celebrated", emptySet())?.toSet() ?: emptySet()
        set(v) = p.edit().putStringSet("badges_celebrated", v).apply()
    /** Badges the admin defined in the app (JSON array cache). */
    var badgeDefs: String
        get() = p.getString("badge_defs", "[]") ?: "[]"
        set(v) = p.edit().putString("badge_defs", v).apply()
    /** Only on the admin's phone, after entering the admin code: the key that signs awards. */
    var adminSecret: String
        get() = p.getString("admin_secret", "") ?: ""
        set(v) = p.edit().putString("admin_secret", v).apply()

    // Battery measured while exploring (whole phone, unplugged only): totals and the part with live sharing.
    val batteryPct: Double get() = p.getFloat("bat_pct", 0f).toDouble()
    val batteryHours: Double get() = p.getFloat("bat_hours", 0f).toDouble()
    val batteryLivePct: Double get() = p.getFloat("bat_live_pct", 0f).toDouble()
    val batteryLiveHours: Double get() = p.getFloat("bat_live_hours", 0f).toDouble()
    fun addBattery(pct: Double, hours: Double, live: Boolean) {
        val e = p.edit().putFloat("bat_pct", (batteryPct + pct).toFloat()).putFloat("bat_hours", (batteryHours + hours).toFloat())
        if (live) e.putFloat("bat_live_pct", (batteryLivePct + pct).toFloat()).putFloat("bat_live_hours", (batteryLiveHours + hours).toFloat())
        e.apply()
    }
    /** Summary of the last finished tour (numbers only, see TourStats). */
    var lastTour: String
        get() = p.getString("last_tour", "") ?: ""
        set(v) = p.edit().putString("last_tour", v).apply()
    /** Newest app exit (crash/ANR) already reported or dismissed. */
    var exitAcknowledged: Long
        get() = p.getLong("exit_ack", 0L)
        set(v) = p.edit().putLong("exit_ack", v).apply()

    /** Map with north up instead of your viewing direction (compass button). */
    var northUp: Boolean
        get() = p.getBoolean("north_up", false)
        set(v) = p.edit().putBoolean("north_up", v).apply()

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

    // ------------------------------------------------------------ koop over Nostr relays

    /** Signing key of this phone for the Nostr relays (random, stays on the phone). */
    fun nostrSecret(): ByteArray {
        val hex = p.getString("nostr_secret", null)
        if (hex != null && hex.length == 64) return hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val k = de.foggymap.app.core.Schnorr.newSecretKey()
        p.edit().putString("nostr_secret", k.joinToString("") { "%02x".format(it) }).apply()
        return k
    }
    var nostrSince: Long
        get() = p.getLong("nostr_since", 0)
        set(v) = p.edit().putLong("nostr_since", v).apply()
    /** Chunk index → hash of what is already on the relays. */
    var nostrPublished: MutableMap<Int, String>
        get() = try {
            val o = org.json.JSONObject(p.getString("nostr_published", "{}")!!)
            o.keys().asSequence().associate { it.toInt() to o.getString(it) }.toMutableMap()
        } catch (_: Exception) { HashMap() }
        set(v) = p.edit().putString("nostr_published", org.json.JSONObject(v.mapKeys { it.key.toString() }).toString()).apply()
    var nostrMetaAt: Long
        get() = p.getLong("nostr_meta_at", 0)
        set(v) = p.edit().putLong("nostr_meta_at", v).apply()
    var nostrMetaHash: String
        get() = p.getString("nostr_meta_hash", "") ?: ""
        set(v) = p.edit().putString("nostr_meta_hash", v).apply()

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
        e.remove("tracked_seconds").remove("sync_revision").remove("rest_spots").remove("battery_mode").apply()
    }

    companion object {
        fun randomHex(bytes: Int): String {
            val b = ByteArray(bytes).also { SecureRandom().nextBytes(it) }
            return b.joinToString("") { "%02x".format(it) }
        }
    }
}
