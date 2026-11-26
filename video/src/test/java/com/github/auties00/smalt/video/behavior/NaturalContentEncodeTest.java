package com.github.auties00.smalt.video.behavior;

import com.github.auties00.smalt.container.matroska.MatroskaMuxer;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.testkit.Conformance;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.Scratch;
import com.github.auties00.smalt.testkit.Tolerance;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.VideoTrack;
import com.github.auties00.smalt.video.VideoEncoderOptions;
import com.github.auties00.smalt.video.VideoEncoders;
import com.github.auties00.smalt.video.VideoFrame;
import com.github.auties00.smalt.video.VideoReader;
import com.github.auties00.smalt.video.intra.IntraTesting;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Encodes libvpx's natural-content test clips ({@code park_joy_90p_<depth>_<format>}: 160x90 frames of a real scene
 * at 8, 10 and 12 bits in 4:2:0, 4:2:2, 4:4:0 and 4:4:4) with every Smalt encoder that codes the clip's picture
 * format, muxes the packets into Matroska and decodes them with ffmpeg: FFV1 must return the source exactly, the
 * lossy encoders must stay close to it. Synthetic test patterns elsewhere leave the encoders' handling of natural
 * texture, noise and motion untested.
 */
@DisplayName("encoders on natural content in every picture format")
class NaturalContentEncodeTest {
    private static final int WIDTH = 160;

    private static final int HEIGHT = 90;

    /** The lossy encoders at a generous rate against the source: at least 30 dB. */
    private static final Tolerance LOSSY = Tolerance.psnr(30);

    private static final Tolerance LOSSLESS = Tolerance.exact();

    record Clip(String vector, ChromaFormat chroma, int depth) {
        String pixelFormat() {
            var base = switch (chroma) {
                case YUV420 -> "yuv420p";
                case YUV422 -> "yuv422p";
                case YUV440 -> "yuv440p";
                default -> "yuv444p";
            };
            return depth == 8 ? base : base + depth + "le";
        }

        boolean raw() {
            return vector.endsWith(".yuv");
        }

        @Override
        public String toString() {
            return vector;
        }
    }

    static List<Clip> clips() {
        var clips = new ArrayList<Clip>();
        for (var depth : List.of(8, 10, 12)) {
            for (var chroma : List.of(ChromaFormat.YUV420, ChromaFormat.YUV422, ChromaFormat.YUV440,
                    ChromaFormat.YUV444)) {
                var format = switch (chroma) {
                    case YUV420 -> "420";
                    case YUV422 -> "422";
                    case YUV440 -> "440";
                    default -> "444";
                };
                var name = chroma == ChromaFormat.YUV440 ? "park_joy_90p_" + depth + "_440.yuv"
                        : depth == 8 ? "park_joy_90p_8_" + format + ".y4m"
                        : "park_joy_90p_" + depth + "_" + format + "_20f.y4m";
                clips.add(new Clip(name, chroma, depth));
            }
        }
        return clips;
    }

    static Stream<Arguments> encodings() {
        var cases = new ArrayList<Arguments>();
        for (var clip : clips()) {
            cases.add(Arguments.of(clip, Codec.VP9));
            cases.add(Arguments.of(clip, Codec.FFV1));
            if (clip.chroma() != ChromaFormat.YUV440) {
                cases.add(Arguments.of(clip, Codec.AV1));
            }
            if (clip.depth() == 10 && clip.chroma() == ChromaFormat.YUV422
                    || clip.depth() >= 10 && clip.chroma() == ChromaFormat.YUV444) {
                cases.add(Arguments.of(clip, Codec.PRORES));
            }
            if (clip.depth() <= 10 && clip.chroma() == ChromaFormat.YUV422) {
                cases.add(Arguments.of(clip, Codec.DNXHD));
            }
        }
        return cases.stream();
    }

    /** Reads the frames of a clip as raw planar video of its pixel format. */
    static byte[] source(Clip clip) {
        var file = Conformance.vector(clip.vector()).toString();
        var input = clip.raw()
                ? List.of("-f", "rawvideo", "-pix_fmt", clip.pixelFormat(), "-s", WIDTH + "x" + HEIGHT, "-i", file)
                : List.of("-i", file);
        var arguments = new ArrayList<>(List.of("-v", "error"));
        arguments.addAll(input);
        arguments.addAll(List.of("-f", "rawvideo", "-pix_fmt", clip.pixelFormat(), "-"));
        return Oracle.run(Oracle.Tool.FFMPEG, Duration.ofMinutes(2), arguments);
    }

    static int frameBytes(Clip clip) {
        var chromaWidth = clip.chroma() == ChromaFormat.YUV444 || clip.chroma() == ChromaFormat.YUV440 ? WIDTH
                : WIDTH / 2;
        var chromaHeight = clip.chroma() == ChromaFormat.YUV420 || clip.chroma() == ChromaFormat.YUV440 ? HEIGHT / 2
                : HEIGHT;
        return (WIDTH * HEIGHT + 2 * chromaWidth * chromaHeight) * (clip.depth() > 8 ? 2 : 1);
    }

    /** Lays one frame of raw planar video out as a picture. */
    static Picture picture(Clip clip, byte[] raw, int frame) {
        var chromaWidth = clip.chroma() == ChromaFormat.YUV444 || clip.chroma() == ChromaFormat.YUV440 ? WIDTH
                : WIDTH / 2;
        var chromaHeight = clip.chroma() == ChromaFormat.YUV420 || clip.chroma() == ChromaFormat.YUV440 ? HEIGHT / 2
                : HEIGHT;
        var at = frame * frameBytes(clip);
        if (clip.depth() == 8) {
            var y = Arrays.copyOfRange(raw, at, at + WIDTH * HEIGHT);
            at += WIDTH * HEIGHT;
            var u = Arrays.copyOfRange(raw, at, at + chromaWidth * chromaHeight);
            at += chromaWidth * chromaHeight;
            var v = Arrays.copyOfRange(raw, at, at + chromaWidth * chromaHeight);
            return YuvPicture.builder().size(WIDTH, HEIGHT).chromaFormat(clip.chroma()).color(ColorInfo.BT601_LIMITED)
                    .luma(y, WIDTH).chroma(u, v, chromaWidth).build();
        }
        var y = new short[WIDTH * HEIGHT];
        var u = new short[chromaWidth * chromaHeight];
        var v = new short[chromaWidth * chromaHeight];
        for (var plane : List.of(y, u, v)) {
            for (var i = 0; i < plane.length; i++, at += 2) {
                plane[i] = (short) ((raw[at] & 0xFF) | (raw[at + 1] & 0xFF) << 8);
            }
        }
        return Yuv16Picture.builder().size(WIDTH, HEIGHT).chromaFormat(clip.chroma()).bitDepth(clip.depth())
                .color(ColorInfo.BT601_LIMITED).luma(y, WIDTH).chroma(u, v, chromaWidth).build();
    }

    @ParameterizedTest(name = "{0} as {1}")
    @MethodSource("encodings")
    @DisplayName("ffmpeg decodes the encoded clip close to the source, FFV1 exactly")
    void encodes(Clip clip, Codec codec) throws IOException {
        var raw = source(clip);
        var size = frameBytes(clip);
        var frames = Math.min(10, raw.length / size);
        assertEquals(0, raw.length % size, clip + " frame size");
        var packets = new ArrayList<Packet>();
        VideoTrack track;
        try (var encoder = encoder(clip, codec)) {
            for (var i = 0; i < frames; i++) {
                encoder.encode(new VideoFrame(picture(clip, raw, i), i * 1000L, 1000L, i == 0),
                        packet -> packets.add(packet.withTrackId(1)));
            }
            encoder.flush(packet -> packets.add(packet.withTrackId(1)));
            track = VideoTrack.builder().id(1).codec(codec).timeBase(encoder.timeBase()).width(WIDTH).height(HEIGHT)
                    .frameRate(new Rational(30, 1)).codecConfig(encoder.codecConfig().orElse(null)).build();
        }
        var sink = MediaSink.memory();
        var muxer = new MatroskaMuxer(sink);
        muxer.addTrack(track);
        packets.forEach(muxer::writePacket);
        muxer.finish();
        var file = Scratch.directory("natural-content").resolve(clip.vector() + "." + codec.name().toLowerCase()
                + ".mkv");
        Files.write(file, sink.toByteArray());
        // the stream codes the clip's own chroma format
        var coded = new String(Oracle.run(Oracle.Tool.FFPROBE, Duration.ofMinutes(1), List.of("-v", "error",
                "-select_streams", "v:0", "-show_entries", "stream=pix_fmt", "-of", "csv=p=0", file.toString())),
                java.nio.charset.StandardCharsets.US_ASCII).strip();
        assertEquals(clip.pixelFormat().substring(0, 7), coded.substring(0, 7), clip + " as " + codec + ": " + coded);
        var decoded = IntraTesting.ffmpegDecode(file, clip.pixelFormat(), 0);
        var expected = Arrays.copyOf(raw, frames * size);
        assertEquals(expected.length, decoded.length, clip + " as " + codec + ": decoded size");
        var peak = (1 << clip.depth()) - 1;
        var difference = IntraTesting.difference(expected, decoded, clip.depth() > 8, peak);
        System.out.printf("%s as %s: %s%n", clip, codec, difference);
        (codec == Codec.FFV1 ? LOSSLESS : LOSSY).check(clip + " as " + codec, difference);
    }

    @org.junit.jupiter.api.Test
    @DisplayName("the y4m demuxer reads the pixel aspect ratio of the A header parameter")
    void y4mPixelAspect() {
        // park_joy_90p_8_420_a10-1.y4m declares A10:1 (YUV4MPEG2 header parameter A, width:height of a pixel)
        try (var demuxer = com.github.auties00.smalt.container.Containers.openDemuxer(
                com.github.auties00.smalt.io.MediaSource.of(Conformance.vector("park_joy_90p_8_420_a10-1.y4m")))) {
            var track = (VideoTrack) demuxer.tracks().getFirst();
            assertEquals(new Rational(10, 1), track.sampleAspectRatio().orElseThrow().reduce());
            assertEquals(WIDTH, track.width());
            assertEquals(HEIGHT, track.height());
        }
    }

    /**
     * Natural content at extreme sizes: libvpx's {@code niklas_1857_167_30.y4m} (both dimensions odd, a wide strip) and
     * {@code 4x2.y4m} (a frame smaller than one block), through the VP9 and AV1 encoders.
     */
    @ParameterizedTest(name = "{0} as {1}")
    @org.junit.jupiter.params.provider.CsvSource({"niklas_1857_167_30.y4m,VP9", "niklas_1857_167_30.y4m,AV1",
            "4x2.y4m,VP9", "4x2.y4m,AV1"})
    @DisplayName("odd and tiny natural frames decode with ffmpeg close to the source")
    void oddSizes(String vector, Codec codec) throws IOException {
        var file = Conformance.vector(vector);
        var pictures = new ArrayList<Picture>();
        try (var source = com.github.auties00.smalt.io.MediaSource.of(file);
             var reader = VideoReader.open(source)) {
            for (var frame = reader.read(); frame != null && pictures.size() < 5; frame = reader.read()) {
                var yuv = (YuvPicture) frame.picture();
                pictures.add(yuv.toBuilder().luma(yuv.y().clone(), yuv.yStride())
                        .chroma(yuv.u().clone(), yuv.v().clone(), yuv.uvStride()).build());
            }
        }
        var width = pictures.getFirst().width();
        var height = pictures.getFirst().height();
        var options = VideoEncoderOptions.builder().size(width, height).frameRate(new Rational(30, 1))
                .bitrate(Math.max(200_000, width * height * 30L)).build();
        var packets = new ArrayList<Packet>();
        VideoTrack track;
        try (var encoder = VideoEncoders.create(codec, options)) {
            for (var i = 0; i < pictures.size(); i++) {
                encoder.encode(new VideoFrame(pictures.get(i), i * 1000L, 1000L, i == 0),
                        packet -> packets.add(packet.withTrackId(1)));
            }
            encoder.flush(packet -> packets.add(packet.withTrackId(1)));
            track = VideoTrack.builder().id(1).codec(codec).timeBase(encoder.timeBase()).width(width).height(height)
                    .frameRate(new Rational(30, 1)).codecConfig(encoder.codecConfig().orElse(null)).build();
        }
        var sink = MediaSink.memory();
        var muxer = new MatroskaMuxer(sink);
        muxer.addTrack(track);
        packets.forEach(muxer::writePacket);
        muxer.finish();
        var encoded = Scratch.directory("natural-content").resolve(vector + "." + codec.name().toLowerCase() + ".mkv");
        Files.write(encoded, sink.toByteArray());
        var decoded = IntraTesting.ffmpegDecode(encoded, "yuv420p", 0);
        var expected = new java.io.ByteArrayOutputStream();
        for (var picture : pictures) {
            var yuv = (YuvPicture) picture;
            for (var y = 0; y < height; y++) {
                expected.write(yuv.y(), y * yuv.yStride(), width);
            }
            for (var plane : List.of(yuv.u(), yuv.v())) {
                for (var y = 0; y < yuv.chromaHeight(); y++) {
                    expected.write(plane, y * yuv.uvStride(), yuv.chromaWidth());
                }
            }
        }
        assertEquals(expected.size(), decoded.length, vector + " as " + codec + ": decoded size");
        var difference = IntraTesting.difference(expected.toByteArray(), decoded, false, 255);
        System.out.printf("%s as %s: %s%n", vector, codec, difference);
        LOSSY.check(vector + " as " + codec, difference);
    }

    /** Creates the encoder of a codec for the clip's own chroma format and depth, at a high quality. */
    static com.github.auties00.smalt.video.VideoEncoder encoder(Clip clip, Codec codec) {
        var rate = new Rational(30, 1);
        return switch (codec) {
            case VP9 -> new com.github.auties00.smalt.video.vp9.Vp9Encoder(WIDTH, HEIGHT,
                    com.github.auties00.smalt.video.vp9.Vp9EncoderOptions.ofQuality(10).toBuilder().frameRate(rate)
                            .pictureFormat(switch (clip.chroma()) {
                                case YUV420 -> com.github.auties00.smalt.video.vp9.Vp9Subsampling.YUV420;
                                case YUV422 -> com.github.auties00.smalt.video.vp9.Vp9Subsampling.YUV422;
                                case YUV440 -> com.github.auties00.smalt.video.vp9.Vp9Subsampling.YUV440;
                                default -> com.github.auties00.smalt.video.vp9.Vp9Subsampling.YUV444;
                            }, clip.depth()).build());
            case AV1 -> new com.github.auties00.smalt.video.av1.Av1Encoder(WIDTH, HEIGHT,
                    com.github.auties00.smalt.video.av1.Av1EncoderOptions.ofQuality(60).toBuilder()
                            .speed(com.github.auties00.smalt.video.av1.Av1EncoderSpeed.FAST)
                            .pictureFormat(clip.chroma(), clip.depth()).build());
            case FFV1 -> new com.github.auties00.smalt.video.ffv1.Ffv1Encoder(WIDTH, HEIGHT,
                    com.github.auties00.smalt.video.ffv1.Ffv1EncoderOptions.builder().version(3).bitDepth(clip.depth())
                            .chromaFormat(clip.chroma()).slices(4).build());
            case PRORES -> new com.github.auties00.smalt.video.prores.ProResEncoder(WIDTH, HEIGHT,
                    com.github.auties00.smalt.video.prores.ProResEncoderOptions.of(clip.chroma() == ChromaFormat.YUV444
                            ? com.github.auties00.smalt.video.prores.ProResProfile.PRORES_4444
                            : com.github.auties00.smalt.video.prores.ProResProfile.HQ));
            case DNXHD -> new com.github.auties00.smalt.video.dnxhd.DnxhdEncoder(WIDTH, HEIGHT,
                    com.github.auties00.smalt.video.dnxhd.DnxhdEncoderOptions.of(clip.depth() > 8
                            ? com.github.auties00.smalt.video.dnxhd.DnxhdProfile.DNXHR_HQX
                            : com.github.auties00.smalt.video.dnxhd.DnxhdProfile.DNXHR_HQ));
            default -> throw new IllegalArgumentException(codec.toString());
        };
    }
}
