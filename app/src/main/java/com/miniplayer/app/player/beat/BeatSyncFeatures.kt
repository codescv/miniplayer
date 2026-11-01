package com.miniplayer.app.player.beat

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Stage 4 beat-synchronous features (§3.4) on a strictly consecutive beat list. All `*` arrays
 * are robust-standardised (floored scale, clipped to `[−2, 3]`); `*Raw` arrays are unstandardised.
 */
class BeatSyncFeatures(
    val timesMs: DoubleArray,
    val k: DoubleArray,
    val s: DoubleArray,
    val a: DoubleArray,
    val h1: DoubleArray,
    val bj: DoubleArray,
    val cad: DoubleArray,
    val sec: DoubleArray,
    val fill: DoubleArray,
    val crash: DoubleArray,
    val step: DoubleArray,
    val click: DoubleArray,
    val tonal: BooleanArray,
    val h1Raw: DoubleArray,
    val stepRaw: DoubleArray,
    val drumPresence: Double,
    /** 0 = no harmonic changes, 1 = every beat, 2 = half-bar, 4 = bar-level or slower. */
    val harmRhythm: Int,
    val keyConfidence: Double,
    /** Beats whose chroma came from the bracketing-interpolation fallback (no body frame). */
    val chromaFallbackBeats: Int,
    /** Beats with no chroma at all (should be 0 whenever tonal frames exist). */
    val emptyChromaBeats: Int,
    /** Optional neural activations (plug-in slot, §3.5). */
    val beatAct: DoubleArray? = null,
    val downbeatAct: DoubleArray? = null,
    /** Debug: off-beat mid/high onset counts per beat (Fill input) and raw crash ratios. */
    val onsetCount: IntArray = IntArray(0),
    val crashRaw: DoubleArray = DoubleArray(0),
) {
    val size: Int get() = timesMs.size

    companion object {
        private const val BODY_START_MS = 25.0 + 93.0
        private const val BODY_END_MS = 10.0 + 93.0

        fun compute(frames: BeatFeatureFrames, timesMs: DoubleArray, periodMs: Double, tau0Hops: Double): BeatSyncFeatures {
            val m = timesMs.size
            fun nextT(i: Int) = if (i + 1 < m) timesMs[i + 1] else timesMs[i] + periodMs

            // ---- Drum / accent features --------------------------------------------------------
            val kRaw = DoubleArray(m)
            val sRaw = DoubleArray(m)
            val aRaw = DoubleArray(m)
            val compRef = Stats.percentilePositive(frames.compositeOnset, 0.75).coerceAtLeast(0.15)
            val energyRef = Stats.percentilePositive(frames.energy, 0.75).coerceAtLeast(1e-5)
            for (i in 0 until m) {
                val t = timesMs[i]
                val low = frames.maxOver(frames.lowOnset, t - 25.0, t + 25.0).toDouble()
                val mid = frames.maxOver(frames.midOnset, t - 25.0, t + 25.0).toDouble()
                val high = frames.maxOver(frames.highOnset, t - 25.0, t + 25.0).toDouble()
                val comp = frames.maxOver(frames.compositeOnset, t - 25.0, t + 25.0).toDouble()
                val e = frames.maxOver(frames.energy, t - 25.0, t + 25.0).toDouble()
                kRaw[i] = if (frames.hasLowBand) low else 0.0
                // max(mid, high) − 0.6·low, with high-band-only onsets (hi-hats) at 0.6 so a constant
                // hat on every beat cannot mask where the snare / backbeat actually is.
                sRaw[i] = max(mid, 0.6 * high) - 0.6 * kRaw[i]
                aRaw[i] = 0.72 * (comp / compRef).coerceAtMost(2.0) + 0.28 * (e / energyRef).coerceAtMost(2.0)
            }

            // ---- Tonal beat-body chroma / bass -------------------------------------------------
            val tonalFrames = frames.tonal
            val chromaBeat = Array(m) { FloatArray(12) }
            val tonalE = DoubleArray(m)
            var fallbackBeats = 0
            var emptyBeats = 0
            val cc = tonalFrames.chromaCentersMs
            for (i in 0 until m) {
                if (cc.isEmpty()) {
                    emptyBeats++
                    continue
                }
                val t = timesMs[i]
                val tn = nextT(i)
                val lo = t + BODY_START_MS
                val hi = tn - BODY_END_MS
                var count = 0
                if (hi >= lo) {
                    var f = lowerBound(cc, lo)
                    while (f < cc.size && cc[f] <= hi) {
                        val c = tonalFrames.chroma[f]
                        for (p in 0 until 12) chromaBeat[i][p] += c[p] * tonalFrames.tonalEnergy[f]
                        tonalE[i] += tonalFrames.tonalEnergy[f].toDouble()
                        count++
                        f++
                    }
                }
                if (count > 0) {
                    tonalE[i] /= count
                } else {
                    // Per-beat fallback: interpolate the two frames bracketing t + 0.55·(t_{m+1} − t).
                    fallbackBeats++
                    val target = t + 0.55 * (tn - t)
                    val okFrame = interpolateFrames(cc, target) { f0, f1, w ->
                        for (p in 0 until 12) {
                            chromaBeat[i][p] = (1 - w).toFloat() * tonalFrames.chroma[f0][p] * tonalFrames.tonalEnergy[f0] +
                                w.toFloat() * tonalFrames.chroma[f1][p] * tonalFrames.tonalEnergy[f1]
                        }
                        tonalE[i] = (1 - w) * tonalFrames.tonalEnergy[f0] + w * tonalFrames.tonalEnergy[f1]
                    }
                    if (!okFrame) emptyBeats++
                }
                normalizeL2(chromaBeat[i])
            }
            val bassBeat = Array(m) { FloatArray(12) }
            val bassCent = DoubleArray(m)
            val bassE = DoubleArray(m)
            val bc = tonalFrames.bassCentersMs
            for (i in 0 until m) {
                if (bc.isEmpty()) continue
                val target = timesMs[i] + 0.65 * (nextT(i) - timesMs[i])
                interpolateFrames(bc, target) { f0, f1, w ->
                    for (p in 0 until 12) {
                        bassBeat[i][p] = ((1 - w) * tonalFrames.bassPcp[f0][p] + w * tonalFrames.bassPcp[f1][p]).toFloat()
                    }
                    bassCent[i] = (1 - w) * tonalFrames.bassCentroidHz[f0] + w * tonalFrames.bassCentroidHz[f1]
                    bassE[i] = (1 - w) * tonalFrames.bassEnergy[f0] + w * tonalFrames.bassEnergy[f1]
                }
            }
            val tonalRef = Stats.percentile(tonalE, 0.75)
            val tonal = BooleanArray(m) { tonalE[it] > max(TONAL_ABS_FLOOR, 0.25 * tonalRef) }
            val bassRef = Stats.percentile(bassE, 0.75)
            val hasBass = BooleanArray(m) { bassE[it] > max(1e-4, 0.25 * bassRef) }

            val zeta = Array(m) { TonalFrontEnd.tonnetz(chromaBeat[it]) }
            val h1Raw = DoubleArray(m)
            val bjRaw = DoubleArray(m)
            for (i in 1 until m) {
                if (tonal[i] && tonal[i - 1]) {
                    var dz = 0.0
                    for (d in 0 until 6) dz += (zeta[i][d] - zeta[i - 1][d]).let { it * it }
                    h1Raw[i] = (1.0 - Stats.cosine(chromaBeat[i], chromaBeat[i - 1])) + 0.5 * sqrt(dz)
                }
                if (hasBass[i] && hasBass[i - 1]) {
                    val cent = if (bassCent[i] > 1.0 && bassCent[i - 1] > 1.0) abs(ln(bassCent[i] / bassCent[i - 1])) else 0.0
                    bjRaw[i] = jsd(bassBeat[i], bassBeat[i - 1]) + cent
                }
            }

            // ---- Key / cadence tie-breaker -----------------------------------------------------
            val meanChroma = DoubleArray(12)
            for (i in 0 until m) if (tonal[i]) for (p in 0 until 12) meanChroma[p] += chromaBeat[i][p]
            val (tonic, minor, kappa) = TonalFrontEnd.estimateKey(meanChroma)
            val tonicChord = tonic + if (minor) 12 else 0
            val chords = IntArray(m) { if (tonal[it]) TonalFrontEnd.matchTriad(chromaBeat[it]) else -1 }
            val cadRaw = DoubleArray(m)
            for (i in 1 until m - 3) {
                if (chords[i] == tonicChord && chords[i - 1] != chords[i] &&
                    chords[i + 1] == chords[i] && chords[i + 2] == chords[i] && chords[i + 3] == chords[i]
                ) {
                    cadRaw[i] = kappa
                }
            }

            // ---- Section novelty (order-preserving, stacked) -----------------------------------
            val secRaw = DoubleArray(m)
            for (i in 16 until m - 15) {
                // L = 16 drives; L = 8 only adds where L = 16 agrees (min), so a static 16-beat loop
                // (L = 8 windows differ by construction) cannot leak an arbitrary phrase phase.
                val t16 = max(0.0, 1.0 - stackedCos(chromaBeat, i, 16))
                val t8 = max(0.0, 1.0 - stackedCos(chromaBeat, i, 8))
                val d16 = max(0.0, 1.0 - stackedDrumCos(kRaw, sRaw, i, 16))
                val d8 = max(0.0, 1.0 - stackedDrumCos(kRaw, sRaw, i, 8))
                secRaw[i] = (t16 + 0.5 * min(t8, t16)) + 0.5 * (d16 + 0.5 * min(d8, d16))
            }

            // ---- Fill / Crash / Step / Click ---------------------------------------------------
            // Fill: density of percussive onsets at *sub-beat* positions in beats m−2..m−1. Onsets
            // are 400 Hz edge-slope peaks that lift the envelope 30 % above its recent maximum
            // (drum attacks; slow tonal swells / beating never qualify); on-beat ±40 ms excluded so
            // grid-aligned kick / snare / chord attacks do not count.
            val x = frames.edge
            val edgeP95 = Stats.percentilePositive(x, 0.95)
            val onsetCount = IntArray(m)
            val minSep = (30.0 / frames.edgeHopMs).toInt().coerceAtLeast(1)
            for (i in 0 until m) {
                val lo = frames.edgeAt(timesMs[i] + 40.0).coerceAtLeast(9)
                val hi = frames.edgeAt(nextT(i) - 40.0).coerceAtMost(x.size - 2)
                var last = -minSep
                for (k in lo..hi) {
                    val d = x[k] - x[k - 1]
                    if (d <= 0f || d < x[k + 1] - x[k]) continue
                    // A genuine new attack clears the recent (2.5–20 ms) maximum: fluctuations inside a
                    // decaying noise burst and slow tonal swells / beating never do.
                    var recentMax = 0f
                    for (j in k - 8 until k - 1) if (x[j] > recentMax) recentMax = x[j]
                    if (x[k + 1] >= 1.3f * recentMax + 0.02f * edgeP95.toFloat() && k - last >= minSep) {
                        onsetCount[i]++
                        last = k
                    }
                }
            }
            // Excess onsets over the track's typical density (median per beat), counted over 2 beats;
            // standardised with a 2-onset floor so one stray onset is ~0.5σ while a 16th fill is ≥ 3σ.
            val medCount = Stats.median(DoubleArray(m) { onsetCount[it].toDouble() })
            val fillRaw = DoubleArray(m) { i ->
                if (i < 2) 0.0 else max(0.0, (onsetCount[i - 2] + onsetCount[i - 1]) - 2.0 * medCount - 1.0)
            }
            // Crash: sustained high-band floor after the beat vs the floor just before it. Floors
            // (20th percentiles) ignore hat / snare / fill transients, so only a ringing cymbal that
            // starts on this beat fires (robust to fills and to the crash's own attack).
            val eps = 0.05 * Stats.percentilePositive(frames.airRms, 0.95) + 1e-6
            val crashRaw = DoubleArray(m) { i ->
                val t = timesMs[i]
                var lo = t + 200.0
                var hi = min(t + 400.0, nextT(i) - 10.0)
                if (hi - lo < 20.0) {
                    lo = t + 0.4 * (nextT(i) - t)
                    hi = t + 0.9 * (nextT(i) - t)
                }
                val preLo = t - min(200.0, 0.6 * periodMs)
                // Sparse: only a ≥ 2× (6 dB) rise of the sustained cymbal floor counts.
                max(0.0, ln((floorOver(frames, frames.airRms, lo, hi) + eps) / (floorOver(frames, frames.airRms, preLo, t - 20.0) + eps)) - ln(2.0))
            }
            val lowRef = Stats.percentilePositive(frames.lowRms, 0.75).coerceAtLeast(1e-6)
            val accRef = Stats.percentilePositive(frames.accentEnergy, 0.75).coerceAtLeast(1e-6)
            val tonalERef = tonalRef.coerceAtLeast(1e-6)
            val eG = DoubleArray(m) { i ->
                val t = timesMs[i]
                val tn = nextT(i)
                frames.meanOver(frames.lowRms, t, tn) / lowRef +
                    frames.meanOver(frames.accentEnergy, t, tn) / accRef +
                    (if (tonalRef > 1e-6) tonalE[i] / tonalERef else 0.0)
            }
            val eGp75 = Stats.percentile(eG, 0.75).coerceAtLeast(1e-9)
            val stepRaw = DoubleArray(m) { i ->
                val ahead = DoubleArray(min(4, m - i)) { eG[i + it] }
                val medAhead = Stats.median(ahead)
                val behindLo = max(0, i - 4)
                val behind = if (i > behindLo) (behindLo until i).sumOf { eG[it] } / (i - behindLo) else 0.0
                val gate = (eG[i] / eGp75).coerceIn(0.0, 1.0)
                val rise = max(0.0, medAhead - behind)
                // Sparse: a band arrival lifts E_g by ≥ 15 % of its p75; chord-to-chord level wobble
                // inside a steady groove must not leak a periodic (phrase-phase) bias.
                if ((i == 0 && eG[0] < 0.7 * eGp75) || rise < 0.15 * eGp75) 0.0 else rise * gate
            }
            val stepMax = stepRaw.maxOrNull() ?: 0.0
            val firstStep = stepRaw.indexOfFirst { it >= 0.5 * stepMax && stepMax > 0.0 }.let { if (it < 0) m else it }
            val clickCand = BooleanArray(m) { i -> isClickLike(frames, timesMs[i], nextT(i), tonal[i]) }
            val clickRaw = DoubleArray(m) { i ->
                val rep = (i > 0 && clickCand[i - 1]) || (i + 1 < m && clickCand[i + 1])
                if (clickCand[i] && rep && i < firstStep) 1.0 else 0.0
            }

            // ---- Track-level: drumPresence, harmonic rhythm ------------------------------------
            val drumPresence = computeDrumPresence(frames, tau0Hops)
            val harm = classifyHarmRhythm(h1Raw, tonal)

            // ---- Standardise -------------------------------------------------------------------
            val h1 = Stats.robustZ(h1Raw, 0.02, 0.15)
            val bj = Stats.robustZ(bjRaw, 0.02, 0.15)
            val cad = Stats.robustZ(cadRaw, 0.1, 0.5)
            for (i in 0 until m) {
                if (!tonal[i]) {
                    h1[i] = 0.0
                    cad[i] = 0.0
                }
                if (!tonal[i] && !hasBass[i]) bj[i] = 0.0
            }
            return BeatSyncFeatures(
                timesMs = timesMs,
                k = Stats.robustZ(kRaw, 0.05, 0.1),
                s = Stats.robustZ(sRaw, 0.05, 0.1),
                a = Stats.robustZ(aRaw, 0.05, 0.1),
                h1 = h1,
                bj = bj,
                cad = cad,
                sec = Stats.robustZ(secRaw, 0.02, 0.3),
                fill = Stats.robustZ(fillRaw, 2.0, 0.0),
                crash = Stats.robustZ(crashRaw, ln(2.0), 0.0),
                step = Stats.robustZ(stepRaw, 0.05, 0.3),
                click = Stats.robustZ(clickRaw, 0.5, 0.5),
                tonal = tonal,
                h1Raw = h1Raw,
                stepRaw = stepRaw,
                drumPresence = drumPresence,
                harmRhythm = harm,
                keyConfidence = kappa,
                chromaFallbackBeats = fallbackBeats,
                emptyChromaBeats = emptyBeats,
                onsetCount = onsetCount,
                crashRaw = crashRaw,
            )
        }

        internal const val TONAL_ABS_FLOOR = 0.5

        /**
         * Phase-free 4-fold harmonic-rhythm classifier on raw `H1` (§3.4, critic R2-2):
         * `μ_b = mean_r H1_raw[4r + b]`.
         */
        fun classifyHarmRhythm(h1Raw: DoubleArray, tonal: BooleanArray): Int {
            val sum = DoubleArray(4)
            val cnt = IntArray(4)
            for (i in h1Raw.indices) {
                if (i == 0 || !tonal[i]) continue
                sum[i % 4] += h1Raw[i]
                cnt[i % 4]++
            }
            if (cnt.any { it == 0 }) return 0
            val mu = DoubleArray(4) { sum[it] / cnt[it] }
            val b1 = mu.indices.maxByOrNull { mu[it] } ?: 0
            if (mu[b1] < 0.03) return 0
            val opp = mu[(b1 + 2) % 4]
            val off = max(mu[(b1 + 1) % 4], mu[(b1 + 3) % 4])
            if (opp >= 0.35 * mu[b1] && off < 0.45 * min(mu[b1], opp)) return 2
            val minMu = mu.minOrNull() ?: 0.0
            if (minMu >= 0.6 * mu[b1]) return 1
            return 4
        }

        private fun isClickLike(frames: BeatFeatureFrames, t: Double, tn: Double, tonal: Boolean): Boolean {
            if (tonal) return false
            val x = frames.edge
            if (x.isEmpty()) return false
            val lo = frames.edgeAt(t - 25.0).coerceIn(0, x.size - 1)
            val hi = frames.edgeAt(t + 25.0).coerceIn(0, x.size - 1)
            var pk = lo
            for (j in lo..hi) if (x[j] > x[pk]) pk = j
            val peak = x[pk]
            if (peak <= 1e-5f) return false
            val after = (pk + (45.0 / frames.edgeHopMs).toInt()).coerceAtMost(x.size - 1)
            if (x[after] > 0.251f * peak) return false
            val low = frames.maxOver(frames.lowRms, t - 10.0, t + 40.0)
            val mh = frames.maxOver(frames.midRms, t - 10.0, t + 40.0) + frames.maxOver(frames.highRms, t - 10.0, t + 40.0)
            if (low > 0.2f * mh) return false
            val bLo = frames.edgeAt(t + 60.0).coerceIn(0, x.size - 1)
            val bHi = frames.edgeAt(tn - 20.0).coerceIn(0, x.size - 1)
            if (bHi > bLo) {
                var s = 0.0
                for (j in bLo..bHi) s += x[j]
                val mean = s / (bHi - bLo + 1)
                if (peak < 4.0 * mean) return false
            }
            return true
        }

        /**
         * Track-level drum presence ∈ [0, 1]: low-band onset periodicity at τ0 / 2τ0 / 4τ0 ×
         * peakiness (kurtosis of the low-band onset; kicks are isolated spikes, strummed / sustained
         * low notes are not) × low-band share (a kick dominates the < 155 Hz band).
         */
        internal fun computeDrumPresence(frames: BeatFeatureFrames, tau0Hops: Double): Double {
            if (!frames.hasLowBand) return 0.0
            val x = frames.lowOnset
            val n = x.size
            if (n < 64 || tau0Hops < 2.0) return 0.0
            val mean = x.average()
            var v = 0.0
            var m4 = 0.0
            for (xi in x) {
                val d = xi - mean
                v += d * d
                m4 += d * d * d * d
            }
            v /= n
            m4 /= n
            if (v < 1e-12) return 0.0
            val kurt = m4 / (v * v)
            fun acf(lag: Int): Double {
                if (lag >= n - 8) return 0.0
                var s = 0.0
                for (i in 0 until n - lag) s += (x[i] - mean) * (x[i + lag] - mean)
                return s / ((n - lag) * v)
            }
            val l1 = tau0Hops.toInt()
            val periodicity = maxOf(
                maxOf(acf(l1), acf(l1 + 1)),
                maxOf(acf((2 * tau0Hops).toInt()), acf((2 * tau0Hops).toInt() + 1)),
                maxOf(acf((4 * tau0Hops).toInt()), acf((4 * tau0Hops).toInt() + 1)),
            )
            val share = Stats.percentilePositive(frames.lowRms, 0.95) /
                Stats.percentilePositive(frames.energy, 0.95).coerceAtLeast(1e-9)
            return ((periodicity / 0.25).coerceIn(0.0, 1.0) *
                ((kurt - 6.0) / 6.0).coerceIn(0.0, 1.0) *
                ((share - 0.15) / 0.35).coerceIn(0.0, 1.0)).coerceIn(0.0, 1.0)
        }

        /** 20th percentile of [series] over hops covering `[fromMs, toMs)`. */
        private fun floorOver(frames: BeatFeatureFrames, series: FloatArray, fromMs: Double, toMs: Double): Double {
            val lo = frames.hopAt(fromMs).coerceIn(0, series.size - 1)
            val hi = (frames.hopAt(toMs) - 1).coerceIn(lo, series.size - 1)
            val vals = DoubleArray(hi - lo + 1) { series[lo + it].toDouble() }
            return Stats.percentile(vals, 0.2)
        }

        private fun stackedCos(c: Array<FloatArray>, i: Int, l: Int): Double {
            var dot = 0.0
            var na = 0.0
            var nb = 0.0
            for (j in 0 until l) {
                val a = c[i + j]
                val b = c[i - l + j]
                for (p in 0 until 12) {
                    dot += a[p].toDouble() * b[p]
                    na += a[p].toDouble() * a[p]
                    nb += b[p].toDouble() * b[p]
                }
            }
            return zeroAwareCos(dot, na, nb)
        }

        /** Both windows empty -> identical (1); exactly one empty -> maximally novel (0). */
        private fun zeroAwareCos(dot: Double, na: Double, nb: Double): Double {
            val ea = na > 1e-9
            val eb = nb > 1e-9
            return when {
                ea && eb -> dot / sqrt(na * nb)
                !ea && !eb -> 1.0
                else -> 0.0
            }
        }

        private fun stackedDrumCos(k: DoubleArray, s: DoubleArray, i: Int, l: Int): Double {
            var dot = 0.0
            var na = 0.0
            var nb = 0.0
            for (j in 0 until l) {
                val ak = max(0.0, k[i + j])
                val bk = max(0.0, k[i - l + j])
                val aS = max(0.0, s[i + j])
                val bS = max(0.0, s[i - l + j])
                dot += ak * bk + aS * bS
                na += ak * ak + aS * aS
                nb += bk * bk + bS * bS
            }
            return zeroAwareCos(dot, na, nb)
        }

        private fun jsd(p: FloatArray, q: FloatArray): Double {
            var d = 0.0
            for (i in 0 until 12) {
                val a = p[i].toDouble()
                val b = q[i].toDouble()
                val mm = 0.5 * (a + b)
                if (a > 1e-12) d += 0.5 * a * ln(a / mm)
                if (b > 1e-12) d += 0.5 * b * ln(b / mm)
            }
            return d / ln(2.0)
        }

        private fun normalizeL2(v: FloatArray) {
            var n = 0.0
            for (x in v) n += x.toDouble() * x
            n = sqrt(n)
            if (n > 1e-12) for (i in v.indices) v[i] = (v[i] / n).toFloat()
        }

        private fun lowerBound(a: DoubleArray, x: Double): Int {
            var lo = 0
            var hi = a.size
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                if (a[mid] < x) lo = mid + 1 else hi = mid
            }
            return lo
        }

        /** Calls [block] with the two frames bracketing [target] and the interpolation weight. */
        private inline fun interpolateFrames(centers: DoubleArray, target: Double, block: (Int, Int, Double) -> Unit): Boolean {
            if (centers.isEmpty()) return false
            val hi = lowerBound(centers, target)
            return when {
                hi <= 0 -> {
                    block(0, 0, 0.0)
                    true
                }
                hi >= centers.size -> {
                    block(centers.size - 1, centers.size - 1, 0.0)
                    true
                }
                else -> {
                    val f0 = hi - 1
                    val w = ((target - centers[f0]) / (centers[hi] - centers[f0])).coerceIn(0.0, 1.0)
                    block(f0, hi, w)
                    true
                }
            }
        }
    }
}
