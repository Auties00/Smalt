package com.github.auties00.smalt.image.raw.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.raw.CfaPattern;
import com.github.auties00.smalt.image.raw.RawFormat;
import com.github.auties00.smalt.image.raw.VendorTiffWriter;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.fuzz.FuzzSeeds;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the variable-length coding of the Sony DSLR-A100 to A390 on hand-written bits and synthetic files; the samples
 * and the converter's values are in {@code SonyDeltaOracleTest}.
 */
@DisplayName("Sony variable-length coding")
class SonyDeltaCodingTest {
    private static final String FUZZ_TARGET = "com.github.auties00.smalt.image.raw.fuzz.RawFuzzTest";

    private static short[] decode(byte[] stream, int width, int height, int streamWidth) {
        var plane = new SensorPlane(width, height, 1, false, MediaLimits.DEFAULT);
        SonyDeltaCoding.decode(stream, stream.length, streamWidth, plane);
        return plane.integers;
    }

    private static short[] random(int width, int height, long seed) {
        var random = new SplittableRandom(seed);
        var image = new short[width * height];
        for (var index = 0; index < image.length; index++) {
            image[index] = (short) (random.nextInt(4) == 0 ? random.nextInt(4096) : 2000 + random.nextInt(9));
        }
        return image;
    }

    private static byte[] file(String model, int width, int height, byte[] strip) {
        var writer = new VendorTiffWriter();
        writer.make = "SONY";
        writer.model = model;
        writer.width = width;
        writer.height = height;
        writer.compression = 32767;
        writer.strip = strip;
        return writer.write();
    }

    private static RawFile parse(byte[] bytes) {
        return RawParser.parse(MediaSource.of(bytes), MediaLimits.DEFAULT);
    }

    private static short[] read(RawFile file) {
        assertNotNull(file.sensor, file.unsupported);
        var plane = SensorPlane.of(file.sensor, MediaLimits.DEFAULT);
        file.sensor.reader.read(plane, MediaLimits.DEFAULT);
        return plane.integers;
    }

    @Test
    @DisplayName("reads every category's hand-written code, sign and order: columns right to left, even rows first")
    void handWritten() {
        // a 2 by 2 image: each of the 2 columns has 10 rows, 5 even and then 5 odd
        var stream = new SonyDeltaWriter().bits("""
                00000000001 111111111111
                011
                001 0111
                0001 01111
                00001 011111
                000001 0111111
                0000001 01111111
                00000001 011111111
                000000001 0111111111
                0000000001 01111111111
                11 1
                10 01
                010 011
                001 0000
                0001 10000
                00001 011111
                """).finish();
        var running = 0;
        var all = new int[16];
        var differences = new int[]{4095, 0, -8, -16, -32, -64, -128, -256, -512, -1024, 1, -2, -4, -15, 16, -32};
        for (var index = 0; index < 16; index++) {
            running += differences[index];
            all[index] = running;
        }
        // the right column first: its row 0, row 2 and its hidden rows, then row 1 and its hidden rows
        var image = decode(stream, 2, 2, 2);
        assertEquals(all[10], image[0] & 0xFFFF, "left column, row 0");
        assertEquals(all[0], image[1] & 0xFFFF, "right column, row 0");
        assertEquals(all[15], image[2] & 0xFFFF, "left column, row 1");
        assertEquals(all[5], image[3] & 0xFFFF, "right column, row 1");
    }

    @Test
    @DisplayName("keeps one running value through colors and columns")
    void runningValue() {
        var image = new short[]{100, 200, 300, 400};
        var stream = SonyDeltaWriter.encode(image, 2, 2, 2, 100);
        assertArrayEquals(image, decode(stream, 2, 2, 2));
        // a single column: row 1, the first of the odd rows, is relative to the last hidden even row
        var explicit = new SonyDeltaWriter().difference(200).difference(-100).difference(0).difference(0).difference(0)
                .difference(300).finish();
        var column = decode(explicit, 1, 2, 1);
        assertEquals(200, column[0] & 0xFFFF);
        assertEquals(400, column[1] & 0xFFFF);
    }

    @Test
    @DisplayName("round trips a noisy image with every category, a hidden column on the right and the hidden rows")
    void roundTrip() {
        var image = random(61, 37, 1);
        assertArrayEquals(image, decode(SonyDeltaWriter.encode(image, 61, 37, 62, 4095), 61, 37, 62));
        assertArrayEquals(image, decode(SonyDeltaWriter.encode(image, 61, 37, 61, 0), 61, 37, 61));
    }

    @Test
    @DisplayName("a stream without its padding, ending at the last visible sample, decodes completely")
    void endsAtLastVisibleSample() {
        var image = random(16, 8, 2);
        var stream = SonyDeltaWriter.encode(image, 16, 8, 16, 0);
        // the leftmost column's odd hidden rows are the last four codes, three bits each, and may be dropped
        var cut = Arrays.copyOf(stream, stream.length - 1);
        assertArrayEquals(image, decode(cut, 16, 8, 16));
    }

    @Test
    @DisplayName("a truncated stream decodes to its end and leaves the rest at zero")
    void truncated() {
        var image = new short[32 * 16];
        Arrays.fill(image, (short) 1234);
        var stream = SonyDeltaWriter.encode(image, 32, 16, 32, 1234);
        var decoded = decode(Arrays.copyOf(stream, stream.length / 2), 32, 16, 32);
        var rightColumnFull = true;
        var leftColumnEmpty = true;
        for (var y = 0; y < 16; y++) {
            rightColumnFull &= decoded[y * 32 + 31] == 1234;
            leftColumnEmpty &= decoded[y * 32] == 0;
        }
        assertTrue(rightColumnFull);
        assertTrue(leftColumnEmpty);
        assertArrayEquals(new short[16], decode(new byte[0], 4, 4, 4));
    }

    @Test
    @DisplayName("a sample whose bits the stream's end cuts is not written, whatever its zero-padded value would be")
    void cutSample() {
        // the code of category 12 and four of its twelve bits: padded with zeros it would read as 3840
        var stream = new SonyDeltaWriter().bits("00000000001 1111").finish();
        assertEquals(2, stream.length);
        assertArrayEquals(new short[4], decode(stream, 2, 2, 2));
        // a complete sample before it is kept
        var kept = new SonyDeltaWriter().difference(1).bits("00000000001 1").finish();
        var image = decode(kept, 2, 2, 2);
        assertEquals(1, image[1]);
        assertEquals(0, image[0]);
    }

    @Test
    @DisplayName("a file whose stream has no bytes is refused rather than decoded to a black image")
    void noData() {
        assertThrows(MediaException.Malformed.class, () -> parse(file("DSLR-A350", 32, 16, new byte[0])));
        var outside = parse(a100File(new byte[0], new int[]{401, 256, 256, 574}));
        var plane = SensorPlane.of(outside.sensor, MediaLimits.DEFAULT);
        assertThrows(MediaException.Malformed.class, () -> outside.sensor.reader.read(plane, MediaLimits.DEFAULT));
    }

    private static byte[] typedFile(String model, int type, byte[] strip) {
        var writer = new VendorTiffWriter();
        writer.make = "SONY";
        writer.model = model;
        writer.width = 32;
        writer.height = 16;
        writer.compression = 32767;
        writer.strip = strip;
        writer.rawEntries.add(writer.shorts(0x7000, type));
        return writer.write();
    }

    @Test
    @DisplayName("a listed model's data is this coding only when the raw directory's type says so or does not say")
    void rawType() {
        var flat = new short[32 * 16];
        var stream = Arrays.copyOf(SonyDeltaWriter.encode(flat, 32, 16, 32, 0), 32 * 16);
        for (var model : List.of("DSLR-A200", "DSLR-A350", "DSLR-A390")) {
            var coded = parse(typedFile(model, 3, stream));
            assertEquals("Sony variable-length", coded.sensor.compression);
            assertArrayEquals(flat, read(coded));
            assertEquals("Sony cRAW", parse(typedFile(model, 2, new byte[32 * 16])).sensor.compression);
            var packed = parse(typedFile(model, 1, new byte[32 * 16 * 3 / 2]));
            assertEquals("uncompressed", packed.sensor.compression);
            assertEquals(12, packed.sensor.bitsPerSample);
            var other = parse(typedFile(model, 2, new byte[32 * 16 * 6 / 5]));
            assertNull(other.sensor);
            assertTrue(other.unsupported.contains("32767"), other.unsupported);
        }
        // the same type on a model outside the list is not this coding
        assertNull(parse(typedFile("DSLR-A900", 3, new byte[32 * 16 * 6 / 5])).sensor);
    }

    @Test
    @DisplayName("rejects a code of no category and samples out of range")
    void malformed() {
        var longCode = new SonyDeltaWriter().bits("000000000001 0000000000000 1111111111").finish();
        assertThrows(MediaException.Malformed.class, () -> decode(Arrays.copyOf(longCode, 64), 2, 2, 2));
        var negative = new SonyDeltaWriter().difference(-1).bits("1".repeat(64)).finish();
        assertThrows(MediaException.Malformed.class, () -> decode(negative, 2, 2, 2));
        var above = new SonyDeltaWriter().difference(4095).difference(1).bits("1".repeat(64)).finish();
        assertThrows(MediaException.Malformed.class, () -> decode(above, 2, 2, 2));
    }

    @Test
    @DisplayName("decodes the listed models' compression 32767 at any size, with 12-bit levels, whatever the size")
    void models() {
        for (var model : List.of("DSLR-A200", "DSLR-A230", "DSLR-A290", "DSLR-A300", "DSLR-A330", "DSLR-A350",
                "DSLR-A380", "DSLR-A390")) {
            var image = random(32, 16, model.hashCode());
            var file = parse(file(model, 32, 16, SonyDeltaWriter.encode(image, 32, 16, 32, 7)));
            assertEquals(RawFormat.ARW, file.format);
            assertEquals("Sony variable-length", file.sensor.compression);
            assertEquals(12, file.sensor.bitsPerSample);
            assertArrayEquals(new double[]{0}, file.sensor.black);
            assertArrayEquals(new double[]{4095}, file.sensor.white);
            assertArrayEquals(image, read(file));
        }
        // without a type tag, a stream of exactly one byte, or one and a half, per photosite is still this coding on
        // these models
        var flat = new short[32 * 16];
        var stream = Arrays.copyOf(SonyDeltaWriter.encode(flat, 32, 16, 32, 0), 32 * 16);
        assertArrayEquals(flat, read(parse(file("DSLR-A350", 32, 16, stream))));
        assertEquals("Sony cRAW", parse(file("DSLR-A450", 32, 16, stream)).sensor.compression);
    }

    @Test
    @DisplayName("reads the black levels of the encrypted directory, in red, green, green, blue order; a zero red "
            + "level makes them all zero")
    void black() {
        var cases = List.of(new int[]{4, 4, 4, 4}, new int[]{12, 4, 20, 36});
        for (var levels : cases) {
            var bytes = sr2File(220, 1654, new int[]{0x18, 0x95, 0x65, 0xf0, 0x18, 0x8f, 0x61, 0xc6}, levels);
            var file = parse(bytes);
            assertNotNull(file.sensor, file.unsupported);
            assertEquals(2, file.sensor.blackRows);
            assertEquals(2, file.sensor.blackColumns);
            assertArrayEquals(new double[]{levels[0], levels[1], levels[2], levels[3]}, file.sensor.black);
        }
        assertArrayEquals(new double[]{0},
                parse(sr2File(224, 2182, new int[]{0xc6, 0x17, 0x02, 0xcb, 0x89, 0x21, 0xbc, 0x43},
                        new int[]{0, 16, 16, 16})).sensor.black);
        assertArrayEquals(new double[]{8, 8, 8, 8},
                parse(sr2File(224, 2182, new int[]{0xc6, 0x17, 0x02, 0xcb, 0x89, 0x21, 0xbc, 0x43},
                        new int[]{8, 8, 8, 8})).sensor.black);
        // an unknown layout or a level out of range leaves the black level at zero
        assertArrayEquals(new double[]{0}, parse(sr2File(133, 2526, new int[8], new int[]{4, 4, 4, 4})).sensor.black);
        assertArrayEquals(new double[]{0},
                parse(sr2File(220, 1654, new int[]{0x18, 0x95, 0x65, 0xf0, 0x18, 0x8f, 0x61, 0xc6},
                        new int[]{5000, 5000, 5000, 5000})).sensor.black);
    }

    // an A200 file of an empty image whose SR2 block holds the levels XORed with the keystream
    private static byte[] sr2File(int first, int offset, int[] keystream, int[] levels) {
        var block = new byte[offset + 8];
        block[0] = (byte) first;
        for (var k = 0; k < 4; k++) {
            block[offset - 16 + 2 * k] = (byte) (levels[k] ^ keystream[2 * k]);
            block[offset - 16 + 2 * k + 1] = (byte) (levels[k] >> 8 ^ keystream[2 * k + 1]);
        }
        var writer = new VendorTiffWriter();
        writer.make = "SONY";
        writer.model = "DSLR-A200";
        writer.width = 16;
        writer.height = 8;
        writer.compression = 32767;
        writer.strip = SonyDeltaWriter.encode(new short[16 * 8], 16, 8, 16, 0);
        var plain = writer.write();
        var order = ByteOrder.LITTLE_ENDIAN;
        var sr2Offset = plain.length;
        var entries = new ArrayList<VendorTiffWriter.Entry>();
        entries.add(writer.longs(0x7200, 0));
        entries.add(writer.longs(0x7201, block.length));
        entries.add(VendorTiffWriter.undefined(0x7221, ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(1144201745).array()));
        var directory = VendorTiffWriter.directory(entries, sr2Offset, order);
        entries.set(0, writer.longs(0x7200, sr2Offset + directory.length));
        directory = VendorTiffWriter.directory(entries, sr2Offset, order);
        var out = new ByteArrayOutputStream();
        out.writeBytes(plain);
        out.writeBytes(directory);
        out.writeBytes(block);
        return withPrivateData(out.toByteArray(), sr2Offset);
    }

    // rewrites the first directory at the end of the file with a DNGPrivateData tag pointing to the given offset
    private static byte[] withPrivateData(byte[] file, long offset) {
        var buffer = ByteBuffer.wrap(file).order(ByteOrder.LITTLE_ENDIAN);
        var first = buffer.getInt(4);
        var count = buffer.getShort(first) & 0xFFFF;
        var out = new ByteArrayOutputStream();
        out.writeBytes(file);
        var start = file.length;
        var rewritten = ByteBuffer.allocate(2 + 12 * (count + 1) + 4).order(ByteOrder.LITTLE_ENDIAN);
        rewritten.putShort((short) (count + 1));
        for (var index = 0; index < count; index++) {
            rewritten.put(file, first + 2 + 12 * index, 12);
        }
        rewritten.putShort((short) 0xC634).putShort((short) 1).putInt(4).putInt((int) offset);
        rewritten.putInt(0);
        out.writeBytes(rewritten.array());
        var result = out.toByteArray();
        ByteBuffer.wrap(result).order(ByteOrder.LITTLE_ENDIAN).putInt(4, start);
        return result;
    }

    private static byte[] a100File(byte[] stream, int[] balance) {
        var order = ByteOrder.LITTLE_ENDIAN;
        var writer = new VendorTiffWriter();
        var mri = ByteBuffer.allocate(8 + 8 + 16 + 8 + 12).order(order);
        mri.put(new byte[]{0, 'M', 'R', 'I'}).putInt(mri.capacity() - 8);
        mri.put(new byte[]{0, 'P', 'R', 'D'}).putInt(16).put(new byte[16]);
        mri.put(new byte[]{0, 'W', 'B', 'G'}).putInt(12).put(new byte[]{2, 2, 2, 2});
        for (var level : balance) {
            mri.putShort((short) level);
        }
        var entries = new ArrayList<VendorTiffWriter.Entry>();
        entries.add(VendorTiffWriter.ascii(271, "SONY"));
        entries.add(VendorTiffWriter.ascii(272, "DSLR-A100"));
        entries.add(writer.longs(330, 0));
        entries.add(new VendorTiffWriter.Entry(0xC634, 1, 4, new byte[4]));
        var size = VendorTiffWriter.directory(entries, 8, order).length;
        var mriOffset = 8 + size;
        var data = mriOffset + mri.capacity();
        entries.set(2, writer.longs(330, data));
        entries.set(3, new VendorTiffWriter.Entry(0xC634, 1, 4, ByteBuffer.allocate(4).order(order)
                .putInt(mriOffset).array()));
        var out = new ByteArrayOutputStream();
        out.writeBytes(new byte[]{'I', 'I', 42, 0, 8, 0, 0, 0});
        out.writeBytes(VendorTiffWriter.directory(entries, 8, order));
        out.writeBytes(mri.array());
        out.writeBytes(stream);
        return out.toByteArray();
    }

    @Test
    @DisplayName("reads a DSLR-A100 file: the stream at the SubIFDs offset, 3881 columns, its image, its white balance")
    void a100() {
        var width = 3880;
        var height = 2600;
        var image = new short[width * height];
        var random = new SplittableRandom(5);
        for (var y = 0; y < height; y += 97) {
            for (var x = 0; x < width; x += 61) {
                image[y * width + x] = (short) random.nextInt(4096);
            }
        }
        var bytes = a100File(SonyDeltaWriter.encode(image, width, height, 3881, 0), new int[]{401, 256, 256, 574});
        var file = parse(bytes);
        assertEquals(RawFormat.ARW, file.format);
        assertEquals("DSLR-A100", file.model);
        assertEquals("Sony variable-length", file.sensor.compression);
        assertEquals(CfaPattern.bayer("GRBG"), file.sensor.cfa);
        assertArrayEquals(new double[]{0}, file.sensor.black);
        assertArrayEquals(new double[]{4095}, file.sensor.white);
        assertArrayEquals(new double[]{256 / 401.0, 1, 256 / 574.0}, file.color.asShotNeutral, 1e-12);
        assertFalse(file.sensor.estimateBalance);
        assertArrayEquals(image, read(file));
    }

    @Test
    @DisplayName("a DSLR-A100 file without its white balance block has it estimated, and one without SubIFDs is refused")
    void a100Damaged() {
        var bytes = a100File(new byte[256], new int[]{401, 256, 256, 574});
        // renames the WBG block
        var at = 0;
        while (bytes[at] != 'W' || bytes[at + 1] != 'B') {
            at++;
        }
        bytes[at] = 'X';
        var file = parse(bytes);
        assertNull(file.color.asShotNeutral);
        assertTrue(file.sensor.estimateBalance);
        var writer = new VendorTiffWriter();
        writer.make = "SONY";
        writer.model = "DSLR-A100";
        var entries = new ArrayList<VendorTiffWriter.Entry>();
        entries.add(VendorTiffWriter.ascii(271, "SONY"));
        entries.add(VendorTiffWriter.ascii(272, "DSLR-A100"));
        var out = new ByteArrayOutputStream();
        out.writeBytes(new byte[]{'I', 'I', 42, 0, 8, 0, 0, 0});
        out.writeBytes(VendorTiffWriter.directory(entries, 8, ByteOrder.LITTLE_ENDIAN));
        var refused = parse(out.toByteArray());
        assertNull(refused.sensor);
        assertNotNull(refused.unsupported);
    }

    @Test
    @EnabledIfSystemProperty(named = "smalt.fuzz.writeSeeds", matches = "true",
            disabledReason = "[opt-in:fuzz-seeds] runs only with -Dsmalt.fuzz.writeSeeds=true")
    @DisplayName("writes the seeds of the ARW fuzz target")
    void fuzzSeeds() {
        var image = random(32, 16, 11);
        FuzzSeeds.write(FUZZ_TARGET, "arw", "valid-delta-a200.arw",
                file("DSLR-A200", 32, 16, SonyDeltaWriter.encode(image, 32, 16, 32, 0)));
        FuzzSeeds.write(FUZZ_TARGET, "arw", "sr2-black-a200.arw",
                sr2File(220, 1654, new int[]{0x18, 0x95, 0x65, 0xf0, 0x18, 0x8f, 0x61, 0xc6}, new int[]{4, 4, 4, 4}));
        // the DSLR-A100's fixed size exceeds the fuzz limits: this seed covers the file's structure and white balance
        FuzzSeeds.write(FUZZ_TARGET, "arw", "a100-truncated.arw", a100File(new byte[512],
                new int[]{401, 256, 256, 574}));
    }
}
