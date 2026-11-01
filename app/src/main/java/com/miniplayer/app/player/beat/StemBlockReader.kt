package com.miniplayer.app.player.beat

import com.miniplayer.app.core.model.BeatGrid
import com.miniplayer.app.core.model.StemType
import com.miniplayer.app.player.OnsetFluxAccumulator
import com.miniplayer.app.player.StemSeparatedTrackCache
import kotlin.math.abs

/**
 * Block-wise bulk reader over a memory-mapped [StemSeparatedTrackCache] (§3.1 Input B). Decodes
 * only the requested stems (never `VOCALS` for beat analysis), one 64-frame block at a time.
 */
class StemBlockReader(private val cache: StemSeparatedTrackCache) {
    val sampleRate: Int get() = cache.sampleRate
    val totalFrames: Int get() = cache.totalFrames

    /** Mono `(L + R)/2` of [stem] for frames `[startFrame, startFrame + frameCount)`. */
    fun readMono(stem: StemType, startFrame: Int, frameCount: Int): FloatArray {
        val out = FloatArray(frameCount.coerceAtLeast(0))
        if (frameCount <= 0) return out
        val stemIdx = stem.ordinal
        val block = FloatArray(StemSeparatedTrackCache.BLOCK_FRAMES)
        var frame = startFrame.coerceAtLeast(0)
        val end = minOf(cache.totalFrames, startFrame + frameCount)
        while (frame < end) {
            val bIdx = frame / StemSeparatedTrackCache.BLOCK_FRAMES
            val bStart = bIdx * StemSeparatedTrackCache.BLOCK_FRAMES
            val n = cache.decodeBlockMono(bIdx, stemIdx, block, 0)
            if (n <= 0) break
            val from = frame - bStart
            val upto = minOf(n, end - bStart)
            for (i in from until upto) out[bStart + i - startFrame] = block[i]
            frame = bStart + upto
        }
        return out
    }

    /**
     * Runs BeatGrid v2 on the stems: `DRUMS` → onset/attack path, `BASS` → bass PCP,
     * `0.4·BASS + GUITAR + OTHER` → chroma, instrumental sum (no `VOCALS`, which is never decoded) →
     * accent / `Step`. Falls back to the full sum
     * for the onset path when the drum stem is (near) silent.
     */
    fun analyzeBeatGrid(
        maxSeconds: Double = 45.0,
        embeddedBpm: Double? = null,
        forcedBeatsPerBar: Int? = null,
    ): BeatGridAnalysis? {
        val frames = (maxSeconds * sampleRate).toInt().coerceAtMost(totalFrames)
        if (frames < sampleRate * 3) return null
        val drums = readMono(StemType.DRUMS, 0, frames)
        val bass = readMono(StemType.BASS, 0, frames)
        val guitar = readMono(StemType.GUITAR, 0, frames)
        val other = readMono(StemType.OTHER, 0, frames)
        var drumPeak = 0f
        var sumPeak = 0f
        for (i in 0 until frames) {
            drumPeak = maxOf(drumPeak, abs(drums[i]))
            sumPeak = maxOf(sumPeak, abs(drums[i] + bass[i] + guitar[i] + other[i]))
        }
        val useDrums = drumPeak >= 0.08f * sumPeak && drumPeak > 1e-4f
        val acc = OnsetFluxAccumulator(sampleRate, 1, enableTonal = false)
        val tonal = TonalStreamBuilder(sampleRate, maxSeconds + 1.0)
        val hop = acc.hopSamples
        val accent = FloatSink((frames / hop) + 2)
        var accentSum = 0.0
        var accentCount = 0
        val chunk = ShortArray(4096)
        var chunkLen = 0
        for (i in 0 until frames) {
            val sum = drums[i] + bass[i] + guitar[i] + other[i]
            val onsetSrc = if (useDrums) drums[i] else sum
            chunk[chunkLen++] = (onsetSrc.coerceIn(-1f, 1f) * 32767f).toInt().toShort()
            if (chunkLen == chunk.size) {
                acc.feedInterleavedPcm(chunk, chunkLen)
                chunkLen = 0
            }
            tonal.pushChroma(0.4f * bass[i] + guitar[i] + other[i])
            tonal.pushBass(bass[i])
            accentSum += abs(sum)
            accentCount++
            if (accentCount == hop) {
                accent.add((accentSum / hop).toFloat())
                accentSum = 0.0
                accentCount = 0
            }
        }
        if (chunkLen > 0) acc.feedInterleavedPcm(chunk, chunkLen)
        val tempo = acc.computeBpmWithClarity() ?: return null
        val bpm = BpmOctave.resolve(embeddedBpm, tempo.exactBpm)
        val featureFrames = acc.buildFeatureFrames(tonalOverride = tonal.build(), accentOverride = accent.toArray()) ?: return null
        return BeatGridAnalyzer.analyzeDetailed(
            BeatGridAnalyzer.AnalysisInput.StemCache(featureFrames, bpm, tempo.clarity),
            forcedBeatsPerBar,
        )
    }

    fun analyze(maxSeconds: Double = 45.0, embeddedBpm: Double? = null): BeatGrid? =
        analyzeBeatGrid(maxSeconds, embeddedBpm)?.grid
}

/** Embedded-tag octave disambiguation (§3.0): the tag never overrides the acoustic tempo. */
object BpmOctave {
    fun resolve(tagBpm: Double?, acousticBpm: Double): Double {
        val tag = tagBpm?.takeIf { it in 30.0..300.0 } ?: return acousticBpm
        var best = acousticBpm
        var bestErr = Double.MAX_VALUE
        for (mult in doubleArrayOf(0.5, 1.0, 2.0)) {
            val cand = acousticBpm * mult
            if (cand !in 30.0..300.0) continue
            val err = abs(kotlin.math.ln(cand / tag))
            if (err < bestErr) {
                bestErr = err
                best = cand
            }
        }
        // Only switch octave when the tag clearly names that octave (within ±4 %).
        return if (bestErr < kotlin.math.ln(1.04)) best else acousticBpm
    }
}
