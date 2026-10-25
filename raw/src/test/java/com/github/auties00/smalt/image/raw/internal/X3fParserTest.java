package com.github.auties00.smalt.image.raw.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.ImageFormat;
import com.github.auties00.smalt.image.jpeg.JpegDecoder;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.image.raw.RawDecoder;
import com.github.auties00.smalt.image.raw.RawDecoderOptions;
import com.github.auties00.smalt.image.raw.RawFormat;
import com.github.auties00.smalt.image.raw.RawPreview;
import com.github.auties00.smalt.image.raw.RawSamples;
import com.github.auties00.smalt.image.raw.X3fWriter;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.fuzz.FuzzSeeds;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.SplittableRandom;

import static com.github.auties00.smalt.image.raw.X3fWriter.floats;
import static com.github.auties00.smalt.image.raw.X3fWriter.matrix;
import static com.github.auties00.smalt.image.raw.X3fWriter.propertyList;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the Sigma X3F parser on synthetic files ({@link X3fWriter}: every raw coding, the Quattro layers, the
 * metadata blocks of type 2, 4 and 5, the levels, the colors and the shot, the bad pixel and lens shading
 * corrections, the thumbnails, damaged and unsupported files, random damage) and on the CC0 sample files: their
 * decoded layers must hash to the values the X3F tools library (commit e8b48ca, built from source and run as a
 * reference) decodes, and their metadata and previews must match what the files hold. The digests of the
 * synthetic files of the codings no CC0 sample shows (the uncompressed and Polaroid x530 variants of the older
 * coding, the Huffman coded thumbnail) were also computed by that library, on the seeds this class writes.
 */
class X3fParserTest {
    /** The fuzz test class whose seeds the synthetic files feed. */
    private static final String FUZZ_TARGET = "com.github.auties00.smalt.image.raw.fuzz.RawFuzzTest";

    private static RawFile parse(byte[] bytes) {
        return RawParser.parse(MediaSource.of(bytes), MediaLimits.DEFAULT);
    }

    private static short[] decode(RawFile file) {
        return decode(file, false);
    }

    private static short[] decode(RawFile file, boolean corrections) {
        assertNotNull(file.sensor, file.unsupported);
        var sensor = file.sensor;
        var plane = new SensorPlane(sensor.width, sensor.height, sensor.samplesPerPixel, false, MediaLimits.DEFAULT);
        sensor.reader.read(plane, MediaLimits.DEFAULT, corrections);
        return plane.integers;
    }

    private static short[] interleaved(int[][] layers, int bias) {
        var samples = new short[layers[0].length * 3];
        for (var index = 0; index < layers[0].length; index++) {
            for (var layer = 0; layer < 3; layer++) {
                samples[index * 3 + layer] = (short) Math.max(0, layers[layer][index] + bias);
            }
        }
        return samples;
    }

    private static String sha256(short[] samples) throws Exception {
        var bytes = ByteBuffer.allocate(samples.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        bytes.asShortBuffer().put(samples);
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.array()));
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static X3fWriter writer(int type, int format, long rowSize, long seed, int low, int high) {
        var x3f = new X3fWriter();
        x3f.type = type;
        x3f.format = format;
        x3f.rowSize = rowSize;
        return x3f.layers(seed, low, high);
    }

    // the synthetic files of the codings no CC0 sample shows, written as fuzz seeds and decoded by the X3F tools
    // library: the uncompressed older coding, the Polaroid x530's coding, Huffman coded and uncompressed
    private static X3fWriter uncompressed() {
        return writer(3, 6, 168, 401, -60, 450);
    }

    private static X3fWriter x530() {
        var x3f = writer(3, 5, -1, 402, -60, 450);
        x3f.preTrue = true;
        return x3f;
    }

    private static X3fWriter x530Uncompressed() {
        var x3f = writer(3, 5, 160, 403, -60, 450);
        x3f.preTrue = true;
        return x3f;
    }

    private static X3fWriter thumbnailFile() {
        var x3f = new X3fWriter().layers(404, 0, 3000);
        x3f.thumbnail(11, 30, 18, 405);
        x3f.thumbnail(3, 20, 12, 406);
        x3f.quattroJpeg = true;
        return x3f;
    }

    private static X3fWriter quattro() {
        var x3f = new X3fWriter();
        x3f.type = 1;
        x3f.format = 35;
        x3f.camfType = 5;
        x3f.layers(407, 0, 3000);
        return x3f;
    }

    @ParameterizedTest(name = "type {0} format {1} row size {2}")
    @CsvSource({"3, 30, -1, 0, 4000", "1, 30, -1, 0, 4095", "3, 6, -1, -60, 450", "3, 5, -1, -60, 450",
            "3, 6, 160, -60, 450", "3, 5, 172, -60, 450", "1, 35, -1, 0, 4000"})
    @DisplayName("decodes the TRUE codings, the older coding with and without Huffman codes and the binned Quattro "
            + "layout exactly")
    void coding(int type, int format, long rowSize, int low, int high) {
        var x3f = new X3fWriter();
        x3f.type = type;
        x3f.format = format;
        x3f.rowSize = rowSize;
        x3f.binned = true;
        x3f.topExtra = 4;
        x3f.layers(type * 100 + format + rowSize, low, high);
        var file = parse(x3f.write());
        assertEquals(RawFormat.X3F, file.format);
        var bias = x3f.older() && !x3f.uncompressed() ? X3fDecoder.HUFFMAN_BIAS : 0;
        var kept = new int[3][];
        for (var layer = 0; layer < 3; layer++) {
            kept[layer] = new int[x3f.width * x3f.height];
            var columns = x3f.columns()[layer];
            for (var row = 0; row < x3f.height; row++) {
                System.arraycopy(x3f.layers[layer], row * columns, kept[layer], row * x3f.width, x3f.width);
            }
        }
        assertArrayEquals(interleaved(kept, bias), decode(file));
        assertEquals(x3f.uncompressed() ? "uncompressed" : x3f.older() ? "Sigma Huffman" : "Sigma TRUE",
                file.sensor.compression);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({
            // SHA-256 of the layers the X3F tools library decodes from the fuzz seeds of the same names, in this
            // decoder's convention (interleaved, little-endian 16-bit, Huffman coded values plus 1024)
            "valid-uncompressed.x3f, 35e12deba2de601a93f6b4a5c04a4819d1e7af990c060d814cee7bd716d1bffa",
            "valid-x530.x3f, cbc551e2bd600174d075b61c9475bd9b4556044175ac7b08e6d833f729859bb6",
            "valid-x530-uncompressed.x3f, e90413f7826224d6458b9e1dde142f0bf0c5a8b7c960cb8606d2817024dc5e95"
    })
    @DisplayName("decodes the codings no sample shows exactly as the X3F tools library does")
    void reference(String seed, String sha256) throws Exception {
        var x3f = switch (seed) {
            case "valid-uncompressed.x3f" -> uncompressed();
            case "valid-x530.x3f" -> x530();
            default -> x530Uncompressed();
        };
        var samples = decode(parse(x3f.write()));
        assertEquals(sha256, sha256(samples));
        var bias = x3f.uncompressed() ? 0 : X3fDecoder.HUFFMAN_BIAS;
        assertArrayEquals(interleaved(x3f.layers, bias), samples);
    }

    @ParameterizedTest(name = "CAMF type {0}")
    @CsvSource({"2", "4", "5"})
    @DisplayName("describes the image area, the levels, the colors and the shot")
    void description(int camfType) {
        var x3f = new X3fWriter().layers(5, 0, 4000);
        x3f.camfType = camfType;
        var file = parse(x3f.write());
        assertEquals("SIGMA", file.make);
        assertEquals("SIGMA TEST", file.model);
        assertEquals(200, file.iso);
        assertEquals(0.008, file.exposureTime, 1e-9);
        assertEquals(5.6, file.fNumber, 1e-9);
        assertEquals(16.6, file.focalLength, 1e-9);
        assertEquals("2010:02:01 08:27:32", file.captureTime);
        var sensor = file.sensor;
        assertEquals(3, sensor.samplesPerPixel);
        assertArrayEquals(new int[]{3, 2, x3f.height - 1, x3f.width - 2}, sensor.activeArea);
        assertArrayEquals(new double[]{2990, 3077, 3074}, sensor.white);
        decode(file);
        // the shield rows hold 7, 8 and 9: the column ranges are left out
        assertArrayEquals(new double[]{7, 8, 9}, sensor.black, 1e-9);
        var gain = new double[]{1.5, 1.02, 0.8 * 1.04};
        assertArrayEquals(new double[]{1 / gain[0], 1 / gain[1], 1 / gain[2]}, file.color.asShotNeutral, 1e-6);
        assertArrayEquals(new double[]{3.6, -4.4, 1.8, -1.3, 3.1, -0.8, 1.0, -3.6, 3.6}, file.color.cameraToSrgb, 1e-6);
        assertEquals(Math.log(4 * 0.8 * 1.04) / Math.log(2), file.color.baselineExposure, 1e-6);
        assertEquals(1, file.previews.size());
        assertEquals(48, file.previews.getFirst().width());
        assertNull(sensor.opcodes2);
        var info = RawDecoder.info(MediaSource.of(x3f.write()));
        assertEquals(x3f.width - 4, info.width());
        assertEquals(x3f.height - 4, info.height());
        assertTrue(info.isDecodable());
        var raw = RawDecoder.decodeRaw(MediaSource.of(x3f.write()));
        assertEquals(3, raw.channels());
        assertEquals(x3f.width - 4, raw.width());
    }

    @Test
    @DisplayName("decodes a type 5 metadata block byte for byte and rejects one that runs past its end")
    void camfType5() {
        var x3f = new X3fWriter().layers(6, 0, 4000);
        x3f.camfType = 5;
        x3f.defaultEntries();
        // a long text whose bytes jump by up to 255 between neighbors
        var text = new StringBuilder();
        var random = new SplittableRandom(7);
        for (var index = 0; index < 3000; index++) {
            text.append((char) random.nextInt(1, 256));
        }
        x3f.entries.add(X3fWriter.text("Noise", text.toString()));
        x3f.entries.add(matrix("Marker", 6, new int[]{2}, 4321, 65000));
        var bytes = x3f.write();
        var section = indexOf(bytes, new byte[]{'S', 'E', 'C', 'c', 0, 0, 2, 0, 5, 0, 0, 0});
        var length = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        var header = new long[]{length.getInt(section + 12), length.getInt(section + 16), 0, 0};
        var end = section + 28 + 32 + length.getInt(section + 28 + 28);
        var camf = X3fCamf.decode(5, header, Arrays.copyOfRange(bytes, section + 28, end));
        assertNotNull(camf);
        assertTrue(camf.has("Noise"));
        assertArrayEquals(new double[]{4321, 65000}, camf.numbers("Marker", 2));
        assertArrayEquals(new double[]{2, 3, 37, 22}, camf.numbers("ActiveImageArea", 4));
        assertEquals("AutoWBGain", camf.property("WhiteBalanceGains", "Auto"));
        assertThrows(MediaException.Malformed.class,
                () -> X3fCamf.decode(5, header, Arrays.copyOfRange(bytes, section + 28, end - 100)));
        assertThrows(MediaException.Malformed.class, () -> X3fCamf.decode(5, new long[]{-1, 0, 0, 0}, new byte[40]));
        assertArrayEquals(new double[]{2990, 3077, 3074}, parse(bytes).sensor.white);
    }

    @Test
    @DisplayName("derives the neutral and the matrix of an older camera from its illuminant matrices")
    void olderColors() {
        var x3f = new X3fWriter();
        x3f.type = 3;
        x3f.format = 6;
        x3f.preTrue = true;
        x3f.layers(6, -20, 450);
        var file = parse(x3f.write());
        assertArrayEquals(new double[]{3000 + 1024, 3100 + 1024, 3200 + 1024}, file.sensor.white);
        var cameraToXyz = ColorMath.multiply(new double[]{1.01, 0, 0, 0, 1, 0, 0, 0, 0.9},
                new double[]{0.9, 0.4, 0.5, -2.1, 5.0, -1.6, 2.2, -8.1, 8.7});
        var neutral = ColorMath.transform(ColorMath.invert(cameraToXyz), new double[]{0.95047, 1.0, 1.08883});
        assertArrayEquals(neutral, file.color.asShotNeutral, 1e-6);
        // a neutral, balanced, maps to sRGB white
        var white = ColorMath.transform(file.color.cameraToSrgb, new double[]{1, 1, 1});
        assertArrayEquals(new double[]{1, 1, 1}, white, 1e-3);
        decode(file);
        assertArrayEquals(new double[]{7 + 1024, 8 + 1024, 9 + 1024}, file.sensor.black, 1e-9);
    }

    @Test
    @DisplayName("falls back to the Sunlight entries for a Daylight setting and estimates a missing balance")
    void balanceNames() {
        var daylight = new X3fWriter().layers(7, 0, 3000);
        daylight.balance = "Daylight";
        daylight.defaultEntries();
        daylight.entries.replaceAll(entry -> new String(entry, StandardCharsets.ISO_8859_1).contains("WhiteBalance")
                ? propertyList(new String(entry, 20, entry.length - 20, StandardCharsets.ISO_8859_1).split("\0")[0],
                "Sunlight", new String(entry, StandardCharsets.ISO_8859_1).contains("Gains") ? "AutoWBGain" : "AutoCCMatrix")
                : entry);
        assertNotNull(parse(daylight.write()).color.asShotNeutral);
        var unknown = new X3fWriter().layers(7, 0, 3000);
        unknown.defaultEntries();
        unknown.balance = "Underwater";
        var file = parse(unknown.write());
        assertNull(file.color.asShotNeutral);
        assertTrue(file.sensor.estimateBalance);
        var none = new X3fWriter().layers(7, 0, 3000);
        none.camf = false;
        var bare = parse(none.write());
        assertNull(bare.sensor.activeArea);
        assertArrayEquals(new double[]{4095, 4095, 4095}, bare.sensor.white);
        decode(bare);
        assertArrayEquals(new double[]{0, 0, 0}, bare.sensor.black);
    }

    @ParameterizedTest(name = "code {0}")
    @CsvSource({"2, 2.0, 1.0, 0.5", "1, 1.5, 1.0, 0.8", "9, 1.5, 1.0, 0.8", "77, 1.5, 1.0, 0.8"})
    @DisplayName("names the white balance setting from the metadata block's code before the header's")
    void balanceCode(int code, double red, double green, double blue) {
        var x3f = new X3fWriter().layers(8, 0, 3000);
        x3f.entries.add(matrix("WhiteBalance", 5, new int[]{1}, code));
        x3f.entries.add(propertyList("WhiteBalanceGains", "Auto", "AutoWBGain", "Sunlight", "SunWBGain"));
        x3f.entries.add(propertyList("WhiteBalanceColorCorrections", "Auto", "Matrix", "Sunlight", "Matrix"));
        x3f.entries.add(floats("AutoWBGain", new int[]{3}, 1.5, 1.0, 0.8));
        x3f.entries.add(floats("SunWBGain", new int[]{3}, 2.0, 1.0, 0.5));
        x3f.entries.add(floats("Matrix", new int[]{3, 3}, 1, 0, 0, 0, 1, 0, 0, 0, 1));
        var file = parse(x3f.write());
        assertArrayEquals(new double[]{1 / red, 1 / green, 1 / blue}, file.color.asShotNeutral, 1e-6);
    }

    @Test
    @DisplayName("reads a Merrill file's image depth as its white level")
    void imageDepth() {
        var x3f = new X3fWriter().layers(8, 0, 4095);
        x3f.type = 1;
        x3f.defaultEntries();
        x3f.entries.add(matrix("ImageDepth", 6, new int[]{1}, 12));
        var file = parse(x3f.write());
        assertArrayEquals(new double[]{4095, 4095, 4095}, file.sensor.white);
        assertEquals(12, file.sensor.bitsPerSample);
    }

    @Test
    @DisplayName("finds the end of a source whose size is unknown")
    void unknownSize() {
        var x3f = new X3fWriter().layers(9, 0, 3000);
        var bytes = x3f.write();
        var source = new MediaSource() {
            @Override
            public long size() {
                return -1;
            }

            @Override
            public int read(long position, byte[] buffer, int offset, int length) {
                if (position >= bytes.length) {
                    return -1;
                }
                var count = (int) Math.min(length, bytes.length - position);
                System.arraycopy(bytes, (int) position, buffer, offset, count);
                return count;
            }

            @Override
            public void close() {
            }
        };
        assertArrayEquals(interleaved(x3f.layers, 0), decode(RawParser.parse(source, MediaLimits.DEFAULT)));
    }

    // OpenCV's bicubic weights (a = -0.75) of the four samples around a fraction
    private static double[] cubic(double fraction) {
        var a = -0.75;
        var x = fraction;
        var w0 = ((a * (x + 1) - 5 * a) * (x + 1) + 8 * a) * (x + 1) - 4 * a;
        var w1 = ((a + 2) * x - (a + 3)) * x * x + 1;
        var w2 = ((a + 2) * (1 - x) - (a + 3)) * (1 - x) * (1 - x) + 1;
        return new double[]{w0, w1, w2, 1 - w0 - w1 - w2};
    }

    @ParameterizedTest(name = "format {0}")
    @CsvSource({"35", "37", "39"})
    @DisplayName("decodes Quattro layers of different sizes and expands the lower ones by the bicubic interpolation of "
            + "their differences from the top layer")
    void quattroLayers(int format) {
        var x3f = quattro();
        x3f.format = format;
        var file = parse(x3f.write());
        var sensor = file.sensor;
        assertEquals(x3f.width, sensor.width);
        assertEquals(x3f.height, sensor.height);
        assertArrayEquals(new int[]{3, 2, x3f.height - 1, x3f.width - 2}, sensor.activeArea);
        var samples = decode(file);
        // the lower layers' shield row reads 7 and 8, the top layer's two rows 9
        assertArrayEquals(new double[]{7, 8, 9}, sensor.black, 1e-9);
        var lowWidth = x3f.width / 2;
        var lowHeight = x3f.height / 2;
        var topWidth = x3f.columns()[2];
        var gain = new double[]{1.5, 1.02, 0.8 * 1.04};
        var scale = new double[3];
        for (var layer = 0; layer < 3; layer++) {
            scale[layer] = gain[layer] / (sensor.white[layer] - sensor.black[layer]);
        }
        var top = x3f.layers[2];
        var differences = new double[2][lowWidth * lowHeight];
        for (var row = 0; row < lowHeight; row++) {
            for (var column = 0; column < lowWidth; column++) {
                var sum = top[2 * row * topWidth + 2 * column] + top[2 * row * topWidth + 2 * column + 1]
                        + top[(2 * row + 1) * topWidth + 2 * column] + top[(2 * row + 1) * topWidth + 2 * column + 1];
                var binned = (sum + 2) >> 2;
                for (var layer = 0; layer < 2; layer++) {
                    var value = x3f.layers[layer][row * x3f.columns()[layer] + column];
                    differences[layer][row * lowWidth + column] = scale[layer] * (value - sensor.black[layer])
                            - scale[2] * (binned - sensor.black[2]);
                }
            }
        }
        var worst = 0L;
        for (var y = 0; y < x3f.height; y++) {
            var fy = (y + 0.5) / 2 - 0.5;
            var sy = (int) Math.floor(fy);
            var wy = cubic(fy - sy);
            for (var x = 0; x < x3f.width; x++) {
                var fx = (x + 0.5) / 2 - 0.5;
                var sx = (int) Math.floor(fx);
                var wx = cubic(fx - sx);
                var t = top[y * topWidth + x];
                assertEquals(t, samples[(y * x3f.width + x) * 3 + 2]);
                for (var layer = 0; layer < 2; layer++) {
                    var up = 0.0;
                    for (var i = 0; i < 4; i++) {
                        var row = Math.clamp(sy - 1 + i, 0, lowHeight - 1);
                        for (var j = 0; j < 4; j++) {
                            var column = Math.clamp(sx - 1 + j, 0, lowWidth - 1);
                            up += wy[i] * wx[j] * differences[layer][row * lowWidth + column];
                        }
                    }
                    var expected = Math.clamp(Math.round(sensor.black[layer]
                            + (scale[2] * (t - sensor.black[2]) + up) / scale[layer]), 0, 65535);
                    var actual = samples[(y * x3f.width + x) * 3 + layer] & 0xFFFF;
                    worst = Math.max(worst, Math.abs(actual - expected));
                }
            }
        }
        assertTrue(worst <= 1, "largest difference " + worst);
    }

    @Test
    @DisplayName("expands flat Quattro layers to flat layers")
    void quattroFlat() {
        var x3f = quattro();
        for (var layer = 0; layer < 3; layer++) {
            Arrays.fill(x3f.layers[layer], 1000 + 100 * layer);
        }
        x3f.defaultEntries();
        var file = parse(x3f.write());
        var samples = decode(file);
        for (var index = 0; index < samples.length; index += 3) {
            assertEquals(1000, samples[index]);
            assertEquals(1100, samples[index + 1]);
            assertEquals(1200, samples[index + 2]);
        }
    }

    // the stored samples with a pixel's layers set to the rounded mean of a list of neighbors
    private static void interpolate(short[] samples, int width, int x, int y, int... neighbors) {
        for (var layer = 0; layer < 3; layer++) {
            var sum = 0;
            for (var at = 0; at < neighbors.length; at += 2) {
                sum += samples[((y + neighbors[at + 1]) * width + x + neighbors[at]) * 3 + layer];
            }
            var count = neighbors.length / 2;
            samples[(y * width + x) * 3 + layer] = (short) ((sum + count / 2) / count);
        }
    }

    @Test
    @DisplayName("interpolates over the listed bad pixels in passes, corners last, only when corrections apply")
    void badPixels() {
        var x3f = new X3fWriter().layers(20, 0, 3000);
        x3f.defaultEntries();
        int[][] bad = {{10, 10}, {20, 12}, {21, 12}, {30, 20}, {7, 5}, {25, 15}, {39, 0}};
        for (var pixel : bad) {
            for (var layer = 0; layer < 3; layer++) {
                x3f.layers[layer][pixel[1] * x3f.width + pixel[0]] = 4000;
            }
        }
        x3f.entries.add(matrix("BadPixels", 2, new int[]{3}, (10 << 8) | (10 << 20), (39 << 8), (500 << 8) | (3 << 20)));
        x3f.entries.add(matrix("BadPixelsF20", 6, new int[]{3, 2}, 12, 20, 0, 12, 21, 0));
        x3f.entries.add(matrix("HighlightPixelsInfo", 6, new int[]{2, 2}, 30, 20, 100, 100));
        x3f.entries.add(matrix("BadPixelsChromaF23", 6, new int[]{4}, 5, 7, 99, 0));
        x3f.entries.add(matrix("Jpeg_BadClusters", 6, new int[]{3, 1}, 15, 25, 0));
        var file = parse(x3f.write());
        var stored = decode(file, false);
        var fixed = decode(file, true);
        var expected = stored.clone();
        var width = x3f.width;
        interpolate(expected, width, 10, 10, -1, 0, 1, 0, 0, -1, 0, 1);
        interpolate(expected, width, 20, 12, 0, -1, 0, 1);
        interpolate(expected, width, 21, 12, 0, -1, 0, 1);
        interpolate(expected, width, 30, 20, -1, 0, 1, 0, 0, -1, 0, 1);
        interpolate(expected, width, 7, 5, -1, 0, 1, 0, 0, -1, 0, 1);
        interpolate(expected, width, 25, 15, -1, 0, 1, 0, 0, -1, 0, 1);
        interpolate(expected, width, 39, 0, -1, 0, 0, 1);
        assertArrayEquals(expected, fixed);
        for (var pixel : bad) {
            assertEquals(4000, stored[(pixel[1] * width + pixel[0]) * 3]);
        }
        var on = RawDecoder.decodeRaw(MediaSource.of(x3f.write()));
        var off = RawDecoder.decodeRaw(MediaSource.of(x3f.write()), RawDecoderOptions.builder().opcodes(false).build());
        var differing = 0;
        for (var index = 0; index < on.samples().length; index++) {
            if (on.samples()[index] != off.samples()[index]) {
                differing++;
            }
        }
        // the pixels inside the active area: all but the corner one
        assertEquals(6 * 3, differing);
    }

    @ParameterizedTest(name = "camera {0}")
    @CsvSource({"40, 108, 232, 1656", "41, 116, 296, 1944"})
    @DisplayName("interpolates over the autofocus photosites of the sd Quattro and sd Quattro H")
    void autofocusPixels(int camera, int firstColumn, int firstRow, int lastRow) {
        var x3f = new X3fWriter().layers(30, 0, 3000);
        x3f.camfType = 5;
        x3f.defaultEntries();
        x3f.entries.add(matrix("CAMERAID", 6, new int[]{1}, camera));
        var bytes = x3f.write();
        var section = indexOf(bytes, new byte[]{'S', 'E', 'C', 'c', 0, 0, 2, 0, 5, 0, 0, 0});
        var header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        var camf = X3fCamf.decode(5, new long[]{header.getInt(section + 12), header.getInt(section + 16), 0, 0},
                Arrays.copyOfRange(bytes, section + 28, section + 28 + 32 + header.getInt(section + 28 + 28)));
        var width = 2830;
        var height = 1950;
        var samples = new short[width * height * 3];
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                for (var layer = 0; layer < 3; layer++) {
                    samples[(y * width + x) * 3 + layer] = (short) (x + y + layer);
                }
            }
        }
        // the lower layers' grid: every 8th column from the first to 2820, every 16th row
        for (var y = firstRow; y <= lastRow; y += 16) {
            for (var x = firstColumn; x <= 2820; x += 8) {
                Arrays.fill(samples, (y * width + x) * 3, (y * width + x) * 3 + 3, (short) 9999);
            }
        }
        // a photosite off the grid, not next to it, keeps its value
        Arrays.fill(samples, (firstRow * width + firstColumn + 3) * 3, (firstRow * width + firstColumn + 4) * 3,
                (short) 9999);
        X3fCorrections.fixBadPixels(camf, samples, width, height, 3, 3);
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                var expected = y == firstRow && x == firstColumn + 3 ? 9999 : x + y;
                assertEquals(expected, samples[(y * width + x) * 3], x + "," + y);
            }
        }
    }

    @ParameterizedTest(name = "camera {0}")
    @CsvSource({"40, 217, 5641, 464, 3312", "41, 233, 6425, 592, 3888"})
    @DisplayName("interpolates over the two-row autofocus photosites of the sd Quattro and sd Quattro H top layers")
    void autofocusTopPixels(int camera, int firstColumn, int lastColumn, int firstRow, int lastRow) {
        var x3f = new X3fWriter().layers(31, 0, 3000);
        x3f.camfType = 5;
        x3f.defaultEntries();
        x3f.entries.add(matrix("CAMERAID", 6, new int[]{1}, camera));
        var bytes = x3f.write();
        var section = indexOf(bytes, new byte[]{'S', 'E', 'C', 'c', 0, 0, 2, 0, 5, 0, 0, 0});
        var header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        var camf = X3fCamf.decode(5, new long[]{header.getInt(section + 12), header.getInt(section + 16), 0, 0},
                Arrays.copyOfRange(bytes, section + 28, section + 28 + 32 + header.getInt(section + 28 + 28)));
        assertNotNull(camf);
        var width = lastColumn + 9;
        var height = lastRow + 8;
        var samples = new short[width * height];
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                samples[y * width + x] = (short) (x + y);
            }
        }
        // the top layer's grid: every 16th column, two rows every 32nd row
        for (var y = firstRow; y <= lastRow; y += 32) {
            for (var x = firstColumn; x <= lastColumn; x += 16) {
                samples[y * width + x] = 9999;
                samples[(y + 1) * width + x] = 9999;
            }
        }
        X3fCorrections.fixBadPixels(camf, samples, width, height, 1, 1);
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                assertEquals(x + y, samples[y * width + x], x + "," + y);
            }
        }
    }

    @Test
    @DisplayName("fixes a Quattro file's bad pixels after binning its top layer, then expands the fixed layers")
    void quattroBadPixels() {
        var x3f = quattro();
        x3f.defaultEntries();
        // a top photosite (column 9, row 6) and a lower one (column 5, row 4)
        x3f.entries.add(matrix("BadPixelsLumaF23", 6, new int[]{4}, 6, 9, 1, 0));
        x3f.entries.add(matrix("BadPixelsChromaF23", 6, new int[]{4}, 4, 5, 1, 0));
        var columns = x3f.columns();
        var topWidth = columns[2];
        x3f.layers[2][6 * topWidth + 9] = 3900;
        x3f.layers[0][4 * columns[0] + 5] = 3900;
        x3f.layers[1][4 * columns[1] + 5] = 3900;
        var file = parse(x3f.write());
        var stored = decode(file, false);
        var fixed = decode(file, true);
        var sensor = file.sensor;
        var lowWidth = x3f.width / 2;
        var lowHeight = x3f.height / 2;
        var top = new short[topWidth * x3f.height];
        for (var index = 0; index < top.length; index++) {
            top[index] = (short) x3f.layers[2][index];
        }
        var low = new short[lowWidth * lowHeight * 3];
        for (var row = 0; row < lowHeight; row++) {
            for (var column = 0; column < lowWidth; column++) {
                var at = (row * lowWidth + column) * 3;
                low[at] = (short) x3f.layers[0][row * columns[0] + column];
                low[at + 1] = (short) x3f.layers[1][row * columns[1] + column];
                var sum = top[2 * row * topWidth + 2 * column] + top[2 * row * topWidth + 2 * column + 1]
                        + top[(2 * row + 1) * topWidth + 2 * column] + top[(2 * row + 1) * topWidth + 2 * column + 1];
                low[at + 2] = (short) ((sum + 2) >> 2);
            }
        }
        // the binned value of the block holding the bad top photosite, as it would be with that photosite fixed first
        var early = low.clone();
        var center = 6 * topWidth + 9;
        top[center] = (short) ((top[center - 1] + top[center + 1] + top[center - topWidth] + top[center + topWidth] + 2) / 4);
        var block = (3 * lowWidth + 4) * 3 + 2;
        early[block] = (short) ((top[6 * topWidth + 8] + top[center] + top[7 * topWidth + 8] + top[7 * topWidth + 9] + 2)
                >> 2);
        var pixel = (4 * lowWidth + 5) * 3;
        for (var image : new short[][]{low, early}) {
            for (var layer = 0; layer < 3; layer++) {
                var sum = image[pixel - 3 + layer] + image[pixel + 3 + layer] + image[pixel - 3 * lowWidth + layer]
                        + image[pixel + 3 * lowWidth + layer];
                image[pixel + layer] = (short) ((sum + 2) / 4);
            }
        }
        // the gains are the products of the float32 AutoWBGain and TempGainFact entries
        double[] gain = {(double) 1.5f * (double) 1.0f, (double) 1.0f * (double) 1.02f, (double) 0.8f * (double) 1.04f};
        var scale = new double[3];
        for (var layer = 0; layer < 3; layer++) {
            scale[layer] = gain[layer] / (sensor.white[layer] - sensor.black[layer]);
        }
        var expected = new short[x3f.width * x3f.height * 3];
        X3fQuattro.expand(low, lowWidth, lowHeight, top, topWidth, sensor.black, scale, expected);
        assertArrayEquals(expected, fixed);
        var other = new short[expected.length];
        X3fQuattro.expand(early, lowWidth, lowHeight, top, topWidth, sensor.black, scale, other);
        assertFalse(Arrays.equals(other, fixed), "the top layer is binned before its bad photosites are fixed");
        assertEquals(3900, stored[(6 * x3f.width + 9) * 3 + 2]);
        assertEquals(top[center], fixed[(6 * x3f.width + 9) * 3 + 2]);
    }

    // the X3F tools library's spatial gain at a pixel of an image, from a table over the whole image
    private static double gainAt(double[] table, int rows, int columns, int channels, int channel, int row, int column,
                                 int height, int width) {
        var rc = (double) row / height * (rows - 1);
        var ri = (int) Math.floor(rc);
        var rf = rc - ri;
        var cc = (double) column / width * (columns - 1);
        var ci = (int) Math.floor(cc);
        var cf = cc - ci;
        var r1 = Math.min(ri, rows - 1);
        var r2 = ri >= rows - 1 ? rows - 1 : ri + 1;
        var c1 = Math.min(ci, columns - 1);
        var c2 = ci >= columns - 1 ? columns - 1 : ci + 1;
        if (ri >= rows - 1) {
            rf = 0;
        }
        var g1 = table[(r1 * columns + c1) * channels + channel] + cf * (table[(r1 * columns + c2) * channels + channel]
                - table[(r1 * columns + c1) * channels + channel]);
        var g2 = table[(r2 * columns + c1) * channels + channel] + cf * (table[(r2 * columns + c2) * channels + channel]
                - table[(r2 * columns + c1) * channels + channel]);
        return g1 + rf * (g2 - g1);
    }

    // the gain each linear sample of an active area of a flat file received, from two extractions
    private static void assertGains(X3fWriter x3f, double[] table, int rows, int columns, int channels,
                                    int firstChannel) {
        var bytes = x3f.write();
        var on = RawDecoder.decodeRaw(MediaSource.of(bytes));
        var off = RawDecoder.decodeRaw(MediaSource.of(bytes), RawDecoderOptions.builder().opcodes(false).build());
        var worst = 0.0;
        for (var y = 0; y < on.height(); y++) {
            for (var x = 0; x < on.width(); x++) {
                for (var channel = firstChannel; channel < firstChannel + channels; channel++) {
                    var index = (y * on.width() + x) * 3 + channel;
                    var ratio = (double) (on.samples()[index] & 0xFFFF) / (off.samples()[index] & 0xFFFF);
                    var expected = gainAt(table, rows, columns, channels, channel - firstChannel, y + 3, x + 2,
                            x3f.height, x3f.width);
                    worst = Math.max(worst, Math.abs(ratio - expected));
                }
            }
        }
        assertTrue(worst < 2e-4, "largest gain error " + worst);
    }

    private static X3fWriter flat() {
        var x3f = new X3fWriter().layers(21, 0, 3000);
        for (var layer = 0; layer < 3; layer++) {
            for (var index = 2 * x3f.width; index < x3f.layers[layer].length; index++) {
                x3f.layers[layer][index] = 1600;
            }
        }
        x3f.defaultEntries();
        return x3f;
    }

    @Test
    @DisplayName("applies an older camera's spatial gain table, the white balance setting's before the general one")
    void classicGain() {
        var table = new double[3 * 4 * 3];
        var other = new double[table.length];
        for (var index = 0; index < table.length; index++) {
            table[index] = 0.8 + 0.013 * index;
            other[index] = 1.3 - 0.01 * index;
        }
        var general = flat();
        general.entries.add(floats("SpatialGain", new int[]{3, 4, 3}, table));
        assertGains(general, table, 3, 4, 3, 0);
        var named = flat();
        named.entries.add(floats("SpatialGain", new int[]{3, 4, 3}, table));
        named.entries.add(propertyList("SpatialGainTables", "Auto", "AutoGain"));
        named.entries.add(floats("AutoGain", new int[]{3, 4, 3}, other));
        assertGains(named, other, 3, 4, 3, 0);
    }

    @Test
    @DisplayName("interpolates a Merrill camera's spatial gain tables bilinearly in aperture and lens position")
    void merrillGain() {
        var x3f = flat();
        x3f.entries.add(floats("CaptureAperture", new int[]{1}, 4.0));
        x3f.entries.add(floats("ObjectDistance", new int[]{1}, 100));
        x3f.entries.add(floats("SpatialGain_Fstop", new int[]{2}, 2.8, 5.6));
        String[] blocks = {"SpatialGainsProps_0_INF", "SpatialGainsProps_1_INF", "SpatialGainsProps_0_MOD",
                "SpatialGainsProps_1_MOD"};
        var include = new ArrayList<String>();
        var tables = new double[4][3][];
        double[][] minimum = {{0.9, 0.95, 1.0}, {1.1, 1.0, 0.98}, {0.85, 0.9, 1.05}, {1.2, 1.1, 1.0}};
        double[][] delta = {{0.002, 0.001, 0.002}, {0.001, 0.002, 0.001}, {0.002, 0.002, 0.002}, {0.001, 0.002, 0.001}};
        String[] channels = {"R", "G", "B"};
        for (var block = 0; block < 4; block++) {
            include.add(blocks[block]);
            include.add("1");
            var pairs = new ArrayList<String>();
            for (var channel = 0; channel < 3; channel++) {
                var name = "Table" + block + channels[channel];
                tables[block][channel] = new double[2 * 3];
                for (var index = 0; index < 6; index++) {
                    tables[block][channel][index] = (block * 37 + channel * 11 + index * 29) % 200;
                }
                x3f.entries.add(matrix(name, 5, new int[]{2, 3}, tables[block][channel]));
                pairs.addAll(List.of("GainsTable" + channels[channel], name, "MinGains" + channels[channel],
                        Double.toString(minimum[block][channel]), "Delta" + channels[channel],
                        Double.toString(delta[block][channel])));
            }
            x3f.entries.add(propertyList(blocks[block], pairs.toArray(String[]::new)));
        }
        x3f.entries.add(propertyList("IncludeBlocks", include.toArray(String[]::new)));
        // aperture 4 lies between the stops 2.8 and 5.6 in reciprocal aperture, the lens position of a 16.6 mm lens
        // focused at 1 m between those of infinity and the 280 mm minimum distance
        var x = 1 / 4.0;
        var xLow = 1 / 5.6;
        var xHigh = 1 / 2.8;
        var lens = (java.util.function.DoubleUnaryOperator) distance -> 1 / (1 / 16.6 - 1 / distance);
        var y = lens.applyAsDouble(1000);
        var yLow = lens.applyAsDouble(Double.POSITIVE_INFINITY);
        var yHigh = lens.applyAsDouble(280);
        var tx = (x - xLow) / (xHigh - xLow);
        var ty = (y - yLow) / (yHigh - yLow);
        // block weights: index 0 is stop 2.8 (high x), index 1 stop 5.6; INF is low y, MOD high y
        double[] weights = {tx * (1 - ty), (1 - tx) * (1 - ty), tx * ty, (1 - tx) * ty};
        for (var channel = 0; channel < 3; channel++) {
            var expected = new double[6];
            for (var block = 0; block < 4; block++) {
                for (var index = 0; index < 6; index++) {
                    expected[index] += weights[block] * (minimum[block][channel]
                            + delta[block][channel] * tables[block][channel][index]);
                }
            }
            assertGains(x3f, expected, 2, 3, 1, channel);
        }
    }

    @Test
    @DisplayName("exposes the RGB thumbnails as previews in the raw coding and the format 25 JPEG as a JPEG preview")
    void thumbnails() throws Exception {
        var x3f = new X3fWriter().layers(404, 0, 3000);
        var huffman = x3f.thumbnail(11, 30, 18, 405);
        var plain = x3f.thumbnail(3, 20, 12, 406);
        x3f.quattroJpeg = true;
        var bytes = x3f.write();
        var info = RawDecoder.info(MediaSource.of(bytes));
        assertEquals(List.of(new RawPreview(64, 40, info.previews().getFirst().length(), ImageFormat.JPEG),
                        new RawPreview(48, 32, info.previews().get(1).length(), ImageFormat.JPEG),
                        new RawPreview(30, 18, info.previews().get(2).length(), ImageFormat.RAW),
                        new RawPreview(20, 12, info.previews().get(3).length(), ImageFormat.RAW)),
                info.previews());
        var file = parse(bytes);
        for (var preview : file.previews) {
            if (preview.pixels() == null) {
                continue;
            }
            var picture = ImageOps.toRgba(RawDecoding.previewPicture(file, preview,
                    ImageDecoderOptions.builder().build()));
            var expected = preview.width() == 30 ? huffman : plain;
            var pixels = picture.pixels();
            for (var index = 0; index < expected.length / 3; index++) {
                for (var channel = 0; channel < 3; channel++) {
                    assertEquals(expected[3 * index + channel], pixels[4 * index + channel]);
                }
                assertEquals((byte) 0xFF, pixels[4 * index + 3]);
            }
        }
        // the largest JPEG is the one extracted, and a hint picks the smallest preview at least as large
        assertEquals(64, JpegDecoder.decode(MediaSource.of(RawDecoder.extractPreview(MediaSource.of(bytes))
                .orElseThrow()), ImageDecoderOptions.builder().build()).picture().width());
        var hinted = RawDecoder.decode(MediaSource.of(bytes), RawDecoderOptions.builder().minSize(25, 15).build());
        assertEquals(30, hinted.picture().width());
        var reduced = RawDecoder.decode(MediaSource.of(bytes), RawDecoderOptions.builder().minSize(10, 6).build());
        assertEquals(10, reduced.picture().width());
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({
            // SHA-256 of the RGB values the X3F tools library decodes from the thumbnails of valid-thumbnails.x3f
            "11, d15365598ebfb8c8b1010e11ec4bd99be4a88c912a1b61bed40f15dca588a3e6",
            "3, 1f72d04a814ad2eba16d6d8a56bfd54360688a5aab84c89bbc8c1d818191459e"
    })
    @DisplayName("decodes the RGB thumbnails exactly as the X3F tools library does")
    void thumbnailReference(int format, String sha256) throws Exception {
        var file = parse(thumbnailFile().write());
        var preview = file.previews.stream().filter(candidate -> candidate.pixels() != null
                && candidate.width() == (format == 11 ? 30 : 20)).findFirst().orElseThrow();
        assertEquals(sha256, sha256(preview.pixels().read(MediaLimits.DEFAULT)));
    }

    @ParameterizedTest(name = "type {0} format {1} row size {2}")
    @CsvSource({"1, 41, 0", "1, 31, 0", "3, 7, 0", "3, 99, 0", "4, 30, 0"})
    @DisplayName("rejects the raw image types and formats it does not know as unsupported, keeping the previews")
    void unsupported(int type, int format, int rowSize) {
        var x3f = new X3fWriter();
        x3f.layers(10, 0, 450);
        var bytes = x3f.write();
        // the TRUE body under another type and format
        var raw = indexOf(bytes, new byte[]{'S', 'E', 'C', 'i', 0, 0, 2, 0, 3, 0, 0, 0, 30});
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(raw + 8, type).putInt(raw + 12, format)
                .putInt(raw + 24, rowSize);
        var info = RawDecoder.info(MediaSource.of(bytes));
        assertEquals(RawFormat.X3F, info.format());
        assertFalse(info.isDecodable());
        assertEquals("SIGMA TEST", info.model().orElseThrow());
        assertThrows(MediaException.Unsupported.class, () -> RawDecoder.decode(MediaSource.of(bytes)));
        assertThrows(MediaException.Unsupported.class, () -> RawDecoder.decodeRaw(MediaSource.of(bytes)));
        assertTrue(RawDecoder.extractPreview(MediaSource.of(bytes)).isPresent());
        var hinted = RawDecoder.decode(MediaSource.of(bytes), RawDecoderOptions.builder().minSize(40, 20).build());
        assertEquals(48, hinted.picture().width());
    }

    @Test
    @DisplayName("rejects a damaged directory, code table, stream or layer size as malformed")
    void malformed() {
        var x3f = new X3fWriter().layers(11, 0, 3000);
        var good = x3f.write();
        var pointer = good.clone();
        ByteBuffer.wrap(pointer).order(ByteOrder.LITTLE_ENDIAN).putInt(good.length - 4, good.length);
        assertThrows(MediaException.Malformed.class, () -> parse(pointer));
        var directory = ByteBuffer.wrap(good).order(ByteOrder.LITTLE_ENDIAN).getInt(good.length - 4);
        var identifier = good.clone();
        identifier[directory] = 'X';
        assertThrows(MediaException.Malformed.class, () -> parse(identifier));
        var count = good.clone();
        ByteBuffer.wrap(count).order(ByteOrder.LITTLE_ENDIAN).putInt(directory + 8, 100000);
        assertThrows(MediaException.Malformed.class, () -> parse(count));
        assertThrows(MediaException.Malformed.class, () -> parse(Arrays.copyOf(good, 30)));
        // the stream of the top layer declared shorter than it is runs past its end
        var shortfall = new X3fWriter().layers(11, 0, 3000);
        shortfall.planeShortfall = 40;
        assertThrows(MediaException.Malformed.class, () -> decode(parse(shortfall.write())));
        // a code table that assigns the same code twice
        var table = good.clone();
        var raw = indexOf(good, new byte[]{'S', 'E', 'C', 'i', 0, 0, 2, 0, 3, 0, 0, 0, 30});
        table[raw + 28 + 8 + 2] = 3;
        table[raw + 28 + 8 + 3] = (byte) 0x80;
        assertThrows(MediaException.Malformed.class, () -> decode(parse(table)));
        // a raw section without its streams
        var cut = good.clone();
        var rawSection = indexOf(cut, new byte[]{'S', 'E', 'C', 'i', 0, 0, 2, 0, 3, 0, 0, 0, 30});
        ByteBuffer.wrap(cut).order(ByteOrder.LITTLE_ENDIAN).putInt(rawSection + 16, 4000);
        assertThrows(MediaException.class, () -> decode(parse(cut)));
        // Quattro lower layers neither half nor all of the image's rows, or a top layer smaller than twice them
        var quattro = quattro().write();
        var quattroRaw = indexOf(quattro, new byte[]{'S', 'E', 'C', 'i', 0, 0, 2, 0, 1, 0, 0, 0, 35});
        var rows = quattro.clone();
        ByteBuffer.wrap(rows).order(ByteOrder.LITTLE_ENDIAN).putShort(quattroRaw + 30, (short) 5);
        assertThrows(MediaException.Malformed.class, () -> parse(rows));
        var top = quattro.clone();
        ByteBuffer.wrap(top).order(ByteOrder.LITTLE_ENDIAN).putShort(quattroRaw + 36, (short) 10);
        assertThrows(MediaException.Malformed.class, () -> parse(top));
        // uncompressed rows shorter than their pixels, or overrunning their section
        var narrow = uncompressed().write();
        var narrowRaw = indexOf(narrow, new byte[]{'S', 'E', 'C', 'i', 0, 0, 2, 0, 3, 0, 0, 0, 6});
        ByteBuffer.wrap(narrow).order(ByteOrder.LITTLE_ENDIAN).putInt(narrowRaw + 24, 100);
        assertThrows(MediaException.Malformed.class, () -> parse(narrow));
        var overrun = uncompressed().write();
        ByteBuffer.wrap(overrun).order(ByteOrder.LITTLE_ENDIAN).putInt(narrowRaw + 24, 4096);
        assertThrows(MediaException.Malformed.class, () -> parse(overrun));
        // a Huffman coded thumbnail with a code its table does not assign
        var thumbnail = thumbnailFile().write();
        var thumbnailSection = indexOf(thumbnail, new byte[]{'S', 'E', 'C', 'i', 0, 0, 2, 0, 2, 0, 0, 0, 11});
        Arrays.fill(thumbnail, thumbnailSection + 28, thumbnailSection + 28 + 1024, (byte) 0);
        var damaged = parse(thumbnail).previews.stream().filter(preview -> preview.width() == 30).findFirst()
                .orElseThrow();
        assertThrows(MediaException.Malformed.class, () -> damaged.pixels().read(MediaLimits.DEFAULT));
    }

    private static int indexOf(byte[] data, byte[] pattern) {
        outer:
        for (var index = 0; index + pattern.length <= data.length; index++) {
            for (var k = 0; k < pattern.length; k++) {
                if (data[index + k] != pattern[k]) {
                    continue outer;
                }
            }
            return index;
        }
        throw new AssertionError("pattern not found");
    }

    @Test
    @DisplayName("damaged synthetic files only raise MediaException, in bounded time")
    void fuzz() {
        var huffman = new X3fWriter();
        huffman.format = 6;
        huffman.preTrue = true;
        var four = new X3fWriter();
        four.camfType = 4;
        var corrected = flat();
        corrected.entries.add(matrix("BadPixels", 2, new int[]{2}, (10 << 8) | (10 << 20), (5 << 8) | (7 << 20)));
        corrected.entries.add(floats("SpatialGain", new int[]{2, 2, 3}, 1, 1.1, 1.2, 1.3, 1.4, 1.5, 1, 1, 1, 1, 1, 1));
        var originals = new byte[][]{new X3fWriter().layers(12, 0, 3000).write(), huffman.layers(13, -30, 450).write(),
                four.layers(14, 0, 4000).write(), quattro().write(), uncompressed().write(), thumbnailFile().write(),
                corrected.write()};
        var random = new SplittableRandom(23);
        assertTimeoutPreemptively(Duration.ofSeconds(90), () -> {
            for (var round = 0; round < 700; round++) {
                var bytes = originals[round % originals.length].clone();
                if (round % 3 == 0) {
                    bytes = Arrays.copyOf(bytes, 8 + random.nextInt(bytes.length - 8));
                } else {
                    for (var count = 0; count < 1 + random.nextInt(6); count++) {
                        bytes[random.nextInt(bytes.length)] = (byte) random.nextInt(256);
                    }
                }
                var input = bytes;
                var limits = MediaLimits.builder().maxPixels(1 << 20).build();
                try {
                    var info = RawDecoder.info(MediaSource.of(input), limits);
                    RawDecoder.extractPreview(MediaSource.of(input), limits);
                    RawDecoder.decodeRaw(MediaSource.of(input), RawDecoderOptions.builder().limits(limits).build());
                    if (!info.previews().isEmpty()) {
                        RawDecoder.decode(MediaSource.of(input), RawDecoderOptions.builder().limits(limits)
                                .minSize(1, 1).build());
                    }
                } catch (MediaException expected) {
                    // a damaged file may be rejected
                }
            }
        });
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({
            // SHA-256 of the decoded layers (interleaved bottom, middle, top, little-endian 16-bit; the Huffman
            // values plus 1024), as the X3F tools library decodes them
            "X3F_DP1, 8cfbb29b663d0342c30e2fa56f578d223bbae175a050d3c8cd75f03ffa4c8c5a",
            "X3F_SD14, 21dcb08975397ae5b6c3a2dd1dac7e275dba2ca666aa9afe85b301aaefbe47d1",
            "X3F_SD15, 086e8cd5377a6bd95888cea49585b4724541c98ab38522b8795e8121b4b1e14e",
            "X3F_SD1_MERRILL, c6547387c357f478f12d1a28034d089884b57a4f24eddee32b3dea13f6079869",
            "X3F_DP2_MERRILL, f4164d231603fe19dc13d41ea81659e34151649d7906938fc769c2f358381e3b"
    })
    @DisplayName("decodes the layers of the sample files exactly as the X3F tools library does")
    void samples(RawSamples sample, String sha256) throws Exception {
        try (var source = MediaSource.of(sample.fetch())) {
            assertEquals(sha256, sha256(decode(RawParser.parse(source, MediaLimits.DEFAULT))));
        }
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({
            "X3F_DP1, SIGMA DP1, 2640, 1760, 2688, 1792, 400, 2010:02:01 08:27:32, 2, 12",
            "X3F_SD14, SIGMA SD14, 2640, 1760, 2688, 1792, 100, -, 2, 12",
            "X3F_SD15, SIGMA SD15, 2640, 1760, 2688, 1792, 50, -, 2, 12",
            "X3F_SD1_MERRILL, SIGMA SD1 Merrill, 4704, 3136, 4928, 3264, 100, -, 2, 12",
            "X3F_DP2_MERRILL, SIGMA DP2 Merrill, 4704, 3136, 4928, 3264, 100, -, 2, 12",
            "X3F_DP2_QUATTRO, SIGMA dp2 Quattro, 5424, 3616, 5888, 3672, 100, -, 2, 14"
    })
    @DisplayName("describes the sample files: the camera, the image area, the levels and the previews")
    void sampleInfo(RawSamples sample, String model, int width, int height, int sensorWidth, int sensorHeight,
                    long iso, String time, int previews, int bits) {
        try (var source = MediaSource.of(sample.fetch())) {
            var info = RawDecoder.info(source);
            assertEquals(RawFormat.X3F, info.format());
            assertEquals("SIGMA", info.make().orElseThrow());
            assertEquals(model, info.model().orElseThrow());
            assertEquals(width, info.width());
            assertEquals(height, info.height());
            assertEquals(sensorWidth, info.sensorWidth());
            assertEquals(sensorHeight, info.sensorHeight());
            assertEquals(iso, info.iso().orElse(0));
            assertEquals(bits, info.bitsPerSample());
            assertTrue(info.isDecodable());
            assertTrue(info.isColorCalibrated());
            if (!time.equals("-")) {
                assertEquals(time, info.captureTimeText().orElseThrow());
            }
            assertEquals(previews, info.previews().size());
            assertEquals(width, info.previews().getFirst().width());
            assertEquals(height, info.previews().getFirst().height());
            var file = RawParser.parse(source, MediaLimits.DEFAULT);
            decode(file);
            var bias = sample == RawSamples.X3F_SD14 ? X3fDecoder.HUFFMAN_BIAS
                    : sample == RawSamples.X3F_DP2_QUATTRO ? 2048 : 0;
            for (var layer = 0; layer < 3; layer++) {
                var black = file.sensor.black[layer] - bias;
                assertTrue(black > -2 && black < 40, "black level " + black);
                assertTrue(file.sensor.white[layer] > 2000, "white level " + file.sensor.white[layer]);
            }
            assertNotNull(file.sensor.opcodes2, "the lens shading");
        }
    }

    @Test
    @DisplayName("reads the SD14's uncompressed RGB thumbnail, which shows the scene of its JPEG preview")
    void sd14Thumbnail() {
        try (var source = MediaSource.of(RawSamples.X3F_SD14.fetch())) {
            var file = RawParser.parse(source, MediaLimits.DEFAULT);
            var thumbnail = file.previews.stream().filter(preview -> preview.pixels() != null).findFirst()
                    .orElseThrow();
            assertEquals(221, thumbnail.width());
            assertEquals(147, thumbnail.height());
            var ours = ImageOps.toRgba(RawDecoding.previewPicture(file, thumbnail, ImageDecoderOptions.builder().build()));
            var jpeg = file.previews.stream().filter(preview -> preview.pixels() == null).findFirst().orElseThrow();
            var reference = ImageOps.toRgba(ImageOps.resize(RawDecoding.previewPicture(file, jpeg,
                            ImageDecoderOptions.builder().build()), 221, 147,
                    com.github.auties00.smalt.image.ops.ResizeFilter.AREA));
            var error = 0.0;
            var count = 0;
            for (var y = 0; y < 147; y++) {
                for (var x = 0; x < 221; x++) {
                    for (var channel = 0; channel < 3; channel++) {
                        var a = ours.pixels()[y * ours.stride() + 4 * x + channel] & 0xFF;
                        var b = reference.pixels()[y * reference.stride() + 4 * x + channel] & 0xFF;
                        error += (a - b) * (a - b);
                        count++;
                    }
                }
            }
            // the camera renders the two differently: the thumbnail matches the scene, not the pixels
            var psnr = 10 * Math.log10(255.0 * 255 / (error / count));
            assertTrue(psnr > 15, "PSNR " + psnr);
        }
    }

    @Test
    @EnabledIfSystemProperty(named = "smalt.fuzz.writeSeeds", matches = "true",
            disabledReason = "[opt-in:fuzz-seeds] runs only with -Dsmalt.fuzz.writeSeeds=true")
    @DisplayName("writes the seeds of the X3F fuzz target")
    void fuzzSeeds() {
        var truePlanes = new X3fWriter();
        truePlanes.layers(330, 0, 4000);
        FuzzSeeds.write(FUZZ_TARGET, "x3f", "valid-true.x3f", truePlanes.write());
        var huffman = new X3fWriter();
        huffman.format = 6;
        huffman.layers(306, -60, 450);
        FuzzSeeds.write(FUZZ_TARGET, "x3f", "valid-huffman.x3f", huffman.write());
        FuzzSeeds.write(FUZZ_TARGET, "x3f", "valid-uncompressed.x3f", uncompressed().write());
        FuzzSeeds.write(FUZZ_TARGET, "x3f", "valid-x530.x3f", x530().write());
        FuzzSeeds.write(FUZZ_TARGET, "x3f", "valid-x530-uncompressed.x3f", x530Uncompressed().write());
        FuzzSeeds.write(FUZZ_TARGET, "x3f", "valid-thumbnails.x3f", thumbnailFile().write());
        var quattro = quattro();
        quattro.defaultEntries();
        quattro.entries.add(matrix("BadPixelsLumaF23", 6, new int[]{4}, 6, 9, 1, 0));
        quattro.entries.add(matrix("BadPixelsChromaF23", 6, new int[]{4}, 4, 5, 1, 0));
        quattro.entries.add(floats("SpatialGain", new int[]{2, 2, 3}, 1, 1.1, 1.2, 1.3, 1.4, 1.5, 1, 1, 1, 1, 1, 1));
        quattro.quattroJpeg = true;
        FuzzSeeds.write(FUZZ_TARGET, "x3f", "valid-quattro.x3f", quattro.write());
        var corrected = flat();
        corrected.entries.add(matrix("BadPixels", 2, new int[]{2}, (10 << 8) | (10 << 20), (5 << 8) | (7 << 20)));
        corrected.entries.add(matrix("HighlightPixelsInfo", 6, new int[]{2, 2}, 3, 4, 9, 7));
        corrected.entries.add(floats("SpatialGain", new int[]{2, 2, 3}, 1, 1.1, 1.2, 1.3, 1.4, 1.5, 1, 1, 1, 1, 1, 1));
        FuzzSeeds.write(FUZZ_TARGET, "x3f", "valid-corrected.x3f", corrected.write());
    }
}
