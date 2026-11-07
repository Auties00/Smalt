package com.github.auties00.smalt.image.ops;

/**
 * Describes an affine map of the plane, from the coordinates of a source picture to those of a
 * destination picture.
 *
 * <p>A point {@code (x, y)} maps to:
 * {@snippet :
 * mappedX = m00 * x + m01 * y + m02
 * mappedY = m10 * x + m11 * y + m12
 * }
 * Coordinates are continuous and measured in pixels from the top-left corner of the picture,
 * with {@code y} growing downwards: pixel {@code (i, j)} covers the unit square from
 * {@code (i, j)} to {@code (i + 1, j + 1)}, and its center is {@code (i + 0.5, j + 0.5)}. Because
 * {@code y} grows downwards, a positive {@link #rotation(double) rotation} turns the picture
 * clockwise on screen.
 *
 * <p>Instances are immutable and thread-safe.
 *
 * @param m00 the factor of {@code x} in the mapped column
 * @param m01 the factor of {@code y} in the mapped column
 * @param m02 the translation of the mapped column
 * @param m10 the factor of {@code x} in the mapped row
 * @param m11 the factor of {@code y} in the mapped row
 * @param m12 the translation of the mapped row
 */
public record AffineTransform(double m00, double m01, double m02, double m10, double m11, double m12) {
    /**
     * Validates the coefficients.
     *
     * @throws IllegalArgumentException if a coefficient is not finite
     */
    public AffineTransform {
        if (!Double.isFinite(m00) || !Double.isFinite(m01) || !Double.isFinite(m02)
                || !Double.isFinite(m10) || !Double.isFinite(m11) || !Double.isFinite(m12)) {
            throw new IllegalArgumentException("affine coefficients must be finite");
        }
    }

    /**
     * Returns the identity map.
     *
     * @return the transform that maps every point to itself
     */
    public static AffineTransform identity() {
        return new AffineTransform(1, 0, 0, 0, 1, 0);
    }

    /**
     * Returns a translation.
     *
     * @param dx the horizontal displacement, in pixels
     * @param dy the vertical displacement, in pixels; positive moves down
     * @return the transform
     * @throws IllegalArgumentException if a displacement is not finite
     */
    public static AffineTransform translation(double dx, double dy) {
        return new AffineTransform(1, 0, dx, 0, 1, dy);
    }

    /**
     * Returns a scaling about the origin.
     *
     * @param sx the horizontal factor; negative mirrors
     * @param sy the vertical factor; negative mirrors
     * @return the transform
     * @throws IllegalArgumentException if a factor is not finite
     */
    public static AffineTransform scaling(double sx, double sy) {
        return new AffineTransform(sx, 0, 0, 0, sy, 0);
    }

    /**
     * Returns a shear about the origin, mapping {@code (x, y)} to
     * {@code (x + shx * y, y + shy * x)}.
     *
     * @param shx the horizontal shear factor
     * @param shy the vertical shear factor
     * @return the transform
     * @throws IllegalArgumentException if a factor is not finite
     */
    public static AffineTransform shearing(double shx, double shy) {
        return new AffineTransform(1, shx, 0, shy, 1, 0);
    }

    /**
     * Returns a rotation about the origin, clockwise on screen.
     *
     * <p>Multiples of 90 degrees produce exact coefficients ({@code 0} and {@code +-1}).
     *
     * @param degrees the angle in degrees; positive turns clockwise
     * @return the transform
     * @throws IllegalArgumentException if the angle is not finite
     */
    public static AffineTransform rotation(double degrees) {
        if (!Double.isFinite(degrees)) {
            throw new IllegalArgumentException("angle " + degrees + " is not finite");
        }
        var normalized = degrees % 360;
        if (normalized < 0) {
            normalized += 360;
        }
        double cos;
        double sin;
        if (normalized == 0) {
            cos = 1;
            sin = 0;
        } else if (normalized == 90) {
            cos = 0;
            sin = 1;
        } else if (normalized == 180) {
            cos = -1;
            sin = 0;
        } else if (normalized == 270) {
            cos = 0;
            sin = -1;
        } else {
            var radians = Math.toRadians(normalized);
            cos = StrictMath.cos(radians);
            sin = StrictMath.sin(radians);
        }
        return new AffineTransform(cos, -sin, 0, sin, cos, 0);
    }

    /**
     * Returns a rotation about a point, clockwise on screen.
     *
     * @param degrees the angle in degrees; positive turns clockwise
     * @param cx      the column of the center of rotation
     * @param cy      the row of the center of rotation
     * @return the transform
     * @throws IllegalArgumentException if an argument is not finite
     */
    public static AffineTransform rotation(double degrees, double cx, double cy) {
        return translation(-cx, -cy).then(rotation(degrees)).then(translation(cx, cy));
    }

    /**
     * Returns the transform that applies this one and then {@code next}.
     *
     * @param next the transform applied to the result of this one
     * @return the composed transform
     * @throws NullPointerException     if {@code next} is {@code null}
     * @throws IllegalArgumentException if a composed coefficient overflows to infinity
     */
    public AffineTransform then(AffineTransform next) {
        return new AffineTransform(
                next.m00 * m00 + next.m01 * m10,
                next.m00 * m01 + next.m01 * m11,
                next.m00 * m02 + next.m01 * m12 + next.m02,
                next.m10 * m00 + next.m11 * m10,
                next.m10 * m01 + next.m11 * m11,
                next.m10 * m02 + next.m11 * m12 + next.m12);
    }

    /**
     * Returns the determinant of the linear part, the factor by which the map scales areas.
     *
     * @return {@code m00 * m11 - m01 * m10}
     */
    public double determinant() {
        return m00 * m11 - m01 * m10;
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
     * Returns the inverse map.
     *
     * @return the transform that undoes this one
     * @throws IllegalStateException if this map is not {@linkplain #isInvertible() invertible}
     */
    public AffineTransform inverse() {
        if (!isInvertible()) {
            throw new IllegalStateException("the affine transform " + this + " is not invertible");
        }
        var determinant = determinant();
        var i00 = m11 / determinant;
        var i01 = -m01 / determinant;
        var i10 = -m10 / determinant;
        var i11 = m00 / determinant;
        return new AffineTransform(i00, i01, -(i00 * m02 + i01 * m12), i10, i11, -(i10 * m02 + i11 * m12));
    }

    /**
     * Returns the column a point maps to.
     *
     * @param x the column of the point
     * @param y the row of the point
     * @return {@code m00 * x + m01 * y + m02}
     */
    public double mapX(double x, double y) {
        return m00 * x + m01 * y + m02;
    }

    /**
     * Returns the row a point maps to.
     *
     * @param x the column of the point
     * @param y the row of the point
     * @return {@code m10 * x + m11 * y + m12}
     */
    public double mapY(double x, double y) {
        return m10 * x + m11 * y + m12;
    }
}
