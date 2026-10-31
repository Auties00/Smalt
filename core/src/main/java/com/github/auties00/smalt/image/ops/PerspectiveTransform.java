package com.github.auties00.smalt.image.ops;

/**
 * Describes a projective map of the plane (a homography), from the coordinates of a source picture
 * to those of a destination picture.
 *
 * <p>A point {@code (x, y)} maps to:
 * {@snippet :
 * w = m20 * x + m21 * y + m22
 * mappedX = (m00 * x + m01 * y + m02) / w
 * mappedY = (m10 * x + m11 * y + m12) / w
 * }
 * Coordinates are those of {@link AffineTransform}: continuous, in pixels from the top-left corner,
 * {@code y} growing downwards, pixel {@code (i, j)} centered at {@code (i + 0.5, j + 0.5)}. Points where
 * {@code w} is zero map to infinity, and points where it is negative lie behind the horizon of the map; a
 * transform keeps straight lines straight, and with {@code m20 = m21 = 0} it is affine. Multiplying every
 * coefficient by the same non-zero factor gives the same map but an unequal record.
 *
 * <p>Instances are immutable and thread-safe.
 *
 * @param m00 the factor of {@code x} in the numerator of the mapped column
 * @param m01 the factor of {@code y} in the numerator of the mapped column
 * @param m02 the constant of the numerator of the mapped column
 * @param m10 the factor of {@code x} in the numerator of the mapped row
 * @param m11 the factor of {@code y} in the numerator of the mapped row
 * @param m12 the constant of the numerator of the mapped row
 * @param m20 the factor of {@code x} in the common denominator
 * @param m21 the factor of {@code y} in the common denominator
 * @param m22 the constant of the common denominator
 */
public record PerspectiveTransform(double m00, double m01, double m02, double m10, double m11, double m12,
                                   double m20, double m21, double m22) {
    /**
     * Validates the coefficients.
     *
     * @throws IllegalArgumentException if a coefficient is not finite
     */
    public PerspectiveTransform {
        if (!Double.isFinite(m00) || !Double.isFinite(m01) || !Double.isFinite(m02)
                || !Double.isFinite(m10) || !Double.isFinite(m11) || !Double.isFinite(m12)
                || !Double.isFinite(m20) || !Double.isFinite(m21) || !Double.isFinite(m22)) {
            throw new IllegalArgumentException("perspective coefficients must be finite");
        }
    }

    /**
     * Returns the identity map.
     *
     * @return the transform that maps every point to itself
     */
    public static PerspectiveTransform identity() {
        return new PerspectiveTransform(1, 0, 0, 0, 1, 0, 0, 0, 1);
    }

    /**
     * Returns the projective form of an affine map.
     *
     * @param transform the affine map
     * @return the transform with the same coefficients and the denominator {@code 1}
     */
    public static PerspectiveTransform of(AffineTransform transform) {
        return new PerspectiveTransform(transform.m00(), transform.m01(), transform.m02(), transform.m10(),
                transform.m11(), transform.m12(), 0, 0, 1);
    }

    /**
     * Returns the map of the eight coefficients of a normalized homography, whose denominator constant is
     * {@code 1}.
     *
     * @apiNote The coefficients are in the order of Pillow's {@code Image.transform} with
     * {@code Transform.PERSPECTIVE}, whose data maps the result to the source: pass that map's
     * {@link #inverse()} to {@link ImageOps#perspective}.
     * @param a the column factors and constant {@code m00, m01, m02}, then {@code m10, m11, m12}, then the
     * denominator factors {@code m20, m21}
     * @return the transform with {@code m22 = 1}
     * @throws IllegalArgumentException if {@code a} does not hold exactly eight finite values
     */
    public static PerspectiveTransform ofCoefficients(double... a) {
        if (a.length != 8) {
            throw new IllegalArgumentException("a normalized homography has 8 coefficients, not " + a.length);
        }
        return new PerspectiveTransform(a[0], a[1], a[2], a[3], a[4], a[5], a[6], a[7], 1);
    }

    /**
     * Returns the map that takes the corners of one quadrilateral to the corners of another, each corner to the
     * corner of the same name.
     *
     * <p>The map is unique when no three corners of either quadrilateral lie on one line; it maps the edges of
     * {@code from} onto the edges of {@code to}, and the result is normalized so that {@code m22} is {@code 1}
     * when that is possible.
     *
     * @apiNote Straightening a photographed document is {@code mapping(corners, Quad.of(0, 0, width, height))}
     * passed to {@link ImageOps#perspective} with a result of {@code width} by {@code height}.
     * @param from the corners in source coordinates
     * @param to the corners they map to
     * @return the transform
     * @throws IllegalArgumentException if three corners of a quadrilateral are collinear, so that no unique
     * map exists
     */
    public static PerspectiveTransform mapping(Quad from, Quad to) {
        var unit = squareTo(to);
        var inverse = squareTo(from);
        if (unit == null || inverse == null) {
            throw new IllegalArgumentException("three corners of " + (unit == null ? to : from) + " are collinear");
        }
        var map = inverse.inverse().then(unit);
        return map.m22 != 0 ? map.scaled(1 / map.m22) : map;
    }

    /**
     * Returns the map from the unit square to a quadrilateral, {@code (0, 0)} to its top-left corner,
     * {@code (1, 0)} to its top-right, {@code (1, 1)} to its bottom-right and {@code (0, 1)} to its bottom-left
     * corner (Heckbert, "Fundamentals of Texture Mapping and Image Warping", 1989).
     *
     * @param quad the quadrilateral
     * @return the transform, or {@code null} when three corners are collinear
     */
    private static PerspectiveTransform squareTo(Quad quad) {
        var x0 = quad.topLeftX();
        var y0 = quad.topLeftY();
        var x1 = quad.topRightX();
        var y1 = quad.topRightY();
        var x2 = quad.bottomRightX();
        var y2 = quad.bottomRightY();
        var x3 = quad.bottomLeftX();
        var y3 = quad.bottomLeftY();
        var sx = x0 - x1 + x2 - x3;
        var sy = y0 - y1 + y2 - y3;
        var dx1 = x1 - x2;
        var dx2 = x3 - x2;
        var dy1 = y1 - y2;
        var dy2 = y3 - y2;
        var denominator = dx1 * dy2 - dx2 * dy1;
        var g = (sx * dy2 - dx2 * sy) / denominator;
        var h = (dx1 * sy - sx * dy1) / denominator;
        var map = new double[]{x1 - x0 + g * x1, x3 - x0 + h * x3, x0, y1 - y0 + g * y1, y3 - y0 + h * y3, y0, g, h};
        for (var value : map) {
            if (!Double.isFinite(value)) {
                return null;
            }
        }
        var transform = ofCoefficients(map);
        return transform.isInvertible() ? transform : null;
    }

    /**
     * Returns the transform that applies this one and then {@code next}.
     *
     * @param next the transform applied to the result of this one
     * @return the composed transform, the matrix product {@code next * this}
     * @throws IllegalArgumentException if a composed coefficient overflows to infinity
     */
    public PerspectiveTransform then(PerspectiveTransform next) {
        return new PerspectiveTransform(
                next.m00 * m00 + next.m01 * m10 + next.m02 * m20,
                next.m00 * m01 + next.m01 * m11 + next.m02 * m21,
                next.m00 * m02 + next.m01 * m12 + next.m02 * m22,
                next.m10 * m00 + next.m11 * m10 + next.m12 * m20,
                next.m10 * m01 + next.m11 * m11 + next.m12 * m21,
                next.m10 * m02 + next.m11 * m12 + next.m12 * m22,
                next.m20 * m00 + next.m21 * m10 + next.m22 * m20,
                next.m20 * m01 + next.m21 * m11 + next.m22 * m21,
                next.m20 * m02 + next.m21 * m12 + next.m22 * m22);
    }

    /**
     * Returns the determinant of the matrix of the coefficients.
     *
     * @return the determinant; zero for a degenerate map
     */
    public double determinant() {
        return m00 * (m11 * m22 - m12 * m21) - m01 * (m10 * m22 - m12 * m20) + m02 * (m10 * m21 - m11 * m20);
    }

    /**
     * Returns whether this map has an inverse.
     *
     * @return whether the determinant is a non-zero finite number with a finite reciprocal
     */
    public boolean isInvertible() {
        var determinant = determinant();
        return determinant != 0 && Double.isFinite(determinant) && Double.isFinite(1 / determinant);
    }

    /**
     * Returns the inverse map, normalized so that {@code m22} is {@code 1} when that is possible.
     *
     * @return the transform that undoes this one
     * @throws IllegalStateException if this map is not {@linkplain #isInvertible() invertible}
     */
    public PerspectiveTransform inverse() {
        if (!isInvertible()) {
            throw new IllegalStateException("the perspective transform " + this + " is not invertible");
        }
        var adjugate = new PerspectiveTransform(
                m11 * m22 - m12 * m21, m02 * m21 - m01 * m22, m01 * m12 - m02 * m11,
                m12 * m20 - m10 * m22, m00 * m22 - m02 * m20, m02 * m10 - m00 * m12,
                m10 * m21 - m11 * m20, m01 * m20 - m00 * m21, m00 * m11 - m01 * m10);
        var scale = adjugate.m22 != 0 ? adjugate.m22 : determinant();
        return adjugate.scaled(1 / scale);
    }

    /**
     * Returns the column a point maps to.
     *
     * @param x the column of the point
     * @param y the row of the point
     * @return {@code (m00 * x + m01 * y + m02) / (m20 * x + m21 * y + m22)}, infinite or NaN where the
     * denominator is zero
     */
    public double mapX(double x, double y) {
        return (m00 * x + m01 * y + m02) / (m20 * x + m21 * y + m22);
    }

    /**
     * Returns the row a point maps to.
     *
     * @param x the column of the point
     * @param y the row of the point
     * @return {@code (m10 * x + m11 * y + m12) / (m20 * x + m21 * y + m22)}, infinite or NaN where the
     * denominator is zero
     */
    public double mapY(double x, double y) {
        return (m10 * x + m11 * y + m12) / (m20 * x + m21 * y + m22);
    }

    /**
     * Returns this transform with every coefficient multiplied by a factor, the same map.
     *
     * @param factor the non-zero factor
     * @return the scaled transform
     */
    private PerspectiveTransform scaled(double factor) {
        return new PerspectiveTransform(m00 * factor, m01 * factor, m02 * factor, m10 * factor, m11 * factor,
                m12 * factor, m20 * factor, m21 * factor, m22 * factor);
    }
}
