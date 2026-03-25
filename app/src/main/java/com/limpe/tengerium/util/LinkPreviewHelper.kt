package com.limpe.tengerium.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.util.regex.Pattern

data class LinkPreview(
    val url: String,
    val title: String? = null,
    val description: String? = null,
    val imageUrl: String? = null,
    val domain: String? = null
)

object LinkPreviewHelper {
    private val URL_PATTERN = Pattern.compile(
        "(?:^|[\\W])((ht|f)tp(s?):\\/\\/|www\\.)"
                + "(([\\w\\-]+\\.){1,24}[\\w\\-]{2,24})"
                + "(?::\\d+)?(/\\S*)?",
        Pattern.CASE_INSENSITIVE
    )

    fun extractUrl(text: String): String? {
        val matcher = URL_PATTERN.matcher(text)
        return if (matcher.find()) {
            val group1 = matcher.group(1) ?: ""
            val group4 = matcher.group(4) ?: ""
            val group6 = matcher.group(6) ?: ""
            group1 + group4 + group6
        } else null
    }

    suspend fun fetchPreview(url: String): LinkPreview? = withContext(Dispatchers.IO) {
        try {
            val finalUrl = if (url.startsWith("www.")) "https://$url" else url
            val connection = URL(finalUrl).openConnection() as HttpURLConnection
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36")
            
            val html = connection.inputStream?.bufferedReader()?.use { it.readText() } ?: return@withContext null
            
            val title = getMetaTag(html, "og:title") ?: getTagContent(html, "title")
            val description = getMetaTag(html, "og:description") ?: getMetaTag(html, "description")
            val image = getMetaTag(html, "og:image")
            val domain = try { URL(finalUrl).host } catch (e: Exception) { null }

            if (title == null && description == null && image == null) return@withContext null

            LinkPreview(finalUrl, title, description, image, domain)
        } catch (e: Exception) {
            null
        }
    }

    private fun getMetaTag(html: String, property: String): String? {
        val pattern = Pattern.compile("<meta[^>]+(?:property|name)=[\"']$property[\"'][^>]+content=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE)
        val matcher = pattern.matcher(html)
        if (matcher.find()) return matcher.group(1)
        
        val patternRev = Pattern.compile("<meta[^>]+content=[\"']([^\"']+)[\"'][^>]+(?:property|name)=[\"']$property[\"']", Pattern.CASE_INSENSITIVE)
        val matcherRev = patternRev.matcher(html)
        if (matcherRev.find()) return matcherRev.group(1)
        
        return null
    }

    private fun getTagContent(html: String, tag: String): String? {
        val pattern = Pattern.compile("<$tag>(.*?)</$tag>", Pattern.CASE_INSENSITIVE or Pattern.DOTALL)
        val matcher = pattern.matcher(html)
        if (matcher.find()) return matcher.group(1)?.trim()
        return null
    }
}
