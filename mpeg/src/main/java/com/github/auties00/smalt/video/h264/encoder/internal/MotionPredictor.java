package com.github.auties00.smalt.video.h264.encoder.internal;

import com.github.auties00.smalt.video.h264.internal.H264Tables;

/**
 * Derives motion vector predictors of P macroblocks (clause 8.4.1.3) and the motion of
 * {@code P_Skip} (clause 8.4.1.1).
 *
 * <p>Neighboring partitions are located at the 4x4 block level: {@code A} left of the
 * partition's top-left block, {@code B} above it, {@code C} above-right of its top-right block
 * (replaced by {@code D}, above-left, when unavailable). Blocks of the current macroblock that
 * precede the partition in decoding order are read from the candidate {@link Macroblock}; blocks
 * of neighboring macroblocks from the {@link PictureInfo}. Predictors are returned packed as
 * {@code (mvx << 16) | (mvy & 0xFFFF)}.
 */
final class MotionPredictor {
    /**
     * A partition of any shape predicted by the median rule.
     */
    static final int SHAPE_MEDIAN = 0;

    /**
     * A 16x8 partition.
     */
    static final int SHAPE_16x8 = 1;

    /**
     * An 8x16 partition.
     */
    static final int SHAPE_8x16 = 2;

    /**
     * The picture tables.
     */
    private PictureInfo pi;

    /**
     * The current macroblock's position and neighbors.
     */
    private MbContext ctx;

    /**
     * Whether the last looked-up neighbor is available.
     */
    private boolean available;

    /**
     * The reference index of the last looked-up neighbor, {@code -1} when unavailable or intra.
     */
    private int ref;

    /**
     * The horizontal motion vector of the last looked-up neighbor.
     */
    private int mvx;

    /**
     * The vertical motion vector of the last looked-up neighbor.
     */
    private int mvy;

    /**
     * The reference list the predictor reads: {@code 0} or {@code 1}.
     */
    private int list;

    /**
     * The reference index of the last {@link #directMotion} call.
     */
    private int directRef;

    /**
     * Constructs a predictor.
     */
    MotionPredictor() {
    }

    /**
     * Selects the reference list whose neighboring vectors predict.
     *
     * @param list {@code 0} or {@code 1}; only B slices use list 1
     */
    void setList(int list) {
        this.list = list;
    }

    /**
     * Derives the reference index and the predicted vector of spatial direct prediction for the
     * selected list in one pass over the neighbors A, B and C of the macroblock.
     *
     * <p>The reference index is available from {@link #directRef()} afterwards; it is {@code -1}
     * when no neighbor uses the list, in which case the returned vector is {@code 0}.
     *
     * @param mb the macroblock being decided
     * @return the median vector prediction of a 16x16 partition with that reference index
     */
    int directMotion(Macroblock mb) {
        lookup(mb, -1, 0, 0);
        var availA = available;
        var refA = ref;
        var mvA = pack(mvx, mvy);
        lookup(mb, 0, -1, 0);
        var availB = available;
        var refB = ref;
        var mvB = pack(mvx, mvy);
        lookup(mb, 4, -1, 0);
        if (!available) {
            lookup(mb, -1, -1, 0);
        }
        var availC = available;
        var refC = ref;
        var mvC = pack(mvx, mvy);
        var refIdx = minPositive(refA, minPositive(refB, refC));
        directRef = refIdx;
        if (refIdx < 0) {
            return 0;
        }
        if (!availB && !availC && availA) {
            return mvA;
        }
        var matchA = refA == refIdx;
        var matchB = refB == refIdx;
        var matchC = refC == refIdx;
        if (matchA && !matchB && !matchC) {
            return mvA;
        }
        if (!matchA && matchB && !matchC) {
            return mvB;
        }
        if (!matchA && !matchB && matchC) {
            return mvC;
        }
        return pack(median(x(mvA), x(mvB), x(mvC)), median(y(mvA), y(mvB), y(mvC)));
    }

    /**
     * Returns the reference index {@link #directMotion} derived.
     *
     * @return the reference index, or {@code -1}
     */
    int directRef() {
        return directRef;
    }

    /**
     * Returns the smaller of two reference indices when both are non-negative, else the larger.
     *
     * @param x the first index
     * @param y the second index
     * @return the minimum positive index, or {@code -1}
     */
    static int minPositive(int x, int y) {
        return x >= 0 && y >= 0 ? Math.min(x, y) : Math.max(x, y);
    }

    /**
     * Binds the predictor to the current picture and macroblock position.
     *
     * @param pi the picture tables
     * @param ctx the current macroblock's position and neighbors
     */
    void bind(PictureInfo pi, MbContext ctx) {
        this.pi = pi;
        this.ctx = ctx;
    }

    /**
     * Packs a motion vector.
     *
     * @param x the horizontal component
     * @param y the vertical component
     * @return the packed vector
     */
    static int pack(int x, int y) {
        return (x << 16) | (y & 0xFFFF);
    }

    /**
     * Returns the horizontal component of a packed vector.
     *
     * @param packed the packed vector
     * @return the horizontal component
     */
    static int x(int packed) {
        return packed >> 16;
    }

    /**
     * Returns the vertical component of a packed vector.
     *
     * @param packed the packed vector
     * @return the vertical component
     */
    static int y(int packed) {
        return (short) packed;
    }

    /**
     * Returns the motion vector of {@code P_Skip} (clause 8.4.1.1).
     *
     * @param mb a scratch macroblock; its motion is not read
     * @return the packed skip vector
     */
    int skipVector(Macroblock mb) {
        if (!ctx.availA || !ctx.availB) {
            return 0;
        }
        lookup(mb, -1, 0, 0);
        if (ref == 0 && mvx == 0 && mvy == 0) {
            return 0;
        }
        lookup(mb, 0, -1, 0);
        if (ref == 0 && mvx == 0 && mvy == 0) {
            return 0;
        }
        return predict(mb, 0, 0, 4, 0, SHAPE_MEDIAN);
    }

    /**
     * Returns the motion vector predictor of a partition.
     *
     * @param mb the current macroblock, holding the motion of earlier partitions
     * @param x4 the partition's left column in 4x4 units
     * @param y4 the partition's top row in 4x4 units
     * @param w4 the partition width in 4x4 units
     * @param refIdx the partition's reference index
     * @param shape {@link #SHAPE_16x8}, {@link #SHAPE_8x16} or {@link #SHAPE_MEDIAN}
     * @return the packed predictor
     */
    int predict(Macroblock mb, int x4, int y4, int w4, int refIdx, int shape) {
        var firstBlock = H264Tables.BLOCK_RASTER[y4 * 4 + x4];
        lookup(mb, x4 - 1, y4, firstBlock);
        var availA = available;
        var refA = ref;
        var mvA = pack(mvx, mvy);
        lookup(mb, x4, y4 - 1, firstBlock);
        var availB = available;
        var refB = ref;
        var mvB = pack(mvx, mvy);
        lookup(mb, x4 + w4, y4 - 1, firstBlock);
        if (!available) {
            lookup(mb, x4 - 1, y4 - 1, firstBlock);
        }
        var availC = available;
        var refC = ref;
        var mvC = pack(mvx, mvy);
        if (shape == SHAPE_16x8) {
            if (y4 == 0 && refB == refIdx) {
                return mvB;
            }
            if (y4 != 0 && refA == refIdx) {
                return mvA;
            }
        } else if (shape == SHAPE_8x16) {
            if (x4 == 0 && refA == refIdx) {
                return mvA;
            }
            if (x4 != 0 && refC == refIdx) {
                return mvC;
            }
        }
        if (!availB && !availC && availA) {
            return mvA;
        }
        var matchA = refA == refIdx;
        var matchB = refB == refIdx;
        var matchC = refC == refIdx;
        if (matchA && !matchB && !matchC) {
            return mvA;
        }
        if (!matchA && matchB && !matchC) {
            return mvB;
        }
        if (!matchA && !matchB && matchC) {
            return mvC;
        }
        return pack(median(x(mvA), x(mvB), x(mvC)), median(y(mvA), y(mvB), y(mvC)));
    }

    /**
     * Returns the median of three values.
     *
     * @param a the first value
     * @param b the second value
     * @param c the third value
     * @return the median
     */
    static int median(int a, int b, int c) {
        return Math.max(Math.min(a, b), Math.min(Math.max(a, b), c));
    }

    /**
     * Looks up the motion of the 4x4 block at a position relative to the current macroblock and
     * stores it in {@link #available}, {@link #ref}, {@link #mvx} and {@link #mvy}.
     *
     * @param mb the current macroblock
     * @param bx the block column, from -1 to 4
     * @param by the block row, from -1 to 3
     * @param firstBlock the {@code luma4x4BlkIdx} of the current partition's top-left block;
     * blocks of the current macroblock at or after it are unavailable
     */
    private void lookup(Macroblock mb, int bx, int by, int firstBlock) {
        int address;
        int index;
        if (by >= 0) {
            if (bx >= 4) {
                unavailable();
                return;
            }
            if (bx >= 0) {
                var blk = H264Tables.BLOCK_RASTER[by * 4 + bx];
                if (blk >= firstBlock) {
                    unavailable();
                    return;
                }
                available = true;
                var raster = by * 4 + bx;
                if (list == 0) {
                    ref = mb.refs[(by >> 1) * 2 + (bx >> 1)];
                    mvx = mb.mvX[raster];
                    mvy = mb.mvY[raster];
                } else {
                    ref = mb.refs1[(by >> 1) * 2 + (bx >> 1)];
                    mvx = mb.mvX1[raster];
                    mvy = mb.mvY1[raster];
                }
                return;
            }
            if (!ctx.availA) {
                unavailable();
                return;
            }
            address = ctx.addrA;
            index = by * 4 + 3;
        } else if (bx < 0) {
            if (!ctx.availD) {
                unavailable();
                return;
            }
            address = ctx.addrD;
            index = 15;
        } else if (bx < 4) {
            if (!ctx.availB) {
                unavailable();
                return;
            }
            address = ctx.addrB;
            index = 12 + bx;
        } else {
            if (!ctx.availC) {
                unavailable();
                return;
            }
            address = ctx.addrC;
            index = 12;
        }
        available = true;
        if (MbType.isIntra(pi.type[address])) {
            ref = -1;
            mvx = 0;
            mvy = 0;
            return;
        }
        if (list == 0) {
            ref = pi.refs[address * 4 + (index >> 3) * 2 + ((index & 3) >> 1)];
            mvx = pi.mvX[address * 16 + index];
            mvy = pi.mvY[address * 16 + index];
            if (ref < 0) {
                mvx = 0;
                mvy = 0;
            }
        } else {
            ref = pi.refs1[address * 4 + (index >> 3) * 2 + ((index & 3) >> 1)];
            mvx = pi.mvX1[address * 16 + index];
            mvy = pi.mvY1[address * 16 + index];
            if (ref < 0) {
                mvx = 0;
                mvy = 0;
            }
        }
    }

    /**
     * Marks the last looked-up neighbor unavailable.
     */
    private void unavailable() {
        available = false;
        ref = -1;
        mvx = 0;
        mvy = 0;
    }
}
