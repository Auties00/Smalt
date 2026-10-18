/* Ports libvpx (vp8/decoder/decodeframe.c, decodemv.c, detokenize.c, onyxd_if.c, vp8/common/reconinter.c,
   vp8_loopfilter.c, findnearmv.h). Copyright (c) 2010 The WebM project authors; BSD 3-Clause License.
   Ports libwebp 1.6.0 (src/dec/vp8_dec.c, ParsePartitions). Copyright (c) 2010, Google Inc.; BSD 3-Clause License.
   Modified for Smalt. */
package com.github.auties00.smalt.video.vp8.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.internal.task.ParallelBands;

import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

/**
 * Decodes a sequence of VP8 frames (RFC 6386), key frames and inter frames, keeping the
 * reference frames and entropy state between calls.
 *
 * <p>{@link #decode(byte[], int, int)} decodes one compressed frame. The decoded picture of the
 * last frame is available from {@link #copyPicture(YuvPicture)} until the next call. A frame that
 * fails to decode leaves the reference frames untouched and makes the decoder reject inter frames
 * until the next key frame, because their references would be wrong. Decoded frames are
 * bit-exact with libvpx's.
 *
 * <p>Instances are not thread-safe. Allowed to ({@link #setThreads}), a decoder runs the
 * parsing, reconstruction and loop filtering of a frame as a pipeline on threads of the module's
 * shared {@link ParallelBands} helpers, with the same output. Every frame starts at a
 * {@link ParallelBands#checkpoint()}, so a decoder running on a virtual thread shares its carrier
 * with other virtual threads between frames.
 *
 * @implNote This implementation pipelines by stage in libvpx's single-threaded decoding order
 * (the loop filter runs one macroblock row behind reconstruction), rather than splitting the
 * frame by rows as libvpx's multi-threaded decoder does, so every read of a neighbor's state
 * happens in the order libvpx makes it. In key-frame only mode (WebP), partition sizes are
 * validated as libwebp validates them.
 */
public final class Vp8FrameDecoder {
    /**
     * The whole-block DC prediction mode and the value of an unset mode.
     */
    static final int DC_PRED = 0;

    /**
     * The whole-block vertical prediction mode.
     */
    static final int V_PRED = 1;

    /**
     * The whole-block horizontal prediction mode.
     */
    static final int H_PRED = 2;

    /**
     * The whole-block TrueMotion prediction mode.
     */
    static final int TM_PRED = 3;

    /**
     * The luma mode that predicts each 4x4 subblock separately.
     */
    static final int B_PRED = 4;

    /**
     * The inter mode using the nearest neighboring motion vector.
     */
    static final int NEARESTMV = 5;

    /**
     * The inter mode using the second nearest neighboring motion vector.
     */
    static final int NEARMV = 6;

    /**
     * The inter mode with a zero motion vector.
     */
    static final int ZEROMV = 7;

    /**
     * The inter mode with an explicitly coded motion vector.
     */
    static final int NEWMV = 8;

    /**
     * The inter mode that splits the macroblock into partitions with their own motion vectors.
     */
    static final int SPLITMV = 9;

    /**
     * The subblock DC prediction mode.
     */
    static final int B_DC_PRED = 0;

    /**
     * The subblock TrueMotion prediction mode.
     */
    static final int B_TM_PRED = 1;

    /**
     * The subblock vertical prediction mode.
     */
    static final int B_VE_PRED = 2;

    /**
     * The subblock horizontal prediction mode.
     */
    static final int B_HE_PRED = 3;

    /**
     * The subblock down-left prediction mode.
     */
    static final int B_LD_PRED = 4;

    /**
     * The subblock down-right prediction mode.
     */
    static final int B_RD_PRED = 5;

    /**
     * The subblock vertical-right prediction mode.
     */
    static final int B_VR_PRED = 6;

    /**
     * The subblock vertical-left prediction mode.
     */
    static final int B_VL_PRED = 7;

    /**
     * The subblock horizontal-down prediction mode.
     */
    static final int B_HD_PRED = 8;

    /**
     * The subblock horizontal-up prediction mode.
     */
    static final int B_HU_PRED = 9;

    /**
     * The reference index of intra-coded macroblocks.
     */
    static final int INTRA_FRAME = 0;

    /**
     * The reference index of the last frame.
     */
    static final int LAST_FRAME = 1;

    /**
     * The reference index of the golden frame.
     */
    static final int GOLDEN_FRAME = 2;

    /**
     * The reference index of the alternate reference frame.
     */
    static final int ALTREF_FRAME = 3;

    /**
     * The motion vector margin, in 1/8 pixel, outside which a vector is clamped or flagged.
     */
    private static final int MV_MARGIN = 16 << 3;

    /**
     * The number of frame buffers: the frame being decoded plus three references.
     */
    private static final int NUM_BUFFERS = 4;

    /**
     * The loop filter mode class of each macroblock mode: 0 for {@code B_PRED}, 1 for the other
     * intra modes and {@code ZEROMV}, 2 for the other single-vector modes, 3 for {@code SPLITMV}.
     */
    private static final int[] MODE_LF_CLASS = {1, 1, 1, 1, 0, 2, 2, 1, 2, 3};

    /**
     * The key-frame subblock context implied by each whole-block luma mode.
     */
    private static final int[] BMODE_OF_MODE = {B_DC_PRED, B_VE_PRED, B_HE_PRED, B_TM_PRED, B_DC_PRED};

    /**
     * The offset of the first token probability of each block type and scan position, flattened
     * as {@code [type 4][position 17]}.
     */
    private static final int[] BAND_OFFSET = new int[4 * 17];

    static {
        for (var t = 0; t < 4; t++) {
            for (var n = 0; n < 17; n++) {
                BAND_OFFSET[t * 17 + n] = (t * 8 + Vp8Tables.BANDS[n]) * 33;
            }
        }
    }

    /**
     * The bounds checked before allocating.
     */
    private final MediaLimits limits;

    /**
     * The pixel kernels.
     */
    private final Vp8Dsp dsp = Vp8Dsp.INSTANCE;

    /**
     * Whether only key frames are accepted, as for WebP images.
     */
    private final boolean keyFramesOnly;

    /**
     * The fewest macroblock rows for which parsing, reconstruction, and the loop filter run as a
     * pipeline.
     */
    private static final int PIPELINE_ROWS = 4;

    /**
     * The longest time, in nanoseconds, a pipeline stage sleeps before rechecking the progress of
     * the stage it waits for when no wake-up arrives.
     */
    private static final long WAIT_NANOS = 100_000;

    /**
     * The number of times a stage that waits checks the progress it waits for, spinning, before it
     * parks: a wait of a few macroblocks costs less than waking a parked thread.
     */
    private static final int SPINS = 400;

    /**
     * The most reconstruction workers of a pipelined frame.
     */
    private static final int MAX_WORKERS = 7;

    /**
     * The fewest macroblock rows per reconstruction worker of a pipelined frame: a worker that
     * has only a few rows spends most of its time waiting for the row above.
     */
    private static final int ROWS_PER_WORKER = 4;

    /**
     * The most coefficients one macroblock stores, 25 blocks of 16.
     */
    private static final int MB_COEFFS = 25 * 16;

    /**
     * The coefficient blocks per macroblock the block store of a pipelined frame starts with; it
     * grows when a frame needs more.
     */
    private static final int INITIAL_BLOCKS_PER_MB = 4;

    /**
     * The first partition decoder.
     */
    private final BoolDecoder header = new BoolDecoder();

    /**
     * The token partition decoders.
     */
    private final BoolDecoder[] partitions = new BoolDecoder[8];

    /**
     * The number of token partitions of the current frame.
     */
    private int numPartitions;

    /**
     * Whether a key frame has been decoded and no corrupt frame followed it.
     */
    private boolean haveReferences;

    /**
     * The displayed width of the current sequence.
     */
    private int width;

    /**
     * The displayed height of the current sequence.
     */
    private int height;

    /**
     * The horizontal scale code of the last key frame.
     */
    private int horizontalScale;

    /**
     * The vertical scale code of the last key frame.
     */
    private int verticalScale;

    /**
     * The width in macroblocks.
     */
    private int mbCols;

    /**
     * The height in macroblocks.
     */
    private int mbRows;

    /**
     * The frame buffers.
     */
    private final FrameBuffer[] buffers = new FrameBuffer[NUM_BUFFERS];

    /**
     * The number of references to each frame buffer.
     */
    private final int[] refCount = new int[NUM_BUFFERS];

    /**
     * The buffer holding each reference frame, indexed by {@code LAST_FRAME} to
     * {@code ALTREF_FRAME}; index 0 holds the frame being decoded.
     */
    private final int[] refIndex = new int[4];

    /**
     * The sign bias of each reference frame.
     */
    private final int[] signBias = new int[4];

    /**
     * The token probabilities, {@code [type 4][band 8][context 3][node 11]}.
     */
    private final int[] coefProbs = new int[1056];

    /**
     * The inter-frame luma mode probabilities.
     */
    private final int[] ymodeProbs = new int[4];

    /**
     * The inter-frame chroma mode probabilities.
     */
    private final int[] uvModeProbs = new int[3];

    /**
     * The motion vector probabilities, row component then column component.
     */
    private final int[] mvProbs = new int[38];

    /**
     * The saved token probabilities, restored after a frame that does not refresh them.
     */
    private final int[] savedCoefProbs = new int[1056];

    /**
     * The saved luma mode probabilities.
     */
    private final int[] savedYmodeProbs = new int[4];

    /**
     * The saved chroma mode probabilities.
     */
    private final int[] savedUvModeProbs = new int[3];

    /**
     * The saved motion vector probabilities.
     */
    private final int[] savedMvProbs = new int[38];

    /**
     * Whether segmentation is enabled.
     */
    private boolean segmentationEnabled;

    /**
     * Whether the current frame codes the segment map.
     */
    private boolean updateSegmentMap;

    /**
     * Whether segment values are absolute rather than deltas.
     */
    private boolean segmentAbsolute;

    /**
     * The segment quantizer values.
     */
    private final int[] segmentQuant = new int[4];

    /**
     * The segment loop filter values.
     */
    private final int[] segmentFilter = new int[4];

    /**
     * The segment map tree probabilities.
     */
    private final int[] segmentTreeProbs = new int[3];

    /**
     * Whether the simple loop filter is selected.
     */
    private boolean simpleFilter;

    /**
     * The frame loop filter level.
     */
    private int filterLevel;

    /**
     * The loop filter sharpness.
     */
    private int sharpness;

    /**
     * Whether loop filter deltas are enabled.
     */
    private boolean filterDeltasEnabled;

    /**
     * The loop filter deltas per reference frame.
     */
    private final int[] refFilterDeltas = new int[4];

    /**
     * The loop filter deltas per mode class.
     */
    private final int[] modeFilterDeltas = new int[4];

    /**
     * The filter level of each segment, reference, and mode class, {@code [4][4][4]}.
     */
    private final int[] filterLevels = new int[64];

    /**
     * The quantizer deltas: Y1 DC, Y2 DC, Y2 AC, UV DC, UV AC.
     */
    private final int[] quantDeltas = new int[5];

    /**
     * The dequantization factors of each segment: Y1 DC, Y1 AC, Y2 DC, Y2 AC, UV DC, UV AC.
     */
    private final int[] dequant = new int[4 * 6];

    /**
     * The frame-level quantizer index.
     */
    private int baseQuant;

    /**
     * Whether the current frame is a key frame.
     */
    private boolean keyFrame;

    /**
     * The bitstream version of the current frame.
     */
    private int version;

    /**
     * Whether the current frame is displayed.
     */
    private boolean showFrame;

    /**
     * The picture the next frame is written into row by row as its rows become final, or
     * {@code null} when the frame is not copied while it decodes.
     */
    private YuvPicture output;

    /**
     * The picture {@link #setOutput} offered for reuse, or {@code null}.
     */
    private YuvPicture outputOffer;

    /**
     * Whether {@link #setOutput} was called for the frame being decoded.
     */
    private boolean outputRequested;

    /**
     * Whether the per-macroblock skip flag is coded.
     */
    private boolean coeffSkipEnabled;

    /**
     * The probability that a macroblock is not skipped.
     */
    private int probSkipFalse;

    /**
     * The probability that a macroblock is intra coded.
     */
    private int probIntra;

    /**
     * The probability that an inter macroblock uses the last frame.
     */
    private int probLast;

    /**
     * The probability that a non-last inter macroblock uses the golden frame.
     */
    private int probGolden;

    /**
     * The mode info stride: {@code mbCols + 1}, the extra column being the zeroed border.
     */
    private int miStride;

    /**
     * The luma mode of each macroblock; index {@code (row + 1) * miStride + col + 1}.
     */
    private byte[] mbMode = new byte[0];

    /**
     * The chroma mode of each macroblock.
     */
    private byte[] mbUvMode = new byte[0];

    /**
     * The reference frame of each macroblock.
     */
    private byte[] mbRef = new byte[0];

    /**
     * The segment of each macroblock, persistent across frames.
     */
    private byte[] mbSegment = new byte[0];

    /**
     * Whether each macroblock has no coefficients.
     */
    private byte[] mbSkip = new byte[0];

    /**
     * Whether each macroblock's motion vectors may reach outside the prediction border.
     */
    private byte[] mbNeedClamp = new byte[0];

    /**
     * The split configuration of each {@code SPLITMV} macroblock.
     */
    private byte[] mbPartitioning = new byte[0];

    /**
     * The motion vector of each macroblock, packed as {@code (row << 16) | (col & 0xFFFF)}.
     */
    private int[] mbMv = new int[0];

    /**
     * The 16 subblock modes of each macroblock.
     */
    private byte[] bModes = new byte[0];

    /**
     * The 16 subblock motion vectors of each macroblock, packed like {@link #mbMv}.
     */
    private int[] bMvs = new int[0];

    /**
     * The above nonzero contexts, 9 per macroblock column (Y 4, U 2, V 2, Y2 1).
     */
    private byte[] aboveContext = new byte[0];

    /**
     * The left nonzero contexts of the current row.
     */
    private final byte[] leftContext = new byte[9];

    /**
     * The coefficient blocks of the current macroblock when one thread parses and reconstructs it,
     * packed as {@link #decodeTokens} stores them; all zero between macroblocks.
     */
    private final short[] coeffs = new short[MB_COEFFS];

    /**
     * The end-of-block position of each block of the current macroblock when one thread parses and
     * reconstructs it, by block number.
     */
    private final byte[] eobs = new byte[25];

    /**
     * The packed coefficient blocks of every macroblock of a pipelined frame, stored by the parse
     * stage and consumed, zeroing them, by reconstruction; replaced by a larger copy when a frame
     * needs more room, which is why it is read again after every wait.
     */
    private volatile short[] blockStore = new short[0];

    /**
     * Whether {@link #blockStore} may hold coefficients that reconstruction did not consume, after
     * it grew while being read or after a frame failed.
     */
    private boolean blockStoreDirty;

    /**
     * The index in {@link #blockStore} of the first block of each macroblock of a pipelined frame,
     * in raster order.
     */
    private int[] blockStart = new int[0];

    /**
     * The end-of-block position of every block of a pipelined frame, 25 per macroblock in raster
     * order.
     */
    private byte[] blockEobs = new byte[0];

    /**
     * The most threads a frame may use: 1 for the calling thread alone, 0 for no limit.
     */
    private int threads = 1;

    /**
     * The worker of the calling thread.
     */
    private final Worker main = new Worker();

    /**
     * The reconstruction workers of pipelined frames, created on first use.
     */
    private Worker[] workers = new Worker[0];

    /**
     * The distance in 1/8 pixel from the current macroblock to the left frame edge (non-positive).
     */
    private int mbToLeftEdge;

    /**
     * The distance in 1/8 pixel from the current macroblock to the right frame edge.
     */
    private int mbToRightEdge;

    /**
     * The distance in 1/8 pixel from the current macroblock to the top frame edge (non-positive).
     */
    private int mbToTopEdge;

    /**
     * The distance in 1/8 pixel from the current macroblock to the bottom frame edge.
     */
    private int mbToBottomEdge;

    /**
     * Creates a decoder that decodes on the calling thread until {@link #setThreads} allows more.
     *
     * @param limits the bounds checked before allocating
     * @param keyFramesOnly whether inter frames are rejected (WebP images)
     */
    public Vp8FrameDecoder(MediaLimits limits, boolean keyFramesOnly) {
        this.limits = Objects.requireNonNull(limits, "limits");
        this.keyFramesOnly = keyFramesOnly;
        for (var i = 0; i < partitions.length; i++) {
            partitions[i] = new BoolDecoder();
        }
    }

    /**
     * Decodes one key frame, as {@link Vp8#decodeKeyFrame}
     * does, optionally pipelining parsing, reconstruction, and the loop filter on the shared
     * helpers.
     *
     * @param data     the array holding the frame
     * @param offset   the index of the frame's first byte
     * @param length   the frame size in bytes
     * @param limits   the bounds checked before allocating
     * @param parallel whether the frame may be decoded as a pipeline
     * @return the picture, cropped to the frame size
     * @throws MediaException.Malformed     if the frame is not a key frame, is truncated, or is
     *                                      corrupt
     * @throws MediaException.Unsupported   if the frame is flagged as not displayable
     * @throws MediaException.LimitExceeded if the frame exceeds {@code limits}
     */
    public static YuvPicture decodeKeyFrame(byte[] data, int offset, int length, MediaLimits limits,
                                            boolean parallel) {
        if (length == 0) {
            throw new MediaException.Malformed("empty vp8 frame");
        }
        try {
            if ((data[offset] & 1) != 0) {
                throw new MediaException.Malformed("vp8 frame is not a key frame");
            }
            var decoder = new Vp8FrameDecoder(limits, true);
            decoder.setThreads(parallel ? 0 : 1);
            decoder.decode(data, offset, length);
            return decoder.copyPicture(null);
        } catch (MediaException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new MediaException.Malformed("corrupt vp8 key frame", exception);
        }
    }

    /**
     * Sets the most threads a frame may use; with more than one, parsing, reconstruction, and the
     * loop filter may run as a pipeline on the module's shared helpers.
     *
     * @param threads the thread count of the decoder options: 1 to decode on the calling thread,
     * 0 for no limit
     */
    public void setThreads(int threads) {
        this.threads = threads;
    }

    /**
     * Returns the horizontal scale code of the last key frame (RFC 6386 section 9.1).
     *
     * @return the code in {@code [0, 3]}; the decoder ignores it for output
     */
    public int horizontalScale() {
        return horizontalScale;
    }

    /**
     * Returns the vertical scale code of the last key frame (RFC 6386 section 9.1).
     *
     * @return the code in {@code [0, 3]}; the decoder ignores it for output
     */
    public int verticalScale() {
        return verticalScale;
    }

    /**
     * Returns whether the last decoded frame is to be displayed.
     *
     * @return the frame header's {@code show_frame} flag
     */
    public boolean showFrame() {
        return showFrame;
    }

    /**
     * Returns whether the last decoded frame is a key frame.
     *
     * @return {@code true} for a key frame
     */
    public boolean keyFrame() {
        return keyFrame;
    }

    /**
     * Decodes one compressed frame.
     *
     * <p>An empty frame is accepted and decodes nothing: it marks a dropped frame and leaves
     * {@link #showFrame()} {@code false}.
     *
     * @param data   the array holding the frame
     * @param offset the index of the frame's first byte
     * @param length the frame size in bytes
     * @throws MediaException.Malformed     if the frame is corrupt, truncated, or an inter frame
     *                                      without valid references
     * @throws MediaException.LimitExceeded if a key frame's size exceeds the limits
     */
    public void decode(byte[] data, int offset, int length) {
        Objects.checkFromIndexSize(offset, length, data.length);
        ParallelBands.checkpoint();
        showFrame = false;
        output = null;
        if (length == 0) {
            outputRequested = false;
            return;
        }
        try {
            swapBuffers(decodeFrame(data, offset, length));
        } catch (MediaException exception) {
            output = null;
            discardReferences();
            throw exception;
        } catch (RuntimeException exception) {
            output = null;
            discardReferences();
            throw new MediaException.Malformed("corrupt vp8 frame", exception);
        } finally {
            outputRequested = false;
            outputOffer = null;
        }
    }

    /**
     * Prepares the picture a displayed frame is written into while it decodes.
     *
     * @param show whether the frame is displayed
     */
    private void beginOutput(boolean show) {
        output = null;
        if (!outputRequested || !show || keyFramesOnly) {
            return;
        }
        var picture = outputOffer;
        if (picture == null || picture.width() != width || picture.height() != height
                || picture.chromaFormat() != ChromaFormat.YUV420) {
            LimitChecks.checkAllocation(limits, (long) width * height * 3 / 2 + 4);
            picture = YuvPicture.allocate(width, height, ChromaFormat.YUV420, false, ColorInfo.BT601_LIMITED);
        }
        output = picture;
    }

    /**
     * Ends a macroblock row that no later filtering changes: extends its borders for inter
     * prediction when asked to, and copies it into the output picture when there is one.
     *
     * @param frame  the frame being decoded
     * @param row    the macroblock row
     * @param extend whether the borders are extended for inter prediction
     */
    private void finishRow(FrameBuffer frame, int row, boolean extend) {
        if (extend) {
            frame.extendRowSides(row);
        }
        var picture = output;
        if (picture == null) {
            return;
        }
        var top = row * 16;
        if (top < height) {
            frame.copyRowsTo(picture, width, height, top, Math.min(top + 16, height));
        }
    }

    /**
     * Puts the decoder in the state that accepts only a key frame after a frame failed to decode.
     *
     * <p>The buffer taken for the failed frame is returned to the free pool by recounting the
     * references of the last, golden and alternate reference slots, which the next key frame
     * replaces.
     */
    private void discardReferences() {
        haveReferences = false;
        showFrame = false;
        Arrays.fill(refCount, 0);
        if (!keyFramesOnly && buffers[0] != null) {
            for (var ref = LAST_FRAME; ref <= ALTREF_FRAME; ref++) {
                refCount[refIndex[ref]]++;
            }
        }
    }

    /**
     * Asks the next {@link #decode} to write a displayed frame, cropped to the display size, into
     * a picture while it decodes, each macroblock row as soon as it is final, so that
     * {@link #copyPicture} returns without copying.
     *
     * <p>The request applies to the next call of {@code decode} only. A picture that does not
     * match the frame is replaced by a new one. The picture is overwritten while the frame
     * decodes, also when the frame then fails.
     *
     * @param reuse a picture to overwrite, or {@code null} to allocate one
     */
    public void setOutput(YuvPicture reuse) {
        outputOffer = reuse;
        outputRequested = true;
    }

    /**
     * Copies the last decoded frame, cropped to the display size, into a picture.
     *
     * <p>When {@link #setOutput} was called before the decode, the picture is already written
     * and is returned as is.
     *
     * @param reuse a picture of the display size to overwrite, or {@code null} to allocate one
     * @return the picture holding the frame, in limited-range BT.601
     * @throws IllegalStateException if no frame has been decoded
     */
    public YuvPicture copyPicture(YuvPicture reuse) {
        var frame = buffers[refIndex[0]];
        if (frame == null || width == 0) {
            throw new IllegalStateException("no decoded frame");
        }
        if (output != null) {
            var written = output;
            output = null;
            return written;
        }
        var picture = reuse;
        if (picture == null || picture.width() != width || picture.height() != height
                || picture.chromaFormat() != ChromaFormat.YUV420) {
            LimitChecks.checkAllocation(limits, (long) width * height * 3 / 2 + 4);
            picture = YuvPicture.allocate(width, height, ChromaFormat.YUV420, false, ColorInfo.BT601_LIMITED);
        }
        frame.copyTo(picture, width, height);
        return picture;
    }

    /**
     * Parses and reconstructs one frame into a free buffer.
     *
     * @param data   the array holding the frame
     * @param offset the index of the frame's first byte
     * @param length the frame size in bytes
     * @return the index of the buffer holding the reconstructed frame
     */
    private int decodeFrame(byte[] data, int offset, int length) {
        if (length < 3) {
            throw new MediaException.Malformed("vp8 frame tag truncated");
        }
        var end = offset + length;
        var tag = (data[offset] & 0xFF) | (data[offset + 1] & 0xFF) << 8 | (data[offset + 2] & 0xFF) << 16;
        var isKey = (tag & 1) == 0;
        var frameVersion = (tag >> 1) & 7;
        var show = ((tag >> 4) & 1) != 0;
        var firstPartitionSize = tag >>> 5;
        var pos = offset + 3;
        if (isKey) {
            if (end - pos < 7) {
                throw new MediaException.Malformed("vp8 key frame header truncated");
            }
            if ((data[pos] & 0xFF) != 0x9D || (data[pos + 1] & 0xFF) != 0x01 || (data[pos + 2] & 0xFF) != 0x2A) {
                throw new MediaException.Malformed("invalid vp8 key frame start code");
            }
            var w = ((data[pos + 3] & 0xFF) | (data[pos + 4] & 0xFF) << 8) & 0x3FFF;
            var h = ((data[pos + 5] & 0xFF) | (data[pos + 6] & 0xFF) << 8) & 0x3FFF;
            if (w == 0 || h == 0) {
                throw new MediaException.Malformed("invalid vp8 frame size " + w + "x" + h);
            }
            LimitChecks.checkPicture(limits, w, h);
            horizontalScale = (data[pos + 4] & 0xFF) >> 6;
            verticalScale = (data[pos + 6] & 0xFF) >> 6;
            pos += 7;
            resize(w, h);
        } else {
            if (keyFramesOnly) {
                throw new MediaException.Malformed("vp8 frame is not a key frame");
            }
            if (!haveReferences) {
                throw new MediaException.Malformed("vp8 inter frame without a valid preceding key frame");
            }
        }
        if (keyFramesOnly) {
            if (frameVersion > 3) {
                throw new MediaException.Malformed("invalid vp8 profile " + frameVersion);
            }
            if (!show) {
                throw new MediaException.Unsupported("vp8 invisible frame", "a vp8 image frame must be displayable");
            }
        }
        if (firstPartitionSize == 0) {
            throw new MediaException.Malformed("vp8 first partition is empty");
        }
        if (firstPartitionSize > end - pos) {
            throw new MediaException.Malformed("vp8 first partition truncated");
        }
        keyFrame = isKey;
        version = frameVersion;
        var newIdx = takeFreeBuffer();
        refIndex[0] = newIdx;
        var br = header;
        br.init(data, pos, firstPartitionSize);
        parseHeader(br, data, pos + firstPartitionSize, end);
        beginOutput(show);
        decodeMacroblocks(buffers[newIdx]);
        if (br.isEof()) {
            throw new MediaException.Malformed("vp8 first partition overrun");
        }
        showFrame = show;
        return newIdx;
    }

    /**
     * Adopts a new frame size, reallocating the frame buffers and per-macroblock state when it
     * changes.
     *
     * @param w the new width
     * @param h the new height
     */
    private void resize(int w, int h) {
        var cols = (w + 15) >> 4;
        var rows = (h + 15) >> 4;
        if (cols == mbCols && rows == mbRows && buffers[0] != null) {
            width = w;
            height = h;
            return;
        }
        width = 0;
        height = 0;
        var perBuffer = FrameBuffer.bytesFor(cols, rows);
        var count = keyFramesOnly ? 1 : NUM_BUFFERS;
        LimitChecks.checkAllocation(limits, perBuffer * count);
        var miCount = (long) (cols + 1) * (rows + 1);
        LimitChecks.checkAllocation(limits, miCount * 16 * 5);
        Arrays.fill(buffers, null);
        for (var i = 0; i < count; i++) {
            buffers[i] = new FrameBuffer(cols, rows, limits);
        }
        Arrays.fill(refCount, 0);
        refIndex[0] = 0;
        if (!keyFramesOnly) {
            for (var i = 1; i < NUM_BUFFERS; i++) {
                refIndex[i] = i;
                refCount[i] = 1;
            }
        }
        mbCols = cols;
        mbRows = rows;
        miStride = cols + 1;
        var mi = (int) miCount;
        mbMode = new byte[mi];
        mbUvMode = new byte[mi];
        mbRef = new byte[mi];
        mbSegment = new byte[mi];
        mbSkip = new byte[mi];
        mbNeedClamp = new byte[mi];
        mbPartitioning = new byte[mi];
        mbMv = new int[mi];
        bModes = new byte[mi * 16];
        bMvs = new int[mi * 16];
        aboveContext = new byte[cols * 9];
        width = w;
        height = h;
    }

    /**
     * Takes a free frame buffer for the frame about to be decoded.
     *
     * @return the buffer index
     */
    private int takeFreeBuffer() {
        if (keyFramesOnly) {
            refCount[0] = 1;
            return 0;
        }
        for (var i = 0; i < NUM_BUFFERS; i++) {
            if (refCount[i] == 0) {
                refCount[i] = 1;
                return i;
            }
        }
        throw new IllegalStateException("no free vp8 frame buffer");
    }

    /**
     * Points a reference at a buffer, maintaining the reference counts.
     *
     * @param ref    the reference index
     * @param buffer the buffer index
     */
    private void assignReference(int ref, int buffer) {
        if (refCount[refIndex[ref]] > 0) {
            refCount[refIndex[ref]]--;
        }
        refIndex[ref] = buffer;
        refCount[buffer]++;
    }

    /**
     * The pending copy of the golden frame signaled by the current frame header.
     */
    private int copyToGolden;

    /**
     * The pending copy of the alternate reference frame signaled by the current frame header.
     */
    private int copyToAltref;

    /**
     * Whether the current frame replaces the golden frame.
     */
    private boolean refreshGolden;

    /**
     * Whether the current frame replaces the alternate reference frame.
     */
    private boolean refreshAltref;

    /**
     * Whether the current frame replaces the last frame.
     */
    private boolean refreshLast;

    /**
     * Whether the current frame keeps its entropy updates for the following frames.
     */
    private boolean refreshEntropy;

    /**
     * Updates the references after a frame decoded successfully, in libvpx's order.
     *
     * @param newIdx the buffer holding the decoded frame
     */
    private void swapBuffers(int newIdx) {
        if (!refreshEntropy) {
            restoreEntropy();
        }
        if (keyFramesOnly) {
            refCount[0] = 0;
            haveReferences = true;
            return;
        }
        if (copyToAltref == 1) {
            assignReference(ALTREF_FRAME, refIndex[LAST_FRAME]);
        } else if (copyToAltref == 2) {
            assignReference(ALTREF_FRAME, refIndex[GOLDEN_FRAME]);
        }
        if (copyToGolden == 1) {
            assignReference(GOLDEN_FRAME, refIndex[LAST_FRAME]);
        } else if (copyToGolden == 2) {
            assignReference(GOLDEN_FRAME, refIndex[ALTREF_FRAME]);
        }
        if (refreshGolden) {
            assignReference(GOLDEN_FRAME, newIdx);
        }
        if (refreshAltref) {
            assignReference(ALTREF_FRAME, newIdx);
        }
        if (refreshLast) {
            assignReference(LAST_FRAME, newIdx);
        }
        refCount[newIdx]--;
        haveReferences = true;
    }

    /**
     * Saves the entropy state before the current frame's updates.
     */
    private void saveEntropy() {
        System.arraycopy(coefProbs, 0, savedCoefProbs, 0, coefProbs.length);
        System.arraycopy(ymodeProbs, 0, savedYmodeProbs, 0, 4);
        System.arraycopy(uvModeProbs, 0, savedUvModeProbs, 0, 3);
        System.arraycopy(mvProbs, 0, savedMvProbs, 0, 38);
    }

    /**
     * Restores the entropy state saved by {@link #saveEntropy()}.
     */
    private void restoreEntropy() {
        System.arraycopy(savedCoefProbs, 0, coefProbs, 0, coefProbs.length);
        System.arraycopy(savedYmodeProbs, 0, ymodeProbs, 0, 4);
        System.arraycopy(savedUvModeProbs, 0, uvModeProbs, 0, 3);
        System.arraycopy(savedMvProbs, 0, mvProbs, 0, 38);
    }

    /**
     * Parses the frame header in the first partition and sets up the token partitions.
     *
     * @param br           the first partition decoder
     * @param data         the array holding the frame
     * @param partitionPos the index of the first byte after the first partition
     * @param end          the index one past the frame's last byte
     */
    private void parseHeader(BoolDecoder br, byte[] data, int partitionPos, int end) {
        if (keyFrame) {
            System.arraycopy(Vp8Tables.MV_PROBS_DEFAULT, 0, mvProbs, 0, 38);
            System.arraycopy(Vp8Tables.YMODE_PROBS_DEFAULT, 0, ymodeProbs, 0, 4);
            System.arraycopy(Vp8Tables.UV_MODE_PROBS_DEFAULT, 0, uvModeProbs, 0, 3);
            System.arraycopy(Vp8Tables.COEF_PROBS_DEFAULT, 0, coefProbs, 0, coefProbs.length);
            Arrays.fill(segmentQuant, 0);
            Arrays.fill(segmentFilter, 0);
            segmentAbsolute = false;
            Arrays.fill(refFilterDeltas, 0);
            Arrays.fill(modeFilterDeltas, 0);
            signBias[GOLDEN_FRAME] = 0;
            signBias[ALTREF_FRAME] = 0;
            br.readBit();
            br.readBit();
        }
        segmentationEnabled = br.readBit() != 0;
        if (segmentationEnabled) {
            updateSegmentMap = br.readBit() != 0;
            var updateData = br.readBit() != 0;
            if (updateData) {
                segmentAbsolute = br.readBit() != 0;
                for (var i = 0; i < 4; i++) {
                    segmentQuant[i] = readOptionalSigned(br, 7);
                }
                for (var i = 0; i < 4; i++) {
                    segmentFilter[i] = readOptionalSigned(br, 6);
                }
            }
            if (updateSegmentMap) {
                for (var i = 0; i < 3; i++) {
                    segmentTreeProbs[i] = br.readBit() != 0 ? br.readLiteral(8) : 255;
                }
            }
        } else {
            updateSegmentMap = false;
        }
        simpleFilter = br.readBit() != 0;
        filterLevel = br.readLiteral(6);
        sharpness = br.readLiteral(3);
        filterDeltasEnabled = br.readBit() != 0;
        if (filterDeltasEnabled && br.readBit() != 0) {
            for (var i = 0; i < 4; i++) {
                if (br.readBit() != 0) {
                    var v = br.readLiteral(6);
                    refFilterDeltas[i] = br.readBit() != 0 ? -v : v;
                }
            }
            for (var i = 0; i < 4; i++) {
                if (br.readBit() != 0) {
                    var v = br.readLiteral(6);
                    modeFilterDeltas[i] = br.readBit() != 0 ? -v : v;
                }
            }
        }
        setupPartitions(br, data, partitionPos, end);
        baseQuant = br.readLiteral(7);
        for (var i = 0; i < 5; i++) {
            quantDeltas[i] = readDeltaQ(br);
        }
        setupDequantization();
        if (keyFrame) {
            refreshGolden = true;
            refreshAltref = true;
            copyToGolden = 0;
            copyToAltref = 0;
        } else {
            refreshGolden = br.readBit() != 0;
            refreshAltref = br.readBit() != 0;
            copyToGolden = refreshGolden ? 0 : br.readLiteral(2);
            copyToAltref = refreshAltref ? 0 : br.readLiteral(2);
            if (copyToGolden == 3 || copyToAltref == 3) {
                throw new MediaException.Malformed("invalid vp8 reference copy flag");
            }
            signBias[GOLDEN_FRAME] = br.readBit();
            signBias[ALTREF_FRAME] = br.readBit();
        }
        refreshEntropy = br.readBit() != 0;
        if (!refreshEntropy) {
            saveEntropy();
        }
        refreshLast = keyFrame || br.readBit() != 0;
        var probs = coefProbs;
        var update = Vp8Tables.COEF_UPDATE_PROBS;
        for (var i = 0; i < 1056; i++) {
            if (br.readBool(update[i]) != 0) {
                probs[i] = br.readLiteral(8);
            }
        }
        coeffSkipEnabled = br.readBit() != 0;
        probSkipFalse = coeffSkipEnabled ? br.readLiteral(8) : 0;
        if (!keyFrame) {
            probIntra = br.readLiteral(8);
            probLast = br.readLiteral(8);
            probGolden = br.readLiteral(8);
            if (br.readBit() != 0) {
                for (var i = 0; i < 4; i++) {
                    ymodeProbs[i] = br.readLiteral(8);
                }
            }
            if (br.readBit() != 0) {
                for (var i = 0; i < 3; i++) {
                    uvModeProbs[i] = br.readLiteral(8);
                }
            }
            var up = Vp8Tables.MV_UPDATE_PROBS;
            for (var i = 0; i < 38; i++) {
                if (br.readBool(up[i]) != 0) {
                    var x = br.readLiteral(7);
                    mvProbs[i] = x != 0 ? x << 1 : 1;
                }
            }
        }
    }

    /**
     * Reads an optional signed value: a presence flag, a magnitude, and a sign.
     *
     * @param br    the decoder
     * @param width the magnitude width in bits
     * @return the value, or {@code 0} when absent
     */
    private static int readOptionalSigned(BoolDecoder br, int width) {
        if (br.readBit() == 0) {
            return 0;
        }
        var v = br.readLiteral(width);
        return br.readBit() != 0 ? -v : v;
    }

    /**
     * Reads a quantizer delta.
     *
     * @param br the decoder
     * @return the delta, or {@code 0} when absent
     */
    private static int readDeltaQ(BoolDecoder br) {
        if (br.readBit() == 0) {
            return 0;
        }
        var v = br.readLiteral(4);
        return br.readBit() != 0 ? -v : v;
    }

    /**
     * Locates the token partitions after the first partition and initializes their decoders.
     *
     * @param br    the first partition decoder
     * @param data  the array holding the frame
     * @param start the index of the first byte after the first partition
     * @param end   the index one past the frame's last byte
     */
    private void setupPartitions(BoolDecoder br, byte[] data, int start, int end) {
        var count = 1 << br.readLiteral(2);
        numPartitions = count;
        var sizesLength = 3 * (count - 1);
        if (end - start < sizesLength) {
            throw new MediaException.Malformed("vp8 token partition sizes truncated");
        }
        var partStart = start + sizesLength;
        var remaining = end - partStart;
        for (var p = 0; p < count - 1; p++) {
            var s = start + 3 * p;
            var size = (data[s] & 0xFF) | (data[s + 1] & 0xFF) << 8 | (data[s + 2] & 0xFF) << 16;
            if (keyFramesOnly) {
                if (size > remaining) {
                    size = remaining;
                }
            } else if (size == 0 || size > remaining) {
                throw new MediaException.Malformed("vp8 token partition " + (p + 1) + " truncated or corrupt");
            }
            partitions[p].init(data, partStart, size);
            partStart += size;
            remaining -= size;
        }
        if (remaining <= 0) {
            throw new MediaException.Malformed("vp8 last token partition is empty");
        }
        partitions[count - 1].init(data, partStart, remaining);
    }

    /**
     * Computes the dequantization factors of every segment.
     */
    private void setupDequantization() {
        for (var s = 0; s < 4; s++) {
            int q;
            if (segmentationEnabled) {
                q = segmentAbsolute ? segmentQuant[s] : baseQuant + segmentQuant[s];
                q = q < 0 ? 0 : Math.min(q, 127);
            } else {
                q = baseQuant;
            }
            var base = s * 6;
            dequant[base] = Vp8Tables.DC_QLOOKUP[clampQ(q + quantDeltas[0])];
            dequant[base + 1] = Vp8Tables.AC_QLOOKUP[clampQ(q)];
            dequant[base + 2] = Vp8Tables.DC_QLOOKUP[clampQ(q + quantDeltas[1])] * 2;
            var y2ac = (Vp8Tables.AC_QLOOKUP[clampQ(q + quantDeltas[2])] * 101581) >> 16;
            dequant[base + 3] = Math.max(y2ac, 8);
            dequant[base + 4] = Math.min(Vp8Tables.DC_QLOOKUP[clampQ(q + quantDeltas[3])], 132);
            dequant[base + 5] = Vp8Tables.AC_QLOOKUP[clampQ(q + quantDeltas[4])];
        }
    }

    /**
     * Clamps a quantizer index to {@code [0, 127]}.
     *
     * @param q the index
     * @return the clamped index
     */
    private static int clampQ(int q) {
        return q < 0 ? 0 : Math.min(q, 127);
    }

    /**
     * Computes the loop filter level of every segment, reference, and mode class.
     */
    private void setupFilterLevels() {
        for (var seg = 0; seg < 4; seg++) {
            var lvlSeg = filterLevel;
            if (segmentationEnabled) {
                lvlSeg = segmentAbsolute ? segmentFilter[seg] : lvlSeg + segmentFilter[seg];
                lvlSeg = lvlSeg > 0 ? Math.min(lvlSeg, 63) : 0;
            }
            var base = seg * 16;
            if (!filterDeltasEnabled) {
                Arrays.fill(filterLevels, base, base + 16, lvlSeg);
                continue;
            }
            var lvlRef = lvlSeg + refFilterDeltas[INTRA_FRAME];
            filterLevels[base] = clampLevel(lvlRef + modeFilterDeltas[0]);
            filterLevels[base + 1] = clampLevel(lvlRef);
            for (var ref = 1; ref < 4; ref++) {
                lvlRef = lvlSeg + refFilterDeltas[ref];
                for (var mode = 1; mode < 4; mode++) {
                    filterLevels[base + ref * 4 + mode] = clampLevel(lvlRef + modeFilterDeltas[mode]);
                }
            }
        }
    }

    /**
     * Clamps a loop filter level to {@code [0, 63]}.
     *
     * @param level the level before clamping
     * @return the clamped level
     */
    private static int clampLevel(int level) {
        return level > 0 ? Math.min(level, 63) : 0;
    }

    /**
     * Parses the modes and reconstructs every macroblock of the frame, applying the loop filter
     * one row behind and extending the borders two rows behind, like libvpx's
     * {@code decode_mb_rows}.
     *
     * <p>When allowed to ({@link #setThreads}), for a frame of at least {@link #PIPELINE_ROWS}
     * macroblock rows, and when a helper is free, the work runs as a {@link ParallelBands#pipeline}
     * of one parsing stage and several reconstruction workers: the calling thread parses the modes
     * and coefficient tokens row by row, and each worker claims the next row in order,
     * reconstructs it from the parsed row, and filters the row above it, macroblock by macroblock,
     * behind the reconstruction of its own row and the filtering of the row above that. The
     * pipeline computes exactly what the single thread does: a macroblock is reconstructed once
     * the three macroblocks above it are, which are the only pixels of the frame an intra
     * prediction reads, and filtered once the macroblock below and to its right is reconstructed,
     * from which point no prediction reads it unfiltered, and once the macroblock above and to its
     * right is filtered, the last macroblock whose filter changes pixels it reads; whichever
     * thread runs a row, it waits only for rows claimed before it. When a stage fails, every stage
     * stops and the earliest failure recorded is thrown.
     *
     * @param frame the frame being decoded
     */
    private void decodeMacroblocks(FrameBuffer frame) {
        var filtering = filterLevel != 0;
        if (filtering) {
            setupFilterLevels();
        }
        var extend = !keyFramesOnly;
        frame.setupIntraTopBorder();
        Arrays.fill(aboveContext, (byte) 0);
        if (threads != 1 && mbRows >= PIPELINE_ROWS && ParallelBands.available() > 0 && blockStoreFits()) {
            decodePipelined(frame, extend);
            return;
        }
        var br = header;
        for (var row = 0; row < mbRows; row++) {
            decodeRow(frame, br, row);
            if (filtering) {
                if (row > 0) {
                    main.filterRow(frame, row - 1);
                    if (row > 1) {
                        finishRow(frame, row - 2, extend);
                    }
                }
            } else if (row > 0) {
                finishRow(frame, row - 1, extend);
            }
        }
        if (filtering) {
            main.filterRow(frame, mbRows - 1);
            if (mbRows > 1) {
                finishRow(frame, mbRows - 2, extend);
            }
        }
        finishRow(frame, mbRows - 1, extend);
        if (extend) {
            frame.extendTopBottom();
        }
    }

    /**
     * Parses and reconstructs one macroblock row on the calling thread, checks its partitions, and
     * extends its bottom lines for the next row's above-right predictions.
     *
     * @param frame the frame being decoded
     * @param br    the first partition decoder
     * @param row   the macroblock row
     * @throws MediaException.Malformed if a partition ends before the row does
     */
    private void decodeRow(FrameBuffer frame, BoolDecoder br, int row) {
        var tokens = partitions[row & (numPartitions - 1)];
        Arrays.fill(leftContext, (byte) 0);
        frame.setupIntraLeftBorder(row);
        mbToTopEdge = -((row * 16) << 3);
        mbToBottomEdge = ((mbRows - 1 - row) * 16) << 3;
        for (var col = 0; col < mbCols; col++) {
            mbToLeftEdge = -((col * 16) << 3);
            mbToRightEdge = ((mbCols - 1 - col) * 16) << 3;
            var idx = (row + 1) * miStride + col + 1;
            parseModes(br, idx);
            parseResidual(tokens, idx, col, coeffs, 0, eobs, 0);
            main.reconstructMacroblock(frame, idx, row, col, coeffs, 0, eobs, 0);
        }
        checkRowEnd(br, tokens);
        frame.extendRowAboveRight(row);
    }

    /**
     * Checks that neither the first partition nor a row's token partition ended before the row
     * did.
     *
     * @param br     the first partition decoder
     * @param tokens the row's token partition decoder
     * @throws MediaException.Malformed if either partition is overrun
     */
    private static void checkRowEnd(BoolDecoder br, BoolDecoder tokens) {
        if (br.isEof()) {
            throw new MediaException.Malformed("vp8 first partition overrun");
        }
        if (tokens.isEof()) {
            throw new MediaException.Malformed("vp8 token partition overrun");
        }
    }

    /**
     * Decodes the macroblocks as one parsing stage and several reconstruction workers of a
     * {@link ParallelBands#pipeline}, as described at {@link #decodeMacroblocks}.
     *
     * <p>The calling thread parses and never waits. Each worker repeatedly claims the next
     * macroblock row, in order, and reconstructs it macroblock by macroblock, waiting only for the
     * macroblocks of the rows above it that its intra predictions read (the three above it), and
     * for the parsed row. The same worker filters the row above its own, macroblock by macroblock,
     * two macroblocks behind its own reconstruction and behind the filtering of the row above that,
     * which are the points from which no pixel it reads or writes is still needed unfiltered by a
     * prediction or changed by another macroblock's filter. A worker waits only for rows that
     * were claimed before its own, so the stages cannot stall whatever threads the helpers lend
     * them. Each failure is recorded with its position in the single-thread order, macroblock
     * {@code k} being parsed at {@code 4k}, reconstructed at {@code 4k + 1}, and its row checked at
     * {@code 4k + 2} when it ends one, and a row being filtered right after the row below it is
     * checked, at {@code 4k + 3}; every stage stops at the first failure any of them records, and
     * the earliest position recorded is thrown.
     *
     * @param frame  the frame being decoded
     * @param extend whether the borders are extended for inter prediction
     */
    private void decodePipelined(FrameBuffer frame, boolean extend) {
        prepareBlockStore();
        var workerCount = Math.max(1, Math.min(Math.min(ParallelBands.available(), MAX_WORKERS), mbRows / ROWS_PER_WORKER));
        if (threads > 1) {
            workerCount = Math.min(workerCount, threads - 1);
        }
        if (workers.length < workerCount) {
            var grown = Arrays.copyOf(workers, workerCount);
            for (var i = workers.length; i < workerCount; i++) {
                grown[i] = new Worker();
            }
            workers = grown;
        }
        var wave = new Wave(mbRows);
        var crew = workers;
        ParallelBands.pipeline(1 + workerCount, stage -> {
            if (stage == 0) {
                parseRows(wave);
            } else {
                crew[stage - 1].work(frame, extend, wave);
            }
        });
        if (wave.failures.failed()) {
            blockStoreDirty = true;
            wave.failures.rethrow();
        }
        if (extend) {
            frame.extendTopBottom();
        }
    }

    /**
     * Returns whether the largest block store a frame of this size can need, every block of every
     * macroblock stored, fits the allocation limit, so that the pipeline never fails where the
     * single thread succeeds.
     *
     * @return {@code true} when the frame may be pipelined
     */
    private boolean blockStoreFits() {
        var most = ((long) mbRows * mbCols * MB_COEFFS + MB_COEFFS) * 2;
        return most <= limits.maxBytes() && most <= Integer.MAX_VALUE - 16;
    }

    /**
     * Readies the block store for a pipelined frame, allocating it for the frame size or clearing
     * what an earlier frame left in it.
     */
    private void prepareBlockStore() {
        var count = mbRows * mbCols;
        if (blockStart.length != count) {
            var initial = count * INITIAL_BLOCKS_PER_MB * 16 + MB_COEFFS;
            blockStart = new int[count];
            blockEobs = new byte[count * 25];
            blockStore = new short[initial];
            blockStoreDirty = false;
        } else if (blockStoreDirty) {
            Arrays.fill(blockStore, (short) 0);
            blockStoreDirty = false;
        }
    }

    /**
     * Replaces the block store with a larger copy that has room for one more macroblock after
     * {@code used} coefficients.
     *
     * <p>The store never outgrows the largest one {@link #blockStoreFits} checked.
     *
     * @param store the current store
     * @param used  the number of coefficients stored so far
     * @return the larger store
     */
    private short[] growBlockStore(short[] store, int used) {
        var most = (long) mbRows * mbCols * MB_COEFFS + MB_COEFFS;
        var length = (int) Math.min(Math.max(store.length * 2L, (long) used + MB_COEFFS), most);
        var grown = Arrays.copyOf(store, length);
        blockStoreDirty = true;
        blockStore = grown;
        return grown;
    }

    /**
     * Parses the modes and coefficient tokens of every macroblock into the block store,
     * publishing each parsed row to reconstruction; on failure publishes the macroblocks parsed
     * before it, records the failure and stops every stage.
     *
     * @param wave the progress and failures of the frame's stages
     */
    private void parseRows(Wave wave) {
        var br = header;
        var store = blockStore;
        var starts = blockStart;
        var eob = blockEobs;
        var pos = 0;
        var mb = 0;
        var rowEnd = false;
        try {
            for (var row = 0; row < mbRows && !wave.aborted; row++) {
                var tokens = partitions[row & (numPartitions - 1)];
                Arrays.fill(leftContext, (byte) 0);
                mbToTopEdge = -((row * 16) << 3);
                mbToBottomEdge = ((mbRows - 1 - row) * 16) << 3;
                for (var col = 0; col < mbCols; col++) {
                    mbToLeftEdge = -((col * 16) << 3);
                    mbToRightEdge = ((mbCols - 1 - col) * 16) << 3;
                    var idx = (row + 1) * miStride + col + 1;
                    parseModes(br, idx);
                    if (pos > store.length - MB_COEFFS) {
                        store = growBlockStore(store, pos);
                    }
                    starts[mb] = pos;
                    pos = parseResidual(tokens, idx, col, store, pos, eob, mb * 25);
                    mb++;
                }
                rowEnd = true;
                checkRowEnd(br, tokens);
                rowEnd = false;
                wave.parsed[row].publish(mbCols);
            }
        } catch (Throwable failure) {
            wave.fail(rowEnd ? 4L * mb - 2 : 4L * mb, failure);
        } finally {
            for (var row = 0; row < mbRows; row++) {
                wave.parsed[row].finish();
            }
        }
    }

    /**
     * Holds the progress of the stages of a pipelined frame and the failures they record.
     */
    private static final class Wave {
        /**
         * The progress of parsing, in macroblocks, of every row.
         */
        final StageProgress[] parsed;

        /**
         * The progress of reconstruction, in macroblocks, of every row.
         */
        final StageProgress[] reconstructed;

        /**
         * The progress of the loop filter, in macroblocks, of every row.
         */
        final StageProgress[] filtered;

        /**
         * The next row for a worker to claim.
         */
        final AtomicInteger next = new AtomicInteger();

        /**
         * The failures of the stages.
         */
        final StageFailures failures = new StageFailures();

        /**
         * Whether a stage failed and every stage stops.
         */
        volatile boolean aborted;

        /**
         * Creates the progress of a frame.
         *
         * @param rows the number of macroblock rows
         */
        Wave(int rows) {
            parsed = new StageProgress[rows];
            reconstructed = new StageProgress[rows];
            filtered = new StageProgress[rows];
            for (var row = 0; row < rows; row++) {
                parsed[row] = new StageProgress();
                reconstructed[row] = new StageProgress();
                filtered[row] = new StageProgress();
            }
        }

        /**
         * Records a failure and stops every stage, waking the threads that wait.
         *
         * @param position the failure's position in the single-thread order
         * @param failure the exception the stage threw
         */
        void fail(long position, Throwable failure) {
            failures.record(position, failure);
            aborted = true;
            for (var row = 0; row < parsed.length; row++) {
                parsed[row].finish();
                reconstructed[row].finish();
                filtered[row].finish();
            }
        }
    }

    /**
     * Holds how far a pipeline stage has advanced, shared with the one stage that waits for it.
     */
    private static final class StageProgress {
        /**
         * The number of units, macroblocks or rows, completed so far.
         */
        private volatile int count;

        /**
         * Whether the stage has stopped, completed or failed.
         */
        private volatile boolean finished;

        /**
         * The thread waiting for progress, or {@code null}.
         */
        private volatile Thread waiter;

        /**
         * Creates the progress of a stage.
         */
        StageProgress() {
        }

        /**
         * Publishes completed units and wakes the waiting thread.
         *
         * @param count the number of units completed so far
         */
        void publish(int count) {
            this.count = count;
            wake();
        }

        /**
         * Marks the end of the stage and wakes the waiting thread.
         */
        void finish() {
            finished = true;
            wake();
        }

        /**
         * Wakes the waiting thread, if any.
         */
        private void wake() {
            var thread = waiter;
            if (thread != null) {
                LockSupport.unpark(thread);
            }
        }

        /**
         * Waits until at least {@code needed} units are completed.
         *
         * @param needed the number of units needed
         * @return {@code true} when they are, {@code false} when the stage stopped before
         */
        boolean await(int needed) {
            if (count >= needed) {
                return true;
            }
            for (var spin = 0; spin < SPINS; spin++) {
                Thread.onSpinWait();
                if (count >= needed) {
                    return true;
                }
            }
            waiter = Thread.currentThread();
            while (true) {
                if (count >= needed) {
                    return true;
                }
                if (finished) {
                    return count >= needed;
                }
                LockSupport.parkNanos(this, WAIT_NANOS);
            }
        }
    }

    /**
     * Keeps the failure of a pipelined frame that comes first in the single-thread order.
     */
    private static final class StageFailures {
        /**
         * Guards {@link #position} and {@link #failure}.
         */
        private final Object lock = new Object();

        /**
         * The single-thread position of {@link #failure}.
         */
        private long position = Long.MAX_VALUE;

        /**
         * The earliest failure, or {@code null}.
         */
        private Throwable failure;

        /**
         * Creates an empty record.
         */
        StageFailures() {
        }

        /**
         * Records a stage's failure, keeping it when it comes before the one recorded.
         *
         * @param position the failure's position in the single-thread order
         * @param failure the exception the stage threw
         */
        void record(long position, Throwable failure) {
            synchronized (lock) {
                if (position < this.position) {
                    this.position = position;
                    this.failure = failure;
                }
            }
        }

        /**
         * Returns whether a stage failed.
         *
         * @return {@code true} when a failure is recorded
         */
        boolean failed() {
            synchronized (lock) {
                return failure != null;
            }
        }

        /**
         * Throws the earliest failure unchanged.
         *
         * @throws RuntimeException the failure, when unchecked
         * @throws Error            the failure, when an error
         */
        void rethrow() {
            Throwable earliest;
            synchronized (lock) {
                earliest = failure;
            }
            if (earliest instanceof RuntimeException exception) {
                throw exception;
            }
            if (earliest instanceof Error error) {
                throw error;
            }
            if (earliest != null) {
                throw new IllegalStateException("vp8 pipeline stage failed", earliest);
            }
        }
    }

    /**
     * Parses the segment, skip flag, and prediction modes of one macroblock.
     *
     * @param br  the first partition decoder
     * @param idx the macroblock's mode info index
     */
    private void parseModes(BoolDecoder br, int idx) {
        if (updateSegmentMap) {
            var p = segmentTreeProbs;
            mbSegment[idx] = (byte) (br.readBool(p[0]) != 0 ? 2 + br.readBool(p[2]) : br.readBool(p[1]));
        } else if (keyFrame) {
            mbSegment[idx] = 0;
        }
        mbSkip[idx] = (byte) (coeffSkipEnabled ? br.readBool(probSkipFalse) : 0);
        if (keyFrame) {
            parseKeyFrameModes(br, idx);
        } else {
            parseInterModes(br, idx);
        }
    }

    /**
     * Parses the intra modes of a key-frame macroblock (RFC 6386 section 11.2).
     *
     * @param br  the first partition decoder
     * @param idx the macroblock's mode info index
     */
    private void parseKeyFrameModes(BoolDecoder br, int idx) {
        mbRef[idx] = INTRA_FRAME;
        var mode = br.readTree(Vp8Tables.KF_YMODE_TREE, Vp8Tables.KF_YMODE_PROBS, 0);
        mbMode[idx] = (byte) mode;
        if (mode == B_PRED) {
            var above = idx - miStride;
            var left = idx - 1;
            var modes = bModes;
            var base = idx * 16;
            for (var i = 0; i < 16; i++) {
                int a;
                if (i < 4) {
                    var am = mbMode[above];
                    a = am == B_PRED ? modes[above * 16 + i + 12] : BMODE_OF_MODE[am];
                } else {
                    a = modes[base + i - 4];
                }
                int l;
                if ((i & 3) == 0) {
                    var lm = mbMode[left];
                    l = lm == B_PRED ? modes[left * 16 + i + 3] : BMODE_OF_MODE[lm];
                } else {
                    l = modes[base + i - 1];
                }
                modes[base + i] = (byte) br.readTree(Vp8Tables.BMODE_TREE, Vp8Tables.KF_BMODE_PROBS, (a * 10 + l) * 9);
            }
        }
        mbUvMode[idx] = (byte) br.readTree(Vp8Tables.UV_MODE_TREE, Vp8Tables.KF_UV_MODE_PROBS, 0);
    }

    /**
     * Negates a neighbor's motion vector when its reference frame's sign bias differs from the
     * current macroblock's.
     *
     * @param mv       the neighbor's packed vector
     * @param neighbor the neighbor's reference frame
     * @param ref      the current macroblock's reference frame
     * @return the possibly negated packed vector
     */
    private int biasMv(int mv, int neighbor, int ref) {
        if (signBias[neighbor] != signBias[ref]) {
            return Mv.pack((short) -Mv.row(mv), (short) -Mv.col(mv));
        }
        return mv;
    }

    /**
     * Clamps a motion vector to the frame plus the 16-pixel margin, like {@code vp8_clamp_mv2}.
     *
     * @param mv the packed vector
     * @return the clamped packed vector
     */
    private int clampMv2(int mv) {
        return Mv.clamp(mv, mbToLeftEdge, mbToRightEdge, mbToTopEdge, mbToBottomEdge);
    }

    /**
     * Returns whether a motion vector lies outside the frame plus the 16-pixel margin.
     *
     * @param mv the packed vector
     * @return {@code true} when prediction must clamp it
     */
    private boolean outOfBounds(int mv) {
        var row = Mv.row(mv);
        var col = Mv.col(mv);
        return col < mbToLeftEdge - MV_MARGIN || col > mbToRightEdge + MV_MARGIN
                || row < mbToTopEdge - MV_MARGIN || row > mbToBottomEdge + MV_MARGIN;
    }

    /**
     * Reads one motion vector component (RFC 6386 section 17.2).
     *
     * @param br   the first partition decoder
     * @param base the index of the component's probabilities in {@link #mvProbs}
     * @return the component in quarter pixels
     */
    private int readMvComponent(BoolDecoder br, int base) {
        var p = mvProbs;
        int x;
        if (br.readBool(p[base]) != 0) {
            x = 0;
            for (var i = 0; i < 3; i++) {
                x += br.readBool(p[base + 9 + i]) << i;
            }
            for (var i = 9; i > 3; i--) {
                x += br.readBool(p[base + 9 + i]) << i;
            }
            if ((x & 0xFFF0) == 0 || br.readBool(p[base + 9 + 3]) != 0) {
                x += 8;
            }
        } else {
            x = br.readTree(Vp8Tables.SMALL_MV_TREE, p, base + 2);
        }
        if (x != 0 && br.readBool(p[base + 1]) != 0) {
            x = -x;
        }
        return x;
    }

    /**
     * Parses the modes and motion vectors of an inter-frame macroblock, like libvpx's
     * {@code read_mb_modes_mv}.
     *
     * @param br  the first partition decoder
     * @param idx the macroblock's mode info index
     */
    private void parseInterModes(BoolDecoder br, int idx) {
        var ref = br.readBool(probIntra);
        if (ref == 0) {
            mbRef[idx] = INTRA_FRAME;
            mbMv[idx] = 0;
            mbNeedClamp[idx] = 0;
            var mode = br.readTree(Vp8Tables.YMODE_TREE, ymodeProbs, 0);
            mbMode[idx] = (byte) mode;
            if (mode == B_PRED) {
                var base = idx * 16;
                for (var j = 0; j < 16; j++) {
                    bModes[base + j] = (byte) br.readTree(Vp8Tables.BMODE_TREE, Vp8Tables.BMODE_PROBS, 0);
                }
            }
            mbUvMode[idx] = (byte) br.readTree(Vp8Tables.UV_MODE_TREE, uvModeProbs, 0);
            return;
        }
        if (br.readBool(probLast) != 0) {
            ref = 2 + br.readBool(probGolden);
        }
        mbRef[idx] = (byte) ref;
        var needClamp = 0;
        var above = idx - miStride;
        var left = idx - 1;
        var aboveLeft = above - 1;
        int cntIntra = 0, cntNearest = 0, cntNear = 0, cntSplit = 0;
        int nearMv0 = 0, nearMv1 = 0, nearMv2 = 0, nearMv3 = 0;
        var n = 0;
        if (mbRef[above] != INTRA_FRAME) {
            var mv = mbMv[above];
            if (mv != 0) {
                nearMv1 = biasMv(mv, mbRef[above], ref);
                n = 1;
                cntNearest += 2;
            } else {
                cntIntra += 2;
            }
        }
        if (mbRef[left] != INTRA_FRAME) {
            var mv = mbMv[left];
            if (mv != 0) {
                var thisMv = biasMv(mv, mbRef[left], ref);
                var current = n == 0 ? nearMv0 : nearMv1;
                if (thisMv != current) {
                    n++;
                    if (n == 1) {
                        nearMv1 = thisMv;
                    } else {
                        nearMv2 = thisMv;
                    }
                }
                if (n == 0) {
                    cntIntra += 2;
                } else if (n == 1) {
                    cntNearest += 2;
                } else {
                    cntNear += 2;
                }
            } else {
                cntIntra += 2;
            }
        }
        if (mbRef[aboveLeft] != INTRA_FRAME) {
            var mv = mbMv[aboveLeft];
            if (mv != 0) {
                var thisMv = biasMv(mv, mbRef[aboveLeft], ref);
                var current = n == 0 ? nearMv0 : (n == 1 ? nearMv1 : nearMv2);
                if (thisMv != current) {
                    n++;
                    if (n == 1) {
                        nearMv1 = thisMv;
                    } else if (n == 2) {
                        nearMv2 = thisMv;
                    } else {
                        nearMv3 = thisMv;
                    }
                }
                if (n == 0) {
                    cntIntra += 1;
                } else if (n == 1) {
                    cntNearest += 1;
                } else if (n == 2) {
                    cntNear += 1;
                } else {
                    cntSplit += 1;
                }
            } else {
                cntIntra += 1;
            }
        }
        var lastNear = n == 0 ? nearMv0 : (n == 1 ? nearMv1 : (n == 2 ? nearMv2 : nearMv3));
        var mc = Vp8Tables.MODE_CONTEXTS;
        int mode;
        int mv;
        if (br.readBool(mc[cntIntra * 4]) != 0) {
            cntNearest += (cntSplit > 0 && lastNear == nearMv1) ? 1 : 0;
            if (cntNear > cntNearest) {
                var t = cntNearest;
                cntNearest = cntNear;
                cntNear = t;
                var tm = nearMv1;
                nearMv1 = nearMv2;
                nearMv2 = tm;
            }
            if (br.readBool(mc[cntNearest * 4 + 1]) != 0) {
                if (br.readBool(mc[cntNear * 4 + 2]) != 0) {
                    var bestMv = clampMv2(cntNearest >= cntIntra ? nearMv1 : nearMv0);
                    cntSplit = ((mbMode[above] == SPLITMV ? 1 : 0) + (mbMode[left] == SPLITMV ? 1 : 0)) * 2
                            + (mbMode[aboveLeft] == SPLITMV ? 1 : 0);
                    if (br.readBool(mc[cntSplit * 4 + 3]) != 0) {
                        needClamp = parseSplitMv(br, idx, left, above, bestMv);
                        mv = bMvs[idx * 16 + 15];
                        mode = SPLITMV;
                    } else {
                        var row = (short) (readMvComponent(br, 0) * 2 + Mv.row(bestMv));
                        var col = (short) (readMvComponent(br, 19) * 2 + Mv.col(bestMv));
                        mv = Mv.pack(row, col);
                        needClamp = outOfBounds(mv) ? 1 : 0;
                        mode = NEWMV;
                    }
                } else {
                    mode = NEARMV;
                    mv = clampMv2(nearMv2);
                }
            } else {
                mode = NEARESTMV;
                mv = clampMv2(nearMv1);
            }
        } else {
            mode = ZEROMV;
            mv = 0;
        }
        mbMode[idx] = (byte) mode;
        mbMv[idx] = mv;
        mbNeedClamp[idx] = (byte) needClamp;
    }

    /**
     * Parses the partitions and motion vectors of a {@code SPLITMV} macroblock, like libvpx's
     * {@code decode_split_mv}.
     *
     * @param br     the first partition decoder
     * @param idx    the macroblock's mode info index
     * @param left   the left neighbor's mode info index
     * @param above  the above neighbor's mode info index
     * @param bestMv the clamped best reference vector added to new vectors
     * @return {@code 1} when any vector lies outside the clamping bounds, {@code 0} otherwise
     */
    private int parseSplitMv(BoolDecoder br, int idx, int left, int above, int bestMv) {
        int s;
        int numParts;
        if (br.readBool(110) != 0) {
            if (br.readBool(111) != 0) {
                s = br.readBool(150);
                numParts = 2;
            } else {
                s = 2;
                numParts = 4;
            }
        } else {
            s = 3;
            numParts = 16;
        }
        var needClamp = 0;
        var base = idx * 16;
        var fillCount = Vp8Tables.MBSPLIT_FILL_COUNT[s];
        for (var j = 0; j < numParts; j++) {
            var k = Vp8Tables.MBSPLIT_OFFSET[s * 16 + j];
            int leftMv;
            if ((k & 3) == 0) {
                leftMv = mbMode[left] != SPLITMV ? mbMv[left] : bMvs[left * 16 + k + 3];
            } else {
                leftMv = bMvs[base + k - 1];
            }
            int aboveMv;
            if ((k >> 2) == 0) {
                aboveMv = mbMode[above] != SPLITMV ? mbMv[above] : bMvs[above * 16 + k + 12];
            } else {
                aboveMv = bMvs[base + k - 4];
            }
            var ctx = ((aboveMv == 0 ? 1 : 0) << 2) | ((leftMv == 0 ? 1 : 0) << 1) | (leftMv == aboveMv ? 1 : 0);
            var p = ctx * 3;
            var probs = Vp8Tables.SUB_MV_REF_PROBS;
            int blockMv;
            if (br.readBool(probs[p]) != 0) {
                if (br.readBool(probs[p + 1]) != 0) {
                    blockMv = 0;
                    if (br.readBool(probs[p + 2]) != 0) {
                        var row = (short) (readMvComponent(br, 0) * 2 + Mv.row(bestMv));
                        var col = (short) (readMvComponent(br, 19) * 2 + Mv.col(bestMv));
                        blockMv = Mv.pack(row, col);
                    }
                } else {
                    blockMv = aboveMv;
                }
            } else {
                blockMv = leftMv;
            }
            if (outOfBounds(blockMv)) {
                needClamp = 1;
            }
            var fill = s * 16 + j * fillCount;
            for (var f = 0; f < fillCount; f++) {
                bMvs[base + Vp8Tables.MBSPLIT_FILL_OFFSET[fill + f]] = blockMv;
            }
        }
        mbPartitioning[idx] = (byte) s;
        return needClamp;
    }

    /**
     * Decodes the coefficient tokens of one macroblock, or resets the nonzero contexts of a
     * macroblock whose skip flag is set, and records in {@link #mbSkip} whether it has no
     * coefficient, like the token half of libvpx's {@code decode_macroblock}.
     *
     * @param tokens  the token partition of the macroblock's row
     * @param idx     the macroblock's mode info index
     * @param col     the macroblock column
     * @param coef    the coefficient storage
     * @param pos     the index in {@code coef} where the macroblock's blocks start
     * @param eob     the end-of-block storage
     * @param eobBase the index in {@code eob} of the macroblock's block 0
     * @return the index in {@code coef} after the macroblock's blocks, {@code pos} when it has no
     *         coefficient
     */
    private int parseResidual(BoolDecoder tokens, int idx, int col, short[] coef, int pos, byte[] eob, int eobBase) {
        var mode = mbMode[idx];
        var is4x4 = mode == B_PRED || mode == SPLITMV;
        var ctxOff = col * 9;
        if (mbSkip[idx] != 0) {
            var above = aboveContext;
            for (var i = 0; i < 8; i++) {
                above[ctxOff + i] = 0;
                leftContext[i] = 0;
            }
            if (!is4x4) {
                above[ctxOff + 8] = 0;
                leftContext[8] = 0;
            }
            return pos;
        }
        var segment = segmentationEnabled ? mbSegment[idx] : 0;
        var end = decodeTokens(tokens, is4x4, ctxOff, segment * 6, coef, pos, eob, eobBase);
        mbSkip[idx] = (byte) (end == pos ? 1 : 0);
        return end;
    }

    /**
     * Decodes the coefficient tokens of one macroblock (RFC 6386 section 13), dequantizing them
     * and updating the nonzero contexts, like libvpx's {@code vp8_decode_mb_tokens}.
     *
     * <p>Only blocks with coefficients are stored, 16 coefficients each, one after the other from
     * {@code pos} in decoding order: the Y2 block, the 16 luma blocks, then the 4 U and 4 V
     * blocks. Whether a block is stored follows from its end-of-block position: it is when the
     * position exceeds 1 for a luma block of a macroblock with a Y2 block, whose position counts
     * the DC the Y2 block supplies, and when it exceeds 0 otherwise. The storage from
     * {@code pos} must be zero.
     *
     * @param br      the token partition decoder
     * @param is4x4   whether the macroblock has no Y2 block
     * @param ctxOff  the index of the macroblock column's above contexts
     * @param dqBase  the index of the macroblock segment's dequantization factors
     * @param coef    the coefficient storage
     * @param pos     the index in {@code coef} of the first stored block
     * @param eob     the end-of-block storage
     * @param eobBase the index in {@code eob} of the macroblock's block 0
     * @return the index in {@code coef} after the last stored block, {@code pos} when the
     *         macroblock has no coefficient
     */
    private int decodeTokens(BoolDecoder br, boolean is4x4, int ctxOff, int dqBase, short[] coef, int pos,
                             byte[] eob, int eobBase) {
        var above = aboveContext;
        var left = leftContext;
        var dq = dequant;
        int type;
        int first;
        if (!is4x4) {
            var ctx = above[ctxOff + 8] + left[8];
            var nz = br.decodeCoefficients(coefProbs, BAND_OFFSET, 17, ctx, 0, Vp8Tables.ZIGZAG, coef, pos,
                    dq[dqBase + 2], dq[dqBase + 3]);
            var flag = nz > 0 ? 1 : 0;
            above[ctxOff + 8] = (byte) flag;
            left[8] = (byte) flag;
            eob[eobBase + 24] = (byte) nz;
            pos += flag << 4;
            type = 0;
            first = 1;
        } else {
            type = 3;
            first = 0;
        }
        var dcY = dq[dqBase];
        var acY = dq[dqBase + 1];
        for (var i = 0; i < 16; i++) {
            var a = ctxOff + (i & 3);
            var l = i >> 2;
            var nz = br.decodeCoefficients(coefProbs, BAND_OFFSET, type * 17, above[a] + left[l], first, Vp8Tables.ZIGZAG,
                    coef, pos, dcY, acY);
            var flag = nz > 0 ? 1 : 0;
            above[a] = (byte) flag;
            left[l] = (byte) flag;
            eob[eobBase + i] = (byte) (nz + first);
            pos += flag << 4;
        }
        var dcUv = dq[dqBase + 4];
        var acUv = dq[dqBase + 5];
        for (var i = 16; i < 24; i++) {
            var a = ctxOff + 4 + ((i > 19 ? 1 : 0) << 1) + (i & 1);
            var l = 4 + ((i > 19 ? 1 : 0) << 1) + ((i & 3) > 1 ? 1 : 0);
            var nz = br.decodeCoefficients(coefProbs, BAND_OFFSET, 34, above[a] + left[l], 0, Vp8Tables.ZIGZAG, coef,
                    pos, dcUv, acUv);
            var flag = nz > 0 ? 1 : 0;
            above[a] = (byte) flag;
            left[l] = (byte) flag;
            eob[eobBase + i] = (byte) nz;
            pos += flag << 4;
        }
        return pos;
    }

    /**
     * Reconstructs and loop filters macroblocks, with scratch memory of its own so that several
     * threads can work on one frame; everything else it reads belongs to the decoder and is not
     * written while a frame's macroblocks are decoded.
     */
    private final class Worker {
        /**
         * The single-thread position of the step the worker is at, for the failure it records.
         */
        private long step;

        /**
         * Creates a worker.
         */
        Worker() {
        }

        /**
         * Reconstructs and filters macroblock rows of a pipelined frame, claiming rows in order
         * until none is left or a stage fails; see {@link Vp8FrameDecoder#decodePipelined}.
         *
         * @param frame  the frame being decoded
         * @param extend whether the borders are extended for inter prediction
         * @param wave   the progress and failures of the frame's stages
         */
        void work(FrameBuffer frame, boolean extend, Wave wave) {
            var filtering = filterLevel != 0;
            while (!wave.aborted) {
                var row = wave.next.getAndIncrement();
                if (row >= mbRows) {
                    return;
                }
                step = 4L * row * mbCols + 1;
                try {
                    workRow(frame, extend, filtering, wave, row);
                } catch (Throwable failure) {
                    wave.fail(step, failure);
                    return;
                }
            }
        }

        /**
         * Reconstructs one macroblock row and filters the row above it, then extends the borders
         * that are final.
         *
         * @param frame     the frame being decoded
         * @param extend    whether the borders are extended for inter prediction
         * @param filtering whether the loop filter runs
         * @param wave      the progress and failures of the frame's stages
         * @param row       the macroblock row
         */
        private void workRow(FrameBuffer frame, boolean extend, boolean filtering, Wave wave, int row) {
            if (!wave.parsed[row].await(mbCols)) {
                return;
            }
            var store = blockStore;
            var starts = blockStart;
            var eob = blockEobs;
            frame.setupIntraLeftBorder(row);
            var above = row > 0 ? wave.reconstructed[row - 1] : null;
            var reconstructed = wave.reconstructed[row];
            for (var col = 0; col < mbCols; col++) {
                if (above != null && !above.await(Math.min(col + 2, mbCols))) {
                    return;
                }
                var mb = row * mbCols + col;
                step = 4L * mb + 1;
                reconstructMacroblock(frame, (row + 1) * miStride + col + 1, row, col, store, starts[mb], eob, mb * 25);
                if (col == mbCols - 1) {
                    frame.extendRowAboveRight(row);
                    reconstructed.publish(mbCols);
                } else if ((col & 3) == 3) {
                    reconstructed.publish(col + 1);
                }
                if (filtering && row > 0 && col >= 1 && !filterStep(frame, wave, row - 1, col - 1)) {
                    return;
                }
            }
            if (filtering && row > 0 && !filterStep(frame, wave, row - 1, mbCols - 1)) {
                return;
            }
            if (filtering) {
                if (row >= 2) {
                    finishRow(frame, row - 2, extend);
                }
            } else if (row > 0) {
                finishRow(frame, row - 1, extend);
            }
            if (row == mbRows - 1) {
                if (filtering) {
                    for (var col = 0; col < mbCols; col++) {
                        if (!filterStep(frame, wave, row, col)) {
                            return;
                        }
                    }
                    if (row >= 1) {
                        finishRow(frame, row - 1, extend);
                    }
                }
                finishRow(frame, row, extend);
            }
        }

        /**
         * Filters one macroblock once the row above has filtered the macroblock above and to its
         * right, and publishes the progress of the row.
         *
         * @param frame the frame being decoded
         * @param wave  the progress and failures of the frame's stages
         * @param row   the macroblock row
         * @param col   the macroblock column
         * @return {@code false} when a stage failed and the row stops
         */
        private boolean filterStep(FrameBuffer frame, Wave wave, int row, int col) {
            if (row > 0 && !wave.filtered[row - 1].await(Math.min(col + 2, mbCols))) {
                return false;
            }
            step = 4L * Math.min(row + 2, mbRows) * mbCols - 1;
            filterMacroblock(frame, row, col);
            if (col == mbCols - 1) {
                wave.filtered[row].publish(mbCols);
            } else if ((col & 3) == 3) {
                wave.filtered[row].publish(col + 1);
            }
            return true;
        }

        /**
         * The DC coefficients of the 16 luma blocks output by the inverse Walsh-Hadamard transform.
         */
        private final short[] lumaDc = new short[16];

        /**
         * Scratch memory for the motion compensation kernels.
         */
        private final byte[] mcScratch = new byte[Vp8Dsp.SCRATCH_SIZE];

        /**
         * Scratch memory for the loop filter kernels, separate from {@link #mcScratch} because the
         * filter may run on another thread than motion compensation.
         */
        private final byte[] filterScratch = new byte[Vp8Dsp.SCRATCH_SIZE];

        /**
         * Scratch rows for predictions whose reference block lies outside the frame border.
         */
        private final byte[] edgeScratch = new byte[32 * 32];

        /**
         * Scratch storage for the Walsh-Hadamard transform output.
         */
        private final int[] whtScratch = new int[16];

        /**
         * The distance in 1/8 pixel from the macroblock being reconstructed to the left frame edge
         * (non-positive); separate from {@link #mbToLeftEdge}, which belongs to the macroblock being
         * parsed, because the two may run on different threads.
         */
        private int reconToLeftEdge;

        /**
         * The distance in 1/8 pixel from the macroblock being reconstructed to the right frame edge.
         */
        private int reconToRightEdge;

        /**
         * The distance in 1/8 pixel from the macroblock being reconstructed to the top frame edge
         * (non-positive).
         */
        private int reconToTopEdge;

        /**
         * The distance in 1/8 pixel from the macroblock being reconstructed to the bottom frame edge.
         */
        private int reconToBottomEdge;

        /**
         * Predicts one macroblock and adds its residual, like the reconstruction half of libvpx's
         * {@code decode_macroblock}.
         *
         * <p>The coefficient blocks are consumed in the order {@link #decodeTokens} stores them and
         * left zero.
         *
         * @param frame   the frame being decoded
         * @param idx     the macroblock's mode info index
         * @param row     the macroblock row
         * @param col     the macroblock column
         * @param c       the coefficient storage
         * @param slot    the index in {@code c} of the macroblock's first stored block
         * @param eob     the end-of-block storage
         * @param eobBase the index in {@code eob} of the macroblock's block 0
         */
        private void reconstructMacroblock(FrameBuffer frame, int idx, int row, int col, short[] c, int slot, byte[] eob,
                                           int eobBase) {
            var mode = mbMode[idx];
            var skip = mbSkip[idx] != 0;
            var yOff = frame.yOrigin + row * 16 * frame.yStride + col * 16;
            var uvOff = frame.uvOrigin + row * 8 * frame.uvStride + col * 8;
            var yStride = frame.yStride;
            var uvStride = frame.uvStride;
            var y = frame.y;
            if (mbRef[idx] == INTRA_FRAME) {
                var uvMode = mbUvMode[idx];
                IntraPredictor.predictBlock(uvMode, frame.u, uvOff, uvStride, 8, row > 0, col > 0);
                IntraPredictor.predictBlock(uvMode, frame.v, uvOff, uvStride, 8, row > 0, col > 0);
                if (mode != B_PRED) {
                    IntraPredictor.predictBlock(mode, y, yOff, yStride, 16, row > 0, col > 0);
                } else {
                    var src = yOff - yStride + 16;
                    for (var r = 4; r < 16; r += 4) {
                        System.arraycopy(y, src, y, src + r * yStride, 4);
                    }
                    var base = idx * 16;
                    for (var i = 0; i < 16; i++) {
                        var off = yOff + (i >> 2) * 4 * yStride + (i & 3) * 4;
                        IntraPredictor.predictSubblock(bModes[base + i], y, off, yStride);
                        if (!skip) {
                            slot = addResidual(c, slot, eob[eobBase + i], y, off, yStride);
                        }
                    }
                }
            } else {
                reconToLeftEdge = -((col * 16) << 3);
                reconToRightEdge = ((mbCols - 1 - col) * 16) << 3;
                reconToTopEdge = -((row * 16) << 3);
                reconToBottomEdge = ((mbRows - 1 - row) * 16) << 3;
                predictInter(frame, idx, yOff, uvOff);
            }
            if (skip) {
                return;
            }
            if (mode == SPLITMV) {
                for (var i = 0; i < 16; i++) {
                    var off = yOff + (i >> 2) * 4 * yStride + (i & 3) * 4;
                    slot = addResidual(c, slot, eob[eobBase + i], y, off, yStride);
                }
            } else if (mode != B_PRED) {
                var y2Eob = eob[eobBase + 24];
                inverseWht(c, slot, y2Eob);
                if (y2Eob > 0) {
                    slot += 16;
                }
                var dc = lumaDc;
                for (var i = 0; i < 16; i++) {
                    var off = yOff + (i >> 2) * 4 * yStride + (i & 3) * 4;
                    if (eob[eobBase + i] > 1) {
                        c[slot] = dc[i];
                        dsp.idctAdd(c, slot, y, off, yStride);
                        slot += 16;
                    } else {
                        dsp.dcOnlyAdd(dc[i], y, off, yStride);
                    }
                }
            }
            for (var i = 16; i < 24; i++) {
                var b = i & 3;
                var plane = i < 20 ? frame.u : frame.v;
                var off = uvOff + (b >> 1) * 4 * uvStride + (b & 1) * 4;
                slot = addResidual(c, slot, eob[eobBase + i], plane, off, uvStride);
            }
        }

        /**
         * Adds the inverse transform of one stored coefficient block to a 4x4 block of pixels and
         * zeroes the coefficients; a block without coefficients has no storage and adds nothing.
         *
         * @param c      the coefficient storage
         * @param slot   the index in {@code c} of the next stored block
         * @param eob    the block's end-of-block position
         * @param plane  the destination plane
         * @param off    the index of the block's top-left pixel
         * @param stride the plane stride
         * @return the index of the next stored block
         */
        private int addResidual(short[] c, int slot, int eob, byte[] plane, int off, int stride) {
            if (eob > 1) {
                dsp.idctAdd(c, slot, plane, off, stride);
                return slot + 16;
            }
            if (eob == 1) {
                dsp.dcOnlyAdd(c[slot], plane, off, stride);
                c[slot] = 0;
                c[slot + 1] = 0;
                return slot + 16;
            }
            return slot;
        }

        /**
         * Applies the inverse Walsh-Hadamard transform to the Y2 block, storing the DC coefficients of
         * the 16 luma blocks in {@link #lumaDc} and zeroing the Y2 coefficients, like libvpx's
         * {@code vp8_short_inv_walsh4x4_c}.
         *
         * @param c   the coefficient storage
         * @param y2  the index in {@code c} of the Y2 block, when it is stored
         * @param eob the Y2 block's end-of-block position, {@code 0} when it is not stored
         */
        private void inverseWht(short[] c, int y2, int eob) {
            var dc = lumaDc;
            if (eob > 1) {
                var t = whtScratch;
                for (var i = 0; i < 4; i++) {
                    var a1 = c[y2 + i] + c[y2 + 12 + i];
                    var b1 = c[y2 + 4 + i] + c[y2 + 8 + i];
                    var c1 = c[y2 + 4 + i] - c[y2 + 8 + i];
                    var d1 = c[y2 + i] - c[y2 + 12 + i];
                    t[i] = (short) (a1 + b1);
                    t[4 + i] = (short) (c1 + d1);
                    t[8 + i] = (short) (a1 - b1);
                    t[12 + i] = (short) (d1 - c1);
                }
                for (var i = 0; i < 4; i++) {
                    var a1 = t[4 * i] + t[4 * i + 3];
                    var b1 = t[4 * i + 1] + t[4 * i + 2];
                    var c1 = t[4 * i + 1] - t[4 * i + 2];
                    var d1 = t[4 * i] - t[4 * i + 3];
                    dc[4 * i] = (short) ((a1 + b1 + 3) >> 3);
                    dc[4 * i + 1] = (short) ((c1 + d1 + 3) >> 3);
                    dc[4 * i + 2] = (short) ((a1 - b1 + 3) >> 3);
                    dc[4 * i + 3] = (short) ((d1 - c1 + 3) >> 3);
                }
                Arrays.fill(c, y2, y2 + 16, (short) 0);
            } else if (eob == 1) {
                Arrays.fill(dc, (short) ((c[y2] + 3) >> 3));
                c[y2] = 0;
                c[y2 + 1] = 0;
            } else {
                Arrays.fill(dc, (short) 0);
            }
        }

        /**
         * Forms the inter prediction of one macroblock, like libvpx's
         * {@code vp8_build_inter_predictors_mb}.
         *
         * @param frame the frame being decoded
         * @param idx   the macroblock's mode info index
         * @param yOff  the index of the macroblock's top-left luma sample
         * @param uvOff the index of the macroblock's top-left chroma sample
         */
        private void predictInter(FrameBuffer frame, int idx, int yOff, int uvOff) {
            var ref = buffers[refIndex[mbRef[idx]]];
            var clamp = mbNeedClamp[idx] != 0;
            var fullPixel = version == 3;
            if (mbMode[idx] != SPLITMV) {
                var mv = mbMv[idx];
                if (clamp) {
                    mv = clampToUmvBorder(mv);
                }
                var row = Mv.row(mv);
                var col = Mv.col(mv);
                predictBlock(ref.y, frame.y, yOff, frame.yStride, ref.width16, ref.height16, FrameBuffer.LUMA_BORDER,
                        row, col, 16, 16);
                var uvRow = (short) (row + (1 | (row >> 31))) / 2;
                var uvCol = (short) (col + (1 | (col >> 31))) / 2;
                if (fullPixel) {
                    uvRow &= ~7;
                    uvCol &= ~7;
                }
                var uvMv = clampUvToUmvBorder(Mv.pack((short) uvRow, (short) uvCol));
                uvRow = Mv.row(uvMv);
                uvCol = Mv.col(uvMv);
                var w8 = ref.width16 / 2;
                var h8 = ref.height16 / 2;
                predictBlock(ref.u, frame.u, uvOff, frame.uvStride, w8, h8, FrameBuffer.CHROMA_BORDER, uvRow, uvCol, 8, 8);
                predictBlock(ref.v, frame.v, uvOff, frame.uvStride, w8, h8, FrameBuffer.CHROMA_BORDER, uvRow, uvCol, 8, 8);
                return;
            }
            var base = idx * 16;
            var yStride = frame.yStride;
            if (mbPartitioning[idx] < 3) {
                for (var b = 0; b < 4; b++) {
                    var block = (b >> 1) * 8 + (b & 1) * 2;
                    var mv = bMvs[base + block];
                    if (clamp) {
                        mv = clampToUmvBorder(mv);
                    }
                    var off = yOff + (block >> 2) * 4 * yStride + (block & 3) * 4;
                    predictBlock(ref.y, frame.y, off, yStride, ref.width16, ref.height16, FrameBuffer.LUMA_BORDER,
                            Mv.row(mv), Mv.col(mv), 8, 8);
                }
            } else {
                for (var i = 0; i < 16; i += 2) {
                    var mv0 = bMvs[base + i];
                    var mv1 = bMvs[base + i + 1];
                    if (clamp) {
                        mv0 = clampToUmvBorder(mv0);
                        mv1 = clampToUmvBorder(mv1);
                    }
                    var off = yOff + (i >> 2) * 4 * yStride + (i & 3) * 4;
                    if (mv0 == mv1) {
                        predictBlock(ref.y, frame.y, off, yStride, ref.width16, ref.height16, FrameBuffer.LUMA_BORDER,
                                Mv.row(mv0), Mv.col(mv0), 8, 4);
                    } else {
                        predictBlock(ref.y, frame.y, off, yStride, ref.width16, ref.height16, FrameBuffer.LUMA_BORDER,
                                Mv.row(mv0), Mv.col(mv0), 4, 4);
                        predictBlock(ref.y, frame.y, off + 4, yStride, ref.width16, ref.height16,
                                FrameBuffer.LUMA_BORDER, Mv.row(mv1), Mv.col(mv1), 4, 4);
                    }
                }
            }
            var uvMvs = whtScratch;
            for (var i = 0; i < 2; i++) {
                for (var j = 0; j < 2; j++) {
                    var y0 = base + i * 8 + j * 2;
                    var sumRow = Mv.row(bMvs[y0]) + Mv.row(bMvs[y0 + 1]) + Mv.row(bMvs[y0 + 4]) + Mv.row(bMvs[y0 + 5]);
                    sumRow += 4 + ((sumRow >> 31) * 8);
                    var r = sumRow / 8;
                    var sumCol = Mv.col(bMvs[y0]) + Mv.col(bMvs[y0 + 1]) + Mv.col(bMvs[y0 + 4]) + Mv.col(bMvs[y0 + 5]);
                    sumCol += 4 + ((sumCol >> 31) * 8);
                    var c = sumCol / 8;
                    if (fullPixel) {
                        r &= ~7;
                        c &= ~7;
                    }
                    var uvMv = Mv.pack((short) r, (short) c);
                    if (clamp) {
                        uvMv = clampUvToUmvBorder(uvMv);
                    }
                    uvMvs[i * 2 + j] = uvMv;
                }
            }
            var uvStride = frame.uvStride;
            var w8 = ref.width16 / 2;
            var h8 = ref.height16 / 2;
            for (var plane = 0; plane < 2; plane++) {
                var src = plane == 0 ? ref.u : ref.v;
                var dst = plane == 0 ? frame.u : frame.v;
                for (var i = 0; i < 4; i += 2) {
                    var mv0 = uvMvs[i];
                    var mv1 = uvMvs[i + 1];
                    var off = uvOff + (i >> 1) * 4 * uvStride;
                    if (mv0 == mv1) {
                        predictBlock(src, dst, off, uvStride, w8, h8, FrameBuffer.CHROMA_BORDER,
                                Mv.row(mv0), Mv.col(mv0), 8, 4);
                    } else {
                        predictBlock(src, dst, off, uvStride, w8, h8, FrameBuffer.CHROMA_BORDER,
                                Mv.row(mv0), Mv.col(mv0), 4, 4);
                        predictBlock(src, dst, off + 4, uvStride, w8, h8, FrameBuffer.CHROMA_BORDER,
                                Mv.row(mv1), Mv.col(mv1), 4, 4);
                    }
                }
            }
        }

        /**
         * Limits a luma motion vector that points far outside the frame to 16 pixels outside,
         * discarding its fraction, like libvpx's {@code clamp_mv_to_umv_border}; the prediction is
         * unchanged because such a block reads only replicated border samples.
         *
         * @param mv the packed vector
         * @return the limited packed vector
         */
        private int clampToUmvBorder(int mv) {
            var row = Mv.row(mv);
            var col = Mv.col(mv);
            if (col < reconToLeftEdge - (19 << 3)) {
                col = reconToLeftEdge - (16 << 3);
            } else if (col > reconToRightEdge + (18 << 3)) {
                col = reconToRightEdge + (16 << 3);
            }
            if (row < reconToTopEdge - (19 << 3)) {
                row = reconToTopEdge - (16 << 3);
            } else if (row > reconToBottomEdge + (18 << 3)) {
                row = reconToBottomEdge + (16 << 3);
            }
            return Mv.pack((short) row, (short) col);
        }

        /**
         * Limits a chroma motion vector like {@link #clampToUmvBorder(int)}, like libvpx's
         * {@code clamp_uvmv_to_umv_border}.
         *
         * @param mv the packed chroma vector
         * @return the limited packed vector
         */
        private int clampUvToUmvBorder(int mv) {
            var row = Mv.row(mv);
            var col = Mv.col(mv);
            col = 2 * col < reconToLeftEdge - (19 << 3) ? (reconToLeftEdge - (16 << 3)) >> 1 : col;
            col = 2 * col > reconToRightEdge + (18 << 3) ? (reconToRightEdge + (16 << 3)) >> 1 : col;
            row = 2 * row < reconToTopEdge - (19 << 3) ? (reconToTopEdge - (16 << 3)) >> 1 : row;
            row = 2 * row > reconToBottomEdge + (18 << 3) ? (reconToBottomEdge + (16 << 3)) >> 1 : row;
            return Mv.pack((short) row, (short) col);
        }

        /**
         * Predicts one block from a reference plane with the frame's sub-pixel filter.
         *
         * <p>When the block and its filter taps lie inside the reference border, the reference is read
         * directly; otherwise the needed samples are gathered with coordinates clamped to the frame,
         * which is what an infinitely replicated border would hold.
         *
         * @param src       the reference plane
         * @param dst       the destination plane
         * @param dstOff    the index of the destination block's top-left sample
         * @param stride    the row stride shared by both planes
         * @param planeW    the plane width without border
         * @param planeH    the plane height without border
         * @param border    the plane's border width
         * @param mvRow     the vertical displacement in 1/8 pixel
         * @param mvCol     the horizontal displacement in 1/8 pixel
         * @param w         the block width
         * @param h         the block height
         */
        private void predictBlock(byte[] src, byte[] dst, int dstOff, int stride, int planeW, int planeH, int border,
                                  int mvRow, int mvCol, int w, int h) {
            var mx = mvCol & 7;
            var my = mvRow & 7;
            var origin = border * stride + border;
            var rel = dstOff - origin;
            var by = Math.floorDiv(rel, stride);
            var bx = rel - by * stride;
            var sx = bx + (mvCol >> 3);
            var sy = by + (mvRow >> 3);
            if (sx - 2 >= -border && sx + w + 3 <= planeW + border && sy - 2 >= -border && sy + h + 3 <= planeH + border) {
                var srcOff = origin + sy * stride + sx;
                if ((mx | my) == 0) {
                    dsp.copy(src, srcOff, stride, dst, dstOff, stride, w, h);
                } else if (version == 0 || version > 3) {
                    dsp.sixtap(src, srcOff, stride, dst, dstOff, stride, w, h, mx, my, mcScratch);
                } else {
                    dsp.bilinear(src, srcOff, stride, dst, dstOff, stride, w, h, mx, my, mcScratch);
                }
                return;
            }
            var ew = w + 5;
            var tmp = edgeScratch;
            for (var r = 0; r < h + 5; r++) {
                var yy = Math.clamp(sy - 2 + r, 0, planeH - 1);
                var rowOff = origin + yy * stride;
                for (var c = 0; c < ew; c++) {
                    var xx = Math.clamp(sx - 2 + c, 0, planeW - 1);
                    tmp[r * ew + c] = src[rowOff + xx];
                }
            }
            var tmpOff = 2 * ew + 2;
            if ((mx | my) == 0) {
                dsp.copy(tmp, tmpOff, ew, dst, dstOff, stride, w, h);
            } else if (version == 0 || version > 3) {
                dsp.sixtap(tmp, tmpOff, ew, dst, dstOff, stride, w, h, mx, my, mcScratch);
            } else {
                dsp.bilinear(tmp, tmpOff, ew, dst, dstOff, stride, w, h, mx, my, mcScratch);
            }
        }

        /**
         * Applies the loop filter to one macroblock row, like libvpx's
         * {@code vp8_loop_filter_row_normal} and {@code vp8_loop_filter_row_simple}.
         *
         * @param frame the frame being decoded
         * @param row   the macroblock row
         */
        private void filterRow(FrameBuffer frame, int row) {
            for (var col = 0; col < mbCols; col++) {
                filterMacroblock(frame, row, col);
            }
        }

        /**
         * Applies the loop filter to the edges of one macroblock: its left and top edges, shared with
         * the macroblocks to its left and above, and its inner edges.
         *
         * @param frame the frame being decoded
         * @param row   the macroblock row
         * @param col   the macroblock column
         */
        private void filterMacroblock(FrameBuffer frame, int row, int col) {
            var yStride = frame.yStride;
            var uvStride = frame.uvStride;
            var yOff = frame.yOrigin + row * 16 * yStride + col * 16;
            var uvOff = frame.uvOrigin + row * 8 * uvStride + col * 8;
            var sharp = sharpness;
            var idx = (row + 1) * miStride + col + 1;
            var mode = mbMode[idx];
            var skipInner = mode != B_PRED && mode != SPLITMV && mbSkip[idx] != 0;
            var seg = mbSegment[idx];
            var level = filterLevels[seg * 16 + mbRef[idx] * 4 + MODE_LF_CLASS[mode]];
            if (level == 0) {
                return;
            }
            var interior = level >> (sharp > 0 ? 1 : 0);
            interior >>= sharp > 4 ? 1 : 0;
            if (sharp > 0 && interior > 9 - sharp) {
                interior = 9 - sharp;
            }
            if (interior < 1) {
                interior = 1;
            }
            var subLimit = 2 * level + interior;
            var mbLimit = 2 * (level + 2) + interior;
            var edges = (col > 0 ? Vp8Dsp.EDGE_LEFT : 0) | (row > 0 ? Vp8Dsp.EDGE_TOP : 0)
                    | (skipInner ? 0 : Vp8Dsp.EDGE_INNER);
            if (simpleFilter) {
                dsp.filterSimple(frame.y, yOff, yStride, mbLimit, subLimit, edges, filterScratch);
            } else {
                int hev;
                if (keyFrame) {
                    hev = level >= 40 ? 2 : (level >= 15 ? 1 : 0);
                } else {
                    hev = level >= 40 ? 3 : (level >= 20 ? 2 : (level >= 15 ? 1 : 0));
                }
                dsp.filterNormal(frame.y, yOff, yStride, frame.u, frame.v, uvOff, uvStride,
                        mbLimit, subLimit, interior, hev, edges, filterScratch);
            }
        }
    }
}
