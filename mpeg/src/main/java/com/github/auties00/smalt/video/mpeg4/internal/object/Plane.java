/* Ports the Microsoft MPEG-4 Visual reference software of ISO/IEC 14496-5:2001 (version 2 FDAM1-2.3-001213,
 * type/grayc.hpp and grayc.cpp: CU8Image). Copyright 1996-2000 Microsoft Corporation and the contributors named in
 * the sources; ISO/IEC free license for products conforming to ISO/IEC 14496-2. Modified for Smalt. */
package com.github.auties00.smalt.video.mpeg4.internal.object;

import java.util.Arrays;

/**
 * A plane of samples over a rectangle of positions ({@code CU8Image}); sample {@code (x, y)} is at
 * {@code where.offset(x, y)} of {@link #pixels}. Samples hold up to 16 bits.
 */
final class Plane {
    int[] pixels;
    final Rct where;
    boolean[] horSamplingCheck;
    boolean[] verSamplingCheck;

    Plane(Rct rect) {
        this.where = new Rct(rect);
        this.pixels = new int[Math.max(0, rect.area())];
    }

    Plane(Rct rect, int value) {
        this(rect);
        Arrays.fill(pixels, value);
    }

    /**
     * Constructs a copy of a plane, cropped to a rectangle, or whole when the rectangle is invalid; positions outside
     * the source plane are zero.
     */
    Plane(Plane source, Rct rect) {
        if (rect == null || !rect.valid()) {
            this.where = new Rct(source.where);
            this.pixels = source.pixels.clone();
            return;
        }
        this.where = new Rct(rect);
        this.pixels = new int[rect.area()];
        var src = source.where;
        for (var y = rect.top; y < rect.bottom; y++) {
            if (y < src.top || y >= src.bottom) {
                continue;
            }
            var x0 = Math.max(rect.left, src.left);
            var x1 = Math.min(rect.right, src.right);
            if (x1 > x0) {
                System.arraycopy(source.pixels, src.offset(x0, y), pixels, where.offset(x0, y), x1 - x0);
            }
        }
    }

    int offset(int x, int y) {
        return where.offset(x, y);
    }

    int pixel(int x, int y) {
        return pixels[where.offset(x, y)];
    }

    void pixel(int x, int y, int value) {
        pixels[where.offset(x, y)] = value;
    }

    void shift(int dx, int dy) {
        where.shift(dx, dy);
    }

    /**
     * Copies the samples of another plane over this one where they overlap ({@code overlay}).
     */
    void overlay(Plane source) {
        overlay(source, source.where);
    }

    /**
     * Copies the samples of a rectangle of another plane over this one where they overlap.
     */
    void overlay(Plane source, Rct rect) {
        var r = new Rct(rect);
        r.clip(source.where);
        r.clip(where);
        if (!r.valid()) {
            return;
        }
        for (var y = r.top; y < r.bottom; y++) {
            System.arraycopy(source.pixels, source.where.offset(r.left, y), pixels, where.offset(r.left, y),
                    r.right - r.left);
        }
    }

    /**
     * Sets every sample of this plane that is opaque in another to opaque ({@code or}), over their overlap.
     */
    void or(Plane other) {
        var r = new Rct(other.where);
        r.clip(where);
        if (!r.valid()) {
            return;
        }
        for (var y = r.top; y < r.bottom; y++) {
            for (var x = r.left; x < r.right; x++) {
                if (other.pixels[other.where.offset(x, y)] != 0) {
                    pixels[where.offset(x, y)] = 255;
                }
            }
        }
    }

    /**
     * Sets this chroma-sized binary plane from a luma-sized one: a chroma sample is opaque when any of its four
     * luma samples (of its field in interlaced coding) is ({@code decimateBinaryShapeFrom}).
     */
    void decimateBinaryShapeFrom(Plane source, boolean interlace) {
        var src = source.where;
        var width = where.width;
        var height = where.height();
        var srcWidth = src.width;
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                int a;
                int b;
                int c;
                int d;
                var sx = 2 * x;
                if (!interlace) {
                    var sy = 2 * y;
                    a = source.pixels[sy * srcWidth + sx];
                    b = source.pixels[sy * srcWidth + sx + 1];
                    c = source.pixels[(sy + 1) * srcWidth + sx];
                    d = source.pixels[(sy + 1) * srcWidth + sx + 1];
                } else {
                    var field = y & 1;
                    var sy = 4 * (y >> 1) + field;
                    a = source.pixels[sy * srcWidth + sx];
                    b = source.pixels[sy * srcWidth + sx + 1];
                    c = source.pixels[(sy + 2) * srcWidth + sx];
                    d = source.pixels[(sy + 2) * srcWidth + sx + 1];
                }
                pixels[y * width + x] = (a | b | c | d) != 0 ? 255 : 0;
            }
        }
    }
}
