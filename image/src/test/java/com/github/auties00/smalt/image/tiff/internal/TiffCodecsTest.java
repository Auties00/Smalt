package com.github.auties00.smalt.image.tiff.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.exif.ExifReader;
import com.github.auties00.smalt.io.MediaSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Arrays;
import java.util.HexFormat;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests of the TIFF codec components: LZW, PackBits, the predictors, SGILog, the directory
 * writer and reader, and EXIF extraction.
 */
class TiffCodecsTest {
    private static byte[] data(int length, int alphabet, long seed) {
        var random = new Random(seed);
        var data = new byte[length];
        for (var i = 0; i < length; i++) {
            data[i] = (byte) (random.nextInt(8) == 0 ? random.nextInt(256) : random.nextInt(alphabet));
            if (i > 0 && random.nextInt(5) == 0) {
                data[i] = data[i - 1];
            }
        }
        return data;
    }

    @Nested
    @DisplayName("LZW")
    class Lzw {
        @ParameterizedTest(name = "{0} bytes")
        @ValueSource(ints = {0, 1, 2, 3, 255, 4096, 70_000, 400_000})
        @DisplayName("round-trips encoder output, table resets included")
        void roundTrip(int length) {
            var encoder = new LzwEncoder();
            var decoder = new LzwDecoder();
            for (var alphabet : new int[]{1, 2, 16, 256}) {
                var input = data(length, alphabet, length * 31L + alphabet);
                var encoded = encoder.encode(input, 0, input.length);
                var output = new byte[length];
                assertEquals(length, decoder.decode(encoded, 0, encoded.length, output, 0, length));
                assertArrayEquals(input, output, "alphabet " + alphabet);
            }
        }

        @Test
        @DisplayName("stops at the output size, the end of the input and the end code")
        void bounds() {
            var input = data(10_000, 4, 9);
            var encoded = new LzwEncoder().encode(input, 0, input.length);
            var decoder = new LzwDecoder();
            var half = new byte[5000];
            assertEquals(5000, decoder.decode(encoded, 0, encoded.length, half, 0, 5000));
            assertArrayEquals(Arrays.copyOf(input, 5000), half);
            var truncated = new byte[10_000];
            var produced = decoder.decode(encoded, 0, encoded.length / 2, truncated, 0, truncated.length);
            assertTrue(produced > 0 && produced < 10_000);
            assertArrayEquals(Arrays.copyOf(input, produced), Arrays.copyOf(truncated, produced));
            var more = new byte[20_000];
            assertEquals(10_000, decoder.decode(encoded, 0, encoded.length, more, 0, more.length));
        }

        @Test
        @DisplayName("rejects a code past the next free entry")
        void invalid() {
            // clear (256), literal 'A' (65), then code 300 when only 258 is defined, 9 bits each
            var codes = new int[]{256, 65, 300};
            var bits = new java.io.ByteArrayOutputStream();
            var accumulator = 0L;
            var count = 0;
            for (var code : codes) {
                accumulator = accumulator << 9 | code;
                count += 9;
                while (count >= 8) {
                    count -= 8;
                    bits.write((int) (accumulator >>> count));
                }
            }
            bits.write((int) (accumulator << (8 - count)));
            var encoded = bits.toByteArray();
            assertThrows(MediaException.Malformed.class, () -> new LzwDecoder().decode(encoded, 0, encoded.length, new byte[16], 0, 16));
        }
    }

    @Nested
    @DisplayName("PackBits")
    class PackBitsCodec {
        @Test
        @DisplayName("decodes the TIFF 6.0 section 9 example")
        void specification() {
            var input = HexFormat.of().parseHex("FEAA0280002AFDAA0380002A22F7AA");
            var expected = HexFormat.of().parseHex("AAAAAA80002AAAAAAAAA80002A22AAAAAAAAAAAAAAAAAAAA");
            var output = new byte[expected.length];
            assertEquals(expected.length, PackBits.decode(input, 0, input.length, output, 0, output.length));
            assertArrayEquals(expected, output);
        }

        @ParameterizedTest(name = "row of {0} bytes")
        @ValueSource(ints = {1, 2, 3, 127, 128, 129, 130, 1000})
        @DisplayName("round-trips rows of every run structure")
        void roundTrip(int rowBytes) {
            for (var alphabet : new int[]{1, 2, 256}) {
                var input = data(rowBytes * 3, alphabet, rowBytes + alphabet);
                var encoded = PackBits.encode(input, 0, rowBytes, 3);
                var output = new byte[input.length];
                assertEquals(input.length, PackBits.decode(encoded, 0, encoded.length, output, 0, output.length));
                assertArrayEquals(input, output);
            }
        }
    }

    @Nested
    @DisplayName("predictors")
    class Predictors {
        @ParameterizedTest(name = "{0}-byte samples")
        @ValueSource(ints = {1, 2})
        @DisplayName("horizontal differencing is reversed exactly in both byte orders")
        void horizontal(int bytes) {
            for (var little : new boolean[]{true, false}) {
                var width = 13;
                var stride = 3;
                var rowBytes = width * stride * bytes + 5;
                var input = data(rowBytes * 4, 256, bytes);
                var work = input.clone();
                Predictor.applyHorizontal(work, 0, rowBytes, 4, stride, width, bytes, little);
                assertFalse(Arrays.equals(input, work));
                Predictor.undoHorizontal(work, 0, rowBytes, 4, stride, width, bytes, little);
                assertArrayEquals(input, work);
            }
        }

        @ParameterizedTest(name = "{0}-byte samples")
        @ValueSource(ints = {2, 4, 8})
        @DisplayName("floating-point differencing is reversed exactly")
        void floatingPoint(int bytes) {
            var width = 11;
            var stride = 2;
            var rowBytes = width * stride * bytes;
            var input = data(rowBytes * 3, 256, 100 + bytes);
            var work = input.clone();
            var scratch = new byte[rowBytes];
            applyFloatingPoint(work, 0, rowBytes, 3, stride, width * stride, bytes, scratch);
            Predictor.undoFloatingPoint(work, 0, rowBytes, 3, stride, width * stride, bytes, scratch);
            assertArrayEquals(input, work);
        }

        @Test
        @DisplayName("floating-point rows with padding past their samples keep the padding")
        void floatingPointPadded() {
            // 7 three-sample pixels of 4-byte samples, rows padded to 96 bytes
            var stride = 3;
            var samples = 7 * stride;
            var bytes = 4;
            var rowBytes = 96;
            var input = data(rowBytes * 2, 256, 77);
            var work = input.clone();
            var scratch = new byte[rowBytes];
            applyFloatingPoint(work, 0, rowBytes, 2, stride, samples, bytes, scratch);
            Predictor.undoFloatingPoint(work, 0, rowBytes, 2, stride, samples, bytes, scratch);
            assertArrayEquals(input, work);
        }

        // the inverse of Predictor.undoFloatingPoint: byte planes, then byte-wise differencing
        private static void applyFloatingPoint(byte[] data, int offset, int rowBytes, int rows, int stride, int samples,
                                               int bytesPerSample, byte[] scratch) {
            var count = samples * bytesPerSample;
            for (var row = 0; row < rows; row++) {
                var base = offset + row * rowBytes;
                for (var sample = 0; sample < samples; sample++) {
                    var at = base + sample * bytesPerSample;
                    for (var plane = 0; plane < bytesPerSample; plane++) {
                        scratch[plane * samples + sample] = data[at + plane];
                    }
                }
                System.arraycopy(scratch, 0, data, base, count);
                for (var i = base + count - 1; i >= base + stride; i--) {
                    data[i] -= data[i - stride];
                }
            }
        }
    }

    @Nested
    @DisplayName("directories")
    class Directories {
        @ParameterizedTest(name = "little-endian {0}")
        @ValueSource(booleans = {true, false})
        @DisplayName("written directories with sub-directories read back")
        void roundTrip(boolean little) {
            for (var big : new boolean[]{false, true}) {
                var child = new IfdBuilder(little, big);
                child.ascii(0x9003, "2026:10:02 12:00:00");
                var root = new IfdBuilder(little, big);
                root.longs(TiffTags.IMAGE_WIDTH, 70000);
                root.shorts(TiffTags.BITS_PER_SAMPLE, 8, 8, 8);
                root.offsets(TiffTags.STRIP_OFFSETS, 1, 2, 3, 4);
                root.rational(TiffTags.X_RESOLUTION, 300, 2);
                root.undefined(TiffTags.ICC_PROFILE, new byte[]{9, 8, 7});
                root.child(TiffTags.EXIF_IFD, child);
                var header = big ? 16 : 8;
                var body = root.write(header, 0);
                var file = new byte[header + body.length];
                file[0] = file[1] = (byte) (little ? 'I' : 'M');
                file[little ? 2 : 3] = (byte) (big ? 43 : 42);
                if (big) {
                    file[little ? 4 : 5] = 8;
                    file[little ? 8 : 15] = (byte) header;
                } else {
                    file[little ? 4 : 7] = (byte) header;
                }
                System.arraycopy(body, 0, file, header, body.length);
                var parsed = TiffFile.open(MediaSource.of(file), MediaLimits.DEFAULT);
                var directory = parsed.pages().getFirst();
                assertEquals(70000, directory.longValue(TiffTags.IMAGE_WIDTH, 0));
                assertArrayEquals(new long[]{8, 8, 8}, directory.longs(TiffTags.BITS_PER_SAMPLE));
                assertArrayEquals(new long[]{1, 2, 3, 4}, directory.longs(TiffTags.STRIP_OFFSETS));
                assertEquals(150.0, directory.doubleValue(TiffTags.X_RESOLUTION, 0));
                assertArrayEquals(new byte[]{9, 8, 7}, directory.bytes(TiffTags.ICC_PROFILE));
                var exif = parsed.readDirectory(directory.longValue(TiffTags.EXIF_IFD, 0));
                assertEquals("2026:10:02 12:00:00", exif.string(0x9003));
            }
        }

        @Test
        @DisplayName("extracts the EXIF block of a camera TIFF in the form EXIF readers expect")
        void exif() {
            var pics = com.github.auties00.smalt.image.tiff.LibTiff.pics();
            var file = pics.get("pc260001.tif");
            var parsed = TiffFile.open(MediaSource.of(file), MediaLimits.DEFAULT);
            var block = TiffExif.extract(parsed.pages().getFirst());
            assertNotNull(block);
            var data = ExifReader.parse(block);
            assertTrue(data.exif().size() > 10, data.toString());
            assertTrue(data.ifd0().containsKey(271));
            assertNull(TiffExif.extract(TiffFile.open(MediaSource.of(pics.get("jello.tif")), MediaLimits.DEFAULT).pages().getFirst()));
        }
    }

    @Nested
    @DisplayName("SGILog")
    class SgiLogCodec {
        // LogL row of 4 pixels: high plane as a run of 4 x 0x3F, low plane as 4 literals
        private static final byte[] ROW = {(byte) 130, 0x3F, 4, 0, 64, (byte) 128, (byte) 255};

        private static int gray(int le) {
            var y = Math.pow(2, (le + 0.5) / 256 - 64);
            return y >= 1 ? 255 : (int) (256 * Math.sqrt(y));
        }

        @Test
        @DisplayName("decodes LogL run-length planes to 256 * sqrt(Y)")
        void logL() {
            var out = new byte[8];
            var raw = new byte[ROW.length * 2];
            System.arraycopy(ROW, 0, raw, 0, ROW.length);
            System.arraycopy(ROW, 0, raw, ROW.length, ROW.length);
            assertEquals(8, SgiLog.decode(raw, raw.length, out, 4, 2, SgiLog.LOG_L));
            var lows = new int[]{0, 64, 128, 255};
            for (var i = 0; i < 8; i++) {
                assertEquals(gray(0x3F00 | lows[i % 4]), out[i] & 0xFF, "pixel " + i);
            }
        }

        @Test
        @DisplayName("stops at the row whose data ends early, leaving it and the rest untouched")
        void truncated() {
            var out = new byte[8];
            var raw = Arrays.copyOf(ROW, ROW.length + 3);
            System.arraycopy(ROW, 0, raw, ROW.length, 3);
            assertEquals(4, SgiLog.decode(raw, raw.length, out, 4, 2, SgiLog.LOG_L));
            for (var i = 4; i < 8; i++) {
                assertEquals(0, out[i]);
            }
            assertEquals(0, SgiLog.decode(new byte[5], 5, new byte[12], 4, 1, SgiLog.LOG_LUV_24));
        }

        @Test
        @DisplayName("renders zero luminance black and a neutral 24-bit LogLuv gray with equal channels")
        void logLuv24() {
            var out = new byte[6];
            // Le' 640 (Y = 2^((640.5) / 64 - 12) = 0.25), chromaticity index 0x3FFF is past the table: neutral
            var p = 640 << 14 | 0x3FFF;
            var raw = new byte[]{0, 0, 0, (byte) (p >>> 16), (byte) (p >>> 8), (byte) p};
            assertEquals(6, SgiLog.decode(raw, raw.length, out, 2, 1, SgiLog.LOG_LUV_24));
            assertArrayEquals(new byte[]{0, 0, 0}, Arrays.copyOf(out, 3));
            assertTrue(Math.abs((out[3] & 0xFF) - (out[4] & 0xFF)) <= 3 && Math.abs((out[4] & 0xFF) - (out[5] & 0xFF)) <= 3,
                    Arrays.toString(out));
            assertTrue((out[4] & 0xFF) > 120 && (out[4] & 0xFF) < 140, Arrays.toString(out));
        }
    }

    @Test
    @DisplayName("the fax code tables are prefix-free (built without collision)")
    void faxTables() {
        assertDoesNotThrow(CcittFaxDecoder::new);
    }
}
