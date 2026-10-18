package com.github.auties00.smalt.audio.loudness;

import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.io.MediaSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the gain computation of loudness normalization, the presets and the option types of the
 * package.
 */
@DisplayName("LoudnessNormalization")
class LoudnessNormalizationTest {
    private static LoudnessStats stats(double integrated, double truePeakDb) {
        return new LoudnessStats(integrated, 0, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, integrated - 10, integrated,
                integrated, Math.pow(10, truePeakDb / 20), Math.pow(10, truePeakDb / 20), 1_000_000);
    }

    @Nested
    @DisplayName("gain")
    class Gain {
        @Test
        @DisplayName("moves the integrated loudness to the target")
        void target() {
            assertEquals(5, LoudnessNormalization.ebuR128().gainDb(stats(-28, -10)), 1e-9);
            assertEquals(-9, LoudnessNormalization.forStreaming().gainDb(stats(-5, -0.5)), 1e-9);
        }

        @Test
        @DisplayName("lowers the gain to keep the true peak under the ceiling")
        void ceiling() {
            assertEquals(2, LoudnessNormalization.ebuR128().gainDb(stats(-30, -3)), 1e-9);
            assertEquals(7, LoudnessNormalization.ebuR128().toBuilder().noTruePeakCeiling().build().gainDb(stats(-30, -3)), 1e-9);
        }

        @Test
        @DisplayName("caps the gain and leaves silence alone")
        void limits() {
            assertEquals(3, LoudnessNormalization.ebuR128().toBuilder().maxGainDb(3).build().gainDb(stats(-40, -30)), 1e-9);
            assertEquals(0, LoudnessNormalization.ebuR128().gainDb(stats(Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY)));
        }

        @Test
        @DisplayName("uses the sample peak when the true peak was not measured")
        void samplePeak() {
            var measured = new LoudnessStats(-30, 0, 0, 0, -40, -30, -30, Math.pow(10, -3 / 20.0), Double.NaN, 1);
            assertEquals(2, LoudnessNormalization.ebuR128().gainDb(measured), 1e-9);
        }

        @Test
        @DisplayName("normalizes a measured file to within 0.1 LU")
        void measured() {
            var planes = Signals.tones(48_000, 2, 5, -30, 5, -15, 5, -24);
            var source = MediaSource.of(Signals.wav(planes, 48_000));
            var gain = Loudness.normalizationGainDb(source, LoudnessNormalization.forPodcast());
            var factor = (float) Math.pow(10, gain / 20);
            for (var plane : planes) {
                for (var index = 0; index < plane.length; index++) {
                    plane[index] *= factor;
                }
            }
            var after = Signals.measure(planes, 48_000, ChannelLayout.STEREO).stats();
            assertEquals(-16, after.integrated(), 0.1);
            assertTrue(after.truePeakDb() <= -1.5);
        }
    }

    @Nested
    @DisplayName("options")
    class Options {
        @Test
        @DisplayName("normalization presets and builder")
        void normalization() {
            assertEquals(LoudnessNormalization.defaults(), LoudnessNormalization.ebuR128());
            assertEquals(-23, LoudnessNormalization.ebuR128().targetLufs());
            assertEquals(-1, LoudnessNormalization.ebuR128().truePeakCeilingDb().orElseThrow());
            assertEquals(-24, LoudnessNormalization.atscA85().targetLufs());
            assertEquals(-2, LoudnessNormalization.atscA85().truePeakCeilingDb().orElseThrow());
            assertEquals(-16, LoudnessNormalization.forPodcast().targetLufs());
            assertEquals(-14, LoudnessNormalization.forStreaming().targetLufs());
            var custom = LoudnessNormalization.of(-18, -2).toBuilder().maxGainDb(12).build();
            assertEquals(custom, custom.toBuilder().build());
            assertEquals(custom.hashCode(), custom.toBuilder().build().hashCode());
            assertEquals(12, custom.maxGainDb().orElseThrow());
            assertTrue(custom.toBuilder().noTruePeakCeiling().build().truePeakCeilingDb().isEmpty());
            assertNotEquals(custom, custom.toBuilder().targetLufs(-20).build());
            assertThrows(IllegalArgumentException.class, () -> LoudnessNormalization.of(3, -1));
            assertThrows(IllegalArgumentException.class, () -> LoudnessNormalization.of(-23, Double.NaN));
            assertThrows(IllegalArgumentException.class, () -> LoudnessNormalization.ebuR128().toBuilder().maxGainDb(-1).build());
        }

        @Test
        @DisplayName("meter options presets and builder")
        void meter() {
            var defaults = LoudnessMeterOptions.defaults();
            assertTrue(defaults.truePeak());
            assertFalse(defaults.dualMono());
            assertTrue(defaults.channelWeights().isEmpty());
            assertFalse(LoudnessMeterOptions.loudnessOnly().truePeak());
            var weighted = defaults.toBuilder().channelWeights(1, 2).build();
            assertEquals(weighted, weighted.toBuilder().build());
            assertEquals(2, weighted.channelWeights().orElseThrow()[1]);
            weighted.channelWeights().orElseThrow()[1] = 7;
            assertEquals(2, weighted.channelWeights().orElseThrow()[1]);
            assertThrows(IllegalArgumentException.class, () -> defaults.toBuilder().channelWeights(-1).build());
            assertNotEquals(defaults, defaults.toBuilder().dualMono(true).build());
        }
    }
}
