package dev.toon.mosaiclink.clock2

enum class ScaleMode(val label: String) {
    AUTO("Auto"),
    CONTAIN("Safe fit"),
    COVER("Fill screen"),
    STRETCH("Stretch"),
    STRETCH_H("Horizontal stretch");
}

object ScaleModeResolver {
    private const val MAX_AUTO_ASPECT_DISTORTION = 0.08f

    fun resolve(
        mode: ScaleMode,
        canvasWidth: Float,
        canvasHeight: Float,
        targetWidth: Float = 434f,
        targetHeight: Float = 494f,
    ): ScaleMode {
        if (mode != ScaleMode.AUTO) return mode
        val canvasRatio = canvasWidth / canvasHeight
        val targetRatio = targetWidth / targetHeight
        val distortion = maxOf(
            canvasRatio / targetRatio,
            targetRatio / canvasRatio,
        ) - 1f
        return if (distortion > MAX_AUTO_ASPECT_DISTORTION) {
            ScaleMode.CONTAIN
        } else if (canvasRatio <= targetRatio) {
            ScaleMode.STRETCH_H
        } else {
            ScaleMode.STRETCH
        }
    }
}
