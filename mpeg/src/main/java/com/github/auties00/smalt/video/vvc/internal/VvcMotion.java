/* Ports VVdeC 3.3.0 (source/Lib/CommonLib/UnitTools.cpp, ContextModelling.cpp, Mv.cpp and
 * DecoderLib/DecCu.cpp: the merge, AMVP, affine, subblock temporal, MMVD, geometric and IBC motion derivations).
 * Copyright (c) 2018-2026, Fraunhofer-Gesellschaft zur Foerderung der angewandten Forschung e.V. & The VVdeC Authors;
 * Clear BSD License. Modified for Smalt. */
package com.github.auties00.smalt.video.vvc.internal;

import com.github.auties00.smalt.MediaException;

import java.util.Arrays;

/**
 * Derives the motion of inter and IBC coding units (ITU-T H.266 8.5.2, 8.5.5 and 8.6.2) and stores it in the
 * picture's motion fields.
 *
 * <p>Motion vectors are in 1/16 luma samples. Two fields are kept per 4x4 luma block: the spatial field, which holds
 * the vectors the merge and AMVP derivations of later coding units read, and the picture field of
 * {@link VvcPicture}, which holds the vectors used for prediction after the decoder side refinement and which the
 * deblocking filter and temporal prediction read.
 *
 * <p>Instances are not thread-safe.
 */
final class VvcMotion {
    /**
     * The most candidates of a merge, AMVP or affine list.
     */
    private static final int MAX_CANDS = 6;
    /**
     * The corners of each constructed affine candidate (8.5.5.6), in the order the list takes them.
     */
    private static final int[][] AFFINE_MODELS = {{0, 1, 2}, {0, 1, 3}, {0, 2, 3}, {1, 2, 3}, {0, 1}, {0, 2}};
    /**
     * The size of the history-based candidate tables.
     */
    private static final int HMVP_SIZE = 5;
    /**
     * The largest motion vector component, {@code 2^17 - 1}.
     */
    private static final int MV_MAX = (1 << 17) - 1;
    /**
     * The smallest motion vector component, {@code -2^17}.
     */
    private static final int MV_MIN = -(1 << 17);

    /**
     * The per picture maps of the picture being decoded.
     */
    private final VvcFrame f;
    /**
     * The header of the slice being decoded.
     */
    VvcSliceHeader sh;
    /**
     * The index of the slice in its picture.
     */
    int sliceIndex;
    /**
     * The sequence parameter set of the slice.
     */
    private VvcSps sps;
    /**
     * The picture header of the slice.
     */
    private VvcPictureHeader ph;
    /**
     * {@code RefIdxSymL0} and {@code RefIdxSymL1} of the slice, -1 when symmetric MVD is not possible.
     */
    final int[] symRefIdx = {-1, -1};
    /**
     * {@code MaxNumSubblockMergeCand} of the slice.
     */
    int maxNumSubblockMergeCand;
    /**
     * {@code NoBackwardPredFlag}: no reference picture of the slice follows the current picture in output order.
     */
    boolean noBackwardPred;
    /**
     * Whether the slice is a B slice.
     */
    private boolean bSlice;
    /**
     * {@code Log2ParMrgLevel}.
     */
    private int plevel;

    /**
     * The spatial motion field of the frame ({@link VvcFrame#smvx0} and the others).
     */
    final int[] smvx0;
    /**
     * The vertical list 0 vectors of the spatial motion field.
     */
    final int[] smvy0;
    /**
     * The horizontal list 1 vectors of the spatial motion field.
     */
    final int[] smvx1;
    /**
     * The vertical list 1 vectors of the spatial motion field.
     */
    final int[] smvy1;
    /**
     * The list 0 reference indices of the spatial motion field.
     */
    final byte[] sref0;
    /**
     * The list 1 reference indices of the spatial motion field.
     */
    final byte[] sref1;

    /**
     * The horizontal vectors of each list of the history-based merge candidates ({@code HmvpCandList}).
     */
    private final int[][] hmvpMvx = new int[HMVP_SIZE][2];
    /**
     * The vertical vectors of each list of the history-based merge candidates.
     */
    private final int[][] hmvpMvy = new int[HMVP_SIZE][2];
    /**
     * The reference indices of each list of the history-based merge candidates.
     */
    private final int[][] hmvpRef = new int[HMVP_SIZE][2];
    /**
     * The BCW index of each history-based merge candidate.
     */
    private final int[] hmvpBcw = new int[HMVP_SIZE];
    /**
     * The half-sample filter flag of each history-based merge candidate.
     */
    private final boolean[] hmvpHpel = new boolean[HMVP_SIZE];
    /**
     * {@code NumHmvpCand}.
     */
    private int hmvpCount;
    /**
     * The horizontal block vectors of the history-based IBC candidates ({@code HmvpIbcCandList}).
     */
    private final int[][] ibcMvx = new int[HMVP_SIZE][2];
    /**
     * The vertical block vectors of the history-based IBC candidates.
     */
    private final int[][] ibcMvy = new int[HMVP_SIZE][2];
    /**
     * The reference indices of the history-based IBC candidates.
     */
    private final int[][] ibcRef = new int[HMVP_SIZE][2];
    /**
     * The BCW index of each history-based IBC candidate.
     */
    private final int[] ibcBcw = new int[HMVP_SIZE];
    /**
     * The half-sample filter flag of each history-based IBC candidate.
     */
    private final boolean[] ibcHpel = new boolean[HMVP_SIZE];
    /**
     * {@code NumHmvpIbcCand}.
     */
    private int ibcCount;

    /**
     * The prediction direction of each merge candidate: bit 0 list 0, bit 1 list 1.
     */
    private final int[] cDir = new int[MAX_CANDS];
    /**
     * The reference index of each list of each merge candidate.
     */
    private final int[][] cRef = new int[2][MAX_CANDS];
    /**
     * The horizontal vector of each list of each merge candidate.
     */
    private final int[][] cMvx = new int[2][MAX_CANDS];
    /**
     * The vertical vector of each list of each merge candidate.
     */
    private final int[][] cMvy = new int[2][MAX_CANDS];
    /**
     * The BCW index of each merge candidate.
     */
    private final int[] cBcw = new int[MAX_CANDS];
    /**
     * The half-sample filter flag of each merge candidate.
     */
    private final boolean[] cHpel = new boolean[MAX_CANDS];
    /**
     * The number of merge candidates.
     */
    private int cCount;

    /**
     * The list 0 reference index of each of the two MMVD base candidates.
     */
    private final int[] mmvdRef0 = new int[2];
    /**
     * The list 1 reference index of each of the two MMVD base candidates.
     */
    private final int[] mmvdRef1 = new int[2];
    /**
     * The vectors of each MMVD base candidate: list 0 horizontal and vertical, then list 1.
     */
    private final int[][] mmvdMv = new int[2][4];
    /**
     * The half-sample filter flag of each MMVD base candidate.
     */
    private final boolean[] mmvdHpel = new boolean[2];
    /**
     * The prediction direction of each MMVD base candidate.
     */
    private final int[] mmvdDir = new int[2];
    /**
     * The BCW index of each MMVD base candidate.
     */
    private final int[] mmvdBcw = new int[2];

    /**
     * The prediction direction of each subblock merge candidate.
     */
    private final int[] aDir = new int[MAX_CANDS];
    /**
     * The reference index of each list of each subblock merge candidate.
     */
    private final int[][] aRef = new int[2][MAX_CANDS];
    /**
     * The horizontal control point vectors of each list of each subblock merge candidate.
     */
    private final int[][][] aMvx = new int[2][MAX_CANDS][3];
    /**
     * The vertical control point vectors of each list of each subblock merge candidate.
     */
    private final int[][][] aMvy = new int[2][MAX_CANDS][3];
    /**
     * Whether each subblock merge candidate uses the six-parameter model.
     */
    private final boolean[] aSix = new boolean[MAX_CANDS];
    /**
     * The BCW index of each subblock merge candidate.
     */
    private final int[] aBcw = new int[MAX_CANDS];
    /**
     * Whether each subblock merge candidate is the subblock-based temporal candidate.
     */
    private final boolean[] aSbtmvp = new boolean[MAX_CANDS];
    /**
     * The number of subblock merge candidates.
     */
    private int aCount;

    /**
     * The horizontal vectors of the AMVP candidates.
     */
    private final int[] amvpX = new int[MAX_CANDS];
    /**
     * The vertical vectors of the AMVP candidates.
     */
    private final int[] amvpY = new int[MAX_CANDS];
    /**
     * The number of AMVP candidates.
     */
    private int amvpCount;
    /**
     * The horizontal control point vectors of the affine AMVP candidates.
     */
    private final int[][] affX = new int[3][MAX_CANDS];
    /**
     * The vertical control point vectors of the affine AMVP candidates.
     */
    private final int[][] affY = new int[3][MAX_CANDS];
    /**
     * The number of affine AMVP candidates.
     */
    private int affCount;

    /**
     * The vector a scaling or rounding step produced.
     */
    private final int[] mvOut = new int[2];
    /**
     * The temporal vector {@link #colocated} derived.
     */
    private final int[] colMv = new int[2];
    /**
     * The candidate count {@link #historyCandidates} reads and updates, in its only element.
     */
    private final int[] historyCounter = new int[1];
    /**
     * The candidates of the merge list a history candidate was compared with, for {@link #historyCandidates}.
     */
    private final boolean[] historyPruned = new boolean[MAX_CANDS];
    /**
     * The reference list of each uni-prediction candidate of a geometric partitioning list.
     */
    private final int[] gpmList = new int[MAX_CANDS];
    /**
     * The reference index of each uni-prediction candidate of a geometric partitioning list.
     */
    private final int[] gpmRef = new int[MAX_CANDS];
    /**
     * The horizontal vector of each uni-prediction candidate of a geometric partitioning list.
     */
    private final int[] gpmMvx = new int[MAX_CANDS];
    /**
     * The vertical vector of each uni-prediction candidate of a geometric partitioning list.
     */
    private final int[] gpmMvy = new int[MAX_CANDS];
    /**
     * The corner vectors of the constructed affine AMVP candidate, horizontal and vertical of each corner.
     */
    private final int[] amvpCorner = new int[6];
    /**
     * The affine neighbors inherited by the affine merge list.
     */
    private final VvcCu[] affineNeighbors = new VvcCu[5];
    /**
     * Whether each corner of the constructed affine candidates is available.
     */
    private final boolean[] cornerAvail = new boolean[4];
    /**
     * The reference index of each corner and list of the constructed affine candidates.
     */
    private final int[][] cornerRef = new int[4][2];
    /**
     * The vectors of each corner of the constructed affine candidates, horizontal and vertical of each list.
     */
    private final int[][] cornerMv = new int[4][4];
    /**
     * The BCW index of the top-left and top-right corners of the constructed affine candidates.
     */
    private final int[] cornerBcw = new int[2];
    /**
     * The reference index of each list of a constructed affine candidate.
     */
    private final int[] cornerCandRef = new int[2];
    /**
     * The horizontal control point vectors of one list of a constructed affine candidate.
     */
    private final int[] cornerX = new int[4];
    /**
     * The vertical control point vectors of one list of a constructed affine candidate.
     */
    private final int[] cornerY = new int[4];
    /**
     * The luma column of the collocated position last clamped by {@link #clipColPos}.
     */
    private int clipX;
    /**
     * The luma row of the collocated position last clamped by {@link #clipColPos}.
     */
    private int clipY;
    /**
     * The reference index of the temporal candidate, in its only element.
     */
    private final int[] colRef = new int[1];
    /**
     * The horizontal control point vectors {@link #inheritAffine} derived.
     */
    private final int[] cpX = new int[3];
    /**
     * The vertical control point vectors {@link #inheritAffine} derived.
     */
    private final int[] cpY = new int[3];

    /**
     * The motion of the subblock-based temporal candidate per 4x4 block of the coding unit (8x8 subblocks), filled
     * when that candidate is derived.
     */
    private final int[] sbMvx0 = new int[32 * 32];
    /**
     * The vertical list 0 vector of the subblock-based temporal candidate per 4x4 block of the coding unit.
     */
    private final int[] sbMvy0 = new int[32 * 32];
    /**
     * The horizontal list 1 vector of the subblock-based temporal candidate per 4x4 block of the coding unit.
     */
    private final int[] sbMvx1 = new int[32 * 32];
    /**
     * The vertical list 1 vector of the subblock-based temporal candidate per 4x4 block of the coding unit.
     */
    private final int[] sbMvy1 = new int[32 * 32];
    /**
     * The list 0 reference index of the subblock-based temporal candidate per 4x4 block of the coding unit.
     */
    private final byte[] sbRef0 = new byte[32 * 32];
    /**
     * The list 1 reference index of the subblock-based temporal candidate per 4x4 block of the coding unit.
     */
    private final byte[] sbRef1 = new byte[32 * 32];
    /**
     * The prediction direction of the subblock-based temporal candidate, in its only element.
     */
    private final int[] sbtmvpDir = new int[1];
    /**
     * The reference index of each list of the subblock-based temporal candidate.
     */
    private final int[][] sbtmvpRef = new int[2][1];
    /**
     * The vector of each list of the center of the subblock-based temporal candidate.
     */
    private final int[][] sbtmvpMv = new int[2][2];

    /**
     * Constructs the motion derivation of a picture size.
     *
     * @param frame the per picture maps
     */
    VvcMotion(VvcFrame frame) {
        this.f = frame;
        smvx0 = frame.smvx0;
        smvy0 = frame.smvy0;
        smvx1 = frame.smvx1;
        smvy1 = frame.smvy1;
        sref0 = frame.sref0;
        sref1 = frame.sref1;
    }

    /**
     * Prepares the motion derivation of a slice: empties the history tables and derives the symmetric MVD references
     * and {@code NoBackwardPredFlag}.
     *
     * @param header the slice header
     * @param slice the index of the slice in its picture
     */
    void beginSlice(VvcSliceHeader header, int slice) {
        sh = header;
        sliceIndex = slice;
        sps = header.sps;
        ph = header.ph;
        bSlice = header.isB();
        plevel = sps.log2ParallelMergeLevel;
        if (sps.affine) {
            maxNumSubblockMergeCand = sps.maxNumSubblockMergeCand;
        } else {
            maxNumSubblockMergeCand = sps.sbtmvp && ph.temporalMvp ? 1 : 0;
        }
        var poc = header.poc;
        var lowDelay = true;
        for (var l = 0; l < (bSlice ? 2 : 1) && lowDelay; l++) {
            for (var i = 0; i < header.numRefIdxActive[l]; i++) {
                if (header.refPoc[l][i] > poc) {
                    lowDelay = false;
                    break;
                }
            }
        }
        noBackwardPred = !header.isIntra() && lowDelay;
        symRefIdx[0] = -1;
        symRefIdx[1] = -1;
        if (sps.smvd && bSlice && !noBackwardPred && !ph.mvdL1Zero) {
            deriveSymmetricRefs(header, poc);
        }
    }

    /**
     * Derives {@code RefIdxSymL0} and {@code RefIdxSymL1} (7.4.11.6): the closest short-term references before and
     * after the picture, or after and before.
     *
     * @param header the slice header
     * @param poc the order count of the picture
     */
    private void deriveSymmetricRefs(VvcSliceHeader header, int poc) {
        var forward = poc;
        var backward = poc;
        var ref0 = -1;
        var ref1 = -1;
        for (var r = 0; r < header.numRefIdxActive[0]; r++) {
            var p = header.refPoc[0][r];
            if (p < poc && (p > forward || ref0 == -1) && !header.refLongTerm[0][r]) {
                forward = p;
                ref0 = r;
            }
        }
        for (var r = 0; r < header.numRefIdxActive[1]; r++) {
            var p = header.refPoc[1][r];
            if (p > poc && (p < backward || ref1 == -1) && !header.refLongTerm[1][r]) {
                backward = p;
                ref1 = r;
            }
        }
        if (!(forward < poc && backward > poc)) {
            forward = poc;
            backward = poc;
            ref0 = -1;
            ref1 = -1;
            for (var r = 0; r < header.numRefIdxActive[0]; r++) {
                var p = header.refPoc[0][r];
                if (p > poc && (p < backward || ref0 == -1) && !header.refLongTerm[0][r]) {
                    backward = p;
                    ref0 = r;
                }
            }
            for (var r = 0; r < header.numRefIdxActive[1]; r++) {
                var p = header.refPoc[1][r];
                if (p < poc && (p > forward || ref1 == -1) && !header.refLongTerm[1][r]) {
                    forward = p;
                    ref1 = r;
                }
            }
        }
        if (forward < poc && backward > poc) {
            symRefIdx[0] = ref0;
            symRefIdx[1] = ref1;
        }
    }

    /**
     * Empties the history-based candidate tables, at the start of a slice, tile or CTU row.
     */
    void resetHistory() {
        hmvpCount = 0;
        ibcCount = 0;
    }

    /**
     * {@return whether explicit weighted prediction flags are set for either reference of a bi-predicted unit}
     *
     * @param ref0 the list 0 reference index, or -1
     * @param ref1 the list 1 reference index, or -1
     */
    boolean weighted(int ref0, int ref1) {
        var w = sh.weights;
        if (w == null || ref0 < 0 || ref1 < 0) {
            return false;
        }
        for (var c = 0; c < 3; c++) {
            if (w.present[0][ref0][c] || w.present[1][ref1][c]) {
                return true;
            }
        }
        return false;
    }

    /**
     * Derives the motion of a coding unit and stores it in the motion fields.
     *
     * @param cu the coding unit
     * @throws MediaException.Malformed if the syntax refers to a candidate that does not exist
     */
    void derive(VvcCu cu) {
        if (cu.predMode == VvcCu.MODE_IBC) {
            deriveIbc(cu);
        } else if (cu.merge) {
            if (cu.mmvd) {
                deriveMmvd(cu);
            } else if (cu.gpm) {
                deriveGpm(cu);
            } else if (cu.subblockMerge) {
                deriveAffineMerge(cu);
            } else {
                mergeCandidates(cu, cu.mergeIdx);
                applyMerge(cu, cu.mergeIdx);
                storeUniform(cu);
            }
        } else if (cu.affine) {
            deriveAffineAmvp(cu);
        } else {
            deriveAmvp(cu);
            storeUniform(cu);
        }
        var smallIbc = cu.predMode == VvcCu.MODE_IBC && cu.w * cu.h <= 16;
        if (!cu.affine && !cu.gpm && !smallIbc) {
            var xBr = cu.x + cu.w;
            var yBr = cu.y + cu.h;
            var enable = (xBr >> plevel) > (cu.x >> plevel) && (yBr >> plevel) > (cu.y >> plevel);
            if (cu.predMode == VvcCu.MODE_IBC || enable) {
                addHistory(cu);
            }
        }
    }

    /**
     * {@return a vector component clamped to 18 bits}
     *
     * @param v the component
     */
    private static int clip18(int v) {
        return Math.clamp(v, MV_MIN, MV_MAX);
    }

    /**
     * {@return a vector component wrapped to 18 bits}
     *
     * @param v the component
     */
    private static int wrap18(int v) {
        v = (v + (1 << 18)) & ((1 << 18) - 1);
        return v >= (1 << 17) ? v - (1 << 18) : v;
    }

    /**
     * Rounds a vector component from 1/16 sample precision to a coarser one and back (round half away from zero
     * handling of {@code Mv::changePrecision}).
     *
     * @param v the component, in 1/16 samples
     * @param shift the precision shift
     * @return the rounded component
     */
    private static int roundTo(int v, int shift) {
        if (shift == 0) {
            return v;
        }
        var offset = 1 << (shift - 1);
        var r = v >= 0 ? (v + offset - 1) >> shift : (v + offset) >> shift;
        return r << shift;
    }

    /**
     * {@return a component shifted right with rounding toward zero on ties}
     *
     * @param v the component
     * @param shift the shift, positive
     */
    private static int shiftRound(int v, int shift) {
        var offset = 1 << (shift - 1);
        return v >= 0 ? (v + offset - 1) >> shift : (v + offset) >> shift;
    }

    /**
     * {@return a component shifted right with the rounding of the affine derivation}
     *
     * @param v the component
     * @param shift the shift, positive
     */
    private static int roundAffine(int v, int shift) {
        var offset = 1 << (shift - 1);
        return (v + offset - (v >= 0 ? 1 : 0)) >> shift;
    }

    /**
     * {@return the AMVR precision shift from 1/16 sample of a non-affine, non-IBC coding unit}
     *
     * @param imv the motion vector resolution of the coding unit
     */
    private static int amvrShift(int imv) {
        return switch (imv) {
            case 1 -> 4;
            case 2 -> 6;
            case 3 -> 3;
            default -> 2;
        };
    }

    /**
     * Returns the coding unit covering a neighbor position when it is available for the current coding unit
     * ({@code getCURestricted}): decoded, in the same slice and tile, and not in a CTU that wavefront parallel
     * processing has not reached.
     *
     * @param x the luma column of the neighbor
     * @param y the luma row of the neighbor
     * @param cu the current coding unit
     * @return the neighbor, or {@code null} when it is not available
     */
    private VvcCu neighbor(int x, int y, VvcCu cu) {
        if (x < 0 || y < 0 || x >= f.width || y >= f.height) {
            return null;
        }
        var n = f.cuAt(x, y, false);
        if (n == null || n == cu) {
            return null;
        }
        var log = f.log2Ctu;
        var ydiff = (y >> log) - (cu.y >> log);
        var xdiff = (x >> log) - (cu.x >> log);
        if (ydiff == 0 && xdiff == 0) {
            return n;
        }
        if (ydiff > 0 || xdiff > (f.wpp ? 0 : 1) || (ydiff == 0 && xdiff > 0)) {
            return null;
        }
        var ctu = (y >> log) * f.widthInCtus + (x >> log);
        var cur = (cu.y >> log) * f.widthInCtus + (cu.x >> log);
        if (f.ctuSlice[ctu] != f.ctuSlice[cur] || f.ctuTile[ctu] != f.ctuTile[cur]) {
            return null;
        }
        return n;
    }

    /**
     * {@return whether a position is in another merge estimation region than a coding unit}
     *
     * @param cu the coding unit
     * @param x the luma column
     * @param y the luma row
     */
    private boolean diffMer(VvcCu cu, int x, int y) {
        return (cu.x >> plevel) != (x >> plevel) || (cu.y >> plevel) != (y >> plevel);
    }

    /**
     * {@return the index of the 4x4 block holding a luma position}
     *
     * @param x the luma column
     * @param y the luma row
     */
    private int idx4(int x, int y) {
        return (y >> 2) * f.width4 + (x >> 2);
    }

    /**
     * {@return whether two 4x4 blocks of the spatial field hold the same motion}
     *
     * @param a the index of the first block
     * @param b the index of the second block
     */
    private boolean sameMotion(int a, int b) {
        if (sref0[a] != sref0[b]) {
            return false;
        }
        if (sref0[a] >= 0 && (smvx0[a] != smvx0[b] || smvy0[a] != smvy0[b])) {
            return false;
        }
        if (sref1[a] != sref1[b]) {
            return false;
        }
        return sref1[a] < 0 || (smvx1[a] == smvx1[b] && smvy1[a] == smvy1[b]);
    }

    /**
     * Copies the motion of a 4x4 block of the spatial field into a merge candidate.
     *
     * @param n the candidate index
     * @param i4 the index of the block
     * @param src the coding unit of the block, which gives the BCW index and the half-sample filter flag
     */
    private void setCandidate(int n, int i4, VvcCu src) {
        int r0 = sref0[i4];
        int r1 = bSlice ? sref1[i4] : -1;
        cRef[0][n] = r0;
        cMvx[0][n] = smvx0[i4];
        cMvy[0][n] = smvy0[i4];
        cRef[1][n] = r1;
        cMvx[1][n] = bSlice ? smvx1[i4] : 0;
        cMvy[1][n] = bSlice ? smvy1[i4] : 0;
        cDir[n] = (sref0[i4] >= 0 ? 1 : 0) | (sref1[i4] >= 0 ? 2 : 0);
        cHpel[n] = src.imv == VvcCu.IMV_HALF;
        cBcw[n] = cDir[n] == 3 ? src.bcwIdx : 0;
    }

    /**
     * Builds the regular merge candidate list (8.5.2.2) up to the candidate with index {@code stop}, or completely
     * when {@code stop} is negative.
     *
     * @param cu the coding unit
     * @param stop the index of the last candidate needed, or a negative value for the whole list
     */
    private void mergeCandidates(VvcCu cu, int stop) {
        var max = sps.maxNumMergeCand;
        for (var i = 0; i < max; i++) {
            cDir[i] = 0;
            cRef[0][i] = -1;
            cRef[1][i] = -1;
            cMvx[0][i] = 0;
            cMvy[0][i] = 0;
            cMvx[1][i] = 0;
            cMvy[1][i] = 0;
            cBcw[i] = 0;
            cHpel[i] = false;
        }
        cCount = max;
        var cnt = 0;
        var xR = cu.x + cu.w - 1;
        var yB = cu.y + cu.h - 1;
        var above = neighbor(xR, cu.y - 1, cu);
        var b1 = above != null && above.predMode == VvcCu.MODE_INTER && diffMer(cu, xR, cu.y - 1);
        var iB1 = idx4(xR, cu.y - 1);
        if (b1) {
            setCandidate(cnt, iB1, above);
            if (stop == cnt) {
                return;
            }
            cnt++;
        }
        if (cnt == max) {
            return;
        }
        var left = neighbor(cu.x - 1, yB, cu);
        var a1 = left != null && left.predMode == VvcCu.MODE_INTER && diffMer(cu, cu.x - 1, yB);
        var iA1 = idx4(cu.x - 1, yB);
        if (a1) {
            if (!b1 || above.slice != left.slice || !sameMotion(iB1, iA1)) {
                setCandidate(cnt, iA1, left);
                if (stop == cnt) {
                    return;
                }
                cnt++;
            }
        }
        if (cnt == max) {
            return;
        }
        var spatialCount = cnt;
        var aboveRight = neighbor(xR + 1, cu.y - 1, cu);
        if (aboveRight != null && aboveRight.predMode == VvcCu.MODE_INTER && diffMer(cu, xR + 1, cu.y - 1)) {
            var iB0 = idx4(xR + 1, cu.y - 1);
            if (!b1 || above.slice != aboveRight.slice || !sameMotion(iB1, iB0)) {
                setCandidate(cnt, iB0, aboveRight);
                if (stop == cnt) {
                    return;
                }
                cnt++;
            }
            if (cnt == max) {
                return;
            }
        }
        var belowLeft = neighbor(cu.x - 1, yB + 1, cu);
        if (belowLeft != null && belowLeft.predMode == VvcCu.MODE_INTER && diffMer(cu, cu.x - 1, yB + 1)) {
            var iA0 = idx4(cu.x - 1, yB + 1);
            if (!a1 || belowLeft.slice != left.slice || !sameMotion(iA0, iA1)) {
                setCandidate(cnt, iA0, belowLeft);
                if (stop == cnt) {
                    return;
                }
                cnt++;
            }
            if (cnt == max) {
                return;
            }
        }
        if (cnt < 4) {
            var aboveLeft = neighbor(cu.x - 1, cu.y - 1, cu);
            if (aboveLeft != null && aboveLeft.predMode == VvcCu.MODE_INTER && diffMer(cu, cu.x - 1, cu.y - 1)) {
                var iB2 = idx4(cu.x - 1, cu.y - 1);
                if ((!a1 || left.slice != aboveLeft.slice || !sameMotion(iA1, iB2))
                        && (!b1 || above.slice != aboveLeft.slice || !sameMotion(iB1, iB2))) {
                    setCandidate(cnt, iB2, aboveLeft);
                    if (stop == cnt) {
                        return;
                    }
                    cnt++;
                }
            }
            if (cnt == max) {
                return;
            }
        }
        if (ph.temporalMvp && cu.w + cu.h > 12) {
            var dir = 0;
            var c0 = colPositionC0(cu);
            var cx = cu.x + (cu.w >> 1);
            var cy = cu.y + (cu.h >> 1);
            if ((c0 && colocated(cu, 0, cu.x + cu.w, cu.y + cu.h, 0, false))
                    || colocated(cu, 0, cx, cy, 0, false)) {
                dir |= 1;
                cMvx[0][cnt] = colMv[0];
                cMvy[0][cnt] = colMv[1];
                cRef[0][cnt] = 0;
            }
            if (bSlice && ((c0 && colocated(cu, 1, cu.x + cu.w, cu.y + cu.h, 0, false))
                    || colocated(cu, 1, cx, cy, 0, false))) {
                dir |= 2;
                cMvx[1][cnt] = colMv[0];
                cMvy[1][cnt] = colMv[1];
                cRef[1][cnt] = 0;
            }
            if (dir != 0) {
                cDir[cnt] = dir;
                cBcw[cnt] = 0;
                cHpel[cnt] = false;
                if (stop == cnt) {
                    return;
                }
                cnt++;
            }
            if (cnt == max) {
                return;
            }
        }
        var maxMinus1 = max - 1;
        if (cnt != maxMinus1) {
            var counter = historyCounter;
            counter[0] = cnt;
            if (historyCandidates(stop, maxMinus1, counter, spatialCount, false, true)) {
                return;
            }
            cnt = counter[0];
        }
        if (cnt > 1 && cnt < max) {
            cRef[0][cnt] = -1;
            cRef[1][cnt] = -1;
            cMvx[0][cnt] = 0;
            cMvy[0][cnt] = 0;
            cMvx[1][cnt] = 0;
            cMvy[1][cnt] = 0;
            var dir = 0;
            cHpel[cnt] = cHpel[0] == cHpel[1] && cHpel[0];
            for (var l = 0; l < (bSlice ? 2 : 1); l++) {
                var ri = cRef[l][0];
                var rj = cRef[l][1];
                if (ri < 0 && rj < 0) {
                    continue;
                }
                dir |= 1 << l;
                if (ri >= 0 && rj >= 0) {
                    cMvx[l][cnt] = roundAffine(cMvx[l][0] + cMvx[l][1], 1);
                    cMvy[l][cnt] = roundAffine(cMvy[l][0] + cMvy[l][1], 1);
                    cRef[l][cnt] = ri;
                } else if (ri >= 0) {
                    cMvx[l][cnt] = cMvx[l][0];
                    cMvy[l][cnt] = cMvy[l][0];
                    cRef[l][cnt] = ri;
                } else {
                    cMvx[l][cnt] = cMvx[l][1];
                    cMvy[l][cnt] = cMvy[l][1];
                    cRef[l][cnt] = rj;
                }
            }
            cDir[cnt] = dir;
            cBcw[cnt] = 0;
            if (dir > 0) {
                cnt++;
            }
            if (cnt == max) {
                return;
            }
        }
        var numRef = bSlice ? Math.min(sh.numRefIdxActive[0], sh.numRefIdxActive[1]) : sh.numRefIdxActive[0];
        var r = 0;
        var refCnt = 0;
        while (cnt < max) {
            cDir[cnt] = bSlice ? 3 : 1;
            cBcw[cnt] = 0;
            cHpel[cnt] = false;
            cRef[0][cnt] = r;
            cMvx[0][cnt] = 0;
            cMvy[0][cnt] = 0;
            cRef[1][cnt] = bSlice ? r : -1;
            cMvx[1][cnt] = 0;
            cMvy[1][cnt] = 0;
            cnt++;
            if (refCnt == numRef - 1) {
                r = 0;
            } else {
                r++;
                refCnt++;
            }
        }
        cCount = cnt;
    }

    /**
     * {@return whether a history-based candidate repeats the motion of one of the first spatial merge candidates}
     *
     * @param cand the index of the history-based candidate
     * @param prevCount the number of spatial candidates compared
     * @param pruned the candidates already pruned, skipped
     */
    private boolean similarToPrevious(int cand, int prevCount, boolean[] pruned) {
        for (var ui = 0; ui < prevCount; ui++) {
            if (pruned[ui] || cDir[ui] != cDir[cand]) {
                continue;
            }
            if (cDir[ui] == 3) {
                if (cRef[0][ui] == cRef[0][cand] && cRef[1][ui] == cRef[1][cand] && cMvx[0][ui] == cMvx[0][cand]
                        && cMvy[0][ui] == cMvy[0][cand] && cMvx[1][ui] == cMvx[1][cand]
                        && cMvy[1][ui] == cMvy[1][cand]) {
                    pruned[ui] = true;
                    return true;
                }
            } else {
                var l = cDir[ui] - 1;
                if (cRef[l][ui] == cRef[l][cand] && cMvx[l][ui] == cMvx[l][cand] && cMvy[l][ui] == cMvy[l][cand]) {
                    pruned[ui] = true;
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Appends the history-based merge candidates (8.5.2.6); returns {@code true} when the candidate with index
     * {@code stop} was reached.
     *
     * @param stop the index of the last candidate needed, or a negative value
     * @param maxMinus1 the size of the list less one
     * @param counter the number of candidates, updated
     * @param prevCount the number of spatial candidates the history candidates are compared with
     * @param ibc whether the IBC table is read
     * @param gt4x4 whether the coding unit is larger than 4x4, which enables the pruning
     * @return whether the list is complete
     */
    private boolean historyCandidates(int stop, int maxMinus1, int[] counter, int prevCount, boolean ibc,
            boolean gt4x4) {
        var pruned = historyPruned;
        Arrays.fill(pruned, false);
        var count = ibc ? ibcCount : hmvpCount;
        var mvx = ibc ? ibcMvx : hmvpMvx;
        var mvy = ibc ? ibcMvy : hmvpMvy;
        var ref = ibc ? ibcRef : hmvpRef;
        var bcw = ibc ? ibcBcw : hmvpBcw;
        var hpel = ibc ? ibcHpel : hmvpHpel;
        var cnt = counter[0];
        for (var m = 1; m <= count; m++) {
            var h = count - m;
            cDir[cnt] = (ref[h][0] >= 0 ? 1 : 0) | (ref[h][1] >= 0 ? 2 : 0);
            cRef[0][cnt] = ref[h][0];
            cMvx[0][cnt] = mvx[h][0];
            cMvy[0][cnt] = mvy[h][0];
            cHpel[cnt] = !ibc && hpel[h];
            if (bSlice) {
                cRef[1][cnt] = ref[h][1];
                cMvx[1][cnt] = mvx[h][1];
                cMvy[1][cnt] = mvy[h][1];
            }
            if (m > 2 || ((m > 1 || !gt4x4) && ibc) || !similarToPrevious(cnt, prevCount, pruned)) {
                cBcw[cnt] = cDir[cnt] == 3 ? bcw[h] : 0;
                if (stop == cnt) {
                    counter[0] = cnt;
                    return true;
                }
                cnt++;
                if (cnt == maxMinus1) {
                    break;
                }
            }
        }
        if (cnt < maxMinus1) {
            cHpel[cnt] = false;
        }
        counter[0] = cnt;
        return false;
    }

    /**
     * Adds the motion of a coding unit to its history-based candidate table (8.5.2.16), replacing an equal entry or the
     * oldest.
     *
     * @param cu the coding unit
     */
    private void addHistory(VvcCu cu) {
        var ibc = cu.predMode == VvcCu.MODE_IBC;
        var mvx = ibc ? ibcMvx : hmvpMvx;
        var mvy = ibc ? ibcMvy : hmvpMvy;
        var ref = ibc ? ibcRef : hmvpRef;
        var bcw = ibc ? ibcBcw : hmvpBcw;
        var hpel = ibc ? ibcHpel : hmvpHpel;
        var count = ibc ? ibcCount : hmvpCount;
        var r0 = ibc ? 0 : cu.refIdx[0];
        var r1 = ibc ? -1 : cu.refIdx[1];
        var x0 = cu.mvx[0][0];
        var y0 = cu.mvy[0][0];
        var x1 = cu.mvx[1][0];
        var y1 = cu.mvy[1][0];
        var b = cu.interDir == 3 ? cu.bcwIdx : 0;
        var hp = cu.imv == VvcCu.IMV_HALF;
        var same = -1;
        for (var i = 0; i < count; i++) {
            if (ref[i][0] == r0 && (r0 < 0 || (mvx[i][0] == x0 && mvy[i][0] == y0)) && ref[i][1] == r1
                    && (r1 < 0 || (mvx[i][1] == x1 && mvy[i][1] == y1))) {
                same = i;
                break;
            }
        }
        if (same >= 0 || count == HMVP_SIZE) {
            var from = same >= 0 ? same : 0;
            for (var i = from; i < count - 1; i++) {
                mvx[i][0] = mvx[i + 1][0];
                mvx[i][1] = mvx[i + 1][1];
                mvy[i][0] = mvy[i + 1][0];
                mvy[i][1] = mvy[i + 1][1];
                ref[i][0] = ref[i + 1][0];
                ref[i][1] = ref[i + 1][1];
                bcw[i] = bcw[i + 1];
                hpel[i] = hpel[i + 1];
            }
            count--;
        }
        mvx[count][0] = x0;
        mvy[count][0] = y0;
        mvx[count][1] = x1;
        mvy[count][1] = y1;
        ref[count][0] = r0;
        ref[count][1] = r1;
        bcw[count] = b;
        hpel[count] = hp;
        count++;
        if (ibc) {
            ibcCount = count;
        } else {
            hmvpCount = count;
        }
    }

    /**
     * Gives a coding unit the motion of a merge candidate, bi-prediction restricted for 8x4 and 4x8 units.
     *
     * @param cu the coding unit
     * @param idx the candidate index
     * @throws MediaException.Malformed if the candidate does not exist
     */
    private void applyMerge(VvcCu cu, int idx) {
        if (idx >= cCount) {
            throw new MediaException.Malformed("vvc merge candidate does not exist");
        }
        cu.interDir = cDir[idx];
        cu.imv = !cu.gpm && cHpel[idx] ? VvcCu.IMV_HALF : 0;
        cu.halfPel = cu.imv == VvcCu.IMV_HALF;
        for (var l = 0; l < 2; l++) {
            cu.mvx[l][0] = cMvx[l][idx];
            cu.mvy[l][0] = cMvy[l][idx];
            cu.refIdx[l] = cRef[l][idx];
        }
        cu.bcwIdx = cDir[idx] == 3 ? cBcw[idx] : 0;
        restrictBiPred(cu);
    }

    /**
     * Converts the bi-prediction of an 8x4 or 4x8 coding unit to list 0 prediction (8.5.2.2).
     *
     * @param cu the coding unit
     */
    private static void restrictBiPred(VvcCu cu) {
        if (cu.w + cu.h <= 12 && cu.interDir == 3) {
            cu.interDir = 1;
            cu.refIdx[1] = -1;
            cu.mvx[1][0] = 0;
            cu.mvy[1][0] = 0;
            cu.bcwIdx = 0;
        }
    }

    /**
     * {@return whether the bottom-right temporal candidate position C0 is usable for a coding unit}
     *
     * @param cu the coding unit
     */
    private boolean colPositionC0(VvcCu cu) {
        var rbX = cu.x + cu.w - 4;
        var rbY = cu.y + cu.h - 4;
        boolean inside;
        var subpic = f.ctuSubpic[(cu.y >> f.log2Ctu) * f.widthInCtus + (cu.x >> f.log2Ctu)];
        if (sps.subpicTreatedAsPic[subpic]) {
            inside = rbX + 4 <= subpicRight(subpic) && rbY + 4 <= subpicBottom(subpic);
        } else {
            inside = rbX + 4 < f.width && rbY + 4 < f.height;
        }
        if (!inside) {
            return false;
        }
        var inCtuY = rbY & (f.ctuSize - 1);
        return inCtuY + 4 < f.ctuSize;
    }

    /**
     * {@return the first luma column of a subpicture}
     *
     * @param s the subpicture index
     */
    private int subpicLeft(int s) {
        return sps.subpicCtuX[s] << f.log2Ctu;
    }

    /**
     * {@return the first luma row of a subpicture}
     *
     * @param s the subpicture index
     */
    private int subpicTop(int s) {
        return sps.subpicCtuY[s] << f.log2Ctu;
    }

    /**
     * {@return the last luma column of a subpicture}
     *
     * @param s the subpicture index
     */
    private int subpicRight(int s) {
        return Math.min(f.width, (sps.subpicCtuX[s] + sps.subpicWidth[s]) << f.log2Ctu) - 1;
    }

    /**
     * {@return the last luma row of a subpicture}
     *
     * @param s the subpicture index
     */
    private int subpicBottom(int s) {
        return Math.min(f.height, (sps.subpicCtuY[s] + sps.subpicHeight[s]) << f.log2Ctu) - 1;
    }

    /**
     * {@return the collocated picture of the slice, or {@code null} when its reference index is not active}
     */
    private VvcPicture colPicture() {
        var list = bSlice ? (sh.colFromL0 ? 0 : 1) : 0;
        if (sh.colRefIdx >= sh.numRefIdxActive[list]) {
            return null;
        }
        return sh.refPics[list][sh.colRefIdx];
    }

    /**
     * {@return the distance scale factor of a temporal vector (8.5.2.12): the ratio of the current and collocated order
     * count distances}
     *
     * @param curPoc the current order count
     * @param curRefPoc the order count of the current reference
     * @param colPoc the order count of the collocated picture
     * @param colRefPoc the order count of the collocated reference
     */
    private static int distScale(int curPoc, int curRefPoc, int colPoc, int colRefPoc) {
        var diffD = colPoc - colRefPoc;
        var diffB = curPoc - curRefPoc;
        if (diffD == diffB) {
            return 4096;
        }
        var tb = Math.clamp(diffB, -128, 127);
        var td = Math.clamp(diffD, -128, 127);
        var tx = (0x4000 + Math.abs(td / 2)) / td;
        return Math.clamp((tb * tx + 32) >> 6, -4096, 4095);
    }

    /**
     * {@return a vector component scaled by a distance scale factor and clamped to 18 bits}
     *
     * @param scale the scale factor
     * @param v the component
     */
    private static int scaleComponent(int scale, int v) {
        var p = scale * v;
        return Math.clamp((p + 128 - (p >= 0 ? 1 : 0)) >> 8, -131072, 131071);
    }

    /**
     * Rounds a vector component to the 6-bit mantissa and 4-bit exponent representation the temporal motion field
     * stores (8.5.2.12).
     *
     * @param val the component
     * @return the rounded component
     */
    private static int roundMvStorage(int val) {
        var sign = val >> 31;
        var scale = (31 - Integer.numberOfLeadingZeros((val ^ sign) | 31)) - 5;
        int exponent;
        int mantissa;
        if (scale >= 0) {
            var round = (1 << scale) >> 1;
            var n = (val + round) >> scale;
            exponent = scale + ((n ^ sign) >> 5);
            mantissa = (n & 31) | (sign * (1 << 5));
        } else {
            exponent = 0;
            mantissa = val;
        }
        var stored = exponent | (mantissa * (1 << 4));
        var e = stored & 15;
        var m = stored >> 4;
        return e == 0 ? m : (m ^ 32) * (1 << (e - 1));
    }

    /**
     * Derives the temporal motion vector of a list from the collocated picture at a luma position
     * ({@code getColocatedMVP}, 8.5.2.12) into {@link #colMv}.
     *
     * @param cu the coding unit
     * @param list the reference picture list, 0 or 1
     * @param x the luma column of the collocated position
     * @param y the luma row of the collocated position
     * @param refIdx the reference index of the current list
     * @param subblock whether the derivation is for the subblock-based temporal candidate
     * @return whether the collocated block has a usable vector
     */
    private boolean colocated(VvcCu cu, int list, int x, int y, int refIdx, boolean subblock) {
        var col = colPicture();
        if (col == null || col.width != f.width || col.height != f.height) {
            return false;
        }
        var subpic = f.ctuSubpic[(cu.y >> f.log2Ctu) * f.widthInCtus + (cu.x >> f.log2Ctu)];
        if (sps.subpicTreatedAsPic[subpic]) {
            if (x < subpicLeft(subpic) || x > subpicRight(subpic) || y < subpicTop(subpic)
                    || y > subpicBottom(subpic)) {
                return false;
            }
        }
        if (x >= col.width || y >= col.height) {
            return false;
        }
        var i8 = (y >> 3) * col.width8 + (x >> 3);
        int flags = col.colFlags[i8];
        if ((flags & 3) == 0) {
            return false;
        }
        int colList;
        if (subblock && !noBackwardPred) {
            colList = list;
            if ((flags & (1 << colList)) == 0) {
                return false;
            }
        } else {
            colList = noBackwardPred ? list : (sh.colFromL0 ? 1 : 0);
            if ((flags & (1 << colList)) == 0) {
                colList = 1 - colList;
                if ((flags & (1 << colList)) == 0) {
                    return false;
                }
            }
        }
        var curLt = sh.refLongTerm[list][refIdx];
        var colLt = (flags & (4 << colList)) != 0;
        if (curLt != colLt) {
            return false;
        }
        var mx = roundMvStorage(colList == 0 ? col.colMvx0[i8] : col.colMvx1[i8]);
        var my = roundMvStorage(colList == 0 ? col.colMvy0[i8] : col.colMvy1[i8]);
        if (curLt) {
            colMv[0] = clip18(mx);
            colMv[1] = clip18(my);
            return true;
        }
        var colRefPoc = colList == 0 ? col.colPoc0[i8] : col.colPoc1[i8];
        var scale = distScale(sh.poc, sh.refPoc[list][refIdx], col.poc, colRefPoc);
        if (scale == 4096) {
            colMv[0] = clip18(mx);
            colMv[1] = clip18(my);
        } else {
            colMv[0] = scaleComponent(scale, mx);
            colMv[1] = scaleComponent(scale, my);
        }
        return true;
    }

    /**
     * Derives the motion of an MMVD coding unit (8.5.2.7): its base candidate plus the scaled distance offsets.
     *
     * @param cu the coding unit
     */
    private void deriveMmvd(VvcCu cu) {
        var base = cu.mmvdIdx / 32;
        mergeCandidates(cu, base + 1);
        var n = 0;
        for (var k = 0; k < cCount && n < 2; k++) {
            mmvdRef0[n] = cRef[0][k];
            mmvdRef1[n] = cRef[1][k];
            mmvdMv[n][0] = cRef[0][k] >= 0 ? cMvx[0][k] : 0;
            mmvdMv[n][1] = cRef[0][k] >= 0 ? cMvy[0][k] : 0;
            mmvdMv[n][2] = cRef[1][k] >= 0 ? cMvx[1][k] : 0;
            mmvdMv[n][3] = cRef[1][k] >= 0 ? cMvy[1][k] : 0;
            if (cRef[0][k] < 0) {
                mmvdRef0[n] = -1;
            }
            if (cRef[1][k] < 0) {
                mmvdRef1[n] = -1;
            }
            mmvdHpel[n] = cHpel[k];
            mmvdDir[n] = cDir[k];
            mmvdBcw[n] = cBcw[k];
            n++;
        }
        var rem = cu.mmvdIdx - base * 32;
        var step = rem / 4;
        var pos = rem & 3;
        var offset = (1 << step) << 2;
        if (ph.mmvdFullpel) {
            offset <<= 2;
        }
        var dx = pos == 0 ? offset : pos == 1 ? -offset : 0;
        var dy = pos == 2 ? offset : pos == 3 ? -offset : 0;
        var r0 = mmvdRef0[base];
        var r1 = mmvdRef1[base];
        if (r0 >= 0 && r1 >= 0) {
            var poc0 = sh.refPoc[0][r0];
            var poc1 = sh.refPoc[1][r1];
            var cur = sh.poc;
            int d0x = dx;
            int d0y = dy;
            int d1x;
            int d1y;
            var lt = sh.refLongTerm[0][r0] || sh.refLongTerm[1][r1];
            if (poc0 - cur == poc1 - cur) {
                d1x = d0x;
                d1y = d0y;
            } else if (Math.abs(poc1 - cur) > Math.abs(poc0 - cur)) {
                d1x = dx;
                d1y = dy;
                if (lt) {
                    if ((poc1 - cur) * (poc0 - cur) > 0) {
                        d0x = d1x;
                        d0y = d1y;
                    } else {
                        d0x = -d1x;
                        d0y = -d1y;
                    }
                } else {
                    var scale = distScale(cur, poc0, cur, poc1);
                    d0x = scaleComponent(scale, d1x);
                    d0y = scaleComponent(scale, d1y);
                }
            } else {
                if (lt) {
                    if ((poc1 - cur) * (poc0 - cur) > 0) {
                        d1x = d0x;
                        d1y = d0y;
                    } else {
                        d1x = -d0x;
                        d1y = -d0y;
                    }
                } else {
                    var scale = distScale(cur, poc1, cur, poc0);
                    d1x = scaleComponent(scale, d0x);
                    d1y = scaleComponent(scale, d0y);
                }
            }
            cu.interDir = 3;
            cu.mvx[0][0] = mmvdMv[base][0] + d0x;
            cu.mvy[0][0] = mmvdMv[base][1] + d0y;
            cu.refIdx[0] = r0;
            cu.mvx[1][0] = mmvdMv[base][2] + d1x;
            cu.mvy[1][0] = mmvdMv[base][3] + d1y;
            cu.refIdx[1] = r1;
        } else if (r0 >= 0) {
            cu.interDir = 1;
            cu.mvx[0][0] = mmvdMv[base][0] + dx;
            cu.mvy[0][0] = mmvdMv[base][1] + dy;
            cu.refIdx[0] = r0;
            cu.mvx[1][0] = 0;
            cu.mvy[1][0] = 0;
            cu.refIdx[1] = -1;
        } else if (r1 >= 0) {
            cu.interDir = 2;
            cu.mvx[0][0] = 0;
            cu.mvy[0][0] = 0;
            cu.refIdx[0] = -1;
            cu.mvx[1][0] = mmvdMv[base][2] + dx;
            cu.mvy[1][0] = mmvdMv[base][3] + dy;
            cu.refIdx[1] = r1;
        } else {
            throw new MediaException.Malformed("vvc MMVD base candidate does not exist");
        }
        cu.imv = mmvdHpel[base] ? VvcCu.IMV_HALF : 0;
        cu.halfPel = mmvdHpel[base];
        cu.bcwIdx = mmvdDir[base] == 3 ? mmvdBcw[base] : 0;
        for (var l = 0; l < 2; l++) {
            if (cu.refIdx[l] >= 0) {
                cu.mvx[l][0] = clip18(cu.mvx[l][0]);
                cu.mvy[l][0] = clip18(cu.mvy[l][0]);
            }
        }
        restrictBiPred(cu);
        storeUniform(cu);
    }

    /**
     * Derives the motion of the two partitions of a geometric partitioning coding unit (8.5.7.2).
     *
     * @param cu the coding unit
     */
    private void deriveGpm(VvcCu cu) {
        mergeCandidates(cu, -1);
        var max = sps.maxNumMergeCand;
        var count = 0;
        var gList = gpmList;
        var gRef = gpmRef;
        var gMvx = gpmMvx;
        var gMvy = gpmMvy;
        for (var i = 0; i < max && count < MAX_CANDS; i++) {
            var parity = i & 1;
            if ((cDir[i] & (1 + parity)) != 0) {
                gList[count] = parity;
                gRef[count] = cRef[parity][i];
                gMvx[count] = cMvx[parity][i];
                gMvy[count] = cMvy[parity][i];
                count++;
                continue;
            }
            if ((cDir[i] & (2 - parity)) != 0) {
                gList[count] = 1 - parity;
                gRef[count] = cRef[1 - parity][i];
                gMvx[count] = cMvx[1 - parity][i];
                gMvy[count] = cMvy[1 - parity][i];
                count++;
            }
        }
        var i0 = cu.gpmIdx0;
        var i1 = cu.gpmIdx1;
        if (i0 >= count || i1 >= count) {
            throw new MediaException.Malformed("vvc GPM merge candidate does not exist");
        }
        cu.gpmList[0] = gList[i0];
        cu.gpmRef[0] = gRef[i0];
        cu.gpmMvx[0] = gMvx[i0];
        cu.gpmMvy[0] = gMvy[i0];
        cu.gpmList[1] = gList[i1];
        cu.gpmRef[1] = gRef[i1];
        cu.gpmMvx[1] = gMvx[i1];
        cu.gpmMvy[1] = gMvy[i1];
        cu.interDir = 3;
        cu.bcwIdx = 0;
        cu.imv = 0;
        storeGpm(cu);
    }

    /**
     * Stores the motion of a geometric partitioning coding unit per 4x4 block (8.5.7.3): each part's vector, and the
     * combination of both vectors on the blending band.
     *
     * @param cu the coding unit
     */
    private void storeGpm(VvcCu cu) {
        var split = cu.gpmSplit;
        var angle = VvcGpm.ANGLE[split];
        var distance = VvcGpm.DISTANCE[split];
        var flip = angle >= 13 && angle <= 27;
        var disX = angle;
        var disY = (disX + 8) % 32;
        var offsetX = -cu.w >> 1;
        var offsetY = -cu.h >> 1;
        if (distance > 0) {
            if (angle % 16 == 8 || (angle % 16 != 0 && cu.h >= cu.w)) {
                offsetY += angle < 16 ? (distance * cu.h) >> 3 : -((distance * cu.h) >> 3);
            } else {
                offsetX += angle < 16 ? (distance * cu.w) >> 3 : -((distance * cu.w) >> 3);
            }
        }
        var l0 = cu.gpmList[0];
        var l1 = cu.gpmList[1];
        int bRef0;
        int bRef1;
        int bx0;
        int by0;
        int bx1;
        int by1;
        if (l0 != l1) {
            var a = l0 == 0 ? 0 : 1;
            var b = 1 - a;
            bRef0 = cu.gpmRef[a];
            bx0 = cu.gpmMvx[a];
            by0 = cu.gpmMvy[a];
            bRef1 = cu.gpmRef[b];
            bx1 = cu.gpmMvx[b];
            by1 = cu.gpmMvy[b];
        } else if (l0 == 0) {
            bRef0 = cu.gpmRef[1];
            bx0 = cu.gpmMvx[1];
            by0 = cu.gpmMvy[1];
            bRef1 = -1;
            bx1 = 0;
            by1 = 0;
        } else {
            bRef0 = -1;
            bx0 = 0;
            by0 = 0;
            bRef1 = cu.gpmRef[1];
            bx1 = cu.gpmMvx[1];
            by1 = cu.gpmMvy[1];
        }
        var pic = f.pic;
        for (var y = 0; y < cu.h >> 2; y++) {
            var lookUpY = (((4 * y + offsetY) * 2) + 5) * VvcGpm.DIS[disY];
            for (var x = 0; x < cu.w >> 2; x++) {
                var motionIdx = (((4 * x + offsetX) * 2) + 5) * VvcGpm.DIS[disX] + lookUpY;
                var mask = Math.abs(motionIdx) < 32 ? 2 : (motionIdx <= 0 ? (flip ? 0 : 1) : (flip ? 1 : 0));
                var i4 = ((cu.y >> 2) + y) * f.width4 + (cu.x >> 2) + x;
                int r0;
                int r1;
                int mx0;
                int my0;
                int mx1;
                int my1;
                if (mask == 2) {
                    r0 = bRef0;
                    r1 = bRef1;
                    mx0 = bx0;
                    my0 = by0;
                    mx1 = bx1;
                    my1 = by1;
                } else {
                    var part = mask == 0 ? 0 : 1;
                    var list = cu.gpmList[part];
                    r0 = list == 0 ? cu.gpmRef[part] : -1;
                    r1 = list == 1 ? cu.gpmRef[part] : -1;
                    mx0 = list == 0 ? cu.gpmMvx[part] : 0;
                    my0 = list == 0 ? cu.gpmMvy[part] : 0;
                    mx1 = list == 1 ? cu.gpmMvx[part] : 0;
                    my1 = list == 1 ? cu.gpmMvy[part] : 0;
                }
                sref0[i4] = (byte) r0;
                sref1[i4] = (byte) r1;
                smvx0[i4] = mx0;
                smvy0[i4] = my0;
                smvx1[i4] = mx1;
                smvy1[i4] = my1;
                pic.ref0[i4] = (byte) r0;
                pic.ref1[i4] = (byte) r1;
                pic.mvx0[i4] = mx0;
                pic.mvy0[i4] = my0;
                pic.mvx1[i4] = mx1;
                pic.mvy1[i4] = my1;
                pic.slice4[i4] = (short) sliceIndex;
            }
        }
    }

    /**
     * Stores the uniform motion of a coding unit in both fields.
     *
     * @param cu the coding unit
     */
    void storeUniform(VvcCu cu) {
        var pic = f.pic;
        var r0 = cu.refIdx[0];
        var r1 = cu.refIdx[1];
        var mx0 = r0 >= 0 ? cu.mvx[0][0] : 0;
        var my0 = r0 >= 0 ? cu.mvy[0][0] : 0;
        var mx1 = r1 >= 0 ? cu.mvx[1][0] : 0;
        var my1 = r1 >= 0 ? cu.mvy[1][0] : 0;
        var x0 = cu.x >> 2;
        var x1 = Math.min(f.width4, (cu.x + cu.w) >> 2);
        var y1 = Math.min(f.height4, (cu.y + cu.h) >> 2);
        for (var y = cu.y >> 2; y < y1; y++) {
            var row = y * f.width4;
            Arrays.fill(sref0, row + x0, row + x1, (byte) r0);
            Arrays.fill(sref1, row + x0, row + x1, (byte) r1);
            Arrays.fill(smvx0, row + x0, row + x1, mx0);
            Arrays.fill(smvy0, row + x0, row + x1, my0);
            Arrays.fill(smvx1, row + x0, row + x1, mx1);
            Arrays.fill(smvy1, row + x0, row + x1, my1);
            Arrays.fill(pic.ref0, row + x0, row + x1, (byte) r0);
            Arrays.fill(pic.ref1, row + x0, row + x1, (byte) r1);
            Arrays.fill(pic.mvx0, row + x0, row + x1, mx0);
            Arrays.fill(pic.mvy0, row + x0, row + x1, my0);
            Arrays.fill(pic.mvx1, row + x0, row + x1, mx1);
            Arrays.fill(pic.mvy1, row + x0, row + x1, my1);
            Arrays.fill(pic.slice4, row + x0, row + x1, (short) sliceIndex);
        }
    }

    /**
     * Copies the spatial field of a coding unit into the picture field, for subblock motion that is not refined.
     *
     * @param cu the coding unit
     */
    private void copyToPicture(VvcCu cu) {
        var pic = f.pic;
        var x0 = cu.x >> 2;
        var n = Math.min(f.width4, (cu.x + cu.w) >> 2) - x0;
        var y1 = Math.min(f.height4, (cu.y + cu.h) >> 2);
        for (var y = cu.y >> 2; y < y1; y++) {
            var at = y * f.width4 + x0;
            System.arraycopy(sref0, at, pic.ref0, at, n);
            System.arraycopy(sref1, at, pic.ref1, at, n);
            System.arraycopy(smvx0, at, pic.mvx0, at, n);
            System.arraycopy(smvy0, at, pic.mvy0, at, n);
            System.arraycopy(smvx1, at, pic.mvx1, at, n);
            System.arraycopy(smvy1, at, pic.mvy1, at, n);
            Arrays.fill(pic.slice4, at, at + n, (short) sliceIndex);
        }
    }

    /**
     * Derives the vectors of an AMVP coding unit (8.5.2.8): the predictor of each list plus its difference, with the
     * AMVR rounding.
     *
     * @param cu the coding unit
     */
    private void deriveAmvp(VvcCu cu) {
        for (var l = 0; l < 2; l++) {
            if ((cu.interDir & (1 << l)) == 0 || sh.numRefIdxActive[l] == 0) {
                cu.refIdx[l] = -1;
                cu.mvx[l][0] = 0;
                cu.mvy[l][0] = 0;
                continue;
            }
            if (cu.refIdx[l] < 0 || cu.refIdx[l] >= sh.numRefIdxActive[l]) {
                throw new MediaException.Malformed("vvc reference index out of range");
            }
            var mvdx = cu.mvx[l][0];
            var mvdy = cu.mvy[l][0];
            if (cu.imv != 0) {
                if (!(l == 1 && ph.mvdL1Zero && cu.interDir == 3)) {
                    var s = amvrShift(cu.imv);
                    mvdx <<= s;
                    mvdy <<= s;
                }
            } else {
                mvdx <<= 2;
                mvdy <<= 2;
            }
            amvpCandidates(cu, l, cu.refIdx[l]);
            var idx = cu.mvpIdx[l];
            cu.mvx[l][0] = wrap18(amvpX[idx] + mvdx);
            cu.mvy[l][0] = wrap18(amvpY[idx] + mvdy);
        }
    }

    /**
     * Builds the AMVP candidate list of a list and reference index (8.5.2.8) into {@link #amvpX} and
     * {@link #amvpY}.
     *
     * @param cu the coding unit
     * @param list the reference picture list, 0 or 1
     * @param refIdx the reference index
     */
    private void amvpCandidates(VvcCu cu, int list, int refIdx) {
        amvpCount = 0;
        var xR = cu.x + cu.w - 1;
        var yB = cu.y + cu.h - 1;
        if (!addAmvpSpatial(cu, list, refIdx, cu.x - 1, yB + 1)) {
            addAmvpSpatial(cu, list, refIdx, cu.x - 1, yB);
        }
        if (!addAmvpSpatial(cu, list, refIdx, xR + 1, cu.y - 1)) {
            if (!addAmvpSpatial(cu, list, refIdx, xR, cu.y - 1)) {
                addAmvpSpatial(cu, list, refIdx, cu.x - 1, cu.y - 1);
            }
        }
        var shift = amvrShift(cu.imv);
        for (var i = 0; i < amvpCount; i++) {
            amvpX[i] = roundTo(amvpX[i], shift);
            amvpY[i] = roundTo(amvpY[i], shift);
        }
        if (amvpCount == 2 && amvpX[0] == amvpX[1] && amvpY[0] == amvpY[1]) {
            amvpCount = 1;
        }
        if (ph.temporalMvp && amvpCount < 2 && cu.w + cu.h > 12) {
            var c0 = colPositionC0(cu);
            if ((c0 && colocated(cu, list, cu.x + cu.w, cu.y + cu.h, refIdx, false))
                    || colocated(cu, list, cu.x + (cu.w >> 1), cu.y + (cu.h >> 1), refIdx, false)) {
                amvpX[amvpCount] = roundTo(colMv[0], shift);
                amvpY[amvpCount] = roundTo(colMv[1], shift);
                amvpCount++;
            }
        }
        if (amvpCount < 2) {
            var curRefPoc = sh.refPoc[list][refIdx];
            var count = Math.min(4, hmvpCount);
            for (var m = 1; m <= count && amvpCount < 2; m++) {
                var h = m - 1;
                for (var src = 0; src < 2; src++) {
                    var l = src == 0 ? list : 1 - list;
                    var r = hmvpRef[h][l];
                    if (r >= 0 && curRefPoc == sh.refPoc[l][r]) {
                        amvpX[amvpCount] = roundTo(hmvpMvx[h][l], shift);
                        amvpY[amvpCount] = roundTo(hmvpMvy[h][l], shift);
                        amvpCount++;
                        if (amvpCount >= 2) {
                            break;
                        }
                    }
                }
            }
        }
        while (amvpCount < 2) {
            amvpX[amvpCount] = 0;
            amvpY[amvpCount] = 0;
            amvpCount++;
        }
        for (var i = 0; i < 2; i++) {
            amvpX[i] = roundTo(amvpX[i], shift);
            amvpY[i] = roundTo(amvpY[i], shift);
        }
    }

    /**
     * Appends the vector of a spatial neighbor that references the same picture as a list and reference index, from
     * either of its lists.
     *
     * @param cu the coding unit
     * @param list the reference picture list, 0 or 1
     * @param refIdx the reference index
     * @param x the luma column of the neighbor
     * @param y the luma row of the neighbor
     * @return whether a candidate was added
     */
    private boolean addAmvpSpatial(VvcCu cu, int list, int refIdx, int x, int y) {
        var n = neighbor(x, y, cu);
        if (n == null || n.predMode != VvcCu.MODE_INTER) {
            return false;
        }
        var i4 = idx4(x, y);
        var curRefPoc = sh.refPoc[list][refIdx];
        for (var src = 0; src < 2; src++) {
            var l = src == 0 ? list : 1 - list;
            int r = l == 0 ? sref0[i4] : sref1[i4];
            if (r >= 0 && curRefPoc == sh.refPoc[l][r]) {
                amvpX[amvpCount] = l == 0 ? smvx0[i4] : smvx1[i4];
                amvpY[amvpCount] = l == 0 ? smvy0[i4] : smvy1[i4];
                amvpCount++;
                return true;
            }
        }
        return false;
    }

    /**
     * Derives the block vector of an IBC coding unit (8.6.2): its merge candidate, or the predictor plus the
     * difference.
     *
     * @param cu the coding unit
     */
    private void deriveIbc(VvcCu cu) {
        if (cu.merge) {
            ibcCandidates(cu, cu.mergeIdx);
            if (cu.mergeIdx >= cCount) {
                throw new MediaException.Malformed("vvc IBC merge candidate does not exist");
            }
            cu.mvx[0][0] = cMvx[0][cu.mergeIdx];
            cu.mvy[0][0] = cMvy[0][cu.mergeIdx];
        } else {
            ibcCandidates(cu, 1);
            var shift = cu.imv == 2 ? 6 : 4;
            var idx = cu.mvpIdx[0];
            var px = roundTo(cMvx[0][idx], shift);
            var py = roundTo(cMvy[0][idx], shift);
            var mvdShift = cu.imv == 2 ? 6 : 4;
            cu.mvx[0][0] = wrap18(px + (cu.mvx[0][0] << mvdShift));
            cu.mvy[0][0] = wrap18(py + (cu.mvy[0][0] << mvdShift));
        }
        cu.interDir = 1;
        cu.refIdx[0] = -1;
        cu.refIdx[1] = -1;
        cu.bcwIdx = 0;
        var bvx = cu.mvx[0][0];
        var bvy = cu.mvy[0][0];
        var pic = f.pic;
        var x0 = cu.x >> 2;
        var x1 = Math.min(f.width4, (cu.x + cu.w) >> 2);
        var y1 = Math.min(f.height4, (cu.y + cu.h) >> 2);
        for (var y = cu.y >> 2; y < y1; y++) {
            var row = y * f.width4;
            Arrays.fill(sref0, row + x0, row + x1, (byte) -1);
            Arrays.fill(sref1, row + x0, row + x1, (byte) -1);
            Arrays.fill(f.bvx, row + x0, row + x1, bvx);
            Arrays.fill(f.bvy, row + x0, row + x1, bvy);
            Arrays.fill(pic.ref0, row + x0, row + x1, (byte) -1);
            Arrays.fill(pic.ref1, row + x0, row + x1, (byte) -1);
            Arrays.fill(pic.slice4, row + x0, row + x1, (short) sliceIndex);
        }
    }

    /**
     * Builds the IBC merge and block vector predictor list (8.6.2.2) up to the candidate with index {@code stop}.
     *
     * @param cu the coding unit
     * @param stop the index of the last candidate needed
     */
    private void ibcCandidates(VvcCu cu, int stop) {
        var max = sps.maxNumIbcMergeCand;
        var listSize = Math.max(max, 2);
        for (var i = 0; i < listSize; i++) {
            cDir[i] = 0;
            cRef[0][i] = -1;
            cRef[1][i] = -1;
            cMvx[0][i] = 0;
            cMvy[0][i] = 0;
            cBcw[i] = 0;
            cHpel[i] = false;
        }
        cCount = max;
        var cnt = 0;
        var xR = cu.x + cu.w - 1;
        var yB = cu.y + cu.h - 1;
        var gt4x4 = cu.w * cu.h > 16;
        var left = neighbor(cu.x - 1, yB, cu);
        var a1 = left != null && left.predMode == VvcCu.MODE_IBC;
        var iA1 = idx4(cu.x - 1, yB);
        if (gt4x4 && a1) {
            cDir[cnt] = 1;
            cRef[0][cnt] = 0;
            cMvx[0][cnt] = f.bvx[iA1];
            cMvy[0][cnt] = f.bvy[iA1];
            if (stop == cnt) {
                cCount = Math.max(cCount, cnt + 1);
                return;
            }
            cnt++;
        }
        if (cnt == max) {
            return;
        }
        var above = neighbor(xR, cu.y - 1, cu);
        var b1 = above != null && above.predMode == VvcCu.MODE_IBC;
        if (gt4x4 && b1) {
            var iB1 = idx4(xR, cu.y - 1);
            if (!a1 || above.slice != left.slice || f.bvx[iB1] != f.bvx[iA1] || f.bvy[iB1] != f.bvy[iA1]) {
                cDir[cnt] = 1;
                cRef[0][cnt] = 0;
                cMvx[0][cnt] = f.bvx[iB1];
                cMvy[0][cnt] = f.bvy[iB1];
                if (stop == cnt) {
                    cCount = Math.max(cCount, cnt + 1);
                    return;
                }
                cnt++;
            }
        }
        if (cnt == max) {
            return;
        }
        var spatial = cnt;
        if (cnt != max) {
            var counter = historyCounter;
            counter[0] = cnt;
            var saveB = bSlice;
            bSlice = false;
            var found = historyCandidates(stop, max, counter, spatial, true, gt4x4);
            bSlice = saveB;
            cnt = counter[0];
            if (found) {
                cCount = Math.max(cCount, cnt + 1);
                return;
            }
        }
        while (cnt < max) {
            cMvx[0][cnt] = 0;
            cMvy[0][cnt] = 0;
            cRef[0][cnt] = -1;
            cDir[cnt] = 1;
            cnt++;
            if (stop == cnt) {
                cCount = Math.max(cCount, cnt);
                return;
            }
        }
        cCount = Math.max(cnt, 2);
    }

    /**
     * Derives the control point vectors of an affine AMVP coding unit (8.5.5.7): the predictors of each list plus their
     * differences.
     *
     * @param cu the coding unit
     */
    private void deriveAffineAmvp(VvcCu cu) {
        for (var l = 0; l < 2; l++) {
            if ((cu.interDir & (1 << l)) == 0 || sh.numRefIdxActive[l] == 0) {
                cu.refIdx[l] = -1;
                continue;
            }
            if (cu.refIdx[l] < 0 || cu.refIdx[l] >= sh.numRefIdxActive[l]) {
                throw new MediaException.Malformed("vvc reference index out of range");
            }
            affineAmvpCandidates(cu, l, cu.refIdx[l]);
            var idx = cu.mvpIdx[l];
            var imvShift = cu.imv == 2 ? 2 : 0;
            var mvd0x = cu.mvx[l][0] << imvShift;
            var mvd0y = cu.mvy[l][0] << imvShift;
            var mvd1x = cu.mvx[l][1] << imvShift;
            var mvd1y = cu.mvy[l][1] << imvShift;
            var mvd2x = cu.mvx[l][2] << imvShift;
            var mvd2y = cu.mvy[l][2] << imvShift;
            var ltx = affX[0][idx] + mvd0x;
            var lty = affY[0][idx] + mvd0y;
            var rtx = affX[1][idx] + mvd1x + mvd0x;
            var rty = affY[1][idx] + mvd1y + mvd0y;
            var lbx = 0;
            var lby = 0;
            if (cu.imv != 1) {
                ltx <<= 2;
                lty <<= 2;
                rtx <<= 2;
                rty <<= 2;
            }
            if (cu.sixParam) {
                lbx = affX[2][idx] + mvd2x + mvd0x;
                lby = affY[2][idx] + mvd2y + mvd0y;
                if (cu.imv != 1) {
                    lbx <<= 2;
                    lby <<= 2;
                }
            }
            setAffineField(cu, l, ltx, lty, rtx, rty, lbx, lby, true);
        }
        for (var l = 0; l < 2; l++) {
            if (cu.refIdx[l] < 0) {
                clearList(cu, l);
            }
        }
        copyToPicture(cu);
    }

    /**
     * Marks a list as unused in the spatial field of a coding unit.
     *
     * @param cu the coding unit
     * @param l the list
     */
    private void clearList(VvcCu cu, int l) {
        var x0 = cu.x >> 2;
        var x1 = Math.min(f.width4, (cu.x + cu.w) >> 2);
        var y1 = Math.min(f.height4, (cu.y + cu.h) >> 2);
        for (var y = cu.y >> 2; y < y1; y++) {
            var row = y * f.width4;
            Arrays.fill(l == 0 ? sref0 : sref1, row + x0, row + x1, (byte) -1);
            Arrays.fill(l == 0 ? smvx0 : smvx1, row + x0, row + x1, 0);
            Arrays.fill(l == 0 ? smvy0 : smvy1, row + x0, row + x1, 0);
        }
        for (var k = 0; k < 3; k++) {
            cu.mvx[l][k] = 0;
            cu.mvy[l][k] = 0;
        }
    }

    /**
     * Derives the 4x4 subblock vectors of an affine coding unit from its control point vectors (8.5.5.9) into the
     * spatial field, and keeps the control points in the coding unit.
     *
     * @param cu the coding unit
     * @param list the reference picture list, 0 or 1
     * @param ltx the horizontal top-left control point vector
     * @param lty the vertical top-left control point vector
     * @param rtx the horizontal top-right control point vector
     * @param rty the vertical top-right control point vector
     * @param lbx the horizontal bottom-left control point vector
     * @param lby the vertical bottom-left control point vector
     * @param clip whether the subblock vectors are clamped to 18 bits
     */
    private void setAffineField(VvcCu cu, int list, int ltx, int lty, int rtx, int rty, int lbx, int lby,
            boolean clip) {
        if (clip) {
            ltx = wrap18(ltx);
            lty = wrap18(lty);
            rtx = wrap18(rtx);
            rty = wrap18(rty);
            if (cu.sixParam) {
                lbx = wrap18(lbx);
                lby = wrap18(lby);
            }
        }
        var log2W = Integer.numberOfTrailingZeros(cu.w);
        var log2H = Integer.numberOfTrailingZeros(cu.h);
        var dHorX = (rtx - ltx) * (1 << (7 - log2W));
        var dHorY = (rty - lty) * (1 << (7 - log2W));
        int dVerX;
        int dVerY;
        if (cu.sixParam) {
            dVerX = (lbx - ltx) * (1 << (7 - log2H));
            dVerY = (lby - lty) * (1 << (7 - log2H));
        } else {
            dVerX = -dHorY;
            dVerY = dHorX;
        }
        var baseX = ltx * (1 << 7);
        var baseY = lty * (1 << 7);
        var spread = spreadOverLimit(dHorX, dHorY, dVerX, dVerY, cu.interDir);
        var flbx = 0;
        var flby = 0;
        if (spread) {
            flbx = clip18(roundAffine(baseX + dHorX * (cu.w >> 1) + dVerX * (cu.h >> 1), 7));
            flby = clip18(roundAffine(baseY + dHorY * (cu.w >> 1) + dVerY * (cu.h >> 1), 7));
        }
        var mx = list == 0 ? smvx0 : smvx1;
        var my = list == 0 ? smvy0 : smvy1;
        var refs = list == 0 ? sref0 : sref1;
        for (var h = 0; h < cu.h >> 2; h++) {
            for (var w = 0; w < cu.w >> 2; w++) {
                var i4 = ((cu.y >> 2) + h) * f.width4 + (cu.x >> 2) + w;
                if (spread) {
                    mx[i4] = flbx;
                    my[i4] = flby;
                } else {
                    var px = baseX + dHorX * (2 + (w << 2)) + dVerX * (2 + (h << 2));
                    var py = baseY + dHorY * (2 + (w << 2)) + dVerY * (2 + (h << 2));
                    mx[i4] = clip18(roundAffine(px, 7));
                    my[i4] = clip18(roundAffine(py, 7));
                }
                refs[i4] = (byte) cu.refIdx[list];
            }
        }
        cu.mvx[list][0] = ltx;
        cu.mvy[list][0] = lty;
        cu.mvx[list][1] = rtx;
        cu.mvy[list][1] = rty;
        cu.mvx[list][2] = lbx;
        cu.mvy[list][2] = lby;
    }

    /**
     * {@return whether the subblock vectors of an affine unit spread beyond the reference fetch limit (8.5.5.9), in
     * which case every subblock uses the vector at the unit's center}
     *
     * @param a the first horizontal gradient parameter
     * @param b the second horizontal gradient parameter
     * @param c the first vertical gradient parameter
     * @param d the second vertical gradient parameter
     * @param predType 1 for uni-prediction, 3 for bi-prediction
     */
    static boolean spreadOverLimit(int a, int b, int c, int d, int predType) {
        var s4 = 4 << 11;
        var tap = 6;
        if (predType == 3) {
            var w = Math.max(Math.max(0, 4 * a + s4), Math.max(4 * c, 4 * a + 4 * c + s4))
                    - Math.min(Math.min(0, 4 * a + s4), Math.min(4 * c, 4 * a + 4 * c + s4));
            var h = Math.max(Math.max(0, 4 * b), Math.max(4 * d + s4, 4 * b + 4 * d + s4))
                    - Math.min(Math.min(0, 4 * b), Math.min(4 * d + s4, 4 * b + 4 * d + s4));
            w = (w >> 11) + tap + 3;
            h = (h >> 11) + tap + 3;
            return w * h > (tap + 9) * (tap + 9);
        }
        var w = Math.max(0, 4 * a + s4) - Math.min(0, 4 * a + s4);
        var h = Math.max(0, 4 * b) - Math.min(0, 4 * b);
        w = (w >> 11) + tap + 3;
        h = (h >> 11) + tap + 3;
        if (w * h > (tap + 9) * (tap + 5)) {
            return true;
        }
        w = Math.max(0, 4 * c) - Math.min(0, 4 * c);
        h = Math.max(0, 4 * d + s4) - Math.min(0, 4 * d + s4);
        w = (w >> 11) + tap + 3;
        h = (h >> 11) + tap + 3;
        return w * h > (tap + 5) * (tap + 9);
    }

    /**
     * Derives the control point vectors of the current coding unit from an affine neighbor
     * ({@code xInheritedAffineMv}, 8.5.5.5) into {@link #cpX} and {@link #cpY}.
     *
     * @param cu the current coding unit
     * @param six whether the current unit uses the six-parameter model
     * @param nb the affine neighbor
     * @param list the list of the neighbor read
     */
    private void inheritAffine(VvcCu cu, boolean six, VvcCu nb, int list) {
        var posNx = nb.x;
        var posNy = nb.y;
        var ltx = nb.mvx[list][0];
        var lty = nb.mvy[list][0];
        var rtx = nb.mvx[list][1];
        var rty = nb.mvy[list][1];
        var lbx = nb.mvx[list][2];
        var lby = nb.mvy[list][2];
        var topCtu = false;
        if ((posNy + nb.h) % f.ctuSize == 0 && posNy + nb.h == cu.y) {
            var iLb = idx4(nb.x, nb.y + nb.h - 1);
            var iRb = idx4(nb.x + nb.w - 1, nb.y + nb.h - 1);
            ltx = list == 0 ? smvx0[iLb] : smvx1[iLb];
            lty = list == 0 ? smvy0[iLb] : smvy1[iLb];
            rtx = list == 0 ? smvx0[iRb] : smvx1[iRb];
            rty = list == 0 ? smvy0[iRb] : smvy1[iRb];
            posNy += nb.h;
            topCtu = true;
        }
        var log2W = Integer.numberOfTrailingZeros(nb.w);
        var log2H = Integer.numberOfTrailingZeros(nb.h);
        var dHorX = (rtx - ltx) * (1 << (7 - log2W));
        var dHorY = (rty - lty) * (1 << (7 - log2W));
        int dVerX;
        int dVerY;
        if (nb.sixParam && !topCtu) {
            dVerX = (lbx - ltx) * (1 << (7 - log2H));
            dVerY = (lby - lty) * (1 << (7 - log2H));
        } else {
            dVerX = -dHorY;
            dVerY = dHorX;
        }
        var sx = ltx * (1 << 7);
        var sy = lty * (1 << 7);
        cpX[0] = clip18(roundAffine(sx + dHorX * (cu.x - posNx) + dVerX * (cu.y - posNy), 7));
        cpY[0] = clip18(roundAffine(sy + dHorY * (cu.x - posNx) + dVerY * (cu.y - posNy), 7));
        cpX[1] = clip18(roundAffine(sx + dHorX * (cu.x + cu.w - posNx) + dVerX * (cu.y - posNy), 7));
        cpY[1] = clip18(roundAffine(sy + dHorY * (cu.x + cu.w - posNx) + dVerY * (cu.y - posNy), 7));
        if (six) {
            cpX[2] = clip18(roundAffine(sx + dHorX * (cu.x - posNx) + dVerX * (cu.y + cu.h - posNy), 7));
            cpY[2] = clip18(roundAffine(sy + dHorY * (cu.x - posNx) + dVerY * (cu.y + cu.h - posNy), 7));
        } else {
            cpX[2] = 0;
            cpY[2] = 0;
        }
    }

    /**
     * {@return whether a neighbor provides an inherited affine candidate}
     *
     * @param n the neighbor, or {@code null}
     */
    private static boolean isAffineSource(VvcCu n) {
        return n != null && n.affine && !n.sbtmvp && n.predMode == VvcCu.MODE_INTER;
    }

    /**
     * Appends the inherited affine AMVP candidate of a neighbor that references the same picture as a list and
     * reference index.
     *
     * @param cu the coding unit
     * @param list the reference picture list, 0 or 1
     * @param refIdx the reference index
     * @param x the luma column of the neighbor
     * @param y the luma row of the neighbor
     * @return whether a candidate was added
     */
    private boolean addAffineAmvpInherited(VvcCu cu, int list, int refIdx, int x, int y) {
        var nb = neighbor(x, y, cu);
        if (!isAffineSource(nb)) {
            return false;
        }
        var i4 = idx4(x, y);
        var curRefPoc = sh.refPoc[list][refIdx];
        for (var src = 0; src < 2; src++) {
            var l = src == 0 ? list : 1 - list;
            int r = l == 0 ? sref0[i4] : sref1[i4];
            if ((nb.interDir & (l + 1)) == 0 || r < 0 || sh.refPoc[l][r] != curRefPoc) {
                continue;
            }
            inheritAffine(cu, cu.sixParam, nb, l);
            var shift = cu.imv == 0 ? 2 : cu.imv == 2 ? 4 : 0;
            for (var k = 0; k < (cu.sixParam ? 3 : 2); k++) {
                affX[k][affCount] = roundTo(cpX[k], shift);
                affY[k][affCount] = roundTo(cpY[k], shift);
            }
            if (!cu.sixParam) {
                affX[2][affCount] = 0;
                affY[2][affCount] = 0;
            }
            affCount++;
            return true;
        }
        return false;
    }

    /**
     * Builds the affine AMVP list (8.5.5.7) into {@link #affX} and {@link #affY}, in quarter sample precision unless
     * the unit uses 1/16 sample AMVR.
     *
     * @param cu the coding unit
     * @param list the reference picture list, 0 or 1
     * @param refIdx the reference index
     */
    private void affineAmvpCandidates(VvcCu cu, int list, int refIdx) {
        affCount = 0;
        var xR = cu.x + cu.w - 1;
        var yB = cu.y + cu.h - 1;
        if (!addAffineAmvpInherited(cu, list, refIdx, cu.x - 1, yB + 1)) {
            addAffineAmvpInherited(cu, list, refIdx, cu.x - 1, yB);
        }
        if (!addAffineAmvpInherited(cu, list, refIdx, xR + 1, cu.y - 1)) {
            if (!addAffineAmvpInherited(cu, list, refIdx, xR, cu.y - 1)) {
                addAffineAmvpInherited(cu, list, refIdx, cu.x - 1, cu.y - 1);
            }
        }
        if (affCount < 2) {
            var pattern = 0;
            var corner = amvpCorner;
            Arrays.fill(corner, 0);
            amvpCount = 0;
            addAmvpSpatial(cu, list, refIdx, cu.x - 1, cu.y - 1);
            if (amvpCount < 1) {
                addAmvpSpatial(cu, list, refIdx, cu.x, cu.y - 1);
            }
            if (amvpCount < 1) {
                addAmvpSpatial(cu, list, refIdx, cu.x - 1, cu.y);
            }
            if (amvpCount > 0) {
                pattern |= 1;
                corner[0] = amvpX[0];
                corner[1] = amvpY[0];
            }
            amvpCount = 0;
            addAmvpSpatial(cu, list, refIdx, xR, cu.y - 1);
            if (amvpCount < 1) {
                addAmvpSpatial(cu, list, refIdx, xR + 1, cu.y - 1);
            }
            if (amvpCount > 0) {
                pattern |= 2;
                corner[2] = amvpX[0];
                corner[3] = amvpY[0];
            }
            amvpCount = 0;
            addAmvpSpatial(cu, list, refIdx, cu.x - 1, yB);
            if (amvpCount < 1) {
                addAmvpSpatial(cu, list, refIdx, cu.x - 1, yB + 1);
            }
            if (amvpCount > 0) {
                pattern |= 4;
                corner[4] = amvpX[0];
                corner[5] = amvpY[0];
            }
            var shift = cu.imv == 0 ? 2 : cu.imv == 2 ? 4 : 0;
            for (var k = 0; k < 6; k++) {
                corner[k] = roundTo(corner[k], shift);
            }
            if (pattern == 7 || (pattern == 3 && !cu.sixParam)) {
                for (var k = 0; k < 3; k++) {
                    affX[k][affCount] = corner[2 * k];
                    affY[k][affCount] = corner[2 * k + 1];
                }
                affCount++;
            }
            if (affCount < 2) {
                for (var i = 2; i >= 0 && affCount < 2; i--) {
                    if ((pattern & (1 << i)) != 0) {
                        for (var k = 0; k < 3; k++) {
                            affX[k][affCount] = corner[2 * i];
                            affY[k][affCount] = corner[2 * i + 1];
                        }
                        affCount++;
                    }
                }
                if (affCount < 2 && ph.temporalMvp) {
                    var c0 = colPositionC0(cu);
                    if ((c0 && colocated(cu, list, cu.x + cu.w, cu.y + cu.h, refIdx, false))
                            || colocated(cu, list, cu.x + (cu.w >> 1), cu.y + (cu.h >> 1), refIdx, false)) {
                        var tx = roundTo(colMv[0], shift);
                        var ty = roundTo(colMv[1], shift);
                        for (var k = 0; k < 3; k++) {
                            affX[k][affCount] = tx;
                            affY[k][affCount] = ty;
                        }
                        affCount++;
                    }
                }
                while (affCount < 2) {
                    for (var k = 0; k < 3; k++) {
                        affX[k][affCount] = 0;
                        affY[k][affCount] = 0;
                    }
                    affCount++;
                }
            }
        }
        for (var i = 0; i < affCount; i++) {
            if (cu.imv != 1) {
                for (var k = 0; k < 3; k++) {
                    affX[k][i] = shiftRound(affX[k][i], 2);
                    affY[k][i] = shiftRound(affY[k][i], 2);
                }
            }
        }
    }

    /**
     * Derives the motion of a subblock merge coding unit (8.5.5.2): its affine or subblock-based temporal candidate.
     *
     * @param cu the coding unit
     */
    private void deriveAffineMerge(VvcCu cu) {
        var idx = cu.mergeIdx;
        affineMergeCandidates(cu, idx);
        if (idx >= maxNumSubblockMergeCand) {
            throw new MediaException.Malformed("vvc subblock merge candidate does not exist");
        }
        cu.interDir = aDir[idx];
        cu.sixParam = aSix[idx];
        cu.bcwIdx = aBcw[idx];
        if (aSbtmvp[idx]) {
            cu.sbtmvp = true;
            cu.refIdx[0] = aRef[0][idx];
            cu.refIdx[1] = aRef[1][idx];
            cu.mvx[0][0] = aMvx[0][idx][0];
            cu.mvy[0][0] = aMvy[0][idx][0];
            cu.mvx[1][0] = aMvx[1][idx][0];
            cu.mvy[1][0] = aMvy[1][idx][0];
            var w4 = cu.w >> 2;
            for (var y = 0; y < cu.h >> 2; y++) {
                for (var x = 0; x < w4; x++) {
                    var i4 = ((cu.y >> 2) + y) * f.width4 + (cu.x >> 2) + x;
                    var s = y * w4 + x;
                    sref0[i4] = sbRef0[s];
                    sref1[i4] = sbRef1[s];
                    smvx0[i4] = sbMvx0[s];
                    smvy0[i4] = sbMvy0[s];
                    smvx1[i4] = sbMvx1[s];
                    smvy1[i4] = sbMvy1[s];
                }
            }
        } else {
            for (var l = 0; l < 2; l++) {
                if (sh.numRefIdxActive[l] > 0) {
                    cu.refIdx[l] = aRef[l][idx];
                    setAffineField(cu, l, aMvx[l][idx][0], aMvy[l][idx][0], aMvx[l][idx][1], aMvy[l][idx][1],
                            aMvx[l][idx][2], aMvy[l][idx][2], false);
                } else {
                    cu.refIdx[l] = -1;
                }
            }
            for (var l = 0; l < 2; l++) {
                if (cu.refIdx[l] < 0) {
                    clearList(cu, l);
                }
            }
        }
        copyToPicture(cu);
    }

    /**
     * Sets the prediction parameters of a subblock merge candidate whose control points {@link #cpX} and {@link #cpY}
     * hold.
     *
     * @param n the candidate index
     * @param dir the prediction direction
     * @param r0 the list 0 reference index
     * @param r1 the list 1 reference index
     * @param six whether the candidate uses the six-parameter model
     * @param bcw the BCW index
     */
    private void setAffineCandidate(int n, int dir, int r0, int r1, boolean six, int bcw) {
        aDir[n] = dir;
        aRef[0][n] = r0;
        aRef[1][n] = r1;
        aSix[n] = six;
        aBcw[n] = bcw;
        aSbtmvp[n] = false;
    }

    /**
     * Builds the subblock merge candidate list (8.5.5.2) up to the candidate with index {@code stop}.
     *
     * @param cu the coding unit
     * @param stop the index of the last candidate needed
     */
    private void affineMergeCandidates(VvcCu cu, int stop) {
        var max = maxNumSubblockMergeCand;
        for (var i = 0; i < MAX_CANDS; i++) {
            for (var k = 0; k < 3; k++) {
                aMvx[0][i][k] = 0;
                aMvy[0][i][k] = 0;
                aMvx[1][i][k] = 0;
                aMvy[1][i][k] = 0;
            }
            setAffineCandidate(i, 0, -1, -1, false, 0);
        }
        aCount = 0;
        var xR = cu.x + cu.w - 1;
        var yB = cu.y + cu.h - 1;
        var irap = sh.nalType >= VvcNal.IDR_W_RADL && sh.nalType <= VvcNal.CRA;
        var subPu = sps.sbtmvp && !(sh.numRefIdxActive[0] > 0 && sh.poc == sh.refPoc[0][0] && irap);
        if (subPu && ph.temporalMvp) {
            var hasLeft = false;
            var left = neighbor(cu.x - 1, yB, cu);
            if (left != null && left.predMode == VvcCu.MODE_INTER && diffMer(cu, cu.x - 1, yB)) {
                var i4 = idx4(cu.x - 1, yB);
                sbtmvpDir[0] = (sref0[i4] >= 0 ? 1 : 0) | (sref1[i4] >= 0 ? 2 : 0);
                sbtmvpRef[0][0] = sref0[i4];
                sbtmvpMv[0][0] = smvx0[i4];
                sbtmvpMv[0][1] = smvy0[i4];
                sbtmvpRef[1][0] = bSlice ? sref1[i4] : -1;
                sbtmvpMv[1][0] = bSlice ? smvx1[i4] : 0;
                sbtmvpMv[1][1] = bSlice ? smvy1[i4] : 0;
                hasLeft = true;
            }
            if (subblockTemporal(cu, hasLeft, stop)) {
                aSbtmvp[aCount] = true;
                aCount++;
                if (aCount == stop + 1 || aCount == max) {
                    return;
                }
            }
        }
        if (sps.affine) {
            var npu = affineNeighbors;
            var num = 0;
            var lb = neighbor(cu.x - 1, yB + 1, cu);
            var l = neighbor(cu.x - 1, yB, cu);
            if (isAffineSource(lb) && diffMer(cu, cu.x - 1, yB + 1)) {
                npu[num++] = lb;
            } else if (isAffineSource(l) && diffMer(cu, cu.x - 1, yB)) {
                npu[num++] = l;
            }
            var ar = neighbor(xR + 1, cu.y - 1, cu);
            var a = neighbor(xR, cu.y - 1, cu);
            var al = neighbor(cu.x - 1, cu.y - 1, cu);
            if (isAffineSource(ar) && diffMer(cu, xR + 1, cu.y - 1)) {
                npu[num++] = ar;
            } else if (isAffineSource(a) && diffMer(cu, xR, cu.y - 1)) {
                npu[num++] = a;
            } else if (isAffineSource(al) && diffMer(cu, cu.x - 1, cu.y - 1)) {
                npu[num++] = al;
            }
            for (var i = 0; i < num; i++) {
                var nb = npu[i];
                var n = aCount;
                for (var k = 0; k < 3; k++) {
                    aMvx[0][n][k] = 0;
                    aMvy[0][n][k] = 0;
                    aMvx[1][n][k] = 0;
                    aMvy[1][n][k] = 0;
                }
                if (nb.interDir != 2) {
                    inheritAffine(cu, nb.sixParam, nb, 0);
                    for (var k = 0; k < 3; k++) {
                        aMvx[0][n][k] = cpX[k];
                        aMvy[0][n][k] = cpY[k];
                    }
                }
                if (bSlice && nb.interDir != 1) {
                    inheritAffine(cu, nb.sixParam, nb, 1);
                    for (var k = 0; k < 3; k++) {
                        aMvx[1][n][k] = cpX[k];
                        aMvy[1][n][k] = cpY[k];
                    }
                }
                setAffineCandidate(n, nb.interDir, nb.refIdx[0], nb.refIdx[1], nb.sixParam, nb.bcwIdx);
                if (aCount == stop) {
                    return;
                }
                aCount++;
                if (aCount == max) {
                    return;
                }
            }
            constructedAffine(cu, stop, max);
            if (aCount == max || (aCount != 0 && aCount - 1 == stop)) {
                return;
            }
        }
        while (aCount < max) {
            var n = aCount;
            for (var k = 0; k < 3; k++) {
                aMvx[0][n][k] = 0;
                aMvy[0][n][k] = 0;
                aMvx[1][n][k] = 0;
                aMvy[1][n][k] = 0;
            }
            setAffineCandidate(n, bSlice ? 3 : 1, 0, bSlice ? 0 : -1, false, 0);
            aCount++;
        }
    }

    /**
     * Appends the constructed affine candidates (8.5.5.6) built from the corner motion of the neighbors.
     *
     * @param cu the coding unit
     * @param stop the index of the last candidate needed
     * @param max the size of the list
     */
    private void constructedAffine(VvcCu cu, int stop, int max) {
        var xR = cu.x + cu.w - 1;
        var yB = cu.y + cu.h - 1;
        var avail = cornerAvail;
        var mRef = cornerRef;
        var mMv = cornerMv;
        var neighBcw = cornerBcw;
        Arrays.fill(avail, false);
        for (var k = 0; k < 4; k++) {
            Arrays.fill(mRef[k], 0);
            Arrays.fill(mMv[k], 0);
        }
        Arrays.fill(neighBcw, 0);
        if (!corner(cu, 0, cu.x - 1, cu.y - 1) && !corner(cu, 0, cu.x, cu.y - 1)) {
            corner(cu, 0, cu.x - 1, cu.y);
        }
        if (!corner(cu, 1, xR, cu.y - 1)) {
            corner(cu, 1, xR + 1, cu.y - 1);
        }
        if (!corner(cu, 2, cu.x - 1, yB)) {
            corner(cu, 2, cu.x - 1, yB + 1);
        }
        mRef[3][0] = -1;
        mRef[3][1] = -1;
        if (ph.temporalMvp) {
            var c0 = colPositionC0(cu);
            if (c0 && colocated(cu, 0, cu.x + cu.w, cu.y + cu.h, 0, false)) {
                mMv[3][0] = colMv[0];
                mMv[3][1] = colMv[1];
                mRef[3][0] = 0;
                avail[3] = true;
            }
            if (bSlice && c0 && colocated(cu, 1, cu.x + cu.w, cu.y + cu.h, 0, false)) {
                mMv[3][2] = colMv[0];
                mMv[3][3] = colMv[1];
                mRef[3][1] = 0;
                avail[3] = true;
            }
        }
        var start = sps.sixParamAffine ? 0 : 4;
        for (var m = start; m < 6; m++) {
            controlPointCandidate(cu, mRef, mMv, avail, AFFINE_MODELS[m], m == 3 ? neighBcw[1] : neighBcw[0], m);
            if (aCount != 0 && aCount - 1 == stop) {
                return;
            }
            if (aCount == max) {
                return;
            }
        }
    }

    /**
     * Takes the motion of a neighbor as a corner of the constructed affine candidates when it is inter coded and in
     * another merge estimation region, recording the BCW index of the top corners.
     *
     * @param cu the coding unit
     * @param k the corner: 0 top-left, 1 top-right, 2 bottom-left
     * @param x the luma column of the neighbor
     * @param y the luma row of the neighbor
     * @return whether the corner was taken
     */
    private boolean corner(VvcCu cu, int k, int x, int y) {
        var nb = neighbor(x, y, cu);
        if (nb == null || nb.predMode != VvcCu.MODE_INTER || !diffMer(cu, x, y)) {
            return false;
        }
        fillCorner(k, idx4(x, y), cornerRef, cornerMv);
        cornerAvail[k] = true;
        if (k < 2) {
            cornerBcw[k] = nb.bcwIdx;
        }
        return true;
    }

    /**
     * Records the motion of a 4x4 block of the spatial field as a corner of the constructed affine candidates.
     *
     * @param k the corner
     * @param i4 the index of the block
     * @param mRef the reference indices of each corner and list
     * @param mMv the vectors of each corner, horizontal and vertical of each list
     */
    private void fillCorner(int k, int i4, int[][] mRef, int[][] mMv) {
        mRef[k][0] = sref0[i4];
        mRef[k][1] = sref1[i4];
        mMv[k][0] = smvx0[i4];
        mMv[k][1] = smvy0[i4];
        mMv[k][2] = smvx1[i4];
        mMv[k][3] = smvy1[i4];
    }

    /**
     * Appends the constructed affine candidate of a combination of corners when every list it uses has the corners and
     * references it needs (8.5.5.6).
     *
     * @param cu the coding unit
     * @param mRef the reference indices of each corner and list
     * @param mMv the vectors of each corner
     * @param avail whether each corner is available
     * @param ver the corners of the combination
     * @param bcw the BCW index
     * @param modelIdx the combination index
     */
    private void controlPointCandidate(VvcCu cu, int[][] mRef, int[][] mMv, boolean[] avail, int[] ver, int bcw,
            int modelIdx) {
        for (var v : ver) {
            if (!avail[v]) {
                return;
            }
        }
        var dir = 0;
        var ref = cornerCandRef;
        ref[0] = -1;
        ref[1] = -1;
        for (var l = 0; l < 2; l++) {
            var ok = true;
            var r = mRef[ver[0]][l];
            for (var v : ver) {
                if (mRef[v][l] < 0 || mRef[v][l] != r) {
                    ok = false;
                    break;
                }
            }
            if (ok) {
                dir |= l + 1;
                ref[l] = r;
            }
        }
        if (dir == 0) {
            return;
        }
        var n = aCount;
        var shiftHtoW = 7 + Integer.numberOfTrailingZeros(cu.w) - Integer.numberOfTrailingZeros(cu.h);
        for (var l = 0; l < 2; l++) {
            var cx = cornerX;
            var cy = cornerY;
            Arrays.fill(cx, 0);
            Arrays.fill(cy, 0);
            if ((dir & (l + 1)) != 0) {
                for (var v : ver) {
                    cx[v] = mMv[v][2 * l];
                    cy[v] = mMv[v][2 * l + 1];
                }
                switch (modelIdx) {
                    case 1 -> {
                        cx[2] = clip18(cx[3] + cx[0] - cx[1]);
                        cy[2] = clip18(cy[3] + cy[0] - cy[1]);
                    }
                    case 2 -> {
                        cx[1] = clip18(cx[3] + cx[0] - cx[2]);
                        cy[1] = clip18(cy[3] + cy[0] - cy[2]);
                    }
                    case 3 -> {
                        cx[0] = clip18(cx[1] + cx[2] - cx[3]);
                        cy[0] = clip18(cy[1] + cy[2] - cy[3]);
                    }
                    case 5 -> {
                        var vx = (cx[0] * (1 << 7)) + ((cy[2] - cy[0]) * (1 << shiftHtoW));
                        var vy = (cy[0] * (1 << 7)) - ((cx[2] - cx[0]) * (1 << shiftHtoW));
                        cx[1] = clip18(roundAffine(vx, 7));
                        cy[1] = clip18(roundAffine(vy, 7));
                    }
                    default -> {
                    }
                }
            }
            for (var k = 0; k < 3; k++) {
                aMvx[l][n][k] = cx[k];
                aMvy[l][n][k] = cy[k];
            }
        }
        setAffineCandidate(n, dir, ref[0], ref[1], ver.length == 3, dir == 3 ? bcw : 0);
        aCount++;
    }

    /**
     * Clamps a collocated position to the CTU row of a coding unit and the area the temporal field covers (8.5.5.4),
     * storing the result in {@link #clipX} and {@link #clipY}.
     *
     * @param cu the coding unit
     * @param x the luma column
     * @param y the luma row
     */
    private void clipColPos(VvcCu cu, int x, int y) {
        var log = f.log2Ctu;
        var ctuX = (cu.x >> log) << log;
        var ctuY = (cu.y >> log) << log;
        var subpic = f.ctuSubpic[(cu.y >> log) * f.widthInCtus + (cu.x >> log)];
        int horMax;
        if (sps.subpicTreatedAsPic[subpic]) {
            horMax = Math.min(subpicRight(subpic), ctuX + f.ctuSize + 3);
        } else {
            horMax = Math.min(f.width - 1, ctuX + f.ctuSize + 3);
        }
        var horMin = Math.max(0, ctuX);
        var verMax = Math.min(f.height - 1, ctuY + f.ctuSize - 1);
        var verMin = Math.max(0, ctuY);
        clipX = Math.clamp(x, horMin, horMax);
        clipY = Math.clamp(y, verMin, verMax);
    }

    /**
     * {@return whether the collocated block at a luma position is inter coded}
     *
     * @param x the luma column
     * @param y the luma row
     */
    private boolean colInter(int x, int y) {
        var col = colPicture();
        if (col == null || x >= col.width || y >= col.height) {
            return false;
        }
        return (col.colFlags[(y >> 3) * col.width8 + (x >> 3)] & 3) != 0;
    }

    /**
     * Derives the subblock-based temporal merge candidate (8.5.5.3); fills the per subblock motion when the
     * candidate is the selected one.
     *
     * @param cu the coding unit
     * @param hasLeft whether the left neighbor A1 is available
     * @param stop the index of the last candidate needed
     * @return whether the candidate was added
     */
    private boolean subblockTemporal(VvcCu cu, boolean hasLeft, int stop) {
        var col = colPicture();
        if (col == null) {
            return false;
        }
        var tx = 0;
        var ty = 0;
        if (hasLeft) {
            if ((sbtmvpDir[0] & 1) != 0 && sh.refPics[0][sbtmvpRef[0][0]] == col) {
                tx = sbtmvpMv[0][0];
                ty = sbtmvpMv[0][1];
            } else if (bSlice && (sbtmvpDir[0] & 2) != 0 && sh.refPics[1][sbtmvpRef[1][0]] == col) {
                tx = sbtmvpMv[1][0];
                ty = sbtmvpMv[1][1];
            }
        }
        var intX = shiftRound(tx, 4);
        var intY = shiftRound(ty, 4);
        clipColPos(cu, cu.x + (cu.w >> 1) + intX, cu.y + (cu.h >> 1) + intY);
        var px = clipX & ~7;
        var py = clipY & ~7;
        var n = aCount;
        var found = false;
        if (colInter(px, py)) {
            var dir = 0;
            for (var l = 0; l < (bSlice ? 2 : 1); l++) {
                if (colocated(cu, l, px, py, 0, true)) {
                    aMvx[l][n][0] = colMv[0];
                    aMvy[l][n][0] = colMv[1];
                    aRef[l][n] = 0;
                    dir |= 1 << l;
                    found = true;
                } else {
                    aMvx[l][n][0] = 0;
                    aMvy[l][n][0] = 0;
                    aRef[l][n] = -1;
                }
            }
            if (!bSlice) {
                aRef[1][n] = -1;
            }
            aDir[n] = dir;
            aBcw[n] = 0;
            aSix[n] = false;
        }
        if (!found) {
            return false;
        }
        if (cu.mergeIdx > 0 || stop < 0) {
            return true;
        }
        var biRestricted = cu.w + cu.h <= 12;
        var w4 = cu.w >> 2;
        for (var y = cu.y; y < cu.y + cu.h; y += 8) {
            for (var x = cu.x; x < cu.x + cu.w; x += 8) {
                var r0 = -1;
                var r1 = -1;
                var mx0 = 0;
                var my0 = 0;
                var mx1 = 0;
                var my1 = 0;
                clipColPos(cu, x + 4 + intX, y + 4 + intY);
                var qx = clipX;
                var qy = clipY;
                var any = false;
                if (colInter(qx, qy)) {
                    for (var l = 0; l < (!biRestricted && bSlice ? 2 : 1); l++) {
                        if (colocated(cu, l, qx, qy, 0, true)) {
                            if (l == 0) {
                                r0 = 0;
                                mx0 = colMv[0];
                                my0 = colMv[1];
                            } else {
                                r1 = 0;
                                mx1 = colMv[0];
                                my1 = colMv[1];
                            }
                            any = true;
                        }
                    }
                }
                if (!any) {
                    mx0 = aMvx[0][n][0];
                    my0 = aMvy[0][n][0];
                    mx1 = aMvx[1][n][0];
                    my1 = aMvy[1][n][0];
                    r0 = aRef[0][n];
                    r1 = aRef[1][n];
                }
                if (biRestricted && r0 >= 0 && r1 >= 0) {
                    mx1 = 0;
                    my1 = 0;
                    r1 = -1;
                }
                for (var dy = 0; dy < 2; dy++) {
                    for (var dx = 0; dx < 2; dx++) {
                        var s = (((y - cu.y) >> 2) + dy) * w4 + ((x - cu.x) >> 2) + dx;
                        sbRef0[s] = (byte) r0;
                        sbRef1[s] = (byte) r1;
                        sbMvx0[s] = mx0;
                        sbMvy0[s] = my0;
                        sbMvx1[s] = mx1;
                        sbMvy1[s] = my1;
                    }
                }
            }
        }
        return true;
    }
}
