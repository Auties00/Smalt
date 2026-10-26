package com.github.auties00.smalt.container.subtitle;

import com.github.auties00.smalt.container.Muxer;
import com.github.auties00.smalt.container.internal.BitmapTrackConfigs;
import com.github.auties00.smalt.container.internal.ConfigChanges;
import com.github.auties00.smalt.container.internal.Languages;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.Track;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.MissingResourceException;
import java.util.Objects;

/**
 * Writes VobSub subtitles: an MPEG program stream ({@code .sub}) and its index ({@code .idx}) from up to 32
 * {@link Codec#DVD_SUBTITLE} tracks.
 *
 * <p>The tracks become the subpicture streams {@code 0x20}, {@code 0x21} and on, in the order they are declared.
 * Each packet, a whole subpicture unit, is written to the program stream as soon as it arrives, in 2048-byte packs
 * whose first PES packet carries the packet's presentation timestamp, padded with a padding stream. The index is
 * written by {@link #finish()}: the header of the first track's configuration (a default 720 by 480 header with a
 * gray palette when it has none), then, for every track, an {@code id} line with its language and one
 * {@code timestamp} line per packet with the file position of its first pack.
 */
public final class VobSubMuxer implements Muxer {
    private static final int PACK = 2048;
    private static final Rational TICKS = Rational.timeBaseOf(90_000);

    private final MediaSink idx;
    private final MediaSink sub;
    private final List<Track> tracks = new ArrayList<>();
    private final List<List<long[]>> entries = new ArrayList<>();
    private long written;
    private boolean started;
    private boolean finished;

    /**
     * Constructs a muxer writing a file pair.
     *
     * @param idx the destination of the index, written at {@link #finish()} from its position; not closed
     * @param sub the destination of the program stream, written from its position; not closed
     */
    public VobSubMuxer(MediaSink idx, MediaSink sub) {
        this.idx = Objects.requireNonNull(idx, "idx");
        this.sub = Objects.requireNonNull(sub, "sub");
    }

    /**
     * Returns whether the muxer can carry a codec.
     *
     * @param codec the codec of a track
     * @return {@code true} for {@link Codec#DVD_SUBTITLE}
     */
    public static boolean supports(Codec codec) {
        return Objects.requireNonNull(codec, "codec") == Codec.DVD_SUBTITLE;
    }

    /**
     * {@inheritDoc}
     *
     * @throws IllegalArgumentException if the track is not a DVD subtitle track, its identifier is already used or
     * 32 tracks are already declared
     * @throws IllegalStateException if a packet was already written or the muxer is finished
     */
    @Override
    public void addTrack(Track track) {
        Objects.requireNonNull(track, "track");
        if (started || finished) {
            throw new IllegalStateException("tracks must be declared before the first packet");
        }
        if (track.codec() != Codec.DVD_SUBTITLE) {
            throw new IllegalArgumentException("VobSub files cannot carry " + track.codec().displayName());
        }
        for (var other : tracks) {
            if (other.id() == track.id()) {
                throw new IllegalArgumentException("track id " + track.id() + " is already declared");
            }
        }
        if (tracks.size() >= 32) {
            throw new IllegalArgumentException("a VobSub file holds at most 32 subpicture streams");
        }
        tracks.add(track);
        entries.add(new ArrayList<>());
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
        var stream = -1;
        for (var index = 0; index < tracks.size(); index++) {
            if (tracks.get(index).id() == packet.trackId()) {
                stream = index;
            }
        }
        if (stream < 0) {
            throw new IllegalArgumentException("packet for undeclared track " + packet.trackId());
        }
        var track = tracks.get(stream);
        ConfigChanges.reject(packet, track.codecConfig().orElse(null), "a VobSub file");
        started = true;
        var time = packet.pts() != Packet.NO_TIMESTAMP ? packet.pts() : packet.dts();
        var ticks = time == Packet.NO_TIMESTAMP ? 0 : Math.max(0, track.timeBase().rescale(time, TICKS));
        entries.get(stream).add(new long[]{ticks, written});
        var data = packet.data();
        var position = 0;
        var first = true;
        do {
            var buffer = ByteBuffer.allocate(PACK);
            pack(buffer, ticks);
            var header = first ? 5 : 0;
            var room = PACK - 14 - 9 - header - 1;
            var take = Math.min(room, data.length - position);
            var left = room - take;
            var stuffing = left > 0 && left < 6 ? left : 0;
            buffer.put((byte) 0).put((byte) 0).put((byte) 1).put((byte) 0xBD);
            buffer.putShort((short) (3 + header + stuffing + 1 + take));
            buffer.put((byte) 0x81).put((byte) (first ? 0x80 : 0)).put((byte) (header + stuffing));
            if (first) {
                timestamp(buffer, ticks);
            }
            for (var index = 0; index < stuffing; index++) {
                buffer.put((byte) 0xFF);
            }
            buffer.put((byte) (0x20 + stream));
            buffer.put(data, position, take);
            position += take;
            var padding = PACK - buffer.position();
            if (padding >= 6) {
                buffer.put((byte) 0).put((byte) 0).put((byte) 1).put((byte) 0xBE).putShort((short) (padding - 6));
                while (buffer.hasRemaining()) {
                    buffer.put((byte) 0xFF);
                }
            }
            buffer.flip();
            sub.write(buffer);
            written += PACK;
            first = false;
        } while (position < data.length);
    }

    /**
     * Writes an MPEG-2 pack header whose system clock reference is a time.
     */
    private static void pack(ByteBuffer buffer, long ticks) {
        var scr = ticks & 0x1FFFFFFFFL;
        buffer.put((byte) 0).put((byte) 0).put((byte) 1).put((byte) 0xBA);
        buffer.put((byte) (0x44 | (scr >> 27 & 0x38) | (scr >> 28 & 0x03)));
        buffer.put((byte) (scr >> 20));
        buffer.put((byte) ((scr >> 12 & 0xF8) | 0x04 | (scr >> 13 & 0x03)));
        buffer.put((byte) (scr >> 5));
        buffer.put((byte) ((scr << 3 & 0xF8) | 0x04));
        buffer.put((byte) 0x01);
        var muxRate = 25_200;
        buffer.put((byte) (muxRate >> 14)).put((byte) (muxRate >> 6)).put((byte) ((muxRate << 2) | 0x03));
        buffer.put((byte) 0xF8);
    }

    /**
     * Writes a PES presentation timestamp field.
     */
    private static void timestamp(ByteBuffer buffer, long ticks) {
        var time = ticks & 0x1FFFFFFFFL;
        buffer.put((byte) (0x21 | (time >> 29 & 0x0E)));
        buffer.putShort((short) ((time >> 14 & 0xFFFE) | 1));
        buffer.putShort((short) ((time << 1 & 0xFFFE) | 1));
    }

    @Override
    public void finish() {
        if (finished) {
            throw new IllegalStateException("the muxer is already finished");
        }
        finished = true;
        var config = tracks.isEmpty() ? null : tracks.getFirst().codecConfig().orElse(null);
        var header = new String(BitmapTrackConfigs.vobSubHeader(config), StandardCharsets.UTF_8);
        var text = new StringBuilder();
        if (!header.startsWith("# VobSub index file")) {
            text.append(BitmapTrackConfigs.VOBSUB_SIGNATURE).append('\n');
        }
        text.append(header);
        if (!header.endsWith("\n")) {
            text.append('\n');
        }
        for (var stream = 0; stream < tracks.size(); stream++) {
            text.append('\n').append("id: ").append(twoLetter(tracks.get(stream).language().orElse(null)))
                    .append(", index: ").append(stream).append('\n');
            for (var entry : entries.get(stream)) {
                var millis = entry[0] / 90;
                text.append(String.format(Locale.ROOT, "timestamp: %02d:%02d:%02d:%03d, filepos: %09x",
                        millis / 3_600_000, millis / 60_000 % 60, millis / 1000 % 60, millis % 1000, entry[1]))
                        .append('\n');
            }
        }
        idx.write(ByteBuffer.wrap(text.toString().getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * Returns the two-letter code of a language, or {@code --} when it has none.
     */
    private static String twoLetter(String language) {
        if (language == null || language.length() != 3 || language.equals("und")) {
            return "--";
        }
        for (var code : Locale.getISOLanguages()) {
            var locale = Locale.of(code);
            try {
                if (locale.getISO3Language().equals(language)
                        || Languages.bibliographicCode(code).equals(language)) {
                    return code;
                }
            } catch (MissingResourceException ignored) {
                // a code without a three-letter form cannot match
            }
        }
        return "--";
    }

    @Override
    public void close() {
        finished = true;
    }
}
