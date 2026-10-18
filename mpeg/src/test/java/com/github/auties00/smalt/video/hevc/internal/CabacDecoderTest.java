package com.github.auties00.smalt.video.hevc.internal;

import com.github.auties00.smalt.video.h264.internal.H264BitReader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Checks the batched bypass decoding of {@link CabacDecoder} against a bit-by-bit decoder written
 * from the equations of ITU-T H.265 9.3.4.3, over random bitstreams and random mixes of context
 * coded, bypass and terminate-free operations.
 */
@DisplayName("HEVC CABAC decoding engine")
class CabacDecoderTest {
    private static final class SpecDecoder {
        private final byte[] data;
        private final int endBits;
        private int bit;
        private int range = 510;
        private int offset;

        SpecDecoder(byte[] data, int end) {
            this.data = data;
            this.endBits = end * 8;
            offset = read(9);
        }

        private int read(int count) {
            var value = 0;
            for (var i = 0; i < count; i++) {
                var b = bit < endBits ? (data[bit >> 3] >> (7 - (bit & 7))) & 1 : 0;
                bit++;
                value = (value << 1) | b;
            }
            return value;
        }

        int decision(int[] states, int ctx) {
            var pState = states[ctx] >> 1;
            var mps = states[ctx] & 1;
            var lps = CabacTables.LPS_RANGE[pState * 4 + ((range >> 6) & 3)];
            range -= lps;
            int bin;
            if (offset >= range) {
                bin = 1 - mps;
                offset -= range;
                range = lps;
                if (pState == 0) {
                    mps = 1 - mps;
                }
                pState = CabacTables.TRANS_LPS[pState];
            } else {
                bin = mps;
                pState = Math.min(pState + 1, 62);
            }
            states[ctx] = (pState << 1) | mps;
            while (range < 256) {
                range <<= 1;
                offset = (offset << 1) | read(1);
            }
            return bin;
        }

        int bypass() {
            offset = (offset << 1) | read(1);
            if (offset >= range) {
                offset -= range;
                return 1;
            }
            return 0;
        }

        void align() {
            range = 256;
        }
    }

    @ParameterizedTest(name = "seed {0}")
    @ValueSource(ints = {1, 2, 3, 4, 5, 6, 7, 8})
    @DisplayName("batched bypass bins and unary prefixes equal bin-by-bin decoding")
    void batchedBypass(int seed) {
        var random = new Random(seed);
        for (var trial = 0; trial < 200; trial++) {
            var length = 16 + random.nextInt(200);
            var data = new byte[length + H264BitReader.PADDING];
            var density = random.nextInt(4);
            for (var i = 0; i < length; i++) {
                // Mostly-ones bytes give long unary prefixes, random bytes give everything else.
                data[i] = (byte) (density == 0 ? 0xFF ^ (1 << random.nextInt(8)) : random.nextInt(256));
            }
            var engine = new CabacDecoder();
            engine.start(data, 0, length);
            var spec = new SpecDecoder(data, length);
            if (spec.offset >= 510) {
                // A conforming stream never starts with an offset of 510 or 511 (9.3.2.5).
                continue;
            }
            var specStates = new int[4];
            for (var ctx = 0; ctx < 4; ctx++) {
                specStates[ctx] = random.nextInt(126);
                engine.states[ctx] = (byte) specStates[ctx];
            }
            for (var op = 0; op < 300; op++) {
                var kind = random.nextInt(6);
                var where = "trial " + trial + " op " + op + " kind " + kind;
                switch (kind) {
                    case 0 -> {
                        var ctx = random.nextInt(4);
                        assertEquals(spec.decision(specStates, ctx), engine.decodeBin(ctx), where);
                    }
                    case 1 -> assertEquals(spec.bypass(), engine.decodeBypass(), where);
                    case 2, 3 -> {
                        var count = random.nextInt(kind == 2 ? 9 : 33);
                        var expected = 0;
                        for (var i = 0; i < count; i++) {
                            expected = (expected << 1) | spec.bypass();
                        }
                        assertEquals(expected, engine.decodeBypassBins(count), where + " count " + count);
                    }
                    case 4 -> {
                        var limit = random.nextInt(33);
                        var expected = 0;
                        while (expected <= limit && spec.bypass() == 1) {
                            expected++;
                        }
                        var actual = engine.decodeBypassOnes(limit);
                        if (expected > limit) {
                            assertEquals(true, actual > limit, where);
                            op = Integer.MAX_VALUE - 1;
                        } else {
                            assertEquals(expected, actual, where);
                        }
                    }
                    default -> {
                        // A conforming stream aligns only while the offset is below 256.
                        if (spec.offset < 256) {
                            spec.align();
                            engine.align();
                        }
                    }
                }
            }
        }
    }
}
