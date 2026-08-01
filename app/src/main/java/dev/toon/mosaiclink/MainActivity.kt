package dev.toon.mosaiclink

import android.Manifest
import android.content.pm.PackageManager
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.material.icons.rounded.Watch
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.toon.mosaiclink.ble.BleConnectionState
import dev.toon.mosaiclink.ble.Hk8Device
import dev.toon.mosaiclink.ui.MosaicTheme

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MosaicTheme {
                val permissions = bluetoothPermissions()
                var permissionsGranted by remember {
                    mutableStateOf(permissions.all {
                        checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
                    })
                }
                val permissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions(),
                ) { grants ->
                    permissionsGranted = grants.values.all { it }
                }
                LaunchedEffect(Unit) {
                    if (!permissionsGranted) permissionLauncher.launch(permissions)
                }
                LaunchedEffect(permissionsGranted) {
                    if (permissionsGranted) viewModel.autoConnect()
                }
                val state by viewModel.state.collectAsState()
                MosaicLinkScreen(
                    state = state,
                    permissionsGranted = permissionsGranted,
                    onRequestPermissions = { permissionLauncher.launch(permissions) },
                    onFile = viewModel::selectClock2,
                    onScaleMode = viewModel::setScaleMode,
                    onConnect = { viewModel.connect() },
                    nearbyDevices = state.nearbyDevices,
                    onConnectDevice = { device -> viewModel.connect(device) },
                    onDisconnect = viewModel::disconnect,
                    onSyncTime = viewModel::syncTime,
                    onInstall = viewModel::installConfirmed,
                    onCancelInstall = viewModel::cancelInstall,
                    onClearError = viewModel::clearError,
                )
            }
        }
        importClock2Intent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        importClock2Intent(intent)
    }

    private fun importClock2Intent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_VIEW) {
            intent.data?.let(viewModel::selectClock2)
        }
    }

    private fun bluetoothPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
            )
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MosaicLinkScreen(
    state: MosaicUiState,
    permissionsGranted: Boolean,
    onRequestPermissions: () -> Unit,
    onFile: (android.net.Uri) -> Unit,
    onScaleMode: (dev.toon.mosaiclink.clock2.ScaleMode) -> Unit,
    onConnect: () -> Unit,
    nearbyDevices: List<Hk8Device>,
    onConnectDevice: (Hk8Device) -> Unit,
    onDisconnect: () -> Unit,
    onSyncTime: () -> Unit,
    onInstall: () -> Unit,
    onCancelInstall: () -> Unit,
    onClearError: () -> Unit,
) {
    val snackbar = remember { SnackbarHostState() }
    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(onFile) }
    var installDialog by remember { mutableStateOf(false) }
    LaunchedEffect(state.error) {
        state.error?.let {
            snackbar.showSnackbar(it)
            onClearError()
        }
    }

    if (installDialog) {
        AlertDialog(
            onDismissRequest = { installDialog = false },
            icon = { Icon(Icons.Rounded.Watch, contentDescription = null) },
            title = { Text("Protect the active face") },
            text = {
                Text(
                    "On the watch, select a different official stock face and leave " +
                        "the screen awake. Also close Wearfit so it does not compete " +
                        "for the Bluetooth connection.\n\nThis local experiment patches " +
                        "the official face-23 display geometry from 410×494 to the " +
                        "XDA-measured 434×494 visible area. Keep the known-good " +
                        "stock face available for recovery.",
                )
            },
            confirmButton = {
                Button(onClick = {
                    installDialog = false
                    onInstall()
                }) { Text("Safe face is active") }
            },
            dismissButton = {
                TextButton(onClick = { installDialog = false }) { Text("Cancel") }
            },
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Mosaic Link", fontWeight = FontWeight.SemiBold)
                        Text(
                            "HK8 PRO MAX companion",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            DeviceHero(
                connection = state.connection,
                busy = state.busy,
                autoConnecting = state.autoConnecting,
                permissionsGranted = permissionsGranted,
                onPermissions = onRequestPermissions,
                onConnect = onConnect,
                nearbyDevices = state.nearbyDevices,
                onConnectDevice = onConnectDevice,
                onDisconnect = onDisconnect,
            )
            QuickTimeCard(
                connected = state.connection is BleConnectionState.Connected,
                busy = state.busy,
                onSync = if (permissionsGranted) onSyncTime else onRequestPermissions,
            )
            WatchfaceCard(
                state = state,
                onChoose = { filePicker.launch(arrayOf("*/*")) },
                onInstall = { installDialog = true },
                onScaleMode = onScaleMode,
            )
            AnimatedVisibility(state.busy) {
                WorkCard(state, onCancelInstall)
            }
            ActivityCard(state.activity)
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun DeviceHero(
    connection: BleConnectionState,
    busy: Boolean,
    autoConnecting: Boolean,
    permissionsGranted: Boolean,
    onPermissions: () -> Unit,
    onConnect: () -> Unit,
    nearbyDevices: List<Hk8Device>,
    onConnectDevice: (Hk8Device) -> Unit,
    onDisconnect: () -> Unit,
) {
    val connected = connection is BleConnectionState.Connected
    ElevatedCard(
        shape = RoundedCornerShape(30.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = Color.Transparent),
    ) {
        Box(
            Modifier
                .background(
                    Brush.linearGradient(
                        listOf(Color(0xFF244A36), Color(0xFF1A2130), Color(0xFF171A22)),
                    ),
                )
                .padding(22.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        modifier = Modifier.size(54.dp),
                        shape = RoundedCornerShape(18.dp),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Rounded.Watch,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (connected) {
                                (connection as BleConnectionState.Connected).device.name
                            } else {
                                "Your watch"
                            },
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            connectionLabel(connection),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Box(
                        Modifier
                            .size(12.dp)
                            .clip(CircleShape)
                            .background(
                                if (connected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.outline,
                            ),
                    )
                }
                Button(
                    onClick = when {
                        !permissionsGranted -> onPermissions
                        connected -> onDisconnect
                        else -> onConnect
                    },
                    enabled = !busy || autoConnecting,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (connected) {
                            MaterialTheme.colorScheme.surfaceVariant
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                    ),
                ) {
                    Icon(Icons.Rounded.Bluetooth, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        when {
                            !permissionsGranted -> "Allow nearby devices"
                            connected -> "Disconnect"
                            autoConnecting -> "Having trouble? Tap to scan"
                            else -> "Scan nearby devices"
                        },
                    )
                }
                if (!connected && nearbyDevices.isNotEmpty()) {
                    Text(
                        "Choose your watch. The app will verify the HK8 protocol before connecting.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    nearbyDevices.take(8).forEach { device ->
                        OutlinedButton(
                            onClick = { onConnectDevice(device) },
                            enabled = !busy || autoConnecting,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(device.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    "${device.address} • ${device.rssi ?: "unknown"} dBm",
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun QuickTimeCard(connected: Boolean, busy: Boolean, onSync: () -> Unit) {
    Card(shape = RoundedCornerShape(24.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Rounded.Schedule,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(30.dp),
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("Phone time", fontWeight = FontWeight.SemiBold)
                Text(
                    if (connected) "Ready to synchronize" else "Connects automatically",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedButton(onClick = onSync, enabled = !busy) {
                Text("Sync now")
            }
        }
    }
}

@Composable
private fun WatchfaceCard(
    state: MosaicUiState,
    onChoose: () -> Unit,
    onInstall: () -> Unit,
    onScaleMode: (dev.toon.mosaiclink.clock2.ScaleMode) -> Unit,
) {
    Card(shape = RoundedCornerShape(28.dp)) {
        Column(
            Modifier.fillMaxWidth().padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Rounded.UploadFile,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    "Clock2 installer",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }

            state.builtFace?.let { face ->
                val bitmap = remember(face.previewPng) {
                    BitmapFactory.decodeByteArray(
                        face.previewPng, 0, face.previewPng.size,
                    ).asImageBitmap()
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Image(
                        bitmap = bitmap,
                        contentDescription = "Watchface preview",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .width(126.dp)
                            .aspectRatio(bitmap.width.toFloat() / bitmap.height)
                            .clip(RoundedCornerShape(24.dp))
                            .background(Color.Black),
                    )
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            face.displayName,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(6.dp))
                        StatusPill(
                            if (face.warnings.isEmpty()) {
                                "Compatible"
                            } else {
                                "Compatible • ${face.warnings.size} notes"
                            },
                            true,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "${face.fileCount} files • measured 434×494 profile",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            "Source: ${state.document?.canvasWidth?.toInt()}×" +
                                "${state.document?.canvasHeight?.toInt()} • " +
                                "fit: ${face.scaleMode.label.lowercase()}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        face.warnings.firstOrNull()?.let { warning ->
                            Text(
                                warning,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.tertiary,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            } ?: run {
                Surface(
                    shape = RoundedCornerShape(22.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                ) {
                    Column(
                        Modifier.fillMaxWidth().padding(22.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(
                            Icons.Rounded.CloudUpload,
                            contentDescription = null,
                            modifier = Modifier.size(38.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(10.dp))
                        Text("Choose a .clock2 file")
                        state.compatibility?.takeIf { !it.supported }?.let {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                it.reasons.firstOrNull().orEmpty(),
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = onChoose,
                    enabled = !state.busy,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(if (state.selectedFileName == null) "Choose file" else "Replace")
                }
                if (state.builtFace != null) {
                    var expanded by remember { mutableStateOf(false) }
                    Box(modifier = Modifier.weight(1f)) {
                        OutlinedButton(
                            onClick = { expanded = true },
                            enabled = !state.busy,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                state.builtFace.scaleMode.label,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                            listOf(
                                dev.toon.mosaiclink.clock2.ScaleMode.CONTAIN,
                                dev.toon.mosaiclink.clock2.ScaleMode.COVER,
                            ).forEach { mode ->
                                DropdownMenuItem(
                                    text = { Text(mode.label) },
                                    onClick = {
                                        expanded = false
                                        onScaleMode(mode)
                                    },
                                )
                            }
                        }
                    }
                }
                Button(
                    onClick = onInstall,
                    enabled = state.builtFace != null &&
                        state.connection is BleConnectionState.Connected &&
                        !state.busy,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        if (
                            state.builtFace != null &&
                            state.connection !is BleConnectionState.Connected
                        ) "Connect first" else "Install",
                    )
                    Spacer(Modifier.width(6.dp))
                    Icon(
                        Icons.AutoMirrored.Rounded.ArrowForward,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun WorkCard(state: MosaicUiState, onCancelInstall: () -> Unit) {
    Card(
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
        ),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(12.dp))
                Text(state.phase ?: "Working…", fontWeight = FontWeight.Medium)
            }
            state.uploadProgress?.let {
                LinearProgressIndicator(
                    progress = { it.fraction },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "File ${it.fileIndex}/${it.fileCount} • ${(it.fraction * 100).toInt()}%",
                    style = MaterialTheme.typography.labelMedium,
                )
                OutlinedButton(
                    onClick = onCancelInstall,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Cancel transfer")
                }
            }
        }
    }
}

@Composable
private fun ActivityCard(entries: List<String>) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            "ACTIVITY",
            fontSize = 12.sp,
            letterSpacing = 1.4.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp),
        )
        Card(shape = RoundedCornerShape(24.dp)) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp)) {
                entries.take(4).forEachIndexed { index, entry ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 14.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Icon(
                            Icons.Rounded.CheckCircle,
                            contentDescription = null,
                            tint = if (index == 0) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.outline
                            },
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(entry, style = MaterialTheme.typography.bodyMedium)
                    }
                    if (index < minOf(entries.size, 4) - 1) HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun StatusPill(text: String, positive: Boolean) {
    Surface(
        shape = RoundedCornerShape(50),
        color = if (positive) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
        } else {
            MaterialTheme.colorScheme.error.copy(alpha = 0.14f)
        },
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = if (positive) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
        )
    }
}

private fun connectionLabel(state: BleConnectionState): String = when (state) {
    BleConnectionState.Disconnected -> "Not connected"
    BleConnectionState.Scanning -> "Scanning nearby devices…"
    is BleConnectionState.Connecting -> "Connecting to ${state.device.name}…"
    is BleConnectionState.Connected -> "${state.device.address} • MTU ${state.mtu}"
    is BleConnectionState.Failed -> state.message
}
