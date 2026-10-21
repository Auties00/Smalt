package com.github.auties00.smalt.container;

import com.github.auties00.smalt.audio.AudioBuffer;
import com.github.auties00.smalt.audio.AudioReader;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.audio.process.ChannelMixer;
import com.github.auties00.smalt.container.mp4.Mp4Demuxer;
import com.github.auties00.smalt.container.mp4.Mp4Muxer;
import com.github.auties00.smalt.container.ts.TsDemuxer;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.PacketSideData;
import com.github.auties00.smalt.track.TrackKind;
import com.github.auties00.smalt.track.VideoTrack;
import com.github.auties00.smalt.transcode.OutputContainer;
import com.github.auties00.smalt.transcode.TranscodeOptions;
import com.github.auties00.smalt.transcode.Transcoder;
import com.github.auties00.smalt.video.VideoReader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Decodes streams whose codec configuration changes in the middle: an MP4 track with two
 * {@code avc1} sample descriptions, and two transport streams of different picture sizes and
 * channel counts concatenated byte for byte. The pictures and samples must equal those of the two
 * halves decoded separately, bit for bit (the second half's mono audio mixed to the stereo of the
 * output), and a copy of the concatenation into MP4 must keep both descriptions.
 */
@DisplayName("Mid-stream codec configuration changes")
class ConfigChangeDecodeTest {
    private static final Path OUTPUT = Path.of("target", "config-change");

    private static Path clip(String extension, String size, String channels, List<String> extra) {
        var options = new ArrayList<>(List.of("-c:v", "libx264", "-g", "5", "-bf", "2", "-pix_fmt", "yuv420p",
                "-c:a", "aac", "-ac", channels, "-threads", "1"));
        options.addAll(extra);
        return Fixtures.ffmpeg(extension, List.of(
                "-f", "lavfi", "-i", "testsrc2=size=" + size + ":rate=10:duration=1.5",
                "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000:duration=1.5"), options);
    }

    private static Path first(String extension) {
        return clip(extension, "64x48", "2", List.of());
    }

    private static Path second(String extension) {
        return clip(extension, "96x64", "1", extension.equals("ts") ? List.of("-output_ts_offset", "2") : List.of());
    }

    private static Path save(String name, byte[] data) {
        try {
            Files.createDirectories(OUTPUT);
            var file = OUTPUT.resolve(name);
            Files.write(file, data);
            return file;
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static byte[] read(Path file) {
        try {
            return Files.readAllBytes(file);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    /** Returns a digest of every plane of a picture, row by row, with its size. */
    private static String digest(Picture picture) {
        var yuv = (YuvPicture) picture;
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            for (var row = 0; row < yuv.height(); row++) {
                digest.update(yuv.y(), row * yuv.yStride(), yuv.width());
            }
            for (var row = 0; row < yuv.chromaHeight(); row++) {
                digest.update(yuv.u(), row * yuv.uvStride(), yuv.chromaWidth());
                digest.update(yuv.v(), row * yuv.uvStride(), yuv.chromaWidth());
            }
            return yuv.width() + "x" + yuv.height() + ":" + HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError(exception);
        }
    }

    private static List<String> frames(byte[] data) {
        var result = new ArrayList<String>();
        try (var source = MediaSource.of(data); var reader = VideoReader.open(source)) {
            for (var frame = reader.read(); frame != null; frame = reader.read()) {
                result.add(digest(frame.picture()));
            }
        }
        return result;
    }

    private static float[][] samples(byte[] data) {
        var planes = new ArrayList<float[]>();
        var length = 0;
        var channels = 0;
        try (var source = MediaSource.of(data); var reader = AudioReader.open(source)) {
            channels = reader.layout().channels();
            for (var index = 0; index < channels; index++) {
                planes.add(new float[0]);
            }
            AudioBuffer block;
            while ((block = reader.read()) != null) {
                for (var channel = 0; channel < channels; channel++) {
                    var grown = java.util.Arrays.copyOf(planes.get(channel), length + block.frames());
                    System.arraycopy(block.plane(channel), 0, grown, length, block.frames());
                    planes.set(channel, grown);
                }
                length += block.frames();
            }
        }
        return planes.toArray(new float[0][]);
    }

    private static List<String> expectedFrames(String extension) {
        var expected = new ArrayList<>(frames(read(first(extension))));
        expected.addAll(frames(read(second(extension))));
        return expected;
    }

    @Test
    @DisplayName("decodes an MP4 track with two avc1 sample descriptions as its two halves")
    void twoDescriptions() {
        var packets = new ArrayList<Packet>();
        VideoTrack track;
        long offset;
        try (var source = MediaSource.of(first("mp4")); var demuxer = new Mp4Demuxer(source)) {
            track = (VideoTrack) demuxer.tracks().stream().filter(candidate -> candidate.kind() == TrackKind.VIDEO)
                    .findFirst().orElseThrow();
            offset = track.duration();
            for (var packet = demuxer.read(); packet != null; packet = demuxer.read()) {
                if (packet.trackId() == track.id()) {
                    packets.add(packet);
                }
            }
        }
        var firstCount = packets.size();
        try (var source = MediaSource.of(second("mp4")); var demuxer = new Mp4Demuxer(source)) {
            var other = (VideoTrack) demuxer.tracks().stream().filter(candidate -> candidate.kind() == TrackKind.VIDEO)
                    .findFirst().orElseThrow();
            assertEquals(track.timeBase(), other.timeBase());
            var change = true;
            for (var packet = demuxer.read(); packet != null; packet = demuxer.read()) {
                if (packet.trackId() != other.id()) {
                    continue;
                }
                var side = change ? PacketSideData.builder().newCodecConfig(other.codecConfig().orElseThrow()).build()
                        : PacketSideData.NONE;
                change = false;
                packets.add(new Packet(track.id(), packet.data(), packet.pts() + offset, packet.dts() + offset,
                        packet.duration(), packet.flags(), side));
            }
        }
        var sink = MediaSink.memory();
        try (var muxer = new Mp4Muxer(sink)) {
            muxer.addTrack(track.toBuilder().duration(Packet.UNKNOWN_DURATION).build());
            for (var packet : packets) {
                muxer.writePacket(packet);
            }
            muxer.finish();
        }
        var output = sink.toByteArray();
        save("two-descriptions.mp4", output);
        assertEquals(expectedFrames("mp4"), frames(output));
        try (var source = MediaSource.of(output); var demuxer = new Mp4Demuxer(source)) {
            var changes = new ArrayList<Integer>();
            var index = 0;
            for (var packet = demuxer.read(); packet != null; packet = demuxer.read(), index++) {
                if (packet.sideData().newCodecConfig().isPresent()) {
                    changes.add(index);
                }
            }
            assertEquals(List.of(firstCount), changes);
        }
        try (var source = MediaSource.of(output); var reader = VideoReader.open(source)) {
            reader.seek(2_200_000);
            var frame = reader.read();
            assertEquals(96, frame.picture().width());
            var expected = frames(read(second("mp4")));
            assertEquals(expected.get(7), digest(frame.picture()));
        }
        if (Oracle.find(Oracle.Tool.FFPROBE).isPresent()) {
            var counted = new String(Oracle.run(Oracle.Tool.FFPROBE, Duration.ofMinutes(1), List.of("-v", "error",
                    "-count_frames", "-show_entries", "stream=nb_read_frames", "-of", "csv=p=0",
                    OUTPUT.resolve("two-descriptions.mp4").toAbsolutePath().toString())), StandardCharsets.UTF_8);
            assertEquals("30", counted.strip());
        }
    }

    @Test
    @DisplayName("decodes two concatenated transport streams of different sizes and channel counts")
    void concatenatedTransportStreams() {
        var concatenated = Mp4Fixture.concat(read(first("ts")), read(second("ts")));
        save("concatenated.ts", concatenated);
        try (var source = MediaSource.of(concatenated); var demuxer = new TsDemuxer(source)) {
            var changes = new ArrayList<String>();
            for (var packet = demuxer.read(); packet != null; packet = demuxer.read()) {
                if (packet.sideData().newCodecConfig().isPresent()) {
                    for (var track : demuxer.tracks()) {
                        if (track.id() == packet.trackId()) {
                            changes.add(track.kind().name());
                        }
                    }
                }
            }
            assertEquals(List.of("AUDIO", "VIDEO"), changes.stream().sorted().toList());
        }
        assertEquals(expectedFrames("ts"), frames(concatenated));
        var firstAudio = samples(read(first("ts")));
        var secondAudio = samples(read(second("ts")));
        var actual = samples(concatenated);
        assertEquals(2, actual.length);
        var mixed = new float[2][secondAudio[0].length];
        new ChannelMixer(ChannelLayout.MONO, ChannelLayout.STEREO).process(secondAudio, mixed, secondAudio[0].length);
        for (var channel = 0; channel < 2; channel++) {
            var expected = java.util.Arrays.copyOf(firstAudio[channel], firstAudio[channel].length + mixed[channel].length);
            System.arraycopy(mixed[channel], 0, expected, firstAudio[channel].length, mixed[channel].length);
            assertArrayEquals(expected, actual[channel], "channel " + channel);
        }
    }

    private static byte[] latm(String channels, List<String> extra) {
        var options = new ArrayList<>(List.of("-c:a", "aac", "-ac", channels, "-mpegts_flags", "latm"));
        options.addAll(extra);
        return read(Fixtures.ffmpeg("ts", List.of("-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000:duration=1.5"),
                options));
    }

    @Test
    @DisplayName("decodes two concatenated LATM transport streams of different channel counts")
    void concatenatedLatm() {
        var firstHalf = latm("2", List.of());
        var secondHalf = latm("1", List.of("-output_ts_offset", "2"));
        byte[] secondConfig;
        try (var source = MediaSource.of(secondHalf); var demuxer = new TsDemuxer(source)) {
            secondConfig = demuxer.tracks().getFirst().codecConfig().orElseThrow();
        }
        var concatenated = Mp4Fixture.concat(firstHalf, secondHalf);
        var changes = new ArrayList<byte[]>();
        try (var source = MediaSource.of(concatenated); var demuxer = new TsDemuxer(source)) {
            for (var packet = demuxer.read(); packet != null; packet = demuxer.read()) {
                packet.sideData().newCodecConfig().ifPresent(changes::add);
            }
        }
        assertEquals(1, changes.size());
        assertArrayEquals(secondConfig, changes.getFirst());
        var firstAudio = samples(firstHalf);
        var secondAudio = samples(secondHalf);
        var actual = samples(concatenated);
        assertEquals(2, actual.length);
        var mixed = new float[2][secondAudio[0].length];
        new ChannelMixer(ChannelLayout.MONO, ChannelLayout.STEREO).process(secondAudio, mixed, secondAudio[0].length);
        for (var channel = 0; channel < 2; channel++) {
            var expected = java.util.Arrays.copyOf(firstAudio[channel], firstAudio[channel].length + mixed[channel].length);
            System.arraycopy(mixed[channel], 0, expected, firstAudio[channel].length, mixed[channel].length);
            assertArrayEquals(expected, actual[channel], "channel " + channel);
        }
    }

    @Test
    @DisplayName("decodes the audio of the concatenation after a seek into its second half")
    void audioSeekAcrossChange() {
        var concatenated = Mp4Fixture.concat(read(first("ts")), read(second("ts")));
        var firstLength = samples(read(first("ts")))[0].length;
        var full = samples(concatenated);
        try (var source = MediaSource.of(concatenated); var reader = AudioReader.open(source)) {
            assertEquals(2, reader.layout().channels());
            // 0.4 s into the second half, which starts 2 s into the timeline; the reader joins the halves
            // across the gap and AAC restarts converge only closely, so the samples are matched in the full decode
            reader.seek(2_400_000);
            var block = reader.read();
            var frames = Math.min(block.frames(), 1024);
            var best = Double.MAX_VALUE;
            var bestAt = -1;
            for (var offset = 0; offset + frames <= full[0].length; offset++) {
                var worst = 0.0;
                for (var channel = 0; channel < 2; channel++) {
                    for (var index = 0; index < frames && worst < best; index++) {
                        worst = Math.max(worst, Math.abs(full[channel][offset + index] - block.plane(channel)[index]));
                    }
                }
                if (worst < best) {
                    best = worst;
                    bestAt = offset;
                }
            }
            assertTrue(best < 1e-3 && bestAt >= firstLength, "closest to the samples at " + bestAt + " (the second half"
                    + " starts at " + firstLength + ") with an error of " + best);
        }
    }

    @Test
    @DisplayName("copies the concatenation into an MP4 with two sample descriptions per track")
    void copyIntoMp4() {
        var concatenated = Mp4Fixture.concat(read(first("ts")), read(second("ts")));
        var sink = MediaSink.memory();
        try (var source = MediaSource.of(concatenated)) {
            Transcoder.transcode(source, sink, TranscodeOptions.of(OutputContainer.MP4));
        }
        var output = sink.toByteArray();
        save("copied.mp4", output);
        assertEquals(expectedFrames("ts"), frames(output));
        var descriptions = new ArrayList<Integer>();
        var text = new String(output, StandardCharsets.ISO_8859_1);
        for (var index = text.indexOf("stsd"); index >= 0; index = text.indexOf("stsd", index + 4)) {
            descriptions.add((int) Mp4Fixture.u32(output, index + 8));
        }
        assertEquals(List.of(2, 2), descriptions);
        assertTrue(samples(output)[0].length > 48_000 * 2, "the audio of both halves is kept");
    }

    @ParameterizedTest
    @EnumSource(value = ContainerFormat.class, names = {"MP4", "MATROSKA", "AVI", "MPEG_TS"})
    @DisplayName("copies the video of the concatenation into a container that stores the change, and decodes it again")
    void copyVideo(ContainerFormat format) {
        var concatenated = Mp4Fixture.concat(read(first("ts")), read(second("ts")));
        var sink = MediaSink.memory();
        var changes = 0;
        try (var source = MediaSource.of(concatenated); var demuxer = new TsDemuxer(source);
             var muxer = Containers.openMuxer(format, sink)) {
            var video = demuxer.tracks().stream().filter(track -> track.kind() == TrackKind.VIDEO).findFirst()
                    .orElseThrow();
            muxer.addTrack(video);
            for (var packet = demuxer.read(); packet != null; packet = demuxer.read()) {
                if (packet.trackId() == video.id()) {
                    changes += packet.sideData().newCodecConfig().isPresent() ? 1 : 0;
                    muxer.writePacket(packet);
                }
            }
            muxer.finish();
        }
        assertEquals(1, changes);
        var output = sink.toByteArray();
        save("copied-video." + format.name().toLowerCase(java.util.Locale.ROOT), output);
        assertEquals(expectedFrames("ts"), frames(output));
    }

    @Test
    @DisplayName("decodes two concatenated HEVC transport streams of different sizes")
    void concatenatedHevc() {
        var options = List.of("-c:v", "libx265", "-x265-params", "keyint=5:min-keyint=5:bframes=2:log-level=error",
                "-pix_fmt", "yuv420p", "-an");
        var firstHalf = read(Fixtures.ffmpeg("ts", List.of("-f", "lavfi", "-i", "testsrc2=size=64x48:rate=10:duration=1"),
                options));
        var secondOptions = new ArrayList<>(options);
        secondOptions.addAll(List.of("-output_ts_offset", "2"));
        var secondHalf = read(Fixtures.ffmpeg("ts", List.of("-f", "lavfi", "-i", "testsrc2=size=96x64:rate=10:duration=1"),
                secondOptions));
        var concatenated = Mp4Fixture.concat(firstHalf, secondHalf);
        var changes = 0;
        try (var source = MediaSource.of(concatenated); var demuxer = new TsDemuxer(source)) {
            for (var packet = demuxer.read(); packet != null; packet = demuxer.read()) {
                if (packet.sideData().newCodecConfig().isPresent()) {
                    changes++;
                }
            }
        }
        assertEquals(1, changes);
        var expected = new ArrayList<>(frames(firstHalf));
        expected.addAll(frames(secondHalf));
        assertEquals(20, expected.size());
        assertEquals(expected, frames(concatenated));
    }

    @Test
    @DisplayName("seeks into the second half of a concatenation")
    void seekAcrossChange() {
        var concatenated = Mp4Fixture.concat(read(first("ts")), read(second("ts")));
        var expected = expectedFrames("ts");
        try (var source = MediaSource.of(concatenated); var reader = VideoReader.open(source)) {
            var times = new ArrayList<Long>();
            for (var frame = reader.read(); frame != null; frame = reader.read()) {
                times.add(reader.track().timeBase().toMicros(frame.pts()));
            }
            assertEquals(30, times.size());
            for (var index : new int[]{22, 15, 3}) {
                reader.seek(times.get(index) + 1);
                assertEquals(expected.get(index), digest(reader.read().picture()), "frame " + index);
            }
        }
    }

    @Test
    @DisplayName("converts the second half of a concatenation at another sampling rate to the first's")
    void rateChange() {
        var other = Fixtures.ffmpeg("ts", List.of("-f", "lavfi", "-i", "sine=frequency=440:sample_rate=44100:duration=1.5"),
                List.of("-c:a", "aac", "-ac", "2", "-output_ts_offset", "2"));
        var audioOnly = Fixtures.ffmpeg("ts", List.of("-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000:duration=1.5"),
                List.of("-c:a", "aac", "-ac", "2"));
        var firstAudio = samples(read(audioOnly));
        var secondAudio = samples(read(other));
        var actual = samples(Mp4Fixture.concat(read(audioOnly), read(other)));
        assertArrayEquals(firstAudio[0], java.util.Arrays.copyOf(actual[0], firstAudio[0].length));
        var converted = actual[0].length - firstAudio[0].length;
        var expected = secondAudio[0].length * 48_000L / 44_100;
        assertTrue(Math.abs(converted - expected) <= 64, converted + " samples, expected about " + expected);
    }

    @Test
    @DisplayName("re-encodes a concatenation whose picture size changes")
    void reencode() {
        var concatenated = Mp4Fixture.concat(read(first("ts")), read(second("ts")));
        var sink = MediaSink.memory();
        try (var source = MediaSource.of(concatenated)) {
            Transcoder.transcode(source, sink, TranscodeOptions.of(OutputContainer.MP4).toBuilder()
                    .video(com.github.auties00.smalt.transcode.VideoTranscodeOptions.encode(
                            com.github.auties00.smalt.track.Codec.H264)).build());
        }
        var output = frames(sink.toByteArray());
        assertEquals(30, output.size());
    }

    /** Small byte helpers. */
    private static final class Mp4Fixture {
        static byte[] concat(byte[] first, byte[] second) {
            var out = java.util.Arrays.copyOf(first, first.length + second.length);
            System.arraycopy(second, 0, out, first.length, second.length);
            return out;
        }

        static long u32(byte[] data, int position) {
            return (data[position] & 0xFFL) << 24 | (data[position + 1] & 0xFF) << 16 | (data[position + 2] & 0xFF) << 8
                    | data[position + 3] & 0xFF;
        }
    }
}
