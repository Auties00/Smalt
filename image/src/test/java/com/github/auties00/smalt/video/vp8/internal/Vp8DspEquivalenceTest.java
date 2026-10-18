package com.github.auties00.smalt.video.vp8.internal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

/**
 * Checks that the Vector API decoder kernels produce exactly the output of the scalar reference
 * on randomized input; the loop filter cases also cover the in-register transposes of vertical
 * edges.
 */
@DisplayName("VP8 decoder kernels: vector equals scalar")
class Vp8DspEquivalenceTest {
    static final int STRIDE = 96;
    static final ScalarVp8Dsp SCALAR = new ScalarVp8Dsp();
    static final Vp8Dsp VECTOR = VectorVp8Dsp.create();

    static byte[] smoothPlane(Random random, int size, int noise) {
        var plane = new byte[size];
        var base = random.nextInt(256);
        for (var i = 0; i < size; i++) {
            var x = i % STRIDE;
            var y = i / STRIDE;
            var v = base + (x / 16) * random.nextInt(3) * (random.nextBoolean() ? 1 : -1) + y / 7 + random.nextInt(noise + 1) - noise / 2;
            if (random.nextInt(50) == 0) {
                v += random.nextInt(120) - 60;
            }
            plane[i] = (byte) Math.clamp(v, 0, 255);
        }
        return plane;
    }

    @ParameterizedTest(name = "noise {0}")
    @ValueSource(ints = {0, 2, 6, 20, 255})
    @DisplayName("normal and simple loop filters on random planes and parameters")
    void loopFilters(int noise) {
        var random = new Random(noise * 31L + 7);
        for (var trial = 0; trial < 400; trial++) {
            var y = smoothPlane(random, STRIDE * 64, noise);
            var u = smoothPlane(random, STRIDE * 40, noise);
            var v = smoothPlane(random, STRIDE * 40, noise);
            var level = 1 + random.nextInt(63);
            var sharp = random.nextInt(8);
            var interior = level >> (sharp > 0 ? 1 : 0);
            interior >>= sharp > 4 ? 1 : 0;
            if (sharp > 0 && interior > 9 - sharp) {
                interior = 9 - sharp;
            }
            interior = Math.max(interior, 1);
            var sub = 2 * level + interior;
            var mb = 2 * (level + 2) + interior;
            var hev = random.nextInt(4);
            var edges = 1 + random.nextInt(7);
            var yOff = 16 * STRIDE + 16 + 16 * random.nextInt(3);
            var uvOff = 12 * STRIDE + 12 + 8 * random.nextInt(3);
            var ys = y.clone();
            var us = u.clone();
            var vs = v.clone();
            var yv = y.clone();
            var uv = u.clone();
            var vv = v.clone();
            SCALAR.filterNormal(ys, yOff, STRIDE, us, vs, uvOff, STRIDE, mb, sub, interior, hev, edges, new byte[Vp8Dsp.SCRATCH_SIZE]);
            VECTOR.filterNormal(yv, yOff, STRIDE, uv, vv, uvOff, STRIDE, mb, sub, interior, hev, edges, new byte[Vp8Dsp.SCRATCH_SIZE]);
            assertArrayEquals(ys, yv, "normal luma, trial " + trial);
            assertArrayEquals(us, uv, "normal u, trial " + trial);
            assertArrayEquals(vs, vv, "normal v, trial " + trial);
            ys = y.clone();
            yv = y.clone();
            SCALAR.filterSimple(ys, yOff, STRIDE, mb, sub, edges, new byte[Vp8Dsp.SCRATCH_SIZE]);
            VECTOR.filterSimple(yv, yOff, STRIDE, mb, sub, edges, new byte[Vp8Dsp.SCRATCH_SIZE]);
            assertArrayEquals(ys, yv, "simple, trial " + trial);
        }
    }

    @Test
    @DisplayName("six-tap and bilinear prediction for every block size and fraction")
    void motionCompensation() {
        var random = new Random(11);
        var src = new byte[STRIDE * 64];
        random.nextBytes(src);
        var scratchS = new byte[Vp8Dsp.SCRATCH_SIZE];
        var scratchV = new byte[Vp8Dsp.SCRATCH_SIZE];
        int[][] sizes = {{16, 16}, {8, 8}, {8, 4}, {4, 4}};
        for (var size : sizes) {
            for (var mx = 0; mx < 8; mx++) {
                for (var my = 0; my < 8; my++) {
                    var off = 20 * STRIDE + 20 + random.nextInt(8);
                    var ds = new byte[32 * 32];
                    var dv = new byte[32 * 32];
                    SCALAR.sixtap(src, off, STRIDE, ds, 33, 32, size[0], size[1], mx, my, scratchS);
                    VECTOR.sixtap(src, off, STRIDE, dv, 33, 32, size[0], size[1], mx, my, scratchV);
                    assertArrayEquals(ds, dv, "sixtap " + size[0] + "x" + size[1] + " " + mx + "," + my);
                    ds = new byte[32 * 32];
                    dv = new byte[32 * 32];
                    SCALAR.bilinear(src, off, STRIDE, ds, 33, 32, size[0], size[1], mx, my, scratchS);
                    VECTOR.bilinear(src, off, STRIDE, dv, 33, 32, size[0], size[1], mx, my, scratchV);
                    assertArrayEquals(ds, dv, "bilinear " + size[0] + "x" + size[1] + " " + mx + "," + my);
                    ds = new byte[32 * 32];
                    dv = new byte[32 * 32];
                    SCALAR.copy(src, off, STRIDE, ds, 33, 32, size[0], size[1]);
                    VECTOR.copy(src, off, STRIDE, dv, 33, 32, size[0], size[1]);
                    assertArrayEquals(ds, dv, "copy " + size[0] + "x" + size[1]);
                }
            }
        }
    }

    @Test
    @DisplayName("inverse DCT and DC-only reconstruction, including 16-bit overflow")
    void transforms() {
        var random = new Random(3);
        for (var trial = 0; trial < 20000; trial++) {
            var coef = new short[16];
            var range = trial % 3 == 0 ? 65536 : (trial % 3 == 1 ? 4096 : 256);
            for (var i = 0; i < 16; i++) {
                coef[i] = (short) (random.nextInt(range) - range / 2);
            }
            var plane = new byte[8 * 8];
            random.nextBytes(plane);
            var ps = plane.clone();
            var pv = plane.clone();
            var cs = coef.clone();
            var cv = coef.clone();
            SCALAR.idctAdd(cs, 0, ps, 9, 8);
            VECTOR.idctAdd(cv, 0, pv, 9, 8);
            assertArrayEquals(ps, pv, "idct trial " + trial);
            assertArrayEquals(cs, cv, "coefficients cleared, trial " + trial);
            ps = plane.clone();
            pv = plane.clone();
            SCALAR.dcOnlyAdd(coef[0], ps, 9, 8);
            VECTOR.dcOnlyAdd(coef[0], pv, 9, 8);
            assertArrayEquals(ps, pv, "dc trial " + trial);
        }
    }

    @Test
    @DisplayName("DC-only add over every DC and extreme and random pixels")
    void dcOnlyExhaustive() {
        var random = new Random(77);
        for (var dc = -4200; dc <= 4200; dc++) {
            for (var kind = 0; kind < 4; kind++) {
                var plane = new byte[8 * 8];
                switch (kind) {
                    case 0 -> random.nextBytes(plane);
                    case 1 -> java.util.Arrays.fill(plane, (byte) 0);
                    case 2 -> java.util.Arrays.fill(plane, (byte) 255);
                    default -> {
                        for (var k = 0; k < plane.length; k++) {
                            plane[k] = (byte) (random.nextBoolean() ? 255 - random.nextInt(3) : random.nextInt(3));
                        }
                    }
                }
                var ps = plane.clone();
                var pv = plane.clone();
                SCALAR.dcOnlyAdd(dc, ps, 9, 8);
                VECTOR.dcOnlyAdd(dc, pv, 9, 8);
                assertArrayEquals(ps, pv, "dc " + dc + " kind " + kind);
            }
        }
    }
}
