/* Ports libvpx (vp9/common/vp9_pred_common.h, vp9_pred_common.c). Copyright (c) 2010, The WebM Project authors; BSD
   3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.video.vp9.internal;

import static com.github.auties00.smalt.video.vp9.internal.Vp9Constants.ALTREF_FRAME;
import static com.github.auties00.smalt.video.vp9.internal.Vp9Constants.GOLDEN_FRAME;
import static com.github.auties00.smalt.video.vp9.internal.Vp9Constants.LAST_FRAME;
import static com.github.auties00.smalt.video.vp9.internal.Vp9Constants.SWITCHABLE_FILTERS;

/**
 * Derives the entropy contexts of the VP9 block-level symbols from the blocks above and to the
 * left of the current one.
 *
 * <p>An absent neighbor ({@code null}) lies outside the frame above, or outside the tile to the
 * left.
 *
 * @implNote This implementation follows libvpx's {@code vp9/common/vp9_pred_common.h} and
 * {@code vp9_pred_common.c}.
 */
final class Vp9PredContexts {
    /**
     * Prevents instantiation.
     */
    private Vp9PredContexts() {
        throw new AssertionError();
    }

    /**
     * Returns the context of the switchable interpolation filter
     * ({@code get_pred_context_switchable_interp}).
     *
     * @param above the block above, or {@code null}
     * @param left the block to the left, or {@code null}
     * @return the context, from 0 to 3
     */
    static int switchableInterp(ModeInfo above, ModeInfo left) {
        var leftType = left != null ? left.interpFilter : SWITCHABLE_FILTERS;
        var aboveType = above != null ? above.interpFilter : SWITCHABLE_FILTERS;
        if (leftType == aboveType) {
            return leftType;
        } else if (leftType == SWITCHABLE_FILTERS) {
            return aboveType;
        } else if (aboveType == SWITCHABLE_FILTERS) {
            return leftType;
        }
        return SWITCHABLE_FILTERS;
    }

    /**
     * Returns the context of the intra or inter flag ({@code get_intra_inter_context}).
     *
     * @param above the block above, or {@code null}
     * @param left the block to the left, or {@code null}
     * @return the context, from 0 to 3
     */
    static int intraInter(ModeInfo above, ModeInfo left) {
        if (above != null && left != null) {
            var aboveIntra = !above.isInter();
            var leftIntra = !left.isInter();
            return leftIntra && aboveIntra ? 3 : (leftIntra || aboveIntra ? 1 : 0);
        } else if (above != null || left != null) {
            return !(above != null ? above : left).isInter() ? 2 : 0;
        }
        return 0;
    }

    /**
     * Returns the context of the transform size ({@code get_tx_size_context}).
     *
     * @param above the block above, or {@code null}
     * @param left the block to the left, or {@code null}
     * @param maxTxSize the largest transform size of the current block
     * @return the context, 0 or 1
     */
    static int txSize(ModeInfo above, ModeInfo left, int maxTxSize) {
        var aboveCtx = above != null && above.skip == 0 ? above.txSize : maxTxSize;
        var leftCtx = left != null && left.skip == 0 ? left.txSize : maxTxSize;
        if (left == null) {
            leftCtx = aboveCtx;
        }
        if (above == null) {
            aboveCtx = leftCtx;
        }
        return aboveCtx + leftCtx > maxTxSize ? 1 : 0;
    }

    /**
     * Returns the context of the compound prediction flag
     * ({@code vp9_get_reference_mode_context}).
     *
     * @param above the block above, or {@code null}
     * @param left the block to the left, or {@code null}
     * @param compFixedRef the fixed reference of compound prediction
     * @return the context, from 0 to 4
     */
    static int referenceMode(ModeInfo above, ModeInfo left, int compFixedRef) {
        if (above != null && left != null) {
            if (!above.hasSecondRef() && !left.hasSecondRef()) {
                return (above.ref0 == compFixedRef ? 1 : 0) ^ (left.ref0 == compFixedRef ? 1 : 0);
            } else if (!above.hasSecondRef()) {
                return 2 + (above.ref0 == compFixedRef || !above.isInter() ? 1 : 0);
            } else if (!left.hasSecondRef()) {
                return 2 + (left.ref0 == compFixedRef || !left.isInter() ? 1 : 0);
            }
            return 4;
        } else if (above != null || left != null) {
            var edge = above != null ? above : left;
            if (!edge.hasSecondRef()) {
                return edge.ref0 == compFixedRef ? 1 : 0;
            }
            return 3;
        }
        return 1;
    }

    /**
     * Returns the reference of a block selected by an index, 0 for the first and 1 for the
     * second.
     *
     * @param mi the block
     * @param index 0 for the first reference, 1 for the second
     * @return the reference frame
     */
    private static int ref(ModeInfo mi, int index) {
        return index == 0 ? mi.ref0 : mi.ref1;
    }

    /**
     * Returns the context of the compound reference selection
     * ({@code vp9_get_pred_context_comp_ref_p}).
     *
     * @param above the block above, or {@code null}
     * @param left the block to the left, or {@code null}
     * @param fixRefIdx the sign bias of the fixed compound reference, 0 or 1
     * @param compVarRef0 the first variable compound reference
     * @param compVarRef1 the second variable compound reference
     * @param compFixedRef the fixed compound reference
     * @return the context, from 0 to 4
     */
    static int compRef(ModeInfo above, ModeInfo left, int fixRefIdx, int compVarRef0, int compVarRef1,
                       int compFixedRef) {
        var varRefIdx = fixRefIdx == 0 ? 1 : 0;
        if (above != null && left != null) {
            var aboveIntra = !above.isInter();
            var leftIntra = !left.isInter();
            if (aboveIntra && leftIntra) {
                return 2;
            } else if (aboveIntra || leftIntra) {
                var edge = aboveIntra ? left : above;
                if (!edge.hasSecondRef()) {
                    return 1 + 2 * (edge.ref0 != compVarRef1 ? 1 : 0);
                }
                return 1 + 2 * (ref(edge, varRefIdx) != compVarRef1 ? 1 : 0);
            }
            var lSg = !left.hasSecondRef();
            var aSg = !above.hasSecondRef();
            var vrfa = aSg ? above.ref0 : ref(above, varRefIdx);
            var vrfl = lSg ? left.ref0 : ref(left, varRefIdx);
            if (vrfa == vrfl && compVarRef1 == vrfa) {
                return 0;
            } else if (lSg && aSg) {
                if ((vrfa == compFixedRef && vrfl == compVarRef0) || (vrfl == compFixedRef && vrfa == compVarRef0)) {
                    return 4;
                } else if (vrfa == vrfl) {
                    return 3;
                }
                return 1;
            } else if (lSg || aSg) {
                var vrfc = lSg ? vrfa : vrfl;
                var rfs = aSg ? vrfa : vrfl;
                if (vrfc == compVarRef1 && rfs != compVarRef1) {
                    return 1;
                } else if (rfs == compVarRef1 && vrfc != compVarRef1) {
                    return 2;
                }
                return 4;
            } else if (vrfa == vrfl) {
                return 4;
            }
            return 2;
        } else if (above != null || left != null) {
            var edge = above != null ? above : left;
            if (!edge.isInter()) {
                return 2;
            }
            if (edge.hasSecondRef()) {
                return 4 * (ref(edge, varRefIdx) != compVarRef1 ? 1 : 0);
            }
            return 3 * (edge.ref0 != compVarRef1 ? 1 : 0);
        }
        return 2;
    }

    /**
     * Returns the context of the first single reference bit, last frame or not
     * ({@code vp9_get_pred_context_single_ref_p1}).
     *
     * @param above the block above, or {@code null}
     * @param left the block to the left, or {@code null}
     * @return the context, from 0 to 4
     */
    static int singleRefP1(ModeInfo above, ModeInfo left) {
        if (above != null && left != null) {
            var aboveIntra = !above.isInter();
            var leftIntra = !left.isInter();
            if (aboveIntra && leftIntra) {
                return 2;
            } else if (aboveIntra || leftIntra) {
                var edge = aboveIntra ? left : above;
                if (!edge.hasSecondRef()) {
                    return 4 * (edge.ref0 == LAST_FRAME ? 1 : 0);
                }
                return 1 + (edge.ref0 == LAST_FRAME || edge.ref1 == LAST_FRAME ? 1 : 0);
            }
            var aboveHasSecond = above.hasSecondRef();
            var leftHasSecond = left.hasSecondRef();
            var above0 = above.ref0;
            var above1 = above.ref1;
            var left0 = left.ref0;
            var left1 = left.ref1;
            if (aboveHasSecond && leftHasSecond) {
                return 1 + (above0 == LAST_FRAME || above1 == LAST_FRAME || left0 == LAST_FRAME || left1 == LAST_FRAME
                        ? 1 : 0);
            } else if (aboveHasSecond || leftHasSecond) {
                var rfs = !aboveHasSecond ? above0 : left0;
                var crf1 = aboveHasSecond ? above0 : left0;
                var crf2 = aboveHasSecond ? above1 : left1;
                if (rfs == LAST_FRAME) {
                    return 3 + (crf1 == LAST_FRAME || crf2 == LAST_FRAME ? 1 : 0);
                }
                return crf1 == LAST_FRAME || crf2 == LAST_FRAME ? 1 : 0;
            }
            return 2 * (above0 == LAST_FRAME ? 1 : 0) + 2 * (left0 == LAST_FRAME ? 1 : 0);
        } else if (above != null || left != null) {
            var edge = above != null ? above : left;
            if (!edge.isInter()) {
                return 2;
            }
            if (!edge.hasSecondRef()) {
                return 4 * (edge.ref0 == LAST_FRAME ? 1 : 0);
            }
            return 1 + (edge.ref0 == LAST_FRAME || edge.ref1 == LAST_FRAME ? 1 : 0);
        }
        return 2;
    }

    /**
     * Returns the context of the second single reference bit, golden or alternate
     * ({@code vp9_get_pred_context_single_ref_p2}).
     *
     * @param above the block above, or {@code null}
     * @param left the block to the left, or {@code null}
     * @return the context, from 0 to 4
     */
    static int singleRefP2(ModeInfo above, ModeInfo left) {
        if (above != null && left != null) {
            var aboveIntra = !above.isInter();
            var leftIntra = !left.isInter();
            if (aboveIntra && leftIntra) {
                return 2;
            } else if (aboveIntra || leftIntra) {
                var edge = aboveIntra ? left : above;
                if (!edge.hasSecondRef()) {
                    if (edge.ref0 == LAST_FRAME) {
                        return 3;
                    }
                    return 4 * (edge.ref0 == GOLDEN_FRAME ? 1 : 0);
                }
                return 1 + 2 * (edge.ref0 == GOLDEN_FRAME || edge.ref1 == GOLDEN_FRAME ? 1 : 0);
            }
            var aboveHasSecond = above.hasSecondRef();
            var leftHasSecond = left.hasSecondRef();
            var above0 = above.ref0;
            var above1 = above.ref1;
            var left0 = left.ref0;
            var left1 = left.ref1;
            if (aboveHasSecond && leftHasSecond) {
                if (above0 == left0 && above1 == left1) {
                    return 3 * (above0 == GOLDEN_FRAME || above1 == GOLDEN_FRAME || left0 == GOLDEN_FRAME
                            || left1 == GOLDEN_FRAME ? 1 : 0);
                }
                return 2;
            } else if (aboveHasSecond || leftHasSecond) {
                var rfs = !aboveHasSecond ? above0 : left0;
                var crf1 = aboveHasSecond ? above0 : left0;
                var crf2 = aboveHasSecond ? above1 : left1;
                if (rfs == GOLDEN_FRAME) {
                    return 3 + (crf1 == GOLDEN_FRAME || crf2 == GOLDEN_FRAME ? 1 : 0);
                } else if (rfs == ALTREF_FRAME) {
                    return crf1 == GOLDEN_FRAME || crf2 == GOLDEN_FRAME ? 1 : 0;
                }
                return 1 + 2 * (crf1 == GOLDEN_FRAME || crf2 == GOLDEN_FRAME ? 1 : 0);
            }
            if (above0 == LAST_FRAME && left0 == LAST_FRAME) {
                return 3;
            } else if (above0 == LAST_FRAME || left0 == LAST_FRAME) {
                var edge0 = above0 == LAST_FRAME ? left0 : above0;
                return 4 * (edge0 == GOLDEN_FRAME ? 1 : 0);
            }
            return 2 * (above0 == GOLDEN_FRAME ? 1 : 0) + 2 * (left0 == GOLDEN_FRAME ? 1 : 0);
        } else if (above != null || left != null) {
            var edge = above != null ? above : left;
            if (!edge.isInter() || (edge.ref0 == LAST_FRAME && !edge.hasSecondRef())) {
                return 2;
            } else if (!edge.hasSecondRef()) {
                return 4 * (edge.ref0 == GOLDEN_FRAME ? 1 : 0);
            }
            return 3 * (edge.ref0 == GOLDEN_FRAME || edge.ref1 == GOLDEN_FRAME ? 1 : 0);
        }
        return 2;
    }
}
