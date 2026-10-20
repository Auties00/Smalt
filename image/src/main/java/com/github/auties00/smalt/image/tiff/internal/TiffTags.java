package com.github.auties00.smalt.image.tiff.internal;

/**
 * Names the TIFF tags this package reads and writes.
 *
 * <p>Tag numbers come from TIFF 6.0 (sections 8 and 18-23), TIFF Technical Note 2 (new-style
 * JPEG), the Adobe Photoshop TIFF technical notes (ICC profile, Deflate) and EXIF 2.32
 * (sub-IFD pointers). The field type codes are those of
 * {@link com.github.auties00.smalt.internal.tiff.TiffTypes}.
 */
public final class TiffTags {
    /** NewSubfileType. */
    public static final int NEW_SUBFILE_TYPE = 254;
    /** ImageWidth. */
    public static final int IMAGE_WIDTH = 256;
    /** ImageLength. */
    public static final int IMAGE_LENGTH = 257;
    /** BitsPerSample. */
    public static final int BITS_PER_SAMPLE = 258;
    /** Compression. */
    public static final int COMPRESSION = 259;
    /** PhotometricInterpretation. */
    public static final int PHOTOMETRIC = 262;
    /** FillOrder. */
    public static final int FILL_ORDER = 266;
    /** ImageDescription. */
    public static final int IMAGE_DESCRIPTION = 270;
    /** Make. */
    public static final int MAKE = 271;
    /** Model. */
    public static final int MODEL = 272;
    /** StripOffsets. */
    public static final int STRIP_OFFSETS = 273;
    /** Orientation. */
    public static final int ORIENTATION = 274;
    /** SamplesPerPixel. */
    public static final int SAMPLES_PER_PIXEL = 277;
    /** RowsPerStrip. */
    public static final int ROWS_PER_STRIP = 278;
    /** StripByteCounts. */
    public static final int STRIP_BYTE_COUNTS = 279;
    /** XResolution. */
    public static final int X_RESOLUTION = 282;
    /** YResolution. */
    public static final int Y_RESOLUTION = 283;
    /** PlanarConfiguration. */
    public static final int PLANAR_CONFIGURATION = 284;
    /** PageName. */
    public static final int PAGE_NAME = 285;
    /** T4Options. */
    public static final int T4_OPTIONS = 292;
    /** T6Options. */
    public static final int T6_OPTIONS = 293;
    /** ResolutionUnit. */
    public static final int RESOLUTION_UNIT = 296;
    /** Software. */
    public static final int SOFTWARE = 305;
    /** DateTime. */
    public static final int DATE_TIME = 306;
    /** Artist. */
    public static final int ARTIST = 315;
    /** HostComputer. */
    public static final int HOST_COMPUTER = 316;
    /** Predictor. */
    public static final int PREDICTOR = 317;
    /** WhitePoint. */
    public static final int WHITE_POINT = 318;
    /** ColorMap. */
    public static final int COLOR_MAP = 320;
    /** TileWidth. */
    public static final int TILE_WIDTH = 322;
    /** TileLength. */
    public static final int TILE_LENGTH = 323;
    /** TileOffsets. */
    public static final int TILE_OFFSETS = 324;
    /** TileByteCounts. */
    public static final int TILE_BYTE_COUNTS = 325;
    /** SubIFDs. */
    public static final int SUB_IFDS = 330;
    /** InkSet. */
    public static final int INK_SET = 332;
    /** InkNames. */
    public static final int INK_NAMES = 333;
    /** NumberOfInks. */
    public static final int NUMBER_OF_INKS = 334;
    /** ExtraSamples. */
    public static final int EXTRA_SAMPLES = 338;
    /** SampleFormat. */
    public static final int SAMPLE_FORMAT = 339;
    /** SMinSampleValue. */
    public static final int S_MIN_SAMPLE_VALUE = 340;
    /** SMaxSampleValue. */
    public static final int S_MAX_SAMPLE_VALUE = 341;
    /** JPEGTables. */
    public static final int JPEG_TABLES = 347;
    /** Decode (TIFF-FX). */
    public static final int DECODE = 433;
    /** JPEGInterchangeFormat (old-style JPEG). */
    public static final int JPEG_INTERCHANGE_FORMAT = 513;
    /** JPEGInterchangeFormatLength (old-style JPEG). */
    public static final int JPEG_INTERCHANGE_FORMAT_LENGTH = 514;
    /** JPEGRestartInterval (old-style JPEG). */
    public static final int JPEG_RESTART_INTERVAL = 515;
    /** JPEGQTables (old-style JPEG). */
    public static final int JPEG_Q_TABLES = 519;
    /** JPEGDCTables (old-style JPEG). */
    public static final int JPEG_DC_TABLES = 520;
    /** JPEGACTables (old-style JPEG). */
    public static final int JPEG_AC_TABLES = 521;
    /** YCbCrCoefficients. */
    public static final int Y_CB_CR_COEFFICIENTS = 529;
    /** YCbCrSubSampling. */
    public static final int Y_CB_CR_SUBSAMPLING = 530;
    /** ReferenceBlackWhite. */
    public static final int REFERENCE_BLACK_WHITE = 532;
    /** XMP packet. */
    public static final int XMP = 700;
    /** Copyright. */
    public static final int COPYRIGHT = 33432;
    /** EXIF IFD pointer. */
    public static final int EXIF_IFD = 34665;
    /** ICC profile. */
    public static final int ICC_PROFILE = 34675;
    /** GPS IFD pointer. */
    public static final int GPS_IFD = 34853;
    /** Interoperability IFD pointer, inside the EXIF IFD. */
    public static final int INTEROPERABILITY_IFD = 40965;

    /**
     * Prevents instantiation.
     */
    private TiffTags() {
        throw new AssertionError();
    }
}
