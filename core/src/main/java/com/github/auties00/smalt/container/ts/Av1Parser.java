package com.github.auties00.smalt.container.ts;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.internal.codec.CodecConfigs;
import com.github.auties00.smalt.internal.codec.VideoConfigs;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.PacketFlag;
import com.github.auties00.smalt.track.PacketSideData;
import com.github.auties00.smalt.track.VideoTrack;

import java.util.Arrays;
import java.util.EnumSet;

/**
 * Splits an AV1 stream carried as the AOM "Carriage of AV1 in MPEG-2 TS" specifies ({@code stream_type 0x06}
 * with the {@code "AV01"} registration) into temporal units.
 *
 * <p>Each PES packet holds one access unit: OBUs, each preceded by the start code {@code 00 00 01} and
 * protected against start code emulation by {@code 03} bytes inserted after every {@code 00 00} (clause 3.2).
 * The parser removes both and stores every OBU with its size field, as ISOBMFF and Matroska do; temporal
 * delimiters are dropped. A packet is one temporal unit: when the stream carries temporal delimiters, the OBUs
 * between two of them; otherwise the access units up to and including the first one that holds a shown frame.
 * A temporal unit whose shown frame is a key frame is a key frame; the track's {@code av1C} configuration is
 * built from the sequence header in force at the first one, and a later key frame whose sequence header differs
 * carries the new configuration as its {@link PacketSideData#newCodecConfig()}. Temporal units before the first
 * key frame cannot be decoded and are dropped. Timestamps are the PES ones on the 90 kHz timeline: the
 * presentation time of the access unit holding the shown frame and the decoding time of the first access unit,
 * and a packet lasts until the next one's decoding time. Instances are not thread-safe.
 */
final class Av1Parser extends ElementaryParser {
    /**
     * The OBU type of a sequence header.
     */
    private static final int OBU_SEQUENCE_HEADER = 1;

    /**
     * The OBU type of a temporal delimiter.
     */
    private static final int OBU_TEMPORAL_DELIMITER = 2;

    /**
     * The OBU type of a frame header.
     */
    private static final int OBU_FRAME_HEADER = 3;

    /**
     * The OBU type of a frame (a frame header and its tile group).
     */
    private static final int OBU_FRAME = 6;

    /**
     * The largest temporal unit assembled.
     */
    private static final int CAPACITY = 64 << 20;

    /**
     * The temporal unit being assembled, or {@code null}.
     */
    private Unit current;

    /**
     * The completed temporal unit waiting for its successor's decoding time, or {@code null}.
     */
    private Unit held;

    /**
     * Whether the stream carries temporal delimiters, which then alone delimit temporal units.
     */
    private boolean delimited;

    /**
     * The last sequence header OBU met, with its size field, or {@code null}.
     */
    private byte[] sequenceHeader;

    /**
     * Whether the last sequence header met uses the reduced still picture header.
     */
    private boolean reducedStill;

    /**
     * The sequence header OBU of the configuration last reported downstream, or {@code null}.
     */
    private byte[] established;

    /**
     * Whether a key frame was met, after which every temporal unit is emitted.
     */
    private boolean seenKey;

    /**
     * Whether temporal units before the first key frame are emitted too.
     */
    private boolean keepLeading;

    /**
     * The decoding time of the last emitted unit, or {@link TsClock#NONE}.
     */
    private long lastDts = TsClock.NONE;

    /**
     * The duration of the last emitted packet, in 90 kHz ticks.
     */
    private long lastDuration;

    /**
     * The scratch buffer of one OBU with its emulation prevention removed.
     */
    private byte[] scratch = new byte[4096];

    /**
     * Collects the OBUs of one temporal unit.
     */
    private static final class Unit {
        /**
         * The OBUs, each with its size field.
         */
        byte[] data = new byte[4096];

        /**
         * The number of bytes of {@link #data} in use.
         */
        int size;

        /**
         * The presentation time on the 90 kHz timeline, or {@link TsClock#NONE}.
         */
        long pts = TsClock.NONE;

        /**
         * The decoding time on the 90 kHz timeline, or {@link TsClock#NONE}.
         */
        long dts = TsClock.NONE;

        /**
         * The offset of the transport packet that started the first PES packet of the unit.
         */
        long fileOffset;

        /**
         * Whether a damaged PES packet contributed bytes.
         */
        boolean corrupt;

        /**
         * Whether the unit holds a shown frame.
         */
        boolean shown;

        /**
         * Whether the unit's shown frame is a key frame.
         */
        boolean key;

        /**
         * The configuration record the unit establishes, or {@code null}.
         */
        byte[] newConfig;
    }

    /**
     * Constructs a parser.
     *
     * @param entry the program map entry of the stream
     * @param limits the resource bounds
     */
    Av1Parser(Psi.StreamEntry entry, MediaLimits limits) {
        super(entry, limits);
    }

    @Override
    ElementaryParser fresh() {
        var parser = new Av1Parser(entry, limits);
        parser.track = track;
        parser.delimited = delimited;
        if (track != null) {
            var config = track.codecConfig().orElse(null);
            if (config != null && config.length > 4) {
                parser.established = Arrays.copyOfRange(config, 4, config.length);
                parser.sequenceHeader = parser.established;
                parser.reducedStill = reducedStill(parser.established);
            }
        }
        return parser;
    }

    @Override
    void keepLeadingUnits() {
        keepLeading = true;
    }

    @Override
    void accept(Pes pes, TsClock clock, UnitSink sink) {
        var dts = pes.dts() == TsClock.NONE ? TsClock.NONE : clock.ticks(trackId(), pes.dts(), true);
        var pts = pes.pts() == TsClock.NONE ? TsClock.NONE : clock.ticks(trackId(), pes.pts(), dts == TsClock.NONE);
        if (dts == TsClock.NONE) {
            dts = pts;
        }
        if (pes.length() == 0) {
            return;
        }
        if (!delimited && current != null && current.shown) {
            finish(sink);
        }
        var data = pes.data();
        var end = pes.offset() + pes.length();
        var code = nextStartCode(data, pes.offset(), end);
        var first = true;
        while (code >= 0) {
            var next = nextStartCode(data, code + 3, end);
            var obuEnd = next >= 0 ? next : end;
            var length = unescape(data, code + 3, obuEnd);
            obu(length, pts, dts, pes, first, sink);
            first = false;
            code = next;
        }
    }

    @Override
    void flush(TsClock clock, UnitSink sink) {
        finish(sink);
        if (held != null) {
            emit(held, lastDuration, sink);
            held = null;
        }
    }

    /**
     * Returns the index of the next start code {@code 00 00 01}.
     *
     * @param data the bytes
     * @param from the index the search starts at
     * @param end the index after the last byte
     * @return the index of the start code's first byte, or {@code -1}
     */
    private static int nextStartCode(byte[] data, int from, int end) {
        for (var index = from; index + 3 <= end; index++) {
            if (data[index + 2] == 1 && data[index + 1] == 0 && data[index] == 0) {
                return index;
            }
        }
        return -1;
    }

    /**
     * Copies the bytes of one OBU into {@link #scratch}, dropping every emulation prevention byte (a
     * {@code 03} after {@code 00 00}).
     *
     * @param data the bytes
     * @param from the index of the OBU header
     * @param to the index after the OBU
     * @return the OBU length
     */
    private int unescape(byte[] data, int from, int to) {
        if (scratch.length < to - from) {
            LimitChecks.checkAllocation(limits, to - from);
            scratch = new byte[Math.max(to - from, scratch.length * 2)];
        }
        var length = 0;
        var zeros = 0;
        for (var index = from; index < to; index++) {
            var value = data[index];
            if (zeros >= 2 && value == 3) {
                zeros = 0;
                continue;
            }
            zeros = value == 0 ? zeros + 1 : 0;
            scratch[length++] = value;
        }
        return length;
    }

    /**
     * Adds one OBU of {@link #scratch} to the temporal unit it belongs to.
     *
     * @param length the OBU length
     * @param pts the presentation time of its PES packet, or {@link TsClock#NONE}
     * @param dts the decoding time of its PES packet, or {@link TsClock#NONE}
     * @param pes the PES packet
     * @param first whether this is the first OBU of the PES packet
     * @param sink receives the completed packets
     */
    private void obu(int length, long pts, long dts, Pes pes, boolean first, UnitSink sink) {
        if (length < 1 || (scratch[0] & 0x80) != 0) {
            return;
        }
        var header = scratch[0] & 0xFF;
        var type = (header >> 3) & 0x0F;
        var headerSize = (header & 0x04) != 0 ? 2 : 1;
        if (length < headerSize) {
            return;
        }
        var payloadStart = headerSize;
        var payloadSize = length - headerSize;
        if ((header & 0x02) != 0) {
            var size = 0L;
            var position = headerSize;
            for (var shift = 0; ; shift += 7) {
                if (position >= length || shift > 49) {
                    return;
                }
                var b = scratch[position++] & 0xFF;
                size |= (long) (b & 0x7F) << shift;
                if ((b & 0x80) == 0) {
                    break;
                }
            }
            if (size > length - position) {
                return;
            }
            payloadStart = position;
            payloadSize = (int) size;
        }
        if (type == OBU_TEMPORAL_DELIMITER) {
            delimited = true;
            finish(sink);
            return;
        }
        if (current == null) {
            current = new Unit();
            current.fileOffset = pes.fileOffset();
            current.dts = dts;
            current.pts = pts;
            current.corrupt = pes.corrupt();
        } else if (first) {
            current.corrupt |= pes.corrupt();
            if (current.pts == TsClock.NONE) {
                current.pts = pts;
            }
            if (current.dts == TsClock.NONE) {
                current.dts = dts;
            }
        }
        var obuStart = current.size;
        append(header & ~0x02 | 0x02);
        if (headerSize == 2) {
            append(scratch[1] & 0xFF);
        }
        var size = payloadSize;
        do {
            append((size & 0x7F) | (size > 0x7F ? 0x80 : 0));
            size >>>= 7;
        } while (size > 0);
        appendScratch(payloadStart, payloadSize);
        switch (type) {
            case OBU_SEQUENCE_HEADER -> {
                sequenceHeader = Arrays.copyOfRange(current.data, obuStart, current.size);
                reducedStill = payloadSize > 0 && (scratch[payloadStart] & 0x08) != 0;
            }
            case OBU_FRAME_HEADER, OBU_FRAME -> {
                if (!current.shown && payloadSize > 0) {
                    frameHeader(payloadStart, pts);
                }
            }
            default -> {
            }
        }
    }

    /**
     * Reads whether a frame header shows a frame and whether that frame is a key frame (AV1 specification
     * section 5.9.2).
     *
     * @param offset the index of the frame header in {@link #scratch}
     * @param pts the presentation time of its PES packet, or {@link TsClock#NONE}
     */
    private void frameHeader(int offset, long pts) {
        var first = scratch[offset] & 0xFF;
        boolean shown;
        boolean key;
        if (reducedStill) {
            shown = true;
            key = true;
        } else if ((first & 0x80) != 0) {
            shown = true;
            key = false;
        } else {
            key = ((first >> 5) & 0x03) == 0;
            shown = (first & 0x10) != 0;
        }
        if (shown) {
            current.shown = true;
            current.key = key;
            if (pts != TsClock.NONE) {
                current.pts = pts;
            }
        }
    }

    /**
     * Appends one byte to the temporal unit being assembled.
     *
     * @param value the byte
     */
    private void append(int value) {
        reserve(1);
        current.data[current.size++] = (byte) value;
    }

    /**
     * Appends bytes of {@link #scratch} to the temporal unit being assembled.
     *
     * @param from the index of the first byte
     * @param count the number of bytes
     */
    private void appendScratch(int from, int count) {
        reserve(count);
        System.arraycopy(scratch, from, current.data, current.size, count);
        current.size += count;
    }

    /**
     * Makes room for bytes in the temporal unit being assembled.
     *
     * @param count the number of bytes to add
     */
    private void reserve(int count) {
        var needed = (long) current.size + count;
        if (needed > current.data.length) {
            if (needed > CAPACITY || needed > limits.maxBytes()) {
                throw new com.github.auties00.smalt.MediaException.Malformed("an AV1 temporal unit exceeds "
                        + Math.min(CAPACITY, limits.maxBytes()) + " bytes");
            }
            var grown = Math.min(Math.max(needed, current.data.length * 2L), CAPACITY);
            LimitChecks.checkAllocation(limits, grown);
            current.data = Arrays.copyOf(current.data, (int) grown);
        }
    }

    /**
     * Completes the temporal unit being assembled: decides its configuration, stamps it and passes the
     * previous one on.
     *
     * @param sink receives the completed packets
     */
    private void finish(UnitSink sink) {
        var unit = current;
        current = null;
        if (unit == null || !unit.shown) {
            return;
        }
        if (unit.key && sequenceHeader != null) {
            if (track == null) {
                configure();
            } else if (!Arrays.equals(sequenceHeader, established)) {
                unit.newConfig = CodecConfigs.av1Config(sequenceHeader);
                if (unit.newConfig != null) {
                    established = sequenceHeader;
                }
            }
        }
        if (unit.dts == TsClock.NONE) {
            if (lastDts == TsClock.NONE) {
                return;
            }
            unit.dts = lastDts + Math.max(1, lastDuration);
        }
        if (unit.pts == TsClock.NONE) {
            unit.pts = unit.dts;
        }
        if (!seenKey && !unit.key && !keepLeading) {
            return;
        }
        if (track == null) {
            return;
        }
        seenKey = true;
        if (held != null) {
            var duration = unit.dts - held.dts;
            if (duration <= 0) {
                duration = lastDuration;
            }
            emit(held, duration, sink);
        }
        held = unit;
        lastDts = unit.dts;
    }

    /**
     * Passes a completed temporal unit on as a packet.
     *
     * @param unit the temporal unit
     * @param duration its duration in 90 kHz ticks
     * @param sink receives the packet
     */
    private void emit(Unit unit, long duration, UnitSink sink) {
        lastDuration = Math.max(0, duration);
        var flags = EnumSet.noneOf(PacketFlag.class);
        if (unit.key) {
            flags.add(PacketFlag.KEY);
        }
        if (unit.corrupt) {
            flags.add(PacketFlag.CORRUPT);
        }
        sink.accept(new Packet(trackId(), Arrays.copyOf(unit.data, unit.size), unit.pts, unit.dts,
                lastDuration > 0 ? lastDuration : Packet.UNKNOWN_DURATION, flags,
                unit.newConfig == null ? PacketSideData.NONE : PacketSideData.builder().newCodecConfig(unit.newConfig).build()),
                unit.fileOffset);
    }

    /**
     * Builds the track from the last sequence header, leaving it unset when the header does not parse.
     */
    private void configure() {
        var config = CodecConfigs.av1Config(sequenceHeader);
        if (config == null) {
            return;
        }
        var info = VideoConfigs.describe(Codec.AV1, config);
        var builder = VideoTrack.builder()
                .id(trackId())
                .codec(Codec.AV1)
                .timeBase(NalParser.TIME_BASE)
                .codecConfig(config)
                .language(entry.language());
        if (info != null && info.width() > 0 && info.height() > 0) {
            LimitChecks.checkPicture(limits, info.width(), info.height());
            builder.width(info.width()).height(info.height()).color(info.color());
        }
        track = builder.build();
        established = sequenceHeader;
    }

    /**
     * Returns whether a sequence header OBU uses the reduced still picture header.
     *
     * @param obu the OBU, with its size field
     * @return {@code true} when {@code reduced_still_picture_header} is set
     */
    private static boolean reducedStill(byte[] obu) {
        var position = (obu[0] & 0x04) != 0 ? 2 : 1;
        while (position < obu.length && (obu[position] & 0x80) != 0) {
            position++;
        }
        position++;
        return position < obu.length && (obu[position] & 0x08) != 0;
    }
}
