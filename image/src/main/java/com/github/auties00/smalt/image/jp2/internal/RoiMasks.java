package com.github.auties00.smalt.image.jp2.internal;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.internal.limits.LimitChecks;

import java.util.List;

/**
 * Generates the masks of arbitrarily shaped regions of interest in the wavelet domain (ISO/IEC 15444-2 L.3): the
 * region on the reference grid is mapped to the tile-component and decomposed like its samples, each lifting step
 * marking every coefficient that reconstructs a marked one (L.3.4).
 *
 * <p>Arguments are not checked; values outside the documented ranges give unspecified results.
 */
final class RoiMasks {
    /**
     * Prevents instantiation.
     */
    private RoiMasks() {
        throw new AssertionError();
    }

    /**
     * Computes the scaling value of every coefficient of a tile-component: the largest scaling value of the regions
     * whose mask holds it, 0 for the background (equation L-1 before the subtraction from {@code sMax}).
     *
     * @param component the tile-component at full resolution structure
     * @param regions the regions of interest of the component, each with its scaling value
     * @param subsamplingX the component's horizontal subsampling
     * @param subsamplingY the component's vertical subsampling
     * @param limits the resource bounds checked before the allocation
     * @return the scaling values, unsigned bytes in the layout of the coefficient buffer of the full-resolution
     * tile-component
     */
    static byte[] scales(TileComponent component, List<HeaderScope.RoiRegion> regions, int subsamplingX,
                         int subsamplingY, MediaLimits limits) {
        var full = component.resolutions[component.resolutions.length - 1];
        var width = full.width();
        var height = full.height();
        LimitChecks.checkAllocation(limits, (long) width * height * 2);
        var result = new byte[width * height];
        var mask = new byte[width * height];
        var kernel = component.kernel != null ? component.kernel
                : component.reversible() ? LiftingKernel.REVERSIBLE_53 : LiftingKernel.IRREVERSIBLE_97;
        var line = new byte[Math.max(width, height)];
        var scratch = new byte[line.length];
        for (var region : regions) {
            for (var v = 0; v < height; v++) {
                var y = (component.y0 + v) * subsamplingY;
                for (var u = 0; u < width; u++) {
                    mask[v * width + u] = (byte) (region.contains((component.x0 + u) * subsamplingX, y) ? 1 : 0);
                }
            }
            if (full.node() != null) {
                decompose(mask, width, full.node(), kernel, line, scratch);
            } else {
                for (var r = component.resolutions.length - 1; r >= 1; r--) {
                    var level = component.resolutions[r];
                    var lower = component.resolutions[r - 1];
                    columns(mask, width, 0, 0, level.width(), level.height(), lower.height(), level.y0(), kernel,
                            line, scratch);
                    rows(mask, width, 0, 0, level.width(), level.height(), lower.width(), level.x0(), kernel, line,
                            scratch);
                }
            }
            var shift = (byte) region.shift();
            for (var i = 0; i < mask.length; i++) {
                if (mask[i] != 0 && (result[i] & 0xFF) < region.shift()) {
                    result[i] = shift;
                }
            }
        }
        return result;
    }

    /**
     * Decomposes the mask of a node of an arbitrary decomposition and its descendants.
     *
     * @param mask the mask, in the layout of the coefficient buffer
     * @param stride the row stride
     * @param node the node to decompose, with its descendants
     * @param kernel the lifting kernel
     * @param line a buffer of the longest row or column
     * @param scratch a second such buffer
     */
    private static void decompose(byte[] mask, int stride, SubbandNode node, LiftingKernel kernel, byte[] line,
                                  byte[] scratch) {
        var children = node.children();
        if (children == null) {
            return;
        }
        var width = node.width();
        var height = node.height();
        if (width > 0 && height > 0) {
            if (node.split() != Decomposition.HORIZONTAL) {
                columns(mask, stride, node.offsetX(), node.offsetY(), width, height, children[0].height(), node.y0(),
                        kernel, line, scratch);
            }
            if (node.split() != Decomposition.VERTICAL) {
                rows(mask, stride, node.offsetX(), node.offsetY(), width, height, children[0].width(), node.x0(),
                        kernel, line, scratch);
            }
        }
        for (var child : children) {
            decompose(mask, stride, child, kernel, line, scratch);
        }
    }

    /**
     * Decomposes the rows of an area of the mask.
     *
     * @param mask the mask, in the layout of the coefficient buffer
     * @param stride the row stride
     * @param left the area's first column
     * @param top the area's first row
     * @param width the area's width
     * @param height the area's height
     * @param sn the number of low-pass coefficients per row
     * @param i0 the coordinate of the area's left edge
     * @param kernel the lifting kernel
     * @param line a row buffer
     * @param scratch a second row buffer
     */
    private static void rows(byte[] mask, int stride, int left, int top, int width, int height, int sn, long i0,
                             LiftingKernel kernel, byte[] line, byte[] scratch) {
        for (var y = 0; y < height; y++) {
            var base = (top + y) * stride + left;
            System.arraycopy(mask, base, line, 0, width);
            spread(line, width, i0, kernel);
            split(line, scratch, width, sn, i0);
            System.arraycopy(scratch, 0, mask, base, width);
        }
    }

    /**
     * Decomposes the columns of an area of the mask.
     *
     * @param mask the mask, in the layout of the coefficient buffer
     * @param stride the row stride
     * @param left the area's first column
     * @param top the area's first row
     * @param width the area's width
     * @param height the area's height
     * @param sn the number of low-pass coefficients per column
     * @param i0 the coordinate of the area's top edge
     * @param kernel the lifting kernel
     * @param line a column buffer
     * @param scratch a second column buffer
     */
    private static void columns(byte[] mask, int stride, int left, int top, int width, int height, int sn, long i0,
                                LiftingKernel kernel, byte[] line, byte[] scratch) {
        for (var x = 0; x < width; x++) {
            for (var y = 0; y < height; y++) {
                line[y] = mask[(top + y) * stride + left + x];
            }
            spread(line, height, i0, kernel);
            split(line, scratch, height, sn, i0);
            for (var y = 0; y < height; y++) {
                mask[(top + y) * stride + left + x] = scratch[y];
            }
        }
    }

    /**
     * Marks, for each lifting step in decomposition order, the samples that update a marked sample (equation L-11).
     *
     * @param line the one-dimensional mask in spatial order
     * @param n its length
     * @param i0 the coordinate of its first sample
     * @param kernel the lifting kernel
     */
    private static void spread(byte[] line, int n, long i0, LiftingKernel kernel) {
        if (n <= 1) {
            return;
        }
        var i1 = i0 + n;
        for (var s = 0; s < kernel.steps(); s++) {
            var m = kernel.updates[s];
            var count = kernel.coefficients[s].length;
            for (var p = (int) Math.floorMod(m - i0, 2); p < n; p += 2) {
                if (line[p] == 0) {
                    continue;
                }
                var base = i0 + p + 1 - 2 * m + 2L * kernel.offsets[s];
                for (var k = 0; k < count; k++) {
                    line[kernel.extend(base + 2L * k, i0, i1)] = 1;
                }
            }
        }
    }

    /**
     * Separates a decomposed one-dimensional mask into its low-pass and high-pass halves.
     *
     * @param line the mask in spatial order
     * @param target receives the low-pass samples, then the high-pass ones
     * @param n the length
     * @param sn the number of low-pass samples
     * @param i0 the coordinate of the first sample
     */
    private static void split(byte[] line, byte[] target, int n, int sn, long i0) {
        var cas = (int) (i0 & 1);
        for (var i = 0; i < sn; i++) {
            target[i] = line[2 * i + cas];
        }
        for (var i = 0; i < n - sn; i++) {
            target[sn + i] = line[2 * i + 1 - cas];
        }
    }
}
