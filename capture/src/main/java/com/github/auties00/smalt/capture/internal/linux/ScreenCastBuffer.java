package com.github.auties00.smalt.capture.internal.linux;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.capture.internal.convert.RgbLayout;
import com.github.auties00.smalt.internal.limits.LimitChecks;

import java.lang.foreign.MemorySegment;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * Reads the {@code pw_buffer}s of a screen cast into an array of pixels.
 *
 * <p>A buffer holds new pixels in shared memory, which PipeWire mapped, or in a DMA-BUF, which the
 * {@link DmaBufMapper} maps. PipeWire's documentation tells a consumer to ignore the sizes of a
 * DMA-BUF's data and chunk, which a producer may leave at zero, while compositors also send buffers
 * with a chunk of size zero that carry only pointer metadata. So a DMA-BUF chunk of size zero is new
 * pixels only until the producer has been seen to fill the size, and a DMA-BUF chunk's offset is
 * taken as it is, while shared memory's is taken modulo the data's size. Rows laid out bottom-up
 * (a negative stride) are not read. A buffer can also carry just pointer metadata; then the last
 * pixels are kept, and shown with the pointer moved.
 *
 * <p>Instances are not thread-safe.
 */
final class ScreenCastBuffer implements AutoCloseable {
    /**
     * The offsets of {@code spa_chunk.offset}, {@code size}, {@code stride} and {@code flags}.
     */
    private static final long CHUNK_OFFSET = 0, CHUNK_SIZE = 4, CHUNK_STRIDE = 8, CHUNK_FLAGS = 12;

    /**
     * The most metadata entries read from a buffer; PipeWire defines far fewer types.
     */
    private static final int MAX_METAS = 64;

    private final DmaBufMapper mapper;

    /**
     * The pointer of a metadata cast, or {@code null} when the frames do not need one drawn.
     */
    private final ScreenCastCursor cursor;

    /**
     * The pixels of the last frame.
     */
    private byte[] pixels = new byte[0];

    /**
     * The row stride of {@link #pixels}.
     */
    private int stride;

    /**
     * The width of the frame in {@link #pixels}, or {@code 0} before the first.
     */
    private int frameWidth;

    /**
     * The height of the frame in {@link #pixels}.
     */
    private int frameHeight;

    /**
     * Whether a DMA-BUF chunk had a size, so that this producer's size zero means no pixels.
     */
    private boolean dmaBufSizes;

    /**
     * Whether the pointer changed since the last frame shown.
     */
    private boolean cursorChanged;

    /**
     * Creates a reader.
     *
     * @param mapper the mapper of DMA-BUFs, closed with this reader
     * @param cursor the pointer to follow in the buffers' metadata, or {@code null} for none
     */
    ScreenCastBuffer(DmaBufMapper mapper, ScreenCastCursor cursor) {
        this.mapper = mapper;
        this.cursor = cursor;
    }

    /**
     * Applies a buffer's pointer metadata and tells whether it holds new pixels. Every dequeued buffer
     * passes here in order, also those whose pixels are skipped, since the pointer's image is sent only
     * when it changes.
     *
     * @param buffer the {@code pw_buffer}
     * @return whether the buffer holds new pixels
     */
    boolean inspect(MemorySegment buffer) {
        var spa = spa(buffer);
        if (cursor != null) {
            cursorChanged |= updateCursor(spa);
        }
        if (spa.get(JAVA_INT, PipeWire.BUFFER_N_DATAS) < 1) {
            return false;
        }
        var datum = spa.get(ADDRESS, PipeWire.BUFFER_DATAS).reinterpret(PipeWire.SPA_DATA.byteSize());
        var chunk = datum.get(ADDRESS, PipeWire.DATA_CHUNK);
        if (chunk.equals(MemorySegment.NULL)) {
            return false;
        }
        chunk = chunk.reinterpret(PipeWire.SPA_CHUNK.byteSize());
        if ((chunk.get(JAVA_INT, CHUNK_FLAGS) & (PipeWire.CHUNK_CORRUPTED | PipeWire.CHUNK_EMPTY)) != 0) {
            return false;
        }
        var filled = chunk.get(JAVA_INT, CHUNK_SIZE) != 0;
        if (datum.get(JAVA_INT, PipeWire.DATA_TYPE) != PipeWire.DATA_DMA_BUF) {
            return filled;
        }
        dmaBufSizes |= filled;
        return filled || !dmaBufSizes;
    }

    /**
     * Prepares a frame.
     *
     * @param buffer the {@code pw_buffer} with new pixels, or {@link MemorySegment#NULL} when no
     * buffer {@linkplain #inspect had any}
     * @param width the width of the negotiated format
     * @param height the height of the negotiated format
     * @return whether a frame should be shown: new pixels were read, or the pointer changed over the
     * pixels of the last frame, which have this size
     */
    boolean read(MemorySegment buffer, int width, int height) {
        var fresh = !buffer.equals(MemorySegment.NULL) && readPixels(buffer, width, height);
        if (!fresh && (!cursorChanged || frameWidth != width || frameHeight != height)) {
            return false;
        }
        cursorChanged = false;
        return true;
    }

    /**
     * Forgets the last frame's pixels, which a pointer change must not show again after the stream's
     * pixel format changed, since they are in the old format's layout.
     */
    void forgetFrame() {
        frameWidth = 0;
        frameHeight = 0;
    }

    /**
     * Returns the {@code spa_buffer} of a {@code pw_buffer}.
     */
    private static MemorySegment spa(MemorySegment buffer) {
        return buffer.reinterpret(8).get(ADDRESS, 0).reinterpret(PipeWire.SPA_BUFFER.byteSize());
    }

    /**
     * Applies the buffer's pointer metadata, if it has any.
     *
     * @return whether the pointer changed
     */
    private boolean updateCursor(MemorySegment spa) {
        var count = spa.get(JAVA_INT, PipeWire.BUFFER_N_METAS);
        if (count <= 0 || count > MAX_METAS) {
            return false;
        }
        var metas = spa.get(ADDRESS, PipeWire.BUFFER_METAS).reinterpret(count * PipeWire.SPA_META.byteSize());
        for (var index = 0; index < count; index++) {
            var at = index * PipeWire.SPA_META.byteSize();
            if (metas.get(JAVA_INT, at + PipeWire.META_TYPE) == SpaPod.META_CURSOR) {
                var data = metas.get(ADDRESS, at + PipeWire.META_DATA);
                var size = Integer.toUnsignedLong(metas.get(JAVA_INT, at + PipeWire.META_SIZE));
                return !data.equals(MemorySegment.NULL) && cursor.update(data.reinterpret(size));
            }
        }
        return false;
    }

    /**
     * Copies a buffer's pixels, if they hold a frame of the format's size.
     *
     * @return whether they do
     */
    private boolean readPixels(MemorySegment buffer, int width, int height) {
        var datum = spa(buffer).get(ADDRESS, PipeWire.BUFFER_DATAS).reinterpret(PipeWire.SPA_DATA.byteSize());
        var type = datum.get(JAVA_INT, PipeWire.DATA_TYPE);
        var chunk = datum.get(ADDRESS, PipeWire.DATA_CHUNK).reinterpret(PipeWire.SPA_CHUNK.byteSize());
        var maxSize = Integer.toUnsignedLong(datum.get(JAVA_INT, PipeWire.DATA_MAXSIZE));
        var offset = Integer.toUnsignedLong(chunk.get(JAVA_INT, CHUNK_OFFSET));
        var size = Integer.toUnsignedLong(chunk.get(JAVA_INT, CHUNK_SIZE));
        var rowStride = chunk.get(JAVA_INT, CHUNK_STRIDE);
        if (rowStride == 0) {
            rowStride = 4 * width;
        }
        var needed = (long) rowStride * (height - 1) + 4L * width;
        if (rowStride < 4L * width || needed > Integer.MAX_VALUE - 16) {
            return false;
        }
        if (type == PipeWire.DATA_DMA_BUF) {
            var fd = (int) datum.get(JAVA_LONG, PipeWire.DATA_FD);
            if (fd < 0) {
                return false;
            }
            var mapOffset = Integer.toUnsignedLong(datum.get(JAVA_INT, PipeWire.DATA_MAPOFFSET));
            var mapped = mapper.map(buffer.address(), fd, mapOffset + maxSize);
            var start = mapOffset + offset;
            if (start + needed > mapped.byteSize()) {
                return false;
            }
            ensureCapacity((int) needed);
            mapper.beginRead(fd);
            try {
                MemorySegment.copy(mapped, JAVA_BYTE, start, pixels, 0, (int) needed);
            } finally {
                mapper.endRead(fd);
            }
        } else if (type == PipeWire.DATA_MEM_PTR || type == PipeWire.DATA_MEM_FD) {
            var base = datum.get(ADDRESS, PipeWire.DATA_DATA);
            if (maxSize != 0) {
                offset %= maxSize;
            }
            if (base.equals(MemorySegment.NULL) || size < needed || offset + needed > maxSize) {
                return false;
            }
            ensureCapacity((int) needed);
            MemorySegment.copy(base.reinterpret(maxSize), JAVA_BYTE, offset, pixels, 0, (int) needed);
        } else {
            return false;
        }
        stride = rowStride;
        frameWidth = width;
        frameHeight = height;
        return true;
    }

    /**
     * Grows the array of pixels.
     */
    private void ensureCapacity(int bytes) {
        if (pixels.length < bytes) {
            LimitChecks.checkAllocation(MediaLimits.DEFAULT, bytes);
            pixels = new byte[bytes];
        }
    }

    /**
     * Returns the pixels of the last frame.
     *
     * @return the array, which may be longer than the frame
     */
    byte[] pixels() {
        return pixels;
    }

    /**
     * Returns the row stride of {@link #pixels()}.
     *
     * @return the stride in bytes
     */
    int stride() {
        return stride;
    }

    /**
     * Draws the pointer over the pixels, for one conversion; {@link #eraseCursor()} ends it, so that
     * the next pointer position is drawn over the clean frame.
     *
     * @param width the width of the frame
     * @param height the height of the frame
     * @param layout the positions of the color samples in a pixel
     */
    void drawCursor(int width, int height, RgbLayout layout) {
        if (cursor != null) {
            cursor.draw(pixels, stride, layout, width, height);
        }
    }

    /**
     * Restores the pixels {@link #drawCursor} covered.
     */
    void eraseCursor() {
        if (cursor != null) {
            cursor.erase(pixels);
        }
    }

    /**
     * Forgets a buffer the stream removed.
     *
     * @param buffer the {@code pw_buffer}
     */
    void remove(MemorySegment buffer) {
        mapper.release(buffer.address());
    }

    /**
     * Unmaps every buffer.
     */
    @Override
    public void close() {
        mapper.close();
    }
}
