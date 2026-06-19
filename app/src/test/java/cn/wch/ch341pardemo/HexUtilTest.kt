package cn.wch.ch341pardemo

import cn.wch.ch341pardemo.data.HexUtil
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HexUtilTest {

    @Test
    fun bytesToHex_knownVector() {
        assertEquals("00FFAA12", HexUtil.bytesToHex(byteArrayOf(0x00, 0xFF.toByte(), 0xAA.toByte(), 0x12), ""))
    }

    @Test
    fun bytesToHex_withSeparator() {
        assertEquals("DE AD BE EF", HexUtil.bytesToHex(byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte()), " "))
    }

    @Test
    fun bytesToHex_empty() {
        assertEquals("", HexUtil.bytesToHex(ByteArray(0), " "))
    }

    @Test
    fun hexToBytes_knownVector() {
        assertArrayEquals(
            byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte()),
            HexUtil.hexToBytes("DEADBEEF")
        )
    }

    @Test
    fun hexToBytes_withSeparators() {
        assertArrayEquals(
            byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte()),
            HexUtil.hexToBytes("DE:AD:BE:EF")
        )
    }

    @Test
    fun hexToBytes_oddLengthReturnsEmpty() {
        assertArrayEquals(ByteArray(0), HexUtil.hexToBytes("ABC"))
    }

    @Test
    fun hexToBytes_invalidCharReturnsEmpty() {
        assertArrayEquals(ByteArray(0), HexUtil.hexToBytes("XY"))
    }

    @Test
    fun hexDump_containsAsciiGutter() {
        val out = HexUtil.hexDump("Hello".toByteArray(Charsets.UTF_8))
        assertTrue(out.contains("Hello"))
        assertTrue(out.startsWith("0000  "))
    }
}
