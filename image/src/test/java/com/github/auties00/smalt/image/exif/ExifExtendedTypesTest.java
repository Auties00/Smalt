package com.github.auties00.smalt.image.exif;

import com.github.auties00.smalt.MediaException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the EXIF 3.0 {@code UTF-8} type (129), the {@code IFD} type (13) of TIFF Technical Note 1
 * and uncompressed (strip) thumbnails through the reader, the fresh writer and the in-place rewrite.
 * Blocks with layouts the writer never produces (non-contiguous strips, type 13 pointers) are built
 * entry by entry with {@link Block}.
 */
@DisplayName("EXIF extended types and uncompressed thumbnails")
class ExifExtendedTypesTest {
    static final String UNICODE = "Caffè 日本 📷 ümläut";

    static ByteOrder order(boolean little) {
        return little ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN;
    }

    /** The raw type, count and value-or-offset field of the first entry of a tag in a directory. */
    record Raw(int type, long count, long field, int entryAt) {
    }

    static Raw raw(byte[] block, long directory, int tag) {
        var little = block[0] == 'I';
        var count = ExifReader.u16(block, (int) directory, little);
        for (var i = 0; i < count; i++) {
            var at = (int) directory + 2 + 12 * i;
            if (ExifReader.u16(block, at, little) == tag) {
                return new Raw(ExifReader.u16(block, at + 2, little), ExifReader.u32(block, at + 4, little),
                        ExifReader.u32(block, at + 8, little), at);
            }
        }
        return null;
    }

    static long ifd0(byte[] block) {
        return ExifReader.u32(block, 4, block[0] == 'I');
    }

    static long ifd1(byte[] block) {
        var little = block[0] == 'I';
        var ifd0 = ifd0(block);
        var count = ExifReader.u16(block, (int) ifd0, little);
        return ExifReader.u32(block, (int) ifd0 + 2 + 12 * count, little);
    }

    static int indexOf(byte[] data, byte[] needle) {
        outer:
        for (var i = 0; i + needle.length <= data.length; i++) {
            for (var j = 0; j < needle.length; j++) {
                if (data[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    /**
     * Builds a TIFF block from raw directory entries: IFD0 at offset 8, the other directories in
     * order, each directory's out-of-line values after its table, and free-form data blobs at the
     * offsets the caller asks for.
     */
    static final class Block {
        record Entry(int tag, int type, long count, byte[] value, int pointsTo) {
        }

        final boolean little;
        final List<List<Entry>> directories = new ArrayList<>();
        final List<Integer> next = new ArrayList<>();
        final Map<Integer, byte[]> blobs = new LinkedHashMap<>();
        int size = 4096;

        Block(boolean little) {
            this.little = little;
        }

        /** Adds a directory and returns its index; IFD0 is index 0. */
        int directory() {
            directories.add(new ArrayList<>());
            next.add(-1);
            return directories.size() - 1;
        }

        Block entry(int directory, int tag, int type, long count, byte[] value) {
            directories.get(directory).add(new Entry(tag, type, count, value, -1));
            return this;
        }

        /** Adds an entry holding the offset of another directory. */
        Block pointer(int directory, int tag, int type, int target) {
            directories.get(directory).add(new Entry(tag, type, 1, null, target));
            return this;
        }

        Block chain(int from, int to) {
            next.set(from, to);
            return this;
        }

        Block blob(int offset, byte[] data) {
            blobs.put(offset, data);
            return this;
        }

        byte[] build() {
            var buffer = ByteBuffer.allocate(size).order(order(little));
            buffer.put((byte) (little ? 'I' : 'M')).put((byte) (little ? 'I' : 'M')).putShort((short) 42).putInt(8);
            var offsets = new int[directories.size()];
            var cursor = 8;
            for (var d = 0; d < directories.size(); d++) {
                offsets[d] = cursor;
                cursor += 2 + 12 * directories.get(d).size() + 4;
                for (var entry : directories.get(d)) {
                    if (entry.value() != null && entry.value().length > 4) {
                        cursor += (entry.value().length + 1) & ~1;
                    }
                }
            }
            for (var d = 0; d < directories.size(); d++) {
                var at = offsets[d];
                var values = at + 2 + 12 * directories.get(d).size() + 4;
                buffer.putShort(at, (short) directories.get(d).size());
                var position = at + 2;
                for (var entry : directories.get(d)) {
                    buffer.putShort(position, (short) entry.tag()).putShort(position + 2, (short) entry.type())
                            .putInt(position + 4, (int) entry.count());
                    if (entry.pointsTo() >= 0) {
                        buffer.putInt(position + 8, offsets[entry.pointsTo()]);
                    } else if (entry.value().length <= 4) {
                        buffer.put(position + 8, entry.value());
                    } else {
                        buffer.putInt(position + 8, values);
                        buffer.put(values, entry.value());
                        values += (entry.value().length + 1) & ~1;
                    }
                    position += 12;
                }
                buffer.putInt(position, next.get(d) >= 0 ? offsets[next.get(d)] : 0);
            }
            var end = cursor;
            for (var blob : blobs.entrySet()) {
                buffer.put(blob.getKey(), blob.getValue());
                end = Math.max(end, blob.getKey() + blob.getValue().length);
            }
            return Arrays.copyOf(buffer.array(), end);
        }

        byte[] shorts(int... values) {
            var buffer = ByteBuffer.allocate(2 * values.length).order(order(little));
            for (var value : values) {
                buffer.putShort((short) value);
            }
            return buffer.array();
        }

        byte[] longs(long... values) {
            var buffer = ByteBuffer.allocate(4 * values.length).order(order(little));
            for (var value : values) {
                buffer.putInt((int) value);
            }
            return buffer.array();
        }
    }

    static byte[] samples(int length, long seed) {
        var data = new byte[length];
        new Random(seed).nextBytes(data);
        return data;
    }

    /** An IFD1 describing a width x height 8-bit RGB uncompressed thumbnail. */
    static Map<Integer, ExifValue> rgbThumbnailIfd(int width, int height) {
        var ifd1 = new LinkedHashMap<Integer, ExifValue>();
        ifd1.put(0x0100, ExifValue.ofShort(width));
        ifd1.put(0x0101, ExifValue.ofShort(height));
        ifd1.put(0x0102, ExifValue.ofShort(8, 8, 8));
        ifd1.put(0x0106, ExifValue.ofShort(2));
        ifd1.put(0x0115, ExifValue.ofShort(3));
        ifd1.put(0x011C, ExifValue.ofShort(1));
        return ifd1;
    }

    @Nested
    @DisplayName("UTF-8 values")
    class Utf8Values {
        @Test
        @DisplayName("ofUtf8 counts UTF-8 bytes and rejects NUL and unpaired surrogates")
        void factory() {
            var value = ExifValue.ofUtf8(UNICODE);
            assertEquals(ExifValue.Type.UTF8, value.type());
            assertEquals(UNICODE.getBytes(StandardCharsets.UTF_8).length, value.count());
            assertEquals(ExifValue.ofUtf8(""), new ExifValue.Utf8(""));
            assertThrows(IllegalArgumentException.class, () -> ExifValue.ofUtf8("a\0b"));
            assertThrows(IllegalArgumentException.class, () -> ExifValue.ofUtf8("x\ud83d"));
            assertThrows(IllegalArgumentException.class, () -> ExifValue.ofUtf8("\udcf7x"));
            assertThrows(NullPointerException.class, () -> ExifValue.ofUtf8(null));
        }

        @Test
        @DisplayName("type codes 13 and 129 resolve; the other codes above 12 do not")
        void codes() {
            assertEquals(ExifValue.Type.IFD, ExifValue.Type.of(13));
            assertEquals(ExifValue.Type.UTF8, ExifValue.Type.of(129));
            assertEquals(4, ExifValue.Type.IFD.size());
            assertEquals(1, ExifValue.Type.UTF8.size());
            for (var code : new int[]{0, 14, 16, 18, 128, 130, 255}) {
                assertNull(ExifValue.Type.find(code), "code " + code);
                assertThrows(IllegalArgumentException.class, () -> ExifValue.Type.of(code));
            }
            assertThrows(IllegalArgumentException.class, () -> new ExifValue.Integers(ExifValue.Type.UTF8, new long[]{1}));
        }

        @ParameterizedTest(name = "little={0}")
        @ValueSource(booleans = {true, false})
        @DisplayName("a fresh block stores type 129 with the NUL counted and reads back the same text")
        void fresh(boolean little) {
            var data = ExifData.EMPTY.with(ExifData.Directory.IFD0, 0x013B, ExifValue.ofUtf8(UNICODE))
                    .with(ExifData.Directory.IFD0, 0x010E, ExifValue.ofUtf8("ok"))
                    .with(ExifData.Directory.EXIF, 0x9286, ExifValue.ofUtf8("é"));
            var block = ExifWriter.write(data, order(little));
            var artist = raw(block, ifd0(block), 0x013B);
            var bytes = UNICODE.getBytes(StandardCharsets.UTF_8);
            assertEquals(129, artist.type());
            assertEquals(bytes.length + 1, artist.count());
            assertEquals(0, block[(int) artist.field() + bytes.length]);
            assertArrayEquals(bytes, Arrays.copyOfRange(block, (int) artist.field(), (int) artist.field() + bytes.length));
            var inline = raw(block, ifd0(block), 0x010E);
            assertEquals(3, inline.count());
            var read = ExifReader.parse(block);
            assertEquals(ExifValue.ofUtf8(UNICODE), read.ifd0().get(0x013B));
            assertEquals(ExifValue.ofUtf8("ok"), read.ifd0().get(0x010E));
            assertEquals(ExifValue.ofUtf8("é"), read.exif().get(0x9286));
        }

        @Test
        @DisplayName("an in-place rewrite keeps an unchanged UTF-8 value at its offset and moves a changed one")
        void rewrite() {
            var data = ExifData.EMPTY.with(ExifData.Directory.IFD0, 0x013B, ExifValue.ofUtf8(UNICODE))
                    .with(ExifData.Directory.IFD0, 0x8298, ExifValue.ofUtf8("© Smalt contributors"));
            var original = ExifWriter.write(data, ByteOrder.LITTLE_ENDIAN);
            var artistAt = raw(original, ifd0(original), 0x013B).field();
            var edited = ExifReader.parse(original).with(ExifData.Directory.IFD0, 0x8298,
                    ExifValue.ofUtf8("© a much longer copyright notice than before ™"));
            var rewritten = ExifWriter.rewrite(original, edited);
            assertEquals(artistAt, raw(rewritten, ifd0(rewritten), 0x013B).field());
            assertEquals(129, raw(rewritten, ifd0(rewritten), 0x8298).type());
            var read = ExifReader.parse(rewritten);
            assertEquals(ExifValue.ofUtf8(UNICODE), read.ifd0().get(0x013B));
            assertEquals(ExifValue.ofUtf8("© a much longer copyright notice than before ™"), read.ifd0().get(0x8298));
            assertEquals(-1, indexOf(rewritten, "Smalt contributors".getBytes(StandardCharsets.UTF_8)));
        }

        @Test
        @DisplayName("the text ends at the first NUL and malformed bytes decode to U+FFFD")
        void decoding() {
            var block = new Block(false);
            var ifd0 = block.directory();
            block.entry(ifd0, 0x013B, 129, 9, new byte[]{'a', (byte) 0xC3, (byte) 0xA9, 0, 'z', 'z', 'z', 'z', 0});
            block.entry(ifd0, 0x010E, 129, 4, new byte[]{'b', (byte) 0xFF, 'c', 0});
            var read = ExifReader.parse(block.build());
            assertEquals(ExifValue.ofUtf8("aé"), read.ifd0().get(0x013B));
            assertEquals(ExifValue.ofUtf8("b�c"), read.ifd0().get(0x010E));
        }

        @Test
        @DisplayName("ExifData.parse and toBytes round trip UTF-8 text")
        void dataApi() {
            var data = ExifData.EMPTY.with(ExifData.Directory.IFD0, 0x013B, ExifValue.ofUtf8(UNICODE));
            assertEquals(data.ifd0(), ExifData.parse(data.toBytes(ByteOrder.LITTLE_ENDIAN)).ifd0());
        }
    }

    @Nested
    @DisplayName("IFD values")
    class IfdValues {
        static Block block(boolean little) {
            var block = new Block(little);
            var ifd0 = block.directory();
            var exif = block.directory();
            var sub = block.directory();
            block.entry(ifd0, 0x010F, 2, 6, "Smalt\0".getBytes(StandardCharsets.US_ASCII));
            block.pointer(ifd0, ExifData.EXIF_IFD_POINTER, 13, exif);
            block.pointer(ifd0, 0xC000, 13, sub);
            block.entry(exif, 0x9003, 2, 20, "2026:10:22 10:00:00\0".getBytes(StandardCharsets.US_ASCII));
            block.entry(sub, 0x0001, 4, 1, block.longs(0x12345678L));
            return block;
        }

        @ParameterizedTest(name = "little={0}")
        @ValueSource(booleans = {true, false})
        @DisplayName("are read as IFD integers, and a type 13 Exif pointer is followed")
        void read(boolean little) {
            var bytes = block(little).build();
            var data = ExifReader.parse(bytes);
            var pointer = assertInstanceOf(ExifValue.Integers.class, data.ifd0().get(0xC000));
            assertEquals(ExifValue.Type.IFD, pointer.type());
            assertEquals(1, pointer.count());
            assertEquals(new ExifValue.Ascii("2026:10:22 10:00:00"), data.exif().get(0x9003));
            assertEquals(ExifValue.Type.IFD, data.ifd0().get(ExifData.EXIF_IFD_POINTER).type());
        }

        @ParameterizedTest(name = "little={0}")
        @ValueSource(booleans = {true, false})
        @DisplayName("an in-place rewrite keeps the entry, its offset and the directory it points at")
        void rewrite(boolean little) {
            var original = block(little).build();
            var data = ExifReader.parse(original);
            var target = ((ExifValue.Integers) data.ifd0().get(0xC000)).get(0);
            var subDirectory = Arrays.copyOfRange(original, (int) target, (int) target + 18);
            var edited = data.with(ExifData.Directory.IFD0, 0x0110, new ExifValue.Ascii("A new model name"));
            var rewritten = ExifWriter.rewrite(original, edited);
            var entry = raw(rewritten, ifd0(rewritten), 0xC000);
            assertEquals(13, entry.type());
            assertEquals(target, entry.field());
            assertArrayEquals(subDirectory, Arrays.copyOfRange(rewritten, (int) target, (int) target + 18));
            var read = ExifReader.parse(rewritten);
            assertEquals(new ExifValue.Ascii("A new model name"), read.ifd0().get(0x0110));
            assertEquals(data.ifd0().get(0xC000), read.ifd0().get(0xC000));
            assertEquals(new ExifValue.Ascii("2026:10:22 10:00:00"), read.exif().get(0x9003));
        }

        @Test
        @DisplayName("a fresh layout leaves out the offsets it cannot relocate")
        void fresh() {
            var data = ExifReader.parse(block(true).build());
            var written = ExifReader.parse(ExifWriter.write(data));
            assertFalse(written.ifd0().containsKey(0xC000));
            assertEquals(new ExifValue.Ascii("Smalt"), written.ifd0().get(0x010F));
            assertEquals(new ExifValue.Ascii("2026:10:22 10:00:00"), written.exif().get(0x9003));
            assertEquals(ExifValue.Type.LONG, written.ifd0().get(ExifData.EXIF_IFD_POINTER).type());
        }
    }

    @Nested
    @DisplayName("uncompressed thumbnails")
    class Uncompressed {
        @ParameterizedTest(name = "little={0}")
        @ValueSource(booleans = {true, false})
        @DisplayName("a fresh block stores the samples as one strip and reads them back")
        void fresh(boolean little) {
            var samples = samples(6 * 4 * 3, 1);
            var data = new ExifData(Map.of(0x010F, new ExifValue.Ascii("Smalt")), Map.of(), Map.of(), Map.of(),
                    rgbThumbnailIfd(6, 4), Optional.empty()).withUncompressedThumbnail(samples);
            assertTrue(data.hasUncompressedThumbnail());
            var block = ExifWriter.write(data, order(little));
            var ifd1 = ifd1(block);
            var offsets = raw(block, ifd1, ExifData.STRIP_OFFSETS);
            var counts = raw(block, ifd1, ExifData.STRIP_BYTE_COUNTS);
            assertEquals(1, offsets.count());
            assertEquals(samples.length, counts.field());
            assertArrayEquals(samples, Arrays.copyOfRange(block, (int) offsets.field(), (int) offsets.field() + samples.length));
            assertNull(raw(block, ifd1, ExifData.THUMBNAIL_OFFSET));
            assertNull(raw(block, ifd1, ExifData.THUMBNAIL_LENGTH));
            assertEquals(1, raw(block, ifd1, ExifData.COMPRESSION).field() >>> (little ? 0 : 16));
            var read = ExifReader.parse(block);
            assertTrue(read.hasUncompressedThumbnail());
            assertArrayEquals(samples, read.thumbnail().orElseThrow());
            assertEquals(rgbThumbnailIfd(6, 4).get(0x0102), read.thumbnailIfd().get(0x0102));
        }

        static byte[] stripped(boolean little, boolean contiguous, byte[] samples, boolean compressionTag) {
            var block = new Block(little);
            var ifd0 = block.directory();
            var ifd1 = block.directory();
            block.entry(ifd0, 0x010F, 2, 6, "Smalt\0".getBytes(StandardCharsets.US_ASCII));
            block.chain(ifd0, ifd1);
            block.entry(ifd1, 0x0100, 3, 1, block.shorts(4, 0));
            block.entry(ifd1, 0x0101, 3, 1, block.shorts(3, 0));
            block.entry(ifd1, 0x0102, 3, 3, block.shorts(8, 8, 8));
            if (compressionTag) {
                block.entry(ifd1, 0x0103, 3, 1, block.shorts(1, 0));
            }
            block.entry(ifd1, 0x0106, 3, 1, block.shorts(2, 0));
            var row = 4 * 3;
            var first = 1000;
            var starts = contiguous ? new long[]{first, first + row, first + 2 * row} : new long[]{first + 400, first, first + 200};
            block.entry(ifd1, 0x0111, 4, 3, block.longs(starts));
            block.entry(ifd1, 0x0115, 3, 1, block.shorts(3, 0));
            block.entry(ifd1, 0x0116, 3, 1, block.shorts(1, 0));
            block.entry(ifd1, 0x0117, 4, 3, block.longs(row, row, row));
            for (var i = 0; i < 3; i++) {
                block.blob((int) starts[i], Arrays.copyOfRange(samples, i * row, (i + 1) * row));
            }
            return block.build();
        }

        @ParameterizedTest(name = "compression tag={0}")
        @ValueSource(booleans = {true, false})
        @DisplayName("the strips are concatenated in order, also without a Compression entry")
        void strips(boolean compressionTag) {
            var samples = samples(36, 2);
            var data = ExifReader.parse(stripped(true, false, samples, compressionTag));
            assertTrue(data.hasUncompressedThumbnail());
            assertArrayEquals(samples, data.thumbnail().orElseThrow());
        }

        @ParameterizedTest(name = "little={0}")
        @ValueSource(booleans = {true, false})
        @DisplayName("a rewrite keeps contiguous strips at their offset as one strip")
        void contiguous(boolean little) {
            var samples = samples(36, 3);
            var original = stripped(little, true, samples, true);
            var edited = ExifReader.parse(original).with(ExifData.Directory.IFD0, 0x0110, new ExifValue.Ascii("Model"));
            var rewritten = ExifWriter.rewrite(original, edited);
            assertArrayEquals(samples, Arrays.copyOfRange(rewritten, 1000, 1036));
            var offsets = raw(rewritten, ifd1(rewritten), ExifData.STRIP_OFFSETS);
            assertEquals(1, offsets.count());
            assertEquals(1000, offsets.field());
            assertNull(raw(rewritten, ifd1(rewritten), ExifData.ROWS_PER_STRIP), "RowsPerStrip contradicts one strip");
            var read = ExifReader.parse(rewritten);
            assertArrayEquals(samples, read.thumbnail().orElseThrow());
            assertEquals(new ExifValue.Ascii("Model"), read.ifd0().get(0x0110));
        }

        @Test
        @DisplayName("a rewrite gathers scattered strips into one and zeroes the old ones")
        void scattered() {
            var samples = samples(36, 4);
            var original = stripped(true, false, samples, true);
            var rewritten = ExifWriter.rewrite(original, ExifReader.parse(original)
                    .with(ExifData.Directory.IFD0, 0x0110, new ExifValue.Ascii("Model")));
            var read = ExifReader.parse(rewritten);
            assertTrue(read.hasUncompressedThumbnail());
            assertArrayEquals(samples, read.thumbnail().orElseThrow());
            assertEquals(1, raw(rewritten, ifd1(rewritten), ExifData.STRIP_OFFSETS).count());
            var moved = raw(rewritten, ifd1(rewritten), ExifData.STRIP_OFFSETS).field();
            assertTrue(moved != 1000 && moved != 1200 && moved != 1400, "the strip was not moved");
            for (var start : new int[]{1000, 1200, 1400}) {
                assertArrayEquals(new byte[12], Arrays.copyOfRange(rewritten, start, start + 12), "old strip at " + start);
            }
        }

        @Test
        @DisplayName("withThumbnail and withUncompressedThumbnail switch the kind and its location entries")
        void switching() {
            var samples = samples(36, 5);
            var uncompressed = ExifReader.parse(stripped(true, true, samples, true));
            var jpeg = new byte[]{(byte) 0xFF, (byte) 0xD8, 1, 2, (byte) 0xFF, (byte) 0xD9};
            var asJpeg = uncompressed.withThumbnail(jpeg);
            assertFalse(asJpeg.hasUncompressedThumbnail());
            assertFalse(asJpeg.thumbnailIfd().containsKey(ExifData.STRIP_OFFSETS));
            assertFalse(asJpeg.thumbnailIfd().containsKey(ExifData.ROWS_PER_STRIP));
            assertEquals(ExifValue.ofShort(6), asJpeg.thumbnailIfd().get(ExifData.COMPRESSION));
            var block = ExifWriter.rewrite(stripped(true, true, samples, true), asJpeg);
            var read = ExifReader.parse(block);
            assertFalse(read.hasUncompressedThumbnail());
            assertArrayEquals(jpeg, read.thumbnail().orElseThrow());
            assertNull(raw(block, ifd1(block), ExifData.STRIP_OFFSETS));
            var back = read.withUncompressedThumbnail(samples);
            assertTrue(back.hasUncompressedThumbnail());
            assertFalse(back.thumbnailIfd().containsKey(ExifData.THUMBNAIL_OFFSET));
            var again = ExifReader.parse(ExifWriter.rewrite(block, back));
            assertTrue(again.hasUncompressedThumbnail());
            assertArrayEquals(samples, again.thumbnail().orElseThrow());
            assertEquals(-1, indexOf(ExifWriter.rewrite(block, back), jpeg));
        }

        @Test
        @DisplayName("strips outside the block, of unequal counts or empty give no thumbnail")
        void invalid() {
            var block = new Block(true);
            var ifd0 = block.directory();
            var ifd1 = block.directory();
            block.chain(ifd0, ifd1);
            block.entry(ifd0, 0x010F, 2, 2, new byte[]{'A', 0});
            block.entry(ifd1, 0x0103, 3, 1, block.shorts(1, 0));
            block.entry(ifd1, 0x0111, 4, 2, block.longs(100, 9000));
            block.entry(ifd1, 0x0117, 4, 2, block.longs(4, 4));
            assertTrue(ExifReader.parse(block.build()).thumbnail().isEmpty());
            var unequal = new Block(true);
            unequal.directory();
            unequal.directory();
            unequal.chain(0, 1);
            unequal.entry(0, 0x010F, 2, 2, new byte[]{'A', 0});
            unequal.entry(1, 0x0103, 3, 1, unequal.shorts(1, 0));
            unequal.entry(1, 0x0111, 4, 2, unequal.longs(100, 104));
            unequal.entry(1, 0x0117, 4, 1, unequal.longs(4));
            assertTrue(ExifReader.parse(unequal.build()).thumbnail().isEmpty());
            var empty = new Block(true);
            empty.directory();
            empty.directory();
            empty.chain(0, 1);
            empty.entry(0, 0x010F, 2, 2, new byte[]{'A', 0});
            empty.entry(1, 0x0103, 3, 1, empty.shorts(1, 0));
            empty.entry(1, 0x0111, 4, 1, empty.longs(100));
            empty.entry(1, 0x0117, 4, 1, empty.longs(0));
            assertTrue(ExifReader.parse(empty.build()).thumbnail().isEmpty());
        }

        @Test
        @DisplayName("survives hostile strip tables, raising nothing but MediaException")
        void hostile() {
            var random = new Random(77);
            var seed = stripped(false, false, samples(36, 6), true);
            for (var round = 0; round < 3000; round++) {
                var mutated = seed.clone();
                for (var flips = 0; flips < 1 + random.nextInt(8); flips++) {
                    mutated[random.nextInt(Math.min(mutated.length, 200))] = (byte) random.nextInt(256);
                }
                try {
                    var parsed = ExifReader.parse(mutated);
                    ExifWriter.rewrite(mutated, parsed.with(ExifData.Directory.IFD0, 0x0110, new ExifValue.Ascii("m")));
                    ExifWriter.write(parsed);
                } catch (MediaException expected) {
                    // corrupt input may be rejected, never crash
                }
            }
        }
    }
}
