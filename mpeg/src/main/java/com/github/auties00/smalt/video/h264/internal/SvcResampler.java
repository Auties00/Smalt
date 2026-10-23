package com.github.auties00.smalt.video.h264.internal;

/**
 * Resamples the constructed intra samples (ITU-T H.264 F.8.6.2) and the residual samples
 * (F.8.6.3) of a reference layer picture for one macroblock of a layer picture of a higher
 * spatial resolution, for frames of 4:2:0 8-bit samples.
 */
final class SvcResampler {
    /**
     * The largest dimension of a reference sample array.
     */
    private static final int MAX_ARRAY = 96;

    /**
     * The 16-phase luma interpolation filter of Intra_Base prediction (ITU-T H.264 Table F-9),
     * four taps per phase.
     */
    private static final int[] FILTER = {
            0, 32, 0, 0, -1, 32, 2, -1, -2, 31, 4, -1, -3, 30, 6, -1, -3, 28, 8, -1, -4, 26, 11, -1, -4, 24, 14, -2,
            -3, 22, 16, -3, -3, 19, 19, -3, -3, 16, 22, -3, -2, 14, 24, -4, -1, 11, 26, -4, -1, 8, 28, -3, -1, 6, 30,
            -3, -1, 4, 31, -2, -1, 2, 32, -1
    };

    /**
     * The reference sample array {@code refSampleArray}.
     */
    private final int[] samples = new int[MAX_ARRAY * MAX_ARRAY];

    /**
     * The availability of each sample of {@link #samples} for Intra_Base prediction, or the
     * transform block identification {@code refTransBlkIdc} of residual resampling.
     */
    private final int[] marks = new int[MAX_ARRAY * MAX_ARRAY];

    /**
     * The vertically interpolated samples {@code tempArray}.
     */
    private final int[] temp = new int[MAX_ARRAY * 16];

    /**
     * Constructs a resampler with its working arrays.
     */
    SvcResampler() {
    }

    /**
     * Resamples the intra samples of the reference layer into one macroblock of the current
     * layer (ITU-T H.264 F.8.6.2 for frames).
     *
     * @param rep the current layer representation
     * @param ref the reference layer arrays
     * @param cur the current layer arrays, whose constructed samples receive the prediction
     * @param mbAddr the macroblock address
     * @param constrainedResampling the {@code constrained_intra_resampling_flag} of the slice
     */
    void resampleIntra(SvcRepresentation rep, SvcLayer ref, SvcLayer cur, int mbAddr, boolean constrainedResampling) {
        var xM = (mbAddr % cur.widthMbs) * 16;
        var yM = (mbAddr / cur.widthMbs) * 16;
        resampleIntraPlane(rep, ref, 0, ref.cY, ref.stride, ref.width, ref.height, cur.cY,
                yM * cur.stride + SvcLayer.PAD + xM, cur.stride, xM, yM, 16, constrainedResampling, mbAddr);
        resampleIntraPlane(rep, ref, 1, ref.cCb, ref.chromaStride, ref.chromaWidth, ref.chromaHeight, cur.cCb,
                (yM / 2) * cur.chromaStride + SvcLayer.PAD + xM / 2, cur.chromaStride, xM / 2, yM / 2, 8,
                constrainedResampling, mbAddr);
        resampleIntraPlane(rep, ref, 1, ref.cCr, ref.chromaStride, ref.chromaWidth, ref.chromaHeight, cur.cCr,
                (yM / 2) * cur.chromaStride + SvcLayer.PAD + xM / 2, cur.chromaStride, xM / 2, yM / 2, 8,
                constrainedResampling, mbAddr);
    }

    /**
     * Resamples one color component of the intra samples of a macroblock (ITU-T H.264
     * F.8.6.2.1 to F.8.6.2.3).
     *
     * @param rep the current layer representation
     * @param ref the reference layer arrays
     * @param chroma 0 for luma, 1 for chroma
     * @param refPlane the reference layer constructed samples of the component
     * @param refStride the row stride of {@code refPlane}
     * @param refW the width of the component in the reference layer
     * @param refH the height of the component in the reference layer
     * @param dst the current layer constructed samples of the component
     * @param dstOffset the index of the macroblock's top-left sample in {@code dst}
     * @param dstStride the row stride of {@code dst}
     * @param x0 the macroblock's horizontal position in the component
     * @param y0 the macroblock's vertical position in the component
     * @param size the macroblock size in the component: 16 or 8
     * @param constrainedResampling the {@code constrained_intra_resampling_flag}
     * @param mbAddr the macroblock address, for the reference layer macroblock lookups
     */
    private void resampleIntraPlane(SvcRepresentation rep, SvcLayer ref, int chroma, byte[] refPlane, int refStride,
                                    int refW, int refH, byte[] dst, int dstOffset, int dstStride, int x0, int y0,
                                    int size, boolean constrainedResampling, int mbAddr) {
        var xRefMin16 = rep.refX16(chroma, x0);
        var yRefMin16 = rep.refY16(chroma, y0);
        var xRefMax16 = rep.refX16(chroma, x0 + size - 1);
        var yRefMax16 = rep.refY16(chroma, y0 + size - 1);
        var refMb = chroma == 0 ? 16 : 8;
        var xOffset = (((xRefMin16 - 64) >> 8) << 4) - (refMb >> 1);
        var yOffset = (((yRefMin16 - 64) >> 8) << 4) - (refMb >> 1);
        var arrayW = (((xRefMax16 + 79) >> 8) << 4) + 3 * (refMb >> 1) - xOffset;
        var arrayH = (((yRefMax16 + 79) >> 8) << 4) + 3 * (refMb >> 1) - yOffset;
        if (arrayW > MAX_ARRAY || arrayH > MAX_ARRAY) {
            throw new com.github.auties00.smalt.MediaException.Malformed("inter-layer resampling ratio out of range");
        }
        var xMin = (xRefMin16 >> 4) - xOffset;
        var yMin = (yRefMin16 >> 4) - yOffset;
        var xMax = ((xRefMax16 + 15) >> 4) - xOffset;
        var yMax = ((yRefMax16 + 15) >> 4) - yOffset;
        var sliceMb = -1;
        if (constrainedResampling) {
            for (var y = yMin + 1; y <= yMax - 1 && sliceMb < 0; y++) {
                for (var x = xMin + 1; x <= xMax - 1; x++) {
                    var xRef = Math.clamp(x + xOffset, 0, refW - 1);
                    var yRef = Math.clamp(y + yOffset, 0, refH - 1);
                    var refAddr = (yRef / refMb) * ref.widthMbs + xRef / refMb;
                    if (SvcMb.intra(ref.mbType[refAddr])) {
                        sliceMb = ref.sliceIdc[refAddr];
                        break;
                    }
                }
            }
        }
        for (var y = 0; y < arrayH; y++) {
            var yRef = Math.clamp(y + yOffset, 0, refH - 1);
            for (var x = 0; x < arrayW; x++) {
                var xRef = Math.clamp(x + xOffset, 0, refW - 1);
                var refAddr = (yRef / refMb) * ref.widthMbs + xRef / refMb;
                var at = y * arrayW + x;
                var available = SvcMb.intra(ref.mbType[refAddr])
                        && (!constrainedResampling || (sliceMb >= 0 && ref.sliceIdc[refAddr] == sliceMb));
                if (available) {
                    samples[at] = refPlane[yRef * refStride + SvcLayer.PAD + xRef] & 0xFF;
                    marks[at] = 1;
                } else {
                    samples[at] = 0;
                    marks[at] = 0;
                }
            }
        }
        fillUnavailable(refMb, arrayW, arrayH, xOffset, yOffset);
        for (var y = 0; y < size; y++) {
            var yRef16 = rep.refY16(chroma, y0 + y);
            var yRef = (yRef16 >> 4) - yOffset;
            var yPhase = (yRef16 - 16 * yOffset) & 15;
            for (var x = 0; x < arrayW; x++) {
                int value;
                if (chroma == 0) {
                    var f = yPhase * 4;
                    value = FILTER[f] * samples[clampRow(yRef - 1, arrayH) * arrayW + x]
                            + FILTER[f + 1] * samples[clampRow(yRef, arrayH) * arrayW + x]
                            + FILTER[f + 2] * samples[clampRow(yRef + 1, arrayH) * arrayW + x]
                            + FILTER[f + 3] * samples[clampRow(yRef + 2, arrayH) * arrayW + x];
                } else {
                    value = (16 - yPhase) * samples[clampRow(yRef, arrayH) * arrayW + x]
                            + yPhase * samples[clampRow(yRef + 1, arrayH) * arrayW + x];
                }
                temp[y * arrayW + x] = value;
            }
        }
        for (var x = 0; x < size; x++) {
            var xRef16 = rep.refX16(chroma, x0 + x);
            var xRef = (xRef16 >> 4) - xOffset;
            var xPhase = (xRef16 - 16 * xOffset) & 15;
            for (var y = 0; y < size; y++) {
                var row = y * arrayW;
                int value;
                if (chroma == 0) {
                    var f = xPhase * 4;
                    value = (FILTER[f] * temp[row + clampRow(xRef - 1, arrayW)]
                            + FILTER[f + 1] * temp[row + clampRow(xRef, arrayW)]
                            + FILTER[f + 2] * temp[row + clampRow(xRef + 1, arrayW)]
                            + FILTER[f + 3] * temp[row + clampRow(xRef + 2, arrayW)] + 512) >> 10;
                    value = Math.clamp(value, 0, 255);
                } else {
                    value = ((16 - xPhase) * temp[row + clampRow(xRef, arrayW)]
                            + xPhase * temp[row + clampRow(xRef + 1, arrayW)] + 128) >> 8;
                }
                dst[dstOffset + y * dstStride + x] = (byte) value;
            }
        }
    }

    /**
     * Keeps an index inside an array dimension.
     *
     * @param index the index to clamp
     * @param size the dimension
     * @return the index clamped to {@code [0, size - 1]}
     */
    private static int clampRow(int index, int size) {
        return Math.clamp(index, 0, size - 1);
    }

    /**
     * Constructs the samples of the reference sample array that are not available for
     * Intra_Base prediction (ITU-T H.264 F.8.6.2.2.2), then marks every sample available.
     *
     * @implNote This implementation constructs the whole array rather than leaving the border of
     * half a reference macroblock unconstructed: the chroma interpolation of the JSVM reference
     * decoder, which produced the conformance outputs, reads samples of that border.
     *
     * @param refMb the macroblock size of the component in the reference layer
     * @param arrayW the array width
     * @param arrayH the array height
     * @param xOffset the reference layer column of array column 0
     * @param yOffset the reference layer row of array row 0
     */
    private void fillUnavailable(int refMb, int arrayW, int arrayH, int xOffset, int yOffset) {
        var half = refMb / 2;
        for (var y = 0; y < arrayH; y++) {
            for (var x = 0; x < arrayW; x++) {
                if (marks[y * arrayW + x] != 0) {
                    continue;
                }
                var xR = Math.floorMod(x + xOffset, refMb);
                var yR = Math.floorMod(y + yOffset, refMb);
                var xD = xR >= half ? xR - refMb : xR + 1;
                var yD = yR >= half ? yR - refMb : yR + 1;
                var horizontal = available(x - xD, y, arrayW, arrayH);
                var vertical = available(x, y - yD, arrayW, arrayH);
                var corner = available(x - xD, y - yD, arrayW, arrayH);
                if (horizontal && vertical) {
                    diagonal(arrayW, xD, yD, x, y, corner);
                } else if (horizontal) {
                    samples[y * arrayW + x] = samples[y * arrayW + x - xD];
                } else if (vertical) {
                    samples[y * arrayW + x] = samples[(y - yD) * arrayW + x];
                } else if (corner) {
                    samples[y * arrayW + x] = samples[(y - yD) * arrayW + x - xD];
                }
            }
        }
    }

    /**
     * Returns whether a sample of the reference sample array is marked available.
     *
     * @param x the column
     * @param y the row
     * @param arrayW the array width
     * @param arrayH the array height
     * @return {@code true} when inside the array and available
     */
    private boolean available(int x, int y, int arrayW, int arrayH) {
        return x >= 0 && y >= 0 && x < arrayW && y < arrayH && marks[y * arrayW + x] != 0;
    }

    /**
     * Constructs one not available sample from its available horizontal and vertical neighbors
     * (ITU-T H.264 F.8.6.2.2.2.1).
     *
     * @param arrayW the array width
     * @param xD the horizontal distance to the available neighbor
     * @param yD the vertical distance to the available neighbor
     * @param x the column of the sample
     * @param y the row of the sample
     * @param cornerAvailable whether the diagonal neighbor is available
     */
    private void diagonal(int arrayW, int xD, int yD, int x, int y, boolean cornerAvailable) {
        var p = samples;
        var diffHorVer = Math.abs(xD) - Math.abs(yD);
        var sgnXY = Integer.signum(xD * yD);
        var cornerAt = (y - yD) * arrayW + x - xD;
        var cornerSample = p[cornerAt];
        if (!cornerAvailable) {
            var xC = x - xD + Integer.signum(xD);
            var yC = y - yD + Integer.signum(yD);
            p[cornerAt] = (p[yC * arrayW + x - xD] + p[(y - yD) * arrayW + xC] + 1) >> 1;
        }
        int value;
        if (diffHorVer > 0) {
            var xC = x - sgnXY * yD;
            var yC = y - yD;
            value = (p[yC * arrayW + xC - 1] + 2 * p[yC * arrayW + xC] + p[yC * arrayW + xC + 1] + 2) >> 2;
        } else if (diffHorVer < 0) {
            var xC = x - xD;
            var yC = y - sgnXY * xD;
            value = (p[(yC - 1) * arrayW + xC] + 2 * p[yC * arrayW + xC] + p[(yC + 1) * arrayW + xC] + 2) >> 2;
        } else {
            var xC = x - xD + Integer.signum(xD);
            var yC = y - yD + Integer.signum(yD);
            value = (p[(y - yD) * arrayW + xC] + 2 * p[cornerAt] + p[yC * arrayW + x - xD] + 2) >> 2;
        }
        p[y * arrayW + x] = value;
        if (!cornerAvailable) {
            p[cornerAt] = cornerSample;
        }
    }

    /**
     * Resamples the residual samples of the reference layer into one macroblock of the current
     * layer (ITU-T H.264 F.8.6.3 for frames).
     *
     * @param rep the current layer representation
     * @param ref the reference layer arrays
     * @param cur the current layer arrays, whose residual samples receive the prediction
     * @param mbAddr the macroblock address
     */
    void resampleResidual(SvcRepresentation rep, SvcLayer ref, SvcLayer cur, int mbAddr) {
        var xM = (mbAddr % cur.widthMbs) * 16;
        var yM = (mbAddr / cur.widthMbs) * 16;
        resampleResidualPlane(rep, ref, 0, ref.rY, ref.width, ref.height, cur.rY, yM * cur.width + xM, cur.width,
                xM, yM, 16);
        resampleResidualPlane(rep, ref, 1, ref.rCb, ref.chromaWidth, ref.chromaHeight, cur.rCb,
                (yM / 2) * cur.chromaWidth + xM / 2, cur.chromaWidth, xM / 2, yM / 2, 8);
        resampleResidualPlane(rep, ref, 1, ref.rCr, ref.chromaWidth, ref.chromaHeight, cur.rCr,
                (yM / 2) * cur.chromaWidth + xM / 2, cur.chromaWidth, xM / 2, yM / 2, 8);
    }

    /**
     * Resamples one color component of the residual samples of a macroblock (ITU-T H.264
     * F.8.6.3.1 to F.8.6.3.3).
     *
     * @param rep the current layer representation
     * @param ref the reference layer arrays
     * @param chroma 0 for luma, 1 for chroma
     * @param refPlane the reference layer residual samples of the component
     * @param refW the width of the component in the reference layer, also its row stride
     * @param refH the height of the component in the reference layer
     * @param dst the current layer residual samples of the component
     * @param dstOffset the index of the macroblock's top-left sample in {@code dst}
     * @param dstStride the row stride of {@code dst}
     * @param x0 the macroblock's horizontal position in the component
     * @param y0 the macroblock's vertical position in the component
     * @param size the macroblock size in the component: 16 or 8
     */
    private void resampleResidualPlane(SvcRepresentation rep, SvcLayer ref, int chroma, short[] refPlane, int refW,
                                       int refH, short[] dst, int dstOffset, int dstStride, int x0, int y0, int size) {
        var xRefMin16 = rep.refX16(chroma, x0);
        var yRefMin16 = rep.refY16(chroma, y0);
        var xRefMax16 = rep.refX16(chroma, x0 + size - 1);
        var yRefMax16 = rep.refY16(chroma, y0 + size - 1);
        var refMb = chroma == 0 ? 16 : 8;
        var xOffset = xRefMin16 >> 4;
        var yOffset = yRefMin16 >> 4;
        var arrayW = (xRefMax16 >> 4) - xOffset + 2;
        var arrayH = (yRefMax16 >> 4) - yOffset + 2;
        if (arrayW > MAX_ARRAY || arrayH > MAX_ARRAY) {
            throw new com.github.auties00.smalt.MediaException.Malformed("inter-layer resampling ratio out of range");
        }
        for (var y = 0; y < arrayH; y++) {
            var yRef = Math.clamp(y + yOffset, 0, refH - 1);
            for (var x = 0; x < arrayW; x++) {
                var xRef = Math.clamp(x + xOffset, 0, refW - 1);
                var at = y * arrayW + x;
                samples[at] = refPlane[yRef * refW + xRef];
                var refAddr = (yRef / refMb) * ref.widthMbs + xRef / refMb;
                var xB = xRef % refMb;
                var yB = yRef % refMb;
                if (chroma == 0 && ref.cTrafo[refAddr] == SvcLayer.T_8X8) {
                    marks[at] = 1 + 2 * (4 * refAddr + 2 * (yB / 8) + xB / 8);
                } else {
                    marks[at] = 2 * (16 * refAddr + 4 * (yB / 4) + xB / 4);
                }
            }
        }
        for (var y = 0; y < size; y++) {
            var yRef16 = rep.refY16(chroma, y0 + y);
            var yRef = (yRef16 >> 4) - yOffset;
            var yPhase = (yRef16 - 16 * yOffset) & 15;
            for (var x = 0; x < size; x++) {
                var xRef16 = rep.refX16(chroma, x0 + x);
                var xRef = (xRef16 >> 4) - xOffset;
                var xPhase = (xRef16 - 16 * xOffset) & 15;
                var top = interpolateRow(arrayW, xRef, yRef, xPhase);
                var bottom = interpolateRow(arrayW, xRef, yRef + 1, xPhase);
                var round = xRef + xPhase / 8;
                int value;
                if (marks[yRef * arrayW + round] == marks[(yRef + 1) * arrayW + round]) {
                    value = ((16 - yPhase) * top + yPhase * bottom + 128) >> 8;
                } else {
                    value = ((yPhase < 8 ? top : bottom) + 8) >> 4;
                }
                dst[dstOffset + y * dstStride + x] = (short) value;
            }
        }
    }

    /**
     * Interpolates one row of residual samples horizontally, without crossing transform block
     * boundaries (ITU-T H.264 equations F-341 and F-342).
     *
     * @param arrayW the array width
     * @param xRef the column of the left sample
     * @param y the row
     * @param xPhase the horizontal phase
     * @return the interpolated sample scaled by 16
     */
    private int interpolateRow(int arrayW, int xRef, int y, int xPhase) {
        var at = y * arrayW + xRef;
        if (marks[at] == marks[at + 1]) {
            return (16 - xPhase) * samples[at] + xPhase * samples[at + 1];
        }
        return (xPhase < 8 ? samples[at] : samples[at + 1]) << 4;
    }
}
