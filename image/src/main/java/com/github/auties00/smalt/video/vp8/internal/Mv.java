/* Ports libvpx 1.17.0 (vp8/common/findnearmv.h, vp8_clamp_mv2). Copyright (c) 2010 The WebM project authors; BSD
   3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.video.vp8.internal;

/**
 * Packs VP8 motion vectors into one {@code int} and clamps them to the frame.
 *
 * <p>A packed vector holds the row component in its high 16 bits and the column component in its
 * low 16 bits, both in 1/8 pixel. Arguments are not checked; values outside the documented ranges
 * give unspecified results.
 */
final class Mv {
    /**
     * The distance a clamped vector may reach past the frame edge, 16 pixels in 1/8 pixel.
     */
    private static final int MARGIN = 16 << 3;

    private Mv() {
        throw new AssertionError();
    }

    /**
     * Packs a motion vector.
     *
     * @param row the row component in 1/8 pixel, truncated to 16 bits
     * @param col the column component in 1/8 pixel, truncated to 16 bits
     * @return the packed vector
     */
    static int pack(int row, int col) {
        return (row << 16) | (col & 0xFFFF);
    }

    /**
     * {@return the row component of a packed vector, in 1/8 pixel}
     *
     * @param mv the packed vector
     */
    static int row(int mv) {
        return mv >> 16;
    }

    /**
     * {@return the column component of a packed vector, in 1/8 pixel}
     *
     * @param mv the packed vector
     */
    static int col(int mv) {
        return (short) mv;
    }

    /**
     * Clamps a motion vector to the frame plus a 16-pixel margin ({@code vp8_clamp_mv2}).
     *
     * @param mv the packed vector
     * @param toLeft the distance from the macroblock to the left frame edge in 1/8 pixel, at most 0
     * @param toRight the distance from the macroblock to the right frame edge in 1/8 pixel, at least 0
     * @param toTop the distance from the macroblock to the top frame edge in 1/8 pixel, at most 0
     * @param toBottom the distance from the macroblock to the bottom frame edge in 1/8 pixel, at
     * least 0
     * @return the clamped packed vector
     */
    static int clamp(int mv, int toLeft, int toRight, int toTop, int toBottom) {
        var row = Math.clamp(row(mv), toTop - MARGIN, toBottom + MARGIN);
        var col = Math.clamp(col(mv), toLeft - MARGIN, toRight + MARGIN);
        return pack(row, col);
    }
}
