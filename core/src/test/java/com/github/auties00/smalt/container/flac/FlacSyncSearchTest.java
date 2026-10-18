package com.github.auties00.smalt.container.flac;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The word-at-a-time frame sync search, checked against the byte-by-byte definition on random
 * buffers dense and sparse in {@code 0xFF} bytes, at every alignment.
 */
@DisplayName("FLAC sync search")
class FlacSyncSearchTest {
    static int naive(byte[] data, int from, int stop, byte second) {
        var index = from;
        while (index < stop && (data[index] != (byte) 0xFF || data[index + 1] != second)) {
            index++;
        }
        return index;
    }

    @Test
    @DisplayName("finds the same sync code as a byte-by-byte scan")
    void matchesNaive() {
        var random = new SplittableRandom(5);
        for (var trial = 0; trial < 20_000; trial++) {
            var length = 2 + random.nextInt(300);
            var data = new byte[length];
            var density = random.nextInt(4);
            for (var i = 0; i < length; i++) {
                var roll = random.nextInt(64);
                data[i] = roll < density ? (byte) 0xFF : roll < 2 * density ? (byte) 0xF8 : (byte) random.nextInt(255);
            }
            var second = random.nextBoolean() ? (byte) 0xF8 : (byte) 0xF9;
            var from = random.nextInt(length);
            var stop = from + random.nextInt(length - from);
            assertEquals(naive(data, from, stop, second), FlacDemuxer.findSync(data, from, stop, second),
                    "trial " + trial);
        }
    }
}
