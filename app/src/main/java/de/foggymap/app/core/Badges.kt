package de.foggymap.app.core

import org.json.JSONObject
import java.math.BigInteger
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Badges ("Abzeichen") that the FoggyMap admin hands out.
 *
 * An award is a small public Nostr event signed by the admin key ([ISSUER]); every app checks the
 * signature, so nobody else can hand out badges. The admin key is derived from an admin code that only
 * the admin knows – the app contains just the public key (nothing secret in the APK).
 *
 * Who gets a badge:
 *  - "all": everybody who installed FoggyMap before a point in time (e.g. all alpha testers),
 *  - "id": one player, by the player ID shown in the app (XXXX-XXXX, from the device's own key),
 *  - "pk": one group member (their full key).
 */
object Badges {
    /** Public key of the admin (derived from the admin code; the code itself is not in the app). */
    const val ISSUER = "41244cf36b34e1fd53f654cd6cf5ec3ddb14228fb3700773ca9fb8135a3aff46"
    const val TAG = "fm-badges"

    data class Badge(val id: String, val title: String, val text: String, val symbol: String, val color: Int, val accent: Int)

    /** Built-in badges with their own artwork. More can be defined by the admin in the app. */
    val CATALOG = listOf(
        Badge("alpha-tester", "Alpha Tester",
            "Du warst von Anfang an dabei und hast FoggyMap getestet, als es noch frisch aus dem Nebel kam.",
            "α", 0xFF8B5CF6.toInt(), 0xFFF5C26B.toInt()),
    )

    /** Colours the admin can pick for new badges. */
    val COLORS = listOf(0xFF8B5CF6.toInt(), 0xFF22D3EE.toInt(), 0xFFF472B6.toInt(), 0xFF34D399.toInt(), 0xFFF59E0B.toInt())

    data class Award(val badge: String, val to: String, val before: Long = 0, val id: String = "", val pk: String = "",
                     val revoked: Boolean = false, val createdAt: Long = 0) {
        fun toJson(): JSONObject = JSONObject().put("badge", badge).put("to", to).apply {
            if (before > 0) put("before", before)
            if (id.isNotEmpty()) put("id", id)
            if (pk.isNotEmpty()) put("pk", pk)
            if (revoked) put("revoked", true)
        }
        /** The replaceable-event key: a newer award for the same badge and target replaces (or revokes) the old one. */
        fun dTag() = "fm-award:$badge:" + when (to) { "all" -> "all"; "id" -> "id:$id"; else -> "pk:${pk.take(16)}" }

        companion object {
            fun fromJson(o: JSONObject, createdAt: Long): Award? {
                val badge = o.optString("badge"); val to = o.optString("to")
                if (badge.isEmpty() || to !in setOf("all", "id", "pk")) return null
                return Award(badge, to, o.optLong("before"), o.optString("id"), o.optString("pk"), o.optBoolean("revoked"), createdAt)
            }
        }
    }

    /** Badges this player has, from all (verified) awards. [installedAt] in seconds. */
    fun earned(awards: List<Award>, myPubkey: String, myPlayerId: String, installedAt: Long): Set<String> {
        val out = HashSet<String>()
        // For each badge+target only the newest award counts (so it can be revoked).
        for (a in awards.groupBy { it.dTag() }.values.map { list -> list.maxBy { it.createdAt } }) {
            if (a.revoked) continue
            val mine = when (a.to) {
                "all" -> installedAt in 1..a.before
                "id" -> normalizeId(a.id) == normalizeId(myPlayerId)
                "pk" -> a.pk.equals(myPubkey, ignoreCase = true)
                else -> false
            }
            if (mine) out += a.badge
        }
        return out
    }

    /** Short player ID from the device's public key: 8 characters (40 bits), shown as XXXX-XXXX. */
    fun playerId(pubkeyHex: String): String {
        val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        var v = BigInteger(pubkeyHex.take(10), 16)
        val sb = StringBuilder()
        repeat(8) { sb.append(alphabet[v.and(BigInteger.valueOf(31)).toInt()]); v = v.shiftRight(5) }
        return sb.reverse().toString().let { it.substring(0, 4) + "-" + it.substring(4) }
    }

    fun normalizeId(s: String) = s.uppercase().filter { it.isLetterOrDigit() }

    /** Admin key from the admin code (PBKDF2, 120 000 rounds, so the code cannot be guessed from the key). */
    fun adminSecret(code: String): ByteArray {
        val norm = code.uppercase().filter { it.isLetterOrDigit() }
        val raw = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(norm.toCharArray(), "FoggyMap/admin/v1".toByteArray(), 120_000, 256)).encoded
        val d = BigInteger(1, raw).mod(Schnorr.N.subtract(BigInteger.ONE)).add(BigInteger.ONE)
        val b = d.toByteArray()
        return when {
            b.size == 32 -> b
            b.size > 32 -> b.copyOfRange(b.size - 32, b.size)
            else -> ByteArray(32 - b.size) + b
        }
    }

    fun isAdminCode(code: String): Boolean = runCatching { Schnorr.publicKey(adminSecret(code)).hex() == ISSUER }.getOrDefault(false)
}
