package com.github.auties00.smalt.container.ts;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.PacketFlag;
import com.github.auties00.smalt.track.PacketSideData;

import java.util.Set;

/**
 * Splits a self-synchronizing audio stream (ADTS, LATM, MPEG audio, AC-3, Opus) into frames.
 *
 * <p>The stream is located by a frame header whose successor is another valid header, or the end
 * of the input; after garbage or lost transport packets the parser resynchronizes the same way.
 * Timestamps are in samples of the track's rate: each frame takes the time of the PES packet it
 * starts in when that packet carries a timestamp no earlier frame took, and otherwise follows the
 * previous frame. PES times are converted to samples as an offset from the first PES time of the
 * stream, so that a frame gets the same timestamp whether the read started at the beginning of
 * the file or after a seek, and a PES time within half a frame of where the previous frame ends
 * is taken as that end, so the jitter of 90 kHz rounding does not reach the packets; a farther
 * one (lost frames, a splice) moves the timeline. Frames before the first timestamp of the
 * stream are dropped. A parser that meets a new stream configuration announces it on the next
 * packet as its {@link PacketSideData#newCodecConfig() new codec configuration}, and converts the
 * durations of frames at another sampling rate to the track's. Instances are not thread-safe.
 */
abstract class FrameParser extends ElementaryParser {
    /**
     * The largest number of buffered bytes: no frame of these formats comes close.
     */
    private static final int CAPACITY = 1 << 20;

    /**
     * The flags of an intact audio frame.
     */
    private static final Set<PacketFlag> KEY = Set.of(PacketFlag.KEY);

    /**
     * The flags of an audio frame a lost transport packet may have damaged.
     */
    private static final Set<PacketFlag> KEY_CORRUPT = Set.of(PacketFlag.KEY, PacketFlag.CORRUPT);

    /**
     * The buffered stream.
     */
    private final StreamBuffer buffer = new StreamBuffer(CAPACITY);

    /**
     * The time at which the previous frame ends, in samples, or {@link TsClock#NONE}.
     */
    private long next = TsClock.NONE;

    /**
     * Whether the parser is synchronized on the frame stream.
     */
    private boolean synced;

    /**
     * Whether the input ended, so that no more bytes follow the buffered ones.
     */
    private boolean flushing;

    /**
     * The sampling rate of the timestamps, set when the track is configured.
     */
    int timestampRate;

    /**
     * The first PES time of the stream, in absolute 90 kHz ticks (before the clock's origin is
     * subtracted), from which PES times are measured, or {@link TsClock#NONE} before the first.
     */
    private long baseTicks = TsClock.NONE;

    /**
     * The origin of the clock of the current read pass, in 90 kHz ticks.
     */
    private long origin;

    /**
     * The PES time of the frame being emitted, in 90 kHz ticks, until its first unit takes it,
     * or {@link TsClock#NONE}.
     */
    private long pendingAnchor = TsClock.NONE;

    /**
     * The sink of the frame being emitted, or {@code null}.
     */
    private UnitSink currentSink;

    /**
     * The file offset of the PES packet holding the frame being emitted.
     */
    private long currentOffset;

    /**
     * Whether a damaged PES packet contributed to the frame being emitted.
     */
    private boolean currentCorrupt;

    /**
     * The output through which {@link #frame(byte[], int, int, boolean, FrameOutput)} reports
     * units.
     */
    private final FrameOutput output = this::unit;

    /**
     * The codec configuration the next emitted packet establishes, or {@code null}.
     */
    private byte[] announced;

    /**
     * The remainder of converting durations at a rate other than {@link #timestampRate}, in
     * units of one over that rate times the timestamp rate.
     */
    private long rateRemainder;

    /**
     * Constructs a parser.
     *
     * @param entry the program map entry of the stream
     * @param limits the resource bounds
     */
    FrameParser(Psi.StreamEntry entry, MediaLimits limits) {
        super(entry, limits);
    }

    /**
     * Receives the units decoded from one frame.
     */
    interface FrameOutput {
        /**
         * Receives one unit.
         *
         * @param payload the packet payload
         * @param samples the duration in samples of the timestamp rate
         * @param duration the presentation duration in samples, at most {@code samples}
         */
        void emit(byte[] payload, int samples, int duration);
    }

    /**
     * Returns the length of the frame starting at an index.
     *
     * @param data the bytes
     * @param offset the index of the candidate header
     * @param available the number of bytes available from {@code offset}
     * @return the frame length, header included; {@code 0} when more bytes are needed to tell;
     * {@code -1} when no valid frame of this stream starts there
     */
    abstract int frameLength(byte[] data, int offset, int available);

    /**
     * Returns whether a byte can start a frame header.
     *
     * @param value the byte
     * @return {@code true} when a header may start with it
     */
    abstract boolean isSyncByte(byte value);

    /**
     * Decodes a frame into packet payloads, configuring the track from the first frame.
     *
     * @param data the bytes
     * @param offset the index of the frame
     * @param length the frame length
     * @param first whether this is the first frame since the stream was located
     * @param output receives the units
     */
    abstract void frame(byte[] data, int offset, int length, boolean first, FrameOutput output);

    /**
     * Makes the next packet emitted carry a new codec configuration, which replaces the track's
     * from that packet on.
     *
     * @param config the configuration
     */
    final void announce(byte[] config) {
        announced = config;
    }

    /**
     * Converts a duration in samples of a stream rate to samples of the timestamp rate, carrying
     * the rounding remainder over to the next conversion so that durations add up exactly.
     *
     * @param samples the duration at {@code rate}
     * @param rate the sampling rate of the frame, after a configuration change the stream's new
     * rate
     * @return the duration at the timestamp rate
     */
    final int toTimestampRate(int samples, int rate) {
        if (rate == timestampRate || rate <= 0 || timestampRate <= 0) {
            return samples;
        }
        var scaled = (long) samples * timestampRate + rateRemainder;
        rateRemainder = scaled % rate;
        return (int) (scaled / rate);
    }

    /**
     * Returns whether the input ended, so that a frame whose length depends on what follows it
     * can be measured on the buffered bytes alone.
     *
     * @return {@code true} while the buffered bytes are being flushed
     */
    final boolean flushing() {
        return flushing;
    }

    /**
     * Copies the configured track and the timing base of this parser into a fresh parser.
     *
     * @param <T> the parser type
     * @param fresh the fresh parser
     * @return {@code fresh}
     */
    final <T extends FrameParser> T inheritTiming(T fresh) {
        fresh.track = track;
        fresh.timestampRate = timestampRate;
        ((FrameParser) fresh).baseTicks = baseTicks;
        return fresh;
    }

    @Override
    final void accept(Pes pes, TsClock clock, UnitSink sink) {
        origin = clock.origin();
        var pts = pes.pts() == TsClock.NONE ? TsClock.NONE : clock.ticks(pid(), pes.pts(), true);
        if (buffer.append(pes, pts, TsClock.NONE)) {
            synced = false;
        }
        process(sink, false);
    }

    @Override
    final void flush(TsClock clock, UnitSink sink) {
        process(sink, true);
    }

    /**
     * Splits the buffered bytes into frames.
     *
     * @param sink receives the packets
     * @param flushing whether the input ended, so that an incomplete frame is discarded and a frame
     * at the end needs no successor
     */
    private void process(UnitSink sink, boolean flushing) {
        this.flushing = flushing;
        while (buffer.available() > 0) {
            var data = buffer.array();
            var start = buffer.start();
            var available = buffer.available();
            if (!isSyncByte(data[start])) {
                skipToSync();
                synced = false;
                continue;
            }
            var length = frameLength(data, start, available);
            if (length == 0 || (length > 0 && length > available)) {
                if (flushing) {
                    buffer.skip(available);
                }
                return;
            }
            if (length < 0) {
                buffer.skip(1);
                synced = false;
                continue;
            }
            var first = !synced;
            if (!synced) {
                var following = length < available ? frameLength(data, start + length, available - length) : 0;
                if (following == 0 && !flushing) {
                    return;
                }
                if (following < 0) {
                    buffer.skip(1);
                    continue;
                }
                synced = true;
            }
            emit(data, start, length, first, sink);
            buffer.skip(length);
        }
    }

    /**
     * Discards bytes up to the next one that can start a header.
     */
    private void skipToSync() {
        var data = buffer.array();
        var start = buffer.start();
        var available = buffer.available();
        var index = 1;
        while (index < available && !isSyncByte(data[start + index])) {
            index++;
        }
        buffer.skip(index);
    }

    /**
     * Turns a frame into packets with timestamps.
     *
     * @param data the bytes
     * @param start the index of the frame
     * @param length the frame length
     * @param first whether this is the first frame since the stream was located
     * @param sink receives the packets
     */
    private void emit(byte[] data, int start, int length, boolean first, UnitSink sink) {
        var position = buffer.position();
        var marker = buffer.markerAt(position);
        if (marker == null) {
            return;
        }
        pendingAnchor = TsClock.NONE;
        if (marker.hasTimestamps()) {
            marker.taken = true;
            pendingAnchor = marker.pts;
        }
        currentSink = sink;
        currentOffset = marker.fileOffset;
        currentCorrupt = buffer.corrupt(position, position + length);
        frame(data, start, length, first, output);
        currentSink = null;
    }

    /**
     * Stamps one unit of the frame being emitted and passes it to the sink.
     *
     * @param payload the packet payload
     * @param samples the duration in samples of the timestamp rate
     * @param duration the presentation duration in samples
     */
    private void unit(byte[] payload, int samples, int duration) {
        if (timestampRate <= 0) {
            return;
        }
        var timestamp = next;
        if (pendingAnchor != TsClock.NONE) {
            if (baseTicks == TsClock.NONE) {
                baseTicks = pendingAnchor + origin;
            }
            var base = baseTicks - origin;
            var anchored = Math.floorDiv(base * timestampRate + 45_000, 90_000)
                    + Math.floorDiv((pendingAnchor - base) * timestampRate + 45_000, 90_000);
            pendingAnchor = TsClock.NONE;
            if (next == TsClock.NONE || Math.abs(anchored - next) > samples / 2) {
                timestamp = anchored;
            }
        }
        if (timestamp == TsClock.NONE) {
            return;
        }
        next = timestamp + samples;
        if (track != null && currentSink != null) {
            var sideData = announced == null ? PacketSideData.NONE
                    : PacketSideData.builder().newCodecConfig(announced).build();
            announced = null;
            currentSink.accept(new Packet(pid(), payload, timestamp, timestamp, Math.max(0, Math.min(samples, duration)),
                    currentCorrupt ? KEY_CORRUPT : KEY, sideData), currentOffset);
        }
    }
}
