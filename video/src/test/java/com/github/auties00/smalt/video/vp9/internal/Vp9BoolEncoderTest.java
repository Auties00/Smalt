package com.github.auties00.smalt.video.vp9.internal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Arrays;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

/**
 * Checks {@link Vp9BoolEncoder} byte for byte against a direct transcription of libvpx's
 * {@code vpx_writer}, which emits each byte as soon as 24 bits are pending, over random boolean
 * sequences that include long carry chains and every partition length up to a few hundred bits.
 */
@DisplayName("VP9 boolean encoder")
class Vp9BoolEncoderTest {
    private static final class LibvpxWriter {
        private final byte[] buffer = new byte[1 << 16];
        private int pos;
        private int lowValue;
        private int range = 255;
        private int count = -24;

        LibvpxWriter() {
            write(0, 128);
        }

        void write(int bit, int probability) {
            var split = 1 + (((range - 1) * probability) >> 8);
            var low = lowValue;
            var r = split;
            if (bit != 0) {
                low += split;
                r = range - split;
            }
            var shift = Integer.numberOfLeadingZeros(r) - 24;
            r <<= shift;
            var c = count + shift;
            if (c >= 0) {
                var offset = shift - c;
                if (((low << (offset - 1)) & 0x80000000) != 0) {
                    var x = pos - 1;
                    while (x >= 0 && buffer[x] == (byte) 0xFF) {
                        buffer[x] = 0;
                        x--;
                    }
                    if (x >= 0) {
                        buffer[x]++;
                    }
                }
                buffer[pos++] = (byte) (low >>> (24 - offset));
                low <<= offset;
                shift = c;
                low &= 0xFFFFFF;
                c -= 8;
            }
            low <<= shift;
            count = c;
            lowValue = low;
            range = r;
        }

        byte[] stop() {
            for (var i = 0; i < 32; i++) {
                write(0, 128);
            }
            if (pos > 0 && (buffer[pos - 1] & 0xE0) == 0xC0) {
                buffer[pos++] = 0;
            }
            return Arrays.copyOf(buffer, pos);
        }
    }

    @ParameterizedTest(name = "seed {0}")
    @ValueSource(ints = {1, 2, 3, 4})
    @DisplayName("produces libvpx's bytes for random sequences, carries and lengths")
    void matchesLibvpx(int seed) {
        var random = new Random(seed);
        var encoder = new Vp9BoolEncoder(256);
        for (var trial = 0; trial < 2000; trial++) {
            var reference = new LibvpxWriter();
            encoder.start();
            var length = random.nextInt(trial % 10 == 0 ? 20000 : 400);
            var skew = random.nextDouble();
            for (var i = 0; i < length; i++) {
                var probability = random.nextInt(4) == 0 ? new int[]{1, 2, 128, 254, 255}[random.nextInt(5)]
                        : 1 + random.nextInt(255);
                // Runs of the less probable value drive the low end up and produce carry chains.
                var bit = random.nextDouble() < (random.nextBoolean() ? skew : 1 - probability / 256.0) ? 1 : 0;
                reference.write(bit, probability);
                encoder.write(bit, probability);
            }
            var expected = reference.stop();
            encoder.stop();
            assertArrayEquals(expected, Arrays.copyOf(encoder.buffer(), encoder.size()), "trial " + trial);
        }
    }
}
