package com.github.auties00.smalt.video;

import com.github.auties00.smalt.container.Containers;
import com.github.auties00.smalt.container.matroska.MatroskaMuxer;
import com.github.auties00.smalt.container.matroska.MatroskaMuxerOptions;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.Orientation;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.Quality;
import com.github.auties00.smalt.testkit.Scratch;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.VideoTrack;
import com.github.auties00.smalt.transcode.OutputContainer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers {@link VideoWriter} with the encoders of every artifact: synthetic pictures written at a
 * constant rate or at chosen times come back from a {@link VideoReader}, and from ffmpeg, with the
 * written frame count and timestamps and a luma PSNR that only a correctly ordered and aligned
 * decode reaches.
 */
class VideoWriterTest {
    private static final Path DIRECTORY = Scratch.directory("video-writer");
    private static final AtomicInteger OUTPUTS = new AtomicInteger();
    private static final int WIDTH = 96;
    private static final int HEIGHT = 64;
    private static final int FRAMES = 24;

    static Stream<Arguments> profiles() {
        // container, codec (null for the profile's default), the expected codec, the lowest luma PSNR in dB
        return Stream.of(
                Arguments.of(OutputContainer.MP4, null, Codec.H264, 28.0),
                Arguments.of(OutputContainer.FRAGMENTED_MP4, Codec.H264, Codec.H264, 28.0),
                Arguments.of(OutputContainer.MPEG_TS, null, Codec.H264, 28.0),
                Arguments.of(OutputContainer.MATROSKA, Codec.H264, Codec.H264, 28.0),
                Arguments.of(OutputContainer.WEBM, null, Codec.VP9, 28.0),
                Arguments.of(OutputContainer.MATROSKA, Codec.VP8, Codec.VP8, 28.0),
                Arguments.of(OutputContainer.IVF, Codec.AV1, Codec.AV1, 28.0),
                Arguments.of(OutputContainer.MP4, Codec.AV1, Codec.AV1, 28.0),
                Arguments.of(OutputContainer.MOV, null, Codec.PRORES, 35.0),
                Arguments.of(OutputContainer.AVI, Codec.MJPEG, Codec.MJPEG, 27.0));
    }

    @ParameterizedTest(name = "{0} {2}")
    @MethodSource("profiles")
    @DisplayName("returns every frame written at the nominal rate, in order and at its timestamp")
    void constantRate(OutputContainer container, Codec codec, Codec expected, double minimumPsnr) {
        var rate = new Rational(25, 1);
        var builder = VideoWriterOptions.of(WIDTH, HEIGHT, rate).toBuilder().bitrate(2_000_000);
        if (codec != null) {
            builder.codec(codec);
        }
        var pictures = pictures(FRAMES);
        var sink = MediaSink.memory();
        try (var writer = VideoWriter.open(sink, container, builder.build())) {
            assertEquals(expected, writer.codec());
            assertTrue(writer.track().isPresent(), "a known size declares the track at once");
            for (var picture : pictures) {
                writer.write(picture);
            }
            assertEquals(FRAMES, writer.framesWritten());
            assertEquals(FRAMES * 40_000L, writer.durationMicros());
            writer.finish();
        }
        var file = save(container, sink.toByteArray());
        var times = new ArrayList<Long>();
        try (var reader = VideoReader.open(MediaSource.of(sink.toByteArray()))) {
            assertEquals(expected, reader.track().codec());
            assertEquals(WIDTH, reader.track().width());
            assertEquals(HEIGHT, reader.track().height());
            var index = 0;
            for (var frame = reader.read(); frame != null; frame = reader.read(), index++) {
                assertTrue(index < FRAMES, "more frames than written");
                times.add(reader.track().timeBase().toMicros(frame.pts()));
                var psnr = lumaPsnr(pictures.get(index), frame.picture());
                assertTrue(psnr > minimumPsnr, "frame " + index + " luma PSNR " + psnr + " dB");
            }
            assertEquals(FRAMES, index, "frame count");
        }
        var origin = times.getFirst();
        for (var index = 0; index < FRAMES; index++) {
            assertEquals(index * 40_000L, times.get(index) - origin, 1_000, "timestamp of frame " + index);
        }
        Oracle.assertDecodesCleanly(file);
        var decoded = Oracle.decodeVideo(file, "gray", FRAMES + 8);
        assertEquals(FRAMES * WIDTH * HEIGHT, decoded.length, "ffmpeg's frame count");
    }

    static Stream<Arguments> variableRates() {
        return Stream.of(
                Arguments.of(OutputContainer.MATROSKA, Codec.VP8),
                Arguments.of(OutputContainer.MATROSKA, Codec.H264),
                Arguments.of(OutputContainer.MP4, Codec.H264),
                Arguments.of(OutputContainer.WEBM, Codec.VP9),
                Arguments.of(OutputContainer.MP4, Codec.AV1));
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("variableRates")
    @DisplayName("places frames at the requested times and durations, continuing the nominal grid after them")
    void variableRate(OutputContainer container, Codec codec) {
        var rate = new Rational(30_000, 1001);
        var pictures = pictures(8);
        var sink = MediaSink.memory();
        var options = VideoWriterOptions.builder().codec(codec).frameRate(rate).timeBase(Rational.MICROSECONDS).build();
        long[] expected;
        try (var writer = VideoWriter.open(sink, container, options)) {
            assertTrue(writer.track().isEmpty(), "the size waits for the first picture");
            writer.write(pictures.get(0));
            assertEquals(WIDTH, writer.track().orElseThrow().width());
            writer.write(pictures.get(1), 100_000);
            writer.write(pictures.get(2), 250_000, 500_000);
            writer.write(pictures.get(3));
            writer.write(pictures.get(4));
            writer.write(pictures.get(5), 2_000_000);
            writer.write(pictures.get(6));
            writer.write(pictures.get(7));
            expected = new long[]{0, 100_000, 250_000, 750_000, 783_367, 2_000_000, 2_033_367, 2_066_733};
            assertEquals(2_100_100, writer.durationMicros(), 1);
            writer.finish();
        }
        var data = sink.toByteArray();
        var file = save(container, data);
        try (var reader = VideoReader.open(MediaSource.of(data))) {
            var index = 0;
            long origin = 0;
            for (var frame = reader.read(); frame != null; frame = reader.read(), index++) {
                var micros = reader.track().timeBase().toMicros(frame.pts());
                if (index == 0) {
                    origin = micros;
                }
                assertEquals(expected[index], micros - origin, 1_000, "timestamp of frame " + index);
                assertTrue(lumaPsnr(pictures.get(index), frame.picture()) > 25, "frame " + index);
            }
            assertEquals(8, index);
        }
        Oracle.assertDecodesCleanly(file);
        // ffprobe's presentation times of the frames, relative to the first
        var probed = new String(Oracle.run(Oracle.Tool.FFPROBE, java.time.Duration.ofMinutes(1), List.of("-v", "error",
                "-select_streams", "v:0", "-show_entries", "frame=pts_time", "-of", "csv=p=0", file.toString())))
                .lines().map(String::strip).filter(line -> !line.isEmpty()).map(Double::parseDouble).sorted().toList();
        assertEquals(8, probed.size(), "ffprobe's frame count");
        for (var index = 0; index < 8; index++) {
            assertEquals(expected[index] / 1e6, probed.get(index) - probed.getFirst(), 0.0011, "ffprobe time of frame " + index);
        }
    }

    @Test
    @DisplayName("writes through a caller's live Matroska muxer to a stream")
    void callerMuxer() {
        var stream = new ByteArrayOutputStream();
        var muxer = new MatroskaMuxer(MediaSink.of(stream), MatroskaMuxerOptions.matroska().toBuilder().live(true).build());
        var pictures = pictures(6);
        try (var writer = VideoWriter.open(muxer, VideoWriterOptions.builder().codec(Codec.VP8).build())) {
            for (var picture : pictures) {
                writer.write(picture);
            }
            writer.finish();
        }
        save(OutputContainer.MATROSKA, stream.toByteArray());
        try (var reader = VideoReader.open(MediaSource.of(stream.toByteArray()))) {
            var index = 0;
            for (var frame = reader.read(); frame != null; frame = reader.read(), index++) {
                assertTrue(lumaPsnr(pictures.get(index), frame.picture()) > 25, "frame " + index);
            }
            assertEquals(6, index);
        }
    }

    @Test
    @DisplayName("writes the orientation, color, language and a requested key frame")
    void metadataAndKeyframes() {
        var options = VideoWriterOptions.of(WIDTH, HEIGHT, new Rational(24, 1)).toBuilder().codec(Codec.H264)
                .noPeriodicKeyframes().orientation(Orientation.ROTATE_90).color(ColorInfo.BT709_LIMITED)
                .language("deu").build();
        var pictures = pictures(20);
        var sink = MediaSink.memory();
        try (var writer = VideoWriter.open(sink, OutputContainer.MP4, options)) {
            for (var index = 0; index < 20; index++) {
                if (index == 12) {
                    writer.requestKeyframe();
                }
                writer.write(pictures.get(index));
            }
            writer.finish();
        }
        save(OutputContainer.MP4, sink.toByteArray());
        try (var demuxer = Containers.openDemuxer(MediaSource.of(sink.toByteArray()))) {
            var track = (VideoTrack) demuxer.tracks().getFirst();
            assertEquals(Orientation.ROTATE_90, track.orientation());
            assertEquals(java.util.Optional.of("deu"), track.language());
            var keyframes = new ArrayList<Long>();
            var first = Long.MIN_VALUE;
            for (var packet = demuxer.read(); packet != null; packet = demuxer.read()) {
                if (first == Long.MIN_VALUE) {
                    first = packet.pts();
                }
                if (packet.keyframe()) {
                    keyframes.add(Math.round((track.timeBase().toMicros(packet.pts() - first)) * 24 / 1e6));
                }
            }
            assertTrue(keyframes.contains(0L) && keyframes.contains(12L), "key frames at " + keyframes);
        }
    }

    @Test
    @DisplayName("takes the size from the first picture and accepts any picture representation")
    void automaticSize() {
        var sink = MediaSink.memory();
        var rgba = RgbaPicture.allocate(64, 48, false);
        try (var writer = VideoWriter.open(sink, OutputContainer.WEBM, VideoWriterOptions.builder().codec(Codec.VP8).build())) {
            assertTrue(writer.track().isEmpty());
            for (var index = 0; index < 5; index++) {
                writer.write(rgba);
            }
            assertEquals(64, writer.track().orElseThrow().width());
            assertThrows(IllegalArgumentException.class, () -> writer.write(RgbaPicture.allocate(32, 48, false)));
            writer.finish();
        }
        try (var reader = VideoReader.open(MediaSource.of(sink.toByteArray()))) {
            var count = 0;
            while (reader.read() != null) {
                count++;
            }
            assertEquals(5, count);
            // Matroska stores the frame duration in nanoseconds
            assertEquals(30, reader.track().frameRate().orElseThrow().toDouble(), 1e-3);
        }
    }

    @Nested
    @DisplayName("rejects")
    class Rejects {
        @Test
        @DisplayName("timestamps that do not advance, bad durations and calls after finishing")
        void misuse() {
            var pictures = pictures(2);
            try (var writer = VideoWriter.open(MediaSink.memory(), OutputContainer.MATROSKA,
                    VideoWriterOptions.of(WIDTH, HEIGHT, new Rational(25, 1)).toBuilder().codec(Codec.VP8).build())) {
                writer.write(pictures.get(0), 40_000);
                assertThrows(IllegalArgumentException.class, () -> writer.write(pictures.get(1), 40_000));
                assertThrows(IllegalArgumentException.class, () -> writer.write(pictures.get(1), 10_000));
                assertThrows(IllegalArgumentException.class, () -> writer.write(pictures.get(1), -1));
                assertThrows(IllegalArgumentException.class, () -> writer.write(pictures.get(1), 80_000, 0));
                assertThrows(IllegalArgumentException.class, () -> writer.write(YuvPicture.allocate(WIDTH, 32,
                        ChromaFormat.YUV420, false, ColorInfo.BT601_LIMITED)));
                writer.write(pictures.get(1));
                assertEquals(2, writer.framesWritten());
                assertEquals(120_000, writer.durationMicros());
                writer.finish();
                assertThrows(IllegalStateException.class, writer::finish);
                assertThrows(IllegalStateException.class, () -> writer.write(pictures.get(0)));
                assertThrows(IllegalStateException.class, writer::requestKeyframe);
            }
        }

        @Test
        @DisplayName("a profile without video or orientation, a muxer writer without a codec, and finishing with no size")
        void invalidSetup() {
            assertThrows(IllegalArgumentException.class, () -> VideoWriter.open(MediaSink.memory(), OutputContainer.OGG));
            assertThrows(IllegalArgumentException.class, () -> VideoWriter.open(MediaSink.memory(), OutputContainer.WEBM,
                    VideoWriterOptions.builder().codec(Codec.H264).build()));
            assertThrows(IllegalArgumentException.class, () -> VideoWriter.open(MediaSink.memory(), OutputContainer.WEBM,
                    VideoWriterOptions.builder().orientation(Orientation.ROTATE_180).build()));
            assertThrows(IllegalArgumentException.class, () -> VideoWriter.open(
                    Containers.openMuxer(OutputContainer.MATROSKA, MediaSink.memory()), VideoWriterOptions.defaults()));
            try (var writer = VideoWriter.open(MediaSink.memory(), OutputContainer.MATROSKA)) {
                assertThrows(IllegalStateException.class, writer::finish);
            }
            assertThrows(IllegalArgumentException.class, () -> VideoWriterOptions.builder().size(0, 10));
            assertThrows(IllegalArgumentException.class, () -> VideoWriterOptions.builder().bitrate(0).build());
            assertThrows(IllegalArgumentException.class, () -> VideoWriterOptions.builder().threads(-1).build());
            assertThrows(IllegalArgumentException.class, () -> VideoWriterOptions.builder().keyframeInterval(0).build());
            assertThrows(IllegalArgumentException.class, () -> VideoWriterOptions.builder().codec(Codec.OPUS));
            // nominal frames 1/30 s apart in a 1/24 s unit would round two frames to one timestamp
            assertThrows(IllegalArgumentException.class, () -> VideoWriterOptions.builder().frameRate(new Rational(30, 1))
                    .timeBase(new Rational(1, 24)).build());
            assertThrows(IllegalArgumentException.class, () -> VideoWriterOptions.builder().timeBase(new Rational(0, 1)).build());
            assertEquals(new Rational(1, 30), VideoWriterOptions.builder().frameRate(new Rational(30, 1))
                    .timeBase(new Rational(1, 30)).build().timeBase().orElseThrow());
            assertEquals(new Rational(1001, 30_000), VideoWriterOptions.builder().frameRate(new Rational(30_000, 1001))
                    .timeBase(new Rational(1001, 30_000)).build().timeBase().orElseThrow());
        }

        @Test
        @DisplayName("options compare by value and rebuild unchanged")
        void optionsValues() {
            var options = VideoWriterOptions.builder().codec(Codec.VP9).size(640, 360).frameRate(new Rational(60, 1))
                    .bitrate(1_000_000).realtime(true).threads(2).keyframeInterval(48).orientation(Orientation.ROTATE_270)
                    .color(ColorInfo.BT709_LIMITED).language("fra").timeBase(Rational.MICROSECONDS).build();
            assertEquals(options, options.toBuilder().build());
            assertEquals(options.hashCode(), options.toBuilder().build().hashCode());
            var reset = options.toBuilder().automaticCodec().automaticSize().frameRate(VideoWriterOptions.DEFAULT_FRAME_RATE)
                    .automaticBitrate().realtime(false).threads(0).automaticKeyframeInterval()
                    .orientation(Orientation.NORMAL).noColor().noLanguage().automaticTimeBase().build();
            assertEquals(VideoWriterOptions.defaults(), reset);
            assertFalse(options.toBuilder().noPeriodicKeyframes().build().periodicKeyframes());
        }
    }

    /**
     * Returns pictures of a gradient that moves two pixels per frame over a fixed chroma pattern.
     */
    private static List<YuvPicture> pictures(int count) {
        var pictures = new ArrayList<YuvPicture>();
        for (var frame = 0; frame < count; frame++) {
            var picture = YuvPicture.allocate(WIDTH, HEIGHT, ChromaFormat.YUV420, false, ColorInfo.BT601_LIMITED);
            var y = picture.y();
            for (var row = 0; row < HEIGHT; row++) {
                for (var column = 0; column < WIDTH; column++) {
                    var value = 32 + ((column + 2 * frame) * 3 + row * 2) % 192;
                    y[row * picture.yStride() + column] = (byte) value;
                }
            }
            var u = picture.u();
            var v = picture.v();
            for (var row = 0; row < picture.chromaHeight(); row++) {
                for (var column = 0; column < picture.chromaWidth(); column++) {
                    u[row * picture.uvStride() + column] = (byte) (96 + column * 2);
                    v[row * picture.uvStride() + column] = (byte) (160 - row * 2);
                }
            }
            pictures.add(picture);
        }
        return pictures;
    }

    private static double lumaPsnr(YuvPicture expected, com.github.auties00.smalt.image.Picture actual) {
        if (!(actual instanceof YuvPicture decoded)) {
            fail("decoded " + actual.getClass().getSimpleName() + ", expected planar YUV");
            return 0;
        }
        return Quality.psnr(expected.y(), expected.yStride(), decoded.y(), decoded.yStride(), WIDTH, HEIGHT);
    }

    private static Path save(OutputContainer container, byte[] data) {
        try {
            Files.createDirectories(DIRECTORY);
            var file = DIRECTORY.resolve("writer-" + OUTPUTS.incrementAndGet() + "." + container.extension());
            Files.write(file, data);
            return file;
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
