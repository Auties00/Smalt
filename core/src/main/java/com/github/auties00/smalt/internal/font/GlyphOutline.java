package com.github.auties00.smalt.internal.font;

/**
 * Holds the contours of a glyph as TrueType stores them: closed loops of points, each on or off
 * the curve, two off-curve points in a row implying an on-curve point midway between them.
 *
 * <p>Coordinates are in design units with y up. Instances are not copied defensively: they are
 * created and read only by this package and its callers, which do not modify the arrays.
 *
 * @param xs the x coordinates
 * @param ys the y coordinates
 * @param onCurve whether each point lies on the curve
 * @param ends the index after the last point of each contour, increasing
 */
public record GlyphOutline(double[] xs, double[] ys, boolean[] onCurve, int[] ends) {
    /**
     * {@return whether the glyph has no contours, as a space has none}
     */
    public boolean isEmpty() {
        return ends.length == 0;
    }

    /**
     * Calls a consumer with the line segments that approximate the outline after an affine mapping.
     *
     * <p>Each quadratic segment is split into enough lines that the approximation stays within
     * {@code tolerance} of the curve.
     *
     * @param scale the factor from design units to pixels
     * @param shear the slant: x grows by {@code shear * y} before scaling (0 for upright text)
     * @param originX the pixel column of the glyph origin
     * @param originY the pixel row of the baseline; pixel rows grow downwards
     * @param tolerance the largest distance from the curve to its lines, in pixels, positive
     * @param lines receives each line from {@code (x0, y0)} to {@code (x1, y1)} in pixels
     */
    public void flatten(double scale, double shear, double originX, double originY, double tolerance, LineSink lines) {
        var first = 0;
        for (var end : ends) {
            var count = end - first;
            if (count < 2) {
                first = end;
                continue;
            }
            var start = -1;
            for (var index = first; index < end; index++) {
                if (onCurve[index]) {
                    start = index;
                    break;
                }
            }
            double startX;
            double startY;
            int following;
            int offset;
            if (start >= 0) {
                startX = px(start, scale, shear, originX);
                startY = py(start, scale, originY);
                following = count - 1;
                offset = start - first + 1;
            } else {
                startX = (px(first, scale, shear, originX) + px(end - 1, scale, shear, originX)) / 2;
                startY = (py(first, scale, originY) + py(end - 1, scale, originY)) / 2;
                following = count;
                offset = 0;
            }
            var currentX = startX;
            var currentY = startY;
            var controlX = 0.0;
            var controlY = 0.0;
            var control = false;
            for (var step = 0; step < following; step++) {
                var index = first + (offset + step) % count;
                var x = px(index, scale, shear, originX);
                var y = py(index, scale, originY);
                if (onCurve[index]) {
                    if (control) {
                        quadratic(currentX, currentY, controlX, controlY, x, y, tolerance, lines);
                    } else {
                        lines.line(currentX, currentY, x, y);
                    }
                    control = false;
                    currentX = x;
                    currentY = y;
                } else {
                    if (control) {
                        var midX = (controlX + x) / 2;
                        var midY = (controlY + y) / 2;
                        quadratic(currentX, currentY, controlX, controlY, midX, midY, tolerance, lines);
                        currentX = midX;
                        currentY = midY;
                    }
                    controlX = x;
                    controlY = y;
                    control = true;
                }
            }
            if (control) {
                quadratic(currentX, currentY, controlX, controlY, startX, startY, tolerance, lines);
            } else if (currentX != startX || currentY != startY) {
                lines.line(currentX, currentY, startX, startY);
            }
            first = end;
        }
    }

    /**
     * {@return the pixel column of a point}
     *
     * @param index the point
     * @param scale the factor from design units to pixels
     * @param shear the slant
     * @param originX the pixel column of the origin
     */
    private double px(int index, double scale, double shear, double originX) {
        return originX + (xs[index] + shear * ys[index]) * scale;
    }

    /**
     * {@return the pixel row of a point}
     *
     * @param index the point
     * @param scale the factor from design units to pixels
     * @param originY the pixel row of the baseline
     */
    private double py(int index, double scale, double originY) {
        return originY - ys[index] * scale;
    }

    /**
     * Splits a quadratic segment into lines.
     *
     * @param x0 the start column
     * @param y0 the start row
     * @param cx the control column
     * @param cy the control row
     * @param x1 the end column
     * @param y1 the end row
     * @param tolerance the largest distance from the curve to its lines
     * @param lines receives the lines
     */
    private static void quadratic(double x0, double y0, double cx, double cy, double x1, double y1, double tolerance,
                                  LineSink lines) {
        // the distance from a quadratic to its chord is at most a quarter of the control point's deviation
        var deviation = StrictMath.hypot(x0 - 2 * cx + x1, y0 - 2 * cy + y1) / 4;
        var segments = (int) Math.clamp(Math.ceil(Math.sqrt(deviation / tolerance)), 1, 64);
        var previousX = x0;
        var previousY = y0;
        for (var index = 1; index <= segments; index++) {
            var t = (double) index / segments;
            var u = 1 - t;
            var x = u * u * x0 + 2 * u * t * cx + t * t * x1;
            var y = u * u * y0 + 2 * u * t * cy + t * t * y1;
            lines.line(previousX, previousY, x, y);
            previousX = x;
            previousY = y;
        }
    }

    /**
     * Receives the line segments of a flattened outline.
     */
    @FunctionalInterface
    public interface LineSink {
        /**
         * Receives one line.
         *
         * @param x0 the start column
         * @param y0 the start row
         * @param x1 the end column
         * @param y1 the end row
         */
        void line(double x0, double y0, double x1, double y1);
    }
}
