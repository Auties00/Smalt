package com.github.auties00.smalt.internal.heif;

import com.github.auties00.smalt.MediaException;

/**
 * Holds one item property of a HEIF file ({@code ipco} child box), parsed into its fields.
 *
 * <p>The descriptive properties (ISO/IEC 23008-12 clause 6.5) are {@link SpatialExtent},
 * {@link PixelInformation}, {@link ColorNclx}, {@link ColorIcc}, {@link PixelAspectRatio},
 * {@link AuxiliaryType}, {@link ContentLightLevel}, {@link MasteringDisplay},
 * {@link LayeredImageIndexing} and the codec configurations {@link Av1Configuration} and
 * {@link HevcConfiguration} ({@link ColorOther} stands for color boxes of other types) and
 * {@link RequiredReferenceTypes}; the
 * transformative ones are {@link CleanAperture},
 * {@link Rotation} and {@link Mirror}; {@link OperatingPointSelector} and {@link LayerSelector}
 * select the part of a layered AV1 item to reconstruct. Every other box becomes {@link Unknown},
 * so an item can still tell whether it carries an essential property a reader does not
 * understand.
 *
 * <p>Instances are immutable apart from the arrays some records hold, which callers must not
 * modify.
 */
public sealed interface HeifProperty {
    /**
     * Returns the four-character code of the property box.
     *
     * @return the box type, for example {@code "ispe"}
     */
    String type();

    /**
     * The image spatial extents ({@code ispe}): the size of the reconstructed image before any
     * transformative property.
     *
     * @param width the width in pixels, an unsigned 32-bit value
     * @param height the height in pixels, an unsigned 32-bit value
     */
    record SpatialExtent(long width, long height) implements HeifProperty {
        @Override
        public String type() {
            return "ispe";
        }
    }

    /**
     * The pixel information ({@code pixi}): the number of bits of each channel.
     *
     * @param depths the bits per channel, one entry per channel
     */
    record PixelInformation(int[] depths) implements HeifProperty {
        @Override
        public String type() {
            return "pixi";
        }
    }

    /**
     * The AV1 codec configuration ({@code av1C}, AV1 Codec ISO Media File Format Binding clause
     * 2.3).
     *
     * @param record the whole record, as AV1 decoders take it
     * @param profile {@code seq_profile}
     * @param level {@code seq_level_idx_0}
     * @param tier {@code seq_tier_0}
     * @param bitDepth the bit depth that {@code high_bitdepth} and {@code twelve_bit} describe: 8,
     * 10 or 12
     * @param monochrome {@code monochrome}
     * @param subsamplingX {@code chroma_subsampling_x}
     * @param subsamplingY {@code chroma_subsampling_y}
     * @param chromaSamplePosition {@code chroma_sample_position}
     */
    record Av1Configuration(byte[] record, int profile, int level, int tier, int bitDepth, boolean monochrome,
                            boolean subsamplingX, boolean subsamplingY, int chromaSamplePosition)
            implements HeifProperty {
        /**
         * Parses the record.
         *
         * @param box the cursor over the payload
         * @return the configuration
         * @throws MediaException.Malformed if the marker or the version is wrong or the record is
         * shorter than its four fixed bytes
         */
        static Av1Configuration parse(BoxCursor box) {
            if (box.remaining() < 4) {
                throw box.truncated();
            }
            var record = box.rest();
            var first = record[0] & 0xFF;
            if (first != 0x81) {
                throw new MediaException.Malformed("heif: av1C has marker and version byte " + first);
            }
            var second = record[1] & 0xFF;
            var third = record[2] & 0xFF;
            var highBitDepth = (third & 0x40) != 0;
            var twelveBit = (third & 0x20) != 0;
            var bitDepth = highBitDepth ? (twelveBit ? 12 : 10) : 8;
            return new Av1Configuration(record, second >> 5, second & 0x1F, third >> 7, bitDepth,
                    (third & 0x10) != 0, (third & 0x08) != 0, (third & 0x04) != 0, third & 3);
        }

        @Override
        public String type() {
            return "av1C";
        }
    }

    /**
     * The HEVC codec configuration ({@code hvcC}), kept as the raw record.
     *
     * @param record the {@code HEVCDecoderConfigurationRecord}
     */
    record HevcConfiguration(byte[] record) implements HeifProperty {
        @Override
        public String type() {
            return "hvcC";
        }
    }

    /**
     * The color description of a {@code colr} box of type {@code nclx}: the H.273 code points.
     *
     * @param primaries the {@code colour_primaries} code point
     * @param transfer the {@code transfer_characteristics} code point
     * @param matrix the {@code matrix_coefficients} code point
     * @param fullRange {@code full_range_flag}
     */
    record ColorNclx(int primaries, int transfer, int matrix, boolean fullRange) implements HeifProperty {
        @Override
        public String type() {
            return "colr";
        }
    }

    /**
     * The ICC profile of a {@code colr} box of type {@code rICC} or {@code prof}.
     *
     * @param profile the profile bytes
     */
    record ColorIcc(byte[] profile) implements HeifProperty {
        @Override
        public String type() {
            return "colr";
        }
    }

    /**
     * A {@code colr} box of a color type other than {@code nclx}, {@code rICC} and {@code prof},
     * such as QuickTime's {@code nclc}, whose content is not interpreted.
     *
     * @param colorType the four-character color type
     */
    record ColorOther(String colorType) implements HeifProperty {
        @Override
        public String type() {
            return "colr";
        }
    }

    /**
     * The pixel aspect ratio ({@code pasp}).
     *
     * @param horizontalSpacing {@code hSpacing}
     * @param verticalSpacing {@code vSpacing}
     */
    record PixelAspectRatio(long horizontalSpacing, long verticalSpacing) implements HeifProperty {
        @Override
        public String type() {
            return "pasp";
        }
    }

    /**
     * The clean aperture ({@code clap}, ISO/IEC 14496-12 clause 12.1.4): a crop given as the
     * size of the kept rectangle and the offset of its center from the image center, each a
     * signed fraction.
     *
     * @param widthN {@code cleanApertureWidthN}
     * @param widthD {@code cleanApertureWidthD}
     * @param heightN {@code cleanApertureHeightN}
     * @param heightD {@code cleanApertureHeightD}
     * @param horizontalOffsetN {@code horizOffN}
     * @param horizontalOffsetD {@code horizOffD}
     * @param verticalOffsetN {@code vertOffN}
     * @param verticalOffsetD {@code vertOffD}
     */
    record CleanAperture(int widthN, int widthD, int heightN, int heightD, int horizontalOffsetN,
                         int horizontalOffsetD, int verticalOffsetN, int verticalOffsetD) implements HeifProperty {
        @Override
        public String type() {
            return "clap";
        }
    }

    /**
     * The image rotation ({@code irot}).
     *
     * @param quarterTurns the anticlockwise rotation in quarter turns, from 0 to 3
     */
    record Rotation(int quarterTurns) implements HeifProperty {
        @Override
        public String type() {
            return "irot";
        }
    }

    /**
     * The image mirroring ({@code imir}, ISO/IEC 23008-12:2022 clause 6.5.12).
     *
     * @param axis {@code 0} to exchange the top and bottom parts of the image (EXIF orientation 4),
     * {@code 1} to exchange its left and right parts (EXIF orientation 2)
     */
    record Mirror(int axis) implements HeifProperty {
        @Override
        public String type() {
            return "imir";
        }
    }

    /**
     * The auxiliary type of an auxiliary image item ({@code auxC}) or track ({@code auxi}).
     *
     * @param type the box type, {@code "auxC"} or {@code "auxi"}
     * @param urn the {@code aux_type} URN
     * @param subtype the {@code aux_subtype} bytes that follow it
     */
    record AuxiliaryType(String type, String urn, byte[] subtype) implements HeifProperty {
        /**
         * The URN of an alpha plane in MPEG-B part 7 (CICP) terms.
         */
        public static final String ALPHA_URN = "urn:mpeg:mpegB:cicp:systems:auxiliary:alpha";

        /**
         * The URN of an alpha plane in HEVC terms.
         */
        public static final String HEVC_ALPHA_URN = "urn:mpeg:hevc:2015:auxid:1";

        /**
         * Returns whether the auxiliary image is an alpha plane.
         *
         * @return {@code true} for either alpha URN
         */
        public boolean isAlpha() {
            return ALPHA_URN.equals(urn) || HEVC_ALPHA_URN.equals(urn);
        }
    }

    /**
     * The AV1 operating point selector ({@code a1op}).
     *
     * @param operatingPoint the operating point index, from 0 to 31 in a valid file
     */
    record OperatingPointSelector(int operatingPoint) implements HeifProperty {
        @Override
        public String type() {
            return "a1op";
        }
    }

    /**
     * The layer selector ({@code lsel}).
     *
     * @param layer the spatial layer to reconstruct, or {@code 0xFFFF} for every layer
     */
    record LayerSelector(int layer) implements HeifProperty {
        /**
         * The value that selects no single layer.
         */
        public static final int ALL_LAYERS = 0xFFFF;

        @Override
        public String type() {
            return "lsel";
        }
    }

    /**
     * The AV1 layered image indexing ({@code a1lx}): the sizes of the first three layers of a
     * layered item, each {@code 0} when unknown.
     *
     * @param layerSizes the byte sizes of layers 0 to 2
     */
    record LayeredImageIndexing(long[] layerSizes) implements HeifProperty {
        @Override
        public String type() {
            return "a1lx";
        }
    }

    /**
     * The content light level ({@code clli}).
     *
     * @param maxContentLightLevel {@code max_content_light_level}, in candelas per square meter
     * @param maxPictureAverageLightLevel {@code max_pic_average_light_level}, in candelas per
     * square meter
     */
    record ContentLightLevel(int maxContentLightLevel, int maxPictureAverageLightLevel) implements HeifProperty {
        @Override
        public String type() {
            return "clli";
        }
    }

    /**
     * The mastering display color volume ({@code mdcv}), with the units of H.265 SEI 137.
     *
     * @param primaries the x and y chromaticities of the three primaries in 0.00002 steps, in the
     * order the box stores them
     * @param whitePointX the white point x chromaticity in 0.00002 steps
     * @param whitePointY the white point y chromaticity in 0.00002 steps
     * @param maxLuminance the maximum luminance in 0.0001 candelas per square meter
     * @param minLuminance the minimum luminance in 0.0001 candelas per square meter
     */
    record MasteringDisplay(int[] primaries, int whitePointX, int whitePointY, long maxLuminance,
                            long minLuminance) implements HeifProperty {
        @Override
        public String type() {
            return "mdcv";
        }
    }

    /**
     * The required reference types ({@code rref}, ISO/IEC 23008-12:2022): the item
     * reference types a reader must follow to reconstruct the item, such as {@code pred} for a
     * predictively coded image.
     *
     * @param referenceTypes the four-character reference types
     */
    record RequiredReferenceTypes(java.util.List<String> referenceTypes) implements HeifProperty {
        @Override
        public String type() {
            return "rref";
        }
    }

    /**
     * A property this parser does not interpret.
     *
     * @param type the box type
     */
    record Unknown(String type) implements HeifProperty {
    }
}
