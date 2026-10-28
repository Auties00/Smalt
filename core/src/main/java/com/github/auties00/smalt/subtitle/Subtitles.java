package com.github.auties00.smalt.subtitle;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.container.ContainerFormat;
import com.github.auties00.smalt.container.Containers;
import com.github.auties00.smalt.container.DemuxerOptions;
import com.github.auties00.smalt.container.Muxer;
import com.github.auties00.smalt.internal.subtitle.Markup;
import com.github.auties00.smalt.internal.subtitle.TtmlWriter;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.PacketFlag;
import com.github.auties00.smalt.track.PacketSideData;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.Track;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Reads, writes and converts text subtitles.
 *
 * <p>Five codecs carry text subtitles, each a {@link Codec} whose cues use a {@link SubtitleMarkup}:
 * <ul>
 *   <li>{@link Codec#SUBRIP}: SubRip, in {@code .srt} files and Matroska {@code S_TEXT/UTF8}
 *       tracks, and the tracks of SAMI and MicroDVD files;</li>
 *   <li>{@link Codec#WEBVTT}: WebVTT, in {@code .vtt} files, Matroska and WebM tracks and MP4
 *       {@code wvtt} tracks, and the track of LRC files;</li>
 *   <li>{@link Codec#MOV_TEXT}: 3GPP timed text, in MP4 {@code tx3g} and QuickTime {@code text} tracks;
 *       its cues use SubRip markup;</li>
 *   <li>{@link Codec#ASS}: SubStation Alpha and Advanced SubStation Alpha, in {@code .ssa} and
 *       {@code .ass} files and Matroska tracks;</li>
 *   <li>{@link Codec#TTML}: TTML and its IMSC profiles, in TTML files and MP4 {@code stpp} tracks, whose
 *       packets are documents of several cues
 *       ({@link SubtitleDecoder#decodeAll(com.github.auties00.smalt.track.Packet)});
 *       its cues use SubRip markup.</li>
 * </ul>
 * Converting between them keeps the text and line breaks; the bold, italic, underline and strikeout
 * styles, text colors, fonts and font sizes as far as the target markup has them (WebVTT has no fonts,
 * sizes or strikeout, and only its default color classes); the placement (a numpad alignment such as the
 * {@code {\an8}} of SubRip and ASS, the WebVTT {@code line}, {@code position} and {@code align} settings,
 * an ASS {@code \pos}); karaoke times (ASS {@code \k} tags, WebVTT timestamp tags); and the speaker (a
 * WebVTT voice span, the {@code Name} of an ASS event). Other markup (WebVTT classes, regions and ruby,
 * ASS animations, borders and shadows) has no counterpart in every format and is dropped.
 *
 * <p>This class is thread-safe.
 */
public final class Subtitles {
    /**
     * The flags of the packet of a TTML file.
     */
    private static final Set<PacketFlag> KEY = Set.of(PacketFlag.KEY);

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
     * @return {@link SubtitleMarkup#SUBRIP} for SubRip, 3GPP timed text, TTML and the caption codecs,
     * {@link SubtitleMarkup#WEBVTT} and {@link SubtitleMarkup#ASS} for WebVTT and ASS
     * @throws IllegalArgumentException if {@code codec} is neither a text subtitle codec, TTML nor a caption codec
     */
    public static SubtitleMarkup markupOf(Codec codec) {
        return Markup.of(Objects.requireNonNull(codec, "codec"));
    }

    /**
     * Converts the markup of a cue text.
     *
     * <p>The styles, colors, fonts, sizes and placements the target markup can hold survive (see the class
     * description); WebVTT character references are decoded when leaving WebVTT and the reserved characters
     * escaped when entering it; ASS line breaks ({@code \N}) become line feeds and back. Karaoke times,
     * which need the cue start, are dropped; {@link #convert(SubtitleCue, SubtitleMarkup, SubtitleMarkup)}
     * keeps them.
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
     * <p>The text is converted as {@link #convertText(String, SubtitleMarkup, SubtitleMarkup)} does, keeping
     * its karaoke times. The placement moves between the text and the settings: a WebVTT cue's settings
     * become an alignment or position in the text of the other formats, and an alignment or position becomes
     * the settings of a WebVTT cue. A cue converted to ASS gets the settings of an event in the
     * {@code Default} style, its speaker as the {@code Name} (empty settings when it has no speaker), and its
     * position in the 384x288 space of a script without a resolution; a cue converted from ASS takes the
     * speaker of its {@code Name} field but not the look of its style, which only a decoder with the script
     * header knows ({@link SubtitleDecoder#decode(com.github.auties00.smalt.track.Packet, SubtitleMarkup)}). A
     * cue converted to another format loses its WebVTT identifier.
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
        var context = Markup.Context.of(cue);
        var styled = Markup.parseCue(cue, source, context);
        return Markup.toCue(styled, cue.startMicros(), cue.endMicros(), target, context);
    }

    /**
     * Reads every cue of the first subtitle track of a file.
     *
     * @param source the input: a SubRip, WebVTT, SSA, ASS, TTML, SAMI, LRC or MicroDVD file, or an MP4, Matroska
     * or MPEG transport stream file with a text subtitle or caption track
     * @return the cues in start order, their text in the track's markup
     * ({@link SubtitleReader#markup()})
     * @throws MediaException.Unsupported if the input is not a recognized container or has no
     * subtitle track
     * @throws MediaException.Malformed if the input is corrupt
     * @throws MediaException.Io if reading the source fails
     */
    public static List<SubtitleCue> read(MediaSource source) {
        return read(source, SubtitleReaderOptions.defaults());
    }

    /**
     * Reads every cue of a subtitle track of a file.
     *
     * @param source the input
     * @param options the track to read, the markup to convert to, the text encoding and the limits
     * @return the cues in start order
     * @throws MediaException.Unsupported if the input is not a recognized container or lacks the
     * selected subtitle track
     * @throws MediaException.Malformed if the input is corrupt
     * @throws MediaException.Io if reading the source fails
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
     * Reads every cue of the first bitmap subtitle track of a file.
     *
     * @param source the input: a {@code .sup} file, the {@code .sub} file of a VobSub pair, or an MP4, Matroska or
     * MPEG transport stream file with a PGS, DVB or DVD subtitle track
     * @return the cues in start order
     * @throws MediaException.Unsupported if the input is not a recognized container or has no bitmap subtitle track
     * @throws MediaException.Malformed if the input is corrupt
     * @throws MediaException.LimitExceeded if a picture exceeds the default limits
     * @throws MediaException.Io if reading the source fails
     */
    public static List<BitmapCue> readBitmaps(MediaSource source) {
        return readBitmaps(source, SubtitleReaderOptions.defaults());
    }

    /**
     * Reads every cue of a bitmap subtitle track of a file.
     *
     * <p>Of the options, the track identifier selects the track (the first bitmap subtitle track by default) and
     * the limits bound the demuxer and the pictures; the markup, text and caption settings do not apply.
     *
     * @param source the input
     * @param options the track to read and the limits
     * @return the cues in start order
     * @throws MediaException.Unsupported if the input is not a recognized container or lacks the selected bitmap
     * subtitle track
     * @throws MediaException.Malformed if the input is corrupt
     * @throws MediaException.LimitExceeded if a picture exceeds the limits
     * @throws MediaException.Io if reading the source fails
     */
    public static List<BitmapCue> readBitmaps(MediaSource source, SubtitleReaderOptions options) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(options, "options");
        try (var demuxer = Containers.openDemuxer(source, DemuxerOptions.builder().limits(options.limits()).build())) {
            Track selected = null;
            for (var track : demuxer.tracks()) {
                if (track.codec().isBitmapSubtitle()
                        && (options.trackId().isEmpty() || options.trackId().getAsInt() == track.id())) {
                    selected = track;
                    break;
                }
            }
            if (selected == null) {
                throw new MediaException.Unsupported("subtitles", options.trackId().isPresent()
                        ? "the input has no bitmap subtitle track " + options.trackId().getAsInt()
                        : "the input has no bitmap subtitle track");
            }
            var decoder = new BitmapSubtitleDecoder(selected, options.limits());
            var cues = new ArrayList<BitmapCue>();
            for (var packet = demuxer.read(); packet != null; packet = demuxer.read()) {
                if (packet.trackId() == selected.id()) {
                    cues.addAll(decoder.decode(packet));
                }
            }
            cues.addAll(decoder.flush());
            return cues;
        }
    }

    /**
     * Writes cues as a text subtitle file.
     *
     * <p>The cues are written in start order (a stable sort keeps the given order of cues that
     * start together), converted from {@code markup} to the file's: SubRip markup for SubRip, SAMI and
     * MicroDVD files, WebVTT for WebVTT and LRC files, ASS events for ASS files and a TTML document for
     * TTML files. An ASS cue keeps its event fields; a SubRip, WebVTT, ASS or TTML file is written in
     * UTF-8, a SAMI file in UTF-8 with its charset declared, a MicroDVD file at 24000/1001 frames per
     * second. A format that core does not write but a {@link com.github.auties00.smalt.spi.ContainerProvider} does
     * is written by the provider's muxer, as one track of the first of SubRip, WebVTT, ASS and TTML that the muxer
     * {@linkplain Containers#isMuxable(ContainerFormat, Codec) carries}.
     *
     * @param cues the cues to write, in presentation order
     * @param markup the markup of the cue texts
     * @param format {@link ContainerFormat#SUBRIP}, {@link ContainerFormat#WEBVTT}, {@link ContainerFormat#ASS},
     * {@link ContainerFormat#TTML}, {@link ContainerFormat#SAMI}, {@link ContainerFormat#LRC},
     * {@link ContainerFormat#MICRODVD}, or a format whose provider writes text subtitle tracks
     * @param sink the output, written from its position; not closed
     * @throws NullPointerException if a cue is {@code null}
     * @throws IllegalArgumentException if {@code format} is not a text subtitle file format
     * @throws MediaException.Io if writing fails
     */
    public static void write(List<SubtitleCue> cues, SubtitleMarkup markup, ContainerFormat format, MediaSink sink) {
        Objects.requireNonNull(format, "format");
        var codec = switch (format) {
            case SUBRIP, SAMI, MICRODVD -> Codec.SUBRIP;
            case WEBVTT, LRC -> Codec.WEBVTT;
            case ASS -> Codec.ASS;
            case TTML -> Codec.TTML;
            case MP4, MATROSKA, OGG, MPEG_AUDIO, ADTS, WAV, FLAC, AMR, AIFF, IVF, AVI, MPEG_TS, AC3, SUP, VOBSUB, H264,
                 HEVC, AV1, AV1_ANNEX_B, MPEG4_VIDEO, H263, Y4M, HLS, DASH, PCM, OTHER -> null;
            default -> textCodec(candidate -> Containers.isMuxable(format, candidate));
        };
        if (codec == null) {
            throw new IllegalArgumentException(format + " is not a text subtitle file format");
        }
        write(cues, markup, codec, () -> Containers.openMuxer(format, sink), sink);
    }

    /**
     * Writes cues as a text subtitle file of a container named by its short name.
     *
     * <p>A {@link ContainerFormat#shortName()} writes the file
     * {@link #write(List, SubtitleMarkup, ContainerFormat, MediaSink)} writes for that format; the name of a
     * container a {@link com.github.auties00.smalt.spi.ContainerProvider} adds writes the cues, in start order, as
     * one track of the first of SubRip, WebVTT, ASS and TTML that the provider's muxer
     * {@linkplain Containers#isMuxable(String, Codec) carries}.
     *
     * @param cues the cues to write, in presentation order
     * @param markup the markup of the cue texts
     * @param container the short name of the container, for example {@code "subrip"}
     * @param sink the output, written from its position; not closed
     * @throws NullPointerException if a cue is {@code null}
     * @throws IllegalArgumentException if the container is not a text subtitle file format that core or a provider
     * on the module or class path writes
     * @throws MediaException.Io if writing fails
     */
    public static void write(List<SubtitleCue> cues, SubtitleMarkup markup, String container, MediaSink sink) {
        Objects.requireNonNull(container, "container");
        var format = ContainerFormat.ofShortName(container);
        if (format.isPresent()) {
            write(cues, markup, format.get(), sink);
            return;
        }
        var codec = textCodec(candidate -> Containers.isMuxable(container, candidate));
        if (codec == null) {
            throw new IllegalArgumentException(container + " is not a text subtitle file format Smalt writes");
        }
        write(cues, markup, codec, () -> Containers.openMuxer(container, sink), sink);
    }

    /**
     * Returns the first text subtitle codec a muxer carries.
     *
     * @param carries whether the muxer carries a codec
     * @return the first of SubRip, WebVTT, ASS and TTML it carries, or {@code null}
     */
    private static Codec textCodec(Predicate<Codec> carries) {
        for (var codec : List.of(Codec.SUBRIP, Codec.WEBVTT, Codec.ASS, Codec.TTML)) {
            if (carries.test(codec)) {
                return codec;
            }
        }
        return null;
    }

    /**
     * Writes cues as one text subtitle track.
     *
     * @param cues the cues to write
     * @param markup the markup of the cue texts
     * @param codec the codec of the track
     * @param muxer creates the muxer of the file
     * @param sink the output
     * @throws NullPointerException if a cue is {@code null}
     * @throws MediaException.Io if writing fails
     */
    private static void write(List<SubtitleCue> cues, SubtitleMarkup markup, Codec codec, Supplier<Muxer> muxer,
                              MediaSink sink) {
        Objects.requireNonNull(cues, "cues");
        Objects.requireNonNull(markup, "markup");
        Objects.requireNonNull(sink, "sink");
        var sorted = new ArrayList<>(cues);
        for (var cue : sorted) {
            Objects.requireNonNull(cue, "cue");
        }
        sorted.sort(Comparator.comparingLong(SubtitleCue::startMicros));
        if (codec == Codec.TTML) {
            var document = TtmlWriter.write(sorted, markup);
            var track = TtmlWriter.track(1);
            var end = 0L;
            for (var cue : sorted) {
                end = Math.max(end, cue.endMicros());
            }
            try (var output = muxer.get()) {
                output.addTrack(track);
                output.writePacket(new Packet(1, document, 0, 0, Rational.MICROSECONDS.rescale(end, track.timeBase()),
                        KEY, PacketSideData.NONE));
                output.finish();
            }
            return;
        }
        var track = SubtitleEncoder.track(codec, 1);
        var encoder = new SubtitleEncoder(track);
        try (var output = muxer.get()) {
            output.addTrack(track);
            for (var cue : sorted) {
                output.writePacket(encoder.encode(cue, markup));
            }
            output.finish();
        }
    }
}
