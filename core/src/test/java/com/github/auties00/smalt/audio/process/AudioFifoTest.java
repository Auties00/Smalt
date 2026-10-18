package com.github.auties00.smalt.audio.process;

import com.github.auties00.smalt.audio.AudioBuffer;
import com.github.auties00.smalt.audio.ChannelLayout;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers the planar FIFO: ordering across growth and compaction, peeking, discarding, the size
 * bound, and argument validation.
 */
class AudioFifoTest {
    @Test
    @DisplayName("returns frames in write order across growth and compaction")
    void order() {
        var fifo = new AudioFifo(2);
        var random = new SplittableRandom(4);
        long written = 0;
        long read = 0;
        for (var round = 0; round < 2000; round++) {
            var count = random.nextInt(0, 700);
            var block = new float[2][count + 3];
            for (var i = 0; i < count; i++) {
                block[0][i + 3] = written + i;
                block[1][i + 3] = -(written + i);
            }
            fifo.write(block, 3, count);
            written += count;
            var want = random.nextInt(0, 800);
            var out = new float[2][want + 1];
            var got = fifo.read(out, 1, want);
            assertEquals(Math.min(want, written - read), got);
            for (var i = 0; i < got; i++) {
                assertEquals(read + i, out[0][i + 1]);
                assertEquals(-(read + i), out[1][i + 1]);
            }
            read += got;
            assertEquals(written - read, fifo.size());
        }
    }

    @Test
    @DisplayName("peeks without removing and discards without copying")
    void peekDiscard() {
        var fifo = new AudioFifo(1);
        fifo.write(new float[][]{{1, 2, 3, 4, 5}}, 0, 5);
        var out = new float[1][3];
        assertEquals(3, fifo.peek(out, 0, 3));
        assertArrayEquals(new float[]{1, 2, 3}, out[0]);
        assertEquals(5, fifo.size());
        assertEquals(2, fifo.discard(2));
        assertEquals(3, fifo.read(out, 0, 10));
        assertArrayEquals(new float[]{3, 4, 5}, out[0]);
        assertTrue(fifo.isEmpty());
        assertEquals(0, fifo.discard(4));
        fifo.write(new float[][]{{9}}, 0, 1);
        fifo.clear();
        assertEquals(0, fifo.size());
    }

    @Test
    @DisplayName("accepts audio buffers")
    void buffers() {
        var fifo = new AudioFifo(2);
        var buffer = new AudioBuffer(ChannelLayout.STEREO, 48000, 8);
        buffer.plane(0)[0] = 0.25f;
        buffer.plane(1)[0] = -0.5f;
        buffer.setFrames(3);
        fifo.write(buffer);
        assertEquals(3, fifo.size());
        var out = new float[2][1];
        fifo.read(out, 0, 1);
        assertEquals(0.25f, out[0][0]);
        assertEquals(-0.5f, out[1][0]);
    }

    @Test
    @DisplayName("enforces its size bound without losing queued frames")
    void bound() {
        var fifo = new AudioFifo(1, 100);
        fifo.write(new float[1][60], 0, 60);
        assertThrows(IllegalStateException.class, () -> fifo.write(new float[1][41], 0, 41));
        assertEquals(60, fifo.size());
        fifo.write(new float[1][40], 0, 40);
        assertEquals(100, fifo.maxFrames());
        assertEquals(100, fifo.size());
    }

    @Test
    @DisplayName("validates arguments")
    void validation() {
        assertThrows(IllegalArgumentException.class, () -> new AudioFifo(0));
        assertThrows(IllegalArgumentException.class, () -> new AudioFifo(1, 0));
        var fifo = new AudioFifo(2);
        assertThrows(IllegalArgumentException.class, () -> fifo.write(new float[1][4], 0, 4));
        assertThrows(IndexOutOfBoundsException.class, () -> fifo.write(new float[2][4], 2, 4));
        assertThrows(IllegalArgumentException.class, () -> fifo.read(new float[2][4], 0, -1));
        assertThrows(IllegalArgumentException.class, () -> fifo.discard(-1));
        fifo.write(new float[2][4], 0, 4);
        assertThrows(IndexOutOfBoundsException.class, () -> fifo.read(new float[2][2], 0, 4));
        assertEquals(4, fifo.size());
    }
}
