package com.github.auties00.smalt.container.hls;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.container.DemuxerOptions;
import com.github.auties00.smalt.container.ts.TsDemuxer;
import com.github.auties00.smalt.io.DataReferenceResolver;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Scratch;
import com.github.auties00.smalt.testkit.container.HlsStitch;
import com.github.auties00.smalt.testkit.container.PresentationSamples;
import com.github.auties00.smalt.testkit.validate.Validators;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.Track;
import com.github.auties00.smalt.track.TrackKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reads HLS media playlists whose segments change encoding or timeline (RFC 8216 clauses 4.3.2.3, 4.3.2.5 and
 * 6.3.3): presentations the packager writes for two clips, joined behind an {@code EXT-X-DISCONTINUITY}, must give
 * the packets of each clip read alone, the second moved to where the playlist puts it (after the first clip's
 * {@code EXTINF} durations, at its {@code EXT-X-PROGRAM-DATE-TIME}, or where the first playlist of a master puts its
 * discontinuity sequence number), behind the first clip's track when kind and codec match (with the new codec
 * configuration on its first packet) and behind a new track otherwise.
 */
@DisplayName("HLS discontinuities")
class HlsDiscontinuityTest {
    private static DemuxerOptions options(Map<String, byte[]> files) {
        DataReferenceResolver resolver = reference -> Optional.ofNullable(files.get(reference.location()))
                .map(MediaSource::of);
        return DemuxerOptions.builder().dataReferences(resolver).build();
    }

    /** A demuxer read to the end, with the tracks it listed. */
    private record Read(List<Track> tracks, List<Packet> packets) {
        Track track(int id) {
            return tracks.stream().filter(track -> track.id() == id).findFirst().orElseThrow();
        }

        List<Packet> of(int id) {
            return packets.stream().filter(packet -> packet.trackId() == id).toList();
        }

        /** {@return the earliest presentation time of every packet, in microseconds} */
        long startMicros() {
            var start = Long.MAX_VALUE;
            for (var packet : packets) {
                start = Math.min(start, track(packet.trackId()).timeBase().toMicros(packet.pts()));
            }
            return start;
        }
    }

    private static Read read(String playlist, Map<String, byte[]> files) {
        try (var demuxer = new HlsDemuxer(MediaSource.of(utf8(playlist)), options(files))) {
            var packets = new ArrayList<Packet>();
            for (var packet = demuxer.read(); packet != null; packet = demuxer.read()) {
                packets.add(packet);
            }
            return new Read(demuxer.tracks(), packets);
        }
    }

    private static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static String text(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /** Returns the media playlist of one side of the first discontinuity of a joined playlist. */
    private static String side(String joined, boolean second) {
        var halves = joined.split("#EXT-X-DISCONTINUITY\n", 2);
        assertEquals(2, halves.length, "the playlist has a discontinuity");
        var lines = HlsStitch.body(halves[second ? 1 : 0]);
        var text = new StringBuilder("#EXTM3U\n#EXT-X-VERSION:7\n#EXT-X-TARGETDURATION:2\n");
        for (var line : lines) {
            text.append(line).append('\n');
        }
        return text.append("#EXT-X-ENDLIST\n").toString();
    }

    /** Asserts that two packets have the same payload, flags and side data apart from a codec change. */
    private static void assertSamePayload(Packet expected, Packet actual, String what) {
        assertArrayEquals(expected.data(), actual.data(), what);
        assertEquals(expected.flags(), actual.flags(), what);
    }

    /** Asserts that packets are a section's packets moved by one offset, in their own time base or rescaled. */
    private static void assertMoved(List<Packet> alone, Rational from, List<Packet> joined, Rational to,
                                    long offsetMicros, String what) {
        assertEquals(alone.size(), joined.size(), what + ": packet count");
        var offset = Rational.MICROSECONDS.rescale(offsetMicros, to);
        for (var index = 0; index < alone.size(); index++) {
            var expected = alone.get(index);
            var actual = joined.get(index);
            assertSamePayload(expected, actual, what + " packet " + index);
            assertEquals(from.rescale(expected.pts(), to) + offset, actual.pts(), what + " packet " + index + " pts");
            assertEquals(from.rescale(expected.dts(), to) + offset, actual.dts(), what + " packet " + index + " dts");
        }
    }

    @Nested
    @DisplayName("playlists")
    class Playlists {
        @Test
        @DisplayName("number discontinuities from EXT-X-DISCONTINUITY-SEQUENCE and place segments on the timeline, gaps included")
        void numbering() {
            var playlist = HlsPlaylist.media(HlsPlaylist.lines("""
                    #EXTM3U
                    #EXT-X-TARGETDURATION:4
                    #EXT-X-MEDIA-SEQUENCE:10
                    #EXT-X-DISCONTINUITY-SEQUENCE:7
                    #EXTINF:2.5,
                    a.ts
                    #EXT-X-DISCONTINUITY
                    #EXT-X-GAP
                    #EXTINF:1.25,
                    gap.ts
                    #EXTINF:3,
                    b.ts
                    #EXT-X-DISCONTINUITY
                    #EXTINF:2,
                    c.ts
                    """), "");
            var segments = playlist.segments();
            assertEquals(3, segments.size());
            assertEquals(List.of(7L, 8L, 9L), segments.stream().map(HlsPlaylist.Segment::discontinuity).toList());
            assertEquals(List.of(0L, 3_750_000L, 6_750_000L), segments.stream()
                    .map(HlsPlaylist.Segment::positionMicros).toList());
            assertEquals(List.of(10L, 12L, 13L), segments.stream().map(HlsPlaylist.Segment::sequence).toList());
            assertTrue(segments.stream().allMatch(segment -> segment.dateMicros() == HlsPlaylist.Segment.NO_DATE));
        }

        @Test
        @DisplayName("date segments by EXT-X-PROGRAM-DATE-TIME, then by the durations since, in both offset forms")
        void dates() {
            var playlist = HlsPlaylist.media(HlsPlaylist.lines("""
                    #EXTM3U
                    #EXT-X-TARGETDURATION:4
                    #EXTINF:2,
                    undated.ts
                    #EXT-X-PROGRAM-DATE-TIME:2026-01-01T00:00:00.250Z
                    #EXTINF:2,
                    a.ts
                    #EXTINF:2.5,
                    b.ts
                    #EXT-X-DISCONTINUITY
                    #EXT-X-PROGRAM-DATE-TIME:2026-01-01T02:00:00.000+0100
                    #EXTINF:2,
                    c.ts
                    """), "");
            var micros = Instant.parse("2026-01-01T00:00:00.250Z").toEpochMilli() * 1000;
            assertEquals(List.of(HlsPlaylist.Segment.NO_DATE, micros, micros + 2_000_000, micros + 3_600_000_000L
                            - 250_000), playlist.segments().stream().map(HlsPlaylist.Segment::dateMicros).toList());
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"#EXT-X-DISCONTINUITY-SEQUENCE:-1", "#EXT-X-DISCONTINUITY-SEQUENCE:x",
                "#EXT-X-DISCONTINUITY-SEQUENCE:9223372036854775807\n#EXT-X-DISCONTINUITY"})
        @DisplayName("reject a malformed or overflowing discontinuity sequence number")
        void malformed(String tag) {
            var lines = HlsPlaylist.lines("#EXTM3U\n" + tag + "\n#EXTINF:1,\na.ts\n");
            assertThrows(MediaException.Malformed.class, () -> HlsPlaylist.media(lines, ""));
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"yesterday", "2026-01-01T00:00:00", "2026-13-01T00:00:00Z"})
        @DisplayName("leave a segment undated after a date in another form, until the next valid date")
        void undatedForm(String date) {
            var playlist = HlsPlaylist.media(HlsPlaylist.lines("#EXTM3U\n#EXT-X-PROGRAM-DATE-TIME:2026-01-01T00:00:00Z"
                    + "\n#EXTINF:1,\na.ts\n#EXT-X-PROGRAM-DATE-TIME:" + date + "\n#EXTINF:1,\nb.ts\n"
                    + "#EXT-X-PROGRAM-DATE-TIME:2026-01-01T00:00:05Z\n#EXTINF:1,\nc.ts\n"), "");
            var micros = Instant.parse("2026-01-01T00:00:00Z").toEpochMilli() * 1000;
            assertEquals(List.of(micros, HlsPlaylist.Segment.NO_DATE, micros + 5_000_000), playlist.segments()
                    .stream().map(HlsPlaylist.Segment::dateMicros).toList());
        }
    }

    @Nested
    @DisplayName("transport stream segments")
    class TransportStream {
        private final PresentationSamples.Sample sample = PresentationSamples.of("hls-discontinuity", Long.MAX_VALUE);
        private final String joined = text(sample.manifestBytes());

        @Test
        @DisplayName("continue the tracks of matching kind and codec and add a track for a new codec")
        void codecChange() {
            var first = read(side(joined, false), sample.files());
            var second = read(side(joined, true), sample.files());
            var actual = read(joined, sample.files());
            var codecs = new ArrayList<>(first.tracks().stream().map(Track::codec).toList());
            codecs.add(Codec.HEVC);
            assertEquals(codecs, actual.tracks().stream().map(Track::codec).toList());
            assertEquals(List.of(1, 2, 3), actual.tracks().stream().map(Track::id).toList());
            var offset = HlsStitch.durationMicros(side(joined, false)) + first.startMicros() - second.startMicros();
            for (var id = 1; id <= 2; id++) {
                assertMoved(first.of(id), first.track(id).timeBase(), actual.of(id), actual.track(id).timeBase(), 0,
                        "track " + id);
            }
            assertMoved(second.of(1), second.track(1).timeBase(), actual.of(3), actual.track(3).timeBase(), offset,
                    "HEVC");
            assertTrue(actual.packets().stream().noneMatch(packet -> packet.sideData().newCodecConfig().isPresent()));
            var video = first.tracks().stream().filter(track -> track.kind() == TrackKind.VIDEO).findFirst()
                    .orElseThrow().id();
            var lastFirst = actual.of(video).getLast();
            var firstSecond = actual.of(3).getFirst();
            assertTrue(actual.track(3).timeBase().toMicros(firstSecond.dts())
                    >= actual.track(video).timeBase().toMicros(lastFirst.dts()), "the second section follows the first");
        }

        @Test
        @DisplayName("are valid presentations")
        void valid() {
            var manifest = PresentationSamples.write(sample, Scratch.directory("hls-discontinuity").resolve("ts"));
            Validators.assertValidHls(manifest);
        }

        @Test
        @DisplayName("place a section at its EXT-X-PROGRAM-DATE-TIME, measured from the first section's")
        void programDateTime() {
            var dated = joined.replace("#EXT-X-PLAYLIST-TYPE:VOD\n",
                            "#EXT-X-PLAYLIST-TYPE:VOD\n#EXT-X-PROGRAM-DATE-TIME:2026-01-01T00:00:00Z\n")
                    .replace("#EXT-X-DISCONTINUITY\n", "#EXT-X-DISCONTINUITY\n#EXT-X-PROGRAM-DATE-TIME:"
                            + "2026-01-01T01:01:40+0100\n");
            var first = read(side(joined, false), sample.files());
            var second = read(side(joined, true), sample.files());
            var actual = read(dated, sample.files());
            assertMoved(second.of(1), second.track(1).timeBase(), actual.of(3), actual.track(3).timeBase(),
                    100_000_000 + first.startMicros() - second.startMicros(), "HEVC");
        }

        @Test
        @DisplayName("align a discontinuity sequence number across the playlists of a master playlist")
        void alignedRenditions() {
            var files = new LinkedHashMap<>(sample.files());
            files.put("x.m3u8", utf8(joined.replace("#EXT-X-MEDIA-SEQUENCE:0\n",
                    "#EXT-X-MEDIA-SEQUENCE:0\n#EXT-X-DISCONTINUITY-SEQUENCE:7\n")));
            // the second playlist's first section declares longer segments, so only the shared number aligns them
            var stretched = new StringBuilder();
            var before = true;
            for (var line : joined.split("\n")) {
                if (line.equals("#EXT-X-DISCONTINUITY")) {
                    before = false;
                }
                if (before && line.startsWith("#EXTINF:")) {
                    var value = Double.parseDouble(line.substring(8, line.indexOf(',')));
                    line = "#EXTINF:" + String.format(java.util.Locale.ROOT, "%.6f", value + 0.25) + ",";
                } else if (line.startsWith("#EXT-X-MEDIA-SEQUENCE:")) {
                    line = line + "\n#EXT-X-DISCONTINUITY-SEQUENCE:7";
                }
                stretched.append(line).append('\n');
            }
            files.put("y.m3u8", utf8(stretched.toString()));
            var master = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1000\nx.m3u8\n#EXT-X-STREAM-INF:BANDWIDTH=2000\ny.m3u8\n";
            var actual = read(master, files);
            assertEquals(6, actual.tracks().size());
            assertEquals(actual.of(3).getFirst().pts(), actual.of(6).getFirst().pts(),
                    "both playlists start discontinuity 8 at once");
            var alone = read(stretched.toString(), files);
            assertTrue(alone.of(3).getFirst().pts() > actual.of(6).getFirst().pts(),
                    "the second playlist alone places it later");
        }

        @Test
        @DisplayName("time WebVTT cues after a discontinuity by the media section of the same number")
        void webVtt() {
            var files = new LinkedHashMap<>(sample.files());
            var secondSegment = HlsStitch.body(side(joined, true)).stream().filter(line -> !line.startsWith("#"))
                    .findFirst().orElseThrow();
            long originB;
            try (var source = MediaSource.of(files.get(secondSegment)); var demuxer = new TsDemuxer(source)) {
                originB = demuxer.originTicks();
            }
            var firstSegment = HlsStitch.body(side(joined, false)).stream().filter(line -> !line.startsWith("#"))
                    .findFirst().orElseThrow();
            long originA;
            try (var source = MediaSource.of(files.get(firstSegment)); var demuxer = new TsDemuxer(source)) {
                originA = demuxer.originTicks();
            }
            files.put("s1.vtt", utf8("WEBVTT\nX-TIMESTAMP-MAP=MPEGTS:" + originA
                    + ",LOCAL:00:00.000\n\n00:00.500 --> 00:01.000\none\n"));
            files.put("s2.vtt", utf8("WEBVTT\nX-TIMESTAMP-MAP=MPEGTS:" + (originB + 90_000)
                    + ",LOCAL:00:00.000\n\n00:00.500 --> 00:01.000\ntwo\n"));
            var firstDuration = HlsStitch.durationMicros(side(joined, false));
            files.put("subs.m3u8", utf8("#EXTM3U\n#EXT-X-TARGETDURATION:4\n#EXTINF:"
                    + firstDuration / 1e6 + ",\ns1.vtt\n#EXT-X-DISCONTINUITY\n#EXTINF:2,\ns2.vtt\n#EXT-X-ENDLIST\n"));
            files.put("v.m3u8", utf8(joined));
            var master = "#EXTM3U\n#EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID=\"s\",NAME=\"en\",URI=\"subs.m3u8\"\n"
                    + "#EXT-X-STREAM-INF:BANDWIDTH=1000,SUBTITLES=\"s\"\nv.m3u8\n";
            var actual = read(master, files);
            var subtitles = actual.tracks().stream().filter(track -> track.codec() == Codec.WEBVTT).findFirst()
                    .orElseThrow();
            var cues = actual.of(subtitles.id());
            assertEquals(2, cues.size());
            var first = read(side(joined, false), sample.files());
            var second = read(side(joined, true), sample.files());
            var expected = firstDuration + first.startMicros() - second.startMicros() + 1_500_000;
            var time = subtitles.timeBase().toMicros(cues.get(1).pts());
            assertTrue(Math.abs(time - expected) <= 1000, "the second cue at " + time + ", not " + expected);
            assertEquals(500_000, subtitles.timeBase().toMicros(cues.get(0).pts()), 1000);
        }

        @Test
        @DisplayName("seek into either section, the new codec's track starting at a key frame")
        void seek() {
            var full = read(joined, sample.files());
            var secondStart = full.track(3).timeBase().toMicros(full.of(3).getFirst().pts());
            try (var demuxer = new HlsDemuxer(MediaSource.of(sample.manifestBytes()), options(sample.files()))) {
                assertTrue(demuxer.isSeekable());
                demuxer.seek(secondStart + 600_000);
                var packet = demuxer.read();
                assertEquals(3, packet.trackId());
                assertTrue(packet.keyframe());
                assertTrue(full.track(3).timeBase().toMicros(packet.pts()) <= secondStart + 600_000);
                demuxer.seek(0);
                var again = new ArrayList<Packet>();
                for (var next = demuxer.read(); next != null; next = demuxer.read()) {
                    again.add(next);
                }
                assertEquals(full.packets().size(), again.size());
                for (var index = 0; index < again.size(); index++) {
                    assertArrayEquals(full.packets().get(index).data(), again.get(index).data(), "packet " + index);
                    assertEquals(full.packets().get(index).pts(), again.get(index).pts(), "packet " + index);
                }
            }
        }

        @Test
        @DisplayName("seek back after reading the first packets of the second section, which are read again")
        void seekBackFromSectionStart() {
            var full = read(joined, sample.files());
            var firstOfSecond = full.packets().indexOf(full.of(3).getFirst());
            try (var demuxer = new HlsDemuxer(MediaSource.of(sample.manifestBytes()), options(sample.files()))) {
                // a few packets into the second section, inside the packets read ahead to measure its start
                for (var index = 0; index < firstOfSecond + 3; index++) {
                    assertArrayEquals(full.packets().get(index).data(), demuxer.read().data(), "packet " + index);
                }
                demuxer.seek(0);
                var again = new ArrayList<Packet>();
                for (var next = demuxer.read(); next != null; next = demuxer.read()) {
                    again.add(next);
                }
                assertEquals(full.packets().size(), again.size());
                for (var index = 0; index < again.size(); index++) {
                    assertArrayEquals(full.packets().get(index).data(), again.get(index).data(), "packet " + index);
                    assertEquals(full.packets().get(index).pts(), again.get(index).pts(), "packet " + index);
                }
            }
        }

        @Test
        @DisplayName("reject WebVTT segments after a discontinuity in a playlist of media segments")
        void mixedWebVtt() {
            var files = new LinkedHashMap<>(sample.files());
            files.put("late.vtt", utf8("WEBVTT\n\n00:00:00.000 --> 00:00:01.000\nlate\n"));
            var playlist = side(joined, false).replace("#EXT-X-ENDLIST\n",
                    "#EXT-X-DISCONTINUITY\n#EXTINF:1,\nlate.vtt\n#EXT-X-ENDLIST\n");
            assertThrows(MediaException.Malformed.class, () -> read(playlist, files));
        }

        @Test
        @DisplayName("read hundreds of sections, each a new timeline, one after the other")
        void manySections() {
            var first = HlsStitch.body(side(joined, false));
            var segment = first.stream().filter(line -> !line.startsWith("#")).findFirst().orElseThrow();
            var extinf = first.get(first.indexOf(segment) - 1);
            var text = new StringBuilder("#EXTM3U\n#EXT-X-TARGETDURATION:2\n");
            for (var index = 0; index < 300; index++) {
                if (index > 0) {
                    text.append("#EXT-X-DISCONTINUITY\n");
                }
                text.append(extinf).append('\n').append(segment).append('\n');
            }
            var actual = read(text.append("#EXT-X-ENDLIST\n").toString(), sample.files());
            var alone = read("#EXTM3U\n#EXT-X-TARGETDURATION:2\n" + extinf + "\n" + segment + "\n#EXT-X-ENDLIST\n",
                    sample.files());
            assertEquals(alone.tracks().size(), actual.tracks().size());
            assertEquals(alone.of(1).size() * 300, actual.of(1).size());
            for (var track : actual.tracks()) {
                var previous = Long.MIN_VALUE;
                for (var packet : actual.of(track.id())) {
                    assertTrue(packet.dts() > previous, "track " + track.id() + " decoding times increase");
                    previous = packet.dts();
                }
            }
            var step = HlsStitch.durationMicros(extinf + "\n");
            var video = actual.of(1);
            var count = alone.of(1).size();
            for (var index = 0; index < video.size(); index++) {
                var expected = alone.of(1).get(index % count);
                assertEquals(expected.pts() + Rational.MICROSECONDS.rescale(step * (index / count),
                        actual.track(1).timeBase()), video.get(index).pts(), "video packet " + index);
            }
        }

        @Test
        @DisplayName("read the key frames of an I-frame playlist across a discontinuity")
        void iFrames() {
            var iframes = PresentationSamples.of("hls-iframes", Long.MAX_VALUE);
            var playlist = text(iframes.manifestBytes());
            var joined = HlsStitch.join(List.of(playlist, playlist), true, List.of())
                    .replace("#EXTM3U\n", "#EXTM3U\n#EXT-X-I-FRAMES-ONLY\n");
            var alone = read(playlist, iframes.others());
            var actual = read(joined, iframes.others());
            assertEquals(alone.tracks().stream().map(Track::codec).toList(),
                    actual.tracks().stream().map(Track::codec).toList());
            var video = alone.tracks().stream().filter(track -> track.kind() == TrackKind.VIDEO).findFirst()
                    .orElseThrow().id();
            var keys = alone.of(video);
            var all = actual.of(video);
            assertFalse(keys.isEmpty());
            assertEquals(keys.size() * 2, all.size());
            var timeBase = alone.track(video).timeBase();
            assertMoved(keys, timeBase, all.subList(0, keys.size()), actual.track(video).timeBase(), 0, "first");
            assertMoved(keys, timeBase, all.subList(keys.size(), all.size()), actual.track(video).timeBase(),
                    HlsStitch.durationMicros(playlist), "second");
            assertTrue(all.stream().allMatch(Packet::keyframe));
        }

        @Test
        @DisplayName("fail cleanly when a later section is missing or corrupt")
        void broken() {
            var secondSegment = HlsStitch.body(side(joined, true)).stream().filter(line -> !line.startsWith("#"))
                    .findFirst().orElseThrow();
            var missing = new LinkedHashMap<>(sample.files());
            missing.remove(secondSegment);
            assertThrows(MediaException.Unsupported.class, () -> read(joined, missing));
            var corrupt = new LinkedHashMap<>(sample.files());
            var bytes = corrupt.get(secondSegment).clone();
            Arrays.fill(bytes, (byte) 0x11);
            corrupt.put(secondSegment, bytes);
            assertThrows(MediaException.class, () -> read(joined, corrupt));
        }
    }

    @Nested
    @DisplayName("CMAF segments")
    class Cmaf {
        private final PresentationSamples.Sample sample = PresentationSamples.of("hls-cmaf-discontinuity",
                Long.MAX_VALUE);
        private final String joined = text(sample.manifestBytes());

        @Test
        @DisplayName("continue an H.264 track whose initialization section changes, with the new configuration")
        void configChange() {
            var first = read(side(joined, false), sample.files());
            var second = read(side(joined, true), sample.files());
            var actual = read(joined, sample.files());
            assertEquals(1, actual.tracks().size());
            assertEquals(Codec.H264, actual.tracks().getFirst().codec());
            var count = first.packets().size();
            var head = actual.packets().subList(0, count);
            var tail = actual.packets().subList(count, actual.packets().size());
            assertMoved(first.packets(), first.track(1).timeBase(), head, actual.track(1).timeBase(), 0, "first");
            var offset = HlsStitch.durationMicros(side(joined, false)) + first.startMicros() - second.startMicros();
            assertMoved(second.packets(), second.track(1).timeBase(), tail, actual.track(1).timeBase(), offset,
                    "second");
            var changes = new ArrayList<Integer>();
            for (var index = 0; index < actual.packets().size(); index++) {
                if (actual.packets().get(index).sideData().newCodecConfig().isPresent()) {
                    changes.add(index);
                }
            }
            assertEquals(List.of(count), changes);
            assertArrayEquals(second.track(1).codecConfig().orElseThrow(),
                    tail.getFirst().sideData().newCodecConfig().orElseThrow());
            assertFalse(Arrays.equals(first.track(1).codecConfig().orElseThrow(),
                    second.track(1).codecConfig().orElseThrow()));
        }

        @Test
        @DisplayName("are valid presentations")
        void valid() {
            var manifest = PresentationSamples.write(sample, Scratch.directory("hls-discontinuity").resolve("cmaf"));
            Validators.assertValidHls(manifest);
        }

        @Test
        @DisplayName("give the configuration of the section a seek lands in on its first packet, unless it is the track's")
        void seek() {
            var first = read(side(joined, false), sample.files());
            var second = read(side(joined, true), sample.files());
            var full = read(joined, sample.files());
            var count = first.packets().size();
            var secondStart = full.track(1).timeBase().toMicros(full.packets().get(count).pts());
            try (var demuxer = new HlsDemuxer(MediaSource.of(sample.manifestBytes()), options(sample.files()))) {
                demuxer.seek(secondStart + 500_000);
                var packet = demuxer.read();
                assertTrue(packet.keyframe());
                assertArrayEquals(second.track(1).codecConfig().orElseThrow(),
                        packet.sideData().newCodecConfig().orElseThrow());
                demuxer.seek(0);
                packet = demuxer.read();
                assertTrue(packet.sideData().newCodecConfig().isEmpty(), "the track's own configuration");
                assertArrayEquals(full.packets().getFirst().data(), packet.data());
                var index = 1;
                for (var next = demuxer.read(); next != null; next = demuxer.read(), index++) {
                    assertArrayEquals(full.packets().get(index).data(), next.data(), "packet " + index);
                    assertEquals(full.packets().get(index).pts(), next.pts(), "packet " + index);
                    assertEquals(index == count, next.sideData().newCodecConfig().isPresent(), "packet " + index);
                }
                assertEquals(full.packets().size(), index);
            }
        }

        @Test
        @DisplayName("read a change of initialization section alone as the same timeline")
        void mapChangeAlone() {
            var cmaf = PresentationSamples.of("hls-cmaf", Long.MAX_VALUE);
            var files = new LinkedHashMap<>(cmaf.files());
            var playlist = text(files.get("clip_video.m3u8"));
            files.put("copy_init.mp4", files.get("clip_video_init.mp4"));
            var lines = new ArrayList<>(playlist.lines().toList());
            var segments = lines.stream().filter(line -> line.endsWith(".m4s")).toList();
            assertTrue(segments.size() >= 2);
            lines.add(lines.indexOf(segments.get(segments.size() / 2)) - 1, "#EXT-X-MAP:URI=\"copy_init.mp4\"");
            var changed = String.join("\n", lines) + "\n";
            var expected = read(playlist, files);
            var actual = read(changed, files);
            assertEquals(1, actual.tracks().size());
            var track = (com.github.auties00.smalt.track.VideoTrack) expected.tracks().getFirst();
            assertEquals(track.toBuilder().duration(Track.UNKNOWN_DURATION).build(), actual.tracks().getFirst());
            assertEquals(expected.packets().size(), actual.packets().size());
            for (var index = 0; index < expected.packets().size(); index++) {
                var packet = expected.packets().get(index);
                var other = actual.packets().get(index);
                assertSamePayload(packet, other, "packet " + index);
                assertEquals(packet.pts(), other.pts(), "packet " + index);
                assertEquals(packet.dts(), other.dts(), "packet " + index);
                assertTrue(other.sideData().newCodecConfig().isEmpty(), "packet " + index);
            }
        }

        @Test
        @DisplayName("add a track of a kind that appears only after the discontinuity")
        void lateKind() {
            var cmaf = PresentationSamples.of("hls-cmaf", Long.MAX_VALUE);
            var files = new LinkedHashMap<>(sample.files());
            files.putAll(cmaf.files());
            var audio = text(cmaf.files().get("speech_audio.m3u8"));
            var mixed = HlsStitch.join(List.of(side(joined, false), audio), true, List.of());
            var actual = read(mixed, files);
            assertEquals(List.of(TrackKind.VIDEO, TrackKind.AUDIO), actual.tracks().stream().map(Track::kind).toList());
            assertEquals(read(audio, files).packets().size(), actual.of(2).size());
        }
    }
}
