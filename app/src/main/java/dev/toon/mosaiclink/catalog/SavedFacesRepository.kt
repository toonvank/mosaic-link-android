package dev.toon.mosaiclink.catalog

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

class SavedFacesRepository(private val context: Context) {
    companion object {
        private const val TAG = "SavedFaces"
        private const val PREFS = "mosaic_link_catalog"
        private const val KEY_FACES = "saved_faces_list"
    }

    private val facesDir = File(context.filesDir, "saved_faces").also { it.mkdirs() }

    fun getAll(): List<SavedFace> {
        val prefs = context.getSharedPreferences(PREFS, 0)
        val raw = prefs.getString(KEY_FACES, null) ?: return emptyList()
        return raw.split("|||").mapNotNull { entry ->
            val parts = entry.split("```")
            if (parts.size == 6) {
                SavedFace(
                    id = parts[0],
                    name = parts[1],
                    fileName = parts[2],
                    savedAt = parts[3].toLongOrNull() ?: 0L,
                    previewPath = parts[4],
                    dataPath = parts[5],
                    sourceSha256 = "",
                )
            } else null
        }.sortedByDescending { it.savedAt }
    }

    fun save(
        name: String,
        fileName: String,
        clock2Bytes: ByteArray,
        preview: Bitmap,
        sourceSha256: String,
    ): SavedFace {
        val id = UUID.randomUUID().toString()
        val dataFile = File(facesDir, "$id.clock2")
        val previewFile = File(facesDir, "$id.png")

        FileOutputStream(dataFile).use { it.write(clock2Bytes) }
        preview.compress(Bitmap.CompressFormat.PNG, 90, FileOutputStream(previewFile))

        val face = SavedFace(
            id = id,
            name = name,
            fileName = fileName,
            savedAt = System.currentTimeMillis(),
            previewPath = previewFile.name,
            dataPath = dataFile.name,
            sourceSha256 = sourceSha256,
        )

        val current = getAll().toMutableList()
        current.add(face)
        persist(current)

        Log.d(TAG, "Saved face: $name ($id)")
        return face
    }

    fun delete(id: String) {
        val current = getAll().toMutableList()
        val face = current.find { it.id == id }
        if (face != null) {
            File(facesDir, face.dataPath).delete()
            File(facesDir, face.previewPath).delete()
            current.remove(face)
            persist(current)
        }
    }

    fun loadClock2Bytes(face: SavedFace): ByteArray? {
        val f = File(facesDir, face.dataPath)
        return if (f.exists()) f.readBytes() else null
    }

    private fun persist(faces: List<SavedFace>) {
        val raw = faces.joinToString("|||") { f ->
            "${f.id}```${f.name}```${f.fileName}```${f.savedAt}```${f.previewPath}```${f.dataPath}"
        }
        context.getSharedPreferences(PREFS, 0).edit()
            .putString(KEY_FACES, raw)
            .apply()
    }
}