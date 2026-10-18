package com.github.auties00.smalt.video.h264.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.Chromaticity;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.ContentLightLevel;
import com.github.auties00.smalt.image.MasteringDisplay;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.internal.nal.NalUnits;
import com.github.auties00.smalt.internal.task.ParallelBands;
import com.github.auties00.smalt.track.Packet;

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
 * <p>With several threads, each picture, once it has received all its slices, is decoded by a
 * {@link PictureJob} on the module's shared pool while the calling thread parses the following
 * NAL units; a job reading a reference picture waits only for the rows its motion vectors reach.
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
 * went stale. The engine also tracks
 * whether a key frame is needed: from any damage, any slice it had to drop and any stream
 * failure, until a later picture in decoding order is output intact.
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
     * Describes a decoded frame handed to a {@link FrameSink}.
     *
     * @param luma the luma plane including borders
     * @param cb the Cb plane including borders, or {@code null} for monochrome
     * @param cr the Cr plane including borders, or {@code null} for monochrome
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
     */
    public record OutputFrame(byte[] luma, byte[] cb, byte[] cr, int lumaStride, int chromaStride, int lumaOrigin,
                              int chromaOrigin, H264Sps sps, ColorInfo color, long pts, long duration, boolean keyframe,
                              int poc, boolean damaged) {
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
     * The picture being decoded, or {@code null} between pictures.
     */
    private DecoderFrame current;

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
     * The job of the picture being received, not started yet, or {@code null}.
     */
    private PictureJob currentJob;

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
     * The reference picture lists of the current slice.
     */
    private final DecoderFrame[][] lists = new DecoderFrame[2][33];

    /**
     * The initial list construction scratch.
     */
    private final List<DecoderFrame> scratchList = new ArrayList<>();

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
        this.inlineTask = new SliceTask(null, lists);
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
    }

    /**
     * Marks the long-term references of the decoded picture buffer as
     * {@linkplain DecoderFrame#staleReference stale}.
     */
    private void markLongTermStale() {
        for (var frame : dpb) {
            if (frame.isLongTerm()) {
                frame.staleReference = true;
            }
        }
    }

    /**
     * Waits for the pictures in progress, dropping the started pictures no thread has claimed
     * when no claimed picture can read them; afterwards no thread runs work of this engine.
     */
    public void close() {
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
        this.packetError = null;
        this.packetIncomplete = incomplete;
        if (incomplete) {
            markLongTermStale();
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
            case H264Nal.SLICE, H264Nal.IDR_SLICE, H264Nal.SPS, H264Nal.PPS, H264Nal.SEI -> {
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
                        default -> handleSlice(size, type, nalRefIdc);
                    }
                } catch (MediaException.Malformed error) {
                    noteDamage();
                    recordError(error);
                }
            }
            case H264Nal.SLICE_PARTITION_A, H264Nal.SLICE_PARTITION_B, H264Nal.SLICE_PARTITION_C ->
                    throw new MediaException.Unsupported("h264 data partitioning", "slice data partitioning (Extended profile) is not supported");
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
     * Returns the color description of the pictures of a sequence, with the high-dynamic-range
     * metadata of the last SEI messages that carried it.
     *
     * @param sps the active sequence parameter set
     * @return the color description
     */
    private ColorInfo colorInfo(H264Sps sps) {
        var info = sps.colorInfo();
        if (masteringDisplay != null) {
            info = info.withMasteringDisplay(masteringDisplay);
        }
        if (contentLightLevel != null) {
            info = info.withContentLightLevel(contentLightLevel);
        }
        return info;
    }

    /**
     * Scans an SEI message list for a recovery point (ITU-T H.264 D.1.8), a mastering display
     * color volume (D.1.29) and a content light level (D.1.31).
     *
     * <p>The high-dynamic-range metadata persists until an SEI message replaces it; a message too
     * short for its syntax is ignored.
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
            if (payloadType == 6) {
                recoveryPointPending = true;
            } else if (payloadType == 137 && payloadSize >= 24 && position + 24 <= size) {
                masteringDisplay = parseMasteringDisplay(position);
            } else if (payloadType == 144 && payloadSize >= 4 && position + 4 <= size) {
                contentLightLevel = new ContentLightLevel(u16(position), u16(position + 2));
            }
            position += payloadSize;
        }
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
        checkSupported(sps, slice);
        slice.parseRest(reader, pps, nalType, nalRefIdc);
        if (slice.fieldPic || sps.mbAdaptiveFrameField) {
            throw new MediaException.Unsupported("h264 interlaced", "field pictures and MBAFF frames are not supported");
        }
        if (slice.redundantPicCnt > 0) {
            return;
        }
        if (current != null) {
            if (isNewPicture(slice)) {
                finishPicture();
            } else if (!parallel && slice.firstMbInSlice < inlineDecoder.state().sliceNum.length
                    && inlineDecoder.state().sliceNum[slice.firstMbInSlice] >= firstSliceNum) {
                if (slice.firstMbInSlice != 0) {
                    return;
                }
                finishPicture();
            }
        }
        if (current == null) {
            if (lastPictureValid && !isNewPicture(slice, lastPicture) && slice.firstMbInSlice != 0) {
                return;
            }
            if (!started) {
                if (!slice.idr && !recoveryPointPending && slice.sliceType != H264SliceHeader.I) {
                    noteDamage();
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
        decodeSliceData(slice, size);
    }

    /**
     * Rejects coding tools this decoder does not implement.
     *
     * @param sps the sequence parameter set
     * @param slice the slice header, parsed up to {@code pic_parameter_set_id}
     * @throws MediaException.Unsupported if a tool is not supported
     */
    private static void checkSupported(H264Sps sps, H264SliceHeader slice) {
        if (sps.chromaFormatIdc == 2) {
            throw new MediaException.Unsupported("h264 4:2:2", "4:2:2 chroma is not supported");
        }
        if (sps.chromaFormatIdc == 3) {
            throw new MediaException.Unsupported("h264 4:4:4", "4:4:4 chroma is not supported");
        }
        if (sps.bitDepthLuma != 8 || sps.bitDepthChroma != 8) {
            throw new MediaException.Unsupported("h264 high bit depth", "bit depths above 8 are not supported");
        }
        if (sps.transformBypass) {
            throw new MediaException.Unsupported("h264 lossless", "lossless transform bypass is not supported");
        }
        if (slice.sliceType == H264SliceHeader.SP || slice.sliceType == H264SliceHeader.SI) {
            throw new MediaException.Unsupported("h264 sp/si slices", "SP and SI slices (Extended profile) are not supported");
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
     * Applies the first-VCL-NAL-unit detection of ITU-T H.264 7.4.1.2.4.
     *
     * @param slice the new slice header
     * @param previous the header of a slice of the previous picture
     * @return {@code true} when the slice belongs to a different picture
     */
    private static boolean isNewPicture(H264SliceHeader slice, H264SliceHeader previous) {
        if (slice.frameNum != previous.frameNum || slice.ppsId != previous.ppsId
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
     * Begins decoding a new picture: activates the sequence, handles {@code frame_num} gaps,
     * derives the picture order count and allocates the frame.
     *
     * @param slice the first slice header of the picture
     * @param sps the sequence parameter set
     */
    private void startPicture(H264SliceHeader slice, H264Sps sps) {
        activateSps(sps, slice.idr);
        if (slice.idr) {
            prevRefFrameNumValid = true;
            prevRefFrameNum = 0;
        } else if (prevRefFrameNumValid) {
            handleFrameNumGap(slice, sps);
        }
        var frame = obtainFrame();
        frame.id = nextId++;
        frame.frameNum = slice.frameNum;
        frame.idr = slice.idr;
        frame.reference = 0;
        frame.outputNeeded = true;
        frame.nonExisting = false;
        frame.mmco5 = false;
        frame.discard = false;
        frame.damaged = packetIncomplete;
        frame.staleReference = false;
        frame.decodeIndex = ++decodeCount;
        frame.decodedMbs = 0;
        frame.sps = sps;
        frame.color = colorInfo(sps);
        frame.keyframe = slice.idr || (recoveryPointPending && slice.sliceType == H264SliceHeader.I);
        recoveryPointPending = false;
        frame.pts = pendingPts;
        frame.duration = pendingDuration;
        pendingPts = Packet.NO_TIMESTAMP;
        if (slice.idr) {
            sequence++;
            joinedMidSequence = false;
        }
        computePoc(frame, slice, sps);
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
        current = frame;
        var concealSliceNum = nextSliceNum++;
        firstSliceNum = concealSliceNum;
        DecoderFrame concealSource = null;
        for (var other : dpb) {
            if (!other.nonExisting && (concealSource == null || other.id > concealSource.id)) {
                concealSource = other;
            }
        }
        var dsp = H264Dsp.INSTANCE;
        if (!parallel) {
            inlineDecoder.start(frame, firstSliceNum, concealSliceNum, concealSource, dsp);
        } else {
            while (inFlight.size() >= queueDepth) {
                joinOldest();
                drainOutput(false);
            }
            frame.beginDecoding();
            var job = new PictureJob(frame, ++submittedJobs, firstSliceNum, concealSliceNum, concealSource, dsp,
                    idleDecoders);
            currentJob = job;
            inFlight.addLast(job);
            frame.job = job;
        }
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
        while (!inFlight.isEmpty() && inFlight.peekFirst().frame.isDone()) {
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
                || activeSps.dpbFrames() != sps.dpbFrames();
        if (!formatChanged) {
            activeSps = sps;
            return;
        }
        LimitChecks.checkPicture(limits, sps.codedWidth(), sps.codedHeight());
        var frames = sps.dpbFrames() + spareFrames();
        var frameBytes = DecoderFrame.allocationSize(sps.widthInMbs, sps.frameHeightInMbs, sps.chromaFormatIdc != 0);
        LimitChecks.checkAllocation(limits, frameBytes);
        LimitChecks.checkAllocation(limits, frameBytes * frames);
        if (activeSps != null) {
            awaitAllJobs();
            flushOutput(false);
            drainOutput(true);
            for (var frame : dpb) {
                frame.reference = 0;
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
        var sps = activeSps;
        return new DecoderFrame(sps.widthInMbs, sps.frameHeightInMbs, sps.chromaFormatIdc != 0);
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
        if (frame != grayFrame && frame.mbWidth == activeSps.widthInMbs
                && frame.mbHeight == activeSps.frameHeightInMbs && pool.size() < dpbSize + spareFrames()) {
            pool.add(frame);
        }
    }

    /**
     * Derives the picture order counts of a new picture (ITU-T H.264 8.2.1).
     *
     * @param frame the frame of the picture
     * @param slice the first slice header
     * @param sps the sequence parameter set
     */
    private void computePoc(DecoderFrame frame, H264SliceHeader slice, H264Sps sps) {
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
                frame.topPoc = msb + lsb;
                frame.bottomPoc = frame.topPoc + slice.deltaPocBottom;
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
                frame.topPoc = expected + slice.deltaPoc0;
                frame.bottomPoc = frame.topPoc + sps.offsetForTopToBottomField + slice.deltaPoc1;
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
                frame.topPoc = temp;
                frame.bottomPoc = temp;
                currentFrameNumOffset = offset;
            }
        }
        frame.poc = Math.min(frame.topPoc, frame.bottomPoc);
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
            if (frame.isShortTerm() && !frame.nonExisting && (source == null || frame.frameNumWrap > source.frameNumWrap)) {
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
                computePoc(frame, gapHeader, sps);
                prevFrameNumOffset = currentFrameNumOffset;
                prevFrameNum = frameNum;
                prevMmco5 = false;
            } else {
                frame.topPoc = frame.bottomPoc = frame.poc = 0;
            }
            frame.sequence = sequence;
            updateFrameNumWrap(frameNum, maxFrameNum);
            slidingWindow(sps);
            frame.reference = DecoderFrame.SHORT_TERM;
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
            SliceGroupMap.compute(pps, sps, slice.sliceGroupChangeCycle, sliceGroupMap);
        }
        if (!slice.isIntra()) {
            buildLists(slice);
        }
        if (packetIncomplete) {
            current.damaged = true;
        }
        var sliceNum = nextSliceNum++;
        var map = pps.numSliceGroups > 1 ? sliceGroupMap : null;
        if (parallel) {
            var copy = SliceTask.copyOf(slice, rbsp, size, lists, sliceNum, map);
            copy.captureReferences(current.poc, slice.numRefIdxActive);
            currentJob.add(copy);
            return;
        }
        var task = inlineTask;
        task.captureReferences(current.poc, slice.numRefIdxActive);
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
        current = null;
        var slice = pictureHeader;
        if (!parallel) {
            var failure = inlineDecoder.finish();
            frame.publishDone();
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
                    other.reference = 0;
                }
                if (slice.longTermReference) {
                    frame.reference = DecoderFrame.LONG_TERM;
                    frame.longTermFrameIdx = 0;
                    maxLongTermFrameIdx = 0;
                } else {
                    frame.reference = DecoderFrame.SHORT_TERM;
                    maxLongTermFrameIdx = -1;
                }
            } else {
                updateFrameNumWrap(slice.frameNum, maxFrameNum);
                if (slice.adaptiveRefPicMarking) {
                    mmco5 = applyMmco(frame, slice);
                } else {
                    slidingWindow(sps);
                }
                if (frame.reference != DecoderFrame.LONG_TERM) {
                    frame.reference = DecoderFrame.SHORT_TERM;
                }
                enforceReferenceLimit(sps);
            }
            frame.frameNumWrap = mmco5 ? 0 : slice.frameNum;
        }
        frame.mmco5 = mmco5;
        if (mmco5) {
            var temp = frame.poc;
            frame.topPoc -= temp;
            frame.bottomPoc -= temp;
            frame.poc = 0;
            frame.frameNum = 0;
        }
        if (sps.pocType == 0) {
            if (slice.nalRefIdc != 0) {
                if (mmco5) {
                    prevPocMsb = 0;
                    prevPocLsb = frame.topPoc;
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
        storePicture(frame);
    }

    /**
     * Recomputes {@code FrameNumWrap} of the short-term reference frames for a current
     * {@code frame_num} (ITU-T H.264 8.2.4.1).
     *
     * @param frameNum the current {@code frame_num}
     * @param maxFrameNum the {@code MaxFrameNum}
     */
    private void updateFrameNumWrap(int frameNum, int maxFrameNum) {
        for (var frame : dpb) {
            if (frame.isShortTerm()) {
                frame.frameNumWrap = frame.frameNum > frameNum ? frame.frameNum - maxFrameNum : frame.frameNum;
            }
        }
    }

    /**
     * Applies the sliding window marking process (ITU-T H.264 8.2.5.3).
     *
     * @param sps the sequence parameter set
     */
    private void slidingWindow(H264Sps sps) {
        var shortTerm = 0;
        var longTerm = 0;
        DecoderFrame oldest = null;
        for (var frame : dpb) {
            if (frame.isShortTerm()) {
                shortTerm++;
                if (oldest == null || frame.frameNumWrap < oldest.frameNumWrap) {
                    oldest = frame;
                }
            } else if (frame.isLongTerm()) {
                longTerm++;
            }
        }
        if (shortTerm + longTerm >= Math.max(sps.maxNumRefFrames, 1) && oldest != null) {
            oldest.reference = 0;
        }
    }

    /**
     * Unmarks the oldest short-term references while more frames are marked for reference than
     * {@code max_num_ref_frames} allows, which only happens for non-conforming streams.
     *
     * @param sps the sequence parameter set
     */
    private void enforceReferenceLimit(H264Sps sps) {
        var limit = Math.max(sps.maxNumRefFrames, 1);
        while (true) {
            var count = 1;
            DecoderFrame oldest = null;
            DecoderFrame oldestLong = null;
            for (var frame : dpb) {
                if (frame.isShortTerm()) {
                    count++;
                    if (oldest == null || frame.frameNumWrap < oldest.frameNumWrap) {
                        oldest = frame;
                    }
                } else if (frame.isLongTerm()) {
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
                oldest.reference = 0;
            } else if (oldestLong != null) {
                oldestLong.reference = 0;
            } else {
                return;
            }
        }
    }

    /**
     * Applies the memory management control operations of the current picture (ITU-T H.264
     * 8.2.5.4).
     *
     * @param frame the current picture
     * @param slice the header carrying the operations
     * @return whether operation 5 was present
     */
    private boolean applyMmco(DecoderFrame frame, H264SliceHeader slice) {
        var mmco5 = false;
        var currentPicNum = slice.frameNum;
        for (var i = 0; i < slice.mmcoCount; i++) {
            var op = slice.mmcoOp[i];
            switch (op) {
                case 1 -> {
                    var picNum = currentPicNum - (slice.mmcoArg1[i] + 1);
                    var target = findShortTerm(picNum);
                    if (target != null) {
                        target.reference = 0;
                    }
                }
                case 2 -> {
                    var target = findLongTerm(slice.mmcoArg1[i]);
                    if (target != null) {
                        target.reference = 0;
                    }
                }
                case 3 -> {
                    var picNum = currentPicNum - (slice.mmcoArg1[i] + 1);
                    var index = slice.mmcoArg2[i];
                    var target = findShortTerm(picNum);
                    var existing = findLongTerm(index);
                    if (existing != null && existing != target) {
                        existing.reference = 0;
                    }
                    if (target != null) {
                        target.reference = DecoderFrame.LONG_TERM;
                        target.longTermFrameIdx = index;
                    }
                }
                case 4 -> {
                    maxLongTermFrameIdx = slice.mmcoArg1[i] - 1;
                    for (var other : dpb) {
                        if (other.isLongTerm() && other.longTermFrameIdx > maxLongTermFrameIdx) {
                            other.reference = 0;
                        }
                    }
                }
                case 5 -> {
                    for (var other : dpb) {
                        other.reference = 0;
                    }
                    maxLongTermFrameIdx = -1;
                    mmco5 = true;
                }
                case 6 -> {
                    var index = slice.mmcoArg2[i];
                    var existing = findLongTerm(index);
                    if (existing != null) {
                        existing.reference = 0;
                    }
                    frame.reference = DecoderFrame.LONG_TERM;
                    frame.longTermFrameIdx = index;
                }
                default -> {
                }
            }
        }
        return mmco5;
    }

    /**
     * Finds the short-term reference frame with a {@code PicNum}.
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
     * Finds the long-term reference frame with a {@code LongTermPicNum}.
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
     * Removes frames that are neither needed for output nor used for reference.
     */
    private void removeUnused() {
        for (var i = dpb.size() - 1; i >= 0; i--) {
            var frame = dpb.get(i);
            if (!frame.outputNeeded && !frame.isReference()) {
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
                var victim = dpb.removeFirst();
                victim.reference = 0;
                release(victim);
            }
        }
        dpb.add(frame);
    }

    /**
     * Stores the finished current picture (ITU-T H.264 C.4.5) and outputs the frames that
     * became due.
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
                    if (other.outputNeeded && other.sequence == frame.sequence && other.poc < frame.poc) {
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
        var reorder = lowDelay && activeSps.profileIdc == 66 ? 0 : activeSps.reorderDepth();
        while (countOutputNeeded() > reorder) {
            if (!bump()) {
                break;
            }
        }
    }

    /**
     * Returns the number of buffered frames waiting for output.
     *
     * @return the count
     */
    private int countOutputNeeded() {
        var count = 0;
        for (var frame : dpb) {
            if (frame.outputNeeded) {
                count++;
            }
        }
        return count;
    }

    /**
     * Outputs the buffered frame that comes first in output order and removes it when it is no
     * longer used for reference (ITU-T H.264 C.4.5.3).
     *
     * @return whether a frame was output
     */
    private boolean bump() {
        DecoderFrame first = null;
        for (var frame : dpb) {
            if (frame.outputNeeded && (first == null || frame.sequence < first.sequence
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
     * Hands one decoded frame to the sink, reporting the failure of the job that decoded it.
     *
     * @param frame the picture to hand to the sink
     */
    private void deliver(DecoderFrame frame) {
        awaitFrame(frame);
        var job = frame.job;
        if (job != null) {
            frame.job = null;
            var failure = job.error();
            if (failure != null) {
                recordError(failure);
            }
        }
        if (frame.discard || frame.nonExisting) {
            return;
        }
        if (frame.damaged) {
            damageKey = Math.max(damageKey, 2 * frame.decodeIndex);
        } else {
            intactKey = Math.max(intactKey, 2 * frame.decodeIndex);
        }
        sink.output(new OutputFrame(frame.luma, frame.cb, frame.cr, frame.lumaStride, frame.chromaStride,
                frame.lumaOrigin, frame.chromaOrigin, frame.sps, frame.color, frame.pts, frame.duration, frame.keyframe,
                frame.poc, frame.damaged));
    }

    /**
     * Finishes the current picture and outputs every frame still buffered, for the end of the
     * stream.
     */
    public void flush() {
        finishPicture();
        if (parallel) {
            awaitAllJobs();
        }
        flushOutput(false);
        drainOutput(true);
        lastPictureValid = false;
    }

    /**
     * Builds the reference picture lists of a P or B slice (ITU-T H.264 8.2.4).
     *
     * @param slice the slice header
     */
    private void buildLists(H264SliceHeader slice) {
        var frame = current;
        var maxFrameNum = 1 << activeSps.log2MaxFrameNum;
        updateFrameNumWrap(slice.frameNum, maxFrameNum);
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
            var before = new ArrayList<DecoderFrame>();
            var after = new ArrayList<DecoderFrame>();
            for (var other : shortTerm) {
                if (other.poc <= frame.poc) {
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
        for (var list = 0; list < 2; list++) {
            if (slice.refPicListModification[list]) {
                modifyList(list, slice, maxFrameNum);
            }
        }
        substituteMissing(slice);
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
     * Applies the reference picture list modification of a list (ITU-T H.264 8.2.4.3).
     *
     * @param list the list index
     * @param slice the slice header
     * @param maxFrameNum the {@code MaxPicNum} of frame coding
     */
    private void modifyList(int list, H264SliceHeader slice, int maxFrameNum) {
        var target = lists[list];
        var active = slice.numRefIdxActive[list];
        var currentPicNum = slice.frameNum;
        var picNumPred = currentPicNum;
        var refIdx = 0;
        for (var op = 0; op < slice.modificationCount[list] && refIdx < active; op++) {
            var idc = slice.modificationIdc[list][op];
            var value = slice.modificationValue[list][op];
            DecoderFrame picture;
            boolean longTerm;
            int number;
            if (idc < 2) {
                var diff = value + 1;
                if (diff > maxFrameNum) {
                    throw new MediaException.Malformed("abs_diff_pic_num_minus1 out of range");
                }
                int noWrap;
                if (idc == 0) {
                    noWrap = picNumPred - diff;
                    if (noWrap < 0) {
                        noWrap += maxFrameNum;
                    }
                } else {
                    noWrap = picNumPred + diff;
                    if (noWrap >= maxFrameNum) {
                        noWrap -= maxFrameNum;
                    }
                }
                picNumPred = noWrap;
                number = noWrap > currentPicNum ? noWrap - maxFrameNum : noWrap;
                picture = findShortTerm(number);
                longTerm = false;
            } else {
                number = value;
                picture = findLongTerm(number);
                longTerm = true;
            }
            if (picture == null) {
                current.damaged = true;
            }
            for (var c = active; c > refIdx; c--) {
                target[c] = target[c - 1];
            }
            target[refIdx++] = picture;
            var n = refIdx;
            for (var c = refIdx; c <= active; c++) {
                var entry = target[c];
                var same = entry != null && (longTerm
                        ? entry.isLongTerm() && entry.longTermFrameIdx == number
                        : entry.isShortTerm() && entry.frameNumWrap == number);
                if (!same || picture == null) {
                    target[n++] = entry;
                }
            }
            for (var c = n; c <= active; c++) {
                target[c] = null;
            }
        }
        target[active] = null;
    }

    /**
     * Replaces missing list entries within the active range with a substitute frame so that
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
                    target[i] = substitute();
                    if (joinedMidSequence) {
                        current.damaged = true;
                    }
                }
            }
        }
    }

    /**
     * Returns the frame used in place of a missing reference: the most recently decoded frame of
     * the buffer, or a gray frame.
     *
     * @return the substitute frame
     */
    private DecoderFrame substitute() {
        DecoderFrame best = null;
        for (var frame : dpb) {
            if (best == null || frame.id > best.id) {
                best = frame;
            }
        }
        if (best != null) {
            return best;
        }
        if (grayFrame == null) {
            var sps = activeSps;
            grayFrame = new DecoderFrame(sps.widthInMbs, sps.frameHeightInMbs, sps.chromaFormatIdc != 0);
            grayFrame.fillGray();
            grayFrame.damaged = true;
            grayFrame.id = nextId++;
            grayFrame.sps = sps;
            grayFrame.publishDone();
        }
        return grayFrame;
    }
}
