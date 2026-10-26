package com.github.auties00.smalt.video.h264.internal;

/**
 * Resamples the constructed intra samples (ITU-T H.264 F.8.6.2) and the residual samples
 * (F.8.6.3) of a reference layer picture for one macroblock of a layer picture of a higher
 * spatial resolution, for 4:2:0 8-bit samples.
 *
 * <p>Frames are resampled as frames. When either layer is interlaced, a macroblock is predicted
 * field by field: a field macroblock or a macroblock of a field from the field of the reference
 * layer of its parity, a frame macroblock either from each field of an interlaced reference frame
 * or from the reference field with the other field interpolated vertically (F.8.6.2.4 and
 * F.8.6.3.4).
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
    private final int[] temp = new int[MAX_ARRAY * 24];

    /**
     * The interpolated samples of one pass ({@code predArray}, {@code topFldPredArray} or
     * {@code fieldPredArray}), 20 rows of up to 16 samples.
     */
    private final int[] pass = new int[16 * 20];

    /**
     * The interpolated samples of the bottom field pass of a frame macroblock predicted from both
     * fields of a reference frame.
     */
    private final int[] bottomPass = new int[16 * 8];

    /**
     * The prediction samples of one component of the current macroblock, in raster order.
     */
    private final int[] prediction = new int[256];

    /**
     * The width of the reference sample array the last construction built.
     */
    private int arrayW;

    /**
     * The height of the reference sample array the last construction built.
     */
    private int arrayH;

    /**
     * The {@code xOffset} of the reference sample array the last construction built.
     */
    private int xOffset;

    /**
     * The {@code yOffset} of the reference sample array the last construction built.
     */
    private int yOffset;

    /**
     * Constructs a resampler with its working arrays.
     */
    SvcResampler() {
    }

    /**
     * Returns the {@code botFieldFlag} of a macroblock (ITU-T H.264 F.8.6.2.1 and F.8.6.3.1).
     *
     * @param rep the current layer representation
     * @param mbAddr the macroblock address
     * @param fieldMb the {@code fieldMbFlag} of the macroblock
     * @return 1 when the bottom field is resampled
     */
    private static int bottomField(SvcRepresentation rep, int mbAddr, boolean fieldMb) {
        if (rep.refFrameMbsOnly) {
            return 0;
        }
        if (rep.fieldPic) {
            return rep.bottomField ? 1 : 0;
        }
        if (rep.refFieldPic) {
            return rep.refBottomField ? 1 : 0;
        }
        return fieldMb ? mbAddr % 2 : 0;
    }

    /**
     * Returns whether a macroblock is a bottom field macroblock predicted from a progressive
     * reference layer ({@code botFieldFrameMbsOnlyRefFlag}).
     *
     * @param rep the current layer representation
     * @param mbAddr the macroblock address
     * @param fieldMb the {@code fieldMbFlag} of the macroblock
     * @return the flag
     */
    private static boolean bottomFromProgressive(SvcRepresentation rep, int mbAddr, boolean fieldMb) {
        return rep.refFrameMbsOnly && fieldMb
                && ((rep.fieldPic && rep.bottomField) || (!rep.fieldPic && mbAddr % 2 == 1));
    }

    /**
     * Returns whether a frame macroblock is predicted from both fields of an interlaced reference
     * frame ({@code topAndBotResamplingFlag}).
     *
     * @param rep the current layer representation
     * @param fieldMb the {@code fieldMbFlag} of the macroblock
     * @return the flag
     */
    private static boolean bothFields(SvcRepresentation rep, boolean fieldMb) {
        return !rep.refFrameMbsOnly && !rep.refFieldPic && !rep.frameMbsOnly && !fieldMb;
    }

    /**
     * Returns the row {@code yP} of the current macroblock that a row of an interpolated array
     * stands for (ITU-T H.264 equations F-300 and F-336).
     *
     * <p>The bottom field offset applies to the field rows of a frame macroblock only, where it
     * selects the frame row of that parity before the location derivation halves it. A field
     * macroblock, or a macroblock of a field, already addresses the rows of its own field, whose
     * phase the location derivation sets from {@code botFieldFlag}; adding the offset there
     * would move the prediction down by one field row, which neither the sample geometry nor the
     * JSVM reference decoder does.
     *
     * @param y the row of the interpolated array
     * @param border the {@code yBorder}
     * @param fieldOfFrame the {@code fldPrdInFrmMbFlag}
     * @param bottom the {@code botFieldFlag}
     * @return {@code yP}
     */
    private static int fieldRow(int y, int border, boolean fieldOfFrame, int bottom) {
        return fieldOfFrame ? (y - border) * 2 + bottom : y - border;
    }

    /**
     * Resamples the intra samples of the reference layer into one macroblock of the current
     * layer (ITU-T H.264 F.8.6.2).
     *
     * @param rep the current layer representation
     * @param ref the reference layer arrays
     * @param cur the current layer arrays, whose constructed samples receive the prediction
     * @param mbAddr the macroblock address
     * @param constrainedResampling the {@code constrained_intra_resampling_flag} of the slice
     */
    void resampleIntra(SvcRepresentation rep, SvcLayer ref, SvcLayer cur, int mbAddr, boolean constrainedResampling) {
        var fieldMb = cur.fieldMb[mbAddr];
        var location = rep.macroblockLocation(mbAddr, fieldMb && rep.mbaff);
        var xM = location >>> 16;
        var yM = location & 0xFFFF;
        var step = cur.rowStep(mbAddr);
        var x0 = cur.mbX(mbAddr) * 16;
        intraComponent(rep, ref, 0, ref.cY, ref.stride, fieldMb, mbAddr, xM, yM, constrainedResampling);
        storeSamples(prediction, 16, cur.cY, cur.lumaY(mbAddr) * cur.stride + SvcLayer.PAD + x0, cur.stride * step);
        var chromaOrigin = cur.chromaY(mbAddr) * cur.chromaStride + SvcLayer.PAD + x0 / 2;
        intraComponent(rep, ref, 1, ref.cCb, ref.chromaStride, fieldMb, mbAddr, xM, yM, constrainedResampling);
        storeSamples(prediction, 8, cur.cCb, chromaOrigin, cur.chromaStride * step);
        intraComponent(rep, ref, 1, ref.cCr, ref.chromaStride, fieldMb, mbAddr, xM, yM, constrainedResampling);
        storeSamples(prediction, 8, cur.cCr, chromaOrigin, cur.chromaStride * step);
    }

    /**
     * Stores a block of prediction samples into a sample plane.
     *
     * @param values the samples in raster order
     * @param size the block size
     * @param plane the sample plane
     * @param offset the index of the block's top-left sample
     * @param stride the distance between the block's rows in the plane
     */
    private static void storeSamples(int[] values, int size, byte[] plane, int offset, int stride) {
        for (var y = 0; y < size; y++) {
            for (var x = 0; x < size; x++) {
                plane[offset + y * stride + x] = (byte) values[y * size + x];
            }
        }
    }

    /**
     * Resamples one color component of the intra samples of a macroblock into
     * {@link #prediction} (ITU-T H.264 F.8.6.2.1).
     *
     * @param rep the current layer representation
     * @param ref the reference layer arrays
     * @param chroma 0 for luma, 1 for chroma
     * @param refPlane the reference layer constructed samples of the component
     * @param refStride the row stride of {@code refPlane}
     * @param fieldMb the {@code fieldMbFlag} of the macroblock
     * @param mbAddr the macroblock address
     * @param xM the horizontal luma location of the macroblock in its picture
     * @param yM the vertical luma location of the macroblock in its picture (ITU-T H.264 6.4.1)
     * @param constrainedResampling the {@code constrained_intra_resampling_flag}
     */
    private void intraComponent(SvcRepresentation rep, SvcLayer ref, int chroma, byte[] refPlane, int refStride,
                                boolean fieldMb, int mbAddr, int xM, int yM, boolean constrainedResampling) {
        var size = chroma == 0 ? 16 : 8;
        var bottom = bottomField(rep, mbAddr, fieldMb);
        var frameBased = rep.refFrameMbsOnly && rep.frameMbsOnly;
        if (bottomFromProgressive(rep, mbAddr, fieldMb)) {
            var border = 2 - chroma;
            constructIntra(rep, ref, chroma, refPlane, refStride, fieldMb, bottom, xM, yM, constrainedResampling, mbAddr,
                    -border, size - 1 + border);
            interpolateIntra(rep, chroma, size, fieldMb, bottom, false, border, xM, yM, pass);
            verticalIntra(chroma, size, bottom, border, false, pass, prediction);
        } else if (frameBased || fieldMb) {
            constructIntra(rep, ref, chroma, refPlane, refStride, fieldMb, bottom, xM, yM, constrainedResampling, mbAddr,
                    0, size - 1);
            interpolateIntra(rep, chroma, size, fieldMb, bottom, false, 0, xM, yM, prediction);
        } else if (!bothFields(rep, fieldMb)) {
            var border = 2 - chroma;
            constructIntra(rep, ref, chroma, refPlane, refStride, fieldMb, bottom, xM, yM, constrainedResampling, mbAddr,
                    -2 * border + bottom, size - 2 + 2 * border + bottom);
            interpolateIntra(rep, chroma, size, fieldMb, bottom, true, border, xM, yM, pass);
            verticalIntra(chroma, size, bottom, border, true, pass, prediction);
        } else {
            constructIntra(rep, ref, chroma, refPlane, refStride, fieldMb, 0, xM, yM, constrainedResampling, mbAddr,
                    0, size - 1);
            interpolateIntra(rep, chroma, size, fieldMb, 0, true, 0, xM, yM, pass);
            constructIntra(rep, ref, chroma, refPlane, refStride, fieldMb, 1, xM, yM, constrainedResampling, mbAddr,
                    0, size - 1);
            interpolateIntra(rep, chroma, size, fieldMb, 1, true, 0, xM, yM, bottomPass);
            for (var y = 0; y < size; y++) {
                var source = (y & 1) == 0 ? pass : bottomPass;
                System.arraycopy(source, (y >> 1) * size, prediction, y * size, size);
            }
        }
    }

    /**
     * Returns the horizontal reference location of a sample column of the current macroblock in
     * units of 1/16 sample (ITU-T H.264 F.6.3).
     *
     * @param rep the current layer representation
     * @param chroma 0 for luma, 1 for chroma
     * @param xM the horizontal luma location of the macroblock
     * @param xP the column inside the macroblock, in samples of the component
     * @return {@code xRef16}
     */
    private static int refX16(SvcRepresentation rep, int chroma, int xM, int xP) {
        return rep.refX16(chroma, xP + (xM >> chroma));
    }

    /**
     * Returns the vertical reference location of a sample row of the current macroblock in units
     * of 1/16 sample (ITU-T H.264 F.6.3).
     *
     * @param rep the current layer representation
     * @param chroma 0 for luma, 1 for chroma
     * @param yM the vertical luma location of the macroblock
     * @param yP the row inside the macroblock, in samples of the component
     * @param fieldMb the {@code fieldMbFlag} of the macroblock
     * @param bottom the {@code botFieldFlag}
     * @return {@code yRef16}
     */
    private static int refY16(SvcRepresentation rep, int chroma, int yM, int yP, boolean fieldMb, int bottom) {
        return rep.refY16(chroma, rep.sampleRow(chroma, yM, yP, fieldMb), bottom);
    }

    /**
     * Returns the reference layer macroblock holding a sample location of a component (ITU-T
     * H.264 F.8.6.2.2.1 and F.8.6.3.2.1).
     *
     * @param ref the reference layer arrays
     * @param refMb the macroblock size of the component
     * @param xRef the column
     * @param yRef the row
     * @return the macroblock address
     */
    private static int referenceMb(SvcLayer ref, int refMb, int xRef, int yRef) {
        if (!ref.mbaff) {
            return (yRef / refMb) * ref.widthMbs + xRef / refMb;
        }
        var top = 2 * ((yRef / (2 * refMb)) * ref.widthMbs + xRef / refMb);
        if (!ref.fieldMb[top]) {
            return top + (yRef % (2 * refMb)) / refMb;
        }
        return top + (yRef % 2);
    }

    /**
     * Constructs the reference sample array of one component of a macroblock for Intra_Base
     * prediction (ITU-T H.264 F.8.6.2.2), with its samples outside intra-coded reference
     * macroblocks constructed from their neighbors (F.8.6.2.2.2).
     *
     * @param rep the current layer representation
     * @param ref the reference layer arrays
     * @param chroma 0 for luma, 1 for chroma
     * @param refPlane the reference layer constructed samples of the component
     * @param refStride the row stride of {@code refPlane}
     * @param fieldMb the {@code fieldMbFlag} of the macroblock
     * @param bottom the {@code botFieldFlag}
     * @param xM the horizontal luma location of the macroblock
     * @param yM the vertical luma location of the macroblock
     * @param constrainedResampling the {@code constrained_intra_resampling_flag}
     * @param mbAddr the macroblock address
     * @param firstRow the first row {@code yP} the interpolation reads, below 0 for a vertical
     * border
     * @param lastRow the last row {@code yP} the interpolation reads, beyond the macroblock for a
     * vertical border
     */
    private void constructIntra(SvcRepresentation rep, SvcLayer ref, int chroma, byte[] refPlane, int refStride,
                                boolean fieldMb, int bottom, int xM, int yM, boolean constrainedResampling,
                                int mbAddr, int firstRow, int lastRow) {
        var size = chroma == 0 ? 16 : 8;
        var xRefMin16 = refX16(rep, chroma, xM, 0);
        var yRefMin16 = refY16(rep, chroma, yM, Math.min(0, firstRow), fieldMb, bottom);
        var xRefMax16 = refX16(rep, chroma, xM, size - 1);
        var yRefMax16 = refY16(rep, chroma, yM, Math.max(size - 1, lastRow), fieldMb, bottom);
        var refMb = size;
        var refW = chroma == 0 ? ref.width : ref.chromaWidth;
        var refH = chroma == 0 ? ref.height : ref.chromaHeight;
        xOffset = (((xRefMin16 - 64) >> 8) << 4) - (refMb >> 1);
        yOffset = (((yRefMin16 - 64) >> 8) << 4) - (refMb >> 1);
        arrayW = (((xRefMax16 + 79) >> 8) << 4) + 3 * (refMb >> 1) - xOffset;
        arrayH = (((yRefMax16 + 79) >> 8) << 4) + 3 * (refMb >> 1) - yOffset;
        if (arrayW > MAX_ARRAY || arrayH > MAX_ARRAY) {
            throw new com.github.auties00.smalt.MediaException.Malformed("inter-layer resampling ratio out of range");
        }
        var scale = rep.refFrameMbsOnly || rep.refFieldPic ? 1 : 2;
        var add = scale == 2 ? bottom : 0;
        var xMin = (xRefMin16 >> 4) - xOffset;
        var yMin = (refY16(rep, chroma, yM, 0, fieldMb, bottom) >> 4) - yOffset;
        var xMax = ((xRefMax16 + 15) >> 4) - xOffset;
        var yMax = ((refY16(rep, chroma, yM, size - 1, fieldMb, bottom) + 15) >> 4) - yOffset;
        var sliceMb = -1;
        if (constrainedResampling) {
            for (var y = yMin + 1; y <= yMax - 1 && sliceMb < 0; y++) {
                for (var x = xMin + 1; x <= xMax - 1; x++) {
                    var xRef = Math.clamp(x + xOffset, 0, refW - 1);
                    var yRef = scale * Math.clamp(y + yOffset, 0, refH / scale - 1) + add;
                    var refAddr = referenceMb(ref, refMb, xRef, yRef);
                    if (SvcMb.intra(ref.mbType[refAddr])) {
                        sliceMb = ref.sliceIdc[refAddr];
                        break;
                    }
                }
            }
        }
        for (var y = 0; y < arrayH; y++) {
            var yRef = scale * Math.clamp(y + yOffset, 0, refH / scale - 1) + add;
            for (var x = 0; x < arrayW; x++) {
                var xRef = Math.clamp(x + xOffset, 0, refW - 1);
                var refAddr = referenceMb(ref, refMb, xRef, yRef);
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
        fillUnavailable(refMb, scale == 2);
    }

    /**
     * Interpolates the reference sample array of one component for Intra_Base prediction (ITU-T
     * H.264 F.8.6.2.3).
     *
     * @param rep the current layer representation
     * @param chroma 0 for luma, 1 for chroma
     * @param size the macroblock size of the component
     * @param fieldMb the {@code fieldMbFlag} of the macroblock
     * @param bottom the {@code botFieldFlag}
     * @param fieldOfFrame the {@code fldPrdInFrmMbFlag}
     * @param border the {@code yBorder}
     * @param xM the horizontal luma location of the macroblock
     * @param yM the vertical luma location of the macroblock
     * @param out receives the interpolated samples, {@code size} per row
     */
    private void interpolateIntra(SvcRepresentation rep, int chroma, int size, boolean fieldMb, int bottom,
                                  boolean fieldOfFrame, int border, int xM, int yM, int[] out) {
        var rows = size / (fieldOfFrame ? 2 : 1) + 2 * border;
        for (var y = 0; y < rows; y++) {
            var yP = fieldRow(y, border, fieldOfFrame, bottom);
            var yRef16 = refY16(rep, chroma, yM, yP, fieldMb, bottom);
            var yRef = (yRef16 >> 4) - yOffset;
            var yPhase = (yRef16 - 16 * yOffset) & 15;
            for (var x = 0; x < arrayW; x++) {
                int value;
                if (chroma == 0) {
                    var f = yPhase * 4;
                    value = FILTER[f] * samples[clamp(yRef - 1, arrayH) * arrayW + x]
                            + FILTER[f + 1] * samples[clamp(yRef, arrayH) * arrayW + x]
                            + FILTER[f + 2] * samples[clamp(yRef + 1, arrayH) * arrayW + x]
                            + FILTER[f + 3] * samples[clamp(yRef + 2, arrayH) * arrayW + x];
                } else {
                    value = (16 - yPhase) * samples[clamp(yRef, arrayH) * arrayW + x]
                            + yPhase * samples[clamp(yRef + 1, arrayH) * arrayW + x];
                }
                temp[y * arrayW + x] = value;
            }
        }
        for (var x = 0; x < size; x++) {
            var xRef16 = refX16(rep, chroma, xM, x);
            var xRef = (xRef16 >> 4) - xOffset;
            var xPhase = (xRef16 - 16 * xOffset) & 15;
            for (var y = 0; y < rows; y++) {
                var row = y * arrayW;
                int value;
                if (chroma == 0) {
                    var f = xPhase * 4;
                    value = (FILTER[f] * temp[row + clamp(xRef - 1, arrayW)]
                            + FILTER[f + 1] * temp[row + clamp(xRef, arrayW)]
                            + FILTER[f + 2] * temp[row + clamp(xRef + 1, arrayW)]
                            + FILTER[f + 3] * temp[row + clamp(xRef + 2, arrayW)] + 512) >> 10;
                    value = Math.clamp(value, 0, 255);
                } else {
                    value = ((16 - xPhase) * temp[row + clamp(xRef, arrayW)]
                            + xPhase * temp[row + clamp(xRef + 1, arrayW)] + 128) >> 8;
                }
                out[y * size + x] = value;
            }
        }
    }

    /**
     * Completes the prediction of a component from the samples of one field interpolated with a
     * vertical border (ITU-T H.264 F.8.6.2.4).
     *
     * @param chroma 0 for luma, 1 for chroma
     * @param size the macroblock size of the component
     * @param bottom the {@code botFieldFlag}
     * @param border the {@code yBorder}
     * @param frameMb the {@code frameMbFlag}
     * @param field the field samples, {@code size} per row
     * @param out receives the prediction, {@code size} per row
     */
    private static void verticalIntra(int chroma, int size, int bottom, int border, boolean frameMb, int[] field,
                                      int[] out) {
        var shift = frameMb ? 1 : 0;
        for (var y = 0; y < size; y++) {
            if (frameMb && (y & 1) == bottom) {
                System.arraycopy(field, ((y >> 1) + border) * size, out, y * size, size);
                continue;
            }
            var yFld = (y >> shift) + border - bottom;
            for (var x = 0; x < size; x++) {
                var a = field[yFld * size + x];
                var b = field[(yFld + 1) * size + x];
                int value;
                if (chroma == 0) {
                    var c = field[(yFld - 1) * size + x];
                    var d = field[(yFld + 2) * size + x];
                    value = Math.clamp((19 * (a + b) - 3 * (c + d) + 16) >> 5, 0, 255);
                } else {
                    value = (a + b + 1) >> 1;
                }
                out[y * size + x] = value;
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
    private static int clamp(int index, int size) {
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
     * @param interlaced whether the array holds the rows of one field of an interlaced reference
     * frame, whose vertical neighbors may come from the other macroblock of a pair
     */
    private void fillUnavailable(int refMb, boolean interlaced) {
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
                if (interlaced) {
                    var yA = yD - (half + 1) * Integer.signum(yD);
                    var vertical = available(x, y - yD);
                    var verticalA = available(x, y - yA);
                    var horizontal = available(x - xD, y);
                    var corner = available(x - xD, y - yD);
                    var cornerA = available(x - xD, y - yA);
                    if ((!vertical && verticalA && horizontal)
                            || (!horizontal && !vertical && !corner && (verticalA || cornerA))
                            || (Math.abs(yA) < Math.abs(yD) && ((vertical && verticalA)
                            || ((vertical || corner) && (verticalA || cornerA) && !horizontal)))) {
                        yD = yA;
                    }
                }
                var horizontal = available(x - xD, y);
                var vertical = available(x, y - yD);
                var corner = available(x - xD, y - yD);
                if (horizontal && vertical) {
                    diagonal(xD, yD, x, y, corner);
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
     * @return {@code true} when inside the array and available
     */
    private boolean available(int x, int y) {
        return x >= 0 && y >= 0 && x < arrayW && y < arrayH && marks[y * arrayW + x] != 0;
    }

    /**
     * Constructs one not available sample from its available horizontal and vertical neighbors
     * (ITU-T H.264 F.8.6.2.2.2.1).
     *
     * @param xD the horizontal distance to the available neighbor
     * @param yD the vertical distance to the available neighbor
     * @param x the column of the sample
     * @param y the row of the sample
     * @param cornerAvailable whether the diagonal neighbor is available
     */
    private void diagonal(int xD, int yD, int x, int y, boolean cornerAvailable) {
        var p = samples;
        var w = arrayW;
        var diffHorVer = Math.abs(xD) - Math.abs(yD);
        var sgnXY = Integer.signum(xD * yD);
        var cornerAt = (y - yD) * w + x - xD;
        var cornerSample = p[cornerAt];
        if (!cornerAvailable) {
            var xC = x - xD + Integer.signum(xD);
            var yC = y - yD + Integer.signum(yD);
            p[cornerAt] = (p[yC * w + x - xD] + p[(y - yD) * w + xC] + 1) >> 1;
        }
        int value;
        if (diffHorVer > 0) {
            var xC = x - sgnXY * yD;
            var yC = y - yD;
            value = (p[yC * w + xC - 1] + 2 * p[yC * w + xC] + p[yC * w + xC + 1] + 2) >> 2;
        } else if (diffHorVer < 0) {
            var xC = x - xD;
            var yC = y - sgnXY * xD;
            value = (p[(yC - 1) * w + xC] + 2 * p[yC * w + xC] + p[(yC + 1) * w + xC] + 2) >> 2;
        } else {
            var xC = x - xD + Integer.signum(xD);
            var yC = y - yD + Integer.signum(yD);
            value = (p[(y - yD) * w + xC] + 2 * p[cornerAt] + p[yC * w + x - xD] + 2) >> 2;
        }
        p[y * w + x] = value;
        if (!cornerAvailable) {
            p[cornerAt] = cornerSample;
        }
    }

    /**
     * Resamples the residual samples of the reference layer into one macroblock of the current
     * layer (ITU-T H.264 F.8.6.3).
     *
     * @param rep the current layer representation
     * @param ref the reference layer arrays
     * @param cur the current layer arrays, whose residual samples receive the prediction
     * @param mbAddr the macroblock address
     */
    void resampleResidual(SvcRepresentation rep, SvcLayer ref, SvcLayer cur, int mbAddr) {
        var fieldMb = cur.fieldMb[mbAddr];
        var location = rep.macroblockLocation(mbAddr, fieldMb && rep.mbaff);
        var xM = location >>> 16;
        var yM = location & 0xFFFF;
        var step = cur.rowStep(mbAddr);
        var x0 = cur.mbX(mbAddr) * 16;
        residualComponent(rep, ref, 0, ref.rY, ref.width, fieldMb, mbAddr, xM, yM);
        storeResidual(prediction, 16, cur.rY, cur.lumaY(mbAddr) * cur.width + x0, cur.width * step);
        var chromaAt = cur.chromaY(mbAddr) * cur.chromaWidth + x0 / 2;
        residualComponent(rep, ref, 1, ref.rCb, ref.chromaWidth, fieldMb, mbAddr, xM, yM);
        storeResidual(prediction, 8, cur.rCb, chromaAt, cur.chromaWidth * step);
        residualComponent(rep, ref, 1, ref.rCr, ref.chromaWidth, fieldMb, mbAddr, xM, yM);
        storeResidual(prediction, 8, cur.rCr, chromaAt, cur.chromaWidth * step);
    }

    /**
     * Stores a block of residual prediction samples into a residual plane.
     *
     * @param values the samples in raster order
     * @param size the block size
     * @param plane the residual plane
     * @param offset the index of the block's top-left sample
     * @param stride the distance between the block's rows in the plane
     */
    private static void storeResidual(int[] values, int size, short[] plane, int offset, int stride) {
        for (var y = 0; y < size; y++) {
            for (var x = 0; x < size; x++) {
                plane[offset + y * stride + x] = (short) values[y * size + x];
            }
        }
    }

    /**
     * Resamples one color component of the residual samples of a macroblock into
     * {@link #prediction} (ITU-T H.264 F.8.6.3.1).
     *
     * @param rep the current layer representation
     * @param ref the reference layer arrays
     * @param chroma 0 for luma, 1 for chroma
     * @param refPlane the reference layer residual samples of the component
     * @param refStride the row stride of {@code refPlane}, its width
     * @param fieldMb the {@code fieldMbFlag} of the macroblock
     * @param mbAddr the macroblock address
     * @param xM the horizontal luma location of the macroblock
     * @param yM the vertical luma location of the macroblock
     */
    private void residualComponent(SvcRepresentation rep, SvcLayer ref, int chroma, short[] refPlane, int refStride,
                                   boolean fieldMb, int mbAddr, int xM, int yM) {
        var size = chroma == 0 ? 16 : 8;
        var bottom = bottomField(rep, mbAddr, fieldMb);
        var frameBased = rep.refFrameMbsOnly && rep.frameMbsOnly;
        if (bottomFromProgressive(rep, mbAddr, fieldMb)) {
            constructResidual(rep, ref, chroma, refPlane, refStride, fieldMb, bottom, 1, xM, yM);
            interpolateResidual(rep, chroma, size, fieldMb, bottom, false, 1, xM, yM, pass);
            verticalResidual(size, bottom, 1, false, pass, prediction);
        } else if (frameBased || fieldMb) {
            constructResidual(rep, ref, chroma, refPlane, refStride, fieldMb, bottom, 0, xM, yM);
            interpolateResidual(rep, chroma, size, fieldMb, bottom, false, 0, xM, yM, prediction);
        } else if (!bothFields(rep, fieldMb)) {
            constructResidual(rep, ref, chroma, refPlane, refStride, fieldMb, bottom, 1, xM, yM);
            interpolateResidual(rep, chroma, size, fieldMb, bottom, true, 1, xM, yM, pass);
            verticalResidual(size, bottom, 1, true, pass, prediction);
        } else {
            constructResidual(rep, ref, chroma, refPlane, refStride, fieldMb, 0, 0, xM, yM);
            interpolateResidual(rep, chroma, size, fieldMb, 0, true, 0, xM, yM, pass);
            constructResidual(rep, ref, chroma, refPlane, refStride, fieldMb, 1, 0, xM, yM);
            interpolateResidual(rep, chroma, size, fieldMb, 1, true, 0, xM, yM, bottomPass);
            for (var y = 0; y < size; y++) {
                var source = (y & 1) == 0 ? pass : bottomPass;
                System.arraycopy(source, (y >> 1) * size, prediction, y * size, size);
            }
        }
    }

    /**
     * Constructs the reference sample array and the transform block identifications of one
     * component of a macroblock for residual prediction (ITU-T H.264 F.8.6.3.2).
     *
     * @param rep the current layer representation
     * @param ref the reference layer arrays
     * @param chroma 0 for luma, 1 for chroma
     * @param refPlane the reference layer residual samples of the component
     * @param refStride the row stride of {@code refPlane}
     * @param fieldMb the {@code fieldMbFlag} of the macroblock
     * @param bottom the {@code botFieldFlag}
     * @param border the {@code yBorder}
     * @param xM the horizontal luma location of the macroblock
     * @param yM the vertical luma location of the macroblock
     */
    private void constructResidual(SvcRepresentation rep, SvcLayer ref, int chroma, short[] refPlane, int refStride,
                                   boolean fieldMb, int bottom, int border, int xM, int yM) {
        var size = chroma == 0 ? 16 : 8;
        var xRefMin16 = refX16(rep, chroma, xM, 0);
        var yRefMin16 = refY16(rep, chroma, yM, -border, fieldMb, bottom);
        var xRefMax16 = refX16(rep, chroma, xM, size - 1);
        var yRefMax16 = refY16(rep, chroma, yM, size - 1 + border, fieldMb, bottom);
        var refMb = size;
        var refW = chroma == 0 ? ref.width : ref.chromaWidth;
        var refH = chroma == 0 ? ref.height : ref.chromaHeight;
        xOffset = xRefMin16 >> 4;
        yOffset = yRefMin16 >> 4;
        arrayW = (xRefMax16 >> 4) - xOffset + 2;
        arrayH = (yRefMax16 >> 4) - yOffset + 2;
        if (arrayW > MAX_ARRAY || arrayH > MAX_ARRAY) {
            throw new com.github.auties00.smalt.MediaException.Malformed("inter-layer resampling ratio out of range");
        }
        var scale = rep.refFrameMbsOnly || rep.refFieldPic ? 1 : 2;
        var add = scale == 2 ? bottom : 0;
        for (var y = 0; y < arrayH; y++) {
            var yRef = scale * Math.clamp(y + yOffset, 0, refH / scale - 1) + add;
            for (var x = 0; x < arrayW; x++) {
                var xRef = Math.clamp(x + xOffset, 0, refW - 1);
                var at = y * arrayW + x;
                samples[at] = refPlane[yRef * refStride + xRef];
                int refAddr;
                int yB;
                if (!ref.mbaff) {
                    refAddr = (yRef / refMb) * ref.widthMbs + xRef / refMb;
                    yB = yRef % refMb;
                } else {
                    var top = 2 * ((yRef / (2 * refMb)) * ref.widthMbs + xRef / refMb);
                    if (!ref.fieldMb[top]) {
                        refAddr = top + (yRef % (2 * refMb)) / refMb;
                        yB = yRef % refMb;
                    } else {
                        refAddr = top + (yRef % 2);
                        yB = (yRef % (2 * refMb)) >> 1;
                    }
                }
                var xB = xRef % refMb;
                if (chroma == 0 && ref.cTrafo[refAddr] == SvcLayer.T_8X8) {
                    marks[at] = 1 + 2 * (4 * refAddr + 2 * (yB / 8) + xB / 8);
                } else {
                    marks[at] = 2 * (16 * refAddr + 4 * (yB / 4) + xB / 4);
                }
            }
        }
    }

    /**
     * Interpolates the reference sample array of one component for residual prediction (ITU-T
     * H.264 F.8.6.3.3).
     *
     * @param rep the current layer representation
     * @param chroma 0 for luma, 1 for chroma
     * @param size the macroblock size of the component
     * @param fieldMb the {@code fieldMbFlag} of the macroblock
     * @param bottom the {@code botFieldFlag}
     * @param fieldOfFrame the {@code fldPrdInFrmMbFlag}
     * @param border the {@code yBorder}
     * @param xM the horizontal luma location of the macroblock
     * @param yM the vertical luma location of the macroblock
     * @param out receives the interpolated samples, {@code size} per row
     */
    private void interpolateResidual(SvcRepresentation rep, int chroma, int size, boolean fieldMb, int bottom,
                                     boolean fieldOfFrame, int border, int xM, int yM, int[] out) {
        var rows = size / (fieldOfFrame ? 2 : 1) + 2 * border;
        for (var y = 0; y < rows; y++) {
            var yP = fieldRow(y, border, fieldOfFrame, bottom);
            var yRef16 = refY16(rep, chroma, yM, yP, fieldMb, bottom);
            var yRef = (yRef16 >> 4) - yOffset;
            var yPhase = (yRef16 - 16 * yOffset) & 15;
            for (var x = 0; x < size; x++) {
                var xRef16 = refX16(rep, chroma, xM, x);
                var xRef = (xRef16 >> 4) - xOffset;
                var xPhase = (xRef16 - 16 * xOffset) & 15;
                var top = interpolateRow(xRef, yRef, xPhase);
                var bottomRow = interpolateRow(xRef, yRef + 1, xPhase);
                var round = xRef + xPhase / 8;
                int value;
                if (marks[yRef * arrayW + round] == marks[(yRef + 1) * arrayW + round]) {
                    value = ((16 - yPhase) * top + yPhase * bottomRow + 128) >> 8;
                } else {
                    value = ((yPhase < 8 ? top : bottomRow) + 8) >> 4;
                }
                out[y * size + x] = value;
            }
        }
    }

    /**
     * Completes the residual prediction of a component from the samples of one field
     * interpolated with a vertical border (ITU-T H.264 F.8.6.3.4).
     *
     * @param size the macroblock size of the component
     * @param bottom the {@code botFieldFlag}
     * @param border the {@code yBorder}
     * @param frameMb the {@code frameMbFlag}
     * @param field the field samples, {@code size} per row
     * @param out receives the prediction, {@code size} per row
     */
    private static void verticalResidual(int size, int bottom, int border, boolean frameMb, int[] field, int[] out) {
        var shift = frameMb ? 1 : 0;
        for (var y = 0; y < size; y++) {
            if (frameMb && (y & 1) == bottom) {
                System.arraycopy(field, ((y >> 1) + border) * size, out, y * size, size);
                continue;
            }
            var yFld = (y >> shift) + border - bottom;
            for (var x = 0; x < size; x++) {
                out[y * size + x] = (field[yFld * size + x] + field[(yFld + 1) * size + x] + 1) >> 1;
            }
        }
    }

    /**
     * Interpolates one row of residual samples horizontally, without crossing transform block
     * boundaries (ITU-T H.264 equations F-341 and F-342).
     *
     * @param xRef the column of the left sample
     * @param y the row
     * @param xPhase the horizontal phase
     * @return the interpolated sample scaled by 16
     */
    private int interpolateRow(int xRef, int y, int xPhase) {
        var at = y * arrayW + xRef;
        if (marks[at] == marks[at + 1]) {
            return (16 - xPhase) * samples[at] + xPhase * samples[at + 1];
        }
        return (xPhase < 8 ? samples[at] : samples[at + 1]) << 4;
    }
}
