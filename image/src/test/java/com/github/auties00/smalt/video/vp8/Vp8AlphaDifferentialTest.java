package com.github.auties00.smalt.video.vp8;

import com.github.auties00.smalt.container.matroska.MatroskaMuxer;
import com.github.auties00.smalt.container.matroska.MatroskaMuxerOptions;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.Scratch;
import com.github.auties00.smalt.testkit.Tolerance;
import com.github.auties00.smalt.testkit.image.AlphaClips;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.PacketFlag;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.VideoTrack;
import com.github.auties00.smalt.transcode.OutputContainer;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Compares transparent VP8 WebM video with libvpx through ffmpeg in both directions: files ffmpeg writes with
 * {@code -pix_fmt yuva420p} decode in Smalt to the frames and alpha planes ffmpeg's libvpx decoder gives, and files
 * Smalt writes with an alpha stream decode in ffmpeg's libvpx decoder to the frames and alpha planes Smalt reads
 * back, with the alpha close to the source's.
 */
@DisplayName("VP8 alpha streams against libvpx")
class Vp8AlphaDifferentialTest {
    /** Both decoders reconstruct VP8 normatively, so the color and alpha planes are identical. */
    private static final Tolerance DECODE = Tolerance.exact();

    /** The alpha stream at the default quantizer reproduces the source alpha closely. */
    private static final Tolerance ALPHA = Tolerance.psnr(38);

    /** An alpha plane decoded from the alpha frame of another picture would be far below this. */
    private static final Tolerance PAIRED = Tolerance.psnr(30);

    private static Path ffmpegClip(int width, int height, int frames, List<String> options) {
        Oracle.assumeEncoder("libvpx");
        var output = new ArrayList<>(List.of("-c:v", "libvpx", "-pix_fmt", "yuva420p", "-auto-alt-ref", "0"));
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
                Arguments.of("good quality odd size", 33, 17, List.of("-deadline", "good", "-b:v", "200k", "-g", "4")),
                Arguments.of("two partitions", 96, 64, List.of("-b:v", "400k", "-slices", "2", "-g", "6")),
                Arguments.of("constant quality", 48, 48, List.of("-crf", "20", "-b:v", "1M", "-qmin", "4")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("ffmpegStreams")
    @DisplayName("decodes libvpx's alpha WebM files to the frames and alpha planes of ffmpeg's libvpx decoder")
    void decodesFfmpeg(String label, int width, int height, List<String> options) {
        var file = ffmpegClip(width, height, 12, options);
        var smalt = AlphaClips.read(file);
        assertEquals(12, smalt.size());
        DECODE.assertFrames(label, AlphaClips.ffmpegYuva(file, "libvpx"), concat(smalt),
                AlphaClips.yuvaFrameSize(width, height));
        assertFalse(smalt.stream().allMatch(frame -> frame.alpha()[width * height / 2] == (byte) 255),
                "the clip is translucent");
    }

    @Test
    @DisplayName("the core sample made by tools/testdata/behavior decodes like ffmpeg's libvpx decoder")
    void coreSample() {
        var file = Path.of("..", "core", "src", "test", "resources", "samples", "matroska", "vp8-alpha.webm");
        DECODE.assertFrames("vp8-alpha.webm", AlphaClips.ffmpegYuva(file, "libvpx"), concat(AlphaClips.read(file)),
                AlphaClips.yuvaFrameSize(64, 48));
    }

    private static Path write(String name, int width, int height, int frames, VideoWriterOptions.Builder options) {
        var file = Scratch.directory("media-fixtures/vp8-alpha").resolve(name + ".webm");
        try (var sink = MediaSink.of(file);
             var writer = VideoWriter.open(sink, OutputContainer.WEBM, options.codec(Codec.VP8).alpha(true)
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
        DECODE.assertFrames(label, AlphaClips.ffmpegYuva(file, "libvpx"), concat(smalt),
                AlphaClips.yuvaFrameSize(width, height));
        for (var index = 0; index < frames; index++) {
            ALPHA.assertWithin(label + " frame " + index, AlphaClips.alpha(AlphaClips.frame(index, width, height)),
                    smalt.get(index).alpha());
        }
    }

    @Nested
    @DisplayName("Smalt-written files")
    class Written {
        @ParameterizedTest(name = "{0}")
        @ValueSource(booleans = {false, true})
        @DisplayName("decode in ffmpeg's libvpx decoder to Smalt's frames, with the source alpha, real time or not")
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
        @DisplayName("hidden alternate references carry no alpha frame and the shown frames still pair with theirs")
        void alternateReferences() {
            var width = 64;
            var height = 48;
            var options = Vp8EncoderOptions.ofBitrate(300_000).toBuilder().speed(Vp8EncoderSpeed.SPEED_4)
                    .frameRate(new Rational(10, 1)).alpha(true).alphaQuality(8).build();
            var packets = new ArrayList<Packet>();
            try (var encoder = new Vp8Encoder(width, height, options)) {
                for (var index = 0; index < 30; index++) {
                    encoder.encode(new VideoFrame(AlphaClips.frame(index, width, height), index * 9000L, 9000, false),
                            packets::add);
                }
                encoder.flush(packets::add);
            }
            var hidden = packets.stream().filter(packet -> packet.flags().contains(PacketFlag.DISCARD)).toList();
            assertFalse(hidden.isEmpty(), "the good-quality usage codes alternate references");
            assertTrue(hidden.stream().allMatch(packet -> packet.sideData().alphaFrame().isEmpty()));
            var shown = packets.stream().filter(packet -> !packet.flags().contains(PacketFlag.DISCARD)).toList();
            assertEquals(java.util.stream.LongStream.range(0, 30).map(index -> index * 9000).boxed().toList(),
                    shown.stream().map(Packet::pts).toList(), packets.stream().map(packet -> packet.pts() + ":"
                            + packet.flags() + ":" + packet.size()).toList().toString());
            assertTrue(shown.stream().allMatch(packet -> packet.sideData().alphaFrame().isPresent()));
            var track = VideoTrack.builder().id(0).codec(Codec.VP8).timeBase(options.timeBase()).width(width)
                    .height(height).frameRate(new Rational(10, 1)).alpha(true).build();
            var file = Scratch.directory("vp8-alpha").resolve("alternate-references.webm");
            try (var sink = MediaSink.of(file); var muxer = new MatroskaMuxer(sink, MatroskaMuxerOptions.webm())) {
                muxer.addTrack(track);
                packets.forEach(muxer::writePacket);
                muxer.finish();
            }
            assertMatchesFfmpeg("alternate references", file, width, height, 30);
        }

        static Stream<Arguments> mainStreams() {
            var rate = new Rational(10, 1);
            return Stream.of(
                    Arguments.of("real time", Vp8EncoderOptions.forRealtime(rate).toBuilder().bitrate(300_000).build()),
                    Arguments.of("real time dropping", Vp8EncoderOptions.forRealtime(rate).toBuilder().bitrate(6_000)
                            .build()),
                    Arguments.of("good quality", Vp8EncoderOptions.ofBitrate(300_000).toBuilder()
                            .speed(Vp8EncoderSpeed.SPEED_4).frameRate(rate).build()),
                    Arguments.of("constant quality", Vp8EncoderOptions.ofQuality(20).toBuilder().frameRate(rate)
                            .build()));
        }

        private static List<Packet> encode(Vp8EncoderOptions options, int frames) {
            var packets = new ArrayList<Packet>();
            try (var encoder = new Vp8Encoder(64, 48, options)) {
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
        void mainStreamUnchanged(String label, Vp8EncoderOptions options) {
            var opaque = encode(options, 30);
            var transparent = encode(options.toBuilder().alpha(true).build(), 30);
            assertEquals(opaque.size(), transparent.size(), label);
            for (var index = 0; index < opaque.size(); index++) {
                var expected = opaque.get(index);
                var actual = transparent.get(index);
                assertArrayEquals(expected.data(), actual.data(), label + " packet " + index);
                assertEquals(expected.pts(), actual.pts(), label + " packet " + index);
                assertEquals(expected.flags(), actual.flags(), label + " packet " + index);
                assertTrue(expected.sideData().alphaFrame().isEmpty());
                assertEquals(!actual.flags().contains(PacketFlag.DISCARD), actual.sideData().alphaFrame().isPresent(),
                        label + " packet " + index);
            }
        }

        @Test
        @DisplayName("frames the real-time rate control drops take their alpha frames with them")
        void droppedFrames() {
            var width = 128;
            var height = 96;
            var options = Vp8EncoderOptions.forRealtime(new Rational(10, 1)).toBuilder().bitrate(4_000).alpha(true)
                    .build();
            var shown = new ArrayList<Packet>();
            try (var encoder = new Vp8Encoder(width, height, options)) {
                for (var index = 0; index < 40; index++) {
                    encoder.encodeFrame(new VideoFrame(AlphaClips.frame(index * 3, width, height), index * 9000L, 9000,
                            false)).ifPresent(shown::add);
                }
                assertTrue(encoder.stats().framesDropped() > 0, "the rate control drops frames at 8 kbit/s");
            }
            assertTrue(shown.stream().allMatch(packet -> packet.sideData().alphaFrame().isPresent()));
            try (var decoder = new Vp8Decoder(VideoTrack.builder().id(0).codec(Codec.VP8).timeBase(options.timeBase())
                    .width(width).height(height).alpha(true).build());
                 var alphaDecoder = com.github.auties00.smalt.video.VideoDecoders.create(VideoTrack.builder().id(0)
                         .codec(Codec.VP8).timeBase(options.timeBase()).width(width).height(height).alpha(true).build())) {
                for (var packet : shown) {
                    var index = (int) (packet.pts() / 9000);
                    var got = new ArrayList<byte[]>();
                    alphaDecoder.decode(packet, frame -> got.add(AlphaClips.alpha(frame.picture())));
                    assertEquals(1, got.size());
                    var expected = AlphaClips.alpha(AlphaClips.frame(index * 3, width, height));
                    PAIRED.assertWithin("frame " + index + " pairs with its alpha", expected, got.getFirst());
                    decoder.decode(packet, frame -> assertFalse(frame.picture().hasAlpha()));
                }
            }
        }

        @Test
        @DisplayName("the registry encodes alpha for VP8 and the provider maps the option")
        void registry() {
            assertTrue(VideoEncoders.encodesAlpha(Codec.VP8));
            var options = com.github.auties00.smalt.video.VideoEncoderOptions.of(32, 32, new Rational(10, 1), 200_000)
                    .toBuilder().alpha(true).build();
            try (var encoder = VideoEncoders.create(Codec.VP8, options)) {
                var vp8 = (Vp8Encoder) encoder;
                assertTrue(vp8.options().alpha());
                assertEquals(10, vp8.options().alphaQuality());
            }
        }

        @Test
        @DisplayName("a running encoder cannot change its alpha stream")
        void reconfigure() {
            try (var encoder = new Vp8Encoder(32, 32, Vp8EncoderOptions.forRealtime().toBuilder().alpha(true).build())) {
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
        assertThrows(IllegalArgumentException.class, () -> Vp8EncoderOptions.builder().alphaQuality(64).build());
        assertThrows(IllegalArgumentException.class, () -> Vp8EncoderOptions.builder().alphaQuality(-1).build());
        var finest = Vp8EncoderOptions.builder().alpha(true).alphaQuality(0).build();
        assertEquals(0, finest.alphaQuality());
        assertEquals(finest, finest.toBuilder().build());
        assertFalse(finest.equals(finest.toBuilder().alphaQuality(1).build()));
        assertFalse(finest.equals(finest.toBuilder().alpha(false).build()));
        assertFalse(Vp8EncoderOptions.defaults().alpha());
        assertEquals(10, Vp8EncoderOptions.defaults().alphaQuality());
    }
}
