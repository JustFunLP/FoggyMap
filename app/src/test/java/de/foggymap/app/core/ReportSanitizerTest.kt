package de.foggymap.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ReportSanitizerTest {
    @Test fun noPlacesNamesOrCodesLeaveThePhone() {
        val code = "ABCDEFGHJKMN"
        val lines = listOf(
            "I/FoggyMap: revealed Unioninkatu, Kaivokatu: 3 new items",
            "I/FoggyMap: cell t_9327_4742: 7620 segments, 326 blocks",
            "I/FoggyMap: ui: camera=60.16862,24.93777 z=16.6 tilt=58",
            "D/OkHttp: GET https://tiles.openfreemap.org/planet/20261018_080001_pt/14/9327/4742.pbf",
            "I/FoggyMap: live: 4 reveals from Anna Müller",
            "W/FoggyMap: join ABCD-EFGH-JKMN failed for $code",
            "I/FoggyMap: pubkey 41244cf36b34e1fd53f654cd6cf5ec3ddb14228fb3700773ca9fb8135a3aff46",
        )
        val out = lines.map { ReportSanitizer.clean(it, code) }
        val all = out.joinToString("\n")
        for (secret in listOf("Unioninkatu", "Kaivokatu", "9327", "4742", "60.16862", "24.93777", "Anna", code, "ABCD-EFGH-JKMN", "41244cf3"))
            assertFalse("leaked: $secret", all.contains(secret))
        assertEquals("I/FoggyMap: revealed [Straße]: 3 new items", out[0])
        assertEquals("I/FoggyMap: ui: camera=[Ort],[Ort] z=16.6 tilt=58", out[2])
        // ordinary numbers stay readable
        assertEquals("I/FoggyMap: fog updated: +12 items in 35 ms", ReportSanitizer.clean("I/FoggyMap: fog updated: +12 items in 35 ms", code))
    }
}
