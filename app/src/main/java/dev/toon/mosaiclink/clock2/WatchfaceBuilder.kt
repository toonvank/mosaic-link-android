package dev.toon.mosaiclink.clock2

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import com.sifli.ezip.sifliEzipUtil
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class WatchfaceBuilder(private val context: Context) {
    private val fontCache = mutableMapOf<String, Typeface>()

    private fun layerTypeface(layer: Clock2Layer): Typeface {
        val fontData = layer.fontData
        if (fontData != null && fontData.size >= 4) {
            val cacheKey = layer.fontName.ifBlank {
                layer.imageFilename.ifBlank { "${fontData.size}" }
            }
            return fontCache.getOrPut(cacheKey) {
                Typeface.createFromFile(
                    java.io.File.createTempFile("clock2font", ".ttf").apply {
                    writeBytes(fontData)
                    deleteOnExit()
                }
                )
            }
        }
        return if (layer.fontName == "Apple Symbols") {
            Typeface.create("sans-serif-light", Typeface.NORMAL)
        } else {
            Typeface.create("sans-serif-condensed", Typeface.NORMAL)
        }
    }

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
        private const val MODULE_PATH = "ex/installer_wf/wf_clock23.so"
        private const val DONOR_ASSET = "stock_wf_clock23.zip"
        private const val DONOR_SHA =
            "bff011a4d1afbc717937169cbb23b86cb6952049090e3d3a475a0555f780ad49"
        private const val DIGITAL_MODULE = "wf_clock443"
        private const val DIGITAL_MODULE_PATH = "ex/installer_wf/wf_clock443.so"
        private const val DIGITAL_DONOR_ASSET = "stock_wf_clock443.zip"
        private const val DIGITAL_RUNTIME_ASSET = "wf_clock443_digital.so"
        private const val DIGITAL_DONOR_SHA =
            "3e5e50ec726629661ae3a399f4c8376e2177f3cac7b8fbd591043e14477d3927"

        private val HAND_SPECS = linkedMapOf(
            "twelveHours" to HandSpec("wf_clock23_h.bin", 30, 134, 15, 126),
            "twentyFourhours" to HandSpec("wf_clock23_h.bin", 30, 134, 15, 126),
            "minute" to HandSpec("wf_clock23_m.bin", 30, 210, 15, 202),
            "seconds" to HandSpec("wf_clock23_s.bin", 12, 244, 6, 202),
        )
        private val PINNED_HASHES = mapOf(
            "ex/installer_wf/wf_clock23.dsc" to
                "bedc3c3d720ad54d6cbc0c5fa65f6d786f9cef36377e562f184603ef972e1cd3",
            MODULE_PATH to
                "989bed6f8955f28ee1bf5b2232ed11846360c1643d35a00215aa9fe34e1b67c3",
            "ex/installer_wf/wf_clock23_res.so" to
                "8ef72a80dabe96ee24efed7b98aeebeef0499a226c08181976fb9c8589f76148",
            "ex/resource/wf_clock23/wf_clock23_yuan_01.bin" to
                "d5ba3120970f973ee72efc4d05034bb20a52e8051f757b59ec98e5f99f1c08a7",
            "ex/resource/wf_clock23/wf_clock23_yuan_02.bin" to
                "97577ce906fb5280a8477605c7073acca3fc88b311f4810e2ce83237b4e44316",
        )
        private val DIGITAL_PINNED_HASHES = mapOf(
            "ex/installer_wf/wf_clock443.dsc" to
                "8db1e7ae6fb64a26718e9caba5f94ce65f37d9b55fe774682241f48ee14b1f94",
            "ex/installer_wf/wf_clock443_res.so" to
                "3e7b683d1d2a7ea8faa058da1f59482f913fef7710ef0afdeefa7a1c6df3db97",
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
        scaleMode: ScaleMode = ScaleMode.AUTO,
    ): BuiltWatchface {
        val compatibility = Clock2Parser.compatibility(document)
        require(compatibility.supported) {
            compatibility.reasons.joinToString("; ")
        }
        if (document.activeLayers.any { it.type == "time" }) {
            return buildDigital(document, instant, battery, scaleMode, compatibility)
        }
        val resolvedMode = ScaleModeResolver.resolve(
            scaleMode, document.canvasWidth, document.canvasHeight,
        )
        val donor = context.assets.open(DONOR_ASSET).use { it.readBytes() }
        check(donor.sha256() == DONOR_SHA) { "Bundled stock donor hash mismatch" }
        val files = unzip(donor)
        check(files.keys == EXPECTED_FILES) { "Stock donor file topology mismatch" }
        PINNED_HASHES.forEach { (path, hash) ->
            check(files[path]?.sha256() == hash) { "Pinned stock file changed: $path" }
        }
        files[MODULE_PATH] = files.getValue(MODULE_PATH)

        val static = renderStatic(document, instant, battery, resolvedMode)
        val mainLayers = document.activeLayers
            .filter(::isMainHand)
            .associateBy { it.kind }
        val hourKind = when {
            mainLayers["twelveHours"] != null -> "twelveHours"
            mainLayers["twentyFourhours"] != null -> "twentyFourhours"
            else -> null
        }
        val hands = linkedMapOf<String, Bitmap>()
        HAND_SPECS.filterKeys { it != "twentyFourhours" }.forEach { (kind, spec) ->
            val sourceKind = if (kind == "twelveHours" && hourKind != null) hourKind else kind
            val hand = mainLayers[sourceKind]?.let { fitHand(document, it, spec) }
                ?: Bitmap.createBitmap(spec.width, spec.height, Bitmap.Config.ARGB_8888)
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
            .put("template_id", "clock2-stock-binary-wf_clock23")
            .put("source_clock2_sha256", document.sourceSha256)
            .put("stock_native_sha256", PINNED_HASHES.getValue(
                MODULE_PATH,
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
            warnings = compatibility.warnings,
            scaleMode = resolvedMode,
        )
    }

    private data class DigitalSlot(
        val index: Int,
        val layer: Clock2Layer,
        val maxChars: Int,
        val x: Int,
        val y: Int,
    )

    private data class DigitalSequenceSlot(
        val index: Int,
        val layer: Clock2Layer,
        val x: Int,
        val y: Int,
    )

    private fun buildDigital(
        document: Clock2Document,
        instant: ZonedDateTime,
        battery: Int,
        scaleMode: ScaleMode,
        compatibility: Compatibility,
    ): BuiltWatchface {
        val resolvedMode = ScaleModeResolver.resolve(
            scaleMode, document.canvasWidth, document.canvasHeight,
        )
        val donor = context.assets.open(DIGITAL_DONOR_ASSET).use { it.readBytes() }
        check(donor.sha256() == DIGITAL_DONOR_SHA) { "Bundled digital donor hash mismatch" }
        val files = unzip(donor)
        DIGITAL_PINNED_HASHES.forEach { (path, hash) ->
            check(files[path]?.sha256() == hash) { "Pinned digital donor file changed: $path" }
        }
        val runtime = context.assets.open(DIGITAL_RUNTIME_ASSET).use { it.readBytes() }

        val slots = digitalSlots(document, resolvedMode)
        val sequenceSlots = digitalSequenceSlots(document, resolvedMode)
        val positions = MutableList(DigitalRuntimePatch.SLOT_COUNT) {
            DigitalRuntimePatch.Position()
        }
        slots.forEach { slot ->
            positions[slot.index] = DigitalRuntimePatch.Position(slot.x, slot.y)
        }
        val sequencePositions = MutableList(DigitalRuntimePatch.SEQUENCE_SLOT_COUNT) {
            DigitalRuntimePatch.Position()
        }
        sequenceSlots.forEach { slot ->
            sequencePositions[slot.index] = DigitalRuntimePatch.Position(slot.x, slot.y)
        }
        files[DIGITAL_MODULE_PATH] = DigitalRuntimePatch.apply(
            runtime, positions, sequencePositions,
        )
        files.keys.filter { it.startsWith("ex/resource/$DIGITAL_MODULE/") }
            .toList()
            .forEach(files::remove)

        val excluded = document.activeLayers
            .filter {
                it.type in setOf("time", "date", "dataLabel", "dataBar")
            }
            .mapTo(mutableSetOf()) { it.index }
        val backgroundBitmap = renderStatic(
            document, instant, 0, resolvedMode, excluded,
        )
        drawDigitalSeparators(backgroundBitmap, document, resolvedMode)
        drawDigitalDataAffixes(backgroundBitmap, document, resolvedMode)
        files["ex/resource/$DIGITAL_MODULE/background.bin"] = encodeEzip(backgroundBitmap)

        val mainLayers = document.activeLayers
            .filter(::isMainHand)
            .associateBy { it.kind }
        val hourKind = when {
            mainLayers["twelveHours"] != null -> "twelveHours"
            mainLayers["twentyFourhours"] != null -> "twentyFourhours"
            else -> null
        }
        val hands = linkedMapOf<String, Bitmap>()
        HAND_SPECS.filterKeys { it != "twentyFourhours" }.forEach { (kind, spec) ->
            val sourceKind = if (kind == "twelveHours" && hourKind != null) hourKind else kind
            val hand = mainLayers[sourceKind]?.let { fitHand(document, it, spec) }
                ?: Bitmap.createBitmap(spec.width, spec.height, Bitmap.Config.ARGB_8888)
            hands[kind] = hand
            val suffix = when (kind) {
                "twelveHours" -> "h"
                "minute" -> "m"
                else -> "s"
            }
            files["ex/resource/$DIGITAL_MODULE/main_$suffix.bin"] = rawResource(hand)
        }
        files["ex/resource/$DIGITAL_MODULE/center.bin"] = rawResource(
            Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888),
        )

        val transparent = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        repeat(DigitalRuntimePatch.SLOT_COUNT) { slotIndex ->
            val slot = slots.firstOrNull { it.index == slotIndex }
            val digits = slot?.let { digitAtlas(it.layer, document, resolvedMode) }
                ?: List(10) { transparent }
            digits.forEachIndexed { digit, bitmap ->
                files["ex/resource/$DIGITAL_MODULE/s${slotIndex}_${digit}.bin"] =
                    rawResource(bitmap)
            }
            if (slot != null) digits.forEach(Bitmap::recycle)
        }
        transparent.recycle()

        repeat(DigitalRuntimePatch.SEQUENCE_SLOT_COUNT) { slotIndex ->
            val slot = sequenceSlots.firstOrNull { it.index == slotIndex }
            val frames = if (slot == null) {
                val count = when (slotIndex) {
                    0 -> 12
                    1 -> 7
                    else -> 11
                }
                List(count) {
                    Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
                }
            } else {
                digitalSequenceFrames(slot, document, resolvedMode)
            }
            frames.forEachIndexed { frameIndex, bitmap ->
                files["ex/resource/$DIGITAL_MODULE/q${slotIndex}_${frameIndex}.bin"] =
                    rawResource(bitmap)
                bitmap.recycle()
            }
        }

        val previewStatic = renderStatic(document, instant, battery, resolvedMode)
        val previewBitmap = renderLiveViewport(previewStatic, hands, instant)
        val thumbnail = Bitmap.createScaledBitmap(previewBitmap, 262, 316, true)
        files["ex/installer_wf/${DIGITAL_MODULE}_tn.bin"] = encodeEzip(thumbnail)

        val manifest = JSONObject()
            .put("format", 4)
            .put("builder", "mosaic-link-android")
            .put("template_id", "clock2-live-digital-wf_clock443-v1")
            .put("source_clock2_sha256", document.sourceSha256)
            .put("runtime_sha256", runtime.sha256())
            .put("runtime_patched_sha256", files.getValue(DIGITAL_MODULE_PATH).sha256())
            .put("live_slots", JSONArray(slots.map { it.index }))
            .put("live_sequence_slots", JSONArray(sequenceSlots.map { it.index }))
            .put("watch_contacted", false)
        val packageBytes = zip(files, "mosaic-link:${manifest}")

        val panel = Bitmap.createBitmap(PANEL_WIDTH, PANEL_HEIGHT, Bitmap.Config.ARGB_8888)
        Canvas(panel).apply {
            drawColor(Color.BLACK)
            drawBitmap(
                previewBitmap,
                ((PANEL_WIDTH - VIEWPORT_WIDTH) / 2).toFloat(),
                ((PANEL_HEIGHT - VIEWPORT_HEIGHT) / 2).toFloat(),
                null,
            )
        }
        return BuiltWatchface(
            displayName = document.name,
            packageBytes = packageBytes,
            previewPng = png(panel),
            sourceSha256 = document.sourceSha256,
            packageSha256 = packageBytes.sha256(),
            fileCount = files.size,
            warnings = compatibility.warnings +
                "Live-digital runtime is offline-audited but still requires staged watch verification",
            scaleMode = resolvedMode,
            experimentalNativeRuntime = true,
            runtimeLabel = "live digital runtime",
        )
    }

    private fun digitalSlots(document: Clock2Document, mode: ScaleMode): List<DigitalSlot> {
        val result = mutableListOf<DigitalSlot>()
        val xScale = xScale(document, mode)
        val yScale = yScale(document, mode)
        val originX = (VIEWPORT_WIDTH - document.canvasWidth * xScale) / 2f
        val originY = (VIEWPORT_HEIGHT - document.canvasHeight * yScale) / 2f
        val centerX = originX + document.canvasWidth * xScale / 2f
        val centerY = originY + document.canvasHeight * yScale / 2f

        fun add(index: Int, layer: Clock2Layer, maxChars: Int, xOffsetChars: Float = 0f) {
            val dimensions = digitDimensions(layer, document, mode)
            val layerX = centerX + layer.x * xScale
            val layerY = centerY + layer.y * yScale
            val x = (layerX - maxChars * dimensions.first / 2f +
                xOffsetChars * dimensions.first).roundToInt()
            val y = (layerY - dimensions.second / 2f).roundToInt()
            result += DigitalSlot(index, layer, maxChars, x.coerceIn(0, 600), y.coerceIn(0, 600))
        }

        val mainTime = document.activeLayers.firstOrNull {
            it.type == "time" && normalizedTimePattern(it) in setOf("HH:mm", "H:mm")
        }
        if (mainTime != null) {
            add(0, mainTime, 2, -1.5f)
            add(1, mainTime, 2, 1.5f)
        } else {
            document.activeLayers.firstOrNull {
                it.type == "time" && normalizedTimePattern(it) in setOf("H", "HH")
            }?.let { add(0, it, 2) }
            document.activeLayers.firstOrNull {
                it.type == "time" && normalizedTimePattern(it) in setOf("MM", "mm")
            }?.let { add(1, it, 2) }
        }
        document.activeLayers.firstOrNull {
            it.type == "time" && normalizedTimePattern(it) == "ss"
        }?.let { add(2, it, 2) }
        document.activeLayers.firstOrNull {
            it.type == "date" && it.dateFormat in setOf("D", "DD", "DAuto", "DDAuto")
        }?.let { add(3, it, 2) }

        val dataSlots = mapOf(
            "battery" to (4 to 3),
            "stepCount" to (5 to 4),
            "activeEnergyBurned" to (6 to 3),
            "heartRate" to (7 to 2),
            "distanceWalkingRunning" to (8 to 2),
        )
        document.activeLayers.filter { it.type == "dataLabel" }.forEach { layer ->
            dataSlots[layer.dataLabelKind]?.let { (index, maxChars) ->
                add(index, layer, maxChars)
            }
        }
        return result.distinctBy { it.index }
    }

    private fun digitalSequenceSlots(
        document: Clock2Document,
        mode: ScaleMode,
    ): List<DigitalSequenceSlot> {
        val xScale = xScale(document, mode)
        val yScale = yScale(document, mode)
        val originX = (VIEWPORT_WIDTH - document.canvasWidth * xScale) / 2f
        val originY = (VIEWPORT_HEIGHT - document.canvasHeight * yScale) / 2f
        val centerX = originX + document.canvasWidth * xScale / 2f
        val centerY = originY + document.canvasHeight * yScale / 2f
        val result = mutableListOf<DigitalSequenceSlot>()

        fun add(index: Int, layer: Clock2Layer) {
            val (width, height) = sequenceFrameDimensions(layer, document, mode)
            val x = (centerX + layer.x * xScale - width / 2f).roundToInt()
            val y = (centerY + layer.y * yScale - height / 2f).roundToInt()
            result += DigitalSequenceSlot(
                index, layer, x.coerceIn(0, 600), y.coerceIn(0, 600),
            )
        }

        document.activeLayers.firstOrNull {
            it.type == "date" && it.dateFormat in setOf("M", "MM", "ML", "MMM", "MMMM")
        }?.let { add(0, it) }
        document.activeLayers.firstOrNull {
            it.type == "date" && it.dateFormat in setOf("DA", "DL")
        }?.let { add(1, it) }
        document.activeLayers.firstOrNull {
            it.type == "dataBar" && it.dataBarFormat == "battery" &&
                it.dataBarStyle == "dashed"
        }?.let { add(2, it) }
        return result
    }

    private fun digitalSequenceFrames(
        slot: DigitalSequenceSlot,
        document: Clock2Document,
        mode: ScaleMode,
    ): List<Bitmap> = when (slot.index) {
        0 -> {
            val values = when (slot.layer.dateFormat) {
                "M" -> (1..12).map(Int::toString)
                "MM" -> (1..12).map { "%02d".format(Locale.US, it) }
                "MMM" -> listOf(
                    "Jan", "Feb", "Mar", "Apr", "May", "Jun",
                    "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
                )
                else -> listOf(
                    "January", "February", "March", "April", "May", "June",
                    "July", "August", "September", "October", "November", "December",
                )
            }
            values.map { textSequenceFrame(slot.layer, it, document, mode) }
        }
        1 -> {
            val values = if (slot.layer.dateFormat == "DA") {
                listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
            } else {
                listOf(
                    "Sunday", "Monday", "Tuesday", "Wednesday",
                    "Thursday", "Friday", "Saturday",
                )
            }
            values.map { textSequenceFrame(slot.layer, it, document, mode) }
        }
        else -> (0..100 step 10).map { level ->
            barSequenceFrame(slot.layer, level, document, mode)
        }
    }

    private fun textSequenceFrame(
        layer: Clock2Layer,
        text: String,
        document: Clock2Document,
        mode: ScaleMode,
    ): Bitmap {
        val xScale = xScale(document, mode)
        val yScale = yScale(document, mode)
        val width = max(1, (layer.width * xScale).roundToInt())
        val height = max(1, (layer.height * yScale).roundToInt())
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
            val x = when (layer.alignment) {
                "left" -> 0f
                "right" -> width.toFloat()
                else -> width / 2f
            }
            drawTextLayer(Canvas(bitmap), text, layer, x, height / 2f, yScale)
        }
    }

    private fun barSequenceFrame(
        layer: Clock2Layer,
        battery: Int,
        document: Clock2Document,
        mode: ScaleMode,
    ): Bitmap {
        val xScale = xScale(document, mode)
        val yScale = yScale(document, mode)
        val (width, height) = sequenceFrameDimensions(layer, document, mode)
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
            drawDataBar(
                Canvas(bitmap), layer, battery, width / 2f, height / 2f, xScale, yScale,
            )
        }
    }

    private fun sequenceFrameDimensions(
        layer: Clock2Layer,
        document: Clock2Document,
        mode: ScaleMode,
    ): Pair<Int, Int> {
        val width = layer.width * xScale(document, mode)
        val height = layer.height * yScale(document, mode)
        val radians = Math.toRadians(layer.rotation.toDouble())
        val cos = kotlin.math.abs(kotlin.math.cos(radians)).toFloat()
        val sin = kotlin.math.abs(kotlin.math.sin(radians)).toFloat()
        return max(1, (width * cos + height * sin).roundToInt()) to
            max(1, (width * sin + height * cos).roundToInt())
    }

    private fun normalizedTimePattern(layer: Clock2Layer): String =
        when (layer.timeFormat) {
            "Custom" -> layer.timeCustomFormat
            "AMPM" -> "h:mm"
            "24Hour" -> "HH:mm"
            else -> layer.timeFormat
        }

    private fun digitDimensions(
        layer: Clock2Layer,
        document: Clock2Document,
        mode: ScaleMode,
    ): Pair<Int, Int> {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = max(1f, layer.fontSize * yScale(document, mode))
            typeface = layerTypeface(layer)
        }
        val width = (('0'..'9').maxOf { paint.measureText(it.toString()) } + 2f)
            .roundToInt().coerceAtLeast(1)
        val metrics = paint.fontMetrics
        val height = (metrics.descent - metrics.ascent + 2f).roundToInt().coerceAtLeast(1)
        return width to height
    }

    private fun digitAtlas(
        layer: Clock2Layer,
        document: Clock2Document,
        mode: ScaleMode,
    ): List<Bitmap> {
        val (width, height) = digitDimensions(layer, document, mode)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = parseColor(layer.color)
            textSize = max(1f, layer.fontSize * yScale(document, mode))
            typeface = layerTypeface(layer)
            textAlign = Paint.Align.CENTER
            alpha = (layer.alpha * 255).roundToInt().coerceIn(0, 255)
        }
        val baseline = height / 2f - (paint.ascent() + paint.descent()) / 2f
        return (0..9).map { digit ->
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
                drawPaintedText(
                    Canvas(bitmap), digit.toString(), width / 2f, baseline, paint, layer,
                )
            }
        }
    }

    private fun drawDigitalSeparators(
        bitmap: Bitmap,
        document: Clock2Document,
        mode: ScaleMode,
    ) {
        val layer = document.activeLayers.firstOrNull {
            it.type == "time" && normalizedTimePattern(it) in setOf("HH:mm", "H:mm")
        } ?: return
        val xScale = xScale(document, mode)
        val yScale = yScale(document, mode)
        val centerX = (VIEWPORT_WIDTH - document.canvasWidth * xScale) / 2f +
            document.canvasWidth * xScale / 2f
        val centerY = (VIEWPORT_HEIGHT - document.canvasHeight * yScale) / 2f +
            document.canvasHeight * yScale / 2f
        drawTextLayer(
            Canvas(bitmap), ":", layer,
            centerX + layer.x * xScale, centerY + layer.y * yScale, yScale,
        )
    }

    private fun drawDigitalDataAffixes(
        bitmap: Bitmap,
        document: Clock2Document,
        mode: ScaleMode,
    ) {
        val xScale = xScale(document, mode)
        val yScale = yScale(document, mode)
        val centerX = (VIEWPORT_WIDTH - document.canvasWidth * xScale) / 2f +
            document.canvasWidth * xScale / 2f
        val centerY = (VIEWPORT_HEIGHT - document.canvasHeight * yScale) / 2f +
            document.canvasHeight * yScale / 2f
        val canvas = Canvas(bitmap)
        document.activeLayers.filter { it.type == "dataLabel" }.forEach { layer ->
            val digitWidth = digitDimensions(layer, document, mode).first.toFloat()
            val x = centerX + layer.x * xScale
            val y = centerY + layer.y * yScale
            when (layer.dataLabelKind) {
                "battery" -> drawTextLayer(canvas, "%", layer, x + digitWidth * 1.8f, y, yScale)
                "stepCount" -> drawTextLayer(canvas, ",", layer, x - digitWidth, y, yScale)
                "heartRate" -> drawTextLayer(canvas, " bpm", layer, x + digitWidth * 1.8f, y, yScale)
                "distanceWalkingRunning" -> {
                    drawTextLayer(canvas, ".", layer, x, y, yScale)
                    drawTextLayer(canvas, "mi", layer, x + digitWidth * 1.75f, y, yScale)
                }
            }
        }
    }

    private fun renderStatic(
        document: Clock2Document,
        instant: ZonedDateTime,
        battery: Int,
        scaleMode: ScaleMode,
        excludedLayerIndices: Set<Int> = emptySet(),
    ): Bitmap {
        val output = Bitmap.createBitmap(
            VIEWPORT_WIDTH, VIEWPORT_HEIGHT, Bitmap.Config.ARGB_8888,
        )
        val canvas = Canvas(output)
        canvas.drawColor(Color.BLACK)
        val resolved = ScaleModeResolver.resolve(scaleMode, document.canvasWidth, document.canvasHeight)
        val xScale = xScale(document, resolved)
        val yScale = yScale(document, resolved)
        val scaledCanvasW = document.canvasWidth * xScale
        val scaledCanvasH = document.canvasHeight * yScale
        val originX = (VIEWPORT_WIDTH - scaledCanvasW) / 2f
        val originY = (VIEWPORT_HEIGHT - scaledCanvasH) / 2f
        val centerX = originX + scaledCanvasW / 2f
        val centerY = originY + scaledCanvasH / 2f
        // Capture all asset keys before BitmapFactory sees any buffers. Clock2
        // uses the filename as its deduplication/reference identity.
        val renderedLayers = document.activeLayers.filter { it.index !in excludedLayerIndices }
        val cacheKeys = renderedLayers
            .filter(::isRasterLayer)
            .filterNot(::isMainHand)
            .associate {
                it.index to layerCacheKey(document, it, instant, scaleMode)
            }
        val decodedImages = mutableMapOf<String, Bitmap>()
        try {
            renderedLayers.filterNot(::isMainHand).forEach { layer ->
                val x = centerX + layer.x * xScale
                val y = centerY + layer.y * yScale
                when (layer.type) {
                    "date" -> {
                    val text = formatDate(layer, instant)
                    drawTextLayer(canvas, text, layer, x, y, yScale)
                    }
                    "time" -> {
                    val text = formatTime(layer, instant)
                    drawTextLayer(canvas, text, layer, x, y, yScale)
                    }
                    "text" -> {
                    drawTextLayer(canvas, layer.layerName, layer, x, y, yScale)
                    }
                    "dataLabel" -> drawTextLayer(
                        canvas, formatDataLabel(layer, battery), layer, x, y, yScale,
                    )
                    "weather" -> drawWeather(
                        canvas, layer, x, y, xScale, yScale,
                    )
                    "icon" -> drawIcon(canvas, layer, x, y, xScale, yScale)
                    "dataBar" -> drawDataBar(
                        canvas, layer, battery, x, y, xScale, yScale,
                    )
                    "shape" -> drawShape(canvas, layer, x, y, xScale, yScale, resolved)
                    "image", "imageStrip", "video", "hand" -> {
                    val key = requireNotNull(cacheKeys[layer.index])
                    val source = decodedImages.getOrPut(key) {
                        layerBitmap(
                            document,
                            layer,
                            instant,
                            scaleMode,
                        )
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
                    else -> Unit
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
        HAND_SPECS.filterKeys { it != "twentyFourhours" }.forEach { (kind, spec) ->
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

    private fun layerBitmap(
        document: Clock2Document,
        layer: Clock2Layer,
        instant: ZonedDateTime? = null,
        scaleMode: ScaleMode = ScaleMode.STRETCH,
    ): Bitmap {
        val bytes = requireNotNull(layer.imageData) {
            "Layer ${layer.index} has no embedded image"
        }
        val resolved = ScaleModeResolver.resolve(scaleMode, document.canvasWidth, document.canvasHeight)
        val targetWidth = max(1, (layer.width * xScale(document, resolved)).roundToInt())
        val targetHeight = max(1, (layer.height * yScale(document, resolved)).roundToInt())
        if (layer.type == "video") {
            val source = videoFrame(bytes, layer.index)
            val scaled = placeInFrame(source, targetWidth, targetHeight, layer.contentMode)
            source.recycle()
            return scaled
        }
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
        var source = BitmapFactory.decodeByteArray(
            decodeBytes, 0, decodeBytes.size, options,
        )
            ?: error("Layer ${layer.index} image could not be decoded")
        if (layer.type == "imageStrip") {
            val frame = imageStripFrame(
                source,
                layer,
                requireNotNull(instant) { "Image strips require an installation time" },
            )
            source.recycle()
            source = frame
        }
        // This experiment maps the *complete* Clock2 composition onto the
        // full panel. Scaling only the background left dials and overlays in
        // the old coordinate system, which looked zoomed/misaligned. Applying
        // the same two-axis transform to every raster layer keeps their
        // relative geometry intact and avoids hidden contain/crop padding.
        val scaled = if (scaleMode == ScaleMode.STRETCH || scaleMode == ScaleMode.STRETCH_H) {
            Bitmap.createScaledBitmap(source, targetWidth, targetHeight, true)
        } else {
            placeInFrame(source, targetWidth, targetHeight, if (scaleMode == ScaleMode.COVER) "fill" else layer.contentMode)
        }
        source.recycle()
        return scaled
    }

    private fun layerCacheKey(
        document: Clock2Document,
        layer: Clock2Layer,
        instant: ZonedDateTime,
        scaleMode: ScaleMode,
    ): String {
        val resolved = ScaleModeResolver.resolve(scaleMode, document.canvasWidth, document.canvasHeight)
        val targetWidth = max(1, (layer.width * xScale(document, resolved)).roundToInt())
        val targetHeight = max(1, (layer.height * yScale(document, resolved)).roundToInt())
        val assetIdentity = layer.imageFilename.ifBlank {
            System.identityHashCode(requireNotNull(layer.imageData)).toString()
        }
        val frame = if (layer.type == "imageStrip") imageStripIndex(layer, instant) else -1
        return "$assetIdentity:$targetWidth:$targetHeight:${layer.contentMode}:$frame:" +
            scaleMode.name
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

    private fun imageStripFrame(
        source: Bitmap,
        layer: Clock2Layer,
        instant: ZonedDateTime,
    ): Bitmap {
        val count = imageStripCount(layer)
        check(count > 0) { "Layer ${layer.index} has no usable image-strip frames" }
        val index = imageStripIndex(layer, instant).coerceIn(0, count - 1)
        return if (layer.imageStripHorizontal) {
            check(source.width % count == 0) {
                "Layer ${layer.index} image strip width does not match $count frames"
            }
            val frameWidth = source.width / count
            Bitmap.createBitmap(source, index * frameWidth, 0, frameWidth, source.height)
        } else {
            check(source.height % count == 0) {
                "Layer ${layer.index} image strip height does not match $count frames"
            }
            val frameHeight = source.height / count
            Bitmap.createBitmap(source, 0, index * frameHeight, source.width, frameHeight)
        }
    }

    private fun imageStripCount(layer: Clock2Layer): Int =
        when (layer.imageStripTimeWindow.lowercase(Locale.ROOT)) {
            "dayofmonth" -> 31
            "month", "monthofyear" -> 12
            "dayofweek", "weekday" -> 7
            "hourofday", "hour" -> 24
            "minuteofhour", "minute", "secondofminute", "second" -> 60
            else -> layer.imageStripFrames
        }

    private fun imageStripIndex(layer: Clock2Layer, instant: ZonedDateTime): Int =
        when (layer.imageStripTimeWindow.lowercase(Locale.ROOT)) {
            "dayofmonth" -> instant.dayOfMonth - 1
            "month", "monthofyear" -> instant.monthValue - 1
            "dayofweek", "weekday" -> instant.dayOfWeek.value % 7
            "hourofday", "hour" -> instant.hour
            "minuteofhour", "minute" -> instant.minute
            "secondofminute", "second" -> instant.second
            else -> 0
        }

    private fun videoFrame(bytes: ByteArray, layerIndex: Int): Bitmap {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(ByteArrayMediaSource(bytes))
            retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: error("Layer $layerIndex video has no decodable frame")
        } catch (error: Exception) {
            throw IllegalArgumentException("Layer $layerIndex video could not be decoded", error)
        } finally {
            retriever.release()
        }
    }

    private fun drawShape(
        canvas: Canvas,
        layer: Clock2Layer,
        x: Float,
        y: Float,
        xScale: Float,
        yScale: Float,
        scaleMode: ScaleMode = ScaleMode.STRETCH,
    ) {
        val width = max(1f, layer.width * xScale)
        val height = max(1f, layer.height * yScale)
        val rect = RectF(x - width / 2f, y - height / 2f, x + width / 2f, y + height / 2f)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = parseColor(
                if (layer.outlineWidth > 0f) layer.outlineColor else layer.color,
            )
            alpha = (layer.alpha * 255).roundToInt().coerceIn(0, 255)
            style = if (layer.outlineWidth > 0f) Paint.Style.STROKE else Paint.Style.FILL
            strokeWidth = max(1f, layer.outlineWidth * min(xScale, yScale))
        }
        when (layer.shapeType.lowercase(Locale.ROOT)) {
            "circle", "ellipse" -> canvas.drawOval(rect, paint)
            else -> {
                val radius = max(0f, layer.cornerRadius * min(xScale, yScale))
                canvas.drawRoundRect(rect, radius, radius, paint)
            }
        }
    }

    private fun formatDataLabel(layer: Clock2Layer, battery: Int): String =
        when (layer.dataLabelKind) {
            "battery" -> "${battery.coerceIn(0, 100)}%"
            "stepCount" -> String.format(Locale.US, "%,d", 1_324)
            "activeEnergyBurned" -> "300"
            "heartRate" -> "70 bpm"
            "distanceWalkingRunning" -> "1.2mi"
            else -> ""
        }

    private fun drawWeather(
        canvas: Canvas,
        layer: Clock2Layer,
        x: Float,
        y: Float,
        xScale: Float,
        yScale: Float,
    ) {
        if (layer.weatherFormat == "weatherIcon") {
            drawSunSymbol(canvas, layer, x, y, xScale, yScale)
            return
        }
        val value = when (layer.weatherFormat) {
            "city" -> "LONDON"
            "sunset" -> "16:12"
            "weatherDescription" -> "SUNNY"
            "temperature" -> "22°C"
            "chanceOfPrecip" -> "25%"
            "windSpeed" -> "22 MPH"
            else -> ""
        }
        drawTextLayer(canvas, value, layer, x, y, yScale)
    }

    private fun drawIcon(
        canvas: Canvas,
        layer: Clock2Layer,
        x: Float,
        y: Float,
        xScale: Float,
        yScale: Float,
    ) {
        val scale = min(xScale, yScale)
        val size = max(5f, layer.fontSize * scale)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = parseColor(layer.color)
            alpha = (layer.alpha * 255).roundToInt().coerceIn(0, 255)
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            strokeWidth = max(1.5f, layer.iconThickness * scale / 2.5f)
        }
        canvas.save()
        canvas.rotate(-layer.rotation, x, y)
        when (layer.iconType) {
            "umbrella_fill" -> {
                val path = Path().apply {
                    moveTo(x - size * .48f, y)
                    quadTo(x, y - size * .62f, x + size * .48f, y)
                    close()
                }
                paint.style = Paint.Style.FILL
                canvas.drawPath(path, paint)
                paint.style = Paint.Style.STROKE
                canvas.drawLine(x, y, x, y + size * .38f, paint)
                canvas.drawArc(
                    RectF(x - size * .12f, y + size * .24f, x + size * .12f, y + size * .48f),
                    0f, 105f, false, paint,
                )
            }
            "sunset" -> {
                canvas.drawLine(x - size * .5f, y + size * .2f, x + size * .5f, y + size * .2f, paint)
                canvas.drawArc(
                    RectF(x - size * .26f, y - size * .08f, x + size * .26f, y + size * .42f),
                    180f, 180f, false, paint,
                )
                canvas.drawLine(x, y - size * .46f, x, y - size * .3f, paint)
                canvas.drawLine(x - size * .36f, y - size * .28f, x - size * .25f, y - size * .17f, paint)
                canvas.drawLine(x + size * .36f, y - size * .28f, x + size * .25f, y - size * .17f, paint)
                canvas.drawLine(x - size * .38f, y + size * .38f, x + size * .38f, y + size * .38f, paint)
            }
            "tornado" -> {
                repeat(4) { row ->
                    val fraction = row / 3f
                    val half = size * (.48f - fraction * .34f)
                    val yy = y - size * .34f + row * size * .22f
                    canvas.drawLine(x - half, yy, x + half, yy, paint)
                }
                canvas.drawLine(x - size * .08f, y + size * .34f, x + size * .03f, y + size * .48f, paint)
            }
        }
        canvas.restore()
    }

    private fun drawSunSymbol(
        canvas: Canvas,
        layer: Clock2Layer,
        x: Float,
        y: Float,
        xScale: Float,
        yScale: Float,
    ) {
        val scale = min(xScale, yScale)
        val diameter = max(8f, (layer.iconSize.takeIf { it > 0f } ?: layer.fontSize) * scale)
        val radius = diameter * .25f
        val rayStart = diameter * .37f
        val rayEnd = diameter * .49f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = parseColor(layer.color)
            alpha = (layer.alpha * 255).roundToInt().coerceIn(0, 255)
            strokeWidth = max(2f, diameter * .08f)
            strokeCap = Paint.Cap.ROUND
        }
        canvas.drawCircle(x, y, radius, paint)
        repeat(8) { index ->
            val angle = Math.toRadians(index * 45.0)
            canvas.drawLine(
                x + (kotlin.math.cos(angle) * rayStart).toFloat(),
                y + (kotlin.math.sin(angle) * rayStart).toFloat(),
                x + (kotlin.math.cos(angle) * rayEnd).toFloat(),
                y + (kotlin.math.sin(angle) * rayEnd).toFloat(),
                paint,
            )
        }
    }

    private fun drawDataBar(
        canvas: Canvas,
        layer: Clock2Layer,
        battery: Int,
        x: Float,
        y: Float,
        xScale: Float,
        yScale: Float,
    ) {
        val width = max(1f, layer.width * xScale)
        val height = max(1f, layer.height * yScale)
        val background = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = parseColor(layer.dataBarBackgroundColor)
            alpha = (layer.alpha * 255).roundToInt().coerceIn(0, 255)
            style = Paint.Style.FILL
        }
        val foreground = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = parseColor(layer.dataBarStartColor)
            alpha = (layer.alpha * 255).roundToInt().coerceIn(0, 255)
            style = Paint.Style.FILL
        }
        canvas.save()
        canvas.rotate(-layer.rotation, x, y)
        val bounds = RectF(x - width / 2f, y - height / 2f, x + width / 2f, y + height / 2f)
        canvas.drawRect(bounds, background)
        val segmentCount = 10
        val gap = max(1f, (layer.dataBarDashPadding + 1f) * min(xScale, yScale))
        val segmentHeight = (height - gap * (segmentCount - 1)) / segmentCount
        val lit = battery.coerceIn(0, 100) / 10f
        repeat(segmentCount) { index ->
            val amount = (lit - index).coerceIn(0f, 1f)
            if (amount <= 0f) return@repeat
            val bottom = bounds.bottom - index * (segmentHeight + gap)
            canvas.drawRect(
                bounds.left,
                bottom - segmentHeight * amount,
                bounds.right,
                bottom,
                foreground,
            )
        }
        canvas.restore()
    }

    private fun formatDate(layer: Clock2Layer, instant: ZonedDateTime): String {
        if (layer.dateCustomFormat.isNotBlank()) {
            runCatching {
                return instant.format(DateTimeFormatter.ofPattern(layer.dateCustomFormat))
            }
        }
        val pattern = when (layer.dateFormat) {
            "D", "DAuto" -> "d"
            "DD", "DDAuto" -> "dd"
            "DA" -> "EEE"
            "DL" -> "EEEE"
            "DADD" -> "EEE dd"
            "M" -> "M"
            "MM" -> "MM"
            "ML", "MMMM" -> "MMMM"
            "MMM" -> "MMM"
            else -> "d"
        }
        return instant.format(DateTimeFormatter.ofPattern(pattern))
    }

    private fun formatTime(layer: Clock2Layer, instant: ZonedDateTime): String {
        if (layer.timeFormat == "Custom" && layer.timeCustomFormat.isNotBlank()) {
            return runCatching {
                instant.format(DateTimeFormatter.ofPattern(layer.timeCustomFormat))
            }.getOrDefault("--:--")
        }
        return when (layer.timeFormat) {
            "AMPM" -> instant.format(DateTimeFormatter.ofPattern("h:mm a"))
            "24Hour" -> instant.format(DateTimeFormatter.ofPattern("HH:mm"))
            "H", "HH" -> instant.format(DateTimeFormatter.ofPattern(layer.timeFormat))
            "MM", "mm" -> instant.format(DateTimeFormatter.ofPattern("mm"))
            "ss" -> instant.format(DateTimeFormatter.ofPattern("ss"))
            else -> instant.format(DateTimeFormatter.ofPattern("HH:mm"))
        }
    }

    private fun drawTextLayer(
        canvas: Canvas,
        text: String,
        layer: Clock2Layer,
        x: Float,
        y: Float,
        yScale: Float,
    ) {
        if (text.isBlank()) return
        val renderedText = when (layer.casing.lowercase(Locale.ROOT)) {
            "uppercase" -> text.uppercase(Locale.getDefault())
            "lowercase" -> text.lowercase(Locale.getDefault())
            else -> text
        }
        val align = when (layer.alignment) {
            "left" -> Paint.Align.LEFT
            "right" -> Paint.Align.RIGHT
            else -> Paint.Align.CENTER
        }
        val typeface = layerTypeface(layer)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = parseColor(layer.color)
            textSize = max(1f, layer.fontSize * yScale)
            this.typeface = typeface
            textAlign = align
            alpha = (layer.alpha * 255).roundToInt().coerceIn(0, 255)
        }
        val baseline = y - (paint.ascent() + paint.descent()) / 2f
        drawPaintedText(canvas, renderedText, x, baseline, paint, layer)
    }

    private fun drawPaintedText(
        canvas: Canvas,
        text: String,
        x: Float,
        baseline: Float,
        paint: Paint,
        layer: Clock2Layer,
    ) {
        if (!layer.textEffect.equals("interlaced", ignoreCase = true)) {
            canvas.drawText(text, x, baseline, paint)
            return
        }
        val metrics = paint.fontMetrics
        val top = baseline + metrics.ascent
        val bottom = baseline + metrics.descent
        val stripe = max(1f, paint.textSize / 28f)
        val period = stripe * 2f
        var lineTop = top
        while (lineTop < bottom) {
            canvas.save()
            canvas.clipRect(0f, lineTop, canvas.width.toFloat(), min(bottom, lineTop + stripe))
            canvas.drawText(text, x, baseline, paint)
            canvas.restore()
            lineTop += period
        }
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
        HAND_SPECS.filterKeys { it != "twentyFourhours" }.values.forEach { spec ->
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

    private fun xScale(document: Clock2Document, mode: ScaleMode = ScaleMode.STRETCH): Float =
        when (mode) {
            ScaleMode.STRETCH, ScaleMode.STRETCH_H -> VIEWPORT_WIDTH / document.canvasWidth
            ScaleMode.COVER -> maxOf(
                VIEWPORT_WIDTH / document.canvasWidth,
                VIEWPORT_HEIGHT / document.canvasHeight,
            )
            ScaleMode.CONTAIN -> minOf(
                VIEWPORT_WIDTH / document.canvasWidth,
                VIEWPORT_HEIGHT / document.canvasHeight,
            )
            ScaleMode.AUTO -> VIEWPORT_WIDTH / document.canvasWidth
        }

    private fun yScale(document: Clock2Document, mode: ScaleMode = ScaleMode.STRETCH): Float =
        when (mode) {
            ScaleMode.STRETCH -> VIEWPORT_HEIGHT / document.canvasHeight
            ScaleMode.STRETCH_H -> minOf(
                VIEWPORT_WIDTH / document.canvasWidth,
                VIEWPORT_HEIGHT / document.canvasHeight,
            )
            ScaleMode.COVER -> maxOf(
                VIEWPORT_WIDTH / document.canvasWidth,
                VIEWPORT_HEIGHT / document.canvasHeight,
            )
            ScaleMode.CONTAIN -> minOf(
                VIEWPORT_WIDTH / document.canvasWidth,
                VIEWPORT_HEIGHT / document.canvasHeight,
            )
            ScaleMode.AUTO -> VIEWPORT_HEIGHT / document.canvasHeight
        }

    private fun isRasterLayer(layer: Clock2Layer): Boolean =
        layer.type in setOf("image", "imageStrip", "video", "hand")

    private fun isMainHand(layer: Clock2Layer): Boolean =
        layer.type == "hand" &&
            layer.kind in HAND_SPECS &&
            (layer.x * 1000f).toInt() == 0 &&
            (layer.y * 1000f).toInt() == 0

    private fun handAngle(kind: String, time: ZonedDateTime, battery: Int): Float =
        when (kind) {
            "twelveHours" -> (time.hour % 12) * 30f + time.minute * 0.5f +
                time.second / 120f
            "minute" -> time.minute * 6f + time.second * 0.1f
            "seconds" -> time.second * 6f + time.nano / 1_000_000_000f * 6f
            "twentyFourhours" -> (time.hour + time.minute / 60f) * 15f
            "battery" -> battery.coerceIn(0, 100) * 3.6f
            else -> 0f
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

private class ByteArrayMediaSource(private val bytes: ByteArray) : MediaDataSource() {
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (position >= bytes.size) return -1
        val count = minOf(size, bytes.size - position.toInt())
        bytes.copyInto(buffer, offset, position.toInt(), position.toInt() + count)
        return count
    }

    override fun getSize(): Long = bytes.size.toLong()

    override fun close() = Unit
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
