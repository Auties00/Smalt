package com.github.auties00.smalt.internal.caption;

import com.github.auties00.smalt.testkit.subtitle.TeletextWriter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Checks the Hamming 24/18 decoding of teletext triplets against the encoder of the test kit: every single bit error
 * is corrected and every double error, or triple error whose syndrome names no bit position, is rejected.
 */
@DisplayName("teletext Hamming 24/18")
class TeletextHammingTest {
    @Test
    @DisplayName("corrects single errors and rejects double errors and syndromes past bit 23")
    void triplets() {
        for (var value = 0; value < 1 << 18; value += 97) {
            var word = word(value);
            assertEquals(value, decode(word), "clean " + value);
            for (var first = 0; first < 24; first++) {
                assertEquals(value, decode(word ^ 1 << first), "value " + value + ", bit " + first);
                for (var second = first + 1; second < 24; second++) {
                    assertEquals(-1, decode(word ^ 1 << first ^ 1 << second),
                            "value " + value + ", bits " + first + " and " + second);
                }
            }
            // Positions 1, 8 and 16 (bits 0, 7 and 15) give the syndrome 25 with a wrong overall parity.
            assertEquals(-1, decode(word ^ (1 | 1 << 7 | 1 << 15)), "value " + value + ", syndrome 25");
        }
    }

    private static int word(int value) {
        var bytes = TeletextWriter.hamming2418(value);
        return bytes[0] | bytes[1] << 8 | bytes[2] << 16;
    }

    private static int decode(int word) {
        var data = new byte[3];
        for (var index = 0; index < 3; index++) {
            data[index] = (byte) (Integer.reverse(word >> index * 8 & 0xFF) >>> 24);
        }
        return TeletextDecoder.hamming24(data, 0);
    }
}
