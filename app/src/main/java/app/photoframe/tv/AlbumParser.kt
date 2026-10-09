package app.photoframe.tv

import org.json.JSONArray
import org.json.JSONTokener

/**
 * Reads the public web page of a Google Photos shared album.
 *
 * The page embeds its data in `AF_initDataCallback({key: 'ds:N', data: [...]})` script blocks.
 * Each photo is an array shaped like
 * `["AF1Qip…", ["https://lh3.googleusercontent.com/…", width, height, …], takenAtMillis, …]`.
 * This is not an official API, so the parser looks for that shape anywhere in the data rather than
 * relying on fixed positions, and falls back to a plain regex if the JSON route finds nothing.
 */
object AlbumParser {

    data class Page(val title: String?, val photos: List<Photo>, val nextPageToken: String?)

    private val linkRegex =
        Regex("""https://(photos\.app\.goo\.gl/[A-Za-z0-9_-]+|photos\.google\.com/(u/\d+/)?share/[^\s"'<>]+)""")
    private val titleRegex = Regex("""<title[^>]*>([^<]*)</title>""", RegexOption.IGNORE_CASE)
    private val fallbackItemRegex =
        Regex("""\["(AF1Qip[^"]+)"\s*,\s*\["(https://[^"]*googleusercontent\.com/[^"]+)"\s*,\s*(\d+)\s*,\s*(\d+)""")

    /** Videos carry their playback metadata under this key; their image URL is only a poster frame. */
    private const val VIDEO_MARKER = "\"76647426\""
    private const val MAX_DEPTH = 6

    /** Finds an album link in pasted text, e.g. "Check out this album: https://photos.app.goo.gl/…". */
    fun extractLink(text: String): String? = linkRegex.find(text)?.value?.trimEnd('.', ',', ')', ']')

    fun parseAlbumHtml(html: String): Page {
        val title = titleRegex.find(html)?.groupValues?.get(1)?.let(::cleanTitle)

        var best: Found? = null
        val marker = "AF_initDataCallback("
        var start = html.indexOf(marker)
        while (start >= 0) {
            val next = html.indexOf(marker, start + marker.length)
            val block = if (next >= 0) html.substring(start, next) else html.substring(start)
            val dataAt = block.indexOf("data:")
            if (dataAt >= 0) {
                val value = try {
                    JSONTokener(block.substring(dataAt + 5)).nextValue()
                } catch (e: Exception) {
                    null
                }
                val found = findItems(value)
                if (found != null && found.score > (best?.score ?: 0)) best = found
            }
            start = next
        }

        best?.let { return Page(title, toPhotos(it.items), it.nextPageToken()) }
        return Page(title, fallbackParse(html), null)
    }

    /** Parses a `batchexecute` response, which is how Google pages through albums of more than ~300 items. */
    fun parseBatchResponse(body: String): Page {
        for (line in body.lineSequence()) {
            val trimmed = line.trim()
            if (!trimmed.startsWith("[")) continue
            val outer = try {
                JSONTokener(trimmed).nextValue() as? JSONArray
            } catch (e: Exception) {
                null
            } ?: continue
            for (i in 0 until outer.length()) {
                val entry = outer.opt(i) as? JSONArray ?: continue
                if (entry.opt(0) != "wrb.fr") continue
                val payload = entry.opt(2) as? String ?: continue
                val found = try {
                    findItems(JSONTokener(payload).nextValue())
                } catch (e: Exception) {
                    null
                } ?: continue
                return Page(null, toPhotos(found.items), found.nextPageToken())
            }
        }
        return Page(null, emptyList(), null)
    }

    private class Found(val items: JSONArray, val parent: JSONArray?, val index: Int, val score: Int) {
        /** The paging token sits right after the item list: `[albumInfo, [items…], "token", …]`. */
        fun nextPageToken(): String? {
            val token = parent?.opt(index + 1) as? String ?: return null
            return token.takeIf { it.isNotBlank() && !it.startsWith("AF1Qip") && !it.startsWith("http") }
        }
    }

    /** Returns the array holding the most photo items, wherever it is nested. */
    private fun findItems(root: Any?): Found? {
        if (root !is JSONArray) return null
        var best: Found? = null
        fun walk(array: JSONArray, parent: JSONArray?, index: Int, depth: Int) {
            var score = 0
            for (i in 0 until array.length()) if (isItem(array.opt(i))) score++
            if (score > (best?.score ?: 0)) best = Found(array, parent, index, score)
            if (depth >= MAX_DEPTH) return
            for (i in 0 until array.length()) {
                val child = array.opt(i)
                if (child is JSONArray && !isItem(child)) walk(child, array, i, depth + 1)
            }
        }
        walk(root, null, -1, 0)
        return best
    }

    private fun isItem(value: Any?): Boolean {
        if (value !is JSONArray || value.length() < 2) return false
        val id = value.opt(0) as? String ?: return false
        if (!id.startsWith("AF1Qip")) return false
        val detail = value.opt(1) as? JSONArray ?: return false
        val url = detail.opt(0) as? String ?: return false
        return url.contains("googleusercontent.com") && detail.opt(1) is Number && detail.opt(2) is Number
    }

    private fun toPhotos(items: JSONArray): List<Photo> {
        val photos = ArrayList<Photo>()
        for (i in 0 until items.length()) {
            val item = items.opt(i) as? JSONArray ?: continue
            if (!isItem(item) || item.toString().contains(VIDEO_MARKER)) continue
            val detail = item.getJSONArray(1)
            photos += Photo(
                id = item.getString(0),
                url = baseUrl(detail.getString(0)),
                width = number(detail, 1).toInt(),
                height = number(detail, 2).toInt(),
                takenAt = number(item, 2),
            )
        }
        return photos
    }

    private fun fallbackParse(html: String): List<Photo> {
        val seen = HashSet<String>()
        return fallbackItemRegex.findAll(html).mapNotNull { match ->
            val (id, url, width, height) = match.destructured
            if (!seen.add(id)) return@mapNotNull null
            val cleanUrl = url.replace("\\u003d", "=").replace("\\/", "/")
            Photo(id, baseUrl(cleanUrl), width.toInt(), height.toInt(), 0L)
        }.toList()
    }

    private fun number(array: JSONArray, index: Int): Long = (array.opt(index) as? Number)?.toLong() ?: 0L

    private fun baseUrl(url: String) = url.substringBefore('=')

    private fun cleanTitle(raw: String): String? {
        val title = raw
            .replace("&amp;", "&").replace("&#39;", "'").replace("&quot;", "\"")
            .replace("&lt;", "<").replace("&gt;", ">")
            .replace(Regex("""\s*[-–]\s*Google Photos\s*$""", RegexOption.IGNORE_CASE), "")
            .trim()
        return title.takeIf { it.isNotEmpty() && !it.equals("Google Photos", ignoreCase = true) }
    }
}
