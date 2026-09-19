/*
 * EDUVPN - one-tap OpenVPN client.
 *
 * Plain Kotlin (no android.* imports) so it can be exercised on a plain JVM.
 */
package com.eduvpn.onetap.domain

/**
 * Minimal, dependency-free Base64 decoder.
 *
 * Why hand-rolled instead of a platform API:
 *  - [java.util.Base64] only exists from Android API 26 onwards.
 *  - `android.util.Base64` would drag the Android framework into a module that
 *    is unit-tested on a plain JVM.
 *
 * The decoder is intentionally lenient ("MIME" style): every character outside
 * the Base64 alphabet - line breaks, spaces, stray padding - is ignored, which
 * is what you want for a payload that travelled through an HTTP CSV field.
 */
internal object Base64Compat {

    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    /** -1 marks a character that is not part of the alphabet. */
    private val LOOKUP: IntArray = IntArray(128) { -1 }.also { table ->
        ALPHABET.forEachIndexed { index, char -> table[char.code] = index }
    }

    /**
     * Decodes Base64 text into bytes, ignoring any non-alphabet characters.
     * Returns null when the meaningful payload is empty.
     *
     * Padding characters are simply not collected, so the length of the collected
     * sextet list decides the output size: every whole group of 4 sextets yields
     * 3 bytes, and a trailing group of 2 or 3 sextets yields 1 or 2 bytes.
     */
    fun decode(input: String): ByteArray? {
        val sextets = ArrayList<Int>(input.length / 4 * 3)
        for (char in input) {
            if (char.code >= LOOKUP.size) continue // non-ASCII noise
            val value = LOOKUP[char.code]
            if (value >= 0) sextets.add(value)
        }
        if (sextets.size < 2) return null

        val fullGroups = sextets.size / 4
        val trailingSextets = sextets.size % 4
        val outSize = fullGroups * 3 + if (trailingSextets >= 2) trailingSextets - 1 else 0
        val out = ByteArray(outSize)

        var outIndex = 0
        var index = 0
        while (index + 3 < sextets.size) {
            val b0 = sextets[index]
            val b1 = sextets[index + 1]
            val b2 = sextets[index + 2]
            val b3 = sextets[index + 3]
            out[outIndex++] = ((b0 shl 2) or (b1 shr 4)).toByte()
            out[outIndex++] = ((b1 shl 4) or (b2 shr 2)).toByte()
            out[outIndex++] = ((b2 shl 6) or b3).toByte()
            index += 4
        }
        when (sextets.size - index) {
            2 -> {
                out[outIndex++] = ((sextets[index] shl 2) or (sextets[index + 1] shr 4)).toByte()
            }
            3 -> {
                out[outIndex++] = ((sextets[index] shl 2) or (sextets[index + 1] shr 4)).toByte()
                out[outIndex++] = ((sextets[index + 1] shl 4) or (sextets[index + 2] shr 2)).toByte()
            }
        }
        check(outIndex == outSize) { "Base64 decoder size mismatch: $outIndex != $outSize" }
        return out
    }

    /** Inverse of [decode]; only used by the test harness. */
    fun encode(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size / 3 * 4 + 4)
        var i = 0
        while (i + 2 < bytes.size) {
            val n = (bytes[i].toInt() and 0xFF shl 16) or
                (bytes[i + 1].toInt() and 0xFF shl 8) or
                (bytes[i + 2].toInt() and 0xFF)
            sb.append(ALPHABET[n ushr 18 and 0x3F])
            sb.append(ALPHABET[n ushr 12 and 0x3F])
            sb.append(ALPHABET[n ushr 6 and 0x3F])
            sb.append(ALPHABET[n and 0x3F])
            i += 3
        }
        when (bytes.size - i) {
            1 -> {
                val n = bytes[i].toInt() and 0xFF shl 16
                sb.append(ALPHABET[n ushr 18 and 0x3F])
                sb.append(ALPHABET[n ushr 12 and 0x3F])
                sb.append("==")
            }
            2 -> {
                val n = (bytes[i].toInt() and 0xFF shl 16) or (bytes[i + 1].toInt() and 0xFF shl 8)
                sb.append(ALPHABET[n ushr 18 and 0x3F])
                sb.append(ALPHABET[n ushr 12 and 0x3F])
                sb.append(ALPHABET[n ushr 6 and 0x3F])
                sb.append('=')
            }
        }
        return sb.toString()
    }
}
