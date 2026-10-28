package com.github.auties00.smalt.video.mpeg4.internal.encoder;

import com.github.auties00.smalt.internal.task.ParallelBands;
import com.github.auties00.smalt.video.mpeg4.internal.Frame;
import com.github.auties00.smalt.video.mpeg4.internal.H263Header;
import com.github.auties00.smalt.video.mpeg4.internal.Macroblocks;
import com.github.auties00.smalt.video.mpeg4.internal.Mpeg4Dsp;
import com.github.auties00.smalt.video.mpeg4.internal.Reconstructor;
import com.github.auties00.smalt.video.mpeg4.internal.Tables;
import com.github.auties00.smalt.video.mpeg4.internal.VideoObjectLayer;
import com.github.auties00.smalt.video.mpeg4.internal.Vop;

import java.util.Arrays;

/**
 * Codes the macroblocks of one picture of MPEG-4 Visual (ISO/IEC 14496-2, I-, P- and B-VOPs of rectangular 8-bit
 * layers) or of ITU-T H.263 (I- and P-pictures with the optional modes of {@link Tools}), and reconstructs the
 * picture as a decoder does.
 *
 * <p>A picture is coded in three steps: {@link #analyze()} chooses the macroblock modes and motion vectors and forms
 * the prediction of the whole picture with the decoder's {@link Reconstructor}; the caller then writes the picture
 * header, with the f_codes {@link #forwardFcode()} and {@link #backwardFcode()} give; {@link #code(BitWriter)} writes
 * the macroblocks, with their video packet or group of blocks headers, and adds the decoded residuals to the
 * prediction; {@link #finish()} applies the in-loop filter and extends the edges, after which the reconstruction is
 * the picture a decoder of the stream produces, sample for sample.
 *
 * <p>The analysis of a picture runs on up to {@link Tools#threads()} threads; every decision depends only on the
 * picture's own row and on earlier pictures, so the stream does not depend on the thread count. Instances are not
 * thread-safe.
 */
public final class PictureCoder {
    /**
     * Selects the syntax and the coding tools of a stream.
     *
     * @param h263 whether the stream is ITU-T H.263 (or MPEG-4 with the short video header) rather than MPEG-4
     * @param extendedType whether H.263 pictures carry the extended picture type (PLUSPTYPE)
     * @param unrestrictedVectors whether H.263 vectors may point outside the picture with the range of Annex D
     * @param overlapped whether H.263 inter macroblocks use overlapped block motion compensation (Annex F)
     * @param fourVectors whether inter macroblocks may have four vectors
     * @param advancedIntra whether H.263 intra macroblocks use Advanced INTRA Coding (Annex I)
     * @param deblocking whether H.263 pictures use the in-loop deblocking filter (Annex J)
     * @param modifiedQuantization whether H.263 pictures use Modified Quantization (Annex T)
     * @param groupHeaders whether every H.263 group of blocks after the first has a header
     * @param sliceMacroblocks the macroblocks of each H.263 slice of the Slice Structured mode (Annex K), or 0 for
     * groups of blocks
     * @param quarterSample whether MPEG-4 vectors are in quarter samples
     * @param mpegQuantization whether MPEG-4 blocks use the MPEG quantization method with matrices
     * @param intraMatrix the MPEG-4 intra quantization matrix in raster order
     * @param interMatrix the MPEG-4 non-intra quantization matrix in raster order
     * @param acPrediction whether MPEG-4 intra macroblocks may use AC prediction
     * @param packetBits the size in bits after which an MPEG-4 video packet ends, or 0 for one packet per VOP
     * @param dataPartitioned whether the macroblocks of MPEG-4 I- and P-VOPs are data partitioned (6.2.5.3)
     * @param reversibleVlc whether the texture of data partitioned VOPs uses the reversible codes of Table B-23
     * @param searchRange the largest whole-sample vector component searched, positive
     * @param threads the most threads of the analysis, positive
     */
    public record Tools(boolean h263, boolean extendedType, boolean unrestrictedVectors, boolean overlapped,
                        boolean fourVectors, boolean advancedIntra, boolean deblocking, boolean modifiedQuantization,
                        boolean groupHeaders, int sliceMacroblocks, boolean quarterSample, boolean mpegQuantization,
                        int[] intraMatrix, int[] interMatrix, boolean acPrediction, int packetBits,
                        boolean dataPartitioned, boolean reversibleVlc, int searchRange, int threads) {
    }

    private static final byte MODE_INTRA = 0;
    private static final byte MODE_INTER = 1;
    private static final byte MODE_INTER4V = 2;

    private static final byte B_DIRECT = 0;
    private static final byte B_INTERPOLATED = 1;
    private static final byte B_BACKWARD = 2;
    private static final byte B_FORWARD = 3;

    /**
     * The B-VOP mode of a macroblock whose co-located macroblock was skipped, coded without bits (7.6.7).
     */
    private static final byte B_FORCED = 4;

    /**
     * The default predictor value of an intra DC coefficient (7.4.3.1).
     */
    private static final int DEFAULT_DC = 1024;

    /**
     * The bias of TMN against intra coding: an intra macroblock is chosen when its deviation is this much below the
     * inter cost.
     */
    private static final int INTRA_BIAS = 500;

    /**
     * The bias toward the zero vector and toward one vector per macroblock (TMN's {@code N*N/2 + 1}).
     */
    private static final int VECTOR_BIAS = 129;

    private final Tools tools;
    private final int width;
    private final int height;
    private final int mbWidth;
    private final int mbHeight;
    private final int mbCount;

    /**
     * The layer the reconstruction reads its tools from.
     */
    private final VideoObjectLayer layer;

    /**
     * The H.263 header state that applies the deblocking filter and the chrominance quantizer of Annex T.
     */
    private final H263Header h263Header = new H263Header();

    /**
     * The coded macroblocks: modes, vectors, quantizers and dequantized coefficients, as a decoder parses them.
     */
    private final Macroblocks blocks;

    /**
     * The macroblock modes chosen by the analysis.
     */
    private final byte[] modes;

    /**
     * The direct mode delta vectors of B-VOP macroblocks, {@code x, y} pairs.
     */
    private final int[] deltas;

    /**
     * The 16x16 vectors of the last predicted picture, {@code x, y} pairs, the start candidates of the next.
     */
    private final int[] history;

    /**
     * The intra predictor stores: reconstructed DC values, and the first row and column of quantized levels (or of
     * reconstructed coefficients in the Advanced INTRA Coding mode) of every block.
     */
    private final int[] dcStore;
    private final int[] rowStore;
    private final int[] columnStore;

    /**
     * The scratch state of the bands of the analysis.
     */
    private final Band[] bands;

    /**
     * The kernels.
     */
    private final Mpeg4Dsp dsp = Mpeg4Dsp.select();

    private final int[] work = new int[64];
    private final int[] idctWork = new int[64];
    private final int[][] levels = new int[6][64];
    private final int[][] codedLevels = new int[6][64];
    private final int[][] intraCoefficients = new int[6][64];
    private final int[][] intraRecon = new int[6][64];
    private final int[][] predicted = new int[6][8];
    private final boolean[] fromAbove = new boolean[6];
    private final int[] dcDiff = new int[6];
    private final int[] dcValue = new int[6];
    private final int[] bPredictors = new int[4];
    private final BitWriter trial = new BitWriter();

    /**
     * The partitions of a data partitioned video packet: macroblock headers and motion, the second header part and
     * the DC coefficients, and the texture.
     */
    private final BitWriter headerPartition = new BitWriter();
    private final BitWriter secondPartition = new BitWriter();
    private final BitWriter texturePartition = new BitWriter();

    /**
     * The {@code ac_pred_flag} of the MPEG-4 intra macroblock prepared last.
     */
    private boolean acPredicted;

    /**
     * Holds the scratch state of one band of the analysis.
     */
    private static final class Band {
        final MotionSearch search = new MotionSearch();
        final Reconstructor reconstructor = new Reconstructor();
        final int[] candidates = new int[16];
        final int[] blockVectors = new int[8];
        final int[] directForward = new int[8];
        final int[] directBackward = new int[8];
    }

    private int type;
    private Frame source;
    private Frame current;
    private Frame past;
    private Frame future;
    private int quant;
    private int rounding;
    private long trb;
    private long trd;
    private int forwardFcode = 1;
    private int backwardFcode = 1;
    private int packetStart;
    private long packetPosition;

    /**
     * Constructs a coder for a picture size.
     *
     * @param width the picture width
     * @param height the picture height
     * @param tools the syntax and coding tools
     */
    public PictureCoder(int width, int height, Tools tools) {
        this.tools = tools;
        this.width = width;
        this.height = height;
        this.mbWidth = (width + 15) >> 4;
        this.mbHeight = (height + 15) >> 4;
        this.mbCount = mbWidth * mbHeight;
        this.layer = new VideoObjectLayer();
        layer.width = width;
        layer.height = height;
        layer.shortVideoHeader = tools.h263();
        layer.quarterSample = tools.quarterSample();
        layer.obmcDisable = !tools.overlapped();
        layer.mpegQuant = tools.mpegQuantization();
        h263Header.deblocking = tools.deblocking();
        h263Header.modifiedQuantization = tools.modifiedQuantization();
        h263Header.extended = tools.extendedType();
        this.blocks = new Macroblocks(mbCount);
        this.modes = new byte[mbCount];
        this.deltas = new int[mbCount * 2];
        this.history = new int[mbCount * 2];
        this.dcStore = new int[mbCount * 6];
        this.rowStore = new int[mbCount * 6 * 8];
        this.columnStore = new int[mbCount * 6 * 8];
        this.bands = new Band[Math.max(1, Math.min(tools.threads(), mbHeight))];
        for (var i = 0; i < bands.length; i++) {
            bands[i] = new Band();
        }
    }

    /**
     * {@return the number of macroblock columns}
     */
    public int mbWidth() {
        return mbWidth;
    }

    /**
     * {@return the number of macroblock rows}
     */
    public int mbHeight() {
        return mbHeight;
    }

    /**
     * Starts a picture.
     *
     * @param type {@link Vop#I}, {@link Vop#P} or {@link Vop#B}
     * @param source the source picture, edge-extended to the coded size
     * @param current receives the reconstruction
     * @param past the forward reference, or {@code null} for an I-picture
     * @param future the backward reference of a B-VOP, or {@code null}
     * @param quant the quantizer, from 1 to 31
     * @param rounding the rounding control of a P-picture
     * @param trb the distance of a B-VOP from its forward reference in time increments
     * @param trd the distance between the references of a B-VOP in time increments
     */
    public void beginPicture(int type, Frame source, Frame current, Frame past, Frame future, int quant, int rounding,
                             long trb, long trd) {
        this.type = type;
        this.source = source;
        this.current = current;
        this.past = past;
        this.future = future;
        this.quant = quant;
        this.rounding = type == Vop.P ? rounding : 0;
        this.trb = trb;
        this.trd = Math.max(1, trd);
        current.codingType = type;
        current.keyframe = type == Vop.I;
    }

    /**
     * {@return the f_code of the forward vectors of the picture analyzed last}
     */
    public int forwardFcode() {
        return forwardFcode;
    }

    /**
     * {@return the f_code of the backward vectors of the B-VOP analyzed last}
     */
    public int backwardFcode() {
        return backwardFcode;
    }

    /**
     * Chooses the modes and vectors of every macroblock and forms the picture's prediction.
     */
    public void analyze() {
        Arrays.fill(blocks.cbp, (byte) 0);
        Arrays.fill(blocks.flags, (byte) 0);
        Arrays.fill(blocks.quant, (byte) quant);
        blocks.segmented = tools.h263() && tools.sliceMacroblocks() > 0;
        if (blocks.segmented) {
            for (var mb = 0; mb < mbCount; mb++) {
                blocks.segment[mb] = mb - mb % tools.sliceMacroblocks();
            }
        }
        var count = Math.max(1, Math.min(ParallelBands.bands(mbHeight, (long) mbCount * 256 * 64), bands.length));
        ParallelBands.run(count, index -> {
            var first = ParallelBands.start(index, count, mbHeight);
            var end = ParallelBands.start(index + 1, count, mbHeight);
            var band = bands[index];
            for (var my = first; my < end; my++) {
                for (var mx = 0; mx < mbWidth; mx++) {
                    switch (type) {
                        case Vop.I -> decideIntra(my * mbWidth + mx);
                        case Vop.P -> decidePredicted(band, mx, my);
                        default -> decideBidirectional(band, mx, my);
                    }
                }
            }
        });
        if (type == Vop.P) {
            forwardFcode = fcodeOf(current.mv, (byte) 0);
            for (var mb = 0; mb < mbCount; mb++) {
                history[2 * mb] = current.mv[mb * 8];
                history[2 * mb + 1] = current.mv[mb * 8 + 1];
            }
        } else if (type == Vop.B) {
            forwardFcode = Math.max(fcodeOf(blocks.forward, B_FORWARD), fcodeOf(blocks.forward, B_INTERPOLATED));
            backwardFcode = Math.max(fcodeOf(blocks.backward, B_BACKWARD), fcodeOf(blocks.backward, B_INTERPOLATED));
            if (tools.packetBits() > 0 && forwardFcode == 1 && backwardFcode == 1) {
                // With both f_codes 1 decoders disagree on the resynchronization marker length of a B-VOP (16 or 17
                // zeros); from f_code 2 on every reading gives 17.
                backwardFcode = 2;
            }
        }
        ParallelBands.run(count, index -> {
            var first = ParallelBands.start(index, count, mbHeight);
            var end = ParallelBands.start(index + 1, count, mbHeight);
            bands[index].reconstructor.reconstruct(blocks, dsp, current, past, future, null, layer, rounding, first,
                    end);
        });
    }

    /**
     * Returns the smallest f_code whose range holds the vectors of the macroblocks of a mode.
     *
     * @param vectors the block vectors
     * @param bMode for a B-VOP the mode whose vectors count, otherwise ignored
     * @return the f_code, from 1 to 7
     */
    private int fcodeOf(int[] vectors, byte bMode) {
        var largest = 0;
        for (var mb = 0; mb < mbCount; mb++) {
            if (type == Vop.B ? modes[mb] != bMode : modes[mb] == MODE_INTRA) {
                continue;
            }
            for (var i = 0; i < 8; i++) {
                var v = vectors[mb * 8 + i];
                largest = Math.max(largest, v < 0 ? -v - 1 : v);
            }
        }
        var fcode = 1;
        while (fcode < 7 && largest >= 32 << (fcode - 1)) {
            fcode++;
        }
        return fcode;
    }

    /**
     * Records an intra macroblock.
     *
     * @param mb the macroblock number
     */
    private void decideIntra(int mb) {
        modes[mb] = MODE_INTRA;
        blocks.kind[mb] = Macroblocks.INTRA;
        current.mbType[mb] = Frame.MB_INTRA;
        Arrays.fill(current.mv, mb * 8, mb * 8 + 8, 0);
    }

    /**
     * Chooses the mode and vectors of a macroblock of a P-picture.
     *
     * @param band the band's scratch state
     * @param mx the macroblock column
     * @param my the macroblock row
     */
    private void decidePredicted(Band band, int mx, int my) {
        var search = band.search;
        var mb = my * mbWidth + mx;
        var x = mx * 16;
        var y = my * 16;
        var lambda = Math.max(1, quant);
        search.configure(tools.quarterSample(), rounding, lambda);
        setBounds(search, x, y, 16);
        var candidates = band.candidates;
        var vectors = band.blockVectors;
        var count = 0;
        var predX = 0;
        var predY = 0;
        if (mx > 0) {
            predX = current.mv[(mb - 1) * 8];
            predY = current.mv[(mb - 1) * 8 + 1];
            candidates[count++] = predX;
            candidates[count++] = predY;
        }
        candidates[count++] = history[2 * mb];
        candidates[count++] = history[2 * mb + 1];
        if (my > 0) {
            candidates[count++] = history[2 * (mb - mbWidth)];
            candidates[count++] = history[2 * (mb - mbWidth) + 1];
        }
        if (my < mbHeight - 1) {
            candidates[count++] = history[2 * (mb + mbWidth)];
            candidates[count++] = history[2 * (mb + mbWidth) + 1];
        }
        var range = tools.searchRange();
        search.search(source, past, x, y, 16, candidates, count / 2, predX, predY, range);
        var vx = search.bestX;
        var vy = search.bestY;
        var sad16 = search.bestSad;
        var zeroSad = MotionSearch.sad(source.y, source.lumaOrigin + y * source.lumaStride + x, source.lumaStride,
                past.y, past.lumaOrigin + y * past.lumaStride + x, past.lumaStride, 16);
        if (zeroSad - VECTOR_BIAS <= sad16) {
            vx = 0;
            vy = 0;
            sad16 = zeroSad - VECTOR_BIAS;
        }
        var mode = MODE_INTER;
        var interSad = sad16;
        if (tools.fourVectors()) {
            var sad4 = 0;
            for (var block = 0; block < 4 && sad4 < interSad; block++) {
                var bx = x + (block & 1) * 8;
                var by = y + (block >> 1) * 8;
                setBounds(search, bx, by, 8);
                candidates[0] = vx;
                candidates[1] = vy;
                search.search(source, past, bx, by, 8, candidates, 1, vx, vy, 2);
                vectors[2 * block] = search.bestX;
                vectors[2 * block + 1] = search.bestY;
                sad4 += search.bestSad;
            }
            if (sad4 + VECTOR_BIAS < interSad) {
                mode = MODE_INTER4V;
                interSad = sad4 + VECTOR_BIAS;
            }
        }
        var deviation = deviation(source, x, y);
        if (deviation < interSad - INTRA_BIAS) {
            decideIntra(mb);
            return;
        }
        modes[mb] = mode;
        var at = mb * 8;
        if (mode == MODE_INTER4V) {
            blocks.kind[mb] = Macroblocks.INTER8;
            current.mbType[mb] = Frame.MB_INTER4V;
            for (var i = 0; i < 8; i++) {
                current.mv[at + i] = vectors[i];
            }
        } else {
            blocks.kind[mb] = Macroblocks.INTER16;
            current.mbType[mb] = Frame.MB_INTER;
            for (var i = 0; i < 4; i++) {
                current.mv[at + 2 * i] = vx;
                current.mv[at + 2 * i + 1] = vy;
            }
        }
        System.arraycopy(current.mv, at, blocks.forward, at, 8);
        blocks.direction[mb] = Macroblocks.FORWARD;
        if (tools.overlapped()) {
            blocks.flags[mb] = Macroblocks.OVERLAPPED;
        }
    }

    /**
     * Returns the sum of absolute deviations of a source macroblock's luminance from its mean.
     *
     * @param picture the source picture
     * @param x the macroblock's left column
     * @param y the macroblock's top row
     * @return the deviation
     */
    private static int deviation(Frame picture, int x, int y) {
        var stride = picture.lumaStride;
        var base = picture.lumaOrigin + y * stride + x;
        var sum = 0;
        for (var row = 0; row < 16; row++) {
            for (var col = 0; col < 16; col++) {
                sum += picture.y[base + row * stride + col] & 0xFF;
            }
        }
        var mean = (sum + 128) >> 8;
        var deviation = 0;
        for (var row = 0; row < 16; row++) {
            for (var col = 0; col < 16; col++) {
                deviation += Math.abs((picture.y[base + row * stride + col] & 0xFF) - mean);
            }
        }
        return deviation;
    }

    /**
     * Sets the vector range of a block: the f_code range of the search range for MPEG-4, and for H.263 the range of
     * the picture's modes and, without Annexes D and F, the vectors that keep the block inside the picture.
     *
     * @param search the search whose range is set
     * @param x the block's left column
     * @param y the block's top row
     * @param size the block size
     */
    private void setBounds(MotionSearch search, int x, int y, int size) {
        if (!tools.h263()) {
            var units = tools.quarterSample() ? 4 : 2;
            var limit = Math.min(tools.searchRange() * units, 1024);
            search.bounds(-limit, limit - 1, -limit, limit - 1);
            return;
        }
        if (tools.unrestrictedVectors()) {
            var horizontal = width <= 352 ? 64 : width <= 704 ? 128 : width <= 1408 ? 256 : 512;
            var vertical = height <= 288 ? 64 : height <= 576 ? 128 : 256;
            search.bounds(-horizontal, horizontal - 1, -vertical, vertical - 1);
            return;
        }
        if (tools.overlapped()) {
            search.bounds(-32, 31, -32, 31);
            return;
        }
        var codedWidth = mbWidth * 16;
        var codedHeight = mbHeight * 16;
        search.bounds(Math.max(-32, -2 * x), Math.min(31, 2 * (codedWidth - x - size)), Math.max(-32, -2 * y),
                Math.min(31, 2 * (codedHeight - y - size)));
    }

    /**
     * Chooses the mode and vectors of a macroblock of a B-VOP.
     *
     * @param band the band's scratch state
     * @param mx the macroblock column
     * @param my the macroblock row
     */
    private void decideBidirectional(Band band, int mx, int my) {
        var search = band.search;
        var mb = my * mbWidth + mx;
        var at = mb * 8;
        if (future.codingType == Vop.P && future.mbType[mb] == Frame.MB_SKIPPED) {
            modes[mb] = B_FORCED;
            blocks.kind[mb] = Macroblocks.INTER16;
            blocks.direction[mb] = Macroblocks.FORWARD;
            Arrays.fill(blocks.forward, at, at + 8, 0);
            current.mbType[mb] = Frame.MB_INTER;
            return;
        }
        var x = mx * 16;
        var y = my * 16;
        var lambda = Math.max(1, quant);
        search.configure(tools.quarterSample(), 0, lambda);
        setBounds(search, x, y, 16);
        var leftForwardX = mx > 0 && modes[mb - 1] != B_DIRECT && modes[mb - 1] != B_FORCED
                ? blocks.forward[(mb - 1) * 8] : 0;
        var leftForwardY = mx > 0 && modes[mb - 1] != B_DIRECT && modes[mb - 1] != B_FORCED
                ? blocks.forward[(mb - 1) * 8 + 1] : 0;
        var leftBackwardX = mx > 0 && modes[mb - 1] != B_DIRECT && modes[mb - 1] != B_FORCED
                ? blocks.backward[(mb - 1) * 8] : 0;
        var leftBackwardY = mx > 0 && modes[mb - 1] != B_DIRECT && modes[mb - 1] != B_FORCED
                ? blocks.backward[(mb - 1) * 8 + 1] : 0;
        var colocated = future.mv;
        var candidates = band.candidates;
        var range = tools.searchRange();
        candidates[0] = (int) (trb * colocated[at] / trd);
        candidates[1] = (int) (trb * colocated[at + 1] / trd);
        candidates[2] = leftForwardX;
        candidates[3] = leftForwardY;
        search.search(source, past, x, y, 16, candidates, 2, leftForwardX, leftForwardY, range);
        var forwardX = search.bestX;
        var forwardY = search.bestY;
        var forwardCost = search.bestCost;
        candidates[0] = (int) ((trb - trd) * colocated[at] / trd);
        candidates[1] = (int) ((trb - trd) * colocated[at + 1] / trd);
        candidates[2] = leftBackwardX;
        candidates[3] = leftBackwardY;
        search.search(source, future, x, y, 16, candidates, 2, leftBackwardX, leftBackwardY, range);
        var backwardX = search.bestX;
        var backwardY = search.bestY;
        var backwardCost = search.bestCost;
        var forward = blocks.forward;
        var backward = blocks.backward;
        for (var i = 0; i < 4; i++) {
            forward[at + 2 * i] = forwardX;
            forward[at + 2 * i + 1] = forwardY;
            backward[at + 2 * i] = backwardX;
            backward[at + 2 * i + 1] = backwardY;
        }
        var interpolatedCost = search.bidirectionalSad(source, past, future, x, y, forward, backward, at, false)
                + search.vectorCost(forwardX - leftForwardX, forwardY - leftForwardY)
                + search.vectorCost(backwardX - leftBackwardX, backwardY - leftBackwardY);
        var bestDirect = Integer.MAX_VALUE;
        var bestDx = 0;
        var bestDy = 0;
        var directForward = band.directForward;
        var directBackward = band.directBackward;
        for (var dy = -1; dy <= 1; dy++) {
            for (var dx = -1; dx <= 1; dx++) {
                directVectors(colocated, at, dx, dy, directForward, directBackward, 0);
                var sad = search.bidirectionalSad(source, past, future, x, y, directForward, directBackward, 0,
                        true) + ((dx | dy) == 0 ? -VECTOR_BIAS : search.vectorCost(dx, dy));
                if (sad < bestDirect) {
                    bestDirect = sad;
                    bestDx = dx;
                    bestDy = dy;
                }
            }
        }
        var mode = B_DIRECT;
        var best = bestDirect;
        if (forwardCost < best) {
            mode = B_FORWARD;
            best = forwardCost;
        }
        if (backwardCost < best) {
            mode = B_BACKWARD;
            best = backwardCost;
        }
        if (interpolatedCost < best) {
            mode = B_INTERPOLATED;
        }
        modes[mb] = mode;
        current.mbType[mb] = Frame.MB_INTER;
        switch (mode) {
            case B_DIRECT -> {
                deltas[2 * mb] = bestDx;
                deltas[2 * mb + 1] = bestDy;
                directVectors(colocated, at, bestDx, bestDy, forward, backward, at);
                blocks.kind[mb] = Macroblocks.INTER8;
                blocks.direction[mb] = Macroblocks.FORWARD | Macroblocks.BACKWARD;
            }
            case B_FORWARD -> {
                blocks.kind[mb] = Macroblocks.INTER16;
                blocks.direction[mb] = Macroblocks.FORWARD;
            }
            case B_BACKWARD -> {
                blocks.kind[mb] = Macroblocks.INTER16;
                blocks.direction[mb] = Macroblocks.BACKWARD;
            }
            default -> {
                blocks.kind[mb] = Macroblocks.INTER16;
                blocks.direction[mb] = Macroblocks.FORWARD | Macroblocks.BACKWARD;
            }
        }
    }

    /**
     * Derives the vectors of a direct mode macroblock from the co-located vectors of the backward reference
     * (7.6.9.5).
     *
     * @param colocated the vectors of the backward reference
     * @param at the index of the macroblock's block 0 in {@code colocated}
     * @param deltaX the horizontal delta vector
     * @param deltaY the vertical delta vector
     * @param forward receives the forward vectors
     * @param backward receives the backward vectors
     * @param out the index of block 0's horizontal component in {@code forward} and {@code backward}
     */
    private void directVectors(int[] colocated, int at, int deltaX, int deltaY, int[] forward, int[] backward,
                               int out) {
        for (var i = 0; i < 8; i++) {
            var v = colocated[at + i];
            var delta = (i & 1) == 0 ? deltaX : deltaY;
            var f = (int) (trb * v / trd) + delta;
            var b = delta == 0 ? (int) ((trb - trd) * v / trd) : f - v;
            forward[out + i] = f;
            backward[out + i] = b;
        }
    }

    /**
     * Writes the macroblocks of the picture after its header, and adds the decoded residuals to the prediction.
     *
     * @param out the stream, positioned after the picture header
     */
    public void code(BitWriter out) {
        if (!tools.h263() && tools.dataPartitioned() && type != Vop.B) {
            codePartitioned(out);
            return;
        }
        startPacket(0, out);
        var gobRows = H263Header.gobRows(height);
        for (var mb = 0; mb < mbCount; mb++) {
            var mx = mb % mbWidth;
            var my = mb / mbWidth;
            if (tools.h263() && tools.sliceMacroblocks() > 0) {
                if (mb % tools.sliceMacroblocks() == 0) {
                    writeSliceHeader(out, mb);
                    startPacket(mb, out);
                }
            } else if (tools.h263()) {
                if (tools.groupHeaders() && mx == 0 && my > 0 && my % gobRows == 0) {
                    writeGroupHeader(out, my / gobRows);
                    startPacket(mb, out);
                }
            } else if (tools.packetBits() > 0 && mb > 0 && out.position() - packetPosition >= tools.packetBits()
                    && packetMayStart(mb)) {
                writePacketHeader(out, mb);
                startPacket(mb, out);
            }
            if (type == Vop.B) {
                if (mx == 0) {
                    Arrays.fill(bPredictors, 0);
                }
                codeBidirectional(out, mb, mx, my);
            } else {
                codeMacroblock(out, mb, mx, my);
            }
        }
    }

    /**
     * Writes the data partitioned video packets of an I- or P-VOP (6.2.5.3): for each packet the macroblock headers
     * and motion vectors (with the DC coefficients of an I-VOP), a marker, the second header part (with the DC
     * coefficients of the intra macroblocks of a P-VOP), and the texture.
     *
     * @param out the stream, positioned after the VOP header
     */
    private void codePartitioned(BitWriter out) {
        startPacket(0, out);
        for (var mb = 0; mb < mbCount; mb++) {
            if (tools.packetBits() > 0 && mb > packetStart && headerPartition.position() + secondPartition.position()
                    + texturePartition.position() >= tools.packetBits() && packetMayStart(mb)) {
                flushPartitions(out);
                writePacketHeader(out, mb);
                startPacket(mb, out);
            }
            codePartitionedMacroblock(mb, mb % mbWidth, mb / mbWidth);
        }
        flushPartitions(out);
    }

    /**
     * Returns whether an MPEG-4 video packet may start at a macroblock. A packet of a B-VOP does not start at a
     * macroblock that carries no bits (its co-located macroblock is not coded), and one of a P-VOP does not start at
     * a four-vector macroblock after the first column: ffmpeg's decoder then keeps a zero vector for block 3 of the
     * macroblock on its left (the candidate the prediction of block 2 may not use), and derives the direct mode
     * vectors of the B-VOPs from that zero instead of the coded vector.
     *
     * @param mb the macroblock number
     * @return whether a packet header may precede the macroblock
     */
    private boolean packetMayStart(int mb) {
        return switch (type) {
            case Vop.B -> modes[mb] != B_FORCED;
            case Vop.P -> modes[mb] != MODE_INTER4V || mb % mbWidth == 0;
            default -> true;
        };
    }

    /**
     * Writes the partitions of the video packet coded so far, separated by the DC marker of an I-VOP or the motion
     * marker of a P-VOP, and empties them.
     *
     * @param out the stream
     */
    private void flushPartitions(BitWriter out) {
        out.append(headerPartition);
        if (type == Vop.I) {
            out.write(0x6B001, 19);
        } else {
            out.write(0x1F001, 17);
        }
        out.append(secondPartition);
        out.append(texturePartition);
        headerPartition.reset();
        secondPartition.reset();
        texturePartition.reset();
    }

    /**
     * Codes one macroblock of a data partitioned I- or P-VOP into the three partitions.
     *
     * @param mb the macroblock number
     * @param mx the macroblock column
     * @param my the macroblock row
     */
    private void codePartitionedMacroblock(int mb, int mx, int my) {
        blocks.quant[mb] = (byte) quant;
        var header = headerPartition;
        var second = secondPartition;
        var texture = texturePartition;
        if (modes[mb] == MODE_INTRA) {
            blocks.kind[mb] = Macroblocks.INTRA;
            blocks.cbp[mb] = 63;
            current.mbType[mb] = Frame.MB_INTRA;
            var cbp = prepareMpeg4Intra(mb, mx, my);
            var cbpc = (cbp >> 3 & 2) | (cbp >> 5 & 1);
            var cbpy = (cbp & 1) << 3 | (cbp & 2) << 1 | (cbp & 4) >> 1 | (cbp & 8) >> 3;
            var dc = type == Vop.I ? header : second;
            if (type == Vop.I) {
                CodeTables.write(header, CodeTables.MCBPC_I[3 << 2 | cbpc]);
            } else {
                header.bit(0);
                CodeTables.write(header, CodeTables.MCBPC_P[3 << 2 | cbpc]);
            }
            if (type == Vop.I) {
                for (var block = 0; block < 6; block++) {
                    writeDcDifferential(dc, codedLevels[block][0], block < 4);
                }
            }
            second.flag(acPredicted);
            CodeTables.write(second, CodeTables.CBPY[cbpy]);
            if (type != Vop.I) {
                for (var block = 0; block < 6; block++) {
                    writeDcDifferential(dc, codedLevels[block][0], block < 4);
                }
            }
            for (var block = 0; block < 6; block++) {
                if ((cbp & (1 << block)) != 0) {
                    writeTexture(texture, codedLevels[block], 1, intraScan(block), true);
                }
            }
            return;
        }
        resetIntraStore(mb);
        var cbp = residuals(mb, mx, my);
        var at = mb * 8;
        if (modes[mb] == MODE_INTER && cbp == 0 && current.mv[at] == 0 && current.mv[at + 1] == 0) {
            header.bit(1);
            current.mbType[mb] = Frame.MB_SKIPPED;
            return;
        }
        header.bit(0);
        var fourVectors = modes[mb] == MODE_INTER4V;
        CodeTables.write(header, CodeTables.MCBPC_P[(fourVectors ? 2 : 0) << 2 | (cbp >> 3 & 2) | (cbp >> 5 & 1)]);
        for (var block = 0; block < (fourVectors ? 4 : 1); block++) {
            writeVector(header, mb, mx, my, block);
        }
        var cbpy = (cbp & 1) << 3 | (cbp & 2) << 1 | (cbp & 4) >> 1 | (cbp & 8) >> 3;
        CodeTables.write(second, CodeTables.CBPY[15 - cbpy]);
        for (var block = 0; block < 6; block++) {
            if ((cbp & (1 << block)) != 0) {
                writeTexture(texture, levels[block], 0, Tables.ZIGZAG, false);
                addResidual(mb, mx, my, block);
            }
        }
    }

    /**
     * Returns the scan of a block of the MPEG-4 intra macroblock prepared last: an alternate scan with AC prediction
     * (horizontal when predicted from above), zigzag otherwise.
     *
     * @param block the block index
     * @return the scan
     */
    private int[] intraScan(int block) {
        if (!acPredicted) {
            return Tables.ZIGZAG;
        }
        return fromAbove[block] ? Tables.ALTERNATE_HORIZONTAL : Tables.ALTERNATE_VERTICAL;
    }

    /**
     * Writes the coefficients of an MPEG-4 block of a data partitioned VOP: with the reversible codes when the layer
     * uses them, else with Table B-16 or B-17.
     *
     * @param out the texture partition
     * @param lv the levels in raster order
     * @param start the first scan index
     * @param scan the scan order
     * @param intra whether the block is an intra block
     */
    private void writeTexture(BitWriter out, int[] lv, int start, int[] scan, boolean intra) {
        if (tools.reversibleVlc()) {
            writeReversibleEvents(out, lv, start, scan, intra ? CodeTables.REVERSIBLE_INTRA
                    : CodeTables.REVERSIBLE_INTER);
        } else if (intra) {
            writeMpeg4Events(out, lv, start, scan, CodeTables.INTRA, Tables.INTRA_LMAX, Tables.INTRA_RMAX);
        } else {
            writeMpeg4Events(out, lv, start, scan, CodeTables.INTER, Tables.INTER_LMAX, Tables.INTER_RMAX);
        }
    }

    /**
     * Writes the coefficient events of a block with the reversible codes of Table B-23 and their escape.
     *
     * @param out the stream
     * @param lv the levels in raster order
     * @param start the first scan index
     * @param scan the scan order
     * @param table the reversible codes of the block type
     */
    private static void writeReversibleEvents(BitWriter out, int[] lv, int start, int[] scan, int[] table) {
        var end = lastScanIndex(lv, start, scan);
        var run = 0;
        for (var index = start; index <= end; index++) {
            var level = lv[scan[index]];
            if (level == 0) {
                run++;
                continue;
            }
            var last = index == end ? 1 : 0;
            var magnitude = Math.abs(level);
            var sign = level < 0 ? 1 : 0;
            var code = magnitude < 128 ? table[CodeTables.event(last, run, magnitude)] : 0;
            if (code != 0) {
                CodeTables.write(out, code);
                out.bit(sign);
            } else {
                CodeTables.write(out, CodeTables.REVERSIBLE_ESCAPE);
                out.bit(last);
                out.write(run, 6);
                out.bit(1);
                out.write(magnitude, 11);
                out.bit(1);
                out.write(0, 4);
                out.bit(sign);
            }
            run = 0;
        }
    }

    /**
     * Applies the in-loop filter of the picture's modes and extends the reconstruction's edges.
     */
    public void finish() {
        if (tools.h263()) {
            h263Header.postProcess(current, blocks, layer);
        }
        // TODO: settle the padding of MPEG-4 references whose size is not a multiple of 16: Smalt's decoder, like
        //  ffmpeg, predicts from the decoded samples of the partial macroblocks beyond the VOP boundary, while the
        //  ISO/IEC 14496-5 reference decoder pads from the VOP boundary, so its decode of such streams differs by up
        //  to about 10 sample values.
        current.extendEdges(mbWidth * 16, mbHeight * 16);
        current.coded = true;
    }

    /**
     * Resets the prediction state at the start of a video packet or group of blocks.
     *
     * @param mb the packet's first macroblock
     * @param out the stream
     */
    private void startPacket(int mb, BitWriter out) {
        packetStart = mb;
        packetPosition = out.position();
        Arrays.fill(bPredictors, 0);
    }

    /**
     * Writes an MPEG-4 video packet header (6.2.5.2) without header extension.
     *
     * @param out the stream
     * @param mb the packet's first macroblock
     */
    private void writePacketHeader(BitWriter out, int mb) {
        out.stuffing();
        var length = switch (type) {
            case Vop.I -> 17;
            case Vop.B -> Math.max(15 + Math.max(forwardFcode, backwardFcode), 17) + 1;
            default -> 16 + forwardFcode;
        };
        out.write(0, length - 1);
        out.bit(1);
        out.write(mb, Math.max(1, 32 - Integer.numberOfLeadingZeros(mbCount - 1)));
        out.write(quant, 5);
        out.bit(0);
    }

    /**
     * Writes an H.263 slice header of the Slice Structured mode (K.2): for the first slice of the picture only the
     * macroblock address between two emulation prevention bits, for the others a byte-aligned slice start code
     * with the address, the quantizer and the GFID.
     *
     * @param out the stream
     * @param mb the slice's first macroblock
     */
    private void writeSliceHeader(BitWriter out, int mb) {
        var bits = mbCount <= 48 ? 6 : mbCount <= 99 ? 7 : mbCount <= 396 ? 9 : mbCount <= 1584 ? 11
                : mbCount <= 6336 ? 13 : 14;
        if (mb == 0) {
            out.bit(1);
            out.write(0, bits);
            out.bit(1);
            return;
        }
        out.alignWithZeros();
        out.write(1, 17);
        out.bit(1);
        out.write(mb, bits);
        if (bits > 11) {
            out.bit(1);
        }
        out.write(quant, 5);
        out.bit(1);
        out.write(type == Vop.I ? 1 : 0, 2);
    }

    /**
     * Writes an H.263 group of blocks header (5.2), byte aligned.
     *
     * @param out the stream
     * @param number the group number
     */
    private void writeGroupHeader(BitWriter out, int number) {
        out.alignWithZeros();
        out.write(1, 17);
        out.write(number, 5);
        out.write(type == Vop.I ? 1 : 0, 2);
        out.write(quant, 5);
    }

    /**
     * Codes one macroblock of an I- or P-picture.
     *
     * @param out the stream
     * @param mb the macroblock number
     * @param mx the macroblock column
     * @param my the macroblock row
     */
    private void codeMacroblock(BitWriter out, int mb, int mx, int my) {
        blocks.quant[mb] = (byte) quant;
        if (modes[mb] == MODE_INTRA) {
            codeIntra(out, mb, mx, my);
            return;
        }
        resetIntraStore(mb);
        var cbp = residuals(mb, mx, my);
        var at = mb * 8;
        if (modes[mb] == MODE_INTER && cbp == 0 && current.mv[at] == 0 && current.mv[at + 1] == 0) {
            out.bit(1);
            current.mbType[mb] = Frame.MB_SKIPPED;
            return;
        }
        out.bit(0);
        var fourVectors = modes[mb] == MODE_INTER4V;
        var mcbpc = (fourVectors ? 2 : 0) << 2 | (cbp >> 3 & 2) | (cbp >> 5 & 1);
        CodeTables.write(out, (tools.h263() && tools.extendedType() ? CodeTables.MCBPC_P_EXTENDED
                : CodeTables.MCBPC_P)[mcbpc]);
        var cbpy = (cbp & 1) << 3 | (cbp & 2) << 1 | (cbp & 4) >> 1 | (cbp & 8) >> 3;
        CodeTables.write(out, CodeTables.CBPY[15 - cbpy]);
        for (var block = 0; block < (fourVectors ? 4 : 1); block++) {
            writeVector(out, mb, mx, my, block);
        }
        for (var block = 0; block < 6; block++) {
            if ((cbp & (1 << block)) != 0) {
                writeInterBlock(out, levels[block]);
                addResidual(mb, mx, my, block);
            }
        }
    }

    /**
     * Quantizes the residuals of an inter macroblock against the prediction in the reconstruction, and stores their
     * dequantized coefficients.
     *
     * @param mb the macroblock number
     * @param mx the macroblock column
     * @param my the macroblock row
     * @return the coded block pattern, block {@code b} in bit {@code b}
     */
    private int residuals(int mb, int mx, int my) {
        var cbp = 0;
        for (var block = 0; block < 6; block++) {
            var blockQuant = block < 4 || !tools.h263() ? quant : h263Header.chromaQuant(quant);
            loadResidual(mx, my, block);
            ForwardDct.transform(work);
            var lv = levels[block];
            var nonzero = false;
            var limit = levelLimit(blockQuant, false);
            for (var i = 0; i < 64; i++) {
                var c = work[i];
                var magnitude = Math.abs(c);
                int level;
                if (tools.mpegQuantization()) {
                    var step = tools.interMatrix()[i] * blockQuant;
                    level = Math.max(0, (32 * magnitude - step) / (4 * step));
                } else {
                    level = Math.max(0, (magnitude - blockQuant / 2) / (2 * blockQuant));
                }
                level = Math.min(level, limit);
                lv[i] = c < 0 ? -level : level;
                nonzero |= level != 0;
            }
            if (nonzero) {
                cbp |= 1 << block;
                dequantizeInter(mb, block, lv, blockQuant);
            }
        }
        blocks.cbp[mb] = (byte) cbp;
        return cbp;
    }

    /**
     * Returns the largest level magnitude a block may code.
     *
     * @param blockQuant the block's quantizer
     * @param advanced whether the block is an Advanced INTRA Coding block
     * @return the limit
     */
    private int levelLimit(int blockQuant, boolean advanced) {
        if (!tools.h263()) {
            return 2047;
        }
        if (!tools.modifiedQuantization() || blockQuant >= 8) {
            return 127;
        }
        var level = 1023;
        while ((advanced ? 2 * blockQuant * level : blockQuant * (2 * level + 1)) > 2047) {
            level--;
        }
        return level;
    }

    /**
     * Loads the residual of a block, the source minus the prediction in the reconstruction, into {@link #work}.
     *
     * @param mx the macroblock column
     * @param my the macroblock row
     * @param block the block index, 0 to 5
     */
    private void loadResidual(int mx, int my, int block) {
        byte[] src;
        byte[] pred;
        int offset;
        int stride;
        if (block < 4) {
            stride = source.lumaStride;
            offset = source.lumaOrigin + (my * 16 + (block >> 1) * 8) * stride + mx * 16 + (block & 1) * 8;
            src = source.y;
            pred = current.y;
        } else {
            stride = source.chromaStride;
            offset = source.chromaOrigin + my * 8 * stride + mx * 8;
            src = block == 4 ? source.u : source.v;
            pred = block == 4 ? current.u : current.v;
        }
        for (var row = 0; row < 8; row++) {
            for (var col = 0; col < 8; col++) {
                var at = offset + row * stride + col;
                work[row * 8 + col] = (src[at] & 0xFF) - (pred[at] & 0xFF);
            }
        }
    }

    /**
     * Loads the source samples of a block into {@link #work}.
     *
     * @param mx the macroblock column
     * @param my the macroblock row
     * @param block the block index, 0 to 5
     */
    private void loadSource(int mx, int my, int block) {
        byte[] src;
        int offset;
        int stride;
        if (block < 4) {
            stride = source.lumaStride;
            offset = source.lumaOrigin + (my * 16 + (block >> 1) * 8) * stride + mx * 16 + (block & 1) * 8;
            src = source.y;
        } else {
            stride = source.chromaStride;
            offset = source.chromaOrigin + my * 8 * stride + mx * 8;
            src = block == 4 ? source.u : source.v;
        }
        for (var row = 0; row < 8; row++) {
            for (var col = 0; col < 8; col++) {
                work[row * 8 + col] = src[offset + row * stride + col] & 0xFF;
            }
        }
    }

    /**
     * Stores the dequantized coefficients of an inter block as a decoder computes them (7.4.4).
     *
     * @param mb the macroblock number
     * @param block the block index
     * @param lv the levels in raster order
     * @param blockQuant the block's quantizer
     */
    private void dequantizeInter(int mb, int block, int[] lv, int blockQuant) {
        var coefficients = blocks.coefficients;
        var out = mb * 384 + block * 64;
        var even = 1 - (blockQuant & 1);
        var sum = 0;
        for (var i = 0; i < 64; i++) {
            var level = lv[i];
            if (level == 0) {
                continue;
            }
            var magnitude = Math.abs(level);
            int value;
            if (tools.mpegQuantization()) {
                value = (2 * magnitude + 1) * tools.interMatrix()[i] * blockQuant >> 4;
                value = clamp(level < 0 ? -value : value, -2048, 2047);
                sum += value;
            } else {
                var m = (2 * magnitude + 1) * blockQuant - even;
                value = clamp(level < 0 ? -m : m, -2048, 2047);
            }
            coefficients[out + column(i)] = (short) value;
        }
        if (tools.mpegQuantization() && (sum & 1) == 0) {
            coefficients[out + 63] ^= 1;
        }
    }

    /**
     * Adds the decoded residual of a block to the prediction in the reconstruction.
     *
     * @param mb the macroblock number
     * @param mx the macroblock column
     * @param my the macroblock row
     * @param block the block index
     */
    private void addResidual(int mb, int mx, int my, int block) {
        var base = mb * 384 + block * 64;
        if (block < 4) {
            var stride = current.lumaStride;
            dsp.idctAdd(blocks.coefficients, base, current.y, current.lumaOrigin + (my * 16 + (block >> 1) * 8)
                    * stride + mx * 16 + (block & 1) * 8, stride, idctWork);
        } else {
            var stride = current.chromaStride;
            dsp.idctAdd(blocks.coefficients, base, block == 4 ? current.u : current.v, current.chromaOrigin
                    + my * 8 * stride + mx * 8, stride, idctWork);
        }
    }

    /**
     * Stores the decoded samples of an intra block into the reconstruction.
     *
     * @param mb the macroblock number
     * @param mx the macroblock column
     * @param my the macroblock row
     * @param block the block index
     */
    private void putIntra(int mb, int mx, int my, int block) {
        var base = mb * 384 + block * 64;
        if (block < 4) {
            var stride = current.lumaStride;
            dsp.idctPut(blocks.coefficients, base, current.y, current.lumaOrigin + (my * 16 + (block >> 1) * 8)
                    * stride + mx * 16 + (block & 1) * 8, stride, idctWork);
        } else {
            var stride = current.chromaStride;
            dsp.idctPut(blocks.coefficients, base, block == 4 ? current.u : current.v, current.chromaOrigin
                    + my * 8 * stride + mx * 8, stride, idctWork);
        }
    }

    /**
     * Writes the vector of one luminance block, as the difference from its median predictor.
     *
     * @param out the stream
     * @param mb the macroblock number
     * @param mx the macroblock column
     * @param my the macroblock row
     * @param block the block index, 0 to 3
     */
    private void writeVector(BitWriter out, int mb, int mx, int my, int block) {
        var at = mb * 8 + 2 * block;
        var vx = current.mv[at];
        var vy = current.mv[at + 1];
        var px = predictor(mb, mx, my, block, 0);
        var py = predictor(mb, mx, my, block, 1);
        if (!tools.h263()) {
            writeComponent(out, vx - px, forwardFcode);
            writeComponent(out, vy - py, forwardFcode);
        } else if (tools.unrestrictedVectors()) {
            var dx = vx - px;
            var dy = vy - py;
            writeReversible(out, dx);
            writeReversible(out, dy);
            if (dx == 1 && dy == 1) {
                out.bit(1);
            }
        } else {
            writeComponent(out, vx - px, 1);
            writeComponent(out, vy - py, 1);
        }
    }

    /**
     * Writes one vector difference component with Table B-12 (Table 14 of ITU-T H.263) and an f_code (7.6.3),
     * wrapped into the f_code's range.
     *
     * @param out the stream
     * @param difference the difference in vector units
     * @param fcode the f_code
     */
    private static void writeComponent(BitWriter out, int difference, int fcode) {
        var size = fcode - 1;
        var f = 1 << size;
        var range = 32 * f;
        if (difference < -range) {
            difference += 2 * range;
        } else if (difference >= range) {
            difference -= 2 * range;
        }
        if (difference == 0) {
            CodeTables.write(out, CodeTables.MVD[32]);
            return;
        }
        var value = Math.abs(difference) - 1;
        var code = (value >> size) + 1;
        CodeTables.write(out, CodeTables.MVD[32 + (difference < 0 ? -code : code)]);
        if (size > 0) {
            out.write(value & (f - 1), size);
        }
    }

    /**
     * Writes one vector difference with the reversible code of Table D.3 of ITU-T H.263.
     *
     * @param out the stream
     * @param difference the difference in half samples
     */
    private static void writeReversible(BitWriter out, int difference) {
        if (difference == 0) {
            out.bit(1);
            return;
        }
        out.bit(0);
        var magnitude = Math.abs(difference);
        var bits = 31 - Integer.numberOfLeadingZeros(magnitude);
        for (var i = bits - 1; i >= 0; i--) {
            out.bit(magnitude >> i & 1);
            out.bit(1);
        }
        out.bit(difference < 0 ? 1 : 0);
        out.bit(0);
    }

    /**
     * Returns the median predictor of a block vector component (7.6.5, ITU-T H.263 6.1.1 and F.2).
     *
     * @param mb the macroblock number
     * @param mx the macroblock column
     * @param my the macroblock row
     * @param block the block index, 0 to 3
     * @param component 0 for horizontal, 1 for vertical
     * @return the predictor
     */
    private int predictor(int mb, int mx, int my, int block, int component) {
        var mv = current.mv;
        int a;
        int b;
        int c;
        var validA = true;
        var validB = true;
        var validC = true;
        switch (block) {
            case 0 -> {
                validA = mx > 0 && mb - 1 >= packetStart;
                a = validA ? mv[(mb - 1) * 8 + 2 + component] : 0;
                validB = my > 0 && mb - mbWidth >= packetStart;
                b = validB ? mv[(mb - mbWidth) * 8 + 4 + component] : 0;
                validC = my > 0 && mx < mbWidth - 1 && mb - mbWidth + 1 >= packetStart;
                c = validC ? mv[(mb - mbWidth + 1) * 8 + 4 + component] : 0;
            }
            case 1 -> {
                a = mv[mb * 8 + component];
                validB = my > 0 && mb - mbWidth >= packetStart;
                b = validB ? mv[(mb - mbWidth) * 8 + 6 + component] : 0;
                validC = my > 0 && mx < mbWidth - 1 && mb - mbWidth + 1 >= packetStart;
                c = validC ? mv[(mb - mbWidth + 1) * 8 + 4 + component] : 0;
            }
            case 2 -> {
                validA = mx > 0 && mb - 1 >= packetStart;
                a = validA ? mv[(mb - 1) * 8 + 6 + component] : 0;
                b = mv[mb * 8 + component];
                c = mv[mb * 8 + 2 + component];
            }
            default -> {
                a = mv[mb * 8 + 4 + component];
                b = mv[mb * 8 + component];
                c = mv[mb * 8 + 2 + component];
            }
        }
        if (tools.h263()) {
            if (!validB) {
                b = a;
            }
            if (!validC) {
                c = mx == mbWidth - 1 && block <= 1 ? 0 : a;
            }
            return median(a, b, c);
        }
        var invalid = (validA ? 0 : 1) + (validB ? 0 : 1) + (validC ? 0 : 1);
        if (invalid == 3) {
            return 0;
        }
        if (invalid == 2) {
            return validA ? a : validB ? b : c;
        }
        return median(a, b, c);
    }

    /**
     * Returns the median of three values.
     *
     * @param a the first value
     * @param b the second value
     * @param c the third value
     * @return the median
     */
    private static int median(int a, int b, int c) {
        return Math.max(Math.min(a, b), Math.min(Math.max(a, b), c));
    }

    /**
     * Writes the coefficients of an inter block in zigzag order.
     *
     * @param out the stream
     * @param lv the levels in raster order
     */
    private void writeInterBlock(BitWriter out, int[] lv) {
        if (tools.h263()) {
            writeH263Events(out, lv, 0, Tables.ZIGZAG, CodeTables.INTER);
        } else {
            writeMpeg4Events(out, lv, 0, Tables.ZIGZAG, CodeTables.INTER, Tables.INTER_LMAX, Tables.INTER_RMAX);
        }
    }

    /**
     * Writes the coefficient events of a block with the escapes of ISO/IEC 14496-2 7.4.1.3.
     *
     * @param out the stream
     * @param lv the levels in raster order
     * @param start the first scan index
     * @param scan the scan order
     * @param table the coefficient codes
     * @param lmax the largest level of each {@code last, run} pair
     * @param rmax the largest run of each {@code last, level} pair
     */
    private static void writeMpeg4Events(BitWriter out, int[] lv, int start, int[] scan, int[] table, int[] lmax,
                                         int[] rmax) {
        var end = lastScanIndex(lv, start, scan);
        var run = 0;
        for (var index = start; index <= end; index++) {
            var level = lv[scan[index]];
            if (level == 0) {
                run++;
                continue;
            }
            var last = index == end ? 1 : 0;
            var magnitude = Math.abs(level);
            var sign = level < 0 ? 1 : 0;
            var code = magnitude < 128 ? table[CodeTables.event(last, run, magnitude)] : 0;
            if (code != 0) {
                CodeTables.write(out, code);
                out.bit(sign);
                run = 0;
                continue;
            }
            var reduced = magnitude - lmax[last * 64 + run];
            if (reduced > 0 && reduced < 128) {
                code = table[CodeTables.event(last, run, reduced)];
                if (code != 0) {
                    CodeTables.write(out, CodeTables.ESCAPE);
                    out.bit(0);
                    CodeTables.write(out, code);
                    out.bit(sign);
                    run = 0;
                    continue;
                }
            }
            if (magnitude < 64 && rmax[last * 64 + magnitude] >= 0) {
                var shorter = run - rmax[last * 64 + magnitude] - 1;
                if (shorter >= 0) {
                    code = table[CodeTables.event(last, shorter, magnitude)];
                    if (code != 0) {
                        CodeTables.write(out, CodeTables.ESCAPE);
                        out.write(0b10, 2);
                        CodeTables.write(out, code);
                        out.bit(sign);
                        run = 0;
                        continue;
                    }
                }
            }
            CodeTables.write(out, CodeTables.ESCAPE);
            out.write(0b11, 2);
            out.bit(last);
            out.write(run, 6);
            out.bit(1);
            out.write(level, 12);
            out.bit(1);
            run = 0;
        }
    }

    /**
     * Writes the coefficient events of a block with the fixed-length escape of ITU-T H.263 5.4.2 and the extended
     * escape of the Modified Quantization mode (T.4).
     *
     * @param out the stream
     * @param lv the levels in raster order
     * @param start the first scan index
     * @param scan the scan order
     * @param table the coefficient codes
     */
    private static void writeH263Events(BitWriter out, int[] lv, int start, int[] scan, int[] table) {
        var end = lastScanIndex(lv, start, scan);
        var run = 0;
        for (var index = start; index <= end; index++) {
            var level = lv[scan[index]];
            if (level == 0) {
                run++;
                continue;
            }
            var last = index == end ? 1 : 0;
            var magnitude = Math.abs(level);
            var code = magnitude < 128 ? table[CodeTables.event(last, run, magnitude)] : 0;
            if (code != 0) {
                CodeTables.write(out, code);
                out.bit(level < 0 ? 1 : 0);
            } else {
                CodeTables.write(out, CodeTables.ESCAPE);
                out.bit(last);
                out.write(run, 6);
                if (magnitude <= 127) {
                    out.write(level, 8);
                } else {
                    out.write(0x80, 8);
                    var v = level & 0x7FF;
                    out.write((v & 0x1F) << 6 | v >> 5, 11);
                }
            }
            run = 0;
        }
    }

    /**
     * Returns the scan index of the last nonzero level of a block.
     *
     * @param lv the levels in raster order
     * @param start the first scan index
     * @param scan the scan order
     * @return the index, or {@code start - 1} when every level from {@code start} on is zero
     */
    private static int lastScanIndex(int[] lv, int start, int[] scan) {
        for (var index = 63; index >= start; index--) {
            if (lv[scan[index]] != 0) {
                return index;
            }
        }
        return start - 1;
    }

    /**
     * Codes one intra macroblock.
     *
     * @param out the stream
     * @param mb the macroblock number
     * @param mx the macroblock column
     * @param my the macroblock row
     */
    private void codeIntra(BitWriter out, int mb, int mx, int my) {
        blocks.kind[mb] = Macroblocks.INTRA;
        blocks.cbp[mb] = 63;
        current.mbType[mb] = Frame.MB_INTRA;
        if (tools.h263() && tools.advancedIntra()) {
            codeAdvancedIntra(out, mb, mx, my);
        } else if (tools.h263()) {
            codeH263Intra(out, mb, mx, my);
        } else {
            codeMpeg4Intra(out, mb, mx, my);
        }
    }

    /**
     * Writes the macroblock header fields of an intra macroblock up to and including {@code cbpy}.
     *
     * @param out the stream
     * @param cbp the coded block pattern, block {@code b} in bit {@code b}
     * @param acPred the {@code ac_pred_flag} of an MPEG-4 macroblock
     * @param intraMode the INTRA_MODE of an Advanced INTRA Coding macroblock, or -1
     */
    private void writeIntraHeader(BitWriter out, int cbp, boolean acPred, int intraMode) {
        var cbpc = (cbp >> 3 & 2) | (cbp >> 5 & 1);
        if (type == Vop.I) {
            CodeTables.write(out, CodeTables.MCBPC_I[3 << 2 | cbpc]);
        } else {
            out.bit(0);
            CodeTables.write(out, (tools.h263() && tools.extendedType() ? CodeTables.MCBPC_P_EXTENDED
                    : CodeTables.MCBPC_P)[3 << 2 | cbpc]);
        }
        if (!tools.h263()) {
            out.flag(acPred);
        } else if (intraMode >= 0) {
            if (intraMode == 0) {
                out.bit(0);
            } else {
                out.write(intraMode == 1 ? 0b10 : 0b11, 2);
            }
        }
        var cbpy = (cbp & 1) << 3 | (cbp & 2) << 1 | (cbp & 4) >> 1 | (cbp & 8) >> 3;
        CodeTables.write(out, CodeTables.CBPY[cbpy]);
    }

    /**
     * Codes an H.263 intra macroblock without Advanced INTRA Coding: an 8-bit DC level and the AC coefficients of
     * Table 16.
     *
     * @param out the stream
     * @param mb the macroblock number
     * @param mx the macroblock column
     * @param my the macroblock row
     */
    private void codeH263Intra(BitWriter out, int mb, int mx, int my) {
        var cbp = 0;
        for (var block = 0; block < 6; block++) {
            var blockQuant = block < 4 ? quant : h263Header.chromaQuant(quant);
            loadSource(mx, my, block);
            ForwardDct.transform(work);
            var lv = levels[block];
            lv[0] = clamp((work[0] + 4) >> 3, 1, 254);
            var limit = levelLimit(blockQuant, false);
            var coded = false;
            for (var i = 1; i < 64; i++) {
                var level = Math.min(Math.abs(work[i]) / (2 * blockQuant), limit);
                lv[i] = work[i] < 0 ? -level : level;
                coded |= level != 0;
            }
            if (coded) {
                cbp |= 1 << block;
            }
            var coefficients = blocks.coefficients;
            var base = mb * 384 + block * 64;
            coefficients[base] = (short) (lv[0] * 8);
            var even = 1 - (blockQuant & 1);
            for (var i = 1; i < 64; i++) {
                var level = lv[i];
                if (level != 0) {
                    var m = (2 * Math.abs(level) + 1) * blockQuant - even;
                    coefficients[base + column(i)] = (short) clamp(level < 0 ? -m : m, -2048, 2047);
                }
            }
        }
        writeIntraHeader(out, cbp, false, -1);
        for (var block = 0; block < 6; block++) {
            var lv = levels[block];
            out.write(lv[0] == 128 ? 255 : lv[0], 8);
            if ((cbp & (1 << block)) != 0) {
                writeH263Events(out, lv, 1, Tables.ZIGZAG, CodeTables.INTER);
            }
            putIntra(mb, mx, my, block);
        }
    }

    /**
     * Codes an intra macroblock in the Advanced INTRA Coding mode (ITU-T H.263 Annex I): the prediction mode with the
     * fewest bits, each block coded as the residual of its prediction from the block above or left.
     *
     * @param out the stream
     * @param mb the macroblock number
     * @param mx the macroblock column
     * @param my the macroblock row
     */
    private void codeAdvancedIntra(BitWriter out, int mb, int mx, int my) {
        var coefficients = intraCoefficients;
        for (var block = 0; block < 6; block++) {
            loadSource(mx, my, block);
            ForwardDct.transform(work);
            System.arraycopy(work, 0, coefficients[block], 0, 64);
        }
        var bestMode = 0;
        var bestBits = Long.MAX_VALUE;
        for (var mode = 0; mode < 3; mode++) {
            trial.reset();
            predictAdvancedIntra(mb, mx, my, mode, coefficients, false);
            var scan = mode == 0 ? Tables.ZIGZAG : mode == 1 ? Tables.ALTERNATE_HORIZONTAL : Tables.ALTERNATE_VERTICAL;
            for (var block = 0; block < 6; block++) {
                writeH263Events(trial, levels[block], 0, scan, CodeTables.ADVANCED_INTRA);
            }
            if (trial.position() < bestBits) {
                bestBits = trial.position();
                bestMode = mode;
            }
        }
        var cbp = predictAdvancedIntra(mb, mx, my, bestMode, coefficients, true);
        writeIntraHeader(out, cbp, false, bestMode);
        var scan = bestMode == 0 ? Tables.ZIGZAG
                : bestMode == 1 ? Tables.ALTERNATE_HORIZONTAL : Tables.ALTERNATE_VERTICAL;
        for (var block = 0; block < 6; block++) {
            if ((cbp & (1 << block)) != 0) {
                writeH263Events(out, levels[block], 0, scan, CodeTables.ADVANCED_INTRA);
            }
            putIntra(mb, mx, my, block);
        }
    }

    /**
     * Quantizes the blocks of an intra macroblock as the residuals of an Advanced INTRA Coding prediction mode (I.3)
     * into {@link #levels}, and, when committing, stores their reconstruction and updates the predictor stores.
     *
     * @param mb the macroblock number
     * @param mx the macroblock column
     * @param my the macroblock row
     * @param mode 0 for DC, 1 for vertical, 2 for horizontal prediction
     * @param coefficients the DCT coefficients of the six blocks in raster order
     * @param commit whether the reconstruction is stored
     * @return the coded block pattern, block {@code b} in bit {@code b}
     */
    private int predictAdvancedIntra(int mb, int mx, int my, int mode, int[][] coefficients, boolean commit) {
        var cbp = 0;
        var recon = intraRecon;
        for (var block = 0; block < 6; block++) {
            var luma = block < 4;
            var blockQuant = luma ? quant : h263Header.chromaQuant(quant);
            int bx;
            int by;
            int gridWidth;
            int base;
            int shift;
            if (luma) {
                bx = 2 * mx + (block & 1);
                by = 2 * my + (block >> 1);
                gridWidth = 2 * mbWidth;
                base = 0;
                shift = 1;
            } else {
                bx = mx;
                by = my;
                gridWidth = mbWidth;
                base = block == 4 ? 4 * mbCount : 5 * mbCount;
                shift = 0;
            }
            var index = base + by * gridWidth + bx;
            var aboveMb = by > 0 ? ((by - 1) >> shift) * mbWidth + (bx >> shift) : -1;
            var leftMb = bx > 0 ? (by >> shift) * mbWidth + ((bx - 1) >> shift) : -1;
            var above = aboveMb >= 0 && aboveMb >= packetStart && intraAt(aboveMb, mb);
            var left = leftMb >= 0 && leftMb >= packetStart && intraAt(leftMb, mb);
            var aboveIndex = index - gridWidth;
            var leftIndex = index - 1;
            var aboveSameMb = aboveMb == mb;
            var leftSameMb = leftMb == mb;
            var pred = work;
            Arrays.fill(pred, 0);
            switch (mode) {
                case 0 -> {
                    var a = above ? storedDc(aboveSameMb, recon, block - 2, aboveIndex) : 0;
                    var l = left ? storedDc(leftSameMb, recon, block - 1, leftIndex) : 0;
                    pred[0] = above && left ? (a + l) / 2 : above ? a : left ? l : DEFAULT_DC;
                }
                case 1 -> {
                    if (above) {
                        pred[0] = storedDc(aboveSameMb, recon, block - 2, aboveIndex);
                        for (var u = 1; u < 8; u++) {
                            pred[u] = aboveSameMb ? recon[block - 2][u] : rowStore[aboveIndex * 8 + u];
                        }
                    } else {
                        pred[0] = DEFAULT_DC;
                    }
                }
                default -> {
                    if (left) {
                        pred[0] = storedDc(leftSameMb, recon, block - 1, leftIndex);
                        for (var v = 1; v < 8; v++) {
                            pred[v * 8] = leftSameMb ? recon[block - 1][v * 8] : columnStore[leftIndex * 8 + v];
                        }
                    } else {
                        pred[0] = DEFAULT_DC;
                    }
                }
            }
            var lv = levels[block];
            var limit = levelLimit(blockQuant, true);
            var step = 2 * blockQuant;
            var r = recon[block];
            var nonzero = false;
            for (var i = 0; i < 64; i++) {
                var residual = coefficients[block][i] - pred[i];
                var level = Math.min(limit, (Math.abs(residual) + blockQuant) / step);
                level = residual < 0 ? -level : level;
                lv[i] = level;
                nonzero |= level != 0;
                var value = level * step + pred[i];
                if (i == 0) {
                    if ((value & 1) == 0) {
                        value++;
                    }
                    r[0] = clamp(value, 0, 2047);
                } else {
                    r[i] = clamp(value, -2048, 2047);
                }
            }
            if (nonzero) {
                cbp |= 1 << block;
            }
            if (commit) {
                dcStore[index] = r[0];
                for (var u = 1; u < 8; u++) {
                    rowStore[index * 8 + u] = r[u];
                    columnStore[index * 8 + u] = r[u * 8];
                }
                var out = mb * 384 + block * 64;
                for (var i = 0; i < 64; i++) {
                    blocks.coefficients[out + column(i)] = (short) r[i];
                }
            }
        }
        return cbp;
    }

    /**
     * Returns whether a neighboring macroblock is intra coded, the current one counting as intra.
     *
     * @param neighbor the neighboring macroblock number
     * @param mb the current macroblock
     * @return {@code true} for an intra neighbor
     */
    private boolean intraAt(int neighbor, int mb) {
        return neighbor == mb || current.mbType[neighbor] == Frame.MB_INTRA;
    }

    /**
     * Returns the reconstructed DC of a neighboring block, from the macroblock being coded or from the store.
     *
     * @param sameMb whether the neighbor is in the macroblock being coded
     * @param recon the reconstruction of the blocks of the macroblock so far
     * @param block the neighbor's index within the macroblock
     * @param index the neighbor's index in the stores
     * @return the DC
     */
    private int storedDc(boolean sameMb, int[][] recon, int block, int index) {
        return sameMb ? recon[block][0] : dcStore[index];
    }

    /**
     * Codes an MPEG-4 intra macroblock (7.4.1 to 7.4.4): the DC coefficients with the DC VLCs and the DC prediction,
     * and the AC coefficients with AC prediction when it saves on the levels.
     *
     * @param out the stream
     * @param mb the macroblock number
     * @param mx the macroblock column
     * @param my the macroblock row
     */
    private void codeMpeg4Intra(BitWriter out, int mb, int mx, int my) {
        var cbp = prepareMpeg4Intra(mb, mx, my);
        writeIntraHeader(out, cbp, acPredicted, -1);
        for (var block = 0; block < 6; block++) {
            writeDcDifferential(out, codedLevels[block][0], block < 4);
            if ((cbp & (1 << block)) != 0) {
                writeMpeg4Events(out, codedLevels[block], 1, intraScan(block), CodeTables.INTRA, Tables.INTRA_LMAX,
                        Tables.INTRA_RMAX);
            }
        }
    }

    /**
     * Quantizes the blocks of an MPEG-4 intra macroblock, chooses its AC prediction, stores the coded levels
     * (the DC differential in place of the DC level) in {@link #codedLevels} and the decoded samples in the
     * reconstruction, and updates the predictor stores.
     *
     * @param mb the macroblock number
     * @param mx the macroblock column
     * @param my the macroblock row
     * @return the coded block pattern, block {@code b} in bit {@code b}
     */
    private int prepareMpeg4Intra(int mb, int mx, int my) {
        var score = 0;
        for (var block = 0; block < 6; block++) {
            var luma = block < 4;
            loadSource(mx, my, block);
            ForwardDct.transform(work);
            var scaler = Tables.dcScaler(quant, luma);
            var lv = levels[block];
            var qdc = clamp((work[0] + (scaler >> 1)) / scaler, 0, 2047 / scaler);
            for (var i = 1; i < 64; i++) {
                var magnitude = Math.abs(work[i]);
                int level;
                if (tools.mpegQuantization()) {
                    var step = tools.intraMatrix()[i] * quant;
                    level = (16 * magnitude + step) / (2 * step);
                } else {
                    level = magnitude / (2 * quant);
                }
                level = Math.min(level, 2047);
                lv[i] = work[i] < 0 ? -level : level;
            }
            int bx;
            int by;
            int gridWidth;
            int base;
            int shift;
            if (luma) {
                bx = 2 * mx + (block & 1);
                by = 2 * my + (block >> 1);
                gridWidth = 2 * mbWidth;
                base = 0;
                shift = 1;
            } else {
                bx = mx;
                by = my;
                gridWidth = mbWidth;
                base = block == 4 ? 4 * mbCount : 5 * mbCount;
                shift = 0;
            }
            var index = base + by * gridWidth + bx;
            var availA = bx > 0 && ((by >> shift) * mbWidth + ((bx - 1) >> shift)) >= packetStart;
            var availB = bx > 0 && by > 0 && (((by - 1) >> shift) * mbWidth + ((bx - 1) >> shift)) >= packetStart;
            var availC = by > 0 && (((by - 1) >> shift) * mbWidth + (bx >> shift)) >= packetStart;
            var fa = availA ? dcStore[index - 1] : DEFAULT_DC;
            var fb = availB ? dcStore[index - gridWidth - 1] : DEFAULT_DC;
            var fc = availC ? dcStore[index - gridWidth] : DEFAULT_DC;
            var fromC = Math.abs(fa - fb) < Math.abs(fb - fc);
            fromAbove[block] = fromC;
            dcDiff[block] = qdc - divRound(fromC ? fc : fa, scaler);
            dcValue[block] = clamp(qdc * scaler, -2048, 2047);
            dcStore[index] = dcValue[block];
            var p = predicted[block];
            Arrays.fill(p, 0);
            var available = fromC ? availC : availA;
            if (available) {
                var neighborMb = fromC
                        ? (luma ? ((by - 1) >> 1) * mbWidth + (bx >> 1) : (by - 1) * mbWidth + bx)
                        : (luma ? (by >> 1) * mbWidth + ((bx - 1) >> 1) : by * mbWidth + bx - 1);
                var predQuant = blocks.quant[neighborMb];
                var neighbor = fromC ? index - gridWidth : index - 1;
                for (var k = 1; k < 8; k++) {
                    var stored = fromC ? rowStore[neighbor * 8 + k] : columnStore[neighbor * 8 + k];
                    p[k] = stored == 0 ? 0 : divRound(stored * predQuant, quant);
                }
            }
            for (var k = 1; k < 8; k++) {
                var level = fromC ? lv[k] : lv[k * 8];
                var residual = level - p[k];
                score += Math.abs(level) - Math.abs(residual);
                if (Math.abs(residual) > 2047) {
                    score = Integer.MIN_VALUE / 2;
                }
            }
            for (var k = 1; k < 8; k++) {
                rowStore[index * 8 + k] = lv[k];
                columnStore[index * 8 + k] = lv[k * 8];
            }
        }
        var acPred = tools.acPrediction() && score > 0;
        var cbp = 0;
        var coded = codedLevels;
        for (var block = 0; block < 6; block++) {
            var c = coded[block];
            System.arraycopy(levels[block], 0, c, 0, 64);
            c[0] = dcDiff[block];
            if (acPred) {
                var p = predicted[block];
                for (var k = 1; k < 8; k++) {
                    if (fromAbove[block]) {
                        c[k] -= p[k];
                    } else {
                        c[k * 8] -= p[k];
                    }
                }
            }
            for (var i = 1; i < 64; i++) {
                if (c[i] != 0) {
                    cbp |= 1 << block;
                    break;
                }
            }
        }
        acPredicted = acPred;
        for (var block = 0; block < 6; block++) {
            dequantizeIntra(mb, block, levels[block], dcValue[block]);
            putIntra(mb, mx, my, block);
        }
        return cbp;
    }

    /**
     * Writes an intra DC differential with {@code dct_dc_size} and {@code dct_dc_differential} (Tables B-13 to B-15).
     *
     * @param out the stream
     * @param value the differential
     * @param luma whether the block is a luminance block
     */
    private static void writeDcDifferential(BitWriter out, int value, boolean luma) {
        var magnitude = Math.abs(value);
        var size = magnitude == 0 ? 0 : 32 - Integer.numberOfLeadingZeros(magnitude);
        CodeTables.write(out, (luma ? CodeTables.DC_SIZE_LUMA : CodeTables.DC_SIZE_CHROMA)[size]);
        if (size == 0) {
            return;
        }
        out.write(value > 0 ? value : value + (1 << size) - 1, size);
        if (size > 8) {
            out.bit(1);
        }
    }

    /**
     * Stores the dequantized coefficients of an MPEG-4 intra block as a decoder computes them.
     *
     * @param mb the macroblock number
     * @param block the block index
     * @param lv the final levels in raster order
     * @param dc the reconstructed DC coefficient
     */
    private void dequantizeIntra(int mb, int block, int[] lv, int dc) {
        var coefficients = blocks.coefficients;
        var out = mb * 384 + block * 64;
        coefficients[out] = (short) dc;
        if (tools.mpegQuantization()) {
            var matrix = tools.intraMatrix();
            var sum = dc;
            for (var i = 1; i < 64; i++) {
                var level = lv[i];
                if (level != 0) {
                    var value = clamp(level * matrix[i] * quant * 2 / 16, -2048, 2047);
                    coefficients[out + column(i)] = (short) value;
                    sum += value;
                }
            }
            if ((sum & 1) == 0) {
                coefficients[out + 63] ^= 1;
            }
        } else {
            var odd = quant & 1;
            for (var i = 1; i < 64; i++) {
                var level = lv[i];
                if (level != 0) {
                    var negative = level >> 31;
                    var magnitude = (2 * ((level ^ negative) - negative) + 1) * quant - (1 - odd);
                    coefficients[out + column(i)] = (short) clamp((magnitude ^ negative) - negative, -2048, 2047);
                }
            }
        }
    }

    /**
     * Writes the default intra predictors of a non-intra macroblock.
     *
     * @param mb the macroblock number
     */
    private void resetIntraStore(int mb) {
        var mx = mb % mbWidth;
        var my = mb / mbWidth;
        var gridWidth = 2 * mbWidth;
        for (var block = 0; block < 4; block++) {
            resetBlockStore((2 * my + (block >> 1)) * gridWidth + 2 * mx + (block & 1));
        }
        resetBlockStore(4 * mbCount + mb);
        resetBlockStore(5 * mbCount + mb);
    }

    /**
     * Writes the default predictor values of one block.
     *
     * @param index the block index in the stores
     */
    private void resetBlockStore(int index) {
        dcStore[index] = DEFAULT_DC;
        Arrays.fill(rowStore, index * 8, index * 8 + 8, 0);
        Arrays.fill(columnStore, index * 8, index * 8 + 8, 0);
    }

    /**
     * Codes one macroblock of a B-VOP (6.2.6).
     *
     * @param out the stream
     * @param mb the macroblock number
     * @param mx the macroblock column
     * @param my the macroblock row
     */
    private void codeBidirectional(BitWriter out, int mb, int mx, int my) {
        blocks.quant[mb] = (byte) quant;
        var mode = modes[mb];
        if (mode == B_FORCED) {
            blocks.cbp[mb] = 0;
            return;
        }
        var cbp = residuals(mb, mx, my);
        var direct = mode == B_DIRECT;
        var deltaX = deltas[2 * mb];
        var deltaY = deltas[2 * mb + 1];
        if (direct && cbp == 0 && deltaX == 0 && deltaY == 0) {
            out.bit(1);
            return;
        }
        out.bit(0);
        out.bit(cbp == 0 ? 1 : 0);
        switch (mode) {
            case B_DIRECT -> out.bit(1);
            case B_INTERPOLATED -> out.write(0b01, 2);
            case B_BACKWARD -> out.write(0b001, 3);
            default -> out.write(0b0001, 4);
        }
        if (cbp != 0) {
            var pattern = 0;
            for (var block = 0; block < 6; block++) {
                if ((cbp & (1 << block)) != 0) {
                    pattern |= 32 >> block;
                }
            }
            out.write(pattern, 6);
            if (!direct) {
                out.bit(0);
            }
        }
        var at = mb * 8;
        if (direct) {
            writeComponent(out, deltaX, 1);
            writeComponent(out, deltaY, 1);
        } else {
            if (mode == B_FORWARD || mode == B_INTERPOLATED) {
                writeComponent(out, blocks.forward[at] - bPredictors[0], forwardFcode);
                writeComponent(out, blocks.forward[at + 1] - bPredictors[1], forwardFcode);
                bPredictors[0] = blocks.forward[at];
                bPredictors[1] = blocks.forward[at + 1];
            }
            if (mode == B_BACKWARD || mode == B_INTERPOLATED) {
                writeComponent(out, blocks.backward[at] - bPredictors[2], backwardFcode);
                writeComponent(out, blocks.backward[at + 1] - bPredictors[3], backwardFcode);
                bPredictors[2] = blocks.backward[at];
                bPredictors[3] = blocks.backward[at + 1];
            }
        }
        for (var block = 0; block < 6; block++) {
            if ((cbp & (1 << block)) != 0) {
                writeMpeg4Events(out, levels[block], 0, Tables.ZIGZAG, CodeTables.INTER, Tables.INTER_LMAX,
                        Tables.INTER_RMAX);
                addResidual(mb, mx, my, block);
            }
        }
    }

    /**
     * Divides with rounding to nearest, halves away from zero (the {@code //} operator).
     *
     * @param numerator the dividend
     * @param denominator the divisor, positive
     * @return the quotient
     */
    private static int divRound(int numerator, int denominator) {
        return numerator >= 0 ? (numerator + (denominator >> 1)) / denominator
                : -((-numerator + (denominator >> 1)) / denominator);
    }

    /**
     * Returns the index of a raster position in the column-major order of the coefficient blocks.
     *
     * @param position the raster position
     * @return the column-major index
     */
    private static int column(int position) {
        return (position & 7) << 3 | position >> 3;
    }

    /**
     * Clamps a value.
     *
     * @param value the value to clamp
     * @param low the lower bound
     * @param high the upper bound
     * @return the clamped value
     */
    private static int clamp(int value, int low, int high) {
        return value < low ? low : Math.min(value, high);
    }
}
