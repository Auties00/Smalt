package com.github.auties00.smalt.video.h264.internal;

import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.internal.plane.Planes;
import com.github.auties00.smalt.internal.sei.DisplaySei;
import com.github.auties00.smalt.internal.sei.FilmGrainParameters;
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
 * {@link #chromaPad} chroma samples on every side) so motion compensation can read slightly
 * outside the picture without clamping. Sample {@code (x, y)} of the luma plane is at
 * {@code lumaOrigin + y * lumaStride + x}.
 *
 * <p>The color planes of a picture coded with {@code separate_colour_plane_flag} are decoded
 * into three monochrome frames ({@link #planes}) that this frame presents as one 4:4:4 frame;
 * the decoded picture buffer holds only the presenting frame, which hands its picture identity
 * and structure, its progress and its motion resets on to the planes.
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
     * The macroblock is the SI macroblock of an SI slice, an Intra_4x4 macroblock that is also
     * flagged {@link #MB_I4X4}.
     */
    static final int MB_SI = 1 << 15;

    /**
     * The macroblock belongs to an SP or SI slice, which the deblocking filter treats as intra
     * coded (ITU-T H.264 8.7.2.1).
     */
    static final int MB_SWITCHING = 1 << 16;

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
     * The {@code chroma_format_idc} of the planes: 0 for monochrome, 1 for 4:2:0, 2 for 4:2:2,
     * 3 for 4:4:4.
     */
    final int chromaFormat;

    /**
     * The chroma width of a macroblock, {@code MbWidthC}: 16 for 4:4:4, 8 otherwise.
     */
    final int chromaMbWidth;

    /**
     * The chroma height of a macroblock, {@code MbHeightC}: 16 for 4:2:2 and 4:4:4, 8 otherwise.
     */
    final int chromaMbHeight;

    /**
     * The chroma width of the frame in samples, {@code mbWidth * chromaMbWidth}.
     */
    final int chromaWidth;

    /**
     * The chroma height of the frame in samples, {@code mbHeight * chromaMbHeight}.
     */
    final int chromaHeight;

    /**
     * The chroma border width in samples: {@link #LUMA_PAD} for 4:4:4, whose chroma is
     * predicted like luma, {@link #CHROMA_PAD} otherwise.
     */
    final int chromaPad;

    /**
     * The three monochrome frames the color planes of a frame coded with
     * {@code separate_colour_plane_flag} are decoded into, Y, Cb and Cr, or {@code null}; this
     * frame's planes and motion arrays are theirs.
     */
    final DecoderFrame[] planes;

    /**
     * The frame whose color plane this frame holds, or {@code null} for a frame that is not a
     * color plane; set once by the owner's constructor.
     */
    private DecoderFrame owner;

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
     * Whether the {@link #nonExisting} frame may stand in for a lost picture: its sequence allows no gaps in
     * {@code frame_num}, or a loss was reported between the previous picture and the gap.
     */
    boolean lostStandIn;

    /**
     * The table mapping the luma samples of a depth view component to uniformly quantized disparity, or
     * {@code null}.
     */
    int[] depthLut;

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
     * Whether every slice of the frame decoded so far is an I slice.
     */
    boolean allIntra;

    /**
     * The {@code PicOrderCnt_offset} of SMPTE RDD 5 1.5.2.1.1 for the frame: the
     * {@code idr_pic_id} of the last IDR frame in decoding order, or 0 after a non-IDR frame made
     * of I slices only.
     */
    int grainPocOffset;

    /**
     * The film grain characteristics message of the frame's access units, or {@code null}.
     */
    FilmGrainParameters filmGrain;

    /**
     * The frame packing arrangement message of the frame's access units, or {@code null}.
     */
    DisplaySei.FramePackingMessage framePacking;

    /**
     * The display orientation message of the frame's access units, or {@code null}.
     */
    DisplaySei.OrientationMessage orientation;

    /**
     * The {@code cc_data} constructs of the caption messages of the frame's access units, or {@code null}.
     */
    byte[] closedCaptions;

    /**
     * The {@code pic_struct} of the picture timing message of the frame's first access unit, or
     * -1.
     */
    int picStruct = -1;

    /**
     * The sequence parameter set the frame was decoded with, which carries its cropping.
     */
    H264Sps sps;

    /**
     * The key pairing a primary frame with the auxiliary frame of its access unit: the decoding
     * index of the primary frame.
     */
    long pairKey;

    /**
     * The extension describing the alpha an auxiliary engine decodes for this frame, or
     * {@code null}.
     */
    H264SpsExtension auxiliary;

    /**
     * The view order index of the view the frame belongs to, 0 for the base view.
     */
    int voIdx;

    /**
     * The {@code view_id} of the view the frame belongs to.
     */
    int viewId;

    /**
     * The {@code DQId} of the scalable layer the frame was decoded at, or -1 for a frame the
     * scalable decoder did not decode.
     */
    int scalableLayer = -1;

    /**
     * Whether the frame stays in the buffer until its access unit ends, because the view
     * components of other views of the access unit may predict from it.
     */
    boolean interViewHold;


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
        if (planes != null) {
            syncPlanes();
            for (var plane : planes) {
                plane.beginDecoding(structure, newFrame);
            }
        }
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
        if (planes != null) {
            syncPlanes();
            for (var plane : planes) {
                plane.publishDone(structure);
            }
        }
        progressLock.lock();
        try {
            var done = doneFields | structure;
            doneFields = done;
            var held = fields;
            if ((done & held) == held) {
                readyLumaRows = mbHeight * 16;
                readyChromaRows = chromaHeight;
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
            awaitChromaRows(Math.min(2 * rows, chromaHeight));
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
     * @param chromaFormat the {@code chroma_format_idc} of the planes: 0 monochrome, 1 4:2:0,
     * 2 4:2:2 or 3 4:4:4
     */
    DecoderFrame(int mbWidth, int mbHeight, int chromaFormat) {
        this(mbWidth, mbHeight, chromaFormat, 8, 8);
    }

    /**
     * Allocates a frame, with 16-bit planes when the luma bit depth or, with chroma planes, the
     * chroma bit depth exceeds 8.
     *
     * @param mbWidth the width in macroblocks
     * @param mbHeight the height in macroblocks
     * @param chromaFormat the {@code chroma_format_idc} of the planes: 0 monochrome, 1 4:2:0,
     * 2 4:2:2 or 3 4:4:4
     * @param bitDepthLuma the luma bit depth, 8 to 14
     * @param bitDepthChroma the chroma bit depth, 8 to 14
     */
    DecoderFrame(int mbWidth, int mbHeight, int chromaFormat, int bitDepthLuma, int bitDepthChroma) {
        this(mbWidth, mbHeight, chromaFormat, bitDepthLuma, bitDepthChroma, null);
    }

    /**
     * Allocates the frame of a picture whose three color planes are coded separately
     * ({@code separate_colour_plane_flag}): each plane is decoded into a monochrome frame of its
     * own, at the luma bit depth, and this 4:4:4 frame presents their samples.
     *
     * @param mbWidth the width in macroblocks
     * @param mbHeight the height in macroblocks
     * @param bitDepth the bit depth of every plane, {@code BitDepthY}, 8 to 14
     * @return the frame
     */
    static DecoderFrame separatePlanes(int mbWidth, int mbHeight, int bitDepth) {
        var planes = new DecoderFrame[3];
        for (var p = 0; p < 3; p++) {
            planes[p] = new DecoderFrame(mbWidth, mbHeight, 0, bitDepth, bitDepth, null);
        }
        return new DecoderFrame(mbWidth, mbHeight, 3, bitDepth, bitDepth, planes);
    }

    /**
     * Allocates a frame, or one that presents the samples and motion of separately coded color
     * planes.
     *
     * @param mbWidth the width in macroblocks
     * @param mbHeight the height in macroblocks
     * @param chromaFormat the {@code chroma_format_idc} of the planes
     * @param bitDepthLuma the luma bit depth, 8 to 14
     * @param bitDepthChroma the chroma bit depth, 8 to 14
     * @param planes the monochrome frames of the color planes, or {@code null}
     */
    private DecoderFrame(int mbWidth, int mbHeight, int chromaFormat, int bitDepthLuma, int bitDepthChroma,
                         DecoderFrame[] planes) {
        this.mbWidth = mbWidth;
        this.mbHeight = mbHeight;
        this.hasChroma = chromaFormat != 0;
        this.chromaFormat = chromaFormat;
        this.chromaMbWidth = chromaFormat == 3 ? 16 : 8;
        this.chromaMbHeight = chromaMbHeight(chromaFormat);
        this.chromaWidth = mbWidth * chromaMbWidth;
        this.chromaHeight = mbHeight * chromaMbHeight;
        this.chromaPad = chromaFormat == 3 ? LUMA_PAD : CHROMA_PAD;
        this.bitDepthLuma = bitDepthLuma;
        this.bitDepthChroma = bitDepthChroma;
        this.wide = bitDepthLuma > 8 || (hasChroma && bitDepthChroma > 8);
        this.planes = planes;
        var mbCount = mbWidth * mbHeight;
        this.lumaStride = mbWidth * 16 + 2 * LUMA_PAD;
        var lumaSize = lumaStride * (mbHeight * 16 + 2 * LUMA_PAD);
        this.lumaOrigin = LUMA_PAD * lumaStride + LUMA_PAD;
        this.chromaStride = chromaWidth + 2 * chromaPad;
        var chromaSize = hasChroma ? chromaStride * (chromaHeight + 2 * chromaPad) : 0;
        if (planes != null) {
            this.luma = planes[0].luma;
            this.cb = planes[1].luma;
            this.cr = planes[2].luma;
            this.luma16 = planes[0].luma16;
            this.cb16 = planes[1].luma16;
            this.cr16 = planes[2].luma16;
        } else if (wide) {
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
        this.chromaOrigin = chromaPad * chromaStride + chromaPad;
        if (planes != null) {
            this.mv = planes[0].mv;
            this.ref = planes[0].ref;
            this.refId = planes[0].refId;
            this.mbType = planes[0].mbType;
            for (var plane : planes) {
                plane.owner = this;
            }
        } else {
            this.mv = new int[2][mbCount * 16];
            this.ref = new byte[2][mbCount * 4];
            this.refId = new int[2][mbCount * 4];
            this.mbType = new int[mbCount];
        }
        this.fields = FRAME;
    }

    /**
     * Returns the frame a color plane of this frame is decoded into.
     *
     * @param colorPlane the {@code colour_plane_id}, 0 for frames whose planes are not coded
     * separately
     * @return the plane's monochrome frame, or this frame when its planes are not coded separately
     */
    DecoderFrame plane(int colorPlane) {
        return planes == null ? this : planes[colorPlane];
    }

    /**
     * {@return the frame of the decoded picture buffer this frame belongs to: the frame whose color
     * plane it holds, or this frame}
     */
    DecoderFrame owner() {
        var holder = owner;
        return holder == null ? this : holder;
    }

    /**
     * Copies the identity and structure of the picture into the frames of its color planes,
     * which later pictures read through their reference lists.
     */
    private void syncPlanes() {
        for (var plane : planes) {
            plane.id = id;
            plane.fields = fields;
            plane.codingStruct = codingStruct;
            plane.missingFieldFilled = missingFieldFilled;
        }
    }

    /**
     * Returns the number of bytes a frame of the given size occupies, for limit checks before
     * allocation.
     *
     * @param mbWidth the width in macroblocks
     * @param mbHeight the height in macroblocks
     * @param chromaFormat the {@code chroma_format_idc} of the planes: 0 monochrome, 1 4:2:0,
     * 2 4:2:2 or 3 4:4:4
     * @param separatePlanes whether the three color planes of 4:4:4 are coded separately, each
     * with motion data of its own
     * @param wide whether the planes hold 16-bit samples
     * @return the approximate allocation size in bytes
     */
    static long allocationSize(int mbWidth, int mbHeight, int chromaFormat, boolean separatePlanes, boolean wide) {
        long luma = (mbWidth * 16L + 2 * LUMA_PAD) * (mbHeight * 16L + 2 * LUMA_PAD);
        var pad = chromaFormat == 3 ? LUMA_PAD : CHROMA_PAD;
        long chromaRows = (long) mbHeight * chromaMbHeight(chromaFormat) + 2 * pad;
        long chromaColumns = mbWidth * (chromaFormat == 3 ? 16L : 8L) + 2 * pad;
        long chroma = chromaFormat != 0 ? 2 * chromaColumns * chromaRows : 0;
        long motion = (long) mbWidth * mbHeight * (2 * 16 * 4 + 2 * 4 + 2 * 4 * 4 + 4);
        return (luma + chroma) * (wide ? 2 : 1) + motion * (separatePlanes ? 3 : 1);
    }

    /**
     * Returns the chroma height of a macroblock, {@code MbHeightC}.
     *
     * @param chromaFormat the {@code chroma_format_idc}: 0 monochrome, 1 4:2:0, 2 4:2:2 or 3 4:4:4
     * @return 16 for 4:2:2 and 4:4:4, 8 otherwise
     */
    static int chromaMbHeight(int chromaFormat) {
        return chromaFormat >= 2 ? 16 : 8;
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
        if (planes != null) {
            for (var plane : planes) {
                plane.clearMotion();
            }
            return;
        }
        Arrays.fill(mv[0], 0);
        Arrays.fill(mv[1], 0);
        Arrays.fill(ref[0], (byte) -1);
        Arrays.fill(ref[1], (byte) -1);
        Arrays.fill(refId[0], -1);
        Arrays.fill(refId[1], -1);
        Arrays.fill(mbType, MB_INTRA);
    }

    /**
     * Copies the motion data of another frame of the same size into this one.
     *
     * @param source the frame whose motion vectors, reference indices and macroblock types are copied
     */
    void copyMotionFrom(DecoderFrame source) {
        for (var list = 0; list < 2; list++) {
            System.arraycopy(source.mv[list], 0, mv[list], 0, mv[list].length);
            System.arraycopy(source.ref[list], 0, ref[list], 0, ref[list].length);
            System.arraycopy(source.refId[list], 0, refId[list], 0, refId[list].length);
        }
        System.arraycopy(source.mbType, 0, mbType, 0, mbType.length);
    }

    /**
     * Copies the samples of another frame of the same size, chroma format and bit depths into
     * this one.
     *
     * @param source the frame to copy
     */
    void copySamplesFrom(DecoderFrame source) {
        if (wide) {
            System.arraycopy(source.luma16, 0, luma16, 0, luma16.length);
            if (hasChroma && source.chromaFormat == chromaFormat) {
                System.arraycopy(source.cb16, 0, cb16, 0, cb16.length);
                System.arraycopy(source.cr16, 0, cr16, 0, cr16.length);
            }
            return;
        }
        System.arraycopy(source.luma, 0, luma, 0, luma.length);
        if (hasChroma && source.chromaFormat == chromaFormat) {
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
                extendPlane(cb16, chromaStride, chromaOrigin, chromaWidth, chromaHeight, chromaPad, firstChromaRow,
                        endChromaRow);
                extendPlane(cr16, chromaStride, chromaOrigin, chromaWidth, chromaHeight, chromaPad, firstChromaRow,
                        endChromaRow);
            }
            return;
        }
        extendPlane(luma, lumaStride, lumaOrigin, mbWidth * 16, mbHeight * 16, LUMA_PAD, firstLumaRow, endLumaRow);
        if (hasChroma) {
            extendPlane(cb, chromaStride, chromaOrigin, chromaWidth, chromaHeight, chromaPad, firstChromaRow,
                    endChromaRow);
            extendPlane(cr, chromaStride, chromaOrigin, chromaWidth, chromaHeight, chromaPad, firstChromaRow,
                    endChromaRow);
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
                extendFieldPlane(cb16, chromaStride, chromaOrigin, chromaWidth, chromaHeight, chromaPad, parity);
                extendFieldPlane(cr16, chromaStride, chromaOrigin, chromaWidth, chromaHeight, chromaPad, parity);
            }
            return;
        }
        extendFieldPlane(luma, lumaStride, lumaOrigin, mbWidth * 16, mbHeight * 16, LUMA_PAD, parity);
        if (hasChroma) {
            extendFieldPlane(cb, chromaStride, chromaOrigin, chromaWidth, chromaHeight, chromaPad, parity);
            extendFieldPlane(cr, chromaStride, chromaOrigin, chromaWidth, chromaHeight, chromaPad, parity);
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
        if (planes != null) {
            for (var plane : planes) {
                plane.fields = held;
                plane.missingFieldFilled = false;
                plane.completeMissingField();
            }
            publishDone(FRAME - held);
            return;
        }
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
                fillFieldPlane(cb16, chromaStride, chromaOrigin, chromaWidth, chromaHeight, parity, value);
                fillFieldPlane(cr16, chromaStride, chromaOrigin, chromaWidth, chromaHeight, parity, value);
            }
            return;
        }
        fillFieldPlane(luma, lumaStride, lumaOrigin, mbWidth * 16, mbHeight * 16, parity);
        if (hasChroma) {
            fillFieldPlane(cb, chromaStride, chromaOrigin, chromaWidth, chromaHeight, parity);
            fillFieldPlane(cr, chromaStride, chromaOrigin, chromaWidth, chromaHeight, parity);
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
