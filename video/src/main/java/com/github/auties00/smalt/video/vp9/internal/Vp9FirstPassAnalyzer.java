/* Ports libvpx 1.15.1 (vp9/encoder/vp9_firstpass.c, vp9/encoder/vp9_mcomp.c). Copyright (c) 2010, The WebM Project
   authors; BSD 3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.video.vp9.internal;

import com.github.auties00.smalt.internal.task.ParallelBands;

import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Measures the first-pass statistics of the pictures of a VP9 stream, one record of
 * {@link #FIELDS} values per picture, which the good-quality rate control places key frames,
 * sizes golden frame groups, boosts alternate references and spreads the bitrate with.
 *
 * <p>Like libvpx's first pass, the analyzer codes the luma of every picture at the quantizer of
 * {@code FIRST_PASS_Q} and predicts from that reconstruction: every 16x16 block is predicted
 * intra, with DC predictions from the reconstruction of its neighbors, and inter from the
 * reconstruction of the previous picture, with a diamond motion search, and of the second
 * reference, an older picture that the analyzer moves forward when the previous picture predicted
 * the current one well or every fourth picture; the block is then coded with the better
 * prediction. The prediction errors and the counts of blocks each prediction wins make the
 * record. Macroblock rows run in a wavefront on up to the given number of threads, each waiting
 * for the reconstruction of the row above, and are summed in row order, so the records do not
 * depend on the thread count. Instances are used by one thread at a time.
 *
 * @implNote This implementation follows libvpx's first pass
 * ({@code vp9_first_pass_encode_tile_mb_row}, {@code first_pass_motion_search},
 * {@code first_pass_stat_calc}, {@code fp_estimate_block_noise}, {@code vp9_encode_intra_block_plane},
 * {@code vp9_encode_sby_pass1}); a 10 or 12-bit picture is measured and coded on its 8-bit
 * proxies.
 */
final class Vp9FirstPassAnalyzer {
    /**
     * The number of values of a record.
     */
    static final int FIELDS = 26;

    /**
     * The record field of the frame's display index ({@code frame}).
     */
    static final int FRAME = 0;

    /**
     * The record field of the frame's error weight from its flat and dark blocks ({@code weight}).
     */
    static final int WEIGHT = 1;

    /**
     * The record field of the intra prediction error per macroblock ({@code intra_error}).
     */
    static final int INTRA_ERROR = 2;

    /**
     * The record field of the error of the better of intra and inter prediction from the previous frame, per
     * macroblock ({@code coded_error}).
     */
    static final int CODED_ERROR = 3;

    /**
     * The record field of the error of the better of intra and inter prediction from the second reference, per
     * macroblock ({@code sr_coded_error}).
     */
    static final int SR_CODED_ERROR = 4;

    /**
     * The record field of the noise energy per macroblock ({@code frame_noise_energy}).
     */
    static final int FRAME_NOISE_ENERGY = 5;

    /**
     * The record field of the share of blocks better predicted from the previous frame than intra ({@code
     * pcnt_inter}).
     */
    static final int PCNT_INTER = 6;

    /**
     * The record field of the share of blocks predicted with a nonzero motion vector ({@code pcnt_motion}).
     */
    static final int PCNT_MOTION = 7;

    /**
     * The record field of the share of blocks better predicted from the second reference ({@code
     * pcnt_second_ref}).
     */
    static final int PCNT_SECOND_REF = 8;

    /**
     * The record field of the share of blocks whose intra and inter errors are close ({@code pcnt_neutral}).
     */
    static final int PCNT_NEUTRAL = 9;

    /**
     * The record field of the share of intra blocks with a low inter error ({@code pcnt_intra_low}).
     */
    static final int PCNT_INTRA_LOW = 10;

    /**
     * The record field of the share of intra blocks with a high inter error ({@code pcnt_intra_high}).
     */
    static final int PCNT_INTRA_HIGH = 11;

    /**
     * The record field of the share of flat blocks ({@code intra_skip_pct}).
     */
    static final int INTRA_SKIP_PCT = 12;

    /**
     * The record field of the share of smooth blocks ({@code intra_smooth_pct}).
     */
    static final int INTRA_SMOOTH_PCT = 13;

    /**
     * The record field of the macroblock rows of formatting bars at the top ({@code inactive_zone_rows}).
     */
    static final int INACTIVE_ZONE_ROWS = 14;

    /**
     * The record field of the macroblock columns of formatting bars, always 0 ({@code inactive_zone_cols}).
     */
    static final int INACTIVE_ZONE_COLS = 15;

    /**
     * The record field of the mean motion vector row of the moving blocks, in eighth samples ({@code MVr}).
     */
    static final int MVR = 16;

    /**
     * The record field of the mean absolute motion vector row ({@code mvr_abs}).
     */
    static final int MVR_ABS = 17;

    /**
     * The record field of the mean motion vector column ({@code MVc}).
     */
    static final int MVC = 18;

    /**
     * The record field of the mean absolute motion vector column ({@code mvc_abs}).
     */
    static final int MVC_ABS = 19;

    /**
     * The record field of the variance of the motion vector rows ({@code MVrv}).
     */
    static final int MVRV = 20;

    /**
     * The record field of the variance of the motion vector columns ({@code MVcv}).
     */
    static final int MVCV = 21;

    /**
     * The record field of the balance of the vectors pointing into and out of the picture, -1 to 1 ({@code
     * mv_in_out_count}).
     */
    static final int MV_IN_OUT_COUNT = 22;

    /**
     * The record field of the frame's duration in 100 nanosecond ticks ({@code duration}).
     */
    static final int DURATION = 23;

    /**
     * The record field of the number of frames the record sums, 1 ({@code count}).
     */
    static final int COUNT = 24;

    /**
     * The record field of the share of blocks whose vector differs from the previous moving block's ({@code
     * new_mv_count}).
     */
    static final int NEW_MV_COUNT = 25;

    /**
     * The cost added to the intra error, matching the overhead of a zero motion vector
     * ({@code INTRA_MODE_PENALTY}).
     */
    private static final int INTRA_MODE_PENALTY = 1024;

    /**
     * The zero-motion error above which the motion search runs ({@code NZ_MOTION_PENALTY}).
     */
    private static final int NZ_MOTION_PENALTY = 128;

    /**
     * The cost added to the error of a searched vector ({@code NEW_MV_MODE_PENALTY}).
     */
    private static final int NEW_MV_MODE_PENALTY = 32;

    /**
     * The intra error above which a close inter error counts as neutral ({@code NCOUNT_INTRA_THRESH}).
     */
    private static final int NCOUNT_INTRA_THRESH = 8192;

    /**
     * The ratio of intra to inter error below which a block counts as partly neutral
     * ({@code NCOUNT_INTRA_FACTOR}).
     */
    private static final int NCOUNT_INTRA_FACTOR = 3;

    /**
     * The intra error below which a block is flat ({@code UL_INTRA_THRESH}).
     */
    private static final int UL_INTRA_THRESH = 50;

    /**
     * The intra error below which a block is smooth ({@code SMOOTH_INTRA_THRESH}).
     */
    private static final int SMOOTH_INTRA_THRESH = 4000;

    /**
     * The intra error below which a block's noise is measured ({@code LOW_I_THRESH}).
     */
    private static final int LOW_I_THRESH = 24000;

    /**
     * The luma level below which a block is dark ({@code DARK_THRESH}).
     */
    private static final int DARK_THRESH = 64;

    /**
     * The noise energy assumed for a block too detailed to measure ({@code SECTION_NOISE_DEF}).
     */
    static final double SECTION_NOISE_DEF = 250.0;

    /**
     * The distance a vector may point beyond the picture, in samples ({@code BORDER_MV_PIXELS_B16}).
     */
    private static final int BORDER_MV = 16 + 4;

    /**
     * The number of scales of the diamond search ({@code MAX_MVSEARCH_STEPS}).
     */
    private static final int STEPS = 11;

    /**
     * The row offsets of the eight sites of a diamond search step, in units of the step's length
     * ({@code vp9_init3smotion_compensation}).
     */
    private static final int[] SITE_ROWS = {-1, 1, 0, 0, -1, -1, 1, 1};

    /**
     * The column offsets of the eight sites of a diamond search step.
     */
    private static final int[] SITE_COLS = {0, 0, -1, 1, -1, 1, -1, 1};

    /**
     * The kernel of the noise estimate ({@code fp_dn_kernel_3}).
     */
    private static final int[] NOISE_KERNEL = {1, 2, 1, 2, 4, 2, 1, 2, 1};

    /**
     * The quantizer index of the first pass's error weights ({@code find_fp_qindex}).
     */
    private static final int FIRST_PASS_QINDEX = firstPassQindex();

    /**
     * The picture width.
     */
    private final int width;

    /**
     * The picture height.
     */
    private final int height;

    /**
     * The number of 8x8 rows of a picture.
     */
    private final int miRows;

    /**
     * The number of 8x8 columns of a picture.
     */
    private final int miCols;

    /**
     * The number of macroblock rows of a picture.
     */
    private final int mbRows;

    /**
     * The number of macroblock columns of a picture.
     */
    private final int mbCols;

    /**
     * The sum of absolute differences weight of a vector's cost ({@code sadperbit16}).
     */
    private final int sadPerBit;

    /**
     * The squared error weight of a vector's cost ({@code errorperbit}).
     */
    private final int errorPerBit;

    /**
     * The search scale the first diamond step starts at ({@code get_search_range} plus 3).
     */
    private final int stepParam;

    /**
     * The cost of each motion vector joint.
     */
    private final int[] jointCost = new int[4];

    /**
     * The cost of each row component difference, centered on {@link Vp9EncMv#MV_MAX}.
     */
    private final int[] rowCost = new int[Vp9EncMv.MV_VALS];

    /**
     * The cost of each column component difference.
     */
    private final int[] colCost = new int[Vp9EncMv.MV_VALS];

    /**
     * The luma plane of the previous source picture, in the layout of the analyzed pictures, or
     * {@code null} before the first.
     */
    private byte[] lastSource;

    /**
     * The reconstructions of the first pass: the one being coded and the two references.
     */
    private final Vp9EncPicture[] recons = new Vp9EncPicture[3];

    /**
     * The reconstruction of the previous picture, or {@code null} before the first.
     */
    private Vp9EncPicture lastRecon;

    /**
     * The reconstruction of the second reference, or {@code null} before the first picture.
     */
    private Vp9EncPicture goldenRecon;

    /**
     * The reconstruction of the picture being measured.
     */
    private Vp9EncPicture recon;

    /**
     * The pictures since the second reference last moved forward ({@code sr_update_lag}).
     */
    private int srUpdateLag;

    /**
     * The quantizer of the first pass's coding.
     */
    private static final Vp9Quantizer QUANT = new Vp9Quantizer();

    /**
     * The scratch of each participant of the wavefront.
     */
    private Scratch[] scratch = {new Scratch()};

    /**
     * The progress of each macroblock row of the picture being measured, in macroblocks.
     */
    private final Vp9Wavefront progress;

    /**
     * The next macroblock row to claim.
     */
    private final AtomicInteger nextRow = new AtomicInteger();

    /**
     * The helper participants of the picture being measured.
     */
    private ParallelBands.Pending<?>[] helpers = new ParallelBands.Pending<?>[0];

    /**
     * Holds the scratch memory of one participant.
     */
    private static final class Scratch {
        /**
         * The motion search result: the error, then the vector's full-sample row and column.
         */
        final int[] search = new int[3];

        /**
         * The residual of one macroblock, 16 samples per row.
         */
        final short[] diff = new short[256];

        /**
         * The coefficients of one macroblock's transform blocks.
         */
        final int[] coeff = new int[256];

        /**
         * The quantized coefficients.
         */
        final int[] qcoeff = new int[256];

        /**
         * The dequantized coefficients.
         */
        final int[] dqcoeff = new int[256];

        /**
         * Scratch memory of the forward transforms.
         */
        final long[] fdct = new long[1200];

        /**
         * Integer scratch memory of the forward transforms.
         */
        final int[] fdctInts = new int[Vp9EncDsp.FDCT_SCRATCH];

        /**
         * Scratch memory of the inverse transforms.
         */
        final int[] idct = new int[Vp9Dsp.TRANSFORM_SCRATCH];

        /**
         * The inverse transforms, whose coefficient layout is the raster order.
         */
        final ScalarVp9Dsp dsp = new ScalarVp9Dsp();
    }

    /**
     * The number of pictures analyzed.
     */
    private int frames;

    /**
     * The integer sums of each macroblock row, {@link #ROW_INTS} per row.
     */
    private long[] rowInts;

    /**
     * The floating point sums of each macroblock row, {@link #ROW_DOUBLES} per row.
     */
    private double[] rowDoubles;

    /**
     * The number of integer sums of a row: intra, coded and second reference errors, noise
     * energy, inter, second reference, low and high intra counts, moving blocks, flat and smooth
     * blocks, new vectors, the row and column sums and absolute sums, a flag for a row holding
     * image data, the squared row and column sums and the in-out balance.
     */
    private static final int ROW_INTS = 21;

    /**
     * The number of floating point sums of a row: the intra and brightness factors and the
     * neutral count.
     */
    private static final int ROW_DOUBLES = 3;

    /**
     * Creates an analyzer of a picture size.
     *
     * @param width the picture width
     * @param height the picture height
     */
    Vp9FirstPassAnalyzer(int width, int height) {
        this.width = width;
        this.height = height;
        this.miRows = (height + 7) >> 3;
        this.miCols = (width + 7) >> 3;
        this.mbRows = (miRows + 1) >> 1;
        this.mbCols = (miCols + 1) >> 1;
        this.sadPerBit = Vp9RtEncoder.SAD_PER_BIT16[FIRST_PASS_QINDEX];
        var dc = Vp9EncTables.dcQuant(FIRST_PASS_QINDEX, 0);
        var rdmult = Math.max(1, (int) ((double) dc * dc * (4.15 + 0.001 * FIRST_PASS_QINDEX)));
        this.errorPerBit = Math.max(1, rdmult >> 6);
        var sr = 0;
        var dim = Math.min(width, height);
        while ((dim << sr) < Vp9RtMotion.MAX_FULL_PEL_VAL) {
            sr++;
        }
        this.stepParam = 3 + sr;
        var fc = new Vp9FrameContext();
        fc.setDefaults();
        Vp9EncMv.buildCostTable(fc.probs, false, jointCost, rowCost, colCost);
        this.rowInts = new long[mbRows * ROW_INTS];
        this.rowDoubles = new double[mbRows * ROW_DOUBLES];
        for (var i = 0; i < recons.length; i++) {
            recons[i] = new Vp9EncPicture(width, height);
        }
        this.progress = new Vp9Wavefront(mbRows, 256, "vp9 first pass thread", true);
    }

    /**
     * Returns the quantizer index whose quantizer step is the first pass's ({@code FIRST_PASS_Q}).
     *
     * @return the index
     */
    private static int firstPassQindex() {
        for (var i = 0; i < 256; i++) {
            if (Vp9EncTables.qindexToQ(i) >= 10.0) {
                return i;
            }
        }
        return 255;
    }

    /**
     * Measures a picture against the pictures before it and writes its record.
     *
     * @param picture the picture, of the analyzer's size, with extended borders
     * @param duration the picture's duration, in {@link Vp9RtEncoder#TICKS_PER_SECOND} ticks
     * @param key whether the picture is coded as a key frame, which then predicts from nothing
     * @param out receives the record
     * @param off the index of the record's first value
     * @param threads the most threads to use
     */
    void analyze(Vp9EncPicture picture, long duration, boolean key, double[] out, int off, int threads) {
        if (picture.strides[0] != recons[0].strides[0] || picture.origins[0] != recons[0].origins[0]) {
            throw new IllegalArgumentException("the picture's luma layout differs from the analyzer's");
        }
        var intraOnly = frames == 0 || key || lastRecon == null;
        var lastPlane = intraOnly ? null : lastRecon.planes[0];
        var lastSourcePlane = intraOnly ? null : lastSource;
        var goldenPlane = intraOnly || frames < 2 ? null : goldenRecon.planes[0];
        recon = freeRecon();
        java.util.Arrays.fill(rowInts, 0);
        java.util.Arrays.fill(rowDoubles, 0);
        runRows(picture, lastPlane, lastSourcePlane, goldenPlane, threads);
        recon.extendLumaBorders();
        var sums = new long[ROW_INTS];
        double intraFactor = 0;
        double brightnessFactor = 0;
        double neutral = 0;
        var imageStartRow = -1;
        for (var r = 0; r < mbRows; r++) {
            for (var i = 0; i < ROW_INTS; i++) {
                if (i == 17) {
                    continue;
                }
                sums[i] += rowInts[r * ROW_INTS + i];
            }
            if (imageStartRow < 0 && rowInts[r * ROW_INTS + 17] != 0) {
                imageStartRow = r;
            }
            intraFactor += rowDoubles[r * ROW_DOUBLES];
            brightnessFactor += rowDoubles[r * ROW_DOUBLES + 1];
            neutral += rowDoubles[r * ROW_DOUBLES + 2];
        }
        var mbs = (double) mbRows * mbCols;
        var minErr = 200 * Math.sqrt(mbs);
        if (imageStartRow < 0 || imageStartRow > mbRows / 2) {
            imageStartRow = mbRows / 2;
        }
        var intraSkip = sums[9];
        if (imageStartRow > 0) {
            intraSkip = Math.max(0, intraSkip - (long) imageStartRow * mbCols * 2);
        }
        out[off + FRAME] = frames;
        out[off + WEIGHT] = (intraFactor / mbs) * (brightnessFactor / mbs);
        out[off + CODED_ERROR] = ((sums[1] >> 8) + minErr) / mbs;
        out[off + SR_CODED_ERROR] = ((sums[2] >> 8) + minErr) / mbs;
        out[off + INTRA_ERROR] = ((sums[0] >> 8) + minErr) / mbs;
        out[off + FRAME_NOISE_ENERGY] = sums[3] / mbs;
        out[off + COUNT] = 1.0;
        out[off + PCNT_INTER] = sums[4] / mbs;
        out[off + PCNT_SECOND_REF] = sums[5] / mbs;
        out[off + PCNT_NEUTRAL] = neutral / mbs;
        out[off + PCNT_INTRA_LOW] = sums[6] / mbs;
        out[off + PCNT_INTRA_HIGH] = sums[7] / mbs;
        out[off + INTRA_SKIP_PCT] = intraSkip / mbs;
        out[off + INTRA_SMOOTH_PCT] = sums[10] / mbs;
        out[off + INACTIVE_ZONE_ROWS] = imageStartRow;
        out[off + INACTIVE_ZONE_COLS] = 0;
        var mvCount = sums[8];
        if (mvCount > 0) {
            out[off + NEW_MV_COUNT] = sums[11] / mbs;
            out[off + MVR] = (double) sums[12] / mvCount;
            out[off + MVR_ABS] = (double) sums[13] / mvCount;
            out[off + MVC] = (double) sums[14] / mvCount;
            out[off + MVC_ABS] = (double) sums[15] / mvCount;
            out[off + MVRV] = ((double) sums[18] - (double) sums[12] * sums[12] / mvCount) / mvCount;
            out[off + MVCV] = ((double) sums[19] - (double) sums[14] * sums[14] / mvCount) / mvCount;
            out[off + MV_IN_OUT_COUNT] = (double) sums[20] / (mvCount * 2.0);
            out[off + PCNT_MOTION] = mvCount / mbs;
        } else {
            out[off + NEW_MV_COUNT] = 0;
            out[off + MVR] = 0;
            out[off + MVR_ABS] = 0;
            out[off + MVC] = 0;
            out[off + MVC_ABS] = 0;
            out[off + MVRV] = 0;
            out[off + MVCV] = 0;
            out[off + MV_IN_OUT_COUNT] = 0;
            out[off + PCNT_MOTION] = 0;
        }
        out[off + DURATION] = Math.max(1.0, duration);
        if (srUpdateLag > 3 || (frames > 0 && out[off + PCNT_INTER] > 0.20
                && out[off + INTRA_ERROR] / Math.max(out[off + CODED_ERROR], 1e-6) > 2.0)) {
            if (lastRecon != null) {
                goldenRecon = lastRecon;
            }
            srUpdateLag = 1;
        } else {
            srUpdateLag++;
        }
        lastRecon = recon;
        if (frames == 0 || key) {
            goldenRecon = lastRecon;
        }
        if (lastSource == null || lastSource.length != picture.planes[0].length) {
            lastSource = new byte[picture.planes[0].length];
        }
        System.arraycopy(picture.planes[0], 0, lastSource, 0, lastSource.length);
        frames++;
    }

    /**
     * Returns a reconstruction that neither reference holds.
     *
     * @return the reconstruction
     */
    private Vp9EncPicture freeRecon() {
        for (var candidate : recons) {
            if (candidate != lastRecon && candidate != goldenRecon) {
                return candidate;
            }
        }
        throw new IllegalStateException("no free reconstruction");
    }

    /**
     * Measures and codes every macroblock row, in a wavefront when more than one thread is
     * allowed.
     *
     * @param picture the picture to measure
     * @param lastPlane the reconstruction of the previous picture, or {@code null}
     * @param lastSourcePlane the previous source picture, or {@code null}
     * @param goldenPlane the reconstruction of the second reference, or {@code null}
     * @param threads the most threads to use
     */
    private void runRows(Vp9EncPicture picture, byte[] lastPlane, byte[] lastSourcePlane, byte[] goldenPlane,
                         int threads) {
        var count = 0;
        if (threads > 1 && mbRows > 1 && ParallelBands.enabled()) {
            count = Math.min(Math.min(threads, mbRows) - 1, ParallelBands.available());
        }
        if (count <= 0) {
            for (var r = 0; r < mbRows; r++) {
                row(picture, lastPlane, lastSourcePlane, goldenPlane, r, scratch[0], false);
            }
            return;
        }
        if (scratch.length < count + 1) {
            var grown = java.util.Arrays.copyOf(scratch, count + 1);
            for (var i = scratch.length; i < grown.length; i++) {
                grown[i] = new Scratch();
            }
            scratch = grown;
        }
        if (helpers.length < count) {
            helpers = new ParallelBands.Pending<?>[count];
        }
        progress.reset(mbRows);
        nextRow.set(0);
        Throwable failure = null;
        try {
            for (var i = 0; i < count; i++) {
                var sc = scratch[i + 1];
                helpers[i] = ParallelBands.start(() -> participate(picture, lastPlane, lastSourcePlane, goldenPlane,
                        sc));
            }
            participate(picture, lastPlane, lastSourcePlane, goldenPlane, scratch[0]);
        } catch (RuntimeException | Error exception) {
            progress.abort();
            failure = exception;
        }
        for (var i = 0; i < count; i++) {
            if (helpers[i] != null && helpers[i].tryCancel()) {
                helpers[i] = null;
            }
        }
        for (var i = 0; i < count; i++) {
            var helper = helpers[i];
            if (helper == null) {
                continue;
            }
            helpers[i] = null;
            try {
                helper.join();
            } catch (RuntimeException | Error exception) {
                if (failure == null || failure instanceof CancellationException
                        && !(exception instanceof CancellationException)) {
                    failure = exception;
                }
            }
        }
        if (failure instanceof RuntimeException runtime) {
            throw runtime;
        }
        if (failure instanceof Error error) {
            throw error;
        }
    }

    /**
     * Claims and measures rows until none is left, as a participant of the wavefront.
     *
     * @param picture the picture to measure
     * @param lastPlane the reconstruction of the previous picture, or {@code null}
     * @param lastSourcePlane the previous source picture, or {@code null}
     * @param goldenPlane the reconstruction of the second reference, or {@code null}
     * @param sc the participant's scratch
     */
    private void participate(Vp9EncPicture picture, byte[] lastPlane, byte[] lastSourcePlane, byte[] goldenPlane,
                             Scratch sc) {
        try {
            for (var r = nextRow.getAndIncrement(); r < mbRows; r = nextRow.getAndIncrement()) {
                row(picture, lastPlane, lastSourcePlane, goldenPlane, r, sc, true);
            }
        } catch (RuntimeException | Error exception) {
            progress.abort();
            throw exception;
        }
    }

    /**
     * Measures and codes one macroblock row into its sums and the reconstruction.
     *
     * @param picture the picture to measure
     * @param lastPlane the reconstruction of the previous picture, or {@code null} for an
     *                  intra-only picture
     * @param lastSourcePlane the previous source picture, or {@code null}
     * @param goldenPlane the reconstruction of the second reference, or {@code null}
     * @param mbRow the macroblock row
     * @param sc the participant's scratch
     * @param wavefront whether the row runs in the wavefront, waiting for the row above
     */
    private void row(Vp9EncPicture picture, byte[] lastPlane, byte[] lastSourcePlane, byte[] goldenPlane, int mbRow,
                     Scratch sc, boolean wavefront) {
        var edsp = Vp9EncDsp.INSTANCE;
        var src = picture.planes[0];
        var rec = recon.planes[0];
        var stride = picture.strides[0];
        var search = sc.search;
        var ints = rowInts;
        var base = mbRow * ROW_INTS;
        var doubles = rowDoubles;
        var dbase = mbRow * ROW_DOUBLES;
        var rowMin = -((mbRow * 16) + BORDER_MV);
        var rowMax = ((mbRows - 1 - mbRow) * 16) + BORDER_MV;
        var bestRow = 0;
        var bestCol = 0;
        var lastNonzeroRow = 0;
        var lastNonzeroCol = 0;
        for (var mbCol = 0; mbCol < mbCols; mbCol++) {
            if (wavefront && mbRow > 0) {
                progress.awaitProgress(mbRow - 1, mbCol + 1);
            }
            var bw = 2 * mbCol + 1 < miCols ? 16 : 8;
            var bh = 2 * mbRow + 1 < miRows ? 16 : 8;
            var x0 = mbCol * 16;
            var y0 = mbRow * 16;
            var srcOff = picture.offset(0, x0, y0);
            var useDc = (mbCol > 0 || mbRow > 0) && (mbCol == 0 || mbRow == 0);
            var thisError = encodeIntra(sc, src, rec, srcOff, stride, bw, bh, mbRow > 0, mbCol > 0, useDc);
            var thisIntraError = thisError;
            if (thisError < UL_INTRA_THRESH) {
                ints[base + 9]++;
            } else if (mbCol > 0) {
                ints[base + 17] = 1;
            }
            if (thisError < SMOOTH_INTRA_THRESH) {
                ints[base + 10]++;
            }
            if (lastPlane == null) {
                if (thisIntraError < LOW_I_THRESH) {
                    ints[base + 3] += blockNoise(src, srcOff, stride, bw, bh);
                } else {
                    ints[base + 3] += (long) SECTION_NOISE_DEF;
                }
            }
            var logIntra = StrictMath.log(thisError + 1.0);
            doubles[dbase] += logIntra < 10.0 ? 1.0 + (10.0 - logIntra) * 0.05 : 1.0;
            var level = src[srcOff] & 0xFF;
            doubles[dbase + 1] += level < DARK_THRESH && logIntra < 9.0 ? 1.0 + 0.01 * (DARK_THRESH - level) : 1.0;
            thisError += INTRA_MODE_PENALTY;
            ints[base] += thisError;
            var colMin = -((mbCol * 16) + BORDER_MV);
            var colMax = ((mbCols - 1 - mbCol) * 16) + BORDER_MV;
            if (lastPlane != null) {
                var motionError = sse(edsp, src, srcOff, stride, lastPlane, srcOff, bw, bh);
                var thisMotionError = motionError;
                var rawMotionError = sse(edsp, src, srcOff, stride, lastSourcePlane, srcOff, bw, bh);
                var mvRow = 0;
                var mvCol = 0;
                if (rawMotionError > NZ_MOTION_PENALTY) {
                    search[0] = motionError;
                    motionSearch(edsp, src, srcOff, stride, lastPlane, bw, bh, bestRow, bestCol, rowMin, rowMax, colMin,
                            colMax, search);
                    if (search[0] < motionError) {
                        motionError = search[0];
                        mvRow = search[1];
                        mvCol = search[2];
                    }
                    thisMotionError = variance(edsp, src, srcOff, stride, lastPlane, srcOff + mvRow * stride + mvCol,
                            bw, bh);
                    if (bestRow != 0 || bestCol != 0) {
                        search[0] = Integer.MAX_VALUE;
                        motionSearch(edsp, src, srcOff, stride, lastPlane, bw, bh, 0, 0, rowMin, rowMax, colMin, colMax,
                                search);
                        if (search[0] < motionError) {
                            motionError = search[0];
                            mvRow = search[1];
                            mvCol = search[2];
                            thisMotionError = variance(edsp, src, srcOff, stride, lastPlane,
                                    srcOff + mvRow * stride + mvCol, bw, bh);
                        }
                    }
                    if (goldenPlane != null) {
                        search[0] = sse(edsp, src, srcOff, stride, goldenPlane, srcOff, bw, bh);
                        motionSearch(edsp, src, srcOff, stride, goldenPlane, bw, bh, 0, 0, rowMin, rowMax, colMin,
                                colMax, search);
                        var gfError = search[0];
                        if (gfError < motionError && gfError < thisError) {
                            ints[base + 5]++;
                        }
                        ints[base + 2] += Math.min(gfError, thisError);
                    } else {
                        ints[base + 2] += motionError;
                    }
                } else {
                    ints[base + 2] += motionError;
                }
                bestRow = 0;
                bestCol = 0;
                if (motionError <= thisError) {
                    if ((thisError - INTRA_MODE_PENALTY) * 9L <= motionError * 10L
                            && thisError < 2 * INTRA_MODE_PENALTY) {
                        doubles[dbase + 2] += 1.0;
                    } else if (thisError > NCOUNT_INTRA_THRESH && thisError < (long) NCOUNT_INTRA_FACTOR * motionError) {
                        doubles[dbase + 2] += (double) motionError / Math.max(thisError, 1e-6);
                    }
                    var r8 = mvRow * 8;
                    var c8 = mvCol * 8;
                    thisError = motionError;
                    ints[base + 12] += r8;
                    ints[base + 13] += Math.abs(r8);
                    ints[base + 14] += c8;
                    ints[base + 15] += Math.abs(c8);
                    ints[base + 18] += (long) r8 * r8;
                    ints[base + 19] += (long) c8 * c8;
                    ints[base + 4]++;
                    bestRow = r8;
                    bestCol = c8;
                    if (r8 != 0 || c8 != 0) {
                        ints[base + 8]++;
                        if (r8 != lastNonzeroRow || c8 != lastNonzeroCol) {
                            ints[base + 11]++;
                        }
                        lastNonzeroRow = r8;
                        lastNonzeroCol = c8;
                        if (mbRow < mbRows / 2) {
                            ints[base + 20] += r8 > 0 ? -1 : r8 < 0 ? 1 : 0;
                        } else if (mbRow > mbRows / 2) {
                            ints[base + 20] += r8 > 0 ? 1 : r8 < 0 ? -1 : 0;
                        }
                        if (mbCol < mbCols / 2) {
                            ints[base + 20] += c8 > 0 ? -1 : c8 < 0 ? 1 : 0;
                        } else if (mbCol > mbCols / 2) {
                            ints[base + 20] += c8 > 0 ? 1 : c8 < 0 ? -1 : 0;
                        }
                    }
                    ints[base + 3] += thisIntraError < LOW_I_THRESH ? blockNoise(src, srcOff, stride, bw, bh)
                            : (long) SECTION_NOISE_DEF;
                    encodeInter(sc, src, rec, lastPlane, srcOff, srcOff + mvRow * stride + mvCol, stride, bw, bh);
                } else if (thisIntraError < LOW_I_THRESH) {
                    ints[base + 3] += blockNoise(src, srcOff, stride, bw, bh);
                    if (thisMotionError < LOW_I_THRESH) {
                        ints[base + 6]++;
                    } else {
                        ints[base + 7]++;
                    }
                } else {
                    ints[base + 3] += (long) SECTION_NOISE_DEF;
                    ints[base + 7]++;
                }
            } else {
                ints[base + 2] += thisError;
            }
            ints[base + 1] += thisError;
            if (wavefront) {
                progress.publish(mbRow, mbCol + 1);
            }
        }
        if (wavefront) {
            progress.publish(mbRow, Integer.MAX_VALUE >> 1);
        }
    }

    /**
     * Predicts a block intra with DC predictions from the reconstruction of its neighbors, codes
     * and reconstructs it and returns the squared error of the predictions: one prediction per
     * 16x16 or 8x8 transform block from the single available edge for a block on the picture's
     * first row or column but not both, else one per 4x4 block ({@code vp9_encode_intra_block_plane}
     * with {@code DC_PRED}).
     *
     * @param sc the participant's scratch
     * @param src the source luma
     * @param rec the reconstruction luma, in the same layout
     * @param off the index of the block
     * @param stride the row stride
     * @param bw the block width
     * @param bh the block height
     * @param above whether the row above the block lies inside the picture
     * @param left whether the column left of the block lies inside the picture
     * @param edgeDc whether the block uses the single edge prediction
     * @return the squared error
     */
    private static int encodeIntra(Scratch sc, byte[] src, byte[] rec, int off, int stride, int bw, int bh,
                                   boolean above, boolean left, boolean edgeDc) {
        var tx = edgeDc ? (bw == 16 && bh == 16 ? 16 : 8) : 4;
        var txSize = tx == 16 ? Vp9Constants.TX_16X16 : tx == 8 ? Vp9Constants.TX_8X8 : Vp9Constants.TX_4X4;
        var err = 0;
        for (var by = 0; by < bh; by += tx) {
            for (var bx = 0; bx < bw; bx += tx) {
                var o = off + by * stride + bx;
                var dc = dcPrediction(rec, o, stride, tx, above || by > 0, left || bx > 0);
                var d = sc.diff;
                for (var y = 0; y < tx; y++) {
                    var r = o + y * stride;
                    for (var x = 0; x < tx; x++) {
                        var v = (src[r + x] & 0xFF) - dc;
                        d[y * tx + x] = (short) v;
                        err += v * v;
                        rec[r + x] = (byte) dc;
                    }
                }
                codeBlock(sc, rec, o, stride, txSize);
            }
        }
        return err;
    }

    /**
     * Predicts a block from a full-sample vector into the previous reconstruction, then codes and
     * reconstructs it with 4x4 transforms ({@code vp9_encode_sby_pass1}).
     *
     * @param sc the participant's scratch
     * @param src the source luma
     * @param rec the reconstruction luma
     * @param ref the reference luma, in the same layout
     * @param off the index of the block
     * @param refOff the index of the block's prediction in {@code ref}
     * @param stride the row stride
     * @param bw the block width
     * @param bh the block height
     */
    private static void encodeInter(Scratch sc, byte[] src, byte[] rec, byte[] ref, int off, int refOff, int stride,
                                    int bw, int bh) {
        var d = sc.diff;
        for (var by = 0; by < bh; by += 4) {
            for (var bx = 0; bx < bw; bx += 4) {
                var o = off + by * stride + bx;
                var p = refOff + by * stride + bx;
                for (var y = 0; y < 4; y++) {
                    for (var x = 0; x < 4; x++) {
                        var pred = ref[p + y * stride + x];
                        d[y * 4 + x] = (short) ((src[o + y * stride + x] & 0xFF) - (pred & 0xFF));
                        rec[o + y * stride + x] = pred;
                    }
                }
                codeBlock(sc, rec, o, stride, Vp9Constants.TX_4X4);
            }
        }
    }

    /**
     * Transforms and quantizes the residual in the scratch at the first pass's quantizer and adds
     * its reconstruction to the prediction already in place ({@code vp9_xform_quant} and the
     * inverse transform).
     *
     * @param sc the participant's scratch, whose residual holds the block's rows contiguously
     * @param rec the reconstruction luma holding the prediction
     * @param off the index of the block
     * @param stride the row stride
     * @param txSize the transform size, 4x4 to 16x16
     */
    private static void codeBlock(Scratch sc, byte[] rec, int off, int stride, int txSize) {
        var edsp = Vp9EncDsp.INSTANCE;
        var size = 4 << txSize;
        switch (txSize) {
            case Vp9Constants.TX_4X4 -> edsp.fdct4x4(sc.diff, 0, size, sc.coeff, 0, sc.fdct);
            case Vp9Constants.TX_8X8 -> edsp.fdct8x8(sc.diff, 0, size, sc.coeff, 0, sc.fdct);
            default -> edsp.fdct16x16(sc.diff, 0, size, sc.coeff, 0, sc.fdct, sc.fdctInts);
        }
        var q = QUANT;
        var eob = Vp9Quantizer.quantizeB(sc.coeff, 0, size * size, sc.qcoeff, sc.dqcoeff,
                Vp9RtCoder.SCANS[txSize * 4], q.yZbin, q.yRound, q.yQuant, q.yQuantShift, q.yDequant,
                FIRST_PASS_QINDEX * 2, false);
        if (eob > 0) {
            sc.dsp.inverseTransformAdd(txSize, Vp9Constants.DCT_DCT, eob, sc.dqcoeff, 0, rec, off, stride, sc.idct);
        }
    }

    /**
     * Returns the DC prediction of a block from the reconstruction of its neighbors
     * ({@code vpx_dc_predictor} and its edge variants).
     *
     * @param rec the reconstruction luma
     * @param o the index of the block
     * @param stride the row stride
     * @param n the block size
     * @param above whether the row above is available
     * @param left whether the column on the left is available
     * @return the prediction
     */
    private static int dcPrediction(byte[] rec, int o, int stride, int n, boolean above, boolean left) {
        var sum = 0;
        var count = 0;
        if (above) {
            for (var i = 0; i < n; i++) {
                sum += rec[o - stride + i] & 0xFF;
            }
            count += n;
        }
        if (left) {
            for (var i = 0; i < n; i++) {
                sum += rec[o + i * stride - 1] & 0xFF;
            }
            count += n;
        }
        return count == 0 ? 128 : (sum + (count >> 1)) / count;
    }

    /**
     * Returns the sum of squared differences between two blocks.
     *
     * @param edsp the kernels
     * @param a the first plane
     * @param aOff the first block
     * @param stride the row stride of both planes
     * @param b the second plane
     * @param bOff the second block
     * @param w the width
     * @param h the height
     * @return the sum, capped at {@link Integer#MAX_VALUE}
     */
    private static int sse(Vp9EncDsp edsp, byte[] a, int aOff, int stride, byte[] b, int bOff, int w, int h) {
        return (int) Math.min(Integer.MAX_VALUE, Vp9EncDsp.sse(edsp.variance(a, aOff, stride, b, bOff, stride, w, h)));
    }

    /**
     * Returns the variance of the difference between two blocks ({@code vp9_get_mvpred_var}
     * without the vector cost).
     *
     * @param edsp the kernels
     * @param a the first plane
     * @param aOff the first block
     * @param stride the row stride of both planes
     * @param b the second plane
     * @param bOff the second block
     * @param w the width
     * @param h the height
     * @return the variance
     */
    private static int variance(Vp9EncDsp edsp, byte[] a, int aOff, int stride, byte[] b, int bOff, int w, int h) {
        var log2 = Integer.numberOfTrailingZeros(w) + Integer.numberOfTrailingZeros(h);
        return (int) Math.min(Integer.MAX_VALUE, Vp9EncDsp.variance(edsp.variance(a, aOff, stride, b, bOff, stride, w,
                h), log2));
    }

    /**
     * Searches a reference for the block with diamond searches at every scale from the first
     * pass's, and lowers the best error when a vector beats it ({@code first_pass_motion_search}).
     *
     * @param edsp the kernels
     * @param src the luma
     * @param off the index of the block
     * @param stride the row stride
     * @param ref the reference luma, in the same layout
     * @param bw the block width
     * @param bh the block height
     * @param refRow the eighth-sample row of the vector the search starts from and costs against
     * @param refCol the eighth-sample column of that vector
     * @param rowMin the smallest full-sample row
     * @param rowMax the largest full-sample row
     * @param colMin the smallest full-sample column
     * @param colMax the largest full-sample column
     * @param best holds the best error, then the vector's full-sample row and column, updated
     * when the search finds a better vector
     */
    private void motionSearch(Vp9EncDsp edsp, byte[] src, int off, int stride, byte[] ref, int bw, int bh, int refRow,
                              int refCol, int rowMin, int rowMax, int colMin, int colMax, int[] best) {
        var startRow = Math.clamp(refRow >> 3, rowMin, rowMax);
        var startCol = Math.clamp(refCol >> 3, colMin, colMax);
        var centerRow = refRow >> 3;
        var centerCol = refCol >> 3;
        var startSad = edsp.sad(src, off, stride, ref, off + startRow * stride + startCol, stride, bw, bh)
                + sadCost(startRow - centerRow, startCol - centerCol);
        var furtherSteps = STEPS - 1 - stepParam;
        var n = -1;
        var num00 = 0;
        while (n < furtherSteps) {
            if (n >= 0) {
                n++;
                if (num00 > 0) {
                    num00--;
                    continue;
                }
            }
            var first = n < 0;
            if (first) {
                n = 0;
            }
            var bestRow = startRow;
            var bestCol = startCol;
            var bestSad = startSad;
            num00 = 0;
            for (var step = stepParam + n; step < STEPS; step++) {
                var len = 1 << (STEPS - 1 - step);
                var site = -1;
                for (var j = 0; j < 8; j++) {
                    var r = bestRow + SITE_ROWS[j] * len;
                    var c = bestCol + SITE_COLS[j] * len;
                    if (r < rowMin || r > rowMax || c < colMin || c > colMax) {
                        continue;
                    }
                    var sad = edsp.sad(src, off, stride, ref, off + r * stride + c, stride, bw, bh);
                    if (sad < bestSad) {
                        sad += sadCost(r - centerRow, c - centerCol);
                        if (sad < bestSad) {
                            bestSad = sad;
                            site = j;
                        }
                    }
                }
                if (site >= 0) {
                    bestRow += SITE_ROWS[site] * len;
                    bestCol += SITE_COLS[site] * len;
                } else if (bestRow == startRow && bestCol == startCol) {
                    num00++;
                }
            }
            var err = sse(edsp, src, off, stride, ref, off + bestRow * stride + bestCol, bw, bh)
                    + mvErrCost(bestRow * 8 - refRow, bestCol * 8 - refCol);
            if (err < Integer.MAX_VALUE - NEW_MV_MODE_PENALTY) {
                err += NEW_MV_MODE_PENALTY;
            }
            if (err < best[0]) {
                best[0] = err;
                best[1] = bestRow;
                best[2] = bestCol;
            }
            if (first) {
                n = num00;
                num00 = 0;
            }
        }
    }

    /**
     * Returns the cost of a full-sample vector difference in the sum of absolute differences
     * ({@code mvsad_err_cost}).
     *
     * @param row the row difference
     * @param col the column difference
     * @return the cost
     */
    private int sadCost(int row, int col) {
        var cost = Vp9EncMv.JOINT_SAD_COST[Vp9EncMv.joint(row, col)] + Vp9EncMv.SAD_COST[Vp9EncMv.MV_MAX + row]
                + Vp9EncMv.SAD_COST[Vp9EncMv.MV_MAX + col];
        return (int) (((long) cost * sadPerBit + (1 << 8)) >> 9);
    }

    /**
     * Returns the cost of an eighth-sample vector difference in the squared error
     * ({@code mv_err_cost}).
     *
     * @param row the row difference
     * @param col the column difference
     * @return the cost
     */
    private int mvErrCost(int row, int col) {
        var mr = Math.clamp(row, -Vp9EncMv.MV_MAX, Vp9EncMv.MV_MAX);
        var mc = Math.clamp(col, -Vp9EncMv.MV_MAX, Vp9EncMv.MV_MAX);
        var cost = jointCost[Vp9EncMv.joint(mr, mc)] + rowCost[Vp9EncMv.MV_MAX + mr] + colCost[Vp9EncMv.MV_MAX + mc];
        return (int) (((long) cost * errorPerBit + (1L << 13)) >> 14);
    }

    /**
     * Returns the noise energy of a block from the effect of a 3x3 smoothing kernel on every
     * other sample ({@code fp_estimate_block_noise}).
     *
     * @param src the luma
     * @param off the index of the block
     * @param stride the row stride
     * @param bw the block width
     * @param bh the block height
     * @return the energy
     */
    private static int blockNoise(byte[] src, int off, int stride, int bw, int bh) {
        var noise = 0;
        for (var y = 0; y < bh; y += 2) {
            for (var x = 0; x < bw; x += 2) {
                noise += pointNoise(src, off + y * stride + x, stride);
            }
        }
        return noise << 2;
    }

    /**
     * Returns the noise energy of one sample: the squared difference between the sample and its
     * smoothed value ({@code fp_estimate_point_noise}).
     *
     * @param src the luma
     * @param o the index of the sample
     * @param stride the row stride
     * @return the energy
     */
    private static int pointNoise(byte[] src, int o, int stride) {
        var center = src[o] & 0xFF;
        var sumWeight = 0;
        var sumVal = 0;
        var maxDiff = 0;
        var k = 0;
        for (var i = -1; i <= 1; i++) {
            for (var j = -1; j <= 1; j++) {
                var v = src[o + i * stride + j] & 0xFF;
                var diff = Math.abs(center - v);
                maxDiff = Math.max(maxDiff, diff);
                if (diff <= 8) {
                    sumWeight += NOISE_KERNEL[k];
                    sumVal += v * NOISE_KERNEL[k];
                }
                k++;
            }
        }
        var dn = maxDiff < 24 ? (sumVal + (sumWeight >> 1)) / sumWeight : center;
        var d = center - dn;
        return d * d;
    }
}
