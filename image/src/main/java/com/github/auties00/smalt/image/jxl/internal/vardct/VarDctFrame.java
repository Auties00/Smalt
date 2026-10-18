/* Ports jxl-oxide 0.12.6 (crates/jxl-render: the VarDCT renderer). Copyright Wonwoo Choi and the jxl-oxide
   contributors; MIT License. Modified for Smalt. */
package com.github.auties00.smalt.image.jxl.internal.vardct;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.jxl.internal.bitstream.JxlBitReader;
import com.github.auties00.smalt.image.jxl.internal.frame.LfGlobal;
import com.github.auties00.smalt.image.jxl.internal.header.FrameHeader;
import com.github.auties00.smalt.image.jxl.internal.header.ImageHeader;
import com.github.auties00.smalt.image.jxl.internal.modular.MaTree;
import com.github.auties00.smalt.image.jxl.internal.modular.ModularChannel;
import com.github.auties00.smalt.image.jxl.internal.modular.ModularImage;
import com.github.auties00.smalt.internal.limits.LimitChecks;

import java.util.ArrayList;
import java.util.Arrays;

/**
 * Decodes the VarDCT data of a frame (ISO/IEC 18181-1 annex I): the quantized LF image and the
 * varblock metadata of the LF groups, the HF globals, the HF coefficients of the pass groups, and
 * finally the dequantization and inverse transforms that produce the X, Y and B planes.
 *
 * <p>The frame decoder drives this class section by section. LF groups and pass groups of one pass
 * may be decoded concurrently, since each writes only its own region; {@link #reconstruct(int)}
 * runs once per group after the last pass.
 */
public final class VarDctFrame {
    /**
     * The weight of the sample itself in the adaptive LF smoothing.
     */
    private static final float SCALE_SELF = 0.052262735f;

    /**
     * The weight of the side neighbors in the adaptive LF smoothing.
     */
    private static final float SCALE_SIDE = 0.2034514f;

    /**
     * The weight of the diagonal neighbors in the adaptive LF smoothing.
     */
    private static final float SCALE_DIAG = 0.03348292f;

    /**
     * The image header.
     */
    private final ImageHeader image;

    /**
     * The frame header.
     */
    private final FrameHeader frame;

    /**
     * The VarDCT globals.
     */
    private final LfGlobalVarDct globals;

    /**
     * The LF dequantization weights of X, Y and B.
     */
    private final float[] lfDequant;

    /**
     * The resource bounds.
     */
    private final MediaLimits limits;

    /**
     * The horizontal chroma shift of X, Y and B.
     */
    private final int[] hshift = new int[3];

    /**
     * The vertical chroma shift of X, Y and B.
     */
    private final int[] vshift = new int[3];

    /**
     * Whether any channel is chroma subsampled.
     */
    private final boolean subsampled;

    /**
     * The frame width in blocks, rounded up to even when subsampled horizontally.
     */
    private final int blocksWide;

    /**
     * The frame height in blocks, rounded up to even when subsampled vertically.
     */
    private final int blocksHigh;

    /**
     * The LF planes of X, Y and B.
     */
    private final float[][] lf = new float[3][];

    /**
     * The width of each LF plane.
     */
    private final int[] lfWidth = new int[3];

    /**
     * The height of each LF plane.
     */
    private final int[] lfHeight = new int[3];

    /**
     * The output planes of X, Y and B.
     */
    private final float[][] planes = new float[3][];

    /**
     * The width of each output plane.
     */
    private final int[] planeWidth = new int[3];

    /**
     * The height of each output plane.
     */
    private final int[] planeHeight = new int[3];

    /**
     * The quantized LF samples of each LF group (X, Y, B), or {@code null} entries with an LF
     * frame.
     */
    private final int[][][] lfQuant;

    /**
     * The width of the quantized LF planes of each LF group.
     */
    private final int[][] lfQuantStride;

    /**
     * The varblock metadata of each LF group.
     */
    private final HfMetadata[] hfMeta;

    /**
     * The integer coefficients of each group (X, Y, B), alive between the first and last pass.
     */
    private final int[][][] coefficients;

    /**
     * The dequantization matrices.
     */
    private DequantMatrices matrices;

    /**
     * The number of HF presets.
     */
    private int numPresets;

    /**
     * The HF passes.
     */
    private HfPass[] passes;

    /**
     * Prepares the VarDCT decoding of a frame.
     *
     * @param image the image header
     * @param frame the frame header
     * @param globals the VarDCT globals
     * @param lfDequant the LF dequantization weights
     * @param limits the resource bounds
     * @throws MediaException.LimitExceeded if the planes exceed the limits
     */
    public VarDctFrame(ImageHeader image, FrameHeader frame, LfGlobalVarDct globals, float[] lfDequant,
                       MediaLimits limits) {
        this.image = image;
        this.frame = frame;
        this.globals = globals;
        this.lfDequant = lfDequant;
        this.limits = limits;
        var hUp = false;
        var vUp = false;
        for (var mode : frame.jpegUpsampling) {
            hUp |= mode == 1 || mode == 2;
            vUp |= mode == 1 || mode == 3;
        }
        var any = false;
        for (var c = 0; c < 3; c++) {
            var shifts = LfGlobal.jpegShifts(frame.jpegUpsampling, c);
            hshift[c] = shifts[0];
            vshift[c] = shifts[1];
            any |= shifts[0] != 0 || shifts[1] != 0;
        }
        this.subsampled = any;
        var bw = (frame.colorWidth() + 7) / 8;
        var bh = (frame.colorHeight() + 7) / 8;
        if (hUp) {
            bw = (bw + 1) / 2 * 2;
        }
        if (vUp) {
            bh = (bh + 1) / 2 * 2;
        }
        this.blocksWide = bw;
        this.blocksHigh = bh;
        var total = 0L;
        for (var c = 0; c < 3; c++) {
            lfWidth[c] = bw >> hshift[c];
            lfHeight[c] = bh >> vshift[c];
            planeWidth[c] = lfWidth[c] * 8;
            planeHeight[c] = lfHeight[c] * 8;
            total += (long) planeWidth[c] * planeHeight[c] * 2;
        }
        LimitChecks.checkAllocation(limits, total * 4);
        for (var c = 0; c < 3; c++) {
            lf[c] = new float[lfWidth[c] * lfHeight[c]];
            planes[c] = new float[planeWidth[c] * planeHeight[c]];
        }
        var numLf = frame.numLfGroups();
        this.lfQuant = new int[numLf][][];
        this.lfQuantStride = new int[numLf][];
        this.hfMeta = new HfMetadata[numLf];
        this.coefficients = new int[frame.numGroups()][][];
    }

    /**
     * Returns the size in pixels of an LF group.
     *
     * @param lfGroup the LF group index
     * @return {@code {left, top, width, height}} in color samples
     */
    private int[] lfGroupRect(int lfGroup) {
        var dim = frame.lfGroupDim();
        var perRow = frame.lfGroupsPerRow();
        var left = (lfGroup % perRow) * dim;
        var top = (lfGroup / perRow) * dim;
        return new int[]{left, top, Math.min(dim, frame.colorWidth() - left), Math.min(dim, frame.colorHeight() - top)};
    }

    /**
     * Reads the quantized LF image of an LF group and dequantizes it into the LF planes.
     *
     * @param br the bitstream of the LF group
     * @param lfGroup the LF group index
     * @param globalTree the global MA tree, or {@code null}
     * @throws MediaException.Malformed if the data is invalid
     */
    public void readLfCoefficients(JxlBitReader br, int lfGroup, MaTree globalTree) {
        var rect = lfGroupRect(lfGroup);
        var extraPrecision = br.readBits(2);
        var w = (rect[2] + 7) / 8;
        var h = (rect[3] + 7) / 8;
        var channels = new ArrayList<ModularChannel>(3);
        var order = new int[]{1, 0, 2};
        for (var c : order) {
            var spec = LfGlobal.jpegChannel(w, h, frame.jpegUpsampling, c);
            channels.add(spec);
        }
        var modular = ModularImage.read(br, channels, globalTree, image.bitDepth().bitsPerSample(), limits, true);
        modular.decode(br, 1 + lfGroup, modular.channels().size());
        var decoded = modular.finish();
        var quant = new int[3][];
        var stride = new int[3];
        quant[0] = decoded.get(1).data;
        stride[0] = decoded.get(1).width;
        quant[1] = decoded.get(0).data;
        stride[1] = decoded.get(0).width;
        quant[2] = decoded.get(2).data;
        stride[2] = decoded.get(2).width;
        lfQuant[lfGroup] = quant;
        lfQuantStride[lfGroup] = stride;
        var gd = frame.groupDim();
        var perRow = frame.lfGroupsPerRow();
        var lx = lfGroup % perRow;
        var ly = lfGroup / perRow;
        var scaleInv = (double) globals.globalScale() * globals.quantLf();
        for (var c = 0; c < 3; c++) {
            var scale = (float) (lfDequant[c] * (double) (1 << (9 - extraPrecision)) / scaleInv);
            var source = quant[c];
            var srcW = stride[c];
            var srcH = source.length / Math.max(srcW, 1);
            var x0 = lx * (gd >> hshift[c]);
            var y0 = ly * (gd >> vshift[c]);
            var copyW = Math.min(srcW, lfWidth[c] - x0);
            var copyH = Math.min(srcH, lfHeight[c] - y0);
            var target = lf[c];
            for (var y = 0; y < copyH; y++) {
                var out = (y0 + y) * lfWidth[c] + x0;
                var in = y * srcW;
                for (var x = 0; x < copyW; x++) {
                    target[out + x] = source[in + x] * scale;
                }
            }
        }
    }

    /**
     * Reads the varblock metadata of an LF group.
     *
     * @param br the bitstream of the LF group, after its modular data
     * @param lfGroup the LF group index
     * @param globalTree the global MA tree, or {@code null}
     * @throws MediaException.Malformed if the metadata is invalid
     */
    public void readHfMetadata(JxlBitReader br, int lfGroup, MaTree globalTree) {
        var rect = lfGroupRect(lfGroup);
        hfMeta[lfGroup] = HfMetadata.read(br, frame, lfGroup, rect[2], rect[3], globals.globalScale(),
                image.bitDepth().bitsPerSample(), globalTree, limits);
    }

    /**
     * Uses the image of an LF frame as the LF planes.
     *
     * @param x the X plane of the LF frame
     * @param y the Y plane
     * @param b the B plane
     * @param width the LF frame width
     * @param height the LF frame height
     */
    public void useLfFrame(float[] x, float[] y, float[] b, int width, int height) {
        var sources = new float[][]{x, y, b};
        for (var c = 0; c < 3; c++) {
            var copyW = Math.min(width, lfWidth[c]);
            var copyH = Math.min(height, lfHeight[c]);
            for (var row = 0; row < copyH; row++) {
                System.arraycopy(sources[c], row * width, lf[c], row * lfWidth[c], copyW);
            }
        }
    }

    /**
     * Applies chroma-from-luma and adaptive smoothing to the LF planes, once every LF group is
     * decoded.
     */
    public void finishLf() {
        if (!subsampled) {
            var kx = globals.baseCorrelationX() + (globals.xFactorLf() - 128) / (float) globals.colorFactor();
            var kb = globals.baseCorrelationB() + (globals.bFactorLf() - 128) / (float) globals.colorFactor();
            var px = lf[0];
            var py = lf[1];
            var pb = lf[2];
            for (var i = 0; i < py.length; i++) {
                var luma = py[i];
                px[i] += kx * luma;
                pb[i] += kb * luma;
            }
        }
        if (!frame.hasFlag(FrameHeader.FLAG_SKIP_ADAPTIVE_LF_SMOOTHING)) {
            smoothLf();
        }
    }

    /**
     * Applies the adaptive LF smoothing (ISO/IEC 18181-1 section I.4.2).
     */
    private void smoothLf() {
        var scaleInv = (double) globals.globalScale() * globals.quantLf();
        var lfX = (float) (512.0 * lfDequant[0] / scaleInv);
        var lfY = (float) (512.0 * lfDequant[1] / scaleInv);
        var lfB = (float) (512.0 * lfDequant[2] / scaleInv);
        var width = Math.max(lfWidth[0], Math.max(lfWidth[1], lfWidth[2]));
        var height = Math.max(lfHeight[0], Math.max(lfHeight[1], lfHeight[2]));
        if (width <= 2 || height <= 2) {
            return;
        }
        if (subsampled) {
            smoothLfSubsampled(width, height, lfX, lfY, lfB);
            return;
        }
        var inX = lf[0];
        var inY = lf[1];
        var inB = lf[2];
        var sumX = verticalSums(inX, width, height);
        var sumY = verticalSums(inY, width, height);
        var sumB = verticalSums(inB, width, height);
        for (var y = 1; y < height - 1; y++) {
            var row = y * width;
            var sumRow = (y - 1) * width;
            var prevX = inX[row];
            var prevY = inY[row];
            var prevB = inB[row];
            for (var x = 1; x < width - 1; x++) {
                var i = row + x;
                var s = sumRow + x;
                var xSelf = inX[i];
                var xWa = xSelf * SCALE_SELF + (prevX + inX[i + 1] + sumX[s]) * SCALE_SIDE
                        + (sumX[s - 1] + sumX[s + 1]) * SCALE_DIAG;
                var ySelf = inY[i];
                var yWa = ySelf * SCALE_SELF + (prevY + inY[i + 1] + sumY[s]) * SCALE_SIDE
                        + (sumY[s - 1] + sumY[s + 1]) * SCALE_DIAG;
                var bSelf = inB[i];
                var bWa = bSelf * SCALE_SELF + (prevB + inB[i + 1] + sumB[s]) * SCALE_SIDE
                        + (sumB[s - 1] + sumB[s + 1]) * SCALE_DIAG;
                var gap = Math.max(0.5f, Math.max(Math.abs(xWa - xSelf) / lfX,
                        Math.max(Math.abs(yWa - ySelf) / lfY, Math.abs(bWa - bSelf) / lfB)));
                var gapScale = Math.max(3.0f - 4.0f * gap, 0.0f);
                inX[i] = (xWa - xSelf) * gapScale + xSelf;
                inY[i] = (yWa - ySelf) * gapScale + ySelf;
                inB[i] = (bWa - bSelf) * gapScale + bSelf;
                prevX = xSelf;
                prevY = ySelf;
                prevB = bSelf;
            }
        }
    }

    /**
     * Computes the sums of the samples above and below every sample of the inner rows.
     *
     * @param plane the source samples
     * @param width the plane width in samples
     * @param height the plane height in samples
     * @return {@code height - 2} rows of sums
     */
    private static float[] verticalSums(float[] plane, int width, int height) {
        var out = new float[width * (height - 2)];
        for (var y = 0; y < height - 2; y++) {
            var up = y * width;
            var down = (y + 2) * width;
            var o = y * width;
            for (var x = 0; x < width; x++) {
                out[o + x] = plane[up + x] + plane[down + x];
            }
        }
        return out;
    }

    /**
     * Applies the adaptive LF smoothing to chroma-subsampled LF planes.
     *
     * @param width the luma LF width
     * @param height the luma LF height
     * @param lfX the X threshold
     * @param lfY the Y threshold
     * @param lfB the B threshold
     */
    private void smoothLfSubsampled(int width, int height, float lfX, float lfY, float lfB) {
        for (var c = 0; c < 3; c++) {
            if (lfWidth[c] <= 2 || lfHeight[c] <= 2) {
                return;
            }
        }
        var wa = new float[3][];
        for (var c = 0; c < 3; c++) {
            var w = lfWidth[c];
            var h = lfHeight[c];
            var plane = lf[c];
            var out = new float[(w - 2) * (h - 2)];
            for (var y = 0; y < h - 2; y++) {
                for (var x = 0; x < w - 2; x++) {
                    var up = y * w + x;
                    var center = (y + 1) * w + x;
                    var down = (y + 2) * w + x;
                    var me = plane[center + 1];
                    var side = plane[up + 1] + plane[center] + plane[center + 2] + plane[down + 1];
                    var diag = plane[up] + plane[up + 2] + plane[down] + plane[down + 2];
                    out[y * (w - 2) + x] = me * SCALE_SELF + side * SCALE_SIDE + diag * SCALE_DIAG;
                }
            }
            wa[c] = out;
        }
        var lastRow = new int[3];
        var current = new float[3][];
        for (var c = 0; c < 3; c++) {
            current[c] = new float[lfWidth[c]];
        }
        var thresholds = new float[]{lfX, lfY, lfB};
        for (var row = 0; row < height; row++) {
            var coordY = new int[3];
            var skipRow = false;
            for (var c = 0; c < 3; c++) {
                coordY[c] = row >> vshift[c];
                if (coordY[c] == 0 || coordY[c] == lfHeight[c] - 1) {
                    skipRow = true;
                }
            }
            if (skipRow) {
                continue;
            }
            for (var c = 0; c < 3; c++) {
                if (lastRow[c] != coordY[c]) {
                    System.arraycopy(lf[c], coordY[c] * lfWidth[c], current[c], 0, lfWidth[c]);
                }
                lastRow[c] = coordY[c];
            }
            for (var col = 0; col < width; col++) {
                var coordX = new int[3];
                var skip = false;
                for (var c = 0; c < 3; c++) {
                    coordX[c] = col >> hshift[c];
                    if (coordX[c] == 0 || coordX[c] == lfWidth[c] - 1) {
                        skip = true;
                    }
                }
                if (skip) {
                    continue;
                }
                var self = new float[3];
                var weighted = new float[3];
                var gap = 0.5f;
                for (var c = 0; c < 3; c++) {
                    self[c] = current[c][coordX[c]];
                    weighted[c] = wa[c][(coordY[c] - 1) * (lfWidth[c] - 2) + coordX[c] - 1];
                    gap = Math.max(gap, Math.abs(weighted[c] - self[c]) / thresholds[c]);
                }
                var gapScale = Math.max(3.0f - 4.0f * gap, 0.0f);
                for (var c = 0; c < 3; c++) {
                    var update = (coordY[c] << vshift[c]) == row && (coordX[c] << hshift[c]) == col;
                    if (update) {
                        lf[c][coordY[c] * lfWidth[c] + coordX[c]] = (weighted[c] - self[c]) * gapScale + self[c];
                    }
                }
            }
        }
    }

    /**
     * Reads the HF global section.
     *
     * @param br the bitstream
     * @param globalTree the global MA tree, or {@code null}
     * @throws MediaException.Malformed if the section is invalid
     */
    public void readHfGlobal(JxlBitReader br, MaTree globalTree) {
        matrices = DequantMatrices.read(br, image.bitDepth().bitsPerSample(), frame.numLfGroups(), globalTree, limits);
        var numGroups = frame.numGroups();
        var bits = 32 - Integer.numberOfLeadingZeros(numGroups - 1);
        if (numGroups == 1) {
            bits = 0;
        }
        numPresets = br.readBits(bits) + 1;
        passes = new HfPass[frame.numPasses];
        for (var p = 0; p < frame.numPasses; p++) {
            passes[p] = HfPass.read(br, numPresets, globals.numBlockClusters());
        }
    }

    /**
     * Returns the geometry of a group in one channel.
     *
     * @param group the group index
     * @param c the channel
     * @return {@code {x0, y0, width, height}} in plane samples
     */
    private int[] groupRect(int group, int c) {
        var perRow = frame.groupsPerRow();
        var gd = frame.groupDim();
        var gw = gd >> hshift[c];
        var gh = gd >> vshift[c];
        var x0 = (group % perRow) * gw;
        var y0 = (group / perRow) * gh;
        return new int[]{x0, y0, Math.max(0, Math.min(gw, planeWidth[c] - x0)),
                Math.max(0, Math.min(gh, planeHeight[c] - y0))};
    }

    /**
     * Reads the HF coefficients of a group in one pass.
     *
     * @param br the bitstream of the pass group
     * @param pass the pass index
     * @param group the group index
     * @throws MediaException.Malformed if the coefficients are invalid
     */
    public void readPassGroup(JxlBitReader br, int pass, int group) {
        var lfGroup = frame.lfGroupOfGroup(group);
        var meta = hfMeta[lfGroup];
        if (meta == null) {
            throw new MediaException.Malformed("pass group before its LF group");
        }
        var perRow = frame.groupsPerRow();
        var left = (group % perRow % 8) * 32;
        var top = (group / perRow % 8) * 32;
        var width = Math.min(meta.blockWidth() - left, 32);
        var height = Math.min(meta.blockHeight() - top, 32);
        if (width <= 0 || height <= 0) {
            return;
        }
        var coeff = coefficients[group];
        var strides = new int[3];
        if (coeff == null) {
            coeff = new int[3][];
            for (var c = 0; c < 3; c++) {
                var rect = groupRect(group, c);
                coeff[c] = new int[rect[2] * rect[3]];
            }
            coefficients[group] = coeff;
        }
        for (var c = 0; c < 3; c++) {
            strides[c] = groupRect(group, c)[2];
        }
        var shift = pass < frame.passShift.length ? frame.passShift[pass] : 0;
        CoefficientDecoder.decode(br, passes[pass], numPresets, globals, meta.blocks(), meta.blockWidth(), left, top,
                width, height, hshift, vshift, lfQuant[lfGroup], lfQuantStride[lfGroup], shift, coeff, strides);
    }

    /**
     * Dequantizes and inverse transforms a group into the output planes.
     *
     * @param group the group index
     */
    public void reconstruct(int group) {
        var lfGroup = frame.lfGroupOfGroup(group);
        var meta = hfMeta[lfGroup];
        var perRow = frame.groupsPerRow();
        var left = (group % perRow % 8) * 32;
        var top = (group / perRow % 8) * 32;
        if (meta == null) {
            return;
        }
        var width = Math.min(meta.blockWidth() - left, 32);
        var height = Math.min(meta.blockHeight() - top, 32);
        if (width <= 0 || height <= 0) {
            return;
        }
        var coeff = coefficients[group];
        coefficients[group] = null;
        var rects = new int[3][];
        for (var c = 0; c < 3; c++) {
            rects[c] = groupRect(group, c);
        }
        var blocks = meta.blocks();
        var stride = meta.blockWidth();
        var oim = image.opsinInverse();
        var qmScale = new float[]{(float) Math.pow(0.8, frame.xQmScale - 2), 1.0f, (float) Math.pow(0.8, frame.bQmScale - 2)};
        var quantBiasNumerator = oim.quantBiasNumerator();
        for (var c = 0; c < 3; c++) {
            var rect = rects[c];
            var plane = planes[c];
            var pw = planeWidth[c];
            var base = rect[1] * pw + rect[0];
            for (var y = 0; y < rect[3]; y++) {
                Arrays.fill(plane, base + y * pw, base + y * pw + rect[2], 0.0f);
            }
            if (coeff == null) {
                continue;
            }
            var source = coeff[c];
            var quantBias = oim.quantBias()[c];
            var hs = hshift[c];
            var vs = vshift[c];
            for (var by = 0; by < height; by++) {
                for (var bx = 0; bx < width; bx++) {
                    var info = blocks[(top + by) * stride + left + bx];
                    if (!HfMetadata.isData(info)) {
                        continue;
                    }
                    var sbx = bx >> hs;
                    var sby = by >> vs;
                    if (hs != 0 || vs != 0) {
                        if ((sbx << hs) != bx || (sby << vs) != by) {
                            continue;
                        }
                        if (!HfMetadata.isData(blocks[(top + sby) * stride + left + sbx])) {
                            continue;
                        }
                    }
                    var type = HfMetadata.type(info);
                    var hfMul = HfMetadata.hfMul(info);
                    var bw = TransformType.width(type) * 8;
                    var bh = TransformType.height(type) * 8;
                    var mul = 65536.0f / (globals.globalScale() * (float) hfMul) * qmScale[c];
                    var matrix = matrices.matrix(type, c);
                    var bx0 = sbx * 8;
                    var by0 = sby * 8;
                    for (var y = 0; y < bh; y++) {
                        var in = (by0 + y) * rect[2] + bx0;
                        var out = base + (by0 + y) * pw + bx0;
                        var m = y * bw;
                        for (var x = 0; x < bw; x++) {
                            var q = (float) source[in + x];
                            if (Math.abs(q) <= 1.0f) {
                                q *= quantBias;
                            } else {
                                q -= quantBiasNumerator / q;
                            }
                            plane[out + x] = q * matrix[m + x] * mul;
                        }
                    }
                }
            }
        }
        if (!subsampled) {
            chromaFromLuma(group, meta, rects[0]);
        }
        var scratch = new float[1024];
        for (var c = 0; c < 3; c++) {
            var rect = rects[c];
            var plane = planes[c];
            var pw = planeWidth[c];
            var base = rect[1] * pw + rect[0];
            var hs = hshift[c];
            var vs = vshift[c];
            var lfPlane = lf[c];
            var lw = lfWidth[c];
            var lfBaseX = rect[0] / 8;
            var lfBaseY = rect[1] / 8;
            for (var by = 0; by < height; by++) {
                for (var bx = 0; bx < width; bx++) {
                    var info = blocks[(top + by) * stride + left + bx];
                    if (!HfMetadata.isData(info)) {
                        continue;
                    }
                    var sbx = bx >> hs;
                    var sby = by >> vs;
                    if (hs != 0 || vs != 0) {
                        if ((sbx << hs) != bx || (sby << vs) != by) {
                            continue;
                        }
                        if (!HfMetadata.isData(blocks[(top + sby) * stride + left + sbx])) {
                            continue;
                        }
                    }
                    var type = HfMetadata.type(info);
                    var off = base + sby * 8 * pw + sbx * 8;
                    var lfOff = (lfBaseY + sby) * lw + lfBaseX + sbx;
                    Varblocks.transform(type, plane, off, pw, lfPlane, lfOff, lw, scratch);
                }
            }
        }
    }

    /**
     * Applies chroma-from-luma to the dequantized HF coefficients of a group.
     *
     * @param group the group index
     * @param meta the metadata of the group's LF group
     * @param rect the group rectangle in the planes
     */
    private void chromaFromLuma(int group, HfMetadata meta, int[] rect) {
        var perRow = frame.groupsPerRow();
        var gd = frame.groupDim();
        var baseX = (group % perRow % 8) * gd / 64;
        var baseY = (group / perRow % 8) * gd / 64;
        var pw = planeWidth[0];
        var base = rect[1] * pw + rect[0];
        var px = planes[0];
        var py = planes[1];
        var pb = planes[2];
        var colorFactor = (float) globals.colorFactor();
        var cflWidth = meta.cflWidth();
        for (var y = 0; y < rect[3]; y++) {
            var tileRow = (baseY + y / 64) * cflWidth;
            for (var x64 = 0; x64 * 64 < rect[2]; x64++) {
                var factorIndex = tileRow + baseX + x64;
                var kx = globals.baseCorrelationX() + meta.xFromY()[factorIndex] / colorFactor;
                var kb = globals.baseCorrelationB() + meta.bFromY()[factorIndex] / colorFactor;
                var end = Math.min(rect[2], x64 * 64 + 64);
                for (var x = x64 * 64; x < end; x++) {
                    var i = base + y * pw + x;
                    var luma = py[i];
                    px[i] += kx * luma;
                    pb[i] += kb * luma;
                }
            }
        }
    }

    /**
     * Returns the quantized HF coefficients of a group, as left by the last pass.
     *
     * <p>Only meaningful before {@link #reconstruct(int)} consumes them.
     *
     * @param group the group index
     * @return the X, Y and B coefficients, each {@link #groupStride(int, int)} wide, or {@code null}
     * for a group without coefficients
     */
    public int[][] coefficients(int group) {
        return coefficients[group];
    }

    /**
     * Returns the row length of a group's coefficients in one channel.
     *
     * @param group the group index
     * @param c the channel, 0 for X, 1 for Y, 2 for B
     * @return the width in coefficients
     */
    public int groupStride(int group, int c) {
        return groupRect(group, c)[2];
    }

    /**
     * Returns the quantized LF samples of an LF group.
     *
     * @param lfGroup the LF group index
     * @return the X, Y and B samples, or {@code null} for a frame that uses an LF frame
     */
    public int[][] lfQuant(int lfGroup) {
        return lfQuant[lfGroup];
    }

    /**
     * Returns the row length of an LF group's quantized LF samples.
     *
     * @param lfGroup the LF group index
     * @return the X, Y and B widths
     */
    public int[] lfQuantStride(int lfGroup) {
        return lfQuantStride[lfGroup];
    }

    /**
     * Returns the horizontal chroma shift of each channel.
     *
     * @return the X, Y and B shifts
     */
    public int[] hshifts() {
        return hshift.clone();
    }

    /**
     * Returns the vertical chroma shift of each channel.
     *
     * @return the X, Y and B shifts
     */
    public int[] vshifts() {
        return vshift.clone();
    }

    /**
     * Returns the decoded X, Y and B planes.
     *
     * @return the planes, each {@link #planeWidth(int)} wide
     */
    public float[][] planes() {
        return planes;
    }

    /**
     * Returns the width of a plane.
     *
     * @param c the channel
     * @return the width in samples, a multiple of 8
     */
    public int planeWidth(int c) {
        return planeWidth[c];
    }

    /**
     * Returns the height of a plane.
     *
     * @param c the channel
     * @return the height in samples, a multiple of 8
     */
    public int planeHeight(int c) {
        return planeHeight[c];
    }

    /**
     * Returns the horizontal chroma shift of a channel.
     *
     * @param c the channel
     * @return 0 or 1
     */
    public int hshift(int c) {
        return hshift[c];
    }

    /**
     * Returns the vertical chroma shift of a channel.
     *
     * @param c the channel
     * @return 0 or 1
     */
    public int vshift(int c) {
        return vshift[c];
    }

    /**
     * Returns the varblock metadata of an LF group.
     *
     * @param lfGroup the LF group index
     * @return the metadata, or {@code null} before it is read
     */
    public HfMetadata hfMetadata(int lfGroup) {
        return hfMeta[lfGroup];
    }

    /**
     * Returns the dequantization matrices.
     *
     * @return the matrices, or {@code null} before the HF global section is read
     */
    public DequantMatrices matrices() {
        return matrices;
    }

    /**
     * Returns the width of the frame in blocks.
     *
     * @return the block columns
     */
    public int blocksWide() {
        return blocksWide;
    }

    /**
     * Returns the height of the frame in blocks.
     *
     * @return the block rows
     */
    public int blocksHigh() {
        return blocksHigh;
    }
}
