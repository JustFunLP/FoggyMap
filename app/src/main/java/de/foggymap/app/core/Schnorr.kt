package de.foggymap.app.core

import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * BIP-340 Schnorr signatures on secp256k1 – what Nostr uses to sign events. Plain BigInteger
 * arithmetic (a few milliseconds per signature, which is plenty for a sync every few minutes).
 * Verified against the official BIP-340 test vectors.
 */
object Schnorr {
    private val P = BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEFFFFFC2F", 16)
    val N = BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141", 16)
    private val G = Point(
        BigInteger("79BE667EF9DCBBAC55A06295CE870B07029BFCDB2DCE28D959F2815B16F81798", 16),
        BigInteger("483ADA7726A3C4655DA4FBFC0E1108A8FD17B448A68554199C47D08FFB10D4B8", 16))
    private val SEVEN = BigInteger.valueOf(7)

    /** Affine point; null = point at infinity. */
    private data class Point(val x: BigInteger, val y: BigInteger)

    private fun add(a: Point?, b: Point?): Point? {
        if (a == null) return b
        if (b == null) return a
        if (a.x == b.x && a.y != b.y) return null
        val lam = if (a == b) {
            BigInteger.valueOf(3).multiply(a.x).multiply(a.x).mod(P).multiply(a.y.shiftLeft(1).modInverse(P)).mod(P)
        } else {
            b.y.subtract(a.y).mod(P).multiply(b.x.subtract(a.x).mod(P).modInverse(P)).mod(P)
        }
        val x = lam.multiply(lam).subtract(a.x).subtract(b.x).mod(P)
        val y = lam.multiply(a.x.subtract(x)).subtract(a.y).mod(P)
        return Point(x, y)
    }

    private fun mul(p: Point?, k: BigInteger): Point? {
        var r: Point? = null
        var q = p
        for (i in 0 until k.bitLength()) {
            if (k.testBit(i)) r = add(r, q)
            q = add(q, q)
        }
        return r
    }

    private fun liftX(x: BigInteger): Point? {
        if (x.signum() < 0 || x >= P) return null
        val c = x.modPow(BigInteger.valueOf(3), P).add(SEVEN).mod(P)
        val y = c.modPow(P.add(BigInteger.ONE).shiftRight(2), P)
        if (y.multiply(y).mod(P) != c) return null
        return Point(x, if (y.testBit(0)) P.subtract(y) else y)
    }

    private fun bytes32(v: BigInteger): ByteArray {
        val b = v.toByteArray()
        return when {
            b.size == 32 -> b
            b.size > 32 -> b.copyOfRange(b.size - 32, b.size)
            else -> ByteArray(32 - b.size) + b
        }
    }

    private fun int(b: ByteArray) = BigInteger(1, b)

    fun taggedHash(tag: String, vararg parts: ByteArray): ByteArray {
        val th = sha256(tag.toByteArray())
        val md = MessageDigest.getInstance("SHA-256")
        md.update(th); md.update(th)
        parts.forEach { md.update(it) }
        return md.digest()
    }

    fun sha256(b: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(b)

    /** x-only public key (32 bytes) of a 32-byte secret key. */
    fun publicKey(secret: ByteArray): ByteArray {
        val d = int(secret)
        require(d.signum() > 0 && d < N) { "invalid secret key" }
        return bytes32(mul(G, d)!!.x)
    }

    fun newSecretKey(random: SecureRandom = SecureRandom()): ByteArray {
        while (true) {
            val b = ByteArray(32).also { random.nextBytes(it) }
            val d = int(b)
            if (d.signum() > 0 && d < N) return b
        }
    }

    fun sign(message: ByteArray, secret: ByteArray, auxRand: ByteArray = ByteArray(32).also { SecureRandom().nextBytes(it) }): ByteArray {
        val d0 = int(secret)
        require(d0.signum() > 0 && d0 < N) { "invalid secret key" }
        val p = mul(G, d0)!!
        val d = if (p.y.testBit(0)) N.subtract(d0) else d0
        val t = bytes32(d).let { db -> val h = taggedHash("BIP0340/aux", auxRand); ByteArray(32) { (db[it].toInt() xor h[it].toInt()).toByte() } }
        val px = bytes32(p.x)
        val k0 = int(taggedHash("BIP0340/nonce", t, px, message)).mod(N)
        require(k0.signum() != 0)
        val r = mul(G, k0)!!
        val k = if (r.y.testBit(0)) N.subtract(k0) else k0
        val rx = bytes32(r.x)
        val e = int(taggedHash("BIP0340/challenge", rx, px, message)).mod(N)
        return rx + bytes32(k.add(e.multiply(d)).mod(N))
    }

    fun verify(message: ByteArray, publicKey: ByteArray, signature: ByteArray): Boolean {
        if (publicKey.size != 32 || signature.size != 64) return false
        val p = liftX(int(publicKey)) ?: return false
        val r = int(signature.copyOfRange(0, 32))
        val s = int(signature.copyOfRange(32, 64))
        if (r >= P || s >= N) return false
        val e = int(taggedHash("BIP0340/challenge", signature.copyOfRange(0, 32), publicKey, message)).mod(N)
        val rr = add(mul(G, s), mul(p, N.subtract(e))) ?: return false
        return !rr.y.testBit(0) && rr.x == r
    }
}

fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }
fun String.unhex(): ByteArray = ByteArray(length / 2) { substring(2 * it, 2 * it + 2).toInt(16).toByte() }
