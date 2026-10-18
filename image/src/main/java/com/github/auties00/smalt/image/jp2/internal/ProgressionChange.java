package com.github.auties00.smalt.image.jp2.internal;

/**
 * Describes one progression of a {@code POC} segment, or the single progression a {@code COD}
 * segment implies (ISO/IEC 15444-1 A.6.6).
 *
 * <p>The ranges are half-open: resolutions {@code [resolutionStart, resolutionEnd)}, components
 * {@code [componentStart, componentEnd)} and layers {@code [0, layerEnd)}.
 *
 * @param resolutionStart the first resolution level
 * @param componentStart  the first component
 * @param layerEnd        the layer after the last one
 * @param resolutionEnd   the resolution level after the last one
 * @param componentEnd    the component after the last one
 * @param order           the progression order, 0 (LRCP) to 4 (CPRL)
 */
public record ProgressionChange(int resolutionStart, int componentStart, int layerEnd, int resolutionEnd,
                                int componentEnd, int order) {
    /**
     * The layer-resolution-component-position progression.
     */
    public static final int LRCP = 0;

    /**
     * The resolution-layer-component-position progression.
     */
    public static final int RLCP = 1;

    /**
     * The resolution-position-component-layer progression.
     */
    public static final int RPCL = 2;

    /**
     * The position-component-resolution-layer progression.
     */
    public static final int PCRL = 3;

    /**
     * The component-position-resolution-layer progression.
     */
    public static final int CPRL = 4;
}
