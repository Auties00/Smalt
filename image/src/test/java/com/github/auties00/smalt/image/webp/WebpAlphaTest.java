package com.github.auties00.smalt.image.webp;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.webp.internal.alpha.AlphaDecoder;
import com.github.auties00.smalt.image.webp.internal.alpha.AlphaEncoder;
import com.github.auties00.smalt.image.webp.internal.alpha.AlphaFilters;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.Oracle;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers the ALPH codec: the prediction filters (hand-computed first row and column rules and
 * random round trips), the raw and lossless payloads, the level quantization, byte identity of
 * the encoder with cwebp's ALPH chunks, and the decoder against dwebp's alpha planes for the
 * corpus and for cwebp-generated files.
 */
class WebpAlphaTest {
    static final int STICKER_WIDTH = 96;
    static final int STICKER_HEIGHT = 80;

    record Plane(byte[] samples, int width, int height) {
    }

    static Plane randomPlane(int width, int height, long seed, int levels) {
        var random = new Random(seed);
        var samples = new byte[width * height];
        var step = 255 / Math.max(1, levels - 1);
        for (var i = 0; i < samples.length; i++) {
            samples[i] = (byte) Math.min(255, random.nextInt(levels) * step);
        }
        return new Plane(samples, width, height);
    }

    /** A soft-edged disc: opaque center, 0 corners, a smooth ramp in between. */
    static Plane discPlane(int width, int height) {
        var samples = new byte[width * height];
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                var d = Math.hypot(x - width / 2.0, y - height / 2.0);
                samples[y * width + x] = (byte) Math.clamp((long) (255 - Math.max(0, d - width / 4.0) * 12), 0, 255);
            }
        }
        return new Plane(samples, width, height);
    }

    @Nested
    @DisplayName("filters")
    class Filters {
        @Test
        @DisplayName("predict the first row from the left and the first column from above")
        void edges() {
            var in = new byte[]{10, 20, 25, 13, 30, 50};
            var out = new byte[6];
            AlphaFilters.filter(AlphaFilters.HORIZONTAL, in, 3, 2, 3, out);
            assertArrayEquals(new byte[]{10, 10, 5, 3, 17, 20}, out);
            AlphaFilters.filter(AlphaFilters.VERTICAL, in, 3, 2, 3, out);
            assertArrayEquals(new byte[]{10, 10, 5, 3, 10, 25}, out);
            // row 1: x=1 predicts clip(13+20-10)=23, x=2 predicts clip(30+25-20)=35
            AlphaFilters.filter(AlphaFilters.GRADIENT, in, 3, 2, 3, out);
            assertArrayEquals(new byte[]{10, 10, 5, 3, 7, 15}, out);
        }

        @Test
        @DisplayName("clip the gradient predictor to the sample range")
        void gradientClip() {
            var in = new byte[]{(byte) 250, (byte) 250, (byte) 10, (byte) 255};
            var out = new byte[4];
            AlphaFilters.filter(AlphaFilters.GRADIENT, in, 2, 2, 2, out);
            // x=1 of row 1 predicts clip(10+250-250)=10
            assertEquals((byte) (255 - 10), out[3]);
        }

        @ParameterizedTest(name = "filter {0}")
        @ValueSource(ints = {0, 1, 2, 3})
        @DisplayName("round-trip random planes through filtering and row-wise unfiltering")
        void roundTrip(int filter) {
            for (var size : new int[][]{{1, 1}, {1, 7}, {7, 1}, {13, 9}, {64, 3}}) {
                var plane = randomPlane(size[0], size[1], size[0] * 31L + size[1], 256);
                var w = plane.width();
                var filtered = plane.samples().clone();
                if (filter != AlphaFilters.NONE) {
                    AlphaFilters.filter(filter, plane.samples(), w, plane.height(), w, filtered);
                }
                var out = new byte[filtered.length];
                for (var y = 0; y < plane.height(); y++) {
                    AlphaFilters.unfilter(filter, y == 0 ? null : out, (y - 1) * w, filtered, y * w, out, y * w, w);
                }
                assertArrayEquals(plane.samples(), out, w + "x" + plane.height());
            }
        }

        @Test
        @DisplayName("estimate no filter for flat planes and a spatial filter for ramps")
        void estimate() {
            assertEquals(AlphaFilters.NONE, AlphaFilters.estimateBestFilter(new byte[64 * 64], 64, 64, 64));
            var ramp = new byte[64 * 64];
            for (var i = 0; i < ramp.length; i++) {
                ramp[i] = (byte) ((i % 64) * 4);
            }
            assertNotEquals(AlphaFilters.NONE, AlphaFilters.estimateBestFilter(ramp, 64, 64, 64));
        }
    }

    @Nested
    @DisplayName("codec")
    class Codec {
        @ParameterizedTest(name = "filter mode {0} effort {1}")
        @CsvSource({"0,0", "0,4", "1,0", "1,3", "1,4", "1,6", "2,2", "2,6"})
        @DisplayName("round-trips alpha exactly at quality 100")
        void lossless(int filterMode, int effort) {
            for (var plane : List.of(discPlane(61, 47), randomPlane(33, 21, 9, 7), randomPlane(40, 40, 3, 256), randomPlane(1, 1, 1, 256))) {
                var payload = AlphaEncoder.encode(plane.samples(), plane.width(), plane.height(), 100, filterMode, effort);
                assertEquals(0, (payload[0] >> 4) & 3, "no pre-processing flag at quality 100");
                assertEquals(0, (payload[0] >> 6) & 3, "reserved bits");
                var decoded = AlphaDecoder.decode(payload, 0, payload.length, plane.width(), plane.height(), MediaLimits.DEFAULT);
                assertArrayEquals(plane.samples(), decoded);
            }
        }

        @ParameterizedTest(name = "quality {0}")
        @ValueSource(ints = {0, 20, 50, 70, 90, 99})
        @DisplayName("quantize to at most the documented number of levels below quality 100, keeping the extremes")
        void quantized(int quality) {
            var plane = discPlane(64, 64);
            var payload = AlphaEncoder.encode(plane.samples(), 64, 64, quality, AlphaEncoder.FILTER_FAST, 4);
            assertEquals(1, (payload[0] >> 4) & 3, "pre-processing flag");
            var decoded = AlphaDecoder.decode(payload, 0, payload.length, 64, 64, MediaLimits.DEFAULT);
            var levels = new HashSet<Integer>();
            for (var sample : decoded) {
                levels.add(sample & 0xFF);
            }
            var expected = quality <= 70 ? 2 + quality / 5 : 16 + (quality - 70) * 8;
            assertTrue(levels.size() <= expected, levels.size() + " levels");
            assertTrue(levels.contains(0) && levels.contains(255), "extremes are preserved");
        }

        @Test
        @DisplayName("decode raw payloads")
        void raw() {
            var plane = randomPlane(8, 8, 1, 256);
            var payload = new byte[65];
            System.arraycopy(plane.samples(), 0, payload, 1, 64);
            assertArrayEquals(plane.samples(), AlphaDecoder.decode(payload, 0, payload.length, 8, 8, MediaLimits.DEFAULT));
        }

        @Test
        @DisplayName("reject reserved bits, unknown methods, unknown pre-processing and short payloads")
        void invalid() {
            var limits = MediaLimits.DEFAULT;
            assertThrows(MediaException.Malformed.class, () -> AlphaDecoder.decode(new byte[]{0}, 0, 1, 1, 1, limits));
            assertThrows(MediaException.Malformed.class, () -> AlphaDecoder.decode(new byte[]{2, 0}, 0, 2, 1, 1, limits));
            assertThrows(MediaException.Malformed.class, () -> AlphaDecoder.decode(new byte[]{(byte) 0x40, 0}, 0, 2, 1, 1, limits));
            assertThrows(MediaException.Malformed.class, () -> AlphaDecoder.decode(new byte[]{0x20, 0}, 0, 2, 1, 1, limits));
            assertThrows(MediaException.Malformed.class, () -> AlphaDecoder.decode(new byte[]{0, 1, 2}, 0, 3, 2, 2, limits));
            assertThrows(MediaException.Malformed.class, () -> AlphaDecoder.decode(new byte[]{1, 0x2F, 0, 0}, 0, 4, 64, 64, limits));
        }
    }

    static Stream<Arguments> cwebpSettings() {
        var list = new ArrayList<Arguments>();
        for (var filter : List.of("none", "fast", "best")) {
            for (var method : List.of(0, 3, 4, 6)) {
                for (var quality : List.of(100, 60)) {
                    list.add(Arguments.of(filter, method, quality));
                }
            }
        }
        return list.stream();
    }

    static Path stickerPng() {
        return Fixtures.ffmpeg("png", List.of("-f", "lavfi", "-i", "testsrc2=size=" + STICKER_WIDTH + "x" + STICKER_HEIGHT + ":rate=1"),
                List.of("-frames:v", "1", "-vf", "format=rgba,geq=r='r(X,Y)':g='g(X,Y)':b='b(X,Y)'"
                        + ":a='clip(315-hypot(X-48,Y-40)*4,0,255)'"));
    }

    static byte[] chunk(byte[] file, String tag) {
        for (var chunk : WebpContainer.parse(file)) {
            if (chunk.fourCc().equals(tag)) {
                var out = new byte[chunk.size()];
                System.arraycopy(file, chunk.payloadOffset(), out, 0, chunk.size());
                return out;
            }
        }
        return null;
    }

    @ParameterizedTest(name = "-alpha_filter {0} -m {1} -alpha_q {2}")
    @MethodSource("cwebpSettings")
    @DisplayName("produces the same ALPH chunk as cwebp")
    void matchesCwebp(String filter, int method, int quality) {
        Oracle.assume(Oracle.Tool.CWEBP);
        var png = stickerPng();
        var out = WebpTestSupport.scratchPath("alph-" + filter + "-" + method + "-" + quality + ".webp");
        Oracle.run(Oracle.Tool.CWEBP, Duration.ofMinutes(1), List.of("-quiet", "-q", "80", "-m", Integer.toString(method),
                "-alpha_filter", filter, "-alpha_q", Integer.toString(quality), png.toString(), "-o", out.toString()));
        var expected = chunk(WebpTestSupport.readFile(out), "ALPH");
        assertNotNull(expected, "cwebp wrote an ALPH chunk");
        var rgba = Oracle.decodeVideo(png, "rgba", 1);
        var plane = new byte[STICKER_WIDTH * STICKER_HEIGHT];
        for (var i = 0; i < plane.length; i++) {
            plane[i] = rgba[4 * i + 3];
        }
        var filterMode = switch (filter) {
            case "none" -> AlphaEncoder.FILTER_NONE;
            case "fast" -> AlphaEncoder.FILTER_FAST;
            default -> AlphaEncoder.FILTER_BEST;
        };
        assertArrayEquals(expected, AlphaEncoder.encode(plane, STICKER_WIDTH, STICKER_HEIGHT, quality, filterMode, method));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"alpha_color_cache.webp", "alpha_filter_0_method_0.webp", "alpha_filter_0_method_1.webp",
            "alpha_filter_1.webp", "alpha_filter_1_method_0.webp", "alpha_filter_1_method_1.webp",
            "alpha_filter_2.webp", "alpha_filter_2_method_0.webp", "alpha_filter_2_method_1.webp",
            "alpha_filter_3.webp", "alpha_filter_3_method_0.webp", "alpha_filter_3_method_1.webp",
            "alpha_no_compression.webp", "lossy_alpha1.webp", "lossy_alpha2.webp", "lossy_alpha3.webp",
            "lossy_alpha4.webp", "big_endian_bug_393.webp"})
    @DisplayName("decodes the corpus ALPH chunks to dwebp's alpha planes")
    void corpus(String name) {
        assertAlphaMatchesDwebp(LibwebpTestData.fetch(name));
    }

    @ParameterizedTest(name = "-alpha_filter {0} -m {1} -alpha_q {2}")
    @MethodSource("cwebpSettings")
    @DisplayName("decodes cwebp's raw and lossless ALPH chunks to dwebp's alpha planes")
    void cwebpFiles(String filter, int method, int quality) {
        Oracle.assume(Oracle.Tool.CWEBP);
        var png = stickerPng();
        var out = WebpTestSupport.scratchPath("alphd-" + filter + "-" + method + "-" + quality + ".webp");
        Oracle.run(Oracle.Tool.CWEBP, Duration.ofMinutes(1), List.of("-quiet", "-q", "70", "-m", Integer.toString(method),
                "-alpha_filter", filter, "-alpha_method", quality == 100 ? "1" : "0", "-alpha_q", Integer.toString(quality),
                png.toString(), "-o", out.toString()));
        assertAlphaMatchesDwebp(out);
    }

    static void assertAlphaMatchesDwebp(Path path) {
        Oracle.assume(Oracle.Tool.DWEBP);
        var file = WebpTestSupport.readFile(path);
        var alph = chunk(file, "ALPH");
        assertNotNull(alph, "the file has an ALPH chunk");
        var vp8 = chunk(file, "VP8 ");
        assertNotNull(vp8, "the file has a VP8 chunk");
        var width = ((vp8[6] & 0xFF) | (vp8[7] & 0xFF) << 8) & 0x3FFF;
        var height = ((vp8[8] & 0xFF) | (vp8[9] & 0xFF) << 8) & 0x3FFF;
        var ours = AlphaDecoder.decode(alph, 0, alph.length, width, height, MediaLimits.DEFAULT);
        var pgm = WebpTestSupport.scratchPath(path.getFileName() + ".alpha.pgm");
        Oracle.run(Oracle.Tool.DWEBP, Duration.ofMinutes(1), List.of(path.toString(), "-alpha", "-o", pgm.toString()));
        var bytes = WebpTestSupport.readFile(pgm);
        var expected = new byte[width * height];
        System.arraycopy(bytes, bytes.length - expected.length, expected, 0, expected.length);
        assertArrayEquals(expected, ours);
    }
}
