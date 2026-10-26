package com.github.auties00.smalt.image.jp2.internal;

/**
 * Describes one sub-band of a resolution level: its area, quantization and precincts.
 *
 * @param orientation the orientation that selects the code-block coder's contexts: 0 for LL and LH (and the LX, XL
 * and XH sub-bands of ISO/IEC 15444-2), 1 for HL (and HX), 2 for LH of ISO/IEC 15444-1, 3 for HH
 * @param x0 the left edge in sub-band coordinates
 * @param y0 the top edge in sub-band coordinates
 * @param x1 the right edge in sub-band coordinates (exclusive)
 * @param y1 the bottom edge in sub-band coordinates (exclusive)
 * @param stepSize the quantization step size, for the irreversible wavelets
 * @param bitPlanes the maximum number of magnitude bit-planes, {@code Mb} of equation E-2 minus one
 * @param index the index of the sub-band in codestream order, which selects its step size
 * @param precincts the precincts, row-major over the resolution's precinct grid; shared, not copied
 * @param offsetX the column of the sub-band's first sample in the tile-component's coefficient buffer
 * @param offsetY the row of the sub-band's first sample in the tile-component's coefficient buffer
 */
record Band(int orientation, long x0, long y0, long x1, long y1, float stepSize, int bitPlanes, int index,
            Precinct[] precincts, int offsetX, int offsetY) {
    /**
     * {@return whether the sub-band holds no sample}
     */
    boolean empty() {
        return x1 <= x0 || y1 <= y0;
    }
}
