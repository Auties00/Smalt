package com.github.auties00.smalt.image.jpeg.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.exif.ExifWriter;
import com.github.auties00.smalt.image.jpeg.JpegDecoder;
import com.github.auties00.smalt.image.jpeg.MjpegDecoder;
import com.github.auties00.smalt.image.jpeg.JpegEncoder;
import com.github.auties00.smalt.image.jpeg.JpegEncoderOptions;
import com.github.auties00.smalt.io.MediaSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Mutates valid streams of every kind (baseline with restarts, 4:4:4, gray, progressive,
 * arithmetic, CMYK, with EXIF and ICC segments) with random byte flips, truncations, segment
 * length corruption, frame header corruption and injected markers, and checks that every decode,
 * reduced-size decode, frame decode and probe either succeeds or throws a {@link MediaException},
 * within a time bound. The mutation sequence is deterministic.
 */
class JpegRobustnessTest {
    private static List<byte[]> seeds() {
        var seeds = new ArrayList<byte[]>();
        var picture = RgbaPicture.allocate(80, 56, false);
        var random = new Random(11);
        for (var i = 0; i < 80 * 56; i++) {
            picture.pixels()[4 * i] = (byte) (i % 80 * 3 + random.nextInt(20));
            picture.pixels()[4 * i + 1] = (byte) (i / 80 * 4);
            picture.pixels()[4 * i + 2] = (byte) random.nextInt(256);
        }
        seeds.add(JpegEncoder.encode(picture, JpegEncoderOptions.builder().quality(90)
                .chromaFormat(ChromaFormat.YUV420).restartInterval(3).build()));
        seeds.add(JpegEncoder.encode(picture, JpegEncoderOptions.builder().quality(60)
                .chromaFormat(ChromaFormat.YUV444).optimizeHuffman(false).build()));
        seeds.add(JpegEncoder.encode(picture, JpegEncoderOptions.builder().quality(75)
                .chromaFormat(ChromaFormat.MONOCHROME).build()));
        var coefficients = new JpegReader(seeds.get(0), 0, seeds.get(0).length, MediaLimits.DEFAULT).decodeCoefficients();
        var progressive = TestJpegWriter.of(coefficients);
        progressive.restartInterval = 4;
        seeds.add(progressive.progressive(TestJpegWriter.simpleProgression(3, true)));
        seeds.add(DjpegConformanceTest.resource("33x65-yuv420-progressive-arithmetic.jpg"));
        seeds.add(DjpegConformanceTest.resource("227x149-yuv422-arithmetic-rst3.jpg"));
        seeds.add(DjpegConformanceTest.resource("227x149-yuv411-progressive.jpg"));
        var cmyk = JpegStreamTest.fourComponents(new int[]{1, 2, 3, 4});
        cmyk.jfif = false;
        cmyk.adobeTransform = 2;
        seeds.add(cmyk.sequential(new int[]{0, 1, 2, 3}));
        var exif = ExifWriter.withOrientation(new byte[0], com.github.auties00.smalt.image.Orientation.ROTATE_90);
        var app1 = new byte[6 + exif.length];
        System.arraycopy("Exif\0\0".getBytes(), 0, app1, 0, 6);
        System.arraycopy(exif, 0, app1, 6, exif.length);
        var icc = new byte[14 + 200];
        System.arraycopy("ICC_PROFILE\0".getBytes(), 0, icc, 0, 12);
        icc[12] = 1;
        icc[13] = 1;
        seeds.add(TestJpegWriter.insertSegment(TestJpegWriter.insertSegment(seeds.get(1), 1, 0xE1, app1), 2, 0xE2, icc));
        return seeds;
    }

    private static byte[] mutate(byte[] seed, int kind, Random random) {
        var data = seed.clone();
        switch (kind) {
            case 0 -> {
                var flips = 1 + random.nextInt(8);
                for (var i = 0; i < flips; i++) {
                    data[random.nextInt(data.length)] ^= (byte) (1 + random.nextInt(255));
                }
                return data;
            }
            case 1 -> {
                var headerEnd = Math.min(data.length, sosEnd(data) + 4);
                data[random.nextInt(headerEnd)] = (byte) random.nextInt(256);
                return data;
            }
            case 2 -> {
                return java.util.Arrays.copyOf(data, random.nextInt(data.length));
            }
            case 3 -> {
                var markers = new ArrayList<Integer>();
                for (var i = 2; i + 3 < data.length && i < sosEnd(data); i++) {
                    if (data[i] == (byte) 0xFF && (data[i + 1] & 0xFF) >= 0xC0 && (data[i + 1] & 0xFF) != 0xFF
                            && ((data[i + 1] & 0xFF) < 0xD0 || (data[i + 1] & 0xFF) > 0xD9)) {
                        markers.add(i);
                    }
                }
                if (markers.isEmpty()) {
                    return data;
                }
                var at = markers.get(random.nextInt(markers.size()));
                var length = ((data[at + 2] & 0xFF) << 8) | (data[at + 3] & 0xFF);
                var replacement = switch (random.nextInt(5)) {
                    case 0 -> 0;
                    case 1 -> 1;
                    case 2 -> length + 1 + random.nextInt(3);
                    case 3 -> Math.max(0, length - 1 - random.nextInt(3));
                    default -> random.nextInt(65536);
                };
                data[at + 2] = (byte) (replacement >>> 8);
                data[at + 3] = (byte) replacement;
                return data;
            }
            case 4 -> {
                var sof = -1;
                for (var i = 2; i + 1 < data.length; i++) {
                    var code = data[i + 1] & 0xFF;
                    if (data[i] == (byte) 0xFF && code >= 0xC0 && code <= 0xCF && code != 0xC4 && code != 0xC8 && code != 0xCC) {
                        sof = i;
                        break;
                    }
                }
                if (sof < 0 || sof + 12 >= data.length) {
                    return data;
                }
                data[sof + 4 + random.nextInt(8)] = (byte) random.nextInt(256);
                return data;
            }
            default -> {
                var start = sosEnd(data);
                if (start >= data.length - 2) {
                    return data;
                }
                var at = start + random.nextInt(data.length - start - 1);
                data[at] = (byte) 0xFF;
                data[at + 1] = (byte) (random.nextBoolean() ? 0xD0 + random.nextInt(8) : random.nextInt(256));
                return data;
            }
        }
    }

    private static int sosEnd(byte[] data) {
        for (var i = 2; i + 3 < data.length; i++) {
            if (data[i] == (byte) 0xFF && data[i + 1] == (byte) 0xDA) {
                return Math.min(data.length, i + 2 + (((data[i + 2] & 0xFF) << 8) | (data[i + 3] & 0xFF)));
            }
        }
        return data.length;
    }

    @Test
    @DisplayName("every mutated stream decodes or fails with a MediaException, in bounded time")
    void mutations() {
        var seeds = seeds();
        var random = new Random(20260928L);
        var outcomes = new LinkedHashMap<String, Integer>();
        var names = new String[]{"byte flips", "header byte", "truncation", "segment length", "frame header", "injected marker"};
        var variants = 0;
        for (var seed : seeds) {
            for (var kind = 0; kind < names.length; kind++) {
                var count = kind == 0 ? 20 : 10;
                for (var i = 0; i < count; i++) {
                    var mutated = mutate(seed, kind, random);
                    variants++;
                    var outcome = assertTimeoutPreemptively(Duration.ofSeconds(10), () -> run(mutated), () -> "variant hung");
                    outcomes.merge(names[kind] + ": " + outcome, 1, Integer::sum);
                }
            }
        }
        System.out.println(variants + " variants: " + outcomes);
        assertTrue(variants >= 400);
    }

    /**
     * Runs every entry point on one input and returns how the full decode ended.
     */
    private static String run(byte[] data) {
        String result;
        try {
            JpegDecoder.decode(MediaSource.of(data), ImageDecoderOptions.defaults());
            result = "decoded";
        } catch (MediaException exception) {
            assertNoWrappedBug(exception);
            result = exception.getClass().getSimpleName();
        }
        attempt(() -> JpegDecoder.decode(MediaSource.of(data), ImageDecoderOptions.ofMinimumSize(1, 1)));
        attempt(() -> JpegDecoder.decode(MediaSource.of(data), ImageDecoderOptions.ofMinimumSize(data.length % 50 + 1, 7)));
        attempt(() -> JpegDecoder.probe(MediaSource.of(data)));
        attempt(() -> new MjpegDecoder().decodeFrame(data, 0, data.length));
        return result;
    }

    /**
     * Fails when a typed failure is only the entry points' translation of an unexpected runtime
     * exception, which would mean a validation is missing.
     */
    private static void assertNoWrappedBug(MediaException exception) {
        var cause = exception.getCause();
        if (cause instanceof RuntimeException && !(cause instanceof MediaException)) {
            throw new AssertionError("unexpected " + cause, cause);
        }
    }

    private static void attempt(Runnable action) {
        try {
            action.run();
        } catch (MediaException expected) {
            assertNoWrappedBug(expected);
        }
    }

    @Test
    @DisplayName("random bytes behind a SOI marker never escape as anything but a MediaException")
    void randomPayloads() {
        var random = new Random(99);
        for (var i = 0; i < 300; i++) {
            var data = new byte[2 + random.nextInt(600)];
            random.nextBytes(data);
            data[0] = (byte) 0xFF;
            data[1] = (byte) 0xD8;
            assertTimeoutPreemptively(Duration.ofSeconds(5), () -> run(data));
        }
    }
}
