package com.github.auties00.smalt.audio.dts.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

/**
 * Builds the access units of the DTS fuzz seeds of termination frames, arrangements above nine and smoothed lossless
 * data, for {@code FuzzSeedWriter}, from the synthetic streams of the tests that cover them.
 */
public final class CoreFuzzSeeds {
    private CoreFuzzSeeds() {
    }

    /**
     * {@return two normal core frames and a termination frame ending in a partial subsubframe of five samples, with
     * ADPCM prediction and an LFE channel}
     */
    public static List<byte[]> termination() {
        var random = new SplittableRandom(1);
        var test = new DtsSyntheticDifferentialTest.Case("termination seed", 9, 2, true, false, false, false, 3, 2,
                DtsSyntheticDifferentialTest.Tool.ADPCM);
        var units = new ArrayList<byte[]>();
        for (var f = 0; f < 2; f++) {
            units.add(CoreStreamWriter.write(DtsSyntheticDifferentialTest.frame(test, random)));
        }
        units.add(CoreStreamWriter.write(DtsTerminationFrameDifferentialTest.terminate(
                DtsSyntheticDifferentialTest.frame(test, random), 5)[0]));
        return units;
    }

    /**
     * {@return three core frames of arrangement 13 whose XXCH extension places the primary channels}
     */
    public static List<byte[]> arrangement() {
        return DtsArrangementDifferentialTest.stream(13, 9, 5, 0x627, false);
    }

    /**
     * {@return the first access units of a stream over a core whose lossless data goes through a peak bit rate
     * smoothing buffer}
     */
    public static List<byte[]> smoothed() {
        var stream = DtsPeakBitRateDifferentialTest.smooth(DtsPeakBitRateDifferentialTest.source(20, true));
        return DtsPeakBitRateDifferentialTest.units(stream, 0, 15).subList(0, stream.delay() + 2);
    }
}
