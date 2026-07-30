package dev.toon.mosaiclink.catalog

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SaveAlt
import androidx.compose.material.icons.rounded.Watch
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CatalogScreen(
    viewModel: CatalogViewModel,
    onLoadFace: (face: SavedFace) -> Unit,
    onLoadFolderFace: (face: FolderFace) -> Unit,
) {
    val state by viewModel.state.collectAsState()
    var selectedTab by remember { mutableStateOf(0) }
    var deleteTarget by remember { mutableStateOf<SavedFace?>(null) }
    val context = LocalContext.current

    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        uri?.let { viewModel.scanFolder(it) }
    }

    if (deleteTarget != null) {
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete saved face?") },
            text = { Text("\"${deleteTarget!!.name}\" will be permanently removed.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteFace(deleteTarget!!.id)
                    deleteTarget = null
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("Cancel") }
            },
        )
    }

    Column(Modifier.fillMaxSize().padding(WindowInsets.statusBars.asPaddingValues()).padding(bottom = 80.dp)) {
        PrimaryTabRow(selectedTabIndex = selectedTab) {
            Tab(
                selected = selectedTab == 0,
                onClick = { selectedTab = 0 },
                text = { Text("Saved Faces") },
            )
            Tab(
                selected = selectedTab == 1,
                onClick = { selectedTab = 1 },
                text = { Text("Folder") },
            )
        }

        when (selectedTab) {
            0 -> SavedFacesTab(
                faces = state.savedFaces,
                context = context,
                onClick = onLoadFace,
                onDelete = { deleteTarget = it },
            )
            1 -> FolderTab(
                state = state,
                context = context,
                onPickFolder = { folderPicker.launch(null) },
                onRefresh = {
                    val restored = viewModel.restoreFolderUri()
                    if (restored != null) viewModel.scanFolder(restored)
                },
                onClick = { face ->
                    onLoadFolderFace(face)
                },
            )
        }
    }
}

@Composable
private fun SavedFacesTab(
    faces: List<SavedFace>,
    context: Context,
    onClick: (SavedFace) -> Unit,
    onDelete: (SavedFace) -> Unit,
) {
    if (faces.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Rounded.SaveAlt,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Text("No saved faces yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Save a face from the installer to see it here",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        return
    }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 140.dp),
        modifier = Modifier.fillMaxSize().padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(faces, key = { it.id }) { face ->
            Card(
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.clickable { onClick(face) },
            ) {
                Column {
                    val bmp = remember(face.id) { face.loadPreview(context) }
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(140.dp)
                            .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                            .background(Color(0xFF1A1A2E)),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (bmp != null) {
                            Image(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = face.name,
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.fillMaxSize(),
                            )
                        } else {
                            Icon(
                                Icons.Rounded.Watch,
                                contentDescription = null,
                                modifier = Modifier.size(40.dp),
                                tint = Color.Gray,
                            )
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                face.name,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                face.fileName,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        IconButton(onClick = { onDelete(face) }) {
                            Icon(
                                Icons.Rounded.Delete,
                                contentDescription = "Delete",
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FolderTab(
    state: CatalogUiState,
    context: Context,
    onPickFolder: () -> Unit,
    onRefresh: () -> Unit,
    onClick: (FolderFace) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(onClick = onPickFolder) {
                Icon(Icons.Rounded.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Pick folder")
            }
            IconButton(onClick = onRefresh) {
                Icon(Icons.Rounded.Refresh, contentDescription = "Refresh")
            }
        }

        if (state.loading) {
            Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }

        state.folderError?.let { err ->
            Text(
                err,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }

        if (!state.loading && state.folderFaces.isEmpty() && state.folderError == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Rounded.FolderOpen,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text("Pick a folder with .clock2 files", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Telegram downloads, Dropbox, etc.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 140.dp),
            modifier = Modifier.fillMaxSize().padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(state.folderFaces, key = { it.fileName }) { face ->
                Card(
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.clickable { onClick(face) },
                ) {
                    Column {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(140.dp)
                                .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                                .background(Color(0xFF1A1A2E)),
                            contentAlignment = Alignment.Center,
                        ) {
                            face.previewUri?.let { uri ->
                                val bmp = remember(face.fileName) {
                                    try {
                                        context.contentResolver.openInputStream(uri)?.use {
                                            BitmapFactory.decodeStream(it)
                                        }
                                    } catch (_: Exception) { null }
                                }
                                if (bmp != null) {
                                    Image(
                                        bitmap = bmp.asImageBitmap(),
                                        contentDescription = face.fileName,
                                        contentScale = ContentScale.Fit,
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                } else {
                                    FacePlaceholder()
                                }
                            } ?: FacePlaceholder()
                        }
                        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                            Text(
                                face.fileName,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                formatSize(face.size),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FacePlaceholder() {
    Icon(
        Icons.Rounded.Watch,
        contentDescription = null,
        modifier = Modifier.size(40.dp),
        tint = Color.Gray,
    )
}

private fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1048576 -> "${bytes / 1024} KB"
    else -> "${"%.1f".format(bytes / 1048576.0)} MB"
}