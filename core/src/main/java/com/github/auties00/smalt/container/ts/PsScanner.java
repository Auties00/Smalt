package com.github.auties00.smalt.container.ts;

import com.github.auties00.smalt.internal.io.ReadAheadCache;
import com.github.auties00.smalt.io.MediaSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Reads the packs of a program stream (ISO/IEC 13818-1 clause 2.5) or of an MPEG-1 system stream (ISO/IEC 11172-1)
 * in order from an offset and feeds the PES packets of the streams it is given to their parsers.
 *
 * <p>Pack headers of both syntaxes, system headers, padding, private stream 2 (the navigation packets of DVD-Video)
 * and the program end code are read past; a program stream map is handed to a listener. A PES packet is keyed by its
 * stream: its {@code stream_id}; for private stream 1 in the DVD-Video layout, {@code 0x100} plus its
 * {@code sub_stream_id}; for an extended stream id ({@code 0xFD}), {@code 0x200} plus its {@code stream_id_extension}.
 * Of a DVD-Video substream the parser receives the payload after the substream identifier, and of an AC-3, E-AC-3 or
 * DTS substream after the three bytes that follow it ({@code number_of_frame_headers} and
 * {@code first_access_unit_pointer}). When the bytes at the scan position are not a pack, a system header or a PES
 * packet, the scanner searches forward for the next one and flags the next PES packet of every stream as damaged; a
 * PES packet with scrambled payload (DVD CSS) is dropped and flags the next one of its stream. Instances are not
 * thread-safe.
 */
final class PsScanner {
    /**
     * The largest PES packet: a 16-bit length after the six-byte prefix.
     */
    static final int MAX_PES = 6 + 0xFFFF;

    /**
     * The key of the streams of private stream 1 that carry no DVD-Video substream identifier.
     */
    static final int PRIVATE_1 = 0xBD;

    /**
     * The offset added to a {@code sub_stream_id} of private stream 1 to key its stream.
     */
    static final int SUBSTREAM = 0x100;

    /**
     * The offset added to a {@code stream_id_extension} to key its stream.
     */
    static final int EXTENDED = 0x200;

    /**
     * The size of the windows searched for a start code while resynchronizing.
     */
    private static final int RESYNC_WINDOW = 64 * 1024;

    /**
     * Receives the PES packets of streams that have no parser.
     */
    @FunctionalInterface
    interface StreamListener {
        /**
         * Receives a PES packet of a stream without a parser.
         *
         * @param key the key of the stream
         * @param pes the packet, its payload after any substream header
         * @return the parser that reads the stream from this packet on, or {@code null} to leave it unread
         */
        ElementaryParser stream(int key, ElementaryParser.Pes pes);
    }

    /**
     * Receives the program stream maps.
     */
    @FunctionalInterface
    interface MapListener {
        /**
         * Receives one program stream map.
         *
         * @param map the whole PES packet of the map
         * @param offset its file offset
         */
        void map(byte[] map, long offset);
    }

    /**
     * Describes how a stream reaches its parser.
     */
    private static final class Route {
        /**
         * The parser.
         */
        final ElementaryParser parser;

        /**
         * Whether the next PES packet of the stream follows lost or dropped bytes.
         */
        boolean damaged;

        Route(ElementaryParser parser) {
            this.parser = parser;
        }
    }

    /**
     * The read-ahead window over the input.
     */
    private final ReadAheadCache cache;

    /**
     * The offset at which the scan ends.
     */
    private final long end;

    /**
     * The timeline.
     */
    private final TsClock clock;

    /**
     * The number of stream keys: stream ids, substream keys and extended stream keys.
     */
    private static final int KEYS = EXTENDED + 0x100;

    /**
     * The route of each parsed stream, indexed by key, {@code null} for a stream without a parser.
     */
    private final Route[] routes = new Route[KEYS];

    /**
     * The parsed streams, in the order they were added.
     */
    private final List<Route> ordered = new ArrayList<>();

    /**
     * The bytes of the current PES packet.
     */
    private final byte[] packet = new byte[MAX_PES];

    /**
     * Receives the packets of the streams without a parser, or {@code null}.
     */
    private StreamListener streams;

    /**
     * Receives the program stream maps, or {@code null}.
     */
    private MapListener maps;

    /**
     * Whether private stream 1 carries DVD-Video substream identifiers, or {@code null} until its first PES packet
     * or the caller decides.
     */
    private Boolean substreams;

    /**
     * The offset of the next unit.
     */
    private long position;

    /**
     * The offset of the last pack header read, or {@code -1} before the first.
     */
    private long packOffset = -1;

    /**
     * The system clock reference of the last pack header read, in 90 kHz ticks, or {@link TsClock#NONE}.
     */
    private long scr = TsClock.NONE;

    /**
     * The window searched for a start code while resynchronizing, allocated on first use.
     */
    private byte[] window;

    /**
     * Constructs a scanner.
     *
     * @param source the input
     * @param start the offset of the first unit read
     * @param end the offset at which the scan ends, or {@link Long#MAX_VALUE}
     * @param clock the timeline
     */
    PsScanner(MediaSource source, long start, long end, TsClock clock) {
        this.cache = new ReadAheadCache(source, 256 * 1024, 4 * 1024);
        this.end = end;
        this.clock = clock;
        this.position = start;
    }

    /**
     * {@return the offset of the next unit}
     */
    long position() {
        return position;
    }

    /**
     * {@return the system clock reference of the last pack header read, in 90 kHz ticks, or TsClock.NONE}
     */
    long scr() {
        return scr;
    }

    /**
     * Sets whether private stream 1 carries DVD-Video substream identifiers. Unless set, the first PES packet of
     * private stream 1 decides: it carries them unless its payload starts with an AC-3 sync word.
     *
     * @param substreams {@code true} for the DVD-Video layout
     */
    void substreams(boolean substreams) {
        this.substreams = substreams;
    }

    /**
     * {@return whether private stream 1 carries DVD-Video substream identifiers, or null while undecided}
     */
    Boolean substreams() {
        return substreams;
    }

    /**
     * Sets the parser of a stream.
     *
     * @param key the key of the stream
     * @param parser the parser that reads the stream
     */
    void parse(int key, ElementaryParser parser) {
        var route = new Route(parser);
        var previous = routes[key];
        routes[key] = route;
        if (previous == null) {
            ordered.add(route);
        } else {
            ordered.replaceAll(existing -> existing == previous ? route : existing);
        }
    }

    /**
     * Sets the listener of the streams without a parser.
     *
     * @param listener the listener, or {@code null}
     */
    void listen(StreamListener listener) {
        this.streams = listener;
    }

    /**
     * Sets the listener of the program stream maps.
     *
     * @param listener the listener, or {@code null}
     */
    void listen(MapListener listener) {
        this.maps = listener;
    }

    /**
     * Reads and processes the next unit: a pack header, a system header or a PES packet.
     *
     * @param sink receives the packets the parsers complete
     * @return {@code false} at the end of the scanned range
     */
    boolean step(ElementaryParser.UnitSink sink) {
        while (true) {
            if (position + 4 > end) {
                return false;
            }
            var read = cache.readUpTo(position, packet, 0, 16);
            if (read < 4) {
                return false;
            }
            if (packet[0] != 0 || packet[1] != 0 || packet[2] != 1 || (packet[3] & 0xFF) < 0xB9) {
                if (!resynchronize(position + 1)) {
                    return false;
                }
                continue;
            }
            var code = packet[3] & 0xFF;
            if (code == 0xBA) {
                var length = packLength(packet, 0, read);
                if (length < 0) {
                    if (read < 16 && position + read >= end) {
                        position = end;
                        return false;
                    }
                    if (!resynchronize(position + 1)) {
                        return false;
                    }
                    continue;
                }
                packOffset = position;
                scr = scr(packet);
                position += length;
                return true;
            }
            if (code == 0xB9) {
                position += 4;
                return true;
            }
            if (read < 6) {
                position = end;
                return false;
            }
            var declared = (packet[4] & 0xFF) << 8 | (packet[5] & 0xFF);
            if (declared == 0) {
                if (!resynchronize(position + 1)) {
                    return false;
                }
                continue;
            }
            var total = 6 + declared;
            var offset = position;
            var available = cache.readUpTo(position, packet, 0, total);
            position += total;
            if (code == 0xBB || code == 0xBE || code == 0xBF) {
                return true;
            }
            if (code == 0xBC) {
                if (maps != null && available == total) {
                    maps.map(Arrays.copyOf(packet, total), offset);
                }
                return true;
            }
            deliver(code, available, available < total, offset, sink);
            return true;
        }
    }

    /**
     * Completes the parsers' packets at the end of the input.
     *
     * @param sink receives the packets
     */
    void finish(ElementaryParser.UnitSink sink) {
        for (var route : ordered) {
            route.parser.flush(clock, sink);
        }
    }

    /**
     * Parses one PES packet and feeds its payload to the parser of its stream.
     *
     * @param streamId the {@code stream_id}
     * @param length the number of bytes of the packet read
     * @param truncated whether the input ended inside the packet
     * @param offset the file offset of the packet
     * @param sink receives the packets the parser completes
     */
    private void deliver(int streamId, int length, boolean truncated, long offset, ElementaryParser.UnitSink sink) {
        var header = header(packet, length, streamId);
        if (header == null) {
            return;
        }
        var payload = header.payload;
        var key = streamId;
        if (streamId == 0xFD) {
            if (header.extension < 0) {
                return;
            }
            key = EXTENDED + header.extension;
        } else if (streamId == PRIVATE_1) {
            if (payload >= length) {
                return;
            }
            if (substreams == null) {
                substreams = payload + 1 >= length || packet[payload] != 0x0B || packet[payload + 1] != 0x77;
            }
        }
        if (streamId == PRIVATE_1 && substreams) {
            var sub = packet[payload] & 0xFF;
            key = SUBSTREAM + sub;
            payload += (sub >= 0x80 && sub <= 0x8F) || (sub >= 0xC0 && sub <= 0xCF) ? 4 : 1;
            if (payload > length) {
                return;
            }
        }
        var route = routes[key];
        var damaged = truncated;
        if (route != null && route.damaged) {
            damaged = true;
            route.damaged = false;
        }
        if (header.scrambled) {
            if (route != null) {
                route.damaged = true;
            }
            return;
        }
        var fileOffset = packOffset >= 0 ? packOffset : offset;
        var pes = new ElementaryParser.Pes(packet, payload, length - payload, header.pts, header.dts, fileOffset,
                damaged, false);
        if (route == null) {
            if (streams == null) {
                return;
            }
            var parser = streams.stream(key, pes);
            if (parser == null) {
                return;
            }
            parse(key, parser);
            route = routes[key];
        }
        route.parser.accept(pes, clock, sink);
    }

    /**
     * Holds the fields of a PES header.
     *
     * @param payload the index of the payload
     * @param pts the 33-bit presentation timestamp, or {@link TsClock#NONE}
     * @param dts the 33-bit decoding timestamp, or {@link TsClock#NONE}
     * @param scrambled whether {@code PES_scrambling_control} is set
     * @param extension the {@code stream_id_extension}, or {@code -1}
     */
    record Header(int payload, long pts, long dts, boolean scrambled, int extension) {
    }

    /**
     * Parses the header of a PES packet in the MPEG-2 syntax or in the MPEG-1 syntax.
     *
     * @param data the packet, from its start code prefix
     * @param length the number of bytes of the packet
     * @param streamId the {@code stream_id}
     * @return the header, or {@code null} when it is truncated or invalid
     */
    static Header header(byte[] data, int length, int streamId) {
        if (length < 7) {
            return null;
        }
        if ((data[6] & 0xC0) == 0x80) {
            if (length < 9) {
                return null;
            }
            var flags = (data[7] & 0xFF) >> 6;
            var headerLength = data[8] & 0xFF;
            var payload = 9 + headerLength;
            if (payload > length || flags == 1) {
                return null;
            }
            var pts = flags >= 2 && headerLength >= 5 ? TsScanner.timestamp(data, 9) : TsClock.NONE;
            var dts = flags == 3 && headerLength >= 10 ? TsScanner.timestamp(data, 14) : TsClock.NONE;
            var extension = streamId == 0xFD ? extension(data, payload, flags) : -1;
            return new Header(payload, pts, dts, (data[6] & 0x30) != 0, extension);
        }
        var position = 6;
        while (position < length && position < 6 + 16 && (data[position] & 0xFF) == 0xFF) {
            position++;
        }
        if (position < length && (data[position] & 0xC0) == 0x40) {
            position += 2;
        }
        if (position >= length) {
            return null;
        }
        var marker = data[position] & 0xFF;
        if ((marker & 0xF0) == 0x20) {
            if (position + 5 > length) {
                return null;
            }
            return new Header(position + 5, TsScanner.timestamp(data, position), TsClock.NONE, false, -1);
        }
        if ((marker & 0xF0) == 0x30) {
            if (position + 10 > length) {
                return null;
            }
            return new Header(position + 10, TsScanner.timestamp(data, position),
                    TsScanner.timestamp(data, position + 5), false, -1);
        }
        return marker == 0x0F ? new Header(position + 1, TsClock.NONE, TsClock.NONE, false, -1) : null;
    }

    /**
     * Reads the {@code stream_id_extension} of an MPEG-2 PES header.
     *
     * @param data the packet
     * @param payload the index of the payload, which bounds the header
     * @param timestamps the {@code PTS_DTS_flags}
     * @return the extension, or {@code -1} when the header has none
     */
    private static int extension(byte[] data, int payload, int timestamps) {
        var flags = data[7] & 0xFF;
        var position = 9 + (timestamps == 2 ? 5 : timestamps == 3 ? 10 : 0);
        if ((flags & 0x20) != 0) {
            position += 6;
        }
        if ((flags & 0x10) != 0) {
            position += 3;
        }
        if ((flags & 0x08) != 0) {
            position += 1;
        }
        if ((flags & 0x04) != 0) {
            position += 1;
        }
        if ((flags & 0x02) != 0) {
            position += 2;
        }
        if ((flags & 0x01) == 0 || position >= payload) {
            return -1;
        }
        var extensionFlags = data[position++] & 0xFF;
        if ((extensionFlags & 0x80) != 0) {
            position += 16;
        }
        if ((extensionFlags & 0x40) != 0) {
            position += position < payload ? 1 + (data[position] & 0xFF) : 1;
        }
        if ((extensionFlags & 0x20) != 0) {
            position += 2;
        }
        if ((extensionFlags & 0x10) != 0) {
            position += 2;
        }
        if ((extensionFlags & 0x01) == 0 || position + 1 >= payload) {
            return -1;
        }
        var field = data[position + 1] & 0xFF;
        return (field & 0x80) == 0 ? field & 0x7F : -1;
    }

    /**
     * Returns the length of a pack header, stuffing included, after checking its marker bits.
     *
     * @param data the bytes
     * @param offset the index of the pack start code
     * @param available the number of bytes read from {@code offset}
     * @return the length, or {@code -1} when the header is truncated or not a pack header
     */
    static int packLength(byte[] data, int offset, int available) {
        if (available < 12) {
            return -1;
        }
        var b4 = data[offset + 4];
        if ((b4 & 0xC4) == 0x44) {
            if (available < 14 || (data[offset + 6] & 0x04) == 0 || (data[offset + 8] & 0x04) == 0
                    || (data[offset + 9] & 0x01) == 0 || (data[offset + 12] & 0x03) != 0x03) {
                return -1;
            }
            return 14 + (data[offset + 13] & 0x07);
        }
        if ((b4 & 0xF1) == 0x21 && (data[offset + 6] & 0x01) != 0 && (data[offset + 8] & 0x01) != 0
                && (data[offset + 9] & 0x80) != 0 && (data[offset + 11] & 0x01) != 0) {
            return 12;
        }
        return -1;
    }

    /**
     * Returns whether a pack header uses the MPEG-1 syntax.
     *
     * @param data the bytes from the pack start code
     * @return {@code true} for an ISO/IEC 11172-1 pack header
     */
    static boolean isMpeg1Pack(byte[] data) {
        return (data[4] & 0xC0) != 0x40;
    }

    /**
     * Returns the 33-bit base of the system clock reference of a pack header, in 90 kHz ticks.
     *
     * @param data the bytes from the pack start code, a valid header
     * @return the clock base
     */
    static long scr(byte[] data) {
        if (isMpeg1Pack(data)) {
            return TsScanner.timestamp(data, 4);
        }
        return ((long) (data[4] & 0x38) << 27) | ((long) (data[4] & 0x03) << 28) | ((data[5] & 0xFFL) << 20)
                | ((long) (data[6] & 0xF8) << 12) | ((long) (data[6] & 0x03) << 13) | ((data[7] & 0xFFL) << 5)
                | ((data[8] & 0xFFL) >> 3);
    }

    /**
     * Returns whether a start code is one the scan can restart at: a pack header whose marker bits hold, a system
     * header, a program stream map or the program end code.
     *
     * @param data the bytes
     * @param index the index of the start code prefix
     * @param available the number of bytes of {@code data} read
     * @return {@code true} for such a start code
     */
    private static boolean isAnchor(byte[] data, int index, int available) {
        var code = data[index + 3] & 0xFF;
        if (code == 0xBA) {
            return packLength(data, index, available - index) > 0;
        }
        return code == 0xB9 || code == 0xBB || code == 0xBC;
    }

    /**
     * Moves the scan position to the next start code of a pack, a system header, a program stream map or the
     * program end code.
     *
     * @param from the offset to search from
     * @return {@code false} when there is none before the end of the scanned range
     */
    private boolean resynchronize(long from) {
        if (window == null) {
            window = new byte[RESYNC_WINDOW];
        }
        for (var route : ordered) {
            route.damaged = true;
        }
        var at = from;
        while (at + 4 <= end) {
            var requested = (int) Math.min(RESYNC_WINDOW, end - at);
            var read = cache.readUpTo(at, window, 0, requested);
            if (read < 4) {
                break;
            }
            var last = read < requested || read <= 16 ? read - 3 : read - 16;
            for (var index = 0; index < last; index++) {
                if (window[index] == 0 && window[index + 1] == 0 && window[index + 2] == 1
                        && isAnchor(window, index, read)) {
                    position = at + index;
                    return true;
                }
            }
            if (read < requested || read <= 16) {
                break;
            }
            at += last;
        }
        position = end;
        return false;
    }
}
