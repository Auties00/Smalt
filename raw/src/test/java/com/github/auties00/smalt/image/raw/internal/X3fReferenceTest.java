package com.github.auties00.smalt.image.raw.internal;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.image.raw.RawDecoder;
import com.github.auties00.smalt.image.raw.RawDecoderOptions;
import com.github.auties00.smalt.image.raw.RawHighlights;
import com.github.auties00.smalt.image.raw.RawSamples;
import com.github.auties00.smalt.image.raw.RawToneCurve;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Tolerance;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.invoke.MethodHandles;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the Sigma X3F decoder against the X3F tools library (commit e8b48ca, BSD 3-Clause, built from source and
 * run on the CC0 samples; the harness and the OpenCV step of its Quattro expansion are described in the README):
 * the Quattro layers and metadata block bit for bit, the bad pixel interpolation bit for bit, the lens shading tables
 * it selects and interpolates ({@code x3f-spatial-gains.tsv}, to the precision of the 32-bit gains), and the sRGB
 * rendering with the bad pixel and lens shading corrections within one 8-bit level on average. The rendering
 * reference averages each of 36x24 cells of the active area ({@code x3f-renderings.tsv}); it takes the black level
 * from the dark shield rows only, as this decoder does, and cells holding saturated or nearly saturated photosites
 * are left out, since that library renders them without highlight handling.
 */
class X3fReferenceTest {
    /** The rendering tolerance over the cell means, in 8-bit levels. */
    private static final Tolerance RENDERING = Tolerance.meanAbs(1).and(Tolerance.maxAbs(4));

    /**
     * The linear value from which a photosite counts as saturated: that library leaves values its lens shading
     * gain lifts past white unclipped, where this decoder clips them, and the color matrices spread the difference
     * over every channel.
     */
    private static final int NEAR_WHITE = 58000;

    private static final int GRID_WIDTH = 36;
    private static final int GRID_HEIGHT = 24;

    private static String sha256(short[] samples) throws Exception {
        var bytes = ByteBuffer.allocate(samples.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        bytes.asShortBuffer().put(samples);
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.array()));
    }

    private static byte[] section(byte[] file, int typeFormat) {
        var buffer = ByteBuffer.wrap(file).order(ByteOrder.LITTLE_ENDIAN);
        var directory = buffer.getInt(file.length - 4);
        for (var index = 0; index < buffer.getInt(directory + 8); index++) {
            var offset = buffer.getInt(directory + 12 + 12 * index);
            var length = buffer.getInt(directory + 16 + 12 * index);
            if (buffer.getInt(offset) == 0x69434553 && (buffer.getInt(offset + 8) << 16 | buffer.getInt(offset + 12))
                    == typeFormat) {
                return Arrays.copyOfRange(file, offset, offset + length);
            }
            if (typeFormat == 0 && buffer.getInt(offset) == 0x63434553) {
                return Arrays.copyOfRange(file, offset, offset + length);
            }
        }
        throw new AssertionError("no section " + Integer.toHexString(typeFormat));
    }

    @Test
    @DisplayName("decodes the dp2 Quattro's layers and type 5 metadata block exactly as the X3F tools library does")
    void quattro() throws Exception {
        var file = Files.readAllBytes(RawSamples.X3F_DP2_QUATTRO.fetch());
        var raw = section(file, 0x00010023);
        var layout = X3fDecoder.trueLayout(raw, raw.length, true, 5888, 3672);
        assertEquals(Arrays.toString(new int[]{2944, 2944, 6272}), Arrays.toString(layout.columns()));
        assertEquals(Arrays.toString(new int[]{1836, 1836, 3672}), Arrays.toString(layout.rows()));
        var low = new short[2944 * 1836 * 3];
        var top = new short[6272 * 3672];
        X3fDecoder.decodeQuattro(raw, layout, low, top);
        // SHA-256 of each layer, little-endian 16-bit, as the library decodes it
        String[] expected = {"68c16416e56e099659bec8325b881ed9a33d547c4ea0d2cb027ac14b76f2b45d",
                "2fd0f8f246e32f20343452300bea43376fec042da026528e1d05152c8856a198"};
        for (var layer = 0; layer < 2; layer++) {
            var values = new short[2944 * 1836];
            for (var index = 0; index < values.length; index++) {
                values[index] = low[3 * index + layer];
            }
            assertEquals(expected[layer], sha256(values));
        }
        assertEquals("e9d8bf016dd7dcee510961708c9ffaec8c643caf04222cada73210a987642bf2", sha256(top));
        var camf = section(file, 0);
        var header = ByteBuffer.wrap(camf).order(ByteOrder.LITTLE_ENDIAN);
        var block = X3fCamf.decode(5, new long[]{header.getInt(12), header.getInt(16), header.getInt(20),
                header.getInt(24)}, Arrays.copyOfRange(camf, 28, camf.length));
        assertNotNull(block);
        var body = X3fCamf.class.getDeclaredField("body");
        body.setAccessible(true);
        assertEquals("f2985845eefc2f279977fb78268481f039c8fb24d222c695a343969eb10bd1aa",
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((byte[]) body.get(block))));
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({
            // SHA-256 of the layers after the library's interpolate_bad_pixels, little-endian 16-bit, interleaved
            // (the Huffman values plus 1024); for the Quattro the lower layers, carrying the rounded 2x2 means of
            // the top layer as their third sample, then the top layer
            "X3F_DP1, e4560391e5039ff37a063ea53afcbc2acc3e68bd3545473061525e4572ca2200",
            "X3F_SD14, 05159aa4ea02151d17a3ccb9b207d9a1b6ea45a1b630faf53696666c459b695c",
            "X3F_SD15, 8c505463d8efd3cf588a2704b2a53ed06a3a85357cc7c1120c7c12b3d7b90614",
            "X3F_SD1_MERRILL, c50e94fdc50698741d696fe444a505e8bf3df43b35f528d2b28e9694cdb0a326",
            "X3F_DP2_MERRILL, 4e665da0b1680323dc78af4046d9fed479cd56c43056f38d55f5020a940f2c25",
            "X3F_DP2_QUATTRO, 4b3ad679d91b0f89eceb2f016aec6aa3c9178612c0c8892e5c00fa84c331dad5"
    })
    @DisplayName("interpolates over the sample files' bad pixels exactly as the X3F tools library does")
    void badPixels(RawSamples sample, String sha256) throws Exception {
        var bytes = Files.readAllBytes(sample.fetch());
        var file = RawParser.parse(MediaSource.of(bytes), MediaLimits.DEFAULT);
        var camf = camf(bytes);
        if (sample != RawSamples.X3F_DP2_QUATTRO) {
            var sensor = file.sensor;
            var plane = new SensorPlane(sensor.width, sensor.height, 3, false, MediaLimits.DEFAULT);
            sensor.reader.read(plane, MediaLimits.DEFAULT, true);
            assertEquals(sha256, sha256(plane.integers));
            return;
        }
        var raw = section(bytes, 0x00010023);
        var layout = X3fDecoder.trueLayout(raw, raw.length, true, 5888, 3672);
        var low = new short[2944 * 1836 * 3];
        var top = new short[6272 * 3672];
        X3fDecoder.decodeQuattro(raw, layout, low, top);
        X3fQuattro.bin(low, 2944, 1836, top, 6272);
        X3fCorrections.fixBadPixels(camf, top, 6272, 3672, 1, 1);
        X3fCorrections.fixBadPixels(camf, low, 2944, 1836, 3, 3);
        var all = Arrays.copyOf(low, low.length + top.length);
        System.arraycopy(top, 0, all, low.length, top.length);
        assertEquals(sha256, sha256(all));
    }

    private static X3fCamf camf(byte[] file) {
        var camf = section(file, 0);
        var header = ByteBuffer.wrap(camf).order(ByteOrder.LITTLE_ENDIAN);
        return X3fCamf.decode(header.getInt(8), new long[]{Integer.toUnsignedLong(header.getInt(12)),
                Integer.toUnsignedLong(header.getInt(16)), Integer.toUnsignedLong(header.getInt(20)),
                Integer.toUnsignedLong(header.getInt(24))}, Arrays.copyOfRange(camf, 28, camf.length));
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({"X3F_DP1", "X3F_SD14", "X3F_SD15", "X3F_SD1_MERRILL", "X3F_DP2_MERRILL", "X3F_DP2_QUATTRO"})
    @DisplayName("selects and interpolates the sample files' lens shading tables as the X3F tools library does")
    void spatialGain(RawSamples sample) throws Exception {
        var name = sample.fileName().replace("rawpixls-sigma-", "").replace("sigma-", "").replace(".x3f", "");
        var expected = new ArrayList<double[]>();
        try (var in = X3fReferenceTest.class.getResourceAsStream("x3f-spatial-gains.tsv")) {
            assertNotNull(in, "x3f-spatial-gains.tsv");
            for (var line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                var fields = line.strip().split("\t");
                if (!line.startsWith("#") && fields[0].equals(name)) {
                    expected.add(Arrays.stream(fields, 1, fields.length).mapToDouble(Double::parseDouble).toArray());
                }
            }
        }
        assertFalse(expected.isEmpty(), name);
        var file = RawParser.parse(MediaSource.of(Files.readAllBytes(sample.fetch())), MediaLimits.DEFAULT);
        assertNotNull(file.sensor.opcodes2, "the lens shading");
        var list = ByteBuffer.wrap(file.sensor.opcodes2);
        assertEquals(expected.size(), list.getInt());
        for (var table : expected) {
            assertEquals(9, list.getInt());
            list.getInt();
            list.getInt();
            var end = list.getInt() + list.position();
            list.position(list.position() + 16);
            assertEquals((int) table[0], list.getInt(), "first channel");
            assertEquals((int) table[1], list.getInt(), "channels");
            list.position(list.position() + 8);
            assertEquals((int) table[2], list.getInt(), "rows");
            assertEquals((int) table[3], list.getInt(), "columns");
            list.position(list.position() + 32);
            assertEquals((int) table[1], list.getInt(), "map planes");
            var gains = new double[(int) table[4]];
            var sum = 0.0;
            for (var index = 0; index < gains.length; index++) {
                gains[index] = list.getFloat();
                sum += gains[index];
            }
            assertEquals(end, list.position());
            // the opcode stores the gains as 32-bit floats
            assertEquals(table[5], Arrays.stream(gains).min().orElseThrow(), 1e-6);
            assertEquals(table[6], Arrays.stream(gains).max().orElseThrow(), 1e-6);
            assertEquals(table[7], sum, table[7] * 1e-6);
            for (var step = 0; step < 9; step++) {
                assertEquals(table[8 + step], gains[(int) ((long) step * (gains.length - 1) / 8)], 1e-6, "step " + step);
            }
        }
        assertEquals(list.capacity(), list.position());
    }

    // the reference cell means of a sample, in 8-bit levels, per channel
    private static double[][] reference(String name) {
        try (var in = X3fReferenceTest.class.getResourceAsStream("x3f-renderings.tsv")) {
            assertNotNull(in, "x3f-renderings.tsv");
            var means = new double[3][];
            for (var line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                var fields = line.split("\t");
                if (!line.startsWith("#") && fields[0].equals(name)) {
                    means[Integer.parseInt(fields[1])] = Arrays.stream(fields[2].split(","))
                            .mapToDouble(value -> Integer.parseInt(value) / 100.0).toArray();
                }
            }
            return means;
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({"X3F_DP1", "X3F_SD14", "X3F_SD15", "X3F_SD1_MERRILL", "X3F_DP2_MERRILL", "X3F_DP2_QUATTRO"})
    @DisplayName("renders the sample files, with their bad pixels and lens shading corrected, as the X3F tools "
            + "library does")
    void rendering(RawSamples sample) throws Exception {
        var bytes = Files.readAllBytes(sample.fetch());
        var exposure = RawParser.parse(MediaSource.of(bytes), MediaLimits.DEFAULT).color.baselineExposure;
        var options = RawDecoderOptions.builder().toneCurve(RawToneCurve.LINEAR).highlights(RawHighlights.CLIP)
                .exposure(exposure).highBitDepth(true).build();
        var picture = ImageOps.toRgba16(RawDecoder.decode(MediaSource.of(bytes), options).picture());
        var linear = RawDecoder.decodeRaw(MediaSource.of(bytes));
        var width = picture.width();
        var height = picture.height();
        assertEquals(width, linear.width());
        var sums = new double[3][GRID_WIDTH * GRID_HEIGHT];
        var counts = new int[GRID_WIDTH * GRID_HEIGHT];
        var saturated = new boolean[GRID_WIDTH * GRID_HEIGHT];
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                var cell = y * GRID_HEIGHT / height * GRID_WIDTH + x * GRID_WIDTH / width;
                counts[cell]++;
                for (var channel = 0; channel < 3; channel++) {
                    sums[channel][cell] += (picture.pixels()[y * picture.stride() + 4 * x + channel] & 0xFFFF) / 257.0;
                    if ((linear.samples()[(y * width + x) * 3 + channel] & 0xFFFF) >= NEAR_WHITE) {
                        saturated[cell] = true;
                    }
                }
            }
        }
        var reference = reference(sample.fileName().replace("rawpixls-sigma-", "").replace(".x3f", ""));
        var used = 0;
        for (var channel = 0; channel < 3; channel++) {
            var accumulator = new Tolerance.Accumulator(255, 255);
            used = 0;
            for (var cell = 0; cell < counts.length; cell++) {
                if (!saturated[cell]) {
                    accumulator.add(cell, reference[channel][cell], sums[channel][cell] / counts[cell]);
                    used++;
                }
            }
            RENDERING.check(sample + " channel " + channel, accumulator.result());
        }
        assertTrue(used > counts.length * 0.8, used + " cells without saturated photosites");
    }
}
