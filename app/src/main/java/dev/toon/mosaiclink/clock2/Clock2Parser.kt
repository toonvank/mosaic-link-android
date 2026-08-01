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
            val text = raw.optJSONObject("textLayerSetting")
            val filename = image?.optString("filename").orEmpty()
            val fontFilename = text?.optString("fontFilename").orEmpty()
            val encoded = if (image == null || image.isNull("imageData")) {
                ""
            } else {
                image.optString("imageData")
            }
            val data = if (encoded.isBlank()) {
                null
            } else {
                try {
                    Base64.getDecoder().decode(encoded)
                } catch (error: IllegalArgumentException) {
                    throw IllegalArgumentException("Layer $index contains invalid image data")
                }
            }
            if (data != null) {
                if (filename.isNotBlank()) assets[filename] = data
                // Clock2 commonly stores a shared font blob on a different
                // layer (APPLE DIGITAL keeps Acens and Euromode on weather
                // layers). Index that blob by the text setting's font file so
                // every referencing time/text/data layer resolves the exact
                // same typeface instead of silently falling back.
                if (fontFilename.isNotBlank()) assets[fontFilename] = data
            }
            decoded += data
        }

        val layers = (0 until rawLayers.length()).map { index ->
            val raw = rawLayers.getJSONObject(index)
            val image = raw.optJSONObject("imageLayerSetting")
            val hand = raw.optJSONObject("handLayerSetting")
            val date = raw.optJSONObject("dateLayerSetting")
            val text = raw.optJSONObject("textLayerSetting")
            val imageStrip = raw.optJSONObject("imageStripLayerSetting")
            val shape = raw.optJSONObject("shapeLayerSetting")
            val timeSetting = raw.optJSONObject("timeLayerSetting")
            val dataLabelSetting = raw.optJSONObject("dataLabelLayerSetting")
            val weatherSetting = raw.optJSONObject("weatherLayerSetting")
            val iconSetting = raw.optJSONObject("iconLayerSetting")
            val dataBarSetting = raw.optJSONObject("dataBarLayerSetting")
            val filename = image?.optString("filename").orEmpty()
            val fontFilename = text?.optString("fontFilename").orEmpty()
            val fontName = text?.optString("fontName").orEmpty()
            val fontBytes = if (fontFilename.isNotBlank()) {
                assets[fontFilename] ?: decoded[index]
            } else null
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
                imageData = (decoded[index] ?: assets[filename])?.copyOf(),
                imageFilename = filename,
                color = raw.optString("colorHex", "#FFFFFFFF"),
                dateFormat = date?.optString("formatType").orEmpty(),
                fontSize = text?.optDouble("ptSize", 20.0)?.toFloat() ?: 20f,
                clockwise = hand?.optBoolean("clockwiseRotation", true) ?: true,
                contentMode = image?.optString("contentMode", "fit") ?: "fit",
                isHeader = raw.optBoolean("isHeader", false),
                dateCustomFormat = date?.optString("customFormat").orEmpty(),
                imageStripTimeWindow = imageStrip?.optString("timeWindowType").orEmpty(),
                imageStripHorizontal = imageStrip?.optBoolean("horizInput", false) ?: false,
                imageStripFrames = imageStrip?.optInt("framesForAnimation", 0) ?: 0,
                shapeType = shape?.optString("shapeType").orEmpty(),
                cornerRadius = shape?.optDouble("cornerRadius", 0.0)?.toFloat() ?: 0f,
                outlineWidth = shape?.optDouble("outlineWidth", 0.0)?.toFloat() ?: 0f,
                outlineColor = shape?.optString("outlineColorHex", "#FFFFFFFF")
                    ?: "#FFFFFFFF",
                layerName = raw.optString("name", ""),
                timeFormat = timeSetting?.optString("formatType").orEmpty(),
                timeCustomFormat = timeSetting?.optString("customFormat").orEmpty(),
                dataLabelKind = dataLabelSetting?.optString("kind").orEmpty(),
                alignment = raw.optString("alignment", "center"),
                fontData = fontBytes?.copyOf(),
                fontName = fontName,
                casing = text?.optString("casingType", "unmodified") ?: "unmodified",
                textEffect = text?.optString("effect", "none") ?: "none",
                textEffectModifier = text?.optDouble("effectModifier", 0.0)?.toFloat() ?: 0f,
                detailLevel = text?.optString("detailLevel", "unmodified") ?: "unmodified",
                weatherFormat = weatherSetting?.optString("formatType").orEmpty(),
                iconType = iconSetting?.optString("type").orEmpty(),
                iconThickness = iconSetting?.optInt("thickness", 0) ?: 0,
                iconSize = raw.optDouble("iconSize", 0.0).toFloat(),
                dataBarFormat = dataBarSetting?.optString("format").orEmpty(),
                dataBarStyle = dataBarSetting?.optString("style").orEmpty(),
                dataBarStartColor = dataBarSetting?.optString(
                    "startColorHex", "#FFFFFFFF",
                ) ?: "#FFFFFFFF",
                dataBarEndColor = dataBarSetting?.optString(
                    "endColorHex", "#FFFFFFFF",
                ) ?: "#FFFFFFFF",
                dataBarBackgroundColor = dataBarSetting?.optString(
                    "backgroundColorHex", "#FFFFFF00",
                ) ?: "#FFFFFF00",
                dataBarDashPadding = dataBarSetting?.optDouble("dashPadding", 0.0)
                    ?.toFloat() ?: 0f,
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
        val warnings = linkedSetOf<String>()
        val active = document.activeLayers
        val liveDigital = active.any { it.type == "time" }
        active.forEach { layer ->
            if (layer.type in setOf("date", "time", "text", "dataLabel", "weather") &&
                layer.textEffect !in setOf("", "none", "interlaced")
            ) {
                if (liveDigital) {
                    reasons += "Layer ${layer.index}: text effect '${layer.textEffect}' is not supported"
                } else {
                    warnings += "Text effect '${layer.textEffect}' is flattened without animation"
                }
            }
            when (layer.type) {
                "image" -> if (layer.imageData == null) {
                    reasons += "Layer ${layer.index}: image asset is missing"
                }
                "hand" -> {
                    if (layer.imageData == null) {
                        reasons += "Layer ${layer.index}: hand asset is missing"
                    } else if (layer.kind !in setOf(
                            "twelveHours", "minute", "seconds",
                            "twentyFourhours", "battery",
                        )
                    ) {
                        warnings += "Unsupported data hands are frozen at their zero position"
                    }
                }
                "date" -> {
                    if (layer.dateFormat.isBlank() && layer.dateCustomFormat.isBlank()) {
                        reasons += "Layer ${layer.index}: date format is missing"
                    } else if (layer.dateFormat !in setOf(
                            "D", "DD", "DAuto", "DDAuto", "DA",
                            "DL", "M", "MM", "ML", "MMM", "MMMM",
                        )
                    ) {
                        if (liveDigital) {
                            reasons += "Layer ${layer.index}: live date format '${layer.dateFormat}' is not supported"
                        } else {
                            warnings += "Unknown date formats are approximated in the static background"
                        }
                    }
                    if (!liveDigital) {
                        warnings += "This date layer shows the value from installation time"
                    }
                }
                "imageStrip" -> {
                    if (layer.imageData == null) {
                        reasons += "Layer ${layer.index}: image strip asset is missing"
                    } else {
                        warnings += "Image strips show the frame from installation time"
                    }
                }
                "video" -> {
                    if (layer.imageData == null) {
                        reasons += "Layer ${layer.index}: video asset is missing"
                    } else {
                        warnings += "Video layers use a still first frame"
                    }
                }
                "shape" -> Unit
                "text" -> Unit
                "time" -> {
                    val pattern = when (layer.timeFormat) {
                        "Custom" -> layer.timeCustomFormat
                        "AMPM" -> "h:mm"
                        "24Hour" -> "HH:mm"
                        else -> layer.timeFormat
                    }
                    if (pattern !in setOf("HH:mm", "H:mm", "H", "HH", "MM", "mm", "ss")) {
                        reasons += "Layer ${layer.index}: live time format '$pattern' is not supported"
                    }
                }
                "dataLabel" -> {
                    if (!liveDigital) {
                        warnings += "Live data labels require a digital-time layer and are omitted"
                    } else if (layer.dataLabelKind !in setOf(
                            "battery", "stepCount", "activeEnergyBurned",
                            "heartRate", "distanceWalkingRunning",
                        )
                    ) {
                        reasons += "Layer ${layer.index}: live data '${layer.dataLabelKind}' has no verified HK8 source"
                    }
                }
                "weather" -> {
                    val supportedWeather = setOf(
                        "weatherIcon", "city", "sunset", "weatherDescription",
                        "temperature", "chanceOfPrecip", "windSpeed",
                    )
                    if (!liveDigital) {
                        warnings += "Live weather layers are omitted from analog packages"
                    } else if (layer.weatherFormat !in supportedWeather) {
                        reasons += "Layer ${layer.index}: live weather '${layer.weatherFormat}' is not supported"
                    } else {
                        warnings += "Weather is retained as offline face artwork and does not update"
                    }
                }
                "icon" -> {
                    val supportedIcons = setOf("tornado", "sunset", "umbrella_fill")
                    if (!liveDigital) {
                        warnings += "Symbol layers are omitted from analog packages"
                    } else if (layer.iconType !in supportedIcons) {
                        reasons += "Layer ${layer.index}: symbol '${layer.iconType}' is not supported"
                    }
                }
                "dataBar" -> {
                    if (!liveDigital) {
                        warnings += "Live data bars are omitted from analog packages"
                    } else if (layer.dataBarFormat != "battery" ||
                        layer.dataBarStyle != "dashed"
                    ) {
                        reasons += "Layer ${layer.index}: only a dashed live battery bar is supported"
                    }
                }
                "dataRing", "calendar", "chart", "ring", "button", "homeKit" -> {
                    if (liveDigital) {
                        reasons += "Layer ${layer.index}: visible ${layer.type} layers are not supported"
                    } else {
                        warnings += "Live ${layer.type} layers are omitted from analog packages"
                    }
                }
                else -> {
                    if (liveDigital) {
                        reasons += "Layer ${layer.index}: unknown visible layer type '${layer.type}'"
                    } else {
                        warnings += "Unknown layer types are omitted"
                    }
                }
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
        val digitalPatterns = active.filter { it.type == "time" }.map { layer ->
            when (layer.timeFormat) {
                "Custom" -> layer.timeCustomFormat
                "AMPM" -> "h:mm"
                "24Hour" -> "HH:mm"
                else -> layer.timeFormat
            }
        }
        if (liveDigital && digitalPatterns.none { it in setOf("HH:mm", "H:mm") } &&
            !(digitalPatterns.any { it in setOf("H", "HH") } &&
                digitalPatterns.any { it in setOf("MM", "mm") })
        ) {
            reasons += "A live digital face requires an HH:mm time layer"
        }
        val centralHands = hands
            .filter { round3(it.x) == 0 && round3(it.y) == 0 }
            .groupingBy { it.kind }
            .eachCount()
        val hasHands = hands.isNotEmpty()
        if (liveDigital && hasHands) {
            reasons += "Mixed analog hands and live digital layers are not supported by the digital runtime"
        }
        if (hasHands && !liveDigital) {
            val hourKind = when {
                centralHands["twelveHours"] != null -> "twelveHours"
                centralHands["twentyFourhours"] != null -> "twentyFourhours"
                else -> null
            }
            if (hourKind == null) {
                reasons += "A central hour hand (twelveHours or twentyFourhours) is required when hands are present"
            } else if (centralHands[hourKind] != 1) {
                reasons += "Exactly one central $hourKind hand is required"
            }
            if (centralHands["minute"] == null) {
                reasons += "A central minute hand is required when hands are present"
            } else if (centralHands["minute"] != 1) {
                reasons += "Exactly one central minute hand is required"
            }
            if ((centralHands["seconds"] ?: 0) > 1) {
                reasons += "At most one central seconds hand is supported"
            }
        }
        if (hasHands && !liveDigital && (centralHands["seconds"] ?: 0) == 0) {
            warnings += "The missing central seconds hand will be transparent"
        }
        if (liveDigital) {
            fun rejectDuplicates(label: String, count: Int) {
                if (count > 1) reasons += "Only one live $label layer is supported"
            }
            rejectDuplicates(
                "combined time",
                digitalPatterns.count { it in setOf("HH:mm", "H:mm") },
            )
            rejectDuplicates("hour", digitalPatterns.count { it in setOf("H", "HH") })
            rejectDuplicates("minute", digitalPatterns.count { it in setOf("MM", "mm") })
            rejectDuplicates("seconds", digitalPatterns.count { it == "ss" })
            if (digitalPatterns.any { it in setOf("HH:mm", "H:mm") } &&
                digitalPatterns.any { it in setOf("H", "HH", "MM", "mm") }
            ) {
                reasons += "A combined time layer cannot be mixed with separate hour/minute layers"
            }
            rejectDuplicates(
                "day",
                active.count {
                    it.type == "date" && it.dateFormat in setOf("D", "DD", "DAuto", "DDAuto")
                },
            )
            rejectDuplicates(
                "month",
                active.count {
                    it.type == "date" && it.dateFormat in setOf("M", "MM", "ML", "MMM", "MMMM")
                },
            )
            rejectDuplicates(
                "weekday",
                active.count { it.type == "date" && it.dateFormat in setOf("DA", "DL") },
            )
            active.filter { it.type == "dataLabel" }
                .groupingBy { it.dataLabelKind }
                .eachCount()
                .forEach { (kind, count) -> rejectDuplicates("$kind data", count) }
            rejectDuplicates("battery bar", active.count { it.type == "dataBar" })
        }
        return Compatibility(
            supported = reasons.isEmpty(),
            reasons = reasons,
            warnings = warnings.toList(),
            requirements = requirements,
        )
    }

    private fun round3(value: Float): Int = (value * 1000f).toInt()
}

internal fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256")
    .digest(this)
    .joinToString("") { "%02x".format(it) }
