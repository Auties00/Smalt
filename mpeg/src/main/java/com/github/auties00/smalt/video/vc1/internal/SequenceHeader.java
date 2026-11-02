package com.github.auties00.smalt.video.vc1.internal;

import com.github.auties00.smalt.MediaException;

/**
 * Holds the sequence level parameters of a VC-1 stream: the advanced profile sequence header of SMPTE ST 421 section
 * 6.1, or the simple and main profile initialization metadata of Annex J (STRUCT_C).
 *
 * <p>Instances are mutable and owned by one decoder.
 */
final class SequenceHeader {
    /**
     * The simple profile.
     */
    static final int SIMPLE = 0;

    /**
     * The main profile.
     */
    static final int MAIN = 1;

    /**
     * The advanced profile.
     */
    static final int ADVANCED = 3;

    /**
     * PROFILE: {@link #SIMPLE}, {@link #MAIN} or {@link #ADVANCED}.
     */
    int profile;

    /**
     * LEVEL of the advanced profile.
     */
    int level;

    /**
     * FRMRTQ_POSTPROC, or FRMRTQ_POSTPROC of STRUCT_C.
     */
    int frameRateQuantized;

    /**
     * BITRTQ_POSTPROC, or BITRTQ_POSTPROC of STRUCT_C.
     */
    int bitRateQuantized;

    /**
     * POSTPROCFLAG: whether picture headers carry POSTPROC.
     */
    boolean postprocFlag;

    /**
     * The maximum coded width, from MAX_CODED_WIDTH.
     */
    int maxCodedWidth;

    /**
     * The maximum coded height, from MAX_CODED_HEIGHT.
     */
    int maxCodedHeight;

    /**
     * PULLDOWN: whether picture headers carry the repeat fields.
     */
    boolean pulldown;

    /**
     * INTERLACE: whether pictures may be interlace coded.
     */
    boolean interlace;

    /**
     * TFCNTRFLAG: whether picture headers carry TFCNTR.
     */
    boolean frameCounterFlag;

    /**
     * FINTERPFLAG: whether picture headers carry INTERPFRM.
     */
    boolean frameInterpolationFlag;

    /**
     * PSF: whether the frames are progressive segmented frames.
     */
    boolean psf;

    /**
     * DISPLAY_EXT: whether the display extension follows.
     */
    boolean displayExtension;

    /**
     * The display width, from DISP_HORIZ_SIZE, or 0.
     */
    int displayWidth;

    /**
     * The display height, from DISP_VERT_SIZE, or 0.
     */
    int displayHeight;

    /**
     * The numerator of the sample aspect ratio, or 0 when unknown.
     */
    int aspectNumerator;

    /**
     * The denominator of the sample aspect ratio, or 0 when unknown.
     */
    int aspectDenominator;

    /**
     * The numerator of the frame rate of the display extension, or 0 when absent.
     */
    int frameRateNumerator;

    /**
     * The denominator of the frame rate of the display extension, or 0 when absent.
     */
    int frameRateDenominator;

    /**
     * COLOR_PRIM, BT.709 by default.
     */
    int colorPrimaries = 1;

    /**
     * TRANSFER_CHAR, BT.709 by default.
     */
    int transferCharacteristics = 1;

    /**
     * MATRIX_COEF, BT.601 by default.
     */
    int matrixCoefficients = 6;

    /**
     * COLOR_FORMAT_FLAG: whether the color description was coded.
     */
    boolean colorFormatPresent;

    /**
     * HRD_NUM_LEAKY_BUCKETS, or 0 without HRD parameters.
     */
    int leakyBuckets;

    /**
     * LOOPFILTER of STRUCT_C.
     */
    boolean loopFilter;

    /**
     * MULTIRES of STRUCT_C: whether frames may be coded at half width or height.
     */
    boolean multiResolution;

    /**
     * FASTUVMC of STRUCT_C.
     */
    boolean fastUvMc;

    /**
     * EXTENDED_MV of STRUCT_C.
     */
    boolean extendedMv;

    /**
     * DQUANT of STRUCT_C.
     */
    int dquant;

    /**
     * VSTRANSFORM of STRUCT_C.
     */
    boolean variableTransform;

    /**
     * OVERLAP of STRUCT_C.
     */
    boolean overlap;

    /**
     * SYNCMARKER of STRUCT_C: whether macroblock rows may be followed by sync markers.
     */
    boolean syncMarker;

    /**
     * RANGERED of STRUCT_C: whether frames may be range reduced.
     */
    boolean rangeReduction;

    /**
     * MAXBFRAMES of STRUCT_C: the number of consecutive B frames, 0 for none.
     */
    int maxBFrames;

    /**
     * QUANTIZER of STRUCT_C.
     */
    int quantizer;

    /**
     * {@return whether the stream is in the advanced profile}
     */
    boolean advanced() {
        return profile == ADVANCED;
    }

    /**
     * Parses the 32-bit STRUCT_C of the simple and main profiles (Table 263).
     *
     * @param bits the reader, at the structure
     * @throws MediaException.Unsupported if the profile is 2, the Windows Media Video 9 complex profile that SMPTE ST
     * 421 reserves
     * @throws MediaException.Malformed if a field takes a reserved value
     */
    void parseStructC(Bits bits) {
        var code = bits.read(4);
        profile = code >>> 2;
        if (profile == 2) {
            throw new MediaException.Unsupported("vc-1 complex profile",
                    "Windows Media Video 9 complex profile (reserved in SMPTE ST 421)");
        }
        if (profile == ADVANCED) {
            return;
        }
        frameRateQuantized = bits.read(3);
        bitRateQuantized = bits.read(5);
        loopFilter = bits.readFlag();
        bits.skip(1);
        multiResolution = bits.readFlag();
        bits.skip(1);
        fastUvMc = bits.readFlag();
        extendedMv = bits.readFlag();
        dquant = bits.read(2);
        variableTransform = bits.readFlag();
        bits.skip(1);
        overlap = bits.readFlag();
        syncMarker = bits.readFlag();
        rangeReduction = bits.readFlag();
        maxBFrames = bits.read(3);
        quantizer = bits.read(2);
        frameInterpolationFlag = bits.readFlag();
        if (dquant > 2) {
            throw new MediaException.Malformed("reserved VC-1 DQUANT value " + dquant);
        }
        if (profile == SIMPLE) {
            loopFilter = false;
            fastUvMc = true;
            extendedMv = false;
            dquant = 0;
            syncMarker = false;
            rangeReduction = false;
            maxBFrames = 0;
        }
    }

    /**
     * Parses the advanced profile sequence header (Table 3).
     *
     * @param bits the reader, after the start code
     * @throws MediaException.Malformed if a field takes a reserved value
     * @throws MediaException.Unsupported if the color-difference format is not 4:2:0
     */
    void parseAdvanced(Bits bits) {
        profile = bits.read(2);
        if (profile != ADVANCED) {
            throw new MediaException.Malformed("VC-1 sequence header with profile " + profile);
        }
        level = bits.read(3);
        if (level > 4) {
            throw new MediaException.Malformed("reserved VC-1 level " + level);
        }
        var chroma = bits.read(2);
        if (chroma != 1) {
            throw new MediaException.Unsupported("vc-1 chroma format", "VC-1 color-difference format " + chroma);
        }
        frameRateQuantized = bits.read(3);
        bitRateQuantized = bits.read(5);
        postprocFlag = bits.readFlag();
        maxCodedWidth = bits.read(12) * 2 + 2;
        maxCodedHeight = bits.read(12) * 2 + 2;
        pulldown = bits.readFlag();
        interlace = bits.readFlag();
        frameCounterFlag = bits.readFlag();
        frameInterpolationFlag = bits.readFlag();
        bits.skip(1);
        psf = bits.readFlag();
        displayExtension = bits.readFlag();
        displayWidth = 0;
        displayHeight = 0;
        aspectNumerator = 0;
        aspectDenominator = 0;
        frameRateNumerator = 0;
        frameRateDenominator = 0;
        colorFormatPresent = false;
        colorPrimaries = 1;
        transferCharacteristics = 1;
        matrixCoefficients = 6;
        if (displayExtension) {
            displayWidth = bits.read(14) + 1;
            displayHeight = bits.read(14) + 1;
            if (bits.readFlag()) {
                var aspect = bits.read(4);
                if (aspect == 15) {
                    aspectNumerator = bits.read(8) + 1;
                    aspectDenominator = bits.read(8) + 1;
                } else if (aspect >= 1 && aspect <= 13) {
                    aspectNumerator = ASPECT[aspect][0];
                    aspectDenominator = ASPECT[aspect][1];
                }
            }
            if (bits.readFlag()) {
                if (bits.readFlag()) {
                    frameRateNumerator = bits.read(16) + 1;
                    frameRateDenominator = 32;
                } else {
                    var numerator = bits.read(8);
                    var denominator = bits.read(4);
                    if (numerator >= 1 && numerator <= 7 && denominator >= 1 && denominator <= 2) {
                        frameRateNumerator = FRAME_RATE_NUMERATORS[numerator] * 1000;
                        frameRateDenominator = denominator == 1 ? 1000 : 1001;
                    }
                }
            }
            if (bits.readFlag()) {
                colorFormatPresent = true;
                colorPrimaries = bits.read(8);
                transferCharacteristics = bits.read(8);
                matrixCoefficients = bits.read(8);
            }
        }
        leakyBuckets = 0;
        if (bits.readFlag()) {
            leakyBuckets = bits.read(5);
            bits.skip(8);
            bits.skip(32L * leakyBuckets);
        }
        if (bits.overrun()) {
            throw new MediaException.Malformed("truncated VC-1 sequence header");
        }
    }

    /**
     * The sample aspect ratios of ASPECT_RATIO 1 to 13 (Table 7), as numerator and denominator pairs indexed by the
     * code.
     */
    private static final int[][] ASPECT = {
            {0, 0}, {1, 1}, {12, 11}, {10, 11}, {16, 11}, {40, 33}, {24, 11}, {20, 11}, {32, 11}, {80, 33},
            {18, 11}, {15, 11}, {64, 33}, {160, 99}
    };

    /**
     * The frame rates of FRAMERATENR 1 to 7 (Table 8), indexed by the code.
     */
    private static final int[] FRAME_RATE_NUMERATORS = {0, 24, 25, 30, 50, 60, 48, 72};
}
