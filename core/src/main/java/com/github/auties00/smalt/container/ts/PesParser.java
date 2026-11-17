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
 * <p>DVB subtitle payloads lose their {@code data_identifier}, {@code subtitle_stream_id} and end marker, so that
 * a packet holds the subtitling segments alone; other payloads pass unchanged.
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
     * Whether the payloads are DVB subtitling PES data fields, whose {@code data_identifier},
     * {@code subtitle_stream_id} and end marker are removed.
     */
    private final boolean dvbSubtitle;

    /**
     * Constructs a parser whose track is known.
     *
     * @param entry the program map entry of the stream
     * @param limits the resource bounds
     * @param codec the codec of the track: {@link Codec#DATA} or an unparsed video or audio codec
     * @param handler the handler of a data track, or {@code null}
     */
    PesParser(Psi.StreamEntry entry, MediaLimits limits, Codec codec, String handler) {
        this(entry, limits, codec, handler, null);
    }

    /**
     * Constructs a parser whose track is known and has a configuration.
     *
     * @param entry the program map entry of the stream
     * @param limits the resource bounds
     * @param codec the codec of the track: a data codec or an unparsed video or audio codec
     * @param handler the handler of a data track, or {@code null}
     * @param config the configuration of a data track, or {@code null}
     */
    PesParser(Psi.StreamEntry entry, MediaLimits limits, Codec codec, String handler, byte[] config) {
        super(entry, limits);
        this.dvbSubtitle = codec == Codec.DVB_SUBTITLE;
        this.track = switch (codec.kind()) {
            case VIDEO -> VideoTrack.builder().id(entry.trackId()).codec(codec).timeBase(NalParser.TIME_BASE)
                    .language(entry.language()).build();
            case AUDIO -> AudioTrack.builder().id(entry.trackId()).codec(codec).timeBase(NalParser.TIME_BASE)
                    .language(entry.language()).build();
            case DATA -> DataTrack.builder().id(entry.trackId()).codec(codec).timeBase(NalParser.TIME_BASE)
                    .language(entry.language()).handler(handler).codecConfig(config).build();
        };
    }

    /**
     * Constructs a parser sharing the track of another.
     *
     * @param other the parser
     */
    private PesParser(PesParser other) {
        super(other.entry, other.limits);
        this.dvbSubtitle = other.dvbSubtitle;
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
        var dts = pes.dts() == TsClock.NONE ? Packet.NO_TIMESTAMP : clock.ticks(trackId(), pes.dts(), true);
        var pts = pes.pts() == TsClock.NONE ? Packet.NO_TIMESTAMP : clock.ticks(trackId(), pes.pts(), dts == Packet.NO_TIMESTAMP);
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
        var start = pes.offset();
        var end = pes.offset() + pes.length();
        if (dvbSubtitle) {
            var data = pes.data();
            if (end - start >= 2 && (data[start] & 0xFF) == 0x20) {
                start += 2;
            }
            end = start + segmentsLength(data, start, end);
            if (end == start) {
                return;
            }
        }
        var payload = Arrays.copyOfRange(pes.data(), start, end);
        sink.accept(new Packet(trackId(), payload, pts, dts, Packet.UNKNOWN_DURATION, flags, PacketSideData.NONE), pes.fileOffset());
    }

    @Override
    void flush(TsClock clock, UnitSink sink) {
    }

    /**
     * Returns the length of the complete subtitling segments at the start of a DVB subtitling payload, which drops
     * the end marker and anything after it.
     *
     * @param data the bytes
     * @param start the index of the first segment
     * @param end the index after the payload
     * @return the number of bytes the segments span
     */
    static int segmentsLength(byte[] data, int start, int end) {
        var position = start;
        while (position + 6 <= end && (data[position] & 0xFF) == 0x0F) {
            var length = (data[position + 4] & 0xFF) << 8 | (data[position + 5] & 0xFF);
            if (position + 6 + length > end) {
                break;
            }
            position += 6 + length;
        }
        return position - start;
    }
}
