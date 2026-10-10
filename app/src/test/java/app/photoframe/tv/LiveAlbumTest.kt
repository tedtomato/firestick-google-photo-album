package app.photoframe.tv

import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Fetches a real shared album, to check that Google's page still parses.
 * Skipped unless PHOTOFRAME_TEST_ALBUM is set (see .github/workflows/check-album.yml).
 * Writes counts and status codes only, never the link, title or photo URLs, because CI logs of a
 * public repository are public.
 */
class LiveAlbumTest {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    @Test
    fun fetchesRealAlbum() {
        val link = System.getenv("PHOTOFRAME_TEST_ALBUM").orEmpty()
        assumeTrue("PHOTOFRAME_TEST_ALBUM not set", link.isNotBlank())
        val report = StringBuilder()
        fun line(text: String) {
            report.appendLine(text)
            println(text)
        }

        try {
            // 1. The raw page, to see its shape if parsing ever breaks.
            val request = Request.Builder().url(link)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("Cookie", "SOCS=CAI; CONSENT=YES+")
                .build()
            val html = client.newCall(request).execute().use { response ->
                line("page: HTTP ${response.code}, final host ${response.request.url.host}, redirects ${response.priorResponse != null}")
                response.body?.string().orEmpty()
            }
            line("page: ${html.length} chars, ${count(html, "AF_initDataCallback(")} data blocks, " +
                "${count(html, "\"AF1Qip")} AF1Qip strings, ${count(html, "googleusercontent.com")} image URLs")
            Regex("""AF_initDataCallback\(\{key: '([^']+)'""").findAll(html).forEach { match ->
                val start = match.range.first
                val end = html.indexOf("AF_initDataCallback(", start + 1).let { if (it < 0) html.length else it }
                val block = html.substring(start, end)
                line("  block ${match.groupValues[1]}: ${block.length} chars, ${count(block, "\"AF1Qip")} AF1Qip strings")
            }

            // 2. The parser on that page.
            val page = AlbumParser.parseAlbumHtml(html)
            line("parser: title found ${page.title != null}, ${page.photos.size} photos, next page token ${page.nextPageToken != null}")
            if (page.photos.isNotEmpty()) {
                val sizes = page.photos.take(5).joinToString { "${it.width}x${it.height}" }
                line("parser: first sizes $sizes, takenAt set on ${page.photos.count { it.takenAt > 0 }}")
            }

            // 3. The full fetch the app does, including paging.
            val content = AlbumFetcher(client).fetch(link)
            val videos = content.photos.filter { it.isVideo }
            line("fetcher: ${content.photos.size} items in total (${videos.size} videos), title found ${content.title != null}")

            // 4. Which video URL serves a playable file. A range request keeps the download tiny.
            videos.firstOrNull()?.let { video ->
                for (suffix in listOf("dv", "m37", "m22", "m18")) {
                    val probe = Request.Builder().url("${video.url}=$suffix").header("Range", "bytes=0-1023").build()
                    try {
                        client.newCall(probe).execute().use { response ->
                            val total = response.header("Content-Range")?.substringAfter('/') ?: response.header("Content-Length")
                            val head = response.body?.bytes() ?: ByteArray(0)
                            val mp4 = head.size >= 8 && String(head, 4, 4, Charsets.ISO_8859_1) == "ftyp"
                            line("video =$suffix: HTTP ${response.code}, ${response.header("Content-Type")}, " +
                                "total $total bytes, mp4 header $mp4, served by ${response.request.url.host}")
                        }
                    } catch (e: Exception) {
                        line("video =$suffix: ${e.javaClass.simpleName}")
                    }
                }
            }

            // 5. A photo at TV size.
            val photo = content.photos.first { !it.isVideo }
            client.newCall(Request.Builder().url(photo.sizedUrl(1920, 1080)).build()).execute().use { response ->
                val bytes = response.body?.bytes()?.size ?: 0
                line("image: HTTP ${response.code}, ${response.header("Content-Type")}, $bytes bytes")
                assertTrue("image did not load", response.isSuccessful && bytes > 1000)
            }
            assertTrue("no photos", content.photos.isNotEmpty())
        } finally {
            File("build/live-album-report.txt").writeText(report.toString())
        }
    }

    private fun count(text: String, needle: String): Int {
        var n = 0
        var i = text.indexOf(needle)
        while (i >= 0) {
            n++
            i = text.indexOf(needle, i + needle.length)
        }
        return n
    }
}
