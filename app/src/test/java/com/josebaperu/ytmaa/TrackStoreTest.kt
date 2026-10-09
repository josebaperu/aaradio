package com.josebaperu.ytmaa

import com.josebaperu.ytmaa.playback.TrackNames
import com.josebaperu.ytmaa.playback.VorbisComment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

class TrackStoreTest {
    @Test fun namesKeepThePlaylistDirectoryAndTheTrackTitle() {
        assertEquals("Night Drive", TrackNames.directoryName("Night Drive"))
        assertEquals("Blue", TrackNames.stem("Blue", "abc"))
        assertEquals("Blue.opus", TrackNames.plainFileName("Blue", "abc"))

        assertEquals("AC DC Live", TrackNames.directoryName("AC/DC: Live"))
        assertEquals("A B", TrackNames.stem("A/B", "abc"))

        assertEquals("Ahí Vamos", TrackNames.stem("Ahí Vamos", "abc"))
        assertEquals("Gustavo Cerati - Ahí Vamos", TrackNames.directoryName("Gustavo Cerati - Ahí Vamos"))
        assertEquals("YouTube Music", TrackNames.directoryName(".."))
        assertEquals("YouTube Music", TrackNames.directoryName(""))
        assertEquals("foo .. .. bar", TrackNames.directoryName("foo/../../bar"))
        assertTrue(!TrackNames.directoryName("foo/../../bar").contains('/'))
        assertEquals("á", TrackNames.sanitize("a\u0301"))
    }

    @Test fun longTitleIsCutOnACharacterBoundary() {
        val title = "á".repeat(80)
        val cut = TrackNames.limit(title, 5)
        assertEquals("áá", cut)
        assertTrue(cut.toByteArray(StandardCharsets.UTF_8).size <= 5)
    }

    @Test fun plainNameIsKeptWhenTheFileHasNoVideoIdOrTheSameOne() {
        assertEquals(TrackNames.PlayFrom.Plain, TrackNames.playFrom(false, true, "", "abc"))
        assertEquals(TrackNames.PlayFrom.Plain, TrackNames.playFrom(false, true, "abc", "abc"))
        assertEquals(TrackNames.SaveDest.Keep, TrackNames.saveDest(false, true, "", "abc"))
        assertEquals(TrackNames.SaveDest.Keep, TrackNames.saveDest(false, true, "abc", "abc"))
    }

    @Test fun aDifferentVideoWithTheSameTitleGetsItsOwnFile() {
        assertNull(TrackNames.playFrom(false, true, "aaa", "bbb"))
        assertEquals(TrackNames.SaveDest.Marked, TrackNames.saveDest(false, true, "aaa", "bbb"))
        assertEquals("Same [bbb].opus", TrackNames.markedFileName("Same", "bbb"))
        assertEquals(TrackNames.PlayFrom.Marked, TrackNames.playFrom(true, true, "aaa", "bbb"))
        assertEquals(TrackNames.SaveDest.Keep, TrackNames.saveDest(true, true, "aaa", "bbb"))
    }

    @Test fun aMissingFileUsesThePlainName() {
        assertNull(TrackNames.playFrom(false, false, "", "abc"))
        assertEquals(TrackNames.SaveDest.Plain, TrackNames.saveDest(false, false, "", "abc"))
    }

    @Test fun opusCommentCarriesTheVideoId() {
        val bytes = opusTags(listOf("title=Blue", "youtube_id=abc_123", "artist=Ada"))
        val prefixed = byteArrayOf(0x4f, 0x67, 0x67, 0x53, 0, 1, 2) + bytes
        val comments = VorbisComment.comments(ByteArrayInputStream(prefixed))
        assertEquals("abc_123", comments["youtube_id"])
        assertEquals("Blue", comments["title"])
        assertEquals("abc_123", VorbisComment.youtubeId(ByteArrayInputStream(prefixed)))
        assertEquals("", VorbisComment.youtubeId(ByteArrayInputStream("not audio".toByteArray())))
    }

    private fun opusTags(comments: List<String>): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("OpusTags".toByteArray(StandardCharsets.US_ASCII))
        writeLe(out, 0)
        writeLe(out, comments.size)
        for (comment in comments) {
            val bytes = comment.toByteArray(StandardCharsets.UTF_8)
            writeLe(out, bytes.size)
            out.write(bytes)
        }
        return out.toByteArray()
    }

    private fun writeLe(out: ByteArrayOutputStream, value: Int) {
        out.write(value and 0xFF)
        out.write((value shr 8) and 0xFF)
        out.write((value shr 16) and 0xFF)
        out.write((value shr 24) and 0xFF)
    }
}
