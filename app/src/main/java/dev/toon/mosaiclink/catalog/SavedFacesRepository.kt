package dev.toon.mosaiclink.catalog

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

class SavedFacesRepository(private val context: Context) {
    companion object {
        private const val TAG = "SavedFaces"
        private const val PREFS = "mosaic_link_catalog"
        private const val KEY_FACES = "saved_faces_list"
        private const val KEY_FACES_V2 = "saved_faces_v2"
    }

    private val facesDir = File(context.filesDir, "saved_faces").also { it.mkdirs() }

    @Synchronized
    fun getAll(): List<SavedFace> {
        val prefs = context.getSharedPreferences(PREFS, 0)
        val encoded = prefs.getString(KEY_FACES_V2, null)
        if (encoded != null) {
            return runCatching { decodeV2(encoded) }
                .onFailure { Log.w(TAG, "Could not read installed-face history", it) }
                .getOrDefault(emptyList())
                .sortedByDescending { it.savedAt }
        }

        val legacy = prefs.getString(KEY_FACES, null) ?: return emptyList()
        val migrated = legacy.split("|||").mapNotNull { entry ->
            val parts = entry.split("```")
            if (parts.size == 6) {
                val dataPath = parts[5]
                SavedFace(
                    id = parts[0],
                    name = parts[1],
                    fileName = parts[2],
                    savedAt = parts[3].toLongOrNull() ?: 0L,
                    previewPath = parts[4],
                    dataPath = dataPath,
                    sourceSha256 = sha256(File(facesDir, dataPath).takeIf { it.isFile }?.readBytes()),
                )
            } else null
        }
        val deduped = deduplicate(migrated)
        persist(deduped)
        prefs.edit().remove(KEY_FACES).apply()
        return deduped.sortedByDescending { it.savedAt }
    }

    @Synchronized
    fun save(
        name: String,
        fileName: String,
        clock2Bytes: ByteArray,
        preview: Bitmap,
        sourceSha256: String,
    ): SavedFace {
        val identityHash = sourceSha256.ifBlank { sha256(clock2Bytes) }
        val current = getAll().toMutableList()
        val existing = current.firstOrNull {
            identityHash.isNotBlank() && it.sourceSha256.equals(identityHash, ignoreCase = true)
        }
        val id = existing?.id ?: UUID.randomUUID().toString()
        val dataFile = File(facesDir, "$id.clock2")
        val previewFile = File(facesDir, "$id.png")

        FileOutputStream(dataFile).use { it.write(clock2Bytes) }
        FileOutputStream(previewFile).use {
            preview.compress(Bitmap.CompressFormat.PNG, 90, it)
        }

        val face = SavedFace(
            id = id,
            name = name,
            fileName = fileName,
            savedAt = System.currentTimeMillis(),
            previewPath = previewFile.name,
            dataPath = dataFile.name,
            sourceSha256 = identityHash,
            installCount = (existing?.installCount ?: 0) + 1,
        )

        existing?.let(current::remove)
        current.add(face)
        persist(deduplicate(current))

        Log.d(TAG, if (existing == null) "Remembered face: $name ($id)" else "Updated face: $name ($id)")
        return face
    }

    @Synchronized
    fun delete(id: String) {
        delete(setOf(id))
    }

    @Synchronized
    fun delete(ids: Set<String>) {
        if (ids.isEmpty()) return
        val current = getAll()
        current.filter { it.id in ids }.forEach(::deleteFiles)
        persist(current.filterNot { it.id in ids })
    }

    @Synchronized
    fun clear() {
        getAll().forEach(::deleteFiles)
        persist(emptyList())
    }

    fun loadClock2Bytes(face: SavedFace): ByteArray? {
        val f = File(facesDir, face.dataPath)
        return if (f.exists()) f.readBytes() else null
    }

    private fun persist(faces: List<SavedFace>) {
        val encoded = JSONArray().apply {
            faces.sortedByDescending { it.savedAt }.forEach { face ->
                put(JSONObject().apply {
                    put("id", face.id)
                    put("name", face.name)
                    put("fileName", face.fileName)
                    put("savedAt", face.savedAt)
                    put("previewPath", face.previewPath)
                    put("dataPath", face.dataPath)
                    put("sourceSha256", face.sourceSha256)
                    put("installCount", face.installCount)
                })
            }
        }
        context.getSharedPreferences(PREFS, 0).edit()
            .putString(KEY_FACES_V2, encoded.toString())
            .apply()
    }

    private fun decodeV2(encoded: String): List<SavedFace> {
        val array = JSONArray(encoded)
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                add(
                    SavedFace(
                        id = item.getString("id"),
                        name = item.getString("name"),
                        fileName = item.getString("fileName"),
                        savedAt = item.getLong("savedAt"),
                        previewPath = item.getString("previewPath"),
                        dataPath = item.getString("dataPath"),
                        sourceSha256 = item.optString("sourceSha256"),
                        installCount = item.optInt("installCount", 1).coerceAtLeast(1),
                    ),
                )
            }
        }
    }

    private fun deduplicate(faces: List<SavedFace>): List<SavedFace> {
        val seen = mutableSetOf<String>()
        val kept = mutableListOf<SavedFace>()
        faces.sortedByDescending { it.savedAt }.forEach { face ->
            val key = face.sourceSha256.ifBlank { face.fileName.lowercase() }
            if (seen.add(key)) {
                kept += face
            } else {
                deleteFiles(face)
            }
        }
        return kept
    }

    private fun deleteFiles(face: SavedFace) {
        File(facesDir, face.dataPath).delete()
        File(facesDir, face.previewPath).delete()
    }

    private fun sha256(bytes: ByteArray?): String {
        if (bytes == null) return ""
        return MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
    }
}
