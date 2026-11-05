package com.github.auties00.smalt.video.hevc.encoder.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.internal.bits.MsbBitWriter;
import com.github.auties00.smalt.internal.task.ParallelBands;
import com.github.auties00.smalt.internal.task.RowProgress;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.PacketFlag;
import com.github.auties00.smalt.track.PacketSideData;
import com.github.auties00.smalt.video.h264.internal.H264BitReader;
import com.github.auties00.smalt.video.hevc.internal.CabacTables;
import com.github.auties00.smalt.video.hevc.internal.Deblocker;
import com.github.auties00.smalt.video.hevc.internal.HevcFrame;
import com.github.auties00.smalt.video.hevc.internal.HevcNal;
import com.github.auties00.smalt.video.hevc.internal.HevcPps;
import com.github.auties00.smalt.video.hevc.internal.HevcSliceHeader;
import com.github.auties00.smalt.video.hevc.internal.HevcSps;
import com.github.auties00.smalt.video.hevc.internal.HevcVps;
import com.github.auties00.smalt.video.hevc.internal.PictureHash;
import com.github.auties00.smalt.video.hevc.internal.SaoFilter;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Codes the pictures of one HEVC stream: it orders the frames into groups of B pictures between anchor
 * pictures, chooses every picture's type, reference picture set, reference lists and quantizer, decides and
 * reconstructs its coding tree units, applies the loop filters as a decoder would, writes the slice data with
 * one wavefront substream per row of coding tree blocks and assembles the access units.
 *
 * <p>The rows of coding tree blocks are decided and coded as a wavefront on the module's shared pool, by up
 * to the configured number of threads: a row starts from the context states its substream starts with and
 * decides a block once the row above has decided the block above and to the right of it, so the stream does
 * not depend on the thread count or the scheduling.
 *
 * <p>The reconstruction of every picture equals what a conforming decoder produces from the stream. Instances
 * are not thread-safe except {@link #requestKeyframe()}, {@link #setTargetBitrate(double)} and the
 * counters, which may be called from any thread.
 */
public final class EncoderEngine {
    /**
     * The settings.
     */
    private final EncoderSettings s;

    /**
     * The raw byte sequence payload of the video parameter set.
     */
    private final byte[] vps;

    /**
     * The raw byte sequence payload of the sequence parameter set.
     */
    private final byte[] sps;

    /**
     * The raw byte sequence payload of the picture parameter set.
     */
    private final byte[] pps;

    /**
     * The payload of the prefix SEI message of every IDR access unit, or {@code null}.
     */
    private final byte[] staticSei;

    /**
     * The parsed sequence parameter sets by identifier.
     */
    private final HevcSps[] spsTable = new HevcSps[16];

    /**
     * The parsed picture parameter sets by identifier.
     */
    private final HevcPps[] ppsTable = new HevcPps[64];

    /**
     * The parsed sequence parameter set.
     */
    private final HevcSps parsedSps;

    /**
     * The input converter.
     */
    private final InputConverter input;

    /**
     * The rate controller.
     */
    private final RateController rate;

    /**
     * The pictures not in use.
     */
    private final ArrayDeque<PictureData> pool = new ArrayDeque<>();

    /**
     * The reference pictures kept for later pictures, in coding order.
     */
    private final ArrayList<PictureData> dpb = new ArrayList<>();

    /**
     * The anchor pictures, most recent first, that later anchors may reference.
     */
    private final ArrayList<PictureData> anchors = new ArrayList<>();

    /**
     * The frames waiting for the anchor picture that ends their group, in display order.
     */
    private final ArrayList<Pending> queue = new ArrayList<>();

    /**
     * The row workers not in use; the wavefront participants take one each.
     */
    private final ConcurrentLinkedQueue<RowWorker> workers = new ConcurrentLinkedQueue<>();

    /**
     * The progress of the rows of the wavefront running, in coding tree blocks.
     */
    private final RowProgress progress = new RowProgress();

    /**
     * The next row of the running wavefront that no participant has claimed.
     */
    private final AtomicInteger nextRow = new AtomicInteger();

    /**
     * The helper participants of the running wavefront.
     */
    private final ParallelBands.Pending<?>[] participants;

    /**
     * The operation the calls of this engine form on the module's shared pool.
     */
    private final ParallelBands.Operation operation = new ParallelBands.Operation();

    /**
     * The number of the picture being analyzed or written, which tells a row worker whether it is set up for
     * it.
     */
    private int generation;

    /**
     * The picture being analyzed or written.
     */
    private PictureData current;

    /**
     * The slice index of {@link #current} in its frame's slice tables.
     */
    private int currentSlice;

    /**
     * The padded luma planes of the references of {@link #current}, or {@code null}.
     */
    private short[][][] currentRefPlanes;

    /**
     * The factor of the Lagrange multiplier of {@link #current}.
     */
    private double currentLambdaFactor;

    /**
     * The context initialization type of {@link #current}.
     */
    private int currentInitType;

    /**
     * The escaped substream sizes of the picture being written.
     */
    private final int[] writtenSizes;

    /**
     * The assembler of the access units, reused from picture to picture.
     */
    private final NalWriter nal;

    /**
     * The context states saved after the second coding tree block of each row, {@link CabacTables#COUNT}
     * per row.
     */
    private final byte[] wppStates;

    /**
     * The substream of each row of coding tree blocks.
     */
    private final MsbBitWriter[] substreams;

    /**
     * The deblocking filter.
     */
    private final Deblocker deblocker = new Deblocker();

    /**
     * The sample adaptive offset filter.
     */
    private final SaoFilter saoFilter = new SaoFilter();

    /**
     * The sample adaptive offset decision.
     */
    private final SaoEncoder saoEncoder = new SaoEncoder();

    /**
     * The source luma of the previous frame, for the complexity of inter pictures.
     */
    private final short[] previousSource;

    /**
     * The SATD scratch.
     */
    private final int[] satdScratch = new int[64];

    /**
     * A flat 8x8 block of the complexity estimate.
     */
    private final short[] flat = new short[64];

    /**
     * {@code QpC} of Table 8-10 for {@code qPi} 30 to 43.
     */
    private static final int[] QPC_TABLE = {29, 30, 31, 32, 33, 33, 34, 34, 35, 35, 36, 36, 37, 37};

    /**
     * The most times a picture that would underflow the buffering verifier is coded again at a coarser
     * quantizer.
     */
    private static final int MAX_RECODES = 4;

    /**
     * The presentation timestamps of the submitted frames, by submission index modulo the length.
     */
    private final long[] ptsRing = new long[16];

    /**
     * The number of frames submitted.
     */
    private long submitted;

    /**
     * The recent average of the inter complexities, against which a scene cut stands out.
     */
    private double recentInter;

    /**
     * The number of pictures coded.
     */
    private long coded;

    /**
     * The submission index of the last IDR picture.
     */
    private long idrIndex;

    /**
     * The timestamp of the last submitted frame, or {@link Packet#NO_TIMESTAMP}.
     */
    private long lastPts = Packet.NO_TIMESTAMP;

    /**
     * The decoding timestamp of the last packet, or {@link Packet#NO_TIMESTAMP}.
     */
    private long lastDts = Packet.NO_TIMESTAMP;

    /**
     * The nominal frame duration in time base units, at least 1.
     */
    private final long nominalDuration;

    /**
     * A number unique to every coded picture.
     */
    private int nextId;

    /**
     * The number of keyframe requests received.
     */
    private final AtomicLong keyframeRequests = new AtomicLong();

    /**
     * The number of keyframe requests already honored.
     */
    private long keyframeRequestsSeen;

    /**
     * The raw bits of the target bitrate set for the next frame, or of {@link Double#NaN} when it is unchanged.
     */
    private final AtomicLong pendingBitrate = new AtomicLong(NO_BITRATE);

    /**
     * The raw bits of {@link Double#NaN}, the value of {@link #pendingBitrate} without a change.
     */
    private static final long NO_BITRATE = Double.doubleToRawLongBits(Double.NaN);

    /**
     * The number of pictures coded, for {@link #frameCount()}.
     */
    private volatile long framesEncoded;

    /**
     * The number of IDR pictures coded, for {@link #keyframeCount()}.
     */
    private volatile long keyframesEncoded;

    /**
     * The number of bytes of the packets emitted, for {@link #byteCount()}.
     */
    private volatile long bytesEncoded;

    /**
     * The slice quantizer of the last picture, for {@link #lastQp()}.
     */
    private volatile int lastQp;

    /**
     * Whether the encoder is closed.
     */
    private boolean closed;

    /**
     * A frame waiting to be coded.
     */
    private static final class Pending {
        PictureData pic;
        long pts;
        long duration;
        double seconds;
        long index;
        long intraComplexity;
        long interComplexity;
    }

    /**
     * Constructs an engine.
     *
     * @param s the settings
     */
    public EncoderEngine(EncoderSettings s) {
        this.s = s;
        this.vps = ParameterSets.vps(s);
        this.sps = ParameterSets.sps(s, true);
        this.pps = ParameterSets.pps(s, true, false);
        this.staticSei = ParameterSets.staticSei(s.color);
        HevcVps.parse(reader(vps, HevcNal.VPS));
        this.parsedSps = HevcSps.parse(reader(sps, HevcNal.SPS));
        spsTable[parsedSps.id] = parsedSps;
        var parsedPps = HevcPps.parse(reader(pps, HevcNal.PPS));
        ppsTable[parsedPps.id] = parsedPps;
        this.input = new InputConverter(s);
        this.rate = new RateController(s);
        this.participants = new ParallelBands.Pending<?>[Math.max(0, s.options.threads() - 1)];
        this.wppStates = new byte[s.heightInCtbs * CabacTables.COUNT];
        this.writtenSizes = new int[s.heightInCtbs];
        this.nal = new NalWriter(Math.max(4096, s.codedWidth * s.codedHeight / 16), s.annexB);
        this.substreams = new MsbBitWriter[s.heightInCtbs];
        for (var i = 0; i < substreams.length; i++) {
            substreams[i] = new MsbBitWriter(1024);
        }
        this.previousSource = new short[s.codedWidth * s.codedHeight];
        var tb = s.timeBase();
        var fr = s.frameRate();
        this.nominalDuration = Math.max(1, Math.round(tb.denominator() * (double) fr.denominator()
                / (tb.numerator() * (double) fr.numerator())));
    }

    /**
     * Returns a reader of a payload the encoder wrote, as a decoder reads it from the NAL unit: escaped, then
     * unescaped.
     *
     * @param payload the raw byte sequence payload
     * @param type the NAL unit type
     * @return the reader, positioned after the NAL unit header
     */
    private static H264BitReader reader(byte[] payload, int type) {
        var unit = NalWriter.unit(type, payload);
        var rbsp = com.github.auties00.smalt.internal.nal.NalUnits.unescape(unit, 2, unit.length - 2);
        var padded = Arrays.copyOf(rbsp, rbsp.length + H264BitReader.PADDING);
        var reader = new H264BitReader();
        reader.init(padded, 0, rbsp.length);
        return reader;
    }

    /**
     * {@return the {@code HEVCDecoderConfigurationRecord} of the stream, a fresh copy}
     */
    public byte[] configurationRecord() {
        return ParameterSets.configurationRecord(s, vps, sps, pps, staticSei);
    }

    /**
     * Requests that the next frame be coded as an IDR picture; may be called from any thread.
     */
    public void requestKeyframe() {
        keyframeRequests.incrementAndGet();
    }

    /**
     * {@return the number of keyframe requests received}
     */
    public long keyframeRequestCount() {
        return keyframeRequests.get();
    }

    /**
     * Changes the target bitrate; may be called from any thread.
     *
     * @param bitsPerSecond the new target
     */
    public void setTargetBitrate(double bitsPerSecond) {
        pendingBitrate.set(Double.doubleToRawLongBits(bitsPerSecond));
    }

    /**
     * {@return the number of frames coded}
     */
    public long frameCount() {
        return framesEncoded;
    }

    /**
     * {@return the number of IDR pictures coded}
     */
    public long keyframeCount() {
        return keyframesEncoded;
    }

    /**
     * {@return the number of bytes produced}
     */
    public long byteCount() {
        return bytesEncoded;
    }

    /**
     * {@return the slice quantizer of the last picture}
     */
    public int lastQp() {
        return lastQp;
    }

    /**
     * {@return the bitrate rate control aims at, 0 for constant quality}
     */
    public long targetBitrate() {
        return Math.round(rate.bitrate());
    }

    /**
     * Encodes one frame and emits the packets that became ready.
     *
     * @param picture the picture, exactly the encoder's size
     * @param pts the presentation timestamp, or {@link Packet#NO_TIMESTAMP}
     * @param duration the duration in time base units, or 0 when unknown
     * @param output receives the packets
     * @throws IllegalArgumentException if the picture size differs from the encoder's or, outside real-time
     * mode, the timestamp does not increase
     * @throws IllegalStateException if the encoder is closed or a still picture stream already holds its
     * picture
     */
    public void encode(Picture picture, long pts, long duration, Consumer<Packet> output) {
        try (var _ = ParallelBands.operation(operation)) {
            encodeFrame(picture, pts, duration, output);
        }
    }

    /**
     * Encodes one frame inside the engine's operation.
     *
     * @param picture the picture, exactly the encoder's size
     * @param pts the presentation timestamp, or {@link Packet#NO_TIMESTAMP}
     * @param duration the duration in time base units, or 0 when unknown
     * @param output receives the packets
     */
    private void encodeFrame(Picture picture, long pts, long duration, Consumer<Packet> output) {
        if (closed) {
            throw new IllegalStateException("encoder is closed");
        }
        var bitrate = pendingBitrate.getAndSet(NO_BITRATE);
        if (bitrate != NO_BITRATE) {
            rate.setBitrate(Double.longBitsToDouble(bitrate));
        }
        if (picture.width() != s.inputWidth || picture.height() != s.inputHeight) {
            throw new IllegalArgumentException("picture is " + picture.width() + "x" + picture.height()
                    + ", encoder expects " + s.inputWidth + "x" + s.inputHeight);
        }
        if (s.stillPicture && submitted > 0) {
            throw new IllegalStateException("a " + s.profile() + " stream holds a single picture");
        }
        var previous = lastPts;
        var nominal = false;
        if (pts == Packet.NO_TIMESTAMP) {
            pts = previous == Packet.NO_TIMESTAMP ? 0 : previous + nominalDuration;
        } else if (previous != Packet.NO_TIMESTAMP && pts <= previous) {
            if (!s.options.realtime()) {
                throw new IllegalArgumentException("timestamp " + pts + " does not follow " + previous);
            }
            pts = previous + 1;
            nominal = true;
        }
        lastPts = pts;
        var frame = new Pending();
        frame.pic = take();
        input.convert(picture, frame.pic);
        frame.pts = pts;
        frame.duration = duration;
        frame.seconds = seconds(pts, duration, previous, nominal);
        frame.index = submitted;
        ptsRing[(int) (submitted % ptsRing.length)] = pts;
        frame.intraComplexity = complexity(frame.pic.sourceY, null);
        frame.interComplexity = submitted == 0 ? frame.intraComplexity
                : complexity(frame.pic.sourceY, previousSource);
        System.arraycopy(frame.pic.sourceY, 0, previousSource, 0, previousSource.length);
        var requests = keyframeRequests.get();
        var requested = requests != keyframeRequestsSeen;
        keyframeRequestsSeen = requests;
        var sinceIdr = submitted - idrIndex;
        var cut = !s.stillPicture && sinceIdr >= 8 && frame.interComplexity > 0.9 * frame.intraComplexity
                && recentInter > 0 && frame.interComplexity > 2.5 * recentInter;
        var idr = submitted == 0 || requested || (s.keyframeInterval > 0 && sinceIdr >= s.keyframeInterval) || cut;
        recentInter = submitted == 0 || idr ? frame.interComplexity
                : 0.7 * recentInter + 0.3 * frame.interComplexity;
        submitted++;
        if (idr) {
            codeGroup(output);
            idrIndex = frame.index;
            codeIntra(frame, output);
            return;
        }
        queue.add(frame);
        if (queue.size() > s.bFrames) {
            codeGroup(output);
        }
    }

    /**
     * Codes every frame still held.
     *
     * @param output receives the packets
     */
    public void flush(Consumer<Packet> output) {
        if (!closed) {
            try (var _ = ParallelBands.operation(operation)) {
                codeGroup(output);
            }
        }
    }

    /**
     * Releases the pictures; the engine cannot be used afterwards.
     */
    public void close() {
        closed = true;
        queue.clear();
        dpb.clear();
        anchors.clear();
        pool.clear();
        workers.clear();
        current = null;
    }

    /**
     * Returns a picture from the pool, allocating one when it is empty.
     *
     * @return the picture
     */
    private PictureData take() {
        var picture = pool.poll();
        return picture != null ? picture : new PictureData(s, parsedSps);
    }

    /**
     * Returns the duration of a frame in seconds.
     *
     * @param pts the frame's timestamp
     * @param duration the frame's duration in time base units, or 0
     * @param previous the previous frame's timestamp, or {@link Packet#NO_TIMESTAMP}
     * @param nominal whether the timestamp was replaced
     * @return the duration, from one thousandth of a second to ten seconds
     */
    private double seconds(long pts, long duration, long previous, boolean nominal) {
        var tb = s.timeBase().toDouble();
        double seconds;
        if (duration > 0) {
            seconds = duration * tb;
        } else if (!nominal && previous != Packet.NO_TIMESTAMP) {
            seconds = (pts - previous) * tb;
        } else {
            seconds = 1 / s.frameRate().toDouble();
        }
        return Math.clamp(seconds, 0.001, 10);
    }

    /**
     * Returns the complexity of a source luma plane: the sum over its 8x8 blocks of the Hadamard differences
     * against the block's mean or, for an inter estimate, the smaller of that and the differences against
     * the previous frame.
     *
     * @param plane the luma plane
     * @param previous the previous frame's luma plane, or {@code null} for the intra complexity
     * @return the complexity
     */
    private long complexity(short[] plane, short[] previous) {
        var w = s.codedWidth;
        long total = 0;
        var flat = this.flat;
        for (var y = 0; y + 8 <= s.codedHeight; y += 8) {
            for (var x = 0; x + 8 <= w; x += 8) {
                var sum = 0;
                for (var j = 0; j < 8; j++) {
                    for (var i = 0; i < 8; i++) {
                        sum += plane[(y + j) * w + x + i];
                    }
                }
                Arrays.fill(flat, (short) ((sum + 32) >> 6));
                var intra = Distortion.satd(plane, y * w + x, w, flat, 0, 8, 8, 8, satdScratch);
                if (previous != null) {
                    var inter = Distortion.satd(plane, y * w + x, w, previous, y * w + x, w, 8, 8, satdScratch);
                    total += Math.min(intra, inter);
                } else {
                    total += intra;
                }
            }
        }
        return Math.max(1, total);
    }

    /**
     * Codes the held frames as a group: the last as a P picture, the others as B pictures between the
     * previous anchor picture and it.
     *
     * @param output receives the packets
     */
    private void codeGroup(Consumer<Packet> output) {
        if (queue.isEmpty()) {
            return;
        }
        var group = new ArrayList<>(queue);
        queue.clear();
        var anchor = group.getLast();
        var previousAnchor = anchors.getFirst();
        var plan = new ArrayList<Planned>();
        var l0 = new ArrayList<PictureData>();
        for (var i = 0; i < Math.min(s.references, anchors.size()); i++) {
            l0.add(anchors.get(i));
        }
        plan.add(new Planned(anchor, RateController.PREDICTED, 0, l0, List.of(), true));
        pyramid(group, -1, group.size() - 1, previousAnchor, anchor.pic, 1, plan);
        var window = new ArrayList<PictureData>();
        window.add(anchor.pic);
        for (var i = 0; i < Math.min(s.references - 1, anchors.size()); i++) {
            window.add(anchors.get(i));
        }
        for (var k = 0; k < plan.size(); k++) {
            var entry = plan.get(k);
            var needed = new ArrayList<PictureData>();
            if (k == 0) {
                needed.addAll(l0);
            } else {
                needed.addAll(window);
            }
            for (var j = k; j < plan.size(); j++) {
                for (var ref : plan.get(j).refs()) {
                    if (!needed.contains(ref)) {
                        needed.add(ref);
                    }
                }
            }
            if (k == 0) {
                // the previous anchor and the window of later anchors stay for the B pictures and later groups
                if (!needed.contains(previousAnchor)) {
                    needed.add(previousAnchor);
                }
            }
            needed.remove(entry.frame().pic);
            needed.removeIf(ref -> !dpb.contains(ref));
            codeInter(entry, needed, output);
            if (k == 0) {
                anchors.addFirst(anchor.pic);
                while (anchors.size() > Math.max(1, s.references)) {
                    anchors.removeLast();
                }
            }
        }
        dpb.removeIf(p -> {
            var keep = anchors.contains(p);
            if (!keep) {
                pool.add(p);
            }
            return !keep;
        });
    }

    /**
     * A picture of a group with its coding parameters.
     *
     * @param frame the frame and its timing
     * @param type the picture type of the rate control
     * @param depth the pyramid depth of a B picture
     * @param l0 the list 0 references, closest first
     * @param l1 the list 1 references, closest first
     * @param reference whether later pictures reference it
     */
    private record Planned(Pending frame, int type, int depth, List<PictureData> l0, List<PictureData> l1,
                           boolean reference) {
        /**
         * {@return the references of both lists}
         */
        List<PictureData> refs() {
            var all = new ArrayList<>(l0);
            for (var ref : l1) {
                if (!all.contains(ref)) {
                    all.add(ref);
                }
            }
            return all;
        }
    }

    /**
     * Plans the B pictures between two coded pictures of a group, the middle one first.
     *
     * @param group the group's frames in display order
     * @param lo the index in {@code group} of the lower reference, -1 for the previous anchor
     * @param hi the index in {@code group} of the upper reference
     * @param low the lower reference picture
     * @param high the upper reference picture
     * @param depth the pyramid depth of the pictures planned
     * @param plan receives the pictures in coding order
     */
    private void pyramid(List<Pending> group, int lo, int hi, PictureData low, PictureData high, int depth,
                         List<Planned> plan) {
        if (hi - lo < 2) {
            return;
        }
        var mid = (lo + hi) >>> 1;
        if (lo < 0) {
            mid = (hi - 1) / 2;
        }
        var frame = group.get(mid);
        var reference = mid - lo >= 2 || hi - mid >= 2;
        plan.add(new Planned(frame, RateController.BIPREDICTED, depth, List.of(low), List.of(high), reference));
        pyramid(group, lo, mid, low, frame.pic, depth + 1, plan);
        pyramid(group, mid, hi, frame.pic, high, depth + 1, plan);
    }

    /**
     * Codes an IDR picture, which empties the decoded picture buffer.
     *
     * @param frame the frame and its timing
     * @param output receives the packet
     */
    private void codeIntra(Pending frame, Consumer<Packet> output) {
        for (var p : dpb) {
            pool.add(p);
        }
        dpb.clear();
        anchors.clear();
        var qp = rate.pictureQp(RateController.INTRA, 0, frame.intraComplexity, frame.seconds);
        // the intra Lagrange multiplier factor of HM's TEncSlice::initEncSlice
        var factor = 0.57 * (1.0 - Math.clamp(0.05 * s.bFrames, 0.0, 0.5));
        codePicture(frame, HevcSliceHeader.I, HevcNal.IDR_W_RADL, List.of(), List.of(), List.of(), true, qp, factor,
                RateController.INTRA, output);
        anchors.addFirst(frame.pic);
    }

    /**
     * Codes a P or B picture of a group.
     *
     * @param entry the planned picture
     * @param keep the pictures its reference picture set keeps
     * @param output receives the packet
     */
    private void codeInter(Planned entry, List<PictureData> keep, Consumer<Packet> output) {
        var frame = entry.frame();
        var complexity = frame.interComplexity;
        var qp = rate.pictureQp(entry.type(), entry.depth(), complexity, frame.seconds);
        // the QPFactor values of the HM configurations: 0.578 for P pictures, 0.4624 and 0.68 for the inner and
        // the deepest B pictures of the hierarchy
        var factor = entry.type() == RateController.PREDICTED ? 0.578 : entry.reference() ? 0.4624 : 0.68;
        var sliceType = entry.type() == RateController.PREDICTED ? HevcSliceHeader.P : HevcSliceHeader.B;
        var nalType = entry.reference() ? HevcNal.TRAIL_R : HevcNal.TRAIL_N;
        codePicture(frame, sliceType, nalType, entry.l0(), entry.l1(), keep, entry.reference(), qp, factor,
                entry.type(), output);
        if (!entry.reference()) {
            pool.add(frame.pic);
        }
    }

    /**
     * Codes one picture and emits its packet.
     *
     * <p>A picture that would underflow the buffering verifier is coded again at a coarser quantizer, up to
     * {@link #MAX_RECODES} times or until the quantizer reaches the largest of the window.
     *
     * @param frame the frame and its timing
     * @param sliceType the slice type
     * @param nalType the NAL unit type
     * @param l0 the list 0 references, closest first
     * @param l1 the list 1 references, closest first
     * @param keep the pictures the reference picture set keeps, besides the references
     * @param reference whether later pictures reference this one
     * @param qp the slice quantizer rate control chose
     * @param lambdaFactor the factor of the Lagrange multiplier
     * @param type the picture type of the rate control
     * @param output receives the packet
     */
    private void codePicture(Pending frame, int sliceType, int nalType, List<PictureData> l0, List<PictureData> l1,
                             List<PictureData> keep, boolean reference, int qp, double lambdaFactor, int type,
                             Consumer<Packet> output) {
        var d = frame.pic;
        var f = d.frame;
        for (var attempt = 0; ; attempt++) {
            assemble(frame, sliceType, nalType, l0, l1, keep, qp, lambdaFactor);
            var bits = 8L * nal.size();
            if (attempt == MAX_RECODES || qp >= s.options.maxQuantizer() || !rate.overflows(bits)) {
                break;
            }
            qp = rate.recodeQp(qp, bits);
            nal.discard();
        }
        var irap = HevcNal.isIrap(nalType);
        var data = nal.take();
        if (reference) {
            d.paddedY = MotionSearch.pad(f.y, s.codedWidth, s.codedHeight, d.paddedY);
            dpb.add(d);
        }
        rate.update(type, qp, 8L * data.length, type == RateController.INTRA ? frame.intraComplexity
                : frame.interComplexity, frame.seconds);
        var dts = frame.pts;
        if (s.maxNumReorder > 0) {
            // the timestamp of the frame maxNumReorder places earlier in display order, which no picture coded
            // this far follows by more than maxNumReorder places, so it never exceeds this picture's timestamp
            var lead = coded - s.maxNumReorder;
            var leadPts = lead >= 0 ? ptsRing[(int) (lead % ptsRing.length)] : ptsRing[0] + lead * nominalDuration;
            dts = Math.min(frame.pts, leadPts);
        }
        if (lastDts != Packet.NO_TIMESTAMP && dts <= lastDts) {
            dts = lastDts + 1;
        }
        lastDts = dts;
        coded++;
        framesEncoded++;
        bytesEncoded += data.length;
        lastQp = qp;
        if (irap) {
            keyframesEncoded++;
        }
        var flags = irap ? EnumSet.of(PacketFlag.KEY)
                : reference ? EnumSet.noneOf(PacketFlag.class) : EnumSet.of(PacketFlag.DISCARDABLE);
        var packetDuration = frame.duration > 0 ? frame.duration : nominalDuration;
        output.accept(new Packet(0, data, frame.pts, dts, packetDuration, flags, PacketSideData.NONE));
    }

    /**
     * Codes one picture at a quantizer, reconstructs and filters it, and writes its access unit into
     * {@link #nal}.
     *
     * @param frame the frame and its timing
     * @param sliceType the slice type
     * @param nalType the NAL unit type
     * @param l0 the list 0 references, closest first
     * @param l1 the list 1 references, closest first
     * @param keep the pictures the reference picture set keeps, besides the references
     * @param qp the slice quantizer
     * @param lambdaFactor the factor of the Lagrange multiplier
     */
    private void assemble(Pending frame, int sliceType, int nalType, List<PictureData> l0, List<PictureData> l1,
                          List<PictureData> keep, int qp, double lambdaFactor) {
        var d = frame.pic;
        d.reset();
        var poc = (int) (frame.index - idrIndex);
        var f = d.frame;
        f.poc = poc;
        f.outputPoc = poc;
        f.id = nextId++;
        var p = d.params;
        p.nalType = nalType;
        p.sliceType = sliceType;
        p.poc = poc;
        p.negativeCount = 0;
        p.positiveCount = 0;
        var negatives = new ArrayList<PictureData>();
        var positives = new ArrayList<PictureData>();
        var all = new ArrayList<PictureData>(keep);
        for (var ref : l0) {
            if (!all.contains(ref)) {
                all.add(ref);
            }
        }
        for (var ref : l1) {
            if (!all.contains(ref)) {
                all.add(ref);
            }
        }
        for (var ref : all) {
            (ref.frame.poc < poc ? negatives : positives).add(ref);
        }
        negatives.sort((a, b) -> Integer.compare(b.frame.poc, a.frame.poc));
        positives.sort((a, b) -> Integer.compare(a.frame.poc, b.frame.poc));
        for (var ref : negatives) {
            p.negativeDelta[p.negativeCount] = poc - ref.frame.poc;
            p.negativeUsed[p.negativeCount] = l0.contains(ref) || l1.contains(ref);
            p.negativeCount++;
        }
        for (var ref : positives) {
            p.positiveDelta[p.positiveCount] = ref.frame.poc - poc;
            p.positiveUsed[p.positiveCount] = l0.contains(ref) || l1.contains(ref);
            p.positiveCount++;
        }
        p.temporalMvp = !s.stillPicture;
        p.saoEnabled = true;
        p.saoLuma = true;
        p.saoChroma = true;
        p.numRefIdx[0] = l0.size();
        p.numRefIdx[1] = sliceType == HevcSliceHeader.B ? l1.size() : 0;
        p.collocatedFromL0 = sliceType != HevcSliceHeader.B;
        p.collocatedRefIdx = 0;
        p.maxNumMergeCand = 5;
        p.sliceQp = qp;
        var header = parseHeader(SliceHeaderWriter.write(s, p, null), nalType);
        header.pps.activate(header.sps);
        d.header = header;
        Arrays.fill(d.pic.ctbHeader, header);
        HevcFrame[][] refList = null;
        short[][][] refPlanes = null;
        if (sliceType != HevcSliceHeader.I) {
            refList = new HevcFrame[2][16];
            refPlanes = new short[2][16][];
            for (var list = 0; list < 2; list++) {
                var refs = list == 0 ? l0 : l1;
                for (var i = 0; i < header.numRefIdx[list]; i++) {
                    refList[list][i] = refs.get(i).frame;
                    refPlanes[list][i] = refs.get(i).paddedY;
                }
            }
        }
        d.refList = refList;
        var pocs = new int[32];
        var longTerm = new boolean[32];
        var ids = new int[32];
        Arrays.fill(ids, -1);
        if (refList != null) {
            for (var list = 0; list < 2; list++) {
                for (var i = 0; i < header.numRefIdx[list]; i++) {
                    pocs[list * 16 + i] = refList[list][i].poc;
                    ids[list * 16 + i] = refList[list][i].id;
                }
            }
        }
        var sliceIndex = f.addSlice(pocs, longTerm, ids);
        analyze(d, sliceIndex, refPlanes, lambdaFactor);
        deblocker.filterRows(f, d.pic, 0, s.heightInCtbs);
        var lambda = lambdaFactor * StrictMath.pow(2.0, (qp + 6 * (s.bitDepth - 8) - 12) / 3.0);
        var chromaLambda = lambda / chromaWeight(qp);
        var used = saoEncoder.decide(d, lambda, chromaLambda);
        header.saoLuma = (used & 1) != 0;
        header.saoChroma = (used & 2) != 0;
        p.saoLuma = header.saoLuma;
        p.saoChroma = header.saoChroma;
        if (used != 0) {
            saoFilter.filterLines(f, d.pic, 0, f.height, false);
        }
        var sizes = writeSlice(d);
        var headerBytes = SliceHeaderWriter.write(s, p, sizes);
        var irap = HevcNal.isIrap(nalType);
        if (irap && s.annexB) {
            nal.append(HevcNal.VPS, 0, vps);
            nal.append(HevcNal.SPS, 0, sps);
            nal.append(HevcNal.PPS, 0, pps);
        }
        if (irap && staticSei != null) {
            nal.append(HevcNal.PREFIX_SEI, 0, staticSei);
        }
        nal.begin(nalType, 0);
        nal.write(headerBytes, 0, headerBytes.length);
        for (var substream : substreams) {
            nal.write(substream.array(), 0, substream.size());
        }
        nal.end();
        if (s.hashType >= 0) {
            nal.append(HevcNal.SUFFIX_SEI, 0, ParameterSets.pictureHashSei(s.hashType,
                    PictureHash.compute(f, parsedSps, s.hashType)));
        }
    }

    /**
     * Returns the weight of chroma squared errors at a quantizer.
     *
     * @param qp the slice quantizer
     * @return the weight
     */
    private double chromaWeight(int qp) {
        var bdOffset = 6 * (s.bitDepth - 8);
        var qPi = Math.clamp(qp, -bdOffset, 57);
        var qpC = qPi < 30 ? qPi : qPi > 43 ? qPi - 6 : QPC_TABLE[qPi - 30];
        return StrictMath.pow(2.0, (qp - qpC) / 3.0);
    }

    /**
     * Parses a slice segment header the encoder wrote.
     *
     * @param bytes the header bytes
     * @param nalType the NAL unit type
     * @return the header
     */
    private HevcSliceHeader parseHeader(byte[] bytes, int nalType) {
        try {
            return HevcSliceHeader.parse(reader(bytes, nalType), nalType, 0, ppsTable, spsTable, null);
        } catch (MediaException exception) {
            throw new IllegalStateException("the encoder wrote a slice header it cannot parse", exception);
        }
    }

    /**
     * Decides and reconstructs the coding tree units of a picture, row by row, with the context states of
     * wavefront parallel processing.
     *
     * @param d the picture
     * @param sliceIndex the index of the slice in the frame's slice tables
     * @param refPlanes the padded luma planes of the references, or {@code null}
     * @param lambdaFactor the factor of the Lagrange multiplier
     */
    private void analyze(PictureData d, int sliceIndex, short[][][] refPlanes, double lambdaFactor) {
        current = d;
        currentSlice = sliceIndex;
        currentRefPlanes = refPlanes;
        currentLambdaFactor = lambdaFactor;
        currentInitType = initType(d.header);
        generation++;
        wavefront(true);
    }

    /**
     * Decides the coding tree units of one row of the current picture.
     *
     * @param w the row worker
     * @param row the row of coding tree blocks
     * @throws CancellationException if another row failed
     */
    private void analyzeRow(RowWorker w, int row) {
        var d = current;
        if (w.analysisGeneration != generation) {
            w.ctu.setPicture(d, currentSlice, currentRefPlanes, currentLambdaFactor);
            w.analysisGeneration = generation;
        }
        var width = s.widthInCtbs;
        if (row == 0 || width < 2) {
            w.counter.initContexts(currentInitType, d.header.sliceQp);
        }
        for (var x = 0; x < width; x++) {
            if (row > 0) {
                await(row - 1, Math.min(x + 2, width));
                if (x == 0 && width >= 2) {
                    w.counter.loadStates(wppStates, (row - 1) * CabacTables.COUNT);
                }
            }
            w.ctu.encode(row * width + x, w.counter);
            if (x == 1) {
                w.counter.storeStates(wppStates, row * CabacTables.COUNT);
            }
            progress.advance(row, x + 1);
        }
    }

    /**
     * Waits until a row of the running wavefront has reached a progress.
     *
     * @param row the row of coding tree blocks
     * @param needed the number of its coding tree blocks that must be done
     * @throws CancellationException if another row failed
     */
    private void await(int row, int needed) {
        if (!progress.await(row, needed)) {
            throw new CancellationException("another row of the picture failed");
        }
    }

    /**
     * Runs the analysis or the writing of the rows of the current picture as a wavefront: the calling thread
     * and up to {@code threads - 1} helpers of the module's shared pool claim the rows in order.
     *
     * <p>A row only waits for the row before it, which a running participant claimed earlier, so the
     * wavefront completes even when no helper is free. A failing row fails the wavefront, so no participant
     * waits forever, and its failure is rethrown once every participant has finished.
     *
     * @param analysis whether to analyze the rows, otherwise to write their substreams
     */
    private void wavefront(boolean analysis) {
        var rows = s.heightInCtbs;
        progress.begin(rows);
        nextRow.set(0);
        var helpers = rows < 2 || !ParallelBands.enabled() ? 0
                : Math.min(Math.min(participants.length, rows - 1), ParallelBands.available());
        for (var i = 0; i < helpers; i++) {
            participants[i] = ParallelBands.start(() -> participate(analysis));
        }
        Throwable failure = null;
        try {
            participate(analysis);
        } catch (RuntimeException | Error exception) {
            failure = exception;
        }
        for (var i = 0; i < helpers; i++) {
            if (participants[i].tryCancel()) {
                participants[i] = null;
            }
        }
        for (var i = 0; i < helpers; i++) {
            var participant = participants[i];
            if (participant == null) {
                continue;
            }
            participants[i] = null;
            try {
                participant.join();
            } catch (RuntimeException | Error exception) {
                if (failure == null || failure instanceof CancellationException
                        && !(exception instanceof CancellationException)) {
                    failure = exception;
                }
            }
        }
        switch (failure) {
            case null -> {
            }
            case RuntimeException exception -> throw exception;
            case Error error -> throw error;
            default -> throw new IllegalStateException(failure);
        }
    }

    /**
     * Claims rows of the running wavefront in order and processes them until none is left.
     *
     * @param analysis whether to analyze the rows, otherwise to write their substreams
     */
    private void participate(boolean analysis) {
        var w = workers.poll();
        if (w == null) {
            w = new RowWorker(s);
        }
        try {
            for (var row = nextRow.getAndIncrement(); row < s.heightInCtbs; row = nextRow.getAndIncrement()) {
                if (analysis) {
                    analyzeRow(w, row);
                } else {
                    writeRow(w, row);
                }
            }
        } catch (RuntimeException | Error exception) {
            progress.fail();
            throw exception;
        } finally {
            workers.add(w);
        }
    }

    /**
     * The state of one participant of a wavefront: the mode decision and the coders of a row.
     */
    private static final class RowWorker {
        /**
         * The mode decision of the coding tree units.
         */
        final CtuEncoder ctu;

        /**
         * The counter that follows the context states of the analysis of a row.
         */
        final CabacCounter counter = new CabacCounter();

        /**
         * The syntax coder of the slice data.
         */
        final SyntaxCoder syntax = new SyntaxCoder();

        /**
         * The arithmetic coder of the slice data.
         */
        final CabacWriter writer = new CabacWriter();

        /**
         * The picture {@link #ctu} is set up for.
         */
        int analysisGeneration = -1;

        /**
         * The picture {@link #syntax} is set up for.
         */
        int writeGeneration = -1;

        /**
         * Constructs a worker.
         *
         * @param s the settings
         */
        RowWorker(EncoderSettings s) {
            this.ctu = new CtuEncoder(s);
            syntax.setCoder(writer);
        }
    }

    /**
     * Returns the context initialization type of a slice (equation 9-7).
     *
     * @param header the slice segment header
     * @return the type, 0 to 2
     */
    private static int initType(HevcSliceHeader header) {
        return header.sliceType == HevcSliceHeader.I ? 0
                : header.sliceType == HevcSliceHeader.P ? (header.cabacInit ? 2 : 1) : (header.cabacInit ? 1 : 2);
    }

    /**
     * Writes the slice data of a picture, one substream per row of coding tree blocks, and returns the escaped
     * size of each substream.
     *
     * @param d the picture
     * @return the substream sizes after emulation prevention
     */
    private int[] writeSlice(PictureData d) {
        current = d;
        currentInitType = initType(d.header);
        generation++;
        wavefront(false);
        return writtenSizes;
    }

    /**
     * Writes the substream of one row of the current picture.
     *
     * @param w the row worker
     * @param row the row of coding tree blocks
     * @throws CancellationException if another row failed
     */
    private void writeRow(RowWorker w, int row) {
        var d = current;
        var syntax = w.syntax;
        var writer = w.writer;
        if (w.writeGeneration != generation) {
            syntax.setPicture(d);
            w.writeGeneration = generation;
        }
        var width = s.widthInCtbs;
        var rows = s.heightInCtbs;
        var out = substreams[row];
        out.reset();
        writer.start(out);
        if (row == 0 || width < 2) {
            writer.initContexts(currentInitType, d.header.sliceQp);
        } else {
            await(row - 1, 2);
            writer.loadStates(wppStates, (row - 1) * CabacTables.COUNT);
        }
        for (var x = 0; x < width; x++) {
            var ctbAddr = row * width + x;
            syntax.codingTreeUnit(ctbAddr, true);
            if (x == 1) {
                writer.storeStates(wppStates, row * CabacTables.COUNT);
                progress.advance(row, 2);
            }
            var last = row == rows - 1 && x == width - 1;
            writer.terminate(last ? 1 : 0);
            if (x == width - 1 && !last) {
                writer.terminate(1);
            }
        }
        writer.finish();
        out.writeBit(1);
        out.alignWithZeros();
        writtenSizes[row] = NalWriter.escapedLength(out.array(), 0, out.size());
        progress.advance(row, width);
    }
}
