package de.foggymap.app.data

import android.content.Context
import android.util.Log
import de.foggymap.app.BuildConfig
import de.foggymap.app.core.Badges
import de.foggymap.app.core.NostrEvent
import de.foggymap.app.core.NostrPacking
import de.foggymap.app.core.Schnorr
import de.foggymap.app.core.hex
import de.foggymap.app.core.unhex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Badges: fetches the admin's signed awards from the relays, works out which badges this player has,
 * and queues new ones for the big "Neues Abzeichen" animation. On the admin's phone (after entering
 * the admin code) it also hands out badges and defines new ones.
 */
class BadgeRepository(private val context: Context, private val prefs: Prefs) {
    private val relays by lazy { NostrRelays(NostrRelays.DEFAULT, "FoggyMap/${BuildConfig.VERSION_NAME} (Android; https://github.com/JustFunLP/FoggyMap)") }

    /** Badges defined by the admin in the app (in addition to the built-in catalog). */
    val defined = MutableStateFlow(decodeDefs(prefs.badgeDefs))
    /** Badges this player has. */
    val earned = MutableStateFlow(prefs.badgesEarned.mapNotNull { find(it) })
    /** Newly earned, still to be celebrated (first one is shown). */
    val celebrate = MutableStateFlow(earned.value.filter { it.id !in prefs.badgesCelebrated })
    val isAdmin = MutableStateFlow(prefs.adminSecret.isNotEmpty())

    val myPubkey: String get() = Schnorr.publicKey(prefs.nostrSecret()).hex()
    val playerId: String get() = Badges.playerId(myPubkey)
    private val installedAt: Long by lazy {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).firstInstallTime / 1000 }.getOrDefault(System.currentTimeMillis() / 1000)
    }
    private var lastRefresh = 0L

    fun find(id: String): Badges.Badge? = Badges.CATALOG.firstOrNull { it.id == id } ?: defined.value.firstOrNull { it.id == id }
    fun all(): List<Badges.Badge> = Badges.CATALOG + defined.value.filter { d -> Badges.CATALOG.none { it.id == d.id } }

    /** Looks for new awards (at most every few minutes unless [force]). */
    suspend fun refresh(force: Boolean = false) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (!force && now - lastRefresh < 5 * 60_000) return@withContext
        lastRefresh = now
        try {
            val events = relays.query(JSONObject().put("authors", JSONArray().put(Badges.ISSUER)).put("kinds", JSONArray().put(NostrPacking.KIND))
                .put("#t", JSONArray().put(Badges.TAG)).put("limit", 500), 12_000)
            val awards = ArrayList<Badges.Award>()
            val defs = ArrayList<Badges.Badge>()
            for (e in events) {
                if (e.pubkey != Badges.ISSUER || !e.verify()) continue
                val o = runCatching { JSONObject(e.content) }.getOrNull() ?: continue
                val d = e.tag("d") ?: continue
                when {
                    d.startsWith("fm-award:") -> Badges.Award.fromJson(o, e.createdAt)?.let { awards += it }
                    d.startsWith("fm-badge:") -> decodeDef(o)?.let { defs += it }
                }
            }
            if (defs.isNotEmpty()) {
                defined.value = defs
                prefs.badgeDefs = JSONArray().apply { defs.forEach { put(encodeDef(it)) } }.toString()
            }
            if (relays.reachable == 0 && events.isEmpty()) return@withContext     // offline: keep what we have
            val ids = Badges.earned(awards, myPubkey, playerId, installedAt)
            prefs.badgesEarned = ids
            earned.value = ids.mapNotNull { find(it) }
            celebrate.value = earned.value.filter { it.id !in prefs.badgesCelebrated }
            Log.i("FoggyMap", "badges: ${ids.size} earned, ${celebrate.value.size} new (${awards.size} awards)")
        } catch (e: Exception) { Log.w("FoggyMap", "badge refresh failed", e) }
    }

    /** The big animation for [id] was shown. */
    fun celebrated(id: String) {
        prefs.badgesCelebrated = prefs.badgesCelebrated + id
        celebrate.update { list -> list.filter { it.id != id } }
    }

    // ------------------------------------------------------------------ admin

    suspend fun unlockAdmin(code: String): Boolean = withContext(Dispatchers.Default) {
        if (!Badges.isAdminCode(code)) return@withContext false
        prefs.adminSecret = Badges.adminSecret(code).hex()
        isAdmin.value = true
        true
    }

    fun lockAdmin() { prefs.adminSecret = ""; isAdmin.value = false }

    /** Hands out (or takes back) a badge. Returns how many relays stored it. */
    suspend fun award(award: Badges.Award): Int = publish("fm-award", award.dTag(), award.toJson())

    /** Defines a new badge (title, text, symbol, colour). */
    suspend fun define(badge: Badges.Badge): Int {
        val n = publish("fm-badge", "fm-badge:${badge.id}", encodeDef(badge))
        if (n > 0) {
            defined.update { list -> list.filter { it.id != badge.id } + badge }
            prefs.badgeDefs = JSONArray().apply { defined.value.forEach { put(encodeDef(it)) } }.toString()
        }
        return n
    }

    private suspend fun publish(kind: String, d: String, content: JSONObject): Int = withContext(Dispatchers.IO) {
        val secret = prefs.adminSecret.takeIf { it.length == 64 }?.unhex() ?: return@withContext 0
        val e = NostrEvent.sign(secret, System.currentTimeMillis() / 1000, NostrPacking.KIND,
            listOf(listOf("d", d), listOf("t", Badges.TAG), listOf("k", kind)), content.toString())
        val acks = relays.publish(listOf(e))
        lastRefresh = 0
        acks.count { it.ok }
    }

    private fun encodeDef(b: Badges.Badge) = JSONObject().put("id", b.id).put("title", b.title).put("text", b.text)
        .put("symbol", b.symbol).put("color", b.color)

    private fun decodeDef(o: JSONObject): Badges.Badge? {
        val id = o.optString("id"); val title = o.optString("title")
        if (id.isEmpty() || title.isEmpty()) return null
        return Badges.Badge(id, title.take(40), o.optString("text").take(200), o.optString("symbol", "★").take(3),
            o.optInt("color", Badges.COLORS[0]), 0xFFF5C26B.toInt())
    }

    private fun decodeDefs(json: String): List<Badges.Badge> = runCatching {
        val a = JSONArray(json); (0 until a.length()).mapNotNull { decodeDef(a.getJSONObject(it)) }
    }.getOrDefault(emptyList())
}
