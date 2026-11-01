package com.miniplayer.app.player.beat

import kotlin.math.abs
import kotlin.math.max

/** Rhythmic pattern hypotheses of Stage 5. */
enum class RhythmPattern { NORMAL, HALF_TIME, WALTZ }

/** Stage 5 decode result. */
class DownbeatDecode(
    val beatsPerBar: Int,
    val pattern: RhythmPattern,
    val totalScore: Double,
    /** Per beat: -1 = PRE, else phrase-phase state `k`. */
    val path: IntArray,
    /** First GROOVE beat with `b = 0`, or -1. */
    val anchorBeat: Int,
    val anchorPhraseBar: Int,
    val downbeatMargin: Double,
    val halfBarMargin: Double,
    val phraseMargin: Double,
    val meterIrregular: Boolean,
) {
    val phraseOffsetBars: Int get() = Math.floorMod(-anchorPhraseBar, 4)
}

/**
 * Beat-synchronous pattern-aware section + bar + phrase Viterbi (§3.5).
 *
 * State `(g, k)`, `g ∈ {PRE, GROOVE}`, `k ∈ 0 until 4·P` (`b = k mod P`, `r = k div P`). `b` is
 * strictly deterministic in GROOVE; the only other GROOVE move is the phrase reset `(r, P−1) → (0, 0)`.
 */
object DownbeatViterbi {
    const val W_K0 = 1.0
    const val W_S0 = 0.8
    const val W_H1 = 1.2
    const val W_B = 0.8
    const val W_CAD = 0.4
    const val W_SEC = 0.7
    const val W_F = 0.5
    const val W_C = 0.4
    const val W_E = 0.8
    const val W_CLICK = 1.0
    const val W_NN = 1.0
    const val CAD_PATH_CAP = 1.5
    /**
     * Structural Beat 1 vs Beat 3 margin below which the grid is flagged `halfBarAmbiguous`
     * (design: "2.0 (tune)"). Tuned on the §4 synthetic set: symmetric half-bar material without
     * fills/cadence leaves 0.1–2.3 of residual evidence over a 45 s window, while a single fill→crash
     * every 4 bars gives ≥ 8.
     */
    const val HALF_BAR_AMBIGUITY_THRESHOLD = 3.0
    private const val PHRASE_RESET = -3.5
    private const val NEG_INF = -1e18

    private class Templates(
        val p: Int,
        val tauK: DoubleArray,
        val tauS: DoubleArray,
        val tauH: DoubleArray,
        val tauF: DoubleArray,
        val kCad: DoubleArray,
        val kSec: DoubleArray,
        val kC: DoubleArray,
        val kE: DoubleArray,
    )

    private fun phraseTemplate(p: Int, first: Double, downbeat: Double): DoubleArray =
        Stats.zm(DoubleArray(4 * p) { k -> if (k == 0) first else if (k % p == 0) downbeat else 0.0 })

    private fun templates(p: Int, pattern: RhythmPattern, harmRhythm: Int): Templates {
        val tauH = when {
            p != 4 -> if (harmRhythm in 0..1) DoubleArray(p) else Stats.zm(DoubleArray(p) { if (it == 0) 1.0 else -1.0 })
            harmRhythm == 2 -> Stats.zm(doubleArrayOf(1.0, -1.0, 1.0, -1.0))
            harmRhythm <= 1 -> DoubleArray(4)
            else -> Stats.zm(doubleArrayOf(1.0, -1.0, 0.35, -1.0))
        }
        val (k, s) = when (pattern) {
            RhythmPattern.NORMAL -> doubleArrayOf(1.0, 0.0, 0.7, 0.0) to doubleArrayOf(0.0, 1.0, 0.0, 1.0)
            RhythmPattern.HALF_TIME -> doubleArrayOf(1.0, 0.0, 0.0, 0.0) to doubleArrayOf(0.0, 0.0, 1.0, 0.0)
            RhythmPattern.WALTZ -> doubleArrayOf(1.0, 0.0, 0.0) to doubleArrayOf(0.0, 1.0, 1.0)
        }
        return Templates(
            p = p,
            tauK = Stats.zm(k),
            tauS = Stats.zm(s),
            tauH = tauH,
            tauF = Stats.zm(DoubleArray(p) { if (it == 0) 1.0 else 0.0 }),
            kCad = phraseTemplate(p, 1.0, 0.0),
            kSec = phraseTemplate(p, 1.0, 0.0),
            kC = phraseTemplate(p, 1.0, 0.6),
            kE = phraseTemplate(p, 1.0, 0.5),
        )
    }

    /** Decodes 4/4 (patterns A/B) and 3/4; 3/4 wins only by a per-beat margin. */
    fun decode(f: BeatSyncFeatures, forcedBeatsPerBar: Int? = null): DownbeatDecode? {
        if (f.size < 4) return null
        val candidates = ArrayList<DownbeatDecode>()
        if (forcedBeatsPerBar == null || forcedBeatsPerBar == 4) {
            decodeOne(f, 4, RhythmPattern.NORMAL)?.let(candidates::add)
            decodeOne(f, 4, RhythmPattern.HALF_TIME)?.let(candidates::add)
        }
        if (forcedBeatsPerBar == null || forcedBeatsPerBar == 3) {
            decodeOne(f, 3, RhythmPattern.WALTZ)?.let(candidates::add)
        }
        if (candidates.isEmpty()) return null
        val best4 = candidates.filter { it.beatsPerBar == 4 }.maxByOrNull { it.totalScore }
        val best3 = candidates.filter { it.beatsPerBar == 3 }.maxByOrNull { it.totalScore }
        return when {
            best4 == null -> best3
            best3 == null -> best4
            best3.totalScore - best4.totalScore > WALTZ_MARGIN_PER_BEAT * f.size -> best3
            else -> best4
        }
    }

    private const val WALTZ_MARGIN_PER_BEAT = 0.08

    /**
     * Debug/diagnostics: per-feature emission totals for an all-GROOVE path whose state at beat 0
     * is [startK] (so `startK` and `startK + 2` compare the Beat 1 vs Beat 3 hypotheses).
     */
    internal fun featureContributions(f: BeatSyncFeatures, p: Int, pattern: RhythmPattern, startK: Int): Map<String, Double> {
        val t = templates(p, pattern, f.harmRhythm)
        val ks = 4 * p
        val out = linkedMapOf("K" to 0.0, "S" to 0.0, "H1" to 0.0, "Bj" to 0.0, "Cad" to 0.0, "Sec" to 0.0, "Fill" to 0.0, "Crash" to 0.0, "Step" to 0.0)
        val wCad = if (f.harmRhythm >= 4) W_CAD else 0.0
        for (i in 0 until f.size) {
            val k = Math.floorMod(startK + i, ks)
            val b = k % p
            out["K"] = out["K"]!! + W_K0 * f.drumPresence * f.k[i] * t.tauK[b]
            out["S"] = out["S"]!! + W_S0 * f.drumPresence * f.s[i] * t.tauS[b]
            out["H1"] = out["H1"]!! + W_H1 * f.h1[i] * t.tauH[b]
            out["Bj"] = out["Bj"]!! + W_B * f.bj[i] * t.tauH[b]
            out["Cad"] = out["Cad"]!! + wCad * f.cad[i] * t.kCad[k]
            out["Sec"] = out["Sec"]!! + W_SEC * f.sec[i] * t.kSec[k]
            out["Fill"] = out["Fill"]!! + W_F * f.fill[i] * t.tauF[b]
            out["Crash"] = out["Crash"]!! + W_C * f.crash[i] * t.kC[k]
            out["Step"] = out["Step"]!! + W_E * f.step[i] * t.kE[k]
        }
        return out
    }

    internal fun decodeOne(f: BeatSyncFeatures, p: Int, pattern: RhythmPattern): DownbeatDecode? {
        val m = f.size
        val t = templates(p, pattern, f.harmRhythm)
        val ks = 4 * p
        val wK = W_K0 * f.drumPresence
        val wS = W_S0 * f.drumPresence
        // Cadence: tie-breaker only, off for harmRhythm <= 2, path contribution capped at ±1.5.
        var wCad = if (f.harmRhythm >= 4) W_CAD else 0.0
        if (wCad > 0.0) {
            val maxK = t.kCad.maxOf { abs(it) }
            val total = f.cad.sumOf { abs(it) } * wCad * maxK
            if (total > CAD_PATH_CAP) wCad *= CAD_PATH_CAP / total
        }
        val emit = Array(m) { DoubleArray(ks) }
        val pre = DoubleArray(m)
        for (i in 0 until m) {
            for (k in 0 until ks) {
                val b = k % p
                var e = wK * f.k[i] * t.tauK[b] + wS * f.s[i] * t.tauS[b] +
                    W_H1 * f.h1[i] * t.tauH[b] + W_B * f.bj[i] * t.tauH[b] +
                    wCad * f.cad[i] * t.kCad[k] +
                    W_SEC * f.sec[i] * t.kSec[k] +
                    W_F * f.fill[i] * t.tauF[b] +
                    W_C * f.crash[i] * t.kC[k] +
                    W_E * f.step[i] * t.kE[k] -
                    W_CLICK * f.click[i]
                val da = f.downbeatAct
                val ba = f.beatAct
                if (da != null && ba != null) e += W_NN * (da[i] * t.tauH[b] + ba[i])
                emit[i][k] = e
            }
            pre[i] = -0.3 + W_CLICK * f.click[i] - 0.5 * (if (f.tonal[i]) 1.0 else 0.0) * f.a[i]
        }
        fun entry(k: Int): Double = when {
            k == 0 -> -1.0
            k % p == 0 -> -1.5
            else -> -2.5
        }

        // Forward max-product.
        val dG = Array(m) { DoubleArray(ks) }
        val dP = DoubleArray(m)
        val bG = Array(m) { IntArray(ks) } // predecessor: k' >= 0 groove, -1 pre
        dP[0] = pre[0]
        // Initial GROOVE −0.5; a track that opens straight into the groove *is* an entrance, so the
        // same relative entry preference (k = 0 over other bar-starts over off-beats) applies.
        for (k in 0 until ks) dG[0][k] = -0.5 + (entry(k) - entry(0)) + emit[0][k]
        for (i in 1 until m) {
            dP[i] = dP[i - 1] + pre[i]
            for (k in 0 until ks) {
                val prevK = (k - 1 + ks) % ks
                var best = dG[i - 1][prevK]
                var arg = prevK
                if (k == 0) {
                    for (r in 0 until 3) {
                        val kk = r * p + (p - 1)
                        val v = dG[i - 1][kk] + PHRASE_RESET
                        if (v > best) {
                            best = v
                            arg = kk
                        }
                    }
                }
                val vPre = dP[i - 1] + entry(k)
                if (vPre > best) {
                    best = vPre
                    arg = -1
                }
                dG[i][k] = best + emit[i][k]
                bG[i][k] = arg
            }
        }
        // Backward (max-sum, excluding emission at i).
        val bwG = Array(m) { DoubleArray(ks) }
        val bwP = DoubleArray(m)
        for (i in m - 2 downTo 0) {
            for (k in 0 until ks) {
                val nk = (k + 1) % ks
                var best = bwG[i + 1][nk] + emit[i + 1][nk]
                if (k % p == p - 1 && k != ks - 1) {
                    val v = bwG[i + 1][0] + emit[i + 1][0] + PHRASE_RESET
                    if (v > best) best = v
                }
                bwG[i][k] = best
            }
            var bestP = bwP[i + 1] + pre[i + 1]
            for (k in 0 until ks) {
                val v = bwG[i + 1][k] + emit[i + 1][k] + entry(k)
                if (v > bestP) bestP = v
            }
            bwP[i] = bestP
        }
        // Terminate + backtrack.
        var endK = -1
        var total = dP[m - 1]
        for (k in 0 until ks) if (dG[m - 1][k] > total) {
            total = dG[m - 1][k]
            endK = k
        }
        val path = IntArray(m)
        var cur = endK
        for (i in m - 1 downTo 0) {
            path[i] = cur
            if (i == 0) break
            cur = if (cur >= 0) bG[i][cur] else -1
        }
        var anchor = path.indices.firstOrNull { path[it] >= 0 && path[it] % p == 0 } ?: -1
        if (anchor < 0) {
            // No GROOVE on the path (very quiet / clicks only): take the best max-marginal downbeat.
            anchor = 0
            var bestV = NEG_INF
            for (i in 0 until minOf(m, 4 * p)) for (k in 0 until ks step p) {
                val v = dG[i][k] + bwG[i][k]
                if (v > bestV) {
                    bestV = v
                    anchor = i
                }
            }
        }
        // Max-marginal margins at the anchor.
        val mm = DoubleArray(ks) { dG[anchor][it] + bwG[anchor][it] }
        val byB = DoubleArray(p) { b -> (0 until 4).maxOf { r -> mm[r * p + b] } }
        val sorted = byB.sortedDescending()
        val bestB = byB.indices.maxByOrNull { byB[it] } ?: 0
        val downbeatMargin = if (p > 1) byB[bestB] - sorted[1] else 0.0
        // Structural Beat 1 vs Beat 3 margin: path vs the same path shifted by two beats, excluding
        // the first bar after the GROOVE entry so the entrance tie-break alone never hides ambiguity.
        val halfBarMargin = if (p == 4) {
            // The Beat-3 hypothesis may pick its own phrase phase: compare against its best one.
            (0 until 4).minOf { r -> structuralShiftMargin(emit, path, anchor + 4, 2 + 4 * r) }
        } else {
            downbeatMargin
        }
        val anchorK = if (path[anchor] >= 0) path[anchor] else (0 until 4).maxByOrNull { mm[it * p] }!! * p
        val rStar = anchorK / p
        val byR = DoubleArray(4) { mm[it * p] }
        val phraseMargin = byR[rStar] - (0 until 4).filter { it != rStar }.maxOf { byR[it] }
        val irregular = meterPostCheck(emit, path, anchor, p)
        return DownbeatDecode(
            beatsPerBar = p,
            pattern = pattern,
            totalScore = total,
            path = path,
            anchorBeat = anchor,
            anchorPhraseBar = rStar,
            downbeatMargin = downbeatMargin,
            halfBarMargin = halfBarMargin,
            phraseMargin = phraseMargin,
            meterIrregular = irregular,
        )
    }

    private fun structuralShiftMargin(emit: Array<DoubleArray>, path: IntArray, from: Int, shift: Int): Double {
        val ks = emit.firstOrNull()?.size ?: return 0.0
        var sum = 0.0
        for (i in from.coerceAtLeast(0) until path.size) {
            val k = path[i]
            if (k < 0) continue
            sum += emit[i][k] - emit[i][(k + shift) % ks]
        }
        return sum
    }

    /** Per-bar evidence along the decoded path; flags a consistent sign flip after some bar. */
    private fun meterPostCheck(emit: Array<DoubleArray>, path: IntArray, anchor: Int, p: Int): Boolean {
        val ks = 4 * p
        val ev = ArrayList<Double>()
        var start = anchor
        while (start + p <= path.size) {
            if (path[start] < 0) break
            var own = 0.0
            var bestAlt = NEG_INF
            for (shift in 1 until p) {
                var alt = 0.0
                for (j in 0 until p) {
                    val k = path[start + j].coerceAtLeast(0)
                    alt += emit[start + j][(k + shift) % ks]
                }
                bestAlt = max(bestAlt, alt)
            }
            for (j in 0 until p) own += emit[start + j][path[start + j].coerceAtLeast(0)]
            ev.add(own - bestAlt)
            start += p
        }
        if (ev.size < 6) return false
        for (split in 2..ev.size - 4) {
            val before = ev.subList(0, split)
            val after = ev.subList(split, ev.size)
            val posBefore = before.count { it > 0 }.toDouble() / before.size
            val negAfter = after.count { it < 0 }.toDouble() / after.size
            if (posBefore >= 0.75 && negAfter >= 0.75) return true
        }
        return false
    }
}
