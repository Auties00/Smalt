package com.github.auties00.smalt.container.ts;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.internal.codec.JxsConfigs;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.PacketFlag;
import com.github.auties00.smalt.track.PacketSideData;
import com.github.auties00.smalt.track.VideoTrack;

import java.util.Arrays;
import java.util.EnumSet;

/**
 * Splits a JPEG XS video stream carried as Rec. ITU-T H.222.0 Annex W specifies ({@code stream_type 0x32}) into
 * frames.
 *
 * <p>Each PES packet holds one access unit: a {@code jxes} elementary stream header followed by the codestream of a
 * progressive frame or the two field codestreams of an interlaced one. A packet is the access unit without its header;
 * every packet is a key frame. The track is known from the first access unit: its size from the codestream's picture
 * header, its frame rate, field order and color description from the {@code jxes} header, and its configuration (the
 * {@code jpvi} and {@code jxpl} boxes) from both. Access units before the first that starts with a {@code jxes}
 * header and a codestream are dropped. Timestamps are the PES ones on the 90 kHz timeline. Instances are not
 * thread-safe.
 */
final class JxsParser extends ElementaryParser {
    /**
     * Constructs a parser.
     *
     * @param entry the program map entry of the stream
     * @param limits the resource bounds
     */
    JxsParser(Psi.StreamEntry entry, MediaLimits limits) {
        super(entry, limits);
    }

    @Override
    ElementaryParser fresh() {
        var parser = new JxsParser(entry, limits);
        parser.track = track;
        return parser;
    }

    @Override
    void accept(Pes pes, TsClock clock, UnitSink sink) {
        var data = pes.data();
        var start = pes.offset();
        var end = pes.offset() + pes.length();
        if (!JxsConfigs.isJxes(data, start, end)) {
            return;
        }
        var header = JxsConfigs.jxesLength(data, start);
        var codestream = start + header;
        if (track == null) {
            var picture = JxsConfigs.picture(data, codestream, end - codestream);
            if (picture == null) {
                return;
            }
            var config = JxsConfigs.jxesConfig(data, start);
            var builder = VideoTrack.builder().id(entry.trackId()).codec(Codec.JPEG_XS)
                    .timeBase(NalParser.TIME_BASE).language(entry.language()).width(picture.pictureWidth())
                    .height(picture.pictureHeight()).color(JxsConfigs.jxesColor(data, start))
                    .codecConfig(JxsConfigs.config(config));
            if (config.frameRate() != null) {
                builder.frameRate(config.frameRate());
            }
            if (config.interlacing() != null) {
                builder.interlacing(config.interlacing());
            }
            track = builder.build();
        }
        var dts = pes.dts() == TsClock.NONE ? Packet.NO_TIMESTAMP : clock.ticks(trackId(), pes.dts(), true);
        var pts = pes.pts() == TsClock.NONE ? Packet.NO_TIMESTAMP
                : clock.ticks(trackId(), pes.pts(), dts == Packet.NO_TIMESTAMP);
        if (dts == Packet.NO_TIMESTAMP) {
            dts = pts;
        }
        var flags = EnumSet.of(PacketFlag.KEY);
        if (pes.corrupt()) {
            flags.add(PacketFlag.CORRUPT);
        }
        sink.accept(new Packet(trackId(), Arrays.copyOfRange(data, codestream, end), pts, dts,
                Packet.UNKNOWN_DURATION, flags, PacketSideData.NONE), pes.fileOffset());
    }

    @Override
    void flush(TsClock clock, UnitSink sink) {
    }
}
