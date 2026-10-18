package com.github.auties00.smalt.video.av1.internal;

import com.github.auties00.smalt.internal.simd.Simd;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Checks every motion compensation kernel against golden hashes produced by dav1d's C reference
 * ({@code mc_tmpl.c}, built without assembly) from the same pseudo-random inputs: each group runs
 * one kernel over every block size and parameter class at bit depth 8 (dav1d's 8bpc template) or
 * 10 (its 16bpc template) and folds the outputs into a 64-bit FNV-1a hash. The C harness is
 * {@code opt-av1dec-work/mc/harness.c} in the porting workspace.
 */
@DisplayName("AV1 motion compensation kernels")
class McDspTest {
    private static final String GOLDEN = """
            put_f0 8 821c668965566e8b
            put_f1 8 89a858f855d93778
            put_f2 8 576eb769235264b4
            put_f3 8 d58a73ec7732898d
            put_f4 8 0df6d3bda4532dcd
            put_f5 8 cb62738cf881342f
            put_f6 8 5922c97cfbc7eefe
            put_f7 8 10f34da96d62baa9
            put_f8 8 6820acb987151ca9
            put_f9 8 1f8e82df2dcec05e
            prep_f0 8 6fb6e40c33aa5563
            prep_f1 8 0b79df39d3068dbb
            prep_f2 8 593e95ced807a8c1
            prep_f3 8 0d6b23dabe015d3e
            prep_f4 8 ffdab98f498c07a7
            prep_f5 8 ae478cf212ddb505
            prep_f6 8 965aae0e5a94680a
            prep_f7 8 494aa28efd419bea
            prep_f8 8 06b843fac192cf07
            prep_f9 8 08069d0fb6f1201f
            put_scaled_f0 8 55cdeb9cce0d45d6
            put_scaled_f1 8 c605bbef223128a0
            put_scaled_f2 8 32e493d77088bd98
            put_scaled_f3 8 64e514cb5022eea3
            put_scaled_f4 8 2a551ee486ecd1f0
            put_scaled_f5 8 fbbe962c60b12450
            put_scaled_f6 8 7743af148f01ec63
            put_scaled_f7 8 4f04c838f6f7d165
            put_scaled_f8 8 552e213e4ba519b8
            put_scaled_f9 8 bafd23bdbbf8e4d1
            prep_scaled_f0 8 fcc17a2597b9f7e2
            prep_scaled_f1 8 4c634489ce02736c
            prep_scaled_f2 8 1b91bdf01d3b5528
            prep_scaled_f3 8 da2087462157c1b2
            prep_scaled_f4 8 e31270db79ed9116
            prep_scaled_f5 8 6a82d15829488e01
            prep_scaled_f6 8 32e2e9e439d737b7
            prep_scaled_f7 8 de770e5e33fb4f54
            prep_scaled_f8 8 40bfe392ca98ee80
            prep_scaled_f9 8 486cf228cd35f56c
            avg 8 20607491c4566cd0
            w_avg 8 a5a4c9e38db9922a
            mask 8 90b48f15cefedc2e
            w_mask_444 8 8c8364ef771fba9c
            w_mask_422 8 73bece75ce81d0e3
            w_mask_420 8 b23f5f06be8128cd
            blend 8 b15786dc30240ee9
            blend_v 8 8840806cac46020b
            blend_h 8 95c2ffd56e234b14
            warp8x8 8 be178ca6abe52d92
            warp8x8t 8 97f2ef66609d005c
            emu_edge 8 fc3b076af45999bb
            resize 8 4b363da105af7b71
            put_f0 10 0ac51497396711a2
            put_f1 10 1ab74c70490437e1
            put_f2 10 76f79e2dbdf2aabe
            put_f3 10 a6db9febe9febd71
            put_f4 10 d0681952bba714b8
            put_f5 10 082c80cf96b71987
            put_f6 10 2d53ef2ae669782d
            put_f7 10 7ac97b76a561dc20
            put_f8 10 cbc3d0539a064102
            put_f9 10 d2984400597d388c
            prep_f0 10 f258149dded5e3df
            prep_f1 10 0c386a44cb8f3818
            prep_f2 10 7c1d30ad46324f41
            prep_f3 10 e96ad798c35a5503
            prep_f4 10 d3b07bc308e28ee6
            prep_f5 10 87e6b5998abf3ece
            prep_f6 10 79877c3e7223984b
            prep_f7 10 f347a1e33113c40c
            prep_f8 10 ff6be220afc45608
            prep_f9 10 c5fd3751f5733e77
            put_scaled_f0 10 254a18d2f5b2243d
            put_scaled_f1 10 95054c95013c1824
            put_scaled_f2 10 2d0f6eabffe2b8e9
            put_scaled_f3 10 7efd26c8721e01c5
            put_scaled_f4 10 21a748f726f61d00
            put_scaled_f5 10 d5a201ba4c7802cb
            put_scaled_f6 10 f2cc5cc4924a260e
            put_scaled_f7 10 5cdef8ad13e0fc18
            put_scaled_f8 10 9d56ac8774a27201
            put_scaled_f9 10 58fb1987f5b56da8
            prep_scaled_f0 10 dfdc26f6b6d35fc2
            prep_scaled_f1 10 1b5a0cce75f97563
            prep_scaled_f2 10 aa0a41325448f492
            prep_scaled_f3 10 b448ffc697b7d0d4
            prep_scaled_f4 10 0143f2fc10978913
            prep_scaled_f5 10 5ce6987135308fed
            prep_scaled_f6 10 16e8631c84d4a2ca
            prep_scaled_f7 10 24b634ac111032de
            prep_scaled_f8 10 97bfe80534c3c049
            prep_scaled_f9 10 7ee5751a2e12a558
            avg 10 5e28ee200eaceaa4
            w_avg 10 2b7a5aa947733cef
            mask 10 ee0e04129e150cdb
            w_mask_444 10 6ab9e5d32f1f2b6d
            w_mask_422 10 556793ed9563090f
            w_mask_420 10 d987b24cb44dd9d2
            blend 10 90883176c13683d6
            blend_v 10 d63aadf0305a77b1
            blend_h 10 8ed96a23d2647777
            warp8x8 10 eaebb768d3512983
            warp8x8t 10 336bbb29f80d05a0
            emu_edge 10 1fe6f9bec47d473d
            resize 10 f4b5c0fb8809a118
            """;

    private static final int[] SIZES = {2, 4, 8, 16, 32, 64, 128};

    record Group(String name, int bitDepth, long expected) {
        @Override
        public String toString() {
            return name + " @ " + bitDepth + "-bit";
        }
    }

    static List<Group> groups() {
        return GOLDEN.lines()
                .map(String::strip)
                .filter(line -> !line.isEmpty())
                .map(line -> line.split(" "))
                .map(parts -> new Group(parts[0], Integer.parseInt(parts[1]), Long.parseUnsignedLong(parts[2], 16)))
                .toList();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("groups")
    @DisplayName("matches dav1d's C output")
    void scalar(Group group) {
        assertEquals(Long.toHexString(group.expected()), Long.toHexString(hash(new ScalarMcDsp(), group.name(), group.bitDepth())));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("groups")
    @DisplayName("the vector kernels match dav1d's C output")
    void vector(Group group) {
        Assumptions.assumeTrue(Simd.ENABLED, "the Vector API is unavailable");
        assertEquals(Long.toHexString(group.expected()), Long.toHexString(hash(VectorMcDsp.create(), group.name(), group.bitDepth())));
    }

    @ParameterizedTest(name = "{0}-bit")
    @ValueSource(ints = {8, 10, 12})
    @DisplayName("the vector translation-only warps match the scalar kernels on extreme samples")
    void translationWarp(int bitDepth) {
        Assumptions.assumeTrue(Simd.ENABLED, "the Vector API is unavailable");
        var vector = VectorMcDsp.create();
        var scalar = new ScalarMcDsp();
        var max = (1 << bitDepth) - 1;
        var random = new java.util.Random(7 + bitDepth);
        var stride = 24;
        var src = new short[stride * 24];
        var abcd = new short[4];
        var scratch = new short[McDsp.SCRATCH_SHORTS];
        var indices = new int[McDsp.WARP_SCRATCH_INTS];
        for (var c = 0; c < 20000; c++) {
            var mode = c % 4;
            for (var i = 0; i < src.length; i++) {
                src[i] = (short) switch (mode) {
                    case 0 -> random.nextInt(max + 1);
                    case 1 -> random.nextBoolean() ? max : 0;
                    case 2 -> ((i / 3 + i / stride) & 1) == 0 ? max : 0;
                    default -> random.nextInt(8) == 0 ? max : 0;
                };
            }
            var mx = random.nextInt(65536) & ~0x3f;
            var my = random.nextInt(65536) & ~0x3f;
            var expected = new short[64];
            var actual = new short[64];
            if ((c & 1) == 0) {
                scalar.warp8x8(expected, 0, 8, src, 3 * stride + 3, stride, abcd, mx, my, scratch, indices, max);
                vector.warp8x8(actual, 0, 8, src, 3 * stride + 3, stride, abcd, mx, my, scratch, indices, max);
            } else {
                scalar.warp8x8t(expected, 0, 8, src, 3 * stride + 3, stride, abcd, mx, my, scratch, indices, max);
                vector.warp8x8t(actual, 0, 8, src, 3 * stride + 3, stride, abcd, mx, my, scratch, indices, max);
            }
            assertArrayEquals(expected, actual, "case " + c + " mx " + mx + " my " + my);
        }
    }

    @ParameterizedTest(name = "{0}-bit")
    @ValueSource(ints = {8, 10, 12})
    @DisplayName("the vector filters and compound averages match the scalar kernels on extreme samples")
    void extremeSamples(int bitDepth) {
        Assumptions.assumeTrue(Simd.ENABLED, "the Vector API is unavailable");
        var vector = VectorMcDsp.create();
        var scalar = new ScalarMcDsp();
        var max = (1 << bitDepth) - 1;
        var random = new java.util.Random(11 + bitDepth);
        var stride = 160;
        var src = new short[stride * 160];
        var scratchA = new short[McDsp.SCRATCH_SHORTS];
        var scratchB = new short[McDsp.SCRATCH_SHORTS];
        var fractions = new int[]{0, 1, 5, 8, 15};
        for (var round = 0; round < 12; round++) {
            for (var i = 0; i < src.length; i++) {
                src[i] = (short) switch (round % 4) {
                    case 0 -> random.nextInt(max + 1);
                    case 1 -> random.nextBoolean() ? max : 0;
                    case 2 -> ((i / 2 + i / stride) & 1) == 0 ? max : 0;
                    default -> random.nextInt(6) == 0 ? max : 0;
                };
            }
            for (var filter2d = 0; filter2d < McDsp.FILTER_2D_BILINEAR; filter2d++) {
                for (var w : new int[]{8, 16, 32, 64}) {
                    for (var h : new int[]{4, 8, 16, 32}) {
                        var mx = fractions[random.nextInt(fractions.length)];
                        var my = fractions[random.nextInt(fractions.length)];
                        var off = 8 * stride + 8;
                        var expected = new short[128 * 128];
                        var actual = new short[128 * 128];
                        scalar.mc(filter2d, expected, 0, 128, src, off, stride, w, h, mx, my, scratchA, max);
                        vector.mc(filter2d, actual, 0, 128, src, off, stride, w, h, mx, my, scratchB, max);
                        assertArrayEquals(expected, actual, "mc " + filter2d + " " + w + "x" + h + " " + mx + "," + my);
                        var expectedPrep = new short[128 * 128];
                        var actualPrep = new short[128 * 128];
                        scalar.mct(filter2d, expectedPrep, 0, src, off, stride, w, h, mx, my, scratchA, max);
                        vector.mct(filter2d, actualPrep, 0, src, off, stride, w, h, mx, my, scratchB, max);
                        assertArrayEquals(expectedPrep, actualPrep,
                                "mct " + filter2d + " " + w + "x" + h + " " + mx + "," + my);
                        var other = new short[128 * 128];
                        scalar.mct(filter2d, other, 0, src, off + 3 * stride + 1, stride, w, h, my, mx, scratchA, max);
                        var expectedAvg = new short[128 * 128];
                        var actualAvg = new short[128 * 128];
                        scalar.avg(expectedAvg, 0, 128, expectedPrep, 0, other, 0, w, h, max);
                        vector.avg(actualAvg, 0, 128, expectedPrep, 0, other, 0, w, h, max);
                        assertArrayEquals(expectedAvg, actualAvg, "avg " + w + "x" + h);
                    }
                }
            }
        }
    }

    static long hash(McDsp dsp, String name, int bitDepth) {
        var harness = new Harness(dsp, bitDepth);
        var names = groupNames();
        var k = names.indexOf(name);
        harness.begin(k);
        if (name.startsWith("put_scaled_f")) {
            harness.scaled(Integer.parseInt(name.substring(12)), false);
        } else if (name.startsWith("prep_scaled_f")) {
            harness.scaled(Integer.parseInt(name.substring(13)), true);
        } else if (name.startsWith("put_f")) {
            harness.putPrep(Integer.parseInt(name.substring(5)), false);
        } else if (name.startsWith("prep_f")) {
            harness.putPrep(Integer.parseInt(name.substring(6)), true);
        } else {
            switch (name) {
                case "avg" -> harness.compound(0);
                case "w_avg" -> harness.compound(1);
                case "mask" -> harness.compound(2);
                case "w_mask_444" -> harness.wMask(0);
                case "w_mask_422" -> harness.wMask(1);
                case "w_mask_420" -> harness.wMask(2);
                case "blend" -> harness.blend(0);
                case "blend_v" -> harness.blend(1);
                case "blend_h" -> harness.blend(2);
                case "warp8x8" -> harness.warp(false);
                case "warp8x8t" -> harness.warp(true);
                case "emu_edge" -> harness.emu();
                case "resize" -> harness.resize();
                default -> throw new IllegalArgumentException(name);
            }
        }
        return harness.hash;
    }

    static List<String> groupNames() {
        var names = new java.util.ArrayList<String>();
        for (var prefix : new String[]{"put_f", "prep_f", "put_scaled_f", "prep_scaled_f"}) {
            for (var f = 0; f < 10; f++) {
                names.add(prefix + f);
            }
        }
        names.addAll(Arrays.asList("avg", "w_avg", "mask", "w_mask_444", "w_mask_422", "w_mask_420", "blend",
                "blend_v", "blend_h", "warp8x8", "warp8x8t", "emu_edge", "resize"));
        return names;
    }

    // Mirrors harness.c: the same xorshift32 stream, case order and hashing.
    static final class Harness {
        final McDsp dsp;
        final int bd;
        final int max;
        final int prepLo;
        final int prepHi;
        final short[] src = new short[300 * 300];
        final short[] dst = new short[300 * 300];
        final short[] tmp = new short[130 * 130];
        final short[] tmp1 = new short[130 * 130];
        final short[] tmp2 = new short[130 * 130];
        final byte[] mask = new byte[130 * 130];
        final short[] scratch = new short[McDsp.SCRATCH_SHORTS];
        final int[] indices = new int[McDsp.WARP_SCRATCH_INTS];
        int state;
        long hash;

        Harness(McDsp dsp, int bitDepth) {
            this.dsp = dsp;
            this.bd = bitDepth;
            this.max = (1 << bitDepth) - 1;
            this.prepLo = bitDepth == 8 ? -5132 : -20588 - 8192;
            this.prepHi = bitDepth == 8 ? 9212 : 36956 - 8192;
        }

        void begin(int k) {
            state = 0x2545F491 * (k + 1) + bd;
            if (state == 0) {
                state = 1;
            }
            hash = 0xcbf29ce484222325L;
        }

        int next() {
            state ^= state << 13;
            state ^= state >>> 17;
            state ^= state << 5;
            return state;
        }

        int below(int bound) {
            return Integer.remainderUnsigned(next(), bound);
        }

        void put(int value) {
            for (var i = 0; i < 4; i++) {
                hash ^= (value >>> (8 * i)) & 0xff;
                hash *= 0x100000001b3L;
            }
        }

        void fill(short[] plane, int count) {
            for (var i = 0; i < count; i++) {
                plane[i] = (short) (next() & max);
            }
        }

        void fillPrep(short[] values, int count) {
            for (var i = 0; i < count; i++) {
                values[i] = (short) (prepLo + below(prepHi - prepLo + 1));
            }
        }

        void hashPlane(short[] plane, int stride, int w, int h) {
            for (var y = 0; y < h; y++) {
                for (var x = 0; x < w; x++) {
                    put(plane[y * stride + x]);
                }
            }
        }

        void putPrep(int filter, boolean prep) {
            for (var w : SIZES) {
                for (var h : SIZES) {
                    for (var c = 0; c < 4; c++) {
                        var mx = (c == 1 || c == 3) ? 1 + below(15) : 0;
                        var my = (c == 2 || c == 3) ? 1 + below(15) : 0;
                        var ss = w + 8 + below(16);
                        fill(src, ss * (h + 8));
                        var sp = 3 * ss + 3;
                        if (prep) {
                            dsp.mct(filter, tmp, 0, src, sp, ss, w, h, mx, my, scratch, max);
                            for (var i = 0; i < w * h; i++) {
                                put(tmp[i]);
                            }
                        } else {
                            var ds = w + below(8);
                            fill(dst, ds * h);
                            dsp.mc(filter, dst, 0, ds, src, sp, ss, w, h, mx, my, scratch, max);
                            hashPlane(dst, ds, w, h);
                        }
                    }
                }
            }
        }

        void scaled(int filter, boolean prep) {
            for (var w : SIZES) {
                for (var h : SIZES) {
                    for (var c = 0; c < 2; c++) {
                        var mx = below(1024);
                        var my = below(1024);
                        var dx = 64 + below(1985);
                        var dy = 64 + below(1985);
                        var cols = ((mx + (w - 1) * dx) >> 10) + 9;
                        var rows = ((my + (h - 1) * dy) >> 10) + 9;
                        var ss = cols + below(8);
                        fill(src, ss * rows);
                        var sp = 3 * ss + 3;
                        if (prep) {
                            dsp.mctScaled(filter, tmp, 0, src, sp, ss, w, h, mx, my, dx, dy, scratch, max);
                            for (var i = 0; i < w * h; i++) {
                                put(tmp[i]);
                            }
                        } else {
                            var ds = w + below(8);
                            fill(dst, ds * h);
                            dsp.mcScaled(filter, dst, 0, ds, src, sp, ss, w, h, mx, my, dx, dy, scratch, max);
                            hashPlane(dst, ds, w, h);
                        }
                    }
                }
            }
        }

        void compound(int kind) {
            for (var w : SIZES) {
                for (var h : SIZES) {
                    for (var c = 0; c < 2; c++) {
                        fillPrep(tmp1, w * h);
                        fillPrep(tmp2, w * h);
                        var ds = w + below(8);
                        fill(dst, ds * h);
                        if (kind == 0) {
                            dsp.avg(dst, 0, ds, tmp1, 0, tmp2, 0, w, h, max);
                        } else if (kind == 1) {
                            var weight = 1 + below(15);
                            dsp.wAvg(dst, 0, ds, tmp1, 0, tmp2, 0, w, h, weight, max);
                        } else {
                            for (var i = 0; i < w * h; i++) {
                                mask[i] = (byte) below(65);
                            }
                            dsp.mask(dst, 0, ds, tmp1, 0, tmp2, 0, w, h, mask, 0, max);
                        }
                        hashPlane(dst, ds, w, h);
                    }
                }
            }
        }

        void wMask(int layout) {
            var sizes = new int[]{8, 16, 32, 64, 128};
            var ssHor = layout > 0 ? 1 : 0;
            var ssVer = layout > 1 ? 1 : 0;
            for (var w : sizes) {
                for (var h : sizes) {
                    for (var c = 0; c < 2; c++) {
                        fillPrep(tmp1, w * h);
                        fillPrep(tmp2, w * h);
                        var ds = w + below(8);
                        fill(dst, ds * h);
                        var count = (w >> ssHor) * (h >> ssVer);
                        for (var i = 0; i < count; i++) {
                            mask[i] = (byte) next();
                        }
                        dsp.wMask(layout, dst, 0, ds, tmp1, 0, tmp2, 0, w, h, mask, 0, c, max);
                        hashPlane(dst, ds, w, h);
                        for (var i = 0; i < count; i++) {
                            put(mask[i] & 0xFF);
                        }
                    }
                }
            }
        }

        void blend(int kind) {
            for (var w : SIZES) {
                for (var h : SIZES) {
                    if ((kind == 1 && w > 32) || (kind == 2 && h > 32)) {
                        continue;
                    }
                    for (var c = 0; c < 2; c++) {
                        fill(src, w * h);
                        var ds = w + below(8);
                        fill(dst, ds * h);
                        if (kind == 0) {
                            for (var i = 0; i < w * h; i++) {
                                mask[i] = (byte) below(65);
                            }
                            dsp.blend(dst, 0, ds, src, 0, w, h, mask, 0);
                        } else if (kind == 1) {
                            dsp.blendV(dst, 0, ds, src, 0, w, h);
                        } else {
                            dsp.blendH(dst, 0, ds, src, 0, w, h);
                        }
                        hashPlane(dst, ds, w, h);
                    }
                }
            }
        }

        int signedRange(int limit) {
            return below(2 * limit + 1) - limit;
        }

        void warp(boolean intermediate) {
            var abcd = new short[4];
            for (var c = 0; c < 400; c++) {
                abcd[0] = (short) (signedRange(127) * 64);
                abcd[1] = (short) (signedRange(72) * 64);
                abcd[2] = (short) (signedRange(127) * 64);
                abcd[3] = (short) (signedRange(127) * 64);
                var mx = ((next() & 0xffff) - abcd[0] * 4 - abcd[1] * 7) & ~0x3f;
                var my = ((next() & 0xffff) - abcd[2] * 4 - abcd[3] * 4) & ~0x3f;
                var ss = 16 + below(16);
                fill(src, ss * 16);
                var sp = 3 * ss + 3;
                if (intermediate) {
                    var ts = 8 + below(8);
                    for (var i = 0; i < ts * 8; i++) {
                        tmp[i] = (short) next();
                    }
                    dsp.warp8x8t(tmp, 0, ts, src, sp, ss, abcd, mx, my, scratch, indices, max);
                    hashPlane(tmp, ts, 8, 8);
                } else {
                    var ds = 8 + below(8);
                    fill(dst, ds * 8);
                    dsp.warp8x8(dst, 0, ds, src, sp, ss, abcd, mx, my, scratch, indices, max);
                    hashPlane(dst, ds, 8, 8);
                }
            }
        }

        void emu() {
            for (var c = 0; c < 400; c++) {
                var bw = 1 + below(135);
                var bh = 1 + below(135);
                var iw = 1 + below(200);
                var ih = 1 + below(200);
                var x = -(bw - 1) + below(iw + bw - 1);
                var y = -(bh - 1) + below(ih + bh - 1);
                var rs = iw + below(8);
                fill(src, rs * ih);
                var ds = bw + below(8);
                fill(dst, ds * bh);
                dsp.emuEdge(bw, bh, iw, ih, x, y, dst, 0, ds, src, 0, rs);
                hashPlane(dst, ds, bw, bh);
            }
        }

        void resize() {
            for (var c = 0; c < 200; c++) {
                var outW = 16 + below(273);
                var denom = 9 + below(8);
                var inW = (outW * 8 + (denom >> 1)) / denom;
                var h = 1 + below(8);
                var step = ((inW << 14) + (outW >> 1)) / outW;
                var err = outW * step - (inW << 14);
                var x0 = ((-((outW - inW) << 13) + (outW >> 1)) / outW + 128 - (err / 2)) & 0x3fff;
                var ss = inW + below(8);
                fill(src, ss * h);
                var ds = outW + below(8);
                fill(dst, ds * h);
                dsp.resize(dst, 0, ds, src, 0, ss, outW, h, inW, step, x0, max);
                hashPlane(dst, ds, outW, h);
            }
        }
    }
}
