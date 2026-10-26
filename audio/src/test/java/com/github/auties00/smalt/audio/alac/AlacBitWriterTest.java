package com.github.auties00.smalt.audio.alac;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Arrays;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Covers {@link AlacBitWriter} against a bit-array model: writes of every width at every alignment, rewinds to any
 * earlier position (which the element encoder uses to replace a compressed element by its escaped form), and the
 * cleared buffer after a reset.
 */
@DisplayName("ALAC bit writer")
class AlacBitWriterTest {
    /** Packs a bit model into bytes, most significant bit first. */
    static byte[] pack(boolean[] bits, int length) {
        var bytes = new byte[length / 8];
        for (var i = 0; i < length; i++) {
            if (bits[i]) {
                bytes[i >> 3] |= (byte) (0x80 >>> (i & 7));
            }
        }
        return bytes;
    }

    @ParameterizedTest(name = "seed {0}")
    @ValueSource(longs = {1, 2, 3, 4, 5, 6, 7, 8})
    @DisplayName("matches the model across random writes and rewinds")
    void model(long seed) {
        var random = new SplittableRandom(seed);
        var capacity = 4096;
        var writer = new AlacBitWriter(capacity);
        var model = new boolean[capacity * 8];
        for (var round = 0; round < 20; round++) {
            var position = 0;
            for (var step = 0; step < 300; step++) {
                if (position > 0 && random.nextInt(8) == 0) {
                    var target = random.nextInt(position + 1);
                    writer.rewind(target);
                    for (var i = target; i < position; i++) {
                        model[i] = false;
                    }
                    position = target;
                } else if (position + 32 <= capacity * 8 - 64) {
                    var bits = random.nextInt(1, 33);
                    var value = random.nextInt();
                    writer.write(value, bits);
                    for (var i = 0; i < bits; i++) {
                        model[position + i] = (value >>> (bits - 1 - i) & 1) != 0;
                    }
                    position += bits;
                }
                assertEquals(position, writer.position());
            }
            writer.alignToByte();
            var aligned = (position + 7) & ~7;
            assertEquals(aligned, writer.position());
            assertArrayEquals(pack(model, aligned), writer.toByteArray(), "round " + round);
            writer.reset();
            Arrays.fill(model, false);
            assertEquals(0, writer.position());
        }
    }

    @Test
    @DisplayName("a rewind clears the bits it takes back, so later writes are not mixed with them")
    void rewindClears() {
        var writer = new AlacBitWriter(16);
        writer.write(0b101, 3);
        writer.write(-1, 32);
        writer.rewind(3);
        writer.write(0, 13);
        assertArrayEquals(new byte[]{(byte) 0b1010_0000, 0}, writer.toByteArray());
        writer.reset();
        writer.write(0, 16);
        assertArrayEquals(new byte[2], writer.toByteArray());
    }
}
