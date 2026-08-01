package dev.toon.mosaiclink.clock2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class DigitalRuntimePatchTest {
    @Test
    fun bundledRuntimeHasPinnedPatchableLayout() {
        val stock = File("src/main/assets/wf_clock443_digital.so").readBytes()
        val positions = List(DigitalRuntimePatch.SLOT_COUNT) { index ->
            DigitalRuntimePatch.Position(10 + index * 7, 20 + index * 9)
        }
        val sequences = List(DigitalRuntimePatch.SEQUENCE_SLOT_COUNT) { index ->
            DigitalRuntimePatch.Position(100 + index * 11, 120 + index * 13)
        }

        val patched = DigitalRuntimePatch.apply(stock, positions, sequences)

        assertEquals(stock.size, patched.size)
        assertFalse(stock.contentEquals(patched))
        assertEquals(0x7f, patched[0].toInt())
        assertEquals('E'.code, patched[1].toInt())
        val marker = "MOSAIC-DIGITAL02".toByteArray(Charsets.US_ASCII)
        val markerOffset = patched.indexOfSubsequence(marker)
        val buffer = ByteBuffer.wrap(patched).order(ByteOrder.LITTLE_ENDIAN)
        positions.forEachIndexed { index, position ->
            val offset = markerOffset + marker.size + index * 20
            assertEquals(position.x, buffer.getShort(offset + 2).toInt() and 0xffff)
            assertEquals(position.y, buffer.getShort(offset + 4).toInt() and 0xffff)
        }
        val sequencesOffset = markerOffset + marker.size +
            DigitalRuntimePatch.NUMBER_SLOT_COUNT * 20
        sequences.forEachIndexed { index, position ->
            val offset = sequencesOffset + index * 36
            assertEquals(position.x, buffer.getShort(offset + 2).toInt() and 0xffff)
            assertEquals(position.y, buffer.getShort(offset + 4).toInt() and 0xffff)
        }
    }

    @Test(expected = IllegalStateException::class)
    fun refusesUnknownRuntimeBytes() {
        DigitalRuntimePatch.apply(
            ByteArray(8232),
            List(DigitalRuntimePatch.SLOT_COUNT) { DigitalRuntimePatch.Position() },
        )
    }

    private fun ByteArray.indexOfSubsequence(needle: ByteArray): Int {
        for (start in 0..size - needle.size) {
            if (needle.indices.all { this[start + it] == needle[it] }) return start
        }
        assertTrue("marker missing", false)
        return -1
    }
}
