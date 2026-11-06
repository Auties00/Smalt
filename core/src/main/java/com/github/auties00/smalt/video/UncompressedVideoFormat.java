package com.github.auties00.smalt.video;

import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.internal.heif.HeifUncompressedWriter;
import com.github.auties00.smalt.track.Codec;

import java.io.ByteArrayOutputStream;
import java.util.Objects;

/**
 * Describes a frame layout of ISO/IEC 23001-17 uncompressed video, the frames of a
 * {@link Codec#UNCOMPRESSED_23001_17} track that {@link UncompressedVideoEncoder} writes: the components, their
 * depth and packing, the interleaving, the row alignment, the tiles and the generic compression.
 *
 * <p>The components are, in storage order, Y, Cb and Cr for {@link ColorModel#YCBCR} (Y, Cb, Y, Cr in each unit
 * of {@link Interleave#MULTI_Y}), red, green and blue for {@link ColorModel#RGB}, or one monochrome component, each
 * followed by alpha when {@link #alpha()} is set; every component has the same unsigned depth. Values are stored bit
 * after bit, most significant bit first, or in whole bytes: a value of 8 or 16 bits that starts on a byte boundary,
 * or every value with {@link #alignedComponents()}, least significant byte first with {@link #littleEndian()}. Every
 * row of a component plane, every row of pixels and every tile starts on a byte boundary, rows padded with zeros to
 * a multiple of {@link #rowAlignment()} bytes.
 *
 * <p>A {@link Codec#UNCOMPRESSED_23001_17} track's
 * {@linkplain com.github.auties00.smalt.track.VideoTrack#codecConfig() codec configuration} is
 * {@link #toCodecConfig()}: the component definition box ({@code cmpd}), the version 0 frame configuration box
 * ({@code uncC}) and, with {@link #compression()}, the generic compression box ({@code cmpC}, whole frames).
 * The decoder reads every layout of the standard it supports, not only these.
 *
 * <p>Instances are immutable and thread-safe.
 */
public final class UncompressedVideoFormat {
    /**
     * The color models of the components.
     */
    public enum ColorModel {
        /**
         * One monochrome component.
         */
        MONOCHROME,

        /**
         * Luma and two chroma components, subsampled as the {@linkplain #chromaFormat() chroma format} says.
         */
        YCBCR,

        /**
         * Red, green and blue components.
         */
        RGB
    }

    /**
     * The interleavings of the components ({@code interleave_type}).
     */
    public enum Interleave {
        /**
         * Each component's plane after the other ({@code interleave_type} 0).
         */
        COMPONENT,

        /**
         * The components of each pixel together, for layouts without chroma subsampling ({@code interleave_type} 1).
         */
        PIXEL,

        /**
         * The luma plane (and the alpha plane) followed by one plane of interleaved Cb and Cr, for 4:2:0 and 4:2:2
         * ({@code interleave_type} 2).
         */
        MIXED,

        /**
         * The rows of the components in turn, for layouts without vertical chroma subsampling
         * ({@code interleave_type} 3).
         */
        ROW,

        /**
         * Each component of every tile, component after component ({@code interleave_type} 4).
         */
        TILE_COMPONENT,

        /**
         * Units of one Cb and one Cr sample with the two luma samples that share them, Y, Cb, Y, Cr, for 4:2:2
         * without alpha ({@code interleave_type} 5).
         */
        MULTI_Y
    }

    /**
     * The generic compressions of whole frames (ISO/IEC 23001-17 Amendment 2).
     */
    public enum Compression {
        /**
         * No compression.
         */
        NONE,

        /**
         * A raw Deflate stream (RFC 1951), compression type {@code defl}.
         */
        DEFLATE,

        /**
         * A zlib stream (RFC 1950), compression type {@code zlib}.
         */
        ZLIB
    }

    /**
     * The largest row alignment, in bytes.
     */
    private static final int MAX_ROW_ALIGNMENT = 1 << 16;

    /**
     * The largest number of tile columns or rows.
     */
    private static final int MAX_TILES = 1 << 16;

    private final ColorModel colorModel;

    private final ChromaFormat chromaFormat;

    private final int bitDepth;

    private final boolean alpha;

    private final Interleave interleave;

    private final boolean alignedComponents;

    private final boolean littleEndian;

    private final int rowAlignment;

    private final int tileColumns;

    private final int tileRows;

    private final Compression compression;

    private UncompressedVideoFormat(ColorModel colorModel, ChromaFormat chromaFormat, int bitDepth, boolean alpha,
                                    Interleave interleave, boolean alignedComponents, boolean littleEndian,
                                    int rowAlignment, int tileColumns, int tileRows, Compression compression) {
        this.colorModel = colorModel;
        this.chromaFormat = chromaFormat;
        this.bitDepth = bitDepth;
        this.alpha = alpha;
        this.interleave = interleave;
        this.alignedComponents = alignedComponents;
        this.littleEndian = littleEndian;
        this.rowAlignment = rowAlignment;
        this.tileColumns = tileColumns;
        this.tileRows = tileRows;
        this.compression = compression;
        if (bitDepth < 1 || bitDepth > 16) {
            throw new IllegalArgumentException("invalid bit depth " + bitDepth);
        }
        if (littleEndian && !alignedComponents && bitDepth % 8 != 0) {
            throw new IllegalArgumentException("little-endian " + bitDepth + "-bit components must be aligned");
        }
        if (rowAlignment < 0 || rowAlignment > MAX_ROW_ALIGNMENT) {
            throw new IllegalArgumentException("invalid row alignment " + rowAlignment);
        }
        if (tileColumns < 1 || tileRows < 1 || tileColumns > MAX_TILES || tileRows > MAX_TILES) {
            throw new IllegalArgumentException("invalid tiles " + tileColumns + "x" + tileRows);
        }
        var subsampled = chromaFormat == ChromaFormat.YUV420 || chromaFormat == ChromaFormat.YUV422;
        var valid = switch (interleave) {
            case COMPONENT, TILE_COMPONENT -> true;
            case PIXEL -> !subsampled;
            case MIXED -> subsampled;
            case ROW -> chromaFormat != ChromaFormat.YUV420;
            case MULTI_Y -> chromaFormat == ChromaFormat.YUV422 && !alpha;
        };
        if (!valid) {
            throw new IllegalArgumentException(interleave + " interleaving cannot store " + colorModel + " "
                    + chromaFormat + (alpha ? " with alpha" : ""));
        }
    }

    /**
     * Returns a Y'CbCr format with the component interleaving, values packed bit after bit (in whole bytes at 8 and
     * 16 bits), big-endian, unaligned rows, one tile and no compression.
     *
     * @param chromaFormat the chroma subsampling: {@link ChromaFormat#YUV420}, {@link ChromaFormat#YUV422} or
     * {@link ChromaFormat#YUV444}
     * @param bitDepth the bits of every component, from 1 to 16
     * @return the format, without alpha
     * @throws IllegalArgumentException if the chroma format is not one of these or the depth is out of range
     */
    public static UncompressedVideoFormat ofYCbCr(ChromaFormat chromaFormat, int bitDepth) {
        Objects.requireNonNull(chromaFormat, "chromaFormat");
        if (chromaFormat != ChromaFormat.YUV420 && chromaFormat != ChromaFormat.YUV422
                && chromaFormat != ChromaFormat.YUV444) {
            throw new IllegalArgumentException("uncompressed video has no " + chromaFormat + " sampling");
        }
        return new UncompressedVideoFormat(ColorModel.YCBCR, chromaFormat, bitDepth, false, Interleave.COMPONENT,
                false, false, 0, 1, 1, Compression.NONE);
    }

    /**
     * Returns an RGB format with the pixel interleaving and otherwise the defaults of
     * {@link #ofYCbCr(ChromaFormat, int)}.
     *
     * @param bitDepth the bits of every component, from 1 to 16
     * @return the format, without alpha
     * @throws IllegalArgumentException if the depth is out of range
     */
    public static UncompressedVideoFormat ofRgb(int bitDepth) {
        return new UncompressedVideoFormat(ColorModel.RGB, ChromaFormat.YUV444, bitDepth, false, Interleave.PIXEL,
                false, false, 0, 1, 1, Compression.NONE);
    }

    /**
     * Returns a monochrome format with the defaults of {@link #ofYCbCr(ChromaFormat, int)}.
     *
     * @param bitDepth the bits of the component, from 1 to 16
     * @return the format, without alpha
     * @throws IllegalArgumentException if the depth is out of range
     */
    public static UncompressedVideoFormat ofMonochrome(int bitDepth) {
        return new UncompressedVideoFormat(ColorModel.MONOCHROME, ChromaFormat.MONOCHROME, bitDepth, false,
                Interleave.COMPONENT, false, false, 0, 1, 1, Compression.NONE);
    }

    /**
     * {@return the color model of the components}
     */
    public ColorModel colorModel() {
        return colorModel;
    }

    /**
     * {@return the chroma format of the frames: the subsampling of a Y'CbCr format, {@link ChromaFormat#YUV444} for
     * RGB and {@link ChromaFormat#MONOCHROME} for monochrome}
     */
    public ChromaFormat chromaFormat() {
        return chromaFormat;
    }

    /**
     * {@return the bits of every component, from 1 to 16}
     */
    public int bitDepth() {
        return bitDepth;
    }

    /**
     * {@return whether the frames carry an alpha component after the color components; {@code false} by default}
     */
    public boolean alpha() {
        return alpha;
    }

    /**
     * {@return the interleaving of the components}
     */
    public Interleave interleave() {
        return interleave;
    }

    /**
     * {@return whether every value occupies whole bytes, {@code ceil(bitDepth / 8)} of them, in its least
     * significant bits ({@code component_align_size}); {@code false} by default}
     */
    public boolean alignedComponents() {
        return alignedComponents;
    }

    /**
     * {@return whether values of two bytes are stored least significant byte first; {@code false} by default}
     */
    public boolean littleEndian() {
        return littleEndian;
    }

    /**
     * {@return the multiple of bytes every row is padded to, or 0 for rows without padding, the default}
     */
    public int rowAlignment() {
        return rowAlignment;
    }

    /**
     * {@return the number of tile columns, at least 1; 1 by default}
     */
    public int tileColumns() {
        return tileColumns;
    }

    /**
     * {@return the number of tile rows, at least 1; 1 by default}
     */
    public int tileRows() {
        return tileRows;
    }

    /**
     * {@return the generic compression of the frames; {@link Compression#NONE} by default}
     */
    public Compression compression() {
        return compression;
    }

    /**
     * Returns this format with or without an alpha component.
     *
     * @param alpha whether the frames carry alpha
     * @return the format
     * @throws IllegalArgumentException if the interleaving cannot store alpha ({@link Interleave#MULTI_Y})
     */
    public UncompressedVideoFormat withAlpha(boolean alpha) {
        return new UncompressedVideoFormat(colorModel, chromaFormat, bitDepth, alpha, interleave, alignedComponents,
                littleEndian, rowAlignment, tileColumns, tileRows, compression);
    }

    /**
     * Returns this format with another interleaving.
     *
     * @param interleave the interleaving
     * @return the format
     * @throws IllegalArgumentException if the interleaving cannot store the components, as each constant of
     * {@link Interleave} says
     */
    public UncompressedVideoFormat withInterleave(Interleave interleave) {
        return new UncompressedVideoFormat(colorModel, chromaFormat, bitDepth, alpha,
                Objects.requireNonNull(interleave, "interleave"), alignedComponents, littleEndian, rowAlignment,
                tileColumns, tileRows, compression);
    }

    /**
     * Returns this format with values in whole bytes or packed.
     *
     * @param aligned whether every value occupies whole bytes; see {@link #alignedComponents()}
     * @return the format
     * @throws IllegalArgumentException if the format is little-endian and packed values would not be whole bytes
     */
    public UncompressedVideoFormat withAlignedComponents(boolean aligned) {
        return new UncompressedVideoFormat(colorModel, chromaFormat, bitDepth, alpha, interleave, aligned,
                littleEndian, rowAlignment, tileColumns, tileRows, compression);
    }

    /**
     * Returns this format with another byte order.
     *
     * @param littleEndian whether values of two bytes are stored least significant byte first
     * @return the format
     * @throws IllegalArgumentException if values are packed and the depth is not 8 or 16 bits
     */
    public UncompressedVideoFormat withLittleEndian(boolean littleEndian) {
        return new UncompressedVideoFormat(colorModel, chromaFormat, bitDepth, alpha, interleave, alignedComponents,
                littleEndian, rowAlignment, tileColumns, tileRows, compression);
    }

    /**
     * Returns this format with another row alignment.
     *
     * @param bytes the multiple of bytes every row is padded to, from 0 (no padding) to 65536
     * @return the format
     * @throws IllegalArgumentException if the alignment is out of range
     */
    public UncompressedVideoFormat withRowAlignment(int bytes) {
        return new UncompressedVideoFormat(colorModel, chromaFormat, bitDepth, alpha, interleave, alignedComponents,
                littleEndian, bytes, tileColumns, tileRows, compression);
    }

    /**
     * Returns this format with the frame split into equal tiles, stored one after the other in raster order (with
     * {@link Interleave#TILE_COMPONENT}, the tiles of each component in turn).
     *
     * @param columns the tile columns, from 1 to 65536; the frame width must be a multiple of it, and with chroma
     * subsampling and several columns the tile width a multiple of 2
     * @param rows the tile rows, from 1 to 65536; the frame height must be a multiple of it, and with 4:2:0 and
     * several rows the tile height a multiple of 2
     * @return the format
     * @throws IllegalArgumentException if a count is out of range
     */
    public UncompressedVideoFormat withTiles(int columns, int rows) {
        return new UncompressedVideoFormat(colorModel, chromaFormat, bitDepth, alpha, interleave, alignedComponents,
                littleEndian, rowAlignment, columns, rows, compression);
    }

    /**
     * Returns this format with another generic compression.
     *
     * @param compression the compression of whole frames
     * @return the format
     */
    public UncompressedVideoFormat withCompression(Compression compression) {
        return new UncompressedVideoFormat(colorModel, chromaFormat, bitDepth, alpha, interleave, alignedComponents,
                littleEndian, rowAlignment, tileColumns, tileRows, Objects.requireNonNull(compression, "compression"));
    }

    /**
     * Returns the size of one frame before compression.
     *
     * @param width the frame width, positive
     * @param height the frame height, positive
     * @return the bytes of an uncompressed frame
     * @throws IllegalArgumentException if a dimension is not positive or the tiles do not divide the frame as
     * {@link #withTiles(int, int)} requires
     */
    public long frameSize(int width, int height) {
        return writer(width, height).frameSize();
    }

    /**
     * Returns the codec configuration of a {@link Codec#UNCOMPRESSED_23001_17} track of this format: the
     * {@code cmpd}, {@code uncC} and, when compressed, {@code cmpC} boxes, each with its header.
     *
     * @return the configuration, a new array
     */
    public byte[] toCodecConfig() {
        var out = new ByteArrayOutputStream();
        var definition = new ByteArrayOutputStream();
        var definitionTypes = definitionTypes();
        u32(definition, definitionTypes.length);
        for (var type : definitionTypes) {
            u16(definition, type);
        }
        box(out, "cmpd", definition.toByteArray());
        var config = new ByteArrayOutputStream();
        u32(config, 0);
        u32(config, 0);
        var indices = layoutIndices();
        u32(config, indices.length);
        for (var index : indices) {
            u16(config, index);
            config.write(bitDepth - 1);
            config.write(0);
            config.write(alignedComponents ? (bitDepth + 7) / 8 : 0);
        }
        config.write(samplingType());
        config.write(interleave.ordinal());
        config.write(0);
        config.write(littleEndian ? 0x80 : 0);
        u32(config, 0);
        u32(config, rowAlignment);
        u32(config, 0);
        u32(config, tileColumns - 1);
        u32(config, tileRows - 1);
        box(out, "uncC", config.toByteArray());
        if (compression != Compression.NONE) {
            var cmpC = new ByteArrayOutputStream();
            u32(cmpC, 0);
            cmpC.writeBytes((compression == Compression.DEFLATE ? "defl" : "zlib").getBytes(
                    java.nio.charset.StandardCharsets.US_ASCII));
            cmpC.write(0);
            box(out, "cmpC", cmpC.toByteArray());
        }
        return out.toByteArray();
    }

    /**
     * {@return the layout writer of a frame size}
     *
     * @param width the frame width
     * @param height the frame height
     * @throws IllegalArgumentException if a dimension is not positive or the tiles do not divide the frame
     */
    HeifUncompressedWriter writer(int width, int height) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("invalid frame size " + width + "x" + height);
        }
        var sx = chromaFormat == ChromaFormat.YUV420 || chromaFormat == ChromaFormat.YUV422 ? 2 : 1;
        var sy = chromaFormat == ChromaFormat.YUV420 ? 2 : 1;
        if (width % tileColumns != 0 || height % tileRows != 0
                || tileColumns > 1 && width / tileColumns % sx != 0 || tileRows > 1 && height / tileRows % sy != 0) {
            throw new IllegalArgumentException(tileColumns + "x" + tileRows + " tiles do not divide a " + width + "x"
                    + height + " " + chromaFormat + " frame");
        }
        var definitionTypes = definitionTypes();
        var indices = layoutIndices();
        var types = new int[indices.length];
        var depths = new int[indices.length];
        var aligns = new int[indices.length];
        for (var i = 0; i < indices.length; i++) {
            types[i] = definitionTypes[indices[i]];
            depths[i] = bitDepth;
            aligns[i] = alignedComponents ? (bitDepth + 7) / 8 : 0;
        }
        return new HeifUncompressedWriter(types, depths, aligns, samplingType(), interleave.ordinal(), littleEndian,
                rowAlignment, 0, tileColumns, tileRows, width, height);
    }

    /**
     * {@return the {@code cmpd} component types: monochrome 0, Y 1, Cb 2, Cr 3, red 4, green 5, blue 6, alpha 7}
     */
    int[] definitionTypes() {
        var color = switch (colorModel) {
            case MONOCHROME -> new int[]{0};
            case YCBCR -> new int[]{1, 2, 3};
            case RGB -> new int[]{4, 5, 6};
        };
        if (!alpha) {
            return color;
        }
        var all = java.util.Arrays.copyOf(color, color.length + 1);
        all[color.length] = 7;
        return all;
    }

    /**
     * {@return the {@code cmpd} index of each {@code uncC} component, in storage order}
     */
    private int[] layoutIndices() {
        if (interleave == Interleave.MULTI_Y) {
            return new int[]{0, 1, 0, 2};
        }
        var indices = new int[definitionTypes().length];
        for (var i = 0; i < indices.length; i++) {
            indices[i] = i;
        }
        return indices;
    }

    /**
     * {@return the {@code sampling_type}: 0 none, 1 4:2:2, 2 4:2:0}
     */
    private int samplingType() {
        return switch (chromaFormat) {
            case YUV422 -> 1;
            case YUV420 -> 2;
            default -> 0;
        };
    }

    /**
     * Writes a box.
     *
     * @param out the destination
     * @param type the four-character type
     * @param payload the box content after its header
     */
    private static void box(ByteArrayOutputStream out, String type, byte[] payload) {
        u32(out, 8L + payload.length);
        out.writeBytes(type.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        out.writeBytes(payload);
    }

    /**
     * Writes a big-endian 32-bit value.
     *
     * @param out the destination
     * @param value the unsigned value, below {@code 2^32}
     */
    private static void u32(ByteArrayOutputStream out, long value) {
        out.write((int) (value >>> 24));
        out.write((int) (value >>> 16));
        out.write((int) (value >>> 8));
        out.write((int) value);
    }

    /**
     * Writes a big-endian 16-bit value.
     *
     * @param out the destination
     * @param value the unsigned value, below {@code 2^16}
     */
    private static void u16(ByteArrayOutputStream out, int value) {
        out.write(value >>> 8);
        out.write(value);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof UncompressedVideoFormat format && colorModel == format.colorModel
                && chromaFormat == format.chromaFormat && bitDepth == format.bitDepth && alpha == format.alpha
                && interleave == format.interleave && alignedComponents == format.alignedComponents
                && littleEndian == format.littleEndian && rowAlignment == format.rowAlignment
                && tileColumns == format.tileColumns && tileRows == format.tileRows
                && compression == format.compression;
    }

    @Override
    public int hashCode() {
        return Objects.hash(colorModel, chromaFormat, bitDepth, alpha, interleave, alignedComponents, littleEndian,
                rowAlignment, tileColumns, tileRows, compression);
    }

    @Override
    public String toString() {
        return "UncompressedVideoFormat[" + colorModel + " " + chromaFormat + " " + bitDepth + "-bit"
                + (alpha ? " alpha" : "") + ", " + interleave + (alignedComponents ? ", aligned" : "")
                + (littleEndian ? ", little-endian" : "") + (rowAlignment != 0 ? ", rows of " + rowAlignment : "")
                + (tileColumns * tileRows > 1 ? ", " + tileColumns + "x" + tileRows + " tiles" : "")
                + (compression != Compression.NONE ? ", " + compression : "") + "]";
    }
}
