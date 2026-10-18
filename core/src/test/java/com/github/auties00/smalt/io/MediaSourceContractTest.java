package com.github.auties00.smalt.io;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.container.Containers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

@DisplayName("MediaSource.readFully")
class MediaSourceContractTest {
    /**
     * Returns a source of 100 bytes whose reads return {@code result} without storing anything.
     */
    static MediaSource returning(int result, AtomicInteger calls) {
        return new MediaSource() {
            @Override
            public long size() {
                return 100;
            }

            @Override
            public int read(long position, byte[] buffer, int offset, int length) {
                calls.incrementAndGet();
                return result;
            }

            @Override
            public void close() {
            }
        };
    }

    @Test
    @DisplayName("fails instead of spinning on a source that returns no byte before its end")
    void zeroRead() {
        var calls = new AtomicInteger();
        var source = returning(0, calls);
        assertTimeoutPreemptively(Duration.ofSeconds(10),
                () -> assertThrows(MediaException.Io.class, () -> source.readFully(0, new byte[10], 0, 10)));
        assertEquals(1, calls.get());
    }

    @Test
    @DisplayName("container detection fails on a source that returns no byte before its end")
    void detectZeroRead() {
        var calls = new AtomicInteger();
        assertThrows(MediaException.Io.class, () -> Containers.detect(returning(0, calls)));
        assertEquals(1, calls.get());
    }

    @Test
    @DisplayName("reports the end of the source as malformed input")
    void endOfSource() {
        var source = returning(-1, new AtomicInteger());
        assertThrows(MediaException.Malformed.class, () -> source.readFully(0, new byte[10], 0, 10));
    }

    @Test
    @DisplayName("reads nothing for an empty range and gathers short reads")
    void shortReads() {
        var calls = new AtomicInteger();
        var source = returning(0, calls);
        source.readFully(0, new byte[10], 0, 0);
        assertEquals(0, calls.get());
        var data = new byte[]{1, 2, 3, 4, 5, 6, 7};
        var chunked = new MediaSource() {
            @Override
            public long size() {
                return data.length;
            }

            @Override
            public int read(long position, byte[] buffer, int offset, int length) {
                if (position >= data.length) {
                    return -1;
                }
                buffer[offset] = data[(int) position];
                return 1;
            }

            @Override
            public void close() {
            }
        };
        var target = new byte[7];
        chunked.readFully(0, target, 0, 7);
        assertArrayEquals(data, target);
    }
}
