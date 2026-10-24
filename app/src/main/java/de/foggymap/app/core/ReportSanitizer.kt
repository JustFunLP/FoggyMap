package de.foggymap.app.core

/**
 * Cleans log lines before they go into a problem report: no positions, no tile numbers (they also
 * tell where you are), no street or friend names, no group code, no keys.
 */
object ReportSanitizer {
    private val coordinate = Regex("-?\\d{1,3}\\.\\d{4,}")
    private val tileId = Regex("\\bt_\\d+_\\d+\\b")
    private val tilePath = Regex("/\\d{1,2}/\\d+/\\d+(\\.pbf)?")
    private val revealed = Regex("(revealed )(.*?)(: \\d+ new items)")
    private val liveFrom = Regex("(reveals from ).*")
    private val hexKey = Regex("\\b[0-9a-f]{40,}\\b")

    fun clean(line: String, groupCode: String = ""): String {
        var s = line
        if (groupCode.length >= 8) {
            s = s.replace(groupCode, "[Gruppencode]", ignoreCase = true)
            if (groupCode.length == 12) s = s.replace(Invite.pretty(groupCode), "[Gruppencode]", ignoreCase = true)
        }
        s = revealed.replace(s) { m -> m.groupValues[1] + "[Straße]" + m.groupValues[3] }
        s = liveFrom.replace(s) { m -> m.groupValues[1] + "[Name]" }
        s = tilePath.replace(s, "/…")
        s = tileId.replace(s, "t_…")
        s = hexKey.replace(s, "[Schlüssel]")
        s = coordinate.replace(s, "[Ort]")
        return s
    }
}
