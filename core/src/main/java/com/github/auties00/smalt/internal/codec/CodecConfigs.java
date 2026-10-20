package com.github.auties00.smalt.internal.codec;

import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.ColorRange;

import java.util.Arrays;

/**
 * Derives the out-of-band configuration records muxers store from the in-band headers of the
 * first key frame, for tracks that arrive without one: the AV1 {@code av1C} record from a
 * sequence header OBU, the VP9 and VP8 {@code vpcC} records from a frame header, the MPEG-4
 * Part 2 decoder configuration from the headers before the first picture, and the QuickTime sample
 * entry type of ProRes and DNxHD from the frame header.
 *
 * <p>The headers are parsed by {@link VideoConfigs}, which the probe API shares. Every method
 * returns {@code null} instead of throwing when the bitstream does not hold what it needs, so a
 * caller can fall back to rejecting the track.
 */
public final class CodecConfigs {
    /**
     * The OBU type of a sequence header.
     */
    private static final int OBU_SEQUENCE_HEADER = 1;

    /**
     * The largest picture area of each VP9 level, in luma samples, from the VP9 level
     * definitions; index {@code i} pairs with {@link #VP9_LEVELS}{@code [i]}.
     */
    private static final long[] VP9_LEVEL_AREAS = {36_864, 73_728, 122_880, 245_760, 552_960, 983_040, 2_228_224,
            8_912_896, 35_651_584};

    /**
     * The VP9 level codes ({@code 10 * major + minor}) matching {@link #VP9_LEVEL_AREAS}.
     */
    private static final int[] VP9_LEVELS = {10, 11, 20, 21, 30, 31, 40, 50, 60};

    /**
     * Prevents instantiation.
     */
    private CodecConfigs() {
        throw new AssertionError();
    }

    /**
     * Builds an {@code AV1CodecConfigurationRecord} (AV1 ISOBMFF binding section 2.3) from the
     * first sequence header OBU of a temporal unit.
     *
     * <p>The record's {@code configOBUs} hold that sequence header OBU, rewritten with its size
     * field; the initial presentation delay is not signaled.
     *
     * @param temporalUnit the OBUs of a key frame's temporal unit, each with a size field
     * @return the record, or {@code null} when the unit holds no parsable sequence header
     */
    public static byte[] av1Config(byte[] temporalUnit) {
        var offset = 0;
        while (offset < temporalUnit.length) {
            var header = temporalUnit[offset] & 0xFF;
            var extension = (header & 0x04) != 0 ? 1 : 0;
            if ((header & 0x02) == 0) {
                return null;
            }
            var position = offset + 1 + extension;
            var size = 0L;
            for (var shift = 0; ; shift += 7) {
                if (position >= temporalUnit.length || shift > 49) {
                    return null;
                }
                var b = temporalUnit[position++] & 0xFF;
                size |= (long) (b & 0x7F) << shift;
                if ((b & 0x80) == 0) {
                    break;
                }
            }
            if (size > temporalUnit.length - position) {
                return null;
            }
            if (((header >> 3) & 0x0F) == OBU_SEQUENCE_HEADER) {
                var sequence = VideoConfigs.parseSequenceHeader(temporalUnit, position, (int) size);
                if (sequence == null) {
                    return null;
                }
                var obu = Arrays.copyOfRange(temporalUnit, offset, position + (int) size);
                var result = new byte[4 + obu.length];
                result[0] = (byte) 0x81;
                result[1] = (byte) ((sequence.profile() << 5) | sequence.level());
                result[2] = (byte) ((sequence.tier() << 7) | (sequence.highBitDepth() ? 0x40 : 0)
                        | (sequence.twelveBit() ? 0x20 : 0) | (sequence.monochrome() ? 0x10 : 0)
                        | (sequence.subsamplingX() << 3) | (sequence.subsamplingY() << 2) | sequence.samplePosition());
                System.arraycopy(obu, 0, result, 4, obu.length);
                return result;
            }
            offset = position + (int) size;
        }
        return null;
    }

    /**
     * Builds the body of a {@code VPCodecConfigurationRecord} (VP codec ISOBMFF binding section
     * 2.2, without the full-box version and flags) from the uncompressed header of a VP9 key
     * frame.
     *
     * <p>The profile, bit depth, chroma subsampling and range come from the frame header; the
     * level is the lowest whose largest picture holds the frame size; the color description is
     * the track's when given, otherwise unspecified primaries and transfer with the matrix the
     * header's color space names. No codec initialization data is stored.
     *
     * @param frame the first frame of the track, a key frame
     * @param width the picture width, or {@code 0} when unknown
     * @param height the picture height, or {@code 0} when unknown
     * @param color the color description of the track, or {@code null}
     * @return the record body, or {@code null} when the frame is not a VP9 key frame
     */
    public static byte[] vp9Config(byte[] frame, int width, int height, ColorInfo color) {
        var header = VideoConfigs.vp9KeyFrame(frame);
        if (header == null) {
            return null;
        }
        var subsamplingX = header.subsamplingX();
        var subsamplingY = header.subsamplingY();
        var chroma = subsamplingX == 1 && subsamplingY == 1 ? 1 : subsamplingX == 1 ? 2 : 3;
        var level = vpLevel(width, height);
        var fullRange = header.fullRange();
        int primaries;
        int transfer;
        int matrix;
        if (color != null) {
            primaries = color.primaries().code();
            transfer = color.transfer().code();
            matrix = color.matrix().code();
            fullRange = color.range() == ColorRange.FULL;
        } else {
            primaries = 2;
            transfer = 2;
            matrix = VideoConfigs.vp9Matrix(header.colorSpace());
        }
        return new byte[]{
                (byte) header.profile(),
                (byte) level,
                (byte) ((header.bitDepth() << 4) | (chroma << 1) | (fullRange ? 1 : 0)),
                (byte) primaries,
                (byte) transfer,
                (byte) matrix,
                0,
                0
        };
    }

    /**
     * Returns the lowest VP9 level whose largest picture holds a frame size, the level the VP codec
     * binding stores for VP8 and VP9 alike.
     *
     * @param width the picture width, or {@code 0} when unknown
     * @param height the picture height, or {@code 0} when unknown
     * @return the level code, {@code 10 * major + minor}
     */
    private static int vpLevel(int width, int height) {
        var area = (long) width * height;
        for (var i = 0; i < VP9_LEVEL_AREAS.length; i++) {
            if (area <= VP9_LEVEL_AREAS[i]) {
                return VP9_LEVELS[i];
            }
        }
        return VP9_LEVELS[VP9_LEVELS.length - 1];
    }

    /**
     * Builds the body of a {@code VPCodecConfigurationRecord} (VP codec ISOBMFF binding section
     * 2.2, without the full-box version and flags) from the frame tag of a VP8 key frame (RFC 6386
     * section 9.1).
     *
     * <p>The profile is the frame tag's version; VP8 is always 8-bit 4:2:0. The level is chosen as
     * for VP9, and the color description is the track's when given, otherwise unspecified primaries,
     * transfer and matrix with limited range. No codec initialization data is stored.
     *
     * @param frame the first frame of the track, a key frame
     * @param width the picture width, or {@code 0} to take it from the frame
     * @param height the picture height, or {@code 0} to take it from the frame
     * @param color the color description of the track, or {@code null}
     * @return the record body, or {@code null} when the frame is not a VP8 key frame
     */
    public static byte[] vp8Config(byte[] frame, int width, int height, ColorInfo color) {
        if (frame.length < 10 || (frame[0] & 1) != 0 || (frame[3] & 0xFF) != 0x9D || (frame[4] & 0xFF) != 0x01
                || (frame[5] & 0xFF) != 0x2A) {
            return null;
        }
        var profile = (frame[0] >> 1) & 7;
        if (profile > 3) {
            return null;
        }
        if (width <= 0 || height <= 0) {
            width = ((frame[6] & 0xFF) | (frame[7] & 0xFF) << 8) & 0x3FFF;
            height = ((frame[8] & 0xFF) | (frame[9] & 0xFF) << 8) & 0x3FFF;
        }
        var fullRange = color != null && color.range() == ColorRange.FULL;
        return new byte[]{
                (byte) profile,
                (byte) vpLevel(width, height),
                (byte) ((8 << 4) | (1 << 1) | (fullRange ? 1 : 0)),
                (byte) (color == null ? 2 : color.primaries().code()),
                (byte) (color == null ? 2 : color.transfer().code()),
                (byte) (color == null ? 2 : color.matrix().code()),
                0,
                0
        };
    }

    /**
     * Extracts the MPEG-4 Part 2 decoder configuration (ISO/IEC 14496-2 clause 6.2: the visual
     * object sequence, visual object and video object layer headers) that a key frame carries in
     * band, as the {@code DecoderSpecificInfo} of an {@code esds} box stores it.
     *
     * @param frame the first key frame of the track
     * @return the bytes before the first group of VOP or VOP start code, or {@code null} when the
     * frame does not start with a header holding a video object layer
     */
    public static byte[] mpeg4Config(byte[] frame) {
        var hasLayer = false;
        for (var index = 0; index + 3 < frame.length; index++) {
            if (frame[index] != 0 || frame[index + 1] != 0 || frame[index + 2] != 1) {
                continue;
            }
            var code = frame[index + 3] & 0xFF;
            if (code == 0xB3 || code == 0xB6) {
                return hasLayer && index > 0 ? Arrays.copyOf(frame, index) : null;
            }
            hasLayer |= code >= 0x20 && code <= 0x2F;
            index += 3;
        }
        return null;
    }

    /**
     * Returns the QuickTime sample entry type of a ProRes or DNxHD track: the track's
     * four-character configuration when it has one, otherwise the type its first frame implies,
     * {@code ap4h} for a ProRes 4:4:4 frame and {@code apcn} for any other ProRes frame,
     * {@code AVdh} for a DNxHR frame and {@code AVdn} for any other DNxHD frame.
     *
     * @param prores {@code true} for ProRes, {@code false} for DNxHD
     * @param config the track configuration, or {@code null}
     * @param frame the first bytes of the first frame (ProRes with or without its frame size and
     * {@code icpf} prefix), or {@code null} when unknown
     * @return the sample entry type, four ASCII characters packed big-endian
     */
    public static int quickTimeVisualType(boolean prores, byte[] config, byte[] frame) {
        if (config != null && config.length == 4) {
            return (config[0] & 0xFF) << 24 | (config[1] & 0xFF) << 16 | (config[2] & 0xFF) << 8 | config[3] & 0xFF;
        }
        if (prores) {
            var header = frame != null && frame.length >= 8 && frame[4] == 'i' && frame[5] == 'c' && frame[6] == 'p'
                    && frame[7] == 'f' ? 8 : 0;
            var chroma = frame != null && frame.length > header + 12 ? (frame[header + 12] & 0xFF) >>> 6 : 0;
            return chroma == 3 ? 0x61703468 : 0x6170636E;
        }
        return frame != null && frame.length > 4 && frame[4] >= 3 ? 0x41566468 : 0x4156646E;
    }
}
