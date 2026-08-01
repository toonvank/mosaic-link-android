package dev.toon.mosaiclink.catalog

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File

data class SavedFace(
    val id: String,
    val name: String,
    val fileName: String,
    val savedAt: Long,
    val previewPath: String,
    val dataPath: String,
    val sourceSha256: String,
    val installCount: Int = 1,
) {
    fun loadPreview(context: Context): Bitmap? {
        val f = File(context.filesDir, "saved_faces/$previewPath")
        return if (f.exists()) BitmapFactory.decodeFile(f.absolutePath) else null
    }
}
