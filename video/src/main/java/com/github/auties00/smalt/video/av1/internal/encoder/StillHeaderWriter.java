package com.github.auties00.smalt.video.av1.internal.encoder;

import com.github.auties00.smalt.video.av1.internal.FrameHeader;
import com.github.auties00.smalt.video.av1.internal.SequenceHeader;

import java.util.Arrays;

/**
 * Writes the sequence header and the key frame header of an intra-only stream (AV1 specification
 * sections 5.5 and 5.9): every profile, bit depth and chroma format, monochrome, the reduced still
 * picture header, screen content tools, the quantizer deltas of each plane, CDEF and loop
 * restoration.
 *
 * <p>Headers are written from the decoder's {@link SequenceHeader} and {@link FrameHeader} models,
 * so a written header parses back into the same fields. The stream has one operating point, no
 * timing or decoder model information, no frame identifiers, order hints, super-resolution or
 * film grain, and its frames are shown key frames that keep no CDFs for later frames.
 *
 * <p>The tile, quantizer and restoration syntax is {@link ObuWriter}'s.
 *
 * @implNote This implementation follows the field order of libaom's {@code av1/encoder/bitstream.c}
 * ({@code av1_write_sequence_header_obu}, {@code write_color_config}, {@code write_uncompressed_header_obu},
 * {@code encode_restoration_mode}, {@code encode_cdef}), checked against the decoder's parser.
 */
final class StillHeaderWriter {
    /**
     * Prevents instantiation.
     */
    private StillHeaderWriter() {
        throw new AssertionError();
    }

    /**
     * Writes the payload of the sequence header OBU of a still picture or intra-only stream.
     *
     * @param seq the sequence header: its profile, still picture flags, level, size, tools and color
     * configuration
     * @return the payload
     */
    static byte[] sequenceHeader(SequenceHeader seq) {
        var w = new HeaderWriter();
        w.bits(seq.profile, 3);
        w.flag(seq.stillPicture);
        w.flag(seq.reducedStillPictureHeader);
        var levelIdx = (seq.majorLevel[0] - 2) * 4 + seq.minorLevel[0];
        if (seq.reducedStillPictureHeader) {
            w.bits(levelIdx, 5);
        } else {
            w.flag(false);
            w.flag(false);
            w.bits(0, 5);
            w.bits(0, 12);
            w.bits(levelIdx, 5);
            if (levelIdx > 7) {
                w.bit(seq.tier[0]);
            }
        }
        w.bits(seq.widthNBits - 1, 4);
        w.bits(seq.heightNBits - 1, 4);
        w.bits(seq.maxWidth - 1, seq.widthNBits);
        w.bits(seq.maxHeight - 1, seq.heightNBits);
        if (!seq.reducedStillPictureHeader) {
            w.flag(false);
        }
        w.flag(seq.sb128);
        w.flag(seq.filterIntra);
        w.flag(seq.intraEdgeFilter);
        if (!seq.reducedStillPictureHeader) {
            w.flag(false);
            w.flag(false);
            w.flag(false);
            w.flag(false);
            w.flag(false);
            w.flag(seq.screenContentTools == SequenceHeader.ADAPTIVE);
            if (seq.screenContentTools != SequenceHeader.ADAPTIVE) {
                w.bit(seq.screenContentTools);
            }
            if (seq.screenContentTools != 0) {
                w.flag(true);
            }
        }
        w.flag(false);
        w.flag(seq.cdef);
        w.flag(seq.restoration);
        w.flag(seq.hbd > 0);
        if (seq.profile == 2 && seq.hbd > 0) {
            w.flag(seq.hbd == 2);
        }
        if (seq.profile != 1) {
            w.flag(seq.monochrome);
        }
        w.flag(seq.colorDescriptionPresent);
        if (seq.colorDescriptionPresent) {
            w.bits(seq.pri, 8);
            w.bits(seq.trc, 8);
            w.bits(seq.mtrx, 8);
        }
        if (seq.monochrome) {
            w.flag(seq.colorRange);
        } else if (!(seq.pri == 1 && seq.trc == 13 && seq.mtrx == 0)) {
            w.flag(seq.colorRange);
            if (seq.profile == 2 && seq.hbd == 2) {
                w.bit(seq.ssHor);
                if (seq.ssHor != 0) {
                    w.bit(seq.ssVer);
                }
            }
            if (seq.ssHor != 0 && seq.ssVer != 0) {
                w.bits(seq.chr, 2);
            }
        }
        if (!seq.monochrome) {
            w.flag(seq.separateUvDeltaQ);
        }
        w.flag(false);
        w.trailingBits();
        return Arrays.copyOf(w.buffer(), w.size());
    }

    /**
     * Writes the uncompressed header of a shown key frame followed by the byte alignment of a
     * frame OBU.
     *
     * @param w the writer, reset first
     * @param seq the sequence header
     * @param hdr the frame header, with the tile, quantizer, filter and tool fields set
     */
    static void frameHeader(HeaderWriter w, SequenceHeader seq, FrameHeader hdr) {
        w.reset();
        if (!seq.reducedStillPictureHeader) {
            w.flag(false);
            w.bits(FrameHeader.KEY, 2);
            w.flag(true);
        }
        w.flag(false);
        if (seq.screenContentTools == SequenceHeader.ADAPTIVE) {
            w.flag(hdr.allowScreenContentTools);
        }
        if (hdr.allowScreenContentTools && seq.forceIntegerMv == SequenceHeader.ADAPTIVE) {
            w.flag(true);
        }
        if (!seq.reducedStillPictureHeader) {
            w.flag(false);
        }
        if (seq.orderHint) {
            w.bits(hdr.frameOffset, seq.orderHintNBits);
        }
        w.flag(false);
        if (hdr.allowScreenContentTools) {
            w.flag(false);
        }
        if (!seq.reducedStillPictureHeader) {
            w.flag(true);
        }
        ObuWriter.writeTiling(w, hdr);
        ObuWriter.writeQuant(w, seq, hdr);
        w.flag(false);
        if (hdr.quantYac != 0) {
            w.flag(hdr.deltaQPresent);
            if (hdr.deltaQPresent) {
                w.bits(hdr.deltaQResLog2, 2);
                w.flag(false);
            }
        }
        var lossless = hdr.quantYac == 0 && hdr.quantYdcDelta == 0 && hdr.quantUdcDelta == 0
                && hdr.quantUacDelta == 0 && hdr.quantVdcDelta == 0 && hdr.quantVacDelta == 0;
        if (!lossless) {
            w.bits(hdr.loopfilterLevelY[0], 6);
            w.bits(hdr.loopfilterLevelY[1], 6);
            if (!seq.monochrome && (hdr.loopfilterLevelY[0] != 0 || hdr.loopfilterLevelY[1] != 0)) {
                w.bits(hdr.loopfilterLevelU, 6);
                w.bits(hdr.loopfilterLevelV, 6);
            }
            w.bits(hdr.sharpness, 3);
            w.flag(false);
            if (seq.cdef) {
                w.bits(hdr.cdefDamping - 3, 2);
                w.bits(hdr.cdefNBits, 2);
                for (var i = 0; i < (1 << hdr.cdefNBits); i++) {
                    w.bits(hdr.cdefYStrength[i], 6);
                    if (!seq.monochrome) {
                        w.bits(hdr.cdefUvStrength[i], 6);
                    }
                }
            }
            if (seq.restoration) {
                ObuWriter.writeRestoration(w, seq, hdr);
            }
            w.flag(hdr.txfmMode == FrameHeader.TX_SWITCHABLE);
        }
        w.flag(hdr.reducedTxtpSet);
        w.byteAlign();
    }
}
