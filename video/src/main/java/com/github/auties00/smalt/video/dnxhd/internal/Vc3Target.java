package com.github.auties00.smalt.video.dnxhd.internal;

/**
 * Describes where the macroblocks of one VC-3 coding unit write their samples: the three planes of
 * the output frame and its optional alpha plane, their strides, the frame rows the coding unit
 * covers and the conversion of reconstructed values to output samples.
 *
 * <p>The planes are sized to whole macroblocks, so a macroblock writes every sample of its blocks
 * without clipping. The alpha plane has the stride of the first plane and the same conversion of
 * reconstructed values (ST 2019-1 clause 8.2.8.3 shifts both by {@code 2^(n-1)} and alpha spans the
 * full range of codes, Tables 1 and 2).
 *
 * @param wide whether the planes hold {@code short} samples rather than {@code byte}s
 * @param planes16 the three 16-bit planes, or {@code null}
 * @param planes8 the three 8-bit planes, or {@code null}
 * @param alpha16 the 16-bit alpha plane, or {@code null}
 * @param alpha8 the 8-bit alpha plane, or {@code null}
 * @param strides the row stride of each plane
 * @param rowOffset the frame row of the coding unit's first row: 1 for a second, bottom field
 * @param rowStep the distance in frame rows between coding unit rows: 2 for a field
 * @param scale the factor of the conversion from reconstructed values to samples
 * @param offset the term of that conversion, including the level shift and the rounding half
 * @param limit the largest sample plus one half
 * @param alphaShift the right shift from the coded alpha depth to the output depth, at least 0
 */
public record Vc3Target(boolean wide, short[][] planes16, byte[][] planes8, short[] alpha16, byte[] alpha8,
                        int[] strides, int rowOffset, int rowStep, float scale, float offset, float limit,
                        int alphaShift) {
}
