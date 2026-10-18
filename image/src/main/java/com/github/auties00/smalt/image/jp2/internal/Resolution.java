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
 * @param bands the sub-bands: LL alone for the lowest resolution, otherwise HL, LH and HH; shared,
 * not copied
 */
record Resolution(int x0, int y0, int x1, int y1, int precinctWidth, int precinctHeight, int precinctsWide,
                  int precinctsHigh, Band[] bands) {
    /**
     * {@return the width in samples}
     */
    int width() {
        return x1 - x0;
    }

    /**
     * {@return the height in samples}
     */
    int height() {
        return y1 - y0;
    }
}
