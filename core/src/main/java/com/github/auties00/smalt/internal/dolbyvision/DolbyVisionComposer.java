package com.github.auties00.smalt.internal.dolbyvision;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.internal.nal.DolbyVisionRpu;
import com.github.auties00.smalt.internal.task.ParallelBands;

/**
 * The composer of ETSI GS CCM 001, clause 5.4, for a stream without an enhancement layer: maps a 10-bit 4:2:0 base
 * layer picture through the curves of an RPU to a 4:2:0 picture of the reconstructed bit depth.
 *
 * <p>Polynomial pieces are tabulated per base layer code when a mapping is prepared; regression pieces, which depend
 * on three samples, are computed per sample. The arithmetic is the integer arithmetic of the clause, so the output is
 * exact. Without an enhancement layer the clause ignores {@code disable_residual_flag} and the nonlinear quantization
 * parameters: the reconstructed signal is the mapped base layer.
 *
 * <p>Instances are not thread-safe. Arguments are not checked; values outside the documented ranges are undefined
 * behavior.
 *
 * @spec https://www.etsi.org/deliver/etsi_gs/CCM/001_099/001/01.01.01_60/gs_CCM001v010101p.pdf ETSI GS CCM 001 V1.1.1
 */
final class DolbyVisionComposer {
    /**
     * The bit depth of the base layer, the only one {@link DolbyVisionRpu} accepts.
     */
    static final int BASE_DEPTH = 10;

    /**
     * The number of base layer codes.
     */
    private static final int BASE_CODES = 1 << BASE_DEPTH;

    /**
     * The fractional bits of the regression terms ({@code 2 * BL_bit_depth} in the clause).
     */
    private static final int TERM_BITS = 20;

    /**
     * The approximate operations per chroma sample of a regression, which weighs its work against
     * {@link ParallelBands#MIN_PARALLEL_WORK}.
     */
    private static final long MMR_WORK = 24;

    /**
     * The reconstructed value of each base layer code per component, for the codes a polynomial maps.
     */
    private final short[][] tables = new short[3][BASE_CODES];

    /**
     * The regression that maps each base layer code per component, {@code null} for a polynomial.
     */
    private final DolbyVisionRpu.Piece[][] regressions = new DolbyVisionRpu.Piece[3][BASE_CODES];

    /**
     * The lowest pivot value of each component, clamped to the base layer codes.
     */
    private final int[] lowest = new int[3];

    /**
     * The highest pivot value of each component, clamped to the base layer codes.
     */
    private final int[] highest = new int[3];

    /**
     * Whether a regression maps any chroma code.
     */
    private boolean anyRegression;

    /**
     * The reconstructed bit depth.
     */
    private int depth;

    /**
     * The fractional bits of the coefficients.
     */
    private int denominatorBits;

    /**
     * The mapping the tables hold, compared by identity.
     */
    private DolbyVisionRpu.Mapping prepared;

    /**
     * Prepares the tables of a mapping, keeping them when it is the mapping last prepared at the same depth.
     *
     * @param mapping the curves
     * @param depth the reconstructed bit depth, from 8 to 14
     * @throws MediaException.Malformed if a regression maps the luma component, which the clause forbids
     */
    void prepare(DolbyVisionRpu.Mapping mapping, int depth) {
        if (mapping == prepared && depth == this.depth) {
            return;
        }
        prepared = null;
        this.depth = depth;
        this.denominatorBits = mapping.denominatorBits();
        anyRegression = false;
        for (var component = 0; component < 3; component++) {
            var curve = mapping.curves()[component];
            var pivots = curve.pivots();
            lowest[component] = Math.min(pivots[0], BASE_CODES - 1);
            highest[component] = Math.min(pivots[pivots.length - 1], BASE_CODES - 1);
            for (var code = 0; code < BASE_CODES; code++) {
                var piece = curve.pieces()[pieceIndex(pivots, code)];
                if (piece.mmr()) {
                    if (component == 0) {
                        throw new MediaException.Malformed("Dolby Vision regression mapping of the luma component");
                    }
                    regressions[component][code] = piece;
                    anyRegression = true;
                } else {
                    regressions[component][code] = null;
                    var clamped = Math.max(pivots[0], Math.min(code, pivots[pivots.length - 1]));
                    tables[component][code] = (short) reconstruct(polynomial(piece.coefficients(), clamped));
                }
            }
        }
        prepared = mapping;
    }

    /**
     * Reconstructs a picture with the prepared mapping.
     *
     * @param base the base layer, 10 bits, 4:2:0
     * @param y receives the luma plane, one row of the picture width after another
     * @param cb receives the first chroma plane, one row of the chroma width after another
     * @param cr receives the second chroma plane
     * @param threads the decoder's thread count, 1 keeping the work on the calling thread
     */
    void compose(Yuv16Picture base, short[] y, short[] cb, short[] cr, int threads) {
        var width = base.width();
        var height = base.height();
        var lumaBands = threads == 1 ? 1 : ParallelBands.bands(height, (long) width * height);
        ParallelBands.run(lumaBands, band -> {
            var end = ParallelBands.start(band + 1, lumaBands, height);
            for (var row = ParallelBands.start(band, lumaBands, height); row < end; row++) {
                lumaRow(base, row, y);
            }
        });
        var chromaHeight = base.chromaHeight();
        var work = (long) base.chromaWidth() * chromaHeight * (anyRegression ? 2 * MMR_WORK : 2);
        var chromaBands = threads == 1 ? 1 : ParallelBands.bands(chromaHeight, work);
        ParallelBands.run(chromaBands, band -> {
            var end = ParallelBands.start(band + 1, chromaBands, chromaHeight);
            for (var row = ParallelBands.start(band, chromaBands, chromaHeight); row < end; row++) {
                chromaRow(base, row, cb, cr);
            }
        });
    }

    /**
     * Maps one luma row.
     *
     * @param base the base layer
     * @param row the index of the row
     * @param y the output plane
     */
    private void lumaRow(Yuv16Picture base, int row, short[] y) {
        var width = base.width();
        var source = base.y();
        var input = row * base.yStride();
        var output = row * width;
        var table = tables[0];
        for (var x = 0; x < width; x++) {
            y[output + x] = table[source[input + x] & (BASE_CODES - 1)];
        }
    }

    /**
     * Maps one row of both chroma planes.
     *
     * @param base the base layer
     * @param row the chroma row
     * @param cb the first output plane
     * @param cr the second output plane
     */
    private void chromaRow(Yuv16Picture base, int row, short[] cb, short[] cr) {
        var width = base.chromaWidth();
        var stride = base.uvStride();
        var u = base.u();
        var v = base.v();
        var input = row * stride;
        var output = row * width;
        var cbTable = tables[1];
        var crTable = tables[2];
        if (!anyRegression) {
            for (var x = 0; x < width; x++) {
                cb[output + x] = cbTable[u[input + x] & (BASE_CODES - 1)];
                cr[output + x] = crTable[v[input + x] & (BASE_CODES - 1)];
            }
            return;
        }
        var cbRegressions = regressions[1];
        var crRegressions = regressions[2];
        for (var x = 0; x < width; x++) {
            var s1 = u[input + x] & (BASE_CODES - 1);
            var s2 = v[input + x] & (BASE_CODES - 1);
            var cbPiece = cbRegressions[s1];
            var crPiece = crRegressions[s2];
            if (cbPiece == null && crPiece == null) {
                cb[output + x] = cbTable[s1];
                cr[output + x] = crTable[s2];
                continue;
            }
            var s0 = downsampledLuma(base, x, row);
            cb[output + x] = cbPiece == null ? cbTable[s1] : (short) regression(cbPiece, s0, s1, s2);
            cr[output + x] = crPiece == null ? crTable[s2] : (short) regression(crPiece, s0, s1, s2);
        }
    }

    /**
     * Computes the luma sample aligned with a chroma sample: a 1-2-1 filter across the column of the chroma sample
     * on each of its two luma rows, averaged, with pixel replication at the edges.
     *
     * @param base the base layer
     * @param x the chroma column
     * @param row the chroma row
     * @return the sample
     */
    private static int downsampledLuma(Yuv16Picture base, int x, int row) {
        var luma = base.y();
        var stride = base.yStride();
        var center = 2 * x;
        var left = Math.max(center - 1, 0);
        var limit = base.width() - 1;
        var right = Math.min(center + 1, limit);
        center = Math.min(center, limit);
        var first = 2 * row * stride;
        var second = Math.min(2 * row + 1, base.height() - 1) * stride;
        var upper = (sample(luma, first + left) + 2 * sample(luma, first + center) + sample(luma, first + right) + 2) >> 2;
        var lower = (sample(luma, second + left) + 2 * sample(luma, second + center) + sample(luma, second + right) + 2) >> 2;
        return (upper + lower + 1) >> 1;
    }

    /**
     * {@return one base layer sample}
     *
     * @param plane the samples
     * @param index the index of the sample
     */
    private static int sample(short[] plane, int index) {
        return plane[index] & (BASE_CODES - 1);
    }

    /**
     * Evaluates a regression at a sample.
     *
     * @param piece the regression
     * @param luma the downsampled luma sample
     * @param first the first chroma sample
     * @param second the second chroma sample
     * @return the reconstructed value, unsigned
     */
    private int regression(DolbyVisionRpu.Piece piece, int luma, int first, int second) {
        long s0 = Math.max(lowest[0], Math.min(luma, highest[0]));
        long s1 = Math.max(lowest[1], Math.min(first, highest[1]));
        long s2 = Math.max(lowest[2], Math.min(second, highest[2]));
        var c = piece.coefficients();
        var order = piece.order();
        var t1 = s0 << (TERM_BITS - BASE_DEPTH);
        var t2 = s1 << (TERM_BITS - BASE_DEPTH);
        var t3 = s2 << (TERM_BITS - BASE_DEPTH);
        var t4 = s0 * s1;
        var t5 = s0 * s2;
        var t6 = s1 * s2;
        var t7 = (t4 * t3) >> TERM_BITS;
        var sum = (c[0] << TERM_BITS) + c[1] * t1 + c[2] * t2 + c[3] * t3 + c[4] * t4 + c[5] * t5 + c[6] * t6 + c[7] * t7;
        if (order >= 2) {
            var t8 = s0 * s0;
            var t9 = s1 * s1;
            var t10 = s2 * s2;
            var t11 = (t4 * t4) >> TERM_BITS;
            var t12 = (t5 * t5) >> TERM_BITS;
            var t13 = (t6 * t6) >> TERM_BITS;
            var t14 = (t7 * t7) >> TERM_BITS;
            sum += c[8] * t8 + c[9] * t9 + c[10] * t10 + c[11] * t11 + c[12] * t12 + c[13] * t13 + c[14] * t14;
            if (order >= 3) {
                sum += c[15] * ((t1 * t8) >> TERM_BITS) + c[16] * ((t2 * t9) >> TERM_BITS)
                        + c[17] * ((t3 * t10) >> TERM_BITS) + c[18] * ((t4 * t11) >> TERM_BITS)
                        + c[19] * ((t5 * t12) >> TERM_BITS) + c[20] * ((t6 * t13) >> TERM_BITS)
                        + c[21] * ((t7 * t14) >> TERM_BITS);
            }
        }
        return reconstruct(sum);
    }

    /**
     * Evaluates a polynomial at a clamped sample.
     *
     * @param coefficients the coefficients in increasing powers
     * @param sample the base layer code, clamped to the pivots
     * @return the fixed-point value
     */
    private static long polynomial(long[] coefficients, int sample) {
        long power = 1;
        var shift = TERM_BITS;
        long sum = 0;
        for (var coefficient : coefficients) {
            sum += coefficient * (power << shift);
            power *= sample;
            shift -= BASE_DEPTH;
        }
        return sum;
    }

    /**
     * Reduces a fixed-point mapped value to the 16-bit mapped sample and rounds that to the reconstructed depth.
     *
     * @param value the value with {@code denominatorBits + 20} fractional bits
     * @return the reconstructed sample, unsigned
     */
    private int reconstruct(long value) {
        var mapped = Math.min(Math.max(value, 0) >> (4 + denominatorBits), 0xFFFF);
        var rounded = (mapped + (1L << (15 - depth))) >> (16 - depth);
        return (int) Math.min(rounded, (1L << depth) - 1);
    }

    /**
     * Finds the piece of a curve that maps a code. A code at or above the last pivot takes the last piece, since the
     * index the clause derives for it names no coefficients.
     *
     * @param pivots the pivot values
     * @param code the base layer code
     * @return the index of the piece
     */
    private static int pieceIndex(int[] pivots, int code) {
        for (var index = 0; index < pivots.length - 1; index++) {
            if (code < pivots[index + 1]) {
                return index;
            }
        }
        return pivots.length - 2;
    }
}
