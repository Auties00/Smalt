package com.github.auties00.smalt.video.av1.internal.encoder;

import com.github.auties00.smalt.video.av1.internal.ItxDsp;
import com.github.auties00.smalt.video.av1.internal.Scans;
import com.github.auties00.smalt.video.av1.internal.Tables;

/**
 * Codes the residual of one transform block of a still frame: forward transform, quantization,
 * dequantization and the decoder's inverse transform, with the distortion of the result.
 *
 * <p>The prediction is read from the destination; the reconstruction is written either in place
 * or into a scratch block, so the caller can compare several transform types before keeping one.
 * After {@link #code} the levels, the end of block and the scan of the coded type stay available
 * until the next call.
 *
 * <p>An instance holds scratch buffers and is used by one thread at a time.
 */
final class TxbCoder {
    /**
     * The shift from the squared coefficient error of a transform without a dequantization shift
     * to the sample-domain squared error: the forward transforms scale by 8.
     */
    private static final int TRIAL_SHIFT = 6;

    /**
     * The frame.
     */
    private final StillFrameEncoder frame;

    /**
     * The forward transform.
     */
    private final FwdTxfm fwd = new FwdTxfm();

    /**
     * The residual of the block being coded.
     */
    private final int[] residual = new int[64 * 64];

    /**
     * The forward transform output.
     */
    private final int[] coef = new int[32 * 32];

    /**
     * The quantized levels of the last coded block, in the decoder's layout.
     */
    final int[] levels = new int[32 * 32];

    /**
     * The dequantized coefficients passed to the inverse transform.
     */
    private final int[] cf = new int[32 * 32];

    /**
     * The inverse transform scratch.
     */
    private final int[] itxScratch = new int[ItxDsp.SCRATCH_INTS];

    /**
     * The reconstruction scratch block, with a stride of the block width.
     */
    final short[] scratch = new short[64 * 64];

    /**
     * The number of coefficients up to the last non-zero one of the last coded block.
     */
    int eob;

    /**
     * The scan of the last coded block.
     */
    int[] scan;

    /**
     * The analysis kernels, refreshed by the tile encoder as the vector kernels become available.
     */
    EncDsp dsp = EncDsp.select();

    /**
     * The inverse transform kernels, refreshed by the tile encoder as the vector kernels become
     * available.
     */
    ItxDsp itx = ItxDsp.select();

    /**
     * Creates a coder for a frame.
     *
     * @param frame the frame encoder
     */
    TxbCoder(StillFrameEncoder frame) {
        this.frame = frame;
    }

    /**
     * Returns the scan of a transform size and type.
     *
     * @param tx the transform size
     * @param txtp the transform type
     * @return the scan
     */
    static int[] scanOf(int tx, int txtp) {
        var txClass = Tables.TX_TYPE_CLASS[txtp];
        return txClass == CoefWriter.TX_CLASS_2D ? Scans.SCANS[tx] : CoefWriter.scan(tx, txClass);
    }

    /**
     * Returns the inverse scan of a transform size and type, the inverse of {@link #scanOf}.
     *
     * @param tx the transform size
     * @param txtp the transform type
     * @return the inverse scan, shared and read-only
     */
    static int[] inverseScanOf(int tx, int txtp) {
        return CoefWriter.inverseScan(tx, Tables.TX_TYPE_CLASS[txtp]);
    }

    /**
     * Codes one transform block whose prediction is in the destination and returns the squared
     * error of the reconstruction over the visible samples.
     *
     * @param plane the plane index: 0 luma, 1 U, 2 V
     * @param tx the transform size
     * @param txtp the transform type
     * @param src the source plane
     * @param srcOff the offset of the block in the source
     * @param srcStride the source stride
     * @param dst the plane holding the prediction
     * @param dstOff the offset of the block in the destination
     * @param dstStride the destination stride
     * @param visW the visible width in samples
     * @param visH the visible height in samples
     * @param inPlace whether the reconstruction replaces the prediction; otherwise it goes to
     * {@link #scratch} with a stride of the block width
     * @return the squared error
     */
    long code(int plane, int tx, int txtp, short[] src, int srcOff, int srcStride, short[] dst, int dstOff,
              int dstStride, int visW, int visH, boolean inPlace) {
        var w = Tables.TX_W[tx] * 4;
        var h = Tables.TX_H[tx] * 4;
        var kernels = dsp;
        kernels.subtract(src, srcOff, srcStride, dst, dstOff, dstStride, residual, w, h);
        var quant = frame.quant;
        if (quant.lossless()) {
            fwd.fwht4x4(residual, 0, w, coef);
        } else {
            kernels.forwardTransform(fwd, tx, txtp, residual, 0, w, coef);
        }
        var n = Math.min(w, 32) * Math.min(h, 32);
        var sc = quant.lossless() ? Scans.SCANS[0] : scanOf(tx, txtp);
        scan = sc;
        var dqShift = Math.max(0, Tables.TX_CTX[tx] - 2);
        var e = quant.quantize(plane, coef, n, sc, inverseScanOf(tx, txtp), dqShift, levels);
        eob = e;
        short[] out;
        int outOff;
        int outStride;
        if (inPlace) {
            out = dst;
            outOff = dstOff;
            outStride = dstStride;
        } else {
            out = scratch;
            outOff = 0;
            outStride = w;
            for (var y = 0; y < h; y++) {
                System.arraycopy(dst, dstOff + y * dstStride, out, y * w, w);
            }
        }
        if (e > 0) {
            quant.dequantize(plane, levels, e, sc, dqShift, cf);
            itx.itxfmAdd(tx, quant.lossless() ? ItxDsp.WHT_WHT : txtp, out, outOff, outStride, cf, 0, e - 1,
                    itxScratch, frame.bdMax);
        }
        if (frame.bitDepth == 8 && visW == w && visH == h) {
            return Integer.toUnsignedLong(EncDsp.sse(kernels.sseSum(src, srcOff, srcStride, out, outOff, outStride,
                    w, h)));
        }
        long sse = 0;
        for (var y = 0; y < visH; y++) {
            var s = srcOff + y * srcStride;
            var d = outOff + y * outStride;
            for (var x = 0; x < visW; x++) {
                var diff = src[s + x] - out[d + x];
                sse += diff * diff;
            }
        }
        return sse;
    }

    /**
     * Quantizes one transform block whose prediction is in the destination without
     * reconstructing it; the levels, end of block and scan stay available as after {@link #code}.
     *
     * @param plane the plane index: 0 luma, 1 U, 2 V
     * @param tx the transform size
     * @param txtp the transform type
     * @param src the source plane
     * @param srcOff the offset of the block in the source
     * @param srcStride the source stride
     * @param dst the plane holding the prediction
     * @param dstOff the offset of the block in the destination
     * @param dstStride the destination stride
     */
    void quantize(int plane, int tx, int txtp, short[] src, int srcOff, int srcStride, short[] dst, int dstOff,
                  int dstStride) {
        var w = Tables.TX_W[tx] * 4;
        var h = Tables.TX_H[tx] * 4;
        dsp.subtract(src, srcOff, srcStride, dst, dstOff, dstStride, residual, w, h);
        var quant = frame.quant;
        if (quant.lossless()) {
            fwd.fwht4x4(residual, 0, w, coef);
        } else {
            dsp.forwardTransform(fwd, tx, txtp, residual, 0, w, coef);
        }
        var n = Math.min(w, 32) * Math.min(h, 32);
        var sc = quant.lossless() ? Scans.SCANS[0] : scanOf(tx, txtp);
        scan = sc;
        eob = quant.quantize(plane, coef, n, sc, inverseScanOf(tx, txtp), Math.max(0, Tables.TX_CTX[tx] - 2), levels);
    }

    /**
     * Quantizes one transform block whose prediction is in the destination without
     * reconstructing it and returns the squared error estimated in the transform domain, at the
     * scale of the sample-domain squared error; the levels, end of block and scan stay available
     * as after {@link #code}.
     *
     * @implNote This implementation measures the distortion as libaom's {@code dist_block_tx_domain}
     * does, between the forward transform's coefficients and their dequantization. The
     * transforms are scaled orthonormal, so the error equals the sample-domain error of
     * the unrounded reconstruction up to a power of two per transform scale.
     * @param plane the plane index: 0 luma, 1 U, 2 V
     * @param tx the transform size, at most 32 samples on each side
     * @param txtp the transform type
     * @param src the source plane
     * @param srcOff the offset of the block in the source
     * @param srcStride the source stride
     * @param dst the plane holding the prediction
     * @param dstOff the offset of the block in the destination
     * @param dstStride the destination stride
     * @return the estimated squared error
     */
    long trial(int plane, int tx, int txtp, short[] src, int srcOff, int srcStride, short[] dst, int dstOff,
               int dstStride) {
        quantize(plane, tx, txtp, src, srcOff, srcStride, dst, dstOff, dstStride);
        var w = Tables.TX_W[tx] * 4;
        var h = Tables.TX_H[tx] * 4;
        var logScale = Math.max(0, Tables.TX_CTX[tx] - 2);
        var error = frame.quant.error(plane, coef, levels, w * h, logScale);
        return error >> (TRIAL_SHIFT - 2 * logScale);
    }

    /**
     * Copies the scratch reconstruction of the last block coded out of place into a plane.
     *
     * @param tx the transform size
     * @param dst the plane
     * @param dstOff the offset of the block
     * @param dstStride the plane stride
     */
    void keep(int tx, short[] dst, int dstOff, int dstStride) {
        var w = Tables.TX_W[tx] * 4;
        var h = Tables.TX_H[tx] * 4;
        for (var y = 0; y < h; y++) {
            System.arraycopy(scratch, y * w, dst, dstOff + y * dstStride, w);
        }
    }

    /**
     * Dequantizes the levels of the last coded block into the decoder's coefficient buffer.
     *
     * @param plane the plane index: 0 luma, 1 U, 2 V
     * @param tx the transform size
     * @param out the coefficient buffer, all zero on entry
     */
    void dequantizeInto(int plane, int tx, int[] out) {
        if (eob > 0) {
            frame.quant.dequantize(plane, levels, eob, scan, Math.max(0, Tables.TX_CTX[tx] - 2), out);
        }
    }
}
