package com.github.auties00.smalt.video.av1.internal.encoder;

import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.internal.task.ParallelBands;
import com.github.auties00.smalt.video.av1.internal.McDsp;

import java.util.Arrays;
import java.util.List;

/**
 * Denoises a source picture by motion-compensated averaging with its neighbors in display order,
 * the filtering libaom applies to the pictures alternate references and key frames are coded from.
 *
 * <p>The picture is processed in 32x32 blocks. For each neighbor, a block's motion is searched on
 * luma (an n-step full-sample search, then half, quarter and eighth-sample refinements of the
 * variance), and again for its four 16x16 quarters, which replace the whole-block vector when their
 * errors differ enough. Every sample then averages the picture and the motion-compensated
 * neighbors, each neighbor weighted by {@code exp(-e)}, where {@code e} combines the squared
 * differences in a 5x5 window around the sample (and, for chroma, the four luma samples it covers)
 * with the block's motion search error, scaled by the motion distance, the noise level estimated
 * on the plane, the quantizer and the strength.
 *
 * @implNote This implementation ports libaom's {@code av1/encoder/temporal_filter.c}
 * ({@code tf_motion_search}, {@code tf_determine_block_partition},
 * {@code tf_build_predictor}, {@code av1_apply_temporal_filter_c},
 * {@code tf_normalize_filtered_frame}, {@code av1_estimate_noise_from_single_plane_c},
 * {@code av1_check_show_filtered_frame}) with its constants ({@code TF_BLOCK_SIZE},
 * {@code TF_WINDOW_LENGTH}, {@code TF_WEIGHT_SCALE} and the decay thresholds). The motion
 * search is this encoder's n-step diamond rather than libaom's with its mesh stage, the
 * predictions use the 8-tap sharp filter instead of {@code MULTITAP_SHARP2}, and the
 * exponential is {@link StrictMath#exp} so the output does not depend on the platform.
 */
final class TemporalFilter {
    /**
     * The block size ({@code TF_BLOCK_SIZE}).
     */
    private static final int BLOCK = 32;

    /**
     * The weight of the frame being filtered ({@code TF_WEIGHT_SCALE}).
     */
    private static final int WEIGHT_SCALE = 1000;

    /**
     * The border of the padded planes, in luma samples.
     */
    private static final int BORDER = 96;

    /**
     * The n-step stages of the whole-block search: radii up to 41.
     */
    private static final int BLOCK_STAGES = 9;

    /**
     * The n-step stages of the quarter-block searches: radii up to 3.
     */
    private static final int SUB_STAGES = 3;

    /**
     * The row offset of each of the eight sites of a diamond stage, in units of its radius.
     */
    private static final int[] SITE_ROW = {-1, 1, 0, 0, -1, 1, -1, 1};

    /**
     * The row offset of the left, right, upper and lower neighbors of a sub-sample position, in
     * units of the step.
     */
    private static final int[] CARDINAL_ROW = {0, 0, -1, 1};

    /**
     * The number of table steps per unit of the weight exponent.
     */
    private static final int EXP_STEPS_PER_UNIT = 1024;

    /**
     * The values of {@code exp(-x)} at {@code x = i / EXP_STEPS_PER_UNIT}, from 0 to 7 plus one
     * entry of margin.
     */
    private static final double[] EXP_TABLE = expTable();

    /**
     * The column offset of the left, right, upper and lower neighbors of a sub-sample position, in
     * units of the step.
     */
    private static final int[] CARDINAL_COL = {-1, 1, 0, 0};

    /**
     * The column offset of each of the eight sites of a diamond stage, in units of its radius.
     */
    private static final int[] SITE_COL = {0, 0, -1, 1, -1, 1, 1, -1};

    /**
     * The edge threshold of the noise estimate ({@code NOISE_ESTIMATION_EDGE_THRESHOLD}).
     */
    private static final int NOISE_EDGE_THRESHOLD = 50;

    /**
     * The result of filtering one picture.
     *
     * @param picture the filtered picture, of the class, format and depth of the filtered source
     * @param diffSum the sum over 32x32 blocks of the squared error between the source and the
     * filtered picture, at the 8-bit scale
     * @param diffSse the sum over 32x32 blocks of the squared block squared errors
     * @param blocks the number of 32x32 blocks
     */
    record Result(Picture picture, long diffSum, double diffSse, int blocks) {
    }

    /**
     * One picture widened to 16-bit samples with replicated borders.
     */
    private static final class Padded {
        /**
         * The planes.
         */
        final short[][] data = new short[3][];

        /**
         * The stride of each plane.
         */
        final int[] stride = new int[3];

        /**
         * The index of the top-left visible sample of each plane.
         */
        final int[] origin = new int[3];

        /**
         * Pads a picture.
         *
         * @param p the picture, a {@code YuvPicture} or a {@code Yuv16Picture}
         * @param planes the number of planes, 1 or 3
         * @param ssHor 1 when chroma is subsampled horizontally
         * @param ssVer 1 when chroma is subsampled vertically
         */
        Padded(Picture p, int planes, int ssHor, int ssVer) {
            for (var pl = 0; pl < planes; pl++) {
                var ssx = pl == 0 ? 0 : ssHor;
                var ssy = pl == 0 ? 0 : ssVer;
                var w = SourcePlanes.width(p, pl);
                var h = SourcePlanes.height(p, pl);
                var borderX = BORDER >> ssx;
                var borderY = BORDER >> ssy;
                var s = w + 2 * borderX;
                var plane = new short[s * (h + 2 * borderY)];
                SourcePlanes.widen(p, pl, 0, h, plane, borderY * s + borderX, s);
                for (var y = 0; y < h; y++) {
                    var d = (y + borderY) * s + borderX;
                    Arrays.fill(plane, d - borderX, d, plane[d]);
                    Arrays.fill(plane, d + w, d + w + borderX, plane[d + w - 1]);
                }
                for (var y = 0; y < borderY; y++) {
                    System.arraycopy(plane, borderY * s, plane, y * s, s);
                    System.arraycopy(plane, (borderY + h - 1) * s, plane, (borderY + h + y) * s, s);
                }
                data[pl] = plane;
                stride[pl] = s;
                origin[pl] = borderY * s + borderX;
            }
        }
    }

    /**
     * Prevents instantiation.
     */
    private TemporalFilter() {
        throw new AssertionError();
    }

    /**
     * Estimates the noise standard deviation of one plane from its Laplacian at samples without a
     * strong gradient, at the 8-bit scale ({@code av1_estimate_noise_from_single_plane_c} and its
     * high bit depth variant).
     *
     * @param p the picture, a {@code YuvPicture} or a {@code Yuv16Picture}
     * @param plane the plane index: 0 luma, 1 U, 2 V
     * @return the estimate, or -1 when fewer than 16 samples are smooth enough
     */
    static double noiseLevel(Picture p, int plane) {
        var w = SourcePlanes.width(p, plane);
        var h = SourcePlanes.height(p, plane);
        if (w < 3 || h < 3) {
            return -1.0;
        }
        var src = new short[3 * w];
        var shift = SourcePlanes.bitDepth(p) - 8;
        var round = (1 << shift) >> 1;
        SourcePlanes.widen(p, plane, 0, 2, src, 0, w);
        long accum = 0;
        var count = 0;
        for (var i = 1; i < h - 1; i++) {
            var top = ((i - 1) % 3) * w;
            var mid = (i % 3) * w;
            var bottom = ((i + 1) % 3) * w;
            SourcePlanes.widen(p, plane, i + 1, i + 2, src, bottom, w);
            for (var j = 1; j < w - 1; j++) {
                var m00 = src[top + j - 1];
                var m01 = src[top + j];
                var m02 = src[top + j + 1];
                var m10 = src[mid + j - 1];
                var m11 = src[mid + j];
                var m12 = src[mid + j + 1];
                var m20 = src[bottom + j - 1];
                var m21 = src[bottom + j];
                var m22 = src[bottom + j + 1];
                var gx = (m00 - m02) + (m20 - m22) + 2 * (m10 - m12);
                var gy = (m00 - m20) + (m02 - m22) + 2 * (m01 - m21);
                if ((Math.abs(gx) + Math.abs(gy) + round) >> shift < NOISE_EDGE_THRESHOLD) {
                    var v = 4 * m11 - 2 * (m01 + m21 + m10 + m12) + (m00 + m02 + m20 + m22);
                    accum += (Math.abs(v) + round) >> shift;
                    count++;
                }
            }
        }
        return count < 16 ? -1.0 : (double) accum / (6 * count) * 1.2533141373155;
    }

    /**
     * Returns whether a filtered alternate reference is close enough to its source to be shown in
     * its place ({@code av1_check_show_filtered_frame}).
     *
     * @param result the filtering result
     * @param qindex the alternate reference's quantizer index
     * @return {@code true} to show the filtered picture
     */
    static boolean showFiltered(Result result, int qindex) {
        var n = Math.max(1, result.blocks);
        var mean = (double) result.diffSum / n;
        var std = Math.sqrt(Math.max(0, result.diffSse / n - mean * mean));
        var step = Quantizer.acQuant(qindex, 0);
        var threshold = 0.7 * step * step;
        return mean < threshold && std < mean * 1.2;
    }

    /**
     * Filters one picture of a sequence.
     *
     * @param frames the pictures, in display order, all {@code YuvPicture}s or all
     * {@code Yuv16Picture}s of the same size, format and depth
     * @param center the index of the picture to filter
     * @param qindex the quantizer index whose step scales the filtering
     * @param strength the strength, 0 to 6
     * @param key whether the picture is a key frame, whose strength drops to 1 when a block's
     * motion search finds a large DC difference
     * @param threads the most threads to use
     * @return the filtered picture and its difference statistics
     */
    static Result filter(List<Picture> frames, int center, int qindex, int strength, boolean key, int threads) {
        var target = frames.get(center);
        var width = target.width();
        var height = target.height();
        var format = SourcePlanes.format(target);
        var planes = format.hasChroma() ? 3 : 1;
        var ssHor = format.shiftX();
        var ssVer = format.shiftY();
        var bitDepth = SourcePlanes.bitDepth(target);
        var padded = new Padded[frames.size()];
        for (var i = 0; i < frames.size(); i++) {
            padded[i] = new Padded(frames.get(i), planes, ssHor, ssVer);
        }
        var noise = new double[3];
        for (var pl = 0; pl < planes; pl++) {
            noise[pl] = noiseLevel(target, pl);
        }
        var qFactor = (int) RateControl.qindexToQ(qindex);
        var out = SourcePlanes.allocateLike(target);
        var mbCols = (width + BLOCK - 1) / BLOCK;
        var mbRows = (height + BLOCK - 1) / BLOCK;
        var bands = Math.max(1, Math.min(threads, mbRows));
        var sums = new long[bands];
        var sses = new double[bands];
        ParallelBands.run(bands, band -> {
            var worker = new Worker(padded, center, width, height, noise, qFactor, strength, key, planes, ssHor, ssVer,
                    bitDepth);
            var first = ParallelBands.start(band, bands, mbRows);
            var end = ParallelBands.start(band + 1, bands, mbRows);
            for (var r = first; r < end; r++) {
                for (var c = 0; c < mbCols; c++) {
                    worker.block(r, c, out);
                }
            }
            sums[band] = worker.diffSum;
            sses[band] = worker.diffSse;
        });
        long sum = 0;
        double sse = 0;
        for (var b = 0; b < bands; b++) {
            sum += sums[b];
            sse += sses[b];
        }
        return new Result(out, sum, sse, mbRows * mbCols);
    }

    /**
     * Filters the blocks of one band of rows.
     */
    private static final class Worker {
        /**
         * The padded pictures.
         */
        private final Padded[] frames;

        /**
         * The index of the picture being filtered.
         */
        private final int center;

        /**
         * The picture width.
         */
        private final int width;

        /**
         * The picture height.
         */
        private final int height;

        /**
         * The noise level of each plane.
         */
        private final double[] noise;

        /**
         * The quantizer step factor.
         */
        private final int qFactor;

        /**
         * The strength the cached decay factors were computed for, or -1 before the first block.
         */
        private int decayStrength = -1;

        /**
         * The filter decay factor of each plane at the cached strength.
         */
        private final double[] planeDecay = new double[3];

        /**
         * The strength.
         */
        private final int strength;

        /**
         * Whether the picture is a key frame.
         */
        private final boolean key;

        /**
         * The kernels.
         */
        private final EncDsp dsp;

        /**
         * The number of planes, 1 or 3.
         */
        private final int planes;

        /**
         * 1 when chroma is subsampled horizontally, 0 otherwise.
         */
        private final int ssHor;

        /**
         * 1 when chroma is subsampled vertically, 0 otherwise.
         */
        private final int ssVer;

        /**
         * The largest sample value.
         */
        private final int bdMax;

        /**
         * Twice the bit depth above 8, the shift of a squared difference to the 8-bit scale.
         */
        private final int sseShift;

        /**
         * The motion compensation kernels.
         */
        private final McDsp mc = McDsp.select();

        /**
         * The motion compensation scratch.
         */
        private final short[] mcScratch = new short[McDsp.SCRATCH_SHORTS];

        /**
         * The prediction of a block, luma then U then V, each of stride its width.
         */
        private final short[] pred = new short[3 * BLOCK * BLOCK];

        /**
         * A trial prediction of the sub-sample search.
         */
        private final short[] trial = new short[BLOCK * BLOCK];

        /**
         * The weighted sums of each sample.
         */
        private final long[] accum = new long[pred.length];

        /**
         * The weights of each sample.
         */
        private final int[] count = new int[pred.length];

        /**
         * The squared differences of one plane, with a two-sample replicated border.
         */
        private final int[] square = new int[(BLOCK + 4) * (BLOCK + 4)];

        /**
         * The vertical window sums of one plane.
         */
        private final long[] column = new long[(BLOCK + 4) * BLOCK];

        /**
         * The luma squared differences of the block, for chroma.
         */
        private final int[] lumaSquare = new int[BLOCK * BLOCK];

        /**
         * The motion vector of each quarter, in eighth samples, as row, column pairs.
         */
        private final int[] subMv = new int[8];

        /**
         * The motion search error of each quarter, per sample.
         */
        private final int[] subMse = new int[4];

        /**
         * The motion vector carried from frame to frame of a block, as row, column.
         */
        private final int[] refMv = new int[2];

        /**
         * The sum of the blocks' squared errors between source and filtered picture.
         */
        long diffSum;

        /**
         * The sum of the squares of the blocks' squared errors.
         */
        double diffSse;

        /**
         * Creates a worker.
         *
         * @param frames the padded pictures
         * @param center the index of the picture being filtered
         * @param width the picture width
         * @param height the picture height
         * @param noise the noise level of each plane
         * @param qFactor the quantizer step factor
         * @param strength the filter strength
         * @param key whether the picture is a key frame
         * @param planes the number of planes, 1 or 3
         * @param ssHor 1 when chroma is subsampled horizontally
         * @param ssVer 1 when chroma is subsampled vertically
         * @param bitDepth the bit depth of the samples
         */
        Worker(Padded[] frames, int center, int width, int height, double[] noise, int qFactor, int strength,
               boolean key, int planes, int ssHor, int ssVer, int bitDepth) {
            this.frames = frames;
            this.center = center;
            this.width = width;
            this.height = height;
            this.noise = noise;
            this.qFactor = qFactor;
            this.strength = strength;
            this.key = key;
            this.planes = planes;
            this.ssHor = ssHor;
            this.ssVer = ssVer;
            this.bdMax = (1 << bitDepth) - 1;
            this.sseShift = 2 * (bitDepth - 8);
            this.dsp = EncDsp.select(bitDepth);
        }

        /**
         * Filters one 32x32 block into the output picture ({@code av1_tf_do_filtering_row}).
         *
         * @param row the block row
         * @param col the block column
         * @param out the output picture
         */
        void block(int row, int col, Picture out) {
            Arrays.fill(accum, 0);
            Arrays.fill(count, 0);
            refMv[0] = 0;
            refMv[1] = 0;
            var blockStrength = strength;
            var target = frames[center];
            var px = col * BLOCK;
            var py = row * BLOCK;
            var subSplit = subBlockSearchAllowed(target, px, py);
            for (var f = 0; f < frames.length; f++) {
                if (f == center) {
                    refMv[0] = -refMv[0];
                    refMv[1] = -refMv[1];
                    self(target, px, py);
                    continue;
                }
                var dcLarge = search(target, frames[f], px, py, subSplit);
                if (key && dcLarge) {
                    blockStrength = Math.min(blockStrength, 1);
                }
                predict(frames[f], px, py);
                apply(target, px, py, blockStrength);
            }
            normalize(target, px, py, out);
        }

        /**
         * Returns whether the quarters of a block get their own motion search: the spread of the
         * logarithmic variances of its 4x4 blocks exceeds 4 ({@code allow_sub_blk_me_in_tf}).
         *
         * @param target the padded picture being filtered
         * @param px the block column in samples
         * @param py the block row in samples
         * @return {@code true} to search the quarters
         */
        private boolean subBlockSearchAllowed(Padded target, int px, int py) {
            var min = Integer.MAX_VALUE;
            var max = 0;
            var s = target.stride[0];
            var base = target.origin[0] + py * s + px;
            for (var y = 0; y < BLOCK; y += 4) {
                for (var x = 0; x < BLOCK; x += 4) {
                    long sum = 0;
                    long sq = 0;
                    for (var r = 0; r < 4; r++) {
                        var o = base + (y + r) * s + x;
                        for (var c = 0; c < 4; c++) {
                            long v = target.data[0][o + c];
                            sum += v;
                            sq += v * v;
                        }
                    }
                    var var = (int) ((sq - (sum * sum) / 16) >> sseShift);
                    min = Math.min(min, var);
                    max = Math.max(max, var);
                }
            }
            return StrictMath.log1p(max / 16.0) - StrictMath.log1p(min / 16.0) > 4.0;
        }

        /**
         * Adds the picture being filtered itself, at full weight.
         *
         * @param target the padded picture
         * @param px the block column in luma samples
         * @param py the block row in luma samples
         */
        private void self(Padded target, int px, int py) {
            var off = 0;
            for (var pl = 0; pl < planes; pl++) {
                var ssx = pl == 0 ? 0 : ssHor;
                var ssy = pl == 0 ? 0 : ssVer;
                var w = BLOCK >> ssx;
                var h = BLOCK >> ssy;
                var s = target.stride[pl];
                var base = target.origin[pl] + (py >> ssy) * s + (px >> ssx);
                for (var y = 0; y < h; y++) {
                    for (var x = 0; x < w; x++) {
                        var i = off + y * w + x;
                        accum[i] += (long) WEIGHT_SCALE * target.data[pl][base + y * s + x];
                        count[i] += WEIGHT_SCALE;
                    }
                }
                off += w * h;
            }
        }

        /**
         * Searches a block's motion against a neighbor and its quarters' motion, leaving the
         * vectors in {@link #subMv} and the errors in {@link #subMse} ({@code tf_motion_search}).
         *
         * @param target the padded picture being filtered
         * @param ref the padded neighbor
         * @param px the block column
         * @param py the block row
         * @param subSplit whether the quarters are searched
         * @return whether the block's DC difference is large
         */
        private boolean search(Padded target, Padded ref, int px, int py, boolean subSplit) {
            var best = searchBlock(target, ref, px, py, BLOCK, refMv[0], refMv[1], BLOCK_STAGES);
            var row = best[0];
            var colMv = best[1];
            var blockErr = best[2];
            var blockSse = best[3];
            var blockMse = (blockErr + BLOCK * BLOCK / 2) / (BLOCK * BLOCK);
            refMv[0] = row;
            refMv[1] = colMv;
            var dcLarge = 50L * blockErr < blockSse;
            var half = BLOCK / 2;
            if (subSplit) {
                var i = 0;
                for (var y = 0; y < BLOCK; y += half) {
                    for (var x = 0; x < BLOCK; x += half) {
                        var sub = searchBlock(target, ref, px + x, py + y, half, row, colMv, SUB_STAGES);
                        subMv[i * 2] = sub[0];
                        subMv[i * 2 + 1] = sub[1];
                        subMse[i] = (sub[2] + half * half / 2) / (half * half);
                        i++;
                    }
                }
                var min = Integer.MAX_VALUE;
                var max = Integer.MIN_VALUE;
                long sum = 0;
                for (var k = 0; k < 4; k++) {
                    sum += subMse[k];
                    min = Math.min(min, subMse[k]);
                    max = Math.max(max, subMse[k]);
                }
                if ((blockMse * 15L < sum * 4 && max - min < 48) || (blockMse * 14L < sum * 4 && max - min < 24)) {
                    subSplit = false;
                }
            }
            if (!subSplit) {
                for (var k = 0; k < 4; k++) {
                    subMv[k * 2] = row;
                    subMv[k * 2 + 1] = colMv;
                    subMse[k] = blockMse;
                }
            }
            var threshold = Math.min(width, height) >= 720 ? 12 : 3;
            if (blockMse > threshold) {
                refMv[0] = 0;
                refMv[1] = 0;
            }
            return dcLarge;
        }

        /**
         * Searches the motion of one square luma block: an n-step full-sample search from a start
         * and from zero, then half, quarter and eighth-sample refinements by variance.
         *
         * @param target the padded picture being filtered
         * @param ref the padded neighbor
         * @param px the block column
         * @param py the block row
         * @param size the block size
         * @param startRow the start row in eighth samples
         * @param startCol the start column in eighth samples
         * @param stages the n-step stages
         * @return the row and column in eighth samples, the variance and the squared error
         */
        private int[] searchBlock(Padded target, Padded ref, int px, int py, int size, int startRow, int startCol,
                                  int stages) {
            var s = target.stride[0];
            var src = target.data[0];
            var srcOff = target.origin[0] + py * s + px;
            var refPlane = ref.data[0];
            var refBase = ref.origin[0] + py * s + px;
            var limitLo = -(BORDER - 16);
            var rowMin = Math.max(limitLo, -py - (BORDER - 16));
            var colMin = Math.max(limitLo, -px - (BORDER - 16));
            var rowMax = height - py - size + BORDER - 16;
            var colMax = width - px - size + BORDER - 16;
            var br = Math.clamp((startRow + 4) >> 3, rowMin, rowMax);
            var bc = Math.clamp((startCol + 4) >> 3, colMin, colMax);
            var best = dsp.sad(src, srcOff, s, refPlane, refBase + br * s + bc, s, size, size);
            if (br != 0 || bc != 0) {
                var zero = dsp.sad(src, srcOff, s, refPlane, refBase, s, size, size);
                if (zero < best) {
                    best = zero;
                    br = 0;
                    bc = 0;
                }
            }
            var radius = 1;
            var radii = new int[stages];
            for (var i = 0; i < stages; i++) {
                radii[i] = radius;
                radius = (int) Math.max(radius * 1.5 + 0.5, radius + 1);
            }
            for (var st = stages - 1; st >= 0; st--) {
                var r = radii[st];
                var moved = -1;
                for (var d = 0; d < 8; d++) {
                    var nr = br + SITE_ROW[d] * r;
                    var nc = bc + SITE_COL[d] * r;
                    if (nr < rowMin || nr > rowMax || nc < colMin || nc > colMax) {
                        continue;
                    }
                    var sad = dsp.sad(src, srcOff, s, refPlane, refBase + nr * s + nc, s, size, size);
                    if (sad < best) {
                        best = sad;
                        moved = d;
                    }
                }
                if (moved >= 0) {
                    br += SITE_ROW[moved] * r;
                    bc += SITE_COL[moved] * r;
                }
            }
            var row = br * 8;
            var col = bc * 8;
            var log2 = 2 * Integer.numberOfTrailingZeros(size);
            var packed = dsp.sseSum(src, srcOff, s, refPlane, refBase + br * s + bc, s, size, size);
            var bestVar = EncDsp.variance(packed, log2);
            var bestSse = EncDsp.sse(packed);
            for (var step = 4; step >= 1; step >>= 1) {
                var cr = row;
                var cc = col;
                var leftErr = Integer.MAX_VALUE;
                var rightErr = Integer.MAX_VALUE;
                var upErr = Integer.MAX_VALUE;
                var downErr = Integer.MAX_VALUE;
                for (var d = 0; d < 4; d++) {
                    var tr = cr + CARDINAL_ROW[d] * step;
                    var tc = cc + CARDINAL_COL[d] * step;
                    if ((tr >> 3) < rowMin || (tr >> 3) > rowMax || (tc >> 3) < colMin || (tc >> 3) > colMax) {
                        continue;
                    }
                    var p = subpelError(src, srcOff, refPlane, refBase, s, size, tr, tc);
                    var v = EncDsp.variance(p, log2);
                    switch (d) {
                        case 0 -> leftErr = v;
                        case 1 -> rightErr = v;
                        case 2 -> upErr = v;
                        default -> downErr = v;
                    }
                    if (v < bestVar) {
                        bestVar = v;
                        bestSse = EncDsp.sse(p);
                        row = tr;
                        col = tc;
                    }
                }
                if (row != cr || col != cc) {
                    var tr = cr + (upErr <= downErr ? -step : step);
                    var tc = cc + (leftErr <= rightErr ? -step : step);
                    if ((tr >> 3) >= rowMin && (tr >> 3) <= rowMax && (tc >> 3) >= colMin && (tc >> 3) <= colMax) {
                        var p = subpelError(src, srcOff, refPlane, refBase, s, size, tr, tc);
                        var v = EncDsp.variance(p, log2);
                        if (v < bestVar) {
                            bestVar = v;
                            bestSse = EncDsp.sse(p);
                            row = tr;
                            col = tc;
                        }
                    }
                }
            }
            return new int[]{row, col, bestVar, bestSse};
        }

        /**
         * Predicts a block at a sub-sample position with the sharp eight-tap filter and returns its
         * sums of squared and of signed differences against the block being filtered.
         *
         * @param src the block being filtered
         * @param srcOff the offset of its first sample
         * @param refPlane the neighbor's plane
         * @param refBase the offset of the block's position in the neighbor
         * @param s the plane stride
         * @param size the block size
         * @param tr the row in eighth samples
         * @param tc the column in eighth samples
         * @return the packed sums
         */
        private long subpelError(short[] src, int srcOff, short[] refPlane, int refBase, int s, int size, int tr,
                                 int tc) {
            mc.mc(McDsp.FILTER_2D_8TAP_SHARP, trial, 0, size, refPlane, refBase + (tr >> 3) * s + (tc >> 3), s,
                    size, size, (tc & 7) << 1, (tr & 7) << 1, mcScratch, bdMax);
            return dsp.sseSum(src, srcOff, s, trial, 0, size, size, size);
        }

        /**
         * Builds the prediction of every plane of the block from a neighbor with the quarters'
         * vectors ({@code tf_build_predictor}).
         *
         * @param ref the padded neighbor
         * @param px the block column in luma samples
         * @param py the block row in luma samples
         */
        private void predict(Padded ref, int px, int py) {
            var off = 0;
            for (var pl = 0; pl < planes; pl++) {
                var ssx = pl == 0 ? 0 : ssHor;
                var ssy = pl == 0 ? 0 : ssVer;
                var w = BLOCK >> ssx;
                var h = BLOCK >> ssy;
                var halfW = w >> 1;
                var halfH = h >> 1;
                var s = ref.stride[pl];
                var base = ref.origin[pl] + (py >> ssy) * s + (px >> ssx);
                var i = 0;
                for (var y = 0; y < h; y += halfH) {
                    for (var x = 0; x < w; x += halfW) {
                        var mvr = subMv[i * 2];
                        var mvc = subMv[i * 2 + 1];
                        i++;
                        var ir = mvr >> (3 + ssy);
                        var ic = mvc >> (3 + ssx);
                        var fr = (mvr & (15 >> (ssy ^ 1))) << (ssy ^ 1);
                        var fc = (mvc & (15 >> (ssx ^ 1))) << (ssx ^ 1);
                        mc.mc(McDsp.FILTER_2D_8TAP_SHARP, pred, off + y * w + x, w, ref.data[pl],
                                base + (y + ir) * s + x + ic, s, halfW, halfH, fc, fr, mcScratch, bdMax);
                    }
                }
                off += w * h;
            }
        }

        /**
         * Returns {@code exp(-x)} for {@code x} in {@code [0, 7]} by linear interpolation of a
         * table; the interpolation error is below 4e-7, far under one weight step.
         *
         * @param x the exponent, between 0 and 7
         * @return the value
         */
        private static double expNegative(double x) {
            var position = x * EXP_STEPS_PER_UNIT;
            var index = (int) position;
            var fraction = position - index;
            return EXP_TABLE[index] + (EXP_TABLE[index + 1] - EXP_TABLE[index]) * fraction;
        }

        /**
         * Adds a neighbor's prediction with its per-sample weights ({@code av1_apply_temporal_filter_c}).
         *
         * <p>Squared differences are taken at the 8-bit scale, so the weights do not depend on the
         * bit depth.
         *
         * @param target the padded picture being filtered
         * @param px the block column in luma samples
         * @param py the block row in luma samples
         * @param strength the filter strength
         */
        private void apply(Padded target, int px, int py, int strength) {
            var invFactor = 1.0 / ((5 + 1) * 20);
            var weightFactor = 5 * invFactor;
            if (strength != decayStrength) {
                var qDecay = StrictMath.pow(qFactor / 20.0, 2);
                qDecay = Math.clamp(qDecay, 1e-5, 1);
                if (qFactor >= 128) {
                    qDecay = 0.5 * StrictMath.pow(qFactor / 64.0, 2);
                }
                var sDecay = Math.clamp(StrictMath.pow(strength / 4.0, 2), 1e-5, 1);
                for (var pl = 0; pl < planes; pl++) {
                    planeDecay[pl] = 1 / ((0.5 + StrictMath.log(2 * noise[pl] + 5.0)) * qDecay * sDecay);
                }
                decayStrength = strength;
            }
            var dFactor = new double[4];
            var distanceThreshold = Math.max(Math.min(width, height) * 0.1, 1);
            for (var k = 0; k < 4; k++) {
                var r = subMv[k * 2];
                var c = subMv[k * 2 + 1];
                var distance = Math.sqrt((double) r * r + (double) c * c);
                dFactor[k] = Math.max(distance / distanceThreshold, 1);
            }
            var off = 0;
            for (var pl = 0; pl < planes; pl++) {
                var ssx = pl == 0 ? 0 : ssHor;
                var ssy = pl == 0 ? 0 : ssVer;
                var w = BLOCK >> ssx;
                var h = BLOCK >> ssy;
                var decay = planeDecay[pl];
                var s = target.stride[pl];
                var base = target.origin[pl] + (py >> ssy) * s + (px >> ssx);
                var ext = w + 4;
                for (var y = 0; y < h; y++) {
                    for (var x = 0; x < w; x++) {
                        var d = target.data[pl][base + y * s + x] - pred[off + y * w + x];
                        square[(y + 2) * ext + x + 2] = (d * d) >> sseShift;
                    }
                }
                if (pl == 0) {
                    for (var y = 0; y < h; y++) {
                        System.arraycopy(square, (y + 2) * ext + 2, lumaSquare, y * w, w);
                    }
                }
                for (var y = 2; y < h + 2; y++) {
                    var row = y * ext;
                    square[row] = square[row + 2];
                    square[row + 1] = square[row + 2];
                    square[row + w + 2] = square[row + w + 1];
                    square[row + w + 3] = square[row + w + 1];
                }
                System.arraycopy(square, 2 * ext, square, 0, ext);
                System.arraycopy(square, 2 * ext, square, ext, ext);
                System.arraycopy(square, (h + 1) * ext, square, (h + 2) * ext, ext);
                System.arraycopy(square, (h + 1) * ext, square, (h + 3) * ext, ext);
                for (var y = 0; y < h; y++) {
                    for (var x = 0; x < ext; x++) {
                        long v = 0;
                        for (var k = 0; k < 5; k++) {
                            v += square[(y + k) * ext + x];
                        }
                        column[y * ext + x] = v;
                    }
                }
                var refPixels = pl == 0 ? 25 : 25 + (1 << (ssx + ssy));
                for (var y = 0; y < h; y++) {
                    for (var x = 0; x < w; x++) {
                        var sum = column[y * ext + x] + column[y * ext + x + 1] + column[y * ext + x + 2]
                                + column[y * ext + x + 3] + column[y * ext + x + 4];
                        if (pl != 0) {
                            var ly = y << ssy;
                            var lx = x << ssx;
                            for (var j = 0; j <= ssy; j++) {
                                for (var k = 0; k <= ssx; k++) {
                                    sum += lumaSquare[(ly + j) * BLOCK + lx + k];
                                }
                            }
                        }
                        var windowError = (double) sum / refPixels;
                        var sub = (y >= h / 2 ? 2 : 0) + (x >= w / 2 ? 1 : 0);
                        var combined = weightFactor * windowError + subMse[sub] * invFactor;
                        var scaled = Math.min(combined * dFactor[sub] * decay, 7);
                        var weight = (int) (expNegative(scaled) * WEIGHT_SCALE);
                        var i = off + y * w + x;
                        accum[i] += (long) weight * pred[i];
                        count[i] += weight;
                    }
                }
                off += w * h;
            }
        }

        /**
         * Divides the weighted sums into the output picture and adds the block's difference
         * statistics, at the 8-bit scale ({@code tf_normalize_filtered_frame}).
         *
         * @param target the padded picture being filtered
         * @param px the block column in luma samples
         * @param py the block row in luma samples
         * @param out the output picture
         */
        private void normalize(Padded target, int px, int py, Picture out) {
            var off = 0;
            long sse = 0;
            for (var pl = 0; pl < planes; pl++) {
                var ssx = pl == 0 ? 0 : ssHor;
                var ssy = pl == 0 ? 0 : ssVer;
                var bw = BLOCK >> ssx;
                var bh = BLOCK >> ssy;
                var w = SourcePlanes.width(out, pl);
                var h = SourcePlanes.height(out, pl);
                var x0 = px >> ssx;
                var y0 = py >> ssy;
                var s = target.stride[pl];
                var base = target.origin[pl] + y0 * s + x0;
                for (var y = 0; y < bh && y0 + y < h; y++) {
                    for (var x = 0; x < bw && x0 + x < w; x++) {
                        var i = off + y * bw + x;
                        var c = count[i];
                        var v = (int) ((accum[i] + (c >> 1)) / c);
                        SourcePlanes.store(out, pl, x0 + x, y0 + y, v);
                        if (pl == 0) {
                            long d = target.data[0][base + y * s + x] - v;
                            sse += d * d;
                        }
                    }
                }
                off += bw * bh;
            }
            sse >>= sseShift;
            diffSum += sse;
            diffSse += (double) sse * sse;
        }
    }

    /**
     * Builds the table of {@code exp(-x)}.
     *
     * @return the table
     */
    private static double[] expTable() {
        var table = new double[7 * EXP_STEPS_PER_UNIT + 2];
        for (var i = 0; i < table.length; i++) {
            table[i] = StrictMath.exp(-(double) i / EXP_STEPS_PER_UNIT);
        }
        return table;
    }
}
