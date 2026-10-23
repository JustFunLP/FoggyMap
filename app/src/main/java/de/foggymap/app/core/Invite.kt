package de.foggymap.app.core

/** Group codes and invitation texts. Pure logic so it is unit-tested. */
object Invite {
    private val CODE = Regex("^[A-HJ-NP-Z2-9]{12}$")
    private const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    /** Pseudo server address of groups that sync over public Nostr relays (no own server). */
    const val NOSTR = "nostr"

    /** New random group code: 12 characters, 60 bit. */
    fun newCode(random: java.security.SecureRandom = java.security.SecureRandom()): String =
        (1..12).map { ALPHABET[random.nextInt(ALPHABET.length)] }.joinToString("")
    private val URL = Regex("https://[A-Za-z0-9.-]+(?::[0-9]+)?(?:/[A-Za-z0-9._~/-]*)?")

    /** Normalises user input (lower case, spaces, dashes). The alphabet has no 0/O/1/I look-alikes. */
    fun normalizeCode(input: String): String? {
        val c = input.uppercase().filter { it.isLetterOrDigit() }
        return if (CODE.matches(c)) c else null
    }

    fun pretty(code: String) = code.chunked(4).joinToString("-")

    fun isValidServer(url: String) = URL.matches(url.trim().trimEnd('/'))

    data class Parsed(val code: String, val server: String?)

    /**
     * Accepts a bare code ("A7K9-P3QX-M2TF") or a full invitation text that contains
     * "foggymap:<server>#<code>" or a server URL plus a code somewhere in the text.
     */
    fun parse(text: String): Parsed? {
        val t = text.trim()
        Regex("foggymap:nostr#([A-Za-z0-9-]{12,16})").find(t)?.let { m ->
            return Parsed(normalizeCode(m.groupValues[1]) ?: return null, NOSTR)
        }
        Regex("foggymap:(https://[^#\\s]+)#([A-Za-z0-9-]{12,16})").find(t)?.let { m ->
            val code = normalizeCode(m.groupValues[2]) ?: return null
            val server = m.groupValues[1].trimEnd('/')
            return Parsed(code, server.takeIf { isValidServer(it) })
        }
        normalizeCode(t)?.let { return Parsed(it, null) }
        // The download link in the invitation is not a sync server.
        val server = URL.findAll(t).map { it.value.trimEnd('/') }.firstOrNull { !it.contains("github.com") }
        val withoutUrl = if (server != null) t.replace(server, " ") else t
        val code = Regex("[A-Za-z0-9]{4}-[A-Za-z0-9]{4}-[A-Za-z0-9]{4}").find(withoutUrl)?.value?.let(::normalizeCode)
            ?: return null
        return Parsed(code, server)
    }

    fun shareText(groupName: String, code: String, server: String) = buildString {
        append("Komm in meine FoggyMap-Gruppe „").append(groupName).append("“ und lass uns gemeinsam die Karte aufdecken!\n\n")
        append("Gruppencode: ").append(pretty(code)).append("\n\n")
        append("FoggyMap installieren: https://github.com/JustFunLP/FoggyMap/releases\n")
        append("Dann in FoggyMap auf „Menü“ → „Koop“ → „Beitreten“ tippen und diese ganze Nachricht einfügen:\n")
        append("foggymap:").append(server).append('#').append(code)
    }
}
