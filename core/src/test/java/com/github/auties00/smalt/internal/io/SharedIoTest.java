package com.github.auties00.smalt.internal.io;

import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.container.CountingSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Covers the shared byte helpers of {@code internal.io}: the endian accessors against
 * {@link ByteBuffer}, four-character codes, the box writer, sliced sources and bounded reads, with
 * the behaviors of the private copies they replace pinned.
 */
class SharedIoTest {
    @Nested
    @DisplayName("ByteArrays")
    class Endian {
        @ParameterizedTest(name = "little = {0}")
        @ValueSource(booleans = {false, true})
        @DisplayName("reads and writes every width in either order as ByteBuffer does")
        void matchesByteBuffer(boolean little) {
            var random = new Random(little ? 7 : 11);
            var data = new byte[64];
            random.nextBytes(data);
            var buffer = ByteBuffer.wrap(data).order(little ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN);
            for (var offset = 0; offset + 8 <= data.length; offset++) {
                assertEquals(buffer.getShort(offset) & 0xFFFF, ByteArrays.u16(data, offset, little));
                assertEquals(buffer.getInt(offset) & 0xFFFFFFFFL, ByteArrays.u32(data, offset, little));
                assertEquals(buffer.getInt(offset) & 0xFFFFFFFFL,
                        little ? ByteArrays.u32le(data, offset) : ByteArrays.u32be(data, offset));
                var u24 = little ? ByteArrays.u24le(data, offset) : ByteArrays.u24be(data, offset);
                var expected24 = little
                        ? (data[offset] & 0xFF) | (data[offset + 1] & 0xFF) << 8 | (data[offset + 2] & 0xFF) << 16
                        : (data[offset] & 0xFF) << 16 | (data[offset + 1] & 0xFF) << 8 | (data[offset + 2] & 0xFF);
                assertEquals(expected24, u24);
            }
            var out = new byte[16];
            var check = ByteBuffer.allocate(16).order(buffer.order());
            ByteArrays.put16(out, 1, 0x1234_ABCD, little);
            check.putShort(1, (short) 0xABCD);
            ByteArrays.put32(out, 4, 0xF_8765_4321L, little);
            check.putInt(4, 0x87654321);
            assertArrayEquals(check.array(), out);
        }

        @Test
        @DisplayName("writes long-valued and 24-bit fields, ignoring the bits above their width")
        void longPuts() {
            var out = new byte[12];
            ByteArrays.put32be(out, 0, 0x1_FFFF_FFFEL);
            ByteArrays.put32le(out, 4, 0xFFFF_FFFEL);
            ByteArrays.put24be(out, 8, 0x7FA_BCDE);
            assertArrayEquals(new byte[]{-1, -1, -1, -2, -2, -1, -1, -1, (byte) 0xFA, (byte) 0xBC, (byte) 0xDE, 0}, out);
            assertEquals(0xFABCDE, ByteArrays.u24be(out, 8));
            assertEquals(0xFFFF_FFFEL, ByteArrays.u32be(out, 0));
            ByteArrays.put24le(out, 9, 0x7FA_BCDE);
            assertArrayEquals(new byte[]{(byte) 0xFA, (byte) 0xDE, (byte) 0xBC, (byte) 0xFA},
                    Arrays.copyOfRange(out, 8, 12));
            assertEquals(0xFABCDE, ByteArrays.u24le(out, 9));
        }

        @Test
        @DisplayName("throws IndexOutOfBoundsException for a value past the end of the array")
        void bounds() {
            var data = new byte[3];
            assertThrows(IndexOutOfBoundsException.class, () -> ByteArrays.u24be(data, 1));
            assertThrows(IndexOutOfBoundsException.class, () -> ByteArrays.u32be(data, 0));
            assertThrows(IndexOutOfBoundsException.class, () -> ByteArrays.put24be(data, 1, 0));
        }
    }

    @Nested
    @DisplayName("FourCc")
    class FourCcCodes {
        @Test
        @DisplayName("round-trips a code and prints non-printable bytes as question marks")
        void roundTrip() {
            assertEquals(0x6D6F6F76, FourCc.of("moov"));
            assertEquals("moov", FourCc.toString(FourCc.of("moov")));
            assertEquals("©nam", FourCc.toString(FourCc.of("©nam")));
            assertEquals("?nam", FourCc.printable(FourCc.of("©nam")));
            assertEquals("a b?", FourCc.printable(0x61206201));
            assertThrows(IllegalArgumentException.class, () -> FourCc.of("abc"));
        }
    }

    @Nested
    @DisplayName("ByteWriter")
    class Writer {
        @Test
        @DisplayName("writes nested boxes and full boxes with their sizes patched in one pass")
        void boxes() {
            var writer = new ByteWriter(0);
            writer.begin(FourCc.of("moov"));
            writer.beginFull(FourCc.of("mvhd"), 1, 0x00ABCDEF);
            writer.u32be(7);
            writer.end();
            writer.begin(FourCc.of("udta"));
            writer.cString("x");
            writer.end();
            writer.end();
            var bytes = writer.toByteArray();
            assertEquals(bytes.length, ByteArrays.i32be(bytes, 0));
            assertEquals(16, ByteArrays.i32be(bytes, 8));
            assertEquals(0x01ABCDEF, ByteArrays.i32be(bytes, 16));
            assertEquals(10, ByteArrays.i32be(bytes, 24));
            assertEquals("x", new String(bytes, 32, 1));
            assertEquals(0, bytes[33]);
            assertThrows(IllegalStateException.class, writer::end);
        }

        @Test
        @DisplayName("rejects boxes nested deeper than 16 and forgets open boxes on reset")
        void depth() {
            var writer = new ByteWriter(8);
            for (var i = 0; i < 16; i++) {
                writer.begin(FourCc.of("free"));
            }
            assertThrows(IllegalStateException.class, () -> writer.begin(FourCc.of("free")));
            writer.reset();
            assertEquals(0, writer.size());
            assertThrows(IllegalStateException.class, writer::end);
        }

        @Test
        @DisplayName("exposes its buffer up to the written size")
        void buffer() {
            var writer = new ByteWriter(4);
            writer.u16le(0x0102);
            writer.u24be(0x030405);
            assertArrayEquals(new byte[]{2, 1, 3, 4, 5}, Arrays.copyOf(writer.buffer(), writer.size()));
        }
    }

    @Nested
    @DisplayName("SlicedSource")
    class Sliced {
        @Test
        @DisplayName("exposes a bounded range at shifted offsets and ends it with -1")
        void bounded() {
            var data = new byte[100];
            for (var i = 0; i < data.length; i++) {
                data[i] = (byte) i;
            }
            var slice = new SlicedSource(MediaSource.of(data), 10, 20);
            assertEquals(20, slice.size());
            var target = new byte[30];
            assertEquals(20, slice.read(0, target, 0, 30));
            assertEquals(10, target[0]);
            assertEquals(29, target[19]);
            assertEquals(-1, slice.read(20, target, 0, 1));
            assertEquals(0, slice.read(20, target, 0, 0));
            assertThrows(IndexOutOfBoundsException.class, () -> slice.read(-1, target, 0, 1));
        }

        @Test
        @DisplayName("runs to the end of the parent when unbounded")
        void unbounded() {
            var slice = new SlicedSource(MediaSource.of(new byte[]{1, 2, 3, 4}), 1, -1);
            var target = new byte[8];
            assertEquals(3, SourceBytes.readAvailable(slice, 0, target, 0, 8));
            assertArrayEquals(new byte[]{2, 3, 4}, Arrays.copyOf(target, 3));
        }
    }

    @Nested
    @DisplayName("SourceBytes.readAvailable")
    class ReadAvailable {
        @ParameterizedTest(name = "chunk {0}")
        @ValueSource(ints = {1, 3, 64})
        @DisplayName("collects short reads, stops where the source ends and reads nothing at a negative position")
        void shortReads(int chunk) {
            var data = new byte[50];
            Arrays.fill(data, (byte) 9);
            var source = new CountingSource(data, chunk);
            var target = new byte[40];
            assertEquals(30, SourceBytes.readAvailable(source, 20, target, 5, 35));
            assertEquals(0, target[4]);
            assertEquals(9, target[5]);
            assertEquals(0, SourceBytes.readAvailable(source, -4, target, 0, 10));
            assertEquals(0, SourceBytes.readAvailable(source, 50, target, 0, 10));
            assertThrows(IndexOutOfBoundsException.class, () -> SourceBytes.readAvailable(source, 0, target, 35, 10));
        }

        @Test
        @DisplayName("fills the tail of an array as the MP4 scan's readUpTo did")
        void readUpTo() {
            var data = new byte[]{1, 2, 3, 4, 5, 6};
            var target = new byte[8];
            var from = 3;
            var read = SourceBytes.readAvailable(MediaSource.of(data), 2, target, from, target.length - from);
            assertEquals(4, read);
            assertArrayEquals(new byte[]{0, 0, 0, 3, 4, 5, 6, 0}, target);
        }
    }
}
