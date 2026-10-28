package com.github.auties00.smalt.subtitle;

import com.github.auties00.smalt.internal.subtitle.AssEvents;
import com.github.auties00.smalt.internal.subtitle.AssScript;
import com.github.auties00.smalt.internal.subtitle.Markup;
import com.github.auties00.smalt.internal.subtitle.TextFiles;
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
 * <p>The cue is converted from the markup it is written in to the markup of the track's codec
 * ({@link Subtitles#convert(SubtitleCue, SubtitleMarkup, SubtitleMarkup)}), then packed in the codec's
 * packet form: UTF-8 text for {@link Codec#SUBRIP}, the payload with the identifier and settings as side
 * data for {@link Codec#WEBVTT}, a 3GPP text sample for {@link Codec#MOV_TEXT} (its styles, colors,
 * fonts the track's font table names, sizes, highlight and karaoke as {@code styl}, {@code hlit},
 * {@code hclr} and {@code krok} boxes), and an event for {@link Codec#ASS}, numbered in encoding order: an
 * ASS cue keeps its event fields (its settings), a cue of another markup goes to the {@code Default}
 * style, its speaker in the {@code Name} field, its placement and font sizes scaled to the script
 * resolution. The packet starts at the cue start and lasts until its end, in the track's time base.
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
     * The script of an ASS track, or {@code null}.
     */
    private final AssScript script;

    /**
     * The sample entry of a 3GPP timed text track, or {@code null}.
     */
    private final TimedText.Config timedText;

    /**
     * The read order of the next ASS event.
     */
    private long readOrder;

    /**
     * Constructs an encoder for a track.
     *
     * @param track the track whose packets are produced; its codec must be a text subtitle codec
     * @throws IllegalArgumentException if the track's codec is not a text subtitle codec
     */
    public SubtitleEncoder(Track track) {
        this.track = Objects.requireNonNull(track, "track");
        if (!track.codec().isSubtitle()) {
            throw new IllegalArgumentException(track.codec().displayName() + " is not a text subtitle codec");
        }
        var config = track.codecConfig().orElse(null);
        this.script = track.codec() == Codec.ASS
                ? AssScript.parse(TextFiles.decode(config == null ? AssEvents.DEFAULT_HEADER.getBytes(StandardCharsets.UTF_8)
                : config)) : null;
        this.timedText = track.codec() == Codec.MOV_TEXT ? TimedText.Config.parse(config) : null;
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
        var timeBase = track.timeBase();
        var pts = Rational.MICROSECONDS.rescale(cue.startMicros(), timeBase);
        var duration = Math.max(0, Rational.MICROSECONDS.rescale(cue.endMicros(), timeBase) - pts);
        var sideData = PacketSideData.NONE;
        byte[] data;
        switch (codec) {
            case WEBVTT -> {
                var converted = Subtitles.convert(cue, markup, SubtitleMarkup.WEBVTT);
                sideData = WebVttSamples.sideData(sideData, converted.identifier(), converted.settings());
                data = SubtitleDecoder.bytes(converted.text());
            }
            case MOV_TEXT -> {
                var styled = Markup.parseCue(cue, markup, Markup.Context.of(cue));
                data = TimedText.encode(styled, timedText, timeBase, cue.durationMicros());
            }
            case ASS -> {
                SubtitleCue converted;
                if (markup == SubtitleMarkup.ASS) {
                    converted = cue;
                } else {
                    var styled = Markup.parseCue(cue, markup, Markup.Context.of(cue));
                    converted = Markup.toCue(styled, cue.startMicros(), cue.endMicros(), SubtitleMarkup.ASS,
                            Markup.Context.NONE.withScript(script, ""));
                }
                var event = AssEvents.withFields(readOrder++, converted.settings(), converted.text().replace("\n", "\\N"));
                data = SubtitleDecoder.bytes(event.block());
            }
            default -> data = SubtitleDecoder.bytes(Subtitles.convert(cue, markup, SubtitleMarkup.SUBRIP).text());
        }
        return new Packet(track.id(), data, pts, pts, duration, KEY, sideData);
    }
}
