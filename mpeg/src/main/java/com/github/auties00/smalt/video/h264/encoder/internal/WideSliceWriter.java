package com.github.auties00.smalt.video.h264.encoder.internal;

import com.github.auties00.smalt.internal.bits.MsbBitWriter;
import com.github.auties00.smalt.video.h264.internal.CabacTables;
import com.github.auties00.smalt.video.h264.internal.CavlcTables;
import com.github.auties00.smalt.video.h264.internal.H264Tables;

/**
 * Writes the slices of the extended engine: the slice header and, for each macroblock the
 * {@link WideCoder} decided, its skip signaling, {@code mb_field_decoding_flag} and
 * {@code macroblock_layer()} with CAVLC or CABAC (ITU-T H.264 7.3.3 to 7.3.5, 9.2 and 9.3).
 *
 * <p>Every syntax element of the chroma formats, bit depths, lossless coding and field coding the
 * engine produces is supported: Cb and Cr coded like luma in 4:4:4, 4:2:2 chroma DC blocks, field
 * scans, and the neighbor derivation of macroblock pairs. Like {@link SliceWriter}, a macroblock is
 * written in two steps, its data then its {@code end_of_slice_flag}, with {@link #save()} and
 * {@link #restore()} around them for slice size limits and the {@code I_PCM} fallback. Instances
 * are not thread-safe.
 */
final class WideSliceWriter {
    /**
     * The {@code ctxBlockCat} of the Intra_16x16 DC, Intra_16x16 AC, 4x4 and 8x8 blocks of the
     * luma, Cb and Cr components coded like luma, indexed {@code [component][kind]}.
     */
    private static final int[][] CATEGORY = {{0, 1, 2, 5}, {6, 7, 8, 9}, {10, 11, 12, 13}};

    /**
     * The block kinds of {@link #CATEGORY}.
     */
    private static final int DC = 0;

    private static final int AC = 1;

    private static final int BLOCK4 = 2;

    private static final int BLOCK8 = 3;

    /**
     * The {@code ctxBlockCat} of chroma DC blocks.
     */
    private static final int CAT_CHROMA_DC = 3;

    /**
     * The {@code ctxBlockCat} of chroma AC blocks.
     */
    private static final int CAT_CHROMA_AC = 4;

    /**
     * The bits a CABAC macroblock may occupy beyond the engine's position, added to its measured
     * size.
     */
    private static final int CABAC_MARGIN = 16;

    private final WideSettings settings;

    private final WideCoder coder;

    private final WideMbTable table;

    private final boolean cabacMode;

    private final MsbBitWriter out;

    private final CabacEncoder cabac;

    /**
     * The values a picture's slices share.
     */
    private Picture picture;

    /**
     * Whether slices enable the deblocking filter.
     */
    private boolean deblocking = true;

    /**
     * The address of the first macroblock of the slice.
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
     * Whether the previous macroblock in decoding order was skipped.
     */
    private boolean previousSkipped;

    /**
     * The PCM samples of a macroblock.
     */
    private final int[] pcm = new int[768];

    /**
     * The emulation prevention state of {@link #sizeBound()}: the bytes scanned, the trailing zeros
     * and the bytes inserted.
     */
    private int scanned;

    private int zeros;

    private int inserted;

    /**
     * The state {@link #save()} keeps for {@link #restore()}.
     */
    private final Snapshot sliceSnapshot;

    /**
     * The state before a macroblock, kept to replace it by {@code I_PCM}.
     */
    private final Snapshot pcmSnapshot;

    /**
     * A saved state of the writer and its entropy coder.
     */
    private static final class Snapshot {
        final long[] registers = new long[7];

        final byte[] contexts = new byte[CabacTables.CONTEXT_COUNT_444];

        long mark;

        final int[] state = new int[7];
    }

    /**
     * Describes the picture a slice belongs to; the engine fills one instance per picture.
     */
    static final class Picture {
        /**
         * Whether the picture is an IDR picture.
         */
        boolean idr;

        /**
         * Whether the picture is a P picture.
         */
        boolean predicted;

        /**
         * The {@code frame_num}.
         */
        int frameNum;

        /**
         * The {@code idr_pic_id}.
         */
        int idrPicId;

        /**
         * 0 for a frame, 1 for a top field, 2 for a bottom field.
         */
        int structure;

        /**
         * The {@code pic_order_cnt_lsb}.
         */
        int pocLsb;

        /**
         * The {@code delta_pic_order_cnt_bottom} of a frame.
         */
        int deltaPocBottom;

        /**
         * The {@code nal_ref_idc}.
         */
        int nalRefIdc;

        /**
         * The number of active reference indices of frame macroblocks or fields.
         */
        int refCount;

        /**
         * The {@code modification_of_pic_nums_idc} and {@code abs_diff_pic_num_minus1} pairs of the
         * reference list modification, {@link #reorderCount} values.
         */
        final int[] reorder = new int[8];

        /**
         * The number of values of {@link #reorder}, 0 for no modification.
         */
        int reorderCount;

        /**
         * The slice quantizer {@code SliceQPY}.
         */
        int sliceQp;
    }

    /**
     * Constructs a writer.
     *
     * @param settings the stream settings
     * @param coder the coder whose macroblocks the writer codes
     */
    WideSliceWriter(WideSettings settings, WideCoder coder) {
        this.settings = settings;
        this.coder = coder;
        this.table = coder.table;
        this.cabacMode = settings.cabac();
        this.out = new MsbBitWriter(settings.mbCount() * 64 + 256);
        this.cabac = new CabacEncoder(CabacTables.CONTEXT_COUNT_444);
        this.sliceSnapshot = new Snapshot();
        this.pcmSnapshot = new Snapshot();
    }

    /**
     * Starts a slice: writes its header and initializes the entropy coder.
     *
     * @param picture the picture the slice belongs to
     * @param firstMb the address of the slice's first macroblock, the top one of a pair in frames of
     * pairs
     */
    void start(Picture picture, int firstMb) {
        this.picture = picture;
        this.firstMb = firstMb;
        out.reset();
        writeHeader();
        previousQp = picture.sliceQp;
        previousQpDeltaNonZero = false;
        skipRun = 0;
        previousSkipped = false;
        scanned = 0;
        zeros = 0;
        inserted = 0;
        if (cabacMode) {
            out.alignWithOnes();
            cabac.initContexts(picture.predicted ? 1 : 0, Math.clamp(picture.sliceQp, 0, 51),
                    CabacTables.CONTEXT_COUNT_444);
            cabac.resetBinCount();
            cabac.start(out);
        }
    }

    /**
     * Writes the slice header (ITU-T H.264 7.3.3).
     */
    private void writeHeader() {
        var p = picture;
        out.writeUe(settings.mbaff() && p.structure == 0 ? firstMb >> 1 : firstMb);
        out.writeUe(p.predicted ? 0 : 2);
        out.writeUe(0);
        out.write(p.frameNum, settings.log2MaxFrameNum());
        if (!settings.frameMbsOnly()) {
            out.writeBit(p.structure != 0 ? 1 : 0);
            if (p.structure != 0) {
                out.writeBit(p.structure == 2 ? 1 : 0);
            }
        }
        if (p.idr) {
            out.writeUe(p.idrPicId);
        }
        if (settings.pictureOrderType0()) {
            out.write(p.pocLsb, settings.log2MaxPocLsb());
            if (settings.bottomFieldPicOrder() && p.structure == 0) {
                out.writeSe(p.deltaPocBottom);
            }
        }
        if (p.predicted) {
            if (p.refCount != settings.activeRefs()) {
                out.writeBit(1);
                out.writeUe(p.refCount - 1);
            } else {
                out.writeBit(0);
            }
            if (p.reorderCount > 0) {
                out.writeBit(1);
                for (var i = 0; i < p.reorderCount; i += 2) {
                    out.writeUe(p.reorder[i]);
                    out.writeUe(p.reorder[i + 1]);
                }
                out.writeUe(3);
            } else {
                out.writeBit(0);
            }
        }
        if (p.nalRefIdc != 0) {
            if (p.idr) {
                out.writeBit(0);
                out.writeBit(0);
            } else {
                out.writeBit(0);
            }
        }
        if (cabacMode && p.predicted) {
            out.writeUe(0);
        }
        out.writeSe(p.sliceQp - 26);
        var disabled = settings.lossless() || !deblocking;
        out.writeUe(disabled ? 1 : 0);
        if (!disabled) {
            out.writeSe(0);
            out.writeSe(0);
        }
    }

    /**
     * Finishes the slice: flushes the entropy coder and writes the trailing bits. The last
     * macroblock's {@code end_of_slice_flag} must have been written.
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
                skipRun = 0;
            }
            out.writeTrailingBits();
        }
        return out.array();
    }

    /**
     * {@return the length of the payload returned by the last {@link #finish()}}
     */
    int payloadLength() {
        return out.size();
    }

    /**
     * {@return the number of CABAC bins of the slice, 0 for CAVLC}
     */
    long binCount() {
        return cabacMode ? cabac.binCount() : 0;
    }

    /**
     * Writes the macroblock the coder decided last, without its {@code end_of_slice_flag}.
     *
     * <p>A macroblock whose coded size exceeds the {@code 128 + RawMbBits} limit of Annex A is
     * replaced by {@code I_PCM}: the coder rewrites it, and the writer codes it again.
     *
     * @param mb the macroblock
     * @param pairField the field flag of the macroblock's pair in frames of pairs
     * @param inferredField the field flag a decoder infers for the pair before reading it
     */
    void writeData(WideMb mb, boolean pairField, boolean inferredField) {
        var address = table.address;
        var raw = 256 * settings.bitDepth() + 2 * settings.mbWidthC() * settings.mbHeightC() * settings.bitDepth();
        var limit = raw + 128;
        if (mb.type != WideMb.IPCM) {
            save(pcmSnapshot);
            var start = position();
            writeMacroblock(mb, address, pairField, inferredField);
            if (position() - start > limit) {
                restore(pcmSnapshot);
                coder.makePcm();
                coder.recommit();
                writeMacroblock(mb, address, pairField, inferredField);
            }
            return;
        }
        writeMacroblock(mb, address, pairField, inferredField);
    }

    /**
     * Returns the writer's position in bits, counting what the CABAC engine holds.
     *
     * @return the position
     */
    private long position() {
        return cabacMode ? cabac.bitPosition() + CABAC_MARGIN : out.bitCount();
    }

    /**
     * Writes one macroblock: its skip signaling, field flag and layer.
     *
     * @param mb the macroblock
     * @param address its address
     * @param pairField the field flag of its pair
     * @param inferredField the field flag a decoder infers for the pair before reading it
     */
    private void writeMacroblock(WideMb mb, int address, boolean pairField, boolean inferredField) {
        var mbaff = settings.mbaff() && picture.structure == 0;
        var skip = mb.type == WideMb.PSKIP;
        var top = (address & 1) == 0;
        var fieldFlagPresent = mbaff && !skip && (top || previousSkipped);
        if (picture.predicted) {
            if (cabacMode) {
                var contextField = mbaff && (top || previousSkipped) ? inferredField : pairField;
                table.moveTo(address, contextField, firstMb);
                writeSkipFlag(skip);
                table.moveTo(address, pairField, firstMb);
            } else if (skip) {
                skipRun++;
            } else {
                out.writeUe(skipRun);
                skipRun = 0;
            }
        }
        if (skip) {
            previousSkipped = true;
            previousQpDeltaNonZero = false;
            return;
        }
        previousSkipped = false;
        if (fieldFlagPresent) {
            if (cabacMode) {
                writeFieldFlag(address, pairField);
            } else {
                out.writeBit(pairField ? 1 : 0);
            }
        }
        writeLayer(mb);
    }

    /**
     * Writes the {@code end_of_slice_flag} after a macroblock; CAVLC slices have none, and in frames
     * of pairs only the bottom macroblock of a pair carries one.
     *
     * @param last whether the macroblock ends the slice
     * @param address the address of the macroblock written last
     */
    void endMacroblock(boolean last, int address) {
        if (!cabacMode) {
            return;
        }
        if (settings.mbaff() && picture.structure == 0 && (address & 1) == 0) {
            return;
        }
        cabac.terminate(last ? 1 : 0);
    }

    /**
     * Sets whether slices enable the deblocking filter, which lossless slices never do.
     *
     * @param enabled whether the filter runs
     */
    void setDeblocking(boolean enabled) {
        this.deblocking = enabled;
    }

    /**
     * Saves the state after a macroblock's data and before its {@code end_of_slice_flag}, for
     * {@link #restore()}.
     */
    void save() {
        save(sliceSnapshot);
    }

    /**
     * Returns to the state of the last {@link #save()}.
     */
    void restore() {
        restore(sliceSnapshot);
    }

    /**
     * Saves the writer's state into a snapshot.
     *
     * @param snapshot the destination
     */
    private void save(Snapshot snapshot) {
        if (cabacMode) {
            cabac.save(snapshot.registers, snapshot.contexts);
        } else {
            snapshot.mark = out.mark();
        }
        var state = snapshot.state;
        state[0] = previousQp;
        state[1] = previousQpDeltaNonZero ? 1 : 0;
        state[2] = skipRun;
        state[3] = previousSkipped ? 1 : 0;
        state[4] = scanned;
        state[5] = zeros;
        state[6] = inserted;
    }

    /**
     * Returns the writer to the state of a snapshot.
     *
     * @param snapshot the saved state
     */
    private void restore(Snapshot snapshot) {
        if (cabacMode) {
            cabac.restore(snapshot.registers, snapshot.contexts);
        } else {
            out.rollback(snapshot.mark);
        }
        var state = snapshot.state;
        previousQp = state[0];
        previousQpDeltaNonZero = state[1] != 0;
        skipRun = state[2];
        previousSkipped = state[3] != 0;
        scanned = state[4];
        zeros = state[5];
        inserted = state[6];
    }

    /**
     * Returns an upper bound of the size of the slice's NAL unit, header byte and emulation
     * prevention bytes included, if the slice were closed now.
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
        var finalBits = cabacMode ? cabac.bitPosition() + 24
                : out.bitCount() + (skipRun > 0 ? 2L * (32 - Integer.numberOfLeadingZeros(skipRun + 1)) : 0) + 8;
        var tail = Math.max(0, (finalBits + 7) / 8 - completed);
        return 1 + completed + inserted + tail + (tail + zeros) / 2 + 1;
    }

    /**
     * Writes a {@code macroblock_layer()}.
     *
     * @param mb the macroblock
     */
    private void writeLayer(WideMb mb) {
        writeMbType(mb);
        if (mb.type == WideMb.IPCM) {
            writePcm();
            previousQpDeltaNonZero = false;
            return;
        }
        var intra = mb.intra();
        if ((mb.type == WideMb.I4X4 || mb.type == WideMb.I8X8) && settings.transform8x8()) {
            writeTransformFlag(mb.transform8x8);
        }
        if (mb.type == WideMb.I4X4 || mb.type == WideMb.I8X8) {
            var count = mb.type == WideMb.I8X8 ? 4 : 16;
            for (var blk = 0; blk < count; blk++) {
                writeIntraMode(mb.modes[blk], mb.predictedModes[blk]);
            }
        }
        if (intra && coderSubsampled()) {
            writeChromaMode(mb.chromaMode);
        }
        if (!intra) {
            writeInterPrediction(mb);
        }
        if (mb.type != WideMb.I16X16) {
            writeCbp(mb.cbp, intra);
            if ((mb.cbp & 15) != 0 && settings.transform8x8() && !intra) {
                writeTransformFlag(mb.transform8x8);
            }
        }
        if (mb.cbp != 0 || mb.type == WideMb.I16X16) {
            var delta = mb.qp - previousQp;
            writeQpDelta(delta);
            previousQpDeltaNonZero = delta != 0;
            previousQp = mb.qp;
            writeResidual(mb);
        } else {
            previousQpDeltaNonZero = false;
        }
    }

    /**
     * {@return whether the stream has 4:2:0 or 4:2:2 chroma}
     */
    private boolean coderSubsampled() {
        return settings.hasChroma() && !settings.chroma444();
    }

    /**
     * Writes {@code mb_type}.
     *
     * @param mb the macroblock
     */
    private void writeMbType(WideMb mb) {
        var predicted = picture.predicted;
        var chroma = coderSubsampled() ? mb.cbp >> 4 : 0;
        if (!cabacMode) {
            int code = switch (mb.type) {
                case WideMb.P16X16 -> 0;
                case WideMb.I4X4, WideMb.I8X8 -> 0;
                case WideMb.IPCM -> 25;
                default -> 1 + mb.i16Mode + 4 * chroma + ((mb.cbp & 15) != 0 ? 12 : 0);
            };
            if (predicted && mb.intra()) {
                code += 5;
            }
            out.writeUe(code);
            return;
        }
        int base;
        if (predicted) {
            if (mb.type == WideMb.P16X16) {
                cabac.decision(14, 0);
                cabac.decision(15, 0);
                cabac.decision(16, 0);
                return;
            }
            cabac.decision(14, 1);
            base = 17;
            if (mb.type == WideMb.I4X4 || mb.type == WideMb.I8X8) {
                cabac.decision(17, 0);
                return;
            }
            cabac.decision(17, 1);
        } else {
            var inc = typeCondition(-1, 0) + typeCondition(0, -1);
            if (mb.type == WideMb.I4X4 || mb.type == WideMb.I8X8) {
                cabac.decision(3 + inc, 0);
                return;
            }
            cabac.decision(3 + inc, 1);
            base = 3;
        }
        if (mb.type == WideMb.IPCM) {
            cabac.terminate(1);
            return;
        }
        cabac.terminate(0);
        if (predicted) {
            cabac.decision(base + 1, (mb.cbp & 15) != 0 ? 1 : 0);
            cabac.decision(base + 2, chroma != 0 ? 1 : 0);
            if (chroma != 0) {
                cabac.decision(base + 2, chroma == 2 ? 1 : 0);
            }
            cabac.decision(base + 3, mb.i16Mode >> 1);
            cabac.decision(base + 3, mb.i16Mode & 1);
        } else {
            cabac.decision(base + 3, (mb.cbp & 15) != 0 ? 1 : 0);
            cabac.decision(base + 4, chroma != 0 ? 1 : 0);
            if (chroma != 0) {
                cabac.decision(base + 5, chroma == 2 ? 1 : 0);
            }
            cabac.decision(base + 6, mb.i16Mode >> 1);
            cabac.decision(base + 7, mb.i16Mode & 1);
        }
    }

    /**
     * Returns the {@code mb_type} context term of the neighbor at a location of an I slice: 1 when it
     * is available and not {@code I_NxN}.
     *
     * @param xN the horizontal location
     * @param yN the vertical location
     * @return the term
     */
    private int typeCondition(int xN, int yN) {
        table.locate(xN, yN, 16, 16);
        var n = table.found;
        if (n < 0) {
            return 0;
        }
        var type = table.type[n];
        return type != WideMb.I4X4 && type != WideMb.I8X8 ? 1 : 0;
    }

    /**
     * Writes the samples of an {@code I_PCM} macroblock, after its {@code mb_type}.
     */
    private void writePcm() {
        var count = coder.pcmSamples(pcm);
        var depth = settings.bitDepth();
        if (cabacMode) {
            cabac.flush();
            out.alignWithZeros();
            for (var i = 0; i < count; i++) {
                out.write(pcm[i], depth);
            }
            cabac.start(out);
        } else {
            out.alignWithZeros();
            for (var i = 0; i < count; i++) {
                out.write(pcm[i], depth);
            }
        }
    }

    /**
     * Writes {@code mb_skip_flag} with CABAC.
     *
     * @param skip whether the macroblock is skipped
     */
    private void writeSkipFlag(boolean skip) {
        var inc = 0;
        table.locate(-1, 0, 16, 16);
        if (table.found >= 0 && table.type[table.found] != WideMb.PSKIP) {
            inc++;
        }
        table.locate(0, -1, 16, 16);
        if (table.found >= 0 && table.type[table.found] != WideMb.PSKIP) {
            inc++;
        }
        cabac.decision(11 + inc, skip ? 1 : 0);
    }

    /**
     * Writes {@code mb_field_decoding_flag} with CABAC.
     *
     * @param address the macroblock address
     * @param field the flag
     */
    private void writeFieldFlag(int address, boolean field) {
        var width = settings.mbWidth();
        var pair = address >> 1;
        var x = pair % width;
        var y = pair / width;
        var inc = 0;
        if (x > 0 && 2 * (pair - 1) >= firstMb && table.field[2 * y * width + x - 1]) {
            inc++;
        }
        if (y > 0 && 2 * (pair - width) >= firstMb && table.field[2 * (y - 1) * width + x]) {
            inc++;
        }
        cabac.decision(70 + inc, field ? 1 : 0);
    }

    /**
     * Writes {@code transform_size_8x8_flag}.
     *
     * @param flag whether the macroblock uses the 8x8 transform
     */
    private void writeTransformFlag(boolean flag) {
        if (!cabacMode) {
            out.writeBit(flag ? 1 : 0);
            return;
        }
        var inc = 0;
        table.locate(-1, 0, 16, 16);
        if (table.found >= 0 && table.transform8x8[table.found]) {
            inc++;
        }
        table.locate(0, -1, 16, 16);
        if (table.found >= 0 && table.transform8x8[table.found]) {
            inc++;
        }
        cabac.decision(399 + inc, flag ? 1 : 0);
    }

    /**
     * Writes one {@code prev_intra4x4_pred_mode_flag} or its 8x8 counterpart, and the remaining mode.
     *
     * @param mode the coded Intra_4x4 or Intra_8x8 mode
     * @param predicted the predicted mode
     */
    private void writeIntraMode(int mode, int predicted) {
        if (cabacMode) {
            CabacWriter.writeIntraMode(cabac, mode, predicted);
            return;
        }
        if (mode == predicted) {
            out.writeBit(1);
            return;
        }
        out.writeBit(0);
        out.write(mode < predicted ? mode : mode - 1, 3);
    }

    /**
     * Writes {@code intra_chroma_pred_mode}.
     *
     * @param mode the chroma prediction mode, 0 to 3
     */
    private void writeChromaMode(int mode) {
        if (!cabacMode) {
            out.writeUe(mode);
            return;
        }
        var inc = chromaModeCondition(-1, 0) + chromaModeCondition(0, -1);
        cabac.decision(64 + inc, mode != 0 ? 1 : 0);
        if (mode != 0) {
            cabac.decision(67, mode != 1 ? 1 : 0);
            if (mode != 1) {
                cabac.decision(67, mode != 2 ? 1 : 0);
            }
        }
    }

    /**
     * Returns the {@code intra_chroma_pred_mode} context term of a neighbor.
     *
     * @param xN the horizontal location
     * @param yN the vertical location
     * @return 1 when the neighbor is an intra macroblock other than {@code I_PCM} with a chroma mode
     * other than DC
     */
    private int chromaModeCondition(int xN, int yN) {
        table.locate(xN, yN, 16, 16);
        var n = table.found;
        if (n < 0) {
            return 0;
        }
        var type = table.type[n];
        return type <= WideMb.I16X16 && table.chromaMode[n] != 0 ? 1 : 0;
    }

    /**
     * Writes the reference index and motion vector difference of a {@code P_L0_16x16} macroblock.
     *
     * @param mb the macroblock
     */
    private void writeInterPrediction(WideMb mb) {
        var fieldMb = table.fieldMb;
        var count = picture.refCount * (fieldMb ? 2 : 1);
        if (count > 1) {
            if (cabacMode) {
                var inc = refCondition(-1, 0) + 2 * refCondition(0, -1);
                if (mb.ref == 0) {
                    cabac.decision(54 + inc, 0);
                } else {
                    cabac.decision(54 + inc, 1);
                    var ctx = 58;
                    for (var i = 1; i < mb.ref; i++) {
                        cabac.decision(ctx, 1);
                        ctx = 59;
                    }
                    cabac.decision(ctx, 0);
                }
            } else if (count == 2) {
                out.writeBit(mb.ref == 0 ? 1 : 0);
            } else {
                out.writeUe(mb.ref);
            }
        }
        if (cabacMode) {
            var sumX = mvdSum(-1, 0, false) + mvdSum(0, -1, false);
            var sumY = mvdSum(-1, 0, true) + mvdSum(0, -1, true);
            CabacWriter.writeMvdComponent(cabac, 40, sumX, mb.mvdX);
            CabacWriter.writeMvdComponent(cabac, 47, sumY, mb.mvdY);
        } else {
            out.writeSe(mb.mvdX);
            out.writeSe(mb.mvdY);
        }
    }

    /**
     * Returns the {@code ref_idx_l0} context term of a neighbor (ITU-T H.264 9.3.3.1.1.6).
     *
     * @param xN the horizontal location
     * @param yN the vertical location
     * @return the term
     */
    private int refCondition(int xN, int yN) {
        table.locate(xN, yN, 16, 16);
        var n = table.found;
        if (n < 0) {
            return 0;
        }
        var type = table.type[n];
        if (type <= WideMb.IPCM || type == WideMb.PSKIP) {
            return 0;
        }
        var ref = table.ref[n];
        var threshold = table.mbaff && !table.fieldMb && table.field[n] ? 1 : 0;
        return ref > threshold ? 1 : 0;
    }

    /**
     * Returns a neighbor's absolute motion vector difference component, scaled to the current
     * macroblock (ITU-T H.264 9.3.3.1.1.7).
     *
     * @param xN the horizontal location
     * @param yN the vertical location
     * @param vertical whether to return the vertical component
     * @return the value
     */
    private int mvdSum(int xN, int yN, boolean vertical) {
        table.locate(xN, yN, 16, 16);
        var n = table.found;
        if (n < 0) {
            return 0;
        }
        var type = table.type[n];
        if (type <= WideMb.IPCM || type == WideMb.PSKIP) {
            return 0;
        }
        if (!vertical) {
            return table.mvdX[n];
        }
        int value = table.mvdY[n];
        if (table.mbaff) {
            if (!table.fieldMb && table.field[n]) {
                value *= 2;
            } else if (table.fieldMb && !table.field[n]) {
                value /= 2;
            }
        }
        return value;
    }

    /**
     * Writes {@code coded_block_pattern}.
     *
     * @param cbp the pattern
     * @param intra whether the macroblock is intra
     */
    private void writeCbp(int cbp, boolean intra) {
        var subsampled = coderSubsampled();
        if (!cabacMode) {
            int code;
            if (subsampled) {
                code = intra ? Tables.CBP_INTRA_TO_CODE[cbp] : Tables.CBP_INTER_TO_CODE[cbp];
            } else {
                code = intra ? CBP_INTRA_MONO_TO_CODE[cbp & 15] : CBP_INTER_MONO_TO_CODE[cbp & 15];
            }
            out.writeUe(code);
            return;
        }
        for (var b8 = 0; b8 < 4; b8++) {
            var bx = (b8 & 1) * 8;
            var by = (b8 >> 1) * 8;
            int condA;
            int condB;
            if ((b8 & 1) != 0) {
                condA = ((cbp >> (b8 - 1)) & 1) != 0 ? 0 : 1;
            } else {
                condA = lumaCbpCondition(bx - 1, by);
            }
            if ((b8 & 2) != 0) {
                condB = ((cbp >> (b8 - 2)) & 1) != 0 ? 0 : 1;
            } else {
                condB = lumaCbpCondition(bx, by - 1);
            }
            cabac.decision(73 + condA + 2 * condB, (cbp >> b8) & 1);
        }
        if (!subsampled) {
            return;
        }
        var chroma = cbp >> 4;
        var inc = chromaCbpCondition(-1, 0, 1) + 2 * chromaCbpCondition(0, -1, 1);
        cabac.decision(77 + inc, chroma != 0 ? 1 : 0);
        if (chroma != 0) {
            inc = chromaCbpCondition(-1, 0, 2) + 2 * chromaCbpCondition(0, -1, 2);
            cabac.decision(81 + inc, chroma == 2 ? 1 : 0);
        }
    }

    /**
     * Returns the luma coded block pattern context term of the neighbor at a location outside the
     * current macroblock (ITU-T H.264 9.3.3.1.1.4).
     *
     * @param xN the horizontal location
     * @param yN the vertical location
     * @return the term
     */
    private int lumaCbpCondition(int xN, int yN) {
        table.locate(xN, yN, 16, 16);
        var n = table.found;
        if (n < 0) {
            return 0;
        }
        var type = table.type[n];
        if (type == WideMb.IPCM) {
            return 0;
        }
        if (type == WideMb.PSKIP) {
            return 1;
        }
        var b8 = (table.foundY >> 3) * 2 + (table.foundX >> 3);
        return ((table.cbp[n] >> b8) & 1) != 0 ? 0 : 1;
    }

    /**
     * Returns the chroma coded block pattern context term of a neighbor.
     *
     * @param xN the horizontal location
     * @param yN the vertical location
     * @param threshold 1 for the first bin, 2 for the second
     * @return the term
     */
    private int chromaCbpCondition(int xN, int yN, int threshold) {
        table.locate(xN, yN, 16, 16);
        var n = table.found;
        if (n < 0) {
            return 0;
        }
        var type = table.type[n];
        if (type == WideMb.IPCM) {
            return 1;
        }
        if (type == WideMb.PSKIP) {
            return 0;
        }
        var chroma = table.cbp[n] >> 4;
        return threshold == 1 ? (chroma != 0 ? 1 : 0) : (chroma == 2 ? 1 : 0);
    }

    /**
     * Writes {@code mb_qp_delta}.
     *
     * @param delta the difference to the previous quantizer
     */
    private void writeQpDelta(int delta) {
        if (cabacMode) {
            CabacWriter.writeQpDelta(cabac, delta, previousQpDeltaNonZero);
        } else {
            out.writeSe(delta);
        }
    }

    /**
     * Writes {@code residual()}.
     *
     * @param mb the macroblock
     */
    private void writeResidual(WideMb mb) {
        writeResidualLuma(mb, 0);
        if (coderSubsampled()) {
            var count = settings.mbHeightC() == 16 ? 8 : 4;
            var chroma = mb.cbp >> 4;
            if ((chroma & 3) != 0) {
                for (var c = 0; c < 2; c++) {
                    if (cabacMode) {
                        var inc = chromaDcCondition(-1, 0, c, mb) + 2 * chromaDcCondition(0, -1, c, mb);
                        writeCabacBlock(mb.chromaDc[c], 0, 0, count, CAT_CHROMA_DC, inc, count == 8);
                    } else {
                        writeCavlcBlock(mb.chromaDc[c], 0, count, count == 8 ? -2 : -1);
                    }
                }
            }
            if ((chroma & 2) != 0) {
                for (var c = 0; c < 2; c++) {
                    for (var blk = 0; blk < count; blk++) {
                        if (cabacMode) {
                            var inc = chromaAcCondition(blk, true, c, mb) + 2 * chromaAcCondition(blk, false, c, mb);
                            writeCabacBlock(mb.chromaAc[c], blk * 16, 1, 15, CAT_CHROMA_AC, inc, false);
                        } else {
                            writeCavlcBlock(mb.chromaAc[c], blk * 16 + 1, 15, chromaNc(blk, c + 1));
                        }
                    }
                }
            }
        } else if (settings.chroma444()) {
            writeResidualLuma(mb, 1);
            writeResidualLuma(mb, 2);
        }
    }

    /**
     * Writes {@code residual_luma()} of a component coded like luma.
     *
     * @param mb the macroblock
     * @param c the component
     */
    private void writeResidualLuma(WideMb mb, int c) {
        var levels = mb.levels[c];
        var i16 = mb.type == WideMb.I16X16;
        if (i16) {
            if (cabacMode) {
                var inc = dcCondition(-1, 0, c) + 2 * dcCondition(0, -1, c);
                writeCabacBlock(mb.dc[c], 0, 0, 16, CATEGORY[c][DC], inc, false);
            } else {
                writeCavlcBlock(mb.dc[c], 0, 16, lumaNc(0, c));
            }
        }
        for (var b8 = 0; b8 < 4; b8++) {
            if ((mb.cbp & (1 << b8)) == 0) {
                continue;
            }
            if (mb.transform8x8 && cabacMode) {
                var inc = settings.chroma444() ? block8Condition(b8, true, c) + 2 * block8Condition(b8, false, c) : -1;
                writeCabacBlock(levels, b8 * 64, 0, 64, CATEGORY[c][BLOCK8], inc, false);
                continue;
            }
            for (var i = 0; i < 4; i++) {
                var blk = b8 * 4 + i;
                if (mb.transform8x8) {
                    var list = interleaved;
                    for (var k = 0; k < 16; k++) {
                        list[k] = levels[b8 * 64 + 4 * k + i];
                    }
                    writeCavlcBlock(list, 0, 16, lumaNc(blk, c));
                } else if (i16) {
                    if (cabacMode) {
                        var inc = blockCondition(blk, true, c, mb) + 2 * blockCondition(blk, false, c, mb);
                        writeCabacBlock(levels, blk * 16, 1, 15, CATEGORY[c][AC], inc, false);
                    } else {
                        writeCavlcBlock(levels, blk * 16 + 1, 15, lumaNc(blk, c));
                    }
                } else if (cabacMode) {
                    var inc = blockCondition(blk, true, c, mb) + 2 * blockCondition(blk, false, c, mb);
                    writeCabacBlock(levels, blk * 16, 0, 16, CATEGORY[c][BLOCK4], inc, false);
                } else {
                    writeCavlcBlock(levels, blk * 16, 16, lumaNc(blk, c));
                }
            }
        }
    }

    /**
     * Scratch list of the CAVLC coding of an 8x8 block.
     */
    private final int[] interleaved = new int[16];

    /**
     * Returns the CAVLC {@code nC} of a 4x4 block of a component coded like luma (ITU-T H.264
     * 9.2.1).
     *
     * @param blk the {@code luma4x4BlkIdx}
     * @param c the component
     * @return the number of non-zero coefficients predicted
     */
    private int lumaNc(int blk, int c) {
        var x = H264Tables.BLOCK_X[blk] * 4;
        var y = H264Tables.BLOCK_Y[blk] * 4;
        var self = table.index;
        int nA = -1;
        int nB = -1;
        if (x > 0) {
            nA = table.totalCoeff[self * 48 + c * 16 + (y >> 2) * 4 + (x >> 2) - 1];
        } else {
            nA = neighborTotal(x - 1, y, 16, 16, c, false);
        }
        if (y > 0) {
            nB = table.totalCoeff[self * 48 + c * 16 + ((y >> 2) - 1) * 4 + (x >> 2)];
        } else {
            nB = neighborTotal(x, y - 1, 16, 16, c, false);
        }
        return combine(nA, nB);
    }

    /**
     * Returns the CAVLC {@code nC} of a chroma AC block of 4:2:0 or 4:2:2.
     *
     * @param blk the {@code chroma4x4BlkIdx}
     * @param c the component, 1 or 2
     * @return the number of non-zero coefficients predicted
     */
    private int chromaNc(int blk, int c) {
        var x = (blk & 1) * 4;
        var y = (blk >> 1) * 4;
        var self = table.index;
        int nA;
        int nB;
        if (x > 0) {
            nA = table.totalCoeff[self * 48 + c * 16 + blk - 1];
        } else {
            nA = neighborTotal(x - 1, y, 8, settings.mbHeightC(), c, true);
        }
        if (y > 0) {
            nB = table.totalCoeff[self * 48 + c * 16 + blk - 2];
        } else {
            nB = neighborTotal(x, y - 1, 8, settings.mbHeightC(), c, true);
        }
        return combine(nA, nB);
    }

    /**
     * Returns the {@code TotalCoeff} of the block of a neighboring macroblock at a location.
     *
     * @param xN the horizontal location
     * @param yN the vertical location
     * @param maxW the component's macroblock width
     * @param maxH the component's macroblock height
     * @param c the component
     * @param chroma whether the component is subsampled chroma, whose blocks are two per row
     * @return the count, or -1 when the neighbor is not available
     */
    private int neighborTotal(int xN, int yN, int maxW, int maxH, int c, boolean chroma) {
        table.locate(xN, yN, maxW, maxH);
        var n = table.found;
        if (n < 0) {
            return -1;
        }
        if (table.type[n] == WideMb.PSKIP) {
            return 0;
        }
        var index = chroma ? (table.foundY >> 2) * 2 + (table.foundX >> 2)
                : (table.foundY >> 2) * 4 + (table.foundX >> 2);
        return table.totalCoeff[n * 48 + c * 16 + index];
    }

    /**
     * Combines the counts of the neighbors into {@code nC}.
     *
     * @param nA the left count, or -1
     * @param nB the upper count, or -1
     * @return {@code nC}
     */
    private static int combine(int nA, int nB) {
        if (nA >= 0 && nB >= 0) {
            return (nA + nB + 1) >> 1;
        }
        if (nA >= 0) {
            return nA;
        }
        return Math.max(nB, 0);
    }

    /**
     * Writes one CAVLC {@code residual_block()} (ITU-T H.264 7.3.5.3.2 and 9.2).
     *
     * @param levels the levels in coding order
     * @param offset the index of the first level of the block list
     * @param count {@code maxNumCoeff}
     * @param nC the coefficient count prediction, -1 for 4:2:0 chroma DC and -2 for 4:2:2 chroma DC
     */
    private void writeCavlcBlock(int[] levels, int offset, int count, int nC) {
        var total = 0;
        var last = -1;
        for (var i = 0; i < count; i++) {
            if (levels[offset + i] != 0) {
                total++;
                last = i;
            }
        }
        var trailingOnes = 0;
        for (var i = last; i >= 0 && trailingOnes < 3; i--) {
            var level = levels[offset + i];
            if (level == 0) {
                continue;
            }
            if (Math.abs(level) != 1) {
                break;
            }
            trailingOnes++;
        }
        writeCoeffToken(total, trailingOnes, nC);
        if (total == 0) {
            return;
        }
        var suffixLength = total > 10 && trailingOnes < 3 ? 1 : 0;
        var index = 0;
        for (var i = last; i >= 0; i--) {
            var level = levels[offset + i];
            if (level == 0) {
                continue;
            }
            if (index < trailingOnes) {
                out.writeBit(level < 0 ? 1 : 0);
                index++;
                continue;
            }
            var code = level > 0 ? 2 * level - 2 : -2 * level - 1;
            if (index == trailingOnes && trailingOnes < 3) {
                code -= 2;
            }
            writeLevel(code, suffixLength);
            if (suffixLength == 0) {
                suffixLength = 1;
            }
            if (Math.abs(level) > (3 << (suffixLength - 1)) && suffixLength < 6) {
                suffixLength++;
            }
            index++;
        }
        var zerosLeft = last + 1 - total;
        if (total < count) {
            if (nC == -1) {
                out.write(CavlcTables.CHROMA_DC_TOTAL_ZEROS_CODE[total - 1][zerosLeft],
                        CavlcTables.CHROMA_DC_TOTAL_ZEROS_LENGTH[total - 1][zerosLeft]);
            } else if (nC == -2) {
                out.write(CavlcTables.CHROMA_DC_422_TOTAL_ZEROS_CODE[total - 1][zerosLeft],
                        CavlcTables.CHROMA_DC_422_TOTAL_ZEROS_LENGTH[total - 1][zerosLeft]);
            } else {
                out.write(CavlcTables.TOTAL_ZEROS_CODE[total - 1][zerosLeft],
                        CavlcTables.TOTAL_ZEROS_LENGTH[total - 1][zerosLeft]);
            }
        }
        var remaining = total;
        for (var i = last; i >= 0 && zerosLeft > 0 && remaining > 1; i--) {
            if (levels[offset + i] == 0) {
                continue;
            }
            var run = 0;
            for (var j = i - 1; j >= 0 && levels[offset + j] == 0; j--) {
                run++;
            }
            var table = Math.min(zerosLeft, 7) - 1;
            out.write(CavlcTables.RUN_BEFORE_CODE[table][run], CavlcTables.RUN_BEFORE_LENGTH[table][run]);
            zerosLeft -= run;
            remaining--;
        }
    }

    /**
     * Writes {@code coeff_token}.
     *
     * @param total the number of non-zero levels
     * @param trailingOnes the trailing levels of magnitude 1, at most 3
     * @param nC the coefficient count prediction
     */
    private void writeCoeffToken(int total, int trailingOnes, int nC) {
        if (nC == -1) {
            var index = trailingOnes * 5 + total;
            out.write(CavlcTables.CHROMA_DC_TOKEN_CODE[index], CavlcTables.CHROMA_DC_TOKEN_LENGTH[index]);
            return;
        }
        if (nC == -2) {
            var index = trailingOnes * 9 + total;
            out.write(CavlcTables.CHROMA_DC_422_TOKEN_CODE[index], CavlcTables.CHROMA_DC_422_TOKEN_LENGTH[index]);
            return;
        }
        if (nC >= 8) {
            out.write(total == 0 ? 3 : ((total - 1) << 2) | trailingOnes, 6);
            return;
        }
        var t = nC < 2 ? 0 : nC < 4 ? 1 : 2;
        var index = trailingOnes * 17 + total;
        out.write(CavlcTables.COEFF_TOKEN_CODE[t][index], CavlcTables.COEFF_TOKEN_LENGTH[t][index]);
    }

    /**
     * Writes {@code level_prefix} and {@code level_suffix} of a level code, with the escape
     * prefixes of the High profiles for large levels.
     *
     * @param code the {@code levelCode}
     * @param suffixLength the {@code suffixLength}
     */
    private void writeLevel(int code, int suffixLength) {
        if (suffixLength == 0) {
            if (code < 14) {
                writePrefix(code);
                return;
            }
            if (code < 30) {
                writePrefix(14);
                out.write(code - 14, 4);
                return;
            }
        } else if ((code >> suffixLength) < 15) {
            writePrefix(code >> suffixLength);
            out.write(code & ((1 << suffixLength) - 1), suffixLength);
            return;
        }
        var base = code - (15 << suffixLength) - (suffixLength == 0 ? 15 : 0);
        if (base < 4096) {
            writePrefix(15);
            out.write(base, 12);
            return;
        }
        for (var prefix = 16; ; prefix++) {
            var offset = (1 << (prefix - 3)) - 4096;
            if (base - offset < (1 << (prefix - 3))) {
                writePrefix(prefix);
                out.writeLong(base - offset, prefix - 3);
                return;
            }
        }
    }

    /**
     * Writes a {@code level_prefix}: that many zeros and a one.
     *
     * @param prefix the number of leading zero bits
     */
    private void writePrefix(int prefix) {
        while (prefix > 16) {
            out.write(0, 16);
            prefix -= 16;
        }
        out.write(1, prefix + 1);
    }

    /**
     * Writes one CABAC {@code residual_block_cabac()} (ITU-T H.264 7.3.5.3.3 and 9.3).
     *
     * @param levels the levels in coding order
     * @param offset the index of the block's first level, scan position 0
     * @param first the first scan position coded, 1 for AC blocks
     * @param count {@code maxNumCoeff}
     * @param cat the {@code ctxBlockCat}
     * @param cbfInc the {@code coded_block_flag} context increment, or -1 when the block has no flag
     * @param chroma422 whether the block is a 4:2:2 chroma DC block
     */
    private void writeCabacBlock(int[] levels, int offset, int first, int count, int cat, int cbfInc,
                                 boolean chroma422) {
        var nonZero = 0;
        var last = -1;
        for (var i = 0; i < count; i++) {
            if (levels[offset + first + i] != 0) {
                nonZero++;
                last = i;
            }
        }
        if (cbfInc >= 0) {
            cabac.decision(CabacTables.CBF_OFFSET[cat] + cbfInc, nonZero != 0 ? 1 : 0);
            if (nonZero == 0) {
                return;
            }
        }
        var field = coder.fieldContext();
        var sigBase = field ? CabacTables.SIG_OFFSET_FIELD[cat] : CabacTables.SIG_OFFSET[cat];
        var lastBase = field ? CabacTables.LAST_OFFSET_FIELD[cat] : CabacTables.LAST_OFFSET[cat];
        var eight = cat == 5 || cat == 9 || cat == 13;
        for (var i = 0; i < count - 1; i++) {
            var significant = levels[offset + first + i] != 0;
            int sigCtx;
            int lastCtx;
            if (eight) {
                sigCtx = sigBase + (field ? CabacTables.SIG_8X8_FIELD[i] : CabacTables.SIG_8X8[i]);
                lastCtx = lastBase + CabacTables.LAST_8X8[i];
            } else if (cat == CAT_CHROMA_DC) {
                var inc = Math.min(chroma422 ? i >> 1 : i, 2);
                sigCtx = sigBase + inc;
                lastCtx = lastBase + inc;
            } else {
                sigCtx = sigBase + i;
                lastCtx = lastBase + i;
            }
            cabac.decision(sigCtx, significant ? 1 : 0);
            if (significant) {
                cabac.decision(lastCtx, i == last ? 1 : 0);
                if (i == last) {
                    break;
                }
            }
        }
        var absBase = CabacTables.ABS_OFFSET[cat];
        var gt1Limit = cat == CAT_CHROMA_DC ? 3 : 4;
        var eq1 = 0;
        var gt1 = 0;
        for (var i = last; i >= 0; i--) {
            var level = levels[offset + first + i];
            if (level == 0) {
                continue;
            }
            var abs = Math.abs(level) - 1;
            var ctx0 = absBase + (gt1 != 0 ? 0 : Math.min(4, 1 + eq1));
            if (abs == 0) {
                cabac.decision(ctx0, 0);
                eq1++;
            } else {
                cabac.decision(ctx0, 1);
                var ctxN = absBase + 5 + Math.min(gt1Limit, gt1);
                var prefix = Math.min(abs, 14);
                for (var k = 1; k < prefix; k++) {
                    cabac.decision(ctxN, 1);
                }
                if (abs < 14) {
                    cabac.decision(ctxN, 0);
                } else {
                    var value = abs - 14;
                    var k = 0;
                    while (value >= (1 << k)) {
                        cabac.bypass(1);
                        value -= 1 << k;
                        k++;
                    }
                    cabac.bypass(0);
                    while (k > 0) {
                        k--;
                        cabac.bypass((value >> k) & 1);
                    }
                }
                gt1++;
            }
            cabac.bypass(level < 0 ? 1 : 0);
        }
    }

    /**
     * Returns the {@code coded_block_flag} context term of the Intra_16x16 DC block of a
     * neighbor of the current macroblock (ITU-T H.264 9.3.3.1.1.9).
     *
     * @param xN the horizontal location
     * @param yN the vertical location
     * @param c the component
     * @return the term
     */
    private int dcCondition(int xN, int yN, int c) {
        table.locate(xN, yN, 16, 16);
        var n = table.found;
        if (n < 0) {
            return 1;
        }
        var type = table.type[n];
        if (type == WideMb.IPCM) {
            return 1;
        }
        if (type != WideMb.I16X16) {
            return 0;
        }
        return (table.cbf[n * 3 + c] >> 16) & 1;
    }

    /**
     * Returns the {@code coded_block_flag} context term of the left or upper neighbor of a 4x4 block
     * of a component coded like luma.
     *
     * @param blk the block's {@code luma4x4BlkIdx}
     * @param left whether to look left rather than up
     * @param c the component
     * @param mb the current macroblock
     * @return the term
     */
    private int blockCondition(int blk, boolean left, int c, WideMb mb) {
        var x = H264Tables.BLOCK_X[blk] * 4 + (left ? -1 : 0);
        var y = H264Tables.BLOCK_Y[blk] * 4 + (left ? 0 : -1);
        if (x >= 0 && y >= 0) {
            var raster = (y >> 2) * 4 + (x >> 2);
            return (table.cbf[table.index * 3 + c] >> raster) & 1;
        }
        table.locate(x, y, 16, 16);
        var n = table.found;
        if (n < 0) {
            return mb.intra() ? 1 : 0;
        }
        var type = table.type[n];
        if (type == WideMb.IPCM) {
            return 1;
        }
        if (type == WideMb.PSKIP) {
            return 0;
        }
        var raster = (table.foundY >> 2) * 4 + (table.foundX >> 2);
        return (table.cbf[n * 3 + c] >> raster) & 1;
    }

    /**
     * Returns the {@code coded_block_flag} context term of the left or upper neighbor of an 8x8 block
     * of a 4:4:4 component.
     *
     * @param b8 the block's {@code luma8x8BlkIdx}
     * @param left whether to look left rather than up
     * @param c the component
     * @return the term
     */
    private int block8Condition(int b8, boolean left, int c) {
        var x = (b8 & 1) * 8 + (left ? -1 : 0);
        var y = (b8 >> 1) * 8 + (left ? 0 : -1);
        if (x >= 0 && y >= 0) {
            var raster = (y >> 2) * 4 + (x >> 2);
            return (table.cbf[table.index * 3 + c] >> raster) & 1;
        }
        table.locate(x, y, 16, 16);
        var n = table.found;
        if (n < 0) {
            return coder.mb.intra() ? 1 : 0;
        }
        var type = table.type[n];
        if (type == WideMb.IPCM) {
            return 1;
        }
        if (type == WideMb.PSKIP || !table.transform8x8[n]) {
            return 0;
        }
        var raster = (table.foundY >> 2) * 4 + (table.foundX >> 2);
        return (table.cbf[n * 3 + c] >> raster) & 1;
    }

    /**
     * Returns the {@code coded_block_flag} context term of a neighbor's chroma DC block.
     *
     * @param xN the horizontal location
     * @param yN the vertical location
     * @param c the chroma component, 0 for Cb and 1 for Cr
     * @param mb the current macroblock
     * @return the term
     */
    private int chromaDcCondition(int xN, int yN, int c, WideMb mb) {
        table.locate(xN, yN, 16, 16);
        var n = table.found;
        if (n < 0) {
            return mb.intra() ? 1 : 0;
        }
        var type = table.type[n];
        if (type == WideMb.IPCM) {
            return 1;
        }
        if (type == WideMb.PSKIP || (table.cbp[n] >> 4) == 0) {
            return 0;
        }
        return (table.cbf[n * 3 + c + 1] >> 16) & 1;
    }

    /**
     * Returns the {@code coded_block_flag} context term of the left or upper neighbor of a chroma AC
     * block.
     *
     * @param blk the block's {@code chroma4x4BlkIdx}
     * @param left whether to look left rather than up
     * @param c the chroma component, 0 for Cb and 1 for Cr
     * @param mb the current macroblock
     * @return the term
     */
    private int chromaAcCondition(int blk, boolean left, int c, WideMb mb) {
        var x = (blk & 1) * 4 + (left ? -1 : 0);
        var y = (blk >> 1) * 4 + (left ? 0 : -1);
        if (x >= 0 && y >= 0) {
            var index = (y >> 2) * 2 + (x >> 2);
            return (table.cbf[table.index * 3 + c + 1] >> index) & 1;
        }
        table.locate(x, y, 8, settings.mbHeightC());
        var n = table.found;
        if (n < 0) {
            return mb.intra() ? 1 : 0;
        }
        var type = table.type[n];
        if (type == WideMb.IPCM) {
            return 1;
        }
        if (type == WideMb.PSKIP || (table.cbp[n] >> 4) != 2) {
            return 0;
        }
        var index = (table.foundY >> 2) * 2 + (table.foundX >> 2);
        return (table.cbf[n * 3 + c + 1] >> index) & 1;
    }

    /**
     * The {@code coded_block_pattern} code number of each intra pattern of 4:0:0 and 4:4:4.
     */
    private static final int[] CBP_INTRA_MONO_TO_CODE = new int[16];

    /**
     * The {@code coded_block_pattern} code number of each inter pattern of 4:0:0 and 4:4:4.
     */
    private static final int[] CBP_INTER_MONO_TO_CODE = new int[16];

    static {
        for (var code = 0; code < 16; code++) {
            CBP_INTRA_MONO_TO_CODE[H264Tables.CBP_INTRA_MONO[code]] = code;
            CBP_INTER_MONO_TO_CODE[H264Tables.CBP_INTER_MONO[code]] = code;
        }
    }
}
