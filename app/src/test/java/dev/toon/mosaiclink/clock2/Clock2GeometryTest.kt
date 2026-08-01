package dev.toon.mosaiclink.clock2

import org.junit.Assert.assertEquals
import org.junit.Test

class Clock2GeometryTest {
    @Test
    fun oversizedAudemarsHandDoesNotBecomeTheCanvas() {
        val canvas = Clock2CanvasInference.infer(
            listOf(
                layer(0, "hand", -53f, -45f, 327f, 327f),
                layer(1, "image", 0f, 0f, 202f, 245f),
                layer(2, "hand", -54f, -48f, 185f, 185f),
                layer(5, "hand", 0f, 0f, 267f, 267f),
            ),
        )

        assertEquals(Clock2CanvasSize(202f, 245f), canvas)
    }

    @Test
    fun offCenterOmegaComplicationsDoNotExpandTheCanvas() {
        val canvas = Clock2CanvasInference.infer(
            listOf(
                layer(0, "image", 0f, 0f, 199f, 242f),
                layer(2, "hand", -41f, -2f, 199f, 242f),
                layer(3, "hand", 0f, 45f, 199f, 242f),
                layer(4, "hand", 40f, -2f, 199f, 242f),
            ),
        )

        assertEquals(Clock2CanvasSize(199f, 242f), canvas)
    }

    @Test
    fun largestCenteredImageWinsOverSmallCenteredOverlay() {
        val canvas = Clock2CanvasInference.infer(
            listOf(
                layer(0, "image", 0f, 0f, 40f, 40f),
                layer(1, "image", 0f, 0f, 204f, 248f),
            ),
        )

        assertEquals(Clock2CanvasSize(204f, 248f), canvas)
    }

    @Test
    fun autoUsesTinyHorizontalCorrectionForAppleWatchAspectFaces() {
        assertEquals(
            ScaleMode.STRETCH_H,
            ScaleModeResolver.resolve(ScaleMode.AUTO, 199f, 242f),
        )
        assertEquals(
            ScaleMode.STRETCH_H,
            ScaleModeResolver.resolve(ScaleMode.AUTO, 202f, 245f),
        )
    }

    @Test
    fun autoPreservesStronglyDifferentAspectRatios() {
        assertEquals(
            ScaleMode.CONTAIN,
            ScaleModeResolver.resolve(ScaleMode.AUTO, 243f, 243f),
        )
        assertEquals(
            ScaleMode.CONTAIN,
            ScaleModeResolver.resolve(ScaleMode.AUTO, 326f, 283f),
        )
    }

    private fun layer(
        index: Int,
        type: String,
        x: Float,
        y: Float,
        width: Float,
        height: Float,
    ) = Clock2LayerGeometry(
        index = index,
        type = type,
        x = x,
        y = y,
        width = width,
        height = height,
        hidden = false,
    )
}
