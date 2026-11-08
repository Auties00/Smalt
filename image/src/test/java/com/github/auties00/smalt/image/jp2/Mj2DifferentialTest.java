package com.github.auties00.smalt.image.jp2;

import com.github.auties00.smalt.container.Containers;
import com.github.auties00.smalt.container.ContainerFormat;
import com.github.auties00.smalt.container.Muxer;
import com.github.auties00.smalt.container.avi.AviMuxer;
import com.github.auties00.smalt.container.matroska.MatroskaMuxer;
import com.github.auties00.smalt.container.mp4.Mp4Muxer;
import com.github.auties00.smalt.container.mp4.Mp4MuxerOptions;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ImageFormat;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.internal.codec.Jp2Frames;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.Media;
import com.github.auties00.smalt.probe.VideoStreamInfo;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.Tolerance;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Interlacing;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.VideoTrack;
import com.github.auties00.smalt.video.FieldOrder;
import com.github.auties00.smalt.video.VideoDecoderOptions;
import com.github.auties00.smalt.video.VideoDecoders;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Compares Smalt's Motion JPEG 2000 with ffmpeg in both directions: files Smalt muxes from {@link Mj2Encoder}
 * frames (MJ2, MP4, QuickTime, Matroska, AVI) demux and decode in Smalt to the coded pictures and in ffmpeg to the
 * same samples, and files ffmpeg writes with its own and with OpenJPEG's encoder (JP2 files and raw codestreams, in
 * each container ffmpeg writes them to) decode in Smalt to the samples ffmpeg decodes. Lossless streams compare
 * exactly; the irreversible wavelet is float arithmetic both decoders order differently, within a PSNR bound.
 */
@DisplayName("Motion JPEG 2000 against ffmpeg")
class Mj2DifferentialTest {
    private static final int WIDTH = 96;

    private static final int HEIGHT = 64;

    enum Container {
        MJ2("mj2", sink -> new Mp4Muxer(sink, Mp4MuxerOptions.mj2())),
        MP4("mp4", sink -> new Mp4Muxer(sink, Mp4MuxerOptions.mp4())),
        MOV("mov", sink -> new Mp4Muxer(sink, Mp4MuxerOptions.quickTime())),
        MKV("mkv", MatroskaMuxer::new),
        AVI("avi", AviMuxer::new);

        final String extension;

        final Function<MediaSink, Muxer> muxer;

        Container(String extension, Function<MediaSink, Muxer> muxer) {
            this.extension = extension;
            this.muxer = muxer;
        }
    }

    private static List<Picture> pictures(ChromaFormat format, int count) {
        var pictures = new ArrayList<Picture>();
        for (var frame = 0; frame < count; frame++) {
            pictures.add(Mj2Testing.yuv(WIDTH, HEIGHT, format, frame));
        }
        return pictures;
    }

    private static List<Picture> decodeAll(Mj2Testing.Stream stream, boolean highBitDepth) {
        var pictures = new ArrayList<Picture>();
        try (var decoder = VideoDecoders.create(stream.track(), VideoDecoderOptions.builder()
                .highBitDepth(highBitDepth).build())) {
            for (var packet : stream.packets()) {
                decoder.decode(packet, frame -> pictures.add(frame.picture()));
            }
            decoder.flush(frame -> pictures.add(frame.picture()));
        }
        return pictures;
    }

    private static byte[] raw(List<Picture> pictures) {
        var parts = new ArrayList<byte[]>();
        for (var picture : pictures) {
            parts.add(Mj2Testing.raw(picture));
        }
        return Mj2Testing.concat(parts);
    }

    @Nested
    @DisplayName("files Smalt writes")
    class Written {
        static Stream<Arguments> cases() {
            var arguments = Stream.<Arguments>builder();
            for (var container : Container.values()) {
                arguments.add(Arguments.of(container, Jp2Format.JP2, ChromaFormat.YUV420));
                arguments.add(Arguments.of(container, Jp2Format.CODESTREAM, ChromaFormat.YUV420));
                arguments.add(Arguments.of(container, Jp2Format.CODESTREAM, ChromaFormat.YUV444));
            }
            return arguments.build();
        }

        @ParameterizedTest(name = "{0}, {1}, {2}")
        @MethodSource("cases")
        @DisplayName("demux and decode in Smalt to the coded pictures, and in ffmpeg to the same samples")
        void lossless(Container container, Jp2Format form, ChromaFormat chroma, @TempDir Path directory)
                throws IOException {
            var format = chroma == ChromaFormat.YUV444 ? Mj2PixelFormat.YUV444 : Mj2PixelFormat.YUV420;
            var encoder = new Mj2Encoder(WIDTH, HEIGHT, Mj2EncoderOptions.builder().pixelFormat(format)
                    .coding(Jp2EncoderOptions.lossless().toBuilder().format(form).build()).build());
            var pictures = pictures(chroma, 4);
            var packets = Mj2Testing.encode(encoder, pictures);
            var track = VideoTrack.builder().id(1).codec(Codec.JPEG2000).timeBase(encoder.timeBase())
                    .frameRate(new Rational(30, 1)).width(WIDTH).height(HEIGHT)
                    .codecConfig(encoder.codecConfig().orElseThrow()).build();
            var file = Mj2Testing.mux(container.muxer, track, packets);
            var stream = Mj2Testing.demux(file);
            assertEquals(Codec.JPEG2000, stream.track().codec());
            assertEquals(packets.size(), stream.packets().size());
            for (var i = 0; i < packets.size(); i++) {
                var stored = stream.packets().get(i).data();
                var expected = switch (container) {
                    // a Motion JPEG 2000 file stores the Contiguous Codestream box of each frame
                    case MJ2 -> Jp2Frames.toSample(packets.get(i).data());
                    // Matroska and AVI keep no header with the track, so a raw codestream becomes a JP2 file
                    case MKV, AVI -> Jp2Frames.selfContained(packets.get(i).data(), encoder.codecConfig().get());
                    default -> packets.get(i).data();
                };
                assertArrayEquals(expected, stored, container + " packet " + i);
                assertTrue(stream.packets().get(i).keyframe());
            }
            if (container == Container.MP4 || container == Container.MOV || container == Container.MJ2) {
                assertArrayEquals(encoder.codecConfig().get(), stream.track().codecConfig().orElseThrow(),
                        "the sample entry's jp2h box");
            }
            var decoded = decodeAll(stream, false);
            for (var i = 0; i < pictures.size(); i++) {
                Tolerance.exact().assertWithin(container + " frame " + i, pictures.get(i), decoded.get(i));
            }
            var info = Media.probe(MediaSource.of(file));
            var video = (VideoStreamInfo) info.streams().getFirst();
            assertEquals(Codec.JPEG2000, video.codec());
            assertEquals(8, video.bitDepth().orElse(-1));
            assertEquals(chroma, video.chromaFormat().orElse(null));
            Oracle.assume(Oracle.Tool.FFMPEG);
            if (chroma == ChromaFormat.YUV444 && form == Jp2Format.CODESTREAM
                    && (container == Container.MP4 || container == Container.MOV || container == Container.MJ2)) {
                // ffmpeg reads the color space from the frames, not from the sample entry's jp2h box
                return;
            }
            var path = directory.resolve("smalt." + container.extension);
            Files.write(path, file);
            Oracle.assertDecodesCleanly(path);
            var pixelFormat = chroma == ChromaFormat.YUV444 ? "yuv444p" : "yuv420p";
            Tolerance.exact().assertFrames(container + " in ffmpeg", Oracle.decodeVideo(path, pixelFormat, 0),
                    raw(pictures), raw(pictures).length / pictures.size());
        }

        @Test
        @DisplayName("write a Motion JPEG 2000 file whose signature and brand make it a video container")
        void motionJpeg2000File() {
            var encoder = new Mj2Encoder(WIDTH, HEIGHT);
            var packets = Mj2Testing.encode(encoder, pictures(ChromaFormat.YUV420, 2));
            var track = VideoTrack.builder().id(1).codec(Codec.JPEG2000).timeBase(encoder.timeBase()).width(WIDTH)
                    .height(HEIGHT).codecConfig(encoder.codecConfig().orElseThrow()).build();
            var file = Mj2Testing.mux(Container.MJ2.muxer, track, packets);
            assertTrue(Jp2Frames.isFile(file, 0, file.length), "the signature box opens the file");
            assertEquals(Optional.of(ContainerFormat.MP4), Containers.detect(MediaSource.of(file)));
            assertTrue(ImageFormat.detect(file, file.length).isEmpty(), "an MJ2 file is not a JP2 image");
            var info = Media.probe(MediaSource.of(file));
            assertTrue(info.image().isEmpty());
            assertEquals(1, info.streams().size());
        }

        static Stream<Arguments> interlaced() {
            return Stream.of(
                    Arguments.of(Container.MJ2, FieldOrder.TOP_FIRST, Interlacing.TOP_FIELD_FIRST_SEPARATED),
                    Arguments.of(Container.MJ2, FieldOrder.BOTTOM_FIRST, Interlacing.BOTTOM_FIELD_FIRST_SEPARATED),
                    Arguments.of(Container.MOV, FieldOrder.BOTTOM_FIRST, Interlacing.BOTTOM_FIELD_FIRST_SEPARATED),
                    Arguments.of(Container.MKV, FieldOrder.TOP_FIRST, Interlacing.TOP_FIELD_FIRST_SEPARATED));
        }

        @ParameterizedTest(name = "{0}, {1}")
        @MethodSource("interlaced")
        @DisplayName("carry interlaced frames whose scan the file records, woven back by the decoder")
        void fields(Container container, FieldOrder order, Interlacing scan) {
            var encoder = new Mj2Encoder(WIDTH, HEIGHT, Mj2EncoderOptions.builder().pixelFormat(Mj2PixelFormat.YUV422)
                    .fieldOrder(order).build());
            var pictures = pictures(ChromaFormat.YUV422, 3);
            var packets = Mj2Testing.encode(encoder, pictures);
            var track = VideoTrack.builder().id(1).codec(Codec.JPEG2000).timeBase(encoder.timeBase()).width(WIDTH)
                    .height(HEIGHT).interlacing(scan).codecConfig(encoder.codecConfig().orElseThrow()).build();
            var file = Mj2Testing.mux(container.muxer, track, packets);
            var stream = Mj2Testing.demux(file);
            assertEquals(scan, stream.track().interlacing().orElseThrow());
            for (var i = 0; i < packets.size(); i++) {
                // Matroska keeps no header with the track, so the fields follow a copy of it
                var expected = container == Container.MKV ? Jp2Frames.selfContained(packets.get(i).data(),
                        encoder.codecConfig().get()) : packets.get(i).data();
                assertArrayEquals(expected, stream.packets().get(i).data(), "packet " + i);
            }
            var decoded = decodeAll(stream, false);
            for (var i = 0; i < pictures.size(); i++) {
                Tolerance.exact().assertWithin(container + " frame " + i, pictures.get(i), decoded.get(i));
            }
            if (container == Container.MJ2) {
                var fiel = indexOf(file, "fiel");
                var orfo = indexOf(file, "orfo");
                var expected = order == FieldOrder.TOP_FIRST ? 1 : 6;
                assertEquals(2, file[fiel + 4]);
                assertEquals(expected, file[fiel + 5]);
                assertEquals(2, file[orfo + 4]);
                assertEquals(expected, file[orfo + 5]);
                var jsub = indexOf(file, "jsub");
                assertEquals(2, file[jsub + 4]);
                assertEquals(1, file[jsub + 5]);
            }
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("headerless")
        @DisplayName("keep the color space of interlaced 4:4:4 Y'CbCr frames in containers without a header")
        void headerlessFields(Container container) {
            var encoder = new Mj2Encoder(WIDTH, HEIGHT, Mj2EncoderOptions.builder().pixelFormat(Mj2PixelFormat.YUV444)
                    .fieldOrder(FieldOrder.TOP_FIRST).build());
            var pictures = pictures(ChromaFormat.YUV444, 2);
            var packets = Mj2Testing.encode(encoder, pictures);
            var track = VideoTrack.builder().id(1).codec(Codec.JPEG2000).timeBase(encoder.timeBase()).width(WIDTH)
                    .height(HEIGHT).interlacing(Interlacing.TOP_FIELD_FIRST_SEPARATED)
                    .codecConfig(encoder.codecConfig().orElseThrow()).build();
            var stream = Mj2Testing.demux(Mj2Testing.mux(container.muxer, track, packets));
            assertTrue(stream.track().codecConfig().isEmpty(), "the container stores no header");
            for (var packet : stream.packets()) {
                var data = packet.data();
                assertArrayEquals(encoder.codecConfig().get(), Jp2Frames.header(data, 0, data.length));
            }
            var scanned = new Mj2Testing.Stream(stream.track().toBuilder()
                    .interlacing(Interlacing.TOP_FIELD_FIRST_SEPARATED).build(), stream.packets());
            var decoded = decodeAll(scanned, false);
            for (var i = 0; i < pictures.size(); i++) {
                Tolerance.exact().assertWithin(container + " frame " + i, pictures.get(i), decoded.get(i));
            }
        }

        static Stream<Container> headerless() {
            return Stream.of(Container.MKV, Container.AVI);
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("highThroughput")
        @DisplayName("carry frames of the high-throughput block coder")
        void highThroughput(Container container) {
            var coding = Jp2EncoderOptions.lossless().toBuilder().highThroughput(true).format(Jp2Format.CODESTREAM)
                    .build();
            var encoder = new Mj2Encoder(WIDTH, HEIGHT, Mj2EncoderOptions.builder().coding(coding).build());
            var pictures = pictures(ChromaFormat.YUV420, 2);
            var packets = Mj2Testing.encode(encoder, pictures);
            var track = VideoTrack.builder().id(1).codec(Codec.JPEG2000).timeBase(encoder.timeBase()).width(WIDTH)
                    .height(HEIGHT).codecConfig(encoder.codecConfig().orElseThrow()).build();
            var stream = Mj2Testing.demux(Mj2Testing.mux(container.muxer, track, packets));
            if (container == Container.MKV) {
                var data = stream.packets().getFirst().data();
                assertTrue(Jp2Frames.isFile(data, 0, data.length));
                assertEquals("jph ", new String(data, 20, 4, java.nio.charset.StandardCharsets.US_ASCII),
                        "a file of a high-throughput codestream is a JPH file");
            }
            var decoded = decodeAll(stream, false);
            for (var i = 0; i < pictures.size(); i++) {
                Tolerance.exact().assertWithin(container + " frame " + i, pictures.get(i), decoded.get(i));
            }
        }

        static Stream<Container> highThroughput() {
            return Stream.of(Container.MJ2, Container.MKV);
        }

        private static int indexOf(byte[] data, String type) {
            var pattern = type.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
            for (var i = 0; i + 4 <= data.length; i++) {
                if (java.util.Arrays.equals(data, i, i + 4, pattern, 0, 4)) {
                    return i;
                }
            }
            throw new AssertionError(type + " not found");
        }
    }

    @Nested
    @DisplayName("files ffmpeg writes")
    class Read {
        static Stream<Arguments> cases() {
            return Stream.of(
                    Arguments.of("mov", List.of("-c:v", "jpeg2000", "-pred", "dwt53"), "yuv420p", false),
                    Arguments.of("mkv", List.of("-c:v", "jpeg2000", "-pred", "dwt53"), "yuv444p", false),
                    Arguments.of("avi", List.of("-c:v", "libopenjpeg"), "yuv422p", false),
                    Arguments.of("mp4", List.of("-c:v", "libopenjpeg", "-format", "j2k"), "gray", false),
                    Arguments.of("mov", List.of("-c:v", "jpeg2000", "-pred", "dwt53"), "rgb24", false),
                    Arguments.of("mov", List.of("-c:v", "libopenjpeg"), "rgba", false),
                    Arguments.of("mkv", List.of("-c:v", "libopenjpeg"), "yuv420p10le", true),
                    Arguments.of("avi", List.of("-c:v", "jpeg2000", "-pred", "dwt53", "-format", "j2k"), "yuv420p",
                            false));
        }

        @ParameterizedTest(name = "{0}, {1}, {2}")
        @MethodSource("cases")
        @DisplayName("decode losslessly coded frames in Smalt to exactly the samples ffmpeg decodes")
        void lossless(String extension, List<String> codec, String pixelFormat, boolean deep) {
            Oracle.assume(Oracle.Tool.FFMPEG);
            var output = new ArrayList<>(codec);
            output.addAll(List.of("-pix_fmt", pixelFormat, "-frames:v", "5"));
            var file = Fixtures.ffmpeg(extension, List.of("-f", "lavfi", "-i", "testsrc2=size=" + WIDTH + "x"
                    + HEIGHT + ":rate=25"), output);
            var stream = Mj2Testing.demux(Fixtures.read(file));
            assertEquals(Codec.JPEG2000, stream.track().codec());
            var decoded = decodeAll(stream, deep);
            assertEquals(5, decoded.size());
            var ours = pixelFormat.startsWith("rgb") ? rgba(decoded) : raw(decoded);
            var reference = Oracle.decodeVideo(file, pixelFormat.startsWith("rgb") ? "rgba" : pixelFormat, 0);
            var what = extension + " " + codec + " " + pixelFormat;
            if (deep) {
                Tolerance.exact().assertFrames(what, words(reference), words(ours), ours.length / 2 / 5, 10);
            } else {
                Tolerance.exact().assertFrames(what, reference, ours, ours.length / 5);
            }
        }

        @Test
        @DisplayName("decode irreversibly coded frames close to ffmpeg's float synthesis")
        void lossy() {
            Oracle.assume(Oracle.Tool.FFMPEG);
            var file = Fixtures.ffmpeg("mov", List.of("-f", "lavfi", "-i", "testsrc2=size=" + WIDTH + "x" + HEIGHT
                    + ":rate=25"), List.of("-c:v", "jpeg2000", "-pix_fmt", "yuv420p", "-frames:v", "4"));
            var decoded = decodeAll(Mj2Testing.demux(Fixtures.read(file)), false);
            var ours = raw(decoded);
            var reference = Oracle.decodeVideo(file, "yuv420p", 0);
            var difference = Tolerance.Difference.of(reference, ours);
            System.out.println("[mj2-lossy] " + difference);
            Tolerance.maxAbs(1).and(Tolerance.psnr(60)).assertFrames("irreversible 9-7", reference, ours,
                    ours.length / 4);
        }

        private static byte[] rgba(List<Picture> pictures) {
            var parts = new ArrayList<byte[]>();
            for (var picture : pictures) {
                parts.add(Mj2Testing.raw(com.github.auties00.smalt.image.ops.ImageOps.toRgba(picture)));
            }
            return Mj2Testing.concat(parts);
        }

        private static short[] words(byte[] data) {
            var out = new short[data.length / 2];
            for (var i = 0; i < out.length; i++) {
                out[i] = (short) ((data[2 * i] & 0xFF) | (data[2 * i + 1] & 0xFF) << 8);
            }
            return out;
        }
    }
}
