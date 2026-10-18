package com.github.auties00.smalt.image.jp2.internal;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Collects the coding parameters one header declares: the main header, or the tile-part headers
 * of one tile.
 *
 * <p>A component's parameters are resolved with the precedence of ISO/IEC 15444-1 A.6: a
 * {@code COC} or {@code QCC} of the tile, then the tile's {@code COD} or {@code QCD}, then the
 * main header's {@code COC} or {@code QCC}, then its {@code COD} or {@code QCD}; the order of the
 * segments within one header does not matter.
 */
final class HeaderScope {
    /**
     * The {@code Scod} flags of the {@code COD} segment, or {@code -1} when there is none.
     */
    int codingFlags = -1;

    /**
     * The progression order of the {@code COD} segment.
     */
    int progression;

    /**
     * The number of quality layers of the {@code COD} segment.
     */
    int layers;

    /**
     * The multiple component transformation of the {@code COD} segment.
     */
    int transform;

    /**
     * The component coding style of the {@code COD} segment, or {@code null}.
     */
    ComponentCoding coding;

    /**
     * The coding style of each {@code COC} segment, by component; entries are {@code null} for
     * components without one.
     */
    final ComponentCoding[] componentCodings;

    /**
     * The quantization of the {@code QCD} segment, or {@code null}.
     */
    Quantization quantization;

    /**
     * The quantization of each {@code QCC} segment, by component; entries are {@code null} for
     * components without one.
     */
    final Quantization[] componentQuantizations;

    /**
     * The region-of-interest shift of each {@code RGN} segment, by component; {@code -1} for
     * components without one.
     */
    final int[] roiShifts;

    /**
     * The progressions of the {@code POC} segments, in order.
     */
    final List<ProgressionChange> progressions = new ArrayList<>();

    /**
     * Constructs an empty scope.
     *
     * @param components the number of image components
     */
    HeaderScope(int components) {
        this.componentCodings = new ComponentCoding[components];
        this.componentQuantizations = new Quantization[components];
        this.roiShifts = new int[components];
        Arrays.fill(roiShifts, -1);
    }
}
