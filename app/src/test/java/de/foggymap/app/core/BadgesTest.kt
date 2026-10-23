package de.foggymap.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BadgesTest {
    @Test fun adminKeyDerivationIsStable() {
        // Same derivation as the tool that made the real admin key (the real code is not in the repository).
        assertEquals("6f6c3e2623fb6f99ac2a63c8ec6ec8854f8b2808b40928db2d6a90982a3413b0",
            Schnorr.publicKey(Badges.adminSecret("test-code-2345-6789")).hex())
        assertFalse(Badges.isAdminCode("TEST-CODE-2345-6789"))
    }

    @Test fun whoGetsWhichBadge() {
        val me = "a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f90"
        val id = Badges.playerId(me)
        assertEquals(9, id.length); assertEquals('-', id[4])
        val awards = listOf(
            Badges.Award("alpha-tester", "all", before = 2_000, createdAt = 10),
            Badges.Award("pathfinder", "id", id = id.lowercase().replace("-", ""), createdAt = 10),
            Badges.Award("other", "pk", pk = "ff".repeat(32), createdAt = 10),
        )
        assertEquals(setOf("alpha-tester", "pathfinder"), Badges.earned(awards, me, id, installedAt = 1_500))
        assertEquals(setOf("pathfinder"), Badges.earned(awards, me, id, installedAt = 2_500))   // installed too late
        // A newer award with the same target can take a badge back.
        val revoked = awards + Badges.Award("alpha-tester", "all", before = 2_000, revoked = true, createdAt = 20)
        assertFalse("alpha-tester" in Badges.earned(revoked, me, id, installedAt = 1_500))
        // and re-issuing it with a later cut-off counts again
        val again = revoked + Badges.Award("alpha-tester", "all", before = 3_000, createdAt = 30)
        assertTrue("alpha-tester" in Badges.earned(again, me, id, installedAt = 2_500))
    }

    @Test fun awardsSurviveJson() {
        val a = Badges.Award("alpha-tester", "all", before = 1_761_250_000)
        assertEquals(a.copy(createdAt = 5), Badges.Award.fromJson(org.json.JSONObject(a.toJson().toString()), 5))
        assertEquals(null, Badges.Award.fromJson(org.json.JSONObject("{\"badge\":\"x\",\"to\":\"everyone\"}"), 0))
    }
}
