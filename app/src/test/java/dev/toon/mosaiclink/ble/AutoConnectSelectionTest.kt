package dev.toon.mosaiclink.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AutoConnectSelectionTest {
    private val saved = Hk8Device("Saved watch", "AA:BB", -70)
    private val hk8 = Hk8Device("HK8 PRO MAX", "CC:DD", -60)
    private val other = Hk8Device("Headphones", "EE:FF", -40)

    @Test
    fun `remembered device wins over an HK8`() {
        val selected = selectAutoConnectTarget(
            devices = listOf(hk8, saved),
            preferredAddress = "aa:bb",
            allowHk8Fallback = true,
        )

        assertEquals(saved, selected)
    }

    @Test
    fun `first launch selects an HK8 immediately`() {
        val selected = selectAutoConnectTarget(
            devices = listOf(other, hk8),
            preferredAddress = null,
            allowHk8Fallback = false,
        )

        assertEquals(hk8, selected)
    }

    @Test
    fun `HK8 fallback waits while remembered device may still appear`() {
        val selected = selectAutoConnectTarget(
            devices = listOf(other, hk8),
            preferredAddress = saved.address,
            allowHk8Fallback = false,
        )

        assertNull(selected)
    }

    @Test
    fun `HK8 fallback is selected after remembered-device timeout`() {
        val selected = selectAutoConnectTarget(
            devices = listOf(other, hk8),
            preferredAddress = saved.address,
            allowHk8Fallback = true,
        )

        assertEquals(hk8, selected)
    }
}
