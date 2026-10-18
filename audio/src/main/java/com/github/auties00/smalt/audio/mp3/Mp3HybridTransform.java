package com.github.auties00.smalt.audio.mp3;

/**
 * Turns the subband samples of a granule into its 576 spectral lines with the forward MDCT of
 * ISO/IEC 11172-3 Annex C.1.5.3.
 *
 * <p>Each subband's MDCT spans 36 samples: the 18 of the previous granule and the 18 of the
 * current one. Every odd sample of every odd subband is negated first, which undoes the
 * frequency inversion the decoder applies after its IMDCT. Long, start and stop blocks window
 * the 36 samples and produce 18 lines per subband, in frequency order, followed by the
 * alias-reduction butterflies that the decoder's butterflies invert. Short blocks apply three
 * 12-sample MDCTs at offsets 6, 12 and 18 and produce 6 lines per subband and window; their
 * output is window-major: line {@code f} of window {@code w} at index {@code 192 w + f}.
 *
 * @implNote This implementation scales the long transform by {@code 2/18} and the short one by
 * {@code 2/6}, the normalization under which the decoder's unscaled IMDCT and window overlap
 * reconstruct the subband samples exactly.
 */
final class Mp3HybridTransform {
    /**
     * The 36 by 18 cosine table of the long MDCT, scaled: entry {@code 18 i + m} holds
     * {@code cos(pi / 72 * (2 i + 19) * (2 m + 1)) / 9}.
     */
    static final float[] LONG_TABLE = new float[36 * 18];

    /**
     * The 12 by 6 cosine table of the short MDCT, scaled: entry {@code 6 i + m} holds
     * {@code cos(pi / 24 * (2 i + 7) * (2 m + 1)) / 3}.
     */
    private static final float[] SHORT_TABLE = new float[12 * 6];

    /**
     * The 36-sample windows of the long, start, short (unused) and stop block types.
     */
    private static final float[][] WINDOWS = new float[4][36];

    /**
     * The 12-sample window of each short block.
     */
    private static final float[] SHORT_WINDOW = new float[12];

    /**
     * The alias-reduction cosines {@code cs} (ISO/IEC 11172-3 Table B.9).
     */
    private static final float[] CS = Mp3Tables.ANTIALIAS[0];

    /**
     * The alias-reduction sines {@code ca} (ISO/IEC 11172-3 Table B.9).
     */
    private static final float[] CA = Mp3Tables.ANTIALIAS[1];

    static {
        for (var i = 0; i < 36; i++) {
            for (var m = 0; m < 18; m++) {
                LONG_TABLE[18 * i + m] = (float) (Math.cos(Math.PI / 72 * (2 * i + 19) * (2 * m + 1)) / 9);
            }
        }
        for (var i = 0; i < 12; i++) {
            for (var m = 0; m < 6; m++) {
                SHORT_TABLE[6 * i + m] = (float) (Math.cos(Math.PI / 24 * (2 * i + 7) * (2 * m + 1)) / 3);
            }
            SHORT_WINDOW[i] = (float) Math.sin(Math.PI / 12 * (i + 0.5));
        }
        for (var i = 0; i < 36; i++) {
            WINDOWS[Mp3CodedGranule.LONG][i] = (float) Math.sin(Math.PI / 36 * (i + 0.5));
        }
        var start = WINDOWS[Mp3CodedGranule.START];
        var stop = WINDOWS[Mp3CodedGranule.STOP];
        for (var i = 0; i < 18; i++) {
            start[i] = WINDOWS[Mp3CodedGranule.LONG][i];
            stop[35 - i] = WINDOWS[Mp3CodedGranule.LONG][35 - i];
        }
        for (var i = 18; i < 24; i++) {
            start[i] = 1;
            stop[35 - i] = 1;
        }
        for (var i = 24; i < 30; i++) {
            start[i] = (float) Math.sin(Math.PI / 12 * (i - 18 + 0.5));
            stop[35 - i] = start[i];
        }
    }

    /**
     * The DSP kernel.
     */
    private final Mp3EncoderKernel kernel;

    /**
     * The windowed MDCT inputs of the 32 subbands, 36 samples each.
     */
    private final float[] blocks = new float[32 * 36];

    /**
     * Creates a transform.
     *
     * @param kernel the DSP kernel
     */
    Mp3HybridTransform(Mp3EncoderKernel kernel) {
        this.kernel = kernel;
    }

    /**
     * Transforms one granule.
     *
     * @param previous  the subband samples of the previous granule, subband {@code k}, slot
     *                  {@code t} at {@code 18 k + t}
     * @param current   the subband samples of this granule, in the same layout
     * @param blockType the block type of {@link Mp3CodedGranule}
     * @param lines     receives the 576 lines
     */
    void transform(float[] previous, float[] current, int blockType, float[] lines) {
        if (blockType == Mp3CodedGranule.SHORT) {
            transformShort(previous, current, lines);
            return;
        }
        var window = WINDOWS[blockType];
        var z = blocks;
        for (var sb = 0; sb < 32; sb++) {
            var base = 36 * sb;
            var sign = (sb & 1) != 0;
            for (var t = 0; t < 18; t++) {
                var p = previous[18 * sb + t];
                var c = current[18 * sb + t];
                if (sign && (t & 1) != 0) {
                    p = -p;
                    c = -c;
                }
                z[base + t] = p * window[t];
                z[base + 18 + t] = c * window[18 + t];
            }
        }
        kernel.mdct(z, LONG_TABLE, lines);
        for (var b = 18; b < 576; b += 18) {
            for (var i = 0; i < 8; i++) {
                var upper = lines[b + i];
                var lower = lines[b - 1 - i];
                lines[b + i] = upper * CS[i] + lower * CA[i];
                lines[b - 1 - i] = lower * CS[i] - upper * CA[i];
            }
        }
    }

    /**
     * Transforms one granule into three short blocks.
     *
     * @param previous the subband samples of the previous granule
     * @param current  the subband samples of this granule
     * @param lines    receives the window-major short spectra
     */
    private void transformShort(float[] previous, float[] current, float[] lines) {
        var z = blocks;
        for (var sb = 0; sb < 32; sb++) {
            var sign = (sb & 1) != 0;
            for (var t = 0; t < 18; t++) {
                var p = previous[18 * sb + t];
                var c = current[18 * sb + t];
                if (sign && (t & 1) != 0) {
                    p = -p;
                    c = -c;
                }
                z[t] = p;
                z[18 + t] = c;
            }
            for (var w = 0; w < 3; w++) {
                var offset = 6 + 6 * w;
                for (var m = 0; m < 6; m++) {
                    var acc = 0.0f;
                    for (var i = 0; i < 12; i++) {
                        acc += z[offset + i] * SHORT_WINDOW[i] * SHORT_TABLE[6 * i + m];
                    }
                    lines[192 * w + 6 * sb + m] = acc;
                }
            }
        }
    }
}
