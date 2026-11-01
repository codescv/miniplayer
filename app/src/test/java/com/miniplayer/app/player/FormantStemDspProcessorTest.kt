package com.miniplayer.app.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import com.miniplayer.app.core.model.PracticeMath
import com.miniplayer.app.core.model.StemMixConfig
import com.miniplayer.app.core.model.StemType
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FormantStemDspProcessorTest {
    private val sampleRate = 44_100
    private val stereo44k = AudioFormat(sampleRate, 2, C.ENCODING_PCM_16BIT)

    @Test
    fun wolaIdentityAtZeroPitchAndUnityStems_reconstructsWithinLsbAndDrainsExactFrameCount() {
        // 1. RealTimeSonicAudioProcessor must bypass WOLA completely at (1.0x, 0st, unity stems)
        val rtProcessor = RealTimeSonicAudioProcessor()
        rtProcessor.configure(stereo44k)
        rtProcessor.flush()
        rtProcessor.setPreserveFormants(true)
        rtProcessor.setStemMix(StemMixConfig())
        rtProcessor.setTargetSpeedAndPitch(1.0f, 1.0f)

        val rawInput = ShortArray(4096) { i -> ((sin(2.0 * PI * 440.0 * (i / 2) / sampleRate) * 18_000.0)).toInt().toShort() }
        val inBuf = shortsToNativeBuffer(rawInput)
        rtProcessor.queueInput(inBuf)
        val outShorts = readShortsFromBuffer(rtProcessor.output)
        assertArrayEquals("Identity speed/pitch/stems must be bit-for-bit zero-latency transparent", rawInput, outShorts)
        assertFalse(rtProcessor.hasActiveDelegate)

        // 2. Direct FormantStemDspProcessor WOLA round-trip at pitch=1.0, unity stems:
        //    must output exact 1:1 frame count after drainRemaining() and reconstruct COLA within <= 2 LSB!
        val dsp = FormantStemDspProcessor()
        dsp.configure(sampleRateHz = sampleRate, channelCount = 2)
        val totalFrames = 8_192
        val stereoSignal = ShortArray(totalFrames * 2)
        for (f in 0 until totalFrames) {
            val l = (sin(2.0 * PI * 330.0 * f / sampleRate) * 14_000.0 + sin(2.0 * PI * 1_100.0 * f / sampleRate) * 6_000.0)
                .toInt().toShort()
            val r = (sin(2.0 * PI * 440.0 * f / sampleRate) * 13_000.0 + cos(2.0 * PI * 880.0 * f / sampleRate) * 5_000.0)
                .toInt().toShort()
            stereoSignal[f * 2] = l
            stereoSignal[f * 2 + 1] = r
        }

        val collected = ShortArrayCollector()
        var offset = 0
        while (offset < stereoSignal.size) {
            val chunk = minOf(1024, stereoSignal.size - offset)
            val chunkShorts = stereoSignal.copyOfRange(offset, offset + chunk)
            val out = dsp.processInterleavedPcm16(
                inputShorts = chunkShorts,
                offsetShorts = 0,
                lengthShorts = chunkShorts.size,
                pitchFactor = 1.0f,
                preserveFormants = true,
                stemMix = StemMixConfig(),
                stemMaskGrid = null,
                playheadPositionMs = 0L,
            )
            collected.append(out)
            offset += chunk
        }
        collected.append(
            dsp.drainRemaining(
                pitchFactor = 1.0f,
                preserveFormants = true,
                stemMix = StemMixConfig(),
                stemMaskGrid = null,
                playheadPositionMs = 0L,
            ),
        )

        val reconstructed = collected.toShortArray()
        assertEquals("WOLA + drainRemaining must preserve exact 1:1 stereo sample count", stereoSignal.size, reconstructed.size)

        var maxInteriorError = 0
        // Check steady-state interior frames [1536 .. totalFrames - 1536]
        for (i in (1536 * 2) until ((totalFrames - 1536) * 2)) {
            val err = abs(stereoSignal[i].toInt() - reconstructed[i].toInt())
            if (err > maxInteriorError) maxInteriorError = err
        }
        assertTrue("Expected WOLA COLA reconstruction error <= 2 LSB, got $maxInteriorError", maxInteriorError <= 2)
    }

    @Test
    fun formantPreservingPitchShiftMinus4Semitones_preservesVocalFormantPeakComparedToUncompensatedShift() {
        // Synthesize a harmonic sung vowel (F0 = 220 Hz) shaped by a sharp vocal-tract formant peak at 1050 Hz
        val targetFormantHz = 1_050.0
        val vocalStereo = synthesizeFormantVowelStereo(
            f0Hz = 220.0,
            formantPeakHz = targetFormantHz,
            formantBandwidthHz = 170.0,
            durationSec = 1.2,
            sampleRate = sampleRate,
        )
        val minusFourPitch = PracticeMath.computePitchFactor(-4, 0) // 2^(-4/12) ≈ 0.7937

        val uncompensatedOut = runRealTimeProcessor(
            inputStereo = vocalStereo,
            speed = 1.0f,
            pitch = minusFourPitch,
            preserveFormants = false,
            stemMix = StemMixConfig(),
        )
        val formantPreservedOut = runRealTimeProcessor(
            inputStereo = vocalStereo,
            speed = 1.0f,
            pitch = minusFourPitch,
            preserveFormants = true,
            stemMix = StemMixConfig(),
        )

        val uncompCentroidHz = computeBandSpectralCentroidHz(uncompensatedOut, sampleRate, minHz = 550.0, maxHz = 1_550.0)
        val preservedCentroidHz = computeBandSpectralCentroidHz(formantPreservedOut, sampleRate, minHz = 550.0, maxHz = 1_550.0)

        // Without formant preservation, -4 st scales the 1050 Hz formant peak down by 0.7937 -> ~833 Hz.
        // With zero-phase STFT formant preservation, the vocal formant envelope stays restored near ~1050 Hz!
        assertTrue(
            "Expected uncompensated -4st formant centroid to drop near ~833 Hz (< 920 Hz), got $uncompCentroidHz Hz",
            uncompCentroidHz < 920.0,
        )
        assertTrue(
            "Expected formant-preserved -4st centroid ($preservedCentroidHz Hz) to stay significantly higher than uncompensated ($uncompCentroidHz Hz) and near ~1050 Hz",
            preservedCentroidHz > uncompCentroidHz + 95.0 && abs(preservedCentroidHz - targetFormantHz) < 120.0,
        )
    }

    @Test
    fun fiveStemSoftMasks_strictlySumToUnityAcrossAllBinsAndApplyMidSideSeparation() {
        // 1. Partition-of-unity check across all 513 STFT bins and feature combinations
        val masks = FloatArray(FormantStemDspProcessor.NUM_STEMS)
        val freqPerBin = sampleRate.toFloat() / FormantStemDspProcessor.FFT_SIZE.toFloat()
        val samplePrior = floatArrayOf(0.45f, 0.20f, 0.15f, 0.10f, 0.10f)

        for (bin in 0 until FormantStemDspProcessor.NUM_BINS) {
            val freqHz = bin * freqPerBin
            for (centerStep in 0..4) {
                val centerAffinity = centerStep * 0.25f
                for (percStep in 0..4) {
                    val percussiveness = percStep * 0.25f
                    FormantStemDspProcessor.computeBinSoftStemMasks(
                        freqHz = freqHz,
                        centerAffinity = centerAffinity,
                        percussiveRatio = percussiveness,
                        neuralPriors5 = if (centerStep % 2 == 0) samplePrior else null,
                        neuralOffset = 0,
                        outMasks5 = masks,
                    )
                    var sum = 0f
                    for (m in masks) {
                        assertTrue("Mask must be non-negative at bin=$bin", m >= -1e-6f)
                        sum += m
                    }
                    assertEquals("5 stem soft masks must sum strictly to 1.0 at bin=$bin", 1.0f, sum, 1e-4f)
                }
            }
        }

        // 2. StemMixConfig mute, solo, and 0%..150% gain math + storage codec
        val unityMix = StemMixConfig()
        assertTrue(unityMix.isUnity)
        val boostedGuitarMutedVocals = StemMixConfig(
            vocals = 1.0f,
            guitar = 1.45f,
            bass = 0.80f,
            mutedStems = setOf(StemType.VOCALS),
        )
        assertFalse(boostedGuitarMutedVocals.isUnity)
        assertEquals(0f, boostedGuitarMutedVocals.effectiveGain(StemType.VOCALS), 1e-5f)
        assertEquals(1.45f, boostedGuitarMutedVocals.effectiveGain(StemType.GUITAR), 1e-5f)

        val soloBass = boostedGuitarMutedVocals.copy(soloStem = StemType.BASS)
        assertEquals(0.80f, soloBass.effectiveGain(StemType.BASS), 1e-5f)
        assertEquals(0f, soloBass.effectiveGain(StemType.GUITAR), 1e-5f)
        assertEquals(0f, soloBass.effectiveGain(StemType.DRUMS), 1e-5f)

        val encoded = soloBass.toStorageString()
        val decoded = StemMixConfig.fromStorageString(encoded)
        assertEquals(soloBass, decoded)

        // 3. Stereo Mid/Side Stem Separation test:
        //    Center-panned harmonic vocal (L == R at 620 Hz + 1240 Hz) + Wide side guitar (L == -R at 370 Hz + 740 Hz)
        val totalFrames = 16_384
        val compositeStereo = ShortArray(totalFrames * 2)
        for (f in 0 until totalFrames) {
            val vocalMid = sin(2.0 * PI * 620.0 * f / sampleRate) * 9_500.0 +
                sin(2.0 * PI * 1_240.0 * f / sampleRate) * 4_500.0
            val guitarSide = sin(2.0 * PI * 370.0 * f / sampleRate) * 9_000.0 +
                sin(2.0 * PI * 740.0 * f / sampleRate) * 4_200.0
            compositeStereo[f * 2] = (vocalMid + guitarSide).toInt().coerceIn(-32767, 32767).toShort()
            compositeStereo[f * 2 + 1] = (vocalMid - guitarSide).toInt().coerceIn(-32767, 32767).toShort()
        }

        val vocalsMutedOut = runRealTimeProcessor(
            inputStereo = compositeStereo,
            speed = 1.0f,
            pitch = 1.0f,
            preserveFormants = true,
            stemMix = StemMixConfig(mutedStems = setOf(StemType.VOCALS)),
        )
        val guitarMutedOut = runRealTimeProcessor(
            inputStereo = compositeStereo,
            speed = 1.0f,
            pitch = 1.0f,
            preserveFormants = true,
            stemMix = StemMixConfig(mutedStems = setOf(StemType.GUITAR)),
        )

        val (origMidRms, origSideRms) = computeMidSideRms(compositeStereo)
        val (vocMutedMidRms, vocMutedSideRms) = computeMidSideRms(vocalsMutedOut)
        val (gtrMutedMidRms, gtrMutedSideRms) = computeMidSideRms(guitarMutedOut)

        // Muting VOCALS strongly suppresses Center/Mid while keeping Side guitar intact!
        assertTrue(
            "Expected vocal mute to attenuate Mid strongly ($vocMutedMidRms vs $origMidRms)",
            vocMutedMidRms < origMidRms * 0.42,
        )
        assertTrue(
            "Expected vocal mute to preserve Side guitar ($vocMutedSideRms vs $origSideRms)",
            vocMutedSideRms > origSideRms * 0.72,
        )

        // Muting GUITAR strongly suppresses Side guitar while keeping Center vocal intact!
        assertTrue(
            "Expected guitar mute to attenuate Side guitar ($gtrMutedSideRms vs $origSideRms)",
            gtrMutedSideRms < origSideRms * 0.45,
        )
        assertTrue(
            "Expected guitar mute to preserve Mid vocal ($gtrMutedMidRms vs $origMidRms)",
            gtrMutedMidRms > origMidRms * 0.72,
        )
    }

    @Test
    fun pretrainedStemNetBinaryAndOfflineStemSeparationCache_roundTripAndMatchBundledAsset() {
        val weights = StemModelManager.generatePretrainedStemNetWeights()
        assertEquals("Pretrained stem-net binary must be exact 64 KB (65,536 bytes)", 65_536, weights.size)

        val model = StemModelManager.parseStemNetWeights(weights)
        assertNotNull("StemNetModel must parse valid MPSN header and CRC32", model)

        // Ensure bundled asset in app/src/main/assets/miniplayer-stem-net-v1.bin matches byte-for-byte
        val assetFile = sequenceOf(
            File("src/main/assets/${StemModelManager.MODEL_FILE_NAME}"),
            File("app/src/main/assets/${StemModelManager.MODEL_FILE_NAME}"),
        ).firstOrNull { it.isFile }
        assertNotNull("Bundled ${StemModelManager.MODEL_FILE_NAME} asset must exist in src/main/assets", assetFile)
        assertArrayEquals(
            "Bundled ${StemModelManager.MODEL_FILE_NAME} must match deterministic pretrained weights",
            weights,
            assetFile!!.readBytes(),
        )

        // Run offline 25 Hz (40 ms hop) 16-band neural-spectral stem analyzer on a 3.0s multi-stem stereo clip
        val durationSec = 3.0
        val totalFrames = (durationSec * sampleRate).toInt()
        val multiStemPcm = ShortArray(totalFrames * 2)
        for (f in 0 until totalFrames) {
            val t = f.toDouble() / sampleRate
            // Sub-bass line at 82 Hz (E2)
            val bass = sin(2.0 * PI * 82.4 * t) * 8_000.0
            // Center vocal harmonic note at 523 Hz (C5) during [0.8s .. 2.2s]
            val vocal = if (t in 0.8..2.2) {
                sin(2.0 * PI * 523.25 * t) * 8_500.0 + sin(2.0 * PI * 1_046.5 * t) * 4_000.0
            } else {
                0.0
            }
            // Wide stereo guitar chord
            val guitarSide = sin(2.0 * PI * 329.6 * t) * 6_500.0
            multiStemPcm[f * 2] = (bass + vocal + guitarSide).toInt().coerceIn(-32767, 32767).toShort()
            multiStemPcm[f * 2 + 1] = (bass + vocal - guitarSide).toInt().coerceIn(-32767, 32767).toShort()
        }

        val engine = StemSeparationEngine()
        val grid = engine.analyzePcmInterleaved(
            itemId = 42L,
            pcmInterleaved = multiStemPcm,
            sampleRateHz = sampleRate,
            channelCount = 2,
            model = model!!,
        )
        assertTrue("Expected ~75 frames at 25 Hz for 3.0s audio, got ${grid.numFrames}", grid.numFrames in 70..78)

        // Verify GZIP .stm binary round-trip and exact uint8 sum == 255 invariant
        val compressedStm = grid.toBytes(compress = true)
        assertTrue("Compressed .stm cache for 3s clip should be compact (< 8 KB), got ${compressedStm.size}", compressedStm.size in 32..8_192)
        val restored = StemTrackMaskGrid.fromBytes(compressedStm)
        assertNotNull(restored)
        assertEquals(42L, restored!!.itemId)
        assertEquals(grid.numFrames, restored.numFrames)
        assertArrayEquals(grid.quantizedMasks, restored.quantizedMasks)

        val lookup = FloatArray(FormantStemDspProcessor.NUM_BARK_BANDS * FormantStemDspProcessor.NUM_STEMS)
        assertTrue(restored.lookupBandMasksAt(1_500L, lookup))
        for (band in 0 until FormantStemDspProcessor.NUM_BARK_BANDS) {
            var bandSum = 0f
            for (s in 0 until FormantStemDspProcessor.NUM_STEMS) {
                bandSum += lookup[band * FormantStemDspProcessor.NUM_STEMS + s]
            }
            assertEquals("Interpolated neural band prior must sum to 1.0 at band=$band", 1.0f, bandSum, 1e-3f)
        }
    }

    private fun runRealTimeProcessor(
        inputStereo: ShortArray,
        speed: Float,
        pitch: Float,
        preserveFormants: Boolean,
        stemMix: StemMixConfig,
    ): ShortArray {
        val processor = RealTimeSonicAudioProcessor()
        processor.configure(stereo44k)
        processor.flush()
        processor.setPreserveFormants(preserveFormants)
        processor.setStemMix(stemMix)
        processor.setTargetSpeedAndPitch(speed, pitch)

        val outCollector = ShortArrayCollector()
        var offset = 0
        val chunkShorts = 2048
        while (offset < inputStereo.size) {
            val len = minOf(chunkShorts, inputStereo.size - offset)
            val buf = shortsToNativeBuffer(inputStereo.copyOfRange(offset, offset + len))
            processor.queueInput(buf)
            outCollector.append(readShortsFromBuffer(processor.output))
            offset += len
        }
        processor.queueEndOfStream()
        outCollector.append(readShortsFromBuffer(processor.output))
        return outCollector.toShortArray()
    }

    private fun synthesizeFormantVowelStereo(
        f0Hz: Double,
        formantPeakHz: Double,
        formantBandwidthHz: Double,
        durationSec: Double,
        sampleRate: Int,
    ): ShortArray {
        val frames = (durationSec * sampleRate).toInt()
        val out = ShortArray(frames * 2)
        val maxHarmonic = (6_000.0 / f0Hz).toInt()
        val harmonicAmps = DoubleArray(maxHarmonic + 1)
        for (h in 1..maxHarmonic) {
            val freq = h * f0Hz
            val z = (freq - formantPeakHz) / formantBandwidthHz
            // Strong vocal tract formant resonance centered at formantPeakHz + gentle rolloff
            harmonicAmps[h] = exp(-0.5 * z * z) + 0.04 / sqrt(h.toDouble())
        }

        var maxPeak = 1e-6
        val mono = DoubleArray(frames)
        for (n in 0 until frames) {
            val t = n.toDouble() / sampleRate
            var v = 0.0
            for (h in 1..maxHarmonic) {
                v += harmonicAmps[h] * sin(2.0 * PI * (h * f0Hz) * t + h * 0.37)
            }
            mono[n] = v
            maxPeak = max(maxPeak, abs(v))
        }
        val scale = 20_000.0 / maxPeak
        for (n in 0 until frames) {
            val s = (mono[n] * scale).toInt().coerceIn(-32767, 32767).toShort()
            out[n * 2] = s
            out[n * 2 + 1] = s
        }
        return out
    }

    private fun computeBandSpectralCentroidHz(
        stereoPcm: ShortArray,
        sampleRate: Int,
        minHz: Double,
        maxHz: Double,
    ): Double {
        val totalFrames = stereoPcm.size / 2
        val fftSize = 1024
        val hop = 512
        val re = FloatArray(fftSize)
        val im = FloatArray(fftSize)
        val accumPower = DoubleArray(fftSize / 2 + 1)
        val minBin = (minHz * fftSize / sampleRate).toInt().coerceAtLeast(1)
        val maxBin = (maxHz * fftSize / sampleRate).toInt().coerceAtMost(fftSize / 2)

        var start = totalFrames / 6
        val end = (totalFrames * 5) / 6 - fftSize
        while (start <= end) {
            for (n in 0 until fftSize) {
                val w = 0.5 * (1.0 - cos(2.0 * PI * n / fftSize))
                val idx = (start + n) * 2
                val mono = 0.5 * (stereoPcm[idx].toDouble() + stereoPcm[idx + 1].toDouble()) / 32768.0
                re[n] = (mono * w).toFloat()
                im[n] = 0f
            }
            FormantStemDspProcessor.fft1024(re, im, inverse = false)
            for (k in minBin..maxBin) {
                accumPower[k] += (re[k] * re[k] + im[k] * im[k]).toDouble()
            }
            start += hop
        }

        // Smooth power spectrum slightly across harmonic comb teeth to measure true formant centroid
        var weightedFreq = 0.0
        var totalWeight = 0.0
        for (k in minBin..maxBin) {
            val p = accumPower[k]
            val freq = k.toDouble() * sampleRate / fftSize
            weightedFreq += freq * p
            totalWeight += p
        }
        return if (totalWeight > 0.0) weightedFreq / totalWeight else 0.0
    }

    private fun computeMidSideRms(stereoPcm: ShortArray): Pair<Double, Double> {
        val totalFrames = stereoPcm.size / 2
        val start = totalFrames / 8
        val end = (totalFrames * 7) / 8
        var midSq = 0.0
        var sideSq = 0.0
        var count = 0
        for (f in start until end) {
            val l = stereoPcm[f * 2].toDouble() / 32768.0
            val r = stereoPcm[f * 2 + 1].toDouble() / 32768.0
            val m = 0.5 * (l + r)
            val s = 0.5 * (l - r)
            midSq += m * m
            sideSq += s * s
            count++
        }
        return sqrt(midSq / max(1, count)) to sqrt(sideSq / max(1, count))
    }

    private fun shortsToNativeBuffer(shorts: ShortArray): ByteBuffer {
        val buf = ByteBuffer.allocateDirect(shorts.size * 2).order(ByteOrder.nativeOrder())
        buf.asShortBuffer().put(shorts)
        buf.limit(shorts.size * 2)
        buf.position(0)
        return buf
    }

    private fun readShortsFromBuffer(buf: ByteBuffer): ShortArray {
        if (!buf.hasRemaining()) return ShortArray(0)
        val dup = buf.slice().order(ByteOrder.nativeOrder()).asShortBuffer()
        val out = ShortArray(dup.remaining())
        dup.get(out)
        buf.position(buf.limit())
        return out
    }

    private class ShortArrayCollector {
        private val bos = ByteArrayOutputStream(32_768)
        fun append(shorts: ShortArray) {
            if (shorts.isEmpty()) return
            val tmp = ByteBuffer.allocate(shorts.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            tmp.asShortBuffer().put(shorts)
            bos.write(tmp.array())
        }
        fun toShortArray(): ShortArray {
            val bytes = bos.toByteArray()
            val sb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            val out = ShortArray(sb.remaining())
            sb.get(out)
            return out
        }
    }
}
