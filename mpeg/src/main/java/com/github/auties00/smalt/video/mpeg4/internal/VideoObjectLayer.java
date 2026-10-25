package com.github.auties00.smalt.video.mpeg4.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.ColorPrimaries;
import com.github.auties00.smalt.image.ColorRange;
import com.github.auties00.smalt.image.MatrixCoefficients;
import com.github.auties00.smalt.image.TransferCharacteristics;
import com.github.auties00.smalt.internal.limits.LimitChecks;

/**
 * Holds the configuration of one video object layer: the fields of the visual object sequence,
 * visual object and video object layer headers of ISO/IEC 14496-2 (6.2.2 and 6.2.3) that the
 * decoding of its planes depends on.
 *
 * <p>{@link #parse(BitReader, int, int, MediaLimits, VideoObjectLayer)} reads a
 * {@code VideoObjectLayer()} header. A layer that uses a tool the fast engine lacks (arbitrary
 * shape, static sprites, sprite brightness change, N-bit video, NEWPRED, reduced resolution VOPs
 * or scalability) is marked {@link #objectTools}: its header is read up to that tool and the
 * object decoder of {@code video.mpeg4.internal.object} decodes it. The Studio profiles and chroma
 * formats other than 4:2:0 are rejected with {@link MediaException.Unsupported}. A layer for H.263
 * pictures or the short video header is created with {@link #shortHeader(int, int)}.
 *
 * <p>Instances are mutable value holders owned by one decoder; they are not thread-safe.
 */
public final class VideoObjectLayer {
    /**
     * The {@code sprite_enable} value of a layer without sprites.
     */
    public static final int SPRITE_NONE = 0;

    /**
     * The {@code sprite_enable} value of a layer with static sprites.
     */
    public static final int SPRITE_STATIC = 1;

    /**
     * The {@code sprite_enable} value of a layer with global motion compensation.
     */
    public static final int SPRITE_GMC = 2;

    /**
     * The reserved {@code sprite_enable} value.
     */
    public static final int SPRITE_RESERVED = 3;

    /**
     * Whether pictures use the short video header (H.263 baseline) syntax.
     */
    public boolean shortVideoHeader;

    /**
     * Whether the layer needs the object decoder; the fields after the tool that needs it keep
     * their defaults.
     */
    public boolean objectTools;

    /**
     * The {@code video_object_type_indication}, {@code 0} when absent.
     */
    public int objectType;

    /**
     * The {@code profile_and_level_indication} of the visual object sequence, {@code 0} when
     * absent.
     */
    public int profileAndLevel;

    /**
     * The {@code video_object_layer_verid}: 1 or 2.
     */
    public int verid = 1;

    /**
     * The pixel aspect ratio width, {@code 0} when unknown.
     */
    public int parWidth;

    /**
     * The pixel aspect ratio height, {@code 0} when unknown.
     */
    public int parHeight;

    /**
     * Whether the layer declares that it contains no B-VOPs.
     */
    public boolean lowDelay;

    /**
     * The {@code vop_time_increment_resolution}: the number of ticks per second.
     */
    public int timeResolution;

    /**
     * The number of bits of {@code vop_time_increment}.
     */
    public int timeIncrementBits;

    /**
     * The {@code fixed_vop_time_increment}, {@code 0} when the VOP rate is not fixed.
     */
    public int fixedTimeIncrement;

    /**
     * The luminance width of the displayed pictures.
     */
    public int width;

    /**
     * The luminance height of the displayed pictures.
     */
    public int height;

    /**
     * Whether VOPs may be interlaced.
     */
    public boolean interlaced;

    /**
     * Whether overlapped block motion compensation is disabled.
     */
    public boolean obmcDisable = true;

    /**
     * The {@code sprite_enable} mode: {@link #SPRITE_NONE} or {@link #SPRITE_GMC}.
     */
    public int spriteEnable;

    /**
     * The {@code no_of_sprite_warping_points}, from 0 to 3.
     */
    public int warpingPoints;

    /**
     * The {@code sprite_warping_accuracy} code, from 0 (half sample) to 3 (sixteenth sample).
     */
    public int warpingAccuracy;

    /**
     * The number of bits of quantizer values.
     */
    public int quantPrecision = 5;

    /**
     * Whether the first (MPEG) inverse quantization method with weighting matrices is used.
     */
    public boolean mpegQuant;

    /**
     * The intra weighting matrix, in raster order.
     */
    public final int[] intraMatrix = Tables.DEFAULT_INTRA_MATRIX.clone();

    /**
     * The non-intra weighting matrix, in raster order.
     */
    public final int[] interMatrix = Tables.DEFAULT_INTER_MATRIX.clone();

    /**
     * Whether luminance motion vectors have quarter sample precision.
     */
    public boolean quarterSample;

    /**
     * Whether VOP headers carry complexity estimation data.
     */
    public boolean complexityEstimation;

    /**
     * The {@code estimation_method} of the complexity estimation header.
     */
    public int estimationMethod;

    /**
     * The enabled complexity estimation fields, one bit per field in syntax order of
     * {@code define_vop_complexity_estimation_header()}: opaque, transparent, intra_cae,
     * inter_cae, no_update, upsampling, intra_blocks, inter_blocks, inter4v_blocks,
     * not_coded_blocks, dct_coefs, dct_lines, vlc_symbols, vlc_bits, apm, npm, interpolate_mc_q,
     * forw_back_mc_q, halfpel2, halfpel4, sadct, quarterpel.
     */
    public int estimationFields;

    /**
     * Whether macroblock data is partitioned.
     */
    public boolean dataPartitioned;

    /**
     * Whether coefficients use the reversible variable length codes.
     */
    public boolean reversibleVlc;

    /**
     * Whether the {@code video_signal_type()} of the visual object signals the full range.
     */
    public boolean fullRange;

    /**
     * The {@code colour_primaries}, {@code -1} when not signaled.
     */
    public int colorPrimaries = -1;

    /**
     * The {@code transfer_characteristics}, {@code -1} when not signaled.
     */
    public int transferCharacteristics = -1;

    /**
     * The {@code matrix_coefficients}, {@code -1} when not signaled.
     */
    public int matrixCoefficients = -1;

    /**
     * Creates a layer with the defaults of an absent header.
     */
    public VideoObjectLayer() {
    }

    /**
     * Creates the layer of the short video header or of an H.263 picture, which has the fixed
     * settings of Table 6-24.
     *
     * @param width the picture width
     * @param height the picture height
     * @return the layer
     */
    public static VideoObjectLayer shortHeader(int width, int height) {
        var layer = new VideoObjectLayer();
        layer.shortVideoHeader = true;
        layer.width = width;
        layer.height = height;
        layer.lowDelay = true;
        layer.timeResolution = 30000;
        layer.timeIncrementBits = 15;
        layer.fixedTimeIncrement = 1001;
        layer.parWidth = 12;
        layer.parHeight = 11;
        return layer;
    }

    /**
     * Returns the width of the coded pictures in macroblocks.
     *
     * @return the macroblock columns
     */
    public int mbWidth() {
        return (width + 15) >> 4;
    }

    /**
     * Returns the height of the coded pictures in macroblocks.
     *
     * @return the macroblock rows
     */
    public int mbHeight() {
        return (height + 15) >> 4;
    }

    /**
     * Returns the color description: the signaled values, with the defaults of the picture size
     * (BT.709 for pictures wider than 1024 or taller than 576 samples, BT.601 otherwise) for
     * components that are not signaled or unspecified.
     *
     * @return the color description
     */
    public ColorInfo color() {
        var hd = width > 1024 || height > 576;
        var fallback = hd ? ColorInfo.BT709_LIMITED : ColorInfo.BT601_LIMITED;
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
        return new ColorInfo(matrix, fullRange ? ColorRange.FULL : ColorRange.LIMITED, primaries, transfer);
    }

    /**
     * Parses a {@code VideoObjectLayer()} header from the bit after its start code.
     *
     * @param reader the reader, positioned after {@code video_object_layer_start_code}
     * @param profileAndLevel the {@code profile_and_level_indication} of the sequence, or 0
     * @param visualVerid the {@code visual_object_verid} of the visual object, or 1
     * @param limits the limits bounding the picture size
     * @param signal the layer holding the color description of the visual object's
     * {@code video_signal_type()}, or {@code null}
     * @return the layer
     * @throws MediaException.Malformed if the header is corrupt
     * @throws MediaException.Unsupported if the layer uses a coding tool this decoder lacks
     * @throws MediaException.LimitExceeded if the picture size exceeds the limits
     */
    public static VideoObjectLayer parse(BitReader reader, int profileAndLevel, int visualVerid, MediaLimits limits,
                                         VideoObjectLayer signal) {
        var layer = new VideoObjectLayer();
        layer.profileAndLevel = profileAndLevel;
        if (profileAndLevel >= 0xE1 && profileAndLevel <= 0xE8) {
            // TODO: decode the Studio profiles of ISO/IEC 14496-2 Amd 1 (6.2.3 of the 2004 edition);
            //  neither reference decoder parses the Studio conformance streams of ISO/IEC 14496-4.
            throw new MediaException.Unsupported("mpeg-4 studio profile", "profile_and_level_indication 0x"
                    + Integer.toHexString(profileAndLevel) + " is a Studio profile");
        }
        layer.verid = visualVerid;
        if (signal != null) {
            layer.fullRange = signal.fullRange;
            layer.colorPrimaries = signal.colorPrimaries;
            layer.transferCharacteristics = signal.transferCharacteristics;
            layer.matrixCoefficients = signal.matrixCoefficients;
        }
        reader.skip(1);
        layer.objectType = reader.read(8);
        if (layer.objectType == 0x0F || layer.objectType == 0x10) {
            // TODO: decode the Simple Studio and Core Studio object types.
            throw new MediaException.Unsupported("mpeg-4 studio profile", "video_object_type_indication "
                    + layer.objectType + " is a Studio object type");
        }
        if (reader.readFlag()) {
            layer.verid = reader.read(4);
            reader.skip(3);
        }
        if (layer.verid != 1 && layer.verid != 2) {
            layer.verid = layer.verid > 2 ? 2 : 1;
        }
        var aspect = reader.read(4);
        switch (aspect) {
            case 1 -> {
                layer.parWidth = 1;
                layer.parHeight = 1;
            }
            case 2 -> {
                layer.parWidth = 12;
                layer.parHeight = 11;
            }
            case 3 -> {
                layer.parWidth = 10;
                layer.parHeight = 11;
            }
            case 4 -> {
                layer.parWidth = 16;
                layer.parHeight = 11;
            }
            case 5 -> {
                layer.parWidth = 40;
                layer.parHeight = 33;
            }
            case 15 -> {
                layer.parWidth = reader.read(8);
                layer.parHeight = reader.read(8);
            }
            default -> {
            }
        }
        layer.lowDelay = layer.objectType == 1;
        if (reader.readFlag()) {
            var chroma = reader.read(2);
            if (chroma != 1) {
                throw new MediaException.Unsupported("mpeg-4 chroma format", "chroma_format " + chroma
                        + " is not 4:2:0");
            }
            layer.lowDelay = reader.readFlag();
            if (reader.readFlag()) {
                reader.skip(15);
                reader.marker("first_half_bit_rate");
                reader.skip(15);
                reader.marker("latter_half_bit_rate");
                reader.skip(15);
                reader.marker("first_half_vbv_buffer_size");
                reader.skip(3 + 11);
                reader.marker("first_half_vbv_occupancy");
                reader.skip(15);
                reader.marker("latter_half_vbv_occupancy");
            }
        }
        var shape = reader.read(2);
        if (shape != 0) {
            return objectLayer(layer, reader, limits);
        }
        reader.marker("video_object_layer_shape");
        layer.timeResolution = reader.read(16);
        if (layer.timeResolution == 0) {
            throw new MediaException.Malformed("vop_time_increment_resolution is zero");
        }
        reader.marker("vop_time_increment_resolution");
        layer.timeIncrementBits = Math.max(1, 32 - Integer.numberOfLeadingZeros(layer.timeResolution - 1));
        if (reader.readFlag()) {
            layer.fixedTimeIncrement = reader.read(layer.timeIncrementBits);
        }
        reader.marker("fixed_vop_rate");
        layer.width = reader.read(13);
        reader.marker("video_object_layer_width");
        layer.height = reader.read(13);
        reader.marker("video_object_layer_height");
        if (layer.width == 0 || layer.height == 0) {
            throw new MediaException.Malformed("video object layer of size " + layer.width + "x" + layer.height);
        }
        LimitChecks.checkPicture(limits, layer.width, layer.height);
        layer.interlaced = reader.readFlag();
        layer.obmcDisable = reader.readFlag();
        layer.spriteEnable = layer.verid == 1 ? reader.read(1) : reader.read(2);
        if (layer.spriteEnable == SPRITE_STATIC) {
            return objectLayer(layer, reader, limits);
        }
        if (layer.spriteEnable == SPRITE_RESERVED) {
            throw new MediaException.Malformed("reserved sprite_enable value");
        }
        if (layer.spriteEnable == SPRITE_GMC) {
            layer.warpingPoints = reader.read(6);
            if (layer.warpingPoints > 3) {
                throw new MediaException.Malformed("no_of_sprite_warping_points " + layer.warpingPoints
                        + " is not allowed with GMC");
            }
            layer.warpingAccuracy = reader.read(2);
            if (reader.readFlag()) {
                return objectLayer(layer, reader, limits);
            }
        }
        if (reader.readFlag()) {
            return objectLayer(layer, reader, limits);
        }
        layer.mpegQuant = reader.readFlag();
        if (layer.mpegQuant) {
            if (reader.readFlag()) {
                readMatrix(reader, layer.intraMatrix);
            }
            if (reader.readFlag()) {
                readMatrix(reader, layer.interMatrix);
            }
        }
        if (layer.verid != 1) {
            layer.quarterSample = reader.readFlag();
        }
        layer.complexityEstimation = !reader.readFlag();
        if (layer.complexityEstimation) {
            readComplexityDefinition(reader, layer);
        }
        reader.readFlag();
        layer.dataPartitioned = reader.readFlag();
        if (layer.dataPartitioned) {
            layer.reversibleVlc = reader.readFlag();
        }
        if (layer.verid != 1) {
            if (reader.readFlag()) {
                return objectLayer(layer, reader, limits);
            }
            if (reader.readFlag()) {
                return objectLayer(layer, reader, limits);
            }
        }
        if (reader.readFlag()) {
            return objectLayer(layer, reader, limits);
        }
        if (reader.overrun()) {
            throw new MediaException.Malformed("truncated video object layer header");
        }
        return layer;
    }

    /**
     * Marks a layer for the object decoder.
     *
     * @param layer the layer read so far
     * @param reader the stream, positioned after the fields read so far
     * @param limits the limits bounding the picture size
     * @return the layer
     * @throws MediaException.Malformed if the header read so far is truncated
     */
    private static VideoObjectLayer objectLayer(VideoObjectLayer layer, BitReader reader, MediaLimits limits) {
        if (reader.overrun()) {
            throw new MediaException.Malformed("truncated video object layer header");
        }
        layer.objectTools = true;
        return layer;
    }

    /**
     * Reads a downloaded quantization matrix: up to 64 values in zigzag order, a zero ending the
     * list and repeating the last value.
     *
     * @param reader the reader, positioned at the syntax structure
     * @param matrix receives the matrix in raster order
     * @throws MediaException.Malformed if a value is zero where one is required
     */
    private static void readMatrix(BitReader reader, int[] matrix) {
        var last = 0;
        var i = 0;
        for (; i < 64; i++) {
            var value = reader.read(8);
            if (value == 0) {
                break;
            }
            last = value;
            matrix[Tables.ZIGZAG[i]] = value;
        }
        if (i == 0) {
            throw new MediaException.Malformed("empty quantization matrix");
        }
        for (; i < 64; i++) {
            matrix[Tables.ZIGZAG[i]] = last;
        }
    }

    /**
     * Reads {@code define_vop_complexity_estimation_header()} (6.2.3).
     *
     * @param reader the reader, positioned at the syntax structure
     * @param layer receives the estimation method and the enabled fields
     * @throws MediaException.Unsupported if the estimation method is reserved
     */
    private static void readComplexityDefinition(BitReader reader, VideoObjectLayer layer) {
        layer.estimationMethod = reader.read(2);
        if (layer.estimationMethod > 1) {
            throw new MediaException.Unsupported("mpeg-4 complexity estimation", "estimation_method "
                    + layer.estimationMethod + " is reserved");
        }
        var fields = 0;
        if (!reader.readFlag()) {
            fields |= reader.read(6);
        }
        fields <<= 4;
        if (!reader.readFlag()) {
            fields |= reader.read(4);
        }
        reader.marker("texture_complexity_estimation_set_1");
        fields <<= 4;
        if (!reader.readFlag()) {
            fields |= reader.read(4);
        }
        fields <<= 6;
        if (!reader.readFlag()) {
            fields |= reader.read(6);
        }
        reader.marker("motion_compensation_complexity");
        fields <<= 2;
        if (layer.estimationMethod == 1 && !reader.readFlag()) {
            fields |= reader.read(2);
        }
        layer.estimationFields = fields;
    }

    /**
     * Skips {@code read_vop_complexity_estimation_header()} (6.2.5.1), which carries nothing for
     * S(GMC)-VOPs.
     *
     * @param reader the reader, positioned at the syntax structure
     * @param codingType the {@code vop_coding_type}
     */
    public void skipComplexityEstimation(BitReader reader, int codingType) {
        var f = estimationFields;
        var opaque = (f >> 21) & 1;
        var transparent = (f >> 20) & 1;
        var intraCae = (f >> 19) & 1;
        var interCae = (f >> 18) & 1;
        var noUpdate = (f >> 17) & 1;
        var upsampling = (f >> 16) & 1;
        var intraBlocks = (f >> 15) & 1;
        var interBlocks = (f >> 14) & 1;
        var inter4v = (f >> 13) & 1;
        var notCoded = (f >> 12) & 1;
        var dctCoefs = (f >> 11) & 1;
        var dctLines = (f >> 10) & 1;
        var vlcSymbols = (f >> 9) & 1;
        var vlcBits = (f >> 8) & 1;
        var apm = (f >> 7) & 1;
        var npm = (f >> 6) & 1;
        var interpolate = (f >> 5) & 1;
        var forwBack = (f >> 4) & 1;
        var halfpel2 = (f >> 3) & 1;
        var halfpel4 = (f >> 2) & 1;
        var sadct = (f >> 1) & 1;
        var quarterpel = f & 1;
        var shape = 8 * (opaque + transparent + intraCae + interCae + noUpdate + upsampling);
        var texture = 8 * (intraBlocks + notCoded + dctCoefs + dctLines + vlcSymbols) + 4 * vlcBits;
        var motion = 8 * (interBlocks + inter4v + apm + npm + forwBack + halfpel2 + halfpel4);
        var bits = switch (codingType) {
            case Vop.I -> shape + texture + 8 * sadct;
            case Vop.P -> shape + texture + motion + 8 * (sadct + quarterpel);
            case Vop.B -> shape + texture + motion + 8 * (interpolate + sadct + quarterpel);
            default -> 0;
        };
        reader.skip(bits);
    }
}
