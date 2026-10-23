package de.foggymap.app.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import java.util.Base64
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** A signed Nostr event (NIP-01). */
class NostrEvent(val id: String, val pubkey: String, val createdAt: Long, val kind: Int,
                 val tags: List<List<String>>, val content: String, val sig: String) {

    fun tag(name: String): String? = tags.firstOrNull { it.size >= 2 && it[0] == name }?.get(1)

    fun toJson(): JSONObject = JSONObject().put("id", id).put("pubkey", pubkey).put("created_at", createdAt).put("kind", kind)
        .put("tags", JSONArray().apply { tags.forEach { put(JSONArray(it)) } }).put("content", content).put("sig", sig)

    /** Id matches the content and the signature matches the author. */
    fun verify(): Boolean = try {
        val expect = Schnorr.sha256(serialize(pubkey, createdAt, kind, tags, content).toByteArray()).hex()
        expect == id && Schnorr.verify(id.unhex(), pubkey.unhex(), sig.unhex())
    } catch (_: Exception) { false }

    companion object {
        fun sign(secret: ByteArray, createdAt: Long, kind: Int, tags: List<List<String>>, content: String): NostrEvent {
            val pub = Schnorr.publicKey(secret).hex()
            val id = Schnorr.sha256(serialize(pub, createdAt, kind, tags, content).toByteArray())
            return NostrEvent(id.hex(), pub, createdAt, kind, tags, content, Schnorr.sign(id, secret).hex())
        }

        fun fromJson(o: JSONObject): NostrEvent? = try {
            val t = o.getJSONArray("tags")
            NostrEvent(o.getString("id"), o.getString("pubkey"), o.getLong("created_at"), o.getInt("kind"),
                (0 until t.length()).map { i -> t.getJSONArray(i).let { a -> (0 until a.length()).map { a.getString(it) } } },
                o.getString("content"), o.getString("sig"))
        } catch (_: Exception) { null }

        /** The exact serialisation NIP-01 hashes: [0,pubkey,created_at,kind,tags,content], no whitespace. */
        fun serialize(pubkey: String, createdAt: Long, kind: Int, tags: List<List<String>>, content: String): String = buildString {
            append("[0,").append(quote(pubkey)).append(',').append(createdAt).append(',').append(kind).append(",[")
            tags.forEachIndexed { i, t ->
                if (i > 0) append(',')
                append('[').append(t.joinToString(",") { quote(it) }).append(']')
            }
            append("],").append(quote(content)).append(']')
        }

        fun quote(s: String): String = buildString(s.length + 2) {
            append('"')
            for (c in s) when (c) {
                '"' -> append("\\\""); '\\' -> append("\\\\"); '\n' -> append("\\n"); '\r' -> append("\\r")
                '\t' -> append("\\t"); '\b' -> append("\\b"); '\u000C' -> append("\\f")
                else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
            }
            append('"')
        }
    }
}

/**
 * Everything a group shares is encrypted with a key derived from the group code (AES-256-GCM,
 * PBKDF2 so the code cannot be guessed from the public tag). Relays only see opaque data, a tag
 * and timestamps; whoever knows the code can read and write.
 */
class GroupKey(code: String) {
    private val derived: ByteArray = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        .generateSecret(PBEKeySpec(code.toCharArray(), "FoggyMap/group/v1".toByteArray(), 60_000, 48 * 8)).encoded
    /** Public lookup tag of the group (not the key). */
    val tag: String = derived.copyOfRange(0, 16).hex()
    private val key = SecretKeySpec(derived.copyOfRange(16, 48), "AES")
    private val random = SecureRandom()

    fun seal(plain: String): String {
        val zipped = ByteArrayOutputStream().also { bos -> GZIPOutputStream(bos).use { it.write(plain.toByteArray()) } }.toByteArray()
        val nonce = ByteArray(12).also { random.nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, nonce))
        return Base64.getEncoder().encodeToString(nonce + c.doFinal(zipped))
    }

    fun open(sealed: String): String? = try {
        val all = Base64.getDecoder().decode(sealed)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, all, 0, 12))
        val zipped = c.doFinal(all, 12, all.size - 12)
        String(GZIPInputStream(zipped.inputStream()).use { it.readBytes() })
    } catch (_: Exception) { null }
}

/**
 * How a member's reveals are packed into Nostr events (NIP-78 app data, kind 30078, replaceable
 * per "d" tag). Own items in their local order are cut into chunks of ~[TARGET] bytes of JSON.
 * New items only ever change the last chunk, so a sync re-publishes one event, not everything.
 */
object NostrPacking {
    const val KIND = 30078
    /** ~13 KB per event after compression and encryption: a re-publish of the last chunk stays cheap. */
    const val TARGET = 40_000
    fun dTag(groupTag: String, part: String) = "fm:$groupTag:$part"

    fun chunks(items: List<RevealItem>): List<List<RevealItem>> {
        val out = ArrayList<List<RevealItem>>()
        var cur = ArrayList<RevealItem>(); var size = 0
        for (it in items) {
            val s = it.toJson().toString().length + 1
            if (cur.isNotEmpty() && size + s > TARGET) { out += cur; cur = ArrayList(); size = 0 }
            cur += it; size += s
        }
        if (cur.isNotEmpty()) out += cur
        return out
    }

    fun encodeItems(items: List<RevealItem>): String = JSONArray().apply { items.forEach { put(it.toJson()) } }.toString()

    fun decodeItems(json: String): List<RevealItem> = try {
        val a = JSONArray(json)
        (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(RevealItem::fromJson) }
    } catch (_: Exception) { emptyList() }
}
