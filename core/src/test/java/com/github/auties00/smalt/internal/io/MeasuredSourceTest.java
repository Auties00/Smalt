package com.github.auties00.smalt.internal.io;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.io.Behaviors;
import com.github.auties00.smalt.testkit.io.IoVariant;
import com.github.auties00.smalt.testkit.io.SimulatedSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers {@link MeasuredSource}: the size found by probing an unsized random-access source, at and around
 * the probe boundaries, the reads through the view, the sources left alone, and failures while probing.
 */
class MeasuredSourceTest {
    static byte[] bytes(int size) {
        var data = new byte[size];
        for (var i = 0; i < size; i++) {
            data[i] = (byte) (i * 131 + 7);
        }
        return data;
    }

    @ParameterizedTest(name = "{0} bytes")
    @ValueSource(ints = {0, 1, 2, 4095, 65534, 65535, 65536, 65537, 131071, 131072, 131073, 300_001, 1 << 20})
    @DisplayName("finds the exact size of an unsized source in about twice the base-two logarithm of reads")
    void size(int size) {
        var data = bytes(size);
        var unsized = SimulatedSource.of(data, false, SimulatedSource.Chunking.ONE_BYTE, 0);
        var measured = MeasuredSource.measure(unsized);
        assertEquals(size, measured.size());
        var bound = 2 * (64 - Long.numberOfLeadingZeros(Math.max(size, 1 << 16))) + 2;
        assertTrue(unsized.reads() <= bound, () -> unsized.reads() + " probing reads for " + size + " bytes");
    }

    @Test
    @DisplayName("reads through to the measured source and ends at the measured size")
    void reads() {
        var data = bytes(70_000);
        var unsized = SimulatedSource.of(data, false, SimulatedSource.Chunking.RANDOM, 3);
        var measured = MeasuredSource.measure(unsized);
        var copy = new byte[data.length];
        measured.readFully(0, copy, 0, copy.length);
        assertArrayEquals(data, copy);
        var tail = new byte[100];
        var read = measured.read(data.length - 10, tail, 0, tail.length);
        assertTrue(read > 0 && read <= 10);
        assertArrayEquals(Arrays.copyOfRange(data, data.length - 10, data.length - 10 + read),
                Arrays.copyOf(tail, read));
        assertEquals(-1, measured.read(data.length, tail, 0, 1));
        assertEquals(0, measured.read(data.length + 5, tail, 0, 0));
        assertThrows(IndexOutOfBoundsException.class, () -> measured.read(-1, tail, 0, 1));
        assertThrows(IndexOutOfBoundsException.class, () -> measured.read(0, tail, 90, 20));
    }

    @Test
    @DisplayName("returns sized and read-once sources unchanged and never closes the measured source")
    void untouched() {
        var data = bytes(1000);
        var sized = MediaSource.of(data);
        assertSame(sized, MeasuredSource.measure(sized));
        try (var once = IoVariant.READ_ONCE_UNKNOWN_LENGTH.open(data)) {
            assertSame(once, MeasuredSource.measure(once));
            var head = new byte[16];
            once.readFully(0, head, 0, head.length);
            assertArrayEquals(Arrays.copyOf(data, 16), head);
        }
        var unsized = SimulatedSource.of(data, false, SimulatedSource.Chunking.WHOLE, 0);
        MeasuredSource.measure(unsized).close();
        assertEquals(0, unsized.closes());
    }

    @Test
    @DisplayName("keeps the locality of the measured source")
    void locality() {
        var measured = MeasuredSource.measure(SimulatedSource.of(bytes(10), false, SimulatedSource.Chunking.WHOLE, 0));
        assertFalse(LocalSource.isLocal(measured));
        assertFalse(LocalSource.isLocal(measured, 0, 10));
    }

    @Test
    @DisplayName("propagates a read failure while probing as the MediaException.Io of the source")
    void failure() {
        var data = bytes(200_000);
        for (var index = 0; index < 8; index++) {
            var failing = new UnsizedFailing(data, index);
            var thrown = assertThrows(MediaException.Io.class, () -> MeasuredSource.measure(failing));
            assertTrue(Behaviors.isCleanFault(thrown), thrown::toString);
        }
    }

    /**
     * An unsized view of a failing source.
     */
    private static final class UnsizedFailing implements MediaSource {
        private final SimulatedSource source;

        UnsizedFailing(byte[] data, long index) {
            this.source = SimulatedSource.failing(data, index, false);
        }

        @Override
        public long size() {
            return -1;
        }

        @Override
        public int read(long position, byte[] buffer, int offset, int length) {
            return source.read(position, buffer, offset, length);
        }

        @Override
        public void close() {
        }
    }
}
