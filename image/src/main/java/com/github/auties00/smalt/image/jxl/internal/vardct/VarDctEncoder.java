/* Ports libjxl 0.11.2 (lib/jxl/enc_group.cc: QuantizeBlockAC, AdjustQuantBlockAC, QuantizeRoundtripYBlockAC,
   ComputeCoefficients; enc_entropy_coder.cc: TokenizeCoefficients; enc_modular.cc: AddVarDCTDC and AddACMetadata;
   quantizer.cc: ComputeGlobalScaleAndQuant; enc_adaptive_quantization.cc: InitialQuantDC; enc_gaborish.cc:
   GaborishInverse; enc_chroma_from_luma.cc: FindBestMultiplier and ComputeTile; enc_frame.cc: the VarDCT section
   layout). Copyright the JPEG XL Project Authors; BSD 3-Clause
   License. Modified for Smalt. */
package com.github.auties00.smalt.image.jxl.internal.vardct;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.jxl.internal.bitstream.JxlBitReader;
import com.github.auties00.smalt.image.jxl.internal.encode.EntropyEncoder;
import com.github.auties00.smalt.image.jxl.internal.encode.JxlBitWriter;
import com.github.auties00.smalt.image.jxl.internal.encode.Tokens;
import com.github.auties00.smalt.image.jxl.internal.modular.ModularChannel;
import com.github.auties00.smalt.image.jxl.internal.modular.ModularEncoder;
import com.github.auties00.smalt.internal.task.ParallelBands;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Encodes the VarDCT parts of a frame (ISO/IEC 18181-1 clause I): the quantized LF image, the HF metadata and the
 * quantized HF coefficients of three channels, from XYB planes or from JPEG coefficients.
 *
 * <p>Instances are not thread-safe.
 */
public final class VarDctEncoder {
    private static final int[] DEFAULT_CONTEXT_MAP = {
            0, 1, 2, 2, 3, 3, 4, 5, 6, 6, 6, 6, 6, 7, 8, 9, 9, 10, 11, 12, 13, 14, 14, 14,
            14, 14, 7, 8, 9, 9, 10, 11, 12, 13, 14, 14, 14, 14, 14,
    };

    private static final int[] FREQ_CONTEXT = {
            0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 15, 16, 16, 17, 17, 18, 18, 19, 19,
            20, 20, 21, 21, 22, 22, 23, 23, 23, 23, 24, 24, 24, 24, 25, 25, 25, 25, 26, 26, 26, 26, 27,
            27, 27, 27, 28, 28, 28, 28, 29, 29, 29, 29, 30, 30, 30, 30,
    };

    private static final int[] NONZERO_CONTEXT = {
            0, 31, 62, 62, 93, 93, 93, 93, 123, 123, 123, 123, 152, 152, 152, 152, 152, 152, 152, 152,
            180, 180, 180, 180, 180, 180, 180, 180, 180, 180, 180, 180, 206, 206, 206, 206, 206, 206,
            206, 206, 206, 206, 206, 206, 206, 206, 206, 206, 206, 206, 206, 206, 206, 206, 206, 206,
            206, 206, 206, 206, 206, 206, 206,
    };

    private static final int[] CHANNEL_ORDER = {1, 0, 2};

    /** The order the LF quantization contexts of the X, B and Y channels combine in. */
    private static final int[] LF_CONTEXT_ORDER = {0, 2, 1};

    /** The error limit per coefficient of the large DCTs, by transform (16, 32x16, 32, 64) and channel. */
    private static final double[][] LARGE_ERROR_MUL = {
            {0.22080615753848404, 0.45797479824262011, 0.29859235095977965},
            {0.70109486510286834, 0.16185281305512639, 0.14387691730035473},
            {0.114985964456218638, 0.44656840441027695, 0.10587658215149048},
            {0.46849665264409396, 0.41239077937781954, 0.088667407767185444}};

    /** The error limit per unit of quantized magnitude of the large DCTs, by transform and channel. */
    private static final double[][] LARGE_VALUE_MUL = {
            {0.27450281941822197, 1.1255766549984996, 0.98950459134128388},
            {0.4652168675598285, 0.40945807983455818, 0.36581899811751367},
            {0.28034972424715715, 0.9182653201929738, 1.5581531543057416},
            {0.26873118114033728, 0.68863712390392484, 1.2082185408666786}};

    /**
     * The first scratch index the forward 8x8 transforms keep temporaries at, past what the DCT itself uses.
     */
    static final int SCRATCH_TEMP = 256;

    /**
     * The dead-zone thresholds of a Y block quantized without adjustment, by quadrant.
     */
    private static final float[] Y_THRESHOLDS = {0.56f, 0.62f, 0.62f, 0.62f};

    /**
     * The dead-zone thresholds an adjusted block starts from, by quadrant.
     */
    private static final float[] ADJUST_THRESHOLDS = {0.58f, 0.64f, 0.64f, 0.64f};

    /**
     * The dead-zone thresholds of X and B blocks, by quadrant.
     */
    private static final float[] CHROMA_THRESHOLDS = {0.58f, 0.62f, 0.62f, 0.62f};

    /**
     * The weight of the highest frequencies in the quantization adjustment, by channel.
     */
    private static final float[] MUL_HIGHEST = {70, 30, 60};

    private static final float[] QUANT_BIAS = {1.0f - 0.05465007330715401f, 1.0f - 0.07005449891748593f,
            1.0f - 0.049935103337343655f};

    private static final float QUANT_BIAS_NUMERATOR = 0.145f;

    private static final float[] DEFAULT_LF_DEQUANT = {1.0f / 32.0f, 1.0f / 4.0f, 1.0f / 2.0f};

    private static final float[] GABORISH = {-0.09495815671340026f, -0.041031725066768575f, 0.013710004822696948f,
            0.006510206083837737f, -0.0014789063378272242f};

    private final int width;

    private final int height;

    private final int bw;

    private final int bh;

    private final int[] hshift = new int[3];

    private final int[] vshift = new int[3];

    private final int[] jpegUpsampling;

    /**
     * The transform type of each block at its varblock's first block, or -1 for a block a varblock covers.
     */
    final int[] strategy;

    /**
     * The HF multiplier of each varblock's first block, from 1 to 256.
     */
    final int[] quantField;

    final int[] sharpness;

    final int cflWidth;

    final int cflHeight;

    final int[] cmapX;

    final int[] cmapB;

    int globalScale;

    int quantLf;

    int xQmScale = 2;

    int bQmScale = 2;

    float[] lfDequant = DEFAULT_LF_DEQUANT.clone();

    int[][] lfThresholds = {new int[0], new int[0], new int[0]};

    int[] qfThresholds = new int[0];

    int[] blockContextMap = DEFAULT_CONTEXT_MAP;

    int numBlockClusters = 15;

    int colorFactor = 84;

    float baseCorrelationX;

    float baseCorrelationB = 1.0f;

    int xFactorLf = 128;

    int bFactorLf = 128;

    final int[][] lfQuant = new int[3][];

    final int[][] coefficients = new int[3][];

    private final int numLf;

    private final int numGroups;

    private final int groupsX;

    private final int lfGroupsX;

    private DequantMatrices matrices;

    private int[][] rawQuantTable;

    private boolean skipLfSmoothing;

    private final List<ModularEncoder.Stream> lfStreams = new ArrayList<>();

    private final List<ModularEncoder.Stream> metaStreams = new ArrayList<>();

    private ModularEncoder.Stream quantTableStream;

    private EntropyEncoder hfCode;

    private final List<Tokens> hfTokens = new ArrayList<>();

    private VarDctEncoder(int width, int height, int[] jpegUpsampling) {
        this.width = width;
        this.height = height;
        this.jpegUpsampling = jpegUpsampling;
        var hUp = false;
        var vUp = false;
        for (var mode : jpegUpsampling) {
            hUp |= mode == 1 || mode == 2;
            vUp |= mode == 1 || mode == 3;
        }
        for (var c = 0; c < 3; c++) {
            var s = com.github.auties00.smalt.image.jxl.internal.frame.LfGlobal.jpegShifts(jpegUpsampling, c);
            hshift[c] = s[0];
            vshift[c] = s[1];
        }
        var w = (width + 7) / 8;
        var h = (height + 7) / 8;
        if (hUp) {
            w = (w + 1) / 2 * 2;
        }
        if (vUp) {
            h = (h + 1) / 2 * 2;
        }
        this.bw = w;
        this.bh = h;
        this.strategy = new int[bw * bh];
        this.quantField = new int[bw * bh];
        this.sharpness = new int[bw * bh];
        this.cflWidth = (width + 63) / 64;
        this.cflHeight = (height + 63) / 64;
        this.cmapX = new int[cflWidth * cflHeight];
        this.cmapB = new int[cflWidth * cflHeight];
        this.groupsX = (width + 255) / 256;
        this.numGroups = groupsX * ((height + 255) / 256);
        this.lfGroupsX = (width + 2047) / 2048;
        this.numLf = lfGroupsX * ((height + 2047) / 2048);
        for (var c = 0; c < 3; c++) {
            lfQuant[c] = new int[(bw >> hshift[c]) * (bh >> vshift[c])];
            coefficients[c] = new int[(bw >> hshift[c]) * 8 * (bh >> vshift[c]) * 8];
        }
    }

    /**
     * Encodes XYB planes lossily.
     *
     * @param xyb the X, Y and B planes, each {@code ceil(width / 8) * 8} by {@code ceil(height / 8) * 8} samples,
     * edge-replicated beyond the image; Gaborish may modify them
     * @param width the frame width
     * @param height the frame height
     * @param distance the Butteraugli distance target, above 0
     * @param effort the effort, from 1 to 10
     * @param gaborish whether the frame enables the Gaborish filter, in which case the planes are sharpened by its
     * approximate inverse first
     * @return the encoder
     */
    public static VarDctEncoder fromXyb(float[][] xyb, int width, int height, float distance, int effort,
                                        boolean gaborish) {
        var e = new VarDctEncoder(width, height, new int[3]);
        e.matrices = defaultMatrices();
        e.xQmScale = 3;
        for (var step : new float[]{2.5f, 5.5f, 9.5f}) {
            if (distance > step) {
                e.xQmScale++;
            }
        }
        e.bQmScale = 2;
        var stride = e.bw * 8;
        if (effort >= 7) {
            e.adjustChromacity(xyb, width, height, stride);
        }
        var quantDc = initialQuantDc(distance);
        float[] field = null;
        float[] mask = null;
        if (effort >= 5) {
            var target = gaborish ? distance : distance * 0.62f;
            field = AdaptiveQuantization.field(xyb, e.bw, e.bh, target, 0.725f / target);
            mask = AcStrategies.mask1x1(xyb[1], stride, e.bh * 8);
            e.computeGlobalScaleAndQuant(quantDc, 0.39f / distance);
            var inv = 65536.0f / e.globalScale;
            for (var i = 0; i < field.length; i++) {
                e.quantField[i] = clampQuant(field[i] * inv + 0.5f);
            }
        } else {
            var q = 0.79f / distance;
            e.computeGlobalScaleAndQuant(quantDc, q);
            Arrays.fill(e.quantField, clampQuant(q * 65536.0f / e.globalScale + 0.5f));
        }
        if (gaborish) {
            for (var c = 0; c < 3; c++) {
                gaborishInverse(xyb[c], stride, e.bh * 8);
            }
        }
        Arrays.fill(e.sharpness, 4);
        AcStrategies.choose(e, xyb, field, mask, distance, effort);
        if (effort >= 5) {
            e.computeCfl(xyb, effort < 7);
        }
        e.quantize(xyb, effort);
        return e;
    }

    /**
     * Describes a JPEG component placed in a JPEG XL channel.
     *
     * @param coefficients the quantized coefficients of every block, 64 per block in JPEG natural order
     * @param widthInBlocks the component width in blocks
     * @param heightInBlocks the component height in blocks
     * @param quant the quantization table, in JPEG natural order
     */
    public record JpegChannel(short[] coefficients, int widthInBlocks, int heightInBlocks, int[] quant) {
    }

    /**
     * Holds the quantized coefficients of a JPEG file in the VarDCT layout, with the integer chroma from luma of
     * a three-component file without subsampling, so that the file can be rebuilt exactly.
     *
     * @param width the image width
     * @param height the image height
     * @param jpegUpsampling the chroma subsampling mode of each JPEG XL channel
     * @param ycbcr whether the components are YCbCr, rather than RGB, gray being YCbCr
     * @param channels the component of each JPEG XL channel X, Y, B; gray images have only Y, the others
     * {@code null}
     * @param effort the effort, from 1 to 10
     * @return the encoder
     */
    public static VarDctEncoder fromJpeg(int width, int height, int[] jpegUpsampling, boolean ycbcr,
                                         JpegChannel[] channels, int effort) {
        var e = new VarDctEncoder(width, height, jpegUpsampling);
        e.skipLfSmoothing = true;
        e.globalScale = 65536;
        e.quantLf = 1;
        e.baseCorrelationX = 0;
        e.baseCorrelationB = 0;
        Arrays.fill(e.strategy, TransformType.DCT8);
        Arrays.fill(e.quantField, 1);
        var luma = channels[1];
        var qt = new int[3][64];
        for (var c = 0; c < 3; c++) {
            var source = channels[c] != null ? channels[c] : luma;
            for (var y = 0; y < 8; y++) {
                for (var x = 0; x < 8; x++) {
                    qt[c][8 * x + y] = source.quant()[8 * y + x];
                }
            }
            var dc = source.quant()[0] * 128.0f / 2040.0f;
            e.lfDequant[c] = Float.float16ToFloat(Float.floatToFloat16(dc));
        }
        e.rawQuantTable = qt;
        var gray = channels[0] == null;
        var subsampled = false;
        for (var mode : jpegUpsampling) {
            subsampled |= mode != 0;
        }
        var cfl = !gray && !subsampled;
        if (cfl) {
            e.chooseJpegCfl(channels);
        }
        var dcCounts = new long[3][2048];
        var totalDc = new long[3];
        for (var c : new int[]{1, 0, 2}) {
            var source = channels[c];
            var lw = e.bw >> e.hshift[c];
            var lh = e.bh >> e.vshift[c];
            if (source == null) {
                dcCounts[c][1024] = 1;
                totalDc[c] = 1;
                continue;
            }
            var stride = lw * 8;
            var offset = ycbcr ? 0 : 1024 / source.quant()[0];
            for (var by = 0; by < Math.min(lh, source.heightInBlocks()); by++) {
                for (var bx = 0; bx < Math.min(lw, source.widthInBlocks()); bx++) {
                    var base = (by * source.widthInBlocks() + bx) * 64;
                    var idc = source.coefficients()[base] + offset;
                    dcCounts[c][Math.min(idc + 1024, 2047) & 0x7FF]++;
                    totalDc[c]++;
                    e.lfQuant[c][by * lw + bx] = idc;
                    var factor = 0;
                    if (cfl && c != 1) {
                        var tile = (by / 8) * e.cflWidth + bx / 8;
                        factor = (c == 0 ? e.cmapX : e.cmapB)[tile] * (1 << 11) / 84;
                    }
                    for (var v = 0; v < 8; v++) {
                        for (var u = 0; u < 8; u++) {
                            var n = v * 8 + u;
                            int value = source.coefficients()[base + n];
                            if (cfl && c != 1) {
                                var ratio = (1 << 11) * luma.quant()[n] / source.quant()[n];
                                var qScale = (ratio * factor + (1 << 10)) >> 11;
                                int y = luma.coefficients()[(by * luma.widthInBlocks() + bx) * 64 + n];
                                value -= (y * qScale + (1 << 10)) >> 11;
                            }
                            if (n != 0) {
                                e.coefficients[c][(by * 8 + v) * stride + bx * 8 + u] = value;
                            }
                        }
                    }
                }
            }
        }
        e.jpegContextModel(dcCounts, totalDc);
        return e;
    }

    /**
     * Raises the X and B quantization matrix scales of a frame whose worst-case pixel steps are steep enough for
     * their quantization to show.
     *
     * @param xyb the X, Y and B planes, before the Gaborish inverse
     * @param width the width of the image
     * @param height the height of the image
     * @param stride the row stride of the planes
     */
    private void adjustChromacity(float[][] xyb, int width, int height, int stride) {
        var px = xyb[0];
        var py = xyb[1];
        var pb = xyb[2];
        var dx = 0.0f;
        var db = 0.0f;
        var exposedBlue = 0.0f;
        for (var y = 1; y < height; y++) {
            for (var x = 1; x < width; x++) {
                var i = y * stride + x;
                dx = Math.max(dx, Math.max(Math.abs(px[i] - px[i - 1]), Math.abs(px[i] - px[i - stride])));
                var curY = py[i];
                var curB = pb[i];
                var exposed = curB - curY * 1.2f;
                var diff = curB - curY;
                var prevRow = pb[i - stride];
                var prev = pb[i - 1];
                db = Math.max(db, Math.abs(diff - (prev - py[i - 1])));
                db = Math.max(db, Math.abs(diff - (prevRow - py[i - stride])));
                if (exposed >= 0) {
                    exposed *= Math.abs(curB - prev) + Math.abs(curB - prevRow);
                    exposedBlue = Math.max(exposedBlue, exposed);
                }
            }
        }
        var xLevel = dx >= 0.026f ? 3 : dx >= 0.022f ? 2 : dx >= 0.015f ? 1 : 0;
        xQmScale = Math.max(xQmScale, 2 + xLevel);
        var add = exposedBlue >= 0.13f ? 1 : 0;
        var bLevel = db > 0.38f ? 2 + add : db > 0.33f ? 1 + add : db > 0.28f ? add : 0;
        bQmScale = 2 + bLevel;
    }

    /**
     * Chooses the chroma-from-luma multipliers of every 64x64 tile of an XYB frame, minimizing the X and B
     * residuals of the varblocks the tile fully contains.
     *
     * @param xyb the X, Y and B planes, after the Gaborish inverse
     * @param fast whether the multipliers come from the least-squares solution rather than Newton iterations
     */
    private void computeCfl(float[][] xyb, boolean fast) {
        var stride = bw * 8;
        var scratch = new float[2048];
        var block = new float[3][4096];
        var yx = new float[4096];
        var x = new float[4096];
        var yb = new float[4096];
        var b = new float[4096];
        var scale = globalScale / 65536.0f * 128.0f;
        for (var ty = 0; ty < cflHeight; ty++) {
            for (var tx = 0; tx < cflWidth; tx++) {
                var y0 = ty * 8;
                var x0 = tx * 8;
                var y1 = Math.min(bh, y0 + 8);
                var x1 = Math.min(bw, x0 + 8);
                var num = 0;
                for (var by = y0; by < y1; by++) {
                    for (var bx = x0; bx < x1; bx++) {
                        var type = strategy[by * bw + bx];
                        if (type < 0) {
                            continue;
                        }
                        var w8 = TransformType.width(type);
                        var h8 = TransformType.height(type);
                        if (bx + w8 > x1 || by + h8 > y1) {
                            continue;
                        }
                        for (var c = 0; c < 3; c++) {
                            forward(xyb[c], stride, bx * 8, by * 8, type, block[c], scratch);
                        }
                        var w = w8 * 8;
                        var size = w * h8 * 8;
                        var q = scale * quantField[by * bw + bx];
                        var matrixX = matrices.matrix(type, 0);
                        var matrixB = matrices.matrix(type, 2);
                        for (var k = 0; k < size; k++) {
                            if (k % w < w8 && k / w < h8) {
                                continue;
                            }
                            var qx = q / matrixX[k];
                            var qb = q / matrixB[k];
                            yx[num] = block[1][k] * qx;
                            x[num] = block[0][k] * qx;
                            yb[num] = block[1][k] * qb;
                            b[num] = block[2][k] * qb;
                            num++;
                        }
                    }
                }
                cmapX[ty * cflWidth + tx] = bestMultiplier(yx, x, num, 0.0f, fast);
                cmapB[ty * cflWidth + tx] = bestMultiplier(yb, b, num, 1.0f, fast);
            }
        }
    }

    private static int bestMultiplier(float[] luma, float[] chroma, int num, float base, boolean fast) {
        if (num == 0) {
            return 0;
        }
        var distanceMul = 1e-9f;
        var invColorFactor = 1.0f / 84.0f;
        float x;
        if (fast) {
            var ca = 0.0f;
            var cb = 0.0f;
            for (var i = 0; i < num; i++) {
                var a = invColorFactor * luma[i];
                var bv = base * luma[i] - chroma[i];
                ca += a * a;
                cb += a * bv;
            }
            x = -cb / (ca + num * distanceMul * 0.5f);
        } else {
            var eps = 100.0f;
            x = 0.0f;
            for (var iteration = 0; iteration < 20; iteration++) {
                var d = 2 * distanceMul * num * x;
                var dp = 2 * distanceMul * num * (x + eps);
                var dm = 2 * distanceMul * num * (x - eps);
                for (var i = 0; i < num; i++) {
                    var a = invColorFactor * luma[i];
                    var bv = base * luma[i] - chroma[i];
                    var v = a * x + bv;
                    if (Math.abs(v) >= 100.0f) {
                        continue;
                    }
                    d += cflDerivative(a, v);
                    dp += cflDerivative(a, a * (x + eps) + bv);
                    dm += cflDerivative(a, a * (x - eps) + bv);
                }
                var dd = (dp - dm) / (2 * eps);
                var step = d / (dd + 0.85f);
                x -= Math.clamp(step, -20.0f, 20.0f);
                if (Math.abs(step) < 3e-3f) {
                    break;
                }
            }
        }
        var towardsZero = 2.6f;
        if (x >= towardsZero) {
            x -= towardsZero;
        } else if (x <= -towardsZero) {
            x += towardsZero;
        } else {
            x = 0.0f;
        }
        return (int) Math.clamp(Math.round(x), -128, 127);
    }

    private static float cflDerivative(float a, float v) {
        var d = 2.0f / 3.0f * a * (Math.abs(v) + 1.0f);
        return v < 0 ? -d : d;
    }

    private void chooseJpegCfl(JpegChannel[] channels) {
        var luma = channels[1];
        var scale = 84.0f;
        var offsetZero = 127;
        for (var c : new int[]{0, 2}) {
            var chroma = channels[c];
            var map = c == 0 ? cmapX : cmapB;
            var zeroThresh = scale * 0.5f * 0.9999f;
            var scaled = new int[64];
            for (var n = 0; n < 64; n++) {
                scaled[n] = (1 << 11) * luma.quant()[n] / chroma.quant()[n];
            }
            var counts = new int[257];
            for (var ty = 0; ty < cflHeight; ty++) {
                for (var tx = 0; tx < cflWidth; tx++) {
                    Arrays.fill(counts, 0);
                    var y0 = ty * 8;
                    var x0 = tx * 8;
                    var y1 = Math.min(bh, y0 + 8);
                    var x1 = Math.min(bw, x0 + 8);
                    for (var by = y0; by < Math.min(y1, luma.heightInBlocks()); by++) {
                        for (var bx = x0; bx < Math.min(x1, luma.widthInBlocks()); bx++) {
                            var base = (by * luma.widthInBlocks() + bx) * 64;
                            var cbase = (by * chroma.widthInBlocks() + bx) * 64;
                            for (var n = 1; n < 64; n++) {
                                var m = luma.coefficients()[base + n] * scaled[n] * (1.0f / (1 << 11));
                                var sv = scale * chroma.coefficients()[cbase + n] + offsetZero * m;
                                if (Math.abs(m) > 1e-8f) {
                                    float from;
                                    float to;
                                    if (m > 0) {
                                        from = (sv - zeroThresh) / m;
                                        to = (sv + zeroThresh) / m;
                                    } else {
                                        from = (sv + zeroThresh) / m;
                                        to = (sv - zeroThresh) / m;
                                    }
                                    if (from < 0.0f) {
                                        from = 0.0f;
                                    }
                                    if (to > 255.0f) {
                                        to = 255.0f;
                                    }
                                    if (from <= to) {
                                        counts[(int) Math.ceil(from)]++;
                                        counts[(int) Math.floor(to + 1)]--;
                                    }
                                }
                            }
                        }
                    }
                    var best = 0;
                    var bestSum = 0;
                    var running = 0;
                    for (var i = 0; i < 256; i++) {
                        running += counts[i];
                        if (running > bestSum) {
                            bestSum = running;
                            best = i;
                        }
                    }
                    var offsetSum = 0;
                    for (var i = 0; i <= offsetZero; i++) {
                        offsetSum += counts[i];
                    }
                    map[ty * cflWidth + tx] = bestSum > offsetSum + 1 ? best - offsetZero : 0;
                }
            }
        }
    }

    private void jpegContextModel(long[][] dcCounts, long[] totalDc) {
        var numDc = 1;
        for (var i = 0; i < 3; i++) {
            var bits = totalDc[i] <= 1 ? 0 : 64 - Long.numberOfLeadingZeros(totalDc[i] - 1);
            var numThresholds = Math.clamp((bits - 12) / 2, 0, 2);
            var list = new java.util.ArrayList<Integer>();
            long cumsum = 0;
            var cut = totalDc[i] / (numThresholds + 1);
            for (var j = 0; j < 2048; j++) {
                cumsum += dcCounts[i][j];
                if (cumsum > cut) {
                    list.add(j - 1025);
                    cut = totalDc[i] * (list.size() + 1) / (numThresholds + 1);
                }
            }
            lfThresholds[i] = list.stream().mapToInt(Integer::intValue).toArray();
            numDc *= lfThresholds[i].length + 1;
        }
        var map = new int[3 * 13 * numDc];
        var lbuckets = lfThresholds[1].length + 1;
        for (var i = 0; i < numDc; i++) {
            map[i] = i / lbuckets;
            map[13 * numDc + i] = numDc / lbuckets + (i % lbuckets);
            map[2 * 13 * numDc + i] = map[13 * numDc + i];
        }
        var max = 0;
        for (var v : map) {
            max = Math.max(max, v);
        }
        blockContextMap = map;
        numBlockClusters = max + 1;
        colorFactor = 84;
    }

    /**
     * Returns the default dequantization matrices.
     *
     * @return the matrices
     */
    static DequantMatrices defaultMatrices() {
        return DequantMatrices.read(new JxlBitReader(new byte[]{1, 0, 0, 0, 0, 0, 0, 0}, 0, 8), 8, 1, null,
                MediaLimits.defaults());
    }

    static float initialQuantDc(float distance) {
        var mul = 0.3f;
        var dcTarget = Math.max(0.5f * distance,
                Math.min(distance, mul * (float) StrictMath.pow((1.0f / mul) * distance, 0.83f)));
        return Math.min(1.095924047623553f / dcTarget, 50.0f);
    }

    private void computeGlobalScaleAndQuant(float quantDc, float quantMedian) {
        var scale = 65536.0f * quantMedian / 5;
        if (scale < 1) {
            scale = 1;
        }
        if (scale > 1 << 15) {
            scale = 1 << 15;
        }
        var gs = (int) scale;
        var scaledQuantDc = (int) (quantDc * 4096 * 1.6);
        if (gs > scaledQuantDc) {
            gs = Math.max(scaledQuantDc, 1);
        }
        globalScale = gs;
        var fval = Math.min(1 << 16, quantDc * (65536.0f / gs) + 0.5f);
        quantLf = (int) fval;
    }

    static int clampQuant(float value) {
        return (int) Math.max(1.0f, Math.min(value, 256));
    }

    float invGlobalScale() {
        return 65536.0f / globalScale;
    }

    int bw() {
        return bw;
    }

    int bh() {
        return bh;
    }

    DequantMatrices matrices() {
        return matrices;
    }

    private static void gaborishInverse(float[] plane, int w, int h) {
        var sum = 1.0 + 4 * (GABORISH[0] + GABORISH[1] + GABORISH[2] + GABORISH[4] + 2 * GABORISH[3]);
        var normalize = (float) (1.0 / Math.max(sum, 1e-5));
        var r = normalize * GABORISH[0];
        var bigR = normalize * GABORISH[2];
        var d = normalize * GABORISH[1];
        var bigD = normalize * GABORISH[4];
        var l = normalize * GABORISH[3];
        var src = plane.clone();
        var bands = ParallelBands.bands(h, (long) w * h * 25);
        ParallelBands.run(bands, band -> {
            for (var y = ParallelBands.start(band, bands, h); y < ParallelBands.start(band + 1, bands, h); y++) {
                var ym2 = mirror(y - 2, h) * w;
                var ym1 = mirror(y - 1, h) * w;
                var y0 = y * w;
                var yp1 = mirror(y + 1, h) * w;
                var yp2 = mirror(y + 2, h) * w;
                for (var x = 0; x < w; x++) {
                    var xm2 = mirror(x - 2, w);
                    var xm1 = mirror(x - 1, w);
                    var xp1 = mirror(x + 1, w);
                    var xp2 = mirror(x + 2, w);
                    var v = normalize * src[y0 + x]
                            + r * (src[ym1 + x] + src[yp1 + x] + src[y0 + xm1] + src[y0 + xp1])
                            + bigR * (src[ym2 + x] + src[yp2 + x] + src[y0 + xm2] + src[y0 + xp2])
                            + d * (src[ym1 + xm1] + src[ym1 + xp1] + src[yp1 + xm1] + src[yp1 + xp1])
                            + bigD * (src[ym2 + xm2] + src[ym2 + xp2] + src[yp2 + xm2] + src[yp2 + xp2])
                            + l * (src[ym2 + xm1] + src[ym2 + xp1] + src[yp2 + xm1] + src[yp2 + xp1]
                            + src[ym1 + xm2] + src[ym1 + xp2] + src[yp1 + xm2] + src[yp1 + xp2]);
                    plane[y0 + x] = v;
                }
            }
        });
    }

    private static int mirror(int x, int size) {
        while (x < 0 || x >= size) {
            if (x < 0) {
                x = -x - 1;
            } else {
                x = 2 * size - 1 - x;
            }
        }
        return x;
    }

    /**
     * Computes the forward transform of a varblock in the decoder's coefficient layout.
     *
     * @param plane the samples
     * @param stride the plane stride
     * @param x0 the left sample of the varblock
     * @param y0 the top sample
     * @param type the transform type
     * @param out receives the coefficients, {@code width * 8} by {@code height * 8}
     * @param scratch scratch space of at least 1024 floats; the 8x8 transforms keep their temporaries from index
     * {@value #SCRATCH_TEMP} on
     */
    static void forward(float[] plane, int stride, int x0, int y0, int type, float[] out, float[] scratch) {
        var w = TransformType.width(type) * 8;
        var h = TransformType.height(type) * 8;
        for (var y = 0; y < h; y++) {
            System.arraycopy(plane, (y0 + y) * stride + x0, out, y * w, w);
        }
        switch (type) {
            case TransformType.DCT2 -> {
                auxDct2(out, 8, scratch);
                auxDct2(out, 4, scratch);
                auxDct2(out, 2, scratch);
            }
            case TransformType.DCT4 -> dct4(out, scratch);
            case TransformType.HORNUSS -> hornuss(out, scratch);
            case TransformType.DCT4X8 -> dct4x8(out, false, scratch);
            case TransformType.DCT8X4 -> dct4x8(out, true, scratch);
            case TransformType.AFV0, TransformType.AFV1, TransformType.AFV2, TransformType.AFV3 ->
                    afv(out, type - TransformType.AFV0, scratch);
            default -> Dct.dct2d(out, 0, w, w, h, scratch, false);
        }
    }

    /**
     * Inverts one level of the decoder's 2x2 Haar step: each 2x2 group of the top-left {@code size} square
     * becomes its average and three differences, spread into the four quadrants.
     */
    private static void auxDct2(float[] buf, int size, float[] scratch) {
        var half = size / 2;
        var t = SCRATCH_TEMP;
        for (var y = 0; y < half; y++) {
            for (var x = 0; x < half; x++) {
                var a = buf[(2 * y) * 8 + 2 * x];
                var b = buf[(2 * y) * 8 + 2 * x + 1];
                var c = buf[(2 * y + 1) * 8 + 2 * x];
                var d = buf[(2 * y + 1) * 8 + 2 * x + 1];
                scratch[t + y * size + x] = (a + b + c + d) * 0.25f;
                scratch[t + y * size + x + half] = (a + b - c - d) * 0.25f;
                scratch[t + (y + half) * size + x] = (a - b + c - d) * 0.25f;
                scratch[t + (y + half) * size + x + half] = (a - b - c + d) * 0.25f;
            }
        }
        for (var y = 0; y < size; y++) {
            System.arraycopy(scratch, t + y * size, buf, y * 8, size);
        }
    }

    /**
     * Inverts the decoder's 4x8 transform: each half of the block (the top and bottom four rows, or the left and
     * right four columns when transposed) takes a 4x8 DCT, the halves' rows interleave, and their two DC
     * coefficients become their average and half difference.
     */
    private static void dct4x8(float[] buf, boolean transposed, float[] scratch) {
        var t = SCRATCH_TEMP;
        for (var y = 0; y < 8; y++) {
            for (var x = 0; x < 8; x++) {
                scratch[t + y * 8 + x] = transposed ? buf[x * 8 + y] : buf[y * 8 + x];
            }
        }
        Dct.dct2d(scratch, t, 8, 8, 4, scratch, false);
        Dct.dct2d(scratch, t + 32, 8, 8, 4, scratch, false);
        for (var idx = 0; idx < 2; idx++) {
            for (var iy = 0; iy < 4; iy++) {
                System.arraycopy(scratch, t + idx * 32 + iy * 8, buf, (iy * 2 + idx) * 8, 8);
            }
        }
        var d0 = buf[0];
        var d1 = buf[8];
        buf[0] = (d0 + d1) * 0.5f;
        buf[8] = (d0 - d1) * 0.5f;
    }

    /**
     * Inverts the decoder's AFV transform: the 4x4 corner the variant names is projected on the AFV basis, the
     * 4x4 next to it takes a 4x4 DCT and the other half of the block a 4x8 DCT; the three DC coefficients are
     * combined as the decoder splits them.
     */
    private static void afv(float[] buf, int variant, float[] scratch) {
        var flipX = variant % 2;
        var flipY = variant / 2;
        var samples = SCRATCH_TEMP;
        var corner = samples + 16;
        var s4 = corner + 16;
        var s8 = s4 + 16;
        for (var iy = 0; iy < 4; iy++) {
            var afvY = flipY == 0 ? iy : 3 - iy;
            for (var ix = 0; ix < 4; ix++) {
                var afvX = flipX == 0 ? ix : 3 - ix;
                scratch[samples + afvY * 4 + afvX] = buf[(flipY * 4 + iy) * 8 + flipX * 4 + ix];
            }
        }
        for (var k = 0; k < 16; k++) {
            var basis = Varblocks.AFV_BASIS[k];
            var sum = 0.0f;
            for (var i = 0; i < 16; i++) {
                sum = Math.fma(scratch[samples + i], basis[i], sum);
            }
            scratch[corner + k] = sum;
        }
        for (var iy = 0; iy < 4; iy++) {
            System.arraycopy(buf, (flipY * 4 + iy) * 8 + (1 - flipX) * 4, scratch, s4 + iy * 4, 4);
        }
        Dct.dct2d(scratch, s4, 4, 4, 4, scratch, false);
        System.arraycopy(buf, (1 - flipY) * 32, scratch, s8, 32);
        Dct.dct2d(scratch, s8, 8, 8, 4, scratch, false);
        for (var iy = 0; iy < 4; iy++) {
            for (var ix = 0; ix < 4; ix++) {
                buf[2 * iy * 8 + 2 * ix] = scratch[corner + iy * 4 + ix];
                buf[2 * iy * 8 + 2 * ix + 1] = scratch[s4 + ix * 4 + iy];
            }
        }
        for (var iy = 0; iy < 4; iy++) {
            System.arraycopy(scratch, s8 + iy * 8, buf, (2 * iy + 1) * 8, 8);
        }
        var a = scratch[corner] * 0.25f;
        var b = scratch[s4];
        var sum = (a + b) * 0.5f;
        buf[0] = (sum + scratch[s8]) * 0.5f;
        buf[8] = (sum - scratch[s8]) * 0.5f;
        buf[1] = (a - b) * 0.5f;
    }

    private static void dct4(float[] buf, float[] scratch) {
        var sub = SCRATCH_TEMP + 64;
        var out = SCRATCH_TEMP + 80;
        for (var qy = 0; qy < 2; qy++) {
            for (var qx = 0; qx < 2; qx++) {
                for (var iy = 0; iy < 4; iy++) {
                    for (var ix = 0; ix < 4; ix++) {
                        scratch[sub + iy * 4 + ix] = buf[(qy * 4 + iy) * 8 + qx * 4 + ix];
                    }
                }
                Dct.dct2d(scratch, sub, 4, 4, 4, scratch, false);
                for (var r = 0; r < 4; r++) {
                    for (var c = 0; c < 4; c++) {
                        scratch[out + (qy + 2 * c) * 8 + qx + 2 * r] = scratch[sub + r * 4 + c];
                    }
                }
            }
        }
        System.arraycopy(scratch, out, buf, 0, 64);
        auxDct2(buf, 2, scratch);
    }

    private static void hornuss(float[] buf, float[] scratch) {
        var out = SCRATCH_TEMP + 64;
        var s = SCRATCH_TEMP + 128;
        for (var qy = 0; qy < 2; qy++) {
            for (var qx = 0; qx < 2; qx++) {
                var avg = buf[(qy * 4 + 1) * 8 + qx * 4 + 1];
                var sum = 0.0f;
                for (var iy = 0; iy < 4; iy++) {
                    for (var ix = 0; ix < 4; ix++) {
                        var i = iy * 4 + ix;
                        if (i == 0 || i == 5) {
                            continue;
                        }
                        scratch[s + i] = buf[(qy * 4 + iy) * 8 + qx * 4 + ix] - avg;
                        sum += scratch[s + i];
                    }
                }
                scratch[s + 5] = buf[(qy * 4) * 8 + qx * 4] - avg;
                sum += scratch[s + 5];
                scratch[s] = avg + sum / 16.0f;
                for (var iy = 0; iy < 4; iy++) {
                    for (var ix = 0; ix < 4; ix++) {
                        scratch[out + (qy + 2 * iy) * 8 + qx + 2 * ix] = scratch[s + iy * 4 + ix];
                    }
                }
            }
        }
        System.arraycopy(scratch, out, buf, 0, 64);
        auxDct2(buf, 2, scratch);
    }

    /**
     * Converts the lowest-frequency coefficients of a varblock into its LF samples, one per 8x8 block.
     *
     * @param coefficients the varblock coefficients
     * @param type the transform type
     * @param lf receives the LF samples, row-major by blocks
     * @param scratch scratch space
     */
    static void lfFromLowest(float[] coefficients, int type, float[] lf, float[] scratch) {
        var bwb = TransformType.width(type);
        var bhb = TransformType.height(type);
        var stride = bwb * 8;
        if (bwb == 1 && bhb == 1) {
            lf[0] = coefficients[0];
            return;
        }
        var logbw = Integer.numberOfTrailingZeros(bwb);
        var logbh = Integer.numberOfTrailingZeros(bhb);
        for (var y = 0; y < bhb; y++) {
            for (var x = 0; x < bwb; x++) {
                lf[y * bwb + x] = coefficients[y * stride + x] * Dct.scaleF(y, 5 - logbh) * Dct.scaleF(x, 5 - logbw);
            }
        }
        Dct.dct2d(lf, 0, bwb, bwb, bhb, scratch, true);
    }

    private void quantize(float[][] xyb, int effort) {
        var lf = new float[3][bw * bh];
        var hare = effort >= 5;
        var xMul = (float) StrictMath.pow(1.25, xQmScale - 2);
        var bMul = (float) StrictMath.pow(1.25, bQmScale - 2);
        // Varblocks never overlap, so the bands of block rows write disjoint coefficients.
        var bands = ParallelBands.bands(bh, (long) bw * bh * 64 * 3 * 16);
        ParallelBands.run(bands, band -> quantizeRows(xyb, lf, ParallelBands.start(band, bands, bh),
                ParallelBands.start(band + 1, bands, bh), hare, xMul, bMul, new QuantScratch()));
        quantizeLf(lf);
    }

    /**
     * The working buffers of one thread quantizing varblocks.
     */
    private static final class QuantScratch {
        final float[] scratch = new float[2048];

        final float[][] coef = new float[3][64 * 64];

        final float[] lfBuf = new float[1024];

        final int[] quantized = new int[64 * 64];

        final float[] thresY = new float[4];

        final float[] thres = new float[4];

        final int[] qv = new int[1];

        final float[] hfNonZeros = new float[4];

        final float[] hfMaxError = new float[4];
    }

    private void quantizeRows(float[][] xyb, float[][] lf, int rowStart, int rowEnd, boolean hare, float xMul,
                              float bMul, QuantScratch q) {
        var stride = bw * 8;
        var scratch = q.scratch;
        var coef = q.coef;
        var lfBuf = q.lfBuf;
        var quantized = q.quantized;
        var thresY = q.thresY;
        var thres = q.thres;
        var qv = q.qv;
        for (var by = rowStart; by < rowEnd; by++) {
            for (var bx = 0; bx < bw; bx++) {
                var type = strategy[by * bw + bx];
                if (type < 0) {
                    continue;
                }
                var w8 = TransformType.width(type);
                var h8 = TransformType.height(type);
                var size = w8 * h8 * 64;
                for (var c = 0; c < 3; c++) {
                    forward(xyb[c], stride, bx * 8, by * 8, type, coef[c], scratch);
                }
                for (var c = 0; c < 3; c++) {
                    lfFromLowest(coef[c], type, lfBuf, scratch);
                    storeLf(lf[c], bx, by, w8, h8, lfBuf);
                }
                var quant = quantField[by * bw + bx];
                System.arraycopy(Y_THRESHOLDS, 0, thresY, 0, 4);
                if (hare) {
                    var maxQuant = 0;
                    var orig = quant;
                    for (var c : CHANNEL_ORDER) {
                        System.arraycopy(ADJUST_THRESHOLDS, 0, thres, 0, 4);
                        qv[0] = orig;
                        adjustQuantBlock(c, c == 0 ? xMul : c == 2 ? bMul : 1.0f, type, w8, h8, thres, coef[c], qv,
                                q);
                        if (c == 1) {
                            System.arraycopy(thres, 0, thresY, 0, 4);
                        }
                        maxQuant = Math.max(qv[0], maxQuant);
                    }
                    quant = maxQuant;
                }
                quantizeBlock(1, 1.0f, type, w8, h8, thresY, coef[1], quant, quantized);
                var matrixY = matrices.matrix(type, 1);
                var invQac = invGlobalScale() / quant;
                for (var k = 0; k < size; k++) {
                    coef[1][k] = biased(quantized[k], 1) * matrixY[k] * invQac;
                }
                storeCoefficients(1, bx, by, w8, h8, quantized);
                var tile = (by / 8) * cflWidth + bx / 8;
                var kx = cmapX[tile] / 84.0f;
                var kb = 1.0f + cmapB[tile] / 84.0f;
                for (var k = 0; k < size; k++) {
                    coef[0][k] -= kx * coef[1][k];
                    coef[2][k] -= kb * coef[1][k];
                }
                for (var c = 0; c < 3; c += 2) {
                    System.arraycopy(CHROMA_THRESHOLDS, 0, thres, 0, 4);
                    quantizeBlock(c, c == 0 ? xMul : bMul, type, w8, h8, thres, coef[c], quant, quantized);
                    storeCoefficients(c, bx, by, w8, h8, quantized);
                }
                quantField[by * bw + bx] = quant;
            }
        }
    }

    private void storeLf(float[] lf, int bx, int by, int w8, int h8, float[] values) {
        for (var y = 0; y < h8; y++) {
            for (var x = 0; x < w8; x++) {
                lf[(by + y) * bw + bx + x] = values[y * w8 + x];
            }
        }
    }

    private void storeCoefficients(int c, int bx, int by, int w8, int h8, int[] q) {
        var w = w8 * 8;
        var stride = bw * 8;
        var target = coefficients[c];
        for (var y = 0; y < h8 * 8; y++) {
            System.arraycopy(q, y * w, target, (by * 8 + y) * stride + bx * 8, w);
        }
    }

    private static float biased(int q, int c) {
        if (q == 0) {
            return 0;
        }
        if (q == 1 || q == -1) {
            return q * QUANT_BIAS[c];
        }
        return q - QUANT_BIAS_NUMERATOR / q;
    }

    private void quantizeBlock(int c, float qmMultiplier, int type, int xsize, int ysize, float[] thresholds,
                               float[] block, int quant, int[] out) {
        var matrix = matrices.matrix(type, c);
        var qac = globalScale / 65536.0f * quant;
        if (c != 1 && xsize * ysize >= 4) {
            for (var i = 0; i < 4; i++) {
                thresholds[i] = Math.max(0.5f, thresholds[i] - 0.00744f * xsize * ysize);
            }
        }
        var w = xsize * 8;
        var h = ysize * 8;
        var mul = qac * qmMultiplier;
        for (var y = 0; y < h; y++) {
            var yfix = y >= h / 2 ? 2 : 0;
            for (var x = 0; x < w; x++) {
                var threshold = thresholds[yfix + (x >= w / 2 ? 1 : 0)];
                var k = y * w + x;
                var val = block[k] * (mul / matrix[k]);
                out[k] = (x < xsize && y < ysize) || Math.abs(val) < threshold ? 0 : Math.round(val);
            }
        }
    }

    private void adjustQuantBlock(int c, float qmMultiplier, int type, int xsize, int ysize, float[] thresholds,
                                  float[] block, int[] quantRef, QuantScratch scratch) {
        if (type == TransformType.HORNUSS || type == TransformType.DCT2 || type == TransformType.DCT4
                || type == TransformType.DCT4X8 || type == TransformType.DCT8X4
                || (type >= TransformType.AFV0 && type <= TransformType.AFV3)) {
            return;
        }
        var matrix = matrices.matrix(type, c);
        var quant = quantRef[0];
        var qac = globalScale / 65536.0f * quant;
        if (xsize > 1 || ysize > 1) {
            for (var i = 0; i < 4; i++) {
                thresholds[i] = Math.max(0.54f, thresholds[i] - Math.clamp(0.003f * xsize * ysize, 0.0f, 0.08f));
            }
        }
        var sumHighest = 0.0f;
        var sumError = 0.0f;
        var sumVals = 0.0f;
        var hfNonZeros = scratch.hfNonZeros;
        var hfMaxError = scratch.hfMaxError;
        Arrays.fill(hfNonZeros, 0.0f);
        Arrays.fill(hfMaxError, 0.0f);
        var w = xsize * 8;
        var h = ysize * 8;
        for (var y = 0; y < h; y++) {
            for (var x = 0; x < w; x++) {
                if (x < xsize && y < ysize) {
                    continue;
                }
                var pos = y * w + x;
                var hfix = (y >= h / 2 ? 2 : 0) + (x >= w / 2 ? 1 : 0);
                var val = block[pos] * (qac * qmMultiplier / matrix[pos]);
                var v = Math.abs(val) < thresholds[hfix] ? 0.0f : (float) Math.rint(val);
                var error = Math.abs(val - v);
                sumError += error;
                sumVals += Math.abs(v);
                if (c == 1 && v == 0 && hfMaxError[hfix] < error) {
                    hfMaxError[hfix] = error;
                }
                if (v != 0.0f) {
                    hfNonZeros[hfix] += Math.abs(v);
                    var inCorner = y >= 7 * ysize && x >= 7 * xsize;
                    var onBorder = y == h - 1 || x == w - 1;
                    var inLargerCorner = x >= 4 * xsize && y >= 4 * ysize;
                    if (inCorner || (onBorder && inLargerCorner)) {
                        sumHighest += Math.abs(val);
                    }
                }
            }
        }
        if (c == 1 && sumVals * 8 < xsize * ysize) {
            var orig = quant;
            var newQuant = quant;
            for (var i = 1; i < 4; i++) {
                if (hfNonZeros[i] == 0.0f && hfMaxError[i] > 0.46f) {
                    newQuant = orig + 1;
                    break;
                }
            }
            quant = newQuant;
            if (hfNonZeros[3] == 0.0f && hfMaxError[3] > 0.46f) {
                thresholds[3] = 0.9999f * hfMaxError[3] * newQuant / orig;
            } else if ((hfNonZeros[1] == 0.0f && hfMaxError[1] > 0.46f)
                    || (hfNonZeros[2] == 0.0f && hfMaxError[2] > 0.46f)) {
                thresholds[1] = 0.9999f * Math.max(hfMaxError[1], hfMaxError[2]) * newQuant / orig;
                thresholds[2] = thresholds[1];
            } else if (hfNonZeros[0] == 0.0f && hfMaxError[0] > 0.46f) {
                thresholds[0] = 0.9999f * hfMaxError[0] * newQuant / orig;
            }
        }
        var all = hfNonZeros[0] + hfNonZeros[1] + hfNonZeros[2] + hfNonZeros[3] + 1;
        var mulHighest = MUL_HIGHEST[c];
        if (mulHighest * sumHighest >= all) {
            quant += (int) (mulHighest * sumHighest / all);
            if (quant >= 256) {
                quant = 255;
            }
        }
        if (type == TransformType.DCT8 && hfNonZeros[0] + hfNonZeros[1] + hfNonZeros[2] + hfNonZeros[3] < 11) {
            quant += 1;
            if (quant >= 256) {
                quant = 255;
            }
        }
        if (type == TransformType.DCT16 || type == TransformType.DCT32 || type == TransformType.DCT32X16
                || type == TransformType.DCT16X32 || type >= TransformType.DCT64) {
            var mul1 = LARGE_ERROR_MUL;
            var mul2 = LARGE_VALUE_MUL;
            var normalizer = 2.2942708343284721;
            var err = sumError * normalizer;
            var vals = sumVals * normalizer;
            var ix = 3;
            if (type == TransformType.DCT32X16 || type == TransformType.DCT16X32) {
                ix = 1;
            } else if (type == TransformType.DCT16) {
                ix = 0;
            } else if (type == TransformType.DCT32) {
                ix = 2;
            }
            var limit = mul1[ix][c] * xsize * ysize * 64 + mul2[ix][c] * vals;
            var step = (int) Math.clamp(err / limit, 0, 2);
            if (err > limit) {
                quant += step;
                if (quant >= 256) {
                    quant = 255;
                }
            }
        }
        var div = xsize * ysize;
        var activity = ((int) hfNonZeros[0] + div / 2) / div;
        var origLimit = Math.max(4, quant / 2);
        for (var i = 1; i < 4; i++) {
            activity = Math.min(activity, ((int) hfNonZeros[i] + div / 2) / div);
        }
        activity = Math.min(activity, 15);
        var qp = quant - activity;
        if (c == 1) {
            for (var i = 1; i < 4; i++) {
                thresholds[i] += 0.01f * activity;
            }
        }
        quantRef[0] = Math.max(qp, origLimit);
    }

    private void quantizeLf(float[][] lf) {
        var scaleInv = (double) globalScale * quantLf;
        var steps = new float[3];
        for (var c = 0; c < 3; c++) {
            steps[c] = (float) (lfDequant[c] * 512.0 / scaleInv);
        }
        var y = lfQuant[1];
        for (var i = 0; i < y.length; i++) {
            y[i] = Math.round(lf[1][i] / steps[1]);
        }
        for (var c : new int[]{0, 2}) {
            var cfl = c == 0 ? 0.0f : 1.0f;
            var q = lfQuant[c];
            for (var i = 0; i < q.length; i++) {
                q[i] = Math.round((lf[c][i] - y[i] * steps[1] * cfl) / steps[c]);
            }
        }
    }

    /**
     * Creates the modular streams of the LF coefficients, the HF metadata and, for a JPEG, the quantization table.
     *
     * @return the streams, in stream index order
     */
    public List<ModularEncoder.Stream> modularStreams() {
        var all = new ArrayList<ModularEncoder.Stream>();
        for (var lfg = 0; lfg < numLf; lfg++) {
            var rect = lfRect(lfg);
            var channels = new ArrayList<ModularChannel>(3);
            for (var c : CHANNEL_ORDER) {
                var spec = com.github.auties00.smalt.image.jxl.internal.frame.LfGlobal.jpegChannel(
                        (rect[2] + 7) / 8, (rect[3] + 7) / 8, jpegUpsampling, c);
                var x0 = rect[0] / 8 >> hshift[c];
                var y0 = rect[1] / 8 >> vshift[c];
                var stride = bw >> hshift[c];
                spec.data = new int[spec.width * spec.height];
                for (var y = 0; y < spec.height; y++) {
                    System.arraycopy(lfQuant[c], (y0 + y) * stride + x0, spec.data, y * spec.width, spec.width);
                }
                channels.add(spec);
            }
            var stream = new ModularEncoder.Stream(1 + lfg, channels);
            lfStreams.add(stream);
        }
        all.addAll(lfStreams);
        for (var lfg = 0; lfg < numLf; lfg++) {
            metaStreams.add(metaStream(lfg));
        }
        all.addAll(metaStreams);
        if (rawQuantTable != null) {
            var channels = new ArrayList<ModularChannel>(3);
            for (var c = 0; c < 3; c++) {
                var ch = new ModularChannel(8, 8, 0, 0);
                ch.data = rawQuantTable[c].clone();
                channels.add(ch);
            }
            quantTableStream = new ModularEncoder.Stream(1 + 3 * numLf, channels);
            all.add(quantTableStream);
        }
        return all;
    }

    private ModularEncoder.Stream metaStream(int lfg) {
        var rect = lfRect(lfg);
        var bx0 = rect[0] / 8;
        var by0 = rect[1] / 8;
        var w = (rect[2] + 7) / 8;
        var h = (rect[3] + 7) / 8;
        var hUp = false;
        var vUp = false;
        for (var mode : jpegUpsampling) {
            hUp |= mode == 1 || mode == 2;
            vUp |= mode == 1 || mode == 3;
        }
        if (hUp) {
            w = (w + 1) / 2 * 2;
        }
        if (vUp) {
            h = (h + 1) / 2 * 2;
        }
        var cw = (rect[2] + 63) / 64;
        var ch = (rect[3] + 63) / 64;
        var cx = new ModularChannel(cw, ch, 0, 0);
        var cb = new ModularChannel(cw, ch, 0, 0);
        cx.data = new int[cw * ch];
        cb.data = new int[cw * ch];
        for (var y = 0; y < ch; y++) {
            for (var x = 0; x < cw; x++) {
                cx.data[y * cw + x] = cmapX[(rect[1] / 64 + y) * cflWidth + rect[0] / 64 + x];
                cb.data[y * cw + x] = cmapB[(rect[1] / 64 + y) * cflWidth + rect[0] / 64 + x];
            }
        }
        var count = 0;
        for (var y = 0; y < h; y++) {
            for (var x = 0; x < w; x++) {
                if (strategy[(by0 + y) * bw + bx0 + x] >= 0) {
                    count++;
                }
            }
        }
        var info = new ModularChannel(count, 2, 0, 0);
        info.data = new int[count * 2];
        var sharp = new ModularChannel(w, h, 0, 0);
        sharp.data = new int[w * h];
        var n = 0;
        for (var y = 0; y < h; y++) {
            for (var x = 0; x < w; x++) {
                var index = (by0 + y) * bw + bx0 + x;
                sharp.data[y * w + x] = sharpness[index];
                if (strategy[index] >= 0) {
                    info.data[n] = strategy[index];
                    info.data[count + n] = quantField[index] - 1;
                    n++;
                }
            }
        }
        var stream = new ModularEncoder.Stream(1 + 2 * numLf + lfg, List.of(cx, cb, info, sharp));
        stream.codeOnly(4);
        return stream;
    }

    private int[] lfRect(int lfg) {
        var x0 = (lfg % lfGroupsX) * 2048;
        var y0 = (lfg / lfGroupsX) * 2048;
        return new int[]{x0, y0, Math.min(2048, width - x0), Math.min(2048, height - y0)};
    }

    /**
     * Returns the tree settings of the VarDCT streams at an effort.
     *
     * @param effort the encoder effort, from 1 to 10
     * @param jpeg whether the coefficients come from a JPEG file
     * @return the settings of the LF, HF metadata and quantization table ranges
     */
    public List<ModularEncoder.TreeSettings> treeSettings(int effort, boolean jpeg) {
        var list = new ArrayList<ModularEncoder.TreeSettings>();
        if (effort >= 8) {
            var predictors = effort >= 9 ? allPredictors() : new int[]{6, 5};
            list.add(new ModularEncoder.TreeSettings(1, ModularEncoder.TREE_LEARN, predictors,
                    new int[]{0, 15, 9, 10, 11, 12, 13, 14, 2, 3, 4, 5, 6, 7, 8}, effort >= 9 ? 256 : 96,
                    82 + 14 * (10 - effort), 0.5f, 1.0f));
        } else {
            list.add(ModularEncoder.TreeSettings.fixed(1, ModularEncoder.TREE_WP_FIXED));
        }
        list.add(ModularEncoder.TreeSettings.fixed(1 + numLf, ModularEncoder.TREE_ZERO));
        if (jpeg) {
            list.add(ModularEncoder.TreeSettings.fixed(1 + 2 * numLf, ModularEncoder.TREE_ZERO));
        } else if (effort <= 3) {
            list.add(ModularEncoder.TreeSettings.fixed(1 + 2 * numLf, ModularEncoder.TREE_WEST));
        } else {
            list.add(ModularEncoder.TreeSettings.fixed(1 + 2 * numLf, ModularEncoder.TREE_AC_META));
        }
        list.add(new ModularEncoder.TreeSettings(1 + 3 * numLf, ModularEncoder.TREE_LEARN, new int[]{5},
                new int[]{0, 2, 3}, 16, 96, 1.0f, 1.0f));
        return list;
    }

    private static int[] allPredictors() {
        var p = new int[14];
        for (var i = 0; i < 14; i++) {
            p[i] = i;
        }
        p[0] = 6;
        p[6] = 0;
        p[1] = 5;
        p[5] = 1;
        return p;
    }

    /**
     * Tokenizes the HF coefficients of every group and builds their distributions.
     *
     * @param params the entropy coding parameters
     */
    public void tokenizeHf(EntropyEncoder.Params params) {
        var tokens = new Tokens[numGroups];
        ParallelBands.run(numGroups, g -> {
            var group = new Tokens(4096);
            tokenizeGroup(g, group);
            tokens[g] = group;
        });
        hfTokens.addAll(List.of(tokens));
        hfCode = EntropyEncoder.build(hfTokens, 495 * numBlockClusters, params);
    }

    private void tokenizeGroup(int group, Tokens tokens) {
        var lfIdxMul = (lfThresholds[0].length + 1) * (lfThresholds[1].length + 1) * (lfThresholds[2].length + 1);
        var left = (group % groupsX) * 32;
        var top = (group / groupsX) * 32;
        var width = Math.min(bw - left, 32);
        var height = Math.min(bh - top, 32);
        var nonZeroRows = new int[3][];
        for (var c = 0; c < 3; c++) {
            nonZeroRows[c] = new int[(width + (1 << hshift[c]) - 1) >> hshift[c]];
        }
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                var type = strategy[(top + y) * bw + left + x];
                if (type < 0) {
                    continue;
                }
                var w8 = TransformType.width(type);
                var h8 = TransformType.height(type);
                var numBlocks = w8 * h8;
                var numBlocksLog = Integer.numberOfTrailingZeros(numBlocks);
                var orderId = TransformType.order(type);
                var transpose = TransformType.needsTranspose(type);
                var lfIdx = 0;
                for (var c : LF_CONTEXT_ORDER) {
                    var thresholds = lfThresholds[c];
                    lfIdx *= thresholds.length + 1;
                    var q = lfQuant[c][((top + y) >> vshift[c]) * (bw >> hshift[c]) + ((left + x) >> hshift[c])];
                    for (var threshold : thresholds) {
                        if (q > threshold) {
                            lfIdx++;
                        }
                    }
                }
                for (var ci = 0; ci < 3; ci++) {
                    var c = CHANNEL_ORDER[ci];
                    var hs = hshift[c];
                    var vs = vshift[c];
                    var sx = x >> hs;
                    var sy = y >> vs;
                    if (hs != 0 || vs != 0) {
                        if ((sx << hs) != x || (sy << vs) != y) {
                            continue;
                        }
                        if (strategy[(top + sy) * bw + left + sx] < 0) {
                            continue;
                        }
                    }
                    var blockCtx = blockContextMap[((ci * 13 + orderId) * (qfThresholds.length + 1)) * lfIdxMul
                            + lfIdx];
                    var row = nonZeroRows[c];
                    int predicted;
                    if (sy == 0) {
                        predicted = sx == 0 ? 32 : row[sx - 1];
                    } else if (sx == 0) {
                        predicted = row[sx];
                    } else {
                        predicted = (row[sx] + row[sx - 1] + 1) >> 1;
                    }
                    var nzIdx = predicted >= 8 ? 4 + predicted / 2 : predicted;
                    var order = HfPass.natural(orderId);
                    var stride = (bw >> hs) * 8;
                    var target = coefficients[c];
                    var gx = ((left >> hs) + sx) * 8;
                    var gy = ((top >> vs) + sy) * 8;
                    var nonZeros = 0;
                    for (var k = numBlocks; k < order.length; k++) {
                        if (coefficientAt(target, stride, gx, gy, order[k], transpose) != 0) {
                            nonZeros++;
                        }
                    }
                    tokens.add(blockCtx + nzIdx * numBlockClusters, nonZeros);
                    var nzValue = (nonZeros + numBlocks - 1) >> numBlocksLog;
                    for (var dx = 0; dx < w8; dx++) {
                        row[sx + dx] = nzValue;
                    }
                    if (nonZeros == 0) {
                        continue;
                    }
                    var prevNonZero = nonZeros <= numBlocks * 4 ? 1 : 0;
                    var ctxBase = blockCtx * 458 + 37 * numBlockClusters;
                    for (var k = numBlocks; k < order.length; k++) {
                        var i = k - numBlocks;
                        var ctx = (NONZERO_CONTEXT[(nonZeros - 1) >> numBlocksLog] + FREQ_CONTEXT[i >> numBlocksLog])
                                * 2 + prevNonZero;
                        var value = coefficientAt(target, stride, gx, gy, order[k], transpose);
                        tokens.addSigned(ctxBase + ctx, value);
                        if (value == 0) {
                            prevNonZero = 0;
                            continue;
                        }
                        prevNonZero = 1;
                        nonZeros--;
                        if (nonZeros == 0) {
                            break;
                        }
                    }
                }
            }
        }
    }

    private static int coefficientAt(int[] plane, int stride, int gx, int gy, int position, boolean transpose) {
        var dx = position & 0xFFFF;
        var dy = position >>> 16;
        if (transpose) {
            var t = dx;
            dx = dy;
            dy = t;
        }
        return plane[(gy + dy) * stride + gx + dx];
    }

    /**
     * Writes the VarDCT fields of the LF global section: the LF dequantization weights must already be written
     * by the caller; this writes the quantizer, the block context map and the color correlation.
     *
     * @param w the output
     */
    public void writeLfGlobalVarDct(JxlBitWriter w) {
        w.writeU32(globalScale, 1, 11, 2049, 11, 4097, 12, 8193, 16);
        w.writeU32(quantLf, 16, 0, 1, 5, 1, 8, 1, 16);
        var defaultMap = qfThresholds.length == 0 && lfThresholds[0].length == 0 && lfThresholds[1].length == 0
                && lfThresholds[2].length == 0 && Arrays.equals(blockContextMap, DEFAULT_CONTEXT_MAP);
        w.writeBool(defaultMap);
        if (!defaultMap) {
            for (var c = 0; c < 3; c++) {
                w.write(4, lfThresholds[c].length);
                for (var t : lfThresholds[c]) {
                    w.writeU32(Tokens.packSigned(t), 0, 4, 16, 8, 272, 16, 65808, 32);
                }
            }
            w.write(4, qfThresholds.length);
            for (var t : qfThresholds) {
                w.writeU32(t - 1, 0, 2, 4, 3, 12, 5, 44, 8);
            }
            EntropyEncoder.writeContextMap(blockContextMap, numBlockClusters, w);
        }
        var cflDefault = colorFactor == 84 && baseCorrelationX == 0.0f && baseCorrelationB == 1.0f
                && xFactorLf == 128 && bFactorLf == 128;
        w.writeBool(cflDefault);
        if (!cflDefault) {
            w.writeU32(colorFactor, 84, 0, 256, 0, 2, 8, 258, 16);
            w.writeF16(baseCorrelationX);
            w.writeF16(baseCorrelationB);
            w.write(8, xFactorLf);
            w.write(8, bFactorLf);
        }
    }

    /**
     * Writes the LF dequantization weights of the LF global section.
     *
     * @param w the output
     */
    public void writeLfDequant(JxlBitWriter w) {
        var isDefault = true;
        for (var c = 0; c < 3; c++) {
            isDefault &= lfDequant[c] == DEFAULT_LF_DEQUANT[c];
        }
        w.writeBool(isDefault);
        if (!isDefault) {
            for (var c = 0; c < 3; c++) {
                w.writeF16(lfDequant[c]);
            }
        }
    }

    /**
     * Writes the VarDCT parts of an LF group section: the LF coefficients, and after the caller's modular LF group
     * data, the HF metadata.
     *
     * @param lfg the LF group
     * @param modular the encoder of the frame's modular streams
     * @param w the output
     * @param beforeMeta writes the modular LF group data between the two parts
     */
    public void writeLfGroup(int lfg, ModularEncoder modular, JxlBitWriter w, Runnable beforeMeta) {
        w.write(2, 0);
        modular.writeStream(lfStreams.get(lfg), w);
        beforeMeta.run();
        var meta = metaStreams.get(lfg);
        var count = meta.channels().get(2).width;
        var rect = lfRect(lfg);
        var w8 = (rect[2] + 7) / 8;
        var h8 = (rect[3] + 7) / 8;
        var hUp = false;
        var vUp = false;
        for (var mode : jpegUpsampling) {
            hUp |= mode == 1 || mode == 2;
            vUp |= mode == 1 || mode == 3;
        }
        if (hUp) {
            w8 = (w8 + 1) / 2 * 2;
        }
        if (vUp) {
            h8 = (h8 + 1) / 2 * 2;
        }
        var bits = w8 * h8 == 1 ? 0 : 32 - Integer.numberOfLeadingZeros(w8 * h8 - 1);
        w.write(bits, count - 1);
        modular.writeStream(meta, w);
    }

    /**
     * Writes the HF global section.
     *
     * @param modular the encoder of the frame's modular streams, for a raw quantization table
     * @param w the output
     */
    public void writeHfGlobal(ModularEncoder modular, JxlBitWriter w) {
        if (rawQuantTable == null) {
            w.writeBool(true);
        } else {
            w.writeBool(false);
            w.write(3, 7);
            w.writeF16(1.0f / 2040.0f);
            modular.writeStream(quantTableStream, w);
            for (var i = 1; i < 17; i++) {
                w.write(3, 0);
            }
        }
        var bits = numGroups == 1 ? 0 : 32 - Integer.numberOfLeadingZeros(numGroups - 1);
        w.write(bits, 0);
        w.writeU32(0, 0x5F, 0, 0x13, 0, 0, 0, 0, 13);
        hfCode.writeHeader(w);
    }

    /**
     * Writes the VarDCT part of a pass group section.
     *
     * @param group the index of the group in the frame
     * @param w the output
     */
    public void writePassGroup(int group, JxlBitWriter w) {
        hfCode.writeTokens(hfTokens.get(group), w);
    }

    /**
     * {@return the number of LF groups}
     */
    public int numLfGroups() {
        return numLf;
    }

    /**
     * {@return the number of groups}
     */
    public int numGroups() {
        return numGroups;
    }

    /**
     * {@return the X quantization matrix scale for the frame header}
     */
    public int xQmScale() {
        return xQmScale;
    }

    /**
     * {@return the B quantization matrix scale for the frame header}
     */
    public int bQmScale() {
        return bQmScale;
    }

    /**
     * {@return whether the frame must skip adaptive LF smoothing}
     */
    public boolean skipLfSmoothing() {
        return skipLfSmoothing;
    }
}
