package com.github.auties00.smalt.container.ts;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.internal.subtitle.bitmap.DvdSubtitleStream;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.DataTrack;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.PacketFlag;
import com.github.auties00.smalt.track.PacketSideData;

import java.util.Arrays;
import java.util.Set;

/**
 * Reassembles the subpicture units of a DVD-Video subpicture stream ({@code sub_stream_id 0x20} to {@code 0x3F}
 * of private stream 1) into packets of {@link Codec#DVD_SUBTITLE}.
 *
 * <p>A unit starts with its 16-bit size and may span PES packets; the PES packet that starts it gives its
 * presentation time, on the 90 kHz timeline, and a unit lasts until its first stop display command when it has one.
 * A unit that a PES packet with a timestamp interrupts, or whose declared size is below four bytes, is dropped. The
 * track is known from the stream alone and has no configuration: a program stream does not carry the palette, which
 * DVD-Video keeps in its IFO files. Every packet is a key frame. Instances are not thread-safe.
 */
final class SubpictureParser extends ElementaryParser {
    /**
     * The flags of an intact unit.
     */
    private static final Set<PacketFlag> KEY = Set.of(PacketFlag.KEY);

    /**
     * The flags of a unit a damaged PES packet contributed to.
     */
    private static final Set<PacketFlag> KEY_CORRUPT = Set.of(PacketFlag.KEY, PacketFlag.CORRUPT);

    /**
     * The bytes of the unit being reassembled.
     */
    private byte[] unit = new byte[0];

    /**
     * The number of bytes of {@link #unit} collected.
     */
    private int collected;

    /**
     * The presentation time of the unit being reassembled, in 90 kHz ticks from the origin, or {@link TsClock#NONE}.
     */
    private long pts = TsClock.NONE;

    /**
     * The file offset of the PES packet that started the unit being reassembled.
     */
    private long unitOffset;

    /**
     * Whether a damaged PES packet contributed to the unit being reassembled.
     */
    private boolean corrupt;

    /**
     * Constructs a parser.
     *
     * @param entry the stream entry
     * @param limits the resource bounds
     */
    SubpictureParser(Psi.StreamEntry entry, MediaLimits limits) {
        super(entry, limits);
        track = DataTrack.builder().id(entry.trackId()).codec(Codec.DVD_SUBTITLE).timeBase(NalParser.TIME_BASE)
                .language(entry.language()).build();
    }

    @Override
    ElementaryParser fresh() {
        var parser = new SubpictureParser(entry, limits);
        parser.track = track;
        return parser;
    }

    @Override
    void accept(Pes pes, TsClock clock, UnitSink sink) {
        var data = pes.data();
        var position = pes.offset();
        var end = position + pes.length();
        if (pes.pts() != TsClock.NONE) {
            collected = 0;
            pts = clock.ticks(trackId(), pes.pts(), true);
            unitOffset = pes.fileOffset();
            corrupt = false;
        } else if (collected == 0) {
            return;
        }
        corrupt |= pes.corrupt();
        while (position < end) {
            if (collected < 2) {
                append(data, position, 1);
                position++;
                continue;
            }
            var size = (unit[0] & 0xFF) << 8 | (unit[1] & 0xFF);
            if (size < 4) {
                collected = 0;
                pts = TsClock.NONE;
                return;
            }
            var take = Math.min(size - collected, end - position);
            append(data, position, take);
            position += take;
            if (collected == size) {
                emit(sink);
                if (position < end && pts == TsClock.NONE) {
                    return;
                }
            }
        }
    }

    /**
     * Appends bytes to the unit being reassembled.
     *
     * @param data the bytes
     * @param from the index of the first byte
     * @param length the number of bytes
     */
    private void append(byte[] data, int from, int length) {
        if (unit.length < collected + length) {
            var size = collected + length;
            LimitChecks.checkAllocation(limits, size);
            unit = Arrays.copyOf(unit, Math.max(size, Math.min(2 * unit.length, 0x10000)));
        }
        System.arraycopy(data, from, unit, collected, length);
        collected += length;
    }

    /**
     * Passes the completed unit on.
     *
     * @param sink receives the packet
     */
    private void emit(UnitSink sink) {
        var payload = Arrays.copyOf(unit, collected);
        collected = 0;
        if (pts == TsClock.NONE) {
            return;
        }
        var stop = DvdSubtitleStream.stopTicks(payload);
        sink.accept(new Packet(trackId(), payload, pts, pts, stop >= 0 ? stop : Packet.UNKNOWN_DURATION,
                corrupt ? KEY_CORRUPT : KEY, PacketSideData.NONE), unitOffset);
        pts = TsClock.NONE;
    }

    @Override
    void flush(TsClock clock, UnitSink sink) {
        collected = 0;
        pts = TsClock.NONE;
    }
}
