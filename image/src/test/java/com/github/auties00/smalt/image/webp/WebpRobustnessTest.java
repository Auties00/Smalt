package com.github.auties00.smalt.image.webp;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.webp.internal.alpha.AlphaDecoder;
import com.github.auties00.smalt.io.MediaSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Feeds deterministic mutants of valid files (bit flips, byte overwrites, extreme 32-bit
 * values over size fields, truncations, insertions and deletions) to every entry point: probe,
 * decode, animation iteration, the container operations and the ALPH decoder. Each must either
 * succeed or throw a {@link MediaException}, within a time bound; the outcome counts are
 * written to {@code target/webp-scratch/mutation-stats.txt}.
 */
class WebpRobustnessTest {
    static final int MUTANTS = 300;

    /** Tight limits so hostile headers cannot make the test allocate gigabytes within the rules. */
    static final ImageDecoderOptions OPTIONS = ImageDecoderOptions.builder().limits(MediaLimits.builder()
            .maxDimension(4096).maxPixels(4L << 20).maxFrames(64).maxChannels(4).maxBytes(64L << 20).build()).build();

    static final Map<String, LongAdder> OUTCOMES = new ConcurrentHashMap<>();

    static byte[] input(String name) {
        return switch (name) {
            case "animation" -> {
                var encoder = new WebpAnimationEncoder(40, 30, WebpAnimationEncoderOptions.lossless());
                var random = new Random(9);
                for (var i = 0; i < 3; i++) {
                    var rgba = new byte[40 * 30 * 4];
                    for (var p = 0; p < rgba.length; p++) {
                        rgba[p] = (byte) (p * (i + 1) + random.nextInt(4));
                    }
                    encoder.addFrame(RgbaPicture.of(40, 30, 160, rgba, true), 40);
                }
                yield encoder.finish();
            }
            case "extended" -> {
                var rgba = new byte[24 * 16 * 4];
                new Random(3).nextBytes(rgba);
                var still = WebpEncoder.encode(RgbaPicture.of(24, 16, 96, rgba, true), WebpEncoderOptions.lossless());
                yield WebpContainer.withExif(still, "MM\0*\0\0\0\u0008\0\0\0\0\0\0".getBytes(StandardCharsets.ISO_8859_1));
            }
            default -> LibwebpTestData.read(name);
        };
    }

    static byte[] mutate(byte[] source, Random random) {
        var data = source.clone();
        switch (random.nextInt(7)) {
            case 0 -> {
                for (var i = 1 + random.nextInt(8); i > 0; i--) {
                    var bit = random.nextInt(data.length * 8);
                    data[bit >> 3] ^= (byte) (1 << (bit & 7));
                }
            }
            case 1 -> {
                for (var i = 1 + random.nextInt(4); i > 0; i--) {
                    data[random.nextInt(data.length)] = (byte) switch (random.nextInt(3)) {
                        case 0 -> 0;
                        case 1 -> 0xFF;
                        default -> random.nextInt(256);
                    };
                }
            }
            case 2 -> {
                if (data.length >= 4) {
                    var at = random.nextInt(data.length - 3);
                    var value = switch (random.nextInt(4)) {
                        case 0 -> 0xFFFFFFFF;
                        case 1 -> 0x7FFFFFFF;
                        case 2 -> 0;
                        default -> 0xFFFFFFF0 + random.nextInt(16);
                    };
                    for (var i = 0; i < 4; i++) {
                        data[at + i] = (byte) (value >>> (8 * i));
                    }
                }
            }
            case 3 -> data = Arrays.copyOf(data, random.nextInt(data.length));
            case 4 -> {
                var at = random.nextInt(data.length + 1);
                var extra = new byte[1 + random.nextInt(16)];
                random.nextBytes(extra);
                var out = new byte[data.length + extra.length];
                System.arraycopy(data, 0, out, 0, at);
                System.arraycopy(extra, 0, out, at, extra.length);
                System.arraycopy(data, at, out, at + extra.length, data.length - at);
                data = out;
            }
            case 5 -> {
                var at = random.nextInt(data.length);
                var count = Math.min(data.length - at, 1 + random.nextInt(16));
                var out = new byte[data.length - count];
                System.arraycopy(data, 0, out, 0, at);
                System.arraycopy(data, at + count, out, at, data.length - at - count);
                data = out;
            }
            default -> {
                // Corrupt the tail region, where most of the entropy-coded data lives.
                var start = data.length / 2;
                for (var i = 0; i < 8 && start < data.length; i++) {
                    data[start + random.nextInt(data.length - start)] = (byte) random.nextInt(256);
                }
            }
        }
        return data;
    }

    static void record(String operation, Throwable outcome) {
        var key = operation + ": " + (outcome == null ? "ok" : outcome.getClass().getSimpleName());
        OUTCOMES.computeIfAbsent(key, _ -> new LongAdder()).increment();
    }

    static void attempt(String operation, Runnable action) {
        try {
            action.run();
            record(operation, null);
        } catch (MediaException exception) {
            record(operation, exception);
        } catch (Throwable throwable) {
            throw new AssertionError(operation + " threw a non-media exception", throwable);
        }
    }

    static void exercise(byte[] data) {
        attempt("probe", () -> WebpDecoder.probe(MediaSource.of(data)));
        attempt("decode", () -> WebpDecoder.decode(MediaSource.of(data), OPTIONS));
        attempt("animation", () -> {
            try (var reader = WebpDecoder.openAnimation(MediaSource.of(data), OPTIONS)) {
                var frames = 0;
                while (frames < 16 && reader.read() != null) {
                    frames++;
                }
            }
        });
        attempt("container", () -> {
            WebpContainer.parse(data);
            WebpContainer.isAnimated(data);
            WebpContainer.exif(data);
            WebpContainer.withExif(data, new byte[]{1, 2, 3});
        });
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"lossless_vec_1_7.webp", "dual_transform.webp", "lossless4.webp", "bad_palette_index.webp",
            "lossless_vec_2_13.webp", "alpha_color_cache.webp", "alpha_no_compression.webp", "one_color_no_palette.webp",
            "animation", "extended"})
    @DisplayName("mutants either decode or fail with a MediaException, promptly")
    void mutants(String name) {
        var source = input(name);
        exercise(source);
        var random = new Random(name.hashCode());
        assertTimeoutPreemptively(Duration.ofSeconds(90), () -> {
            for (var i = 0; i < MUTANTS; i++) {
                exercise(mutate(source, random));
            }
        });
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"alpha_color_cache.webp", "alpha_no_compression.webp", "alpha_filter_1_method_1.webp",
            "alpha_filter_3_method_1.webp", "lossy_alpha1.webp"})
    @DisplayName("ALPH payload mutants either decode or fail with a MediaException, promptly")
    void alphaMutants(String name) {
        var file = LibwebpTestData.read(name);
        byte[] alph = null;
        int width = 0;
        int height = 0;
        for (var chunk : WebpContainer.parse(file)) {
            if (chunk.fourCc().equals("ALPH")) {
                alph = Arrays.copyOfRange(file, chunk.payloadOffset(), chunk.payloadOffset() + chunk.size());
            } else if (chunk.fourCc().equals("VP8X")) {
                width = 1 + WebpStructure.le24(file, chunk.payloadOffset() + 4);
                height = 1 + WebpStructure.le24(file, chunk.payloadOffset() + 7);
            }
        }
        assertNotNull(alph);
        var source = alph;
        var w = width;
        var h = height;
        var random = new Random(name.hashCode() * 31L);
        assertTimeoutPreemptively(Duration.ofSeconds(90), () -> {
            for (var i = 0; i < MUTANTS; i++) {
                var mutant = mutate(source, random);
                attempt("alph", () -> AlphaDecoder.decode(mutant, 0, mutant.length, w, h, OPTIONS.limits()));
            }
        });
    }

    @AfterAll
    static void writeStats() throws Exception {
        var sorted = new TreeMap<String, Long>();
        OUTCOMES.forEach((key, value) -> sorted.put(key, value.sum()));
        var lines = new ArrayList<String>();
        sorted.forEach((key, value) -> lines.add(key + " " + value));
        Files.write(WebpTestSupport.scratchPath("mutation-stats.txt"), lines);
        assertFalse(List.copyOf(lines).isEmpty());
    }
}
