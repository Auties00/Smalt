package com.github.auties00.smalt.audio.process;

import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.audio.Speaker;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.stream.Stream;

import static com.github.auties00.smalt.audio.Speaker.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers the channel mixer: the exact coefficient matrix for every pair of standard layouts
 * (written out by hand from the documented BS.775 rules), normalization, unknown speakers,
 * processing, and bit-identical scalar and vector kernels.
 */
class ChannelMixerTest {
    static final ChannelLayout MONO = ChannelLayout.MONO;
    static final ChannelLayout STEREO = ChannelLayout.STEREO;
    static final ChannelLayout L30 = layout(FRONT_LEFT, FRONT_RIGHT, FRONT_CENTER);
    static final ChannelLayout QUAD = layout(FRONT_LEFT, FRONT_RIGHT, BACK_LEFT, BACK_RIGHT);
    static final ChannelLayout L50 = layout(FRONT_LEFT, FRONT_RIGHT, FRONT_CENTER, BACK_LEFT, BACK_RIGHT);
    static final ChannelLayout L51 = ChannelLayout.SURROUND_5_1;
    static final ChannelLayout L61 = ChannelLayout.defaultFor(7);
    static final ChannelLayout L71 = ChannelLayout.SURROUND_7_1;
    static final ChannelLayout L51S = layout(FRONT_LEFT, FRONT_RIGHT, FRONT_CENTER, LOW_FREQUENCY, SIDE_LEFT, SIDE_RIGHT);

    static final Map<String, ChannelLayout> LAYOUTS = Map.of("mono", MONO, "stereo", STEREO, "3.0", L30, "quad", QUAD,
            "5.0", L50, "5.1", L51, "6.1", L61, "7.1", L71, "5.1side", L51S);

    static ChannelLayout layout(Speaker... speakers) {
        return new ChannelLayout(List.of(speakers));
    }

    /**
     * The expected matrices, one row per output speaker, written as {@code OUT=term+term}; a
     * term is {@code [coefficient*]SPEAKER} with coefficient {@code k = sqrt(1/2)}, {@code h = 1/2}
     * or {@code q = sqrt(1/2)/2}. Output speakers not listed are silent.
     */
    static Stream<Arguments> pairs() {
        return Stream.of(
                // mono input
                pair("mono", "stereo", "FL=FC; FR=FC"),
                pair("mono", "3.0", "FC=FC"),
                pair("mono", "quad", "FL=FC; FR=FC"),
                pair("mono", "5.0", "FC=FC"),
                pair("mono", "5.1", "FC=FC"),
                pair("mono", "6.1", "FC=FC"),
                pair("mono", "7.1", "FC=FC"),
                pair("mono", "5.1side", "FC=FC"),
                // stereo input
                pair("stereo", "mono", "FC=h*FL+h*FR"),
                pair("stereo", "3.0", "FL=FL; FR=FR"),
                pair("stereo", "quad", "FL=FL; FR=FR"),
                pair("stereo", "5.0", "FL=FL; FR=FR"),
                pair("stereo", "5.1", "FL=FL; FR=FR"),
                pair("stereo", "6.1", "FL=FL; FR=FR"),
                pair("stereo", "7.1", "FL=FL; FR=FR"),
                pair("stereo", "5.1side", "FL=FL; FR=FR"),
                // 3.0 input
                pair("3.0", "mono", "FC=h*FL+h*FR+k*FC"),
                pair("3.0", "stereo", "FL=FL+k*FC; FR=FR+k*FC"),
                pair("3.0", "quad", "FL=FL+k*FC; FR=FR+k*FC"),
                pair("3.0", "5.0", "FL=FL; FR=FR; FC=FC"),
                pair("3.0", "5.1", "FL=FL; FR=FR; FC=FC"),
                pair("3.0", "6.1", "FL=FL; FR=FR; FC=FC"),
                pair("3.0", "7.1", "FL=FL; FR=FR; FC=FC"),
                pair("3.0", "5.1side", "FL=FL; FR=FR; FC=FC"),
                // quad input
                pair("quad", "mono", "FC=h*FL+h*FR+q*BL+q*BR"),
                pair("quad", "stereo", "FL=FL+k*BL; FR=FR+k*BR"),
                pair("quad", "3.0", "FL=FL+k*BL; FR=FR+k*BR"),
                pair("quad", "5.0", "FL=FL; FR=FR; BL=BL; BR=BR"),
                pair("quad", "5.1", "FL=FL; FR=FR; BL=BL; BR=BR"),
                pair("quad", "6.1", "FL=FL; FR=FR; SL=BL; SR=BR"),
                pair("quad", "7.1", "FL=FL; FR=FR; BL=BL; BR=BR"),
                pair("quad", "5.1side", "FL=FL; FR=FR; SL=BL; SR=BR"),
                // 5.0 input
                pair("5.0", "mono", "FC=h*FL+h*FR+k*FC+q*BL+q*BR"),
                pair("5.0", "stereo", "FL=FL+k*FC+k*BL; FR=FR+k*FC+k*BR"),
                pair("5.0", "3.0", "FL=FL+k*BL; FR=FR+k*BR; FC=FC"),
                pair("5.0", "quad", "FL=FL+k*FC; FR=FR+k*FC; BL=BL; BR=BR"),
                pair("5.0", "5.1", "FL=FL; FR=FR; FC=FC; BL=BL; BR=BR"),
                pair("5.0", "6.1", "FL=FL; FR=FR; FC=FC; SL=BL; SR=BR"),
                pair("5.0", "7.1", "FL=FL; FR=FR; FC=FC; BL=BL; BR=BR"),
                pair("5.0", "5.1side", "FL=FL; FR=FR; FC=FC; SL=BL; SR=BR"),
                // 5.1 input
                pair("5.1", "mono", "FC=h*FL+h*FR+k*FC+q*BL+q*BR"),
                pair("5.1", "stereo", "FL=FL+k*FC+k*BL; FR=FR+k*FC+k*BR"),
                pair("5.1", "3.0", "FL=FL+k*BL; FR=FR+k*BR; FC=FC"),
                pair("5.1", "quad", "FL=FL+k*FC; FR=FR+k*FC; BL=BL; BR=BR"),
                pair("5.1", "5.0", "FL=FL; FR=FR; FC=FC; BL=BL; BR=BR"),
                pair("5.1", "6.1", "FL=FL; FR=FR; FC=FC; LFE=LFE; SL=BL; SR=BR"),
                pair("5.1", "7.1", "FL=FL; FR=FR; FC=FC; LFE=LFE; BL=BL; BR=BR"),
                pair("5.1", "5.1side", "FL=FL; FR=FR; FC=FC; LFE=LFE; SL=BL; SR=BR"),
                // 6.1 input
                pair("6.1", "mono", "FC=h*FL+h*FR+k*FC+h*BC+q*SL+q*SR"),
                pair("6.1", "stereo", "FL=FL+k*FC+h*BC+k*SL; FR=FR+k*FC+h*BC+k*SR"),
                pair("6.1", "3.0", "FL=FL+h*BC+k*SL; FR=FR+h*BC+k*SR; FC=FC"),
                pair("6.1", "quad", "FL=FL+k*FC; FR=FR+k*FC; BL=k*BC+SL; BR=k*BC+SR"),
                pair("6.1", "5.0", "FL=FL; FR=FR; FC=FC; BL=k*BC+SL; BR=k*BC+SR"),
                pair("6.1", "5.1", "FL=FL; FR=FR; FC=FC; LFE=LFE; BL=k*BC+SL; BR=k*BC+SR"),
                pair("6.1", "7.1", "FL=FL; FR=FR; FC=FC; LFE=LFE; BL=k*BC; BR=k*BC; SL=SL; SR=SR"),
                pair("6.1", "5.1side", "FL=FL; FR=FR; FC=FC; LFE=LFE; SL=k*BC+SL; SR=k*BC+SR"),
                // 7.1 input
                pair("7.1", "mono", "FC=h*FL+h*FR+k*FC+q*BL+q*BR+q*SL+q*SR"),
                pair("7.1", "stereo", "FL=FL+k*FC+k*BL+k*SL; FR=FR+k*FC+k*BR+k*SR"),
                pair("7.1", "3.0", "FL=FL+k*BL+k*SL; FR=FR+k*BR+k*SR; FC=FC"),
                pair("7.1", "quad", "FL=FL+k*FC; FR=FR+k*FC; BL=BL+k*SL; BR=BR+k*SR"),
                pair("7.1", "5.0", "FL=FL; FR=FR; FC=FC; BL=BL+k*SL; BR=BR+k*SR"),
                pair("7.1", "5.1", "FL=FL; FR=FR; FC=FC; LFE=LFE; BL=BL+k*SL; BR=BR+k*SR"),
                pair("7.1", "6.1", "FL=FL; FR=FR; FC=FC; LFE=LFE; SL=SL+k*BL; SR=SR+k*BR"),
                pair("7.1", "5.1side", "FL=FL; FR=FR; FC=FC; LFE=LFE; SL=SL+k*BL; SR=SR+k*BR"),
                // 5.1 side input
                pair("5.1side", "mono", "FC=h*FL+h*FR+k*FC+q*SL+q*SR"),
                pair("5.1side", "stereo", "FL=FL+k*FC+k*SL; FR=FR+k*FC+k*SR"),
                pair("5.1side", "3.0", "FL=FL+k*SL; FR=FR+k*SR; FC=FC"),
                pair("5.1side", "quad", "FL=FL+k*FC; FR=FR+k*FC; BL=SL; BR=SR"),
                pair("5.1side", "5.0", "FL=FL; FR=FR; FC=FC; BL=SL; BR=SR"),
                pair("5.1side", "5.1", "FL=FL; FR=FR; FC=FC; LFE=LFE; BL=SL; BR=SR"),
                pair("5.1side", "6.1", "FL=FL; FR=FR; FC=FC; LFE=LFE; SL=SL; SR=SR"),
                pair("5.1side", "7.1", "FL=FL; FR=FR; FC=FC; LFE=LFE; SL=SL; SR=SR"));
    }

    static Arguments pair(String input, String output, String spec) {
        return Arguments.of(input, output, spec);
    }

    static Speaker speaker(String name) {
        return switch (name) {
            case "FL" -> FRONT_LEFT;
            case "FR" -> FRONT_RIGHT;
            case "FC" -> FRONT_CENTER;
            case "LFE" -> LOW_FREQUENCY;
            case "BL" -> BACK_LEFT;
            case "BR" -> BACK_RIGHT;
            case "BC" -> BACK_CENTER;
            case "SL" -> SIDE_LEFT;
            case "SR" -> SIDE_RIGHT;
            default -> throw new IllegalArgumentException(name);
        };
    }

    static double coefficient(String name) {
        return switch (name) {
            case "k" -> Math.sqrt(0.5);
            case "h" -> 0.5;
            case "q" -> Math.sqrt(0.5) / 2;
            default -> Double.parseDouble(name);
        };
    }

    static float[][] expected(ChannelLayout input, ChannelLayout output, String spec) {
        var matrix = new float[output.channels()][input.channels()];
        for (var row : spec.split(";")) {
            var parts = row.trim().split("=");
            var out = output.indexOf(speaker(parts[0]));
            assertTrue(out >= 0, "spec names output " + parts[0]);
            for (var term : parts[1].split("\\+")) {
                var factors = term.split("\\*");
                var gain = factors.length == 2 ? coefficient(factors[0]) : 1.0;
                var in = input.indexOf(speaker(factors[factors.length - 1]));
                assertTrue(in >= 0, "spec names input " + term);
                matrix[out][in] = (float) gain;
            }
        }
        return matrix;
    }

    static void assertMatrix(float[][] expected, float[][] actual, String label) {
        assertEquals(expected.length, actual.length, label);
        for (var o = 0; o < expected.length; o++) {
            for (var i = 0; i < expected[o].length; i++) {
                assertEquals(expected[o][i], actual[o][i], 1e-7f, label + " [" + o + "][" + i + "]");
            }
        }
    }

    @Nested
    @DisplayName("coefficients")
    class Coefficients {
        @ParameterizedTest(name = "{0} -> {1}")
        @MethodSource("com.github.auties00.smalt.audio.process.ChannelMixerTest#pairs")
        @DisplayName("follow the documented BS.775 rules exactly")
        void pairs(String input, String output, String spec) {
            var in = LAYOUTS.get(input);
            var out = LAYOUTS.get(output);
            assertMatrix(expected(in, out, spec), new ChannelMixer(in, out).matrix(), input + " -> " + output);
        }

        @Test
        @DisplayName("covers every ordered pair of standard layouts")
        void complete() {
            assertEquals(LAYOUTS.size() * (LAYOUTS.size() - 1), ChannelMixerTest.pairs().count());
        }

        @Test
        @DisplayName("is the identity for equal layouts")
        void identity() {
            for (var layout : LAYOUTS.values()) {
                var matrix = new ChannelMixer(layout, layout).matrix();
                for (var o = 0; o < matrix.length; o++) {
                    for (var i = 0; i < matrix.length; i++) {
                        assertEquals(o == i ? 1.0f : 0.0f, matrix[o][i]);
                    }
                }
            }
        }

        @Test
        @DisplayName("maps unknown speakers by index")
        void unknown() {
            var unknown3 = new ChannelLayout(Collections.nCopies(3, UNKNOWN));
            assertMatrix(new float[][]{{1, 0, 0}, {0, 1, 0}}, new ChannelMixer(unknown3, STEREO).matrix(), "unknown -> stereo");
            var unknown4 = new ChannelLayout(Collections.nCopies(4, UNKNOWN));
            assertMatrix(new float[][]{{1, 0}, {0, 1}, {0, 0}, {0, 0}}, new ChannelMixer(STEREO, unknown4).matrix(), "stereo -> unknown");
            var mixed = layout(FRONT_LEFT, FRONT_RIGHT, UNKNOWN);
            assertMatrix(new float[][]{{1, 0, 0}, {0, 1, 0}, {0, 0, 1}, {0, 0, 0}},
                    new ChannelMixer(mixed, QUAD).matrix(), "FL FR ? -> quad");
            var single = new ChannelLayout(List.of(UNKNOWN));
            assertMatrix(new float[][]{{1}, {1}}, new ChannelMixer(single, STEREO).matrix(), "unknown mono -> stereo");
        }

        @Test
        @DisplayName("folds top and front-of-center speakers")
        void exotic() {
            var input = layout(FRONT_LEFT_OF_CENTER, FRONT_RIGHT_OF_CENTER, TOP_FRONT_LEFT, TOP_FRONT_RIGHT, TOP_CENTER);
            var k = (float) Math.sqrt(0.5);
            assertMatrix(new float[][]{{1, 0, k, 0, 0.5f}, {0, 1, 0, k, 0.5f}}, new ChannelMixer(input, STEREO).matrix(), "exotic -> stereo");
        }

        @Test
        @DisplayName("folds the wide, surround direct, second LFE, top side and bottom speakers")
        void extended() {
            var k = (float) Math.sqrt(0.5);
            var input = layout(WIDE_LEFT, WIDE_RIGHT, SURROUND_DIRECT_LEFT, SURROUND_DIRECT_RIGHT, LOW_FREQUENCY_2,
                    TOP_SIDE_LEFT, TOP_SIDE_RIGHT, BOTTOM_FRONT_CENTER, BOTTOM_FRONT_LEFT, BOTTOM_FRONT_RIGHT);
            assertMatrix(new float[][]{
                    {1, 0, 0, 0, 0, 0, 0, 0, k, 0},
                    {0, 1, 0, 0, 0, 0, 0, 0, 0, k},
                    {0, 0, 0, 0, 0, 0, 0, k, 0, 0},
                    {0, 0, 0, 0, 1, 0, 0, 0, 0, 0},
                    {0, 0, 1, 0, 0, k, 0, 0, 0, 0},
                    {0, 0, 0, 1, 0, 0, k, 0, 0, 0}}, new ChannelMixer(input, L51S).matrix(), "extended -> 5.1 side");
            var doubled = layout(FRONT_LEFT, FRONT_RIGHT, LOW_FREQUENCY, SIDE_LEFT, SIDE_RIGHT, SURROUND_DIRECT_LEFT,
                    SURROUND_DIRECT_RIGHT, LOW_FREQUENCY_2);
            assertMatrix(new float[][]{
                    {1, 0, 0, 0, 0, 0, 0, 0},
                    {0, 1, 0, 0, 0, 0, 0, 0},
                    {0, 0, 0, 0, 0, 0, 0, 0},
                    {0, 0, 1, 0, 0, 0, 0, k},
                    {0, 0, 0, 1, 0, k, 0, 0},
                    {0, 0, 0, 0, 1, 0, k, 0}}, new ChannelMixer(doubled, L51S).matrix(), "doubled -> 5.1 side");
        }

        @Test
        @DisplayName("routes every channel of 22.2 but the LFE pair into stereo")
        void twentyTwoTwo() {
            var input = layout(FRONT_LEFT, FRONT_RIGHT, FRONT_CENTER, LOW_FREQUENCY, BACK_LEFT, BACK_RIGHT,
                    FRONT_LEFT_OF_CENTER, FRONT_RIGHT_OF_CENTER, BACK_CENTER, SIDE_LEFT, SIDE_RIGHT, TOP_CENTER,
                    TOP_FRONT_LEFT, TOP_FRONT_CENTER, TOP_FRONT_RIGHT, TOP_BACK_LEFT, TOP_BACK_CENTER, TOP_BACK_RIGHT,
                    LOW_FREQUENCY_2, TOP_SIDE_LEFT, TOP_SIDE_RIGHT, BOTTOM_FRONT_CENTER, BOTTOM_FRONT_LEFT,
                    BOTTOM_FRONT_RIGHT);
            var matrix = new ChannelMixer(input, STEREO).matrix();
            for (var i = 0; i < input.channels(); i++) {
                var speaker = input.speakers().get(i);
                var reached = matrix[0][i] != 0 || matrix[1][i] != 0;
                assertEquals(speaker != LOW_FREQUENCY && speaker != LOW_FREQUENCY_2, reached, speaker.name());
                if (speaker.name().endsWith("LEFT")) {
                    assertTrue(matrix[0][i] > matrix[1][i], speaker.name());
                } else if (speaker.name().endsWith("RIGHT")) {
                    assertTrue(matrix[1][i] > matrix[0][i], speaker.name());
                }
            }
        }

        @Test
        @DisplayName("normalizes rows whose absolute gains exceed one")
        void normalized() {
            var k = Math.sqrt(0.5);
            var sum = 1 + 2 * k;
            var matrix = new ChannelMixer(L51, STEREO, true).matrix();
            assertMatrix(new float[][]{
                    {(float) (1 / sum), 0, (float) (k / sum), 0, (float) (k / sum), 0},
                    {0, (float) (1 / sum), (float) (k / sum), 0, 0, (float) (k / sum)}}, matrix, "normalized 5.1 -> stereo");
            assertMatrix(new ChannelMixer(STEREO, MONO).matrix(), new ChannelMixer(STEREO, MONO, true).matrix(), "stereo -> mono stays");
        }
    }

    @Nested
    @DisplayName("processing")
    class Processing {
        @ParameterizedTest(name = "{0} -> {1}")
        @MethodSource("com.github.auties00.smalt.audio.process.ChannelMixerTest#pairs")
        @DisplayName("applies the matrix to every sample")
        void apply(String input, String output, String spec) {
            var in = LAYOUTS.get(input);
            var out = LAYOUTS.get(output);
            var mixer = new ChannelMixer(in, out);
            var frames = 1003;
            var source = random(in.channels(), frames, input.hashCode());
            var target = new float[out.channels()][frames + 5];
            mixer.process(source, target, frames);
            var matrix = mixer.matrix();
            for (var o = 0; o < out.channels(); o++) {
                for (var f = 0; f < frames; f++) {
                    var expected = 0.0;
                    for (var i = 0; i < in.channels(); i++) {
                        expected += (double) matrix[o][i] * source[i][f];
                    }
                    assertEquals(expected, target[o][f], 1e-6, "channel " + o + " frame " + f);
                }
            }
        }

        @Test
        @DisplayName("lets an identity mixer work in place")
        void inPlace() {
            var mixer = new ChannelMixer(STEREO, STEREO);
            var planes = random(2, 100, 1);
            var copy = new float[][]{planes[0].clone(), planes[1].clone()};
            mixer.process(planes, planes, 100);
            assertArrayEquals(copy[0], planes[0]);
            assertArrayEquals(copy[1], planes[1]);
        }

        @Test
        @DisplayName("rejects aliasing, mismatched planes and short planes")
        void validation() {
            var mixer = new ChannelMixer(STEREO, MONO);
            var planes = random(2, 10, 1);
            assertThrows(IllegalArgumentException.class, () -> mixer.process(planes, new float[][]{planes[0]}, 10));
            assertThrows(IllegalArgumentException.class, () -> mixer.process(planes, new float[2][10], 10));
            assertThrows(IllegalArgumentException.class, () -> mixer.process(planes, new float[1][5], 10));
            assertThrows(IllegalArgumentException.class, () -> mixer.process(planes, new float[1][10], -1));
            assertThrows(NullPointerException.class, () -> new ChannelMixer(null, MONO));
        }

        @Test
        @DisplayName("scalar and vector kernels are bit-identical")
        void simd() {
            var scalar = new ScalarMixKernel();
            var vector = VectorProcessKernels.mix();
            var random = new SplittableRandom(9);
            for (var trial = 0; trial < 500; trial++) {
                var count = random.nextInt(1, 9);
                var frames = random.nextInt(0, 300);
                var inputs = random(count, frames, trial);
                var gains = new float[count];
                for (var j = 0; j < count; j++) {
                    gains[j] = (float) random.nextDouble(-2, 2);
                }
                var a = new float[frames];
                var b = new float[frames];
                scalar.mix(inputs, gains, count, a, frames);
                vector.mix(inputs, gains, count, b, frames);
                assertArrayEquals(a, b, "trial " + trial);
            }
        }
    }

    static float[][] random(int channels, int frames, long seed) {
        var random = new SplittableRandom(seed);
        var result = new float[channels][frames];
        for (var c = 0; c < channels; c++) {
            for (var i = 0; i < frames; i++) {
                result[c][i] = (float) random.nextDouble(-1, 1);
            }
        }
        return result;
    }

    @Test
    @DisplayName("exposes its layouts and a defensive matrix copy")
    void accessors() {
        var mixer = new ChannelMixer(L51, STEREO);
        assertEquals(L51, mixer.input());
        assertEquals(STEREO, mixer.output());
        mixer.matrix()[0][0] = 42;
        assertEquals(1.0f, mixer.matrix()[0][0]);
    }
}
