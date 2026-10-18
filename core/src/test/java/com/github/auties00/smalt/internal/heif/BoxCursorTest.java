package com.github.auties00.smalt.internal.heif;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.internal.io.FourCc;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the box cursor shared by the MP4 demuxer (movie rules) and the HEIF parser (item rules).
 */
@DisplayName("BoxCursor")
class BoxCursorTest {
    private static final int PARENT = FourCc.of("moov");

    private static byte[] box(String type, byte[]... parts) {
        var out = new ByteArrayOutputStream();
        var size = 8;
        for (var part : parts) {
            size += part.length;
        }
        out.writeBytes(new byte[]{(byte) (size >>> 24), (byte) (size >>> 16), (byte) (size >>> 8), (byte) size});
        out.writeBytes(type.getBytes(StandardCharsets.ISO_8859_1));
        for (var part : parts) {
            out.writeBytes(part);
        }
        return out.toByteArray();
    }

    private static byte[] concat(byte[]... parts) {
        var out = new ByteArrayOutputStream();
        for (var part : parts) {
            out.writeBytes(part);
        }
        return out.toByteArray();
    }

    private static List<String> children(BoxCursor cursor) {
        var types = new ArrayList<String>();
        while (cursor.hasBox()) {
            types.add(FourCc.toString(cursor.nextBox().type()));
        }
        return types;
    }

    @Nested
    @DisplayName("child lists")
    class Children {
        @Test
        @DisplayName("end at an all-zero header under the movie rules but not under the item rules")
        void terminator() {
            var data = concat(box("free", new byte[2]), new byte[8]);
            assertEquals(List.of("free"), children(BoxCursor.of(data, PARENT)));
            assertEquals(List.of("free", "\0\0\0\0"), children(BoxCursor.strict(data, PARENT)));
        }

        @Test
        @DisplayName("start the payload of a uuid box at its extended type only under the movie rules")
        void uuid() {
            var extended = new byte[16];
            extended[0] = 0x55;
            var data = box("uuid", extended, new byte[]{7});
            var movie = BoxCursor.of(data, PARENT).nextBox();
            assertEquals(17, movie.remaining());
            assertEquals(0x55, movie.u8());
            var item = BoxCursor.strict(data, PARENT).nextBox();
            assertEquals(1, item.remaining());
            assertEquals(7, item.u8());
            var tooShort = box("uuid", new byte[4]);
            assertEquals(4, BoxCursor.of(tooShort, PARENT).nextBox().remaining());
            assertThrows(MediaException.Malformed.class, () -> BoxCursor.strict(tooShort, PARENT).nextBox());
        }

        @Test
        @DisplayName("read 64-bit sizes and extend a zero size to the end of the parent")
        void sizes() {
            var large = concat(new byte[]{0, 0, 0, 1}, "mdat".getBytes(StandardCharsets.ISO_8859_1),
                    new byte[]{0, 0, 0, 0, 0, 0, 0, 18}, new byte[]{1, 2});
            var child = BoxCursor.of(large, PARENT).nextBox();
            assertEquals(FourCc.of("mdat"), child.type());
            assertArrayEquals(new byte[]{1, 2}, child.rest());
            var open = concat(new byte[]{0, 0, 0, 0}, "mdat".getBytes(StandardCharsets.ISO_8859_1), new byte[5]);
            assertEquals(5, BoxCursor.of(open, PARENT).nextBox().remaining());
        }

        @Test
        @DisplayName("reject a child that runs past its parent or is smaller than its header")
        void bounds() {
            var overrun = concat(new byte[]{0, 0, 0, 20}, "free".getBytes(StandardCharsets.ISO_8859_1), new byte[4]);
            var failure = assertThrows(MediaException.Malformed.class, () -> BoxCursor.of(overrun, PARENT).nextBox());
            assertTrue(failure.getMessage().contains("'free'"), failure.getMessage());
            var tiny = concat(new byte[]{0, 0, 0, 4}, "free".getBytes(StandardCharsets.ISO_8859_1));
            assertThrows(MediaException.Malformed.class, () -> BoxCursor.strict(tiny, PARENT).nextBox());
        }
    }

    @Nested
    @DisplayName("fields")
    class Fields {
        @Test
        @DisplayName("read big-endian fields and fail naming the box when one does not fit")
        void fields() {
            var cursor = BoxCursor.of(new byte[]{(byte) 0xFF, (byte) 0xFE, 1, 2, 3, 0, 0, 0, 1, 9}, PARENT);
            assertEquals(-2, cursor.s16());
            assertEquals(0x010203, cursor.u24());
            assertEquals(1, cursor.uN(4));
            assertEquals(1, cursor.remaining());
            var failure = assertThrows(MediaException.Malformed.class, cursor::u16);
            assertTrue(failure.getMessage().contains("'moov'"), failure.getMessage());
            assertThrows(MediaException.Malformed.class, () -> cursor.uN(3));
        }

        @Test
        @DisplayName("read terminated and fixed-length strings")
        void strings() {
            var data = "ab\0cd\0\0xy".getBytes(StandardCharsets.UTF_8);
            var cursor = BoxCursor.of(data, PARENT);
            assertEquals("ab", cursor.string());
            assertEquals("cd", cursor.string(4));
            assertEquals("xy", cursor.string());
            assertEquals(0, cursor.remaining());
        }

        @Test
        @DisplayName("slice and duplicate without moving the other cursor")
        void slices() {
            var cursor = BoxCursor.of(new byte[]{1, 2, 3, 4}, PARENT);
            var copy = cursor.duplicate();
            var slice = cursor.slice(2);
            assertEquals(2, slice.remaining());
            assertEquals(PARENT, slice.type());
            assertEquals(3, cursor.u8());
            assertEquals(1, copy.u8());
            assertFalse(slice.hasBox());
            assertThrows(MediaException.Malformed.class, () -> cursor.position(5));
        }
    }
}
