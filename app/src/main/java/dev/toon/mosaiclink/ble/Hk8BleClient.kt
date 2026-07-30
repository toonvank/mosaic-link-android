@file:Suppress("DEPRECATION", "MissingPermission")

package dev.toon.mosaiclink.ble

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.os.Build
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.time.ZonedDateTime
import java.util.UUID
import kotlin.math.ceil

data class Hk8Device(val name: String, val address: String, val rssi: Int?)

sealed interface BleConnectionState {
    data object Disconnected : BleConnectionState
    data object Scanning : BleConnectionState
    data class Connecting(val device: Hk8Device) : BleConnectionState
    data class Connected(val device: Hk8Device, val mtu: Int) : BleConnectionState
    data class Failed(val message: String) : BleConnectionState
}

data class UploadProgress(
    val fileIndex: Int,
    val fileCount: Int,
    val fileName: String,
    val bytesSent: Long,
    val totalBytes: Long,
) {
    val fraction: Float
        get() = if (totalBytes == 0L) 0f else bytesSent.toFloat() / totalBytes
}

class Hk8BleClient(private val context: Context) {
    companion object {
        private val SIFLI_UUID = UUID.fromString("00000000-0000-0200-6473-5f696c666973")
        private val WATCH_WRITE_UUID =
            UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")
        private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    private val manager = context.getSystemService(BluetoothManager::class.java)
    private val adapter: BluetoothAdapter get() = manager.adapter
    private val responseChannel = Channel<TransferResponse>(Channel.UNLIMITED)
    private val writeMutex = Mutex()

    private var gatt: BluetoothGatt? = null
    private var sifliCharacteristic: BluetoothGattCharacteristic? = null
    private var watchWriteCharacteristic: BluetoothGattCharacteristic? = null
    private var connectedDevice: Hk8Device? = null
    private var negotiatedMtu = 23
    private var connectionReady: CompletableDeferred<Unit>? = null
    private var pendingWrite: CompletableDeferred<Unit>? = null
    private var pendingDescriptor: CompletableDeferred<Unit>? = null

    suspend fun findWatch(
        preferredAddress: String? = null,
        timeoutMillis: Long = 12_000,
    ): Hk8Device {
        check(adapter.isEnabled) { "Bluetooth is turned off" }
        val bondedWatches = adapter.bondedDevices
            .filter { it.name?.contains("HK8", ignoreCase = true) == true }
        if (preferredAddress == null) {
            bondedWatches.firstOrNull()
                ?.let { return Hk8Device(it.name ?: "HK8 PRO MAX", it.address, null) }
        }

        val result = CompletableDeferred<Hk8Device>()
        var fallback = bondedWatches
            .firstOrNull { !it.address.equals(preferredAddress, ignoreCase = true) }
            ?.let {
                Hk8Device(it.name ?: "HK8 PRO MAX", it.address, null)
            }
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, scanResult: ScanResult) {
                val name = scanResult.device.name ?: scanResult.scanRecord?.deviceName
                if (name?.contains("HK8", ignoreCase = true) != true || result.isCompleted) {
                    return
                }
                val device = Hk8Device(name, scanResult.device.address, scanResult.rssi)
                if (
                    preferredAddress == null ||
                    device.address.equals(preferredAddress, ignoreCase = true)
                ) {
                    result.complete(device)
                } else if (fallback == null) {
                    fallback = device
                }
            }

            override fun onScanFailed(errorCode: Int) {
                if (!result.isCompleted) {
                    result.completeExceptionally(
                        IllegalStateException("Bluetooth scan failed ($errorCode)"),
                    )
                }
            }
        }
        adapter.bluetoothLeScanner.startScan(callback)
        return try {
            withTimeoutOrNull(timeoutMillis) { result.await() }
                ?: fallback
                ?: error("No HK8 watch found nearby")
        } finally {
            adapter.bluetoothLeScanner.stopScan(callback)
        }
    }

    /**
     * Lists nearby BLE devices without assuming the clone's advertised name.
     * Compatibility is verified only after the user selects a device and GATT
     * service discovery finds both required SiFli characteristics.
     */
    suspend fun scanNearby(timeoutMillis: Long = 12_000): List<Hk8Device> {
        check(adapter.isEnabled) { "Bluetooth is turned off" }
        val devices = linkedMapOf<String, Hk8Device>()
        val finished = CompletableDeferred<Unit>()
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, scanResult: ScanResult) {
                val name = scanResult.device.name ?: scanResult.scanRecord?.deviceName
                devices[scanResult.device.address] = Hk8Device(
                    name = name?.takeIf(String::isNotBlank) ?: "Unnamed BLE device",
                    address = scanResult.device.address,
                    rssi = scanResult.rssi,
                )
            }

            override fun onScanFailed(errorCode: Int) {
                finished.completeExceptionally(
                    IllegalStateException("Bluetooth scan failed ($errorCode)"),
                )
            }
        }
        adapter.bluetoothLeScanner.startScan(callback)
        try {
            withTimeoutOrNull(timeoutMillis) { finished.await() }
        } finally {
            adapter.bluetoothLeScanner.stopScan(callback)
        }
        return devices.values.sortedWith(
            compareByDescending<Hk8Device> { it.name.contains("HK8", ignoreCase = true) }
                .thenByDescending { it.rssi ?: Int.MIN_VALUE },
        )
    }

    suspend fun connect(device: Hk8Device): BleConnectionState.Connected {
        disconnect()
        connectedDevice = device
        negotiatedMtu = 23
        connectionReady = CompletableDeferred()
        val bluetoothDevice = adapter.getRemoteDevice(device.address)
        gatt = bluetoothDevice.connectGatt(
            context,
            false,
            callback,
            BluetoothDeviceTransport.LE,
        )
        withTimeout(30_000) { requireNotNull(connectionReady).await() }
        return BleConnectionState.Connected(device, negotiatedMtu)
    }

    fun disconnect() {
        connectionReady?.cancel()
        pendingWrite?.cancel()
        pendingDescriptor?.cancel()
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        sifliCharacteristic = null
        watchWriteCharacteristic = null
        connectedDevice = null
    }

    suspend fun syncTime(time: ZonedDateTime = ZonedDateTime.now()) {
        write(requireNotNull(watchWriteCharacteristic), SiFliProtocol.timePacket(time))
    }

    suspend fun uploadWatchface(
        packageBytes: ByteArray,
        onProgress: (UploadProgress) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val files = SiFliProtocol.filesFromZip(packageBytes)
        val total = files.sumOf { it.bytes.size.toLong() }
        var sent = 0L
        val start = sendAndWait(SiFliProtocol.entireStart(total.toInt()), 1)
        check(start.result == 0) { "Watch rejected transfer start (${start.result})" }
        var sliceSize = SiFliProtocol.DEFAULT_SLICE
        if (start.maxDataLength > 0) sliceSize = minOf(sliceSize, start.maxDataLength)
        if (start.version > 0) {
            check(start.blockLength > 0) { "Watch reported an invalid block size" }
            val blocks = files.sumOf {
                ceil(it.bytes.size.toDouble() / start.blockLength).toInt()
            }
            val space = sendAndWait(SiFliProtocol.fileSpace(blocks), 14)
            check(space.result == 0) { "Watch has insufficient transfer space" }
        }

        files.forEachIndexed { fileIndex, file ->
            val opened = sendAndWait(
                SiFliProtocol.fileStart(file.path, file.bytes.size),
                3,
            )
            check(opened.result == 0) { "Watch rejected ${file.path}" }
            var offset = 0
            var index = 1
            while (offset < file.bytes.size) {
                val end = minOf(file.bytes.size, offset + sliceSize)
                val chunk = file.bytes.copyOfRange(offset, end)
                val response = sendAndWait(SiFliProtocol.fileData(index, chunk), 5)
                when {
                    response.result == 0 -> {
                        offset = end
                        index++
                        sent += chunk.size
                        onProgress(
                            UploadProgress(
                                fileIndex + 1,
                                files.size,
                                file.path.substringAfterLast('/'),
                                sent,
                                total,
                            ),
                        )
                    }
                    response.result == 4 && response.expectedIndex > 0 -> {
                        index = response.expectedIndex
                    }
                    else -> error(
                        "Watch rejected ${file.path} slice $index (${response.result})",
                    )
                }
            }
            val closed = sendAndWait(SiFliProtocol.fileEnd(), 7)
            check(closed.result == 0) { "Watch could not finalize ${file.path}" }
        }
        val finished = sendAndWait(SiFliProtocol.entireEnd(), 9, 60_000)
        check(finished.result == 0) { "Watch could not activate package (${finished.result})" }
    }

    private suspend fun sendAndWait(
        payload: ByteArray,
        expectedCommand: Int,
        timeoutMillis: Long = 30_000,
    ): TransferResponse {
        while (responseChannel.tryReceive().isSuccess) Unit
        SiFliProtocol.frames(payload, negotiatedMtu).forEach { frame ->
            write(requireNotNull(sifliCharacteristic), frame)
            delay(10)
        }
        return withTimeout(timeoutMillis) {
            while (true) {
                val response = responseChannel.receive()
                if (response.command == 10) continue
                if (response.command == expectedCommand) return@withTimeout response
            }
            error("unreachable")
        }
    }

    private suspend fun write(
        characteristic: BluetoothGattCharacteristic,
        bytes: ByteArray,
    ) = writeMutex.withLock {
        val activeGatt = requireNotNull(gatt) { "Watch is not connected" }
        pendingWrite = CompletableDeferred()
        val started = if (Build.VERSION.SDK_INT >= 33) {
            activeGatt.writeCharacteristic(
                characteristic,
                bytes,
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE,
            ) == BluetoothStatusCodes.SUCCESS
        } else {
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            characteristic.value = bytes
            activeGatt.writeCharacteristic(characteristic)
        }
        check(started) { "Android rejected the Bluetooth write" }
        withTimeout(5_000) { requireNotNull(pendingWrite).await() }
    }

    private suspend fun enableNotifications(characteristic: BluetoothGattCharacteristic) {
        val activeGatt = requireNotNull(gatt)
        check(activeGatt.setCharacteristicNotification(characteristic, true)) {
            "Could not enable SiFli notifications"
        }
        val descriptor = characteristic.getDescriptor(CCCD_UUID)
            ?: error("SiFli notification descriptor is missing")
        pendingDescriptor = CompletableDeferred()
        val value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        val started = if (Build.VERSION.SDK_INT >= 33) {
            activeGatt.writeDescriptor(descriptor, value) == BluetoothStatusCodes.SUCCESS
        } else {
            descriptor.value = value
            activeGatt.writeDescriptor(descriptor)
        }
        check(started) { "Could not configure SiFli notifications" }
        withTimeout(10_000) { requireNotNull(pendingDescriptor).await() }
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                connectionReady?.completeExceptionally(
                    IllegalStateException("Bluetooth connection failed ($status)"),
                )
                return
            }
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                connectionReady?.completeExceptionally(
                    IllegalStateException("Watch disconnected"),
                )
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                connectionReady?.completeExceptionally(
                    IllegalStateException("Could not discover watch services ($status)"),
                )
                return
            }
            val characteristics = gatt.services.flatMap { it.characteristics }
            sifliCharacteristic = characteristics.firstOrNull { it.uuid == SIFLI_UUID }
            watchWriteCharacteristic = characteristics.firstOrNull {
                it.uuid == WATCH_WRITE_UUID
            }
            if (sifliCharacteristic == null || watchWriteCharacteristic == null) {
                connectionReady?.completeExceptionally(
                    IllegalStateException("This device does not expose the HK8 protocol"),
                )
                return
            }
            gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
            if (!gatt.requestMtu(SiFliProtocol.MTU_CAP)) {
                finishHandshake()
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            negotiatedMtu = if (status == BluetoothGatt.GATT_SUCCESS) {
                minOf(mtu, SiFliProtocol.MTU_CAP)
            } else {
                23
            }
            finishHandshake()
        }

        private fun finishHandshake() {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    enableNotifications(requireNotNull(sifliCharacteristic))
                    connectionReady?.complete(Unit)
                } catch (error: Exception) {
                    connectionReady?.completeExceptionally(error)
                }
            }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) pendingDescriptor?.complete(Unit)
            else pendingDescriptor?.completeExceptionally(
                IllegalStateException("Notification setup failed ($status)"),
            )
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) pendingWrite?.complete(Unit)
            else pendingWrite?.completeExceptionally(
                IllegalStateException("Bluetooth write failed ($status)"),
            )
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            if (characteristic.uuid == SIFLI_UUID) {
                SiFliProtocol.response(value)?.let(responseChannel::trySend)
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            if (Build.VERSION.SDK_INT < 33 && characteristic.uuid == SIFLI_UUID) {
                SiFliProtocol.response(characteristic.value)?.let(responseChannel::trySend)
            }
        }
    }
}

private object BluetoothDeviceTransport {
    const val LE = 2
}
