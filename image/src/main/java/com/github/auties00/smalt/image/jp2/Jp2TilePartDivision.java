package com.github.auties00.smalt.image.jp2;

/**
 * Enumerates the ways a JPEG 2000 encoder divides the packets of each tile into tile-parts
 * (ISO/IEC 15444-1 A.4.2).
 *
 * <p>A tile-part starts each time the chosen dimension, or a dimension the progression order nests
 * outside it, takes a new value; a decoder or a server can then reach every resolution, layer or
 * component of a tile through its tile-part headers alone. A progression whose packets the chosen
 * dimension does not cover still gets one tile-part per value, possibly empty.
 *
 * <p>Constants may be added in minor releases, so a {@code switch} over this type needs a
 * {@code default} branch.
 */
public enum Jp2TilePartDivision {
    /**
     * A tile-part per resolution level ({@code opj_compress -TP R}).
     */
    RESOLUTION('R'),

    /**
     * A tile-part per quality layer ({@code opj_compress -TP L}).
     */
    LAYER('L'),

    /**
     * A tile-part per component ({@code opj_compress -TP C}).
     */
    COMPONENT('C');

    /**
     * The letter of the dimension in a progression order name.
     */
    private final char letter;

    /**
     * Constructs a constant.
     *
     * @param letter the dimension's letter
     */
    Jp2TilePartDivision(char letter) {
        this.letter = letter;
    }

    /**
     * {@return the letter of the dimension in a progression order name: R, L or C}
     */
    char letter() {
        return letter;
    }
}
