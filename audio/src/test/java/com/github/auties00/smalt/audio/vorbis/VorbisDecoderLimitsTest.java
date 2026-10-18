package com.github.auties00.smalt.audio.vorbis;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.audio.AudioDecoderOptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Covers the {@link AudioDecoderOptions#limits()} a {@link VorbisDecoder} enforces.
 */
@DisplayName("VorbisDecoder limits")
class VorbisDecoderLimitsTest {
    private static final MediaLimits MONO = MediaLimits.builder().maxChannels(1).build();

    @Test
    @DisplayName("checks the channel count against the limits")
    void limits() {
        var stereo = VorbisStreamBuilder.minimal(2).track();
        assertEquals(2, new VorbisDecoder(stereo, AudioDecoderOptions.defaults()).layout().channels());
        assertThrows(MediaException.LimitExceeded.class,
                () -> new VorbisDecoder(stereo, AudioDecoderOptions.builder().limits(MONO).build()));
    }
}
