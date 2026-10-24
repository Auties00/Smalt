package com.github.auties00.smalt.image.jp2.internal;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Collects the coding parameters one header declares: the main header, or the tile-part headers
 * of one tile.
 *
 * <p>A component's parameters are resolved with the precedence of ISO/IEC 15444-1 A.6: a
 * {@code COC} or {@code QCC} of the tile, then the tile's {@code COD} or {@code QCD}, then the
 * main header's {@code COC} or {@code QCC}, then its {@code COD} or {@code QCD}; the order of the
 * segments within one header does not matter. The segments of ISO/IEC 15444-2 follow the precedence their clauses of
 * annex A give.
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
     * The region-of-interest shift of each {@code RGN} segment of the maximum shift style, by component; {@code -1}
     * for components without one.
     */
    final int[] roiShifts;

    /**
     * The region-of-interest shift of the maximum shift {@code RGN} segment applying to every component (ISO/IEC
     * 15444-2 A.2.5), or {@code -1} without one.
     */
    int defaultRoiShift = -1;

    /**
     * The arbitrary regions of interest of the {@code RGN} segments of each component (ISO/IEC 15444-2 A.2.5).
     */
    final List<List<RoiRegion>> componentRegions;

    /**
     * The arbitrary regions of interest of the {@code RGN} segments applying to every component.
     */
    final List<RoiRegion> defaultRegions = new ArrayList<>();

    /**
     * The progressions of the {@code POC} segments, in order.
     */
    final List<ProgressionChange> progressions = new ArrayList<>();

    /**
     * The variable DC offset of each component from the {@code DCO} segment, or {@code null} without one.
     */
    double[] dcOffsets;

    /**
     * The non-linear transformation of the {@code NLT} segment applying to every component, or {@code null}.
     */
    NonLinearity defaultNonLinearity;

    /**
     * The non-linear transformation of each component's {@code NLT} segment, by component.
     */
    final Map<Integer, NonLinearity> nonLinearities = new HashMap<>();

    /**
     * The {@code MCT} and {@code MCC} series.
     */
    final ComponentTransforms.Series componentTransforms = new ComponentTransforms.Series();

    /**
     * The {@code MCC} indices of the stages of the {@code MCO} segment, in inverse processing order, or {@code null}
     * without one.
     */
    int[] stages;

    /**
     * The kernels of the {@code ATK} segments, by index.
     */
    final Map<Integer, LiftingKernel> kernels = new HashMap<>();

    /**
     * The decomposition structures of the {@code ADS} segments, by index.
     */
    final Map<Integer, DecompositionStyle> decompositionStyles = new HashMap<>();

    /**
     * The resolution formations of the {@code DFS} segments, by index.
     */
    final Map<Integer, int[]> downsamplingStyles = new HashMap<>();

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
        this.componentRegions = new ArrayList<>(components);
        for (var c = 0; c < components; c++) {
            componentRegions.add(new ArrayList<>());
        }
    }

    /**
     * Holds an arbitrary region of interest of an {@code RGN} segment (ISO/IEC 15444-2 annex L).
     *
     * @param ellipse whether the region is an ellipse ({@code Srgn = 2}) rather than a rectangle
     * @param shift the scaling value {@code s}
     * @param x the left edge of a rectangle or the center of an ellipse on the reference grid
     * @param y the top edge of a rectangle or the center of an ellipse on the reference grid
     * @param width the width of a rectangle or the horizontal semi-axis of an ellipse
     * @param height the height of a rectangle or the vertical semi-axis of an ellipse
     */
    record RoiRegion(boolean ellipse, int shift, long x, long y, long width, long height) {
        /**
         * Returns whether a reference grid point lies in the region (equations L-9 and L-10).
         *
         * @param px the horizontal coordinate
         * @param py the vertical coordinate
         * @return whether it does
         */
        boolean contains(long px, long py) {
            if (!ellipse) {
                return px >= x && px < x + width && py >= y && py < y + height;
            }
            var dx = (double) (px - x);
            var dy = (double) (py - y);
            var w = (double) width;
            var h = (double) height;
            return h * h * dx * dx + w * w * dy * dy <= w * w * h * h;
        }
    }

    /**
     * Holds the arrays of an {@code ADS} segment (ISO/IEC 15444-2 A.3.4 and F.2.5).
     *
     * @param levels the elements {@code d_theta(i)}: the maximum number of sub-levels of each decomposition level
     * @param structure the elements {@code d_S(i)}: how each sub-band splits
     */
    record DecompositionStyle(int[] levels, int[] structure) {
    }
}
