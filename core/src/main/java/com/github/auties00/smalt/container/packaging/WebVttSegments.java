package com.github.auties00.smalt.container.packaging;

import com.github.auties00.smalt.internal.subtitle.WebVttText;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.subtitle.SubtitleCue;
import com.github.auties00.smalt.subtitle.SubtitleDecoder;
import com.github.auties00.smalt.subtitle.SubtitleMarkup;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Track;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Cuts a text subtitle track into the WebVTT segments of an HLS subtitle rendition (RFC 8216 clause 3.5).
 *
 * <p>Each segment spans one segment of the presentation's reference stream and holds every cue shown during it,
 * so a cue that spans segments is repeated in each; its header maps the cue times, which are media times, to the
 * 90 kHz timestamps of the media segments with an {@code X-TIMESTAMP-MAP}. Cues of any text subtitle codec are
 * converted to WebVTT. Instances are not thread-safe.
 */
final class WebVttSegments {
    /**
     * The segments written.
     */
    final SegmentedStream stream;

    /**
     * Decodes the cues of the track's packets.
     */
    private final SubtitleDecoder decoder;

    /**
     * The header of every segment, with its {@code X-TIMESTAMP-MAP}, followed by a blank line.
     */
    private final String header;

    /**
     * The cues not yet past the segments written, in start order.
     */
    private final List<SubtitleCue> cues = new ArrayList<>();

    /**
     * Constructs the segmenter of a track.
     *
     * @param stream the stream the segments are recorded in
     * @param track the text subtitle track
     * @param mpegTs the 90 kHz media segment timestamp of media time zero
     */
    WebVttSegments(SegmentedStream stream, Track track, long mpegTs) {
        this.stream = stream;
        this.decoder = new SubtitleDecoder(track);
        var source = track.codec() == Codec.WEBVTT ? track.codecConfig()
                .map(bytes -> new String(bytes, StandardCharsets.UTF_8)).orElse(null) : null;
        var text = WebVttText.header(source);
        var split = text.indexOf("\n\n");
        var first = split < 0 ? text : text.substring(0, split);
        var rest = split < 0 ? "" : text.substring(split);
        this.header = first + "\nX-TIMESTAMP-MAP=MPEGTS:" + mpegTs + ",LOCAL:00:00:00.000" + rest + "\n\n";
    }

    /**
     * Adds the cues of a packet of the track.
     *
     * @param packet a packet of the subtitle track
     */
    void add(Packet packet) {
        insert(decoder.decodeAll(packet, SubtitleMarkup.WEBVTT));
    }

    /**
     * Adds the cues a decoder holds back until the end of its track.
     */
    void drain() {
        insert(decoder.flush(SubtitleMarkup.WEBVTT));
    }

    /**
     * Inserts cues in start order, after those that start at the same time.
     *
     * @param decoded the cues
     */
    private void insert(List<SubtitleCue> decoded) {
        for (var cue : decoded) {
            var at = cues.size();
            while (at > 0 && cues.get(at - 1).startMicros() > cue.startMicros()) {
                at--;
            }
            cues.add(at, cue);
        }
    }

    /**
     * Writes the segments of the reference stream's segments that end at or before a time.
     *
     * @param reference the segments of the reference stream
     * @param clock the media time up to which every cue has been added, or {@link Long#MAX_VALUE} once all have
     */
    void flush(List<SegmentedStream.Written> reference, long clock) {
        while (stream.segments.size() < reference.size()) {
            var index = stream.segments.size();
            var segment = reference.get(index);
            var end = index + 1 < reference.size() ? reference.get(index + 1).startMicros()
                    : segment.startMicros() + Math.max(0, segment.durationMicros());
            if (end > clock) {
                return;
            }
            write(index + 1, segment.startMicros(), end, clock == Long.MAX_VALUE && index + 1 == reference.size());
        }
    }

    /**
     * Writes one segment.
     *
     * @param sequence the segment number, from one
     * @param start the start of the segment, in microseconds
     * @param end the end of the segment, in microseconds
     * @param last whether this is the last segment, which also holds the cues that start after it
     */
    private void write(int sequence, long start, long end, boolean last) {
        var text = new StringBuilder(header);
        for (var cue : cues) {
            if (!last && cue.startMicros() >= end) {
                break;
            }
            if (cue.endMicros() > start || cue.startMicros() >= start) {
                text.append(WebVttText.format(cue));
            }
        }
        cues.removeIf(cue -> !last && cue.endMicros() <= end && cue.startMicros() < end || last);
        MediaSink sink = stream.openSegment(sequence, start, end - start, true);
        try {
            sink.write(ByteBuffer.wrap(text.toString().getBytes(StandardCharsets.UTF_8)));
        } finally {
            sink.close();
        }
    }
}
