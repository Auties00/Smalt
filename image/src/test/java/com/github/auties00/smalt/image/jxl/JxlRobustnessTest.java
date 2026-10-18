package com.github.auties00.smalt.image.jxl;

import com.github.auties00.smalt.Media;
import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.io.MediaSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Duration;
import java.util.Arrays;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Feeds deterministic mutants of conformance files (bit flips, byte overwrites, truncations,
 * insertions and deletions) to every entry point: the probes, the still, float and animation
 * decoders and the JPEG reconstruction. Each call must either succeed or throw a
 * {@link MediaException} within a time bound.
 */
class JxlRobustnessTest {
    /** Tight limits so hostile headers cannot make the test allocate gigabytes within the rules. */
    static final ImageDecoderOptions OPTIONS = ImageDecoderOptions.defaults()
            .toBuilder().limits(MediaLimits.builder().maxDimension(4096).maxPixels(4L << 20).maxFrames(64)
                    .maxChannels(8).maxBytes(64L << 20).build()).build();

    static byte[] mutate(byte[] source, Random random) {
        var data = source.clone();
        switch (random.nextInt(6)) {
            case 0 -> {
                for (var i = 1 + random.nextInt(8); i > 0; i--) {
                    var bit = random.nextInt(data.length * 8);
                    data[bit >> 3] ^= (byte) (1 << (bit & 7));
                }
            }
            case 1 -> {
                for (var i = 1 + random.nextInt(4); i > 0; i--) {
                    data[random.nextInt(data.length)] = (byte) random.nextInt(256);
                }
            }
            case 2 -> data = Arrays.copyOf(data, random.nextInt(data.length));
            case 3 -> {
                // Damage the headers, where sizes and counts live.
                var limit = Math.min(data.length, 64);
                for (var i = 1 + random.nextInt(3); i > 0; i--) {
                    data[random.nextInt(limit)] ^= (byte) (1 + random.nextInt(255));
                }
            }
            case 4 -> {
                var at = random.nextInt(data.length);
                var insert = new byte[1 + random.nextInt(16)];
                random.nextBytes(insert);
                var out = new byte[data.length + insert.length];
                System.arraycopy(data, 0, out, 0, at);
                System.arraycopy(insert, 0, out, at, insert.length);
                System.arraycopy(data, at, out, at + insert.length, data.length - at);
                data = out;
            }
            default -> {
                var at = random.nextInt(data.length);
                var length = Math.min(1 + random.nextInt(32), data.length - at);
                var out = new byte[data.length - length];
                System.arraycopy(data, 0, out, 0, at);
                System.arraycopy(data, at + length, out, at, data.length - at - length);
                data = out;
            }
        }
        return data;
    }

    static void survive(String what, Runnable call) {
        try {
            call.run();
        } catch (MediaException expected) {
            // Rejecting a corrupt file is the expected outcome.
        } catch (Throwable unexpected) {
            fail(what + " threw " + unexpected, unexpected);
        }
    }

    static void exercise(byte[] data, boolean jpeg) {
        survive("probe", () -> JxlDecoder.probe(MediaSource.of(data)));
        survive("media probe", () -> Media.probe(MediaSource.of(data)));
        survive("decode", () -> JxlDecoder.decode(MediaSource.of(data), OPTIONS));
        survive("decodeFloat", () -> JxlDecoder.decodeFloat(MediaSource.of(data), OPTIONS));
        survive("animation", () -> {
            try (var reader = JxlDecoder.openAnimation(MediaSource.of(data), OPTIONS)) {
                while (reader.read() != null) {
                    // Every frame is decoded.
                }
            }
        });
        if (jpeg) {
            survive("reconstructJpeg", () -> JxlDecoder.reconstructJpeg(MediaSource.of(data)));
        }
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({
            "grayscale, 120, false",
            "lz77_flower, 80, false",
            "delta_palette, 80, false",
            "noise, 60, false",
            "upsampling, 40, false",
            "spot, 40, false",
            "cmyk_layers, 30, false",
            "animation_icos4d, 20, false",
            "grayscale_jpeg, 120, true",
            "bench_oriented_brg, 30, true"
    })
    @DisplayName("mutants either decode or fail with a MediaException")
    void mutants(String name, int count, boolean jpeg) {
        var source = JxlVectors.read(JxlVectors.find(name).input());
        var random = new Random(name.hashCode());
        assertTimeoutPreemptively(Duration.ofMinutes(5), () -> {
            for (var i = 0; i < count; i++) {
                exercise(mutate(source, random), jpeg);
            }
        });
    }

    @Test
    @DisplayName("every truncation of a small file fails cleanly")
    void truncations() {
        var source = JxlVectors.read(JxlVectors.find("grayscale").input());
        assertTimeoutPreemptively(Duration.ofMinutes(5), () -> {
            for (var length = 0; length < source.length; length += Math.max(1, source.length / 200)) {
                var data = Arrays.copyOf(source, length);
                survive("decode " + length, () -> JxlDecoder.decode(MediaSource.of(data), OPTIONS));
            }
        });
    }

    @Test
    @DisplayName("a declared size beyond the limits is rejected before allocating")
    void limits() {
        var data = JxlVectors.read(JxlVectors.find("bike").input());
        var tiny = ImageDecoderOptions.builder().limits(MediaLimits.builder().maxPixels(1024).build()).build();
        assertThrows(MediaException.LimitExceeded.class, () -> JxlDecoder.decode(MediaSource.of(data), tiny));
    }
}
