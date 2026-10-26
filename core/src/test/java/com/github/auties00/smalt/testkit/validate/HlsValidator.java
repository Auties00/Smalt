package com.github.auties00.smalt.testkit.validate;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Checks an HLS presentation against RFC 8216 in the manner of Apple's {@code mediastreamvalidator}: the playlist
 * syntax and the tags each playlist type requires, the compatibility version the features need, target and segment
 * durations, every URI and byte range, every segment (as a transport stream, or as an fMP4 media segment against its
 * initialization segment), the continuity of the fragment timelines, independent segments starting with a sync
 * sample, and, in the master playlist, {@code BANDWIDTH} and {@code AVERAGE-BANDWIDTH} against the measured segment
 * bit rates, {@code CODECS} and {@code RESOLUTION} against the segments, and the rendition groups. WebVTT segments
 * are checked for their {@code X-TIMESTAMP-MAP} and cue timings, each cue placed on the timeline of the first
 * variant: it must first appear in the segment during which it starts and be repeated while it is shown (RFC 8216
 * clause 3.5). I-frame playlists ({@code EXT-X-I-FRAMES-ONLY}) must address key frames: a video PES packet at a
 * random access point, or a movie fragment starting with a sync sample. Event and live playlists follow the rules of
 * RFC 8216 clause 6.2.2 (an event never removes segments, a live window lasts three target durations), and program
 * dates advance by the segment durations.
 */
public final class HlsValidator {
    /** The highest compatibility version RFC 8216 defines. */
    private static final int MAX_VERSION = 7;

    /**
     * What validation measured of a media playlist.
     *
     * @param peakBitrate the largest segment bit rate, in bits per second
     * @param averageBitrate the average segment bit rate, in bits per second
     * @param codecs the codecs parameters derived from the segments
     * @param width the picture width, or 0 when unknown
     * @param height the picture height, or 0 when unknown
     * @param startTicks the 90 kHz timestamp of the first segment's start, or -1 when unknown
     */
    public record Media(long peakBitrate, long averageBitrate, Set<String> codecs, int width, int height,
                        long startTicks) {
    }

    private final Findings findings;

    /** The 90 kHz timestamp of the first variant's start, or {@link Long#MIN_VALUE} when unknown. */
    private long subtitleOrigin = Long.MIN_VALUE;

    private HlsValidator(Findings findings) {
        this.findings = findings;
    }

    /**
     * Validates a master playlist, with every media playlist and segment it references, or a media playlist.
     *
     * @param playlist the playlist
     * @param findings receives the problems
     */
    public static void validate(Path playlist, Findings findings) {
        var validator = new HlsValidator(findings);
        var lines = validator.read(playlist);
        if (lines == null) {
            return;
        }
        var master = lines.stream().anyMatch(line -> line.startsWith("#EXT-X-STREAM-INF")
                || line.startsWith("#EXT-X-MEDIA:"));
        if (master) {
            validator.master(playlist, lines);
        } else {
            validator.media(playlist, lines);
        }
    }

    private List<String> read(Path playlist) {
        var where = playlist.getFileName().toString();
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(playlist);
        } catch (IOException exception) {
            findings.add(where, "unreadable: " + exception);
            return null;
        }
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException exception) {
            findings.add(where, "not UTF-8");
            return null;
        }
        findings.require(!text.startsWith("﻿"), where, "a byte order mark");
        findings.require(text.indexOf('\r') < 0 || text.contains("\r\n"), where, "a line ending of CR alone");
        var lines = new ArrayList<String>();
        for (var line : text.split("\r?\n")) {
            lines.add(line);
        }
        findings.require(!lines.isEmpty() && lines.getFirst().equals("#EXTM3U"), where, "the first line is not #EXTM3U");
        return lines;
    }

    private void master(Path playlist, List<String> lines) {
        var where = playlist.getFileName().toString();
        var version = version(lines, where);
        subtitleOrigin = originOf(playlist, lines);
        var groups = new LinkedHashMap<String, List<Media>>();
        var groupFiles = new HashMap<String, List<Path>>();
        var defaults = new HashMap<String, Integer>();
        var names = new HashMap<String, Set<String>>();
        var variants = 0;
        for (var index = 0; index < lines.size(); index++) {
            var line = lines.get(index);
            if (line.startsWith("#EXTINF") || line.startsWith("#EXT-X-TARGETDURATION")) {
                findings.add(where + ":" + (index + 1), "a media playlist tag in a master playlist");
            }
            if (line.startsWith("#EXT-X-MEDIA:")) {
                var attributes = attributes(line.substring("#EXT-X-MEDIA:".length()), where + ":" + (index + 1));
                var at = where + ":" + (index + 1);
                for (var required : List.of("TYPE", "GROUP-ID", "NAME")) {
                    findings.require(attributes.containsKey(required), at, "EXT-X-MEDIA without " + required);
                }
                var type = attributes.getOrDefault("TYPE", "");
                findings.require(Set.of("AUDIO", "VIDEO", "SUBTITLES", "CLOSED-CAPTIONS").contains(type), at,
                        "TYPE " + type);
                findings.require(!attributes.containsKey("FORCED") || type.equals("SUBTITLES"), at,
                        "FORCED on a rendition that is not SUBTITLES");
                if ("YES".equals(attributes.get("FORCED"))) {
                    findings.require(!"NO".equals(attributes.get("AUTOSELECT")), at, "FORCED=YES with AUTOSELECT=NO");
                }
                if (attributes.containsKey("LANGUAGE")) {
                    var language = unquote(attributes.get("LANGUAGE"));
                    findings.require(language.matches("[A-Za-z]{2,8}(-[A-Za-z0-9]{1,8})*"), at, "LANGUAGE " + language
                            + " is not an RFC 5646 tag");
                }
                if (type.equals("SUBTITLES")) {
                    findings.require(attributes.containsKey("URI"), at, "a SUBTITLES rendition without a URI");
                }
                var group = type + "/" + unquote(attributes.getOrDefault("GROUP-ID", ""));
                findings.require(names.computeIfAbsent(group, key -> new java.util.HashSet<>())
                        .add(unquote(attributes.getOrDefault("NAME", ""))), at, "a duplicate NAME in group " + group);
                if ("YES".equals(attributes.get("DEFAULT"))) {
                    defaults.merge(group, 1, Integer::sum);
                    findings.require(!"NO".equals(attributes.get("AUTOSELECT")), at,
                            "DEFAULT=YES with AUTOSELECT=NO");
                }
                var renditions = groups.computeIfAbsent(group, key -> new ArrayList<>());
                if (attributes.containsKey("URI")) {
                    findings.require(!type.equals("CLOSED-CAPTIONS"), at, "a URI on CLOSED-CAPTIONS");
                    var media = resolve(playlist, unquote(attributes.get("URI")), at);
                    if (media != null) {
                        groupFiles.computeIfAbsent(group, key -> new ArrayList<>()).add(media);
                        var lines2 = read(media);
                        if (lines2 != null) {
                            var measured = media(media, lines2);
                            if (measured != null) {
                                renditions.add(measured);
                            }
                        }
                    }
                }
            }
        }
        for (var entry : defaults.entrySet()) {
            findings.require(entry.getValue() <= 1, where, "group " + entry.getKey() + " has " + entry.getValue()
                    + " DEFAULT=YES renditions");
        }
        for (var index = 0; index < lines.size(); index++) {
            var line = lines.get(index);
            if (line.startsWith("#EXT-X-I-FRAME-STREAM-INF:")) {
                iFrameStream(playlist, line, where + ":" + (index + 1));
                continue;
            }
            if (!line.startsWith("#EXT-X-STREAM-INF:")) {
                continue;
            }
            variants++;
            var at = where + ":" + (index + 1);
            var attributes = attributes(line.substring("#EXT-X-STREAM-INF:".length()), at);
            var bandwidth = number(attributes.get("BANDWIDTH"), at, "BANDWIDTH");
            var uri = index + 1 < lines.size() ? lines.get(index + 1) : "";
            if (uri.isEmpty() || uri.startsWith("#")) {
                findings.add(at, "EXT-X-STREAM-INF is not followed by a URI line");
                continue;
            }
            var file = resolve(playlist, uri, at);
            if (file == null) {
                continue;
            }
            var mediaLines = read(file);
            if (mediaLines == null) {
                continue;
            }
            var measured = media(file, mediaLines);
            if (measured == null) {
                continue;
            }
            var peak = measured.peakBitrate();
            var average = measured.averageBitrate();
            var codecs = new LinkedHashSet<>(measured.codecs());
            for (var kind : List.of("AUDIO", "VIDEO", "SUBTITLES")) {
                if (!attributes.containsKey(kind)) {
                    continue;
                }
                var group = kind + "/" + unquote(attributes.get(kind));
                var renditions = groups.get(group);
                if (renditions == null) {
                    findings.add(at, kind + " group " + group + " is not declared by EXT-X-MEDIA");
                    continue;
                }
                if (groupFiles.getOrDefault(group, List.of()).contains(file)) {
                    // the variant is one of the group's renditions, already measured
                    continue;
                }
                var groupPeak = 0L;
                var groupAverage = 0L;
                for (var rendition : renditions) {
                    groupPeak = Math.max(groupPeak, rendition.peakBitrate());
                    groupAverage = Math.max(groupAverage, rendition.averageBitrate());
                    codecs.addAll(rendition.codecs());
                }
                peak += groupPeak;
                average += groupAverage;
            }
            if (bandwidth >= 0) {
                findings.require(bandwidth >= peak * 0.98, at, "BANDWIDTH " + bandwidth
                        + " is below the peak segment bit rate " + peak);
            }
            if (attributes.containsKey("AVERAGE-BANDWIDTH")) {
                var declared = number(attributes.get("AVERAGE-BANDWIDTH"), at, "AVERAGE-BANDWIDTH");
                findings.require(Math.abs(declared - average) <= average * 0.1 + 1000, at, "AVERAGE-BANDWIDTH "
                        + declared + " differs from the average segment bit rate " + average + " by over 10%");
                findings.require(declared <= bandwidth, at, "AVERAGE-BANDWIDTH above BANDWIDTH");
            }
            if (attributes.containsKey("CODECS")) {
                codecs(unquote(attributes.get("CODECS")), codecs, at);
            } else {
                findings.add(at, "EXT-X-STREAM-INF without CODECS");
            }
            if (measured.width() > 0) {
                var resolution = attributes.get("RESOLUTION");
                findings.require((measured.width() + "x" + measured.height()).equals(resolution), at, "RESOLUTION "
                        + resolution + ", the segments are " + measured.width() + "x" + measured.height());
            }
        }
        findings.require(variants > 0, where, "a master playlist without EXT-X-STREAM-INF");
        findings.require(version <= MAX_VERSION, where, "EXT-X-VERSION " + version);
    }

    /**
     * Checks an {@code EXT-X-I-FRAME-STREAM-INF} and its I-frame playlist: the URI, the I-frame segments, and the
     * {@code BANDWIDTH}, {@code CODECS} and {@code RESOLUTION} against them.
     */
    private void iFrameStream(Path playlist, String line, String at) {
        var attributes = attributes(line.substring("#EXT-X-I-FRAME-STREAM-INF:".length()), at);
        if (!attributes.containsKey("URI")) {
            findings.add(at, "EXT-X-I-FRAME-STREAM-INF without a URI");
            return;
        }
        var bandwidth = number(attributes.get("BANDWIDTH"), at, "BANDWIDTH");
        var file = resolve(playlist, unquote(attributes.get("URI")), at);
        var lines = file == null ? null : read(file);
        if (lines == null) {
            return;
        }
        findings.require(lines.contains("#EXT-X-I-FRAMES-ONLY"), at, "an I-frame stream whose playlist is not "
                + "EXT-X-I-FRAMES-ONLY");
        var measured = media(file, lines);
        if (measured == null) {
            return;
        }
        if (bandwidth >= 0) {
            findings.require(bandwidth >= measured.peakBitrate() * 0.98, at, "BANDWIDTH " + bandwidth
                    + " is below the peak I-frame bit rate " + measured.peakBitrate());
        }
        if (attributes.containsKey("CODECS")) {
            codecs(unquote(attributes.get("CODECS")), measured.codecs(), at);
        } else {
            findings.add(at, "EXT-X-I-FRAME-STREAM-INF without CODECS");
        }
        if (measured.width() > 0) {
            var resolution = attributes.get("RESOLUTION");
            findings.require((measured.width() + "x" + measured.height()).equals(resolution), at, "RESOLUTION "
                    + resolution + ", the I-frames are " + measured.width() + "x" + measured.height());
        }
    }

    private void codecs(String declared, Set<String> derived, String at) {
        var tokens = new ArrayList<String>();
        for (var token : declared.split(",")) {
            tokens.add(token.trim());
        }
        for (var codec : derived) {
            findings.require(tokens.stream().anyMatch(token -> matches(token, codec)), at, "CODECS \"" + declared
                    + "\" does not list " + codec + ", which the segments carry");
        }
        for (var token : tokens) {
            findings.require(derived.stream().anyMatch(codec -> matches(token, codec)), at, "CODECS lists " + token
                    + ", which no segment carries (they carry " + derived + ")");
        }
    }

    private static boolean matches(String declared, String derived) {
        if (!derived.contains(".")) {
            return declared.equals(derived) || declared.startsWith(derived + ".");
        }
        return SampleEntryCodecs.agree(declared, derived);
    }

    private Media media(Path playlist, List<String> lines) {
        var where = playlist.getFileName().toString();
        var version = version(lines, where);
        var target = -1L;
        var targetCount = 0;
        var endList = false;
        String type = null;
        var independent = false;
        var iFrames = lines.contains("#EXT-X-I-FRAMES-ONLY");
        var map = (Segment) null;
        var duration = -1.0;
        var range = (long[]) null;
        var lastRangeEnd = new HashMap<Path, Long>();
        var segments = new ArrayList<Segment>();
        var needs = iFrames ? 4 : 1;
        Key key = null;
        var sequence = 0L;
        var firstSequence = 0L;
        java.time.Instant programDate = null;
        java.time.Instant previousDate = null;
        var previousDuration = 0.0;
        var discontinuity = false;
        var total = 0.0;
        for (var index = 0; index < lines.size(); index++) {
            var line = lines.get(index);
            var at = where + ":" + (index + 1);
            if (line.isEmpty()) {
                continue;
            }
            if (line.startsWith("#EXT-X-TARGETDURATION:")) {
                target = number(line.substring(22), at, "EXT-X-TARGETDURATION");
                targetCount++;
            } else if (line.startsWith("#EXT-X-MEDIA-SEQUENCE:")) {
                findings.require(segments.isEmpty() && duration < 0, at, "EXT-X-MEDIA-SEQUENCE after a segment");
                sequence = number(line.substring(22), at, "EXT-X-MEDIA-SEQUENCE");
                firstSequence = sequence;
            } else if (line.startsWith("#EXT-X-PLAYLIST-TYPE:")) {
                type = line.substring(21);
                findings.require(type.equals("VOD") || type.equals("EVENT"), at, "EXT-X-PLAYLIST-TYPE " + type);
            } else if (line.equals("#EXT-X-ENDLIST")) {
                endList = true;
            } else if (line.equals("#EXT-X-INDEPENDENT-SEGMENTS")) {
                independent = true;
            } else if (line.equals("#EXT-X-DISCONTINUITY")) {
                discontinuity = true;
            } else if (line.startsWith("#EXT-X-PROGRAM-DATE-TIME:")) {
                try {
                    programDate = java.time.OffsetDateTime.parse(line.substring(25)).toInstant();
                } catch (java.time.format.DateTimeParseException exception) {
                    findings.add(at, "EXT-X-PROGRAM-DATE-TIME " + line.substring(25) + " is not an ISO 8601 date");
                }
            } else if (line.startsWith("#EXT-X-MAP:")) {
                needs = Math.max(needs, iFrames ? 5 : 6);
                var attributes = attributes(line.substring(11), at);
                var file = resolve(playlist, unquote(attributes.getOrDefault("URI", "\"\"")), at);
                var mapRange = attributes.containsKey("BYTERANGE")
                        ? byteRange(unquote(attributes.get("BYTERANGE")), 0, at, true) : null;
                if (file != null) {
                    map = new Segment(file, mapRange, 0, at);
                }
            } else if (line.startsWith("#EXTINF:")) {
                var value = line.substring(8);
                var comma = value.indexOf(',');
                findings.require(comma >= 0, at, "EXTINF without a comma");
                var text = comma >= 0 ? value.substring(0, comma) : value;
                try {
                    duration = Double.parseDouble(text);
                    if (text.contains(".")) {
                        needs = Math.max(needs, 3);
                    }
                } catch (NumberFormatException exception) {
                    findings.add(at, "EXTINF duration " + text);
                    duration = 0;
                }
            } else if (line.startsWith("#EXT-X-BYTERANGE:")) {
                needs = Math.max(needs, 4);
                range = new long[]{-1, -1};
                var parsed = byteRange(line.substring(17), -1, at, false);
                if (parsed != null) {
                    range = parsed;
                }
            } else if (line.startsWith("#EXT-X-KEY:")) {
                var attributes = attributes(line.substring(11), at);
                var method = attributes.getOrDefault("METHOD", "");
                findings.require(List.of("NONE", "AES-128", "SAMPLE-AES", "SAMPLE-AES-CTR").contains(method), at,
                        "EXT-X-KEY METHOD " + method);
                if (attributes.containsKey("IV")) {
                    needs = Math.max(needs, 2);
                    var iv = attributes.get("IV");
                    findings.require(iv.matches("0[xX][0-9a-fA-F]{32}"), at, "EXT-X-KEY IV " + iv);
                }
                if (!method.equals("NONE") && !method.equals("AES-128") || attributes.containsKey("KEYFORMAT")) {
                    needs = Math.max(needs, 5);
                }
                findings.require(method.equals("NONE") || attributes.containsKey("URI"), at, "EXT-X-KEY without a URI");
                key = null;
                if (method.equals("AES-128") && attributes.containsKey("URI")) {
                    var file = resolve(playlist, unquote(attributes.get("URI")), at);
                    var bytes = file == null ? null : bytes(file, null, at);
                    findings.require(bytes == null || bytes.length == 16, at, "an AES-128 key that is not 16 bytes");
                    if (bytes != null && bytes.length == 16) {
                        var iv = attributes.get("IV");
                        key = new Key(bytes, iv == null ? null : java.util.HexFormat.of().parseHex(iv.substring(2)));
                    }
                }
            } else if (line.startsWith("#EXT-X-STREAM-INF") || line.startsWith("#EXT-X-MEDIA:")) {
                findings.add(at, "a master playlist tag in a media playlist");
            } else if (!line.startsWith("#")) {
                if (duration < 0) {
                    findings.add(at, "a URI without EXTINF");
                    continue;
                }
                if (programDate != null && previousDate != null && !discontinuity) {
                    var gap = java.time.Duration.between(previousDate, programDate).toNanos() / 1e9;
                    findings.require(Math.abs(gap - previousDuration) <= 0.002, at, "EXT-X-PROGRAM-DATE-TIME advances "
                            + gap + " s over a segment of " + previousDuration + " s");
                }
                if (programDate != null) {
                    previousDate = programDate;
                }
                previousDuration = duration;
                discontinuity = false;
                programDate = null;
                var file = resolve(playlist, line, at);
                if (file != null) {
                    if (range != null && range[1] < 0) {
                        var previousEnd = lastRangeEnd.get(file);
                        if (previousEnd == null) {
                            findings.add(at, "EXT-X-BYTERANGE without an offset after no range of the same file");
                            previousEnd = 0L;
                        }
                        range = new long[]{range[0], previousEnd};
                    }
                    if (range != null) {
                        lastRangeEnd.put(file, range[1] + range[0]);
                    }
                    findings.require(!iFrames || range != null, at, "an I-frame segment without EXT-X-BYTERANGE");
                    segments.add(new Segment(file, range, duration, at, map, key, sequence));
                    if (target >= 0) {
                        findings.require(Math.round(duration) <= target, at, "EXTINF " + duration
                                + " rounds above EXT-X-TARGETDURATION " + target);
                    }
                }
                total += duration;
                duration = -1;
                range = null;
                sequence++;
            } else if (line.startsWith("#EXT") && !line.startsWith("#EXTM3U") && !known(line)) {
                findings.add(at, "an unknown tag " + line);
            }
        }
        findings.require(targetCount == 1, where, targetCount + " EXT-X-TARGETDURATION tags, exactly one required");
        findings.require(!"VOD".equals(type) || endList, where, "a VOD playlist without EXT-X-ENDLIST");
        findings.require(!"EVENT".equals(type) || firstSequence == 0, where, "an EVENT playlist that removed "
                + "segments (EXT-X-MEDIA-SEQUENCE " + firstSequence + ")");
        if (type == null && !endList && firstSequence > 0 && target > 0) {
            // RFC 8216 clause 6.2.2: a live playlist that dropped segments still lasts three target durations
            findings.require(total >= 3 * target - 0.001, where, "a live playlist of " + total + " s, below three "
                    + "target durations after removing segments");
        }
        findings.require(!segments.isEmpty(), where, "no segments");
        findings.require(version >= needs, where, "EXT-X-VERSION " + version + ", the playlist's features need "
                + needs);
        return segments(segments, independent, iFrames, target);
    }

    /**
     * A segment of a media playlist.
     *
     * @param file the file
     * @param range the length and offset of the byte range, or {@code null} for the whole file
     * @param duration the EXTINF duration in seconds
     * @param where the playlist line
     * @param map the initialization section, or {@code null} for transport stream segments
     * @param key the AES-128 key that encrypts the segment, or {@code null}
     * @param sequence the media sequence number of the segment
     */
    private record Segment(Path file, long[] range, double duration, String where, Segment map, Key key,
                           long sequence) {
        Segment(Path file, long[] range, double duration, String where) {
            this(file, range, duration, where, null, null, 0);
        }
    }

    /**
     * The AES-128 key of encrypted segments.
     *
     * @param key the 16-byte key
     * @param iv the initialization vector of every segment, or {@code null} for each segment's media sequence number
     */
    private record Key(byte[] key, byte[] iv) {
        byte[] decrypt(byte[] segment, long sequence) throws java.security.GeneralSecurityException {
            var vector = iv;
            if (vector == null) {
                vector = new byte[16];
                for (var index = 0; index < 8; index++) {
                    vector[15 - index] = (byte) (sequence >>> (8 * index));
                }
            }
            var cipher = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(javax.crypto.Cipher.DECRYPT_MODE, new javax.crypto.spec.SecretKeySpec(key, "AES"),
                    new javax.crypto.spec.IvParameterSpec(vector));
            return cipher.doFinal(segment);
        }
    }

    private Media segments(List<Segment> segments, boolean independent, boolean iFrames, long target) {
        var sizes = new ArrayList<double[]>();
        var bits = 0.0;
        var seconds = 0.0;
        var codecs = new LinkedHashSet<String>();
        var width = 0;
        var height = 0;
        var startTicks = -1L;
        var initializations = new HashMap<String, Map<Integer, IsoBmffValidator.Track>>();
        var nextDecode = new HashMap<Integer, Long>();
        var firstSegment = true;
        var elapsed = 0.0;
        Map<String, double[]> previousCues = null;
        for (var number = 0; number < segments.size(); number++) {
            var segment = segments.get(number);
            var windowStart = elapsed;
            elapsed += segment.duration();
            var stored = bytes(segment.file(), segment.range(), segment.where());
            if (stored == null) {
                continue;
            }
            var bytes = stored;
            var segmentFindings = new Findings(segment.where());
            if (segment.key() != null) {
                try {
                    bytes = segment.key().decrypt(stored, segment.sequence());
                } catch (java.security.GeneralSecurityException exception) {
                    findings.add(segment.where(), "an AES-128 segment that does not decrypt: " + exception);
                    continue;
                }
            }
            if (segment.map() == null && isWebVtt(bytes)) {
                previousCues = webVtt(bytes, windowStart, elapsed, number + 1 == segments.size(), previousCues,
                        segmentFindings);
            } else if (iFrames) {
                iFrame(bytes, segment, initializations, segmentFindings, codecs);
                if (segment.map() != null) {
                    var tracks = initializations.get(segment.map().file() + "@"
                            + (segment.map().range() == null ? "" : segment.map().range()[1]));
                    if (tracks != null) {
                        for (var track : tracks.values()) {
                            if (track.width() > 0) {
                                width = track.width();
                                height = track.height();
                            }
                        }
                    }
                }
            } else if (segment.map() != null) {
                var key = segment.map().file() + "@" + (segment.map().range() == null ? "" : segment.map().range()[1]);
                var tracks = initializations.get(key);
                if (tracks == null) {
                    var init = bytes(segment.map().file(), segment.map().range(), segment.map().where());
                    if (init == null) {
                        continue;
                    }
                    var initFindings = new Findings(segment.map().where());
                    var movie = IsoBmffValidator.validate(init, initFindings, IsoBmffValidator.Kind.INIT_SEGMENT,
                            Map.of());
                    initFindings.problems().forEach(problem -> findings.add("EXT-X-MAP " + segment.map().where(),
                            problem));
                    tracks = movie.tracks();
                    initializations.put(key, tracks);
                    for (var track : tracks.values()) {
                        if (!track.codecs().isEmpty()) {
                            codecs.add(track.codecs());
                        }
                        if (track.width() > 0) {
                            width = track.width();
                            height = track.height();
                        }
                    }
                }
                var movie = IsoBmffValidator.validate(bytes, segmentFindings, IsoBmffValidator.Kind.MEDIA_SEGMENT,
                        tracks);
                var minimum = Double.POSITIVE_INFINITY;
                var maximum = 0.0;
                var trimmed = 0.0;
                var perTrack = new HashMap<Integer, Long>();
                var first = new HashMap<Integer, IsoBmffValidator.Fragment>();
                for (var fragment : movie.fragments()) {
                    perTrack.merge(fragment.trackId(), fragment.duration(), Long::sum);
                    first.putIfAbsent(fragment.trackId(), fragment);
                }
                for (var entry : perTrack.entrySet()) {
                    var track = tracks.get(entry.getKey());
                    if (track == null || track.timescale() == 0) {
                        continue;
                    }
                    var trackSeconds = (double) entry.getValue() / track.timescale();
                    minimum = Math.min(minimum, trackSeconds);
                    maximum = Math.max(maximum, trackSeconds);
                    if (firstSegment) {
                        // the first EXTINF may count presentation time, after the edit list's skip
                        trimmed = Math.max(trimmed, (double) track.editOffset() / track.timescale());
                    }
                    var fragment = first.get(entry.getKey());
                    if (fragment.baseDecodeTime() >= 0) {
                        if (startTicks < 0) {
                            startTicks = Math.round((double) fragment.baseDecodeTime() * 90_000 / track.timescale());
                        }
                        var expected = nextDecode.get(entry.getKey());
                        if (expected != null) {
                            segmentFindings.require(expected == fragment.baseDecodeTime(), "track " + entry.getKey(),
                                    "starts at " + fragment.baseDecodeTime() + ", the previous segment ended at "
                                            + expected);
                        }
                        nextDecode.put(entry.getKey(), fragment.baseDecodeTime() + entry.getValue());
                    }
                    if (independent && track.handler().equals("vide")) {
                        segmentFindings.require(fragment.firstSync(), "track " + entry.getKey(),
                                "an independent segment that does not start with a sync sample");
                    }
                }
                if (!perTrack.isEmpty()) {
                    segmentFindings.require(segment.duration() >= minimum - trimmed - 0.001
                                    && segment.duration() <= maximum + 0.001, "EXTINF", "duration "
                                    + segment.duration() + " outside the tracks' " + (minimum - trimmed) + ".."
                                    + maximum + " s");
                }
                firstSegment = false;
            } else {
                var summary = TsValidator.validate(bytes, segmentFindings);
                segmentFindings.require(summary.tablesFirst(), "/", "a PES packet before the PAT and the PMT");
                codecs.addAll(TsCodecs.of(bytes, summary));
                if (startTicks < 0) {
                    startTicks = TsCodecs.firstPts(bytes, summary);
                }
            }
            segmentFindings.problems().forEach(problem -> findings.add(segment.where() + " " + segment.file()
                    .getFileName(), problem));
            sizes.add(new double[]{stored.length * 8.0, segment.duration()});
            bits += stored.length * 8.0;
            seconds += segment.duration();
        }
        return new Media(peak(sizes, target), seconds > 0 ? Math.round(bits / seconds) : 0, codecs, width, height,
                startTicks);
    }

    /**
     * Returns the peak segment bit rate of RFC 8216 clause 4.3.4.2: the largest bit rate of any contiguous set of
     * segments lasting between half and one and a half target durations; without such a set, the largest bit rate
     * of one segment.
     *
     * @param sizes the bits and the duration in seconds of each segment
     * @param target the target duration in seconds
     * @return the bit rate in bits per second
     */
    static long peak(List<double[]> sizes, long target) {
        var peak = 0.0;
        for (var first = 0; first < sizes.size(); first++) {
            var bits = 0.0;
            var seconds = 0.0;
            for (var last = first; last < sizes.size(); last++) {
                bits += sizes.get(last)[0];
                seconds += sizes.get(last)[1];
                if (target > 0 && seconds > 1.5 * target + 1e-9) {
                    break;
                }
                if (seconds > 0 && (target <= 0 || seconds >= 0.5 * target - 1e-9)) {
                    peak = Math.max(peak, bits / seconds);
                }
            }
        }
        if (peak == 0) {
            for (var size : sizes) {
                if (size[1] > 0) {
                    peak = Math.max(peak, size[0] / size[1]);
                }
            }
        }
        return (long) Math.ceil(peak);
    }

    /** Returns whether a segment is a WebVTT file. */
    private static boolean isWebVtt(byte[] bytes) {
        var text = new String(bytes, 0, Math.min(bytes.length, 16), StandardCharsets.UTF_8);
        if (text.startsWith("﻿")) {
            text = text.substring(1);
        }
        return text.startsWith("WEBVTT") && (text.length() == 6 || " \t\r\n".indexOf(text.charAt(6)) >= 0);
    }

    /**
     * Checks a WebVTT segment (RFC 8216 clause 3.5): the signature, the {@code X-TIMESTAMP-MAP}, the cue timings, and,
     * once the presentation's timeline is known, that each cue first appears in the segment during which it starts
     * and that a cue still shown at the end of a segment is repeated in the next.
     *
     * @return the cues of the segment, by text, with their start and end on the presentation timeline in seconds
     */
    private Map<String, double[]> webVtt(byte[] bytes, double windowStart, double windowEnd, boolean last,
                                         Map<String, double[]> previous, Findings segmentFindings) {
        var text = new String(bytes, StandardCharsets.UTF_8);
        if (text.startsWith("﻿")) {
            text = text.substring(1);
        }
        var lines = text.split("\r?\n", -1);
        var mpegTs = 0L;
        var local = 0.0;
        var index = 1;
        for (; index < lines.length && !lines[index].isBlank(); index++) {
            var line = lines[index];
            if (!line.startsWith("X-TIMESTAMP-MAP=")) {
                continue;
            }
            var fields = new HashMap<String, String>();
            for (var field : line.substring(16).split(",")) {
                var colon = field.indexOf(':');
                if (colon < 0) {
                    segmentFindings.add("X-TIMESTAMP-MAP", "a field without a colon: " + field);
                    continue;
                }
                fields.put(field.substring(0, colon).strip(), field.substring(colon + 1).strip());
            }
            segmentFindings.require(fields.containsKey("MPEGTS") && fields.containsKey("LOCAL"), "X-TIMESTAMP-MAP",
                    "not both MPEGTS and LOCAL: " + line);
            try {
                mpegTs = Long.parseLong(fields.getOrDefault("MPEGTS", "0"));
                segmentFindings.require(mpegTs >= 0 && mpegTs < 1L << 33, "X-TIMESTAMP-MAP", "MPEGTS " + mpegTs
                        + " is not a 33-bit timestamp");
            } catch (NumberFormatException exception) {
                segmentFindings.add("X-TIMESTAMP-MAP", "MPEGTS " + fields.get("MPEGTS"));
            }
            var parsed = cueTime(fields.getOrDefault("LOCAL", "00:00:00.000"));
            if (parsed < 0) {
                segmentFindings.add("X-TIMESTAMP-MAP", "LOCAL " + fields.get("LOCAL"));
            } else {
                local = parsed;
            }
        }
        var cues = new LinkedHashMap<String, double[]>();
        var timeline = subtitleOrigin != Long.MIN_VALUE;
        for (; index < lines.length; index++) {
            var line = lines[index];
            var arrow = line.indexOf("-->");
            if (arrow < 0) {
                continue;
            }
            var start = cueTime(line.substring(0, arrow).strip());
            var rest = line.substring(arrow + 3).strip();
            var space = rest.indexOf(' ');
            var end = cueTime(space < 0 ? rest : rest.substring(0, space));
            if (start < 0 || end < 0) {
                segmentFindings.add("cue", "a timing line that does not parse: " + line);
                continue;
            }
            segmentFindings.require(end >= start, "cue", "a cue that ends before it starts: " + line);
            var payload = new StringBuilder(line);
            for (var next = index + 1; next < lines.length && !lines[next].isBlank(); next++) {
                payload.append('\n').append(lines[next]);
            }
            var ticks = Math.floorMod(mpegTs - (timeline ? subtitleOrigin : 0), 1L << 33);
            if (ticks >= 1L << 32) {
                ticks -= 1L << 33;
            }
            var offset = ticks / 90_000.0 - local;
            var mapped = new double[]{start + offset, end + offset};
            cues.put(payload.toString(), mapped);
            if (!timeline) {
                continue;
            }
            var repeated = previous != null && previous.containsKey(payload.toString());
            if (repeated) {
                segmentFindings.require(mapped[0] < windowStart + 0.05, "cue", "a cue repeated from the previous segment"
                        + " starts at " + mapped[0] + " s, inside this segment " + windowStart + ".." + windowEnd);
            } else {
                segmentFindings.require(mapped[0] >= windowStart - 0.05 && (last || mapped[0] < windowEnd + 0.05),
                        "cue", "a cue starting at " + mapped[0] + " s first appears in the segment " + windowStart
                                + ".." + windowEnd + " s");
            }
        }
        if (timeline && previous != null) {
            for (var entry : previous.entrySet()) {
                if (entry.getValue()[1] > windowStart + 0.05) {
                    segmentFindings.require(cues.containsKey(entry.getKey()), "cue", "a cue shown until "
                            + entry.getValue()[1] + " s is not repeated in the segment that starts at " + windowStart
                            + " s");
                }
            }
        }
        segmentFindings.require(lines.length > 0 && lines[0].startsWith("WEBVTT"), "/", "a WebVTT segment that does "
                + "not start with WEBVTT");
        return cues;
    }

    /** Parses a WebVTT timestamp, {@code [hh:]mm:ss.ttt}, in seconds, or returns -1. */
    private static double cueTime(String text) {
        var matcher = java.util.regex.Pattern.compile("(?:(\\d+):)?([0-5]\\d):([0-5]\\d)\\.(\\d{3})").matcher(text);
        if (!matcher.matches()) {
            return -1;
        }
        var hours = matcher.group(1) == null ? 0 : Long.parseLong(matcher.group(1));
        return hours * 3600 + Integer.parseInt(matcher.group(2)) * 60 + Integer.parseInt(matcher.group(3))
                + Integer.parseInt(matcher.group(4)) / 1000.0;
    }

    /**
     * Checks an I-frame segment (RFC 8216 clause 4.3.3.6): with the program tables of its {@code EXT-X-MAP}, a
     * transport stream byte range must start with the PES packet of a key frame on a video PID; with an
     * initialization section, a fragmented MP4 byte range must start at a movie fragment whose first sample is a sync
     * sample and end at or after that sample's data.
     */
    private void iFrame(byte[] bytes, Segment segment, Map<String, Map<Integer, IsoBmffValidator.Track>> inits,
                        Findings segmentFindings, Set<String> codecs) {
        if (segment.map() == null) {
            segmentFindings.add("/", "an I-frame segment without EXT-X-MAP");
            return;
        }
        var map = bytes(segment.map().file(), segment.map().range(), segment.map().where());
        if (map == null) {
            return;
        }
        var key = segment.map().file() + "@" + (segment.map().range() == null ? "" : segment.map().range()[1]);
        if (isTransportStream(map)) {
            var tables = TsValidator.validate(map, new Findings("map"));
            var videoPids = new java.util.HashSet<Integer>();
            for (var entry : tables.streamTypes().entrySet()) {
                if (List.of(0x1B, 0xDB, 0x24, 0x10, 0x02).contains(entry.getValue())) {
                    videoPids.add(entry.getKey());
                }
            }
            segmentFindings.require(!videoPids.isEmpty(), "EXT-X-MAP", "program tables without a video stream");
            if (bytes.length < 188 || (bytes[0] & 0xFF) != 0x47) {
                segmentFindings.add("/", "an I-frame byte range that does not start with a transport packet");
                return;
            }
            segmentFindings.require(bytes.length % 188 == 0, "/", "an I-frame byte range of " + bytes.length
                    + " bytes, not whole transport packets");
            var pid = (bytes[1] & 0x1F) << 8 | bytes[2] & 0xFF;
            var control = (bytes[3] >> 4) & 3;
            var randomAccess = (control & 2) != 0 && (bytes[4] & 0xFF) > 0 && (bytes[5] & 0x40) != 0;
            segmentFindings.require(videoPids.contains(pid) && (bytes[1] & 0x40) != 0 && randomAccess, "/",
                    "an I-frame byte range that does not start with a video PES packet at a random access point");
            var joined = new byte[map.length + bytes.length];
            System.arraycopy(map, 0, joined, 0, map.length);
            System.arraycopy(bytes, 0, joined, map.length, bytes.length);
            var summary = TsValidator.validate(Arrays.copyOf(joined, map.length + 188), new Findings("i-frame"));
            for (var codec : TsCodecs.of(joined, summary)) {
                if (codec.startsWith("avc1") || codec.startsWith("hvc1")) {
                    codecs.add(codec);
                }
            }
            return;
        }
        var tracks = inits.get(key);
        if (tracks == null) {
            var initFindings = new Findings(segment.map().where());
            tracks = IsoBmffValidator.validate(map, initFindings, IsoBmffValidator.Kind.INIT_SEGMENT, Map.of()).tracks();
            initFindings.problems().forEach(problem -> findings.add("EXT-X-MAP " + segment.map().where(), problem));
            inits.put(key, tracks);
        }
        for (var track : tracks.values()) {
            if (!track.codecs().isEmpty()) {
                codecs.add(track.codecs());
            }
        }
        var boxes = IsoBmff.parse(bytes, new Findings("i-frame"));
        var moof = boxes.stream().filter(box -> box.type().equals("moof")).findFirst();
        if (moof.isEmpty() || boxes.indexOf(moof.get()) > 1
                || boxes.indexOf(moof.get()) == 1 && !boxes.getFirst().type().equals("styp")) {
            segmentFindings.add("/", "an I-frame byte range that does not start with a movie fragment");
            return;
        }
        var trun = moof.get().find("traf", "trun");
        var tfhd = moof.get().find("traf", "tfhd");
        if (trun.isEmpty() || tfhd.isEmpty()) {
            segmentFindings.add("moof", "a movie fragment without tfhd and trun");
            return;
        }
        var flags = IsoBmff.flags(bytes, trun.get());
        var at = trun.get().payload() + 8;
        var dataOffset = 0L;
        if ((flags & 0x01) != 0) {
            dataOffset = IsoBmff.s32(bytes, at);
            at += 4;
        }
        var sampleFlags = -1L;
        if ((flags & 0x04) != 0) {
            sampleFlags = IsoBmff.u32(bytes, at);
            at += 4;
        }
        if ((flags & 0x100) != 0) {
            at += 4;
        }
        var size = -1L;
        if ((flags & 0x200) != 0) {
            size = IsoBmff.u32(bytes, at);
            at += 4;
        }
        if (sampleFlags < 0 && (flags & 0x400) != 0) {
            sampleFlags = IsoBmff.u32(bytes, at);
        }
        segmentFindings.require(sampleFlags < 0 || (sampleFlags & 0x10000) == 0, "trun",
                "an I-frame whose first sample is not a sync sample");
        if (size >= 0) {
            segmentFindings.require(bytes.length >= moof.get().offset() + dataOffset + size, "/",
                    "an I-frame byte range that ends before its first sample");
        }
    }

    /** Returns whether bytes start with transport stream packets. */
    private static boolean isTransportStream(byte[] data) {
        return data.length >= 188 && (data[0] & 0xFF) == 0x47 && (data.length < 376 || (data[188] & 0xFF) == 0x47);
    }

    /**
     * Returns the 90 kHz timestamp the presentation's first variant starts at: the earliest PES presentation time of
     * its first transport stream segment, or the first decoding time of its first fMP4 segment, so that the cue times
     * of WebVTT segments can be placed on the presentation timeline; {@link Long#MIN_VALUE} when unknown.
     */
    private long originOf(Path master, List<String> lines) {
        for (var index = 0; index + 1 < lines.size(); index++) {
            if (!lines.get(index).startsWith("#EXT-X-STREAM-INF:")) {
                continue;
            }
            var quiet = new HlsValidator(new Findings("origin"));
            var file = quiet.resolve(master, lines.get(index + 1), "origin");
            var media = file == null ? null : quiet.read(file);
            if (media == null) {
                return Long.MIN_VALUE;
            }
            var measured = quiet.media(file, media);
            return measured == null || measured.startTicks() < 0 ? Long.MIN_VALUE : measured.startTicks();
        }
        return Long.MIN_VALUE;
    }

    private byte[] bytes(Path file, long[] range, String where) {
        byte[] all;
        try {
            all = Files.readAllBytes(file);
        } catch (IOException exception) {
            findings.add(where, "cannot read " + file + ": " + exception);
            return null;
        }
        if (range == null) {
            return all;
        }
        if (range[1] < 0 || range[1] + range[0] > all.length) {
            findings.add(where, "byte range " + range[0] + "@" + range[1] + " outside the " + all.length + "-byte "
                    + file.getFileName());
            return null;
        }
        return java.util.Arrays.copyOfRange(all, (int) range[1], (int) (range[1] + range[0]));
    }

    private long[] byteRange(String value, long fallbackOffset, String at, boolean offsetRequired) {
        var parts = value.split("@");
        try {
            var length = Long.parseLong(parts[0]);
            var offset = parts.length > 1 ? Long.parseLong(parts[1]) : fallbackOffset;
            if (offsetRequired && parts.length < 2) {
                findings.add(at, "a BYTERANGE without an offset");
            }
            findings.require(length > 0, at, "a byte range of length " + length);
            return new long[]{length, offset};
        } catch (NumberFormatException exception) {
            findings.add(at, "byte range " + value);
            return null;
        }
    }

    private Path resolve(Path playlist, String uri, String at) {
        if (uri.contains("://")) {
            findings.add(at, "an absolute URI " + uri + " in a local presentation");
            return null;
        }
        var file = playlist.toAbsolutePath().getParent().resolve(uri).normalize();
        if (!Files.isRegularFile(file)) {
            findings.add(at, uri + " does not exist");
            return null;
        }
        return file;
    }

    private int version(List<String> lines, String where) {
        var versions = lines.stream().filter(line -> line.startsWith("#EXT-X-VERSION:")).toList();
        findings.require(versions.size() <= 1, where, versions.size() + " EXT-X-VERSION tags");
        if (versions.isEmpty()) {
            return 1;
        }
        return (int) number(versions.getFirst().substring(15), where, "EXT-X-VERSION");
    }

    private long number(String text, String at, String what) {
        if (text == null) {
            findings.add(at, "no " + what);
            return -1;
        }
        try {
            var value = Long.parseLong(text.trim());
            findings.require(value >= 0, at, what + " " + value);
            return value;
        } catch (NumberFormatException exception) {
            findings.add(at, what + " " + text + " is not a decimal integer");
            return -1;
        }
    }

    private static boolean known(String line) {
        for (var tag : List.of("#EXT-X-VERSION", "#EXT-X-DISCONTINUITY", "#EXT-X-PROGRAM-DATE-TIME",
                "#EXT-X-KEY", "#EXT-X-DATERANGE", "#EXT-X-I-FRAMES-ONLY", "#EXT-X-START", "#EXT-X-GAP",
                "#EXT-X-BITRATE", "#EXT-X-SESSION-DATA", "#EXT-X-SESSION-KEY", "#EXT-X-I-FRAME-STREAM-INF",
                "#EXT-X-DEFINE", "#EXT-X-CONTENT-STEERING")) {
            if (line.startsWith(tag)) {
                return true;
            }
        }
        return false;
    }

    private Map<String, String> attributes(String list, String at) {
        var attributes = new LinkedHashMap<String, String>();
        var position = 0;
        while (position < list.length()) {
            var equals = list.indexOf('=', position);
            if (equals < 0) {
                findings.add(at, "an attribute without a value in " + list);
                break;
            }
            var name = list.substring(position, equals);
            findings.require(name.matches("[A-Z0-9-]+"), at, "attribute name " + name);
            var value = new StringBuilder();
            position = equals + 1;
            if (position < list.length() && list.charAt(position) == '"') {
                var close = list.indexOf('"', position + 1);
                if (close < 0) {
                    findings.add(at, "an unterminated quoted string");
                    break;
                }
                value.append(list, position, close + 1);
                position = close + 1;
            } else {
                while (position < list.length() && list.charAt(position) != ',') {
                    value.append(list.charAt(position++));
                }
            }
            findings.require(attributes.put(name, value.toString()) == null, at, "attribute " + name + " twice");
            if (position < list.length()) {
                findings.require(list.charAt(position) == ',', at, "no comma after attribute " + name);
                position++;
            }
        }
        return attributes;
    }

    private static String unquote(String value) {
        return value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")
                ? value.substring(1, value.length() - 1) : value;
    }

    /** Derives the codecs parameters of a transport stream's elementary streams. */
    static final class TsCodecs {
        private TsCodecs() {
        }

        static Set<String> of(byte[] data, TsValidator.Summary summary) {
            var codecs = new LinkedHashSet<String>();
            for (var entry : summary.streamTypes().entrySet()) {
                var codec = switch (entry.getValue()) {
                    case 0x1B, 0xDB -> avc(data, entry.getKey());
                    case 0x24 -> "hvc1";
                    case 0x0F, 0xCF -> aac(data, entry.getKey());
                    case 0x03, 0x04 -> "mp4a.40.34";
                    case 0x81, 0xC1 -> "ac-3";
                    case 0x87, 0xC2 -> "ec-3";
                    default -> null;
                };
                if (codec != null) {
                    codecs.add(codec);
                }
            }
            return codecs;
        }

        private static byte[] payload(byte[] data, int pid, int limit) {
            var out = new java.io.ByteArrayOutputStream();
            for (var at = 0; at + 188 <= data.length && out.size() < limit; at += 188) {
                var packetPid = (data[at + 1] & 0x1F) << 8 | data[at + 2] & 0xFF;
                var control = (data[at + 3] >> 4) & 3;
                if (packetPid != pid || (control & 1) == 0) {
                    continue;
                }
                var start = at + 4 + ((control & 2) != 0 ? 1 + (data[at + 4] & 0xFF) : 0);
                if (start < at + 188) {
                    out.write(data, start, at + 188 - start);
                }
            }
            return out.toByteArray();
        }

        /** Returns the earliest PES presentation time of the audio and video streams, in 90 kHz ticks, or -1. */
        static long firstPts(byte[] data, TsValidator.Summary summary) {
            var earliest = -1L;
            for (var at = 0; at + 188 <= data.length; at += 188) {
                var pid = (data[at + 1] & 0x1F) << 8 | data[at + 2] & 0xFF;
                var type = summary.streamTypes().get(pid);
                if (type == null || (data[at + 1] & 0x40) == 0 || type == 0x15 || type == 0x86) {
                    continue;
                }
                var control = (data[at + 3] >> 4) & 3;
                var start = at + 4 + ((control & 2) != 0 ? 1 + (data[at + 4] & 0xFF) : 0);
                if ((control & 1) == 0 || start + 14 > at + 188 || data[start] != 0 || data[start + 1] != 0
                        || data[start + 2] != 1 || (data[start + 7] & 0x80) == 0) {
                    continue;
                }
                var p = start + 9;
                var pts = ((long) (data[p] & 0x0E) << 29) | ((data[p + 1] & 0xFFL) << 22) | ((data[p + 2] & 0xFEL) << 14)
                        | ((data[p + 3] & 0xFFL) << 7) | ((data[p + 4] & 0xFEL) >> 1);
                earliest = earliest < 0 ? pts : Math.min(earliest, pts);
            }
            return earliest;
        }

        private static String avc(byte[] data, int pid) {
            var bytes = payload(data, pid, 64 * 1024);
            for (var i = 0; i + 7 < bytes.length; i++) {
                if (bytes[i] == 0 && bytes[i + 1] == 0 && bytes[i + 2] == 1 && (bytes[i + 3] & 0x1F) == 7) {
                    return String.format(Locale.ROOT, "avc1.%02X%02X%02X", bytes[i + 4] & 0xFF, bytes[i + 5] & 0xFF,
                            bytes[i + 6] & 0xFF);
                }
            }
            return "avc1";
        }

        private static String aac(byte[] data, int pid) {
            var bytes = payload(data, pid, 4096);
            for (var i = 0; i + 3 < bytes.length; i++) {
                if ((bytes[i] & 0xFF) == 0xFF && (bytes[i + 1] & 0xF6) == 0xF0) {
                    return "mp4a.40." + (((bytes[i + 2] & 0xFF) >> 6) + 1);
                }
            }
            return "mp4a";
        }
    }
}
