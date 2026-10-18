package com.github.auties00.smalt.video.av1.internal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Checks the film grain kernels and the whole-picture grain application against golden hashes
 * produced by dav1d's C reference ({@code filmgrain_tmpl.c} and {@code fg_apply_tmpl.c}, built
 * without assembly) from the same pseudo-random parameter sets, grain tables and pictures: each
 * group runs one kernel (or {@code dav1d_apply_grain}) for one layout at bit depth 8 (dav1d's 8bpc
 * template), 10 or 12 (its 16bpc template) and folds the outputs into a 64-bit FNV-1a hash. The C
 * harness is {@code opt-av1dec-work/fg/harness_tmpl.c} in the porting workspace.
 */
@DisplayName("AV1 film grain synthesis")
class FilmGrainDspTest {
    private static final String GOLDEN = """
            gy-8 bb5b774150ba8386
            guv-1-8 83bf568db6e93135
            guv-2-8 3aa2d820de5c4342
            guv-3-8 6b9aa46b3aa2b6f8
            fgy-8 fdccd12691dad8bc
            fguv-1-8 d03e50d79741844b
            fguv-2-8 db8f4bc6003a4a5e
            fguv-3-8 a883d2059e41247c
            apply-0-8 5366681404935e62
            apply-1-8 6f2ce494225ab7c6
            apply-2-8 3c9b58909bae0f4c
            apply-3-8 e854c7593e473fca
            gy-10 205d4cbf507444c3
            guv-1-10 3f8b04a186b1fc68
            guv-2-10 8f8db6b5cdaa91a4
            guv-3-10 bf86db8f323599e8
            fgy-10 86798f4d366c7a07
            fguv-1-10 3cd266117d0279b6
            fguv-2-10 047fd89cc3f9376d
            fguv-3-10 79ec60cee66a6c03
            apply-0-10 9653de122f372d98
            apply-1-10 579049f174f2f0d3
            apply-2-10 2cf04a5812f0fbd7
            apply-3-10 945ebf78bf3a3f66
            gy-12 c217548709cb1d9d
            guv-1-12 f6e9ad5ed816307f
            guv-2-12 d4244bf96be97ad3
            guv-3-12 afe6aed3db7a10c1
            fgy-12 04a6731d2071ad8b
            fguv-1-12 7be3d3aab5f0d919
            fguv-2-12 12c5c4aeb9d7ca25
            fguv-3-12 f3daa14b9816d288
            apply-0-12 3f688ebb86c36d17
            apply-1-12 fa9427eaa11b1cd2
            apply-2-12 3aa7ee90aea78552
            apply-3-12 4fb5e0445449f6b7
            """;

    private static final ScalarFilmGrainDsp DSP = new ScalarFilmGrainDsp();

    private int s;
    private long h;

    static List<String[]> groups() {
        return Arrays.stream(GOLDEN.strip().split("\n"))
                .map(line -> line.strip().split(" "))
                .toList();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("groups")
    @DisplayName("matches dav1d's C reference")
    void matchesReference(String group, String expected) {
        assertEquals(expected, String.format("%016x", new FilmGrainDspTest().compute(group)));
    }

    private int next() {
        s ^= s << 13;
        s ^= s >>> 17;
        s ^= s << 5;
        return s;
    }

    private int nextMod(int n) {
        return Integer.remainderUnsigned(next(), n);
    }

    private void fold(int v) {
        for (var i = 0; i < 4; i++) {
            h ^= (v >>> (8 * i)) & 0xff;
            h *= 0x100000001b3L;
        }
    }

    private void foldLut(short[] lut) {
        for (var v : lut) {
            fold(v);
        }
    }

    private int randPoints(int[][] points, int maxn) {
        var n = nextMod(maxn + 1);
        var pos = nextMod(20);
        int i;
        for (i = 0; i < n; i++) {
            if (pos > 255) {
                break;
            }
            points[i][0] = pos;
            points[i][1] = next() & 255;
            pos += 1 + nextMod(40);
        }
        return i;
    }

    private FilmGrainData randData(int layout) {
        var d = new FilmGrainData();
        d.seed = next() & 0xFFFF;
        d.numYPoints = randPoints(d.yPoints, 14);
        d.chromaScalingFromLuma = layout != 0 && (next() & 1) != 0;
        for (var pl = 0; pl < 2; pl++) {
            d.numUvPoints[pl] = layout != 0 && !d.chromaScalingFromLuma ? randPoints(d.uvPoints[pl], 10) : 0;
        }
        d.scalingShift = 8 + nextMod(4);
        d.arCoeffLag = nextMod(4);
        for (var i = 0; i < 24; i++) {
            d.arCoeffsY[i] = (next() & 255) - 128;
        }
        for (var pl = 0; pl < 2; pl++) {
            for (var i = 0; i < 25; i++) {
                d.arCoeffsUv[pl][i] = (next() & 255) - 128;
            }
        }
        d.arCoeffShift = 6 + nextMod(4);
        d.grainScaleShift = nextMod(4);
        for (var pl = 0; pl < 2; pl++) {
            d.uvMult[pl] = (next() & 255) - 128;
            d.uvLumaMult[pl] = (next() & 255) - 128;
            d.uvOffset[pl] = (next() & 511) - 256;
        }
        d.overlapFlag = (next() & 1) != 0;
        d.clipToRestrictedRange = (next() & 1) != 0;
        return d;
    }

    long compute(String group) {
        var parts = group.split("-");
        var bitdepth = Integer.parseInt(parts[parts.length - 1]);
        var bdmax = (1 << bitdepth) - 1;
        var layout = parts.length == 3 ? Integer.parseInt(parts[1]) : 1;
        h = 0xcbf29ce484222325L;
        switch (parts[0]) {
            case "gy" -> grainY(bitdepth, bdmax);
            case "guv" -> grainUv(bitdepth, bdmax, layout);
            case "fgy" -> fgy(bitdepth, bdmax);
            case "fguv" -> fguv(bitdepth, bdmax, layout);
            case "apply" -> apply(bitdepth, bdmax, layout);
            default -> throw new IllegalArgumentException(group);
        }
        return h;
    }

    private void grainY(int bitdepth, int bdmax) {
        s = 0x13579BDF ^ (bitdepth << 20);
        for (var n = 0; n < 32; n++) {
            var d = randData(1);
            var lut = new short[FilmGrainDsp.GRAIN_LUT_SIZE];
            DSP.generateGrainY(lut, d, bdmax);
            foldLut(lut);
        }
    }

    private void grainUv(int bitdepth, int bdmax, int layout) {
        s = 0x2468ACE1 ^ (bitdepth << 20) ^ (layout << 4);
        for (var n = 0; n < 24; n++) {
            var d = randData(layout);
            var luty = new short[FilmGrainDsp.GRAIN_LUT_SIZE];
            DSP.generateGrainY(luty, d, bdmax);
            var lutu = new short[FilmGrainDsp.GRAIN_LUT_SIZE];
            var lutv = new short[FilmGrainDsp.GRAIN_LUT_SIZE];
            DSP.generateGrainUv(layout, lutu, luty, d, 0, bdmax);
            DSP.generateGrainUv(layout, lutv, luty, d, 1, bdmax);
            foldLut(lutu);
            foldLut(lutv);
        }
    }

    private void fgy(int bitdepth, int bdmax) {
        s = 0x0F1E2D3C ^ (bitdepth << 20);
        var scalingSize = 1 << bitdepth;
        for (var n = 0; n < 48; n++) {
            var d = randData(1);
            var luty = new short[FilmGrainDsp.GRAIN_LUT_SIZE];
            DSP.generateGrainY(luty, d, bdmax);
            var scaling = new byte[scalingSize];
            for (var i = 0; i < scalingSize; i++) {
                scaling[i] = (byte) next();
            }
            var pw = 1 + nextMod(200);
            var bh = 1 + nextMod(32);
            var row = nextMod(4);
            var stride = (pw + 31) & ~31;
            var src = new short[stride * bh];
            var dst = new short[stride * bh];
            for (var i = 0; i < src.length; i++) {
                src[i] = (short) (next() & bdmax);
            }
            for (var i = 0; i < dst.length; i++) {
                dst[i] = (short) (next() & bdmax);
            }
            DSP.fgy32x32xn(dst, 0, src, 0, stride, d, pw, scaling, luty, bh, row, bdmax);
            for (var v : dst) {
                fold(v);
            }
        }
    }

    private void fguv(int bitdepth, int bdmax, int layout) {
        var sx = layout != 3 ? 1 : 0;
        var sy = layout == 1 ? 1 : 0;
        s = 0x5A5A1234 ^ (bitdepth << 20) ^ (layout << 4);
        var scalingSize = 1 << bitdepth;
        for (var n = 0; n < 48; n++) {
            var d = randData(layout);
            var luty = new short[FilmGrainDsp.GRAIN_LUT_SIZE];
            DSP.generateGrainY(luty, d, bdmax);
            var uv = next() & 1;
            var lutu = new short[FilmGrainDsp.GRAIN_LUT_SIZE];
            DSP.generateGrainUv(layout, lutu, luty, d, uv, bdmax);
            var scaling = new byte[scalingSize];
            for (var i = 0; i < scalingSize; i++) {
                scaling[i] = (byte) next();
            }
            var isId = (next() & 1) != 0;
            var cpw = 1 + nextMod(200 >> sx);
            var bh = 1 + nextMod(32 >> sy);
            var row = nextMod(4);
            var stride = (cpw + 31) & ~31;
            var lstride = ((cpw << sx) + 1 + 31) & ~31;
            var luma = new short[lstride * (bh << sy)];
            for (var i = 0; i < luma.length; i++) {
                luma[i] = (short) (next() & bdmax);
            }
            var src = new short[stride * bh];
            var dst = new short[stride * bh];
            for (var i = 0; i < src.length; i++) {
                src[i] = (short) (next() & bdmax);
            }
            for (var i = 0; i < dst.length; i++) {
                dst[i] = (short) (next() & bdmax);
            }
            DSP.fguv32x32xn(layout, dst, 0, src, 0, stride, d, cpw, scaling, lutu, bh, row, luma, 0, lstride, uv,
                    isId, bdmax);
            for (var v : dst) {
                fold(v);
            }
        }
    }

    private void apply(int bitdepth, int bdmax, int layout) {
        var ssx = layout == 1 || layout == 2 ? 1 : 0;
        var ssy = layout == 1 ? 1 : 0;
        s = 0x7EED5EED ^ (bitdepth << 20) ^ (layout << 4);
        for (var n = 0; n < 16; n++) {
            var w = 1 + nextMod(200);
            var ht = 1 + nextMod(150);
            var d = randData(layout);
            var isId = (next() & 1) != 0;
            var ys = (w + 1 + 31) & ~31;
            var cw = (w + ssx) >> ssx;
            var ch = (ht + ssy) >> ssy;
            var uvs = (cw + 31) & ~31;
            var in = new short[3][];
            var out = new short[3][];
            in[0] = new short[ys * ht];
            out[0] = new short[ys * ht];
            for (var i = 0; i < in[0].length; i++) {
                in[0][i] = (short) (next() & bdmax);
            }
            if (layout != 0) {
                for (var pl = 1; pl < 3; pl++) {
                    in[pl] = new short[uvs * ch];
                    out[pl] = new short[uvs * ch];
                    for (var i = 0; i < in[pl].length; i++) {
                        in[pl][i] = (short) (next() & bdmax);
                    }
                }
            }
            var offsets = new int[3];
            var scaling = new byte[3][FilmGrainApplier.scalingSize(bdmax)];
            var grainLut = new short[3][FilmGrainDsp.GRAIN_LUT_SIZE];
            FilmGrainApplier.applyGrain(DSP, out, offsets, in, offsets, ys, uvs, w, ht, layout, isId, bdmax, d,
                    scaling, grainLut);
            for (var y = 0; y < ht; y++) {
                for (var x = 0; x < w; x++) {
                    fold(out[0][y * ys + x]);
                }
            }
            if (layout != 0) {
                for (var pl = 1; pl < 3; pl++) {
                    for (var y = 0; y < ch; y++) {
                        for (var x = 0; x < cw; x++) {
                            fold(out[pl][y * uvs + x]);
                        }
                    }
                }
            }
        }
    }
}
