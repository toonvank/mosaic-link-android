package dev.toon.mosaiclink.catalog

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

data class CatalogUiState(
    val savedFaces: List<SavedFace> = emptyList(),
    val channelFaces: List<ScrapedFace> = emptyList(),
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val error: String? = null,
    val hasMore: Boolean = false,
    val scraped: Boolean = false,
)

class CatalogViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "CatalogVM"
        private const val PREFS = "mosaic_link_catalog"
        private const val KEY_CACHED_FACES = "cached_channel_faces"
        private const val KEY_LAST_BEFORE_BY_CHANNEL = "cached_before_by_channel"
    }

    private val repo = SavedFacesRepository(application)
    private val scraper = ChannelScraper()
    private val mutableState = MutableStateFlow(CatalogUiState())
    val state: StateFlow<CatalogUiState> = mutableState.asStateFlow()

    init {
        refreshSaved()
        loadCachedChannelFaces()
    }

    fun refreshSaved() {
        mutableState.update { it.copy(savedFaces = repo.getAll()) }
    }

    fun deleteFace(id: String) {
        repo.delete(id)
        refreshSaved()
    }

    fun deleteFaces(ids: Set<String>) {
        repo.delete(ids)
        refreshSaved()
    }

    fun clearSavedFaces() {
        repo.clear()
        refreshSaved()
    }

    fun saveCurrentFace(
        name: String,
        fileName: String,
        clock2Bytes: ByteArray,
        preview: android.graphics.Bitmap,
        sourceSha256: String,
    ) {
        repo.save(name, fileName, clock2Bytes, preview, sourceSha256)
        refreshSaved()
    }

    fun loadFaceBytes(face: SavedFace): ByteArray? = repo.loadClock2Bytes(face)

    fun scrapeChannels() {
        viewModelScope.launch {
            mutableState.update { it.copy(loading = true, error = null) }
            try {
                val results = ChannelScraper.CHANNELS.map { channel ->
                    async(Dispatchers.IO) {
                        runCatching {
                            val latest = scraper.scrapeChannel(channel)
                            val archive = if (latest.lowestMessageId > 30) {
                                scraper.scrapeRandomPage(channel, latest.lowestMessageId)
                            } else {
                                ScrapeResult(emptyList(), 0, false)
                            }
                            val faces = (latest.faces + archive.faces)
                                .distinctBy { it.messageUrl }
                            ChannelBatch(
                                channel = channel,
                                faces = faces,
                                cursor = latest.lowestMessageId.takeIf { it > 0 },
                                hasMore = latest.hasMore,
                            )
                        }.getOrElse { failure ->
                            Log.w(TAG, "Could not scrape ${channel.username}", failure)
                            ChannelBatch(channel, emptyList(), null, false, failure.message)
                        }
                    }
                }.awaitAll()
                val channelResults = results
                val xeosFaces = async(Dispatchers.IO) {
                    runCatching { scraper.scrapeXEOS() }
                        .onFailure { Log.w(TAG, "Could not load the XEOS catalog", it) }
                        .getOrDefault(emptyList())
                }.await()
                if (channelResults.all { it.error != null } && xeosFaces.isEmpty()) {
                    error("Could not reach the public watchface catalogs")
                }

                val deduped = interleave(channelResults.map { it.faces } + listOf(xeosFaces))
                    .distinctBy { it.messageUrl }
                val cursors = channelResults.mapNotNull { batch ->
                    batch.cursor?.let { batch.channel.username to it }
                }.toMap()

                mutableState.update {
                    it.copy(
                        channelFaces = deduped,
                        loading = false,
                        hasMore = channelResults.any { batch -> batch.hasMore },
                        scraped = true,
                    )
                }

                cacheChannelFaces(deduped, cursors)
                Log.d(TAG, "Loaded ${deduped.size} faces from Telegram and XEOS")
            } catch (e: Exception) {
                Log.e(TAG, "Scrape failed", e)
                mutableState.update {
                    it.copy(loading = false, error = e.message ?: "Failed to load catalog")
                }
            }
        }
    }

    fun loadMore() {
        val currentState = mutableState.value
        if (currentState.loadingMore || !currentState.hasMore) return

        viewModelScope.launch {
            mutableState.update { it.copy(loadingMore = true, error = null) }
            try {
                val existing = currentState.channelFaces
                val existingUrls = existing.map { it.messageUrl }.toSet()
                val batches = ChannelScraper.CHANNELS.map { channel ->
                    async(Dispatchers.IO) {
                        runCatching {
                            val cachedBefore = getChannelLastBefore(channel.username)
                                ?: existing.filter { it.channelName == channel.displayName }
                                    .minOfOrNull { it.messageId }
                            val result = scraper.scrapeChannel(channel, cachedBefore)
                            ChannelBatch(
                                channel = channel,
                                faces = result.faces.filter { it.messageUrl !in existingUrls },
                                cursor = result.lowestMessageId.takeIf { it > 0 },
                                hasMore = result.hasMore,
                            )
                        }.getOrElse { failure ->
                            Log.w(TAG, "Could not load more from ${channel.username}", failure)
                            ChannelBatch(channel, emptyList(), null, false, failure.message)
                        }
                    }
                }.awaitAll()
                if (batches.all { it.error != null }) error("Could not reach Telegram's public catalog")
                val newFaces = interleave(batches.map { it.faces })

                if (newFaces.isEmpty()) {
                    mutableState.update { it.copy(loadingMore = false, hasMore = false) }
                    return@launch
                }

                val combined = (existing + newFaces).distinctBy { it.messageUrl }

                mutableState.update {
                    it.copy(
                        channelFaces = combined,
                        loadingMore = false,
                        hasMore = batches.any { it.hasMore },
                    )
                }

                val updatedCursors = getChannelCursors().toMutableMap()
                batches.forEach { batch ->
                    batch.cursor?.let { updatedCursors[batch.channel.username] = it }
                }
                cacheChannelFaces(combined, updatedCursors)
                Log.d(TAG, "Loaded ${newFaces.size} more faces (total: ${combined.size})")
            } catch (e: Exception) {
                Log.e(TAG, "Load more failed", e)
                mutableState.update {
                    it.copy(loadingMore = false, error = e.message ?: "Failed to load more")
                }
            }
        }
    }

    private fun loadCachedChannelFaces() {
        val prefs = getApplication<Application>().getSharedPreferences(PREFS, 0)
        val raw = prefs.getString(KEY_CACHED_FACES, null) ?: return
        try {
            val arr = JSONArray(raw)
            val faces = mutableListOf<ScrapedFace>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                faces.add(ScrapedFace(
                    fileName = obj.getString("fileName"),
                    fileSizeText = obj.optString("fileSizeText", ""),
                    previewUrl = obj.optString("previewUrl").ifEmpty { null },
                    messageUrl = obj.getString("messageUrl"),
                    channelName = obj.getString("channelName"),
                    messageId = obj.getInt("messageId"),
                    description = obj.optString("description").ifEmpty { null },
                    downloadUrl = obj.optString("downloadUrl").ifEmpty { null },
                    format = runCatching {
                        CatalogFaceFormat.valueOf(obj.optString("format", CatalogFaceFormat.CLOCK2.name))
                    }.getOrDefault(CatalogFaceFormat.CLOCK2),
                ))
            }
            mutableState.update {
                it.copy(
                    channelFaces = faces,
                    hasMore = faces.isNotEmpty(),
                )
            }
            Log.d(TAG, "Loaded ${faces.size} cached channel faces")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load cached faces", e)
        }
    }

    private fun cacheChannelFaces(faces: List<ScrapedFace>, cursors: Map<String, Int>) {
        val arr = JSONArray()
        for (face in faces) {
            arr.put(JSONObject().apply {
                put("fileName", face.fileName)
                put("fileSizeText", face.fileSizeText)
                face.previewUrl?.let { put("previewUrl", it) }
                put("messageUrl", face.messageUrl)
                put("channelName", face.channelName)
                put("messageId", face.messageId)
                face.description?.let { put("description", it) }
                face.downloadUrl?.let { put("downloadUrl", it) }
                put("format", face.format.name)
            })
        }
        getApplication<Application>().getSharedPreferences(PREFS, 0)
            .edit()
            .putString(KEY_CACHED_FACES, arr.toString())
            .putString(KEY_LAST_BEFORE_BY_CHANNEL, JSONObject(cursors).toString())
            .apply()
    }

    private fun getChannelLastBefore(channelUsername: String): Int? {
        return getChannelCursors()[channelUsername]
    }

    private fun getChannelCursors(): Map<String, Int> {
        val prefs = getApplication<Application>().getSharedPreferences(PREFS, 0)
        val raw = prefs.getString(KEY_LAST_BEFORE_BY_CHANNEL, null) ?: return emptyMap()
        return runCatching {
            val objectValue = JSONObject(raw)
            buildMap {
                objectValue.keys().forEach { key -> put(key, objectValue.getInt(key)) }
            }
        }.getOrDefault(emptyMap())
    }

    private fun interleave(groups: List<List<ScrapedFace>>): List<ScrapedFace> = buildList {
        val largest = groups.maxOfOrNull { it.size } ?: 0
        for (index in 0 until largest) {
            groups.forEach { group -> group.getOrNull(index)?.let(::add) }
        }
    }

    private data class ChannelBatch(
        val channel: ChannelConfig,
        val faces: List<ScrapedFace>,
        val cursor: Int?,
        val hasMore: Boolean,
        val error: String? = null,
    )
}
