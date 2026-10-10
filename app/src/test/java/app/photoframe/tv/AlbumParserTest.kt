package app.photoframe.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AlbumParserTest {

    // Trimmed-down copy of the structure Google serves on a shared album page.
    private val albumHtml = """
        <html><head><title>Summer 2024 &amp; friends - Google Photos</title></head><body>
        <script nonce="a">AF_initDataCallback({key: 'ds:0', hash: '1', data:[null,"unrelated"], sideChannel: {}});</script>
        <script class="ds:1" nonce="b">AF_initDataCallback({key: 'ds:1', hash: '2', data:[null,[
          ["AF1QipAAA",["https://lh3.googleusercontent.com/pw/AAA",4032,3024,null,null,null,null,null,[1],[2000000]],1690000000000,"",3600000,1690000001000],
          ["AF1QipVID",["https://lh3.googleusercontent.com/pw/VID=w100-h100",1080,1920,null],1690000002000,"",0,1690000003000,{"76647426":[1,2]}],
          ["AF1QipCCC",["https://lh3.googleusercontent.com/pw/CCC=w200-h150",800,600],1690000004000,"",0,1690000005000]
        ],"NEXT_TOKEN",[null,"Summer 2024"]], sideChannel: {}});</script>
        </body></html>
    """.trimIndent()

    @Test
    fun parsesPhotosTitleAndPageToken() {
        val page = AlbumParser.parseAlbumHtml(albumHtml)
        assertEquals("Summer 2024 & friends", page.title)
        assertEquals(listOf("AF1QipAAA", "AF1QipVID", "AF1QipCCC"), page.photos.map { it.id })
        assertEquals(listOf(false, true, false), page.photos.map { it.isVideo })
        val first = page.photos[0]
        assertEquals("https://lh3.googleusercontent.com/pw/AAA", first.url)
        assertEquals(4032, first.width)
        assertEquals(3024, first.height)
        assertEquals(1690000000000L, first.takenAt)
        assertEquals("https://lh3.googleusercontent.com/pw/VID", page.photos[1].url) // size suffix stripped
        assertEquals("https://lh3.googleusercontent.com/pw/VID=dv", page.photos[1].videoUrl())
        assertEquals("NEXT_TOKEN", page.nextPageToken)
    }

    @Test
    fun fallsBackToRegexWhenDataBlocksAreUnreadable() {
        val html = """<script>weird([["AF1QipXYZ",["https://lh3.googleusercontent.com/pw/XYZ",640,480,null]]])</script>"""
        val page = AlbumParser.parseAlbumHtml(html)
        assertEquals(listOf("AF1QipXYZ"), page.photos.map { it.id })
        assertNull(page.nextPageToken)
    }

    @Test
    fun parsesPagingResponse() {
        val body = ")]}'\n\n200\n" +
            """[["wrb.fr","snAcKc","[null,[[\"AF1QipDDD\",[\"https://lh3.googleusercontent.com/pw/DDD\",10,20],5,\"\",0,6]],\"TOKEN2\"]",null,null,null,"generic"]]""" +
            "\n25\n[[\"di\",50]]\n"
        val page = AlbumParser.parseBatchResponse(body)
        assertEquals(listOf("AF1QipDDD"), page.photos.map { it.id })
        assertEquals("TOKEN2", page.nextPageToken)
    }

    @Test
    fun extractsLinkFromSharedText() {
        assertEquals(
            "https://photos.app.goo.gl/AbC123xyz",
            AlbumParser.extractLink("Check out this album: https://photos.app.goo.gl/AbC123xyz."),
        )
        assertEquals(
            "https://photos.google.com/share/AF1QipABC?key=XYZ",
            AlbumParser.extractLink("https://photos.google.com/share/AF1QipABC?key=XYZ"),
        )
        assertNull(AlbumParser.extractLink("https://example.com/not-an-album"))
    }
}
