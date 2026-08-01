package dev.toon.mosaiclink.clock2

import java.nio.ByteBuffer
import java.nio.ByteOrder

internal object DigitalRuntimePatch {
    const val INPUT_SHA256 =
        "5f3c46f3bf80ee9d7c9efe09718a5379cf2da45c83cff208bc7275912a3f7684"
    const val NUMBER_SLOT_COUNT = 9
    const val SEQUENCE_SLOT_COUNT = 3
    const val SLOT_COUNT = NUMBER_SLOT_COUNT
    private const val NUMBER_CONFIG_BYTES = 20
    private const val SEQUENCE_CONFIG_BYTES = 36
    private val marker = "MOSAIC-DIGITAL02".toByteArray(Charsets.US_ASCII)

    data class Position(val x: Int = 600, val y: Int = 600)

    fun apply(
        module: ByteArray,
        positions: List<Position>,
        sequencePositions: List<Position> = List(SEQUENCE_SLOT_COUNT) { Position() },
    ): ByteArray {
        check(module.sha256() == INPUT_SHA256) {
            "Bundled live-digital runtime hash mismatch"
        }
        require(positions.size == SLOT_COUNT) {
            "Live-digital runtime requires exactly $SLOT_COUNT slot positions"
        }
        require(sequencePositions.size == SEQUENCE_SLOT_COUNT) {
            "Live-digital runtime requires exactly $SEQUENCE_SLOT_COUNT sequence positions"
        }
        (positions + sequencePositions).forEachIndexed { index, position ->
            require(position.x in 0..600 && position.y in 0..600) {
                "Digital slot $index is outside the patchable coordinate range"
            }
        }
        val markerOffset = module.findUnique(marker)
        val result = module.copyOf()
        val buffer = ByteBuffer.wrap(result).order(ByteOrder.LITTLE_ENDIAN)
        val configsOffset = markerOffset + marker.size
        positions.forEachIndexed { index, position ->
            val configOffset = configsOffset + index * NUMBER_CONFIG_BYTES
            check(buffer.getShort(configOffset + 2).toInt() and 0xffff == 600)
            check(buffer.getShort(configOffset + 4).toInt() and 0xffff == 600)
            buffer.putShort(configOffset + 2, position.x.toShort())
            buffer.putShort(configOffset + 4, position.y.toShort())
        }
        val sequencesOffset = configsOffset + NUMBER_SLOT_COUNT * NUMBER_CONFIG_BYTES
        sequencePositions.forEachIndexed { index, position ->
            val configOffset = sequencesOffset + index * SEQUENCE_CONFIG_BYTES
            check(buffer.getShort(configOffset + 2).toInt() and 0xffff == 600)
            check(buffer.getShort(configOffset + 4).toInt() and 0xffff == 600)
            buffer.putShort(configOffset + 2, position.x.toShort())
            buffer.putShort(configOffset + 4, position.y.toShort())
        }
        return result
    }

    private fun ByteArray.findUnique(needle: ByteArray): Int {
        val matches = indices.filter { start ->
            start + needle.size <= size && needle.indices.all { offset ->
                this[start + offset] == needle[offset]
            }
        }
        check(matches.size == 1) { "Live-digital runtime marker is not unique" }
        return matches.single()
    }
}
