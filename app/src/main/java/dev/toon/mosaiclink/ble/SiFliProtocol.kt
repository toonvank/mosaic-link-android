package dev.toon.mosaiclink.ble

import dev.toon.mosaiclink.clock2.Crc32Mpeg2
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.ZonedDateTime
import java.util.zip.ZipInputStream

internal data class TransferResponse(
    val command: Int,
    val result: Int,
    val expectedIndex: Int = 0,
    val maxDataLength: Int = 0,
    val version: Int = 0,
    val blockLength: Int = 0,
    val blocksLeft: Int = 0,
)

internal data class TransferFile(val path: String, val bytes: ByteArray)

internal object SiFliProtocol {
    private const val CATEGORY = 4
    private const val PHONE_TYPE = 2
    const val MTU_CAP = 247
    const val DEFAULT_SLICE = 4096

    fun entireStart(totalBytes: Int): ByteArray = request(
        0,
        ByteBuffer.allocate(7).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(0)
            .put(PHONE_TYPE.toByte())
            .putInt(totalBytes)
            .array(),
    )

    fun fileStart(path: String, length: Int): ByteArray {
        val name = path.toByteArray(Charsets.UTF_8)
        return request(
            2,
            ByteBuffer.allocate(6 + name.size).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(length)
                .putShort(name.size.toShort())
                .put(name)
                .array(),
        )
    }

    fun fileData(index: Int, bytes: ByteArray): ByteArray = request(
        4,
        ByteBuffer.allocate(4 + bytes.size).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(index)
            .put(bytes)
            .array(),
    )

    fun fileEnd(): ByteArray = request(6)
    fun entireEnd(): ByteArray = request(8)
    fun fileSpace(blocks: Int): ByteArray = request(
        13,
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(blocks).array(),
    )

    /**
     * Wearfit's `startSendPic(fileSize=2)` command — cancels a stuck
     * custom-dial loading state on the watch. Written to the Nordic UART
     * write characteristic (6e400002), not the SiFli transport char.
     *
     * Without this, a failed or interrupted type-3 custom photo dial
     * upload leaves the watch in a perpetual "transfer in progress"
     * state, causing it to BLE-advertise at ~5× the normal rate and
     * drain its battery in hours instead of weeks.
     */
    fun cancelCustomDial(): ByteArray = byteArrayOf(
        0xAD.toByte(), 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 2,
    )

    fun timePacket(time: ZonedDateTime): ByteArray = byteArrayOf(
        0xAB.toByte(), 0, 11, 0xFF.toByte(), 0x93.toByte(), 0x80.toByte(), 0,
        ((time.year ushr 8) and 0xff).toByte(),
        (time.year and 0xff).toByte(),
        time.monthValue.toByte(),
        time.dayOfMonth.toByte(),
        time.hour.toByte(),
        time.minute.toByte(),
        time.second.toByte(),
    )

    fun frames(payload: ByteArray, negotiatedMtu: Int): List<ByteArray> {
        val mtu = negotiatedMtu.coerceIn(23, MTU_CAP)
        val firstCapacity = mtu - 7
        val continuingCapacity = mtu - 5
        if (payload.size <= firstCapacity) {
            return listOf(
                byteArrayOf(CATEGORY.toByte(), 0) +
                    littleShort(payload.size) + payload,
            )
        }
        val output = mutableListOf<ByteArray>()
        output += byteArrayOf(CATEGORY.toByte(), 1) +
            littleShort(payload.size) + payload.copyOfRange(0, firstCapacity)
        var offset = firstCapacity
        while (offset < payload.size) {
            val end = minOf(payload.size, offset + continuingCapacity)
            val final = end == payload.size
            output += byteArrayOf(CATEGORY.toByte(), if (final) 3 else 2) +
                payload.copyOfRange(offset, end)
            offset = end
        }
        return output
    }

    fun response(notification: ByteArray): TransferResponse? {
        if (notification.size < 8) return null
        val flag = notification[1].toInt() and 0xff
        val payload = when (flag) {
            0, 1 -> notification.copyOfRange(4, notification.size)
            else -> notification.copyOfRange(2, notification.size)
        }
        if (payload.size < 4) return null
        val command = payload[0].toInt() and 0xff
        val buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        val result = buffer.getShort(2).toInt()
        return when {
            command == 1 && payload.size >= 14 -> TransferResponse(
                command = command,
                result = result,
                maxDataLength = buffer.getShort(4).toInt(),
                version = buffer.getShort(6).toInt(),
                blockLength = buffer.getShort(8).toInt(),
                blocksLeft = buffer.getInt(10),
            )
            command in setOf(5, 10) && payload.size >= 8 -> TransferResponse(
                command = command,
                result = result,
                expectedIndex = buffer.getInt(4),
            )
            else -> TransferResponse(command, result)
        }
    }

    fun filesFromZip(packageBytes: ByteArray): List<TransferFile> {
        val files = mutableListOf<TransferFile>()
        ZipInputStream(ByteArrayInputStream(packageBytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory && entry.name.isNotBlank() && !entry.name.startsWith(".")) {
                    files += TransferFile(
                        path = "/" + entry.name.replace('\\', '/').trimStart('/'),
                        bytes = align(zip.readBytes()),
                    )
                }
                zip.closeEntry()
            }
        }
        require(files.isNotEmpty()) { "Watchface package contains no files" }
        return files.sortedByDescending { it.bytes.size }
    }

    fun align(data: ByteArray): ByteArray {
        val padding = ByteArray((4 - data.size % 4) % 4)
        val crc = Crc32Mpeg2.compute(padding, Crc32Mpeg2.compute(data))
        return data + padding +
            ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(crc).array()
    }

    private fun request(command: Int, data: ByteArray = byteArrayOf()): ByteArray =
        ByteBuffer.allocate(4 + data.size).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(command.toShort())
            .putShort(data.size.toShort())
            .put(data)
            .array()

    private fun littleShort(value: Int): ByteArray =
        ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(value.toShort()).array()
}
