package dev.toon.mosaiclink.catalog

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material.icons.rounded.Watch
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest

private enum class CatalogPage { Browse, Installed }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CatalogScreen(
    viewModel: CatalogViewModel,
    onLoadSavedFace: (SavedFace) -> Unit,
    onInstallXEOSResource: (ScrapedFace) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsState()
    var pageIndex by rememberSaveable { mutableIntStateOf(0) }
    var browseQuery by rememberSaveable { mutableStateOf("") }
    var installedQuery by rememberSaveable { mutableStateOf("") }
    var source by rememberSaveable { mutableStateOf("All") }
    var detailFace by remember { mutableStateOf<ScrapedFace?>(null) }
    var selectedIds by remember { mutableStateOf(emptySet<String>()) }
    var deleteConfirmation by remember { mutableStateOf<Set<String>?>(null) }
    val page = CatalogPage.entries[pageIndex]

    LaunchedEffect(state.channelFaces.isEmpty(), state.scraped) {
        if (state.channelFaces.isEmpty() && !state.scraped && !state.loading) {
            viewModel.scrapeChannels()
        }
    }

    LaunchedEffect(page) {
        if (page == CatalogPage.Installed) viewModel.refreshSaved()
        selectedIds = emptySet()
    }

    detailFace?.let { face ->
        ModalBottomSheet(
            onDismissRequest = { detailFace = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            FaceDetailSheet(
                face = face,
                onInstallXEOSResource = {
                    detailFace = null
                    onInstallXEOSResource(face)
                },
            )
        }
    }

    deleteConfirmation?.let { ids ->
        AlertDialog(
            onDismissRequest = { deleteConfirmation = null },
            title = {
                Text(if (ids.size == state.savedFaces.size) "Clear installed history?" else "Remove ${ids.size} faces?")
            },
            text = { Text("The stored watchface files and previews will be removed from Mosaic Link.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteFaces(ids)
                        selectedIds = emptySet()
                        deleteConfirmation = null
                    },
                ) { Text("Remove") }
            },
            dismissButton = {
                TextButton(onClick = { deleteConfirmation = null }) { Text("Cancel") }
            },
        )
    }

    Column(modifier.fillMaxSize()) {
        SingleChoiceSegmentedButtonRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            SegmentedButton(
                selected = page == CatalogPage.Browse,
                onClick = { pageIndex = CatalogPage.Browse.ordinal },
                shape = SegmentedButtonDefaults.itemShape(0, 2),
                icon = { Icon(Icons.Rounded.Explore, contentDescription = null, modifier = Modifier.size(18.dp)) },
                label = { Text("Browse") },
            )
            SegmentedButton(
                selected = page == CatalogPage.Installed,
                onClick = { pageIndex = CatalogPage.Installed.ordinal },
                shape = SegmentedButtonDefaults.itemShape(1, 2),
                icon = { Icon(Icons.Rounded.History, contentDescription = null, modifier = Modifier.size(18.dp)) },
                label = { Text("Installed (${state.savedFaces.size})") },
            )
        }

        when (page) {
            CatalogPage.Browse -> BrowseCatalog(
                state = state,
                query = browseQuery,
                onQueryChange = { browseQuery = it },
                selectedSource = source,
                onSourceChange = { source = it },
                onRefresh = viewModel::scrapeChannels,
                onLoadMore = viewModel::loadMore,
                onFaceClick = { detailFace = it },
            )
            CatalogPage.Installed -> InstalledHistory(
                faces = state.savedFaces,
                query = installedQuery,
                onQueryChange = { installedQuery = it },
                selectedIds = selectedIds,
                onSelectionChange = { selectedIds = it },
                onOpen = onLoadSavedFace,
                onDelete = { deleteConfirmation = it },
            )
        }
    }
}

@Composable
private fun BrowseCatalog(
    state: CatalogUiState,
    query: String,
    onQueryChange: (String) -> Unit,
    selectedSource: String,
    onSourceChange: (String) -> Unit,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    onFaceClick: (ScrapedFace) -> Unit,
) {
    val sources = remember(state.channelFaces) {
        listOf("All") + state.channelFaces.map { it.channelName }.distinct()
    }
    val visibleFaces = remember(state.channelFaces, query, selectedSource) {
        state.channelFaces.filter { face ->
            (selectedSource == "All" || face.channelName == selectedSource) &&
                (query.isBlank() || face.fileName.contains(query, ignoreCase = true) ||
                    face.description?.contains(query, ignoreCase = true) == true)
        }
    }
    val gridState = rememberLazyGridState()

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text("Search faces") },
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                trailingIcon = if (query.isNotEmpty()) {
                    { IconButton(onClick = { onQueryChange("") }) { Icon(Icons.Rounded.Close, "Clear search") } }
                } else null,
                singleLine = true,
                shape = RoundedCornerShape(24.dp),
            )
            IconButton(onClick = onRefresh, enabled = !state.loading) {
                if (state.loading) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Rounded.Refresh, contentDescription = "Get a new mix")
                }
            }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            sources.forEach { item ->
                FilterChip(
                    selected = selectedSource == item,
                    onClick = { onSourceChange(item) },
                    label = { Text(item) },
                )
            }
        }

        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.fillMaxSize(),
            state = gridState,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (state.error != null) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    InlineMessage(
                        icon = Icons.Rounded.CloudOff,
                        title = "Catalog could not refresh",
                        body = state.error,
                        action = "Retry",
                        onAction = onRefresh,
                    )
                }
            }

            if (visibleFaces.isEmpty() && !state.loading && state.error == null) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    InlineMessage(
                        icon = Icons.Rounded.Explore,
                        title = if (query.isBlank()) "No faces found" else "No matching faces",
                        body = if (query.isBlank()) "Refresh to load a visual mix from the public channels."
                            else "Try another name or channel.",
                    )
                }
            }

            items(visibleFaces, key = { it.messageUrl }) { face ->
                ChannelFaceCard(face = face, onClick = { onFaceClick(face) })
            }

            if (state.loadingMore) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Row(
                        Modifier.fillMaxWidth().padding(20.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text("Loading older picks…")
                    }
                }
            } else if (state.hasMore && visibleFaces.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    OutlinedButton(onClick = onLoadMore, modifier = Modifier.fillMaxWidth()) {
                        Text("Browse further back")
                    }
                }
            }
        }
    }
}

@Composable
private fun InstalledHistory(
    faces: List<SavedFace>,
    query: String,
    onQueryChange: (String) -> Unit,
    selectedIds: Set<String>,
    onSelectionChange: (Set<String>) -> Unit,
    onOpen: (SavedFace) -> Unit,
    onDelete: (Set<String>) -> Unit,
) {
    val context = LocalContext.current
    val visibleFaces = remember(faces, query) {
        faces.filter {
            query.isBlank() || it.name.contains(query, ignoreCase = true) ||
                it.fileName.contains(query, ignoreCase = true)
        }
    }
    val selecting = selectedIds.isNotEmpty()
    BackHandler(enabled = selecting) { onSelectionChange(emptySet()) }

    Column(Modifier.fillMaxSize()) {
        if (selecting) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { onSelectionChange(emptySet()) }) {
                    Icon(Icons.Rounded.Close, contentDescription = "Cancel selection")
                }
                Text("${selectedIds.size} selected", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                IconButton(onClick = { onSelectionChange(visibleFaces.map { it.id }.toSet()) }) {
                    Icon(Icons.Rounded.SelectAll, contentDescription = "Select all")
                }
                IconButton(onClick = { onDelete(selectedIds) }) {
                    Icon(Icons.Rounded.DeleteSweep, contentDescription = "Remove selected")
                }
            }
        } else {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 8.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Search installed faces") },
                    leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                    trailingIcon = if (query.isNotEmpty()) {
                        { IconButton(onClick = { onQueryChange("") }) { Icon(Icons.Rounded.Close, "Clear search") } }
                    } else null,
                    singleLine = true,
                    shape = RoundedCornerShape(24.dp),
                )
                IconButton(
                    onClick = { onDelete(faces.map { it.id }.toSet()) },
                    enabled = faces.isNotEmpty(),
                ) {
                    Icon(Icons.Rounded.DeleteSweep, contentDescription = "Clear installed history")
                }
            }
        }

        if (faces.isNotEmpty()) {
            Text(
                "Faces are remembered after a successful install. Reinstalling the same file updates one entry.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
            )
        }

        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (visibleFaces.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    InlineMessage(
                        icon = Icons.Rounded.History,
                        title = if (faces.isEmpty()) "No installed faces yet" else "No matching faces",
                        body = if (faces.isEmpty()) "Once a transfer succeeds, that face will stay one tap away here."
                            else "Try another filename.",
                    )
                }
            }
            items(visibleFaces, key = { it.id }) { face ->
                InstalledFaceCard(
                    face = face,
                    context = context,
                    selected = face.id in selectedIds,
                    selectionMode = selecting,
                    onClick = {
                        if (selecting) {
                            onSelectionChange(selectedIds.toggle(face.id))
                        } else {
                            onOpen(face)
                        }
                    },
                    onLongClick = { onSelectionChange(selectedIds.toggle(face.id)) },
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChannelFaceCard(face: ScrapedFace, onClick: () -> Unit) {
    Card(
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth().combinedClickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column {
            FaceImage(url = face.previewUrl, contentDescription = face.fileName)
            Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                Text(
                    face.fileName.removeSuffix(".clock2"),
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    face.channelName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun InstalledFaceCard(
    face: SavedFace,
    context: Context,
    selected: Boolean,
    selectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Card(
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column {
            Box {
                val bitmap = remember(face.id, face.previewPath) { face.loadPreview(context) }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .background(Color(0xFF11131A)),
                    contentAlignment = Alignment.Center,
                ) {
                    if (bitmap != null) {
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = face.name,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        Icon(Icons.Rounded.Watch, contentDescription = null, modifier = Modifier.size(44.dp))
                    }
                }
                if (selectionMode) {
                    Box(
                        Modifier
                            .align(Alignment.TopEnd)
                            .padding(8.dp)
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(if (selected) MaterialTheme.colorScheme.primary else Color.Black.copy(alpha = 0.55f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (selected) Icon(Icons.Rounded.Check, null, Modifier.size(18.dp), Color.White)
                    }
                }
            }
            Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                Text(
                    face.name.removeSuffix(".clock2"),
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    DateUtils.getRelativeTimeSpanString(face.savedAt).toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun FaceImage(url: String?, contentDescription: String) {
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .background(Color(0xFF11131A)),
        contentAlignment = Alignment.Center,
    ) {
        SubcomposeAsyncImage(
            model = ImageRequest.Builder(LocalContext.current).data(url).crossfade(true).build(),
            contentDescription = contentDescription,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
            loading = { CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp) },
            error = { Icon(Icons.Rounded.Watch, null, Modifier.size(44.dp), Color.Gray) },
        )
    }
}

@Composable
private fun InlineMessage(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    body: String,
    action: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null, Modifier.size(42.dp), MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        Text(title, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (action != null && onAction != null) {
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = onAction) { Text(action) }
        }
    }
}

@Composable
private fun FaceDetailSheet(
    face: ScrapedFace,
    onInstallXEOSResource: () -> Unit,
) {
    val context = LocalContext.current
    Column(
        Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        SubcomposeAsyncImage(
            model = ImageRequest.Builder(context).data(face.previewUrl).crossfade(true).build(),
            contentDescription = face.fileName,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .size(220.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(Color(0xFF11131A)),
            loading = { CircularProgressIndicator(strokeWidth = 2.dp) },
            error = { Icon(Icons.Rounded.Watch, null, Modifier.size(48.dp), Color.Gray) },
        )
        Spacer(Modifier.height(18.dp))
        Text(
            face.fileName.removeSuffix(".clock2"),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            listOf(face.fileSizeText, face.channelName).filter { it.isNotBlank() }.joinToString("  •  "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        face.description?.takeIf { it.isNotBlank() }?.let {
            Spacer(Modifier.height(10.dp))
            Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(20.dp))
        if (face.format == CatalogFaceFormat.XEOS_RESOURCE && face.downloadUrl != null) {
            Button(onClick = onInstallXEOSResource, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Rounded.Watch, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Download and prepare install")
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "Native XEOS resource: its catalog preview stays in Mosaic Link. Installation uses the experimental SiFli resource route for this HK8 profile.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Button(onClick = { openTelegramPost(context, face.messageUrl) }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Open exact post in Telegram")
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "Telegram only exposes the preview publicly. Download the file there, then open or share it with Mosaic Link.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun openTelegramPost(context: Context, messageUrl: String) {
    val webUri = Uri.parse(messageUrl)
    val domain = webUri.pathSegments.getOrNull(0).orEmpty()
    val post = webUri.pathSegments.getOrNull(1).orEmpty()
    val deepLink = Uri.parse("tg://resolve?domain=$domain&post=$post")
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, deepLink).setPackage("org.telegram.messenger"))
    } catch (_: ActivityNotFoundException) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, deepLink))
        } catch (_: ActivityNotFoundException) {
            context.startActivity(Intent(Intent.ACTION_VIEW, webUri))
        }
    }
}

private fun Set<String>.toggle(id: String): Set<String> =
    if (id in this) this - id else this + id
