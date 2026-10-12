package fr.eva.cloudgallery.data.dedup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.Base64
import kotlin.random.Random

/**
 * QuickXorHash comparé à une implémentation de référence indépendante, écrite bit à bit d'après
 * la définition de l'algorithme (registre circulaire de 160 bits, octet n au décalage 11 × n,
 * longueur petit-boutiste combinée aux 8 derniers octets).
 */
class QuickXorHashTest {

    /** Définition littérale, sans l'optimisation par cellules de 64 bits de l'implémentation. */
    private fun referenceHash(bytes: ByteArray): String {
        val bits = BooleanArray(160)
        bytes.forEachIndexed { n, value ->
            val offset = ((11L * n) % 160).toInt()
            for (k in 0 until 8) {
                if ((value.toInt() shr k) and 1 == 1) {
                    val position = (offset + k) % 160
                    bits[position] = !bits[position]
                }
            }
        }
        val out = ByteArray(20)
        for (position in 0 until 160) {
            if (bits[position]) out[position / 8] = (out[position / 8].toInt() or (1 shl (position % 8))).toByte()
        }
        val length = bytes.size.toLong()
        for (b in 0 until 8) out[12 + b] = (out[12 + b].toInt() xor (length ushr (8 * b)).toInt()).toByte()
        return Base64.getEncoder().encodeToString(out)
    }

    private fun hashOf(bytes: ByteArray): String = QuickXorHash().apply { update(bytes) }.digestBase64()

    @Test
    fun emptyInput_isTwentyZeroBytes() {
        assertEquals("AAAAAAAAAAAAAAAAAAAAAAAAAAA=", hashOf(ByteArray(0)))
        assertEquals("AAAAAAAAAAAAAAAAAAAAAAAAAAA=", referenceHash(ByteArray(0)))
    }

    @Test
    fun singleByte_matchesTheDefinition() {
        // Octet 0x01 au décalage 0, longueur 1 dans l'octet 12 : 0x01 00… 01 00…
        val expected = ByteArray(20).also { it[0] = 1; it[12] = 1 }
        assertEquals(Base64.getEncoder().encodeToString(expected), hashOf(byteArrayOf(1)))
    }

    @Test
    fun matchesTheReference_forVariousSizes_includingCellAndRegisterWraps() {
        val random = Random(42)
        for (size in listOf(1, 2, 7, 8, 15, 20, 31, 64, 159, 160, 161, 319, 320, 1000, 4096, 65_537)) {
            val data = random.nextBytes(size)
            assertEquals("taille $size", referenceHash(data), hashOf(data))
        }
    }

    @Test
    fun chunkedUpdates_giveTheSameResultAsOneUpdate() {
        val data = Random(7).nextBytes(10_000)
        val whole = hashOf(data)
        for (chunk in listOf(1, 3, 11, 159, 160, 161, 4096)) {
            val hash = QuickXorHash()
            var offset = 0
            while (offset < data.size) {
                val n = minOf(chunk, data.size - offset)
                hash.update(data, offset, n)
                offset += n
            }
            assertEquals("morceaux de $chunk", whole, hash.digestBase64())
        }
        assertEquals(whole, QuickXorHash.of(ByteArrayInputStream(data), bufferBytes = 333))
    }

    @Test
    fun differentContentOrLength_givesADifferentHash() {
        val data = ByteArray(500) { (it % 251).toByte() }
        val changed = data.copyOf().also { it[250] = (it[250] + 1).toByte() }
        assertNotEquals(hashOf(data), hashOf(changed))
        // Zéros ajoutés : seul le compteur de longueur change, l'empreinte aussi.
        assertNotEquals(hashOf(ByteArray(10)), hashOf(ByteArray(11)))
    }

    @Test
    fun stream_reportsProgress_andCanBeInterrupted() {
        val data = ByteArray(1000)
        val seen = ArrayList<Long>()
        QuickXorHash.of(ByteArrayInputStream(data), bufferBytes = 400) { seen += it }
        assertEquals(listOf(400L, 800L, 1000L), seen)
        val stopped = runCatching {
            QuickXorHash.of(ByteArrayInputStream(data), bufferBytes = 400) { if (it >= 400) throw IllegalStateException("arrêt") }
        }
        assertEquals("arrêt", stopped.exceptionOrNull()?.message)
    }
}
