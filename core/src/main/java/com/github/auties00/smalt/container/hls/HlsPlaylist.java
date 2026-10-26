package com.github.auties00.smalt.container.hls;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.container.internal.Uris;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parses HLS playlists (RFC 8216 clause 4): the variants and renditions of a master playlist, and the segments of a
 * media playlist with their byte ranges, initialization sections and keys.
 */
final class HlsPlaylist {
    /**
     * The most lines a playlist may have.
     */
    private static final int MAX_LINES = 1 << 20;

    /**
     * The most segments a media playlist may list.
     */
    private static final int MAX_SEGMENTS = 1 << 16;

    private HlsPlaylist() {
        throw new AssertionError();
    }

    /**
     * A rendition of a master playlist ({@code EXT-X-MEDIA}).
     *
     * @param type the {@code TYPE}
     * @param uri the media playlist, resolved against the master playlist, or {@code null} for a rendition carried
     * by the variants
     * @param attributes the attributes, unquoted
     */
    record Media(String type, String uri, Map<String, String> attributes) {
    }

    /**
     * A master playlist.
     *
     * @param variants the media playlists of the variant streams, resolved, without repeats, in order
     * @param media the renditions, in order
     */
    record Master(List<String> variants, List<Media> media) {
    }

    /**
     * An initialization section ({@code EXT-X-MAP}).
     *
     * @param uri the file, resolved
     * @param offset the offset of its byte range
     * @param length the length of its byte range, or -1 for the whole file
     */
    record Section(String uri, long offset, long length) {
    }

    /**
     * The encryption of segments ({@code EXT-X-KEY}).
     *
     * @param method the {@code METHOD}
     * @param uri the key, resolved, or {@code null}
     * @param iv the 16-byte initialization vector, or {@code null} for the media sequence number
     * @param format the {@code KEYFORMAT}, {@code identity} when absent
     */
    record Key(String method, String uri, byte[] iv, String format) {
    }

    /**
     * A media segment.
     *
     * @param uri the file, resolved
     * @param offset the offset of its byte range
     * @param length the length of its byte range, or -1 for the whole file
     * @param durationMicros the {@code EXTINF} duration
     * @param section the initialization section in force, or {@code null}
     * @param key the encryption in force, the {@code identity} key when several key formats apply, or
     * {@code null} for clear segments
     * @param sequence the media sequence number
     */
    record Segment(String uri, long offset, long length, long durationMicros, Section section, Key key,
                   long sequence) {
    }

    /**
     * A media playlist.
     *
     * @param segments the segments, without the gaps
     * @param iFramesOnly whether each segment is one I-frame
     */
    record MediaPlaylist(List<Segment> segments, boolean iFramesOnly) {
    }

    /**
     * Returns whether playlist text is a master playlist.
     *
     * @param lines the playlist lines
     * @return {@code true} when it has a variant or a rendition tag
     */
    static boolean isMaster(List<String> lines) {
        for (var line : lines) {
            if (line.startsWith("#EXT-X-STREAM-INF:") || line.startsWith("#EXT-X-MEDIA:")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Splits playlist text into its lines.
     *
     * @param text the text, a byte order mark removed
     * @return the lines without their line ends and trailing blanks
     * @throws MediaException.Malformed if the first line is not {@code #EXTM3U}
     * @throws MediaException.LimitExceeded if the playlist has too many lines
     */
    static List<String> lines(String text) {
        var lines = new ArrayList<String>();
        var start = 0;
        while (start <= text.length()) {
            var end = text.indexOf('\n', start);
            if (end < 0) {
                end = text.length();
            }
            var line = text.substring(start, end).stripTrailing();
            lines.add(line);
            if (lines.size() > MAX_LINES) {
                throw new MediaException.LimitExceeded("an HLS playlist of more than " + MAX_LINES + " lines");
            }
            start = end + 1;
        }
        if (lines.isEmpty() || !lines.getFirst().equals("#EXTM3U")) {
            throw new MediaException.Malformed("not an HLS playlist: the first line is not #EXTM3U");
        }
        return lines;
    }

    /**
     * Parses a master playlist.
     *
     * @param lines the playlist lines
     * @param base the location of the playlist, against which its URIs resolve
     * @return the playlist
     * @throws MediaException.Malformed if a tag is malformed
     */
    static Master master(List<String> lines, String base) {
        var variants = new ArrayList<String>();
        var media = new ArrayList<Media>();
        for (var index = 0; index < lines.size(); index++) {
            var line = lines.get(index);
            if (line.startsWith("#EXT-X-STREAM-INF:")) {
                var next = index + 1;
                while (next < lines.size() && (lines.get(next).isBlank() || lines.get(next).startsWith("#")
                        && !lines.get(next).startsWith("#EXT"))) {
                    next++;
                }
                if (next >= lines.size() || lines.get(next).startsWith("#")) {
                    throw new MediaException.Malformed("an EXT-X-STREAM-INF without a URI");
                }
                var uri = resolve(base, lines.get(next).strip());
                if (!variants.contains(uri)) {
                    variants.add(uri);
                }
            } else if (line.startsWith("#EXT-X-MEDIA:")) {
                var attributes = attributes(line.substring("#EXT-X-MEDIA:".length()));
                var type = attributes.get("TYPE");
                if (type == null) {
                    throw new MediaException.Malformed("an EXT-X-MEDIA without a TYPE");
                }
                var uri = attributes.get("URI");
                media.add(new Media(type, uri == null ? null : resolve(base, uri), attributes));
            }
        }
        return new Master(variants, media);
    }

    /**
     * Parses a media playlist.
     *
     * @param lines the playlist lines
     * @param base the location of the playlist, against which its URIs resolve
     * @return the playlist
     * @throws MediaException.Malformed if a tag or a byte range is malformed
     * @throws MediaException.LimitExceeded if the playlist lists too many segments
     */
    static MediaPlaylist media(List<String> lines, String base) {
        var segments = new ArrayList<Segment>();
        var ends = new HashMap<String, Long>();
        var sequence = 0L;
        var duration = -1L;
        long[] range = null;
        Section section = null;
        // the keys in force by key format: a key tag applies until the next one of the same format (RFC 8216
        // clause 4.3.2.4)
        var keys = new LinkedHashMap<String, Key>();
        var gap = false;
        var iFramesOnly = false;
        for (var line : lines) {
            if (line.isBlank()) {
                continue;
            }
            if (line.startsWith("#EXT-X-MEDIA-SEQUENCE:")) {
                sequence = number(line.substring("#EXT-X-MEDIA-SEQUENCE:".length()), "EXT-X-MEDIA-SEQUENCE");
            } else if (line.startsWith("#EXTINF:")) {
                var value = line.substring("#EXTINF:".length());
                var comma = value.indexOf(',');
                var text = (comma >= 0 ? value.substring(0, comma) : value).strip();
                try {
                    var seconds = Double.parseDouble(text);
                    if (!(seconds >= 0) || seconds > 1e9) {
                        throw new NumberFormatException(text);
                    }
                    duration = Math.round(seconds * 1_000_000);
                } catch (NumberFormatException exception) {
                    throw new MediaException.Malformed("an EXTINF duration " + text);
                }
            } else if (line.startsWith("#EXT-X-BYTERANGE:")) {
                range = byteRange(line.substring("#EXT-X-BYTERANGE:".length()), false);
            } else if (line.startsWith("#EXT-X-MAP:")) {
                var attributes = attributes(line.substring("#EXT-X-MAP:".length()));
                var uri = attributes.get("URI");
                if (uri == null) {
                    throw new MediaException.Malformed("an EXT-X-MAP without a URI");
                }
                var mapRange = attributes.containsKey("BYTERANGE") ? byteRange(attributes.get("BYTERANGE"), true)
                        : new long[]{-1, 0};
                section = new Section(resolve(base, uri), mapRange[1], mapRange[0]);
            } else if (line.startsWith("#EXT-X-KEY:")) {
                var attributes = attributes(line.substring("#EXT-X-KEY:".length()));
                var method = attributes.getOrDefault("METHOD", "");
                if (method.equals("NONE")) {
                    keys.clear();
                    continue;
                }
                if (!List.of("AES-128", "SAMPLE-AES", "SAMPLE-AES-CTR").contains(method)) {
                    throw new MediaException.Unsupported("hls", "the key method " + method);
                }
                byte[] iv = null;
                var ivText = attributes.get("IV");
                if (ivText != null) {
                    if (!ivText.matches("0[xX][0-9a-fA-F]{1,32}")) {
                        throw new MediaException.Malformed("an EXT-X-KEY IV " + ivText);
                    }
                    var hex = "0".repeat(32 - (ivText.length() - 2)) + ivText.substring(2);
                    iv = HexFormat.of().parseHex(hex);
                }
                var uri = attributes.get("URI");
                var format = attributes.getOrDefault("KEYFORMAT", "identity");
                keys.put(format, new Key(method, uri == null ? null : resolve(base, uri), iv, format));
            } else if (line.equals("#EXT-X-GAP")) {
                gap = true;
            } else if (line.equals("#EXT-X-I-FRAMES-ONLY")) {
                iFramesOnly = true;
            } else if (!line.startsWith("#")) {
                if (duration < 0) {
                    throw new MediaException.Malformed("a segment URI without EXTINF");
                }
                var uri = resolve(base, line.strip());
                var offset = 0L;
                var length = -1L;
                if (range != null) {
                    length = range[0];
                    if (range[1] >= 0) {
                        offset = range[1];
                    } else {
                        var previous = ends.get(uri);
                        if (previous == null) {
                            throw new MediaException.Malformed("an EXT-X-BYTERANGE without an offset after no range"
                                    + " of " + uri);
                        }
                        offset = previous;
                    }
                    ends.put(uri, offset + length);
                }
                if (!gap) {
                    var key = keys.get("identity");
                    if (key == null && !keys.isEmpty()) {
                        key = keys.values().iterator().next();
                    }
                    segments.add(new Segment(uri, offset, length, duration, section, key, sequence));
                    if (segments.size() > MAX_SEGMENTS) {
                        throw new MediaException.LimitExceeded("an HLS media playlist of more than " + MAX_SEGMENTS
                                + " segments");
                    }
                }
                sequence++;
                duration = -1;
                range = null;
                gap = false;
            }
        }
        return new MediaPlaylist(segments, iFramesOnly);
    }

    /**
     * Parses a byte range {@code n[@o]}.
     *
     * @param text the range
     * @param offsetRequired whether the offset must be present
     * @return the length and the offset, -1 when absent
     * @throws MediaException.Malformed if the range is malformed
     */
    private static long[] byteRange(String text, boolean offsetRequired) {
        var at = text.indexOf('@');
        if (at < 0 && offsetRequired) {
            throw new MediaException.Malformed("a byte range without an offset: " + text);
        }
        var length = number(at < 0 ? text : text.substring(0, at), "byte range length");
        var offset = at < 0 ? -1 : number(text.substring(at + 1), "byte range offset");
        return new long[]{length, offset};
    }

    /**
     * Parses a decimal integer.
     *
     * @param text the decimal text
     * @param what what the number is, for the message
     * @return the number
     * @throws MediaException.Malformed if it is not a non-negative decimal integer
     */
    private static long number(String text, String what) {
        try {
            var value = Long.parseLong(text.strip());
            if (value < 0) {
                throw new NumberFormatException(text);
            }
            return value;
        } catch (NumberFormatException exception) {
            throw new MediaException.Malformed("an HLS " + what + " " + text);
        }
    }

    /**
     * Parses an attribute list (RFC 8216 clause 4.2), unquoting quoted strings.
     *
     * @param list the attribute list
     * @return the attributes, by name, in order
     * @throws MediaException.Malformed if the list is malformed
     */
    static Map<String, String> attributes(String list) {
        var attributes = new LinkedHashMap<String, String>();
        var position = 0;
        while (position < list.length()) {
            var equals = list.indexOf('=', position);
            if (equals < 0) {
                throw new MediaException.Malformed("an HLS attribute without a value in " + list);
            }
            var name = list.substring(position, equals).strip();
            position = equals + 1;
            String value;
            if (position < list.length() && list.charAt(position) == '"') {
                var close = list.indexOf('"', position + 1);
                if (close < 0) {
                    throw new MediaException.Malformed("an unterminated HLS quoted string in " + list);
                }
                value = list.substring(position + 1, close);
                position = close + 1;
            } else {
                var comma = list.indexOf(',', position);
                var end = comma < 0 ? list.length() : comma;
                value = list.substring(position, end).strip();
                position = end;
            }
            attributes.put(name, value);
            while (position < list.length() && (list.charAt(position) == ',' || list.charAt(position) == ' ')) {
                position++;
            }
        }
        return attributes;
    }

    /**
     * Resolves a URI against the location of the playlist that names it.
     *
     * @param base the location of the playlist
     * @param reference the URI
     * @return the resolved URI
     */
    static String resolve(String base, String reference) {
        return Uris.resolve(base, reference);
    }
}
