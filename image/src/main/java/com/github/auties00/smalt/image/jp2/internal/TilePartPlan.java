/* Ports OpenJPEG 2.5.3 (j2k.c opj_j2k_get_num_tp, pi.c opj_pi_create_encode). Copyright (c) 2002-2014,
   Universite catholique de Louvain (UCL), Belgium, and the OpenJPEG authors; BSD 2-Clause License. Modified for
   Smalt. */
package com.github.auties00.smalt.image.jp2.internal;

import java.util.List;

/**
 * Divides the packets of one tile into tile-parts (ISO/IEC 15444-1 A.4.2): one run of tile-parts per
 * progression, and within a progression a tile-part for each combination of values of the dimensions its order
 * nests down to the dividing dimension, in the order the progression visits them.
 *
 * <p>The plan depends on the tile's geometry only, so the number of tile-parts is known before any code-block is
 * coded. Since a progression visits packets in the lexicographic order of its dimensions, the packets of each
 * tile-part form one run of the progression's packets.
 *
 * @implNote This implementation numbers the tile-parts of a progression as OpenJPEG's encoder does when the
 * progression order of a change is that of the {@code COD} segment, and counts the positions of the
 * position-driven orders by the windows of the tile's finest precinct grid that OpenJPEG steps through, rather
 * than by OpenJPEG's largest precinct count, which equals it on an aligned tile.
 */
final class TilePartPlan {
    /**
     * The letters of the progression orders, by order code.
     */
    private static final String[] LETTERS = {"LRCP", "RLCP", "RPCL", "PCRL", "CPRL"};

    /**
     * The progressions of the tile.
     */
    private final List<ProgressionChange> progressions;

    /**
     * The index of the first tile-part of each progression.
     */
    private final int[] first;

    /**
     * The number of dimensions that divide each progression into tile-parts.
     */
    private final int[] depth;

    /**
     * The number of values of each dividing dimension of each progression.
     */
    private final int[][] sizes;

    /**
     * The number of layers.
     */
    private final int layers;

    /**
     * The left edge of the tile on the reference grid.
     */
    private final long tx0;

    /**
     * The top edge of the tile on the reference grid.
     */
    private final long ty0;

    /**
     * The width of the windows of the position-driven orders on the reference grid.
     */
    private final long stepX;

    /**
     * The height of the windows of the position-driven orders on the reference grid.
     */
    private final long stepY;

    /**
     * The number of window columns.
     */
    private final int windowsWide;

    /**
     * The number of tile-parts.
     */
    private final int count;

    /**
     * Plans the tile-parts of a tile.
     *
     * @param config the parameters
     * @param progressions the progressions of the tile, the {@code COD} one alone without changes
     * @param bounds the tile's area on the reference grid: left, top, right, bottom
     */
    TilePartPlan(EncoderConfig config, List<ProgressionChange> progressions, long[] bounds) {
        this.progressions = progressions;
        this.layers = config.layers();
        this.tx0 = bounds[0];
        this.ty0 = bounds[1];
        var components = config.components();
        var resolutions = config.resolutions();
        var maxPrecincts = 0L;
        var dx = 0L;
        var dy = 0L;
        for (var c = 0; c < components; c++) {
            var sx = config.subsamplingX()[c];
            var sy = config.subsamplingY()[c];
            for (var r = 0; r < resolutions; r++) {
                var level = resolutions - 1 - r;
                var px = config.precinctWidths() == null ? 15 : config.precinctWidths()[r];
                var py = config.precinctHeights() == null ? 15 : config.precinctHeights()[r];
                var rx0 = Math.ceilDiv(bounds[0], (long) sx << level);
                var ry0 = Math.ceilDiv(bounds[1], (long) sy << level);
                var rx1 = Math.ceilDiv(bounds[2], (long) sx << level);
                var ry1 = Math.ceilDiv(bounds[3], (long) sy << level);
                var wide = rx0 == rx1 ? 0 : Math.ceilDiv(rx1, 1L << px) - Math.floorDiv(rx0, 1L << px);
                var high = ry0 == ry1 ? 0 : Math.ceilDiv(ry1, 1L << py) - Math.floorDiv(ry0, 1L << py);
                maxPrecincts = Math.max(maxPrecincts, wide * high);
                if (px + level < 32 && ((long) sx << (px + level)) <= 0xFFFF_FFFFL) {
                    var step = (long) sx << (px + level);
                    dx = dx == 0 ? step : Math.min(dx, step);
                }
                if (py + level < 32 && ((long) sy << (py + level)) <= 0xFFFF_FFFFL) {
                    var step = (long) sy << (py + level);
                    dy = dy == 0 ? step : Math.min(dy, step);
                }
            }
        }
        this.stepX = Math.max(dx, 1);
        this.stepY = Math.max(dy, 1);
        this.windowsWide = (int) windows(bounds[0], bounds[2], stepX);
        var windows = (long) windowsWide * windows(bounds[1], bounds[3], stepY);
        var split = config.tilePartSplit();
        this.first = new int[progressions.size()];
        this.depth = new int[progressions.size()];
        this.sizes = new int[progressions.size()][];
        var total = 0L;
        for (var i = 0; i < progressions.size(); i++) {
            var progression = progressions.get(i);
            var letters = LETTERS[progression.order()];
            var position = split == 0 ? -1 : letters.indexOf(split);
            first[i] = (int) Math.min(total, Integer.MAX_VALUE);
            depth[i] = position + 1;
            sizes[i] = new int[position + 1];
            var parts = 1L;
            for (var k = 0; k <= position; k++) {
                long size = switch (letters.charAt(k)) {
                    case 'L' -> Math.min(progression.layerEnd(), layers);
                    case 'R' -> Math.max(0, Math.min(progression.resolutionEnd(), resolutions)
                            - progression.resolutionStart());
                    case 'C' -> Math.max(0, Math.min(progression.componentEnd(), components)
                            - progression.componentStart());
                    default -> progression.order() <= ProgressionChange.RLCP ? maxPrecincts : windows;
                };
                sizes[i][k] = (int) Math.min(size, Integer.MAX_VALUE);
                parts *= size;
                if (parts > Integer.MAX_VALUE) {
                    parts = Integer.MAX_VALUE;
                }
            }
            total += Math.max(parts, 1);
        }
        this.count = (int) Math.min(total, Integer.MAX_VALUE);
    }

    /**
     * Returns the number of windows of a grid step that a range of the reference grid meets.
     *
     * @param start the first position
     * @param end the position after the last
     * @param step the grid step
     * @return the number of windows, 0 for an empty range
     */
    private static long windows(long start, long end, long step) {
        return end <= start ? 0 : Math.floorDiv(end - 1, step) - Math.floorDiv(start, step) + 1;
    }

    /**
     * {@return the number of tile-parts}
     */
    int count() {
        return count;
    }

    /**
     * {@return the index of the first tile-part of a progression}
     *
     * @param progression the progression index
     */
    int first(int progression) {
        return first[progression];
    }

    /**
     * Returns the tile-part that holds a packet of a progression.
     *
     * @param progression the progression index
     * @param layer the quality layer
     * @param resolution the resolution level
     * @param component the component index
     * @param precinct the precinct index
     * @param x the reference grid position at which a position-driven order reaches the precinct
     * @param y the vertical position, as for {@code x}
     * @return the tile-part index
     */
    int part(int progression, int layer, int resolution, int component, int precinct, long x, long y) {
        var change = progressions.get(progression);
        var letters = LETTERS[change.order()];
        var index = 0L;
        for (var k = 0; k < depth[progression]; k++) {
            long value = switch (letters.charAt(k)) {
                case 'L' -> layer;
                case 'R' -> resolution - change.resolutionStart();
                case 'C' -> component - change.componentStart();
                default -> change.order() <= ProgressionChange.RLCP ? precinct
                        : (Math.floorDiv(y, stepY) - Math.floorDiv(ty0, stepY)) * windowsWide
                        + Math.floorDiv(x, stepX) - Math.floorDiv(tx0, stepX);
            };
            index = index * sizes[progression][k] + value;
        }
        return (int) (first[progression] + index);
    }
}
