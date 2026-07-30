package dev.toon.mosaiclink.clock2

/**
 * Quarantined, byte-exact patch for the bundled official wf_clock23 module.
 *
 * The stock module hard-codes a 410x494 root and an analog center of (205, 247).
 * This changes those geometry values and disables page glue for that oversized
 * root. Without the latter, LVGL treats it as a pannable page. Its lifecycle,
 * imports, resource paths, and executable size remain untouched.
 */
internal object WfClock23FullPanelPatch {
    const val OUTPUT_SHA256 =
        "0032e2bef7dad5fc80d760d02a76f1a68bfe1f99733386c82dc1d0540975784e"

    private val STOCK_ROOT_INSTRUCTIONS = byteArrayOf(
        0x4f, 0xf4.toByte(), 0xf7.toByte(), 0x72,
        0x4f, 0xf4.toByte(), 0xcd.toByte(), 0x71,
    )
    private val FULL_PANEL_ROOT_INSTRUCTIONS = byteArrayOf(
        0x4f, 0xf4.toByte(), 0x02, 0x72,
        0x40, 0xf2.toByte(), 0xe5.toByte(), 0x11,
    )
    private val STOCK_ANALOG_CONFIG = byteArrayOf(
        0x05, 0x00,
        0xcd.toByte(), 0x00,
        0xf7.toByte(), 0x00,
        0x00, 0x00,
        0xec.toByte(), 0x09, 0x00, 0x00,
    )
    private val FULL_PANEL_ANALOG_CONFIG = byteArrayOf(
        0x05, 0x00,
        0xf2.toByte(), 0x00,
        0x04, 0x01,
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
            FULL_PANEL_ROOT_INSTRUCTIONS,
            "stock root-size instructions",
        )
        replaceExactlyOnce(
            output,
            STOCK_ANALOG_CONFIG,
            FULL_PANEL_ANALOG_CONFIG,
            "stock analog-center config",
        )
        replaceExactlyOnce(
            output,
            STOCK_PAGE_GLUE_CALL,
            FIXED_PAGE_GLUE_CALL,
            "stock page-glue call",
        )
        check(output.sha256() == OUTPUT_SHA256) {
            "Full-panel native patch did not produce the pinned module"
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
