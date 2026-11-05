package com.github.auties00.smalt.internal.tiff;

import com.github.auties00.smalt.image.Orientation;
import com.github.auties00.smalt.internal.io.ByteArrays;
import com.github.auties00.smalt.testkit.image.ExifBlockBuilder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Arrays;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers the in-place EXIF edits over blocks laid out by {@link ExifBlockBuilder}, whose offsets are known: every
 * byte an edit keeps must stay where it was, and every byte it removes must be zeroed or cut off.
 */
class ExifBlocksTest {
    /**
     * {@return the indices at which two arrays of the same length differ}
     */
    static int[] differences(byte[] a, byte[] b) {
        return java.util.stream.IntStream.range(0, Math.min(a.length, b.length)).filter(i -> a[i] != b[i]).toArray();
    }

    static boolean contains(byte[] data, byte[] part) {
        outer:
        for (var i = 0; i + part.length <= data.length; i++) {
            for (var j = 0; j < part.length; j++) {
                if (data[i + j] != part[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    @Nested
    @DisplayName("withOrientation")
    class OrientationEdits {
        @ParameterizedTest(name = "little-endian {0}")
        @ValueSource(booleans = {true, false})
        @DisplayName("patches an existing SHORT entry in place, changing only its value")
        void patch(boolean little) {
            var builder = ExifBlockBuilder.camera(little, 6);
            var block = builder.build();
            var copy = block.clone();
            var edited = ExifBlocks.withOrientation(block, Orientation.NORMAL);
            assertArrayEquals(copy, block, "the input is never modified");
            assertEquals(block.length, edited.length);
            var changed = differences(block, edited);
            assertTrue(changed.length >= 1 && changed.length <= 2, Arrays.toString(changed));
            assertEquals(Orientation.NORMAL, TiffExif.orientation(edited, 0));
            assertSame(edited, ExifBlocks.withOrientation(edited, Orientation.NORMAL), "an unchanged block is returned");
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(Orientation.class)
        @DisplayName("records every orientation, adding a new first directory to a block without the entry")
        void append(Orientation orientation) {
            var builder = ExifBlockBuilder.camera(true, 0);
            var block = builder.build();
            var edited = ExifBlocks.withOrientation(block, orientation);
            if (orientation == Orientation.NORMAL) {
                assertSame(block, edited, "an absent entry already means normal");
                return;
            }
            assertEquals(orientation, TiffExif.orientation(edited, 0));
            // the old values stay where they were, so a maker note keeps its offsets
            assertArrayEquals(Arrays.copyOfRange(block, builder.offsets.get("exif"), block.length),
                    Arrays.copyOfRange(edited, builder.offsets.get("exif"), block.length));
            var ifd0 = (int) ByteArrays.u32(edited, 4, true);
            assertTrue(ifd0 >= block.length && ifd0 % 2 == 0, "the new directory is appended, word aligned");
            assertEquals(6, ByteArrays.u16(edited, ifd0, true));
            var tags = new int[6];
            for (var i = 0; i < 6; i++) {
                tags[i] = ByteArrays.u16(edited, ifd0 + 2 + 12 * i, true);
            }
            var sorted = tags.clone();
            Arrays.sort(sorted);
            assertArrayEquals(sorted, tags, "the entries stay in tag order");
            assertTrue(ExifBlocks.hasGps(edited));
            assertEquals(builder.offsets.get("ifd1"), (int) ByteArrays.u32(edited, ifd0 + 2 + 12 * 6, true),
                    "the next-directory link is kept");
            var oldTable = builder.offsets.get("ifd0");
            for (var i = oldTable; i < oldTable + 2 + 12 * 5 + 4; i++) {
                assertEquals(0, edited[i], "the old table is zeroed at " + i);
            }
        }

        @Test
        @DisplayName("rewrites an entry of another type as a single SHORT")
        void retype() {
            var block = new ExifBlockBuilder(false).add("ifd0", TiffExif.ORIENTATION, 4, 1, new byte[]{0, 0, 0, 6})
                    .build();
            var asLong = ExifBlocks.withOrientation(block, Orientation.ROTATE_90);
            assertEquals(Orientation.ROTATE_90, TiffExif.orientation(asLong, 0));
            var ascii = new ExifBlockBuilder(true).ascii("ifd0", TiffExif.ORIENTATION, "6").build();
            var edited = ExifBlocks.withOrientation(ascii, Orientation.ROTATE_180);
            assertEquals(3, ByteArrays.u16(edited, 8 + 2 + 2, true));
            assertEquals(Orientation.ROTATE_180, TiffExif.orientation(edited, 0));
        }

        @Test
        @DisplayName("rewrites an entry whose values lie outside the block in place, keeping the other entries")
        void outOfBlock() {
            var builder = ExifBlockBuilder.camera(true, 6);
            var block = builder.build();
            var ifd0 = builder.offsets.get("ifd0");
            var entry = -1;
            for (var i = 0; i < ByteArrays.u16(block, ifd0, true); i++) {
                if (ByteArrays.u16(block, ifd0 + 2 + 12 * i, true) == TiffExif.ORIENTATION) {
                    entry = ifd0 + 2 + 12 * i;
                }
            }
            assertTrue(entry > 0);
            // three SHORT values, out of line, at an offset past the end of the block
            ByteArrays.put32(block, entry + 4, 3, true);
            ByteArrays.put32(block, entry + 8, block.length + 100, true);
            var edited = ExifBlocks.withOrientation(block, Orientation.ROTATE_180);
            assertEquals(block.length, edited.length, "the block is patched, not replaced");
            assertEquals(Orientation.ROTATE_180, TiffExif.orientation(edited, 0));
            assertEquals(1, ByteArrays.u32(edited, entry + 4, true));
            for (var index : differences(block, edited)) {
                assertTrue(index >= entry + 4 && index < entry + 12, "byte " + index + " outside the entry changed");
            }
            assertTrue(ExifBlocks.hasGps(edited));
        }

        @Test
        @DisplayName("recognizes the two classic TIFF headers only")
        void tiffHeader() {
            assertTrue(ExifBlocks.isTiff(ExifBlockBuilder.camera(true, 1).build()));
            assertTrue(ExifBlocks.isTiff(ExifBlockBuilder.camera(false, 1).build()));
            assertFalse(ExifBlocks.isTiff(new byte[]{'I', 'I', 42, 0}));
            assertFalse(ExifBlocks.isTiff(new byte[]{'I', 'I', 43, 0, 8, 0, 0, 0}), "BigTIFF");
            assertFalse(ExifBlocks.isTiff(new byte[]{'M', 'M', 42, 0, 0, 0, 0, 8}));
            assertFalse(ExifBlocks.isTiff("Exif\0\0MM".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1)));
        }

        @Test
        @DisplayName("replaces a block that is not TIFF with a minimal one")
        void minimal() {
            for (var garbage : new byte[][]{new byte[0], "not a tiff block".getBytes(), {'I', 'I', 42, 0, 0, 0, 0, 0}}) {
                var edited = ExifBlocks.withOrientation(garbage, Orientation.TRANSPOSE);
                assertEquals(26, edited.length);
                assertEquals(Orientation.TRANSPOSE, TiffExif.orientation(edited, 0));
            }
        }
    }

    @Nested
    @DisplayName("withoutGps")
    class Gps {
        @ParameterizedTest(name = "little-endian {0}")
        @ValueSource(booleans = {true, false})
        @DisplayName("removes the pointer, zeroes the directory and its values and keeps everything else in place")
        void strip(boolean little) {
            var builder = ExifBlockBuilder.camera(little, 6);
            var block = builder.build();
            var latitude = Arrays.copyOfRange(block, builder.offsets.get("gps:2"), builder.offsets.get("gps:2") + 24);
            var edited = ExifBlocks.withoutGps(block);
            assertFalse(ExifBlocks.hasGps(edited));
            assertTrue(ExifBlocks.hasGps(block));
            assertEquals(block.length, edited.length, "the thumbnail ends the block, so nothing is cut");
            assertFalse(contains(edited, latitude), "the position is gone");
            var gps = builder.offsets.get("gps");
            var gpsEnd = builder.offsets.get("ifd1");
            for (var i = gps; i < gpsEnd; i++) {
                assertEquals(0, edited[i], "GPS byte " + i);
            }
            var exif = builder.offsets.get("exif");
            assertArrayEquals(Arrays.copyOfRange(block, exif, gps), Arrays.copyOfRange(edited, exif, gps),
                    "the Exif directory and the maker note keep their bytes and offsets");
            assertArrayEquals(Arrays.copyOfRange(block, gpsEnd, block.length),
                    Arrays.copyOfRange(edited, gpsEnd, block.length), "the thumbnail stays");
            assertEquals(Orientation.ROTATE_90, TiffExif.orientation(edited, 0));
            var ifd0 = builder.offsets.get("ifd0");
            assertEquals(5, ByteArrays.u16(edited, ifd0, little));
            assertEquals(builder.offsets.get("ifd1"), (int) ByteArrays.u32(edited, ifd0 + 2 + 12 * 5, little));
            assertSame(edited, ExifBlocks.withoutGps(edited));
        }

        @Test
        @DisplayName("cuts the GPS bytes that end the block")
        void trimsTail() {
            var builder = ExifBlockBuilder.camera(true, 1);
            var noThumbnail = ExifBlocks.withoutThumbnail(builder.build());
            assertEquals(builder.offsets.get("ifd1"), noThumbnail.length);
            var edited = ExifBlocks.withoutGps(noThumbnail);
            assertEquals(builder.offsets.get("gps"), edited.length);
        }

        @Test
        @DisplayName("keeps a value a remaining directory shares with the GPS directory")
        void shared() {
            var builder = ExifBlockBuilder.camera(true, 1);
            var block = builder.build();
            // point the Exif DateTimeOriginal at the GPS latitude bytes
            var exif = builder.offsets.get("exif");
            var latitude = builder.offsets.get("gps:2");
            ByteArrays.put16(block, exif + 2 + 2, 7, true);
            ByteArrays.put32(block, exif + 2 + 4, 24, true);
            ByteArrays.put32(block, exif + 2 + 8, latitude, true);
            var expected = Arrays.copyOfRange(block, latitude, latitude + 24);
            var edited = ExifBlocks.withoutGps(block);
            assertArrayEquals(expected, Arrays.copyOfRange(edited, latitude, latitude + 24));
        }
    }

    @Nested
    @DisplayName("withoutThumbnail")
    class Thumbnail {
        @ParameterizedTest(name = "little-endian {0}")
        @ValueSource(booleans = {true, false})
        @DisplayName("unlinks the thumbnail directory and cuts it with the thumbnail off the end of the block")
        void strip(boolean little) {
            var builder = ExifBlockBuilder.camera(little, 3);
            var block = builder.build();
            var edited = ExifBlocks.withoutThumbnail(block);
            var ifd1 = builder.offsets.get("ifd1");
            assertEquals(ifd1, edited.length);
            assertArrayEquals(withNextZero(block, builder, little), edited);
            assertTrue(ExifBlocks.hasGps(edited));
            assertSame(edited, ExifBlocks.withoutThumbnail(edited));
        }

        private byte[] withNextZero(byte[] block, ExifBlockBuilder builder, boolean little) {
            var copy = Arrays.copyOf(block, builder.offsets.get("ifd1"));
            var ifd0 = builder.offsets.get("ifd0");
            ByteArrays.put32(copy, ifd0 + 2 + 12 * 6, 0, little);
            return copy;
        }

        @Test
        @DisplayName("zeroes a thumbnail that does not end the block instead of cutting it")
        void middle() {
            var builder = ExifBlockBuilder.camera(true, 1);
            var block = builder.build();
            var trailer = new byte[]{9, 8, 7, 6, 5, 4};
            var extended = ExifBlockBuilder.concat(block, trailer);
            // a maker note value that reaches past the thumbnail keeps the trailer referenced
            var exif = builder.offsets.get("exif");
            var note = exif + 2 + 12 * 1;
            assertEquals(ExifBlockBuilder.MAKER_NOTE, ByteArrays.u16(extended, note, true));
            ByteArrays.put32(extended, note + 4, 6, true);
            ByteArrays.put32(extended, note + 8, block.length, true);
            var edited = ExifBlocks.withoutThumbnail(extended);
            assertEquals(extended.length, edited.length);
            var thumbnail = builder.offsets.get("thumbnail");
            for (var i = thumbnail; i < block.length; i++) {
                assertEquals(0, edited[i]);
            }
            assertArrayEquals(trailer, Arrays.copyOfRange(edited, block.length, edited.length));
        }
    }

    @Nested
    @DisplayName("withPixelDimensions")
    class Dimensions {
        @Test
        @DisplayName("patches the size entries in place and widens a SHORT that cannot hold the value")
        void patch() {
            var builder = ExifBlockBuilder.camera(false, 1);
            var block = builder.build();
            var edited = ExifBlocks.withPixelDimensions(block, 1200, 70000);
            assertEquals(block.length, edited.length);
            var exif = builder.offsets.get("exif");
            var x = exif + 2 + 12 * 2;
            var y = exif + 2 + 12 * 3;
            assertEquals(ExifBlocks.PIXEL_X_DIMENSION, ByteArrays.u16(edited, x, false));
            assertEquals(3, ByteArrays.u16(edited, x + 2, false));
            assertEquals(1200, ByteArrays.u16(edited, x + 8, false));
            assertEquals(4, ByteArrays.u16(edited, y + 2, false));
            assertEquals(70000, ByteArrays.u32(edited, y + 8, false));
            for (var index : differences(block, edited)) {
                assertTrue(index >= x + 2 && index < x + 12 || index >= y + 2 && index < y + 12,
                        "byte " + index + " outside the two entries changed");
            }
            assertSame(edited, ExifBlocks.withPixelDimensions(edited, 1200, 70000));
        }

        @Test
        @DisplayName("leaves a block without the entries or without an Exif directory unchanged")
        void absent() {
            var bare = new ExifBlockBuilder(true).ascii("ifd0", ExifBlockBuilder.MAKE, "X").build();
            assertSame(bare, ExifBlocks.withPixelDimensions(bare, 10, 10));
            var noSize = new ExifBlockBuilder(true).pointer("ifd0", ExifBlocks.EXIF_POINTER, "exif")
                    .ascii("exif", ExifBlockBuilder.DATE_TIME_ORIGINAL, "2026:01:01 00:00:00").build();
            assertSame(noSize, ExifBlocks.withPixelDimensions(noSize, 10, 10));
        }
    }

    @Test
    @DisplayName("never throws and never modifies its input on truncated and mutated blocks")
    void robustness() {
        var random = new Random(20261025L);
        var seeds = new byte[][]{ExifBlockBuilder.camera(true, 6).build(), ExifBlockBuilder.camera(false, 0).build()};
        for (var round = 0; round < 3000; round++) {
            var seed = seeds[round % 2];
            var block = round % 3 == 0 ? Arrays.copyOf(seed, random.nextInt(seed.length + 1)) : seed.clone();
            for (var flips = random.nextInt(8); flips > 0 && block.length > 0; flips--) {
                block[random.nextInt(block.length)] = (byte) random.nextInt(256);
            }
            var copy = block.clone();
            var gps = ExifBlocks.withoutGps(block);
            var thumbnail = ExifBlocks.withoutThumbnail(block);
            var sized = ExifBlocks.withPixelDimensions(block, 100, 200);
            var oriented = ExifBlocks.withOrientation(block, Orientation.ROTATE_270);
            ExifBlocks.hasGps(block);
            assertArrayEquals(copy, block, "round " + round);
            assertNotNull(gps);
            assertNotNull(thumbnail);
            assertNotNull(sized);
            assertTrue(oriented.length >= 8);
            ExifBlocks.withoutGps(thumbnail);
        }
    }
}
