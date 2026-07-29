package dev.toon.mosaiclink.clock2

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Clock2CompatibilityTest {
    @Test
    fun seikoStyleLayoutIsAcceptedByCapability() {
        val document = document(
            layer(0, "image", image = byteArrayOf(1)),
            layer(1, "date", x = 48f, y = 57f, date = "DDAuto"),
            layer(2, "hand", "seconds", y = 49f, image = byteArrayOf(1)),
            layer(3, "hand", "twentyFourhours", x = 32f, y = -1f, image = byteArrayOf(1)),
            layer(4, "hand", "minute", x = -33f, y = -1f, image = byteArrayOf(1)),
            layer(5, "hand", "twelveHours", image = byteArrayOf(1)),
            layer(6, "hand", "minute", image = byteArrayOf(1)),
            layer(7, "hand", "seconds", image = byteArrayOf(1)),
        )

        assertTrue(Clock2Parser.compatibility(document).reasons.joinToString(), Clock2Parser.compatibility(document).supported)
    }

    @Test
    fun missingCentralLiveHandIsRejected() {
        val document = document(
            layer(0, "image", image = byteArrayOf(1)),
            layer(1, "hand", "twelveHours", image = byteArrayOf(1)),
            layer(2, "hand", "minute", image = byteArrayOf(1)),
            layer(3, "hand", "seconds", x = 10f, image = byteArrayOf(1)),
        )

        assertFalse(Clock2Parser.compatibility(document).supported)
    }

    private fun document(vararg layers: Clock2Layer) = Clock2Document(
        name = "Test",
        schemaVersion = 6,
        canvasWidth = 199f,
        canvasHeight = 267f,
        layers = layers.toList(),
        sourceSha256 = "test",
    )

    private fun layer(
        index: Int,
        type: String,
        kind: String = "",
        x: Float = 0f,
        y: Float = 0f,
        date: String = "",
        image: ByteArray? = null,
    ) = Clock2Layer(
        index = index,
        type = type,
        kind = kind,
        x = x,
        y = y,
        width = 100f,
        height = 100f,
        alpha = 1f,
        rotation = 0f,
        hidden = false,
        imageData = image,
        imageFilename = "",
        color = "#FFFFFFFF",
        dateFormat = date,
        fontSize = 20f,
        clockwise = true,
    )
}
