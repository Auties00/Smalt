package com.github.auties00.smalt.container.dash;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.container.ContainerFormat;
import com.github.auties00.smalt.container.Containers;
import com.github.auties00.smalt.container.DemuxerOptions;
import com.github.auties00.smalt.container.internal.SegmentSource;
import com.github.auties00.smalt.container.mp4.Mp4Demuxer;
import com.github.auties00.smalt.io.DataReferenceResolver;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.subtitle.SubtitleDecoder;
import com.github.auties00.smalt.testkit.Scratch;
import com.github.auties00.smalt.testkit.container.PresentationSamples;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.TrackDisposition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reads DASH presentations: the packager's in every addressing (segment template with timeline, segment list with
 * media ranges, segment base, live window), which must give the same packets, and hand-written manifests over its
 * segments for the addressing the packager does not write ({@code $Time$} and width formats, a segment duration
 * instead of a timeline, a repeat to the period's end, nested base URLs, presentation time offsets, several periods,
 * a live manifest addressed by duration up to its publish time, side files), with the trick-play and unknown
 * essential properties left out, and malformed manifests.
 */
@DisplayName("DASH demuxer")
class DashDemuxerTest {
    private static Map<String, byte[]> files(PresentationSamples.Sample sample) {
        return new LinkedHashMap<>(sample.files());
    }

    private static DashDemuxer open(String manifest, Map<String, byte[]> files) {
        DataReferenceResolver resolver = reference -> Optional.ofNullable(files.get(reference.location()))
                .map(MediaSource::of);
        return new DashDemuxer(MediaSource.of(manifest.getBytes(StandardCharsets.UTF_8)),
                DemuxerOptions.builder().dataReferences(resolver).build());
    }

    private static List<Packet> packets(DashDemuxer demuxer) {
        var packets = new ArrayList<Packet>();
        for (var packet = demuxer.read(); packet != null; packet = demuxer.read()) {
            packets.add(packet);
        }
        return packets;
    }

    /** The packets of the video segments of the template presentation, read straight from its files. */
    private static List<Packet> videoPackets(Map<String, byte[]> files) {
        var parts = new ArrayList<SegmentSource.Part>();
        parts.add(SegmentSource.Part.of("clip_video_init.mp4"));
        for (var index = 1; files.containsKey(String.format("clip_video_%05d.m4s", index)); index++) {
            parts.add(SegmentSource.Part.of(String.format("clip_video_%05d.m4s", index)));
        }
        DataReferenceResolver resolver = reference -> Optional.ofNullable(files.get(reference.location()))
                .map(MediaSource::of);
        try (var source = new SegmentSource(parts, resolver, com.github.auties00.smalt.MediaLimits.DEFAULT);
             var demuxer = new Mp4Demuxer(source)) {
            var packets = new ArrayList<Packet>();
            for (var packet = demuxer.read(); packet != null; packet = demuxer.read()) {
                packets.add(packet);
            }
            return packets;
        }
    }

    private static void assertSame(List<Packet> expected, List<Packet> actual, long offset) {
        assertEquals(expected.size(), actual.size());
        for (var index = 0; index < expected.size(); index++) {
            assertArrayEquals(expected.get(index).data(), actual.get(index).data(), "packet " + index);
            assertEquals(expected.get(index).pts() + offset, actual.get(index).pts(), "packet " + index);
        }
    }

    @Nested
    @DisplayName("the packager's manifests")
    class Packaged {
        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"dash-list", "dash-base"})
        @DisplayName("give the packets of the segment template layout in every addressing")
        void addressing(String name) {
            var template = PresentationSamples.of("dash-template", Long.MAX_VALUE);
            var other = PresentationSamples.of(name, Long.MAX_VALUE);
            try (var expected = open(new String(template.manifestBytes(), StandardCharsets.UTF_8), files(template));
                 var actual = open(new String(other.manifestBytes(), StandardCharsets.UTF_8), files(other))) {
                assertEquals(ContainerFormat.DASH, actual.format());
                assertEquals(expected.tracks().stream().map(track -> track.codec()).toList(),
                        actual.tracks().stream().map(track -> track.codec()).toList());
                var left = packets(expected);
                var right = packets(actual);
                assertEquals(left.size(), right.size());
                for (var index = 0; index < left.size(); index++) {
                    assertArrayEquals(left.get(index).data(), right.get(index).data(), "packet " + index);
                    assertEquals(left.get(index).pts(), right.get(index).pts(), "packet " + index);
                }
            }
        }

        @Test
        @DisplayName("read the template layout as its video, audio and stpp text tracks, the trick play left out")
        void template() {
            var sample = PresentationSamples.of("dash-template", Long.MAX_VALUE);
            var manifest = new String(sample.manifestBytes(), StandardCharsets.UTF_8);
            assertTrue(manifest.contains("trickmode"), manifest);
            try (var demuxer = open(manifest, files(sample))) {
                assertEquals(List.of(Codec.H264, Codec.AAC, Codec.TTML), demuxer.tracks().stream()
                        .map(track -> track.codec()).toList());
                assertEquals("eng", demuxer.tracks().get(2).language().orElseThrow());
                var all = packets(demuxer);
                var video = all.stream().filter(packet -> packet.trackId() == 1).toList();
                assertSame(videoPackets(sample.files()), video, 0);
            }
        }

        @Test
        @DisplayName("read the window a live manifest lists")
        void live() {
            var sample = PresentationSamples.of("dash-live", Long.MAX_VALUE);
            var manifest = new String(sample.manifestBytes(), StandardCharsets.UTF_8);
            assertTrue(manifest.contains("type=\"dynamic\""), manifest);
            try (var demuxer = open(manifest, files(sample))) {
                var video = packets(demuxer).stream().filter(packet -> packet.trackId() == 1).toList();
                var all = videoPackets(sample.files());
                // the window of three target durations covers the whole clip, which is shorter
                assertTrue(!video.isEmpty() && video.size() <= all.size(), video.size() + " of " + all.size());
                assertSame(all.subList(all.size() - video.size(), all.size()), video, 0);
            }
        }
    }

    @Nested
    @DisplayName("hand-written manifests")
    class Handwritten {
        /** The template presentation with its video segments renamed by their start times and numbers. */
        private Map<String, byte[]> renamed(PresentationSamples.Sample sample, List<Long> times) {
            var files = files(sample);
            for (var index = 1; files.containsKey(String.format("clip_video_%05d.m4s", index)); index++) {
                var data = files.get(String.format("clip_video_%05d.m4s", index));
                files.put("media/v/t" + times.get(index - 1) + ".m4s", data);
                files.put(String.format("media/v/n%03d.m4s", index), data);
            }
            files.put("media/v/init.mp4", files.get("clip_video_init.mp4"));
            return files;
        }

        /** Returns the start times of the template presentation's video segments, in its timescale. */
        private List<Long> times(PresentationSamples.Sample sample) {
            var manifest = new String(sample.manifestBytes(), StandardCharsets.UTF_8);
            var start = manifest.indexOf("<Representation id=\"clip_video\"");
            var timeline = manifest.substring(start, manifest.indexOf("</SegmentTimeline>", start));
            var times = new ArrayList<Long>();
            var matcher = java.util.regex.Pattern.compile("<S t=\"(\\d+)\" d=\"(\\d+)\"(?: r=\"(\\d+)\")?/>")
                    .matcher(timeline);
            while (matcher.find()) {
                var time = Long.parseLong(matcher.group(1));
                var duration = Long.parseLong(matcher.group(2));
                var repeat = matcher.group(3) == null ? 0 : Integer.parseInt(matcher.group(3));
                for (var index = 0; index <= repeat; index++) {
                    times.add(time);
                    time += duration;
                }
            }
            return times;
        }

        private String mpd(String type, String extra, String period, String set, String representation) {
            return """
                    <?xml version="1.0"?>
                    <MPD xmlns="urn:mpeg:dash:schema:mpd:2011" profiles="urn:mpeg:dash:profile:isoff-live:2011" \
                    minBufferTime="PT2S" %s %s>
                      %s
                        <BaseURL>media/</BaseURL>
                        <AdaptationSet mimeType="video/mp4" contentType="video" %s
                          <Representation id="v" bandwidth="100000" codecs="avc1.42c00d">
                            %s
                          </Representation>
                        </AdaptationSet>
                      </Period>
                    </MPD>
                    """.formatted(type, extra, period, set, representation);
        }

        @Test
        @DisplayName("address segments by $Time$, by a $Number$ width and by a repeat to the period's end")
        void templates() {
            var sample = PresentationSamples.of("dash-template", Long.MAX_VALUE);
            var times = times(sample);
            var files = renamed(sample, times);
            var expected = videoPackets(sample.files());
            var byTime = mpd("type=\"static\"", "mediaPresentationDuration=\"PT2S\"", "<Period id=\"p\">", ">",
                    "<BaseURL>v/</BaseURL><SegmentTemplate timescale=\"15360\" initialization=\"init.mp4\" "
                            + "media=\"t$Time$.m4s\"><SegmentTimeline><S t=\"" + times.getFirst() + "\" d=\""
                            + (times.get(1) - times.get(0)) + "\" r=\"-1\"/></SegmentTimeline></SegmentTemplate>");
            try (var demuxer = open(byTime, files)) {
                assertSame(expected, packets(demuxer), 0);
            }
            var byNumber = mpd("type=\"static\"", "mediaPresentationDuration=\"PT2S\"", "<Period id=\"p\">",
                    "><SegmentTemplate timescale=\"15360\" duration=\"" + (times.get(1) - times.get(0))
                            + "\" initialization=\"v/init.mp4\" media=\"v/n$Number%03d$.m4s\"/>", "");
            try (var demuxer = open(byNumber, files)) {
                assertSame(expected, packets(demuxer), 0);
            }
        }

        @Test
        @DisplayName("move each period's timestamps by its start less the presentation time offset")
        void periods() {
            var sample = PresentationSamples.of("dash-template", Long.MAX_VALUE);
            var times = times(sample);
            var files = renamed(sample, times);
            var expected = videoPackets(sample.files());
            var template = "<BaseURL>v/</BaseURL><SegmentTemplate timescale=\"15360\" presentationTimeOffset=\"%d\" "
                    + "initialization=\"init.mp4\" media=\"t$Time$.m4s\"><SegmentTimeline><S t=\"%d\" d=\"%d\"/>"
                    + "</SegmentTimeline></SegmentTemplate>";
            var first = template.formatted(0, times.get(0), times.get(1) - times.get(0));
            var second = template.formatted(times.get(1), times.get(1), times.get(2) - times.get(1));
            var manifest = """
                    <?xml version="1.0"?>
                    <MPD xmlns="urn:mpeg:dash:schema:mpd:2011" profiles="urn:mpeg:dash:profile:isoff-live:2011" \
                    minBufferTime="PT2S" type="static" mediaPresentationDuration="PT20S">
                      <Period id="a" start="PT0S" duration="PT10S"><BaseURL>media/</BaseURL>
                        <AdaptationSet mimeType="video/mp4" lang="fr"><Label>Video</Label>
                          <Role schemeIdUri="urn:mpeg:dash:role:2011" value="main"/>
                          <Representation id="v" bandwidth="1" codecs="avc1">%s</Representation></AdaptationSet>
                      </Period>
                      <Period id="b"><BaseURL>media/</BaseURL>
                        <AdaptationSet mimeType="video/mp4">
                          <Representation id="v" bandwidth="1" codecs="avc1">%s</Representation></AdaptationSet>
                      </Period>
                    </MPD>
                    """.formatted(first, second);
            try (var demuxer = open(manifest, files)) {
                assertEquals(2, demuxer.tracks().size(), "a stream per period");
                assertEquals("fre", demuxer.tracks().getFirst().language().orElseThrow());
                assertEquals("Video", demuxer.trackMetadata(1).get("title"));
                assertTrue(demuxer.trackDisposition(1).contains(TrackDisposition.DEFAULT));
                var all = packets(demuxer);
                var firstPeriod = all.stream().filter(packet -> packet.trackId() == 1).toList();
                var secondPeriod = all.stream().filter(packet -> packet.trackId() == 2).toList();
                var split = firstPeriod.size();
                assertSame(expected.subList(0, split), firstPeriod, 0);
                // the second period starts at ten seconds, and its offset puts its first segment there
                var shift = 10 * 15360 - times.get(1);
                assertSame(expected.subList(split, split + secondPeriod.size()), secondPeriod, shift);
            }
        }

        @Test
        @DisplayName("list the segments of a live manifest addressed by duration up to its publish time")
        void liveByDuration() {
            var sample = PresentationSamples.of("dash-template", Long.MAX_VALUE);
            var times = times(sample);
            var files = renamed(sample, times);
            var duration = times.get(1) - times.get(0);
            // one segment and a half has elapsed: one segment is available
            var publish = java.time.Instant.parse("2026-01-01T00:00:00Z").plusNanos(duration * 1_500_000_000L / 15360);
            var manifest = mpd("type=\"dynamic\"", "availabilityStartTime=\"2026-01-01T00:00:00Z\" publishTime=\""
                            + publish + "\"", "<Period id=\"p\" start=\"PT0S\">",
                    "><SegmentTemplate timescale=\"15360\" duration=\"" + duration + "\" initialization=\"v/init.mp4\" "
                            + "media=\"v/n$Number%03d$.m4s\"/>", "");
            try (var demuxer = open(manifest, files)) {
                var video = packets(demuxer);
                var expected = videoPackets(sample.files());
                assertTrue(video.size() < expected.size() && !video.isEmpty());
                assertSame(expected.subList(0, video.size()), video, 0);
            }
        }

        @Test
        @DisplayName("read a side subtitle file, and leave out adaptation sets with essential properties")
        void sideFile() {
            var files = new LinkedHashMap<String, byte[]>();
            files.put("subs/en.vtt", "WEBVTT\n\n00:00:01.000 --> 00:00:02.000\nhello\n".getBytes(StandardCharsets.UTF_8));
            var manifest = """
                    <?xml version="1.0"?>
                    <MPD xmlns="urn:mpeg:dash:schema:mpd:2011" profiles="urn:mpeg:dash:profile:isoff-on-demand:2011" \
                    minBufferTime="PT2S" type="static" mediaPresentationDuration="PT5S">
                      <Period start="PT3S">
                        <AdaptationSet mimeType="text/vtt" lang="en">
                          <Role schemeIdUri="urn:mpeg:dash:role:2011" value="caption"/>
                          <Representation id="s" bandwidth="100"><BaseURL>subs/en.vtt</BaseURL></Representation>
                        </AdaptationSet>
                        <AdaptationSet mimeType="video/mp4">
                          <EssentialProperty schemeIdUri="urn:example:unknown"/>
                          <Representation id="x" bandwidth="1"><BaseURL>missing.mp4</BaseURL></Representation>
                        </AdaptationSet>
                      </Period>
                    </MPD>
                    """;
            try (var demuxer = open(manifest, files)) {
                assertEquals(1, demuxer.tracks().size());
                var track = demuxer.tracks().getFirst();
                assertEquals(Codec.WEBVTT, track.codec());
                assertTrue(demuxer.trackDisposition(track.id()).contains(TrackDisposition.HEARING_IMPAIRED));
                var cue = new SubtitleDecoder(track).decode(packets(demuxer).getFirst());
                assertEquals(4_000_000, cue.startMicros(), "the period start moves the cue");
                assertEquals(5_000_000, demuxer.durationMicros());
            }
        }

        @Test
        @DisplayName("reject malformed manifests and unsupported addressing")
        void malformed() {
            var none = Map.<String, byte[]>of();
            assertThrows(MediaException.Malformed.class, () -> open("<?xml version=\"1.0\"?><tt/>", none));
            assertThrows(MediaException.Malformed.class, () -> open("<MPD><Period><AdaptationSet><Representation "
                    + "id=\"a\"><SegmentTemplate media=\"x\"><SegmentTimeline><S t=\"0\"/></SegmentTimeline>"
                    + "</SegmentTemplate></Representation></AdaptationSet></Period></MPD>", none));
            assertThrows(MediaException.Malformed.class, () -> open("<MPD><Period><AdaptationSet><Representation "
                    + "id=\"a\"><SegmentTemplate timescale=\"0\" media=\"x\" duration=\"1\"/></Representation>"
                    + "</AdaptationSet></Period></MPD>", none));
            assertThrows(MediaException.Malformed.class, () -> open("<MPD mediaPresentationDuration=\"P1Y\"/>", none));
            assertThrows(MediaException.Malformed.class, () -> open("<MPD><Period><AdaptationSet><Representation "
                    + "id=\"a\"><SegmentTemplate duration=\"1\"/></Representation></AdaptationSet></Period></MPD>",
                    none));
            assertThrows(MediaException.Malformed.class, () -> open("<MPD><Period><AdaptationSet><Representation>"
                    + "</Representation></AdaptationSet></Period></MPD>", none));
            assertThrows(MediaException.Unsupported.class, () -> open("<MPD mediaPresentationDuration=\"PT1S\">"
                    + "<Period><AdaptationSet><Representation id=\"a\"><SegmentTemplate media=\"$SubNumber$\" "
                    + "duration=\"1\"/></Representation></AdaptationSet></Period></MPD>", none));
            assertThrows(MediaException.Unsupported.class, () -> open("<MPD type=\"dynamic\"><Period>"
                    + "<AdaptationSet><Representation id=\"a\"><SegmentTemplate media=\"x\" duration=\"1\"/>"
                    + "</Representation></AdaptationSet></Period></MPD>", none));
        }

        @Test
        @DisplayName("is recognized by its MPD root element")
        void recognition() {
            for (var text : List.of("<?xml version=\"1.0\"?>\n<MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\">",
                    "<!-- a comment --><MPD>", "<mpd:MPD xmlns:mpd=\"urn:mpeg:dash:schema:mpd:2011\">")) {
                assertEquals(ContainerFormat.DASH, Containers.detect(MediaSource.of(text.getBytes(StandardCharsets.UTF_8)))
                        .orElseThrow(), text);
            }
            assertTrue(Containers.detect(MediaSource.of("<MPDX/>".getBytes(StandardCharsets.UTF_8))).isEmpty());
            assertEquals(30_500_000L, DashManifest.duration("PT30.5S"));
            assertEquals(90_061_000_000L, DashManifest.duration("P1DT1H1M1S"));
        }
    }
}
