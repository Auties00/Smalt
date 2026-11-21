package com.github.auties00.smalt.capture.internal.linux;

import com.github.auties00.smalt.MediaException;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.Arrays;

import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * Maps the DMA-BUFs of a stream's buffers for reading and brackets every read with the kernel's
 * cache synchronization.
 *
 * <p>A descriptor is mapped whole and read-only the first time its buffer is read, sized through
 * the descriptor since a producer may leave the buffer's own sizes at zero, and stays mapped until
 * the buffer is removed, because mapping costs page table work on every frame otherwise. Instances
 * are not thread-safe.
 *
 * @see <a href="https://docs.kernel.org/driver-api/dma-buf.html">Buffer Sharing and Synchronization</a>
 */
final class DmaBufMapper implements AutoCloseable {
    /**
     * The {@code DMA_BUF_IOCTL_SYNC} request: {@code _IOW('b', 0, struct dma_buf_sync)}, the same
     * number on every Linux architecture that uses the generic ioctl encoding.
     */
    static final long IOCTL_SYNC = 0x40086200L;

    /**
     * The {@code DMA_BUF_SYNC_READ} and {@code DMA_BUF_SYNC_START} flags.
     */
    static final long SYNC_START_READ = 1;

    /**
     * The {@code DMA_BUF_SYNC_READ} and {@code DMA_BUF_SYNC_END} flags.
     */
    static final long SYNC_END_READ = 1 | 4;

    /**
     * How many times a sync that answers {@code EAGAIN} is repeated, as the kernel asks for.
     */
    private static final int SYNC_ATTEMPTS = 64;

    private final Posix posix;

    /**
     * The {@code struct dma_buf_sync} argument.
     */
    private final MemorySegment syncArgument;

    /**
     * The keys of the mappings; the first {@link #count} entries are used.
     */
    private long[] keys = new long[8];

    /**
     * The mappings, parallel to {@link #keys}.
     */
    private MemorySegment[] mappings = new MemorySegment[8];

    private int count;

    /**
     * Creates a mapper.
     *
     * @param posix the C library, used only by the thread that uses this mapper
     * @param arena the arena of the call arguments, alive as long as this mapper
     */
    DmaBufMapper(Posix posix, Arena arena) {
        this.posix = posix;
        this.syncArgument = arena.allocate(JAVA_LONG);
    }

    /**
     * Returns a buffer's DMA-BUF as read-only memory, mapping it if it is not yet.
     *
     * @param key identifies the buffer until {@link #release(long)}
     * @param fd the descriptor
     * @param expectedSize the size to assume when the descriptor cannot report one, or {@code 0} for none
     * @return the whole file
     * @throws MediaException.Io if the descriptor cannot be mapped
     */
    MemorySegment map(long key, int fd, long expectedSize) {
        for (var index = 0; index < count; index++) {
            if (keys[index] == key) {
                return mappings[index];
            }
        }
        var size = posix.fileSize(fd);
        if (size <= 0) {
            size = expectedSize;
        }
        if (size <= 0) {
            throw new MediaException.Io("cannot tell the size of a DMA-BUF of the screen cast",
                    Posix.failure("lseek", posix.errno()));
        }
        var mapping = posix.mmapReadOnly(fd, size, 0);
        if (mapping == null) {
            throw new MediaException.Io("cannot map a DMA-BUF of the screen cast", Posix.failure("mmap", posix.errno()));
        }
        if (count == keys.length) {
            keys = Arrays.copyOf(keys, count * 2);
            mappings = Arrays.copyOf(mappings, count * 2);
        }
        keys[count] = key;
        mappings[count++] = mapping;
        return mapping;
    }

    /**
     * Unmaps a buffer's DMA-BUF, if it is mapped.
     *
     * @param key the buffer
     */
    void release(long key) {
        for (var index = 0; index < count; index++) {
            if (keys[index] == key) {
                posix.munmap(mappings[index]);
                count--;
                keys[index] = keys[count];
                mappings[index] = mappings[count];
                mappings[count] = null;
                return;
            }
        }
    }

    /**
     * Returns the number of mapped buffers.
     *
     * @return the number
     */
    int mapped() {
        return count;
    }

    /**
     * Starts a read of a DMA-BUF, waiting for the fences of the device writing it and making the
     * processor's view coherent. Descriptors that are not DMA-BUFs refuse the request, and their
     * memory needs no synchronization.
     *
     * @param fd the descriptor
     * @return {@code 0}, or the {@code errno} of the refusal, which leaves the memory readable
     */
    int beginRead(int fd) {
        return sync(fd, SYNC_START_READ);
    }

    /**
     * Ends a read of a DMA-BUF.
     *
     * @param fd the descriptor
     * @return {@code 0}, or the {@code errno} of the refusal
     */
    int endRead(int fd) {
        return sync(fd, SYNC_END_READ);
    }

    /**
     * Issues one synchronization, repeating it while the kernel asks to.
     *
     * @return {@code 0}, or the last {@code errno}
     */
    private int sync(int fd, long flags) {
        syncArgument.set(JAVA_LONG, 0, flags);
        var result = Posix.EAGAIN;
        for (var attempt = 0; attempt < SYNC_ATTEMPTS && result == Posix.EAGAIN; attempt++) {
            result = posix.ioctl(fd, IOCTL_SYNC, syncArgument);
        }
        return result;
    }

    /**
     * Unmaps every buffer.
     */
    @Override
    public void close() {
        while (count > 0) {
            release(keys[count - 1]);
        }
    }
}
