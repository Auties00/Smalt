package com.github.auties00.smalt.container.ts;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.internal.io.ReadAheadCache;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.io.MediaSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * Reads the transport packets of a stream in order from an offset, reassembles the PES packets of
 * the elementary PIDs it is given and feeds them to their parsers, and the sections of the PSI
 * PIDs it watches to a listener.
 *
 * <p>Transport packets are {@link Geometry#packetSize()} bytes apart, with the sync byte
 * {@code 0x47} at {@link Geometry#syncOffset()}. When a packet lacks its sync byte the scanner
 * searches forward for three consecutive aligned sync bytes and continues there, flagging every
 * PES packet in progress as damaged. A packet with the transport error indicator, a scrambled
 * payload, or a continuity counter gap (ISO/IEC 13818-1 clause 2.4.3.3; a repeated counter is a
 * duplicate packet and is dropped) also flags the PES packet it belongs to. Instances are not
 * thread-safe.
 */
final class TsScanner {
    /**
     * The transport packet sync byte.
     */
    static final int SYNC = 0x47;

    /**
     * The size of a transport packet without a prefix or suffix.
     */
    static final int PACKET = 188;

    /**
     * The size of the window searched for sync bytes at a time while resynchronizing.
     */
    private static final int RESYNC_WINDOW = 64 * 1024;

    /**
     * The largest PES packet reassembled; a longer one is cut and flagged as damaged.
     */
    private static final int MAX_PES = 64 << 20;

    /**
     * Describes how transport packets are laid out in the file.
     *
     * @param packetSize the distance between packets: 188 bytes, 192 for M2TS (a four-byte timecode
     * before each packet) or 204 (sixteen bytes of Reed-Solomon parity after each packet)
     * @param syncOffset the index of the sync byte within a packet's {@code packetSize} bytes
     * @param first the offset of the first packet
     */
    record Geometry(int packetSize, int syncOffset, long first) {
    }

    /**
     * The input.
     */
    private final MediaSource source;

    /**
     * The read-ahead window over the input.
     */
    private final ReadAheadCache cache;

    /**
     * The packet layout.
     */
    private final Geometry geometry;

    /**
     * The offset at which the scan ends.
     */
    private final long end;

    /**
     * The resource bounds.
     */
    private final MediaLimits limits;

    /**
     * The timeline.
     */
    private final TsClock clock;

    /**
     * The reassembly state of each elementary PID, in the order the PIDs were added.
     */
    private final List<PidState> pids = new ArrayList<>();

    /**
     * The reassembly state of each elementary PID, indexed by PID, {@code null} for the PIDs that
     * are not parsed.
     */
    private final PidState[] byPid = new PidState[0x2000];

    /**
     * The section reassembly state of each watched PSI PID.
     */
    private final Map<Integer, SectionState> sections = new HashMap<>();

    /**
     * Receives the sections of the watched PIDs, or {@code null}.
     */
    private BiConsumer<Integer, byte[]> sectionListener;

    /**
     * The bytes of the current packet.
     */
    private final byte[] packet;

    /**
     * The offset of the next packet.
     */
    private long position;

    /**
     * Constructs a scanner.
     *
     * @param source the input
     * @param geometry the packet layout
     * @param start the offset of the first packet read; a packet boundary
     * @param end the offset at which the scan ends, or {@link Long#MAX_VALUE}
     * @param limits the resource bounds
     * @param clock the timeline
     */
    TsScanner(MediaSource source, Geometry geometry, long start, long end, MediaLimits limits, TsClock clock) {
        this.source = source;
        this.geometry = geometry;
        this.end = end;
        this.limits = limits;
        this.clock = clock;
        this.cache = new ReadAheadCache(source, 256 * 1024, 4 * 1024);
        this.packet = new byte[geometry.packetSize()];
        this.position = start;
    }

    /**
     * {@return the offset of the next packet}
     */
    long position() {
        return position;
    }

    /**
     * Returns the timeline.
     *
     * @return the clock
     */
    TsClock clock() {
        return clock;
    }

    /**
     * Sets the parser of an elementary PID.
     *
     * @param parser the parser; its PID is the one parsed
     */
    void parse(ElementaryParser parser) {
        var state = new PidState(parser);
        if (byPid[parser.pid()] != null) {
            pids.remove(byPid[parser.pid()]);
        }
        byPid[parser.pid()] = state;
        pids.add(state);
    }

    /**
     * Starts collecting the sections of a PSI PID.
     *
     * @param pid the PID
     * @param listener receives every complete section of every watched PID
     */
    void watch(int pid, BiConsumer<Integer, byte[]> listener) {
        sections.putIfAbsent(pid, new SectionState());
        this.sectionListener = listener;
    }

    /**
     * Reads and processes the next transport packet.
     *
     * @param sink receives the packets the parsers complete
     * @return {@code false} at the end of the scanned range
     */
    boolean step(ElementaryParser.UnitSink sink) {
        while (true) {
            var size = geometry.packetSize();
            if (position > end - size) {
                return false;
            }
            var read = cache.readUpTo(position, packet, 0, size);
            if (read < size) {
                return false;
            }
            var header = geometry.syncOffset();
            if ((packet[header] & 0xFF) != SYNC) {
                var next = resynchronize(position + 1);
                if (next < 0) {
                    position = Math.max(position, end);
                    return false;
                }
                interruptAll();
                position = next;
                continue;
            }
            var offset = position;
            position += size;
            process(header, offset, sink);
            return true;
        }
    }

    /**
     * Completes every PES packet in progress and flushes the parsers, at the end of the input.
     *
     * @param sink receives the packets the parsers complete
     */
    void finish(ElementaryParser.UnitSink sink) {
        for (var state : pids) {
            if (state.active) {
                deliver(state, sink);
            }
        }
        for (var state : pids) {
            state.parser.flush(clock, sink);
        }
    }

    /**
     * Processes one transport packet.
     *
     * @param header the index of the sync byte in {@link #packet}
     * @param offset the file offset of the packet
     * @param sink receives the packets the parsers complete
     */
    private void process(int header, long offset, ElementaryParser.UnitSink sink) {
        var b1 = packet[header + 1] & 0xFF;
        var pid = (b1 & 0x1F) << 8 | (packet[header + 2] & 0xFF);
        var state = byPid[pid];
        var section = state == null && !sections.isEmpty() ? sections.get(pid) : null;
        if (state == null && section == null) {
            return;
        }
        var error = (b1 & 0x80) != 0;
        var unitStart = (b1 & 0x40) != 0;
        var b3 = packet[header + 3] & 0xFF;
        var scrambled = (b3 >> 6) != 0;
        var control = (b3 >> 4) & 3;
        var counter = b3 & 0x0F;
        var payload = header + 4;
        var limit = header + PACKET;
        var discontinuity = false;
        var randomAccess = false;
        if (control == 0) {
            return;
        }
        if ((control & 2) != 0) {
            var length = packet[payload] & 0xFF;
            if (payload + 1 + length > limit) {
                error = true;
            } else if (length > 0) {
                var flags = packet[payload + 1] & 0xFF;
                discontinuity = (flags & 0x80) != 0;
                randomAccess = (flags & 0x40) != 0;
            }
            payload += 1 + length;
        }
        var hasPayload = (control & 1) != 0 && payload < limit;
        if (state != null) {
            elementary(state, offset, error, unitStart, scrambled, counter, (control & 1) != 0, discontinuity,
                    randomAccess, hasPayload ? payload : limit, limit, sink);
        } else if (!error && hasPayload) {
            var continuous = section.counter < 0 || discontinuity || counter == ((section.counter + 1) & 0x0F)
                    || counter == section.counter;
            if (counter == section.counter && !unitStart && section.counter >= 0) {
                return;
            }
            section.counter = counter;
            section.assembler.accept(packet, payload, limit, unitStart, continuous,
                    bytes -> sectionListener.accept(pid, bytes));
        }
    }

    /**
     * Processes a transport packet of an elementary PID.
     *
     * @param state the PID state
     * @param offset the file offset of the packet
     * @param error whether the packet is damaged
     * @param unitStart whether a PES packet starts in it
     * @param scrambled whether the payload is scrambled
     * @param counter the continuity counter
     * @param counted whether the packet carries a payload, so its counter increments
     * @param discontinuity whether the adaptation field flags a discontinuity
     * @param randomAccess whether the adaptation field flags a random access point
     * @param from the index of the payload
     * @param to the index after the payload
     * @param sink receives the packets the parsers complete
     */
    private void elementary(PidState state, long offset, boolean error, boolean unitStart, boolean scrambled,
                            int counter, boolean counted, boolean discontinuity, boolean randomAccess, int from,
                            int to, ElementaryParser.UnitSink sink) {
        if (error) {
            state.damaged = true;
            return;
        }
        if (counted) {
            if (state.counter >= 0 && !discontinuity) {
                if (counter == state.counter) {
                    return;
                }
                if (counter != ((state.counter + 1) & 0x0F)) {
                    state.damaged = true;
                }
            }
            state.counter = counter;
        }
        if (from >= to) {
            return;
        }
        if (unitStart) {
            if (state.active) {
                deliver(state, sink);
            }
            state.active = !scrambled;
            state.length = 0;
            state.offset = offset;
            state.randomAccess = randomAccess;
            state.corrupt = state.damaged;
            state.damaged = false;
        } else if (state.damaged && state.active) {
            state.corrupt = true;
            state.damaged = false;
        }
        if (!state.active) {
            return;
        }
        if (scrambled) {
            state.active = false;
            return;
        }
        append(state, from, to);
        if (state.length >= 6) {
            var declared = (state.data[4] & 0xFF) << 8 | (state.data[5] & 0xFF);
            if (declared > 0 && state.length >= 6 + declared) {
                state.length = 6 + declared;
                deliver(state, sink);
            }
        }
    }

    /**
     * Appends payload bytes to the PES packet being reassembled.
     *
     * @param state the PID state
     * @param from the index of the first payload byte in {@link #packet}
     * @param to the index after the payload
     */
    private void append(PidState state, int from, int to) {
        var count = to - from;
        if (state.length + count > MAX_PES || state.length + count > limits.maxBytes()) {
            state.corrupt = true;
            return;
        }
        if (state.length + count > state.data.length) {
            var grown = Math.max(state.data.length * 2L, state.length + count);
            grown = Math.min(grown, MAX_PES);
            LimitChecks.checkAllocation(limits, grown);
            state.data = Arrays.copyOf(state.data, (int) grown);
        }
        System.arraycopy(packet, from, state.data, state.length, count);
        state.length += count;
    }

    /**
     * Parses the header of the reassembled PES packet (ISO/IEC 13818-1 clause 2.4.3.6) and passes
     * it to the parser.
     *
     * @param state the PID state
     * @param sink receives the packets the parser completes
     */
    private void deliver(PidState state, ElementaryParser.UnitSink sink) {
        state.active = false;
        var data = state.data;
        var length = state.length;
        if (length < 6 || data[0] != 0 || data[1] != 0 || data[2] != 1) {
            return;
        }
        var streamId = data[3] & 0xFF;
        var payload = 6;
        var pts = TsClock.NONE;
        var dts = TsClock.NONE;
        if (hasOptionalHeader(streamId)) {
            if (length < 9 || (data[6] & 0xC0) != 0x80) {
                return;
            }
            var flags = (data[7] & 0xFF) >> 6;
            var headerLength = data[8] & 0xFF;
            payload = 9 + headerLength;
            if (payload > length) {
                return;
            }
            if (flags >= 2 && headerLength >= 5) {
                pts = timestamp(data, 9);
            }
            if (flags == 3 && headerLength >= 10) {
                dts = timestamp(data, 14);
            }
        }
        var declared = (data[4] & 0xFF) << 8 | (data[5] & 0xFF);
        var payloadEnd = declared > 0 ? Math.min(length, 6 + declared) : length;
        if (payloadEnd < payload) {
            return;
        }
        state.parser.accept(new ElementaryParser.Pes(data, payload, payloadEnd - payload, pts, dts, state.offset,
                state.corrupt, state.randomAccess), clock, sink);
    }

    /**
     * Returns whether a PES packet of a stream id carries the optional PES header.
     *
     * @param streamId the {@code stream_id}
     * @return {@code false} for the program stream map, padding, private stream 2, ECM, EMM,
     * DSM-CC, H.222.1 type E and directory streams
     */
    private static boolean hasOptionalHeader(int streamId) {
        return switch (streamId) {
            case 0xBC, 0xBE, 0xBF, 0xF0, 0xF1, 0xF2, 0xF8, 0xFF -> false;
            default -> true;
        };
    }

    /**
     * Reads a 33-bit PES timestamp.
     *
     * @param data the bytes
     * @param offset the index of the five timestamp bytes
     * @return the timestamp
     */
    static long timestamp(byte[] data, int offset) {
        return ((long) ((data[offset] >> 1) & 0x07) << 30) | ((data[offset + 1] & 0xFFL) << 22)
                | (((data[offset + 2] & 0xFFL) >> 1) << 15) | ((data[offset + 3] & 0xFFL) << 7)
                | ((data[offset + 4] & 0xFFL) >> 1);
    }

    /**
     * Flags every PES packet in progress as damaged and resets the continuity counters, after the
     * scanner lost synchronization.
     */
    private void interruptAll() {
        for (var state : pids) {
            state.damaged = true;
            state.counter = -1;
        }
        for (var state : sections.values()) {
            state.counter = -1;
        }
    }

    /**
     * Searches forward for a packet boundary: a sync byte followed by two more at packet
     * distance (or by the end of the input).
     *
     * @param from the offset where the search starts
     * @return the offset of the packet start, or {@code -1} when none is found before the end
     */
    private long resynchronize(long from) {
        var size = geometry.packetSize();
        var sync = geometry.syncOffset();
        var window = new byte[RESYNC_WINDOW + 2 * size + sync + 1];
        var start = from;
        while (start < end) {
            var read = cache.readUpTo(start, window, 0, window.length);
            if (read <= sync) {
                return -1;
            }
            var candidates = Math.min(read - sync, RESYNC_WINDOW);
            for (var index = 0; index < candidates; index++) {
                if ((window[index + sync] & 0xFF) != SYNC) {
                    continue;
                }
                var second = index + size + sync;
                var third = second + size;
                if ((second >= read || (window[second] & 0xFF) == SYNC) && (third >= read || (window[third] & 0xFF) == SYNC)) {
                    return start + index;
                }
            }
            if (read < window.length) {
                return -1;
            }
            start += RESYNC_WINDOW;
        }
        return -1;
    }

    /**
     * Holds the PES reassembly state of one elementary PID.
     */
    private static final class PidState {
        /**
         * The parser of the stream.
         */
        final ElementaryParser parser;

        /**
         * The bytes of the PES packet being reassembled.
         */
        byte[] data = new byte[4096];

        /**
         * The number of bytes reassembled.
         */
        int length;

        /**
         * Whether a PES packet is being reassembled.
         */
        boolean active;

        /**
         * Whether the PES packet being reassembled is damaged.
         */
        boolean corrupt;

        /**
         * Whether a packet lost before the next one damaged the stream.
         */
        boolean damaged;

        /**
         * The offset of the transport packet that started the PES packet.
         */
        long offset;

        /**
         * Whether the PES packet started with the random access indicator.
         */
        boolean randomAccess;

        /**
         * The last continuity counter, or {@code -1}.
         */
        int counter = -1;

        /**
         * Constructs the state of a PID.
         *
         * @param parser the parser of the stream
         */
        PidState(ElementaryParser parser) {
            this.parser = parser;
        }
    }

    /**
     * Holds the section reassembly state of one PSI PID.
     */
    private static final class SectionState {
        /**
         * The section assembler.
         */
        final Psi.SectionAssembler assembler = new Psi.SectionAssembler();

        /**
         * The last continuity counter, or {@code -1}.
         */
        int counter = -1;
    }
}
