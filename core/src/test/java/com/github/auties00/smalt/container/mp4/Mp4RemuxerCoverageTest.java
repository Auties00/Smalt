package com.github.auties00.smalt.container.mp4;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.container.ContainerFormat;
import com.github.auties00.smalt.container.ContainerProbe;
import com.github.auties00.smalt.container.Containers;
import com.github.auties00.smalt.container.MemoryChannel;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Track;
import com.github.auties00.smalt.track.TrackKind;
import com.github.auties00.smalt.transcode.OutputContainer;
import com.github.auties00.smalt.transcode.TranscodeOptions;
import com.github.auties00.smalt.transcode.Transcoder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the codecs, track selections and inputs {@link Mp4Remuxer} copies besides H.264, HEVC, AAC,
 * Opus, MP3, ALAC and FLAC from MP4 and Matroska: VP8, VP9, AV1, AC-3, E-AC-3, AMR, PCM, ProRes,
 * text subtitles and several audio tracks, from transport streams, AVI and Ogg too. The packets of
 * the output must equal the input's, as Smalt and ffprobe read them.
 */
@DisplayName("Mp4Remuxer codec coverage")
class Mp4RemuxerCoverageTest {
    static Mp4RemuxerTest.Output remux(Path input, String name, RemuxOptions options) {
        return Mp4RemuxerTest.remux(input, name, options);
    }

    static ContainerProbe.Demuxed demux(Path file) {
        return ContainerProbe.demux(file, Containers::openDemuxer);
    }

    /** Asserts that Smalt reads the same payloads, in order, from each track of the input and the output. */
    static void assertSamePackets(Path input, Path output, List<Track> outputTracks) {
        var before = demux(input);
        var after = ContainerProbe.demux(output, Mp4Demuxer::new);
        assertEquals(outputTracks.size(), after.tracks().size());
        var used = new ArrayList<Track>();
        for (var index = 0; index < outputTracks.size(); index++) {
            var written = after.tracks().get(index);
            Track source = null;
            for (var candidate : before.tracks()) {
                if (source == null && candidate.codec() == written.codec() && !used.contains(candidate)) {
                    source = candidate;
                }
            }
            used.add(source);
            var expected = before.packetsOf(source);
            var actual = after.packetsOf(written);
            if (written.codec().isPcm()) {
                assertArrayEquals(concatenate(expected), concatenate(actual), "PCM of track " + index);
                continue;
            }
            if (written.codec().isSubtitle()) {
                continue;
            }
            assertEquals(expected.size(), actual.size(), "packets of track " + index + " " + written.codec());
            for (var position = 0; position < expected.size(); position++) {
                var data = expected.get(position).data();
                if (written.codec() == Codec.PRORES && !Mp4Muxer.hasProResPrefix(data)) {
                    data = Mp4Muxer.withProResPrefix(data);
                }
                assertArrayEquals(data, actual.get(position).data(), "track " + index + " packet " + position);
            }
        }
    }

    static byte[] concatenate(List<Packet> packets) {
        var out = new ByteArrayOutputStream();
        packets.forEach(packet -> out.writeBytes(packet.data()));
        return out.toByteArray();
    }

    static String videoMd5(Path file) {
        return new String(Oracle.run(Oracle.Tool.FFMPEG, Duration.ofMinutes(2), List.of("-v", "error", "-i", file.toString(),
                "-map", "0:v:0", "-f", "md5", "-")), StandardCharsets.US_ASCII).trim();
    }

    static String audioMd5(Path file, int stream) {
        return new String(Oracle.run(Oracle.Tool.FFMPEG, Duration.ofMinutes(2), List.of("-v", "error", "-i", file.toString(),
                "-map", "0:a:" + stream, "-c:a", "pcm_f64le", "-f", "md5", "-")), StandardCharsets.US_ASCII).trim();
    }

    /**
     * Asserts that ffmpeg decodes the output's first audio stream to the input's samples, followed by
     * at most {@code extraFrames} sample frames: ffmpeg trims the end of an Ogg stream by its granule
     * position but does not trim the end of an MP4 edit list.
     */
    static void assertSameDecodedAudio(Path input, Path output, int channels, int extraFrames) {
        var want = Oracle.run(Oracle.Tool.FFMPEG, Duration.ofMinutes(2), List.of("-v", "error", "-i", input.toString(),
                "-map", "0:a:0", "-c:a", "pcm_f32le", "-f", "f32le", "-"));
        var got = Oracle.run(Oracle.Tool.FFMPEG, Duration.ofMinutes(2), List.of("-v", "error", "-i", output.toString(),
                "-map", "0:a:0", "-c:a", "pcm_f32le", "-f", "f32le", "-"));
        assertTrue(got.length >= want.length && got.length <= want.length + 4L * channels * extraFrames,
                "decoded " + got.length + " bytes for " + want.length);
        assertArrayEquals(want, java.util.Arrays.copyOf(got, want.length), "decoded samples");
    }

    @Nested
    @DisplayName("codecs")
    class Codecs {
        @Test
        @DisplayName("remuxes WebM VP9 and Opus into MP4 with bit-identical packets and a derived vpcC")
        void vp9() {
            var input = Fixtures.ffmpeg("webm", Mp4Fixtures.both(160, 120, 2, 48000), List.of("-c:v", "libvpx-vp9", "-b:v", "200k",
                    "-deadline", "realtime", "-cpu-used", "8", "-g", "15", "-c:a", "libopus"));
            var output = remux(input, "webm-vp9", RemuxOptions.video());
            assertTrue(Mp4RemuxerTest.fastStart(output.file()));
            assertEquals(List.of(Codec.VP9, Codec.OPUS), output.result().tracks().stream().map(Track::codec).toList());
            assertSamePackets(input, output.file(), output.result().tracks());
            Mp4RemuxerTest.assertSameData(input, 0, output.file(), 0);
            Mp4RemuxerTest.assertSameData(input, 1, output.file(), 1);
            var config = ContainerProbe.demux(output.file(), Mp4Demuxer::new).tracks().getFirst().codecConfig().orElseThrow();
            assertEquals(8, config.length, "vpcC derived from the first key frame");
            Oracle.assertDecodesCleanly(output.file());
        }

        @Test
        @DisplayName("remuxes Matroska AV1 with AC-3 and E-AC-3 into MP4 with bit-identical packets")
        void av1Ac3() {
            var input = Fixtures.ffmpeg("mkv", List.of("-f", "lavfi", "-i", "testsrc2=size=160x120:rate=25:duration=2",
                    "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000:duration=2",
                    "-f", "lavfi", "-i", "sine=frequency=660:sample_rate=48000:duration=2"), List.of("-map", "0", "-map", "1", "-map", "2",
                    "-c:v", "libaom-av1", "-cpu-used", "8", "-usage", "realtime", "-g", "25", "-c:a:0", "ac3", "-c:a:1", "eac3",
                    "-ac", "2"));
            var output = remux(input, "mkv-av1-ac3", RemuxOptions.video().toBuilder().allAudioTracks().build());
            assertEquals(List.of(Codec.AV1, Codec.AC3, Codec.EAC3), output.result().tracks().stream().map(Track::codec).toList());
            assertSamePackets(input, output.file(), output.result().tracks());
            for (var stream = 0; stream < 3; stream++) {
                Mp4RemuxerTest.assertSameData(input, stream, output.file(), stream);
            }
            var probe = ContainerProbe.probe(output.file());
            assertEquals(List.of("av1", "ac3", "eac3"), probe.streams().stream().map(ContainerProbe.ProbeStream::codecName).toList());
            var audio = ContainerProbe.demux(output.file(), Mp4Demuxer::new);
            var ac3 = audio.packetsOf(audio.tracks().get(1));
            for (var index = 1; index < ac3.size(); index++) {
                assertEquals(1536, ac3.get(index).pts() - ac3.get(index - 1).pts(), "exact AC-3 frame steps");
            }
        }

        @Test
        @DisplayName("remuxes 3GPP AMR-NB into M4A with the same frames")
        void amr() {
            var input = Fixtures.ffmpeg("3gp", Mp4Fixtures.audio(8000, 1), List.of("-c:a", "libopencore_amrnb", "-ar", "8000",
                    "-ac", "1", "-b:a", "12.2k"));
            var output = remux(input, "amr", RemuxOptions.audio());
            assertEquals(Codec.AMR_NB, output.result().tracks().getFirst().codec());
            assertSamePackets(input, output.file(), output.result().tracks());
            assertEquals(audioMd5(input, 0), audioMd5(output.file(), 0));
        }

        @Test
        @DisplayName("remuxes a ProRes and 24-bit PCM movie into a QuickTime file that ffprobe reads alike")
        void proresPcm() {
            var input = Fixtures.ffmpeg("mov", Mp4Fixtures.both(320, 240, 1, 48000), List.of("-c:v", "prores_ks", "-profile:v", "2",
                    "-c:a", "pcm_s24le", "-ac", "2"));
            var options = RemuxOptions.video().toBuilder().brand(Mp4MuxerOptions.Brand.QUICKTIME).build();
            var output = remux(input, "prores-pcm", options);
            assertEquals(List.of(Codec.PRORES, Codec.PCM_S24LE), output.result().tracks().stream().map(Track::codec).toList());
            assertSamePackets(input, output.file(), output.result().tracks());
            var probe = ContainerProbe.probe(output.file());
            assertEquals(List.of("prores", "pcm_s24le"), probe.streams().stream().map(ContainerProbe.ProbeStream::codecName).toList());
            Mp4RemuxerTest.assertSameData(input, 0, output.file(), 0);
            assertEquals(audioMd5(input, 0), audioMd5(output.file(), 0));
            assertTrue(Mp4RemuxerTest.fastStart(output.file()));
        }

        @Test
        @DisplayName("remuxes QuickTime H.264 and PCM into an ISO file through the planned single-pass layout")
        void plannedPcm() throws IOException {
            var input = Fixtures.ffmpeg("mov", Mp4Fixtures.both(160, 120, 1.5, 48000), List.of("-c:v", "libx264", "-g", "15",
                    "-c:a", "pcm_s24le", "-ac", "2"));
            var output = remux(input, "mov-pcm-planned", RemuxOptions.video());
            assertEquals(List.of(Codec.H264, Codec.PCM_S24LE), output.result().tracks().stream().map(Track::codec).toList());
            assertSamePackets(input, output.file(), output.result().tracks());
            assertEquals("ipcm", ContainerProbe.probe(output.file()).streams().get(1).get("codec_tag_string"));
            assertEquals(audioMd5(input, 0), audioMd5(output.file(), 0));
            // the plan from the index writes what the muxer writes when it spools the demuxed packets
            var spooled = Mp4MuxerTest.remux(input, "mov-pcm-spooled", Mp4MuxerOptions.mp4(), muxer -> {
            });
            assertArrayEquals(Files.readAllBytes(spooled), Files.readAllBytes(output.file()));
        }

        @Test
        @DisplayName("retimes Matroska PCM and keeps its samples")
        void matroskaPcm() {
            var input = Fixtures.ffmpeg("mkv", Mp4Fixtures.audio(44100, 1.3), List.of("-c:a", "pcm_s16le", "-ac", "2"));
            var options = RemuxOptions.audio().toBuilder().brand(Mp4MuxerOptions.Brand.MP4).build();
            var output = remux(input, "mkv-pcm", options);
            assertEquals(Codec.PCM_S16LE, output.result().tracks().getFirst().codec());
            // the M4A brand carries no PCM
            assertThrows(MediaException.Unsupported.class, () -> remux(input, "mkv-pcm-m4a", RemuxOptions.audio()));
            assertSamePackets(input, output.file(), output.result().tracks());
            assertEquals(audioMd5(input, 0), audioMd5(output.file(), 0));
        }
    }

    @Nested
    @DisplayName("track selections")
    class Selections {
        static Path multiTrack() {
            var inputs = new ArrayList<>(Mp4Fixtures.video(160, 120, 1.5, 25));
            inputs.addAll(List.of("-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000:duration=1.5",
                    "-f", "lavfi", "-i", "sine=frequency=880:sample_rate=44100:duration=1.5"));
            return Fixtures.ffmpeg("mkv", inputs, List.of("-map", "0", "-map", "1", "-map", "2", "-c:v", "libx264", "-g", "15",
                    "-c:a:0", "libopus", "-c:a:1", "aac"));
        }

        @Test
        @DisplayName("keeps every audio track, or the ones named, in the order named")
        void severalAudioTracks() {
            var input = multiTrack();
            var every = remux(input, "mkv-all-audio", RemuxOptions.video().toBuilder().allAudioTracks().build());
            assertEquals(List.of(Codec.H264, Codec.OPUS, Codec.AAC), every.result().tracks().stream().map(Track::codec).toList());
            assertSamePackets(input, every.file(), every.result().tracks());
            for (var stream = 0; stream < 3; stream++) {
                Mp4RemuxerTest.assertSameData(input, stream, every.file(), stream);
            }
            Oracle.assertDecodesCleanly(every.file());
            var ids = demux(input).tracks().stream().filter(track -> track.kind() == TrackKind.AUDIO).map(Track::id).toList();
            var named = remux(input, "mkv-named-audio", RemuxOptions.audio().toBuilder().audioTracks(ids.get(1), ids.get(0)).build());
            assertEquals(List.of(Codec.AAC, Codec.OPUS), named.result().tracks().stream().map(Track::codec).toList());
            Mp4RemuxerTest.assertSameData(input, 2, named.file(), 0);
            Mp4RemuxerTest.assertSameData(input, 1, named.file(), 1);
        }

        static Path subtitled(String extension, String codec) {
            try {
                var directory = Files.createDirectories(Path.of("target", "media-fixtures"));
                var srt = directory.resolve("remux-cues.srt");
                Files.writeString(srt, "1\n00:00:00,300 --> 00:00:01,000\nHello\n\n2\n00:00:01,500 --> 00:00:02,200\nWorld\n",
                        StandardCharsets.UTF_8);
                var inputs = new ArrayList<>(Mp4Fixtures.both(160, 120, 2.5, 48000));
                inputs.addAll(List.of("-i", srt.toString()));
                return Fixtures.ffmpeg(extension, inputs, List.of("-map", "0:v", "-map", "1:a", "-map", "2:s", "-c:v", "libx264",
                        "-c:a", "aac", "-c:s", codec));
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
        }

        @Test
        @DisplayName("copies the 3GPP timed text samples of an MP4 input as they are")
        void timedText() {
            var input = subtitled("mp4", "mov_text");
            var output = remux(input, "mp4-mov-text", RemuxOptions.video().toBuilder().allSubtitleTracks().build());
            assertEquals(List.of(Codec.H264, Codec.AAC, Codec.MOV_TEXT), output.result().tracks().stream().map(Track::codec).toList());
            Mp4RemuxerTest.assertSameData(input, 2, output.file(), 2);
            var before = ContainerProbe.probe(input).packetsOf(2);
            var after = ContainerProbe.probe(output.file()).packetsOf(2);
            for (var index = 0; index < before.size(); index++) {
                assertEquals(before.get(index).pts(), after.get(index).pts(), "cue " + index);
                assertEquals(before.get(index).duration(), after.get(index).duration(), "cue " + index);
            }
            var cues = new String(Oracle.run(Oracle.Tool.FFMPEG, Duration.ofMinutes(1), List.of("-v", "error", "-i",
                    output.file().toString(), "-map", "0:s:0", "-f", "srt", "-")), StandardCharsets.UTF_8);
            assertTrue(cues.contains("00:00:00,300 --> 00:00:01,000") && cues.contains("World"), cues);
        }

        @Test
        @DisplayName("cuts the WebVTT cues of a Matroska input into wvtt samples")
        void webVtt() {
            var input = subtitled("mkv", "webvtt");
            var output = remux(input, "mkv-webvtt", RemuxOptions.video().toBuilder().allSubtitleTracks().build());
            var tracks = ContainerProbe.demux(output.file(), Mp4Demuxer::new);
            var text = tracks.tracks().get(2);
            assertEquals(Codec.WEBVTT, text.codec());
            // the samples are an empty cue to 0.3 s, Hello, an empty cue to 1.5 s and World, which Smalt reads back as cues
            var cues = tracks.packetsOf(text);
            var source = demux(input);
            var original = source.packetsOf(source.tracks().get(2));
            assertEquals(original.size(), cues.size());
            var millis = com.github.auties00.smalt.track.Rational.timeBaseOf(1000);
            for (var index = 0; index < cues.size(); index++) {
                var expected = original.get(index);
                var actual = cues.get(index);
                assertArrayEquals(expected.data(), actual.data(), "cue " + index);
                assertEquals(source.tracks().get(2).timeBase().rescale(expected.pts(), millis), text.timeBase().rescale(actual.pts(), millis));
                assertEquals(source.tracks().get(2).timeBase().rescale(expected.duration(), millis),
                        text.timeBase().rescale(actual.duration(), millis), "duration " + index);
            }
            var probe = ContainerProbe.probe(output.file()).packetsOf(2);
            assertEquals(List.of(0L, 300L, 1000L, 1500L), probe.stream().map(ContainerProbe.ProbePacket::pts).toList());
        }

        @Test
        @DisplayName("rejects subtitles it cannot copy and identifiers of the wrong kind")
        void rejects() {
            var input = subtitled("mkv", "srt");
            var failure = assertThrows(MediaException.Unsupported.class,
                    () -> remux(input, "mkv-subrip", RemuxOptions.video().toBuilder().allSubtitleTracks().build()));
            assertEquals("subrip", failure.format());
            var video = demux(input).tracks().stream().filter(track -> track.kind() == TrackKind.VIDEO).findFirst().orElseThrow();
            assertThrows(IllegalArgumentException.class, () -> remux(input, "mkv-wrong-kind",
                    RemuxOptions.video().toBuilder().subtitleTracks(video.id()).build()));
            assertThrows(IllegalArgumentException.class, () -> RemuxOptions.builder().audioTracks(2, 2).build());
            assertThrows(IllegalArgumentException.class, () -> RemuxOptions.builder().audioTracks().build());
            assertThrows(IllegalArgumentException.class, () -> RemuxOptions.builder().videoTrack(1).audioTrack(1).build());
            assertThrows(IllegalArgumentException.class, () -> RemuxOptions.audio().toBuilder().subtitleTracks(3).build());
            var options = RemuxOptions.builder().audioTracks(4, 3).allSubtitleTracks().build();
            assertEquals(List.of(4, 3), options.audioTrackIds());
            assertTrue(options.audioTrackId().isEmpty());
            assertTrue(options.includesEverySubtitleTrack());
            assertEquals(options, options.toBuilder().build());
            assertEquals(3, RemuxOptions.builder().audioTrack(3).build().audioTrackId().orElseThrow());
        }
    }

    @Nested
    @DisplayName("inputs")
    class Inputs {
        @Test
        @DisplayName("remuxes an MPEG transport stream with H.264 and AAC, retiming the 90 kHz audio")
        void transportStream() {
            var input = Fixtures.ffmpeg("ts", Mp4Fixtures.both(160, 120, 2, 44100), List.of("-c:v", "libx264", "-bf", "2", "-g", "15",
                    "-c:a", "aac"));
            var output = remux(input, "ts", RemuxOptions.video());
            assertEquals(ContainerFormat.MPEG_TS, output.result().inputFormat());
            assertSamePackets(input, output.file(), output.result().tracks());
            // ffprobe reads transport stream video as Annex B, so the oracle compares the decoded pictures
            assertEquals(videoMd5(input), videoMd5(output.file()), "ffmpeg decodes the same pictures");
            var audio = ContainerProbe.demux(output.file(), Mp4Demuxer::new);
            var aac = audio.packetsOf(audio.tracks().get(1));
            for (var index = 1; index < aac.size(); index++) {
                assertEquals(1024, aac.get(index).pts() - aac.get(index - 1).pts(), "exact AAC frame steps");
            }
            Oracle.assertDecodesCleanly(output.file());
        }

        @Test
        @DisplayName("remuxes AVI with MPEG-4 Part 2 and MP3")
        void avi() {
            var input = Fixtures.ffmpeg("avi", Mp4Fixtures.both(176, 144, 2, 44100), List.of("-c:v", "mpeg4", "-g", "12",
                    "-c:a", "libmp3lame"));
            var output = remux(input, "avi", RemuxOptions.video());
            assertEquals(ContainerFormat.AVI, output.result().inputFormat());
            assertEquals(List.of(Codec.MPEG4_PART2, Codec.MP3), output.result().tracks().stream().map(Track::codec).toList());
            assertSamePackets(input, output.file(), output.result().tracks());
            Mp4RemuxerTest.assertSameData(input, 0, output.file(), 0);
            Oracle.assertDecodesCleanly(output.file());
        }

        @Test
        @DisplayName("remuxes Ogg Opus and Ogg FLAC into M4A that decodes to the same samples")
        void ogg() {
            var opus = Fixtures.ffmpeg("ogg", Mp4Fixtures.audio(48000, 1.5), List.of("-c:a", "libopus", "-ac", "2"));
            var output = remux(opus, "ogg-opus", RemuxOptions.audio());
            assertEquals(ContainerFormat.OGG, output.result().inputFormat());
            assertSamePackets(opus, output.file(), output.result().tracks());
            assertSameDecodedAudio(opus, output.file(), 2, 960);
            var flac = Fixtures.ffmpeg("ogg", Mp4Fixtures.audio(44100, 1.5), List.of("-c:a", "flac"));
            var flacOutput = remux(flac, "ogg-flac", RemuxOptions.audio());
            assertSamePackets(flac, flacOutput.file(), flacOutput.result().tracks());
            assertSameDecodedAudio(flac, flacOutput.file(), 1, 4608);
        }

        @Test
        @DisplayName("plans a large local transport stream in a first reading pass, byte for byte like a spooled one")
        void largeTransportStream() throws IOException {
            var input = Fixtures.ffmpeg("ts", List.of("-f", "lavfi", "-i", "testsrc2=size=640x480:rate=30:duration=2",
                            "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000:duration=2"),
                    List.of("-vf", "noise=alls=100:allf=t", "-c:v", "libx264", "-preset", "ultrafast", "-qp", "0", "-bf", "2",
                            "-c:a", "aac"));
            assertTrue(Files.size(input) > MdatSpool.MEMORY_LIMIT, "fixture exceeds the in-memory spool");
            var planned = remux(input, "ts-large", RemuxOptions.video());
            var data = Files.readAllBytes(input);
            var source = new com.github.auties00.smalt.testkit.container.CountingSource(data, 64 * 1024);
            var spooled = new MemoryChannel();
            Mp4Remuxer.remux(source, MediaSink.of(spooled), RemuxOptions.video());
            assertArrayEquals(spooled.toByteArray(), Files.readAllBytes(planned.file()));
            assertTrue(source.bytes < data.length * 3L / 2, "a source that is not local is read once: " + source);
            assertEquals(videoMd5(input), videoMd5(planned.file()), "ffmpeg decodes the same pictures");
        }
    }

    @Nested
    @DisplayName("transcoding")
    class Transcoding {
        @Test
        @DisplayName("hands MOV remuxes with ProRes, PCM and two audio tracks to the remuxer")
        void movRemux() throws IOException {
            var inputs = new ArrayList<>(Mp4Fixtures.both(320, 240, 1, 48000));
            inputs.addAll(List.of("-f", "lavfi", "-i", "sine=frequency=880:sample_rate=48000:duration=1"));
            var input = Fixtures.ffmpeg("mov", inputs, List.of("-map", "0", "-map", "1", "-map", "2", "-c:v", "prores_ks",
                    "-c:a", "pcm_s24le", "-ac", "2"));
            var transcoded = Mp4MuxerTest.OUTPUT.resolve("transcode-mov.mov");
            Files.createDirectories(Mp4MuxerTest.OUTPUT);
            com.github.auties00.smalt.transcode.TranscodeResult result;
            try (var source = MediaSource.of(input); var channel = java.nio.channels.FileChannel.open(transcoded,
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE,
                    java.nio.file.StandardOpenOption.TRUNCATE_EXISTING)) {
                var options = TranscodeOptions.remux(OutputContainer.MOV).toBuilder()
                        .audio(com.github.auties00.smalt.transcode.AudioTranscodeOptions.copy().toBuilder().allTracks().build())
                        .build();
                result = Transcoder.transcode(source, MediaSink.of(channel), options);
            }
            assertTrue(result.remuxed());
            assertEquals(3, result.streams().size());
            var probe = ContainerProbe.probe(transcoded);
            assertEquals(List.of("prores", "pcm_s24le", "pcm_s24le"),
                    probe.streams().stream().map(ContainerProbe.ProbeStream::codecName).toList());
            var direct = remux(input, "mov-direct", RemuxOptions.video().toBuilder().brand(Mp4MuxerOptions.Brand.QUICKTIME)
                    .allAudioTracks().build());
            assertArrayEquals(Files.readAllBytes(direct.file()), Files.readAllBytes(transcoded),
                    "the transcode writes what the remuxer writes");
            assertEquals(audioMd5(input, 1), audioMd5(transcoded, 1));
        }
    }
}
