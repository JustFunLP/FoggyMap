package de.foggymap.app.data

import de.foggymap.app.core.GroupKey
import de.foggymap.app.core.LatLon
import de.foggymap.app.core.NostrEvent
import de.foggymap.app.core.NostrPacking
import de.foggymap.app.core.RevealItem
import de.foggymap.app.core.Schnorr
import de.foggymap.app.core.hex
import org.json.JSONArray
import org.json.JSONObject

/**
 * Koop without any server setup: every member publishes their own reveals to public Nostr relays,
 * encrypted with the group code, and reads everybody else's.
 *
 *  - reveals: replaceable app-data events (NIP-78) "fm:<tag>:c<n>", ~25 KB each; new reveals only
 *    change the last one,
 *  - member card "fm:<tag>:m": name, group name, kilometres, segments,
 *  - live position "fm:<tag>:l": only when switched on, expires after 10 minutes (NIP-40).
 *
 * Pure JVM (no Android types) so a CI test can run two members against the real relays.
 */
class NostrGroupSync(private val relays: NostrRelays, private val secret: ByteArray, val code: String) {
    val key = GroupKey(code)
    val myPubkey: String = Schnorr.publicKey(secret).hex()

    interface Store {
        /** Own reveals in the order they were made (append-only). */
        fun ownItems(): List<RevealItem>
        /** Stores items from another member; returns the ones that were new. */
        fun insertFromMember(member: String, items: List<RevealItem>): List<RevealItem>
        fun markSynced(ids: Collection<String>)
    }

    data class Meta(val name: String, val groupName: String, val roads: Int, val km: Double)
    data class Member(val pubkey: String, val name: String, val groupName: String, val roads: Int, val km: Double,
                      val lastSeen: Long, val live: LatLon?)

    data class Result(val published: Int, val received: List<RevealItem>, val members: List<Member>, val relaysOk: Int, val newest: Long,
                      /** The member card was accepted by at least one relay. */
                      val metaOk: Boolean = false)

    /**
     * One round: publish what changed, then fetch what is new since [since] (seconds).
     * [published]: chunk index → content hash of what is already on the relays (updated in place).
     */
    suspend fun sync(store: Store, published: MutableMap<Int, String>, since: Long, meta: Meta?): Result {
        val now = System.currentTimeMillis() / 1000
        // 1) own reveals
        val chunks = NostrPacking.chunks(store.ownItems())
        val outgoing = ArrayList<Pair<Int, NostrEvent>>()
        val hashes = HashMap<Int, String>()
        chunks.forEachIndexed { i, c ->
            val h = Schnorr.sha256(c.joinToString("|") { it.id }.toByteArray()).hex().take(24)
            hashes[i] = h
            if (published[i] != h) outgoing += i to event(now, "c$i", "c", key.seal(NostrPacking.encodeItems(c)))
        }
        if (meta != null) outgoing += -1 to event(now, "m", "m", key.seal(JSONObject().put("n", meta.name).put("g", meta.groupName)
            .put("r", meta.roads).put("k", meta.km).toString()))
        var publishedCount = 0
        var metaOk = false
        if (outgoing.isNotEmpty()) {
            val acks = relays.publish(outgoing.map { it.second })
            val okIds = acks.filter { it.ok }.map { it.eventId }.toSet()
            for ((i, e) in outgoing) if (i >= 0 && e.id in okIds) {
                published[i] = hashes[i]!!
                store.markSynced(chunks[i].map { it.id })
                publishedCount++
            }
            metaOk = outgoing.any { (i, e) -> i == -1 && e.id in okIds }
            if (okIds.isEmpty() && acks.isNotEmpty()) throw java.io.IOException(acks.first().message.ifEmpty { "Relays nehmen nichts an" })
        } else {
            // Nothing new to send: everything already published counts as synced.
            chunks.forEachIndexed { i, c -> if (published[i] == hashes[i]) store.markSynced(c.map { it.id }) }
        }

        // 2) everybody's reveals since the last round (a little overlap, relays' clocks differ)
        relays.resetReachable()
        val data = relays.query(JSONObject().put("kinds", JSONArray().put(NostrPacking.KIND))
            .put("#t", JSONArray().put(key.tag)).put("#k", JSONArray().put("c")).put("since", maxOf(0, since - 600)).put("limit", 2000))
        val received = ArrayList<RevealItem>()
        var newest = since
        for (e in data) {
            newest = maxOf(newest, e.createdAt)
            if (e.pubkey == myPubkey) continue
            val json = key.open(e.content) ?: continue          // not from the group (wrong key) – ignore
            received += store.insertFromMember(e.pubkey.take(16), NostrPacking.decodeItems(json))
        }
        // 3) member cards and live positions (small, always fetched completely)
        val cards = relays.query(JSONObject().put("kinds", JSONArray().put(NostrPacking.KIND))
            .put("#t", JSONArray().put(key.tag)).put("#k", JSONArray().put("m").put("l")).put("limit", 500))
        val members = HashMap<String, Member>()
        val lives = HashMap<String, LatLon>()
        for (e in cards) {
            val o = key.open(e.content)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: continue
            when (e.tag("k")) {
                "m" -> members[e.pubkey] = Member(e.pubkey, o.optString("n", "?"), o.optString("g"), o.optInt("r"), o.optDouble("k", 0.0), e.createdAt, null)
                "l" -> {
                    val exp = e.tag("expiration")?.toLongOrNull() ?: 0
                    if (!o.optBoolean("off") && exp > now) lives[e.pubkey] = LatLon(o.getDouble("a"), o.getDouble("o"))
                }
            }
        }
        val list = members.values.map { it.copy(live = lives[it.pubkey]) }
        return Result(publishedCount, received, list, relays.reachable, newest, metaOk)
    }

    /** Does a group with this code exist (anyone published a member card)? Null = relays not reachable. */
    suspend fun findGroup(): Member? {
        relays.resetReachable()
        val cards = relays.query(JSONObject().put("kinds", JSONArray().put(NostrPacking.KIND))
            .put("#t", JSONArray().put(key.tag)).put("#k", JSONArray().put("m")).put("limit", 200))
        if (relays.reachable == 0) throw java.io.IOException("Keine Verbindung zu den Koop-Relays")
        return cards.mapNotNull { e ->
            val o = key.open(e.content)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return@mapNotNull null
            Member(e.pubkey, o.optString("n", "?"), o.optString("g"), o.optInt("r"), o.optDouble("k", 0.0), e.createdAt, null)
        }.maxByOrNull { it.lastSeen }
    }

    suspend fun pushLive(lat: Double, lon: Double, on: Boolean) {
        val now = System.currentTimeMillis() / 1000
        val content = if (on) JSONObject().put("a", Math.round(lat * 1e5) / 1e5).put("o", Math.round(lon * 1e5) / 1e5)
            else JSONObject().put("off", true)
        relays.publish(listOf(event(now, "l", "l", key.seal(content.toString()), expiration = now + if (on) 600 else 60)))
    }

    private fun event(now: Long, part: String, kind: String, content: String, expiration: Long? = null): NostrEvent {
        val tags = arrayListOf(listOf("d", NostrPacking.dTag(key.tag, part)), listOf("t", key.tag), listOf("k", kind))
        expiration?.let { tags += listOf("expiration", it.toString()) }
        return NostrEvent.sign(secret, now, NostrPacking.KIND, tags, content)
    }
}
