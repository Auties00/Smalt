package com.github.auties00.smalt.image.jp2.internal;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.internal.limits.LimitChecks;

import java.util.Arrays;
import java.util.List;

/**
 * Lists the packets of a tile in codestream order (ISO/IEC 15444-1 B.12): the order of the
 * {@code COD} progression, or of each {@code POC} progression in turn.
 *
 * <p>Each packet is identified by its layer, resolution level, component and precinct. Under
 * progression changes a packet already listed by an earlier progression is not listed again.
 *
 * @implNote This implementation follows OpenJPEG's {@code pi.c} decoder iterators, including the
 * tests that decide at which reference grid position a precinct of the position-driven orders is
 * reached. Instead of visiting every position of the tile's grid step by step, it visits only the
 * positions at which some precinct can be reached, which lists the same packets in the same order
 * with work proportional to the number of precincts.
 */
final class PacketOrder {
    /**
     * The callback receiving each packet.
     */
    @FunctionalInterface
    interface Visitor {
        /**
         * Receives one packet.
         *
         * @param layer the quality layer
         * @param resolution the resolution level
         * @param component the component index
         * @param precinct the precinct index within the resolution
         * @return {@code false} to stop listing packets
         */
        boolean packet(int layer, int resolution, int component, int precinct);

        /**
         * Receives one packet with the reference grid position at which a position-driven order
         * reaches its precinct.
         *
         * @implSpec The default implementation calls {@link #packet(int, int, int, int)}.
         * @param layer the quality layer
         * @param resolution the resolution level
         * @param component the component index
         * @param precinct the precinct index within the resolution
         * @param x the horizontal position, or {@code -1} for the layer- and resolution-first orders
         * @param y the vertical position, or {@code -1} for the layer- and resolution-first orders
         * @return {@code false} to stop listing packets
         */
        default boolean packet(int layer, int resolution, int component, int precinct, long x, long y) {
            return packet(layer, resolution, component, precinct);
        }
    }

    /**
     * The left edge of the tile on the reference grid.
     */
    private final long tx0;

    /**
     * The top edge of the tile on the reference grid.
     */
    private final long ty0;

    /**
     * The right edge of the tile on the reference grid (exclusive).
     */
    private final long tx1;

    /**
     * The bottom edge of the tile on the reference grid (exclusive).
     */
    private final long ty1;

    /**
     * The tile-components.
     */
    private final TileComponent[] components;

    /**
     * The horizontal subsampling of each component.
     */
    private final int[] subsamplingX;

    /**
     * The vertical subsampling of each component.
     */
    private final int[] subsamplingY;

    /**
     * The number of layers.
     */
    private final int layers;

    /**
     * The largest number of resolution levels of a component.
     */
    private final int maxResolutions;

    /**
     * The largest number of precincts of a resolution level.
     */
    private final int maxPrecincts;

    /**
     * The packets already listed, as a bit set, or {@code null} without progression changes.
     */
    private final long[] listed;

    /**
     * Constructs the order of a tile.
     *
     * @param bounds the tile's area on the reference grid: left, top, right, bottom
     * @param components the tile-components
     * @param subsamplingX the horizontal subsampling of each component
     * @param subsamplingY the vertical subsampling of each component
     * @param layers the number of layers
     * @param changes whether progression changes apply
     * @param limits the limits bounding the bookkeeping of listed packets
     */
    PacketOrder(long[] bounds, TileComponent[] components, int[] subsamplingX, int[] subsamplingY, int layers,
                boolean changes, MediaLimits limits) {
        this.tx0 = bounds[0];
        this.ty0 = bounds[1];
        this.tx1 = bounds[2];
        this.ty1 = bounds[3];
        this.components = components;
        this.subsamplingX = subsamplingX;
        this.subsamplingY = subsamplingY;
        this.layers = layers;
        var resolutions = 0;
        var precincts = 0;
        for (var component : components) {
            resolutions = Math.max(resolutions, component.resolutions.length);
            for (var resolution : component.resolutions) {
                precincts = Math.max(precincts, resolution.precinctsWide() * resolution.precinctsHigh());
            }
        }
        this.maxResolutions = resolutions;
        this.maxPrecincts = precincts;
        if (changes) {
            var total = (long) (layers + 1) * resolutions * components.length * Math.max(precincts, 1);
            LimitChecks.checkAllocation(limits, total / 8 + 8);
            listed = new long[(int) ((total + 63) >>> 6)];
        } else {
            listed = null;
        }
    }

    /**
     * Forgets the packets listed so far, so that the progressions can be listed again.
     */
    void reset() {
        if (listed != null) {
            Arrays.fill(listed, 0);
        }
    }

    /**
     * Lists the packets of one progression.
     *
     * @param change the progression
     * @param visitor the callback
     * @return {@code false} when the visitor asked to stop
     */
    boolean list(ProgressionChange change, Visitor visitor) {
        var componentStart = change.componentStart();
        var componentEnd = Math.min(change.componentEnd(), components.length);
        if (componentStart >= components.length || componentEnd <= componentStart) {
            return true;
        }
        var resolutionStart = change.resolutionStart();
        var resolutionEnd = Math.min(change.resolutionEnd(), maxResolutions);
        var layerEnd = Math.min(change.layerEnd(), layers);
        return switch (change.order()) {
            case ProgressionChange.LRCP -> lrcp(layerEnd, resolutionStart, resolutionEnd, componentStart,
                    componentEnd, visitor);
            case ProgressionChange.RLCP -> rlcp(layerEnd, resolutionStart, resolutionEnd, componentStart,
                    componentEnd, visitor);
            case ProgressionChange.RPCL -> rpcl(layerEnd, resolutionStart, resolutionEnd, componentStart,
                    componentEnd, visitor);
            case ProgressionChange.PCRL -> pcrl(layerEnd, resolutionStart, resolutionEnd, componentStart,
                    componentEnd, visitor);
            default -> cprl(layerEnd, resolutionStart, resolutionEnd, componentStart, componentEnd, visitor);
        };
    }

    /**
     * Lists packets in layer-resolution-component-position order.
     *
     * @param layerEnd the layer after the last
     * @param resolutionStart the first resolution
     * @param resolutionEnd the resolution after the last
     * @param componentStart the first component
     * @param componentEnd the component after the last
     * @param visitor the callback
     * @return {@code false} when the visitor asked to stop
     */
    private boolean lrcp(int layerEnd, int resolutionStart, int resolutionEnd, int componentStart, int componentEnd,
                         Visitor visitor) {
        for (var l = 0; l < layerEnd; l++) {
            for (var r = resolutionStart; r < resolutionEnd; r++) {
                for (var c = componentStart; c < componentEnd; c++) {
                    if (!precincts(l, r, c, visitor)) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    /**
     * Lists packets in resolution-layer-component-position order.
     *
     * @param layerEnd the layer after the last
     * @param resolutionStart the first resolution
     * @param resolutionEnd the resolution after the last
     * @param componentStart the first component
     * @param componentEnd the component after the last
     * @param visitor the callback
     * @return {@code false} when the visitor asked to stop
     */
    private boolean rlcp(int layerEnd, int resolutionStart, int resolutionEnd, int componentStart, int componentEnd,
                         Visitor visitor) {
        for (var r = resolutionStart; r < resolutionEnd; r++) {
            for (var l = 0; l < layerEnd; l++) {
                for (var c = componentStart; c < componentEnd; c++) {
                    if (!precincts(l, r, c, visitor)) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    /**
     * Lists every precinct of one layer, resolution and component.
     *
     * @param l the layer
     * @param r the resolution
     * @param c the component
     * @param visitor the callback
     * @return {@code false} when the visitor asked to stop
     */
    private boolean precincts(int l, int r, int c, Visitor visitor) {
        var component = components[c];
        if (r >= component.resolutions.length) {
            return true;
        }
        var resolution = component.resolutions[r];
        var count = resolution.precinctsWide() * resolution.precinctsHigh();
        for (var p = 0; p < count; p++) {
            if (first(l, r, c, p) && !visitor.packet(l, r, c, p, -1, -1)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Lists packets in resolution-position-component-layer order.
     *
     * @param layerEnd the layer after the last
     * @param resolutionStart the first resolution
     * @param resolutionEnd the resolution after the last
     * @param componentStart the first component
     * @param componentEnd the component after the last
     * @param visitor the callback
     * @return {@code false} when the visitor asked to stop
     */
    private boolean rpcl(int layerEnd, int resolutionStart, int resolutionEnd, int componentStart, int componentEnd,
                         Visitor visitor) {
        var steps = steps(0, components.length);
        if (steps == null) {
            return true;
        }
        var ys = positions(ty0, ty1, steps[1], 0, components.length, false);
        var xs = positions(tx0, tx1, steps[0], 0, components.length, true);
        for (var r = resolutionStart; r < resolutionEnd; r++) {
            for (var y : ys) {
                for (var x : xs) {
                    for (var c = componentStart; c < componentEnd; c++) {
                        if (!position(x, y, r, c, layerEnd, visitor)) {
                            return false;
                        }
                    }
                }
            }
        }
        return true;
    }

    /**
     * Lists packets in position-component-resolution-layer order.
     *
     * @param layerEnd the layer after the last
     * @param resolutionStart the first resolution
     * @param resolutionEnd the resolution after the last
     * @param componentStart the first component
     * @param componentEnd the component after the last
     * @param visitor the callback
     * @return {@code false} when the visitor asked to stop
     */
    private boolean pcrl(int layerEnd, int resolutionStart, int resolutionEnd, int componentStart, int componentEnd,
                         Visitor visitor) {
        var steps = steps(0, components.length);
        if (steps == null) {
            return true;
        }
        var ys = positions(ty0, ty1, steps[1], 0, components.length, false);
        var xs = positions(tx0, tx1, steps[0], 0, components.length, true);
        for (var y : ys) {
            for (var x : xs) {
                for (var c = componentStart; c < componentEnd; c++) {
                    var end = Math.min(resolutionEnd, components[c].resolutions.length);
                    for (var r = resolutionStart; r < end; r++) {
                        if (!position(x, y, r, c, layerEnd, visitor)) {
                            return false;
                        }
                    }
                }
            }
        }
        return true;
    }

    /**
     * Lists packets in component-position-resolution-layer order.
     *
     * @param layerEnd the layer after the last
     * @param resolutionStart the first resolution
     * @param resolutionEnd the resolution after the last
     * @param componentStart the first component
     * @param componentEnd the component after the last
     * @param visitor the callback
     * @return {@code false} when the visitor asked to stop
     */
    private boolean cprl(int layerEnd, int resolutionStart, int resolutionEnd, int componentStart, int componentEnd,
                         Visitor visitor) {
        for (var c = componentStart; c < componentEnd; c++) {
            var steps = steps(c, c + 1);
            if (steps == null) {
                return true;
            }
            var ys = positions(ty0, ty1, steps[1], c, c + 1, false);
            var xs = positions(tx0, tx1, steps[0], c, c + 1, true);
            var end = Math.min(resolutionEnd, components[c].resolutions.length);
            for (var y : ys) {
                for (var x : xs) {
                    for (var r = resolutionStart; r < end; r++) {
                        if (!position(x, y, r, c, layerEnd, visitor)) {
                            return false;
                        }
                    }
                }
            }
        }
        return true;
    }

    /**
     * Computes the grid steps of the position-driven orders: the smallest precinct size, on the
     * reference grid, over a range of components and all their resolutions.
     *
     * @param componentStart the first component
     * @param componentEnd the component after the last
     * @return the horizontal and vertical steps, or {@code null} when no precinct size fits 32
     * bits, which ends the progression
     */
    private long[] steps(int componentStart, int componentEnd) {
        var dx = 0L;
        var dy = 0L;
        for (var c = componentStart; c < componentEnd; c++) {
            var component = components[c];
            var count = component.resolutions.length;
            for (var r = 0; r < count; r++) {
                var resolution = component.resolutions[r];
                var shiftX = resolution.precinctWidth() + resolution.depthX();
                var shiftY = resolution.precinctHeight() + resolution.depthY();
                if (shiftX < 32 && ((long) subsamplingX[c] << shiftX) <= 0xFFFFFFFFL) {
                    var step = (long) subsamplingX[c] << shiftX;
                    dx = dx == 0 ? step : Math.min(dx, step);
                }
                if (shiftY < 32 && ((long) subsamplingY[c] << shiftY) <= 0xFFFFFFFFL) {
                    var step = (long) subsamplingY[c] << shiftY;
                    dy = dy == 0 ? step : Math.min(dy, step);
                }
            }
        }
        return dx == 0 || dy == 0 ? null : new long[]{dx, dy};
    }

    /**
     * Lists the reference grid positions, along one axis, at which some precinct of a range of
     * components can be reached: the tile's edge, and the positions the grid walk visits that are
     * multiples of a precinct size.
     *
     * @param start the tile's first position
     * @param end the position after the tile
     * @param step the grid step of the walk
     * @param componentStart the first component
     * @param componentEnd the component after the last
     * @param horizontal whether the axis is horizontal
     * @return the positions, ascending and distinct
     */
    private long[] positions(long start, long end, long step, int componentStart, int componentEnd,
                             boolean horizontal) {
        var values = new long[16];
        var count = 0;
        values[count++] = start;
        for (var c = componentStart; c < componentEnd; c++) {
            var component = components[c];
            var resolutions = component.resolutions.length;
            var subsampling = horizontal ? subsamplingX[c] : subsamplingY[c];
            for (var r = 0; r < resolutions; r++) {
                var resolution = component.resolutions[r];
                var shift = horizontal ? resolution.precinctWidth() + resolution.depthX()
                        : resolution.precinctHeight() + resolution.depthY();
                if (shift >= 32) {
                    continue;
                }
                var size = (long) subsampling << shift;
                if (size > 0xFFFFFFFFL) {
                    continue;
                }
                var multiple = lcm(size, step);
                if (multiple <= 0 || multiple >= end) {
                    continue;
                }
                for (var position = (start / multiple + 1) * multiple; position < end; position += multiple) {
                    if (count == values.length) {
                        values = Arrays.copyOf(values, count * 2);
                    }
                    values[count++] = position;
                }
            }
        }
        Arrays.sort(values, 0, count);
        var unique = 0;
        for (var i = 0; i < count; i++) {
            if (unique == 0 || values[unique - 1] != values[i]) {
                values[unique++] = values[i];
            }
        }
        return Arrays.copyOf(values, unique);
    }

    /**
     * Returns the least common multiple of two positive values, or {@code -1} when it exceeds
     * 2^40.
     *
     * @param a the first value
     * @param b the second value
     * @return the least common multiple, or {@code -1}
     */
    private static long lcm(long a, long b) {
        var x = a;
        var y = b;
        while (y != 0) {
            var t = x % y;
            x = y;
            y = t;
        }
        var multiple = a / x;
        if (multiple > (1L << 40) / b) {
            return -1;
        }
        return multiple * b;
    }

    /**
     * Lists the packets of the precinct of one resolution and component reached at a reference
     * grid position, if any.
     *
     * @param x the horizontal position
     * @param y the vertical position
     * @param r the resolution
     * @param c the component
     * @param layerEnd the layer after the last
     * @param visitor the callback
     * @return {@code false} when the visitor asked to stop
     */
    private boolean position(long x, long y, int r, int c, int layerEnd, Visitor visitor) {
        var component = components[c];
        if (r >= component.resolutions.length) {
            return true;
        }
        var resolution = component.resolutions[r];
        var levelX = resolution.depthX();
        var levelY = resolution.depthY();
        var dx = (long) subsamplingX[c];
        var dy = (long) subsamplingY[c];
        if ((dx << levelX) > 0xFFFFFFFFL || (dy << levelY) > 0xFFFFFFFFL) {
            return true;
        }
        var trx0 = Math.ceilDiv(tx0, dx << levelX);
        var try0 = Math.ceilDiv(ty0, dy << levelY);
        var trx1 = Math.ceilDiv(tx1, dx << levelX);
        var try1 = Math.ceilDiv(ty1, dy << levelY);
        var rpx = resolution.precinctWidth() + levelX;
        var rpy = resolution.precinctHeight() + levelY;
        if (rpx >= 64 || rpy >= 64 || (dx << rpx) > 0xFFFFFFFFL || (dy << rpy) > 0xFFFFFFFFL || (dx << rpx) <= 0
                || (dy << rpy) <= 0) {
            return true;
        }
        if (!(y % (dy << rpy) == 0 || (y == ty0 && ((try0 << levelY) % (1L << rpy)) != 0))) {
            return true;
        }
        if (!(x % (dx << rpx) == 0 || (x == tx0 && ((trx0 << levelX) % (1L << rpx)) != 0))) {
            return true;
        }
        if (resolution.precinctsWide() == 0 || resolution.precinctsHigh() == 0 || trx0 == trx1 || try0 == try1) {
            return true;
        }
        var column = (Math.ceilDiv(x, dx << levelX) >> resolution.precinctWidth())
                - (trx0 >> resolution.precinctWidth());
        var row = (Math.ceilDiv(y, dy << levelY) >> resolution.precinctHeight())
                - (try0 >> resolution.precinctHeight());
        var precinct = (int) (column + row * resolution.precinctsWide());
        if (column < 0 || row < 0 || precinct >= resolution.precinctsWide() * resolution.precinctsHigh()) {
            return true;
        }
        for (var l = 0; l < layerEnd; l++) {
            if (first(l, r, c, precinct) && !visitor.packet(l, r, c, precinct, x, y)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Marks a packet as listed and reports whether it was not listed before.
     *
     * @param l the layer
     * @param r the resolution
     * @param c the component
     * @param p the precinct
     * @return whether the packet is listed for the first time
     */
    private boolean first(int l, int r, int c, int p) {
        if (listed == null) {
            return true;
        }
        var index = (((long) l * maxResolutions + r) * components.length + c) * Math.max(maxPrecincts, 1) + p;
        var word = (int) (index >>> 6);
        var mask = 1L << (index & 63);
        if ((listed[word] & mask) != 0) {
            return false;
        }
        listed[word] |= mask;
        return true;
    }

    /**
     * Returns the progressions of a tile: its progression changes, or the default one.
     *
     * @param parameters the tile's parameters
     * @param components the number of components
     * @return the progressions
     */
    static List<ProgressionChange> progressions(TileParameters parameters, int components) {
        if (!parameters.progressions().isEmpty()) {
            return parameters.progressions();
        }
        return List.of(new ProgressionChange(0, 0, parameters.layers(), ComponentCoding.MAX_RESOLUTIONS, components,
                parameters.progression()));
    }
}
