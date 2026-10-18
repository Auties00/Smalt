package com.github.auties00.smalt.subtitle;

import com.github.auties00.smalt.internal.subtitle.AssEvents;
import com.github.auties00.smalt.internal.subtitle.Markup;
import com.github.auties00.smalt.internal.subtitle.TextFiles;
import com.github.auties00.smalt.internal.subtitle.TimedText;
import com.github.auties00.smalt.internal.subtitle.WebVttSamples;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.Track;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Turns the packets of a text subtitle track into {@link SubtitleCue}s.
 *
 * <p>Each packet is decoded on its own, in the packet form its codec documents
 * ({@link Codec#SUBRIP}, {@link Codec#WEBVTT}, {@link Codec#MOV_TEXT}, {@link Codec#ASS}). The cue
 * starts at the packet's presentation timestamp (its decoding timestamp when it has none, the end
 * of the previous cue when it has neither) and lasts the packet's duration. Its text keeps the
 * markup of {@link #markup()}: SubRip for SubRip and 3GPP timed text (whose {@code styl} runs
 * become {@code <b>}, {@code <i>} and {@code <u>} tags), WebVTT, or the text field of the ASS
 * event with its override blocks. Text is read as UTF-8, falling back to Windows-1252 for bytes
 * that are not valid UTF-8, with line breaks normalized to line feeds.
 *
 * <p>An instance is not thread-safe.
 */
public final class SubtitleDecoder {
    /**
     * The track decoded.
     */
    private final Track track;

    /**
     * The end of the previous cue in microseconds.
     */
    private long previousEnd;

    /**
     * Constructs a decoder for a track.
     *
     * @param track the track; its codec must be a text subtitle codec
     * @throws NullPointerException     if {@code track} is {@code null}
     * @throws IllegalArgumentException if the track's codec is not a text subtitle codec
     */
    public SubtitleDecoder(Track track) {
        this.track = Objects.requireNonNull(track, "track");
        if (!track.codec().isSubtitle()) {
            throw new IllegalArgumentException(track.codec().displayName() + " is not a text subtitle codec");
        }
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
     * @return {@link SubtitleMarkup#SUBRIP} for SubRip and 3GPP timed text tracks, {@link SubtitleMarkup#WEBVTT}
     *         or {@link SubtitleMarkup#ASS} for those tracks
     */
    public SubtitleMarkup markup() {
        return Markup.of(track.codec());
    }

    /**
     * Decodes one packet.
     *
     * @param packet a packet of the track
     * @return the cue, or {@code null} when the packet shows nothing (an empty 3GPP timed text
     *         sample, which clears the screen, or a packet whose text is blank)
     * @throws NullPointerException if {@code packet} is {@code null}
     */
    public SubtitleCue decode(Packet packet) {
        Objects.requireNonNull(packet, "packet");
        var timeBase = track.timeBase();
        var time = packet.pts() != Packet.NO_TIMESTAMP ? packet.pts() : packet.dts();
        var start = time == Packet.NO_TIMESTAMP ? previousEnd : micros(timeBase, time);
        var duration = Math.max(0, packet.duration());
        var end = Math.max(start, time == Packet.NO_TIMESTAMP ? start + micros(timeBase, duration)
                : micros(timeBase, saturatingAdd(time, duration)));
        previousEnd = end;
        String text;
        var identifier = "";
        var settings = "";
        switch (track.codec()) {
            case WEBVTT -> {
                var cue = WebVttSamples.cue(packet);
                text = cue.payload();
                identifier = cue.identifier();
                settings = cue.settings();
            }
            case MOV_TEXT -> {
                if (TimedText.isEmpty(packet.data())) {
                    return null;
                }
                text = TimedText.decodeText(packet.data());
            }
            case ASS -> text = AssEvents.text(stripTrailingLineBreaks(utf8(packet)));
            default -> text = utf8(packet);
        }
        text = stripTrailingLineBreaks(text);
        if (text.isBlank()) {
            return null;
        }
        return new SubtitleCue(start, end, text, identifier, settings);
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
