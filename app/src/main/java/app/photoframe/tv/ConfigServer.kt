package app.photoframe.tv

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import kotlin.concurrent.thread

/**
 * A tiny web page on the local network, so albums can be added by pasting a link on a phone
 * instead of typing it with the TV remote. Only runs while the settings screen is open.
 */
class ConfigServer(private val library: Library) {

    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    var port: Int? = null
        private set

    fun start() {
        if (serverSocket != null) return
        for (candidate in 8765..8770) {
            val socket = ServerSocket()
            try {
                socket.reuseAddress = true
                socket.bind(InetSocketAddress(candidate))
                serverSocket = socket
                port = candidate
                break
            } catch (e: IOException) {
                socket.close()
            }
        }
        val socket = serverSocket ?: return
        thread(name = "config-server", isDaemon = true) {
            while (!socket.isClosed) {
                val client = try {
                    socket.accept()
                } catch (e: IOException) {
                    break
                }
                thread(isDaemon = true) {
                    try {
                        client.use { handle(it) }
                    } catch (e: Exception) {
                        // A broken connection from a browser is not our problem.
                    }
                }
            }
        }
    }

    fun stop() {
        try {
            serverSocket?.close()
        } catch (e: IOException) {
        }
        serverSocket = null
        port = null
    }

    private fun handle(socket: Socket) {
        socket.soTimeout = 10_000
        val input = BufferedInputStream(socket.getInputStream())
        val requestLine = readLine(input) ?: return
        val parts = requestLine.split(" ")
        if (parts.size < 2) return
        val method = parts[0]
        val path = parts[1].substringBefore('?')
        val query = parts[1].substringAfter('?', "")

        var contentLength = 0
        while (true) {
            val line = readLine(input) ?: return
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon > 0 && line.substring(0, colon).trim().equals("Content-Length", ignoreCase = true)) {
                contentLength = line.substring(colon + 1).trim().toIntOrNull() ?: 0
            }
        }
        val body = if (contentLength in 1..65_536) {
            val buffer = ByteArray(contentLength)
            var read = 0
            while (read < contentLength) {
                val n = input.read(buffer, read, contentLength - read)
                if (n < 0) break
                read += n
            }
            String(buffer, 0, read, Charsets.UTF_8)
        } else {
            ""
        }

        val out = socket.getOutputStream()
        when {
            method == "GET" && path == "/" -> respond(out, 200, "text/html; charset=utf-8", page(form(query)["m"]))
            method == "POST" && path == "/add" -> {
                val result = library.addAlbum(form(body)["link"].orEmpty())
                redirect(out, "/?m=${result.name.lowercase()}")
            }
            method == "POST" && path == "/remove" -> {
                form(body)["link"]?.let { library.removeAlbum(it) }
                redirect(out, "/")
            }
            method == "POST" && path == "/refresh" -> {
                library.refreshAsync()
                redirect(out, "/")
            }
            else -> respond(out, 404, "text/plain; charset=utf-8", "Not found")
        }
    }

    private fun page(messageKey: String?): String {
        val message = Library.AddResult.values().find { it.name.lowercase() == messageKey }?.let { library.messageFor(it) }
        val albums = library.albums()
        val loading = albums.any { library.isFetching(it.link) || (it.updatedAt == 0L && it.error == null) }
        val albumItems = if (albums.isEmpty()) {
            "<p class=muted>No albums yet.</p>"
        } else {
            albums.joinToString("") { album ->
                """
                <li>
                  <div class=info><b>${escape(album.title ?: "Untitled album")}</b>
                  <small>${escape(library.statusOf(album))}</small>
                  <small class=link>${escape(album.link)}</small></div>
                  <form method=post action=/remove><input type=hidden name=link value="${escape(album.link)}">
                  <button class=remove>Remove</button></form>
                </li>
                """
            }.let { "<ul>$it</ul>" }
        }
        return """
<!doctype html><html><head><meta charset=utf-8>
<meta name=viewport content="width=device-width, initial-scale=1">
<title>Photo Frame</title>
<style>
  body{font:16px/1.45 system-ui,-apple-system,sans-serif;margin:0;padding:20px;background:#101418;color:#f3f5f8}
  main{max-width:560px;margin:auto}
  h1{font-size:24px;margin:0 0 4px} h2{font-size:15px;text-transform:uppercase;letter-spacing:.08em;color:#9aa4b2;margin:28px 0 8px}
  .msg{background:#1f3a66;padding:12px 14px;border-radius:10px}
  ul{list-style:none;padding:0;margin:0}
  li{display:flex;gap:12px;align-items:center;background:#1a2027;border-radius:12px;padding:12px 14px;margin-bottom:8px}
  .info{flex:1;min-width:0} small{display:block;color:#9aa4b2} .link{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
  input[type=text]{width:100%;box-sizing:border-box;font:inherit;padding:12px;border-radius:10px;border:1px solid #39424e;background:#0b0e11;color:inherit}
  button{font:inherit;padding:11px 16px;border:0;border-radius:10px;background:#3d7eff;color:#fff}
  .add{margin-top:10px;width:100%} .remove{background:#3a2026;color:#ffb4b4} .secondary{background:#262e38}
  .muted{color:#9aa4b2}
</style></head><body><main>
<h1>Photo Frame</h1><p class=muted>Albums shown on your TV</p>
${if (message != null) "<p class=msg>${escape(message)}</p>" else ""}
<h2>Albums</h2>
$albumItems
<h2>Add an album</h2>
<form method=post action=/add>
  <input type=text name=link inputmode=url autocomplete=off placeholder="https://photos.app.goo.gl/…" required>
  <button class=add>Add album</button>
</form>
<p class=muted>In Google Photos, open the album, tap Share, then Create link (or Copy link), and paste it above.
New photos you add to the album show up on the TV automatically.</p>
<form method=post action=/refresh><button class=secondary>Check all albums for new photos now</button></form>
${if (loading) "<script>setTimeout(function(){var i=document.querySelector('input[name=link]');if(!i.value&&document.activeElement!==i)location.replace('/')},3000)</script>" else ""}
</main></body></html>
"""
    }

    private fun respond(out: OutputStream, code: Int, type: String, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val reason = if (code == 200) "OK" else "Not Found"
        val head = "HTTP/1.1 $code $reason\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\n" +
            "Cache-Control: no-store\r\nConnection: close\r\n\r\n"
        out.write(head.toByteArray(Charsets.ISO_8859_1))
        out.write(bytes)
        out.flush()
    }

    private fun redirect(out: OutputStream, location: String) {
        val head = "HTTP/1.1 303 See Other\r\nLocation: $location\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
        out.write(head.toByteArray(Charsets.ISO_8859_1))
        out.flush()
    }

    private fun readLine(input: InputStream): String? {
        val buffer = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return if (buffer.size() > 0) buffer.toString("ISO-8859-1") else null
            if (b == '\n'.code) break
            if (b != '\r'.code) buffer.write(b)
            if (buffer.size() > 8192) return null
        }
        return buffer.toString("ISO-8859-1")
    }

    private fun form(encoded: String): Map<String, String> = encoded.split('&').mapNotNull { pair ->
        val eq = pair.indexOf('=')
        if (eq <= 0) return@mapNotNull null
        try {
            URLDecoder.decode(pair.substring(0, eq), "UTF-8") to URLDecoder.decode(pair.substring(eq + 1), "UTF-8")
        } catch (e: IllegalArgumentException) {
            null
        }
    }.toMap()

    private fun escape(value: String) = value
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")
}
