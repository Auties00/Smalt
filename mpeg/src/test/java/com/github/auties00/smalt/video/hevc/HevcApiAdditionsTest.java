package com.github.auties00.smalt.video.hevc;

import com.github.auties00.smalt.MediaLimits;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Covers the HEVC decoder accessor added for parity with the other video decoders.
 */
@DisplayName("HEVC API additions")
class HevcApiAdditionsTest {
    @Test
    @DisplayName("options() returns the options the decoder was constructed with")
    void options() {
        var options = HevcDecoderOptions.builder().limits(MediaLimits.builder().maxDimension(1024).build()).threads(1)
                .highBitDepth(true).hashVerification(true).build();
        try (var decoder = new HevcDecoder(HevcTestStreams.track(), options)) {
            assertSame(options, decoder.options());
        }
        try (var decoder = new HevcDecoder(HevcTestStreams.track())) {
            assertEquals(HevcDecoderOptions.defaults(), decoder.options());
        }
    }
}
