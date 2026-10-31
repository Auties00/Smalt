package com.github.auties00.smalt.video.av1.internal;

import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ChromaSiting;

import java.util.Arrays;

/**
 * Holds the fields of an AV1 sequence header OBU ({@code sequence_header_obu()}, AV1 specification
 * section 5.5) that decoding uses.
 *
 * @implNote This implementation mirrors dav1d's {@code Dav1dSequenceHeader} and its parser
 * {@code parse_seq_hdr} ({@code src/obu.c}).
 */
public final class SequenceHeader {
    /**
     * The pixel layout of a monochrome stream.
     */
    public static final int LAYOUT_I400 = 0;

    /**
     * The pixel layout of a 4:2:0 stream.
     */
    public static final int LAYOUT_I420 = 1;

    /**
     * The pixel layout of a 4:2:2 stream.
     */
    public static final int LAYOUT_I422 = 2;

    /**
     * The pixel layout of a 4:4:4 stream.
     */
    public static final int LAYOUT_I444 = 3;

    /**
     * The value of the adaptive tool switches meaning that each frame signals the choice.
     */
    public static final int ADAPTIVE = 2;

    /**
     * The stream profile: 0 (main), 1 (high) or 2 (professional).
     */
    public int profile;

    /**
     * The largest frame width of the stream.
     */
    public int maxWidth;

    /**
     * The largest frame height of the stream.
     */
    public int maxHeight;

    /**
     * The pixel layout, one of the {@code LAYOUT_} constants.
     */
    public int layout;

    /**
     * The color primaries (H.273 code point).
     */
    public int pri;

    /**
     * The transfer characteristics (H.273 code point).
     */
    public int trc;

    /**
     * The matrix coefficients (H.273 code point).
     */
    public int mtrx;

    /**
     * The chroma sample position: 0 unknown, 1 vertical, 2 colocated.
     */
    public int chr;

    /**
     * The bit depth index: 0, 1 and 2 for 8, 10 and 12 bits per sample.
     */
    public int hbd;

    /**
     * Whether samples use the full range.
     */
    public boolean colorRange;

    /**
     * The number of operating points.
     */
    public int numOperatingPoints;

    /**
     * The layer membership mask ({@code operating_point_idc}) of each operating point.
     */
    public final int[] operatingPointIdc = new int[32];

    /**
     * The major level of each operating point.
     */
    public final int[] majorLevel = new int[32];

    /**
     * The minor level of each operating point.
     */
    public final int[] minorLevel = new int[32];

    /**
     * The tier of each operating point.
     */
    public final int[] tier = new int[32];

    /**
     * Whether each operating point carries decoder model parameters.
     */
    public final boolean[] decoderModelParamPresent = new boolean[32];

    /**
     * Whether each operating point carries display model parameters.
     */
    public final boolean[] displayModelParamPresent = new boolean[32];

    /**
     * The initial display delay of each operating point.
     */
    public final int[] initialDisplayDelay = new int[32];

    /**
     * The decoder buffer delay of each operating point.
     */
    public final long[] decoderBufferDelay = new long[32];

    /**
     * The encoder buffer delay of each operating point.
     */
    public final long[] encoderBufferDelay = new long[32];

    /**
     * Whether each operating point uses the low delay mode.
     */
    public final boolean[] lowDelayMode = new boolean[32];

    /**
     * Whether the stream is a single still picture.
     */
    public boolean stillPicture;

    /**
     * Whether the reduced still picture header syntax is used.
     */
    public boolean reducedStillPictureHeader;

    /**
     * Whether timing information is present.
     */
    public boolean timingInfoPresent;

    /**
     * The number of time units of one clock tick.
     */
    public long numUnitsInTick;

    /**
     * The number of time units in a second.
     */
    public long timeScale;

    /**
     * Whether pictures are equally spaced in time.
     */
    public boolean equalPictureInterval;

    /**
     * The number of clock ticks per picture when {@link #equalPictureInterval} is set.
     */
    public long numTicksPerPicture;

    /**
     * Whether decoder model information is present.
     */
    public boolean decoderModelInfoPresent;

    /**
     * The bit length of the buffer delay fields.
     */
    public int encoderDecoderBufferDelayLength;

    /**
     * The number of time units of one decoding clock tick.
     */
    public long numUnitsInDecodingTick;

    /**
     * The bit length of the buffer removal time fields.
     */
    public int bufferRemovalDelayLength;

    /**
     * The bit length of the frame presentation time fields.
     */
    public int framePresentationDelayLength;

    /**
     * Whether display model information is present.
     */
    public boolean displayModelInfoPresent;

    /**
     * The bit length of the frame width fields.
     */
    public int widthNBits;

    /**
     * The bit length of the frame height fields.
     */
    public int heightNBits;

    /**
     * Whether frame identifiers are present.
     */
    public boolean frameIdNumbersPresent;

    /**
     * The bit length of the delta frame identifier fields.
     */
    public int deltaFrameIdNBits;

    /**
     * The bit length of the frame identifier fields.
     */
    public int frameIdNBits;

    /**
     * Whether superblocks are 128x128 (otherwise 64x64).
     */
    public boolean sb128;

    /**
     * Whether filter intra may be used.
     */
    public boolean filterIntra;

    /**
     * Whether the intra edge filter is enabled.
     */
    public boolean intraEdgeFilter;

    /**
     * Whether inter-intra prediction may be used.
     */
    public boolean interIntra;

    /**
     * Whether masked compound prediction may be used.
     */
    public boolean maskedCompound;

    /**
     * Whether warped motion may be used.
     */
    public boolean warpedMotion;

    /**
     * Whether the two interpolation filter directions may differ.
     */
    public boolean dualFilter;

    /**
     * Whether order hints are present.
     */
    public boolean orderHint;

    /**
     * Whether distance-weighted compound prediction may be used.
     */
    public boolean jntComp;

    /**
     * Whether motion vectors of reference frames may be used.
     */
    public boolean refFrameMvs;

    /**
     * The screen content tools switch: 0 off, 1 on, {@link #ADAPTIVE}.
     */
    public int screenContentTools;

    /**
     * The integer motion vector switch: 0 off, 1 on, {@link #ADAPTIVE}.
     */
    public int forceIntegerMv;

    /**
     * The bit length of order hints.
     */
    public int orderHintNBits;

    /**
     * Whether super-resolution may be used.
     */
    public boolean superRes;

    /**
     * Whether CDEF is enabled.
     */
    public boolean cdef;

    /**
     * Whether loop restoration is enabled.
     */
    public boolean restoration;

    /**
     * The horizontal chroma subsampling shift.
     */
    public int ssHor;

    /**
     * The vertical chroma subsampling shift.
     */
    public int ssVer;

    /**
     * Whether the stream is monochrome.
     */
    public boolean monochrome;

    /**
     * Whether color description fields are present.
     */
    public boolean colorDescriptionPresent;

    /**
     * Whether the two chroma planes have separate quantizer deltas.
     */
    public boolean separateUvDeltaQ;

    /**
     * Whether film grain parameters are present.
     */
    public boolean filmGrainPresent;

    /**
     * Creates an all-zero header.
     */
    public SequenceHeader() {
    }

    /**
     * Returns the number of bits per sample.
     *
     * @return 8, 10 or 12
     */
    public int bitDepth() {
        return 8 + 2 * hbd;
    }

    /**
     * Returns the chroma format of a pixel layout.
     *
     * @param layout the pixel layout, one of the {@code LAYOUT_} constants
     * @return the chroma format, {@link ChromaFormat#YUV420} for an unknown layout
     */
    public static ChromaFormat chromaFormat(int layout) {
        return switch (layout) {
            case LAYOUT_I400 -> ChromaFormat.MONOCHROME;
            case LAYOUT_I422 -> ChromaFormat.YUV422;
            case LAYOUT_I444 -> ChromaFormat.YUV444;
            default -> ChromaFormat.YUV420;
        };
    }

    /**
     * Returns the pixel layout of a chroma format.
     *
     * @param format the chroma format, which AV1 can code
     * @return the layout, one of the {@code LAYOUT_} constants
     * @throws IllegalArgumentException if the format is {@link ChromaFormat#YUV440}, which AV1 cannot
     * code
     */
    public static int layoutOf(ChromaFormat format) {
        return switch (format) {
            case MONOCHROME -> LAYOUT_I400;
            case YUV420 -> LAYOUT_I420;
            case YUV422 -> LAYOUT_I422;
            case YUV444 -> LAYOUT_I444;
            case YUV440 -> throw new IllegalArgumentException("AV1 cannot code 4:4:0 chroma");
        };
    }

    /**
     * {@return the chroma siting {@link #chr} signals, {@link ChromaSiting#UNSPECIFIED} when unknown}
     */
    public ChromaSiting chromaSiting() {
        return switch (chr) {
            case 1 -> ChromaSiting.LEFT;
            case 2 -> ChromaSiting.TOP_LEFT;
            default -> ChromaSiting.UNSPECIFIED;
        };
    }

    /**
     * Returns whether this header codes the same sequence as another, ignoring the operating
     * parameters, which may change within a sequence (AV1 specification section 7.5).
     *
     * @param other the other header
     * @return {@code true} when every field but the operating parameters is equal
     */
    public boolean sameSequence(SequenceHeader other) {
        return profile == other.profile && maxWidth == other.maxWidth && maxHeight == other.maxHeight
                && layout == other.layout && pri == other.pri && trc == other.trc && mtrx == other.mtrx
                && chr == other.chr && hbd == other.hbd && colorRange == other.colorRange
                && numOperatingPoints == other.numOperatingPoints
                && Arrays.equals(operatingPointIdc, other.operatingPointIdc)
                && Arrays.equals(majorLevel, other.majorLevel) && Arrays.equals(minorLevel, other.minorLevel)
                && Arrays.equals(tier, other.tier)
                && Arrays.equals(decoderModelParamPresent, other.decoderModelParamPresent)
                && Arrays.equals(displayModelParamPresent, other.displayModelParamPresent)
                && Arrays.equals(initialDisplayDelay, other.initialDisplayDelay)
                && stillPicture == other.stillPicture && reducedStillPictureHeader == other.reducedStillPictureHeader
                && timingInfoPresent == other.timingInfoPresent && numUnitsInTick == other.numUnitsInTick
                && timeScale == other.timeScale && equalPictureInterval == other.equalPictureInterval
                && numTicksPerPicture == other.numTicksPerPicture
                && decoderModelInfoPresent == other.decoderModelInfoPresent
                && encoderDecoderBufferDelayLength == other.encoderDecoderBufferDelayLength
                && numUnitsInDecodingTick == other.numUnitsInDecodingTick
                && bufferRemovalDelayLength == other.bufferRemovalDelayLength
                && framePresentationDelayLength == other.framePresentationDelayLength
                && displayModelInfoPresent == other.displayModelInfoPresent && widthNBits == other.widthNBits
                && heightNBits == other.heightNBits && frameIdNumbersPresent == other.frameIdNumbersPresent
                && deltaFrameIdNBits == other.deltaFrameIdNBits && frameIdNBits == other.frameIdNBits
                && sb128 == other.sb128 && filterIntra == other.filterIntra
                && intraEdgeFilter == other.intraEdgeFilter && interIntra == other.interIntra
                && maskedCompound == other.maskedCompound && warpedMotion == other.warpedMotion
                && dualFilter == other.dualFilter && orderHint == other.orderHint && jntComp == other.jntComp
                && refFrameMvs == other.refFrameMvs && screenContentTools == other.screenContentTools
                && forceIntegerMv == other.forceIntegerMv && orderHintNBits == other.orderHintNBits
                && superRes == other.superRes && cdef == other.cdef && restoration == other.restoration
                && ssHor == other.ssHor && ssVer == other.ssVer && monochrome == other.monochrome
                && colorDescriptionPresent == other.colorDescriptionPresent
                && separateUvDeltaQ == other.separateUvDeltaQ && filmGrainPresent == other.filmGrainPresent;
    }

    /**
     * Parses a sequence header OBU payload.
     *
     * <p>Fields are read as dav1d's {@code parse_seq_hdr} reads them; the trailing bits are checked
     * when {@code strict} is set.
     *
     * @param gb the reader, positioned at the start of the payload
     * @param strict whether to reject streams violating constraints that do not affect decoding
     * @return {@code true} when the header is valid
     */
    public boolean parse(BitReader gb, boolean strict) {
        profile = gb.bits(3);
        if (profile > 2) {
            return false;
        }
        stillPicture = gb.bit() != 0;
        reducedStillPictureHeader = gb.bit() != 0;
        if (reducedStillPictureHeader && !stillPicture) {
            return false;
        }
        if (reducedStillPictureHeader) {
            numOperatingPoints = 1;
            majorLevel[0] = gb.bits(3);
            minorLevel[0] = gb.bits(2);
            initialDisplayDelay[0] = 10;
        } else {
            timingInfoPresent = gb.bit() != 0;
            if (timingInfoPresent) {
                numUnitsInTick = gb.bits32();
                timeScale = gb.bits32();
                if (strict && (numUnitsInTick == 0 || timeScale == 0)) {
                    return false;
                }
                equalPictureInterval = gb.bit() != 0;
                if (equalPictureInterval) {
                    var ticks = gb.vlc();
                    if (ticks == -1) {
                        return false;
                    }
                    numTicksPerPicture = (ticks & 0xFFFFFFFFL) + 1;
                }
                decoderModelInfoPresent = gb.bit() != 0;
                if (decoderModelInfoPresent) {
                    encoderDecoderBufferDelayLength = gb.bits(5) + 1;
                    numUnitsInDecodingTick = gb.bits32();
                    if (strict && numUnitsInDecodingTick == 0) {
                        return false;
                    }
                    bufferRemovalDelayLength = gb.bits(5) + 1;
                    framePresentationDelayLength = gb.bits(5) + 1;
                }
            }
            displayModelInfoPresent = gb.bit() != 0;
            numOperatingPoints = gb.bits(5) + 1;
            for (var i = 0; i < numOperatingPoints; i++) {
                var idc = gb.bits(12);
                if (idc != 0 && ((idc & 0xff) == 0 || (idc & 0xf00) == 0)) {
                    return false;
                }
                operatingPointIdc[i] = idc;
                majorLevel[i] = 2 + gb.bits(3);
                minorLevel[i] = gb.bits(2);
                if (majorLevel[i] > 3) {
                    tier[i] = gb.bit();
                }
                if (decoderModelInfoPresent) {
                    decoderModelParamPresent[i] = gb.bit() != 0;
                    if (decoderModelParamPresent[i]) {
                        decoderBufferDelay[i] = gb.bits(encoderDecoderBufferDelayLength) & 0xFFFFFFFFL;
                        encoderBufferDelay[i] = gb.bits(encoderDecoderBufferDelayLength) & 0xFFFFFFFFL;
                        lowDelayMode[i] = gb.bit() != 0;
                    }
                }
                if (displayModelInfoPresent) {
                    displayModelParamPresent[i] = gb.bit() != 0;
                }
                initialDisplayDelay[i] = displayModelParamPresent[i] ? gb.bits(4) + 1 : 10;
            }
        }
        widthNBits = gb.bits(4) + 1;
        heightNBits = gb.bits(4) + 1;
        maxWidth = gb.bits(widthNBits) + 1;
        maxHeight = gb.bits(heightNBits) + 1;
        if (!reducedStillPictureHeader) {
            frameIdNumbersPresent = gb.bit() != 0;
            if (frameIdNumbersPresent) {
                deltaFrameIdNBits = gb.bits(4) + 2;
                frameIdNBits = gb.bits(3) + deltaFrameIdNBits + 1;
            }
        }
        sb128 = gb.bit() != 0;
        filterIntra = gb.bit() != 0;
        intraEdgeFilter = gb.bit() != 0;
        if (reducedStillPictureHeader) {
            screenContentTools = ADAPTIVE;
            forceIntegerMv = ADAPTIVE;
        } else {
            interIntra = gb.bit() != 0;
            maskedCompound = gb.bit() != 0;
            warpedMotion = gb.bit() != 0;
            dualFilter = gb.bit() != 0;
            orderHint = gb.bit() != 0;
            if (orderHint) {
                jntComp = gb.bit() != 0;
                refFrameMvs = gb.bit() != 0;
            }
            screenContentTools = gb.bit() != 0 ? ADAPTIVE : gb.bit();
            forceIntegerMv = screenContentTools != 0 ? (gb.bit() != 0 ? ADAPTIVE : gb.bit()) : 2;
            if (orderHint) {
                orderHintNBits = gb.bits(3) + 1;
            }
        }
        superRes = gb.bit() != 0;
        cdef = gb.bit() != 0;
        restoration = gb.bit() != 0;
        hbd = gb.bit();
        if (profile == 2 && hbd != 0) {
            hbd += gb.bit();
        }
        if (profile != 1) {
            monochrome = gb.bit() != 0;
        }
        colorDescriptionPresent = gb.bit() != 0;
        if (colorDescriptionPresent) {
            pri = gb.bits(8);
            trc = gb.bits(8);
            mtrx = gb.bits(8);
        } else {
            pri = 2;
            trc = 2;
            mtrx = 2;
        }
        if (monochrome) {
            colorRange = gb.bit() != 0;
            layout = LAYOUT_I400;
            ssHor = 1;
            ssVer = 1;
            chr = 0;
        } else if (pri == 1 && trc == 13 && mtrx == 0) {
            layout = LAYOUT_I444;
            colorRange = true;
            if (profile != 1 && !(profile == 2 && hbd == 2)) {
                return false;
            }
        } else {
            colorRange = gb.bit() != 0;
            switch (profile) {
                case 0 -> {
                    layout = LAYOUT_I420;
                    ssHor = 1;
                    ssVer = 1;
                }
                case 1 -> layout = LAYOUT_I444;
                default -> {
                    if (hbd == 2) {
                        ssHor = gb.bit();
                        if (ssHor != 0) {
                            ssVer = gb.bit();
                        }
                    } else {
                        ssHor = 1;
                    }
                    layout = ssHor != 0 ? (ssVer != 0 ? LAYOUT_I420 : LAYOUT_I422) : LAYOUT_I444;
                }
            }
            chr = (ssHor & ssVer) != 0 ? gb.bits(2) : 0;
        }
        if (strict && mtrx == 0 && layout != LAYOUT_I444) {
            return false;
        }
        if (!monochrome) {
            separateUvDeltaQ = gb.bit() != 0;
        }
        filmGrainPresent = gb.bit() != 0;
        return Obu.checkTrailingBits(gb, strict);
    }
}
