package dev.toon.mosaiclink.catalog

import android.util.Log
import java.net.HttpURLConnection
import java.net.URL
import java.util.regex.Pattern

data class ChannelConfig(
    val username: String,
    val displayName: String,
)

data class ScrapedFace(
    val fileName: String,
    val fileSizeText: String,
    val previewUrl: String?,
    val messageUrl: String,
    val channelName: String,
    val messageId: Int,
    val description: String?,
)

data class ScrapeResult(
    val faces: List<ScrapedFace>,
    val lowestMessageId: Int,
    val hasMore: Boolean,
)

class ChannelScraper {

    companion object {
        private const val TAG = "ChannelScraper"
        private const val PAGE_SIZE = 20
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.6533.84 Mobile Safari/537.36"

        val CHANNELS = listOf(
            ChannelConfig("ClockologyOfficial", "Clockology Official"),
            ChannelConfig("Clockologychannel", "Clockology Fans"),
            ChannelConfig("ClockologyWatchFaces", "Clockology Watch Faces"),
            ChannelConfig("bAdstylee", "bAdGB Faces"),
        )
    }

    private val messageSplitPattern =
        Pattern.compile("(?=<div class=\"tgme_widget_message_wrap)")
    private val dataPostPattern =
        Pattern.compile("data-post=\"([^\"]+)/([^\"]+)\"")
    private val docTitlePattern =
        Pattern.compile("tgme_widget_message_document_title[^\"]*\"[^>]*>([^<]+)")
    private val docSizePattern =
        Pattern.compile("tgme_widget_message_document_extra[^>]*>([^<]+)")
    private val docLinkPattern =
        Pattern.compile("tgme_widget_message_document_wrap\"[^>]*href=\"([^\"]+)\"")
    private val photoUrlPattern =
        Pattern.compile("background-image:url\\('(https?://[^']+)'\\)")
    private val textPattern =
        Pattern.compile("tgme_widget_message_text[^>]*>(.*?)</div>", Pattern.DOTALL)
    private val tagStripPattern =
        Pattern.compile("<[^>]+>")

    fun scrapeChannel(
        channel: ChannelConfig,
        beforeMessageId: Int? = null,
    ): ScrapeResult {
        val url = if (beforeMessageId != null) {
            "https://t.me/s/${channel.username}?before=$beforeMessageId"
        } else {
            "https://t.me/s/${channel.username}"
        }

        Log.d(TAG, "Scraping $url")
        val html = fetchUrl(url)
        val faces = parseHtml(html, channel)
        val messageIds = faces.map { it.messageId }
        val lowestId = messageIds.minOrNull() ?: 0
        val hasMore = faces.size >= 5 && lowestId > 1

        return ScrapeResult(faces, lowestId, hasMore)
    }

    fun getLatestMessageId(channel: ChannelConfig): Int {
        val url = "https://t.me/s/${channel.username}"
        Log.d(TAG, "Probing latest message ID for ${channel.username}")
        val html = fetchUrl(url)
        val postIds = dataPostPattern.matcher(html).let { m ->
            val ids = mutableListOf<Int>()
            while (m.find()) {
                m.group(2)?.toIntOrNull()?.let { ids.add(it) }
            }
            ids
        }
        return postIds.maxOrNull() ?: 0
    }

    fun scrapeRandomPage(channel: ChannelConfig, latestMessageId: Int): ScrapeResult {
        if (latestMessageId <= 30) {
            return scrapeChannel(channel)
        }
        val randomBefore = (20..latestMessageId).random()
        return scrapeChannel(channel, randomBefore)
    }

    private fun fetchUrl(urlStr: String): String {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("User-Agent", USER_AGENT)
            connectTimeout = 15_000
            readTimeout = 20_000
        }
        try {
            if (conn.responseCode !in 200..299) {
                error("HTTP ${conn.responseCode} for $urlStr")
            }
            return conn.inputStream.bufferedReader()
                .use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun parseHtml(html: String, channel: ChannelConfig): List<ScrapedFace> {
        val rawMessages = messageSplitPattern.split(html)
            .filter { it.contains("data-post=") }

        data class ParsedMessage(
            val messageId: Int,
            val messageUrl: String,
            val docTitle: String?,
            val docSize: String?,
            val photoUrl: String?,
            val text: String?,
        )

        val messages = rawMessages.mapNotNull { msgHtml ->
            val postMatch = dataPostPattern.matcher(msgHtml)
            if (!postMatch.find()) return@mapNotNull null
            val channelName = postMatch.group(1)
            val msgIdStr = postMatch.group(2) ?: return@mapNotNull null
            val msgId = msgIdStr.toIntOrNull() ?: return@mapNotNull null

            val docTitle = findFirst(docTitlePattern, msgHtml)?.trim()
            val docSize = findFirst(docSizePattern, msgHtml)?.trim()
            val docLink = findFirst(docLinkPattern, msgHtml)
            val photoUrl = findFirst(photoUrlPattern, msgHtml)
            val rawText = findFirst(textPattern, msgHtml)
            val text = rawText?.let { tagStripPattern.matcher(it).replaceAll("").trim() }
                ?.takeIf { it.isNotEmpty() }

            val messageUrl = docLink ?: "https://t.me/$channelName/$msgId"

            ParsedMessage(msgId, messageUrl, docTitle, docSize, photoUrl, text)
        }

        val faces = mutableListOf<ScrapedFace>()
        for (i in messages.indices) {
            val msg = messages[i]
            val title = msg.docTitle ?: continue
            if (!title.endsWith(".clock2", ignoreCase = true)) continue

            val previewUrl = msg.photoUrl
                ?: if (i > 0) messages[i - 1].photoUrl else null
                ?: if (i < messages.size - 1) messages[i + 1].photoUrl else null
            if (previewUrl == null) continue
            val description = msg.text
                ?: if (i > 0) messages[i - 1].text else null

            faces.add(ScrapedFace(
                fileName = title,
                fileSizeText = msg.docSize ?: "",
                previewUrl = previewUrl,
                messageUrl = msg.messageUrl,
                channelName = channel.displayName,
                messageId = msg.messageId,
                description = description,
            ))
        }

        return faces
    }

    private fun findFirst(pattern: Pattern, input: String): String? {
        val m = pattern.matcher(input)
        return if (m.find()) m.group(1) else null
    }
}
