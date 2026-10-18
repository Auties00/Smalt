package com.github.auties00.smalt.image.exif;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.Orientation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers the EXIF reader on hand-built TIFF blocks in both byte orders, including hostile offsets,
 * counts and directory cycles, and the orientation rewriter.
 */
class ExifReaderTest {
    /**
     * Builds TIFF blocks: a header, IFD0 at offset 8, and optional extra directories, with values
     * that do not fit in four bytes placed after the directories.
     */
    static final class Tiff {
        private final boolean little;
        private final List<List<int[]>> directories = new ArrayList<>();
        private final List<List<byte[]>> payloads = new ArrayList<>();

        Tiff(boolean little) {
            this.little = little;
            directories.add(new ArrayList<>());
            payloads.add(new ArrayList<>());
        }

        int addDirectory() {
            directories.add(new ArrayList<>());
            payloads.add(new ArrayList<>());
            return directories.size() - 1;
        }

        Tiff entry(int directory, int tag, int type, long count, byte[] value) {
            directories.get(directory).add(new int[]{tag, type, (int) count});
            payloads.get(directory).add(value);
            return this;
        }

        Tiff shortEntry(int directory, int tag, int... values) {
            var bytes = new byte[values.length * 2];
            for (var i = 0; i < values.length; i++) {
                put16(bytes, 2 * i, values[i]);
            }
            return entry(directory, tag, 3, values.length, bytes);
        }

        Tiff longEntry(int directory, int tag, long... values) {
            var bytes = new byte[values.length * 4];
            for (var i = 0; i < values.length; i++) {
                put32(bytes, 4 * i, values[i]);
            }
            return entry(directory, tag, 4, values.length, bytes);
        }

        void put16(byte[] data, int at, int value) {
            if (little) {
                data[at] = (byte) value;
                data[at + 1] = (byte) (value >>> 8);
            } else {
                data[at] = (byte) (value >>> 8);
                data[at + 1] = (byte) value;
            }
        }

        void put32(byte[] data, int at, long value) {
            if (little) {
                put16(data, at, (int) value);
                put16(data, at + 2, (int) (value >>> 16));
            } else {
                put16(data, at, (int) (value >>> 16));
                put16(data, at + 2, (int) value);
            }
        }

        int directoryOffset(int directory) {
            var offset = 8;
            for (var d = 0; d < directory; d++) {
                offset += 2 + 12 * directories.get(d).size() + 4;
            }
            return offset;
        }

        byte[] build() {
            var directoriesEnd = directoryOffset(directories.size());
            var out = new byte[directoriesEnd + 4096];
            out[0] = (byte) (little ? 'I' : 'M');
            out[1] = out[0];
            put16(out, 2, 42);
            put32(out, 4, 8);
            var data = directoriesEnd;
            for (var d = 0; d < directories.size(); d++) {
                var at = directoryOffset(d);
                var entries = directories.get(d);
                put16(out, at, entries.size());
                for (var i = 0; i < entries.size(); i++) {
                    var entry = entries.get(i);
                    var value = payloads.get(d).get(i);
                    var e = at + 2 + 12 * i;
                    put16(out, e, entry[0]);
                    put16(out, e + 2, entry[1]);
                    put32(out, e + 4, entry[2] & 0xFFFFFFFFL);
                    if (value.length <= 4) {
                        System.arraycopy(value, 0, out, e + 8, value.length);
                    } else {
                        put32(out, e + 8, data);
                        System.arraycopy(value, 0, out, data, value.length);
                        data += (value.length + 1) & ~1;
                    }
                }
                put32(out, at + 2 + 12 * entries.size(), 0);
            }
            return java.util.Arrays.copyOf(out, data);
        }
    }

    static List<Arguments> orders() {
        return List.of(Arguments.of(true), Arguments.of(false));
    }

    @Nested
    @DisplayName("orientation")
    class OrientationTag {
        @ParameterizedTest(name = "little={0}")
        @MethodSource("com.github.auties00.smalt.image.exif.ExifReaderTest#orders")
        @DisplayName("reads all eight codes as SHORT and LONG in both byte orders")
        void allCodes(boolean little) {
            for (var orientation : Orientation.values()) {
                var asShort = new Tiff(little).shortEntry(0, 0x010F, 1).shortEntry(0, 0x0112, orientation.exifCode()).build();
                assertEquals(orientation, ExifReader.orientation(asShort));
                var asLong = new Tiff(little).longEntry(0, 0x0112, orientation.exifCode()).build();
                assertEquals(orientation, ExifReader.orientation(asLong));
            }
        }

        @Test
        @DisplayName("reads the first value of an out-of-line SHORT array")
        void outOfLine() {
            var tiff = new Tiff(false).shortEntry(0, 0x0112, 8, 1, 1).build();
            assertEquals(Orientation.ROTATE_270, ExifReader.orientation(tiff));
        }

        @ParameterizedTest(name = "code {0}")
        @ValueSource(ints = {0, 9, 255, 65535})
        @DisplayName("treats out-of-range codes as normal")
        void outOfRange(int code) {
            assertEquals(Orientation.NORMAL, ExifReader.orientation(new Tiff(true).shortEntry(0, 0x0112, code).build()));
        }

        @Test
        @DisplayName("ignores an orientation of another type and a missing entry")
        void wrongType() {
            var ascii = new Tiff(true).entry(0, 0x0112, 2, 2, new byte[]{'6', 0}).build();
            assertEquals(Orientation.NORMAL, ExifReader.orientation(ascii));
            assertEquals(Orientation.NORMAL, ExifReader.orientation(new Tiff(true).shortEntry(0, 0x0110, 6).build()));
        }

        @Test
        @DisplayName("never throws on garbage")
        void garbage() {
            assertEquals(Orientation.NORMAL, ExifReader.orientation(null));
            assertEquals(Orientation.NORMAL, ExifReader.orientation(new byte[0]));
            assertEquals(Orientation.NORMAL, ExifReader.orientation("II".getBytes()));
            assertEquals(Orientation.NORMAL, ExifReader.orientation(new byte[]{'I', 'I', 43, 0, 8, 0, 0, 0}));
            assertEquals(Orientation.NORMAL, ExifReader.orientation(new byte[]{'I', 'I', 42, 0, (byte) 0xFF, 0x7F, 0, 0}));
            assertEquals(Orientation.NORMAL, ExifReader.orientation(new byte[]{'M', 'M', 0, 42, 0, 0, 0, 4, 0, 1}));
            var truncated = new Tiff(true).shortEntry(0, 0x0112, 6).build();
            truncated[8] = (byte) 0xFF;
            truncated[9] = (byte) 0xFF;
            assertEquals(Orientation.ROTATE_90, ExifReader.orientation(truncated));
            var badPointer = new Tiff(false).entry(0, 0x0112, 3, 3, new byte[6]).build();
            badPointer[8 + 2 + 8] = 0x7F;
            assertEquals(Orientation.NORMAL, ExifReader.orientation(badPointer));
            var random = new Random(7);
            for (var i = 0; i < 20_000; i++) {
                var bytes = new byte[random.nextInt(64)];
                random.nextBytes(bytes);
                if (bytes.length >= 4 && random.nextBoolean()) {
                    bytes[0] = 'I';
                    bytes[1] = 'I';
                    bytes[2] = 42;
                    bytes[3] = 0;
                }
                assertDoesNotThrow(() -> ExifReader.orientation(bytes));
            }
        }
    }

    @Nested
    @DisplayName("parse")
    class Parse {
        @ParameterizedTest(name = "little={0}")
        @MethodSource("com.github.auties00.smalt.image.exif.ExifReaderTest#orders")
        @DisplayName("decodes every TIFF type in IFD0, the Exif IFD and the GPS IFD")
        void types(boolean little) {
            var builder = new Tiff(little);
            var exif = builder.addDirectory();
            var gps = builder.addDirectory();
            builder.entry(0, 0x010F, 2, 6, "Canon\0".getBytes())
                    .shortEntry(0, 0x0112, 6)
                    .longEntry(0, ExifData.EXIF_IFD_POINTER, 0)
                    .longEntry(0, ExifData.GPS_IFD_POINTER, 0);
            var rational = new byte[16];
            builder.put32(rational, 0, 72);
            builder.put32(rational, 4, 1);
            builder.put32(rational, 8, 1);
            builder.put32(rational, 12, 3);
            builder.entry(exif, 0x011A, 5, 2, rational);
            var srational = new byte[8];
            builder.put32(srational, 0, -5);
            builder.put32(srational, 4, 2);
            builder.entry(exif, 0x9204, 10, 1, srational);
            builder.entry(exif, 0x9000, 7, 4, "0230".getBytes());
            builder.entry(exif, 0x0001, 1, 3, new byte[]{1, (byte) 200, 3});
            builder.entry(exif, 0x0002, 6, 2, new byte[]{-1, 5});
            var sshort = new byte[2];
            builder.put16(sshort, 0, -300);
            builder.entry(exif, 0x0003, 8, 1, sshort);
            var slong = new byte[4];
            builder.put32(slong, 0, -70000);
            builder.entry(exif, 0x0004, 9, 1, slong);
            var floats = new byte[4];
            builder.put32(floats, 0, Float.floatToIntBits(1.5f));
            builder.entry(exif, 0x0005, 11, 1, floats);
            var doubles = new byte[8];
            var bits = Double.doubleToLongBits(-2.25);
            if (little) {
                builder.put32(doubles, 0, bits & 0xFFFFFFFFL);
                builder.put32(doubles, 4, bits >>> 32);
            } else {
                builder.put32(doubles, 0, bits >>> 32);
                builder.put32(doubles, 4, bits & 0xFFFFFFFFL);
            }
            builder.entry(exif, 0x0006, 12, 1, doubles);
            builder.entry(gps, 0x0001, 2, 2, "N\0".getBytes());
            var tiff = builder.build();
            builder.put32(tiff, 8 + 2 + 12 * 2 + 8, builder.directoryOffset(exif));
            builder.put32(tiff, 8 + 2 + 12 * 3 + 8, builder.directoryOffset(gps));
            var data = ExifReader.parse(tiff);
            assertEquals(Orientation.ROTATE_90, data.orientation());
            assertEquals(new ExifValue.Ascii("Canon"), data.ifd0().get(0x010F));
            assertEquals(new ExifValue.Rationals(ExifValue.Type.RATIONAL, new long[]{72, 1}, new long[]{1, 3}), data.exif().get(0x011A));
            assertEquals(new ExifValue.Rationals(ExifValue.Type.SRATIONAL, new long[]{-5}, new long[]{2}), data.exif().get(0x9204));
            assertEquals(-2.5, ((ExifValue.Rationals) data.exif().get(0x9204)).doubleValue(0));
            assertEquals(new ExifValue.Undefined("0230".getBytes()), data.exif().get(0x9000));
            assertEquals(new ExifValue.Integers(ExifValue.Type.BYTE, new long[]{1, 200, 3}), data.exif().get(0x0001));
            assertEquals(new ExifValue.Integers(ExifValue.Type.SBYTE, new long[]{-1, 5}), data.exif().get(0x0002));
            assertEquals(new ExifValue.Integers(ExifValue.Type.SSHORT, new long[]{-300}), data.exif().get(0x0003));
            assertEquals(new ExifValue.Integers(ExifValue.Type.SLONG, new long[]{-70000}), data.exif().get(0x0004));
            assertEquals(new ExifValue.Reals(ExifValue.Type.FLOAT, new double[]{1.5}), data.exif().get(0x0005));
            assertEquals(new ExifValue.Reals(ExifValue.Type.DOUBLE, new double[]{-2.25}), data.exif().get(0x0006));
            assertEquals(new ExifValue.Ascii("N"), data.gps().get(0x0001));
        }

        @Test
        @DisplayName("rejects a block without a TIFF header or with IFD0 outside the block")
        void malformed() {
            assertThrows(MediaException.Malformed.class, () -> ExifReader.parse(new byte[0]));
            assertThrows(MediaException.Malformed.class, () -> ExifReader.parse("JFIF\0\0\0\0".getBytes()));
            assertThrows(MediaException.Malformed.class, () -> ExifReader.parse(new byte[]{'I', 'I', 42, 0, 100, 0, 0, 0}));
            assertThrows(NullPointerException.class, () -> ExifReader.parse(null));
        }

        @Test
        @DisplayName("skips entries with hostile counts and offsets, and breaks directory cycles")
        void hostile() {
            var builder = new Tiff(true);
            builder.entry(0, 0x0100, 12, 0xFFFFFFFFL, new byte[4])
                    .entry(0, 0x0101, 4, 1000, new byte[4])
                    .entry(0, 0x0102, 99, 1, new byte[4])
                    .shortEntry(0, 0x0112, 3)
                    .longEntry(0, ExifData.EXIF_IFD_POINTER, 8)
                    .longEntry(0, ExifData.GPS_IFD_POINTER, 0x7FFFFFFF);
            var tiff = builder.build();
            builder.put32(tiff, 8 + 2 + 12 + 8, 0x7FFF0000L);
            var data = ExifReader.parse(tiff);
            assertFalse(data.ifd0().containsKey(0x0100));
            assertFalse(data.ifd0().containsKey(0x0101));
            assertFalse(data.ifd0().containsKey(0x0102));
            assertEquals(Orientation.ROTATE_180, data.orientation());
            assertTrue(data.exif().isEmpty());
            assertTrue(data.gps().isEmpty());
        }

        @Test
        @DisplayName("bounds the decoded size when many entries share one large value")
        void amplification() {
            var builder = new Tiff(false);
            var large = new byte[4000];
            builder.entry(0, 0x0001, 7, large.length, large);
            var tiff = builder.build();
            var bytes = new ByteArrayOutputStream();
            bytes.writeBytes(java.util.Arrays.copyOf(tiff, 8));
            var entries = 5000;
            var directory = new byte[2 + 12 * entries + 4];
            builder.put16(directory, 0, entries);
            for (var i = 0; i < entries; i++) {
                builder.put16(directory, 2 + 12 * i, i);
                builder.put16(directory, 2 + 12 * i + 2, 7);
                builder.put32(directory, 2 + 12 * i + 4, large.length);
                builder.put32(directory, 2 + 12 * i + 8, 8 + directory.length);
            }
            bytes.writeBytes(directory);
            bytes.writeBytes(large);
            var block = bytes.toByteArray();
            var data = assertTimeoutPreemptively(Duration.ofSeconds(5), () -> ExifReader.parse(block));
            var total = data.ifd0().values().stream().mapToLong(ExifValue::count).sum();
            assertTrue(total <= Math.max(1 << 16, 4L * block.length), "decoded " + total + " bytes");
        }
    }

    @Nested
    @DisplayName("withOrientation")
    class Rewrite {
        @ParameterizedTest(name = "little={0}")
        @MethodSource("com.github.auties00.smalt.image.exif.ExifReaderTest#orders")
        @DisplayName("rewrites an existing SHORT or LONG entry in place")
        void inPlace(boolean little) {
            var original = new Tiff(little).entry(0, 0x010F, 2, 6, "Nikon\0".getBytes()).shortEntry(0, 0x0112, 6).build();
            var rewritten = ExifWriter.withOrientation(original, Orientation.NORMAL);
            assertEquals(original.length, rewritten.length);
            assertEquals(Orientation.NORMAL, ExifReader.orientation(rewritten));
            assertEquals(Orientation.ROTATE_90, ExifReader.orientation(original));
            var before = ExifReader.parse(original);
            var after = ExifReader.parse(rewritten);
            assertEquals(before.ifd0().get(0x010F), after.ifd0().get(0x010F));
            var asLong = new Tiff(little).longEntry(0, 0x0112, 3).build();
            assertEquals(Orientation.TRANSVERSE, ExifReader.orientation(ExifWriter.withOrientation(asLong,
                    Orientation.TRANSVERSE)));
        }

        @Test
        @DisplayName("appends a new IFD0 when the entry is missing, keeping every other entry and pointer")
        void append() {
            var builder = new Tiff(true);
            var exif = builder.addDirectory();
            builder.entry(0, 0x010F, 2, 6, "Apple\0".getBytes())
                    .longEntry(0, ExifData.EXIF_IFD_POINTER, 0)
                    .entry(exif, 0x9003, 2, 20, "2026:09:28 10:00:00\0".getBytes());
            var original = builder.build();
            builder.put32(original, 8 + 2 + 12 + 8, builder.directoryOffset(exif));
            var rewritten = ExifWriter.withOrientation(original, Orientation.ROTATE_270);
            assertEquals(Orientation.ROTATE_270, ExifReader.orientation(rewritten));
            var data = ExifReader.parse(rewritten);
            assertEquals(new ExifValue.Ascii("Apple"), data.ifd0().get(0x010F));
            assertEquals(new ExifValue.Ascii("2026:09:28 10:00:00"), data.exif().get(0x9003));
            assertEquals(List.of(0x010F, 0x0112, ExifData.EXIF_IFD_POINTER), List.copyOf(data.ifd0().keySet()));
        }

        @Test
        @DisplayName("rewrites an entry of another type as a SHORT")
        void wrongType() {
            var original = new Tiff(false).entry(0, 0x0112, 2, 8, "rotated\0".getBytes()).build();
            assertEquals(Orientation.FLIP_VERTICAL, ExifReader.orientation(ExifWriter.withOrientation(original,
                    Orientation.FLIP_VERTICAL)));
        }

        @Test
        @DisplayName("returns a minimal block for garbage and never modifies its input")
        void garbage() {
            var garbage = "not a tiff".getBytes();
            var copy = garbage.clone();
            var block = ExifWriter.withOrientation(garbage, Orientation.ROTATE_90);
            assertArrayEquals(copy, garbage);
            assertEquals(Orientation.ROTATE_90, ExifReader.orientation(block));
            assertEquals(1, ExifReader.parse(block).ifd0().size());
        }
    }
}
