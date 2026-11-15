package com.github.auties00.smalt.video.av1.internal;

import java.util.Arrays;

/**
 * Holds the fields of an AV1 frame header ({@code uncompressed_header()}, AV1 specification section
 * 5.9) and parses it.
 *
 * <p>Instances are filled by {@link #parse} and then treated as immutable while reference slots and
 * pictures share them; an owner that no longer shares one may {@link #reset} it and parse into it
 * again.
 *
 * @implNote This implementation mirrors dav1d's {@code Dav1dFrameHeader} and ports
 * {@code parse_frame_hdr} and {@code read_frame_size} ({@code src/obu.c}).
 */
public final class FrameHeader {
    /**
     * The key frame type.
     */
    public static final int KEY = 0;

    /**
     * The inter frame type.
     */
    public static final int INTER = 1;

    /**
     * The intra-only frame type.
     */
    public static final int INTRA = 2;

    /**
     * The switch frame type.
     */
    public static final int SWITCH = 3;

    /**
     * The {@code primary_ref_frame} value meaning no primary reference.
     */
    public static final int PRIMARY_REF_NONE = 7;

    /**
     * The interpolation filter mode meaning per-block switchable filters.
     */
    public static final int FILTER_SWITCHABLE = 4;

    /**
     * The transform mode allowing only 4x4 transforms.
     */
    public static final int TX_4X4_ONLY = 0;

    /**
     * The transform mode using the largest transform of each block.
     */
    public static final int TX_LARGEST = 1;

    /**
     * The transform mode with per-block transform sizes.
     */
    public static final int TX_SWITCHABLE = 2;

    /**
     * The restoration type of a plane without loop restoration.
     */
    public static final int RESTORATION_NONE = 0;

    /**
     * The restoration type with a per-unit choice.
     */
    public static final int RESTORATION_SWITCHABLE = 1;

    /**
     * The Wiener filter restoration type.
     */
    public static final int RESTORATION_WIENER = 2;

    /**
     * The self-guided filter restoration type.
     */
    public static final int RESTORATION_SGRPROJ = 3;

    /**
     * The default loop filter reference deltas ({@code ref_deltas} of section 7.20).
     */
    private static final int[] DEFAULT_REF_DELTAS = {1, 0, 0, 0, -1, 0, -1, -1};

    /**
     * The film grain parameters of a new header, which {@link #reset} restores; never modified.
     */
    private static final FilmGrainData NO_FILM_GRAIN = new FilmGrainData();

    /**
     * The identity global motion model that predicts the parameters of a frame without a primary
     * reference; never modified.
     */
    private static final WarpedMotionParams IDENTITY_GMV = new WarpedMotionParams();

    /**
     * Whether film grain is applied to this frame.
     */
    public boolean filmGrainPresent;

    /**
     * Whether the film grain parameters were coded rather than copied from a reference.
     */
    public boolean filmGrainUpdate;

    /**
     * The film grain parameters.
     */
    public final FilmGrainData filmGrain = new FilmGrainData();

    /**
     * The frame type: {@link #KEY}, {@link #INTER}, {@link #INTRA} or {@link #SWITCH}.
     */
    public int frameType;

    /**
     * The coded frame width (before super-resolution upscaling).
     */
    public int codedWidth;

    /**
     * The upscaled frame width.
     */
    public int upscaledWidth;

    /**
     * The frame height.
     */
    public int height;

    /**
     * The order hint of the frame.
     */
    public int frameOffset;

    /**
     * The temporal layer of the frame.
     */
    public int temporalId;

    /**
     * The spatial layer of the frame.
     */
    public int spatialId;

    /**
     * Whether the header only shows a previously decoded frame.
     */
    public boolean showExistingFrame;

    /**
     * The reference slot of the frame to show when {@link #showExistingFrame} is set.
     */
    public int existingFrameIdx;

    /**
     * The presentation time of a shown frame in display clock ticks
     * ({@code frame_presentation_time}), 0 unless the sequence signals the decoder model without
     * equal picture intervals.
     */
    public long framePresentationTime;

    /**
     * Whether the header signals the removal times of the frame from the decoder buffer
     * ({@code buffer_removal_time_present_flag}).
     */
    public boolean bufferRemovalTimePresent;

    /**
     * The removal time of the frame from the decoder buffer of each operating point in decoding
     * clock ticks ({@code buffer_removal_time}), 0 for the operating points it does not signal.
     */
    public final long[] bufferRemovalTime = new long[32];

    /**
     * The frame identifier.
     */
    public int frameId;

    /**
     * Whether the frame is shown.
     */
    public boolean showFrame;

    /**
     * Whether the frame may be shown later through {@link #showExistingFrame}.
     */
    public boolean showableFrame;

    /**
     * Whether error resilient mode is on.
     */
    public boolean errorResilientMode;

    /**
     * Whether CDF adaptation is disabled.
     */
    public boolean disableCdfUpdate;

    /**
     * Whether screen content tools are allowed.
     */
    public boolean allowScreenContentTools;

    /**
     * Whether motion vectors are whole-sample only.
     */
    public boolean forceIntegerMv;

    /**
     * Whether the frame size is coded explicitly.
     */
    public boolean frameSizeOverride;

    /**
     * The reference whose state the frame inherits, or {@link #PRIMARY_REF_NONE}.
     */
    public int primaryRefFrame;

    /**
     * The bit mask of reference slots the frame replaces.
     */
    public int refreshFrameFlags;

    /**
     * The render width.
     */
    public int renderWidth;

    /**
     * The render height.
     */
    public int renderHeight;

    /**
     * The super-resolution denominator, 8 when super-resolution is off.
     */
    public int superResDenominator;

    /**
     * Whether super-resolution is enabled.
     */
    public boolean superResEnabled;

    /**
     * Whether intra block copy is allowed.
     */
    public boolean allowIntrabc;

    /**
     * Whether the references were signaled with the short syntax.
     */
    public boolean frameRefShortSignaling;

    /**
     * The reference slot of each of the seven references (LAST to ALTREF).
     */
    public final int[] refidx = new int[7];

    /**
     * Whether motion vectors have eighth-sample precision.
     */
    public boolean hp;

    /**
     * The interpolation filter: 0 regular, 1 smooth, 2 sharp, 3 bilinear, {@link #FILTER_SWITCHABLE}.
     */
    public int subpelFilterMode;

    /**
     * Whether the motion mode is signaled per block.
     */
    public boolean switchableMotionMode;

    /**
     * Whether motion vectors of reference frames are used.
     */
    public boolean useRefFrameMvs;

    /**
     * Whether the frame's adapted CDFs are stored for later frames.
     */
    public boolean refreshContext;

    /**
     * Whether tiles are uniformly spaced.
     */
    public boolean tilingUniform;

    /**
     * The number of bytes of each tile size field.
     */
    public int tilingNBytes;

    /**
     * The smallest base-2 logarithm of the tile column count.
     */
    public int tilingMinLog2Cols;

    /**
     * The largest base-2 logarithm of the tile column count.
     */
    public int tilingMaxLog2Cols;

    /**
     * The base-2 logarithm of the tile column count.
     */
    public int tilingLog2Cols;

    /**
     * The number of tile columns.
     */
    public int tilingCols;

    /**
     * The smallest base-2 logarithm of the tile row count.
     */
    public int tilingMinLog2Rows;

    /**
     * The largest base-2 logarithm of the tile row count.
     */
    public int tilingMaxLog2Rows;

    /**
     * The base-2 logarithm of the tile row count.
     */
    public int tilingLog2Rows;

    /**
     * The number of tile rows.
     */
    public int tilingRows;

    /**
     * The first superblock column of each tile column, followed by the superblock width.
     */
    public final int[] colStartSb = new int[65];

    /**
     * The first superblock row of each tile row, followed by the superblock height.
     */
    public final int[] rowStartSb = new int[65];

    /**
     * The tile whose adapted CDFs the frame stores.
     */
    public int tilingUpdate;

    /**
     * The base quantizer index ({@code base_q_idx}).
     */
    public int quantYac;

    /**
     * The luma DC quantizer delta.
     */
    public int quantYdcDelta;

    /**
     * The first chroma plane DC quantizer delta.
     */
    public int quantUdcDelta;

    /**
     * The first chroma plane AC quantizer delta.
     */
    public int quantUacDelta;

    /**
     * The second chroma plane DC quantizer delta.
     */
    public int quantVdcDelta;

    /**
     * The second chroma plane AC quantizer delta.
     */
    public int quantVacDelta;

    /**
     * Whether quantizer matrices are used.
     */
    public boolean quantQm;

    /**
     * The luma quantizer matrix level.
     */
    public int quantQmY;

    /**
     * The first chroma plane quantizer matrix level.
     */
    public int quantQmU;

    /**
     * The second chroma plane quantizer matrix level.
     */
    public int quantQmV;

    /**
     * Whether segmentation is enabled.
     */
    public boolean segmentationEnabled;

    /**
     * Whether the segmentation map is coded.
     */
    public boolean segmentationUpdateMap;

    /**
     * Whether the segmentation map is coded relative to the previous one.
     */
    public boolean segmentationTemporal;

    /**
     * Whether the segment features are coded.
     */
    public boolean segmentationUpdateData;

    /**
     * The quantizer delta of each segment.
     */
    public final int[] segDeltaQ = new int[8];

    /**
     * The vertical-edge luma loop filter delta of each segment.
     */
    public final int[] segDeltaLfYV = new int[8];

    /**
     * The horizontal-edge luma loop filter delta of each segment.
     */
    public final int[] segDeltaLfYH = new int[8];

    /**
     * The first chroma plane loop filter delta of each segment.
     */
    public final int[] segDeltaLfU = new int[8];

    /**
     * The second chroma plane loop filter delta of each segment.
     */
    public final int[] segDeltaLfV = new int[8];

    /**
     * The reference frame feature of each segment, or {@code -1}.
     */
    public final int[] segRef = new int[8];

    /**
     * The skip feature of each segment.
     */
    public final boolean[] segSkip = new boolean[8];

    /**
     * The global motion feature of each segment.
     */
    public final boolean[] segGlobalmv = new boolean[8];

    /**
     * Whether a segment feature requires reading the segment identifier before the skip flag.
     */
    public boolean segPreskip;

    /**
     * The highest segment with an enabled feature, or {@code -1}.
     */
    public int segLastActiveSegid;

    /**
     * Whether each segment is lossless.
     */
    public final boolean[] segLossless = new boolean[8];

    /**
     * The quantizer index of each segment.
     */
    public final int[] segQidx = new int[8];

    /**
     * Whether quantizer deltas are coded per superblock.
     */
    public boolean deltaQPresent;

    /**
     * The base-2 logarithm of the quantizer delta resolution.
     */
    public int deltaQResLog2;

    /**
     * Whether loop filter deltas are coded per superblock.
     */
    public boolean deltaLfPresent;

    /**
     * The base-2 logarithm of the loop filter delta resolution.
     */
    public int deltaLfResLog2;

    /**
     * Whether a loop filter delta is coded for each edge direction and plane.
     */
    public boolean deltaLfMulti;

    /**
     * Whether every segment is lossless.
     */
    public boolean allLossless;

    /**
     * The luma loop filter level of vertical (0) and horizontal (1) edges.
     */
    public final int[] loopfilterLevelY = new int[2];

    /**
     * The first chroma plane loop filter level.
     */
    public int loopfilterLevelU;

    /**
     * The second chroma plane loop filter level.
     */
    public int loopfilterLevelV;

    /**
     * Whether mode and reference loop filter deltas apply.
     */
    public boolean modeRefDeltaEnabled;

    /**
     * Whether the mode and reference loop filter deltas are coded.
     */
    public boolean modeRefDeltaUpdate;

    /**
     * The loop filter delta of zero and non-zero motion vector modes.
     */
    public final int[] modeDelta = new int[2];

    /**
     * The loop filter delta of each reference frame (intra first).
     */
    public final int[] refDelta = new int[8];

    /**
     * The loop filter sharpness.
     */
    public int sharpness;

    /**
     * The CDEF damping.
     */
    public int cdefDamping;

    /**
     * The number of bits of the per-block CDEF index.
     */
    public int cdefNBits;

    /**
     * The luma CDEF strength of each index.
     */
    public final int[] cdefYStrength = new int[8];

    /**
     * The chroma CDEF strength of each index.
     */
    public final int[] cdefUvStrength = new int[8];

    /**
     * The restoration type of each plane.
     */
    public final int[] restorationType = new int[3];

    /**
     * The base-2 logarithm of the restoration unit size of luma (0) and chroma (1).
     */
    public final int[] restorationUnitSize = new int[2];

    /**
     * The transform mode.
     */
    public int txfmMode;

    /**
     * Whether compound references may be selected per block.
     */
    public boolean switchableCompRefs;

    /**
     * Whether skip mode is allowed.
     */
    public boolean skipModeAllowed;

    /**
     * Whether skip mode is enabled.
     */
    public boolean skipModeEnabled;

    /**
     * The two references of skip mode blocks.
     */
    public final int[] skipModeRefs = new int[2];

    /**
     * Whether warped motion is allowed.
     */
    public boolean warpMotion;

    /**
     * Whether the reduced transform type set is used.
     */
    public boolean reducedTxtpSet;

    /**
     * The global motion model of each reference.
     */
    public final WarpedMotionParams[] gmv = new WarpedMotionParams[7];

    /**
     * The scratch order hint distances of {@link #setFrameRefs}, entry {@code i + 1} for slot {@code i}.
     */
    private final int[] frameOffsets = new int[9];

    /**
     * Creates an empty header.
     */
    public FrameHeader() {
        for (var i = 0; i < 7; i++) {
            gmv[i] = new WarpedMotionParams();
        }
    }

    /**
     * Returns this header to the state of a newly constructed one, so that it can be parsed into
     * again.
     */
    public void reset() {
        filmGrainPresent = false;
        filmGrainUpdate = false;
        filmGrain.copyFrom(NO_FILM_GRAIN);
        frameType = 0;
        codedWidth = 0;
        upscaledWidth = 0;
        height = 0;
        frameOffset = 0;
        temporalId = 0;
        spatialId = 0;
        showExistingFrame = false;
        existingFrameIdx = 0;
        framePresentationTime = 0;
        bufferRemovalTimePresent = false;
        Arrays.fill(bufferRemovalTime, 0);
        frameId = 0;
        showFrame = false;
        showableFrame = false;
        errorResilientMode = false;
        disableCdfUpdate = false;
        allowScreenContentTools = false;
        forceIntegerMv = false;
        frameSizeOverride = false;
        primaryRefFrame = 0;
        refreshFrameFlags = 0;
        renderWidth = 0;
        renderHeight = 0;
        superResDenominator = 0;
        superResEnabled = false;
        allowIntrabc = false;
        frameRefShortSignaling = false;
        Arrays.fill(refidx, 0);
        hp = false;
        subpelFilterMode = 0;
        switchableMotionMode = false;
        useRefFrameMvs = false;
        refreshContext = false;
        tilingUniform = false;
        tilingNBytes = 0;
        tilingMinLog2Cols = 0;
        tilingMaxLog2Cols = 0;
        tilingLog2Cols = 0;
        tilingCols = 0;
        tilingMinLog2Rows = 0;
        tilingMaxLog2Rows = 0;
        tilingLog2Rows = 0;
        tilingRows = 0;
        Arrays.fill(colStartSb, 0);
        Arrays.fill(rowStartSb, 0);
        tilingUpdate = 0;
        quantYac = 0;
        quantYdcDelta = 0;
        quantUdcDelta = 0;
        quantUacDelta = 0;
        quantVdcDelta = 0;
        quantVacDelta = 0;
        quantQm = false;
        quantQmY = 0;
        quantQmU = 0;
        quantQmV = 0;
        segmentationEnabled = false;
        segmentationUpdateMap = false;
        segmentationTemporal = false;
        segmentationUpdateData = false;
        Arrays.fill(segDeltaQ, 0);
        Arrays.fill(segDeltaLfYV, 0);
        Arrays.fill(segDeltaLfYH, 0);
        Arrays.fill(segDeltaLfU, 0);
        Arrays.fill(segDeltaLfV, 0);
        Arrays.fill(segRef, 0);
        Arrays.fill(segSkip, false);
        Arrays.fill(segGlobalmv, false);
        segPreskip = false;
        segLastActiveSegid = 0;
        Arrays.fill(segLossless, false);
        Arrays.fill(segQidx, 0);
        deltaQPresent = false;
        deltaQResLog2 = 0;
        deltaLfPresent = false;
        deltaLfResLog2 = 0;
        deltaLfMulti = false;
        allLossless = false;
        Arrays.fill(loopfilterLevelY, 0);
        loopfilterLevelU = 0;
        loopfilterLevelV = 0;
        modeRefDeltaEnabled = false;
        modeRefDeltaUpdate = false;
        Arrays.fill(modeDelta, 0);
        Arrays.fill(refDelta, 0);
        sharpness = 0;
        cdefDamping = 0;
        cdefNBits = 0;
        Arrays.fill(cdefYStrength, 0);
        Arrays.fill(cdefUvStrength, 0);
        Arrays.fill(restorationType, 0);
        Arrays.fill(restorationUnitSize, 0);
        txfmMode = 0;
        switchableCompRefs = false;
        skipModeAllowed = false;
        skipModeEnabled = false;
        Arrays.fill(skipModeRefs, 0);
        warpMotion = false;
        reducedTxtpSet = false;
        for (var g : gmv) {
            g.setDefault();
        }
        Arrays.fill(frameOffsets, 0);
    }

    /**
     * Returns whether the frame is a key or intra-only frame.
     *
     * @return {@code true} for intra frames
     */
    public boolean isKeyOrIntra() {
        return (frameType & 1) == 0;
    }

    /**
     * Returns whether the frame is an inter or switch frame.
     *
     * @return {@code true} for inter frames
     */
    public boolean isInterOrSwitch() {
        return (frameType & 1) != 0;
    }

    /**
     * Returns the signed distance between two order hints (AV1 specification section 7.12.3,
     * {@code get_relative_dist}).
     *
     * @param orderHintNBits the bit length of order hints, 0 when order hints are absent
     * @param poc0 the first order hint
     * @param poc1 the second order hint
     * @return {@code poc0 - poc1} wrapped to the order hint range
     */
    public static int pocDiff(int orderHintNBits, int poc0, int poc1) {
        if (orderHintNBits == 0) {
            return 0;
        }
        var mask = 1 << (orderHintNBits - 1);
        var diff = poc0 - poc1;
        return (diff & (mask - 1)) - (diff & mask);
    }

    /**
     * Returns the smallest {@code k} such that {@code sz << k >= tgt} ({@code tile_log2}, AV1
     * specification section 5.9.15).
     *
     * @param sz the block size, in superblocks, positive
     * @param tgt the size to reach, in superblocks
     * @return the base-2 logarithm, 0 when {@code sz} already reaches {@code tgt}
     */
    public static int tileLog2(int sz, int tgt) {
        var k = 0;
        while ((sz << k) < tgt) {
            k++;
        }
        return k;
    }

    /**
     * Parses the frame size, super-resolution and render size fields.
     *
     * @param seq the sequence header
     * @param gb the reader
     * @param useRef whether the size may be copied from a reference
     * @param refs the frame header of each reference slot, or {@code null} for empty slots
     * @return {@code false} when a referenced slot is empty
     */
    private boolean readFrameSize(SequenceHeader seq, BitReader gb, boolean useRef, FrameHeader[] refs) {
        if (useRef) {
            for (var i = 0; i < 7; i++) {
                if (gb.bit() != 0) {
                    var ref = refs[refidx[i]];
                    if (ref == null) {
                        return false;
                    }
                    upscaledWidth = ref.upscaledWidth;
                    height = ref.height;
                    renderWidth = ref.renderWidth;
                    renderHeight = ref.renderHeight;
                    readSuperRes(seq, gb);
                    return true;
                }
            }
        }
        if (frameSizeOverride) {
            upscaledWidth = gb.bits(seq.widthNBits) + 1;
            height = gb.bits(seq.heightNBits) + 1;
        } else {
            upscaledWidth = seq.maxWidth;
            height = seq.maxHeight;
        }
        readSuperRes(seq, gb);
        var haveRenderSize = gb.bit() != 0;
        if (haveRenderSize) {
            renderWidth = gb.bits(16) + 1;
            renderHeight = gb.bits(16) + 1;
        } else {
            renderWidth = upscaledWidth;
            renderHeight = height;
        }
        return true;
    }

    /**
     * Parses the super-resolution parameters and derives the coded width.
     *
     * @param seq the sequence header
     * @param gb the reader
     */
    private void readSuperRes(SequenceHeader seq, BitReader gb) {
        superResEnabled = seq.superRes && gb.bit() != 0;
        if (superResEnabled) {
            var d = superResDenominator = 9 + gb.bits(3);
            codedWidth = Math.max((upscaledWidth * 8 + (d >> 1)) / d, Math.min(16, upscaledWidth));
        } else {
            superResDenominator = 8;
            codedWidth = upscaledWidth;
        }
    }

    /**
     * Parses a frame header.
     *
     * @param seq the active sequence header
     * @param gb the reader, positioned at the start of the header
     * @param refs the frame header of each reference slot, or {@code null} for empty slots
     * @param strict whether to reject streams violating constraints that do not affect decoding
     * @return {@code true} when the header is valid
     */
    public boolean parse(SequenceHeader seq, BitReader gb, FrameHeader[] refs, boolean strict) {
        if (!seq.reducedStillPictureHeader) {
            showExistingFrame = gb.bit() != 0;
        }
        if (showExistingFrame) {
            existingFrameIdx = gb.bits(3);
            if (seq.decoderModelInfoPresent && !seq.equalPictureInterval) {
                framePresentationTime = gb.bits(seq.framePresentationDelayLength) & 0xFFFFFFFFL;
            }
            if (seq.frameIdNumbersPresent) {
                frameId = gb.bits(seq.frameIdNBits);
                var ref = refs[existingFrameIdx];
                return ref != null && ref.frameId == frameId;
            }
            return true;
        }
        if (seq.reducedStillPictureHeader) {
            frameType = KEY;
            showFrame = true;
        } else {
            frameType = gb.bits(2);
            showFrame = gb.bit() != 0;
        }
        if (showFrame) {
            if (seq.decoderModelInfoPresent && !seq.equalPictureInterval) {
                framePresentationTime = gb.bits(seq.framePresentationDelayLength) & 0xFFFFFFFFL;
            }
            showableFrame = frameType != KEY;
        } else {
            showableFrame = gb.bit() != 0;
        }
        errorResilientMode = (frameType == KEY && showFrame) || frameType == SWITCH
                || seq.reducedStillPictureHeader || gb.bit() != 0;
        disableCdfUpdate = gb.bit() != 0;
        allowScreenContentTools = (seq.screenContentTools == SequenceHeader.ADAPTIVE
                ? gb.bit() : seq.screenContentTools) != 0;
        if (allowScreenContentTools) {
            forceIntegerMv = (seq.forceIntegerMv == SequenceHeader.ADAPTIVE ? gb.bit() : seq.forceIntegerMv) != 0;
        }
        if (isKeyOrIntra()) {
            forceIntegerMv = true;
        }
        if (seq.frameIdNumbersPresent) {
            frameId = gb.bits(seq.frameIdNBits);
        }
        if (!seq.reducedStillPictureHeader) {
            frameSizeOverride = frameType == SWITCH || gb.bit() != 0;
        }
        if (seq.orderHint) {
            frameOffset = gb.bits(seq.orderHintNBits);
        }
        primaryRefFrame = !errorResilientMode && isInterOrSwitch() ? gb.bits(3) : PRIMARY_REF_NONE;
        if (seq.decoderModelInfoPresent) {
            bufferRemovalTimePresent = gb.bit() != 0;
            if (bufferRemovalTimePresent) {
                for (var i = 0; i < seq.numOperatingPoints; i++) {
                    if (seq.decoderModelParamPresent[i]) {
                        var idc = seq.operatingPointIdc[i];
                        var inTemporalLayer = ((idc >> temporalId) & 1) != 0;
                        var inSpatialLayer = ((idc >> (spatialId + 8)) & 1) != 0;
                        if (idc == 0 || (inTemporalLayer && inSpatialLayer)) {
                            bufferRemovalTime[i] = gb.bits(seq.bufferRemovalDelayLength) & 0xFFFFFFFFL;
                        }
                    }
                }
            }
        }
        if (isKeyOrIntra()) {
            refreshFrameFlags = (frameType == KEY && showFrame) ? 0xff : gb.bits(8);
            if (refreshFrameFlags != 0xff && errorResilientMode && seq.orderHint) {
                for (var i = 0; i < 8; i++) {
                    gb.bits(seq.orderHintNBits);
                }
            }
            if (strict && frameType == INTRA && refreshFrameFlags == 0xff) {
                return false;
            }
            if (!readFrameSize(seq, gb, false, refs)) {
                return false;
            }
            if (allowScreenContentTools && !superResEnabled) {
                allowIntrabc = gb.bit() != 0;
            }
        } else {
            refreshFrameFlags = frameType == SWITCH ? 0xff : gb.bits(8);
            if (errorResilientMode && seq.orderHint) {
                for (var i = 0; i < 8; i++) {
                    gb.bits(seq.orderHintNBits);
                }
            }
            if (seq.orderHint) {
                frameRefShortSignaling = gb.bit() != 0;
                if (frameRefShortSignaling && !setFrameRefs(seq, gb, refs)) {
                    return false;
                }
            }
            for (var i = 0; i < 7; i++) {
                if (!frameRefShortSignaling) {
                    refidx[i] = gb.bits(3);
                }
                if (seq.frameIdNumbersPresent) {
                    var deltaRefFrameId = gb.bits(seq.deltaFrameIdNBits) + 1;
                    var refFrameId = (frameId + (1 << seq.frameIdNBits) - deltaRefFrameId)
                            & ((1 << seq.frameIdNBits) - 1);
                    var ref = refs[refidx[i]];
                    if (ref == null || ref.frameId != refFrameId) {
                        return false;
                    }
                }
            }
            var useRef = !errorResilientMode && frameSizeOverride;
            if (!readFrameSize(seq, gb, useRef, refs)) {
                return false;
            }
            if (!forceIntegerMv) {
                hp = gb.bit() != 0;
            }
            subpelFilterMode = gb.bit() != 0 ? FILTER_SWITCHABLE : gb.bits(2);
            switchableMotionMode = gb.bit() != 0;
            if (!errorResilientMode && seq.refFrameMvs && seq.orderHint && isInterOrSwitch()) {
                useRefFrameMvs = gb.bit() != 0;
            }
        }
        if (!seq.reducedStillPictureHeader && !disableCdfUpdate) {
            refreshContext = gb.bit() == 0;
        }
        if (!parseTiling(seq, gb)) {
            return false;
        }
        parseQuant(seq, gb);
        if (!parseSegmentation(gb, refs)) {
            return false;
        }
        if (quantYac != 0) {
            deltaQPresent = gb.bit() != 0;
            if (deltaQPresent) {
                deltaQResLog2 = gb.bits(2);
                if (!allowIntrabc) {
                    deltaLfPresent = gb.bit() != 0;
                    if (deltaLfPresent) {
                        deltaLfResLog2 = gb.bits(2);
                        deltaLfMulti = gb.bit() != 0;
                    }
                }
            }
        }
        var deltaLossless = quantYdcDelta == 0 && quantUdcDelta == 0 && quantUacDelta == 0
                && quantVdcDelta == 0 && quantVacDelta == 0;
        allLossless = true;
        for (var i = 0; i < 8; i++) {
            segQidx[i] = segmentationEnabled ? Math.clamp(quantYac + segDeltaQ[i], 0, 255) : quantYac;
            segLossless[i] = segQidx[i] == 0 && deltaLossless;
            allLossless &= segLossless[i];
        }
        if (!parseLoopFilter(seq, gb, refs)) {
            return false;
        }
        if (!allLossless && seq.cdef && !allowIntrabc) {
            cdefDamping = gb.bits(2) + 3;
            cdefNBits = gb.bits(2);
            for (var i = 0; i < (1 << cdefNBits); i++) {
                cdefYStrength[i] = gb.bits(6);
                if (!seq.monochrome) {
                    cdefUvStrength[i] = gb.bits(6);
                }
            }
        }
        if ((!allLossless || superResEnabled) && seq.restoration && !allowIntrabc) {
            restorationType[0] = gb.bits(2);
            if (!seq.monochrome) {
                restorationType[1] = gb.bits(2);
                restorationType[2] = gb.bits(2);
            }
            if (restorationType[0] != 0 || restorationType[1] != 0 || restorationType[2] != 0) {
                restorationUnitSize[0] = 6 + (seq.sb128 ? 1 : 0);
                if (gb.bit() != 0) {
                    restorationUnitSize[0]++;
                    if (!seq.sb128) {
                        restorationUnitSize[0] += gb.bit();
                    }
                }
                restorationUnitSize[1] = restorationUnitSize[0];
                if ((restorationType[1] != 0 || restorationType[2] != 0) && seq.ssHor == 1 && seq.ssVer == 1) {
                    restorationUnitSize[1] -= gb.bit();
                }
            } else {
                restorationUnitSize[0] = 8;
            }
        }
        txfmMode = allLossless ? TX_4X4_ONLY : gb.bit() != 0 ? TX_SWITCHABLE : TX_LARGEST;
        if (isInterOrSwitch()) {
            switchableCompRefs = gb.bit() != 0;
        }
        if (switchableCompRefs && isInterOrSwitch() && seq.orderHint && !deriveSkipModeRefs(seq, refs)) {
            return false;
        }
        if (skipModeAllowed) {
            skipModeEnabled = gb.bit() != 0;
        }
        if (!errorResilientMode && isInterOrSwitch() && seq.warpedMotion) {
            warpMotion = gb.bit() != 0;
        }
        reducedTxtpSet = gb.bit() != 0;
        for (var i = 0; i < 7; i++) {
            gmv[i].setDefault();
        }
        if (isInterOrSwitch() && !parseGlobalMotion(gb, refs)) {
            return false;
        }
        return !seq.filmGrainPresent || (!showFrame && !showableFrame) || parseFilmGrain(seq, gb, refs);
    }

    /**
     * Derives the references from the two coded ones ({@code set_frame_refs}, AV1 specification
     * section 7.8).
     *
     * @param seq the sequence header
     * @param gb the reader
     * @param refs the frame header of each reference slot
     * @return {@code false} when a reference slot is empty
     */
    private boolean setFrameRefs(SequenceHeader seq, BitReader gb, FrameHeader[] refs) {
        refidx[0] = gb.bits(3);
        refidx[1] = refidx[2] = -1;
        refidx[3] = gb.bits(3);
        var earliestRef = -1;
        var earliestOffset = Integer.MAX_VALUE;
        for (var i = 0; i < 8; i++) {
            var refhdr = refs[i];
            if (refhdr == null) {
                return false;
            }
            var diff = pocDiff(seq.orderHintNBits, refhdr.frameOffset, frameOffset);
            frameOffsets[i + 1] = diff;
            if (diff < earliestOffset) {
                earliestOffset = diff;
                earliestRef = i;
            }
        }
        frameOffsets[refidx[0] + 1] = Integer.MIN_VALUE;
        frameOffsets[refidx[3] + 1] = Integer.MIN_VALUE;
        var ref = -1;
        for (int i = 0, latest = 0; i < 8; i++) {
            var hint = frameOffsets[i + 1];
            if (hint >= latest) {
                latest = hint;
                ref = i;
            }
        }
        frameOffsets[ref + 1] = Integer.MIN_VALUE;
        refidx[6] = ref;
        for (var i = 4; i < 6; i++) {
            var earliest = 0xFFL;
            ref = -1;
            for (var j = 0; j < 8; j++) {
                var hint = frameOffsets[j + 1] & 0xFFFFFFFFL;
                if (hint < earliest) {
                    earliest = hint;
                    ref = j;
                }
            }
            frameOffsets[ref + 1] = Integer.MIN_VALUE;
            refidx[i] = ref;
        }
        for (var i = 1; i < 7; i++) {
            ref = refidx[i];
            if (ref < 0) {
                var latest = ~0xFFL & 0xFFFFFFFFL;
                for (var j = 0; j < 8; j++) {
                    var hint = frameOffsets[j + 1] & 0xFFFFFFFFL;
                    if (hint >= latest) {
                        latest = hint;
                        ref = j;
                    }
                }
                frameOffsets[ref + 1] = Integer.MIN_VALUE;
                refidx[i] = ref >= 0 ? ref : earliestRef;
            }
        }
        return true;
    }

    /**
     * Parses the tile layout ({@code tile_info()}).
     *
     * @param seq the sequence header
     * @param gb the reader
     * @return {@code false} when the context update tile is out of range
     */
    private boolean parseTiling(SequenceHeader seq, BitReader gb) {
        tilingUniform = gb.bit() != 0;
        var sbszMin1 = (64 << (seq.sb128 ? 1 : 0)) - 1;
        var sbszLog2 = 6 + (seq.sb128 ? 1 : 0);
        var sbw = (codedWidth + sbszMin1) >> sbszLog2;
        var sbh = (height + sbszMin1) >> sbszLog2;
        var maxTileWidthSb = 4096 >> sbszLog2;
        var maxTileAreaSb = 4096 * 2304 >> (2 * sbszLog2);
        tilingMinLog2Cols = tileLog2(maxTileWidthSb, sbw);
        tilingMaxLog2Cols = tileLog2(1, Math.min(sbw, 64));
        tilingMaxLog2Rows = tileLog2(1, Math.min(sbh, 64));
        var minLog2Tiles = Math.max(tileLog2(maxTileAreaSb, sbw * sbh), tilingMinLog2Cols);
        if (tilingUniform) {
            tilingLog2Cols = tilingMinLog2Cols;
            while (tilingLog2Cols < tilingMaxLog2Cols && gb.bit() != 0) {
                tilingLog2Cols++;
            }
            var tileW = 1 + ((sbw - 1) >> tilingLog2Cols);
            tilingCols = 0;
            for (var sbx = 0; sbx < sbw; sbx += tileW, tilingCols++) {
                colStartSb[tilingCols] = sbx;
            }
            tilingMinLog2Rows = Math.max(minLog2Tiles - tilingLog2Cols, 0);
            tilingLog2Rows = tilingMinLog2Rows;
            while (tilingLog2Rows < tilingMaxLog2Rows && gb.bit() != 0) {
                tilingLog2Rows++;
            }
            var tileH = 1 + ((sbh - 1) >> tilingLog2Rows);
            tilingRows = 0;
            for (var sby = 0; sby < sbh; sby += tileH, tilingRows++) {
                rowStartSb[tilingRows] = sby;
            }
        } else {
            tilingCols = 0;
            var widestTile = 0;
            var maxArea = sbw * sbh;
            for (var sbx = 0; sbx < sbw && tilingCols < 64; tilingCols++) {
                var tileWidthSb = Math.min(sbw - sbx, maxTileWidthSb);
                var tileW = tileWidthSb > 1 ? 1 + gb.uniform(tileWidthSb) : 1;
                colStartSb[tilingCols] = sbx;
                sbx += tileW;
                widestTile = Math.max(widestTile, tileW);
            }
            tilingLog2Cols = tileLog2(1, tilingCols);
            if (minLog2Tiles != 0) {
                maxArea >>= minLog2Tiles + 1;
            }
            var maxTileHeightSb = Math.max(maxArea / widestTile, 1);
            tilingRows = 0;
            for (var sby = 0; sby < sbh && tilingRows < 64; tilingRows++) {
                var tileHeightSb = Math.min(sbh - sby, maxTileHeightSb);
                var tileH = tileHeightSb > 1 ? 1 + gb.uniform(tileHeightSb) : 1;
                rowStartSb[tilingRows] = sby;
                sby += tileH;
            }
            tilingLog2Rows = tileLog2(1, tilingRows);
        }
        colStartSb[tilingCols] = sbw;
        rowStartSb[tilingRows] = sbh;
        if (tilingLog2Cols != 0 || tilingLog2Rows != 0) {
            tilingUpdate = gb.bits(tilingLog2Cols + tilingLog2Rows);
            if (tilingUpdate >= tilingCols * tilingRows) {
                return false;
            }
            tilingNBytes = gb.bits(2) + 1;
        }
        return true;
    }

    /**
     * Parses the quantization parameters ({@code quantization_params()}).
     *
     * @param seq the sequence header
     * @param gb the reader
     */
    private void parseQuant(SequenceHeader seq, BitReader gb) {
        quantYac = gb.bits(8);
        if (gb.bit() != 0) {
            quantYdcDelta = gb.sbits(7);
        }
        if (!seq.monochrome) {
            var diffUvDelta = seq.separateUvDeltaQ && gb.bit() != 0;
            if (gb.bit() != 0) {
                quantUdcDelta = gb.sbits(7);
            }
            if (gb.bit() != 0) {
                quantUacDelta = gb.sbits(7);
            }
            if (diffUvDelta) {
                if (gb.bit() != 0) {
                    quantVdcDelta = gb.sbits(7);
                }
                if (gb.bit() != 0) {
                    quantVacDelta = gb.sbits(7);
                }
            } else {
                quantVdcDelta = quantUdcDelta;
                quantVacDelta = quantUacDelta;
            }
        }
        quantQm = gb.bit() != 0;
        if (quantQm) {
            quantQmY = gb.bits(4);
            quantQmU = gb.bits(4);
            quantQmV = seq.separateUvDeltaQ ? gb.bits(4) : quantQmU;
        }
    }

    /**
     * Parses the segmentation parameters ({@code segmentation_params()}).
     *
     * @param gb the reader
     * @param refs the frame header of each reference slot
     * @return {@code false} when the primary reference slot is empty
     */
    private boolean parseSegmentation(BitReader gb, FrameHeader[] refs) {
        segmentationEnabled = gb.bit() != 0;
        if (segmentationEnabled) {
            if (primaryRefFrame == PRIMARY_REF_NONE) {
                segmentationUpdateMap = true;
                segmentationUpdateData = true;
            } else {
                segmentationUpdateMap = gb.bit() != 0;
                if (segmentationUpdateMap) {
                    segmentationTemporal = gb.bit() != 0;
                }
                segmentationUpdateData = gb.bit() != 0;
            }
            if (segmentationUpdateData) {
                segLastActiveSegid = -1;
                for (var i = 0; i < 8; i++) {
                    if (gb.bit() != 0) {
                        segDeltaQ[i] = gb.sbits(9);
                        segLastActiveSegid = i;
                    }
                    if (gb.bit() != 0) {
                        segDeltaLfYV[i] = gb.sbits(7);
                        segLastActiveSegid = i;
                    }
                    if (gb.bit() != 0) {
                        segDeltaLfYH[i] = gb.sbits(7);
                        segLastActiveSegid = i;
                    }
                    if (gb.bit() != 0) {
                        segDeltaLfU[i] = gb.sbits(7);
                        segLastActiveSegid = i;
                    }
                    if (gb.bit() != 0) {
                        segDeltaLfV[i] = gb.sbits(7);
                        segLastActiveSegid = i;
                    }
                    if (gb.bit() != 0) {
                        segRef[i] = gb.bits(3);
                        segLastActiveSegid = i;
                        segPreskip = true;
                    } else {
                        segRef[i] = -1;
                    }
                    segSkip[i] = gb.bit() != 0;
                    if (segSkip[i]) {
                        segLastActiveSegid = i;
                        segPreskip = true;
                    }
                    segGlobalmv[i] = gb.bit() != 0;
                    if (segGlobalmv[i]) {
                        segLastActiveSegid = i;
                        segPreskip = true;
                    }
                }
            } else {
                var ref = refs[refidx[primaryRefFrame]];
                if (ref == null) {
                    return false;
                }
                copySegmentationData(ref);
            }
        } else {
            for (var i = 0; i < 8; i++) {
                segRef[i] = -1;
            }
        }
        return true;
    }

    /**
     * Copies the segment features of another header.
     *
     * @param ref the header to copy from
     */
    private void copySegmentationData(FrameHeader ref) {
        System.arraycopy(ref.segDeltaQ, 0, segDeltaQ, 0, 8);
        System.arraycopy(ref.segDeltaLfYV, 0, segDeltaLfYV, 0, 8);
        System.arraycopy(ref.segDeltaLfYH, 0, segDeltaLfYH, 0, 8);
        System.arraycopy(ref.segDeltaLfU, 0, segDeltaLfU, 0, 8);
        System.arraycopy(ref.segDeltaLfV, 0, segDeltaLfV, 0, 8);
        System.arraycopy(ref.segRef, 0, segRef, 0, 8);
        System.arraycopy(ref.segSkip, 0, segSkip, 0, 8);
        System.arraycopy(ref.segGlobalmv, 0, segGlobalmv, 0, 8);
        segPreskip = ref.segPreskip;
        segLastActiveSegid = ref.segLastActiveSegid;
    }

    /**
     * Parses the loop filter parameters ({@code loop_filter_params()}).
     *
     * @param seq the sequence header
     * @param gb the reader
     * @param refs the frame header of each reference slot
     * @return {@code false} when the primary reference slot is empty
     */
    private boolean parseLoopFilter(SequenceHeader seq, BitReader gb, FrameHeader[] refs) {
        if (allLossless || allowIntrabc) {
            modeRefDeltaEnabled = true;
            modeRefDeltaUpdate = true;
            modeDelta[0] = 0;
            modeDelta[1] = 0;
            System.arraycopy(DEFAULT_REF_DELTAS, 0, refDelta, 0, 8);
            return true;
        }
        loopfilterLevelY[0] = gb.bits(6);
        loopfilterLevelY[1] = gb.bits(6);
        if (!seq.monochrome && (loopfilterLevelY[0] != 0 || loopfilterLevelY[1] != 0)) {
            loopfilterLevelU = gb.bits(6);
            loopfilterLevelV = gb.bits(6);
        }
        sharpness = gb.bits(3);
        if (primaryRefFrame == PRIMARY_REF_NONE) {
            modeDelta[0] = 0;
            modeDelta[1] = 0;
            System.arraycopy(DEFAULT_REF_DELTAS, 0, refDelta, 0, 8);
        } else {
            var ref = refs[refidx[primaryRefFrame]];
            if (ref == null) {
                return false;
            }
            System.arraycopy(ref.modeDelta, 0, modeDelta, 0, 2);
            System.arraycopy(ref.refDelta, 0, refDelta, 0, 8);
        }
        modeRefDeltaEnabled = gb.bit() != 0;
        if (modeRefDeltaEnabled) {
            modeRefDeltaUpdate = gb.bit() != 0;
            if (modeRefDeltaUpdate) {
                for (var i = 0; i < 8; i++) {
                    if (gb.bit() != 0) {
                        refDelta[i] = gb.sbits(7);
                    }
                }
                for (var i = 0; i < 2; i++) {
                    if (gb.bit() != 0) {
                        modeDelta[i] = gb.sbits(7);
                    }
                }
            }
        }
        return true;
    }

    /**
     * Derives whether skip mode is allowed, and its references, from this header's references and
     * order hint, as {@link #parse} does after reading {@code reference_select}; an encoder calls it
     * on a header it builds to learn whether the skip mode flag is coded.
     *
     * @param seq the sequence header
     * @param refs the frame header of each reference slot
     * @return whether skip mode is allowed; {@code false} also when a referenced slot is empty
     */
    public boolean deriveSkipMode(SequenceHeader seq, FrameHeader[] refs) {
        skipModeAllowed = false;
        return deriveSkipModeRefs(seq, refs) && skipModeAllowed;
    }

    /**
     * Derives the skip mode references ({@code skip_mode_params()}).
     *
     * @param seq the sequence header
     * @param refs the frame header of each reference slot
     * @return {@code false} when a reference slot is empty
     */
    private boolean deriveSkipModeRefs(SequenceHeader seq, FrameHeader[] refs) {
        var bits = seq.orderHintNBits;
        var poc = frameOffset;
        int offBefore = -1, offAfter = -1;
        int offBeforeIdx = 0, offAfterIdx = 0;
        for (var i = 0; i < 7; i++) {
            var ref = refs[refidx[i]];
            if (ref == null) {
                return false;
            }
            var refpoc = ref.frameOffset;
            var diff = pocDiff(bits, refpoc, poc);
            if (diff > 0) {
                if (offAfter < 0 || pocDiff(bits, offAfter, refpoc) > 0) {
                    offAfter = refpoc;
                    offAfterIdx = i;
                }
            } else if (diff < 0 && (offBefore < 0 || pocDiff(bits, refpoc, offBefore) > 0)) {
                offBefore = refpoc;
                offBeforeIdx = i;
            }
        }
        if ((offBefore | offAfter) >= 0) {
            skipModeRefs[0] = Math.min(offBeforeIdx, offAfterIdx);
            skipModeRefs[1] = Math.max(offBeforeIdx, offAfterIdx);
            skipModeAllowed = true;
        } else if (offBefore >= 0) {
            var offBefore2 = -1;
            var offBefore2Idx = 0;
            for (var i = 0; i < 7; i++) {
                var ref = refs[refidx[i]];
                if (ref == null) {
                    return false;
                }
                var refpoc = ref.frameOffset;
                if (pocDiff(bits, refpoc, offBefore) < 0
                        && (offBefore2 < 0 || pocDiff(bits, refpoc, offBefore2) > 0)) {
                    offBefore2 = refpoc;
                    offBefore2Idx = i;
                }
            }
            if (offBefore2 >= 0) {
                skipModeRefs[0] = Math.min(offBeforeIdx, offBefore2Idx);
                skipModeRefs[1] = Math.max(offBeforeIdx, offBefore2Idx);
                skipModeAllowed = true;
            }
        }
        return true;
    }

    /**
     * Parses the global motion parameters ({@code global_motion_params()}).
     *
     * @param gb the reader
     * @param refs the frame header of each reference slot
     * @return {@code false} when the primary reference slot is empty
     */
    private boolean parseGlobalMotion(BitReader gb, FrameHeader[] refs) {
        for (var i = 0; i < 7; i++) {
            var g = gmv[i];
            g.type = gb.bit() == 0 ? WarpedMotionParams.IDENTITY
                    : gb.bit() != 0 ? WarpedMotionParams.ROT_ZOOM
                    : gb.bit() != 0 ? WarpedMotionParams.TRANSLATION
                    : WarpedMotionParams.AFFINE;
            if (g.type == WarpedMotionParams.IDENTITY) {
                continue;
            }
            WarpedMotionParams refGmv;
            if (primaryRefFrame == PRIMARY_REF_NONE) {
                refGmv = IDENTITY_GMV;
            } else {
                var ref = refs[refidx[primaryRefFrame]];
                if (ref == null) {
                    return false;
                }
                refGmv = ref.gmv[i];
            }
            var mat = g.matrix;
            var refMat = refGmv.matrix;
            int bits, shift;
            if (g.type >= WarpedMotionParams.ROT_ZOOM) {
                mat[2] = (1 << 16) + 2 * gb.subexp((refMat[2] - (1 << 16)) >> 1, 12);
                mat[3] = 2 * gb.subexp(refMat[3] >> 1, 12);
                bits = 12;
                shift = 10;
            } else {
                bits = 9 - (hp ? 0 : 1);
                shift = 13 + (hp ? 0 : 1);
            }
            if (g.type == WarpedMotionParams.AFFINE) {
                mat[4] = 2 * gb.subexp(refMat[4] >> 1, 12);
                mat[5] = (1 << 16) + 2 * gb.subexp((refMat[5] - (1 << 16)) >> 1, 12);
            } else {
                mat[4] = -mat[3];
                mat[5] = mat[2];
            }
            mat[0] = gb.subexp(refMat[0] >> shift, bits) * (1 << shift);
            mat[1] = gb.subexp(refMat[1] >> shift, bits) * (1 << shift);
        }
        return true;
    }

    /**
     * Parses the film grain parameters ({@code film_grain_params()}).
     *
     * @param seq the sequence header
     * @param gb the reader
     * @param refs the frame header of each reference slot
     * @return {@code false} when the parameters are invalid or reference an empty slot
     */
    private boolean parseFilmGrain(SequenceHeader seq, BitReader gb, FrameHeader[] refs) {
        filmGrainPresent = gb.bit() != 0;
        if (!filmGrainPresent) {
            return true;
        }
        var seed = gb.bits(16);
        filmGrainUpdate = frameType != INTER || gb.bit() != 0;
        if (!filmGrainUpdate) {
            var ref = gb.bits(3);
            var i = 0;
            while (i < 7 && refidx[i] != ref) {
                i++;
            }
            if (i == 7 || refs[ref] == null) {
                return false;
            }
            filmGrain.copyFrom(refs[ref].filmGrain);
            filmGrain.seed = seed;
            return true;
        }
        var fgd = filmGrain;
        fgd.seed = seed;
        fgd.numYPoints = gb.bits(4);
        if (fgd.numYPoints > 14) {
            return false;
        }
        for (var i = 0; i < fgd.numYPoints; i++) {
            fgd.yPoints[i][0] = gb.bits(8);
            if (i != 0 && fgd.yPoints[i - 1][0] >= fgd.yPoints[i][0]) {
                return false;
            }
            fgd.yPoints[i][1] = gb.bits(8);
        }
        if (!seq.monochrome) {
            fgd.chromaScalingFromLuma = gb.bit() != 0;
        }
        if (seq.monochrome || fgd.chromaScalingFromLuma
                || (seq.ssVer == 1 && seq.ssHor == 1 && fgd.numYPoints == 0)) {
            fgd.numUvPoints[0] = 0;
            fgd.numUvPoints[1] = 0;
        } else {
            for (var pl = 0; pl < 2; pl++) {
                fgd.numUvPoints[pl] = gb.bits(4);
                if (fgd.numUvPoints[pl] > 10) {
                    return false;
                }
                for (var i = 0; i < fgd.numUvPoints[pl]; i++) {
                    fgd.uvPoints[pl][i][0] = gb.bits(8);
                    if (i != 0 && fgd.uvPoints[pl][i - 1][0] >= fgd.uvPoints[pl][i][0]) {
                        return false;
                    }
                    fgd.uvPoints[pl][i][1] = gb.bits(8);
                }
            }
        }
        if (seq.ssHor == 1 && seq.ssVer == 1 && (fgd.numUvPoints[0] != 0) != (fgd.numUvPoints[1] != 0)) {
            return false;
        }
        fgd.scalingShift = gb.bits(2) + 8;
        fgd.arCoeffLag = gb.bits(2);
        var numYPos = 2 * fgd.arCoeffLag * (fgd.arCoeffLag + 1);
        if (fgd.numYPoints != 0) {
            for (var i = 0; i < numYPos; i++) {
                fgd.arCoeffsY[i] = gb.bits(8) - 128;
            }
        }
        for (var pl = 0; pl < 2; pl++) {
            if (fgd.numUvPoints[pl] != 0 || fgd.chromaScalingFromLuma) {
                var numUvPos = numYPos + (fgd.numYPoints != 0 ? 1 : 0);
                for (var i = 0; i < numUvPos; i++) {
                    fgd.arCoeffsUv[pl][i] = gb.bits(8) - 128;
                }
                if (fgd.numYPoints == 0) {
                    fgd.arCoeffsUv[pl][numUvPos] = 0;
                }
            }
        }
        fgd.arCoeffShift = gb.bits(2) + 6;
        fgd.grainScaleShift = gb.bits(2);
        for (var pl = 0; pl < 2; pl++) {
            if (fgd.numUvPoints[pl] != 0) {
                fgd.uvMult[pl] = gb.bits(8) - 128;
                fgd.uvLumaMult[pl] = gb.bits(8) - 128;
                fgd.uvOffset[pl] = gb.bits(9) - 256;
            }
        }
        fgd.overlapFlag = gb.bit() != 0;
        fgd.clipToRestrictedRange = gb.bit() != 0;
        return true;
    }
}
