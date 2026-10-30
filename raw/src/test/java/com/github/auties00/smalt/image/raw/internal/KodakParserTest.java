package com.github.auties00.smalt.image.raw.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.raw.RawDecoder;
import com.github.auties00.smalt.image.raw.RawDecoderOptions;
import com.github.auties00.smalt.image.raw.RawFormat;
import com.github.auties00.smalt.image.raw.VendorTiffWriter;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.fuzz.FuzzSeeds;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.ByteArrayOutputStream;
import java.nio.ByteOrder;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the Kodak parser on synthetic files built from the layouts the CC0 samples show: the DCS files' raw
 * directory, gray response curve and Kodak directory (image area, black levels, neutral levels), the groups of
 * Kodak's compression 65000 with and without their table of starts, and the KDC files' Kodak directory with
 * 12-bit sensor data after metadata padded with {@code 0xFF} bytes or right before the JPEG
 * preview. The CC0 samples themselves are checked against Adobe DNG Converter in {@link VendorOracleTest}.
 */
class KodakParserTest {
    private static final String FUZZ_TARGET = "com.github.auties00.smalt.image.raw.fuzz.RawFuzzTest";

    private static final ByteOrder ORDER = ByteOrder.LITTLE_ENDIAN;

    private static final VendorTiffWriter ENTRIES = new VendorTiffWriter();

    static int[] photosites(int width, int height, long seed) {
        var random = new SplittableRandom(seed);
        var photosites = new int[width * height];
        for (var index = 0; index < photosites.length; index++) {
            photosites[index] = random.nextInt(4) == 0 ? random.nextInt(4096) : 600 + random.nextInt(-50, 50);
        }
        return photosites;
    }

    // packs 12-bit photosites two in three bytes, most significant bit first
    static byte[] pack(int[] photosites) {
        var data = new byte[photosites.length / 2 * 3];
        for (var index = 0; index < photosites.length; index += 2) {
            var a = photosites[index];
            var b = photosites[index + 1];
            var at = index / 2 * 3;
            data[at] = (byte) (a >> 4);
            data[at + 1] = (byte) ((a & 0xF) << 4 | b >> 8);
            data[at + 2] = (byte) b;
        }
        return data;
    }

    /**
     * A synthetic KDC file of the EasyShare and P series.
     */
    static final class Kdc {
        int width = 24;
        int height = 8;
        String filter = "RGGB";
        String model = "KODAK Z123 DIGITAL CAMERA";
        boolean padded = true;
        int balanceMode = 1;
        int[] photosites;

        Kdc photosites(long seed) {
            photosites = KodakParserTest.photosites(width, height, seed);
            return this;
        }

        byte[] write() {
            var preview = new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xD9};
            var kodak = new ArrayList<VendorTiffWriter.Entry>();
            kodak.add(ENTRIES.shorts(0xFA10, balanceMode));
            kodak.add(ENTRIES.longs(0xFA12, 70000, 65536, 120000));
            kodak.add(ENTRIES.shorts(0xFA13, width));
            kodak.add(ENTRIES.shorts(0xFA14, height));
            kodak.add(VendorTiffWriter.ascii(0xFA15, filter));
            kodak.add(ENTRIES.longs(0xFA25, 80000, 65536, 130000));
            kodak.add(ENTRIES.shorts(0xFA31, width - 8));
            kodak.add(ENTRIES.shorts(0xFA32, height - 3));
            var first = new ArrayList<VendorTiffWriter.Entry>();
            first.add(ENTRIES.longs(254, 0));
            first.add(VendorTiffWriter.ascii(271, "EASTMAN KODAK COMPANY"));
            first.add(VendorTiffWriter.ascii(272, model));
            first.add(ENTRIES.longs(513, 0));
            first.add(ENTRIES.longs(514, preview.length));
            first.add(ENTRIES.longs(KodakParser.KDC_IFD, 0));
            var firstSize = VendorTiffWriter.directory(first, 8, ORDER).length;
            var kodakOffset = 8 + firstSize + (firstSize & 1);
            var kodakSize = VendorTiffWriter.directory(kodak, kodakOffset, ORDER).length;
            var metadataEnd = kodakOffset + kodakSize;
            var data = pack(photosites);
            // without padding, a few bytes of no directory lie between the metadata and the sensor data
            var dataOffset = padded ? (metadataEnd / 4096 + 1) * 4096 : metadataEnd + 16;
            var previewOffset = dataOffset + data.length + (padded ? 100 : 0);
            first.set(3, ENTRIES.longs(513, previewOffset));
            first.set(5, ENTRIES.longs(KodakParser.KDC_IFD, kodakOffset));
            var out = new ByteArrayOutputStream();
            out.writeBytes(new byte[]{'I', 'I', 42, 0, 8, 0, 0, 0});
            out.writeBytes(VendorTiffWriter.directory(first, 8, ORDER));
            while (out.size() < kodakOffset) {
                out.write(0);
            }
            out.writeBytes(VendorTiffWriter.directory(kodak, kodakOffset, ORDER));
            while (out.size() < dataOffset) {
                out.write(padded ? 0xFF : 0x5A);
            }
            out.writeBytes(data);
            while (out.size() < previewOffset) {
                out.write(0);
            }
            out.writeBytes(preview);
            return out.toByteArray();
        }
    }

    /**
     * A synthetic DCS file with an uncompressed raw directory.
     */
    static final class Dcs {
        int width = 20;
        int height = 6;
        int compression = 1;
        boolean groupTable = true;
        int[] photosites;
        int stripLength = -1;

        Dcs photosites(long seed) {
            photosites = KodakParserTest.photosites(width, height, seed);
            return this;
        }

        byte[] write() {
            var groups = new ArrayList<Long>();
            var strip = compression == KodakParser.COMPRESSION_KODAK ? encode(photosites, width, height, groups)
                    : pack(photosites);
            if (stripLength >= 0) {
                strip = Arrays.copyOf(strip, stripLength);
            }
            var curve = new int[4096];
            for (var index = 0; index < curve.length; index++) {
                curve[index] = (int) Math.round(4095 * Math.pow(index / 4095.0, 1.2));
            }
            var columns = new long[width];
            Arrays.fill(columns, -100);
            columns[3] = -101;
            var rows = new long[height];
            rows[1] = -1;
            var kodak = new ArrayList<VendorTiffWriter.Entry>();
            kodak.add(ENTRIES.shorts(1003, 4));
            kodak.add(ENTRIES.shorts(1004, 2));
            kodak.add(ENTRIES.shorts(1005, width - 8));
            kodak.add(ENTRIES.shorts(1006, height - 4));
            kodak.add(sshorts(1039, columns));
            kodak.add(sshorts(1040, rows));
            kodak.add(ENTRIES.longs(2120, 1900, 1800, 900));
            var raw = new ArrayList<VendorTiffWriter.Entry>();
            raw.add(ENTRIES.longs(254, 0));
            raw.add(ENTRIES.longs(256, width));
            raw.add(ENTRIES.longs(257, height));
            raw.add(ENTRIES.shorts(258, 12));
            raw.add(ENTRIES.shorts(259, compression));
            raw.add(ENTRIES.shorts(262, 32803));
            raw.add(ENTRIES.longs(273, 0));
            raw.add(ENTRIES.shorts(277, 1));
            raw.add(ENTRIES.longs(278, height));
            raw.add(ENTRIES.longs(279, strip.length));
            raw.add(ENTRIES.shorts(291, curve));
            raw.add(ENTRIES.shorts(33421, 2, 2));
            raw.add(new VendorTiffWriter.Entry(33422, 1, 4, new byte[]{1, 0, 2, 1}));
            if (compression == KodakParser.COMPRESSION_KODAK && groupTable) {
                raw.add(ENTRIES.longs(KodakDecoder.GROUP_OFFSETS,
                        groups.stream().mapToLong(Long::longValue).toArray()));
            }
            var first = new ArrayList<VendorTiffWriter.Entry>();
            first.add(ENTRIES.longs(254, 1));
            first.add(VendorTiffWriter.ascii(271, "Kodak"));
            first.add(VendorTiffWriter.ascii(272, "DCS123C"));
            first.add(ENTRIES.longs(330, 0));
            first.add(ENTRIES.longs(KodakParser.KODAK_IFD, 0));
            var firstSize = VendorTiffWriter.directory(first, 8, ORDER).length;
            var rawOffset = 8 + firstSize + (firstSize & 1);
            var rawSize = VendorTiffWriter.directory(raw, rawOffset, ORDER).length;
            var kodakOffset = rawOffset + rawSize + (rawSize & 1);
            var kodakSize = VendorTiffWriter.directory(kodak, kodakOffset, ORDER).length;
            var stripOffset = kodakOffset + kodakSize + (kodakSize & 1);
            first.set(3, ENTRIES.longs(330, rawOffset));
            first.set(4, ENTRIES.longs(KodakParser.KODAK_IFD, kodakOffset));
            raw.set(6, ENTRIES.longs(273, stripOffset));
            var out = new ByteArrayOutputStream();
            out.writeBytes(new byte[]{'I', 'I', 42, 0, 8, 0, 0, 0});
            for (var part : List.of(VendorTiffWriter.directory(first, 8, ORDER),
                    VendorTiffWriter.directory(raw, rawOffset, ORDER), VendorTiffWriter.directory(kodak, kodakOffset,
                            ORDER))) {
                while (out.size() % 2 != 0) {
                    out.write(0);
                }
                out.writeBytes(part);
            }
            while (out.size() < stripOffset) {
                out.write(0);
            }
            out.writeBytes(strip);
            return out.toByteArray();
        }

        // codes photosites in Kodak's compression 65000: groups of up to 256 photosites, their 4-bit difference
        // lengths, then the differences least significant bit first in big-endian 16-bit words, each group padded
        // to 4 bytes; the start of every group but the first goes to groups
        static byte[] encode(int[] photosites, int width, int height, List<Long> groups) {
            var out = new ByteArrayOutputStream();
            for (var y = 0; y < height; y++) {
                for (var x = 0; x < width; x += KodakDecoder.GROUP) {
                    if (out.size() > 0) {
                        groups.add((long) out.size());
                    }
                    var count = Math.min(KodakDecoder.GROUP, width - x);
                    var differences = new int[count];
                    var lengths = new int[count];
                    for (var index = 0; index < count; index++) {
                        var value = photosites[y * width + x + index];
                        differences[index] = index < 2 ? value : value - photosites[y * width + x + index - 2];
                        lengths[index] = 32 - Integer.numberOfLeadingZeros(Math.abs(differences[index]));
                    }
                    for (var index = 0; index < count; index += 2) {
                        out.write(lengths[index] | (index + 1 < count ? lengths[index + 1] << 4 : 0));
                    }
                    var buffer = 0L;
                    var bits = 0;
                    for (var index = 0; index < count; index++) {
                        var difference = differences[index];
                        var size = lengths[index];
                        var code = difference < 0 ? difference + (1 << size) - 1 : difference;
                        buffer |= (long) code << bits;
                        bits += size;
                        while (bits >= 16) {
                            out.write((int) (buffer >>> 8) & 0xFF);
                            out.write((int) buffer & 0xFF);
                            buffer >>>= 16;
                            bits -= 16;
                        }
                    }
                    if (bits > 0) {
                        out.write((int) (buffer >>> 8) & 0xFF);
                        out.write((int) buffer & 0xFF);
                    }
                    while (out.size() % 4 != 0) {
                        out.write(0);
                    }
                }
            }
            return out.toByteArray();
        }

        static VendorTiffWriter.Entry sshorts(int tag, long... values) {
            var shorts = ENTRIES.shorts(tag, Arrays.stream(values).mapToInt(value -> (int) value).toArray());
            return new VendorTiffWriter.Entry(tag, 8, shorts.count(), shorts.value());
        }
    }

    private static RawFile parse(byte[] data) {
        return RawParser.parse(MediaSource.of(data), MediaLimits.DEFAULT);
    }

    private static int[] read(RawFile file) {
        assertNotNull(file.sensor, file.unsupported);
        var plane = SensorPlane.of(file.sensor, MediaLimits.DEFAULT);
        file.sensor.reader.read(plane, MediaLimits.DEFAULT);
        var out = new int[plane.width * plane.height];
        for (var index = 0; index < out.length; index++) {
            out[index] = plane.integers[index] & 0xFFFF;
        }
        return out;
    }

    @ParameterizedTest(name = "padded {0}")
    @CsvSource({"true", "false"})
    @DisplayName("a KDC file's 12-bit sensor data, after padding or before the preview, decodes to its photosites, "
            + "with the Kodak directory's filter array, centered crop and automatic white balance")
    void kdc(boolean padded) {
        var synthetic = new Kdc().photosites(padded ? 1 : 2);
        synthetic.padded = padded;
        var file = parse(synthetic.write());
        assertEquals(RawFormat.DCR, file.format);
        assertArrayEquals(synthetic.photosites, read(file));
        assertEquals("RGGB", file.sensor.cfa.toString());
        assertArrayEquals(new double[]{4, 1, 16, 5}, file.sensor.defaultCrop);
        assertArrayEquals(new double[]{4095}, file.sensor.white);
        assertArrayEquals(new double[]{65536 / 80000.0, 1, 65536 / 130000.0}, file.color.asShotNeutral, 1e-12);
    }

    @Test
    @DisplayName("a KDC file shot under another white balance mode takes the multipliers of tag 0xFA12")
    void presetBalance() {
        var synthetic = new Kdc().photosites(3);
        synthetic.balanceMode = 11;
        var file = parse(synthetic.write());
        assertArrayEquals(new double[]{65536 / 70000.0, 1, 65536 / 120000.0}, file.color.asShotNeutral, 1e-12);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({"KODAK EasyShare Z981 Digital Camera, Z981, GRBG, 4095",
            "KODAK EasyShare Z990 Digital Camera, Z990, BGGR, 4000",
            "KODAK P712 ZOOM DIGITAL CAMERA, P712, RGGB, 4030", "KODAK P880 ZOOM DIGITAL CAMERA, P880, RGGB, 4095"})
    @DisplayName("the models whose filter array or white level differ from the file's take the converter's")
    void models(String model, String name, String filter, int white) {
        assertEquals(name, KodakParser.model(model));
        var synthetic = new Kdc().photosites(4);
        synthetic.model = model;
        var file = parse(synthetic.write());
        assertEquals(filter, file.sensor.cfa.toString());
        assertArrayEquals(new double[]{white}, file.sensor.white);
    }

    @Test
    @DisplayName("a KDC file whose filter array is not one red, two greens and one blue is described and not decoded")
    void badFilter() {
        var synthetic = new Kdc().photosites(5);
        synthetic.filter = "RGBX";
        var file = parse(synthetic.write());
        assertNull(file.sensor);
        assertNotNull(file.unsupported);
    }

    @Test
    @DisplayName("a DCS file decodes to its photosites with the gray response curve as linearization table, the "
            + "Kodak directory's crop, negated black levels and neutral")
    void dcs() {
        var synthetic = new Dcs().photosites(6);
        var file = parse(synthetic.write());
        assertEquals(RawFormat.DCR, file.format);
        assertArrayEquals(synthetic.photosites, read(file));
        assertEquals("GRBG", file.sensor.cfa.toString());
        assertEquals(4096, file.sensor.linearization.length);
        assertEquals(4095, file.sensor.linearization[4095]);
        assertEquals((int) Math.round(4095 * Math.pow(1000 / 4095.0, 1.2)), file.sensor.linearization[1000]);
        assertArrayEquals(new double[]{4, 2, 12, 2}, file.sensor.defaultCrop);
        var columns = new double[synthetic.width];
        Arrays.fill(columns, 100);
        columns[3] = 101;
        assertArrayEquals(columns, file.sensor.blackDeltaH);
        var rows = new double[synthetic.height];
        rows[1] = 1;
        assertArrayEquals(rows, file.sensor.blackDeltaV);
        assertArrayEquals(new double[]{1900 / 1800.0, 1, 900 / 1800.0}, file.color.asShotNeutral, 1e-12);
    }

    @ParameterizedTest(name = "group table {0}")
    @CsvSource({"true", "false"})
    @DisplayName("a DCS file of compression 65000 decodes to its photosites, its rows in parallel from the group table "
            + "or in order without it")
    void compressed(boolean groupTable) {
        var synthetic = new Dcs();
        synthetic.width = 600;
        synthetic.height = 5;
        synthetic.compression = KodakParser.COMPRESSION_KODAK;
        synthetic.groupTable = groupTable;
        synthetic.photosites(10);
        var file = parse(synthetic.write());
        assertEquals("Kodak 65000", file.sensor.compression);
        assertArrayEquals(synthetic.photosites, read(file));
    }

    @Test
    @DisplayName("a truncated strip of compression 65000 decodes its missing photosites from zero bits")
    void compressedTruncated() {
        var synthetic = new Dcs();
        synthetic.width = 600;
        synthetic.compression = KodakParser.COMPRESSION_KODAK;
        synthetic.groupTable = false;
        synthetic.photosites(11);
        var full = synthetic.write();
        var file = parse(Arrays.copyOf(full, full.length - 200));
        var photosites = read(file);
        assertEquals(synthetic.photosites[0], photosites[0]);
    }

    @Test
    @DisplayName("a DCS file whose uncompressed strip is short is described and not decoded")
    void shortStrip() {
        var synthetic = new Dcs().photosites(7);
        synthetic.stripLength = 40;
        var file = parse(synthetic.write());
        assertEquals(RawFormat.DCR, file.format);
        assertNull(file.sensor);
        assertTrue(file.unsupported.contains("smaller"), file.unsupported);
        assertNotNull(file.color.asShotNeutral);
    }

    // an EOS DCS file: a first directory with the gray response curve, a thumbnail child and the 8-bit raw child
    static byte[] eosDcs(int[] photosites, int width, int height) {
        var curve = new int[256];
        for (var index = 0; index < curve.length; index++) {
            curve[index] = index * index / 8;
        }
        var raw = new ArrayList<VendorTiffWriter.Entry>();
        raw.add(ENTRIES.longs(254, 0));
        raw.add(ENTRIES.longs(256, width));
        raw.add(ENTRIES.longs(257, height));
        raw.add(ENTRIES.shorts(258, 8));
        raw.add(ENTRIES.shorts(262, 1));
        raw.add(ENTRIES.longs(273, 0));
        raw.add(ENTRIES.longs(279, (long) width * height));
        var thumbnail = new ArrayList<VendorTiffWriter.Entry>();
        thumbnail.add(ENTRIES.longs(254, 1));
        thumbnail.add(ENTRIES.longs(256, 4));
        thumbnail.add(ENTRIES.longs(257, 4));
        thumbnail.add(ENTRIES.shorts(258, 8));
        thumbnail.add(ENTRIES.shorts(262, 1));
        thumbnail.add(ENTRIES.longs(273, 0));
        thumbnail.add(ENTRIES.longs(279, 16));
        var first = new ArrayList<VendorTiffWriter.Entry>();
        first.add(ENTRIES.longs(254, 1));
        first.add(VendorTiffWriter.ascii(271, "KODAK           "));
        first.add(VendorTiffWriter.ascii(272, "EOSDCS3C        FILE VERSION 3"));
        first.add(ENTRIES.shorts(291, curve));
        first.add(ENTRIES.longs(330, 0, 0));
        var firstSize = VendorTiffWriter.directory(first, 8, ORDER).length;
        var thumbnailOffset = 8 + firstSize + (firstSize & 1);
        var thumbnailSize = VendorTiffWriter.directory(thumbnail, thumbnailOffset, ORDER).length;
        var rawOffset = thumbnailOffset + thumbnailSize + (thumbnailSize & 1);
        var rawSize = VendorTiffWriter.directory(raw, rawOffset, ORDER).length;
        var dataOffset = rawOffset + rawSize + (rawSize & 1);
        first.set(4, ENTRIES.longs(330, thumbnailOffset, rawOffset));
        thumbnail.set(5, ENTRIES.longs(273, dataOffset));
        raw.set(5, ENTRIES.longs(273, dataOffset + 16));
        var out = new ByteArrayOutputStream();
        out.writeBytes(new byte[]{'I', 'I', 42, 0, 8, 0, 0, 0});
        for (var part : List.of(VendorTiffWriter.directory(first, 8, ORDER),
                VendorTiffWriter.directory(thumbnail, thumbnailOffset, ORDER),
                VendorTiffWriter.directory(raw, rawOffset, ORDER))) {
            while (out.size() % 2 != 0) {
                out.write(0);
            }
            out.writeBytes(part);
        }
        while (out.size() < dataOffset + 16) {
            out.write(0);
        }
        for (var value : photosites) {
            out.write(value);
        }
        return out.toByteArray();
    }

    @Test
    @DisplayName("an EOS DCS file decodes its 8-bit photosites with the gray response curve, the GRBG array, two "
            + "masked columns at each side and their mean linearized level as black level")
    void eosDcs() {
        var width = 12;
        var height = 4;
        var random = new SplittableRandom(13);
        var photosites = new int[width * height];
        var sum = 0L;
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                var masked = x < 2 || x >= width - 2;
                var value = masked ? 10 + random.nextInt(5) : random.nextInt(256);
                photosites[y * width + x] = value;
                if (masked) {
                    sum += value * value / 8;
                }
            }
        }
        var file = parse(eosDcs(photosites, width, height));
        assertEquals(RawFormat.DCR, file.format);
        assertArrayEquals(photosites, read(file));
        assertEquals("GRBG", file.sensor.cfa.toString());
        assertArrayEquals(new int[]{0, 2, height, width - 2}, file.sensor.active());
        assertEquals(255 * 255 / 8, file.sensor.linearization[255]);
        assertArrayEquals(new double[]{255 * 255 / 8}, file.sensor.white);
        assertArrayEquals(new double[]{(double) sum / (4 * height)}, file.sensor.black);
    }

    @Test
    @DisplayName("damaged synthetic files only raise MediaException, in bounded time")
    void fuzz() {
        var compressed = new Dcs();
        compressed.width = 300;
        compressed.compression = KodakParser.COMPRESSION_KODAK;
        compressed.photosites(12);
        var originals = List.of(new Kdc().photosites(8).write(), new Dcs().photosites(9).write(), compressed.write());
        var random = new SplittableRandom(25);
        assertTimeoutPreemptively(Duration.ofSeconds(60), () -> {
            for (var round = 0; round < 600; round++) {
                var bytes = originals.get(round % 3).clone();
                if (round % 3 == 0) {
                    bytes = Arrays.copyOf(bytes, 8 + random.nextInt(bytes.length - 8));
                } else {
                    for (var count = 0; count < 1 + random.nextInt(6); count++) {
                        bytes[4 + random.nextInt(bytes.length - 4)] = (byte) random.nextInt(256);
                    }
                }
                var input = bytes;
                var limits = MediaLimits.builder().maxPixels(1 << 20).build();
                try {
                    RawDecoder.info(MediaSource.of(input), limits);
                    RawDecoder.decode(MediaSource.of(input), RawDecoderOptions.builder().limits(limits).build());
                } catch (MediaException expected) {
                    // a damaged file may be rejected
                }
            }
        });
    }

    @Test
    @EnabledIfSystemProperty(named = "smalt.fuzz.writeSeeds", matches = "true",
            disabledReason = "[opt-in:fuzz-seeds] runs only with -Dsmalt.fuzz.writeSeeds=true")
    @DisplayName("writes the seeds of the Kodak fuzz target")
    void fuzzSeeds() {
        FuzzSeeds.write(FUZZ_TARGET, "dcr", "valid-easyshare.kdc", new Kdc().photosites(1).write());
        FuzzSeeds.write(FUZZ_TARGET, "dcr", "valid-dcs.dcr", new Dcs().photosites(2).write());
        var compressed = new Dcs();
        compressed.width = 300;
        compressed.compression = KodakParser.COMPRESSION_KODAK;
        FuzzSeeds.write(FUZZ_TARGET, "dcr", "valid-65000.dcr", compressed.photosites(3).write());
    }
}
