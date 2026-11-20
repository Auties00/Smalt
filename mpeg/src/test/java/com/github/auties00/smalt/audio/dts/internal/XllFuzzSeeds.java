package com.github.auties00.smalt.audio.dts.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

/**
 * Builds the access units of the DTS-HD Master Audio fuzz seeds, for {@code FuzzSeedWriter}, from the synthetic streams
 * of {@link DtsFixedCoreDifferentialTest}.
 */
public final class XllFuzzSeeds {
    private static final int FRAME = 512;

    private XllFuzzSeeds() {
    }

    /**
     * {@return three access units of a 5.1 core and two XLL channel sets, the second downmixed into the first}
     *
     * @param ratio the ratio of the rate of the lossless channels to the rate of the core, 1 or 2
     */
    public static List<byte[]> overCore(int ratio) {
        var random = new SplittableRandom(ratio);
        var units = new ArrayList<byte[]>();
        var downmix = new int[12];
        for (var i = 0; i < 12; i += 2) {
            downmix[i] = 0x1D9;
            downmix[i + 1] = 0x1D9;
        }
        for (var f = 0; f < 3; f++) {
            var first = DtsFixedCoreDifferentialTest.set(6, 0x3F, f == 0 ? 0x20 : 0, null, random, 40, ratio);
            var second = DtsFixedCoreDifferentialTest.set(1, 0x40, 1, downmix, random, 40, ratio);
            units.add(DtsFixedCoreDifferentialTest.unit(DtsFixedCoreDifferentialTest.plainFrame(random, 80, false, 6),
                    List.of(first, second), 7, 0x4F, FRAME * ratio, ratio));
        }
        return units;
    }

    /**
     * {@return three access units of a 5.1 core, a two channel XXCH set downmixed into it and one XLL channel set}
     */
    public static List<byte[]> overXxch() {
        var random = new SplittableRandom(3);
        var units = new ArrayList<byte[]>();
        for (var f = 0; f < 3; f++) {
            units.add(DtsFixedCoreDifferentialTest.unit(DtsFixedCoreDifferentialTest.xxchFrame(random, 55),
                    List.of(DtsFixedCoreDifferentialTest.set(8, 0x7A7, 0, null, random, 40, 1)), 8, 0xCF, FRAME, 1));
        }
        return units;
    }

    /**
     * {@return three access units of a 5.1 core and two XLL channel sets at 192 kHz, the second downmixed into the first
     * in both frequency bands}
     */
    public static List<byte[]> twoBands() {
        var random = new SplittableRandom(5);
        var units = new ArrayList<byte[]>();
        var downmix = new int[12];
        for (var i = 0; i < 12; i += 2) {
            downmix[i] = 0x1D9;
            downmix[i + 1] = 0x1D9;
        }
        for (var f = 0; f < 3; f++) {
            var first = DtsFixedCoreDifferentialTest.set(6, 0x3F, 0, null, random, 40, 2);
            var second = DtsFixedCoreDifferentialTest.set(1, 0x40, 1, downmix, random, 40, 2);
            for (var set : List.of(first, second)) {
                set.rateCode = 14;
                set.highResiduals = new int[set.channels][FRAME * 2];
                set.history = new int[set.channels][7];
                for (var ch = 0; ch < set.channels; ch++) {
                    for (var n = 0; n < FRAME * 2; n++) {
                        set.highResiduals[ch][n] = random.nextInt(-40, 40);
                    }
                    for (var n = 0; n < 7; n++) {
                        set.history[ch][n] = random.nextInt(-400, 400);
                    }
                }
            }
            var out = new java.io.ByteArrayOutputStream();
            out.writeBytes(CoreStreamWriter.write(DtsFixedCoreDifferentialTest.plainFrame(random, 80, false, 6)));
            out.writeBytes(XllStreamWriter.substream(XllStreamWriter.component(List.of(first, second), FRAME * 2), 7,
                    0x4F, 14));
            units.add(out.toByteArray());
        }
        return units;
    }

    /**
     * {@return three access units of a 5.1 core and one XLL channel set with Rice coded residuals and long unary parts}
     */
    public static List<byte[]> rice() {
        var random = new SplittableRandom(4);
        var units = new ArrayList<byte[]>();
        for (var f = 0; f < 3; f++) {
            var set = DtsFixedCoreDifferentialTest.set(6, 0x3F, 0, null, random, 3, 1);
            set.riceBits = 1;
            set.residuals[f][f] = 60;
            units.add(DtsFixedCoreDifferentialTest.unit(DtsFixedCoreDifferentialTest.plainFrame(random, 80, false, 6),
                    List.of(set), 6, 0xF, FRAME, 1));
        }
        return units;
    }
}
