package com.github.auties00.smalt.internal.heif;

import com.github.auties00.smalt.MediaException;

/**
 * Holds one item property of a HEIF file ({@code ipco} child box), parsed into its fields.
 *
 * <p>The descriptive properties (ISO/IEC 23008-12 clause 6.5) are {@link SpatialExtent},
 * {@link PixelInformation}, {@link ColorNclx}, {@link ColorIcc}, {@link PixelAspectRatio},
 * {@link AuxiliaryType}, {@link ContentLightLevel}, {@link MasteringDisplay},
 * {@link LayeredImageIndexing}, the codec configurations {@link Av1Configuration},
 * {@link HevcConfiguration}, {@link AvcConfiguration}, {@link JpegConfiguration} and
 * {@link Jpeg2000Header}, the uncompressed image descriptions of ISO/IEC 23001-17
 * ({@link UncompressedConfiguration}, {@link ComponentDefinition}, {@link ComponentPalette},
 * {@link ComponentPattern}, {@link CompressionConfiguration}, {@link CompressedUnits} and
 * {@link ChromaLocation}) ({@link ColorOther} stands for color boxes of other types) and
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
     * The layered HEVC codec configuration ({@code lhvC}, ISO/IEC 14496-15 clause 9.6.3) of a layered
     * HEVC image item ({@code lhv1}), kept as the raw record: the parameter sets the item's layers
     * need.
     *
     * @param record the {@code LHEVCDecoderConfigurationRecord}
     */
    record LayeredHevcConfiguration(byte[] record) implements HeifProperty {
        @Override
        public String type() {
            return "lhvC";
        }
    }

    /**
     * The target output layer set of a layered HEVC image item ({@code tols}, ISO/IEC 23008-12): the
     * output layer set its reconstruction decodes.
     *
     * @param outputLayerSet {@code target_ols_idx}
     */
    record TargetOutputLayerSet(int outputLayerSet) implements HeifProperty {
        @Override
        public String type() {
            return "tols";
        }
    }

    /**
     * The operating points information of a layered image item ({@code oinf}, ISO/IEC 14496-15
     * clause 9.6.2), kept as the raw payload: the profile, tier and level of each operating point
     * and the layers it holds. The bitstream's video parameter set carries the same information,
     * so the reconstruction does not need it.
     *
     * @param payload the box payload after the full box header
     */
    record OperatingPointsInformation(byte[] payload) implements HeifProperty {
        @Override
        public String type() {
            return "oinf";
        }
    }

    /**
     * The AVC codec configuration ({@code avcC}, ISO/IEC 14496-15 clause 5.3.3), kept as the raw
     * record.
     *
     * @param record the {@code AVCDecoderConfigurationRecord}
     */
    record AvcConfiguration(byte[] record) implements HeifProperty {
        @Override
        public String type() {
            return "avcC";
        }
    }

    /**
     * The JPEG configuration ({@code jpgC}, ISO/IEC 23008-12 Annex H): marker segments shared by
     * JPEG items and left out of their data.
     *
     * @param prefix the bytes that precede the item data to form the JPEG stream
     */
    record JpegConfiguration(byte[] prefix) implements HeifProperty {
        @Override
        public String type() {
            return "jpgC";
        }
    }

    /**
     * The JPEG 2000 header ({@code j2kH}, ISO/IEC 15444-16): the JP2 boxes that describe the
     * codestream of a {@code j2k1} item, such as its channel definition ({@code cdef}),
     * component mapping ({@code cmap}) and palette ({@code pclr}).
     *
     * @param boxes the child boxes, each whole with its header
     */
    record Jpeg2000Header(java.util.List<byte[]> boxes) implements HeifProperty {
        /**
         * The {@link #opacity()} of a header whose channel definition declares no opacity channel.
         */
        public static final int NO_OPACITY = 0;

        /**
         * The {@link #opacity()} of a header that declares a straight opacity channel.
         */
        public static final int STRAIGHT_OPACITY = 1;

        /**
         * The {@link #opacity()} of a header that declares a premultiplied opacity channel.
         */
        public static final int PREMULTIPLIED_OPACITY = 2;

        /**
         * Returns the kind of opacity channel the channel definition box ({@code cdef}, ISO/IEC
         * 15444-1 I.5.3.6) of the header declares.
         *
         * @return {@link #NO_OPACITY}, {@link #STRAIGHT_OPACITY} or {@link #PREMULTIPLIED_OPACITY};
         * a truncated channel definition declares none
         */
        public int opacity() {
            for (var box : boxes) {
                if (box.length < 10 || box[4] != 'c' || box[5] != 'd' || box[6] != 'e' || box[7] != 'f') {
                    continue;
                }
                var count = ((box[8] & 0xFF) << 8) | (box[9] & 0xFF);
                var result = NO_OPACITY;
                for (var i = 0; i < count && 10 + 6 * i + 6 <= box.length; i++) {
                    var at = 10 + 6 * i;
                    var type = ((box[at + 2] & 0xFF) << 8) | (box[at + 3] & 0xFF);
                    if (type == 1 && result == NO_OPACITY) {
                        result = STRAIGHT_OPACITY;
                    } else if (type == 2) {
                        result = PREMULTIPLIED_OPACITY;
                    }
                }
                return result;
            }
            return NO_OPACITY;
        }

        @Override
        public String type() {
            return "j2kH";
        }
    }

    /**
     * One component of an {@link UncompressedConfiguration}.
     *
     * @param index the index of the component in the {@link ComponentDefinition}
     * @param bitDepth the bits per value, from 1 to 256
     * @param format {@code component_format}: 0 unsigned integer, 1 floating point, 2 complex,
     * 3 signed integer
     * @param alignSize the bytes each value occupies, or 0 for values packed bit after bit
     */
    record UncompressedComponent(int index, int bitDepth, int format, int alignSize) {
    }

    /**
     * The uncompressed frame configuration ({@code uncC}, ISO/IEC 23001-17 clause 5.2): how the
     * values of the components are laid out in the data of an {@code unci} item.
     *
     * <p>A version 1 box names one of the profiles {@code rgb3}, {@code rgba} and {@code abgr},
     * which this record holds expanded into their fields; {@link #shorthand()} then reports the
     * components it implies, with the component types of red, green, blue and alpha as their
     * indices.
     *
     * @param profile the four-character profile, or 0 for none
     * @param shorthand whether the record was expanded from a version 1 profile, whose
     * components carry their component types rather than indices into a {@link ComponentDefinition}
     * @param components the components in the order their values are stored
     * @param samplingType {@code sampling_type}: 0 no subsampling, 1 4:2:2, 2 4:2:0, 3 4:1:1
     * @param interleaveType {@code interleave_type}: 0 component, 1 pixel, 2 mixed, 3 row,
     * 4 tile-component, 5 multi-Y
     * @param blockSize the bytes of a block, or 0 for no blocks
     * @param componentsLittleEndian whether multi-byte values are stored least significant byte
     * first
     * @param blockPadLsb whether the padding of a block is at its least significant end
     * @param blockLittleEndian whether a block is stored least significant byte first
     * @param blockReversed whether the first value of a block is at its least significant end
     * @param padUnknown whether padding bits have unspecified values
     * @param pixelSize the bytes of a pixel, or 0 for no pixel padding
     * @param rowAlignSize the multiple of bytes a row is padded to, or 0
     * @param tileAlignSize the multiple of bytes a tile is padded to, or 0
     * @param tileColumns the number of tile columns, at least 1
     * @param tileRows the number of tile rows, at least 1
     */
    record UncompressedConfiguration(int profile, boolean shorthand, java.util.List<UncompressedComponent> components,
                                     int samplingType, int interleaveType, int blockSize,
                                     boolean componentsLittleEndian, boolean blockPadLsb, boolean blockLittleEndian,
                                     boolean blockReversed, boolean padUnknown, long pixelSize, long rowAlignSize,
                                     long tileAlignSize, long tileColumns, long tileRows) implements HeifProperty {
        /**
         * Parses the box, expanding a version 1 profile.
         *
         * <p>A box of a version above 1, or of version 1 with a profile other than {@code rgb3},
         * {@code rgba} and {@code abgr}, becomes an {@link Unknown} property of type
         * {@code uncC}, so that the file stays readable and only the decoding of its item fails.
         *
         * @param box the cursor over the payload
         * @return the configuration, or an {@link Unknown} property for a version or profile this
         * parser does not know
         * @throws MediaException.Malformed if the box is truncated
         */
        static HeifProperty parse(BoxCursor box) {
            var version = box.fullBoxHeader() >>> 24;
            if (version > 1) {
                return new Unknown("uncC");
            }
            var profile = box.fourCc();
            if (version == 1) {
                var types = switch (com.github.auties00.smalt.internal.io.FourCc.toString(profile)) {
                    case "rgb3" -> new int[]{4, 5, 6};
                    case "rgba" -> new int[]{4, 5, 6, 7};
                    case "abgr" -> new int[]{7, 6, 5, 4};
                    default -> null;
                };
                if (types == null) {
                    return new Unknown("uncC");
                }
                var components = new java.util.ArrayList<UncompressedComponent>(types.length);
                for (var type : types) {
                    components.add(new UncompressedComponent(type, 8, 0, 0));
                }
                return new UncompressedConfiguration(profile, true, java.util.List.copyOf(components), 0, 1, 0, false,
                        false, false, false, false, 0, 0, 0, 1, 1);
            }
            var count = box.u32();
            if (count > box.remaining() / 5) {
                throw box.truncated();
            }
            var components = new java.util.ArrayList<UncompressedComponent>((int) count);
            for (var i = 0; i < count; i++) {
                components.add(new UncompressedComponent(box.u16(), box.u8() + 1, box.u8(), box.u8()));
            }
            var samplingType = box.u8();
            var interleaveType = box.u8();
            var blockSize = box.u8();
            var flags = box.u8();
            return new UncompressedConfiguration(profile, false, java.util.List.copyOf(components), samplingType,
                    interleaveType, blockSize, (flags & 0x80) != 0, (flags & 0x40) != 0, (flags & 0x20) != 0,
                    (flags & 0x10) != 0, (flags & 0x08) != 0, box.u32(), box.u32(), box.u32(), box.u32() + 1,
                    box.u32() + 1);
        }

        @Override
        public String type() {
            return "uncC";
        }
    }

    /**
     * The component definition ({@code cmpd}, ISO/IEC 23001-17 clause 5.1): the type of each
     * component an {@link UncompressedConfiguration} refers to by index.
     *
     * @param types the {@code component_type} of each component: 0 monochrome, 1 Y, 2 Cb, 3 Cr,
     * 4 red, 5 green, 6 blue, 7 alpha, 8 depth, 9 disparity, 10 palette, 11 filter array,
     * 12 padded, 13 cyan, 14 magenta, 15 yellow, 16 black, 0x8000 and above defined by a URI
     */
    record ComponentDefinition(int[] types) implements HeifProperty {
        /**
         * Parses the box.
         *
         * @param box the cursor over the payload
         * @return the definition
         * @throws MediaException.Malformed if the box is truncated
         */
        static ComponentDefinition parse(BoxCursor box) {
            var count = box.u32();
            if (count > box.remaining() / 2) {
                throw box.truncated();
            }
            var types = new int[(int) count];
            for (var i = 0; i < types.length; i++) {
                types[i] = box.u16();
                if (types[i] >= 0x8000) {
                    box.string();
                }
            }
            return new ComponentDefinition(types);
        }

        @Override
        public String type() {
            return "cmpd";
        }
    }

    /**
     * The generic compression configuration ({@code cmpC}, ISO/IEC 23001-17 Amendment 2) of the
     * data of an item.
     *
     * @param compressionType the four-character compression type, such as {@code "defl"}
     * @param individualEntities {@code must_decompress_individual_entities}, the most significant bit
     * of the unit type byte
     * @param unitType {@code compressed_unit_type}, the seven other bits: 0 when the whole item data
     * is one compressed stream, 1 for one stream per image, 2 per tile, 3 per row and 4 per pixel,
     * the units of types 1 to 4 being located by a {@link CompressedUnits} property
     */
    record CompressionConfiguration(String compressionType, boolean individualEntities, int unitType)
            implements HeifProperty {
        @Override
        public String type() {
            return "cmpC";
        }
    }

    /**
     * The generically compressed units information ({@code icef}, ISO/IEC 23001-17 Amendment 2):
     * where each compressed unit of the data of an item starts and how long it is.
     *
     * @param offsets the byte offset of each unit from the start of the item data
     * @param sizes the byte length of each unit
     */
    record CompressedUnits(long[] offsets, long[] sizes) implements HeifProperty {
        /**
         * Parses the box: a byte of offset and size codes, the unit count, then each unit's offset
         * (implied by the previous units for code 0, otherwise of 2, 3, 4 or 8 bytes for codes 1 to
         * 4) and size (of 1, 2, 3, 4 or 8 bytes for codes 0 to 4).
         *
         * @param box the cursor over the payload
         * @return the units
         * @throws MediaException.Malformed if the box is truncated, of a version other than 0, or
         * uses a reserved code
         */
        static CompressedUnits parse(BoxCursor box) {
            var version = box.fullBoxHeader() >>> 24;
            if (version != 0) {
                throw new MediaException.Malformed("heif: icef box of version " + version);
            }
            var codes = box.u8();
            var offsetCode = codes >> 5;
            var sizeCode = (codes >> 2) & 7;
            if (offsetCode > 4 || sizeCode > 4) {
                throw new MediaException.Malformed("heif: icef box with reserved unit offset code " + offsetCode
                        + " or unit size code " + sizeCode);
            }
            var count = box.u32();
            var offsetBytes = new int[]{0, 2, 3, 4, 8}[offsetCode];
            var sizeBytes = new int[]{1, 2, 3, 4, 8}[sizeCode];
            if (count > box.remaining() / (offsetBytes + sizeBytes)) {
                throw box.truncated();
            }
            var offsets = new long[(int) count];
            var sizes = new long[(int) count];
            var implied = 0L;
            for (var i = 0; i < offsets.length; i++) {
                offsets[i] = offsetBytes == 0 ? implied : offsetBytes == 3 ? box.u24() : box.uN(offsetBytes);
                sizes[i] = sizeBytes == 3 ? box.u24() : box.uN(sizeBytes);
                if (offsets[i] < 0 || sizes[i] < 0) {
                    throw new MediaException.Malformed("heif: icef unit " + i + " lies beyond 2^63 bytes");
                }
                implied = offsets[i] + sizes[i];
                if (implied < 0) {
                    throw new MediaException.Malformed("heif: icef unit " + i + " ends beyond 2^63 bytes");
                }
            }
            return new CompressedUnits(offsets, sizes);
        }

        @Override
        public String type() {
            return "icef";
        }
    }

    /**
     * The component palette ({@code cpal}, ISO/IEC 23001-17 clause 6.1.3): the values a palette
     * component of an uncompressed image indexes, one value per palette component for each entry.
     *
     * @param components the components each entry gives, with their index in the
     * {@link ComponentDefinition}, bit depth and format; their {@code alignSize} is the number of
     * bytes each value takes in {@link #values()}
     * @param count the number of entries
     * @param values the values, entry after entry and in each entry component after component, each
     * one big-endian in the smallest of 1, 2, 4, 8, 16 or 32 bytes that holds its bit depth, with the
     * value in the least significant bits
     */
    record ComponentPalette(java.util.List<UncompressedComponent> components, int count, byte[] values)
            implements HeifProperty {
        /**
         * Parses the box.
         *
         * @param box the cursor over the payload
         * @return the palette
         * @throws MediaException.Malformed if the box is truncated
         */
        static ComponentPalette parse(BoxCursor box) {
            box.fullBoxHeader();
            var componentCount = box.u16();
            if (componentCount > box.remaining() / 4) {
                throw box.truncated();
            }
            var components = new java.util.ArrayList<UncompressedComponent>(componentCount);
            var entryBytes = 0;
            for (var i = 0; i < componentCount; i++) {
                var index = box.u16();
                var depth = box.u8() + 1;
                var format = box.u8();
                var bytes = depth <= 8 ? 1 : depth <= 16 ? 2 : depth <= 32 ? 4 : depth <= 64 ? 8 : depth <= 128 ? 16 : 32;
                entryBytes += bytes;
                components.add(new UncompressedComponent(index, depth, format, bytes));
            }
            var count = box.u32();
            if (entryBytes == 0 ? count != 0 : count > box.remaining() / entryBytes) {
                throw box.truncated();
            }
            var values = box.bytes((int) count * entryBytes);
            return new ComponentPalette(java.util.List.copyOf(components), (int) count, values);
        }

        @Override
        public String type() {
            return "cpal";
        }
    }

    /**
     * The component pattern definition ({@code cpat}, ISO/IEC 23001-17 clause 6.1.4): the color
     * filter array a filter array component samples, as a pattern repeated over the image.
     *
     * @param width the pattern width, at least 1
     * @param height the pattern height, at least 1
     * @param components the index in the {@link ComponentDefinition} of the component each position
     * of the pattern samples, row by row
     * @param gains the gain of each position of the pattern, row by row
     */
    record ComponentPattern(int width, int height, long[] components, float[] gains) implements HeifProperty {
        /**
         * Parses the box.
         *
         * @param box the cursor over the payload
         * @return the pattern
         * @throws MediaException.Malformed if the box is truncated or the pattern is empty
         */
        static ComponentPattern parse(BoxCursor box) {
            box.fullBoxHeader();
            var width = box.u16();
            var height = box.u16();
            if (width == 0 || height == 0) {
                throw new MediaException.Malformed("heif: cpat box with an empty " + width + "x" + height + " pattern");
            }
            var count = (long) width * height;
            if (count > box.remaining() / 8) {
                throw box.truncated();
            }
            var components = new long[(int) count];
            var gains = new float[(int) count];
            for (var i = 0; i < count; i++) {
                components[i] = box.u32();
                gains[i] = Float.intBitsToFloat(box.s32());
            }
            return new ComponentPattern(width, height, components, gains);
        }

        @Override
        public String type() {
            return "cpat";
        }
    }

    /**
     * The chroma location ({@code cloc}, ISO/IEC 23001-17 clause 6.1.8).
     *
     * @param location the H.273 {@code ChromaSampleLocType}, from 0 to 5, or 6 for the two-row
     * location of ISO/IEC 23001-17
     */
    record ChromaLocation(int location) implements HeifProperty {
        @Override
        public String type() {
            return "cloc";
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
