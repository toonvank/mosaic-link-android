package dev.toon.mosaiclink.clock2

/**
 * Byte-exact geometry patch for the bundled official wf_clock23 module.
 *
 * A physical HK8 grid test reported roughly 434 visible horizontal pixels,
 * while the stock module creates a 410x494 root centered at (205,247). The
 * previous 485x520 experiment overflowed on the watch. This conservative
 * profile preserves the proven 494-pixel height and changes only the visible
 * width to 434, with the analog center moved to (217,247).
 */
internal object WfClock23VisiblePanelPatch {
    const val OUTPUT_SHA256 =
        "0f9fde1e39375103aef22090b810f6d8fb78b95c273cc501d02779fa03c08bf4"

    private val STOCK_ROOT_INSTRUCTIONS = byteArrayOf(
        0x4f, 0xf4.toByte(), 0xf7.toByte(), 0x72,
        0x4f, 0xf4.toByte(), 0xcd.toByte(), 0x71,
    )
    private val VISIBLE_PANEL_ROOT_INSTRUCTIONS = byteArrayOf(
        0x4f, 0xf4.toByte(), 0xf7.toByte(), 0x72,
        0x4f, 0xf4.toByte(), 0xd9.toByte(), 0x71,
    )
    private val STOCK_ANALOG_CONFIG = byteArrayOf(
        0x05, 0x00,
        0xcd.toByte(), 0x00,
        0xf7.toByte(), 0x00,
        0x00, 0x00,
        0xec.toByte(), 0x09, 0x00, 0x00,
    )
    private val VISIBLE_PANEL_ANALOG_CONFIG = byteArrayOf(
        0x05, 0x00,
        0xd9.toByte(), 0x00,
        0xf7.toByte(), 0x00,
        0x00, 0x00,
        0xec.toByte(), 0x09, 0x00, 0x00,
    )
    private val STOCK_PAGE_GLUE_CALL = byteArrayOf(
        0x36, 0x4b,
        0x01, 0x21,
        0xe3.toByte(), 0x58,
        0x40, 0x46,
        0x98.toByte(), 0x47,
    )
    private val FIXED_PAGE_GLUE_CALL = byteArrayOf(
        0x36, 0x4b,
        0x00, 0x21,
        0xe3.toByte(), 0x58,
        0x40, 0x46,
        0x98.toByte(), 0x47,
    )

    fun apply(stockModule: ByteArray): ByteArray {
        val output = stockModule.copyOf()
        replaceExactlyOnce(
            output,
            STOCK_ROOT_INSTRUCTIONS,
            VISIBLE_PANEL_ROOT_INSTRUCTIONS,
            "stock root-size instructions",
        )
        replaceExactlyOnce(
            output,
            STOCK_ANALOG_CONFIG,
            VISIBLE_PANEL_ANALOG_CONFIG,
            "stock analog-center config",
        )
        replaceExactlyOnce(
            output,
            STOCK_PAGE_GLUE_CALL,
            FIXED_PAGE_GLUE_CALL,
            "stock page-glue call",
        )
        check(output.sha256() == OUTPUT_SHA256) {
            "Visible-panel native patch did not produce the pinned module"
        }
        return output
    }

    private fun replaceExactlyOnce(
        bytes: ByteArray,
        needle: ByteArray,
        replacement: ByteArray,
        label: String,
    ) {
        check(needle.size == replacement.size)
        val matches = (0..bytes.size - needle.size)
            .filter { offset ->
                needle.indices.all { index -> bytes[offset + index] == needle[index] }
            }
        check(matches.size == 1) {
            "Expected exactly one $label match, found ${matches.size}"
        }
        replacement.copyInto(bytes, matches.single())
    }
}
