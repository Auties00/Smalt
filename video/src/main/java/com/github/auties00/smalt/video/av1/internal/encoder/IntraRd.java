package com.github.auties00.smalt.video.av1.internal.encoder;

import com.github.auties00.smalt.video.av1.internal.IntraEdge;
import com.github.auties00.smalt.video.av1.internal.IpredDsp;
import com.github.auties00.smalt.video.av1.internal.ItxDsp;
import com.github.auties00.smalt.video.av1.internal.Scans;
import com.github.auties00.smalt.video.av1.internal.Tables;

/**
 * Chooses the luma mode and transform type of a small intra block by rate-distortion
 * optimization: every luma mode is predicted, every DCT and ADST combination transformed,
 * quantized, reconstructed and costed with the symbol costs of the tile's current CDFs, and the
 * pair with the lowest Lagrangian cost wins.
 *
 * <p>An instance belongs to one tile encoder and is used by one thread at a time.
 *
 * @implNote This implementation follows the luma part of libaom's {@code av1_rd_pick_intra_mode_sb}
 * ({@code av1/encoder/intra_mode_search.c}), which libaom's real-time encoder runs on
 * key frame blocks smaller than 16x16 ({@code hybrid_intra_mode_search}), restricted to
 * 8x8 transforms without angle deltas, filter intra, palette, identity transforms or a
 * chroma search; the chroma mode stays DC.
 */
final class IntraRd {
    /**
     * The transform types searched: {@code DCT_DCT}, {@code ADST_DCT}, {@code DCT_ADST},
     * {@code ADST_ADST}.
     */
    private static final int[] TYPES = {0, FwdTxfm.ADST_DCT, FwdTxfm.DCT_ADST, FwdTxfm.ADST_ADST};

    /**
     * The decoder's index of the 8x8 transform.
     */
    private static final int TX_8X8 = 1;

    /**
     * The number of luma intra modes searched, {@code DC_PRED} to {@code PAETH_PRED}.
     */
    private static final int MODES = 13;

    /**
     * The frame encoder.
     */
    private final FrameEncoder frame;

    /**
     * The tile encoder.
     */
    private final TileEncoder tile;

    /**
     * The coder that costs the coefficient symbols.
     */
    private final RangeEncoder counter = new RangeEncoder();

    /**
     * The coefficient writer used to cost the coefficients.
     */
    private final CoefWriter coefWriter = new CoefWriter();

    /**
     * The forward transform.
     */
    private final FwdTxfm fwd = new FwdTxfm();

    /**
     * The prediction of the evaluated mode.
     */
    private final short[] pred = new short[64];

    /**
     * The reconstruction of the evaluated mode and type.
     */
    private final short[] recon = new short[64];

    /**
     * The residual.
     */
    private final int[] residual = new int[64];

    /**
     * The coefficients.
     */
    private final int[] coef = new int[64];

    /**
     * The levels.
     */
    private final int[] levels = new int[64];

    /**
     * The dequantized coefficients.
     */
    private final int[] dequantized = new int[64];

    /**
     * The inverse transform scratch.
     */
    private final int[] itxScratch = new int[ItxDsp.SCRATCH_INTS];

    /**
     * The transform type of the chosen pair.
     */
    int txType;

    /**
     * Creates the search of a tile.
     *
     * @param frame the frame encoder
     * @param tile the tile encoder
     */
    IntraRd(FrameEncoder frame, TileEncoder tile) {
        this.frame = frame;
        this.tile = tile;
        counter.reset(false);
        counter.startCounting();
    }

    /**
     * Searches the luma mode and transform type of the 8x8 block at the tile's current position
     * and returns the mode, leaving the type in {@link #txType}.
     *
     * @param bs the block size, 8x8
     * @param edgeFlags the block's intra edge flags
     * @param rdmult the Lagrangian multiplier
     * @param skipCost the cost of a zero skip flag in {@code [0]} and of a one in {@code [1]}
     * @return the mode
     */
    int search(int bs, int edgeFlags, int rdmult, int[] skipCost) {
        // TODO: search angle deltas, filter intra, the identity transforms and the chroma modes
        // (chroma-from-luma included), and extend the search to 4x4 blocks
        var t = tile.t;
        var ts = tile.ts;
        var b = t.b;
        var f = frame.f;
        var hdr = f.frameHdr;
        var cur = f.cur;
        var plane = cur.data[0];
        var stride = cur.stride[0];
        var px = t.bx * 4;
        var py = t.by * 4;
        var dst = py * stride + px;
        var src = frame.source.data[0];
        var srcStride = frame.source.stride[0];
        var srcOff = py * srcStride + px;
        var bx4 = t.bx & 31;
        var by4 = t.by & 31;
        var q = frame.quantizer(b.segId, 0);
        var qidx = hdr.segQidx[b.segId];
        var rd = frame.rd;
        var cdf = tile.ts.cdf;
        var aCtx = Tables.INTRA_MODE_CONTEXT[t.a.mode[bx4]];
        var lCtx = Tables.INTRA_MODE_CONTEXT[t.l.mode[by4]];
        var flags = edgeFlags & (IntraEdge.I444_TOP_HAS_RIGHT | IntraEdge.I444_LEFT_HAS_BOTTOM);
        var intraEdgeFilter = f.seqHdr.intraEdgeFilter;
        var smooth = t.a.smFlag(bx4) | t.l.smFlag(by4)
                | (intraEdgeFilter ? IpredDsp.ANGLE_USE_EDGE_FILTER_FLAG : 0);
        var scan = Scans.SCANS[TX_8X8];
        var iscan = CoefWriter.inverseScan(TX_8X8, CoefWriter.TX_CLASS_2D);
        var dsp = frame.dsp;
        var bdMax = frame.bdMax;
        var distShift = frame.distShift();
        var itx = f.itx;
        var bestMode = IpredDsp.DC_PRED;
        var bestType = 0;
        var bestRd = Long.MAX_VALUE;
        var angle = t.angle;
        for (var mode = 0; mode < MODES; mode++) {
            angle[0] = 0;
            var m = IpredDsp.prepareIntraEdges(t.bx, t.bx > ts.colStart, t.by, t.by > ts.rowStart, ts.colEnd,
                    ts.rowEnd, flags, plane, dst, stride, null, 0, mode, angle, 2, 2, intraEdgeFilter, t.edge,
                    IpredDsp.EDGE_TOPLEFT, bdMax);
            IpredDsp.INSTANCE.intraPred(m, pred, 0, 8, t.edge, IpredDsp.EDGE_TOPLEFT, 8, 8, angle[0] | smooth,
                    4 * f.bw - px, 4 * f.bh - py, t.ipredScratch, bdMax);
            for (var y = 0; y < 8; y++) {
                for (var x = 0; x < 8; x++) {
                    residual[y * 8 + x] = src[srcOff + y * srcStride + x] - pred[y * 8 + x];
                }
            }
            var modeCost = rd.kfYMode[(aCtx * 5 + lCtx) * 13 + mode];
            if (mode >= IpredDsp.VERT_PRED && mode <= IpredDsp.VERT_LEFT_PRED) {
                modeCost += rd.angleDelta[(mode - IpredDsp.VERT_PRED) * 7 + 3];
            }
            var types = qidx == 0 ? 1 : TYPES.length;
            for (var ti = 0; ti < types; ti++) {
                var type = TYPES[ti];
                dsp.forwardTransform(fwd, TX_8X8, type, residual, 0, 8, coef);
                var eob = q.quantizeB(coef, 64, scan, iscan, 0, levels);
                System.arraycopy(pred, 0, recon, 0, 64);
                if (eob > 0) {
                    q.dequantize(levels, eob, scan, 0, dequantized);
                    itx.itxfmAdd(TX_8X8, type, recon, 0, 8, dequantized, 0, eob - 1, itxScratch, bdMax);
                }
                long sse = 0;
                for (var y = 0; y < 8; y++) {
                    for (var x = 0; x < 8; x++) {
                        long d = src[srcOff + y * srcStride + x] - recon[y * 8 + x];
                        sse += d * d;
                    }
                }
                sse >>= distShift;
                counter.startCounting();
                coefWriter.write(counter, cdf, t.a.lcoef, bx4, t.l.lcoef, by4, TX_8X8, bs, 0, true, mode,
                        hdr.reducedTxtpSet, qidx, type, levels, 0, eob);
                var rate = counter.cost() + modeCost + skipCost[eob == 0 ? 1 : 0];
                var cost = RdCost.rdcost(rdmult, rate, sse << 4);
                if (cost < bestRd) {
                    bestRd = cost;
                    bestMode = mode;
                    bestType = type;
                }
            }
        }
        txType = bestType;
        return bestMode;
    }
}
