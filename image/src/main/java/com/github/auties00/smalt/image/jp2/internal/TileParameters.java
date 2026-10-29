package com.github.auties00.smalt.image.jp2.internal;

import java.util.List;
import java.util.Map;

/**
 * Holds the coding parameters in force for one tile, resolved from the main header and the
 * tile-part headers.
 *
 * @param codingFlags   the {@code Scod} flags: bit 0 user precincts, bit 1 {@code SOP} markers,
 *                      bit 2 {@code EPH} markers
 * @param progression   the default progression order
 * @param layers        the number of quality layers
 * @param transform     the multiple component transformation: 0 (none), 1 (RCT or ICT), or the array-based (2) and
 *                      wavelet-based (4) flags of ISO/IEC 15444-2
 * @param codings       the coding style of each component, with its decomposition level count resolved
 * @param quantizations the quantization of each component
 * @param roiShifts     the maximum shift region-of-interest shift of each component, 0 without
 * @param progressions  the progression changes, empty when the default order applies
 * @param extensions    the ISO/IEC 15444-2 parameters of the tile, or {@code null} when it uses none
 */
record TileParameters(int codingFlags, int progression, int layers, int transform, ComponentCoding[] codings,
                      Quantization[] quantizations, int[] roiShifts, List<ProgressionChange> progressions,
                      Extensions extensions) {
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

    /**
     * Holds the ISO/IEC 15444-2 parameters in force for one tile.
     *
     * @param kernels the {@code ATK} kernel of each component, {@code null} for the wavelets of ISO/IEC 15444-1
     * @param decompositions the arbitrary decomposition of each component, {@code null} for the dyadic one
     * @param dcOffsets the variable DC offset of each component, or {@code null} for the level shift of ISO/IEC
     *                  15444-1
     * @param stages the component collections of each multiple component transformation stage, in inverse
     *               processing order; empty when the tile applies none
     * @param arrays the transformation and offset arrays in force, by the ten low bits of {@code Imct}
     * @param allKernels every {@code ATK} kernel in force, by index
     * @param nonLinearities the non-linear transformation of each decoded image component, {@code null} for none
     * @param regions the arbitrary regions of interest of each component
     */
    record Extensions(LiftingKernel[] kernels, Decomposition[] decompositions, double[] dcOffsets,
                      List<ComponentTransforms.Collection[]> stages, Map<Integer, double[]> arrays,
                      Map<Integer, LiftingKernel> allKernels, NonLinearity[] nonLinearities,
                      List<List<HeaderScope.RoiRegion>> regions) {
    }
}
