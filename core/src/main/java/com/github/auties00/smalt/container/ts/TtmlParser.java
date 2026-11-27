package com.github.auties00.smalt.container.ts;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.DataTrack;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.PacketFlag;
import com.github.auties00.smalt.track.PacketSideData;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.Locale;
import java.util.zip.GZIPInputStream;

/**
 * Reads a DVB TTML subtitle stream (ETSI EN 303 560): the PES data field of each PES packet is a 48-bit
 * {@code segment_mediatime} (in units of 100 microseconds), a segment count, segments of an 8-bit type and a
 * 16-bit length, and a {@code CRC_32}; the TTML segment (type 1, or type 2 compressed with gzip) holds a whole TTML
 * document.
 *
 * <p>Each document becomes one {@link Codec#TTML} packet timestamped with the PES time. The document's times are on
 * its media timeline, whose {@code segment_mediatime} the PES time presents, so its {@code body} is moved to start
 * at the difference of the two: the document's times are then times of the track, as those of the TTML samples of
 * an MP4 track are. A document whose media time is ahead of the PES time, or whose {@code body} has a begin time
 * that is not a clock or offset time in hours, minutes, seconds or milliseconds, keeps its times. Packets without
 * a TTML segment are dropped.
 */
final class TtmlParser extends ElementaryParser {
    /**
     * Constructs the parser.
     *
     * @param entry the program map entry of the stream
     * @param limits the resource bounds
     */
    TtmlParser(Psi.StreamEntry entry, MediaLimits limits) {
        super(entry, limits);
        this.track = DataTrack.builder().id(entry.trackId()).codec(Codec.TTML).timeBase(NalParser.TIME_BASE)
                .language(entry.language()).build();
    }

    /**
     * Constructs a parser sharing the track of another.
     *
     * @param other the parser
     */
    private TtmlParser(TtmlParser other) {
        super(other.entry, other.limits);
        this.track = other.track;
    }

    @Override
    ElementaryParser fresh() {
        return new TtmlParser(this);
    }

    @Override
    void accept(Pes pes, TsClock clock, UnitSink sink) {
        var data = pes.data();
        var position = pes.offset();
        var end = pes.offset() + pes.length();
        if (end - position < 7 || pes.pts() == TsClock.NONE) {
            return;
        }
        var mediaTime = 0L;
        for (var index = 0; index < 6; index++) {
            mediaTime = mediaTime << 8 | (data[position + index] & 0xFF);
        }
        var segments = data[position + 6] & 0xFF;
        position += 7;
        byte[] document = null;
        for (var segment = 0; segment < segments && position + 3 <= end; segment++) {
            var type = data[position] & 0xFF;
            var length = (data[position + 1] & 0xFF) << 8 | (data[position + 2] & 0xFF);
            position += 3;
            if (position + length > end) {
                break;
            }
            if (type == 1 && document == null) {
                document = java.util.Arrays.copyOfRange(data, position, position + length);
            } else if (type == 2 && document == null) {
                document = inflate(data, position, length);
            }
            position += length;
        }
        if (document == null) {
            return;
        }
        var pts = clock.ticks(trackId(), pes.pts(), true);
        var offset = pts - mediaTime * 9;
        var flags = EnumSet.of(PacketFlag.KEY);
        if (pes.corrupt()) {
            flags.add(PacketFlag.CORRUPT);
        }
        sink.accept(new Packet(trackId(), offset >= 0 ? shifted(document, offset) : document, pts, pts,
                Packet.UNKNOWN_DURATION, flags, PacketSideData.NONE), pes.fileOffset());
    }

    @Override
    void flush(TsClock clock, UnitSink sink) {
    }

    /**
     * Decompresses a gzip compressed TTML segment within the byte limit.
     */
    private byte[] inflate(byte[] data, int offset, int length) {
        try (var input = new GZIPInputStream(new ByteArrayInputStream(data, offset, length))) {
            var output = new ByteArrayOutputStream(Math.min(length * 4, 1 << 20));
            var buffer = new byte[8192];
            for (var read = input.read(buffer); read >= 0; read = input.read(buffer)) {
                LimitChecks.checkAllocation(limits, output.size() + (long) read);
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        } catch (IOException exception) {
            throw new MediaException.Malformed("a gzip TTML segment of the DVB TTML stream on PID " + entry.pid()
                    + " is not a gzip stream: " + exception.getMessage());
        }
    }

    /**
     * Moves the {@code body} of a document by a time.
     *
     * @param document the UTF-8 document
     * @param ticks the time to add, in 90 kHz ticks, zero or more
     * @return the document with the {@code begin} of its {@code body} increased by the time, or the document
     *         unchanged when it has no {@code body} or its {@code body} begin cannot be read
     */
    static byte[] shifted(byte[] document, long ticks) {
        if (ticks == 0) {
            return document;
        }
        var text = new String(document, StandardCharsets.UTF_8);
        var tag = bodyTag(text);
        if (tag < 0) {
            return document;
        }
        var close = text.indexOf('>', tag);
        if (close < 0) {
            return document;
        }
        var head = text.substring(tag, close);
        var begin = attribute(head, "begin");
        String rewritten;
        if (begin == null) {
            var at = close > tag && text.charAt(close - 1) == '/' ? close - 1 : close;
            rewritten = text.substring(0, at) + " begin=\"" + seconds(ticks) + "\"" + text.substring(at);
        } else {
            var existing = timeTicks(head.substring(begin[0], begin[1]));
            if (existing < 0) {
                return document;
            }
            rewritten = text.substring(0, tag + begin[0]) + seconds(existing + ticks) + text.substring(tag + begin[1]);
        }
        return rewritten.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Returns the index of the {@code <} of the {@code body} start tag, with any namespace prefix, or {@code -1}.
     */
    private static int bodyTag(String text) {
        for (var at = text.indexOf('<'); at >= 0; at = text.indexOf('<', at + 1)) {
            var name = at + 1;
            var end = name;
            while (end < text.length() && !Character.isWhitespace(text.charAt(end)) && text.charAt(end) != '>'
                    && text.charAt(end) != '/') {
                end++;
            }
            var qualified = text.substring(name, end);
            var local = qualified.substring(qualified.indexOf(':') + 1);
            if (local.equals("body")) {
                return at;
            }
        }
        return -1;
    }

    /**
     * Returns the bounds of an unprefixed attribute's value in a start tag, or {@code null}.
     */
    private static int[] attribute(String head, String name) {
        for (var at = head.indexOf(name); at > 0; at = head.indexOf(name, at + 1)) {
            if (!Character.isWhitespace(head.charAt(at - 1))) {
                continue;
            }
            var equals = at + name.length();
            while (equals < head.length() && Character.isWhitespace(head.charAt(equals))) {
                equals++;
            }
            if (equals >= head.length() || head.charAt(equals) != '=') {
                continue;
            }
            var quote = equals + 1;
            while (quote < head.length() && Character.isWhitespace(head.charAt(quote))) {
                quote++;
            }
            if (quote >= head.length() || head.charAt(quote) != '"' && head.charAt(quote) != '\'') {
                return null;
            }
            var close = head.indexOf(head.charAt(quote), quote + 1);
            return close < 0 ? null : new int[]{quote + 1, close};
        }
        return null;
    }

    /**
     * Parses a clock time ({@code hh:mm:ss} with an optional fraction) or an offset time in hours, minutes, seconds
     * or milliseconds.
     *
     * @return the time in 90 kHz ticks, or {@code -1} for another expression
     */
    private static long timeTicks(String value) {
        var text = value.strip();
        try {
            if (text.matches("\\d+:\\d{2}:\\d{2}(\\.\\d+)?")) {
                var parts = text.split(":");
                var seconds = Long.parseLong(parts[0]) * 3600 + Long.parseLong(parts[1]) * 60;
                return Math.round((seconds + Double.parseDouble(parts[2])) * 90_000);
            }
            var unit = text.endsWith("ms") ? "ms" : text.isEmpty() ? "" : text.substring(text.length() - 1);
            var number = text.substring(0, text.length() - unit.length());
            if (!number.matches("\\d+(\\.\\d+)?")) {
                return -1;
            }
            var scale = switch (unit) {
                case "h" -> 3600.0;
                case "m" -> 60.0;
                case "s" -> 1.0;
                case "ms" -> 0.001;
                default -> -1.0;
            };
            return scale < 0 ? -1 : Math.round(Double.parseDouble(number) * scale * 90_000);
        } catch (NumberFormatException exception) {
            return -1;
        }
    }

    /**
     * Formats a time of 90 kHz ticks as a TTML offset time in seconds.
     */
    private static String seconds(long ticks) {
        return String.format(Locale.ROOT, "%d.%05ds", ticks / 90_000, (ticks % 90_000) * 100_000 / 90_000);
    }
}
