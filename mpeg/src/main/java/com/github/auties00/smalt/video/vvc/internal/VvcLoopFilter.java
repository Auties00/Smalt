/* Ports VVdeC 3.3.0 (source/Lib/CommonLib/LoopFilter.cpp, SampleAdaptiveOffset.cpp and AdaptiveLoopFilter.cpp: the
 * deblocking, sample adaptive offset and adaptive loop filter decisions and filters). Copyright (c) 2018-2026,
 * Fraunhofer-Gesellschaft zur Foerderung der angewandten Forschung e.V. & The VVdeC Authors; Clear BSD License.
 * Modified for Smalt. */
package com.github.auties00.smalt.video.vvc.internal;

import com.github.auties00.smalt.internal.task.ParallelBands;

import java.util.Arrays;

/**
 * Applies the in-loop filters of a decoded picture in the order of ITU-T H.266 8.8: the inverse luma mapping, the
 * deblocking filter (8.8.3), sample adaptive offset (8.8.4) and the adaptive loop filter with its cross-component
 * part (8.8.5).
 *
 * <p>The deblocking filter derives the boundary strength and filter lengths of every edge segment of four samples
 * from the per 4x4 block maps the reconstruction recorded, then filters all vertical edges of the picture followed by
 * all horizontal edges. SAO and ALF read a copy of their input picture and write the picture in place.
 *
 * <p>Each stage splits the picture into bands of CTU rows that run in parallel when the thread count allows: the
 * inverse mapping, SAO and ALF of a CTU depend only on its input samples, and the deblocking filter is designed so
 * that the edges of one direction are filtered independently (each edge reads and writes samples no other edge of
 * that direction writes). The bands of SAO and ALF run on workers that hold their own scratch buffers; the output
 * never depends on the band count.
 *
 * <p>Instances are not thread-safe.
 */
final class VvcLoopFilter {
    /**
     * Constructs the filters, which hold their scratch buffers across pictures.
     */
    VvcLoopFilter() {
    }

    /**
     * The {@code tC'} of each {@code Q} of the deblocking filter (Table 47), for a 10-bit picture.
     */
    private static final int[] TC = {
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 3, 4, 4, 4, 4, 5, 5, 5, 5, 7, 7, 8, 9, 10, 10, 11,
            13, 14, 15, 17, 19, 21, 24, 25, 29, 33, 36, 41, 45, 51, 57, 64, 71, 80, 89, 100, 112, 125, 141, 157, 177,
            198, 222, 250, 280, 314, 352, 395
    };
    /**
     * The {@code beta'} of each {@code Q} of the deblocking filter (Table 47).
     */
    private static final int[] BETA = {
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 20, 22, 24,
            26, 28, 30, 32, 34, 36, 38, 40, 42, 44, 46, 48, 50, 52, 54, 56, 58, 60, 62, 64, 66, 68, 70, 72, 74, 76, 78,
            80, 82, 84, 86, 88
    };
    /**
     * The filter coefficients {@code fi} of a seven-sample long luma filter side (8.8.3.6.7).
     */
    private static final int[] COEFF7 = {59, 50, 41, 32, 23, 14, 5};
    /**
     * The filter coefficients of a five-sample long luma filter side.
     */
    private static final int[] COEFF5 = {58, 45, 32, 19, 6};
    /**
     * The filter coefficients of a three-sample long luma filter side.
     */
    private static final int[] COEFF3 = {53, 32, 11};
    /**
     * The clipping weights {@code tCPDi} of a seven-sample long luma filter side.
     */
    private static final int[] TC7 = {6, 5, 4, 3, 2, 1, 1};
    /**
     * The clipping weights of a three-sample long luma filter side.
     */
    private static final int[] TC3 = {6, 4, 2};
    /**
     * The coefficient order of each ALF transpose index: the filter applies {@code coeff[TRANSPOSE[t][k]]} at tap
     * {@code k}.
     */
    private static final int[][] TRANSPOSE = {
            {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11},
            {9, 4, 10, 8, 1, 5, 11, 7, 3, 0, 2, 6},
            {0, 3, 2, 1, 8, 7, 6, 5, 4, 9, 10, 11},
            {9, 8, 10, 4, 3, 7, 11, 5, 1, 0, 2, 6}
    };
    /**
     * The activity class of each quantized activity of the ALF classification ({@code varTab}, 8.8.5.3).
     */
    private static final int[] ACTIVITY_CLASS = {0, 1, 2, 2, 2, 2, 2, 3, 3, 3, 3, 3, 3, 3, 3, 4};
    /**
     * The transpose index of each pair of main and secondary directions of the ALF classification.
     */
    private static final int[] TRANSPOSE_TABLE = {0, 1, 0, 2, 2, 3, 1, 3};
    /**
     * The samples the ALF of a region may read beyond its sides that no boundary clips.
     */
    private static final int ALF_PAD = 4;

    /**
     * The margin of the padded region copy: the reach of the 7x7 luma filter and of the luma classification.
     */
    private static final int PAD_MARGIN = 3;

    /**
     * The per picture maps of the picture being filtered.
     */
    private VvcFrame f;
    /**
     * The sequence parameter set of the picture.
     */
    private VvcSps sps;
    /**
     * The picture parameter set of the picture.
     */
    private VvcPps pps;
    /**
     * The picture header of the picture.
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
     * Per direction (0 vertical edges, 1 horizontal edges) and 4x4 block: the boundary strength of luma in bits 0
     * and 1, of Cb in bits 2 and 3, of Cr in bits 4 and 5, and in bit 6 whether both chroma transform blocks are at
     * least 8 samples deep across the edge.
     */
    private final byte[][] bs = new byte[2][0];
    /**
     * Per direction and 4x4 block: {@code maxFilterLengthP} in bits 4 to 6 and {@code maxFilterLengthQ} in bits 0 to
     * 2 of the luma edge.
     */
    private final byte[][] len = new byte[2][0];
    /**
     * The copy of the input picture SAO and ALF read, per component; workers share the copy of the filter that
     * created them.
     */
    private short[][] copy = new short[3][0];

    /**
     * The most bands a stage splits into: 1 keeps the filters on the calling thread, 0 lets
     * {@link ParallelBands#bands(int, long)} choose.
     */
    private int maxBands = 1;

    /**
     * The workers that run the SAO and ALF bands after the first, created on demand.
     */
    private VvcLoopFilter[] workers = new VvcLoopFilter[0];

    /**
     * The coefficients of each of the 25 classes of the luma filter set of the CTU.
     */
    private final int[][] alfCoeff = new int[25][12];
    /**
     * The clipping values of each of the 25 classes of the luma filter set of the CTU.
     */
    private final int[][] alfClip = new int[25][12];
    /**
     * The coefficients of the filter of the block being filtered, transposed.
     */
    private final int[] coeffBuf = new int[12];
    /**
     * The clipping values of the filter of the block being filtered, transposed.
     */
    private final int[] clipBuf = new int[12];
    /**
     * The class of the block being classified, in its first element.
     */
    private final int[] cls = new int[32 * 32 / 16];
    /**
     * The transpose index of the block being classified, in its first element.
     */
    private final int[] tpose = new int[32 * 32 / 16];
    /**
     * The band offsets of the SAO band offset of the CTU, by band.
     */
    private final int[] saoTable = new int[32];

    /**
     * The horizontal virtual boundaries crossing the CTU being filtered by the ALF, as sorted luma rows.
     */
    private final int[] horVb = new int[3];
    /**
     * The vertical virtual boundaries crossing the CTU being filtered by the ALF, as sorted luma columns.
     */
    private final int[] verVb = new int[3];

    /**
     * The input samples of the region being filtered, with a margin of {@link #PAD_MARGIN} samples on each side,
     * padded as {@link #sample} pads them.
     */
    private int[] pad = new int[0];

    /**
     * The row stride of {@link #pad}.
     */
    private int padStride;

    /**
     * The picture column of the first column of {@link #pad}.
     */
    private int padX;

    /**
     * The picture row of the first row of {@link #pad}.
     */
    private int padY;

    /**
     * The first column the filters of the region may read.
     */
    private int availX0;
    /**
     * The column after the last one the filters of the region may read.
     */
    private int availX1;
    /**
     * The first row the filters of the region may read.
     */
    private int availY0;
    /**
     * The row after the last one the filters of the region may read.
     */
    private int availY1;
    /**
     * The corners of the region whose diagonal neighbors belong to another raster slice: bit 0 the top-left corner, bit
     * 1 the bottom-right corner.
     */
    private int padCorner;
    /**
     * The first column of the region being filtered.
     */
    private int regionX0;
    /**
     * The first row of the region being filtered.
     */
    private int regionY0;
    /**
     * The column after the region being filtered.
     */
    private int regionX1;
    /**
     * The row after the region being filtered.
     */
    private int regionY1;

    /**
     * Sets how many threads the filters may use.
     *
     * @param threads the thread count: 0 for the shared pool, 1 for the calling thread only, or a larger bound
     */
    void setThreads(int threads) {
        maxBands = threads == 1 || !ParallelBands.enabled() ? 1 : threads;
    }

    /**
     * Returns the number of bands of CTU rows a stage of the current picture splits into.
     *
     * @return the band count, at least 1
     */
    private int bands() {
        if (maxBands == 1) {
            return 1;
        }
        var bands = ParallelBands.bands(f.heightInCtus, (long) f.width * f.height);
        return maxBands > 1 ? Math.min(bands, maxBands) : bands;
    }

    /**
     * Prepares the workers of the bands after the first for the current picture, on the calling thread: creates the
     * missing ones and gives each this filter's picture, parameters and input copy.
     *
     * @param bands the number of bands
     */
    private void prepareWorkers(int bands) {
        if (workers.length < bands - 1) {
            workers = Arrays.copyOf(workers, bands - 1);
        }
        for (var i = 0; i < bands - 1; i++) {
            var worker = workers[i];
            if (worker == null) {
                worker = new VvcLoopFilter();
                workers[i] = worker;
            }
            worker.f = f;
            worker.sps = sps;
            worker.pps = pps;
            worker.ph = ph;
            worker.bitDepth = bitDepth;
            worker.maxVal = maxVal;
            worker.sx = sx;
            worker.sy = sy;
            worker.chromaFormat = chromaFormat;
            worker.copy = copy;
        }
    }

    /**
     * Returns the filter that runs a band of SAO or ALF: this one for the first band, a worker
     * {@link #prepareWorkers} prepared for the others.
     *
     * @param band the band index
     * @return the filter
     */
    private VvcLoopFilter worker(int band) {
        return band == 0 ? this : workers[band - 1];
    }

    /**
     * Applies the in-loop filters to a decoded picture: the inverse luma mapping, then the deblocking filter, SAO and
     * ALF where the slices enable them.
     *
     * @param frame the per picture maps of the decoded picture
     * @param sps its sequence parameter set
     * @param pps its picture parameter set
     * @param recon the reconstruction, which holds the luma mapping
     */
    void apply(VvcFrame frame, VvcSps sps, VvcPps pps, VvcRecon recon) {
        this.f = frame;
        this.sps = sps;
        this.pps = pps;
        this.ph = frame.ph;
        bitDepth = sps.bitDepth;
        maxVal = (1 << bitDepth) - 1;
        chromaFormat = sps.chromaFormatIdc;
        sx = sps.chromaShiftX();
        sy = sps.chromaShiftY();
        var bands = bands();
        var rows = frame.heightInCtus;
        ParallelBands.run(bands, band -> inverseMap(frame, recon, ParallelBands.start(band, bands, rows),
                ParallelBands.start(band + 1, bands, rows)));
        var deblock = false;
        var sao = false;
        var alf = false;
        for (var header : frame.ctuHeader) {
            if (header == null) {
                continue;
            }
            deblock |= !header.deblockingDisabled;
            sao |= header.saoLuma || header.saoChroma;
            alf |= header.alfEnabled || header.ccAlfCb || header.ccAlfCr;
        }
        if (deblock) {
            var n4 = frame.width4 * frame.height4;
            var rows4 = frame.ctuSize >> 2;
            for (var d = 0; d < 2; d++) {
                if (bs[d].length < n4) {
                    bs[d] = new byte[n4];
                    len[d] = new byte[n4];
                }
                Arrays.fill(bs[d], 0, n4, (byte) 0);
                var dir = d;
                ParallelBands.run(bands, band -> derive(dir, ParallelBands.start(band, bands, rows) * rows4,
                        Math.min(frame.height4, ParallelBands.start(band + 1, bands, rows) * rows4)));
            }
            for (var d = 0; d < 2; d++) {
                var dir = d;
                ParallelBands.run(bands, band -> filterEdges(dir, ParallelBands.start(band, bands, rows) * rows4,
                        Math.min(frame.height4, ParallelBands.start(band + 1, bands, rows) * rows4)));
            }
        }
        if (sao) {
            snapshot();
            prepareWorkers(bands);
            ParallelBands.run(bands, band -> worker(band).sao(ParallelBands.start(band, bands, rows),
                    ParallelBands.start(band + 1, bands, rows)));
        }
        if (alf) {
            snapshot();
            prepareWorkers(bands);
            ParallelBands.run(bands, band -> worker(band).alf(ParallelBands.start(band, bands, rows),
                    ParallelBands.start(band + 1, bands, rows)));
        }
    }

    /**
     * Maps the luma samples of the CTUs that use luma mapping back to the input domain (8.7.5.2).
     *
     * @param f the picture
     * @param recon the reconstruction, which holds the mapping
     * @param firstRow the first CTU row
     * @param endRow the CTU row after the last one
     */
    private static void inverseMap(VvcFrame f, VvcRecon recon, int firstRow, int endRow) {
        var plane = f.pic.planes[0];
        var stride = f.pic.planeWidth[0];
        var lmcs = recon.lmcs;
        for (var ctuY = firstRow; ctuY < endRow; ctuY++) {
            for (var ctuX = 0; ctuX < f.widthInCtus; ctuX++) {
                var header = f.ctuHeader[ctuY * f.widthInCtus + ctuX];
                if (header == null || !header.lmcsUsed) {
                    continue;
                }
                var x0 = ctuX << f.log2Ctu;
                var y0 = ctuY << f.log2Ctu;
                var x1 = Math.min(f.width, x0 + f.ctuSize);
                var y1 = Math.min(f.height, y0 + f.ctuSize);
                for (var y = y0; y < y1; y++) {
                    for (var x = x0; x < x1; x++) {
                        var i = y * stride + x;
                        plane[i] = (short) lmcs.inverse(plane[i]);
                    }
                }
            }
        }
    }

    /**
     * {@return the address of the CTU holding a luma position}
     *
     * @param x the luma column
     * @param y the luma row
     */
    private int ctuOf(int x, int y) {
        return (y >> f.log2Ctu) * f.widthInCtus + (x >> f.log2Ctu);
    }

    /**
     * {@return whether the deblocking filter may filter an edge between two CTUs (the slice, tile and subpicture
     * restrictions, with the subpicture flags of both sides)}
     *
     * @param ctuA the CTU on one side
     * @param ctuB the CTU on the other side
     */
    private boolean acrossAllowed(int ctuA, int ctuB) {
        if (ctuA == ctuB) {
            return true;
        }
        if (!pps.loopFilterAcrossSlices && f.ctuSlice[ctuA] != f.ctuSlice[ctuB]) {
            return false;
        }
        if (!pps.loopFilterAcrossTiles && f.ctuTile[ctuA] != f.ctuTile[ctuB]) {
            return false;
        }
        if (sps.subpicInfoPresent) {
            var a = f.ctuSubpic[ctuA];
            var b = f.ctuSubpic[ctuB];
            return a == b || (sps.loopFilterAcrossSubpic[a] && sps.loopFilterAcrossSubpic[b]);
        }
        return true;
    }

    /**
     * {@return whether SAO and ALF filtering the samples of a CTU may read the samples of another CTU (the slice, tile
     * and subpicture restrictions, with the subpicture flag of the filtered CTU)}
     *
     * @param ctu the CTU filtered
     * @param other the CTU whose samples would be read
     */
    private boolean readAllowed(int ctu, int other) {
        if (ctu == other) {
            return true;
        }
        if (!pps.loopFilterAcrossSlices && f.ctuSlice[ctu] != f.ctuSlice[other]) {
            return false;
        }
        if (!pps.loopFilterAcrossTiles && f.ctuTile[ctu] != f.ctuTile[other]) {
            return false;
        }
        return !sps.subpicInfoPresent || f.ctuSubpic[ctu] == f.ctuSubpic[other]
                || sps.loopFilterAcrossSubpic[f.ctuSubpic[ctu]];
    }

    /**
     * {@return whether a luma column (for vertical edges) or row (for horizontal edges) is a virtual boundary}
     *
     * @param dir 0 for a column, 1 for a row
     * @param pos the luma column or row
     */
    private boolean virtualBoundary(int dir, int pos) {
        if (!ph.virtualBoundariesPresent) {
            return false;
        }
        if (dir == 0) {
            for (var i = 0; i < ph.numVerVirtualBoundaries; i++) {
                if (ph.virtualBoundaryX[i] == pos) {
                    return true;
                }
            }
        } else {
            for (var i = 0; i < ph.numHorVirtualBoundaries; i++) {
                if (ph.virtualBoundaryY[i] == pos) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Derives the boundary strengths and luma filter lengths of the edge segments of one direction in a band of
     * 4x4 block rows (8.8.3.3 to 8.8.3.5), whose strengths were cleared.
     *
     * @param dir 0 for vertical edges, 1 for horizontal edges
     * @param firstRow the first 4x4 block row
     * @param endRow the 4x4 block row after the last one
     */
    private void derive(int dir, int firstRow, int endRow) {
        var w4 = f.width4;
        var bsMap = bs[dir];
        var lenMap = len[dir];
        var tuMaskY = dir == 0 ? 0x03 : 0x30;
        var cuMaskY = dir == 0 ? 0x02 : 0x20;
        var tuMaskC = dir == 0 ? 0x0C : 0xC0;
        var cuMaskC = dir == 0 ? 0x08 : 0x80;
        var chromaGrid = chromaFormat == 0 ? 0 : (dir == 0 ? (8 << sx) - 1 : (8 << sy) - 1);
        for (var y4 = Math.max(dir, firstRow); y4 < endRow; y4++) {
            for (var x4 = 1 - dir; x4 < w4; x4++) {
                var i = y4 * w4 + x4;
                var x = x4 << 2;
                var y = y4 << 2;
                var xP = dir == 0 ? x - 1 : x;
                var yP = dir == 0 ? y : y - 1;
                var iP = dir == 0 ? i - 1 : i - w4;
                var ctuQ = ctuOf(x, y);
                var header = f.ctuHeader[ctuQ];
                if (header == null || header.deblockingDisabled) {
                    continue;
                }
                var perp = dir == 0 ? x : y;
                if (virtualBoundary(dir, perp)) {
                    continue;
                }
                var e = f.edges[i] & 0xFF;
                var ctuP = ctuOf(xP, yP);
                var value = 0;
                var cuQ = f.cuAt(x, y, false);
                if (cuQ != null) {
                    var tu = (e & tuMaskY) != 0;
                    var cuEdge = (e & cuMaskY) != 0;
                    var rel = perp - (dir == 0 ? cuQ.x : cuQ.y);
                    var subblock = cuQ.affine || cuQ.sbtmvp;
                    var sub = subblock && rel > 0 && (rel & 7) == 0;
                    if ((tu || sub) && (!cuEdge || acrossAllowed(ctuP, ctuQ))) {
                        value = lumaStrength(i, iP, xP, yP, x, y, tu, cuEdge || sub, ctuP, ctuQ);
                        lenMap[i] = (byte) lumaLengths(dir, i, iP, xP, yP, cuQ, rel, tu, subblock);
                    }
                }
                if (chromaGrid != 0 && (perp & chromaGrid) == 0 && (e & tuMaskC) != 0
                        && ((e & cuMaskC) == 0 || acrossAllowed(ctuP, ctuQ))) {
                    value |= chromaStrength(dir, i, iP);
                }
                bsMap[i] = (byte) value;
            }
        }
    }

    /**
     * Derives the boundary strength of a luma edge segment (8.8.3.5): 2 for an intra side, 1 for a transform edge with
     * coded coefficients, a motion edge with different references, vectors or vector counts, or IBC sides with distant
     * vectors, 0 otherwise.
     *
     * @param i the 4x4 block index of the Q side
     * @param iP the 4x4 block index of the P side
     * @param xP the luma column of the P sample
     * @param yP the luma row of the P sample
     * @param x the luma column of the Q sample
     * @param y the luma row of the Q sample
     * @param tu whether the edge is a transform block edge
     * @param motionEdge whether the edge is a coding or prediction block edge
     * @param ctuP the CTU of the P side
     * @param ctuQ the CTU of the Q side
     * @return the boundary strength
     */
    private int lumaStrength(int i, int iP, int xP, int yP, int x, int y, boolean tu, boolean motionEdge, int ctuP,
            int ctuQ) {
        var infoP = f.blockInfo[iP];
        var infoQ = f.blockInfo[i];
        if (((infoP | infoQ) & 0x08) != 0) {
            return (infoP & infoQ & 0x10) != 0 ? 0 : 2;
        }
        if (tu && ((infoP | infoQ) & 0x01) != 0) {
            return 1;
        }
        if (!motionEdge) {
            return 0;
        }
        if (((infoP ^ infoQ) & 0x40) != 0) {
            return 1;
        }
        if ((infoQ & 0x40) != 0) {
            return Math.abs(f.bvx[i] - f.bvx[iP]) >= 8 || Math.abs(f.bvy[i] - f.bvy[iP]) >= 8 ? 1 : 0;
        }
        var q4 = i;
        var p4 = iP;
        var shP = f.ctuHeader[ctuP];
        var shQ = f.ctuHeader[ctuQ];
        var refP0 = f.sref0[p4] >= 0 ? shP.refPics[0][f.sref0[p4]] : null;
        var refP1 = f.sref1[p4] >= 0 ? shP.refPics[1][f.sref1[p4]] : null;
        var refQ0 = f.sref0[q4] >= 0 ? shQ.refPics[0][f.sref0[q4]] : null;
        var refQ1 = f.sref1[q4] >= 0 ? shQ.refPics[1][f.sref1[q4]] : null;
        var p0x = refP0 != null ? f.smvx0[p4] : 0;
        var p0y = refP0 != null ? f.smvy0[p4] : 0;
        var p1x = refP1 != null ? f.smvx1[p4] : 0;
        var p1y = refP1 != null ? f.smvy1[p4] : 0;
        var q0x = refQ0 != null ? f.smvx0[q4] : 0;
        var q0y = refQ0 != null ? f.smvy0[q4] : 0;
        var q1x = refQ1 != null ? f.smvx1[q4] : 0;
        var q1y = refQ1 != null ? f.smvy1[q4] : 0;
        if (!((refP0 == refQ0 && refP1 == refQ1) || (refP0 == refQ1 && refP1 == refQ0))) {
            return 1;
        }
        if (refP0 != refP1) {
            if (refP0 == refQ0) {
                return far(q0x, q0y, p0x, p0y) || far(q1x, q1y, p1x, p1y) ? 1 : 0;
            }
            return far(q1x, q1y, p0x, p0y) || far(q0x, q0y, p1x, p1y) ? 1 : 0;
        }
        return (far(q0x, q0y, p0x, p0y) || far(q1x, q1y, p1x, p1y))
                && (far(q1x, q1y, p0x, p0y) || far(q0x, q0y, p1x, p1y)) ? 1 : 0;
    }

    /**
     * {@return whether two vectors differ by 8 or more in 1/16 luma samples, horizontally or vertically}
     *
     * @param ax the horizontal component of the first vector
     * @param ay the vertical component of the first vector
     * @param bx the horizontal component of the second vector
     * @param by the vertical component of the second vector
     */
    private static boolean far(int ax, int ay, int bx, int by) {
        return Math.abs(ax - bx) >= 8 || Math.abs(ay - by) >= 8;
    }

    /**
     * {@return the size of a transform block across an edge of a direction: its width for a vertical edge, its height
     * otherwise}
     *
     * @param widths the transform block widths
     * @param heights the transform block heights
     * @param i the 4x4 block index
     * @param dir the edge direction
     */
    private static int perpSize(byte[] widths, byte[] heights, int i, int dir) {
        return dir == 0 ? widths[i] : heights[i];
    }

    /**
     * Derives {@code maxFilterLengthP} and {@code maxFilterLengthQ} of a luma edge segment (8.8.3.3) from the transform
     * block sizes and, at prediction subblock edges, the subblock grid.
     *
     * @param dir the edge direction
     * @param i the 4x4 block index of the Q side
     * @param iP the 4x4 block index of the P side
     * @param xP the luma column of the P sample
     * @param yP the luma row of the P sample
     * @param cuQ the coding unit of the Q side
     * @param rel the position of the edge inside the coding unit of the Q side
     * @param tu whether the edge is a transform block edge
     * @param subblock whether the edge is a prediction subblock edge
     * @return {@code maxFilterLengthP} in bits 4 to 6 and {@code maxFilterLengthQ} in bits 0 to 2
     */
    private int lumaLengths(int dir, int i, int iP, int xP, int yP, VvcCu cuQ, int rel, boolean tu, boolean subblock) {
        var lp = 3;
        var lq = 3;
        if (tu) {
            var sizeQ = perpSize(f.tbLumaW, f.tbLumaH, i, dir);
            var sizeP = perpSize(f.tbLumaW, f.tbLumaH, iP, dir);
            if (sizeP <= 4 || sizeQ <= 4) {
                lp = 1;
                lq = 1;
            } else {
                var cuP = f.cuAt(xP, yP, false);
                lp = sizeP >= 32 ? (cuP != null && cuP.affine ? 5 : 7) : 3;
                lq = sizeQ >= 32 ? 7 : 3;
            }
        }
        if (subblock && (rel & 7) == 0) {
            var size = dir == 0 ? cuQ.w : cuQ.h;
            var step = dir == 0 ? 1 : f.width4;
            if (tu) {
                lq = Math.min(lq, 5);
                if (rel > 0) {
                    lp = Math.min(lp, 5);
                }
            } else if (rel > 0 && (tuEdgeAt(dir, i - step, rel - 4) || rel + 4 >= size
                    || tuEdgeAt(dir, i + step, rel + 4))) {
                lp = 1;
                lq = 1;
            } else if (rel > 0 && (rel == 8 || tuEdgeAt(dir, i - 2 * step, rel - 8) || rel + 8 >= size
                    || tuEdgeAt(dir, i + 2 * step, rel + 8))) {
                lp = 2;
                lq = 2;
            } else {
                lp = 3;
                lq = 3;
            }
        }
        return (lp << 4) | lq;
    }

    /**
     * {@return whether the 4x4 block at an index inside the current coding unit has a filtered luma transform edge}
     *
     * @param dir the edge direction
     * @param index the 4x4 block index
     * @param rel the position of the block inside the coding unit
     */
    private boolean tuEdgeAt(int dir, int index, int rel) {
        if (rel <= 0) {
            return false;
        }
        var mask = dir == 0 ? 0x03 : 0x30;
        if ((f.edges[index] & mask) == 0) {
            return false;
        }
        var w4 = f.width4;
        var perp = dir == 0 ? (index % w4) << 2 : (index / w4) << 2;
        return !virtualBoundary(dir, perp);
    }

    /**
     * Derives the boundary strengths of the Cb and Cr edge segments of an edge (8.8.3.5): 2 for an intra side, 1 for a
     * chroma transform edge whose side codes coefficients of that component or joint residuals, 0 otherwise.
     *
     * @param dir the edge direction
     * @param i the 4x4 block index of the Q side
     * @param iP the 4x4 block index of the P side
     * @return the Cb strength in bits 0 and 1 and the Cr strength in bits 2 and 3
     */
    private int chromaStrength(int dir, int i, int iP) {
        var infoP = f.blockInfo[iP];
        var infoQ = f.blockInfo[i];
        int cb;
        int cr;
        if (((infoP | infoQ) & 0x80) != 0) {
            cb = (infoP & infoQ & 0x20) != 0 ? 0 : 2;
            cr = cb;
        } else {
            cb = ((infoP | infoQ) & 0x02) != 0 ? 1 : 0;
            cr = ((infoP | infoQ) & 0x04) != 0 ? 1 : 0;
        }
        var large = perpSize(f.tbChromaW, f.tbChromaH, i, dir) >= 8 && perpSize(f.tbChromaW, f.tbChromaH, iP, dir) >= 8;
        return (cb << 2) | (cr << 4) | (large ? 0x40 : 0);
    }

    /**
     * Filters the edge segments of one direction in a band of 4x4 block rows (8.8.3.6).
     *
     * @param dir 0 for vertical edges, 1 for horizontal edges
     * @param firstRow the first 4x4 block row
     * @param endRow the 4x4 block row after the last one
     */
    private void filterEdges(int dir, int firstRow, int endRow) {
        var w4 = f.width4;
        var bsMap = bs[dir];
        var lenMap = len[dir];
        var pic = f.pic;
        var luma = pic.planes[0];
        var stride = pic.planeWidth[0];
        var offset = dir == 0 ? 1 : stride;
        var step = dir == 0 ? stride : 1;
        var ctuMask = f.ctuSize - 1;
        for (var y4 = firstRow; y4 < endRow; y4++) {
            for (var x4 = 0; x4 < w4; x4++) {
                var i = y4 * w4 + x4;
                var b = bsMap[i] & 3;
                if (b == 0) {
                    continue;
                }
                var x = x4 << 2;
                var y = y4 << 2;
                var header = f.ctuHeader[ctuOf(x, y)];
                var iP = dir == 0 ? i - 1 : i - w4;
                var qp = (f.qp4[i] + f.qp4[iP] + 1) >> 1;
                var l = lenMap[i];
                filterLuma(luma, y * stride + x, offset, step, b, qp, (l >> 4) & 7, l & 7,
                        dir == 1 && (y & ctuMask) == 0, header.betaOffset, header.tcOffset);
            }
        }
        if (chromaFormat == 0) {
            return;
        }
        var cStride = pic.planeWidth[1];
        var cOffset = dir == 0 ? 1 : cStride;
        var cStep = dir == 0 ? cStride : 1;
        var lines = dir == 0 ? 4 >> sy : 4 >> sx;
        var subsampled = dir == 0 ? sy : sx;
        var chromaCtuMask = (f.ctuSize >> sy) - 1;
        var bd = sps.qpBdOffset;
        for (var y4 = firstRow; y4 < endRow; y4++) {
            for (var x4 = 0; x4 < w4; x4++) {
                var i = y4 * w4 + x4;
                var b = bsMap[i] & 0x7C;
                if ((b & 0x3C) == 0) {
                    continue;
                }
                var x = x4 << 2;
                var y = y4 << 2;
                var header = f.ctuHeader[ctuOf(x, y)];
                var iP = dir == 0 ? i - 1 : i - w4;
                var large = (b & 0x40) != 0;
                var cy = y >> sy;
                var horCtu = dir == 1 && (cy & chromaCtuMask) == 0;
                var pos = cy * cStride + (x >> sx);
                for (var c = 0; c < 2; c++) {
                    var cbs = (b >> (2 + 2 * c)) & 3;
                    if (cbs == 2 || (large && cbs == 1)) {
                        var qp = c == 0 ? (f.qpCb4[i] + f.qpCb4[iP] + 1) >> 1 : (f.qpCr4[i] + f.qpCr4[iP] + 1) >> 1;
                        var tcOffset = c == 0 ? header.cbTcOffset : header.crTcOffset;
                        var betaOffset = c == 0 ? header.cbBetaOffset : header.crBetaOffset;
                        filterChroma(pic.planes[1 + c], pos, cOffset, cStep, lines, subsampled, cbs, qp, large,
                                horCtu, betaOffset, tcOffset);
                    }
                }
            }
        }
    }

    /**
     * {@return {@code tC} for a quantization parameter (8.8.3.6.2)}
     *
     * @param qp the average QP of the two sides
     * @param b the boundary strength
     * @param tcOffsetDiv2 the slice's {@code tc_offset_div2}
     */
    private int tc(int qp, int b, int tcOffsetDiv2) {
        var index = Math.clamp(qp + 2 * (b - 1) + 2 * tcOffsetDiv2, 0, 65);
        return bitDepth < 10 ? (TC[index] + (1 << (9 - bitDepth))) >> (10 - bitDepth) : TC[index] << (bitDepth - 10);
    }

    /**
     * {@return the second derivative of the P side, {@code dp}, of a line}
     *
     * @param s the plane
     * @param p the index of the first Q sample of the line
     * @param o the step across the edge
     */
    private static int dp(short[] s, int p, int o) {
        return Math.abs(s[p - 3 * o] - 2 * s[p - 2 * o] + s[p - o]);
    }

    /**
     * {@return the second derivative of the Q side, {@code dq}, of a line}
     *
     * @param s the plane
     * @param p the index of the first Q sample of the line
     * @param o the step across the edge
     */
    private static int dq(short[] s, int p, int o) {
        return Math.abs(s[p] - 2 * s[p + o] + s[p + 2 * o]);
    }

    /**
     * Returns the strong filter decision {@code dSam} of a line (8.8.3.6.6) for the short or long filters.
     *
     * @param s the plane
     * @param p the index of the first Q sample of the line
     * @param o the step across the edge
     * @param d twice the line's activity
     * @param beta {@code beta}
     * @param tc {@code tC}
     * @param largeP whether the P side uses the long filter
     * @param largeQ whether the Q side uses the long filter
     * @param lenP {@code maxFilterLengthP}
     * @param lenQ {@code maxFilterLengthQ}
     * @param chromaHorCtu whether the line crosses a horizontal CTU boundary of a chroma plane, where the P side reads
     * one sample less
     * @return whether the strong filter applies to the line
     */
    private static boolean strong(short[] s, int p, int o, int d, int beta, int tc, boolean largeP, boolean largeQ,
            int lenP, int lenQ, boolean chromaHorCtu) {
        int m3 = s[p - o];
        int m4 = s[p];
        if (!(d < (beta >> 2) && Math.abs(m3 - m4) < ((tc * 5 + 1) >> 1))) {
            return false;
        }
        int m0 = s[p - 4 * o];
        int m7 = s[p + 3 * o];
        var sp3 = chromaHorCtu ? Math.abs(s[p - 2 * o] - m3) : Math.abs(m0 - m3);
        var sq3 = Math.abs(m7 - m4);
        if (largeP || largeQ) {
            if (largeP) {
                int mP4 = s[p - o * lenP - o];
                if (lenP == 7) {
                    sp3 += Math.abs(s[p - 5 * o] - s[p - 6 * o] - s[p - 7 * o] + mP4);
                }
                sp3 = (sp3 + Math.abs(m0 - mP4) + 1) >> 1;
            }
            if (largeQ) {
                int m11 = s[p + o * lenQ];
                if (lenQ == 7) {
                    sq3 += Math.abs(s[p + 4 * o] - s[p + 5 * o] - s[p + 6 * o] + m11);
                }
                sq3 = (sq3 + Math.abs(m11 - m7) + 1) >> 1;
            }
            return sp3 + sq3 < ((beta * 3) >> 5) && d < (beta >> 4) && Math.abs(m3 - m4) < ((tc * 5 + 1) >> 1);
        }
        return sp3 + sq3 < (beta >> 3);
    }

    /**
     * Filters a luma edge segment of four lines (8.8.3.6.2 to 8.8.3.6.7): the decisions, with the luma adaptive QP
     * offset, then the long, strong or weak filters.
     *
     * @param s the luma plane
     * @param pos the index of the first Q sample of the segment
     * @param o the step across the edge
     * @param step the step along the edge
     * @param b the boundary strength
     * @param qp the average QP of the two sides
     * @param lenP {@code maxFilterLengthP}
     * @param lenQ {@code maxFilterLengthQ}
     * @param horCtuBoundary whether the edge is a horizontal CTU boundary, which limits the P side to the short filters
     * @param betaOffsetDiv2 the slice's {@code beta_offset_div2}
     * @param tcOffsetDiv2 the slice's {@code tc_offset_div2}
     */
    private void filterLuma(short[] s, int pos, int o, int step, int b, int qp, int lenP, int lenQ,
            boolean horCtuBoundary, int betaOffsetDiv2, int tcOffsetDiv2) {
        var largeP = lenP > 3 && !horCtuBoundary;
        var largeQ = lenQ > 3;
        if (sps.ladf) {
            var level = (s[pos] + s[pos + 3 * step] + s[pos - o] + s[pos + 3 * step - o]) >> 2;
            var shift = sps.ladfQpOffset[0];
            for (var k = 1; k < sps.ladfNumIntervals && level > sps.ladfLowerBound[k]; k++) {
                shift = sps.ladfQpOffset[k];
            }
            qp += shift;
        }
        var tc = tc(qp, b, tcOffsetDiv2);
        var beta = BETA[Math.clamp(qp + 2 * betaOffsetDiv2, 0, 63)] << (bitDepth - 8);
        var sideThreshold = (beta + (beta >> 1)) >> 3;
        var p3 = pos + 3 * step;
        var dp0 = dp(s, pos, o);
        var dq0 = dq(s, pos, o);
        var dp3 = dp(s, p3, o);
        var dq3 = dq(s, p3, o);
        var d0 = dp0 + dq0;
        var d3 = dp3 + dq3;
        if (largeP || largeQ) {
            var o3 = 3 * o;
            var dp0L = largeP ? (dp0 + dp(s, pos - o3, o) + 1) >> 1 : dp0;
            var dq0L = largeQ ? (dq0 + dq(s, pos + o3, o) + 1) >> 1 : dq0;
            var dp3L = largeP ? (dp3 + dp(s, p3 - o3, o) + 1) >> 1 : dp3;
            var dq3L = largeQ ? (dq3 + dq(s, p3 + o3, o) + 1) >> 1 : dq3;
            var d0L = dp0L + dq0L;
            var d3L = dp3L + dq3L;
            if (d0L + d3L < beta && strong(s, pos, o, 2 * d0L, beta, tc, largeP, largeQ, lenP, lenQ, false)
                    && strong(s, p3, o, 2 * d3L, beta, tc, largeP, largeQ, lenP, lenQ, false)) {
                filterLong(s, pos, step, o, largeP ? lenP : 3, largeQ ? lenQ : 3, tc);
                return;
            }
        }
        if (d0 + d3 >= beta) {
            return;
        }
        var filterP = false;
        var filterQ = false;
        if (lenP > 1 && lenQ > 1) {
            filterP = dp0 + dp3 < sideThreshold;
            filterQ = dq0 + dq3 < sideThreshold;
        }
        var sw = lenP > 2 && lenQ > 2 && strong(s, pos, o, 2 * d0, beta, tc, false, false, 7, 7, false)
                && strong(s, p3, o, 2 * d3, beta, tc, false, false, 7, 7, false);
        var thrCut = tc * 10;
        for (var k = 0; k < 4; k++) {
            filterLumaLine(s, pos + k * step, o, tc, sw, thrCut, filterP, filterQ);
        }
    }

    /**
     * Filters one line of a luma edge with the strong or weak filter (8.8.3.6.7).
     *
     * @param s the luma plane
     * @param p the index of the first Q sample of the line
     * @param o the step across the edge
     * @param tc {@code tC}
     * @param sw whether the strong filter applies
     * @param thrCut the weak filter's threshold, {@code tC * 10}
     * @param filterP whether the weak filter modifies the second P sample
     * @param filterQ whether the weak filter modifies the second Q sample
     */
    private void filterLumaLine(short[] s, int p, int o, int tc, boolean sw, int thrCut, boolean filterP,
            boolean filterQ) {
        int m1 = s[p - 3 * o];
        int m2 = s[p - 2 * o];
        int m3 = s[p - o];
        int m4 = s[p];
        int m5 = s[p + o];
        int m6 = s[p + 2 * o];
        if (sw) {
            int m0 = s[p - 4 * o];
            int m7 = s[p + 3 * o];
            s[p - 3 * o] = (short) Math.clamp((2 * m0 + 3 * m1 + m2 + m3 + m4 + 4) >> 3, m1 - tc, m1 + tc);
            s[p - 2 * o] = (short) Math.clamp((m1 + m2 + m3 + m4 + 2) >> 2, m2 - 2 * tc, m2 + 2 * tc);
            s[p - o] = (short) Math.clamp((m1 + 2 * m2 + 2 * m3 + 2 * m4 + m5 + 4) >> 3, m3 - 3 * tc, m3 + 3 * tc);
            s[p] = (short) Math.clamp((m2 + 2 * m3 + 2 * m4 + 2 * m5 + m6 + 4) >> 3, m4 - 3 * tc, m4 + 3 * tc);
            s[p + o] = (short) Math.clamp((m3 + m4 + m5 + m6 + 2) >> 2, m5 - 2 * tc, m5 + 2 * tc);
            s[p + 2 * o] = (short) Math.clamp((m3 + m4 + m5 + 3 * m6 + 2 * m7 + 4) >> 3, m6 - tc, m6 + tc);
            return;
        }
        var delta = (9 * (m4 - m3) - 3 * (m5 - m2) + 8) >> 4;
        if (Math.abs(delta) >= thrCut) {
            return;
        }
        delta = Math.clamp(delta, -tc, tc);
        var tc2 = tc >> 1;
        s[p - o] = (short) Math.clamp(m3 + delta, 0, maxVal);
        if (filterP) {
            var delta1 = Math.clamp((((m1 + m3 + 1) >> 1) - m2 + delta) >> 1, -tc2, tc2);
            s[p - 2 * o] = (short) Math.clamp(m2 + delta1, 0, maxVal);
        }
        s[p] = (short) Math.clamp(m4 - delta, 0, maxVal);
        if (filterQ) {
            var delta2 = Math.clamp((((m6 + m4 + 1) >> 1) - m5 - delta) >> 1, -tc2, tc2);
            s[p + o] = (short) Math.clamp(m5 + delta2, 0, maxVal);
        }
    }

    /**
     * Applies the long luma filter of 8.8.3.6.7 to the four lines of an edge segment.
     *
     * @param s the luma plane
     * @param pos the index of the first Q sample of the segment
     * @param step the step along the edge
     * @param o the step across the edge
     * @param nP the filter length of the P side
     * @param nQ the filter length of the Q side
     * @param tc {@code tC}
     */
    private static void filterLong(short[] s, int pos, int step, int o, int nP, int nQ, int tc) {
        var coeffP = nP == 7 ? COEFF7 : nP == 5 ? COEFF5 : COEFF3;
        var coeffQ = nQ == 7 ? COEFF7 : nQ == 5 ? COEFF5 : COEFF3;
        var tcP = nP == 3 ? TC3 : TC7;
        var tcQ = nQ == 3 ? TC3 : TC7;
        for (var k = 0; k < 4; k++) {
            var q = pos + k * step;
            var p = q - o;
            var refP = (s[p - (nP - 1) * o] + s[p - nP * o] + 1) >> 1;
            var refQ = (s[q + (nQ - 1) * o] + s[q + nQ * o] + 1) >> 1;
            int middle;
            if (nP == nQ) {
                if (nP == 5) {
                    middle = (2 * (s[p] + s[q] + s[p - o] + s[q + o] + s[p - 2 * o] + s[q + 2 * o]) + s[p - 3 * o]
                            + s[q + 3 * o] + s[p - 4 * o] + s[q + 4 * o] + 8) >> 4;
                } else {
                    middle = (2 * (s[p] + s[q]) + s[p - o] + s[q + o] + s[p - 2 * o] + s[q + 2 * o] + s[p - 3 * o]
                            + s[q + 3 * o] + s[p - 4 * o] + s[q + 4 * o] + s[p - 5 * o] + s[q + 5 * o] + s[p - 6 * o]
                            + s[q + 6 * o] + 8) >> 4;
                }
            } else {
                var pt = p;
                var qt = q;
                var oP = -o;
                var oQ = o;
                var bigN = nP;
                var smallN = nQ;
                if (nQ > nP) {
                    pt = q;
                    qt = p;
                    oP = o;
                    oQ = -o;
                    bigN = nQ;
                    smallN = nP;
                }
                if (bigN == 7 && smallN == 5) {
                    middle = (2 * (s[p] + s[q] + s[p - o] + s[q + o]) + s[p - 2 * o] + s[q + 2 * o] + s[p - 3 * o]
                            + s[q + 3 * o] + s[p - 4 * o] + s[q + 4 * o] + s[p - 5 * o] + s[q + 5 * o] + 8) >> 4;
                } else if (bigN == 7) {
                    middle = (2 * (s[pt] + s[qt]) + s[qt] + 2 * (s[qt + oQ] + s[qt + 2 * oQ]) + s[pt + oP]
                            + s[qt + oQ] + s[pt + 2 * oP] + s[pt + 3 * oP] + s[pt + 4 * oP] + s[pt + 5 * oP]
                            + s[pt + 6 * oP] + 8) >> 4;
                } else {
                    middle = (s[p] + s[q] + s[p - o] + s[q + o] + s[p - 2 * o] + s[q + 2 * o] + s[p - 3 * o]
                            + s[q + 3 * o] + 4) >> 3;
                }
            }
            for (var n = 0; n < nP; n++) {
                var at = p - o * n;
                int v = s[at];
                var limit = (tc * tcP[n]) >> 1;
                s[at] = (short) Math.clamp((middle * coeffP[n] + refP * (64 - coeffP[n]) + 32) >> 6, v - limit,
                        v + limit);
            }
            for (var n = 0; n < nQ; n++) {
                var at = q + o * n;
                int v = s[at];
                var limit = (tc * tcQ[n]) >> 1;
                s[at] = (short) Math.clamp((middle * coeffQ[n] + refQ * (64 - coeffQ[n]) + 32) >> 6, v - limit,
                        v + limit);
            }
        }
    }

    /**
     * Filters a chroma edge segment (8.8.3.6.4 and 8.8.3.6.5): the decisions, then the strong or weak chroma filter.
     *
     * @param s the chroma plane
     * @param pos the index of the first Q sample of the segment
     * @param o the step across the edge
     * @param step the step along the edge
     * @param lines the number of lines of the segment
     * @param subsampled the subsampling shift along the edge
     * @param b the boundary strength
     * @param qp the chroma QP of the edge
     * @param large whether both transform blocks are at least 8 samples deep across the edge
     * @param horCtu whether the edge is a horizontal CTU boundary
     * @param betaOffsetDiv2 the slice's {@code beta_offset_div2} of the component
     * @param tcOffsetDiv2 the slice's {@code tc_offset_div2} of the component
     */
    private void filterChroma(short[] s, int pos, int o, int step, int lines, int subsampled, int b, int qp,
            boolean large, boolean horCtu, int betaOffsetDiv2, int tcOffsetDiv2) {
        var tc = tc(qp, b, tcOffsetDiv2);
        var sw = false;
        if (large) {
            var beta = BETA[Math.clamp(qp + 2 * betaOffsetDiv2, 0, 63)] << (bitDepth - 8);
            var p3 = pos + (subsampled == 1 ? step : 3 * step);
            var dp0 = horCtu ? Math.abs(s[pos - 2 * o] - 2 * s[pos - 2 * o] + s[pos - o]) : dp(s, pos, o);
            var dq0 = dq(s, pos, o);
            var dp3 = horCtu ? Math.abs(s[p3 - 2 * o] - 2 * s[p3 - 2 * o] + s[p3 - o]) : dp(s, p3, o);
            var dq3 = dq(s, p3, o);
            var d0 = dp0 + dq0;
            var d3 = dp3 + dq3;
            if (d0 + d3 < beta) {
                sw = strong(s, pos, o, 2 * d0, beta, tc, false, false, 7, 7, horCtu)
                        && strong(s, p3, o, 2 * d3, beta, tc, false, false, 7, 7, horCtu);
            }
        }
        for (var k = 0; k < lines; k++) {
            filterChromaLine(s, pos + k * step, o, tc, sw, horCtu);
        }
    }

    /**
     * Filters one line of a chroma edge with the strong or weak filter (8.8.3.6.10).
     *
     * @param s the chroma plane
     * @param p the index of the first Q sample of the line
     * @param o the step across the edge
     * @param tc {@code tC}
     * @param sw whether the strong filter applies
     * @param horCtu whether the line crosses a horizontal CTU boundary, where the P side keeps its far samples
     */
    private void filterChromaLine(short[] s, int p, int o, int tc, boolean sw, boolean horCtu) {
        int m2 = s[p - 2 * o];
        int m3 = s[p - o];
        int m4 = s[p];
        int m5 = s[p + o];
        if (sw) {
            int m6 = s[p + 2 * o];
            int m7 = s[p + 3 * o];
            if (horCtu) {
                s[p - o] = (short) Math.clamp((3 * m2 + 2 * m3 + m4 + m5 + m6 + 4) >> 3, m3 - tc, m3 + tc);
                s[p] = (short) Math.clamp((2 * m2 + m3 + 2 * m4 + m5 + m6 + m7 + 4) >> 3, m4 - tc, m4 + tc);
                s[p + o] = (short) Math.clamp((m2 + m3 + m4 + 2 * m5 + m6 + 2 * m7 + 4) >> 3, m5 - tc, m5 + tc);
                s[p + 2 * o] = (short) Math.clamp((m3 + m4 + m5 + 2 * m6 + 3 * m7 + 4) >> 3, m6 - tc, m6 + tc);
            } else {
                int m0 = s[p - 4 * o];
                int m1 = s[p - 3 * o];
                s[p - 3 * o] = (short) Math.clamp((3 * m0 + 2 * m1 + m2 + m3 + m4 + 4) >> 3, m1 - tc, m1 + tc);
                s[p - 2 * o] = (short) Math.clamp((2 * m0 + m1 + 2 * m2 + m3 + m4 + m5 + 4) >> 3, m2 - tc, m2 + tc);
                s[p - o] = (short) Math.clamp((m0 + m1 + m2 + 2 * m3 + m4 + m5 + m6 + 4) >> 3, m3 - tc, m3 + tc);
                s[p] = (short) Math.clamp((m1 + m2 + m3 + 2 * m4 + m5 + m6 + m7 + 4) >> 3, m4 - tc, m4 + tc);
                s[p + o] = (short) Math.clamp((m2 + m3 + m4 + 2 * m5 + m6 + 2 * m7 + 4) >> 3, m5 - tc, m5 + tc);
                s[p + 2 * o] = (short) Math.clamp((m3 + m4 + m5 + 2 * m6 + 3 * m7 + 4) >> 3, m6 - tc, m6 + tc);
            }
            return;
        }
        var delta = Math.clamp((((m4 - m3) * 4) + m2 - m5 + 4) >> 3, -tc, tc);
        s[p - o] = (short) Math.clamp(m3 + delta, 0, maxVal);
        s[p] = (short) Math.clamp(m4 - delta, 0, maxVal);
    }

    /**
     * Copies the picture into {@link #copy}, the input SAO or ALF reads.
     */
    private void snapshot() {
        var pic = f.pic;
        for (var c = 0; c < pic.planes.length; c++) {
            var plane = pic.planes[c];
            if (copy[c].length < plane.length) {
                copy[c] = new short[plane.length];
            }
            System.arraycopy(plane, 0, copy[c], 0, plane.length);
        }
    }

    /**
     * Applies sample adaptive offset to the CTUs of a band of CTU rows (8.8.4), reading the copy of the input.
     *
     * @param firstRow the first CTU row
     * @param endRow the CTU row after the last one
     */
    private void sao(int firstRow, int endRow) {
        var pic = f.pic;
        for (var ctu = firstRow * f.widthInCtus; ctu < endRow * f.widthInCtus; ctu++) {
            var header = f.ctuHeader[ctu];
            if (header == null) {
                continue;
            }
            var ctuX = ctu % f.widthInCtus;
            var ctuY = ctu / f.widthInCtus;
            for (var c = 0; c < pic.planes.length; c++) {
                var type = f.saoType[ctu][c];
                if (type == 0) {
                    continue;
                }
                var shiftX = c == 0 ? 0 : sx;
                var shiftY = c == 0 ? 0 : sy;
                var x0 = (ctuX << f.log2Ctu) >> shiftX;
                var y0 = (ctuY << f.log2Ctu) >> shiftY;
                var w = Math.min(f.ctuSize >> shiftX, pic.planeWidth[c] - x0);
                var h = Math.min(f.ctuSize >> shiftY, pic.planeHeight[c] - y0);
                if (type == 1) {
                    saoBand(c, ctu, x0, y0, w, h);
                } else {
                    saoEdge(c, ctu, x0, y0, w, h, shiftX, shiftY);
                }
            }
        }
    }

    /**
     * Applies the SAO band offset to a block of a component.
     *
     * @param c the component
     * @param ctu the CTU address
     * @param x0 the first column of the block
     * @param y0 the first row of the block
     * @param w the block width
     * @param h the block height
     */
    private void saoBand(int c, int ctu, int x0, int y0, int w, int h) {
        var table = saoTable;
        Arrays.fill(table, 0);
        var band = f.saoBand[ctu][c];
        var offsets = f.saoOffset[ctu][c];
        for (var k = 0; k < 4; k++) {
            table[(band + k) & 31] = offsets[k];
        }
        var src = copy[c];
        var dst = f.pic.planes[c];
        var stride = f.pic.planeWidth[c];
        var shift = bitDepth - 5;
        for (var y = y0; y < y0 + h; y++) {
            for (var x = x0; x < x0 + w; x++) {
                var i = y * stride + x;
                int v = src[i];
                dst[i] = (short) Math.clamp(v + table[v >> shift], 0, maxVal);
            }
        }
    }

    /**
     * Applies the SAO edge offset to a block of a component, leaving the samples whose neighbors are outside the
     * picture, across a boundary that forbids reading them or on a virtual boundary.
     *
     * @param c the component
     * @param ctu the CTU address
     * @param x0 the first column of the block
     * @param y0 the first row of the block
     * @param w the block width
     * @param h the block height
     * @param shiftX the horizontal subsampling shift of the component
     * @param shiftY the vertical subsampling shift of the component
     */
    private void saoEdge(int c, int ctu, int x0, int y0, int w, int h, int shiftX, int shiftY) {
        var cls = f.saoBand[ctu][c];
        var offsets = f.saoOffset[ctu][c];
        int dx;
        int dy;
        switch (cls) {
            case 0 -> {
                dx = 1;
                dy = 0;
            }
            case 1 -> {
                dx = 0;
                dy = 1;
            }
            case 2 -> {
                dx = 1;
                dy = 1;
            }
            default -> {
                dx = -1;
                dy = 1;
            }
        }
        var src = copy[c];
        var dst = f.pic.planes[c];
        var stride = f.pic.planeWidth[c];
        var pw = f.pic.planeWidth[c];
        var phh = f.pic.planeHeight[c];
        var log2CtuX = f.log2Ctu - shiftX;
        var log2CtuY = f.log2Ctu - shiftY;
        var checkVer = dx != 0;
        var checkHor = dy != 0;
        for (var y = y0; y < y0 + h; y++) {
            for (var x = x0; x < x0 + w; x++) {
                var ax = x - dx;
                var ay = y - dy;
                var bx = x + dx;
                var by = y + dy;
                if (ax < 0 || ay < 0 || bx < 0 || by < 0 || ax >= pw || bx >= pw || ay >= phh || by >= phh) {
                    continue;
                }
                var ctuA = (ay >> log2CtuY) * f.widthInCtus + (ax >> log2CtuX);
                var ctuB = (by >> log2CtuY) * f.widthInCtus + (bx >> log2CtuX);
                if (!readAllowed(ctu, ctuA) || !readAllowed(ctu, ctuB)) {
                    continue;
                }
                if (ph.virtualBoundariesPresent && saoVirtualBoundary(x, y, checkVer, checkHor, shiftX, shiftY)) {
                    continue;
                }
                var i = y * stride + x;
                int v = src[i];
                var edge = Integer.signum(v - src[ay * stride + ax]) + Integer.signum(v - src[by * stride + bx]);
                var offset = switch (edge) {
                    case -2 -> offsets[0];
                    case -1 -> offsets[1];
                    case 1 -> offsets[2];
                    case 2 -> offsets[3];
                    default -> 0;
                };
                dst[i] = (short) Math.clamp(v + offset, 0, maxVal);
            }
        }
    }

    /**
     * {@return whether a sample is next to a virtual boundary its edge class crosses}
     *
     * @param x the column
     * @param y the row
     * @param checkVer whether the class compares horizontal neighbors, which vertical boundaries separate
     * @param checkHor whether the class compares vertical neighbors, which horizontal boundaries separate
     * @param shiftX the horizontal subsampling shift of the component
     * @param shiftY the vertical subsampling shift of the component
     */
    private boolean saoVirtualBoundary(int x, int y, boolean checkVer, boolean checkHor, int shiftX, int shiftY) {
        if (checkVer) {
            for (var i = 0; i < ph.numVerVirtualBoundaries; i++) {
                var vb = ph.virtualBoundaryX[i] >> shiftX;
                if (x == vb || x == vb - 1) {
                    return true;
                }
            }
        }
        if (checkHor) {
            for (var i = 0; i < ph.numHorVirtualBoundaries; i++) {
                var vb = ph.virtualBoundaryY[i] >> shiftY;
                if (y == vb || y == vb - 1) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Applies the adaptive loop filter and the cross-component filter to the CTUs of a band of CTU rows (8.8.5),
     * reading the copy of the input.
     *
     * @param firstRow the first CTU row
     * @param endRow the CTU row after the last one
     */
    private void alf(int firstRow, int endRow) {
        for (var ctu = firstRow * f.widthInCtus; ctu < endRow * f.widthInCtus; ctu++) {
            var header = f.ctuHeader[ctu];
            if (header == null) {
                continue;
            }
            var luma = f.alfEnabled[ctu][0];
            var cb = f.alfEnabled[ctu][1];
            var cr = f.alfEnabled[ctu][2];
            var ccCb = header.ccAlfCb && f.ccAlfIdx[ctu][0] != 0;
            var ccCr = header.ccAlfCr && f.ccAlfIdx[ctu][1] != 0;
            if (!luma && !cb && !cr && !ccCb && !ccCr) {
                continue;
            }
            alfCtu(ctu, header, luma, cb, cr, ccCb, ccCr);
        }
    }

    /**
     * Applies ALF and CC-ALF to a CTU, region by region of the virtual boundaries inside it, each with the padding its
     * picture, slice, tile, subpicture and virtual boundaries require.
     *
     * @param ctu the CTU address
     * @param header the slice header of the CTU
     * @param luma whether the luma ALF applies
     * @param cb whether the Cb ALF applies
     * @param cr whether the Cr ALF applies
     * @param ccCb whether the Cb cross-component filter applies
     * @param ccCr whether the Cr cross-component filter applies
     */
    private void alfCtu(int ctu, VvcSliceHeader header, boolean luma, boolean cb, boolean cr, boolean ccCb,
            boolean ccCr) {
        var x0 = (ctu % f.widthInCtus) << f.log2Ctu;
        var y0 = (ctu / f.widthInCtus) << f.log2Ctu;
        var w = Math.min(f.ctuSize, f.width - x0);
        var h = Math.min(f.ctuSize, f.height - y0);
        var clipTop = false;
        var clipBottom = false;
        var clipLeft = false;
        var clipRight = false;
        var horVb = this.horVb;
        var verVb = this.verVb;
        var numHor = 0;
        var numVer = 0;
        if (ph.virtualBoundariesPresent) {
            for (var i = 0; i < ph.numHorVirtualBoundaries; i++) {
                var vb = ph.virtualBoundaryY[i];
                if (vb == y0) {
                    clipTop = true;
                } else if (vb == y0 + h) {
                    clipBottom = true;
                } else if (y0 < vb && vb < y0 + h) {
                    horVb[numHor++] = vb;
                }
            }
            for (var i = 0; i < ph.numVerVirtualBoundaries; i++) {
                var vb = ph.virtualBoundaryX[i];
                if (vb == x0) {
                    clipLeft = true;
                } else if (vb == x0 + w) {
                    clipRight = true;
                } else if (x0 < vb && vb < x0 + w) {
                    verVb[numVer++] = vb;
                }
            }
            Arrays.sort(horVb, 0, numHor);
            Arrays.sort(verVb, 0, numVer);
        }
        var cs = f.ctuSize;
        if (y0 >= cs && !clipTop && !readAllowed(ctu, ctu - f.widthInCtus)) {
            clipTop = true;
        }
        if (y0 + cs < f.height && !clipBottom && !readAllowed(ctu, ctu + f.widthInCtus)) {
            clipBottom = true;
        }
        if (x0 >= cs && !clipLeft && !readAllowed(ctu, ctu - 1)) {
            clipLeft = true;
        }
        if (x0 + cs < f.width && !clipRight && !readAllowed(ctu, ctu + 1)) {
            clipRight = true;
        }
        var rasterPad = 0;
        if (!pps.loopFilterAcrossSlices) {
            if (!clipTop && !clipLeft && x0 >= cs && y0 >= cs
                    && f.ctuSlice[ctu - f.widthInCtus - 1] != f.ctuSlice[ctu]) {
                rasterPad |= 1;
            }
            if (!clipBottom && !clipRight && x0 + cs < f.width && y0 + cs < f.height
                    && f.ctuSlice[ctu + f.widthInCtus + 1] != f.ctuSlice[ctu]) {
                rasterPad |= 2;
            }
        }
        var yStart = y0;
        for (var i = 0; i <= numHor; i++) {
            var yEnd = i == numHor ? y0 + h : horVb[i];
            var clipT = (i == 0 && clipTop) || i > 0 || yStart == 0;
            var clipB = (i == numHor && clipBottom) || i < numHor || yEnd == f.height;
            var xStart = x0;
            for (var j = 0; j <= numVer; j++) {
                var xEnd = j == numVer ? x0 + w : verVb[j];
                var clipL = (j == 0 && clipLeft) || j > 0 || xStart == 0;
                var clipR = (j == numVer && clipRight) || j < numVer || xEnd == f.width;
                var corner = 0;
                if (xStart == x0 && yStart == y0) {
                    corner |= rasterPad & 1;
                }
                if (xEnd == x0 + w && yEnd == y0 + h) {
                    corner |= rasterPad & 2;
                }
                regionX0 = xStart;
                regionY0 = yStart;
                regionX1 = xEnd;
                regionY1 = yEnd;
                setAvailable(xStart, yStart, xEnd, yEnd, clipL, clipR, clipT, clipB, ALF_PAD, corner);
                if (luma) {
                    alfLuma(header, ctu, xStart, yStart, xEnd - xStart, yEnd - yStart, y0);
                }
                if (chromaFormat != 0) {
                    var cxs = xStart >> sx;
                    var cys = yStart >> sy;
                    var cxe = xEnd >> sx;
                    var cye = yEnd >> sy;
                    for (var c = 1; c <= 2; c++) {
                        var enabled = c == 1 ? cb : cr;
                        var cc = c == 1 ? ccCb : ccCr;
                        if (!enabled && !cc) {
                            continue;
                        }
                        regionX0 = cxs;
                        regionY0 = cys;
                        regionX1 = cxe;
                        regionY1 = cye;
                        setAvailable(cxs, cys, cxe, cye, clipL, clipR, clipT, clipB, ALF_PAD >> sx, corner);
                        if (enabled) {
                            alfChroma(header, ctu, c, cxs, cys, cxe - cxs, cye - cys, y0 >> sy);
                        }
                        if (cc) {
                            regionX0 = xStart;
                            regionY0 = yStart;
                            regionX1 = xEnd;
                            regionY1 = yEnd;
                            setAvailable(xStart, yStart, xEnd, yEnd, clipL, clipR, clipT, clipB, ALF_PAD, corner);
                            ccAlf(header, ctu, c, cxs, cys, cxe - cxs, cye - cys, y0);
                        }
                    }
                }
                xStart = xEnd;
            }
            yStart = yEnd;
        }
    }

    /**
     * Sets the rectangle of samples the filters of a region may read: the region itself, extended by {@code pad}
     * samples on every side that is not clipped by a picture, slice, tile, subpicture or virtual boundary. Samples
     * outside the rectangle repeat its nearest sample.
     *
     * @param x0 the first column of the region
     * @param y0 the first row of the region
     * @param x1 the column after the region
     * @param y1 the row after the region
     * @param clipL whether a boundary clips the left side
     * @param clipR whether a boundary clips the right side
     * @param clipT whether a boundary clips the top side
     * @param clipB whether a boundary clips the bottom side
     * @param pad the samples readable beyond an unclipped side
     * @param corner the corners of the region padded for raster slices
     */
    private void setAvailable(int x0, int y0, int x1, int y1, boolean clipL, boolean clipR, boolean clipT,
            boolean clipB, int pad, int corner) {
        availX0 = clipL ? x0 : x0 - pad;
        availX1 = clipR ? x1 : x1 + pad;
        availY0 = clipT ? y0 : y0 - pad;
        availY1 = clipB ? y1 : y1 + pad;
        padCorner = corner;
    }

    /**
     * Copies a region of the filter input with a margin of {@link #PAD_MARGIN} samples into {@link #pad}, padded as
     * {@link #sample} pads it, so that the filters read it without bounds checks.
     *
     * @param src the input plane
     * @param stride the row stride of the plane
     * @param x0 the first column of the region
     * @param y0 the first row of the region
     * @param w the width of the region
     * @param h the height of the region
     */
    private void fillPad(short[] src, int stride, int x0, int y0, int w, int h) {
        var pw = w + 2 * PAD_MARGIN;
        var rows = h + 2 * PAD_MARGIN;
        if (pad.length < pw * rows) {
            pad = new int[pw * rows];
        }
        padStride = pw;
        padX = x0 - PAD_MARGIN;
        padY = y0 - PAD_MARGIN;
        for (var y = 0; y < rows; y++) {
            var base = y * pw;
            for (var x = 0; x < pw; x++) {
                pad[base + x] = sample(src, stride, padX + x, padY + y);
            }
        }
    }

    /**
     * Reads a sample of the padded region copy {@link #fillPad} made.
     *
     * @param x the picture column, within the region's margin
     * @param y the picture row, within the region's margin
     * @return the sample
     */
    private int at(int x, int y) {
        return pad[(y - padY) * padStride + x - padX];
    }

    /**
     * Reads a sample of the filter input with the padding of {@link #setAvailable}.
     *
     * @param src the plane
     * @param stride the row stride of the plane
     * @param x the column
     * @param y the row
     * @return the sample, or the nearest readable one
     */
    private int sample(short[] src, int stride, int x, int y) {
        var cx = Math.clamp(x, availX0, availX1 - 1);
        var cy = Math.clamp(y, availY0, availY1 - 1);
        if (padCorner != 0) {
            if ((padCorner & 1) != 0 && cx < regionX0 && cy < regionY0) {
                cx = regionX0;
            } else if ((padCorner & 2) != 0 && cx >= regionX1 && cy >= regionY1) {
                cx = regionX1 - 1;
            }
        }
        return src[cy * stride + cx];
    }

    /**
     * Applies the luma ALF to a region of a CTU (8.8.5.2): classifies each 4x4 block and filters it with its class's
     * filter.
     *
     * @param header the slice header of the CTU
     * @param ctu the CTU address
     * @param x0 the first column of the region
     * @param y0 the first row of the region
     * @param w the region width
     * @param h the region height
     * @param ctuY the first luma row of the CTU
     */
    private void alfLuma(VvcSliceHeader header, int ctu, int x0, int y0, int w, int h, int ctuY) {
        var set = f.alfFilterSet[ctu];
        var clipNone = 1 << bitDepth;
        if (set < 16) {
            for (var k = 0; k < 25; k++) {
                var filter = VvcAlfTables.CLASS_TO_FILTER[set * 25 + k];
                for (var j = 0; j < 12; j++) {
                    alfCoeff[k][j] = VvcAlfTables.FIXED_COEFF[filter * 12 + j];
                    alfClip[k][j] = clipNone;
                }
            }
        } else {
            var aps = header.alfLumaApsRefs[set - 16];
            for (var k = 0; k < 25; k++) {
                var filter = aps.alfLumaFilterIdx[k];
                for (var j = 0; j < 12; j++) {
                    alfCoeff[k][j] = aps.alfLumaCoeff[filter][j];
                    alfClip[k][j] = aps.alfLumaClip ? clipValue(aps.alfLumaClipIdx[filter][j]) : clipNone;
                }
            }
        }
        var src = copy[0];
        var dst = f.pic.planes[0];
        var stride = f.pic.planeWidth[0];
        var vbPos = f.ctuSize - 4;
        fillPad(src, stride, x0, y0, w, h);
        for (var by = y0; by < y0 + h; by += 4) {
            for (var bx = x0; bx < x0 + w; bx += 4) {
                classify(bx, by, by - ctuY, vbPos);
                var cIdx = cls[0];
                var perm = TRANSPOSE[tpose[0]];
                for (var k = 0; k < 12; k++) {
                    coeffBuf[k] = alfCoeff[cIdx][perm[k]];
                    clipBuf[k] = alfClip[cIdx][perm[k]];
                }
                var bh = Math.min(4, y0 + h - by);
                var bw = Math.min(4, x0 + w - bx);
                for (var y = by; y < by + bh; y++) {
                    filterLine(dst, stride, bx, y, bw, y - ctuY, vbPos, false, coeffBuf, clipBuf);
                }
            }
        }
    }

    /**
     * {@return the clipping value of an {@code alf_luma_clip_idx} or {@code alf_chroma_clip_idx} for the bit depth}
     *
     * @param idx the clipping index
     */
    private int clipValue(int idx) {
        return switch (idx) {
            case 0 -> 1 << bitDepth;
            case 1 -> 1 << (bitDepth - 3);
            case 2 -> 1 << (bitDepth - 5);
            default -> 1 << (bitDepth - 7);
        };
    }

    /**
     * Derives the class and transpose index of the 4x4 block at a luma position (8.8.5.3).
     *
     * @param bx the first column of the block
     * @param by the first row of the block
     * @param relY the row of the block inside its CTU
     * @param vbPos the row of the ALF virtual boundary inside the CTU
     */
    private void classify(int bx, int by, int relY, int vbPos) {
        var sumV = 0;
        var sumH = 0;
        var sumD0 = 0;
        var sumD1 = 0;
        var first = 0;
        var last = 4;
        if (relY == vbPos - 4) {
            last = 3;
        } else if (relY == vbPos) {
            first = 1;
        }
        for (var k = first; k < last; k++) {
            var r = by - 2 + 2 * k;
            var rel = relY - 2 + 2 * k;
            var rowUp = r - 1;
            var rowDown2 = r + 2;
            if (rel > 0 && rel == vbPos - 2) {
                rowDown2 = r + 1;
            } else if (rel > 0 && rel == vbPos) {
                rowUp = r;
            }
            for (var m = 0; m < 4; m++) {
                var c = bx - 2 + 2 * m;
                var y0 = at(c, r) << 1;
                var y1 = at(c + 1, r + 1) << 1;
                sumV += Math.abs(y0 - at(c, rowUp) - at(c, r + 1))
                        + Math.abs(y1 - at(c + 1, r) - at(c + 1, rowDown2));
                sumH += Math.abs(y0 - at(c + 1, r) - at(c - 1, r))
                        + Math.abs(y1 - at(c + 2, r + 1) - at(c, r + 1));
                sumD0 += Math.abs(y0 - at(c - 1, rowUp) - at(c + 1, r + 1))
                        + Math.abs(y1 - at(c, r) - at(c + 2, rowDown2));
                sumD1 += Math.abs(y0 - at(c - 1, r + 1) - at(c + 1, rowUp))
                        + Math.abs(y1 - at(c, rowDown2) - at(c + 2, r));
            }
        }
        var shift = bitDepth + 4;
        var scale = last - first == 3 ? 96 : 64;
        var activity = Math.clamp(((sumV + sumH) * scale) >> shift, 0, 15);
        var classIdx = ACTIVITY_CLASS[activity];
        int hv1;
        int hv0;
        int dirHv;
        if (sumV > sumH) {
            hv1 = sumV;
            hv0 = sumH;
            dirHv = 1;
        } else {
            hv1 = sumH;
            hv0 = sumV;
            dirHv = 3;
        }
        int d1;
        int d0;
        int dirD;
        if (sumD0 > sumD1) {
            d1 = sumD0;
            d0 = sumD1;
            dirD = 0;
        } else {
            d1 = sumD1;
            d0 = sumD0;
            dirD = 2;
        }
        int hvd1;
        int hvd0;
        int main;
        int secondary;
        if ((long) d1 * hv0 > (long) hv1 * d0) {
            hvd1 = d1;
            hvd0 = d0;
            main = dirD;
            secondary = dirHv;
        } else {
            hvd1 = hv1;
            hvd0 = hv0;
            main = dirHv;
            secondary = dirD;
        }
        var strength = 0;
        if (hvd1 > 2 * hvd0) {
            strength = 1;
        }
        if (hvd1 * 2 > 9 * hvd0) {
            strength = 2;
        }
        if (strength != 0) {
            classIdx += (((main & 1) << 1) + strength) * 5;
        }
        cls[0] = classIdx;
        tpose[0] = TRANSPOSE_TABLE[main * 2 + (secondary >> 1)];
    }

    /**
     * Filters one row of a block with the 7x7 luma or 5x5 chroma diamond filter (8.8.5.2, 8.8.5.4), with the
     * symmetric padding at the ALF virtual boundary above the next CTU row.
     *
     * @param dst the plane receiving the filtered samples
     * @param stride the row stride of the plane
     * @param x0 the first column of the row
     * @param y the row
     * @param w the number of samples of the row
     * @param relY the row inside its CTU
     * @param vbPos the row of the ALF virtual boundary inside the CTU
     * @param chroma whether the 5x5 chroma filter applies rather than the 7x7 luma filter
     * @param coeff the filter coefficients
     * @param clip the clipping values
     */
    private void filterLine(short[] dst, int stride, int x0, int y, int w, int relY, int vbPos,
            boolean chroma, int[] coeff, int[] clip) {
        var r1 = y + 1;
        var r2 = y - 1;
        var r3 = y + 2;
        var r4 = y - 2;
        var r5 = y + 3;
        var r6 = y - 3;
        var yVb = relY;
        if (yVb < vbPos && yVb >= vbPos - (chroma ? 2 : 4)) {
            if (yVb == vbPos - 1) {
                r1 = y;
                r2 = y;
            }
            if (yVb >= vbPos - 2) {
                r3 = r1;
                r4 = r2;
            }
            if (yVb >= vbPos - 3) {
                r5 = r3;
                r6 = r4;
            }
        } else if (yVb >= vbPos && yVb <= vbPos + (chroma ? 1 : 3)) {
            if (yVb == vbPos) {
                r1 = y;
                r2 = y;
            }
            if (yVb <= vbPos + 1) {
                r3 = r1;
                r4 = r2;
            }
            if (yVb <= vbPos + 2) {
                r5 = r3;
                r6 = r4;
            }
        }
        var nearVb = yVb == vbPos - 1 || yVb == vbPos;
        for (var x = x0; x < x0 + w; x++) {
            var curr = at(x, y);
            int sum;
            if (!chroma) {
                sum = coeff[0] * pair(clip[0], curr, at(x, r5), at(x, r6))
                        + coeff[1] * pair(clip[1], curr, at(x + 1, r3),
                        at(x - 1, r4))
                        + coeff[2] * pair(clip[2], curr, at(x, r3), at(x, r4))
                        + coeff[3] * pair(clip[3], curr, at(x - 1, r3),
                        at(x + 1, r4))
                        + coeff[4] * pair(clip[4], curr, at(x + 2, r1),
                        at(x - 2, r2))
                        + coeff[5] * pair(clip[5], curr, at(x + 1, r1),
                        at(x - 1, r2))
                        + coeff[6] * pair(clip[6], curr, at(x, r1), at(x, r2))
                        + coeff[7] * pair(clip[7], curr, at(x - 1, r1),
                        at(x + 1, r2))
                        + coeff[8] * pair(clip[8], curr, at(x - 2, r1),
                        at(x + 2, r2))
                        + coeff[9] * pair(clip[9], curr, at(x + 3, y),
                        at(x - 3, y))
                        + coeff[10] * pair(clip[10], curr, at(x + 2, y),
                        at(x - 2, y))
                        + coeff[11] * pair(clip[11], curr, at(x + 1, y),
                        at(x - 1, y));
            } else {
                sum = coeff[0] * pair(clip[0], curr, at(x, r3), at(x, r4))
                        + coeff[1] * pair(clip[1], curr, at(x + 1, r1),
                        at(x - 1, r2))
                        + coeff[2] * pair(clip[2], curr, at(x, r1), at(x, r2))
                        + coeff[3] * pair(clip[3], curr, at(x - 1, r1),
                        at(x + 1, r2))
                        + coeff[4] * pair(clip[4], curr, at(x + 2, y),
                        at(x - 2, y))
                        + coeff[5] * pair(clip[5], curr, at(x + 1, y),
                        at(x - 1, y));
            }
            sum = nearVb ? (sum + 512) >> 10 : (sum + 64) >> 7;
            dst[y * stride + x] = (short) Math.clamp(sum + curr, 0, maxVal);
        }
    }

    /**
     * {@return the clipped differences of a symmetric pair of neighbors to the current sample, summed}
     *
     * @param clip the clipping value
     * @param curr the current sample
     * @param a the first neighbor
     * @param b the second neighbor
     */
    private static int pair(int clip, int curr, int a, int b) {
        return Math.clamp(a - curr, -clip, clip) + Math.clamp(b - curr, -clip, clip);
    }

    /**
     * Applies the chroma ALF to a region of a CTU (8.8.5.4).
     *
     * @param header the slice header of the CTU
     * @param ctu the CTU address
     * @param c the component
     * @param x0 the first chroma column of the region
     * @param y0 the first chroma row of the region
     * @param w the region width
     * @param h the region height
     * @param ctuY the first chroma row of the CTU
     */
    private void alfChroma(VvcSliceHeader header, int ctu, int c, int x0, int y0, int w, int h, int ctuY) {
        var aps = header.alfChromaApsRef;
        var alt = f.alfChromaAlt[ctu][c - 1];
        for (var k = 0; k < 6; k++) {
            coeffBuf[k] = aps.alfChromaCoeff[alt][k];
            clipBuf[k] = aps.alfChromaClip ? clipValue(aps.alfChromaClipIdx[alt][k]) : 1 << bitDepth;
        }
        var src = copy[c];
        var dst = f.pic.planes[c];
        var stride = f.pic.planeWidth[c];
        var vbPos = (f.ctuSize >> sy) - 2;
        fillPad(src, stride, x0, y0, w, h);
        for (var y = y0; y < y0 + h; y++) {
            filterLine(dst, stride, x0, y, w, y - ctuY, vbPos, true, coeffBuf, clipBuf);
        }
    }

    /**
     * Adds the cross-component ALF correction of a chroma region of a CTU (8.8.5.7), computed from the luma samples
     * before ALF.
     *
     * @param header the slice header of the CTU
     * @param ctu the CTU address
     * @param c the component
     * @param x0 the first chroma column of the region
     * @param y0 the first chroma row of the region
     * @param w the region width
     * @param h the region height
     * @param ctuY the first luma row of the CTU
     */
    private void ccAlf(VvcSliceHeader header, int ctu, int c, int x0, int y0, int w, int h, int ctuY) {
        var aps = header.ccAlfApsRefs[c - 1];
        var coeff = aps.ccAlfCoeff[c - 1][f.ccAlfIdx[ctu][c - 1] - 1];
        var luma = copy[0];
        var lumaStride = f.pic.planeWidth[0];
        var dst = f.pic.planes[c];
        var stride = f.pic.planeWidth[c];
        var vbPos = f.ctuSize - 4;
        var half = 1 << (bitDepth - 1);
        fillPad(luma, lumaStride, x0 << sx, y0 << sy, w << sx, h << sy);
        for (var y = y0; y < y0 + h; y++) {
            var ly = y << sy;
            var pos = ly - ctuY;
            if (sy == 0 && (pos == vbPos || pos == vbPos + 1)) {
                continue;
            }
            var o1 = 1;
            var o2 = -1;
            var o3 = 2;
            if (pos == vbPos - 2 || pos == vbPos + 1) {
                o3 = 1;
            } else if (pos == vbPos - 1 || pos == vbPos) {
                o1 = 0;
                o2 = 0;
                o3 = 0;
            }
            for (var x = x0; x < x0 + w; x++) {
                var lx = x << sx;
                var curr = at(lx, ly);
                var sum = coeff[0] * (at(lx, ly + o2) - curr)
                        + coeff[1] * (at(lx - 1, ly) - curr)
                        + coeff[2] * (at(lx + 1, ly) - curr)
                        + coeff[3] * (at(lx - 1, ly + o1) - curr)
                        + coeff[4] * (at(lx, ly + o1) - curr)
                        + coeff[5] * (at(lx + 1, ly + o1) - curr)
                        + coeff[6] * (at(lx, ly + o3) - curr);
                sum = Math.clamp((sum + 64) >> 7, -half, half - 1);
                var i = y * stride + x;
                dst[i] = (short) Math.clamp(dst[i] + sum, 0, maxVal);
            }
        }
    }
}
