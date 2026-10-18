package com.github.auties00.smalt.video.av1.internal;

import com.github.auties00.smalt.internal.simd.Simd;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Checks the scalar CDEF kernels bit for bit against dav1d's C reference. Each group of
 * pseudo-random cases (a kernel, a block layout, an edge-flag combination and a bit depth) is
 * folded into a 64-bit FNV-1a hash; the expected hashes were produced by dav1d's
 * {@code cdef_tmpl.c} through a C harness that generates the same inputs from the same xorshift32
 * sequence, seeded with the FNV-1a 32 hash of the group name.
 */
@DisplayName("AV1 CDEF kernels match dav1d's C reference")
class CdefDspTest {
    private static final int FILTER_STRIDE = 32;

    private static final String EXPECTED = """
            cdef.dir.b8 59f241550dce4f99
            cdef.filter.L0.e0.b8 8c3999dc973aa4bf
            cdef.filter.L0.e1.b8 aa9fe2cc10674996
            cdef.filter.L0.e2.b8 568a28dfe1199706
            cdef.filter.L0.e3.b8 9d45e5ea662731bc
            cdef.filter.L0.e4.b8 e22ab8ad5f48a1d0
            cdef.filter.L0.e5.b8 93db848498c22fe1
            cdef.filter.L0.e6.b8 5eac86cf221b9ef8
            cdef.filter.L0.e7.b8 d2583d1eba12ca45
            cdef.filter.L0.e8.b8 35cd7565e2b93f0c
            cdef.filter.L0.e9.b8 7989c88bc7a4daf7
            cdef.filter.L0.e10.b8 0da781553cdae4c3
            cdef.filter.L0.e11.b8 740e3019bb18880e
            cdef.filter.L0.e12.b8 0b8c1decfd76e82f
            cdef.filter.L0.e13.b8 619efae60f6013d3
            cdef.filter.L0.e14.b8 ec3e21b3af8c2954
            cdef.filter.L0.e15.b8 59708b9c6d381ec3
            cdef.filter.L1.e0.b8 40ae814e4da202a3
            cdef.filter.L1.e1.b8 797dda0261464c39
            cdef.filter.L1.e2.b8 e7dbd473d2671b6e
            cdef.filter.L1.e3.b8 a039201823032f52
            cdef.filter.L1.e4.b8 9ed0671f433266da
            cdef.filter.L1.e5.b8 e76036694928a4ef
            cdef.filter.L1.e6.b8 5be80237fc1b3660
            cdef.filter.L1.e7.b8 78f2109e15eb9ffa
            cdef.filter.L1.e8.b8 07cbc6c16ac8fbfe
            cdef.filter.L1.e9.b8 e85e32189ad289fd
            cdef.filter.L1.e10.b8 72aee72e8c7c1615
            cdef.filter.L1.e11.b8 8591b884611cfbcf
            cdef.filter.L1.e12.b8 90e3935499c8d061
            cdef.filter.L1.e13.b8 bde360a57fc83be4
            cdef.filter.L1.e14.b8 555ecd7f3539fc7e
            cdef.filter.L1.e15.b8 2fe98925e5e4702b
            cdef.filter.L2.e0.b8 e998d77b1014bc7e
            cdef.filter.L2.e1.b8 a05f1775f623f317
            cdef.filter.L2.e2.b8 8b041dce309d4ab7
            cdef.filter.L2.e3.b8 0aa946cfae4f9a75
            cdef.filter.L2.e4.b8 d4fe555bc3203636
            cdef.filter.L2.e5.b8 0c8c90fea750e24a
            cdef.filter.L2.e6.b8 65e6e308772376ad
            cdef.filter.L2.e7.b8 e6a23c281a69e7b6
            cdef.filter.L2.e8.b8 290f34e22c380a76
            cdef.filter.L2.e9.b8 65ac0103d01fc6db
            cdef.filter.L2.e10.b8 2b01f64ce8a32332
            cdef.filter.L2.e11.b8 5471e2f2c8728a1e
            cdef.filter.L2.e12.b8 932c8f0f1b0d4204
            cdef.filter.L2.e13.b8 499ffe68ff43cf9a
            cdef.filter.L2.e14.b8 2b4207bbf6b2fa6a
            cdef.filter.L2.e15.b8 68db4d61435f628f
            cdef.dir.b10 854c5d5f92059048
            cdef.filter.L0.e0.b10 6cdf0c17116ad16e
            cdef.filter.L0.e1.b10 a0a1ec95911c5503
            cdef.filter.L0.e2.b10 a04b8b5b1b0c54c5
            cdef.filter.L0.e3.b10 127be7f0e9dab771
            cdef.filter.L0.e4.b10 0123f59473ecb5e5
            cdef.filter.L0.e5.b10 b65fddef1731480f
            cdef.filter.L0.e6.b10 6ce1879f3e16f2fb
            cdef.filter.L0.e7.b10 5e7154ab9ef9b277
            cdef.filter.L0.e8.b10 cc048f61caad9e38
            cdef.filter.L0.e9.b10 91d1b44ac34af91c
            cdef.filter.L0.e10.b10 719c57b1b4793bfe
            cdef.filter.L0.e11.b10 d38dd23cdc4bfba8
            cdef.filter.L0.e12.b10 98ee2c41d8d91b39
            cdef.filter.L0.e13.b10 bdc03284b250b9d9
            cdef.filter.L0.e14.b10 724b491bea0cd82e
            cdef.filter.L0.e15.b10 8607cf2357f24439
            cdef.filter.L1.e0.b10 72a2fe7d72e7813b
            cdef.filter.L1.e1.b10 3a8b20955aacd840
            cdef.filter.L1.e2.b10 545dbb0d50af820e
            cdef.filter.L1.e3.b10 94e49df86cb73a94
            cdef.filter.L1.e4.b10 bb639b19a4a0317e
            cdef.filter.L1.e5.b10 068885ef18384ce2
            cdef.filter.L1.e6.b10 5ba66b3ff2073def
            cdef.filter.L1.e7.b10 553b9334c2e699d4
            cdef.filter.L1.e8.b10 71daca7e1c7c6a4b
            cdef.filter.L1.e9.b10 805ee59322ec5fee
            cdef.filter.L1.e10.b10 dada04bbd5c3bb6d
            cdef.filter.L1.e11.b10 6ab790a6106f018f
            cdef.filter.L1.e12.b10 155251d70969e5d6
            cdef.filter.L1.e13.b10 1f5a17270c002d8d
            cdef.filter.L1.e14.b10 420e142a722ecafa
            cdef.filter.L1.e15.b10 4178bb5360e7879a
            cdef.filter.L2.e0.b10 16234c86cf0f476f
            cdef.filter.L2.e1.b10 915c251bd3a0aa90
            cdef.filter.L2.e2.b10 85ef193623dc8f54
            cdef.filter.L2.e3.b10 6a5b13674ce947eb
            cdef.filter.L2.e4.b10 7863a7e32aec5d98
            cdef.filter.L2.e5.b10 fd79a0b934edfc37
            cdef.filter.L2.e6.b10 165a83c1589c0be6
            cdef.filter.L2.e7.b10 e126a69ed82804f4
            cdef.filter.L2.e8.b10 38d99a0a32e1fd99
            cdef.filter.L2.e9.b10 9e05c5b29b07a9f2
            cdef.filter.L2.e10.b10 9801cb1041dcb2ff
            cdef.filter.L2.e11.b10 1ef6dd3b52e152da
            cdef.filter.L2.e12.b10 44740bf721ecd020
            cdef.filter.L2.e13.b10 16054f5122232310
            cdef.filter.L2.e14.b10 97641f20a36e4375
            cdef.filter.L2.e15.b10 e9ab389f76c146d0
            cdef.dir.b12 78d5eafb1de48ac2
            cdef.filter.L0.e0.b12 6d3a44c335e5a0e7
            cdef.filter.L0.e1.b12 825d605031aa26c4
            cdef.filter.L0.e2.b12 fd29951f6bd15500
            cdef.filter.L0.e3.b12 e0ef0171edd3382b
            cdef.filter.L0.e4.b12 f6fce0031e284cf5
            cdef.filter.L0.e5.b12 ab67f1682d7abb41
            cdef.filter.L0.e6.b12 a70aeff17711ba3c
            cdef.filter.L0.e7.b12 912f8fe43896aa2e
            cdef.filter.L0.e8.b12 1e6716cc88f254a6
            cdef.filter.L0.e9.b12 522a7efb4f47acf2
            cdef.filter.L0.e10.b12 6ddd1be4375891e6
            cdef.filter.L0.e11.b12 1f6485e745f71578
            cdef.filter.L0.e12.b12 55fd03525eed7206
            cdef.filter.L0.e13.b12 5f41fb4e09dd3cb2
            cdef.filter.L0.e14.b12 1e8b33366892e628
            cdef.filter.L0.e15.b12 2dc214b5818b32c5
            cdef.filter.L1.e0.b12 8c8f35b9b3066b9c
            cdef.filter.L1.e1.b12 c03ad25715e4f5c6
            cdef.filter.L1.e2.b12 f7f3e11720ebd3ef
            cdef.filter.L1.e3.b12 e05036a7f93d406a
            cdef.filter.L1.e4.b12 f989a81962f2818a
            cdef.filter.L1.e5.b12 1218d4fc91042aa0
            cdef.filter.L1.e6.b12 9bca33cf4c1d6601
            cdef.filter.L1.e7.b12 aa9d327ad645b75b
            cdef.filter.L1.e8.b12 6a8b68ca43fbaad5
            cdef.filter.L1.e9.b12 9d335853ab7ec0c4
            cdef.filter.L1.e10.b12 ff386aca98f5b41e
            cdef.filter.L1.e11.b12 6e8df17a271a3fd7
            cdef.filter.L1.e12.b12 53f3ea97c1bcd444
            cdef.filter.L1.e13.b12 65a0b23780f3346b
            cdef.filter.L1.e14.b12 c0b272e660fd98b2
            cdef.filter.L1.e15.b12 4ecebf1709ed9d59
            cdef.filter.L2.e0.b12 ebc3c45d80cdd00a
            cdef.filter.L2.e1.b12 dc6651fae49cd1e1
            cdef.filter.L2.e2.b12 ecbd9c80013b4868
            cdef.filter.L2.e3.b12 bafc79feb196dcbe
            cdef.filter.L2.e4.b12 740fc60cdbf5218f
            cdef.filter.L2.e5.b12 7b4e7cda7b8a5bf8
            cdef.filter.L2.e6.b12 e557dd609b72931c
            cdef.filter.L2.e7.b12 53e1c8128dc6dfa4
            cdef.filter.L2.e8.b12 f5d873d0d0f895ad
            cdef.filter.L2.e9.b12 d3f6cbd78a58bb68
            cdef.filter.L2.e10.b12 c4344109927bc447
            cdef.filter.L2.e11.b12 0286c7f5244b6893
            cdef.filter.L2.e12.b12 68345772c2f3e444
            cdef.filter.L2.e13.b12 4726cc41bf4de61f
            cdef.filter.L2.e14.b12 80eb6654800e40a2
            cdef.filter.L2.e15.b12 b17781a9a15bf60c
            """;

    static Stream<Arguments> groups() {
        return EXPECTED.lines()
                .map(String::strip)
                .filter(line -> !line.isEmpty())
                .map(line -> line.split(" "))
                .map(parts -> Arguments.of(parts[0], parts[1]));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("groups")
    @DisplayName("produces dav1d's output hash")
    void group(String name, String expected) {
        assertEquals(expected, hash(new ScalarCdefDsp(), name));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("groups")
    @DisplayName("the vector kernels produce dav1d's output hash")
    void vector(String name, String expected) {
        Assumptions.assumeTrue(Simd.ENABLED, "the Vector API is unavailable");
        assertEquals(expected, hash(VectorCdefDsp.create(), name));
    }

    static String hash(CdefDsp dsp, String name) {
        var parts = name.split("\\.");
        var bitdepth = Integer.parseInt(parts[parts.length - 1].substring(1));
        if (parts[1].equals("dir")) {
            return hashDir(dsp, name, bitdepth);
        }
        var layout = Integer.parseInt(parts[2].substring(1));
        var edges = Integer.parseInt(parts[3].substring(1));
        return hashFilter(dsp, name, layout, edges, bitdepth);
    }

    private static String hashDir(CdefDsp dsp, String name, int bitdepth) {
        var rng = new Rng(name);
        var max = (1 << bitdepth) - 1;
        var bdm8 = bitdepth - 8;
        var buf = new short[8 * 16];
        var variance = new int[CdefDsp.DIR_SCRATCH_INTS];
        var hash = new Fnv();
        for (var n = 0; n < 4000; n++) {
            if (rng.rnd(2) == 0) {
                for (var i = 0; i < buf.length; i++) {
                    buf[i] = (short) rng.rnd(max + 1);
                }
            } else {
                var c0 = rng.rnd(max + 1);
                var dx = rng.rnd(65) - 32;
                var dy = rng.rnd(65) - 32;
                for (var y = 0; y < 8; y++) {
                    for (var x = 0; x < 16; x++) {
                        var value = c0 + (((x * dx + y * dy) * (1 << bdm8)) >> 2) + rng.rnd(9) - 4;
                        buf[y * 16 + x] = (short) Math.clamp(value, 0, max);
                    }
                }
            }
            var dir = dsp.dir(buf, 0, 16, variance, max);
            hash.add(dir);
            hash.add(variance[0]);
        }
        return hash.hex();
    }

    private static String hashFilter(CdefDsp dsp, String name, int layout, int edges, int bitdepth) {
        var rng = new Rng(name);
        var max = (1 << bitdepth) - 1;
        var bdm8 = bitdepth - 8;
        var w = layout == CdefDsp.LAYOUT_8X8 ? 8 : 4;
        var h = layout == CdefDsp.LAYOUT_4X4 ? 4 : 8;
        var dst = new short[8 * FILTER_STRIDE];
        var left = new short[8 * 2];
        var top = new short[2 * FILTER_STRIDE];
        var bottom = new short[2 * FILTER_STRIDE];
        var scratch = new short[CdefDsp.SCRATCH_SHORTS];
        var hash = new Fnv();
        for (var n = 0; n < 150; n++) {
            for (var i = 0; i < h * FILTER_STRIDE; i++) {
                dst[i] = (short) rng.rnd(max + 1);
            }
            for (var y = 0; y < h; y++) {
                left[2 * y] = (short) rng.rnd(max + 1);
                left[2 * y + 1] = (short) rng.rnd(max + 1);
            }
            for (var i = 0; i < top.length; i++) {
                top[i] = (short) rng.rnd(max + 1);
            }
            for (var i = 0; i < bottom.length; i++) {
                bottom[i] = (short) rng.rnd(max + 1);
            }
            var mode = rng.rnd(3);
            var pri = 0;
            var sec = 0;
            if (mode != 1) {
                pri = 1 + rng.rnd(15 << bdm8);
            }
            if (mode != 0) {
                var s = 1 + rng.rnd(3);
                sec = (s + (s == 3 ? 1 : 0)) << bdm8;
            }
            var damping = 2 + bdm8 + rng.rnd(5);
            var dir = rng.rnd(8);
            dsp.filter(layout, dst, 4, FILTER_STRIDE, left, 0, top, 4, bottom, 4, pri, sec, dir, damping, edges,
                    scratch, max);
            for (var y = 0; y < h; y++) {
                for (var x = 0; x < w; x++) {
                    hash.add(dst[4 + y * FILTER_STRIDE + x]);
                }
            }
        }
        return hash.hex();
    }

    static final class Rng {
        private int state;

        Rng(String name) {
            var h = 0x811c9dc5;
            for (var b : name.getBytes(StandardCharsets.US_ASCII)) {
                h ^= b & 0xFF;
                h *= 0x01000193;
            }
            state = h | 1;
        }

        int next() {
            var s = state;
            s ^= s << 13;
            s ^= s >>> 17;
            s ^= s << 5;
            state = s;
            return s;
        }

        int rnd(int n) {
            return Integer.remainderUnsigned(next(), n);
        }
    }

    static final class Fnv {
        private long hash = 0xcbf29ce484222325L;

        void add(int value) {
            for (var i = 0; i < 4; i++) {
                hash ^= (value >>> (8 * i)) & 0xFF;
                hash *= 0x100000001b3L;
            }
        }

        String hex() {
            return String.format("%016x", hash);
        }
    }
}
