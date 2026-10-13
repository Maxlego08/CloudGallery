package fr.eva.cloudgallery.data.dedup

import java.io.InputStream
import java.util.Base64

/**
 * Microsoft's QuickXorHash (OneDrive's `file.hashes.quickXorHash`), a port of the reference
 * implementation (learn.microsoft.com/onedrive/developer/code-snippets/quickxorhash):
 * 160-bit circular register in which byte n is XORed in at offset (11 × n) mod 160; the total
 * length (64 bits, little-endian) is XORed into the last 8 bytes of the result. Output: 20 bytes
 * in standard base64, like Graph.
 *
 * Incremental computation ([update] with chunks of any size, same result as all at once),
 * without per-byte allocation. Not thread-safe.
 */
class QuickXorHash {
    private val data = LongArray(CELLS)
    private var shiftSoFar = 0
    private var lengthSoFar = 0L

    fun update(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset) {
        require(offset >= 0 && length >= 0 && offset + length <= bytes.size)
        if (length == 0) return
        var vectorArrayIndex = shiftSoFar / 64
        var vectorOffset = shiftSoFar % 64
        val iterations = minOf(length, WIDTH_IN_BITS)
        for (i in 0 until iterations) {
            val isLastCell = vectorArrayIndex == CELLS - 1
            val bitsInVectorCell = if (isLastCell) WIDTH_IN_BITS % 64 else 64
            if (vectorOffset <= bitsInVectorCell - 8) {
                var j = offset + i
                while (j < offset + length) {
                    data[vectorArrayIndex] = data[vectorArrayIndex] xor ((bytes[j].toLong() and 0xFF) shl vectorOffset)
                    j += WIDTH_IN_BITS
                }
            } else {
                val index1 = vectorArrayIndex
                val index2 = if (isLastCell) 0 else vectorArrayIndex + 1
                val low = bitsInVectorCell - vectorOffset
                var xoredByte = 0L
                var j = offset + i
                while (j < offset + length) {
                    xoredByte = xoredByte xor (bytes[j].toLong() and 0xFF)
                    j += WIDTH_IN_BITS
                }
                data[index1] = data[index1] xor (xoredByte shl vectorOffset)
                data[index2] = data[index2] xor (xoredByte ushr low)
            }
            vectorOffset += SHIFT
            while (vectorOffset >= bitsInVectorCell) {
                vectorArrayIndex = if (isLastCell) 0 else vectorArrayIndex + 1
                vectorOffset -= bitsInVectorCell
            }
        }
        shiftSoFar = ((shiftSoFar + SHIFT.toLong() * (length % WIDTH_IN_BITS)) % WIDTH_IN_BITS).toInt()
        lengthSoFar += length
    }

    /** Hash of the bytes received (20 bytes). The object must not be used afterwards. */
    fun digest(): ByteArray {
        val out = ByteArray(WIDTH_IN_BITS / 8)
        for (cell in 0 until CELLS) {
            val bytesInCell = if (cell == CELLS - 1) out.size - cell * 8 else 8
            for (b in 0 until bytesInCell) out[cell * 8 + b] = (data[cell] ushr (8 * b)).toByte()
        }
        for (b in 0 until 8) {
            val index = out.size - 8 + b
            out[index] = (out[index].toInt() xor (lengthSoFar ushr (8 * b)).toInt()).toByte()
        }
        return out
    }

    /** Hash in standard base64 (Graph format). */
    fun digestBase64(): String = Base64.getEncoder().encodeToString(digest())

    companion object {
        private const val WIDTH_IN_BITS = 160
        private const val SHIFT = 11
        private const val CELLS = (WIDTH_IN_BITS - 1) / 64 + 1

        /**
         * Hash of [input] read to the end in chunks of [bufferBytes]. [onChunk] is called after
         * each chunk (total bytes read): it may throw to interrupt the read (work stopped).
         */
        fun of(input: InputStream, bufferBytes: Int = 256 * 1024, onChunk: (Long) -> Unit = {}): String {
            val hash = QuickXorHash()
            val buffer = ByteArray(bufferBytes)
            var total = 0L
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                if (n == 0) continue
                hash.update(buffer, 0, n)
                total += n
                onChunk(total)
            }
            return hash.digestBase64()
        }
    }
}
