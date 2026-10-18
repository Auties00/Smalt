package com.github.auties00.smalt.video.h264.internal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Arrays;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Checks the CABAC engine against a bit-serial transcription of the arithmetic decoding process
 * of ITU-T H.264 9.3.3.2 (one renormalization bit at a time, no look-ahead window), on random
 * payloads: single decisions, bypass and terminating bins, and whole residual blocks of every
 * block category, whose significance map and level binarization the reference decodes from the
 * syntax of 7.3.5.3.3 and 9.3.2.3.
 */
@DisplayName("H.264 CABAC engine")
class H264CabacTest {
    private static final int[] SIG_BASE = {105, 120, 134, 149, 152, 402};
    private static final int[] LAST_BASE = {166, 181, 195, 210, 213, 417};
    private static final int[] LEVEL_BASE = {227, 237, 247, 257, 266, 426};
    private static final int[] MAX_COEFF = {16, 15, 16, 4, 15, 64};
    private static final byte[] SIG_8X8 = {
            0, 1, 2, 3, 4, 5, 5, 4, 4, 3, 3, 4, 4, 4, 5, 5, 4, 4, 4, 4, 3, 3, 6, 7, 7, 7, 8, 9, 10, 9, 8, 7,
            7, 6, 11, 12, 13, 11, 6, 7, 8, 9, 14, 10, 9, 8, 6, 11, 12, 13, 11, 6, 9, 14, 10, 9, 11, 12, 13, 11, 14, 10, 12
    };
    private static final byte[] LAST_8X8 = {
            0, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2,
            3, 3, 3, 3, 3, 3, 3, 3, 4, 4, 4, 4, 4, 4, 4, 4, 5, 5, 5, 5, 6, 6, 6, 6, 7, 7, 7, 7, 8, 8, 8
    };

    /**
     * The arithmetic decoding engine exactly as the standard describes it.
     */
    private static final class Reference {
        final byte[] states = new byte[1024];
        private final byte[] data;
        private final int end;
        private long position;
        private int range;
        private int offset;

        Reference(byte[] data, int start, int end) {
            this.data = data;
            this.end = end;
            this.position = start * 8L;
            this.range = 510;
            this.offset = readBits(9);
        }

        private int readBit() {
            var index = (int) (position >> 3);
            var bit = index < end ? (data[index] >> (7 - (int) (position & 7))) & 1 : 0;
            position++;
            return bit;
        }

        private int readBits(int count) {
            var value = 0;
            for (var i = 0; i < count; i++) {
                value = (value << 1) | readBit();
            }
            return value;
        }

        int decision(int ctx) {
            var state = states[ctx] & 0xFF;
            var pState = state >> 1;
            var mps = state & 1;
            var lps = CabacTables.LPS_RANGE[pState * 4 + ((range >> 6) & 3)];
            range -= lps;
            int bin;
            if (offset >= range) {
                bin = 1 - mps;
                offset -= range;
                range = lps;
                states[ctx] = CabacTables.NEXT_STATE_LPS[state];
            } else {
                bin = mps;
                states[ctx] = CabacTables.NEXT_STATE_MPS[state];
            }
            while (range < 256) {
                range <<= 1;
                offset = (offset << 1) | readBit();
            }
            return bin;
        }

        int bypass() {
            offset = (offset << 1) | readBit();
            if (offset >= range) {
                offset -= range;
                return 1;
            }
            return 0;
        }

        int terminate() {
            range -= 2;
            if (offset >= range) {
                return 1;
            }
            while (range < 256) {
                range <<= 1;
                offset = (offset << 1) | readBit();
            }
            return 0;
        }

        /**
         * Decodes a residual block into {@code coefficients} (in scan order), returning the
         * number of non-zero coefficients.
         */
        int residual(int category, int[] coefficients) {
            var maxCoeff = MAX_COEFF[category];
            Arrays.fill(coefficients, 0);
            var significant = new boolean[64];
            var numCoeff = maxCoeff;
            var i = 0;
            for (; i < numCoeff - 1; i++) {
                var sigInc = category == 5 ? SIG_8X8[i] : category == 3 ? Math.min(i, 2) : i;
                significant[i] = decision(SIG_BASE[category] + sigInc) != 0;
                if (significant[i]) {
                    var lastInc = category == 5 ? LAST_8X8[i] : category == 3 ? Math.min(i, 2) : i;
                    if (decision(LAST_BASE[category] + lastInc) != 0) {
                        numCoeff = i + 1;
                        break;
                    }
                }
            }
            significant[numCoeff - 1] = true;
            var eq1 = 0;
            var gt1 = 0;
            var count = 0;
            for (var k = numCoeff - 1; k >= 0; k--) {
                if (!significant[k]) {
                    continue;
                }
                count++;
                var firstInc = gt1 != 0 ? 0 : Math.min(4, 1 + eq1);
                var level = 1;
                if (decision(LEVEL_BASE[category] + firstInc) != 0) {
                    var restInc = 5 + Math.min(4 - (category == 3 ? 1 : 0), gt1);
                    level = 2;
                    while (level < 15 && decision(LEVEL_BASE[category] + restInc) != 0) {
                        level++;
                    }
                    if (level >= 15) {
                        var exponent = 0;
                        while (bypass() != 0) {
                            level += 1 << exponent;
                            exponent++;
                        }
                        while (exponent-- > 0) {
                            level += bypass() << exponent;
                        }
                    }
                }
                if (level == 1) {
                    eq1++;
                } else {
                    gt1++;
                }
                coefficients[k] = bypass() != 0 ? -level : level;
            }
            return count;
        }
    }

    private static byte[] payload(Random random, int size) {
        var data = new byte[size + H264BitReader.PADDING];
        for (var i = 0; i < size; i++) {
            data[i] = (byte) random.nextInt(256);
        }
        return data;
    }

    private static byte[] initialStates(Random random) {
        var states = new byte[1024];
        for (var i = 0; i < states.length; i++) {
            states[i] = (byte) random.nextInt(126);
        }
        return states;
    }

    @ParameterizedTest(name = "seed {0}")
    @ValueSource(ints = {1, 2, 3, 4})
    @DisplayName("decision, bypass and terminating bins match the bit-serial engine")
    void bins(int seed) {
        var random = new Random(seed);
        var data = payload(random, 4096);
        var states = initialStates(random);
        var reference = new Reference(data, 3, 4096);
        var engine = new CabacReader();
        System.arraycopy(states, 0, reference.states, 0, 1024);
        System.arraycopy(states, 0, engine.states, 0, 1024);
        engine.init(data, 3, 4096);
        for (var i = 0; i < 20_000; i++) {
            var kind = random.nextInt(20);
            if (kind == 0) {
                assertEquals(reference.bypass(), engine.bypass(), "bypass bin " + i);
            } else if (kind == 1 && random.nextInt(20) == 0) {
                assertEquals(reference.terminate(), engine.terminate(), "terminating bin " + i);
            } else {
                var ctx = random.nextInt(460);
                assertEquals(reference.decision(ctx), engine.decision(ctx), "decision " + i);
            }
        }
        assertArrayEquals(reference.states, engine.states);
    }

    @ParameterizedTest(name = "seed {0}")
    @ValueSource(ints = {1, 2, 3, 4})
    @DisplayName("residual blocks of every category match the bit-serial engine")
    void residualBlocks(int seed) {
        var random = new Random(seed);
        var data = payload(random, 1 << 16);
        var states = initialStates(random);
        var reference = new Reference(data, 0, 1 << 16);
        var engine = new CabacReader();
        System.arraycopy(states, 0, reference.states, 0, 1024);
        System.arraycopy(states, 0, engine.states, 0, 1024);
        engine.init(data, 0, 1 << 16);
        var expected = new int[64];
        var actual = new int[64];
        var indices = new int[64];
        var levels = new int[64];
        for (var block = 0; block < 3000; block++) {
            var category = random.nextInt(6);
            var count = reference.residual(category, expected);
            var decoded = engine.residualBlock(category, MAX_COEFF[category], indices, levels);
            Arrays.fill(actual, 0);
            for (var k = 0; k < decoded; k++) {
                actual[indices[k]] = levels[k];
            }
            assertEquals(count, decoded, "coefficient count of block " + block);
            assertArrayEquals(expected, actual, "coefficients of block " + block);
            if (random.nextInt(4) == 0) {
                var ctx = random.nextInt(460);
                assertEquals(reference.decision(ctx), engine.decision(ctx), "decision after block " + block);
            }
        }
        assertArrayEquals(reference.states, engine.states);
    }
}
