package com.github.auties00.smalt.video.h264.encoder.internal;

import com.github.auties00.smalt.image.Chromaticity;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.ColorRange;
import com.github.auties00.smalt.internal.bits.MsbBitWriter;

import java.io.ByteArrayOutputStream;

/**
 * Writes the sequence and picture parameter sets of a stream, the SEI messages describing it and
 * the {@code AVCDecoderConfigurationRecord} that carries the parameter sets out of band.
 *
 * <p>The sequence parameter set declares the stream's chroma format and bit depth, picture order
 * count type 2 (output order equals decoding order) or 0, frame or field coding, frame cropping for
 * sizes that are not whole macroblocks, and video usability information with the sample aspect
 * ratio, the color description, the chroma sample location when known, the nominal timing and a
 * bitstream restriction that announces the reordering depth, so players display each picture as soon
 * as its turn comes.
 */
final class ParameterSets {
    /**
     * The sample aspect ratios of ITU-T H.264 Table E-1, as width and height pairs indexed by
     * {@code aspect_ratio_idc} minus 1.
     */
    private static final int[][] ASPECT_RATIOS = {
            {1, 1}, {12, 11}, {10, 11}, {16, 11}, {40, 33}, {24, 11}, {20, 11}, {32, 11}, {80, 33},
            {18, 11}, {15, 11}, {64, 33}, {160, 99}, {4, 3}, {3, 2}, {2, 1}
    };

    /**
     * The {@code aspect_ratio_idc} of an explicit ratio.
     */
    private static final int EXTENDED_SAR = 255;

    /**
     * The SEI {@code payloadType} of a recovery point.
     */
    private static final int SEI_RECOVERY_POINT = 6;

    /**
     * The SEI {@code payloadType} of the mastering display color volume.
     */
    private static final int SEI_MASTERING_DISPLAY = 137;

    /**
     * The SEI {@code payloadType} of the content light level information.
     */
    private static final int SEI_CONTENT_LIGHT_LEVEL = 144;

    private ParameterSets() {
        throw new AssertionError();
    }

    /**
     * Returns whether a {@code profile_idc} carries the chroma format and bit depth syntax of the
     * sequence parameter set.
     *
     * @param profileIdc the profile indicator
     * @return whether the profile is a High profile
     */
    static boolean highSyntax(int profileIdc) {
        return profileIdc >= 100;
    }

    /**
     * Returns the {@code constraint_set} flags byte of the sequence parameter set, the six flags
     * followed by two reserved zero bits.
     *
     * @param settings the stream settings
     * @return the flags byte
     */
    static int constraintFlags(StreamParameters settings) {
        var flags = 0;
        switch (settings.profileIdc()) {
            case 66 -> flags |= 0x80 | 0x40;
            case 77 -> flags |= 0x40;
            default -> {
            }
        }
        if (settings.levelIdc() == 9 && !highSyntax(settings.profileIdc())) {
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
    static int levelIdc(StreamParameters settings) {
        if (settings.levelIdc() == 9 && !highSyntax(settings.profileIdc())) {
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
    static byte[] sps(StreamParameters settings) {
        var writer = new MsbBitWriter(64);
        writer.write(settings.profileIdc(), 8);
        writer.write(constraintFlags(settings), 8);
        writer.write(levelIdc(settings), 8);
        writer.writeUe(0);
        if (highSyntax(settings.profileIdc())) {
            writer.writeUe(settings.chromaFormatIdc());
            if (settings.chromaFormatIdc() == 3) {
                writer.writeBit(0);
            }
            writer.writeUe(settings.bitDepth() - 8);
            writer.writeUe(settings.bitDepth() - 8);
            writer.writeBit(settings.transformBypass() ? 1 : 0);
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
        writer.writeBit(settings.frameNumGapsAllowed() ? 1 : 0);
        writer.writeUe(settings.mbWidth() - 1);
        var frameMbsOnly = settings.frameMbsOnly();
        writer.writeUe((frameMbsOnly ? settings.mbHeight() : settings.mbHeight() / 2) - 1);
        writer.writeBit(frameMbsOnly ? 1 : 0);
        if (!frameMbsOnly) {
            writer.writeBit(settings.mbaff() ? 1 : 0);
        }
        writer.writeBit(1);
        var chroma = settings.chromaFormatIdc();
        var cropUnitX = chroma == 1 || chroma == 2 ? 2 : 1;
        var cropUnitY = (chroma == 1 ? 2 : 1) * (frameMbsOnly ? 1 : 2);
        var cropRight = settings.cropRight() / cropUnitX;
        var cropBottom = settings.cropBottom() / cropUnitY;
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
    private static void writeVui(MsbBitWriter writer, StreamParameters settings) {
        writer.writeBit(1);
        var sar = settings.sampleAspectRatio().reduce();
        var idc = EXTENDED_SAR;
        for (var i = 0; i < ASPECT_RATIOS.length; i++) {
            if (ASPECT_RATIOS[i][0] == sar.numerator() && ASPECT_RATIOS[i][1] == sar.denominator()) {
                idc = i + 1;
                break;
            }
        }
        writer.write(idc, 8);
        if (idc == EXTENDED_SAR) {
            writer.write((int) sar.numerator(), 16);
            writer.write((int) sar.denominator(), 16);
        }
        writer.writeBit(0);
        writer.writeBit(1);
        writer.write(5, 3);
        var color = settings.color();
        writer.writeBit(color.range() == ColorRange.FULL ? 1 : 0);
        writer.writeBit(1);
        writer.write(color.primaries().code(), 8);
        writer.write(color.transfer().code(), 8);
        writer.write(color.matrix().code(), 8);
        var location = color.chromaSiting().chromaSampleLocType();
        if (location.isPresent() && settings.chromaFormatIdc() == 1) {
            writer.writeBit(1);
            writer.writeUe(location.getAsInt());
            writer.writeUe(location.getAsInt());
        } else {
            writer.writeBit(0);
        }
        writer.writeBit(1);
        writer.write((int) settings.numUnitsInTick(), 32);
        writer.write((int) settings.timeScale(), 32);
        writer.writeBit(settings.fixedFrameRate() ? 1 : 0);
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
    static byte[] pps(StreamParameters settings) {
        var writer = new MsbBitWriter(32);
        writer.writeUe(0);
        writer.writeUe(0);
        writer.writeBit(settings.cabac() ? 1 : 0);
        writer.writeBit(settings.bottomFieldPicOrder() ? 1 : 0);
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
     * Returns the SEI payload of the messages every IDR access unit carries: the mastering display
     * color volume and the content light level of the color description, when present.
     *
     * @param color the stream color description
     * @return the raw byte sequence payload of the SEI NAL unit, or {@code null} when the color
     * description carries neither
     */
    static byte[] staticSei(ColorInfo color) {
        if (color.masteringDisplay().isEmpty() && color.contentLightLevel().isEmpty()) {
            return null;
        }
        var writer = new MsbBitWriter(64);
        color.masteringDisplay().ifPresent(display -> {
            var payload = new MsbBitWriter(24);
            // H.264 D.2.29 orders the primaries green, blue, red, in increments of 0.00002, and the
            // luminances in increments of 0.0001 candelas per square meter.
            for (var point : new Chromaticity[]{display.green(), display.blue(), display.red(), display.whitePoint()}) {
                payload.write(chromaticityCode(point.x()), 16);
                payload.write(chromaticityCode(point.y()), 16);
            }
            payload.writeLong(luminanceCode(display.maxLuminance()), 32);
            payload.writeLong(luminanceCode(display.minLuminance()), 32);
            writeMessage(writer, SEI_MASTERING_DISPLAY, payload);
        });
        color.contentLightLevel().ifPresent(level -> {
            var payload = new MsbBitWriter(4);
            payload.write(level.maxContentLightLevel(), 16);
            payload.write(level.maxFrameAverageLightLevel(), 16);
            writeMessage(writer, SEI_CONTENT_LIGHT_LEVEL, payload);
        });
        writer.writeTrailingBits();
        return writer.toByteArray();
    }

    /**
     * Returns the 16-bit code of a chromaticity coordinate in increments of 0.00002.
     *
     * @param value the coordinate
     * @return the code, clipped to 50000 as D.2.29 requires
     */
    private static int chromaticityCode(double value) {
        return (int) Math.clamp(Math.round(value / 0.00002), 0, 50_000);
    }

    /**
     * Returns the 32-bit code of a luminance in increments of 0.0001 candelas per square meter.
     *
     * @param value the luminance
     * @return the code, clipped to the 32-bit range
     */
    private static long luminanceCode(double value) {
        return Math.clamp(Math.round(value / 0.0001), 0, 0xFFFF_FFFFL);
    }

    /**
     * Returns the SEI payload of a recovery point message (ITU-T H.264 D.1.8) with an exact match.
     *
     * @param recoveryFrames the {@code recovery_frame_cnt}
     * @return the raw byte sequence payload of the SEI NAL unit
     */
    static byte[] recoveryPointSei(int recoveryFrames) {
        var payload = new MsbBitWriter(8);
        payload.writeUe(recoveryFrames);
        payload.writeBit(1);
        payload.writeBit(0);
        payload.write(0, 2);
        if (!payload.isByteAligned()) {
            payload.writeBit(1);
            payload.alignWithZeros();
        }
        var writer = new MsbBitWriter(16);
        writeMessage(writer, SEI_RECOVERY_POINT, payload);
        writer.writeTrailingBits();
        return writer.toByteArray();
    }

    /**
     * Appends one SEI message: its type, its size and its byte-aligned payload.
     *
     * @param writer the SEI payload being built
     * @param type the {@code payloadType}
     * @param payload the message payload, byte-aligned
     */
    private static void writeMessage(MsbBitWriter writer, int type, MsbBitWriter payload) {
        var bytes = payload.toByteArray();
        for (var value = type; ; value -= 255) {
            if (value < 255) {
                writer.write(value, 8);
                break;
            }
            writer.write(255, 8);
        }
        for (var value = bytes.length; ; value -= 255) {
            if (value < 255) {
                writer.write(value, 8);
                break;
            }
            writer.write(255, 8);
        }
        writer.writeBytes(bytes, 0, bytes.length);
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
    static byte[] avcDecoderConfigurationRecord(StreamParameters settings, byte[] sps, byte[] pps) {
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
        if (highSyntax(settings.profileIdc())) {
            out.write(0xFC | settings.chromaFormatIdc());
            out.write(0xF8 | (settings.bitDepth() - 8));
            out.write(0xF8 | (settings.bitDepth() - 8));
            out.write(0);
        }
        return out.toByteArray();
    }
}
