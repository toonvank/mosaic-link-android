package dev.toon.mosaiclink.clock2

data class Clock2Layer(
    val index: Int,
    val type: String,
    val kind: String,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val alpha: Float,
    val rotation: Float,
    val hidden: Boolean,
    val imageData: ByteArray?,
    val imageFilename: String,
    val color: String,
    val dateFormat: String,
    val fontSize: Float,
    val clockwise: Boolean,
    val contentMode: String = "fit",
    val isHeader: Boolean = false,
    val dateCustomFormat: String = "",
    val imageStripTimeWindow: String = "",
    val imageStripHorizontal: Boolean = false,
    val imageStripFrames: Int = 0,
    val shapeType: String = "",
    val cornerRadius: Float = 0f,
    val outlineWidth: Float = 0f,
    val outlineColor: String = "#FFFFFFFF",
    val layerName: String = "",
    val timeFormat: String = "",
    val timeCustomFormat: String = "",
    val dataLabelKind: String = "",
    val alignment: String = "center",
    val fontData: ByteArray? = null,
    val fontName: String = "",
    val casing: String = "unmodified",
    val textEffect: String = "none",
    val textEffectModifier: Float = 0f,
    val detailLevel: String = "unmodified",
    val weatherFormat: String = "",
    val iconType: String = "",
    val iconThickness: Int = 0,
    val iconSize: Float = 0f,
    val dataBarFormat: String = "",
    val dataBarStyle: String = "",
    val dataBarStartColor: String = "#FFFFFFFF",
    val dataBarEndColor: String = "#FFFFFFFF",
    val dataBarBackgroundColor: String = "#FFFFFF00",
    val dataBarDashPadding: Float = 0f,
) {
    val active: Boolean get() = !hidden && !isHeader && alpha > 0f
}

data class Clock2Document(
    val name: String,
    val schemaVersion: Int,
    val canvasWidth: Float,
    val canvasHeight: Float,
    val layers: List<Clock2Layer>,
    val sourceSha256: String,
) {
    val activeLayers: List<Clock2Layer> get() = layers.filter { it.active }
}

data class Clock2Requirements(
    val backgroundImages: Int,
    val dateFormats: List<String>,
    val analogGroups: Int,
    val independentHands: List<String>,
    val handCounts: Map<String, Int>,
)

data class Compatibility(
    val supported: Boolean,
    val reasons: List<String>,
    val warnings: List<String>,
    val requirements: Clock2Requirements,
)

data class BuiltWatchface(
    val displayName: String,
    val packageBytes: ByteArray,
    val previewPng: ByteArray,
    val sourceSha256: String,
    val packageSha256: String,
    val fileCount: Int,
    val warnings: List<String>,
    val scaleMode: ScaleMode = ScaleMode.STRETCH,
    val experimentalNativeRuntime: Boolean = false,
    val runtimeLabel: String = "stock face 23",
)
