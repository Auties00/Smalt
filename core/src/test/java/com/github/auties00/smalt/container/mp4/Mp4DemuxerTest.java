package com.github.auties00.smalt.container.mp4;

import com.github.auties00.smalt.container.ContainerProbe;
import com.github.auties00.smalt.testkit.container.CountingSource;
import com.github.auties00.smalt.container.DemuxAssertions;
import com.github.auties00.smalt.image.Orientation;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.DataTrack;
import com.github.auties00.smalt.track.Track;
import com.github.auties00.smalt.track.TrackKind;
import com.github.auties00.smalt.track.VideoTrack;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Differential tests of {@link Mp4Demuxer} against ffprobe on ffmpeg-generated files. Audio
 * timestamps differ from ffprobe's by the edit list media time, which ffprobe subtracts and this
 * demuxer reports as {@link AudioTrack#skipSamples()} (plus the Opus pre-skip, which stays in
 * band); every other timestamp, size, hash and flag must be identical.
 */
@DisplayName("Mp4Demuxer")
class Mp4DemuxerTest {
    static List<Mp4Fixtures.Case> cases() {
        return Mp4Fixtures.demuxCases();
    }

    static ContainerProbe.Demuxed demux(Path file) {
        return ContainerProbe.demux(file, source -> new Mp4Demuxer(source));
    }

    /** The difference between our timestamps and ffprobe's: the audio media time we keep unshifted. */
    static long expectedOffset(Track track) {
        if (!(track instanceof AudioTrack audio)) {
            return 0;
        }
        var timescale = audio.timeBase().denominator();
        var rate = audio.sampleRate() > 0 ? audio.sampleRate() : timescale;
        var offset = Mp4Track.rescale(audio.skipSamples(), rate, timescale);
        if (audio.codec() == Codec.OPUS && audio.codecConfig().isPresent()) {
            var head = audio.codecConfig().orElseThrow();
            offset += Mp4Track.rescale((head[10] & 0xFF) | (head[11] & 0xFF) << 8, 48000, timescale);
        }
        return offset;
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    @DisplayName("matches ffprobe packet by packet")
    void matchesFfprobe(Mp4Fixtures.Case fixture) {
        var file = fixture.file().get();
        var probe = ContainerProbe.probe(file);
        var ours = demux(file);
        DemuxAssertions.assertPacketsMatch(ours, probe, Mp4DemuxerTest::expectedOffset, true);
        DemuxAssertions.assertStreamsMatch(ours.tracks(), probe);
        for (var index = 0; index < ours.tracks().size(); index++) {
            var track = ours.tracks().get(index);
            var stream = probe.streams().get(index);
            assertEquals(stream.timeBaseDenominator(), track.timeBase().denominator() * stream.timeBaseNumerator(),
                    "time base of track " + index);
            assertCodecConfig(track, stream);
        }
    }

    static void assertCodecConfig(Track track, ContainerProbe.ProbeStream stream) {
        var expected = stream.extradata();
        var config = track.codecConfig().orElse(null);
        switch (track.codec()) {
            case H264, HEVC, AV1, AAC, OPUS, MPEG4_PART2 -> assertArrayEquals(expected, config, "codec config of " + track.codec());
            case FLAC -> assertArrayEquals(expected, Arrays.copyOfRange(config, 8, 42), "FLAC STREAMINFO");
            case ALAC -> assertArrayEquals(Arrays.copyOfRange(expected, 12, 36), config, "ALACSpecificConfig");
            case VP9 -> {
                assertTrue(config != null && config.length >= 8, "vpcC record");
                assertEquals(stream.get("profile").replaceAll("\\D", ""), Integer.toString(config[0]), "VP9 profile");
            }
            case DATA -> {
                if (expected.length > 0) {
                    assertArrayEquals(expected, config, "data track config");
                }
            }
            default -> {
            }
        }
    }

    @Nested
    @DisplayName("track description")
    class Description {
        @ParameterizedTest(name = "{0} -> {1}")
        @CsvSource({"90, ROTATE_270", "180, ROTATE_180", "270, ROTATE_90"})
        @DisplayName("maps ffmpeg's counter-clockwise display rotation to the clockwise orientation")
        void rotation(int ffmpegDegrees, Orientation expected) {
            var file = Mp4Fixtures.copy(Mp4Fixtures.h264Aac(false, List.of(), "mp4"), "mp4",
                    List.of("-display_rotation", Integer.toString(ffmpegDegrees)), List.of());
            var video = (VideoTrack) demux(file).tracks().getFirst();
            assertEquals(expected, video.orientation());
            assertEquals(160, video.width());
            assertEquals(120, video.height());
        }

        @Test
        @DisplayName("reports a mirrored orientation for hflip")
        void mirror() {
            var file = Mp4Fixtures.copy(Mp4Fixtures.h264Aac(false, List.of(), "mp4"), "mp4", List.of("-display_hflip"), List.of());
            assertEquals(Orientation.FLIP_HORIZONTAL, ((VideoTrack) demux(file).tracks().getFirst()).orientation());
            var rotated = Mp4Fixtures.copy(Mp4Fixtures.h264Aac(false, List.of(), "mp4"), "mp4",
                    List.of("-display_rotation", "90", "-display_hflip"), List.of());
            assertEquals(Orientation.TRANSVERSE, ((VideoTrack) demux(rotated).tracks().getFirst()).orientation());
        }

        @Test
        @DisplayName("reports the declared frame rate, time base, language and handler")
        void parameters() {
            var tracks = demux(Mp4Fixtures.multiTrack()).tracks();
            assertEquals(4, tracks.size());
            var video = assertInstanceOf(VideoTrack.class, tracks.get(0));
            assertEquals(30, video.frameRate().orElseThrow().toDouble(), 1e-9);
            var first = assertInstanceOf(AudioTrack.class, tracks.get(1));
            var second = assertInstanceOf(AudioTrack.class, tracks.get(2));
            assertEquals(48000, first.sampleRate());
            assertEquals(44100, second.sampleRate());
            assertEquals(1024, first.skipSamples());
            var text = assertInstanceOf(DataTrack.class, tracks.get(3));
            assertEquals("sbtl", text.handler().orElseThrow());
            assertTrue(tracks.stream().allMatch(track -> track.language().isEmpty()));
        }

        @Test
        @DisplayName("converts the audio edit list into skipSamples and keeps video presentation at zero")
        void editList() {
            var file = Mp4Fixtures.copy(Mp4Fixtures.h264Aac(true, List.of(), "mp4"), "mp4", List.of("-ss", "0.7"), List.of());
            var ours = demux(file);
            var video = ours.tracks().get(0);
            var audio = (AudioTrack) ours.tracks().get(1);
            assertTrue(audio.skipSamples() > 1024, "cut point plus priming is skipped");
            var videoPackets = ours.packetsOf(video);
            assertTrue(videoPackets.getFirst().pts() < 0, "frames before the cut have negative timestamps");
            assertTrue(videoPackets.stream().anyMatch(packet -> packet.pts() == 0), "the cut frame is at zero");
            assertEquals(0, ours.packetsOf(audio).getFirst().pts());
        }

        @Test
        @DisplayName("reads iTunes and keyed metadata")
        void metadata() {
            var tags = demux(Mp4Fixtures.copy(Mp4Fixtures.h264Aac(false, List.of(), "mp4"), "mp4", List.of(),
                    List.of("-metadata", "title=Title è", "-metadata", "artist=Someone", "-metadata", "comment=hi"))).metadata();
            assertEquals("Title è", tags.get("title"));
            assertEquals("Someone", tags.get("artist"));
            assertEquals("hi", tags.get("comment"));
            assertEquals("isom", tags.get("major_brand"));
            var keyed = demux(Mp4Fixtures.copy(Mp4Fixtures.h264Aac(false, List.of(), "mp4"), "mp4", List.of(),
                    List.of("-metadata", "title=Keyed", "-metadata", "custom_key=custom value", "-movflags", "use_metadata_tags"))).metadata();
            assertEquals("Keyed", keyed.get("title"));
            assertEquals("custom value", keyed.get("custom_key"));
        }

        @Test
        @DisplayName("reports the presentation duration and fast start")
        void durationAndFastStart() {
            var plain = Mp4Fixtures.h264Aac(true, List.of(), "mp4");
            var faststart = Mp4Fixtures.h264Aac(true, List.of("-movflags", "+faststart"), "mp4");
            try (var source = MediaSource.of(plain); var demuxer = new Mp4Demuxer(source)) {
                assertFalse(demuxer.isFastStart());
                assertEquals(2_000_000, demuxer.durationMicros(), 30_000);
            }
            try (var source = MediaSource.of(faststart); var demuxer = new Mp4Demuxer(source)) {
                assertTrue(demuxer.isFastStart());
            }
        }

        @Test
        @DisplayName("interleaves packets in file order")
        void fileOrder() {
            var ours = demux(Mp4Fixtures.h264Aac(true, List.of(), "mp4"));
            var kinds = ours.order().stream().map(packet -> ours.tracks().stream()
                    .filter(track -> track.id() == packet.trackId()).findFirst().orElseThrow().kind()).toList();
            var switches = 0;
            for (var index = 1; index < kinds.size(); index++) {
                if (kinds.get(index) != kinds.get(index - 1)) {
                    switches++;
                }
            }
            assertTrue(switches > 4, "audio and video chunks alternate");
            assertTrue(kinds.contains(TrackKind.AUDIO) && kinds.contains(TrackKind.VIDEO));
        }
    }

    @Nested
    @DisplayName("read pattern")
    class ReadPattern {
        static List<String> packets(Mp4Demuxer demuxer) {
            var result = new java.util.ArrayList<String>();
            com.github.auties00.smalt.track.Packet packet;
            while ((packet = demuxer.read()) != null) {
                var crc = new java.util.zip.CRC32();
                crc.update(packet.data());
                result.add(packet.trackId() + " " + packet.pts() + " " + packet.dts() + " " + packet.duration() + " "
                        + packet.keyframe() + " " + packet.size() + " " + Long.toHexString(crc.getValue()));
            }
            return result;
        }

        static List<String> describe(List<Track> tracks) {
            return tracks.stream().map(track -> track.toString().replaceAll("\\[B@[0-9a-f]+", "")
                    + track.codecConfig().map(Arrays::toString).orElse("-")).toList();
        }

        /**
         * Opens and demuxes through a counting source, returning the backward reads at open and in
         * total; {@code slack} bounds the bytes read more than once.
         */
        static long[] backwardReads(byte[] data, int chunk, long slack) {
            var source = new CountingSource(data, chunk);
            try (var counted = new Mp4Demuxer(source);
                 var memory = new Mp4Demuxer(MediaSource.of(data))) {
                var open = source.backward;
                assertEquals(describe(memory.tracks()), describe(counted.tracks()));
                assertEquals(packets(memory), packets(counted));
                assertTrue(source.bytes <= data.length + slack, source.toString());
                return new long[]{open, source.backward};
            }
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.github.auties00.smalt.container.mp4.Mp4DemuxerTest#cases")
        @DisplayName("demuxes a source that is not in memory like an in-memory one, reading it forwards")
        void forwards(Mp4Fixtures.Case fixture) throws java.io.IOException {
            var data = java.nio.file.Files.readAllBytes(fixture.file().get());
            // an MPEG audio track has its first frame header read to tell Layer III from Layer II, and
            // the read-ahead window of a fragmented file reads the small moof boxes between its mdat boxes
            var probes = fixture.name().contains("mp3") || fixture.name().contains("mp2") ? 1 : 0;
            var slack = fixture.name().startsWith("fragmented") ? data.length / 20 : 64;
            for (var chunk : new int[]{1000, Integer.MAX_VALUE}) {
                var backward = backwardReads(data, chunk, slack);
                assertTrue(backward[0] <= probes, "backward reads while opening " + fixture + ": " + backward[0]);
                assertTrue(backward[1] <= backward[0] + 1, "backward reads " + backward[1] + " for " + fixture);
            }
        }

        @Test
        @DisplayName("reads a trailing moov forwards and goes back only once, to the media data")
        void trailingMoov() {
            var sizes = new int[40];
            for (var index = 0; index < sizes.length; index++) {
                sizes[index] = index % 3 == 2 ? 150_000 + index * 37 : 3_000 + index;
            }
            var media = new byte[Arrays.stream(sizes).sum()];
            new java.util.SplittableRandom(7).nextBytes(media);
            var data = Mp4Builder.file(media, Mp4Builder.mvhd(1000, sizes.length * 40L),
                    Mp4Builder.trak(1, "vide", 1000, sizes.length * 40L, Mp4Builder.avc1(), Mp4Builder.table("stts", 0, 2, sizes.length, 40),
                            Mp4Builder.table("stsc", 0, 3, 1, sizes.length, 1), Mp4Builder.stsz(0, sizes),
                            Mp4Builder.table("stco", 0, 1, Mp4Builder.DATA_START)));
            var backward = backwardReads(data, Integer.MAX_VALUE, 64);
            assertEquals(0, backward[0], "the moov is read with its header");
            assertEquals(1, backward[1], "large samples crossing the read-ahead window are not read again");
        }
    }
}
