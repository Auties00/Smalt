package com.github.auties00.smalt.image.jp2.internal;

/**
 * Holds the parameters of one encoding: the image geometry, the coding style shared by every
 * component, the quality layers, and the markers to write.
 *
 * @param width          the image width
 * @param height         the image height
 * @param tileWidth      the nominal tile width
 * @param tileHeight     the nominal tile height
 * @param precisions     the precision of each component, in bits
 * @param signed         whether each component is signed
 * @param subsamplingX   the horizontal subsampling of each component
 * @param subsamplingY   the vertical subsampling of each component
 * @param resolutions    the number of resolution levels
 * @param codeBlockWidth the base-2 logarithm of the code-block width
 * @param codeBlockHeight the base-2 logarithm of the code-block height
 * @param codeBlockStyle the code-block style flags
 * @param reversible     whether the reversible 5-3 wavelet (and no quantization) is used
 * @param precinctWidths the precinct width exponents of each resolution, or {@code null} for the
 *                       maximal precincts
 * @param precinctHeights the precinct height exponents of each resolution, or {@code null}
 * @param progression    the progression order, 0 (LRCP) to 4 (CPRL)
 * @param ratios         the compression ratio of each layer, the last one possibly 0 for a
 *                       lossless last layer; {@code null} when layers target qualities
 * @param qualities      the PSNR target of each layer in decibels, the last one possibly 0 for a
 *                       lossless last layer; {@code null} when layers target ratios
 * @param transform      whether the multiple component transformation is applied to the first
 *                       three components
 * @param sop            whether packets start with {@code SOP} marker segments
 * @param eph            whether packet headers end with {@code EPH} markers
 * @param comment        the comment of the {@code COM} segment, or {@code null}
 */
public record EncoderConfig(int width, int height, int tileWidth, int tileHeight, int[] precisions, boolean[] signed,
                            int[] subsamplingX, int[] subsamplingY, int resolutions, int codeBlockWidth,
                            int codeBlockHeight, int codeBlockStyle, boolean reversible, int[] precinctWidths,
                            int[] precinctHeights, int progression, double[] ratios, double[] qualities,
                            boolean transform, boolean sop, boolean eph, String comment) {
    /**
     * Returns the number of components.
     *
     * @return the component count
     */
    public int components() {
        return precisions.length;
    }

    /**
     * Returns the number of quality layers.
     *
     * @return the layer count
     */
    public int layers() {
        return ratios != null ? ratios.length : qualities.length;
    }
}
