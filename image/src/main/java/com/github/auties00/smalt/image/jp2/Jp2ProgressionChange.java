package com.github.auties00.smalt.image.jp2;

import java.util.Objects;

/**
 * Describes one progression of a {@code POC} marker segment (ISO/IEC 15444-1 A.6.6): a range of
 * resolution levels, components and quality layers whose packets follow a progression order.
 *
 * <p>The ranges are half-open. A tile's packets follow its progressions in turn; a packet an
 * earlier progression already holds is not repeated, and a packet no progression covers is not
 * written, so the progressions of an encoding together cover every resolution, component and layer
 * when the image is to decode completely.
 *
 * @param resolutionStart the first resolution level, from 0 to 32
 * @param componentStart the first component, from 0 to 16383
 * @param layerEnd the quality layer after the last one, from 1 to 65535; a value above the layer
 * count covers every layer
 * @param resolutionEnd the resolution level after the last one, from {@code resolutionStart + 1}
 * to 33; a value above the resolution count covers every level from {@code resolutionStart}
 * @param componentEnd the component after the last one, from {@code componentStart + 1} to 16384;
 * a value above the component count covers every component from {@code componentStart}
 * @param order the progression order
 */
public record Jp2ProgressionChange(int resolutionStart, int componentStart, int layerEnd, int resolutionEnd,
                                   int componentEnd, Jp2ProgressionOrder order) {
    /**
     * Validates the ranges.
     *
     * @throws IllegalArgumentException if a range is empty or a bound is outside the range a
     * {@code POC} segment holds
     */
    public Jp2ProgressionChange {
        Objects.requireNonNull(order, "order");
        if (resolutionStart < 0 || resolutionEnd <= resolutionStart || resolutionEnd > 33) {
            throw new IllegalArgumentException("invalid resolution range [" + resolutionStart + ", " + resolutionEnd
                    + ")");
        }
        if (componentStart < 0 || componentEnd <= componentStart || componentEnd > 16384) {
            throw new IllegalArgumentException("invalid component range [" + componentStart + ", " + componentEnd
                    + ")");
        }
        if (layerEnd < 1 || layerEnd > 65535) {
            throw new IllegalArgumentException("invalid layer end " + layerEnd);
        }
    }
}
