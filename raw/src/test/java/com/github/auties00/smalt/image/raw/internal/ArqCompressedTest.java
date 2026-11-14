package com.github.auties00.smalt.image.raw.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.raw.PixelShiftFiles;
import com.github.auties00.smalt.image.raw.VendorTiffWriter;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.fuzz.FuzzSeeds;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.ByteArrayOutputStream;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks ARQ files of lossless JPEG sensor data on the layouts Adobe DNG Converter, run as a black box, reads in
 * copies of the CC0 ILCE-7RM3 ARQ file whose sensor data was replaced (one strip of four components; 512 by 512 tiles
 * of four components, or of two components at twice the width; the converter's merged values equal {@code (v - 512)
 * * 65535 / 15871} of red, the mean of the greens and blue), and the refusal of the other compressions. No camera or
 * software is known to write such a file, so these are synthetic.
 */
@DisplayName("compressed ARQ files")
class ArqCompressedTest {
    private static final String FUZZ_TARGET = "com.github.auties00.smalt.image.raw.fuzz.RawFuzzTest";
    private static final int WIDTH = 24;
    private static final int HEIGHT = 16;

    private static int[] samples(long seed) {
        var random = new SplittableRandom(seed);
        var samples = new int[WIDTH * HEIGHT * 4];
        for (var index = 0; index < samples.length; index++) {
            samples[index] = 512 + random.nextInt(15000);
        }
        return samples;
    }

    private static List<VendorTiffWriter.Entry> entries(VendorTiffWriter writer, int compression, int bits) {
        var entries = new ArrayList<VendorTiffWriter.Entry>();
        entries.add(writer.longs(254, 0));
        entries.add(writer.longs(256, WIDTH));
        entries.add(writer.longs(257, HEIGHT));
        entries.add(writer.shorts(258, bits, bits, bits, bits));
        entries.add(writer.shorts(259, compression));
        entries.add(writer.shorts(262, 32892));
        entries.add(writer.shorts(277, 4));
        entries.add(new VendorTiffWriter.Entry(50710, 1, 4, new byte[]{0, 1, 1, 2}));
        entries.add(writer.shorts(0x7310, 512, 512, 512, 512));
        entries.add(writer.shorts(0x7313, 2400, 1024, 1024, 1700));
        entries.add(writer.longs(50717, 16383));
        return entries;
    }

    // a file whose raw directory holds the given blocks, tiled when the tile width is not zero
    private static byte[] file(int compression, List<byte[]> blocks, int tileWidth, int tileHeight) {
        return file(compression, 14, blocks, tileWidth, tileHeight);
    }

    private static byte[] file(int compression, int bits, List<byte[]> blocks, int tileWidth, int tileHeight) {
        var writer = new VendorTiffWriter();
        var order = ByteOrder.LITTLE_ENDIAN;
        var first = new ArrayList<VendorTiffWriter.Entry>();
        first.add(VendorTiffWriter.ascii(271, "SONY"));
        first.add(VendorTiffWriter.ascii(272, "ILCE-7RM3"));
        first.add(writer.longs(330, 0));
        var firstLength = VendorTiffWriter.directory(first, 8, order).length;
        var rawOffset = 8L + firstLength;
        first.set(2, writer.longs(330, rawOffset));
        var offsets = new long[blocks.size()];
        var lengths = new long[blocks.size()];
        for (var index = 0; index < offsets.length; index++) {
            lengths[index] = blocks.get(index).length;
        }
        var offsetTag = tileWidth != 0 ? 324 : 273;
        var lengthTag = tileWidth != 0 ? 325 : 279;
        var raw = new ArrayList<>(entries(writer, compression, bits));
        if (tileWidth != 0) {
            raw.add(writer.longs(322, tileWidth));
            raw.add(writer.longs(323, tileHeight));
        } else {
            raw.add(writer.longs(278, HEIGHT));
        }
        raw.add(writer.longs(offsetTag, offsets));
        raw.add(writer.longs(lengthTag, lengths));
        var rawLength = VendorTiffWriter.directory(raw, rawOffset, order).length;
        var at = rawOffset + rawLength;
        for (var index = 0; index < offsets.length; index++) {
            offsets[index] = at;
            at += lengths[index];
        }
        raw.set(raw.size() - 2, writer.longs(offsetTag, offsets));
        var out = new ByteArrayOutputStream();
        out.writeBytes(new byte[]{'I', 'I', 42, 0, 8, 0, 0, 0});
        out.writeBytes(VendorTiffWriter.directory(first, 8, order));
        out.writeBytes(VendorTiffWriter.directory(raw, rawOffset, order));
        blocks.forEach(out::writeBytes);
        return out.toByteArray();
    }

    private static byte[] stripFile(int[] samples) {
        return file(7, List.of(LeafParserTest.LosslessJpegWriter.encode(samples, WIDTH, HEIGHT, 4)), 0, 0);
    }

    private static byte[] tiledFile(int[] samples, int components) {
        return tiledFile(samples, components, 8);
    }

    // tiles of a size, those past the image's right and bottom edges repeating its last pixels as the real layout does
    private static byte[] tiledFile(int[] samples, int components, int size) {
        var blocks = new ArrayList<byte[]>();
        for (var top = 0; top < HEIGHT; top += size) {
            for (var left = 0; left < WIDTH; left += size) {
                var tile = new int[size * size * 4];
                for (var y = 0; y < size; y++) {
                    for (var x = 0; x < size; x++) {
                        var from = (Math.min(top + y, HEIGHT - 1) * WIDTH + Math.min(left + x, WIDTH - 1)) * 4;
                        System.arraycopy(samples, from, tile, (y * size + x) * 4, 4);
                    }
                }
                blocks.add(LeafParserTest.LosslessJpegWriter.encode(tile, size * 4 / components, size, components));
            }
        }
        return file(7, blocks, size, size);
    }

    private static short[] decode(byte[] bytes) {
        var file = RawParser.parse(MediaSource.of(bytes), MediaLimits.DEFAULT);
        assertNotNull(file.sensor, file.unsupported);
        assertEquals("lossless JPEG", file.sensor.compression);
        assertEquals(3, file.sensor.samplesPerPixel);
        assertEquals(14, file.sensor.bitsPerSample);
        assertArrayEquals(new double[]{512, 512, 512}, file.sensor.black);
        assertArrayEquals(new double[]{16383, 16383, 16383}, file.sensor.white);
        assertNotNull(file.color.asShotNeutral);
        var plane = SensorPlane.of(file.sensor, MediaLimits.DEFAULT);
        file.sensor.reader.read(plane, MediaLimits.DEFAULT);
        return plane.integers;
    }

    private static short[] merged(int[] samples) {
        var out = new short[WIDTH * HEIGHT * 3];
        for (var index = 0; index < WIDTH * HEIGHT; index++) {
            out[index * 3] = (short) samples[index * 4];
            out[index * 3 + 1] = (short) ((samples[index * 4 + 1] + samples[index * 4 + 2] + 1) >>> 1);
            out[index * 3 + 2] = (short) samples[index * 4 + 3];
        }
        return out;
    }

    @Test
    @DisplayName("one strip of four components merges as uncompressed samples do")
    void strip() {
        var samples = samples(1);
        assertArrayEquals(merged(samples), decode(stripFile(samples)));
    }

    @Test
    @DisplayName("tiles of four components merge as uncompressed samples do")
    void tiles() {
        var samples = samples(2);
        assertArrayEquals(merged(samples), decode(tiledFile(samples, 4)));
    }

    @Test
    @DisplayName("tiles of two components at twice the width merge as uncompressed samples do")
    void tilesOfTwoComponents() {
        var samples = samples(3);
        assertArrayEquals(merged(samples), decode(tiledFile(samples, 2)));
    }

    @Test
    @DisplayName("tiles that reach past the image's right and bottom edges are cut to the image")
    void tilesPastTheEdges() {
        var samples = samples(6);
        assertArrayEquals(merged(samples), decode(tiledFile(samples, 4, 10)));
        assertArrayEquals(merged(samples), decode(tiledFile(samples, 2, 10)));
    }

    @Test
    @DisplayName("lossless JPEG sensor data of more than 16 bits is described as unsupported")
    void tooManyBits() {
        var bytes = file(7, 17, List.of(new byte[16]), 0, 0);
        var file = RawParser.parse(MediaSource.of(bytes), MediaLimits.DEFAULT);
        assertNull(file.sensor);
        assertTrue(file.unsupported.contains("lossless JPEG"), file.unsupported);
    }

    @Test
    @DisplayName("the uncompressed layout is unchanged")
    void uncompressed() {
        var files = new PixelShiftFiles(12, 8, 3);
        var file = RawParser.parse(MediaSource.of(files.arq()), MediaLimits.DEFAULT);
        assertEquals("uncompressed", file.sensor.compression);
    }

    @Test
    @DisplayName("other compressions are described as unsupported and with no sensor image")
    void otherCompressions() {
        for (var compression : new int[]{32767, 8, 34713}) {
            var bytes = file(compression, List.of(new byte[WIDTH * HEIGHT]), 0, 0);
            var file = RawParser.parse(MediaSource.of(bytes), MediaLimits.DEFAULT);
            assertNull(file.sensor);
            assertTrue(file.unsupported.contains("TIFF compression " + compression), file.unsupported);
        }
    }

    @Test
    @DisplayName("a block smaller than the image is malformed, and a damaged block does not hang")
    void damaged() {
        var small = LeafParserTest.LosslessJpegWriter.encode(new int[4], 1, 1, 4);
        var file = RawParser.parse(MediaSource.of(file(7, List.of(small), 0, 0)), MediaLimits.DEFAULT);
        var plane = SensorPlane.of(file.sensor, MediaLimits.DEFAULT);
        assertThrows(MediaException.class, () -> file.sensor.reader.read(plane, MediaLimits.DEFAULT));
        var bytes = stripFile(samples(4));
        var cut = Arrays.copyOf(bytes, bytes.length / 2);
        try {
            decode(cut);
        } catch (MediaException expected) {
            assertNotNull(expected.getMessage());
        }
    }

    @Test
    @EnabledIfSystemProperty(named = "smalt.fuzz.writeSeeds", matches = "true",
            disabledReason = "[opt-in:fuzz-seeds] runs only with -Dsmalt.fuzz.writeSeeds=true")
    @DisplayName("writes the seeds of the ARW fuzz target")
    void fuzzSeeds() {
        var samples = samples(5);
        FuzzSeeds.write(FUZZ_TARGET, "arw", "valid-arq-lossless-strip.arq", stripFile(samples));
        FuzzSeeds.write(FUZZ_TARGET, "arw", "valid-arq-lossless-tiles.arq", tiledFile(samples, 2));
    }
}
