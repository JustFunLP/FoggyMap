package de.foggymap.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InviteTest {
    @Test fun codesAreNormalised() {
        assertEquals("A7K9P3QXM2TF", Invite.normalizeCode("a7k9-p3qx-m2tf"))
        assertEquals("A7K9P3QXM2TF", Invite.normalizeCode(" A7K9 P3QX M2TF "))
        assertNull(Invite.normalizeCode("A7K9-P3QX"))
        assertEquals("A7K9-P3QX-M2TF", Invite.pretty("A7K9P3QXM2TF"))
    }

    @Test fun fullInvitationRoundTrip() {
        val text = Invite.shareText("Büro", "A7K9P3QXM2TF", "https://foggymap-sync.tim.workers.dev")
        val p = Invite.parse(text)!!
        assertEquals("A7K9P3QXM2TF", p.code)
        assertEquals("https://foggymap-sync.tim.workers.dev", p.server)
    }

    @Test fun messengerMangledInvitationStillWorks() {
        val p = Invite.parse("Code: A7K9-P3QX-M2TF\nServer https://sync.example.de/foggy/")!!
        assertEquals("A7K9P3QXM2TF", p.code)
        assertEquals("https://sync.example.de/foggy", p.server)
    }

    @Test fun serversMustBeHttps() {
        assertTrue(Invite.isValidServer("https://a.b.workers.dev"))
        assertFalse(Invite.isValidServer("http://a.b.workers.dev"))
        assertFalse(Invite.isValidServer("https://a.b/\"><script>"))
    }

    @Test fun groupsWithoutServerUseNostr() {
        val code = Invite.newCode()
        assertEquals(code, Invite.normalizeCode(code))
        val text = Invite.shareText("Büro", code, Invite.NOSTR)
        assertTrue(text.contains("foggymap:nostr#$code"))
        val p = Invite.parse(text)!!
        assertEquals(code, p.code)
        assertEquals(Invite.NOSTR, p.server)
        // Without the last line (messenger cut it off) the code alone still works; the download link is no server.
        val cut = Invite.parse(text.lines().filter { !it.startsWith("foggymap:") }.joinToString("\n"))!!
        assertEquals(code, cut.code)
        assertNull(cut.server)
    }
}
