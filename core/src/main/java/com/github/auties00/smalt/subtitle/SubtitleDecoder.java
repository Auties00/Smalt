package com.github.auties00.smalt.subtitle;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.internal.caption.CaptionStream;
import com.github.auties00.smalt.internal.caption.CaptionTimeline;
import com.github.auties00.smalt.internal.caption.Cea608Decoder;
import com.github.auties00.smalt.internal.caption.Cea708Decoder;
import com.github.auties00.smalt.internal.caption.TeletextDecoder;
import com.github.auties00.smalt.internal.limits.Guards;
import com.github.auties00.smalt.internal.subtitle.AssEvents;
import com.github.auties00.smalt.internal.subtitle.AssScript;
import com.github.auties00.smalt.internal.subtitle.Markup;
import com.github.auties00.smalt.internal.subtitle.TextFiles;
import com.github.auties00.smalt.internal.subtitle.TimedText;
import com.github.auties00.smalt.internal.subtitle.TtmlDocument;
import com.github.auties00.smalt.internal.subtitle.WebVttSamples;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.Track;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Turns the packets of a text subtitle track into {@link SubtitleCue}s.
 *
 * <p>Each packet is decoded on its own, in the packet form its codec documents ({@link Codec#SUBRIP},
 * {@link Codec#WEBVTT}, {@link Codec#MOV_TEXT}, {@link Codec#ASS}, {@link Codec#TTML}). A cue starts at
 * the packet's presentation timestamp (its decoding timestamp when it has none, the end of the previous
 * cue when it has neither) and lasts the packet's duration; the cues of a TTML document keep the times
 * the document gives them, cut to the packet's span. Text is read as UTF-8, falling back to Windows-1252
 * for bytes that are not valid UTF-8, with line breaks normalized to line feeds.
 *
 * <p>Cues come in the markup of {@link #markup()} unless another is asked for:
 * <ul>
 *   <li>SubRip and WebVTT cues are the packet text, a WebVTT cue with its identifier and settings;</li>
 *   <li>ASS cues are the text field of the event, with the event's {@code Layer, Style, Name, MarginL,
 *       MarginR, MarginV, Effect} fields as the settings;</li>
 *   <li>3GPP timed text and TTML cues are SubRip text: bold, italic, underline (and strikeout for TTML)
 *       as tags, and the colors, fonts and sizes that differ from the track defaults as {@code <font>} tags,
 *       with an {@code {\anN}} placement.</li>
 * </ul>
 * Decoding into another markup ({@link #decode(Packet, SubtitleMarkup)}) converts with the whole track in
 * view: an ASS event gets the bold, italic, underline, strikeout, color and alignment of its style from
 * the script header, and positions and font sizes are scaled from the script resolution; karaoke times
 * and placements carry over where the target markup has them.
 *
 * <p>Caption tracks ({@link Codec#CEA_608}, {@link Codec#CEA_708}, {@link Codec#DVB_TELETEXT}) are decoded
 * statefully, one {@link CaptionService} at a time, in SubRip markup: italics, underline and colors other than white
 * as tags, a caption in the upper half of the screen with the alignment {@code {\an8}}, rows from top to bottom
 * without their leading and trailing spaces. A caption cue is complete once the caption that replaces or erases it
 * arrives, so {@link #decodeAll(Packet)} returns the cues that end at the packet and {@link #flush()} the one shown
 * after the last packet. A pop-on caption is one cue from the command that shows it to the one that removes it; a
 * roll-up or paint-on caption, which grows a character at a time, is one cue per line or paint, showing the text
 * complete at the next carriage return, erase or flip, from the time its first character appeared; a teletext page
 * is one cue from the packet that carried its header to the next version of the page.
 *
 * <p>An instance is not thread-safe.
 */
public final class SubtitleDecoder {
    /**
     * The track decoded.
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
     * The end of the previous cue in microseconds.
     */
    private long previousEnd;

    /**
     * The decoder of a caption track, or {@code null}.
     */
    private final CaptionStream captions;

    /**
     * The caption service decoded, or {@code null}.
     */
    private final CaptionService service;

    /**
     * The caption cues completed by the packet decoded.
     */
    private final List<SubtitleCue> completed = new ArrayList<>();

    /**
     * The markup the caption cues are written in.
     */
    private SubtitleMarkup requested = SubtitleMarkup.SUBRIP;

    /**
     * Constructs a decoder for a track.
     *
     * <p>A caption track is decoded for its {@linkplain CaptionService#defaultFor(Track) default service}.
     *
     * @param track the track; its codec must be a text subtitle codec, {@link Codec#TTML} or a caption codec
     * @throws IllegalArgumentException if the track's codec is none of these
     */
    public SubtitleDecoder(Track track) {
        this(track, track.codec().isCaption() ? CaptionService.defaultFor(track) : null);
    }

    /**
     * Constructs a decoder for one service of a caption track.
     *
     * @param track the track; its codec must be a text subtitle codec, {@link Codec#TTML} or a caption codec
     * @param service the caption service decoded, or {@code null} for a track that is not a caption track
     * @throws IllegalArgumentException if the track's codec is none of these, if {@code service} is {@code null} for
     * a caption track or cannot be read from it, or if a service is given for another track
     */
    public SubtitleDecoder(Track track, CaptionService service) {
        this.track = Objects.requireNonNull(track, "track");
        var codec = track.codec();
        if (!codec.isSubtitle() && codec != Codec.TTML && !codec.isCaption()) {
            throw new IllegalArgumentException(codec.displayName() + " is not a text subtitle or caption codec");
        }
        if (codec.isCaption() != (service != null)) {
            throw new IllegalArgumentException(codec.isCaption() ? "a caption track needs a caption service"
                    : codec.displayName() + " tracks have no caption services");
        }
        if (service != null && !service.readableFrom(codec)) {
            throw new IllegalArgumentException(service + " cannot be read from a " + codec.displayName() + " track");
        }
        this.service = service;
        var config = track.codecConfig().orElse(null);
        this.script = codec == Codec.ASS
                ? AssScript.parse(config == null ? "" : TextFiles.decode(config)) : null;
        this.timedText = codec == Codec.MOV_TEXT ? TimedText.Config.parse(config) : null;
        if (service == null) {
            this.captions = null;
            return;
        }
        var timeline = new CaptionTimeline((start, end, text) -> completed.add(
                Markup.toCue(text, start, end, requested, Markup.Context.NONE)));
        this.captions = switch (service.kind()) {
            case CEA_608 -> new Cea608Decoder(timeline, service.number());
            case CEA_708 -> new Cea708Decoder(timeline, service.number());
            case TELETEXT -> new TeletextDecoder(timeline, service.number(),
                    teletextGroup(track.language().orElse("")));
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
     * Returns the markup convention of the decoded cue text.
     *
     * @return {@link SubtitleMarkup#SUBRIP} for SubRip, 3GPP timed text and TTML tracks,
     * {@link SubtitleMarkup#WEBVTT} or {@link SubtitleMarkup#ASS} for those tracks
     */
    public SubtitleMarkup markup() {
        return Markup.of(track.codec());
    }

    /**
     * Returns the caption service decoded.
     *
     * @return the service, empty for a track that is not a caption track
     */
    public Optional<CaptionService> service() {
        return Optional.ofNullable(service);
    }

    /**
     * Decodes one packet.
     *
     * @param packet a packet of the track
     * @return the cue, or {@code null} when the packet shows nothing (an empty 3GPP timed text
     * sample, which clears the screen, or a packet whose text is blank)
     * @throws IllegalStateException if the track is a TTML track, whose packets hold several cues, or a caption
     * track, whose cues end at later packets ({@link #decodeAll(Packet)})
     */
    public SubtitleCue decode(Packet packet) {
        return decode(packet, markup());
    }

    /**
     * Decodes one packet into a markup.
     *
     * <p>In the track's own markup this is {@link #decode(Packet)}.
     *
     * @param packet a packet of the track
     * @param markup the markup of the returned cue
     * @return the cue, or {@code null} when the packet shows nothing
     * @throws IllegalStateException if the track is a TTML track, whose packets hold several cues, or a caption
     * track
     */
    public SubtitleCue decode(Packet packet, SubtitleMarkup markup) {
        Objects.requireNonNull(packet, "packet");
        Objects.requireNonNull(markup, "markup");
        if (track.codec() == Codec.TTML) {
            throw new IllegalStateException("a TTML packet holds a document of cues; decode it with decodeAll");
        }
        if (captions != null) {
            throw new IllegalStateException("caption cues end at later packets; decode them with decodeAll");
        }
        var cues = decodeAll(packet, markup);
        return cues.isEmpty() ? null : cues.getFirst();
    }

    /**
     * Decodes one packet into every cue it holds, in its own markup.
     *
     * @param packet a packet of the track
     * @return the cues in start order: none or one for a text subtitle codec, every cue of the document,
     * cut to the packet's span, for TTML, the cues that end at the packet for a caption track
     * @throws MediaException.Malformed if a TTML packet is not a well-formed document
     */
    public List<SubtitleCue> decodeAll(Packet packet) {
        return decodeAll(packet, markup());
    }

    /**
     * Decodes one packet into every cue it holds, in a markup.
     *
     * @param packet a packet of the track
     * @param markup the markup of the returned cues
     * @return the cues in start order
     * @throws MediaException.Malformed if a TTML packet is not a well-formed document
     */
    public List<SubtitleCue> decodeAll(Packet packet, SubtitleMarkup markup) {
        Objects.requireNonNull(packet, "packet");
        Objects.requireNonNull(markup, "markup");
        var timeBase = track.timeBase();
        var time = packet.pts() != Packet.NO_TIMESTAMP ? packet.pts() : packet.dts();
        var start = time == Packet.NO_TIMESTAMP ? previousEnd : micros(timeBase, time);
        var duration = Math.max(0, packet.duration());
        var end = Math.max(start, time == Packet.NO_TIMESTAMP ? start + micros(timeBase, duration)
                : micros(timeBase, saturatingAdd(time, duration)));
        previousEnd = end;
        var own = markup();
        if (captions != null) {
            requested = markup;
            completed.clear();
            Guards.malformed(track.codec().displayName() + " packet", () -> {
                captions.decode(packet.data(), start);
                return null;
            });
            var result = List.copyOf(completed);
            completed.clear();
            return result;
        }
        switch (track.codec()) {
            case TTML -> {
                var result = new ArrayList<SubtitleCue>();
                var span = packet.duration() > 0 ? end : Long.MAX_VALUE;
                for (var cue : TtmlDocument.parse(packet.data()).cues()) {
                    var cueStart = Math.max(cue.startMicros(), start);
                    var cueEnd = Math.min(cue.endMicros(), span);
                    if (cueEnd <= cueStart || cue.text().plain().isBlank()) {
                        continue;
                    }
                    result.add(Markup.toCue(cue.text(), cueStart, cueEnd, markup, Markup.Context.NONE));
                }
                return result;
            }
            case MOV_TEXT -> {
                if (TimedText.isEmpty(packet.data())) {
                    return List.of();
                }
                var styled = TimedText.decode(packet.data(), timedText, timeBase);
                return single(styled.plain().isBlank() ? null : trimmed(Markup.toCue(styled, start, end, markup,
                        Markup.Context.NONE)));
            }
            case WEBVTT -> {
                var sample = WebVttSamples.cue(packet);
                var cue = new SubtitleCue(start, end, stripTrailingLineBreaks(sample.payload()), sample.identifier(),
                        sample.settings());
                return single(cue.text().isBlank() ? null : convert(cue, own, markup));
            }
            case ASS -> {
                var event = AssEvents.parseBlock(stripTrailingLineBreaks(utf8(packet)));
                var cue = new SubtitleCue(start, end, stripTrailingLineBreaks(event.text()), "", event.fields());
                if (Markup.plain(cue.text(), SubtitleMarkup.ASS).isBlank()) {
                    return List.of();
                }
                if (markup == SubtitleMarkup.ASS) {
                    return List.of(cue);
                }
                var context = new Markup.Context(start, end, script, event.style());
                var styled = Markup.parseCue(cue, SubtitleMarkup.ASS, context);
                return single(trimmed(Markup.toCue(styled, start, end, markup, Markup.Context.NONE)));
            }
            default -> {
                var text = stripTrailingLineBreaks(utf8(packet));
                return single(text.isBlank() ? null : convert(SubtitleCue.of(start, end, text), own, markup));
            }
        }
    }

    /**
     * Ends the caption shown after the last packet, in the track's markup.
     *
     * @return the cue still shown, ending at the end of the last packet (its time plus its duration), or an empty
     * list when nothing is shown or the track is not a caption track
     */
    public List<SubtitleCue> flush() {
        return flush(markup());
    }

    /**
     * Ends the caption shown after the last packet, in a markup.
     *
     * <p>The decoder then starts afresh, as after {@link #reset()}.
     *
     * @param markup the markup of the returned cue
     * @return the cue still shown, or an empty list when nothing is shown or the track is not a caption track
     */
    public List<SubtitleCue> flush(SubtitleMarkup markup) {
        Objects.requireNonNull(markup, "markup");
        if (captions == null) {
            return List.of();
        }
        requested = markup;
        completed.clear();
        captions.finish(previousEnd);
        var result = List.copyOf(completed);
        completed.clear();
        reset();
        return result;
    }

    /**
     * Discards the caption shown and the state built from earlier packets, as after a seek.
     */
    public void reset() {
        previousEnd = 0;
        if (captions != null) {
            captions.reset();
        }
    }

    /**
     * Returns the teletext character set group a language implies when the stream designates none.
     *
     * @param language the ISO 639-2 code of the track's language, empty when unknown
     * @return the designation code group of ETSI EN 300 706 table 32
     */
    private static int teletextGroup(String language) {
        return switch (language) {
            case "cze", "ces", "slo", "slk", "pol", "hun" -> 0x08;
            case "tur" -> 0x10;
            case "srp", "hrv", "scc", "scr", "slv", "rum", "ron" -> 0x18;
            case "rus", "bul", "ukr", "bel", "mkd", "est", "lav", "lit" -> 0x20;
            case "gre", "ell" -> 0x30;
            case "ara" -> 0x40;
            case "heb" -> 0x50;
            default -> 0x00;
        };
    }

    /**
     * Converts a cue from the track's markup.
     *
     * @param cue the cue in the track's markup
     * @param from the track's markup
     * @param to the markup asked for
     * @return the cue in {@code to}
     */
    private static SubtitleCue convert(SubtitleCue cue, SubtitleMarkup from, SubtitleMarkup to) {
        return from == to ? cue : trimmed(Subtitles.convert(cue, from, to));
    }

    /**
     * Returns a list of at most one cue.
     *
     * @param cue the cue, or {@code null}
     * @return the list
     */
    private static List<SubtitleCue> single(SubtitleCue cue) {
        return cue == null ? List.of() : List.of(cue);
    }

    /**
     * Removes the line feeds that end a cue text.
     *
     * @param cue the subtitle cue
     * @return the cue without trailing line feeds
     */
    private static SubtitleCue trimmed(SubtitleCue cue) {
        var text = stripTrailingLineBreaks(cue.text());
        return text.equals(cue.text()) ? cue : cue.withText(text);
    }

    /**
     * Decodes the payload of a packet as text.
     *
     * @param packet the packet whose payload is text
     * @return the text with line feeds only
     */
    private static String utf8(Packet packet) {
        var data = packet.data();
        var offset = data.length >= 3 && (data[0] & 0xFF) == 0xEF && (data[1] & 0xFF) == 0xBB && (data[2] & 0xFF) == 0xBF ? 3 : 0;
        return TextFiles.normalizeLineBreaks(TextFiles.decodeUtf8OrLegacy(data, offset, data.length - offset));
    }

    /**
     * Removes the line feeds that end a text.
     *
     * @param text the text to trim
     * @return the text without trailing line feeds
     */
    private static String stripTrailingLineBreaks(String text) {
        var end = text.length();
        while (end > 0 && text.charAt(end - 1) == '\n') {
            end--;
        }
        return text.substring(0, end);
    }

    /**
     * Converts a time to microseconds, saturating instead of overflowing.
     *
     * @param timeBase the time base
     * @param value    the time in time-base units
     * @return the time in microseconds
     */
    private static long micros(Rational timeBase, long value) {
        try {
            return timeBase.toMicros(value);
        } catch (ArithmeticException exception) {
            return value < 0 ? Long.MIN_VALUE / 2 : Long.MAX_VALUE / 2;
        }
    }

    /**
     * Adds two values, saturating to the {@code long} range.
     *
     * @param a the first value
     * @param b the second value
     * @return the sum
     */
    private static long saturatingAdd(long a, long b) {
        var sum = a + b;
        return ((a ^ sum) & (b ^ sum)) < 0 ? (a < 0 ? Long.MIN_VALUE : Long.MAX_VALUE) : sum;
    }

    /**
     * Returns the bytes of a text in UTF-8.
     *
     * @param text the text to encode
     * @return the bytes
     */
    static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
