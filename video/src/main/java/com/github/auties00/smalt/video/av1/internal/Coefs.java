package com.github.auties00.smalt.video.av1.internal;

import java.util.Arrays;

/**
 * Decodes the coefficients of one transform block: the all-zero flag, the transform type, the
 * end-of-block position, the base and range tokens, the signs, Golomb remainders and the
 * dequantization (AV1 specification sections 5.11.39 and 7.12.3).
 *
 * <p>Coefficients are stored in dav1d's transposed layout, which {@link ItxDsp} consumes. During
 * token decoding each non-zero coefficient temporarily holds its token in bits 11 and up and the
 * position of the previous non-zero coefficient in its low bits, forming the list the sign and
 * dequantization pass walks.
 *
 * @implNote This implementation ports dav1d's {@code decode_coefs} with {@code get_skip_ctx},
 * {@code get_dc_sign_ctx}, {@code get_lo_ctx} and {@code read_golomb}
 * ({@code src/recon_tmpl.c}).
 */
final class Coefs {
    /**
     * The 2-D transform class.
     */
    private static final int TX_CLASS_2D = 0;

    /**
     * The horizontal one-dimensional transform class.
     */
    private static final int TX_CLASS_H = 1;


    /**
     * The identity transform type.
     */
    private static final int IDTX = 9;

    /**
     * Prevents instantiation.
     */
    private Coefs() {
        throw new AssertionError();
    }

    /**
     * Reads an Exp-Golomb coded value ({@code read_golomb}).
     *
     * @param msac the decoder
     * @return the value
     */
    private static int readGolomb(Msac msac) {
        var len = 0;
        var val = 1;
        while (msac.decodeBoolEqui() == 0 && len < 32) {
            len++;
        }
        while (len-- > 0) {
            val = (val << 1) + msac.decodeBoolEqui();
        }
        return val - 1;
    }

    /**
     * Returns the all-zero flag context of a transform block ({@code get_skip_ctx}).
     *
     * @param tx the transform size
     * @param bs the block size
     * @param a the above coefficient contexts
     * @param aOff the first above entry
     * @param l the left coefficient contexts
     * @param lOff the first left entry
     * @param chroma whether the block is chroma
     * @param layout the pixel layout
     * @return the context
     */
    private static int skipCtx(int tx, int bs, byte[] a, int aOff, byte[] l, int lOff, boolean chroma, int layout) {
        var lw = Tables.TX_LW[tx];
        var lh = Tables.TX_LH[tx];
        var bwl = Tables.BLOCK_DIMENSIONS[bs * 4 + 2];
        var bhl = Tables.BLOCK_DIMENSIONS[bs * 4 + 3];
        if (chroma) {
            var ssVer = layout == SequenceHeader.LAYOUT_I420 ? 1 : 0;
            var ssHor = layout != SequenceHeader.LAYOUT_I444 ? 1 : 0;
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
     * @param a the above coefficient contexts
     * @param aOff the first above entry
     * @param l the left coefficient contexts
     * @param lOff the first left entry
     * @return 0 for balanced neighbors, 1 for mostly negative, 2 for mostly positive
     */
    private static int dcSignCtx(int tx, byte[] a, int aOff, byte[] l, int lOff) {
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
     * Decodes the coefficients of one transform block ({@code decode_coefs}).
     *
     * <p>On return {@link TaskContext#txtp} holds the transform type and {@link TaskContext#cfCtx}
     * the context byte for the neighbors. For chroma blocks of inter blocks, {@link TaskContext#txtp}
     * must hold the co-located luma transform type on entry. When the task context has a
     * {@link TaskContext#coefSource}, the source supplies the block instead of the bitstream. In
     * the parsing pass of two-pass decoding ({@link TaskContext#PASS_PARSE}) the coefficients are
     * appended to the task context's block records ({@link BlockRecords#fetchCoefs}) and
     * {@code cf} stays all zero.
     *
     * @param t the task context
     * @param a the above coefficient contexts
     * @param aOff the first above entry
     * @param l the left coefficient contexts
     * @param lOff the first left entry
     * @param tx the transform size
     * @param bs the block size
     * @param b the block
     * @param intra whether the block is intra
     * @param plane the plane, 0 for luma
     * @param cf the coefficient buffer, all zero on entry
     * @return the end-of-block position, or {@code -1} when every coefficient is zero
     */
    static int decode(TaskContext t, byte[] a, int aOff, byte[] l, int lOff, int tx, int bs, Av1Block b,
                      boolean intra, int plane, int[] cf) {
        var source = t.coefSource;
        if (source != null) {
            return source.code(t, a, aOff, l, lOff, tx, bs, b, intra, plane, cf);
        }
        var ts = t.ts;
        var f = t.f;
        var hdr = f.frameHdr;
        var msac = ts.msac;
        var cdf = ts.cdf;
        var chroma = plane != 0 ? 1 : 0;
        var lossless = hdr.segLossless[b.segId];
        var tctx = Tables.TX_CTX[tx];
        var sctx = skipCtx(tx, bs, a, aOff, l, lOff, chroma != 0, f.cur.layout);
        if (msac.decodeBoolAdapt(cdf, Cdf.COEF_SKIP + (tctx * 13 + sctx) * 2) != 0) {
            t.cfCtx = 0x40;
            t.txtp = lossless ? ItxDsp.WHT_WHT : 0;
            if (t.pass == TaskContext.PASS_PARSE) {
                BlockRecords.put(t, 0);
            }
            return -1;
        }
        var txMin = Tables.TX_MIN[tx];
        var txMax = Tables.TX_MAX[tx];
        int txtp;
        if (lossless) {
            txtp = ItxDsp.WHT_WHT;
        } else if (txMax + (intra ? 1 : 0) >= 4) {
            txtp = 0;
        } else if (chroma != 0) {
            txtp = intra ? Tables.TXTP_FROM_UVMODE[b.uvMode] : uvInterTxtp(tx, t.txtp);
        } else if (hdr.segQidx[b.segId] == 0) {
            txtp = 0;
        } else if (intra) {
            var yModeNofilt = b.yMode == IpredDsp.FILTER_PRED ? Tables.FILTER_MODE_TO_Y_MODE[b.yAngle] : b.yMode;
            if (hdr.reducedTxtpSet || txMin == 2) {
                var idx = msac.decodeSymbolAdapt(cdf, Cdf.TXTP_INTRA2 + (txMin * 13 + yModeNofilt) * 8, 4);
                txtp = Tables.TX_TYPES_PER_SET[idx];
            } else {
                var idx = msac.decodeSymbolAdapt(cdf, Cdf.TXTP_INTRA1 + (txMin * 13 + yModeNofilt) * 8, 6);
                txtp = Tables.TX_TYPES_PER_SET[idx + 5];
            }
        } else if (hdr.reducedTxtpSet || txMax == 3) {
            var idx = msac.decodeBoolAdapt(cdf, Cdf.TXTP_INTER3 + txMin * 2);
            txtp = (idx - 1) & IDTX;
        } else if (txMin == 2) {
            var idx = msac.decodeSymbolAdapt(cdf, Cdf.TXTP_INTER2, 11);
            txtp = Tables.TX_TYPES_PER_SET[idx + 12];
        } else {
            var idx = msac.decodeSymbolAdapt(cdf, Cdf.TXTP_INTER1 + txMin * 16, 15);
            txtp = Tables.TX_TYPES_PER_SET[idx + 24];
        }
        t.txtp = txtp;
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
        var eob = msac.decodeSymbolAdapt(cdf, eobCdf, 4 + tx2dszctx);
        if (eob > 1) {
            var eobBin = eob - 2;
            var hiBit = msac.decodeBoolAdapt(cdf, Cdf.COEF_EOB_HI_BIT + ((tctx * 2 + chroma) * 9 + eobBin) * 2);
            eob = ((hiBit | 2) << eobBin) | msac.decodeBools(eobBin);
        }
        var eobBaseCdf = Cdf.COEF_EOB_BASE_TOK + (tctx * 2 + chroma) * 16;
        var hiCdf = Cdf.COEF_BR_TOK + (Math.min(tctx, 3) * 2 + chroma) * 21 * 4;
        int rc;
        int dcTok;
        if (eob != 0) {
            var loCdf = Cdf.COEF_BASE_TOK + (tctx * 2 + chroma) * 41 * 4;
            var ctx = 1 + (eob > (2 << tx2dszctx) ? 1 : 0) + (eob > (4 << tx2dszctx) ? 1 : 0);
            var eobTok = msac.decodeSymbolAdapt(cdf, eobBaseCdf + ctx * 4, 2);
            var packed = switch (txClass) {
                case TX_CLASS_2D -> tokens2d(t, cf, tx, slw, slh, eob, eobTok, loCdf, hiCdf);
                case TX_CLASS_H -> tokens1d(t, cf, slw, slh, eob, eobTok, loCdf, hiCdf, false);
                default -> tokens1d(t, cf, slw, slh, eob, eobTok, loCdf, hiCdf, true);
            };
            rc = packed & 0xFFFF;
            dcTok = packed >>> 16;
        } else {
            var tokBr = msac.decodeSymbolAdapt(cdf, eobBaseCdf, 2);
            dcTok = 1 + tokBr;
            if (tokBr == 2) {
                dcTok = msac.decodeHiTok(cdf, hiCdf);
            }
            rc = 0;
        }
        if (t.pass == TaskContext.PASS_PARSE) {
            dequantRecorded(t, a, aOff, l, lOff, tx, b, plane, cf, rc, dcTok, txtp, eob);
        } else {
            dequant(t, a, aOff, l, lOff, tx, b, plane, cf, rc, dcTok, txtp);
        }
        return eob;
    }

    /**
     * Returns the chroma transform type of an inter block ({@code get_uv_inter_txtp}).
     *
     * @param uvtx the chroma transform size
     * @param ytxtp the co-located luma transform type
     * @return the chroma transform type
     */
    private static int uvInterTxtp(int uvtx, int ytxtp) {
        if (Tables.TX_MAX[uvtx] == 3) {
            return ytxtp == IDTX ? IDTX : 0;
        }
        if (Tables.TX_MIN[uvtx] == 2 && ((1 << ytxtp) & ((1 << 15) | (1 << 14) | (1 << 13) | (1 << 12))) != 0) {
            return 0;
        }
        return ytxtp;
    }

    /**
     * Returns the base level context of a coefficient ({@code get_lo_ctx}), and its partial magnitude
     * in the upper 16 bits.
     *
     * @param levels the decoded levels
     * @param p the coefficient's level index
     * @param twoD whether the transform class is 2-D
     * @param ctxOffsets the offset of the 2-D context offsets table, or {@code -1}
     * @param x the coefficient's first coordinate
     * @param y the coefficient's second coordinate
     * @param stride the level stride
     * @return the context in the low 16 bits, the magnitude used by range contexts in the high bits
     */
    private static int loCtx(byte[] levels, int p, boolean twoD, int ctxOffsets, int x, int y, int stride) {
        var mag = (levels[p + 1] & 0xFF) + (levels[p + stride] & 0xFF);
        int hiMag;
        int offset;
        if (twoD) {
            mag += levels[p + stride + 1] & 0xFF;
            hiMag = mag;
            mag += (levels[p + 2] & 0xFF) + (levels[p + 2 * stride] & 0xFF);
            offset = Tables.LO_CTX_OFFSETS[ctxOffsets + Math.min(y, 4) * 5 + Math.min(x, 4)];
        } else {
            mag += levels[p + 2] & 0xFF;
            hiMag = mag;
            mag += (levels[p + 3] & 0xFF) + (levels[p + 4] & 0xFF);
            offset = 26 + (y > 1 ? 10 : y * 5);
        }
        return (offset + (mag > 512 ? 4 : (mag + 64) >> 7)) | hiMag << 16;
    }

    /**
     * Decodes the tokens of a 2-D transform class block.
     *
     * @param t the task context
     * @param cf the coefficients
     * @param tx the transform size
     * @param slw the base-2 logarithm of the coded width in 4-sample units
     * @param slh the base-2 logarithm of the coded height in 4-sample units
     * @param eob the end-of-block position
     * @param eobTok the token of the last coefficient minus one
     * @param loCdf the offset of the base token CDFs
     * @param hiCdf the offset of the range token CDFs
     * @return the first list position in the low 16 bits and the DC token in the high bits
     */
    private static int tokens2d(TaskContext t, int[] cf, int tx, int slw, int slh, int eob, int eobTok, int loCdf,
                                int hiCdf) {
        var msac = t.ts.msac;
        var cdf = t.ts.cdf;
        var levels = t.levels;
        var nonsquare = tx >= 5 ? 1 : 0;
        var ctxOffsets = (nonsquare + (tx & nonsquare)) * 25;
        var scan = Scans.SCANS[tx];
        var stride = 4 << slh;
        var shift = slh + 2;
        var mask = (4 << slh) - 1;
        Arrays.fill(levels, 0, stride * ((4 << slw) + 2), (byte) 0);
        var tok = eobTok + 1;
        var levelTok = tok * 0x41;
        var rc = scan[eob];
        var x = rc >> shift;
        var y = rc & mask;
        if (eobTok == 2) {
            var ctx = (x | y) > 1 ? 14 : 7;
            tok = msac.decodeHiTok(cdf, hiCdf + ctx * 4);
            levelTok = tok + (3 << 6);
        }
        cf[rc] = tok << 11;
        levels[rc] = (byte) levelTok;
        for (var i = eob - 1; i > 0; i--) {
            var rcI = scan[i];
            x = rcI >> shift;
            y = rcI & mask;
            var lc = loCtx(levels, rcI, true, ctxOffsets, x, y, stride);
            y |= x;
            tok = msac.decodeSymbol4(cdf, loCdf + (lc & 0xFFFF) * 4);
            if (tok == 3) {
                var mag = (lc >>> 16) & 63;
                var ctx = (y > 1 ? 14 : 7) + (mag > 12 ? 6 : (mag + 1) >> 1);
                tok = msac.decodeHiTok(cdf, hiCdf + ctx * 4);
                levels[rcI] = (byte) (tok + (3 << 6));
                cf[rcI] = (tok << 11) | rc;
                rc = rcI;
            } else {
                var nonzero = -tok >> 31;
                levels[rcI] = (byte) (tok * 0x41);
                cf[rcI] = ((tok << 11) | rc) & nonzero;
                rc ^= (rc ^ rcI) & nonzero;
            }
        }
        var dcTok = msac.decodeSymbol4(cdf, loCdf);
        if (dcTok == 3) {
            var mag = ((levels[1] & 0xFF) + (levels[stride] & 0xFF) + (levels[stride + 1] & 0xFF)) & 63;
            var ctx = mag > 12 ? 6 : (mag + 1) >> 1;
            dcTok = msac.decodeHiTok(cdf, hiCdf + ctx * 4);
        }
        return rc | dcTok << 16;
    }

    /**
     * Decodes the tokens of a horizontal or vertical transform class block.
     *
     * @param t the task context
     * @param cf the coefficients
     * @param slw the base-2 logarithm of the coded width in 4-sample units
     * @param slh the base-2 logarithm of the coded height in 4-sample units
     * @param eob the end-of-block position
     * @param eobTok the token of the last coefficient minus one
     * @param loCdf the offset of the base token CDFs
     * @param hiCdf the offset of the range token CDFs
     * @param vertical whether the class is vertical
     * @return the first list position in the low 16 bits and the DC token in the high bits
     */
    private static int tokens1d(TaskContext t, int[] cf, int slw, int slh, int eob, int eobTok, int loCdf,
                                int hiCdf, boolean vertical) {
        var msac = t.ts.msac;
        var cdf = t.ts.cdf;
        var levels = t.levels;
        var stride = 16;
        var shift = vertical ? slw + 2 : slh + 2;
        var shift2 = vertical ? slh + 2 : 0;
        var mask = vertical ? (4 << slw) - 1 : (4 << slh) - 1;
        Arrays.fill(levels, 0, stride * ((4 << (vertical ? slw : slh)) + 2), (byte) 0);
        var tok = eobTok + 1;
        var levelTok = tok * 0x41;
        var x = eob & mask;
        var y = eob >> shift;
        var rc = vertical ? (x << shift2) | y : eob;
        if (eobTok == 2) {
            var ctx = y != 0 ? 14 : 7;
            tok = msac.decodeHiTok(cdf, hiCdf + ctx * 4);
            levelTok = tok + (3 << 6);
        }
        cf[rc] = tok << 11;
        levels[x * stride + y] = (byte) levelTok;
        for (var i = eob - 1; i > 0; i--) {
            x = i & mask;
            y = i >> shift;
            var rcI = vertical ? (x << shift2) | y : i;
            var level = x * stride + y;
            var lc = loCtx(levels, level, false, -1, x, y, stride);
            tok = msac.decodeSymbol4(cdf, loCdf + (lc & 0xFFFF) * 4);
            if (tok == 3) {
                var mag = (lc >>> 16) & 63;
                var ctx = (y > 0 ? 14 : 7) + (mag > 12 ? 6 : (mag + 1) >> 1);
                tok = msac.decodeHiTok(cdf, hiCdf + ctx * 4);
                levels[level] = (byte) (tok + (3 << 6));
                cf[rcI] = (tok << 11) | rc;
                rc = rcI;
            } else {
                var nonzero = -tok >> 31;
                levels[level] = (byte) (tok * 0x41);
                cf[rcI] = ((tok << 11) | rc) & nonzero;
                rc ^= (rc ^ rcI) & nonzero;
            }
        }
        var lc = loCtx(levels, 0, false, -1, 0, 0, stride);
        var dcTok = msac.decodeSymbol4(cdf, loCdf + (lc & 0xFFFF) * 4);
        if (dcTok == 3) {
            var mag = (lc >>> 16) & 63;
            var ctx = mag > 12 ? 6 : (mag + 1) >> 1;
            dcTok = msac.decodeHiTok(cdf, hiCdf + ctx * 4);
        }
        return rc | dcTok << 16;
    }

    /**
     * Decodes the signs and Golomb remainders, dequantizes the coefficients and derives the context
     * byte.
     *
     * @param t the task context
     * @param a the above coefficient contexts
     * @param aOff the first above entry
     * @param l the left coefficient contexts
     * @param lOff the first left entry
     * @param tx the transform size
     * @param b the block
     * @param plane the plane, 0 for luma
     * @param cf the coefficients
     * @param rc the first list position, 0 for none
     * @param dcTok the DC token
     * @param txtp the transform type
     */
    private static void dequant(TaskContext t, byte[] a, int aOff, byte[] l, int lOff, int tx, Av1Block b, int plane,
                                int[] cf, int rc, int dcTok, int txtp) {
        var ts = t.ts;
        var f = t.f;
        var msac = ts.msac;
        var dq = ts.dq;
        var dqBase = (b.segId * 3 + plane) * 2;
        var qm = txtp < IDTX ? f.qm[tx * 3 + plane] : null;
        var dqShift = Math.max(0, Tables.TX_CTX[tx] - 2);
        var cfMax = (128 << f.cur.bpc) - 1;
        int culLevel;
        int dcSignLevel;
        if (dcTok == 0) {
            culLevel = 0;
            dcSignLevel = 1 << 6;
        } else {
            var dcSignCtx = dcSignCtx(tx, a, aOff, l, lOff);
            var dcSign = msac.decodeBoolAdapt(ts.cdf, Cdf.COEF_DC_SIGN + ((plane != 0 ? 3 : 0) + dcSignCtx) * 2);
            var dcDq = dq[dqBase];
            dcSignLevel = (dcSign - 1) & (2 << 6);
            if (qm != null) {
                dcDq = (dcDq * (qm[0] & 0xFF) + 16) >> 5;
                if (dcTok == 15) {
                    dcTok = (readGolomb(msac) + 15) & 0xfffff;
                    dcDq = (dcDq * dcTok) & 0xffffff;
                } else {
                    dcDq *= dcTok;
                }
                culLevel = dcTok;
                dcDq >>= dqShift;
                dcDq = Math.min(dcDq, cfMax + dcSign);
            } else {
                if (dcTok == 15) {
                    dcTok = (readGolomb(msac) + 15) & 0xfffff;
                    dcDq = ((dcDq * dcTok) & 0xffffff) >> dqShift;
                    dcDq = Math.min(dcDq, cfMax + dcSign);
                } else {
                    dcDq = (dcDq * dcTok) >> dqShift;
                }
                culLevel = dcTok;
            }
            cf[0] = dcSign != 0 ? -dcDq : dcDq;
        }
        if (rc != 0) {
            var acDq = dq[dqBase + 1];
            do {
                var sign = msac.decodeBoolEqui();
                var rcTok = cf[rc];
                int tok;
                int dqv;
                var stepDq = qm != null ? (acDq * (qm[rc] & 0xFF) + 16) >> 5 : acDq;
                if (rcTok >= (15 << 11)) {
                    tok = (readGolomb(msac) + 15) & 0xfffff;
                    dqv = ((stepDq * tok) & 0xffffff) >> dqShift;
                    dqv = Math.min(dqv, cfMax + sign);
                } else {
                    tok = rcTok >> 11;
                    dqv = (stepDq * tok) >> dqShift;
                    if (qm != null) {
                        dqv = Math.min(dqv, cfMax + sign);
                    }
                }
                culLevel += tok;
                cf[rc] = sign != 0 ? -dqv : dqv;
                rc = rcTok & 0x3ff;
            } while (rc != 0);
        }
        t.cfCtx = Integer.compareUnsigned(culLevel, 63) < 0 ? culLevel | dcSignLevel : 63 | dcSignLevel;
    }

    /**
     * Decodes the signs and Golomb remainders and dequantizes the coefficients as
     * {@link #dequant} does, but appends them to the block records as one coefficient entry
     * ({@link BlockRecords#fetchCoefs}) and clears their positions in {@code cf} instead of
     * storing them there.
     *
     * @param t the task context, in {@link TaskContext#PASS_PARSE}
     * @param a the above coefficient contexts
     * @param aOff the first above entry
     * @param l the left coefficient contexts
     * @param lOff the first left entry
     * @param tx the transform size
     * @param b the block
     * @param plane the plane, 0 for luma
     * @param cf the coefficients holding the token list, all zero on return
     * @param rc the first list position, 0 for none
     * @param dcTok the DC token
     * @param txtp the transform type
     * @param eob the end-of-block position
     */
    private static void dequantRecorded(TaskContext t, byte[] a, int aOff, byte[] l, int lOff, int tx, Av1Block b,
                                        int plane, int[] cf, int rc, int dcTok, int txtp, int eob) {
        var ts = t.ts;
        var f = t.f;
        var msac = ts.msac;
        var dq = ts.dq;
        var dqBase = (b.segId * 3 + plane) * 2;
        var qm = txtp < IDTX ? f.qm[tx * 3 + plane] : null;
        var dqShift = Math.max(0, Tables.TX_CTX[tx] - 2);
        var cfMax = (128 << f.cur.bpc) - 1;
        var records = BlockRecords.reserve(t, eob + 2);
        var header = t.recordPos;
        var pos = header + 1;
        int culLevel;
        int dcSignLevel;
        if (dcTok == 0) {
            culLevel = 0;
            dcSignLevel = 1 << 6;
        } else {
            var dcSignCtx = dcSignCtx(tx, a, aOff, l, lOff);
            var dcSign = msac.decodeBoolAdapt(ts.cdf, Cdf.COEF_DC_SIGN + ((plane != 0 ? 3 : 0) + dcSignCtx) * 2);
            var dcDq = dq[dqBase];
            dcSignLevel = (dcSign - 1) & (2 << 6);
            if (qm != null) {
                dcDq = (dcDq * (qm[0] & 0xFF) + 16) >> 5;
                if (dcTok == 15) {
                    dcTok = (readGolomb(msac) + 15) & 0xfffff;
                    dcDq = (dcDq * dcTok) & 0xffffff;
                } else {
                    dcDq *= dcTok;
                }
                culLevel = dcTok;
                dcDq >>= dqShift;
                dcDq = Math.min(dcDq, cfMax + dcSign);
            } else {
                if (dcTok == 15) {
                    dcTok = (readGolomb(msac) + 15) & 0xfffff;
                    dcDq = ((dcDq * dcTok) & 0xffffff) >> dqShift;
                    dcDq = Math.min(dcDq, cfMax + dcSign);
                } else {
                    dcDq = (dcDq * dcTok) >> dqShift;
                }
                culLevel = dcTok;
            }
            records[pos++] = (dcSign != 0 ? -dcDq : dcDq) << 10;
        }
        if (rc != 0) {
            var acDq = dq[dqBase + 1];
            do {
                var sign = msac.decodeBoolEqui();
                var rcTok = cf[rc];
                int tok;
                int dqv;
                var stepDq = qm != null ? (acDq * (qm[rc] & 0xFF) + 16) >> 5 : acDq;
                if (rcTok >= (15 << 11)) {
                    tok = (readGolomb(msac) + 15) & 0xfffff;
                    dqv = ((stepDq * tok) & 0xffffff) >> dqShift;
                    dqv = Math.min(dqv, cfMax + sign);
                } else {
                    tok = rcTok >> 11;
                    dqv = (stepDq * tok) >> dqShift;
                    if (qm != null) {
                        dqv = Math.min(dqv, cfMax + sign);
                    }
                }
                culLevel += tok;
                cf[rc] = 0;
                records[pos++] = (sign != 0 ? -dqv : dqv) << 10 | rc;
                rc = rcTok & 0x3ff;
            } while (rc != 0);
        }
        records[header] = (eob + 1) | txtp << 11 | (pos - header - 1) << 16;
        t.recordPos = pos;
        t.cfCtx = Integer.compareUnsigned(culLevel, 63) < 0 ? culLevel | dcSignLevel : 63 | dcSignLevel;
    }
}
