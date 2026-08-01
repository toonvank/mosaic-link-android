package dev.toon.mosaiclink

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.toon.mosaiclink.ble.BleConnectionState
import dev.toon.mosaiclink.ble.Hk8Device
import dev.toon.mosaiclink.ble.Hk8BleClient
import dev.toon.mosaiclink.ble.UploadProgress
import dev.toon.mosaiclink.clock2.BuiltWatchface
import dev.toon.mosaiclink.clock2.Clock2Document
import dev.toon.mosaiclink.clock2.Clock2Parser
import dev.toon.mosaiclink.clock2.Compatibility
import dev.toon.mosaiclink.clock2.WatchfaceBuilder
import dev.toon.mosaiclink.catalog.SavedFacesRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.ZonedDateTime
import java.io.File

data class MosaicUiState(
    val connection: BleConnectionState = BleConnectionState.Disconnected,
    val selectedFileName: String? = null,
    val document: Clock2Document? = null,
    val compatibility: Compatibility? = null,
    val builtFace: BuiltWatchface? = null,
    val phase: String? = null,
    val uploadProgress: UploadProgress? = null,
    val busy: Boolean = false,
    val error: String? = null,
    val nearbyDevices: List<Hk8Device> = emptyList(),
    val activity: List<String> = listOf("Ready — no watch contacted"),
    val currentScreen: String = "installer",
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    companion object {
        private const val LAST_CLOCK2 = "last.clock2"
        private const val PREFS = "mosaic_link"
        private const val LAST_FILE_NAME = "last_file_name"
        private const val LAST_DEVICE_ADDRESS = "last_device_address"
        private const val LAST_DEVICE_NAME = "last_device_name"
    }

    private val ble = Hk8BleClient(application)
    private val builder = WatchfaceBuilder(application)
    private val mutableState = MutableStateFlow(MosaicUiState())
    val state: StateFlow<MosaicUiState> = mutableState.asStateFlow()
    private var installJob: Job? = null

    init {
        val cached = File(application.filesDir, LAST_CLOCK2)
        if (cached.isFile) {
            val name = application.getSharedPreferences(PREFS, 0)
                .getString(LAST_FILE_NAME, null)
                ?: LAST_CLOCK2
            viewModelScope.launch {
                runBusy("Restoring last Clock2 file…") {
                    val bytes = withContext(Dispatchers.IO) { cached.readBytes() }
                    processClock2(name, bytes, persist = false)
                }
            }
        }
    }

    fun selectClock2(uri: Uri) {
        viewModelScope.launch {
            runBusy("Reading Clock2 file…") {
                val resolver = getApplication<Application>().contentResolver
                val fileName = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { cursor ->
                        if (cursor.moveToFirst()) cursor.getString(0) else null
                    } ?: "watchface.clock2"
                val bytes = withContext(Dispatchers.IO) {
                    resolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: error("Android could not open this file")
                }
                processClock2(fileName, bytes, persist = true)
            }
        }
    }

    private suspend fun processClock2(
        fileName: String,
        bytes: ByteArray,
        persist: Boolean,
    ) {
        updatePhase("Checking layer topology…")
        val document = withContext(Dispatchers.Default) {
            Clock2Parser.parse(bytes, fileName)
        }
        val compatibility = Clock2Parser.compatibility(document)
        mutableState.update {
            it.copy(
                selectedFileName = fileName,
                document = document,
                compatibility = compatibility,
                builtFace = null,
                uploadProgress = null,
            )
        }
        if (!compatibility.supported) {
            log("Rejected $fileName — unsupported topology")
            return
        }
        updatePhase("Rendering and validating assets…")
        val built = withContext(Dispatchers.Default) {
            builder.build(document, ZonedDateTime.now(), 73)
        }
        if (persist) {
            withContext(Dispatchers.IO) {
                File(getApplication<Application>().filesDir, LAST_CLOCK2)
                    .writeBytes(bytes)
                getApplication<Application>().getSharedPreferences(PREFS, 0)
                    .edit()
                    .putString(LAST_FILE_NAME, fileName)
                    .apply()
            }
        }
        mutableState.update { it.copy(builtFace = built) }
        log(
            if (built.warnings.isEmpty()) {
                "Built ${built.displayName}: ${built.fileCount} verified files"
            } else {
                "Built ${built.displayName}: ${built.warnings.size} conversion notes"
            },
        )
    }

    fun connect() {
        viewModelScope.launch {
            runBusy("Scanning nearby Bluetooth devices…") {
                val devices = ble.scanNearby()
                mutableState.update { it.copy(nearbyDevices = devices) }
                check(devices.isNotEmpty()) { "No Bluetooth devices found nearby" }
                log("Found ${devices.size} nearby devices — choose your watch")
            }
        }
    }

    fun connect(device: Hk8Device) {
        viewModelScope.launch {
            runBusy("Connecting to ${device.name}…") {
                connectToWatch(device)
            }
        }
    }

    fun disconnect() {
        ble.disconnect()
        mutableState.update {
            it.copy(
                connection = BleConnectionState.Disconnected,
                phase = null,
                uploadProgress = null,
            )
        }
        log("Disconnected")
    }

    fun syncTime() {
        viewModelScope.launch {
            runBusy("Synchronizing time…") {
                ensureConnected()
                val now = ZonedDateTime.now()
                ble.syncTime(now)
                log("Watch time updated to ${now.toLocalTime().withNano(0)}")
            }
        }
    }

    fun installConfirmed() {
        val face = mutableState.value.builtFace ?: return
        if (mutableState.value.connection !is BleConnectionState.Connected) {
            mutableState.update { it.copy(error = "Connect to the watch before installing") }
            log("Install blocked — connect to the watch first")
            return
        }
        installJob = viewModelScope.launch {
            runBusy("Preparing safe transfer…") {
                updatePhase("Installing ${face.displayName}…")
                log("Transfer started — keep the watch awake")
                ble.uploadWatchface(face.packageBytes) { progress ->
                    mutableState.update {
                        it.copy(uploadProgress = progress, phase = "Sending ${progress.fileName}")
                    }
                }
                mutableState.update { it.copy(uploadProgress = null) }
                runCatching { withContext(Dispatchers.IO) { rememberCurrentFace() } }
                    .onFailure { log("Installed, but history could not be updated") }
                log("Transfer accepted — press Home once to open the new face")
            }
            installJob = null
        }
    }

    fun cancelInstall() {
        if (installJob == null) return
        installJob?.cancel()
        installJob = null
        ble.disconnect()
        mutableState.update {
            it.copy(
                connection = BleConnectionState.Disconnected,
                uploadProgress = null,
                phase = null,
                busy = false,
                error = null,
            )
        }
        log("Transfer canceled before activation")
    }

    fun clearError() {
        mutableState.update { it.copy(error = null) }
    }

    fun setScreen(screen: String) {
        mutableState.update { it.copy(currentScreen = screen) }
    }

    fun importSharedClock2(uri: Uri) {
        viewModelScope.launch {
            runBusy("Importing shared Clock2 file…") {
                val resolver = getApplication<Application>().contentResolver
                val fileName = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { cursor ->
                        if (cursor.moveToFirst()) cursor.getString(0) else null
                    } ?: "shared_watchface.clock2"
                
                if (!fileName.endsWith(".clock2", ignoreCase = true)) {
                    error("Shared file is not a valid .clock2 file: $fileName")
                }
                
                val bytes = withContext(Dispatchers.IO) {
                    resolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: error("Could not read shared file")
                }
                
                updatePhase("Parsing watchface…")
                processClock2(fileName, bytes, persist = true)
                log("Imported \"$fileName\" from Telegram")
                mutableState.update { it.copy(currentScreen = "installer") }
            }
        }
    }

    private val catalogRepo = SavedFacesRepository(getApplication<Application>())

    fun saveToCatalog() {
        viewModelScope.launch {
            val saved = withContext(Dispatchers.IO) { rememberCurrentFace() } ?: return@launch
            log("Kept \"${saved.name}\" in installed faces")
        }
    }

    fun selectClock2FromCatalog(fileName: String, bytes: ByteArray) {
        viewModelScope.launch {
            runBusy("Loading from catalog…") {
                processClock2(fileName, bytes, persist = true)
            }
        }
    }

    private fun rememberCurrentFace(): dev.toon.mosaiclink.catalog.SavedFace? {
        val face = mutableState.value.builtFace ?: return null
        val name = mutableState.value.selectedFileName ?: face.displayName
        val source = File(getApplication<Application>().filesDir, LAST_CLOCK2)
        if (!source.isFile) return null
        val preview = android.graphics.BitmapFactory.decodeByteArray(
            face.previewPng,
            0,
            face.previewPng.size,
        ) ?: return null
        return catalogRepo.save(
            name = face.displayName,
            fileName = name,
            clock2Bytes = source.readBytes(),
            preview = preview,
            sourceSha256 = face.sourceSha256,
        )
    }

    private suspend fun ensureConnected() {
        if (mutableState.value.connection is BleConnectionState.Connected) return
        connectToWatch()
    }

    private suspend fun connectToWatch(selectedDevice: Hk8Device? = null) {
        val preferences = getApplication<Application>().getSharedPreferences(PREFS, 0)
        val preferredAddress = preferences.getString(LAST_DEVICE_ADDRESS, null)
        mutableState.update { it.copy(connection = BleConnectionState.Scanning) }
        val device = selectedDevice ?: ble.findWatch(preferredAddress)
        mutableState.update { it.copy(connection = BleConnectionState.Connecting(device)) }
        val connected = ble.connect(device)
        mutableState.update { it.copy(connection = connected, nearbyDevices = emptyList()) }
        preferences.edit()
            .putString(LAST_DEVICE_ADDRESS, device.address)
            .putString(LAST_DEVICE_NAME, device.name)
            .apply()
        val remembered = preferredAddress?.equals(device.address, ignoreCase = true) == true
        log(
            if (remembered) {
                "Reconnected to ${device.name} at MTU ${connected.mtu}"
            } else {
                "Connected to ${device.name} at MTU ${connected.mtu}"
            },
        )
    }

    private suspend fun runBusy(phase: String, block: suspend () -> Unit) {
        mutableState.update { it.copy(busy = true, phase = phase, error = null) }
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            val message = error.message ?: error::class.java.simpleName
            mutableState.update {
                it.copy(
                    error = message,
                    connection = if (
                        it.connection is BleConnectionState.Scanning ||
                        it.connection is BleConnectionState.Connecting
                    ) BleConnectionState.Failed(message) else it.connection,
                )
            }
            log("Stopped: $message")
        } finally {
            mutableState.update { it.copy(busy = false, phase = null) }
        }
    }

    private fun updatePhase(phase: String) {
        mutableState.update { it.copy(phase = phase) }
    }

    private fun log(message: String) {
        mutableState.update {
            it.copy(activity = (listOf(message) + it.activity).take(8))
        }
    }

    override fun onCleared() {
        installJob?.cancel()
        ble.disconnect()
        super.onCleared()
    }
}
