package com.github.auties00.smalt.image.jp2;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.io.MediaSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.Arrays;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * Feeds the JPEG 2000 decoder hostile input: random mutations and truncations of conformance
 * codestreams, JP2 files and encoder output, an image over the limits, and empty input. Every
 * failure must be a {@link MediaException}, and every decode must end promptly.
 */
class Jp2RobustnessTest {
    private static byte[] encoded(Jp2EncoderOptions options) {
        var random = new Random(5);
        var picture = RgbaPicture.allocate(37, 29, true);
        for (var i = 0; i < picture.pixels().length; i++) {
            picture.pixels()[i] = (byte) (i * 7 + random.nextInt(16));
        }
        return Jp2Encoder.encode(picture, options);
    }

    private static void decodeQuietly(byte[] data) {
        try {
            Jp2Decoder.decode(MediaSource.of(data));
        } catch (MediaException expected) {
            // hostile input is reported as a media exception
        }
        try {
            Jp2Decoder.decode(MediaSource.of(data), ImageDecoderOptions.ofMinimumSize(8, 8));
        } catch (MediaException expected) {
            // likewise at a reduced resolution
        }
    }

    private static void mutate(byte[] base, long seed, int rounds) {
        var random = new Random(seed);
        assertTimeoutPreemptively(Duration.ofSeconds(180), () -> {
            for (var round = 0; round < rounds; round++) {
                var data = base.clone();
                var flips = 1 + random.nextInt(10);
                for (var i = 0; i < flips; i++) {
                    data[random.nextInt(data.length)] = (byte) random.nextInt(256);
                }
                if (random.nextInt(5) == 0) {
                    data = Arrays.copyOf(data, random.nextInt(data.length));
                }
                decodeQuietly(data);
            }
        });
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"input/conformance/p0_01.j2k", "input/conformance/p0_03.j2k", "input/conformance/p1_02.j2k",
            "input/conformance/file1.jp2", "input/conformance/file9.jp2"})
    @DisplayName("mutated conformance files fail only with media exceptions")
    void conformance(String path) {
        mutate(Jp2TestVectors.fetch(path), path.hashCode(), 120);
    }

    @Test
    @DisplayName("mutated encoder output fails only with media exceptions")
    void encoderOutput() {
        mutate(encoded(Jp2EncoderOptions.lossless()), 1, 200);
        mutate(encoded(Jp2EncoderOptions.lossy(12).toBuilder().compressionRatios(40, 20, 12).sopMarkers(true)
                .ephMarkers(true).build()), 2, 200);
    }

    @Test
    @DisplayName("mutated and truncated HTJ2K output fails only with media exceptions not caused by a bug")
    void highThroughput() {
        var inputs = new byte[][]{
                encoded(Jp2EncoderOptions.lossless().toBuilder().highThroughput(true).codeBlockSize(16, 16).build()),
                encoded(Jp2EncoderOptions.lossy(8).toBuilder().highThroughput(true).compressionRatios(30, 8)
                        .codeBlockStyles(java.util.Set.of(Jp2CodeBlockStyle.VERTICALLY_CAUSAL)).build())};
        var random = new Random(15);
        assertTimeoutPreemptively(Duration.ofSeconds(180), () -> {
            for (var input : inputs) {
                for (var length = 0; length < input.length; length += 5) {
                    decodeStrictly(Arrays.copyOf(input, length));
                }
                for (var round = 0; round < 300; round++) {
                    var data = input.clone();
                    var flips = 1 + random.nextInt(6);
                    for (var i = 0; i < flips; i++) {
                        data[random.nextInt(data.length)] = (byte) random.nextInt(256);
                    }
                    decodeStrictly(data);
                }
            }
        });
    }

    static void decodeStrictly(byte[] data) {
        try {
            Jp2Decoder.decodeChannels(MediaSource.of(data));
        } catch (MediaException exception) {
            for (var cause = exception.getCause(); cause != null; cause = cause.getCause()) {
                if (cause instanceof IndexOutOfBoundsException || cause instanceof NullPointerException
                        || cause instanceof ArithmeticException || cause instanceof NegativeArraySizeException) {
                    throw new AssertionError("decoder bug behind " + exception, cause);
                }
            }
        }
    }

    @Test
    @DisplayName("every truncation of a small tiled file fails only with media exceptions")
    void truncations() {
        var data = encoded(Jp2EncoderOptions.lossless().toBuilder().tileSize(16, 16).build());
        assertTimeoutPreemptively(Duration.ofSeconds(180), () -> {
            for (var length = 0; length < data.length; length += 3) {
                decodeQuietly(Arrays.copyOf(data, length));
            }
        });
    }

    @Test
    @DisplayName("an image over the limits and empty input are rejected")
    void limits() {
        var data = encoded(Jp2EncoderOptions.lossless());
        var limits = MediaLimits.builder().maxPixels(100).build();
        assertThrows(MediaException.LimitExceeded.class,
                () -> Jp2Decoder.decode(MediaSource.of(data), ImageDecoderOptions.builder().limits(limits).build()));
        assertThrows(MediaException.class, () -> Jp2Decoder.decode(MediaSource.of(new byte[0])));
        assertThrows(MediaException.class,
                () -> Jp2Decoder.probe(MediaSource.of(new byte[]{(byte) 0xFF, 0x4F, (byte) 0xFF, 0x51})));
    }
}
