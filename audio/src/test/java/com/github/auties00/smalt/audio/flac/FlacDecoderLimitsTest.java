package com.github.auties00.smalt.audio.flac;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.audio.AudioDecoderOptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Covers the {@link AudioDecoderOptions#limits()} a {@link FlacDecoder} enforces.
 */
@DisplayName("FlacDecoder limits")
class FlacDecoderLimitsTest {
    private static final MediaLimits MONO = MediaLimits.builder().maxChannels(1).build();

    @Test
    @DisplayName("checks the STREAMINFO channel count and the sample buffers against the limits")
    void limits() {
        var stereo = FlacDecoderTest.track(FlacFrameBuilder.config(4096, 4096, 44100, 2, 16, 0));
        assertEquals(2, new FlacDecoder(stereo, AudioDecoderOptions.defaults()).layout().channels());
        assertThrows(MediaException.LimitExceeded.class,
                () -> new FlacDecoder(stereo, AudioDecoderOptions.builder().limits(MONO).build()));
        assertThrows(MediaException.LimitExceeded.class,
                () -> new FlacDecoder(stereo, AudioDecoderOptions.builder().limits(MediaLimits.builder()
                        .maxBytes(1024).build()).build()));
    }
}
