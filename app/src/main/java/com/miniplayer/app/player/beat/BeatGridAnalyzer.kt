package com.miniplayer.app.player.beat

import com.miniplayer.app.core.model.BeatGrid
import com.miniplayer.app.core.model.BeatGridConfidence
import com.miniplayer.app.core.model.BeatGridSource
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToLong

/** Diagnostics of one BeatGrid v2 analysis (debug screen / tests). */
data class BeatGridDiagnostics(
    val dpBeats: Int,
    val tempoFit: TempoFit?,
    val pattern: RhythmPattern?,
    val harmRhythm: Int,
    val drumPresence: Double,
    val chromaFallbackBeats: Int,
    val emptyChromaBeats: Int,
    val anchorBeatIndex: Int,
    val attackSamples: Int,
    val halfBarMargin: Double,
    val sectionNoveltyAtAnchor: Double,
    val beatTimesMs: DoubleArray = DoubleArray(0),
    val sec: DoubleArray = DoubleArray(0),
    val h1Raw: DoubleArray = DoubleArray(0),
    val path: IntArray = IntArray(0),
    /** Stage 4 features (debug screen / tests). */
    val features: BeatSyncFeatures? = null,
)

class BeatGridAnalysis(val grid: BeatGrid, val diagnostics: BeatGridDiagnostics)

/**
 * BeatGrid v2 orchestrator (§3, §5): Stage 2 tempo prior (supplied) → Stage 3 Ellis DP + robust
 * regression → Stage 4 beat-synchronous features → Stage 5 Viterbi → Stage 6 attack alignment.
 * Works identically for the mixed decode (Input A) and the `.stems` cache (Input B).
 */
object BeatGridAnalyzer {

    sealed class AnalysisInput {
        /** Front-end features from the mixed MediaCodec decode. */
        class MixPcm(val frames: BeatFeatureFrames, val exactBpm: Double, val clarity: Double) : AnalysisInput()

        /** Front-end features from the `.stems` cache. */
        class StemCache(val frames: BeatFeatureFrames, val exactBpm: Double, val clarity: Double) : AnalysisInput()
    }

    fun analyze(input: AnalysisInput, forcedBeatsPerBar: Int? = null): BeatGrid? =
        analyzeDetailed(input, forcedBeatsPerBar)?.grid

    fun analyzeDetailed(input: AnalysisInput, forcedBeatsPerBar: Int? = null): BeatGridAnalysis? = when (input) {
        is AnalysisInput.MixPcm -> run(input.frames, input.exactBpm, input.clarity, BeatGridSource.AUTO_MIX, forcedBeatsPerBar)
        is AnalysisInput.StemCache -> run(input.frames, input.exactBpm, input.clarity, BeatGridSource.AUTO_STEMS, forcedBeatsPerBar)
    }

    fun run(
        frames: BeatFeatureFrames,
        exactBpm: Double,
        clarity: Double,
        source: BeatGridSource,
        forcedBeatsPerBar: Int? = null,
    ): BeatGridAnalysis? {
        if (!(exactBpm in 30.0..300.0)) return null
        val dp = DpBeatTracker.track(frames, exactBpm) ?: return null
        val fit = TempoRegression.fit(dp.index, dp.timesMs, dp.strength)
        val periodMs = fit?.periodMs?.takeIf { it in 200.0..2000.0 } ?: (60_000.0 / exactBpm)
        val stable = fit?.tempoStable == true
        val beats = if (stable && fit != null) {
            TempoRegression.uniformBeats(fit, frames.durationMs)
        } else {
            TempoRegression.consecutiveBeats(dp.index, dp.timesMs).filter { it >= 0.0 }.toDoubleArray()
        }
        if (beats.size < 8) return null
        val feats = BeatSyncFeatures.compute(frames, beats, periodMs, dp.tau0Hops)
        val decode = DownbeatViterbi.decode(feats, forcedBeatsPerBar) ?: return null
        val bpb = decode.beatsPerBar
        val anchor = decode.anchorBeat.coerceIn(0, beats.size - 1)

        // Stage 6: all strong GROOVE beats contribute to the attack offset.
        val strengths = DoubleArray(beats.size) { frames.maxOver(frames.compositeOnset, beats[it] - 25.0, beats[it] + 25.0).toDouble() }
        val positive = strengths.filter { it > 0.0 }.toDoubleArray()
        val medStrength = if (positive.isEmpty()) 0.0 else Stats.median(positive)
        val strong = BooleanArray(beats.size) { decode.path[it] >= 0 && strengths[it] >= 0.5 * medStrength && strengths[it] > 0.05 }
        val attack = AttackAligner.align(frames, beats, strong)
        val anchorMs = (beats[anchor] + attack.attackOffsetMs - AttackAligner.PRE_ROLL_MS).roundToLong()
        val firstBeatMs = firstBeatAtOrAfterZero(anchorMs.toDouble(), periodMs)

        val bpmPrecise = (60_000.0 / periodMs).coerceIn(30.0, 300.0)
        val confidence = BeatGridConfidence(
            tempoClarity = clarity.toFloat(),
            tempoStable = stable,
            downbeatMargin = decode.downbeatMargin.toFloat(),
            halfBarAmbiguous = bpb == 4 && decode.halfBarMargin < DownbeatViterbi.HALF_BAR_AMBIGUITY_THRESHOLD,
            phraseMargin = decode.phraseMargin.toFloat(),
            attackOffsetMs = attack.attackOffsetMs.toFloat(),
            meterIrregular = decode.meterIrregular,
        )
        val grid = BeatGrid(
            bpmPrecise = bpmPrecise,
            firstBeatMs = firstBeatMs,
            firstDownbeatMs = anchorMs,
            beatsPerBar = bpb,
            phraseBars = 4,
            phraseOffsetBars = decode.phraseOffsetBars,
            confidence = confidence,
            source = source,
        )
        return BeatGridAnalysis(
            grid,
            BeatGridDiagnostics(
                dpBeats = dp.size,
                tempoFit = fit,
                pattern = decode.pattern,
                harmRhythm = feats.harmRhythm,
                drumPresence = feats.drumPresence,
                chromaFallbackBeats = feats.chromaFallbackBeats,
                emptyChromaBeats = feats.emptyChromaBeats,
                anchorBeatIndex = anchor,
                attackSamples = attack.samples,
                halfBarMargin = decode.halfBarMargin,
                sectionNoveltyAtAnchor = feats.sec[anchor],
                beatTimesMs = beats,
                sec = feats.sec,
                h1Raw = feats.h1Raw,
                path = decode.path,
                features = feats,
            ),
        )
    }

    /** First beat time `>= 0` on the grid through [anchorMs] (beats, not bars, so no bar folding). */
    fun firstBeatAtOrAfterZero(anchorMs: Double, periodMs: Double): Long {
        if (periodMs <= 1.0) return anchorMs.roundToLong().coerceAtLeast(0L)
        val t = if (anchorMs >= 0.0) {
            anchorMs - floor(anchorMs / periodMs) * periodMs
        } else {
            anchorMs + ceil(-anchorMs / periodMs) * periodMs
        }
        return t.roundToLong().coerceAtLeast(0L)
    }
}
