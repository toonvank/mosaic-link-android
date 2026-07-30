package dev.toon.mosaiclink.catalog

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class CatalogUiState(
    val savedFaces: List<SavedFace> = emptyList(),
    val channelFaces: List<ScrapedFace> = emptyList(),
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val error: String? = null,
    val searchQuery: String = "",
    val hasMore: Boolean = false,
    val scraped: Boolean = false,
)

class CatalogViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "CatalogVM"
        private const val PREFS = "mosaic_link_catalog"
        private const val KEY_CACHED_FACES = "cached_channel_faces"
        private const val KEY_LAST_BEFORE = "cached_last_before_id"
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

    fun setSearchQuery(query: String) {
        mutableState.update { it.copy(searchQuery = query) }
    }

    fun scrapeChannels() {
        viewModelScope.launch {
            mutableState.update { it.copy(loading = true, error = null) }
            try {
                val allFaces = mutableListOf<ScrapedFace>()
                var lowestId = Int.MAX_VALUE

                for (channel in ChannelScraper.CHANNELS) {
                    val latestResult = withContext(Dispatchers.IO) {
                        scraper.scrapeChannel(channel)
                    }
                    allFaces.addAll(latestResult.faces)
                    if (latestResult.lowestMessageId < lowestId && latestResult.lowestMessageId > 0) {
                        lowestId = latestResult.lowestMessageId
                    }

                    if (latestResult.lowestMessageId > 30) {
                        val randomResult = withContext(Dispatchers.IO) {
                            scraper.scrapeRandomPage(channel, latestResult.lowestMessageId)
                        }
                        allFaces.addAll(randomResult.faces)
                        if (randomResult.lowestMessageId < lowestId && randomResult.lowestMessageId > 0) {
                            lowestId = randomResult.lowestMessageId
                        }
                    }
                }

                val deduped = allFaces
                    .distinctBy { it.fileName }
                    .sortedByDescending { it.messageId }

                mutableState.update {
                    it.copy(
                        channelFaces = deduped,
                        loading = false,
                        hasMore = deduped.isNotEmpty(),
                        scraped = true,
                    )
                }

                cacheChannelFaces(deduped, lowestId)
                Log.d(TAG, "Scraped ${deduped.size} faces from ${ChannelScraper.CHANNELS.size} channels")
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
                val existingIds = existing.map { it.messageId }.toSet()
                val newFaces = mutableListOf<ScrapedFace>()
                var lowestId = Int.MAX_VALUE

                for (channel in ChannelScraper.CHANNELS) {
                    val cachedBefore = getChannelLastBefore(channel.username)
                        ?: existing.filter { it.channelName == channel.displayName }
                            .minOfOrNull { it.messageId }

                    val result = withContext(Dispatchers.IO) {
                        scraper.scrapeChannel(channel, cachedBefore)
                    }

                    val fresh = result.faces.filter { it.messageId !in existingIds }
                    newFaces.addAll(fresh)

                    if (result.lowestMessageId < lowestId && result.lowestMessageId > 0) {
                        lowestId = result.lowestMessageId
                    }
                }

                if (newFaces.isEmpty()) {
                    mutableState.update { it.copy(loadingMore = false, hasMore = false) }
                    return@launch
                }

                val combined = (existing + newFaces)
                    .distinctBy { it.fileName }
                    .sortedByDescending { it.messageId }

                mutableState.update {
                    it.copy(
                        channelFaces = combined,
                        loadingMore = false,
                        hasMore = newFaces.size >= 5,
                    )
                }

                if (lowestId != Int.MAX_VALUE) {
                    cacheChannelFaces(combined, lowestId)
                }
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
                ))
            }
            mutableState.update {
                it.copy(
                    channelFaces = faces.sortedByDescending { f -> f.messageId },
                    hasMore = faces.isNotEmpty(),
                )
            }
            Log.d(TAG, "Loaded ${faces.size} cached channel faces")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load cached faces", e)
        }
    }

    private fun cacheChannelFaces(faces: List<ScrapedFace>, lowestId: Int) {
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
            })
        }
        getApplication<Application>().getSharedPreferences(PREFS, 0)
            .edit()
            .putString(KEY_CACHED_FACES, arr.toString())
            .putInt(KEY_LAST_BEFORE, lowestId)
            .apply()
    }

    private fun getChannelLastBefore(channelUsername: String): Int? {
        val prefs = getApplication<Application>().getSharedPreferences(PREFS, 0)
        val v = prefs.getInt(KEY_LAST_BEFORE, -1)
        return if (v > 0) v else null
    }
}
