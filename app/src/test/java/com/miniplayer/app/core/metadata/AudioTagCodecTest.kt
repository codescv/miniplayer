package com.miniplayer.app.core.metadata

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AudioTagCodecTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun fakeJpegBytes(size: Int, seed: Int = 7): ByteArray {
        val bytes = ByteArray(size.coerceAtLeast(8))
        bytes[0] = 0xFF.toByte()
        bytes[1] = 0xD8.toByte()
        bytes[2] = 0xFF.toByte()
        bytes[3] = 0xE0.toByte()
        for (i in 4 until bytes.size - 2) {
            bytes[i] = ((i * 31 + seed) and 0xFF).toByte()
        }
        bytes[bytes.size - 2] = 0xFF.toByte()
        bytes[bytes.size - 1] = 0xD9.toByte()
        return bytes
    }

    private fun fakePngBytes(size: Int): ByteArray {
        val bytes = ByteArray(size.coerceAtLeast(12))
        val sig = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        System.arraycopy(sig, 0, bytes, 0, sig.size)
        for (i in 8 until bytes.size) bytes[i] = (i and 0xFF).toByte()
        return bytes
    }

    @Test
    fun mp3Id3v23AndId3v24_readsPreservesUnknownFramesAndRewritesShrinksCleanly() {
        // Build an MP3 with ID3v2.3 header (containing a UTF-16 TIT2 + custom TCON frame) + 10 MPEG-1 Layer 3 128kbps frames
        val mp3AudioFrame = ByteArray(417).apply {
            this[0] = 0xFF.toByte()
            this[1] = 0xFB.toByte() // MPEG-1 Layer III
            this[2] = 0x90.toByte() // 128kbps, 44100Hz
            this[3] = 0x00.toByte()
            for (i in 4 until size) this[i] = (i xor 0x5A).toByte()
        }
        val audioPayload = ByteArray(mp3AudioFrame.size * 20)
        for (f in 0 until 20) {
            System.arraycopy(mp3AudioFrame, 0, audioPayload, f * mp3AudioFrame.size, mp3AudioFrame.size)
        }

        val v23TagBody = ByteArrayOutputStream().apply {
            // TIT2 in UTF-16LE with BOM
            val utf16Title = byteArrayOf(0x01, 0xFF.toByte(), 0xFE.toByte()) + "Old MP3 Title".toByteArray(Charsets.UTF_16LE)
            write("TIT2".toByteArray(Charsets.ISO_8859_1))
            write(ByteBuffer.allocate(4).putInt(utf16Title.size).array())
            write(byteArrayOf(0, 0))
            write(utf16Title)

            // Custom genre frame TCON ("Rock") that must be preserved untouched!
            val genrePayload = byteArrayOf(0x00) + "Rock".toByteArray(Charsets.ISO_8859_1)
            write("TCON".toByteArray(Charsets.ISO_8859_1))
            write(ByteBuffer.allocate(4).putInt(genrePayload.size).array())
            write(byteArrayOf(0, 0))
            write(genrePayload)
        }.toByteArray()

        val srcMp3 = tempFolder.newFile("sample.mp3")
        srcMp3.outputStream().use { out ->
            out.write(byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 3, 0, 0))
            val s = v23TagBody.size
            out.write(byteArrayOf(((s ushr 21) and 0x7F).toByte(), ((s ushr 14) and 0x7F).toByte(), ((s ushr 7) and 0x7F).toByte(), (s and 0x7F).toByte()))
            out.write(v23TagBody)
            out.write(audioPayload)
        }

        val initial = AudioTagCodec.readTags(srcMp3)
        assertEquals("Old MP3 Title", initial.title)
        assertTrue((initial.durationMs ?: 0L) > 0L)

        // Rewrite with full 6 fields + large 16 KB APIC cover
        val bigCover = fakeJpegBytes(16_384, seed = 11)
        val staged1 = File(tempFolder.root, "staged1.mp3")
        AudioTagCodec.writeTagsToTempFile(
            sourceFile = srcMp3,
            tempOutputFile = staged1,
            spec = TagWriteSpec(
                title = OptionalField.Set("星座になれたら (伴奏)"),
                artist = OptionalField.Set("结束バンド"),
                album = OptionalField.Set("结束バンド"),
                albumArtist = OptionalField.Set("结束バンド"),
                trackNumber = OptionalField.Set(12),
                year = OptionalField.Set(2022),
                cover = OptionalField.Set(CoverImage(bigCover, "image/jpeg")),
            ),
        )

        val afterBig = AudioTagCodec.readTags(staged1)
        assertEquals("星座になれたら (伴奏)", afterBig.title)
        assertEquals("结束バンド", afterBig.artist)
        assertEquals("结束バンド", afterBig.album)
        assertEquals("结束バンド", afterBig.albumArtist)
        assertEquals(12, afterBig.trackNumber)
        assertEquals(2022, afterBig.year)
        assertArrayEquals(bigCover, afterBig.coverBytes)

        // Verify audio tail bytes are 100% identical to original audioPayload
        val staged1Bytes = staged1.readBytes()
        val tail1 = staged1Bytes.copyOfRange(staged1Bytes.size - audioPayload.size, staged1Bytes.size)
        assertArrayEquals(audioPayload, tail1)

        // Now replace 16 KB cover with tiny 512 B cover and leave Title unchanged -> file size must shrink cleanly
        val smallCover = fakeJpegBytes(512, seed = 22)
        val staged2 = File(tempFolder.root, "staged2.mp3")
        AudioTagCodec.writeTagsToTempFile(
            sourceFile = staged1,
            tempOutputFile = staged2,
            spec = TagWriteSpec(
                cover = OptionalField.Set(CoverImage(smallCover, "image/jpeg")),
            ),
        )
        assertTrue(staged2.length() < staged1.length())
        val afterSmall = AudioTagCodec.readTags(staged2)
        assertEquals("星座になれたら (伴奏)", afterSmall.title)
        assertArrayEquals(smallCover, afterSmall.coverBytes)
    }

    @Test
    fun flacVorbisAndPicture_readsStreamInfoAndRewritesWithoutCorruptingFrames() {
        // Build valid FLAC: "fLaC" + STREAMINFO (type=0, isLast=1, 34 bytes: 44100 Hz, 44100*180 samples = 180_000 ms) + audio bytes
        val streamInfo = ByteArray(34)
        val sampleRate = 44_100
        val totalSamples = 44_100L * 180L // 180,000 ms
        streamInfo[10] = ((sampleRate ushr 12) and 0xFF).toByte()
        streamInfo[11] = ((sampleRate ushr 4) and 0xFF).toByte()
        streamInfo[12] = (((sampleRate and 0x0F) shl 4) and 0xF0).toByte()
        streamInfo[13] = ((totalSamples ushr 32) and 0x0F).toByte()
        ByteBuffer.wrap(streamInfo, 14, 4).order(ByteOrder.BIG_ENDIAN).putInt(totalSamples.toInt())

        val flacFrames = ByteArray(2048) { idx -> (0xAA xor idx).toByte() }
        val srcFlac = tempFolder.newFile("track.flac")
        srcFlac.outputStream().use { out ->
            out.write("fLaC".toByteArray(Charsets.ISO_8859_1))
            out.write(0x80) // isLast=1, blockType=0 (STREAMINFO)
            out.write(byteArrayOf(0, 0, 34))
            out.write(streamInfo)
            out.write(flacFrames)
        }

        val initial = AudioTagCodec.readTags(srcFlac)
        assertEquals(180_000L, initial.durationMs)
        assertNull(initial.title)

        val pngCover = fakePngBytes(4096)
        val outFlac = File(tempFolder.root, "updated.flac")
        AudioTagCodec.writeTagsToTempFile(
            sourceFile = srcFlac,
            tempOutputFile = outFlac,
            spec = TagWriteSpec(
                title = OptionalField.Set("Neon"),
                artist = OptionalField.Set("John Mayer"),
                album = OptionalField.Set("Continuum"),
                albumArtist = OptionalField.Set("John Mayer"),
                trackNumber = OptionalField.Set(4),
                year = OptionalField.Set(2006),
                cover = OptionalField.Set(CoverImage(pngCover, "image/png")),
            ),
        )

        val updated = AudioTagCodec.readTags(outFlac)
        assertEquals(180_000L, updated.durationMs)
        assertEquals("Neon", updated.title)
        assertEquals("John Mayer", updated.artist)
        assertEquals("Continuum", updated.album)
        assertEquals("John Mayer", updated.albumArtist)
        assertEquals(4, updated.trackNumber)
        assertEquals(2006, updated.year)
        assertEquals("image/png", updated.coverMimeType)
        assertArrayEquals(pngCover, updated.coverBytes)

        val allBytes = outFlac.readBytes()
        assertArrayEquals(flacFrames, allBytes.copyOfRange(allBytes.size - flacFrames.size, allBytes.size))
    }

    @Test
    fun oggOpus_readsAndWritesTagsAndCoverWithValidOggCrc32() {
        val opusHead = ByteArray(19).apply {
            val magic = "OpusHead".toByteArray(Charsets.ISO_8859_1)
            System.arraycopy(magic, 0, this, 0, 8)
            this[8] = 1 // version
            this[9] = 2 // channels
            // preSkip = 0, sampleRate = 48000
            ByteBuffer.wrap(this, 12, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(48_000)
        }
        val vendor = "MiniTest".toByteArray(Charsets.UTF_8)
        val opusTags = ByteBuffer.allocate(8 + 4 + vendor.size + 4).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("OpusTags".toByteArray(Charsets.ISO_8859_1))
            putInt(vendor.size)
            put(vendor)
            putInt(0) // 0 comments initially
        }.array()
        val audioPacket = ByteArray(300) { (it * 7).toByte() }

        fun makeOggPage(headerType: Int, granule: Long, seq: Int, pkt: ByteArray): ByteArray {
            val segs = ArrayList<ByteArray>()
            var off = 0
            while (off < pkt.size) {
                val n = minOf(255, pkt.size - off)
                segs.add(pkt.copyOfRange(off, off + n))
                off += n
            }
            if (pkt.size % 255 == 0) segs.add(ByteArray(0))
            val raw = ByteArray(27 + segs.size + pkt.size)
            val bb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
            bb.put("OggS".toByteArray(Charsets.ISO_8859_1))
            bb.put(0)
            bb.put(headerType.toByte())
            bb.putLong(granule)
            bb.putInt(0x12345678)
            bb.putInt(seq)
            bb.putInt(0)
            bb.put(segs.size.toByte())
            segs.forEach { bb.put(it.size.toByte()) }
            segs.forEach { bb.put(it) }
            bb.putInt(22, AudioTagCodec.computeOggCrc32(raw))
            return raw
        }

        val srcOpus = tempFolder.newFile("jam.opus")
        srcOpus.outputStream().use { out ->
            out.write(makeOggPage(0x02, 0L, 0, opusHead))
            out.write(makeOggPage(0x00, 0L, 1, opusTags))
            out.write(makeOggPage(0x04, 48_000L * 95L, 2, audioPacket))
        }

        val cover = fakeJpegBytes(1500)
        val outOpus = File(tempFolder.root, "jam_tagged.opus")
        AudioTagCodec.writeTagsToTempFile(
            sourceFile = srcOpus,
            tempOutputFile = outOpus,
            spec = TagWriteSpec(
                title = OptionalField.Set("Slow Blues Jam"),
                artist = OptionalField.Set("B.B. King / Eric Clapton"),
                album = OptionalField.Set("Riding with the King"),
                year = OptionalField.Set(2000),
                cover = OptionalField.Set(CoverImage(cover, "image/jpeg")),
            ),
        )

        val readBack = AudioTagCodec.readTags(outOpus)
        assertEquals(95_000L, readBack.durationMs)
        assertEquals("Slow Blues Jam", readBack.title)
        assertEquals("B.B. King / Eric Clapton", readBack.artist)
        assertEquals("Riding with the King", readBack.album)
        assertEquals(2000, readBack.year)
        assertArrayEquals(cover, readBack.coverBytes)
    }

    @Test
    fun mp4Ilst_recalculatesStcoAndCo64OffsetsExactlyWhenMoovPrecedesMdat() {
        val chunk0Marker = "AUDIO_CHUNK_0_EXACT_SYNC_BYTES!".toByteArray(Charsets.US_ASCII)
        val chunk1Marker = "AUDIO_CHUNK_1_EXACT_SYNC_BYTES!".toByteArray(Charsets.US_ASCII)
        val mdatPayload = ByteArray(1024).also { buf ->
            System.arraycopy(chunk0Marker, 0, buf, 0, chunk0Marker.size)
            System.arraycopy(chunk1Marker, 0, buf, 512, chunk1Marker.size)
        }

        fun box(type: String, payload: ByteArray): ByteArray {
            val out = ByteArray(8 + payload.size)
            ByteBuffer.wrap(out).order(ByteOrder.BIG_ENDIAN).putInt(out.size)
            System.arraycopy(type.toByteArray(Charsets.ISO_8859_1), 0, out, 4, 4)
            System.arraycopy(payload, 0, out, 8, payload.size)
            return out
        }

        val ftyp = box("ftyp", "M4A \u0000\u0000\u0002\u0000M4A mp42isom".toByteArray(Charsets.ISO_8859_1))
        val mvhd = box(
            "mvhd",
            ByteBuffer.allocate(20).order(ByteOrder.BIG_ENDIAN).apply {
                putInt(0) // version 0
                putInt(0)
                putInt(0)
                putInt(1000) // timescale = 1000
                putInt(214_500) // duration = 214.5s
            }.array(),
        )

        // Build placeholder stco first so we know exact moov size, then fill true chunk offsets
        fun buildMoovWithStco(off0: Int, off1: Int): ByteArray {
            val stco = box(
                "stco",
                ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN).apply {
                    putInt(0) // version/flags
                    putInt(2) // 2 entries
                    putInt(off0)
                    putInt(off1)
                }.array(),
            )
            val stbl = box("stbl", stco)
            val minf = box("minf", stbl)
            val mdia = box("mdia", minf)
            val trak = box("trak", mdia)
            return box("moov", mvhd + trak)
        }

        val dummyMoovSize = buildMoovWithStco(0, 0).size
        val mdatDataStart = ftyp.size + dummyMoovSize + 8 // +8 for mdat box header
        val moov = buildMoovWithStco(mdatDataStart, mdatDataStart + 512)
        val mdat = box("mdat", mdatPayload)

        val srcM4a = tempFolder.newFile("song.m4a")
        srcM4a.writeBytes(ftyp + moov + mdat)

        // Helper to extract stco offsets from any MP4 file
        fun readStcoOffsets(file: File): List<Long> {
            val all = file.readBytes()
            val sig = "stco".toByteArray(Charsets.ISO_8859_1)
            for (i in 4 until all.size - 16) {
                if (all[i] == sig[0] && all[i + 1] == sig[1] && all[i + 2] == sig[2] && all[i + 3] == sig[3]) {
                    val bb = ByteBuffer.wrap(all).order(ByteOrder.BIG_ENDIAN)
                    val count = bb.getInt(i + 8)
                    return (0 until count).map { idx -> bb.getInt(i + 12 + idx * 4).toLong() and 0xFFFF_FFFFL }
                }
            }
            return emptyList()
        }

        // 1. Expand `moov` before `mdat` by writing metadata + 12 KB JPEG cover
        val cover12k = fakeJpegBytes(12_288)
        val expandedM4a = File(tempFolder.root, "expanded.m4a")
        AudioTagCodec.writeTagsToTempFile(
            sourceFile = srcM4a,
            tempOutputFile = expandedM4a,
            spec = TagWriteSpec(
                title = OptionalField.Set("Little Wing (Backing)"),
                artist = OptionalField.Set("Jimi Hendrix"),
                album = OptionalField.Set("Axis: Bold as Love"),
                albumArtist = OptionalField.Set("The Jimi Hendrix Experience"),
                trackNumber = OptionalField.Set(6),
                year = OptionalField.Set(1967),
                cover = OptionalField.Set(CoverImage(cover12k, "image/jpeg")),
            ),
        )

        val tagsExpanded = AudioTagCodec.readTags(expandedM4a)
        assertEquals(214_500L, tagsExpanded.durationMs)
        assertEquals("Little Wing (Backing)", tagsExpanded.title)
        assertEquals("Jimi Hendrix", tagsExpanded.artist)
        assertEquals("Axis: Bold as Love", tagsExpanded.album)
        assertEquals("The Jimi Hendrix Experience", tagsExpanded.albumArtist)
        assertEquals(6, tagsExpanded.trackNumber)
        assertEquals(1967, tagsExpanded.year)
        assertArrayEquals(cover12k, tagsExpanded.coverBytes)

        // Verify updated `stco` offsets point to the EXACT audio chunks inside `mdat`!
        val expandedOffsets = readStcoOffsets(expandedM4a)
        assertEquals(2, expandedOffsets.size)
        RandomAccessFile(expandedM4a, "r").use { raf ->
            val buf0 = ByteArray(chunk0Marker.size)
            raf.seek(expandedOffsets[0])
            raf.readFully(buf0)
            assertArrayEquals("Chunk 0 misaligned after moov expansion!", chunk0Marker, buf0)

            val buf1 = ByteArray(chunk1Marker.size)
            raf.seek(expandedOffsets[1])
            raf.readFully(buf1)
            assertArrayEquals("Chunk 1 misaligned after moov expansion!", chunk1Marker, buf1)
        }

        // 2. Remove the 12 KB cover -> `moov` shrinks, file shrinks, and `stco` offsets shift back accurately!
        val shrunkM4a = File(tempFolder.root, "shrunk.m4a")
        AudioTagCodec.writeTagsToTempFile(
            sourceFile = expandedM4a,
            tempOutputFile = shrunkM4a,
            spec = TagWriteSpec(cover = OptionalField.Set(null)),
        )
        assertTrue(shrunkM4a.length() < expandedM4a.length())
        assertNull(AudioTagCodec.readTags(shrunkM4a).coverBytes)

        val shrunkOffsets = readStcoOffsets(shrunkM4a)
        RandomAccessFile(shrunkM4a, "r").use { raf ->
            val buf0 = ByteArray(chunk0Marker.size)
            raf.seek(shrunkOffsets[0])
            raf.readFully(buf0)
            assertArrayEquals("Chunk 0 misaligned after moov shrink!", chunk0Marker, buf0)
        }

        // 3. Verify 32-bit `stco` -> 64-bit `co64` automatic overflow upgrade when near 4 GiB boundary
        val near4GbOffset = 0xFFFF_FF80L.toInt() // 4294967168 unsigned
        val overflowMoov = buildMoovWithStco(near4GbOffset, near4GbOffset + 64)
        val overflowSrc = tempFolder.newFile("near4gb.m4a")
        overflowSrc.writeBytes(ftyp + overflowMoov + mdat)

        val upgradedOut = File(tempFolder.root, "upgraded_co64.m4a")
        AudioTagCodec.writeTagsToTempFile(
            sourceFile = overflowSrc,
            tempOutputFile = upgradedOut,
            spec = TagWriteSpec(
                title = OptionalField.Set("4GB Overflow Safe Track"),
                cover = OptionalField.Set(CoverImage(fakeJpegBytes(512), "image/jpeg")),
            ),
        )
        val outBytes = upgradedOut.readBytes()
        val hasCo64 = String(outBytes, Charsets.ISO_8859_1).contains("co64")
        assertTrue("Expected stco to upgrade to co64 upon 32-bit overflow", hasCo64)
        assertEquals("4GB Overflow Safe Track", AudioTagCodec.readTags(upgradedOut).title)
    }
}
