package com.github.auties00.smalt.image.jp2.internal;

/**
 * Describes one sub-band of a resolution level: its area, quantization and precincts.
 *
 * @param orientation the orientation: 0 for LL, 1 for HL, 2 for LH, 3 for HH
 * @param x0 the left edge in sub-band coordinates
 * @param y0 the top edge in sub-band coordinates
 * @param x1 the right edge in sub-band coordinates (exclusive)
 * @param y1 the bottom edge in sub-band coordinates (exclusive)
 * @param stepSize the quantization step size, for the irreversible wavelet
 * @param bitPlanes the maximum number of magnitude bit-planes, {@code Mb} of equation E-2 minus one
 * @param index the index of the sub-band in codestream order, which selects its step size
 * @param precincts the precincts, row-major over the resolution's precinct grid; shared, not copied
 */
record Band(int orientation, int x0, int y0, int x1, int y1, float stepSize, int bitPlanes, int index,
            Precinct[] precincts) {
    /**
     * {@return whether the sub-band holds no sample}
     */
    boolean empty() {
        return x1 <= x0 || y1 <= y0;
    }
}
