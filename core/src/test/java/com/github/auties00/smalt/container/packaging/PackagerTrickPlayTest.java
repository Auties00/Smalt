package com.github.auties00.smalt.container.packaging;

import com.github.auties00.smalt.container.mp4.Mp4Demuxer;
import com.github.auties00.smalt.container.internal.SegmentSource;
import com.github.auties00.smalt.io.DataReferenceResolver;
import com.github.auties00.smalt.testkit.validate.Validators;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.TrackKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static com.github.auties00.smalt.container.packaging.Presentations.Input;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Packages an encode with a key frame every half second into 2-second segments with trick play: HLS I-frame
 * playlists over transport stream and CMAF segments (one, byte-range and indexed files), and DASH trick-play
 * adaptation sets. The I-frame byte ranges are checked by the HLS validator and read back through the HLS demuxer,
 * which must give exactly the input's key frames at their times; the trick-play representation must hold every key
 * frame and nothing else, each lasting until the next.
 */
@DisplayName("Packager trick play")
class PackagerTrickPlayTest {
    private static Path input() {
        return Presentations.video("320x240", "400k", 15);
    }

    private static PackagingResult pack(Path directory, PackagingOptions preset) {
        return Presentations.pack(directory, preset.toBuilder().segmentMicros(2_000_000).trickPlay(true).build(),
                List.of(new Input(input(), builder -> builder.name("main"))));
    }

    /** Returns the key frames of the input's video. */
    private static Presentations.Stream keyFrames() {
        var video = Presentations.source(input(), TrackKind.VIDEO);
        return new Presentations.Stream(video.track(), video.packets().stream().filter(Packet::keyframe).toList());
    }

    @Nested
    @DisplayName("HLS I-frame playlists")
    class IFrames {
        @Test
        @DisplayName("address every key frame of the transport stream segments, behind the program tables")
        void transportStream() {
            var directory = Presentations.directory("trick-ts");
            var result = pack(directory, PackagingOptions.hls());
            var master = directory.resolve(result.masterPlaylist().orElseThrow());
            Validators.assertValidHls(master);
            var text = Presentations.text(master);
            assertTrue(Pattern.compile("#EXT-X-I-FRAME-STREAM-INF:BANDWIDTH=\\d+,AVERAGE-BANDWIDTH=\\d+,"
                    + "CODECS=\"avc1\\.6400[0-9a-f]{2}\",RESOLUTION=320x240,URI=\"main_iframes\\.m3u8\"").matcher(text)
                    .find(), text);
            assertEquals(List.of("main_iframes.m3u8"), result.streams().stream()
                    .flatMap(stream -> stream.iFramePlaylist().stream()).toList());
            var playlist = Presentations.text(directory.resolve("main_iframes.m3u8"));
            assertTrue(playlist.contains("#EXT-X-VERSION:5\n"), playlist);
            assertTrue(playlist.contains("#EXT-X-I-FRAMES-ONLY\n#EXT-X-MAP:URI=\"main_00001.ts\",BYTERANGE=\"376@0\"\n"),
                    playlist);
            var keyFrames = keyFrames();
            assertEquals(12, keyFrames.packets().size(), "the input has a key frame every half second");
            var durations = Pattern.compile("#EXTINF:([0-9.]+),").matcher(playlist);
            var total = 0.0;
            var entries = 0;
            while (durations.find()) {
                assertEquals(0.5, Double.parseDouble(durations.group(1)), 0.001, "each I-frame lasts until the next");
                total += Double.parseDouble(durations.group(1));
                entries++;
            }
            assertEquals(12, entries, playlist);
            assertEquals(6.0, total, 0.05);
            assertReadBack(directory.resolve("main_iframes.m3u8"), keyFrames, false);
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"segments", "byte-ranges", "indexed"})
        @DisplayName("address the key frame that starts each CMAF segment, from its movie fragment to its end")
        void cmaf(String layout) {
            var directory = Presentations.directory("trick-cmaf-" + layout);
            var preset = switch (layout) {
                case "segments" -> PackagingOptions.hlsCmaf();
                case "byte-ranges" -> PackagingOptions.hlsCmaf().toBuilder().byteRanges(true).build();
                default -> PackagingOptions.cmaf().toBuilder().byteRanges(true).segmentIndex(true).build();
            };
            var result = pack(directory, preset);
            var master = directory.resolve(result.masterPlaylist().orElseThrow());
            Validators.assertValidHls(master);
            var playlist = Presentations.text(directory.resolve("main_video_iframes.m3u8"));
            assertTrue(playlist.contains("#EXT-X-I-FRAMES-ONLY\n#EXT-X-MAP:URI=\"main_video"), playlist);
            var ranges = Pattern.compile("#EXT-X-BYTERANGE:(\\d+)@(\\d+)\n([^\n]+)").matcher(playlist);
            var keyFrames = keyFrames();
            var segmentStarts = new ArrayList<Packet>();
            for (var index = 0; index < keyFrames.packets().size(); index += 4) {
                segmentStarts.add(keyFrames.packets().get(index));
            }
            var count = 0;
            while (ranges.find()) {
                var file = Presentations.read(directory.resolve(ranges.group(3)));
                var offset = Integer.parseInt(ranges.group(2));
                var length = Integer.parseInt(ranges.group(1));
                var type = new String(file, offset + 4, 4, java.nio.charset.StandardCharsets.US_ASCII);
                assertTrue(type.equals("moof") || type.equals("styp"), "the range starts with " + type);
                // the range ends with the key frame, the first sample of the fragment's mdat
                var frame = segmentStarts.get(count).data();
                assertArrayEquals(frame, java.util.Arrays.copyOfRange(file, offset + length - frame.length,
                        offset + length), "range " + count + " ends with its key frame");
                count++;
            }
            assertEquals(3, count, playlist);
            assertReadBack(directory.resolve("main_video_iframes.m3u8"),
                    new Presentations.Stream(keyFrames.track(), segmentStarts), true);
        }

        @Test
        @DisplayName("cannot address the key frames of AES-128 segments")
        void aes128() {
            var encryption = PackagingEncryption.forAes128(new byte[16], "key.bin");
            assertThrows(IllegalArgumentException.class, () -> PackagingOptions.hls().toBuilder().encryption(encryption)
                    .trickPlay(true).build());
        }
    }

    @Nested
    @DisplayName("DASH trick-play adaptation sets")
    class TrickMode {
        @Test
        @DisplayName("hold every key frame, each lasting until the next, and name the video adaptation set")
        void adaptationSet() {
            var directory = Presentations.directory("trick-dash");
            var result = pack(directory, PackagingOptions.cmaf());
            var manifest = directory.resolve(result.manifest().orElseThrow());
            Validators.assertValidDash(manifest);
            var mpd = Presentations.text(manifest);
            assertTrue(mpd.contains("<EssentialProperty schemeIdUri=\"http://dashif.org/guidelines/trickmode\" "
                    + "value=\"0\"/>"), mpd);
            assertTrue(Pattern.compile("<Representation id=\"main_trick\" bandwidth=\"\\d+\" codecs=\"avc1\\.6400"
                    + "[0-9a-f]{2}\" width=\"320\" height=\"240\" maxPlayoutRate=\"15\" codingDependency=\"false\">")
                    .matcher(mpd).find(), mpd);
            assertTrue(result.streams().stream().anyMatch(PackagedStream::trickPlay));
            assertFalse(Files.isRegularFile(directory.resolve("main_trick.m3u8")), "HLS does not list the stream");
            var parts = new ArrayList<SegmentSource.Part>();
            parts.add(SegmentSource.Part.of("main_trick_init.mp4"));
            for (var index = 1; Files.isRegularFile(directory.resolve(String.format("main_trick_%05d.m4s", index)));
                 index++) {
                parts.add(SegmentSource.Part.of(String.format("main_trick_%05d.m4s", index)));
            }
            var keyFrames = keyFrames();
            try (var source = new SegmentSource(parts, DataReferenceResolver.directory(directory),
                    com.github.auties00.smalt.MediaLimits.DEFAULT);
                 var demuxer = new Mp4Demuxer(source)) {
                var read = Presentations.streams(demuxer).getFirst();
                assertTrue(read.packets().stream().allMatch(Packet::keyframe));
                Presentations.assertSamePackets("trick play", keyFrames, read, read.startMicros()
                        - keyFrames.startMicros(), true);
                for (var index = 0; index + 1 < read.packets().size(); index++) {
                    assertEquals(read.track().timeBase().toMicros(read.packets().get(index).duration()), 500_000,
                            read.track().timeBase().toMicros(1) + 1, "key frame " + index + " lasts until the next");
                }
            }
            try (var demuxer = Presentations.dash(manifest)) {
                assertEquals(1, demuxer.tracks().stream().filter(track -> track.kind() == TrackKind.VIDEO).count(),
                        "the DASH demuxer leaves the trick-play adaptation set out");
            }
        }
    }

    /** Reads an I-frame playlist back and compares its video with the expected key frames. */
    private static void assertReadBack(Path playlist, Presentations.Stream expected, boolean sameBytes) {
        try (var demuxer = Presentations.hls(playlist)) {
            var video = Presentations.streams(demuxer).stream().filter(stream -> stream.track().kind() == TrackKind.VIDEO)
                    .findFirst().orElseThrow();
            assertTrue(video.packets().stream().allMatch(Packet::keyframe));
            Presentations.assertSamePackets("I-frames of " + playlist.getFileName(), expected, video,
                    video.startMicros() - expected.startMicros(), sameBytes);
        }
    }
}
