/* Ports the Microsoft MPEG-4 Visual reference software of ISO/IEC 14496-5:2001 (version 2 FDAM1-2.3-001213,
 * type/yuvac.hpp and yuvac.cpp: CVOPU8YUVBA). Copyright 1996-2000 Microsoft Corporation and the contributors named
 * in the sources; ISO/IEC free license for products conforming to ISO/IEC 14496-2. Modified for Smalt. */
package com.github.auties00.smalt.video.mpeg4.internal.object;

/**
 * The planes of one video object plane ({@code CVOPU8YUVBA}): 4:2:0 Y'CbCr, the binary shape at luma and chroma
 * resolution, and the gray-level alpha planes, over a common luma rectangle, with a bounding rectangle inside it.
 */
final class VopPlanes {
    /**
     * Rectangular VOPs ({@code RECTANGLE}).
     */
    static final int RECTANGLE = 0;

    /**
     * Binary shape ({@code ONE_BIT}).
     */
    static final int ONE_BIT = 1;

    /**
     * Gray-level shape ({@code EIGHT_BIT}).
     */
    static final int EIGHT_BIT = 2;

    final int alphaUsage;
    final int auxCount;
    Plane y;
    Plane u;
    Plane v;
    Plane by;
    Plane buv;
    Plane[] a;
    final Rct whereY;
    final Rct whereUV;
    final Rct boundY = new Rct();
    final Rct boundUV = new Rct();

    /**
     * Constructs zeroed planes over a luma rectangle; the shape planes start transparent.
     */
    VopPlanes(int alphaUsage, Rct rect, int auxCount) {
        this.alphaUsage = alphaUsage;
        this.auxCount = auxCount;
        this.whereY = new Rct(rect);
        this.whereUV = whereY.downSampleBy2();
        y = new Plane(whereY);
        u = new Plane(whereUV);
        v = new Plane(whereUV);
        if (alphaUsage != RECTANGLE) {
            by = new Plane(whereY);
            buv = new Plane(whereUV);
            if (alphaUsage == EIGHT_BIT) {
                a = new Plane[auxCount];
                for (var i = 0; i < auxCount; i++) {
                    a[i] = new Plane(whereY);
                }
            }
        }
        if (a == null) {
            a = new Plane[0];
        }
    }

    /**
     * Constructs a copy of other planes, cropped to a rectangle, or whole when it is {@code null}.
     */
    VopPlanes(VopPlanes source, int alphaUsage, Rct rect) {
        this.alphaUsage = alphaUsage;
        this.auxCount = source.auxCount;
        if (rect != null && rect.valid()) {
            whereY = new Rct(rect);
            whereUV = whereY.downSampleBy2();
        } else {
            whereY = new Rct(source.whereY);
            whereUV = new Rct(source.whereUV);
        }
        var r = rect != null && rect.valid() ? whereY : null;
        var ruv = rect != null && rect.valid() ? whereUV : null;
        y = new Plane(source.y, r);
        u = new Plane(source.u, ruv);
        v = new Plane(source.v, ruv);
        if (alphaUsage != RECTANGLE) {
            by = new Plane(source.by, r);
            buv = new Plane(source.buv, ruv);
            if (alphaUsage == EIGHT_BIT) {
                a = new Plane[auxCount];
                for (var i = 0; i < auxCount; i++) {
                    a[i] = new Plane(source.a[i], r);
                }
            }
        }
        if (a == null) {
            a = new Plane[0];
        }
    }

    /**
     * Sets the bounding rectangle, which lies inside the planes ({@code setBoundRct}).
     */
    void setBoundRct(Rct rect) {
        boundY.set(rect);
        boundUV.set(boundY.downSampleBy2());
    }

    void shift(int left, int top) {
        whereY.shift(left, top);
        whereUV.shift(left / 2, top / 2);
        y.shift(left, top);
        u.shift(left / 2, top / 2);
        v.shift(left / 2, top / 2);
        if (alphaUsage == EIGHT_BIT) {
            for (var plane : a) {
                plane.shift(left, top);
            }
        } else if (alphaUsage == ONE_BIT) {
            by.shift(left, top);
            buv.shift(left / 2, top / 2);
        }
    }

    /**
     * Copies the planes of another VOP over these where they overlap ({@code overlay}).
     */
    void overlay(VopPlanes source) {
        if (by != null && source.by != null) {
            by.overlay(source.by);
        }
        if (buv != null && source.buv != null) {
            buv.overlay(source.buv);
        }
        y.overlay(source.y);
        u.overlay(source.u);
        v.overlay(source.v);
        if (alphaUsage == EIGHT_BIT) {
            for (var i = 0; i < auxCount; i++) {
                a[i].overlay(source.a[i]);
            }
        }
    }

    /**
     * Copies a luma rectangle of the planes of another VOP over these.
     */
    void overlay(VopPlanes source, Rct rectY) {
        if (!rectY.valid()) {
            return;
        }
        var rectUV = rectY.downSampleBy2();
        by.overlay(source.by, rectY);
        buv.overlay(source.buv, rectUV);
        y.overlay(source.y, rectY);
        u.overlay(source.u, rectUV);
        v.overlay(source.v, rectUV);
        if (alphaUsage == EIGHT_BIT) {
            for (var i = 0; i < auxCount; i++) {
                a[i].overlay(source.a[i], rectY);
            }
        }
    }
}
