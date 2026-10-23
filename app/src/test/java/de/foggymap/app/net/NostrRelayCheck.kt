package de.foggymap.app.net

import de.foggymap.app.core.GroupKey
import de.foggymap.app.core.NostrEvent
import de.foggymap.app.core.NostrPacking
import de.foggymap.app.core.RevealItem
import de.foggymap.app.core.Schnorr
import de.foggymap.app.core.TestWorlds
import de.foggymap.app.data.NostrGroupSync
import de.foggymap.app.data.NostrLive
import de.foggymap.app.data.NostrRelays
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Network test against the real public relays (runs in its own CI step, never blocks the build):
 * which relays accept FoggyMap's app data, and does a two-member group actually sync through them?
 */
class NostrRelayCheck {
    private val report = File("build/relay-check.txt").apply { parentFile.mkdirs() }
    private fun note(s: String) { println(s); report.appendText(s + "\n") }
    private fun code() = (1..12).map { "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".random() }.joinToString("")

    private val candidates = listOf("wss://relay.damus.io", "wss://nos.lol", "wss://relay.primal.net", "wss://nostr.mom",
        "wss://offchain.pub", "wss://relay.snort.social", "wss://nostr.oxtr.dev", "wss://relay.nostr.bg",
        "wss://nostr-pub.wellorder.net", "wss://relay.nostr.band", "wss://nostr.bitcoiner.social", "wss://relay.mostr.pub")

    @Test fun whichRelaysAcceptAppData() = runBlocking {
        for (url in candidates) {
            val relays = NostrRelays(listOf(url), "FoggyMap-CI/1.0 (+https://github.com/JustFunLP/FoggyMap)")
            val g = GroupKey(code())
            val sk = Schnorr.newSecretKey()
            val parts = ArrayList<String>()
            for (size in listOf(25_000, 50_000)) {
                val content = (1..size).map { "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".random() }.joinToString("")
                val e = NostrEvent.sign(sk, System.currentTimeMillis() / 1000, NostrPacking.KIND,
                    listOf(listOf("d", "fm:${g.tag}:c$size"), listOf("t", g.tag), listOf("k", "c")), content)
                val t0 = System.currentTimeMillis()
                val ack = relays.publish(listOf(e), 8_000).firstOrNull()
                val back = relays.query(JSONObject().put("kinds", JSONArray().put(NostrPacking.KIND)).put("#t", JSONArray().put(g.tag))
                    .put("#k", JSONArray().put("c")), 8_000)
                val found = back.any { it.id == e.id }
                parts += "${size / 1000}KB:${if (ack?.ok == true) "ok" else "NEIN(" + (ack?.message ?: "-").take(60) + ")"}/${if (found) "zurück" else "fehlt"}/${System.currentTimeMillis() - t0}ms"
            }
            note("::notice title=Relay $url::" + parts.joinToString("  "))
        }
    }

    private class MemStore(own: List<RevealItem>) : NostrGroupSync.Store {
        val mine = own.toMutableList()
        val others = LinkedHashMap<String, RevealItem>()
        val synced = HashSet<String>()
        override fun ownItems() = mine.toList()
        override fun insertFromMember(member: String, items: List<RevealItem>) = items.filter { others.putIfAbsent(it.id, it) == null }
        override fun markSynced(ids: Collection<String>) { synced += ids }
    }

    @Test fun twoMembersSyncThroughTheDefaultRelays() = runBlocking {
        val code = code()
        val relays = NostrRelays(NostrRelays.DEFAULT, "FoggyMap-CI/1.0 (+https://github.com/JustFunLP/FoggyMap)")
        val m = TestWorlds.grid()
        val base = m.segments.filter { it.matchable }.flatMap { m.reveal(it) }.distinctBy { it.id }
        val aItems = (0 until 12).flatMap { k -> base.map { it.copy(id = it.id + "~a$k") } }
        val bItems = base.take(30).map { it.copy(id = it.id + "~b") }
        val a = NostrGroupSync(relays, Schnorr.newSecretKey(), code); val sa = MemStore(aItems); val pa = HashMap<Int, String>()
        val b = NostrGroupSync(relays, Schnorr.newSecretKey(), code); val sb = MemStore(bItems); val pb = HashMap<Int, String>()
        val t0 = System.currentTimeMillis()
        val ra1 = a.sync(sa, pa, 0, NostrGroupSync.Meta("Anna", "CI-Gruppe", 12, 3.4))
        val found = b.findGroup()
        val rb1 = b.sync(sb, pb, 0, NostrGroupSync.Meta("Ben", "CI-Gruppe", 3, 0.9))
        val ra2 = a.sync(sa, pa, ra1.newest, null)
        b.pushLive(48.0, 11.0, true)
        val ra3 = a.sync(sa, pa, ra2.newest, null)
        note("::notice title=Koop über Relays::A veröffentlichte ${ra1.published} Pakete (${aItems.size} Funde), B fand Gruppe '${found?.groupName}' und erhielt " +
            "${rb1.received.size}, A erhielt ${ra2.received.size} von B, Mitglieder ${ra3.members.map { it.name }}, Live ${ra3.members.count { it.live != null }}, " +
            "Relays erreichbar ${ra3.relaysOk}/${NostrRelays.DEFAULT.size}, ${System.currentTimeMillis() - t0} ms")
        assertEquals("CI-Gruppe", found?.groupName)
        assertTrue("member cards accepted", ra1.metaOk && rb1.metaOk)
        assertEquals(aItems.map { it.id }.toSet(), rb1.received.map { it.id }.toSet())
        assertEquals(bItems.map { it.id }.toSet(), ra2.received.map { it.id }.toSet())
        assertTrue(ra3.members.map { it.name }.containsAll(listOf("Anna", "Ben")))
        assertTrue(ra3.members.first { it.name == "Ben" }.live != null)
        // A second round without changes publishes nothing.
        assertEquals(0, a.sync(sa, pa, ra3.newest, null).published)
        assertTrue(sa.synced.containsAll(aItems.map { it.id }))
    }

    /** Live channel: ephemeral events must arrive at a listening member within seconds. */
    private fun liveRoundTrip(urls: List<String>, messages: Int = 4): Pair<Int, Long> {
        val code = code(); val g = GroupKey(code)
        val ua = "FoggyMap-CI/1.0 (+https://github.com/JustFunLP/FoggyMap)"
        val a = NostrLive(urls, ua, Schnorr.newSecretKey(), g)
        val b = NostrLive(urls, ua, Schnorr.newSecretKey(), g)
        val got = java.util.Collections.synchronizedList(ArrayList<Pair<NostrLive.Live, Long>>())
        b.onLive = { got += it to System.currentTimeMillis() }
        b.start(listen = true); a.start(listen = false)
        val t0 = System.currentTimeMillis()
        while ((a.connectedRelays < urls.size || b.connectedRelays < urls.size) && System.currentTimeMillis() - t0 < 10_000) Thread.sleep(100)
        Thread.sleep(1_000)   // let the subscription settle
        val sentAt = ArrayList<Long>()
        val item = TestWorlds.grid().let { m -> m.segments.first { it.matchable }.let { m.reveal(it).first() } }
        for (k in 0 until messages) {
            sentAt += System.currentTimeMillis()
            a.send("Anna", 52.5438 + k * 1e-5, 13.4177, 90.0, 1.4, if (k == 0) listOf(item) else emptyList())
            Thread.sleep(1_000)
        }
        Thread.sleep(3_000)
        a.stop(); b.stop()
        val received = got.map { it.first.lat }.distinct().size
        val delay = if (got.isEmpty()) -1 else got.minOf { it.second } - sentAt.first()
        if (got.isNotEmpty()) assertTrue("reveals travel with the live message", got.any { it.first.items.isNotEmpty() })
        return received to delay
    }

    @Test fun liveEverySecondsThroughTheDefaultRelays() {
        for (url in NostrRelays.DEFAULT) {
            val (n, ms) = liveRoundTrip(listOf(url), 3)
            note("::notice title=Live über $url::$n/3 Live-Nachrichten angekommen" + if (ms >= 0) ", erste nach $ms ms" else "")
        }
        val (n, ms) = liveRoundTrip(NostrRelays.DEFAULT, 4)
        note("::notice title=Live über alle Relays::$n/4 Live-Nachrichten angekommen, erste nach $ms ms")
        assertTrue("live messages arrive through the default relays", n >= 3)
    }
}
