package dev.toon.mosaiclink.clock2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile

class WfClock23FullPanelPatchTest {
    @Test
    fun bundledOfficialModuleProducesPinnedFullPanelElf() {
        val donor = File("src/main/assets/stock_wf_clock23.zip")
        assertTrue("Bundled donor is missing", donor.isFile)
        val stock = ZipFile(donor).use { zip ->
            zip.getInputStream(zip.getEntry("ex/installer_wf/wf_clock23.so")).readBytes()
        }

        val patched = WfClock23FullPanelPatch.apply(stock)

        assertEquals(stock.size, patched.size)
        assertEquals(WfClock23FullPanelPatch.OUTPUT_SHA256, patched.sha256())
        assertFalse(stock.contentEquals(patched))
        assertEquals(0x7f, patched[0].toInt())
        assertEquals('E'.code, patched[1].toInt())
        assertEquals('L'.code, patched[2].toInt())
        assertEquals('F'.code, patched[3].toInt())
    }

    @Test(expected = IllegalStateException::class)
    fun refusesAnUnknownModule() {
        WfClock23FullPanelPatch.apply(ByteArray(3416))
    }
}
