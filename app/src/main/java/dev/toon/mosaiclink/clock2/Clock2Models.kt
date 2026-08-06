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

    /**
     * Image layers that could serve as the watchface background.
     * When a Clock2 face has multiple large image layers (e.g. a main
     * background and an AOD variant), the user can choose which one to use.
     * A layer is considered a background candidate if it's a large image
     * (not a hand, not a small overlay) centered at or near (0,0).
     */
    val backgroundCandidates: List<Clock2Layer>
        get() = activeLayers.filter { layer ->
            layer.type == "image" &&
                layer.kind != "twelveHours" &&
                layer.kind != "minute" &&
                layer.kind != "seconds" &&
                (layer.x * 1000f).toInt() == 0 &&
                (layer.y * 1000f).toInt() == 0
        }
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
    val scaleMode: ScaleMode = ScaleMode.CONTAIN,
    val backgroundIndex: Int = 0,
)
