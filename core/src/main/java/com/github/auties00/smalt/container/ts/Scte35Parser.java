package com.github.auties00.smalt.container.ts;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.internal.header.Crc32Mpeg;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.DataTrack;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.PacketFlag;
import com.github.auties00.smalt.track.PacketSideData;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Passes the {@code splice_info_section}s of an SCTE 35 stream ({@code stream_type 0x86}, ANSI/SCTE 35 clause
 * 9) through as the packets of a {@link DataTrack} with the handler {@value #HANDLER}.
 *
 * <p>Every section whose {@code table_id} is {@code 0xFC} and whose {@code CRC_32} holds becomes one packet
 * holding the whole section, unparsed but for its {@code pts_adjustment}, which is rebased (and the
 * {@code CRC_32} recomputed) so that a splice time plus {@code pts_adjustment}, modulo 2^33, is a time of the
 * track's 90 kHz timeline, the one the packets of every track of the demuxer are on. The sections carry no
 * timestamp of their own: a packet is stamped with the latest decoding time of the program's other streams when
 * the section arrives, and the sections that arrive before any stream had one are held back and stamped with the
 * first one; at the end of an input without any they have no timestamp and keep the {@code pts_adjustment} they
 * were sent with. A packet lasts until the next section's time, the last one until the latest decoding time at
 * the end of the input, so a packet is passed on when the next section arrives. Every packet is a key frame.
 * Instances are not thread-safe.
 */
final class Scte35Parser extends ElementaryParser {
    /**
     * The handler of an SCTE 35 track.
     */
    static final String HANDLER = "scte35";

    /**
     * The largest number of sections held back while the timeline has no time.
     */
    private static final int MAX_HELD = 64;

    /**
     * The flags of every packet.
     */
    private static final Set<PacketFlag> KEY = Set.of(PacketFlag.KEY);

    /**
     * The packet of the last section, waiting for the next one to give it its duration, or {@code null}.
     */
    private Packet pending;

    /**
     * The file offset of {@link #pending}.
     */
    private long pendingOffset;

    /**
     * The sections held back until the timeline has a time, with their file offsets.
     */
    private final List<Held> held = new ArrayList<>();

    /**
     * Holds a section that arrived before the timeline had a time.
     *
     * @param section the whole {@code splice_info_section}
     * @param fileOffset the offset of the transport packet in which it started
     */
    private record Held(byte[] section, long fileOffset) {
    }

    /**
     * Constructs a parser.
     *
     * @param entry the program map entry of the stream
     * @param limits the resource bounds
     */
    Scte35Parser(Psi.StreamEntry entry, MediaLimits limits) {
        super(entry, limits);
        this.track = DataTrack.builder()
                .id(entry.trackId())
                .codec(Codec.DATA)
                .timeBase(NalParser.TIME_BASE)
                .language(entry.language())
                .handler(HANDLER)
                .build();
    }

    @Override
    ElementaryParser fresh() {
        return new Scte35Parser(entry, limits);
    }

    @Override
    boolean sectionCarried() {
        return true;
    }

    @Override
    void accept(Pes pes, TsClock clock, UnitSink sink) {
    }

    @Override
    void section(byte[] section, long fileOffset, TsClock clock, UnitSink sink) {
        if ((section[0] & 0xFF) != Psi.SPLICE_INFO_TABLE) {
            return;
        }
        var time = clock.latestDecoding();
        if (time == TsClock.NONE) {
            if (held.size() < MAX_HELD) {
                held.add(new Held(section, fileOffset));
            }
            return;
        }
        tick(clock, sink);
        emit(rebased(section, clock.latestDecodingOffset()), fileOffset, time, sink);
    }

    @Override
    void tick(TsClock clock, UnitSink sink) {
        if (held.isEmpty()) {
            return;
        }
        var time = clock.latestDecoding();
        if (time == TsClock.NONE) {
            return;
        }
        var offset = clock.latestDecodingOffset();
        for (var section : held) {
            emit(rebased(section.section(), offset), section.fileOffset(), time, sink);
        }
        held.clear();
    }

    @Override
    void flush(TsClock clock, UnitSink sink) {
        var time = clock.latestDecoding();
        for (var section : held) {
            emit(section.section(), section.fileOffset(), time == TsClock.NONE ? Packet.NO_TIMESTAMP : time, sink);
        }
        held.clear();
        if (pending != null) {
            var duration = time == TsClock.NONE || pending.pts() == Packet.NO_TIMESTAMP ? Packet.UNKNOWN_DURATION
                    : Math.max(0, time - pending.pts());
            sink.accept(pending.withTiming(pending.pts(), pending.dts(), duration), pendingOffset);
            pending = null;
        }
    }

    /**
     * Passes one section on as a packet, once the next one gives it its duration.
     *
     * @param section the whole {@code splice_info_section}
     * @param fileOffset the offset of the transport packet in which it started
     * @param time the timestamp in 90 kHz ticks, or {@link Packet#NO_TIMESTAMP}
     * @param sink receives the packet
     */
    private void emit(byte[] section, long fileOffset, long time, UnitSink sink) {
        if (pending != null) {
            var duration = time == Packet.NO_TIMESTAMP || pending.pts() == Packet.NO_TIMESTAMP
                    ? Packet.UNKNOWN_DURATION : Math.max(0, time - pending.pts());
            sink.accept(pending.withTiming(pending.pts(), pending.dts(), duration), pendingOffset);
        }
        pending = new Packet(trackId(), section, time, time, Packet.UNKNOWN_DURATION, KEY, PacketSideData.NONE);
        pendingOffset = fileOffset;
    }

    /**
     * Returns a {@code splice_info_section} with a value added to its {@code pts_adjustment} (ANSI/SCTE 35 clause
     * 9.6), modulo 2^33, and its {@code CRC_32} recomputed.
     *
     * @param section the whole section, its {@code CRC_32} included
     * @param delta the value to add, in 90 kHz ticks
     * @return a new section, or {@code section} itself when the value added is a multiple of 2^33 or the section
     * is too short to hold the field
     */
    static byte[] rebased(byte[] section, long delta) {
        if (section.length < 13 || Math.floorMod(delta, TsClock.WRAP) == 0) {
            return section;
        }
        var result = section.clone();
        var adjustment = (long) (result[4] & 0x01) << 32 | (result[5] & 0xFFL) << 24 | (result[6] & 0xFFL) << 16
                | (result[7] & 0xFFL) << 8 | (result[8] & 0xFFL);
        adjustment = Math.floorMod(adjustment + delta, TsClock.WRAP);
        result[4] = (byte) ((result[4] & 0xFE) | (int) (adjustment >>> 32));
        result[5] = (byte) (adjustment >>> 24);
        result[6] = (byte) (adjustment >>> 16);
        result[7] = (byte) (adjustment >>> 8);
        result[8] = (byte) adjustment;
        var crc = Crc32Mpeg.update(Crc32Mpeg.MPEG_INIT, result, 0, result.length - 4);
        result[result.length - 4] = (byte) (crc >>> 24);
        result[result.length - 3] = (byte) (crc >>> 16);
        result[result.length - 2] = (byte) (crc >>> 8);
        result[result.length - 1] = (byte) crc;
        return result;
    }
}
