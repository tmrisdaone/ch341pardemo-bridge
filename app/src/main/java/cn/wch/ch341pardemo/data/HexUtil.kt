package cn.wch.ch341pardemo.data

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Hex <-> ASCII utilities. Kept tiny so it can be unit-tested without
 * pulling in the Android framework.
 */
object HexUtil {

    private val HEX = "0123456789ABCDEF".toCharArray()

    fun bytesToHex(bytes: ByteArray, separator: String = " "): String {
        if (bytes.isEmpty()) return ""
        val sb = StringBuilder(bytes.size * 3)
        for (i in bytes.indices) {
            val v = bytes[i].toInt() and 0xFF
            sb.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
            if (i != bytes.lastIndex && separator.isNotEmpty()) sb.append(separator)
        }
        return sb.toString()
    }

    fun hexToBytes(hex: String): ByteArray {
        val clean = hex.filter { !it.isWhitespace() && it != ',' && it != ':' && it != '-' }
        if (clean.length % 2 != 0) return ByteArray(0)
        val out = ByteArray(clean.length / 2)
        var i = 0
        while (i < clean.length) {
            val hi = Character.digit(clean[i], 16)
            val lo = Character.digit(clean[i + 1], 16)
            if (hi == -1 || lo == -1) return ByteArray(0)
            out[i / 2] = ((hi shl 4) or lo).toByte()
            i += 2
        }
        return out
    }

    /**
     * Render a byte array as a hex dump, 16 bytes per line, with offset +
     * ASCII gutter on the right. Useful for the "show as hex" terminal view.
     */
    fun hexDump(bytes: ByteArray, bytesPerLine: Int = 16): String {
        if (bytes.isEmpty()) return ""
        val sb = StringBuilder()
        var offset = 0
        while (offset < bytes.size) {
            val end = minOf(offset + bytesPerLine, bytes.size)
            sb.append(String.format(Locale.US, "%04X  ", offset))
            for (i in offset until end) {
                val v = bytes[i].toInt() and 0xFF
                sb.append(HEX[v ushr 4]).append(HEX[v and 0x0F]).append(' ')
            }
            // pad short line
            repeat((bytesPerLine - (end - offset)) * 3) { sb.append(' ') }
            sb.append(' ')
            for (i in offset until end) {
                val c = bytes[i].toInt() and 0xFF
                sb.append(if (c in 0x20..0x7E) c.toChar() else '.')
            }
            sb.append('\n')
            offset = end
        }
        return sb.toString()
    }

    fun formatTimestamp(epochMs: Long): String =
        SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(epochMs))
}
