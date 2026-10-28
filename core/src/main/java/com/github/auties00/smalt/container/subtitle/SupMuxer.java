package com.github.auties00.smalt.container.subtitle;

import com.github.auties00.smalt.container.Muxer;
import com.github.auties00.smalt.container.internal.ConfigChanges;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.Track;

import java.nio.ByteBuffer;
import java.util.Objects;

/**
 * Writes a Blu-ray presentation graphic stream file ({@code .sup}) from one {@link Codec#PGS} track.
 *
 * <p>Each segment of each packet is written behind its 13-byte header: the magic {@code "PG"}, the packet's
 * presentation timestamp on the 90 kHz clock (the low 32 bits, as the format stores it), a decoding timestamp of
 * zero, and the segment type and length the packet holds. A packet whose segments run past its end is written up to
 * its last complete segment. A packet without a presentation timestamp takes its decoding timestamp, or the
 * previous packet's time. Packets must come in file order; the muxer buffers nothing.
 */
public final class SupMuxer implements Muxer {
    private static final Rational TICKS = Rational.timeBaseOf(90_000);

    private final MediaSink output;
    private Track track;
    private long previous;
    private boolean started;
    private boolean finished;

    /**
     * Constructs a muxer writing to a sink.
     *
     * @param output the destination, written from its position; not closed
     */
    public SupMuxer(MediaSink output) {
        this.output = Objects.requireNonNull(output, "output");
    }

    /**
     * Returns whether the muxer can carry a codec.
     *
     * @param codec the codec of the track
     * @return {@code true} for {@link Codec#PGS}
     */
    public static boolean supports(Codec codec) {
        return Objects.requireNonNull(codec, "codec") == Codec.PGS;
    }

    /**
     * {@inheritDoc}
     *
     * @throws IllegalArgumentException if the track is not a PGS track or a track is already declared
     * @throws IllegalStateException if a packet was already written or the muxer is finished
     */
    @Override
    public void addTrack(Track track) {
        Objects.requireNonNull(track, "track");
        if (started || finished) {
            throw new IllegalStateException("tracks must be declared before the first packet");
        }
        if (this.track != null) {
            throw new IllegalArgumentException("a .sup file holds one track");
        }
        if (track.codec() != Codec.PGS) {
            throw new IllegalArgumentException(".sup files cannot carry " + track.codec().displayName());
        }
        this.track = track;
    }

    /**
     * {@inheritDoc}
     *
     * @throws IllegalArgumentException if the packet's track was not declared
     * @throws IllegalStateException if the muxer is finished
     */
    @Override
    public void writePacket(Packet packet) {
        Objects.requireNonNull(packet, "packet");
        if (finished) {
            throw new IllegalStateException("the muxer is finished");
        }
        if (track == null || packet.trackId() != track.id()) {
            throw new IllegalArgumentException("packet for undeclared track " + packet.trackId());
        }
        ConfigChanges.reject(packet, null, "a .sup file");
        started = true;
        var time = packet.pts() != Packet.NO_TIMESTAMP ? packet.pts() : packet.dts();
        var ticks = time == Packet.NO_TIMESTAMP ? previous : track.timeBase().rescale(time, TICKS);
        previous = ticks;
        var data = packet.data();
        var position = 0;
        var segments = 0;
        while (position + 3 <= data.length) {
            var length = (data[position + 1] & 0xFF) << 8 | (data[position + 2] & 0xFF);
            if (position + 3 + length > data.length) {
                break;
            }
            position += 3 + length;
            segments++;
        }
        var out = ByteBuffer.allocate(position + segments * 10);
        position = 0;
        for (var segment = 0; segment < segments; segment++) {
            var length = (data[position + 1] & 0xFF) << 8 | (data[position + 2] & 0xFF);
            out.put((byte) 'P').put((byte) 'G').putInt((int) ticks).putInt(0);
            out.put(data, position, 3 + length);
            position += 3 + length;
        }
        out.flip();
        output.write(out);
    }

    @Override
    public void finish() {
        if (finished) {
            throw new IllegalStateException("the muxer is already finished");
        }
        finished = true;
    }

    @Override
    public void close() {
        finished = true;
    }
}
