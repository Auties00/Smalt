package com.github.auties00.smalt.video.av1.internal.encoder;

import com.github.auties00.smalt.video.av1.internal.Cdf;
import com.github.auties00.smalt.video.av1.internal.Scans;
import com.github.auties00.smalt.video.av1.internal.Tables;

import java.util.Arrays;

/**
 * Writes the coefficients of AV1 transform blocks (AV1 specification section 5.11.39,
 * {@code coeffs()}), the inverse of the decoder's coefficient reader.
 *
 * <p>Contexts are derived from the same above and left context bytes and the same running level
 * map the decoder keeps, filled in the same order, so every symbol is coded with the CDF the
 * decoder reads it with. Transform types of every class are supported: the two-dimensional ones
 * (the lossless Walsh-Hadamard transform included) and the horizontal and vertical identity
 * classes.
 *
 * <p>An instance holds scratch buffers and is used by one thread at a time.
 *
 * @implNote This implementation follows libaom's {@code av1_write_coeffs_txb}
 * ({@code av1/encoder/encodetxb.c}) for the syntax and mirrors the decoder's context
 * derivations (dav1d's {@code decode_coefs}) for the context values.
 */
final class CoefWriter {
    /**
     * The identity transform type.
     */
    private static final int IDTX = 9;

    /**
     * The two-dimensional transform class.
     */
    static final int TX_CLASS_2D = 0;

    /**
     * The horizontal transform class.
     */
    static final int TX_CLASS_H = 1;

    /**
     * The vertical transform class.
     */
    static final int TX_CLASS_V = 2;

    /**
     * The scans of the vertical class by transform size.
     */
    private static final int[][] SCANS_V = new int[19][];

    /**
     * The scans of the horizontal class by transform size.
     */
    private static final int[][] SCANS_H = new int[19][];

    /**
     * The inverse scans of each class ({@link #TX_CLASS_2D}, {@link #TX_CLASS_H},
     * {@link #TX_CLASS_V}) by transform size.
     */
    private static final int[][][] INVERSE_SCANS = new int[3][19][];

    static {
        for (var tx = 0; tx < 19; tx++) {
            SCANS_V[tx] = buildScan(tx, TX_CLASS_V);
            SCANS_H[tx] = buildScan(tx, TX_CLASS_H);
            INVERSE_SCANS[TX_CLASS_2D][tx] = inverseScan(Scans.SCANS[tx]);
            INVERSE_SCANS[TX_CLASS_H][tx] = inverseScan(SCANS_H[tx]);
            INVERSE_SCANS[TX_CLASS_V][tx] = inverseScan(SCANS_V[tx]);
        }
    }

    /**
     * The running level map of the block being written.
     */
    private final byte[] levels = new byte[32 * 34];

    /**
     * Creates a writer with its scratch buffers.
     */
    CoefWriter() {
    }

    /**
     * Returns the all-zero flag context of a transform block ({@code get_skip_ctx}).
     *
     * @param tx the transform size
     * @param bs the block size
     * @param a the above coefficient context
     * @param aOff the offset of the block in {@code a}
     * @param l the left coefficient context
     * @param lOff the offset of the block in {@code l}
     * @param chroma whether the block is a chroma block of a 4:2:0 picture
     * @return the context
     */
    static int skipCtx(int tx, int bs, byte[] a, int aOff, byte[] l, int lOff, boolean chroma) {
        return skipCtx(tx, bs, a, aOff, l, lOff, chroma, 1, 1);
    }

    /**
     * Returns the all-zero flag context of a transform block of any chroma subsampling
     * ({@code get_skip_ctx}).
     *
     * @param tx the transform size
     * @param bs the block size
     * @param a the above coefficient context
     * @param aOff the offset of the block in {@code a}
     * @param l the left coefficient context
     * @param lOff the offset of the block in {@code l}
     * @param chroma whether the block is a chroma block
     * @param ssHor 1 when chroma is subsampled horizontally
     * @param ssVer 1 when chroma is subsampled vertically
     * @return the context
     */
    static int skipCtx(int tx, int bs, byte[] a, int aOff, byte[] l, int lOff, boolean chroma, int ssHor,
                       int ssVer) {
        var lw = Tables.TX_LW[tx];
        var lh = Tables.TX_LH[tx];
        var bwl = Tables.BLOCK_DIMENSIONS[bs * 4 + 2];
        var bhl = Tables.BLOCK_DIMENSIONS[bs * 4 + 3];
        if (chroma) {
            var notOneBlk = bwl - (bwl != 0 ? ssHor : 0) > lw || bhl - (bhl != 0 ? ssVer : 0) > lh ? 1 : 0;
            var ca = 0;
            for (int i = 0, n = 1 << lw; i < n; i++) {
                if (a[aOff + i] != 0x40) {
                    ca = 1;
                    break;
                }
            }
            var cl = 0;
            for (int i = 0, n = 1 << lh; i < n; i++) {
                if (l[lOff + i] != 0x40) {
                    cl = 1;
                    break;
                }
            }
            return 7 + notOneBlk * 3 + ca + cl;
        }
        if (bwl == lw && bhl == lh) {
            return 0;
        }
        var la = 0;
        for (int i = 0, n = 1 << lw; i < n; i++) {
            la |= a[aOff + i];
        }
        var ll = 0;
        for (int i = 0, n = 1 << lh; i < n; i++) {
            ll |= l[lOff + i];
        }
        return Tables.SKIP_CTX[Math.min(la & 0x3F, 4) * 5 + Math.min(ll & 0x3F, 4)];
    }

    /**
     * Returns the DC sign context of a transform block ({@code get_dc_sign_ctx}).
     *
     * @param tx the transform size
     * @param a the above coefficient context
     * @param aOff the offset of the block in {@code a}
     * @param l the left coefficient context
     * @param lOff the offset of the block in {@code l}
     * @return the context
     */
    static int dcSignCtx(int tx, byte[] a, int aOff, byte[] l, int lOff) {
        var na = 1 << Tables.TX_LW[tx];
        var nl = 1 << Tables.TX_LH[tx];
        var s = -na - nl;
        for (var i = 0; i < na; i++) {
            s += (a[aOff + i] >> 6) & 3;
        }
        for (var i = 0; i < nl; i++) {
            s += (l[lOff + i] >> 6) & 3;
        }
        return (s != 0 ? 1 : 0) + (s > 0 ? 1 : 0);
    }

    /**
     * Returns the index of a transform type within the set read from a CDF.
     *
     * @param first the first entry of the set in {@link Tables#TX_TYPES_PER_SET}
     * @param count the number of types in the set
     * @param txtp the transform type
     * @return the index
     * @throws IllegalArgumentException if the set does not hold the type
     */
    private static int setIndex(int first, int count, int txtp) {
        for (var i = 0; i < count; i++) {
            if (Tables.TX_TYPES_PER_SET[first + i] == txtp) {
                return i;
            }
        }
        throw new IllegalArgumentException("transform type " + txtp + " is not in the set");
    }

    /**
     * Writes the transform type of a luma transform block when the syntax codes it.
     *
     * @param ec the entropy coder
     * @param cdf the CDFs
     * @param tx the transform size
     * @param intra whether the block is intra coded
     * @param yMode the luma intra mode of an intra block
     * @param reduced whether the frame uses the reduced transform sets
     * @param qidx the quantizer index of the block's segment
     * @param txtp the transform type
     */
    private static void writeTxType(RangeEncoder ec, char[] cdf, int tx, boolean intra, int yMode, boolean reduced,
                                    int qidx, int txtp) {
        var txMin = Tables.TX_MIN[tx];
        var txMax = Tables.TX_MAX[tx];
        if (txMax + (intra ? 1 : 0) >= 4 || qidx == 0) {
            return;
        }
        if (intra) {
            if (reduced || txMin == 2) {
                ec.encodeSymbol(cdf, Cdf.TXTP_INTRA2 + (txMin * 13 + yMode) * 8, setIndex(0, 5, txtp), 4);
            } else {
                ec.encodeSymbol(cdf, Cdf.TXTP_INTRA1 + (txMin * 13 + yMode) * 8, setIndex(5, 7, txtp), 6);
            }
        } else if (reduced || txMax == 3) {
            ec.encodeBool(cdf, Cdf.TXTP_INTER3 + txMin * 2, txtp == IDTX ? 0 : 1);
        } else if (txMin == 2) {
            ec.encodeSymbol(cdf, Cdf.TXTP_INTER2, setIndex(12, 12, txtp), 11);
        } else {
            ec.encodeSymbol(cdf, Cdf.TXTP_INTER1 + txMin * 16, setIndex(24, 16, txtp), 15);
        }
    }

    /**
     * Writes the base-range part of a level above two ({@code decode_hi_tok}'s inverse).
     *
     * @param ec the entropy coder
     * @param cdf the CDFs
     * @param off the offset of the context's CDF
     * @param tok the level, capped at 15, at least 3
     */
    private static void writeHiTok(RangeEncoder ec, char[] cdf, int off, int tok) {
        var br = tok - 3;
        for (var k = 0; k < 4; k++) {
            var s = Math.min(br - 3 * k, 3);
            ec.encodeSymbol(cdf, off, s, 3);
            if (s < 3) {
                return;
            }
        }
    }

    /**
     * Returns the base level context of a coefficient and the magnitude of its nearest neighbors
     * ({@code get_lo_ctx} of the two-dimensional class).
     *
     * @param levels the level map
     * @param p the coefficient's position
     * @param ctxOffsets the offset of the size's context table
     * @param x the coefficient's column
     * @param y the coefficient's row
     * @param stride the map stride
     * @return the context in the low 16 bits and the near magnitude above
     */
    static int loCtx(byte[] levels, int p, int ctxOffsets, int x, int y, int stride) {
        var mag = (levels[p + 1] & 0xFF) + (levels[p + stride] & 0xFF);
        mag += levels[p + stride + 1] & 0xFF;
        var hiMag = mag;
        mag += (levels[p + 2] & 0xFF) + (levels[p + 2 * stride] & 0xFF);
        var offset = Tables.LO_CTX_OFFSETS[ctxOffsets + Math.min(y, 4) * 5 + Math.min(x, 4)];
        return (offset + (mag > 512 ? 4 : (mag + 64) >> 7)) | hiMag << 16;
    }

    /**
     * Returns the level map byte of a level.
     *
     * @param tok the level, capped at 15
     * @return the byte
     */
    static byte levelByte(int tok) {
        return (byte) (tok >= 3 ? tok + (3 << 6) : tok * 0x41);
    }

    /**
     * Writes one transform block of a 4:2:0 picture and returns the context byte the decoder
     * stores for it.
     *
     * @param ec the entropy coder
     * @param cdf the CDFs
     * @param a the above coefficient context
     * @param aOff the offset of the block in {@code a}
     * @param l the left coefficient context
     * @param lOff the offset of the block in {@code l}
     * @param tx the transform size
     * @param bs the block size
     * @param plane the plane: 0 luma, 1 or 2 chroma
     * @param intra whether the block is intra coded
     * @param yMode the luma intra mode of an intra block
     * @param reduced whether the frame uses the reduced transform sets
     * @param qidx the quantizer index of the block's segment
     * @param txtp the transform type
     * @param qcoeff the signed levels, in the decoder's layout
     * @param qOff the offset of the block's levels in {@code qcoeff}
     * @param eob the number of coefficients up to the last non-zero one in scan order
     * @return the context byte: the capped level sum and the DC sign
     */
    int write(RangeEncoder ec, char[] cdf, byte[] a, int aOff, byte[] l, int lOff, int tx, int bs, int plane,
                     boolean intra, int yMode, boolean reduced, int qidx, int txtp, int[] qcoeff, int qOff, int eob) {
        return write(ec, cdf, a, aOff, l, lOff, tx, bs, plane, intra, yMode, reduced, qidx, txtp, qcoeff, qOff, eob,
                1, 1);
    }

    /**
     * Writes one transform block and returns the context byte the decoder stores for it.
     *
     * <p>The levels of a two-dimensional class block follow {@link Scans#SCANS}; those of a
     * horizontal or vertical class block follow {@link #scan(int, int)}.
     *
     * @param ec the entropy coder
     * @param cdf the CDFs
     * @param a the above coefficient context
     * @param aOff the offset of the block in {@code a}
     * @param l the left coefficient context
     * @param lOff the offset of the block in {@code l}
     * @param tx the transform size
     * @param bs the block size
     * @param plane the plane: 0 luma, 1 or 2 chroma
     * @param intra whether the block is intra coded
     * @param yMode the luma intra mode of an intra block, filter intra mapped to its equivalent
     * @param reduced whether the frame uses the reduced transform sets
     * @param qidx the quantizer index of the block's segment
     * @param txtp the transform type
     * @param qcoeff the signed levels, in the decoder's layout
     * @param qOff the offset of the block's levels in {@code qcoeff}
     * @param eob the number of coefficients up to the last non-zero one in scan order
     * @param ssHor 1 when chroma is subsampled horizontally
     * @param ssVer 1 when chroma is subsampled vertically
     * @return the context byte: the capped level sum and the DC sign
     */
    int write(RangeEncoder ec, char[] cdf, byte[] a, int aOff, byte[] l, int lOff, int tx, int bs, int plane,
                     boolean intra, int yMode, boolean reduced, int qidx, int txtp, int[] qcoeff, int qOff, int eob,
                     int ssHor, int ssVer) {
        var chroma = plane != 0 ? 1 : 0;
        var tctx = Tables.TX_CTX[tx];
        var sctx = skipCtx(tx, bs, a, aOff, l, lOff, chroma != 0, ssHor, ssVer);
        ec.encodeBool(cdf, Cdf.COEF_SKIP + (tctx * 13 + sctx) * 2, eob == 0 ? 1 : 0);
        if (eob == 0) {
            return 0x40;
        }
        if (chroma == 0) {
            writeTxType(ec, cdf, tx, intra, yMode, reduced, qidx, txtp);
        }
        var slw = Math.min(Tables.TX_LW[tx], 3);
        var slh = Math.min(Tables.TX_LH[tx], 3);
        var tx2dszctx = slw + slh;
        var txClass = Tables.TX_TYPE_CLASS[txtp];
        var is1d = txClass != TX_CLASS_2D ? 1 : 0;
        var eobCdf = switch (tx2dszctx) {
            case 0 -> Cdf.COEF_EOB_BIN_16 + (chroma * 2 + is1d) * 8;
            case 1 -> Cdf.COEF_EOB_BIN_32 + (chroma * 2 + is1d) * 8;
            case 2 -> Cdf.COEF_EOB_BIN_64 + (chroma * 2 + is1d) * 8;
            case 3 -> Cdf.COEF_EOB_BIN_128 + (chroma * 2 + is1d) * 8;
            case 4 -> Cdf.COEF_EOB_BIN_256 + (chroma * 2 + is1d) * 16;
            case 5 -> Cdf.COEF_EOB_BIN_512 + chroma * 16;
            default -> Cdf.COEF_EOB_BIN_1024 + chroma * 16;
        };
        var last = eob - 1;
        if (last < 2) {
            ec.encodeSymbol(cdf, eobCdf, last, 4 + tx2dszctx);
        } else {
            var n = 31 - Integer.numberOfLeadingZeros(last);
            ec.encodeSymbol(cdf, eobCdf, n + 1, 4 + tx2dszctx);
            var eobBin = n - 1;
            ec.encodeBool(cdf, Cdf.COEF_EOB_HI_BIT + ((tctx * 2 + chroma) * 9 + eobBin) * 2, (last >> eobBin) & 1);
            ec.encodeBits(last & ((1 << eobBin) - 1), eobBin);
        }
        var eobBaseCdf = Cdf.COEF_EOB_BASE_TOK + (tctx * 2 + chroma) * 16;
        var hiCdf = Cdf.COEF_BR_TOK + (Math.min(tctx, 3) * 2 + chroma) * 21 * 4;
        var scan = txClass == TX_CLASS_2D ? Scans.SCANS[tx] : scan(tx, txClass);
        int dcTok;
        if (last != 0) {
            var loCdf = Cdf.COEF_BASE_TOK + (tctx * 2 + chroma) * 41 * 4;
            var ctx = 1 + (last > (2 << tx2dszctx) ? 1 : 0) + (last > (4 << tx2dszctx) ? 1 : 0);
            if (txClass == TX_CLASS_2D) {
                dcTok = tokens2d(ec, cdf, tx, slw, slh, last, scan, qcoeff, qOff, eobBaseCdf + ctx * 4, loCdf, hiCdf);
            } else {
                dcTok = tokens1d(ec, cdf, slw, slh, last, txClass == TX_CLASS_V, qcoeff, qOff, eobBaseCdf + ctx * 4,
                        loCdf, hiCdf);
            }
        } else {
            dcTok = Math.min(Math.abs(qcoeff[qOff]), 15);
            ec.encodeSymbol(cdf, eobBaseCdf, Math.min(dcTok, 3) - 1, 2);
            if (dcTok >= 3) {
                writeHiTok(ec, cdf, hiCdf, dcTok);
            }
        }
        var culLevel = 0;
        int dcSignLevel;
        if (dcTok == 0) {
            dcSignLevel = 1 << 6;
        } else {
            var dc = qcoeff[qOff];
            var sign = dc < 0 ? 1 : 0;
            ec.encodeBool(cdf, Cdf.COEF_DC_SIGN + ((plane != 0 ? 3 : 0) + dcSignCtx(tx, a, aOff, l, lOff)) * 2, sign);
            if (dcTok == 15) {
                ec.encodeGolomb(Math.abs(dc) - 15);
            }
            culLevel = Math.abs(dc);
            dcSignLevel = (sign - 1) & (2 << 6);
        }
        for (var i = 1; i <= last; i++) {
            var v = qcoeff[qOff + scan[i]];
            if (v == 0) {
                continue;
            }
            var abs = Math.abs(v);
            ec.encodeBoolEqui(v < 0 ? 1 : 0);
            if (abs >= 15) {
                ec.encodeGolomb(abs - 15);
            }
            culLevel += abs;
        }
        return Integer.compareUnsigned(culLevel, 63) < 0 ? culLevel | dcSignLevel : 63 | dcSignLevel;
    }

    /**
     * Writes the base and range tokens of a two-dimensional class block, from the last
     * coefficient to the DC, and returns the DC token.
     *
     * @param ec the entropy coder
     * @param cdf the CDFs
     * @param tx the transform size
     * @param slw the base-2 logarithm of the coded width in 4-sample units
     * @param slh the base-2 logarithm of the coded height in 4-sample units
     * @param last the scan position of the last non-zero coefficient, at least 1
     * @param scan the scan order
     * @param qcoeff the signed levels
     * @param qOff the offset of the block's levels
     * @param eobBaseCdf the CDF of the last coefficient's token
     * @param loCdf the offset of the base token CDFs
     * @param hiCdf the offset of the range token CDFs
     * @return the DC token, capped at 15
     */
    private int tokens2d(RangeEncoder ec, char[] cdf, int tx, int slw, int slh, int last, int[] scan, int[] qcoeff,
                         int qOff, int eobBaseCdf, int loCdf, int hiCdf) {
        var nonsquare = tx >= 5 ? 1 : 0;
        var ctxOffsets = (nonsquare + (tx & nonsquare)) * 25;
        var stride = 4 << slh;
        var shift = slh + 2;
        var mask = (4 << slh) - 1;
        var lv = levels;
        Arrays.fill(lv, 0, stride * ((4 << slw) + 2), (byte) 0);
        var rc = scan[last];
        var x = rc >> shift;
        var y = rc & mask;
        var tok = Math.min(Math.abs(qcoeff[qOff + rc]), 15);
        ec.encodeSymbol(cdf, eobBaseCdf, Math.min(tok, 3) - 1, 2);
        if (tok >= 3) {
            writeHiTok(ec, cdf, hiCdf + ((x | y) > 1 ? 14 : 7) * 4, tok);
        }
        lv[rc] = levelByte(tok);
        for (var i = last - 1; i > 0; i--) {
            var rcI = scan[i];
            x = rcI >> shift;
            y = rcI & mask;
            var lc = loCtx(lv, rcI, ctxOffsets, x, y, stride);
            tok = Math.min(Math.abs(qcoeff[qOff + rcI]), 15);
            var sym = Math.min(tok, 3);
            ec.encodeSymbol(cdf, loCdf + (lc & 0xFFFF) * 4, sym, 3);
            if (sym == 3) {
                var mag = (lc >>> 16) & 63;
                var hctx = ((x | y) > 1 ? 14 : 7) + (mag > 12 ? 6 : (mag + 1) >> 1);
                writeHiTok(ec, cdf, hiCdf + hctx * 4, tok);
            }
            lv[rcI] = levelByte(tok);
        }
        var dcTok = Math.min(Math.abs(qcoeff[qOff]), 15);
        var dsym = Math.min(dcTok, 3);
        ec.encodeSymbol(cdf, loCdf, dsym, 3);
        if (dsym == 3) {
            var mag = ((lv[1] & 0xFF) + (lv[stride] & 0xFF) + (lv[stride + 1] & 0xFF)) & 63;
            writeHiTok(ec, cdf, hiCdf + (mag > 12 ? 6 : (mag + 1) >> 1) * 4, dcTok);
        }
        return dcTok;
    }

    /**
     * Writes the base and range tokens of a horizontal or vertical class block, from the last
     * coefficient to the DC, and returns the DC token, the inverse of the decoder's
     * one-dimensional token reader.
     *
     * @param ec the entropy coder
     * @param cdf the CDFs
     * @param slw the base-2 logarithm of the coded width in 4-sample units
     * @param slh the base-2 logarithm of the coded height in 4-sample units
     * @param last the scan position of the last non-zero coefficient, at least 1
     * @param vertical whether the class is vertical
     * @param qcoeff the signed levels
     * @param qOff the offset of the block's levels
     * @param eobBaseCdf the CDF of the last coefficient's token
     * @param loCdf the offset of the base token CDFs
     * @param hiCdf the offset of the range token CDFs
     * @return the DC token, capped at 15
     */
    private int tokens1d(RangeEncoder ec, char[] cdf, int slw, int slh, int last, boolean vertical, int[] qcoeff,
                         int qOff, int eobBaseCdf, int loCdf, int hiCdf) {
        var stride = 16;
        var shift = vertical ? slw + 2 : slh + 2;
        var shift2 = vertical ? slh + 2 : 0;
        var mask = vertical ? (4 << slw) - 1 : (4 << slh) - 1;
        var lv = levels;
        Arrays.fill(lv, 0, stride * ((4 << (vertical ? slw : slh)) + 2), (byte) 0);
        var x = last & mask;
        var y = last >> shift;
        var rc = vertical ? (x << shift2) | y : last;
        var tok = Math.min(Math.abs(qcoeff[qOff + rc]), 15);
        ec.encodeSymbol(cdf, eobBaseCdf, Math.min(tok, 3) - 1, 2);
        if (tok >= 3) {
            writeHiTok(ec, cdf, hiCdf + (y != 0 ? 14 : 7) * 4, tok);
        }
        lv[x * stride + y] = levelByte(tok);
        for (var i = last - 1; i > 0; i--) {
            x = i & mask;
            y = i >> shift;
            var rcI = vertical ? (x << shift2) | y : i;
            var level = x * stride + y;
            var lc = loCtx1d(lv, level, y, stride);
            tok = Math.min(Math.abs(qcoeff[qOff + rcI]), 15);
            var sym = Math.min(tok, 3);
            ec.encodeSymbol(cdf, loCdf + (lc & 0xFFFF) * 4, sym, 3);
            if (sym == 3) {
                var mag = (lc >>> 16) & 63;
                var hctx = (y > 0 ? 14 : 7) + (mag > 12 ? 6 : (mag + 1) >> 1);
                writeHiTok(ec, cdf, hiCdf + hctx * 4, tok);
            }
            lv[level] = levelByte(tok);
        }
        var lc = loCtx1d(lv, 0, 0, stride);
        var dcTok = Math.min(Math.abs(qcoeff[qOff]), 15);
        var dsym = Math.min(dcTok, 3);
        ec.encodeSymbol(cdf, loCdf + (lc & 0xFFFF) * 4, dsym, 3);
        if (dsym == 3) {
            var mag = (lc >>> 16) & 63;
            writeHiTok(ec, cdf, hiCdf + (mag > 12 ? 6 : (mag + 1) >> 1) * 4, dcTok);
        }
        return dcTok;
    }

    /**
     * Returns the base level context of a coefficient of a one-dimensional class block and the
     * magnitude of its nearest neighbors ({@code get_lo_ctx} of the one-dimensional classes).
     *
     * @param levels the level map
     * @param p the coefficient's position in the map
     * @param y the coefficient's position along the transformed direction
     * @param stride the map stride
     * @return the context in the low 16 bits and the near magnitude above
     */
    private static int loCtx1d(byte[] levels, int p, int y, int stride) {
        var mag = (levels[p + 1] & 0xFF) + (levels[p + stride] & 0xFF);
        mag += levels[p + 2] & 0xFF;
        var hiMag = mag;
        mag += (levels[p + 3] & 0xFF) + (levels[p + 4] & 0xFF);
        var offset = 26 + (y > 1 ? 10 : y * 5);
        return (offset + (mag > 512 ? 4 : (mag + 64) >> 7)) | hiMag << 16;
    }

    /**
     * Returns the scan order of a one-dimensional class: the decoder-layout position of each
     * scan position, rows first for the vertical class and columns first for the horizontal one.
     *
     * @param tx the transform size, at most 16 samples per side
     * @param txClass {@link #TX_CLASS_H} or {@link #TX_CLASS_V}
     * @return the scan, shared and read-only
     */
    static int[] scan(int tx, int txClass) {
        return (txClass == TX_CLASS_V ? SCANS_V : SCANS_H)[tx];
    }

    /**
     * Returns the inverse scan of a transform size and class: the scan position plus one of each
     * coefficient, by raster index.
     *
     * @param tx the transform size
     * @param txClass {@link #TX_CLASS_2D}, {@link #TX_CLASS_H} or {@link #TX_CLASS_V}
     * @return the inverse scan, shared and read-only
     */
    static int[] inverseScan(int tx, int txClass) {
        return INVERSE_SCANS[txClass][tx];
    }

    /**
     * Returns the inverse of a scan: the scan position plus one of each coefficient, by raster
     * index.
     *
     * @param scan the scan, holding the raster index of each scan position
     * @return a new inverse scan
     */
    static int[] inverseScan(int[] scan) {
        var out = new int[scan.length];
        for (var i = 0; i < scan.length; i++) {
            out[scan[i]] = i + 1;
        }
        return out;
    }

    /**
     * Builds the scan order of a one-dimensional class.
     *
     * @param tx the transform size
     * @param txClass {@link #TX_CLASS_H} or {@link #TX_CLASS_V}
     * @return the scan
     */
    private static int[] buildScan(int tx, int txClass) {
        var slw = Math.min(Tables.TX_LW[tx], 3);
        var slh = Math.min(Tables.TX_LH[tx], 3);
        var n = (4 << slw) * (4 << slh);
        var s = new int[n];
        var shift = slw + 2;
        var shift2 = slh + 2;
        var mask = (4 << slw) - 1;
        for (var i = 0; i < n; i++) {
            s[i] = txClass == TX_CLASS_V ? ((i & mask) << shift2) | (i >> shift) : i;
        }
        return s;
    }
}
