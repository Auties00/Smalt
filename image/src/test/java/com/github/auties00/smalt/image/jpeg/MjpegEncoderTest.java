package com.github.auties00.smalt.image.jpeg;

import com.github.auties00.smalt.container.avi.AviMuxer;
import com.github.auties00.smalt.container.matroska.MatroskaMuxer;
import com.github.auties00.smalt.container.matroska.MatroskaMuxerOptions;
import com.github.auties00.smalt.container.mp4.Mp4Muxer;
import com.github.auties00.smalt.container.mp4.Mp4MuxerOptions;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.Rgba16Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.Quality;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Interlacing;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.VideoTrack;
import com.github.auties00.smalt.transcode.OutputContainer;
import com.github.auties00.smalt.transcode.TranscodeOptions;
import com.github.auties00.smalt.transcode.Transcoder;
import com.github.auties00.smalt.transcode.VideoTranscodeOptions;
import com.github.auties00.smalt.video.FieldOrder;
import com.github.auties00.smalt.video.VideoDecoders;
import com.github.auties00.smalt.video.VideoEncoderOptions;
import com.github.auties00.smalt.video.VideoEncoders;
import com.github.auties00.smalt.video.VideoFrame;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers {@link MjpegEncoder}: progressive frames are the files {@link JpegEncoder} writes, the AVI convention
 * leaves their DHT segments out, interlaced frames hold one image per field, the rate control approaches its
 * target, the registry serves the encoder, and AVI, QuickTime, MP4 and Matroska files of its packets demux to the
 * same packets and decode in ffmpeg to the frames Smalt decodes.
 */
@DisplayName("MjpegEncoder")
class MjpegEncoderTest {
    private static List<Packet> encode(MjpegEncoder encoder, List<? extends Picture> pictures) {
        var packets = new ArrayList<Packet>();
        for (var picture : pictures) {
            encoder.encode(new VideoFrame(picture, Packet.NO_TIMESTAMP, Packet.UNKNOWN_DURATION, true), packets::add);
        }
        encoder.flush(packets::add);
        return packets;
    }

    private static List<Picture> patterns(int width, int height, int count) {
        var pictures = new ArrayList<Picture>();
        for (var frame = 0; frame < count; frame++) {
            pictures.add(MotionJpegTesting.pattern(width, height, frame));
        }
        return pictures;
    }

    static Stream<Arguments> settings() {
        var arguments = Stream.<Arguments>builder();
        for (var format : ChromaFormat.values()) {
            arguments.add(Arguments.of(format, 75, true, 0));
            arguments.add(Arguments.of(format, 30, false, 0));
            arguments.add(Arguments.of(format, 95, true, 3));
        }
        arguments.add(Arguments.of(ChromaFormat.YUV420, 0, false, 1));
        arguments.add(Arguments.of(ChromaFormat.YUV422, 100, true, 0));
        return arguments.build();
    }

    @ParameterizedTest(name = "{0}, quality {1}, optimized {2}, restart {3}")
    @MethodSource("settings")
    @DisplayName("codes every progressive frame as the file JpegEncoder writes")
    void progressive(ChromaFormat format, int quality, boolean optimize, int restart) {
        var options = MjpegEncoderOptions.builder().quality(quality).chromaFormat(format).optimizeHuffman(optimize)
                .restartInterval(restart).build();
        var jpegOptions = JpegEncoderOptions.builder().quality(quality).chromaFormat(format)
                .optimizeHuffman(optimize).restartInterval(restart).build();
        var pictures = new ArrayList<Picture>(patterns(45, 31, 2));
        pictures.add(MotionJpegTesting.yuvPattern(45, 31, format, 3));
        pictures.add(ImageOps.toYuv16(pictures.getFirst(), ChromaFormat.YUV444, ColorInfo.BT709_LIMITED, 10));
        var packets = encode(new MjpegEncoder(45, 31, options), pictures);
        assertEquals(pictures.size(), packets.size());
        for (var i = 0; i < pictures.size(); i++) {
            assertArrayEquals(JpegEncoder.encode(pictures.get(i), jpegOptions), packets.get(i).data(), "frame " + i);
            assertTrue(packets.get(i).keyframe());
        }
    }

    @Test
    @DisplayName("leaves the DHT segments out in the AVI convention, and the frames decode with the Annex K tables")
    void avi() {
        var options = MjpegEncoderOptions.forAvi();
        assertEquals(false, options.optimizeHuffman());
        assertEquals(false, options.huffmanTables());
        var pictures = patterns(64, 48, 3);
        var packets = encode(new MjpegEncoder(64, 48, options), pictures);
        var standard = JpegEncoderOptions.builder().optimizeHuffman(false).build();
        var decoder = new MjpegDecoder();
        for (var i = 0; i < pictures.size(); i++) {
            var full = JpegEncoder.encode(pictures.get(i), standard);
            var data = packets.get(i).data();
            assertArrayEquals(MotionJpegTesting.stripDht(full), data);
            assertEquals(-1, MotionJpegTesting.find(data, 0xC4));
            assertArrayEquals(MotionJpegTesting.planes(JpegDecoder.decode(MediaSource.of(full)).picture()),
                    MotionJpegTesting.planes(decoder.decodeFrame(data, 0, data.length)));
        }
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(ints = {0, 1, 2, 3})
    @DisplayName("produces the same frames whatever the thread count")
    void threads(int threads) {
        var pictures = patterns(320, 240, 2);
        var reference = encode(new MjpegEncoder(320, 240, MjpegEncoderOptions.builder().threads(1).build()), pictures);
        var packets = encode(new MjpegEncoder(320, 240, MjpegEncoderOptions.builder().threads(threads).build()),
                pictures);
        for (var i = 0; i < pictures.size(); i++) {
            assertArrayEquals(reference.get(i).data(), packets.get(i).data());
        }
    }

    @Nested
    @DisplayName("interlaced frames")
    class Interlaced {
        static Stream<Arguments> cases() {
            var arguments = Stream.<Arguments>builder();
            for (var order : new FieldOrder[]{FieldOrder.TOP_FIRST, FieldOrder.BOTTOM_FIRST}) {
                for (var format : ChromaFormat.values()) {
                    for (var height : new int[]{48, 30, 7, 2}) {
                        arguments.add(Arguments.of(order, format, height));
                    }
                }
            }
            return arguments.build();
        }

        @ParameterizedTest(name = "{0}, {1}, height {2}")
        @MethodSource("cases")
        @DisplayName("code one image per field, each the file of its field with an AVI1 segment, and decode woven")
        void fields(FieldOrder order, ChromaFormat format, int height) {
            var width = 40;
            var source = MotionJpegTesting.yuvPattern(width, height, format, 1);
            var options = MjpegEncoderOptions.builder().fieldOrder(order).chromaFormat(format).quality(90).build();
            var packet = encode(new MjpegEncoder(width, height, options), List.of(source)).getFirst();
            var top = MotionJpegTesting.field(source, false);
            var bottom = MotionJpegTesting.field(source, true);
            var jpegOptions = JpegEncoderOptions.builder().quality(90).chromaFormat(format).build();
            var topFirst = order == FieldOrder.TOP_FIRST;
            var first = JpegEncoder.encode(topFirst ? top : bottom, jpegOptions);
            var second = JpegEncoder.encode(topFirst ? bottom : top, jpegOptions);
            var expected = MotionJpegTesting.concat(MotionJpegTesting.withAvi1(first, 1, first.length),
                    MotionJpegTesting.withAvi1(second, 2, second.length));
            assertArrayEquals(expected, packet.data());
            var scan = topFirst ? Interlacing.TOP_FIELD_FIRST : Interlacing.BOTTOM_FIELD_FIRST;
            var track = VideoTrack.builder().id(1).codec(Codec.MJPEG).timeBase(new Rational(1, 90000)).width(width)
                    .height(height).interlacing(scan).build();
            var decoded = new ArrayList<Picture>();
            new MjpegDecoder(track).decode(packet, frame -> decoded.add(frame.picture()));
            var stillTop = (YuvPicture) JpegDecoder.decode(MediaSource.of(topFirst ? first : second)).picture();
            var stillBottom = (YuvPicture) JpegDecoder.decode(MediaSource.of(topFirst ? second : first)).picture();
            var woven = assertInstanceOf(YuvPicture.class, decoded.getFirst());
            assertEquals(height, woven.height());
            assertArrayEquals(MotionJpegTesting.planes(MotionJpegTesting.weave(stillTop, stillBottom, height)),
                    MotionJpegTesting.planes(woven));
            if (height >= 30) {
                var psnr = Quality.psnr(source, woven);
                assertTrue(psnr > 30, "PSNR " + psnr);
            }
        }

        // The field of a picture of any representation, rows (and chroma rows) taken as MotionJpegTesting.field takes them
        private static Picture fieldOf(Picture picture, boolean bottom) {
            var rows = bottom ? picture.height() / 2 : (picture.height() + 1) / 2;
            var parity = bottom ? 1 : 0;
            return switch (picture) {
                case YuvPicture yuv -> MotionJpegTesting.field(yuv, bottom);
                case RgbaPicture rgba -> {
                    var field = RgbaPicture.allocate(rgba.width(), rows, rgba.hasAlpha());
                    for (var y = 0; y < rows; y++) {
                        System.arraycopy(rgba.pixels(), (2 * y + parity) * rgba.stride(), field.pixels(),
                                y * field.stride(), 4 * rgba.width());
                    }
                    yield field;
                }
                case Rgba16Picture rgba -> {
                    var field = Rgba16Picture.allocate(rgba.width(), rows, rgba.hasAlpha());
                    for (var y = 0; y < rows; y++) {
                        System.arraycopy(rgba.pixels(), (2 * y + parity) * rgba.stride(), field.pixels(),
                                y * field.stride(), 4 * rgba.width());
                    }
                    yield field;
                }
                case Yuv16Picture yuv -> {
                    var format = yuv.chromaFormat();
                    var field = Yuv16Picture.allocate(yuv.width(), rows, format, yuv.bitDepth(), false, yuv.color());
                    for (var y = 0; y < rows; y++) {
                        System.arraycopy(yuv.y(), (2 * y + parity) * yuv.yStride(), field.y(), y * field.yStride(),
                                yuv.width());
                    }
                    var chromaRows = yuv.chromaHeight();
                    var last = bottom ? (chromaRows >= 2 ? (chromaRows / 2) * 2 - 1 : 0) : chromaRows - 1;
                    for (var y = 0; y < field.chromaHeight(); y++) {
                        var source = Math.min(2 * y + parity, last) * yuv.uvStride();
                        System.arraycopy(yuv.u(), source, field.u(), y * field.uvStride(), field.chromaWidth());
                        System.arraycopy(yuv.v(), source, field.v(), y * field.uvStride(), field.chromaWidth());
                    }
                    yield field;
                }
                default -> throw new IllegalArgumentException(picture.getClass().getName());
            };
        }

        static Stream<Arguments> pictureKinds() {
            var rgba = MotionJpegTesting.pattern(36, 22, 4);
            var odd = MotionJpegTesting.pattern(36, 21, 5);
            return Stream.of(
                    Arguments.of("RGBA", rgba),
                    Arguments.of("RGBA, odd height", odd),
                    Arguments.of("RGBA16", ImageOps.toRgba16(rgba)),
                    Arguments.of("Yuv16 4:2:0", ImageOps.toYuv16(odd, ChromaFormat.YUV420, ColorInfo.BT709_LIMITED,
                            10)),
                    Arguments.of("Yuv16 4:4:4", ImageOps.toYuv16(rgba, ChromaFormat.YUV444, ColorInfo.BT709_LIMITED,
                            12)));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("pictureKinds")
        @DisplayName("code the fields of pictures of every representation as the files of those fields")
        void representations(String name, Picture picture) {
            for (var order : new FieldOrder[]{FieldOrder.TOP_FIRST, FieldOrder.BOTTOM_FIRST}) {
                var options = MjpegEncoderOptions.builder().fieldOrder(order).quality(85).build();
                var encoder = new MjpegEncoder(picture.width(), picture.height(), options);
                // twice, so that the second frame reuses the arrays of the first one's bottom field
                for (var frame = 0; frame < 2; frame++) {
                    var packet = encoder.encodeFrame(new VideoFrame(picture, Packet.NO_TIMESTAMP,
                            Packet.UNKNOWN_DURATION, true));
                    var topFirst = order == FieldOrder.TOP_FIRST;
                    var jpegOptions = JpegEncoderOptions.ofQuality(85);
                    var first = JpegEncoder.encode(fieldOf(picture, !topFirst), jpegOptions);
                    var second = JpegEncoder.encode(fieldOf(picture, topFirst), jpegOptions);
                    assertArrayEquals(MotionJpegTesting.concat(MotionJpegTesting.withAvi1(first, 1, first.length),
                            MotionJpegTesting.withAvi1(second, 2, second.length)), packet.data(), name + " " + order);
                }
            }
        }

        @Test
        @DisplayName("reject a picture of one row")
        void tooShort() {
            var options = MjpegEncoderOptions.builder().fieldOrder(FieldOrder.TOP_FIRST).build();
            assertThrows(IllegalArgumentException.class, () -> new MjpegEncoder(16, 1, options));
        }
    }

    @Nested
    @DisplayName("timing")
    class Timing {
        @Test
        @DisplayName("stamps frames without timestamps at the nominal rate and keeps given ones")
        void timestamps() {
            var encoder = new MjpegEncoder(16, 16, MjpegEncoderOptions.builder().frameRate(new Rational(25, 1))
                    .timeBase(new Rational(1, 1000)).build());
            var picture = MotionJpegTesting.pattern(16, 16, 0);
            var first = encoder.encodeFrame(new VideoFrame(picture, Packet.NO_TIMESTAMP, Packet.UNKNOWN_DURATION, true));
            var second = encoder.encodeFrame(new VideoFrame(picture, Packet.NO_TIMESTAMP, Packet.UNKNOWN_DURATION, true));
            var third = encoder.encodeFrame(new VideoFrame(picture, 500, 10, true));
            assertEquals(0, first.pts());
            assertEquals(40, second.pts());
            assertEquals(40, second.duration());
            assertEquals(500, third.pts());
            assertEquals(500, third.dts());
            assertEquals(10, third.duration());
            assertThrows(IllegalArgumentException.class,
                    () -> encoder.encodeFrame(new VideoFrame(picture, 500, 10, true)));
            assertThrows(IllegalArgumentException.class, () -> encoder.encodeFrame(
                    new VideoFrame(MotionJpegTesting.pattern(16, 8, 0), 900, 10, true)));
            encoder.requestKeyframe();
            assertEquals(Codec.MJPEG, encoder.codec());
            assertTrue(encoder.codecConfig().isEmpty());
            assertEquals(new Rational(1, 1000), encoder.timeBase());
        }

        @Test
        @DisplayName("rejects invalid sizes and limits")
        void sizes() {
            assertThrows(IllegalArgumentException.class, () -> new MjpegEncoder(0, 16));
            assertThrows(IllegalArgumentException.class, () -> new MjpegEncoder(16, 65536));
            var limits = com.github.auties00.smalt.MediaLimits.builder().maxDimension(32).build();
            assertThrows(com.github.auties00.smalt.MediaException.LimitExceeded.class,
                    () -> new MjpegEncoder(64, 16, MjpegEncoderOptions.builder().limits(limits).build()));
        }
    }

    @Nested
    @DisplayName("rate control")
    class RateControl {
        private static long bits(List<Packet> packets) {
            return packets.stream().mapToLong(packet -> 8L * packet.data().length).sum();
        }

        @ParameterizedTest(name = "{0} bit/s")
        @ValueSource(longs = {300_000, 1_000_000, 2_500_000})
        @DisplayName("brings the average rate within 8% of the target")
        void target(long bitrate) {
            var count = 60;
            var encoder = new MjpegEncoder(160, 120, MjpegEncoderOptions.builder().bitrate(bitrate).build());
            var packets = encode(encoder, patterns(160, 120, count));
            var rate = bits(packets) * 30.0 / count;
            assertTrue(Math.abs(rate - bitrate) < 0.08 * bitrate, "rate " + rate + " for " + bitrate);
        }

        @Test
        @DisplayName("follows a new target set while running, and only in that mode")
        void retarget() {
            var encoder = new MjpegEncoder(160, 120, MjpegEncoderOptions.builder().bitrate(4_000_000).build());
            var pictures = patterns(160, 120, 80);
            var before = encode(encoder, pictures.subList(0, 40));
            encoder.setTargetBitrate(800_000);
            var after = encode(encoder, pictures.subList(40, 80));
            var lateRate = bits(after.subList(20, 40)) * 30.0 / 20;
            assertTrue(Math.abs(lateRate - 800_000) < 0.1 * 800_000, "rate " + lateRate);
            assertTrue(bits(before) > 3 * bits(after));
            assertThrows(IllegalArgumentException.class, () -> encoder.setTargetBitrate(0));
            var constant = new MjpegEncoder(16, 16);
            assertThrows(IllegalStateException.class, () -> constant.setTargetBitrate(1_000_000));
        }
    }

    @Nested
    @DisplayName("options")
    class Options {
        @Test
        @DisplayName("hold their defaults and presets, and round-trip through toBuilder")
        void defaults() {
            var defaults = MjpegEncoderOptions.defaults();
            assertEquals(75, defaults.quality());
            assertEquals(ChromaFormat.YUV420, defaults.chromaFormat());
            assertTrue(defaults.optimizeHuffman());
            assertTrue(defaults.huffmanTables());
            assertEquals(0, defaults.restartInterval());
            assertEquals(FieldOrder.PROGRESSIVE, defaults.fieldOrder());
            assertEquals(new Rational(30, 1), defaults.frameRate());
            assertEquals(new Rational(1, 90000), defaults.timeBase());
            assertTrue(defaults.bitrate().isEmpty());
            assertEquals(0, defaults.threads());
            assertEquals(40, MjpegEncoderOptions.ofQuality(40).quality());
            var custom = MjpegEncoderOptions.builder().quality(12).chromaFormat(ChromaFormat.YUV444)
                    .optimizeHuffman(false).huffmanTables(false).restartInterval(9)
                    .fieldOrder(FieldOrder.BOTTOM_FIRST).frameRate(new Rational(50, 1)).timeBase(new Rational(1, 50))
                    .bitrate(123).threads(2).build();
            assertEquals(custom, custom.toBuilder().build());
            assertEquals(custom.hashCode(), custom.toBuilder().build().hashCode());
            assertNotEquals(custom, custom.toBuilder().noBitrate().build());
            assertTrue(custom.toBuilder().noBitrate().build().bitrate().isEmpty());
            assertTrue(custom.toString().contains("BOTTOM_FIRST"));
        }

        @Test
        @DisplayName("reject out-of-range values and optimized tables left out")
        void validation() {
            assertThrows(IllegalArgumentException.class, () -> MjpegEncoderOptions.builder().quality(101).build());
            assertThrows(IllegalArgumentException.class, () -> MjpegEncoderOptions.builder().restartInterval(-1).build());
            assertThrows(IllegalArgumentException.class,
                    () -> MjpegEncoderOptions.builder().frameRate(new Rational(2000, 1)).build());
            assertThrows(IllegalArgumentException.class, () -> MjpegEncoderOptions.builder().bitrate(0).build());
            assertThrows(IllegalArgumentException.class, () -> MjpegEncoderOptions.builder().threads(-1).build());
            assertThrows(IllegalArgumentException.class, () -> MjpegEncoderOptions.builder().huffmanTables(false).build());
            assertThrows(NullPointerException.class, () -> MjpegEncoderOptions.builder().fieldOrder(null));
        }
    }

    @Test
    @DisplayName("is served by VideoEncoders with the requested frame rate and rate control")
    void registry() {
        assertTrue(VideoEncoders.isSupported(Codec.MJPEG));
        try (var encoder = VideoEncoders.create(Codec.MJPEG, VideoEncoderOptions.of(160, 120, new Rational(30, 1),
                1_500_000))) {
            var mjpeg = assertInstanceOf(MjpegEncoder.class, encoder);
            assertEquals(1_500_000L, mjpeg.options().bitrate().orElseThrow());
            assertEquals(new Rational(30, 1), mjpeg.options().frameRate());
            var packets = encode(mjpeg, patterns(160, 120, 45));
            var rate = packets.stream().mapToLong(packet -> 8L * packet.data().length).sum() * 30.0 / 45;
            assertTrue(Math.abs(rate - 1_500_000) < 0.1 * 1_500_000, "rate " + rate);
            var frames = new ArrayList<Picture>();
            try (var decoder = VideoDecoders.create(VideoTrack.builder().id(1).codec(Codec.MJPEG)
                    .timeBase(encoder.timeBase()).width(160).height(120).build())) {
                decoder.decode(packets.getFirst(), frame -> frames.add(frame.picture()));
            }
            assertEquals(160, frames.getFirst().width());
        }
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"avi", "mov", "mkv"})
    @DisplayName("is the encoder the transcoder uses for Motion-JPEG output, into files ffmpeg decodes cleanly")
    void transcode(String extension, @TempDir Path directory) {
        Oracle.assume(Oracle.Tool.FFMPEG);
        var input = Fixtures.testVideo("webm", 160, 120, 0.5, List.of("-c:v", "libvpx", "-b:v", "400k", "-an"));
        var output = directory.resolve("out." + extension);
        var container = OutputContainer.forFileName(output.toString()).orElseThrow();
        var options = TranscodeOptions.of(container).toBuilder().noAudio()
                .video(VideoTranscodeOptions.encode(Codec.MJPEG)).build();
        Transcoder.transcode(MediaSource.of(Fixtures.read(input)), MediaSink.of(output), options);
        Oracle.assertDecodesCleanly(output);
        assertTrue(Oracle.probe(output).contains("\"codec_name\": \"mjpeg\""));
        var stream = MotionJpegTesting.demux(Fixtures.read(output));
        assertEquals(Codec.MJPEG, stream.track().codec());
        assertEquals(15, stream.packets().size());
    }

    @Nested
    @DisplayName("in containers")
    class Containers {
        enum Container {
            AVI("avi"), MOV("mov"), MP4("mp4"), MKV("mkv");

            final String extension;

            Container(String extension) {
                this.extension = extension;
            }
        }

        private static byte[] mux(Container container, VideoTrack track, List<Packet> packets) {
            var sink = MediaSink.memory();
            var muxer = switch (container) {
                case AVI -> new AviMuxer(sink);
                case MOV -> new Mp4Muxer(sink, Mp4MuxerOptions.quickTime());
                case MP4 -> new Mp4Muxer(sink, Mp4MuxerOptions.mp4());
                case MKV -> new MatroskaMuxer(sink, MatroskaMuxerOptions.matroska());
            };
            try (muxer) {
                muxer.addTrack(track);
                for (var packet : packets) {
                    muxer.writePacket(packet.withTrackId(track.id()));
                }
                muxer.finish();
            }
            return sink.toByteArray();
        }

        static Stream<Arguments> cases() {
            var arguments = Stream.<Arguments>builder();
            for (var container : Container.values()) {
                arguments.add(Arguments.of(container, MjpegEncoderOptions.defaults()));
                arguments.add(Arguments.of(container, MjpegEncoderOptions.forAvi().toBuilder()
                        .chromaFormat(ChromaFormat.YUV422).build()));
            }
            return arguments.build();
        }

        @ParameterizedTest(name = "{0}, {1}")
        @MethodSource("cases")
        @DisplayName("demux to the same packets, and ffmpeg decodes them to Smalt's frames within its IDCT error")
        void progressive(Container container, MjpegEncoderOptions options, @TempDir Path directory)
                throws IOException {
            var width = 96;
            var height = 64;
            var encoder = new MjpegEncoder(width, height, options);
            var packets = encode(encoder, patterns(width, height, 4));
            var track = VideoTrack.builder().id(1).codec(Codec.MJPEG).timeBase(encoder.timeBase())
                    .frameRate(new Rational(30, 1)).width(width).height(height).build();
            var file = mux(container, track, packets);
            var stream = MotionJpegTesting.demux(file);
            assertEquals(Codec.MJPEG, stream.track().codec());
            assertEquals(packets.size(), stream.packets().size());
            var frames = new ArrayList<Picture>();
            try (var decoder = VideoDecoders.create(stream.track())) {
                for (var i = 0; i < packets.size(); i++) {
                    assertArrayEquals(packets.get(i).data(), stream.packets().get(i).data());
                    decoder.decode(stream.packets().get(i), frame -> frames.add(frame.picture()));
                }
            }
            if (Oracle.find(Oracle.Tool.FFMPEG).isEmpty()) {
                return;
            }
            var path = directory.resolve("mjpeg." + container.extension);
            Files.write(path, file);
            var format = options.chromaFormat() == ChromaFormat.YUV422 ? "yuvj422p" : "yuvj420p";
            MotionJpegTesting.assertCloseToFfmpeg(Oracle.decodeVideo(path, format, 0), frames, 3, 0.5);
        }

        static Stream<Arguments> interlacedCases() {
            return Stream.of(
                    Arguments.of(Container.MOV, FieldOrder.TOP_FIRST, Interlacing.TOP_FIELD_FIRST_SEPARATED),
                    Arguments.of(Container.MOV, FieldOrder.BOTTOM_FIRST, Interlacing.BOTTOM_FIELD_FIRST_SEPARATED),
                    Arguments.of(Container.MOV, FieldOrder.TOP_FIRST, Interlacing.TOP_FIELD_FIRST),
                    Arguments.of(Container.MKV, FieldOrder.TOP_FIRST, Interlacing.TOP_FIELD_FIRST_SEPARATED),
                    Arguments.of(Container.MKV, FieldOrder.BOTTOM_FIRST, Interlacing.BOTTOM_FIELD_FIRST_SEPARATED),
                    // AVI records no field order: its track demuxes as interlaced in an undeclared order, and ffmpeg
                    // takes the first field of an AVI frame as the bottom one, so the decoder is given that order
                    Arguments.of(Container.AVI, FieldOrder.BOTTOM_FIRST, Interlacing.BOTTOM_FIELD_FIRST_SEPARATED));
        }

        @ParameterizedTest(name = "{0}, {1}, track {2}")
        @MethodSource("interlacedCases")
        @DisplayName("carry interlaced frames that ffmpeg weaves as Smalt does")
        void interlaced(Container container, FieldOrder order, Interlacing scan, @TempDir Path directory)
                throws IOException {
            var width = 96;
            var height = 64;
            var encoder = new MjpegEncoder(width, height, MjpegEncoderOptions.builder()
                    .fieldOrder(order).chromaFormat(ChromaFormat.YUV422).build());
            var packets = encode(encoder, patterns(width, height, 3));
            var track = VideoTrack.builder().id(1).codec(Codec.MJPEG).timeBase(encoder.timeBase())
                    .frameRate(new Rational(30, 1)).width(width).height(height).interlacing(scan).build();
            var file = mux(container, track, packets);
            var stream = MotionJpegTesting.demux(file);
            var demuxed = stream.track().interlacing().orElseThrow();
            assertTrue(demuxed.isInterlaced());
            if (container != Container.AVI) {
                assertEquals(scan, demuxed);
            }
            var frames = new ArrayList<Picture>();
            try (var decoder = VideoDecoders.create(stream.track().toBuilder().interlacing(scan).build())) {
                for (var packet : stream.packets()) {
                    decoder.decode(packet, frame -> frames.add(frame.picture()));
                }
            }
            assertEquals(3, frames.size());
            var progressive = new MjpegDecoder();
            var reference = encode(new MjpegEncoder(width, height, MjpegEncoderOptions.builder()
                    .chromaFormat(ChromaFormat.YUV422).build()), patterns(width, height, 3));
            for (var i = 0; i < frames.size(); i++) {
                assertEquals(height, frames.get(i).height());
                var source = MotionJpegTesting.pattern(width, height, i);
                var psnr = Quality.psnr(ImageOps.toRgba(frames.get(i)), source);
                var data = reference.get(i).data();
                var bound = Quality.psnr(ImageOps.toRgba(progressive.decodeFrame(data, 0, data.length)), source);
                // coding each field alone loses some of what a progressive frame keeps; swapped fields lose far more
                assertTrue(psnr > bound - 3, "PSNR " + psnr + ", progressive " + bound);
            }
            if (Oracle.find(Oracle.Tool.FFMPEG).isEmpty()) {
                return;
            }
            var path = directory.resolve("interlaced." + container.extension);
            Files.write(path, file);
            MotionJpegTesting.assertCloseToFfmpeg(Oracle.decodeVideo(path, "yuvj422p", 0), frames, 3, 0.5);
        }
    }
}
