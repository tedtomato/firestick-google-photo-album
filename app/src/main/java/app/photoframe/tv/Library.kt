package app.photoframe.tv

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

data class Album(
    val link: String,
    val title: String?,
    val resolvedUrl: String?,
    val photoCount: Int,
    val error: String?,
    val updatedAt: Long,
)

/**
 * The albums the user added and the cached photo list of each, shared by every screen.
 * Changes are announced by bumping [KEY_VERSION] in [prefs], so screens just listen to prefs.
 */
class Library private constructor(context: Context) {

    enum class AddResult { ADDED, DUPLICATE, INVALID }

    val prefs: SharedPreferences = context.getSharedPreferences("photoframe", Context.MODE_PRIVATE)
    val settings = Settings(prefs)

    private val cacheFile = File(context.filesDir, "photos.json")
    private val lock = Any()
    private val photosByAlbum = LinkedHashMap<String, List<Photo>>()
    private val fetching = HashSet<String>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val fetcher = AlbumFetcher(
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    )

    init {
        loadCache()
    }

    val lastRefresh: Long get() = prefs.getLong(KEY_LAST_REFRESH, 0)

    fun albums(): List<Album> = synchronized(lock) { readAlbums() }

    fun isFetching(link: String) = synchronized(lock) { link in fetching }

    /** All photos from all albums, de-duplicated. */
    fun photos(): List<Photo> = synchronized(lock) {
        val seen = HashSet<String>()
        photosByAlbum.values.flatten().filter { seen.add(it.id) }
    }

    fun addAlbum(text: String): AddResult {
        val link = AlbumParser.extractLink(text) ?: return AddResult.INVALID
        synchronized(lock) {
            val albums = readAlbums()
            if (albums.any { it.link == link || it.resolvedUrl == link }) return AddResult.DUPLICATE
            writeAlbums(albums + Album(link, null, null, 0, null, 0))
        }
        changed()
        refreshAsync(link)
        return AddResult.ADDED
    }

    fun removeAlbum(link: String) {
        synchronized(lock) {
            writeAlbums(readAlbums().filter { it.link != link })
            photosByAlbum.remove(link)
            saveCache()
        }
        changed()
    }

    /** Refreshes one album, or all of them when [link] is null, without waiting. */
    fun refreshAsync(link: String? = null) {
        scope.launch { if (link == null) refreshAll() else refreshAlbum(link) }
    }

    suspend fun refreshAll() {
        var allOk = true
        for (album in albums()) if (!refreshAlbum(album.link)) allOk = false
        val now = System.currentTimeMillis()
        // After a failure (e.g. Wi-Fi down) try again in 5 minutes rather than waiting a full cycle.
        val stamp = if (allOk) now else now - settings.refreshMinutes * 60_000L + 5 * 60_000L
        prefs.edit().putLong(KEY_LAST_REFRESH, stamp).apply()
    }

    /** Returns false if the album could not be fetched. */
    suspend fun refreshAlbum(link: String): Boolean = withContext(Dispatchers.IO) {
        val album = synchronized(lock) {
            readAlbums().find { it.link == link }?.takeIf { fetching.add(link) }
        } ?: return@withContext true
        changed()

        var content: AlbumContent? = null
        var error: String? = null
        try {
            content = try {
                fetcher.fetch(album.resolvedUrl ?: link)
            } catch (e: IOException) {
                // The full URL may have changed; the original short link always redirects to the current one.
                if (album.resolvedUrl != null) fetcher.fetch(link) else throw e
            }
        } catch (e: AlbumFetchException) {
            error = e.message
        } catch (e: IOException) {
            error = "Network problem (${e.message ?: e.javaClass.simpleName})"
        } catch (e: Exception) {
            error = "Couldn't read the album page (${e.javaClass.simpleName})"
        }

        val result = content
        synchronized(lock) {
            fetching.remove(link)
            val albums = readAlbums()
            if (albums.any { it.link == link }) {
                writeAlbums(albums.map { a ->
                    when {
                        a.link != link -> a
                        result != null -> a.copy(
                            title = result.title ?: a.title,
                            resolvedUrl = result.resolvedUrl,
                            photoCount = result.photos.size,
                            error = null,
                            updatedAt = System.currentTimeMillis(),
                        )
                        else -> a.copy(error = error)
                    }
                })
                if (result != null) {
                    photosByAlbum[link] = result.photos
                    saveCache()
                }
            }
        }
        changed()
        result != null
    }

    fun statusOf(album: Album): String = when {
        isFetching(album.link) -> "Loading…"
        album.error != null && album.updatedAt > 0 -> "${album.photoCount} photos (last update failed: ${album.error})"
        album.error != null -> album.error
        album.updatedAt == 0L -> "Waiting to load…"
        else -> "${album.photoCount} photos"
    }

    fun messageFor(result: AddResult) = when (result) {
        AddResult.ADDED -> "Album added. Its photos will appear in a moment."
        AddResult.DUPLICATE -> "That album is already in the list."
        AddResult.INVALID -> "That doesn't look like a Google Photos album link."
    }

    private fun changed() {
        prefs.edit().putLong(KEY_VERSION, System.nanoTime()).apply()
    }

    private fun readAlbums(): List<Album> {
        val array = try {
            JSONArray(prefs.getString(KEY_ALBUMS, "[]"))
        } catch (e: Exception) {
            JSONArray()
        }
        return (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            Album(
                link = o.optString("link").ifEmpty { return@mapNotNull null },
                title = o.optString("title").ifEmpty { null },
                resolvedUrl = o.optString("resolvedUrl").ifEmpty { null },
                photoCount = o.optInt("count"),
                error = o.optString("error").ifEmpty { null },
                updatedAt = o.optLong("updatedAt"),
            )
        }
    }

    private fun writeAlbums(albums: List<Album>) {
        val array = JSONArray()
        for (a in albums) {
            array.put(
                JSONObject()
                    .put("link", a.link)
                    .put("title", a.title)
                    .put("resolvedUrl", a.resolvedUrl)
                    .put("count", a.photoCount)
                    .put("error", a.error)
                    .put("updatedAt", a.updatedAt)
            )
        }
        prefs.edit().putString(KEY_ALBUMS, array.toString()).apply()
    }

    private fun loadCache() {
        synchronized(lock) {
            try {
                if (!cacheFile.exists()) return
                val root = JSONObject(cacheFile.readText())
                val links = readAlbums().map { it.link }.toSet()
                for (link in root.keys()) {
                    if (link !in links) continue
                    val items = root.getJSONArray(link)
                    photosByAlbum[link] = (0 until items.length()).map { i ->
                        val p = items.getJSONArray(i)
                        Photo(p.getString(0), p.getString(1), p.getInt(2), p.getInt(3), p.getLong(4))
                    }
                }
            } catch (e: Exception) {
                photosByAlbum.clear()
            }
        }
    }

    private fun saveCache() {
        val root = JSONObject()
        for ((link, photos) in photosByAlbum) {
            val items = JSONArray()
            for (p in photos) items.put(JSONArray().put(p.id).put(p.url).put(p.width).put(p.height).put(p.takenAt))
            root.put(link, items)
        }
        try {
            val tmp = File(cacheFile.path + ".tmp")
            tmp.writeText(root.toString())
            if (!tmp.renameTo(cacheFile)) {
                cacheFile.delete()
                tmp.renameTo(cacheFile)
            }
        } catch (e: IOException) {
            // Not fatal: the next refresh will try again.
        }
    }

    companion object {
        const val KEY_ALBUMS = "albums"
        const val KEY_VERSION = "libraryVersion"
        const val KEY_LAST_REFRESH = "lastRefresh"

        @Volatile
        private var instance: Library? = null

        fun get(context: Context): Library = instance ?: synchronized(this) {
            instance ?: Library(context.applicationContext).also { instance = it }
        }
    }
}
