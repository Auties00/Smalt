/* Ports the RPU parser of the dolby_vision crate 3.4.0 of dovi_tool, commit 41dae64 (rpu/dovi_rpu.rs,
 * rpu_data_header.rs, rpu_data_mapping.rs, rpu_data_nlq.rs, vdr_dm_data.rs, extension_metadata/mod.rs).
 * Copyright quietvoid; MIT License. Modified for Smalt. */
package com.github.auties00.smalt.internal.nal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.internal.bits.MsbBitReader;
import com.github.auties00.smalt.internal.header.Crc32Mpeg;

import java.util.ArrayList;
import java.util.List;

/**
 * A parsed Dolby Vision reference processing unit (RPU): the header, the kind of enhancement layer its mapping
 * describes, and the display management data a decoder or a muxer may report.
 *
 * <p>The syntax is that of HEVC NAL units of type 62 ({@code UNSPEC62}) after the NAL unit header and of the
 * payload of the EMDF container in AV1 ITU-T T.35 metadata: the prefix byte {@code 0x19}, {@code rpu_data_header},
 * {@code rpu_data_mapping} unless the previous RPU is reused, {@code vdr_dm_data_payload} when present, the
 * alignment bits, then a CRC-32/MPEG-2 of everything after the prefix and the terminating byte {@code 0x80}.
 * Trailing zero bytes are ignored. The display management extension blocks are skipped by their length; only
 * their levels are kept.
 *
 * @param rpuFormat {@code rpu_format}, 11 bits
 * @param vdrRpuProfile {@code vdr_rpu_profile}, 4 bits
 * @param baseLayerFullRange {@code bl_video_full_range_flag}
 * @param baseLayerBitDepth the bit depth of the base layer ({@code bl_bit_depth_minus8 + 8})
 * @param enhancementLayerBitDepth the bit depth of the enhancement layer ({@code el_bit_depth_minus8 + 8})
 * @param vdrBitDepth the bit depth of the reconstructed signal ({@code vdr_bit_depth_minus8 + 8})
 * @param enhancementLayerResampling {@code el_spatial_resampling_filter_flag}
 * @param residualDisabled {@code disable_residual_flag}
 * @param previousReused {@code use_prev_vdr_rpu_flag}: the mapping of an earlier RPU applies and this one has none
 * @param profile the Dolby Vision profile the header implies (4, 5, 7 or 8), or 0 when it implies none
 * @param enhancementLayer the kind of enhancement layer the mapping describes
 * @param displayManagement the display management data, or {@code null} when the RPU carries none
 * @param mapping the reshaping curves, or {@code null} when the previous RPU is reused
 * @param previousRpuId {@code prev_vdr_rpu_id}, the identifier of the reused mapping, or -1 when the RPU has its own
 */
public record DolbyVisionRpu(int rpuFormat, int vdrRpuProfile, boolean baseLayerFullRange, int baseLayerBitDepth,
                             int enhancementLayerBitDepth, int vdrBitDepth, boolean enhancementLayerResampling,
                             boolean residualDisabled, boolean previousReused, int profile,
                             EnhancementLayer enhancementLayer, DisplayManagement displayManagement,
                             Mapping mapping, int previousRpuId) {
    /**
     * The first byte of every RPU ({@code rpu_nal_prefix}).
     */
    public static final int PREFIX = 0x19;

    /**
     * The HEVC NAL unit type that carries RPUs ({@code UNSPEC62}).
     */
    public static final int HEVC_NAL_TYPE = 62;

    /**
     * The byte that ends an RPU.
     */
    private static final int FINAL_BYTE = 0x80;

    /**
     * The bits of the CRC and the final byte.
     */
    private static final int TRAILER_BITS = 40;

    /**
     * The fewest bits left before the trailer for a CM v4.0 display management block to be present.
     */
    private static final int CM_V40_MIN_BITS = 56;

    /**
     * The largest number of reshaping pieces of a component ({@code RESHAPING_MAX_PIECES}).
     */
    private static final int MAX_PIECES = 8;

    /**
     * The number of coefficients of each order of an MMR piece ({@code MMR_MAX_COEFFS}).
     */
    private static final int MMR_COEFFICIENTS = 7;

    /**
     * The largest number of CM v2.9 extension blocks.
     */
    private static final int CM_V29_MAX_BLOCKS = 13;

    /**
     * The largest number of CM v4.0 extension blocks.
     */
    private static final int CM_V40_MAX_BLOCKS = 14;

    /**
     * The kinds of enhancement layer an RPU mapping describes.
     */
    public enum EnhancementLayer {
        /**
         * No enhancement layer: the residual is disabled or the previous mapping is reused.
         */
        NONE,

        /**
         * A minimal enhancement layer, whose nonlinear quantization adds nothing to the base layer.
         */
        MINIMAL,

        /**
         * A full enhancement layer, whose residual the reconstruction adds to the base layer.
         */
        FULL
    }

    /**
     * The display management data of an RPU ({@code vdr_dm_data_payload}).
     *
     * @param compressed whether the data is the compressed form, which carries only the identifiers and the
     * extension blocks
     * @param signalEotf {@code signal_eotf}, or 0 for compressed data
     * @param signalBitDepth {@code signal_bit_depth}, or 0 for compressed data
     * @param signalColorSpace {@code signal_color_space}, or 0 for compressed data
     * @param signalFullRange {@code signal_full_range_flag}, or 0 for compressed data
     * @param sourceMinPq {@code source_min_pq}, a 12-bit PQ code, or 0 for compressed data
     * @param sourceMaxPq {@code source_max_pq}, a 12-bit PQ code, or 0 for compressed data
     * @param cmV29Levels the levels of the CM v2.9 extension blocks, in order
     * @param cmV40Levels the levels of the CM v4.0 extension blocks, in order, or {@code null} when the RPU has
     * no CM v4.0 data
     * @param affectedId {@code affected_dm_metadata_id}, the identifier this data is stored under
     * @param currentId {@code current_dm_metadata_id}, the identifier of the data that applies to this picture
     * @param yccToRgb the nine {@code ycc_to_rgb_coef} values in row order, in units of 1/8192, or {@code null} for
     * compressed data
     * @param yccToRgbOffset the three {@code ycc_to_rgb_offset} values, unsigned, in units of 1/2^28 of the signal
     * range, or {@code null} for compressed data
     * @param rgbToLms the nine {@code rgb_to_lms_coef} values in row order, in units of 1/16384, or {@code null} for
     * compressed data
     */
    public record DisplayManagement(boolean compressed, int signalEotf, int signalBitDepth, int signalColorSpace,
                                    int signalFullRange, int sourceMinPq, int sourceMaxPq, List<Integer> cmV29Levels,
                                    List<Integer> cmV40Levels, int affectedId, int currentId, short[] yccToRgb,
                                    long[] yccToRgbOffset, short[] rgbToLms) {
    }

    /**
     * The reshaping curves of an RPU ({@code rpu_data_mapping}).
     *
     * @param rpuId {@code vdr_rpu_id}, the identifier a later RPU reuses this mapping by
     * @param denominatorBits the width of the fractional part of the coefficients, in bits
     * @param floatingCoefficients whether the coefficients are IEEE 754 values ({@code coefficient_data_type} 1),
     * which {@link Piece#coefficients()} holds converted to {@code denominatorBits} fractional bits
     * @param curves the curves of the three components
     */
    public record Mapping(int rpuId, int denominatorBits, boolean floatingCoefficients, Curve[] curves) {
    }

    /**
     * The reshaping curve of one component.
     *
     * @param pivots the {@code pivot_value} of each pivot point: the running sum of {@code pred_pivot_value}
     * @param pieces the mapping of each interval between consecutive pivots
     */
    public record Curve(int[] pivots, Piece[] pieces) {
    }

    /**
     * The mapping of one interval of a curve.
     *
     * @param mmr whether the mapping is a multivariate multiple regression instead of a polynomial
     * @param order the order: from 1 to 2 for a polynomial, from 1 to 3 for a regression
     * @param coefficients the fixed-point coefficients {@code (int << denominatorBits) + fraction}: {@code order + 1}
     * of a polynomial in increasing powers, or the constant and then {@code 7 * order} of a regression
     */
    public record Piece(boolean mmr, int order, long[] coefficients) {
    }

    /**
     * Parses the RPU of an HEVC NAL unit of type 62.
     *
     * @param data the buffer holding the NAL unit
     * @param offset the index of the two-byte NAL unit header
     * @param length the NAL unit length, emulation prevention bytes included
     * @return the RPU
     * @throws MediaException.Malformed if the unit is not a NAL unit of type 62 or its RPU is corrupt
     */
    public static DolbyVisionRpu parseHevcNal(byte[] data, int offset, int length) {
        if (length < 3 || NalUnits.type(data[offset], true) != HEVC_NAL_TYPE) {
            throw new MediaException.Malformed("not a Dolby Vision RPU NAL unit");
        }
        var payload = NalUnits.unescape(data, offset + 2, length - 2);
        return parse(payload, 0, payload.length);
    }

    /**
     * Parses an HEVC RPU NAL unit, returning {@code null} instead of failing.
     *
     * @param data the buffer holding the NAL unit
     * @param offset the index of the two-byte NAL unit header
     * @param length the NAL unit length, emulation prevention bytes included
     * @return the RPU, or {@code null} when the unit is not a valid RPU NAL unit
     */
    public static DolbyVisionRpu tryParseHevcNal(byte[] data, int offset, int length) {
        try {
            return parseHevcNal(data, offset, length);
        } catch (MediaException.Malformed exception) {
            return null;
        }
    }

    /**
     * Parses an unescaped RPU.
     *
     * @param data the buffer holding the RPU
     * @param offset the index of its prefix byte
     * @param length the RPU length, trailing zero bytes allowed
     * @return the RPU
     * @throws MediaException.Malformed if the RPU is truncated, fails its CRC or breaks a constraint of its syntax
     */
    public static DolbyVisionRpu parse(byte[] data, int offset, int length) {
        var end = offset + length;
        while (end > offset && data[end - 1] == 0) {
            end--;
        }
        if (end - offset < 7) {
            throw new MediaException.Malformed("Dolby Vision RPU of " + (end - offset) + " bytes is too short");
        }
        if ((data[end - 1] & 0xFF) != FINAL_BYTE) {
            throw new MediaException.Malformed("Dolby Vision RPU does not end with 0x80");
        }
        if ((data[offset] & 0xFF) != PREFIX) {
            throw new MediaException.Malformed("Dolby Vision RPU does not start with 0x19");
        }
        var crcStart = end - 5;
        var stored = (data[crcStart] & 0xFFL) << 24 | (data[crcStart + 1] & 0xFF) << 16
                | (data[crcStart + 2] & 0xFF) << 8 | (data[crcStart + 3] & 0xFF);
        if ((Crc32Mpeg.update(Crc32Mpeg.MPEG_INIT, data, offset + 1, crcStart - offset - 1) & 0xFFFFFFFFL) != stored) {
            throw new MediaException.Malformed("Dolby Vision RPU fails its CRC");
        }
        var reader = new MsbBitReader(data, offset, end - offset);
        reader.skip(8);
        var rpu = new Parser(reader).parse();
        reader.checkNotOverrun();
        return rpu;
    }

    /**
     * Reads the syntax of one RPU after its prefix byte.
     */
    private static final class Parser {
        /**
         * The bits of the RPU.
         */
        private final MsbBitReader reader;

        /**
         * The width of the fractional part of the coefficients, in bits.
         */
        private int denominatorBits;

        /**
         * Whether coefficients carry an Exp-Golomb integer part ({@code coefficient_data_type} 0).
         */
        private boolean integerParts;

        /**
         * The mapping being parsed, set by {@link #mapping(int, int, boolean)}.
         */
        private Mapping parsedMapping;

        /**
         * Constructs a parser.
         *
         * @param reader the bits, positioned after the prefix byte
         */
        private Parser(MsbBitReader reader) {
            this.reader = reader;
        }

        /**
         * Parses the RPU and checks its trailer.
         *
         * @return the RPU
         */
        private DolbyVisionRpu parse() {
            var rpuType = reader.read(6);
            if (rpuType != 2) {
                throw new MediaException.Malformed("Dolby Vision RPU type " + rpuType + " is not 2");
            }
            var rpuFormat = reader.read(11);
            var vdrRpuProfile = reader.read(4);
            var vdrRpuLevel = reader.read(4);
            if (!reader.readFlag()) {
                throw new MediaException.Malformed("Dolby Vision RPU without sequence information");
            }
            reader.skip(1);
            var coefficientType = reader.read(2);
            var log2Denominator = coefficientType == 0 ? reader.readUe() : 0;
            denominatorBits = switch (coefficientType) {
                case 0 -> log2Denominator;
                case 1 -> 32;
                default -> throw new MediaException.Malformed("Dolby Vision coefficient_data_type " + coefficientType);
            };
            integerParts = coefficientType == 0;
            reader.skip(2);
            var fullRange = reader.readFlag();
            var baseDepth = 0;
            var enhancementDepth = 0;
            var vdrDepth = 0;
            var compressedDm = false;
            var resampling = false;
            var residualDisabled = false;
            if ((rpuFormat & 0x700) == 0) {
                baseDepth = reader.readUe();
                enhancementDepth = reader.readUe() & 0xFF;
                vdrDepth = reader.readUe();
                reader.skip(1);
                compressedDm = reader.read(3) == 1;
                resampling = reader.readFlag();
                residualDisabled = reader.readFlag();
            }
            if (vdrRpuLevel != 0 || baseDepth != 2 || enhancementDepth != 2 || vdrDepth > 6 || log2Denominator > 23) {
                throw new MediaException.Malformed("Dolby Vision RPU header out of range");
            }
            var dmPresent = reader.readFlag();
            var reuse = reader.readFlag();
            var previousRpuId = reuse ? reader.readUe() : -1;
            var profile = switch (vdrRpuProfile) {
                case 0 -> fullRange ? 5 : 0;
                case 1 -> resampling && !residualDisabled ? (vdrDepth == 4 ? 7 : 4) : 8;
                default -> 0;
            };
            var layer = EnhancementLayer.NONE;
            if (!reuse) {
                layer = mapping(baseDepth + 8, enhancementDepth + 8, (rpuFormat & 0x700) == 0 && !residualDisabled);
            }
            var management = dmPresent ? displayManagement(compressedDm) : null;
            while (!reader.isByteAligned()) {
                if (reader.readBit() != 0) {
                    throw new MediaException.Malformed("Dolby Vision rpu_alignment_zero_bit is set");
                }
            }
            var left = reader.bitsLeft();
            if (left < TRAILER_BITS) {
                throw new MediaException.Malformed("Dolby Vision RPU overruns its CRC");
            }
            reader.skip(left - TRAILER_BITS);
            return new DolbyVisionRpu(rpuFormat, vdrRpuProfile, fullRange, baseDepth + 8, enhancementDepth + 8,
                    vdrDepth + 8, resampling, residualDisabled, reuse, profile, layer, management, parsedMapping,
                    previousRpuId);
        }

        /**
         * Parses {@code rpu_data_mapping} and its nonlinear quantization parameters.
         *
         * @param baseDepth the base layer bit depth
         * @param enhancementDepth the enhancement layer bit depth
         * @param quantized whether the mapping carries nonlinear quantization parameters
         * @return the kind of enhancement layer
         */
        private EnhancementLayer mapping(int baseDepth, int enhancementDepth, boolean quantized) {
            var rpuId = reader.readUe();
            if (reader.readUe() != 0 || reader.readUe() != 0) {
                throw new MediaException.Malformed("Dolby Vision mapping color space or chroma format is not 0");
            }
            var pivotValues = new int[3][];
            for (var component = 0; component < 3; component++) {
                var pivots = reader.readUe() + 2;
                if (pivots - 2 >= MAX_PIECES) {
                    throw new MediaException.Malformed("Dolby Vision reshaping curve of " + pivots + " pivots");
                }
                var values = new int[pivots];
                var sum = 0;
                for (var pivot = 0; pivot < pivots; pivot++) {
                    sum += reader.read(baseDepth);
                    values[pivot] = sum;
                }
                pivotValues[component] = values;
            }
            if (quantized) {
                if (reader.read(3) != 0) {
                    throw new MediaException.Malformed("Dolby Vision NLQ method is not linear dead zone");
                }
                reader.skip(2L * baseDepth);
            }
            reader.readUe();
            reader.readUe();
            var curves = new Curve[3];
            for (var component = 0; component < 3; component++) {
                var pieces = new Piece[pivotValues[component].length - 1];
                for (var piece = 0; piece < pieces.length; piece++) {
                    pieces[piece] = switch (reader.readUe()) {
                        case 0 -> polynomial();
                        case 1 -> mmr();
                        default -> throw new MediaException.Malformed("Dolby Vision mapping_idc out of range");
                    };
                }
                curves[component] = new Curve(pivotValues[component], pieces);
            }
            parsedMapping = new Mapping(rpuId, denominatorBits, !integerParts, curves);
            return quantized ? quantization(enhancementDepth) : EnhancementLayer.NONE;
        }

        /**
         * Reads the coefficients of a polynomial piece.
         *
         * @return the piece
         */
        private Piece polynomial() {
            var orderMinus1 = reader.readUe();
            if (orderMinus1 > 1) {
                throw new MediaException.Malformed("Dolby Vision polynomial of order " + (orderMinus1 + 1));
            }
            if (orderMinus1 == 0 && reader.readFlag()) {
                // TODO: linear interpolation pieces (linear_interp_flag); no known stream uses them
                throw new MediaException.Malformed("Dolby Vision polynomial linear interpolation");
            }
            var coefficients = new long[orderMinus1 + 2];
            for (var coefficient = 0; coefficient < coefficients.length; coefficient++) {
                coefficients[coefficient] = coefficient();
            }
            return new Piece(false, orderMinus1 + 1, coefficients);
        }

        /**
         * Reads the coefficients of a multivariate multiple regression piece.
         *
         * @return the piece
         */
        private Piece mmr() {
            var orderMinus1 = reader.read(2);
            if (orderMinus1 > 2) {
                throw new MediaException.Malformed("Dolby Vision MMR of order 4");
            }
            var coefficients = new long[1 + (orderMinus1 + 1) * MMR_COEFFICIENTS];
            for (var coefficient = 0; coefficient < coefficients.length; coefficient++) {
                coefficients[coefficient] = coefficient();
            }
            return new Piece(true, orderMinus1 + 1, coefficients);
        }

        /**
         * Reads one signed coefficient: its integer part when coefficients carry one, then its fraction.
         *
         * @return the coefficient with {@link #denominatorBits} fractional bits
         */
        private long coefficient() {
            if (integerParts) {
                var integer = (long) reader.readSe();
                return (integer << denominatorBits) + reader.readLong(denominatorBits);
            }
            return (long) (Float.intBitsToFloat(reader.read(32)) * (double) (1L << denominatorBits));
        }

        /**
         * Parses {@code rpu_data_nlq} and tells a minimal enhancement layer from a full one.
         *
         * @param enhancementDepth the enhancement layer bit depth
         * @return the kind of enhancement layer
         */
        private EnhancementLayer quantization(int enhancementDepth) {
            // a minimal layer has vdr_in_max_int 1, which coefficients without integer parts cannot carry
            var minimal = integerParts;
            for (var component = 0; component < 3; component++) {
                minimal &= reader.read(enhancementDepth) == 0;
                minimal &= (!integerParts || reader.readUe() == 1);
                minimal &= reader.readLong(denominatorBits) == 0;
                for (var value = 0; value < 2; value++) {
                    minimal &= (!integerParts || reader.readUe() == 0);
                    minimal &= reader.readLong(denominatorBits) == 0;
                }
            }
            return minimal ? EnhancementLayer.MINIMAL : EnhancementLayer.FULL;
        }

        /**
         * Parses {@code vdr_dm_data_payload}.
         *
         * @param compressed whether the data is in its compressed form
         * @return the display management data
         */
        private DisplayManagement displayManagement(boolean compressed) {
            var affectedId = reader.readUe();
            var currentId = reader.readUe();
            reader.readUe();
            short[] yccToRgb = null;
            long[] yccToRgbOffset = null;
            short[] rgbToLms = null;
            var eotf = 0;
            var depth = 0;
            var space = 0;
            var full = 0;
            var minPq = 0;
            var maxPq = 0;
            if (!compressed) {
                yccToRgb = new short[9];
                for (var coefficient = 0; coefficient < 9; coefficient++) {
                    yccToRgb[coefficient] = (short) reader.read(16);
                }
                yccToRgbOffset = new long[3];
                for (var offset = 0; offset < 3; offset++) {
                    yccToRgbOffset[offset] = reader.readLong(32);
                }
                rgbToLms = new short[9];
                for (var coefficient = 0; coefficient < 9; coefficient++) {
                    rgbToLms[coefficient] = (short) reader.read(16);
                }
                eotf = reader.read(16);
                reader.skip(16 + 16 + 32);
                depth = reader.read(5);
                space = reader.read(2);
                reader.skip(2);
                full = reader.read(2);
                minPq = reader.read(12);
                maxPq = reader.read(12);
                reader.skip(10);
            }
            var v29 = extensionBlocks(CM_V29_MAX_BLOCKS);
            List<Integer> v40 = null;
            if (reader.bitsLeft() >= CM_V40_MIN_BITS) {
                v40 = extensionBlocks(CM_V40_MAX_BLOCKS);
            }
            return new DisplayManagement(compressed, eotf, depth, space, full, minPq, maxPq, v29, v40, affectedId,
                    currentId, yccToRgb, yccToRgbOffset, rgbToLms);
        }

        /**
         * Parses the extension blocks of one content mapping version, skipping each payload by its length.
         *
         * @param maxBlocks the largest number of blocks the version allows
         * @return the levels of the blocks, in order
         */
        private List<Integer> extensionBlocks(int maxBlocks) {
            var count = reader.readUe();
            if (count > maxBlocks) {
                throw new MediaException.Malformed("Dolby Vision display management data with " + count + " blocks");
            }
            while (!reader.isByteAligned()) {
                if (reader.readBit() != 0) {
                    throw new MediaException.Malformed("Dolby Vision dm_alignment_zero_bit is set");
                }
            }
            var levels = new ArrayList<Integer>(count);
            for (var block = 0; block < count; block++) {
                var length = reader.readUe();
                levels.add(reader.read(8));
                if ((long) length * 8 > reader.bitsLeft()) {
                    throw new MediaException.Malformed("Dolby Vision extension block overruns its RPU");
                }
                reader.skip((long) length * 8);
            }
            return List.copyOf(levels);
        }
    }
}
