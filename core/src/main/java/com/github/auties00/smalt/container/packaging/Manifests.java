package com.github.auties00.smalt.container.packaging;

import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.VideoTrack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Writes HLS playlists (RFC 8216) and DASH manifests (ISO/IEC 23009-1) for packaged streams.
 */
final class Manifests {
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
     * Writes the media playlist of a stream (RFC 8216 clause 4.3.3): a VOD playlist with the
     * target duration, the initialization segment of a CMAF stream as {@code EXT-X-MAP}, and
     * every segment with its duration and byte range.
     *
     * @param stream the segmented stream the playlist describes
     * @param version the protocol version
     * @return the playlist text
     */
    static String mediaPlaylist(SegmentedStream stream, int version) {
        var durations = durations(stream);
        var target = 1L;
        for (var duration : durations) {
            target = Math.max(target, (duration + 500_000) / 1_000_000);
        }
        var text = new StringBuilder();
        text.append("#EXTM3U\n");
        text.append("#EXT-X-VERSION:").append(version).append('\n');
        text.append("#EXT-X-TARGETDURATION:").append(target).append('\n');
        text.append("#EXT-X-MEDIA-SEQUENCE:0\n");
        text.append("#EXT-X-PLAYLIST-TYPE:VOD\n");
        if (stream.independent()) {
            text.append("#EXT-X-INDEPENDENT-SEGMENTS\n");
        }
        if (stream.init != null) {
            text.append("#EXT-X-MAP:URI=\"").append(stream.init.uri()).append('"');
            if (version >= 4 && isRange(stream.init)) {
                text.append(",BYTERANGE=\"").append(stream.init.size()).append('@').append(stream.init.offset()).append('"');
            }
            text.append('\n');
        }
        for (var index = 0; index < stream.segments.size(); index++) {
            var segment = stream.segments.get(index);
            text.append("#EXTINF:").append(seconds(durations[index], 6)).append(",\n");
            if (isRange(segment)) {
                text.append("#EXT-X-BYTERANGE:").append(segment.size()).append('@').append(segment.offset()).append('\n');
            }
            text.append(segment.uri()).append('\n');
        }
        text.append("#EXT-X-ENDLIST\n");
        return text.toString();
    }

    /**
     * Returns the duration of each media segment of a stream: the distance to the next segment's
     * start, so that the durations add up to the timeline, and the measured duration for the last
     * segment.
     *
     * @param stream the segmented stream
     * @return the durations in microseconds
     */
    private static long[] durations(SegmentedStream stream) {
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
     * Returns whether a segment shares its file with others.
     *
     * @param segment a written segment
     * @return {@code true} when the segment is addressed by byte range
     */
    private static boolean isRange(SegmentedStream.Written segment) {
        return segment.ranged();
    }

    /**
     * Groups the streams of the presentation by rendition, video and audio apart.
     *
     * @param video the video stream of the rendition, or {@code null}
     * @param audio the audio stream of the rendition, or {@code null}
     * @param muxed the muxed stream of a transport stream rendition, or {@code null}
     */
    record RenditionStreams(SegmentedStream video, SegmentedStream audio, SegmentedStream muxed) {
    }

    /**
     * Writes the master playlist (RFC 8216 clause 4.3.4): one {@code EXT-X-STREAM-INF} per
     * rendition, with an {@code EXT-X-MEDIA} audio rendition group per CMAF rendition that has
     * audio next to video.
     *
     * @param renditions the streams of each rendition
     * @param version the protocol version
     * @return the playlist text
     */
    static String masterPlaylist(List<RenditionStreams> renditions, int version) {
        var text = new StringBuilder();
        text.append("#EXTM3U\n");
        text.append("#EXT-X-VERSION:").append(version).append('\n');
        var independent = true;
        for (var rendition : renditions) {
            for (var stream : new SegmentedStream[]{rendition.video, rendition.audio, rendition.muxed}) {
                if (stream != null && !stream.independent()) {
                    independent = false;
                }
            }
        }
        if (independent) {
            text.append("#EXT-X-INDEPENDENT-SEGMENTS\n");
        }
        for (var rendition : renditions) {
            if (rendition.video != null && rendition.audio != null) {
                var audio = rendition.audio;
                var track = audio.audio();
                text.append("#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"").append(groupOf(rendition)).append("\",NAME=\"")
                        .append(audio.rendition).append("\",DEFAULT=YES,AUTOSELECT=YES");
                if (track != null && track.language().isPresent()) {
                    text.append(",LANGUAGE=\"").append(track.language().get()).append('"');
                }
                if (track != null && track.layout().isPresent()) {
                    text.append(",CHANNELS=\"").append(track.layout().get().channels()).append('"');
                }
                text.append(",URI=\"").append(playlistName(audio)).append("\"\n");
            }
        }
        for (var rendition : renditions) {
            var main = rendition.muxed != null ? rendition.muxed : rendition.video != null ? rendition.video : rendition.audio;
            var peak = main.peakBandwidth();
            var average = main.averageBandwidth();
            var codecs = main.codecString();
            if (rendition.muxed == null && rendition.video != null && rendition.audio != null) {
                peak += rendition.audio.peakBandwidth();
                average += rendition.audio.averageBandwidth();
                codecs = codecs + "," + rendition.audio.codecString();
            }
            text.append("#EXT-X-STREAM-INF:BANDWIDTH=").append(Math.max(1, peak))
                    .append(",AVERAGE-BANDWIDTH=").append(Math.max(1, average));
            if (!codecs.isEmpty()) {
                text.append(",CODECS=\"").append(codecs).append('"');
            }
            var video = main.video();
            if (video != null && video.width() > 0 && video.height() > 0) {
                text.append(",RESOLUTION=").append(video.width()).append('x').append(video.height());
            }
            if (video != null && video.frameRate().isPresent()) {
                text.append(",FRAME-RATE=").append(String.format(Locale.ROOT, "%.3f", video.frameRate().get().toDouble()));
            }
            if (rendition.muxed == null && rendition.video != null && rendition.audio != null) {
                text.append(",AUDIO=\"").append(groupOf(rendition)).append('"');
            }
            text.append('\n').append(playlistName(main)).append('\n');
        }
        return text.toString();
    }

    /**
     * Returns the audio group of a rendition.
     *
     * @param rendition the streams of the rendition
     * @return the {@code GROUP-ID}
     */
    private static String groupOf(RenditionStreams rendition) {
        return "audio_" + rendition.audio.rendition;
    }

    /**
     * Returns the name of the media playlist of a stream.
     *
     * @param stream the segmented stream
     * @return the file name
     */
    static String playlistName(SegmentedStream stream) {
        return stream.name + ".m3u8";
    }

    /**
     * Writes a static DASH manifest (ISO/IEC 23009-1) for CMAF streams: one adaptation set per
     * kind, codec and language, one representation per stream, each with a
     * {@code SegmentTemplate} and {@code SegmentTimeline} under the live and CMAF profiles, or a
     * {@code SegmentList} with media ranges under the main profile when the streams are addressed
     * by byte range.
     *
     * @param streams the CMAF streams
     * @param durationMicros the presentation duration
     * @param minBuffer the minimum buffer time, in microseconds
     * @return the manifest text
     */
    static String dashManifest(List<SegmentedStream> streams, long durationMicros, long minBuffer) {
        var sets = new LinkedHashMap<String, List<SegmentedStream>>();
        for (var stream : streams) {
            var track = stream.tracks.getFirst();
            var key = track.kind() + "|" + track.codec() + "|" + track.language().orElse("");
            sets.computeIfAbsent(key, ignored -> new ArrayList<>()).add(stream);
        }
        var ranged = !streams.isEmpty() && streams.getFirst().init != null && isRange(streams.getFirst().init);
        var indexed = !streams.isEmpty() && streams.getFirst().isIndexed();
        var profiles = indexed ? "urn:mpeg:dash:profile:isoff-on-demand:2011"
                : ranged ? "urn:mpeg:dash:profile:isoff-main:2011"
                : "urn:mpeg:dash:profile:isoff-live:2011,urn:mpeg:dash:profile:cmaf:2019";
        var text = new StringBuilder();
        text.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        text.append("<MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\" profiles=\"").append(profiles)
                .append("\" type=\"static\" mediaPresentationDuration=\"")
                .append(duration(durationMicros)).append("\" minBufferTime=\"").append(duration(minBuffer)).append("\">\n");
        text.append("  <Period id=\"0\" start=\"PT0S\">\n");
        var setId = 0;
        for (Map.Entry<String, List<SegmentedStream>> entry : sets.entrySet()) {
            var first = entry.getValue().getFirst().tracks.getFirst();
            var video = first instanceof VideoTrack;
            text.append("    <AdaptationSet id=\"").append(setId++).append("\" contentType=\"").append(video ? "video" : "audio")
                    .append("\" mimeType=\"").append(video ? "video/mp4" : "audio/mp4")
                    .append(indexed ? "\" subsegmentAlignment=\"true\" subsegmentStartsWithSAP=\"1\"" : "\" startWithSAP=\"1\"");
            if (first.language().isPresent()) {
                text.append(" lang=\"").append(escape(first.language().get())).append('"');
            }
            text.append(">\n");
            for (var stream : entry.getValue()) {
                representation(text, stream);
            }
            text.append("    </AdaptationSet>\n");
        }
        text.append("  </Period>\n");
        text.append("</MPD>\n");
        return text.toString();
    }

    /**
     * Writes one {@code Representation}.
     *
     * @param text the manifest being written
     * @param stream the segmented stream the representation describes
     */
    private static void representation(StringBuilder text, SegmentedStream stream) {
        var track = stream.tracks.getFirst();
        text.append("      <Representation id=\"").append(escape(stream.name)).append("\" bandwidth=\"")
                .append(Math.max(1, stream.peakBandwidth())).append("\" codecs=\"").append(escape(stream.codecString()))
                .append('"');
        if (track instanceof VideoTrack video) {
            if (video.width() > 0 && video.height() > 0) {
                text.append(" width=\"").append(video.width()).append("\" height=\"").append(video.height()).append('"');
            }
            video.frameRate().ifPresent(rate -> text.append(" frameRate=\"").append(rate.reduce().numerator()).append('/')
                    .append(rate.reduce().denominator()).append('"'));
        }
        if (track instanceof AudioTrack audio && audio.sampleRate() > 0) {
            text.append(" audioSamplingRate=\"").append(audio.sampleRate()).append('"');
        }
        text.append(">\n");
        if (track instanceof AudioTrack audio && audio.layout().isPresent()) {
            text.append("        <AudioChannelConfiguration schemeIdUri=\"urn:mpeg:dash:23003:3:audio_channel_configuration:2011\"")
                    .append(" value=\"").append(audio.layout().get().channels()).append("\"/>\n");
        }
        var timescale = timescaleOf(track.timeBase());
        var ranged = stream.init != null && isRange(stream.init);
        if (stream.isIndexed()) {
            text.append("        <BaseURL>").append(escape(stream.init.uri())).append("</BaseURL>\n");
            text.append("        <SegmentBase timescale=\"").append(timescale).append("\" indexRange=\"")
                    .append(stream.indexOffset).append('-').append(stream.indexOffset + stream.indexSize - 1)
                    .append("\" indexRangeExact=\"true\">\n");
            text.append("          <Initialization range=\"").append(stream.init.offset()).append('-')
                    .append(stream.init.offset() + stream.init.size() - 1).append("\"/>\n");
            text.append("        </SegmentBase>\n");
        } else if (ranged) {
            text.append("        <SegmentList timescale=\"").append(timescale).append("\">\n");
            text.append("          <Initialization sourceURL=\"").append(escape(stream.init.uri())).append("\" range=\"")
                    .append(stream.init.offset()).append('-').append(stream.init.offset() + stream.init.size() - 1)
                    .append("\"/>\n");
            timeline(text, stream, timescale, "          ");
            for (var segment : stream.segments) {
                text.append("          <SegmentURL media=\"").append(escape(segment.uri())).append("\" mediaRange=\"")
                        .append(segment.offset()).append('-').append(segment.offset() + segment.size() - 1).append("\"/>\n");
            }
            text.append("        </SegmentList>\n");
        } else {
            text.append("        <SegmentTemplate timescale=\"").append(timescale).append("\" initialization=\"")
                    .append(escape(stream.name)).append("_init.mp4\" media=\"").append(escape(stream.name))
                    .append("_$Number%05d$.m4s\" startNumber=\"1\">\n");
            timeline(text, stream, timescale, "          ");
            text.append("        </SegmentTemplate>\n");
        }
        text.append("      </Representation>\n");
    }

    /**
     * Writes a {@code SegmentTimeline}, merging runs of equal durations with {@code r}.
     *
     * @param text the manifest being written
     * @param stream the segmented stream whose segments are listed
     * @param timescale the ticks per second of the timeline
     * @param indent the indentation of the element
     */
    private static void timeline(StringBuilder text, SegmentedStream stream, long timescale, String indent) {
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
        var index = 0;
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
     * Escapes the XML special characters of an attribute value.
     *
     * @param value the raw attribute value
     * @return the escaped value
     */
    private static String escape(String value) {
        Objects.requireNonNull(value, "value");
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
