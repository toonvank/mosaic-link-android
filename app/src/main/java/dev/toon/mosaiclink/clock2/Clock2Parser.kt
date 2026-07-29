package dev.toon.mosaiclink.clock2

import com.dd.plist.NSArray
import com.dd.plist.NSData
import com.dd.plist.NSDictionary
import com.dd.plist.PropertyListParser
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.util.Base64

object Clock2Parser {
    fun parse(bytes: ByteArray, fallbackName: String): Clock2Document {
        val plist = try {
            PropertyListParser.parse(ByteArrayInputStream(bytes)) as? NSDictionary
        } catch (error: Exception) {
            throw IllegalArgumentException("This file is not a valid Apple plist", error)
        } ?: throw IllegalArgumentException("Clock2 root is not a dictionary")

        val objects = plist.objectForKey("\$objects") as? NSArray
            ?: throw IllegalArgumentException("Clock2 keyed archive has no \$objects array")
        val settings = objects.array
            .asSequence()
            .filterIsInstance<NSData>()
            .mapNotNull { data ->
                runCatching {
                    JSONObject(String(data.bytes(), Charsets.UTF_8))
                }.getOrNull()
            }
            .firstOrNull { it.has("complicationSettings") }
            ?: throw IllegalArgumentException("Could not find Clock2 face settings")

        val complications = settings.optJSONArray("complicationSettings")
            ?: throw IllegalArgumentException("Clock2 file has no complication settings")
        val rawLayers = (0 until complications.length())
            .mapNotNull { complications.optJSONObject(it) }
            .mapNotNull { it.optJSONArray("layerSettings") }
            .maxByOrNull { it.length() }
            ?: throw IllegalArgumentException("Clock2 file has no layers")

        val visibleSizes = (0 until rawLayers.length())
            .mapNotNull { rawLayers.optJSONObject(it) }
            .filter { it.optDouble("width", 0.0) > 0 && it.optDouble("height", 0.0) > 0 }
        val canvasWidth = visibleSizes.maxOfOrNull { it.optDouble("width").toFloat() }
            ?: throw IllegalArgumentException("Could not determine Clock2 canvas width")
        val canvasHeight = visibleSizes.maxOfOrNull { it.optDouble("height").toFloat() }
            ?: throw IllegalArgumentException("Could not determine Clock2 canvas height")

        val assets = mutableMapOf<String, ByteArray>()
        val decoded = ArrayList<ByteArray?>(rawLayers.length())
        for (index in 0 until rawLayers.length()) {
            val raw = rawLayers.optJSONObject(index)
                ?: throw IllegalArgumentException("Layer $index is not an object")
            val image = raw.optJSONObject("imageLayerSetting")
            val filename = image?.optString("filename").orEmpty()
            val encoded = image?.optString("imageData").orEmpty()
            val data = if (encoded.isBlank()) {
                null
            } else {
                try {
                    Base64.getDecoder().decode(encoded)
                } catch (error: IllegalArgumentException) {
                    throw IllegalArgumentException("Layer $index contains invalid image data")
                }
            }
            if (filename.isNotBlank() && data != null) assets[filename] = data
            decoded += data
        }

        val layers = (0 until rawLayers.length()).map { index ->
            val raw = rawLayers.getJSONObject(index)
            val image = raw.optJSONObject("imageLayerSetting")
            val hand = raw.optJSONObject("handLayerSetting")
            val date = raw.optJSONObject("dateLayerSetting")
            val text = raw.optJSONObject("textLayerSetting")
            val filename = image?.optString("filename").orEmpty()
            Clock2Layer(
                index = index,
                type = raw.optString("type", "unknown"),
                kind = hand?.optString("kind").orEmpty(),
                x = raw.optDouble("xPos", 0.0).toFloat(),
                y = raw.optDouble("yPos", 0.0).toFloat(),
                width = raw.optDouble("width", canvasWidth.toDouble()).toFloat(),
                height = raw.optDouble("height", canvasHeight.toDouble()).toFloat(),
                alpha = raw.optDouble("alpha", 1.0).toFloat(),
                rotation = raw.optDouble("rotAngle", 0.0).toFloat(),
                hidden = raw.optBoolean("isHidden", false),
                // Give every layer immutable ownership. Several Android vendor
                // image decoders modify their input buffer, while Clock2 reuses
                // one filename across multiple layers.
                imageData = (decoded[index] ?: assets[filename])?.copyOf(),
                imageFilename = filename,
                color = raw.optString("colorHex", "#FFFFFFFF"),
                dateFormat = date?.optString("formatType").orEmpty(),
                fontSize = text?.optDouble("ptSize", 20.0)?.toFloat() ?: 20f,
                clockwise = hand?.optBoolean("clockwiseRotation", true) ?: true,
                contentMode = image?.optString("contentMode", "fit") ?: "fit",
            )
        }

        return Clock2Document(
            name = settings.optString("name").ifBlank { fallbackName.substringBeforeLast('.') },
            schemaVersion = settings.optInt("schemaVersion", 0),
            canvasWidth = canvasWidth,
            canvasHeight = canvasHeight,
            layers = layers,
            sourceSha256 = bytes.sha256(),
        )
    }

    fun compatibility(document: Clock2Document): Compatibility {
        val reasons = mutableListOf<String>()
        val active = document.activeLayers
        active.forEach { layer ->
            when (layer.type) {
                "image" -> if (layer.imageData == null) {
                    reasons += "Layer ${layer.index}: image asset is missing"
                }
                "hand" -> {
                    if (layer.kind !in setOf(
                            "twelveHours", "minute", "seconds",
                            "twentyFourhours", "battery",
                        )
                    ) reasons += "Layer ${layer.index}: unsupported hand ${layer.kind}"
                    if (layer.imageData == null) {
                        reasons += "Layer ${layer.index}: hand asset is missing"
                    }
                }
                "date" -> if (layer.dateFormat !in setOf("D", "DD", "DAuto", "DDAuto")) {
                    reasons += "Layer ${layer.index}: unsupported date format"
                }
                else -> reasons += "Layer ${layer.index}: unsupported type ${layer.type}"
            }
        }

        val hands = active.filter { it.type == "hand" }
        val grouped = hands.groupBy { Pair(round3(it.x), round3(it.y)) }
        val mainIndices = mutableSetOf<Int>()
        var analogGroups = 0
        grouped.values.forEach { group ->
            if (listOf("twelveHours", "minute", "seconds").all { kind ->
                    group.any { it.kind == kind }
                }
            ) {
                analogGroups++
                listOf("twelveHours", "minute", "seconds").forEach { kind ->
                    group.firstOrNull { it.kind == kind && it.index !in mainIndices }
                        ?.let { mainIndices += it.index }
                }
            }
        }
        val requirements = Clock2Requirements(
            backgroundImages = active.count { it.type == "image" },
            dateFormats = active.filter { it.type == "date" }.map { it.dateFormat },
            analogGroups = analogGroups,
            independentHands = hands
                .filter { it.index !in mainIndices }
                .map { it.kind }
                .sorted(),
            handCounts = hands.groupingBy { it.kind }.eachCount().toSortedMap(),
        )
        if (requirements.backgroundImages < 1) {
            reasons += "A Clock2 background image is required"
        }
        val centralHands = hands
            .filter { round3(it.x) == 0 && round3(it.y) == 0 }
            .groupingBy { it.kind }
            .eachCount()
        listOf("twelveHours", "minute", "seconds").forEach { kind ->
            if (centralHands[kind] != 1) {
                reasons += "Exactly one central $kind hand is required"
            }
        }
        return Compatibility(reasons.isEmpty(), reasons, requirements)
    }

    private fun round3(value: Float): Int = (value * 1000f).toInt()
}

internal fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256")
    .digest(this)
    .joinToString("") { "%02x".format(it) }
