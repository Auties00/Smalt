/* Ports libaom 3.13.1 (av1/encoder/encoder_utils.c: av1_is_integer_mv; av1/encoder/hash_motion.c:
   av1_hash_is_horizontal_perfect, av1_hash_is_vertical_perfect). Copyright (c) 2016, Alliance for Open Media;
   BSD 2-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.video.av1.internal.encoder;

import com.github.auties00.smalt.image.Picture;

/**
 * Decides whether the inter frames of screen content code whole-sample motion vectors, from how
 * much of each picture repeats the previous one or is made of constant rows or columns.
 *
 * <p>Each picture is split into 8x8 luma blocks. A block counts when it equals the block at the
 * same place in the previous picture, or when every one of its rows or every one of its columns
 * holds a single value. Motion is whole-sample when at least 80% of the blocks count and either
 * every block repeats the previous picture, or the counted share averaged over the last 32
 * pictures reaches 95% and the picture holds no block that neither repeats nor is constant along
 * rows or columns.
 *
 * <p>An instance belongs to one encoder and is used by one thread at a time.
 *
 * @implNote This implementation accepts the history test when no block is left uncounted, where
 * libaom requires the uncounted blocks to number below zero, which never holds and leaves only
 * pictures that repeat the previous one entirely with whole-sample motion.
 */
final class IntegerMvDecision {
    /**
     * The side of the compared blocks ({@code FORCE_INT_MV_DECISION_BLOCK_SIZE}).
     */
    private static final int BLOCK = 8;

    /**
     * The least share of counted blocks of the picture.
     */
    private static final double THRESHOLD_CURRENT = 0.8;

    /**
     * The least share of counted blocks averaged over the recent pictures.
     */
    private static final double THRESHOLD_AVERAGE = 0.95;

    /**
     * The number of pictures the average covers.
     */
    private static final int HISTORY = 32;

    /**
     * The picture width.
     */
    private final int width;

    /**
     * The picture height.
     */
    private final int height;

    /**
     * The luma of the current picture, at the picture width as stride.
     */
    private short[] current;

    /**
     * The luma of the previous picture, or {@code null} before the second picture.
     */
    private short[] previous;

    /**
     * The counted share of the recent pictures, a ring.
     */
    private final double[] shares = new double[HISTORY];

    /**
     * The position in {@link #shares} of the next share.
     */
    private int next;

    /**
     * The number of valid entries of {@link #shares}.
     */
    private int count;

    /**
     * Creates the decision of a picture size.
     *
     * @param width the picture width
     * @param height the picture height
     */
    IntegerMvDecision(int width, int height) {
        this.width = width;
        this.height = height;
    }

    /**
     * Takes the next coded picture: it becomes the current picture and the current one the
     * previous.
     *
     * @param picture the picture, a {@code YuvPicture} or a {@code Yuv16Picture} of the decision's
     * size
     */
    void advance(Picture picture) {
        var swap = previous;
        previous = current;
        current = swap != null ? swap : new short[width * height];
        SourcePlanes.widen(picture, 0, 0, height, current, 0, width);
    }

    /**
     * Returns whether the current picture codes whole-sample motion vectors, and records its
     * counted share in the history ({@code av1_is_integer_mv}).
     *
     * @return {@code true} for whole-sample motion; {@code false} before a previous picture
     */
    boolean decide() {
        if (previous == null || width < BLOCK || height < BLOCK) {
            return false;
        }
        var total = 0;
        var matched = 0;
        var smooth = 0;
        for (var y = 0; y + BLOCK <= height; y += BLOCK) {
            for (var x = 0; x + BLOCK <= width; x += BLOCK) {
                total++;
                var off = y * width + x;
                if (equal(off)) {
                    matched++;
                } else if (horizontallyPerfect(off) || verticallyPerfect(off)) {
                    smooth++;
                }
            }
        }
        var share = (double) (matched + smooth) / total;
        shares[next] = share;
        next = (next + 1) % HISTORY;
        count = Math.min(count + 1, HISTORY);
        if (share < THRESHOLD_CURRENT) {
            return false;
        }
        if (matched == total) {
            return true;
        }
        var average = 0.0;
        for (var i = 0; i < count; i++) {
            average += shares[i];
        }
        average /= count;
        if (average < THRESHOLD_AVERAGE) {
            return false;
        }
        return total - matched - smooth == 0;
    }

    /**
     * Returns whether a block of the current picture equals the block at the same place of the
     * previous one.
     *
     * @param off the index of the block's top-left sample
     * @return {@code true} when every sample is equal
     */
    private boolean equal(int off) {
        for (var r = 0; r < BLOCK; r++) {
            var o = off + r * width;
            for (var c = 0; c < BLOCK; c++) {
                if (current[o + c] != previous[o + c]) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Returns whether every row of a block of the current picture holds a single value
     * ({@code av1_hash_is_horizontal_perfect}).
     *
     * @param off the index of the block's top-left sample
     * @return {@code true} when the rows are constant
     */
    private boolean horizontallyPerfect(int off) {
        for (var r = 0; r < BLOCK; r++) {
            var o = off + r * width;
            for (var c = 1; c < BLOCK; c++) {
                if (current[o + c] != current[o]) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Returns whether every column of a block of the current picture holds a single value
     * ({@code av1_hash_is_vertical_perfect}).
     *
     * @param off the index of the block's top-left sample
     * @return {@code true} when the columns are constant
     */
    private boolean verticallyPerfect(int off) {
        for (var c = 0; c < BLOCK; c++) {
            for (var r = 1; r < BLOCK; r++) {
                if (current[off + r * width + c] != current[off + c]) {
                    return false;
                }
            }
        }
        return true;
    }
}
