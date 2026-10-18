package com.github.auties00.smalt.subtitle;

import com.github.auties00.smalt.internal.subtitle.AssEvents;
import com.github.auties00.smalt.internal.subtitle.TimedText;
import com.github.auties00.smalt.internal.subtitle.WebVttSamples;
import com.github.auties00.smalt.internal.subtitle.WebVttText;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.DataTrack;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.PacketFlag;
import com.github.auties00.smalt.track.PacketSideData;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.Track;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Set;

/**
 * Turns {@link SubtitleCue}s into the packets of a text subtitle track.
 *
 * <p>The cue text is converted from the markup it is written in to the markup of the track's
 * codec ({@link Subtitles#convert(SubtitleCue, SubtitleMarkup, SubtitleMarkup)}: bold, italic and underline
 * survive every conversion), then packed in the codec's packet form: UTF-8 text for
 * {@link Codec#SUBRIP}, the payload with the identifier and settings as side data for
 * {@link Codec#WEBVTT}, a 3GPP text sample with {@code styl} records for {@link Codec#MOV_TEXT},
 * and an event in the {@code Default} style, numbered in encoding order, for {@link Codec#ASS}. A
 * SubRip {@code {\anN}} placement becomes the matching WebVTT {@code line} and {@code align}
 * settings when a cue without settings is written to a WebVTT track. The packet starts at the
 * cue start and lasts until its end, in the track's time base.
 *
 * <p>An instance is not thread-safe.
 */
public final class SubtitleEncoder {
    /**
     * The time base of the tracks {@link #track(Codec, int)} creates.
     */
    private static final Rational TIME_BASE = Rational.MILLISECONDS;

    /**
     * The flags of every packet: subtitles are always random access points.
     */
    private static final Set<PacketFlag> KEY = Set.of(PacketFlag.KEY);

    /**
     * The track encoded.
     */
    private final Track track;

    /**
     * The read order of the next ASS event.
     */
    private long readOrder;

    /**
     * Constructs an encoder for a track.
     *
     * @param track the track whose packets are produced; its codec must be a text subtitle codec
     * @throws NullPointerException     if {@code track} is {@code null}
     * @throws IllegalArgumentException if the track's codec is not a text subtitle codec
     */
    public SubtitleEncoder(Track track) {
        this.track = Objects.requireNonNull(track, "track");
        if (!track.codec().isSubtitle()) {
            throw new IllegalArgumentException(track.codec().displayName() + " is not a text subtitle codec");
        }
    }

    /**
     * Creates a subtitle track with the configuration its codec needs.
     *
     * <p>The track has a millisecond time base. A WebVTT track gets the bare {@code WEBVTT}
     * header, a 3GPP timed text track the default {@code tx3g} description (bottom center, white
     * 16-pixel {@code Arial}), an ASS track a script header with one
     * {@code Default} style; a SubRip track needs no configuration.
     *
     * @param codec the text subtitle codec
     * @param id    the track identifier
     * @return the track
     * @throws NullPointerException     if {@code codec} is {@code null}
     * @throws IllegalArgumentException if {@code codec} is not a text subtitle codec
     */
    public static DataTrack track(Codec codec, int id) {
        Objects.requireNonNull(codec, "codec");
        byte[] config = switch (codec) {
            case SUBRIP -> null;
            case WEBVTT -> WebVttText.DEFAULT_HEADER.getBytes(StandardCharsets.UTF_8);
            case MOV_TEXT -> TimedText.defaultConfig();
            case ASS -> AssEvents.DEFAULT_HEADER.getBytes(StandardCharsets.UTF_8);
            default -> throw new IllegalArgumentException(codec.displayName() + " is not a text subtitle codec");
        };
        return DataTrack.builder().id(id).codec(codec).timeBase(TIME_BASE).codecConfig(config).build();
    }

    /**
     * Returns the track encoded.
     *
     * @return the track
     */
    public Track track() {
        return track;
    }

    /**
     * Encodes one cue.
     *
     * @param cue    the cue to encode
     * @param markup the markup convention of the cue text
     * @return the packet, carrying the track's identifier
     * @throws ArithmeticException if a cue time does not fit the track's time base
     */
    public Packet encode(SubtitleCue cue, SubtitleMarkup markup) {
        Objects.requireNonNull(cue, "cue");
        Objects.requireNonNull(markup, "markup");
        var codec = track.codec();
        var converted = Subtitles.convert(cue, markup, Subtitles.markupOf(codec));
        var text = converted.text();
        var timeBase = track.timeBase();
        var pts = Rational.MICROSECONDS.rescale(cue.startMicros(), timeBase);
        var duration = Math.max(0, Rational.MICROSECONDS.rescale(cue.endMicros(), timeBase) - pts);
        var sideData = PacketSideData.NONE;
        byte[] data;
        switch (codec) {
            case WEBVTT -> {
                sideData = WebVttSamples.sideData(sideData, converted.identifier(), converted.settings());
                data = SubtitleDecoder.bytes(text);
            }
            case MOV_TEXT -> data = TimedText.encodeText(text);
            case ASS -> data = SubtitleDecoder.bytes(AssEvents.event(readOrder++, text));
            default -> data = SubtitleDecoder.bytes(text);
        }
        return new Packet(track.id(), data, pts, pts, duration, KEY, sideData);
    }
}
