package com.github.auties00.smalt.audio.process;

import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.audio.Speaker;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;
import java.util.SplittableRandom;

import static com.github.auties00.smalt.audio.Speaker.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers the mixer settings: the AC-3 Lo/Ro mix levels and the Lt/Rt matrix of ATSC A/52 section 7.8
 * written out in closed form, the low-frequency mix, caller matrices, and the validation of the options.
 */
@DisplayName("ChannelMixerOptions")
class ChannelMixerOptionsTest {
    private static final double K = Math.sqrt(0.5);
    private static final ChannelLayout L51 = ChannelLayout.SURROUND_5_1;
    private static final ChannelLayout L71 = ChannelLayout.SURROUND_7_1;
    private static final ChannelLayout STEREO = ChannelLayout.STEREO;
    private static final ChannelLayout L30 = layout(FRONT_LEFT, FRONT_RIGHT, FRONT_CENTER);
    private static final ChannelLayout QUAD = layout(FRONT_LEFT, FRONT_RIGHT, BACK_LEFT, BACK_RIGHT);
    private static final ChannelLayout L31 = layout(FRONT_LEFT, FRONT_RIGHT, FRONT_CENTER, BACK_CENTER);

    private static ChannelLayout layout(Speaker... speakers) {
        return new ChannelLayout(List.of(speakers));
    }

    private static void assertRow(double[] expected, float[] actual) {
        assertEquals(expected.length, actual.length);
        for (var i = 0; i < expected.length; i++) {
            assertEquals((float) expected[i], actual[i], 1e-7f, "coefficient " + i);
        }
    }

    @Nested
    @DisplayName("Lo/Ro mix levels")
    class Levels {
        @ParameterizedTest(name = "cmixlev {0}, surmixlev {1}")
        @CsvSource({"0, 0, 0.7071067811865476, 0.7071067811865476", "1, 0, 0.5956621435290105, 0.7071067811865476",
                "2, 1, 0.5, 0.5", "0, 2, 0.7071067811865476, 0"})
        @DisplayName("5.1 to stereo is L + clev C + slev Ls and R + clev C + slev Rs (A/52 7.8.2)")
        void ac3(int cmixlev, int surmixlev, double clev, double slev) {
            // A/52 Tables 5.9 and 5.10: 0.707 (-3 dB), 0.595 (-4.5 dB), 0.500 (-6 dB) and 0
            var options = ChannelMixerOptions.ofAc3MixLevels(cmixlev, surmixlev);
            assertEquals(clev, options.centerMixLevel(), 1e-12);
            assertEquals(slev, options.surroundMixLevel(), 1e-12);
            var matrix = new ChannelMixer(L51, STEREO, options).matrix();
            // FL FR FC LFE BL BR
            assertRow(new double[]{1, 0, clev, 0, slev, 0}, matrix[0]);
            assertRow(new double[]{0, 1, clev, 0, 0, slev}, matrix[1]);
        }

        @Test
        @DisplayName("the defaults reproduce the BS.775 matrix exactly")
        void defaults() {
            for (var input : List.of(L51, L71, QUAD, L30, L31)) {
                for (var output : List.of(STEREO, ChannelLayout.MONO, L30, QUAD)) {
                    assertArrayEquals(new ChannelMixer(input, output).matrix(),
                            new ChannelMixer(input, output, ChannelMixerOptions.defaults()).matrix());
                }
            }
        }

        @Test
        @DisplayName("a single back surround folds at -3 dB times slev, as A/52 3/1 does")
        void singleSurround() {
            var options = ChannelMixerOptions.builder().surroundMixLevel(0.5).build();
            var matrix = new ChannelMixer(L31, STEREO, options).matrix();
            // FL FR FC BC
            assertRow(new double[]{1, 0, K, 0.5 * K}, matrix[0]);
            assertRow(new double[]{0, 1, K, 0.5 * K}, matrix[1]);
        }

        @Test
        @DisplayName("the low-frequency channel goes to the center, or to the fronts at -3 dB")
        void lowFrequency() {
            var options = ChannelMixerOptions.builder().lfeMixLevel(2).build();
            var stereo = new ChannelMixer(L51, STEREO, options).matrix();
            assertEquals((float) (2 * K), stereo[0][3], 1e-7f);
            assertEquals((float) (2 * K), stereo[1][3], 1e-7f);
            var threeZero = new ChannelMixer(L51, L30, options).matrix();
            assertEquals(2f, threeZero[2][3]);
            assertEquals(0f, threeZero[0][3]);
            assertEquals(0f, new ChannelMixer(L51, STEREO).matrix()[0][3], "dropped by default");
        }
    }

    @Nested
    @DisplayName("Lt/Rt")
    class MatrixEncoded {
        private final ChannelMixerOptions ltRt = ChannelMixerOptions.builder()
                .stereoDownmix(ChannelMixerOptions.StereoDownmix.LT_RT).build();

        @Test
        @DisplayName("5.1 is Lt = L + 0.707 C - 0.707 (Ls + Rs), Rt = R + 0.707 C + 0.707 (Ls + Rs)")
        void fivePointOne() {
            var matrix = new ChannelMixer(L51, STEREO, ltRt).matrix();
            assertRow(new double[]{1, 0, K, 0, -K, -K}, matrix[0]);
            assertRow(new double[]{0, 1, K, 0, K, K}, matrix[1]);
        }

        @Test
        @DisplayName("7.1 folds the side pair into the back pair at -3 dB before the matrix")
        void sevenPointOne() {
            var matrix = new ChannelMixer(L71, STEREO, ltRt).matrix();
            // FL FR FC LFE BL BR SL SR; SL folds into BL at -3 dB, so it reaches the surround sum at -3 dB
            assertRow(new double[]{1, 0, K, 0, -K, -K, -K * K, -K * K}, matrix[0]);
            assertRow(new double[]{0, 1, K, 0, K, K, K * K, K * K}, matrix[1]);
        }

        @Test
        @DisplayName("a quad input has no center and a 3/1 input sums its single surround")
        void otherLayouts() {
            var quad = new ChannelMixer(QUAD, STEREO, ltRt).matrix();
            assertRow(new double[]{1, 0, -K, -K}, quad[0]);
            assertRow(new double[]{0, 1, K, K}, quad[1]);
            var threeOne = new ChannelMixer(L31, STEREO, ltRt).matrix();
            // the back center splits at -3 dB into the back pair: S = 2 * K * BC
            assertRow(new double[]{1, 0, K, -2 * K * K}, threeOne[0]);
            assertRow(new double[]{0, 1, K, 2 * K * K}, threeOne[1]);
        }

        @Test
        @DisplayName("uses the set levels, and leaves stereo, mono and non-stereo outputs to the rules")
        void levelsAndScope() {
            var levels = ltRt.toBuilder().centerMixLevel(0.5).surroundMixLevel(0.25).build();
            var matrix = new ChannelMixer(L51, STEREO, levels).matrix();
            assertRow(new double[]{1, 0, 0.5, 0, -0.25, -0.25}, matrix[0]);
            assertArrayEquals(new ChannelMixer(STEREO, STEREO).matrix(), new ChannelMixer(STEREO, STEREO, ltRt).matrix());
            assertArrayEquals(new ChannelMixer(ChannelLayout.MONO, STEREO).matrix(),
                    new ChannelMixer(ChannelLayout.MONO, STEREO, ltRt).matrix());
            assertArrayEquals(new ChannelMixer(L51, ChannelLayout.MONO).matrix(),
                    new ChannelMixer(L51, ChannelLayout.MONO, ltRt).matrix());
            assertArrayEquals(new ChannelMixer(L71, L51).matrix(), new ChannelMixer(L71, L51, ltRt).matrix());
        }

        @Test
        @DisplayName("a surround-only signal cancels in the Lt + Rt sum and survives in Lt - Rt")
        void steering() {
            var mixer = new ChannelMixer(L51, STEREO, ltRt);
            var frames = 64;
            var in = new float[6][frames];
            var random = new SplittableRandom(3);
            for (var i = 0; i < frames; i++) {
                in[4][i] = (float) random.nextDouble(-0.5, 0.5);
                in[5][i] = in[4][i];
            }
            var out = new float[2][frames];
            mixer.process(in, out, frames);
            for (var i = 0; i < frames; i++) {
                assertEquals(0, out[0][i] + out[1][i], 1e-6);
                assertEquals(-2 * 2 * K * in[4][i], out[0][i] - out[1][i], 1e-6);
            }
        }
    }

    @Nested
    @DisplayName("caller matrix")
    class Custom {
        @Test
        @DisplayName("replaces the rules, even between equal layouts")
        void swap() {
            var options = ChannelMixerOptions.ofMatrix(new double[][]{{0, 1}, {1, 0}});
            var mixer = new ChannelMixer(STEREO, STEREO, options);
            var in = new float[][]{{1, 2, 3}, {4, 5, 6}};
            var out = new float[2][3];
            mixer.process(in, out, 3);
            assertArrayEquals(in[1], out[0]);
            assertArrayEquals(in[0], out[1]);
        }

        @Test
        @DisplayName("is normalized when asked and copied defensively")
        void normalizedAndCopied() {
            var rows = new double[][]{{1, 1, 2}};
            var options = ChannelMixerOptions.builder().matrix(rows).normalize(true).build();
            rows[0][0] = 9;
            var matrix = new ChannelMixer(L30, ChannelLayout.MONO, options).matrix();
            assertRow(new double[]{0.25, 0.25, 0.5}, matrix[0]);
            var copy = options.matrix().orElseThrow();
            copy[0][0] = 7;
            assertEquals(1, options.matrix().orElseThrow()[0][0]);
            assertEquals(options, options.toBuilder().build());
            assertNotEquals(options, options.toBuilder().ruleMatrix().build());
        }

        @Test
        @DisplayName("rejects layouts whose channel counts differ from the matrix")
        void dimensions() {
            var options = ChannelMixerOptions.ofMatrix(new double[][]{{1, 0}, {0, 1}});
            assertThrows(IllegalArgumentException.class, () -> new ChannelMixer(L51, STEREO, options));
            assertThrows(IllegalArgumentException.class, () -> new ChannelMixer(STEREO, ChannelLayout.MONO, options));
        }
    }

    @Test
    @DisplayName("rejects levels out of range, reserved AC-3 codes and malformed matrices")
    void validation() {
        assertThrows(IllegalArgumentException.class, () -> ChannelMixerOptions.builder().centerMixLevel(-0.1).build());
        assertThrows(IllegalArgumentException.class, () -> ChannelMixerOptions.builder().surroundMixLevel(4.5).build());
        assertThrows(IllegalArgumentException.class, () -> ChannelMixerOptions.builder().lfeMixLevel(Double.NaN).build());
        assertThrows(IllegalArgumentException.class, () -> ChannelMixerOptions.ofAc3MixLevels(3, 0));
        assertThrows(IllegalArgumentException.class, () -> ChannelMixerOptions.ofAc3MixLevels(0, 3));
        assertThrows(IllegalArgumentException.class, () -> ChannelMixerOptions.ofMatrix(new double[0][]));
        assertThrows(IllegalArgumentException.class, () -> ChannelMixerOptions.ofMatrix(new double[][]{{1}, {1, 2}}));
        assertThrows(IllegalArgumentException.class,
                () -> ChannelMixerOptions.ofMatrix(new double[][]{{Double.POSITIVE_INFINITY}}));
        assertThrows(NullPointerException.class, () -> ChannelMixerOptions.builder().matrix(new double[][]{null}));
        assertThrows(NullPointerException.class, () -> ChannelMixerOptions.builder().stereoDownmix(null));
    }
}
