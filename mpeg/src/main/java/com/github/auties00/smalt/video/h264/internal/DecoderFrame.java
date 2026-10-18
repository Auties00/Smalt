package com.github.auties00.smalt.video.h264.internal;

import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.internal.plane.Planes;
import com.github.auties00.smalt.internal.task.ParallelBands;

import java.util.Arrays;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Holds one decoded frame of the decoder: padded sample planes, the motion data later pictures
 * read for temporal direct prediction and deblocking, and the reference marking and output state
 * of the decoded picture buffer.
 *
 * <p>Planes carry a border of replicated edge samples ({@link #LUMA_PAD} luma and
 * {@link #CHROMA_PAD} chroma samples on every side) so motion compensation can read slightly
 * outside the picture without clamping. Sample {@code (x, y)} of the luma plane is at
 * {@code lumaOrigin + y * lumaStride + x}.
 *
 * <p>Pictures decoded concurrently wait for the published rows of their references; each wait is
 * declared to the module's pool with {@link ParallelBands#beginBlocking()}, so a pool helper
 * blocked here lets another helper decode meanwhile.
 */
final class DecoderFrame {
    /**
     * The luma border width in samples.
     */
    static final int LUMA_PAD = 32;

    /**
     * The chroma border width in samples.
     */
    static final int CHROMA_PAD = 16;

    /**
     * The macroblock is intra coded.
     */
    static final int MB_INTRA = 1;

    /**
     * The macroblock is Intra_4x4.
     */
    static final int MB_I4X4 = 1 << 1;

    /**
     * The macroblock is Intra_8x8.
     */
    static final int MB_I8X8 = 1 << 2;

    /**
     * The macroblock is Intra_16x16.
     */
    static final int MB_I16X16 = 1 << 3;

    /**
     * The macroblock is I_PCM.
     */
    static final int MB_PCM = 1 << 4;

    /**
     * The macroblock is P_Skip or B_Skip.
     */
    static final int MB_SKIP = 1 << 5;

    /**
     * The macroblock is B_Skip or B_Direct_16x16.
     */
    static final int MB_DIRECT16 = 1 << 6;

    /**
     * The macroblock uses the 8x8 transform.
     */
    static final int MB_T8X8 = 1 << 7;

    /**
     * The shift of the four bits flagging the 8x8 partitions predicted in direct mode.
     */
    static final int MB_DIRECT8_SHIFT = 8;

    /**
     * Every 4x4 block of the macroblock has the same reference pictures and motion vectors.
     */
    static final int MB_UNIFORM = 1 << 12;

    /**
     * Every 4x4 block of each 8x8 quadrant of the macroblock has the quadrant's reference
     * pictures and motion vectors; implied by {@link #MB_UNIFORM}.
     */
    static final int MB_UNIFORM8 = 1 << 13;

    /**
     * The macroblock width of the frame.
     */
    final int mbWidth;

    /**
     * The macroblock height of the frame.
     */
    final int mbHeight;

    /**
     * Whether the frame has chroma planes.
     */
    final boolean hasChroma;

    /**
     * The luma plane including borders.
     */
    final byte[] luma;

    /**
     * The Cb plane including borders, or {@code null} without chroma.
     */
    final byte[] cb;

    /**
     * The Cr plane including borders, or {@code null} without chroma.
     */
    final byte[] cr;

    /**
     * The luma row stride.
     */
    final int lumaStride;

    /**
     * The chroma row stride.
     */
    final int chromaStride;

    /**
     * The index of luma sample {@code (0, 0)}.
     */
    final int lumaOrigin;

    /**
     * The index of chroma sample {@code (0, 0)}.
     */
    final int chromaOrigin;

    /**
     * The motion vectors of each list per 4x4 block, indexed {@code [list][mbAddr * 16 + y * 4 + x]}
     * and packed as {@code (mvy << 16) | (mvx & 0xFFFF)}.
     */
    final int[][] mv;

    /**
     * The reference indices of each list per 8x8 partition, indexed
     * {@code [list][mbAddr * 4 + partition]}; {@code -1} when the list is unused.
     */
    final byte[][] ref;

    /**
     * The identity ({@link #id}) of the reference picture of each list per 8x8 partition, or
     * {@code -1} when the list is unused.
     */
    final int[][] refId;

    /**
     * The macroblock flags ({@code MB_*} constants) per macroblock.
     */
    final int[] mbType;

    /**
     * A number that identifies the picture decoded into this buffer; renewed for every picture.
     */
    int id;

    /**
     * The picture order count of the frame, the smaller of its field counts.
     */
    int poc;

    /**
     * The top field order count.
     */
    int topPoc;

    /**
     * The bottom field order count.
     */
    int bottomPoc;

    /**
     * The {@code frame_num} of the picture.
     */
    int frameNum;

    /**
     * The {@code FrameNumWrap} of a short-term reference frame.
     */
    int frameNumWrap;

    /**
     * The {@code LongTermFrameIdx} of a long-term reference frame.
     */
    int longTermFrameIdx;

    /**
     * The reference marking: 0 unused, {@link #SHORT_TERM} or {@link #LONG_TERM}.
     */
    int reference;

    /**
     * The marking of a short-term reference frame.
     */
    static final int SHORT_TERM = 1;

    /**
     * The marking of a long-term reference frame.
     */
    static final int LONG_TERM = 2;

    /**
     * Whether the frame still waits to be output.
     */
    boolean outputNeeded;

    /**
     * Whether the frame was inferred for a gap in {@code frame_num} and holds no decoded samples.
     */
    boolean nonExisting;

    /**
     * Whether the frame is an IDR picture.
     */
    boolean idr;

    /**
     * Whether the frame is reported as a keyframe.
     */
    boolean keyframe;

    /**
     * Whether the frame's marking included {@code memory_management_control_operation} 5.
     */
    boolean mmco5;

    /**
     * Whether the frame must not be output because it precedes the random access point decoding
     * started at, or its decoding failed.
     */
    boolean discard;

    /**
     * Whether the samples may differ from what the encoder reconstructed: the picture came from
     * an incomplete access unit, a slice failed to decode, macroblocks were concealed, it
     * predicts from a damaged picture, or it is a stand-in for a missing picture. Written before
     * the frame is published as done and read only after that.
     */
    boolean damaged;

    /**
     * The position of the picture in decoding order, from 1, counted over every picture the
     * engine started.
     */
    long decodeIndex;

    /**
     * Whether the picture is a long-term reference that was marked before data of the stream was
     * lost: a lost picture may have given its long-term index to another picture, or removed it,
     * so predicting from it may use the wrong picture even though its own samples are intact.
     * Written before the frame is read as a reference by pictures decoded after the loss.
     */
    boolean staleReference;

    /**
     * The presentation timestamp, or {@link com.github.auties00.smalt.track.Packet#NO_TIMESTAMP}.
     */
    long pts;

    /**
     * The presentation duration, or
     * {@link com.github.auties00.smalt.track.Packet#UNKNOWN_DURATION Packet.UNKNOWN_DURATION}.
     */
    long duration;

    /**
     * The color description of the frame.
     */
    ColorInfo color;

    /**
     * The sequence parameter set the frame was decoded with, which carries its cropping.
     */
    H264Sps sps;

    /**
     * The number of macroblocks decoded so far.
     */
    int decodedMbs;

    /**
     * The number of the coded video sequence (between IDR pictures or memory management
     * control operation 5) the frame belongs to, for output ordering.
     */
    long sequence;

    /**
     * The job decoding the frame on a worker thread, or {@code null}; cleared once its outcome
     * was reported.
     */
    PictureJob job;

    /**
     * The sequence number of the last job submitted when the frame left the buffer; the frame's
     * memory is reused only after every job up to it finished.
     */
    long releaseAfter;

    /**
     * Whether the frame is due for output but not yet handed to the sink, so its memory must
     * not be reused.
     */
    boolean awaitingOutput;

    /**
     * The number of leading luma sample rows that are final: deblocked, including the filtering
     * of the macroblock row below, and replicated into the borders. Other threads read a luma
     * sample row only after this count exceeds it.
     */
    private volatile int readyLumaRows;

    /**
     * The number of leading chroma sample rows that are final, with the meaning of
     * {@link #readyLumaRows}.
     */
    private volatile int readyChromaRows;

    /**
     * The number of leading macroblock rows whose motion (types, reference indices and
     * pictures, motion vectors) is final. Motion is final as soon as a row is decoded, ahead of
     * {@link #readyLumaRows}, which also waits for the deblocking of the samples.
     */
    private volatile int decodedRows;

    /**
     * Whether decoding of the frame finished, successfully or not.
     */
    private volatile boolean done;

    /**
     * Guards the waiting on the progress counters and {@link #done}.
     */
    private final ReentrantLock progressLock = new ReentrantLock();

    /**
     * Signals changes of the progress counters and {@link #done}.
     */
    private final Condition progressChanged = progressLock.newCondition();

    /**
     * Marks the frame as being decoded: no row is final yet.
     */
    void beginDecoding() {
        readyLumaRows = 0;
        readyChromaRows = 0;
        decodedRows = 0;
        done = false;
    }

    /**
     * Publishes that the leading sample rows of the frame are final.
     *
     * @param lumaRows the number of final luma sample rows
     * @param chromaRows the number of final chroma sample rows
     */
    void publishRows(int lumaRows, int chromaRows) {
        if (lumaRows <= readyLumaRows && chromaRows <= readyChromaRows) {
            return;
        }
        progressLock.lock();
        try {
            readyLumaRows = Math.max(readyLumaRows, lumaRows);
            readyChromaRows = Math.max(readyChromaRows, chromaRows);
            progressChanged.signalAll();
        } finally {
            progressLock.unlock();
        }
    }

    /**
     * Publishes that the motion of the leading rows of the frame is final.
     *
     * @param rows the number of decoded macroblock rows
     */
    void publishDecodedRows(int rows) {
        if (rows <= decodedRows) {
            return;
        }
        progressLock.lock();
        try {
            decodedRows = rows;
            progressChanged.signalAll();
        } finally {
            progressLock.unlock();
        }
    }

    /**
     * Publishes that decoding of the frame finished: every row is final.
     */
    void publishDone() {
        progressLock.lock();
        try {
            readyLumaRows = mbHeight * 16;
            readyChromaRows = mbHeight * 8;
            decodedRows = mbHeight;
            done = true;
            progressChanged.signalAll();
        } finally {
            progressLock.unlock();
        }
    }

    /**
     * Returns whether decoding of the frame finished.
     *
     * @return {@code true} once {@link #publishDone()} was called
     */
    boolean isDone() {
        return done;
    }

    /**
     * Waits until at least the given number of leading luma sample rows are final.
     *
     * @param rows the number of luma rows needed, at most the coded height
     */
    void awaitLumaRows(int rows) {
        if (readyLumaRows >= rows) {
            return;
        }
        ParallelBands.beginBlocking();
        progressLock.lock();
        try {
            while (readyLumaRows < rows) {
                progressChanged.awaitUninterruptibly();
            }
        } finally {
            progressLock.unlock();
            ParallelBands.endBlocking();
        }
    }

    /**
     * Waits until at least the given number of leading chroma sample rows are final.
     *
     * @param rows the number of chroma rows needed, at most the coded chroma height
     */
    void awaitChromaRows(int rows) {
        if (readyChromaRows >= rows) {
            return;
        }
        ParallelBands.beginBlocking();
        progressLock.lock();
        try {
            while (readyChromaRows < rows) {
                progressChanged.awaitUninterruptibly();
            }
        } finally {
            progressLock.unlock();
            ParallelBands.endBlocking();
        }
    }

    /**
     * Waits until the motion of at least the given number of leading rows is final.
     *
     * @param rows the number of rows needed, clamped to the frame height
     */
    void awaitDecodedRows(int rows) {
        if (decodedRows >= rows) {
            return;
        }
        var needed = Math.min(rows, mbHeight);
        ParallelBands.beginBlocking();
        progressLock.lock();
        try {
            while (decodedRows < needed) {
                progressChanged.awaitUninterruptibly();
            }
        } finally {
            progressLock.unlock();
            ParallelBands.endBlocking();
        }
    }

    /**
     * Waits until decoding of the frame finished.
     */
    void awaitDone() {
        if (done) {
            return;
        }
        ParallelBands.beginBlocking();
        progressLock.lock();
        try {
            while (!done) {
                progressChanged.awaitUninterruptibly();
            }
        } finally {
            progressLock.unlock();
            ParallelBands.endBlocking();
        }
    }

    /**
     * Allocates a frame.
     *
     * @param mbWidth the width in macroblocks
     * @param mbHeight the height in macroblocks
     * @param hasChroma whether to allocate 4:2:0 chroma planes
     */
    DecoderFrame(int mbWidth, int mbHeight, boolean hasChroma) {
        this.mbWidth = mbWidth;
        this.mbHeight = mbHeight;
        this.hasChroma = hasChroma;
        var mbCount = mbWidth * mbHeight;
        this.lumaStride = mbWidth * 16 + 2 * LUMA_PAD;
        this.luma = new byte[lumaStride * (mbHeight * 16 + 2 * LUMA_PAD)];
        this.lumaOrigin = LUMA_PAD * lumaStride + LUMA_PAD;
        this.chromaStride = mbWidth * 8 + 2 * CHROMA_PAD;
        if (hasChroma) {
            var size = chromaStride * (mbHeight * 8 + 2 * CHROMA_PAD);
            this.cb = new byte[size];
            this.cr = new byte[size];
        } else {
            this.cb = null;
            this.cr = null;
        }
        this.chromaOrigin = CHROMA_PAD * chromaStride + CHROMA_PAD;
        this.mv = new int[2][mbCount * 16];
        this.ref = new byte[2][mbCount * 4];
        this.refId = new int[2][mbCount * 4];
        this.mbType = new int[mbCount];
    }

    /**
     * Returns the number of bytes a frame of the given size occupies, for limit checks before
     * allocation.
     *
     * @param mbWidth the width in macroblocks
     * @param mbHeight the height in macroblocks
     * @param hasChroma whether chroma planes are allocated
     * @return the approximate allocation size in bytes
     */
    static long allocationSize(int mbWidth, int mbHeight, boolean hasChroma) {
        long luma = (mbWidth * 16L + 2 * LUMA_PAD) * (mbHeight * 16L + 2 * LUMA_PAD);
        long chroma = hasChroma ? 2 * (mbWidth * 8L + 2 * CHROMA_PAD) * (mbHeight * 8L + 2 * CHROMA_PAD) : 0;
        long motion = (long) mbWidth * mbHeight * (2 * 16 * 4 + 2 * 4 + 2 * 4 * 4 + 4);
        return luma + chroma + motion;
    }

    /**
     * Returns whether the frame is marked as used for reference.
     *
     * @return {@code true} for short-term and long-term reference frames
     */
    boolean isReference() {
        return reference != 0;
    }

    /**
     * Returns whether the frame is a long-term reference frame.
     *
     * @return {@code true} when marked long-term
     */
    boolean isLongTerm() {
        return reference == LONG_TERM;
    }

    /**
     * Returns whether the frame is a short-term reference frame.
     *
     * @return {@code true} when marked short-term
     */
    boolean isShortTerm() {
        return reference == SHORT_TERM;
    }

    /**
     * Fills the whole frame with mid-gray samples and clears its motion data, used for frames
     * that must exist but carry no decoded content.
     */
    void fillGray() {
        Arrays.fill(luma, (byte) 128);
        if (hasChroma) {
            Arrays.fill(cb, (byte) 128);
            Arrays.fill(cr, (byte) 128);
        }
        clearMotion();
    }

    /**
     * Resets the motion data to "intra, no references".
     */
    void clearMotion() {
        Arrays.fill(mv[0], 0);
        Arrays.fill(mv[1], 0);
        Arrays.fill(ref[0], (byte) -1);
        Arrays.fill(ref[1], (byte) -1);
        Arrays.fill(refId[0], -1);
        Arrays.fill(refId[1], -1);
        Arrays.fill(mbType, MB_INTRA);
    }

    /**
     * Copies the samples of another frame of the same size into this one.
     *
     * @param source the frame to copy
     */
    void copySamplesFrom(DecoderFrame source) {
        System.arraycopy(source.luma, 0, luma, 0, luma.length);
        if (hasChroma && source.hasChroma) {
            System.arraycopy(source.cb, 0, cb, 0, cb.length);
            System.arraycopy(source.cr, 0, cr, 0, cr.length);
        }
    }

    /**
     * Replicates the edge samples of a band of sample rows into the left and right borders, and
     * into the top or bottom border when the band touches that edge.
     *
     * @param firstLumaRow the first luma sample row of the band
     * @param endLumaRow one past the last luma sample row of the band
     * @param firstChromaRow the first chroma sample row of the band
     * @param endChromaRow one past the last chroma sample row of the band
     */
    void extendBorders(int firstLumaRow, int endLumaRow, int firstChromaRow, int endChromaRow) {
        extendPlane(luma, lumaStride, lumaOrigin, mbWidth * 16, mbHeight * 16, LUMA_PAD, firstLumaRow, endLumaRow);
        if (hasChroma) {
            extendPlane(cb, chromaStride, chromaOrigin, mbWidth * 8, mbHeight * 8, CHROMA_PAD, firstChromaRow, endChromaRow);
            extendPlane(cr, chromaStride, chromaOrigin, mbWidth * 8, mbHeight * 8, CHROMA_PAD, firstChromaRow, endChromaRow);
        }
    }

    /**
     * Replicates the edge samples of a band of rows of one plane into its borders.
     *
     * @param plane the samples, edited in place
     * @param stride the row stride
     * @param origin the index of sample {@code (0, 0)}
     * @param width the plane width without borders
     * @param height the plane height without borders
     * @param pad the border width
     * @param firstRow the first row of the band
     * @param endRow one past the last row of the band
     */
    private static void extendPlane(byte[] plane, int stride, int origin, int width, int height, int pad,
                                    int firstRow, int endRow) {
        Planes.extendSides(plane, origin + firstRow * stride, stride, width, endRow - firstRow, pad, pad);
        if (firstRow == 0) {
            Planes.extendTop(plane, origin - pad, stride, width + 2 * pad, pad);
        }
        if (endRow == height) {
            Planes.extendBottom(plane, origin - pad + (height - 1) * stride, stride, width + 2 * pad, pad);
        }
    }
}
