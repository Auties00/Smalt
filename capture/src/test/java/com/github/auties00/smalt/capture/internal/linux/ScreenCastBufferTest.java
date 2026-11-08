package com.github.auties00.smalt.capture.internal.linux;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.capture.internal.convert.RgbLayout;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Feeds {@link ScreenCastBuffer} structures laid out by hand as spa/buffer/buffer.h, spa/buffer/meta.h
 * and pipewire/stream.h define them, in native memory. Files stand in for DMA-BUFs, since both are a
 * descriptor to map, and reject the synchronization ioctl as a non-DMA-BUF does; the live test runs
 * real DMA-BUFs.
 */
@DisplayName("screen cast buffers")
@EnabledOnOs(value = OS.LINUX, disabledReason = "[platform:linux] the Linux backend runs only on Linux")
class ScreenCastBufferTest {
    private static final int WIDTH = 4;
    private static final int HEIGHT = 2;
    private static final int STRIDE = 24;

    private Arena arena;
    private Posix posix;
    private DmaBufMapper mapper;

    @BeforeEach
    void open() {
        arena = Arena.ofConfined();
        posix = new Posix();
        mapper = new DmaBufMapper(posix, arena);
    }

    @AfterEach
    void close() {
        mapper.close();
        arena.close();
    }

    /** A pw_buffer with its spa_buffer, one spa_data, its chunk and, when given, a cursor metadata entry. */
    private final class Fake {
        final MemorySegment pwBuffer = arena.allocate(32, 8);
        final MemorySegment spa = arena.allocate(PipeWire.SPA_BUFFER);
        final MemorySegment data = arena.allocate(PipeWire.SPA_DATA);
        final MemorySegment chunk = arena.allocate(PipeWire.SPA_CHUNK);

        Fake(int type, long fd, MemorySegment memory, int mapOffset, int maxSize) {
            pwBuffer.set(ADDRESS, 0, spa);
            spa.set(JAVA_INT, PipeWire.BUFFER_N_DATAS, 1);
            spa.set(ADDRESS, PipeWire.BUFFER_DATAS, data);
            data.set(JAVA_INT, PipeWire.DATA_TYPE, type);
            data.set(JAVA_LONG, PipeWire.DATA_FD, fd);
            data.set(JAVA_INT, PipeWire.DATA_MAPOFFSET, mapOffset);
            data.set(JAVA_INT, PipeWire.DATA_MAXSIZE, maxSize);
            data.set(ADDRESS, PipeWire.DATA_DATA, memory);
            data.set(ADDRESS, PipeWire.DATA_CHUNK, chunk);
        }

        Fake chunk(int offset, int size, int stride, int flags) {
            chunk.set(JAVA_INT, 0, offset);
            chunk.set(JAVA_INT, 4, size);
            chunk.set(JAVA_INT, 8, stride);
            chunk.set(JAVA_INT, 12, flags);
            return this;
        }

        Fake cursor(int id, int x, int y) {
            var meta = arena.allocate(PipeWire.SPA_META, 2);
            var body = arena.allocate(28, 4);
            body.set(JAVA_INT, 0, id);
            body.set(JAVA_INT, 8, x);
            body.set(JAVA_INT, 12, y);
            meta.setAtIndex(JAVA_INT, 0, 1);
            meta.setAtIndex(JAVA_INT, 1, 16);
            var second = PipeWire.SPA_META.byteSize();
            meta.set(JAVA_INT, second + PipeWire.META_TYPE, SpaPod.META_CURSOR);
            meta.set(JAVA_INT, second + PipeWire.META_SIZE, 28);
            meta.set(ADDRESS, second + PipeWire.META_DATA, body);
            spa.set(JAVA_INT, PipeWire.BUFFER_N_METAS, 2);
            spa.set(ADDRESS, PipeWire.BUFFER_METAS, meta);
            return this;
        }
    }

    private MemorySegment memory(byte[] bytes) {
        var memory = arena.allocate(bytes.length, 8);
        MemorySegment.copy(bytes, 0, memory, JAVA_BYTE, 0, bytes.length);
        return memory;
    }

    /** Rows of STRIDE bytes starting at an offset, every byte unique in its row. */
    private static byte[] picture(int offset, int size) {
        var bytes = new byte[size];
        for (var y = 0; y < HEIGHT; y++) {
            for (var x = 0; x < STRIDE; x++) {
                bytes[offset + y * STRIDE + x] = (byte) (10 * y + x + 1);
            }
        }
        return bytes;
    }

    private static void assertPicture(ScreenCastBuffer frames) {
        assertEquals(STRIDE, frames.stride());
        for (var y = 0; y < HEIGHT; y++) {
            for (var x = 0; x < 4 * WIDTH; x++) {
                assertEquals((byte) (10 * y + x + 1), frames.pixels()[y * STRIDE + x], x + "," + y);
            }
        }
    }

    private int openFile(Path file) {
        var fd = posix.open(file.toString(), 0);
        assertTrue(fd >= 0);
        return fd;
    }

    @Test
    @DisplayName("shared memory is read at the chunk's offset, modulo the data's size, with the chunk's stride")
    void sharedMemory() {
        var frames = new ScreenCastBuffer(mapper, null);
        var memory = memory(picture(16, 16 + 2 * STRIDE));
        var size = 16 + 2 * STRIDE;
        for (var type : new int[]{PipeWire.DATA_MEM_PTR, PipeWire.DATA_MEM_FD}) {
            var buffer = new Fake(type, -1, memory, 0, size).chunk(size + 16, 2 * STRIDE, STRIDE, 0);
            assertTrue(frames.inspect(buffer.pwBuffer));
            assertTrue(frames.read(buffer.pwBuffer, WIDTH, HEIGHT));
            assertPicture(frames);
        }
    }

    @Test
    @DisplayName("shared memory that cannot hold the frame, a null pointer and other types are no frame")
    void sharedMemoryRefused() {
        var frames = new ScreenCastBuffer(mapper, null);
        var memory = memory(new byte[2 * STRIDE]);
        var small = new Fake(PipeWire.DATA_MEM_FD, -1, memory, 0, 2 * STRIDE);
        assertFalse(frames.read(small.chunk(0, STRIDE, STRIDE, 0).pwBuffer, WIDTH, HEIGHT), "the chunk is too small");
        assertFalse(frames.read(small.chunk(STRIDE, 2 * STRIDE, STRIDE, 0).pwBuffer, WIDTH, HEIGHT), "the offset leaves the data");
        assertFalse(frames.read(small.chunk(0, 2 * STRIDE, 3, 0).pwBuffer, WIDTH, HEIGHT), "a stride shorter than a row");
        assertFalse(frames.read(small.chunk(0, 2 * STRIDE, -STRIDE, 0).pwBuffer, WIDTH, HEIGHT), "rows laid out bottom-up");
        var unmapped = new Fake(PipeWire.DATA_MEM_FD, -1, MemorySegment.NULL, 0, 2 * STRIDE).chunk(0, 2 * STRIDE, STRIDE, 0);
        assertFalse(frames.read(unmapped.pwBuffer, WIDTH, HEIGHT));
        var other = new Fake(4, -1, memory, 0, 2 * STRIDE).chunk(0, 2 * STRIDE, STRIDE, 0);
        assertFalse(frames.read(other.pwBuffer, WIDTH, HEIGHT));
    }

    @Test
    @DisplayName("corrupted, empty or unfilled shared memory chunks hold no pixels")
    void noPixels() {
        var frames = new ScreenCastBuffer(mapper, null);
        var buffer = new Fake(PipeWire.DATA_MEM_FD, -1, memory(new byte[2 * STRIDE]), 0, 2 * STRIDE);
        assertTrue(frames.inspect(buffer.chunk(0, 2 * STRIDE, STRIDE, 0).pwBuffer));
        assertFalse(frames.inspect(buffer.chunk(0, 2 * STRIDE, STRIDE, PipeWire.CHUNK_CORRUPTED).pwBuffer));
        assertFalse(frames.inspect(buffer.chunk(0, 2 * STRIDE, STRIDE, PipeWire.CHUNK_EMPTY).pwBuffer));
        assertFalse(frames.inspect(buffer.chunk(0, 0, STRIDE, 0).pwBuffer));
        assertFalse(frames.read(MemorySegment.NULL, WIDTH, HEIGHT), "no buffer, no frame");
    }

    @Test
    @DisplayName("a DMA-BUF is mapped once, read at the map offset plus the chunk's offset, and unmapped when removed")
    void dmaBuf(@TempDir Path directory) throws IOException {
        var file = directory.resolve("dmabuf");
        Files.write(file, picture(4096 + 32, 8192));
        var fd = openFile(file);
        try {
            var frames = new ScreenCastBuffer(mapper, null);
            var buffer = new Fake(PipeWire.DATA_DMA_BUF, fd, MemorySegment.NULL, 4096, 4096).chunk(32, 2 * STRIDE, STRIDE, 0);
            assertTrue(frames.inspect(buffer.pwBuffer));
            assertTrue(frames.read(buffer.pwBuffer, WIDTH, HEIGHT));
            assertPicture(frames);
            assertEquals(1, mapper.mapped());
            assertTrue(frames.read(buffer.pwBuffer, WIDTH, HEIGHT));
            assertEquals(1, mapper.mapped(), "the mapping is kept between frames");
            assertNotEquals(0, mapper.beginRead(fd), "a descriptor that is no DMA-BUF is asked, and refuses");
            assertNotEquals(0, mapper.endRead(fd));
            frames.remove(buffer.pwBuffer);
            assertEquals(0, mapper.mapped());
            frames.remove(buffer.pwBuffer);
        } finally {
            posix.close(fd);
        }
    }

    @Test
    @DisplayName("a read-only descriptor can be mapped, and the sizes of a DMA-BUF may be zero, the file's size counting instead")
    void dmaBufSizesZero(@TempDir Path directory) throws IOException {
        var file = directory.resolve("dmabuf");
        Files.write(file, picture(32, 32 + 2 * STRIDE));
        var fd = openFile(file);
        try {
            var frames = new ScreenCastBuffer(mapper, null);
            var buffer = new Fake(PipeWire.DATA_DMA_BUF, fd, MemorySegment.NULL, 0, 0).chunk(32, 0, STRIDE, 0);
            assertTrue(frames.inspect(buffer.pwBuffer), "a producer that never fills the size sends pixels with size 0");
            assertTrue(frames.read(buffer.pwBuffer, WIDTH, HEIGHT));
            assertPicture(frames);
            assertTrue(frames.inspect(buffer.pwBuffer), "still");
            assertFalse(frames.inspect(buffer.chunk(32, 0, STRIDE, PipeWire.CHUNK_CORRUPTED).pwBuffer), "the flags still count");
        } finally {
            posix.close(fd);
        }
    }

    @Test
    @DisplayName("once a producer filled a DMA-BUF chunk's size, size 0 means no pixels")
    void dmaBufSizesFilled(@TempDir Path directory) throws IOException {
        var file = directory.resolve("dmabuf");
        Files.write(file, picture(0, 2 * STRIDE));
        var fd = openFile(file);
        try {
            var frames = new ScreenCastBuffer(mapper, null);
            var buffer = new Fake(PipeWire.DATA_DMA_BUF, fd, MemorySegment.NULL, 0, 2 * STRIDE);
            assertTrue(frames.inspect(buffer.chunk(0, 2 * STRIDE, STRIDE, 0).pwBuffer));
            assertFalse(frames.inspect(buffer.chunk(0, 0, STRIDE, 0).pwBuffer));
        } finally {
            posix.close(fd);
        }
    }

    @Test
    @DisplayName("a DMA-BUF too small for the frame is no frame, and one that cannot be mapped fails")
    void dmaBufRefused(@TempDir Path directory) throws IOException {
        var file = directory.resolve("dmabuf");
        Files.write(file, new byte[STRIDE]);
        var fd = openFile(file);
        try {
            var frames = new ScreenCastBuffer(mapper, null);
            var small = new Fake(PipeWire.DATA_DMA_BUF, fd, MemorySegment.NULL, 0, 2 * STRIDE).chunk(0, 2 * STRIDE, STRIDE, 0);
            assertFalse(frames.read(small.pwBuffer, WIDTH, HEIGHT));
            var negative = new Fake(PipeWire.DATA_DMA_BUF, -1, MemorySegment.NULL, 0, 2 * STRIDE).chunk(0, 2 * STRIDE, STRIDE, 0);
            assertFalse(frames.read(negative.pwBuffer, WIDTH, HEIGHT));
            var closed = new Fake(PipeWire.DATA_DMA_BUF, 1_000_000, MemorySegment.NULL, 0, 2 * STRIDE).chunk(0, 2 * STRIDE, STRIDE, 0);
            assertThrows(MediaException.Io.class, () -> frames.read(closed.pwBuffer, WIDTH, HEIGHT));
            var sizeless = new Fake(PipeWire.DATA_DMA_BUF, 1_000_000, MemorySegment.NULL, 0, 0).chunk(0, 0, STRIDE, 0);
            assertThrows(MediaException.Io.class, () -> frames.read(sizeless.pwBuffer, WIDTH, HEIGHT), "no size from the descriptor or the data");
            var bottomUp = new Fake(PipeWire.DATA_DMA_BUF, fd, MemorySegment.NULL, 0, 2 * STRIDE).chunk(0, 2 * STRIDE, -STRIDE, 0);
            assertFalse(frames.read(bottomUp.pwBuffer, WIDTH, 1));
        } finally {
            posix.close(fd);
        }
    }

    @Test
    @DisplayName("a DMA-BUF chunk's offset is not taken modulo the data's size, which a consumer ignores")
    void dmaBufOffsetPastMaxSize(@TempDir Path directory) throws IOException {
        var file = directory.resolve("dmabuf");
        Files.write(file, picture(4096, 4096 + 2 * STRIDE));
        var fd = openFile(file);
        try {
            var frames = new ScreenCastBuffer(mapper, null);
            var buffer = new Fake(PipeWire.DATA_DMA_BUF, fd, MemorySegment.NULL, 0, 2 * STRIDE).chunk(4096, 2 * STRIDE, STRIDE, 0);
            assertTrue(frames.read(buffer.pwBuffer, WIDTH, HEIGHT));
            assertPicture(frames);
        } finally {
            posix.close(fd);
        }
    }

    @Test
    @DisplayName("every buffer keeps its own mapping, past the initial table and across the removal of another")
    void manyMappings(@TempDir Path directory) throws IOException {
        var file = directory.resolve("dmabuf");
        Files.write(file, picture(0, 2 * STRIDE));
        var fd = openFile(file);
        try {
            var frames = new ScreenCastBuffer(mapper, null);
            var buffers = new ArrayList<Fake>();
            var mappings = new ArrayList<Long>();
            for (var index = 0; index < 20; index++) {
                var buffer = new Fake(PipeWire.DATA_DMA_BUF, fd, MemorySegment.NULL, 0, 2 * STRIDE).chunk(0, 2 * STRIDE, STRIDE, 0);
                assertTrue(frames.read(buffer.pwBuffer, WIDTH, HEIGHT));
                buffers.add(buffer);
                mappings.add(mapper.map(buffer.pwBuffer.address(), fd, 0).address());
            }
            assertEquals(20, mapper.mapped());
            frames.remove(buffers.get(3).pwBuffer);
            assertEquals(19, mapper.mapped());
            for (var index = 0; index < 20; index++) {
                if (index != 3) {
                    assertEquals(mappings.get(index), mapper.map(buffers.get(index).pwBuffer.address(), fd, 0).address(), "buffer " + index);
                }
            }
            assertEquals(19, mapper.mapped(), "nothing was mapped again");
            frames.close();
            assertEquals(0, mapper.mapped());
        } finally {
            posix.close(fd);
        }
    }

    @Test
    @DisplayName("after a change of pixel format, a pointer change waits for pixels of the new format")
    void forgottenFrame() {
        var frames = new ScreenCastBuffer(mapper, new ScreenCastCursor());
        var memory = memory(picture(0, 2 * STRIDE));
        var full = new Fake(PipeWire.DATA_MEM_FD, -1, memory, 0, 2 * STRIDE).chunk(0, 2 * STRIDE, STRIDE, 0).cursor(1, 1, 1);
        assertTrue(frames.inspect(full.pwBuffer));
        assertTrue(frames.read(full.pwBuffer, WIDTH, HEIGHT));

        frames.forgetFrame();
        var moved = new Fake(PipeWire.DATA_MEM_FD, -1, memory, 0, 2 * STRIDE).chunk(0, 0, STRIDE, 0).cursor(1, 2, 1);
        assertFalse(frames.inspect(moved.pwBuffer));
        assertFalse(frames.read(MemorySegment.NULL, WIDTH, HEIGHT), "those pixels are of the old format");

        assertTrue(frames.read(full.pwBuffer, WIDTH, HEIGHT));
        var movedAgain = new Fake(PipeWire.DATA_MEM_FD, -1, memory, 0, 2 * STRIDE).chunk(0, 0, STRIDE, 0).cursor(1, 3, 1);
        assertFalse(frames.inspect(movedAgain.pwBuffer));
        assertTrue(frames.read(MemorySegment.NULL, WIDTH, HEIGHT));
    }

    @Test
    @DisplayName("the ioctl number is _IOW('b', 0, struct dma_buf_sync) and its flags those of linux/dma-buf.h")
    void syncConstants() {
        assertEquals(1L << 30 | 8L << 16 | 'b' << 8, DmaBufMapper.IOCTL_SYNC);
        assertEquals(1L, DmaBufMapper.SYNC_START_READ);
        assertEquals(5L, DmaBufMapper.SYNC_END_READ);
    }

    @Test
    @DisplayName("a pointer change over the last pixels asks for a frame, which erases the pointer again")
    void cursorOnly() {
        var frames = new ScreenCastBuffer(mapper, new ScreenCastCursor());
        var memory = memory(picture(0, 2 * STRIDE));
        var empty = new Fake(PipeWire.DATA_MEM_FD, -1, memory, 0, 2 * STRIDE).chunk(0, 0, STRIDE, 0).cursor(1, 1, 1);
        assertFalse(frames.inspect(empty.pwBuffer));
        assertFalse(frames.read(MemorySegment.NULL, WIDTH, HEIGHT), "no pixels yet to show the pointer over");

        var full = new Fake(PipeWire.DATA_MEM_FD, -1, memory, 0, 2 * STRIDE).chunk(0, 2 * STRIDE, STRIDE, 0).cursor(1, 1, 1);
        assertTrue(frames.inspect(full.pwBuffer));
        assertTrue(frames.read(full.pwBuffer, WIDTH, HEIGHT));

        var same = new Fake(PipeWire.DATA_MEM_FD, -1, memory, 0, 2 * STRIDE).chunk(0, 0, STRIDE, 0).cursor(1, 1, 1);
        assertFalse(frames.inspect(same.pwBuffer));
        assertFalse(frames.read(MemorySegment.NULL, WIDTH, HEIGHT), "nothing changed");

        var moved = new Fake(PipeWire.DATA_MEM_FD, -1, memory, 0, 2 * STRIDE).chunk(0, 0, STRIDE, 0).cursor(1, 2, 1);
        assertFalse(frames.inspect(moved.pwBuffer));
        assertFalse(frames.read(MemorySegment.NULL, WIDTH + 2, HEIGHT), "the format changed since those pixels");
        var movedAgain = new Fake(PipeWire.DATA_MEM_FD, -1, memory, 0, 2 * STRIDE).chunk(0, 0, STRIDE, 0).cursor(1, 3, 1);
        assertFalse(frames.inspect(movedAgain.pwBuffer));
        assertTrue(frames.read(MemorySegment.NULL, WIDTH, HEIGHT));
        assertFalse(frames.read(MemorySegment.NULL, WIDTH, HEIGHT), "the change is shown once");
        frames.drawCursor(WIDTH, HEIGHT, RgbLayout.BGRX);
        frames.eraseCursor();
        assertPicture(frames);
    }

    @Test
    @DisplayName("without a pointer to follow, metadata is ignored")
    void noCursor() {
        var frames = new ScreenCastBuffer(mapper, null);
        var memory = memory(picture(0, 2 * STRIDE));
        var buffer = new Fake(PipeWire.DATA_MEM_FD, -1, memory, 0, 2 * STRIDE).chunk(0, 2 * STRIDE, STRIDE, 0).cursor(1, 1, 1);
        assertTrue(frames.inspect(buffer.pwBuffer));
        assertTrue(frames.read(buffer.pwBuffer, WIDTH, HEIGHT));
        var moved = new Fake(PipeWire.DATA_MEM_FD, -1, memory, 0, 2 * STRIDE).chunk(0, 0, STRIDE, 0).cursor(1, 2, 2);
        assertFalse(frames.inspect(moved.pwBuffer));
        assertFalse(frames.read(MemorySegment.NULL, WIDTH, HEIGHT));
        var before = frames.pixels().clone();
        frames.drawCursor(WIDTH, HEIGHT, RgbLayout.BGRX);
        assertArrayEquals(before, frames.pixels());
        frames.eraseCursor();
    }
}
