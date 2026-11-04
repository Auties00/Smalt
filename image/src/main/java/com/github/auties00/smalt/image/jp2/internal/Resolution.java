package com.github.auties00.smalt.image.jp2.internal;

/**
 * Describes one resolution level of a tile-component: its area, precinct grid and sub-bands.
 *
 * @param x0 the left edge in resolution coordinates
 * @param y0 the top edge in resolution coordinates
 * @param x1 the right edge in resolution coordinates (exclusive)
 * @param y1 the bottom edge in resolution coordinates (exclusive)
 * @param precinctWidth the base-2 logarithm of the precinct width
 * @param precinctHeight the base-2 logarithm of the precinct height
 * @param precinctsWide the number of precinct columns
 * @param precinctsHigh the number of precinct rows
 * @param bands the sub-bands: LL alone for the lowest resolution, otherwise HL, LH and HH, or the sub-bands of an
 * arbitrary decomposition level in packet order; shared, not copied
 * @param depthX the number of horizontal decompositions from the tile-component to this resolution
 * @param depthY the number of vertical decompositions from the tile-component to this resolution
 * @param node the node of an arbitrary decomposition this resolution is, or {@code null} for the dyadic
 * decomposition
 */
record Resolution(long x0, long y0, long x1, long y1, int precinctWidth, int precinctHeight, int precinctsWide,
                  int precinctsHigh, Band[] bands, int depthX, int depthY, SubbandNode node) {
    /**
     * {@return the width in samples}
     */
    int width() {
        return (int) (x1 - x0);
    }

    /**
     * {@return the height in samples}
     */
    int height() {
        return (int) (y1 - y0);
    }
}
