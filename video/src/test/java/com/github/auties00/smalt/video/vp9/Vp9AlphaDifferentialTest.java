package com.github.auties00.smalt.video.vp9;

import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.Scratch;
import com.github.auties00.smalt.testkit.Tolerance;
import com.github.auties00.smalt.testkit.image.AlphaClips;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.VideoTrack;
import com.github.auties00.smalt.transcode.OutputContainer;
import com.github.auties00.smalt.video.VideoDecoderOptions;
import com.github.auties00.smalt.video.VideoDecoders;
import com.github.auties00.smalt.video.VideoEncoderOptions;
import com.github.auties00.smalt.video.VideoEncoders;
import com.github.auties00.smalt.video.VideoFrame;
import com.github.auties00.smalt.video.VideoReader;
import com.github.auties00.smalt.video.VideoWriter;
import com.github.auties00.smalt.video.VideoWriterOptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Compares transparent VP9 WebM video with libvpx through ffmpeg in both directions: files ffmpeg writes with
 * {@code -pix_fmt yuva420p} decode in Smalt to the frames and alpha planes ffmpeg's libvpx-vp9 decoder gives, and
 * files Smalt writes with an alpha stream decode in ffmpeg's libvpx-vp9 decoder to the frames and alpha planes Smalt
 * reads back, with the alpha close to the source's.
 */
@DisplayName("VP9 alpha streams against libvpx")
class Vp9AlphaDifferentialTest {
    /** Both decoders reconstruct VP9 normatively, so the color and alpha planes are identical. */
    private static final Tolerance DECODE = Tolerance.exact();

    /** The alpha stream at the default quantizer reproduces the source alpha closely. */
    private static final Tolerance ALPHA = Tolerance.psnr(38);

    /** An alpha plane decoded from the alpha frame of another picture would be far below this. */
    private static final Tolerance PAIRED = Tolerance.psnr(30);

    private static Path ffmpegClip(int width, int height, int frames, List<String> options) {
        Oracle.assumeEncoder("libvpx-vp9");
        var output = new ArrayList<>(List.of("-c:v", "libvpx-vp9", "-pix_fmt", "yuva420p", "-threads", "1"));
        output.addAll(options);
        return Fixtures.ffmpeg("webm", List.of("-f", "lavfi", "-i", AlphaClips.FFMPEG_SOURCE.formatted(width, height,
                frames)), output);
    }

    private static byte[] concat(List<AlphaClips.Decoded> frames) {
        var out = new ByteArrayOutputStream();
        frames.forEach(frame -> out.writeBytes(frame.yuva()));
        return out.toByteArray();
    }

    static Stream<Arguments> ffmpegStreams() {
        return Stream.of(
                Arguments.of("real time 64x48", 64, 48, List.of("-deadline", "realtime", "-cpu-used", "8", "-b:v", "300k")),
                Arguments.of("good quality with alternate references", 66, 50,
                        List.of("-deadline", "good", "-cpu-used", "4", "-b:v", "200k", "-g", "8", "-auto-alt-ref", "1",
                                "-lag-in-frames", "12")),
                Arguments.of("odd size", 31, 19, List.of("-deadline", "good", "-cpu-used", "5", "-crf", "30", "-b:v", "0")),
                Arguments.of("lossless", 32, 32, List.of("-lossless", "1")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("ffmpegStreams")
    @DisplayName("decodes libvpx's alpha WebM files to the frames and alpha planes of ffmpeg's libvpx-vp9 decoder")
    void decodesFfmpeg(String label, int width, int height, List<String> options) {
        var file = ffmpegClip(width, height, 16, options);
        var smalt = AlphaClips.read(file);
        assertEquals(16, smalt.size());
        DECODE.assertFrames(label, AlphaClips.ffmpegYuva(file, "libvpx-vp9"), concat(smalt),
                AlphaClips.yuvaFrameSize(width, height));
        assertFalse(smalt.stream().allMatch(frame -> frame.alpha()[width * height / 2] == (byte) 255),
                "the clip is translucent");
    }

    @Test
    @DisplayName("the core sample made by tools/testdata/behavior decodes like ffmpeg's libvpx-vp9 decoder")
    void coreSample() {
        var file = Path.of("..", "core", "src", "test", "resources", "samples", "matroska", "vp9-alpha.webm");
        DECODE.assertFrames("vp9-alpha.webm", AlphaClips.ffmpegYuva(file, "libvpx-vp9"), concat(AlphaClips.read(file)),
                AlphaClips.yuvaFrameSize(64, 48));
    }

    private static Path write(String name, int width, int height, int frames, VideoWriterOptions.Builder options) {
        var file = Scratch.directory("media-fixtures/vp9-alpha").resolve(name + ".webm");
        try (var sink = MediaSink.of(file);
             var writer = VideoWriter.open(sink, OutputContainer.WEBM, options.codec(Codec.VP9).alpha(true)
                     .frameRate(new Rational(10, 1)).build())) {
            for (var index = 0; index < frames; index++) {
                writer.write(AlphaClips.frame(index, width, height));
            }
            writer.finish();
        }
        return file;
    }

    private static void assertMatchesFfmpeg(String label, Path file, int width, int height, int frames) {
        Oracle.assertDecodesCleanly(file);
        var smalt = AlphaClips.read(file);
        assertEquals(frames, smalt.size(), label);
        DECODE.assertFrames(label, AlphaClips.ffmpegYuva(file, "libvpx-vp9"), concat(smalt),
                AlphaClips.yuvaFrameSize(width, height));
        for (var index = 0; index < frames; index++) {
            ALPHA.assertWithin(label + " frame " + index, AlphaClips.alpha(AlphaClips.frame(index, width, height)),
                    smalt.get(index).alpha());
        }
    }

    @Nested
    @DisplayName("Smalt-written files")
    class Written {
        @ParameterizedTest(name = "realtime={0}")
        @ValueSource(booleans = {false, true})
        @DisplayName("decode in ffmpeg's libvpx-vp9 decoder to Smalt's frames, with the source alpha, real time or not")
        void writer(boolean realtime) {
            var file = write("writer-" + realtime, 70, 42, 24, VideoWriterOptions.builder().realtime(realtime)
                    .bitrate(1_000_000).keyframeInterval(8));
            try (var source = MediaSource.of(file); var reader = VideoReader.open(source)) {
                assertTrue(reader.track().hasAlpha());
            }
            assertMatchesFfmpeg("writer realtime=" + realtime, file, 70, 42, 24);
        }

        @Test
        @DisplayName("seeking decodes from a key frame of both streams to the frames of a linear decode")
        void seek() {
            var file = write("seek", 48, 32, 30, VideoWriterOptions.builder().keyframeInterval(5));
            var linear = AlphaClips.read(file);
            try (var source = MediaSource.of(file); var reader = VideoReader.open(source)) {
                for (var target : new long[]{2_000_000, 1_250_000, 600_000}) {
                    reader.seek(target);
                    var frame = reader.read();
                    var expected = linear.stream().filter(decoded -> decoded.pts() == frame.pts()).findFirst()
                            .orElseThrow();
                    assertArrayEquals(expected.alpha(), AlphaClips.alpha(frame.picture()), "seek to " + target);
                }
            }
        }

        @Test
        @DisplayName("a 10-bit stream carries an 8-bit alpha stream, rescaled to 10 bits by a high bit depth decode")
        void highBitDepth() {
            var width = 40;
            var height = 24;
            var options = Vp9EncoderOptions.ofQuality(20).toBuilder().speed(Vp9EncoderSpeed.SPEED_4)
                    .pictureFormat(Vp9Subsampling.YUV420, 10).frameRate(new Rational(10, 1)).alpha(true).build();
            var track = VideoTrack.builder().id(1).codec(Codec.VP9).timeBase(options.timeBase()).width(width)
                    .height(height).alpha(true).build();
            var packets = new ArrayList<Packet>();
            try (var encoder = new Vp9Encoder(width, height, options)) {
                for (var index = 0; index < 8; index++) {
                    encoder.encode(new VideoFrame(AlphaClips.frame(index, width, height), index * 9000L, 9000, false),
                            packet -> packets.add(packet.withTrackId(1)));
                }
                encoder.flush(packet -> packets.add(packet.withTrackId(1)));
            }
            assertEquals(8, packets.size());
            var narrow = new ArrayList<byte[]>();
            try (var decoder = VideoDecoders.create(track)) {
                packets.forEach(packet -> decoder.decode(packet, frame -> narrow.add(AlphaClips.alpha(frame.picture()))));
            }
            var deep = new ArrayList<byte[]>();
            try (var decoder = VideoDecoders.create(track, VideoDecoderOptions.builder().highBitDepth(true).build())) {
                packets.forEach(packet -> decoder.decode(packet, frame -> {
                    var picture = assertInstanceOf(Yuv16Picture.class, frame.picture());
                    assertEquals(10, picture.bitDepth());
                    deep.add(AlphaClips.alpha(picture));
                }));
            }
            assertEquals(8, deep.size());
            for (var index = 0; index < 8; index++) {
                assertArrayEquals(narrow.get(index), deep.get(index), "frame " + index);
                ALPHA.assertWithin("frame " + index, AlphaClips.alpha(AlphaClips.frame(index, width, height)),
                        narrow.get(index));
            }
        }

        static Stream<Arguments> mainStreams() {
            var rate = new Rational(10, 1);
            return Stream.of(
                    Arguments.of("real time", Vp9EncoderOptions.forRealtime().toBuilder().frameRate(rate)
                            .bitrate(300_000).build()),
                    Arguments.of("good quality", Vp9EncoderOptions.ofBitrate(300_000).toBuilder()
                            .speed(Vp9EncoderSpeed.SPEED_4).frameRate(rate).build()),
                    Arguments.of("10-bit constant quality", Vp9EncoderOptions.ofQuality(24).toBuilder()
                            .speed(Vp9EncoderSpeed.SPEED_4).pictureFormat(Vp9Subsampling.YUV420, 10).frameRate(rate)
                            .build()));
        }

        private static List<Packet> encode(Vp9EncoderOptions options, int frames) {
            var packets = new ArrayList<Packet>();
            try (var encoder = new Vp9Encoder(64, 48, options)) {
                for (var index = 0; index < frames; index++) {
                    encoder.encode(new VideoFrame(AlphaClips.frame(index, 64, 48), index * 9000L, 9000, false),
                            packets::add);
                }
                encoder.flush(packets::add);
            }
            return packets;
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("mainStreams")
        @DisplayName("the alpha stream leaves the main stream byte for byte as it is without alpha")
        void mainStreamUnchanged(String label, Vp9EncoderOptions options) {
            var opaque = encode(options, 24);
            var transparent = encode(options.toBuilder().alpha(true).build(), 24);
            assertEquals(opaque.size(), transparent.size(), label);
            for (var index = 0; index < opaque.size(); index++) {
                assertArrayEquals(opaque.get(index).data(), transparent.get(index).data(), label + " packet " + index);
                assertEquals(opaque.get(index).pts(), transparent.get(index).pts(), label + " packet " + index);
                assertTrue(transparent.get(index).sideData().alphaFrame().isPresent(), label + " packet " + index);
            }
        }

        @Test
        @DisplayName("frames the real-time rate control drops take their alpha frames with them")
        void droppedFrames() {
            var width = 64;
            var height = 48;
            var options = Vp9EncoderOptions.forRealtime().toBuilder().frameRate(new Rational(10, 1)).bitrate(8_000)
                    .dropFrames(true).alpha(true).build();
            var track = VideoTrack.builder().id(0).codec(Codec.VP9).timeBase(options.timeBase()).width(width)
                    .height(height).alpha(true).build();
            var shown = new ArrayList<Packet>();
            try (var encoder = new Vp9Encoder(width, height, options)) {
                for (var index = 0; index < 40; index++) {
                    encoder.encodeFrame(new VideoFrame(AlphaClips.frame(index * 3, width, height), index * 9000L, 9000,
                            false)).ifPresent(shown::add);
                }
                assertTrue(encoder.stats().framesDropped() > 0, "the rate control drops frames at 8 kbit/s");
            }
            assertTrue(shown.stream().allMatch(packet -> packet.sideData().alphaFrame().isPresent()));
            try (var decoder = VideoDecoders.create(track)) {
                for (var packet : shown) {
                    var index = (int) (packet.pts() / 9000);
                    var got = new ArrayList<byte[]>();
                    decoder.decode(packet, frame -> got.add(AlphaClips.alpha(frame.picture())));
                    assertEquals(1, got.size());
                    var expected = AlphaClips.alpha(AlphaClips.frame(index * 3, width, height));
                    PAIRED.assertWithin("frame " + index + " pairs with its alpha", expected, got.getFirst());
                }
            }
        }

        @Test
        @DisplayName("the registry encodes alpha for VP9 and the provider maps the option")
        void registry() {
            assertTrue(VideoEncoders.encodesAlpha(Codec.VP9));
            var options = VideoEncoderOptions.of(32, 32, new Rational(10, 1), 200_000).toBuilder().alpha(true).build();
            try (var encoder = VideoEncoders.create(Codec.VP9, options)) {
                var vp9 = (Vp9Encoder) encoder;
                assertTrue(vp9.options().alpha());
                assertEquals(10, vp9.options().alphaQuality());
            }
        }

        @Test
        @DisplayName("a running encoder cannot change its alpha stream")
        void reconfigure() {
            try (var encoder = new Vp9Encoder(32, 32, Vp9EncoderOptions.forRealtime().toBuilder().alpha(true).build())) {
                assertThrows(IllegalArgumentException.class, () -> encoder.reconfigure(
                        encoder.options().toBuilder().alpha(false).build()));
                assertThrows(IllegalArgumentException.class, () -> encoder.reconfigure(
                        encoder.options().toBuilder().alphaQuality(30).build()));
                encoder.setTargetBitrate(500_000);
                assertTrue(encoder.options().alpha());
            }
        }
    }

    @Test
    @DisplayName("rejects an alpha quantizer outside 0 to 63, and options compare by their alpha settings")
    void alphaOptions() {
        assertThrows(IllegalArgumentException.class, () -> Vp9EncoderOptions.builder().alphaQuality(64).build());
        assertThrows(IllegalArgumentException.class, () -> Vp9EncoderOptions.builder().alphaQuality(-1).build());
        var finest = Vp9EncoderOptions.builder().alpha(true).alphaQuality(0).build();
        assertEquals(0, finest.alphaQuality());
        assertEquals(finest, finest.toBuilder().build());
        assertFalse(finest.equals(finest.toBuilder().alphaQuality(1).build()));
        assertFalse(finest.equals(finest.toBuilder().alpha(false).build()));
        assertFalse(Vp9EncoderOptions.defaults().alpha());
        assertEquals(10, Vp9EncoderOptions.defaults().alphaQuality());
    }
}
