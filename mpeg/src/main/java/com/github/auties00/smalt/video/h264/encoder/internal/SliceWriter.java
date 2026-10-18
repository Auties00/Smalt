package com.github.auties00.smalt.video.h264.encoder.internal;

import com.github.auties00.smalt.internal.bits.MsbBitWriter;

/**
 * Entropy codes the decided macroblocks of a picture, in raster order, as one slice.
 *
 * <p>The writer owns the only state that follows the decoding order: the arithmetic coder or the
 * CAVLC skip run, and the quantizer each {@code mb_qp_delta} is relative to. It records the
 * quantizer of every macroblock in the picture tables, since skipped macroblocks and those
 * without residual inherit it from their predecessor. A macroblock whose coded size exceeds the
 * {@code 128 + RawMbBits} limit of Annex A is reported, so the picture can be coded again with
 * that macroblock as {@code I_PCM}. Instances are not thread-safe.
 */
final class SliceWriter {
    /**
     * The largest number of bits a macroblock may occupy (Annex A: {@code 128 + RawMbBits}).
     */
    private static final int MAX_MB_BITS = 3200;

    /**
     * The stream settings.
     */
    private final EncoderSettings settings;

    /**
     * Whether the slice data is CABAC coded.
     */
    private final boolean cabacMode;

    /**
     * Whether the 8x8 transform is enabled.
     */
    private final boolean transform8x8Mode;

    /**
     * The pixel kernels.
     */
    private PixelKernels pixels;

    /**
     * The slice data being written.
     */
    private final MsbBitWriter out;

    /**
     * The CABAC encoding engine.
     */
    private final CabacEncoder cabac = new CabacEncoder();

    /**
     * The CAVLC writer.
     */
    private final CavlcWriter cavlc = new CavlcWriter();

    /**
     * The current macroblock's position and neighbors.
     */
    private final MbContext ctx = new MbContext();

    /**
     * The source samples of an {@code I_PCM} macroblock.
     */
    private final byte[] pcm = new byte[384];

    /**
     * The picture being written.
     */
    private PictureContext picture;

    /**
     * The macroblock tables of the picture.
     */
    private PictureInfo pi;

    /**
     * The quantizer the next {@code mb_qp_delta} is relative to.
     */
    private int previousQp;

    /**
     * Whether the previous macroblock coded a non-zero {@code mb_qp_delta}.
     */
    private boolean previousQpDeltaNonZero;

    /**
     * The number of skipped macroblocks not yet written as {@code mb_skip_run}.
     */
    private int skipRun;

    /**
     * Whether a macroblock of the picture exceeded the size limit.
     */
    private boolean overflow;

    /**
     * The largest number of bits a macroblock may occupy, {@link #MAX_MB_BITS} unless lowered
     * for tests.
     */
    private int bitLimit = MAX_MB_BITS;

    /**
     * The number of macroblocks written with each {@link MbType} constant since the writer was
     * created; slot {@link MbType#TRANSFORM_8x8} counts those coded with the 8x8 transform.
     */
    final long[] typeCounts = new long[MbType.COUNT];

    /**
     * Constructs a writer.
     *
     * @param settings the stream settings
     * @param pixels the pixel kernels
     */
    SliceWriter(EncoderSettings settings, PixelKernels pixels) {
        this.settings = settings;
        this.cabacMode = settings.cabac();
        this.transform8x8Mode = settings.transform8x8();
        this.pixels = pixels;
        this.out = new MsbBitWriter(settings.mbCount() * 16 + 256);
    }

    /**
     * Replaces the kernels the writer computes with, between two pictures.
     *
     * @param pixels the pixel kernels
     */
    void useKernels(PixelKernels pixels) {
        this.pixels = pixels;
    }

    /**
     * Starts the slice of a picture: writes the slice header and initializes the entropy coder.
     *
     * @param picture the picture being encoded
     */
    void start(PictureContext picture) {
        this.picture = picture;
        this.pi = picture.info;
        overflow = false;
        out.reset();
        writeSliceHeader();
        previousQp = picture.sliceQp;
        previousQpDeltaNonZero = false;
        skipRun = 0;
        if (cabacMode) {
            out.alignWithOnes();
            cabac.initContexts(picture.pSlice || picture.bSlice ? 1 : 0, picture.sliceQp);
            cabac.start(out);
        }
    }

    /**
     * Finishes the slice: flushes the entropy coder and writes the trailing bits.
     *
     * @return the raw byte sequence payload, valid up to {@link #payloadLength()}
     */
    byte[] finish() {
        if (cabacMode) {
            cabac.flush();
            out.alignWithZeros();
        } else {
            if (skipRun > 0) {
                out.writeUe(skipRun);
            }
            out.writeTrailingBits();
        }
        return out.array();
    }

    /**
     * Returns the length of the payload returned by the last {@link #finish()}.
     *
     * @return the number of bytes
     */
    int payloadLength() {
        return out.size();
    }

    /**
     * Returns the number of CABAC bins of the last slice.
     *
     * @return the bin count, {@code 0} for CAVLC
     */
    long binCount() {
        return cabacMode ? cabac.binCount() : 0;
    }

    /**
     * Returns whether a macroblock of the slice exceeded the size limit.
     *
     * @return whether the picture must be coded again
     */
    boolean overflowed() {
        return overflow;
    }

    /**
     * Lowers the macroblock size limit, so tests can exercise the coding of the macroblocks
     * that exceed it as {@code I_PCM} on a second pass.
     *
     * @param bits the limit in bits, at most {@link #MAX_MB_BITS}
     */
    void setBitLimit(int bits) {
        this.bitLimit = Math.min(bits, MAX_MB_BITS);
    }

    /**
     * Writes the slice header (clause 7.3.3) of the picture's only slice.
     */
    private void writeSliceHeader() {
        out.writeUe(0);
        out.writeUe(picture.pSlice ? 0 : picture.bSlice ? 1 : 2);
        out.writeUe(0);
        out.write(picture.frameNum, settings.log2MaxFrameNum());
        if (picture.idr) {
            out.writeUe(picture.idrPicId);
        }
        if (settings.pictureOrderType0()) {
            out.write(picture.pocLsb, settings.log2MaxPocLsb());
        }
        if (picture.bSlice) {
            out.writeBit(1);
            out.writeBit(1);
            out.writeUe(0);
            out.writeUe(0);
            out.writeBit(0);
            out.writeBit(0);
        }
        if (picture.pSlice) {
            if (picture.refCount != settings.activeRefs()) {
                out.writeBit(1);
                out.writeUe(picture.refCount - 1);
            } else {
                out.writeBit(0);
            }
            if (picture.reorderCount > 0) {
                out.writeBit(1);
                for (var i = 0; i < picture.reorderCount; i++) {
                    out.writeUe(picture.reorderIdc[i]);
                    out.writeUe(picture.reorderDifferences[i]);
                }
                out.writeUe(3);
            } else {
                out.writeBit(0);
            }
        }
        if (picture.idr) {
            out.writeBit(0);
            out.writeBit(0);
        } else if (picture.nalRefIdc != 0) {
            if (picture.markCount >= 0) {
                out.writeBit(1);
                for (var i = 0; i < picture.markCount; i++) {
                    out.writeUe(1);
                    out.writeUe(picture.markDifferences[i]);
                }
                out.writeUe(0);
            } else {
                out.writeBit(0);
            }
        }
        if (cabacMode && (picture.pSlice || picture.bSlice)) {
            out.writeUe(0);
        }
        out.writeSe(picture.sliceQp - 26);
        out.writeUe(0);
        out.writeSe(0);
        out.writeSe(0);
        if (cabacMode) {
            cabac.resetBinCount();
        }
    }

    /**
     * Writes one decided macroblock and records its quantizer in the picture tables.
     *
     * <p>A macroblock whose coded size exceeds the limit is still written, but marked in
     * {@link PictureContext#forcePcm} and reported by {@link #overflowed()}.
     *
     * @param mb the macroblock, committed to the picture tables
     * @param address the macroblock address
     * @param last whether the macroblock is the last of the slice
     */
    void write(Macroblock mb, int address, boolean last) {
        var mbWidth = settings.mbWidth();
        ctx.moveTo(address % mbWidth, address / mbWidth, mbWidth, 0);
        var type = mb.type;
        typeCounts[type]++;
        if (mb.transform8x8) {
            typeCounts[MbType.TRANSFORM_8x8]++;
        }
        if (MbType.isSkip(type)) {
            if (cabacMode) {
                CabacWriter.writeSkipFlag(cabac, pi, ctx, true, picture.bSlice);
                CabacWriter.writeEndOfSlice(cabac, last);
            } else {
                skipRun++;
            }
            pi.qp[address] = (byte) previousQp;
            previousQpDeltaNonZero = false;
            return;
        }
        long bits;
        if (cabacMode) {
            if (picture.pSlice || picture.bSlice) {
                CabacWriter.writeSkipFlag(cabac, pi, ctx, false, picture.bSlice);
            }
            var start = cabac.bitPosition();
            writeLayer(mb, address);
            bits = cabac.bitPosition() - start + 16;
            CabacWriter.writeEndOfSlice(cabac, last);
        } else {
            if (picture.pSlice) {
                out.writeUe(skipRun);
                skipRun = 0;
            }
            var start = out.mark();
            writeLayer(mb, address);
            bits = out.bitCount() - start;
        }
        if (type != MbType.I_PCM && bits > bitLimit) {
            picture.forcePcm[address] = true;
            overflow = true;
        }
        if (mb.cbp != 0 || type == MbType.I16x16) {
            previousQpDeltaNonZero = qpDelta(mb) != 0;
            previousQp = mb.qp;
        } else {
            previousQpDeltaNonZero = false;
        }
        pi.qp[address] = (byte) previousQp;
    }

    /**
     * Writes one non-skipped macroblock layer with the slice's entropy coder.
     *
     * @param mb the macroblock
     * @param address the macroblock address
     */
    private void writeLayer(Macroblock mb, int address) {
        var delta = qpDelta(mb);
        if (cabacMode) {
            CabacWriter.writeMacroblock(cabac, pi, ctx, mb, picture.pSlice, picture.bSlice, picture.refCount, delta,
                    previousQpDeltaNonZero, transform8x8Mode);
            if (mb.type == MbType.I_PCM) {
                copyPcm(address);
                cabac.flush();
                out.alignWithZeros();
                out.writeBytes(pcm, 0, 384);
                cabac.start(out);
            }
        } else {
            cavlc.writeMacroblock(out, pi, ctx, mb, picture.pSlice, picture.refCount, delta, transform8x8Mode);
            if (mb.type == MbType.I_PCM) {
                copyPcm(address);
                cavlc.writePcmSamples(out, pcm);
            }
        }
    }

    /**
     * Copies the source samples of an {@code I_PCM} macroblock into {@link #pcm}.
     *
     * @param address the macroblock address
     */
    private void copyPcm(int address) {
        var source = picture.source;
        var mbWidth = settings.mbWidth();
        var mbX = address % mbWidth;
        var mbY = address / mbWidth;
        var luma = source.lumaOrigin + mbY * 16 * source.lumaStride + mbX * 16;
        var chroma = source.chromaOrigin + mbY * 8 * source.chromaStride + mbX * 8;
        pixels.copy(source.y, luma, source.lumaStride, pcm, 0, 16, 16, 16);
        pixels.copy(source.u, chroma, source.chromaStride, pcm, 256, 8, 8, 8);
        pixels.copy(source.v, chroma, source.chromaStride, pcm, 320, 8, 8, 8);
    }

    /**
     * Returns the {@code mb_qp_delta} a macroblock signals.
     *
     * @param mb the macroblock
     * @return the difference to the previous quantizer, wrapped to the syntax range, or
     * {@code 0} when the macroblock carries none
     */
    private int qpDelta(Macroblock mb) {
        if (mb.cbp == 0 && mb.type != MbType.I16x16) {
            return 0;
        }
        var delta = mb.qp - previousQp;
        if (delta < -26) {
            return delta + 52;
        }
        return delta > 25 ? delta - 52 : delta;
    }
}
