/* Ports Shaka Packager v3.9.3 (packager/media/codecs/av1_parser.cc, the OBU, sequence header, uncompressed frame
 * header and tile group syntax read to find the tile data of each frame, with the reference frame state it needs).
 * Copyright 2018 Google LLC; BSD 3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.internal.cenc;

import com.github.auties00.smalt.internal.bits.MsbBitReader;

import java.util.Arrays;

/**
 * Finds the tile data of AV1 samples, which Common Encryption protects while every other byte stays clear (AV1
 * Codec ISO Media File Format Binding, section 2.5): the OBU headers, sequence headers, frame headers, tile group
 * headers, tile sizes and the OBUs that carry no tiles.
 *
 * <p>A sample is a temporal unit of OBUs that all carry their size field. The frame headers (AV1 specification
 * section 5.9) are read with the sequence header in force and the state of the eight reference frames that the
 * frames read before left (frame sizes, order hints, frame types and segmentation features), so frames that take
 * their size from a reference, short reference signaling, skip mode and lossless segments are read as a decoder
 * reads them. A sample that cannot be read (a damaged OBU, a frame before any sequence header) reports no tiles, and
 * the caller leaves it clear. Instances keep the state of one stream and are not thread-safe.
 *
 * @implNote This implementation follows the specification where the ported parser does not: superres scales the
 * coded frame width (which sets the tile layout) rather than the upscaled one, a frame without new segmentation data
 * takes the features of its primary reference frame, the reference order hints of an error resilient frame replace
 * the stored ones, and a redundant frame header is skipped.
 */
public final class Av1Tiles {
    private static final int OBU_SEQUENCE_HEADER = 1;
    private static final int OBU_TEMPORAL_DELIMITER = 2;
    private static final int OBU_FRAME_HEADER = 3;
    private static final int OBU_TILE_GROUP = 4;
    private static final int OBU_FRAME = 6;
    private static final int OBU_REDUNDANT_FRAME_HEADER = 7;

    private static final int KEY_FRAME = 0;
    private static final int INTER_FRAME = 1;
    private static final int INTRA_ONLY_FRAME = 2;
    private static final int SWITCH_FRAME = 3;

    private static final int SELECT_SCREEN_CONTENT_TOOLS = 2;
    private static final int SELECT_INTEGER_MV = 2;
    private static final int PRIMARY_REF_NONE = 7;
    private static final int REFS_PER_FRAME = 7;
    private static final int ALL_FRAMES = 0xFF;
    private static final int MAX_SEGMENTS = 8;
    private static final int SEG_LVL_MAX = 8;
    private static final int[] SEGMENTATION_FEATURE_BITS = {8, 6, 6, 6, 6, 3, 0, 0};
    private static final boolean[] SEGMENTATION_FEATURE_SIGNED = {true, true, true, true, true, false, false, false};
    private static final int[] SEGMENTATION_FEATURE_MAX = {255, 63, 63, 63, 63, 7, 0, 0};

    /**
     * The references {@code set_frame_refs()} gives the latest forward frames, in its order: {@code LAST2_FRAME},
     * {@code LAST3_FRAME}, {@code BWDREF_FRAME}, {@code ALTREF2_FRAME} and {@code ALTREF_FRAME}, as indexes of
     * {@code ref_frame_idx}.
     */
    private static final int[] FORWARD_REFERENCES = {1, 2, 4, 5, 6};

    /**
     * The fields of the sequence header in force that frame headers depend on.
     */
    private static final class Sequence {
        boolean reducedStillPicture;
        boolean equalPictureInterval;
        boolean decoderModelInfo;
        int bufferDelayLength;
        int bufferRemovalTimeLength;
        int framePresentationTimeLength;
        int operatingPoints;
        final int[] operatingPointIdc = new int[32];
        final boolean[] decoderModelForOperatingPoint = new boolean[32];
        int frameWidthBits;
        int frameHeightBits;
        int maxFrameWidth;
        int maxFrameHeight;
        boolean frameIdNumbers;
        int deltaFrameIdLength;
        int additionalFrameIdLength;
        boolean superblock128;
        boolean warpedMotion;
        boolean orderHint;
        boolean refFrameMvs;
        int orderHintBits;
        int forceScreenContentTools;
        int forceIntegerMv;
        boolean superres;
        boolean cdef;
        boolean restoration;
        int planes;
        boolean subsamplingX;
        boolean subsamplingY;
        boolean separateUvDeltaQ;
        boolean filmGrain;
    }

    /**
     * The state of a reference frame slot.
     */
    private static final class Reference {
        boolean valid;
        int frameType;
        int orderHint;
        int frameWidth;
        int frameHeight;
        int upscaledWidth;
        final boolean[][] featureEnabled = new boolean[MAX_SEGMENTS][SEG_LVL_MAX];
        final int[][] featureData = new int[MAX_SEGMENTS][SEG_LVL_MAX];
    }

    private final MsbBitReader reader = new MsbBitReader(new byte[0]);
    private final Reference[] references = new Reference[8];
    private Sequence sequence;

    private boolean seenFrameHeader;
    private boolean showExistingFrame;
    private int frameToShow;
    private int frameType;
    private int refreshFrameFlags;
    private final int[] refFrameIdx = new int[REFS_PER_FRAME];
    private int orderHint;
    private int frameWidth;
    private int frameHeight;
    private int upscaledWidth;
    private int miCols;
    private int miRows;
    private int tileCols;
    private int tileRows;
    private int tileColsLog2;
    private int tileRowsLog2;
    private int tileSizeBytes;
    private int baseQIdx;
    private boolean deltasZero;
    private boolean segmentationEnabled;
    private final boolean[][] featureEnabled = new boolean[MAX_SEGMENTS][SEG_LVL_MAX];
    private final int[][] featureData = new int[MAX_SEGMENTS][SEG_LVL_MAX];
    private int temporalId;
    private int spatialId;

    private final boolean[] usedSlots = new boolean[8];
    private final int[] shiftedHints = new int[8];
    private int[] tiles = new int[32];
    private int tileCount;

    /**
     * Constructs a parser for one stream, with no sequence header and no reference frame known.
     */
    public Av1Tiles() {
        for (var index = 0; index < references.length; index++) {
            references[index] = new Reference();
        }
    }

    /**
     * Reads the sequence header among the configuration OBUs of an {@code av1C} record, the bytes after its first
     * four; a record without one, or one that does not parse, changes nothing.
     *
     * @param record the {@code av1C} record, or {@code null}
     */
    public void configure(byte[] record) {
        if (record == null || record.length <= 4) {
            return;
        }
        var saved = sequence;
        if (find(record, 4, record.length - 4) == null) {
            sequence = saved;
        }
    }

    /**
     * Finds the tiles of a sample.
     *
     * <p>The result holds the tile count {@code n}, then for each tile, in sample order, the offset of its first
     * byte from the start of the sample and its size:
     * {@snippet :
     * [n, start0, size0, start1, size1, ...]
     * }
     *
     * @param data the array holding the sample
     * @param offset the index of its first byte
     * @param length its size in bytes
     * @return the tiles, in an array this parser reuses on the next call, or {@code null} when the sample cannot be
     * read
     */
    public int[] find(byte[] data, int offset, int length) {
        tileCount = 0;
        try {
            reader.reset(data, offset, length);
            while (reader.bitsLeft() > 0) {
                if (!obu(length)) {
                    seenFrameHeader = false;
                    return null;
                }
            }
        } catch (RuntimeException failure) {
            seenFrameHeader = false;
            return null;
        }
        if (tiles.length < 1 + 2 * tileCount) {
            tiles = Arrays.copyOf(tiles, 1 + 2 * tileCount);
        }
        tiles[0] = tileCount;
        return tiles;
    }

    /**
     * Reads one OBU (section 5.3.1) and returns whether it parsed.
     */
    private boolean obu(int length) {
        if (reader.readFlag()) {
            return false;
        }
        var type = reader.read(4);
        var extension = reader.readFlag();
        var hasSize = reader.readFlag();
        reader.skip(1);
        temporalId = 0;
        spatialId = 0;
        if (extension) {
            temporalId = reader.read(3);
            spatialId = reader.read(2);
            reader.skip(3);
        }
        long size;
        if (hasSize) {
            size = leb128();
        } else {
            size = reader.bitsLeft() / 8;
        }
        var start = reader.bitPosition();
        if (size < 0 || start / 8 + size > length) {
            return false;
        }
        var end = start + 8 * size;
        switch (type) {
            case OBU_SEQUENCE_HEADER -> {
                var parsed = sequenceHeader();
                if (parsed == null || reader.bitPosition() > end) {
                    return false;
                }
                sequence = parsed;
            }
            case OBU_TEMPORAL_DELIMITER -> seenFrameHeader = false;
            case OBU_FRAME_HEADER, OBU_REDUNDANT_FRAME_HEADER -> {
                if (seenFrameHeader) {
                    break;
                }
                if (!frameHeader() || reader.bitPosition() > end) {
                    return false;
                }
            }
            case OBU_FRAME -> {
                if (seenFrameHeader || !frameHeader() || showExistingFrame) {
                    return false;
                }
                byteAlignment();
                if (reader.bitPosition() > end || !tileGroup(end)) {
                    return false;
                }
            }
            case OBU_TILE_GROUP -> {
                if (!seenFrameHeader || !tileGroup(end)) {
                    return false;
                }
            }
            default -> {
            }
        }
        if (reader.overrun()) {
            return false;
        }
        reader.seek(end);
        return true;
    }

    private long leb128() {
        long value = 0;
        for (var index = 0; index < 8; index++) {
            var b = reader.read(8);
            value |= (long) (b & 0x7F) << (7 * index);
            if ((b & 0x80) == 0) {
                break;
            }
        }
        return value > 0xFFFFFFFFL ? -1 : value;
    }

    private void byteAlignment() {
        var misaligned = (int) (reader.bitPosition() & 7);
        if (misaligned != 0) {
            reader.skip(8 - misaligned);
        }
    }

    /**
     * Reads {@code uvlc()} (section 4.10.3).
     */
    private void skipUvlc() {
        var zeros = 0;
        while (!reader.readFlag()) {
            if (++zeros >= 32 || reader.overrun()) {
                return;
            }
        }
        reader.skip(zeros);
    }

    /**
     * Reads {@code ns(n)} (section 4.10.7).
     */
    private int ns(int n) {
        var w = 32 - Integer.numberOfLeadingZeros(n);
        var m = (1 << w) - n;
        var value = reader.read(w - 1);
        if (value < m) {
            return value;
        }
        return (value << 1) - m + reader.readBit();
    }

    private int su(int bits) {
        return reader.readSigned(bits);
    }

    private static int tileLog2(int blockSize, int target) {
        var k = 0;
        while (blockSize << k < target) {
            k++;
        }
        return k;
    }

    /**
     * Reads a sequence header OBU (section 5.5).
     */
    private Sequence sequenceHeader() {
        var s = new Sequence();
        var profile = reader.read(3);
        reader.skip(1);
        s.reducedStillPicture = reader.readFlag();
        if (s.reducedStillPicture) {
            s.operatingPoints = 1;
            reader.skip(5);
        } else {
            if (reader.readFlag()) {
                reader.skip(32 + 32);
                s.equalPictureInterval = reader.readFlag();
                if (s.equalPictureInterval) {
                    skipUvlc();
                }
                s.decoderModelInfo = reader.readFlag();
                if (s.decoderModelInfo) {
                    s.bufferDelayLength = reader.read(5) + 1;
                    reader.skip(32);
                    s.bufferRemovalTimeLength = reader.read(5) + 1;
                    s.framePresentationTimeLength = reader.read(5) + 1;
                }
            }
            var initialDisplayDelay = reader.readFlag();
            s.operatingPoints = reader.read(5) + 1;
            for (var index = 0; index < s.operatingPoints; index++) {
                s.operatingPointIdc[index] = reader.read(12);
                if (reader.read(5) > 7) {
                    reader.skip(1);
                }
                if (s.decoderModelInfo) {
                    s.decoderModelForOperatingPoint[index] = reader.readFlag();
                    if (s.decoderModelForOperatingPoint[index]) {
                        reader.skip(2L * s.bufferDelayLength + 1);
                    }
                }
                if (initialDisplayDelay && reader.readFlag()) {
                    reader.skip(4);
                }
            }
        }
        s.frameWidthBits = reader.read(4) + 1;
        s.frameHeightBits = reader.read(4) + 1;
        s.maxFrameWidth = reader.read(s.frameWidthBits) + 1;
        s.maxFrameHeight = reader.read(s.frameHeightBits) + 1;
        s.frameIdNumbers = !s.reducedStillPicture && reader.readFlag();
        if (s.frameIdNumbers) {
            s.deltaFrameIdLength = reader.read(4) + 2;
            s.additionalFrameIdLength = reader.read(3) + 1;
        }
        s.superblock128 = reader.readFlag();
        reader.skip(2);
        if (s.reducedStillPicture) {
            s.forceScreenContentTools = SELECT_SCREEN_CONTENT_TOOLS;
            s.forceIntegerMv = SELECT_INTEGER_MV;
        } else {
            reader.skip(2);
            s.warpedMotion = reader.readFlag();
            reader.skip(1);
            s.orderHint = reader.readFlag();
            if (s.orderHint) {
                reader.skip(1);
                s.refFrameMvs = reader.readFlag();
            }
            s.forceScreenContentTools = reader.readFlag() ? SELECT_SCREEN_CONTENT_TOOLS : reader.readBit();
            if (s.forceScreenContentTools > 0) {
                s.forceIntegerMv = reader.readFlag() ? SELECT_INTEGER_MV : reader.readBit();
            } else {
                s.forceIntegerMv = SELECT_INTEGER_MV;
            }
            if (s.orderHint) {
                s.orderHintBits = reader.read(3) + 1;
            }
        }
        s.superres = reader.readFlag();
        s.cdef = reader.readFlag();
        s.restoration = reader.readFlag();
        colorConfig(s, profile);
        s.filmGrain = reader.readFlag();
        return reader.overrun() ? null : s;
    }

    /**
     * Reads {@code color_config()} (section 5.5.2).
     */
    private void colorConfig(Sequence s, int profile) {
        var highBitDepth = reader.readFlag();
        var bitDepth = highBitDepth ? 10 : 8;
        if (profile == 2 && highBitDepth) {
            bitDepth = reader.readFlag() ? 12 : 10;
        }
        var monochrome = profile != 1 && reader.readFlag();
        s.planes = monochrome ? 1 : 3;
        var primaries = 2;
        var transfer = 2;
        var matrix = 2;
        if (reader.readFlag()) {
            primaries = reader.read(8);
            transfer = reader.read(8);
            matrix = reader.read(8);
        }
        if (monochrome) {
            reader.skip(1);
            s.subsamplingX = true;
            s.subsamplingY = true;
            return;
        }
        if (primaries == 1 && transfer == 13 && matrix == 0) {
            s.subsamplingX = false;
            s.subsamplingY = false;
        } else {
            reader.skip(1);
            if (profile == 0) {
                s.subsamplingX = true;
                s.subsamplingY = true;
            } else if (profile == 1) {
                s.subsamplingX = false;
                s.subsamplingY = false;
            } else if (bitDepth == 12) {
                s.subsamplingX = reader.readFlag();
                s.subsamplingY = s.subsamplingX && reader.readFlag();
            } else {
                s.subsamplingX = true;
                s.subsamplingY = false;
            }
            if (s.subsamplingX && s.subsamplingY) {
                reader.skip(2);
            }
        }
        s.separateUvDeltaQ = reader.readFlag();
    }

    /**
     * Reads a frame header OBU (section 5.9.1) that is not a copy, and returns whether it parsed.
     */
    private boolean frameHeader() {
        if (sequence == null) {
            return false;
        }
        seenFrameHeader = true;
        if (!uncompressedHeader()) {
            return false;
        }
        if (showExistingFrame) {
            wrapUp();
            seenFrameHeader = false;
        }
        return true;
    }

    /**
     * Reads {@code uncompressed_header()} (section 5.9.2).
     */
    private boolean uncompressedHeader() {
        var s = sequence;
        var idLength = s.frameIdNumbers ? s.additionalFrameIdLength + s.deltaFrameIdLength : 0;
        boolean intra;
        boolean showFrame;
        boolean showableFrame;
        boolean errorResilient;
        showExistingFrame = false;
        if (s.reducedStillPicture) {
            frameType = KEY_FRAME;
            intra = true;
            showFrame = true;
            showableFrame = false;
            errorResilient = true;
        } else {
            showExistingFrame = reader.readFlag();
            if (showExistingFrame) {
                frameToShow = reader.read(3);
                if (s.decoderModelInfo && !s.equalPictureInterval) {
                    reader.skip(s.framePresentationTimeLength);
                }
                refreshFrameFlags = 0;
                if (s.frameIdNumbers) {
                    reader.skip(idLength);
                }
                var shown = references[frameToShow];
                if (!shown.valid) {
                    return false;
                }
                frameType = shown.frameType;
                if (frameType == KEY_FRAME) {
                    refreshFrameFlags = ALL_FRAMES;
                }
                return true;
            }
            frameType = reader.read(2);
            intra = frameType == INTRA_ONLY_FRAME || frameType == KEY_FRAME;
            showFrame = reader.readFlag();
            if (showFrame && s.decoderModelInfo && !s.equalPictureInterval) {
                reader.skip(s.framePresentationTimeLength);
            }
            showableFrame = showFrame ? frameType != KEY_FRAME : reader.readFlag();
            errorResilient = frameType == SWITCH_FRAME || frameType == KEY_FRAME && showFrame || reader.readFlag();
        }
        if (frameType == KEY_FRAME && showFrame) {
            for (var reference : references) {
                reference.valid = false;
                reference.orderHint = 0;
            }
        }
        var disableCdfUpdate = reader.readFlag();
        var screenContentTools = s.forceScreenContentTools == SELECT_SCREEN_CONTENT_TOOLS ? reader.readFlag()
                : s.forceScreenContentTools != 0;
        var forceIntegerMv = false;
        if (screenContentTools) {
            forceIntegerMv = s.forceIntegerMv == SELECT_INTEGER_MV ? reader.readFlag() : s.forceIntegerMv != 0;
        }
        if (intra) {
            forceIntegerMv = true;
        }
        if (s.frameIdNumbers) {
            reader.skip(idLength);
        }
        var frameSizeOverride = frameType == SWITCH_FRAME || !s.reducedStillPicture && reader.readFlag();
        orderHint = reader.read(s.orderHintBits);
        var primaryRefFrame = intra || errorResilient ? PRIMARY_REF_NONE : reader.read(3);
        if (s.decoderModelInfo && reader.readFlag()) {
            for (var point = 0; point < s.operatingPoints; point++) {
                if (s.decoderModelForOperatingPoint[point]) {
                    var idc = s.operatingPointIdc[point];
                    var inTemporal = (idc >>> temporalId & 1) != 0;
                    var inSpatial = (idc >>> (spatialId + 8) & 1) != 0;
                    if (idc == 0 || inTemporal && inSpatial) {
                        reader.skip(s.bufferRemovalTimeLength);
                    }
                }
            }
        }
        var allowHighPrecisionMv = false;
        var allowIntrabc = false;
        refreshFrameFlags = frameType == SWITCH_FRAME || frameType == KEY_FRAME && showFrame ? ALL_FRAMES
                : reader.read(8);
        if ((!intra || refreshFrameFlags != ALL_FRAMES) && errorResilient && s.orderHint) {
            for (var reference : references) {
                var hint = reader.read(s.orderHintBits);
                if (hint != reference.orderHint) {
                    reference.orderHint = hint;
                    reference.valid = false;
                }
            }
        }
        if (intra) {
            frameSize(frameSizeOverride);
            renderSize();
            if (screenContentTools && upscaledWidth == frameWidth) {
                allowIntrabc = reader.readFlag();
            }
        } else {
            var shortSignaling = s.orderHint && reader.readFlag();
            if (shortSignaling) {
                var last = reader.read(3);
                var gold = reader.read(3);
                if (!setFrameRefs(last, gold)) {
                    return false;
                }
            }
            for (var index = 0; index < REFS_PER_FRAME; index++) {
                if (!shortSignaling) {
                    refFrameIdx[index] = reader.read(3);
                }
                if (s.frameIdNumbers) {
                    reader.skip(s.deltaFrameIdLength);
                }
            }
            if (frameSizeOverride && !errorResilient) {
                if (!frameSizeWithRefs(frameSizeOverride)) {
                    return false;
                }
            } else {
                frameSize(frameSizeOverride);
                renderSize();
            }
            allowHighPrecisionMv = !forceIntegerMv && reader.readFlag();
            if (!reader.readFlag()) {
                reader.skip(2);
            }
            reader.skip(1);
            if (!errorResilient && s.refFrameMvs) {
                reader.skip(1);
            }
        }
        if (!s.reducedStillPicture && !disableCdfUpdate) {
            reader.skip(1);
        }
        if (primaryRefFrame == PRIMARY_REF_NONE) {
            clearFeatures();
        } else {
            var primary = references[refFrameIdx[primaryRefFrame]];
            for (var segment = 0; segment < MAX_SEGMENTS; segment++) {
                System.arraycopy(primary.featureEnabled[segment], 0, featureEnabled[segment], 0, SEG_LVL_MAX);
                System.arraycopy(primary.featureData[segment], 0, featureData[segment], 0, SEG_LVL_MAX);
            }
        }
        tileInfo();
        quantizationParams();
        segmentationParams(primaryRefFrame);
        var deltaQPresent = baseQIdx > 0 && reader.readFlag();
        if (deltaQPresent) {
            reader.skip(2);
            if (!allowIntrabc && reader.readFlag()) {
                reader.skip(3);
            }
        }
        var codedLossless = true;
        for (var segment = 0; segment < MAX_SEGMENTS; segment++) {
            var qIndex = baseQIdx;
            if (segmentationEnabled && featureEnabled[segment][0]) {
                qIndex = Math.clamp(baseQIdx + featureData[segment][0], 0, 255);
            }
            if (qIndex != 0 || !deltasZero) {
                codedLossless = false;
            }
        }
        var allLossless = codedLossless && frameWidth == upscaledWidth;
        loopFilterParams(codedLossless, allowIntrabc);
        cdefParams(codedLossless, allowIntrabc);
        lrParams(allLossless, allowIntrabc);
        if (!codedLossless) {
            reader.skip(1);
        }
        var referenceSelect = !intra && reader.readFlag();
        if (skipModeAllowed(intra, referenceSelect)) {
            reader.skip(1);
        }
        if (!intra && !errorResilient && s.warpedMotion) {
            reader.skip(1);
        }
        reader.skip(1);
        globalMotionParams(intra, allowHighPrecisionMv);
        filmGrainParams(showFrame, showableFrame);
        return !reader.overrun();
    }

    private void clearFeatures() {
        for (var segment = 0; segment < MAX_SEGMENTS; segment++) {
            Arrays.fill(featureEnabled[segment], false);
            Arrays.fill(featureData[segment], 0);
        }
    }

    /**
     * Returns {@code get_relative_dist(a, b)} (section 5.9.3).
     */
    private int relativeDistance(int a, int b) {
        if (!sequence.orderHint) {
            return 0;
        }
        var diff = a - b;
        var m = 1 << (sequence.orderHintBits - 1);
        return (diff & (m - 1)) - (diff & m);
    }

    private void frameSize(boolean override) {
        if (override) {
            frameWidth = reader.read(sequence.frameWidthBits) + 1;
            frameHeight = reader.read(sequence.frameHeightBits) + 1;
        } else {
            frameWidth = sequence.maxFrameWidth;
            frameHeight = sequence.maxFrameHeight;
        }
        superresParams();
        computeImageSize();
    }

    private void renderSize() {
        if (reader.readFlag()) {
            reader.skip(32);
        }
    }

    private boolean frameSizeWithRefs(boolean override) {
        for (var index = 0; index < REFS_PER_FRAME; index++) {
            if (reader.readFlag()) {
                var reference = references[refFrameIdx[index]];
                if (reference.upscaledWidth == 0) {
                    return false;
                }
                upscaledWidth = reference.upscaledWidth;
                frameWidth = upscaledWidth;
                frameHeight = reference.frameHeight;
                superresParams();
                computeImageSize();
                return true;
            }
        }
        frameSize(override);
        renderSize();
        return true;
    }

    /**
     * Reads {@code superres_params()} (section 5.9.8): the coded width becomes the upscaled width scaled by
     * {@code 8 / SuperresDenom}.
     */
    private void superresParams() {
        var denominator = 8;
        if (sequence.superres && reader.readFlag()) {
            denominator = reader.read(3) + 9;
        }
        upscaledWidth = frameWidth;
        frameWidth = (upscaledWidth * 8 + denominator / 2) / denominator;
    }

    private void computeImageSize() {
        miCols = 2 * ((frameWidth + 7) >> 3);
        miRows = 2 * ((frameHeight + 7) >> 3);
    }

    /**
     * Reads {@code tile_info()} (section 5.9.15).
     */
    private void tileInfo() {
        var big = sequence.superblock128;
        var sbCols = big ? (miCols + 31) >> 5 : (miCols + 15) >> 4;
        var sbRows = big ? (miRows + 31) >> 5 : (miRows + 15) >> 4;
        var sbShift = big ? 5 : 4;
        var sbSize = sbShift + 2;
        var maxTileWidthSb = 4096 >> sbSize;
        var maxTileAreaSb = (4096 * 2304) >> (2 * sbSize);
        var minLog2TileCols = tileLog2(maxTileWidthSb, sbCols);
        var maxLog2TileCols = tileLog2(1, Math.min(sbCols, 64));
        var maxLog2TileRows = tileLog2(1, Math.min(sbRows, 64));
        var minLog2Tiles = Math.max(minLog2TileCols, tileLog2(maxTileAreaSb, sbRows * sbCols));
        if (reader.readFlag()) {
            tileColsLog2 = minLog2TileCols;
            while (tileColsLog2 < maxLog2TileCols && reader.readFlag()) {
                tileColsLog2++;
            }
            var tileWidthSb = (sbCols + (1 << tileColsLog2) - 1) >> tileColsLog2;
            tileCols = (sbCols + tileWidthSb - 1) / tileWidthSb;
            tileRowsLog2 = Math.max(minLog2Tiles - tileColsLog2, 0);
            while (tileRowsLog2 < maxLog2TileRows && reader.readFlag()) {
                tileRowsLog2++;
            }
            var tileHeightSb = (sbRows + (1 << tileRowsLog2) - 1) >> tileRowsLog2;
            tileRows = (sbRows + tileHeightSb - 1) / tileHeightSb;
        } else {
            var widestTileSb = 0;
            var start = 0;
            var count = 0;
            while (start < sbCols) {
                var size = ns(Math.min(sbCols - start, maxTileWidthSb)) + 1;
                widestTileSb = Math.max(size, widestTileSb);
                start += size;
                count++;
            }
            tileCols = count;
            tileColsLog2 = tileLog2(1, tileCols);
            var area = minLog2Tiles > 0 ? (sbRows * sbCols) >> (minLog2Tiles + 1) : sbRows * sbCols;
            var maxTileHeightSb = Math.max(area / widestTileSb, 1);
            start = 0;
            count = 0;
            while (start < sbRows) {
                start += ns(Math.min(sbRows - start, maxTileHeightSb)) + 1;
                count++;
            }
            tileRows = count;
            tileRowsLog2 = tileLog2(1, tileRows);
        }
        if (tileColsLog2 > 0 || tileRowsLog2 > 0) {
            reader.skip(tileRowsLog2 + tileColsLog2);
            tileSizeBytes = reader.read(2) + 1;
        }
    }

    /**
     * Reads {@code quantization_params()} (section 5.9.12).
     */
    private void quantizationParams() {
        baseQIdx = reader.read(8);
        var zero = deltaQ() == 0;
        if (sequence.planes > 1) {
            var different = sequence.separateUvDeltaQ && reader.readFlag();
            zero &= deltaQ() == 0;
            zero &= deltaQ() == 0;
            if (different) {
                zero &= deltaQ() == 0;
                zero &= deltaQ() == 0;
            }
        }
        deltasZero = zero;
        if (reader.readFlag()) {
            reader.skip(4 + 4);
            if (sequence.separateUvDeltaQ) {
                reader.skip(4);
            }
        }
    }

    private int deltaQ() {
        return reader.readFlag() ? su(7) : 0;
    }

    /**
     * Reads {@code segmentation_params()} (section 5.9.14) over the features loaded from the primary reference.
     */
    private void segmentationParams(int primaryRefFrame) {
        segmentationEnabled = reader.readFlag();
        if (!segmentationEnabled) {
            clearFeatures();
            return;
        }
        var updateData = true;
        if (primaryRefFrame != PRIMARY_REF_NONE) {
            if (reader.readFlag()) {
                reader.skip(1);
            }
            updateData = reader.readFlag();
        }
        if (!updateData) {
            return;
        }
        for (var segment = 0; segment < MAX_SEGMENTS; segment++) {
            for (var feature = 0; feature < SEG_LVL_MAX; feature++) {
                var enabled = reader.readFlag();
                var value = 0;
                if (enabled) {
                    var bits = SEGMENTATION_FEATURE_BITS[feature];
                    var limit = SEGMENTATION_FEATURE_MAX[feature];
                    value = SEGMENTATION_FEATURE_SIGNED[feature] ? Math.clamp(su(1 + bits), -limit, limit)
                            : Math.clamp(reader.read(bits), 0, limit);
                }
                featureEnabled[segment][feature] = enabled;
                featureData[segment][feature] = value;
            }
        }
    }

    private void loopFilterParams(boolean codedLossless, boolean allowIntrabc) {
        if (codedLossless || allowIntrabc) {
            return;
        }
        var level0 = reader.read(6);
        var level1 = reader.read(6);
        if (sequence.planes > 1 && (level0 != 0 || level1 != 0)) {
            reader.skip(12);
        }
        reader.skip(3);
        if (reader.readFlag() && reader.readFlag()) {
            for (var index = 0; index < 8 + 2; index++) {
                if (reader.readFlag()) {
                    reader.skip(7);
                }
            }
        }
    }

    private void cdefParams(boolean codedLossless, boolean allowIntrabc) {
        if (codedLossless || allowIntrabc || !sequence.cdef) {
            return;
        }
        reader.skip(2);
        var strengths = 1 << reader.read(2);
        reader.skip((long) strengths * (sequence.planes > 1 ? 12 : 6));
    }

    private void lrParams(boolean allLossless, boolean allowIntrabc) {
        if (allLossless || allowIntrabc || !sequence.restoration) {
            return;
        }
        var usesLr = false;
        var usesChromaLr = false;
        for (var plane = 0; plane < sequence.planes; plane++) {
            if (reader.read(2) != 0) {
                usesLr = true;
                usesChromaLr |= plane > 0;
            }
        }
        if (usesLr) {
            if (sequence.superblock128) {
                reader.skip(1);
            } else if (reader.readFlag()) {
                reader.skip(1);
            }
            if (sequence.subsamplingX && sequence.subsamplingY && usesChromaLr) {
                reader.skip(1);
            }
        }
    }

    /**
     * Returns {@code skipModeAllowed} (section 5.9.22).
     */
    private boolean skipModeAllowed(boolean intra, boolean referenceSelect) {
        if (intra || !referenceSelect || !sequence.orderHint) {
            return false;
        }
        var forwardIdx = -1;
        var forwardHint = 0;
        var backwardIdx = -1;
        var backwardHint = 0;
        for (var index = 0; index < REFS_PER_FRAME; index++) {
            var hint = references[refFrameIdx[index]].orderHint;
            if (relativeDistance(hint, orderHint) < 0) {
                if (forwardIdx < 0 || relativeDistance(hint, forwardHint) > 0) {
                    forwardIdx = index;
                    forwardHint = hint;
                }
            } else if (relativeDistance(hint, orderHint) > 0) {
                if (backwardIdx < 0 || relativeDistance(hint, backwardHint) < 0) {
                    backwardIdx = index;
                    backwardHint = hint;
                }
            }
        }
        if (forwardIdx < 0) {
            return false;
        }
        if (backwardIdx >= 0) {
            return true;
        }
        for (var index = 0; index < REFS_PER_FRAME; index++) {
            if (relativeDistance(references[refFrameIdx[index]].orderHint, forwardHint) < 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * Reads {@code global_motion_params()} (section 5.9.24); the number of bits of each parameter does not depend
     * on the reference parameters.
     */
    private void globalMotionParams(boolean intra, boolean allowHighPrecisionMv) {
        if (intra) {
            return;
        }
        for (var reference = 1; reference <= 7; reference++) {
            var type = 0;
            if (reader.readFlag()) {
                type = reader.readFlag() ? 2 : reader.readFlag() ? 1 : 3;
            }
            if (type >= 2) {
                skipGlobalParam(type, 2, allowHighPrecisionMv);
                skipGlobalParam(type, 3, allowHighPrecisionMv);
                if (type == 3) {
                    skipGlobalParam(type, 4, allowHighPrecisionMv);
                    skipGlobalParam(type, 5, allowHighPrecisionMv);
                }
            }
            if (type >= 1) {
                skipGlobalParam(type, 0, allowHighPrecisionMv);
                skipGlobalParam(type, 1, allowHighPrecisionMv);
            }
        }
    }

    private void skipGlobalParam(int type, int index, boolean allowHighPrecisionMv) {
        var absBits = 12;
        if (index < 2) {
            absBits = type == 1 ? 9 - (allowHighPrecisionMv ? 0 : 1) : 12;
        }
        var mx = 1 << absBits;
        skipSubexp(2 * mx + 1);
    }

    /**
     * Reads {@code decode_subexp(numSyms)} (section 5.9.28).
     */
    private void skipSubexp(int numSyms) {
        var i = 0;
        var mk = 0;
        var k = 3;
        while (true) {
            var b2 = i != 0 ? k + i - 1 : k;
            var a = 1 << b2;
            if (numSyms <= mk + 3 * a) {
                ns(numSyms - mk);
                return;
            }
            if (!reader.readFlag()) {
                reader.skip(b2);
                return;
            }
            i++;
            mk += a;
        }
    }

    /**
     * Reads {@code film_grain_params()} (section 5.9.30).
     */
    private void filmGrainParams(boolean showFrame, boolean showableFrame) {
        var s = sequence;
        if (!s.filmGrain || !showFrame && !showableFrame || !reader.readFlag()) {
            return;
        }
        reader.skip(16);
        if (frameType == INTER_FRAME && !reader.readFlag()) {
            reader.skip(3);
            return;
        }
        var yPoints = reader.read(4);
        reader.skip(16L * yPoints);
        var monochrome = s.planes == 1;
        var chromaFromLuma = !monochrome && reader.readFlag();
        var cbPoints = 0;
        var crPoints = 0;
        if (!monochrome && !chromaFromLuma && !(s.subsamplingX && s.subsamplingY && yPoints == 0)) {
            cbPoints = reader.read(4);
            reader.skip(16L * cbPoints);
            crPoints = reader.read(4);
            reader.skip(16L * crPoints);
        }
        reader.skip(2);
        var lag = reader.read(2);
        var lumaPositions = 2 * lag * (lag + 1);
        var chromaPositions = lumaPositions;
        if (yPoints != 0) {
            chromaPositions = lumaPositions + 1;
            reader.skip(8L * lumaPositions);
        }
        if (chromaFromLuma || cbPoints != 0) {
            reader.skip(8L * chromaPositions);
        }
        if (chromaFromLuma || crPoints != 0) {
            reader.skip(8L * chromaPositions);
        }
        reader.skip(4);
        if (cbPoints != 0) {
            reader.skip(25);
        }
        if (crPoints != 0) {
            reader.skip(25);
        }
        reader.skip(2);
    }

    /**
     * Reads a tile group (section 5.11.1) that ends at a bit position, recording its tiles.
     */
    private boolean tileGroup(long end) {
        var tilesInFrame = tileCols * tileRows;
        var start = 0;
        var last = tilesInFrame - 1;
        if (tilesInFrame > 1 && reader.readFlag()) {
            var bits = tileColsLog2 + tileRowsLog2;
            start = reader.read(bits);
            last = reader.read(bits);
        }
        byteAlignment();
        if (start > last || last >= tilesInFrame || reader.overrun()) {
            return false;
        }
        for (var tile = start; tile <= last; tile++) {
            long size;
            if (tile == last) {
                size = (end - reader.bitPosition()) / 8;
            } else {
                size = 0;
                for (var index = 0; index < tileSizeBytes; index++) {
                    size |= (long) reader.read(8) << (8 * index);
                }
                size++;
            }
            var position = reader.bitPosition() / 8;
            if (size <= 0 || reader.bitPosition() + 8 * size > end) {
                return false;
            }
            addTile((int) position, (int) size);
            reader.skip(8 * size);
        }
        if (last == tilesInFrame - 1) {
            wrapUp();
            seenFrameHeader = false;
        }
        return true;
    }

    private void addTile(int start, int size) {
        if (tiles.length < 1 + 2 * (tileCount + 1)) {
            tiles = Arrays.copyOf(tiles, 2 * tiles.length + 2);
        }
        tiles[1 + 2 * tileCount] = start;
        tiles[2 + 2 * tileCount] = size;
        tileCount++;
    }

    /**
     * Runs {@code decode_frame_wrapup()} (section 7.4): loads the shown key frame and refreshes the reference slots.
     */
    private void wrapUp() {
        if (showExistingFrame && frameType == KEY_FRAME) {
            var shown = references[frameToShow];
            upscaledWidth = shown.upscaledWidth;
            frameWidth = shown.frameWidth;
            frameHeight = shown.frameHeight;
            orderHint = shown.orderHint;
            for (var segment = 0; segment < MAX_SEGMENTS; segment++) {
                System.arraycopy(shown.featureEnabled[segment], 0, featureEnabled[segment], 0, SEG_LVL_MAX);
                System.arraycopy(shown.featureData[segment], 0, featureData[segment], 0, SEG_LVL_MAX);
            }
        }
        if (showExistingFrame && frameType != KEY_FRAME) {
            return;
        }
        for (var slot = 0; slot < references.length; slot++) {
            if ((refreshFrameFlags >>> slot & 1) == 0) {
                continue;
            }
            var reference = references[slot];
            reference.valid = true;
            reference.frameType = frameType;
            reference.orderHint = orderHint;
            reference.frameWidth = frameWidth;
            reference.frameHeight = frameHeight;
            reference.upscaledWidth = upscaledWidth;
            for (var segment = 0; segment < MAX_SEGMENTS; segment++) {
                System.arraycopy(featureEnabled[segment], 0, reference.featureEnabled[segment], 0, SEG_LVL_MAX);
                System.arraycopy(featureData[segment], 0, reference.featureData[segment], 0, SEG_LVL_MAX);
            }
        }
    }

    /**
     * Runs {@code set_frame_refs()} (section 7.8) and returns whether the references it starts from are valid.
     */
    private boolean setFrameRefs(int last, int gold) {
        Arrays.fill(refFrameIdx, -1);
        refFrameIdx[0] = last;
        refFrameIdx[3] = gold;
        var used = usedSlots;
        Arrays.fill(used, false);
        used[last] = true;
        used[gold] = true;
        var current = 1 << (sequence.orderHintBits - 1);
        var shifted = shiftedHints;
        for (var index = 0; index < 8; index++) {
            shifted[index] = current + relativeDistance(references[index].orderHint, orderHint);
        }
        if (shifted[last] >= current || shifted[gold] >= current) {
            return false;
        }
        var ref = latestBackward(shifted, used, current);
        if (ref >= 0) {
            refFrameIdx[6] = ref;
            used[ref] = true;
        }
        ref = earliestBackward(shifted, used, current);
        if (ref >= 0) {
            refFrameIdx[4] = ref;
            used[ref] = true;
        }
        ref = earliestBackward(shifted, used, current);
        if (ref >= 0) {
            refFrameIdx[5] = ref;
            used[ref] = true;
        }
        for (var name : FORWARD_REFERENCES) {
            if (refFrameIdx[name] < 0) {
                ref = latestForward(shifted, used, current);
                if (ref >= 0) {
                    refFrameIdx[name] = ref;
                    used[ref] = true;
                }
            }
        }
        ref = -1;
        var earliest = 0;
        for (var index = 0; index < 8; index++) {
            if (ref < 0 || shifted[index] < earliest) {
                ref = index;
                earliest = shifted[index];
            }
        }
        for (var index = 0; index < REFS_PER_FRAME; index++) {
            if (refFrameIdx[index] < 0) {
                refFrameIdx[index] = ref;
            }
        }
        return true;
    }

    private static int latestBackward(int[] shifted, boolean[] used, int current) {
        var ref = -1;
        var latest = 0;
        for (var index = 0; index < 8; index++) {
            var hint = shifted[index];
            if (!used[index] && hint >= current && (ref < 0 || hint >= latest)) {
                ref = index;
                latest = hint;
            }
        }
        return ref;
    }

    private static int earliestBackward(int[] shifted, boolean[] used, int current) {
        var ref = -1;
        var earliest = 0;
        for (var index = 0; index < 8; index++) {
            var hint = shifted[index];
            if (!used[index] && hint >= current && (ref < 0 || hint < earliest)) {
                ref = index;
                earliest = hint;
            }
        }
        return ref;
    }

    private static int latestForward(int[] shifted, boolean[] used, int current) {
        var ref = -1;
        var latest = 0;
        for (var index = 0; index < 8; index++) {
            var hint = shifted[index];
            if (!used[index] && hint < current && (ref < 0 || hint >= latest)) {
                ref = index;
                latest = hint;
            }
        }
        return ref;
    }
}
