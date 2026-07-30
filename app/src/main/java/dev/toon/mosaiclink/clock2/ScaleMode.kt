package dev.toon.mosaiclink.clock2

enum class ScaleMode {
    AUTO,
    CONTAIN,
    COVER,
    STRETCH,
    STRETCH_H;

    fun isStretch(): Boolean = this == STRETCH
}

object ScaleModeResolver {
    fun resolve(mode: ScaleMode, canvasWidth: Float, canvasHeight: Float): ScaleMode {
        if (mode != ScaleMode.AUTO) return mode
        val canvasRatio = canvasWidth / canvasHeight
        val panelRatio = 485f / 520f
        return if (kotlin.math.abs(canvasRatio - panelRatio) < 0.05f) {
            ScaleMode.STRETCH
        } else if (canvasRatio < panelRatio) {
            ScaleMode.STRETCH_H
        } else {
            ScaleMode.CONTAIN
        }
    }
}