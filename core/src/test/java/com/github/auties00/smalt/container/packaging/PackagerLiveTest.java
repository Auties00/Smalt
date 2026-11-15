package com.github.auties00.smalt.container.packaging;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.validate.Validators;
import com.github.auties00.smalt.track.TrackKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Packages encodes as events and live presentations through an output that records every version of every file and
 * validates each media playlist, master playlist and DASH manifest the moment it is written, against the segments on
 * disk at that moment: event playlists that grow, live windows that slide (RFC 8216 clause 6.2.2) and delete the
 * segments they dropped once players cannot need them, program dates, and dynamic DASH manifests with their
 * availability and publish times, update period and time-shift buffer; and the target duration of growing playlists,
 * which never changes (RFC 8216 clause 4.3.3.1) and rejects a segment that would exceed it.
 */
@DisplayName("Packager events and live presentations")
class PackagerLiveTest {
    private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");

    /** Records every version and validates it on the spot. */
    private static Presentations.Recording recording(Path directory) {
        return new Presentations.Recording(directory, (name, bytes) -> {
            if (name.endsWith(".m3u8")) {
                Validators.assertValidHls(directory.resolve(name));
            } else if (name.endsWith(".mpd")) {
                Validators.assertValidDash(directory.resolve(name));
            }
        });
    }

    private static PackagingResult pack(Presentations.Recording output, PackagingOptions options, Path... inputs) {
        var sources = new ArrayList<MediaSource>();
        try {
            var renditions = new ArrayList<Rendition>();
            for (var index = 0; index < inputs.length; index++) {
                var source = MediaSource.of(inputs[index]);
                sources.add(source);
                renditions.add(Rendition.of("v" + index, source));
            }
            return Packager.pack(renditions, output, options);
        } finally {
            sources.forEach(MediaSource::close);
        }
    }

    private static int count(String text, String regex) {
        var matcher = Pattern.compile(regex).matcher(text);
        var count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    @Nested
    @DisplayName("events")
    class Events {
        @Test
        @DisplayName("grow every playlist segment by segment with program dates, and end them with ENDLIST")
        void hls() {
            var directory = Presentations.directory("live-event-hls");
            var output = recording(directory);
            pack(output, PackagingOptions.cmaf().toBuilder().segmentMicros(2_000_000)
                    .presentation(PackagingOptions.Presentation.EVENT).programDateTime(START).build(),
                    Presentations.video("320x240", "400k", 30), Presentations.video("160x120", "150k", 30));
            var versions = output.texts("v0_video.m3u8");
            assertEquals(4, versions.size(), "one version per segment, then the complete playlist");
            for (var index = 0; index < versions.size(); index++) {
                var version = versions.get(index);
                assertTrue(version.contains("#EXT-X-PLAYLIST-TYPE:EVENT\n"), version);
                assertTrue(version.contains("#EXT-X-MEDIA-SEQUENCE:0\n"), version);
                assertEquals(index == versions.size() - 1, version.endsWith("#EXT-X-ENDLIST\n"), version);
                assertEquals(Math.min(index + 1, 3), count(version, "#EXTINF:"), version);
            }
            var last = versions.getLast();
            assertTrue(last.contains("#EXT-X-PROGRAM-DATE-TIME:2026-01-01T00:00:00.000Z\n#EXTINF:2.000000,\n"
                    + "v0_video_00001.m4s\n#EXT-X-PROGRAM-DATE-TIME:2026-01-01T00:00:02.000Z\n"), last);
            var masters = output.texts("master.m3u8");
            assertEquals(2, masters.size(), "the master playlist once every stream has a segment, and at the end");
        }

        @Test
        @DisplayName("write a dynamic DASH manifest after every segment, made static in duration at the end")
        void dash() {
            var directory = Presentations.directory("live-event-dash");
            var output = recording(directory);
            pack(output, PackagingOptions.dash().toBuilder().segmentMicros(2_000_000)
                    .presentation(PackagingOptions.Presentation.EVENT).programDateTime(START).build(),
                    Presentations.video("320x240", "400k", 30));
            var versions = output.texts("manifest.mpd");
            assertTrue(versions.size() >= 3, versions.size() + " versions");
            var published = new ArrayList<Instant>();
            for (var index = 0; index < versions.size(); index++) {
                var version = versions.get(index);
                var last = index == versions.size() - 1;
                assertTrue(version.contains("type=\"dynamic\" availabilityStartTime=\"2026-01-01T00:00:00.000Z\""),
                        version);
                assertEquals(!last, version.contains("minimumUpdatePeriod=\"PT2.000S\""), version);
                assertEquals(last, version.contains("mediaPresentationDuration=\"PT6.0"), version);
                assertFalse(version.contains("timeShiftBufferDepth"), "an event keeps every segment");
                assertTrue(version.contains("<UTCTiming schemeIdUri=\"urn:mpeg:dash:utc:direct:2014\""), version);
                var publish = Pattern.compile("publishTime=\"([^\"]+)\"").matcher(version);
                assertTrue(publish.find());
                published.add(Instant.parse(publish.group(1)));
            }
            for (var index = 1; index < published.size(); index++) {
                assertFalse(published.get(index).isBefore(published.get(index - 1)), "publish times never go back");
            }
            assertEquals(START.plusMillis(6_000), published.getLast().truncatedTo(java.time.temporal.ChronoUnit.SECONDS));
        }
    }

    @Nested
    @DisplayName("live windows")
    class Live {
        @Test
        @DisplayName("slide the media sequence over a window of three target durations and delete expired segments")
        void hls() {
            var directory = Presentations.directory("live-window-hls");
            // the number of segments deleted when each version of the media playlist is written
            var deletedBefore = new ArrayList<Integer>();
            var recorded = new Presentations.Recording[1];
            var output = new Presentations.Recording(directory, (name, bytes) -> {
                if (name.endsWith(".m3u8")) {
                    Validators.assertValidHls(directory.resolve(name));
                }
                if (name.equals("v0.m3u8")) {
                    deletedBefore.add(recorded[0].deleted.size());
                }
            });
            recorded[0] = output;
            var result = pack(output, PackagingOptions.hls().toBuilder().segmentMicros(1_000_000)
                    .presentation(PackagingOptions.Presentation.LIVE).windowSegments(3).removeExpiredSegments(true)
                    .build(), Presentations.video("320x240", "400k", 30, 12));
            var versions = output.texts("v0.m3u8");
            assertEquals(13, versions.size());
            for (var index = 0; index < versions.size(); index++) {
                var version = versions.get(index);
                var segments = Math.min(index + 1, 12);
                assertFalse(version.contains("#EXT-X-PLAYLIST-TYPE"), "a live playlist has no type");
                assertTrue(version.contains("#EXT-X-MEDIA-SEQUENCE:" + Math.max(0, segments - 3) + "\n"), version);
                assertEquals(Math.min(segments, 3), count(version, "#EXTINF:"), version);
                assertEquals(index == versions.size() - 1, version.contains("#EXT-X-ENDLIST"), version);
            }
            // a segment stays for its own duration plus the window's after it leaves the window
            assertEquals(List.of("v0_00001.ts", "v0_00002.ts", "v0_00003.ts", "v0_00004.ts", "v0_00005.ts"),
                    output.deleted);
            for (var index = 1; index <= 12; index++) {
                var name = String.format("v0_%05d.ts", index);
                assertEquals(index > 5, Files.isRegularFile(directory.resolve(name)), name);
                assertEquals(index > 5, result.files().contains(name), name);
            }
            // segment one leaves the window with the fourth segment and is deleted after the eighth, four
            // one-second segments later: its duration plus the window's; the playlist of the ninth is the first
            // written after a deletion
            assertTrue(versions.get(3).contains("#EXT-X-MEDIA-SEQUENCE:1\n"), versions.get(3));
            assertEquals(List.of(0, 0, 0, 0, 0, 0, 0, 0, 1, 2, 3, 4, 5), deletedBefore);
        }

        @Test
        @DisplayName("keep the segment whose program tables a live I-frame playlist maps")
        void iFrames() {
            var directory = Presentations.directory("live-window-iframes");
            var output = recording(directory);
            pack(output, PackagingOptions.hls().toBuilder().segmentMicros(1_000_000)
                    .presentation(PackagingOptions.Presentation.LIVE).windowSegments(3).removeExpiredSegments(true)
                    .trickPlay(true).build(), Presentations.video("320x240", "400k", 30, 12));
            var iFrames = output.texts("v0_iframes.m3u8");
            assertEquals(13, iFrames.size());
            for (var version : iFrames) {
                assertTrue(version.contains("#EXT-X-MAP:URI=\"v0_00001.ts\",BYTERANGE="), version);
            }
            assertTrue(Files.isRegularFile(directory.resolve("v0_00001.ts")), "the mapped program tables stay");
            assertEquals(List.of("v0_00002.ts", "v0_00003.ts", "v0_00004.ts", "v0_00005.ts"), output.deleted);
        }

        @Test
        @DisplayName("write dynamic DASH manifests whose timelines and buffer depth follow the window")
        void dash() {
            var directory = Presentations.directory("live-window-dash");
            var output = recording(directory);
            pack(output, PackagingOptions.dash().toBuilder().segmentMicros(1_000_000)
                    .presentation(PackagingOptions.Presentation.LIVE).windowSegments(3).removeExpiredSegments(true)
                    .programDateTime(START).build(), Presentations.video("320x240", "400k", 30, 12));
            var last = output.texts("manifest.mpd").getLast();
            // the audio window grows to four segments to last three target durations, as its last one is short
            assertTrue(last.contains("timeShiftBufferDepth=\"PT4.000S\""), last);
            assertTrue(last.contains("<SegmentTemplate timescale=\"15360\" initialization=\"v0_video_init.mp4\" "
                    + "media=\"v0_video_$Number%05d$.m4s\" startNumber=\"10\">"), last);
            assertTrue(last.contains("<S t=\"138240\" d=\"15360\" r=\"2\"/>"), last);
            assertTrue(Files.isRegularFile(directory.resolve("v0_video_00010.m4s")));
            assertFalse(Files.isRegularFile(directory.resolve("v0_video_00001.m4s")));
        }

        @Test
        @DisplayName("write the playlists of the application's packets as they come, before the end")
        void writer() {
            var directory = Presentations.directory("live-writer");
            var input = Presentations.video("320x240", "400k", 30, 12);
            var video = Presentations.source(input, TrackKind.VIDEO);
            var rendition = Rendition.builder(List.of(video.track())).name("cam").build();
            try (var writer = Packager.open(List.of(rendition), PackageOutput.directory(directory),
                    PackagingOptions.hlsCmaf().toBuilder().segmentMicros(1_000_000)
                            .presentation(PackagingOptions.Presentation.LIVE).build())) {
                for (var index = 0; index < video.packets().size(); index++) {
                    writer.writePacket("cam", video.packets().get(index));
                    if (index == 90) {
                        // three seconds of frames make two complete segments
                        var playlist = Presentations.text(directory.resolve("cam_video.m3u8"));
                        assertTrue(count(playlist, "#EXTINF:") >= 2 && !playlist.contains("#EXT-X-ENDLIST"), playlist);
                        assertTrue(Files.isRegularFile(directory.resolve("master.m3u8")));
                    }
                }
                writer.finish();
            }
            assertTrue(Presentations.text(directory.resolve("cam_video.m3u8")).endsWith("#EXT-X-ENDLIST\n"));
        }
    }

    /**
     * Returns an H.264 encode of 10 seconds at 30 frames per second with key frames at 0 to 3 seconds and from 5
     * seconds on, so that one-second segments are followed by a two-second one.
     */
    private static Path irregular() {
        return Fixtures.ffmpeg("mp4", List.of("-f", "lavfi", "-i", "testsrc2=size=160x120:rate=30:duration=10"),
                List.of("-c:v", "libx264", "-b:v", "150k", "-g", "300", "-keyint_min", "300", "-sc_threshold", "0",
                        "-bf", "0", "-force_key_frames", "0,1,2,3,5,6,7,8,9"));
    }

    private static List<Integer> targets(List<String> versions) {
        var targets = new ArrayList<Integer>();
        for (var version : versions) {
            var matcher = Pattern.compile("#EXT-X-TARGETDURATION:(\\d+)\n").matcher(version);
            assertTrue(matcher.find(), version);
            targets.add(Integer.parseInt(matcher.group(1)));
        }
        return targets;
    }

    private static double listed(String playlist) {
        var matcher = Pattern.compile("#EXTINF:([0-9.]+),").matcher(playlist);
        var total = 0.0;
        while (matcher.find()) {
            total += Double.parseDouble(matcher.group(1));
        }
        return total;
    }

    @Nested
    @DisplayName("target durations")
    class TargetDurations {
        @ParameterizedTest(name = "{0}")
        @EnumSource(value = PackagingOptions.Presentation.class, names = {"EVENT", "LIVE"})
        @DisplayName("keep the declared target in every version when a later segment is the longest")
        void declared(PackagingOptions.Presentation presentation) {
            var directory = Presentations.directory("target-declared-" + presentation);
            var output = recording(directory);
            pack(output, PackagingOptions.hls().toBuilder().segmentMicros(1_000_000).presentation(presentation)
                    .windowSegments(2).targetDuration(3).build(), irregular());
            var versions = output.texts("v0.m3u8");
            assertTrue(versions.size() >= 9, versions.size() + " versions");
            assertEquals(List.of(3), targets(versions).stream().distinct().toList());
            var last = versions.getLast();
            assertTrue(last.contains("#EXTINF:2.000000,\nv0_00004.ts\n"), last);
            if (presentation == PackagingOptions.Presentation.LIVE) {
                // two segments are listed until they last the three target durations (nine seconds) of RFC 8216
                // clause 6.2.2, which takes eight or nine of the ten seconds
                var listed = count(last, "#EXTINF:");
                assertTrue(listed == 8 || listed == 9, last);
                assertTrue(listed(last) >= 8.9, last);
                assertTrue(versions.get(2).contains("#EXT-X-MEDIA-SEQUENCE:0\n"), versions.get(2));
            }
        }

        @Test
        @DisplayName("fix an automatic target with the first version and reject the longer segment that follows")
        void automatic() {
            var directory = Presentations.directory("target-automatic");
            var output = recording(directory);
            var failure = assertThrows(MediaException.Unsupported.class, () -> pack(output, PackagingOptions.hls()
                    .toBuilder().segmentMicros(1_000_000).presentation(PackagingOptions.Presentation.EVENT).build(),
                    irregular()));
            assertTrue(failure.getMessage().contains("segment 4 of stream v0"), failure.getMessage());
            var versions = output.texts("v0.m3u8");
            assertEquals(3, versions.size(), "the versions listing the one-second segments");
            assertEquals(List.of(1, 1, 1), targets(versions));
            for (var version : versions) {
                assertFalse(version.contains("v0_00004.ts"), version);
            }
        }

        @Test
        @DisplayName("keep an automatic target that every segment fits, in media and I-frame playlists")
        void automaticRegular() {
            var directory = Presentations.directory("target-automatic-regular");
            var output = recording(directory);
            pack(output, PackagingOptions.hlsCmaf().toBuilder().segmentMicros(2_000_000)
                    .presentation(PackagingOptions.Presentation.LIVE).trickPlay(true).build(),
                    Presentations.video("320x240", "400k", 30, 8));
            for (var name : List.of("v0_video.m3u8", "v0_video_iframes.m3u8")) {
                var targets = targets(output.texts(name));
                assertTrue(targets.size() >= 4, name);
                assertEquals(List.of(2), targets.stream().distinct().toList(), name);
            }
        }

        @Test
        @DisplayName("leave the DASH live window of a presentation without HLS to the segments")
        void dashOnly() {
            var output = recording(Presentations.directory("target-dash-only"));
            pack(output, PackagingOptions.dash().toBuilder().segmentMicros(1_000_000)
                    .presentation(PackagingOptions.Presentation.LIVE).windowSegments(3).targetDuration(5)
                    .programDateTime(START).build(), Presentations.video("320x240", "400k", 30, 12));
            var last = output.texts("manifest.mpd").getLast();
            // as without a declared target: three one-second segments, grown by one for the short last audio one;
            // a five-second target would have kept fifteen seconds
            assertTrue(last.contains("timeShiftBufferDepth=\"PT4.000S\""), last);
        }

        @Test
        @DisplayName("reject a segment longer than the declared target on demand too, and declare it when it fits")
        void onDemand() {
            assertThrows(MediaException.Unsupported.class, () -> pack(recording(Presentations.directory(
                    "target-on-demand-short")), PackagingOptions.hls().toBuilder().segmentMicros(1_000_000)
                    .targetDuration(1).build(), irregular()));
            var output = recording(Presentations.directory("target-on-demand"));
            pack(output, PackagingOptions.hls().toBuilder().segmentMicros(1_000_000).targetDuration(4).trickPlay(true)
                    .build(), irregular());
            assertEquals(List.of(4), targets(output.texts("v0.m3u8")));
            assertEquals(List.of(4), targets(output.texts("v0_iframes.m3u8")));
            var automatic = recording(Presentations.directory("target-on-demand-automatic"));
            pack(automatic, PackagingOptions.hls().toBuilder().segmentMicros(1_000_000).build(), irregular());
            assertEquals(List.of(2), targets(automatic.texts("v0.m3u8")), "the longest segment on demand");
        }
    }

    @Test
    @DisplayName("reject settings a live presentation cannot honor")
    void validation() {
        assertThrows(IllegalArgumentException.class, () -> PackagingOptions.hls().toBuilder().removeExpiredSegments(true)
                .build());
        assertThrows(IllegalArgumentException.class, () -> PackagingOptions.dashOnDemand().toBuilder()
                .presentation(PackagingOptions.Presentation.LIVE).build());
        assertThrows(IllegalArgumentException.class, () -> PackagingOptions.hls().toBuilder().windowSegments(0)
                .build());
        assertThrows(IllegalArgumentException.class, () -> PackagingOptions.hls().toBuilder().byteRanges(true)
                .presentation(PackagingOptions.Presentation.LIVE).removeExpiredSegments(true).build());
        var options = PackagingOptions.cmaf().toBuilder().presentation(PackagingOptions.Presentation.LIVE)
                .windowSegments(4).programDateTime(START).trickPlay(true).build();
        assertEquals(options, options.toBuilder().build());
        assertEquals(START, options.programDateTime().orElseThrow());
        assertFalse(options.toBuilder().noProgramDateTime().build().programDateTime().isPresent());
        assertTrue(options.targetDuration().isEmpty());
        var bounded = options.toBuilder().targetDuration(6).build();
        assertEquals(6, bounded.targetDuration().orElseThrow());
        assertEquals(bounded, bounded.toBuilder().build());
        assertFalse(bounded.equals(options));
        assertTrue(bounded.toString().contains("targetDuration=6"), bounded.toString());
        assertEquals(options, bounded.toBuilder().automaticTargetDuration().build());
        assertThrows(IllegalArgumentException.class, () -> PackagingOptions.hls().toBuilder().targetDuration(0)
                .build());
        assertThrows(IllegalArgumentException.class, () -> PackagingOptions.hls().toBuilder().targetDuration(-2)
                .build());
        // segments of at least 2.5 s round to 3 s, above a target of 2 s; 2.4 s ones round to 2 s
        assertThrows(IllegalArgumentException.class, () -> PackagingOptions.hls().toBuilder()
                .segmentMicros(2_500_000).targetDuration(2).build());
        assertEquals(2, PackagingOptions.hls().toBuilder().segmentMicros(2_499_999).targetDuration(2).build()
                .targetDuration().orElseThrow());
        // without HLS the target declares nothing, so it bounds no segment
        assertEquals(2, PackagingOptions.dash().toBuilder().segmentMicros(2_500_000).targetDuration(2).build()
                .targetDuration().orElseThrow());
        assertThrows(IllegalArgumentException.class, () -> PackagingOptions.dash().toBuilder().targetDuration(0)
                .build());
    }
}
