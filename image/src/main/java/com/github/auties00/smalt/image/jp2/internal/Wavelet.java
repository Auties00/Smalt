package com.github.auties00.smalt.image.jp2.internal;

import com.github.auties00.smalt.internal.task.ParallelBands;

import static com.github.auties00.smalt.image.jp2.internal.WaveletKernel.LANES;

/**
 * Applies the two-dimensional discrete wavelet transforms of a tile-component (ISO/IEC 15444-1
 * annex F) to its coefficients, held row-major in one array.
 *
 * <p>The inverse transform rebuilds each resolution level from the one below and its three
 * sub-bands, rows first, then columns; the forward transform splits each level, columns first,
 * then rows. Before an inverse level, and after a forward one, the samples of a level are stored
 * with its low-pass half first along each axis, as the sub-band layout of B.5 places them.
 *
 * <p>Rows and columns are transformed {@link WaveletKernel#LANES} at a time through
 * {@link WaveletKernel#INSTANCE}, in bands that run in parallel for large levels.
 */
final class Wavelet {
    /**
     * The approximate work of one sample of one level, weighed against
     * {@link ParallelBands#MIN_PARALLEL_WORK}.
     */
    private static final long SAMPLE_WORK = 8;

    /**
     * Prevents instantiation.
     */
    private Wavelet() {
        throw new AssertionError();
    }

    /**
     * Applies the inverse 5-3 transform up to the highest decoded resolution.
     *
     * @param data the coefficients, row-major
     * @param stride the row stride of {@code data}
     * @param component the tile-component
     */
    static void inverse53(int[] data, int stride, TileComponent component) {
        var top = component.top();
        if (top.node() != null) {
            node53(data, stride, top.node());
            return;
        }
        for (var r = 1; r < component.decodedResolutions; r++) {
            var level = component.resolutions[r];
            var lower = component.resolutions[r - 1];
            var width = level.width();
            var height = level.height();
            if (width == 0 || height == 0) {
                continue;
            }
            rows53(data, 0, stride, width, height, lower.width(), (int) (level.x0() & 1), true);
            columns53(data, 0, stride, width, height, lower.height(), (int) (level.y0() & 1), true);
        }
    }

    /**
     * Applies the forward 5-3 transform over every resolution level.
     *
     * @param data the samples, row-major
     * @param stride the row stride of {@code data}
     * @param component the tile-component
     */
    static void forward53(int[] data, int stride, TileComponent component) {
        for (var r = component.resolutions.length - 1; r >= 1; r--) {
            var level = component.resolutions[r];
            var lower = component.resolutions[r - 1];
            var width = level.width();
            var height = level.height();
            if (width == 0 || height == 0) {
                continue;
            }
            columns53(data, 0, stride, width, height, lower.height(), (int) (level.y0() & 1), false);
            rows53(data, 0, stride, width, height, lower.width(), (int) (level.x0() & 1), false);
        }
    }

    /**
     * Applies the inverse irreversible transform up to the highest decoded resolution: the 9-7 wavelet, or the
     * component's {@code ATK} kernel.
     *
     * @param data the coefficients, row-major
     * @param stride the row stride of {@code data}
     * @param component the tile-component
     */
    static void inverse97(float[] data, int stride, TileComponent component) {
        var top = component.top();
        var kernel = component.kernel;
        if (top.node() != null) {
            node97(data, stride, top.node(), kernel);
            return;
        }
        for (var r = 1; r < component.decodedResolutions; r++) {
            var level = component.resolutions[r];
            var lower = component.resolutions[r - 1];
            var width = level.width();
            var height = level.height();
            if (width == 0 || height == 0) {
                continue;
            }
            rows97(data, 0, stride, width, height, lower.width(), (int) (level.x0() & 1), true, kernel);
            columns97(data, 0, stride, width, height, lower.height(), (int) (level.y0() & 1), true, kernel);
        }
    }

    /**
     * Applies the forward 9-7 transform over every resolution level.
     *
     * @param data the samples, row-major
     * @param stride the row stride of {@code data}
     * @param component the tile-component
     */
    static void forward97(float[] data, int stride, TileComponent component) {
        for (var r = component.resolutions.length - 1; r >= 1; r--) {
            var level = component.resolutions[r];
            var lower = component.resolutions[r - 1];
            var width = level.width();
            var height = level.height();
            if (width == 0 || height == 0) {
                continue;
            }
            columns97(data, 0, stride, width, height, lower.height(), (int) (level.y0() & 1), false, null);
            rows97(data, 0, stride, width, height, lower.width(), (int) (level.x0() & 1), false, null);
        }
    }

    /**
     * Applies the inverse transform of a reversible kernel up to the highest decoded resolution, in 64-bit integers:
     * the component's {@code ATK} kernel, or the 5-3 wavelet in the form of {@link LiftingKernel#REVERSIBLE_53}.
     *
     * @param data the coefficients, row-major
     * @param stride the row stride of {@code data}
     * @param component the tile-component
     */
    static void inverseWide(long[] data, int stride, TileComponent component) {
        var top = component.top();
        var kernel = component.kernel != null ? component.kernel : LiftingKernel.REVERSIBLE_53;
        if (top.node() != null) {
            nodeWide(data, stride, top.node(), kernel);
            return;
        }
        for (var r = 1; r < component.decodedResolutions; r++) {
            var level = component.resolutions[r];
            var lower = component.resolutions[r - 1];
            var width = level.width();
            var height = level.height();
            if (width == 0 || height == 0) {
                continue;
            }
            rowsWide(data, 0, stride, width, height, lower.width(), (int) (level.x0() & 1), kernel);
            columnsWide(data, 0, stride, width, height, lower.height(), (int) (level.y0() & 1), kernel);
        }
    }

    /**
     * Rebuilds a node of an arbitrary decomposition with the 5-3 wavelet: its children, then its rows and columns.
     *
     * @param data the coefficients
     * @param stride the row stride
     * @param node the node to rebuild, with its descendants
     */
    private static void node53(int[] data, int stride, SubbandNode node) {
        var children = node.children();
        if (children == null) {
            return;
        }
        for (var child : children) {
            node53(data, stride, child);
        }
        var width = node.width();
        var height = node.height();
        if (width == 0 || height == 0) {
            return;
        }
        var base = node.offsetY() * stride + node.offsetX();
        if (node.split() != Decomposition.VERTICAL) {
            rows53(data, base, stride, width, height, children[0].width(), (int) (node.x0() & 1), true);
        }
        if (node.split() != Decomposition.HORIZONTAL) {
            columns53(data, base, stride, width, height, children[0].height(), (int) (node.y0() & 1), true);
        }
    }

    /**
     * Rebuilds a node of an arbitrary decomposition with an irreversible wavelet.
     *
     * @param data the coefficients
     * @param stride the row stride
     * @param node the node to rebuild, with its descendants
     * @param kernel the {@code ATK} kernel, or {@code null} for the 9-7 wavelet
     */
    private static void node97(float[] data, int stride, SubbandNode node, LiftingKernel kernel) {
        var children = node.children();
        if (children == null) {
            return;
        }
        for (var child : children) {
            node97(data, stride, child, kernel);
        }
        var width = node.width();
        var height = node.height();
        if (width == 0 || height == 0) {
            return;
        }
        var base = node.offsetY() * stride + node.offsetX();
        if (node.split() != Decomposition.VERTICAL) {
            rows97(data, base, stride, width, height, children[0].width(), (int) (node.x0() & 1), true, kernel);
        }
        if (node.split() != Decomposition.HORIZONTAL) {
            columns97(data, base, stride, width, height, children[0].height(), (int) (node.y0() & 1), true, kernel);
        }
    }

    /**
     * Rebuilds a node of an arbitrary decomposition with a reversible kernel in 64-bit integers.
     *
     * @param data the coefficients
     * @param stride the row stride
     * @param node the node to rebuild, with its descendants
     * @param kernel the reversible lifting kernel
     */
    private static void nodeWide(long[] data, int stride, SubbandNode node, LiftingKernel kernel) {
        var children = node.children();
        if (children == null) {
            return;
        }
        for (var child : children) {
            nodeWide(data, stride, child, kernel);
        }
        var width = node.width();
        var height = node.height();
        if (width == 0 || height == 0) {
            return;
        }
        var base = node.offsetY() * stride + node.offsetX();
        if (node.split() != Decomposition.VERTICAL) {
            rowsWide(data, base, stride, width, height, children[0].width(), (int) (node.x0() & 1), kernel);
        }
        if (node.split() != Decomposition.HORIZONTAL) {
            columnsWide(data, base, stride, width, height, children[0].height(), (int) (node.y0() & 1), kernel);
        }
    }

    /**
     * Transforms the rows of a 5-3 level.
     *
     * @param data the coefficients
     * @param base0 the index of the level's first sample
     * @param stride the row stride
     * @param width the level width
     * @param height the level height
     * @param sn the number of low-pass coefficients per row
     * @param cas the parity of the level's left edge
     * @param inverse whether the inverse transform is applied
     */
    private static void rows53(int[] data, int base0, int stride, int width, int height, int sn, int cas,
                               boolean inverse) {
        var dn = width - sn;
        var groups = (height + LANES - 1) / LANES;
        var bands = ParallelBands.bands(groups, SAMPLE_WORK * width * height);
        var kernel = WaveletKernel.INSTANCE;
        ParallelBands.run(bands, band -> {
            var buffer = new int[width * LANES];
            var end = ParallelBands.start(band + 1, bands, groups);
            for (var group = ParallelBands.start(band, bands, groups); group < end; group++) {
                var row = group * LANES;
                var count = Math.min(LANES, height - row);
                if (inverse) {
                    for (var lane = 0; lane < count; lane++) {
                        var base = base0 + (row + lane) * stride;
                        for (var i = 0; i < sn; i++) {
                            buffer[(2 * i + cas) * LANES + lane] = data[base + i];
                        }
                        for (var i = 0; i < dn; i++) {
                            buffer[(2 * i + 1 - cas) * LANES + lane] = data[base + sn + i];
                        }
                    }
                    clearLanes(buffer, width, count);
                    kernel.inverse53(buffer, sn, dn, cas);
                    for (var lane = 0; lane < count; lane++) {
                        var base = base0 + (row + lane) * stride;
                        for (var k = 0; k < width; k++) {
                            data[base + k] = buffer[k * LANES + lane];
                        }
                    }
                } else {
                    for (var lane = 0; lane < count; lane++) {
                        var base = base0 + (row + lane) * stride;
                        for (var k = 0; k < width; k++) {
                            buffer[k * LANES + lane] = data[base + k];
                        }
                    }
                    clearLanes(buffer, width, count);
                    kernel.forward53(buffer, sn, dn, cas);
                    for (var lane = 0; lane < count; lane++) {
                        var base = base0 + (row + lane) * stride;
                        for (var i = 0; i < sn; i++) {
                            data[base + i] = buffer[(2 * i + cas) * LANES + lane];
                        }
                        for (var i = 0; i < dn; i++) {
                            data[base + sn + i] = buffer[(2 * i + 1 - cas) * LANES + lane];
                        }
                    }
                }
            }
        });
    }

    /**
     * Transforms the columns of a 5-3 level.
     *
     * @param data the coefficients
     * @param base0 the index of the level's first sample
     * @param stride the row stride
     * @param width the level width
     * @param height the level height
     * @param sn the number of low-pass coefficients per column
     * @param cas the parity of the level's top edge
     * @param inverse whether the inverse transform is applied
     */
    private static void columns53(int[] data, int base0, int stride, int width, int height, int sn, int cas,
                                  boolean inverse) {
        var dn = height - sn;
        var groups = (width + LANES - 1) / LANES;
        var bands = ParallelBands.bands(groups, SAMPLE_WORK * width * height);
        var kernel = WaveletKernel.INSTANCE;
        ParallelBands.run(bands, band -> {
            var buffer = new int[height * LANES];
            var end = ParallelBands.start(band + 1, bands, groups);
            for (var group = ParallelBands.start(band, bands, groups); group < end; group++) {
                var column = base0 + group * LANES;
                var count = Math.min(LANES, width - group * LANES);
                if (inverse) {
                    for (var i = 0; i < sn; i++) {
                        copyIn(data, i * stride + column, buffer, (2 * i + cas) * LANES, count);
                    }
                    for (var i = 0; i < dn; i++) {
                        copyIn(data, (sn + i) * stride + column, buffer, (2 * i + 1 - cas) * LANES, count);
                    }
                    kernel.inverse53(buffer, sn, dn, cas);
                    for (var k = 0; k < height; k++) {
                        System.arraycopy(buffer, k * LANES, data, k * stride + column, count);
                    }
                } else {
                    for (var k = 0; k < height; k++) {
                        copyIn(data, k * stride + column, buffer, k * LANES, count);
                    }
                    kernel.forward53(buffer, sn, dn, cas);
                    for (var i = 0; i < sn; i++) {
                        System.arraycopy(buffer, (2 * i + cas) * LANES, data, i * stride + column, count);
                    }
                    for (var i = 0; i < dn; i++) {
                        System.arraycopy(buffer, (2 * i + 1 - cas) * LANES, data, (sn + i) * stride + column, count);
                    }
                }
            }
        });
    }

    /**
     * Transforms the rows of a 9-7 level.
     *
     * @param data the coefficients
     * @param base0 the index of the level's first sample
     * @param stride the row stride
     * @param width the level width
     * @param height the level height
     * @param sn the number of low-pass coefficients per row
     * @param cas the parity of the level's left edge
     * @param inverse whether the inverse transform is applied
     * @param lifting the {@code ATK} kernel of an inverse transform, or {@code null} for the 9-7 wavelet
     */
    private static void rows97(float[] data, int base0, int stride, int width, int height, int sn, int cas,
                               boolean inverse, LiftingKernel lifting) {
        var dn = width - sn;
        var groups = (height + LANES - 1) / LANES;
        var bands = ParallelBands.bands(groups, SAMPLE_WORK * width * height);
        var kernel = WaveletKernel.INSTANCE;
        ParallelBands.run(bands, band -> {
            var buffer = new float[width * LANES];
            var end = ParallelBands.start(band + 1, bands, groups);
            for (var group = ParallelBands.start(band, bands, groups); group < end; group++) {
                var row = group * LANES;
                var count = Math.min(LANES, height - row);
                if (inverse) {
                    for (var lane = 0; lane < count; lane++) {
                        var base = base0 + (row + lane) * stride;
                        for (var i = 0; i < sn; i++) {
                            buffer[(2 * i + cas) * LANES + lane] = data[base + i];
                        }
                        for (var i = 0; i < dn; i++) {
                            buffer[(2 * i + 1 - cas) * LANES + lane] = data[base + sn + i];
                        }
                    }
                    clearLanes(buffer, width, count);
                    if (lifting != null) {
                        lifting.inverse(buffer, width, cas, LANES);
                    } else {
                        kernel.inverse97(buffer, sn, dn, cas);
                    }
                    for (var lane = 0; lane < count; lane++) {
                        var base = base0 + (row + lane) * stride;
                        for (var k = 0; k < width; k++) {
                            data[base + k] = buffer[k * LANES + lane];
                        }
                    }
                } else {
                    for (var lane = 0; lane < count; lane++) {
                        var base = base0 + (row + lane) * stride;
                        for (var k = 0; k < width; k++) {
                            buffer[k * LANES + lane] = data[base + k];
                        }
                    }
                    clearLanes(buffer, width, count);
                    kernel.forward97(buffer, sn, dn, cas);
                    for (var lane = 0; lane < count; lane++) {
                        var base = base0 + (row + lane) * stride;
                        for (var i = 0; i < sn; i++) {
                            data[base + i] = buffer[(2 * i + cas) * LANES + lane];
                        }
                        for (var i = 0; i < dn; i++) {
                            data[base + sn + i] = buffer[(2 * i + 1 - cas) * LANES + lane];
                        }
                    }
                }
            }
        });
    }

    /**
     * Transforms the columns of a 9-7 level.
     *
     * @param data the coefficients
     * @param base0 the index of the level's first sample
     * @param stride the row stride
     * @param width the level width
     * @param height the level height
     * @param sn the number of low-pass coefficients per column
     * @param cas the parity of the level's top edge
     * @param inverse whether the inverse transform is applied
     * @param lifting the {@code ATK} kernel of an inverse transform, or {@code null} for the 9-7 wavelet
     */
    private static void columns97(float[] data, int base0, int stride, int width, int height, int sn, int cas,
                                  boolean inverse, LiftingKernel lifting) {
        var dn = height - sn;
        var groups = (width + LANES - 1) / LANES;
        var bands = ParallelBands.bands(groups, SAMPLE_WORK * width * height);
        var kernel = WaveletKernel.INSTANCE;
        ParallelBands.run(bands, band -> {
            var buffer = new float[height * LANES];
            var end = ParallelBands.start(band + 1, bands, groups);
            for (var group = ParallelBands.start(band, bands, groups); group < end; group++) {
                var column = base0 + group * LANES;
                var count = Math.min(LANES, width - group * LANES);
                if (inverse) {
                    for (var i = 0; i < sn; i++) {
                        copyIn(data, i * stride + column, buffer, (2 * i + cas) * LANES, count);
                    }
                    for (var i = 0; i < dn; i++) {
                        copyIn(data, (sn + i) * stride + column, buffer, (2 * i + 1 - cas) * LANES, count);
                    }
                    if (lifting != null) {
                        lifting.inverse(buffer, height, cas, LANES);
                    } else {
                        kernel.inverse97(buffer, sn, dn, cas);
                    }
                    for (var k = 0; k < height; k++) {
                        System.arraycopy(buffer, k * LANES, data, k * stride + column, count);
                    }
                } else {
                    for (var k = 0; k < height; k++) {
                        copyIn(data, k * stride + column, buffer, k * LANES, count);
                    }
                    kernel.forward97(buffer, sn, dn, cas);
                    for (var i = 0; i < sn; i++) {
                        System.arraycopy(buffer, (2 * i + cas) * LANES, data, i * stride + column, count);
                    }
                    for (var i = 0; i < dn; i++) {
                        System.arraycopy(buffer, (2 * i + 1 - cas) * LANES, data, (sn + i) * stride + column, count);
                    }
                }
            }
        });
    }

    /**
     * Inverse-transforms the rows of a level with a reversible kernel in 64-bit integers.
     *
     * @param data the coefficients
     * @param base0 the index of the level's first sample
     * @param stride the row stride
     * @param width the level width
     * @param height the level height
     * @param sn the number of low-pass coefficients per row
     * @param cas the parity of the level's left edge
     * @param kernel the reversible lifting kernel
     */
    private static void rowsWide(long[] data, int base0, int stride, int width, int height, int sn, int cas,
                                 LiftingKernel kernel) {
        var dn = width - sn;
        var groups = (height + LANES - 1) / LANES;
        var bands = ParallelBands.bands(groups, SAMPLE_WORK * width * height);
        ParallelBands.run(bands, band -> {
            var buffer = new long[width * LANES];
            var end = ParallelBands.start(band + 1, bands, groups);
            for (var group = ParallelBands.start(band, bands, groups); group < end; group++) {
                var row = group * LANES;
                var count = Math.min(LANES, height - row);
                java.util.Arrays.fill(buffer, 0);
                for (var lane = 0; lane < count; lane++) {
                    var base = base0 + (row + lane) * stride;
                    for (var i = 0; i < sn; i++) {
                        buffer[(2 * i + cas) * LANES + lane] = data[base + i];
                    }
                    for (var i = 0; i < dn; i++) {
                        buffer[(2 * i + 1 - cas) * LANES + lane] = data[base + sn + i];
                    }
                }
                kernel.inverse(buffer, width, cas, LANES);
                for (var lane = 0; lane < count; lane++) {
                    var base = base0 + (row + lane) * stride;
                    for (var k = 0; k < width; k++) {
                        data[base + k] = buffer[k * LANES + lane];
                    }
                }
            }
        });
    }

    /**
     * Inverse-transforms the columns of a level with a reversible kernel in 64-bit integers.
     *
     * @param data the coefficients
     * @param base0 the index of the level's first sample
     * @param stride the row stride
     * @param width the level width
     * @param height the level height
     * @param sn the number of low-pass coefficients per column
     * @param cas the parity of the level's top edge
     * @param kernel the reversible lifting kernel
     */
    private static void columnsWide(long[] data, int base0, int stride, int width, int height, int sn, int cas,
                                    LiftingKernel kernel) {
        var dn = height - sn;
        var groups = (width + LANES - 1) / LANES;
        var bands = ParallelBands.bands(groups, SAMPLE_WORK * width * height);
        ParallelBands.run(bands, band -> {
            var buffer = new long[height * LANES];
            var end = ParallelBands.start(band + 1, bands, groups);
            for (var group = ParallelBands.start(band, bands, groups); group < end; group++) {
                var column = base0 + group * LANES;
                var count = Math.min(LANES, width - group * LANES);
                java.util.Arrays.fill(buffer, 0);
                for (var i = 0; i < sn; i++) {
                    System.arraycopy(data, i * stride + column, buffer, (2 * i + cas) * LANES, count);
                }
                for (var i = 0; i < dn; i++) {
                    System.arraycopy(data, (sn + i) * stride + column, buffer, (2 * i + 1 - cas) * LANES, count);
                }
                kernel.inverse(buffer, height, cas, LANES);
                for (var k = 0; k < height; k++) {
                    System.arraycopy(buffer, k * LANES, data, k * stride + column, count);
                }
            }
        });
    }

    /**
     * Copies up to {@link WaveletKernel#LANES} samples into one position of a buffer, zeroing the
     * lanes beyond them.
     *
     * @param data the source
     * @param from the index of the first sample
     * @param buffer the interleaved buffer
     * @param to the index of the position's first lane
     * @param count the number of samples
     */
    private static void copyIn(int[] data, int from, int[] buffer, int to, int count) {
        System.arraycopy(data, from, buffer, to, count);
        for (var lane = count; lane < LANES; lane++) {
            buffer[to + lane] = 0;
        }
    }

    /**
     * Copies up to {@link WaveletKernel#LANES} samples into one position of a buffer, zeroing the
     * lanes beyond them.
     *
     * @param data the source
     * @param from the index of the first sample
     * @param buffer the interleaved buffer
     * @param to the index of the position's first lane
     * @param count the number of samples
     */
    private static void copyIn(float[] data, int from, float[] buffer, int to, int count) {
        System.arraycopy(data, from, buffer, to, count);
        for (var lane = count; lane < LANES; lane++) {
            buffer[to + lane] = 0;
        }
    }

    /**
     * Zeroes the lanes of a row buffer that no row fills.
     *
     * @param buffer the interleaved buffer
     * @param positions the number of positions
     * @param count the number of lanes in use
     */
    private static void clearLanes(int[] buffer, int positions, int count) {
        if (count == LANES) {
            return;
        }
        for (var k = 0; k < positions; k++) {
            for (var lane = count; lane < LANES; lane++) {
                buffer[k * LANES + lane] = 0;
            }
        }
    }

    /**
     * Zeroes the lanes of a row buffer that no row fills.
     *
     * @param buffer the interleaved buffer
     * @param positions the number of positions
     * @param count the number of lanes in use
     */
    private static void clearLanes(float[] buffer, int positions, int count) {
        if (count == LANES) {
            return;
        }
        for (var k = 0; k < positions; k++) {
            for (var lane = count; lane < LANES; lane++) {
                buffer[k * LANES + lane] = 0;
            }
        }
    }
}
