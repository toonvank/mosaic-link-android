package dev.toon.mosaiclink.catalog

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class CatalogUiState(
    val savedFaces: List<SavedFace> = emptyList(),
    val folderFaces: List<FolderFace> = emptyList(),
    val folderUri: Uri? = null,
    val folderError: String? = null,
    val loading: Boolean = false,
    val error: String? = null,
)

class CatalogViewModel(application: Application) : AndroidViewModel(application) {
    companion object {
        private const val TAG = "CatalogVM"
        private const val PREFS = "mosaic_link_catalog"
        private const val KEY_FOLDER_URI = "folder_uri"
    }

    private val repo = SavedFacesRepository(application)
    private val mutableState = MutableStateFlow(CatalogUiState())
    val state: StateFlow<CatalogUiState> = mutableState.asStateFlow()

    init {
        refreshSaved()
        restoreFolderUri()?.let { scanFolder(it) }
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

    fun scanFolder(treeUri: Uri) {
        viewModelScope.launch {
            mutableState.update { it.copy(loading = true, folderError = null) }
            try {
                val faces = withContext(Dispatchers.IO) {
                    val app = getApplication<Application>()
                    val resolver = app.contentResolver

                    resolver.takePersistableUriPermission(
                        treeUri,
                        android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                    app.getSharedPreferences(PREFS, 0).edit()
                        .putString(KEY_FOLDER_URI, treeUri.toString()).apply()

                    val docTree = DocumentFile.fromTreeUri(app, treeUri)
                        ?: error("Cannot access folder")

                    val faces = mutableListOf<FolderFace>()
                    for (doc in docTree.listFiles()) {
                        val name = doc.name ?: continue
                        if (name.endsWith(".clock2", ignoreCase = true)) {
                            val baseName = name.substringBeforeLast(".")
                            val previewDoc = docTree.findFile("$baseName.png")
                                ?: docTree.findFile("$baseName.jpg")
                                ?: docTree.findFile("$baseName.webp")
                            faces.add(FolderFace(
                                fileName = name,
                                documentUri = doc.uri,
                                size = doc.length(),
                                lastModified = doc.lastModified(),
                                previewUri = previewDoc?.uri,
                            ))
                        }
                    }
                    faces.sortedByDescending { it.lastModified }
                }
                mutableState.update {
                    it.copy(folderFaces = faces, loading = false, folderUri = treeUri)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Scan failed", e)
                mutableState.update {
                    it.copy(folderError = e.message, loading = false)
                }
            }
        }
    }

    fun restoreFolderUri(): Uri? {
        val raw = getApplication<Application>().getSharedPreferences(PREFS, 0)
            .getString(KEY_FOLDER_URI, null) ?: return null
        return try { Uri.parse(raw) } catch (_: Exception) { null }
    }

    fun getDocumentUri(face: FolderFace): Uri = face.documentUri
}