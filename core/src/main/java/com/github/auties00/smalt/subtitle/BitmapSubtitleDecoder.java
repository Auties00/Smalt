package com.github.auties00.smalt.subtitle;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.internal.limits.Guards;
import com.github.auties00.smalt.internal.subtitle.bitmap.BitmapStream;
import com.github.auties00.smalt.internal.subtitle.bitmap.DvbSubtitleStream;
import com.github.auties00.smalt.internal.subtitle.bitmap.DvdSubtitleStream;
import com.github.auties00.smalt.internal.subtitle.bitmap.PgsStream;
import com.github.auties00.smalt.internal.subtitle.bitmap.VobSubHeader;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.Track;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Turns the packets of a bitmap subtitle track into {@link BitmapCue}s.
 *
 * <p>The decoder reads the packet forms of {@link Codec#PGS}, {@link Codec#DVB_SUBTITLE} and
 * {@link Codec#DVD_SUBTITLE}, in presentation order, and keeps the state the formats build across packets (the
 * palettes, objects and regions of an epoch). A cue is complete once its end is known, so {@link #decode(Packet)}
 * returns the cues that end at or before the packet: the screen a display set replaces or clears, a screen whose
 * own deadline (a DVD stop command, a DVB page time-out, the packet's duration when the container gives one) has
 * passed. {@link #flush()} returns the cue still shown after the last packet. A cue starts at the time its display
 * set is shown: the presentation timestamp of the packet that composed it (its decoding timestamp when it has
 * none), plus the delay of a DVD start command. Consecutive screens with the same pictures are one cue, and a
 * screen replaced at the instant it appears yields no cue.
 *
 * <p>Pictures are converted to sRGB with straight alpha: PGS palettes from BT.709 (BT.601 for a composition of at
 * most 576 lines), DVB CLUTs from BT.601, both limited range; DVD palette entries are already RGB.
 *
 * <p>An instance is not thread-safe.
 */
public final class BitmapSubtitleDecoder {
    private final Track track;
    private final BitmapStream stream;

    /**
     * The screen shown, or {@code null}.
     */
    private Pending pending;

    /**
     * The latest time a packet covers, in microseconds.
     */
    private long latest = Long.MIN_VALUE;

    /**
     * Constructs a decoder for a track with the default limits.
     *
     * @param track the track; its codec must be a bitmap subtitle codec
     * @throws IllegalArgumentException if the track's codec is not a bitmap subtitle codec
     */
    public BitmapSubtitleDecoder(Track track) {
        this(track, MediaLimits.defaults());
    }

    /**
     * Constructs a decoder for a track.
     *
     * @param track the track; its codec must be a bitmap subtitle codec
     * @param limits the bounds of the canvas and the pictures
     * @throws IllegalArgumentException if the track's codec is not a bitmap subtitle codec
     */
    public BitmapSubtitleDecoder(Track track, MediaLimits limits) {
        this.track = Objects.requireNonNull(track, "track");
        Objects.requireNonNull(limits, "limits");
        var config = track.codecConfig().orElse(null);
        this.stream = switch (track.codec()) {
            case PGS -> new PgsStream(limits);
            case DVB_SUBTITLE -> config != null && config.length >= 8
                    ? new DvbSubtitleStream(limits, (config[4] & 0xFF) << 8 | (config[5] & 0xFF),
                    (config[6] & 0xFF) << 8 | (config[7] & 0xFF))
                    : new DvbSubtitleStream(limits, -1, -1);
            case DVD_SUBTITLE -> new DvdSubtitleStream(limits, VobSubHeader.parse(config));
            default -> throw new IllegalArgumentException(track.codec().displayName()
                    + " is not a bitmap subtitle codec");
        };
    }

    /**
     * Returns the codec of the track.
     *
     * @return the codec
     */
    public Codec codec() {
        return track.codec();
    }

    /**
     * Decodes one packet.
     *
     * @param packet the next packet of the track, in presentation order
     * @return the cues that ended by the time of the packet or the screens it shows, in start order; often empty
     * @throws MediaException.Malformed if the packet is corrupt beyond what the decoder skips
     * @throws MediaException.LimitExceeded if a canvas or picture exceeds the limits
     */
    public List<BitmapCue> decode(Packet packet) {
        Objects.requireNonNull(packet, "packet");
        return Guards.malformed(track.codec().displayName() + " packet", () -> decodePacket(packet));
    }

    /**
     * Decodes one packet, unguarded.
     *
     * @param packet the packet of the track to decode
     * @return the cues that ended
     */
    private List<BitmapCue> decodePacket(Packet packet) {
        var time = packet.pts() != Packet.NO_TIMESTAMP ? packet.pts() : packet.dts();
        var micros = time == Packet.NO_TIMESTAMP ? Math.max(latest, 0) : micros(time);
        var cap = packet.duration() <= 0 ? BitmapStream.NO_DEADLINE : time == Packet.NO_TIMESTAMP
                ? micros + micros(packet.duration()) : micros(saturatingAdd(time, packet.duration()));
        latest = Math.max(latest, cap != BitmapStream.NO_DEADLINE ? cap : micros);
        var result = new ArrayList<BitmapCue>();
        expire(micros, result);
        stream.decode(packet.data(), micros, new BitmapStream.Sink() {
            @Override
            public void show(long at, BitmapStream.Screen screen, long deadline) {
                expire(at, result);
                var end = cap == BitmapStream.NO_DEADLINE ? deadline
                        : deadline == BitmapStream.NO_DEADLINE ? cap : Math.min(deadline, cap);
                var next = new Pending(at, screen, end);
                if (pending != null) {
                    if (pending.sameScreen(next)) {
                        pending = new Pending(pending.start, pending.screen, end);
                        return;
                    }
                    emit(pending, at, result);
                }
                pending = next;
            }

            @Override
            public void clear(long at) {
                expire(at, result);
                if (pending != null) {
                    emit(pending, at, result);
                    pending = null;
                }
            }
        });
        return result;
    }

    /**
     * Ends the cue still shown after the last packet.
     *
     * <p>The cue ends at its deadline when it has one, otherwise at the latest time a packet covered (its
     * presentation time plus its duration when known); a cue that would end where it starts is dropped. The
     * decoder then starts afresh, as after {@link #reset()}.
     *
     * @return the cue, or an empty list when nothing is shown
     */
    public List<BitmapCue> flush() {
        var result = new ArrayList<BitmapCue>();
        if (pending != null) {
            emit(pending, pending.deadline != BitmapStream.NO_DEADLINE ? pending.deadline : latest, result);
        }
        reset();
        return result;
    }

    /**
     * Discards the screen shown and the state built from earlier packets, as after a seek.
     */
    public void reset() {
        pending = null;
        latest = Long.MIN_VALUE;
        stream.reset();
    }

    /**
     * Emits the shown screen whose deadline has passed by a time.
     */
    private void expire(long micros, List<BitmapCue> result) {
        if (pending != null && pending.deadline != BitmapStream.NO_DEADLINE && pending.deadline <= micros) {
            emit(pending, pending.deadline, result);
            pending = null;
        }
    }

    /**
     * Adds the cue of a screen that ends at a time, unless it would be empty.
     */
    private static void emit(Pending shown, long end, List<BitmapCue> result) {
        if (end > shown.start) {
            var screen = shown.screen;
            result.add(new BitmapCue(shown.start, end, screen.width(), screen.height(), screen.regions(),
                    screen.forced()));
        }
    }

    private static long saturatingAdd(long a, long b) {
        var sum = a + b;
        return ((a ^ sum) & (b ^ sum)) < 0 ? (a < 0 ? Long.MIN_VALUE : Long.MAX_VALUE) : sum;
    }

    private long micros(long value) {
        Rational timeBase = track.timeBase();
        try {
            return timeBase.toMicros(value);
        } catch (ArithmeticException exception) {
            return value < 0 ? Long.MIN_VALUE / 2 : Long.MAX_VALUE / 2;
        }
    }

    /**
     * Holds the screen shown.
     *
     * @param start the time it appeared, in microseconds
     * @param screen the pictures
     * @param deadline its latest end, or {@link BitmapStream#NO_DEADLINE}
     */
    private record Pending(long start, BitmapStream.Screen screen, long deadline) {
        boolean sameScreen(Pending other) {
            var a = screen;
            var b = other.screen;
            return a.width() == b.width() && a.height() == b.height() && a.forced() == b.forced()
                    && new BitmapCue(0, 0, a.width(), a.height(), a.regions(), false)
                    .equals(new BitmapCue(0, 0, b.width(), b.height(), b.regions(), false));
        }
    }
}
