package com.github.auties00.smalt.video.av1.internal.encoder;

import com.github.auties00.smalt.video.av1.internal.Cdf;
import com.github.auties00.smalt.video.av1.internal.EncoderBridge;
import com.github.auties00.smalt.video.av1.internal.FrameContext;
import com.github.auties00.smalt.video.av1.internal.FrameHeader;
import com.github.auties00.smalt.video.av1.internal.LoopRestorationDsp;
import com.github.auties00.smalt.video.av1.internal.LrParams;
import com.github.auties00.smalt.video.av1.internal.RestorationUnit;
import com.github.auties00.smalt.video.av1.internal.SequenceHeader;
import com.github.auties00.smalt.video.av1.internal.Tables;
import com.github.auties00.smalt.video.av1.internal.TaskContext;

import java.util.Arrays;

/**
 * Chooses the loop restoration of a still frame after CDEF: for every plane the frame-level
 * restoration type and for every restoration unit its Wiener filter or self-guided projection,
 * by rate-distortion cost against the source.
 *
 * <p>For each unit the search derives a separable symmetric Wiener filter from the unit's
 * correlation statistics and refines its taps, and picks the self-guided parameter set and
 * projection weights with the least squared error. Every candidate is measured by restoring the
 * unit exactly as the decoder does, stripes and saved boundary lines included. Units are visited
 * in coding order so the delta-coded parameters are costed against the right reference. The
 * frame-level type of each plane (none, Wiener, self-guided or switchable) is the cheapest total.
 *
 * <p>After {@link #pick()} the frame header carries the restoration types and unit sizes, the
 * frame context the per-unit parameters, and the reconstruction is restored; {@link #write}
 * codes the units of a superblock in the decoder's order.
 *
 * @implNote This implementation ports libaom's {@code av1/encoder/pickrst.c}: the
 * {@code compute_stats}, {@code wiener_decompose_sep_sym}, {@code linsolve_wiener},
 * {@code finalize_sym_filter}, {@code compute_score} and {@code finer_search_wiener}
 * Wiener search, the {@code search_selfguided_restoration}, {@code get_proj_subspace},
 * {@code encode_xq} and {@code finer_search_pixel_proj_error} self-guided search with its
 * parameter pruning, {@code search_switchable} and the frame type selection of
 * {@code av1_pick_filter_restoration}, and the self-guided filter of
 * {@code av1_selfguided_restoration_c} ({@code av1/common/restoration.c}) that yields
 * the two unprojected filter outputs. The Wiener correlation matrix is accumulated from
 * summed-area tables of the products of shifted samples rather than per sample, which
 * yields the same sums.
 */
final class RestorationSearch {
    /**
     * The extra precision bits of the self-guided filter outputs ({@code SGRPROJ_RST_BITS}).
     */
    private static final int RST_BITS = 4;

    /**
     * The precision bits of the projection weights ({@code SGRPROJ_PRJ_BITS}).
     */
    private static final int PRJ_BITS = 7;

    /**
     * The smallest and largest first projection weight.
     */
    private static final int PRJ_MIN0 = -96;

    /**
     * The largest first projection weight.
     */
    private static final int PRJ_MAX0 = 31;

    /**
     * The smallest second projection weight.
     */
    private static final int PRJ_MIN1 = -32;

    /**
     * The largest second projection weight.
     */
    private static final int PRJ_MAX1 = 95;

    /**
     * The smallest value of each coded Wiener tap.
     */
    private static final int[] TAP_MIN = {-5, -23, -17};

    /**
     * The largest value of each coded Wiener tap.
     */
    private static final int[] TAP_MAX = {10, 8, 46};

    /**
     * The sub-exponential parameter of each coded Wiener tap.
     */
    private static final int[] TAP_K = {1, 2, 3};

    /**
     * The reference taps each tile starts from.
     */
    private static final int[] TAP_DEFAULT = {3, -7, 15};

    /**
     * The fixed-point scale of the Wiener taps ({@code WIENER_FILT_STEP}).
     */
    private static final int FILT_STEP = 128;

    /**
     * The working precision of the Wiener taps during the decomposition.
     */
    private static final long TAP_SCALE = 1L << 16;

    /**
     * The alternating least-squares iterations of the Wiener decomposition.
     */
    private static final int WIENER_ITERS = 5;

    /**
     * The radii of the self-guided passes of each parameter set: 5x5 pass, 3x3 pass.
     */
    private static final int[][] SGR_RADII = {
            {2, 1}, {2, 1}, {2, 1}, {2, 1}, {2, 1}, {2, 1}, {2, 1}, {2, 1}, {2, 1}, {2, 1},
            {0, 1}, {0, 1}, {0, 1}, {0, 1}, {2, 0}, {2, 0}
    };

    /**
     * The parameter sets the pruned self-guided search starts from.
     */
    private static final int[] SGR_SEEDS = {0, 3, 6, 9};

    /**
     * The single-pass parameter sets the pruned search tries after the best seed, indexed by it.
     */
    private static final int[][] SGR_GROUPS = {
            {10, 10, 11, 11, 12, 12, 13, 13, 13, 13, -1, -1, -1, -1},
            {14, 14, 14, 14, 14, 14, 14, 15, 15, 15, 15, 15, 15, 15}
    };

    /**
     * The blend factor of the self-guided filter for each variance score ({@code av1_x_by_xplus1}).
     */
    private static final int[] X_BY_XPLUS1 = {
            1, 128, 171, 192, 205, 213, 219, 224, 228, 230, 233, 235, 236, 238, 239,
            240, 241, 242, 243, 243, 244, 244, 245, 245, 246, 246, 247, 247, 247, 247,
            248, 248, 248, 248, 249, 249, 249, 249, 249, 250, 250, 250, 250, 250, 250,
            250, 251, 251, 251, 251, 251, 251, 251, 251, 251, 251, 252, 252, 252, 252,
            252, 252, 252, 252, 252, 252, 252, 252, 252, 252, 252, 252, 252, 253, 253,
            253, 253, 253, 253, 253, 253, 253, 253, 253, 253, 253, 253, 253, 253, 253,
            253, 253, 253, 253, 253, 253, 253, 253, 253, 253, 253, 253, 254, 254, 254,
            254, 254, 254, 254, 254, 254, 254, 254, 254, 254, 254, 254, 254, 254, 254,
            254, 254, 254, 254, 254, 254, 254, 254, 254, 254, 254, 254, 254, 254, 254,
            254, 254, 254, 254, 254, 254, 254, 254, 254, 254, 254, 254, 254, 254, 254,
            254, 254, 254, 254, 254, 254, 254, 254, 254, 254, 254, 254, 254, 254, 254,
            254, 254, 254, 254, 254, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255,
            255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255,
            255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255,
            255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255,
            255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255,
            255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255,
            256,
    };

    /**
     * The reciprocals {@code round(4096 / n)} of the box sizes 1 to 25 ({@code av1_one_by_x}).
     */
    private static final int[] ONE_BY_X = {
            4096, 2048, 1365, 1024, 819, 683, 585, 512, 455, 410, 372, 341, 315,
            293, 273, 256, 241, 228, 216, 205, 195, 186, 178, 171, 164,
    };

    /**
     * The largest restoration unit side, 1.5 times the largest unit size.
     */
    private static final int MAX_UNIT = 384;

    /**
     * The frame encoder.
     */
    private final StillFrameEncoder frame;

    /**
     * The frame context.
     */
    private final FrameContext f;

    /**
     * The restoration kernels.
     */
    private final LoopRestorationDsp dsp;

    /**
     * The symbol cost counter.
     */
    private final RangeEncoder counter = new RangeEncoder();

    /**
     * The kernel parameters.
     */
    private final LrParams params = new LrParams();

    /**
     * The kernel scratch.
     */
    private final int[] lrScratch = new int[LoopRestorationDsp.SCRATCH_INTS];

    /**
     * The four columns left of the unit, four per row of a stripe.
     */
    private final short[] left = new short[(LoopRestorationDsp.MAX_HEIGHT + 8) * 4];

    /**
     * The copy of one stripe of a unit the kernels restore, with the plane's stride.
     */
    private short[] stripe = new short[0];

    /**
     * The first self-guided output of a unit.
     */
    private final int[] flt0 = new int[MAX_UNIT * MAX_UNIT];

    /**
     * The second self-guided output of a unit.
     */
    private final int[] flt1 = new int[MAX_UNIT * MAX_UNIT];

    /**
     * The unit's degraded samples with a three-sample border, for the self-guided filter.
     */
    private final int[] dgd32 = new int[(MAX_UNIT + 6) * (MAX_UNIT + 6)];

    /**
     * The box sums of the self-guided filter.
     */
    private final int[] boxA = new int[(MAX_UNIT + 6) * (MAX_UNIT + 6)];

    /**
     * The box sums of squares of the self-guided filter.
     */
    private final int[] boxB = new int[(MAX_UNIT + 6) * (MAX_UNIT + 6)];

    /**
     * The degraded samples minus their mean around a unit, for the Wiener statistics.
     */
    private final int[] centered = new int[(MAX_UNIT + 12) * (MAX_UNIT + 12)];

    /**
     * The summed-area table of one shifted product image.
     */
    private final long[] area = new long[(MAX_UNIT + 13) * (MAX_UNIT + 13)];

    /**
     * The Wiener cross-correlation vector.
     */
    private final long[] m = new long[49];

    /**
     * The Wiener auto-correlation matrix.
     */
    private final long[] h = new long[49 * 49];

    /**
     * The searched parameters of each unit of the plane being searched.
     */
    private Unit[] units = new Unit[0];

    /**
     * Creates the search of a frame whose reconstruction has been deblocked and CDEF-filtered.
     *
     * @param frame the frame encoder
     */
    RestorationSearch(StillFrameEncoder frame) {
        this.frame = frame;
        this.f = frame.f;
        this.dsp = f.lr;
    }

    /**
     * Holds the search results of one restoration unit.
     */
    private static final class Unit {
        /**
         * The left column, inclusive.
         */
        int x0;

        /**
         * The right column, exclusive.
         */
        int x1;

        /**
         * The top row, inclusive, moved up to the restoration stripes.
         */
        int y0;

        /**
         * The bottom row, exclusive, moved up to the restoration stripes.
         */
        int y1;

        /**
         * The squared error without, with the Wiener and with the self-guided restoration, at the
         * 8-bit scale; {@link Long#MAX_VALUE} when a filter was not found.
         */
        final long[] sse = new long[3];

        /**
         * The vertical Wiener taps 0 to 2.
         */
        final int[] wienerV = new int[3];

        /**
         * The horizontal Wiener taps 0 to 2.
         */
        final int[] wienerH = new int[3];

        /**
         * The self-guided parameter set.
         */
        int sgrSet;

        /**
         * The self-guided projection weights as coded.
         */
        final int[] xqd = new int[2];

        /**
         * The chosen type for each frame type (Wiener, self-guided, switchable), as a
         * {@link FrameHeader} restoration type.
         */
        final int[] best = new int[3];
    }

    /**
     * Holds the delta-coding references of one plane.
     */
    private static final class Reference {
        /**
         * The vertical Wiener taps.
         */
        final int[] wienerV = new int[3];

        /**
         * The horizontal Wiener taps.
         */
        final int[] wienerH = new int[3];

        /**
         * The self-guided projection weights.
         */
        final int[] xqd = new int[2];

        /**
         * Creates the references a tile starts from.
         */
        Reference() {
            reset();
        }

        /**
         * Resets the references to their tile defaults.
         */
        void reset() {
            System.arraycopy(TAP_DEFAULT, 0, wienerV, 0, 3);
            System.arraycopy(TAP_DEFAULT, 0, wienerH, 0, 3);
            xqd[0] = -32;
            xqd[1] = 31;
        }

        /**
         * Copies the Wiener taps of a unit.
         *
         * @param u the unit
         */
        void setWiener(Unit u) {
            System.arraycopy(u.wienerV, 0, wienerV, 0, 3);
            System.arraycopy(u.wienerH, 0, wienerH, 0, 3);
        }

        /**
         * Copies the projection weights of a unit.
         *
         * @param u the unit
         */
        void setSgr(Unit u) {
            xqd[0] = u.xqd[0];
            xqd[1] = u.xqd[1];
        }
    }

    /**
     * Searches the restoration of every plane, records it in the frame header and context and
     * restores the reconstruction.
     */
    void pick() {
        // TODO: search the restoration units of a plane in parallel; units only depend on their
        // references, which can be costed in coding order afterwards.
        var hdr = f.frameHdr;
        var planes = frame.layout == SequenceHeader.LAYOUT_I400 ? 1 : 3;
        var sizes = unitSizes();
        var bestCost = Double.MAX_VALUE;
        int[] bestTypes = null;
        RestorationUnit[][] bestUnits = null;
        var bestSize = sizes[0];
        for (var size : sizes) {
            var types = new int[3];
            var chosen = new RestorationUnit[3][];
            var cost = 0.0;
            var allNone = true;
            for (var p = 0; p < planes; p++) {
                var result = searchPlane(p, size);
                types[p] = result.type;
                chosen[p] = result.units;
                cost += result.cost;
                allNone &= result.type == FrameHeader.RESTORATION_NONE;
            }
            if (cost < bestCost) {
                bestCost = cost;
                bestTypes = types;
                bestUnits = chosen;
                bestSize = size;
                if (allNone) {
                    break;
                }
            } else {
                break;
            }
        }
        var log2 = 31 - Integer.numberOfLeadingZeros(bestSize);
        hdr.restorationUnitSize[0] = log2;
        hdr.restorationUnitSize[1] = log2;
        for (var p = 0; p < 3; p++) {
            hdr.restorationType[p] = p < planes ? bestTypes[p] : FrameHeader.RESTORATION_NONE;
        }
        var restorePlanes = 0;
        for (var p = 0; p < planes; p++) {
            if (bestTypes[p] == FrameHeader.RESTORATION_NONE) {
                continue;
            }
            restorePlanes |= 1 << p;
            var ss = subsampling(p);
            var unitsPlane = bestUnits[p];
            var cols = count(planeWidth(p), bestSize);
            for (var i = 0; i < unitsPlane.length; i++) {
                var target = unitAt(p, i % cols, i / cols, bestSize, ss);
                var src = unitsPlane[i];
                target.type = src.type;
                System.arraycopy(src.filterH, 0, target.filterH, 0, 3);
                System.arraycopy(src.filterV, 0, target.filterV, 0, 3);
                target.sgrWeights[0] = src.sgrWeights[0];
                target.sgrWeights[1] = src.sgrWeights[1];
            }
        }
        f.restorePlanes = restorePlanes;
        if (restorePlanes != 0) {
            var task = new TaskContext();
            task.f = f;
            for (var sby = 0; sby < f.sbh; sby++) {
                EncoderBridge.lrSbrow(task, f, sby);
            }
        }
    }

    /**
     * Returns the luma restoration unit sizes to try, largest first.
     *
     * @return the sizes
     */
    private int[] unitSizes() {
        var settings = frame.settings;
        var large = Math.min(frame.width, frame.height) >= 1440;
        if (settings.restorationSizeSearch) {
            var medium = Math.min(frame.width, frame.height) >= 720;
            return large ? new int[]{256} : medium ? new int[]{256, 128} : new int[]{256, 128, 64};
        }
        return settings.qindex <= 96 && !large ? new int[]{128} : new int[]{256};
    }

    /**
     * Holds the outcome of the search of one plane.
     *
     * @param type the frame-level restoration type
     * @param units the parameters of every unit in raster order, or {@code null} for none
     * @param cost the rate-distortion cost of the plane's restoration
     */
    private record PlaneResult(int type, RestorationUnit[] units, double cost) {
    }

    /**
     * Searches the restoration of one plane at a unit size.
     *
     * @param p the plane
     * @param size the unit size in the plane's samples
     * @return the frame-level type, the unit parameters and the cost
     */
    private PlaneResult searchPlane(int p, int size) {
        var ss = subsampling(p);
        var w = planeWidth(p);
        var hgt = planeHeight(p);
        var cols = count(w, size);
        var rows = count(hgt, size);
        var n = cols * rows;
        if (units.length < n) {
            units = new Unit[n];
            for (var i = 0; i < n; i++) {
                units[i] = new Unit();
            }
        }
        var offset = 8 >> (ss & 1);
        for (var r = 0; r < rows; r++) {
            for (var c = 0; c < cols; c++) {
                var u = units[r * cols + c];
                u.x0 = c * size;
                u.x1 = c == cols - 1 ? w : (c + 1) * size;
                var y0 = r * size;
                var y1 = r == rows - 1 ? hgt : (r + 1) * size;
                u.y0 = Math.max(0, y0 - offset);
                u.y1 = y1 < hgt ? y1 - offset : y1;
            }
        }
        var totalSse = new long[4];
        var totalBits = new long[4];
        var refWiener = new Reference();
        var refSgr = new Reference();
        var refSwitch = new Reference();
        var cdf = f.inCdf;
        var bitsWiener = new long[]{boolCost(cdf, Cdf.RESTORE_WIENER, 0), boolCost(cdf, Cdf.RESTORE_WIENER, 1)};
        var bitsSgr = new long[]{boolCost(cdf, Cdf.RESTORE_SGRPROJ, 0), boolCost(cdf, Cdf.RESTORE_SGRPROJ, 1)};
        var bitsSwitch = new long[]{symbolCost(cdf, Cdf.RESTORE_SWITCHABLE, 0, 2),
                symbolCost(cdf, Cdf.RESTORE_SWITCHABLE, 1, 2), symbolCost(cdf, Cdf.RESTORE_SWITCHABLE, 2, 2)};
        var hdr = f.frameHdr;
        for (var tileRow = 0; tileRow < hdr.tilingRows; tileRow++) {
            for (var tileCol = 0; tileCol < hdr.tilingCols; tileCol++) {
                refWiener.reset();
                refSgr.reset();
                refSwitch.reset();
                var sbRow0 = hdr.rowStartSb[tileRow];
                var sbRow1 = Math.min(hdr.rowStartSb[tileRow + 1], f.sbh);
                var sbCol0 = hdr.colStartSb[tileCol];
                var sbCol1 = Math.min(hdr.colStartSb[tileCol + 1], (f.bw + 15) >> 4);
                for (var sby = sbRow0; sby < sbRow1; sby++) {
                    for (var sbx = sbCol0; sbx < sbCol1; sbx++) {
                        var yPlane = (sby * 64) >> (ss & 1);
                        var xPlane = (sbx * 64) >> (ss >> 1);
                        if ((yPlane & (size - 1)) != 0 || (xPlane & (size - 1)) != 0) {
                            continue;
                        }
                        if (yPlane != 0 && yPlane + (size >> 1) > hgt) {
                            continue;
                        }
                        if (xPlane != 0 && xPlane + (size >> 1) > w) {
                            continue;
                        }
                        var u = units[(yPlane / size) * cols + xPlane / size];
                        searchUnit(p, u, totalSse, totalBits, refWiener, refSgr, refSwitch, bitsWiener, bitsSgr,
                                bitsSwitch);
                    }
                }
            }
        }
        var rdmult = frame.rdmult;
        var bestType = FrameHeader.RESTORATION_NONE;
        var bestCost = cost(rdmult, totalBits[0], totalSse[0]);
        int[] candidates = n > 1
                ? new int[]{FrameHeader.RESTORATION_WIENER, FrameHeader.RESTORATION_SGRPROJ,
                FrameHeader.RESTORATION_SWITCHABLE}
                : new int[]{FrameHeader.RESTORATION_WIENER, FrameHeader.RESTORATION_SGRPROJ};
        for (var type : candidates) {
            var slot = slot(type);
            var c = cost(rdmult, totalBits[slot], totalSse[slot]);
            if (c < bestCost) {
                bestCost = c;
                bestType = type;
            }
        }
        if (bestType == FrameHeader.RESTORATION_NONE) {
            return new PlaneResult(bestType, null, bestCost);
        }
        var out = new RestorationUnit[n];
        var pick = bestType == FrameHeader.RESTORATION_WIENER ? 0 : bestType == FrameHeader.RESTORATION_SGRPROJ ? 1 : 2;
        for (var i = 0; i < n; i++) {
            var u = units[i];
            var lr = new RestorationUnit();
            var type = u.best[pick];
            lr.type = type == FrameHeader.RESTORATION_SGRPROJ ? FrameHeader.RESTORATION_SGRPROJ + u.sgrSet : type;
            System.arraycopy(u.wienerH, 0, lr.filterH, 0, 3);
            System.arraycopy(u.wienerV, 0, lr.filterV, 0, 3);
            lr.sgrWeights[0] = u.xqd[0];
            lr.sgrWeights[1] = u.xqd[1];
            out[i] = lr;
        }
        return new PlaneResult(bestType, out, bestCost);
    }

    /**
     * Returns the accumulator slot of a frame-level restoration type.
     *
     * @param type the restoration type
     * @return 0 for none, 1 Wiener, 2 self-guided, 3 switchable
     */
    private static int slot(int type) {
        return switch (type) {
            case FrameHeader.RESTORATION_WIENER -> 1;
            case FrameHeader.RESTORATION_SGRPROJ -> 2;
            case FrameHeader.RESTORATION_SWITCHABLE -> 3;
            default -> 0;
        };
    }

    /**
     * Returns the rate-distortion cost of a restoration choice
     * ({@code RDCOST_DBL_WITH_NATIVE_BD_DIST}).
     *
     * @param rdmult the multiplier
     * @param bits the rate in 1/512 bits
     * @param sse the squared error at the 8-bit scale
     * @return the cost
     */
    private static double cost(long rdmult, long bits, long sse) {
        return (double) (bits >> 4) * rdmult / 512.0 + (double) sse * 128;
    }

    /**
     * Searches one unit for every frame-level type and accumulates the totals of each.
     *
     * @param p the plane
     * @param u the unit
     * @param totalSse the squared error totals of none, Wiener, self-guided and switchable
     * @param totalBits the rate totals of none, Wiener, self-guided and switchable
     * @param refWiener the references of the Wiener frame type
     * @param refSgr the references of the self-guided frame type
     * @param refSwitch the references of the switchable frame type
     * @param bitsWiener the costs of the Wiener flag
     * @param bitsSgr the costs of the self-guided flag
     * @param bitsSwitch the costs of the switchable type symbol
     */
    private void searchUnit(int p, Unit u, long[] totalSse, long[] totalBits, Reference refWiener, Reference refSgr,
                            Reference refSwitch, long[] bitsWiener, long[] bitsSgr, long[] bitsSwitch) {
        var settings = frame.settings;
        var rdmult = frame.rdmult;
        var sseNone = sse(p, u, null);
        u.sse[0] = sseNone;
        totalSse[0] += sseNone;
        var skipSgr = false;
        var wienerFound = searchWiener(p, u);
        if (!wienerFound) {
            u.sse[1] = Long.MAX_VALUE;
            u.best[0] = FrameHeader.RESTORATION_NONE;
            totalBits[1] += bitsWiener[0];
            totalSse[1] += sseNone;
            skipSgr = settings.sgrFromWiener == 2;
        } else {
            var bits = bitsWiener[1] + ((long) wienerBits(p, u, refWiener) << 9);
            var costNone = cost(rdmult, bitsWiener[0], sseNone);
            var costWiener = cost(rdmult, bits, u.sse[1]);
            var use = costWiener < costNone;
            u.best[0] = use ? FrameHeader.RESTORATION_WIENER : FrameHeader.RESTORATION_NONE;
            totalBits[1] += use ? bits : bitsWiener[0];
            totalSse[1] += use ? u.sse[1] : sseNone;
            if (use) {
                refWiener.setWiener(u);
            }
            if (settings.sgrFromWiener == 1) {
                skipSgr = costWiener > 1.01 * costNone;
            } else if (settings.sgrFromWiener == 2) {
                skipSgr = !use;
            }
        }
        if (skipSgr) {
            u.sse[2] = Long.MAX_VALUE;
            u.best[1] = FrameHeader.RESTORATION_NONE;
            totalBits[2] += bitsSgr[0];
            totalSse[2] += sseNone;
        } else {
            searchSgr(p, u);
            var bits = bitsSgr[1] + ((long) sgrBits(u, refSgr) << 9);
            var costNone = cost(rdmult, bitsSgr[0], sseNone);
            var costSgr = cost(rdmult, bits, u.sse[2]);
            if (u.sgrSet < 10 && settings.dualSgrPenalty) {
                costSgr *= 1.01;
            }
            var use = costSgr < costNone;
            u.best[1] = use ? FrameHeader.RESTORATION_SGRPROJ : FrameHeader.RESTORATION_NONE;
            totalBits[2] += use ? bits : bitsSgr[0];
            totalSse[2] += use ? u.sse[2] : sseNone;
            if (use) {
                refSgr.setSgr(u);
            }
        }
        var bestCost = 0.0;
        var bestBits = 0L;
        var bestType = 0;
        for (var r = 0; r < 3; r++) {
            if (r > 0 && u.sse[r] > u.sse[0]) {
                continue;
            }
            long coeffBits = switch (r) {
                case 1 -> wienerBits(p, u, refSwitch);
                case 2 -> sgrBits(u, refSwitch);
                default -> 0;
            };
            var bits = bitsSwitch[r] + (coeffBits << 9);
            var c = cost(rdmult, bits, u.sse[r]);
            if (r == 2 && u.sgrSet < 10 && settings.dualSgrPenalty) {
                c *= 1.01;
            }
            if (r == 0 || c < bestCost) {
                bestCost = c;
                bestBits = bits;
                bestType = r;
            }
        }
        u.best[2] = bestType == 0 ? FrameHeader.RESTORATION_NONE
                : bestType == 1 ? FrameHeader.RESTORATION_WIENER : FrameHeader.RESTORATION_SGRPROJ;
        totalSse[3] += u.sse[bestType];
        totalBits[3] += bestBits;
        if (bestType == 1) {
            refSwitch.setWiener(u);
        } else if (bestType == 2) {
            refSwitch.setSgr(u);
        }
    }

    /**
     * Searches the Wiener filter of a unit, leaving its taps and squared error in the unit.
     *
     * @param p the plane
     * @param u the unit
     * @return {@code false} when no filter improves on the unrestored unit
     */
    private boolean searchWiener(int p, Unit u) {
        var settings = frame.settings;
        if (settings.wienerVarPrune > 0) {
            var scale = settings.wienerVarPrune;
            var qs = frame.quant.dequant(0, false) >> (frame.bitDepth - 8) >> 3;
            var thresh = ((long) qs * qs * scale) >> 4;
            if (u.sse[0] == 0 || sourceVariance(p, u) < thresh) {
                return false;
            }
        }
        var win = p == 0 && !settings.reducedWiener ? 7 : 5;
        stats(p, u, win);
        var a = new long[win];
        var b = new long[win];
        decompose(win, a, b);
        var vfilter = new int[7];
        var hfilter = new int[7];
        finalizeFilter(win, a, vfilter);
        finalizeFilter(win, b, hfilter);
        if (score(win, vfilter, hfilter) > 0) {
            return false;
        }
        System.arraycopy(vfilter, 0, u.wienerV, 0, 3);
        System.arraycopy(hfilter, 0, u.wienerH, 0, 3);
        u.sse[1] = refineWiener(p, u, win);
        return true;
    }

    /**
     * Returns the variance of the source samples of a unit ({@code aom_get_*_var}).
     *
     * @param p the plane
     * @param u the unit
     * @return the variance, at the 8-bit scale
     */
    private long sourceVariance(int p, Unit u) {
        var src = frame.source.data[p];
        var stride = frame.source.stride[p == 0 ? 0 : 1];
        long sum = 0;
        long sq = 0;
        var shift = frame.bitDepth - 8;
        for (var y = u.y0; y < u.y1; y++) {
            var row = y * stride;
            for (var x = u.x0; x < u.x1; x++) {
                var v = src[row + x] >> shift;
                sum += v;
                sq += (long) v * v;
            }
        }
        var n = (long) (u.x1 - u.x0) * (u.y1 - u.y0);
        return (sq - sum * sum / n) / n;
    }

    /**
     * Accumulates the Wiener statistics of a unit: the cross-correlation of the source with the
     * degraded samples in the filter window, and the auto-correlation of the window
     * ({@code av1_compute_stats}).
     *
     * @param p the plane
     * @param u the unit
     * @param win the window side, 5 or 7
     */
    private void stats(int p, Unit u, int win) {
        var dgd = f.cur.data[p];
        var ds = f.cur.stride[p == 0 ? 0 : 1];
        var src = frame.source.data[p];
        var ss = frame.source.stride[p == 0 ? 0 : 1];
        var pw = planeWidth(p);
        var ph = planeHeight(p);
        var half = win >> 1;
        var uw = u.x1 - u.x0;
        var uh = u.y1 - u.y0;
        long total = 0;
        for (var y = u.y0; y < u.y1; y++) {
            for (var x = u.x0; x < u.x1; x++) {
                total += dgd[y * ds + x];
            }
        }
        var avg = (int) (total / ((long) uw * uh));
        var border = 2 * half;
        var cw = uw + 2 * border;
        var ch = uh + 2 * border;
        for (var y = 0; y < ch; y++) {
            var sy = Math.clamp(u.y0 - border + y, 0, ph - 1);
            for (var x = 0; x < cw; x++) {
                var sx = Math.clamp(u.x0 - border + x, 0, pw - 1);
                centered[y * cw + x] = dgd[sy * ds + sx] - avg;
            }
        }
        var win2 = win * win;
        Arrays.fill(m, 0, win2, 0);
        for (var k = 0; k < win; k++) {
            for (var l = 0; l < win; l++) {
                long acc = 0;
                var dx = k - half + border;
                var dy = l - half + border;
                for (var y = 0; y < uh; y++) {
                    var srow = (u.y0 + y) * ss + u.x0;
                    var crow = (y + dy) * cw + dx;
                    for (var x = 0; x < uw; x++) {
                        acc += (long) centered[crow + x] * (src[srow + x] - avg);
                    }
                }
                m[k * win + l] = acc;
            }
        }
        var span = 2 * half;
        var aw = uw + span + 1;
        var ah = uh + span + 1;
        for (var ddx = -span; ddx <= span; ddx++) {
            for (var ddy = -span; ddy <= span; ddy++) {
                if (ddx < 0 || (ddx == 0 && ddy < 0)) {
                    continue;
                }
                Arrays.fill(area, 0, aw, 0);
                for (var y = 0; y < ah - 1; y++) {
                    var by = y + border - half;
                    long rowAcc = 0;
                    var outRow = (y + 1) * aw;
                    var prevRow = y * aw;
                    area[outRow] = 0;
                    for (var x = 0; x < aw - 1; x++) {
                        var bx = x + border - half;
                        var y2 = by + ddy;
                        var x2 = bx + ddx;
                        long prod = 0;
                        if (y2 >= 0 && y2 < ch && x2 >= 0 && x2 < cw) {
                            prod = (long) centered[by * cw + bx] * centered[y2 * cw + x2];
                        }
                        rowAcc += prod;
                        area[outRow + x + 1] = area[prevRow + x + 1] + rowAcc;
                    }
                }
                for (var k = 0; k < win; k++) {
                    var k2 = k + ddx;
                    if (k2 < 0 || k2 >= win) {
                        continue;
                    }
                    for (var l = 0; l < win; l++) {
                        var l2 = l + ddy;
                        if (l2 < 0 || l2 >= win) {
                            continue;
                        }
                        var x0 = k;
                        var y0 = l;
                        var sum = area[(y0 + uh) * aw + x0 + uw] - area[y0 * aw + x0 + uw]
                                - area[(y0 + uh) * aw + x0] + area[y0 * aw + x0];
                        var i1 = k * win + l;
                        var i2 = k2 * win + l2;
                        h[i1 * win2 + i2] = sum;
                        h[i2 * win2 + i1] = sum;
                    }
                }
            }
        }
    }

    /**
     * Decomposes the Wiener solution into a separable symmetric vertical and horizontal filter by
     * alternating least squares ({@code wiener_decompose_sep_sym}).
     *
     * @param win the window side
     * @param a receives the vertical filter, scaled by {@link #TAP_SCALE}
     * @param b receives the horizontal filter, scaled by {@link #TAP_SCALE}
     */
    private void decompose(int win, long[] a, long[] b) {
        var init = new int[]{3, -7, 15, 106, 15, -7, 3};
        var off = (7 - win) >> 1;
        for (var i = 0; i < win; i++) {
            a[i] = b[i] = TAP_SCALE / FILT_STEP * init[i + off];
        }
        for (var iter = 1; iter < WIENER_ITERS; iter++) {
            updateA(win, a, b);
            updateB(win, a, b);
        }
    }

    /**
     * Returns the index a window position folds to under the filter's symmetry.
     *
     * @param i the position
     * @param win the window side
     * @return the folded position
     */
    private static int wrap(int i, int win) {
        var half1 = (win >> 1) + 1;
        return i >= half1 ? win - 1 - i : i;
    }

    /**
     * Returns {@code x * w / TAP_SCALE} without overflowing ({@code multiply_and_scale}).
     *
     * @param x the value
     * @param w the scaled weight
     * @return the product
     */
    private static long multiplyAndScale(long x, long w) {
        var w1 = w / TAP_SCALE;
        var w2 = w - w1 * TAP_SCALE;
        return x * w1 + x * w2 / TAP_SCALE;
    }

    /**
     * Solves for the vertical filter with the horizontal one fixed ({@code update_a_sep_sym}).
     *
     * @param win the window side
     * @param a the vertical filter, updated
     * @param b the horizontal filter
     */
    private void updateA(int win, long[] a, long[] b) {
        var win2 = win * win;
        var half1 = (win >> 1) + 1;
        var va = new long[half1];
        var mb = new long[half1 * half1];
        for (var i = 0; i < win; i++) {
            for (var j = 0; j < win; j++) {
                va[wrap(j, win)] += m[i * win + j] * b[i] / TAP_SCALE;
            }
        }
        for (var i = 0; i < win; i++) {
            for (var j = 0; j < win; j++) {
                for (var k = 0; k < win; k++) {
                    var kk = wrap(k, win);
                    for (var l = 0; l < win; l++) {
                        var ll = wrap(l, win);
                        var x = h[(j * win + k) * win2 + i * win + l] * b[i] / TAP_SCALE;
                        mb[ll * half1 + kk] += multiplyAndScale(x, b[j]);
                    }
                }
            }
        }
        solve(win, va, mb, a);
    }

    /**
     * Solves for the horizontal filter with the vertical one fixed ({@code update_b_sep_sym}).
     *
     * @param win the window side
     * @param a the vertical filter
     * @param b the horizontal filter, updated
     */
    private void updateB(int win, long[] a, long[] b) {
        var win2 = win * win;
        var half1 = (win >> 1) + 1;
        var va = new long[half1];
        var mb = new long[half1 * half1];
        for (var i = 0; i < win; i++) {
            var ii = wrap(i, win);
            for (var j = 0; j < win; j++) {
                va[ii] += m[i * win + j] * a[j] / TAP_SCALE;
            }
        }
        for (var i = 0; i < win; i++) {
            var ii = wrap(i, win);
            for (var j = 0; j < win; j++) {
                var jj = wrap(j, win);
                for (var k = 0; k < win; k++) {
                    for (var l = 0; l < win; l++) {
                        var x = h[(i * win + k) * win2 + j * win + l] * a[k] / TAP_SCALE;
                        mb[jj * half1 + ii] += multiplyAndScale(x, a[l]);
                    }
                }
            }
        }
        solve(win, va, mb, b);
    }

    /**
     * Enforces the unit-gain constraint on a symmetric system and solves it, updating the filter
     * when the system is well posed.
     *
     * @param win the window side
     * @param va the right-hand side
     * @param mb the matrix, {@code (win / 2 + 1)} squared
     * @param filter the filter, updated
     */
    private static void solve(int win, long[] va, long[] mb, long[] filter) {
        var half1 = (win >> 1) + 1;
        var c = half1 - 1;
        for (var i = 0; i < c; i++) {
            va[i] -= va[c] * 2 + mb[i * half1 + c] - 2 * mb[c * half1 + c];
        }
        for (var i = 0; i < c; i++) {
            for (var j = 0; j < c; j++) {
                mb[i * half1 + j] -= 2 * (mb[i * half1 + c] + mb[c * half1 + j] - 2 * mb[c * half1 + c]);
            }
        }
        var s = new long[win];
        if (linsolve(c, mb, half1, va, s)) {
            s[c] = TAP_SCALE;
            for (var i = half1; i < win; i++) {
                s[i] = s[win - 1 - i];
                s[c] -= 2 * s[i];
            }
            for (var i = 0; i < win; i++) {
                filter[i] = Math.clamp(s[i], -(1L << 29), (1L << 29) - 1);
            }
        }
    }

    /**
     * Solves a small linear system by Gaussian elimination with partial pivoting, the solution
     * scaled by {@link #TAP_SCALE} ({@code linsolve_wiener}).
     *
     * @param n the order
     * @param a the matrix, destroyed
     * @param stride the matrix stride
     * @param b the right-hand side, destroyed
     * @param x receives the solution
     * @return {@code false} when the system is singular
     */
    private static boolean linsolve(int n, long[] a, int stride, long[] b, long[] x) {
        for (var k = 0; k < n - 1; k++) {
            for (var i = n - 1; i > k; i--) {
                if (Math.abs(a[(i - 1) * stride + k]) < Math.abs(a[i * stride + k])) {
                    for (var j = 0; j < n; j++) {
                        var c = a[i * stride + j];
                        a[i * stride + j] = a[(i - 1) * stride + j];
                        a[(i - 1) * stride + j] = c;
                    }
                    var c = b[i];
                    b[i] = b[i - 1];
                    b[i - 1] = c;
                }
            }
            long maxAbs = 0;
            for (var j = 0; j < n; j++) {
                maxAbs = Math.max(maxAbs, Math.abs(a[k * stride + j]));
            }
            var scalerA = maxAbs < (1 << 22) ? 1 : 1 << 6;
            var scalerC = maxAbs < (1 << 22) ? 1 : 1 << 7;
            var scaler = scalerC * scalerA;
            for (var i = k; i < n - 1; i++) {
                if (a[k * stride + k] == 0) {
                    return false;
                }
                var c = a[(i + 1) * stride + k] / scalerC;
                var cd = a[k * stride + k];
                for (var j = 0; j < n; j++) {
                    a[(i + 1) * stride + j] -= a[k * stride + j] / scalerA * c / cd * scaler;
                }
                b[i + 1] -= c * b[k] / cd * scalerC;
            }
        }
        for (var i = n - 1; i >= 0; i--) {
            if (a[i * stride + i] == 0) {
                return false;
            }
            long c = 0;
            for (var j = i + 1; j <= n - 1; j++) {
                c += a[i * stride + j] * x[j] / TAP_SCALE;
            }
            x[i] = TAP_SCALE * (b[i] - c) / a[i * stride + i];
        }
        return true;
    }

    /**
     * Rounds a decomposed filter to the coded taps and their ranges ({@code finalize_sym_filter}).
     *
     * @param win the window side
     * @param fl the filter, scaled by {@link #TAP_SCALE}
     * @param out receives the seven taps, the center tap without its implicit 128
     */
    private static void finalizeFilter(int win, long[] fl, int[] out) {
        var half = win >> 1;
        var taps = new int[3];
        for (var i = 0; i < half; i++) {
            var dividend = fl[i] * FILT_STEP;
            taps[i] = (int) (dividend < 0 ? (dividend - TAP_SCALE / 2) / TAP_SCALE
                    : (dividend + TAP_SCALE / 2) / TAP_SCALE);
        }
        if (win == 7) {
            out[0] = Math.clamp(taps[0], TAP_MIN[0], TAP_MAX[0]);
            out[1] = Math.clamp(taps[1], TAP_MIN[1], TAP_MAX[1]);
            out[2] = Math.clamp(taps[2], TAP_MIN[2], TAP_MAX[2]);
        } else {
            out[2] = Math.clamp(taps[1], TAP_MIN[2], TAP_MAX[2]);
            out[1] = Math.clamp(taps[0], TAP_MIN[1], TAP_MAX[1]);
            out[0] = 0;
        }
        out[6] = out[0];
        out[5] = out[1];
        out[4] = out[2];
        out[3] = -2 * (out[0] + out[1] + out[2]);
    }

    /**
     * Returns how much worse the quadratic error model rates a filter than the identity filter;
     * a positive score means the filter does not help ({@code compute_score}).
     *
     * @param win the window side
     * @param vfilter the vertical taps
     * @param hfilter the horizontal taps
     * @return the score
     */
    private long score(int win, int[] vfilter, int[] hfilter) {
        var off = (7 - win) >> 1;
        var win2 = win * win;
        var a = new int[7];
        var b = new int[7];
        a[3] = b[3] = FILT_STEP;
        for (var i = 0; i < 3; i++) {
            a[i] = a[6 - i] = vfilter[i];
            b[i] = b[6 - i] = hfilter[i];
            a[3] -= 2 * a[i];
            b[3] -= 2 * b[i];
        }
        var ab = new long[win2];
        for (var k = 0; k < win; k++) {
            for (var l = 0; l < win; l++) {
                ab[k * win + l] = (long) a[l + off] * b[k + off];
            }
        }
        long pp = 0;
        long qq = 0;
        for (var k = 0; k < win2; k++) {
            pp += ab[k] * m[k] / FILT_STEP / FILT_STEP;
            for (var l = 0; l < win2; l++) {
                qq += ab[k] * h[k * win2 + l] * ab[l] / FILT_STEP / FILT_STEP / FILT_STEP / FILT_STEP;
            }
        }
        var score = qq - 2 * pp;
        var ip = m[win2 >> 1];
        var iq = h[(win2 >> 1) * win2 + (win2 >> 1)];
        return score - (iq - 2 * ip);
    }

    /**
     * Refines the Wiener taps of a unit by a descending-step coordinate search on the measured
     * error ({@code finer_search_wiener}).
     *
     * @param p the plane
     * @param u the unit
     * @param win the window side
     * @return the squared error of the refined filter
     */
    private long refineWiener(int p, Unit u, int win) {
        var lr = new RestorationUnit();
        lr.type = FrameHeader.RESTORATION_WIENER;
        System.arraycopy(u.wienerH, 0, lr.filterH, 0, 3);
        System.arraycopy(u.wienerV, 0, lr.filterV, 0, 3);
        var err = sse(p, u, lr);
        var first = (7 - win) >> 1;
        for (var s = 4; s >= 1; s >>= 1) {
            for (var dir = 0; dir < 2; dir++) {
                var taps = dir == 0 ? lr.filterH : lr.filterV;
                for (var t = first; t < 3; t++) {
                    var moved = false;
                    while (taps[t] - s >= TAP_MIN[t]) {
                        taps[t] -= s;
                        var e = sse(p, u, lr);
                        if (e > err) {
                            taps[t] += s;
                            break;
                        }
                        err = e;
                        moved = true;
                        if (s != 4) {
                            break;
                        }
                    }
                    if (moved) {
                        continue;
                    }
                    while (taps[t] + s <= TAP_MAX[t]) {
                        taps[t] += s;
                        var e = sse(p, u, lr);
                        if (e > err) {
                            taps[t] -= s;
                            break;
                        }
                        err = e;
                        if (s != 4) {
                            break;
                        }
                    }
                }
            }
        }
        System.arraycopy(lr.filterH, 0, u.wienerH, 0, 3);
        System.arraycopy(lr.filterV, 0, u.wienerV, 0, 3);
        return err;
    }

    /**
     * Searches the self-guided parameter set and projection of a unit, leaving them and their
     * squared error in the unit ({@code search_selfguided_restoration}).
     *
     * @param p the plane
     * @param u the unit
     */
    private void searchSgr(int p, Unit u) {
        loadDegraded(p, u);
        var best = -1L;
        var bestSet = 0;
        var xqd = new int[2];
        var bestXqd = new int[2];
        if (!frame.settings.sgrPruning) {
            for (var set = 0; set < 16; set++) {
                var err = sgrError(p, u, set, xqd);
                if (best < 0 || err < best) {
                    best = err;
                    bestSet = set;
                    bestXqd[0] = xqd[0];
                    bestXqd[1] = xqd[1];
                }
            }
        } else {
            for (var set : SGR_SEEDS) {
                var err = sgrError(p, u, set, xqd);
                if (best < 0 || err < best) {
                    best = err;
                    bestSet = set;
                    bestXqd[0] = xqd[0];
                    bestXqd[1] = xqd[1];
                }
            }
            var seed = bestSet;
            for (var set = seed - 1; set < seed + 2; set += 2) {
                if (set < 0 || set > 9) {
                    continue;
                }
                var err = sgrError(p, u, set, xqd);
                if (err < best) {
                    best = err;
                    bestSet = set;
                    bestXqd[0] = xqd[0];
                    bestXqd[1] = xqd[1];
                }
            }
            for (var group : SGR_GROUPS) {
                var set = group[bestSet];
                if (set < 0) {
                    continue;
                }
                var err = sgrError(p, u, set, xqd);
                if (err < best) {
                    best = err;
                    bestSet = set;
                    bestXqd[0] = xqd[0];
                    bestXqd[1] = xqd[1];
                }
            }
        }
        u.sgrSet = bestSet;
        u.xqd[0] = bestXqd[0];
        u.xqd[1] = bestXqd[1];
        var lr = new RestorationUnit();
        lr.type = FrameHeader.RESTORATION_SGRPROJ + bestSet;
        lr.sgrWeights[0] = bestXqd[0];
        lr.sgrWeights[1] = bestXqd[1];
        u.sse[2] = sse(p, u, lr);
    }

    /**
     * Copies the degraded samples of a unit with a three-sample border, replicated at the plane
     * edges, into {@link #dgd32}.
     *
     * @param p the plane
     * @param u the unit
     */
    private void loadDegraded(int p, Unit u) {
        var dgd = f.cur.data[p];
        var ds = f.cur.stride[p == 0 ? 0 : 1];
        var pw = planeWidth(p);
        var ph = planeHeight(p);
        var w = u.x1 - u.x0 + 6;
        var hh = u.y1 - u.y0 + 6;
        for (var y = 0; y < hh; y++) {
            var sy = Math.clamp(u.y0 - 3 + y, 0, ph - 1);
            for (var x = 0; x < w; x++) {
                var sx = Math.clamp(u.x0 - 3 + x, 0, pw - 1);
                dgd32[y * w + x] = dgd[sy * ds + sx];
            }
        }
    }

    /**
     * Filters the loaded unit with one self-guided parameter set, solves its projection and
     * returns the refined projection's squared error ({@code compute_sgrproj_err}).
     *
     * @param p the plane
     * @param u the unit
     * @param set the parameter set
     * @param xqd receives the coded projection weights
     * @return the squared error at the frame's bit depth
     */
    private long sgrError(int p, Unit u, int set, int[] xqd) {
        var w = u.x1 - u.x0;
        var hh = u.y1 - u.y0;
        var r0 = SGR_RADII[set][0];
        var r1 = SGR_RADII[set][1];
        if (r0 > 0) {
            selfGuided(w, hh, set, 0, flt0);
        }
        if (r1 > 0) {
            selfGuided(w, hh, set, 1, flt1);
        }
        var xq = new int[2];
        projection(p, u, r0, r1, xq);
        if (r0 == 0) {
            xqd[0] = 0;
            xqd[1] = Math.clamp((1 << PRJ_BITS) - xq[1], PRJ_MIN1, PRJ_MAX1);
        } else if (r1 == 0) {
            xqd[0] = Math.clamp(xq[0], PRJ_MIN0, PRJ_MAX0);
            xqd[1] = Math.clamp((1 << PRJ_BITS) - xqd[0], PRJ_MIN1, PRJ_MAX1);
        } else {
            xqd[0] = Math.clamp(xq[0], PRJ_MIN0, PRJ_MAX0);
            xqd[1] = Math.clamp((1 << PRJ_BITS) - xqd[0] - xq[1], PRJ_MIN1, PRJ_MAX1);
        }
        var err = projectionError(p, u, r0, r1, xqd);
        var min = new int[]{PRJ_MIN0, PRJ_MIN1};
        var max = new int[]{PRJ_MAX0, PRJ_MAX1};
        for (var s = 2; s >= 1; s >>= 1) {
            for (var k = 0; k < 2; k++) {
                if ((r0 == 0 && k == 0) || (r1 == 0 && k == 1)) {
                    continue;
                }
                var moved = false;
                while (xqd[k] - s >= min[k]) {
                    xqd[k] -= s;
                    var e = projectionError(p, u, r0, r1, xqd);
                    if (e > err) {
                        xqd[k] += s;
                        break;
                    }
                    err = e;
                    moved = true;
                    if (s != 2) {
                        break;
                    }
                }
                if (moved) {
                    continue;
                }
                while (xqd[k] + s <= max[k]) {
                    xqd[k] += s;
                    var e = projectionError(p, u, r0, r1, xqd);
                    if (e > err) {
                        xqd[k] -= s;
                        break;
                    }
                    err = e;
                    if (s != 2) {
                        break;
                    }
                }
            }
        }
        return err;
    }

    /**
     * Solves the least-squares projection of the self-guided outputs onto the source
     * ({@code get_proj_subspace}).
     *
     * @param p the plane
     * @param u the unit
     * @param r0 the radius of the first pass, 0 when disabled
     * @param r1 the radius of the second pass, 0 when disabled
     * @param xq receives the projection weights, zero when the problem is ill-posed
     */
    private void projection(int p, Unit u, int r0, int r1, int[] xq) {
        var src = frame.source.data[p];
        var ss = frame.source.stride[p == 0 ? 0 : 1];
        var w = u.x1 - u.x0;
        var hh = u.y1 - u.y0;
        var dw = w + 6;
        long h00 = 0;
        long h11 = 0;
        long h01 = 0;
        long c0 = 0;
        long c1 = 0;
        for (var y = 0; y < hh; y++) {
            for (var x = 0; x < w; x++) {
                var uu = dgd32[(y + 3) * dw + x + 3] << RST_BITS;
                var s = (src[(u.y0 + y) * ss + u.x0 + x] << RST_BITS) - uu;
                var f1 = r0 > 0 ? flt0[y * w + x] - uu : 0;
                var f2 = r1 > 0 ? flt1[y * w + x] - uu : 0;
                h00 += (long) f1 * f1;
                h11 += (long) f2 * f2;
                h01 += (long) f1 * f2;
                c0 += (long) f1 * s;
                c1 += (long) f2 * s;
            }
        }
        var size = (long) w * hh;
        h00 /= size;
        h01 /= size;
        h11 /= size;
        c0 /= size;
        c1 /= size;
        xq[0] = 0;
        xq[1] = 0;
        if (r0 == 0) {
            if (h11 == 0) {
                return;
            }
            xq[1] = (int) roundedDivide(c1 * (1 << PRJ_BITS), h11);
        } else if (r1 == 0) {
            if (h00 == 0) {
                return;
            }
            xq[0] = (int) roundedDivide(c0 * (1 << PRJ_BITS), h00);
        } else {
            var det = h00 * h11 - h01 * h01;
            if (det == 0) {
                return;
            }
            var div1 = h11 * c0 - h01 * c1;
            xq[0] = (int) (Math.abs(div1) > Long.MAX_VALUE >> PRJ_BITS
                    ? roundedDivide(div1, det / (1 << PRJ_BITS)) : roundedDivide(div1 * (1 << PRJ_BITS), det));
            var div2 = h00 * c1 - h01 * c0;
            xq[1] = (int) (Math.abs(div2) > Long.MAX_VALUE >> PRJ_BITS
                    ? roundedDivide(div2, det / (1 << PRJ_BITS)) : roundedDivide(div2 * (1 << PRJ_BITS), det));
        }
    }

    /**
     * Divides rounding half away from zero ({@code signed_rounded_divide}).
     *
     * @param dividend the value to divide
     * @param divisor the positive divisor
     * @return the quotient
     */
    private static long roundedDivide(long dividend, long divisor) {
        if (divisor == 0) {
            return 0;
        }
        return dividend < 0 ? (dividend - divisor / 2) / divisor : (dividend + divisor / 2) / divisor;
    }

    /**
     * Returns the squared error of the projection of the self-guided outputs with coded weights
     * ({@code av1_lowbd_pixel_proj_error}).
     *
     * @param p the plane
     * @param u the unit
     * @param r0 the radius of the first pass
     * @param r1 the radius of the second pass
     * @param xqd the coded projection weights
     * @return the squared error at the frame's bit depth
     */
    private long projectionError(int p, Unit u, int r0, int r1, int[] xqd) {
        var src = frame.source.data[p];
        var ss = frame.source.stride[p == 0 ? 0 : 1];
        var w = u.x1 - u.x0;
        var hh = u.y1 - u.y0;
        var dw = w + 6;
        int xq0;
        int xq1;
        if (r0 == 0) {
            xq0 = 0;
            xq1 = (1 << PRJ_BITS) - xqd[1];
        } else if (r1 == 0) {
            xq0 = xqd[0];
            xq1 = 0;
        } else {
            xq0 = xqd[0];
            xq1 = (1 << PRJ_BITS) - xq0 - xqd[1];
        }
        var shift = RST_BITS + PRJ_BITS;
        var round = 1 << (shift - 1);
        long err = 0;
        for (var y = 0; y < hh; y++) {
            for (var x = 0; x < w; x++) {
                var uu = dgd32[(y + 3) * dw + x + 3] << RST_BITS;
                var v = uu << PRJ_BITS;
                if (r0 > 0) {
                    v += xq0 * (flt0[y * w + x] - uu);
                }
                if (r1 > 0) {
                    v += xq1 * (flt1[y * w + x] - uu);
                }
                var e = ((v + round) >> shift) - src[(u.y0 + y) * ss + u.x0 + x];
                err += (long) e * e;
            }
        }
        return err;
    }

    /**
     * Runs one pass of the self-guided filter over the loaded unit, producing its output at
     * {@link #RST_BITS} extra precision ({@code selfguided_restoration_internal} and its fast
     * variant for the 5x5 pass).
     *
     * @param w the unit width
     * @param hh the unit height
     * @param set the parameter set
     * @param radius 0 for the 5x5 pass, 1 for the 3x3 pass
     * @param out receives the output, with a stride of the unit width
     */
    private void selfGuided(int w, int hh, int set, int radius, int[] out) {
        var r = SGR_RADII[set][radius];
        var s = Tables.SGR_PARAMS[set * 2 + radius];
        var bd = frame.bitDepth;
        var dw = w + 6;
        var dh = hh + 6;
        boxSums(dw, dh, r);
        var n = (2 * r + 1) * (2 * r + 1);
        var oneByN = ONE_BY_X[n - 1];
        var fast = radius == 0;
        var step = fast ? 2 : 1;
        for (var i = -1; i < hh + 1; i += step) {
            for (var j = -1; j < w + 1; j++) {
                var k = (i + 3) * dw + j + 3;
                var a = (boxA[k] + ((1 << (2 * (bd - 8))) >> 1)) >> (2 * (bd - 8));
                var b = (boxB[k] + ((1 << (bd - 8)) >> 1)) >> (bd - 8);
                long an = (long) a * n;
                long bb = (long) b * b;
                var pv = an < bb ? 0 : an - bb;
                var z = (pv * s + (1L << 19)) >> 20;
                var aa = X_BY_XPLUS1[(int) Math.min(z, 255)];
                boxA[k] = aa;
                boxB[k] = (int) (((long) (256 - aa) * boxB[k] * oneByN + (1 << 11)) >> 12);
            }
        }
        for (var i = 0; i < hh; i++) {
            for (var j = 0; j < w; j++) {
                var k = (i + 3) * dw + j + 3;
                var l = dgd32[k];
                int a;
                int b;
                int nb;
                if (fast) {
                    if ((i & 1) == 0) {
                        nb = 5;
                        a = (boxA[k - dw] + boxA[k + dw]) * 6
                                + (boxA[k - 1 - dw] + boxA[k - 1 + dw] + boxA[k + 1 - dw] + boxA[k + 1 + dw]) * 5;
                        b = (boxB[k - dw] + boxB[k + dw]) * 6
                                + (boxB[k - 1 - dw] + boxB[k - 1 + dw] + boxB[k + 1 - dw] + boxB[k + 1 + dw]) * 5;
                    } else {
                        nb = 4;
                        a = boxA[k] * 6 + (boxA[k - 1] + boxA[k + 1]) * 5;
                        b = boxB[k] * 6 + (boxB[k - 1] + boxB[k + 1]) * 5;
                    }
                } else {
                    nb = 5;
                    a = (boxA[k] + boxA[k - 1] + boxA[k + 1] + boxA[k - dw] + boxA[k + dw]) * 4
                            + (boxA[k - 1 - dw] + boxA[k - 1 + dw] + boxA[k + 1 - dw] + boxA[k + 1 + dw]) * 3;
                    b = (boxB[k] + boxB[k - 1] + boxB[k + 1] + boxB[k - dw] + boxB[k + dw]) * 4
                            + (boxB[k - 1 - dw] + boxB[k - 1 + dw] + boxB[k + 1 - dw] + boxB[k + 1 + dw]) * 3;
                }
                var v = (long) a * l + b;
                var shift = 8 + nb - RST_BITS;
                out[i * w + j] = (int) ((v + (1L << (shift - 1))) >> shift);
            }
        }
    }

    /**
     * Computes the box sums of the samples into {@link #boxB} and of their squares into
     * {@link #boxA} over the bordered unit ({@code boxsum}).
     *
     * @param dw the bordered width
     * @param dh the bordered height
     * @param r the box radius, 1 or 2
     */
    private void boxSums(int dw, int dh, int r) {
        for (var y = 0; y < dh; y++) {
            for (var x = 0; x < dw; x++) {
                long sum = 0;
                long sq = 0;
                for (var dy = -r; dy <= r; dy++) {
                    var yy = y + dy;
                    if (yy < 0 || yy >= dh) {
                        continue;
                    }
                    for (var dx = -r; dx <= r; dx++) {
                        var xx = x + dx;
                        if (xx < 0 || xx >= dw) {
                            continue;
                        }
                        var v = dgd32[yy * dw + xx];
                        sum += v;
                        sq += (long) v * v;
                    }
                }
                boxB[y * dw + x] = (int) sum;
                boxA[y * dw + x] = (int) sq;
            }
        }
    }

    /**
     * Restores a unit the way the decoder does and returns its squared error against the source,
     * at the 8-bit scale.
     *
     * @param p the plane
     * @param u the unit
     * @param lr the restoration, or {@code null} for none
     * @return the squared error
     */
    private long sse(int p, Unit u, RestorationUnit lr) {
        var src = frame.source.data[p];
        var ss = frame.source.stride[p == 0 ? 0 : 1];
        var plane = f.cur.data[p];
        var stride = f.cur.stride[p == 0 ? 0 : 1];
        var shift = 2 * (frame.bitDepth - 8);
        long err = 0;
        if (lr == null) {
            for (var y = u.y0; y < u.y1; y++) {
                for (var x = u.x0; x < u.x1; x++) {
                    var d = plane[y * stride + x] - src[y * ss + x];
                    err += (long) d * d;
                }
            }
            return err >> shift;
        }
        var ssVer = p != 0 && frame.layout == SequenceHeader.LAYOUT_I420 ? 1 : 0;
        var pw = planeWidth(p);
        var ph = planeHeight(p);
        var offset = 8 >> ssVer;
        var chunk = 64 >> ssVer;
        var lpf = f.lrLpfLine[p];
        var w = u.x1 - u.x0;
        var edges = (u.x0 > 0 ? LoopRestorationDsp.HAVE_LEFT : 0) | (u.x1 < pw ? LoopRestorationDsp.HAVE_RIGHT : 0);
        var copyW = Math.min(w + 4, pw - u.x0);
        configure(lr);
        var wiener = lr.type == FrameHeader.RESTORATION_WIENER;
        int kind;
        if (wiener) {
            kind = (lr.filterH[0] | lr.filterV[0]) == 0 ? LoopRestorationDsp.WIENER_5TAP
                    : LoopRestorationDsp.WIENER_7TAP;
        } else {
            kind = (params.s0 != 0 ? 1 : 0) + (params.s1 != 0 ? 2 : 0) - 1;
        }
        if (stripe.length < (chunk + 1) * stride) {
            stripe = new short[(chunk + 1) * stride];
        }
        var y = u.y0;
        while (y < u.y1) {
            var next = y == 0 ? chunk - offset : y + chunk;
            var end = Math.min(next, u.y1);
            var rows = end - y;
            var sby = (y + (y != 0 ? offset : 0)) >> (6 - ssVer);
            var lpfOff = (sby * 4 - 4) * stride + u.x0;
            var e = edges | (y > 0 ? LoopRestorationDsp.HAVE_TOP : 0)
                    | (end < ph ? LoopRestorationDsp.HAVE_BOTTOM : 0);
            for (var r = 0; r < rows; r++) {
                System.arraycopy(plane, (y + r) * stride + u.x0, stripe, r * stride + u.x0, copyW);
                if (u.x0 > 0) {
                    System.arraycopy(plane, (y + r) * stride + u.x0 - 4, left, r * 4, 4);
                }
            }
            if (wiener) {
                dsp.wiener(kind, stripe, u.x0, stride, left, 0, lpf, lpfOff, w, rows, params, e, lrScratch,
                        f.bitdepthMax);
            } else {
                dsp.sgr(kind, stripe, u.x0, stride, left, 0, lpf, lpfOff, w, rows, params, e, lrScratch,
                        f.bitdepthMax);
            }
            for (var r = 0; r < rows; r++) {
                var srow = (y + r) * ss + u.x0;
                var drow = r * stride + u.x0;
                for (var x = 0; x < w; x++) {
                    var d = stripe[drow + x] - src[srow + x];
                    err += (long) d * d;
                }
            }
            y = end;
        }
        return err >> shift;
    }

    /**
     * Loads the kernel parameters of a restoration.
     *
     * @param lr the restoration
     */
    private void configure(RestorationUnit lr) {
        if (lr.type == FrameHeader.RESTORATION_WIENER) {
            params.setWiener(lr.filterH[0], lr.filterH[1], lr.filterH[2], lr.filterV[0], lr.filterV[1],
                    lr.filterV[2]);
        } else {
            var set = lr.type - FrameHeader.RESTORATION_SGRPROJ;
            params.s0 = Tables.SGR_PARAMS[set * 2];
            params.s1 = Tables.SGR_PARAMS[set * 2 + 1];
            params.w0 = lr.sgrWeights[0];
            params.w1 = 128 - (lr.sgrWeights[0] + lr.sgrWeights[1]);
        }
    }

    /**
     * Returns the coded size in bits of a unit's Wiener taps against references
     * ({@code count_wiener_bits}).
     *
     * @param p the plane
     * @param u the unit
     * @param ref the references
     * @return the bits
     */
    private static int wienerBits(int p, Unit u, Reference ref) {
        var bits = 0;
        for (var dir = 0; dir < 2; dir++) {
            var taps = dir == 0 ? u.wienerV : u.wienerH;
            var refs = dir == 0 ? ref.wienerV : ref.wienerH;
            for (var t = p == 0 ? 0 : 1; t < 3; t++) {
                bits += subexpBits(TAP_MAX[t] - TAP_MIN[t] + 1, TAP_K[t], refs[t] - TAP_MIN[t], taps[t] - TAP_MIN[t]);
            }
        }
        return bits;
    }

    /**
     * Returns the coded size in bits of a unit's self-guided parameters against references
     * ({@code count_sgrproj_bits}).
     *
     * @param u the unit
     * @param ref the references
     * @return the bits
     */
    private static int sgrBits(Unit u, Reference ref) {
        var bits = 4;
        if (SGR_RADII[u.sgrSet][0] > 0) {
            bits += subexpBits(128, 4, ref.xqd[0] - PRJ_MIN0, u.xqd[0] - PRJ_MIN0);
        }
        if (SGR_RADII[u.sgrSet][1] > 0) {
            bits += subexpBits(128, 4, ref.xqd[1] - PRJ_MIN1, u.xqd[1] - PRJ_MIN1);
        }
        return bits;
    }

    /**
     * Returns the value a reference-relative sub-exponential code codes for a value
     * ({@code recenter_finite_nonneg}).
     *
     * @param n the alphabet size
     * @param ref the reference
     * @param v the value
     * @return the recentered value
     */
    private static int recenter(int n, int ref, int v) {
        if ((ref << 1) <= n) {
            return recenterNonNeg(ref, v);
        }
        return recenterNonNeg(n - 1 - ref, n - 1 - v);
    }

    /**
     * Recenters a value around a reference ({@code recenter_nonneg}).
     *
     * @param r the reference
     * @param v the value
     * @return the recentered value
     */
    private static int recenterNonNeg(int r, int v) {
        if (v > (r << 1)) {
            return v;
        }
        if (v >= r) {
            return (v - r) << 1;
        }
        return ((r - v) << 1) - 1;
    }

    /**
     * Returns the size in bits of a reference-relative sub-exponential code.
     *
     * @param n the alphabet size, 16 to 128
     * @param k the code parameter
     * @param ref the reference
     * @param v the value
     * @return the bits
     */
    private static int subexpBits(int n, int k, int ref, int v) {
        var x = recenter(n, ref, v);
        if (x < (1 << k)) {
            return 1 + k;
        }
        if (x < (2 << k)) {
            return 2 + k;
        }
        if (x < (4 << k)) {
            return 4 + k;
        }
        return 5 + k;
    }

    /**
     * Writes a reference-relative sub-exponential code as the decoder reads it
     * ({@code msac_decode_subexp}).
     *
     * @param ec the encoder
     * @param ref the reference, offset to be non-negative
     * @param n the alphabet size
     * @param k the code parameter
     * @param v the value, offset as the reference
     */
    private static void writeSubexp(RangeEncoder ec, int ref, int n, int k, int v) {
        var x = recenter(n, ref, v);
        if (x < (1 << k)) {
            ec.encodeBoolEqui(0);
            ec.encodeBits(x, k);
        } else if (x < (2 << k)) {
            ec.encodeBoolEqui(1);
            ec.encodeBoolEqui(0);
            ec.encodeBits(x - (1 << k), k);
        } else if (x < (4 << k)) {
            ec.encodeBoolEqui(1);
            ec.encodeBoolEqui(1);
            ec.encodeBoolEqui(0);
            ec.encodeBits(x - (2 << k), k + 1);
        } else {
            ec.encodeBoolEqui(1);
            ec.encodeBoolEqui(1);
            ec.encodeBoolEqui(1);
            ec.encodeBits(x - (4 << k), k + 2);
        }
    }

    /**
     * Writes the restoration units coded in one superblock, in the decoder's order
     * ({@code read_restoration_info}).
     *
     * @param ec the encoder
     * @param cdf the tile's CDFs
     * @param f the frame context, with the chosen units
     * @param refs the per-plane references of the tile, reset at the tile start
     * @param bx the superblock column in 4-sample units
     * @param by the superblock row in 4-sample units
     */
    static void write(RangeEncoder ec, char[] cdf, FrameContext f, RestorationUnit[] refs, int bx, int by) {
        var hdr = f.frameHdr;
        for (var p = 0; p < 3; p++) {
            if (((f.restorePlanes >> p) & 1) == 0) {
                continue;
            }
            var ssVer = p != 0 && f.cur.layout == SequenceHeader.LAYOUT_I420 ? 1 : 0;
            var ssHor = p != 0 && f.cur.layout != SequenceHeader.LAYOUT_I444 ? 1 : 0;
            var unitSizeLog2 = hdr.restorationUnitSize[p != 0 ? 1 : 0];
            var unitSize = 1 << unitSizeLog2;
            var mask = unitSize - 1;
            var y = by * 4 >> ssVer;
            var h = (f.cur.h + ssVer) >> ssVer;
            if ((y & mask) != 0 || (y != 0 && y + (unitSize >> 1) > h)) {
                continue;
            }
            var x = 4 * bx >> ssHor;
            var w = (f.cur.w + ssHor) >> ssHor;
            if ((x & mask) != 0 || (x != 0 && x + (unitSize >> 1) > w)) {
                continue;
            }
            var sbIdx = (by >> 5) * f.srSb128w + (bx >> 5);
            var unitIdx = ((by & 16) >> 3) + ((bx & 16) >> 4);
            var lr = f.lrMask[(sbIdx * 3 + p) * 4 + unitIdx];
            var frameType = hdr.restorationType[p];
            var ref = refs[p];
            var type = lr.type >= FrameHeader.RESTORATION_SGRPROJ ? FrameHeader.RESTORATION_SGRPROJ : lr.type;
            if (frameType == FrameHeader.RESTORATION_SWITCHABLE) {
                ec.encodeSymbol(cdf, Cdf.RESTORE_SWITCHABLE, type == FrameHeader.RESTORATION_NONE ? 0
                        : type == FrameHeader.RESTORATION_WIENER ? 1 : 2, 2);
            } else {
                ec.encodeBool(cdf, frameType == FrameHeader.RESTORATION_WIENER ? Cdf.RESTORE_WIENER
                        : Cdf.RESTORE_SGRPROJ, type != FrameHeader.RESTORATION_NONE ? 1 : 0);
            }
            if (type == FrameHeader.RESTORATION_WIENER) {
                if (p == 0) {
                    writeSubexp(ec, ref.filterV[0] + 5, 16, 1, lr.filterV[0] + 5);
                }
                writeSubexp(ec, ref.filterV[1] + 23, 32, 2, lr.filterV[1] + 23);
                writeSubexp(ec, ref.filterV[2] + 17, 64, 3, lr.filterV[2] + 17);
                if (p == 0) {
                    writeSubexp(ec, ref.filterH[0] + 5, 16, 1, lr.filterH[0] + 5);
                }
                writeSubexp(ec, ref.filterH[1] + 23, 32, 2, lr.filterH[1] + 23);
                writeSubexp(ec, ref.filterH[2] + 17, 64, 3, lr.filterH[2] + 17);
                System.arraycopy(lr.filterV, 0, ref.filterV, 0, 3);
                System.arraycopy(lr.filterH, 0, ref.filterH, 0, 3);
            } else if (type == FrameHeader.RESTORATION_SGRPROJ) {
                var set = lr.type - FrameHeader.RESTORATION_SGRPROJ;
                ec.encodeBits(set, 4);
                if (SGR_RADII[set][0] > 0) {
                    writeSubexp(ec, ref.sgrWeights[0] + 96, 128, 4, lr.sgrWeights[0] + 96);
                }
                if (SGR_RADII[set][1] > 0) {
                    writeSubexp(ec, ref.sgrWeights[1] + 32, 128, 4, lr.sgrWeights[1] + 32);
                }
                ref.sgrWeights[0] = lr.sgrWeights[0];
                ref.sgrWeights[1] = lr.sgrWeights[1];
            }
        }
    }

    /**
     * Returns references reset to the values every tile starts from.
     *
     * @return the references of the three planes
     */
    static RestorationUnit[] tileReferences() {
        var refs = new RestorationUnit[3];
        for (var p = 0; p < 3; p++) {
            var r = new RestorationUnit();
            System.arraycopy(TAP_DEFAULT, 0, r.filterV, 0, 3);
            System.arraycopy(TAP_DEFAULT, 0, r.filterH, 0, 3);
            r.sgrWeights[0] = -32;
            r.sgrWeights[1] = 31;
            refs[p] = r;
        }
        return refs;
    }

    /**
     * Returns the decoder's restoration unit record of a unit.
     *
     * @param p the plane
     * @param col the unit column
     * @param row the unit row
     * @param size the unit size in the plane's samples
     * @param ss the plane's subsampling, horizontal in bit 1 and vertical in bit 0
     * @return the record
     */
    private RestorationUnit unitAt(int p, int col, int row, int size, int ss) {
        var lumaX = (col * size) << (ss >> 1);
        var lumaY = (row * size) << (ss & 1);
        var sbIdx = (lumaY >> 7) * f.srSb128w + (lumaX >> 7);
        var unitIdx = ((lumaY >> 6) & 1) * 2 + ((lumaX >> 6) & 1);
        return f.lrMask[(sbIdx * 3 + p) * 4 + unitIdx];
    }

    /**
     * Returns the number of restoration units along a plane side ({@code av1_lr_count_units}).
     *
     * @param length the side length
     * @param size the unit size
     * @return the count, at least 1
     */
    private static int count(int length, int size) {
        return Math.max((length + (size >> 1)) / size, 1);
    }

    /**
     * Returns the subsampling of a plane.
     *
     * @param p the plane
     * @return the horizontal subsampling in bit 1 and the vertical in bit 0
     */
    private int subsampling(int p) {
        if (p == 0) {
            return 0;
        }
        var layout = frame.layout;
        var hor = layout != SequenceHeader.LAYOUT_I444 ? 1 : 0;
        var ver = layout == SequenceHeader.LAYOUT_I420 ? 1 : 0;
        return hor << 1 | ver;
    }

    /**
     * Returns the width of a plane.
     *
     * @param p the plane
     * @return the width
     */
    private int planeWidth(int p) {
        var ss = subsampling(p) >> 1;
        return (frame.width + ss) >> ss;
    }

    /**
     * Returns the height of a plane.
     *
     * @param p the plane
     * @return the height
     */
    private int planeHeight(int p) {
        var ss = subsampling(p) & 1;
        return (frame.height + ss) >> ss;
    }

    /**
     * Returns the cost of a binary symbol under the frame's initial CDFs.
     *
     * @param cdf the CDFs
     * @param off the offset of the CDF
     * @param bit the bit, 0 or 1
     * @return the cost in 1/512 bits
     */
    private long boolCost(char[] cdf, int off, int bit) {
        counter.startCounting();
        counter.encodeBool(cdf, off, bit);
        return counter.cost();
    }

    /**
     * Returns the cost of a symbol under the frame's initial CDFs.
     *
     * @param cdf the CDFs
     * @param off the offset of the CDF
     * @param val the symbol
     * @param n the number of symbols minus one
     * @return the cost in 1/512 bits
     */
    private long symbolCost(char[] cdf, int off, int val, int n) {
        counter.startCounting();
        counter.encodeSymbol(cdf, off, val, n);
        return counter.cost();
    }
}
