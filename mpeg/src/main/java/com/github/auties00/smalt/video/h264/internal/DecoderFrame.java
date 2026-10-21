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
     * The macroblock is a field macroblock: it belongs to a field picture or to a field
     * macroblock pair of an MBAFF frame.
     */
    static final int MB_FIELD = 1 << 14;

    /**
     * The top field, as a bit of a field set and as a picture structure.
     */
    static final int TOP = 1;

    /**
     * The bottom field, as a bit of a field set and as a picture structure.
     */
    static final int BOTTOM = 2;

    /**
     * Both fields: the structure of a frame picture.
     */
    static final int FRAME = 3;

    /**
     * The coding of a frame decoded from a frame picture without macroblock-adaptive frame/field
     * decoding ({@code PicCodingStruct} FRM).
     */
    static final int CODED_FRAME = 0;

    /**
     * The coding of a frame decoded from an MBAFF frame picture ({@code PicCodingStruct} AFRM).
     */
    static final int CODED_MBAFF = 1;

    /**
     * The coding of a frame decoded from one or two field pictures ({@code PicCodingStruct}
     * FLD).
     */
    static final int CODED_FIELDS = 2;

    /**
     * The reference kind of a frame or complementary field pair in a {@link #refId} entry.
     */
    static final int REF_FRAME = 0;

    /**
     * The reference kind of a top field in a {@link #refId} entry.
     */
    static final int REF_TOP = 1;

    /**
     * The reference kind of a bottom field in a {@link #refId} entry.
     */
    static final int REF_BOTTOM = 2;

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
     * The bit depth of the luma samples, 8 to 14.
     */
    final int bitDepthLuma;

    /**
     * The bit depth of the chroma samples, 8 to 14.
     */
    final int bitDepthChroma;

    /**
     * Whether the samples are held in the 16-bit planes ({@link #luma16}, {@link #cb16} and
     * {@link #cr16}) because the luma bit depth or, in a frame with chroma, the chroma bit
     * depth exceeds 8, rather than in the 8-bit planes.
     */
    final boolean wide;

    /**
     * The 8-bit luma plane including borders, or {@code null} for a {@link #wide} frame.
     */
    final byte[] luma;

    /**
     * The 8-bit Cb plane including borders, or {@code null} without chroma or for a
     * {@link #wide} frame.
     */
    final byte[] cb;

    /**
     * The 8-bit Cr plane including borders, or {@code null} without chroma or for a
     * {@link #wide} frame.
     */
    final byte[] cr;

    /**
     * The 16-bit luma plane including borders, or {@code null} for a frame that is not
     * {@link #wide}.
     */
    final short[] luma16;

    /**
     * The 16-bit Cb plane including borders, or {@code null} without chroma or for a frame
     * that is not {@link #wide}.
     */
    final short[] cb16;

    /**
     * The 16-bit Cr plane including borders, or {@code null} without chroma or for a frame
     * that is not {@link #wide}.
     */
    final short[] cr16;

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
     * The motion vectors of each list per 4x4 block, indexed {@code [list][mbIndex * 16 + y * 4 + x]}
     * and packed as {@code (mvy << 16) | (mvx & 0xFFFF)}; field macroblocks hold field vectors.
     *
     * <p>Per-macroblock arrays are indexed by the macroblock's position in the frame grid,
     * {@code mbIndex = row * mbWidth + column}: a frame macroblock at frame macroblock row
     * {@code R} has row {@code R}, and the field macroblock at field macroblock row {@code r} of
     * parity {@code p} (of a field picture or an MBAFF field macroblock pair) has row
     * {@code 2 * r + p}.
     */
    final int[][] mv;

    /**
     * The reference indices of each list per 8x8 partition, indexed
     * {@code [list][mbIndex * 4 + partition]}; {@code -1} when the list is unused.
     */
    final byte[][] ref;

    /**
     * The reference picture of each list per 8x8 partition, {@code (id << 2) | kind} with the
     * {@link #id} of the reference frame and a {@code REF_*} kind, or {@code -1} when the list is
     * unused.
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
     * The reference marking of the top field: 0 unused, {@link #SHORT_TERM} or
     * {@link #LONG_TERM}.
     */
    int refTop;

    /**
     * The reference marking of the bottom field, with the values of {@link #refTop}.
     */
    int refBottom;

    /**
     * The fields the frame holds or is receiving: {@link #FRAME} for a frame picture, the
     * parity bits of its coded fields otherwise.
     */
    volatile int fields;

    /**
     * How the frame was coded: {@link #CODED_FRAME}, {@link #CODED_MBAFF} or
     * {@link #CODED_FIELDS}.
     */
    int codingStruct;

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
     * The number of losses of stream data the engine had counted when the picture before the
     * frame's first picture started, which tells whether data was lost next to a field that
     * receives no other field.
     */
    long lossMark;

    /**
     * Whether the field a frame holding a single field lacks was filled by
     * {@link #completeMissingField()}.
     */
    boolean missingFieldFilled;

    /**
     * The presentation timestamp, or {@link com.github.auties00.smalt.track.Packet#NO_TIMESTAMP}.
     */
    long pts;

    /**
     * The number of the packet the frame's first picture started in, counted by the engine.
     */
    long packet;

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
     * The fields whose decoding finished, successfully or not, as {@link #TOP} and
     * {@link #BOTTOM} bits.
     */
    private volatile int doneFields;

    /**
     * Guards the waiting on the progress counters and {@link #doneFields}.
     */
    private final ReentrantLock progressLock = new ReentrantLock();

    /**
     * Signals changes of the progress counters and {@link #doneFields}.
     */
    private final Condition progressChanged = progressLock.newCondition();

    /**
     * Marks a picture of the frame as being decoded: no row of the frame is final any more, and
     * the fields of {@code structure} are not done.
     *
     * <p>Only a frame picture, which the frame was coded as when {@link #codingStruct} is
     * {@link #CODED_FRAME} or {@link #CODED_MBAFF}, publishes its rows while it is decoded; the
     * rows of a frame coded as two fields become final together, once both fields are done.
     *
     * @param structure the structure of the picture: {@link #FRAME}, {@link #TOP} or
     * {@link #BOTTOM}
     * @param newFrame whether the picture starts a new frame, rather than being the second field
     * of a frame whose first field was decoded
     */
    void beginDecoding(int structure, boolean newFrame) {
        progressLock.lock();
        try {
            readyLumaRows = 0;
            readyChromaRows = 0;
            decodedRows = 0;
            doneFields = newFrame ? 0 : doneFields & ~structure;
        } finally {
            progressLock.unlock();
        }
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
     * Publishes that decoding of a picture of the frame finished; once every field the frame
     * holds is done, every row is final.
     *
     * @param structure the fields the finished picture covers: {@link #FRAME}, {@link #TOP} or
     * {@link #BOTTOM}
     */
    void publishDone(int structure) {
        progressLock.lock();
        try {
            var done = doneFields | structure;
            doneFields = done;
            var held = fields;
            if ((done & held) == held) {
                readyLumaRows = mbHeight * 16;
                readyChromaRows = mbHeight * 8;
                decodedRows = mbHeight;
            }
            progressChanged.signalAll();
        } finally {
            progressLock.unlock();
        }
    }

    /**
     * Publishes that decoding of every field of the frame finished.
     */
    void publishDone() {
        publishDone(FRAME);
    }

    /**
     * Returns whether decoding of every field the frame holds finished.
     *
     * @return {@code true} once {@link #publishDone(int)} covered every field of {@link #fields}
     */
    boolean isDone() {
        var held = fields;
        return (doneFields & held) == held;
    }

    /**
     * Returns whether decoding of some fields finished.
     *
     * @param structure the fields: {@link #FRAME}, {@link #TOP} or {@link #BOTTOM}
     * @return {@code true} once every field of {@code structure} is done
     */
    boolean isDone(int structure) {
        return (doneFields & structure) == structure;
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
     * Waits until at least the given number of leading luma sample rows of one field are final.
     *
     * @param parity 0 for the top field, 1 for the bottom field
     * @param rows the number of field rows needed, at most the field height
     */
    void awaitFieldLumaRows(int parity, int rows) {
        if (codingStruct != CODED_FIELDS) {
            awaitLumaRows(Math.min(2 * rows, mbHeight * 16));
        } else {
            awaitDone(parity + 1);
        }
    }

    /**
     * Waits until at least the given number of leading chroma sample rows of one field are
     * final.
     *
     * @param parity 0 for the top field, 1 for the bottom field
     * @param rows the number of field rows needed, at most the chroma field height
     */
    void awaitFieldChromaRows(int parity, int rows) {
        if (codingStruct != CODED_FIELDS) {
            awaitChromaRows(Math.min(2 * rows, mbHeight * 8));
        } else {
            awaitDone(parity + 1);
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
     * Waits until decoding of every field the frame holds finished.
     */
    void awaitDone() {
        if (isDone()) {
            return;
        }
        ParallelBands.beginBlocking();
        progressLock.lock();
        try {
            while (!isDone()) {
                progressChanged.awaitUninterruptibly();
            }
        } finally {
            progressLock.unlock();
            ParallelBands.endBlocking();
        }
    }

    /**
     * Waits until decoding of some fields finished.
     *
     * @param structure the fields: {@link #FRAME}, {@link #TOP} or {@link #BOTTOM}
     */
    void awaitDone(int structure) {
        if (isDone(structure)) {
            return;
        }
        ParallelBands.beginBlocking();
        progressLock.lock();
        try {
            while (!isDone(structure)) {
                progressChanged.awaitUninterruptibly();
            }
        } finally {
            progressLock.unlock();
            ParallelBands.endBlocking();
        }
    }

    /**
     * Allocates a frame of 8-bit samples.
     *
     * @param mbWidth the width in macroblocks
     * @param mbHeight the height in macroblocks
     * @param hasChroma whether to allocate 4:2:0 chroma planes
     */
    DecoderFrame(int mbWidth, int mbHeight, boolean hasChroma) {
        this(mbWidth, mbHeight, hasChroma, 8, 8);
    }

    /**
     * Allocates a frame, with 16-bit planes when the luma bit depth or, with chroma planes, the
     * chroma bit depth exceeds 8.
     *
     * @param mbWidth the width in macroblocks
     * @param mbHeight the height in macroblocks
     * @param hasChroma whether to allocate 4:2:0 chroma planes
     * @param bitDepthLuma the luma bit depth, 8 to 14
     * @param bitDepthChroma the chroma bit depth, 8 to 14
     */
    DecoderFrame(int mbWidth, int mbHeight, boolean hasChroma, int bitDepthLuma, int bitDepthChroma) {
        this.mbWidth = mbWidth;
        this.mbHeight = mbHeight;
        this.hasChroma = hasChroma;
        this.bitDepthLuma = bitDepthLuma;
        this.bitDepthChroma = bitDepthChroma;
        this.wide = bitDepthLuma > 8 || (hasChroma && bitDepthChroma > 8);
        var mbCount = mbWidth * mbHeight;
        this.lumaStride = mbWidth * 16 + 2 * LUMA_PAD;
        var lumaSize = lumaStride * (mbHeight * 16 + 2 * LUMA_PAD);
        this.lumaOrigin = LUMA_PAD * lumaStride + LUMA_PAD;
        this.chromaStride = mbWidth * 8 + 2 * CHROMA_PAD;
        var chromaSize = hasChroma ? chromaStride * (mbHeight * 8 + 2 * CHROMA_PAD) : 0;
        if (wide) {
            this.luma = null;
            this.cb = null;
            this.cr = null;
            this.luma16 = new short[lumaSize];
            this.cb16 = hasChroma ? new short[chromaSize] : null;
            this.cr16 = hasChroma ? new short[chromaSize] : null;
        } else {
            this.luma = new byte[lumaSize];
            this.cb = hasChroma ? new byte[chromaSize] : null;
            this.cr = hasChroma ? new byte[chromaSize] : null;
            this.luma16 = null;
            this.cb16 = null;
            this.cr16 = null;
        }
        this.chromaOrigin = CHROMA_PAD * chromaStride + CHROMA_PAD;
        this.mv = new int[2][mbCount * 16];
        this.ref = new byte[2][mbCount * 4];
        this.refId = new int[2][mbCount * 4];
        this.mbType = new int[mbCount];
        this.fields = FRAME;
    }

    /**
     * Returns the number of bytes a frame of the given size occupies, for limit checks before
     * allocation.
     *
     * @param mbWidth the width in macroblocks
     * @param mbHeight the height in macroblocks
     * @param hasChroma whether chroma planes are allocated
     * @param wide whether the planes hold 16-bit samples
     * @return the approximate allocation size in bytes
     */
    static long allocationSize(int mbWidth, int mbHeight, boolean hasChroma, boolean wide) {
        long luma = (mbWidth * 16L + 2 * LUMA_PAD) * (mbHeight * 16L + 2 * LUMA_PAD);
        long chroma = hasChroma ? 2 * (mbWidth * 8L + 2 * CHROMA_PAD) * (mbHeight * 8L + 2 * CHROMA_PAD) : 0;
        long motion = (long) mbWidth * mbHeight * (2 * 16 * 4 + 2 * 4 + 2 * 4 * 4 + 4);
        return (luma + chroma) * (wide ? 2 : 1) + motion;
    }

    /**
     * Returns the value of a mid-gray sample.
     *
     * @param bitDepth the bit depth of the samples
     * @return {@code 1 << (bitDepth - 1)}
     */
    static int gray(int bitDepth) {
        return 1 << (bitDepth - 1);
    }

    /**
     * Returns whether a field of the frame is marked as used for reference.
     *
     * @return {@code true} when either field is a short-term or long-term reference
     */
    boolean isReference() {
        return refTop != 0 || refBottom != 0;
    }

    /**
     * Returns whether the frame or complementary field pair is a long-term reference: both its
     * fields are marked long-term.
     *
     * @return {@code true} when both fields are marked long-term
     */
    boolean isLongTerm() {
        return refTop == LONG_TERM && refBottom == LONG_TERM;
    }

    /**
     * Returns whether the frame or complementary field pair is a short-term reference: both its
     * fields are marked short-term.
     *
     * @return {@code true} when both fields are marked short-term
     */
    boolean isShortTerm() {
        return refTop == SHORT_TERM && refBottom == SHORT_TERM;
    }

    /**
     * Returns whether at least one field of the frame is marked short-term.
     *
     * @return {@code true} when a field is a short-term reference
     */
    boolean anyShortTerm() {
        return refTop == SHORT_TERM || refBottom == SHORT_TERM;
    }

    /**
     * Returns whether at least one field of the frame is marked long-term.
     *
     * @return {@code true} when a field is a long-term reference
     */
    boolean anyLongTerm() {
        return refTop == LONG_TERM || refBottom == LONG_TERM;
    }

    /**
     * Returns the reference marking of one field.
     *
     * @param parity 0 for the top field, 1 for the bottom field
     * @return 0, {@link #SHORT_TERM} or {@link #LONG_TERM}
     */
    int marking(int parity) {
        return parity == 0 ? refTop : refBottom;
    }

    /**
     * Returns the reference marking of a picture structure of the frame: of the frame when
     * {@code structure} is {@link #FRAME}, which is a marking only when both fields share it.
     *
     * @param structure {@link #TOP}, {@link #BOTTOM} or {@link #FRAME}
     * @return 0, {@link #SHORT_TERM} or {@link #LONG_TERM}
     */
    int structureMarking(int structure) {
        return switch (structure) {
            case TOP -> refTop;
            case BOTTOM -> refBottom;
            default -> refTop == refBottom ? refTop : 0;
        };
    }

    /**
     * Sets the reference marking of the fields of a picture structure.
     *
     * @param structure {@link #TOP}, {@link #BOTTOM} or {@link #FRAME}
     * @param marking 0, {@link #SHORT_TERM} or {@link #LONG_TERM}
     */
    void mark(int structure, int marking) {
        if ((structure & TOP) != 0) {
            refTop = marking;
        }
        if ((structure & BOTTOM) != 0) {
            refBottom = marking;
        }
    }

    /**
     * Returns the picture order count of one field.
     *
     * @param parity 0 for the top field, 1 for the bottom field
     * @return the field order count
     */
    int fieldPoc(int parity) {
        return parity == 0 ? topPoc : bottomPoc;
    }

    /**
     * Returns the picture order count of a picture structure of the frame: a field's count, or
     * for {@link #FRAME} the smaller of the counts of the fields the frame holds.
     *
     * @param structure {@link #TOP}, {@link #BOTTOM} or {@link #FRAME}
     * @return the picture order count
     */
    int structurePoc(int structure) {
        return switch (structure) {
            case TOP -> topPoc;
            case BOTTOM -> bottomPoc;
            default -> poc;
        };
    }

    /**
     * Recomputes {@link #poc} from the field counts of the fields the frame holds.
     */
    void updatePoc() {
        var held = fields;
        poc = held == TOP ? topPoc : held == BOTTOM ? bottomPoc : Math.min(topPoc, bottomPoc);
    }

    /**
     * Fills the whole frame with mid-gray samples and clears its motion data, used for frames
     * that must exist but carry no decoded content.
     */
    void fillGray() {
        if (wide) {
            Arrays.fill(luma16, (short) gray(bitDepthLuma));
            if (hasChroma) {
                Arrays.fill(cb16, (short) gray(bitDepthChroma));
                Arrays.fill(cr16, (short) gray(bitDepthChroma));
            }
        } else {
            Arrays.fill(luma, (byte) 128);
            if (hasChroma) {
                Arrays.fill(cb, (byte) 128);
                Arrays.fill(cr, (byte) 128);
            }
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
     * Copies the samples of another frame of the same size and bit depths into this one.
     *
     * @param source the frame to copy
     */
    void copySamplesFrom(DecoderFrame source) {
        if (wide) {
            System.arraycopy(source.luma16, 0, luma16, 0, luma16.length);
            if (hasChroma && source.hasChroma) {
                System.arraycopy(source.cb16, 0, cb16, 0, cb16.length);
                System.arraycopy(source.cr16, 0, cr16, 0, cr16.length);
            }
            return;
        }
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
        if (wide) {
            extendPlane(luma16, lumaStride, lumaOrigin, mbWidth * 16, mbHeight * 16, LUMA_PAD, firstLumaRow,
                    endLumaRow);
            if (hasChroma) {
                extendPlane(cb16, chromaStride, chromaOrigin, mbWidth * 8, mbHeight * 8, CHROMA_PAD, firstChromaRow,
                        endChromaRow);
                extendPlane(cr16, chromaStride, chromaOrigin, mbWidth * 8, mbHeight * 8, CHROMA_PAD, firstChromaRow,
                        endChromaRow);
            }
            return;
        }
        extendPlane(luma, lumaStride, lumaOrigin, mbWidth * 16, mbHeight * 16, LUMA_PAD, firstLumaRow, endLumaRow);
        if (hasChroma) {
            extendPlane(cb, chromaStride, chromaOrigin, mbWidth * 8, mbHeight * 8, CHROMA_PAD, firstChromaRow, endChromaRow);
            extendPlane(cr, chromaStride, chromaOrigin, mbWidth * 8, mbHeight * 8, CHROMA_PAD, firstChromaRow, endChromaRow);
        }
    }

    /**
     * Replicates the edge samples of every row of one field into the left and right borders,
     * and the frame's first row into the top border (for the top field) or its last row into the
     * bottom border (for the bottom field).
     *
     * @param parity 0 for the top field, 1 for the bottom field
     */
    void extendFieldBorders(int parity) {
        if (wide) {
            extendFieldPlane(luma16, lumaStride, lumaOrigin, mbWidth * 16, mbHeight * 16, LUMA_PAD, parity);
            if (hasChroma) {
                extendFieldPlane(cb16, chromaStride, chromaOrigin, mbWidth * 8, mbHeight * 8, CHROMA_PAD, parity);
                extendFieldPlane(cr16, chromaStride, chromaOrigin, mbWidth * 8, mbHeight * 8, CHROMA_PAD, parity);
            }
            return;
        }
        extendFieldPlane(luma, lumaStride, lumaOrigin, mbWidth * 16, mbHeight * 16, LUMA_PAD, parity);
        if (hasChroma) {
            extendFieldPlane(cb, chromaStride, chromaOrigin, mbWidth * 8, mbHeight * 8, CHROMA_PAD, parity);
            extendFieldPlane(cr, chromaStride, chromaOrigin, mbWidth * 8, mbHeight * 8, CHROMA_PAD, parity);
        }
    }

    /**
     * Replicates the edge samples of the rows of one field of a plane into its borders.
     *
     * @param plane the samples, edited in place
     * @param stride the row stride
     * @param origin the index of sample {@code (0, 0)}
     * @param width the plane width without borders
     * @param height the plane height without borders, even
     * @param pad the border width
     * @param parity 0 for the top field, 1 for the bottom field
     */
    private static void extendFieldPlane(byte[] plane, int stride, int origin, int width, int height, int pad,
                                         int parity) {
        Planes.extendSides(plane, origin + parity * stride, 2 * stride, width, height / 2, pad, pad);
        if (parity == 0) {
            Planes.extendTop(plane, origin - pad, stride, width + 2 * pad, pad);
        } else {
            Planes.extendBottom(plane, origin - pad + (height - 1) * stride, stride, width + 2 * pad, pad);
        }
    }

    /**
     * Replicates the edge samples of the rows of one field of a 16-bit plane into its borders.
     *
     * @param plane the samples, edited in place
     * @param stride the row stride
     * @param origin the index of sample {@code (0, 0)}
     * @param width the plane width without borders
     * @param height the plane height without borders, even
     * @param pad the border width
     * @param parity 0 for the top field, 1 for the bottom field
     */
    private static void extendFieldPlane(short[] plane, int stride, int origin, int width, int height, int pad,
                                         int parity) {
        Planes.extendSides(plane, origin + parity * stride, 2 * stride, width, height / 2, pad, pad);
        if (parity == 0) {
            Planes.extendTop(plane, origin - pad, stride, width + 2 * pad, pad);
        } else {
            Planes.extendBottom(plane, origin - pad + (height - 1) * stride, stride, width + 2 * pad, pad);
        }
    }

    /**
     * Completes a frame that holds a single field and will receive no other: fills the missing
     * field with mid-gray samples, replicates them into the borders, gives its macroblocks the
     * motion of intra macroblocks and publishes that field as done, so that output, pictures
     * predicting from the frame and co-located motion lookups see a gray intra field where no
     * field was decoded, and waits on the missing field end. Does nothing for a frame holding
     * both fields or one already completed.
     *
     * <p>The decoded field may still be in progress on another thread: the missing field's rows
     * and borders are disjoint from what decoding the other field writes.
     */
    void completeMissingField() {
        var held = fields;
        if (held == FRAME || missingFieldFilled) {
            return;
        }
        missingFieldFilled = true;
        var parity = held == TOP ? 1 : 0;
        fillFieldGray(parity);
        extendFieldBorders(parity);
        for (var row = parity; row < mbHeight; row += 2) {
            var first = row * mbWidth;
            var last = first + mbWidth;
            Arrays.fill(mbType, first, last, MB_INTRA | MB_FIELD);
            for (var list = 0; list < 2; list++) {
                Arrays.fill(mv[list], first * 16, last * 16, 0);
                Arrays.fill(ref[list], first * 4, last * 4, (byte) -1);
                Arrays.fill(refId[list], first * 4, last * 4, -1);
            }
        }
        publishDone(FRAME - held);
    }

    /**
     * Fills one field with mid-gray samples, for the missing field of a frame whose other field
     * was decoded alone.
     *
     * @param parity 0 for the top field, 1 for the bottom field
     */
    void fillFieldGray(int parity) {
        if (wide) {
            fillFieldPlane(luma16, lumaStride, lumaOrigin, mbWidth * 16, mbHeight * 16, parity, gray(bitDepthLuma));
            if (hasChroma) {
                var value = gray(bitDepthChroma);
                fillFieldPlane(cb16, chromaStride, chromaOrigin, mbWidth * 8, mbHeight * 8, parity, value);
                fillFieldPlane(cr16, chromaStride, chromaOrigin, mbWidth * 8, mbHeight * 8, parity, value);
            }
            return;
        }
        fillFieldPlane(luma, lumaStride, lumaOrigin, mbWidth * 16, mbHeight * 16, parity);
        if (hasChroma) {
            fillFieldPlane(cb, chromaStride, chromaOrigin, mbWidth * 8, mbHeight * 8, parity);
            fillFieldPlane(cr, chromaStride, chromaOrigin, mbWidth * 8, mbHeight * 8, parity);
        }
    }

    /**
     * Fills the rows of one field of a plane with mid-gray samples.
     *
     * @param plane the samples, edited in place
     * @param stride the row stride
     * @param origin the index of sample {@code (0, 0)}
     * @param width the plane width without borders
     * @param height the plane height without borders, even
     * @param parity 0 for the top field, 1 for the bottom field
     */
    private static void fillFieldPlane(byte[] plane, int stride, int origin, int width, int height, int parity) {
        for (var y = parity; y < height; y += 2) {
            var at = origin + y * stride;
            Arrays.fill(plane, at, at + width, (byte) 128);
        }
    }

    /**
     * Fills the rows of one field of a 16-bit plane with one sample value.
     *
     * @param plane the samples, edited in place
     * @param stride the row stride
     * @param origin the index of sample {@code (0, 0)}
     * @param width the plane width without borders
     * @param height the plane height without borders, even
     * @param parity 0 for the top field, 1 for the bottom field
     * @param value the sample value
     */
    private static void fillFieldPlane(short[] plane, int stride, int origin, int width, int height, int parity,
                                       int value) {
        for (var y = parity; y < height; y += 2) {
            var at = origin + y * stride;
            Arrays.fill(plane, at, at + width, (short) value);
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

    /**
     * Replicates the edge samples of a band of rows of one 16-bit plane into its borders.
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
    private static void extendPlane(short[] plane, int stride, int origin, int width, int height, int pad,
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
