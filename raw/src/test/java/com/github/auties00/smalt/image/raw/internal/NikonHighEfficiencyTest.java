package com.github.auties00.smalt.image.raw.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.raw.RawDecoder;
import com.github.auties00.smalt.image.raw.RawSamples;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.fuzz.FuzzSeeds;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the Nikon HE and HE* decoder on codestreams and NEF files {@link NikonHighEfficiencyWriter} writes, whose
 * coefficients are known: the entropy decoding must return them exactly, the reconstruction must invert a forward
 * wavelet and lifting written here from the JPEG XS 5/3 analysis, damaged headers and data must fail with a
 * {@link MediaException}; and on the CC0 samples of the Z 50II, whose HE, HE* and lossless files of each image area
 * show the same scene, the HE and HE* photosites must agree with the lossless ones.
 */
class NikonHighEfficiencyTest {
    /** The fuzz test class whose seeds the written files feed. */
    private static final String FUZZ_TARGET = "com.github.auties00.smalt.image.raw.fuzz.RawFuzzTest";

    static int[][] randomPlanes(NikonHighEfficiencyWriter writer, long seed, int maxBits, double density) {
        var random = new SplittableRandom(seed);
        var count = writer.width / 2 * (writer.height / 2);
        var planes = new int[4][count];
        for (var plane : planes) {
            for (var i = 0; i < count; i++) {
                if (random.nextDouble() < density) {
                    var magnitude = random.nextInt(1 << random.nextInt(1, maxBits + 1));
                    plane[i] = random.nextBoolean() ? magnitude : -magnitude;
                }
            }
        }
        return planes;
    }

    static NikonHighEfficiency.Header header(byte[] codestream) {
        return NikonHighEfficiency.header(codestream);
    }

    @Nested
    @DisplayName("entropy decoding")
    class Entropy {
        @ParameterizedTest(name = "{0}x{1} slices of {2}, raw {3}, density {4}")
        @CsvSource({
                "64, 16, 2, false, 0.5", "64, 16, 1, false, 1.0", "128, 24, 4, false, 0.1", "66, 12, 2, false, 0.7",
                "200, 8, 1, false, 0.3", "64, 16, 2, true, 0.5", "130, 20, 3, true, 1.0"})
        @DisplayName("returns the coefficients the writer coded")
        void roundTrip(int width, int height, int sliceRows, boolean raw, double density) {
            var writer = new NikonHighEfficiencyWriter();
            writer.width = width;
            writer.height = height;
            writer.sliceRows = sliceRows;
            writer.raw = raw;
            var planes = randomPlanes(writer, width * 31L + height, raw ? 15 : 18, density);
            var codestream = writer.codestream(planes);
            var decoded = NikonHighEfficiency.coefficients(codestream, header(codestream), MediaLimits.DEFAULT);
            for (var c = 0; c < 4; c++) {
                assertArrayEquals(planes[c], decoded[c], "component " + c);
            }
        }

        @ParameterizedTest(name = "mode {0}, quantization {1}, refinement {2}, raw {3}, padding {4}")
        @CsvSource({
                "3, 0, 0, false, true", "0, 0, 0, false, false", "1, 0, 0, false, true", "2, 0, 0, false, true",
                "3, 3, 0, false, false", "3, 4, 9, false, true", "0, 2, 5, false, true", "1, 5, 25, false, false",
                "2, 1, 12, false, true", "3, 6, 3, true, true", "3, 20, 0, false, false", "1, 0, 0, true, true"})
        @DisplayName("returns the coefficients the writer coded, within their quantization interval")
        void quantized(int mode, int quantization, int refinement, boolean raw, boolean padding) {
            var writer = new NikonHighEfficiencyWriter();
            writer.width = 72;
            writer.height = 20;
            writer.mode = mode;
            writer.quantization = quantization;
            writer.refinement = refinement;
            writer.raw = raw;
            writer.padding = padding;
            var planes = randomPlanes(writer, mode * 101L + quantization * 7L + refinement, raw ? 15 : 18, 0.4);
            // a run of zeros wide enough to fill groups of eight code groups, which significance flags can skip
            Arrays.fill(planes[1], 0, 40, 0);
            var codestream = writer.codestream(planes);
            var decoded = NikonHighEfficiency.coefficients(codestream, header(codestream), MediaLimits.DEFAULT);
            var bands = bandWeights(writer);
            var truncated = 0;
            for (var c = 0; c < 4; c++) {
                for (var i = 0; i < planes[c].length; i++) {
                    var gtli = writer.truncation(bands[c][i]);
                    var value = planes[c][i];
                    var magnitude = Math.abs(value) >> gtli;
                    var actual = decoded[c][i];
                    if (magnitude == 0) {
                        assertEquals(0, actual, "component " + c + " coefficient " + i + " of " + value);
                        truncated += value != 0 ? 1 : 0;
                        continue;
                    }
                    assertEquals(Integer.signum(value), Integer.signum(actual), "the sign of " + value);
                    var reconstructed = Math.abs(actual);
                    assertTrue(reconstructed >= magnitude << gtli && reconstructed < (magnitude + 1) << gtli,
                            "component " + c + " coefficient " + i + ": " + value + " reconstructed as " + actual
                                    + " with truncation " + gtli);
                    if (gtli == 0) {
                        assertEquals(value, actual);
                    }
                }
            }
            assertEquals(quantization > 0, truncated > 0, "coefficients truncated to zero");
        }

        // the weights table index of the band each coefficient of each component plane belongs to
        private static int[][] bandWeights(NikonHighEfficiencyWriter writer) {
            var cw = writer.width / 2;
            var ch = writer.height / 2;
            var lowHeight = (ch + 1) / 2;
            var half = (cw + 1) / 2;
            var result = new int[4][cw * ch];
            var weight = 0;
            var first = new int[4];
            for (var c = 0; c < 4; c++) {
                first[c] = weight;
                weight += writer.levels[c] == 0 ? 1 : writer.levels[c] + 1;
            }
            for (var c = 0; c < 4; c++) {
                var levels = writer.levels[c];
                for (var y = 0; y < ch; y++) {
                    for (var x = 0; x < cw; x++) {
                        int w;
                        if (levels == 0) {
                            w = first[c];
                        } else if (y >= lowHeight) {
                            // the two high vertical bands follow every band of the first lines, component by
                            // component, in the second-line packets
                            var second = weight;
                            for (var k = 0; k < c; k++) {
                                second += writer.levels[k] == 0 ? 0 : 2;
                            }
                            w = second + (x < half ? 0 : 1);
                        } else if (x >= half) {
                            w = first[c] + levels;
                        } else {
                            var depth = levels;
                            while (depth > 1 && x >= ceil(cw, depth)) {
                                depth--;
                            }
                            w = x < ceil(cw, levels) ? first[c] : first[c] + levels - depth;
                        }
                        result[c][y * cw + x] = w;
                    }
                }
            }
            return result;
        }

        private static int ceil(int value, int shift) {
            return (value + (1 << shift) - 1) >> shift;
        }

        @Test
        @DisplayName("decodes zero coefficients flagged insignificant and coded one by one alike")
        void significance() {
            var writer = new NikonHighEfficiencyWriter();
            writer.width = 96;
            writer.height = 20;
            var planes = randomPlanes(writer, 7, 6, 0.02);
            var flagged = writer.codestream(planes);
            writer.significance = false;
            var coded = writer.codestream(planes);
            assertTrue(flagged.length < coded.length, "the flags must shorten the codestream");
            assertArrayEquals(NikonHighEfficiency.coefficients(coded, header(coded), MediaLimits.DEFAULT),
                    NikonHighEfficiency.coefficients(flagged, header(flagged), MediaLimits.DEFAULT));
        }

        @Test
        @DisplayName("a packet whose significance flags and sub-packets run past its precinct is malformed")
        void packetPastPrecinct() {
            var writer = new NikonHighEfficiencyWriter();
            writer.height = 8;
            var codestream = writer.codestream(randomPlanes(writer, 13, 9, 0.5));
            NikonHighEfficiency.coefficients(codestream, header(codestream), MediaLimits.DEFAULT);
            // the precinct header: 24-bit length, quantization, refinement, two bits for each of the 25 bands
            var headerBytes = 5 + (2 * 25 + 7) / 8;
            var first = header(codestream).headerLength() + 6;
            var second = first + headerBytes + u24(codestream, first);
            var length = u24(codestream, second);
            assertEquals(codestream.length - 2, second + headerBytes + length, "the second precinct ends the slices");
            // one byte less: the last packet, whose lengths leave out its byte of significance flags, now overruns
            // the precinct by one byte while its declared lengths still fit
            var cut = new byte[codestream.length - 1];
            System.arraycopy(codestream, 0, cut, 0, codestream.length - 3);
            cut[cut.length - 2] = (byte) 0xFF;
            cut[cut.length - 1] = 0x11;
            cut[second] = (byte) (length - 1 >> 16);
            cut[second + 1] = (byte) (length - 1 >> 8);
            cut[second + 2] = (byte) (length - 1);
            var lcod = 0x26 + 4;
            var total = ByteBuffer.wrap(cut).getInt(lcod);
            ByteBuffer.wrap(cut).putInt(lcod, total - 1);
            assertThrows(MediaException.Malformed.class,
                    () -> NikonHighEfficiency.coefficients(cut, header(cut), MediaLimits.DEFAULT));
        }

        private static int u24(byte[] data, int at) {
            return (data[at] & 0xFF) << 16 | (data[at + 1] & 0xFF) << 8 | data[at + 2] & 0xFF;
        }

        @Test
        @DisplayName("an undecomposed component in another position keeps its packets and weights in order")
        void plainComponent() {
            var writer = new NikonHighEfficiencyWriter();
            writer.levels = new int[]{0, 4, 5, 3};
            writer.width = 80;
            writer.height = 12;
            var planes = randomPlanes(writer, 11, 12, 0.6);
            var codestream = writer.codestream(planes);
            assertArrayEquals(planes, NikonHighEfficiency.coefficients(codestream, header(codestream),
                    MediaLimits.DEFAULT));
        }
    }

    @Nested
    @DisplayName("reconstruction")
    class Reconstruction {
        static int at(int[] plane, int width, int height, int x, int y) {
            return plane[Math.clamp(y, 0, height - 1) * width + Math.clamp(x, 0, width - 1)];
        }

        // the forward 5/3 lifting of JPEG XS (ISO/IEC 21122-1 annex E) over n samples, low half then high half
        static void analyze(int[] in, int[] out, int n) {
            var nl = (n + 1) / 2;
            var nh = n / 2;
            var high = new int[nh];
            for (var i = 0; i < nh; i++) {
                var right = 2 * i + 2 < n ? in[2 * i + 2] : in[2 * i];
                high[i] = in[2 * i + 1] - ((in[2 * i] + right) >> 1);
            }
            for (var i = 0; i < nl; i++) {
                var previous = high[Math.max(i - 1, 0)];
                var current = high[Math.min(i, nh - 1)];
                out[i] = in[2 * i] + ((previous + current + 2) >> 2);
            }
            System.arraycopy(high, 0, out, nl, nh);
        }

        static void forwardWavelet(int[] plane, int width, int height, int levels) {
            var column = new int[height];
            var result = new int[height];
            for (var x = 0; x < width; x++) {
                for (var y = 0; y < height; y++) {
                    column[y] = plane[y * width + x];
                }
                analyze(column, result, height);
                for (var y = 0; y < height; y++) {
                    plane[y * width + x] = result[y];
                }
            }
            var row = new int[width];
            var out = new int[width];
            for (var y = 0; y < height; y++) {
                System.arraycopy(plane, y * width, row, 0, width);
                analyze(row, out, width);
                System.arraycopy(out, 0, plane, y * width, width);
            }
            var lowHeight = (height + 1) / 2;
            var length = (width + 1) / 2;
            for (var level = 2; level <= levels; level++) {
                for (var y = 0; y < lowHeight; y++) {
                    System.arraycopy(plane, y * width, row, 0, length);
                    analyze(row, out, length);
                    System.arraycopy(out, 0, plane, y * width, length);
                }
                length = (length + 1) / 2;
            }
        }

        // the forward lifting the decoder inverts, on component planes: 0 on the green right of the top-left
        // photosite of each 2x2 block, 1 on the top-left one, 2 on the green below it, 3 diagonally
        static void forwardLifting(int[][] p, int w, int h) {
            var y0 = p[0];
            var c1 = p[1];
            var d2 = p[2];
            var c3 = p[3];
            for (var y = 0; y < h; y++) {
                for (var x = 0; x < w; x++) {
                    c1[y * w + x] -= (at(y0, w, h, x - 1, y) + y0[y * w + x] + at(d2, w, h, x, y - 1) + d2[y * w + x]) >> 2;
                    c3[y * w + x] -= (d2[y * w + x] + at(d2, w, h, x + 1, y) + y0[y * w + x] + at(y0, w, h, x, y + 1)) >> 2;
                }
            }
            for (var y = 0; y < h; y++) {
                for (var x = 0; x < w; x++) {
                    y0[y * w + x] += (c1[y * w + x] + at(c1, w, h, x + 1, y) + at(c3, w, h, x, y - 1) + c3[y * w + x]) >> 3;
                    d2[y * w + x] += (at(c3, w, h, x - 1, y) + c3[y * w + x] + c1[y * w + x] + at(c1, w, h, x, y + 1)) >> 3;
                }
            }
            for (var y = 0; y < h; y++) {
                for (var x = 0; x < w; x++) {
                    d2[y * w + x] -= (at(y0, w, h, x - 1, y) + y0[y * w + x] + at(y0, w, h, x - 1, y + 1)
                            + at(y0, w, h, x, y + 1)) >> 2;
                }
            }
            for (var y = 0; y < h; y++) {
                for (var x = 0; x < w; x++) {
                    y0[y * w + x] += (at(d2, w, h, x, y - 1) + at(d2, w, h, x + 1, y - 1) + d2[y * w + x]
                            + at(d2, w, h, x + 1, y)) >> 3;
                }
            }
        }

        @ParameterizedTest(name = "{0}x{1}")
        @CsvSource({"64, 16", "66, 20", "130, 12", "512, 64", "68, 8"})
        @DisplayName("inverts the forward wavelet and lifting exactly")
        void inverse(int width, int height) {
            var writer = new NikonHighEfficiencyWriter();
            writer.width = width;
            writer.height = height;
            var cw = width / 2;
            var ch = height / 2;
            var random = new SplittableRandom(width * 7L + height);
            var values = new int[4][cw * ch];
            for (var plane : values) {
                for (var i = 0; i < plane.length; i++) {
                    plane[i] = random.nextInt(-20000, 40000);
                }
            }
            var planes = new int[4][];
            for (var c = 0; c < 4; c++) {
                planes[c] = values[c].clone();
            }
            forwardLifting(planes, cw, ch);
            for (var c = 0; c < 4; c++) {
                if (writer.levels[c] > 0) {
                    forwardWavelet(planes[c], cw, ch, writer.levels[c]);
                }
            }
            var codestream = writer.codestream(new int[4][cw * ch]);
            NikonHighEfficiency.reconstruct(planes, header(codestream));
            assertArrayEquals(values, planes);
        }

        @Test
        @DisplayName("the picture header gives the square law of the camera files")
        void squareLawFields() {
            var writer = new NikonHighEfficiencyWriter();
            var codestream = writer.codestream(new int[4][writer.width / 2 * (writer.height / 2)]);
            var header = header(codestream);
            assertEquals(1008, header.black());
            assertEquals(84429, header.gain());
            assertEquals(16255, header.pivot());
            // the black level is 11 bits from bit 29 of the 13 bytes after the 24 standard bytes of the payload
            var law = 0x26 + 4 + 24;
            codestream[law + 3] ^= 0x04;
            assertEquals(1008 + 1024, header(codestream).black());
            codestream[law + 3] ^= 0x04;
            // bits that select the form of the law, which the converter changes its output for or refuses
            for (var bit : new int[]{5, 8, 12, 16, 17, 28, 42, 43, 96, 103}) {
                codestream[law + bit / 8] ^= (byte) (0x80 >>> bit % 8);
                assertThrows(MediaException.Unsupported.class, () -> header(codestream), "bit " + bit);
                codestream[law + bit / 8] ^= (byte) (0x80 >>> bit % 8);
            }
        }

        // the SHA-256 of the 65536 photosites, little-endian 16-bit words, that Adobe DNG Converter 18.0 stores for
        // the reconstructed values -32768 to 32767 of synthetic files: a Z 6III sample whose codestream was replaced
        // by one reconstructing a noisy ramp over every value, with the camera's square law and with one field
        // changed by flipping a bit of the picture header
        @ParameterizedTest(name = "black {0}, gain {1}, pivot {2}")
        @CsvSource({
                "1008, 84429, 16255, d0466c78d556fef083a18b5c9431df5635af1a9d065a900950a31e7be8a12fb6",
                "2032, 84429, 16255, 49ffedcab0a0fc0e74b07766316e27f2933dc569c1905e1b30ae723527d4c6e0",
                "1008, 18893, 16255, f9bef337d2f7f565f7c0ec83b030d971e328809118dd0f55e36b2caa141d8869",
                "1008, 84429, 49023, b978b73871a406a0708410449136df9eb43d04b6d3fa410df6702e80c8b1228b",
                "1008, 84429, 16254, 596d118c2204920c154af85e3f41d74ad700acd4a913e4389e3c5fe91126d5a7"})
        @DisplayName("the square law maps every 16-bit code to the photosite Adobe DNG Converter stores")
        void squareLaw(int black, int gain, int pivot, String sha256) throws Exception {
            var words = ByteBuffer.allocate(2 << 16).order(ByteOrder.LITTLE_ENDIAN);
            for (var v = -32768; v <= 32767; v++) {
                words.putShort((short) NikonHighEfficiency.linear(v, black, gain, pivot));
            }
            var digest = MessageDigest.getInstance("SHA-256").digest(words.array());
            assertEquals(sha256, HexFormat.of().formatHex(digest));
            // values outside the 16-bit codes take those of the nearest code
            assertEquals(NikonHighEfficiency.linear(-32768, black, gain, pivot),
                    NikonHighEfficiency.linear(Integer.MIN_VALUE, black, gain, pivot));
            assertEquals(NikonHighEfficiency.linear(32767, black, gain, pivot),
                    NikonHighEfficiency.linear(Integer.MAX_VALUE, black, gain, pivot));
        }

        @Test
        @DisplayName("the camera's square law maps the pivot to the black level and clamps to 14 bits")
        void squareLawValues() {
            // values the converter stores for these reconstructed values
            int[][] pairs = {{-32768, 0}, {-32765, 1}, {-20000, 962}, {-16918, 1007}, {-16917, 1008}, {-16270, 1008},
                    {-16269, 1009}, {0, 2734}, {16000, 7701}, {32000, 15909}, {32765, 16382}, {32766, 16383}};
            for (var pair : pairs) {
                assertEquals(pair[1], NikonHighEfficiency.linear(pair[0], 1008, 84429, 16255), "value " + pair[0]);
            }
        }
    }

    @Nested
    @DisplayName("NEF files")
    class Files_ {
        static byte[] file(NikonHighEfficiencyWriter writer, long seed) {
            var planes = randomPlanes(writer, seed, 9, 0.5);
            planes[0][0] = 3000;
            return writer.nef(writer.codestream(planes));
        }

        @Test
        @DisplayName("describes and decodes a written file")
        void decodes() {
            var writer = new NikonHighEfficiencyWriter();
            writer.width = 128;
            writer.height = 32;
            var bytes = file(writer, 3);
            var info = RawDecoder.info(MediaSource.of(bytes));
            assertEquals(14, info.bitsPerSample());
            assertEquals("RGGB", info.cfaPattern().orElseThrow().toString());
            var raw = RawDecoder.decodeRaw(MediaSource.of(bytes));
            assertEquals(128, raw.width());
            assertEquals(32, raw.height());
            var file = RawParser.parse(MediaSource.of(bytes), MediaLimits.DEFAULT);
            assertTrue(file.sensor.compression.startsWith("Nikon HE"), file.sensor.compression);
            assertEquals(1008, file.sensor.black[0]);
        }

        @Test
        @DisplayName("a codestream whose size differs from its directory is rejected")
        void mismatch() {
            var writer = new NikonHighEfficiencyWriter();
            var bytes = file(writer, 4);
            // the image width tag of the directory: 64 becomes 66
            var at = 8 + 2 + 12 + 8;
            assertEquals(64, bytes[at]);
            bytes[at] = 66;
            assertThrows(MediaException.class, () -> RawDecoder.decodeRaw(MediaSource.of(bytes)));
        }

        @ParameterizedTest(name = "keep {0}")
        @ValueSource(doubles = {0.99, 0.9, 0.7, 0.5})
        @DisplayName("a file cut short fails as malformed")
        void truncated(double keep) {
            var writer = new NikonHighEfficiencyWriter();
            writer.width = 96;
            writer.height = 24;
            var bytes = file(writer, 5);
            var cut = Arrays.copyOf(bytes, (int) (bytes.length * keep));
            assertThrows(MediaException.Malformed.class, () -> RawDecoder.decodeRaw(MediaSource.of(cut)));
        }

        @Test
        @DisplayName("2000 mutations of a written file produce a result or a media exception, quickly")
        void mutations() {
            var writer = new NikonHighEfficiencyWriter();
            writer.width = 96;
            writer.height = 24;
            writer.sliceRows = 2;
            var original = file(writer, 6);
            var random = new SplittableRandom(99);
            assertTimeoutPreemptively(Duration.ofMinutes(2), () -> {
                for (var round = 0; round < 2000; round++) {
                    var bytes = original.clone();
                    // most changes land in the codestream, which ends the file
                    for (var k = 0; k < 1 + random.nextInt(8); k++) {
                        var at = random.nextInt(4) == 0 ? random.nextInt(bytes.length)
                                : bytes.length - 1 - random.nextInt(Math.min(bytes.length, 3000));
                        bytes[at] = (byte) random.nextInt(256);
                    }
                    try {
                        RawDecoder.decodeRaw(MediaSource.of(bytes));
                    } catch (MediaException expected) {
                        // a damaged file may be rejected
                    }
                }
            });
        }
    }

    @Nested
    @DisplayName("main header")
    class Header {
        static byte[] valid() {
            var writer = new NikonHighEfficiencyWriter();
            return writer.codestream(new int[4][writer.width / 2 * (writer.height / 2)]);
        }

        @Test
        @DisplayName("reads the size, slices and per-component levels")
        void parses() {
            var header = header(valid());
            assertEquals(64, header.width());
            assertEquals(16, header.height());
            assertEquals(2, header.sliceRows());
            assertArrayEquals(new int[]{5, 5, 0, 5}, header.levels());
            assertEquals(25, header.gains().length);
        }

        @ParameterizedTest(name = "byte {0} = {1}")
        @CsvSource({
                // the capabilities vendor, the picture header length, the component count, the slice coding and
                // progression, the quantizer and sign flags, a component's levels, a component's bit depth
                "6, 0x44, unsupported", "0x29, 26, unsupported", "0x3A, 3, unsupported", "0x3F, 0x00, unsupported",
                "0x41, 0x34, unsupported", "0x58, 0x52, unsupported", "0x56, 12, unsupported",
                // a height that is not a multiple of 4
                "0x35, 18, unsupported",
                // the weights table length, the start marker
                "0x62, 50, malformed", "0, 0x00, malformed"})
        @DisplayName("refuses parameters no sample file has shown and damaged structure")
        void refuses(String offset, String value, String kind) {
            var bytes = valid();
            bytes[Integer.decode(offset)] = (byte) (int) Integer.decode(value);
            var expected = kind.equals("unsupported") ? MediaException.Unsupported.class
                    : MediaException.Malformed.class;
            assertThrows(expected, () -> header(bytes));
        }

        @Test
        @DisplayName("a header cut anywhere is malformed")
        void truncated() {
            var bytes = valid();
            for (var length = 0; length < 0x95; length++) {
                var cut = Arrays.copyOf(bytes, length);
                assertThrows(MediaException.class, () -> header(cut), "cut at " + length);
            }
        }
    }

    @ParameterizedTest(name = "{0} against {1}")
    @CsvSource({
            "NEF_Z50II_HE_DX, NEF_Z50II_LOSSLESS_DX", "NEF_Z50II_HE_STAR_DX, NEF_Z50II_LOSSLESS_DX",
            "NEF_Z50II_HE_1_1, NEF_Z50II_LOSSLESS_1_1", "NEF_Z50II_HE_STAR_1_1, NEF_Z50II_LOSSLESS_1_1",
            "NEF_Z50II_HE_16_9, NEF_Z50II_LOSSLESS_16_9", "NEF_Z50II_HE_STAR_16_9, NEF_Z50II_LOSSLESS_16_9"})
    @DisplayName("the HE and HE* photosites agree with the lossless file of the same scene")
    void twins(RawSamples compressed, RawSamples lossless) {
        var he = sensor(compressed);
        var reference = sensor(lossless);
        assertEquals(reference.width, he.width);
        assertEquals(reference.height, he.height);
        var columns = 64;
        var rows = 48;
        var a = new double[columns * rows * 4];
        var b = new double[columns * rows * 4];
        for (var y = 0; y < he.height; y++) {
            for (var x = 0; x < he.width; x++) {
                var cell = ((y * rows / he.height) * columns + x * columns / he.width) * 4 + (y & 1) * 2 + (x & 1);
                a[cell] += (he.integers[y * he.width + x] & 0xFFFF) - 1008;
                b[cell] += (reference.integers[y * he.width + x] & 0xFFFF) - 1008;
            }
        }
        var squared = 0.0;
        var count = 0;
        for (var i = 0; i < a.length; i++) {
            if (b[i] > 0) {
                squared += (a[i] / b[i] - 1) * (a[i] / b[i] - 1);
                count++;
            }
        }
        var relative = Math.sqrt(squared / count);
        assertTrue(relative < 0.04, "the cell means differ from the lossless file's by " + relative);
    }

    @Test
    @EnabledIfSystemProperty(named = "smalt.fuzz.writeSeeds", matches = "true",
            disabledReason = "[opt-in:fuzz-seeds] runs only with -Dsmalt.fuzz.writeSeeds=true")
    @DisplayName("writes the seeds of the NEF fuzz target")
    void fuzzSeeds() {
        var predicted = new NikonHighEfficiencyWriter();
        predicted.width = 96;
        predicted.height = 24;
        FuzzSeeds.write(FUZZ_TARGET, "nef", "valid-nikon-he.nef", Files_.file(predicted, 21));
        var raw = new NikonHighEfficiencyWriter();
        raw.width = 64;
        raw.height = 16;
        raw.raw = true;
        raw.sliceRows = 1;
        FuzzSeeds.write(FUZZ_TARGET, "nef", "valid-nikon-he-raw-counts.nef", Files_.file(raw, 22));

    }

    private static SensorPlane sensor(RawSamples sample) {
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(sample.fetch());
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
        var file = RawParser.parse(MediaSource.of(bytes), MediaLimits.DEFAULT);
        var sensor = file.sensor;
        assertTrue(sensor != null, file.unsupported);
        var plane = new SensorPlane(sensor.width, sensor.height, 1, false, MediaLimits.DEFAULT);
        sensor.reader.read(plane, MediaLimits.DEFAULT);
        return plane;
    }
}
