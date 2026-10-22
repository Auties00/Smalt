package com.github.auties00.smalt.video.h264.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.Chromaticity;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.ContentLightLevel;
import com.github.auties00.smalt.image.MasteringDisplay;
import com.github.auties00.smalt.image.TransferCharacteristics;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.internal.nal.NalUnits;
import com.github.auties00.smalt.internal.sei.DisplaySei;
import com.github.auties00.smalt.internal.sei.FilmGrainParameters;
import com.github.auties00.smalt.internal.sei.SeiPersistence;
import com.github.auties00.smalt.internal.task.ParallelBands;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.video.FieldOrder;
import com.github.auties00.smalt.video.FrameSideData;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Decodes a sequence of H.264 NAL units into frames in output order: parameter set management,
 * picture boundary detection, picture order count derivation, reference picture marking and
 * list construction, the decoded picture buffer with its output ("bumping") process, error
 * concealment, and the dispatch of slice data to {@link SliceDecoder} (ITU-T H.264 7.4.1.2.4,
 * 8.2 and Annex C).
 *
 * <p>The engine is not thread-safe. Frames reach the {@link FrameSink} in output order; a frame
 * handed to the sink is recycled once the sink returns.
 *
 * <p>A picture is a frame, an MBAFF frame or a field. The two fields of a frame coded as field
 * pictures share one {@link DecoderFrame}: the first field is stored in the buffer as soon as it
 * is decoded and is held back from output until the next picture shows whether it is the
 * second field of the same frame (a complementary field pair, ITU-T H.264 3.30 and 3.31). A
 * field that receives no second field is output with its missing field mid-gray. Reference
 * marking, list construction and memory management address the fields of the buffered frames
 * individually when the current picture is a field (ITU-T H.264 8.2.4 and 8.2.5).
 *
 * <p>With several threads, each picture, once it has received all its slices, is decoded by a
 * {@link PictureJob} on the module's shared pool while the calling thread parses the following
 * NAL units; a job reading a reference picture waits only for the rows its motion vectors reach,
 * or, for references coded as fields or MBAFF frames, for the fields it reads to be done.
 * Frames due for output wait in a queue until their job finishes, so output order never holds
 * back later pictures, and frame buffers return to the pool only once no unfinished job can read
 * them and they were output.
 *
 * <p>Every frame carries a damage flag (see {@link DecoderFrame#damaged}): it is set for a picture
 * received in a packet declared incomplete, for a picture whose slices failed or left
 * macroblocks to conceal, for a picture whose partitions predict from a damaged or missing
 * picture (the stand-ins for a gap in {@code frame_num} are damaged, and so, for prediction, are
 * the long-term references marked before a loss, since a lost picture may have given their index
 * to another picture), and for a picture whose reference list modification names a picture that
 * does not exist. A loss is a packet declared incomplete, a {@link #noteLoss()}, or a gap in
 * {@code frame_num} in a sequence that does not allow gaps; a sequence that allows them may skip
 * {@code frame_num} values on purpose, so only the transport can tell that long-term references
 * went stale. A frame holding a single field is damaged when a loss was reported between the
 * start of the picture before its field and the start of the picture after it, since the lost
 * data may have held its other field; a reference field then stands in for both fields of its
 * frame, so that later reference lists keep the positions the encoder gave them. After a loss,
 * the frames of the fields that follow are damaged as long as consecutive fields share
 * {@code frame_num} and reference status, since a lost field may have shifted which fields pair
 * up. A B picture is damaged when its initial lists leave out a stand-in for a lost picture
 * (with {@code pic_order_cnt_type} 0, ITU-T H.264 8.2.4.2.3), since the encoder's lists held
 * the lost picture there. The engine also tracks whether a key frame is needed: from any damage,
 * any slice it had to drop and any stream failure, until a later picture in decoding order is
 * output intact.
 *
 * <p>In low-delay mode every packet is one complete access unit: its picture is finished when the
 * packet ends instead of when the next picture starts, and in Baseline profile sequences, which
 * cannot reorder pictures, it is output at once whatever the sequence's reordering limits say.
 *
 * @implNote This implementation starts decoding at the first IDR picture, the first picture
 * carrying a recovery point SEI message, or the first picture whose first slice is an I slice.
 * Pictures before that point are skipped. When decoding starts on a non-IDR picture, pictures
 * that follow it in decoding order but precede it in output order are decoded (their missing
 * references are substituted) but not output.
 */
public final class H264DecoderEngine {
    /**
     * Receives decoded frames in output order.
     */
    @FunctionalInterface
    public interface FrameSink {
        /**
         * Handles one frame; the frame's buffers are reused after the call returns.
         *
         * @param frame the frame, with its presentation metadata
         */
        void output(OutputFrame frame);
    }

    /**
     * Describes a decoded frame handed to a {@link FrameSink}: its samples are in the 16-bit
     * planes when the luma bit depth or, with chroma, the chroma bit depth exceeds 8, and in the
     * 8-bit planes otherwise.
     *
     * @param luma the 8-bit luma plane including borders, or {@code null} for a deeper frame
     * @param cb the 8-bit Cb plane including borders, or {@code null} for monochrome or a deeper
     * frame
     * @param cr the 8-bit Cr plane including borders, or {@code null} for monochrome or a deeper
     * frame
     * @param luma16 the 16-bit luma plane including borders, or {@code null} for an 8-bit frame
     * @param cb16 the 16-bit Cb plane including borders, or {@code null} for monochrome or an
     * 8-bit frame
     * @param cr16 the 16-bit Cr plane including borders, or {@code null} for monochrome or an
     * 8-bit frame
     * @param bitDepthLuma the luma bit depth, 8 to 14
     * @param bitDepthChroma the chroma bit depth, 8 to 14
     * @param lumaStride the luma row stride
     * @param chromaStride the chroma row stride
     * @param lumaOrigin the index of luma sample {@code (0, 0)}
     * @param chromaOrigin the index of chroma sample {@code (0, 0)}
     * @param sps the sequence parameter set, which carries size and cropping
     * @param color the color description: the one of {@code sps} with the
     * high-dynamic-range metadata of the SEI messages
     * @param pts the presentation timestamp, or {@link Packet#NO_TIMESTAMP}
     * @param duration the presentation duration, or {@link Packet#UNKNOWN_DURATION}
     * @param keyframe whether the frame is an IDR or recovery point I frame
     * @param poc the picture order count
     * @param damaged whether the samples may differ from the encoder's reconstruction (see
     * {@link DecoderFrame#damaged})
     * @param filmGrain the film grain characteristics message that applies to the frame, or
     * {@code null}
     * @param grainOffset the {@code pic_offset} of SMPTE RDD 5 1.5.2.1.1 that seeds the film grain
     * of the frame
     * @param sideData the display information of the frame
     * @param picStruct the {@code pic_struct} of the frame's picture timing message, or -1
     */
    public record OutputFrame(byte[] luma, byte[] cb, byte[] cr, short[] luma16, short[] cb16, short[] cr16,
                              int bitDepthLuma, int bitDepthChroma, int lumaStride, int chromaStride, int lumaOrigin,
                              int chromaOrigin, H264Sps sps, ColorInfo color, long pts, long duration, boolean keyframe,
                              int poc, boolean damaged, FilmGrainParameters filmGrain, int grainOffset,
                              FrameSideData sideData, int picStruct) {
        /**
         * {@return whether the samples are in the 16-bit planes}
         */
        public boolean wide() {
            return luma16 != null;
        }

        /**
         * {@return whether the frame has chroma planes}
         */
        public boolean hasChroma() {
            return cb != null || cb16 != null;
        }
    }

    /**
     * The resource limits.
     */
    private final MediaLimits limits;

    /**
     * The frame receiver.
     */
    private final FrameSink sink;

    /**
     * The sequence parameter sets by id.
     */
    private final H264Sps[] spsTable = new H264Sps[32];

    /**
     * The raw picture parameter set payloads by id.
     */
    private final byte[][] ppsRaw = new byte[256][];

    /**
     * The parsed picture parameter sets by id, valid for {@link H264Pps#sps}.
     */
    private final H264Pps[] ppsTable = new H264Pps[256];

    /**
     * The unescaped payload of the NAL unit being processed.
     */
    private byte[] rbsp = new byte[1024];

    /**
     * The header of the slice being processed.
     */
    private H264SliceHeader header = new H264SliceHeader();

    /**
     * The header of the first slice of the current picture.
     */
    private H264SliceHeader pictureHeader = new H264SliceHeader();

    /**
     * The sequence parameter set of the frames in the buffer.
     */
    private H264Sps activeSps;

    /**
     * The frames of the decoded picture buffer, excluding the current picture.
     */
    private final List<DecoderFrame> dpb = new ArrayList<>();

    /**
     * Frames that can be reused.
     */
    private final List<DecoderFrame> pool = new ArrayList<>();

    /**
     * The number of frames the buffer holds.
     */
    private int dpbSize;

    /**
     * The frame of the picture being decoded, or {@code null} between pictures.
     */
    private DecoderFrame current;

    /**
     * The structure of the picture being decoded: {@link DecoderFrame#FRAME},
     * {@link DecoderFrame#TOP} or {@link DecoderFrame#BOTTOM}.
     */
    private int currentStructure = DecoderFrame.FRAME;

    /**
     * Whether the picture being decoded is the second field of {@link #current}, whose first
     * field was the previous picture.
     */
    private boolean secondField;

    /**
     * Whether the picture being decoded is an MBAFF frame.
     */
    private boolean currentMbaff;

    /**
     * The picture order count of the picture being decoded: of the field for a field picture,
     * {@code PicOrderCnt(CurrPic)} in ITU-T H.264 terms.
     */
    private int currentPoc;

    /**
     * The frame whose first field was the last picture decoded, held back from output until the
     * next picture shows whether it is the second field of the same frame; {@code null} when the
     * last picture was not a field or the pairing is decided.
     */
    private DecoderFrame pendingPair;

    /**
     * Whether the first field of {@link #pendingPair} is a reference field.
     */
    private boolean pendingPairReference;

    /**
     * The number of losses of stream data reported so far, by {@link #noteLoss()} or packets
     * declared incomplete.
     */
    private long lossEvents;

    /**
     * The value of {@link #lossEvents} when the last picture started.
     */
    private long lossesAtLastPicture;

    /**
     * Whether the last picture was a field.
     */
    private boolean lastPictureField;

    /**
     * The {@code frame_num} of the last picture.
     */
    private int lastPictureFrameNum;

    /**
     * Whether the last picture was a reference picture.
     */
    private boolean lastPictureReference;

    /**
     * Whether the pairing of the fields being decoded is in doubt: a loss was reported since
     * the picture before the run of fields that share {@code frame_num} and reference status with
     * the current one, so a lost field may have shifted which fields pair up.
     */
    private boolean pairingSuspect;

    /**
     * The next unique picture identity.
     */
    private int nextId = 1;

    /**
     * The most pictures decoded at once, 1 when pictures are decoded on the calling thread.
     */
    private final int threads;

    /**
     * The most pictures started and not yet retired: twice {@link #threads}, so that a helper
     * that finishes a picture finds the next one already queued while the calling thread parses
     * or outputs, and 1 when pictures are decoded on the calling thread.
     *
     * @implNote This implementation takes 0.85 times the round time of a queue as deep as the thread
     * count when decoding a 720p stream with 8 threads; a deeper queue gained no significant further time.
     */
    private final int queueDepth;

    /**
     * Whether pictures are decoded by {@link PictureJob}s on the shared pool rather than on the
     * calling thread.
     */
    private final boolean parallel;

    /**
     * The picture decoder used on the calling thread when {@link #parallel} is {@code false}.
     */
    private final PictureDecoder inlineDecoder = new PictureDecoder();

    /**
     * The slice task reused on the calling thread, referring to the engine buffers.
     */
    private final SliceTask inlineTask;

    /**
     * The idle picture decoders of the jobs.
     */
    private final Queue<PictureDecoder> idleDecoders = new ConcurrentLinkedQueue<>();

    /**
     * The jobs created and not yet known to be finished, oldest first: the started ones, then
     * the job of the picture being received, if any.
     */
    private final ArrayDeque<PictureJob> inFlight = new ArrayDeque<>();

    /**
     * The slices of the current picture coded as data partitions whose partition C has not
     * arrived, in arrival order. Their partition A was processed; the partitions B and C that
     * follow attach to them by {@code slice_id} and {@code colour_plane_id}. On a single thread
     * they are decoded when their partition C arrives or the picture finishes; with several
     * threads they already belong to the picture's job.
     */
    private final List<SliceTask> pendingPartitions = new ArrayList<>();

    /**
     * The keys ({@link #partitionKey(int, int)}) of the slices whose partitions B and C are
     * dropped silently: the partitions A dropped on purpose since the current picture started (a
     * repeated slice, or a slice of a picture decoding does not start at) and the slices of the
     * picture already complete, whose partitions may be repeated. The first
     * {@link #droppedPartitionCount} entries are used; the array grows when it is full.
     */
    private long[] droppedPartitions = new long[16];

    /**
     * The number of used entries of {@link #droppedPartitions}.
     */
    private int droppedPartitionCount;

    /**
     * The picture parameter set of the last slice header parsed, which describes the header of
     * a partition B or C whose partition A is no longer waiting.
     */
    private H264Pps lastSlicePps;

    /**
     * The job of the picture being received, not started yet, or {@code null}.
     */
    private PictureJob currentJob;

    /**
     * The color planes (bit {@code colour_plane_id}, bit 0 when the planes are not coded
     * separately) of which a slice given to {@link #currentJob} starts at each macroblock index,
     * by {@link PictureState#mbIndex}; with several threads the slices are decoded later, so a
     * slice that repeats one of the picture is recognized by its first macroblock instead of the
     * decoded state the calling thread consults.
     */
    private byte[] sliceStarts = new byte[0];

    /**
     * The sequence number of the last created job.
     */
    private long submittedJobs;

    /**
     * The sequence number up to which every job finished.
     */
    private long finishedJobs;

    /**
     * Frames removed from the buffer whose memory may still be read by unfinished jobs.
     */
    private final List<DecoderFrame> pendingRelease = new ArrayList<>();

    /**
     * The frames due for output, in output order, whose decoding may still be running.
     */
    private final ArrayDeque<DecoderFrame> outputQueue = new ArrayDeque<>();

    /**
     * The next unique slice number.
     */
    private int nextSliceNum;

    /**
     * The slice number of the current picture's first slice.
     */
    private int firstSliceNum;

    /**
     * The number of the coded video sequence being decoded.
     */
    private long sequence;

    /**
     * The macroblock to slice group map of the current slice, or {@code null}.
     */
    private int[] sliceGroupMap;

    /**
     * Whether decoding reached a random access point.
     */
    private boolean started;

    /**
     * The picture order count of the picture decoding started at, when it was not an IDR
     * picture; pictures of the same sequence with a lower count are not output.
     */
    private int startPoc = Integer.MIN_VALUE;

    /**
     * Whether the next picture is the non-IDR picture decoding starts at.
     */
    private boolean startPending;

    /**
     * The sequence number of {@link #startPoc}.
     */
    private long startSequence = -1;

    /**
     * The mastering display of the last mastering display color volume SEI message, or
     * {@code null} before the first.
     */
    private MasteringDisplay masteringDisplay;

    /**
     * The light level of the last content light level SEI message, or {@code null} before the
     * first.
     */
    private ContentLightLevel contentLightLevel;

    /**
     * Whether a recovery point SEI message was seen for the next picture.
     */
    private boolean recoveryPointPending;

    /**
     * The film grain characteristics message of the next picture's access unit, or {@code null}.
     */
    private FilmGrainParameters pendingFilmGrain;

    /**
     * The frame packing arrangement message of the next picture's access unit, or {@code null}.
     */
    private DisplaySei.FramePackingMessage pendingFramePacking;

    /**
     * The display orientation message of the next picture's access unit, or {@code null}.
     */
    private DisplaySei.OrientationMessage pendingOrientation;

    /**
     * The payload of the picture timing message of the next picture's access unit, followed by
     * zero padding; parsed when the picture's sequence parameter set is known.
     */
    private byte[] pendingTiming = new byte[64 + H264BitReader.PADDING];

    /**
     * The size of {@link #pendingTiming}, or -1 when the next picture's access unit carried none.
     */
    private int pendingTimingSize = -1;

    /**
     * The {@code preferred_transfer_characteristics} of the alternative transfer characteristics
     * message in effect for the coded video sequence, or -1.
     */
    private int preferredTransfer = -1;

    /**
     * Whether the next picture's access unit carried an alternative transfer characteristics
     * message.
     */
    private boolean pendingTransfer;

    /**
     * The {@code PicOrderCnt_offset} of SMPTE RDD 5 1.5.2.1.1 after the last frame in decoding
     * order.
     */
    private int grainPocOffset;

    /**
     * The value of {@link #grainPocOffset} when the current frame started.
     */
    private int grainPocOffsetAtFrameStart;

    /**
     * Which film grain characteristics message applies to each frame, in output order.
     */
    private final SeiPersistence<FilmGrainParameters> grainPersistence = new SeiPersistence<>();

    /**
     * Which frame packing arrangement message applies to each frame, in output order.
     */
    private final SeiPersistence<DisplaySei.FramePackingMessage> packingPersistence = new SeiPersistence<>();

    /**
     * Which display orientation message applies to each frame, in output order.
     */
    private final SeiPersistence<DisplaySei.OrientationMessage> orientationPersistence = new SeiPersistence<>();

    /**
     * The reader of SEI message payloads.
     */
    private final H264BitReader seiReader = new H264BitReader();

    /**
     * Whether the {@code frame_num} of the previous reference picture is known.
     */
    private boolean prevRefFrameNumValid;

    /**
     * The {@code PrevRefFrameNum}.
     */
    private int prevRefFrameNum;

    /**
     * The {@code prevPicOrderCntMsb} of picture order count type 0.
     */
    private int prevPocMsb;

    /**
     * The {@code prevPicOrderCntLsb} of picture order count type 0.
     */
    private int prevPocLsb;

    /**
     * The {@code prevFrameNumOffset} of picture order count types 1 and 2.
     */
    private int prevFrameNumOffset;

    /**
     * The {@code frame_num} of the previous picture, for picture order count types 1 and 2.
     */
    private int prevFrameNum;

    /**
     * Whether the previous picture carried memory management control operation 5.
     */
    private boolean prevMmco5;

    /**
     * The {@code PicOrderCntMsb} of the current picture.
     */
    private int currentPocMsb;

    /**
     * The {@code FrameNumOffset} of the current picture.
     */
    private int currentFrameNumOffset;

    /**
     * The {@code MaxLongTermFrameIdx}, {@code -1} for "no long-term frame indices".
     */
    private int maxLongTermFrameIdx = -1;

    /**
     * The identifiers of the last finished picture, for detecting its trailing slices.
     */
    private final H264SliceHeader lastPicture = new H264SliceHeader();

    /**
     * Whether {@link #lastPicture} is valid.
     */
    private boolean lastPictureValid;

    /**
     * The reference picture lists of the current slice: the frames holding the references.
     */
    private final DecoderFrame[][] lists = new DecoderFrame[2][33];

    /**
     * The structure of each entry of {@link #lists}: {@link DecoderFrame#FRAME} for frames and
     * complementary field pairs, {@link DecoderFrame#TOP} or {@link DecoderFrame#BOTTOM} for
     * fields.
     */
    private final byte[][] listStructures = new byte[2][33];

    /**
     * The initial list construction scratch.
     */
    private final List<DecoderFrame> scratchList = new ArrayList<>();

    /**
     * The fields of an initial field reference list: the frames, with their parities in
     * {@link #fieldListParity}.
     */
    private final List<DecoderFrame> fieldListFrames = new ArrayList<>();

    /**
     * The parities of the entries of {@link #fieldListFrames}: 0 top, 1 bottom.
     */
    private final List<Integer> fieldListParity = new ArrayList<>();

    /**
     * The fields of the initial list 1 of a B field slice, with their parities in
     * {@link #fieldListParity1}.
     */
    private final List<DecoderFrame> fieldListFrames1 = new ArrayList<>();

    /**
     * The parities of the entries of {@link #fieldListFrames1}: 0 top, 1 bottom.
     */
    private final List<Integer> fieldListParity1 = new ArrayList<>();

    /**
     * The frames holding a short-term reference field, scratch of the field list construction.
     */
    private final List<DecoderFrame> fieldShortTerm = new ArrayList<>();

    /**
     * The frames holding a long-term reference field, scratch of the field list construction.
     */
    private final List<DecoderFrame> fieldLongTerm = new ArrayList<>();

    /**
     * The short-term entries preceding the current field in output order, scratch of the field
     * list construction.
     */
    private final List<DecoderFrame> fieldBefore = new ArrayList<>();

    /**
     * The short-term entries following the current field in output order, scratch of the field
     * list construction.
     */
    private final List<DecoderFrame> fieldAfter = new ArrayList<>();

    /**
     * The ordered short-term entries of one list, scratch of the field list construction.
     */
    private final List<DecoderFrame> fieldOrdered = new ArrayList<>();

    /**
     * The presentation timestamp of the packet being decoded, consumed by the first picture that
     * starts in it.
     */
    private long pendingPts = Packet.NO_TIMESTAMP;

    /**
     * The duration of the packet being decoded.
     */
    private long pendingDuration;

    /**
     * The number of packets begun so far, which tells whether the two fields of a frame arrived
     * in different packets.
     */
    private long packetSerial;

    /**
     * The first failure of the current packet, rethrown once the packet is processed.
     */
    private MediaException packetError;

    /**
     * A gray frame used in place of missing references.
     */
    private DecoderFrame grayFrame;

    /**
     * Whether every packet is one access unit whose picture is finished and output when the
     * packet ends.
     */
    private boolean lowDelay;

    /**
     * Whether the packet being decoded lost data on its way, so every picture it carries is
     * damaged.
     */
    private boolean packetIncomplete;

    /**
     * The number of pictures started, which numbers them in decoding order.
     */
    private long decodeCount;

    /**
     * Whether decoding of the current coded video sequence started at a picture other than an
     * IDR picture, so a reference missing from a list is a picture decoding never saw.
     */
    private boolean joinedMidSequence;

    /**
     * The latest damage event in decoding order: twice the decoding index of a damaged picture
     * output, or twice the number of pictures started plus one for a failure outside a picture.
     */
    private long damageKey;

    /**
     * Twice the decoding index of the latest intact picture output.
     */
    private long intactKey;

    /**
     * Constructs an engine that decodes several pictures at once on the module's shared pool.
     *
     * <p>With more than one thread, consecutive pictures decode concurrently and a picture waits
     * only for the rows of its reference pictures that its motion vectors reach. Output is
     * identical to single-threaded decoding. Failures of a picture decoded concurrently are
     * reported by the {@link #endPacket()} or {@link #flush()} call during which the picture is
     * output.
     *
     * @implNote This implementation starts no threads of its own: each picture that has received
     *           all its slices becomes a computation of {@link ParallelBands}, which the shared
     *           helpers claim in decoding order, and which the calling thread decodes itself when
     *           it has to wait for it and no helper has claimed it. {@code threads} bounds the
     *           pictures of this engine in flight; when the module runs every task on the calling
     *           thread, pictures are decoded on the calling thread as with a single thread.
     * @param limits the resource limits
     * @param sink receives decoded frames in output order
     * @param threads the most pictures decoded concurrently; 1 decodes on the calling thread
     */
    public H264DecoderEngine(MediaLimits limits, FrameSink sink, int threads) {
        this.limits = limits;
        this.sink = sink;
        this.parallel = threads > 1 && ParallelBands.enabled();
        this.threads = parallel ? threads : 1;
        this.queueDepth = parallel ? 2 * threads : 1;
        this.inlineTask = new SliceTask(null, lists, listStructures);
    }

    /**
     * Switches low-delay mode on or off: when on, every packet is taken as one complete access
     * unit, whose picture is finished when the packet ends, and pictures of Baseline profile
     * sequences are output as soon as they are decoded.
     *
     * @param lowDelay whether low-delay mode is on
     */
    public void setLowDelay(boolean lowDelay) {
        this.lowDelay = lowDelay;
    }

    /**
     * Returns whether the decoder needs a key frame to output intact pictures again: since the
     * latest damaged picture, dropped slice or stream failure, no later picture in decoding
     * order was output intact.
     *
     * @return whether a key frame is needed
     */
    public boolean keyframeNeeded() {
        return damageKey > intactKey;
    }

    /**
     * Records a failure that damages the stream outside any output picture, such as a slice that
     * had to be dropped, so a key frame is needed until a picture started after it is output
     * intact.
     */
    public void noteDamage() {
        damageKey = Math.max(damageKey, 2 * decodeCount + 1);
    }

    /**
     * Records that data of the stream was lost before reaching the engine: a key frame is needed
     * (see {@link #noteDamage()}), and the long-term references marked so far become stale, since
     * the lost data may have changed which pictures their indices name.
     */
    public void noteLoss() {
        noteDamage();
        markLongTermStale();
        lossEvents++;
    }

    /**
     * Marks the long-term references of the decoded picture buffer as
     * {@linkplain DecoderFrame#staleReference stale}.
     */
    private void markLongTermStale() {
        for (var frame : dpb) {
            if (frame.anyLongTerm()) {
                frame.staleReference = true;
            }
        }
    }

    /**
     * Waits for the pictures in progress, dropping the started pictures no thread has claimed
     * when no claimed picture can read them; afterwards no thread runs work of this engine.
     */
    public void close() {
        pendingPartitions.clear();
        if (!parallel) {
            return;
        }
        if (currentJob != null) {
            inFlight.pollLast();
            currentJob = null;
        }
        while (!inFlight.isEmpty() && inFlight.peekLast().cancel()) {
            inFlight.pollLast();
        }
        while (!inFlight.isEmpty()) {
            inFlight.pollFirst().join();
        }
    }

    /**
     * Declares the timing of the packet whose NAL units follow.
     *
     * @param pts the packet presentation timestamp
     * @param duration the packet duration
     */
    public void beginPacket(long pts, long duration) {
        beginPacket(pts, duration, false);
    }

    /**
     * Declares the timing and integrity of the packet whose NAL units follow.
     *
     * <p>A packet that lost data damages every picture that receives a slice from it; a picture
     * that lost whole slices with it is damaged anyway, because its missing macroblocks are
     * concealed. The long-term references marked before the packet become
     * {@linkplain DecoderFrame#staleReference stale}, since the lost data may have changed their
     * marking.
     *
     * @param pts the packet presentation timestamp
     * @param duration the packet duration
     * @param incomplete whether the packet lost data on its way
     */
    public void beginPacket(long pts, long duration, boolean incomplete) {
        this.pendingPts = pts;
        this.pendingDuration = duration;
        this.packetSerial++;
        this.packetError = null;
        this.packetIncomplete = incomplete;
        if (incomplete) {
            markLongTermStale();
            lossEvents++;
        }
    }

    /**
     * Completes a packet: finishes the current picture when all its macroblocks are decoded, or
     * in low-delay mode in any case, and rethrows the first failure of the packet.
     *
     * @throws MediaException the first failure raised while processing the packet
     */
    public void endPacket() {
        try {
            if (lowDelay) {
                finishPicture();
            }
            if (parallel) {
                drainOutput(false);
            }
        } finally {
            packetIncomplete = false;
            pendingPts = Packet.NO_TIMESTAMP;
        }
        var error = packetError;
        packetError = null;
        if (error != null) {
            throw error;
        }
    }

    /**
     * Abandons the packet being decoded after an unexpected failure: the current picture is
     * marked damaged, and in low-delay mode finished, failures of the packet are forgotten and a
     * key frame becomes needed.
     */
    public void abandonPacket() {
        noteDamage();
        packetError = null;
        packetIncomplete = false;
        pendingPts = Packet.NO_TIMESTAMP;
        if (current != null) {
            current.damaged = true;
            if (lowDelay) {
                try {
                    finishPicture();
                } catch (RuntimeException ignored) {
                    current = null;
                }
                packetError = null;
            }
        }
    }

    /**
     * Records a failure of the current packet and keeps decoding.
     *
     * @param error the failure
     */
    private void recordError(MediaException error) {
        if (packetError == null) {
            packetError = error;
        }
    }

    /**
     * Processes one NAL unit.
     *
     * @param data the buffer holding the escaped NAL unit, starting with its header byte
     * @param offset the index of the header byte
     * @param length the NAL unit length in bytes
     * @throws MediaException.Unsupported if the NAL unit uses an unsupported coding tool
     */
    public void decodeNal(byte[] data, int offset, int length) {
        if (length <= 0) {
            return;
        }
        var headerByte = data[offset] & 0xFF;
        if ((headerByte & 0x80) != 0) {
            noteDamage();
            recordError(new MediaException.Malformed("forbidden_zero_bit is set"));
            return;
        }
        var nalRefIdc = headerByte >> 5;
        var type = headerByte & 0x1F;
        switch (type) {
            case H264Nal.SLICE, H264Nal.IDR_SLICE, H264Nal.SPS, H264Nal.PPS, H264Nal.SEI, H264Nal.SLICE_PARTITION_A,
                 H264Nal.SLICE_PARTITION_B, H264Nal.SLICE_PARTITION_C -> {
                var needed = length + H264BitReader.PADDING;
                LimitChecks.checkAllocation(limits, needed);
                if (rbsp.length < needed) {
                    rbsp = new byte[Math.max(needed, rbsp.length * 2)];
                }
                var size = NalUnits.unescape(data, offset + 1, length - 1, rbsp);
                Arrays.fill(rbsp, size, size + H264BitReader.PADDING, (byte) 0);
                try {
                    switch (type) {
                        case H264Nal.SPS -> handleSps(size);
                        case H264Nal.PPS -> handlePps(size);
                        case H264Nal.SEI -> handleSei(size);
                        case H264Nal.SLICE_PARTITION_B, H264Nal.SLICE_PARTITION_C -> handlePartition(size, type);
                        default -> handleSlice(size, type, nalRefIdc);
                    }
                } catch (MediaException.Malformed error) {
                    noteDamage();
                    recordError(error);
                }
            }
            case H264Nal.AUD -> finishPicture();
            case H264Nal.END_OF_SEQUENCE, H264Nal.END_OF_STREAM -> {
                finishPicture();
                lastPictureValid = false;
            }
            default -> {
            }
        }
    }

    /**
     * Stores a sequence parameter set.
     *
     * @param size the payload size in {@link #rbsp}
     */
    private void handleSps(int size) {
        var sps = H264Sps.parse(rbsp, 0, size);
        var previous = spsTable[sps.id];
        if (previous != null && Arrays.equals(previous.raw, sps.raw)) {
            return;
        }
        spsTable[sps.id] = sps;
        for (var i = 0; i < ppsTable.length; i++) {
            if (ppsTable[i] != null && ppsTable[i].spsId == sps.id) {
                ppsTable[i] = null;
            }
        }
    }

    /**
     * Stores a picture parameter set payload; it is parsed on activation, against the sequence
     * parameter set current at that time.
     *
     * @param size the payload size in {@link #rbsp}
     */
    private void handlePps(int size) {
        var ids = H264Pps.peekIds(rbsp, 0, size);
        var id = ids >> 8;
        var raw = Arrays.copyOf(rbsp, size);
        if (ppsRaw[id] != null && Arrays.equals(ppsRaw[id], raw)) {
            return;
        }
        ppsRaw[id] = raw;
        ppsTable[id] = null;
    }

    /**
     * Returns the parsed picture parameter set with an id, parsing it on first use.
     *
     * @param id the {@code pic_parameter_set_id}
     * @return the set
     * @throws MediaException.Malformed if the set or its sequence parameter set is missing
     */
    private H264Pps activatePps(int id) {
        var raw = ppsRaw[id];
        if (raw == null) {
            throw new MediaException.Malformed("missing picture parameter set " + id);
        }
        var parsed = ppsTable[id];
        var spsId = parsed != null ? parsed.spsId : (H264Pps.peekIds(padded(raw), 0, raw.length) & 0xFF);
        var sps = spsTable[spsId];
        if (sps == null) {
            throw new MediaException.Malformed("missing sequence parameter set " + spsId);
        }
        if (parsed == null || parsed.sps != sps) {
            parsed = H264Pps.parse(padded(raw), 0, raw.length, sps);
            ppsTable[id] = parsed;
        }
        return parsed;
    }

    /**
     * Returns a copy of a payload followed by the zero padding the bit reader needs.
     *
     * @param raw the payload
     * @return the padded copy
     */
    private static byte[] padded(byte[] raw) {
        return Arrays.copyOf(raw, raw.length + H264BitReader.PADDING);
    }

    /**
     * Returns the color description of the pictures of a sequence, with the transfer function an
     * alternative transfer characteristics SEI message prefers and the high-dynamic-range metadata
     * of the last SEI messages that carried it.
     *
     * @param sps the active sequence parameter set
     * @return the color description
     */
    private ColorInfo colorInfo(H264Sps sps) {
        var info = sps.colorInfo();
        if (preferredTransfer >= 0) {
            var preferred = TransferCharacteristics.of(preferredTransfer);
            if (preferred != TransferCharacteristics.UNSPECIFIED) {
                info = info.withTransfer(preferred);
            }
        }
        if (masteringDisplay != null) {
            info = info.withMasteringDisplay(masteringDisplay);
        }
        if (contentLightLevel != null) {
            info = info.withContentLightLevel(contentLightLevel);
        }
        return info;
    }

    /**
     * Scans an SEI message list for the messages the decoder uses: the picture timing (ITU-T H.264
     * D.1.3), the recovery point (D.1.8), the film grain characteristics (D.1.21), the frame
     * packing arrangement (D.1.26), the display orientation (D.1.27), the mastering display color
     * volume (D.1.29), the content light level (D.1.31) and the alternative transfer
     * characteristics (D.1.32).
     *
     * <p>The messages apply to the next picture. The high-dynamic-range metadata persists until an
     * SEI message replaces it; a message too short for its syntax, or malformed, is ignored.
     *
     * @param size the payload size in {@link #rbsp}
     */
    private void handleSei(int size) {
        finishPicture();
        var position = 0;
        while (position < size - 1) {
            var payloadType = 0;
            while (position < size && (rbsp[position] & 0xFF) == 0xFF) {
                payloadType += 255;
                position++;
            }
            if (position >= size) {
                return;
            }
            payloadType += rbsp[position++] & 0xFF;
            var payloadSize = 0;
            while (position < size && (rbsp[position] & 0xFF) == 0xFF) {
                payloadSize += 255;
                position++;
            }
            if (position >= size) {
                return;
            }
            payloadSize += rbsp[position++] & 0xFF;
            // a message cut short by the end of the NAL unit is read as far as it goes
            payloadSize = Math.min(payloadSize, size - position);
            switch (payloadType) {
                case 1 -> {
                    if (pendingTiming.length < payloadSize + H264BitReader.PADDING) {
                        pendingTiming = new byte[payloadSize + H264BitReader.PADDING];
                    }
                    System.arraycopy(rbsp, position, pendingTiming, 0, payloadSize);
                    Arrays.fill(pendingTiming, payloadSize, payloadSize + H264BitReader.PADDING, (byte) 0);
                    pendingTimingSize = payloadSize;
                }
                case 6 -> recoveryPointPending = true;
                case 19 -> {
                    seiReader.init(rbsp, position, payloadSize);
                    var message = FilmGrainParameters.parse(seiReader, false);
                    if (message != null) {
                        pendingFilmGrain = message;
                    }
                }
                case 45 -> {
                    seiReader.init(rbsp, position, payloadSize);
                    var message = DisplaySei.framePacking(seiReader, false);
                    if (message != null) {
                        pendingFramePacking = message;
                    }
                }
                case 47 -> {
                    seiReader.init(rbsp, position, payloadSize);
                    var message = DisplaySei.displayOrientation(seiReader, false);
                    if (message != null) {
                        pendingOrientation = message;
                    }
                }
                case 137 -> {
                    if (payloadSize >= 24) {
                        masteringDisplay = parseMasteringDisplay(position);
                    }
                }
                case 144 -> {
                    if (payloadSize >= 4) {
                        contentLightLevel = new ContentLightLevel(u16(position), u16(position + 2));
                    }
                }
                case 147 -> {
                    if (payloadSize >= 1) {
                        preferredTransfer = rbsp[position] & 0xFF;
                        pendingTransfer = true;
                    }
                }
                default -> {
                }
            }
            position += payloadSize;
        }
    }

    /**
     * Returns the {@code pic_struct} of the picture timing message of the next picture's access
     * unit (ITU-T H.264 D.1.3), which follows the HRD delays when the sequence carries HRD
     * parameters, and forgets the message.
     *
     * @param sps the active sequence parameter set
     * @return the {@code pic_struct}, from 0 to 8, or -1 when the access unit carried no picture
     * timing message, the sequence signals no {@code pic_struct}, or the value is reserved
     */
    private int takePicStruct(H264Sps sps) {
        var size = pendingTimingSize;
        pendingTimingSize = -1;
        if (size < 0 || !sps.picStructPresent) {
            return -1;
        }
        seiReader.init(pendingTiming, 0, size);
        if (sps.cpbDpbDelaysPresent) {
            seiReader.readBits(sps.cpbRemovalDelayLength);
            seiReader.readBits(sps.dpbOutputDelayLength);
        }
        var picStruct = seiReader.readBits(4);
        return seiReader.overrun() || picStruct > 8 ? -1 : picStruct;
    }

    /**
     * Parses the payload of a mastering display color volume SEI message, whose primaries are
     * stored in the order green, blue, red, in units of 0.00002, and whose luminances are in units
     * of 0.0001 candelas per square meter.
     *
     * @param position the index of the payload in {@link #rbsp}
     * @return the mastering display
     */
    private MasteringDisplay parseMasteringDisplay(int position) {
        var green = new Chromaticity(u16(position) * 0.00002, u16(position + 2) * 0.00002);
        var blue = new Chromaticity(u16(position + 4) * 0.00002, u16(position + 6) * 0.00002);
        var red = new Chromaticity(u16(position + 8) * 0.00002, u16(position + 10) * 0.00002);
        var white = new Chromaticity(u16(position + 12) * 0.00002, u16(position + 14) * 0.00002);
        var max = u32(position + 16) * 0.0001;
        var min = u32(position + 20) * 0.0001;
        return new MasteringDisplay(red, green, blue, white, max, min);
    }

    /**
     * Reads a big-endian unsigned 16-bit value of {@link #rbsp}.
     *
     * @param position the index of the first byte
     * @return the value
     */
    private int u16(int position) {
        return (rbsp[position] & 0xFF) << 8 | rbsp[position + 1] & 0xFF;
    }

    /**
     * Reads a big-endian unsigned 32-bit value of {@link #rbsp}.
     *
     * @param position the index of the first byte
     * @return the value
     */
    private long u32(int position) {
        return (long) u16(position) << 16 | u16(position + 2);
    }

    /**
     * Processes one slice NAL unit.
     *
     * @param size the payload size in {@link #rbsp}
     * @param nalType the {@code nal_unit_type}
     * @param nalRefIdc the {@code nal_ref_idc}
     */
    private void handleSlice(int size, int nalType, int nalRefIdc) {
        var reader = new H264BitReader();
        reader.init(rbsp, 0, size);
        var slice = header;
        slice.parseStart(reader);
        var pps = activatePps(slice.ppsId);
        var sps = pps.sps;
        lastSlicePps = pps;
        if (nalType == H264Nal.SLICE_PARTITION_A && pps.cabac) {
            throw new MediaException.Unsupported("h264 cabac data partitioning",
                    "slice data partitioning with CABAC entropy coding belongs to no profile");
        }
        slice.parseRest(reader, pps, nalType, nalRefIdc);
        checkSwitching(sps, pps, slice);

        if (slice.redundantPicCnt > 0) {
            return;
        }
        if (current != null) {
            if (isNewPicture(slice)) {
                finishPicture();
            } else if (parallel ? repeatsSliceStart(slice)
                    : repeatsDecodedMacroblock(slice) || repeatsPendingStart(slice)) {
                if (slice.firstMbInSlice != 0) {
                    dropped(slice);
                    return;
                }
                finishPicture();
            }
        }
        if (current == null) {
            if (lastPictureValid && !isNewPicture(slice, lastPicture) && slice.firstMbInSlice != 0) {
                dropped(slice);
                return;
            }
            if (!started) {
                if (!slice.idr && !recoveryPointPending && !slice.isIntra()) {
                    noteDamage();
                    discardPictureMessages();
                    dropped(slice);
                    return;
                }
                started = true;
                startPending = !slice.idr;
                joinedMidSequence = !slice.idr;
            }
            startPicture(slice, sps);
            var swap = pictureHeader;
            pictureHeader = header;
            header = swap;
            slice = pictureHeader;
        }
        if (slice.sliceType != H264SliceHeader.I) {
            current.allIntra = false;
        }
        decodeSliceData(slice, size);
    }

    /**
     * Moves the display messages of the access unit of a picture that starts onto its frame: the
     * picture timing's {@code pic_struct} and the film grain characteristics, frame packing
     * arrangement and display orientation messages. The second field of a frame only adds the
     * messages its first field's access unit lacked.
     *
     * @param frame the frame of the picture
     * @param sps the active sequence parameter set
     * @param first whether the picture is a frame or the first field of a frame
     */
    private void takeDisplayMessages(DecoderFrame frame, H264Sps sps, boolean first) {
        var picStruct = takePicStruct(sps);
        if (first) {
            frame.picStruct = picStruct;
            frame.filmGrain = pendingFilmGrain;
            frame.framePacking = pendingFramePacking;
            frame.orientation = pendingOrientation;
        } else {
            if (frame.filmGrain == null) {
                frame.filmGrain = pendingFilmGrain;
            }
            if (frame.framePacking == null) {
                frame.framePacking = pendingFramePacking;
            }
            if (frame.orientation == null) {
                frame.orientation = pendingOrientation;
            }
        }
        discardPictureMessages();
    }

    /**
     * Forgets the display messages of the next picture's access unit, for a picture that is not
     * decoded.
     */
    private void discardPictureMessages() {
        pendingTimingSize = -1;
        pendingFilmGrain = null;
        pendingFramePacking = null;
        pendingOrientation = null;
    }

    /**
     * Remembers a slice dropped on purpose when it is a partition A, so that its partitions B and
     * C are dropped silently.
     *
     * @param slice the dropped slice
     */
    private void dropped(H264SliceHeader slice) {

    }

    /**
     * Adds a slice to {@link #droppedPartitions}.
     *
     * @param slice the header of the slice's partition A
     */
    private void remember(H264SliceHeader slice) {
        var key = partitionKey(slice.sliceId, slice.colorPlaneId);
        if (isDropped(key)) {
            return;
        }
        if (droppedPartitionCount == droppedPartitions.length) {
            droppedPartitions = Arrays.copyOf(droppedPartitions, droppedPartitionCount * 2);
        }
        droppedPartitions[droppedPartitionCount++] = key;
    }

    /**
     * Returns whether the partitions B and C of a slice are dropped silently.
     *
     * @param key the key of the slice ({@link #partitionKey(int, int)})
     * @return {@code true} when {@link #droppedPartitions} holds the key
     */
    private boolean isDropped(long key) {
        for (var i = 0; i < droppedPartitionCount; i++) {
            if (droppedPartitions[i] == key) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns the key that identifies a slice coded as partitions within a picture.
     *
     * @param sliceId the {@code slice_id}
     * @param colorPlane the {@code colour_plane_id}, 0 to 2
     * @return the key
     */
    private static long partitionKey(int sliceId, int colorPlane) {
        return (sliceId & 0xFFFFFFFFL) << 2 | colorPlane;
    }

    /**
     * Rejects SP and SI slices outside the only configuration ITU-T H.264 8.6 defines them for:
     * 8-bit 4:2:0 video with 4x4 transforms, the Extended profile's.
     *
     * @param sps the sequence parameter set
     * @param pps the picture parameter set
     * @param slice the slice header
     * @throws MediaException.Malformed if an SP or SI slice uses another configuration
     */
    private static void checkSwitching(H264Sps sps, H264Pps pps, H264SliceHeader slice) {
        if (slice.sliceType != H264SliceHeader.SP && slice.sliceType != H264SliceHeader.SI) {
            return;
        }
        if (sps.chromaArrayType() != 1 || sps.bitDepthLuma != 8 || sps.bitDepthChroma != 8 || sps.transformBypass
                || pps.transform8x8Mode) {
            throw new MediaException.Malformed("SP and SI slices are defined for 8-bit 4:2:0 video with 4x4 "
                    + "transforms only");
        }
    }

    /**
     * Attaches a slice data partition B or C to the slice of its partition A (ITU-T H.264 7.3.2.9
     * and 8.2.3), and decodes that slice when its last partition, C, arrives on a single thread.
     *
     * <p>A partition of a redundant coded picture is dropped with the picture, and the partitions
     * of a partition A dropped on purpose are dropped with it. A partition whose partition A is
     * missing damages the current picture, as a lost slice would; a repeated partition, of a
     * slice still waiting or already complete, is ignored.
     *
     * @param size the payload size in {@link #rbsp}
     * @param nalType {@link H264Nal#SLICE_PARTITION_B} or {@link H264Nal#SLICE_PARTITION_C}
     * @throws MediaException.Malformed if the partition header is corrupt
     */
    private void handlePartition(int size, int nalType) {
        var reader = new H264BitReader();
        reader.init(rbsp, 0, size);
        var sliceId = reader.readUe();
        H264Pps pps = null;
        for (var task : pendingPartitions) {
            if (task.header.sliceId == sliceId) {
                pps = task.header.pps;
                break;
            }
        }
        if (pps == null) {
            pps = current != null ? pictureHeader.pps : null;
        }
        var colorPlane = 0;
        var redundant = 0;
        if (pps != null) {
            if (pps.sps.separateColorPlane) {
                colorPlane = reader.readBits(2);
            }
            if (pps.redundantPicCntPresent) {
                redundant = reader.readUe();
            }
        }
        if (reader.overrun()) {
            throw new MediaException.Malformed("truncated slice data partition header");
        }
        if (redundant > 0) {
            return;
        }
        SliceTask match = null;
        for (var task : pendingPartitions) {
            if (task.header.sliceId == sliceId && task.header.colorPlaneId == colorPlane) {
                match = task;
                break;
            }
        }
        if (match == null) {
            if (!isDropped(partitionKey(sliceId, colorPlane))) {
                noteDamage();
                if (current != null) {
                    current.damaged = true;
                }
            }
            return;
        }
        var category = nalType == H264Nal.SLICE_PARTITION_B ? 3 : 4;
        if ((category == 3 ? match.partitionB : match.partitionC) != null) {
            return;
        }
        if (packetIncomplete && current != null) {
            current.damaged = true;
        }
        match.attachPartition(category, Arrays.copyOf(rbsp, size + H264BitReader.PADDING), size,
                reader.bitPosition());
        if (category == 4) {
            pendingPartitions.remove(match);
            remember(match.header);
            if (!parallel) {
                decodeInline(match);
                if (current != null && inlineDecoder.complete()) {
                    finishPicture();
                }
            }
        }
    }

    /**
     * Decodes a slice of the current picture on the calling thread, recording its failure.
     *
     * @param task the slice
     */
    private void decodeInline(SliceTask task) {
        inlineDecoder.decodeSlice(task);
        var failure = inlineDecoder.takeError();
        if (failure != null) {
            recordError(failure);
        }
    }

    /**
     * Returns whether a slice starts a new picture relative to the current picture's first
     * slice.
     *
     * @param slice the slice header
     * @return {@code true} when the slice belongs to a new primary coded picture
     */
    private boolean isNewPicture(H264SliceHeader slice) {
        return isNewPicture(slice, pictureHeader);
    }

    /**
     * Applies the first-VCL-NAL-unit detection of ITU-T H.264 7.4.1.2.4; a slice that refers to
     * another sequence parameter set than the previous one also starts a new picture, since a
     * sequence parameter set NAL unit can only start an access unit.
     *
     * @param slice the new slice header
     * @param previous the header of a slice of the previous picture
     * @return {@code true} when the slice belongs to a different picture
     */
    private static boolean isNewPicture(H264SliceHeader slice, H264SliceHeader previous) {
        if (slice.sps != previous.sps || slice.frameNum != previous.frameNum || slice.ppsId != previous.ppsId
                || slice.fieldPic != previous.fieldPic || slice.bottomField != previous.bottomField
                || (slice.nalRefIdc == 0) != (previous.nalRefIdc == 0) || slice.idr != previous.idr) {
            return true;
        }
        if (slice.sps.pocType == 0 && (slice.pocLsb != previous.pocLsb || slice.deltaPocBottom != previous.deltaPocBottom)) {
            return true;
        }
        if (slice.sps.pocType == 1 && (slice.deltaPoc0 != previous.deltaPoc0 || slice.deltaPoc1 != previous.deltaPoc1)) {
            return true;
        }
        return slice.idr && slice.idrPicId != previous.idrPicId;
    }

    /**
     * Begins decoding a new picture: activates the sequence, decides whether a field completes
     * the frame of the previous field, handles {@code frame_num} gaps, derives the picture order
     * count and allocates the frame.
     *
     * @param slice the first slice header of the picture
     * @param sps the sequence parameter set
     */
    private void startPicture(H264SliceHeader slice, H264Sps sps) {
        droppedPartitionCount = 0;
        activateSps(sps, slice.idr);
        var structure = slice.fieldPic ? (slice.bottomField ? DecoderFrame.BOTTOM : DecoderFrame.TOP)
                : DecoderFrame.FRAME;
        var pair = pendingPair;
        pendingPair = null;
        var reference = slice.nalRefIdc != 0;
        if (structure == DecoderFrame.FRAME || slice.idr) {
            pairingSuspect = false;
        } else {
            var chained = lastPictureField && lastPictureFrameNum == slice.frameNum
                    && lastPictureReference == reference;
            pairingSuspect = (chained && pairingSuspect) || lossEvents != lossesAtLastPicture;
        }
        lastPictureField = structure != DecoderFrame.FRAME;
        lastPictureFrameNum = slice.frameNum;
        lastPictureReference = reference;
        var second = pair != null && structure != DecoderFrame.FRAME && pair.fields != DecoderFrame.FRAME
                && (pair.fields & structure) == 0 && pair.frameNum == slice.frameNum && !slice.idr
                && pendingPairReference == reference && !hasMmco5(slice) && dpb.contains(pair);
        if (!second && pair != null) {
            closeUnpaired(pair);
            removeUnused();
        }
        currentStructure = structure;
        currentMbaff = structure == DecoderFrame.FRAME && sps.mbAdaptiveFrameField;
        secondField = second;
        DecoderFrame frame;
        if (second) {
            frame = pair;
            frame.fields = DecoderFrame.FRAME;
            if (packetIncomplete || pairingSuspect) {
                frame.damaged = true;
            }
            computePoc(frame, slice, sps, structure);
            frame.updatePoc();
            currentPoc = frame.fieldPoc(structure == DecoderFrame.BOTTOM ? 1 : 0);
            recoveryPointPending = false;
            pendingTransfer = false;
            takeDisplayMessages(frame, sps, false);
            if (frame.packet != packetSerial && frame.duration > 0 && pendingDuration > 0) {
                frame.duration += pendingDuration;
            }
            pendingPts = Packet.NO_TIMESTAMP;
        } else {
            if (slice.idr) {
                prevRefFrameNumValid = true;
                prevRefFrameNum = 0;
            } else if (prevRefFrameNumValid) {
                handleFrameNumGap(slice, sps);
            }
            frame = obtainFrame();
            frame.id = nextId++;
            frame.frameNum = slice.frameNum;
            frame.idr = slice.idr;
            frame.refTop = 0;
            frame.refBottom = 0;
            frame.fields = structure;
            frame.codingStruct = structure != DecoderFrame.FRAME ? DecoderFrame.CODED_FIELDS
                    : currentMbaff ? DecoderFrame.CODED_MBAFF : DecoderFrame.CODED_FRAME;
            frame.outputNeeded = true;
            frame.nonExisting = false;
            frame.mmco5 = false;
            frame.discard = false;
            frame.damaged = packetIncomplete || (structure != DecoderFrame.FRAME && pairingSuspect);
            frame.staleReference = false;
            frame.lossMark = lossesAtLastPicture;
            frame.missingFieldFilled = false;
            frame.decodeIndex = ++decodeCount;
            frame.sps = sps;
            if (slice.idr && !pendingTransfer) {
                // an alternative transfer characteristics message lasts until the end of its sequence
                preferredTransfer = -1;
            }
            pendingTransfer = false;
            frame.color = colorInfo(sps);
            frame.keyframe = slice.idr || (recoveryPointPending && slice.sliceType == H264SliceHeader.I);
            recoveryPointPending = false;
            frame.allIntra = true;
            grainPocOffsetAtFrameStart = grainPocOffset;
            takeDisplayMessages(frame, sps, true);
            frame.pts = pendingPts;
            frame.duration = pendingDuration;
            frame.packet = packetSerial;
            pendingPts = Packet.NO_TIMESTAMP;
            if (slice.idr) {
                sequence++;
                joinedMidSequence = false;
            }
            computePoc(frame, slice, sps, structure);
            if (structure == DecoderFrame.TOP) {
                frame.bottomPoc = frame.topPoc;
            } else if (structure == DecoderFrame.BOTTOM) {
                frame.topPoc = frame.bottomPoc;
            }
            frame.updatePoc();
            currentPoc = frame.poc;
            frame.sequence = sequence;
            if (slice.idr) {
                startSequence = -1;
                startPoc = Integer.MIN_VALUE;
                startPending = false;
            } else if (startPending) {
                startPoc = frame.poc;
                startSequence = sequence;
                startPending = false;
            }
            if (startSequence == sequence && frame.poc < startPoc) {
                frame.discard = true;
            }
        }
        current = frame;
        lossesAtLastPicture = lossEvents;
        var concealSliceNum = nextSliceNum++;
        firstSliceNum = concealSliceNum;
        DecoderFrame concealSource = null;
        for (var other : dpb) {
            if (other != frame && !other.nonExisting && (concealSource == null || other.id > concealSource.id)) {
                concealSource = other;
            }
        }
        var dsp = H264Dsp.INSTANCE;
        if (!parallel) {
            frame.beginDecoding(structure, !second);
            inlineDecoder.start(frame, structure, currentMbaff, firstSliceNum, concealSliceNum, concealSource, dsp);
        } else {
            while (inFlight.size() >= queueDepth) {
                joinOldest();
                drainOutput(false);
            }
            frame.beginDecoding(structure, !second);
            var macroblocks = activeSps.widthInMbs * activeSps.frameHeightInMbs;
            if (sliceStarts.length != macroblocks) {
                sliceStarts = new byte[macroblocks];
            } else {
                Arrays.fill(sliceStarts, (byte) 0);
            }
            var job = new PictureJob(frame, structure, currentMbaff, ++submittedJobs, firstSliceNum, concealSliceNum,
                    concealSource, dsp, idleDecoders);
            job.previous = second ? frame.job : null;
            currentJob = job;
            inFlight.addLast(job);
            frame.job = job;
        }
    }

    /**
     * Settles a frame whose field will not receive another: the missing field is filled with
     * mid-gray samples before any later picture can read the frame, and the frame is damaged when
     * data of the stream was lost since the picture before its field started, since the lost data
     * may have held the other field.
     *
     * <p>A damaged reference field also marks its missing field as a reference, the partner of a
     * reference field in a complementary reference field pair being a reference field as well
     * (ITU-T H.264 3.30): later reference lists then hold the frame where the encoder's lists did,
     * instead of shifting other pictures into its place, and the partitions predicting from it
     * inherit its damage.
     *
     * @param frame the frame holding a single field
     */
    private void closeUnpaired(DecoderFrame frame) {
        frame.completeMissingField();
        if (lossEvents != frame.lossMark) {
            frame.damaged = true;
            var marking = frame.marking(frame.fields == DecoderFrame.TOP ? 0 : 1);
            if (marking != 0) {
                frame.mark(DecoderFrame.FRAME, marking);
            }
        }
    }

    /**
     * Returns whether a slice header carries memory management control operation 5.
     *
     * @param slice the slice header
     * @return {@code true} when one of its operations is 5
     */
    private static boolean hasMmco5(H264SliceHeader slice) {
        if (slice.nalRefIdc == 0 || !slice.adaptiveRefPicMarking) {
            return false;
        }
        for (var i = 0; i < slice.mmcoCount; i++) {
            if (slice.mmcoOp[i] == 5) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns whether the first macroblock of a slice of the current picture was already decoded
     * by an earlier slice, which makes the slice a repeat or the start of a new picture.
     *
     * @param slice the slice header, of the current picture
     * @return {@code true} when its first macroblock already belongs to a slice of the picture
     */
    private boolean repeatsDecodedMacroblock(H264SliceHeader slice) {
        var width = activeSps.widthInMbs;
        var address = (long) slice.firstMbInSlice * (currentMbaff ? 2 : 1);
        var slices = inlineDecoder.state(slice.colorPlaneId).sliceNum;
        if (address >= slices.length) {
            return false;
        }
        var index = PictureState.mbIndex((int) address, currentStructure, currentMbaff, width);
        return index < slices.length && slices[index] >= firstSliceNum;
    }
    /**
     * Returns whether a slice of the current picture starts at the first macroblock of a slice
     * coded as data partitions that waits for its partitions, which makes it a repeat or the
     * start of a new picture.
     *
     * @param slice the slice header, of the current picture
     * @return {@code true} when a waiting slice of the same color plane starts at the same
     * macroblock
     */
    private boolean repeatsPendingStart(H264SliceHeader slice) {
        for (var task : pendingPartitions) {
            if (task.header.firstMbInSlice == slice.firstMbInSlice && task.header.colorPlaneId == slice.colorPlaneId) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns the index of the first macroblock of a slice of the current picture.
     *
     * @param slice the slice header, of the current picture
     * @return the index in the per-macroblock arrays, or -1 when the slice starts past the picture
     */
    private int sliceStartIndex(H264SliceHeader slice) {
        var width = activeSps.widthInMbs;
        var address = (long) slice.firstMbInSlice * (currentMbaff ? 2 : 1);
        var count = (long) width * activeSps.frameHeightInMbs;
        if (address >= count) {
            return -1;
        }
        var index = PictureState.mbIndex((int) address, currentStructure, currentMbaff, width);
        return index < count ? index : -1;
    }

    /**
     * Returns whether a slice of the current picture starts at the first macroblock of a slice
     * of the same color plane already given to the picture's job, which makes it a repeat or the
     * start of a new picture.
     *
     * @param slice the slice header, of the current picture
     * @return {@code true} when an earlier slice of the picture and color plane started at the
     * same macroblock
     */
    private boolean repeatsSliceStart(H264SliceHeader slice) {
        var index = sliceStartIndex(slice);
        return index >= 0 && index < sliceStarts.length && (sliceStarts[index] & (1 << slice.colorPlaneId)) != 0;
    }

    /**
     * Waits for the oldest job, decoding its picture on the calling thread when no other thread
     * has claimed it, and retires the finished jobs.
     *
     * <p>Jobs are started in decoding order and waited for oldest first, so a job run here only
     * waits for older jobs that have finished or are running on other threads.
     */
    private void joinOldest() {
        inFlight.peekFirst().join();
        retireFinishedJobs();
    }

    /**
     * Waits until a frame is decoded, joining the jobs in flight oldest first until it is.
     *
     * <p>Every frame still being decoded belongs to a started job in flight, so joining in
     * decoding order reaches it without waiting for a job out of order.
     *
     * @param frame the picture whose decoding to wait for
     */
    private void awaitFrame(DecoderFrame frame) {
        while (!frame.isDone() && !inFlight.isEmpty()) {
            joinOldest();
        }
        frame.awaitDone();
    }

    /**
     * Starts the job of the picture that has received all its slices, then decodes on the
     * calling thread the oldest started pictures that no helper is free to claim.
     *
     * @implNote This implementation asks {@link ParallelBands#idle()}, not
     *           {@link ParallelBands#available()}: a picture has to be decoded anyway, so only a
     *           busy set of helpers, not the load of other operations, is a reason for the caller
     *           to stop parsing ahead and decode it itself.
     */
    private void startCurrentJob() {
        currentJob.start();
        currentJob = null;
        while (!inFlight.isEmpty() && ParallelBands.idle() == 0 && !inFlight.peekFirst().claimed()) {
            joinOldest();
        }
    }

    /**
     * Forgets the leading finished jobs and returns the frames that are no longer read by any
     * unfinished job to the pool.
     */
    private void retireFinishedJobs() {
        while (!inFlight.isEmpty() && inFlight.peekFirst().isDone()) {
            finishedJobs = inFlight.pollFirst().sequence;
        }
        for (var i = pendingRelease.size() - 1; i >= 0; i--) {
            var frame = pendingRelease.get(i);
            if (frame.releaseAfter <= finishedJobs && !frame.awaitingOutput) {
                pendingRelease.remove(i);
                pool(frame);
            }
        }
    }

    /**
     * Waits for every started job to finish, oldest first.
     */
    private void awaitAllJobs() {
        while (!inFlight.isEmpty()) {
            joinOldest();
        }
    }

    /**
     * Makes a sequence parameter set active, reallocating the buffers when the picture format
     * changes.
     *
     * @param sps the sequence parameter set of the new picture
     * @param idr whether the new picture is an IDR picture
     * @throws MediaException.LimitExceeded if the pictures exceed the limits
     */
    private void activateSps(H264Sps sps, boolean idr) {
        if (activeSps == sps) {
            return;
        }
        var formatChanged = activeSps == null || activeSps.widthInMbs != sps.widthInMbs
                || activeSps.frameHeightInMbs != sps.frameHeightInMbs
                || activeSps.chromaFormatIdc != sps.chromaFormatIdc
                || activeSps.separateColorPlane != sps.separateColorPlane
                || activeSps.bitDepthLuma != sps.bitDepthLuma
                || activeSps.bitDepthChroma != sps.bitDepthChroma
                || activeSps.dpbFrames() != sps.dpbFrames();
        if (!formatChanged) {
            activeSps = sps;
            return;
        }
        LimitChecks.checkPicture(limits, sps.codedWidth(), sps.codedHeight());
        var frames = sps.dpbFrames() + spareFrames();
        var frameBytes = DecoderFrame.allocationSize(sps.widthInMbs, sps.frameHeightInMbs, sps.chromaFormatIdc,
                sps.separateColorPlane, sps.bitDepthLuma > 8
                        || (sps.chromaArrayType() != 0 && sps.bitDepthChroma > 8));
        LimitChecks.checkAllocation(limits, frameBytes);
        LimitChecks.checkAllocation(limits, frameBytes * frames);
        if (activeSps != null) {
            if (pendingPair != null) {
                closeUnpaired(pendingPair);
                pendingPair = null;
            }
            awaitAllJobs();
            flushOutput(false);
            drainOutput(true);
            for (var frame : dpb) {
                frame.mark(DecoderFrame.FRAME, 0);
            }
            dpb.clear();
            pool.clear();
            pendingRelease.clear();
            grayFrame = null;
        }
        activeSps = sps;
        dpbSize = sps.dpbFrames();
        prevRefFrameNumValid = idr;
    }

    /**
     * Returns a frame buffer for a new picture, from the pool or newly allocated.
     *
     * @return a frame sized for {@link #activeSps}
     */
    private DecoderFrame obtainFrame() {
        if (parallel) {
            retireFinishedJobs();
        }
        if (!pool.isEmpty()) {
            return pool.removeLast();
        }
        return newFrame(activeSps);
    }

    /**
     * Allocates a frame for the pictures of a sequence: one presenting three separately decoded
     * monochrome color planes, at the luma bit depth, when the sequence codes its planes
     * separately.
     *
     * @param sps the sequence parameter set
     * @return the frame
     */
    private static DecoderFrame newFrame(H264Sps sps) {
        if (sps.separateColorPlane) {
            return DecoderFrame.separatePlanes(sps.widthInMbs, sps.frameHeightInMbs, sps.bitDepthLuma);
        }
        return new DecoderFrame(sps.widthInMbs, sps.frameHeightInMbs, sps.chromaFormatIdc, sps.bitDepthLuma,
                sps.bitDepthChroma);
    }

    /**
     * Returns a frame buffer to the pool.
     *
     * @param frame the frame, no longer referenced by the buffer
     */
    private void release(DecoderFrame frame) {
        if (parallel) {
            frame.releaseAfter = submittedJobs;
            pendingRelease.add(frame);
            return;
        }
        pool(frame);
    }

    /**
     * Returns the number of frame buffers needed beyond the decoded picture buffer: the picture
     * being decoded and one being output, plus, with several threads, the pictures in flight and
     * those waiting for output.
     *
     * @return the number of spare frames
     */
    private int spareFrames() {
        return parallel ? 2 + threads + queueDepth : 2;
    }

    /**
     * Adds a frame that no job reads any more to the pool, when it fits the active format.
     *
     * @param frame the picture whose buffers become reusable
     */
    private void pool(DecoderFrame frame) {
        var sps = activeSps;
        if (frame != grayFrame && frame.mbWidth == sps.widthInMbs
                && frame.mbHeight == sps.frameHeightInMbs && frame.chromaFormat == sps.chromaFormatIdc
                && (frame.planes != null) == sps.separateColorPlane && frame.bitDepthLuma == sps.bitDepthLuma
                && frame.bitDepthChroma == (sps.separateColorPlane ? sps.bitDepthLuma : sps.bitDepthChroma)
                && pool.size() < dpbSize + spareFrames()) {
            pool.add(frame);
        }
    }

    /**
     * Derives the picture order counts of a new picture (ITU-T H.264 8.2.1): both field counts
     * of a frame, or the count of the field a field picture holds.
     *
     * @param frame the frame of the picture
     * @param slice the first slice header
     * @param sps the sequence parameter set
     * @param structure the picture structure: {@link DecoderFrame#FRAME}, {@link DecoderFrame#TOP}
     * or {@link DecoderFrame#BOTTOM}
     */
    private void computePoc(DecoderFrame frame, H264SliceHeader slice, H264Sps sps, int structure) {
        var maxFrameNum = 1 << sps.log2MaxFrameNum;
        switch (sps.pocType) {
            case 0 -> {
                if (slice.idr) {
                    prevPocMsb = 0;
                    prevPocLsb = 0;
                }
                var maxLsb = 1 << sps.log2MaxPocLsb;
                var lsb = slice.pocLsb;
                int msb;
                if (lsb < prevPocLsb && prevPocLsb - lsb >= maxLsb / 2) {
                    msb = prevPocMsb + maxLsb;
                } else if (lsb > prevPocLsb && lsb - prevPocLsb > maxLsb / 2) {
                    msb = prevPocMsb - maxLsb;
                } else {
                    msb = prevPocMsb;
                }
                currentPocMsb = msb;
                switch (structure) {
                    case DecoderFrame.TOP -> frame.topPoc = msb + lsb;
                    case DecoderFrame.BOTTOM -> frame.bottomPoc = msb + lsb;
                    default -> {
                        frame.topPoc = msb + lsb;
                        frame.bottomPoc = frame.topPoc + slice.deltaPocBottom;
                    }
                }
            }
            case 1 -> {
                var offset = frameNumOffset(slice, maxFrameNum);
                var cycle = sps.offsetForRefFrame.length;
                var absFrameNum = cycle != 0 ? offset + slice.frameNum : 0;
                if (slice.nalRefIdc == 0 && absFrameNum > 0) {
                    absFrameNum--;
                }
                var expected = 0;
                if (absFrameNum > 0) {
                    var deltaPerCycle = 0;
                    for (var value : sps.offsetForRefFrame) {
                        deltaPerCycle += value;
                    }
                    var cycleCount = (absFrameNum - 1) / cycle;
                    var inCycle = (absFrameNum - 1) % cycle;
                    expected = cycleCount * deltaPerCycle;
                    for (var i = 0; i <= inCycle; i++) {
                        expected += sps.offsetForRefFrame[i];
                    }
                }
                if (slice.nalRefIdc == 0) {
                    expected += sps.offsetForNonRefPic;
                }
                switch (structure) {
                    case DecoderFrame.TOP -> frame.topPoc = expected + slice.deltaPoc0;
                    case DecoderFrame.BOTTOM ->
                            frame.bottomPoc = expected + sps.offsetForTopToBottomField + slice.deltaPoc0;
                    default -> {
                        frame.topPoc = expected + slice.deltaPoc0;
                        frame.bottomPoc = frame.topPoc + sps.offsetForTopToBottomField + slice.deltaPoc1;
                    }
                }
                currentFrameNumOffset = offset;
            }
            default -> {
                var offset = frameNumOffset(slice, maxFrameNum);
                int temp;
                if (slice.idr) {
                    temp = 0;
                } else if (slice.nalRefIdc == 0) {
                    temp = 2 * (offset + slice.frameNum) - 1;
                } else {
                    temp = 2 * (offset + slice.frameNum);
                }
                if (structure != DecoderFrame.BOTTOM) {
                    frame.topPoc = temp;
                }
                if (structure != DecoderFrame.TOP) {
                    frame.bottomPoc = temp;
                }
                currentFrameNumOffset = offset;
            }
        }
        if (structure == DecoderFrame.FRAME) {
            frame.poc = Math.min(frame.topPoc, frame.bottomPoc);
        }
    }

    /**
     * Derives {@code FrameNumOffset} for picture order count types 1 and 2.
     *
     * @param slice the slice header
     * @param maxFrameNum the {@code MaxFrameNum}
     * @return the offset
     */
    private int frameNumOffset(H264SliceHeader slice, int maxFrameNum) {
        if (slice.idr) {
            return 0;
        }
        var previousOffset = prevMmco5 ? 0 : prevFrameNumOffset;
        if (prevFrameNum > slice.frameNum) {
            return previousOffset + maxFrameNum;
        }
        return previousOffset;
    }

    /**
     * Inserts "non-existing" frames for a gap in {@code frame_num} (ITU-T H.264 8.2.5.2).
     *
     * @param slice the first slice header of the new picture
     * @param sps the sequence parameter set
     */
    private void handleFrameNumGap(H264SliceHeader slice, H264Sps sps) {
        var maxFrameNum = 1 << sps.log2MaxFrameNum;
        if (slice.frameNum == prevRefFrameNum || slice.frameNum == (prevRefFrameNum + 1) % maxFrameNum) {
            return;
        }
        var missing = (slice.frameNum - prevRefFrameNum - 1 + maxFrameNum) % maxFrameNum;
        if (!sps.gapsInFrameNumAllowed) {
            markLongTermStale();
        }
        var limit = Math.max(sps.maxNumRefFrames, 1);
        var skip = Math.max(0, missing - limit);
        var frameNum = (prevRefFrameNum + 1 + skip) % maxFrameNum;
        DecoderFrame source = null;
        for (var frame : dpb) {
            if (frame.anyShortTerm() && !frame.nonExisting && (source == null || frame.frameNumWrap > source.frameNumWrap)) {
                source = frame;
            }
        }
        for (var i = skip; i < missing; i++) {
            var frame = obtainFrame();
            frame.id = nextId++;
            frame.frameNum = frameNum;
            frame.nonExisting = true;
            frame.outputNeeded = false;
            frame.discard = true;
            frame.damaged = true;
            frame.staleReference = false;
            frame.decodeIndex = decodeCount;
            frame.idr = false;
            frame.mmco5 = false;
            frame.fields = DecoderFrame.FRAME;
            frame.codingStruct = DecoderFrame.CODED_FRAME;
            frame.sps = sps;
            frame.color = colorInfo(sps);
            if (source != null) {
                awaitFrame(source);
                frame.copySamplesFrom(source);
                frame.clearMotion();
            } else {
                frame.fillGray();
            }
            frame.publishDone();
            var gapHeader = new H264SliceHeader();
            gapHeader.frameNum = frameNum;
            gapHeader.nalRefIdc = 1;
            gapHeader.sps = sps;
            if (sps.pocType != 0) {
                computePoc(frame, gapHeader, sps, DecoderFrame.FRAME);
                prevFrameNumOffset = currentFrameNumOffset;
                prevFrameNum = frameNum;
                prevMmco5 = false;
            } else {
                frame.topPoc = frame.bottomPoc = frame.poc = 0;
            }
            frame.sequence = sequence;
            updateFrameNumWrap(frameNum, maxFrameNum);
            slidingWindow(sps, null);
            frame.mark(DecoderFrame.FRAME, DecoderFrame.SHORT_TERM);
            frame.frameNumWrap = frameNum;
            insertIntoDpb(frame);
            prevRefFrameNum = frameNum;
            frameNum = (frameNum + 1) % maxFrameNum;
        }
    }

    /**
     * Decodes the data of one slice of the current picture, recording failures without
     * aborting the picture.
     *
     * @param slice the slice header
     * @param size the payload size in {@link #rbsp}
     */
    private void decodeSliceData(H264SliceHeader slice, int size) {
        var sps = activeSps;
        var pps = slice.pps;
        if (pps.sps.widthInMbs != sps.widthInMbs || pps.sps.frameHeightInMbs != sps.frameHeightInMbs) {
            throw new MediaException.Malformed("slice refers to a sequence parameter set of another size");
        }
        var mbCount = sps.widthInMbs * sps.frameHeightInMbs;
        if (pps.numSliceGroups > 1) {
            if (sliceGroupMap == null || sliceGroupMap.length < mbCount) {
                sliceGroupMap = new int[mbCount];
            }
            SliceGroupMap.compute(pps, sps, slice.sliceGroupChangeCycle, currentStructure != DecoderFrame.FRAME,
                    currentMbaff, sliceGroupMap);
        }
        if (!slice.isIntra()) {
            buildLists(slice);
        }
        if (packetIncomplete) {
            current.damaged = true;
        }
        var sliceNum = nextSliceNum++;
        var map = pps.numSliceGroups > 1 ? sliceGroupMap : null;
        var partitioned = slice.nalUnitType == H264Nal.SLICE_PARTITION_A;
        if (parallel) {
            var start = sliceStartIndex(slice);
            if (start >= 0) {
                sliceStarts[start] |= (byte) (1 << slice.colorPlaneId);
            }
            var copy = SliceTask.copyOf(slice, rbsp, size, lists, listStructures, sliceNum, map);
            copy.captureReferences(currentPoc, current.topPoc, current.bottomPoc, slice.numRefIdxActive);
            currentJob.add(copy);
            if (partitioned) {
                pendingPartitions.add(copy);
            }
            return;
        }
        if (partitioned) {
            var copy = SliceTask.copyOf(slice, rbsp, size, lists, listStructures, sliceNum, map);
            copy.captureReferences(currentPoc, current.topPoc, current.bottomPoc, slice.numRefIdxActive);
            pendingPartitions.add(copy);
            return;
        }
        var task = inlineTask;
        task.captureReferences(currentPoc, current.topPoc, current.bottomPoc, slice.numRefIdxActive);
        task.rbsp = rbsp;
        task.size = size;
        task.sliceNum = sliceNum;
        task.sliceGroupMap = map;
        inlineDecoder.decodeSlice(task.forHeader(slice));
        var failure = inlineDecoder.takeError();
        if (failure != null) {
            recordError(failure);
        }
        if (current != null && inlineDecoder.complete()) {
            finishPicture();
        }
    }

    /**
     * Completes the current picture: conceals missing macroblocks, finishes deblocking and
     * border extension, marks references and stores the picture in the buffer.
     */
    private void finishPicture() {
        var frame = current;
        if (frame == null) {
            return;
        }
        try {
            if (!parallel) {
                for (var task : pendingPartitions) {
                    decodeInline(task);
                }
            }
        } finally {
            pendingPartitions.clear();
        }
        current = null;
        var slice = pictureHeader;
        var structure = currentStructure;
        var second = secondField;
        int offset;
        if (frame.idr) {
            offset = second ? frame.grainPocOffset : slice.idrPicId;
        } else {
            offset = frame.allIntra ? 0 : grainPocOffsetAtFrameStart;
        }
        frame.grainPocOffset = offset;
        grainPocOffset = offset;
        if (!parallel) {
            var failure = inlineDecoder.finish();
            frame.publishDone(structure);
            if (failure != null) {
                recordError(failure);
            }
        } else {
            startCurrentJob();
        }
        lastPicture.frameNum = slice.frameNum;
        lastPicture.ppsId = slice.ppsId;
        lastPicture.fieldPic = slice.fieldPic;
        lastPicture.bottomField = slice.bottomField;
        lastPicture.nalRefIdc = slice.nalRefIdc;
        lastPicture.idr = slice.idr;
        lastPicture.idrPicId = slice.idrPicId;
        lastPicture.pocLsb = slice.pocLsb;
        lastPicture.deltaPocBottom = slice.deltaPocBottom;
        lastPicture.deltaPoc0 = slice.deltaPoc0;
        lastPicture.deltaPoc1 = slice.deltaPoc1;
        lastPicture.sps = slice.sps;
        lastPictureValid = true;
        var sps = activeSps;
        var maxFrameNum = 1 << sps.log2MaxFrameNum;
        var mmco5 = false;
        if (slice.nalRefIdc != 0) {
            if (slice.idr) {
                for (var other : dpb) {
                    if (other != frame) {
                        other.mark(DecoderFrame.FRAME, 0);
                    }
                }
                if (slice.longTermReference) {
                    frame.mark(structure, DecoderFrame.LONG_TERM);
                    frame.longTermFrameIdx = 0;
                    maxLongTermFrameIdx = 0;
                } else {
                    frame.mark(structure, DecoderFrame.SHORT_TERM);
                    maxLongTermFrameIdx = -1;
                }
            } else {
                updateFrameNumWrap(slice.frameNum, maxFrameNum);
                var firstFieldShortTerm = second
                        && frame.marking(structure == DecoderFrame.BOTTOM ? 0 : 1) == DecoderFrame.SHORT_TERM;
                if (slice.adaptiveRefPicMarking) {
                    mmco5 = applyMmco(frame, slice, structure);
                } else if (!firstFieldShortTerm) {
                    slidingWindow(sps, frame);
                }
                if ((structure & DecoderFrame.TOP) != 0 && frame.refTop != DecoderFrame.LONG_TERM) {
                    frame.refTop = DecoderFrame.SHORT_TERM;
                }
                if ((structure & DecoderFrame.BOTTOM) != 0 && frame.refBottom != DecoderFrame.LONG_TERM) {
                    frame.refBottom = DecoderFrame.SHORT_TERM;
                }
                enforceReferenceLimit(sps, frame);
            }
            frame.frameNumWrap = mmco5 ? 0 : slice.frameNum;
        }
        if (mmco5) {
            frame.mmco5 = true;
            var temp = currentPoc;
            if ((structure & DecoderFrame.TOP) != 0) {
                frame.topPoc -= temp;
            }
            if ((structure & DecoderFrame.BOTTOM) != 0) {
                frame.bottomPoc -= temp;
            }
            if (frame.fields == DecoderFrame.TOP) {
                frame.bottomPoc = frame.topPoc;
            } else if (frame.fields == DecoderFrame.BOTTOM) {
                frame.topPoc = frame.bottomPoc;
            }
            frame.updatePoc();
            frame.frameNum = 0;
        } else if (!second) {
            frame.mmco5 = false;
        }
        if (sps.pocType == 0) {
            if (slice.nalRefIdc != 0) {
                if (mmco5) {
                    prevPocMsb = 0;
                    prevPocLsb = structure == DecoderFrame.BOTTOM ? 0 : frame.topPoc;
                } else {
                    prevPocMsb = currentPocMsb;
                    prevPocLsb = slice.pocLsb;
                }
            }
        } else {
            prevFrameNumOffset = currentFrameNumOffset;
            prevFrameNum = slice.frameNum;
            prevMmco5 = mmco5;
        }
        if (mmco5 && sps.pocType != 0) {
            prevFrameNum = 0;
        }
        if (slice.nalRefIdc != 0) {
            prevRefFrameNum = mmco5 ? 0 : slice.frameNum;
            prevRefFrameNumValid = true;
        }
        if (second) {
            removeUnused();
            outputDue();
            return;
        }
        if (slice.idr || mmco5) {
            if (slice.idr && slice.noOutputOfPriorPics) {
                for (var other : dpb) {
                    other.outputNeeded = false;
                }
            }
            flushOutput(true);
            if (mmco5) {
                sequence++;
                frame.sequence = sequence;
            }
        } else {
            removeUnused();
        }
        if (structure != DecoderFrame.FRAME) {
            if (frame.discard) {
                frame.outputNeeded = false;
            }
            insertIntoDpb(frame);
            pendingPair = frame;
            pendingPairReference = slice.nalRefIdc != 0;
            outputDue();
            return;
        }
        storePicture(frame);
    }

    /**
     * Recomputes {@code FrameNumWrap} of the frames holding a short-term reference field for a
     * current {@code frame_num} (ITU-T H.264 8.2.4.1).
     *
     * @param frameNum the current {@code frame_num}
     * @param maxFrameNum the {@code MaxFrameNum}
     */
    private void updateFrameNumWrap(int frameNum, int maxFrameNum) {
        for (var frame : dpb) {
            if (frame.anyShortTerm()) {
                frame.frameNumWrap = frame.frameNum > frameNum ? frame.frameNum - maxFrameNum : frame.frameNum;
            }
        }
    }

    /**
     * Applies the sliding window marking process (ITU-T H.264 8.2.5.3): frames, complementary
     * field pairs and non-paired fields count once each.
     *
     * @param sps the sequence parameter set
     * @param currentFrame the frame of the current picture, which is never unmarked
     */
    private void slidingWindow(H264Sps sps, DecoderFrame currentFrame) {
        var shortTerm = 0;
        var longTerm = 0;
        DecoderFrame oldest = null;
        for (var frame : dpb) {
            if (frame.anyShortTerm()) {
                shortTerm++;
                if (frame != currentFrame && (oldest == null || frame.frameNumWrap < oldest.frameNumWrap)) {
                    oldest = frame;
                }
            } else if (frame.anyLongTerm()) {
                longTerm++;
            }
        }
        if (shortTerm + longTerm >= Math.max(sps.maxNumRefFrames, 1) && oldest != null) {
            oldest.mark(DecoderFrame.FRAME, 0);
        }
    }

    /**
     * Unmarks the oldest short-term references while more frames are marked for reference than
     * {@code max_num_ref_frames} allows, which only happens for non-conforming streams.
     *
     * @param sps the sequence parameter set
     * @param currentFrame the frame of the current picture, counted once and never unmarked
     */
    private void enforceReferenceLimit(H264Sps sps, DecoderFrame currentFrame) {
        var limit = Math.max(sps.maxNumRefFrames, 1);
        while (true) {
            var count = 1;
            DecoderFrame oldest = null;
            DecoderFrame oldestLong = null;
            for (var frame : dpb) {
                if (frame == currentFrame) {
                    continue;
                }
                if (frame.anyShortTerm()) {
                    count++;
                    if (oldest == null || frame.frameNumWrap < oldest.frameNumWrap) {
                        oldest = frame;
                    }
                } else if (frame.anyLongTerm()) {
                    count++;
                    if (oldestLong == null || frame.longTermFrameIdx < oldestLong.longTermFrameIdx) {
                        oldestLong = frame;
                    }
                }
            }
            if (count <= limit) {
                return;
            }
            if (oldest != null) {
                oldest.mark(DecoderFrame.FRAME, 0);
            } else if (oldestLong != null) {
                oldestLong.mark(DecoderFrame.FRAME, 0);
            } else {
                return;
            }
        }
    }

    /**
     * Applies the memory management control operations of the current picture (ITU-T H.264
     * 8.2.5.4): to frames and complementary field pairs for a frame picture, to individual
     * fields for a field picture.
     *
     * @param frame the frame of the current picture
     * @param slice the header carrying the operations
     * @param structure the structure of the current picture
     * @return whether operation 5 was present
     */
    private boolean applyMmco(DecoderFrame frame, H264SliceHeader slice, int structure) {
        var mmco5 = false;
        var field = structure != DecoderFrame.FRAME;
        var parity = structure == DecoderFrame.BOTTOM ? 1 : 0;
        var currentPicNum = field ? 2 * slice.frameNum + 1 : slice.frameNum;
        for (var i = 0; i < slice.mmcoCount; i++) {
            var op = slice.mmcoOp[i];
            switch (op) {
                case 1 -> {
                    var picNum = currentPicNum - (slice.mmcoArg1[i] + 1);
                    var target = field ? findShortTermField(picNum, parity) : findShortTerm(picNum);
                    if (target != null) {
                        target.mark(field ? foundStructure : DecoderFrame.FRAME, 0);
                    }
                }
                case 2 -> {
                    var target = field ? findLongTermField(slice.mmcoArg1[i], parity) : findLongTerm(slice.mmcoArg1[i]);
                    if (target != null) {
                        target.mark(field ? foundStructure : DecoderFrame.FRAME, 0);
                    }
                }
                case 3 -> {
                    var picNum = currentPicNum - (slice.mmcoArg1[i] + 1);
                    var index = slice.mmcoArg2[i];
                    var target = field ? findShortTermField(picNum, parity) : findShortTerm(picNum);
                    var targetStructure = field ? foundStructure : DecoderFrame.FRAME;
                    for (var other : dpb) {
                        if (other != target && other.anyLongTerm() && other.longTermFrameIdx == index) {
                            unmarkLongTerm(other);
                        }
                    }
                    if (target != null) {
                        if (!field && target.anyLongTerm() && target.longTermFrameIdx != index) {
                            unmarkLongTerm(target);
                        }
                        target.mark(targetStructure, DecoderFrame.LONG_TERM);
                        target.longTermFrameIdx = index;
                    }
                }
                case 4 -> {
                    maxLongTermFrameIdx = slice.mmcoArg1[i] - 1;
                    for (var other : dpb) {
                        if (other.anyLongTerm() && other.longTermFrameIdx > maxLongTermFrameIdx) {
                            unmarkLongTerm(other);
                        }
                    }
                }
                case 5 -> {
                    for (var other : dpb) {
                        other.mark(DecoderFrame.FRAME, 0);
                    }
                    maxLongTermFrameIdx = -1;
                    mmco5 = true;
                }
                case 6 -> {
                    var index = slice.mmcoArg2[i];
                    for (var other : dpb) {
                        if (other != frame && other.anyLongTerm() && other.longTermFrameIdx == index) {
                            unmarkLongTerm(other);
                        }
                    }
                    frame.mark(structure, DecoderFrame.LONG_TERM);
                    frame.longTermFrameIdx = index;
                }
                default -> {
                }
            }
        }
        return mmco5;
    }

    /**
     * Marks the long-term reference fields of a frame as unused for reference.
     *
     * @param frame the frame whose long-term fields lose their marking; short-term fields keep it
     */
    private static void unmarkLongTerm(DecoderFrame frame) {
        if (frame.refTop == DecoderFrame.LONG_TERM) {
            frame.refTop = 0;
        }
        if (frame.refBottom == DecoderFrame.LONG_TERM) {
            frame.refBottom = 0;
        }
    }

    /**
     * The structure of the field the last {@link #findShortTermField} or
     * {@link #findLongTermField} found: {@link DecoderFrame#TOP} or {@link DecoderFrame#BOTTOM}.
     */
    private int foundStructure;

    /**
     * Finds the short-term reference frame or complementary field pair with a {@code PicNum}.
     *
     * @param picNum the picture number
     * @return the frame, or {@code null}
     */
    private DecoderFrame findShortTerm(int picNum) {
        for (var frame : dpb) {
            if (frame.isShortTerm() && frame.frameNumWrap == picNum) {
                return frame;
            }
        }
        return null;
    }

    /**
     * Finds the long-term reference frame or complementary field pair with a
     * {@code LongTermPicNum}.
     *
     * @param longTermPicNum the long-term picture number
     * @return the frame, or {@code null}
     */
    private DecoderFrame findLongTerm(int longTermPicNum) {
        for (var frame : dpb) {
            if (frame.isLongTerm() && frame.longTermFrameIdx == longTermPicNum) {
                return frame;
            }
        }
        return null;
    }

    /**
     * Finds the short-term reference field with a field {@code PicNum} (ITU-T H.264 8.2.4.1) and
     * records its structure in {@link #foundStructure}.
     *
     * @param picNum the picture number
     * @param parity the parity of the current field: 0 top, 1 bottom
     * @return the frame holding the field, or {@code null}
     */
    private DecoderFrame findShortTermField(int picNum, int parity) {
        for (var frame : dpb) {
            for (var q = 0; q < 2; q++) {
                if (frame.marking(q) == DecoderFrame.SHORT_TERM
                        && 2 * frame.frameNumWrap + (q == parity ? 1 : 0) == picNum) {
                    foundStructure = q + 1;
                    return frame;
                }
            }
        }
        return null;
    }

    /**
     * Finds the long-term reference field with a field {@code LongTermPicNum} (ITU-T H.264
     * 8.2.4.1) and records its structure in {@link #foundStructure}.
     *
     * @param longTermPicNum the long-term picture number
     * @param parity the parity of the current field: 0 top, 1 bottom
     * @return the frame holding the field, or {@code null}
     */
    private DecoderFrame findLongTermField(int longTermPicNum, int parity) {
        for (var frame : dpb) {
            for (var q = 0; q < 2; q++) {
                if (frame.marking(q) == DecoderFrame.LONG_TERM
                        && 2 * frame.longTermFrameIdx + (q == parity ? 1 : 0) == longTermPicNum) {
                    foundStructure = q + 1;
                    return frame;
                }
            }
        }
        return null;
    }

    /**
     * Removes frames that are neither needed for output nor used for reference, except the frame
     * whose first field may still receive its second field.
     */
    private void removeUnused() {
        for (var i = dpb.size() - 1; i >= 0; i--) {
            var frame = dpb.get(i);
            if (!frame.outputNeeded && !frame.isReference() && frame != pendingPair) {
                dpb.remove(i);
                release(frame);
            }
        }
    }

    /**
     * Inserts a frame into the buffer, bumping frames out first when it is full.
     *
     * @param frame the decoded picture to store
     */
    private void insertIntoDpb(DecoderFrame frame) {
        removeUnused();
        while (dpb.size() >= dpbSize) {
            if (!bump()) {
                DecoderFrame victim = null;
                for (var candidate : dpb) {
                    if (candidate != pendingPair) {
                        victim = candidate;
                        break;
                    }
                }
                if (victim == null) {
                    break;
                }
                dpb.remove(victim);
                victim.mark(DecoderFrame.FRAME, 0);
                release(victim);
            }
        }
        dpb.add(frame);
    }

    /**
     * Stores the finished current frame (ITU-T H.264 C.4.5) and outputs the frames that became
     * due.
     *
     * @param frame the finished picture
     */
    private void storePicture(DecoderFrame frame) {
        if (frame.discard) {
            frame.outputNeeded = false;
        }
        if (!frame.isReference()) {
            if (!frame.outputNeeded) {
                release(frame);
                return;
            }
            removeUnused();
            if (dpb.size() >= dpbSize) {
                var lowest = true;
                for (var other : dpb) {
                    if (other.outputNeeded && other != pendingPair && other.sequence == frame.sequence
                            && other.poc < frame.poc) {
                        lowest = false;
                        break;
                    }
                }
                if (lowest) {
                    emit(frame);
                    release(frame);
                    return;
                }
            }
        }
        insertIntoDpb(frame);
        outputDue();
    }

    /**
     * Outputs frames while more are waiting than the sequence's reordering allows.
     */
    private void outputDue() {
        var reorder = lowDelay && activeSps.profileIdc == 66 ? 0 : activeSps.reorderDepth();
        while (countOutputNeeded() > reorder) {
            if (!bump()) {
                break;
            }
        }
    }

    /**
     * Returns the number of buffered frames waiting for output, without the frame that may
     * still receive its second field.
     *
     * @return the count
     */
    private int countOutputNeeded() {
        var count = 0;
        for (var frame : dpb) {
            if (frame.outputNeeded && frame != pendingPair) {
                count++;
            }
        }
        return count;
    }

    /**
     * Outputs the buffered frame that comes first in output order and removes it when it is no
     * longer used for reference (ITU-T H.264 C.4.5.3); the frame that may still receive its
     * second field is not output.
     *
     * @return whether a frame was output
     */
    private boolean bump() {
        DecoderFrame first = null;
        for (var frame : dpb) {
            if (frame.outputNeeded && frame != pendingPair && (first == null || frame.sequence < first.sequence
                    || (frame.sequence == first.sequence && frame.poc < first.poc))) {
                first = frame;
            }
        }
        if (first == null) {
            return false;
        }
        first.outputNeeded = false;
        emit(first);
        if (!first.isReference()) {
            dpb.remove(first);
            release(first);
        }
        return true;
    }

    /**
     * Outputs every buffered frame and, when requested, empties the buffer.
     *
     * @param empty whether to remove every frame afterwards
     */
    private void flushOutput(boolean empty) {
        while (bump()) {
            // bump until no frame waits for output
        }
        if (empty) {
            for (var frame : dpb) {
                release(frame);
            }
            dpb.clear();
        }
    }

    /**
     * Outputs one frame: hands it to the sink at once when pictures are decoded on the calling
     * thread, and otherwise queues it until its decoding finishes, so that output order never
     * holds back the submission of later pictures.
     *
     * @param frame the picture due for output
     */
    private void emit(DecoderFrame frame) {
        if (!parallel) {
            deliver(frame);
            return;
        }
        frame.awaitingOutput = true;
        outputQueue.addLast(frame);
        drainOutput(false);
    }

    /**
     * Hands the queued frames to the sink in order, stopping at the first one still being
     * decoded unless asked to wait for it.
     *
     * @param wait whether to wait for every queued frame
     */
    private void drainOutput(boolean wait) {
        while (!outputQueue.isEmpty()) {
            var frame = outputQueue.peekFirst();
            if (!wait && !frame.isDone()) {
                return;
            }
            outputQueue.pollFirst();
            deliver(frame);
            frame.awaitingOutput = false;
        }
    }

    /**
     * Hands one decoded frame to the sink, reporting the failures of the jobs that decoded it.
     *
     * <p>A frame holding a single field, whose other field was never decoded, is output with the
     * missing field filled with mid-gray samples.
     *
     * @param frame the picture to hand to the sink
     */
    private void deliver(DecoderFrame frame) {
        awaitFrame(frame);
        var job = frame.job;
        frame.job = null;
        while (job != null) {
            var failure = job.error();
            if (failure != null) {
                recordError(failure);
            }
            var previous = job.previous;
            job.previous = null;
            job = previous;
        }
        if (frame.discard || frame.nonExisting) {
            return;
        }
        frame.completeMissingField();
        if (frame.damaged) {
            damageKey = Math.max(damageKey, 2 * frame.decodeIndex);
        } else {
            intactKey = Math.max(intactKey, 2 * frame.decodeIndex);
        }
        var grain = grainPersistence.resolve(frame.idr, frame.filmGrain);
        var packing = packingPersistence.resolve(frame.idr, frame.framePacking);
        var orientation = orientationPersistence.resolve(frame.idr, frame.orientation);
        var fieldOrder = DisplaySei.fieldOrder(frame.picStruct);
        if (fieldOrder == null && frame.codingStruct == DecoderFrame.CODED_FIELDS) {
            fieldOrder = frame.topPoc <= frame.bottomPoc ? FieldOrder.TOP_FIRST : FieldOrder.BOTTOM_FIRST;
        }
        var sideData = DisplaySei.sideData(fieldOrder, DisplaySei.repeatedFields(frame.picStruct), packing,
                orientation);
        sink.output(new OutputFrame(frame.luma, frame.cb, frame.cr, frame.luma16, frame.cb16, frame.cr16,
                frame.bitDepthLuma, frame.bitDepthChroma, frame.lumaStride, frame.chromaStride,
                frame.lumaOrigin, frame.chromaOrigin, frame.sps, frame.color, frame.pts, frame.duration, frame.keyframe,
                frame.poc, frame.damaged, grain, frame.poc + (frame.grainPocOffset << 5), sideData, frame.picStruct));
    }

    /**
     * Finishes the current picture and outputs every frame still buffered, for the end of the
     * stream, then rethrows the first failure recorded since the last packet ended, such as one
     * of the slice coded as partitions that the end of the stream completed.
     *
     * @throws MediaException the first failure recorded since the last packet ended
     */
    public void flush() {
        finishPicture();
        if (pendingPair != null) {
            closeUnpaired(pendingPair);
            pendingPair = null;
        }
        if (parallel) {
            awaitAllJobs();
        }
        flushOutput(false);
        drainOutput(true);
        lastPictureValid = false;
        discardPictureMessages();
        grainPersistence.reset();
        packingPersistence.reset();
        orientationPersistence.reset();
        var error = packetError;
        packetError = null;
        if (error != null) {
            throw error;
        }
    }

    /**
     * Builds the reference picture lists of a P or B slice (ITU-T H.264 8.2.4).
     *
     * @param slice the slice header
     */
    private void buildLists(H264SliceHeader slice) {
        var maxFrameNum = 1 << activeSps.log2MaxFrameNum;
        updateFrameNumWrap(slice.frameNum, maxFrameNum);
        Arrays.fill(listStructures[0], (byte) currentStructure);
        Arrays.fill(listStructures[1], (byte) currentStructure);
        if (currentStructure == DecoderFrame.FRAME) {
            buildFrameLists(slice);
        } else {
            buildFieldLists(slice);
        }
        for (var list = 0; list < 2; list++) {
            if (slice.refPicListModification[list]) {
                modifyList(list, slice, maxFrameNum);
            }
        }
        substituteMissing(slice);
    }

    /**
     * Builds the initial reference picture lists of a slice of a frame (ITU-T H.264 8.2.4.2.1
     * and 8.2.4.2.3): frames and complementary field pairs whose fields are both marked.
     *
     * @param slice the slice header
     */
    private void buildFrameLists(H264SliceHeader slice) {
        var shortTerm = new ArrayList<DecoderFrame>();
        var longTerm = new ArrayList<DecoderFrame>();
        for (var other : dpb) {
            if (other.isShortTerm()) {
                shortTerm.add(other);
            } else if (other.isLongTerm()) {
                longTerm.add(other);
            }
        }
        longTerm.sort((a, b) -> Integer.compare(a.longTermFrameIdx, b.longTermFrameIdx));
        if (slice.sliceType == H264SliceHeader.P || slice.sliceType == H264SliceHeader.SP) {
            shortTerm.sort((a, b) -> Integer.compare(b.frameNumWrap, a.frameNumWrap));
            var initial = scratchList;
            initial.clear();
            initial.addAll(shortTerm);
            initial.addAll(longTerm);
            fillList(0, initial, slice.numRefIdxActive[0]);
        } else {
            var skipNonExisting = activeSps.pocType == 0;
            var before = new ArrayList<DecoderFrame>();
            var after = new ArrayList<DecoderFrame>();
            for (var other : shortTerm) {
                if (skipNonExisting && other.nonExisting) {
                    skippedLostPicture();
                    continue;
                }
                if (other.poc <= currentPoc) {
                    before.add(other);
                } else {
                    after.add(other);
                }
            }
            before.sort((a, b) -> Integer.compare(b.poc, a.poc));
            after.sort((a, b) -> Integer.compare(a.poc, b.poc));
            var list0 = new ArrayList<DecoderFrame>(before);
            list0.addAll(after);
            list0.addAll(longTerm);
            var list1 = new ArrayList<DecoderFrame>(after);
            list1.addAll(before);
            list1.addAll(longTerm);
            if (list1.size() > 1 && list0.equals(list1)) {
                var first = list1.get(0);
                list1.set(0, list1.get(1));
                list1.set(1, first);
            }
            fillList(0, list0, slice.numRefIdxActive[0]);
            fillList(1, list1, slice.numRefIdxActive[1]);
        }
    }

    /**
     * Builds the initial reference picture lists of a slice of a field (ITU-T H.264 8.2.4.2.2,
     * 8.2.4.2.4 and 8.2.4.2.5): the fields of the frames holding a marked field, alternating
     * between the parities.
     *
     * @param slice the slice header
     */
    private void buildFieldLists(H264SliceHeader slice) {
        var parity = currentStructure == DecoderFrame.BOTTOM ? 1 : 0;
        var shortTerm = fieldShortTerm;
        var longTerm = fieldLongTerm;
        shortTerm.clear();
        longTerm.clear();
        for (var other : dpb) {
            if (other.anyShortTerm()) {
                shortTerm.add(other);
            }
            if (other.anyLongTerm()) {
                longTerm.add(other);
            }
        }
        longTerm.sort((a, b) -> Integer.compare(a.longTermFrameIdx, b.longTermFrameIdx));
        var frames = fieldListFrames;
        var parities = fieldListParity;
        if (slice.sliceType == H264SliceHeader.P || slice.sliceType == H264SliceHeader.SP) {
            shortTerm.sort((a, b) -> Integer.compare(b.frameNumWrap, a.frameNumWrap));
            frames.clear();
            parities.clear();
            appendFields(shortTerm, parity, DecoderFrame.SHORT_TERM, frames, parities);
            appendFields(longTerm, parity, DecoderFrame.LONG_TERM, frames, parities);
            fillFieldList(0, frames, parities, slice.numRefIdxActive[0]);
            return;
        }
        var skipNonExisting = activeSps.pocType == 0;
        var before = fieldBefore;
        var after = fieldAfter;
        before.clear();
        after.clear();
        for (var other : shortTerm) {
            if (skipNonExisting && other.nonExisting) {
                skippedLostPicture();
                continue;
            }
            if (entryPoc(other, parity) <= currentPoc) {
                before.add(other);
            } else {
                after.add(other);
            }
        }
        before.sort((a, b) -> Integer.compare(entryPoc(b, parity), entryPoc(a, parity)));
        after.sort((a, b) -> Integer.compare(entryPoc(a, parity), entryPoc(b, parity)));
        var frames1 = fieldListFrames1;
        var parities1 = fieldListParity1;
        frames1.clear();
        parities1.clear();
        var ordered = fieldOrdered;
        ordered.clear();
        ordered.addAll(before);
        ordered.addAll(after);
        frames.clear();
        parities.clear();
        appendFields(ordered, parity, DecoderFrame.SHORT_TERM, frames, parities);
        appendFields(longTerm, parity, DecoderFrame.LONG_TERM, frames, parities);
        ordered.clear();
        ordered.addAll(after);
        ordered.addAll(before);
        appendFields(ordered, parity, DecoderFrame.SHORT_TERM, frames1, parities1);
        appendFields(longTerm, parity, DecoderFrame.LONG_TERM, frames1, parities1);
        if (frames1.size() > 1 && frames.equals(frames1) && parities.equals(parities1)) {
            var frame = frames1.get(0);
            frames1.set(0, frames1.get(1));
            frames1.set(1, frame);
            var first = parities1.get(0);
            parities1.set(0, parities1.get(1));
            parities1.set(1, first);
        }
        fillFieldList(0, frames, parities, slice.numRefIdxActive[0]);
        fillFieldList(1, frames1, parities1, slice.numRefIdxActive[1]);
    }

    /**
     * Notes that the initial reference lists of a B slice left out a "non-existing" frame, as
     * ITU-T H.264 8.2.4.2.3 and 8.2.4.2.4 require when {@code pic_order_cnt_type} is 0: in a
     * sequence that allows no gaps in {@code frame_num} the frame stands in for a lost picture
     * the encoder's lists held, so the lists may name other pictures than the encoder's and the
     * current picture is damaged.
     */
    private void skippedLostPicture() {
        if (!activeSps.gapsInFrameNumAllowed) {
            current.damaged = true;
        }
    }

    /**
     * Returns the picture order count by which a reference entry is ordered in the field
     * reference lists of B slices: of the frame, complementary field pair or non-paired field,
     * and for the frame of the current picture of its first field alone.
     *
     * @param frame the reference entry
     * @param parity the parity of the current field
     * @return the picture order count
     */
    private int entryPoc(DecoderFrame frame, int parity) {
        return frame == current ? frame.fieldPoc(1 - parity) : frame.poc;
    }

    /**
     * Appends the reference fields of an ordered list of frames to a field list, alternating
     * between the parities and starting with the parity of the current field (ITU-T H.264
     * 8.2.4.2.5).
     *
     * @param frames the ordered frames
     * @param parity the parity of the current field
     * @param marking the marking the fields must carry
     * @param outFrames receives the frames of the fields
     * @param outParity receives the parities of the fields
     */
    private static void appendFields(List<DecoderFrame> frames, int parity, int marking, List<DecoderFrame> outFrames,
                                     List<Integer> outParity) {
        var count = frames.size();
        var same = 0;
        var opposite = 0;
        while (same < count || opposite < count) {
            while (same < count) {
                var frame = frames.get(same++);
                if (frame.marking(parity) == marking) {
                    outFrames.add(frame);
                    outParity.add(parity);
                    break;
                }
            }
            while (opposite < count) {
                var frame = frames.get(opposite++);
                if (frame.marking(1 - parity) == marking) {
                    outFrames.add(frame);
                    outParity.add(1 - parity);
                    break;
                }
            }
        }
    }

    /**
     * Copies an initial list into a reference list, truncated to the active count.
     *
     * @param list the list index
     * @param initial the initial list
     * @param active the number of active entries
     */
    private void fillList(int list, List<DecoderFrame> initial, int active) {
        var target = lists[list];
        Arrays.fill(target, null);
        for (var i = 0; i < Math.min(initial.size(), active); i++) {
            target[i] = initial.get(i);
        }
    }

    /**
     * Copies an initial field list into a reference list, truncated to the active count.
     *
     * @param list the list index
     * @param frames the frames of the fields
     * @param parities the parities of the fields
     * @param active the number of active entries
     */
    private void fillFieldList(int list, List<DecoderFrame> frames, List<Integer> parities, int active) {
        var target = lists[list];
        var structures = listStructures[list];
        Arrays.fill(target, null);
        for (var i = 0; i < Math.min(frames.size(), active); i++) {
            target[i] = frames.get(i);
            structures[i] = (byte) (parities.get(i) + 1);
        }
    }

    /**
     * Applies the reference picture list modification of a list (ITU-T H.264 8.2.4.3), with the
     * field picture numbers of 8.2.4.1 for a field.
     *
     * @param list the list index
     * @param slice the slice header
     * @param maxFrameNum the {@code MaxFrameNum}
     */
    private void modifyList(int list, H264SliceHeader slice, int maxFrameNum) {
        var target = lists[list];
        var structures = listStructures[list];
        var active = slice.numRefIdxActive[list];
        var field = currentStructure != DecoderFrame.FRAME;
        var parity = currentStructure == DecoderFrame.BOTTOM ? 1 : 0;
        var maxPicNum = field ? 2 * maxFrameNum : maxFrameNum;
        var currentPicNum = field ? 2 * slice.frameNum + 1 : slice.frameNum;
        var picNumPred = currentPicNum;
        var refIdx = 0;
        for (var op = 0; op < slice.modificationCount[list] && refIdx < active; op++) {
            var idc = slice.modificationIdc[list][op];
            var value = slice.modificationValue[list][op];
            DecoderFrame picture;
            int structure;
            if (idc < 2) {
                var diff = value + 1;
                if (diff > maxPicNum) {
                    throw new MediaException.Malformed("abs_diff_pic_num_minus1 out of range");
                }
                int noWrap;
                if (idc == 0) {
                    noWrap = picNumPred - diff;
                    if (noWrap < 0) {
                        noWrap += maxPicNum;
                    }
                } else {
                    noWrap = picNumPred + diff;
                    if (noWrap >= maxPicNum) {
                        noWrap -= maxPicNum;
                    }
                }
                picNumPred = noWrap;
                var number = noWrap > currentPicNum ? noWrap - maxPicNum : noWrap;
                picture = field ? findShortTermField(number, parity) : findShortTerm(number);
            } else {
                picture = field ? findLongTermField(value, parity) : findLongTerm(value);
            }
            structure = field ? foundStructure : DecoderFrame.FRAME;
            if (picture == null) {
                current.damaged = true;
                structure = currentStructure;
            }
            for (var c = active; c > refIdx; c--) {
                target[c] = target[c - 1];
                structures[c] = structures[c - 1];
            }
            target[refIdx] = picture;
            structures[refIdx] = (byte) structure;
            refIdx++;
            var n = refIdx;
            for (var c = refIdx; c <= active; c++) {
                var entry = target[c];
                var same = picture != null && entry == picture && structures[c] == structure
                        && entry.structureMarking(structure) == (idc < 2 ? DecoderFrame.SHORT_TERM : DecoderFrame.LONG_TERM);
                if (!same) {
                    target[n] = entry;
                    structures[n] = structures[c];
                    n++;
                }
            }
            for (var c = n; c <= active; c++) {
                target[c] = null;
            }
        }
        target[active] = null;
    }

    /**
     * Replaces missing list entries within the active range with a substitute picture so that
     * corrupt or truncated streams never dereference an absent picture.
     *
     * @param slice the slice header
     */
    private void substituteMissing(H264SliceHeader slice) {
        for (var list = 0; list < 2; list++) {
            var target = lists[list];
            var active = slice.numRefIdxActive[list];
            for (var i = 0; i < active; i++) {
                if (target[i] == null) {
                    var frame = substitute();
                    target[i] = frame;
                    var structure = currentStructure;
                    if (structure != DecoderFrame.FRAME && (frame.fields & structure) == 0) {
                        structure = DecoderFrame.FRAME - structure;
                    }
                    listStructures[list][i] = (byte) structure;
                    if (joinedMidSequence) {
                        current.damaged = true;
                    }
                }
            }
        }
    }

    /**
     * Returns the frame used in place of a missing reference: the most recently decoded frame of
     * the buffer other than the current one, or a gray frame.
     *
     * @return the substitute frame
     */
    private DecoderFrame substitute() {
        DecoderFrame best = null;
        for (var frame : dpb) {
            if (frame != current && (best == null || frame.id > best.id)) {
                best = frame;
            }
        }
        if (best != null) {
            return best;
        }
        if (grayFrame == null) {
            var sps = activeSps;
            grayFrame = newFrame(sps);
            grayFrame.fillGray();
            grayFrame.damaged = true;
            grayFrame.id = nextId++;
            grayFrame.sps = sps;
            grayFrame.publishDone();
        }
        return grayFrame;
    }
}
