package com.github.auties00.smalt.image.exif;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.Orientation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the EXIF writer: fresh layouts round trip through the reader in both byte orders, and
 * rewrites keep unchanged values (the maker note above all) at their offsets while zeroing what
 * they remove.
 */
@DisplayName("ExifWriter")
class ExifWriterTest {
    /** A maker note holding an absolute offset into the block, as Canon's do. */
    static final byte[] MAKER_NOTE = "MAKERNOTE-with-absolute-offsets-0123456789".getBytes(StandardCharsets.US_ASCII);

    static ExifData sample() {
        var ifd0 = new LinkedHashMap<Integer, ExifValue>();
        ifd0.put(0x010F, new ExifValue.Ascii("SmaltCam"));
        ifd0.put(0x0110, new ExifValue.Ascii("Model One"));
        ifd0.put(ExifData.ORIENTATION, new ExifValue.Integers(ExifValue.Type.SHORT, new long[]{6}));
        ifd0.put(0x011A, new ExifValue.Rationals(ExifValue.Type.RATIONAL, new long[]{72}, new long[]{1}));
        var exif = new LinkedHashMap<Integer, ExifValue>();
        exif.put(0x9003, new ExifValue.Ascii("2024:05:01 12:00:00"));
        exif.put(0x9000, new ExifValue.Undefined("0232".getBytes(StandardCharsets.US_ASCII)));
        exif.put(ExifData.MAKER_NOTE, new ExifValue.Undefined(MAKER_NOTE));
        exif.put(0x9204, new ExifValue.Rationals(ExifValue.Type.SRATIONAL, new long[]{-1}, new long[]{3}));
        exif.put(0x0005, new ExifValue.Reals(ExifValue.Type.DOUBLE, new double[]{1.25, -8}));
        var gps = new LinkedHashMap<Integer, ExifValue>();
        gps.put(0x0000, new ExifValue.Integers(ExifValue.Type.BYTE, new long[]{2, 3, 0, 0}));
        gps.put(0x0001, new ExifValue.Ascii("N"));
        gps.put(0x0002, new ExifValue.Rationals(ExifValue.Type.RATIONAL, new long[]{45, 27, 5112}, new long[]{1, 1, 100}));
        var interop = Map.<Integer, ExifValue>of(0x0001, new ExifValue.Ascii("R98"));
        var ifd1 = Map.<Integer, ExifValue>of(0x0103, new ExifValue.Integers(ExifValue.Type.SHORT, new long[]{6}));
        return new ExifData(ifd0, exif, gps, interop, ifd1, Optional.of(new byte[]{(byte) 0xFF, (byte) 0xD8, 1, 2, 3, (byte) 0xFF,
                (byte) 0xD9}));
    }

    /** Removes the pointer entries the writer computes, so data written and read back compare equal. */
    static ExifData withoutPointers(ExifData data) {
        var ifd0 = new LinkedHashMap<>(data.ifd0());
        ifd0.remove(ExifData.EXIF_IFD_POINTER);
        ifd0.remove(ExifData.GPS_IFD_POINTER);
        var exif = new LinkedHashMap<>(data.exif());
        exif.remove(ExifData.INTEROP_IFD_POINTER);
        var ifd1 = new LinkedHashMap<>(data.thumbnailIfd());
        ifd1.remove(ExifData.THUMBNAIL_OFFSET);
        ifd1.remove(ExifData.THUMBNAIL_LENGTH);
        return new ExifData(sorted(ifd0), sorted(exif), sorted(data.gps()), sorted(data.interop()), sorted(ifd1), data.thumbnail());
    }

    static Map<Integer, ExifValue> sorted(Map<Integer, ExifValue> map) {
        return new java.util.TreeMap<>(map);
    }

    @Nested
    @DisplayName("write")
    class Write {
        @ParameterizedTest(name = "little={0}")
        @ValueSource(booleans = {true, false})
        @DisplayName("round trips every directory, value type and the thumbnail")
        void roundTrip(boolean little) {
            var data = sample();
            var block = ExifWriter.write(data, little ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN);
            assertEquals(little ? 'I' : 'M', block[0]);
            var read = ExifReader.parse(block);
            assertEquals(withoutPointers(data), withoutPointers(read));
            assertEquals(Orientation.ROTATE_90, read.orientation());
            assertArrayEquals(data.thumbnail().orElseThrow(), read.thumbnail().orElseThrow());
        }

        @Test
        @DisplayName("drops the pointers of empty directories and writes an empty block as a bare IFD0")
        void empty() {
            var block = ExifWriter.write(ExifData.EMPTY);
            assertEquals(8 + 2 + 4, block.length);
            assertEquals(ExifData.EMPTY, ExifReader.parse(block));
            var gpsOnly = ExifReader.parse(ExifWriter.write(sample().withoutThumbnail()));
            assertTrue(gpsOnly.thumbnailIfd().isEmpty());
            assertTrue(gpsOnly.thumbnail().isEmpty());
        }
    }

    @Nested
    @DisplayName("rewrite")
    class Rewrite {
        @Test
        @DisplayName("keeps the maker note and unchanged values at their offsets")
        void makerNote() {
            var original = ExifWriter.write(sample(), ByteOrder.LITTLE_ENDIAN);
            var makerAt = indexOf(original, MAKER_NOTE);
            var parsed = ExifReader.parse(original);
            var edited = parsed.with(ExifData.Directory.IFD0, 0x013B, new ExifValue.Ascii("A much longer artist name than before"))
                    .with(ExifData.Directory.IFD0, 0x0110, new ExifValue.Ascii("Model Two"))
                    .withOrientation(Orientation.FLIP_HORIZONTAL);
            var rewritten = ExifWriter.rewrite(original, edited);
            assertEquals(makerAt, indexOf(rewritten, MAKER_NOTE), "the maker note moved");
            assertArrayEquals(Arrays.copyOf(original, 2), Arrays.copyOf(rewritten, 2));
            var read = ExifReader.parse(rewritten);
            assertEquals(withoutPointers(edited), withoutPointers(read));
            assertEquals(Orientation.FLIP_HORIZONTAL, read.orientation());
            assertEquals(-1, indexOf(rewritten, "Model One".getBytes(StandardCharsets.US_ASCII)), "a replaced value survived");
        }

        @ParameterizedTest(name = "little={0}")
        @ValueSource(booleans = {true, false})
        @DisplayName("removes the GPS directory without leaving its values in the block")
        void gps(boolean little) {
            var original = ExifWriter.write(sample(), little ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN);
            var makerAt = indexOf(original, MAKER_NOTE);
            var rewritten = ExifWriter.rewrite(original, ExifReader.parse(original).withoutGps());
            assertEquals(original.length, rewritten.length, "removal never grows the block");
            var read = ExifReader.parse(rewritten);
            assertTrue(read.gps().isEmpty());
            assertFalse(read.hasLocation());
            assertFalse(read.ifd0().containsKey(ExifData.GPS_IFD_POINTER));
            assertEquals(makerAt, indexOf(rewritten, MAKER_NOTE));
            var gpsValue = new byte[24];
            java.nio.ByteBuffer.wrap(gpsValue).order(little ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN)
                    .putInt(45).putInt(1).putInt(27).putInt(1).putInt(5112).putInt(100);
            assertEquals(-1, indexOf(rewritten, gpsValue), "the GPS coordinates survived");
            assertEquals(withoutPointers(ExifReader.parse(original).withoutGps()), withoutPointers(read));
        }

        @Test
        @DisplayName("moves a value that overlaps the header instead of writing the old header back over the new one")
        void valueOverHeader() {
            var original = java.nio.ByteBuffer.allocate(56)
                    .put(new byte[]{'M', 'M', 0, 42}).putInt(8)
                    .putShort((short) 2)
                    .putShort((short) ExifData.GPS_IFD_POINTER).putShort((short) 4).putInt(1).putInt(38)
                    // a private entry whose 38 bytes are the header and IFD0 themselves
                    .putShort((short) 0xC000).putShort((short) 1).putInt(38).putInt(0)
                    .putInt(0)
                    .putShort((short) 1)
                    .putShort((short) 1).putShort((short) 2).putInt(2).put(new byte[]{'N', 0, 0, 0})
                    .putInt(0)
                    .array();
            var parsed = ExifReader.parse(original);
            assertTrue(parsed.hasLocation());
            var read = ExifReader.parse(ExifWriter.rewrite(original, parsed.withoutGps()));
            assertFalse(read.hasLocation());
            assertFalse(read.ifd0().containsKey(ExifData.GPS_IFD_POINTER));
            assertEquals(parsed.ifd0().get(0xC000), read.ifd0().get(0xC000));
        }

        @Test
        @DisplayName("removes and replaces the thumbnail")
        void thumbnail() {
            var original = ExifWriter.write(sample());
            var removed = ExifWriter.rewrite(original, ExifReader.parse(original).withoutThumbnail());
            assertTrue(ExifReader.parse(removed).thumbnail().isEmpty());
            assertEquals(-1, indexOf(removed, new byte[]{(byte) 0xFF, (byte) 0xD8, 1, 2, 3}));
            var replacement = new byte[]{(byte) 0xFF, (byte) 0xD8, 9, 9, 9, 9, 9, (byte) 0xFF, (byte) 0xD9};
            var replaced = ExifWriter.rewrite(original, ExifReader.parse(original).withThumbnail(replacement));
            assertArrayEquals(replacement, ExifReader.parse(replaced).thumbnail().orElseThrow());
        }

        @Test
        @DisplayName("returns the same bytes when nothing changes")
        void unchanged() {
            var original = ExifWriter.write(sample(), ByteOrder.LITTLE_ENDIAN);
            assertArrayEquals(original, ExifWriter.rewrite(original, ExifReader.parse(original)));
        }

        @Test
        @DisplayName("writes a fresh block when the original is not a TIFF structure")
        void garbage() {
            var rewritten = ExifWriter.rewrite("not a tiff block".getBytes(StandardCharsets.US_ASCII), sample());
            assertEquals(withoutPointers(sample()), withoutPointers(ExifReader.parse(rewritten)));
        }

        @Test
        @DisplayName("terminates on cyclic directory pointers")
        void cycles() {
            var block = ExifWriter.write(sample().withoutThumbnail(), ByteOrder.BIG_ENDIAN);
            var buffer = java.nio.ByteBuffer.wrap(block);
            var entries = buffer.getShort(8) & 0xFFFF;
            buffer.putInt(8 + 2 + 12 * entries, 8);
            var exifAt = -1;
            for (var i = 0; i < entries; i++) {
                var entry = 8 + 2 + 12 * i;
                if ((buffer.getShort(entry) & 0xFFFF) == ExifData.EXIF_IFD_POINTER) {
                    exifAt = buffer.getInt(entry + 8);
                    buffer.putInt(entry + 8, 8);
                }
            }
            assertTrue(exifAt > 0);
            var cyclic = block;
            var rewritten = assertTimeoutPreemptively(Duration.ofSeconds(5),
                    () -> ExifWriter.rewrite(cyclic, ExifReader.parse(cyclic).withoutGps()));
            assertFalse(ExifReader.parse(rewritten).hasLocation());
        }

        @Test
        @DisplayName("survives hostile blocks, raising nothing but MediaException")
        void hostile() {
            var random = new Random(7);
            var base = ExifWriter.write(sample(), ByteOrder.LITTLE_ENDIAN);
            for (var round = 0; round < 400; round++) {
                var mutated = base.clone();
                for (var flips = 0; flips < 1 + random.nextInt(8); flips++) {
                    mutated[random.nextInt(mutated.length)] = (byte) random.nextInt(256);
                }
                if (random.nextInt(4) == 0) {
                    mutated = Arrays.copyOf(mutated, 8 + random.nextInt(mutated.length - 8));
                }
                var input = mutated;
                assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
                    ExifData parsed;
                    try {
                        parsed = ExifReader.parse(input);
                    } catch (MediaException exception) {
                        return;
                    }
                    var rewritten = ExifWriter.rewrite(input, parsed.withoutGps().withOrientation(Orientation.ROTATE_180));
                    assertEquals(Orientation.ROTATE_180, ExifReader.parse(rewritten).orientation());
                    assertFalse(ExifReader.parse(rewritten).hasLocation());
                });
            }
        }
    }

    static int indexOf(byte[] data, byte[] pattern) {
        outer:
        for (var i = 0; i + pattern.length <= data.length; i++) {
            for (var j = 0; j < pattern.length; j++) {
                if (data[i + j] != pattern[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }
}
