package com.github.auties00.smalt.video.av1.internal;

import com.github.auties00.smalt.internal.simd.Simd;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Checks the scalar loop restoration kernels bit for bit against dav1d's C reference. Each group
 * of pseudo-random stripes (a filter, its tap count or self-guided form, an edge-flag combination
 * and a bit depth) is folded into a 64-bit FNV-1a hash; the expected hashes were produced by
 * dav1d's {@code looprestoration_tmpl.c} through a C harness that generates the same stripes,
 * left columns, saved neighbor rows and parameters from the same xorshift32 sequence, seeded with
 * the FNV-1a 32 hash of the group name. The C harness hands dav1d's 8-bit template its Wiener
 * center tap without the separately added 128, which {@link LrParams#filter} always includes.
 */
@DisplayName("AV1 loop restoration kernels match dav1d's C reference")
class LoopRestorationDspTest {
    private static final int STRIDE = 416;

    private static final String EXPECTED = """
            lr.wiener.t0.e0.b8 401dba02240557af
            lr.wiener.t0.e1.b8 0df51d15c98a618c
            lr.wiener.t0.e2.b8 9afe263225f958a6
            lr.wiener.t0.e3.b8 369c7042b6938fd1
            lr.wiener.t0.e4.b8 526b8e5ea04a4870
            lr.wiener.t0.e5.b8 bd1eb41be9dca1c4
            lr.wiener.t0.e6.b8 087b89568907a7f0
            lr.wiener.t0.e7.b8 65dfae0abbadb8a7
            lr.wiener.t0.e8.b8 1a71dcd96bd0d59d
            lr.wiener.t0.e9.b8 33a4abe2241c3ae9
            lr.wiener.t0.e10.b8 9275e6386e26d466
            lr.wiener.t0.e11.b8 f7b6e84d61a74f68
            lr.wiener.t0.e12.b8 55592a79a38d8a70
            lr.wiener.t0.e13.b8 e9cb9271ba047856
            lr.wiener.t0.e14.b8 d84b5154e6f4a85e
            lr.wiener.t0.e15.b8 1f1cc56a6cc808fa
            lr.wiener.t1.e0.b8 56b871369e13aeef
            lr.wiener.t1.e1.b8 2764051887899b66
            lr.wiener.t1.e2.b8 83a02faddc2f4921
            lr.wiener.t1.e3.b8 45c86a6af1bafafa
            lr.wiener.t1.e4.b8 a36f3fd60fb98e79
            lr.wiener.t1.e5.b8 5b42a565c7d42e61
            lr.wiener.t1.e6.b8 3b6c96ca609278db
            lr.wiener.t1.e7.b8 7d3c3e7c2a802c66
            lr.wiener.t1.e8.b8 5f4decb4288eb456
            lr.wiener.t1.e9.b8 b2ddecb4bad940a3
            lr.wiener.t1.e10.b8 4e188d4123993371
            lr.wiener.t1.e11.b8 8f5b71d95bd62d21
            lr.wiener.t1.e12.b8 2ea409a8562f7786
            lr.wiener.t1.e13.b8 2f134216b2c147fc
            lr.wiener.t1.e14.b8 119123d0ffabb7ba
            lr.wiener.t1.e15.b8 f0ca8fe10a1f6a44
            lr.sgr.T0.e0.b8 e60ab3c0225bedc0
            lr.sgr.T0.e1.b8 fdcf54635b414a33
            lr.sgr.T0.e2.b8 65787289b1737756
            lr.sgr.T0.e3.b8 0da0ab1108b9c57a
            lr.sgr.T0.e4.b8 1f88ec1399468f5b
            lr.sgr.T0.e5.b8 c9b0e1d72d98a87c
            lr.sgr.T0.e6.b8 dbbe15b44b72f393
            lr.sgr.T0.e7.b8 f1cd0ee3eff3e5ab
            lr.sgr.T0.e8.b8 df9b20c66ab88aee
            lr.sgr.T0.e9.b8 792ff1ecf3db34cd
            lr.sgr.T0.e10.b8 dad0c57186ceca4b
            lr.sgr.T0.e11.b8 18e800f4818f3008
            lr.sgr.T0.e12.b8 ba42bde0bb8b9398
            lr.sgr.T0.e13.b8 e4c4d67b4fdaa76a
            lr.sgr.T0.e14.b8 9f410248e817ec7d
            lr.sgr.T0.e15.b8 91cddc9987614507
            lr.sgr.T1.e0.b8 92d72260f85b45a8
            lr.sgr.T1.e1.b8 1bdc07f9d8d38b91
            lr.sgr.T1.e2.b8 28d3fab8aabc0481
            lr.sgr.T1.e3.b8 83185fc443c799ee
            lr.sgr.T1.e4.b8 3cf276dc427f2296
            lr.sgr.T1.e5.b8 ebecc2d198ec49b8
            lr.sgr.T1.e6.b8 5f3c06e6d6d7471d
            lr.sgr.T1.e7.b8 05ffab87857b5c12
            lr.sgr.T1.e8.b8 4db1b7b127439876
            lr.sgr.T1.e9.b8 33bfc159bd87321c
            lr.sgr.T1.e10.b8 ce85c11d74cc8191
            lr.sgr.T1.e11.b8 faaf56e75bf30618
            lr.sgr.T1.e12.b8 e992d0571ea6d99f
            lr.sgr.T1.e13.b8 116f66f73f84eb42
            lr.sgr.T1.e14.b8 58749d38a102cf1d
            lr.sgr.T1.e15.b8 185707dfbe4204c8
            lr.sgr.T2.e0.b8 ab6736510048a04a
            lr.sgr.T2.e1.b8 45a59111e38aa4a7
            lr.sgr.T2.e2.b8 5c772d6f294a4f19
            lr.sgr.T2.e3.b8 220a5b2bd7c1d936
            lr.sgr.T2.e4.b8 d3860614050947fc
            lr.sgr.T2.e5.b8 e3f50c6817ef18e6
            lr.sgr.T2.e6.b8 a60b6e81c9816837
            lr.sgr.T2.e7.b8 a4cfd10e08c1e998
            lr.sgr.T2.e8.b8 8c6d2c4612560107
            lr.sgr.T2.e9.b8 a9416ab9c5c0cd0b
            lr.sgr.T2.e10.b8 278958bff2116c7d
            lr.sgr.T2.e11.b8 ea81b558e2c3e952
            lr.sgr.T2.e12.b8 614837ddbfa9fbea
            lr.sgr.T2.e13.b8 05d1934a68432ba6
            lr.sgr.T2.e14.b8 544a9808343d4433
            lr.sgr.T2.e15.b8 aec133055d03afa4
            lr.wiener.t0.e0.b10 ba8d3a13e9712086
            lr.wiener.t0.e1.b10 78ab8fc3bdd73d98
            lr.wiener.t0.e2.b10 33b767d43dba4bab
            lr.wiener.t0.e3.b10 edb0575fdc2c7d24
            lr.wiener.t0.e4.b10 9c2ed05172a56136
            lr.wiener.t0.e5.b10 6e756cc169a6d9fa
            lr.wiener.t0.e6.b10 656365fb509e6fd1
            lr.wiener.t0.e7.b10 dde50b126a3f9455
            lr.wiener.t0.e8.b10 d1543bf09e234ef3
            lr.wiener.t0.e9.b10 a25b47b6361b03be
            lr.wiener.t0.e10.b10 5f9cb3ad45acd5ab
            lr.wiener.t0.e11.b10 5eeb66309c428093
            lr.wiener.t0.e12.b10 468623ec533ef29f
            lr.wiener.t0.e13.b10 db05d87a61ad68d1
            lr.wiener.t0.e14.b10 6cbc39f47105fa86
            lr.wiener.t0.e15.b10 15bacb0b033d1364
            lr.wiener.t1.e0.b10 f5b91dbea1b94e1b
            lr.wiener.t1.e1.b10 61918170eb80f0fd
            lr.wiener.t1.e2.b10 4b4e11d3eca969a1
            lr.wiener.t1.e3.b10 459d46f98f525e2e
            lr.wiener.t1.e4.b10 a089bb13231c5dce
            lr.wiener.t1.e5.b10 ac063d1905e02261
            lr.wiener.t1.e6.b10 cd418ebe397bc3a0
            lr.wiener.t1.e7.b10 f0946781433fb029
            lr.wiener.t1.e8.b10 34faecfb06829cf2
            lr.wiener.t1.e9.b10 dde422d4987abd83
            lr.wiener.t1.e10.b10 89fb2a499bd0d000
            lr.wiener.t1.e11.b10 b8530d3eec133da8
            lr.wiener.t1.e12.b10 1477a40e2b772436
            lr.wiener.t1.e13.b10 496dbedc843aa116
            lr.wiener.t1.e14.b10 ce9aaacceca56332
            lr.wiener.t1.e15.b10 f0780cb5d57d08fe
            lr.sgr.T0.e0.b10 eb8659f9e2afd386
            lr.sgr.T0.e1.b10 89ef8e9295d2ee6e
            lr.sgr.T0.e2.b10 625ae285c38dae0f
            lr.sgr.T0.e3.b10 d8fb9c0240d754a1
            lr.sgr.T0.e4.b10 1a91712d6519f497
            lr.sgr.T0.e5.b10 6e3c41d8c7a4e60c
            lr.sgr.T0.e6.b10 1444945b8998c8d4
            lr.sgr.T0.e7.b10 07c3c4ca913ca061
            lr.sgr.T0.e8.b10 ec813f89b5bd40ce
            lr.sgr.T0.e9.b10 7558e315a6f368e7
            lr.sgr.T0.e10.b10 e4b3519f5fccbce6
            lr.sgr.T0.e11.b10 a4f7aaef9f0e87ee
            lr.sgr.T0.e12.b10 4f5cbe39e66d77d3
            lr.sgr.T0.e13.b10 ab75555208da7e90
            lr.sgr.T0.e14.b10 c76ba14cabcbe3ba
            lr.sgr.T0.e15.b10 d325a6a7ef69fcfa
            lr.sgr.T1.e0.b10 7e09d93f9e08d2a4
            lr.sgr.T1.e1.b10 6c639045d7db186a
            lr.sgr.T1.e2.b10 a27a5f07ddae99cd
            lr.sgr.T1.e3.b10 3d358a9a2d00f11a
            lr.sgr.T1.e4.b10 474b540539ec2e6a
            lr.sgr.T1.e5.b10 31af34057c79c27e
            lr.sgr.T1.e6.b10 b0975e725f6bee5b
            lr.sgr.T1.e7.b10 95325200a023da16
            lr.sgr.T1.e8.b10 7c918e809b3dd0a5
            lr.sgr.T1.e9.b10 d6eba41e5cda92b4
            lr.sgr.T1.e10.b10 8222e71df71b9ce8
            lr.sgr.T1.e11.b10 ee23b4f674054767
            lr.sgr.T1.e12.b10 18902dac9929ec67
            lr.sgr.T1.e13.b10 89994c92a62328eb
            lr.sgr.T1.e14.b10 ca5cab9d4ea6c188
            lr.sgr.T1.e15.b10 5ed2f51e5faa8380
            lr.sgr.T2.e0.b10 2d105f3b466f5325
            lr.sgr.T2.e1.b10 f4b76bae2e994d77
            lr.sgr.T2.e2.b10 04f8fb54bd7fa10c
            lr.sgr.T2.e3.b10 b2e3892c7020019c
            lr.sgr.T2.e4.b10 3d58de549029b2b1
            lr.sgr.T2.e5.b10 5e9176483436e2ea
            lr.sgr.T2.e6.b10 0bb19ce1d6549048
            lr.sgr.T2.e7.b10 f2733a9e8a780797
            lr.sgr.T2.e8.b10 e60cba55b3426dfb
            lr.sgr.T2.e9.b10 db10b7975e55cd2b
            lr.sgr.T2.e10.b10 b70018947ad6d172
            lr.sgr.T2.e11.b10 1bf89b923e694b20
            lr.sgr.T2.e12.b10 f2d99679740aabdf
            lr.sgr.T2.e13.b10 0eb2665353486c09
            lr.sgr.T2.e14.b10 f402baba9957458a
            lr.sgr.T2.e15.b10 985172676ce17131
            lr.wiener.t0.e0.b12 9ca9b912cfac76de
            lr.wiener.t0.e1.b12 f4a2ae508c3d9959
            lr.wiener.t0.e2.b12 c28fb4c430955945
            lr.wiener.t0.e3.b12 7a5539753c129f1f
            lr.wiener.t0.e4.b12 88903383c7c0c731
            lr.wiener.t0.e5.b12 7b42a256521ae771
            lr.wiener.t0.e6.b12 d80624b268bc279f
            lr.wiener.t0.e7.b12 172aecad126b8146
            lr.wiener.t0.e8.b12 ec4921e721236d26
            lr.wiener.t0.e9.b12 dc29b3a4bf69cabb
            lr.wiener.t0.e10.b12 bebcd046f2a46c22
            lr.wiener.t0.e11.b12 f1f58e5fd85708f4
            lr.wiener.t0.e12.b12 b0df2660ce49ed9a
            lr.wiener.t0.e13.b12 66c28f62d10a60c5
            lr.wiener.t0.e14.b12 3a54a7474e036f9e
            lr.wiener.t0.e15.b12 71591de59d32735f
            lr.wiener.t1.e0.b12 7f157c0877302c5d
            lr.wiener.t1.e1.b12 7420bc0b75ec2b9a
            lr.wiener.t1.e2.b12 9b3492337ea77686
            lr.wiener.t1.e3.b12 f51da4d905bff82e
            lr.wiener.t1.e4.b12 ab7fc28f08a76a59
            lr.wiener.t1.e5.b12 06e42f0c2f9a0228
            lr.wiener.t1.e6.b12 6fc5009514c18842
            lr.wiener.t1.e7.b12 5ded57f09602a68e
            lr.wiener.t1.e8.b12 2bbcad9b630d2e6d
            lr.wiener.t1.e9.b12 608cf872b8468608
            lr.wiener.t1.e10.b12 39ea1f1e89bc501b
            lr.wiener.t1.e11.b12 f460cec784c77665
            lr.wiener.t1.e12.b12 4e95ffe29df0106f
            lr.wiener.t1.e13.b12 59e2848b60f04bc1
            lr.wiener.t1.e14.b12 e7868b8bc93619e8
            lr.wiener.t1.e15.b12 35b9997dbb6dbbfd
            lr.sgr.T0.e0.b12 a43fa40d458ca705
            lr.sgr.T0.e1.b12 e1d1e17b80478615
            lr.sgr.T0.e2.b12 8c68e06c81166c79
            lr.sgr.T0.e3.b12 e778f82e62537201
            lr.sgr.T0.e4.b12 6ffa70942f660a68
            lr.sgr.T0.e5.b12 296e2d4ecbfd949a
            lr.sgr.T0.e6.b12 a63b2d025059c128
            lr.sgr.T0.e7.b12 b0bd9ed4bb7d8a13
            lr.sgr.T0.e8.b12 a5daae0931972ad5
            lr.sgr.T0.e9.b12 60360fa5a6d55df3
            lr.sgr.T0.e10.b12 3a1e910d0b2f3165
            lr.sgr.T0.e11.b12 3bef1eebd261e108
            lr.sgr.T0.e12.b12 d2c84df4982b340b
            lr.sgr.T0.e13.b12 e1ae87801e6a0eb5
            lr.sgr.T0.e14.b12 75915dcec6905270
            lr.sgr.T0.e15.b12 3ea67a1a9cda7096
            lr.sgr.T1.e0.b12 c8bb079ff4f47d25
            lr.sgr.T1.e1.b12 0e0ff3c37de498ff
            lr.sgr.T1.e2.b12 736b5620c1862d23
            lr.sgr.T1.e3.b12 5d07e988d6f04eae
            lr.sgr.T1.e4.b12 cb7274b7f4a704ed
            lr.sgr.T1.e5.b12 ed4c78527102478d
            lr.sgr.T1.e6.b12 9e30d40ffa1f2b81
            lr.sgr.T1.e7.b12 b3a48488eed9e713
            lr.sgr.T1.e8.b12 01d076154830817b
            lr.sgr.T1.e9.b12 f4afcefe43171d74
            lr.sgr.T1.e10.b12 5644002adfb98ad7
            lr.sgr.T1.e11.b12 eca31637c44abbca
            lr.sgr.T1.e12.b12 6802d2482e5e92fd
            lr.sgr.T1.e13.b12 200847322f00c677
            lr.sgr.T1.e14.b12 6f85d99c662776a9
            lr.sgr.T1.e15.b12 82eca389cd94b15d
            lr.sgr.T2.e0.b12 c179914178b19915
            lr.sgr.T2.e1.b12 e796d3a1d3e6e1b7
            lr.sgr.T2.e2.b12 d8056d9c958d94ec
            lr.sgr.T2.e3.b12 2c527a4df4aa1f0e
            lr.sgr.T2.e4.b12 de70c77b057cb29b
            lr.sgr.T2.e5.b12 5bf9f11e4e9cf83b
            lr.sgr.T2.e6.b12 c0fb8b2e941049f6
            lr.sgr.T2.e7.b12 4a1b37ff2e69c534
            lr.sgr.T2.e8.b12 27255c35dd77fbf6
            lr.sgr.T2.e9.b12 f8a84b565ea5f7cc
            lr.sgr.T2.e10.b12 d3714ceb045518d9
            lr.sgr.T2.e11.b12 fafaa70d3a804009
            lr.sgr.T2.e12.b12 d712734efd6f6434
            lr.sgr.T2.e13.b12 1da90b30e8d2a021
            lr.sgr.T2.e14.b12 75fbead80894a547
            lr.sgr.T2.e15.b12 18519d448fbbf162
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
        assertEquals(expected, hash(new ScalarLoopRestorationDsp(), name));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("groups")
    @DisplayName("the vector kernels produce dav1d's output hash")
    void vector(String name, String expected) {
        Assumptions.assumeTrue(Simd.ENABLED, "the Vector API is unavailable");
        assertEquals(expected, hash(VectorLoopRestorationDsp.create(), name));
    }

    static String hash(LoopRestorationDsp dsp, String name) {
        var parts = name.split("\\.");
        var wiener = parts[1].equals("wiener");
        var sub = Integer.parseInt(parts[2].substring(1));
        var edges = Integer.parseInt(parts[3].substring(1));
        var bitdepth = Integer.parseInt(parts[4].substring(1));
        var rng = new CdefDspTest.Rng(name);
        var max = (1 << bitdepth) - 1;
        var dst = new short[64 * STRIDE];
        var left = new short[64 * 4];
        var lpf = new short[8 * STRIDE];
        var scratch = new int[LoopRestorationDsp.SCRATCH_INTS];
        var params = new LrParams();
        var hash = new CdefDspTest.Fnv();
        for (var n = 0; n < 12; n++) {
            var w = rng.rnd(2) == 0 ? 1 + rng.rnd(8) : 1 + rng.rnd(384);
            var h = 1 + rng.rnd(64);
            fill(rng, dst, h * STRIDE, max);
            fill(rng, left, h * 4, max);
            fill(rng, lpf, 8 * STRIDE, max);
            if (wiener) {
                var h0 = 0;
                var v0 = 0;
                if (sub == 0) {
                    h0 = -5 + rng.rnd(16);
                    v0 = -5 + rng.rnd(16);
                }
                var h1 = -23 + rng.rnd(32);
                var h2 = -17 + rng.rnd(64);
                var v1 = -23 + rng.rnd(32);
                var v2 = -17 + rng.rnd(64);
                params.setWiener(h0, h1, h2, v0, v1, v2);
                dsp.wiener(sub, dst, 8, STRIDE, left, 0, lpf, 8, w, h, params, edges, scratch, max);
            } else {
                var index = sub == 0 ? 14 + rng.rnd(2) : sub == 1 ? 10 + rng.rnd(4) : rng.rnd(10);
                params.s0 = LoopRestorationDsp.SGR_PARAMS[index][0];
                params.s1 = LoopRestorationDsp.SGR_PARAMS[index][1];
                var w0 = -96 + rng.rnd(128);
                var w1 = -32 + rng.rnd(128);
                params.w0 = w0;
                params.w1 = 128 - (w0 + w1);
                dsp.sgr(sub, dst, 8, STRIDE, left, 0, lpf, 8, w, h, params, edges, scratch, max);
            }
            for (var y = 0; y < h; y++) {
                for (var x = 0; x < w; x++) {
                    hash.add(dst[8 + y * STRIDE + x]);
                }
            }
        }
        return hash.hex();
    }

    private static void fill(CdefDspTest.Rng rng, short[] plane, int count, int max) {
        var amp = rng.rnd(max + 1);
        var base = rng.rnd(max + 1);
        for (var i = 0; i < count; i++) {
            plane[i] = (short) Math.clamp(base + rng.rnd(2 * amp + 1) - amp, 0, max);
        }
    }
}
