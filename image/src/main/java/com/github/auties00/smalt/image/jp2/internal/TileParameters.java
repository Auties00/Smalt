package com.github.auties00.smalt.image.jp2.internal;

import java.util.List;

/**
 * Holds the coding parameters in force for one tile, resolved from the main header and the
 * tile-part headers.
 *
 * @param codingFlags   the {@code Scod} flags: bit 0 user precincts, bit 1 {@code SOP} markers,
 *                      bit 2 {@code EPH} markers
 * @param progression   the default progression order
 * @param layers        the number of quality layers
 * @param transform     the multiple component transformation, 0 (none) or 1 (RCT or ICT)
 * @param codings       the coding style of each component
 * @param quantizations the quantization of each component
 * @param roiShifts     the region-of-interest shift of each component, 0 without
 * @param progressions  the progression changes, empty when the default order applies
 */
record TileParameters(int codingFlags, int progression, int layers, int transform, ComponentCoding[] codings,
                      Quantization[] quantizations, int[] roiShifts, List<ProgressionChange> progressions) {
    /**
     * Returns whether every packet starts with an {@code SOP} marker segment.
     *
     * @return whether {@code SOP} markers may be present
     */
    boolean sopMarkers() {
        return (codingFlags & 2) != 0;
    }

    /**
     * Returns whether every packet header ends with an {@code EPH} marker.
     *
     * @return whether {@code EPH} markers are present
     */
    boolean ephMarkers() {
        return (codingFlags & 4) != 0;
    }
}
