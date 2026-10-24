package de.foggymap.app.core

import org.json.JSONObject
import kotlin.math.max

/**
 * What a tour tells about the phone: GPS quality, time spent in each GPS stage and how fast the
 * battery went down. No positions – only numbers, so it can go into a problem report.
 */
class TourStats(val startMs: Long) {
    var fixes = 0; private set
    /** Fixes too vague to reveal anything (worse than ±35 m). */
    var vague = 0; private set
    private val accuracies = ArrayList<Float>()
    private val stageMs = LinkedHashMap<String, Long>()
    private var stage: String? = null
    private var stageSince = 0L
    private var lastBattery: BatterySample? = null
    val drain = Drain()

    data class BatterySample(val timeMs: Long, val levelPct: Double, val chargeUah: Long, val plugged: Boolean,
                             val screenOn: Boolean, val live: Boolean)

    /** Battery used while unplugged; [live] = the part with live position sharing switched on. */
    class Drain {
        var pct = 0.0; var hours = 0.0
        var livePct = 0.0; var liveHours = 0.0
        var screenOnHours = 0.0
        val perHour: Double? get() = if (hours >= MIN_HOURS) pct / hours else null
        val livePerHour: Double? get() = if (liveHours >= MIN_HOURS) livePct / liveHours else null
        fun add(pct: Double, hours: Double, live: Boolean, screenOn: Boolean) {
            this.pct += pct; this.hours += hours
            if (live) { livePct += pct; liveHours += hours }
            if (screenOn) screenOnHours += hours
        }
    }

    fun addFix(accuracy: Float) {
        fixes++
        if (accuracy > 35f) vague++
        // Keep memory bounded on very long tours: thin out evenly.
        if (accuracies.size >= 4000) { val kept = accuracies.filterIndexed { i, _ -> i % 2 == 0 }; accuracies.clear(); accuracies += kept }
        accuracies += accuracy
    }

    fun accuracyPercentile(p: Double): Float? {
        if (accuracies.isEmpty()) return null
        val sorted = accuracies.sorted()
        return sorted[((sorted.size - 1) * p).toInt().coerceIn(0, sorted.size - 1)]
    }

    fun stage(name: String, nowMs: Long) {
        stage?.let { stageMs[it] = (stageMs[it] ?: 0L) + max(0L, nowMs - stageSince) }
        stage = name; stageSince = nowMs
    }

    fun stageDurations(nowMs: Long): Map<String, Long> {
        val out = LinkedHashMap(stageMs)
        stage?.let { out[it] = (out[it] ?: 0L) + max(0L, nowMs - stageSince) }
        return out
    }

    /**
     * Adds a battery reading and returns the drop since the previous one (percent, hours, live) – or
     * null while charging. Uses the charge counter (µAh, fine-grained) when the phone has one,
     * otherwise the battery percentage.
     */
    fun addBattery(s: BatterySample): Triple<Double, Double, Boolean>? {
        val prev = lastBattery
        lastBattery = s
        if (prev == null || prev.plugged || s.plugged) return null
        val hours = (s.timeMs - prev.timeMs) / 3_600_000.0
        if (hours <= 0 || hours > 1.0) return null            // gap (phone asleep for long, clock change)
        val drop = if (prev.chargeUah > 0 && s.chargeUah > 0 && prev.levelPct > 1) {
            val capacity = prev.chargeUah / (prev.levelPct / 100.0)
            (prev.chargeUah - s.chargeUah) / capacity * 100.0
        } else prev.levelPct - s.levelPct
        val pct = drop.coerceAtLeast(0.0)
        drain.add(pct, hours, prev.live, prev.screenOn)
        return Triple(pct, hours, prev.live)
    }

    fun summary(nowMs: Long, segments: Int, meters: Double): JSONObject = JSONObject()
        .put("start", startMs).put("minutes", (nowMs - startMs) / 60_000)
        .put("fixes", fixes).put("vague", vague)
        .put("acc50", accuracyPercentile(0.5)?.toDouble() ?: JSONObject.NULL)
        .put("acc90", accuracyPercentile(0.9)?.toDouble() ?: JSONObject.NULL)
        .put("stages", JSONObject().apply { stageDurations(nowMs).forEach { (k, v) -> put(k, v / 1000) } })
        .put("drainPct", round1(drain.pct)).put("drainHours", round2(drain.hours))
        .put("livePct", round1(drain.livePct)).put("liveHours", round2(drain.liveHours))
        .put("screenOnHours", round2(drain.screenOnHours))
        .put("segments", segments).put("km", round2(meters / 1000))

    companion object {
        /** Below ~10 minutes of measuring a "% per hour" would be mostly noise. */
        const val MIN_HOURS = 0.15
        private fun round1(v: Double) = Math.round(v * 10) / 10.0
        private fun round2(v: Double) = Math.round(v * 100) / 100.0
    }
}
