package com.github.auties00.smalt.video.mpeg2;

import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.video.mpeg2.internal.SyntaxStream;
import com.github.auties00.smalt.video.mpeg2.internal.SyntaxStream.Picture;
import com.github.auties00.smalt.video.mpeg2.internal.SyntaxStream.Sequence;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Decodes streams of pseudo-random syntax ({@link SyntaxStream}) that reach the coding tools no encoder on the test
 * runners produces, and compares every sample with the ISO/IEC 13818-5 reference decoder built with Smalt's inverse
 * DCT: MPEG-1 D-pictures, whole sample vectors, macroblock stuffing and escapes; MPEG-2 concealment motion vectors in
 * I- and P-pictures, every frame and field motion type (dual-prime and 16x8 included) with random vectors and
 * {@code f_code}s up to 9, field pictures whose second field predicts from the first, I-P field pairs, 4:2:2 and 4:4:4
 * macroblocks with extended coded block patterns, quant matrix extensions with chroma matrices, intra DC precisions
 * of 8 to 11 bits, saturating coefficients, extra slice information, slices that start past column 33 and slices past row 127
 * of pictures taller than 2800 lines ({@code slice_vertical_position_extension}). Each case
 * runs with several seeds.
 */
@DisplayName("MPEG video decoder on random syntax against the reference decoder")
class Mpeg2SyntaxTest {
    record Case(String name, Function<Random, Sequence> sequence, Function<Random, List<Picture>> pictures,
                boolean mpeg2) {
        @Override
        public String toString() {
            return name;
        }
    }

    static Stream<Object[]> cases() {
        var cases = List.of(
                new Case("MPEG-1 D-pictures", r -> mpeg1(64, 48), r -> repeat(5, () -> new Picture(SyntaxStream.D)),
                        false),
                new Case("MPEG-1 I, P and B with whole sample vectors and stuffing", r -> {
                    var s = mpeg1(96, 64);
                    s.stuffing = true;
                    return s;
                }, r -> gops(r, false, 2, 9, true), false),
                new Case("MPEG-1 macroblock escapes", r -> {
                    var s = mpeg1(720, 32);
                    s.skipRate = 0.6;
                    return s;
                }, r -> gops(r, false, 2, 6, false), false),
                new Case("MPEG-2 frame pictures, every frame motion type", r -> mpeg2(96, 64, 1),
                        r -> gops(r, true, 2, 9, false), true),
                new Case("MPEG-2 field pictures", r -> mpeg2(96, 64, 1), r -> fieldGops(r, 2, 8), true),
                new Case("MPEG-2 dual-prime frame and field pictures", r -> mpeg2(96, 64, 1), r -> dualPrime(r),
                        true),
                new Case("MPEG-2 4:2:2 with chroma quantizer matrices", r -> mpeg2(64, 64, 2), r -> {
                    var list = gops(r, true, 2, 6, false);
                    list.addAll(fieldGops(r, 1, 6));
                    matrices(r, list, true);
                    return list;
                }, true),
                new Case("MPEG-2 4:4:4 with chroma quantizer matrices", r -> mpeg2(64, 64, 3), r -> {
                    var list = gops(r, true, 2, 6, false);
                    list.addAll(fieldGops(r, 1, 6));
                    matrices(r, list, true);
                    return list;
                }, true),
                new Case("MPEG-2 macroblock escapes", r -> {
                    var s = mpeg2(720, 64, 1);
                    s.skipRate = 0.6;
                    return s;
                }, r -> gops(r, true, 1, 6, false), true),
                // 180 macroblock rows, so slices below row 127 carry slice_vertical_position_extension
                new Case("MPEG-2 slice vertical position extension", r -> mpeg2(32, 2880, 1), r -> {
                    var list = gops(r, true, 1, 4, false);
                    list.addAll(fieldGops(r, 1, 2));
                    return list;
                }, true),
                new Case("MPEG-2 progressive sequence with sequence header changes", r -> {
                    var s = mpeg2(80, 48, 1);
                    s.progressive = true;
                    s.intraMatrix = randomMatrix(r, true);
                    return s;
                }, r -> {
                    var list = gops(r, true, 3, 6, false);
                    for (var p : list) {
                        p.framePredFrameDct = true;
                        p.progressiveFrame = true;
                        p.topFieldFirst = false;
                    }
                    matrices(r, list, false);
                    list.get(6).sequenceHeader = true;
                    list.get(6).sequenceMatrices = new int[][]{null, randomMatrix(r, false)};
                    list.get(12).sequenceHeader = true;
                    list.get(12).sequenceMatrices = new int[][]{randomMatrix(r, true), null};
                    return list;
                }, true));
        var arguments = new ArrayList<Object[]>();
        for (var c : cases) {
            for (var seed = 1L; seed <= 3; seed++) {
                arguments.add(new Object[]{c, seed});
            }
        }
        return arguments.stream();
    }

    static Sequence mpeg1(int width, int height) {
        var s = new Sequence();
        s.mpeg2 = false;
        s.width = width;
        s.height = height;
        s.aspect = 1;
        s.progressive = true;
        return s;
    }

    static Sequence mpeg2(int width, int height, int chroma) {
        var s = new Sequence();
        s.width = width;
        s.height = height;
        s.chroma = chroma;
        s.displayExtension = true;
        return s;
    }

    static List<Picture> repeat(int count, java.util.function.Supplier<Picture> picture) {
        var list = new ArrayList<Picture>();
        for (var i = 0; i < count; i++) {
            var p = picture.get();
            p.temporalReference = i;
            list.add(p);
        }
        return list;
    }

    /** Randomizes the picture-level coding choices of an MPEG-2 picture. */
    static void choices(Random r, Picture p, boolean mpeg2) {
        for (var s = 0; s < 2; s++) {
            for (var t = 0; t < 2; t++) {
                p.fCode[s][t] = 1 + r.nextInt(mpeg2 ? 9 : 7);
            }
        }
        if (!mpeg2) {
            p.fCode[0][1] = p.fCode[0][0];
            p.fCode[1][1] = p.fCode[1][0];
            p.fullPelForward = r.nextBoolean();
            p.fullPelBackward = r.nextBoolean();
            return;
        }
        p.framePredFrameDct = r.nextInt(3) == 0;
        p.concealment = r.nextBoolean();
        p.qScaleType = r.nextBoolean();
        p.intraVlc = r.nextBoolean();
        p.alternateScan = r.nextBoolean();
        p.dcPrecision = r.nextInt(4);
        p.topFieldFirst = r.nextBoolean();
        p.intraRate = 0.15;
    }

    /** Builds groups of an I-picture followed by P-pictures, each with B-pictures in front in coding order. */
    static List<Picture> gops(Random r, boolean mpeg2, int groups, int length, boolean fullPel) {
        var list = new ArrayList<Picture>();
        for (var g = 0; g < groups; g++) {
            var reference = 0;
            for (var i = 0; i < length; i++) {
                var type = i == 0 ? SyntaxStream.I : i % 3 == 0 ? SyntaxStream.P : SyntaxStream.B;
                if (type == SyntaxStream.B && i < 3) {
                    type = SyntaxStream.P;
                }
                var p = new Picture(type);
                choices(r, p, mpeg2);
                p.group = i == 0;
                p.closedGop = true;
                p.temporalReference = type == SyntaxStream.B ? i - 1 : reference;
                reference = i;
                list.add(p);
            }
        }
        return list;
    }

    /** Builds field-picture frames: I+I or I+P key frames, P+P frames and B+B frames. */
    static List<Picture> fieldGops(Random r, int groups, int frames) {
        var list = new ArrayList<Picture>();
        for (var g = 0; g < groups; g++) {
            for (var i = 0; i < frames; i++) {
                var type = i == 0 ? SyntaxStream.I : i % 2 == 0 ? SyntaxStream.P : SyntaxStream.B;
                if (i == 1) {
                    type = SyntaxStream.P;
                }
                var topFirst = r.nextBoolean();
                for (var field = 0; field < 2; field++) {
                    var fieldType = type == SyntaxStream.I && field == 1 && r.nextBoolean() ? SyntaxStream.P : type;
                    var p = new Picture(fieldType, (field == 0) == topFirst ? SyntaxStream.TOP : SyntaxStream.BOTTOM);
                    choices(r, p, true);
                    p.framePredFrameDct = false;
                    p.group = i == 0 && field == 0;
                    p.temporalReference = i;
                    list.add(p);
                }
            }
        }
        return list;
    }

    /** Builds P-only sequences of frame and field pictures with dual-prime prediction. */
    static List<Picture> dualPrime(Random r) {
        var list = new ArrayList<Picture>();
        for (var i = 0; i < 6; i++) {
            var p = new Picture(i == 0 ? SyntaxStream.I : SyntaxStream.P);
            choices(r, p, true);
            p.framePredFrameDct = false;
            p.dualPrime = true;
            p.group = i == 0;
            p.temporalReference = i;
            list.add(p);
        }
        for (var i = 0; i < 4; i++) {
            var topFirst = r.nextBoolean();
            for (var field = 0; field < 2; field++) {
                var p = new Picture(i == 0 && field == 0 ? SyntaxStream.I : SyntaxStream.P,
                        (field == 0) == topFirst ? SyntaxStream.TOP : SyntaxStream.BOTTOM);
                choices(r, p, true);
                p.framePredFrameDct = false;
                p.dualPrime = true;
                p.group = i == 0 && field == 0;
                p.temporalReference = i;
                list.add(p);
            }
        }
        return list;
    }

    static int[] randomMatrix(Random r, boolean intra) {
        var m = new int[64];
        for (var i = 0; i < 64; i++) {
            m[i] = 1 + r.nextInt(255);
        }
        if (intra) {
            m[0] = 8;
        }
        return m;
    }

    /** Gives some pictures quant matrix extensions, chroma matrices included. */
    static void matrices(Random r, List<Picture> list, boolean chroma) {
        for (var p : list) {
            if (r.nextInt(3) == 0) {
                p.quantExtension = new int[][]{r.nextBoolean() ? randomMatrix(r, true) : null,
                        r.nextBoolean() ? randomMatrix(r, false) : null,
                        chroma && r.nextBoolean() ? randomMatrix(r, true) : null,
                        chroma && r.nextBoolean() ? randomMatrix(r, false) : null};
            }
        }
    }

    @ParameterizedTest(name = "{0}, seed {1}")
    @MethodSource("cases")
    @DisplayName("decodes every sample as the reference decoder does")
    void matchesReference(Case c, long seed) {
        var random = new Random(seed * 7919 + c.name().hashCode());
        var sequence = c.sequence().apply(random);
        var pictures = c.pictures().apply(random);
        var stream = SyntaxStream.write(sequence, pictures, seed);
        var decoded = Mpeg2ReferenceDecoderTest.check(c.name() + " seed " + seed, stream,
                c.mpeg2() ? Codec.MPEG2_VIDEO : Codec.MPEG1_VIDEO);
        var frames = 0;
        for (var p : pictures) {
            frames += p.structure == SyntaxStream.FRAME ? 2 : 1;
        }
        assertEquals(frames / 2, decoded.count(), c.name() + " frame count");
    }
}
