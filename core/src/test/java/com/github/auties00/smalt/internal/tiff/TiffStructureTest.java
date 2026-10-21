package com.github.auties00.smalt.internal.tiff;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.Orientation;
import com.github.auties00.smalt.io.MediaSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the shared TIFF structure reader: headers, directories under the strict policy of the
 * TIFF image reader and the lenient policy of the raw and EXIF readers, value decoding, and the
 * EXIF orientation rule. The differences between the readers the walker replaces are pinned here:
 * unknown field types, entry caps, truncated directories and out-of-range values.
 */
class TiffStructureTest {
    /** Builds a classic TIFF structure with one directory at offset 8 and its values after it. */
    private static final class Builder {
        private final boolean little;
        private final List<Object[]> entries = new ArrayList<>();
        private long next;

        Builder(boolean little) {
            this.little = little;
        }

        Builder entry(int tag, int type, long valueCount, long value) {
            entries.add(new Object[]{tag, type, valueCount, value});
            return this;
        }

        Builder entry(int tag, int type, long valueCount, byte[] outOfLine) {
            entries.add(new Object[]{tag, type, valueCount, outOfLine});
            return this;
        }

        Builder next(long next) {
            this.next = next;
            return this;
        }

        /** Builds the structure, declaring {@code declared} entries whatever the number written. */
        byte[] build(int declared) {
            var out = new ByteArrayOutputStream();
            out.write(little ? 'I' : 'M');
            out.write(little ? 'I' : 'M');
            put(out, 42, 2);
            put(out, 8, 4);
            put(out, declared, 2);
            var dataStart = 8 + 2 + 12L * entries.size() + 4;
            var data = new ByteArrayOutputStream();
            for (var entry : entries) {
                put(out, (int) entry[0], 2);
                put(out, (int) entry[1], 2);
                put(out, (long) entry[2], 4);
                if (entry[3] instanceof byte[] bytes) {
                    put(out, dataStart + data.size(), 4);
                    data.writeBytes(bytes);
                } else {
                    put(out, (long) entry[3], 4);
                }
            }
            put(out, next, 4);
            out.writeBytes(data.toByteArray());
            return out.toByteArray();
        }

        private void put(ByteArrayOutputStream out, long value, int size) {
            for (var i = 0; i < size; i++) {
                var shift = little ? 8 * i : 8 * (size - 1 - i);
                out.write((int) (value >>> shift));
            }
        }
    }

    @Nested
    @DisplayName("TiffHeader")
    class Headers {
        @Test
        @DisplayName("reads classic and BigTIFF headers and rejects other bytes")
        void headers() {
            assertEquals(new TiffHeader(true, false, 8), TiffHeader.parse(new byte[]{'I', 'I', 42, 0, 8, 0, 0, 0}, 0, 8));
            assertEquals(new TiffHeader(false, false, 16), TiffHeader.read(TiffInput.of(new byte[]{1, 'M', 'M', 0, 42, 0, 0, 0, 16}), 1));
            var big = new byte[]{'I', 'I', 43, 0, 8, 0, 0, 0, 16, 0, 0, 0, 0, 0, 0, 0};
            var header = TiffHeader.parse(big, 0, big.length);
            assertTrue(header.big());
            assertEquals(16, header.firstIfd());
            assertEquals(20, header.entrySize());
            assertEquals(8, header.fieldSize());
            assertNull(TiffHeader.parse(new byte[]{'I', 'M', 42, 0, 8, 0, 0, 0}, 0, 8));
            assertNull(TiffHeader.parse(new byte[]{'I', 'I', 41, 0, 8, 0, 0, 0}, 0, 8));
            assertNull(TiffHeader.parse(Arrays.copyOf(big, 12), 0, 12));
            assertNull(TiffHeader.read(TiffInput.of(new byte[4]), 0));
            big[4] = 4;
            assertThrows(MediaException.Unsupported.class, () -> TiffHeader.parse(big, 0, big.length));
        }
    }

    @Nested
    @DisplayName("Ifd")
    class Directories {
        @ParameterizedTest(name = "little endian {0}")
        @ValueSource(booleans = {false, true})
        @DisplayName("reads inline and out-of-line values of every integer and numeric type")
        void values(boolean little) {
            var bytes = new Builder(little)
                    .entry(256, TiffTypes.SHORT, 1, little ? 640 : 640L << 16)
                    .entry(257, TiffTypes.LONG, 1, 480)
                    .entry(258, TiffTypes.SHORT, 3, new byte[6])
                    .entry(282, TiffTypes.RATIONAL, 1, rationalBytes(little, 72, 1))
                    .entry(305, TiffTypes.ASCII, 4, little ? 0x00636261L : 0x61626300L)
                    .entry(9, TiffTypes.SSHORT, 1, little ? 0xFFFE : 0xFFFEL << 16)
                    .next(0)
                    .build(6);
            var input = TiffInput.of(bytes);
            var header = TiffHeader.read(input, 0);
            var ifd = Ifd.read(input, 0, header, header.firstIfd(), Ifd.Policy.STRICT);
            assertEquals(6, ifd.count());
            assertEquals(0, ifd.next());
            assertEquals(640, ifd.firstInteger(ifd.find(256), -1));
            assertEquals(480, ifd.firstInteger(ifd.find(257), -1));
            assertArrayEquals(new long[]{0, 0, 0}, ifd.integers(ifd.find(258), MediaLimits.DEFAULT));
            assertFalse(ifd.isInline(ifd.find(258)));
            assertTrue(ifd.isInline(ifd.find(256)));
            var rationalIndex = ifd.find(282);
            assertEquals(72.0, TiffTypes.number(ifd.value(rationalIndex, MediaLimits.DEFAULT), 0, TiffTypes.RATIONAL, little));
            assertEquals("abc", new String(ifd.value(ifd.find(305), MediaLimits.DEFAULT), 0, 3));
            assertEquals(-2, ifd.firstInteger(ifd.find(9), 0));
            assertEquals(8 + 2 + 8, ifd.valuePosition(ifd.find(256)));
            assertEquals(ifd.valueOffset(ifd.find(258)), ifd.valuePosition(ifd.find(258)));
            assertThrows(MediaException.Malformed.class, () -> ifd.integers(rationalIndex, MediaLimits.DEFAULT));
            assertSame(header, ifd.header());
        }

        @Test
        @DisplayName("the strict policy hides entries of an unknown type, the lenient one reads them as bytes")
        void unknownTypes() {
            var bytes = new Builder(true).entry(700, 99, 3, 0x00030201L).build(1);
            var input = TiffInput.of(bytes);
            var header = TiffHeader.read(input, 0);
            var strict = Ifd.read(input, 0, header, 8, Ifd.Policy.STRICT);
            assertEquals(-1, strict.find(700));
            assertEquals(-1, strict.byteLength(0));
            assertThrows(MediaException.Malformed.class, () -> strict.value(0, MediaLimits.DEFAULT));
            var lenient = Ifd.read(input, 0, header, 8, Ifd.Policy.LENIENT);
            assertEquals(0, lenient.find(700));
            assertArrayEquals(new byte[]{1, 2, 3}, lenient.value(0, MediaLimits.DEFAULT));
        }

        @Test
        @DisplayName("the strict policy rejects a truncated directory, the lenient one keeps its whole entries")
        void truncated() {
            var bytes = new Builder(true).entry(1, TiffTypes.SHORT, 1, 5).entry(2, TiffTypes.SHORT, 1, 6)
                    .entry(3, TiffTypes.SHORT, 1, 7).next(8).build(3);
            var cut = Arrays.copyOf(bytes, 8 + 2 + 12 * 2 + 5);
            var input = TiffInput.of(cut);
            var header = TiffHeader.read(input, 0);
            assertThrows(MediaException.Malformed.class, () -> Ifd.read(input, 0, header, 8, Ifd.Policy.STRICT));
            var lenient = Ifd.read(input, 0, header, 8, Ifd.Policy.LENIENT);
            assertEquals(2, lenient.count());
            assertEquals(0, lenient.next());
            var whole = Ifd.read(TiffInput.of(bytes), 0, header, 8, Ifd.Policy.STRICT);
            assertEquals(8, whole.next());
        }

        @Test
        @DisplayName("caps the declared entry count by policy")
        void entryCap() {
            var bytes = new Builder(true).build(5000);
            var input = TiffInput.of(Arrays.copyOf(bytes, bytes.length + 5000 * 12));
            var header = TiffHeader.read(input, 0);
            assertThrows(MediaException.Malformed.class, () -> Ifd.read(input, 0, header, 8, Ifd.Policy.LENIENT));
            assertEquals(5000, Ifd.read(input, 0, header, 8, Ifd.Policy.STRICT).count());
        }

        @Test
        @DisplayName("rejects directories and values outside the input and values over the policy's maximum")
        void bounds() {
            var bytes = new Builder(false).entry(1, TiffTypes.BYTE, 100, 4000).build(1);
            var input = TiffInput.of(bytes);
            var header = TiffHeader.read(input, 0);
            assertThrows(MediaException.Malformed.class, () -> Ifd.read(input, 0, header, bytes.length, Ifd.Policy.STRICT));
            assertThrows(MediaException.Malformed.class, () -> Ifd.read(input, 0, header, -1, Ifd.Policy.STRICT));
            var ifd = Ifd.read(input, 0, header, 8, Ifd.Policy.STRICT);
            assertThrows(MediaException.Malformed.class, () -> ifd.value(0, MediaLimits.DEFAULT));
            var large = new Builder(false).entry(1, TiffTypes.BYTE, 64, new byte[64]).build(1);
            var tiny = new Ifd.Policy(16, true, 1, 32);
            var limited = Ifd.read(TiffInput.of(large), 0, header, 8, tiny);
            assertThrows(MediaException.LimitExceeded.class, () -> limited.value(0, MediaLimits.DEFAULT));
            assertThrows(IllegalArgumentException.class, () -> new Ifd.Policy(16, true, 2, 32));
            // a count that runs past the end of the input invalidates the first value too, although its bytes exist
            var counted = new Builder(true).entry(262, TiffTypes.SHORT, 0x7FFF0001L, 4).build(1);
            var countedInput = TiffInput.of(counted);
            var countedIfd = Ifd.read(countedInput, 0, TiffHeader.read(countedInput, 0), 8, Ifd.Policy.STRICT);
            assertThrows(MediaException.Malformed.class, () -> countedIfd.firstInteger(0, -1));
            var listed = new Builder(true).entry(262, TiffTypes.SHORT, 3, new byte[]{2, 0, 0, 0, 0, 0}).build(1);
            var listedInput = TiffInput.of(listed);
            assertEquals(2, Ifd.read(listedInput, 0, TiffHeader.read(listedInput, 0), 8, Ifd.Policy.STRICT)
                    .firstInteger(0, -1));
        }

        @Test
        @DisplayName("resolves offsets against a base, as an EXIF block inside a file is read")
        void base() {
            var structure = new Builder(true).entry(1, TiffTypes.BYTE, 6, new byte[]{1, 2, 3, 4, 5, 6}).build(1);
            var file = new byte[100 + structure.length];
            System.arraycopy(structure, 0, file, 100, structure.length);
            var input = TiffInput.of(MediaSource.of(file));
            var header = TiffHeader.read(input, 100);
            var ifd = Ifd.read(input, 100, header, header.firstIfd(), Ifd.Policy.LENIENT);
            assertEquals(100, ifd.base());
            assertArrayEquals(new byte[]{1, 2, 3, 4, 5, 6}, ifd.value(0, MediaLimits.DEFAULT));
            assertEquals(100 + ifd.valueOffset(0), ifd.valuePosition(0));
        }

        private static byte[] rationalBytes(boolean little, long numerator, long denominator) {
            var out = new byte[8];
            for (var i = 0; i < 4; i++) {
                var shift = little ? 8 * i : 8 * (3 - i);
                out[i] = (byte) (numerator >>> shift);
                out[4 + i] = (byte) (denominator >>> shift);
            }
            return out;
        }
    }

    @Nested
    @DisplayName("TiffExif")
    class Exif {
        @ParameterizedTest(name = "little endian {0}")
        @ValueSource(booleans = {false, true})
        @DisplayName("reads SHORT and LONG orientations from 1 to 8, inline or not, and NORMAL otherwise")
        void rule(boolean little) {
            assertEquals(Orientation.ofExif(6), TiffExif.orientation(exif(little, TiffTypes.SHORT, 1, 6), 0));
            assertEquals(Orientation.ofExif(3), TiffExif.orientation(exif(little, TiffTypes.LONG, 1, 3), 0));
            assertEquals(Orientation.NORMAL, TiffExif.orientation(exif(little, TiffTypes.SHORT, 1, 9), 0));
            assertEquals(Orientation.NORMAL, TiffExif.orientation(exif(little, TiffTypes.LONG, 1, 0x10006), 0));
            assertEquals(Orientation.NORMAL, TiffExif.orientation(exif(little, TiffTypes.BYTE, 1, 6), 0));
            assertEquals(Orientation.NORMAL, TiffExif.orientation(exif(little, TiffTypes.SHORT, 0, 6), 0));
            var outOfLine = new Builder(little).entry(TiffExif.ORIENTATION, TiffTypes.SHORT, 3,
                    little ? new byte[]{8, 0, 1, 0, 1, 0} : new byte[]{0, 8, 0, 1, 0, 1}).build(1);
            assertEquals(Orientation.ofExif(8), TiffExif.orientation(outOfLine, 0));
        }

        @Test
        @DisplayName("reads a structure at an offset, behind the Exif identifier, and tolerates damage")
        void placement() {
            var tiff = exif(false, TiffTypes.SHORT, 1, 5);
            var block = new byte[6 + tiff.length];
            block[0] = 'E';
            block[1] = 'x';
            block[2] = 'i';
            block[3] = 'f';
            System.arraycopy(tiff, 0, block, 6, tiff.length);
            assertEquals(6, TiffExif.prefixLength(block, 0));
            assertEquals(Orientation.ofExif(5), TiffExif.orientation(block, 6));
            assertArrayEquals(tiff, TiffExif.stripExifPrefix(block));
            assertSame(tiff, TiffExif.stripExifPrefix(tiff));
            assertEquals(Orientation.NORMAL, TiffExif.orientation(Arrays.copyOf(tiff, 20), 0));
            assertEquals(Orientation.NORMAL, TiffExif.orientation(new byte[3], 0));
            var badOffset = tiff.clone();
            badOffset[7] = 4;
            assertEquals(Orientation.NORMAL, TiffExif.orientation(badOffset, 0));
        }

        private static byte[] exif(boolean little, int type, long count, long value) {
            var field = type == TiffTypes.SHORT || type == TiffTypes.BYTE
                    ? (little ? value : value << (type == TiffTypes.BYTE ? 24 : 16))
                    : value;
            return new Builder(little).entry(0x0100, TiffTypes.LONG, 1, 640).entry(TiffExif.ORIENTATION, type, count, field)
                    .build(2);
        }
    }
}
