package eu.kanade.tachiyomi.source.rules

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.zip.CRC32
import java.util.zip.Inflater

/**
 * Decodes a PipiMiao ppcat://ruleLink data payload into its .mh_rules JSON record.
 *
 * PipiMiao's share payload is an intentionally rearranged ZIP/DEFLATE stream:
 * the local header is truncated, the DEFLATE stream wraps around the central
 * directory, and exactly two padding bytes are inserted at its boundaries.
 * The central-directory CRC and compressed size are still available.
 *
 * No rule-supplied executable code is evaluated here.
 */
object PipiMiaoLinkDecoder {
    private const val LIMIT = 2_000_000
    private val centralSignature = byteArrayOf(0x50, 0x4b, 0x01, 0x02)
    private val endSignature = byteArrayOf(0x50, 0x4b, 0x05, 0x06)

    fun decode(link: String): JSONObject {
        val trimmed = link.trim()
        require(trimmed.startsWith("ppcat://ruleLink?", ignoreCase = true)) {
            "Expected a ppcat://ruleLink link"
        }
        val marker = trimmed.indexOf("data=")
        require(marker >= 0) { "PipiMiao link has no data payload" }
        val encoded = trimmed.substring(marker + 5).substringBefore('&')
        require(encoded.length in 24..(LIMIT * 2)) { "PipiMiao payload too large or empty" }
        val b64 = URLDecoder.decode(encoded.replace("+", "%2B"), "UTF-8")
        val bytes = Base64.getDecoder().decode(b64)
        require(bytes.size in 70..LIMIT) { "PipiMiao payload exceeds 2 MB" }

        val central = find(bytes, centralSignature)
        val end = find(bytes, endSignature, central + centralSignature.size)
        require(central >= 0 && end > central) { "Invalid PipiMiao ZIP directory" }
        require(central + 46 <= bytes.size) { "Truncated ZIP directory" }
        require(short(bytes, central + 10) == 8) { "Only DEFLATE PipiMiao rules are supported" }

        val crc = uint(bytes, central + 16)
        val compressedSize = uint(bytes, central + 20)
        val fileNameSize = short(bytes, central + 28)
        val extraSize = short(bytes, central + 30)
        require(compressedSize in 1..LIMIT.toLong()) { "Compressed rule is too large" }
        require(central + 46 + fileNameSize <= bytes.size) { "Truncated ZIP entry name" }
        val name = String(bytes, central + 46, fileNameSize, StandardCharsets.UTF_8)
        require(name == ".mh_rules") { "Unsupported PipiMiao entry: $name" }

        // The local stream starts after the 20 available bytes of the EOCD
        // and the remaining local-header fields and entry filename.
        val payloadStart = end + 40 + fileNameSize + extraSize
        require(payloadStart in 0..bytes.size) { "Truncated ZIP rule data" }
        val tailSize = bytes.size - payloadStart
        val inflatedStream = bytes.copyOfRange(payloadStart, bytes.size) +
            bytes.copyOfRange(0, central)
        // Two obfuscation bytes may be at the tail, head, or split.
        require(inflatedStream.size == compressedSize.toInt() + 2) {
            "Unrecognized PipiMiao rule layout"
        }

        val candidates = listOf(
            tailSize - 2 to tailSize - 1,
            0 to 1,
            0 to tailSize - 1,
        )
        for ((first, second) in candidates) {
            if (first !in inflatedStream.indices || second !in inflatedStream.indices || first == second) {
                continue
            }
            val raw = ByteArray(inflatedStream.size - 2)
            var target = 0
            for (i in inflatedStream.indices) {
                if (i != first && i != second) raw[target++] = inflatedStream[i]
            }
            val jsonBytes = inflate(raw) ?: continue
            val checksum = CRC32().apply { update(jsonBytes) }.value
            if (checksum == crc) {
                return JSONObject(String(jsonBytes, StandardCharsets.UTF_8))
            }
        }
        throw IllegalArgumentException("Unable to decode PipiMiao rule (DEFLATE/CRC mismatch)")
    }

    private fun inflate(raw: ByteArray): ByteArray? {
        val inflater = Inflater(true)
        return try {
            inflater.setInput(raw)
            val out = ByteArrayOutputStream()
            val chunk = ByteArray(8192)
            while (!inflater.finished()) {
                val amount = inflater.inflate(chunk)
                if (amount > 0) {
                    require(out.size() + amount <= LIMIT) { "PipiMiao JSON exceeds 2 MB" }
                    out.write(chunk, 0, amount)
                } else if (inflater.needsInput() || inflater.needsDictionary()) {
                    return null
                } else {
                    return null
                }
            }
            out.toByteArray()
        } catch (_: java.util.zip.DataFormatException) {
            null
        } finally {
            inflater.end()
        }
    }

    private fun short(b: ByteArray, i: Int): Int =
        (b[i].toInt() and 255) or ((b[i + 1].toInt() and 255) shl 8)

    private fun uint(b: ByteArray, i: Int): Long =
        (short(b, i).toLong() and 65535) or (short(b, i + 2).toLong() shl 16)

    private fun find(b: ByteArray, signature: ByteArray, start: Int = 0): Int {
        if (start < 0) return -1
        for (i in start..(b.size - signature.size)) {
            if (signature.indices.all { b[i + it] == signature[it] }) return i
        }
        return -1
    }
}
