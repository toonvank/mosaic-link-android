package dev.toon.mosaiclink.ble

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class SiFliProtocolTest {
    @Test
    fun packetsMatchProvenDesktopImplementation() {
        assertEquals(
            "0000070000000240e20100",
            SiFliProtocol.entireStart(123456).hex(),
        )
        assertEquals(
            "02000f000c00000009002f65782f612e62696e",
            SiFliProtocol.fileStart("/ex/a.bin", 12).hex(),
        )
        assertEquals(
            listOf(
                "04012800000102030405060708090a0b0c0d0e0f",
                "0402101112131415161718191a1b1c1d1e1f2021",
                "0403222324252627",
            ),
            SiFliProtocol.frames(ByteArray(40) { it.toByte() }, 23).map { it.hex() },
        )
    }

    @Test
    fun alignmentUsesCrc32Mpeg2() {
        assertEquals("ffffffff", SiFliProtocol.align(byteArrayOf()).hex())
        assertEquals(
            "61626300ff7c127d",
            SiFliProtocol.align("abc".toByteArray()).hex(),
        )
        assertEquals(
            "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f5099818f",
            SiFliProtocol.align(ByteArray(32) { it.toByte() }).hex(),
        )
    }

    @Test
    fun timePacketUsesLocalCalendarFields() {
        val time = ZonedDateTime.of(
            2026, 7, 29, 17, 42, 8, 0, ZoneId.of("Europe/Brussels"),
        )
        assertArrayEquals(
            byteArrayOf(
                0xAB.toByte(), 0, 11, 0xFF.toByte(), 0x93.toByte(), 0x80.toByte(), 0,
                0x07, 0xEA.toByte(), 7, 29, 17, 42, 8,
            ),
            SiFliProtocol.timePacket(time),
        )
    }

    @Test
    fun cancelCustomDialMatchesWearfitStartSendPicFileSize2() {
        assertArrayEquals(
            byteArrayOf(
                0xAD.toByte(), 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 2,
            ),
            SiFliProtocol.cancelCustomDial(),
        )
    }

    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }
}
