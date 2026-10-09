package app.photoframe.tv

import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

class AlbumFetchException(message: String) : IOException(message)

data class AlbumContent(val title: String?, val resolvedUrl: String, val photos: List<Photo>)

class AlbumFetcher(private val client: OkHttpClient) {

    fun fetch(link: String): AlbumContent {
        client.newCall(baseRequest(link).build()).execute().use { response ->
            val finalUrl = response.request.url
            if (finalUrl.host.startsWith("consent.")) {
                throw AlbumFetchException("Google showed a cookie-consent page instead of the album")
            }
            if (response.code == 404) throw AlbumFetchException("Album not found. Is link sharing still on?")
            if (!response.isSuccessful) throw AlbumFetchException("Google Photos returned HTTP ${response.code}")

            val first = AlbumParser.parseAlbumHtml(response.body?.string().orEmpty())
            if (first.photos.isEmpty()) throw AlbumFetchException("No photos found. Is this a shared album link?")

            val photos = LinkedHashMap<String, Photo>()
            for (photo in first.photos) photos[photo.id] = photo
            loadMorePages(finalUrl, first.nextPageToken, photos)
            return AlbumContent(first.title, finalUrl.toString(), photos.values.toList())
        }
    }

    /**
     * The album page only embeds roughly the first 300 items; Google's web app fetches the rest
     * page by page. Best effort: if this ever stops working we still have the first page.
     */
    private fun loadMorePages(albumUrl: HttpUrl, firstToken: String?, photos: MutableMap<String, Photo>) {
        val segments = albumUrl.pathSegments
        val shareIndex = segments.indexOf("share")
        if (shareIndex < 0) return
        val albumId = segments.getOrNull(shareIndex + 1) ?: return
        val key = albumUrl.queryParameter("key")

        var token = firstToken
        var pages = 0
        while (token != null && pages < MAX_EXTRA_PAGES) {
            val page = try {
                fetchPage(albumUrl, albumId, key, token)
            } catch (e: Exception) {
                null
            } ?: return
            val before = photos.size
            for (photo in page.photos) if (!photos.containsKey(photo.id)) photos[photo.id] = photo
            if (photos.size == before) return
            token = page.nextPageToken
            pages++
        }
    }

    private fun fetchPage(albumUrl: HttpUrl, albumId: String, key: String?, token: String): AlbumParser.Page? {
        val args = JSONArray().put(albumId).put(token).put(JSONObject.NULL).put(key ?: JSONObject.NULL)
        val call = JSONArray().put("snAcKc").put(args.toString()).put(JSONObject.NULL).put("generic")
        val fReq = JSONArray().put(JSONArray().put(call))
        val url = HttpUrl.Builder()
            .scheme("https")
            .host("photos.google.com")
            .addPathSegments("_/PhotosUi/data/batchexecute")
            .addQueryParameter("rpcids", "snAcKc")
            .addQueryParameter("source-path", albumUrl.encodedPath)
            .addQueryParameter("hl", "en")
            .addQueryParameter("rt", "c")
            .build()
        val request = baseRequest(url.toString())
            .post(FormBody.Builder().add("f.req", fReq.toString()).build())
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            return AlbumParser.parseBatchResponse(response.body?.string().orEmpty())
        }
    }

    private fun baseRequest(url: String) = Request.Builder()
        .url(url)
        .header("User-Agent", USER_AGENT)
        .header("Accept-Language", "en-US,en;q=0.9")
        // Pre-answers Google's EU cookie banner so we get the album, not consent.google.com.
        .header("Cookie", "SOCS=CAI; CONSENT=YES+")

    private companion object {
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
        const val MAX_EXTRA_PAGES = 40
    }
}
