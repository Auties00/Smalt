package com.github.auties00.smalt.video.theora;

import com.github.auties00.smalt.container.Containers;
import com.github.auties00.smalt.container.matroska.MatroskaMuxer;
import com.github.auties00.smalt.container.ogg.OggMuxer;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.internal.header.XiphLacing;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.Quality;
import com.github.auties00.smalt.testkit.Scratch;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.VideoTrack;
import com.github.auties00.smalt.video.VideoEncoder;
import com.github.auties00.smalt.video.VideoEncoderOptions;
import com.github.auties00.smalt.video.VideoEncoders;
import com.github.auties00.smalt.video.VideoFrame;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntFunction;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Encodes {@link TheoraClips} clips with inter frames and checks the decodes of {@link TheoraDecoder} and ffmpeg (whose
 * Theora decoder matches libtheora) sample for sample, through Ogg and Matroska, the size against intra-only coding,
 * and the placement of key frames.
 */
@DisplayName("Theora encoder inter frames")
class TheoraInterFrameTest {
    record Encoded(List<Packet> packets, byte[] config, List<YuvPicture> sources) {
        long bytes() {
            return packets.stream().mapToLong(packet -> packet.data().length).sum();
        }

        List<Integer> keyframes() {
            return IntStream.range(0, packets.size()).filter(i -> packets.get(i).keyframe()).boxed().toList();
        }
    }

    static Encoded encode(VideoEncoder encoder, int count, IntFunction<YuvPicture> frames) {
        var packets = new ArrayList<Packet>();
        var sources = new ArrayList<YuvPicture>();
        for (var t = 0; t < count; t++) {
            var picture = frames.apply(t);
            sources.add(picture);
            encoder.encode(new VideoFrame(picture, t * 3000L, 3000L, false), packet -> packets.add(packet.withTrackId(1)));
        }
        encoder.flush(packets::add);
        return new Encoded(packets, encoder.codecConfig().orElseThrow(), sources);
    }

    static Encoded encode(int width, int height, ChromaFormat format, TheoraEncoderOptions.Builder options, int count,
                          int cut) {
        var encoder = new TheoraEncoder(width, height, options.chromaFormat(format).build());
        return encode(encoder, count, t -> TheoraClips.frame(width, height, format, t, cut));
    }

    static VideoTrack track(int width, int height, byte[] config) {
        return VideoTrack.builder().id(1).codec(Codec.THEORA).timeBase(new Rational(1, 90_000)).width(width)
                .height(height).frameRate(new Rational(30, 1)).codecConfig(config).build();
    }

    static List<byte[]> decode(Encoded encoded, int width, int height) {
        var decoder = new TheoraDecoder(track(width, height, encoded.config()));
        var pictures = new ArrayList<byte[]>();
        for (var packet : encoded.packets()) {
            var frames = new ArrayList<VideoFrame>();
            decoder.decode(packet, frames::add);
            assertEquals(1, frames.size());
            assertEquals(packet.keyframe(), frames.getFirst().keyframe());
            pictures.add(TheoraEncoderTest.raw((YuvPicture) frames.getFirst().picture()));
        }
        return pictures;
    }

    static double meanPsnr(Encoded encoded, List<byte[]> decoded) {
        var total = 0.0;
        for (var i = 0; i < decoded.size(); i++) {
            total += Quality.psnr(TheoraEncoderTest.raw(encoded.sources().get(i)), decoded.get(i));
        }
        return total / decoded.size();
    }

    static int granuleShift(byte[] config) {
        var identification = XiphLacing.unlace(config)[0];
        return ((identification[40] & 3) << 3) | (identification[41] & 0xFF) >> 5;
    }

    static Stream<Arguments> layouts() {
        return Stream.of(
                Arguments.of(176, 112, ChromaFormat.YUV420, 48, "yuv420p", 34.0),
                Arguments.of(90, 54, ChromaFormat.YUV420, 20, "yuv420p", 25.0),
                Arguments.of(80, 48, ChromaFormat.YUV422, 40, "yuv422p", 30.0),
                Arguments.of(53, 37, ChromaFormat.YUV444, 63, "yuv444p", 38.0),
                // a quality index this coarse cannot code the detail a half-sample pan blurs away, so single frames dip
                Arguments.of(32, 24, ChromaFormat.YUV420, 0, "yuv420p", 16.0));
    }

    @ParameterizedTest(name = "{0}x{1} {2} quality {3}")
    @MethodSource("layouts")
    @DisplayName("decode the same in Smalt and ffmpeg through Ogg and Matroska, close to the source")
    void roundTrip(int width, int height, ChromaFormat format, int quality, String pixelFormat, double psnr)
            throws IOException {
        var encoded = encode(width, height, format, TheoraEncoderOptions.builder().quality(quality), 20, 14);
        assertEquals(20, encoded.packets().size());
        assertTrue(encoded.packets().getFirst().keyframe());
        assertTrue(encoded.keyframes().size() <= 3, "key frames " + encoded.keyframes());
        var decoded = decode(encoded, width, height);
        for (var i = 0; i < decoded.size(); i++) {
            var value = Quality.psnr(TheoraEncoderTest.raw(encoded.sources().get(i)), decoded.get(i));
            assertTrue(value > psnr, "frame " + i + " PSNR " + value);
        }
        var ours = new ByteArrayOutputStream();
        decoded.forEach(ours::writeBytes);
        var track = track(width, height, encoded.config());
        var ogg = MediaSink.memory();
        var oggMuxer = new OggMuxer(ogg);
        oggMuxer.addTrack(track);
        encoded.packets().forEach(oggMuxer::writePacket);
        oggMuxer.finish();
        var mkv = MediaSink.memory();
        var mkvMuxer = new MatroskaMuxer(mkv);
        mkvMuxer.addTrack(track);
        encoded.packets().forEach(mkvMuxer::writePacket);
        mkvMuxer.finish();
        for (var file : List.of(ogg.toByteArray(), mkv.toByteArray())) {
            try (var demuxer = Containers.openDemuxer(MediaSource.of(file))) {
                var index = 0;
                Packet packet;
                while ((packet = demuxer.read()) != null) {
                    var written = encoded.packets().get(index++);
                    assertArrayEquals(written.data(), packet.data(), "packet " + (index - 1));
                    assertEquals(written.keyframe(), packet.keyframe(), "key frame flag of packet " + (index - 1));
                }
                assertEquals(encoded.packets().size(), index);
            }
        }
        Oracle.assume(Oracle.Tool.FFMPEG);
        var directory = Scratch.directory("theora-inter");
        var name = width + "x" + height + "-" + format + "-" + quality;
        var oggFile = directory.resolve(name + ".ogv");
        Files.write(oggFile, ogg.toByteArray());
        assertArrayEquals(ours.toByteArray(), Oracle.decodeVideo(oggFile, pixelFormat, decoded.size()),
                "ffmpeg's decode of the Ogg file");
        var mkvFile = directory.resolve(name + ".mkv");
        Files.write(mkvFile, mkv.toByteArray());
        assertArrayEquals(ours.toByteArray(), Oracle.decodeVideo(mkvFile, pixelFormat, decoded.size()),
                "ffmpeg's decode of the Matroska file");
    }

    @ParameterizedTest(name = "{0} quality {1}")
    @CsvSource({"YUV420, 10", "YUV420, 40", "YUV420, 63", "YUV444, 10", "YUV444, 40", "YUV444, 63"})
    @DisplayName("are smaller than intra-only coding at the same quality index, at about the same quality")
    void smallerThanIntra(ChromaFormat format, int quality) {
        var inter = encode(128, 80, format, TheoraEncoderOptions.builder().quality(quality), 24, 1000);
        var intra = encode(128, 80, format, TheoraEncoderOptions.builder().quality(quality).keyframeInterval(1), 24,
                1000);
        assertEquals(List.of(0), inter.keyframes());
        assertEquals(24, intra.keyframes().size());
        var interPsnr = meanPsnr(inter, decode(inter, 128, 80));
        var intraPsnr = meanPsnr(intra, decode(intra, 128, 80));
        assertTrue(inter.bytes() * 4 < intra.bytes() * 3, inter.bytes() + " bytes against " + intra.bytes());
        assertTrue(interPsnr > intraPsnr - 1, "PSNR " + interPsnr + " against " + intraPsnr);
    }

    static YuvPicture gradient(int width, int height) {
        var picture = YuvPicture.allocate(width, height, ChromaFormat.YUV420, false,
                com.github.auties00.smalt.image.ColorInfo.BT601_LIMITED);
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                picture.y()[y * picture.yStride() + x] = (byte) (60 + x + y);
            }
        }
        java.util.Arrays.fill(picture.u(), (byte) 110);
        java.util.Arrays.fill(picture.v(), (byte) 150);
        return picture;
    }

    /** Muxes a stream into Ogg and decodes it with ffmpeg. */
    static byte[] ffmpeg(Encoded encoded, int width, int height, String name) throws IOException {
        var ogg = MediaSink.memory();
        var muxer = new OggMuxer(ogg);
        muxer.addTrack(track(width, height, encoded.config()));
        encoded.packets().forEach(muxer::writePacket);
        muxer.finish();
        var file = Scratch.directory("theora-inter").resolve(name + ".ogv");
        Files.write(file, ogg.toByteArray());
        return Oracle.decodeVideo(file, "yuv420p", encoded.packets().size());
    }

    @Test
    @DisplayName("code an unchanged smooth picture as empty packets, dropped frames that every decoder repeats")
    void unchanged() throws IOException {
        var picture = gradient(64, 48);
        var encoded = encode(new TheoraEncoder(64, 48), 10, t -> picture);
        assertEquals(List.of(0), encoded.keyframes());
        var sizes = encoded.packets().stream().mapToInt(packet -> packet.data().length).toArray();
        // the first inter frames still correct the quantization error of the key frame and the loop filter's changes
        var empty = IntStream.range(1, sizes.length).filter(i -> sizes[i] == 0).findFirst().orElseThrow(
                () -> new AssertionError("no frame codes nothing: " + java.util.Arrays.toString(sizes)));
        assertTrue(empty <= 7, java.util.Arrays.toString(sizes));
        var decoded = decode(encoded, 64, 48);
        for (var i = empty; i < decoded.size(); i++) {
            assertEquals(0, sizes[i], java.util.Arrays.toString(sizes));
            assertArrayEquals(decoded.get(empty - 1), decoded.get(i));
        }
        var track = track(64, 48, encoded.config());
        var mkv = MediaSink.memory();
        var mkvMuxer = new MatroskaMuxer(mkv);
        mkvMuxer.addTrack(track);
        encoded.packets().forEach(mkvMuxer::writePacket);
        mkvMuxer.finish();
        try (var demuxer = Containers.openDemuxer(MediaSource.of(mkv.toByteArray()))) {
            var count = 0;
            Packet packet;
            while ((packet = demuxer.read()) != null) {
                assertArrayEquals(encoded.packets().get(count++).data(), packet.data());
            }
            assertEquals(sizes.length, count);
        }
        // ffmpeg outputs no picture for an empty packet, so its decode ends with the last frame that codes a block
        var ours = new ByteArrayOutputStream();
        decoded.subList(0, empty).forEach(ours::writeBytes);
        Oracle.assume(Oracle.Tool.FFMPEG);
        assertArrayEquals(ours.toByteArray(), ffmpeg(encoded, 64, 48, "unchanged"));
    }

    @Test
    @DisplayName("code an unchanged textured picture in a small fraction of its key frame")
    void unchangedTexture() throws IOException {
        var picture = TheoraClips.frame(64, 48, ChromaFormat.YUV420, 3, 1000);
        var encoded = encode(new TheoraEncoder(64, 48), 8, t -> picture);
        assertEquals(List.of(0), encoded.keyframes());
        var key = encoded.packets().getFirst().data().length;
        var decoded = decode(encoded, 64, 48);
        var source = TheoraEncoderTest.raw(picture);
        var first = Quality.psnr(source, decoded.getFirst());
        for (var i = 3; i < decoded.size(); i++) {
            var size = encoded.packets().get(i).data().length;
            assertTrue(50 * size <= key, "frame " + i + " takes " + size + " bytes, the key frame " + key);
            assertTrue(Quality.psnr(source, decoded.get(i)) >= first, "frame " + i + " lost quality");
        }
        var ours = new ByteArrayOutputStream();
        decoded.forEach(ours::writeBytes);
        Oracle.assume(Oracle.Tool.FFMPEG);
        assertArrayEquals(ours.toByteArray(), ffmpeg(encoded, 64, 48, "unchanged-texture"));
    }

    /** Muxes a stream into Ogg and checks that ffmpeg decodes it to the pictures Smalt decodes. */
    static void assertFfmpegDecodes(Encoded encoded, List<byte[]> decoded, int width, int height, String pixelFormat,
                                    String name) throws IOException {
        var ours = new ByteArrayOutputStream();
        decoded.forEach(ours::writeBytes);
        var ogg = MediaSink.memory();
        var muxer = new OggMuxer(ogg);
        muxer.addTrack(track(width, height, encoded.config()));
        encoded.packets().forEach(muxer::writePacket);
        muxer.finish();
        Oracle.assume(Oracle.Tool.FFMPEG);
        var file = Scratch.directory("theora-inter").resolve(name + ".ogv");
        Files.write(file, ogg.toByteArray());
        assertArrayEquals(ours.toByteArray(), Oracle.decodeVideo(file, pixelFormat, decoded.size()),
                "ffmpeg's decode of " + name);
    }

    @ParameterizedTest(name = "{0} {1},{2} samples a frame")
    @CsvSource({"YUV420, 6, -3", "YUV444, -5.5, 4.5", "YUV420, 15, -15", "YUV422, 15.5, 15.5", "YUV420, -40, 25",
            "YUV444, 70, 0"})
    @DisplayName("follow a pan of any speed, vectors reaching outside the frame, and decode the same in ffmpeg")
    void pan(ChromaFormat format, double velocityX, double velocityY) throws IOException {
        var width = 96;
        var height = 64;
        var options = TheoraEncoderOptions.builder().chromaFormat(format).build();
        var encoded = encode(new TheoraEncoder(width, height, options), 12,
                t -> TheoraClips.pan(width, height, format, t, velocityX, velocityY));
        var decoded = decode(encoded, width, height);
        for (var i = 0; i < decoded.size(); i++) {
            var value = Quality.psnr(TheoraEncoderTest.raw(encoded.sources().get(i)), decoded.get(i));
            assertTrue(value > 30, "frame " + i + " PSNR " + value);
        }
        var inRange = Math.abs(velocityX) <= 15.5 && Math.abs(velocityY) <= 15.5;
        if (inRange) {
            // the search reaches every vector of these pans, so no frame falls back to a key frame
            assertEquals(List.of(0), encoded.keyframes());
            var intra = encode(new TheoraEncoder(width, height, options.toBuilder().keyframeInterval(1).build()), 12,
                    t -> TheoraClips.pan(width, height, format, t, velocityX, velocityY));
            // the samples that enter the picture every frame have no prediction
            var fresh = 1 - (1 - Math.abs(velocityX) / width) * (1 - Math.abs(velocityY) / height);
            assertTrue(encoded.bytes() < intra.bytes() * (0.75 + fresh / 2), encoded.bytes() + " bytes against "
                    + intra.bytes());
        }
        var pixelFormat = switch (format) {
            case YUV422 -> "yuv422p";
            case YUV444 -> "yuv444p";
            default -> "yuv420p";
        };
        assertFfmpegDecodes(encoded, decoded, width, height, pixelFormat,
                "pan-" + format + "-" + velocityX + "-" + velocityY);
    }

    @ParameterizedTest(name = "{0}x{1} {2}")
    @CsvSource({"1, 1, YUV420", "1, 1, YUV444", "8, 8, YUV420", "17, 9, YUV422", "16, 16, YUV444", "33, 2, YUV420", "18, 5, YUV444", "40, 17, YUV422"})
    @DisplayName("code pictures of one macro block or a few, every vector reaching outside the frame")
    void tiny(int width, int height, ChromaFormat format) throws IOException {
        var encoded = encode(new TheoraEncoder(width, height, TheoraEncoderOptions.builder().chromaFormat(format)
                .build()), 10, t -> TheoraClips.pan(width, height, format, t, 1.5, -2.5));
        assertTrue(encoded.packets().getFirst().keyframe());
        var decoded = decode(encoded, width, height);
        for (var i = 0; i < decoded.size(); i++) {
            var value = Quality.psnr(TheoraEncoderTest.raw(encoded.sources().get(i)), decoded.get(i));
            assertTrue(value > 30, "frame " + i + " PSNR " + value);
        }
        var pixelFormat = switch (format) {
            case YUV422 -> "yuv422p";
            case YUV444 -> "yuv444p";
            default -> "yuv420p";
        };
        // ffmpeg outputs no picture for an empty packet, which an unchanged tiny picture can become, and refuses
        // pictures narrower than 18 samples (found by running it: 17 fails, 33 decodes)
        if (width >= 18 && encoded.packets().stream().noneMatch(packet -> packet.data().length == 0)) {
            assertFfmpegDecodes(encoded, decoded, width, height, pixelFormat, "tiny-" + width + "x" + height + "-"
                    + format);
        }
    }

    @Nested
    @DisplayName("place key frames")
    class Keyframes {
        @Test
        @DisplayName("once for a request made before the first picture")
        void requestFirst() {
            var encoder = new TheoraEncoder(64, 48, TheoraEncoderOptions.builder().keyframeInterval(6).build());
            encoder.requestKeyframe();
            encoder.requestKeyframe();
            var encoded = encode(encoder, 13, t -> TheoraClips.frame(64, 48, ChromaFormat.YUV420, t, 1000));
            assertEquals(List.of(0, 6, 12), encoded.keyframes());
        }

        @Test
        @DisplayName("at every multiple of the key frame interval")
        void interval() {
            var encoded = encode(96, 64, ChromaFormat.YUV420, TheoraEncoderOptions.builder().keyframeInterval(5),
                    23, 1000);
            assertEquals(List.of(0, 5, 10, 15, 20), encoded.keyframes());
            assertEquals(6, granuleShift(encoded.config()));
        }

        @Test
        @DisplayName("on request, restarting the interval")
        void request() {
            var encoder = new TheoraEncoder(96, 64, TheoraEncoderOptions.builder().keyframeInterval(5).build());
            var packets = new ArrayList<Packet>();
            for (var t = 0; t < 18; t++) {
                if (t == 7) {
                    encoder.requestKeyframe();
                }
                encoder.encode(new VideoFrame(TheoraClips.frame(96, 64, ChromaFormat.YUV420, t, 1000), t * 3000L,
                        3000L, false), packets::add);
            }
            assertEquals(List.of(0, 5, 7, 12, 17), new Encoded(packets, null, List.of()).keyframes());
        }

        @Test
        @DisplayName("at a scene cut")
        void sceneCut() {
            var encoder = new TheoraEncoder(128, 80);
            var encoded = encode(encoder, 15, t -> t < 10 ? TheoraClips.frame(128, 80, ChromaFormat.YUV420, t, 1000)
                    : TheoraEncoderTest.picture(128, 80, ChromaFormat.YUV420, t));
            assertEquals(List.of(0, 10), encoded.keyframes());
        }

        @Test
        @DisplayName("on every frame with an interval of 1")
        void intraOnly() {
            var encoded = encode(48, 32, ChromaFormat.YUV420, TheoraEncoderOptions.builder().keyframeInterval(1), 4,
                    1000);
            assertEquals(List.of(0, 1, 2, 3), encoded.keyframes());
        }

        @ParameterizedTest(name = "interval {0}")
        @CsvSource({"1, 6", "2, 6", "64, 6", "65, 7", "128, 7", "129, 8", "2147483647, 31"})
        @DisplayName("declaring the granule shift that counts the interval")
        void shift(int interval, int shift) {
            var encoder = new TheoraEncoder(16, 16, TheoraEncoderOptions.builder().keyframeInterval(interval).build());
            assertEquals(shift, granuleShift(encoder.codecConfig().orElseThrow()));
        }

        @Test
        @DisplayName("declaring the longest granule shift without periodic key frames, which Ogg and ffmpeg read")
        void noPeriodic() throws IOException {
            var encoded = encode(64, 48, ChromaFormat.YUV420, TheoraEncoderOptions.builder().noPeriodicKeyframes(),
                    80, 1000);
            assertEquals(List.of(0), encoded.keyframes());
            assertEquals(31, granuleShift(encoded.config()));
            var decoded = decode(encoded, 64, 48);
            assertFfmpegDecodes(encoded, decoded, 64, 48, "yuv420p", "no-periodic");
        }

        @Test
        @DisplayName("that the Ogg granule positions of a long interval hold")
        void longInterval() {
            var encoded = encode(32, 32, ChromaFormat.YUV420, TheoraEncoderOptions.builder().keyframeInterval(100),
                    100, 1000);
            assertEquals(List.of(0), encoded.keyframes());
            assertEquals(7, granuleShift(encoded.config()));
            var muxer = new OggMuxer(MediaSink.memory());
            muxer.addTrack(track(32, 32, encoded.config()));
            assertDoesNotThrow(() -> {
                encoded.packets().forEach(muxer::writePacket);
                muxer.finish();
            });
        }

        @Test
        @DisplayName("as the codec-independent options ask")
        void registry() throws IOException {
            var periodic = VideoEncoderOptions.of(64, 48, new Rational(30, 1), 500_000).toBuilder().keyframeInterval(4)
                    .build();
            try (var encoder = VideoEncoders.create(Codec.THEORA, periodic)) {
                var encoded = encode(encoder, 10, t -> TheoraClips.frame(64, 48, ChromaFormat.YUV420, t, 1000));
                assertEquals(List.of(0, 4, 8), encoded.keyframes());
            }
            var none = VideoEncoderOptions.of(64, 48, new Rational(30, 1), 500_000).toBuilder().noPeriodicKeyframes()
                    .build();
            try (var encoder = VideoEncoders.create(Codec.THEORA, none)) {
                var encoded = encode(encoder, 70, t -> TheoraClips.frame(64, 48, ChromaFormat.YUV420, t % 8, 1000));
                assertEquals(List.of(0), encoded.keyframes());
                assertEquals(31, granuleShift(encoded.config()));
            }
            try (var encoder = VideoEncoders.create(Codec.THEORA, VideoEncoderOptions.of(64, 48, new Rational(30, 1),
                    500_000))) {
                assertEquals(6, granuleShift(encoder.codecConfig().orElseThrow()));
            }
        }
    }

    @Test
    @DisplayName("take a key frame interval of at least 1")
    void options() {
        assertEquals(java.util.OptionalInt.of(TheoraEncoderOptions.DEFAULT_KEYFRAME_INTERVAL),
                TheoraEncoderOptions.defaults().keyframeInterval());
        assertThrows(IllegalArgumentException.class, () -> TheoraEncoderOptions.builder().keyframeInterval(0).build());
        assertThrows(IllegalArgumentException.class, () -> TheoraEncoderOptions.builder().keyframeInterval(-3).build());
        var options = TheoraEncoderOptions.builder().keyframeInterval(12).build();
        assertEquals(java.util.OptionalInt.of(12), options.toBuilder().build().keyframeInterval());
        var none = options.toBuilder().noPeriodicKeyframes().build();
        assertEquals(java.util.OptionalInt.empty(), none.keyframeInterval());
        assertEquals(none, none.toBuilder().build());
        assertNotEquals(options, none);
        assertEquals(options, none.toBuilder().keyframeInterval(12).build());
        assertEquals(options, options.toBuilder().build());
        assertNotEquals(TheoraEncoderOptions.defaults(), options);
        assertTrue(options.toString().contains("keyframeInterval=OptionalInt[12]"), options.toString());
    }
}
