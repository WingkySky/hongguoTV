package com.hongguotv.nativeapp

import androidx.media3.common.PlaybackException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrialSelfHealTest {
    private val decoderInit = PlaybackException.ERROR_CODE_DECODER_INIT_FAILED
    private val decoderUnsupported = PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED
    private val io = PlaybackException.ERROR_CODE_IO_UNSPECIFIED

    @Test
    fun healsDecoderFailuresWithTrackOnce() {
        assertTrue(shouldSelfHealTrial(decoderInit, hasTrack = true, alreadyHealed = false))
        assertTrue(shouldSelfHealTrial(decoderUnsupported, hasTrack = true, alreadyHealed = false))
        assertFalse(shouldSelfHealTrial(decoderInit, hasTrack = true, alreadyHealed = true))
    }

    @Test
    fun ignoresNetworkErrorsAndMissingTracks() {
        assertFalse(shouldSelfHealTrial(io, hasTrack = true, alreadyHealed = false))
        assertFalse(shouldSelfHealTrial(null, hasTrack = true, alreadyHealed = false))
        assertFalse(shouldSelfHealTrial(decoderInit, hasTrack = false, alreadyHealed = false))
    }
}
