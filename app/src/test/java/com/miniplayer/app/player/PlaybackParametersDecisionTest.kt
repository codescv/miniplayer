package com.miniplayer.app.player

import com.miniplayer.app.core.model.PracticeMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackParametersDecisionTest {
    @Test
    fun rapidPitchClicksAtConstantSpeed_neverFlushAudioSinkOrMutateExoPlaybackParameters() {
        var prevSpeed = 1.0f
        var prevPitch = 1.0f

        val semitoneSequence = listOf(1, 2, 3, 4, 5, 6, 4, 2, 0, -1, -2, -4, -6, -3, 0, 1, -1, 0)
        for (semitone in semitoneSequence) {
            val reqPitch = PracticeMath.computePitchFactor(semitone, 0)
            val decision = PlayerController.evaluatePlaybackParametersUpdate(
                requestedSpeed = 1.0f,
                requestedPitch = reqPitch,
                previousSpeed = prevSpeed,
                previousPitch = prevPitch,
            )
            assertFalse("Pitch click ($semitone st) must never seek/flush DefaultAudioSink", decision.shouldFlushAudioSink)
            assertFalse("Pure pitch click ($semitone st) must not update ExoPlayer PlaybackParameters", decision.shouldUpdateExoPlaybackParameters)
            assertEquals(1.0f, decision.exoPlaybackSpeed, 0.0001f)
            assertEquals(1.0f, decision.exoPlaybackPitch, 0.0001f)
            assertEquals(reqPitch, decision.safePitch, 0.0001f)

            prevSpeed = decision.safeSpeed
            prevPitch = decision.safePitch
        }
    }

    @Test
    fun speedTransitionsWithActivePitch_updateExoSpeedOnlyWhileKeepingExoPitchAtUnity() {
        val pitchFactor = PracticeMath.computePitchFactor(-2, 0)

        val d1 = PlayerController.evaluatePlaybackParametersUpdate(
            requestedSpeed = 0.75f,
            requestedPitch = pitchFactor,
            previousSpeed = 1.0f,
            previousPitch = pitchFactor,
        )
        assertTrue(d1.shouldUpdateExoPlaybackParameters)
        assertFalse(d1.shouldFlushAudioSink)
        assertEquals(0.75f, d1.exoPlaybackSpeed, 0.0001f)
        assertEquals(1.0f, d1.exoPlaybackPitch, 0.0001f)
        assertEquals(pitchFactor, d1.safePitch, 0.0001f)

        // Subsequent pitch change at 0.75x speed must NOT re-trigger ExoPlayer PlaybackParameters
        val nextPitch = PracticeMath.computePitchFactor(3, 0)
        val d2 = PlayerController.evaluatePlaybackParametersUpdate(
            requestedSpeed = 0.75f,
            requestedPitch = nextPitch,
            previousSpeed = d1.safeSpeed,
            previousPitch = d1.safePitch,
        )
        assertFalse(d2.shouldUpdateExoPlaybackParameters)
        assertFalse(d2.shouldFlushAudioSink)
        assertEquals(0.75f, d2.exoPlaybackSpeed, 0.0001f)
        assertEquals(1.0f, d2.exoPlaybackPitch, 0.0001f)
        assertEquals(nextPitch, d2.safePitch, 0.0001f)
    }

    @Test
    fun extremeValues_clampCleanlyWithinSafeBounds() {
        val low = PlayerController.evaluatePlaybackParametersUpdate(0.01f, 0.1f, 1.0f, 1.0f)
        assertEquals(0.25f, low.safeSpeed, 0.0001f)
        assertEquals(0.50f, low.safePitch, 0.0001f)
        assertEquals(1.0f, low.exoPlaybackPitch, 0.0001f)

        val high = PlayerController.evaluatePlaybackParametersUpdate(5.0f, 9.0f, 0.25f, 0.50f)
        assertEquals(2.0f, high.safeSpeed, 0.0001f)
        assertEquals(2.0f, high.safePitch, 0.0001f)
        assertEquals(1.0f, high.exoPlaybackPitch, 0.0001f)
    }
}
