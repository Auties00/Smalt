package com.github.auties00.smalt.image.jpeg.internal;

import com.github.auties00.smalt.MediaException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Arrays;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Checks the lossless JPEG decoder against streams of the test encoder: every predictor, component
 * count, precision, restart interval and point transform.
 */
class LosslessJpegTest {
    static int[] noise(int count, int precision, long seed) {
        var values = new int[count];
        var state = seed;
        var max = (1 << precision) - 1;
        for (var i = 0; i < count; i++) {
            state = state * 6364136223846793005L + 1442695040888963407L;
            var smooth = (int) ((Math.sin(i * 0.01) + 1) * max * 0.4);
            values[i] = Math.clamp(smooth + (int) ((state >>> 58) - 32), 0, max);
        }
        return values;
    }

    static int[] decode(byte[] stream) {
        var jpeg = new LosslessJpeg(stream, 0, stream.length);
        var stride = jpeg.width() * jpeg.components();
        var out = new int[stride * jpeg.height()];
        jpeg.decode((row, samples) -> System.arraycopy(samples, 0, out, row * stride, stride));
        return out;
    }

    static Stream<Arguments> settings() {
        var arguments = Stream.<Arguments>builder();
        for (var predictor = 1; predictor <= 7; predictor++) {
            for (var components = 1; components <= 3; components++) {
                for (var precision : new int[]{8, 12, 16}) {
                    arguments.add(Arguments.of(predictor, components, precision));
                }
            }
        }
        return arguments.build();
    }

    @ParameterizedTest(name = "predictor {0}, {1} components, {2} bits")
    @MethodSource("settings")
    @DisplayName("decodes every predictor, component count and precision exactly")
    void roundTrip(int predictor, int components, int precision) {
        var width = 37;
        var height = 11;
        var samples = noise(width * height * components, precision, predictor * 31L + components);
        var stream = Lj92Encoder.encode(samples, width, height, components, precision, predictor, 0, 0);
        assertArrayEquals(samples, decode(stream));
    }

    @Test
    @DisplayName("restart intervals of one row reset the prediction")
    void restarts() {
        var width = 40;
        var samples = noise(width * 9 * 2, 14, 7);
        var stream = Lj92Encoder.encode(samples, width, 9, 2, 14, 6, width, 0);
        assertArrayEquals(samples, decode(stream));
    }

    @Test
    @DisplayName("the point transform scales the decoded samples back up")
    void pointTransform() {
        var samples = noise(24 * 6, 12, 3);
        for (var i = 0; i < samples.length; i++) {
            samples[i] &= ~3;
        }
        var stream = Lj92Encoder.encode(samples, 24, 6, 1, 12, 1, 0, 2);
        assertArrayEquals(samples, decode(stream));
    }

    @Test
    @DisplayName("a truncated stream decodes every row with zero bits for the missing data")
    void truncated() {
        var samples = noise(64 * 32, 16, 9);
        var stream = Lj92Encoder.encode(samples, 64, 32, 1, 16, 1, 0, 0);
        var cut = Arrays.copyOf(stream, stream.length / 2);
        var rows = new int[1];
        new LosslessJpeg(cut, 0, cut.length).decode((row, values) -> rows[0]++);
        assertEquals(32, rows[0]);
    }

    @Test
    @DisplayName("damaged headers raise Malformed and other JPEG processes Unsupported")
    void headers() {
        var stream = Lj92Encoder.encode(noise(16, 8, 1), 4, 4, 1, 8, 1, 0, 0);
        assertThrows(MediaException.Malformed.class, () -> new LosslessJpeg(stream, 0, 10));
        var baseline = stream.clone();
        baseline[3] = (byte) 0xC0;
        assertThrows(MediaException.Unsupported.class, () -> new LosslessJpeg(baseline, 0, baseline.length));
        var noSoi = stream.clone();
        noSoi[1] = 0;
        assertThrows(MediaException.Malformed.class, () -> new LosslessJpeg(noSoi, 0, noSoi.length));
    }
}
