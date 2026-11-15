package com.github.auties00.smalt.audio.ac3.internal;

/**
 * A one-shot transcription of the transient pre-noise processing pseudo-code of ETSI TS 102 366
 * clause E.2.7.2, applied to a whole decoded channel at once, with the conventions measured on
 * Dolby's decoder: the transient lies 256 samples after the location the frame carries, only
 * locations from 1024 to 2559 are applied, and the cross-fade windows are
 * {@code w(n) = (1 + cos(pi * (n + 1) / 257)) / 2} and its complement over the first 256 samples and
 * the same windows at every other sample over the last 128.
 */
public final class PreNoiseReference {
    private PreNoiseReference() {
    }

    /**
     * Returns the window {@code w(n)}.
     */
    static double window(int n) {
        return 0.5 * (1 + Math.cos(Math.PI * (n + 1) / 257));
    }

    /**
     * Applies the pseudo-code to a whole channel for data {@code location} ({@code 4 * transprocloc})
     * samples after {@code frameStart}, returning a corrected copy.
     *
     * @param pcm the decoded channel
     * @param frameStart the first sample of the frame carrying the data
     * @param location the transient location {@code 4 * transprocloc}
     * @param length the processing length {@code transproclen}
     * @return the corrected channel
     */
    public static float[] apply(float[] pcm, int frameStart, int location, int length) {
        var out = pcm.clone();
        if (location < 1024 || location >= 2560) {
            return out;
        }
        var transloc = location + 256;
        var blockStart = (transloc / 256 - 1) * 256;
        var pnlen = transloc - blockStart;
        var total = pnlen + length + 256;
        var position = frameStart + transloc;
        var synthesis = new float[2 * 256 + pnlen];
        for (var n = 0; n < synthesis.length; n++) {
            synthesis[n] = pcm[position - (2 * 256 + 2 * pnlen) + n];
        }
        var start = position - total;
        for (var n = 0; n < total; n++) {
            var index = start + n;
            if (n < 256) {
                out[index] = (float) (pcm[index] * (float) window(n) + synthesis[n] * (float) (1 - window(n)));
            } else if (n < total - 128) {
                out[index] = synthesis[n];
            } else {
                var k = n - (total - 128);
                out[index] = (float) (pcm[index] * (float) (1 - window(2 * k)) + synthesis[n] * (float) window(2 * k + 1));
            }
        }
        return out;
    }

    /**
     * {@return the first sample, relative to the frame carrying the data, that the data corrects}
     *
     * @param location the transient location {@code 4 * transprocloc}
     * @param length the processing length {@code transproclen}
     */
    public static int correctionStart(int location, int length) {
        var transloc = location + 256;
        var blockStart = (transloc / 256 - 1) * 256;
        return blockStart - length - 256;
    }

    /**
     * {@return the end, relative to the frame carrying the data, of the synthesis buffer the data copies}
     *
     * @param location the transient location {@code 4 * transprocloc}
     */
    public static int bufferEnd(int location) {
        var transloc = location + 256;
        return (transloc / 256 - 1) * 256;
    }
}
