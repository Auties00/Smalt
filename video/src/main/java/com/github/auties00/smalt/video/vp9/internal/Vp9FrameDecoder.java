/* Ports libvpx (vp9/decoder/vp9_decodeframe.c, vp9_decoder.c, vp9/vp9_dx_iface.c, vp9/common/vp9_loopfilter.c).
   Copyright (c) 2010, The WebM Project authors; BSD 3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.video.vp9.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.internal.task.ParallelBands;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;

import static com.github.auties00.smalt.video.vp9.internal.Vp9Constants.COMPOUND_REFERENCE;
import static com.github.auties00.smalt.video.vp9.internal.Vp9Constants.REFERENCE_MODE_SELECT;
import static com.github.auties00.smalt.video.vp9.internal.Vp9Constants.SINGLE_REFERENCE;
import static com.github.auties00.smalt.video.vp9.internal.Vp9Constants.SWITCHABLE;
import static com.github.auties00.smalt.video.vp9.internal.Vp9Constants.TX_MODE_SELECT;

/**
 * Decodes VP9 frames: the uncompressed and compressed headers, the reference frame slots, the
 * tiles, the loop filter and the backward adaptation of the probabilities.
 *
 * <p>{@link #decodePacket(byte[], int, int)} decodes every frame of a packet, including the
 * frames of a superframe, and reports whether the packet leaves a frame to show. The decoder is
 * not thread-safe; with parallelism enabled it decodes the tile columns of a frame and filters
 * its superblock rows on the module's shared helper threads, the loop filter following the
 * decoding as a wavefront, with the same output as a single thread.
 *
 * <p>Corrupt data raises {@link MediaException.Malformed}; the decoder then rejects inter frames
 * until a key frame or an intra-only frame restores valid references, as libvpx does.
 *
 * @implNote This implementation follows libvpx's {@code vp9/decoder/vp9_decodeframe.c}
 * ({@code read_uncompressed_header}, {@code read_compressed_header},
 * {@code vp9_decode_frame}), {@code vp9_decoder.c} ({@code vp9_receive_compressed_data},
 * {@code swap_frame_buffers}, {@code vp9_parse_superframe_index}),
 * {@code vp9/vp9_dx_iface.c} ({@code decoder_decode}) and
 * {@code vp9/common/vp9_loopfilter.c} ({@code vp9_loop_filter_frame_init}).
 */
public final class Vp9FrameDecoder {
    /**
     * The probability of a delta update of a probability ({@code DIFF_UPDATE_PROB}).
     */
    private static final int DIFF_UPDATE_PROB = 252;

    /**
     * The probability of an update of a motion vector probability ({@code MV_UPDATE_PROB}).
     */
    private static final int MV_UPDATE_PROB = 252;

    /**
     * The interpolation filter of each value of the frame header's 2-bit filter literal
     * ({@code literal_to_filter}): smooth, regular, sharp, bilinear.
     */
    private static final int[] LITERAL_TO_FILTER = {1, 0, 2, 3};

    /**
     * The estimated bytes of the decoder state of one 8x8 unit: its mode information, grid
     * entry, segmentation maps and motion vectors.
     */
    private static final long BYTES_PER_UNIT = 256;

    /**
     * The resource limits.
     */
    private final MediaLimits limits;

    /**
     * Whether tile columns and loop filter rows may run on helper threads.
     */
    private final boolean parallel;

    /**
     * The profile of the last frame header.
     */
    int profile;

    /**
     * The bit depth of the frames: 8, 10 or 12.
     */
    int bitDepth = 8;

    /**
     * The horizontal chroma subsampling shift.
     */
    int ssX = 1;

    /**
     * The vertical chroma subsampling shift.
     */
    int ssY = 1;

    /**
     * The color space code of the last key or intra-only frame.
     */
    int colorSpace;

    /**
     * Whether the samples use the full range.
     */
    boolean fullRange;

    /**
     * Whether the current frame is a key frame.
     */
    boolean keyFrame;

    /**
     * Whether the current non-key frame is intra-only; keeps its value across key frames, as
     * libvpx's {@code intra_only}.
     */
    boolean intraOnly;

    /**
     * Whether the current frame is shown.
     */
    boolean showFrame;

    /**
     * Whether the current frame is decodable without the probabilities of earlier frames.
     */
    boolean errorResilient;

    /**
     * The frame width in luma samples.
     */
    int width;

    /**
     * The frame height in luma samples.
     */
    int height;

    /**
     * The intended display width.
     */
    int renderWidth;

    /**
     * The intended display height.
     */
    int renderHeight;

    /**
     * The number of 8x8 unit rows of the frame.
     */
    int miRows;

    /**
     * The number of 8x8 unit columns of the frame.
     */
    int miCols;

    /**
     * The interpolation filter of the frame, or {@link Vp9Constants#SWITCHABLE}.
     */
    int interpFilter;

    /**
     * Whether motion vectors may use eighth-sample precision.
     */
    boolean allowHighPrecisionMv;

    /**
     * The reference mode of the frame.
     */
    int referenceMode;

    /**
     * The fixed reference of compound prediction.
     */
    int compFixedRef;

    /**
     * The two variable references of compound prediction.
     */
    final int[] compVarRef = new int[2];

    /**
     * The sign bias of each reference, indexed by reference frame.
     */
    final int[] refFrameSignBias = new int[4];

    /**
     * The transform mode of the frame.
     */
    int txMode;

    /**
     * Whether the frame is coded losslessly with the Walsh-Hadamard transform.
     */
    boolean lossless;

    /**
     * The base-2 logarithm of the number of tile columns.
     */
    int log2TileCols;

    /**
     * The base-2 logarithm of the number of tile rows.
     */
    int log2TileRows;

    /**
     * Whether the adapted probabilities replace the saved context.
     */
    boolean refreshFrameContext;

    /**
     * Whether the frame skips backward adaptation.
     */
    boolean frameParallelDecodingMode;

    /**
     * The saved context the frame starts from.
     */
    int frameContextIdx;

    /**
     * Which saved contexts an intra-only or error resilient frame resets.
     */
    int resetFrameContext;

    /**
     * Whether candidate motion vectors include those of the previous frame.
     */
    boolean usePrevFrameMvs;

    /**
     * The reference slots the frame replaces.
     */
    int refreshFrameFlags;

    /**
     * Whether segmentation is enabled.
     */
    boolean segEnabled;

    /**
     * Whether the frame codes a new segmentation map.
     */
    boolean segUpdateMap;

    /**
     * Whether the segment features hold absolute values rather than deltas.
     */
    boolean segAbsDelta;

    /**
     * Whether segments are predicted from the previous map.
     */
    boolean segTemporalUpdate;

    /**
     * The segment tree probabilities.
     */
    final int[] segTreeProbs = new int[7];

    /**
     * The segment prediction probabilities.
     */
    final int[] segPredProbs = new int[3];

    /**
     * The segment feature values, {@code [segment 8][feature 4]}.
     */
    final int[] segFeatureData = new int[8 * 4];

    /**
     * The enabled features of each segment, one bit per feature.
     */
    final int[] segFeatureMask = new int[8];

    /**
     * The loop filter level of the frame.
     */
    int filterLevel;

    /**
     * The loop filter sharpness of the frame.
     */
    int sharpnessLevel;

    /**
     * Whether the filter level depends on the reference and mode.
     */
    boolean modeRefDeltaEnabled;

    /**
     * The filter level deltas of the references, intra first.
     */
    final int[] refDeltas = new int[4];

    /**
     * The filter level deltas of the zero and other inter modes.
     */
    final int[] modeDeltas = new int[2];

    /**
     * The levels, thresholds and blocks the loop filter reads.
     */
    final Vp9LoopFilterParams lf = new Vp9LoopFilterParams();

    /**
     * The base quantizer index.
     */
    int baseQindex;

    /**
     * The luma DC quantizer delta.
     */
    int yDcDeltaQ;

    /**
     * The chroma DC quantizer delta.
     */
    int uvDcDeltaQ;

    /**
     * The chroma AC quantizer delta.
     */
    int uvAcDeltaQ;

    /**
     * The luma DC and AC quantizer steps of each segment, {@code [segment 8][dc, ac]}.
     */
    final int[] yDequant = new int[16];

    /**
     * The chroma DC and AC quantizer steps of each segment.
     */
    final int[] uvDequant = new int[16];

    /**
     * The probabilities of the frame being decoded.
     */
    final Vp9FrameContext fc = new Vp9FrameContext();

    /**
     * The four saved probability contexts.
     */
    final Vp9FrameContext[] frameContexts = {new Vp9FrameContext(), new Vp9FrameContext(), new Vp9FrameContext(),
            new Vp9FrameContext()};

    /**
     * The summed symbol counts of the frame's tiles.
     */
    private final Vp9FrameCounts frameCounts = new Vp9FrameCounts();

    /**
     * The mode information objects, one per 8x8 unit position.
     */
    ModeInfo[] miPool = new ModeInfo[0];

    /**
     * The mode information of the block covering each 8x8 unit, row-major with {@link #miCols}
     * columns.
     */
    ModeInfo[] miGrid = new ModeInfo[0];

    /**
     * The segmentation map the frame writes.
     */
    byte[] currentSegMap = new byte[0];

    /**
     * The segmentation map of the previous frame.
     */
    byte[] lastSegMap = new byte[0];

    /**
     * The nonzero coefficient contexts above the current superblock row, per plane, in 4x4 units.
     */
    final byte[][] aboveContext = {new byte[0], new byte[0], new byte[0]};

    /**
     * The partition contexts above the current superblock row, in 8x8 units.
     */
    byte[] aboveSegContext = new byte[0];

    /**
     * The frame being decoded.
     */
    Vp9Frame curFrame;

    /**
     * The eight reference slots.
     */
    final Vp9Frame[] refFrameMap = new Vp9Frame[8];

    /**
     * The three references of the current inter frame: last, golden, alternate.
     */
    final Vp9Frame[] activeRefs = new Vp9Frame[3];

    /**
     * The horizontal scale factor of each active reference.
     */
    final int[] refXScale = new int[3];

    /**
     * The vertical scale factor of each active reference.
     */
    final int[] refYScale = new int[3];

    /**
     * The horizontal step of each active reference, in sixteenths.
     */
    final int[] refXStep = new int[3];

    /**
     * The vertical step of each active reference, in sixteenths.
     */
    final int[] refYStep = new int[3];

    /**
     * The previously decoded frame, whose motion vectors the current frame may use.
     */
    Vp9Frame prevFrame;

    /**
     * The frame buffers, in use or free.
     */
    private final List<Vp9Frame> pool = new ArrayList<>();

    /**
     * The width of the previous frame.
     */
    private int lastWidth;

    /**
     * The height of the previous frame.
     */
    private int lastHeight;

    /**
     * Whether the previous decoded frame was shown.
     */
    private boolean lastShowFrame;

    /**
     * Whether the previous frame was a key frame.
     */
    private boolean lastKeyFrame;

    /**
     * Whether the previous frame was intra-only.
     */
    private boolean lastIntraOnly;

    /**
     * Whether the decoder waits for a key frame or an intra-only frame.
     */
    private boolean needResync = true;

    /**
     * The frame to show after the last decoded frame, or {@code null}.
     */
    private Vp9Frame frameToShow;

    /**
     * The frame a show-existing-frame header selects.
     */
    private Vp9Frame refFrameShown;

    /**
     * Whether the last frame of the last packet is shown.
     */
    private boolean showResult;

    /**
     * Whether the frame to show is a key frame.
     */
    private boolean shownKeyFrame;

    /**
     * The tile decoders, one per tile column decoded in parallel.
     */
    private final List<Vp9TileDecoder> tileDecoders = new ArrayList<>();

    /**
     * The loop filters, one per worker of the loop filter wavefront; the first one serves the
     * calling thread.
     */
    private final List<Vp9LoopFilter> loopFilters = new ArrayList<>();

    /**
     * The wavefront of the loop filter rows, or {@code null} before the first parallel filter.
     */
    private Vp9Wavefront wavefront;

    /**
     * The number of superblock rows the loop filter has finished while the current frame's tiles
     * were decoded on the calling thread.
     */
    private int filteredSbRows;

    /**
     * The next superblock row a loop filter worker claims.
     */
    private final AtomicInteger filterTickets = new AtomicInteger();

    /**
     * The loop filter workers running on helper threads for the current frame.
     */
    private final List<ParallelBands.Pending<Void>> filterWorkers = new ArrayList<>();

    /**
     * The number of superblock rows each tile column has decoded, one wavefront row per column,
     * which loop filter workers started during parallel tile decoding wait for; {@code null}
     * before the first such frame.
     */
    private Vp9Wavefront decodeProgress;

    /**
     * The start index in the packet of every tile, {@code [row][column]}.
     */
    private final int[] tileStarts = new int[4 * 64];

    /**
     * The size in bytes of every tile.
     */
    private final int[] tileSizes = new int[4 * 64];

    /**
     * The index of the first byte after the last frame decoded.
     */
    private int frameEnd;

    /**
     * Creates a frame decoder.
     *
     * @param limits the limits checked before allocating
     * @param parallel whether tile columns and loop filter rows may run on helper threads
     */
    public Vp9FrameDecoder(MediaLimits limits, boolean parallel) {
        this.limits = limits;
        this.parallel = parallel;
        this.loopFilters.add(new Vp9LoopFilter(lf));
    }

    /**
     * Returns the frame the last packet shows, valid until the next call.
     *
     * @return the frame, or {@code null} when the packet shows none
     */
    public Vp9Frame shownFrame() {
        return showResult ? frameToShow : null;
    }

    /**
     * Returns whether the frame the last packet shows was coded as a key frame.
     *
     * @return {@code true} for a key frame
     */
    public boolean shownKeyFrame() {
        return shownKeyFrame;
    }

    /**
     * Decodes every frame of a packet ({@code decoder_decode}).
     *
     * <p>A packet ending with a superframe index holds the frames the index lists; otherwise it
     * holds one frame, possibly followed by further frames after zero padding. After the call,
     * {@link #shownFrame()} returns the frame to show when the packet's last frame is shown.
     *
     * @param data the packet
     * @param offset the index of the packet's first byte
     * @param length the packet length
     * @throws MediaException.Malformed if a frame is corrupt or needs references the decoder lacks
     * @throws MediaException.Unsupported if a frame uses a feature the decoder lacks
     * @throws MediaException.LimitExceeded if a frame exceeds the limits
     */
    public void decodePacket(byte[] data, int offset, int length) {
        showResult = false;
        if (length <= 0) {
            return;
        }
        var end = offset + length;
        var marker = data[end - 1] & 0xFF;
        if ((marker & 0xE0) == 0xC0) {
            var frames = (marker & 7) + 1;
            var mag = ((marker >> 3) & 3) + 1;
            var indexSize = 2 + mag * frames;
            if (length < indexSize) {
                throw new MediaException.Malformed("invalid vp9 superframe index");
            }
            if ((data[end - indexSize] & 0xFF) != marker) {
                throw new MediaException.Malformed("invalid vp9 superframe index");
            }
            var x = end - indexSize + 1;
            var start = offset;
            for (var i = 0; i < frames; i++) {
                long size = 0;
                for (var j = 0; j < mag; j++) {
                    size |= (long) (data[x++] & 0xFF) << (j * 8);
                }
                if (size > end - start) {
                    throw new MediaException.Malformed("invalid vp9 frame size in superframe index");
                }
                decodeOne(data, start, (int) size);
                start += (int) size;
            }
            return;
        }
        var start = offset;
        while (start < end) {
            var next = decodeOne(data, start, end - start);
            start = Math.max(next, start + 1);
            while (start < end && data[start] == 0) {
                start++;
            }
        }
    }

    /**
     * Decodes one frame, marking the decoder for resynchronization when it fails
     * ({@code decode_one}, {@code vp9_receive_compressed_data}).
     *
     * @param data the packet
     * @param offset the index of the frame's first byte
     * @param size the frame's size in bytes
     * @return the index of the first byte after the frame's data
     */
    private int decodeOne(byte[] data, int offset, int size) {
        try {
            var result = decodeFrame(data, offset, offset + size);
            showResult = showFrame;
            return result;
        } catch (RuntimeException failure) {
            needResync = true;
            showResult = false;
            if (curFrame != null && curFrame.refCount == 0) {
                curFrame = null;
            }
            if (failure instanceof MediaException mediaException) {
                throw mediaException;
            }
            throw new MediaException.Malformed("corrupt vp9 frame", failure);
        }
    }

    /**
     * Decodes one frame ({@code vp9_decode_frame}).
     *
     * @param data the packet
     * @param start the index of the frame's first byte
     * @param end the index one past the frame's last byte
     * @return the index of the first byte after the frame's data
     */
    private int decodeFrame(byte[] data, int start, int end) {
        var rb = new Vp9BitReader(data, start, end);
        var headerSize = readUncompressedHeader(rb);
        if (headerSize == 0) {
            frameToShow = refFrameShown;
            shownKeyFrame = refFrameShown.keyFrame;
            return start + (profile <= 2 ? 1 : 2);
        }
        var compressedStart = start + rb.bytesRead();
        if (headerSize > end - compressedStart) {
            throw new MediaException.Malformed("truncated packet or corrupt vp9 header length");
        }
        usePrevFrameMvs = !errorResilient && width == lastWidth && height == lastHeight && !lastIntraOnly
                && lastShowFrame && !lastKeyFrame && prevFrame != null && prevFrame.mvRows == miRows
                && prevFrame.mvCols == miCols;
        fc.copyFrom(frameContexts[frameContextIdx]);
        if (!fc.initialized) {
            throw new MediaException.Malformed("uninitialized vp9 entropy context");
        }
        if (readCompressedHeader(data, compressedStart, headerSize)) {
            throw new MediaException.Malformed("corrupt vp9 compressed header");
        }
        if (filterLevel != 0) {
            loopFilterFrameInit();
        }
        frameEnd = decodeTiles(data, compressedStart + headerSize, end);
        if (filterLevel != 0) {
            filterFrame();
        }
        if (!errorResilient && !frameParallelDecodingMode) {
            frameCounts.clear();
            for (var decoder : tileDecoders) {
                frameCounts.add(decoder.counts);
            }
            var previous = frameContexts[frameContextIdx];
            Vp9Adaptation.adaptCoefProbs(previous, fc, frameCounts, intraOnlyFrame(), lastKeyFrame);
            if (!intraOnlyFrame()) {
                Vp9Adaptation.adaptModeProbs(previous, fc, frameCounts, interpFilter == SWITCHABLE,
                        txMode == TX_MODE_SELECT);
                Vp9Adaptation.adaptMvProbs(previous, fc, frameCounts, allowHighPrecisionMv);
            }
        }
        if (refreshFrameContext) {
            frameContexts[frameContextIdx].copyFrom(fc);
        }
        swapFrameBuffers();
        lastShowFrame = showFrame;
        prevFrame = curFrame;
        if (segEnabled) {
            var swap = currentSegMap;
            currentSegMap = lastSegMap;
            lastSegMap = swap;
        }
        lastWidth = width;
        lastHeight = height;
        frameToShow = curFrame;
        shownKeyFrame = keyFrame;
        curFrame.keyFrame = keyFrame;
        return frameEnd;
    }

    /**
     * Returns whether the current frame is a key frame or an intra-only frame
     * ({@code frame_is_intra_only}).
     *
     * @return {@code true} for frames without references
     */
    boolean intraOnlyFrame() {
        return keyFrame || intraOnly;
    }

    /**
     * Updates the reference slots the frame refreshes ({@code swap_frame_buffers}).
     */
    private void swapFrameBuffers() {
        for (var i = 0; i < 8; i++) {
            if ((refreshFrameFlags & (1 << i)) != 0) {
                var old = refFrameMap[i];
                if (old != null) {
                    old.refCount--;
                }
                refFrameMap[i] = curFrame;
                curFrame.refCount++;
            }
        }
        Arrays.fill(activeRefs, null);
    }

    /**
     * Releases every reference slot ({@code flush_all_fb_on_key} with the reset of
     * {@code ref_frame_map}).
     */
    private void releaseReferences() {
        for (var i = 0; i < 8; i++) {
            if (refFrameMap[i] != null) {
                refFrameMap[i].refCount--;
                refFrameMap[i] = null;
            }
        }
    }

    /**
     * Reads the uncompressed header of a frame and prepares the frame buffer
     * ({@code read_uncompressed_header}).
     *
     * @param rb the header reader
     * @return the size of the compressed header, or 0 for a frame that shows an earlier frame
     * @throws MediaException.Malformed if the header is invalid
     * @throws MediaException.Unsupported if the profile is unknown
     */
    private int readUncompressedHeader(Vp9BitReader rb) {
        lastKeyFrame = keyFrame;
        lastIntraOnly = intraOnly;
        if (rb.readLiteral(2) != 2) {
            throw new MediaException.Malformed("invalid vp9 frame marker");
        }
        profile = rb.readBit() | (rb.readBit() << 1);
        if (profile > 2) {
            profile += rb.readBit();
        }
        if (profile > 3) {
            throw new MediaException.Unsupported("vp9", "vp9 profile " + profile + " is not supported");
        }
        if (rb.readBit() != 0) {
            var frame = refFrameMap[rb.readLiteral(3)];
            if (frame == null) {
                throw new MediaException.Malformed("vp9 frame shows an empty reference slot");
            }
            refFrameShown = frame;
            refreshFrameFlags = 0;
            filterLevel = 0;
            showFrame = true;
            return 0;
        }
        keyFrame = rb.readBit() == 0;
        showFrame = rb.readBit() != 0;
        errorResilient = rb.readBit() != 0;
        if (keyFrame) {
            readSyncCode(rb);
            readColorConfig(rb);
            refreshFrameFlags = 0xFF;
            Arrays.fill(activeRefs, null);
            setupFrameSize(rb);
            if (needResync) {
                releaseReferences();
                needResync = false;
            }
        } else {
            intraOnly = !showFrame && rb.readBit() != 0;
            resetFrameContext = errorResilient ? 0 : rb.readLiteral(2);
            if (intraOnly) {
                readSyncCode(rb);
                if (profile > 0) {
                    readColorConfig(rb);
                } else {
                    colorSpace = 1;
                    fullRange = false;
                    ssX = 1;
                    ssY = 1;
                    bitDepth = 8;
                }
                refreshFrameFlags = rb.readLiteral(8);
                setupFrameSize(rb);
                if (needResync) {
                    releaseReferences();
                    needResync = false;
                }
            } else if (!needResync) {
                refreshFrameFlags = rb.readLiteral(8);
                for (var i = 0; i < 3; i++) {
                    var frame = refFrameMap[rb.readLiteral(3)];
                    if (frame == null) {
                        throw new MediaException.Malformed("invalid vp9 reference frame index");
                    }
                    activeRefs[i] = frame;
                    refFrameSignBias[1 + i] = rb.readBit();
                }
                setupFrameSizeWithRefs(rb);
                allowHighPrecisionMv = rb.readBit() != 0;
                interpFilter = rb.readBit() != 0 ? SWITCHABLE : LITERAL_TO_FILTER[rb.readLiteral(2)];
                for (var i = 0; i < 3; i++) {
                    setupScaleFactors(i, activeRefs[i].width, activeRefs[i].height);
                }
            }
        }
        if (needResync) {
            throw new MediaException.Malformed("vp9 decoding needs a key frame or an intra-only frame");
        }
        curFrame.colorSpace = colorSpace;
        curFrame.fullRange = fullRange;
        curFrame.renderWidth = renderWidth;
        curFrame.renderHeight = renderHeight;
        if (!errorResilient) {
            refreshFrameContext = rb.readBit() != 0;
            frameParallelDecodingMode = rb.readBit() != 0;
        } else {
            refreshFrameContext = false;
            frameParallelDecodingMode = true;
        }
        frameContextIdx = rb.readLiteral(2);
        if (intraOnlyFrame() || errorResilient) {
            setupPastIndependence();
        }
        readLoopFilter(rb);
        readQuantization(rb);
        readSegmentation(rb);
        setupSegmentationDequant();
        readTileInfo(rb);
        var size = rb.readLiteral(16);
        if (size == 0) {
            throw new MediaException.Malformed("invalid vp9 compressed header size");
        }
        return size;
    }

    /**
     * Reads and checks the sync code of key and intra-only frames.
     *
     * @param rb the header reader
     * @throws MediaException.Malformed if the code is wrong
     */
    private static void readSyncCode(Vp9BitReader rb) {
        if (rb.readLiteral(8) != 0x49 || rb.readLiteral(8) != 0x83 || rb.readLiteral(8) != 0x42) {
            throw new MediaException.Malformed("invalid vp9 frame sync code");
        }
    }

    /**
     * Reads the bit depth, color space and chroma subsampling
     * ({@code read_bitdepth_colorspace_sampling}).
     *
     * @param rb the header reader
     * @throws MediaException.Malformed if the combination is invalid for the profile
     */
    private void readColorConfig(Vp9BitReader rb) {
        bitDepth = profile >= 2 ? (rb.readBit() != 0 ? 12 : 10) : 8;
        colorSpace = rb.readLiteral(3);
        if (colorSpace != 7) {
            fullRange = rb.readBit() != 0;
            if (profile == 1 || profile == 3) {
                ssX = rb.readBit();
                ssY = rb.readBit();
                if (ssX == 1 && ssY == 1) {
                    throw new MediaException.Malformed("vp9 4:2:0 color in profile " + profile);
                }
                if (rb.readBit() != 0) {
                    throw new MediaException.Malformed("vp9 reserved bit set");
                }
            } else {
                ssX = 1;
                ssY = 1;
            }
        } else {
            fullRange = true;
            if (profile == 1 || profile == 3) {
                ssX = 0;
                ssY = 0;
                if (rb.readBit() != 0) {
                    throw new MediaException.Malformed("vp9 reserved bit set");
                }
            } else {
                throw new MediaException.Malformed("vp9 4:4:4 color in profile " + profile);
            }
        }
    }

    /**
     * Reads the frame size and the render size, then sizes the decoder state and the frame
     * buffer ({@code setup_frame_size}).
     *
     * @param rb the header reader
     */
    private void setupFrameSize(Vp9BitReader rb) {
        var w = rb.readLiteral(16) + 1;
        var h = rb.readLiteral(16) + 1;
        resizeContextBuffers(w, h);
        readRenderSize(rb);
        allocateFrame();
    }

    /**
     * Reads the frame size, possibly copied from a reference, checks the references, then sizes
     * the decoder state and the frame buffer ({@code setup_frame_size_with_refs}).
     *
     * @param rb the header reader
     * @throws MediaException.Malformed if no reference has a usable size or a reference has
     * another format
     */
    private void setupFrameSizeWithRefs(Vp9BitReader rb) {
        var w = 0;
        var h = 0;
        var found = false;
        for (var i = 0; i < 3; i++) {
            if (rb.readBit() != 0) {
                w = activeRefs[i].width;
                h = activeRefs[i].height;
                found = true;
                break;
            }
        }
        if (!found) {
            w = rb.readLiteral(16) + 1;
            h = rb.readLiteral(16) + 1;
        }
        var valid = false;
        for (var i = 0; i < 3; i++) {
            valid |= validRefSize(activeRefs[i].width, activeRefs[i].height, w, h);
        }
        if (!valid) {
            throw new MediaException.Malformed("vp9 referenced frame has invalid size");
        }
        for (var i = 0; i < 3; i++) {
            var ref = activeRefs[i];
            if (ref.bitDepth != bitDepth || ref.ssX != ssX || ref.ssY != ssY) {
                throw new MediaException.Malformed("vp9 referenced frame has incompatible color format");
            }
        }
        resizeContextBuffers(w, h);
        readRenderSize(rb);
        allocateFrame();
    }

    /**
     * Returns whether a frame may predict from a reference of a given size
     * ({@code valid_ref_frame_size}).
     *
     * @param refWidth the reference width
     * @param refHeight the reference height
     * @param thisWidth the frame width
     * @param thisHeight the frame height
     * @return {@code true} when the reference is at most twice as large and at most 16 times as
     * small
     */
    private static boolean validRefSize(int refWidth, int refHeight, int thisWidth, int thisHeight) {
        return 2 * thisWidth >= refWidth && 2 * thisHeight >= refHeight && thisWidth <= 16 * refWidth
                && thisHeight <= 16 * refHeight;
    }

    /**
     * Reads the render size ({@code setup_render_size}).
     *
     * @param rb the header reader
     */
    private void readRenderSize(Vp9BitReader rb) {
        renderWidth = width;
        renderHeight = height;
        if (rb.readBit() != 0) {
            renderWidth = rb.readLiteral(16) + 1;
            renderHeight = rb.readLiteral(16) + 1;
        }
    }

    /**
     * Computes the scale factors of an active reference ({@code vp9_setup_scale_factors_for_frame}).
     *
     * @param index the reference index, 0 to 2
     * @param refWidth the reference width
     * @param refHeight the reference height
     */
    private void setupScaleFactors(int index, int refWidth, int refHeight) {
        if (!validRefSize(refWidth, refHeight, width, height)) {
            refXScale[index] = Vp9Constants.REF_INVALID_SCALE;
            refYScale[index] = Vp9Constants.REF_INVALID_SCALE;
            return;
        }
        refXScale[index] = (refWidth << 14) / width;
        refYScale[index] = (refHeight << 14) / height;
        refXStep[index] = (16 * refXScale[index]) >> 14;
        refYStep[index] = (16 * refYScale[index]) >> 14;
    }

    /**
     * Sizes the mode information, contexts and segmentation maps for a frame size
     * ({@code resize_context_buffers}).
     *
     * @param w the frame width
     * @param h the frame height
     * @throws MediaException.LimitExceeded if the frame exceeds the limits
     */
    private void resizeContextBuffers(int w, int h) {
        LimitChecks.checkPicture(limits, w, h);
        if (width != w || height != h) {
            var newMiRows = (h + 7) >> 3;
            var newMiCols = (w + 7) >> 3;
            var units = (long) newMiRows * newMiCols;
            LimitChecks.checkAllocation(limits, units * BYTES_PER_UNIT);
            if (miPool.length < units) {
                var grown = new ModeInfo[(int) units];
                System.arraycopy(miPool, 0, grown, 0, miPool.length);
                for (var i = miPool.length; i < grown.length; i++) {
                    grown[i] = new ModeInfo();
                }
                miPool = grown;
                miGrid = new ModeInfo[(int) units];
            }
            var alignedCols = (newMiCols + 7) & ~7;
            if (aboveSegContext.length < alignedCols) {
                for (var plane = 0; plane < 3; plane++) {
                    aboveContext[plane] = new byte[2 * alignedCols + 16];
                }
                aboveSegContext = new byte[alignedCols + 8];
            }
            if (currentSegMap.length < units) {
                currentSegMap = new byte[(int) units];
                lastSegMap = new byte[(int) units];
            }
            miRows = newMiRows;
            miCols = newMiCols;
            lf.setGrid(miGrid, miRows, miCols);
            Arrays.fill(miGrid, null);
            Arrays.fill(lastSegMap, 0, (int) units, (byte) 0);
            width = w;
            height = h;
        }
    }

    /**
     * Takes a free frame buffer and sizes it for the current format.
     *
     * @throws MediaException.LimitExceeded if the frame exceeds the limits
     */
    private void allocateFrame() {
        Vp9Frame free = null;
        for (var frame : pool) {
            if (frame.refCount == 0 && frame != prevFrame && frame != frameToShow) {
                free = frame;
                break;
            }
        }
        if (free == null) {
            free = new Vp9Frame();
            pool.add(free);
        }
        free.allocate(width, height, ssX, ssY, bitDepth, limits);
        free.allocateMvs(miRows, miCols);
        free.keyFrame = false;
        curFrame = free;
    }

    /**
     * Resets the probabilities, segmentation features and loop filter deltas to their defaults,
     * for key, intra-only and error resilient frames ({@code vp9_setup_past_independence}).
     */
    private void setupPastIndependence() {
        Arrays.fill(segFeatureData, 0);
        Arrays.fill(segFeatureMask, 0);
        segAbsDelta = false;
        var units = miRows * miCols;
        Arrays.fill(lastSegMap, 0, units, (byte) 0);
        Arrays.fill(currentSegMap, 0, units, (byte) 0);
        modeRefDeltaEnabled = true;
        refDeltas[0] = 1;
        refDeltas[1] = 0;
        refDeltas[2] = -1;
        refDeltas[3] = -1;
        modeDeltas[0] = 0;
        modeDeltas[1] = 0;
        lf.resetSharpness();
        fc.setDefaults();
        if (keyFrame || errorResilient || resetFrameContext == 3) {
            for (var context : frameContexts) {
                context.copyFrom(fc);
            }
        } else if (resetFrameContext == 2) {
            frameContexts[frameContextIdx].copyFrom(fc);
        }
        Arrays.fill(refFrameSignBias, 0);
        frameContextIdx = 0;
    }

    /**
     * Reads the loop filter parameters ({@code setup_loopfilter}).
     *
     * @param rb the header reader
     */
    private void readLoopFilter(Vp9BitReader rb) {
        filterLevel = rb.readLiteral(6);
        sharpnessLevel = rb.readLiteral(3);
        modeRefDeltaEnabled = rb.readBit() != 0;
        if (modeRefDeltaEnabled && rb.readBit() != 0) {
            for (var i = 0; i < 4; i++) {
                if (rb.readBit() != 0) {
                    refDeltas[i] = rb.readSignedLiteral(6);
                }
            }
            for (var i = 0; i < 2; i++) {
                if (rb.readBit() != 0) {
                    modeDeltas[i] = rb.readSignedLiteral(6);
                }
            }
        }
    }

    /**
     * Reads the quantizer parameters ({@code setup_quantization}).
     *
     * @param rb the header reader
     */
    private void readQuantization(Vp9BitReader rb) {
        baseQindex = rb.readLiteral(8);
        yDcDeltaQ = rb.readBit() != 0 ? rb.readSignedLiteral(4) : 0;
        uvDcDeltaQ = rb.readBit() != 0 ? rb.readSignedLiteral(4) : 0;
        uvAcDeltaQ = rb.readBit() != 0 ? rb.readSignedLiteral(4) : 0;
        lossless = baseQindex == 0 && yDcDeltaQ == 0 && uvDcDeltaQ == 0 && uvAcDeltaQ == 0;
    }

    /**
     * Reads the segmentation parameters ({@code setup_segmentation}).
     *
     * @param rb the header reader
     */
    private void readSegmentation(Vp9BitReader rb) {
        segUpdateMap = false;
        segEnabled = rb.readBit() != 0;
        if (!segEnabled) {
            return;
        }
        segUpdateMap = rb.readBit() != 0;
        if (segUpdateMap) {
            for (var i = 0; i < 7; i++) {
                segTreeProbs[i] = rb.readBit() != 0 ? rb.readLiteral(8) : 255;
            }
            segTemporalUpdate = rb.readBit() != 0;
            for (var i = 0; i < 3; i++) {
                segPredProbs[i] = segTemporalUpdate && rb.readBit() != 0 ? rb.readLiteral(8) : 255;
            }
        }
        if (rb.readBit() != 0) {
            segAbsDelta = rb.readBit() != 0;
            Arrays.fill(segFeatureData, 0);
            Arrays.fill(segFeatureMask, 0);
            int[] maxValues = {255, 63, 3, 0};
            for (var i = 0; i < 8; i++) {
                for (var j = 0; j < 4; j++) {
                    var data = 0;
                    if (rb.readBit() != 0) {
                        segFeatureMask[i] |= 1 << j;
                        var max = maxValues[j];
                        var bits = 32 - Integer.numberOfLeadingZeros(max);
                        data = Math.min(rb.readLiteral(bits), max);
                        if (j < 2 && rb.readBit() != 0) {
                            data = -data;
                        }
                    }
                    segFeatureData[i * 4 + j] = data;
                }
            }
        }
    }

    /**
     * Computes the quantizer steps of every segment ({@code setup_segmentation_dequant}).
     */
    private void setupSegmentationDequant() {
        var segments = segEnabled ? 8 : 1;
        for (var i = 0; i < segments; i++) {
            var qindex = baseQindex;
            if (segEnabled && (segFeatureMask[i] & 1) != 0) {
                var data = segFeatureData[i * 4];
                qindex = Math.clamp(segAbsDelta ? data : baseQindex + data, 0, 255);
            }
            yDequant[i * 2] = dcQuant(qindex, yDcDeltaQ);
            yDequant[i * 2 + 1] = acQuant(qindex, 0);
            uvDequant[i * 2] = dcQuant(qindex, uvDcDeltaQ);
            uvDequant[i * 2 + 1] = acQuant(qindex, uvAcDeltaQ);
        }
    }

    /**
     * Returns the DC quantizer step of a quantizer index ({@code vp9_dc_quant}).
     *
     * @param qindex the index
     * @param delta the signed offset added to {@code qindex} before clamping
     * @return the step
     */
    private int dcQuant(int qindex, int delta) {
        var index = Math.clamp(qindex + delta, 0, 255);
        return switch (bitDepth) {
            case 10 -> Vp9QuantTables.DC_QLOOKUP_10[index];
            case 12 -> Vp9QuantTables.DC_QLOOKUP_12[index];
            default -> Vp9QuantTables.DC_QLOOKUP[index];
        };
    }

    /**
     * Returns the AC quantizer step of a quantizer index ({@code vp9_ac_quant}).
     *
     * @param qindex the index
     * @param delta the signed offset added to {@code qindex} before clamping
     * @return the step
     */
    private int acQuant(int qindex, int delta) {
        var index = Math.clamp(qindex + delta, 0, 255);
        return switch (bitDepth) {
            case 10 -> Vp9QuantTables.AC_QLOOKUP_10[index];
            case 12 -> Vp9QuantTables.AC_QLOOKUP_12[index];
            default -> Vp9QuantTables.AC_QLOOKUP[index];
        };
    }

    /**
     * Reads the tile layout ({@code setup_tile_info}).
     *
     * @param rb the header reader
     * @throws MediaException.Malformed if there are too many tile columns
     */
    private void readTileInfo(Vp9BitReader rb) {
        var sb64Cols = (miCols + 7) >> 3;
        var minLog2 = 0;
        while ((64 << minLog2) < sb64Cols) {
            minLog2++;
        }
        var maxLog2 = 1;
        while ((sb64Cols >> maxLog2) >= 4) {
            maxLog2++;
        }
        maxLog2--;
        var maxOnes = maxLog2 - minLog2;
        log2TileCols = minLog2;
        while (maxOnes-- > 0 && rb.readBit() != 0) {
            log2TileCols++;
        }
        if (log2TileCols > 6) {
            throw new MediaException.Malformed("invalid number of vp9 tile columns");
        }
        log2TileRows = rb.readBit();
        if (log2TileRows != 0) {
            log2TileRows += rb.readBit();
        }
    }

    /**
     * Returns the first unit of a tile row or column ({@code get_tile_offset}).
     *
     * @param index the tile index
     * @param mis the number of units of the frame in that direction
     * @param log2 the base-2 logarithm of the number of tiles in that direction
     * @return the first unit, at most {@code mis}
     */
    int tileOffset(int index, int mis, int log2) {
        var sbCols = (mis + 7) >> 3;
        var offset = ((index * sbCols) >> log2) << 3;
        return Math.min(offset, mis);
    }

    /**
     * Reads the compressed header, the probability updates of the frame
     * ({@code read_compressed_header}).
     *
     * @param data the packet
     * @param offset the index of the header's first byte
     * @param size the header size
     * @return {@code true} when the header is corrupt
     */
    private boolean readCompressedHeader(byte[] data, int offset, int size) {
        var r = new Vp9BoolDecoder();
        if (r.init(data, offset, size)) {
            throw new MediaException.Malformed("corrupt vp9 compressed header marker bit");
        }
        var p = fc.probs;
        if (lossless) {
            txMode = 0;
        } else {
            txMode = r.readLiteral(2);
            if (txMode == 3) {
                txMode += r.readBit();
            }
        }
        if (txMode == TX_MODE_SELECT) {
            for (var i = 0; i < 2; i++) {
                diffUpdate(r, p, Vp9FrameContext.TX_8X8 + i);
            }
            for (var i = 0; i < 2; i++) {
                for (var j = 0; j < 2; j++) {
                    diffUpdate(r, p, Vp9FrameContext.TX_16X16 + i * 2 + j);
                }
            }
            for (var i = 0; i < 2; i++) {
                for (var j = 0; j < 3; j++) {
                    diffUpdate(r, p, Vp9FrameContext.TX_32X32 + i * 3 + j);
                }
            }
        }
        var maxTxSize = Vp9Constants.TX_MODE_TO_BIGGEST[txMode];
        for (var tx = 0; tx <= maxTxSize; tx++) {
            if (r.readBit() != 0) {
                for (var set = 0; set < 2 * 2 * 6; set++) {
                    var band = set % 6;
                    var contexts = band == 0 ? 3 : 6;
                    for (var ctx = 0; ctx < contexts; ctx++) {
                        var base = Vp9FrameContext.COEF + ((tx * 24 + set) * 6 + ctx) * 3;
                        for (var m = 0; m < 3; m++) {
                            diffUpdate(r, p, base + m);
                        }
                    }
                }
            }
        }
        for (var k = 0; k < 3; k++) {
            diffUpdate(r, p, Vp9FrameContext.SKIP + k);
        }
        if (!intraOnlyFrame()) {
            for (var i = 0; i < 7 * 3; i++) {
                diffUpdate(r, p, Vp9FrameContext.INTER_MODE + i);
            }
            if (interpFilter == SWITCHABLE) {
                for (var i = 0; i < 4 * 2; i++) {
                    diffUpdate(r, p, Vp9FrameContext.SWITCHABLE_INTERP + i);
                }
            }
            for (var i = 0; i < 4; i++) {
                diffUpdate(r, p, Vp9FrameContext.INTRA_INTER + i);
            }
            referenceMode = SINGLE_REFERENCE;
            if (refFrameSignBias[2] != refFrameSignBias[1] || refFrameSignBias[3] != refFrameSignBias[1]) {
                referenceMode = r.readBit() != 0 ? (r.readBit() != 0 ? REFERENCE_MODE_SELECT : COMPOUND_REFERENCE)
                        : SINGLE_REFERENCE;
            }
            if (referenceMode != SINGLE_REFERENCE) {
                setupCompoundReferenceMode();
            }
            if (referenceMode == REFERENCE_MODE_SELECT) {
                for (var i = 0; i < 5; i++) {
                    diffUpdate(r, p, Vp9FrameContext.COMP_INTER + i);
                }
            }
            if (referenceMode != COMPOUND_REFERENCE) {
                for (var i = 0; i < 5; i++) {
                    diffUpdate(r, p, Vp9FrameContext.SINGLE_REF + i * 2);
                    diffUpdate(r, p, Vp9FrameContext.SINGLE_REF + i * 2 + 1);
                }
            }
            if (referenceMode != SINGLE_REFERENCE) {
                for (var i = 0; i < 5; i++) {
                    diffUpdate(r, p, Vp9FrameContext.COMP_REF + i);
                }
            }
            for (var i = 0; i < 4 * 9; i++) {
                diffUpdate(r, p, Vp9FrameContext.Y_MODE + i);
            }
            for (var i = 0; i < 16 * 3; i++) {
                diffUpdate(r, p, Vp9FrameContext.PARTITION + i);
            }
            readMvProbs(r, p);
        }
        return r.hasError();
    }

    /**
     * Chooses the fixed and variable references of compound prediction from the sign biases
     * ({@code vp9_setup_compound_reference_mode}).
     */
    private void setupCompoundReferenceMode() {
        var bias = refFrameSignBias;
        if (bias[1] == bias[2]) {
            compFixedRef = 3;
            compVarRef[0] = 1;
            compVarRef[1] = 2;
        } else if (bias[1] == bias[3]) {
            compFixedRef = 2;
            compVarRef[0] = 1;
            compVarRef[1] = 3;
        } else {
            compFixedRef = 1;
            compVarRef[0] = 2;
            compVarRef[1] = 3;
        }
    }

    /**
     * Reads the motion vector probability updates ({@code read_mv_probs}).
     *
     * @param r the compressed header decoder
     * @param p the probabilities
     */
    private void readMvProbs(Vp9BoolDecoder r, int[] p) {
        updateMvProbs(r, p, Vp9FrameContext.MV_JOINTS, 3);
        for (var i = 0; i < 2; i++) {
            updateMvProbs(r, p, Vp9FrameContext.MV_SIGN + i, 1);
            updateMvProbs(r, p, Vp9FrameContext.MV_CLASSES + i * 10, 10);
            updateMvProbs(r, p, Vp9FrameContext.MV_CLASS0 + i, 1);
            updateMvProbs(r, p, Vp9FrameContext.MV_BITS + i * 10, 10);
        }
        for (var i = 0; i < 2; i++) {
            for (var j = 0; j < 2; j++) {
                updateMvProbs(r, p, Vp9FrameContext.MV_CLASS0_FP + (i * 2 + j) * 3, 3);
            }
            updateMvProbs(r, p, Vp9FrameContext.MV_FP + i * 3, 3);
        }
        if (allowHighPrecisionMv) {
            for (var i = 0; i < 2; i++) {
                updateMvProbs(r, p, Vp9FrameContext.MV_CLASS0_HP + i, 1);
                updateMvProbs(r, p, Vp9FrameContext.MV_HP + i, 1);
            }
        }
    }

    /**
     * Reads updates of consecutive motion vector probabilities ({@code update_mv_probs}).
     *
     * @param r the compressed header decoder
     * @param p the probabilities
     * @param start the index of the first probability
     * @param count the number of probabilities
     */
    private static void updateMvProbs(Vp9BoolDecoder r, int[] p, int start, int count) {
        for (var i = 0; i < count; i++) {
            if (r.readBool(MV_UPDATE_PROB) != 0) {
                p[start + i] = (r.readLiteral(7) << 1) | 1;
            }
        }
    }

    /**
     * Reads an optional delta update of one probability ({@code vp9_diff_update_prob}).
     *
     * @param r the compressed header decoder
     * @param p the probabilities
     * @param index the probability's index
     */
    private static void diffUpdate(Vp9BoolDecoder r, int[] p, int index) {
        if (r.readBool(DIFF_UPDATE_PROB) == 0) {
            return;
        }
        int delta;
        if (r.readBit() == 0) {
            delta = r.readLiteral(4);
        } else if (r.readBit() == 0) {
            delta = r.readLiteral(4) + 16;
        } else if (r.readBit() == 0) {
            delta = r.readLiteral(5) + 32;
        } else {
            var v = r.readLiteral(7);
            delta = (v < 65 ? v : (v << 1) - 65 + r.readBit()) + 64;
        }
        p[index] = invRemapProb(delta, p[index]);
    }

    /**
     * Applies a decoded delta to a probability ({@code inv_remap_prob}).
     *
     * @param v the delta index
     * @param m the previous probability
     * @return the new probability
     */
    private static int invRemapProb(int v, int m) {
        v = Vp9ProbTables.INV_MAP_TABLE[v];
        m--;
        if ((m << 1) <= 255) {
            return 1 + invRecenterNonneg(v, m);
        }
        return 255 - invRecenterNonneg(v, 255 - 1 - m);
    }

    /**
     * Recenters a delta around a probability ({@code inv_recenter_nonneg}).
     *
     * @param v the delta
     * @param m the center
     * @return the recentered value
     */
    private static int invRecenterNonneg(int v, int m) {
        if (v > 2 * m) {
            return v;
        }
        return (v & 1) != 0 ? m - ((v + 1) >> 1) : m + (v >> 1);
    }

    /**
     * Computes the filter level of every segment, reference and mode
     * ({@code vp9_loop_filter_frame_init}).
     */
    private void loopFilterFrameInit() {
        var scale = 1 << (filterLevel >> 5);
        lf.setSharpness(sharpnessLevel);
        var lfLevels = lf.levels;
        for (var seg = 0; seg < 8; seg++) {
            var levelSeg = filterLevel;
            if (segEnabled && (segFeatureMask[seg] & 2) != 0) {
                var data = segFeatureData[seg * 4 + 1];
                levelSeg = Math.clamp(segAbsDelta ? data : filterLevel + data, 0, 63);
            }
            if (!modeRefDeltaEnabled) {
                Arrays.fill(lfLevels, seg * 8, seg * 8 + 8, levelSeg);
            } else {
                var intraLevel = levelSeg + refDeltas[0] * scale;
                lfLevels[seg * 8] = Math.clamp(intraLevel, 0, 63);
                for (var ref = 1; ref < 4; ref++) {
                    for (var mode = 0; mode < 2; mode++) {
                        var interLevel = levelSeg + refDeltas[ref] * scale + modeDeltas[mode] * scale;
                        lfLevels[(seg * 4 + ref) * 2 + mode] = Math.clamp(interLevel, 0, 63);
                    }
                }
            }
        }
    }

    /**
     * Locates the tiles of the frame and decodes them ({@code decode_tiles}, {@code decode_tiles_mt}).
     *
     * <p>With parallelism enabled and enough free helpers, every tile column is parsed on one
     * thread and reconstructed on another, and the threads that finish filter the superblock rows
     * the columns have reconstructed (see {@link #decodePipelined}). With several tile columns
     * and fewer helpers, the columns are decoded on helper threads, and loop filter workers start
     * filtering the superblock rows every column has decoded; with a single column, the calling
     * thread decodes while helpers filter behind it. Otherwise the calling thread decodes the
     * frame row by row across the columns and filters each row once the row below it is decoded
     * (see {@link #decodeRowByRow}). {@link #filterFrame()} completes the loop filter in every
     * case.
     *
     * @param data the packet
     * @param start the index of the first tile's size or data
     * @param end the index one past the frame's last byte
     * @return the index of the first byte after the last tile's data
     * @throws MediaException.Malformed if a tile size is corrupt or a tile is
     */
    private int decodeTiles(byte[] data, int start, int end) {
        var tileCols = 1 << log2TileCols;
        var tileRows = 1 << log2TileRows;
        var alignedCols = (miCols + 7) & ~7;
        for (var plane = 0; plane < 3; plane++) {
            Arrays.fill(aboveContext[plane], 0, 2 * alignedCols, (byte) 0);
        }
        Arrays.fill(aboveSegContext, 0, alignedCols, (byte) 0);
        var position = start;
        for (var row = 0; row < tileRows; row++) {
            for (var col = 0; col < tileCols; col++) {
                var last = row == tileRows - 1 && col == tileCols - 1;
                int size;
                if (!last) {
                    if (end - position < 4) {
                        throw new MediaException.Malformed("truncated packet or corrupt vp9 tile length");
                    }
                    var value = ((data[position] & 0xFFL) << 24) | ((data[position + 1] & 0xFF) << 16)
                            | ((data[position + 2] & 0xFF) << 8) | (data[position + 3] & 0xFF);
                    position += 4;
                    if (value > end - position) {
                        throw new MediaException.Malformed("truncated packet or corrupt vp9 tile size");
                    }
                    size = (int) value;
                } else {
                    size = end - position;
                }
                tileStarts[row * 64 + col] = position;
                tileSizes[row * 64 + col] = size;
                position += size;
            }
        }
        var workers = parallel && tileCols > 1 ? Math.min(tileCols, ParallelBands.THREADS) : 1;
        while (tileDecoders.size() < workers) {
            tileDecoders.add(new Vp9TileDecoder(this));
        }
        for (var decoder : tileDecoders) {
            decoder.counts.clear();
        }
        filteredSbRows = 0;
        for (var decoder : tileDecoders) {
            decoder.queue = null;
        }
        if (parallel && 2 * workers - 1 <= ParallelBands.available()) {
            return decodePipelined(data, tileCols, workers);
        }
        if (workers == 1 && tileCols == 1 && parallel && filterLevel != 0) {
            var front = decodeProgress == null ? decodeProgress = new Vp9Wavefront(64, false) : decodeProgress;
            front.reset(1);
            var decoder = tileDecoders.getFirst();
            decoder.progress = front;
            if (startFilterWorkers(true)) {
                try {
                    decodeTileColumns(decoder, data, 0, 1);
                } catch (RuntimeException | Error exception) {
                    wavefront.abort();
                    joinFilterWorkers(exception);
                }
                return decoder.reader.findEnd();
            }
        }
        if (workers == 1) {
            return decodeRowByRow(data, tileCols, tileRows);
        }
        Vp9Wavefront front = null;
        if (filterLevel != 0) {
            if (decodeProgress == null) {
                decodeProgress = new Vp9Wavefront(64, false);
            }
            front = decodeProgress;
            front.reset(tileCols);
        }
        var pending = new ArrayList<ParallelBands.Pending<Void>>(workers - 1);
        var overlap = false;
        Throwable failure = null;
        try {
            for (var w = 1; w < workers; w++) {
                var decoder = tileDecoders.get(w);
                var first = w;
                decoder.progress = front;
                pending.add(ParallelBands.start(() -> decodeTileColumns(decoder, data, first, workers)));
            }
            overlap = front != null && startFilterWorkers(true);
            var decoder = tileDecoders.getFirst();
            decoder.progress = front;
            decodeTileColumns(decoder, data, 0, workers);
        } catch (RuntimeException | Error exception) {
            failure = exception;
        }
        for (var task : pending) {
            try {
                task.join();
            } catch (RuntimeException | Error exception) {
                if (failure == null) {
                    failure = exception;
                }
            }
        }
        if (failure != null) {
            if (overlap) {
                front.abort();
                wavefront.abort();
                joinFilterWorkers(failure);
            }
            rethrow(failure);
        }
        var lastWorker = tileDecoders.get((tileCols - 1) % workers);
        return lastWorker.reader.findEnd();
    }

    /**
     * Decodes the tile columns with two threads per column: one parses the column's superblock
     * rows into a {@link Vp9ReconQueue} and one reconstructs them, and then joins the loop filter.
     *
     * <p>The parsing threads are the calling thread and {@code workers - 1} helpers. Every
     * reconstructing thread publishes the rows it finishes to the decode progress wavefront, which
     * the loop filter waits for, and filters rows itself once its column is done. The caller joins
     * the loop filter after parsing its column, so when this method returns the frame is filtered.
     *
     * @param data the packet
     * @param tileCols the number of tile columns
     * @param workers the number of parsing threads, which is also the number of reconstructing
     * threads
     * @return the index of the first byte after the last tile's data
     * @throws MediaException.Malformed if a tile is corrupt
     */
    private int decodePipelined(byte[] data, int tileCols, int workers) {
        var sbRows = (miRows + 7) >> 3;
        var frame = curFrame;
        Vp9Wavefront front = null;
        Vp9Wavefront filterFront = null;
        if (filterLevel != 0) {
            if (decodeProgress == null) {
                decodeProgress = new Vp9Wavefront(64, false);
            }
            front = decodeProgress;
            front.reset(tileCols);
            if (wavefront == null || wavefront.capacity() < sbRows) {
                wavefront = new Vp9Wavefront(sbRows, false);
            }
            wavefront.reset(sbRows);
            filterFront = wavefront;
            filterTickets.set(0);
            while (loopFilters.size() < Math.max(2 * workers, ParallelBands.THREADS)) {
                loopFilters.add(new Vp9LoopFilter(lf));
            }
        }
        for (var w = 0; w < workers; w++) {
            var parser = tileDecoders.get(w);
            if (parser.spareQueue == null) {
                parser.spareQueue = new Vp9ReconQueue();
                parser.recon = new Vp9TileDecoder(this);
            }
            parser.queue = parser.spareQueue;
            parser.queue.reset();
            parser.progress = null;
            parser.recon.progress = front;
        }
        var pending = new ArrayList<ParallelBands.Pending<Void>>(2 * workers - 1);
        Throwable failure = null;
        try {
            for (var w = 0; w < workers; w++) {
                var parser = tileDecoders.get(w);
                var first = w;
                var filter = filterLevel != 0 ? loopFilters.get(w + 1) : null;
                var rows = filterFront;
                pending.add(ParallelBands.start(() -> reconstructColumns(parser, first, workers, filter, frame,
                        sbRows, rows)));
            }
            for (var w = 1; w < workers; w++) {
                var parser = tileDecoders.get(w);
                var first = w;
                var rows = filterFront;
                var filter = filterLevel != 0 ? loopFilters.get(workers + w) : null;
                pending.add(ParallelBands.start(() -> {
                    parseColumns(parser, data, first, workers, rows);
                    if (rows != null) {
                        joinFilter(parser, filter, frame, sbRows, rows);
                    }
                }));
            }
            if (filterFront != null) {
                var spare = Math.min(Math.min(sbRows - 1, ParallelBands.THREADS - 2 * workers),
                        ParallelBands.available());
                for (var k = 0; k < spare; k++) {
                    var parser = tileDecoders.getFirst();
                    var filter = loopFilters.get(2 * workers + k);
                    var rows = filterFront;
                    pending.add(ParallelBands.start(() -> joinFilter(parser, filter, frame, sbRows, rows)));
                }
            }
            parseColumns(tileDecoders.getFirst(), data, 0, workers, filterFront);
            if (filterFront != null) {
                joinFilter(tileDecoders.getFirst(), loopFilters.getFirst(), frame, sbRows, filterFront);
            }
        } catch (RuntimeException | Error exception) {
            failure = exception;
        }
        for (var task : pending) {
            try {
                task.join();
            } catch (RuntimeException | Error exception) {
                if (failure == null || failure instanceof CancellationException
                        && !(exception instanceof CancellationException)) {
                    failure = exception;
                }
            }
        }
        rethrow(failure);
        if (filterFront != null) {
            filteredSbRows = sbRows;
        }
        var lastWorker = tileDecoders.get((tileCols - 1) % workers);
        return lastWorker.reader.findEnd();
    }

    /**
     * Parses the tile columns of one parsing decoder into its queue.
     *
     * <p>A failure aborts the queue and the wavefronts, so that no thread keeps waiting for work
     * that will never arrive.
     *
     * @param parser the parsing decoder
     * @param data the packet
     * @param first the first tile column of the decoder
     * @param workers the number of parsing decoders, the distance between their columns
     * @param filter the loop filter wavefront, or {@code null} when the frame is not filtered
     */
    private void parseColumns(Vp9TileDecoder parser, byte[] data, int first, int workers, Vp9Wavefront filter) {
        var tileCols = 1 << log2TileCols;
        var tileRows = 1 << log2TileRows;
        try {
            for (var col = first; col < tileCols; col += workers) {
                for (var row = 0; row < tileRows; row++) {
                    parser.decodeTile(data, tileStarts[row * 64 + col], tileSizes[row * 64 + col], row, col);
                }
            }
        } catch (RuntimeException | Error exception) {
            abortPipeline(parser, filter);
            throw exception;
        }
    }

    /**
     * Reconstructs the tile columns of one parsing decoder from its queue and then filters rows
     * until none is left.
     *
     * @param parser the parsing decoder whose queue is consumed
     * @param first the first tile column of the decoder
     * @param workers the number of parsing decoders, the distance between their columns
     * @param filter the thread's loop filter, or {@code null} when the frame is not filtered
     * @param frame the frame being reconstructed
     * @param sbRows the number of superblock rows
     * @param rows the loop filter wavefront, or {@code null} when the frame is not filtered
     */
    private void reconstructColumns(Vp9TileDecoder parser, int first, int workers, Vp9LoopFilter filter,
                                    Vp9Frame frame, int sbRows, Vp9Wavefront rows) {
        var tileCols = 1 << log2TileCols;
        var tileRows = 1 << log2TileRows;
        var reconstructor = parser.recon;
        try {
            for (var col = first; col < tileCols; col += workers) {
                for (var row = 0; row < tileRows; row++) {
                    reconstructor.startReconstruction(row, col);
                    reconstructor.reconstructTile(parser.queue);
                }
            }
            if (rows != null) {
                filterRows(filter, frame, sbRows, rows, true);
            }
        } catch (RuntimeException | Error exception) {
            abortPipeline(parser, rows);
            throw exception;
        }
    }

    /**
     * Filters loop filter rows on a thread that has finished its part of a pipelined decode,
     * aborting the pipeline on failure.
     *
     * @param parser the parsing decoder of the thread
     * @param filter the thread's loop filter
     * @param frame the frame being filtered
     * @param sbRows the number of superblock rows
     * @param rows the loop filter wavefront
     */
    private void joinFilter(Vp9TileDecoder parser, Vp9LoopFilter filter, Vp9Frame frame, int sbRows,
                            Vp9Wavefront rows) {
        try {
            filterRows(filter, frame, sbRows, rows, true);
        } catch (RuntimeException | Error exception) {
            abortPipeline(parser, rows);
            throw exception;
        }
    }

    /**
     * Wakes every thread of the pipelined decode after a failure.
     *
     * @param parser the decoder whose queue to abort
     * @param filter the loop filter wavefront, or {@code null}
     */
    private void abortPipeline(Vp9TileDecoder parser, Vp9Wavefront filter) {
        parser.queue.abort();
        if (filter != null) {
            decodeProgress.abort();
            filter.abort();
        }
    }

    /**
     * Decodes the tiles on the calling thread, superblock row by superblock row across the tile
     * columns, one tile decoder per column, and applies the loop filter to each row once the row
     * below it is decoded, while its samples are still cached; {@link #filterFrame} filters the
     * remaining rows.
     *
     * <p>A row is filtered only after the row below it because intra prediction reads the
     * unfiltered samples at the bottom of the row above, which the filter of that row changes.
     *
     * @param data the packet
     * @param tileCols the number of tile columns
     * @param tileRows the number of tile rows
     * @return the index of the first byte after the last tile's data
     * @throws MediaException.Malformed if a tile is corrupt
     */
    private int decodeRowByRow(byte[] data, int tileCols, int tileRows) {
        while (tileDecoders.size() < tileCols) {
            var decoder = new Vp9TileDecoder(this);
            decoder.counts.clear();
            tileDecoders.add(decoder);
        }
        var frame = curFrame;
        var filter = loopFilters.getFirst();
        for (var row = 0; row < tileRows; row++) {
            for (var col = 0; col < tileCols; col++) {
                var decoder = tileDecoders.get(col);
                decoder.progress = null;
                decoder.startTile(data, tileStarts[row * 64 + col], tileSizes[row * 64 + col], row, col);
            }
            var rowStart = tileOffset(row, miRows, log2TileRows);
            var rowEnd = tileOffset(row + 1, miRows, log2TileRows);
            for (var miRow = rowStart; miRow < rowEnd; miRow += 8) {
                for (var col = 0; col < tileCols; col++) {
                    tileDecoders.get(col).decodeRow(miRow);
                }
                if (filterLevel != 0 && miRow > 0) {
                    filter.filterRow(frame, miRow - 8, null);
                    filteredSbRows = miRow >> 3;
                }
            }
        }
        return tileDecoders.get(tileCols - 1).reader.findEnd();
    }

    /**
     * Decodes the tile columns of one tile decoder, every tile row of each; the decoder publishes
     * the decoded superblock rows of each column to its progress wavefront, if it has one.
     *
     * <p>A failure aborts the progress wavefront, so that no loop filter worker keeps waiting for
     * rows that will never be decoded.
     *
     * @param decoder the tile decoder
     * @param data the packet
     * @param first the first tile column of the decoder
     * @param workers the number of tile decoders, the distance between their columns
     * @throws MediaException.Malformed if a tile is corrupt
     */
    private void decodeTileColumns(Vp9TileDecoder decoder, byte[] data, int first, int workers) {
        var tileCols = 1 << log2TileCols;
        var tileRows = 1 << log2TileRows;
        try {
            for (var col = first; col < tileCols; col += workers) {
                for (var row = 0; row < tileRows; row++) {
                    decoder.decodeTile(data, tileStarts[row * 64 + col], tileSizes[row * 64 + col], row, col);
                }
            }
        } catch (RuntimeException | Error exception) {
            if (decoder.progress != null) {
                decoder.progress.abort();
            }
            throw exception;
        }
    }

    /**
     * Applies the loop filter to the whole frame, as a wavefront of superblock rows on the
     * calling thread and helper threads when parallelism is enabled and helpers are free.
     *
     * <p>When the tile columns were decoded in parallel, workers may already be filtering the
     * rows the tiles have finished; the calling thread joins them, with more workers if helpers
     * have become free. Workers claim rows in order from a shared counter, so every row a worker
     * waits for has been claimed by a running worker; before returning, the calling thread
     * cancels the workers no helper has claimed and waits for the others. A failing worker aborts
     * the wavefront, and the first failure is rethrown once every worker has stopped.
     *
     * @implNote This implementation starts workers with {@link ParallelBands#start(Runnable)},
     * at most as many as {@link ParallelBands#available()} reports free and one fewer
     * than the superblock rows; the result does not depend on their number.
     */
    private void filterFrame() {
        var frame = curFrame;
        var sbRows = (miRows + 7) >> 3;
        if (filteredSbRows > 0 || filterWorkers.isEmpty() && !startFilterWorkers(false)) {
            var filter = loopFilters.getFirst();
            for (var miRow = filteredSbRows << 3; miRow < miRows; miRow += 8) {
                filter.filterRow(frame, miRow, null);
            }
            return;
        }
        if (filterWorkers.size() < ParallelBands.THREADS - 1) {
            addFilterWorkers(Math.min(sbRows - 1 - filterWorkers.size(), ParallelBands.available()), false);
        }
        Throwable failure = null;
        try {
            filterRows(loopFilters.getFirst(), frame, sbRows, wavefront, false);
        } catch (RuntimeException | Error exception) {
            wavefront.abort();
            failure = exception;
        }
        joinFilterWorkers(failure);
    }

    /**
     * Starts loop filter workers for the current frame when parallelism is enabled and helpers
     * are free, resetting the wavefront and the row counter.
     *
     * @param afterDecode whether the workers wait for the tile decoders to publish each row
     * (see {@link #filterRows}) because the tiles are still being decoded
     * @return whether any worker was started
     */
    private boolean startFilterWorkers(boolean afterDecode) {
        var sbRows = (miRows + 7) >> 3;
        var reserved = afterDecode ? Math.min(1 << log2TileCols, ParallelBands.THREADS) : 1;
        var helpers = parallel ? Math.min(Math.min(sbRows - 1, ParallelBands.THREADS - reserved),
                ParallelBands.available() - (reserved - 1)) : 0;
        if (helpers <= 0) {
            return false;
        }
        if (wavefront == null || wavefront.capacity() < sbRows) {
            wavefront = new Vp9Wavefront(sbRows, false);
        }
        wavefront.reset(sbRows);
        filterTickets.set(0);
        addFilterWorkers(helpers, afterDecode);
        return true;
    }

    /**
     * Starts loop filter workers, each with a loop filter of its own.
     *
     * @param count the number of workers to start
     * @param afterDecode whether the workers wait for the tile decoders to publish each row
     */
    private void addFilterWorkers(int count, boolean afterDecode) {
        var frame = curFrame;
        var sbRows = (miRows + 7) >> 3;
        var front = wavefront;
        for (var i = 0; i < count; i++) {
            var index = filterWorkers.size() + 1;
            while (loopFilters.size() <= index) {
                loopFilters.add(new Vp9LoopFilter(lf));
            }
            var filter = loopFilters.get(index);
            filterWorkers.add(ParallelBands.start(() -> filterRows(filter, frame, sbRows, front, afterDecode)));
        }
    }

    /**
     * Cancels the loop filter workers no helper has claimed, waits for the others, forgets them
     * and rethrows the failure to report.
     *
     * <p>A cancellation caused by another worker's failure is only reported when nothing else
     * failed.
     *
     * @param failure the failure of the calling thread's own part, or {@code null}
     * @throws RuntimeException the failure to report
     * @throws Error the failure to report
     */
    private void joinFilterWorkers(Throwable failure) {
        for (var worker : filterWorkers) {
            if (worker.tryCancel()) {
                continue;
            }
            try {
                worker.join();
            } catch (RuntimeException | Error exception) {
                if (failure == null || failure instanceof CancellationException
                        && !(exception instanceof CancellationException)) {
                    failure = exception;
                }
            }
        }
        filterWorkers.clear();
        rethrow(failure);
    }

    /**
     * Rethrows a failure unchanged when unchecked, wrapped otherwise.
     *
     * @param failure the failure, or {@code null} for none
     */
    private static void rethrow(Throwable failure) {
        switch (failure) {
            case null -> {
            }
            case RuntimeException runtime -> throw runtime;
            case Error error -> throw error;
            default -> throw new IllegalStateException(failure);
        }
    }

    /**
     * Filters superblock rows claimed from the shared counter until none is left, as one worker
     * of the loop filter wavefront.
     *
     * <p>A worker started while the tiles are being decoded filters row {@code r} only once every
     * tile column has decoded rows {@code r} and {@code r + 1}: intra prediction of a row reads the
     * unfiltered samples at the bottom of the row above, which the filter of that row changes.
     *
     * @param filter the worker's loop filter
     * @param frame the frame being filtered
     * @param sbRows the number of superblock rows
     * @param front the wavefront
     * @param afterDecode whether to wait for the tile decoders to publish each row
     * @throws CancellationException if another worker or a tile decoder failed
     */
    private void filterRows(Vp9LoopFilter filter, Vp9Frame frame, int sbRows, Vp9Wavefront front,
                            boolean afterDecode) {
        var tileCols = 1 << log2TileCols;
        try {
            for (var row = filterTickets.getAndIncrement(); row < sbRows; row = filterTickets.getAndIncrement()) {
                if (afterDecode) {
                    var needed = Math.min(row + 2, sbRows);
                    for (var col = 0; col < tileCols; col++) {
                        decodeProgress.awaitProgress(col, needed);
                    }
                }
                filter.filterRow(frame, row << 3, front);
            }
        } catch (RuntimeException | Error exception) {
            front.abort();
            throw exception;
        }
    }
}
