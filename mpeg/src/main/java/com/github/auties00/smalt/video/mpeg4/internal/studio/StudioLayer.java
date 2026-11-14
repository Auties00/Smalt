package com.github.auties00.smalt.video.mpeg4.internal.studio;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.video.mpeg4.internal.BitReader;

/**
 * Holds the fields of a Studio profile video object layer header ({@code StudioVideoObjectLayer()}) and of the
 * extension data that follows it.
 *
 * <p>The layer is rectangular: arbitrarily shaped layers (binary, grayscale and binary only shape) and sprites raise
 * {@link MediaException.Unsupported}. Instances are mutable value holders owned by one decoder.
 */
public final class StudioLayer {
    /**
     * The {@code video_object_type_indication} of the Simple Studio object type.
     */
    public static final int SIMPLE_STUDIO = 0x0F;

    /**
     * The {@code video_object_type_indication} of the Core Studio object type.
     */
    public static final int CORE_STUDIO = 0x10;

    /**
     * The extension start code ({@code 0x000001B8}) value after the prefix.
     */
    static final int EXTENSION_START = 0xB8;

    /**
     * The user data start code value after the prefix.
     */
    static final int USER_DATA_START = 0xB2;

    /**
     * The default intra quantiser matrix in raster order (the MPEG-4 default intra matrix).
     */
    static final int[] DEFAULT_INTRA = {
            8, 17, 18, 19, 21, 23, 25, 27,
            17, 18, 19, 21, 23, 25, 27, 28,
            20, 21, 22, 23, 24, 26, 28, 30,
            21, 22, 23, 24, 26, 28, 30, 32,
            22, 23, 24, 26, 28, 30, 32, 35,
            23, 24, 26, 28, 30, 32, 35, 38,
            25, 26, 28, 30, 32, 35, 38, 41,
            27, 28, 30, 32, 35, 38, 41, 45};

    /**
     * The zigzag scan: the raster index of each scan position.
     */
    static final int[] ZIGZAG = {
            0, 1, 8, 16, 9, 2, 3, 10, 17, 24, 32, 25, 18, 11, 4, 5,
            12, 19, 26, 33, 40, 48, 41, 34, 27, 20, 13, 6, 7, 14, 21, 28,
            35, 42, 49, 56, 57, 50, 43, 36, 29, 22, 15, 23, 30, 37, 44, 51,
            58, 59, 52, 45, 38, 31, 39, 46, 53, 60, 61, 54, 47, 55, 62, 63};

    /**
     * The {@code video_object_type_indication}.
     */
    public int objectType;

    /**
     * Whether the layer codes progressive frames only ({@code progressive_sequence}).
     */
    public boolean progressive;

    /**
     * Whether the three components are R, G and B rather than Y, Cb and Cr ({@code rgb_components}).
     */
    public boolean rgb;

    /**
     * The chroma format: {@link ChromaFormat#YUV420}, {@link ChromaFormat#YUV422} or {@link ChromaFormat#YUV444}.
     */
    public ChromaFormat chroma;

    /**
     * The sample precision in bits: 8, 10 or 12.
     */
    public int bitsPerPixel;

    /**
     * The width in luminance samples.
     */
    public int width;

    /**
     * The height in luminance samples.
     */
    public int height;

    /**
     * The {@code aspect_ratio_info}.
     */
    public int aspectRatio;

    /**
     * The pixel aspect width of an extended pixel aspect ratio, or 0.
     */
    public int parWidth;

    /**
     * The pixel aspect height of an extended pixel aspect ratio, or 0.
     */
    public int parHeight;

    /**
     * The {@code frame_rate_code} (the frame rates of ISO/IEC 13818-2 Table 6-4).
     */
    public int frameRateCode;

    /**
     * Whether the layer holds no B-VOPs ({@code low_delay}).
     */
    public boolean lowDelay;

    /**
     * Whether the layer was converted from an ISO/IEC 13818-2 stream ({@code mpeg2_stream}), which narrows the
     * range of the dequantised coefficients.
     */
    public boolean mpeg2Stream;

    /**
     * The {@code colour_primaries} of a sequence display extension, or -1.
     */
    public int colorPrimaries = -1;

    /**
     * The {@code transfer_characteristics} of a sequence display extension, or -1.
     */
    public int transferCharacteristics = -1;

    /**
     * The {@code matrix_coefficients} of a sequence display extension, or -1.
     */
    public int matrixCoefficients = -1;

    /**
     * The luminance intra quantiser matrix in raster order.
     */
    final int[] intraMatrix = DEFAULT_INTRA.clone();

    /**
     * The chrominance intra quantiser matrix in raster order.
     */
    final int[] chromaIntraMatrix = DEFAULT_INTRA.clone();

    /**
     * Returns whether a video object layer header starts a Studio profile layer: its
     * {@code video_object_type_indication} is Simple Studio or Core Studio, or the visual object sequence
     * declares a Studio profile.
     *
     * @param data the bytes
     * @param offset the index after the video object layer start code
     * @param length the bytes available
     * @param profileAndLevel the {@code profile_and_level_indication} of the sequence, or 0
     * @return {@code true} for a Studio layer
     */
    public static boolean isStudio(byte[] data, int offset, int length, int profileAndLevel) {
        if (profileAndLevel >= 0xE1 && profileAndLevel <= 0xE8) {
            return true;
        }
        if (length < 2) {
            return false;
        }
        var type = ((data[offset] & 0x7F) << 1) | ((data[offset + 1] & 0xFF) >>> 7);
        return type == SIMPLE_STUDIO || type == CORE_STUDIO;
    }

    /**
     * Parses a {@code StudioVideoObjectLayer()} header.
     *
     * @param reader the reader, positioned after the video object layer start code
     * @param limits the limits bounding the picture size
     * @return the layer
     * @throws MediaException.Malformed if the header is corrupt
     * @throws MediaException.Unsupported if the layer is arbitrarily shaped or uses sprites
     * @throws MediaException.LimitExceeded if the picture size exceeds the limits
     */
    public static StudioLayer parse(BitReader reader, MediaLimits limits) {
        var layer = new StudioLayer();
        reader.skip(1);
        layer.objectType = reader.read(8);
        reader.skip(4);
        var shape = reader.read(2);
        reader.skip(4);
        if (shape != 0) {
            throw new MediaException.Unsupported("mpeg-4 studio arbitrary shape",
                    "video_object_layer_shape " + shape + " of a Studio layer");
        }
        layer.progressive = reader.readFlag();
        layer.rgb = reader.readFlag();
        layer.chroma = switch (reader.read(2)) {
            case 1 -> ChromaFormat.YUV420;
            case 2 -> ChromaFormat.YUV422;
            case 3 -> ChromaFormat.YUV444;
            default -> throw new MediaException.Malformed("reserved chroma_format in a Studio layer");
        };
        layer.bitsPerPixel = reader.read(4);
        if (layer.bitsPerPixel != 8 && layer.bitsPerPixel != 10 && layer.bitsPerPixel != 12) {
            throw new MediaException.Malformed("invalid bits_per_pixel " + layer.bitsPerPixel + " in a Studio layer");
        }
        reader.skip(1);
        layer.width = reader.read(14);
        reader.skip(1);
        layer.height = reader.read(14);
        reader.skip(1);
        if (layer.width == 0 || layer.height == 0) {
            throw new MediaException.Malformed("zero Studio layer size");
        }
        layer.aspectRatio = reader.read(4);
        if (layer.aspectRatio == 15) {
            layer.parWidth = reader.read(8);
            layer.parHeight = reader.read(8);
        }
        layer.frameRateCode = reader.read(4);
        reader.skip(15 + 1 + 15 + 1 + 15 + 1 + 3 + 11 + 1 + 15 + 1);
        layer.lowDelay = reader.readFlag();
        layer.mpeg2Stream = reader.readFlag();
        if (reader.readFlag()) {
            throw new MediaException.Unsupported("mpeg-4 studio sprites", "a Studio layer with sprites");
        }
        if (reader.overrun()) {
            throw new MediaException.Malformed("truncated Studio video object layer header");
        }
        if (layer.rgb && layer.chroma != ChromaFormat.YUV444) {
            throw new MediaException.Malformed("rgb_components with a subsampled chroma_format");
        }
        LimitChecks.checkPicture(limits, layer.width, layer.height);
        return layer;
    }

    /**
     * Reads the extension data that follows the layer header or a VOP header: sequence display extensions (layer
     * only) and quant matrix extensions; the other extensions and user data are skipped.
     *
     * @param data the bytes
     * @param offset the index after the extension start code
     * @param length the bytes up to the next start code
     * @param reader a reader to use
     * @param layerLevel whether the data follows the layer header
     * @throws MediaException.Unsupported for a VLC code extension, which replaces the variable length codes
     */
    void readExtension(byte[] data, int offset, int length, BitReader reader, boolean layerLevel) {
        reader.load(data, offset, length);
        var id = reader.read(4);
        switch (id) {
            case 2 -> {
                if (layerLevel) {
                    reader.skip(4);
                    if (reader.readFlag()) {
                        colorPrimaries = reader.read(8);
                        transferCharacteristics = reader.read(8);
                        matrixCoefficients = reader.read(8);
                    }
                }
            }
            case 3 -> {
                if (reader.readFlag()) {
                    readMatrix(reader, intraMatrix);
                    System.arraycopy(intraMatrix, 0, chromaIntraMatrix, 0, 64);
                }
                if (reader.readFlag()) {
                    reader.skip(64 * 8);
                }
                if (reader.readFlag()) {
                    readMatrix(reader, chromaIntraMatrix);
                }
                if (reader.overrun()) {
                    throw new MediaException.Malformed("truncated Studio quant matrix extension");
                }
            }
            // TODO: decode the VLC code extension, which downloads replacement VLCs; no stream, decoder or final
            //  syntax of it is available.
            case 5 -> throw new MediaException.Unsupported("mpeg-4 studio vlc code extension",
                    "a Studio VLC code extension");
            default -> {
            }
        }
    }

    /**
     * Reads a quantiser matrix in zigzag order.
     *
     * @param reader the reader, positioned at the first entry
     * @param matrix the matrix to fill in raster order
     */
    private static void readMatrix(BitReader reader, int[] matrix) {
        for (var i = 0; i < 64; i++) {
            var value = reader.read(8);
            if (value == 0) {
                throw new MediaException.Malformed("zero quantiser matrix entry in a Studio layer");
            }
            matrix[ZIGZAG[i]] = value;
        }
    }

    /**
     * {@return the number of macroblock columns}
     */
    int mbWidth() {
        return (width + 15) >> 4;
    }
}
