package com.github.auties00.smalt.audio.aac.internal;

import com.github.auties00.smalt.internal.bits.MsbBitWriter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the AAC Main predictor bank: its 16-bit quantization helpers and {@code b / VAR} tables against
 * ISO/IEC 13818-7 subclause 13.3.2, and its behavior on hand-built frames: adaptation to a stationary
 * line, short-window, reset-group and noise-band resets.
 */
class FrequencyPredictorTest {
    /** The table index of 48 kHz, whose predictors cover lines 0 to 671. */
    private static final int RATE_48000 = 3;

    /** The number of long-window bands at 48 kHz. */
    private static final int BANDS = 49;

    /**
     * Parses a long-window AAC Main stream of 49 zero bands (or eight short windows), with prediction
     * enabled in every band, an optional reset group and an optional noise band.
     */
    static ChannelStream frame(boolean shortWindows, int resetGroup, int noiseBand) {
        var writer = new MsbBitWriter();
        writer.write(100, 8);
        writer.write(0, 1);
        writer.write(shortWindows ? ChannelStream.EIGHT_SHORT_SEQUENCE : ChannelStream.ONLY_LONG_SEQUENCE, 2);
        writer.write(0, 1);
        if (shortWindows) {
            writer.write(0, 4);
            writer.write(0, 7);
        } else {
            writer.write(BANDS, 6);
            writer.write(1, 1);
            writer.write(resetGroup != 0 ? 1 : 0, 1);
            if (resetGroup != 0) {
                writer.write(resetGroup, 5);
            }
            for (var band = 0; band < SamplingTables.predictionMaxBands(RATE_48000); band++) {
                writer.write(1, 1);
            }
            var band = 0;
            if (noiseBand >= 0) {
                section(writer, Codebooks.ZERO_HCB, noiseBand);
                section(writer, Codebooks.NOISE_HCB, 1);
                band = noiseBand + 1;
            }
            section(writer, Codebooks.ZERO_HCB, BANDS - band);
            if (noiseBand >= 0) {
                writer.write(256, 9);
            }
        }
        writer.write(0, 3);
        writer.write(0, 8);
        var stream = new ChannelStream(1024, RATE_48000, AudioSpecificConfig.AOT_MAIN, AacKernels.scalar());
        stream.parse(new AacBitReader(writer.toByteArray()), false, false);
        return stream;
    }

    static void section(MsbBitWriter writer, int codebook, int length) {
        while (length > 0) {
            var run = Math.min(length, 30);
            writer.write(codebook, 4);
            writer.write(run, 5);
            length -= run;
        }
    }

    /** Runs a predictor over a frame holding {@code values}, returning the reconstructed spectrum. */
    static float[] run(FrequencyPredictor predictor, ChannelStream stream, float[] values) {
        System.arraycopy(values, 0, stream.spectrum(), 0, values.length);
        predictor.apply(stream);
        return stream.spectrum().clone();
    }

    @Nested
    @DisplayName("quantization of ISO/IEC 13818-7 subclause 13.3.2")
    class Quantization {
        @Test
        @DisplayName("truncates to the 16 most significant bits and rounds half away from zero")
        void rounding() {
            var random = new Random(1);
            for (var i = 0; i < 200_000; i++) {
                var value = (float) (random.nextGaussian() * Math.pow(10, random.nextInt(12) - 6));
                var bits = Float.floatToRawIntBits(value);
                assertEquals(Float.intBitsToFloat(bits & 0xFFFF0000), FrequencyPredictor.truncate(value));
                assertEquals(Float.intBitsToFloat((bits + 0x8000) & 0xFFFF0000), FrequencyPredictor.roundInfinity(value),
                        "rounding of " + value);
            }
            var tie = Float.intBitsToFloat(0x3F808000);
            assertEquals(Float.intBitsToFloat(0x3F810000), FrequencyPredictor.roundInfinity(tie));
            assertEquals(-Float.intBitsToFloat(0x3F810000), FrequencyPredictor.roundInfinity(-tie));
        }

        @Test
        @DisplayName("takes b / VAR from tables rounded to eight significant bits, zero below 2")
        void inverseTables() {
            assertEquals(0, FrequencyPredictor.inverse(1));
            assertEquals(0, FrequencyPredictor.inverse(1.9921875f));
            for (var exponent = 1; exponent < 60; exponent++) {
                for (var mantissa = 0; mantissa < 128; mantissa++) {
                    var variance = (float) Math.scalb(1 + mantissa / 128.0, exponent);
                    var ratio = new BigDecimal(0.953125).divide(new BigDecimal(1 + mantissa / 128.0), MathContext.DECIMAL128);
                    var exact = ratio.doubleValue();
                    var scale = Math.scalb(1.0, 8 - (Math.getExponent(exact) + 1));
                    var rounded = new BigDecimal(exact * scale).setScale(0, RoundingMode.HALF_UP).doubleValue() / scale;
                    assertEquals((float) Math.scalb(rounded, -exponent), FrequencyPredictor.inverse(variance),
                            "b / " + variance);
                }
            }
        }
    }

    @Nested
    @DisplayName("predictor processing")
    class Processing {
        @Test
        @DisplayName("estimates nothing after initialization and reconstructs an encoder's lines")
        void adaptation() {
            var predictor = new FrequencyPredictor(1024, RATE_48000);
            assertEquals(0, run(predictor, frame(false, 0, -1), new float[1024])[100]);
            predictor.reset();
            var encoder = new Lattice();
            var values = new float[1024];
            var residual = 0.0;
            for (var n = 0; n < 60; n++) {
                // a line oscillating across frames, the auto-correlation the lattice predicts
                var target = (float) (1000 * Math.cos(0.7 * n));
                var estimate = encoder.estimate();
                values[100] = (float) (target - estimate);
                var output = run(predictor, frame(false, 0, -1), values)[100];
                assertEquals(target, output, 1e-2, "frame " + n);
                encoder.update(output);
                if (n >= 40) {
                    residual = Math.max(residual, Math.abs(values[100]));
                }
            }
            assertTrue(residual < 300, "residual " + residual + " of a 1000 amplitude line");
        }

        @Test
        @DisplayName("resets every predictor in an eight-short-window frame")
        void shortWindows() {
            var predictor = trained();
            run(predictor, frame(true, 0, -1), new float[1024]);
            var estimates = run(predictor, frame(false, 0, -1), new float[1024]);
            for (var k = 0; k < 672; k++) {
                assertEquals(0, estimates[k]);
            }
        }

        @Test
        @DisplayName("resets the lines of the frame's reset group after the update")
        void resetGroup() {
            var reset = trained();
            var kept = trained();
            var values = new float[1024];
            run(reset, frame(false, 7, -1), values.clone());
            run(kept, frame(false, 0, -1), values.clone());
            var resetEstimates = run(reset, frame(false, 0, -1), new float[1024]);
            var keptEstimates = run(kept, frame(false, 0, -1), new float[1024]);
            for (var k = 0; k < 672; k++) {
                if (k % 30 == 6) {
                    assertEquals(0, resetEstimates[k], "line " + k);
                    assertNotEquals(0, keptEstimates[k], "line " + k);
                } else {
                    assertEquals(keptEstimates[k], resetEstimates[k], "line " + k);
                }
            }
            for (var k = 672; k < 1024; k++) {
                assertEquals(0, keptEstimates[k], "lines beyond PRED_SFB_MAX are not predicted");
            }
        }

        @Test
        @DisplayName("never predicts a noise band and resets its predictors")
        void noiseBand() {
            var predictor = trained();
            var offsets = SamplingTables.longOffsets(RATE_48000, 1024);
            var noise = frame(false, 0, 10);
            var before = noise.spectrum().clone();
            predictor.apply(noise);
            for (var k = offsets[10]; k < offsets[11]; k++) {
                assertEquals(before[k], noise.spectrum()[k], "noise line " + k + " keeps its value");
            }
            var estimates = run(predictor, frame(false, 0, -1), new float[1024]);
            for (var k = offsets[10]; k < offsets[11]; k++) {
                assertEquals(0, estimates[k]);
            }
            assertNotEquals(0, estimates[offsets[12]]);
        }

        /**
         * The predictor of one line written out from the equations of ISO/IEC 13818-7 subclause 13.3.2,
         * as an encoder runs it.
         */
        static final class Lattice {
            private float r0;
            private float r1;
            private float cor0;
            private float cor1;
            private float var0 = 1;
            private float var1 = 1;
            private double k1;
            private double estimate0;

            double estimate() {
                k1 = cor0 * ratio(var0);
                var k2 = cor1 * ratio(var1);
                estimate0 = k1 * r0;
                var sum = Float.floatToRawIntBits((float) (estimate0 + k2 * r1));
                return Float.intBitsToFloat((sum + 0x8000) & 0xFFFF0000);
            }

            void update(float reconstructed) {
                double e0 = reconstructed;
                var e1 = e0 - estimate0;
                cor1 = keep(0.90625 * cor1 + r1 * e1);
                var1 = keep(0.90625 * var1 + 0.5 * ((double) r1 * r1 + e1 * e1));
                cor0 = keep(0.90625 * cor0 + r0 * e0);
                var0 = keep(0.90625 * var0 + 0.5 * ((double) r0 * r0 + e0 * e0));
                r1 = keep(0.953125 * (r0 - k1 * e0));
                r0 = keep(0.953125 * e0);
            }

            static float keep(double value) {
                return Float.intBitsToFloat(Float.floatToRawIntBits((float) value) & 0xFFFF0000);
            }

            static double ratio(float variance) {
                if (variance < 2) {
                    return 0;
                }
                var exponent = Math.getExponent(variance);
                var mantissa = Math.scalb(variance, -exponent);
                var exact = 0.953125f / (float) mantissa;
                var scale = Math.scalb(1.0, 8 - (Math.getExponent(exact) + 1));
                return Math.floor(exact * scale + 0.5) / scale * Math.scalb(1.0, -exponent);
            }
        }

        /** Returns a predictor bank fed 30 frames of a deterministic signal on every line. */
        static FrequencyPredictor trained() {
            var predictor = new FrequencyPredictor(1024, RATE_48000);
            var values = new float[1024];
            for (var n = 0; n < 30; n++) {
                for (var k = 0; k < 1024; k++) {
                    values[k] = (float) (500 * Math.cos(0.3 * n + k));
                }
                var stream = frame(false, 0, -1);
                System.arraycopy(values, 0, stream.spectrum(), 0, 1024);
                predictor.apply(stream);
            }
            return predictor;
        }
    }
}
