package com.josebaperu.ytmaa

import com.josebaperu.ytmaa.data.FilePlaylist
import com.josebaperu.ytmaa.data.YtPlaylist
import com.josebaperu.ytmaa.data.YtPlaylistFile
import com.josebaperu.ytmaa.data.YtTrack
import com.josebaperu.ytmaa.playback.YtMedia
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class YtPlaylistFileTest {
    @Test fun parsesExportObjectsAndSkipsOnesWithoutAUrl() {
        val parsed = YtPlaylistFile.parseExport(
            """
            [
              {"id":"a","title":"One \"two\"","url":"https://music.youtube.com/playlist?list=a","updatedDate":1790527678072},
              {"title":"skip"},
              {"title":"No id","url":"https://music.youtube.com/playlist?list=b\u0026x=1"}
            ]
            """.trimIndent(),
        )
        assertEquals(listOf("a", "https://music.youtube.com/playlist?list=b&x=1"), parsed.map { it.id })
        assertEquals("One \"two\"", parsed[0].title)
        assertEquals(1790527678072L, parsed[0].updatedDate)
        assertEquals("No id", parsed[1].title)
    }

    @Test fun rejectsAFileThatIsNotAnArray() {
        val error = runCatching { YtPlaylistFile.parseExport("{\"url\":\"https://x\"}") }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }

    @Test fun parsesTheSampleExportWhenItIsPresent() {
        val file = File("../1790911263891_playlists.txt")
        if (!file.isFile) return
        val parsed = YtPlaylistFile.parseExport(file.readText())
        assertEquals(48, parsed.size)
        assertEquals("Placebo - Never Let Me Go", parsed.first().title)
        assertEquals("Radiohead - Kid A", parsed.last().title)
        assertTrue(parsed.all { it.url.startsWith("https://music.youtube.com/playlist?list=") })
    }

    @Test fun mergeKeepsResolvedTracksAddsNewUrlsAndDropsMissingOnes() {
        val kept = playlist("old", "Old name", "https://example/kept", resolved = true, tracks = listOf(track("t1")))
        val dropped = playlist("gone", "Gone", "https://example/gone", resolved = true, tracks = listOf(track("t2")))
        val failed = playlist("bad", "Bad", "https://example/bad", resolved = false, error = "nope")
        val file = listOf(
            FilePlaylist("old", "New name", "https://example/kept", 5),
            FilePlaylist("fresh", "Fresh", "https://example/fresh", 6),
            FilePlaylist("bad", "Bad again", "https://example/bad", 7),
            FilePlaylist("fresh", "duplicate", "https://example/fresh", 8),
        )
        val merged = YtPlaylistFile.merge(file, listOf(dropped, kept, failed))
        assertEquals(listOf("https://example/kept", "https://example/fresh", "https://example/bad"), merged.map { it.url })
        assertEquals("New name", merged[0].title)
        assertEquals(listOf("t1"), merged[0].tracks.map { it.id })
        assertTrue(merged[0].resolved)
        assertFalse(merged[1].resolved)
        assertTrue(merged[1].tracks.isEmpty())
        assertFalse(merged[2].resolved)
        assertNull(merged[2].error)
    }

    @Test fun mergeRestoresArchivedTracksWhenTheFileIncludesThatUrl() {
        val archived = playlist("old", "Old", "https://example/back", resolved = true, tracks = listOf(track("t1")))
        val file = listOf(FilePlaylist("newid", "New title", "https://example/back", 9))
        val merged = YtPlaylistFile.merge(file, emptyList(), listOf(archived))
        assertEquals("newid", merged.single().id)
        assertEquals("New title", merged.single().title)
        assertEquals(listOf("t1"), merged.single().tracks.map { it.id })
        assertTrue(merged.single().resolved)
    }

    @Test fun mergeLeavesOutAnArchivedPlaylistTheFileOmits() {
        val archived = playlist("old", "Old", "https://example/back", resolved = true, tracks = listOf(track("t1")))
        val file = listOf(FilePlaylist("fresh", "Fresh", "https://example/fresh", 1))
        val merged = YtPlaylistFile.merge(file, emptyList(), listOf(archived))
        assertEquals(listOf("https://example/fresh"), merged.map { it.url })
        assertFalse(merged.single().resolved)
    }

    @Test fun savedResolvedTracksWinOverTheArchive() {
        val archived = playlist("old", "Old", "https://example/kept", resolved = true, tracks = listOf(track("old")))
        val saved = playlist("cur", "Current", "https://example/kept", resolved = true, tracks = listOf(track("new")))
        val file = listOf(FilePlaylist("id", "Title", "https://example/kept", 1))
        val merged = YtPlaylistFile.merge(file, listOf(saved), listOf(archived))
        assertEquals(listOf("new"), merged.single().tracks.map { it.id })
        assertEquals("Title", merged.single().title)
    }

    @Test fun sortsTitlesFromAToZ() {
        val sorted = YtPlaylistFile.sortedByTitle(
            listOf(
                playlist("z", "zeta", "https://example/z", resolved = false),
                playlist("a", "Alpha", "https://example/a", resolved = false),
                playlist("b", "álbum", "https://example/b", resolved = false),
                playlist("a2", "alpha", "https://example/c", resolved = false),
            ),
        )
        assertEquals(listOf("https://example/b", "https://example/a", "https://example/c", "https://example/z"), sorted.map { it.url })
    }

    @Test fun matchingTitlesKeepsAlbumsWhoseNameContainsTheText() {
        val albums = listOf(
            playlist("z", "zeta", "https://example/z", resolved = false),
            playlist("a", "Arcade Fire - Funeral", "https://example/funeral", resolved = true, tracks = listOf(track("song"))),
            playlist("r", "Arcade Fire - Reflektor", "https://example/reflektor", resolved = false),
            playlist("b", "Blondie - Best Of Blondie", "https://example/blondie", resolved = false),
        )
        assertEquals(
            listOf("https://example/funeral", "https://example/reflektor"),
            YtPlaylistFile.matchingTitles(albums, "  fire ").map { it.url },
        )
        assertEquals(listOf("https://example/blondie"), YtPlaylistFile.matchingTitles(albums, "BLONDIE").map { it.url })
        assertTrue(YtPlaylistFile.matchingTitles(albums, "song").isEmpty())
        assertTrue(YtPlaylistFile.matchingTitles(albums, "   ").isEmpty())
    }

    @Test fun libraryRoundTripKeepsTracks() {
        val original = listOf(
            playlist(
                "id",
                "Title\nline",
                "https://example/a",
                resolved = true,
                tracks = listOf(YtTrack("v", "https://music.youtube.com/watch?v=v", "Song", "Name", 183.5)),
            ),
        )
        val decoded = YtPlaylistFile.decodeLibrary(YtPlaylistFile.encodeLibrary(original))
        assertEquals(original, decoded)
    }

    @Test fun flatTrackSkipsDeletedVideosAndStripsTopic() {
        val line = """{"id":"abc","title":"Song","url":"abc","channel":"Band - Topic","duration":12}"""
        val track = YtPlaylistFile.parseFlatTrack(line)
        assertEquals("abc", track?.id)
        assertEquals("https://music.youtube.com/watch?v=abc", track?.url)
        assertEquals("Band", track?.artist)
        assertEquals(12.0, track?.durationSec)
        assertNull(YtPlaylistFile.parseFlatTrack("""{"id":"x","title":"[Deleted video]","url":"https://youtu.be/x"}"""))
    }

    @Test fun mediaIdCarriesTheWatchUrl() {
        val track = YtTrack("abc", "https://music.youtube.com/watch?v=abc", "Song", "Band", 1.0)
        val id = YtMedia.mediaId("playlist", 2, track)
        assertTrue(YtMedia.isYtm(id))
        assertEquals("playlist", YtMedia.playlistId(id))
        assertEquals(track.url, YtMedia.watchUrl(id))
        assertFalse(YtMedia.isYtm("station"))
    }

    private fun track(id: String) = YtTrack(id, "https://music.youtube.com/watch?v=$id", id, "", 0.0)

    private fun playlist(
        id: String,
        title: String,
        url: String,
        resolved: Boolean,
        tracks: List<YtTrack> = emptyList(),
        error: String? = null,
    ) = YtPlaylist(id, title, url, 1, resolved, tracks, error)
}
