package com.github.auties00.smalt.container.ts;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.DataTrack;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.PacketFlag;
import com.github.auties00.smalt.track.PacketSideData;
import com.github.auties00.smalt.track.TrackKind;
import com.github.auties00.smalt.track.VideoTrack;

import java.util.Arrays;
import java.util.EnumSet;

/**
 * Passes every PES payload of a stream through as one packet: ID3 timed metadata, private data,
 * and the streams whose coding this module does not parse.
 *
 * <p>The track is known from the program map entry alone: a {@link DataTrack}, or a video or
 * audio track of {@link Codec#UNKNOWN_VIDEO}, {@link Codec#UNKNOWN_AUDIO} or
 * {@link Codec#MPEG4_PART2} for the video and audio stream types without a parser. Timestamps are
 * the PES ones on the 90 kHz timeline ({@link Packet#NO_TIMESTAMP} when the PES packet has none).
 * A data or audio packet is always a key frame; a video packet is one when its PES packet started
 * with the random access indicator.
 */
final class PesParser extends ElementaryParser {
    /**
     * The handler of an ID3 timed metadata track.
     */
    static final String ID3_HANDLER = "id3";

    /**
     * Constructs a parser whose track is known.
     *
     * @param entry the program map entry of the stream
     * @param limits the resource bounds
     * @param codec the codec of the track: {@link Codec#DATA} or an unparsed video or audio codec
     * @param handler the handler of a data track, or {@code null}
     */
    PesParser(Psi.StreamEntry entry, MediaLimits limits, Codec codec, String handler) {
        super(entry, limits);
        this.track = switch (codec.kind()) {
            case VIDEO -> VideoTrack.builder().id(entry.pid()).codec(codec).timeBase(NalParser.TIME_BASE)
                    .language(entry.language()).build();
            case AUDIO -> AudioTrack.builder().id(entry.pid()).codec(codec).timeBase(NalParser.TIME_BASE)
                    .language(entry.language()).build();
            case DATA -> DataTrack.builder().id(entry.pid()).codec(codec).timeBase(NalParser.TIME_BASE)
                    .language(entry.language()).handler(handler).build();
        };
    }

    /**
     * Constructs a parser sharing the track of another.
     *
     * @param other the parser
     */
    private PesParser(PesParser other) {
        super(other.entry, other.limits);
        this.track = other.track;
    }

    @Override
    ElementaryParser fresh() {
        return new PesParser(this);
    }

    @Override
    void accept(Pes pes, TsClock clock, UnitSink sink) {
        if (pes.length() == 0) {
            return;
        }
        var dts = pes.dts() == TsClock.NONE ? Packet.NO_TIMESTAMP : clock.ticks(pid(), pes.dts(), true);
        var pts = pes.pts() == TsClock.NONE ? Packet.NO_TIMESTAMP : clock.ticks(pid(), pes.pts(), dts == Packet.NO_TIMESTAMP);
        if (dts == Packet.NO_TIMESTAMP) {
            dts = pts;
        }
        var flags = EnumSet.noneOf(PacketFlag.class);
        if (track.kind() != TrackKind.VIDEO || pes.randomAccess()) {
            flags.add(PacketFlag.KEY);
        }
        if (pes.corrupt()) {
            flags.add(PacketFlag.CORRUPT);
        }
        var payload = Arrays.copyOfRange(pes.data(), pes.offset(), pes.offset() + pes.length());
        sink.accept(new Packet(pid(), payload, pts, dts, Packet.UNKNOWN_DURATION, flags, PacketSideData.NONE), pes.fileOffset());
    }

    @Override
    void flush(TsClock clock, UnitSink sink) {
    }
}
