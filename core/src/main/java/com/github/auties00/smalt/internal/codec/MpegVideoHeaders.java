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
import com.github.auties00.smalt.track.Rational;

import java.util.Arrays;

/**
 * Parses the sequence-level headers of MPEG-1 (ISO/IEC 11172-2 2.4.2.3) and MPEG-2 (ISO/IEC 13818-2 6.2.2) video
 * streams: the sequence header with its quantizer matrices, the sequence extension, the sequence display extension
 * and the presence of a sequence scalable extension.
 *
 * <p>The parsers return {@code null} instead of throwing when the bytes do not hold a valid header, so that a
 * demuxer can fall back to the container's description and a decoder can report the corruption itself. This class
 * is thread-safe.
 */
public final class MpegVideoHeaders {
    /**
     * The start code value of a picture header.
     */
    public static final int PICTURE_START = 0x00;

    /**
     * The highest start code value of a slice.
     */
    public static final int SLICE_LAST = 0xAF;

    /**
     * The start code value of user data.
     */
    public static final int USER_DATA = 0xB2;

    /**
     * The start code value of a sequence header.
     */
    public static final int SEQUENCE_HEADER = 0xB3;

    /**
     * The start code value of an extension.
     */
    public static final int EXTENSION = 0xB5;

    /**
     * The start code value of the end of a sequence.
     */
    public static final int SEQUENCE_END = 0xB7;

    /**
     * The start code value of a group of pictures header.
     */
    public static final int GROUP = 0xB8;

    /**
     * The {@code extension_start_code_identifier} of a sequence extension.
     */
    public static final int SEQUENCE_EXTENSION_ID = 1;

    /**
     * The {@code extension_start_code_identifier} of a sequence display extension.
     */
    public static final int SEQUENCE_DISPLAY_EXTENSION_ID = 2;

    /**
     * The {@code extension_start_code_identifier} of a sequence scalable extension.
     */
    public static final int SEQUENCE_SCALABLE_EXTENSION_ID = 5;

    /**
     * The picture rates of {@code frame_rate_code} 1 to 8 (ISO/IEC 13818-2 Table 6-4), as numerator and
     * denominator pairs.
     */
    private static final int[][] FRAME_RATES = {{24000, 1001}, {24, 1}, {25, 1}, {30000, 1001}, {30, 1}, {50, 1},
            {60000, 1001}, {60, 1}};

    /**
     * The pel aspect ratios (height over width) of the MPEG-1 {@code pel_aspect_ratio} codes 1 to 14 (ISO/IEC
     * 11172-2 2.4.3.2), in ten-thousandths.
     */
    private static final int[] PEL_ASPECT = {10000, 6735, 7031, 7615, 8055, 8437, 8935, 9157, 9815, 10255, 10695,
            10950, 11575, 12015};

    /**
     * The display aspect ratios of the MPEG-2 {@code aspect_ratio_information} codes 2 to 4 (ISO/IEC 13818-2
     * Table 6-3), as numerator and denominator pairs.
     */
    private static final int[][] DISPLAY_ASPECT = {{4, 3}, {16, 9}, {221, 100}};

    /**
     * The default intra quantizer matrix (ISO/IEC 13818-2 6.3.11), in raster order.
     */
    private static final int[] DEFAULT_INTRA = {
            8, 16, 19, 22, 26, 27, 29, 34,
            16, 16, 22, 24, 27, 29, 34, 37,
            19, 22, 26, 27, 29, 34, 34, 38,
            22, 22, 26, 27, 29, 34, 37, 40,
            22, 26, 27, 29, 32, 35, 40, 48,
            26, 27, 29, 32, 35, 40, 48, 58,
            26, 27, 29, 34, 38, 46, 56, 69,
            27, 29, 35, 38, 46, 56, 69, 83};

    /**
     * The zigzag scan (ISO/IEC 13818-2 Figure 7-2): the raster index of the coefficient at each scan position.
     */
    private static final int[] ZIGZAG = {
            0, 1, 8, 16, 9, 2, 3, 10, 17, 24, 32, 25, 18, 11, 4, 5,
            12, 19, 26, 33, 40, 48, 41, 34, 27, 20, 13, 6, 7, 14, 21, 28,
            35, 42, 49, 56, 57, 50, 43, 36, 29, 22, 15, 23, 30, 37, 44, 51,
            58, 59, 52, 45, 38, 31, 39, 46, 53, 60, 61, 54, 47, 55, 62, 63};

    private MpegVideoHeaders() {
        throw new AssertionError();
    }

    /**
     * Describes the sequence header of a stream and the extensions that follow it.
     *
     * @param mpeg2 whether a sequence extension follows the header, which makes the stream MPEG-2
     * @param width {@code horizontal_size}, with {@code horizontal_size_extension}
     * @param height {@code vertical_size}, with {@code vertical_size_extension}
     * @param aspectCode {@code aspect_ratio_information} (MPEG-2) or {@code pel_aspect_ratio} (MPEG-1)
     * @param frameRateCode {@code frame_rate_code}
     * @param frameRateNumerator {@code frame_rate_extension_n}, 0 for MPEG-1
     * @param frameRateDenominator {@code frame_rate_extension_d}, 0 for MPEG-1
     * @param bitRate {@code bit_rate_value} with {@code bit_rate_extension}, in units of 400 bit/s
     * @param vbvBufferSize {@code vbv_buffer_size_value} with {@code vbv_buffer_size_extension}, in units of
     * 16 kibibits
     * @param intraMatrix the loaded intra quantizer matrix in raster order, or {@code null} for the default
     * @param nonIntraMatrix the loaded non-intra quantizer matrix in raster order, or {@code null} for the flat
     * default
     * @param profileAndLevel {@code profile_and_level_indication}, or -1 for MPEG-1
     * @param progressive {@code progressive_sequence}; {@code true} for MPEG-1
     * @param chromaFormat {@code chroma_format}: 1 for 4:2:0 (always for MPEG-1), 2 for 4:2:2, 3 for 4:4:4
     * @param lowDelay {@code low_delay}
     * @param videoFormat {@code video_format}, or -1 without a sequence display extension
     * @param colorPrimaries {@code colour_primaries}, or -1 when not described
     * @param transferCharacteristics {@code transfer_characteristics}, or -1 when not described
     * @param matrixCoefficients {@code matrix_coefficients}, or -1 when not described
     * @param displayWidth {@code display_horizontal_size}, or 0 without a sequence display extension
     * @param displayHeight {@code display_vertical_size}, or 0 without a sequence display extension
     * @param scalableMode {@code scalable_mode} of a sequence scalable extension, or -1 without one
     * @param end the index of the first start code after the header and its extensions and user data
     */
    public record Sequence(boolean mpeg2, int width, int height, int aspectCode, int frameRateCode,
                           int frameRateNumerator, int frameRateDenominator, long bitRate, int vbvBufferSize,
                           int[] intraMatrix, int[] nonIntraMatrix, int profileAndLevel, boolean progressive,
                           int chromaFormat, boolean lowDelay, int videoFormat, int colorPrimaries,
                           int transferCharacteristics, int matrixCoefficients, int displayWidth, int displayHeight,
                           int scalableMode, int end) {
        /**
         * Returns the picture rate the header signals.
         *
         * @return the frames per second, or {@code null} for a reserved {@code frame_rate_code}
         */
        public Rational frameRate() {
            if (frameRateCode < 1 || frameRateCode > FRAME_RATES.length) {
                return null;
            }
            var rate = FRAME_RATES[frameRateCode - 1];
            return new Rational((long) rate[0] * (frameRateNumerator + 1),
                    (long) rate[1] * (frameRateDenominator + 1)).reduce();
        }

        /**
         * Returns the sample aspect ratio the header signals.
         *
         * <p>For MPEG-2 it is the display aspect ratio of {@code aspect_ratio_information} over the display size
         * of the sequence display extension, or over the picture size without one (ISO/IEC 13818-2 6.3.3); for
         * MPEG-1 it is the inverse of the pel aspect ratio (ISO/IEC 11172-2 2.4.3.2), the closest fraction with terms
         * up to 255.
         *
         * @return the ratio in lowest terms, or {@code null} for a forbidden or reserved code
         */
        public Rational sampleAspect() {
            if (!mpeg2) {
                if (aspectCode < 1 || aspectCode > PEL_ASPECT.length) {
                    return null;
                }
                return approximate(10000, PEL_ASPECT[aspectCode - 1], 255);
            }
            if (aspectCode == 1) {
                return new Rational(1, 1);
            }
            if (aspectCode < 2 || aspectCode > 4) {
                return null;
            }
            var dar = DISPLAY_ASPECT[aspectCode - 2];
            var displayW = displayWidth > 0 && displayHeight > 0 ? displayWidth : width;
            var displayH = displayWidth > 0 && displayHeight > 0 ? displayHeight : height;
            if (displayW <= 0 || displayH <= 0) {
                return null;
            }
            return new Rational((long) dar[0] * displayH, (long) dar[1] * displayW).reduce();
        }

        /**
         * Returns the chroma subsampling of the pictures.
         *
         * @return {@link ChromaFormat#YUV420}, {@link ChromaFormat#YUV422} or {@link ChromaFormat#YUV444}
         */
        public ChromaFormat chroma() {
            return switch (chromaFormat) {
                case 2 -> ChromaFormat.YUV422;
                case 3 -> ChromaFormat.YUV444;
                default -> ChromaFormat.YUV420;
            };
        }

        /**
         * Returns the color description of the sequence display extension.
         *
         * @return the description, or {@code null} when the stream carries none
         */
        public ColorInfo signaledColor() {
            if (colorPrimaries < 0) {
                return null;
            }
            return new ColorInfo(MatrixCoefficients.of(matrixCoefficients), ColorRange.LIMITED,
                    ColorPrimaries.of(colorPrimaries), TransferCharacteristics.of(transferCharacteristics))
                    .withChromaSiting(siting());
        }

        /**
         * Returns the color of the decoded pictures: the signaled description, its unspecified components and an
         * absent description taken as BT.601 for pictures up to 1024 by 576 and BT.709 for larger ones, in limited
         * range, with the chroma siting of the format.
         *
         * @return the description
         */
        public ColorInfo color() {
            var fallback = width > 1024 || height > 576 ? ColorInfo.BT709_LIMITED : ColorInfo.BT601_LIMITED;
            var matrix = fallback.matrix();
            var primaries = fallback.primaries();
            var transfer = fallback.transfer();
            if (matrixCoefficients >= 0 && MatrixCoefficients.of(matrixCoefficients) != MatrixCoefficients.UNSPECIFIED) {
                matrix = MatrixCoefficients.of(matrixCoefficients);
            }
            if (colorPrimaries >= 0 && ColorPrimaries.of(colorPrimaries) != ColorPrimaries.UNSPECIFIED) {
                primaries = ColorPrimaries.of(colorPrimaries);
            }
            if (transferCharacteristics >= 0
                    && TransferCharacteristics.of(transferCharacteristics) != TransferCharacteristics.UNSPECIFIED) {
                transfer = TransferCharacteristics.of(transferCharacteristics);
            }
            return new ColorInfo(matrix, ColorRange.LIMITED, primaries, transfer).withChromaSiting(siting());
        }

        /**
         * Returns where the chroma samples sit: between the luma samples for MPEG-1, co-sited with the left luma
         * sample of each pair for MPEG-2 (ISO/IEC 13818-2 Figures 6-1 to 6-3).
         *
         * @return the siting
         */
        public ChromaSiting siting() {
            return mpeg2 ? ChromaSiting.LEFT : ChromaSiting.CENTER;
        }

        /**
         * Returns the profile name of {@code profile_and_level_indication}, as ffprobe spells it.
         *
         * @return the name, or {@code null} for MPEG-1 and unknown profiles
         */
        public String profile() {
            if (profileAndLevel < 0) {
                return null;
            }
            if ((profileAndLevel & 0x80) != 0) {
                return switch (profileAndLevel & 0x0F) {
                    case 2, 5 -> "4:2:2";
                    case 0x0A, 0x0B, 0x0D, 0x0E -> "Multiview";
                    default -> null;
                };
            }
            return switch (profileAndLevel >> 4 & 7) {
                case 1 -> "High";
                case 2 -> "Spatially Scalable";
                case 3 -> "SNR Scalable";
                case 4 -> "Main";
                case 5 -> "Simple";
                default -> null;
            };
        }

        /**
         * Returns the level code of {@code profile_and_level_indication}, its low four bits.
         *
         * @return the level, or -1 for MPEG-1
         */
        public int level() {
            return profileAndLevel < 0 ? -1 : profileAndLevel & 0x0F;
        }

        /**
         * Returns the intra quantizer matrix in force after the header.
         *
         * @return the 64 weights in raster order, a new array
         */
        public int[] intraWeights() {
            return intraMatrix == null ? DEFAULT_INTRA.clone() : intraMatrix.clone();
        }

        /**
         * Returns the non-intra quantizer matrix in force after the header.
         *
         * @return the 64 weights in raster order, a new array
         */
        public int[] nonIntraWeights() {
            if (nonIntraMatrix != null) {
                return nonIntraMatrix.clone();
            }
            var flat = new int[64];
            Arrays.fill(flat, 16);
            return flat;
        }

        /**
         * Returns whether another header codes the same sequence parameters, which a stream repeats before
         * groups of pictures without starting a new sequence.
         *
         * @param other the header to compare
         * @return {@code true} when every field but {@link #end()} is equal
         */
        public boolean sameAs(Sequence other) {
            return other != null && mpeg2 == other.mpeg2 && width == other.width && height == other.height
                    && aspectCode == other.aspectCode && frameRateCode == other.frameRateCode
                    && frameRateNumerator == other.frameRateNumerator
                    && frameRateDenominator == other.frameRateDenominator
                    && Arrays.equals(intraMatrix, other.intraMatrix)
                    && Arrays.equals(nonIntraMatrix, other.nonIntraMatrix)
                    && profileAndLevel == other.profileAndLevel && progressive == other.progressive
                    && chromaFormat == other.chromaFormat && lowDelay == other.lowDelay
                    && colorPrimaries == other.colorPrimaries
                    && transferCharacteristics == other.transferCharacteristics
                    && matrixCoefficients == other.matrixCoefficients && displayWidth == other.displayWidth
                    && displayHeight == other.displayHeight && scalableMode == other.scalableMode;
        }
    }

    /**
     * Returns the default intra quantizer matrix.
     *
     * @return the 64 weights in raster order, a new array
     */
    public static int[] defaultIntraMatrix() {
        return DEFAULT_INTRA.clone();
    }

    /**
     * Returns the zigzag scan.
     *
     * @return the raster index of the coefficient at each of the 64 scan positions, a new array
     */
    public static int[] zigzag() {
        return ZIGZAG.clone();
    }

    /**
     * Parses the first sequence header of a stream and the extensions that follow it.
     *
     * @param data the elementary stream bytes
     * @return the description, or {@code null} when no valid sequence header is found
     */
    public static Sequence find(byte[] data) {
        var position = NalUnits.findStartCode(data, 0, data.length);
        while (position >= 0 && position + 3 < data.length) {
            if ((data[position + 3] & 0xFF) == SEQUENCE_HEADER) {
                return parse(data, position, data.length);
            }
            position = NalUnits.findStartCode(data, position + 4, data.length);
        }
        return null;
    }

    /**
     * Parses a sequence header and the extensions and user data that follow it.
     *
     * @param data the elementary stream bytes
     * @param position the index of the sequence header's start code prefix
     * @param end the index after the last byte to read
     * @return the description, or {@code null} when the header is truncated or invalid: a zero size, a zero
     * quantizer weight, or a sequence extension that is truncated or names chroma format 0
     */
    public static Sequence parse(byte[] data, int position, int end) {
        if (position < 0 || position + 12 > end || (data[position + 3] & 0xFF) != SEQUENCE_HEADER) {
            return null;
        }
        var next = NalUnits.findStartCode(data, position + 4, end);
        var headerEnd = next < 0 ? end : next;
        var bits = new MsbBitReader(data, position + 4, headerEnd - position - 4);
        var width = bits.read(12);
        var height = bits.read(12);
        var aspect = bits.read(4);
        var rateCode = bits.read(4);
        long bitRate = bits.read(18);
        bits.skip(1);
        var vbv = bits.read(10);
        bits.skip(1);
        int[] intra = null;
        int[] nonIntra = null;
        if (bits.readFlag()) {
            intra = readMatrix(bits);
        }
        if (bits.readFlag()) {
            nonIntra = readMatrix(bits);
        }
        if (bits.overrun() || width == 0 || height == 0 || intra != null && intra[0] == 0 || nonIntra != null
                && nonIntra[0] == 0) {
            return null;
        }
        var mpeg2 = false;
        var profileAndLevel = -1;
        var progressive = true;
        var chroma = 1;
        var lowDelay = false;
        var rateN = 0;
        var rateD = 0;
        var videoFormat = -1;
        var primaries = -1;
        var transfer = -1;
        var matrix = -1;
        var displayWidth = 0;
        var displayHeight = 0;
        var scalable = -1;
        var cursor = next;
        while (cursor >= 0 && cursor + 4 < end) {
            var code = data[cursor + 3] & 0xFF;
            if (code != EXTENSION && code != USER_DATA) {
                break;
            }
            var following = NalUnits.findStartCode(data, cursor + 4, end);
            var unitEnd = following < 0 ? end : following;
            if (code == EXTENSION) {
                var ext = new MsbBitReader(data, cursor + 4, unitEnd - cursor - 4);
                var id = ext.read(4);
                switch (id) {
                    case SEQUENCE_EXTENSION_ID -> {
                        profileAndLevel = ext.read(8);
                        progressive = ext.readFlag();
                        chroma = ext.read(2);
                        width |= ext.read(2) << 12;
                        height |= ext.read(2) << 12;
                        bitRate |= (long) ext.read(12) << 18;
                        ext.skip(1);
                        vbv |= ext.read(8) << 10;
                        lowDelay = ext.readFlag();
                        rateN = ext.read(2);
                        rateD = ext.read(5);
                        if (ext.overrun() || chroma == 0) {
                            return null;
                        }
                        mpeg2 = true;
                    }
                    case SEQUENCE_DISPLAY_EXTENSION_ID -> {
                        var format = ext.read(3);
                        var description = ext.readFlag();
                        var p = -1;
                        var t = -1;
                        var m = -1;
                        if (description) {
                            p = ext.read(8);
                            t = ext.read(8);
                            m = ext.read(8);
                        }
                        var dw = ext.read(14);
                        ext.skip(1);
                        var dh = ext.read(14);
                        if (!ext.overrun()) {
                            videoFormat = format;
                            primaries = p;
                            transfer = t;
                            matrix = m;
                            displayWidth = dw;
                            displayHeight = dh;
                        }
                    }
                    case SEQUENCE_SCALABLE_EXTENSION_ID -> scalable = ext.read(2);
                    default -> {
                    }
                }
            }
            cursor = following;
        }
        return new Sequence(mpeg2, width, height, aspect, rateCode, rateN, rateD, bitRate, vbv, intra, nonIntra,
                profileAndLevel, progressive, chroma, lowDelay, videoFormat, primaries, transfer, matrix,
                displayWidth, displayHeight, scalable, cursor < 0 ? end : cursor);
    }

    /**
     * Reads a quantizer matrix transmitted in zigzag order.
     *
     * @param bits the reader at the first weight
     * @return the 64 weights in raster order
     */
    private static int[] readMatrix(MsbBitReader bits) {
        var matrix = new int[64];
        for (var index = 0; index < 64; index++) {
            matrix[ZIGZAG[index]] = bits.read(8);
        }
        for (var weight : matrix) {
            if (weight == 0) {
                matrix[0] = 0;
                break;
            }
        }
        return matrix;
    }

    /**
     * Extracts the decoder configuration a key frame carries in band: the sequence header with its extensions and
     * user data, as the {@code DecoderSpecificInfo} of an {@code esds} box and Matroska's {@code CodecPrivate}
     * hold it.
     *
     * @param frame the packet of a key frame
     * @return the bytes from the sequence header's start code to the first group of pictures or picture start
     * code, or {@code null} when the frame does not start with a valid sequence header before its first picture
     */
    public static byte[] config(byte[] frame) {
        var position = NalUnits.findStartCode(frame, 0, frame.length);
        while (position >= 0 && position + 3 < frame.length) {
            var code = frame[position + 3] & 0xFF;
            if (code == SEQUENCE_HEADER) {
                var sequence = parse(frame, position, frame.length);
                if (sequence == null) {
                    return null;
                }
                var end = sequence.end();
                return Arrays.copyOfRange(frame, position, end);
            }
            if (code == PICTURE_START || code == GROUP) {
                return null;
            }
            position = NalUnits.findStartCode(frame, position + 4, frame.length);
        }
        return null;
    }

    /**
     * Returns the coding type of the first picture of a packet.
     *
     * @param data the elementary stream bytes
     * @return {@code picture_coding_type}: 1 for I, 2 for P, 3 for B, 4 for D; 0 when the bytes hold no picture
     * header
     */
    public static int firstPictureType(byte[] data) {
        var position = NalUnits.findStartCode(data, 0, data.length);
        while (position >= 0 && position + 5 < data.length) {
            if ((data[position + 3] & 0xFF) == PICTURE_START) {
                return (data[position + 5] & 0x38) >> 3;
            }
            position = NalUnits.findStartCode(data, position + 4, data.length);
        }
        return 0;
    }

    /**
     * Returns the MPEG-4 Systems {@code objectTypeIndication} of a stream (ISO/IEC 14496-1 Table 5), as an
     * {@code esds} box declares it: {@code 0x6A} for MPEG-1, and for MPEG-2 {@code 0x60} to {@code 0x65} for the
     * Simple, Main, SNR, Spatial, High and 4:2:2 profiles of its sequence extension.
     *
     * @param mpeg1 whether the track is MPEG-1 video
     * @param config the sequence header with its extensions, or {@code null}
     * @return the object type; Main ({@code 0x61}) for an MPEG-2 stream of another profile or without a valid
     * header
     */
    public static int objectType(boolean mpeg1, byte[] config) {
        if (mpeg1) {
            return 0x6A;
        }
        var sequence = config == null ? null : find(config);
        if (sequence == null || sequence.profileAndLevel() < 0) {
            return 0x61;
        }
        var indication = sequence.profileAndLevel();
        if ((indication & 0x80) != 0) {
            return (indication & 0x0F) == 2 || (indication & 0x0F) == 5 ? 0x65 : 0x61;
        }
        return switch (indication >> 4 & 7) {
            case 1 -> 0x64;
            case 2 -> 0x63;
            case 3 -> 0x62;
            case 5 -> 0x60;
            default -> 0x61;
        };
    }

    /**
     * Returns whether a key frame starts with a sequence header, before any group of pictures or picture header.
     *
     * @param frame the elementary stream bytes of the frame
     * @return {@code true} when its first start code is a sequence header
     */
    public static boolean startsWithSequenceHeader(byte[] frame) {
        var position = NalUnits.findStartCode(frame, 0, frame.length);
        return position >= 0 && position + 3 < frame.length && (frame[position + 3] & 0xFF) == SEQUENCE_HEADER;
    }

    /**
     * Describes a configuration as {@link VideoConfigs#describe} reports it.
     *
     * @param config the sequence header with its extensions
     * @return the description, or {@code null} when the bytes hold no valid sequence header
     */
    static VideoConfigs.Info describe(byte[] config) {
        var sequence = find(config);
        if (sequence == null) {
            return null;
        }
        return new VideoConfigs.Info(sequence.profile(), sequence.level(), 8, sequence.chroma(),
                sequence.signaledColor(), sequence.width(), sequence.height(), sequence.sampleAspect(),
                sequence.frameRate());
    }

    /**
     * Returns the fraction closest to {@code numerator / denominator} whose terms do not exceed a bound, among the
     * convergents and semiconvergents of its continued fraction.
     *
     * @param numerator the numerator of the exact value, positive
     * @param denominator the denominator of the exact value, positive
     * @param max the largest term allowed
     * @return the approximation in lowest terms
     */
    static Rational approximate(long numerator, long denominator, long max) {
        long p0 = 0;
        long q0 = 1;
        long p1 = 1;
        long q1 = 0;
        var n = numerator;
        var d = denominator;
        while (d != 0) {
            var a = n / d;
            var p2 = a * p1 + p0;
            var q2 = a * q1 + q0;
            if (p2 > max || q2 > max) {
                var k = Math.min(q1 == 0 ? Long.MAX_VALUE : (max - q0) / q1, p1 == 0 ? Long.MAX_VALUE : (max - p0) / p1);
                var ps = k * p1 + p0;
                var qs = k * q1 + q0;
                if (k > 0 && 2 * k >= a && error(ps, qs, numerator, denominator) < error(p1, q1, numerator,
                        denominator)) {
                    return new Rational(ps, qs).reduce();
                }
                return new Rational(p1, q1).reduce();
            }
            p0 = p1;
            q0 = q1;
            p1 = p2;
            q1 = q2;
            var remainder = n - a * d;
            n = d;
            d = remainder;
        }
        return new Rational(p1, q1).reduce();
    }

    /**
     * Returns the distance of a fraction from an exact value.
     *
     * @param p the numerator of the fraction
     * @param q the denominator of the fraction
     * @param numerator the numerator of the exact value
     * @param denominator the denominator of the exact value
     * @return the absolute difference
     */
    private static double error(long p, long q, long numerator, long denominator) {
        return Math.abs((double) p / q - (double) numerator / denominator);
    }
}
