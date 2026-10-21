package com.github.auties00.smalt.internal.codec;

import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ChromaSiting;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.ColorPrimaries;
import com.github.auties00.smalt.image.ColorRange;
import com.github.auties00.smalt.image.MatrixCoefficients;
import com.github.auties00.smalt.image.TransferCharacteristics;
import com.github.auties00.smalt.internal.bits.MsbBitReader;
import com.github.auties00.smalt.internal.nal.NalUnits;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.VideoTrack;

import java.util.Arrays;

/**
 * Parses the out-of-band configuration records of video codecs and the sequence-level headers
 * they carry: the H.264 {@code avcC} record and its sequence parameter set, the HEVC
 * {@code hvcC} record, the AV1 {@code av1C} record and sequence header OBU, and the VP9
 * {@code vpcC} record and uncompressed key frame header.
 *
 * <p>Every parser returns {@code null} instead of throwing when the bytes do not hold what it
 * needs, so a caller can fall back to the container's own description. The results feed the
 * muxers' configuration builders ({@link CodecConfigs}) and the probe API.
 */
public final class VideoConfigs {
    /**
     * Prevents instantiation.
     */
    private VideoConfigs() {
        throw new AssertionError();
    }

    /**
     * Describes what a video configuration record states about the stream.
     *
     * @param profile the profile name, as the codec specification and ffprobe spell it, or {@code null} when unknown
     * @param level the level code (H.264 {@code level_idc}, HEVC {@code general_level_idc}, AV1 {@code seq_level_idx},
     * VP9 level), or {@code -1}
     * @param bitDepth the luma bit depth, or {@code 0} when unknown
     * @param chroma the chroma subsampling, or {@code null} when unknown
     * @param color the color description signaled in band, or {@code null}
     * @param width the coded picture width after cropping, or {@code 0}
     * @param height the coded picture height after cropping, or {@code 0}
     * @param sampleAspect the sample aspect ratio, or {@code null} when not signaled
     * @param frameRate the frame rate from the timing information, or {@code null}
     */
    public record Info(String profile, int level, int bitDepth, ChromaFormat chroma, ColorInfo color, int width,
                       int height, Rational sampleAspect, Rational frameRate) {
    }

    /**
     * Describes an AV1 sequence header (AV1 specification section 5.5).
     *
     * @param profile {@code seq_profile}
     * @param level {@code seq_level_idx} of operating point 0
     * @param tier {@code seq_tier} of operating point 0
     * @param highBitDepth {@code high_bitdepth}
     * @param twelveBit {@code twelve_bit}
     * @param monochrome {@code mono_chrome}
     * @param subsamplingX {@code subsampling_x}
     * @param subsamplingY {@code subsampling_y}
     * @param samplePosition {@code chroma_sample_position}
     * @param primaries {@code color_primaries}
     * @param transfer {@code transfer_characteristics}
     * @param matrix {@code matrix_coefficients}
     * @param fullRange {@code color_range}
     * @param maxWidth {@code max_frame_width_minus_1 + 1}
     * @param maxHeight {@code max_frame_height_minus_1 + 1}
     */
    public record Av1SequenceHeader(int profile, int level, int tier, boolean highBitDepth, boolean twelveBit,
                                    boolean monochrome, int subsamplingX, int subsamplingY, int samplePosition,
                                    int primaries, int transfer, int matrix, boolean fullRange, int maxWidth,
                                    int maxHeight) {
        /**
         * Returns the bit depth.
         *
         * @return 8, 10 or 12
         */
        public int bitDepth() {
            return twelveBit ? 12 : highBitDepth ? 10 : 8;
        }
    }

    /**
     * Describes the uncompressed header of a VP9 key frame (VP9 bitstream specification section
     * 6.2).
     *
     * @param profile the profile, 0 to 3
     * @param bitDepth 8, 10 or 12
     * @param colorSpace {@code color_space}
     * @param fullRange {@code color_range}
     * @param subsamplingX {@code subsampling_x}
     * @param subsamplingY {@code subsampling_y}
     * @param width the frame width
     * @param height the frame height
     */
    public record Vp9KeyFrame(int profile, int bitDepth, int colorSpace, boolean fullRange, int subsamplingX,
                              int subsamplingY, int width, int height) {
    }

    /**
     * Returns the sample aspect ratio a player applies to a video track: the one the track
     * declares, else the one its configuration record signals.
     *
     * @param track the video track whose container declaration and configuration record are read
     * @return the ratio in lowest terms, or {@code null} when neither declares one
     */
    public static Rational sampleAspect(VideoTrack track) {
        var declared = track.sampleAspectRatio();
        if (declared.isPresent()) {
            return declared.get();
        }
        var info = describe(track.codec(), track.codecConfig().orElse(null));
        return info == null || info.sampleAspect() == null ? null : info.sampleAspect().reduce();
    }

    /**
     * Describes the configuration record of a video track.
     *
     * @param codec the codec of the track
     * @param config the record as {@link com.github.auties00.smalt.track.Track#codecConfig()} holds it
     * @return the description, or {@code null} for a codec without a parser or an unparsable record
     */
    public static Info describe(Codec codec, byte[] config) {
        if (config == null || config.length == 0) {
            return null;
        }
        try {
            return switch (codec) {
                case H264 -> avc(config);
                case HEVC -> hevc(config);
                case AV1 -> av1(config);
                case VP9 -> vp9(config);
                case VP8 -> new Info("0", -1, 8, ChromaFormat.YUV420, null, 0, 0, null, null);
                default -> null;
            };
        } catch (RuntimeException exception) {
            return null;
        }
    }

    /**
     * Parses an {@code AVCDecoderConfigurationRecord} (ISO/IEC 14496-15 section 5.3.3) and its
     * first sequence parameter set.
     *
     * @param config the record
     * @return the description, or {@code null}
     */
    static Info avc(byte[] config) {
        if (config.length < 7 || config[0] != 1) {
            return null;
        }
        var count = config[5] & 0x1F;
        if (count == 0) {
            var profile = config[1] & 0xFF;
            return new Info(avcProfile(profile, config[2] & 0xFF), config[3] & 0xFF, 8, ChromaFormat.YUV420, null,
                    0, 0, null, null);
        }
        var length = ((config[6] & 0xFF) << 8) | (config[7] & 0xFF);
        if (8 + length > config.length || length < 4) {
            return null;
        }
        return avcSps(Arrays.copyOfRange(config, 8, 8 + length));
    }

    /**
     * Parses an H.264 sequence parameter set NAL unit (ITU-T H.264 section 7.3.2.1.1).
     *
     * @param nal the NAL unit, header byte included, with emulation prevention bytes
     * @return the description, or {@code null}
     */
    public static Info avcSps(byte[] nal) {
        var rbsp = unescape(nal, 1);
        var bits = new MsbBitReader(rbsp);
        var profileIdc = bits.read(8);
        var constraints = bits.read(8);
        var levelIdc = bits.read(8);
        bits.readUe();
        var chromaFormatIdc = 1;
        var bitDepth = 8;
        if (profileIdc == 100 || profileIdc == 110 || profileIdc == 122 || profileIdc == 244 || profileIdc == 44
                || profileIdc == 83 || profileIdc == 86 || profileIdc == 118 || profileIdc == 128 || profileIdc == 138
                || profileIdc == 139 || profileIdc == 134 || profileIdc == 135) {
            chromaFormatIdc = bits.readUe();
            if (chromaFormatIdc == 3) {
                bits.readFlag();
            }
            bitDepth = bits.readUe() + 8;
            bits.readUe();
            bits.readFlag();
            if (bits.readFlag()) {
                for (var index = 0; index < (chromaFormatIdc != 3 ? 8 : 12); index++) {
                    if (bits.readFlag()) {
                        skipScalingList(bits, index < 6 ? 16 : 64);
                    }
                }
            }
        }
        bits.readUe();
        var pocType = bits.readUe();
        if (pocType == 0) {
            bits.readUe();
        } else if (pocType == 1) {
            bits.readFlag();
            bits.readSe();
            bits.readSe();
            var cycle = bits.readUe();
            if (cycle > 255) {
                return null;
            }
            for (var index = 0; index < cycle; index++) {
                bits.readSe();
            }
        }
        bits.readUe();
        bits.readFlag();
        var widthMbs = bits.readUe() + 1;
        var heightUnits = bits.readUe() + 1;
        var frameMbsOnly = bits.readFlag();
        if (!frameMbsOnly) {
            bits.readFlag();
        }
        bits.readFlag();
        var width = widthMbs * 16;
        var height = heightUnits * 16 * (frameMbsOnly ? 1 : 2);
        if (bits.readFlag()) {
            var left = bits.readUe();
            var right = bits.readUe();
            var top = bits.readUe();
            var bottom = bits.readUe();
            var cropX = chromaFormatIdc == 1 || chromaFormatIdc == 2 ? 2 : 1;
            var cropY = (chromaFormatIdc == 1 ? 2 : 1) * (frameMbsOnly ? 1 : 2);
            width -= (left + right) * cropX;
            height -= (top + bottom) * cropY;
        }
        Rational sar = null;
        ColorInfo color = null;
        Rational frameRate = null;
        if (bits.readFlag()) {
            if (bits.readFlag()) {
                var idc = bits.read(8);
                if (idc == 255) {
                    var w = bits.read(16);
                    var h = bits.read(16);
                    sar = w > 0 && h > 0 ? new Rational(w, h).reduce() : null;
                } else if (idc > 0 && idc < SAR_TABLE.length) {
                    sar = new Rational(SAR_TABLE[idc][0], SAR_TABLE[idc][1]);
                }
            }
            if (bits.readFlag()) {
                bits.readFlag();
            }
            var fullRange = false;
            var primaries = 2;
            var transfer = 2;
            var matrix = 2;
            var signaled = false;
            if (bits.readFlag()) {
                bits.read(3);
                fullRange = bits.readFlag();
                signaled = true;
                if (bits.readFlag()) {
                    primaries = bits.read(8);
                    transfer = bits.read(8);
                    matrix = bits.read(8);
                }
            }
            var siting = ChromaSiting.UNSPECIFIED;
            if (bits.readFlag()) {
                siting = ChromaSiting.of(bits.readUe());
                bits.readUe();
                signaled = true;
            }
            if (signaled) {
                color = new ColorInfo(MatrixCoefficients.of(matrix), fullRange ? ColorRange.FULL : ColorRange.LIMITED,
                        ColorPrimaries.of(primaries), TransferCharacteristics.of(transfer)).withChromaSiting(siting);
            }
            if (bits.readFlag()) {
                var units = bits.readLong(32);
                var scale = bits.readLong(32);
                if (units > 0 && scale > 0) {
                    frameRate = new Rational(scale, 2 * units).reduce();
                }
            }
        }
        if (bits.overrun()) {
            return null;
        }
        var chroma = switch (chromaFormatIdc) {
            case 0 -> ChromaFormat.MONOCHROME;
            case 2 -> ChromaFormat.YUV422;
            case 3 -> ChromaFormat.YUV444;
            default -> ChromaFormat.YUV420;
        };
        return new Info(avcProfile(profileIdc, constraints), levelIdc, bitDepth, chroma, color, Math.max(0, width),
                Math.max(0, height), sar, frameRate);
    }

    /**
     * The sample aspect ratios of the H.264 and HEVC {@code aspect_ratio_idc} values 1 to 16
     * (ITU-T H.264 table E-1).
     */
    private static final int[][] SAR_TABLE = {{0, 0}, {1, 1}, {12, 11}, {10, 11}, {16, 11}, {40, 33}, {24, 11},
            {20, 11}, {32, 11}, {80, 33}, {18, 11}, {15, 11}, {64, 33}, {160, 99}, {4, 3}, {3, 2}, {2, 1}};

    /**
     * Skips one scaling list of a sequence parameter set.
     *
     * @param bits the reader
     * @param size the number of coefficients
     */
    private static void skipScalingList(MsbBitReader bits, int size) {
        var last = 8;
        var next = 8;
        for (var index = 0; index < size; index++) {
            if (next != 0) {
                next = (last + bits.readSe() + 256) % 256;
            }
            last = next == 0 ? last : next;
        }
    }

    /**
     * Names an H.264 profile the way ffprobe does.
     *
     * @param idc {@code profile_idc}
     * @param constraints the constraint flags byte
     * @return the name
     */
    static String avcProfile(int idc, int constraints) {
        var set1 = (constraints & 0x40) != 0;
        var set3 = (constraints & 0x10) != 0;
        return switch (idc) {
            case 66 -> set1 ? "Constrained Baseline" : "Baseline";
            case 77 -> "Main";
            case 88 -> "Extended";
            case 100 -> "High";
            case 110 -> set3 ? "High 10 Intra" : "High 10";
            case 122 -> set3 ? "High 4:2:2 Intra" : "High 4:2:2";
            case 244 -> set3 ? "High 4:4:4 Intra" : "High 4:4:4 Predictive";
            case 44 -> "CAVLC 4:4:4";
            case 118 -> "Multiview High";
            case 128 -> "Stereo High";
            default -> Integer.toString(idc);
        };
    }

    /**
     * Removes the emulation prevention bytes of a NAL unit.
     *
     * @param nal the NAL unit
     * @param offset the index of the first payload byte, after the NAL header, non-negative
     * @return the raw byte sequence payload, empty when {@code offset} is past the unit
     */
    static byte[] unescape(byte[] nal, int offset) {
        return offset >= nal.length ? new byte[0] : NalUnits.unescape(nal, offset, nal.length - offset);
    }

    /**
     * Parses an {@code HEVCDecoderConfigurationRecord} (ISO/IEC 14496-15 section 8.3.3).
     *
     * @param config the record
     * @return the description, or {@code null}
     */
    static Info hevc(byte[] config) {
        if (config.length < 23) {
            return null;
        }
        var profileIdc = config[1] & 0x1F;
        var compatibility = ((config[2] & 0xFFL) << 24) | ((config[3] & 0xFFL) << 16) | ((config[4] & 0xFFL) << 8)
                | (config[5] & 0xFFL);
        var level = config[12] & 0xFF;
        var chromaIdc = config[16] & 0x03;
        var bitDepth = (config[17] & 0x07) + 8;
        if (profileIdc == 0) {
            for (var candidate = 1; candidate < 32; candidate++) {
                if ((compatibility & (1L << (31 - candidate))) != 0) {
                    profileIdc = candidate;
                    break;
                }
            }
        }
        var profile = hevcProfile(profileIdc);
        var chroma = switch (chromaIdc) {
            case 0 -> ChromaFormat.MONOCHROME;
            case 2 -> ChromaFormat.YUV422;
            case 3 -> ChromaFormat.YUV444;
            default -> ChromaFormat.YUV420;
        };
        var sps = hevcSpsOf(config);
        if (sps != null) {
            return new Info(profile, level, bitDepth, chroma, sps.color(), sps.width(), sps.height(), sps.sampleAspect(),
                    sps.frameRate());
        }
        return new Info(profile, level, bitDepth, chroma, null, 0, 0, null, null);
    }

    /**
     * Finds and parses the first sequence parameter set of an {@code hvcC} record's arrays.
     *
     * @param config the record
     * @return the description of the sequence parameter set, or {@code null} when none parses
     */
    private static Info hevcSpsOf(byte[] config) {
        var arrays = config[22] & 0xFF;
        var position = 23;
        for (var array = 0; array < arrays && position + 3 <= config.length; array++) {
            var type = config[position] & 0x3F;
            var count = ((config[position + 1] & 0xFF) << 8) | (config[position + 2] & 0xFF);
            position += 3;
            for (var unit = 0; unit < count && position + 2 <= config.length; unit++) {
                var length = ((config[position] & 0xFF) << 8) | (config[position + 1] & 0xFF);
                position += 2;
                if (position + length > config.length) {
                    return null;
                }
                if (type == 33 && length > 2) {
                    try {
                        return hevcSps(Arrays.copyOfRange(config, position, position + length));
                    } catch (RuntimeException exception) {
                        return null;
                    }
                }
                position += length;
            }
        }
        return null;
    }

    /**
     * Parses an HEVC sequence parameter set NAL unit (ITU-T H.265 section 7.3.2.2) up to its VUI
     * color, aspect and timing fields.
     *
     * @param nal the NAL unit, two-byte header included, with emulation prevention bytes
     * @return the description, or {@code null} when the unit is truncated
     */
    public static Info hevcSps(byte[] nal) {
        var bits = new MsbBitReader(unescape(nal, 2));
        bits.read(4);
        var maxSubLayersMinus1 = bits.read(3);
        bits.read(1);
        bits.read(2);
        bits.read(1);
        var profileIdc = bits.read(5);
        bits.skip(32 + 48);
        var levelIdc = bits.read(8);
        var profilePresent = new boolean[8];
        var levelPresent = new boolean[8];
        for (var index = 0; index < maxSubLayersMinus1; index++) {
            profilePresent[index] = bits.readFlag();
            levelPresent[index] = bits.readFlag();
        }
        if (maxSubLayersMinus1 > 0) {
            bits.skip(2L * (8 - maxSubLayersMinus1));
        }
        for (var index = 0; index < maxSubLayersMinus1; index++) {
            if (profilePresent[index]) {
                bits.skip(88);
            }
            if (levelPresent[index]) {
                bits.skip(8);
            }
        }
        bits.readUe();
        var chromaFormatIdc = bits.readUe();
        if (chromaFormatIdc == 3) {
            bits.readFlag();
        }
        var width = bits.readUe();
        var height = bits.readUe();
        if (bits.readFlag()) {
            var subWidth = chromaFormatIdc == 1 || chromaFormatIdc == 2 ? 2 : 1;
            var subHeight = chromaFormatIdc == 1 ? 2 : 1;
            var left = bits.readUe();
            var right = bits.readUe();
            var top = bits.readUe();
            var bottom = bits.readUe();
            width -= subWidth * (left + right);
            height -= subHeight * (top + bottom);
        }
        var bitDepth = bits.readUe() + 8;
        bits.readUe();
        var pocBits = bits.readUe() + 4;
        var orderingPresent = bits.readFlag();
        for (var index = orderingPresent ? 0 : maxSubLayersMinus1; index <= maxSubLayersMinus1; index++) {
            bits.readUe();
            bits.readUe();
            bits.readUe();
        }
        for (var index = 0; index < 6; index++) {
            bits.readUe();
        }
        if (bits.readFlag() && bits.readFlag()) {
            for (var size = 0; size < 4; size++) {
                for (var matrix = 0; matrix < 6; matrix += size == 3 ? 3 : 1) {
                    if (!bits.readFlag()) {
                        bits.readUe();
                    } else {
                        var coefficients = Math.min(64, 1 << (4 + (size << 1)));
                        if (size > 1) {
                            bits.readSe();
                        }
                        for (var index = 0; index < coefficients; index++) {
                            bits.readSe();
                        }
                    }
                }
            }
        }
        bits.readFlag();
        bits.readFlag();
        if (bits.readFlag()) {
            bits.read(4);
            bits.read(4);
            bits.readUe();
            bits.readUe();
            bits.readFlag();
        }
        var sets = bits.readUe();
        if (sets > 64) {
            return null;
        }
        var deltaPocs = new int[sets];
        for (var set = 0; set < sets; set++) {
            if (set != 0 && bits.readFlag()) {
                bits.readFlag();
                bits.readUe();
                var reference = set - 1;
                var count = 0;
                for (var index = 0; index <= deltaPocs[reference]; index++) {
                    var used = bits.readFlag();
                    if (used || bits.readFlag()) {
                        count++;
                    }
                }
                deltaPocs[set] = count;
            } else {
                var negative = bits.readUe();
                var positive = bits.readUe();
                if (negative > 16 || positive > 16) {
                    return null;
                }
                for (var index = 0; index < negative + positive; index++) {
                    bits.readUe();
                    bits.readFlag();
                }
                deltaPocs[set] = negative + positive;
            }
        }
        if (bits.readFlag()) {
            var longTerm = bits.readUe();
            if (longTerm > 32) {
                return null;
            }
            for (var index = 0; index < longTerm; index++) {
                bits.skip(pocBits);
                bits.readFlag();
            }
        }
        bits.readFlag();
        bits.readFlag();
        Rational sar = null;
        ColorInfo color = null;
        Rational frameRate = null;
        if (bits.readFlag()) {
            if (bits.readFlag()) {
                var idc = bits.read(8);
                if (idc == 255) {
                    var w = bits.read(16);
                    var h = bits.read(16);
                    sar = w > 0 && h > 0 ? new Rational(w, h).reduce() : null;
                } else if (idc > 0 && idc < SAR_TABLE.length) {
                    sar = new Rational(SAR_TABLE[idc][0], SAR_TABLE[idc][1]);
                }
            }
            if (bits.readFlag()) {
                bits.readFlag();
            }
            var signaled = false;
            var fullRange = false;
            var primaries = 2;
            var transfer = 2;
            var matrix = 2;
            if (bits.readFlag()) {
                bits.read(3);
                fullRange = bits.readFlag();
                signaled = true;
                if (bits.readFlag()) {
                    primaries = bits.read(8);
                    transfer = bits.read(8);
                    matrix = bits.read(8);
                }
            }
            var siting = ChromaSiting.UNSPECIFIED;
            if (bits.readFlag()) {
                siting = ChromaSiting.of(bits.readUe());
                bits.readUe();
                signaled = true;
            }
            if (signaled) {
                color = new ColorInfo(MatrixCoefficients.of(matrix), fullRange ? ColorRange.FULL : ColorRange.LIMITED,
                        ColorPrimaries.of(primaries), TransferCharacteristics.of(transfer)).withChromaSiting(siting);
            }
            bits.readFlag();
            bits.readFlag();
            bits.readFlag();
            if (bits.readFlag()) {
                bits.readUe();
                bits.readUe();
                bits.readUe();
                bits.readUe();
            }
            if (bits.readFlag()) {
                var units = bits.readLong(32);
                var scale = bits.readLong(32);
                if (units > 0 && scale > 0) {
                    frameRate = new Rational(scale, units).reduce();
                }
            }
        }
        if (bits.overrun()) {
            return null;
        }
        var chroma = switch (chromaFormatIdc) {
            case 0 -> ChromaFormat.MONOCHROME;
            case 2 -> ChromaFormat.YUV422;
            case 3 -> ChromaFormat.YUV444;
            default -> ChromaFormat.YUV420;
        };
        return new Info(hevcProfile(profileIdc), levelIdc, bitDepth, chroma, color, Math.max(0, width), Math.max(0, height),
                sar, frameRate);
    }

    /**
     * Names an HEVC profile the way ffprobe does.
     *
     * @param profileIdc {@code general_profile_idc}
     * @return the name, or {@code null} for zero
     */
    private static String hevcProfile(int profileIdc) {
        return switch (profileIdc) {
            case 0 -> null;
            case 1 -> "Main";
            case 2 -> "Main 10";
            case 3 -> "Main Still Picture";
            case 4 -> "Rext";
            case 9 -> "SCC";
            default -> Integer.toString(profileIdc);
        };
    }

    /**
     * Parses an {@code AV1CodecConfigurationRecord} (AV1 ISOBMFF binding section 2.3), and the
     * sequence header among its configuration OBUs when it holds one.
     *
     * @param config the record
     * @return the description, or {@code null}
     */
    static Info av1(byte[] config) {
        if (config.length < 4 || (config[0] & 0x80) == 0) {
            return null;
        }
        var profile = (config[1] >> 5) & 0x07;
        var level = config[1] & 0x1F;
        var high = (config[2] & 0x40) != 0;
        var twelve = (config[2] & 0x20) != 0;
        var mono = (config[2] & 0x10) != 0;
        var subX = (config[2] >> 3) & 1;
        var subY = (config[2] >> 2) & 1;
        var chroma = mono ? ChromaFormat.MONOCHROME : subX == 1 && subY == 1 ? ChromaFormat.YUV420
                : subX == 1 ? ChromaFormat.YUV422 : ChromaFormat.YUV444;
        ColorInfo color = null;
        var width = 0;
        var height = 0;
        if (config.length > 4) {
            var header = sequenceHeaderOf(Arrays.copyOfRange(config, 4, config.length));
            if (header != null) {
                color = av1Color(header);
                width = header.maxWidth();
                height = header.maxHeight();
            }
        }
        return new Info(av1Profile(profile), level, twelve ? 12 : high ? 10 : 8, chroma, color, width, height, null, null);
    }

    /**
     * Names an AV1 profile the way ffprobe does.
     *
     * @param profile {@code seq_profile}
     * @return the name
     */
    static String av1Profile(int profile) {
        return switch (profile) {
            case 0 -> "Main";
            case 1 -> "High";
            case 2 -> "Professional";
            default -> Integer.toString(profile);
        };
    }

    /**
     * Returns the color description of an AV1 sequence header.
     *
     * @param header the parsed sequence header
     * @return the description
     */
    static ColorInfo av1Color(Av1SequenceHeader header) {
        var siting = header.subsamplingX() == 1 && header.subsamplingY() == 1
                ? switch (header.samplePosition()) {
                    case 1 -> ChromaSiting.LEFT;
                    case 2 -> ChromaSiting.TOP_LEFT;
                    default -> ChromaSiting.UNSPECIFIED;
                }
                : ChromaSiting.UNSPECIFIED;
        return new ColorInfo(MatrixCoefficients.of(header.matrix()), header.fullRange() ? ColorRange.FULL : ColorRange.LIMITED,
                ColorPrimaries.of(header.primaries()), TransferCharacteristics.of(header.transfer())).withChromaSiting(siting);
    }

    /**
     * Finds and parses the first sequence header OBU of a sequence of OBUs.
     *
     * @param obus the OBUs, each with a size field
     * @return the sequence header, or {@code null} when none parses
     */
    public static Av1SequenceHeader sequenceHeaderOf(byte[] obus) {
        var offset = 0;
        while (offset < obus.length) {
            var header = obus[offset] & 0xFF;
            var extension = (header & 0x04) != 0 ? 1 : 0;
            if ((header & 0x02) == 0) {
                return null;
            }
            var position = offset + 1 + extension;
            var size = 0L;
            for (var shift = 0; ; shift += 7) {
                if (position >= obus.length || shift > 49) {
                    return null;
                }
                var b = obus[position++] & 0xFF;
                size |= (long) (b & 0x7F) << shift;
                if ((b & 0x80) == 0) {
                    break;
                }
            }
            if (size > obus.length - position) {
                return null;
            }
            if (((header >> 3) & 0x0F) == 1) {
                return parseSequenceHeader(obus, position, (int) size);
            }
            offset = position + (int) size;
        }
        return null;
    }

    /**
     * Parses the payload of a sequence header OBU (AV1 specification section 5.5).
     *
     * @param data the bytes holding the payload
     * @param offset the index of the payload
     * @param length the payload length
     * @return the header, or {@code null} when the payload is truncated
     */
    public static Av1SequenceHeader parseSequenceHeader(byte[] data, int offset, int length) {
        var bits = new MsbBitReader(data, offset, length);
        var profile = bits.read(3);
        bits.read(1);
        var reduced = bits.readFlag();
        var level = 0;
        var tier = 0;
        if (reduced) {
            level = bits.read(5);
        } else {
            var decoderModel = false;
            var bufferDelayLength = 0;
            if (bits.readFlag()) {
                bits.read(32);
                bits.read(32);
                if (bits.readFlag()) {
                    readUvlc(bits);
                }
                decoderModel = bits.readFlag();
                if (decoderModel) {
                    bufferDelayLength = bits.read(5) + 1;
                    bits.read(32);
                    bits.read(5);
                    bits.read(5);
                }
            }
            var initialDisplayDelay = bits.readFlag();
            var operatingPoints = bits.read(5) + 1;
            for (var i = 0; i < operatingPoints; i++) {
                bits.read(12);
                var opLevel = bits.read(5);
                var opTier = opLevel > 7 ? bits.read(1) : 0;
                if (i == 0) {
                    level = opLevel;
                    tier = opTier;
                }
                if (decoderModel && bits.readFlag()) {
                    bits.skip(2L * bufferDelayLength);
                    bits.read(1);
                }
                if (initialDisplayDelay && bits.readFlag()) {
                    bits.read(4);
                }
            }
        }
        var widthBits = bits.read(4) + 1;
        var heightBits = bits.read(4) + 1;
        var maxWidth = (int) bits.readLong(widthBits) + 1;
        var maxHeight = (int) bits.readLong(heightBits) + 1;
        if (!reduced && bits.readFlag()) {
            bits.read(4);
            bits.read(3);
        }
        bits.read(3);
        if (!reduced) {
            bits.read(4);
            var orderHint = bits.readFlag();
            if (orderHint) {
                bits.read(2);
            }
            var screenContent = bits.readFlag() ? 2 : bits.read(1);
            if (screenContent > 0 && !bits.readFlag()) {
                bits.read(1);
            }
            if (orderHint) {
                bits.read(3);
            }
        }
        bits.read(3);
        var highBitDepth = bits.readFlag();
        var twelveBit = profile == 2 && highBitDepth && bits.readFlag();
        var monochrome = profile != 1 && bits.readFlag();
        var primaries = 2;
        var transfer = 2;
        var matrix = 2;
        if (bits.readFlag()) {
            primaries = bits.read(8);
            transfer = bits.read(8);
            matrix = bits.read(8);
        }
        int subsamplingX;
        int subsamplingY;
        var samplePosition = 0;
        boolean fullRange;
        if (monochrome) {
            fullRange = bits.readFlag();
            subsamplingX = 1;
            subsamplingY = 1;
        } else if (primaries == 1 && transfer == 13 && matrix == 0) {
            fullRange = true;
            subsamplingX = 0;
            subsamplingY = 0;
        } else {
            fullRange = bits.readFlag();
            if (profile == 0) {
                subsamplingX = 1;
                subsamplingY = 1;
            } else if (profile == 1) {
                subsamplingX = 0;
                subsamplingY = 0;
            } else if (twelveBit) {
                subsamplingX = bits.read(1);
                subsamplingY = subsamplingX == 1 ? bits.read(1) : 0;
            } else {
                subsamplingX = 1;
                subsamplingY = 0;
            }
            if (subsamplingX == 1 && subsamplingY == 1) {
                samplePosition = bits.read(2);
            }
        }
        if (bits.overrun()) {
            return null;
        }
        return new Av1SequenceHeader(profile, level, tier, highBitDepth, twelveBit, monochrome, subsamplingX,
                subsamplingY, samplePosition, primaries, transfer, matrix, fullRange, maxWidth, maxHeight);
    }

    /**
     * Reads a variable-length unsigned code, AV1 specification section 4.10.3.
     *
     * @param bits the reader
     * @return the value, saturated to {@code 2^32 - 1}
     */
    private static long readUvlc(MsbBitReader bits) {
        var leadingZeros = 0;
        while (!bits.readFlag()) {
            if (++leadingZeros >= 32 || bits.overrun()) {
                return 0xFFFFFFFFL;
            }
        }
        return bits.readLong(leadingZeros) + (1L << leadingZeros) - 1;
    }

    /**
     * Parses the body of a {@code VPCodecConfigurationRecord} (VP codec ISOBMFF binding section
     * 2.2, without the full-box version and flags), or the feature list Matroska stores as the
     * {@code CodecPrivate} of a VP9 track (WebM codec registry: identifier, length and value
     * triples for the profile, level, bit depth and chroma subsampling).
     *
     * @param config the record body or feature list
     * @return the description, or {@code null}
     */
    static Info vp9(byte[] config) {
        if (config.length % 3 == 0 && config.length > 0 && isFeatureList(config)) {
            var profile = -1;
            var level = -1;
            var bitDepth = 0;
            ChromaFormat chroma = null;
            for (var index = 0; index < config.length; index += 3) {
                var value = config[index + 2] & 0xFF;
                switch (config[index]) {
                    case 1 -> profile = value;
                    case 2 -> level = value;
                    case 3 -> bitDepth = value;
                    case 4 -> chroma = value <= 1 ? ChromaFormat.YUV420 : value == 2 ? ChromaFormat.YUV422 : ChromaFormat.YUV444;
                    default -> {
                    }
                }
            }
            return new Info(profile < 0 ? null : "Profile " + profile, level <= 0 ? -1 : level, bitDepth, chroma, null, 0,
                    0, null, null);
        }
        if (config.length < 6) {
            return null;
        }
        var profile = config[0] & 0xFF;
        var level = config[1] & 0xFF;
        var bitDepth = (config[2] >> 4) & 0x0F;
        var chromaCode = (config[2] >> 1) & 0x07;
        var fullRange = (config[2] & 1) != 0;
        var chroma = switch (chromaCode) {
            case 0, 1 -> ChromaFormat.YUV420;
            case 2 -> ChromaFormat.YUV422;
            case 3 -> ChromaFormat.YUV444;
            default -> null;
        };
        var siting = chromaCode == 0 ? ChromaSiting.LEFT : chromaCode == 1 ? ChromaSiting.CENTER : ChromaSiting.UNSPECIFIED;
        var color = new ColorInfo(MatrixCoefficients.of(config[5] & 0xFF), fullRange ? ColorRange.FULL : ColorRange.LIMITED,
                ColorPrimaries.of(config[3] & 0xFF), TransferCharacteristics.of(config[4] & 0xFF)).withChromaSiting(siting);
        return new Info("Profile " + profile, level == 0 ? -1 : level, bitDepth, chroma, color, 0, 0, null, null);
    }

    /**
     * Returns whether bytes form a Matroska VP9 feature list: identifiers 1 to 4, each with a
     * length of one.
     *
     * @param config the bytes, a multiple of three long
     * @return {@code true} for a feature list
     */
    private static boolean isFeatureList(byte[] config) {
        for (var index = 0; index < config.length; index += 3) {
            if (config[index] < 1 || config[index] > 4 || config[index + 1] != 1) {
                return false;
            }
        }
        return true;
    }

    /**
     * Returns the ITU-T H.273 matrix coefficients that a VP9 {@code color_space} signals.
     *
     * <p>{@code CS_BT_601} maps to BT.470 System B and G (code point 5) and {@code CS_SMPTE_170} to
     * BT.601 (code point 6), the two code points of the same matrix; {@code CS_RGB} maps to the
     * identity matrix. {@code CS_UNKNOWN}, {@code CS_RESERVED} and values outside 0 to 7 map to
     * unspecified (code point 2): a decoder that needs a matrix applies its own fallback.
     *
     * @param colorSpace the {@code color_space} field of the frame header
     * @return the {@code matrix_coefficients} code point
     */
    public static int vp9Matrix(int colorSpace) {
        return switch (colorSpace) {
            case 1 -> 5;
            case 2 -> 1;
            case 3 -> 6;
            case 4 -> 7;
            case 5 -> 9;
            case 7 -> 0;
            default -> 2;
        };
    }

    /**
     * Parses the uncompressed header of a VP9 key frame.
     *
     * @param frame the coded frame
     * @return the header, or {@code null} when the frame is not a VP9 key frame
     */
    public static Vp9KeyFrame vp9KeyFrame(byte[] frame) {
        if (frame.length < 10) {
            return null;
        }
        var bits = new MsbBitReader(frame);
        if (bits.read(2) != 2) {
            return null;
        }
        var profile = bits.read(1);
        profile |= bits.read(1) << 1;
        if (profile == 3) {
            bits.read(1);
        }
        if (bits.readFlag() || bits.readFlag()) {
            return null;
        }
        bits.read(2);
        if (bits.read(24) != 0x498342) {
            return null;
        }
        var bitDepth = profile >= 2 ? (bits.readFlag() ? 12 : 10) : 8;
        var colorSpace = bits.read(3);
        var fullRange = true;
        var subsamplingX = 0;
        var subsamplingY = 0;
        if (colorSpace != 7) {
            fullRange = bits.readFlag();
            if (profile == 1 || profile == 3) {
                subsamplingX = bits.read(1);
                subsamplingY = bits.read(1);
                bits.read(1);
            } else {
                subsamplingX = 1;
                subsamplingY = 1;
            }
        } else if (profile == 1 || profile == 3) {
            bits.read(1);
        }
        var width = bits.read(16) + 1;
        var height = bits.read(16) + 1;
        if (bits.overrun()) {
            return null;
        }
        return new Vp9KeyFrame(profile, bitDepth, colorSpace, fullRange, subsamplingX, subsamplingY, width, height);
    }
}
