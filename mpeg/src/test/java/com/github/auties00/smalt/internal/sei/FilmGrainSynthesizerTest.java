package com.github.auties00.smalt.internal.sei;

import com.github.auties00.smalt.video.h264.internal.H264BitReader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the SMPTE RDD 5 film grain renderer: its tables against the values printed in RDD 5, its output against a
 * straightforward model written from the RDD 5 text (full matrix products, block by block, one thread) over
 * random pictures and models, and one output against a digest of the Python model in
 * {@code tools/testdata/film-grain}, which matches ffmpeg's renderer.
 */
@DisplayName("FilmGrainSynthesizer")
class FilmGrainSynthesizerTest {
    static FilmGrainParameters parse(byte[] payload, boolean hevc) {
        var padded = Arrays.copyOf(payload, payload.length + H264BitReader.PADDING);
        var reader = new H264BitReader();
        reader.init(padded, 0, payload.length);
        return FilmGrainParameters.parse(reader, hevc);
    }

    static FilmGrainParameters model(List<List<SeiWriter.Interval>> components, int log2ScaleFactor) {
        return parse(SeiWriter.filmGrain(false, log2ScaleFactor, 0, components), false);
    }

    static Stream<Arguments> reservedIds() {
        return Stream.of(Arguments.of(2, 0), Arguments.of(3, 0), Arguments.of(0, 2), Arguments.of(1, 3));
    }

    @Nested
    @DisplayName("tables")
    class Tables {
        @Test
        @DisplayName("the Gaussian and seed tables start and end with the RDD 5 values")
        void luts() {
            assertEquals(2048, FilmGrainTables.GAUSSIAN.length);
            assertArrayEquals(new byte[]{-11, 12, 103, -11, 42, -35}, Arrays.copyOf(FilmGrainTables.GAUSSIAN, 6));
            assertArrayEquals(new byte[]{-84, 127, 68, 46, 17, 47}, Arrays.copyOfRange(FilmGrainTables.GAUSSIAN, 2042, 2048));
            assertEquals(256, FilmGrainTables.SEEDS.length);
            assertEquals(747538460, FilmGrainTables.SEEDS[0]);
            assertEquals(1088979410, FilmGrainTables.SEEDS[1]);
            assertEquals(1359020032, FilmGrainTables.SEEDS[254]);
            assertEquals(1358954497, FilmGrainTables.SEEDS[255]);
            var checksum = 0L;
            for (var value : FilmGrainTables.GAUSSIAN) {
                checksum = checksum * 31 + value;
            }
            for (var value : FilmGrainTables.SEEDS) {
                checksum = checksum * 31 + value;
            }
            // the checksum of the tables as parsed from the RDD 5 text, which also equal the VVC reference copies
            assertEquals(-250573488984100871L, checksum);
        }

        @Test
        @DisplayName("the transformation matrix has the RDD 5 rows")
        void matrix() {
            int[] row1 = {45, 45, 45, 45, 44, 44, 43, 42, 41, 40, 39, 38, 37, 36, 34, 33, 31, 30, 28, 26, 24, 22, 20, 18,
                    16, 14, 12, 10, 8, 6, 3, 1};
            int[] row63 = {1, -3, 6, -8, 10, -12, 14, -16, 18, -20, 22, -24, 26, -28, 30, -31, 33, -34, 36, -37, 38, -39,
                    40, -41, 42, -43, 44, -44, 45, -45, 45, -45};
            int[] row40 = {25, -44, 9, 38, -38, -9, 44, -25, -25, 44, -9, -38, 38, 9, -44, 25};
            for (var n = 0; n < 64; n++) {
                assertEquals(32, FilmGrainSynthesizer.R64[n]);
                var mirrored = 63 - n;
                assertEquals(n < 32 ? row1[n] : -row1[mirrored], FilmGrainSynthesizer.R64[64 + n], "row 1 column " + n);
                assertEquals(n < 32 ? row63[n] : -row63[mirrored], FilmGrainSynthesizer.R64[63 * 64 + n],
                        "row 63 column " + n);
                assertEquals(row40[n % 16], FilmGrainSynthesizer.R64[40 * 64 + n], "row 40 column " + n);
            }
        }

        @Test
        @DisplayName("the generator shifts in the complement of bit 30 exclusive-or bit 2")
        void generator() {
            assertEquals(1, FilmGrainSynthesizer.next(0));
            assertEquals(0b1010, FilmGrainSynthesizer.next(0b101));
            assertEquals(0x80000000, FilmGrainSynthesizer.next(0x40000000));
            assertEquals(0x80000009, FilmGrainSynthesizer.next(0x40000004));
            assertEquals(0xFFFFFFFF, FilmGrainSynthesizer.next(0xFFFFFFFF));
        }

        @Test
        @DisplayName("every pattern equals the RDD 5 database built with full matrix products")
        void patterns() {
            for (var h = 0; h < 13; h++) {
                for (var v = 0; v < 13; v++) {
                    var expected = Reference.pattern(h, v);
                    var actual = FilmGrainSynthesizer.pattern(h, v);
                    for (var k = 0; k < 64; k++) {
                        for (var l = 0; l < 64; l++) {
                            assertEquals(expected[k][l], actual[l * 64 + k], "pattern " + h + "," + v + " at " + k + "," + l);
                        }
                    }
                }
            }
        }
    }

    record Case(String name, int chromaFormat, int bitDepth, int width, int height, int threads, long seed) {
        @Override
        public String toString() {
            return name;
        }
    }

    static Stream<Case> cases() {
        return Stream.of(
                new Case("8-bit 4:2:0 64x48", 1, 8, 64, 48, 1, 1),
                new Case("8-bit 4:2:0 176x144 four threads", 1, 8, 176, 144, 4, 2),
                new Case("8-bit 4:2:0 partial blocks 84x68", 1, 8, 84, 68, 1, 3),
                new Case("8-bit 4:2:0 partial blocks 100x36 three threads", 1, 8, 100, 36, 3, 4),
                new Case("8-bit 4:2:2 48x40", 2, 8, 48, 40, 1, 5),
                new Case("8-bit 4:4:4 40x40", 3, 8, 40, 40, 2, 6),
                new Case("8-bit 4:0:0 72x24", 0, 8, 72, 24, 1, 7),
                new Case("10-bit 4:2:0 64x64", 1, 10, 64, 64, 1, 8),
                new Case("12-bit 4:2:2 36x52 two threads", 2, 12, 36, 52, 2, 9),
                new Case("16-bit 4:4:4 32x16", 3, 16, 32, 16, 1, 10),
                new Case("8 bits through the 16-bit path", 1, 8, 48, 48, 1, 11));
    }

    static List<List<SeiWriter.Interval>> randomModel(Random random, int bitDepth) {
        var components = new ArrayList<List<SeiWriter.Interval>>();
        for (var c = 0; c < 3; c++) {
            var intervals = new ArrayList<SeiWriter.Interval>();
            if (random.nextInt(5) != 0) {
                var count = 1 + random.nextInt(3);
                var values = 1 + random.nextInt(3);
                var lower = 0;
                for (var i = 0; i < count && lower < 256; i++) {
                    var upper = Math.min(255, lower + random.nextInt(160));
                    var model = new int[values];
                    model[0] = random.nextInt(1 << bitDepth);
                    for (var j = 1; j < values; j++) {
                        model[j] = random.nextInt(16);
                    }
                    intervals.add(new SeiWriter.Interval(lower, upper, model));
                    // intervals may overlap or leave gaps
                    lower = Math.max(0, upper - 20 + random.nextInt(40));
                }
            }
            components.add(intervals);
        }
        return components;
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    @DisplayName("matches the RDD 5 model on random pictures and models")
    void reference(Case c) {
        var random = new Random(c.seed());
        var synthesizer = new FilmGrainSynthesizer();
        for (var round = 0; round < 6; round++) {
            var params = model(randomModel(random, c.bitDepth()), random.nextInt(8));
            var picOffset = random.nextInt(4096) - 2048;
            var components = c.chromaFormat() == 0 ? 1 : 3;
            for (var component = 0; component < components; component++) {
                var width = component == 0 || c.chromaFormat() == 3 ? c.width() : c.width() / 2;
                var height = component == 0 || c.chromaFormat() != 1 ? c.height() : c.height() / 2;
                var max = (1 << c.bitDepth()) - 1;
                var src = new int[width * height];
                for (var i = 0; i < src.length; i++) {
                    // smooth content with noise, so blocks fall into several intervals and values clip
                    var base = (i % width) * max / Math.max(1, width - 1);
                    src[i] = Math.clamp(base + random.nextInt(max / 8 + 1) - max / 16, 0, max);
                }
                var expected = Reference.apply(params, picOffset, component, c.chromaFormat(), c.bitDepth(), src, width,
                        height);
                int[] actual;
                if (c.bitDepth() == 8 && !c.name().startsWith("8 bits through")) {
                    var in = new byte[width * height + 7];
                    var out = new byte[width * height + 11];
                    for (var i = 0; i < src.length; i++) {
                        in[i + 7] = (byte) src[i];
                    }
                    synthesizer.apply(params, picOffset, component, c.chromaFormat(), in, 7, width, out, 11, width,
                            width, height, c.threads());
                    actual = new int[src.length];
                    for (var i = 0; i < src.length; i++) {
                        actual[i] = out[i + 11] & 0xFF;
                    }
                } else {
                    var stride = width + 5;
                    var in = new short[stride * height];
                    var out = new short[stride * height];
                    for (var y = 0; y < height; y++) {
                        for (var x = 0; x < width; x++) {
                            in[y * stride + x] = (short) src[y * width + x];
                        }
                    }
                    synthesizer.apply(params, picOffset, component, c.chromaFormat(), c.bitDepth(), in, 0, stride, out,
                            0, stride, width, height, c.threads());
                    actual = new int[src.length];
                    for (var y = 0; y < height; y++) {
                        for (var x = 0; x < width; x++) {
                            actual[y * width + x] = out[y * stride + x] & 0xFFFF;
                        }
                    }
                }
                assertArrayEquals(expected, actual, c + " round " + round + " component " + component);
            }
        }
    }

    @Test
    @DisplayName("an 8-bit 4:2:0 picture matches the digest of the Python RDD 5 model")
    void golden() throws Exception {
        // tools/testdata/film-grain/rdd5_model.py golden: the model matches ffmpeg's renderer bit for bit
        var params = model(List.of(
                List.of(new SeiWriter.Interval(0, 120, 60, 8, 6), new SeiWriter.Interval(121, 255, 100, 4, 10)),
                List.of(new SeiWriter.Interval(0, 127, 40, 6, 6), new SeiWriter.Interval(128, 255, 90, 12, 3)),
                List.of(new SeiWriter.Interval(0, 127, 80, 3, 12), new SeiWriter.Interval(128, 255, 30, 10, 10))), 3);
        var synthesizer = new FilmGrainSynthesizer();
        var digest = MessageDigest.getInstance("SHA-256");
        for (var component = 0; component < 3; component++) {
            var width = component == 0 ? 168 : 84;
            var height = component == 0 ? 136 : 68;
            var in = new byte[width * height];
            for (var y = 0; y < height; y++) {
                for (var x = 0; x < width; x++) {
                    in[y * width + x] = (byte) ((x * 7 + y * 13 + component * 50 + (x * y) % 23) & 0xFF);
                }
            }
            var out = new byte[in.length];
            synthesizer.apply(params, 37, component, 1, in, 0, width, out, 0, width, width, height, 1);
            digest.update(out);
        }
        assertEquals("fc3200ea1c2275f24bb99c5daaee50d112b635ff59c9bfe45c020ba0a54302cb",
                HexFormat.of().formatHex(digest.digest()));
    }

    @Nested
    @DisplayName("intervals")
    class Intervals {
        @Test
        @DisplayName("a block whose average no interval covers keeps its samples")
        void uncovered() {
            var params = model(List.of(List.of(new SeiWriter.Interval(200, 255, 255, 8, 8)), List.of(), List.of()), 2);
            var in = new byte[32 * 32];
            Arrays.fill(in, (byte) 100);
            var out = new byte[in.length];
            new FilmGrainSynthesizer().apply(params, 0, 0, 1, in, 0, 32, out, 0, 32, 32, 32, 1);
            assertArrayEquals(in, out);
        }

        @Test
        @DisplayName("a component without a model is copied")
        void unmodeled() {
            var params = model(List.of(List.of(new SeiWriter.Interval(0, 255, 255, 8, 8)), List.of(), List.of()), 2);
            var in = new byte[16 * 16];
            new Random(3).nextBytes(in);
            var out = new byte[in.length];
            new FilmGrainSynthesizer().apply(params, 0, 1, 1, in, 0, 16, out, 0, 16, 16, 16, 1);
            assertArrayEquals(in, out);
        }

        @Test
        @DisplayName("grain changes covered blocks and stays within the sample range")
        void covered() {
            var params = model(List.of(List.of(new SeiWriter.Interval(0, 255, 255, 14, 14)), List.of(), List.of()), 2);
            var in = new byte[64 * 64];
            Arrays.fill(in, 0, 32 * 64, (byte) 2);
            Arrays.fill(in, 32 * 64, in.length, (byte) 253);
            var out = new byte[in.length];
            new FilmGrainSynthesizer().apply(params, 5, 0, 1, in, 0, 64, out, 0, 64, 64, 64, 1);
            assertFalse(Arrays.equals(in, out));
            var low = false;
            var high = false;
            for (var value : out) {
                low |= value == 0;
                high |= value == (byte) 255;
            }
            assertTrue(low && high, "grain of intensity 255 clips at both ends");
        }
    }

    @Nested
    @DisplayName("messages")
    class Messages {
        @Test
        @DisplayName("left-out model values take their inferred defaults")
        void defaults() {
            var params = model(List.of(List.of(new SeiWriter.Interval(0, 255, 50)), List.of(
                    new SeiWriter.Interval(0, 255, 50, 4)), List.of()), 4);
            assertNotNull(params);
            assertArrayEquals(new int[]{50, 8, 8, 0, 0, 0}, params.values[0]);
            assertArrayEquals(new int[]{50, 4, 4, 0, 0, 0}, params.values[1]);
            assertNull(params.values[2]);
            assertFalse(params.persists());
            assertTrue(params.renderable(8, 8, false, true, 1, 1, 1));
        }

        @Test
        @DisplayName("the H.264 repetition period and the H.265 persistence flag decide persistence")
        void persistence() {
            var components = List.of(List.of(new SeiWriter.Interval(0, 255, 50)), List.<SeiWriter.Interval>of(),
                    List.<SeiWriter.Interval>of());
            assertTrue(parse(SeiWriter.filmGrain(false, 3, 7, components), false).persists());
            assertFalse(parse(SeiWriter.filmGrain(false, 3, 0, components), false).persists());
            assertTrue(parse(SeiWriter.filmGrain(true, 3, 1, components), true).persists());
            assertFalse(parse(SeiWriter.filmGrain(true, 3, 0, components), true).persists());
            var cancel = parse(SeiWriter.filmGrainCancel(), false);
            assertTrue(cancel.cancel());
            assertFalse(cancel.renderable(8, 8, false, true, 1, 1, 1));
        }

        @Test
        @DisplayName("a truncated message is ignored")
        void truncated() {
            var payload = SeiWriter.filmGrain(false, 3, 0, List.of(List.of(new SeiWriter.Interval(0, 255, 50, 8, 8)),
                    List.<SeiWriter.Interval>of(), List.<SeiWriter.Interval>of()));
            for (var length = 0; length < payload.length - 1; length++) {
                assertNull(parse(Arrays.copyOf(payload, length), false), "length " + length);
            }
        }

        @ParameterizedTest(name = "model {0}, blending {1}")
        @MethodSource("com.github.auties00.smalt.internal.sei.FilmGrainSynthesizerTest#reservedIds")
        @DisplayName("a message with a reserved model or blending mode id is ignored, so it ends no persistence")
        void reserved(int modelId, int blendingMode) {
            var bits = new SeiWriter.Bits().flag(false).u(2, modelId).flag(false).u(2, blendingMode).u(4, 3)
                    .flag(true).flag(false).flag(false).u(8, 0).u(3, 0).u(8, 0).u(8, 255).se(10).ue(1);
            assertNull(parse(bits.bytes(), false));
            assertNull(parse(new SeiWriter.Bits().flag(false).u(2, modelId).flag(false).u(2, blendingMode).u(4, 3)
                    .flag(true).flag(false).flag(false).u(8, 0).u(3, 0).u(8, 0).u(8, 255).se(10).flag(true).bytes(),
                    true));
        }

        @Test
        @DisplayName("only the frequency filtering model with additive blending in the stream's domain renders")
        void renderable() {
            var bits = new SeiWriter.Bits().flag(false).u(2, 1).flag(false).u(2, 0).u(4, 3).flag(true).flag(false)
                    .flag(false).u(8, 0).u(3, 0).u(8, 0).u(8, 255).se(10).ue(0);
            assertFalse(parse(bits.bytes(), false).renderable(8, 8, false, true, 1, 1, 1), "auto-regression");
            bits = new SeiWriter.Bits().flag(false).u(2, 0).flag(false).u(2, 1).u(4, 3).flag(true).flag(false)
                    .flag(false).u(8, 0).u(3, 0).u(8, 0).u(8, 255).se(10).ue(0);
            assertFalse(parse(bits.bytes(), false).renderable(8, 8, false, true, 1, 1, 1), "multiplicative");
            bits = new SeiWriter.Bits().flag(false).u(2, 0).flag(true).u(3, 2).u(3, 2).flag(false).u(8, 9).u(8, 16)
                    .u(8, 9).u(2, 0).u(4, 3).flag(true).flag(false).flag(false).u(8, 0).u(3, 0).u(8, 0).u(8, 255)
                    .se(10).ue(0);
            var separate = parse(bits.bytes(), false);
            assertTrue(separate.renderable(10, 10, false, true, 9, 16, 9));
            assertTrue(separate.renderable(10, 10, false, false, 2, 2, 2), "the sequence describes no color");
            assertFalse(separate.renderable(8, 8, false, true, 9, 16, 9), "other bit depth");
            assertFalse(separate.renderable(10, 10, true, true, 9, 16, 9), "other range");
            assertFalse(separate.renderable(10, 10, false, true, 1, 1, 1), "other color description");
        }
    }

    /**
     * The RDD 5 process written as the text states it, with the extensions the renderer documents.
     */
    static final class Reference {
        private static final int[] DEBLOCK = {64, 71, 77, 84, 90, 96, 103, 109, 116, 122, 128, 128, 128};

        static int[][] matrix() {
            var r = new int[64][64];
            for (var k = 0; k < 64; k++) {
                for (var n = 0; n < 64; n++) {
                    r[k][n] = k == 0 ? 32 : (int) Math.round(32 * Math.sqrt(2) * Math.cos(Math.PI * (2 * n + 1) * k / 128));
                }
            }
            return r;
        }

        static int next(int x) {
            var bit = 1 ^ ((x >>> 30) & 1) ^ ((x >>> 2) & 1);
            return (x << 1) | bit;
        }

        static int[][] pattern(int h, int v) {
            var r = matrix();
            var b = new int[64][64];
            var e = FilmGrainTables.SEEDS[h + v * 13];
            var fh = ((h + 3) << 2) - 1;
            var fv = ((v + 3) << 2) - 1;
            for (var l = 0; l <= fv; l++) {
                for (var k = 0; k <= fh; k += 4) {
                    for (var j = 0; j < 4; j++) {
                        b[k + j][l] = FilmGrainTables.GAUSSIAN[(int) (((e & 0xFFFFFFFFL) + j) % 2048)];
                    }
                    e = next(e);
                }
            }
            b[0][0] = 0;
            var t = new int[64][64];
            for (var i = 0; i < 64; i++) {
                for (var j = 0; j < 64; j++) {
                    var sum = 0;
                    for (var k = 0; k < 64; k++) {
                        sum += r[k][i] * b[k][j];
                    }
                    t[i][j] = (sum + 128) >> 8;
                }
            }
            var db = new int[64][64];
            for (var i = 0; i < 64; i++) {
                for (var j = 0; j < 64; j++) {
                    var sum = 0;
                    for (var k = 0; k < 64; k++) {
                        sum += t[i][k] * r[k][j];
                    }
                    db[i][j] = Math.clamp((sum + 128) >> 8, -127, 127);
                }
            }
            for (var l = 0; l < 64; l += 8) {
                for (var k = 0; k < 64; k++) {
                    db[k][l] = (db[k][l] * DEBLOCK[v]) >> 7;
                    db[k][l + 7] = (db[k][l + 7] * DEBLOCK[v]) >> 7;
                }
            }
            return db;
        }

        static int[] apply(FilmGrainParameters params, int picOffset, int c, int format, int depth, int[] src,
                           int width, int height) {
            var out = src.clone();
            if (params.values[c] == null) {
                return out;
            }
            var intervals = params.bounds[c].length / 2;
            var grain = new int[height][width];
            var e = FilmGrainTables.SEEDS[Math.floorMod(picOffset + new int[]{0, 85, 170}[c], 256)];
            for (var by = 0; by < height; by += 16) {
                for (var bx = 0; bx < width; bx += 16) {
                    for (var block = 0; block < 4; block++) {
                        var m = bx + (block & 1) * 8;
                        var n = by + (block >> 1) * 8;
                        if (m >= width || n >= height) {
                            continue;
                        }
                        var sum = 0;
                        for (var k = 0; k < 8; k++) {
                            for (var l = 0; l < 8; l++) {
                                if (m + k < width && n + l < height) {
                                    sum += src[(n + l) * width + m + k];
                                }
                            }
                        }
                        var avg = Math.min(255, sum >> (6 + depth - 8));
                        var s = -1;
                        for (var i = 0; i < intervals && s < 0; i++) {
                            if (avg >= params.bounds[c][2 * i] && avg <= params.bounds[c][2 * i + 1]) {
                                s = i;
                            }
                        }
                        if (s < 0) {
                            continue;
                        }
                        var v0 = Math.clamp(params.values[c][6 * s], 0, (1 << depth) - 1);
                        var v1 = params.values[c][6 * s + 1];
                        var v2 = params.values[c][6 * s + 2];
                        if (c > 0 && format == 1) {
                            v0 >>= 1;
                            v1 <<= 1;
                            v2 <<= 1;
                        } else if (c > 0 && format == 2) {
                            v0 = v0 * 181 >> 8;
                            v1 <<= 1;
                        }
                        v1 = Math.clamp(v1, 2, 14);
                        v2 = Math.clamp(v2, 2, 14);
                        var kOffset = (int) (((e & 0xFFFFFFFFL) >>> 16) % 52) & 0xFFFC;
                        kOffset += m & 8;
                        var lOffset = (int) ((e & 0xFFFF) % 56) & 0xFFF8;
                        lOffset += n & 8;
                        var scale = (e & 1) == 0 ? v0 : -v0;
                        var db = pattern(v1 - 2, v2 - 2);
                        for (var k = 0; k < 8; k++) {
                            for (var l = 0; l < 8; l++) {
                                if (m + k < width && n + l < height) {
                                    grain[n + l][m + k] = (scale * db[k + kOffset][l + lOffset])
                                            >> (params.log2ScaleFactor + 6);
                                }
                            }
                        }
                    }
                    e = next(e);
                }
            }
            for (var y = 0; y < height; y++) {
                for (var m = 8; m + 1 < width; m += 8) {
                    var l1 = grain[y][m - 2];
                    var l0 = grain[y][m - 1];
                    var r0 = grain[y][m];
                    var r1 = grain[y][m + 1];
                    grain[y][m] = (l0 + (r0 << 1) + r1) >> 2;
                    grain[y][m - 1] = (l1 + (l0 << 1) + r0) >> 2;
                }
            }
            var max = (1 << depth) - 1;
            for (var y = 0; y < height; y++) {
                for (var x = 0; x < width; x++) {
                    out[y * width + x] = Math.clamp(src[y * width + x] + grain[y][x], 0, max);
                }
            }
            return out;
        }
    }
}
