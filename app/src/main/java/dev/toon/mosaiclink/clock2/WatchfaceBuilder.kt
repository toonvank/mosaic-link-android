package dev.toon.mosaiclink.clock2

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import com.sifli.ezip.sifliEzipUtil
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.ZonedDateTime
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class WatchfaceBuilder(private val context: Context) {
    private data class HandSpec(
        val filename: String,
        val width: Int,
        val height: Int,
        val pivotX: Int,
        val pivotY: Int,
    )

    companion object {
        const val VIEWPORT_WIDTH = 410
        const val VIEWPORT_HEIGHT = 494
        const val PANEL_WIDTH = 485
        const val PANEL_HEIGHT = 520
        private const val MODULE = "wf_clock23"
        private const val DONOR_ASSET = "stock_wf_clock23.zip"
        private const val DONOR_SHA =
            "bff011a4d1afbc717937169cbb23b86cb6952049090e3d3a475a0555f780ad49"

        private val HAND_SPECS = linkedMapOf(
            "twelveHours" to HandSpec("wf_clock23_h.bin", 30, 134, 15, 126),
            "minute" to HandSpec("wf_clock23_m.bin", 30, 210, 15, 202),
            "seconds" to HandSpec("wf_clock23_s.bin", 12, 244, 6, 202),
        )
        private val PINNED_HASHES = mapOf(
            "ex/installer_wf/wf_clock23.dsc" to
                "bedc3c3d720ad54d6cbc0c5fa65f6d786f9cef36377e562f184603ef972e1cd3",
            "ex/installer_wf/wf_clock23.so" to
                "989bed6f8955f28ee1bf5b2232ed11846360c1643d35a00215aa9fe34e1b67c3",
            "ex/installer_wf/wf_clock23_res.so" to
                "8ef72a80dabe96ee24efed7b98aeebeef0499a226c08181976fb9c8589f76148",
            "ex/resource/wf_clock23/wf_clock23_yuan_01.bin" to
                "d5ba3120970f973ee72efc4d05034bb20a52e8051f757b59ec98e5f99f1c08a7",
            "ex/resource/wf_clock23/wf_clock23_yuan_02.bin" to
                "97577ce906fb5280a8477605c7073acca3fc88b311f4810e2ce83237b4e44316",
        )
        private val EXPECTED_FILES = setOf(
            "ex/installer_wf/wf_clock23.dsc",
            "ex/installer_wf/wf_clock23.so",
            "ex/installer_wf/wf_clock23_res.so",
            "ex/installer_wf/wf_clock23_tn.bin",
            "ex/resource/wf_clock23/wf_clock23_bg.bin",
            "ex/resource/wf_clock23/wf_clock23_h.bin",
            "ex/resource/wf_clock23/wf_clock23_m.bin",
            "ex/resource/wf_clock23/wf_clock23_s.bin",
            "ex/resource/wf_clock23/wf_clock23_yuan_01.bin",
            "ex/resource/wf_clock23/wf_clock23_yuan_02.bin",
        )
    }

    fun build(
        document: Clock2Document,
        instant: ZonedDateTime = ZonedDateTime.now(),
        battery: Int = 73,
    ): BuiltWatchface {
        val compatibility = Clock2Parser.compatibility(document)
        require(compatibility.supported) {
            compatibility.reasons.joinToString("; ")
        }
        val donor = context.assets.open(DONOR_ASSET).use { it.readBytes() }
        check(donor.sha256() == DONOR_SHA) { "Bundled stock donor hash mismatch" }
        val files = unzip(donor)
        check(files.keys == EXPECTED_FILES) { "Stock donor file topology mismatch" }
        PINNED_HASHES.forEach { (path, hash) ->
            check(files[path]?.sha256() == hash) { "Pinned stock file changed: $path" }
        }

        val static = renderStatic(document, instant, battery)
        val mainLayers = document.activeLayers
            .filter(::isMainHand)
            .associateBy { it.kind }
        check(mainLayers.keys.containsAll(HAND_SPECS.keys)) {
            "Compatible face is missing a central hand"
        }
        val hands = linkedMapOf<String, Bitmap>()
        HAND_SPECS.forEach { (kind, spec) ->
            val hand = fitHand(document, requireNotNull(mainLayers[kind]), spec)
            hands[kind] = hand
            files["ex/resource/$MODULE/${spec.filename}"] = rawResource(hand)
        }

        val background = encodeEzip(static)
        validateEzip(background, VIEWPORT_WIDTH, VIEWPORT_HEIGHT)
        files["ex/resource/$MODULE/${MODULE}_bg.bin"] = background

        val liveViewport = renderLiveViewport(static, hands, instant)
        val thumbnail = Bitmap.createScaledBitmap(liveViewport, 262, 316, true)
        val thumbnailBin = encodeEzip(thumbnail)
        validateEzip(thumbnailBin, 262, 316)
        files["ex/installer_wf/${MODULE}_tn.bin"] = thumbnailBin

        validateGenerated(files)
        val manifest = JSONObject()
            .put("format", 3)
            .put("builder", "mosaic-link-android")
            .put("template_id", "clock2-stock-binary-wf_clock23-v3")
            .put("source_clock2_sha256", document.sourceSha256)
            .put("stock_native_sha256", PINNED_HASHES.getValue(
                "ex/installer_wf/wf_clock23.so",
            ))
            .put("modified_paths", JSONArray(listOf(
                "ex/installer_wf/wf_clock23_tn.bin",
                "ex/resource/wf_clock23/wf_clock23_bg.bin",
                "ex/resource/wf_clock23/wf_clock23_h.bin",
                "ex/resource/wf_clock23/wf_clock23_m.bin",
                "ex/resource/wf_clock23/wf_clock23_s.bin",
            )))
            .put("baked_time", instant.toString())
            .put("baked_battery", battery.coerceIn(0, 100))
            .put("watch_contacted", false)
        val packageBytes = zip(files, "mosaic-link:${manifest}")

        val panel = Bitmap.createBitmap(PANEL_WIDTH, PANEL_HEIGHT, Bitmap.Config.ARGB_8888)
        Canvas(panel).apply {
            drawColor(Color.BLACK)
            drawBitmap(
                liveViewport,
                ((PANEL_WIDTH - VIEWPORT_WIDTH) / 2).toFloat(),
                ((PANEL_HEIGHT - VIEWPORT_HEIGHT) / 2).toFloat(),
                null,
            )
        }
        val preview = png(panel)
        return BuiltWatchface(
            displayName = document.name,
            packageBytes = packageBytes,
            previewPng = preview,
            sourceSha256 = document.sourceSha256,
            packageSha256 = packageBytes.sha256(),
            fileCount = files.size,
        )
    }

    private fun renderStatic(
        document: Clock2Document,
        instant: ZonedDateTime,
        battery: Int,
    ): Bitmap {
        val output = Bitmap.createBitmap(
            VIEWPORT_WIDTH, VIEWPORT_HEIGHT, Bitmap.Config.ARGB_8888,
        )
        val canvas = Canvas(output)
        canvas.drawColor(Color.BLACK)
        val xScale = xScale(document)
        val yScale = yScale(document)
        val centerX = VIEWPORT_WIDTH / 2f
        val centerY = VIEWPORT_HEIGHT / 2f
        // Capture all asset keys before BitmapFactory sees any buffers. Clock2
        // uses the filename as its deduplication/reference identity.
        val cacheKeys = document.activeLayers
            .filter { it.type != "date" && !isMainHand(it) }
            .associate { it.index to layerCacheKey(document, it) }
        val decodedImages = mutableMapOf<String, Bitmap>()
        try {
            document.activeLayers.filterNot(::isMainHand).forEach { layer ->
                val x = centerX + layer.x * xScale
                val y = centerY + layer.y * yScale
                if (layer.type == "date") {
                    val text = if (layer.dateFormat in setOf("D", "DAuto")) {
                        instant.dayOfMonth.toString()
                    } else {
                        "%02d".format(instant.dayOfMonth)
                    }
                    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = parseColor(layer.color)
                        textSize = max(1f, layer.fontSize * yScale)
                        typeface = Typeface.create("sans-serif-condensed", Typeface.NORMAL)
                        textAlign = Paint.Align.CENTER
                        alpha = (layer.alpha * 255).roundToInt().coerceIn(0, 255)
                    }
                    val baseline = y - (paint.ascent() + paint.descent()) / 2f
                    canvas.drawText(text, x, baseline, paint)
                } else {
                    val key = requireNotNull(cacheKeys[layer.index])
                    val source = decodedImages.getOrPut(key) {
                        layerBitmap(document, layer)
                    }
                    var image = source
                    if (layer.type == "hand") {
                        var angle = handAngle(layer.kind, instant, battery)
                        if (!layer.clockwise) angle = -angle
                        image = rotateFixed(source, -(angle + layer.rotation))
                    } else if (layer.rotation != 0f) {
                        image = rotateFixed(source, -layer.rotation)
                    }
                    val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                        alpha = (layer.alpha * 255).roundToInt().coerceIn(0, 255)
                    }
                    canvas.drawBitmap(image, x - image.width / 2f, y - image.height / 2f, paint)
                    if (image !== source) image.recycle()
                }
            }
        } finally {
            decodedImages.values.forEach(Bitmap::recycle)
        }
        return output
    }

    private fun renderLiveViewport(
        static: Bitmap,
        hands: Map<String, Bitmap>,
        instant: ZonedDateTime,
    ): Bitmap {
        val output = static.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(output)
        val centerX = VIEWPORT_WIDTH / 2f
        val centerY = VIEWPORT_HEIGHT / 2f
        HAND_SPECS.forEach { (kind, spec) ->
            canvas.save()
            canvas.rotate(handAngle(kind, instant, 0), centerX, centerY)
            canvas.drawBitmap(
                requireNotNull(hands[kind]),
                centerX - spec.pivotX,
                centerY - spec.pivotY,
                Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG),
            )
            canvas.restore()
        }
        return output
    }

    private fun fitHand(
        document: Clock2Document,
        layer: Clock2Layer,
        spec: HandSpec,
    ): Bitmap {
        val source = layerBitmap(document, layer)
        val bounds = alphaBounds(source)
            ?: error("Layer ${layer.index} has no visible hand pixels")
        val centerX = source.width / 2
        val centerY = source.height / 2
        val horizontalScale = min(
            (spec.pivotX - 1f) / max(1, centerX - bounds.left),
            (spec.width - spec.pivotX - 1f) / max(1, bounds.right - centerX),
        )
        val targetWidth = max(1, (bounds.width() * horizontalScale).roundToInt())
        val targetPivotX = ((centerX - bounds.left) * horizontalScale).roundToInt()
        val pasteX = spec.pivotX - targetPivotX
        val output = Bitmap.createBitmap(spec.width, spec.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        if (centerY > bounds.top) {
            canvas.drawBitmap(
                source,
                Rect(bounds.left, bounds.top, bounds.right, centerY),
                Rect(pasteX, 1, pasteX + targetWidth, spec.pivotY),
                paint,
            )
        }
        if (bounds.bottom > centerY) {
            canvas.drawBitmap(
                source,
                Rect(bounds.left, centerY, bounds.right, bounds.bottom),
                Rect(
                    pasteX,
                    spec.pivotY,
                    pasteX + targetWidth,
                    max(spec.pivotY + 1, spec.height - 1),
                ),
                paint,
            )
        }
        source.recycle()
        return output
    }

    private fun layerBitmap(document: Clock2Document, layer: Clock2Layer): Bitmap {
        val bytes = requireNotNull(layer.imageData) {
            "Layer ${layer.index} has no embedded image"
        }
        val targetWidth = max(1, (layer.width * xScale(document)).roundToInt())
        val targetHeight = max(1, (layer.height * yScale(document)).roundToInt())
        val dimensions = imageDimensions(bytes)
        check(dimensions.first > 0 && dimensions.second > 0) {
            "Layer ${layer.index} image has invalid dimensions"
        }
        var sampleSize = 1
        while (
            dimensions.first / (sampleSize * 2) >= targetWidth &&
            dimensions.second / (sampleSize * 2) >= targetHeight
        ) {
            sampleSize *= 2
        }
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        // Some vendor BitmapFactory implementations decode PNGs in place.
        // Clock2 intentionally reuses the same ByteArray for deduplicated assets,
        // so always give the decoder a disposable copy.
        val decodeBytes = bytes.copyOf()
        val source = BitmapFactory.decodeByteArray(
            decodeBytes, 0, decodeBytes.size, options,
        )
            ?: error("Layer ${layer.index} image could not be decoded")
        val scaled = placeInFrame(source, targetWidth, targetHeight, layer.contentMode)
        source.recycle()
        return scaled
    }

    private fun layerCacheKey(document: Clock2Document, layer: Clock2Layer): String {
        val targetWidth = max(1, (layer.width * xScale(document)).roundToInt())
        val targetHeight = max(1, (layer.height * yScale(document)).roundToInt())
        val assetIdentity = layer.imageFilename.ifBlank {
            System.identityHashCode(requireNotNull(layer.imageData)).toString()
        }
        return "$assetIdentity:$targetWidth:$targetHeight:${layer.contentMode}"
    }

    private fun placeInFrame(
        source: Bitmap,
        width: Int,
        height: Int,
        contentMode: String,
    ): Bitmap {
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val sourceRatio = source.width.toFloat() / source.height
        val frameRatio = width.toFloat() / height
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        if (contentMode == "fill") {
            val crop = if (sourceRatio > frameRatio) {
                val cropWidth = (source.height * frameRatio).roundToInt()
                Rect(
                    (source.width - cropWidth) / 2,
                    0,
                    (source.width + cropWidth) / 2,
                    source.height,
                )
            } else {
                val cropHeight = (source.width / frameRatio).roundToInt()
                Rect(
                    0,
                    (source.height - cropHeight) / 2,
                    source.width,
                    (source.height + cropHeight) / 2,
                )
            }
            canvas.drawBitmap(source, crop, Rect(0, 0, width, height), paint)
        } else {
            val destination = if (sourceRatio > frameRatio) {
                val drawHeight = (width / sourceRatio).roundToInt()
                Rect(0, (height - drawHeight) / 2, width, (height + drawHeight) / 2)
            } else {
                val drawWidth = (height * sourceRatio).roundToInt()
                Rect((width - drawWidth) / 2, 0, (width + drawWidth) / 2, height)
            }
            canvas.drawBitmap(
                source,
                Rect(0, 0, source.width, source.height),
                destination,
                paint,
            )
        }
        return output
    }

    private fun imageDimensions(bytes: ByteArray): Pair<Int, Int> {
        val pngSignature = byteArrayOf(
            0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
        )
        if (bytes.size >= 24 && bytes.copyOfRange(0, 8).contentEquals(pngSignature)) {
            val header = ByteBuffer.wrap(bytes, 16, 8).order(ByteOrder.BIG_ENDIAN)
            return header.int to header.int
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        return bounds.outWidth to bounds.outHeight
    }

    private fun alphaBounds(bitmap: Bitmap): Rect? {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        var left = bitmap.width
        var top = bitmap.height
        var right = -1
        var bottom = -1
        pixels.forEachIndexed { index, color ->
            if (Color.alpha(color) != 0) {
                val x = index % bitmap.width
                val y = index / bitmap.width
                left = min(left, x)
                top = min(top, y)
                right = max(right, x)
                bottom = max(bottom, y)
            }
        }
        return if (right < left || bottom < top) null
        else Rect(left, top, right + 1, bottom + 1)
    }

    private fun rawResource(bitmap: Bitmap): ByteArray {
        val body = ByteBuffer.allocate(4 + bitmap.width * bitmap.height * 3)
            .order(ByteOrder.LITTLE_ENDIAN)
        body.putInt(5 or (bitmap.width shl 10) or (bitmap.height shl 21))
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        pixels.forEach { color ->
            val rgb565 = ((Color.red(color) shr 3) shl 11) or
                ((Color.green(color) shr 2) shl 5) or
                (Color.blue(color) shr 3)
            body.putShort(rgb565.toShort())
            body.put(Color.alpha(color).toByte())
        }
        val payload = body.array()
        return ByteBuffer.allocate(payload.size + 4)
            .order(ByteOrder.LITTLE_ENDIAN)
            .put(payload)
            .putInt(Crc32Mpeg2.compute(payload))
            .array()
    }

    private fun encodeEzip(bitmap: Bitmap): ByteArray =
        sifliEzipUtil.encodeRgb565Alpha(png(bitmap))

    private fun png(bitmap: Bitmap): ByteArray = ByteArrayOutputStream().use { output ->
        check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
            "PNG encoding failed"
        }
        output.toByteArray()
    }

    private fun validateGenerated(files: Map<String, ByteArray>) {
        validateEzip(requireNotNull(files["ex/installer_wf/wf_clock23_tn.bin"]), 262, 316)
        validateEzip(
            requireNotNull(files["ex/resource/wf_clock23/wf_clock23_bg.bin"]),
            VIEWPORT_WIDTH,
            VIEWPORT_HEIGHT,
        )
        HAND_SPECS.values.forEach { spec ->
            validateRaw(
                requireNotNull(files["ex/resource/$MODULE/${spec.filename}"]),
                spec.width,
                spec.height,
            )
        }
        PINNED_HASHES.forEach { (path, hash) ->
            check(files[path]?.sha256() == hash) { "Pinned byte changed: $path" }
        }
    }

    private fun validateEzip(data: ByteArray, width: Int, height: Int) {
        val header = ByteBuffer.wrap(data, 0, 4).order(ByteOrder.LITTLE_ENDIAN).int
        check(header and 0x3ff == 2) { "eZip resource has the wrong color format" }
        check((header shr 10) and 0x7ff == width) { "eZip resource has the wrong width" }
        check((header shr 21) and 0x7ff == height) { "eZip resource has the wrong height" }
    }

    private fun validateRaw(data: ByteArray, width: Int, height: Int) {
        val header = ByteBuffer.wrap(data, 0, 4).order(ByteOrder.LITTLE_ENDIAN).int
        check(header and 0x3ff == 5)
        check((header shr 10) and 0x7ff == width)
        check((header shr 21) and 0x7ff == height)
        check(data.size == 8 + width * height * 3)
        val expected = ByteBuffer.wrap(data, data.size - 4, 4)
            .order(ByteOrder.LITTLE_ENDIAN).int
        check(expected == Crc32Mpeg2.compute(data.copyOf(data.size - 4))) {
            "Hand resource CRC mismatch"
        }
    }

    private fun unzip(bytes: ByteArray): MutableMap<String, ByteArray> {
        val result = linkedMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                if (!entry.isDirectory) result[entry.name] = input.readBytes()
                input.closeEntry()
            }
        }
        return result
    }

    private fun zip(files: Map<String, ByteArray>, comment: String): ByteArray =
        ByteArrayOutputStream().use { output ->
            ZipOutputStream(output).use { zip ->
                zip.setLevel(9)
                zip.setComment(comment)
                files.toSortedMap().forEach { (name, bytes) ->
                    val entry = ZipEntry(name).apply { time = 315532800000L }
                    zip.putNextEntry(entry)
                    zip.write(bytes)
                    zip.closeEntry()
                }
            }
            output.toByteArray()
        }

    private fun rotateFixed(source: Bitmap, degrees: Float): Bitmap {
        if (degrees == 0f) return source
        val result = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        Canvas(result).apply {
            rotate(degrees, source.width / 2f, source.height / 2f)
            drawBitmap(source, 0f, 0f, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        }
        return result
    }

    private fun xScale(document: Clock2Document): Float =
        VIEWPORT_WIDTH / document.canvasWidth

    private fun yScale(document: Clock2Document): Float =
        VIEWPORT_HEIGHT / document.canvasHeight

    private fun isMainHand(layer: Clock2Layer): Boolean =
        layer.type == "hand" && layer.kind in HAND_SPECS && layer.x == 0f && layer.y == 0f

    private fun handAngle(kind: String, time: ZonedDateTime, battery: Int): Float =
        when (kind) {
            "twelveHours" -> (time.hour % 12) * 30f + time.minute * 0.5f +
                time.second / 120f
            "minute" -> time.minute * 6f + time.second * 0.1f
            "seconds" -> time.second * 6f + time.nano / 1_000_000_000f * 6f
            "twentyFourhours" -> (time.hour + time.minute / 60f) * 15f
            "battery" -> battery.coerceIn(0, 100) * 3.6f
            else -> error("Unsupported hand $kind")
        }

    private fun parseColor(value: String): Int = runCatching {
        val raw = value.removePrefix("#")
        if (raw.length == 8) {
            val red = raw.substring(0, 2).toInt(16)
            val green = raw.substring(2, 4).toInt(16)
            val blue = raw.substring(4, 6).toInt(16)
            val alpha = raw.substring(6, 8).toInt(16)
            Color.argb(alpha, red, green, blue)
        } else {
            Color.parseColor(value)
        }
    }.getOrDefault(Color.WHITE)
}

internal object Crc32Mpeg2 {
    fun compute(bytes: ByteArray, initial: Int = -1): Int {
        var crc = initial
        bytes.forEach { byte ->
            crc = crc xor ((byte.toInt() and 0xff) shl 24)
            repeat(8) {
                crc = if (crc and Int.MIN_VALUE != 0) {
                    (crc shl 1) xor 0x04C11DB7
                } else {
                    crc shl 1
                }
            }
        }
        return crc
    }
}
