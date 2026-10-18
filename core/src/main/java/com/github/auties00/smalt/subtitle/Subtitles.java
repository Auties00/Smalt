package com.github.auties00.smalt.subtitle;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.container.ContainerFormat;
import com.github.auties00.smalt.container.Containers;
import com.github.auties00.smalt.internal.subtitle.Markup;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.track.Codec;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Reads, writes and converts text subtitles.
 *
 * <p>Four formats are supported, each a {@link Codec} whose cues use a {@link SubtitleMarkup}:
 * <ul>
 *   <li>{@link Codec#SUBRIP}: SubRip, in {@code .srt} files and Matroska {@code S_TEXT/UTF8}
 *       tracks;</li>
 *   <li>{@link Codec#WEBVTT}: WebVTT, in {@code .vtt} files, Matroska and WebM tracks and MP4
 *       {@code wvtt} tracks;</li>
 *   <li>{@link Codec#MOV_TEXT}: 3GPP timed text, in MP4 {@code tx3g} tracks; its cues use SubRip
 *       markup;</li>
 *   <li>{@link Codec#ASS}: Advanced SubStation Alpha, in Matroska tracks.</li>
 * </ul>
 * Converting between them keeps the text, line breaks and the bold, italic and underline styles;
 * other markup (colors, fonts, voices, classes, positions other than a SubRip {@code {\anN}}
 * placement turned into WebVTT settings) has no counterpart in every format and is dropped.
 *
 * <p>This class is thread-safe.
 */
public final class Subtitles {
    /**
     * Prevents instantiation.
     */
    private Subtitles() {
        throw new AssertionError();
    }

    /**
     * Returns the markup convention of the cue text a subtitle codec's packets decode to.
     *
     * @param codec the subtitle codec
     * @return {@link SubtitleMarkup#SUBRIP} for SubRip and 3GPP timed text, {@link SubtitleMarkup#WEBVTT} and
     *         {@link SubtitleMarkup#ASS} for WebVTT and ASS
     * @throws IllegalArgumentException if {@code codec} is not a text subtitle codec
     */
    public static SubtitleMarkup markupOf(Codec codec) {
        return Markup.of(Objects.requireNonNull(codec, "codec"));
    }

    /**
     * Converts the markup of a cue text.
     *
     * <p>{@code <b>}, {@code <i>} and {@code <u>} (or the matching ASS overrides) survive; WebVTT
     * character references are decoded when leaving WebVTT and the reserved characters escaped
     * when entering it; ASS line breaks ({@code \N}) become line feeds and back.
     *
     * @param text the cue text in the {@code from} markup
     * @param from the markup of {@code text}
     * @param to the markup of the result
     * @return the converted text, {@code text} itself when both use the same markup
     */
    public static String convertText(String text, SubtitleMarkup from, SubtitleMarkup to) {
        Objects.requireNonNull(text, "text");
        return Markup.convert(text, Objects.requireNonNull(from, "from"), Objects.requireNonNull(to, "to"));
    }

    /**
     * Returns the characters a cue text shows, without markup.
     *
     * @param text the cue text in {@code markup}
     * @param markup the markup of {@code text}
     * @return the plain text, lines separated by line feeds
     */
    public static String plainText(String text, SubtitleMarkup markup) {
        Objects.requireNonNull(text, "text");
        return Markup.plain(text, Objects.requireNonNull(markup, "markup"));
    }

    /**
     * Converts a cue from one format's markup to another's.
     *
     * <p>The text is converted with {@link #convertText(String, SubtitleMarkup, SubtitleMarkup)}. A cue converted to
     * WebVTT keeps its settings, or gets the {@code line} and {@code align} settings of its SubRip
     * {@code {\anN}} placement when it has none; a cue converted from WebVTT to another format
     * loses its identifier and settings.
     *
     * @param cue the cue to convert
     * @param from the markup of the cue text
     * @param to the markup of the result
     * @return the converted cue, {@code cue} itself when both use the same markup
     */
    public static SubtitleCue convert(SubtitleCue cue, SubtitleMarkup from, SubtitleMarkup to) {
        Objects.requireNonNull(cue, "cue");
        var source = Objects.requireNonNull(from, "from");
        var target = Objects.requireNonNull(to, "to");
        if (source == target) {
            return cue;
        }
        var text = Markup.convert(cue.text(), source, target);
        if (target == SubtitleMarkup.WEBVTT) {
            var settings = cue.settings().isEmpty() && source == SubtitleMarkup.SUBRIP
                    ? Markup.webVttSettings(Markup.subRipAlignment(cue.text())) : cue.settings();
            return new SubtitleCue(cue.startMicros(), cue.endMicros(), text, cue.identifier(), settings);
        }
        return SubtitleCue.of(cue.startMicros(), cue.endMicros(), text);
    }

    /**
     * Reads every cue of the first subtitle track of a file.
     *
     * @param source the input: a SubRip or WebVTT file, or an MP4 or Matroska file with a subtitle
     *               track
     * @return the cues in start order, their text in the track's markup
     *         ({@link SubtitleReader#markup()})
     * @throws NullPointerException       if {@code source} is {@code null}
     * @throws MediaException.Unsupported if the input is not a recognized container or has no
     *                                    subtitle track
     * @throws MediaException.Malformed   if the input is corrupt
     * @throws MediaException.Io          if reading the source fails
     */
    public static List<SubtitleCue> read(MediaSource source) {
        return read(source, SubtitleReaderOptions.defaults());
    }

    /**
     * Reads every cue of a subtitle track of a file.
     *
     * @param source  the input
     * @param options the track to read, the markup to convert to and the limits
     * @return the cues in start order
     * @throws NullPointerException       if an argument is {@code null}
     * @throws MediaException.Unsupported if the input is not a recognized container or lacks the
     *                                    selected subtitle track
     * @throws MediaException.Malformed   if the input is corrupt
     * @throws MediaException.Io          if reading the source fails
     */
    public static List<SubtitleCue> read(MediaSource source, SubtitleReaderOptions options) {
        var cues = new ArrayList<SubtitleCue>();
        try (var reader = SubtitleReader.open(source, options)) {
            for (var cue = reader.read(); cue != null; cue = reader.read()) {
                cues.add(cue);
            }
        }
        return cues;
    }

    /**
     * Writes cues as a SubRip or WebVTT file.
     *
     * <p>The cues are written in start order (a stable sort keeps the given order of cues that
     * start together), their text converted from {@code markup} to the file's.
     *
     * @param cues the cues to write, in presentation order
     * @param markup the markup of the cue texts
     * @param format {@link ContainerFormat#SUBRIP} or {@link ContainerFormat#WEBVTT}
     * @param sink the output, written from its position; not closed
     * @throws NullPointerException if a cue is {@code null}
     * @throws IllegalArgumentException if {@code format} is not a text subtitle file format
     * @throws MediaException.Io if writing fails
     */
    public static void write(List<SubtitleCue> cues, SubtitleMarkup markup, ContainerFormat format, MediaSink sink) {
        Objects.requireNonNull(cues, "cues");
        Objects.requireNonNull(markup, "markup");
        Objects.requireNonNull(sink, "sink");
        var codec = switch (Objects.requireNonNull(format, "format")) {
            case SUBRIP -> Codec.SUBRIP;
            case WEBVTT -> Codec.WEBVTT;
            default -> throw new IllegalArgumentException(format + " is not a text subtitle file format");
        };
        var sorted = new ArrayList<>(cues);
        sorted.sort(Comparator.comparingLong(SubtitleCue::startMicros));
        var track = SubtitleEncoder.track(codec, 1);
        var encoder = new SubtitleEncoder(track);
        try (var muxer = Containers.openMuxer(format, sink)) {
            muxer.addTrack(track);
            for (var cue : sorted) {
                muxer.writePacket(encoder.encode(cue, markup));
            }
            muxer.finish();
        }
    }
}
