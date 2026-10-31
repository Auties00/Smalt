/* Ports VVdeC 3.3.0 (source/Lib/CommonLib/InterPrediction.cpp, InterpolationFilter.cpp, WeightPrediction.cpp,
 * Buffer.cpp and RdCost.cpp: motion compensation with BDOF, DMVR, PROF, weighted and geometric prediction, reference
 * picture resampling and intra block copy). Copyright (c) 2018-2026, Fraunhofer-Gesellschaft zur Foerderung der
 * angewandten Forschung e.V. & The VVdeC Authors; Clear BSD License. Modified for Smalt. */
package com.github.auties00.smalt.video.vvc.internal;

/**
 * Predicts inter and IBC coding units (ITU-T H.266 8.5.6, 8.5.7 and 8.6.3) into the picture being decoded.
 *
 * <p>Every list prediction is first computed at the 14-bit intermediate precision of 8.5.6.3 (the sample value
 * shifted left by {@code 14 - BitDepth} less {@code 1 << 13}); the uni-prediction rounding, the bi-prediction
 * average, the bi-prediction with CU weights, explicit weighted prediction, BDOF and the geometric blend then produce
 * the output samples. Reference samples outside the picture repeat its border samples, or wrap around horizontally
 * when reference wraparound applies, or repeat the border of the subpicture when it is treated as a picture.
 *
 * <p>Instances are not thread-safe.
 */
final class VvcInter {
    /**
     * The offset of the 14-bit intermediate precision, {@code 1 << 13}.
     */
    private static final int OFFS = 1 << 13;

    /**
     * The sample loop kernels.
     */
    private static final ScalarVvcDsp DSP = VvcDsp.KERNELS;
    /**
     * The largest block side predicted at once.
     */
    private static final int MAX = 128;
    /**
     * The reference samples a window holds beyond the block on each side.
     */
    private static final int MARGIN = 4;
    /**
     * The weight {@code w1} of each {@code bcw_idx} of bi-prediction with CU weights, out of 8.
     */
    private static final int[] BCW_WEIGHTS = {4, 5, 3, 10, -2};
    /**
     * The regular interpolation filters.
     */
    private static final int FILTER_REGULAR = 0;
    /**
     * The interpolation filters of affine blocks.
     */
    private static final int FILTER_AFFINE = 2;
    /**
     * The filter of an integer position: the sample itself, with the 8-tap filter gain.
     */
    private static final int[] IDENTITY8 = {0, 0, 0, 64, 0, 0, 0, 0};

    /**
     * The per picture maps of the picture being predicted.
     */
    private final VvcFrame f;
    /**
     * The motion derivation, which holds the subblock-based temporal motion.
     */
    private VvcMotion motion;
    /**
     * The header of the slice being predicted.
     */
    private VvcSliceHeader sh;
    /**
     * The sequence parameter set of the slice.
     */
    private VvcSps sps;
    /**
     * The picture parameter set of the slice.
     */
    private VvcPps pps;
    /**
     * The picture header of the slice.
     */
    private VvcPictureHeader ph;
    /**
     * The bit depth of the samples.
     */
    private int bitDepth;
    /**
     * The largest sample value.
     */
    private int maxVal;
    /**
     * The shift from the sample values to the 14-bit intermediate precision, at least 2.
     */
    private int hr;
    /**
     * The horizontal chroma subsampling shift.
     */
    private int sx;
    /**
     * The vertical chroma subsampling shift.
     */
    private int sy;
    /**
     * {@code sps_chroma_format_idc}.
     */
    private int chromaFormat;

    /**
     * The luma prediction of each list, at 14-bit precision.
     */
    private final int[][] predY = {new int[MAX * MAX], new int[MAX * MAX]};
    /**
     * The Cb prediction of each list, at 14-bit precision.
     */
    private final int[][] predCb = {new int[MAX * MAX / 4], new int[MAX * MAX / 4]};
    /**
     * The Cr prediction of each list, at 14-bit precision.
     */
    private final int[][] predCr = {new int[MAX * MAX / 4], new int[MAX * MAX / 4]};
    /**
     * The reference window of the block being interpolated.
     */
    private final int[] win = new int[(MAX + 4 + 2 * MARGIN + 1) * (MAX + 4 + 2 * MARGIN + 1)];
    /**
     * The output of the horizontal interpolation stage.
     */
    private final int[] tmp = new int[(MAX + 16) * (MAX + 2 * MARGIN + 1)];
    /**
     * The extended BDOF block of each list: the prediction surrounded by one sample on each side.
     */
    private final int[][] bdofExt = {new int[18 * 18], new int[18 * 18]};
    /**
     * The horizontal gradients of each list's extended BDOF block.
     */
    private final int[][] gradX = {new int[18 * 18], new int[18 * 18]};
    /**
     * The vertical gradients of each list's extended BDOF block.
     */
    private final int[][] gradY = {new int[18 * 18], new int[18 * 18]};
    /**
     * The extended 6x6 luma subblock of the optical flow refinement.
     */
    private final int[] profExt = new int[36];
    /**
     * The horizontal gradients of the optical flow refinement of a subblock.
     */
    private final int[] profGx = new int[16];
    /**
     * The vertical gradients of the optical flow refinement of a subblock.
     */
    private final int[] profGy = new int[16];
    /**
     * The horizontal vector differences of the optical flow refinement, per sample position.
     */
    private final int[] profDx = new int[16];
    /**
     * The vertical vector differences of the optical flow refinement, per sample position.
     */
    private final int[] profDy = new int[16];
    /**
     * The bilinear prediction of each list of the DMVR search, two samples larger on each side.
     */
    private final int[][] dmvrBuf = {new int[(MAX + 4) * (MAX + 4)], new int[(MAX + 4) * (MAX + 4)]};
    /**
     * The matching cost of each of the 25 DMVR search positions.
     */
    private final long[] sads = new long[25];
    /**
     * The output of the horizontal stage of a resampled reference.
     */
    private final int[] rprBuf = new int[(MAX + 16) * (2 * MAX + 32)];

    /**
     * The first column of the window the samples of a DMVR subblock are read from, when {@link #windowClamp} is set.
     */
    private int wx0;
    /**
     * The first row of the window the samples of a DMVR subblock are read from.
     */
    private int wy0;
    /**
     * The last column of the window the samples of a DMVR subblock are read from.
     */
    private int wx1;
    /**
     * The last row of the window the samples of a DMVR subblock are read from.
     */
    private int wy1;
    /**
     * Whether the reference samples are clamped to the window of the initial DMVR vectors.
     */
    private boolean windowClamp;

    /**
     * Constructs the inter prediction of a picture size.
     *
     * @param frame the per picture maps
     */
    VvcInter(VvcFrame frame) {
        this.f = frame;
    }

    /**
     * Sets the bit depth of the samples predicted next.
     *
     * @param bitDepth the bit depth
     */
    void setBitDepth(int bitDepth) {
        this.bitDepth = bitDepth;
        this.maxVal = (1 << bitDepth) - 1;
        this.hr = Math.max(2, 14 - bitDepth);
    }

    /**
     * Prepares the inter prediction of a slice.
     *
     * @param header the slice header
     */
    void beginSlice(VvcSliceHeader header) {
        this.sh = header;
        this.sps = header.sps;
        this.pps = header.pps;
        this.ph = header.ph;
        this.chromaFormat = sps.chromaFormatIdc;
        this.sx = sps.chromaShiftX();
        this.sy = sps.chromaShiftY();
    }

    /**
     * {@return whether a reference picture is resampled for the current picture ({@code isRefScaled})}
     *
     * @param ref the reference picture
     */
    private boolean scaled(VvcPicture ref) {
        if (ref.width != f.width || ref.height != f.height) {
            return true;
        }
        var cur = f.pic;
        if ((ref.pps != null && ref.pps.scalingWindowExplicit) || pps.scalingWindowExplicit) {
            for (var i = 0; i < 4; i++) {
                if (ref.scalingWindow[i] != cur.scalingWindow[i]) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * {@return whether reference wraparound applies to a reference: enabled and the reference not resampled}
     *
     * @param ref the reference picture
     */
    private boolean wrapEnabled(VvcPicture ref) {
        return pps.refWraparound && !scaled(ref);
    }

    /**
     * {@return the subpicture holding a coding unit}
     *
     * @param cu the coding unit
     */
    private int subpicOf(VvcCu cu) {
        return f.ctuSubpic[(cu.y >> f.log2Ctu) * f.widthInCtus + (cu.x >> f.log2Ctu)];
    }

    /**
     * {@return whether a coding unit's references are clamped to its subpicture, which is treated as a picture}
     *
     * @param cu the coding unit
     */
    private boolean subpicClamp(VvcCu cu) {
        return sps.numSubpics > 1 && sps.subpicTreatedAsPic[subpicOf(cu)];
    }

    /**
     * {@return a horizontal vector clamped to the area that may be referenced ({@code clipMv})}
     *
     * @param mv the vector, in 1/16 luma samples
     * @param cu the coding unit
     * @param x the luma column of the block
     */
    private int clipMvX(int mv, VvcCu cu, int x) {
        var horMax = (f.width + 8 - x - 1) << 4;
        var horMin = (-f.ctuSize - 8 - x + 1) << 4;
        if (subpicClamp(cu)) {
            var s = subpicOf(cu);
            var left = sps.subpicCtuX[s] << f.log2Ctu;
            var right = Math.min(f.width, (sps.subpicCtuX[s] + sps.subpicWidth[s]) << f.log2Ctu) - 1;
            horMax = ((right + 1) + 8 - x - 1) << 4;
            horMin = (-f.ctuSize - 8 - (x - left) + 1) << 4;
        }
        return Math.clamp(mv, horMin, horMax);
    }

    /**
     * {@return a vertical vector clamped to the area that may be referenced ({@code clipMv})}
     *
     * @param mv the vector, in 1/16 luma samples
     * @param cu the coding unit
     * @param y the luma row of the block
     */
    private int clipMvY(int mv, VvcCu cu, int y) {
        var verMax = (f.height + 8 - y - 1) << 4;
        var verMin = (-f.ctuSize - 8 - y + 1) << 4;
        if (subpicClamp(cu)) {
            var s = subpicOf(cu);
            var top = sps.subpicCtuY[s] << f.log2Ctu;
            var bottom = Math.min(f.height, (sps.subpicCtuY[s] + sps.subpicHeight[s]) << f.log2Ctu) - 1;
            verMax = ((bottom + 1) + 8 - y - 1) << 4;
            verMin = (-f.ctuSize - 8 - (y - top) + 1) << 4;
        }
        return Math.clamp(mv, verMin, verMax);
    }

    /**
     * The vector {@link #wrapClip} and {@link #clipForPrediction} produce.
     */
    private final int[] wrapMv = new int[2];

    /**
     * Applies {@code wrapClipMv}: returns whether the wrapped reference applies, and stores the clipped vector in
     * {@link #wrapMv}.
     *
     * @param mvx the horizontal vector, in 1/16 luma samples
     * @param mvy the vertical vector, in 1/16 luma samples
     * @param x the luma column of the block
     * @param y the luma row of the block
     * @param w the block width
     * @return whether the wrapped reference applies
     */
    private boolean wrapClip(int mvx, int mvy, int x, int y, int w) {
        var wrap = true;
        var horMax = (f.width + f.ctuSize - w + 8 - x - 1) << 4;
        var horMin = (-f.ctuSize - 8 - x + 1) << 4;
        var verMax = (f.height + 8 - y - 1) << 4;
        var verMin = (-f.ctuSize - 8 - y + 1) << 4;
        if (mvx > horMax) {
            mvx -= pps.wraparoundOffset << 4;
            mvx = Math.clamp(mvx, horMin, horMax);
            wrap = false;
        }
        if (mvx < horMin) {
            mvx += pps.wraparoundOffset << 4;
            mvx = Math.clamp(mvx, horMin, horMax);
            wrap = false;
        }
        wrapMv[0] = mvx;
        wrapMv[1] = Math.clamp(mvy, verMin, verMax);
        return wrap;
    }

    /**
     * Clips a vector for prediction ({@code clipMv}, which wraps it around when the sequence enables reference
     * wraparound) and decides whether the wrapped reference applies (a second {@code wrapClipMv}); stores the vector
     * in {@link #wrapMv} and returns the decision.
     *
     * @param mvx the horizontal vector, in 1/16 luma samples
     * @param mvy the vertical vector, in 1/16 luma samples
     * @param cu the coding unit
     * @param x the luma column of the block
     * @param y the luma row of the block
     * @param w the block width
     * @return whether the wrapped reference applies
     */
    private boolean clipForPrediction(int mvx, int mvy, VvcCu cu, int x, int y, int w) {
        if (sps.refWraparound) {
            wrapClip(mvx, mvy, x, y, w);
            if (!pps.refWraparound) {
                return false;
            }
            return wrapClip(wrapMv[0], wrapMv[1], x, y, w);
        }
        wrapMv[0] = clipMvX(mvx, cu, x);
        wrapMv[1] = clipMvY(mvy, cu, y);
        return false;
    }

    /**
     * Copies the reference samples of a window of one component into {@link #win}: the {@code w} by {@code h}
     * samples at {@code (x0, y0)} in component coordinates, padded as described in the class documentation.
     *
     * @param ref the reference picture
     * @param c the component
     * @param cu the coding unit
     * @param wrap whether reference wraparound applies
     * @param x0 the first column of the window in component samples
     * @param y0 the first row of the window in component samples
     * @param w the block width
     * @param h the block height
     */
    private void fetch(VvcPicture ref, int c, VvcCu cu, boolean wrap, int x0, int y0, int w, int h) {
        var plane = ref.planes[c];
        var pw = ref.planeWidth[c];
        var phh = ref.planeHeight[c];
        var csx = c == 0 ? 0 : sx;
        var csy = c == 0 ? 0 : sy;
        var minX = 0;
        var maxX = pw - 1;
        var minY = 0;
        var maxY = phh - 1;
        if (subpicClamp(cu)) {
            var s = subpicOf(cu);
            minX = (sps.subpicCtuX[s] << f.log2Ctu) >> csx;
            maxX = (Math.min(f.width, (sps.subpicCtuX[s] + sps.subpicWidth[s]) << f.log2Ctu) >> csx) - 1;
            minY = (sps.subpicCtuY[s] << f.log2Ctu) >> csy;
            maxY = (Math.min(f.height, (sps.subpicCtuY[s] + sps.subpicHeight[s]) << f.log2Ctu) >> csy) - 1;
            wrap = false;
        }
        var wrapOffset = pps.wraparoundOffset >> csx;
        var inside = !windowClamp && x0 >= minX && y0 >= minY && x0 + w - 1 <= maxX && y0 + h - 1 <= maxY;
        if (inside) {
            for (var j = 0; j < h; j++) {
                var src = (y0 + j) * pw + x0;
                var dst = j * w;
                for (var i = 0; i < w; i++) {
                    win[dst + i] = plane[src + i];
                }
            }
            return;
        }
        for (var j = 0; j < h; j++) {
            var y = y0 + j;
            if (windowClamp) {
                y = Math.clamp(y, wy0, wy1);
            }
            y = Math.clamp(y, minY, maxY);
            var row = y * pw;
            var dst = j * w;
            for (var i = 0; i < w; i++) {
                var x = x0 + i;
                if (windowClamp) {
                    x = Math.clamp(x, wx0, wx1);
                }
                if (wrap) {
                    if (x < 0) {
                        x += wrapOffset;
                    } else if (x > pw - 1) {
                        x -= wrapOffset;
                    }
                }
                x = Math.clamp(x, minX, maxX);
                win[dst + i] = plane[row + x];
            }
        }
    }

    /**
     * {@return the luma filter coefficients of a fractional position}
     *
     * @param frac the fractional position, in 1/16 samples
     * @param kind the filter kind
     * @param hpel whether the half-sample filter applies at half-sample positions
     */
    private int[] lumaCoeff(int frac, int kind, boolean hpel) {
        if (kind == FILTER_AFFINE) {
            return VvcInterTables.LUMA_AFFINE[frac];
        }
        if (frac == 8 && hpel) {
            return VvcInterTables.LUMA_HALF_ALT;
        }
        return VvcInterTables.LUMA[frac];
    }

    /**
     * Interpolates a block from {@link #win} (whose top-left sample is {@code MARGIN} samples above and left of the
     * block's integer position) into {@code dst} at 14-bit intermediate precision.
     *
     * @param c the component
     * @param fracX the horizontal fractional position, in 1/16 or 1/32 samples
     * @param fracY the vertical fractional position
     * @param w the block width
     * @param h the block height
     * @param ws the row stride of the window
     * @param dst receives the prediction
     * @param dstOff the index of the first sample in {@code dst}
     * @param dstStride the row stride of {@code dst}
     * @param kind the filter kind: regular or affine
     * @param hpel whether the half-sample interpolation filter applies
     */
    private void interpolate(int c, int fracX, int fracY, int w, int h, int ws, int[] dst, int dstOff, int dstStride,
            int kind, boolean hpel) {
        int[] cx;
        int[] cy;
        int taps;
        if (c == 0) {
            taps = 8;
            cx = fracX == 0 ? IDENTITY8 : lumaCoeff(fracX, kind, hpel);
            cy = fracY == 0 ? IDENTITY8 : lumaCoeff(fracY, kind, hpel);
        } else {
            taps = 4;
            cx = VvcInterTables.CHROMA[fracX << (1 - sx)];
            cy = VvcInterTables.CHROMA[fracY << (1 - sy)];
        }
        var half = taps / 2 - 1;
        var base = MARGIN * ws + MARGIN;
        if (fracX == 0 && fracY == 0) {
            for (var j = 0; j < h; j++) {
                var s = base + j * ws;
                var d = dstOff + j * dstStride;
                for (var i = 0; i < w; i++) {
                    dst[d + i] = (win[s + i] << hr) - OFFS;
                }
            }
            return;
        }
        var shift1 = 6 - hr;
        if (fracY == 0) {
            DSP.filterRows(win, base - half, ws, dst, dstOff, dstStride, w, h, cx, taps, shift1);
            return;
        }
        if (fracX == 0) {
            DSP.filterCols(win, base - half * ws, ws, dst, dstOff, dstStride, w, h, cy, taps, shift1, -OFFS);
            return;
        }
        var rows = h + taps - 1;
        DSP.filterRows(win, base - half * ws - half, ws, tmp, 0, w, w, rows, cx, taps, shift1);
        DSP.filterCols(tmp, 0, w, dst, dstOff, dstStride, w, h, cy, taps, 6, 0);
    }

    /**
     * Predicts one component of a block from one reference at 14-bit precision.
     *
     * @param x the block's left column in component samples
     * @param y the block's top row in component samples
     * @param c the component
     * @param cu the coding unit
     * @param ref the reference picture
     * @param mvx the horizontal vector, in 1/16 luma samples
     * @param mvy the vertical vector, in 1/16 luma samples
     * @param w the block width
     * @param h the block height
     * @param dst receives the prediction
     * @param dstOff the index of the first sample in {@code dst}
     * @param dstStride the row stride of {@code dst}
     * @param wrap whether reference wraparound applies
     * @param kind the filter kind: regular or affine
     * @param hpel whether the half-sample interpolation filter applies
     */
    private void predictBlock(int c, VvcCu cu, VvcPicture ref, int mvx, int mvy, int x, int y, int w, int h,
            int[] dst, int dstOff, int dstStride, boolean wrap, int kind, boolean hpel) {
        var csx = c == 0 ? 0 : sx;
        var csy = c == 0 ? 0 : sy;
        var shiftX = 4 + csx;
        var shiftY = 4 + csy;
        var fracX = mvx & ((1 << shiftX) - 1);
        var fracY = mvy & ((1 << shiftY) - 1);
        var ix = x + (mvx >> shiftX);
        var iy = y + (mvy >> shiftY);
        var ws = w + 2 * MARGIN + 1;
        fetch(ref, c, cu, wrap, ix - MARGIN, iy - MARGIN, ws, h + 2 * MARGIN + 1);
        interpolate(c, fracX, fracY, w, h, ws, dst, dstOff, dstStride, kind, hpel);
    }

    /**
     * Predicts a uni-directional list of a non-affine block (all components) into the list's buffers, at 14-bit
     * precision ({@code xPredInterUni}).
     *
     * @param cu the coding unit
     * @param list the reference picture list, 0 or 1
     * @param refIdx the reference index
     * @param mvx the horizontal vector, in 1/16 luma samples
     * @param mvy the vertical vector, in 1/16 luma samples
     * @param x the luma column of the block
     * @param y the luma row of the block
     * @param w the luma block width
     * @param h the luma block height
     * @param bufOff the index of the block in the list buffers
     * @param bufStride the row stride of the list buffers
     * @param luma whether the luma block is predicted
     * @param chroma whether the chroma blocks are predicted
     */
    private void predictList(VvcCu cu, int list, int refIdx, int mvx, int mvy, int x, int y, int w, int h,
            int bufOff, int bufStride, boolean luma, boolean chroma) {
        var ref = sh.refPics[list][refIdx];
        var hpel = cu.imv == VvcCu.IMV_HALF;
        if (scaled(ref)) {
            if (luma) {
                predictRpr(0, ref, list, refIdx, mvx, mvy, x, y, w, h, predY[list], bufOff, bufStride,
                        FILTER_REGULAR, hpel, false);
            }
            if (chroma && chromaFormat != 0) {
                var cOff = (bufOff % bufStride >> sx) + (bufOff / bufStride >> sy) * (bufStride >> sx);
                predictRpr(1, ref, list, refIdx, mvx, mvy, x >> sx, y >> sy, w >> sx, h >> sy, predCb[list], cOff,
                        bufStride >> sx, FILTER_REGULAR, hpel, false);
                predictRpr(2, ref, list, refIdx, mvx, mvy, x >> sx, y >> sy, w >> sx, h >> sy, predCr[list], cOff,
                        bufStride >> sx, FILTER_REGULAR, hpel, false);
            }
            return;
        }
        var wrap = clipForPrediction(mvx, mvy, cu, x, y, w) && wrapEnabled(ref);
        mvx = wrapMv[0];
        mvy = wrapMv[1];
        if (luma) {
            predictBlock(0, cu, ref, mvx, mvy, x, y, w, h, predY[list], bufOff, bufStride, wrap, FILTER_REGULAR,
                    hpel);
        }
        if (chroma && chromaFormat != 0) {
            var cOff = (bufOff % bufStride >> sx) + (bufOff / bufStride >> sy) * (bufStride >> sx);
            predictBlock(1, cu, ref, mvx, mvy, x >> sx, y >> sy, w >> sx, h >> sy, predCb[list], cOff,
                    bufStride >> sx, wrap, FILTER_REGULAR, hpel);
            predictBlock(2, cu, ref, mvx, mvy, x >> sx, y >> sy, w >> sx, h >> sy, predCr[list], cOff,
                    bufStride >> sx, wrap, FILTER_REGULAR, hpel);
        }
    }

    /**
     * Predicts a block from a resampled reference ({@code xPredInterBlkRPR}, 8.5.6.3.1).
     *
     * @param c the component
     * @param ref the reference picture
     * @param list the reference picture list, 0 or 1
     * @param refIdx the reference index
     * @param mvx the horizontal vector, in 1/16 luma samples
     * @param mvy the vertical vector, in 1/16 luma samples
     * @param x the column of the block in component samples
     * @param y the row of the block in component samples
     * @param w the block width
     * @param h the block height
     * @param dst receives the prediction
     * @param dstOff the index of the first sample in {@code dst}
     * @param dstStride the row stride of {@code dst}
     * @param filterIndex the interpolation filter of the reference picture resampling
     * @param hpel whether the half-sample interpolation filter applies
     * @param wrap whether reference wraparound applies
     */
    private void predictRpr(int c, VvcPicture ref, int list, int refIdx, int mvx, int mvy, int x, int y, int w, int h,
            int[] dst, int dstOff, int dstStride, int filterIndex, boolean hpel, boolean wrap) {
        var csx = c == 0 ? 0 : sx;
        var csy = c == 0 ? 0 : sy;
        var shiftHor = 4 + csx;
        var shiftVer = 4 + csy;
        var ratioX = sh.refScaleX[list][refIdx];
        var ratioY = sh.refScaleY[list][refIdx];
        var thr1 = (1 << 14) * 5 / 4;
        var thr2 = (1 << 14) * 7 / 4;
        var xFilter = filterIndex;
        if (ratioX > thr2) {
            xFilter = 4;
        } else if (ratioX > thr1) {
            xFilter = 3;
        }
        var yFilter = filterIndex;
        if (ratioY > thr2) {
            yFilter = 4;
        } else if (ratioY > thr1) {
            yFilter = 3;
        }
        if (c == 0 && filterIndex == FILTER_AFFINE) {
            if (ratioX > thr1) {
                xFilter += 2;
            }
            if (ratioY > thr1) {
                yFilter += 2;
            }
        }
        var posShift = 14 - 4;
        var stepX = (ratioX + 8) >> 4;
        var stepY = (ratioY + 8) >> 4;
        var offX = 1 << (posShift - shiftHor - 1);
        var offY = 1 << (posShift - shiftVer - 1);
        var unitX = sx + 1 == 2 ? 2 : 1;
        var unitY = sy + 1 == 2 ? 2 : 1;
        if (chromaFormat == 0) {
            unitX = 1;
            unitY = 1;
        }
        var cur = f.pic;
        long posX = (((long) x << csx) - (long) cur.scalingWindow[0] * unitX) >> csx;
        long posY = (((long) y << csy) - (long) cur.scalingWindow[2] * unitY) >> csy;
        var addX = c == 0 ? 0 : (sps.chromaHorCollocated ? 0 : 1) * 8 * (ratioX - (1 << 14));
        var addY = c == 0 ? 0 : (sps.chromaVerCollocated ? 0 : 1) * 8 * (ratioY - (1 << 14));
        var x0Int = ((posX << (4 + csx)) + mvx) * (long) ratioX + addX;
        x0Int = Long.signum(x0Int) * ((Math.abs(x0Int) + (1L << (7 + csx))) >> (8 + csx))
                + (((long) ref.scalingWindow[0] * unitX) << (posShift - csx));
        var y0Int = ((posY << (4 + csy)) + mvy) * (long) ratioY + addY;
        y0Int = Long.signum(y0Int) * ((Math.abs(y0Int) + (1L << (7 + csy))) >> (8 + csy))
                + (((long) ref.scalingWindow[2] * unitY) << (posShift - csy));
        var taps = c == 0 ? 8 : 4;
        var extSize = c == 0 ? 1 : 2;
        var refW = ref.width >> csx;
        var refH = ref.height >> csy;
        var yInt0 = Math.clamp(((int) y0Int + offY) >> posShift, -4, refH + 4);
        var refHeight = Math.max(1, ((((int) y0Int + (h - 1) * stepY) + offY) >> posShift)
                - ((((int) y0Int) + offY) >> posShift) + 1);
        var filtHeight = refHeight + taps - 1 + extSize;
        var bufStride = w;
        var plane = ref.planes[c];
        var pw = ref.planeWidth[c];
        var phh = ref.planeHeight[c];
        var wrapOffset = pps.wraparoundOffset >> csx;
        for (var col = 0; col < w; col++) {
            var px = (int) x0Int + col * stepX;
            var xInt = Math.clamp((px + offX) >> posShift, -4, refW + 4);
            var xFrac = ((px + offX) >> (posShift - shiftHor)) & ((1 << shiftHor) - 1);
            var coeff = rprCoeff(c, xFrac, xFilter, hpel && ratioX == 1 << 14);
            for (var row = 0; row < filtHeight; row++) {
                var yy = Math.clamp(yInt0 - (taps / 2 - 1) + row, 0, phh - 1);
                var sum = 0;
                for (var k = 0; k < taps; k++) {
                    var xx = xInt - (taps / 2 - 1) + k;
                    if (wrap) {
                        if (xx < 0) {
                            xx += wrapOffset;
                        } else if (xx > pw - 1) {
                            xx -= wrapOffset;
                        }
                    }
                    xx = Math.clamp(xx, 0, pw - 1);
                    sum += plane[yy * pw + xx] * coeff[k];
                }
                rprBuf[row * bufStride + col] = (sum >> (6 - hr)) - OFFS;
            }
        }
        for (var row = 0; row < h; row++) {
            var py = (int) y0Int + row * stepY;
            var yInt = Math.clamp((py + offY) >> posShift, -4, refH + 4);
            var yFrac = ((py + offY) >> (posShift - shiftVer)) & ((1 << shiftVer) - 1);
            var coeff = rprCoeff(c, yFrac, yFilter, hpel && ratioY == 1 << 14);
            var startRow = yInt - yInt0;
            for (var col = 0; col < w; col++) {
                var sum = 0;
                for (var k = 0; k < taps; k++) {
                    sum += rprBuf[(startRow + k) * bufStride + col] * coeff[k];
                }
                dst[dstOff + row * dstStride + col] = sum >> 6;
            }
        }
    }

    /**
     * {@return the filter coefficients of a fractional position for a resampled reference}
     *
     * @param c the component
     * @param frac the fractional position
     * @param index the filter of the resampling ratio
     * @param hpel whether the half-sample filter applies
     */
    private int[] rprCoeff(int c, int frac, int index, boolean hpel) {
        if (c == 0) {
            return switch (index) {
                case 2 -> VvcInterTables.LUMA_AFFINE[frac];
                case 3 -> VvcInterTables.LUMA_RPR1[frac];
                case 4 -> VvcInterTables.LUMA_RPR2[frac];
                case 5 -> VvcInterTables.LUMA_AFFINE_RPR1[frac];
                case 6 -> VvcInterTables.LUMA_AFFINE_RPR2[frac];
                default -> frac == 8 && hpel ? VvcInterTables.LUMA_HALF_ALT : VvcInterTables.LUMA[frac];
            };
        }
        var f2 = frac << (1 - (c == 0 ? 0 : sx));
        return switch (index) {
            case 3 -> VvcInterTables.CHROMA_RPR1[f2];
            case 4 -> VvcInterTables.CHROMA_RPR2[f2];
            default -> VvcInterTables.CHROMA[f2];
        };
    }

    /**
     * {@return whether explicit weighted prediction applies to a reference pair}
     *
     * @param ref0 the list 0 reference index, or -1
     * @param ref1 the list 1 reference index, or -1
     */
    private boolean wpPresent(int ref0, int ref1) {
        var w = sh.weights;
        if (w == null) {
            return false;
        }
        for (var c = 0; c < 3; c++) {
            if ((ref0 >= 0 && w.present[0][ref0][c]) || (ref1 >= 0 && w.present[1][ref1][c])) {
                return true;
            }
        }
        return false;
    }

    /**
     * {@return whether the references of a bi-predicted coding unit are on opposite sides of the picture at the same
     * order count distance}
     *
     * @param cu the coding unit
     */
    private boolean diffDirEqualDistance(VvcCu cu) {
        if (cu.refIdx[0] < 0 || cu.refIdx[1] < 0) {
            return false;
        }
        if (sh.refLongTerm[0][cu.refIdx[0]] || sh.refLongTerm[1][cu.refIdx[1]]) {
            return false;
        }
        var poc0 = sh.refPoc[0][cu.refIdx[0]];
        var poc1 = sh.refPoc[1][cu.refIdx[1]];
        return sh.poc - poc0 == poc1 - sh.poc;
    }

    /**
     * Predicts an inter coding unit into the picture.
     *
     * @param cu the coding unit, whose motion has been derived
     * @param motion the motion derivation holding the spatial motion field
     */
    void predict(VvcCu cu, VvcMotion motion) {
        this.motion = motion;
        if (cu.gpm) {
            predictGpm(cu);
            return;
        }
        if (cu.affine && !cu.sbtmvp) {
            predictAffine(cu);
            return;
        }
        if (cu.sbtmvp) {
            predictSubPu(cu);
            return;
        }
        var bi = cu.refIdx[0] >= 0 && cu.refIdx[1] >= 0;
        var refScaled = (cu.refIdx[0] >= 0 && scaled(sh.refPics[0][cu.refIdx[0]]))
                || (cu.refIdx[1] >= 0 && scaled(sh.refPics[1][cu.refIdx[1]]));
        var bdof = false;
        if (sps.bdof && !ph.bdofDisabled && !cu.ciip && !cu.smvd && cu.bcwIdx == 0) {
            var noWp = !(sh.isB() && wpPresent(cu.refIdx[0], cu.refIdx[1])) && !(pps.weightedPred && !sh.isB());
            bdof = noWp && diffDirEqualDistance(cu) && cu.h >= 8 && cu.w >= 8 && cu.w * cu.h >= 128;
        }
        var dmvr = dmvrCondition(cu);
        bdof &= !refScaled;
        dmvr &= !refScaled;
        if (bdof && !dmvr) {
            predictBdofSubblocks(cu);
        } else if (dmvr) {
            predictDmvr(cu, bdof);
        } else {
            predictUniform(cu, cu.x, cu.y, cu.w, cu.h, cu.refIdx[0], cu.refIdx[1], cu.mvx[0][0], cu.mvy[0][0],
                    cu.mvx[1][0], cu.mvy[1][0], bi);
        }
    }

    /**
     * {@return whether decoder side motion vector refinement applies to a coding unit (8.5.1)}
     *
     * @param cu the coding unit
     */
    private boolean dmvrCondition(VvcCu cu) {
        if (!sps.dmvr || ph.dmvrDisabled) {
            return false;
        }
        return cu.merge && !cu.subblockMerge && !cu.ciip && !cu.affine && !cu.mmvd && !cu.gpm
                && diffDirEqualDistance(cu) && cu.h >= 8 && cu.w >= 8 && cu.w * cu.h >= 128 && cu.bcwIdx == 0
                && !wpPresent(cu.refIdx[0], cu.refIdx[1]) && !scaled(sh.refPics[0][cu.refIdx[0]])
                && !scaled(sh.refPics[1][cu.refIdx[1]]);
    }

    /**
     * Predicts a block with uniform motion, both lists or one, and writes the output samples.
     *
     * @param cu the coding unit
     * @param x the luma column of the block
     * @param y the luma row of the block
     * @param w the block width
     * @param h the block height
     * @param ref0 the list 0 reference index, or -1
     * @param ref1 the list 1 reference index, or -1
     * @param mx0 the horizontal list 0 vector
     * @param my0 the vertical list 0 vector
     * @param mx1 the horizontal list 1 vector
     * @param my1 the vertical list 1 vector
     * @param bi whether both lists are predicted
     */
    private void predictUniform(VvcCu cu, int x, int y, int w, int h, int ref0, int ref1, int mx0, int my0, int mx1,
            int my1, boolean bi) {
        if (ref0 >= 0) {
            predictList(cu, 0, ref0, mx0, my0, x, y, w, h, 0, w, cu.hasLuma(), true);
        }
        if (ref1 >= 0) {
            predictList(cu, 1, ref1, mx1, my1, x, y, w, h, 0, w, cu.hasLuma(), true);
        }
        combine(cu, x, y, w, h, ref0, ref1, 0, w, false);
    }

    /**
     * Produces the output samples of a block from the list predictions: uni-prediction rounding, the average, the CU
     * weighted average or explicit weighted prediction.
     *
     * @param cu the coding unit
     * @param x the luma column of the block
     * @param y the luma row of the block
     * @param w the block width
     * @param h the block height
     * @param ref0 the list 0 reference index, or -1
     * @param ref1 the list 1 reference index, or -1
     * @param bufOff the index of the block in the list buffers
     * @param bufStride the row stride of the list buffers
     * @param skipLuma whether the luma output is written elsewhere, by BDOF
     */
    private void combine(VvcCu cu, int x, int y, int w, int h, int ref0, int ref1, int bufOff, int bufStride,
            boolean skipLuma) {
        var bi = ref0 >= 0 && ref1 >= 0;
        var pic = f.pic;
        var comps = chromaFormat == 0 ? 1 : 3;
        for (var c = 0; c < comps; c++) {
            if (c == 0 && skipLuma) {
                continue;
            }
            var csx = c == 0 ? 0 : sx;
            var csy = c == 0 ? 0 : sy;
            var cw = w >> csx;
            var ch = h >> csy;
            var bs = bufStride >> csx;
            var off = c == 0 ? bufOff : (bufOff % bufStride >> csx) + (bufOff / bufStride >> csy) * bs;
            var p0 = c == 0 ? predY[0] : c == 1 ? predCb[0] : predCr[0];
            var p1 = c == 0 ? predY[1] : c == 1 ? predCb[1] : predCr[1];
            var plane = pic.planes[c];
            var stride = pic.planeWidth[c];
            var px = x >> csx;
            var py = y >> csy;
            var explicitBi = sh.weights != null && pps.weightedBipred && sh.isB() && (cu.bcwIdx == 0 || !bi);
            var explicitUni = sh.weights != null && pps.weightedPred && !sh.isB();
            if (explicitBi || explicitUni) {
                weighted(c, plane, stride, px, py, cw, ch, p0, p1, off, bs, ref0, ref1);
                continue;
            }
            if (bi) {
                if (cu.bcwIdx != 0 && !cu.ciip) {
                    var w1 = BCW_WEIGHTS[cu.bcwIdx];
                    var w0 = 8 - w1;
                    var shift = hr + 3;
                    var offset = (1 << (shift - 1)) + (OFFS << 3);
                    DSP.average(p0, p1, off, bs, plane, py * stride + px, stride, cw, ch, w0, w1, shift, offset,
                            maxVal);
                } else {
                    var shift = hr + 1;
                    var offset = (1 << (shift - 1)) + 2 * OFFS;
                    DSP.average(p0, p1, off, bs, plane, py * stride + px, stride, cw, ch, 1, 1, shift, offset,
                            maxVal);
                }
            } else {
                var src = ref0 >= 0 ? p0 : p1;
                var offset = OFFS + (1 << (hr - 1));
                DSP.single(src, off, bs, plane, py * stride + px, stride, cw, ch, hr, offset, maxVal);
            }
        }
    }

    /**
     * Applies explicit weighted sample prediction (8.5.6.6.3).
     *
     * @param c the component
     * @param plane the output plane
     * @param stride the row stride
     * @param px the column of the block in the output plane
     * @param py the row of the block in the output plane
     * @param w the block width
     * @param h the block height
     * @param p0 the list 0 prediction
     * @param p1 the list 1 prediction
     * @param off the index of the block in the list predictions
     * @param bs the row stride of the list predictions
     * @param ref0 the list 0 reference index, or -1
     * @param ref1 the list 1 reference index, or -1
     */
    private void weighted(int c, short[] plane, int stride, int px, int py, int w, int h, int[] p0, int[] p1, int off,
            int bs, int ref0, int ref1) {
        var wp = sh.weights;
        var scale = 1 << (bitDepth - 8);
        var denom = wp.log2Denom[c];
        if (ref0 >= 0 && ref1 >= 0) {
            var w0 = wp.weight[0][ref0][c];
            var w1 = wp.weight[1][ref1][c];
            var o = wp.offset[0][ref0][c] * scale + wp.offset[1][ref1][c] * scale;
            var shift = denom + 1 + hr;
            var round = 1 << shift >> 1;
            var apply = round + (o * (1 << (shift - 1))) + (w0 + w1) * OFFS;
            for (var j = 0; j < h; j++) {
                var d = (py + j) * stride + px;
                var s = off + j * bs;
                for (var i = 0; i < w; i++) {
                    plane[d + i] = (short) Math.clamp((p0[s + i] * w0 + p1[s + i] * w1 + apply) >> shift, 0, maxVal);
                }
            }
            return;
        }
        var list = ref0 >= 0 ? 0 : 1;
        var ref = ref0 >= 0 ? ref0 : ref1;
        var src = ref0 >= 0 ? p0 : p1;
        var w0 = wp.weight[list][ref][c];
        var o = wp.offset[list][ref][c] * scale;
        var shift = denom + hr;
        if (w0 != 1 << denom) {
            var round = shift > 0 ? 1 << (shift - 1) : 0;
            for (var j = 0; j < h; j++) {
                var d = (py + j) * stride + px;
                var s = off + j * bs;
                for (var i = 0; i < w; i++) {
                    plane[d + i] = (short) Math.clamp(((w0 * (src[s + i] + OFFS) + round) >> shift) + o, 0, maxVal);
                }
            }
        } else {
            var round = 1 << (hr - 1);
            for (var j = 0; j < h; j++) {
                var d = (py + j) * stride + px;
                var s = off + j * bs;
                for (var i = 0; i < w; i++) {
                    plane[d + i] = (short) Math.clamp((((src[s + i] + OFFS) + round) >> hr) + o, 0, maxVal);
                }
            }
        }
    }

    /**
     * Predicts the subblock-based temporal merge candidate's subblocks, merging runs of 8x8 subblocks with the same
     * motion ({@code xSubPuMC}).
     *
     * @param cu the coding unit
     */
    private void predictSubPu(VvcCu cu) {
        var numX = Math.max(cu.w >> 3, 1);
        var numY = Math.max(cu.h >> 3, 1);
        var puH = numY == 1 ? cu.h : 8;
        var puW = numX == 1 ? cu.w : 8;
        var ver = cu.h > cu.w;
        var fstStart = !ver ? cu.y : cu.x;
        var secStart = !ver ? cu.x : cu.y;
        var fstEnd = !ver ? cu.y + cu.h : cu.x + cu.w;
        var secEnd = !ver ? cu.x + cu.w : cu.y + cu.h;
        var fstStep = !ver ? puH : puW;
        var secStep = !ver ? puW : puH;
        var anyScaled = scaled(sh.refPics[0][0]) || (sh.isB() && scaled(sh.refPics[1][0]));
        for (var fst = fstStart; fst < fstEnd; fst += fstStep) {
            for (var sec = secStart; sec < secEnd; sec += secStep) {
                var x = !ver ? sec : fst;
                var y = !ver ? fst : sec;
                var cur = (y >> 2) * f.width4 + (x >> 2);
                var length = secStep;
                var later = sec + secStep;
                while (later < secEnd) {
                    var lx = !ver ? later : fst;
                    var ly = !ver ? fst : later;
                    var other = (ly >> 2) * f.width4 + (lx >> 2);
                    if (!anyScaled && sameSpatial(cur, other)) {
                        length += secStep;
                    } else {
                        break;
                    }
                    later += secStep;
                }
                var dx = !ver ? length : puW;
                var dy = !ver ? puH : length;
                var r0 = f.sref0[cur];
                var r1 = f.sref1[cur];
                predictUniform(cu, x, y, dx, dy, r0, r1, f.smvx0[cur], f.smvy0[cur], f.smvx1[cur], f.smvy1[cur],
                        r0 >= 0 && r1 >= 0);
                sec = later - secStep;
            }
        }
    }

    /**
     * {@return whether two 4x4 blocks of the spatial field hold the same motion}
     *
     * @param a the index of the first block
     * @param b the index of the second block
     */
    private boolean sameSpatial(int a, int b) {
        if (f.sref0[a] != f.sref0[b] || f.sref1[a] != f.sref1[b]) {
            return false;
        }
        if (f.sref0[a] >= 0 && (f.smvx0[a] != f.smvx0[b] || f.smvy0[a] != f.smvy0[b])) {
            return false;
        }
        return f.sref1[a] < 0 || (f.smvx1[a] == f.smvx1[b] && f.smvy1[a] == f.smvy1[b]);
    }

    /**
     * Predicts a bi-predicted unit with bi-directional optical flow in subblocks of at most 16x16
     * ({@code xSubPuBio}).
     *
     * @param cu the coding unit
     */
    private void predictBdofSubblocks(VvcCu cu) {
        var sw = Math.min(16, cu.w);
        var shh = Math.min(16, cu.h);
        for (var y = cu.y; y < cu.y + cu.h; y += shh) {
            for (var x = cu.x; x < cu.x + cu.w; x += sw) {
                bdofBlock(cu, x, y, sw, shh, cu.mvx[0][0], cu.mvy[0][0], cu.mvx[1][0], cu.mvy[1][0], true);
            }
        }
    }

    /**
     * Predicts a bi-predicted block, with or without BDOF on luma, and writes the output samples.
     *
     * @param cu the coding unit
     * @param x the luma column of the block
     * @param y the luma row of the block
     * @param w the block width
     * @param h the block height
     * @param mx0 the horizontal list 0 vector
     * @param my0 the vertical list 0 vector
     * @param mx1 the horizontal list 1 vector
     * @param my1 the vertical list 1 vector
     * @param bdof whether bi-directional optical flow applies
     */
    private void bdofBlock(VvcCu cu, int x, int y, int w, int h, int mx0, int my0, int mx1, int my1,
            boolean bdof) {
        for (var l = 0; l < 2; l++) {
            var ref = sh.refPics[l][cu.refIdx[l]];
            var wrap = clipForPrediction(l == 0 ? mx0 : mx1, l == 0 ? my0 : my1, cu, cu.x, cu.y, cu.w)
                    && wrapEnabled(ref);
            var mvx = wrapMv[0];
            var mvy = wrapMv[1];
            var hpel = cu.imv == VvcCu.IMV_HALF;
            predictBlock(0, cu, ref, mvx, mvy, x, y, w, h, predY[l], 0, w, wrap, FILTER_REGULAR, hpel);
            if (bdof) {
                bdofBorder(l, w, h, mvx, mvy);
            }
            if (chromaFormat != 0) {
                predictBlock(1, cu, ref, mvx, mvy, x >> sx, y >> sy, w >> sx, h >> sy, predCb[l], 0, w >> sx, wrap,
                        FILTER_REGULAR, hpel);
                predictBlock(2, cu, ref, mvx, mvy, x >> sx, y >> sy, w >> sx, h >> sy, predCr[l], 0, w >> sx, wrap,
                        FILTER_REGULAR, hpel);
            }
        }
        if (bdof) {
            applyBdof(x, y, w, h);
            combine(cu, x, y, w, h, cu.refIdx[0], cu.refIdx[1], 0, w, true);
        } else {
            combine(cu, x, y, w, h, cu.refIdx[0], cu.refIdx[1], 0, w, false);
        }
    }

    /**
     * Builds the extended BDOF block of a list: the 14-bit prediction surrounded by the nearest integer reference
     * samples (8.5.6.3.1), read from {@link #win} which still holds the luma window of the prediction.
     *
     * @param l the list
     * @param w the luma block width
     * @param h the luma block height
     * @param mvx the horizontal vector, in 1/16 luma samples
     * @param mvy the vertical vector, in 1/16 luma samples
     */
    private void bdofBorder(int l, int w, int h, int mvx, int mvy) {
        var ext = bdofExt[l];
        var es = w + 2;
        var p = predY[l];
        for (var j = 0; j < h; j++) {
            System.arraycopy(p, j * w, ext, (j + 1) * es + 1, w);
        }
        var fracX = mvx & 15;
        var fracY = mvy & 15;
        var xOff = fracX < 8 ? 1 : 0;
        var yOff = fracY < 8 ? 1 : 0;
        var ws = w + 2 * MARGIN + 1;
        var base = MARGIN * ws + MARGIN;
        for (var j = 0; j < h; j++) {
            var r = base + (j + 1 - yOff) * ws - xOff;
            ext[(j + 1) * es] = (win[r] << hr) - OFFS;
            ext[(j + 1) * es + w + 1] = (win[r + w + 1] << hr) - OFFS;
        }
        var top = base - yOff * ws - xOff;
        var bottom = base + (h + 1 - yOff) * ws - xOff;
        for (var i = 0; i < w + 2; i++) {
            ext[i] = (win[top + i] << hr) - OFFS;
            ext[(h + 1) * es + i] = (win[bottom + i] << hr) - OFFS;
        }
    }

    /**
     * {@return {@code numer >> Floor( Log2( denom ) )}, the division of the BDOF refinement}
     *
     * @param numer the numerator
     * @param denom the denominator
     */
    private static int msbShift(int numer, int denom) {
        var msb = 0;
        while (msb < 32 && denom >= (1 << msb)) {
            msb++;
        }
        return numer >> (msb - 1);
    }

    /**
     * Applies bi-directional optical flow to the luma of a block (8.5.6.5) and writes the output samples.
     *
     * @param x the luma column of the block
     * @param y the luma row of the block
     * @param w the block width
     * @param h the block height
     */
    private void applyBdof(int x, int y, int w, int h) {
        var es = w + 2;
        var eh = h + 2;
        for (var l = 0; l < 2; l++) {
            var src = bdofExt[l];
            var gx = gradX[l];
            var gy = gradY[l];
            DSP.gradients(src, gx, gy, es, w, h);
            for (var j = 1; j <= h; j++) {
                var row = j * es;
                gx[row] = gx[row + 1];
                gx[row + w + 1] = gx[row + w];
                gy[row] = gy[row + 1];
                gy[row + w + 1] = gy[row + w];
                src[row] = src[row + 1];
                src[row + w + 1] = src[row + w];
            }
            System.arraycopy(gx, es, gx, 0, es);
            System.arraycopy(gx, h * es, gx, (eh - 1) * es, es);
            System.arraycopy(gy, es, gy, 0, es);
            System.arraycopy(gy, h * es, gy, (eh - 1) * es, es);
            System.arraycopy(src, es, src, 0, es);
            System.arraycopy(src, h * es, src, (eh - 1) * es, es);
        }
        var s0 = bdofExt[0];
        var s1 = bdofExt[1];
        var gx0 = gradX[0];
        var gx1 = gradX[1];
        var gy0 = gradY[0];
        var gy1 = gradY[1];
        var shiftNum = 15 - bitDepth;
        var offset = (1 << (shiftNum - 1)) + 2 * OFFS;
        var limit = 15;
        var plane = f.pic.planes[0];
        var stride = f.pic.planeWidth[0];
        for (var yu = 0; yu < h >> 2; yu++) {
            for (var xu = 0; xu < w >> 2; xu++) {
                var sumAbsGx = 0;
                var sumAbsGy = 0;
                var sumDix = 0;
                var sumDiy = 0;
                var sumSign = 0;
                for (var j = 0; j < 6; j++) {
                    for (var i = 0; i < 6; i++) {
                        var p = (yu * 4 + j) * es + xu * 4 + i;
                        var tgx = (gx0[p] + gx1[p]) >> 1;
                        var tgy = (gy0[p] + gy1[p]) >> 1;
                        var di = (s1[p] >> 4) - (s0[p] >> 4);
                        sumAbsGx += Math.abs(tgx);
                        sumAbsGy += Math.abs(tgy);
                        sumDix += tgx < 0 ? -di : tgx == 0 ? 0 : di;
                        sumDiy += tgy < 0 ? -di : tgy == 0 ? 0 : di;
                        sumSign += tgy < 0 ? -tgx : tgy == 0 ? 0 : tgx;
                    }
                }
                var tx = sumAbsGx == 0 ? 0 : Math.clamp(msbShift(sumDix * 4, sumAbsGx), -limit, limit);
                var mains = sumSign >> 12;
                var secs = sumSign & ((1 << 12) - 1);
                var data = ((tx * mains) * (1 << 12) + tx * secs) >> 1;
                var ty = sumAbsGy == 0 ? 0 : Math.clamp(msbShift(sumDiy * 4 - data, sumAbsGy), -limit, limit);
                DSP.bdofSamples(s0, s1, gx0, gx1, gy0, gy1, (yu * 4 + 1) * es + xu * 4 + 1, es, plane,
                        (y + yu * 4) * stride + x + xu * 4, stride, tx, ty, shiftNum, offset, maxVal);
            }
        }
    }

    /**
     * Refines the motion of a bi-predicted merge unit by decoder side motion vector refinement (8.5.3) in subblocks of
     * at most 16x16, predicts each subblock and stores the refined vectors in the picture's motion field.
     *
     * @param cu the coding unit
     * @param bdof whether bi-directional optical flow applies
     */
    private void predictDmvr(VvcCu cu, boolean bdof) {
        var m0x = cu.mvx[0][0];
        var m0y = cu.mvy[0][0];
        var m1x = cu.mvx[1][0];
        var m1y = cu.mvy[1][0];
        var bs = cu.w + 4;
        var extW = cu.w + 4;
        var extH = cu.h + 4;
        for (var l = 0; l < 2; l++) {
            var ref = sh.refPics[l][cu.refIdx[l]];
            var wrap = clipForPrediction(l == 0 ? m0x : m1x, l == 0 ? m0y : m1y, cu, cu.x, cu.y, cu.w)
                    && wrapEnabled(ref);
            var mvx = wrapMv[0];
            var mvy = wrapMv[1];
            mvx -= 2 << 4;
            mvy -= 2 << 4;
            bilinear(cu, ref, mvx, mvy, cu.x, cu.y, extW, extH, dmvrBuf[l], bs, wrap);
        }
        var dx = Math.min(16, cu.w);
        var dy = Math.min(16, cu.h);
        var pic = f.pic;
        for (var y = cu.y; y < cu.y + cu.h; y += dy) {
            for (var x = cu.x; x < cu.x + cu.w; x += dx) {
                var xs = x - cu.x;
                var ys = y - cu.y;
                var base = (ys + 2) * bs + xs + 2;
                var minCost = sad(base, base, dx, dy, bs);
                minCost -= minCost >> 2;
                var deltaX = 0;
                var deltaY = 0;
                if (minCost >= (long) dx * dy) {
                    var bestH = 0;
                    var bestV = 0;
                    var center = minCost;
                    var k = 0;
                    for (var ver = -2; ver <= 2; ver++) {
                        for (var hor = -2; hor <= 2; hor++, k++) {
                            long cost;
                            if (ver == 0 && hor == 0) {
                                cost = center;
                            } else {
                                var offset = hor + ver * bs;
                                cost = sad(base + offset, base - offset, dx, dy, bs);
                            }
                            sads[k] = cost;
                            if (cost < minCost) {
                                minCost = cost;
                                bestH = hor;
                                bestV = ver;
                            }
                        }
                    }
                    deltaX = bestH << 4;
                    deltaY = bestV << 4;
                    if (Math.abs(deltaX) != 2 << 4 && Math.abs(deltaY) != 2 << 4) {
                        var c = 12 + bestV * 5 + bestH;
                        deltaX += subPel(sads[c], sads[c - 1], sads[c + 1]);
                        deltaY += subPel(sads[c], sads[c - 5], sads[c + 5]);
                    }
                }
                var bdofSub = minCost >= 2L * dx * dy && bdof;
                var r0x = Math.clamp(m0x + deltaX, -(1 << 17), (1 << 17) - 1);
                var r0y = Math.clamp(m0y + deltaY, -(1 << 17), (1 << 17) - 1);
                var r1x = Math.clamp(m1x - deltaX, -(1 << 17), (1 << 17) - 1);
                var r1y = Math.clamp(m1y - deltaY, -(1 << 17), (1 << 17) - 1);
                dmvrFinal(cu, x, y, dx, dy, m0x, m0y, m1x, m1y, r0x, r0y, r1x, r1y, bdofSub);
                var x4 = x >> 2;
                var x4e = (x + dx) >> 2;
                for (var yy = y >> 2; yy < (y + dy) >> 2; yy++) {
                    for (var xx = x4; xx < x4e; xx++) {
                        var i4 = yy * f.width4 + xx;
                        pic.mvx0[i4] = r0x;
                        pic.mvy0[i4] = r0y;
                        pic.mvx1[i4] = r1x;
                        pic.mvy1[i4] = r1y;
                    }
                }
            }
        }
    }

    /**
     * {@return the parametric sub-sample offset of the DMVR search (8.5.3.4), in 1/16 samples}
     *
     * @param center the cost at the integer position
     * @param minus the cost one sample before
     * @param plus the cost one sample after
     */
    private static int subPel(long center, long minus, long plus) {
        var denominator = minus + plus - (center << 1);
        if (denominator == 0) {
            return 0;
        }
        if (minus != center && plus != center) {
            return divMax7((minus - plus) << 4, denominator);
        }
        return minus == center ? -8 : 8;
    }

    /**
     * {@return the quotient of the DMVR sub-sample division, at most 7 sixteenths in magnitude}
     *
     * @param n the numerator
     * @param d the denominator, positive
     */
    private static int divMax7(long n, long d) {
        var sign = false;
        if (n < 0) {
            sign = true;
            n = -n;
        }
        var q = 0;
        d <<= 3;
        if (n >= d) {
            n -= d;
            q++;
        }
        q <<= 1;
        d >>= 1;
        if (n >= d) {
            n -= d;
            q++;
        }
        q <<= 1;
        if (n >= (d >> 1)) {
            q++;
        }
        return sign ? -q : q;
    }

    /**
     * {@return the DMVR matching cost of a subblock: the sum of absolute differences of every other row}
     *
     * @param off0 the index of the list 0 block in its buffer
     * @param off1 the index of the list 1 block in its buffer
     * @param w the subblock width
     * @param h the subblock height
     * @param stride the row stride of the buffers
     */
    private long sad(int off0, int off1, int w, int h, int stride) {
        var b0 = dmvrBuf[0];
        var b1 = dmvrBuf[1];
        long sum = 0;
        for (var j = 0; j < h; j += 2) {
            var p0 = off0 + j * stride;
            var p1 = off1 + j * stride;
            for (var i = 0; i < w; i++) {
                sum += Math.abs(b0[p0 + i] - b1[p1 + i]);
            }
        }
        return sum;
    }

    /**
     * Predicts the bilinear luma block of the DMVR search at 10-bit precision ({@code xinitMC}).
     *
     * @param cu the coding unit
     * @param ref the reference picture
     * @param mvx the horizontal vector, in 1/16 luma samples
     * @param mvy the vertical vector, in 1/16 luma samples
     * @param x the luma column of the block
     * @param y the luma row of the block
     * @param w the block width
     * @param h the block height
     * @param dst receives the bilinear prediction
     * @param dstStride the row stride of {@code dst}
     * @param wrap whether reference wraparound applies
     */
    private void bilinear(VvcCu cu, VvcPicture ref, int mvx, int mvy, int x, int y, int w, int h, int[] dst,
            int dstStride, boolean wrap) {
        var fracX = mvx & 15;
        var fracY = mvy & 15;
        var ix = x + (mvx >> 4);
        var iy = y + (mvy >> 4);
        var ws = w + 2 * MARGIN + 1;
        fetch(ref, 0, cu, wrap, ix - MARGIN, iy - MARGIN, ws, h + 2 * MARGIN + 1);
        var base = MARGIN * ws + MARGIN;
        var cx = VvcInterTables.BILINEAR[fracX];
        var cy = VvcInterTables.BILINEAR[fracY];
        var shift1 = bitDepth - 6;
        if (fracX == 0 && fracY == 0) {
            var s = 10 - bitDepth;
            for (var j = 0; j < h; j++) {
                for (var i = 0; i < w; i++) {
                    dst[j * dstStride + i] = win[base + j * ws + i] << s;
                }
            }
        } else if (fracY == 0) {
            for (var j = 0; j < h; j++) {
                for (var i = 0; i < w; i++) {
                    var p = base + j * ws + i;
                    dst[j * dstStride + i] = (win[p] * cx[0] + win[p + 1] * cx[1] + (1 << (shift1 - 1))) >> shift1;
                }
            }
        } else if (fracX == 0) {
            for (var j = 0; j < h; j++) {
                for (var i = 0; i < w; i++) {
                    var p = base + j * ws + i;
                    dst[j * dstStride + i] = (win[p] * cy[0] + win[p + ws] * cy[1] + (1 << (shift1 - 1))) >> shift1;
                }
            }
        } else {
            for (var j = 0; j < h + 1; j++) {
                for (var i = 0; i < w; i++) {
                    var p = base + j * ws + i;
                    tmp[j * w + i] = (win[p] * cx[0] + win[p + 1] * cx[1] + (1 << (shift1 - 1))) >> shift1;
                }
            }
            for (var j = 0; j < h; j++) {
                for (var i = 0; i < w; i++) {
                    var p = j * w + i;
                    dst[j * dstStride + i] = (tmp[p] * cy[0] + tmp[p + w] * cy[1] + 8) >> 4;
                }
            }
        }
    }

    /**
     * Predicts a DMVR subblock with its refined vectors, reading reference samples outside the window of the initial
     * vectors as the nearest window samples ({@code xFinalPaddedMCForDMVR}), and writes the output samples.
     *
     * @param cu the coding unit
     * @param x the luma column of the subblock
     * @param y the luma row of the subblock
     * @param w the block width
     * @param h the block height
     * @param m0x the horizontal initial list 0 vector
     * @param m0y the vertical initial list 0 vector
     * @param m1x the horizontal initial list 1 vector
     * @param m1y the vertical initial list 1 vector
     * @param r0x the horizontal refined list 0 vector
     * @param r0y the vertical refined list 0 vector
     * @param r1x the horizontal refined list 1 vector
     * @param r1y the vertical refined list 1 vector
     * @param bdof whether bi-directional optical flow applies
     */
    private void dmvrFinal(VvcCu cu, int x, int y, int w, int h, int m0x, int m0y, int m1x, int m1y, int r0x, int r0y,
            int r1x, int r1y, boolean bdof) {
        for (var l = 0; l < 2; l++) {
            var ref = sh.refPics[l][cu.refIdx[l]];
            var startX = l == 0 ? m0x : m1x;
            var startY = l == 0 ? m0y : m1y;
            var wrap = clipForPrediction(l == 0 ? r0x : r1x, l == 0 ? r0y : r1y, cu, x, y, w) && wrapEnabled(ref);
            var mvx = wrapMv[0];
            var mvy = wrapMv[1];
            for (var c = 0; c < (chromaFormat == 0 ? 1 : 3); c++) {
                var csx = c == 0 ? 0 : sx;
                var csy = c == 0 ? 0 : sy;
                var taps = c == 0 ? 8 : 4;
                var half = taps / 2 - 1;
                var px = startX - (half << (4 + csx));
                var py = startY - (half << (4 + csy));
                var pwrap = false;
                if (wrapEnabled(ref)) {
                    pwrap = wrapClip(px, py, x, y, w);
                    px = wrapMv[0];
                    py = wrapMv[1];
                } else {
                    clipForPrediction(px, py, cu, x, y, w);
                    px = wrapMv[0];
                    py = wrapMv[1];
                }
                var bx = x >> csx;
                var by = y >> csy;
                var cw = w >> csx;
                var ch = h >> csy;
                wx0 = bx + (px >> (4 + csx));
                wy0 = by + (py >> (4 + csy));
                wx1 = wx0 + cw + taps - 2;
                wy1 = wy0 + ch + taps - 2;
                windowClamp = true;
                var dst = c == 0 ? predY[l] : c == 1 ? predCb[l] : predCr[l];
                predictBlock(c, cu, ref, mvx, mvy, bx, by, cw, ch, dst, 0, cw, pwrap, FILTER_REGULAR,
                        cu.imv == VvcCu.IMV_HALF);
                windowClamp = false;
                if (c == 0 && bdof) {
                    bdofBorder(l, w, h, mvx, mvy);
                }
            }
        }
        if (bdof) {
            applyBdof(x, y, w, h);
            combine(cu, x, y, w, h, cu.refIdx[0], cu.refIdx[1], 0, w, true);
        } else {
            combine(cu, x, y, w, h, cu.refIdx[0], cu.refIdx[1], 0, w, false);
        }
    }

    /**
     * Predicts an affine coding unit in 4x4 luma and chroma subblocks, with prediction refinement with optical flow
     * for luma (8.5.6.4).
     *
     * @param cu the coding unit
     */
    private void predictAffine(VvcCu cu) {
        for (var l = 0; l < 2; l++) {
            if (cu.refIdx[l] < 0) {
                continue;
            }
            affineList(cu, l, cu.refIdx[0] >= 0 && cu.refIdx[1] >= 0);
        }
        combine(cu, cu.x, cu.y, cu.w, cu.h, cu.refIdx[0], cu.refIdx[1], 0, cu.w, false);
    }

    /**
     * Predicts the 4x4 subblocks of one list of an affine coding unit.
     *
     * @param cu the coding unit
     * @param list the reference picture list, 0 or 1
     * @param bi whether both lists are predicted
     */
    private void affineList(VvcCu cu, int list, boolean bi) {
        var ref = sh.refPics[list][cu.refIdx[list]];
        var refScaled = scaled(ref);
        var ltx = cu.mvx[list][0];
        var lty = cu.mvy[list][0];
        var rtx = cu.mvx[list][1];
        var rty = cu.mvy[list][1];
        var lbx = cu.mvx[list][2];
        var lby = cu.mvy[list][2];
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
        var spread = VvcMotion.spreadOverLimit(dHorX, dHorY, dVerX, dVerY, cu.interDir);
        var prof = sps.prof && !ph.profDisabled && !spread && !refScaled
                && !((cu.sixParam && ltx == rtx && lty == rty && ltx == lbx && lty == lby)
                || (!cu.sixParam && ltx == rtx && lty == rty));
        if (prof) {
            var qhx = dHorX * 4;
            var qhy = dHorY * 4;
            var qvx = dVerX * 4;
            var qvy = dVerY * 4;
            profDx[0] = ((dHorX + dVerX) * 2) - ((qhx + qvx) * 2);
            profDy[0] = ((dHorY + dVerY) * 2) - ((qhy + qvy) * 2);
            for (var w = 1; w < 4; w++) {
                profDx[w] = profDx[w - 1] + qhx;
                profDy[w] = profDy[w - 1] + qhy;
            }
            for (var h = 1; h < 4; h++) {
                for (var w = 0; w < 4; w++) {
                    profDx[h * 4 + w] = profDx[(h - 1) * 4 + w] + qvx;
                    profDy[h * 4 + w] = profDy[(h - 1) * 4 + w] + qvy;
                }
            }
            for (var i = 0; i < 16; i++) {
                profDx[i] = Math.clamp(roundAffine(profDx[i], 8), -31, 31);
                profDy[i] = Math.clamp(roundAffine(profDy[i], 8), -31, 31);
            }
        }
        var horMax = (f.width + 8 - cu.x - 1) << 4;
        var horMin = (-f.ctuSize - 8 - cu.x + 1) << 4;
        var verMax = (f.height + 8 - cu.y - 1) << 4;
        var verMin = (-f.ctuSize - 8 - cu.y + 1) << 4;
        var clipSub = subpicClamp(cu);
        var wrapOn = pps.refWraparound && wrapEnabled(ref);
        var mvx = list == 0 ? f.smvx0 : f.smvx1;
        var mvy = list == 0 ? f.smvy0 : f.smvy1;
        var wb = cu.w >> 2;
        for (var c = 0; c < (chromaFormat == 0 ? 1 : 3); c++) {
            var csx = c == 0 ? 0 : sx;
            var csy = c == 0 ? 0 : sy;
            var cw = cu.w >> csx;
            var ch = cu.h >> csy;
            var dst = c == 0 ? predY[list] : c == 1 ? predCb[list] : predCr[list];
            for (var h = 0; h < ch; h += 4) {
                for (var w = 0; w < cw; w += 4) {
                    int vx;
                    int vy;
                    if (c == 0) {
                        var i4 = ((cu.y >> 2) + (h >> 2)) * f.width4 + (cu.x >> 2) + (w >> 2);
                        vx = mvx[i4];
                        vy = mvy[i4];
                    } else {
                        var bx = (w >> 2) << csx;
                        var by = (h >> 2) << csy;
                        var sumX = 0;
                        var sumY = 0;
                        if (sx == 1 && sy == 1) {
                            var a = ((cu.y >> 2) + by) * f.width4 + (cu.x >> 2) + bx;
                            var b = a + f.width4 + 1;
                            sumX = mvx[a] + mvx[b];
                            sumY = mvy[a] + mvy[b];
                            vx = roundAffine(sumX, 1);
                            vy = roundAffine(sumY, 1);
                        } else {
                            var a = ((cu.y >> 2) + by) * f.width4 + (cu.x >> 2) + bx;
                            vx = mvx[a];
                            vy = mvy[a];
                        }
                    }
                    var wrap = false;
                    var subX = cu.x + (w << csx);
                    var subY = cu.y + (h << csy);
                    if (wrapOn) {
                        wrap = wrapClip(vx, vy, subX, subY, 4 << csx);
                        vx = wrapMv[0];
                        vy = wrapMv[1];
                    } else if (!refScaled && clipSub) {
                        vx = clipMvX(vx, cu, cu.x);
                        vy = clipMvY(vy, cu, cu.y);
                    } else {
                        vx = Math.clamp(vx, horMin, horMax);
                        vy = Math.clamp(vy, verMin, verMax);
                    }
                    var bx = (cu.x >> csx) + w;
                    var by = (cu.y >> csy) + h;
                    var off = h * cw + w;
                    if (refScaled) {
                        predictRpr(c, ref, list, cu.refIdx[list], vx, vy, bx, by, 4, 4, dst, off, cw, FILTER_AFFINE,
                                false, wrap);
                        continue;
                    }
                    predictBlock(c, cu, ref, vx, vy, bx, by, 4, 4, dst, off, cw, wrap,
                            c == 0 ? FILTER_AFFINE : FILTER_REGULAR, false);
                    if (c == 0 && prof) {
                        applyProf(dst, off, cw, vx, vy);
                    }
                }
            }
        }
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
     * Refines a 4x4 luma subblock prediction with optical flow (8.5.6.4), using the integer samples around it in
     * {@link #win}.
     *
     * @param dst the prediction, refined in place
     * @param off the index of the subblock in {@code dst}
     * @param stride the row stride of {@code dst}
     * @param mvx the horizontal subblock vector
     * @param mvy the vertical subblock vector
     */
    private void applyProf(int[] dst, int off, int stride, int mvx, int mvy) {
        var fracX = mvx & 15;
        var fracY = mvy & 15;
        var xOff = fracX >> 3;
        var yOff = fracY >> 3;
        var ws = 4 + 2 * MARGIN + 1;
        var base = MARGIN * ws + MARGIN;
        for (var j = 0; j < 4; j++) {
            for (var i = 0; i < 4; i++) {
                profExt[(j + 1) * 6 + i + 1] = dst[off + j * stride + i];
            }
        }
        var top = base - (1 - yOff) * ws + xOff - 1;
        var bottom = top + 5 * ws;
        for (var i = 0; i < 6; i++) {
            profExt[i] = (win[top + i] << hr) - OFFS;
            profExt[30 + i] = (win[bottom + i] << hr) - OFFS;
        }
        for (var j = 0; j < 4; j++) {
            var r = base + (j + yOff) * ws + xOff;
            profExt[(j + 1) * 6] = (win[r - 1] << hr) - OFFS;
            profExt[(j + 1) * 6 + 5] = (win[r + 4] << hr) - OFFS;
        }
        for (var j = 0; j < 4; j++) {
            for (var i = 0; i < 4; i++) {
                var p = (j + 1) * 6 + i + 1;
                profGy[j * 4 + i] = (profExt[p + 6] >> 6) - (profExt[p - 6] >> 6);
                profGx[j * 4 + i] = (profExt[p + 1] >> 6) - (profExt[p - 1] >> 6);
            }
        }
        var limit = 1 << Math.max(bitDepth + 1, 13);
        for (var j = 0; j < 4; j++) {
            for (var i = 0; i < 4; i++) {
                var k = j * 4 + i;
                var di = Math.clamp(profDx[k] * profGx[k] + profDy[k] * profGy[k], -limit, limit - 1);
                dst[off + j * stride + i] = (short) (profExt[(j + 1) * 6 + i + 1] + di);
            }
        }
    }

    /**
     * Predicts a geometric partitioning coding unit: both partitions' uni-directional predictions and their blend
     * (8.5.7).
     *
     * @param cu the coding unit
     */
    private void predictGpm(VvcCu cu) {
        for (var p = 0; p < 2; p++) {
            var list = cu.gpmList[p];
            var target = p;
            var ref = sh.refPics[list][cu.gpmRef[p]];
            var mvx = cu.gpmMvx[p];
            var mvy = cu.gpmMvy[p];
            var hpel = false;
            if (scaled(ref)) {
                predictRpr(0, ref, list, cu.gpmRef[p], mvx, mvy, cu.x, cu.y, cu.w, cu.h, predY[target], 0, cu.w,
                        FILTER_REGULAR, hpel, false);
                if (chromaFormat != 0) {
                    predictRpr(1, ref, list, cu.gpmRef[p], mvx, mvy, cu.x >> sx, cu.y >> sy, cu.w >> sx, cu.h >> sy,
                            predCb[target], 0, cu.w >> sx, FILTER_REGULAR, hpel, false);
                    predictRpr(2, ref, list, cu.gpmRef[p], mvx, mvy, cu.x >> sx, cu.y >> sy, cu.w >> sx, cu.h >> sy,
                            predCr[target], 0, cu.w >> sx, FILTER_REGULAR, hpel, false);
                }
                continue;
            }
            var wrap = clipForPrediction(mvx, mvy, cu, cu.x, cu.y, cu.w) && wrapEnabled(ref);
            mvx = wrapMv[0];
            mvy = wrapMv[1];
            predictBlock(0, cu, ref, mvx, mvy, cu.x, cu.y, cu.w, cu.h, predY[target], 0, cu.w, wrap, FILTER_REGULAR,
                    hpel);
            if (chromaFormat != 0) {
                predictBlock(1, cu, ref, mvx, mvy, cu.x >> sx, cu.y >> sy, cu.w >> sx, cu.h >> sy, predCb[target], 0,
                        cu.w >> sx, wrap, FILTER_REGULAR, hpel);
                predictBlock(2, cu, ref, mvx, mvy, cu.x >> sx, cu.y >> sy, cu.w >> sx, cu.h >> sy, predCr[target], 0,
                        cu.w >> sx, wrap, FILTER_REGULAR, hpel);
            }
        }
        var pic = f.pic;
        VvcGpm.blend(pic.planes[0], cu.y * pic.planeWidth[0] + cu.x, pic.planeWidth[0], predY[0], predY[1], cu.w,
                cu.h, cu.w, cu.h, cu.gpmSplit, 0, 0, bitDepth);
        if (chromaFormat != 0) {
            for (var c = 1; c <= 2; c++) {
                var pr = c == 1 ? predCb : predCr;
                VvcGpm.blend(pic.planes[c], (cu.y >> sy) * pic.planeWidth[c] + (cu.x >> sx), pic.planeWidth[c], pr[0],
                        pr[1], cu.w >> sx, cu.h >> sy, cu.w, cu.h, cu.gpmSplit, sx, sy, bitDepth);
            }
        }
    }

    /**
     * Predicts an IBC coding unit by copying the reconstructed samples its block vector points to (8.6.3).
     *
     * @param cu the coding unit
     * @param motion the motion derivation
     */
    void predictIbc(VvcCu cu, VvcMotion motion) {
        var pic = f.pic;
        var bvx = cu.mvx[0][0] >> 4;
        var bvy = cu.mvy[0][0] >> 4;
        for (var c = 0; c < (chromaFormat == 0 || !cu.hasChroma(chromaFormat) ? 1 : 3); c++) {
            if (c == 0 && !cu.hasLuma()) {
                continue;
            }
            var csx = c == 0 ? 0 : sx;
            var csy = c == 0 ? 0 : sy;
            var plane = pic.planes[c];
            var stride = pic.planeWidth[c];
            var x0 = cu.x >> csx;
            var y0 = cu.y >> csy;
            var w = cu.w >> csx;
            var h = cu.h >> csy;
            var rx = x0 + (bvx >> csx);
            var ry = y0 + (bvy >> csy);
            for (var j = 0; j < h; j++) {
                var yy = Math.clamp(ry + j, 0, pic.planeHeight[c] - 1);
                for (var i = 0; i < w; i++) {
                    var xx = Math.clamp(rx + i, 0, stride - 1);
                    plane[(y0 + j) * stride + x0 + i] = plane[yy * stride + xx];
                }
            }
        }
    }
}
