package com.github.auties00.smalt.video.vc1.internal;

import com.github.auties00.smalt.MediaException;

/**
 * Holds and parses the picture layer of a VC-1 picture (SMPTE ST 421 sections 7.1.1 and 9.1.1) up to, but not
 * including, its bitplanes and macroblock layer, which the picture decoder reads.
 *
 * <p>Instances are mutable and owned by one decoder.
 */
final class PictureHeader {
    /**
     * An I picture.
     */
    static final int I = 0;

    /**
     * A P picture.
     */
    static final int P = 1;

    /**
     * A B picture.
     */
    static final int B = 2;

    /**
     * A BI picture: a B picture of intra macroblocks, not a reference.
     */
    static final int BI = 3;

    /**
     * A skipped picture, a copy of the previous anchor.
     */
    static final int SKIPPED = 4;

    /**
     * Progressive frame coding.
     */
    static final int PROGRESSIVE = 0;

    /**
     * Interlace frame coding: one picture of interleaved fields.
     */
    static final int FRAME_INTERLACE = 1;

    /**
     * Interlace field coding: one picture per field.
     */
    static final int FIELD_INTERLACE = 2;

    /**
     * FCM: {@link #PROGRESSIVE}, {@link #FRAME_INTERLACE} or {@link #FIELD_INTERLACE}.
     */
    int frameCodingMode;

    /**
     * The picture type, {@link #I} to {@link #SKIPPED}; for a field picture, the type of the field being decoded.
     */
    int type;

    /**
     * INTERPFRM.
     */
    boolean interpolationHint;

    /**
     * RANGEREDFRM: whether the picture is coded range reduced.
     */
    boolean rangeReducedFrame;

    /**
     * TFCNTR.
     */
    int frameCounter;

    /**
     * TFF: whether the top field is displayed first.
     */
    boolean topFieldFirst = true;

    /**
     * RFF: whether the first field is displayed twice.
     */
    boolean repeatFirstField;

    /**
     * RPTFRM: the number of extra displays of a progressive segmented frame.
     */
    int repeatFrameCount;

    /**
     * RNDCTRL, the rounding control of motion compensation.
     */
    int rnd;

    /**
     * PQINDEX.
     */
    int pqindex;

    /**
     * The picture quantizer.
     */
    int pquant;

    /**
     * HALFQP: 1 when half a step is added to the quantizer.
     */
    int halfQp;

    /**
     * Whether the picture uses the uniform quantizer.
     */
    boolean uniform;

    /**
     * POSTPROC.
     */
    int postproc;

    /**
     * The MVRANGE index, 0 for the default range.
     */
    int mvRange;

    /**
     * The horizontal RESPIC bit of a multi-resolution I picture.
     */
    int halfWidth;

    /**
     * The vertical RESPIC bit of a multi-resolution I picture.
     */
    int halfHeight;

    /**
     * The BFRACTION symbol of {@code Vc1Codes.B_FRACTION}.
     */
    int bFractionCode;

    /**
     * The direct mode ScaleFactor of BFRACTION (section 8.4.5.4).
     */
    int scaleFactor;

    /**
     * The numerator of BFRACTION.
     */
    int bNumerator;

    /**
     * The denominator of BFRACTION.
     */
    int bDenominator;

    /**
     * Parses the start of an advanced profile frame header, through the quantizer and POSTPROC: FCM, PTYPE, TFCNTR,
     * the pulldown flags, the pan-scan windows, RNDCTRL, UVSAMP, INTERPFRM, BFRACTION, PQINDEX, HALFQP, PQUANTIZER
     * and POSTPROC (Tables 18 to 23).
     *
     * @param bits the reader, after the frame start code
     * @param sequence the sequence header in force
     * @param entry the entry point
     * @return {@code false} for a skipped picture, whose header ends after the pan-scan windows
     * @throws MediaException.Malformed if a code is invalid or reserved
     */
    boolean parseAdvancedStart(Bits bits, SequenceHeader sequence, EntryPoint entry) {
        frameCodingMode = PROGRESSIVE;
        if (sequence.interlace) {
            frameCodingMode = Vc1Codes.FRAME_CODING_MODE.decode(bits);
        }
        if (frameCodingMode == FIELD_INTERLACE) {
            return true;
        }
        type = Vc1Codes.ADVANCED_PICTURE_TYPE.decode(bits);
        if (type < 0) {
            throw new MediaException.Malformed("invalid VC-1 picture type");
        }
        parseTiming(bits, sequence, entry, type == SKIPPED);
        if (type == SKIPPED) {
            return false;
        }
        parseQuantizer(bits, sequence, entry, true);
        return true;
    }

    /**
     * Parses the timing and display fields shared by frame and field picture headers: TFCNTR, the pulldown flags and
     * the pan-scan windows, then RNDCTRL, UVSAMP and INTERPFRM unless the picture is skipped.
     *
     * @param bits the reader
     * @param sequence the sequence header in force
     * @param entry the entry point
     * @param skipped whether the picture is a skipped picture
     */
    void parseTiming(Bits bits, SequenceHeader sequence, EntryPoint entry, boolean skipped) {
        if (sequence.frameCounterFlag && !skipped) {
            frameCounter = bits.read(8);
        }
        topFieldFirst = true;
        repeatFirstField = false;
        repeatFrameCount = 0;
        if (sequence.pulldown) {
            if (!sequence.interlace || sequence.psf) {
                repeatFrameCount = bits.read(2);
            } else {
                topFieldFirst = bits.readFlag();
                repeatFirstField = bits.readFlag();
            }
        }
        if (entry.panScan && bits.readFlag()) {
            int windows;
            if (sequence.interlace && !sequence.psf) {
                windows = sequence.pulldown ? 2 + (repeatFirstField ? 1 : 0) : 2;
            } else {
                windows = sequence.pulldown ? 1 + repeatFrameCount : 1;
            }
            bits.skip(64L * windows);
        }
        if (skipped) {
            return;
        }
        rnd = bits.readBit();
        if (sequence.interlace) {
            bits.skip(1);
        }
        interpolationHint = frameCodingMode == PROGRESSIVE && sequence.frameInterpolationFlag && bits.readFlag();
    }

    /**
     * Parses BFRACTION when the picture is B, then PQINDEX, HALFQP, PQUANTIZER and, in the advanced profile,
     * POSTPROC.
     *
     * @param bits the reader
     * @param sequence the sequence header in force
     * @param entry the entry point
     * @param advanced whether the stream is in the advanced profile
     */
    void parseQuantizer(Bits bits, SequenceHeader sequence, EntryPoint entry, boolean advanced) {
        if (advanced && type == B && frameCodingMode == PROGRESSIVE) {
            parseBFraction(bits, true);
        }
        pqindex = bits.read(5);
        if (pqindex == 0) {
            throw new MediaException.Malformed("reserved VC-1 PQINDEX 0");
        }
        halfQp = pqindex <= 8 ? bits.readBit() : 0;
        switch (entry.quantizer) {
            case 0 -> {
                pquant = IMPLICIT_PQUANT[pqindex];
                uniform = pqindex <= 8;
            }
            case 1 -> {
                pquant = pqindex;
                uniform = bits.readFlag();
            }
            case 2 -> {
                pquant = pqindex;
                uniform = false;
            }
            default -> {
                pquant = pqindex;
                uniform = true;
            }
        }
        postproc = advanced && sequence.postprocFlag ? bits.read(2) : 0;
        if (advanced && type == B && frameCodingMode == FRAME_INTERLACE) {
            parseBFraction(bits, true);
        }
    }

    /**
     * The picture types of the first and second fields, indexed by FPTYPE (Table 105).
     */
    private static final int[][] FIELD_TYPES = {
            {I, I}, {I, P}, {P, I}, {P, P}, {B, B}, {B, BI}, {BI, B}, {BI, BI}
    };

    /**
     * The picture type of the first field of a field picture.
     */
    int firstFieldType;

    /**
     * The picture type of the second field of a field picture.
     */
    int secondFieldType;

    /**
     * REFDIST of a field picture, 0 when absent.
     */
    int referenceDistance;

    /**
     * Parses the frame header of an interlace field picture after FCM (Table 85): FPTYPE, the timing and display
     * fields, REFDIST and BFRACTION.
     *
     * @param bits the reader, after FCM
     * @param sequence the sequence header in force
     * @param entry the entry point
     * @throws MediaException.Malformed if a code is invalid or reserved
     */
    void parseFieldFrameHeader(Bits bits, SequenceHeader sequence, EntryPoint entry) {
        var fieldTypes = FIELD_TYPES[bits.read(3)];
        firstFieldType = fieldTypes[0];
        secondFieldType = fieldTypes[1];
        parseTiming(bits, sequence, entry, false);
        var bPair = firstFieldType == B || firstFieldType == BI;
        referenceDistance = 0;
        if (entry.referenceDistance && !bPair) {
            referenceDistance = bits.read(2);
            if (referenceDistance == 3) {
                while (bits.readBit() == 1) {
                    referenceDistance++;
                    if (referenceDistance > 16) {
                        throw new MediaException.Malformed("VC-1 REFDIST above 16");
                    }
                }
            }
        }
        if (bPair) {
            parseBFraction(bits, true);
        }
        if (bits.overrun()) {
            throw new MediaException.Malformed("truncated VC-1 field picture header");
        }
    }

    /**
     * Parses BFRACTION and derives the direct mode scale factor (Figure 70).
     *
     * @param bits the reader
     * @param advanced whether the stream is in the advanced profile, where the BI code is reserved
     * @return {@code true} if the code signals a BI picture (main profile)
     * @throws MediaException.Malformed if the code is invalid or reserved
     */
    boolean parseBFraction(Bits bits, boolean advanced) {
        var code = Vc1Codes.B_FRACTION.decode(bits);
        if (code < 0 || code == 21 || (advanced && code == 22)) {
            throw new MediaException.Malformed("invalid VC-1 BFRACTION");
        }
        bFractionCode = code;
        if (code == 22) {
            return true;
        }
        int numerator;
        int denominator;
        if (code < 7) {
            numerator = SHORT_NUMERATOR[code];
            denominator = SHORT_DENOMINATOR[code];
        } else {
            numerator = LONG_NUMERATOR[code - 7];
            denominator = LONG_DENOMINATOR[code - 7];
        }
        bNumerator = numerator;
        bDenominator = denominator;
        scaleFactor = numerator * INVERSE[denominator - 1];
        return false;
    }

    /**
     * {@return whether the B fraction is at least one half, which selects the meaning of BMVTYPE (Table 57)}
     */
    boolean fractionAtLeastHalf() {
        return 2 * bNumerator >= bDenominator;
    }

    /**
     * Parses the simple and main profile start of a picture header: INTERPFRM, FRMCNT, RANGEREDFRM and PTYPE, then
     * BFRACTION and BF where present, PQINDEX, HALFQP, PQUANTIZER, MVRANGE and RESPIC (Tables 16, 17, 19 and 21).
     *
     * @param bits the reader, at the start of the picture
     * @param sequence the sequence header in force
     * @param entry the controls
     * @throws MediaException.Malformed if a code is invalid or reserved
     */
    void parseSimpleMain(Bits bits, SequenceHeader sequence, EntryPoint entry) {
        frameCodingMode = PROGRESSIVE;
        topFieldFirst = true;
        repeatFirstField = false;
        repeatFrameCount = 0;
        interpolationHint = sequence.frameInterpolationFlag && bits.readFlag();
        frameCounter = bits.read(2);
        rangeReducedFrame = sequence.rangeReduction && bits.readFlag();
        if (sequence.maxBFrames == 0) {
            type = bits.readBit() == 0 ? I : P;
        } else if (bits.readBit() == 1) {
            type = P;
        } else {
            type = bits.readBit() == 1 ? I : B;
        }
        if (type == B && parseBFraction(bits, false)) {
            type = BI;
        }
        if (type == I || type == BI) {
            bits.skip(7);
        }
        parseQuantizer(bits, sequence, entry, false);
        mvRange = entry.extendedMv ? Vc1Codes.MV_RANGE.decode(bits) : 0;
        halfWidth = 0;
        halfHeight = 0;
        if (sequence.multiResolution && (type == I || type == P)) {
            var respic = bits.read(2);
            halfWidth = respic & 1;
            halfHeight = respic >>> 1;
        }
    }

    /**
     * The picture quantizer of each PQINDEX under the implicit quantizer (Table 36).
     */
    private static final int[] IMPLICIT_PQUANT = {
            0, 1, 2, 3, 4, 5, 6, 7, 8, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25,
            27, 29, 31
    };

    /**
     * The numerators of the 3-bit BFRACTION codes (Table 40).
     */
    private static final int[] SHORT_NUMERATOR = {1, 1, 2, 1, 3, 1, 2};

    /**
     * The denominators of the 3-bit BFRACTION codes.
     */
    private static final int[] SHORT_DENOMINATOR = {2, 3, 3, 4, 4, 5, 5};

    /**
     * The numerators of the 7-bit BFRACTION codes 1110000b to 1111101b.
     */
    private static final int[] LONG_NUMERATOR = {3, 4, 1, 5, 1, 2, 3, 4, 5, 6, 1, 3, 5, 7};

    /**
     * The denominators of the 7-bit BFRACTION codes.
     */
    private static final int[] LONG_DENOMINATOR = {5, 5, 6, 6, 7, 7, 7, 7, 7, 7, 8, 8, 8, 8};

    /**
     * The reciprocals 256 / denominator of the BFRACTION denominators 1 to 8, as FrameReciprocal of section 8.4.5.4
     * rounds them.
     */
    private static final int[] INVERSE = {256, 128, 85, 64, 51, 43, 37, 32};
}
