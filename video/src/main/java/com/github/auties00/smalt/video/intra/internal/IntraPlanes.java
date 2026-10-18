package com.github.auties00.smalt.video.intra.internal;

import com.github.auties00.smalt.image.ColorInfo;

/**
 * Holds the planes of a picture prepared for an intra encoder: three components at one bit depth
 * and chroma layout, an optional alpha plane, every plane padded to whole macroblocks by
 * replicating the last column and row.
 *
 * <p>For a Y'CbCr picture the components are luma, Cb and Cr; for a picture prepared in RGB they
 * are red, green and blue, all at full size.
 *
 * @param first        the luma (or red) plane
 * @param second       the Cb (or green) plane
 * @param third        the Cr (or blue) plane
 * @param alpha        the alpha plane at {@code alphaDepth} bits, or {@code null}
 * @param lumaStride   the row stride of the first and alpha planes, the padded width
 * @param chromaStride the row stride of the second and third planes
 * @param rows         the number of rows of every plane, the padded height
 * @param depth        the bit depth of the components
 * @param alphaDepth   the bit depth of the alpha plane
 * @param color        the color description of the samples
 */
public record IntraPlanes(short[] first, short[] second, short[] third, short[] alpha, int lumaStride,
                          int chromaStride, int rows, int depth, int alphaDepth, ColorInfo color) {
}
