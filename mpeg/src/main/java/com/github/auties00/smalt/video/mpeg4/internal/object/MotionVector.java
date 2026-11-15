/* Ports the Microsoft MPEG-4 Visual reference software of ISO/IEC 14496-5:2001 (version 2 FDAM1-2.3-001213,
 * type/basic.hpp and basic.cpp: CMotionVector). Copyright 1996-2000 Microsoft Corporation and the contributors named
 * in the sources; ISO/IEC free license for products conforming to ISO/IEC 14496-2. Modified for Smalt. */
package com.github.auties00.smalt.video.mpeg4.internal.object;

/**
 * A motion vector in its two forms: whole samples plus a half-sample flag per component, and the "true" vector in
 * half samples ({@code CMotionVector}); the doubled true vector serves reduced resolution VOPs.
 */
final class MotionVector {
    /**
     * The component value marking a vector of a transparent block ({@code NOT_MV}).
     */
    static final int NOT_MV = 1 << 20;

    int mvx;
    int mvy;
    int halfX;
    int halfY;
    int trueX;
    int trueY;
    int trueX2;
    int trueY2;

    static MotionVector[] array(int count) {
        var vectors = new MotionVector[count];
        for (var i = 0; i < count; i++) {
            vectors[i] = new MotionVector();
        }
        return vectors;
    }

    static void zero(MotionVector[] vectors, int from, int count) {
        for (var i = from; i < from + count; i++) {
            vectors[i].setToZero();
        }
    }

    void setToZero() {
        mvx = 0;
        mvy = 0;
        halfX = 0;
        halfY = 0;
        trueX = 0;
        trueY = 0;
        trueX2 = 0;
        trueY2 = 0;
    }

    void set(MotionVector other) {
        mvx = other.mvx;
        mvy = other.mvy;
        halfX = other.halfX;
        halfY = other.halfY;
        trueX = other.trueX;
        trueY = other.trueY;
        trueX2 = other.trueX2;
        trueY2 = other.trueY2;
    }

    /**
     * Sets the vector from whole and half sample parts ({@code CMotionVector (ix, iy, hx, hy)}); the doubled vector
     * is left as it was.
     */
    void setParts(int x, int y, int hx, int hy) {
        mvx = x;
        mvy = y;
        halfX = hx;
        halfY = hy;
        computeTrueMV();
    }

    /**
     * Sets the vector from half samples ({@code CMotionVector (const CVector&)}); the doubled vector is left as it
     * was.
     */
    void setHalfPel(int x, int y) {
        trueX = x;
        trueY = y;
        computeMV();
    }

    boolean isZero() {
        return trueX == 0 && trueY == 0;
    }

    void computeTrueMV() {
        trueX = mvx * 2 + halfX;
        trueY = mvy * 2 + halfY;
    }

    void computeMV() {
        mvx = trueX / 2;
        mvy = trueY / 2;
        halfX = trueX - mvx * 2;
        halfY = trueY - mvy * 2;
    }

    /**
     * Doubles the vector for a reduced resolution VOP ({@code scaleup}).
     */
    void scaleUp() {
        trueX2 = trueX == 0 ? 0 : trueX > 0 ? 2 * trueX - 1 : 2 * trueX + 1;
        trueY2 = trueY == 0 ? 0 : trueY > 0 ? 2 * trueY - 1 : 2 * trueY + 1;
    }
}
