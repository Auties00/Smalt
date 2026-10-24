package com.github.auties00.smalt.container.mp4;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.container.ContainerFormat;
import com.github.auties00.smalt.container.ContainerProbe;
import com.github.auties00.smalt.testkit.container.CountingSource;
import com.github.auties00.smalt.container.MemoryChannel;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.Quality;
import com.github.auties00.smalt.track.Codec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests of {@link Mp4Remuxer}: MP4, MOV, fragmented MP4, Matroska and WebM inputs become
 * fast-start MP4 or M4A files that ffprobe reads with the same packets and ffmpeg decodes to the
 * same audio.
 */
@DisplayName("Mp4Remuxer")
class Mp4RemuxerTest {
    record Output(Path file, RemuxResult result) {
    }

    static Output remux(Path input, String name, RemuxOptions options) {
        try {
            Files.createDirectories(Mp4MuxerTest.OUTPUT);
            var target = Mp4MuxerTest.OUTPUT.resolve("remux-" + name + ".mp4");
            try (var source = MediaSource.of(input); var channel = FileChannel.open(target, StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                var result = Mp4Remuxer.remux(source, MediaSink.of(channel), options);
                return new Output(target, result);
            }
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    static boolean fastStart(Path file) {
        try (var source = MediaSource.of(file)) {
            return Mp4Remuxer.isFastStart(source);
        }
    }

    /** Asserts that the given streams of two files carry the same packet data in the same order. */
    static void assertSameData(Path expected, int expectedStream, Path actual, int actualStream) {
        var want = ContainerProbe.probe(expected).packetsOf(expectedStream);
        var got = ContainerProbe.probe(actual).packetsOf(actualStream);
        assertEquals(want.size(), got.size(), "packet count");
        for (var index = 0; index < want.size(); index++) {
            assertEquals(want.get(index).hash(), got.get(index).hash(), "packet " + index);
            assertEquals(want.get(index).key(), got.get(index).key(), "keyframe " + index);
        }
    }

    /**
     * Asserts that ffmpeg decodes the first audio stream of both files to the same samples, up to
     * {@code maxShift} interleaved samples of priming the original leaves in its decode.
     */
    static void assertSameAudio(Path expected, Path actual, int maxShift) {
        assertSameAudio(expected, actual, maxShift, 2 * 64);
    }

    /**
     * Asserts like {@link #assertSameAudio(Path, Path, int)}, letting the remuxed decode run up to
     * {@code maxExtra} interleaved samples longer: ffmpeg trims the end padding of Matroska
     * ({@code DiscardPadding}) but not the end of an MP4 edit list.
     */
    static void assertSameAudio(Path expected, Path actual, int maxShift, int maxExtra) {
        var want = Oracle.decodeAudio(expected);
        var got = Oracle.decodeAudio(actual);
        var best = 0;
        var bestError = Double.MAX_VALUE;
        for (var shift = 0; shift <= maxShift && shift < want.length; shift++) {
            var error = 0.0;
            for (var index = 0; index < 2048 && index < got.length && index + shift < want.length; index++) {
                var difference = want[index + shift] - got[index];
                error += difference * difference;
            }
            if (error < bestError) {
                bestError = error;
                best = shift;
            }
        }
        var aligned = java.util.Arrays.copyOfRange(want, best, want.length);
        assertTrue(got.length >= aligned.length - 2 * 64 && got.length <= aligned.length + maxExtra,
                "decoded lengths " + aligned.length + " vs " + got.length + " after skipping " + best);
        assertTrue(Quality.snr(aligned, got) > 60, "decoded audio matches: " + Quality.snr(aligned, got) + " dB");
    }

    @Nested
    @DisplayName("ISO base media inputs")
    class Iso {
        @Test
        @DisplayName("keeps the first video and audio track, moves the moov first and preserves timing")
        void firstTracks() {
            var input = Mp4Fixtures.multiTrack();
            assertFalse(fastStart(input));
            var output = remux(input, "multi", RemuxOptions.video());
            assertTrue(fastStart(output.file()));
            assertEquals(ContainerFormat.MP4, output.result().inputFormat());
            assertEquals(2, output.result().tracks().size());
            assertEquals(Codec.H264, output.result().tracks().get(0).codec());
            assertEquals(Codec.AAC, output.result().tracks().get(1).codec());
            try {
                assertEquals(Files.size(output.file()), output.result().outputSize());
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
            assertEquals(1_000_000, output.result().durationMicros(), 40_000);
            var probeIn = ContainerProbe.probe(input);
            var probeOut = ContainerProbe.probe(output.file());
            assertEquals(2, probeOut.streams().size());
            for (var stream = 0; stream < 2; stream++) {
                var want = probeIn.packetsOf(stream);
                var got = probeOut.packetsOf(stream);
                assertEquals(want.size(), got.size());
                for (var index = 0; index < want.size(); index++) {
                    assertEquals(want.get(index).hash(), got.get(index).hash());
                    assertEquals(want.get(index).pts(), got.get(index).pts());
                    assertEquals(want.get(index).dts(), got.get(index).dts());
                }
            }
            Oracle.assertDecodesCleanly(output.file());
            assertSameAudio(input, output.file(), 0);
        }

        @Test
        @DisplayName("writes the planned single-pass layout byte for byte like the spooled one")
        void plannedMatchesSpooled() throws IOException {
            for (var input : List.of(Mp4Fixtures.h264Aac(true, List.of(), "mp4"),
                    Mp4Fixtures.h264Aac(true, List.of("-movflags", "+negative_cts_offsets"), "mp4"),
                    Mp4Fixtures.copy(Mp4Fixtures.h264Aac(true, List.of(), "mp4"), "mp4", List.of("-ss", "0.7"), List.of()))) {
                // the muxer that spools writes no tags
                var planned = remux(input, "planned", RemuxOptions.video().toBuilder().noTags().build());
                var spooled = Mp4MuxerTest.remux(input, "spooled", Mp4MuxerOptions.mp4(), muxer -> {
                });
                org.junit.jupiter.api.Assertions.assertArrayEquals(Files.readAllBytes(spooled), Files.readAllBytes(planned.file()));
            }
        }

        @Test
        @DisplayName("honors explicit track identifiers and the M4A brand")
        void selection() {
            var input = Mp4Fixtures.multiTrack();
            var output = remux(input, "second-audio", RemuxOptions.audio().toBuilder().audioTrack(3).build());
            assertEquals(1, output.result().tracks().size());
            var probe = ContainerProbe.probe(output.file());
            assertEquals("44100", probe.streams().getFirst().get("sample_rate"));
            assertEquals("M4A ", probe.format().get("tag:major_brand"));
            assertThrows(IllegalArgumentException.class, () -> remux(input, "bad-id", RemuxOptions.video().toBuilder()
                    .audioTrack(1).build()));
            assertThrows(IllegalArgumentException.class, () -> remux(input, "m4a-video", RemuxOptions.audio()
                    .toBuilder().videoTrack(1).build()));
        }

        @Test
        @DisplayName("turns a fragmented file into a fast-start progressive file")
        void fragmented() {
            var original = Mp4Fixtures.h264Aac(true, List.of(), "mp4");
            var fragmented = Mp4Fixtures.copy(original, "mp4", List.of(), List.of("-movflags", "frag_keyframe+empty_moov+default_base_moof"));
            var output = remux(fragmented, "fragmented", RemuxOptions.video());
            assertTrue(fastStart(output.file()));
            assertSameData(fragmented, 0, output.file(), 0);
            assertSameData(fragmented, 1, output.file(), 1);
            Oracle.assertDecodesCleanly(output.file());
        }

        @Test
        @DisplayName("keeps the rotation and edit lists of an iPhone-style HEVC movie")
        void iphoneMovie() {
            var movie = Fixtures.ffmpeg("mov", Mp4Fixtures.both(160, 120, 1.5, 44100), List.of("-c:v", "libx265", "-x265-params",
                    "log-level=error:bframes=3", "-tag:v", "hvc1", "-c:a", "aac"));
            var rotated = Mp4Fixtures.copy(movie, "mov", List.of("-display_rotation", "-90"), List.of());
            var output = remux(rotated, "iphone", RemuxOptions.video());
            var probe = ContainerProbe.probe(output.file());
            assertEquals(ContainerProbe.probe(rotated).streams().getFirst().rotation(), probe.streams().getFirst().rotation());
            Mp4MuxerTest.assertSameProbe(rotated, output.file());
            Oracle.assertDecodesCleanly(output.file());
        }

        @Test
        @DisplayName("rejects codecs MP4 cannot carry, naming them")
        void unsupported() {
            var prores = Fixtures.ffmpeg("mov", Mp4Fixtures.both(96, 64, 1, 48000),
                    List.of("-c:v", "prores_ks", "-c:a", "aac"));
            var failure = assertThrows(MediaException.Unsupported.class, () -> remux(prores, "prores", RemuxOptions.video()));
            assertEquals("prores", failure.format());
            assertTrue(failure.getMessage().contains("QuickTime"), failure.getMessage());
            var audioOnly = remux(prores, "prores-audio", RemuxOptions.video().toBuilder().noVideo().build());
            assertEquals(Codec.AAC, audioOnly.result().tracks().getFirst().codec());
            Oracle.assertDecodesCleanly(audioOnly.file());
            var pcm = Fixtures.ffmpeg("mov", Mp4Fixtures.audio(22050, 0.5), List.of("-c:a", "pcm_u8"));
            assertEquals("pcm_u8", assertThrows(MediaException.Unsupported.class, () -> remux(pcm, "pcm", RemuxOptions.audio())).format());
        }
    }

    @Nested
    @DisplayName("Matroska inputs")
    class Matroska {
        @Test
        @DisplayName("remuxes H.264 with B-frames and AAC, deriving decoding timestamps")
        void h264Aac() {
            var input = Fixtures.ffmpeg("mkv", Mp4Fixtures.both(160, 120, 2, 48000), List.of("-c:v", "libx264", "-preset", "veryfast",
                    "-g", "15", "-bf", "3", "-c:a", "aac"));
            var output = remux(input, "mkv-h264", RemuxOptions.video());
            assertEquals(ContainerFormat.MATROSKA, output.result().inputFormat());
            assertTrue(fastStart(output.file()));
            assertSameData(input, 0, output.file(), 0);
            assertSameData(input, 1, output.file(), 1);
            var probeIn = ContainerProbe.probe(input).packetsOf(0);
            var probeOut = ContainerProbe.probe(output.file()).packetsOf(0);
            for (var index = 0; index < probeIn.size(); index++) {
                assertEquals(probeIn.get(index).pts(), probeOut.get(index).pts(), "video pts " + index);
                assertTrue(probeOut.get(index).dts() <= probeOut.get(index).pts());
            }
            Oracle.assertDecodesCleanly(output.file());
            // ffmpeg keeps the 1024 AAC priming samples of a Matroska input in its decode; the MP4 edit list trims them
            assertSameAudio(input, output.file(), 2048);
        }

        static Path largeMatroska() {
            return Fixtures.ffmpeg("mkv", List.of("-f", "lavfi", "-i", "testsrc2=size=640x480:rate=30:duration=2",
                            "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000:duration=2"),
                    List.of("-vf", "noise=alls=100:allf=t", "-c:v", "libx264", "-preset", "ultrafast", "-qp", "0", "-bf", "2",
                            "-c:a", "aac"));
        }

        @Test
        @DisplayName("remuxes a large Matroska input that is not local in one read, byte for byte like a local one")
        void largeNotLocal() throws IOException {
            var input = largeMatroska();
            // a source that is not local takes its tags from the demuxer rather than from its tag structure
            var options = RemuxOptions.video().toBuilder().noTags().build();
            var local = remux(input, "mkv-large-local", options);
            var data = Files.readAllBytes(input);
            var source = new CountingSource(data, 64 * 1024);
            var output = new MemoryChannel();
            var result = Mp4Remuxer.remux(source, MediaSink.of(output), options);
            org.junit.jupiter.api.Assertions.assertArrayEquals(Files.readAllBytes(local.file()), output.toByteArray());
            assertEquals(local.result().packets(), result.packets());
            assertEquals(local.result().durationMicros(), result.durationMicros());
            assertEquals(local.result().outputSize(), result.outputSize());
            assertEquals(1, source.requests, source.toString());
            assertTrue(source.bytes <= data.length + 64 * 1024, source.toString());
            var tagged = new CountingSource(data, 64 * 1024);
            Mp4Remuxer.remux(tagged, MediaSink.of(new MemoryChannel()), RemuxOptions.video());
            assertEquals(1, tagged.requests, "carrying the tags reads the input once: " + tagged);
        }

        @Test
        @DisplayName("plans large Matroska inputs in a first reading pass")
        void largeTwoPass() throws IOException {
            var input = largeMatroska();
            assertTrue(Files.size(input) > MdatSpool.MEMORY_LIMIT, "fixture exceeds the in-memory spool");
            var output = remux(input, "mkv-large", RemuxOptions.video());
            assertTrue(fastStart(output.file()));
            assertSameData(input, 0, output.file(), 0);
            assertSameData(input, 1, output.file(), 1);
            Oracle.assertDecodesCleanly(output.file());
            assertSameAudio(input, output.file(), 2048);
        }

        @Test
        @DisplayName("remuxes WebM Opus into M4A with the pre-skip in the edit list")
        void opus() {
            var input = Fixtures.ffmpeg("webm", Mp4Fixtures.audio(48000, 1.5), List.of("-c:a", "libopus", "-ac", "2"));
            var output = remux(input, "webm-opus", RemuxOptions.audio());
            assertSameData(input, 0, output.file(), 0);
            Oracle.assertDecodesCleanly(output.file());
            assertSameAudio(input, output.file(), 0, 2 * 960);
            // the edit list keeps 1.5 s give or take the millisecond rounding of the Matroska DiscardPadding
            var stream = ContainerProbe.probe(output.file()).streams().getFirst();
            assertEquals(72000, Long.parseLong(stream.get("duration_ts")), 48);
            var packets = ContainerProbe.demux(output.file(), Mp4Demuxer::new).order();
            for (var index = 1; index < packets.size(); index++) {
                assertEquals(packets.get(index - 1).pts() + 960, packets.get(index).pts(), "exact 20 ms steps");
            }
        }

        @Test
        @DisplayName("retimes MP3 audio to exact frame steps")
        void mp3() {
            var input = Fixtures.ffmpeg("mkv", Mp4Fixtures.both(160, 120, 1, 44100), List.of("-c:v", "libx264", "-c:a", "libmp3lame"));
            var output = remux(input, "mkv-mp3", RemuxOptions.video());
            var audio = ContainerProbe.demux(output.file(), Mp4Demuxer::new).packets().get(2);
            for (var index = 1; index < audio.size(); index++) {
                assertEquals(1152, audio.get(index).pts() - audio.get(index - 1).pts());
            }
            Oracle.assertDecodesCleanly(output.file());
        }

        @Test
        @DisplayName("rejects Vorbis audio in WebM and non-media input")
        void rejects() {
            var webm = Fixtures.ffmpeg("webm", Mp4Fixtures.both(96, 64, 1, 48000),
                    List.of("-c:v", "libvpx-vp9", "-b:v", "200k", "-deadline", "realtime", "-c:a", "libvorbis"));
            assertEquals("vorbis", assertThrows(MediaException.Unsupported.class, () -> remux(webm, "webm-vorbis", RemuxOptions.video())).format());
            assertFalse(fastStart(webm));
            try (var source = MediaSource.of("this is not a media file".getBytes())) {
                assertFalse(Mp4Remuxer.isFastStart(source));
                var failure = assertThrows(MediaException.Unsupported.class, () -> Mp4Remuxer.remux(source,
                        MediaSink.of(FileChannel.open(Mp4MuxerTest.OUTPUT.resolve("never.mp4"),
                                StandardOpenOption.CREATE,
                                StandardOpenOption.WRITE)),
                        RemuxOptions.video()));
                assertEquals("unknown container", failure.format());
            }
        }
    }
}
