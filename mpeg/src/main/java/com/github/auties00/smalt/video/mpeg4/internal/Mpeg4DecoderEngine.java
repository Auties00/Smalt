package com.github.auties00.smalt.video.mpeg4.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.internal.task.ParallelBands;
import com.github.auties00.smalt.video.FrameSideData;
import com.github.auties00.smalt.video.VideoLayer;
import com.github.auties00.smalt.video.h263.internal.H263PlusStream;
import com.github.auties00.smalt.video.mpeg4.internal.object.ObjectStream;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.PriorityQueue;

/**
 * Decodes MPEG-4 Visual elementary stream data (ISO/IEC 14496-2) and H.263 pictures into
 * {@link Frame}s handed out in display order.
 *
 * <p>The engine accepts the data of one container packet at a time. It finds the start codes of
 * the visual object sequence, visual object, video object layer, group of VOPs and VOP headers, or
 * the picture start codes of the short video header and of H.263, decodes every picture they
 * delimit, and keeps the forward and backward references of B-VOPs. A packet may hold several
 * VOPs, as packed bitstreams do (a P-VOP followed by a B-VOP, the next packet holding a not-coded
 * placeholder VOP).
 *
 * <p>Pictures leave in display order: with B-VOPs possible, each reference picture is held until
 * the next reference picture is decoded or {@link #flush()} is called. Every picture takes the
 * smallest pending packet timestamp, which is the display order of the packets' timestamps
 * whether a container stores decoding or presentation times; a timestamp that does not follow the
 * previous picture's (two pictures timed alike, as a {@code vop_time_increment} outside the range of
 * its {@code vop_time_increment_resolution} gives) is replaced by the previous one plus the frame
 * duration, at least one tick. Pictures without a pending timestamp continue from the previous one by
 * the VOP time difference. VOPs that cannot be decoded (a P-VOP
 * or B-VOP whose references precede the first intra VOP) and not-coded VOPs produce no picture.
 *
 * <p>With frame threads (see the constructor) the engine does not decode a picture before it
 * returns: it parses the headers of the packet, starts the picture as a {@link VopJob} on the
 * module's helper threads, and hands the pictures out as their jobs finish, in display order, which
 * may be during a later call. At most as many pictures are in flight as threads may be used.
 *
 * <p>An instance is not thread-safe.
 */
public final class Mpeg4DecoderEngine {
    /**
     * Reads eight bytes of an array as one little-endian {@code long}.
     */
    private static final VarHandle LONG = MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.LITTLE_ENDIAN);

    /**
     * The most bytes of a VOP the decoder loads to parse its header when pictures are decoded
     * concurrently, far more than any header takes.
     */
    private static final int HEADER_BYTES = 4096;

    /**
     * Receives the pictures in display order.
     */
    public interface Output {
        /**
         * Hands out one picture.
         *
         * @param frame the picture, valid until the call returns
         * @param layer the layer the picture belongs to
         * @param pts the presentation timestamp in the track time base, or
         * {@code Long.MIN_VALUE}
         * @param duration the duration in the track time base, or 0
         */
        void emit(Frame frame, VideoObjectLayer layer, long pts, long duration);

        /**
         * Hands out one picture of a layer decoded by the object decoder or the H.263 annex decoder.
         *
         * @param picture the picture, valid until the call returns
         * @param layer the layer the picture belongs to
         * @param pts the presentation timestamp in the track time base, or
         * {@code Long.MIN_VALUE}
         * @param duration the duration in the track time base, or 0
         * @param keyframe whether the picture comes from an intra VOP or picture
         * @param sideData the side data of the frame
         */
        void emitPicture(Picture picture, VideoObjectLayer layer, long pts, long duration, boolean keyframe,
                         FrameSideData sideData);
    }

    /**
     * Converts a VOP time difference in ticks to the track time base.
     */
    public interface TimeScale {
        /**
         * Converts a time difference.
         *
         * @param ticks the difference in ticks
         * @param resolution the ticks per second
         * @return the difference in the track time base
         */
        long convert(long ticks, int resolution);
    }

    /**
     * The limits.
     */
    private final MediaLimits limits;

    /**
     * Whether the stream is H.263 rather than MPEG-4 Visual.
     */
    private final boolean h263;

    /**
     * Whether the stream is Sorenson H.263, one picture per packet.
     */
    private final boolean sorenson;

    /**
     * The number of threads reconstruction may use.
     */
    private final int threads;

    /**
     * The receiver of pictures.
     */
    private final Output output;

    /**
     * The conversion of VOP times to the track time base.
     */
    private final TimeScale timeScale;

    /**
     * The reader of the current picture.
     */
    private final BitReader reader = new BitReader();

    /**
     * The parser.
     */
    private final VopParser parser = new VopParser();

    /**
     * The H.263 picture header parser.
     */
    private final H263Header h263Header = new H263Header();

    /**
     * The reconstructors, one per band.
     */
    private Reconstructor[] reconstructors = {new Reconstructor()};

    /**
     * The VOP header being decoded.
     */
    private final Vop vop = new Vop();

    /**
     * The active layer, or {@code null} before the first video object layer header.
     */
    private VideoObjectLayer layer;

    /**
     * The bytes of the active video object layer header, to recognize repetitions.
     */
    private byte[] layerBytes;

    /**
     * The {@code profile_and_level_indication} of the visual object sequence.
     */
    private int profileAndLevel;

    /**
     * The {@code visual_object_verid} of the visual object.
     */
    private int visualVerid = 1;

    /**
     * The color description of the visual object, held in a layer, or {@code null}.
     */
    private VideoObjectLayer signal;

    /**
     * The free pictures of the active size.
     */
    private final ArrayDeque<Frame> pool = new ArrayDeque<>();

    /**
     * The forward reference: the older of the two last reference pictures, or {@code null}.
     */
    private Frame past;

    /**
     * The backward reference: the most recent reference picture, or {@code null}.
     */
    private Frame future;

    /**
     * Whether {@link #future} has been handed out.
     */
    private boolean futureShown = true;

    /**
     * The picture B-VOPs are decoded into.
     */
    private Frame bFrame;

    /**
     * The whole seconds of the last reference VOP's time base.
     */
    private long timeBase;

    /**
     * The whole seconds of the time base of the reference VOP before the last.
     */
    private long previousTimeBase;

    /**
     * The time of the forward reference in ticks.
     */
    private long pastTime;

    /**
     * The time of the backward reference in ticks.
     */
    private long futureTime;

    /**
     * The frame period {@code Tframe} of interlaced direct mode (7.7.2.2): the time from the
     * forward reference of the first B-VOP after the video object layer header to that B-VOP, or 0
     * before it.
     */
    private long framePeriod;

    /**
     * Whether pictures are reordered for B-VOPs.
     */
    private boolean delayed;

    /**
     * The pending packet timestamps.
     */
    private final PriorityQueue<Long> timestamps = new PriorityQueue<>();

    /**
     * The number of not-coded VOPs owed to packed bitstreams: each packet that carried more than
     * one VOP leaves one placeholder packet whose timestamp belongs to the held picture.
     */
    private int owed;

    /**
     * The timestamp of the last picture handed out, or {@code Long.MIN_VALUE}.
     */
    private long lastPts = Long.MIN_VALUE;

    /**
     * The time in ticks of the last picture handed out.
     */
    private long lastTime;

    /**
     * The duration of the packet being decoded.
     */
    private long packetDuration;

    /**
     * The duration of a frame from the track, in the track time base, or 0.
     */
    private final long trackFrameDuration;

    /**
     * Whether the current packet's picture was damaged.
     */
    private boolean damaged;

    /**
     * A picture whose output waits for its job to finish.
     *
     * @param frame the picture
     * @param layer the layer the picture belongs to
     * @param pts the presentation timestamp, assigned when the picture became due
     * @param duration the duration of the picture, in track time base units, zero or less when unknown
     */
    private record Emission(Frame frame, VideoObjectLayer layer, long pts, long duration) {
    }

    /**
     * Whether pictures are decoded concurrently as jobs.
     */
    private final boolean frameThreads;

    /**
     * The resources the jobs share.
     */
    private final VopJob.Pools pools = new VopJob.Pools();

    /**
     * The jobs that have not been waited for, in decoding order.
     */
    private final ArrayDeque<VopJob> jobs = new ArrayDeque<>();

    /**
     * The pictures of jobs, in decoding order.
     */
    private final ArrayList<Frame> frames = new ArrayList<>();

    /**
     * The pictures due for output, in display order, whose jobs may still run.
     */
    private final ArrayDeque<Emission> emissions = new ArrayDeque<>();

    /**
     * The most jobs in flight at once.
     */
    private int inFlightMax = 1;

    /**
     * The failure of an earlier picture that was found while a later one was being started, or
     * {@code null}.
     */
    private RuntimeException deferred;

    /**
     * Whether pictures of more than 8 bits keep their depth.
     */
    private final boolean highBitDepth;

    /**
     * The decoder of the active layer when it needs the object decoder, or {@code null}.
     */
    private ObjectStream object;

    /**
     * The index, in the data being scanned, of the group of VOP start code waiting for the next VOP of
     * the object decoder, or {@code -1}.
     */
    private int objectGov = -1;

    /**
     * The display width of arbitrarily shaped layers, from the track, or 0.
     */
    private final int displayWidth;

    /**
     * The display height of arbitrarily shaped layers, from the track, or 0.
     */
    private final int displayHeight;

    /**
     * The receiver of the pictures of the object decoder.
     */
    private final ObjectStream.Sink objectSink = this::emitObject;

    /**
     * The decoder of H.263 streams with the modes the macroblock engine lacks, or {@code null} while the engine
     * decodes the stream.
     */
    private H263PlusStream plus;

    /**
     * The receiver of the pictures of the H.263 annex decoder.
     */
    private final H263PlusStream.Sink plusSink = this::emitPlus;

    /**
     * The timestamp of the first picture of the H.263 annex decoder, or {@code Long.MIN_VALUE}.
     */
    private long plusBasePts = Long.MIN_VALUE;

    /**
     * The time {@link #plusBasePts} belongs to in ticks of 1/1800000 second, or {@code Long.MIN_VALUE} when the
     * first picture of the annex decoder sets it.
     */
    private long plusBaseTime = Long.MIN_VALUE;

    /**
     * Whether the H.263 stream has more than one layer or sub-bitstream, whose frames name theirs.
     */
    private boolean plusLayered;

    /**
     * The layer description of the pictures of the H.263 annex decoder.
     */
    private VideoObjectLayer plusLayer;

    /**
     * The array holding the VOP being started.
     */
    private byte[] windowData;

    /**
     * The index of the VOP's first byte in {@link #windowData}.
     */
    private int windowOffset;

    /**
     * The length of the VOP in {@link #windowData}.
     */
    private int windowLength;

    /**
     * Creates an engine.
     *
     * @param limits the resource bounds checked against every picture size
     * @param h263 whether the stream is H.263
     * @param sorenson whether the H.263 stream is Sorenson H.263, one picture per packet
     * @param threads the number of threads reconstruction may use, positive
     * @param frameThreads whether up to {@code threads} pictures are decoded concurrently on
     * the helper threads, handing pictures out up to that many pictures
     * late; H.263 and short video header pictures are always decoded
     * one at a time
     * @param trackFrameDuration the frame duration from the track, or 0
     * @param timeScale the conversion of VOP times to the track time base
     * @param displayWidth the width of the track, the display of arbitrarily shaped layers, or 0
     * @param displayHeight the height of the track, or 0
     * @param highBitDepth whether pictures of more than 8 bits keep their depth
     * @param output the receiver of pictures
     */
    public Mpeg4DecoderEngine(MediaLimits limits, boolean h263, boolean sorenson, int threads, boolean frameThreads,
                              long trackFrameDuration, TimeScale timeScale, int displayWidth, int displayHeight,
                              boolean highBitDepth, Output output) {
        this.limits = limits;
        this.highBitDepth = highBitDepth;
        this.displayWidth = displayWidth;
        this.displayHeight = displayHeight;
        this.h263 = h263;
        this.sorenson = h263 && sorenson;
        this.threads = Math.max(1, threads);
        this.frameThreads = frameThreads && !h263 && this.threads > 1;
        this.trackFrameDuration = trackFrameDuration;
        this.timeScale = timeScale;
        this.output = output;
    }

    /**
     * Returns the active layer.
     *
     * @return the layer, or {@code null} before the first header
     */
    public VideoObjectLayer layer() {
        return layer;
    }

    /**
     * Reads the out-of-band configuration of a track: the visual object sequence, visual object
     * and video object layer headers.
     *
     * @param config the configuration bytes
     * @throws MediaException.Malformed if a header is corrupt
     * @throws MediaException.Unsupported if the layer uses a tool this decoder lacks
     */
    public void configure(byte[] config) {
        scan(config, Long.MIN_VALUE, false);
    }

    /**
     * Decodes the data of one packet.
     *
     * @param data the packet payload
     * @param pts the packet's presentation timestamp, or {@code Long.MIN_VALUE}
     * @param duration the packet's duration, or
     * {@link com.github.auties00.smalt.track.Packet#UNKNOWN_DURATION Packet.UNKNOWN_DURATION}
     * @throws MediaException.Malformed if the data is corrupt; the pictures decoded before are
     * handed out first
     * @throws MediaException.Unsupported if the stream uses a tool this decoder lacks
     * @throws MediaException.LimitExceeded if a picture exceeds the limits
     */
    public void decode(byte[] data, long pts, long duration) {
        packetDuration = duration;
        damaged = false;
        scan(data, pts, true);
        if (frameThreads) {
            drain(false);
        }
        if (damaged) {
            throw new MediaException.Malformed("corrupt " + (h263 ? "H.263" : "MPEG-4") + " picture data");
        }
    }

    /**
     * Hands out the picture still held for reordering.
     */
    public void flush() {
        if (object != null) {
            object.flush(objectSink);
        }
        if (plus != null) {
            plus.flush(plusSink);
        }
        if (future != null && !futureShown) {
            futureShown = true;
            emit(future);
        }
        timestamps.clear();
        owed = 0;
        if (frameThreads) {
            drain(true);
        }
    }

    /**
     * Waits for the pictures in flight and abandons their output; the engine is not used after
     * this call.
     */
    public void close() {
        if (frameThreads) {
            finishJobs();
            for (var emission : emissions) {
                emission.frame().users().decrementAndGet();
            }
            emissions.clear();
        }
    }

    /**
     * Forgets the references and pending timestamps, as after a seek.
     */
    public void reset() {
        if (object != null) {
            object.reset();
            objectGov = -1;
        }
        if (plus != null) {
            plus.reset();
            plusBasePts = Long.MIN_VALUE;
            plusBaseTime = Long.MIN_VALUE;
        }
        if (frameThreads) {
            drain(true);
            finishJobs();
            frames.clear();
        }
        if (past != null) {
            pool.add(past);
        }
        if (future != null) {
            pool.add(future);
        }
        past = null;
        future = null;
        futureShown = true;
        timestamps.clear();
        owed = 0;
        lastPts = Long.MIN_VALUE;
    }

    /**
     * Finds the start codes of a packet and processes the headers and pictures they delimit.
     *
     * @param data the bytes
     * @param pts the packet timestamp
     * @param decode whether pictures are decoded (otherwise only headers are read)
     */
    private void scan(byte[] data, long pts, boolean decode) {
        var length = data.length;
        if (sorenson) {
            if (decode) {
                if (pts != Long.MIN_VALUE) {
                    timestamps.add(pts);
                }
                decodeShortPicture(data, 0, length, pts, 0);
            }
            return;
        }
        var vops = 0;
        var timestampAdded = false;
        var shortMode = h263 || (layer != null && layer.shortVideoHeader) || (layer == null && startsShort(data));
        if (shortMode) {
            var position = nextShortStart(data, 0);
            while (position >= 0) {
                var end = nextShortStart(data, position + 3);
                if (decode) {
                    if (!timestampAdded && pts != Long.MIN_VALUE && plus == null) {
                        timestamps.add(pts);
                        timestampAdded = true;
                    }
                    decodeShortPicture(data, position, (end < 0 ? length : end) - position, pts, vops);
                    vops++;
                }
                position = end;
            }
            return;
        }
        objectGov = -1;
        var position = nextStartCode(data, 0);
        while (position >= 0 && position + 3 < length) {
            var code = data[position + 3] & 0xFF;
            var start = position + 4;
            var next = nextStartCode(data, start);
            var end = next < 0 ? length : next;
            if (object != null && code == 0xB3) {
                objectGov = position;
            } else if (code == 0xB6) {
                if (decode) {
                    if (!timestampAdded && pts != Long.MIN_VALUE) {
                        timestamps.add(pts);
                        timestampAdded = true;
                    }
                    if (object != null) {
                        var from = objectGov >= 0 ? objectGov : position;
                        objectGov = -1;
                        if (decodeObject(data, from, end - from, vops, pts)) {
                            vops++;
                        }
                    } else if (decodeVop(data, start, end - start, vops, pts)) {
                        vops++;
                    }
                }
            } else {
                header(code, data, start, end - start);
            }
            position = next;
        }
        if (vops > 1) {
            owed += vops - 1;
        }
    }

    /**
     * Returns whether data begins, after zero bytes, with a short video header picture start code
     * rather than an MPEG-4 start code.
     *
     * @param data the bytes
     * @return {@code true} for a short video header stream
     */
    private static boolean startsShort(byte[] data) {
        var shortStart = nextShortStart(data, 0);
        if (shortStart < 0) {
            return false;
        }
        var start = nextStartCode(data, 0);
        return start < 0 || shortStart < start;
    }

    /**
     * Returns the index of the next {@code 00 00 01} start code prefix.
     *
     * @param data the bytes
     * @param from the index to search from
     * @return the index, or {@code -1}
     */
    private static int nextStartCode(byte[] data, int from) {
        var end = data.length - 2;
        var i = from;
        while (i < end) {
            if (i + 8 <= data.length) {
                var word = (long) LONG.get(data, i);
                if (((word - 0x0101010101010101L) & ~word & 0x8080808080808080L) == 0) {
                    i += 8;
                    continue;
                }
            }
            var limit = Math.min(end, i + 8);
            for (; i < limit; i++) {
                if (data[i] == 0 && data[i + 1] == 0 && data[i + 2] == 1) {
                    return i;
                }
            }
        }
        return -1;
    }

    /**
     * Returns the index of the next byte-aligned short video header or H.263 picture start code.
     *
     * @param data the bytes
     * @param from the index to search from
     * @return the index, or {@code -1}
     */
    private static int nextShortStart(byte[] data, int from) {
        for (var i = from; i + 2 < data.length; i++) {
            if (data[i] == 0 && data[i + 1] == 0 && (data[i + 2] & 0xFC) == 0x80) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Processes a header start code.
     *
     * @param code the start code value
     * @param data the bytes
     * @param offset the index of the header after the start code
     * @param length the header length
     */
    private void header(int code, byte[] data, int offset, int length) {
        if (code == 0xB0) {
            profileAndLevel = length > 0 ? data[offset] & 0xFF : 0;
        } else if (code == 0xB5) {
            readVisualObject(data, offset, length);
        } else if (code >= 0x20 && code <= 0x2F) {
            framePeriod = 0;
            var bytes = Arrays.copyOfRange(data, offset, offset + length);
            if (layer != null && layerBytes != null && Arrays.equals(bytes, layerBytes)) {
                return;
            }
            reader.load(data, offset, length);
            var parsed = VideoObjectLayer.parse(reader, profileAndLevel, visualVerid, limits, signal);
            if (parsed.objectTools) {
                activateObject(parsed, data, offset - 4, length + 4);
            } else {
                object = null;
                activate(parsed);
            }
            layerBytes = bytes;
        } else if (code == 0xB3) {
            reader.load(data, offset, length);
            var hours = reader.read(5);
            var minutes = reader.read(6);
            reader.skip(1);
            var seconds = reader.read(6);
            timeBase = hours * 3600L + minutes * 60L + seconds;
        }
    }

    /**
     * Reads a {@code VisualObject()} header's version and {@code video_signal_type()}.
     *
     * @param data the bytes
     * @param offset the index after the start code
     * @param length the header length
     */
    private void readVisualObject(byte[] data, int offset, int length) {
        reader.load(data, offset, length);
        visualVerid = 1;
        if (reader.readFlag()) {
            visualVerid = reader.read(4);
            reader.skip(3);
        }
        var type = reader.read(4);
        signal = null;
        if ((type == 1 || type == 2) && reader.readFlag()) {
            var holder = new VideoObjectLayer();
            reader.skip(3);
            holder.fullRange = reader.readFlag();
            if (reader.readFlag()) {
                holder.colorPrimaries = reader.read(8);
                holder.transferCharacteristics = reader.read(8);
                holder.matrixCoefficients = reader.read(8);
            }
            if (!reader.overrun()) {
                signal = holder;
            }
        }
    }

    /**
     * Makes a layer active, discarding the pictures of a different size.
     *
     * @param parsed the layer
     */
    private void activate(VideoObjectLayer parsed) {
        var resized = layer == null || layer.mbWidth() != parsed.mbWidth() || layer.mbHeight() != parsed.mbHeight();
        if (resized) {
            LimitChecks.checkAllocation(limits, 6 * Frame.bytes(parsed.mbWidth(), parsed.mbHeight())
                    + Macroblocks.bytes(parsed.mbWidth() * parsed.mbHeight()));
            if (future != null && !futureShown) {
                futureShown = true;
                emit(future);
            }
            if (frameThreads) {
                try {
                    drain(true);
                } finally {
                    finishJobs();
                    frames.clear();
                }
                var perJob = Frame.bytes(parsed.mbWidth(), parsed.mbHeight())
                        + Macroblocks.bytes(parsed.mbWidth() * parsed.mbHeight())
                        + 700L * parsed.mbWidth() * parsed.mbHeight();
                inFlightMax = (int) Math.max(1, Math.min(threads, limits.maxBytes() / (2 * perJob)));
            }
            pool.clear();
            past = null;
            future = null;
            bFrame = null;
        }
        layer = parsed;
        delayed = !parsed.lowDelay || h263;
        parser.configure(parsed);
    }

    /**
     * Makes a layer that needs the object decoder active.
     *
     * @param parsed the layer as the fast engine read it
     * @param data the bytes
     * @param offset the index of the layer's start code
     * @param length the length of the layer header with its start code
     */
    private void activateObject(VideoObjectLayer parsed, byte[] data, int offset, int length) {
        if (future != null && !futureShown) {
            futureShown = true;
            emit(future);
        }
        if (object != null) {
            object.flush(objectSink);
        }
        if (frameThreads) {
            try {
                drain(true);
            } finally {
                finishJobs();
                frames.clear();
            }
        }
        pool.clear();
        past = null;
        future = null;
        bFrame = null;
        object = new ObjectStream(data, offset, length, displayWidth, displayHeight, parsed.color(), highBitDepth,
                limits);
        parsed.width = object.width();
        parsed.height = object.height();
        layer = parsed;
        objectGov = -1;
    }

    /**
     * Decodes one VOP with the object decoder.
     *
     * @param data the bytes
     * @param offset the index of the VOP start code, or of the group of VOP start code before it
     * @param length the length up to the next VOP
     * @param index the number of VOPs decoded before in the same packet
     * @param pts the packet timestamp
     * @return {@code true} when the VOP was coded
     */
    private boolean decodeObject(byte[] data, int offset, int length, int index, long pts) {
        var coded = object.decode(data, offset, length, objectSink);
        if (!coded) {
            if (owed > 0) {
                owed--;
            } else {
                dropTimestamp(pts, index);
            }
        }
        return coded;
    }

    /**
     * Hands a picture of the object decoder out with its timestamp.
     *
     * @param picture the decoded VOP, valid until the call returns
     * @param time the VOP time in ticks
     * @param keyframe whether the picture comes from an intra VOP
     */
    private void emitObject(Picture picture, long time, boolean keyframe) {
        long pts;
        var polled = timestamps.poll();
        if (polled != null) {
            pts = lastPts != Long.MIN_VALUE && polled <= lastPts ? lastPts + Math.max(1, frameDuration()) : polled;
        } else if (lastPts != Long.MIN_VALUE) {
            var delta = time - lastTime;
            pts = lastPts + (delta > 0 ? timeScale.convert(delta, object.timeResolution()) : frameDuration());
        } else {
            pts = Long.MIN_VALUE;
        }
        lastPts = pts;
        lastTime = time;
        output.emitPicture(picture, layer, pts, frameDuration(), keyframe, FrameSideData.NONE);
    }

    /**
     * Returns a free picture of the active size.
     *
     * @return the picture
     */
    private Frame allocate() {
        var frame = pool.poll();
        if (frame == null) {
            frame = new Frame(layer.mbWidth(), layer.mbHeight());
        }
        return frame;
    }

    /**
     * Decodes one VOP.
     *
     * @param data the bytes
     * @param offset the index after the VOP start code
     * @param length the VOP length
     * @param index the number of VOPs decoded before in the same packet
     * @param pts the packet timestamp
     * @return {@code true} when the VOP was coded
     */
    private boolean decodeVop(byte[] data, int offset, int length, int index, long pts) {
        if (layer == null) {
            dropTimestamp(pts, index);
            return false;
        }
        reader.load(data, offset, frameThreads && !layer.shortVideoHeader ? Math.min(length, HEADER_BYTES) : length);
        vop.parse(reader, layer);
        long seconds;
        if (vop.codingType == Vop.B) {
            seconds = previousTimeBase + vop.moduloTimeBase;
        } else {
            previousTimeBase = timeBase;
            timeBase += vop.moduloTimeBase;
            seconds = timeBase;
        }
        var time = seconds * layer.timeResolution + vop.timeIncrement;
        if (!vop.coded) {
            if (owed > 0) {
                owed--;
            } else {
                dropTimestamp(pts, index);
            }
            return false;
        }
        windowData = data;
        windowOffset = offset;
        windowLength = length;
        decodePicture(vop, time, pts, index);
        windowData = null;
        return true;
    }

    /**
     * Removes a packet's timestamp from the pending timestamps when it produces no picture.
     *
     * @param pts the packet timestamp
     * @param index the number of VOPs of the packet decoded before
     */
    private void dropTimestamp(long pts, int index) {
        if (pts != Long.MIN_VALUE && index == 0) {
            timestamps.remove(pts);
        }
    }

    /**
     * Decodes a picture with the short video header or of H.263.
     *
     * @param data the bytes
     * @param offset the index of the picture start code
     * @param length the picture length
     * @param pts the packet timestamp
     * @param index the number of pictures of the packet decoded before
     */
    private void decodeShortPicture(byte[] data, int offset, int length, long pts, int index) {
        if (plus == null && h263 && !sorenson) {
            reader.load(data, offset, length);
            if (H263PlusStream.required(reader, layer == null ? 0 : layer.width, layer == null ? 0 : layer.height)) {
                startPlus(pts);
            }
        }
        if (plus != null) {
            try {
                plus.decode(data, offset, length, plusSink);
            } catch (MediaException.Malformed exception) {
                damaged = true;
            }
            return;
        }
        reader.load(data, offset, length);
        VideoObjectLayer parsed;
        try {
            parsed = sorenson ? h263Header.parseSorenson(reader, layer, limits)
                    : h263Header.parse(reader, h263, layer, limits);
        } catch (MediaException exception) {
            dropTimestamp(pts, index);
            throw exception;
        }
        if (parsed == null) {
            dropTimestamp(pts, index);
            return;
        }
        if (layer == null || layer != parsed) {
            if (layer == null || layer.width != parsed.width || layer.height != parsed.height
                    || layer.shortVideoHeader != parsed.shortVideoHeader) {
                LimitChecks.checkPicture(limits, parsed.width, parsed.height);
                activate(parsed);
            } else {
                layer = parsed;
                parser.configure(parsed);
            }
        }
        h263Header.fill(vop);
        parser.setH263(h263Header);
        layer.obmcDisable = !h263Header.advancedPrediction;
        var time = h263Header.time();
        if (h263Header.disposable) {
            decodeDisposable(time, pts, index);
        } else {
            decodePicture(vop, time, pts, index);
        }
    }

    /**
     * Decodes a Sorenson H.263 disposable inter picture: predicted from the reference like a P-picture and handed
     * out at once, after the reference when it is still held, without becoming a reference itself.
     *
     * @param time the picture time in ticks
     * @param pts the packet timestamp
     * @param index the number of pictures of the packet decoded before
     */
    private void decodeDisposable(long time, long pts, int index) {
        if (future == null) {
            dropTimestamp(pts, index);
            return;
        }
        var frame = allocate();
        frame.codingType = Vop.P;
        frame.time = time;
        frame.keyframe = false;
        run(frame, future, null, 0, 0, 0, 0);
        if (!futureShown) {
            futureShown = true;
            emit(future);
        }
        try {
            emit(frame);
        } finally {
            pool.add(frame);
        }
    }

    /**
     * Hands the stream over to the H.263 annex decoder, with the reference pictures and header state of the pictures
     * decoded so far.
     *
     * @param pts the timestamp of the packet that needs the annex decoder
     */
    private void startPlus(long pts) {
        plus = new H263PlusStream(limits);
        if (layer != null && layer.shortVideoHeader && future != null) {
            plus.adopt(h263Header.state(), past, pastTime, true, future, futureTime, futureShown, layer.width,
                    layer.height);
            if (!futureShown) {
                var first = timestamps.peek();
                if (first != null) {
                    plusBasePts = first;
                    plusBaseTime = futureTime;
                }
            }
        }
        if (plusBasePts == Long.MIN_VALUE && lastPts != Long.MIN_VALUE) {
            plusBasePts = lastPts;
            plusBaseTime = lastTime;
        } else if (plusBasePts == Long.MIN_VALUE && pts != Long.MIN_VALUE) {
            plusBasePts = pts;
        }
        if (past != null) {
            pool.add(past);
        }
        if (future != null) {
            pool.add(future);
        }
        past = null;
        future = null;
        futureShown = true;
        timestamps.clear();
        owed = 0;
    }

    /**
     * Hands out a picture of the H.263 annex decoder, timed from its temporal reference.
     *
     * @param picture the decoded picture, valid until the call returns
     * @param time the picture time in ticks of 1/1800000 second
     * @param keyframe whether the picture is an I- or EI-picture
     * @param number the enhancement layer number
     * @param subBitstream the sub-bitstream
     */
    private void emitPlus(YuvPicture picture, long time, boolean keyframe, int number, int subBitstream) {
        if (plusBaseTime == Long.MIN_VALUE) {
            plusBaseTime = time;
            if (plusBasePts == Long.MIN_VALUE) {
                plusBasePts = 0;
            }
        }
        var delta = time - plusBaseTime;
        var pts = plusBasePts + (delta >= 0 ? timeScale.convert(delta, 1800000)
                : -timeScale.convert(-delta, 1800000));
        if (number > 1 || subBitstream > 0) {
            plusLayered = true;
        }
        var sideData = plusLayered ? FrameSideData.builder().layer(new VideoLayer(number - 1, subBitstream,
                VideoLayer.Type.PRIMARY)).build() : FrameSideData.NONE;
        if (plusLayer == null || plusLayer.width != picture.width() || plusLayer.height != picture.height()) {
            plusLayer = VideoObjectLayer.shortHeader(picture.width(), picture.height());
            plusLayer.timeResolution = 1800000;
        }
        lastPts = pts;
        lastTime = time;
        output.emitPicture(picture, plusLayer, pts, frameDuration(), keyframe, sideData);
    }

    /**
     * Decodes a coded picture, updates the references and hands out the pictures that became due.
     *
     * @param vop the header
     * @param time the picture time in ticks
     * @param pts the packet timestamp
     * @param index the number of pictures of the packet decoded before
     */
    private void decodePicture(Vop vop, long time, long pts, int index) {
        if (frameThreads && windowData != null && !layer.shortVideoHeader) {
            decodePictureConcurrently(vop, time, pts, index);
            return;
        }
        var type = vop.codingType;
        if (type == Vop.B) {
            if (past == null || future == null) {
                dropTimestamp(pts, index);
                return;
            }
            if (!delayed) {
                delayed = true;
            }
            if (bFrame == null) {
                bFrame = new Frame(layer.mbWidth(), layer.mbHeight());
            }
            var trd = Math.max(1, futureTime - pastTime);
            var trb = Math.min(Math.max(1, time - pastTime), trd);
            if (framePeriod <= 0) {
                framePeriod = Math.max(1, time - pastTime);
            }
            var frame = bFrame;
            frame.codingType = Vop.B;
            frame.time = time;
            frame.keyframe = false;
            run(frame, past, future, trb, trd, framesBetween(pastTime, time), framesBetween(pastTime, futureTime));
            emit(frame);
            return;
        }
        if (type != Vop.I && future == null) {
            dropTimestamp(pts, index);
            return;
        }
        var frame = allocate();
        frame.codingType = type;
        frame.time = time;
        frame.keyframe = type == Vop.I;
        run(frame, future, null, 0, 0, 0, 0);
        frame.extendEdges(layer.mbWidth() * 16, layer.mbHeight() * 16);
        if (past != null) {
            pool.add(past);
        }
        past = future;
        pastTime = futureTime;
        var held = future;
        var heldShown = futureShown;
        future = frame;
        futureTime = time;
        futureShown = false;
        if (held != null && !heldShown) {
            emit(held);
        }
        if (!delayed) {
            futureShown = true;
            emit(frame);
        }
    }

    /**
     * Starts the decoding of a coded picture as a job, updates the references and queues the
     * pictures that became due, like {@link #decodePicture} does for a picture decoded at once.
     *
     * @param vop the header
     * @param time the picture time in ticks
     * @param pts the packet timestamp
     * @param index the number of pictures of the packet decoded before
     */
    private void decodePictureConcurrently(Vop vop, long time, long pts, int index) {
        var type = vop.codingType;
        if (type == Vop.B) {
            if (past == null || future == null) {
                dropTimestamp(pts, index);
                return;
            }
            if (!delayed) {
                delayed = true;
            }
            var trd = Math.max(1, futureTime - pastTime);
            var trb = Math.min(Math.max(1, time - pastTime), trd);
            if (framePeriod <= 0) {
                framePeriod = Math.max(1, time - pastTime);
            }
            var frame = allocateConcurrent();
            frame.codingType = Vop.B;
            frame.time = time;
            frame.keyframe = false;
            start(frame, past, future, vop, trb, trd, framesBetween(pastTime, time),
                    framesBetween(pastTime, futureTime));
            emit(frame);
            return;
        }
        if (type != Vop.I && future == null) {
            dropTimestamp(pts, index);
            return;
        }
        var frame = allocateConcurrent();
        frame.codingType = type;
        frame.time = time;
        frame.keyframe = type == Vop.I;
        start(frame, future, null, vop, 0, 0, 0, 0);
        past = future;
        pastTime = futureTime;
        var held = future;
        var heldShown = futureShown;
        future = frame;
        futureTime = time;
        futureShown = false;
        if (held != null && !heldShown) {
            emit(held);
        }
        if (!delayed) {
            futureShown = true;
            emit(frame);
        }
    }

    /**
     * Starts the job of a picture, first waiting for the oldest job when the most allowed are in
     * flight.
     *
     * @param frame the picture to decode into
     * @param forward the forward reference, or {@code null}
     * @param backward the backward reference, or {@code null}
     * @param vop the header
     * @param trb the temporal distance from the past to a B-VOP
     * @param trd the temporal distance from the past to the future picture
     * @param framesB the distance from the past to a B-VOP in frame periods
     * @param framesD the distance from the past to the future picture in frame periods
     */
    private void start(Frame frame, Frame forward, Frame backward, Vop vop, long trb, long trd, int framesB,
                       int framesD) {
        while (!jobs.isEmpty() && jobs.peekFirst().finished()) {
            jobs.pollFirst().join();
        }
        while (jobs.size() >= inFlightMax) {
            jobs.pollFirst().join();
        }
        var job = new VopJob(frame, forward, backward, vop, layer, windowData, windowOffset, windowLength, trb, trd,
                framesB, framesD, threads, pools);
        frame.job = job;
        jobs.addLast(job);
        job.start();
    }

    /**
     * Returns a picture no job and no pending output uses and that is not a reference, waiting
     * for jobs and outputs to finish when every picture is in use.
     *
     * @return the picture
     */
    private Frame allocateConcurrent() {
        while (true) {
            for (var candidate : frames) {
                if (candidate.users().get() == 0 && candidate != past && candidate != future) {
                    return candidate;
                }
            }
            if (frames.size() < inFlightMax + 4 || (emissions.isEmpty() && jobs.isEmpty())) {
                var frame = new Frame(layer.mbWidth(), layer.mbHeight());
                frames.add(frame);
                return frame;
            }
            if (!emissions.isEmpty()) {
                try {
                    drainHead();
                } catch (RuntimeException failure) {
                    defer(failure);
                }
            } else {
                jobs.pollFirst().join();
            }
        }
    }

    /**
     * Keeps a failure of an earlier picture to throw when the call in progress ends, so that it
     * does not cut short the decoding of the packet being processed.
     *
     * @param failure the failure of an earlier picture
     */
    private void defer(RuntimeException failure) {
        if (deferred == null) {
            deferred = failure;
        } else {
            deferred.addSuppressed(failure);
        }
    }

    /**
     * Hands out the due pictures whose jobs finished, or all of them.
     *
     * @param all whether to wait for the jobs still running
     * @throws MediaException.Malformed if a job failed or a picture was damaged; the other due
     * pictures are handed out first
     */
    private void drain(boolean all) {
        RuntimeException error = deferred;
        deferred = null;
        while (!emissions.isEmpty() && (all || emissions.peekFirst().frame().job.finished())) {
            try {
                drainHead();
            } catch (RuntimeException failure) {
                if (error == null) {
                    error = failure;
                } else {
                    error.addSuppressed(failure);
                }
            }
        }
        if (error != null) {
            throw error;
        }
    }

    /**
     * Waits for the job of the picture due next and hands the picture out.
     *
     * @throws MediaException.Malformed if the job failed or the picture was damaged
     */
    private void drainHead() {
        var emission = emissions.pollFirst();
        var frame = emission.frame();
        try {
            await(frame.job);
            var failure = frame.job.failure();
            if (failure != null) {
                if (failure instanceof MediaException exception) {
                    throw exception;
                }
                throw new MediaException.Malformed("corrupt " + (h263 ? "H.263" : "MPEG-4") + " picture data", failure);
            }
            output.emit(frame, emission.layer(), emission.pts(), emission.duration());
            if (frame.damaged) {
                throw new MediaException.Malformed("corrupt " + (h263 ? "H.263" : "MPEG-4") + " picture data");
            }
        } finally {
            frame.users().decrementAndGet();
        }
    }

    /**
     * Waits for a job, first waiting for every job started before it, which it may wait for: a
     * job that no helper runs is run by the thread that waits for it, and runs a reference
     * picture's job before the pictures that read it.
     *
     * @param job the decoding of a picture
     */
    private void await(VopJob job) {
        if (jobs.contains(job)) {
            VopJob next;
            do {
                next = jobs.pollFirst();
                next.join();
            } while (next != job);
        } else {
            job.join();
        }
    }

    /**
     * Waits for every job that was started.
     */
    private void finishJobs() {
        while (!jobs.isEmpty()) {
            jobs.pollFirst().join();
        }
    }

    /**
     * Returns the number of frame periods between two VOP times,
     * {@code T(later) // Tframe - T(earlier) // Tframe} (7.7.2.2).
     *
     * @param earlier the earlier time in ticks
     * @param later the later time in ticks
     * @return the distance in frame periods
     */
    private int framesBetween(long earlier, long later) {
        return (int) (frameNumber(later, framePeriod) - frameNumber(earlier, framePeriod));
    }

    /**
     * Returns {@code time // period}: the quotient rounded to nearest, halves away from zero.
     *
     * @param time the time in ticks, at least 0
     * @param period the frame period in ticks, at least 1
     * @return the quotient
     */
    static long frameNumber(long time, long period) {
        return (time + period / 2) / period;
    }

    /**
     * Parses and reconstructs a picture.
     *
     * @param frame the destination
     * @param forward the forward reference, or {@code null}
     * @param backward the backward reference, or {@code null}
     * @param trb the B-VOP distance to the forward reference
     * @param trd the B-VOP distance between the references
     * @param framesB the B-VOP distance to the forward reference in frame periods
     * @param framesD the B-VOP distance between the references in frame periods
     */
    private void run(Frame frame, Frame forward, Frame backward, long trb, long trd, int framesB, int framesD) {
        reader.seek(vop.dataPosition);
        var corrupt = parser.parse(reader, vop, frame, backward, trb, trd, framesB, framesD);
        frame.damaged = corrupt;
        if (corrupt) {
            damaged = true;
        }
        var dsp = Mpeg4Dsp.select();
        var blocks = parser.blocks();
        var gmc = vop.codingType == Vop.S ? parser.globalMotion() : null;
        var rows = frame.mbHeight;
        var bands = Reconstructor.bandCount(threads, rows, (long) rows * 16 * frame.mbWidth * 16);
        if (reconstructors.length < bands) {
            var grown = Arrays.copyOf(reconstructors, bands);
            for (var i = reconstructors.length; i < bands; i++) {
                grown[i] = new Reconstructor();
            }
            reconstructors = grown;
        }
        var rounding = vop.roundingType;
        if (bands <= 1) {
            reconstructors[0].reconstruct(blocks, dsp, frame, forward, backward, gmc, layer, rounding, 0, rows);
        } else {
            var workers = reconstructors;
            ParallelBands.run(bands, band -> workers[band].reconstruct(blocks, dsp, frame, forward, backward, gmc,
                    layer, rounding, ParallelBands.start(band, bands, rows), ParallelBands.start(band + 1, bands, rows)));
        }
        if (layer.shortVideoHeader) {
            h263Header.postProcess(frame, blocks, layer);
        }
    }

    /**
     * Hands a picture out with its timestamp.
     *
     * @param frame the picture
     */
    private void emit(Frame frame) {
        long pts;
        var polled = timestamps.poll();
        if (polled != null) {
            pts = lastPts != Long.MIN_VALUE && polled <= lastPts ? lastPts + Math.max(1, frameDuration()) : polled;
        } else if (lastPts != Long.MIN_VALUE) {
            var delta = frame.time - lastTime;
            pts = lastPts + (delta > 0 ? timeScale.convert(delta, layer.timeResolution) : frameDuration());
        } else {
            pts = Long.MIN_VALUE;
        }
        lastPts = pts;
        lastTime = frame.time;
        if (frameThreads && frame.job != null) {
            frame.users().incrementAndGet();
            emissions.addLast(new Emission(frame, layer, pts, frameDuration()));
            return;
        }
        output.emit(frame, layer, pts, frameDuration());
    }

    /**
     * Returns the nominal frame duration in the track time base.
     *
     * @return the duration, or 0
     */
    private long frameDuration() {
        if (packetDuration > 0) {
            return packetDuration;
        }
        if (trackFrameDuration > 0) {
            return trackFrameDuration;
        }
        if (layer != null && layer.fixedTimeIncrement > 0) {
            return Math.max(0, timeScale.convert(layer.fixedTimeIncrement, layer.timeResolution));
        }
        return 0;
    }
}
