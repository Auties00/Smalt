package com.github.auties00.smalt.image.jp2;

/**
 * Selects the samples a JPEG 2000 encoder codes ahead of the rest, with the max-shift method of
 * ISO/IEC 15444-1 annex H: the quantized wavelet coefficients of the region are scaled up by
 * {@code 2^s}, so that their bit-planes precede every bit-plane of the background, and an
 * {@code RGN} marker segment records {@code s}.
 *
 * <p>A lossy encoding spends its bytes on the region first: at a given size the region is
 * sharper and the background coarser than without one. A lossless encoding still reproduces every
 * sample. Every decoder of ISO/IEC 15444-1 reads the result; it needs no description of the
 * region's shape.
 *
 * <p>This sealed type may gain subtypes in later releases; a {@code switch} over it needs a
 * {@code default} branch.
 */
public sealed interface Jp2RegionOfInterest {
    /**
     * Makes one whole component the region, scaled by a chosen shift; the other components are
     * the background ({@code opj_compress -ROI c=component,U=shift}).
     *
     * <p>Since no coefficient of the component is background, a shift of any size is valid: it
     * sets how strongly a lossy encoding favors the component. A lossless encoding of the
     * ISO/IEC 15444-1 block coder is byte-identical to {@code opj_compress}'s with the same option.
     *
     * @param component the component index, from 0 to 16383
     * @param shift the scaling exponent {@code s}, from 0 to 37
     */
    record Component(int component, int shift) implements Jp2RegionOfInterest {
        /**
         * Validates the region.
         *
         * @throws IllegalArgumentException if the component or the shift is out of range
         */
        public Component {
            if (component < 0 || component > 16383) {
                throw new IllegalArgumentException("invalid component " + component);
            }
            if (shift < 0 || shift > 37) {
                throw new IllegalArgumentException("shift " + shift + " is not in [0, 37]");
            }
        }
    }

    /**
     * Makes a rectangle of the image the region, in every component; the shift of each
     * tile-component lifts the region above its background with one bit to spare.
     *
     * <p>The rectangle is given in image samples from the top-left corner of the image (not of the
     * reference grid) and may extend past the image, which clips it. The coefficients that
     * contribute to its samples are scaled, so the region coded ahead of the background covers the
     * rectangle and a margin of a few samples per decomposition level around it. The
     * {@code RGN} segments are written in the tile-part headers, one per component of each tile
     * that meets the rectangle.
     *
     * @param x the left edge, at least 0
     * @param y the top edge, at least 0
     * @param width the width, positive
     * @param height the height, positive
     */
    record Rectangle(int x, int y, int width, int height) implements Jp2RegionOfInterest {
        /**
         * Validates the rectangle.
         *
         * @throws IllegalArgumentException if an edge is negative or a dimension is not positive
         */
        public Rectangle {
            if (x < 0 || y < 0 || width <= 0 || height <= 0) {
                throw new IllegalArgumentException("invalid rectangle " + x + "," + y + " " + width + "x" + height);
            }
        }
    }
}
