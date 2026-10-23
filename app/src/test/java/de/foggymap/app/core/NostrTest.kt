package de.foggymap.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NostrTest {
    @Test fun eventsAreSignedAndVerifiable() {
        val sk = Schnorr.newSecretKey()
        val e = NostrEvent.sign(sk, 1_761_200_000, NostrPacking.KIND, listOf(listOf("d", "fm:x:0"), listOf("t", "x")), "hällo \"welt\"\n")
        assertEquals(64, e.id.length); assertEquals(128, e.sig.length)
        assertTrue(e.verify())
        val back = NostrEvent.fromJson(org.json.JSONObject(e.toJson().toString()))!!
        assertTrue(back.verify())
        assertEquals("fm:x:0", back.tag("d"))
        val forged = NostrEvent(e.id, e.pubkey, e.createdAt, e.kind, e.tags, "anders", e.sig)
        assertFalse(forged.verify())
    }

    @Test fun serialisationFollowsNip01() {
        assertEquals("[0,\"ab\",1,30078,[[\"d\",\"x\"]],\"a\\\"b\\\\c\\nd\"]",
            NostrEvent.serialize("ab", 1, 30078, listOf(listOf("d", "x")), "a\"b\\c\nd"))
    }

    @Test fun onlyTheGroupCanReadItsData() {
        val g = GroupKey("ABCDEFGHJKMN")
        val sealed = g.seal("{\"geheim\":1}")
        assertEquals("{\"geheim\":1}", GroupKey("ABCDEFGHJKMN").open(sealed))
        assertNull(GroupKey("ABCDEFGHJKMP").open(sealed))
        assertEquals(32, g.tag.length)
        assertEquals(g.tag, GroupKey("ABCDEFGHJKMN").tag)
        assertTrue(g.tag != GroupKey("ABCDEFGHJKMP").tag)
        assertFalse(sealed.contains("geheim"))
        // Tampered data is rejected (AES-GCM authentication), not decoded into garbage.
        val raw = java.util.Base64.getDecoder().decode(sealed)
        raw[raw.size - 5] = (raw[raw.size - 5].toInt() xor 1).toByte()
        assertNull(g.open(java.util.Base64.getEncoder().encodeToString(raw)))
    }

    @Test fun realRevealsFitIntoRelaySizedEvents() {
        val m = TestWorlds.grid()
        val items = m.segments.filter { it.matchable }.flatMap { m.reveal(it) }.distinctBy { it.id }
        val all = (0 until 40).flatMap { k -> items.map { it.copy(id = it.id + "~$k") } }
        val chunks = NostrPacking.chunks(all)
        val g = GroupKey("ABCDEFGHJKMN")
        val sizes = chunks.map { g.seal(NostrPacking.encodeItems(it)).length }
        println("${all.size} items -> ${chunks.size} events, sealed sizes ${sizes.minOrNull()}..${sizes.maxOrNull()} bytes")
        assertTrue(sizes.all { it < 60_000 })
        // Appending items only changes the last chunk.
        val more = NostrPacking.chunks(all + items.map { it.copy(id = it.id + "~new") })
        for (i in 0 until chunks.size - 1) assertEquals(chunks[i].map { it.id }, more[i].map { it.id })
        // Round trip.
        val back = NostrPacking.decodeItems(g.open(g.seal(NostrPacking.encodeItems(chunks[0])))!!)
        assertEquals(chunks[0].map { it.id }, back.map { it.id })
    }
}
