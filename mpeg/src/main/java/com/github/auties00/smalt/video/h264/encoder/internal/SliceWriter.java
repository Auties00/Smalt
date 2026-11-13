package com.github.auties00.smalt.video.h264.encoder.internal;

import com.github.auties00.smalt.internal.bits.MsbBitWriter;

/**
 * Entropy codes the decided macroblocks of a picture, in raster order, as one or more slices.
 *
 * <p>The writer owns the only state that follows the decoding order: the arithmetic coder or the
 * CAVLC skip run, and the quantizer each {@code mb_qp_delta} is relative to. It records the
 * quantizer of every macroblock in the picture tables, since skipped macroblocks and those
 * without residual inherit it from their predecessor. A macroblock whose coded size exceeds the
 * {@code 128 + RawMbBits} limit of Annex A is reported, so the picture can be coded again with
 * that macroblock as {@code I_PCM}.
 *
 * <p>A macroblock is written in two steps: {@link #writeData} codes it, and
 * {@link #endMacroblock(boolean)} codes the {@code end_of_slice_flag} that follows it once the
 * caller knows whether the next macroblock starts a new slice. Between the two, {@link #save()}
 * and {@link #restore()} let a caller undo the next macroblock and close the slice before it, and
 * {@link #sizeBound()} bounds the size the slice's NAL unit would have if it closed now. Instances
 * are not thread-safe.
 */
final class SliceWriter {
    /**
     * The largest number of bits a macroblock may occupy (Annex A: {@code 128 + RawMbBits}).
     */
    private static final int MAX_MB_BITS = 3200;

    /**
     * The bits that closing a CABAC slice adds at most beyond the engine's position: the terminating
     * bin, the flush, the stop bit and the alignment.
     */
    private static final int CABAC_CLOSE_BITS = 24;

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
     * The address of the first macroblock of the slice being written.
     */
    private int firstMb;

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
     * The number of completed payload bytes whose emulation prevention bytes are counted in
     * {@link #inserted}.
     */
    private int scanned;

    /**
     * The number of zero bytes that end the scanned bytes, at most 2.
     */
    private int zeros;

    /**
     * The number of emulation prevention bytes the scanned bytes need.
     */
    private int inserted;

    /**
     * The CABAC registers saved by {@link #save()}.
     */
    private final long[] savedRegisters = new long[7];

    /**
     * The CABAC context states saved by {@link #save()}.
     */
    private final byte[] savedContexts = new byte[CabacTables.CONTEXTS];

    /**
     * The writer position saved by {@link #save()} for CAVLC.
     */
    private long savedMark;

    /**
     * The scalar state saved by {@link #save()}: the previous quantizer, the delta flag, the skip
     * run, the overflow flag, the scanned bytes, the trailing zeros and the inserted bytes.
     */
    private final int[] savedState = new int[7];

    /**
     * The {@link MbType} of the last macroblock written, or {@code -1}.
     */
    private int lastType = -1;

    /**
     * Whether the last macroblock written used the 8x8 transform.
     */
    private boolean lastTransform8x8;

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
     * Clears the overflow flag before a pass over a picture.
     */
    void startPicture() {
        overflow = false;
    }

    /**
     * Starts a slice of a picture: writes the slice header and initializes the entropy coder.
     *
     * @param picture the picture being encoded
     * @param firstMb the address of the slice's first macroblock
     */
    void start(PictureContext picture, int firstMb) {
        this.picture = picture;
        this.pi = picture.info;
        this.firstMb = firstMb;
        out.reset();
        writeSliceHeader();
        previousQp = picture.sliceQp;
        previousQpDeltaNonZero = false;
        skipRun = 0;
        scanned = 0;
        zeros = 0;
        inserted = 0;
        lastType = -1;
        if (cabacMode) {
            out.alignWithOnes();
            cabac.initContexts(picture.pSlice || picture.bSlice ? 1 : 0, picture.sliceQp);
            cabac.start(out);
        }
    }

    /**
     * Finishes the slice: flushes the entropy coder and writes the trailing bits. The last
     * macroblock's {@code end_of_slice_flag} must have been written with
     * {@link #endMacroblock(boolean) endMacroblock(true)}.
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
     * Returns whether a macroblock of the picture exceeded the size limit.
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
     * Writes the slice header (clause 7.3.3) of the slice that starts at {@link #firstMb}.
     */
    private void writeSliceHeader() {
        out.writeUe(firstMb);
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
     * Writes one decided macroblock and its {@code end_of_slice_flag}, and records its quantizer
     * in the picture tables.
     *
     * @param mb the macroblock, committed to the picture tables
     * @param address the macroblock address
     * @param last whether the macroblock is the last of the slice
     */
    void write(Macroblock mb, int address, boolean last) {
        writeData(mb, address);
        endMacroblock(last);
    }

    /**
     * Writes one decided macroblock without its {@code end_of_slice_flag} and records its
     * quantizer in the picture tables.
     *
     * <p>A macroblock whose coded size exceeds the limit is still written, but marked in
     * {@link PictureContext#forcePcm} and reported by {@link #overflowed()}.
     *
     * @param mb the macroblock, committed to the picture tables
     * @param address the macroblock address, in the slice being written
     */
    void writeData(Macroblock mb, int address) {
        var mbWidth = settings.mbWidth();
        ctx.moveTo(address % mbWidth, address / mbWidth, mbWidth, firstMb);
        var type = mb.type;
        typeCounts[type]++;
        lastType = type;
        lastTransform8x8 = mb.transform8x8;
        if (mb.transform8x8) {
            typeCounts[MbType.TRANSFORM_8x8]++;
        }
        if (MbType.isSkip(type)) {
            if (cabacMode) {
                CabacWriter.writeSkipFlag(cabac, pi, ctx, true, picture.bSlice);
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
     * Writes the {@code end_of_slice_flag} of the macroblock written last; CAVLC slices have none.
     *
     * @param last whether the macroblock ends the slice
     */
    void endMacroblock(boolean last) {
        if (cabacMode) {
            CabacWriter.writeEndOfSlice(cabac, last);
        }
    }

    /**
     * Saves the writer's state after a macroblock's data and before its {@code end_of_slice_flag},
     * for {@link #restore()}.
     */
    void save() {
        if (cabacMode) {
            cabac.save(savedRegisters, savedContexts);
        } else {
            savedMark = out.mark();
        }
        savedState[0] = previousQp;
        savedState[1] = previousQpDeltaNonZero ? 1 : 0;
        savedState[2] = skipRun;
        savedState[3] = overflow ? 1 : 0;
        savedState[4] = scanned;
        savedState[5] = zeros;
        savedState[6] = inserted;
    }

    /**
     * Returns the writer to the state of the last {@link #save()}, discarding the macroblock written
     * since; its {@code end_of_slice_flag} and the macroblock that followed it.
     */
    void restore() {
        if (lastType >= 0) {
            typeCounts[lastType]--;
            if (lastTransform8x8) {
                typeCounts[MbType.TRANSFORM_8x8]--;
            }
            lastType = -1;
        }
        if (cabacMode) {
            cabac.restore(savedRegisters, savedContexts);
        } else {
            out.rollback(savedMark);
        }
        previousQp = savedState[0];
        previousQpDeltaNonZero = savedState[1] != 0;
        skipRun = savedState[2];
        overflow = savedState[3] != 0;
        scanned = savedState[4];
        zeros = savedState[5];
        inserted = savedState[6];
    }

    /**
     * Returns an upper bound of the size of the slice's NAL unit, header byte and emulation
     * prevention bytes included, if the slice were closed after the macroblock written last.
     *
     * @return the bound in bytes
     */
    long sizeBound() {
        var completed = out.size();
        var data = out.array();
        for (var i = scanned; i < completed; i++) {
            var value = data[i] & 0xFF;
            if (zeros >= 2 && value <= 3) {
                inserted++;
                zeros = 0;
            }
            zeros = value == 0 ? Math.min(zeros + 1, 2) : 0;
        }
        scanned = completed;
        long finalBits;
        if (cabacMode) {
            finalBits = cabac.bitPosition() + CABAC_CLOSE_BITS;
        } else {
            finalBits = out.bitCount() + (skipRun > 0 ? ueBits(skipRun) : 0) + 8;
        }
        var tail = Math.max(0, (finalBits + 7) / 8 - completed);
        return 1 + completed + inserted + tail + (tail + zeros) / 2 + 1;
    }

    /**
     * Returns the length of the Exp-Golomb code of a value.
     *
     * @param value the value, non-negative
     * @return the code length in bits
     */
    private static int ueBits(int value) {
        return 2 * (31 - Integer.numberOfLeadingZeros(value + 1)) + 1;
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
