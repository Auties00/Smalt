/* Ports the Microsoft MPEG-4 Visual reference software of ISO/IEC 14496-5:2001 (version 2 FDAM1-2.3-001213,
 * type/basic.hpp and basic.cpp: CRct). Copyright 1996-2000 Microsoft Corporation and the contributors named in the
 * sources; ISO/IEC free license for products conforming to ISO/IEC 14496-2. Modified for Smalt. */
package com.github.auties00.smalt.video.mpeg4.internal.object;

/**
 * A mutable rectangle of sample positions, {@code left} and {@code top} inclusive, {@code right} and
 * {@code bottom} exclusive, with its width cached ({@code CRct}).
 */
final class Rct {
    int left;
    int top;
    int right;
    int bottom;
    int width;

    /**
     * Constructs the invalid rectangle.
     */
    Rct() {
        invalidate();
    }

    Rct(int left, int top, int right, int bottom) {
        set(left, top, right, bottom);
    }

    Rct(Rct other) {
        set(other);
    }

    void set(int left, int top, int right, int bottom) {
        this.left = left;
        this.top = top;
        this.right = right;
        this.bottom = bottom;
        this.width = right - left;
    }

    void set(Rct other) {
        left = other.left;
        top = other.top;
        right = other.right;
        bottom = other.bottom;
        width = other.width;
    }

    boolean valid() {
        return left < right && top < bottom;
    }

    boolean empty() {
        return left >= right || top >= bottom;
    }

    int height() {
        return !valid() ? 0 : bottom - top;
    }

    int area() {
        return width * height();
    }

    /**
     * {@return the index of a position in a raster of this rectangle, 0 when it is invalid}
     */
    int offset(int x, int y) {
        return !valid() ? 0 : width * (y - top) + (x - left);
    }

    boolean includes(int x, int y) {
        return x >= left && x < right && y >= top && y < bottom;
    }

    void invalidate() {
        left = 0;
        top = 0;
        right = -1;
        bottom = -1;
        width = right - left;
    }

    void expand(int dl, int dt, int dr, int db) {
        left -= dl;
        top -= dt;
        right += dr;
        bottom += db;
        width += dr + dl;
    }

    void expand(int size) {
        expand(size, size, size, size);
    }

    void shift(int dx, int dy) {
        left += dx;
        top += dy;
        right += dx;
        bottom += dy;
    }

    /**
     * Intersects this rectangle with another.
     */
    void clip(Rct rc) {
        if (empty()) {
            return;
        }
        if (rc.empty()) {
            set(rc);
        }
        if (left < rc.left) {
            left = rc.left;
        }
        if (top < rc.top) {
            top = rc.top;
        }
        if (right > rc.right) {
            right = rc.right;
        }
        if (bottom > rc.bottom) {
            bottom = rc.bottom;
        }
        width = right - left;
    }

    /**
     * Extends this rectangle to the union with another.
     */
    void include(Rct rc) {
        if (empty()) {
            set(rc);
        }
        if (rc.empty()) {
            return;
        }
        if (left > rc.left) {
            left = rc.left;
        }
        if (top > rc.top) {
            top = rc.top;
        }
        if (right < rc.right) {
            right = rc.right;
        }
        if (bottom < rc.bottom) {
            bottom = rc.bottom;
        }
        width = right - left;
    }

    Rct downSampleBy2() {
        return new Rct(left / 2, top / 2, right / 2, bottom / 2);
    }

    Rct upSampleBy2() {
        return new Rct(left * 2, top * 2, right * 2, bottom * 2);
    }

    Rct times(int scale) {
        return new Rct(left * scale, top * scale, right * scale, bottom * scale);
    }

    Rct divide(int scale) {
        var roundR = right >= 0 ? scale - 1 : 1 - scale;
        var roundB = bottom >= 0 ? scale - 1 : 1 - scale;
        return new Rct(left / scale, top / scale, (right + roundR) / scale, (bottom + roundB) / scale);
    }

    boolean sameAs(Rct rc) {
        return left == rc.left && top == rc.top && right == rc.right && bottom == rc.bottom;
    }

    /**
     * {@return whether this rectangle lies inside another ({@code operator <=})}
     */
    boolean inside(Rct rc) {
        return left >= rc.left && top >= rc.top && right <= rc.right && bottom <= rc.bottom;
    }

    @Override
    public String toString() {
        return "[" + left + "," + top + "," + right + "," + bottom + "]";
    }
}
