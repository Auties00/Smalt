package com.github.auties00.smalt.container.packaging;

import com.github.auties00.smalt.container.internal.Languages;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.TrackDisposition;
import com.github.auties00.smalt.track.VideoTrack;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Writes HLS playlists (RFC 8216) and DASH manifests (ISO/IEC 23009-1) for a packaged presentation.
 */
final class Manifests {
    /**
     * The format of program dates and DASH times: UTC with milliseconds.
     */
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'",
            Locale.ROOT).withZone(ZoneOffset.UTC);

    /**
     * The group of the HLS subtitle renditions.
     */
    private static final String SUBTITLE_GROUP = "subs";

    private Manifests() {
        throw new AssertionError();
    }

    /**
     * Returns the HLS protocol version a playlist of the packaging needs (RFC 8216 clause 7).
     *
     * @param cmaf whether segments are fragmented MP4
     * @param byteRanges whether segments are addressed by byte range
     * @return the version
     */
    static int hlsVersion(boolean cmaf, boolean byteRanges) {
        if (cmaf) {
            return 7;
        }
        return byteRanges ? 4 : 3;
    }

    /**
     * Returns the index of the first segment a playlist or manifest lists: zero, or for a live presentation the
     * first of the last {@link PackagingOptions#windowSegments()} segments, extended to last three target
     * durations (RFC 8216 clause 6.2.2).
     *
     * @param presentation the packaged presentation, its options and streams
     * @param stream the segmented stream
     * @return the index
     */
    static int windowStart(Presentation presentation, SegmentedStream stream) {
        if (presentation.options.presentation() != PackagingOptions.Presentation.LIVE) {
            return 0;
        }
        var durations = durations(stream);
        var first = Math.max(0, durations.length - presentation.options.windowSegments());
        var length = 0L;
        for (var index = first; index < durations.length; index++) {
            length += durations[index];
        }
        var needed = 3 * target(durations) * 1_000_000;
        while (first > 0 && length < needed) {
            first--;
            length += durations[first];
        }
        return first;
    }

    /**
     * Returns the target duration of segments: their longest duration, rounded to whole seconds, at least one.
     *
     * @param durations the durations, in microseconds
     * @return the target duration in seconds
     */
    private static long target(long[] durations) {
        var target = 1L;
        for (var duration : durations) {
            target = Math.max(target, (duration + 500_000) / 1_000_000);
        }
        return target;
    }

    /**
     * Writes the media playlist of an output (RFC 8216 clause 4.3.3): the target duration, the playlist type of
     * the presentation, the initialization segment of a CMAF stream as {@code EXT-X-MAP}, the encryption, and
     * every segment of the window with its program date, duration and byte range.
     *
     * @param presentation the packaged presentation, its options and streams
     * @param output the output the playlist describes
     * @return the playlist text
     */
    static String mediaPlaylist(Presentation presentation, Presentation.Output output) {
        var stream = output.stream;
        var durations = durations(stream);
        var first = windowStart(presentation, stream);
        var text = new StringBuilder();
        header(text, presentation, target(durations), first);
        if (stream.independent()) {
            text.append("#EXT-X-INDEPENDENT-SEGMENTS\n");
        }
        if (stream.init != null) {
            text.append("#EXT-X-MAP:URI=\"").append(stream.init.uri()).append('"');
            if (presentation.version >= 4 && stream.init.ranged()) {
                text.append(",BYTERANGE=\"").append(stream.init.size()).append('@').append(stream.init.offset()).append('"');
            }
            text.append('\n');
        }
        if (presentation.keys != null && output.role != Presentation.Role.SUBTITLES) {
            text.append(presentation.keys.playlistTag()).append('\n');
        }
        for (var index = first; index < stream.segments.size(); index++) {
            var segment = stream.segments.get(index);
            programDate(text, presentation, segment.startMicros());
            text.append("#EXTINF:").append(seconds(durations[index], 6)).append(",\n");
            if (segment.ranged()) {
                text.append("#EXT-X-BYTERANGE:").append(segment.size()).append('@').append(segment.offset()).append('\n');
            }
            text.append(segment.uri()).append('\n');
        }
        footer(text, presentation);
        return text.toString();
    }

    /**
     * Writes the I-frame playlist of an output (RFC 8216 clause 4.3.3.6): one byte-range segment per key frame,
     * lasting until the next key frame, with the initialization section of a CMAF stream or the program tables of
     * the first transport stream segment as {@code EXT-X-MAP}.
     *
     * @param presentation the packaged presentation, its options and streams
     * @param output the output whose key frames the playlist addresses
     * @return the playlist text
     */
    static String iFramePlaylist(Presentation presentation, Presentation.Output output) {
        var stream = output.stream;
        var frames = stream.keyFrames;
        var first = windowStart(presentation, stream);
        var from = 0;
        if (first > 0 && first < stream.segments.size()) {
            var windowStart = stream.segments.get(first).startMicros();
            while (from < frames.size() && frames.get(from).startMicros() < windowStart) {
                from++;
            }
        }
        var durations = keyFrameDurations(stream);
        var text = new StringBuilder();
        header(text, presentation, target(durations), from);
        text.append("#EXT-X-I-FRAMES-ONLY\n");
        if (stream.init != null) {
            text.append("#EXT-X-MAP:URI=\"").append(stream.init.uri()).append('"');
            if (stream.init.ranged()) {
                text.append(",BYTERANGE=\"").append(stream.init.size()).append('@').append(stream.init.offset()).append('"');
            }
            text.append('\n');
        } else if (stream.tablesUri != null) {
            text.append("#EXT-X-MAP:URI=\"").append(stream.tablesUri).append("\",BYTERANGE=\"").append(stream.tablesSize)
                    .append('@').append(stream.tablesOffset).append("\"\n");
        }
        if (presentation.keys != null) {
            text.append(presentation.keys.playlistTag()).append('\n');
        }
        for (var index = from; index < frames.size(); index++) {
            var frame = frames.get(index);
            programDate(text, presentation, frame.startMicros());
            text.append("#EXTINF:").append(seconds(durations[index], 6)).append(",\n");
            text.append("#EXT-X-BYTERANGE:").append(frame.size()).append('@').append(frame.offset()).append('\n');
            text.append(frame.uri()).append('\n');
        }
        footer(text, presentation);
        return text.toString();
    }

    /**
     * Returns the duration of each key frame of a stream: the distance to the next one, and for the last the
     * distance to the end of the stream.
     *
     * @param stream the segmented stream
     * @return the durations in microseconds
     */
    static long[] keyFrameDurations(SegmentedStream stream) {
        var frames = stream.keyFrames;
        var durations = new long[frames.size()];
        var end = stream.durationMicros();
        for (var index = 0; index < durations.length; index++) {
            var next = index + 1 < durations.length ? frames.get(index + 1).startMicros() : end;
            durations[index] = Math.max(0, next - frames.get(index).startMicros());
        }
        return durations;
    }

    /**
     * Writes the tags that open a media playlist.
     *
     * @param text the playlist being written
     * @param presentation the packaged presentation, its options and streams
     * @param target the target duration in seconds
     * @param sequence the media sequence number of the first segment listed
     */
    private static void header(StringBuilder text, Presentation presentation, long target, int sequence) {
        text.append("#EXTM3U\n");
        text.append("#EXT-X-VERSION:").append(presentation.version).append('\n');
        text.append("#EXT-X-TARGETDURATION:").append(target).append('\n');
        text.append("#EXT-X-MEDIA-SEQUENCE:").append(sequence).append('\n');
        switch (presentation.options.presentation()) {
            case ON_DEMAND -> text.append("#EXT-X-PLAYLIST-TYPE:VOD\n");
            case EVENT -> text.append("#EXT-X-PLAYLIST-TYPE:EVENT\n");
            case LIVE -> {
            }
        }
    }

    /**
     * Writes the tag that closes a complete media playlist.
     *
     * @param text the playlist being written
     * @param presentation the packaged presentation, its options and streams
     */
    private static void footer(StringBuilder text, Presentation presentation) {
        if (presentation.ended || presentation.options.presentation() == PackagingOptions.Presentation.ON_DEMAND) {
            text.append("#EXT-X-ENDLIST\n");
        }
    }

    /**
     * Writes the program date of a segment, when the options ask for them.
     *
     * @param text the playlist being written
     * @param presentation the packaged presentation, its options and streams
     * @param startMicros the media time of the segment
     */
    private static void programDate(StringBuilder text, Presentation presentation, long startMicros) {
        if (presentation.options.programDateTime().isPresent()) {
            text.append("#EXT-X-PROGRAM-DATE-TIME:").append(TIME.format(at(presentation.start, startMicros)))
                    .append('\n');
        }
    }

    /**
     * Returns a wall-clock time a media time after another.
     *
     * @param start the wall-clock time of media time zero
     * @param micros the media time
     * @return the time
     */
    private static Instant at(Instant start, long micros) {
        return start.plusNanos(Math.multiplyExact(micros, 1000L));
    }

    /**
     * Returns the duration of each media segment of a stream: the distance to the next segment's
     * start, so that the durations add up to the timeline, and the measured duration for the last
     * segment.
     *
     * @param stream the segmented stream
     * @return the durations in microseconds
     */
    static long[] durations(SegmentedStream stream) {
        var segments = stream.segments;
        var durations = new long[segments.size()];
        for (var index = 0; index < durations.length; index++) {
            var segment = segments.get(index);
            var next = index + 1 < durations.length ? segments.get(index + 1).startMicros() - segment.startMicros() : 0;
            durations[index] = next > 0 ? next : Math.max(0, segment.durationMicros());
        }
        return durations;
    }

    /**
     * Writes the master playlist (RFC 8216 clause 4.3.4): the audio rendition groups, the subtitle group, one
     * {@code EXT-X-STREAM-INF} per variant (and per audio group, when the presentation has audio renditions) and
     * one {@code EXT-X-I-FRAME-STREAM-INF} per I-frame playlist.
     *
     * @param presentation the packaged presentation, its options and streams
     * @return the playlist text
     */
    static String masterPlaylist(Presentation presentation) {
        var outputs = presentation.outputs;
        var text = new StringBuilder();
        text.append("#EXTM3U\n");
        text.append("#EXT-X-VERSION:").append(presentation.version).append('\n');
        var independent = true;
        for (var output : outputs) {
            if (output.role != Presentation.Role.SUBTITLES && !output.stream.independent()) {
                independent = false;
            }
        }
        if (independent) {
            text.append("#EXT-X-INDEPENDENT-SEGMENTS\n");
        }
        var groups = new LinkedHashMap<String, List<Presentation.Output>>();
        if (presentation.alternateAudio) {
            var byCodec = new LinkedHashMap<String, List<Presentation.Output>>();
            for (var output : outputs) {
                if (output.role == Presentation.Role.AUDIO && output.member.kind() == Rendition.Kind.AUDIO) {
                    byCodec.computeIfAbsent(output.stream.codecString(), ignored -> new ArrayList<>()).add(output);
                }
            }
            for (var entry : byCodec.entrySet()) {
                var id = byCodec.size() == 1 ? "audio" : "audio-" + entry.getKey().replaceAll("[^A-Za-z0-9._-]", "_");
                groups.put(id, entry.getValue());
            }
        } else {
            for (var output : outputs) {
                if (output.role == Presentation.Role.AUDIO && videoOf(presentation, output.member) != null) {
                    groups.put("audio_" + output.member.name, List.of(output));
                }
            }
        }
        for (var entry : groups.entrySet()) {
            var renditions = entry.getValue();
            var chosen = renditions.stream().filter(output -> output.member.rendition.isDefault()).findFirst()
                    .orElse(renditions.getFirst());
            for (var output : renditions) {
                var track = output.stream.audio();
                text.append("#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"").append(entry.getKey()).append("\",NAME=\"")
                        .append(output.member.label()).append('"');
                var language = output.member.language();
                if (language != null) {
                    text.append(",LANGUAGE=\"").append(language).append('"');
                }
                text.append(",DEFAULT=").append(output == chosen ? "YES" : "NO").append(",AUTOSELECT=YES");
                if (track != null && track.layout().isPresent()) {
                    text.append(",CHANNELS=\"").append(track.layout().get().channels()).append('"');
                }
                var dispositions = output.member.dispositions;
                if (dispositions.contains(TrackDisposition.VISUAL_IMPAIRED)
                        || dispositions.contains(TrackDisposition.DESCRIPTIONS)) {
                    text.append(",CHARACTERISTICS=\"public.accessibility.describes-video\"");
                }
                text.append(",URI=\"").append(output.playlistName()).append("\"\n");
            }
        }
        var subtitles = outputs.stream().filter(output -> output.role == Presentation.Role.SUBTITLES).toList();
        var defaultSubtitles = subtitles.stream().filter(output -> output.member.rendition.isDefault()).findFirst()
                .orElse(null);
        for (var output : subtitles) {
            text.append("#EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID=\"").append(SUBTITLE_GROUP).append("\",NAME=\"")
                    .append(output.member.label()).append('"');
            var language = output.member.language();
            if (language != null) {
                text.append(",LANGUAGE=\"").append(language).append('"');
            }
            text.append(",DEFAULT=").append(output == defaultSubtitles ? "YES" : "NO").append(",AUTOSELECT=YES");
            if (output.member.rendition.isForced()) {
                text.append(",FORCED=YES");
            }
            var dispositions = output.member.dispositions;
            if (dispositions.contains(TrackDisposition.HEARING_IMPAIRED)
                    || dispositions.contains(TrackDisposition.CAPTIONS)) {
                text.append(",CHARACTERISTICS=\"public.accessibility.transcribes-spoken-dialog,"
                        + "public.accessibility.describes-music-and-sound\"");
            }
            text.append(",URI=\"").append(output.playlistName()).append("\"\n");
        }
        var subtitleGroup = subtitles.isEmpty() ? null : SUBTITLE_GROUP;
        var mains = new ArrayList<Presentation.Output>();
        for (var output : outputs) {
            if (output.member.kind() != Rendition.Kind.VARIANT) {
                continue;
            }
            if (output.role == Presentation.Role.MUXED || output.role == Presentation.Role.VIDEO
                    || output.role == Presentation.Role.AUDIO && videoOf(presentation, output.member) == null) {
                mains.add(output);
            }
        }
        if (mains.isEmpty()) {
            for (var entry : groups.entrySet()) {
                var renditions = entry.getValue();
                var chosen = renditions.stream().filter(output -> output.member.rendition.isDefault()).findFirst()
                        .orElse(renditions.getFirst());
                variant(text, null, entry.getKey(), renditions, subtitleGroup, subtitles, chosen.playlistName());
            }
        }
        for (var main : mains) {
            if (presentation.alternateAudio) {
                for (var entry : groups.entrySet()) {
                    variant(text, main, entry.getKey(), entry.getValue(), subtitleGroup, subtitles,
                            main.playlistName());
                }
                continue;
            }
            var group = groups.entrySet().stream().filter(entry -> entry.getValue().getFirst().member == main.member
                    && main.role == Presentation.Role.VIDEO).findFirst().orElse(null);
            variant(text, main, group == null ? null : group.getKey(), group == null ? List.of() : group.getValue(),
                    subtitleGroup, subtitles, main.playlistName());
        }
        for (var output : outputs) {
            if (!output.iFrames) {
                continue;
            }
            var stream = output.stream;
            var frames = stream.keyFrames;
            var durations = keyFrameDurations(stream);
            var peak = 0L;
            var bits = 0L;
            var micros = 0L;
            for (var index = 0; index < frames.size(); index++) {
                if (durations[index] > 0) {
                    peak = Math.max(peak, frames.get(index).size() * 8_000_000L / durations[index]);
                }
                bits += frames.get(index).size() * 8;
                micros += durations[index];
            }
            var average = micros > 0 ? bits * 1_000_000L / micros : 0;
            text.append("#EXT-X-I-FRAME-STREAM-INF:BANDWIDTH=").append(Math.max(1, Math.max(peak, average)))
                    .append(",AVERAGE-BANDWIDTH=").append(Math.max(1, average));
            var video = stream.video();
            var codec = video == null ? null : CodecStrings.of(video, stream.keyFrame);
            if (codec != null) {
                text.append(",CODECS=\"").append(codec).append('"');
            }
            if (video != null && video.width() > 0 && video.height() > 0) {
                text.append(",RESOLUTION=").append(video.width()).append('x').append(video.height());
            }
            text.append(",URI=\"").append(output.iFramePlaylistName()).append("\"\n");
        }
        return text.toString();
    }

    /**
     * Returns the video stream of a rendition.
     *
     * @param presentation the packaged presentation, its options and streams
     * @param member the rendition
     * @return the stream carrying its video, or {@code null}
     */
    private static Presentation.Output videoOf(Presentation presentation, Presentation.Member member) {
        for (var output : presentation.outputs) {
            if (output.member == member && output.hasVideo() && output.role != Presentation.Role.TRICK) {
                return output;
            }
        }
        return null;
    }

    /**
     * Writes one {@code EXT-X-STREAM-INF}.
     *
     * @param text the playlist being written
     * @param main the video or muxed stream of the variant, or {@code null} for an audio-only variant of an audio
     * group
     * @param group the audio group, or {@code null}
     * @param renditions the streams of the audio group
     * @param subtitles the subtitle group, or {@code null}
     * @param subtitleStreams the streams of the subtitle group, whose largest bit rates the bandwidths include
     * @param uri the media playlist of the variant
     */
    private static void variant(StringBuilder text, Presentation.Output main, String group,
                                List<Presentation.Output> renditions, String subtitles,
                                List<Presentation.Output> subtitleStreams, String uri) {
        var peak = main == null ? 0 : main.stream.peakBandwidth();
        var average = main == null ? 0 : main.stream.averageBandwidth();
        var codecs = new ArrayList<String>();
        if (main != null && !main.stream.codecString().isEmpty()) {
            codecs.add(main.stream.codecString());
        }
        var groupPeak = 0L;
        var groupAverage = 0L;
        for (var rendition : renditions) {
            groupPeak = Math.max(groupPeak, rendition.stream.peakBandwidth());
            groupAverage = Math.max(groupAverage, rendition.stream.averageBandwidth());
            var codec = rendition.stream.codecString();
            if (!codec.isEmpty() && !codecs.contains(codec)) {
                codecs.add(codec);
            }
        }
        peak += groupPeak;
        average += groupAverage;
        var subtitlePeak = 0L;
        var subtitleAverage = 0L;
        for (var rendition : subtitleStreams) {
            subtitlePeak = Math.max(subtitlePeak, rendition.stream.peakBandwidth());
            subtitleAverage = Math.max(subtitleAverage, rendition.stream.averageBandwidth());
        }
        peak += subtitlePeak;
        average += subtitleAverage;
        text.append("#EXT-X-STREAM-INF:BANDWIDTH=").append(Math.max(1, peak))
                .append(",AVERAGE-BANDWIDTH=").append(Math.max(1, average));
        if (!codecs.isEmpty()) {
            text.append(",CODECS=\"").append(String.join(",", codecs)).append('"');
        }
        var video = main == null ? null : main.stream.video();
        if (video != null && video.width() > 0 && video.height() > 0) {
            text.append(",RESOLUTION=").append(video.width()).append('x').append(video.height());
        }
        if (video != null && video.frameRate().isPresent()) {
            text.append(",FRAME-RATE=").append(String.format(Locale.ROOT, "%.3f", video.frameRate().get().toDouble()));
        }
        if (group != null) {
            text.append(",AUDIO=\"").append(group).append('"');
        }
        if (subtitles != null) {
            text.append(",SUBTITLES=\"").append(subtitles).append('"');
        }
        text.append('\n').append(uri).append('\n');
    }

    /**
     * Writes the DASH manifest (ISO/IEC 23009-1) of the CMAF streams: static for a presentation on demand, dynamic
     * for an event or live presentation; one adaptation set per kind, codec, language and role, one representation
     * per stream, each with a {@code SegmentTemplate} and {@code SegmentTimeline} under the live and CMAF profiles,
     * a {@code SegmentList} with media ranges under the main profile when the streams are addressed by byte range,
     * or a {@code SegmentBase} under the on-demand profile when each is one indexed file.
     *
     * @param presentation the packaged presentation, its options and streams
     * @return the manifest text
     */
    static String dashManifest(Presentation presentation) {
        var options = presentation.options;
        var streams = new ArrayList<Presentation.Output>();
        for (var output : presentation.outputs) {
            if (output.role == Presentation.Role.VIDEO || output.role == Presentation.Role.AUDIO
                    || output.role == Presentation.Role.TEXT || output.role == Presentation.Role.TRICK) {
                streams.add(output);
            }
        }
        var sets = new LinkedHashMap<String, List<Presentation.Output>>();
        var keyOf = new HashMap<Presentation.Output, String>();
        for (var output : streams) {
            var key = setKey(output, keyOf);
            keyOf.put(output, key);
            sets.computeIfAbsent(key, ignored -> new ArrayList<>()).add(output);
        }
        var ids = new HashMap<String, Integer>();
        for (var key : sets.keySet()) {
            ids.put(key, ids.size());
        }
        var first = streams.isEmpty() ? null : streams.getFirst().stream;
        var ranged = first != null && first.init != null && first.init.ranged();
        var indexed = first != null && first.isIndexed();
        var profiles = indexed ? "urn:mpeg:dash:profile:isoff-on-demand:2011"
                : ranged ? "urn:mpeg:dash:profile:isoff-main:2011"
                : "urn:mpeg:dash:profile:isoff-live:2011,urn:mpeg:dash:profile:cmaf:2019";
        var dynamic = options.presentation() != PackagingOptions.Presentation.ON_DEMAND;
        var scheme = presentation.keys == null ? null : presentation.keys.dashScheme();
        var keyId = presentation.keys == null ? null : presentation.keys.settings().keyId().orElse(null);
        var text = new StringBuilder();
        text.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        text.append("<MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\"");
        if (scheme != null) {
            text.append(" xmlns:cenc=\"urn:mpeg:cenc:2013\"");
        }
        text.append(" profiles=\"").append(profiles).append('"');
        String publish = null;
        if (dynamic) {
            var reached = 0L;
            var longest = 0L;
            var depth = 0L;
            for (var output : streams) {
                reached = Math.max(reached, output.stream.durationMicros());
                longest = Math.max(longest, output.stream.longestSegment());
                var durations = durations(output.stream);
                var window = 0L;
                for (var index = windowStart(presentation, output.stream); index < durations.length; index++) {
                    window += durations[index];
                }
                depth = Math.max(depth, window);
            }
            var time = at(presentation.start, reached);
            if (presentation.published == null || time.isAfter(presentation.published)) {
                presentation.published = time;
            }
            publish = TIME.format(presentation.published);
            text.append(" type=\"dynamic\" availabilityStartTime=\"").append(TIME.format(presentation.start))
                    .append("\" publishTime=\"").append(publish).append('"');
            if (presentation.ended) {
                text.append(" mediaPresentationDuration=\"").append(duration(presentation.durationMicros())).append('"');
            } else {
                text.append(" minimumUpdatePeriod=\"").append(duration(options.segmentMicros())).append('"');
            }
            if (options.presentation() == PackagingOptions.Presentation.LIVE) {
                text.append(" timeShiftBufferDepth=\"").append(duration(depth)).append('"');
            }
            text.append(" maxSegmentDuration=\"").append(duration(longest)).append('"');
        } else {
            text.append(" type=\"static\" mediaPresentationDuration=\"").append(duration(presentation.durationMicros()))
                    .append('"');
        }
        text.append(" minBufferTime=\"").append(duration(Presentation.MIN_BUFFER_MICROS)).append("\">\n");
        text.append("  <Period id=\"0\" start=\"PT0S\">\n");
        for (Map.Entry<String, List<Presentation.Output>> entry : sets.entrySet()) {
            var members = entry.getValue();
            var head = members.getFirst();
            var track = head.stream.tracks.getFirst();
            var contentType = head.role == Presentation.Role.TEXT ? "text" : track instanceof VideoTrack ? "video"
                    : "audio";
            var mime = head.role == Presentation.Role.TEXT ? "application/mp4" : contentType + "/mp4";
            text.append("    <AdaptationSet id=\"").append(ids.get(entry.getKey())).append("\" contentType=\"")
                    .append(contentType).append("\" mimeType=\"").append(mime)
                    .append(indexed ? "\" subsegmentAlignment=\"true\" subsegmentStartsWithSAP=\"1\"" : "\" startWithSAP=\"1\"");
            var language = head.role == Presentation.Role.VIDEO || head.role == Presentation.Role.TRICK ? null
                    : head.member.kind() == Rendition.Kind.VARIANT ? track.language()
                    .filter(code -> !code.equals("und")).map(Languages::languageTag).orElse(null)
                    : head.member.language();
            if (language != null) {
                text.append(" lang=\"").append(escape(language)).append('"');
            }
            text.append(">\n");
            if (scheme != null && head.role != Presentation.Role.TEXT) {
                text.append("      <ContentProtection schemeIdUri=\"urn:mpeg:dash:mp4protection:2011\" value=\"")
                        .append(scheme).append('"');
                if (keyId != null) {
                    var hex = HexFormat.of().formatHex(keyId);
                    text.append(" cenc:default_KID=\"").append(hex, 0, 8).append('-').append(hex, 8, 12).append('-')
                            .append(hex, 12, 16).append('-').append(hex, 16, 20).append('-').append(hex, 20, 32)
                            .append('"');
                }
                text.append("/>\n");
                text.append("      <ContentProtection schemeIdUri=\"urn:uuid:e2719d58-a985-b3c9-781a-b030af78d30e\""
                        + " value=\"ClearKey1.0\"/>\n");
            }
            if (head.role == Presentation.Role.TRICK) {
                text.append("      <EssentialProperty schemeIdUri=\"http://dashif.org/guidelines/trickmode\" value=\"")
                        .append(ids.get(keyOf.get(head.main))).append("\"/>\n");
            }
            if (head.member.kind() != Rendition.Kind.VARIANT) {
                text.append("      <Label>").append(escape(head.member.label())).append("</Label>\n");
            }
            var dispositions = head.member.dispositions;
            if (head.member.kind() == Rendition.Kind.AUDIO && (dispositions.contains(TrackDisposition.VISUAL_IMPAIRED)
                    || dispositions.contains(TrackDisposition.DESCRIPTIONS))) {
                text.append("      <Accessibility schemeIdUri=\"urn:tva:metadata:cs:AudioPurposeCS:2007\" value=\"1\"/>\n");
            }
            for (var role : roles(presentation, head)) {
                text.append("      <Role schemeIdUri=\"urn:mpeg:dash:role:2011\" value=\"").append(role).append("\"/>\n");
            }
            for (var output : members) {
                representation(text, presentation, output);
            }
            text.append("    </AdaptationSet>\n");
        }
        text.append("  </Period>\n");
        if (publish != null) {
            text.append("  <UTCTiming schemeIdUri=\"urn:mpeg:dash:utc:direct:2014\" value=\"").append(publish)
                    .append("\"/>\n");
        }
        text.append("</MPD>\n");
        return text.toString();
    }

    /**
     * Returns the adaptation set of a stream: video by codec, the audio of variants by codec and language, audio
     * and text renditions by codec, language, label and roles, and trick play by the set of its video.
     *
     * @param output the stream
     * @param keys the sets of the streams before it
     * @return the key of its adaptation set
     */
    private static String setKey(Presentation.Output output, Map<Presentation.Output, String> keys) {
        var track = output.stream.tracks.getFirst();
        var member = output.member;
        return switch (output.role) {
            case VIDEO -> "video|" + track.codec();
            case TRICK -> "trick|" + keys.getOrDefault(output.main, "video|" + track.codec());
            case AUDIO -> member.kind() == Rendition.Kind.VARIANT
                    ? "audio|" + track.codec() + "|" + track.language().orElse("")
                    : "rendition|" + track.codec() + "|" + member.language() + "|" + member.label() + "|"
                    + member.dispositions;
            case TEXT -> "text|" + track.codec() + "|" + member.language() + "|" + member.label() + "|"
                    + member.rendition.isForced() + "|" + member.dispositions;
            default -> throw new IllegalArgumentException(output.role.name());
        };
    }

    /**
     * Returns the values of the DASH role scheme ({@code urn:mpeg:dash:role:2011}) of an adaptation set.
     *
     * @param presentation the packaged presentation, its options and streams
     * @param output the first stream of the set
     * @return the roles: {@code main} or {@code alternate} for audio renditions, with {@code commentary},
     * {@code dub} and {@code description} from their dispositions, and {@code subtitle},
     * {@code forced-subtitle} or {@code caption} for text
     */
    private static List<String> roles(Presentation presentation, Presentation.Output output) {
        var roles = new ArrayList<String>();
        var member = output.member;
        var dispositions = member.dispositions;
        if (output.role == Presentation.Role.TEXT) {
            roles.add(member.rendition.isForced() ? "forced-subtitle"
                    : dispositions.contains(TrackDisposition.HEARING_IMPAIRED)
                    || dispositions.contains(TrackDisposition.CAPTIONS) ? "caption" : "subtitle");
            return roles;
        }
        if (output.role != Presentation.Role.AUDIO || member.kind() != Rendition.Kind.AUDIO) {
            return roles;
        }
        var codec = output.stream.codecString();
        Presentation.Member chosen = null;
        for (var candidate : presentation.outputs) {
            if (candidate.role == Presentation.Role.AUDIO && candidate.member.kind() == Rendition.Kind.AUDIO
                    && candidate.stream.codecString().equals(codec)
                    && (chosen == null || candidate.member.rendition.isDefault() && !chosen.rendition.isDefault())) {
                chosen = candidate.member;
            }
        }
        roles.add(chosen == member ? "main" : "alternate");
        if (dispositions.contains(TrackDisposition.COMMENTARY)) {
            roles.add("commentary");
        }
        if (dispositions.contains(TrackDisposition.DUB)) {
            roles.add("dub");
        }
        if (dispositions.contains(TrackDisposition.VISUAL_IMPAIRED) || dispositions.contains(TrackDisposition.DESCRIPTIONS)) {
            roles.add("description");
        }
        return roles;
    }

    /**
     * Writes one {@code Representation}.
     *
     * @param text the manifest being written
     * @param presentation the packaged presentation, its options and streams
     * @param output the stream the representation describes
     */
    private static void representation(StringBuilder text, Presentation presentation, Presentation.Output output) {
        var stream = output.stream;
        var track = stream.tracks.getFirst();
        text.append("      <Representation id=\"").append(escape(stream.name)).append("\" bandwidth=\"")
                .append(Math.max(1, stream.peakBandwidth())).append("\" codecs=\"").append(escape(stream.codecString()))
                .append('"');
        if (track instanceof VideoTrack video) {
            if (video.width() > 0 && video.height() > 0) {
                text.append(" width=\"").append(video.width()).append("\" height=\"").append(video.height()).append('"');
            }
            if (output.role != Presentation.Role.TRICK) {
                video.frameRate().ifPresent(rate -> text.append(" frameRate=\"").append(rate.reduce().numerator())
                        .append('/').append(rate.reduce().denominator()).append('"'));
            }
        }
        if (track instanceof AudioTrack audio && audio.sampleRate() > 0) {
            text.append(" audioSamplingRate=\"").append(audio.sampleRate()).append('"');
        }
        if (output.role == Presentation.Role.TRICK) {
            text.append(" maxPlayoutRate=\"").append(maxPlayoutRate(output)).append("\" codingDependency=\"false\"");
        }
        text.append(">\n");
        if (track instanceof AudioTrack audio && audio.layout().isPresent()) {
            text.append("        <AudioChannelConfiguration schemeIdUri=\"urn:mpeg:dash:23003:3:audio_channel_configuration:2011\"")
                    .append(" value=\"").append(audio.layout().get().channels()).append("\"/>\n");
        }
        var timescale = timescaleOf(track.timeBase());
        var ranged = stream.init != null && stream.init.ranged();
        var first = windowStart(presentation, stream);
        if (stream.isIndexed()) {
            text.append("        <BaseURL>").append(escape(stream.init.uri())).append("</BaseURL>\n");
            text.append("        <SegmentBase timescale=\"").append(timescale).append("\" indexRange=\"")
                    .append(stream.indexOffset).append('-').append(stream.indexOffset + stream.indexSize - 1)
                    .append("\" indexRangeExact=\"true\">\n");
            text.append("          <Initialization range=\"").append(stream.init.offset()).append('-')
                    .append(stream.init.offset() + stream.init.size() - 1).append("\"/>\n");
            text.append("        </SegmentBase>\n");
        } else if (ranged) {
            text.append("        <SegmentList timescale=\"").append(timescale).append('"');
            if (first > 0) {
                text.append(" startNumber=\"").append(first + 1).append('"');
            }
            text.append(">\n");
            text.append("          <Initialization sourceURL=\"").append(escape(stream.init.uri())).append("\" range=\"")
                    .append(stream.init.offset()).append('-').append(stream.init.offset() + stream.init.size() - 1)
                    .append("\"/>\n");
            timeline(text, stream, timescale, "          ", first);
            for (var index = first; index < stream.segments.size(); index++) {
                var segment = stream.segments.get(index);
                text.append("          <SegmentURL media=\"").append(escape(segment.uri())).append("\" mediaRange=\"")
                        .append(segment.offset()).append('-').append(segment.offset() + segment.size() - 1).append("\"/>\n");
            }
            text.append("        </SegmentList>\n");
        } else {
            text.append("        <SegmentTemplate timescale=\"").append(timescale).append("\" initialization=\"")
                    .append(escape(stream.name)).append("_init.mp4\" media=\"").append(escape(stream.name))
                    .append("_$Number%05d$.m4s\" startNumber=\"").append(first + 1).append("\">\n");
            timeline(text, stream, timescale, "          ", first);
            text.append("        </SegmentTemplate>\n");
        }
        text.append("      </Representation>\n");
    }

    /**
     * Returns the highest playout rate of a trick-play stream: the average number of frames of its video per key
     * frame, at least one.
     *
     * @param output the trick-play stream
     * @return the rate
     */
    private static long maxPlayoutRate(Presentation.Output output) {
        var main = output.main;
        if (main == null || output.samples == 0) {
            return 1;
        }
        return Math.max(1, Math.round((double) main.samples / output.samples));
    }

    /**
     * Writes a {@code SegmentTimeline}, merging runs of equal durations with {@code r}.
     *
     * @param text the manifest being written
     * @param stream the segmented stream whose segments are listed
     * @param timescale the ticks per second of the timeline
     * @param indent the indentation of the element
     * @param from the index of the first segment listed
     */
    private static void timeline(StringBuilder text, SegmentedStream stream, long timescale, String indent, int from) {
        text.append(indent).append("<SegmentTimeline>\n");
        var segments = stream.segments;
        var starts = new long[segments.size() + 1];
        for (var index = 0; index < segments.size(); index++) {
            starts[index] = ticks(segments.get(index).startMicros(), timescale);
        }
        if (!segments.isEmpty()) {
            var last = segments.getLast();
            starts[segments.size()] = ticks(last.startMicros() + last.durationMicros(), timescale);
        }
        var index = from;
        while (index < segments.size()) {
            var duration = Math.max(1, starts[index + 1] - starts[index]);
            var repeat = 0;
            while (index + repeat + 1 < segments.size()
                    && starts[index + repeat + 2] - starts[index + repeat + 1] == duration) {
                repeat++;
            }
            text.append(indent).append("  <S t=\"").append(starts[index]).append("\" d=\"").append(duration).append('"');
            if (repeat > 0) {
                text.append(" r=\"").append(repeat).append('"');
            }
            text.append("/>\n");
            index += repeat + 1;
        }
        text.append(indent).append("</SegmentTimeline>\n");
    }

    /**
     * Returns the timescale of a representation: the track's, when its time base is one tick of
     * a whole rate, and 90 kHz otherwise.
     *
     * @param timeBase the track time base
     * @return the timescale
     */
    private static long timescaleOf(Rational timeBase) {
        var reduced = timeBase.reduce();
        return reduced.numerator() == 1 && reduced.denominator() <= Integer.MAX_VALUE ? reduced.denominator() : 90_000;
    }

    /**
     * Converts microseconds to timescale ticks, rounding to nearest.
     *
     * @param micros the time
     * @param timescale the ticks per second
     * @return the ticks
     */
    private static long ticks(long micros, long timescale) {
        return Rational.MICROSECONDS.rescale(micros, Rational.timeBaseOf(timescale));
    }

    /**
     * Formats a duration as an ISO 8601 duration in seconds.
     *
     * @param micros the duration
     * @return the duration, for example {@code PT6.006S}
     */
    private static String duration(long micros) {
        return "PT" + seconds(micros, 3) + "S";
    }

    /**
     * Formats microseconds as decimal seconds.
     *
     * @param micros the time
     * @param decimals the number of decimals
     * @return the seconds
     */
    private static String seconds(long micros, int decimals) {
        return String.format(Locale.ROOT, "%." + decimals + "f", micros / 1_000_000.0);
    }

    /**
     * Escapes the XML special characters of an attribute value or character data.
     *
     * @param value the raw value
     * @return the escaped value
     */
    private static String escape(String value) {
        Objects.requireNonNull(value, "value");
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
