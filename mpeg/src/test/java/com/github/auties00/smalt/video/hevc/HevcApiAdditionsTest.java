package com.github.auties00.smalt.video.hevc;

import com.github.auties00.smalt.MediaLimits;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the HEVC decoder accessor added for parity with the other video decoders and the layer options.
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

    @Test
    @DisplayName("the layer options default to the base layer's output layer set with alpha attached")
    void layerOptions() {
        var defaults = HevcDecoderOptions.defaults();
        assertEquals(OptionalInt.of(0), defaults.outputLayerSet());
        assertTrue(defaults.alpha());
        var last = HevcDecoderOptions.builder().lastOutputLayerSet().alpha(false).build();
        assertTrue(last.outputLayerSet().isEmpty());
        assertFalse(last.alpha());
        var second = HevcDecoderOptions.builder().outputLayerSet(2).build();
        assertEquals(OptionalInt.of(2), second.outputLayerSet());
        assertNotEquals(defaults, second);
        assertNotEquals(defaults, last);
        assertEquals(second, HevcDecoderOptions.builder().outputLayerSet(2).build());
        assertEquals(second.hashCode(), HevcDecoderOptions.builder().outputLayerSet(2).build().hashCode());
        // the later call wins, and a set index replaces the last-set selection
        assertEquals(OptionalInt.of(1), HevcDecoderOptions.builder().lastOutputLayerSet().outputLayerSet(1).build()
                .outputLayerSet());
        assertTrue(last.toString().contains("alpha"), last.toString());
        assertThrows(IllegalArgumentException.class, () -> HevcDecoderOptions.builder().outputLayerSet(-1).build());
    }
}
