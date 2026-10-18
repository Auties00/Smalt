package com.github.auties00.smalt.video.av1.internal.encoder;

import com.github.auties00.smalt.video.av1.internal.FrameHeader;
import com.github.auties00.smalt.video.av1.internal.Obu;
import com.github.auties00.smalt.video.av1.internal.SequenceHeader;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;

/**
 * Writes AV1 open bitstream units: the OBU framing, the sequence header and the uncompressed frame
 * header (AV1 specification sections 5.3, 5.5 and 5.9).
 *
 * <p>Headers are written from the decoder's {@link SequenceHeader} and {@link FrameHeader} models,
 * so a written header parses back into the same fields. The writer covers the syntax the encoder
 * produces: hidden frames, {@code show_existing_frame} headers, compound references with skip mode,
 * and loop restoration types, but no frame size override, super-resolution, frame identifiers,
 * decoder model, screen content tools, warped or global motion or film grain; fields of those
 * features must hold their disabled values. The static members (the tile, quantizer and
 * restoration syntax, the uniform tile layout, the frame OBU, the {@code av1C} record and the
 * level) are shared with {@link StillHeaderWriter} and {@link StillFrameEncoder}.
 *
 * @implNote This implementation follows the field order of libaom's {@code av1/encoder/bitstream.c}
 * ({@code write_sequence_header_obu}, {@code write_uncompressed_header_obu} and their helpers),
 * checked against the decoder's parser.
 */
final class ObuWriter {
    /**
     * The largest tile width, in 64x64 superblocks ({@code MAX_TILE_WIDTH_SB}).
     */
    private static final int MAX_TILE_WIDTH_SB = 4096 >> 6;

    /**
     * The largest tile area, in 64x64 superblocks ({@code MAX_TILE_AREA_SB}).
     */
    private static final int MAX_TILE_AREA_SB = 4096 * 2304 >> 12;

    /**
     * The picture size limits of each level up to 6.0: the {@code seq_level_idx}, the largest
     * area, the largest width and the largest height (AV1 specification annex A.3).
     */
    private static final int[][] LEVELS = {{0, 147456, 2048, 1152}, {1, 278784, 2816, 1584},
            {4, 665856, 4352, 2448}, {5, 1065024, 5504, 3096}, {8, 2359296, 6144, 3456}, {12, 8912896, 8192, 4352},
            {16, 35651584, 16384, 8704}};

    /**
     * The scratch writer for header payloads.
     */
    private final HeaderWriter header = new HeaderWriter();

    /**
     * Creates a writer.
     */
    ObuWriter() {
    }

    /**
     * Appends an unsigned value as little-endian base-128 ({@code leb128()}).
     *
     * @param out the output
     * @param value the value, non-negative
     */
    static void writeLeb128(ByteArrayOutputStream out, long value) {
        do {
            var b = (int) (value & 0x7F);
            value >>>= 7;
            out.write(value != 0 ? b | 0x80 : b);
        } while (value != 0);
    }

    /**
     * Appends one OBU without an extension header, with its size field.
     *
     * @param out the output
     * @param type the OBU type
     * @param payload the payload bytes
     * @param offset the index of the first payload byte
     * @param length the payload length, in bytes
     */
    static void writeObu(ByteArrayOutputStream out, int type, byte[] payload, int offset, int length) {
        writeObuHeader(out, type, false, 0, length);
        out.write(payload, offset, length);
    }

    /**
     * Appends the header and size field of an OBU whose payload the caller appends next.
     *
     * @param out the output
     * @param type the OBU type
     * @param extension whether the OBU carries an extension header
     * @param temporalId the temporal layer of the extension header
     * @param length the payload size, in bytes
     */
    static void writeObuHeader(ByteArrayOutputStream out, int type, boolean extension, int temporalId, int length) {
        out.write((type << 3) | (extension ? 4 : 0) | 2);
        if (extension) {
            out.write(temporalId << 5);
        }
        writeLeb128(out, length);
    }

    /**
     * Appends a temporal delimiter OBU.
     *
     * @param out the output
     */
    static void writeTemporalDelimiter(ByteArrayOutputStream out) {
        out.write(Obu.OBU_TD << 3 | 2);
        out.write(0);
    }

    /**
     * Appends a frame OBU: the frame header, then the tiles, each but the last preceded by its size
     * minus one in {@code tileSizeBytes} little-endian bytes.
     *
     * <p>With several tiles the header gains a clear {@code tile_start_and_end_present_flag} and its
     * byte alignment, so {@code w} is modified.
     *
     * @param out the output
     * @param w the byte-aligned frame header
     * @param extension whether the OBU carries an extension header
     * @param temporalId the temporal layer of the extension header
     * @param nTiles the number of tiles
     * @param sizes the coded size of each tile, in bytes
     * @param data the coded bytes of each tile
     * @param tileSizeBytes the width of the tile size fields, 1 to 4 bytes
     */
    static void writeFrameObu(ByteArrayOutputStream out, HeaderWriter w, boolean extension, int temporalId,
                              int nTiles, int[] sizes, byte[][] data, int tileSizeBytes) {
        if (nTiles > 1) {
            w.bit(0);
            w.byteAlign();
        }
        var payload = w.size() + (nTiles - 1) * tileSizeBytes;
        for (var i = 0; i < nTiles; i++) {
            payload += sizes[i];
        }
        writeObuHeader(out, Obu.OBU_FRAME, extension, temporalId, payload);
        out.write(w.buffer(), 0, w.size());
        for (var i = 0; i < nTiles; i++) {
            if (i < nTiles - 1) {
                var v = sizes[i] - 1;
                for (var k = 0; k < tileSizeBytes; k++) {
                    out.write(v >>> (8 * k));
                }
            }
            out.write(data[i], 0, sizes[i]);
        }
    }

    /**
     * Returns the width of the tile size fields a frame needs.
     *
     * @param maxSize the size of the frame's largest tile, in bytes
     * @return the {@code tile_size_bytes}, 1 to 4
     */
    static int tileSizeBytes(int maxSize) {
        return maxSize <= 0x100 ? 1 : maxSize <= 0x10000 ? 2 : maxSize <= 0x1000000 ? 3 : 4;
    }

    /**
     * Returns the {@code av1C} codec configuration record of a stream: the four bytes describing the
     * sequence header, followed by the sequence header OBU.
     *
     * @param seq the sequence header
     * @param payload the sequence header OBU payload
     * @return the record
     */
    static byte[] av1Config(SequenceHeader seq, byte[] payload) {
        var out = new ByteArrayOutputStream(payload.length + 8);
        out.write(0x81);
        out.write(seq.profile << 5 | ((seq.majorLevel[0] - 2) * 4 + seq.minorLevel[0]));
        out.write(seq.tier[0] << 7 | (seq.hbd > 0 ? 1 : 0) << 6 | (seq.hbd == 2 ? 1 : 0) << 5
                | (seq.monochrome ? 1 : 0) << 4 | seq.ssHor << 3 | seq.ssVer << 2 | seq.chr);
        out.write(0);
        writeObu(out, Obu.OBU_SEQ_HDR, payload, 0, payload.length);
        return out.toByteArray();
    }

    /**
     * Returns the smallest level whose picture size limits admit a picture size.
     *
     * @param width the picture width
     * @param height the picture height
     * @return the {@code seq_level_idx}, or 31 (no level constraint) beyond level 6.0
     */
    static int level(int width, int height) {
        var area = (long) width * height;
        for (var l : LEVELS) {
            if (area <= l[1] && width <= l[2] && height <= l[3]) {
                return l[0];
            }
        }
        return 31;
    }

    /**
     * Sets the uniform tile layout of a frame header from requested tile logarithms, clamped to what
     * the picture allows; the tile size fields are provisionally 4 bytes wide.
     *
     * @param h the frame header
     * @param sbw the picture width, in 64x64 superblocks
     * @param sbh the picture height, in 64x64 superblocks
     * @param colsLog2 the requested base-2 logarithm of the tile columns
     * @param rowsLog2 the requested base-2 logarithm of the tile rows
     */
    static void uniformTiling(FrameHeader h, int sbw, int sbh, int colsLog2, int rowsLog2) {
        h.tilingMinLog2Cols = FrameHeader.tileLog2(MAX_TILE_WIDTH_SB, sbw);
        h.tilingMaxLog2Cols = FrameHeader.tileLog2(1, Math.min(sbw, 64));
        h.tilingMaxLog2Rows = FrameHeader.tileLog2(1, Math.min(sbh, 64));
        h.tilingLog2Cols = Math.clamp(colsLog2, h.tilingMinLog2Cols, h.tilingMaxLog2Cols);
        var minLog2Tiles = Math.max(FrameHeader.tileLog2(MAX_TILE_AREA_SB, sbw * sbh), h.tilingMinLog2Cols);
        h.tilingMinLog2Rows = Math.max(minLog2Tiles - h.tilingLog2Cols, 0);
        h.tilingLog2Rows = Math.clamp(rowsLog2, h.tilingMinLog2Rows, h.tilingMaxLog2Rows);
        h.tilingUpdate = 0;
        h.tilingNBytes = 4;
    }

    /**
     * Writes the payload of a sequence header OBU.
     *
     * @param seq the sequence header
     * @return the payload
     */
    byte[] sequenceHeader(SequenceHeader seq) {
        var w = header;
        w.reset();
        w.bits(seq.profile, 3);
        w.flag(seq.stillPicture);
        w.flag(false);
        w.flag(false);
        w.flag(false);
        w.bits(seq.numOperatingPoints - 1, 5);
        for (var i = 0; i < seq.numOperatingPoints; i++) {
            w.bits(seq.operatingPointIdc[i], 12);
            var levelIdx = (seq.majorLevel[i] - 2) * 4 + seq.minorLevel[i];
            w.bits(levelIdx, 5);
            if (seq.majorLevel[i] > 3) {
                w.bit(seq.tier[i]);
            }
        }
        w.bits(seq.widthNBits - 1, 4);
        w.bits(seq.heightNBits - 1, 4);
        w.bits(seq.maxWidth - 1, seq.widthNBits);
        w.bits(seq.maxHeight - 1, seq.heightNBits);
        w.flag(false);
        w.flag(seq.sb128);
        w.flag(seq.filterIntra);
        w.flag(seq.intraEdgeFilter);
        w.flag(seq.interIntra);
        w.flag(seq.maskedCompound);
        w.flag(seq.warpedMotion);
        w.flag(seq.dualFilter);
        w.flag(seq.orderHint);
        if (seq.orderHint) {
            w.flag(seq.jntComp);
            w.flag(seq.refFrameMvs);
        }
        w.flag(false);
        w.bit(seq.screenContentTools);
        if (seq.orderHint) {
            w.bits(seq.orderHintNBits - 1, 3);
        }
        w.flag(seq.superRes);
        w.flag(seq.cdef);
        w.flag(seq.restoration);
        w.bit(seq.hbd);
        w.flag(seq.monochrome);
        w.flag(seq.colorDescriptionPresent);
        if (seq.colorDescriptionPresent) {
            w.bits(seq.pri, 8);
            w.bits(seq.trc, 8);
            w.bits(seq.mtrx, 8);
        }
        w.flag(seq.colorRange);
        if (!seq.monochrome) {
            w.bits(seq.chr, 2);
            w.flag(seq.separateUvDeltaQ);
        }
        w.flag(seq.filmGrainPresent);
        w.trailingBits();
        return Arrays.copyOf(w.buffer(), w.size());
    }

    /**
     * Writes an uncompressed frame header without compound references, followed by the byte
     * alignment of a frame OBU.
     *
     * @param seq the sequence header
     * @param hdr the frame header
     * @param refHint the order hint of each reference slot, written for error resilient frames
     * @return the header writer holding the bits, valid until the next call
     */
    HeaderWriter frameHeader(SequenceHeader seq, FrameHeader hdr, int[] refHint) {
        return frameHeader(seq, hdr, refHint, null);
    }

    /**
     * Writes the header of a frame that shows the frame of a reference slot
     * ({@code show_existing_frame}), followed by the trailing bits of a frame header OBU.
     *
     * @param slot the reference slot
     * @return the header writer holding the bits, valid until the next call
     */
    HeaderWriter showExistingHeader(int slot) {
        var w = header;
        w.reset();
        w.flag(true);
        w.bits(slot, 3);
        w.trailingBits();
        return w;
    }

    /**
     * Writes an uncompressed frame header followed by the byte alignment of a frame OBU.
     *
     * <p>When {@link FrameHeader#switchableCompRefs} is set on an inter frame, the skip mode flag
     * is written if the frame's references allow skip mode, which needs the reference headers.
     *
     * @param seq the sequence header
     * @param hdr the frame header
     * @param refHint the order hint of each reference slot, written for error resilient frames
     * @param refs the frame header of each reference slot, or {@code null} when the frame does not
     * select compound references
     * @return the header writer holding the bits, valid until the next call
     */
    HeaderWriter frameHeader(SequenceHeader seq, FrameHeader hdr, int[] refHint, FrameHeader[] refs) {
        var w = header;
        w.reset();
        w.flag(false);
        w.bits(hdr.frameType, 2);
        w.flag(hdr.showFrame);
        if (!hdr.showFrame) {
            w.flag(hdr.showableFrame);
        }
        if (!(hdr.frameType == FrameHeader.KEY && hdr.showFrame) && hdr.frameType != FrameHeader.SWITCH) {
            w.flag(hdr.errorResilientMode);
        }
        w.flag(hdr.disableCdfUpdate);
        w.flag(false);
        if (seq.orderHint) {
            w.bits(hdr.frameOffset, seq.orderHintNBits);
        }
        if (!hdr.errorResilientMode && hdr.isInterOrSwitch()) {
            w.bits(hdr.primaryRefFrame, 3);
        }
        if (hdr.isKeyOrIntra()) {
            if (!(hdr.frameType == FrameHeader.KEY && hdr.showFrame)) {
                w.bits(hdr.refreshFrameFlags, 8);
            }
            if (hdr.refreshFrameFlags != 0xff && hdr.errorResilientMode && seq.orderHint) {
                for (var i = 0; i < 8; i++) {
                    w.bits(refHint[i], seq.orderHintNBits);
                }
            }
            w.flag(false);
        } else {
            w.bits(hdr.refreshFrameFlags, 8);
            if (hdr.errorResilientMode && seq.orderHint) {
                for (var i = 0; i < 8; i++) {
                    w.bits(refHint[i], seq.orderHintNBits);
                }
            }
            if (seq.orderHint) {
                w.flag(false);
            }
            for (var i = 0; i < 7; i++) {
                w.bits(hdr.refidx[i], 3);
            }
            w.flag(false);
            w.flag(hdr.hp);
            var switchable = hdr.subpelFilterMode == FrameHeader.FILTER_SWITCHABLE;
            w.flag(switchable);
            if (!switchable) {
                w.bits(hdr.subpelFilterMode, 2);
            }
            w.flag(hdr.switchableMotionMode);
            if (!hdr.errorResilientMode && seq.refFrameMvs && seq.orderHint) {
                w.flag(hdr.useRefFrameMvs);
            }
        }
        if (!hdr.disableCdfUpdate) {
            w.flag(!hdr.refreshContext);
        }
        writeTiling(w, hdr);
        writeQuant(w, seq, hdr);
        writeSegmentation(w, hdr);
        if (hdr.quantYac != 0) {
            w.flag(hdr.deltaQPresent);
            if (hdr.deltaQPresent) {
                w.bits(hdr.deltaQResLog2, 2);
                w.flag(hdr.deltaLfPresent);
                if (hdr.deltaLfPresent) {
                    w.bits(hdr.deltaLfResLog2, 2);
                    w.flag(hdr.deltaLfMulti);
                }
            }
        }
        if (!hdr.allLossless) {
            writeLoopFilter(w, hdr);
            if (seq.cdef) {
                w.bits(hdr.cdefDamping - 3, 2);
                w.bits(hdr.cdefNBits, 2);
                for (var i = 0; i < (1 << hdr.cdefNBits); i++) {
                    w.bits(hdr.cdefYStrength[i], 6);
                    w.bits(hdr.cdefUvStrength[i], 6);
                }
            }
            if (seq.restoration) {
                writeRestoration(w, seq, hdr);
            }
            w.flag(hdr.txfmMode == FrameHeader.TX_SWITCHABLE);
        }
        if (hdr.isInterOrSwitch()) {
            w.flag(hdr.switchableCompRefs);
            if (hdr.switchableCompRefs && seq.orderHint && refs != null && hdr.deriveSkipMode(seq, refs)) {
                w.flag(hdr.skipModeEnabled);
            }
        }
        if (!hdr.errorResilientMode && hdr.isInterOrSwitch() && seq.warpedMotion) {
            w.flag(hdr.warpMotion);
        }
        w.flag(hdr.reducedTxtpSet);
        if (hdr.isInterOrSwitch()) {
            for (var i = 0; i < 7; i++) {
                w.flag(false);
            }
        }
        w.byteAlign();
        return w;
    }

    /**
     * Writes the restoration types and unit sizes ({@code lr_params()}).
     *
     * @param w the writer
     * @param seq the sequence header
     * @param hdr the frame header, with the types and the unit size logarithms set
     */
    static void writeRestoration(HeaderWriter w, SequenceHeader seq, FrameHeader hdr) {
        var planes = seq.monochrome ? 1 : 3;
        var any = false;
        var chroma = false;
        for (var p = 0; p < planes; p++) {
            var type = hdr.restorationType[p];
            w.bits(type, 2);
            any |= type != FrameHeader.RESTORATION_NONE;
            chroma |= p > 0 && type != FrameHeader.RESTORATION_NONE;
        }
        if (!any) {
            return;
        }
        var base = 6 + (seq.sb128 ? 1 : 0);
        var shift = hdr.restorationUnitSize[0] - base;
        if (seq.sb128) {
            w.bit(shift);
        } else {
            w.flag(shift > 0);
            if (shift > 0) {
                w.bit(shift - 1);
            }
        }
        if (chroma && seq.ssHor == 1 && seq.ssVer == 1) {
            w.bit(hdr.restorationUnitSize[0] - hdr.restorationUnitSize[1]);
        }
    }

    /**
     * Writes the uniform tile layout of a frame ({@code tile_info()}).
     *
     * @param w the writer
     * @param hdr the frame header, with the tile fields set
     */
    static void writeTiling(HeaderWriter w, FrameHeader hdr) {
        w.flag(true);
        for (var i = hdr.tilingMinLog2Cols; i < hdr.tilingMaxLog2Cols; i++) {
            var more = i < hdr.tilingLog2Cols;
            w.flag(more);
            if (!more) {
                break;
            }
        }
        for (var i = hdr.tilingMinLog2Rows; i < hdr.tilingMaxLog2Rows; i++) {
            var more = i < hdr.tilingLog2Rows;
            w.flag(more);
            if (!more) {
                break;
            }
        }
        if (hdr.tilingLog2Cols != 0 || hdr.tilingLog2Rows != 0) {
            w.bits(hdr.tilingUpdate, hdr.tilingLog2Cols + hdr.tilingLog2Rows);
            w.bits(hdr.tilingNBytes - 1, 2);
        }
    }

    /**
     * Writes a delta quantizer field: a presence flag and, when non-zero, the signed value.
     *
     * @param w the writer
     * @param delta the delta, -64 to 63
     */
    static void writeDeltaQ(HeaderWriter w, int delta) {
        w.flag(delta != 0);
        if (delta != 0) {
            w.sbits(delta, 6);
        }
    }

    /**
     * Writes the quantizer parameters without quantizer matrices ({@code quantization_params()}).
     *
     * @param w the writer
     * @param seq the sequence header
     * @param hdr the frame header
     */
    static void writeQuant(HeaderWriter w, SequenceHeader seq, FrameHeader hdr) {
        w.bits(hdr.quantYac, 8);
        writeDeltaQ(w, hdr.quantYdcDelta);
        if (!seq.monochrome) {
            var diffUv = seq.separateUvDeltaQ
                    && (hdr.quantUdcDelta != hdr.quantVdcDelta || hdr.quantUacDelta != hdr.quantVacDelta);
            if (seq.separateUvDeltaQ) {
                w.flag(diffUv);
            }
            writeDeltaQ(w, hdr.quantUdcDelta);
            writeDeltaQ(w, hdr.quantUacDelta);
            if (diffUv) {
                writeDeltaQ(w, hdr.quantVdcDelta);
                writeDeltaQ(w, hdr.quantVacDelta);
            }
        }
        w.flag(false);
    }

    /**
     * Writes the segmentation parameters ({@code segmentation_params()}).
     *
     * @param w the writer
     * @param hdr the frame header
     */
    private static void writeSegmentation(HeaderWriter w, FrameHeader hdr) {
        w.flag(hdr.segmentationEnabled);
        if (!hdr.segmentationEnabled) {
            return;
        }
        if (hdr.primaryRefFrame != FrameHeader.PRIMARY_REF_NONE) {
            w.flag(hdr.segmentationUpdateMap);
            if (hdr.segmentationUpdateMap) {
                w.flag(hdr.segmentationTemporal);
            }
            w.flag(hdr.segmentationUpdateData);
        }
        if (hdr.segmentationUpdateData) {
            for (var i = 0; i < 8; i++) {
                writeFeature(w, hdr.segDeltaQ[i], 8);
                writeFeature(w, hdr.segDeltaLfYV[i], 6);
                writeFeature(w, hdr.segDeltaLfYH[i], 6);
                writeFeature(w, hdr.segDeltaLfU[i], 6);
                writeFeature(w, hdr.segDeltaLfV[i], 6);
                w.flag(hdr.segRef[i] >= 0);
                if (hdr.segRef[i] >= 0) {
                    w.bits(hdr.segRef[i], 3);
                }
                w.flag(hdr.segSkip[i]);
                w.flag(hdr.segGlobalmv[i]);
            }
        }
    }

    /**
     * Writes one signed segment feature: an enable flag and, when non-zero, the value.
     *
     * @param w the writer
     * @param value the feature value, zero when disabled
     * @param bits the number of magnitude bits
     */
    private static void writeFeature(HeaderWriter w, int value, int bits) {
        w.flag(value != 0);
        if (value != 0) {
            w.sbits(value, bits);
        }
    }

    /**
     * Writes the loop filter parameters ({@code loop_filter_params()}) with mode and reference
     * deltas disabled.
     *
     * @param w the writer
     * @param hdr the frame header
     */
    private static void writeLoopFilter(HeaderWriter w, FrameHeader hdr) {
        w.bits(hdr.loopfilterLevelY[0], 6);
        w.bits(hdr.loopfilterLevelY[1], 6);
        if (hdr.loopfilterLevelY[0] != 0 || hdr.loopfilterLevelY[1] != 0) {
            w.bits(hdr.loopfilterLevelU, 6);
            w.bits(hdr.loopfilterLevelV, 6);
        }
        w.bits(hdr.sharpness, 3);
        w.flag(hdr.modeRefDeltaEnabled);
        if (hdr.modeRefDeltaEnabled) {
            w.flag(false);
        }
    }
}
