package com.github.auties00.smalt.video.h264.encoder.internal;

import com.github.auties00.smalt.image.ColorRange;
import com.github.auties00.smalt.internal.bits.MsbBitWriter;

import java.io.ByteArrayOutputStream;

/**
 * Writes the sequence and picture parameter sets of a stream and the
 * {@code AVCDecoderConfigurationRecord} that carries them out of band.
 *
 * <p>The sequence parameter set declares 8-bit 4:2:0 frame coding with picture order count type
 * 2 (output order equals decoding order), frame cropping for sizes that are not whole
 * macroblocks, and video usability information with the color description, the nominal timing
 * and a bitstream restriction that announces zero reordering, so players display each picture as
 * soon as it is decoded.
 */
final class ParameterSets {
    private ParameterSets() {
        throw new AssertionError();
    }

    /**
     * Returns the {@code constraint_set} flags byte of the sequence parameter set, the six flags
     * followed by two reserved zero bits.
     *
     * @param settings the stream settings
     * @return the flags byte
     */
    static int constraintFlags(EncoderSettings settings) {
        var flags = 0;
        switch (settings.profileIdc()) {
            case 66 -> flags |= 0x80 | 0x40;
            case 77 -> flags |= 0x40;
            default -> {
            }
        }
        if (settings.levelIdc() == 9 && settings.profileIdc() != 100) {
            flags |= 0x10;
        }
        return flags;
    }

    /**
     * Returns the {@code level_idc} written to the sequence parameter set.
     *
     * @param settings the stream settings
     * @return the level indicator, with level 1b mapped per profile
     */
    static int levelIdc(EncoderSettings settings) {
        if (settings.levelIdc() == 9 && settings.profileIdc() != 100) {
            return 11;
        }
        return settings.levelIdc();
    }

    /**
     * Writes the sequence parameter set payload.
     *
     * @param settings the stream settings
     * @return the raw byte sequence payload
     */
    static byte[] sps(EncoderSettings settings) {
        var writer = new MsbBitWriter(64);
        writer.write(settings.profileIdc(), 8);
        writer.write(constraintFlags(settings), 8);
        writer.write(levelIdc(settings), 8);
        writer.writeUe(0);
        if (settings.profileIdc() == 100) {
            writer.writeUe(1);
            writer.writeUe(0);
            writer.writeUe(0);
            writer.writeBit(0);
            writer.writeBit(0);
        }
        writer.writeUe(settings.log2MaxFrameNum() - 4);
        if (settings.pictureOrderType0()) {
            writer.writeUe(0);
            writer.writeUe(settings.log2MaxPocLsb() - 4);
        } else {
            writer.writeUe(2);
        }
        writer.writeUe(settings.numRefFrames());
        writer.writeBit(0);
        writer.writeUe(settings.mbWidth() - 1);
        writer.writeUe(settings.mbHeight() - 1);
        writer.writeBit(1);
        writer.writeBit(1);
        var cropRight = (settings.codedWidth() - settings.displayWidth()) / 2;
        var cropBottom = (settings.codedHeight() - settings.displayHeight()) / 2;
        if (cropRight != 0 || cropBottom != 0) {
            writer.writeBit(1);
            writer.writeUe(0);
            writer.writeUe(cropRight);
            writer.writeUe(0);
            writer.writeUe(cropBottom);
        } else {
            writer.writeBit(0);
        }
        writer.writeBit(1);
        writeVui(writer, settings);
        writer.writeTrailingBits();
        return writer.toByteArray();
    }

    /**
     * Writes the video usability information of the sequence parameter set.
     *
     * @param writer the destination
     * @param settings the stream settings
     */
    private static void writeVui(MsbBitWriter writer, EncoderSettings settings) {
        writer.writeBit(1);
        writer.write(1, 8);
        writer.writeBit(0);
        writer.writeBit(1);
        writer.write(5, 3);
        var color = settings.color();
        writer.writeBit(color.range() == ColorRange.FULL ? 1 : 0);
        writer.writeBit(1);
        writer.write(color.primaries().code(), 8);
        writer.write(color.transfer().code(), 8);
        writer.write(color.matrix().code(), 8);
        writer.writeBit(0);
        writer.writeBit(1);
        writer.write((int) settings.numUnitsInTick(), 32);
        writer.write((int) settings.timeScale(), 32);
        writer.writeBit(0);
        writer.writeBit(0);
        writer.writeBit(0);
        writer.writeBit(0);
        writer.writeBit(1);
        writer.writeBit(1);
        writer.writeUe(0);
        writer.writeUe(0);
        writer.writeUe(15);
        writer.writeUe(15);
        writer.writeUe(settings.reorderFrames());
        writer.writeUe(settings.maxDecFrameBuffering());
    }

    /**
     * Writes the picture parameter set payload.
     *
     * @param settings the stream settings
     * @return the raw byte sequence payload
     */
    static byte[] pps(EncoderSettings settings) {
        var writer = new MsbBitWriter(32);
        writer.writeUe(0);
        writer.writeUe(0);
        writer.writeBit(settings.cabac() ? 1 : 0);
        writer.writeBit(0);
        writer.writeUe(0);
        writer.writeUe(settings.activeRefs() - 1);
        writer.writeUe(0);
        writer.writeBit(0);
        writer.write(0, 2);
        writer.writeSe(0);
        writer.writeSe(0);
        writer.writeSe(settings.chromaQpOffset());
        writer.writeBit(1);
        writer.writeBit(0);
        writer.writeBit(0);
        if (settings.transform8x8()) {
            writer.writeBit(1);
            writer.writeBit(0);
            writer.writeSe(settings.chromaQpOffset());
        }
        writer.writeTrailingBits();
        return writer.toByteArray();
    }

    /**
     * Builds the {@code AVCDecoderConfigurationRecord} of ISO/IEC 14496-15 clause 5.3.3.1 with
     * four-byte NAL unit lengths.
     *
     * @param settings the stream settings
     * @param sps the escaped sequence parameter set NAL unit, header included
     * @param pps the escaped picture parameter set NAL unit, header included
     * @return the record bytes
     */
    static byte[] avcDecoderConfigurationRecord(EncoderSettings settings, byte[] sps, byte[] pps) {
        var out = new ByteArrayOutputStream(sps.length + pps.length + 16);
        out.write(1);
        out.write(settings.profileIdc());
        out.write(constraintFlags(settings));
        out.write(levelIdc(settings));
        out.write(0xFC | 3);
        out.write(0xE0 | 1);
        out.write(sps.length >>> 8);
        out.write(sps.length);
        out.write(sps, 0, sps.length);
        out.write(1);
        out.write(pps.length >>> 8);
        out.write(pps.length);
        out.write(pps, 0, pps.length);
        if (settings.profileIdc() == 100) {
            out.write(0xFC | 1);
            out.write(0xF8);
            out.write(0xF8);
            out.write(0);
        }
        return out.toByteArray();
    }
}
