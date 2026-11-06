package com.github.auties00.smalt.image.jp2.internal;

import java.util.Map;

/**
 * Resolves the precinct-dependent quantization of one tile-component from the {@code QPD} and {@code QPC} segments of
 * ISO/IEC 15444-2 (A.3.11 and A.3.12), with their precedence: a {@code QPC} of the tile, a {@code QPD} of the tile, the
 * tile's {@code QCC} or {@code QCD}, a {@code QPC} of the main header, a {@code QPD} of the main header, and the main
 * header's {@code QCC} or {@code QCD}.
 *
 * @param tileComponent the tile's {@code QPC} segments of the component, by {@link #key}
 * @param tileDefault the tile's {@code QPD} segments, by {@link #key}
 * @param mainComponent the main header's {@code QPC} segments of the component, by {@link #key}
 * @param mainDefault the main header's {@code QPD} segments, by {@link #key}
 * @param tileQuantized whether the tile's headers carry a {@code QCD} or a {@code QCC} of the component, which hides
 * the main header's precinct segments
 */
record PrecinctQuantizations(Map<Long, Quantization> tileComponent, Map<Long, Quantization> tileDefault,
                             Map<Long, Quantization> mainComponent, Map<Long, Quantization> mainDefault,
                             boolean tileQuantized) {
    /**
     * Returns the key of a resolution level and precinct.
     *
     * @param resolution the resolution level index {@code lev}
     * @param precinct the precinct index within the resolution level
     * @return the key
     */
    static long key(int resolution, int precinct) {
        return ((long) resolution << 32) | (precinct & 0xFFFFFFFFL);
    }

    /**
     * Returns the quantization of a precinct when a precinct segment overrides the tile-component's.
     *
     * @param resolution the resolution level index
     * @param precinct the precinct index within the resolution level
     * @return the precinct's quantization, or {@code null} when the tile-component's applies
     */
    Quantization lookup(int resolution, int precinct) {
        var key = key(resolution, precinct);
        var result = tileComponent.get(key);
        if (result == null) {
            result = tileDefault.get(key);
        }
        if (result == null && !tileQuantized) {
            result = mainComponent.get(key);
            if (result == null) {
                result = mainDefault.get(key);
            }
        }
        return result;
    }
}
