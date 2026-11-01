package com.miniplayer.app.player

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/**
 * Deterministic synthetic backing-track renderer for BeatGrid v2 tests (§4): kick = 55 Hz decaying
 * sine + click, snare = band-passed noise burst + body, hat = 8 kHz noise, chords = 6-harmonic pads,
 * bass = sawtooth roots, DAW click = short sine burst. Every event starts exactly at its attack time
 * (ground truth = attack start, ms).
 */
class SyntheticBackingTrack(
    val sampleRate: Int = 44_100,
    val durationSec: Double,
    seed: Long = 7L,
) {
    val frames: Int = (durationSec * sampleRate).toInt()
    val buf = FloatArray(frames)
    private val rnd = Random(seed)

    private fun idx(ms: Double): Int = (ms * sampleRate / 1000.0).roundToInt()

    private inline fun render(startMs: Double, lenMs: Double, gen: (i: Int, tSec: Double) -> Double) {
        val s0 = idx(startMs)
        val len = (lenMs * sampleRate / 1000.0).toInt()
        for (i in 0 until len) {
            val f = s0 + i
            if (f < 0) continue
            if (f >= frames) break
            buf[f] += gen(i, i.toDouble() / sampleRate).toFloat()
        }
    }

    fun kick(tMs: Double, amp: Double = 0.55) {
        render(tMs, 260.0) { i, t ->
            val env = exp(-t / 0.075)
            val pitch = 55.0 + 60.0 * exp(-t / 0.012)
            val phase = 2.0 * PI * (55.0 * t + 60.0 * 0.012 * (1 - exp(-t / 0.012)))
            val click = if (i < sampleRate / 500) (1.0 - i / (sampleRate / 500.0)) * 0.35 * (if (i % 2 == 0) 1 else -1) else 0.0
            amp * (sin(phase) * env + click) * (if (pitch > 0) 1.0 else 1.0)
        }
    }

    fun snare(tMs: Double, amp: Double = 0.35) {
        // 2-pole resonant band-pass (~1.8 kHz) over white noise + 190 Hz body.
        val r = 0.93
        val w0 = 2.0 * PI * 1800.0 / sampleRate
        val a1 = 2 * r * kotlin.math.cos(w0)
        val a2 = -r * r
        var y1 = 0.0
        var y2 = 0.0
        render(tMs, 180.0) { _, t ->
            val x = rnd.nextDouble() * 2 - 1
            val y = x + a1 * y1 + a2 * y2
            y2 = y1
            y1 = y
            val env = exp(-t / 0.045)
            amp * env * (0.22 * y + 0.45 * sin(2 * PI * 190.0 * t))
        }
    }

    fun hat(tMs: Double, amp: Double = 0.07, decaySec: Double = 0.018) {
        var prev = 0.0
        render(tMs, decaySec * 6000.0) { _, t ->
            val x = rnd.nextDouble() * 2 - 1
            val hp = x - prev
            prev = x
            amp * exp(-t / decaySec) * (0.6 * hp + 0.4 * sin(2 * PI * 8000.0 * t))
        }
    }

    fun crash(tMs: Double, amp: Double = 0.12) {
        var prev = 0.0
        render(tMs, 1600.0) { _, t ->
            val x = rnd.nextDouble() * 2 - 1
            val hp = x - prev
            prev = x
            amp * exp(-t / 0.55) * hp
        }
    }

    /** DAW-style count-in click: short sine burst at [freq] (mid band). */
    fun dawClick(tMs: Double, freq: Double = 1000.0, amp: Double = 0.30) {
        render(tMs, 30.0) { _, t -> amp * exp(-t / 0.006) * sin(2 * PI * freq * t) }
    }

    private fun midiHz(m: Double) = 440.0 * 2.0.pow((m - 69.0) / 12.0)

    /** 6-harmonic pad triad (root pitch class [rootPc], octave 3–4) with a 6 ms attack. */
    fun chord(startMs: Double, durMs: Double, rootPc: Int, minor: Boolean, amp: Double = 0.05, attackMs: Double = 6.0) {
        val notes = intArrayOf(0, if (minor) 3 else 4, 7).map { 48.0 + rootPc + it }
        val freqs = notes.map { midiHz(it) }
        render(startMs, durMs) { i, t ->
            val atk = (t * 1000.0 / attackMs).coerceAtMost(1.0)
            val rel = ((durMs / 1000.0 - t) / 0.02).coerceIn(0.0, 1.0)
            var v = 0.0
            for (f in freqs) for (h in 1..6) v += sin(2 * PI * f * h * t) / h
            amp * atk * rel * (0.85 + 0.15 * exp(-t / 0.4)) * v / 3.0
        }
    }

    /** Sawtooth bass root (octave 2). */
    fun bass(startMs: Double, durMs: Double, rootPc: Int, amp: Double = 0.12) {
        val f = midiHz(36.0 + rootPc)
        render(startMs, durMs) { _, t ->
            val atk = (t * 1000.0 / 5.0).coerceAtMost(1.0)
            val rel = ((durMs / 1000.0 - t) / 0.02).coerceIn(0.0, 1.0)
            val ph = (f * t) % 1.0
            amp * atk * rel * (2.0 * ph - 1.0) * (0.7 + 0.3 * exp(-t / 0.3))
        }
    }

    fun toPcm16(peak: Double = 0.9): ShortArray {
        var mx = 1e-9
        for (v in buf) mx = maxOf(mx, kotlin.math.abs(v.toDouble()))
        val g = if (mx > peak) peak / mx else 1.0
        return ShortArray(frames) { (buf[it] * g * 32767.0).roundToInt().coerceIn(-32767, 32767).toShort() }
    }

    fun toStereoPcm16(): ShortArray {
        val mono = toPcm16()
        return ShortArray(mono.size * 2) { mono[it / 2] }
    }

    companion object {
        const val C = 0
        const val D = 2
        const val F = 5
        const val G = 7
        const val A = 9

        /** A chord: (pitch class, minor). */
        data class Ch(val pc: Int, val minor: Boolean = false)
    }
}

/**
 * Song-level helper: places a 4/4 (or [beatsPerBar]) groove on an arbitrary beat-time function so
 * tests can express fractional tempo, wander and pushes. [beatTime] maps a beat index (0 = first
 * downbeat of the groove) to ms.
 */
class GrooveWriter(
    private val track: SyntheticBackingTrack,
    private val beatTime: (Int) -> Double,
    private val beatsPerBar: Int = 4,
) {
    /** Kick on [kickBeats], snare on [snareBeats] (beat-in-bar indices), hats on 8ths. */
    fun drums(
        fromBeat: Int,
        toBeat: Int,
        kickBeats: Set<Int> = setOf(0, 2),
        snareBeats: Set<Int> = setOf(1, 3),
        kickAmps: Map<Int, Double> = emptyMap(),
        hats: Boolean = true,
        hatAmp: Double = 0.06,
        snareAmp: Double = 0.35,
    ) {
        for (b in fromBeat until toBeat) {
            val bib = Math.floorMod(b, beatsPerBar)
            val t = beatTime(b)
            if (bib in kickBeats) track.kick(t, kickAmps[bib] ?: 0.55)
            if (bib in snareBeats) track.snare(t, snareAmp)
            if (hats) {
                track.hat(t, hatAmp)
                track.hat(0.5 * (t + beatTime(b + 1)), hatAmp * 0.8)
            }
        }
    }

    /** One chord per [beatsPerChord] beats following [prog] cyclically, plus bass roots. */
    fun harmony(
        fromBeat: Int,
        toBeat: Int,
        prog: List<SyntheticBackingTrack.Companion.Ch>,
        beatsPerChord: Int,
        padAmp: Double = 0.05,
        bassAmp: Double = 0.12,
        withBass: Boolean = true,
    ) {
        var b = fromBeat
        var ci = 0
        while (b < toBeat) {
            val ch = prog[ci % prog.size]
            val end = minOf(toBeat, b + beatsPerChord)
            val t0 = beatTime(b)
            val dur = beatTime(end) - t0
            track.chord(t0, dur, ch.pc, ch.minor, padAmp)
            if (withBass) track.bass(t0, dur, ch.pc, bassAmp)
            b = end
            ci++
        }
    }

    /** 16th-note snare fill over the last two beats of every [everyBars]-th bar + crash on the next 1. */
    fun fills(fromBeat: Int, toBeat: Int, everyBars: Int = 4, amp: Double = 0.30) {
        val barLen = beatsPerBar
        var bar = 0
        while (fromBeat + (bar + 1) * barLen <= toBeat) {
            if ((bar + 1) % everyBars == 0) {
                val fillStart = fromBeat + bar * barLen + barLen - 2
                for (b in fillStart until fillStart + 2) {
                    val t = beatTime(b)
                    val dt = (beatTime(b + 1) - t) / 4.0
                    for (q in 1 until 4) track.snare(t + q * dt, amp * 0.8)
                }
                val next = fromBeat + (bar + 1) * barLen
                if (next < toBeat) track.crash(beatTime(next))
            }
            bar++
        }
    }
}

/** Circular distance (ms) between [t] and the grid `ref + k·period`. */
fun phaseErrorMs(t: Double, ref: Double, period: Double): Double {
    val d = ((t - ref) % period + period) % period
    return minOf(d, period - d)
}
