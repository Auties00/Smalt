package com.github.auties00.smalt.internal.heif;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.internal.bits.MsbBitReader;
import com.github.auties00.smalt.internal.io.ByteWriter;
import com.github.auties00.smalt.internal.io.FourCc;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Transforms the low-overhead image file format's {@code MinimizedImageBox} ({@code mini}, brand {@code mif3}, ISO/IEC
 * 23008-12:2024 Amendment 2, clause O.4) into the equivalent {@code meta} box, which {@link HeifFile} then parses like
 * the one of any other HEIF file.
 *
 * <p>A {@code mini} box codes in a few bits what a {@code meta} box spells out: one coded image item (identifier 1)
 * with its codec configuration, {@code ispe}, {@code pixi} and {@code colr} properties (the nclx values and an ICC
 * profile), an alpha auxiliary item when the box codes alpha data (2, with {@code auxl} and, for premultiplied alpha, {@code prem}
 * references), an optional gain map (a {@code tmap} item 3 derived from the image and the gain map item 4, an
 * alternative to the image in an {@code altr} group), the HDR properties ({@code clli}, {@code mdcv}, {@code cclv},
 * {@code amve}, {@code reve}, {@code ndwt}), the orientation as {@code irot} and {@code imir} properties, and
 * {@code Exif} (6) and XMP (7) metadata items that describe the image. The item data follows the header in a fixed
 * order: the ICC profiles, the gain map metadata, the alpha, gain map and main image data, the EXIF and the XMP data.
 * The coded items are located in the file; the EXIF item, which the box stores without the offset of its TIFF header,
 * and the tone map item, which the box stores without its version byte, are built in an {@code idat} box.
 *
 * <p>Without explicit codec types the minor version of the {@code ftyp} box gives the coding: {@code avif} for AV1
 * ({@code av01} items, {@code av1C} configuration) and {@code heic} for HEVC ({@code hvc1}, {@code hvcC}).
 *
 * @implNote The syntax and the transformation follow libavif's {@code avifParseMinimizedImageBox} ({@code src/read.c},
 * BSD 2-clause), including its mapping of the orientation to {@code irot} and {@code imir}.
 */
final class MinimizedImageBox {
    /**
     * The auxiliary type of an alpha plane (ISO/IEC 23091-2).
     */
    private static final String ALPHA_URN = "urn:mpeg:mpegB:cicp:systems:auxiliary:alpha";

    /**
     * The identifier of the coded image item.
     */
    private static final int MAIN_ID = 1;

    /**
     * The identifier of the alpha item.
     */
    private static final int ALPHA_ID = 2;

    /**
     * The identifier of the tone map item.
     */
    private static final int TONE_MAP_ID = 3;

    /**
     * The identifier of the gain map item.
     */
    private static final int GAIN_MAP_ID = 4;

    /**
     * The identifier of the EXIF item.
     */
    private static final int EXIF_ID = 6;

    /**
     * The identifier of the XMP item.
     */
    private static final int XMP_ID = 7;

    /**
     * The identifier of the alternatives group of the tone map item and the image.
     */
    private static final int GROUP_ID = 8;

    private MinimizedImageBox() {
        throw new AssertionError();
    }

    /**
     * An item of the equivalent {@code meta} box.
     *
     * @param id the identifier
     * @param type the item type
     * @param contentType the content type of a {@code mime} item, or {@code null}
     * @param method the construction method: {@code 0} for data in the file, {@code 1} for data in the {@code idat}
     * @param offset the offset of the data in the file or in the {@code idat}
     * @param length the length of the data
     * @param properties the indices of the associated properties, from 1, essential ones with bit 7 set
     */
    private record Item(int id, String type, String contentType, int method, long offset, long length,
                        List<Integer> properties) {
    }

    /**
     * The properties and their associations being built.
     */
    private static final class Properties {
        /**
         * The property boxes, in property index order.
         */
        private final List<byte[]> boxes = new ArrayList<>();

        /**
         * Adds a property box.
         *
         * @param box the box, header included
         * @return the property index, from 1
         */
        int add(byte[] box) {
            boxes.add(box);
            return boxes.size();
        }
    }

    /**
     * The transformation of a {@code mini} box.
     *
     * @param meta the payload of the equivalent {@code meta} box: its version and flags, then its children
     * @param toneMapped whether it has a tone map item, which implies the {@code tmap} brand
     */
    record Transformed(byte[] meta, boolean toneMapped) {
    }

    /**
     * Returns the {@code meta} box payload equivalent to a {@code mini} box.
     *
     * @param mini the payload of the {@code mini} box
     * @param payloadOffset the file offset of that payload
     * @param minorVersion the minor version of the {@code ftyp} box
     * @return the equivalent {@code meta} box
     * @throws MediaException.Malformed if the box is truncated, has a version other than 0, holds more or fewer bytes
     * than its chunk sizes declare, or codes neither explicit codec types nor a known minor version
     * @throws MediaException.Unsupported if the EXIF and XMP data are compressed
     */
    static Transformed toMeta(byte[] mini, long payloadOffset, String minorVersion) {
        var bits = new MsbBitReader(mini);
        if (bits.read(2) != 0) {
            throw new MediaException.Malformed("heif: unsupported mini box version");
        }
        var explicitCodecTypes = bits.readFlag();
        var floatFlag = bits.readFlag();
        var fullRange = bits.readFlag();
        var hasAlpha = bits.readFlag();
        var explicitCicp = bits.readFlag();
        var hasHdr = bits.readFlag();
        var hasIcc = bits.readFlag();
        var hasExif = bits.readFlag();
        var hasXmp = bits.readFlag();
        var chromaSubsampling = bits.read(2);
        var orientation = bits.read(3) + 1;
        var largeDimensions = bits.readFlag();
        var dimensionBits = largeDimensions ? 15 : 7;
        var width = bits.read(dimensionBits) + 1;
        var height = bits.read(dimensionBits) + 1;
        if (chromaSubsampling == 1 || chromaSubsampling == 2) {
            bits.read(1); // chroma_is_horizontally_centered
        }
        if (chromaSubsampling == 1) {
            bits.read(1); // chroma_is_vertically_centered
        }
        var bitDepth = readBitDepth(bits, floatFlag);
        var alphaPremultiplied = hasAlpha && bits.readFlag();
        int primaries;
        int transfer;
        int matrix;
        if (explicitCicp) {
            primaries = bits.read(8);
            transfer = bits.read(8);
            matrix = chromaSubsampling != 0 ? bits.read(8) : 2;
        } else {
            primaries = hasIcc ? 2 : 1;
            transfer = hasIcc ? 2 : 13;
            matrix = chromaSubsampling == 0 ? 2 : 6;
        }
        String itemType;
        String configType;
        if (explicitCodecTypes) {
            itemType = FourCc.toString(bits.read(32));
            configType = FourCc.toString(bits.read(32));
        } else if (minorVersion.equals("avif")) {
            itemType = "av01";
            configType = "av1C";
        } else if (minorVersion.equals("heic")) {
            itemType = "hvc1";
            configType = "hvcC";
        } else {
            throw new MediaException.Malformed("heif: the mini box codes no codec types and the minor version '"
                    + minorVersion + "' names no coding");
        }

        var hasGainMap = false;
        var gainMapWidth = 0;
        var gainMapHeight = 0;
        var gainMapMatrix = 0;
        var gainMapFullRange = false;
        var gainMapChroma = 0;
        var gainMapDepth = 0;
        var toneMapHasIcc = false;
        var toneMapExplicitCicp = false;
        int toneMapPrimaries = 1;
        int toneMapTransfer = 13;
        int toneMapMatrix = 6;
        var toneMapFullRange = true;
        List<byte[]> hdrProperties = List.of();
        List<byte[]> toneMapHdrProperties = List.of();
        if (hasHdr) {
            hasGainMap = bits.readFlag();
            if (hasGainMap) {
                if (bits.readFlag()) {
                    gainMapWidth = width;
                    gainMapHeight = height;
                } else {
                    gainMapWidth = bits.read(dimensionBits) + 1;
                    gainMapHeight = bits.read(dimensionBits) + 1;
                }
                gainMapMatrix = bits.read(8);
                gainMapFullRange = bits.readFlag();
                gainMapChroma = bits.read(2);
                if (gainMapChroma == 1 || gainMapChroma == 2) {
                    bits.read(1);
                }
                if (gainMapChroma == 1) {
                    bits.read(1);
                }
                gainMapDepth = readBitDepth(bits, bits.readFlag());
                toneMapHasIcc = bits.readFlag();
                toneMapExplicitCicp = bits.readFlag();
                if (toneMapExplicitCicp) {
                    toneMapPrimaries = bits.read(8);
                    toneMapTransfer = bits.read(8);
                    toneMapMatrix = bits.read(8);
                    toneMapFullRange = bits.readFlag();
                }
            }
            hdrProperties = readHdrProperties(bits);
            if (hasGainMap) {
                toneMapHdrProperties = readHdrProperties(bits);
            }
        }

        var largeMetadata = (hasIcc || hasExif || hasXmp || hasGainMap) && bits.readFlag();
        var largeCodecConfig = bits.readFlag();
        var largeItemData = bits.readFlag();
        var metadataBits = largeMetadata ? 20 : 10;
        var codecConfigBits = largeCodecConfig ? 12 : 3;
        var itemDataBits = largeItemData ? 28 : 15;
        var iccSize = hasIcc ? bits.read(metadataBits) + 1L : 0;
        var toneMapIccSize = hasGainMap && toneMapHasIcc ? bits.read(metadataBits) + 1L : 0;
        long gainMapMetadataSize = 0;
        long gainMapDataSize = 0;
        var gainMapConfigSize = 0;
        if (hasGainMap) {
            gainMapMetadataSize = bits.read(metadataBits);
            gainMapDataSize = bits.read(itemDataBits);
            if (gainMapDataSize != 0) {
                gainMapConfigSize = bits.read(codecConfigBits);
            }
        }
        var mainConfigSize = bits.read(codecConfigBits);
        var mainDataSize = bits.read(itemDataBits) + 1L;
        long alphaDataSize = hasAlpha ? bits.read(itemDataBits) : 0;
        var alphaConfigSize = alphaDataSize != 0 ? bits.read(codecConfigBits) : 0;
        if ((hasExif || hasXmp) && bits.readFlag()) {
            throw new MediaException.Unsupported("heif", "compressed EXIF and XMP data in a mini box");
        }
        var exifSize = hasExif ? bits.read(metadataBits) + 1L : 0;
        var xmpSize = hasXmp ? bits.read(metadataBits) + 1L : 0;
        bits.alignToByte();
        if (bits.overrun()) {
            throw new MediaException.Malformed("heif: truncated mini box");
        }

        var position = (int) (bits.bitPosition() >>> 3);
        var mainConfig = slice(mini, position, mainConfigSize);
        position += mainConfigSize;
        var alphaConfig = mainConfig;
        if (alphaConfigSize != 0) {
            alphaConfig = slice(mini, position, alphaConfigSize);
            position += alphaConfigSize;
        }
        var gainMapConfig = mainConfig;
        if (gainMapConfigSize != 0) {
            gainMapConfig = slice(mini, position, gainMapConfigSize);
            position += gainMapConfigSize;
        }
        var chunks = iccSize + toneMapIccSize + gainMapMetadataSize + alphaDataSize + gainMapDataSize + mainDataSize
                + exifSize + xmpSize;
        if (mini.length - position != chunks) {
            throw new MediaException.Malformed("heif: the mini box holds " + (mini.length - position)
                    + " bytes of item data where its chunk sizes declare " + chunks);
        }
        var icc = slice(mini, position, (int) iccSize);
        position += (int) iccSize;
        var toneMapIcc = slice(mini, position, (int) toneMapIccSize);
        position += (int) toneMapIccSize;
        var gainMapMetadata = slice(mini, position, (int) gainMapMetadataSize);
        position += (int) gainMapMetadataSize;
        var alphaOffset = position;
        position += (int) alphaDataSize;
        var gainMapOffset = position;
        position += (int) gainMapDataSize;
        var mainOffset = position;
        position += (int) mainDataSize;
        var exif = slice(mini, position, (int) exifSize);
        position += (int) exifSize;
        var xmpOffset = position;

        var properties = new Properties();
        var idat = new ByteWriter(64);
        var items = new ArrayList<Item>();
        var mainProperties = new ArrayList<Integer>();
        mainProperties.add(0x80 | properties.add(box(configType, mainConfig)));
        var extent = properties.add(extent(width, height));
        mainProperties.add(extent);
        mainProperties.add(properties.add(pixelInformation(chromaSubsampling == 0 ? 1 : 3, bitDepth)));
        mainProperties.add(properties.add(nclx(primaries, transfer, matrix, fullRange)));
        if (icc.length != 0) {
            mainProperties.add(properties.add(profile(icc)));
        }
        for (var property : hdrProperties) {
            mainProperties.add(properties.add(property));
        }
        var transforms = new ArrayList<Integer>();
        if (orientation == 3 || orientation == 5 || orientation == 6 || orientation == 7 || orientation == 8) {
            var angle = orientation == 3 ? 2 : orientation == 5 || orientation == 8 ? 1 : 3;
            transforms.add(0x80 | properties.add(box("irot", new byte[]{(byte) angle})));
        }
        if (orientation == 2 || orientation == 4 || orientation == 5 || orientation == 7) {
            transforms.add(0x80 | properties.add(box("imir", new byte[]{(byte) (orientation == 2 ? 1 : 0)})));
        }
        mainProperties.addAll(transforms);
        items.add(new Item(MAIN_ID, itemType, null, 0, payloadOffset + mainOffset, mainDataSize, mainProperties));

        var hasAlphaItem = alphaDataSize != 0;
        if (hasAlphaItem) {
            var alphaProperties = new ArrayList<Integer>();
            alphaProperties.add(0x80 | properties.add(box(configType, alphaConfig)));
            alphaProperties.add(0x80 | properties.add(auxiliaryType()));
            alphaProperties.add(extent);
            alphaProperties.add(properties.add(pixelInformation(1, bitDepth)));
            alphaProperties.addAll(transforms);
            items.add(new Item(ALPHA_ID, itemType, null, 0, payloadOffset + alphaOffset, alphaDataSize,
                    alphaProperties));
        }
        if (hasGainMap) {
            var rotated = orientation > 4;
            var toneMapProperties = new ArrayList<Integer>();
            toneMapProperties.add(properties.add(extent(rotated ? height : width, rotated ? width : height)));
            if (toneMapExplicitCicp || !toneMapHasIcc) {
                toneMapProperties.add(properties.add(nclx(toneMapPrimaries, toneMapTransfer, toneMapMatrix,
                        toneMapFullRange)));
            }
            if (toneMapIcc.length != 0) {
                toneMapProperties.add(properties.add(profile(toneMapIcc)));
            }
            for (var property : toneMapHdrProperties) {
                toneMapProperties.add(properties.add(property));
            }
            var toneMapOffset = idat.size();
            idat.u8(0);
            idat.bytes(gainMapMetadata);
            items.add(new Item(TONE_MAP_ID, "tmap", null, 1, toneMapOffset, gainMapMetadataSize + 1,
                    toneMapProperties));
            if (gainMapDataSize != 0) {
                var gainMapProperties = new ArrayList<Integer>();
                gainMapProperties.add(0x80 | properties.add(box(configType, gainMapConfig)));
                gainMapProperties.add(properties.add(extent(gainMapWidth, gainMapHeight)));
                gainMapProperties.add(properties.add(pixelInformation(gainMapChroma == 0 ? 1 : 3, gainMapDepth)));
                gainMapProperties.add(properties.add(nclx(2, 2, gainMapMatrix, gainMapFullRange)));
                gainMapProperties.addAll(transforms);
                items.add(new Item(GAIN_MAP_ID, itemType, null, 0, payloadOffset + gainMapOffset, gainMapDataSize,
                        gainMapProperties));
            }
        }
        if (hasExif) {
            var payload = HeifWriter.exifPayload(exif);
            items.add(new Item(EXIF_ID, "Exif", null, 1, idat.size(), payload.length, List.of()));
            idat.bytes(payload);
        }
        if (hasXmp) {
            items.add(new Item(XMP_ID, "mime", HeifFile.XMP_TYPE, 0, payloadOffset + xmpOffset, xmpSize,
                    List.of()));
        }
        return new Transformed(meta(items, properties, idat.toByteArray(), hasAlphaItem, alphaPremultiplied,
                hasGainMap && gainMapDataSize != 0, hasGainMap), hasGainMap);
    }

    /**
     * Reads a bit depth: {@code bit_depth_log2_minus4} for floating-point samples, otherwise
     * {@code high_bit_depth_flag} and {@code bit_depth_minus9}.
     *
     * @param bits the reader
     * @param floatFlag whether the samples are floating point
     * @return the bit depth
     */
    private static int readBitDepth(MsbBitReader bits, boolean floatFlag) {
        if (floatFlag) {
            return 1 << (bits.read(2) + 4);
        }
        return bits.readFlag() ? bits.read(3) + 9 : 8;
    }

    /**
     * Reads the HDR properties of an image: the flags of the content light level, mastering display color volume,
     * content color volume, ambient viewing environment, reference viewing environment and nominal diffuse white
     * properties, then the present ones.
     *
     * @param bits the reader
     * @return the present properties as boxes, in that order
     */
    private static List<byte[]> readHdrProperties(MsbBitReader bits) {
        var clli = bits.readFlag();
        var mdcv = bits.readFlag();
        var cclv = bits.readFlag();
        var amve = bits.readFlag();
        var reve = bits.readFlag();
        var ndwt = bits.readFlag();
        var boxes = new ArrayList<byte[]>();
        if (clli) {
            boxes.add(box("clli", copy(bits, 16, 16)));
        }
        if (mdcv) {
            boxes.add(box("mdcv", copy(bits, 16, 16, 16, 16, 16, 16, 16, 16, 32, 32)));
        }
        if (cclv) {
            var flags = bits.read(8);
            var widths = new ArrayList<Integer>();
            if ((flags & 0x20) != 0) {
                for (var i = 0; i < 6; i++) {
                    widths.add(32);
                }
            }
            for (var mask : new int[]{0x10, 0x08, 0x04}) {
                if ((flags & mask) != 0) {
                    widths.add(32);
                }
            }
            var fields = copy(bits, widths.stream().mapToInt(Integer::intValue).toArray());
            var payload = new byte[fields.length + 1];
            payload[0] = (byte) flags;
            System.arraycopy(fields, 0, payload, 1, fields.length);
            boxes.add(box("cclv", payload));
        }
        if (amve) {
            boxes.add(box("amve", copy(bits, 32, 16, 16)));
        }
        if (reve) {
            boxes.add(box("reve", copy(bits, 32, 16, 16, 32, 16, 16)));
        }
        if (ndwt) {
            boxes.add(box("ndwt", copy(bits, 32)));
        }
        return boxes;
    }

    /**
     * Copies fields into big-endian bytes.
     *
     * @param bits the reader
     * @param widths the widths of the fields, each a multiple of 8 up to 32
     * @return the fields
     */
    private static byte[] copy(MsbBitReader bits, int... widths) {
        var writer = new ByteWriter(16);
        for (var width : widths) {
            var value = bits.read(width);
            for (var shift = width - 8; shift >= 0; shift -= 8) {
                writer.u8(value >>> shift);
            }
        }
        return writer.toByteArray();
    }

    /**
     * Returns a range of the box.
     *
     * @param data the box payload
     * @param offset the start of the range
     * @param length the length of the range
     * @return a copy of the range
     */
    private static byte[] slice(byte[] data, int offset, int length) {
        if (length > data.length - offset) {
            throw new MediaException.Malformed("heif: truncated mini box");
        }
        return Arrays.copyOfRange(data, offset, offset + length);
    }

    /**
     * Returns a box.
     *
     * @param type the box type
     * @param payload the payload
     * @return the box, header included
     */
    private static byte[] box(String type, byte[] payload) {
        var writer = new ByteWriter(payload.length + 8);
        writer.begin(FourCc.of(type));
        writer.bytes(payload);
        writer.end();
        return writer.toByteArray();
    }

    /**
     * Returns an image spatial extents property.
     *
     * @param width the width
     * @param height the height
     * @return the {@code ispe} box
     */
    private static byte[] extent(int width, int height) {
        var writer = new ByteWriter(20);
        writer.beginFull(FourCc.of("ispe"), 0, 0);
        writer.u32be(width);
        writer.u32be(height);
        writer.end();
        return writer.toByteArray();
    }

    /**
     * Returns a pixel information property.
     *
     * @param channels the number of channels
     * @param bitDepth the bit depth of each
     * @return the {@code pixi} box
     */
    private static byte[] pixelInformation(int channels, int bitDepth) {
        var writer = new ByteWriter(16);
        writer.beginFull(FourCc.of("pixi"), 0, 0);
        writer.u8(channels);
        for (var i = 0; i < channels; i++) {
            writer.u8(bitDepth);
        }
        writer.end();
        return writer.toByteArray();
    }

    /**
     * Returns a color information property of type {@code nclx}.
     *
     * @param primaries the color primaries
     * @param transfer the transfer characteristics
     * @param matrix the matrix coefficients
     * @param fullRange whether the samples use the full range
     * @return the {@code colr} box
     */
    private static byte[] nclx(int primaries, int transfer, int matrix, boolean fullRange) {
        var writer = new ByteWriter(19);
        writer.begin(FourCc.of("colr"));
        writer.fourCc("nclx");
        writer.u16be(primaries);
        writer.u16be(transfer);
        writer.u16be(matrix);
        writer.u8(fullRange ? 0x80 : 0);
        writer.end();
        return writer.toByteArray();
    }

    /**
     * Returns a color information property that holds an ICC profile.
     *
     * @param icc the profile
     * @return the {@code colr} box
     */
    private static byte[] profile(byte[] icc) {
        var writer = new ByteWriter(icc.length + 12);
        writer.begin(FourCc.of("colr"));
        writer.fourCc("prof");
        writer.bytes(icc);
        writer.end();
        return writer.toByteArray();
    }

    /**
     * Returns the auxiliary type property of an alpha item.
     *
     * @return the {@code auxC} box
     */
    private static byte[] auxiliaryType() {
        var writer = new ByteWriter(64);
        writer.beginFull(FourCc.of("auxC"), 0, 0);
        writer.cString(ALPHA_URN);
        writer.end();
        return writer.toByteArray();
    }

    /**
     * Writes the {@code meta} box payload.
     *
     * @param items the items, in increasing identifier order
     * @param properties the properties
     * @param idat the {@code idat} payload
     * @param hasAlpha whether item 2 is an alpha item
     * @param premultiplied whether the image is premultiplied by its alpha
     * @param hasGainMap whether item 4 is the gain map
     * @param hasToneMap whether item 3 is the tone map
     * @return the payload
     */
    private static byte[] meta(List<Item> items, Properties properties, byte[] idat, boolean hasAlpha,
                               boolean premultiplied, boolean hasGainMap, boolean hasToneMap) {
        var writer = new ByteWriter(1024);
        writer.u32be(0);
        writer.beginFull(FourCc.of("hdlr"), 0, 0);
        writer.u32be(0);
        writer.fourCc("pict");
        writer.zeros(12);
        writer.u8(0);
        writer.end();
        writer.beginFull(FourCc.of("pitm"), 0, 0);
        writer.u16be(MAIN_ID);
        writer.end();
        writer.beginFull(FourCc.of("iinf"), 0, 0);
        writer.u16be(items.size());
        for (var item : items) {
            writer.beginFull(FourCc.of("infe"), 2, 0);
            writer.u16be(item.id());
            writer.u16be(0);
            writer.fourCc(item.type());
            writer.u8(0);
            if (item.contentType() != null) {
                writer.cString(item.contentType());
                writer.u8(0);
            }
            writer.end();
        }
        writer.end();
        writer.beginFull(FourCc.of("iloc"), 1, 0);
        writer.u16be(0x8800);
        writer.u16be(items.size());
        for (var item : items) {
            writer.u16be(item.id());
            writer.u16be(item.method());
            writer.u16be(0);
            writer.u16be(1);
            writer.u64be(item.offset());
            writer.u64be(item.length());
        }
        writer.end();
        writer.begin(FourCc.of("iprp"));
        writer.begin(FourCc.of("ipco"));
        for (var box : properties.boxes) {
            writer.bytes(box);
        }
        writer.end();
        var associated = items.stream().filter(item -> !item.properties().isEmpty()).toList();
        writer.beginFull(FourCc.of("ipma"), 0, 0);
        writer.u32be(associated.size());
        for (var item : associated) {
            writer.u16be(item.id());
            writer.u8(item.properties().size());
            for (var index : item.properties()) {
                writer.u8(index);
            }
        }
        writer.end();
        writer.end();
        writer.beginFull(FourCc.of("iref"), 0, 0);
        if (hasAlpha) {
            reference(writer, "auxl", ALPHA_ID, MAIN_ID);
            if (premultiplied) {
                reference(writer, "prem", MAIN_ID, ALPHA_ID);
            }
        }
        if (hasToneMap) {
            if (hasGainMap) {
                reference(writer, "dimg", TONE_MAP_ID, MAIN_ID, GAIN_MAP_ID);
            } else {
                reference(writer, "dimg", TONE_MAP_ID, MAIN_ID);
            }
        }
        for (var item : items) {
            if (item.id() == EXIF_ID || item.id() == XMP_ID) {
                reference(writer, "cdsc", item.id(), MAIN_ID);
            }
        }
        writer.end();
        if (idat.length != 0) {
            writer.begin(FourCc.of("idat"));
            writer.bytes(idat);
            writer.end();
        }
        if (hasToneMap) {
            writer.begin(FourCc.of("grpl"));
            writer.beginFull(FourCc.of("altr"), 0, 0);
            writer.u32be(GROUP_ID);
            writer.u32be(2);
            writer.u32be(TONE_MAP_ID);
            writer.u32be(MAIN_ID);
            writer.end();
            writer.end();
        }
        return writer.toByteArray();
    }

    /**
     * Writes a single item reference box.
     *
     * @param writer the writer, inside the {@code iref} box
     * @param type the reference type
     * @param from the referencing item
     * @param to the referenced items
     */
    private static void reference(ByteWriter writer, String type, int from, int... to) {
        writer.begin(FourCc.of(type));
        writer.u16be(from);
        writer.u16be(to.length);
        for (var id : to) {
            writer.u16be(id);
        }
        writer.end();
    }
}
