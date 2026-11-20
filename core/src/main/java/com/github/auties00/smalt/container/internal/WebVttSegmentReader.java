package com.github.auties00.smalt.container.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.container.Demuxer;
import com.github.auties00.smalt.container.DemuxerOptions;
import com.github.auties00.smalt.container.subtitle.WebVttDemuxer;
import com.github.auties00.smalt.internal.io.SourceBytes;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.internal.subtitle.CueTimes;
import com.github.auties00.smalt.internal.subtitle.TextFiles;
import com.github.auties00.smalt.internal.subtitle.WebVttText;
import com.github.auties00.smalt.io.DataReferenceResolver;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.subtitle.SubtitleCue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;

/**
 * Reads the WebVTT segments of an HLS subtitle rendition (RFC 8216 clause 3.5) as one WebVTT track: each
 * segment's cue times are moved by its {@code X-TIMESTAMP-MAP} onto the presentation timeline, and a cue repeated
 * in the segments it spans is kept once.
 */
public final class WebVttSegmentReader {
    /**
     * The 33-bit wrap of MPEG-2 timestamps.
     */
    private static final long WRAP = 1L << 33;

    private WebVttSegmentReader() {
        throw new AssertionError();
    }

    /**
     * Reads the segments.
     *
     * @param segments the segments, in order
     * @param resolver opens the segment files
     * @param options the demuxing options of the presentation
     * @param originTicks the 90 kHz media timestamp of presentation time zero
     * @return a demuxer of one {@link com.github.auties00.smalt.track.Codec#WEBVTT} track, its header the first
     * segment's without the timestamp map
     * @throws MediaException.Malformed if a segment is not a WebVTT file or its timestamp map is invalid
     * @throws MediaException.Unsupported if a segment file cannot be resolved
     * @throws MediaException.LimitExceeded if the cues exceed the limits
     * @throws MediaException.Io if reading fails
     */
    public static Demuxer open(List<SegmentSource.Part> segments, DataReferenceResolver resolver,
                               DemuxerOptions options, long originTicks) {
        var origins = new long[segments.size()];
        Arrays.fill(origins, originTicks);
        return open(segments, resolver, options, origins, new long[segments.size()]);
    }

    /**
     * Reads segments that lie on several timelines, as the segments of an HLS subtitle rendition on either side of a
     * discontinuity do: each segment's timestamp map refers to the media timestamps of its own timeline.
     *
     * @param segments the segments, in order
     * @param resolver opens the segment files
     * @param options the demuxing options of the presentation
     * @param originTicks the 90 kHz media timestamp of each segment's timeline that its {@code shiftMicros} maps to
     * @param shiftMicros the presentation time of each segment's {@code originTicks}, in microseconds
     * @return a demuxer of one {@link com.github.auties00.smalt.track.Codec#WEBVTT} track, its header the first
     * segment's without the timestamp map
     * @throws IllegalArgumentException if the arrays do not hold one value per segment
     * @throws MediaException.Malformed if a segment is not a WebVTT file or its timestamp map is invalid
     * @throws MediaException.Unsupported if a segment file cannot be resolved
     * @throws MediaException.LimitExceeded if the cues exceed the limits
     * @throws MediaException.Io if reading fails
     */
    public static Demuxer open(List<SegmentSource.Part> segments, DataReferenceResolver resolver,
                               DemuxerOptions options, long[] originTicks, long[] shiftMicros) {
        if (originTicks.length != segments.size() || shiftMicros.length != segments.size()) {
            throw new IllegalArgumentException("one origin and one shift per segment");
        }
        String header = null;
        var cues = new ArrayList<SubtitleCue>();
        var seen = new HashSet<List<Object>>();
        var total = 0L;
        for (var index = 0; index < segments.size(); index++) {
            var segment = segments.get(index);
            String text;
            try (var source = new SegmentSource(List.of(segment), resolver, options.limits())) {
                var bytes = SourceBytes.readAll(source, options.limits(), false);
                total += bytes.length;
                LimitChecks.checkAllocation(options.limits(), total);
                text = TextFiles.normalizeLineBreaks(TextFiles.decode(bytes, StandardCharsets.UTF_8));
            }
            if (text.startsWith("﻿")) {
                text = text.substring(1);
            }
            var document = WebVttText.parse(text);
            var offset = offset(document.header(), originTicks[index]) + shiftMicros[index];
            if (header == null) {
                header = withoutMap(document.header());
            }
            for (var cue : document.cues()) {
                var start = Math.max(0, cue.startMicros() + offset);
                var end = Math.max(start, cue.endMicros() + offset);
                var moved = new SubtitleCue(start, end, cue.text(), cue.identifier(), cue.settings());
                if (seen.add(List.of(start, end, cue.text(), cue.identifier(), cue.settings()))) {
                    cues.add(moved);
                }
            }
        }
        cues.sort(Comparator.comparingLong(SubtitleCue::startMicros));
        var merged = new StringBuilder(header == null ? WebVttText.DEFAULT_HEADER : header).append("\n\n");
        for (var cue : cues) {
            merged.append(WebVttText.format(cue));
        }
        return new WebVttDemuxer(MediaSource.of(merged.toString().getBytes(StandardCharsets.UTF_8)), options);
    }

    /**
     * Returns the time a segment's {@code X-TIMESTAMP-MAP} adds to its cue times.
     *
     * @param header the segment header
     * @param originTicks the 90 kHz media timestamp of presentation time zero
     * @return the offset in microseconds; a segment without a map maps cue time zero to MPEG-2 timestamp zero
     * (RFC 8216 clause 3.5)
     * @throws MediaException.Malformed if the map is invalid
     */
    private static long offset(String header, long originTicks) {
        var mpegTs = 0L;
        var local = 0L;
        var found = false;
        for (var line : header.split("\n")) {
            if (!line.startsWith("X-TIMESTAMP-MAP=")) {
                continue;
            }
            found = true;
            for (var field : line.substring("X-TIMESTAMP-MAP=".length()).split(",")) {
                var trimmed = field.strip();
                if (trimmed.startsWith("MPEGTS:")) {
                    try {
                        mpegTs = Long.parseLong(trimmed.substring(7));
                    } catch (NumberFormatException exception) {
                        throw new MediaException.Malformed("an X-TIMESTAMP-MAP with MPEGTS " + trimmed.substring(7));
                    }
                } else if (trimmed.startsWith("LOCAL:")) {
                    var end = new int[1];
                    local = CueTimes.parse(trimmed, 6, end);
                    if (local < 0) {
                        throw new MediaException.Malformed("an X-TIMESTAMP-MAP with LOCAL " + trimmed.substring(6));
                    }
                }
            }
        }
        if (!found) {
            mpegTs = 0;
        }
        if (mpegTs < 0 || mpegTs >= WRAP) {
            throw new MediaException.Malformed("an X-TIMESTAMP-MAP with MPEGTS " + mpegTs + " beyond 33 bits");
        }
        var ticks = Math.floorMod(mpegTs - originTicks, WRAP);
        if (ticks >= WRAP / 2) {
            ticks -= WRAP;
        }
        return ticks * 100 / 9 - local;
    }

    /**
     * Returns a header without its {@code X-TIMESTAMP-MAP} line.
     *
     * @param header the segment header
     * @return the header
     */
    private static String withoutMap(String header) {
        var lines = new ArrayList<String>();
        for (var line : header.split("\n", -1)) {
            if (!line.startsWith("X-TIMESTAMP-MAP=")) {
                lines.add(line);
            }
        }
        return String.join("\n", lines).strip();
    }
}
