package com.github.auties00.smalt.aot.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.container.ContainerFormat;
import com.github.auties00.smalt.container.Containers;
import com.github.auties00.smalt.container.DemuxerOptions;
import com.github.auties00.smalt.container.subtitle.VobSubDemuxer;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.subtitle.BitmapSubtitleDecoder;
import com.github.auties00.smalt.subtitle.Subtitles;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.Track;
import com.github.auties00.smalt.track.VideoTrack;
import com.github.auties00.smalt.transcode.AudioTranscodeOptions;
import com.github.auties00.smalt.transcode.OutputContainer;
import com.github.auties00.smalt.transcode.TranscodeOptions;
import com.github.auties00.smalt.transcode.Transcoder;
import com.github.auties00.smalt.video.h264.H264Decoder;
import com.github.auties00.smalt.video.h264.H264DecoderOptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the files of the ASF, VobSub and joined inputs training workloads: the ASF writer's file demuxes to the
 * video, interleaved audio and compressed audio it was written from (and ffprobe, when installed, agrees), the DVD
 * subpicture writer's units survive the VobSub pair and the program stream and decode to their bitmaps, and the
 * merged and concatenated transcodes produce the tracks and durations their inputs imply.
 */
@DisplayName("ASF, VobSub and joined inputs training files")
class JoinedAndLegacyFilesTest {
    private static final Rational MILLIS = new Rational(1, 1000);

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    @DisplayName("the ASF file")
    class Asf {
        private ContainerWorkloads.AsfFile asf;
        private ContainerWorkloads.Tracks read;

        @BeforeAll
        void write() {
            asf = ContainerWorkloads.asf();
            read = ContainerWorkloads.read(asf.file());
        }

        @Test
        @DisplayName("is detected as ASF with its three streams and title")
        void tracks() {
            assertEquals(ContainerFormat.ASF, Containers.detect(MediaSource.of(asf.file())).orElseThrow());
            try (var demuxer = Containers.openDemuxer(MediaSource.of(asf.file()))) {
                assertEquals("Smalt training", demuxer.metadata().get("title"), demuxer.metadata().toString());
                assertEquals(2_000_000, demuxer.durationMicros(), 100_000);
            }
            assertEquals(List.of(Codec.H264, Codec.PCM_S16LE, Codec.PCM_U8),
                    read.tracks().stream().map(Track::codec).toList());
            var video = (VideoTrack) read.tracks().getFirst();
            var original = (VideoTrack) asf.video().tracks().getFirst();
            assertEquals(original.width(), video.width());
            assertEquals(original.height(), video.height());
            var config = video.codecConfig().orElseThrow();
            assertEquals(1, config[0], "an avcC record built from the Annex B parameter sets");
            assertArrayEquals(SyntheticAsf.annexBParameterSets(original.codecConfig().orElseThrow()),
                    SyntheticAsf.annexBParameterSets(config));
            var stereo = (AudioTrack) read.tracks().get(1);
            assertEquals(48000, stereo.sampleRate());
            assertEquals(2, stereo.layout().orElseThrow().channels());
            var mono = (AudioTrack) read.tracks().get(2);
            assertEquals(8000, mono.sampleRate());
            assertEquals(1, mono.layout().orElseThrow().channels());
        }

        @Test
        @DisplayName("demuxes the video frames with their times and keys, which decode to the original pictures")
        void video() {
            var original = asf.video().packets();
            var video = packetsOf(read, 1);
            assertEquals(original.size(), video.size());
            var originalTrack = (VideoTrack) asf.video().tracks().getFirst();
            var firstPts = original.stream().mapToLong(Packet::pts).min().orElseThrow();
            var timeBase = read.tracks().getFirst().timeBase();
            for (var index = 0; index < original.size(); index++) {
                var expected = originalTrack.timeBase().rescale(original.get(index).pts() - firstPts, MILLIS);
                assertEquals(expected, timeBase.rescale(video.get(index).pts(), MILLIS), "pts of frame " + index);
                assertEquals(original.get(index).keyframe(), video.get(index).keyframe(), "key of frame " + index);
            }
            var expected = decode(originalTrack, original);
            assertEquals(original.size(), expected.size());
            assertEquals(expected, decode((VideoTrack) read.tracks().getFirst(), video));
        }

        @Test
        @DisplayName("restores the samples of the audio spread stream and of the compressed payloads")
        void audio() {
            assertArrayEquals(asf.stereo(), join(packetsOf(read, 2)));
            var mono = packetsOf(read, 3);
            assertEquals(100, mono.size(), "one packet per 20 ms object");
            assertArrayEquals(asf.mono(), join(mono));
            var timeBase = read.tracks().get(2).timeBase();
            for (var index = 0; index < mono.size(); index++) {
                assertEquals(index * 20L, timeBase.rescale(mono.get(index).pts(), MILLIS), "object " + index);
            }
        }

        @Test
        @DisplayName("seeks to the last key frame of the video at or before the target")
        void seek() {
            var original = asf.video().packets();
            var originalTrack = asf.video().tracks().getFirst();
            var firstPts = original.stream().mapToLong(Packet::pts).min().orElseThrow();
            var target = 1_200_000L;
            var expected = original.stream().filter(Packet::keyframe)
                    .mapToLong(packet -> originalTrack.timeBase().rescale(packet.pts() - firstPts,
                            Rational.MICROSECONDS))
                    .filter(pts -> pts <= target).max().orElseThrow();
            assertTrue(expected > 0, "the video has a key frame after its first before the target");
            try (var demuxer = Containers.openDemuxer(MediaSource.of(asf.file()))) {
                demuxer.seek(target);
                Packet first = null;
                for (var packet = demuxer.read(); packet != null && first == null; packet = demuxer.read()) {
                    if (packet.trackId() == 1) {
                        first = packet;
                    }
                }
                assertTrue(first != null && first.keyframe(), "the first video packet after the seek is a key frame");
                assertEquals(expected, demuxer.tracks().getFirst().timeBase().rescale(first.pts(),
                        Rational.MICROSECONDS));
            }
        }

        @Test
        @DisplayName("truncated anywhere, reads what it holds or fails with a MediaException")
        void truncated() {
            var file = asf.file();
            for (var length = 0; length < file.length; length += 977) {
                var cut = Arrays.copyOf(file, length);
                try (var demuxer = Containers.openDemuxer(MediaSource.of(cut))) {
                    for (var packet = demuxer.read(); packet != null; packet = demuxer.read()) {
                        assertTrue(packet.size() >= 0);
                    }
                } catch (MediaException expected) {
                    // a header cut short is malformed
                }
            }
        }

        @Test
        @DisplayName("with bytes overwritten anywhere, reads what it can or fails with a MediaException")
        void corrupted() {
            var random = new Random(0x5A5F);
            var file = asf.file();
            for (var trial = 0; trial < 200; trial++) {
                var corrupt = file.clone();
                // half of the trials hit the header objects, where every field is structural
                var limit = trial % 2 == 0 ? Math.min(file.length, 1024) : file.length;
                for (var hit = 0; hit < 1 + trial % 8; hit++) {
                    corrupt[random.nextInt(limit)] = (byte) random.nextInt(256);
                }
                readOrFail(corrupt);
            }
        }

        @Test
        @DisplayName("reads in ffprobe as the same streams and media objects")
        void ffprobe(@TempDir Path directory) throws IOException, InterruptedException {
            Oracle.assume(Oracle.Tool.FFPROBE);
            var path = directory.resolve("training.asf");
            Files.write(path, asf.file());
            var streams = run("ffprobe", "-v", "error", "-show_entries",
                    "stream=index,codec_name,sample_rate,channels,width,height", "-of", "csv=p=0", path.toString());
            assertEquals(List.of("0,h264,320,180", "1,pcm_s16le,48000,2", "2,pcm_u8,8000,1"),
                    streams.stream().map(line -> line.replaceAll(",+$", "").replace(",,", ",")).toList(),
                    String.join("\n", streams));
            var packets = run("ffprobe", "-v", "error", "-show_entries", "packet=stream_index,size", "-of",
                    "csv=p=0", path.toString());
            var counts = new int[3];
            var sizes = new long[3];
            for (var line : packets) {
                var fields = line.split(",");
                var stream = Integer.parseInt(fields[0]);
                counts[stream]++;
                sizes[stream] += Long.parseLong(fields[1]);
            }
            assertEquals(asf.video().packets().size(), counts[0]);
            assertEquals(100, counts[2]);
            assertEquals(asf.stereo().length, sizes[1]);
            assertEquals(asf.mono().length, sizes[2]);
        }

        @Test
        @DisplayName("decodes in ffmpeg to the original samples and pictures")
        void ffmpeg(@TempDir Path directory) throws IOException, InterruptedException {
            Oracle.assume(Oracle.Tool.FFMPEG);
            var path = directory.resolve("training.asf");
            Files.write(path, asf.file());
            // an independent demuxer undoes the audio spread and expands the compressed payloads
            assertArrayEquals(asf.stereo(), output("ffmpeg", "-v", "error", "-i", path.toString(), "-map", "0:1",
                    "-c:a", "copy", "-f", "s16le", "-"));
            assertArrayEquals(asf.mono(), output("ffmpeg", "-v", "error", "-i", path.toString(), "-map", "0:2",
                    "-c:a", "copy", "-f", "u8", "-"));
            var pictures = output("ffmpeg", "-v", "error", "-i", path.toString(), "-map", "0:0", "-fps_mode",
                    "passthrough", "-pix_fmt", "yuv420p", "-f", "rawvideo", "-");
            var frameSize = 320 * 180 * 3 / 2;
            assertEquals(0, pictures.length % frameSize);
            var hashes = new ArrayList<String>();
            for (var offset = 0; offset < pictures.length; offset += frameSize) {
                hashes.add(md5(pictures, offset, frameSize));
            }
            assertEquals(decode((VideoTrack) asf.video().tracks().getFirst(), asf.video().packets()), hashes);
        }
    }

    @Nested
    @DisplayName("the VobSub files")
    class VobSub {
        @Test
        @DisplayName("write a pair whose index names the languages and whose units read back unchanged")
        void pair() {
            var tracks = ContainerWorkloads.vobSubTracks(6);
            var pair = ContainerWorkloads.vobSub(tracks);
            var index = new String(pair[0], StandardCharsets.UTF_8);
            assertTrue(index.contains("id: en, index: 0") && index.contains("id: de, index: 1"), index);
            assertTrue(index.contains("size: 720x480"), index);
            try (var demuxer = new VobSubDemuxer(MediaSource.of(pair[0]), MediaSource.of(pair[1]),
                    DemuxerOptions.defaults())) {
                assertEquals(List.of("eng", "ger"),
                        demuxer.tracks().stream().map(track -> track.language().orElseThrow()).toList());
                var read = new ArrayList<Packet>();
                for (var packet = demuxer.read(); packet != null; packet = demuxer.read()) {
                    read.add(packet);
                }
                assertSamePackets(tracks.packets(), read, demuxer.tracks());
            }
            try (var demuxer = Containers.openDemuxer(MediaSource.of(pair[1]))) {
                assertInstanceOf(VobSubDemuxer.class, demuxer);
            }
        }

        @Test
        @DisplayName("read in ffprobe as two DVD subtitle streams of their units")
        void ffprobe(@TempDir Path directory) throws IOException, InterruptedException {
            Oracle.assume(Oracle.Tool.FFPROBE);
            var tracks = ContainerWorkloads.vobSubTracks(6);
            var pair = ContainerWorkloads.vobSub(tracks);
            Files.write(directory.resolve("pair.idx"), pair[0]);
            Files.write(directory.resolve("pair.sub"), pair[1]);
            var index = directory.resolve("pair.idx").toString();
            assertEquals(List.of("0,dvd_subtitle", "1,dvd_subtitle"), run("ffprobe", "-v", "error", "-show_entries",
                    "stream=index,codec_name", "-of", "csv=p=0", index));
            var sizes = run("ffprobe", "-v", "error", "-show_entries", "packet=stream_index,size", "-of", "csv=p=0",
                    index);
            var expected = new ArrayList<String>();
            for (var stream = 0; stream < 2; stream++) {
                for (var packet : tracks.packets()) {
                    if (packet.trackId() == stream + 1) {
                        expected.add(stream + "," + packet.size());
                    }
                }
            }
            assertEquals(expected, sizes.stream().sorted(java.util.Comparator.comparingInt(
                    (String line) -> Integer.parseInt(line.split(",")[0]))).toList());
        }

        @Test
        @DisplayName("truncated or with bytes overwritten, read what they can or fail with a MediaException")
        void malformed() {
            var pair = ContainerWorkloads.vobSub(ContainerWorkloads.vobSubTracks(4));
            var random = new Random(0x7EB5);
            for (var trial = 0; trial < 120; trial++) {
                var idx = pair[0];
                var sub = pair[1];
                switch (trial % 4) {
                    case 0 -> sub = Arrays.copyOf(sub, random.nextInt(sub.length));
                    case 1 -> idx = Arrays.copyOf(idx, random.nextInt(idx.length));
                    case 2 -> {
                        sub = sub.clone();
                        for (var hit = 0; hit < 1 + trial % 6; hit++) {
                            sub[random.nextInt(sub.length)] = (byte) random.nextInt(256);
                        }
                    }
                    default -> {
                        idx = idx.clone();
                        idx[random.nextInt(idx.length)] = (byte) (' ' + random.nextInt(95));
                    }
                }
                try (var demuxer = new VobSubDemuxer(MediaSource.of(idx), MediaSource.of(sub),
                        DemuxerOptions.defaults())) {
                    var decoders = new HashMap<Integer, BitmapSubtitleDecoder>();
                    for (var track : demuxer.tracks()) {
                        decoders.put(track.id(), new BitmapSubtitleDecoder(track));
                    }
                    for (var packet = demuxer.read(); packet != null; packet = demuxer.read()) {
                        var decoder = decoders.get(packet.trackId());
                        if (decoder != null) {
                            decoder.decode(packet);
                        }
                    }
                } catch (MediaException expected) {
                    // a malformed pair or unit
                }
                readOrFail(sub);
            }
        }

        @Test
        @DisplayName("keep their units through an MPEG program stream")
        void programStream() {
            var tracks = ContainerWorkloads.vobSubTracks(4);
            var file = ContainerWorkloads.write(ContainerFormat.MPEG_PS, tracks);
            var read = ContainerWorkloads.read(file);
            assertEquals(2, read.tracks().size());
            assertSamePackets(tracks.packets(), read.packets(), read.tracks());
        }

        @Test
        @DisplayName("decode to cues of the bitmaps, colors, contrasts, placement and times they were written with")
        void bitmaps() {
            var tracks = ContainerWorkloads.vobSubTracks(5);
            var pair = ContainerWorkloads.vobSub(tracks);
            var fromFile = Subtitles.readBitmaps(MediaSource.of(pair[1]));
            assertEquals(5, fromFile.size(), "the cues of the first track of the program stream");
            for (var index = 0; index < fromFile.size(); index++) {
                // without the index the units decode with a gray palette, so only the contrasts are checked
                assertCue(fromFile.get(index), index, false);
            }
            var decoder = new BitmapSubtitleDecoder(tracks.tracks().getFirst());
            var cues = new ArrayList<com.github.auties00.smalt.subtitle.BitmapCue>();
            for (var packet : tracks.packets()) {
                if (packet.trackId() == 1) {
                    cues.addAll(decoder.decode(packet));
                }
            }
            cues.addAll(decoder.flush());
            assertEquals(5, cues.size());
            for (var index = 0; index < cues.size(); index++) {
                var cue = cues.get(index);
                assertEquals(720, cue.canvasWidth());
                assertEquals(480, cue.canvasHeight());
                assertCue(cue, index, true);
            }
        }

        private static void assertCue(com.github.auties00.smalt.subtitle.BitmapCue cue, int index, boolean colors) {
            var duration = Math.round(SyntheticDvdSub.STOP_DELAY * 1024 * 1_000_000.0 / 90_000);
            assertEquals(index * 2_000_000L, cue.startMicros(), 1);
            assertEquals(index * 2_000_000L + duration, cue.endMicros(), 1);
            assertEquals(1, cue.regions().size());
            var region = cue.regions().getFirst();
            assertEquals(SyntheticDvdSub.left(360), region.x());
            assertEquals(SyntheticDvdSub.top(64), region.y());
            var picture = region.picture();
            assertEquals(360, picture.width());
            assertEquals(64, picture.height());
            for (var y = 0; y < 64; y++) {
                for (var x = 0; x < 360; x++) {
                    var value = SyntheticDvdSub.pixel(index, 360, 64, x, y);
                    var at = y * picture.stride() + x * 4;
                    var where = "cue " + index + " at " + x + "," + y;
                    assertEquals(SyntheticDvdSub.CONTRAST[3 - value] * 17, picture.pixels()[at + 3] & 0xFF, where);
                    if (colors && (value == 1 || value == 2)) {
                        var rgb = SyntheticDvdSub.PALETTE[SyntheticDvdSub.COLORS[3 - value]];
                        assertEquals(rgb >> 16 & 0xFF, picture.pixels()[at] & 0xFF, where);
                        assertEquals(rgb >> 8 & 0xFF, picture.pixels()[at + 1] & 0xFF, where);
                        assertEquals(rgb & 0xFF, picture.pixels()[at + 2] & 0xFF, where);
                    }
                }
            }
        }

        private static void assertSamePackets(List<Packet> expected, List<Packet> actual, List<Track> tracks) {
            assertEquals(expected.size(), actual.size());
            for (var trackIndex = 0; trackIndex < 2; trackIndex++) {
                var track = tracks.get(trackIndex);
                var writtenId = trackIndex + 1;
                var wanted = expected.stream().filter(packet -> packet.trackId() == writtenId).toList();
                var got = actual.stream().filter(packet -> packet.trackId() == track.id()).toList();
                assertEquals(wanted.size(), got.size());
                for (var index = 0; index < wanted.size(); index++) {
                    assertArrayEquals(wanted.get(index).data(), got.get(index).data(), "unit " + index);
                    assertEquals(wanted.get(index).pts(),
                            track.timeBase().rescale(got.get(index).pts(), SyntheticDvdSub.TIME_BASE));
                }
            }
        }
    }

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    @DisplayName("the joined inputs")
    class Joined {
        private byte[] mp4;
        private List<ContainerWorkloads.JoinedJob> jobs;

        @BeforeAll
        void write() {
            mp4 = SyntheticFiles.mp4(320, 180, 2);
            var m4a = SyntheticFiles.m4a(2);
            var flac = transcodeOnce(m4a);
            jobs = ContainerWorkloads.joinedJobs(mp4, m4a, flac);
        }

        @Test
        @DisplayName("merge the MP4's video, copied unchanged, with the M4A's audio re-encoded as Opus")
        void merged() {
            var output = ContainerWorkloads.read(run(jobs.getFirst()));
            assertEquals(List.of(Codec.H264, Codec.OPUS), output.tracks().stream().map(Track::codec).toList());
            var source = ContainerWorkloads.read(mp4);
            var sourceVideo = packetsOf(source, source.tracks().getFirst().id());
            var video = packetsOf(output, output.tracks().getFirst().id());
            assertEquals(sourceVideo.size(), video.size());
            for (var index = 0; index < video.size(); index++) {
                assertArrayEquals(sourceVideo.get(index).data(), video.get(index).data(), "frame " + index);
            }
            assertFalse(packetsOf(output, output.tracks().get(1).id()).isEmpty());
        }

        @Test
        @DisplayName("concatenate an M4A and a FLAC file of another rate into four seconds of AAC")
        void concatenatedAudio() {
            var file = run(jobs.get(1));
            try (var demuxer = Containers.openDemuxer(MediaSource.of(file))) {
                assertEquals(List.of(Codec.AAC), demuxer.tracks().stream().map(Track::codec).toList());
                assertEquals(4_000_000, demuxer.durationMicros(), 100_000);
            }
        }

        @Test
        @DisplayName("concatenate an MP4 with itself into twice its frames of re-encoded video")
        void concatenatedVideo() {
            var file = run(jobs.get(2));
            var source = ContainerWorkloads.read(mp4);
            var output = ContainerWorkloads.read(file);
            var video = (VideoTrack) output.tracks().stream().filter(track -> track.codec() == Codec.H264)
                    .findFirst().orElseThrow();
            assertEquals(90, Math.min(video.width(), video.height()));
            assertEquals(2 * packetsOf(source, source.tracks().getFirst().id()).size(),
                    packetsOf(output, video.id()).size());
            try (var demuxer = Containers.openDemuxer(MediaSource.of(file))) {
                assertEquals(4_000_000, demuxer.durationMicros(), 100_000);
            }
        }

        private static byte[] run(ContainerWorkloads.JoinedJob job) {
            var sink = MediaSink.memory();
            Transcoder.transcode(job.inputs(), sink, job.options());
            return sink.toByteArray();
        }

        private static byte[] transcodeOnce(byte[] m4a) {
            var sink = MediaSink.memory();
            Transcoder.transcode(MediaSource.of(m4a), sink, TranscodeOptions.of(OutputContainer.FLAC).toBuilder()
                    .audio(AudioTranscodeOptions.encode(Codec.FLAC).toBuilder().sampleRate(48000).build()).build());
            return sink.toByteArray();
        }
    }

    private static void readOrFail(byte[] file) {
        try (var demuxer = Containers.openDemuxer(MediaSource.of(file))) {
            for (var packet = demuxer.read(); packet != null; packet = demuxer.read()) {
                assertTrue(packet.size() >= 0);
            }
        } catch (MediaException expected) {
            // malformed input
        }
    }

    private static List<Packet> packetsOf(ContainerWorkloads.Tracks tracks, int trackId) {
        return tracks.packets().stream().filter(packet -> packet.trackId() == trackId).toList();
    }

    private static byte[] join(List<Packet> packets) {
        var out = new ByteArrayOutputStream();
        packets.forEach(packet -> out.writeBytes(packet.data()));
        return out.toByteArray();
    }

    private static List<String> decode(VideoTrack track, List<Packet> packets) {
        var hashes = new ArrayList<String>();
        try (var decoder = new H264Decoder(track, H264DecoderOptions.defaults())) {
            java.util.function.Consumer<com.github.auties00.smalt.video.VideoFrame> output =
                    frame -> hashes.add(md5((YuvPicture) frame.picture()));
            for (var packet : packets) {
                decoder.decode(packet, output);
            }
            decoder.flush(output);
        }
        return hashes;
    }

    private static String md5(YuvPicture picture) {
        try {
            var digest = MessageDigest.getInstance("MD5");
            var width = picture.width();
            var height = picture.height();
            var chromaWidth = picture.chromaFormat().chromaWidth(width);
            var chromaHeight = picture.chromaFormat().chromaHeight(height);
            for (var row = 0; row < height; row++) {
                digest.update(picture.y(), row * picture.yStride(), width);
            }
            for (var row = 0; row < chromaHeight; row++) {
                digest.update(picture.u(), row * picture.uvStride(), chromaWidth);
            }
            for (var row = 0; row < chromaHeight; row++) {
                digest.update(picture.v(), row * picture.uvStride(), chromaWidth);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError(exception);
        }
    }

    private static String md5(byte[] data, int offset, int length) {
        try {
            var digest = MessageDigest.getInstance("MD5");
            digest.update(data, offset, length);
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError(exception);
        }
    }

    private static byte[] output(String... command) throws IOException, InterruptedException {
        var process = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        var output = process.getInputStream().readAllBytes();
        assertTrue(process.waitFor(60, TimeUnit.SECONDS));
        assertEquals(0, process.exitValue());
        return output;
    }

    private static List<String> run(String... command) throws IOException, InterruptedException {
        var process = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(60, TimeUnit.SECONDS));
        assertEquals(0, process.exitValue(), output);
        return output.lines().filter(line -> !line.isBlank()).toList();
    }
}
