package com.github.auties00.smalt.image.jp2;

/**
 * Enumerates the progression orders of JPEG 2000 packets (ISO/IEC 15444-1 Table A.16): the order
 * in which quality layers, resolution levels, components and precinct positions are nested.
 *
 * <p>The order decides which partial decodes a truncated codestream allows: a layer-first order
 * gives a coarse full-size image first, a resolution-first order a sharp small image first.
 *
 * <p>Constants may be added in minor releases, so a {@code switch} over this type needs a
 * {@code default} branch.
 */
public enum Jp2ProgressionOrder {
    /**
     * Layer, resolution, component, position: quality progressive.
     */
    LRCP,

    /**
     * Resolution, layer, component, position: resolution progressive.
     */
    RLCP,

    /**
     * Resolution, position, component, layer: resolution progressive, spatially ordered.
     */
    RPCL,

    /**
     * Position, component, resolution, layer: spatially progressive.
     */
    PCRL,

    /**
     * Component, position, resolution, layer: component progressive.
     */
    CPRL
}
