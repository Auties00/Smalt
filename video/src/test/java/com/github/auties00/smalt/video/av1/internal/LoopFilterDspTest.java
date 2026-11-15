package com.github.auties00.smalt.video.av1.internal;

import com.github.auties00.smalt.testkit.Skip;
import com.github.auties00.smalt.internal.simd.Simd;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Checks the deblocking kernels and the mask builders against dav1d's C reference: every group
 * feeds pseudo-random but valid inputs from a shared xorshift32 generator to the Java port and
 * folds the outputs into a 64-bit FNV-1a hash, and the expected hashes were produced by the same
 * procedure run on dav1d's C code (harness kept with the port's working files).
 */
@DisplayName("AV1 loop filter kernels and masks (dav1d C reference hashes)")
class LoopFilterDspTest {
    private static final long FNV_INIT = 0xcbf29ce484222325L;

    private static final int[][] TX_SIZES = {
            {1, 1}, {2, 2}, {4, 4}, {8, 8}, {16, 16},
            {1, 2}, {2, 1}, {2, 4}, {4, 2}, {4, 8}, {8, 4},
            {8, 16}, {16, 8}, {1, 4}, {4, 1}, {2, 8}, {8, 2}, {4, 16}, {16, 4}};

    private static final int[][] BLOCK_SIZES = {
            {32, 32}, {32, 16}, {16, 32}, {16, 16}, {16, 8}, {16, 4}, {8, 16}, {8, 8}, {8, 4}, {8, 2}, {4, 16},
            {4, 8}, {4, 4}, {4, 2}, {4, 1}, {2, 8}, {2, 4}, {2, 2}, {2, 1}, {1, 4}, {1, 2}, {1, 1}};

    private int state;

    @ParameterizedTest(name = "plane {0}, dir {1}, {2}-bit")
    @CsvSource({
            "0, 0, 8, 5f1d914c68f22a8a",
            "0, 1, 8, 31e832fb4f4ce5e1",
            "1, 0, 8, f600ff9fa7034d2b",
            "1, 1, 8, 938f6b840feb4503",
            "0, 0, 10, 6234f27f3c9a9284",
            "0, 1, 10, 0d34b152229cc999",
            "1, 0, 10, 4f841e46bbdf6d7c",
            "1, 1, 10, 8795f8cc90b9ebdd"
    })
    @DisplayName("deblocking kernel matches dav1d")
    void kernel(int plane, int dir, int bitDepth, String expected) {
        state = 1000 + 100 * plane + 10 * dir + bitDepth;
        assertEquals(expected, hex(runKernel(new ScalarLoopFilterDsp(), plane, dir, bitDepth, 300)));
    }

    @ParameterizedTest(name = "plane {0}, dir {1}, {2}-bit")
    @CsvSource({
            "0, 0, 8, 5f1d914c68f22a8a",
            "0, 1, 8, 31e832fb4f4ce5e1",
            "1, 0, 8, f600ff9fa7034d2b",
            "1, 1, 8, 938f6b840feb4503",
            "0, 0, 10, 6234f27f3c9a9284",
            "0, 1, 10, 0d34b152229cc999",
            "1, 0, 10, 4f841e46bbdf6d7c",
            "1, 1, 10, 8795f8cc90b9ebdd"
    })
    @DisplayName("vector deblocking kernel matches dav1d")
    void vectorKernel(int plane, int dir, int bitDepth, String expected) {
        Skip.unlessVector(Simd.ENABLED);
        state = 1000 + 100 * plane + 10 * dir + bitDepth;
        assertEquals(expected, hex(runKernel(VectorLoopFilterDsp.create(), plane, dir, bitDepth, 300)));
    }

    @ParameterizedTest(name = "layout {0}")
    @CsvSource({
            "0, 5141de4573b78685",
            "1, 2c9a0af589ffe451",
            "2, 5321c406656cc427",
            "3, 868160751ac48f58"
    })
    @DisplayName("intra and inter edge masks, level cache and contexts match dav1d")
    void masks(int layout, String expected) {
        state = 2000 + layout;
        assertEquals(expected, hex(runMasks(layout, 400)));
    }

    @Test
    @DisplayName("filter limits match dav1d for every sharpness")
    void limits() {
        var lut = new FilterLut();
        var h = FNV_INIT;
        for (var sharpness = 0; sharpness < 8; sharpness++) {
            LoopFilterMasks.calcEih(lut, sharpness);
            for (var v : lut.e) {
                h = fnv(h, v);
            }
            for (var v : lut.i) {
                h = fnv(h, v);
            }
            h = fnv(h, lut.sharp[0]);
            h = fnv(h, lut.sharp[1]);
        }
        assertEquals("10dabe71cdecdb29", hex(h));
    }

    @Test
    @DisplayName("per-segment filter levels match dav1d")
    void levels() {
        state = 3000;
        var values = new byte[LoopFilterMasks.LF_VALUES_LENGTH];
        var modeDeltas = new int[2];
        var refDeltas = new int[8];
        var segDeltas = new int[32];
        var lfDelta = new int[4];
        var h = FNV_INIT;
        for (var c = 0; c < 500; c++) {
            Arrays.fill(values, (byte) 0);
            var y0 = rnd(3) == 0 ? 0 : rnd(64);
            var y1 = rnd(3) == 0 ? 0 : rnd(64);
            var u = rnd(3) == 0 ? 0 : rnd(64);
            var v = rnd(3) == 0 ? 0 : rnd(64);
            var modeRef = rnd(2) != 0;
            for (var i = 0; i < 2; i++) {
                modeDeltas[i] = rnd(127) - 63;
            }
            for (var i = 0; i < 8; i++) {
                refDeltas[i] = rnd(127) - 63;
            }
            var segmentation = rnd(2) != 0;
            for (var i = 0; i < 32; i++) {
                segDeltas[i] = rnd(127) - 63;
            }
            var multi = rnd(2) != 0;
            for (var i = 0; i < 4; i++) {
                lfDelta[i] = rnd(127) - 63;
            }
            LoopFilterMasks.calcLfValues(values, y0, y1, u, v, modeRef, modeDeltas, refDeltas,
                    segmentation, segDeltas, multi, lfDelta);
            for (var b : values) {
                h = fnv(h, b & 0xFF);
            }
        }
        assertEquals("9dfe4331bbd60c4d", hex(h));
    }

    private long runKernel(LoopFilterDsp dsp, int plane, int dir, int bitDepth, int cases) {
        var max = (1 << bitDepth) - 1;
        int width = 160, height = 160, b4Stride = 40, levelRows = 36;
        var buf = new short[width * height];
        var lvl = new byte[levelRows * b4Stride * 4];
        var lut = new FilterLut();
        int[] amps = {0, 1, 2, 4, 16, 64};
        int[] spreads = {0, 2, 8, 32, 128};
        var h = FNV_INIT;
        for (var c = 0; c < cases; c++) {
            LoopFilterMasks.calcEih(lut, rnd(8));
            var globalBase = rnd(max + 1);
            var spread = spreads[rnd(5)] << (bitDepth - 8);
            for (var ty = 0; ty < height / 8; ty++) {
                for (var tx = 0; tx < width / 8; tx++) {
                    var base = globalBase + rnd(2 * spread + 1) - spread;
                    var amp = amps[rnd(6)] << (bitDepth - 8);
                    for (var y = 0; y < 8; y++) {
                        for (var x = 0; x < 8; x++) {
                            var v = base;
                            if (amp != 0) {
                                v += rnd(2 * amp + 1) - amp;
                            }
                            buf[(ty * 8 + y) * width + tx * 8 + x] = (short) Math.clamp(v, 0, max);
                        }
                    }
                }
            }
            for (var i = 0; i < lvl.length; i++) {
                lvl[i] = (byte) (rnd(4) == 0 ? 0 : rnd(64));
            }
            var m0 = next();
            var m1 = next();
            m1 &= next();
            var m2 = next();
            m2 &= next();
            m2 &= next();
            var length = rnd(33);
            if (length < 32) {
                var keep = length == 0 ? 0 : (1 << length) - 1;
                m0 &= keep;
                m1 &= keep;
                m2 &= keep;
            }
            var byteIndex = rnd(4);
            dsp.loopFilterSb(plane, dir, buf, 16 * width + 16, width, m0, m1, m2,
                    lvl, (b4Stride + 1) * 4 + byteIndex, b4Stride, lut, 32, new short[LoopFilterDsp.SCRATCH_SHORTS], max);
            for (var v : buf) {
                h = fnv(h, v & 0xFFFF);
            }
        }
        return h;
    }

    private long runMasks(int layout, int cases) {
        var mask = new LfMask();
        var cache = new byte[64 * 64 * 4];
        var ay = new byte[32];
        var ly = new byte[32];
        var auv = new byte[32];
        var luv = new byte[32];
        var levels = new byte[64];
        var txa = new byte[LoopFilterMasks.TX_SCRATCH_LENGTH];
        var ssVer = layout == SequenceHeader.LAYOUT_I420 ? 1 : 0;
        var ssHor = layout != SequenceHeader.LAYOUT_I444 ? 1 : 0;
        var h = FNV_INIT;
        for (var c = 0; c < cases; c++) {
            mask.clear();
            Arrays.fill(cache, (byte) 0);
            fill(ay, 3);
            fill(ly, 3);
            fill(auv, 2);
            fill(luv, 2);
            fill(levels, 64);
            var iw = 1 + rnd(64);
            var ih = 1 + rnd(64);
            for (var b = 0; b < 6; b++) {
                var bs = rnd(22);
                var bw = BLOCK_SIZES[bs][0];
                var bh = BLOCK_SIZES[bs][1];
                var bx = rnd(iw) & ~(bw - 1);
                var by = rnd(ih) & ~(bh - 1);
                var intra = rnd(2) != 0;
                var hasChroma = layout != SequenceHeader.LAYOUT_I400 && rnd(4) != 0;
                var uvtx = pickTxUpTo(Math.min((bw + ssHor) >> ssHor, 8), Math.min((bh + ssVer) >> ssVer, 8));
                var bx4 = bx & 31;
                var by4 = by & 31;
                var a = hasChroma ? auv : null;
                var l = hasChroma ? luv : null;
                if (intra) {
                    var ytx = pickTxUpTo(Math.min(bw, 16), Math.min(bh, 16));
                    LoopFilterMasks.createLfMaskIntra(mask, cache, 64, levels, 0, bx, by, iw, ih, bs, ytx, uvtx,
                            layout, ay, bx4, ly, by4, a, bx4 >> ssHor, l, by4 >> ssVer);
                } else {
                    var maxTx = findTx(Math.min(bw, 16), Math.min(bh, 16));
                    var split0 = next() & 0x33;
                    var split1 = next() & 0xFFFF;
                    var skip = rnd(2) != 0;
                    var ref = rnd(8);
                    var mode = rnd(2);
                    LoopFilterMasks.createLfMaskInter(mask, cache, 64, levels, ref * 2 + mode, bx, by, iw, ih,
                            skip, bs, maxTx, split0, split1, uvtx, layout,
                            ay, bx4, ly, by4, a, bx4 >> ssHor, l, by4 >> ssVer, txa);
                }
            }
            for (var w : mask.filterY) {
                h = fnv(h, w);
            }
            for (var w : mask.filterUv) {
                h = fnv(h, w);
            }
            for (var array : new byte[][]{cache, ay, ly, auv, luv}) {
                for (var v : array) {
                    h = fnv(h, v & 0xFF);
                }
            }
        }
        return h;
    }

    private void fill(byte[] array, int bound) {
        for (var i = 0; i < array.length; i++) {
            array[i] = (byte) rnd(bound);
        }
    }

    private int pickTxUpTo(int maxWidth, int maxHeight) {
        var count = 0;
        for (var size : TX_SIZES) {
            if (size[0] <= maxWidth && size[1] <= maxHeight) {
                count++;
            }
        }
        var pick = rnd(count);
        for (var t = 0; t < TX_SIZES.length; t++) {
            if (TX_SIZES[t][0] <= maxWidth && TX_SIZES[t][1] <= maxHeight && pick-- == 0) {
                return t;
            }
        }
        return 0;
    }

    private static int findTx(int width, int height) {
        for (var t = 0; t < TX_SIZES.length; t++) {
            if (TX_SIZES[t][0] == width && TX_SIZES[t][1] == height) {
                return t;
            }
        }
        return -1;
    }

    private int next() {
        var s = state;
        s ^= s << 13;
        s ^= s >>> 17;
        s ^= s << 5;
        state = s;
        return s;
    }

    private int rnd(int bound) {
        return Integer.remainderUnsigned(next(), bound);
    }

    private static long fnv(long h, int value) {
        for (var i = 0; i < 4; i++) {
            h ^= (value >>> (8 * i)) & 0xFF;
            h *= 0x100000001b3L;
        }
        return h;
    }

    private static String hex(long value) {
        return String.format("%016x", value);
    }
}
