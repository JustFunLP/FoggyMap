package de.foggymap.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** BIP-340 test vectors (bitcoin/bips, bip-0340/test-vectors.csv, BSD-2-Clause). */
class SchnorrTest {
    @Test fun officialTestVectors() {
        val lines = javaClass.classLoader!!.getResource("bip340-test-vectors.csv")!!.readText().lines().drop(1).filter { it.isNotBlank() }
        var signed = 0; var verified = 0
        for (line in lines) {
            val c = line.split(",")
            val (idx, sk, pk, aux, msg, sig, result) = c
            if (sk.isNotEmpty()) {
                assertEquals("vector $idx public key", pk.lowercase(), Schnorr.publicKey(sk.unhex()).hex())
                assertEquals("vector $idx signature", sig.lowercase(), Schnorr.sign(msg.unhex(), sk.unhex(), aux.unhex()).hex())
                signed++
            }
            assertEquals("vector $idx verification", result == "TRUE", Schnorr.verify(msg.unhex(), pk.unhex(), sig.unhex()))
            verified++
        }
        println("BIP-340: $signed signatures and $verified verifications match")
        assertTrue(signed >= 4 && verified >= 15)
    }

    @Test fun signAndVerifyRoundTrip() {
        val sk = Schnorr.newSecretKey()
        val pk = Schnorr.publicKey(sk)
        val msg = Schnorr.sha256("FoggyMap".toByteArray())
        val sig = Schnorr.sign(msg, sk)
        assertTrue(Schnorr.verify(msg, pk, sig))
        val bad = sig.copyOf().also { it[10] = (it[10] + 1).toByte() }
        assertTrue(!Schnorr.verify(msg, pk, bad))
        assertTrue(pk.contentEquals(Schnorr.publicKey(sk)))
    }
}

private operator fun <T> List<T>.component6() = this[5]
private operator fun <T> List<T>.component7() = this[6]
