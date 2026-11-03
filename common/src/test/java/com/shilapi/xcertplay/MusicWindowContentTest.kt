package com.shilapi.xcertplay

import com.shilapi.xcertplay.media.CarPlayNowPlaying
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MusicWindowContentTest {
    private fun content(info: CarPlayNowPlaying) = musicWindowContent(info, "Unknown artist", "Nothing playing")

    @Test fun aFullTrackFillsTheTwoLines() {
        val shown = content(CarPlayNowPlaying(title = "song", artist = "artist", album = "album"))
        assertEquals("song", shown.title)
        assertEquals("artist · album", shown.meta)
    }

    @Test fun anEmptySnapshotIsTheEmptyState() {
        val shown = content(CarPlayNowPlaying())
        assertEquals("Nothing playing", shown.title)
        assertNull(shown.meta)
    }

    @Test fun aMissingArtistIsNamedRatherThanHidden() {
        val shown = content(CarPlayNowPlaying(title = "song", album = "album"))
        assertEquals("song", shown.title)
        assertEquals("Unknown artist · album", shown.meta)
    }

    @Test fun theSeparatorOnlyJoinsTwoFields() {
        // No dangling dot when there is no artist name to put before the album.
        val shown = musicWindowContent(
            CarPlayNowPlaying(title = "song", album = "album"), "", "Nothing playing",
        )
        assertEquals("album", shown.meta)
    }

    @Test fun anArtistWithoutATitleBecomesTheTitleLine() {
        // Some apps report the performer before the song arrives; repeating it as its own line says nothing.
        val shown = content(CarPlayNowPlaying(artist = "artist"))
        assertEquals("artist", shown.title)
        assertNull(shown.meta)
    }

    @Test fun blankFieldsCountAsAbsent() {
        val shown = content(CarPlayNowPlaying(title = "  ", artist = "", album = " "))
        assertEquals("Nothing playing", shown.title)
        assertNull(shown.meta)
    }

    @Test fun anAlbumWithoutATrackIsStillTheArtistOnly() {
        // A cover transfer can arrive before the metadata: an album alone is no song to show.
        val shown = content(CarPlayNowPlaying(album = "album"))
        assertEquals("Nothing playing", shown.title)
        assertNull(shown.meta)
    }
}
