package com.miniplayer.app.core.metadata

import com.miniplayer.app.core.model.LyricsSource
import com.miniplayer.app.core.model.MediaItem
import com.miniplayer.app.core.model.MountState
import com.miniplayer.app.core.model.SafRoot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MetadataParsingAndM3uTest {

    @Test
    fun smartFilenameParser_stripsNoiseExtractsTrackAndDetectsBackingSuffix() {
        val p1 = SmartFilenameParser.parse(
            parentRelPath = "Music/Backing",
            fileName = "[320k] 04. John Mayer - Neon (Backing Track).mp3",
        )
        assertEquals(4, p1.trackNumber)
        assertEquals("John Mayer", p1.artist)
        assertEquals("Neon", p1.queryTitle)
        assertEquals("Neon (Backing Track)", p1.cleanedTitle)
        assertEquals("(Backing Track)", p1.backingSuffix)
        assertTrue(p1.isBackingTrack)
        // Generic parent folder "Music/Backing" should not pollute album so untagged tracks land in 未知专辑
        assertNull(p1.album)

        val p2 = SmartFilenameParser.parse(
            parentRelPath = "",
            fileName = "[FLAC] 12 - 结束バンド - 星座になれたら (无吉他伴奏) [24bit-96kHz].flac",
        )
        assertEquals(12, p2.trackNumber)
        assertEquals("结束バンド", p2.artist)
        assertEquals("星座になれたら", p2.queryTitle)
        assertEquals("星座になれたら (无吉他伴奏)", p2.cleanedTitle)
        assertTrue(p2.isBackingTrack)

        // Preserve original backing suffix when applying an online candidate title
        val filled1 = SmartFilenameParser.applyBackingSuffix("Neon", "[320k] 04. John Mayer - Neon (Backing Track).mp3")
        assertEquals("Neon (Backing Track)", filled1)
        val filled2 = SmartFilenameParser.applyBackingSuffix("星座になれたら", "星座になれたら (无吉他伴奏).flac")
        assertEquals("星座になれたら (无吉他伴奏)", filled2)
        val filledFallback = SmartFilenameParser.applyBackingSuffix("Little Wing", "Little Wing.mp3")
        assertEquals("Little Wing (伴奏)", filledFallback)
    }

    @Test
    fun smartFilenameParser_infersArtistAndAlbumFromHierarchyAndHandlesBlindTracks() {
        val hierarchical = SmartFilenameParser.parse(
            parentRelPath = "Lossless/John Mayer/Continuum",
            fileName = "04. Gravity.flac",
        )
        assertEquals(4, hierarchical.trackNumber)
        assertEquals("Gravity", hierarchical.cleanedTitle)
        assertEquals("John Mayer", hierarchical.artist)
        assertEquals("Continuum", hierarchical.album)
        assertFalse(hierarchical.isBackingTrack)

        val blind = SmartFilenameParser.parse(
            parentRelPath = "Jimi Hendrix - Axis Bold as Love",
            fileName = "Track_03.wav",
        )
        assertEquals(3, blind.trackNumber)
        assertEquals("Track 03", blind.cleanedTitle)
        assertEquals("Jimi Hendrix", blind.artist)
        assertEquals("Axis Bold as Love", blind.album)

        // Natural alphanumeric ordering: Track 2 < Track 10
        val unsorted = listOf("Track 10.mp3", "Track 2.mp3", "Track 1.mp3", "Track 20.mp3")
        val sorted = unsorted.sortedWith(SmartFilenameParser.NaturalComparator)
        assertEquals(listOf("Track 1.mp3", "Track 2.mp3", "Track 10.mp3", "Track 20.mp3"), sorted)
    }

    @Test
    fun artistSplitter_splitsMultiArtistsWhilePreservingAllowlistBands() {
        // Band allowlist protection (`AC/DC`, `Simon & Garfunkel`, `Earth, Wind & Fire`, `MYTH & ROID`)
        val acdcFeat = ArtistSplitter.split("AC/DC feat. Slash; Brian Johnson", albumArtist = "AC/DC")
        assertEquals(listOf("AC/DC", "Slash", "Brian Johnson"), acdcFeat.map { it.display })

        val simon = ArtistSplitter.split("Simon & Garfunkel / Crosby, Stills & Nash")
        assertEquals(listOf("Simon & Garfunkel", "Crosby, Stills & Nash"), simon.map { it.display })

        // Japanese頓号 `、` + bracketed `(CV. ...)` + `(ft. ...)` without leaving stray parentheses
        val jrock = ArtistSplitter.split("结束バンド (CV. 青山吉能)、喜多郁代 (CV: 长谷川育美) [ft. 初音ミク]")
        assertEquals(
            listOf("结束バンド", "青山吉能", "喜多郁代", "长谷川育美", "初音ミク"),
            jrock.map { it.display },
        )

        // Custom AlbumArtist with `/` protected from splitting
        val customBand = ArtistSplitter.split("Custom/SlashBand", albumArtist = "Custom/SlashBand")
        assertEquals(listOf("Custom/SlashBand"), customBand.map { it.display })

        // Empty fallback
        val unknown = ArtistSplitter.split("   ", null)
        assertEquals(listOf("未知歌手"), unknown.map { it.display })
    }

    @Test
    fun lrcParser_parsesMultiTimestampsOffsetsHeadersAndEncodings() {
        val rawLrc = """
            [ti:星座になれたら]
            [ar:结束バンド]
            [offset:100]
            [00:12.50][01:20.00]もういいかい まだだよ
            [00:05.00]Intro line
            [00:18.345]Millisecond precision
        """.trimIndent()

        val parsed = LrcParser.parse(rawLrc, LyricsSource.LOCAL_LRC)
        assertNotNull(parsed)
        assertEquals(LyricsSource.LOCAL_LRC, parsed!!.source)
        assertEquals(4, parsed.lines.size)
        // Sorted ascending with -100ms offset adjustment
        assertEquals(4_900L, parsed.lines[0].timeMs)
        assertEquals("Intro line", parsed.lines[0].text)
        assertEquals(12_400L, parsed.lines[1].timeMs)
        assertEquals("もういいかい まだだよ", parsed.lines[1].text)
        assertEquals(18_245L, parsed.lines[2].timeMs)
        assertEquals("Millisecond precision", parsed.lines[2].text)
        assertEquals(79_900L, parsed.lines[3].timeMs)
        assertEquals("もういいかい まだだよ", parsed.lines[3].text)

        assertEquals(-1, LrcParser.findActiveLineIndex(parsed.lines, 1_000L))
        assertEquals(0, LrcParser.findActiveLineIndex(parsed.lines, 5_000L))
        assertEquals(2, LrcParser.findActiveLineIndex(parsed.lines, 50_000L))
        assertEquals(3, LrcParser.findActiveLineIndex(parsed.lines, 90_000L))

        // UTF-8 BOM & GB18030 CJK fallback
        val gbBytes = "[00:01.00]吉他伴奏练习".toByteArray(java.nio.charset.Charset.forName("GB18030"))
        val decodedGb = LrcParser.decodeBytes(gbBytes)
        assertEquals("[00:01.00]吉他伴奏练习", decodedGb)
    }

    @Test
    fun m3uPlaylistCodec_exportsPracticeHeaderAndResolvesAcross3Tiers() {
        val roots = mapOf(
            1L to SafRoot(1L, "content://saf/tree/music", "primary:Music", "Music", true, MountState.MOUNTED),
            2L to SafRoot(2L, "content://saf/tree/otg", "otg:Tabs", "OTG_Tabs", false, MountState.MOUNTED),
        )

        fun item(
            id: Long,
            rootId: Long,
            parentRel: String,
            fileName: String,
            durationMs: Long,
            title: String = fileName,
            isVideo: Boolean = false,
        ) = MediaItem(
            itemId = id,
            documentUri = "content://saf/doc/$id",
            rootId = rootId,
            parentRelPath = parentRel,
            fileName = fileName,
            mimeType = if (isVideo) "video/mp4" else "audio/mpeg",
            isVideo = isVideo,
            sizeBytes = 1024L,
            lastModified = 1L,
            durationMs = durationMs,
            title = title,
            artist = "John Mayer",
        )

        // Two files named "Solo.mp3" in different folders & different durations, plus two ambiguous "Jam.mp3"
        val t1 = item(1L, 1L, "Live", "Solo.mp3", 180_200L, "Solo Live")
        val t2 = item(2L, 1L, "Studio", "Solo.mp3", 245_000L, "Solo Studio")
        val t3 = item(3L, 2L, "Lessons", "Neon_Fretboard.mp4", 612_000L, "Neon Fretboard", isVideo = true)
        val t4Unique = item(4L, 1L, "Deep/Subfolder", "UniqueTrack.flac", 210_000L, "Unique Track")
        val t5AmbA = item(5L, 1L, "Take1", "Ambiguous.wav", 120_000L)
        val t6AmbB = item(6L, 2L, "Take2", "Ambiguous.wav", 120_000L)

        val allItems = listOf(t1, t2, t3, t4Unique, t5AmbA, t6AmbB)

        // 1. Export practice playlist and verify #EXT-X-MINIPLAYER-PRACTICE:1 header
        val exported = M3uPlaylistCodec.exportToM3u8(
            playlistName = "本周排练",
            isPracticePlaylist = true,
            items = listOf(t1, t3),
            rootsById = roots,
        )
        assertTrue(exported.contains("#EXT-X-MINIPLAYER-PRACTICE:1"))
        assertTrue(exported.contains("#PLAYLIST:本周排练"))
        assertTrue(exported.contains("Music/Live/Solo.mp3"))
        assertTrue(exported.contains("OTG_Tabs/Lessons/Neon_Fretboard.mp4"))

        // 2. Import M3U exercising all 3 tiers + ambiguous/missing rejection
        val importedM3u = """
            #EXTM3U
            #EXT-X-MINIPLAYER-PRACTICE:1
            #PLAYLIST:跨设备导入练习单
            #EXTINF:180,Tier 1 relative path match
            D:\Backup\Music\Live\Solo.mp3
            #EXTINF:245,Tier 2 duration (±1s) match despite moved directory
            /old/computer/moved_dir/Solo.mp3
            #EXTINF:-1,Tier 3 unique filename match without duration or matching folder
            /totally/elsewhere/UniqueTrack.flac
            #EXTINF:612,Mixed video entry
            Lessons/Neon_Fretboard.mp4
            #EXTINF:-1,Ambiguous duplicate filename with no duration -> must NOT guess!
            /unknown/Ambiguous.wav
            #EXTINF:200,Non-existent file
            MissingSong.mp3
        """.trimIndent()

        val resolved = M3uPlaylistCodec.resolvePlaylist(
            rawContent = importedM3u,
            fallbackName = "默认",
            allItems = allItems,
            rootsById = roots,
        )
        assertEquals("跨设备导入练习单", resolved.playlistName)
        assertTrue(resolved.isPracticePlaylist)
        assertEquals(listOf(1L, 2L, 4L, 3L), resolved.matchedItems.map { it.itemId })
        assertEquals(2, resolved.unmatchedCount)
    }
}
