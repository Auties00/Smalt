package com.github.auties00.smalt.video.vc1.internal;

/**
 * Forms motion compensated predictions from a reference plane: the bicubic and bilinear interpolation of SMPTE ST 421
 * section 8.3.6.5, with the out-of-bounds sample replication of sections 8.3.2 and 10.3.2.
 *
 * <p>A reference is described by a plane, its stride, the row of its first line and the distance between its lines
 * (2 for a field of an interlaced frame), and the area whose samples are replicated outward. Instances are not
 * thread-safe; each holds its scratch buffers.
 */
final class MotionCompensation {
    /**
     * Interpolation with the bicubic filters, luma of quarter sample modes.
     */
    static final int BICUBIC = 0;

    /**
     * Interpolation with the bilinear filter.
     */
    static final int BILINEAR = 1;

    /**
     * The distance between rows of the scratch buffers, wide enough for a 16-sample block and its filter taps.
     */
    private static final int PATCH_STRIDE = 24;

    /**
     * The reference samples around the block, replicated where out of bounds and intensity compensated.
     */
    private final int[] patch = new int[PATCH_STRIDE * 24];

    /**
     * The vertically filtered samples of the two-dimensional bicubic filter.
     */
    private final int[] middle = new int[PATCH_STRIDE * 24];

    /**
     * The reference plane.
     */
    private byte[] plane;

    /**
     * The distance between rows of {@link #plane}.
     */
    private int stride;

    /**
     * The plane row of the reference's line 0.
     */
    private int firstRow;

    /**
     * The distance in plane rows between two lines of the reference.
     */
    private int rowStep;

    /**
     * The samples per row read before replication.
     */
    private int validWidth;

    /**
     * The plane rows read before replication.
     */
    private int validHeight;

    /**
     * Whether rows outside the plane are replicated by parity.
     */
    private boolean interlacedRows;

    /**
     * The intensity compensation of the even plane rows, or {@code null}.
     */
    private byte[] evenLut;

    /**
     * The intensity compensation of the odd plane rows, or {@code null}.
     */
    private byte[] oddLut;

    /**
     * Selects the reference plane.
     *
     * @param plane the samples
     * @param stride the distance between rows of the plane
     * @param firstRow the plane row of the reference's line 0
     * @param rowStep the distance in plane rows between two lines of the reference, 1 for a frame and 2 for a field
     * @param validWidth the samples per row read before replication
     * @param validHeight the plane rows read before replication
     * @param interlacedRows whether rows outside the plane are replicated by parity (a reference coded as interlace)
     * rather than from the top and bottom rows (a reference coded as progressive)
     */
    void reference(byte[] plane, int stride, int firstRow, int rowStep, int validWidth, int validHeight,
                   boolean interlacedRows) {
        this.plane = plane;
        this.stride = stride;
        this.firstRow = firstRow;
        this.rowStep = rowStep;
        this.validWidth = validWidth;
        this.validHeight = validHeight;
        this.interlacedRows = interlacedRows;
        this.evenLut = null;
        this.oddLut = null;
    }

    /**
     * Sets the intensity compensation applied to the samples read, by the parity of their plane row.
     *
     * @param even the table for even rows, or {@code null} for none
     * @param odd the table for odd rows, or {@code null} for none
     */
    void compensation(byte[] even, byte[] odd) {
        this.evenLut = even;
        this.oddLut = odd;
    }

    /**
     * Returns the plane index of a reference line, the line replicated from the nearest row of the same parity (an
     * interlace reference) or from the top or bottom row when it is out of bounds.
     *
     * @param line the line, possibly negative
     * @return the index of the row's first sample
     */
    private int row(int line) {
        var row = firstRow + line * rowStep;
        if (row < 0) {
            row = interlacedRows ? row & 1 : 0;
        } else if (row >= validHeight) {
            row = interlacedRows ? validHeight - 2 + ((row - validHeight) & 1) : validHeight - 1;
        }
        return row * stride;
    }

    /**
     * Reads a rectangle of reference samples into {@link #patch}, replicating the samples out of bounds and applying
     * the intensity compensation of their rows.
     *
     * @param x the left column, possibly negative
     * @param y the top line, possibly negative
     * @param columns the columns, at most {@link #PATCH_STRIDE}
     * @param rows the lines, at most 24
     */
    private void fetch(int x, int y, int columns, int rows) {
        var maxX = validWidth - 1;
        for (var r = 0; r < rows; r++) {
            var base = row(y + r);
            var lut = (base / stride & 1) == 0 ? evenLut : oddLut;
            if (lut != null) {
                var out = r * PATCH_STRIDE;
                for (var c = 0; c < columns; c++) {
                    var column = x + c;
                    column = column < 0 ? 0 : Math.min(column, maxX);
                    patch[out + c] = lut[plane[base + column] & 0xFF] & 0xFF;
                }
                continue;
            }
            var out = r * PATCH_STRIDE;
            if (x >= 0 && x + columns <= validWidth) {
                for (var c = 0; c < columns; c++) {
                    patch[out + c] = plane[base + x + c] & 0xFF;
                }
            } else {
                for (var c = 0; c < columns; c++) {
                    var column = x + c;
                    column = column < 0 ? 0 : Math.min(column, maxX);
                    patch[out + c] = plane[base + column] & 0xFF;
                }
            }
        }
    }

    /**
     * Predicts a block.
     *
     * @param x the block's left column in the reference's sampling grid
     * @param y the block's top line
     * @param width the block width, at most 16
     * @param height the block height, at most 16
     * @param mvx the horizontal motion vector, in quarter samples
     * @param mvy the vertical motion vector, in quarter samples
     * @param filter {@link #BICUBIC} or {@link #BILINEAR}
     * @param rnd the rounding control, 0 or 1
     * @param out the prediction, 0 to 255
     * @param offset the index of the block's top-left sample in {@code out}
     * @param outStride the distance between rows of {@code out}
     */
    void predict(int x, int y, int width, int height, int mvx, int mvy, int filter, int rnd, int[] out, int offset,
                 int outStride) {
        var ix = x + (mvx >> 2);
        var iy = y + (mvy >> 2);
        var fx = mvx & 3;
        var fy = mvy & 3;
        fetch(ix - 1, iy - 1, width + 3, height + 3);
        if (fx == 0 && fy == 0) {
            for (var r = 0; r < height; r++) {
                System.arraycopy(patch, (r + 1) * PATCH_STRIDE + 1, out, offset + r * outStride, width);
            }
            return;
        }
        if (filter == BILINEAR) {
            bilinear(width, height, fx, fy, rnd, out, offset, outStride);
        } else {
            bicubic(width, height, fx, fy, rnd, out, offset, outStride);
        }
    }

    /**
     * Interpolates a block with the bilinear filter of section 8.3.6.5.1 from {@link #patch}.
     *
     * @param width the block width
     * @param height the block height
     * @param fx the horizontal quarter sample fraction
     * @param fy the vertical quarter sample fraction
     * @param rnd the rounding control
     * @param out the prediction
     * @param offset the index of the block's top-left sample in {@code out}
     * @param outStride the distance between rows of {@code out}
     */
    private void bilinear(int width, int height, int fx, int fy, int rnd, int[] out, int offset, int outStride) {
        var w00 = (4 - fx) * (4 - fy);
        var w10 = fx * (4 - fy);
        var w01 = (4 - fx) * fy;
        var w11 = fx * fy;
        for (var r = 0; r < height; r++) {
            var top = (r + 1) * PATCH_STRIDE + 1;
            var bottom = top + PATCH_STRIDE;
            var o = offset + r * outStride;
            for (var c = 0; c < width; c++) {
                out[o + c] = (w00 * patch[top + c] + w10 * patch[top + c + 1] + w01 * patch[bottom + c]
                        + w11 * patch[bottom + c + 1] + 8 - rnd) >> 4;
            }
        }
    }

    /**
     * Returns one tap of the bicubic filter of a quarter sample position (section 8.3.6.5.2).
     *
     * @param fraction the position, 1 to 3
     * @param index the tap, 0 to 3
     * @return the weight
     */
    private static int tap(int fraction, int index) {
        return switch (fraction) {
            case 1 -> index == 0 ? -4 : index == 1 ? 53 : index == 2 ? 18 : -3;
            case 2 -> index == 0 || index == 3 ? -1 : 9;
            default -> index == 0 ? -3 : index == 1 ? 18 : index == 2 ? 53 : -4;
        };
    }

    /**
     * Interpolates a block with the bicubic filters of section 8.3.6.5.2 from {@link #patch}: one-dimensional when one
     * fraction is zero, the vertical pass then the horizontal pass otherwise.
     *
     * @param width the block width
     * @param height the block height
     * @param fx the horizontal quarter sample fraction
     * @param fy the vertical quarter sample fraction
     * @param rnd the rounding control
     * @param out the prediction
     * @param offset the index of the block's top-left sample in {@code out}
     * @param outStride the distance between rows of {@code out}
     */
    private void bicubic(int width, int height, int fx, int fy, int rnd, int[] out, int offset, int outStride) {
        if (fy != 0 && fx == 0) {
            var t0 = tap(fy, 0);
            var t1 = tap(fy, 1);
            var t2 = tap(fy, 2);
            var t3 = tap(fy, 3);
            var shift = fy == 2 ? 4 : 6;
            var add = (1 << (shift - 1)) - (1 - rnd);
            for (var r = 0; r < height; r++) {
                var p = r * PATCH_STRIDE + 1;
                var o = offset + r * outStride;
                for (var c = 0; c < width; c++) {
                    var s = t0 * patch[p + c] + t1 * patch[p + c + PATCH_STRIDE] + t2 * patch[p + c + 2 * PATCH_STRIDE]
                            + t3 * patch[p + c + 3 * PATCH_STRIDE];
                    out[o + c] = Frame.clip((s + add) >> shift);
                }
            }
            return;
        }
        if (fy == 0) {
            var t0 = tap(fx, 0);
            var t1 = tap(fx, 1);
            var t2 = tap(fx, 2);
            var t3 = tap(fx, 3);
            var shift = fx == 2 ? 4 : 6;
            var add = (1 << (shift - 1)) - rnd;
            for (var r = 0; r < height; r++) {
                var p = (r + 1) * PATCH_STRIDE;
                var o = offset + r * outStride;
                for (var c = 0; c < width; c++) {
                    var s = t0 * patch[p + c] + t1 * patch[p + c + 1] + t2 * patch[p + c + 2] + t3 * patch[p + c + 3];
                    out[o + c] = Frame.clip((s + add) >> shift);
                }
            }
            return;
        }
        var v0 = tap(fy, 0);
        var v1 = tap(fy, 1);
        var v2 = tap(fy, 2);
        var v3 = tap(fy, 3);
        var shiftV = (fy == 2 ? 4 : 6) + (fx == 2 ? 4 : 6) - 7;
        var addV = (1 << (shiftV - 1)) - 1 + rnd;
        for (var r = 0; r < height; r++) {
            var p = r * PATCH_STRIDE;
            var m = r * PATCH_STRIDE;
            for (var c = 0; c < width + 3; c++) {
                var s = v0 * patch[p + c] + v1 * patch[p + c + PATCH_STRIDE] + v2 * patch[p + c + 2 * PATCH_STRIDE]
                        + v3 * patch[p + c + 3 * PATCH_STRIDE];
                middle[m + c] = (s + addV) >> shiftV;
            }
        }
        var h0 = tap(fx, 0);
        var h1 = tap(fx, 1);
        var h2 = tap(fx, 2);
        var h3 = tap(fx, 3);
        for (var r = 0; r < height; r++) {
            var m = r * PATCH_STRIDE;
            var o = offset + r * outStride;
            for (var c = 0; c < width; c++) {
                var s = h0 * middle[m + c] + h1 * middle[m + c + 1] + h2 * middle[m + c + 2] + h3 * middle[m + c + 3];
                out[o + c] = Frame.clip((s + 64 - rnd) >> 7);
            }
        }
    }
}
