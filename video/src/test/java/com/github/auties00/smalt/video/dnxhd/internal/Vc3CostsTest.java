package com.github.auties00.smalt.video.dnxhd.internal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Checks the tables the VC-3 macroblock coder looks code lengths and codewords up in against the
 * symbol assembly of ST 2019-1 clause 7.3 done by hand, and the 8x8 bit transposition that turns
 * per-position lane masks into per-block masks.
 */
@DisplayName("VC-3 coding tables")
class Vc3CostsTest {
    @Test
    @DisplayName("transpose an 8x8 bit matrix")
    void transpose() {
        var random = new Random(3);
        for (var round = 0; round < 1000; round++) {
            var matrix = round == 0 ? 0L : round == 1 ? -1L : random.nextLong();
            var expected = 0L;
            for (var row = 0; row < 8; row++) {
                for (var column = 0; column < 8; column++) {
                    expected |= (matrix >>> (8 * row + column) & 1L) << (8 * column + row);
                }
            }
            assertEquals(expected, Vc3RowEncoder.transpose(matrix));
        }
    }

    @ParameterizedTest(name = "set {0}")
    @ValueSource(ints = {0, 1, 2, 3, 4, 5})
    @DisplayName("hold the length and codeword of every amplitude with and without a run")
    void amplitudes(int set) {
        for (var depth : new int[]{8, 10}) {
            var costs = Vc3Costs.of(set, depth);
            var amplitudes = Vc3Tables.amplitude(set);
            var runs = Vc3Tables.run(set);
            var indexBits = depth == 8 ? 4 : 6;
            var maxLevel = depth == 8 ? 1024 : 4096;
            for (var amplitude = 1; amplitude <= maxLevel; amplitude++) {
                for (var run = 0; run < 64; run += 7) {
                    var a = amplitude;
                    var symbol = 0;
                    var extra = -1;
                    if (a > 64) {
                        extra = (a - 1) >>> 6;
                        a -= extra * 64;
                        symbol |= Vc3Tables.INDEX_FLAG;
                    }
                    symbol |= a;
                    if (run > 0) {
                        symbol |= Vc3Tables.RUN_FLAG;
                    }
                    var slot = 2 * amplitude + Math.min(run, 1);
                    var length = amplitudes.codeLength(symbol) + 1 + (extra >= 0 ? indexBits : 0);
                    assertEquals(length, costs.bits[slot]);
                    if (amplitudes.codeLength(symbol) > 0) {
                        var word = amplitudes.code(symbol) << (1 + (extra >= 0 ? indexBits : 0)) | Math.max(extra, 0);
                        assertEquals(word, costs.words[slot]);
                    }
                    assertEquals(extra >= 0 ? 1 << indexBits : 1, costs.signs[amplitude]);
                    assertEquals(run > 0 ? runs.codeLength(run) : 0, costs.runBits[run]);
                }
            }
        }
    }
}
