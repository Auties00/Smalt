package com.github.auties00.smalt.video.hevc.internal;

import java.util.Arrays;

/**
 * Derives the inter-layer reference picture of a picture from a picture of a direct reference
 * layer (ITU-T H.265 H.8.1.4): the reference layer picture itself when the two layers share their
 * picture size, offsets, sampling and bit depths and no colour mapping applies, otherwise a
 * picture of the current layer's format holding the reference layer samples colour mapped
 * (H.8.1.4.4) and resampled (H.8.1.4.2) and its motion resampled (H.8.1.4.3).
 *
 * <p>Arguments are not checked; values outside the documented ranges give unspecified results.
 */
final class InterLayerPicture {
    /**
     * The 16-phase luma resampling filter, Table H.1, indexed {@code [phase * 8 + tap]}.
     */
    private static final int[] LUMA_FILTER = {
            0, 0, 0, 64, 0, 0, 0, 0,
            0, 1, -3, 63, 4, -2, 1, 0,
            -1, 2, -5, 62, 8, -3, 1, 0,
            -1, 3, -8, 60, 13, -4, 1, 0,
            -1, 4, -10, 58, 17, -5, 1, 0,
            -1, 4, -11, 52, 26, -8, 3, -1,
            -1, 3, -9, 47, 31, -10, 4, -1,
            -1, 4, -11, 45, 34, -10, 4, -1,
            -1, 4, -11, 40, 40, -11, 4, -1,
            -1, 4, -10, 34, 45, -11, 4, -1,
            -1, 4, -10, 31, 47, -9, 3, -1,
            -1, 3, -8, 26, 52, -11, 4, -1,
            0, 1, -5, 17, 58, -10, 4, -1,
            0, 1, -4, 13, 60, -8, 3, -1,
            0, 1, -3, 8, 62, -5, 2, -1,
            0, 1, -2, 4, 63, -3, 1, 0
    };

    /**
     * The 16-phase chroma resampling filter, Table H.2, indexed {@code [phase * 4 + tap]}.
     */
    private static final int[] CHROMA_FILTER = {
            0, 64, 0, 0,
            -2, 62, 4, 0,
            -2, 58, 10, -2,
            -4, 56, 14, -2,
            -4, 54, 16, -2,
            -6, 52, 20, -2,
            -6, 46, 28, -4,
            -4, 42, 30, -4,
            -4, 36, 36, -4,
            -4, 30, 42, -4,
            -4, 28, 46, -6,
            -2, 20, 52, -6,
            -2, 16, 54, -4,
            -2, 14, 56, -4,
            -2, 10, 58, -2,
            0, 4, 62, -2
    };

    /**
     * The spatial relation between the pictures of a layer and those of one of its direct
     * reference layers (H.8.1.4.1), all offsets and sizes in luma samples.
     *
     * @param refWidth {@code PicWidthInSamplesRefLayerY}
     * @param refHeight {@code PicHeightInSamplesRefLayerY}
     * @param refLeft {@code RefLayerRegionLeftOffset}
     * @param refTop {@code RefLayerRegionTopOffset}
     * @param refRegionWidth {@code RefLayerRegionWidthInSamplesY}
     * @param refRegionHeight {@code RefLayerRegionHeightInSamplesY}
     * @param scaledLeft {@code ScaledRefLayerLeftOffset}
     * @param scaledTop {@code ScaledRefLayerTopOffset}
     * @param scaledWidth {@code ScaledRefRegionWidthInSamplesY}
     * @param scaledHeight {@code ScaledRefRegionHeightInSamplesY}
     * @param scaleHorY {@code SpatialScaleFactorHorY}
     * @param scaleVerY {@code SpatialScaleFactorVerY}
     * @param scaleHorC {@code SpatialScaleFactorHorC}
     * @param scaleVerC {@code SpatialScaleFactorVerC}
     * @param phaseHorY {@code PhaseHorY}
     * @param phaseVerY {@code PhaseVerY}
     * @param phaseHorC {@code PhaseHorC}
     * @param phaseVerC {@code PhaseVerC}
     * @param equalSizeAndOffset {@code equalPictureSizeAndOffsetFlag}
     */
    record Geometry(int refWidth, int refHeight, int refLeft, int refTop, int refRegionWidth, int refRegionHeight,
                    int scaledLeft, int scaledTop, int scaledWidth, int scaledHeight, int scaleHorY, int scaleVerY,
                    int scaleHorC, int scaleVerC, int phaseHorY, int phaseVerY, int phaseHorC, int phaseVerC,
                    boolean equalSizeAndOffset) {
    }

    /**
     * Prevents instantiation.
     */
    private InterLayerPicture() {
        throw new AssertionError();
    }

    /**
     * Derives the spatial relation of a picture of the current layer to a reference layer
     * picture.
     *
     * @param current the sequence parameter set of the current picture
     * @param reference the sequence parameter set of the reference layer picture
     * @param location the reference layer location offsets of the current picture parameter set
     * for the reference layer, {@link HevcPps.RefLocation#NONE} when it gives none
     * @return the relation, or {@code null} when the regions it gives are empty or the reference
     * region is larger than its scaled region
     */
    static Geometry geometry(HevcSps current, HevcSps reference, HevcPps.RefLocation location) {
        var subWidthRef = 1 << reference.chromaShiftX;
        var subHeightRef = 1 << reference.chromaShiftY;
        var subWidthCur = 1 << current.chromaShiftX;
        var subHeightCur = 1 << current.chromaShiftY;
        var refLeft = location.regionLeft() * subWidthRef;
        var refTop = location.regionTop() * subHeightRef;
        var refRight = location.regionRight() * subWidthRef;
        var refBottom = location.regionBottom() * subHeightRef;
        var refRegionWidth = reference.width - refLeft - refRight;
        var refRegionHeight = reference.height - refTop - refBottom;
        var scaledLeft = location.scaledLeft() * subWidthCur;
        var scaledTop = location.scaledTop() * subHeightCur;
        var scaledRight = location.scaledRight() * subWidthCur;
        var scaledBottom = location.scaledBottom() * subHeightCur;
        var scaledWidth = current.width - scaledLeft - scaledRight;
        var scaledHeight = current.height - scaledTop - scaledBottom;
        if (refRegionWidth <= 0 || refRegionHeight <= 0 || scaledWidth < refRegionWidth
                || scaledHeight < refRegionHeight || scaledWidth / subWidthCur <= 0 || scaledHeight / subHeightCur <= 0) {
            return null;
        }
        var scaleHorY = (int) ((((long) refRegionWidth << 16) + (scaledWidth >> 1)) / scaledWidth);
        var scaleVerY = (int) ((((long) refRegionHeight << 16) + (scaledHeight >> 1)) / scaledHeight);
        var scaledWidthC = scaledWidth / subWidthCur;
        var scaledHeightC = scaledHeight / subHeightCur;
        var scaleHorC = (int) ((((long) (refRegionWidth / subWidthRef) << 16) + (scaledWidthC >> 1)) / scaledWidthC);
        var scaleVerC = (int) ((((long) (refRegionHeight / subHeightRef) << 16) + (scaledHeightC >> 1)) / scaledHeightC);
        var phaseVerChromaPlus8 = location.phaseVerChromaPlus8();
        if (phaseVerChromaPlus8 < 0) {
            phaseVerChromaPlus8 = current.chromaFormatIdc == 3 ? 8
                    : (4 * scaledHeight + refRegionHeight / 2) / refRegionHeight + 4;
        }
        var phaseHorY = location.phaseHorLuma();
        var phaseVerY = location.phaseVerLuma();
        var phaseHorC = location.phaseHorChromaPlus8() - 8;
        var phaseVerC = phaseVerChromaPlus8 - 8;
        var equal = current.width == reference.width && current.height == reference.height
                && scaledLeft == refLeft && scaledTop == refTop && scaledRight == refRight && scaledBottom == refBottom
                && phaseHorY == 0 && phaseVerY == 0 && phaseHorC == 0 && phaseVerC == 0;
        return new Geometry(reference.width, reference.height, refLeft, refTop, refRegionWidth, refRegionHeight,
                scaledLeft, scaledTop, scaledWidth, scaledHeight, scaleHorY, scaleVerY, scaleHorC, scaleVerC,
                phaseHorY, phaseVerY, phaseHorC, phaseVerC, equal);
    }

    /**
     * Returns whether the reference layer picture serves as the inter-layer reference picture
     * itself (H.8.1.4.1): equal sizes and offsets, the same bit depths and chroma sampling, and no
     * colour mapping. Two monochrome pictures have no chroma arrays, so their chroma bit depths
     * need not agree.
     *
     * @param geometry the spatial relation
     * @param current the sequence parameter set of the current picture
     * @param reference the sequence parameter set of the reference layer picture
     * @param colourMapping whether colour mapping applies to the reference layer
     * @return {@code true} when no new picture is needed
     */
    static boolean identity(Geometry geometry, HevcSps current, HevcSps reference, boolean colourMapping) {
        var monochrome = reference.chromaFormatIdc == 0 && current.chromaFormatIdc == 0;
        var sameChroma = monochrome || reference.bitDepthChroma == current.bitDepthChroma
                && reference.chromaShiftX == current.chromaShiftX && reference.chromaShiftY == current.chromaShiftY
                && reference.chromaFormatIdc == current.chromaFormatIdc;
        return geometry.equalSizeAndOffset() && reference.bitDepthLuma == current.bitDepthLuma && sameChroma
                && !colourMapping;
    }

    /**
     * The working memory of the derivations, reused from picture to picture.
     */
    static final class Scratch {
        int[] luma = new int[0];

        int[] cb = new int[0];

        int[] cr = new int[0];

        int[] rows = new int[0];

        int[] positions = new int[0];

        int[] phases = new int[0];

        int[] mapped = new int[0];

        /**
         * Returns an array of at least a size, reusing a kept one.
         *
         * @param array the kept array
         * @param size the entries needed
         * @return the array
         */
        static int[] fit(int[] array, int size) {
            return array.length >= size ? array : new int[size];
        }
    }

    /**
     * Fills an inter-layer reference picture from a reference layer picture.
     *
     * @param rl the decoded reference layer picture
     * @param out the inter-layer reference picture, of the current layer's format, whose
     * {@link HevcFrame#sps} is the current picture's
     * @param geometry the spatial relation
     * @param mapping the colour mapping table to apply, or {@code null}
     * @param samples whether inter-layer sample prediction is enabled
     * @param motion whether inter-layer motion prediction is enabled
     * @param scratch the working memory
     */
    static void derive(HevcFrame rl, HevcFrame out, Geometry geometry, HevcPps.ColourMapping mapping, boolean samples,
                       boolean motion, Scratch scratch) {
        var current = out.sps;
        var reference = rl.sps;
        if (samples) {
            var depthY = reference.bitDepthLuma;
            var depthC = reference.bitDepthChroma;
            var lumaSource = scratch.luma = Scratch.fit(scratch.luma, rl.width * rl.height);
            read(rl, 0, lumaSource);
            int[] cbSource = null;
            int[] crSource = null;
            if (rl.chromaWidth > 0) {
                var size = rl.chromaWidth * rl.chromaHeight;
                cbSource = scratch.cb = Scratch.fit(scratch.cb, size);
                crSource = scratch.cr = Scratch.fit(scratch.cr, size);
                read(rl, 1, cbSource);
                read(rl, 2, crSource);
            }
            if (mapping != null && cbSource != null) {
                colourMap(rl, lumaSource, cbSource, crSource, mapping, scratch);
                depthY = mapping.outputDepthLuma();
                depthC = mapping.outputDepthChroma();
            }
            var direct = geometry.equalSizeAndOffset() && depthY == current.bitDepthLuma
                    && depthC == current.bitDepthChroma && reference.chromaShiftX == current.chromaShiftX
                    && reference.chromaShiftY == current.chromaShiftY;
            if (direct) {
                write(out, 0, lumaSource, out.width, out.height);
                if (out.chromaWidth > 0) {
                    write(out, 1, cbSource, out.chromaWidth, out.chromaHeight);
                    write(out, 2, crSource, out.chromaWidth, out.chromaHeight);
                }
            } else {
                resampleLuma(lumaSource, rl.width, rl.height, depthY, out, geometry, scratch);
                if (out.chromaWidth > 0 && cbSource != null) {
                    resampleChroma(cbSource, rl.chromaWidth, rl.chromaHeight, depthC, out, 1, geometry, reference,
                            scratch);
                    resampleChroma(crSource, rl.chromaWidth, rl.chromaHeight, depthC, out, 2, geometry, reference,
                            scratch);
                } else if (out.chromaWidth > 0) {
                    fillGray(out, 1);
                    fillGray(out, 2);
                }
            }
        } else {
            fillGray(out, 0);
            if (out.chromaWidth > 0) {
                fillGray(out, 1);
                fillGray(out, 2);
            }
        }
        var planes = out.planes != null ? out.planes.length : 1;
        for (var k = 0; k < planes; k++) {
            // with separate color planes each plane has its own motion field and slices
            var target = out.plane(k);
            var source = rl.planes != null ? rl.plane(k) : rl;
            Arrays.fill(target.ref0, (byte) -1);
            Arrays.fill(target.ref1, (byte) -1);
            target.sliceCount = 0;
            if (motion && source.sliceCount > 0) {
                target.addSlice(source.sliceRefPoc[0], source.sliceRefLongTerm[0], source.sliceRefId[0]);
                Arrays.fill(target.ctbSlice, 0);
                resampleMotion(source, target, geometry);
            } else {
                Arrays.fill(target.ctbSlice, -1);
            }
        }
    }

    /**
     * Copies a sample plane of a picture into an array, one sample per entry, rows packed.
     *
     * @param frame the picture
     * @param component 0 for luma, 1 for Cb, 2 for Cr
     * @param target the array
     */
    private static void read(HevcFrame frame, int component, int[] target) {
        var width = component == 0 ? frame.width : frame.chromaWidth;
        var height = component == 0 ? frame.height : frame.chromaHeight;
        var count = width * height;
        if (frame.eightBit()) {
            var plane = component == 0 ? frame.y8 : component == 1 ? frame.cb8 : frame.cr8;
            for (var i = 0; i < count; i++) {
                target[i] = plane[i] & 0xFF;
            }
        } else {
            var plane = component == 0 ? frame.y : component == 1 ? frame.cb : frame.cr;
            for (var i = 0; i < count; i++) {
                target[i] = plane[i] & 0xFFFF;
            }
        }
    }

    /**
     * Copies packed samples into a sample plane of a picture.
     *
     * @param frame the picture
     * @param component 0 for luma, 1 for Cb, 2 for Cr
     * @param source the samples, rows packed
     * @param width the plane width
     * @param height the plane height
     */
    private static void write(HevcFrame frame, int component, int[] source, int width, int height) {
        var count = width * height;
        if (frame.eightBit()) {
            var plane = component == 0 ? frame.y8 : component == 1 ? frame.cb8 : frame.cr8;
            for (var i = 0; i < count; i++) {
                plane[i] = (byte) source[i];
            }
        } else {
            var plane = component == 0 ? frame.y : component == 1 ? frame.cb : frame.cr;
            for (var i = 0; i < count; i++) {
                plane[i] = (short) source[i];
            }
        }
    }

    /**
     * Fills a sample plane of a picture with mid-gray, {@code 1 << (d - 1)} at bit depth {@code d}.
     *
     * @param frame the picture
     * @param component 0 for luma, 1 for Cb, 2 for Cr
     */
    private static void fillGray(HevcFrame frame, int component) {
        var sps = frame.sps;
        if (frame.eightBit()) {
            Arrays.fill(component == 0 ? frame.y8 : component == 1 ? frame.cb8 : frame.cr8, (byte) 128);
        } else {
            var depth = component == 0 ? sps.bitDepthLuma : sps.bitDepthChroma;
            Arrays.fill(component == 0 ? frame.y : component == 1 ? frame.cb : frame.cr, (short) (1 << (depth - 1)));
        }
    }

    /**
     * Resamples the luma samples of a reference layer picture into an inter-layer reference
     * picture (H.8.1.4.2.2).
     *
     * @param source the reference layer luma samples, rows packed
     * @param refWidth the reference layer picture width
     * @param refHeight the reference layer picture height
     * @param depth {@code BitDepthRefLayerY}
     * @param out the inter-layer reference picture
     * @param g the spatial relation
     * @param scratch the working memory
     */
    private static void resampleLuma(int[] source, int refWidth, int refHeight, int depth, HevcFrame out, Geometry g,
                                     Scratch scratch) {
        var width = out.width;
        var height = out.height;
        var currentDepth = out.sps.bitDepthLuma;
        var shift1 = depth - 8;
        var shift2 = 20 - currentDepth;
        var offset = 1 << (shift2 - 1);
        var max = (1 << currentDepth) - 1;
        var addHor = -((g.scaleHorY() * g.phaseHorY() + 8) >> 4);
        var addVer = -((g.scaleVerY() * g.phaseVerY() + 8) >> 4);
        var xPositions = scratch.positions = Scratch.fit(scratch.positions, Math.max(width, height) * 2);
        var xPhases = scratch.phases = Scratch.fit(scratch.phases, Math.max(width, height) * 2);
        for (var x = 0; x < width; x++) {
            var ref16 = (int) ((((long) (x - g.scaledLeft()) * g.scaleHorY() + addHor + (1 << 11)) >> 12)
                    + ((long) g.refLeft() << 4));
            xPositions[x] = ref16 >> 4;
            xPhases[x] = ref16 & 15;
        }
        var rows = scratch.rows = Scratch.fit(scratch.rows, refHeight * width);
        for (var y = 0; y < refHeight; y++) {
            var line = y * refWidth;
            var target = y * width;
            for (var x = 0; x < width; x++) {
                var xRef = xPositions[x];
                var taps = xPhases[x] * 8;
                var sum = 0;
                for (var k = 0; k < 8; k++) {
                    var column = Math.clamp(xRef + k - 3, 0, refWidth - 1);
                    sum += LUMA_FILTER[taps + k] * source[line + column];
                }
                rows[target + x] = sum >> shift1;
            }
        }
        for (var y = 0; y < height; y++) {
            var ref16 = (int) ((((long) (y - g.scaledTop()) * g.scaleVerY() + addVer + (1 << 11)) >> 12)
                    + ((long) g.refTop() << 4));
            var yRef = ref16 >> 4;
            var taps = (ref16 & 15) * 8;
            var base = y * width;
            for (var x = 0; x < width; x++) {
                var sum = 0;
                for (var k = 0; k < 8; k++) {
                    var row = Math.clamp(yRef + k - 3, 0, refHeight - 1);
                    sum += LUMA_FILTER[taps + k] * rows[row * width + x];
                }
                var value = Math.clamp((sum + offset) >> shift2, 0, max);
                if (out.eightBit()) {
                    out.y8[base + x] = (byte) value;
                } else {
                    out.y[base + x] = (short) value;
                }
            }
        }
    }

    /**
     * Resamples a chroma component of a reference layer picture into an inter-layer reference
     * picture (H.8.1.4.2.3).
     *
     * @param source the reference layer chroma samples, rows packed
     * @param refWidth the reference layer chroma width
     * @param refHeight the reference layer chroma height
     * @param depth {@code BitDepthRefLayerC}
     * @param out the inter-layer reference picture
     * @param component 1 for Cb, 2 for Cr
     * @param g the spatial relation
     * @param reference the sequence parameter set of the reference layer picture
     * @param scratch the working memory
     */
    private static void resampleChroma(int[] source, int refWidth, int refHeight, int depth, HevcFrame out,
                                       int component, Geometry g, HevcSps reference, Scratch scratch) {
        var current = out.sps;
        var width = out.chromaWidth;
        var height = out.chromaHeight;
        var currentDepth = current.bitDepthChroma;
        var shift1 = depth - 8;
        var shift2 = 20 - currentDepth;
        var offset = 1 << (shift2 - 1);
        var max = (1 << currentDepth) - 1;
        var currLeft = g.scaledLeft() / (1 << current.chromaShiftX);
        var currTop = g.scaledTop() / (1 << current.chromaShiftY);
        var refLeft = (g.refLeft() / (1 << reference.chromaShiftX)) << 4;
        var refTop = (g.refTop() / (1 << reference.chromaShiftY)) << 4;
        var addHor = -((g.scaleHorC() * g.phaseHorC() + 8) >> 4);
        var addVer = -((g.scaleVerC() * g.phaseVerC() + 8) >> 4);
        var xPositions = scratch.positions = Scratch.fit(scratch.positions, Math.max(width, height) * 2);
        var xPhases = scratch.phases = Scratch.fit(scratch.phases, Math.max(width, height) * 2);
        for (var x = 0; x < width; x++) {
            var ref16 = (int) ((((long) (x - currLeft) * g.scaleHorC() + addHor + (1 << 11)) >> 12) + refLeft);
            xPositions[x] = ref16 >> 4;
            xPhases[x] = ref16 & 15;
        }
        var rows = scratch.rows = Scratch.fit(scratch.rows, refHeight * width);
        for (var y = 0; y < refHeight; y++) {
            var line = y * refWidth;
            var target = y * width;
            for (var x = 0; x < width; x++) {
                var xRef = xPositions[x];
                var taps = xPhases[x] * 4;
                var sum = 0;
                for (var k = 0; k < 4; k++) {
                    var column = Math.clamp(xRef + k - 1, 0, refWidth - 1);
                    sum += CHROMA_FILTER[taps + k] * source[line + column];
                }
                rows[target + x] = sum >> shift1;
            }
        }
        var plane8 = component == 1 ? out.cb8 : out.cr8;
        var plane16 = component == 1 ? out.cb : out.cr;
        for (var y = 0; y < height; y++) {
            var ref16 = (int) ((((long) (y - currTop) * g.scaleVerC() + addVer + (1 << 11)) >> 12) + refTop);
            var yRef = ref16 >> 4;
            var taps = (ref16 & 15) * 4;
            var base = y * width;
            for (var x = 0; x < width; x++) {
                var sum = 0;
                for (var k = 0; k < 4; k++) {
                    var row = Math.clamp(yRef + k - 1, 0, refHeight - 1);
                    sum += CHROMA_FILTER[taps + k] * rows[row * width + x];
                }
                var value = Math.clamp((sum + offset) >> shift2, 0, max);
                if (plane8 != null) {
                    plane8[base + x] = (byte) value;
                } else {
                    plane16[base + x] = (short) value;
                }
            }
        }
    }

    /**
     * Applies a colour mapping table to the samples of a reference layer picture in place
     * (H.8.1.4.4), producing samples at the table's output bit depths.
     *
     * @param rl the reference layer picture, for its sizes and chroma sampling
     * @param luma the luma samples, rows packed
     * @param cb the Cb samples, rows packed
     * @param cr the Cr samples, rows packed
     * @param mapping the colour mapping table
     * @param scratch the working memory
     */
    private static void colourMap(HevcFrame rl, int[] luma, int[] cb, int[] cr, HevcPps.ColourMapping mapping,
                                  Scratch scratch) {
        var width = rl.width;
        var height = rl.height;
        var chromaWidth = rl.chromaWidth;
        var chromaHeight = rl.chromaHeight;
        var subX = rl.sps.chromaShiftX;
        var subY = rl.sps.chromaShiftY;
        var depth = mapping.octantDepth();
        var yShift = mapping.inputDepthLuma() - depth - mapping.yPartNumLog2();
        var cShift = mapping.inputDepthChroma() - depth;
        var mappingShift = 10 + mapping.inputDepthLuma() - mapping.outputDepthLuma();
        var mappingOffset = 1 << (mappingShift - 1);
        var maxY = (1 << mapping.outputDepthLuma()) - 1;
        var maxC = (1 << mapping.outputDepthChroma()) - 1;
        var octantNumC = 1 << depth;
        var lut = mapping.lut();
        var chromaSize = chromaWidth * chromaHeight;
        var mapped = scratch.mapped = Scratch.fit(scratch.mapped, width * height + 2 * chromaSize);
        var lumaBase = 2 * chromaSize;
        for (var yP = 0; yP < height; yP++) {
            for (var xP = 0; xP < width; xP++) {
                var xPC = xP >> subX;
                var yPC = yP >> subY;
                int tempCb;
                int tempCr;
                if (subX == 1 && subY == 1) {
                    if ((xP & 1) == 0 && (yP & 1) == 0) {
                        var yP2C = Math.max(0, yPC - 1);
                        tempCb = (cb[yPC * chromaWidth + xPC] * 3 + cb[yP2C * chromaWidth + xPC] + 2) >> 2;
                        tempCr = (cr[yPC * chromaWidth + xPC] * 3 + cr[yP2C * chromaWidth + xPC] + 2) >> 2;
                    } else if ((xP & 1) == 0) {
                        var yP2C = Math.min(yPC + 1, chromaHeight - 1);
                        tempCb = (cb[yPC * chromaWidth + xPC] * 3 + cb[yP2C * chromaWidth + xPC] + 2) >> 2;
                        tempCr = (cr[yPC * chromaWidth + xPC] * 3 + cr[yP2C * chromaWidth + xPC] + 2) >> 2;
                    } else if ((yP & 1) == 0) {
                        var xP2C = Math.min(xPC + 1, chromaWidth - 1);
                        var yP2C = Math.max(0, yPC - 1);
                        tempCb = (cb[yP2C * chromaWidth + xPC] + cb[yP2C * chromaWidth + xP2C]
                                + (cb[yPC * chromaWidth + xPC] + cb[yPC * chromaWidth + xP2C]) * 3 + 4) >> 3;
                        tempCr = (cr[yP2C * chromaWidth + xPC] + cr[yP2C * chromaWidth + xP2C]
                                + (cr[yPC * chromaWidth + xPC] + cr[yPC * chromaWidth + xP2C]) * 3 + 4) >> 3;
                    } else {
                        var xP2C = Math.min(xPC + 1, chromaWidth - 1);
                        var yP2C = Math.min(yPC + 1, chromaHeight - 1);
                        tempCb = ((cb[yPC * chromaWidth + xPC] + cb[yPC * chromaWidth + xP2C]) * 3
                                + cb[yP2C * chromaWidth + xPC] + cb[yP2C * chromaWidth + xP2C] + 4) >> 3;
                        tempCr = ((cr[yPC * chromaWidth + xPC] + cr[yPC * chromaWidth + xP2C]) * 3
                                + cr[yP2C * chromaWidth + xPC] + cr[yP2C * chromaWidth + xP2C] + 4) >> 3;
                    }
                } else if (subX == 1) {
                    if ((xP & 1) == 1) {
                        var xP2C = Math.min(xPC + 1, chromaWidth - 1);
                        tempCb = (cb[yPC * chromaWidth + xPC] + cb[yPC * chromaWidth + xP2C] + 1) >> 1;
                        tempCr = (cr[yPC * chromaWidth + xPC] + cr[yPC * chromaWidth + xP2C] + 1) >> 1;
                    } else {
                        tempCb = cb[yPC * chromaWidth + xPC];
                        tempCr = cr[yPC * chromaWidth + xPC];
                    }
                } else {
                    tempCb = cb[yPC * chromaWidth + xPC];
                    tempCr = cr[yPC * chromaWidth + xPC];
                }
                var sample = luma[yP * width + xP];
                var idxY = sample >> yShift;
                var idxCb = depth == 1 ? (tempCb >= mapping.threshU() ? 1 : 0) : tempCb >> cShift;
                var idxCr = depth == 1 ? (tempCr >= mapping.threshV() ? 1 : 0) : tempCr >> cShift;
                var base = ((idxY * octantNumC + idxCb) * octantNumC + idxCr) * 4;
                var value = ((lut[0][base] * sample + lut[0][base + 1] * tempCb + lut[0][base + 2] * tempCr
                        + mappingOffset) >> mappingShift) + lut[0][base + 3];
                mapped[lumaBase + yP * width + xP] = Math.clamp(value, 0, maxY);
            }
        }
        for (var yPC = 0; yPC < chromaHeight; yPC++) {
            for (var xPC = 0; xPC < chromaWidth; xPC++) {
                var xP = xPC << subX;
                var yP = yPC << subY;
                var tempY = subX == 1 && subY == 1
                        ? (luma[yP * width + xP] + luma[Math.min(yP + 1, height - 1) * width + xP] + 1) >> 1
                        : luma[yP * width + xP];
                var sampleCb = cb[yPC * chromaWidth + xPC];
                var sampleCr = cr[yPC * chromaWidth + xPC];
                var idxY = tempY >> yShift;
                var idxCb = depth == 1 ? (sampleCb >= mapping.threshU() ? 1 : 0) : sampleCb >> cShift;
                var idxCr = depth == 1 ? (sampleCr >= mapping.threshV() ? 1 : 0) : sampleCr >> cShift;
                var base = ((idxY * octantNumC + idxCb) * octantNumC + idxCr) * 4;
                var valueCb = ((lut[1][base] * tempY + lut[1][base + 1] * sampleCb + lut[1][base + 2] * sampleCr
                        + mappingOffset) >> mappingShift) + lut[1][base + 3];
                var valueCr = ((lut[2][base] * tempY + lut[2][base + 1] * sampleCb + lut[2][base + 2] * sampleCr
                        + mappingOffset) >> mappingShift) + lut[2][base + 3];
                mapped[yPC * chromaWidth + xPC] = Math.clamp(valueCb, 0, maxC);
                mapped[chromaSize + yPC * chromaWidth + xPC] = Math.clamp(valueCr, 0, maxC);
            }
        }
        System.arraycopy(mapped, lumaBase, luma, 0, width * height);
        System.arraycopy(mapped, 0, cb, 0, chromaSize);
        System.arraycopy(mapped, chromaSize, cr, 0, chromaSize);
    }

    /**
     * Resamples the motion of a reference layer picture into an inter-layer reference picture, one
     * 16x16 block at a time (H.8.1.4.3).
     *
     * @param rl the reference layer picture
     * @param out the inter-layer reference picture
     * @param g the spatial relation
     */
    private static void resampleMotion(HevcFrame rl, HevcFrame out, Geometry g) {
        var scaleX = g.scaledWidth() != g.refRegionWidth();
        var scaleY = g.scaledHeight() != g.refRegionHeight();
        var scaleMvX = Math.clamp(((g.scaledWidth() << 8) + (g.refRegionWidth() >> 1)) / g.refRegionWidth(), -4096, 4095);
        var scaleMvY = Math.clamp(((g.scaledHeight() << 8) + (g.refRegionHeight() >> 1)) / g.refRegionHeight(), -4096,
                4095);
        var blocksX = (out.width + 15) >> 4;
        var blocksY = (out.height + 15) >> 4;
        for (var yB = 0; yB < blocksY; yB++) {
            for (var xB = 0; xB < blocksX; xB++) {
                var xPCtr = (xB << 4) + 8;
                var yPCtr = (yB << 4) + 8;
                var xRef = (int) ((((long) (xPCtr - g.scaledLeft()) * g.scaleHorY() + (1 << 15)) >> 16) + g.refLeft());
                var yRef = (int) ((((long) (yPCtr - g.scaledTop()) * g.scaleVerY() + (1 << 15)) >> 16) + g.refTop());
                var xRL = ((xRef + 4) >> 4) << 4;
                var yRL = ((yRef + 4) >> 4) << 4;
                byte r0 = -1;
                byte r1 = -1;
                var m0 = 0;
                var m1 = 0;
                if (xRL >= 0 && xRL < g.refWidth() && yRL >= 0 && yRL < g.refHeight()) {
                    var block = (yRL >> 2) * rl.width4 + (xRL >> 2);
                    r0 = rl.ref0[block];
                    r1 = rl.ref1[block];
                    if (r0 >= 0) {
                        m0 = scale(rl.mv0[block], scaleX, scaleY, scaleMvX, scaleMvY);
                    }
                    if (r1 >= 0) {
                        m1 = scale(rl.mv1[block], scaleX, scaleY, scaleMvX, scaleMvY);
                    }
                }
                var x4End = Math.min((xB + 1) << 2, out.width4);
                var y4End = Math.min((yB + 1) << 2, out.height4);
                for (var y4 = yB << 2; y4 < y4End; y4++) {
                    for (var x4 = xB << 2; x4 < x4End; x4++) {
                        var index = y4 * out.width4 + x4;
                        out.ref0[index] = r0;
                        out.ref1[index] = r1;
                        out.mv0[index] = m0;
                        out.mv1[index] = m1;
                    }
                }
            }
        }
    }

    /**
     * Scales a packed motion vector by the spatial ratio of the layers (equations H-74 to H-79).
     *
     * @param mv the motion vector, {@code (y << 16) | (x & 0xFFFF)}
     * @param scaleX whether the horizontal component is scaled
     * @param scaleY whether the vertical component is scaled
     * @param scaleMvX {@code scaleMVX}
     * @param scaleMvY {@code scaleMVY}
     * @return the scaled vector, packed
     */
    private static int scale(int mv, boolean scaleX, boolean scaleY, int scaleMvX, int scaleMvY) {
        var x = (short) mv;
        var y = mv >> 16;
        var sx = scaleX ? scaleComponent(x, scaleMvX) : x;
        var sy = scaleY ? scaleComponent(y, scaleMvY) : y;
        return (sy << 16) | (sx & 0xFFFF);
    }

    /**
     * Scales one motion vector component: {@code Clip3(-32768, 32767, Sign(s * v) * ((Abs(s * v) +
     * 127) >> 8))}.
     *
     * @param value the component
     * @param scale the scale factor
     * @return the scaled component
     */
    private static int scaleComponent(int value, int scale) {
        var product = scale * value;
        var magnitude = (Math.abs(product) + 127) >> 8;
        return Math.clamp(product < 0 ? -magnitude : magnitude, -32768, 32767);
    }
}
