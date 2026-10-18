package com.github.auties00.smalt.video.prores.internal;

/**
 * Describes where the slices of one ProRes picture write their samples: the planes of the output
 * frame, their strides, the rows of the frame the picture covers, and how reconstructed values
 * become samples of the output depth.
 *
 * <p>The planes are sized to whole macroblocks, so a slice writes every sample of its blocks
 * without clipping; the samples past the frame's width and height are never read.
 *
 * @param wide        whether the planes hold {@code short} samples rather than {@code byte}s
 * @param luma16      the 16-bit luma plane, or {@code null}
 * @param cb16        the 16-bit Cb plane, or {@code null}
 * @param cr16        the 16-bit Cr plane, or {@code null}
 * @param alpha16     the 16-bit alpha plane, or {@code null}
 * @param luma8       the 8-bit luma plane, or {@code null}
 * @param cb8         the 8-bit Cb plane, or {@code null}
 * @param cr8         the 8-bit Cr plane, or {@code null}
 * @param alpha8      the 8-bit alpha plane, or {@code null}
 * @param lumaStride  the row stride of the luma and alpha planes
 * @param chromaStride the row stride of the chroma planes
 * @param rowOffset   the frame row of the picture's first row: 1 for a bottom field, 0 otherwise
 * @param rowStep     the distance in frame rows between two picture rows: 2 for a field, 1
 *                    otherwise
 * @param pictureHeight the height of the picture in luma rows
 * @param scale       the factor of the conversion from reconstructed values to samples
 * @param offset      the term of that conversion, including the rounding half
 * @param limit       the largest sample plus one half
 * @param depth       the bit depth of the output samples
 */
public record ProResTarget(boolean wide, short[] luma16, short[] cb16, short[] cr16, short[] alpha16,
                           byte[] luma8, byte[] cb8, byte[] cr8, byte[] alpha8, int lumaStride, int chromaStride,
                           int rowOffset, int rowStep, int pictureHeight, float scale, float offset, float limit,
                           int depth) {
}
