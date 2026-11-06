/* Ports Pillow (src/libImaging/Geometry.c, the perspective and quad maps; src/PIL/Image.py, the quad coefficients
 * and the mesh cells). Copyright Secret Labs AB, Fredrik Lundh and contributors, and Jeffrey A. Clark and
 * contributors; MIT-CMU License. Modified for Smalt. */
package com.github.auties00.smalt.image.ops;

import java.util.Arrays;
import java.util.List;

/**
 * Maps the pixel centers of a {@link Warp} result to the source points they sample.
 *
 * <p>Implementations are immutable and evaluate each point directly, so a row does not depend on the rows before
 * it.
 */
interface SourceMap {
    /**
     * Writes the source point of every pixel center of one output row.
     *
     * @param row the output row
     * @param count the number of pixels in the row
     * @param xs the destination of the source columns; NaN for a pixel that samples nothing and takes the
     * background
     * @param ys the destination of the source rows; NaN likewise
     */
    void row(int row, int count, double[] xs, double[] ys);

    /**
     * Returns the map of an affine transform.
     *
     * @param inverse the map from output coordinates to source coordinates
     * @return the source map
     */
    static SourceMap affine(AffineTransform inverse) {
        return (row, count, xs, ys) -> {
            var cy = row + 0.5;
            var rowX = inverse.m01() * cy + inverse.m02();
            var rowY = inverse.m11() * cy + inverse.m12();
            for (var i = 0; i < count; i++) {
                var cx = i + 0.5;
                xs[i] = inverse.m00() * cx + rowX;
                ys[i] = inverse.m10() * cx + rowY;
            }
        };
    }

    /**
     * Returns the map of a projective transform; points whose denominator is not positive sample nothing.
     *
     * @param inverse the map from output coordinates to source coordinates
     * @return the source map
     */
    static SourceMap perspective(PerspectiveTransform inverse) {
        return (row, count, xs, ys) -> {
            var cy = row + 0.5;
            var rowX = inverse.m01() * cy + inverse.m02();
            var rowY = inverse.m11() * cy + inverse.m12();
            var rowW = inverse.m21() * cy + inverse.m22();
            for (var i = 0; i < count; i++) {
                var cx = i + 0.5;
                var w = inverse.m20() * cx + rowW;
                if (w > 0) {
                    xs[i] = (inverse.m00() * cx + rowX) / w;
                    ys[i] = (inverse.m10() * cx + rowY) / w;
                } else {
                    xs[i] = Double.NaN;
                    ys[i] = Double.NaN;
                }
            }
        };
    }

    /**
     * Returns the bilinear map of a rectangle of the output onto a quadrilateral of the source.
     *
     * @param source the quadrilateral the rectangle's corners map to
     * @param left the column of the rectangle's left edge
     * @param top the row of the rectangle's top edge
     * @param width the width of the rectangle
     * @param height the height of the rectangle
     * @return the source map, which maps only the pixels inside the rectangle
     */
    static SourceMap quad(Quad source, int left, int top, int width, int height) {
        var coefficients = Bilinear.of(source, width, height);
        return (row, count, xs, ys) -> {
            Arrays.fill(xs, 0, count, Double.NaN);
            Arrays.fill(ys, 0, count, Double.NaN);
            coefficients.row(row, left, top, width, height, count, xs, ys);
        };
    }

    /**
     * Returns the map of a mesh warp: each cell maps its rectangle bilinearly onto its quadrilateral, a later
     * cell replacing an earlier one where they overlap, and pixels outside every cell sample nothing.
     *
     * @param cells the cells, in order, each filling its target rectangle
     * @return the source map
     */
    static SourceMap mesh(List<MeshCell> cells) {
        var maps = new Bilinear[cells.size()];
        for (var i = 0; i < maps.length; i++) {
            var target = cells.get(i).target();
            maps[i] = Bilinear.of(cells.get(i).source(), target.width(), target.height());
        }
        return (row, count, xs, ys) -> {
            Arrays.fill(xs, 0, count, Double.NaN);
            Arrays.fill(ys, 0, count, Double.NaN);
            for (var i = 0; i < maps.length; i++) {
                var target = cells.get(i).target();
                maps[i].row(row, target.x(), target.y(), target.width(), target.height(), count, xs, ys);
            }
        };
    }

    /**
     * The coefficients of the bilinear map of a rectangle onto a quadrilateral.
     *
     * <p>Relative to the rectangle's top-left corner, the center {@code (x, y)} of a pixel maps to
     * {@code (a0 + a1 * x + a2 * y + a3 * x * y, a4 + a5 * x + a6 * y + a7 * x * y)}, computed as Pillow's
     * {@code Transform.QUAD} computes it.
     *
     * @param a0 the column of the top-left corner
     * @param a1 the column change per unit of {@code x}
     * @param a2 the column change per unit of {@code y}
     * @param a3 the column change per unit of {@code x * y}
     * @param a4 the row of the top-left corner
     * @param a5 the row change per unit of {@code x}
     * @param a6 the row change per unit of {@code y}
     * @param a7 the row change per unit of {@code x * y}
     */
    record Bilinear(double a0, double a1, double a2, double a3, double a4, double a5, double a6, double a7) {
        /**
         * Computes the coefficients of a quadrilateral and the size of its rectangle.
         *
         * @param quad the quadrilateral
         * @param width the width of the rectangle
         * @param height the height of the rectangle
         * @return the coefficients
         */
        static Bilinear of(Quad quad, int width, int height) {
            var x0 = quad.topLeftX();
            var y0 = quad.topLeftY();
            var as = 1.0 / width;
            var at = 1.0 / height;
            return new Bilinear(
                    x0,
                    (quad.topRightX() - x0) * as,
                    (quad.bottomLeftX() - x0) * at,
                    (quad.bottomRightX() - quad.bottomLeftX() - quad.topRightX() + x0) * as * at,
                    y0,
                    (quad.topRightY() - y0) * as,
                    (quad.bottomLeftY() - y0) * at,
                    (quad.bottomRightY() - quad.bottomLeftY() - quad.topRightY() + y0) * as * at);
        }

        /**
         * Writes the source points of the pixels of one output row that lie inside a rectangle.
         *
         * @param row the output row
         * @param left the column of the rectangle's left edge
         * @param top the row of the rectangle's top edge
         * @param width the width of the rectangle
         * @param height the height of the rectangle
         * @param count the number of pixels in the output row
         * @param xs the destination of the source columns
         * @param ys the destination of the source rows
         */
        void row(int row, int left, int top, int width, int height, int count, double[] xs, double[] ys) {
            if (row < top || row >= top + height) {
                return;
            }
            var yin = row - top + 0.5;
            var from = Math.max(left, 0);
            var to = (int) Math.min((long) left + width, count);
            for (var i = from; i < to; i++) {
                var xin = i - left + 0.5;
                xs[i] = a0 + a1 * xin + a2 * yin + a3 * xin * yin;
                ys[i] = a4 + a5 * xin + a6 * yin + a7 * xin * yin;
            }
        }
    }
}
