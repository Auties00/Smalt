package com.github.auties00.smalt.video.h264.encoder;

import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.ColorPrimaries;
import com.github.auties00.smalt.image.ColorRange;
import com.github.auties00.smalt.image.MatrixCoefficients;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.TransferCharacteristics;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.PacketFlag;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.VideoTrack;
import com.github.auties00.smalt.video.FieldOrder;
import com.github.auties00.smalt.video.VideoFrame;
import com.github.auties00.smalt.video.h264.H264Decoder;
import com.github.auties00.smalt.video.h264.H264DecoderOptions;
import com.github.auties00.smalt.video.h264.H264StreamInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the extended engine: samples of 8 to 14 bits, the 4:0:0, 4:2:2 and 4:4:4 chroma formats,
 * lossless coding and interlaced frames coded as macroblock pairs or as field pictures, with CABAC
 * and CAVLC. With the deblocking filter disabled (a test hook) the encoder's own reconstruction must
 * equal what Smalt and ffmpeg decode, sample for sample, which checks every prediction, transform and
 * entropy coding path; with the filter enabled the two decoders must agree with each other and the
 * pictures must stay close to the source.
 */
@DisplayName("H264Encoder extended formats")
class ExtendedFormatsTest {
    private static final VideoTrack TRACK = VideoTrack.builder().id(1).codec(Codec.H264)
            .timeBase(new Rational(1, 90_000)).build();

    /** One coded sequence: the packets, the encoder's reconstructions and the decoded frames. */
    record Run(List<Packet> packets, List<short[][]> encoderRecon, List<short[][]> decoded, List<Picture> sources) {
        byte[] stream() {
            var out = new ByteArrayOutputStream();
            packets.forEach(packet -> out.writeBytes(packet.data()));
            return out.toByteArray();
        }
    }

    static H264EncoderOptions.Builder options(H264Profile profile, ChromaFormat format, int depth) {
        return H264EncoderOptions.builder().profile(profile).chromaFormat(format).bitDepth(depth)
                .packetFormat(H264PacketFormat.ANNEX_B).lookahead(0).bFrames(0).keyframeInterval(4)
                .rateControl(H264RateControl.CONSTANT_QUALITY).quality(24);
    }

    static Run run(int width, int height, H264EncoderOptions options, List<Picture> pictures, boolean deblocking) {
        var packets = new ArrayList<Packet>();
        var recon = new ArrayList<short[][]>();
        var decoded = new ArrayList<short[][]>();
        try (var encoder = new H264Encoder(width, height, options)) {
            encoder.wide().setDeblocking(deblocking);
            long pts = 0;
            for (var picture : pictures) {
                encoder.encode(new VideoFrame(picture, pts, 3000, false), packets::add);
                pts += 3000;
                recon.add(encoder.wide().lastEncoderReconstruction());
                decoded.add(encoder.wide().lastReconstruction());
            }
        }
        return new Run(packets, recon, decoded, pictures);
    }

    /** Synthetic frames of a format and depth: moving gradients, a moving square and noise. */
    static List<Picture> frames(int width, int height, ChromaFormat format, int depth, int count, long seed,
                                boolean interlaced) {
        var list = new ArrayList<Picture>();
        var max = (1 << depth) - 1;
        for (var f = 0; f < count; f++) {
            var random = new Random(seed * 31 + f);
            var picture = Yuv16Picture.allocate(width, height, format, depth, false, ColorInfo.BT709_LIMITED);
            for (var y = 0; y < height; y++) {
                // the rows of the bottom field are sampled half a frame later
                var t = interlaced && (y & 1) == 1 ? f + 0.5 : f;
                for (var x = 0; x < width; x++) {
                    var value = 0.25 + 0.5 * (((x + 3 * t) % 64) / 64.0) * 0.6 + 0.2 * Math.sin((y + 2 * t) * 0.15);
                    if (Math.abs(x - width / 2.0 - 6 * Math.sin(t * 0.4)) < width / 6.0 && Math.abs(y - height / 2.0) < 8) {
                        value = 0.85;
                    }
                    value += random.nextGaussian() * 0.01;
                    picture.y()[y * picture.yStride() + x] = (short) Math.clamp(Math.round(value * max), 0, max);
                }
            }
            if (format != ChromaFormat.MONOCHROME) {
                for (var y = 0; y < picture.chromaHeight(); y++) {
                    for (var x = 0; x < picture.chromaWidth(); x++) {
                        var t = f;
                        var u = 0.5 + 0.2 * Math.sin((x + t) * 0.2) + random.nextGaussian() * 0.005;
                        var v = 0.5 + 0.2 * Math.cos((y - t) * 0.17) + random.nextGaussian() * 0.005;
                        picture.u()[y * picture.uvStride() + x] = (short) Math.clamp(Math.round(u * max), 0, max);
                        picture.v()[y * picture.uvStride() + x] = (short) Math.clamp(Math.round(v * max), 0, max);
                    }
                }
            }
            list.add(picture);
        }
        return list;
    }

    /** Decodes packets with Smalt at their own depth, returning each frame's planes. */
    static List<short[][]> decodeSmalt(List<Packet> packets) {
        var frames = new ArrayList<short[][]>();
        try (var decoder = new H264Decoder(TRACK, H264DecoderOptions.builder().highBitDepth(true).build())) {
            for (var packet : packets) {
                decoder.decode(packet.withTrackId(1), (frame, damaged) -> frames.add(planes(frame.picture())));
            }
            decoder.flush((frame, damaged) -> frames.add(planes(frame.picture())));
        }
        return frames;
    }

    static short[][] planes(Picture picture) {
        if (picture instanceof Yuv16Picture yuv) {
            return new short[][]{copy(yuv.y(), yuv.yStride(), yuv.width(), yuv.height()),
                    yuv.chromaFormat() == ChromaFormat.MONOCHROME ? new short[0]
                            : copy(yuv.u(), yuv.uvStride(), yuv.chromaWidth(), yuv.chromaHeight()),
                    yuv.chromaFormat() == ChromaFormat.MONOCHROME ? new short[0]
                            : copy(yuv.v(), yuv.uvStride(), yuv.chromaWidth(), yuv.chromaHeight())};
        }
        var yuv = (YuvPicture) picture;
        return new short[][]{copy(yuv.y(), yuv.yStride(), yuv.width(), yuv.height()),
                yuv.chromaFormat() == ChromaFormat.MONOCHROME ? new short[0]
                        : copy(yuv.u(), yuv.uvStride(), yuv.chromaWidth(), yuv.chromaHeight()),
                yuv.chromaFormat() == ChromaFormat.MONOCHROME ? new short[0]
                        : copy(yuv.v(), yuv.uvStride(), yuv.chromaWidth(), yuv.chromaHeight())};
    }

    static short[] copy(short[] plane, int stride, int width, int height) {
        var out = new short[width * height];
        for (var y = 0; y < height; y++) {
            System.arraycopy(plane, y * stride, out, y * width, width);
        }
        return out;
    }

    static short[] copy(byte[] plane, int stride, int width, int height) {
        var out = new short[width * height];
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                out[y * width + x] = (short) (plane[y * stride + x] & 0xFF);
            }
        }
        return out;
    }

    static void assertSame(List<short[][]> expected, List<short[][]> actual, String what) {
        assertEquals(expected.size(), actual.size(), what + ": frame count");
        for (var i = 0; i < expected.size(); i++) {
            for (var c = 0; c < 3; c++) {
                assertArrayEquals(expected.get(i)[c], actual.get(i)[c], what + ": frame " + i + ", plane " + c);
            }
        }
    }

    /** The ffmpeg pixel format of a format and depth; ffmpeg decodes 4:0:0 as 4:2:0 with neutral chroma. */
    static String pixelFormat(ChromaFormat format, int depth) {
        var base = switch (format) {
            case MONOCHROME, YUV420 -> "yuv420p";
            case YUV422 -> "yuv422p";
            default -> "yuv444p";
        };
        return depth == 8 ? base : base + depth + "le";
    }

    /** Decodes an Annex B stream with ffmpeg, failing on errors, into planes like {@link #planes}. */
    static List<short[][]> decodeFfmpeg(String name, byte[] stream, ChromaFormat format, int depth, int width,
                                        int height) {
        Oracle.assume(Oracle.Tool.FFMPEG);
        var file = EncoderHarness.write(name + ".264", stream);
        var raw = Oracle.decodeVideo(file, List.of("-err_detect", "explode", "-f", "h264"), pixelFormat(format, depth), 0);
        var bytes = depth > 8 ? 2 : 1;
        var mono = format == ChromaFormat.MONOCHROME;
        var cw = format == ChromaFormat.YUV444 ? width : (width + 1) / 2;
        var ch = format == ChromaFormat.YUV420 || mono ? (height + 1) / 2 : height;
        var chroma = cw * ch;
        var frameSize = (width * height + 2 * chroma) * bytes;
        assertEquals(0, raw.length % frameSize, name + ": whole frames");
        var frames = new ArrayList<short[][]>();
        for (var offset = 0; offset < raw.length; offset += frameSize) {
            frames.add(new short[][]{read(raw, offset, width * height, bytes),
                    mono ? new short[0] : read(raw, offset + width * height * bytes, chroma, bytes),
                    mono ? new short[0] : read(raw, offset + (width * height + chroma) * bytes, chroma, bytes)});
        }
        return frames;
    }

    static short[] read(byte[] raw, int offset, int count, int bytes) {
        var out = new short[count];
        for (var i = 0; i < count; i++) {
            out[i] = bytes == 1 ? (short) (raw[offset + i] & 0xFF)
                    : (short) ((raw[offset + 2 * i] & 0xFF) | (raw[offset + 2 * i + 1] & 0xFF) << 8);
        }
        return out;
    }

    /** Returns the PSNR of decoded luma against the source luma at the stream's depth. */
    static double psnr(Picture source, short[] luma, int depth) {
        var yuv = (Yuv16Picture) source;
        double error = 0;
        for (var y = 0; y < yuv.height(); y++) {
            for (var x = 0; x < yuv.width(); x++) {
                var d = yuv.y()[y * yuv.yStride() + x] - luma[y * yuv.width() + x];
                error += (double) d * d;
            }
        }
        var max = (1 << depth) - 1;
        var mse = error / (yuv.width() * yuv.height());
        return mse == 0 ? 99 : 10 * Math.log10((double) max * max / mse);
    }

    static Stream<Arguments> formats() {
        return Stream.of(
                Arguments.of(H264Profile.HIGH, ChromaFormat.MONOCHROME, 8),
                Arguments.of(H264Profile.HIGH_10, ChromaFormat.YUV420, 10),
                Arguments.of(H264Profile.HIGH_10, ChromaFormat.YUV420, 9),
                Arguments.of(H264Profile.HIGH_10, ChromaFormat.MONOCHROME, 10),
                Arguments.of(H264Profile.HIGH_422, ChromaFormat.YUV422, 8),
                Arguments.of(H264Profile.HIGH_422, ChromaFormat.YUV422, 10),
                Arguments.of(H264Profile.HIGH_444_PREDICTIVE, ChromaFormat.YUV444, 8),
                Arguments.of(H264Profile.HIGH_444_PREDICTIVE, ChromaFormat.YUV444, 12),
                Arguments.of(H264Profile.HIGH_444_PREDICTIVE, ChromaFormat.YUV444, 14),
                Arguments.of(H264Profile.HIGH_444_PREDICTIVE, ChromaFormat.YUV420, 12),
                Arguments.of(H264Profile.HIGH_444_PREDICTIVE, ChromaFormat.YUV422, 14));
    }

    static Stream<Arguments> formatsAndCoders() {
        return formats().flatMap(arguments -> Stream.of(false, true).map(cavlc -> Arguments.of(arguments.get()[0],
                arguments.get()[1], arguments.get()[2], cavlc)));
    }

    @Nested
    @DisplayName("progressive frames")
    class Progressive {
        @ParameterizedTest(name = "{0} {1} {2}-bit, CAVLC {3}")
        @MethodSource("com.github.auties00.smalt.video.h264.encoder.ExtendedFormatsTest#formatsAndCoders")
        @DisplayName("without deblocking, Smalt and ffmpeg decode exactly the encoder's reconstruction")
        void reconstruction(H264Profile profile, ChromaFormat format, int depth, boolean cavlc) {
            var width = 80;
            var height = 48;
            var options = options(profile, format, depth).cavlc(cavlc).build();
            var run = run(width, height, options, frames(width, height, format, depth, 6, depth, false), false);
            var smalt = decodeSmalt(run.packets());
            assertSame(run.encoderRecon(), smalt, "Smalt");
            if (Oracle.find(Oracle.Tool.FFMPEG).isPresent()) {
                assertSame(run.encoderRecon(), decodeFfmpeg("ext-" + profile + "-" + format + "-" + depth + "-" + cavlc,
                        run.stream(), format, depth, width, height), "ffmpeg");
            }
        }

        @ParameterizedTest(name = "{0} {1} {2}-bit")
        @MethodSource("com.github.auties00.smalt.video.h264.encoder.ExtendedFormatsTest#formats")
        @DisplayName("with deblocking, Smalt and ffmpeg agree and the pictures stay close to the source")
        void deblocked(H264Profile profile, ChromaFormat format, int depth) {
            var width = 96;
            var height = 64;
            var options = options(profile, format, depth).quality(20).build();
            var sources = frames(width, height, format, depth, 6, 3, false);
            var run = run(width, height, options, sources, true);
            var smalt = decodeSmalt(run.packets());
            assertSame(run.decoded(), smalt, "Smalt");
            for (var i = 0; i < smalt.size(); i++) {
                var quality = psnr(sources.get(i), smalt.get(i)[0], depth);
                assertTrue(quality > 38, "frame " + i + " luma PSNR " + quality);
            }
            if (Oracle.find(Oracle.Tool.FFMPEG).isPresent()) {
                assertSame(smalt, decodeFfmpeg("extdb-" + profile + "-" + format + "-" + depth, run.stream(), format,
                        depth, width, height), "ffmpeg");
            }
        }

        @Test
        @DisplayName("the sequence parameter set signals the profile, chroma format and bit depth")
        void signaling() {
            var options = options(H264Profile.HIGH_422, ChromaFormat.YUV422, 10).build();
            var run = run(64, 32, options, frames(64, 32, ChromaFormat.YUV422, 10, 1, 1, false), true);
            var info = H264StreamInfo.probe(run.packets().getFirst().data());
            assertEquals(122, info.profileIdc());
            assertEquals(ChromaFormat.YUV422, info.chromaFormat());
            assertEquals(10, info.bitDepthLuma());
        }
    }

    @Nested
    @DisplayName("lossless coding")
    class Lossless {
        static Stream<Arguments> lossless() {
            return Stream.of(
                    Arguments.of(ChromaFormat.YUV444, 8, false), Arguments.of(ChromaFormat.YUV444, 8, true),
                    Arguments.of(ChromaFormat.YUV444, 10, false), Arguments.of(ChromaFormat.YUV444, 14, true),
                    Arguments.of(ChromaFormat.YUV420, 8, false), Arguments.of(ChromaFormat.YUV420, 8, true),
                    Arguments.of(ChromaFormat.YUV422, 12, false), Arguments.of(ChromaFormat.YUV422, 12, true),
                    Arguments.of(ChromaFormat.MONOCHROME, 8, true), Arguments.of(ChromaFormat.MONOCHROME, 10, false));
        }

        @ParameterizedTest(name = "{0} {1}-bit, CAVLC {2}")
        @MethodSource("lossless")
        @DisplayName("Smalt and ffmpeg decode the input samples exactly")
        void exact(ChromaFormat format, int depth, boolean cavlc) {
            var width = 64;
            var height = 48;
            var options = options(H264Profile.HIGH_444_PREDICTIVE, format, depth).lossless(true).cavlc(cavlc).build();
            var sources = frames(width, height, format, depth, 5, 7, false);
            var run = run(width, height, options, sources, true);
            var expected = new ArrayList<short[][]>();
            for (var source : sources) {
                expected.add(planes(source));
            }
            assertSame(expected, decodeSmalt(run.packets()), "Smalt");
            if (Oracle.find(Oracle.Tool.FFMPEG).isPresent()) {
                assertSame(expected, decodeFfmpeg("lossless-" + format + "-" + depth + "-" + cavlc, run.stream(), format,
                        depth, width, height), "ffmpeg");
            }
        }

        @Test
        @DisplayName("noise at the largest depth still decodes exactly, through the I_PCM fallback where needed")
        void noise() {
            var width = 48;
            var height = 32;
            var random = new Random(5);
            var sources = new ArrayList<Picture>();
            for (var f = 0; f < 3; f++) {
                var picture = Yuv16Picture.allocate(width, height, ChromaFormat.YUV444, 14, false,
                        ColorInfo.BT709_LIMITED);
                for (var i = 0; i < width * height; i++) {
                    picture.y()[i] = (short) random.nextInt(1 << 14);
                    picture.u()[i] = (short) random.nextInt(1 << 14);
                    picture.v()[i] = (short) random.nextInt(1 << 14);
                }
                sources.add(picture);
            }
            var options = options(H264Profile.HIGH_444_PREDICTIVE, ChromaFormat.YUV444, 14).lossless(true).build();
            var run = run(width, height, options, sources, true);
            var expected = new ArrayList<short[][]>();
            for (var source : sources) {
                expected.add(planes(source));
            }
            assertSame(expected, decodeSmalt(run.packets()), "Smalt");
        }

        @Test
        @DisplayName("RGB input with the identity matrix round trips losslessly through 4:4:4")
        void identityRgb() {
            var color = new ColorInfo(MatrixCoefficients.IDENTITY, ColorRange.FULL, ColorPrimaries.BT709,
                    TransferCharacteristics.SRGB);
            var options = options(H264Profile.HIGH_444_PREDICTIVE, ChromaFormat.YUV444, 8).lossless(true).color(color)
                    .build();
            var rgba = RgbaPicture.allocate(32, 32, false);
            var random = new Random(9);
            random.nextBytes(rgba.pixels());
            for (var i = 3; i < rgba.pixels().length; i += 4) {
                rgba.pixels()[i] = (byte) 255;
            }
            var packets = new ArrayList<Packet>();
            try (var encoder = new H264Encoder(32, 32, options)) {
                encoder.encode(new VideoFrame(rgba, 0, 3000, false), packets::add);
            }
            var frames = new ArrayList<VideoFrame>();
            try (var decoder = new H264Decoder(TRACK, H264DecoderOptions.defaults())) {
                decoder.decode(packets.getFirst().withTrackId(1), (frame, damaged) -> frames.add(frame));
                decoder.flush((frame, damaged) -> frames.add(frame));
            }
            var back = ImageOps.toRgba(frames.getFirst().picture());
            for (var y = 0; y < 32; y++) {
                for (var x = 0; x < 32; x++) {
                    for (var c = 0; c < 3; c++) {
                        assertEquals(rgba.pixels()[y * rgba.stride() + 4 * x + c],
                                back.pixels()[y * back.stride() + 4 * x + c], "pixel " + x + "," + y + " channel " + c);
                    }
                }
            }
        }
    }

    @Nested
    @DisplayName("interlaced frames")
    class Interlaced {
        static Stream<Arguments> structures() {
            return Stream.of(
                    Arguments.of(H264FieldCoding.MBAFF, FieldOrder.TOP_FIRST, H264Profile.MAIN, ChromaFormat.YUV420, 8,
                            false),
                    Arguments.of(H264FieldCoding.MBAFF, FieldOrder.BOTTOM_FIRST, H264Profile.HIGH, ChromaFormat.YUV420,
                            8, true),
                    Arguments.of(H264FieldCoding.MBAFF, FieldOrder.TOP_FIRST, H264Profile.HIGH_422, ChromaFormat.YUV422,
                            10, false),
                    Arguments.of(H264FieldCoding.MBAFF, FieldOrder.TOP_FIRST, H264Profile.HIGH_444_PREDICTIVE,
                            ChromaFormat.YUV444, 10, true),
                    Arguments.of(H264FieldCoding.FIELD_PICTURES, FieldOrder.TOP_FIRST, H264Profile.MAIN,
                            ChromaFormat.YUV420, 8, false),
                    Arguments.of(H264FieldCoding.FIELD_PICTURES, FieldOrder.BOTTOM_FIRST, H264Profile.HIGH_10,
                            ChromaFormat.YUV420, 10, true),
                    Arguments.of(H264FieldCoding.FIELD_PICTURES, FieldOrder.TOP_FIRST, H264Profile.HIGH_444_PREDICTIVE,
                            ChromaFormat.YUV444, 8, false),
                    Arguments.of(H264FieldCoding.FIELD_PICTURES, FieldOrder.TOP_FIRST, H264Profile.HIGH,
                            ChromaFormat.MONOCHROME, 8, true));
        }

        @ParameterizedTest(name = "{0} {1} {2} {3} {4}-bit, CAVLC {5}")
        @MethodSource("structures")
        @DisplayName("without deblocking, Smalt and ffmpeg decode exactly the encoder's reconstruction")
        void reconstruction(H264FieldCoding coding, FieldOrder order, H264Profile profile, ChromaFormat format,
                            int depth, boolean cavlc) {
            var width = 80;
            var height = 64;
            var options = options(profile, format, depth).fieldOrder(order).fieldCoding(coding).cavlc(cavlc).build();
            var run = run(width, height, options, frames(width, height, format, depth, 6, 11, true), false);
            var smalt = decodeSmalt(run.packets());
            assertSame(run.encoderRecon(), smalt, "Smalt");
            if (Oracle.find(Oracle.Tool.FFMPEG).isPresent()) {
                assertSame(run.encoderRecon(), decodeFfmpeg("int-" + coding + "-" + order + "-" + format + "-" + depth
                        + "-" + cavlc, run.stream(), format, depth, width, height), "ffmpeg");
            }
        }

        @ParameterizedTest(name = "{0} {1} {2} {3} {4}-bit, CAVLC {5}")
        @MethodSource("structures")
        @DisplayName("with deblocking, Smalt and ffmpeg agree and the frames stay close to the source")
        void deblocked(H264FieldCoding coding, FieldOrder order, H264Profile profile, ChromaFormat format, int depth,
                       boolean cavlc) {
            var width = 96;
            var height = 64;
            var options = options(profile, format, depth).fieldOrder(order).fieldCoding(coding).cavlc(cavlc).quality(20)
                    .build();
            var sources = frames(width, height, format, depth, 6, 13, true);
            var run = run(width, height, options, sources, true);
            var smalt = decodeSmalt(run.packets());
            assertSame(run.decoded(), smalt, "Smalt");
            for (var i = 0; i < smalt.size(); i++) {
                var quality = psnr(sources.get(i), smalt.get(i)[0], depth);
                assertTrue(quality > 36, "frame " + i + " luma PSNR " + quality);
            }
            if (Oracle.find(Oracle.Tool.FFMPEG).isPresent()) {
                assertSame(smalt, decodeFfmpeg("intdb-" + coding + "-" + order + "-" + format + "-" + depth + "-"
                        + cavlc, run.stream(), format, depth, width, height), "ffmpeg");
            }
        }

        static Stream<Arguments> stillStructures() {
            return Stream.of(
                    Arguments.of(H264FieldCoding.MBAFF, false, ChromaFormat.YUV420, 8),
                    Arguments.of(H264FieldCoding.MBAFF, true, ChromaFormat.YUV420, 8),
                    Arguments.of(H264FieldCoding.MBAFF, false, ChromaFormat.YUV444, 10),
                    Arguments.of(H264FieldCoding.FIELD_PICTURES, false, ChromaFormat.YUV422, 10),
                    Arguments.of(H264FieldCoding.FIELD_PICTURES, true, ChromaFormat.YUV420, 8));
        }

        /**
         * Builds frames of a still background, with a different texture in each third so that the pairs
         * code as frames or as fields, and a small square that moves between the fields of a frame.
         */
        static List<Picture> stillFrames(int width, int height, ChromaFormat format, int depth, int count) {
            var list = new ArrayList<Picture>();
            var max = (1 << depth) - 1;
            var background = new Random(77);
            var noise = new double[width * height];
            for (var i = 0; i < noise.length; i++) {
                noise[i] = background.nextGaussian() * 0.01;
            }
            for (var f = 0; f < count; f++) {
                var picture = Yuv16Picture.allocate(width, height, format, depth, false, ColorInfo.BT709_LIMITED);
                for (var y = 0; y < height; y++) {
                    var t = (y & 1) == 1 ? f + 0.5 : f;
                    for (var x = 0; x < width; x++) {
                        double value;
                        if (x < width / 3) {
                            value = 0.3 + 0.4 * ((y & 1) == 0 ? 0.2 : 0.8);
                        } else if (x < 2 * width / 3) {
                            value = 0.3 + 0.3 * Math.sin(y * 0.3) + 0.1 * Math.cos(x * 0.2);
                        } else {
                            value = 0.5 + 0.2 * Math.sin((x + y) * 0.1);
                        }
                        value += noise[y * width + x];
                        if (Math.abs(x - 8 - 4 * t) < 4 && Math.abs(y - height / 2.0) < 6) {
                            value = 0.9;
                        }
                        picture.y()[y * picture.yStride() + x] = (short) Math.clamp(Math.round(value * max), 0, max);
                    }
                }
                if (format != ChromaFormat.MONOCHROME) {
                    for (var y = 0; y < picture.chromaHeight(); y++) {
                        for (var x = 0; x < picture.chromaWidth(); x++) {
                            var u = 0.5 + 0.2 * Math.sin(x * 0.2);
                            var v = 0.5 + 0.2 * Math.cos(y * 0.17);
                            picture.u()[y * picture.uvStride() + x] = (short) Math.clamp(Math.round(u * max), 0, max);
                            picture.v()[y * picture.uvStride() + x] = (short) Math.clamp(Math.round(v * max), 0, max);
                        }
                    }
                }
                list.add(picture);
            }
            return list;
        }

        @ParameterizedTest(name = "{0}, CAVLC {1}, {2} {3}-bit")
        @MethodSource("stillStructures")
        @DisplayName("a still background of skipped macroblocks, frame and field pairs mixed, decodes exactly in Smalt and ffmpeg")
        void skipped(H264FieldCoding coding, boolean cavlc, ChromaFormat format, int depth) {
            var width = 96;
            var height = 64;
            var profile = format == ChromaFormat.YUV420 && depth == 8 ? H264Profile.HIGH
                    : format == ChromaFormat.YUV444 ? H264Profile.HIGH_444_PREDICTIVE : H264Profile.HIGH_422;
            // adaptive quantization would code the faint noise of the flat thirds finer in the fields that
            // predict from the other parity; the test measures skipping
            var options = options(profile, format, depth).fieldOrder(FieldOrder.TOP_FIRST).fieldCoding(coding)
                    .cavlc(cavlc).keyframeInterval(12).adaptiveQuantization(H264AdaptiveQuantization.NONE).build();
            for (var deblocking : new boolean[]{false, true}) {
                var run = run(width, height, options, stillFrames(width, height, format, depth, 8), deblocking);
                var smalt = decodeSmalt(run.packets());
                assertSame(deblocking ? run.decoded() : run.encoderRecon(), smalt, "Smalt, deblocking " + deblocking);
                var intra = run.packets().getFirst().size();
                for (var i = 2; i < run.packets().size(); i++) {
                    assertTrue(run.packets().get(i).size() * 4 < intra, "P picture " + i + " of "
                            + run.packets().get(i).size() + " bytes skips the background, I picture " + intra);
                }
                if (Oracle.find(Oracle.Tool.FFMPEG).isPresent()) {
                    assertSame(smalt, decodeFfmpeg("still-" + coding + "-" + cavlc + "-" + format + "-" + deblocking,
                            run.stream(), format, depth, width, height), "ffmpeg, deblocking " + deblocking);
                }
            }
        }

        @Test
        @DisplayName("interlaced coding is rejected for Constrained Baseline and above level 4.1")
        void validation() {
            assertThrows(IllegalArgumentException.class, () -> H264EncoderOptions.builder()
                    .profile(H264Profile.CONSTRAINED_BASELINE).fieldOrder(FieldOrder.TOP_FIRST).build());
            var options = H264EncoderOptions.builder().fieldOrder(FieldOrder.TOP_FIRST).level(H264Level.LEVEL_5_1)
                    .build();
            assertThrows(IllegalArgumentException.class, () -> new H264Encoder(1920, 1080, options));
        }
    }

    @Nested
    @DisplayName("stream tools in the extended engine")
    class Tools {
        static Stream<Arguments> configurations() {
            return Stream.of(
                    Arguments.of(H264FieldCoding.MBAFF, false), Arguments.of(H264FieldCoding.MBAFF, true),
                    Arguments.of(H264FieldCoding.FIELD_PICTURES, false), Arguments.of(null, true));
        }

        @ParameterizedTest(name = "{0}, CAVLC {1}")
        @MethodSource("configurations")
        @DisplayName("fixed slices and a slice size limit keep the reconstruction exact and every multi-macroblock slice in size")
        void slices(H264FieldCoding coding, boolean cavlc) {
            var width = 96;
            var height = 64;
            var builder = options(H264Profile.HIGH_10, ChromaFormat.YUV420, 10).cavlc(cavlc).slices(2)
                    .maxSliceBytes(400).quality(18);
            if (coding != null) {
                builder.fieldOrder(FieldOrder.TOP_FIRST).fieldCoding(coding);
            }
            var run = run(width, height, builder.build(), frames(width, height, ChromaFormat.YUV420, 10, 5, 2,
                    coding != null), false);
            assertSame(run.encoderRecon(), decodeSmalt(run.packets()), "Smalt");
            // first_mb_in_slice counts macroblock pairs in MBAFF frames and the macroblocks of one field in
            // field pictures, so a picture has 24 slice units progressive and 12 otherwise
            var unitsPerPicture = coding == null ? 24 : 12;
            var slices = 0;
            var limited = 0;
            for (var packet : run.packets()) {
                var firsts = new ArrayList<Integer>();
                var sizes = new ArrayList<Integer>();
                for (var unit : StreamToolsTest.units(packet.data())) {
                    var type = unit[0] & 0x1F;
                    if (type == 1 || type == 5) {
                        firsts.add(StreamToolsTest.firstMb(unit));
                        sizes.add(unit.length);
                        slices++;
                    }
                }
                for (var k = 0; k < sizes.size(); k++) {
                    var end = k + 1 < sizes.size() && firsts.get(k + 1) > firsts.get(k) ? firsts.get(k + 1)
                            : unitsPerPicture;
                    assertTrue(end > firsts.get(k), "slices in raster order");
                    if (end - firsts.get(k) > 1) {
                        limited++;
                        assertTrue(sizes.get(k) <= 400, "slice of " + (end - firsts.get(k)) + " units has "
                                + sizes.get(k) + " bytes");
                    }
                }
            }
            assertTrue(slices > 4 * run.packets().size(), "the limit divides the pictures, " + slices + " slices");
            assertTrue(limited > 0, "some slices hold several units");
            if (Oracle.find(Oracle.Tool.FFMPEG).isPresent()) {
                assertSame(run.encoderRecon(), decodeFfmpeg("extslices-" + coding + "-" + cavlc, run.stream(),
                        ChromaFormat.YUV420, 10, width, height), "ffmpeg");
            }
        }

        @ParameterizedTest(name = "CAVLC {0}")
        @MethodSource("com.github.auties00.smalt.video.h264.encoder.ExtendedFormatsTest#booleans")
        @DisplayName("a slice size limit on a lossless stream with I_PCM fallbacks keeps it exact and in size")
        void losslessSlices(boolean cavlc) {
            var width = 64;
            var height = 48;
            var random = new Random(41);
            var sources = new ArrayList<Picture>();
            for (var picture : frames(width, height, ChromaFormat.YUV444, 12, 4, 8, false)) {
                var yuv = (Yuv16Picture) picture;
                // a smooth left half codes small macroblocks, noise in the right half drives them to I_PCM
                for (var y = 0; y < height; y++) {
                    for (var x = 0; x < width; x++) {
                        var smooth = x < width / 2;
                        yuv.y()[y * yuv.yStride() + x] = (short) (smooth ? 1000 + 8 * x + 4 * y
                                : random.nextInt(1 << 12));
                        yuv.u()[y * yuv.uvStride() + x] = (short) (smooth ? 2048 + 2 * y : random.nextInt(1 << 12));
                        yuv.v()[y * yuv.uvStride() + x] = (short) (smooth ? 2048 - 2 * x : random.nextInt(1 << 12));
                    }
                }
                sources.add(yuv);
            }
            var limit = 1500;
            var options = options(H264Profile.HIGH_444_PREDICTIVE, ChromaFormat.YUV444, 12).lossless(true)
                    .cavlc(cavlc).maxSliceBytes(limit).build();
            var run = run(width, height, options, sources, true);
            var expected = new ArrayList<short[][]>();
            for (var source : sources) {
                expected.add(planes(source));
            }
            assertSame(expected, decodeSmalt(run.packets()), "Smalt");
            var multi = 0;
            for (var packet : run.packets()) {
                var firsts = new ArrayList<Integer>();
                var sizes = new ArrayList<Integer>();
                for (var unit : StreamToolsTest.units(packet.data())) {
                    var type = unit[0] & 0x1F;
                    if (type == 1 || type == 5) {
                        firsts.add(StreamToolsTest.firstMb(unit));
                        sizes.add(unit.length);
                    }
                }
                firsts.add(12);
                for (var k = 0; k < sizes.size(); k++) {
                    if (firsts.get(k + 1) - firsts.get(k) > 1) {
                        multi++;
                        assertTrue(sizes.get(k) <= limit, "slice of " + sizes.get(k) + " bytes");
                    }
                }
            }
            assertTrue(multi > 0, "some slices hold several macroblocks");
            if (Oracle.find(Oracle.Tool.FFMPEG).isPresent()) {
                assertSame(expected, decodeFfmpeg("lossless-slices-" + cavlc, run.stream(), ChromaFormat.YUV444, 12,
                        width, height), "ffmpeg");
            }
        }

        static Stream<Arguments> layered() {
            return Stream.of(
                    Arguments.of(null, 3, false), Arguments.of(null, 4, true),
                    Arguments.of(H264FieldCoding.MBAFF, 3, false), Arguments.of(H264FieldCoding.FIELD_PICTURES, 3, true),
                    Arguments.of(H264FieldCoding.FIELD_PICTURES, 4, false));
        }

        @ParameterizedTest(name = "{0}, {1} layers, CAVLC {2}")
        @MethodSource("layered")
        @DisplayName("temporal layers of an extended stream decode on their own, by dropping packets and by maxTemporalId")
        void layers(H264FieldCoding coding, int layers, boolean cavlc) {
            var width = 64;
            var height = 64;
            var builder = options(H264Profile.HIGH_422, ChromaFormat.YUV422, 10).temporalLayers(layers)
                    .keyframeInterval(16).cavlc(cavlc);
            if (coding != null) {
                builder.fieldOrder(FieldOrder.TOP_FIRST).fieldCoding(coding);
            }
            var run = run(width, height, builder.build(), frames(width, height, ChromaFormat.YUV422, 10, 20, 4,
                    coding != null), true);
            var full = decodeSmalt(run.packets());
            assertSame(run.decoded(), full, "Smalt");
            var period = 1 << (layers - 1);
            for (var i = 0; i < run.packets().size(); i++) {
                var position = (i % 16) % period;
                var layer = position == 0 ? 0 : layers - 1 - Integer.numberOfTrailingZeros(position);
                var packet = run.packets().get(i);
                var unitList = StreamToolsTest.units(packet.data());
                var sliceCount = 0;
                for (var k = 0; k < unitList.size(); k++) {
                    var type = unitList.get(k)[0] & 0x1F;
                    if (type == 1 || type == 5) {
                        sliceCount++;
                        var prefix = unitList.get(k - 1);
                        assertEquals(14, prefix[0] & 0x1F, "a prefix NAL unit precedes every slice, packet " + i);
                        assertEquals(prefix[0] & 0x60, unitList.get(k)[0] & 0x60, "the prefix has the slice's nal_ref_idc");
                        assertEquals(layer, (prefix[3] & 0xFF) >> 5, "temporal_id of packet " + i);
                        assertEquals(layer == layers - 1, (unitList.get(k)[0] & 0x60) == 0,
                                "only the highest layer is unreferenced, packet " + i);
                    }
                }
                assertTrue(sliceCount > 0, "packet " + i + " has slices");
                assertEquals(layer == layers - 1, packet.flags().contains(PacketFlag.DISCARDABLE),
                        "the highest layer is discardable, packet " + i);
            }
            for (var top = 0; top < layers - 1; top++) {
                var kept = new ArrayList<Packet>();
                var expected = new ArrayList<short[][]>();
                for (var i = 0; i < run.packets().size(); i++) {
                    var position = (i % 16) % period;
                    var layer = position == 0 ? 0 : layers - 1 - Integer.numberOfTrailingZeros(position);
                    if (layer <= top) {
                        kept.add(run.packets().get(i));
                        expected.add(full.get(i));
                    }
                }
                assertSame(expected, decodeSmalt(kept), "layers to " + top);
                var selected = new ArrayList<short[][]>();
                try (var decoder = new H264Decoder(TRACK, H264DecoderOptions.builder().highBitDepth(true)
                        .maxTemporalId(top).build())) {
                    for (var packet : run.packets()) {
                        decoder.decode(packet.withTrackId(1), (frame, damaged) -> selected.add(planes(frame.picture())));
                    }
                    decoder.flush((frame, damaged) -> selected.add(planes(frame.picture())));
                }
                assertSame(expected, selected, "maxTemporalId " + top);
                if (Oracle.find(Oracle.Tool.FFMPEG).isPresent()) {
                    var stream = new ByteArrayOutputStream();
                    kept.forEach(packet -> stream.writeBytes(packet.data()));
                    assertSame(expected, decodeFfmpeg("extlayers-" + coding + "-" + layers + "-" + top, stream.toByteArray(),
                            ChromaFormat.YUV422, 10, width, height), "ffmpeg layers to " + top);
                }
            }
        }

        static Stream<Arguments> refreshed() {
            return Stream.of(Arguments.of(null, ChromaFormat.YUV444), Arguments.of(H264FieldCoding.MBAFF, ChromaFormat.YUV420),
                    Arguments.of(H264FieldCoding.FIELD_PICTURES, ChromaFormat.YUV422));
        }

        @ParameterizedTest(name = "{0} {1}")
        @MethodSource("refreshed")
        @DisplayName("intra refresh of an extended stream recovers exactly from a cycle start")
        void refresh(H264FieldCoding coding, ChromaFormat format) {
            var width = 128;
            var height = 64;
            var profile = format == ChromaFormat.YUV420 ? H264Profile.HIGH : format == ChromaFormat.YUV422
                    ? H264Profile.HIGH_422 : H264Profile.HIGH_444_PREDICTIVE;
            var builder = options(profile, format, 8).intraRefresh(4);
            if (coding != null) {
                builder.fieldOrder(FieldOrder.BOTTOM_FIRST).fieldCoding(coding);
            }
            var run = run(width, height, builder.build(), frames(width, height, format, 8, 14, 6, coding != null), true);
            var cycleStarts = new ArrayList<Integer>();
            for (var i = 0; i < run.packets().size(); i++) {
                var packet = run.packets().get(i);
                assertEquals(i == 0, packet.keyframe(), "only the first picture is an IDR picture, packet " + i);
                for (var unit : StreamToolsTest.units(packet.data())) {
                    if ((unit[0] & 0x1F) == 6 && (unit[1] & 0xFF) == 6 && !cycleStarts.contains(i)) {
                        cycleStarts.add(i);
                    }
                }
            }
            // 8 columns refreshed over 4 frames: bands of 2 columns, cycles of 4 pictures after the IDR picture
            assertEquals(List.of(1, 5, 9, 13), cycleStarts, "recovery point SEI at every cycle start");
            var full = decodeSmalt(run.packets());
            assertSame(run.decoded(), full, "Smalt");
            var start = 5;
            var tail = new ArrayList<>(run.packets().subList(start, run.packets().size()));
            var first = StreamToolsTest.units(run.packets().getFirst().data());
            var head = new ByteArrayOutputStream();
            for (var unit : first.subList(0, 2)) {
                head.writeBytes(new byte[]{0, 0, 0, 1});
                head.writeBytes(unit);
            }
            head.writeBytes(tail.getFirst().data());
            tail.set(0, tail.getFirst().withData(head.toByteArray()));
            var joined = decodeSmalt(tail);
            assertEquals(run.packets().size() - start, joined.size(), "frames output from the recovery point");
            assertTrue(!java.util.Arrays.equals(full.get(start)[0], joined.getFirst()[0]),
                    "the first picture of the cycle shows the missing references");
            for (var i = 3; i < joined.size(); i++) {
                for (var c = 0; c < 3; c++) {
                    assertArrayEquals(full.get(start + i)[c], joined.get(i)[c], "joined frame " + i + " plane " + c);
                }
            }
        }

        @Test
        @DisplayName("the sample aspect ratio and color description reach the extended stream")
        void vui() {
            var color = new ColorInfo(MatrixCoefficients.YCGCO, ColorRange.FULL, ColorPrimaries.BT709,
                    TransferCharacteristics.BT709);
            var options = options(H264Profile.HIGH_10, ChromaFormat.YUV420, 10).sampleAspectRatio(new Rational(40, 33))
                    .color(color).build();
            var run = run(32, 32, options, frames(32, 32, ChromaFormat.YUV420, 10, 1, 1, false), true);
            var info = H264StreamInfo.probe(run.packets().getFirst().data());
            assertEquals(new Rational(40, 33), info.sampleAspectRatio().orElseThrow());
            assertEquals(MatrixCoefficients.YCGCO, info.color().matrix());
            assertEquals(ColorRange.FULL, info.color().range());
        }
    }

    @Nested
    @DisplayName("profiles and formats")
    class Profiles {
        static Stream<Arguments> combinations() {
            var list = new ArrayList<Arguments>();
            for (var profile : H264Profile.values()) {
                for (var format : new ChromaFormat[]{ChromaFormat.MONOCHROME, ChromaFormat.YUV420, ChromaFormat.YUV422,
                        ChromaFormat.YUV444}) {
                    for (var depth : new int[]{8, 10, 14}) {
                        for (var lossless : new boolean[]{false, true}) {
                            list.add(Arguments.of(profile, format, depth, lossless));
                        }
                    }
                }
            }
            return list.stream();
        }

        @ParameterizedTest(name = "{0} {1} {2}-bit lossless {3}")
        @MethodSource("combinations")
        @DisplayName("each profile accepts exactly the formats of its table row")
        void table(H264Profile profile, ChromaFormat format, int depth, boolean lossless) {
            var high = profile.profileIdc() >= 100;
            var formatOk = switch (format) {
                case YUV420 -> true;
                case MONOCHROME -> high;
                case YUV422 -> profile == H264Profile.HIGH_422 || profile == H264Profile.HIGH_444_PREDICTIVE;
                default -> profile == H264Profile.HIGH_444_PREDICTIVE;
            };
            var maxDepth = switch (profile) {
                case CONSTRAINED_BASELINE, MAIN, HIGH -> 8;
                case HIGH_10, HIGH_422 -> 10;
                case HIGH_444_PREDICTIVE -> 14;
            };
            var accepted = formatOk && depth <= maxDepth && (!lossless || profile == H264Profile.HIGH_444_PREDICTIVE);
            var builder = H264EncoderOptions.builder().profile(profile).chromaFormat(format).bitDepth(depth)
                    .lossless(lossless);
            if (accepted) {
                assertEquals(profile, builder.build().profile());
            } else {
                assertThrows(IllegalArgumentException.class, builder::build);
            }
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.github.auties00.smalt.video.h264.encoder.ExtendedFormatsTest#highProfiles")
        @DisplayName("the 8-bit engine signals any High profile for 8-bit 4:2:0 streams, which decode exactly")
        void eightBitHighProfiles(H264Profile profile) {
            var options = H264EncoderOptions.forRealtime().toBuilder().profile(profile).build();
            var run = StreamToolsTest.run(64, 48, options.toBuilder().packetFormat(H264PacketFormat.ANNEX_B).build(),
                    EncoderHarness.sequence(64, 48, 4, 2, 2.0));
            assertEquals(profile.profileIdc(), H264StreamInfo.probe(run.packets().getFirst().data()).profileIdc());
            StreamToolsTest.assertFrames(run.reconstructions(), StreamToolsTest.decodeSmalt(run.packets(),
                    H264DecoderOptions.defaults()), "Smalt");
        }

        @Test
        @DisplayName("4:4:0 is rejected")
        void yuv440() {
            assertThrows(IllegalArgumentException.class, () -> H264EncoderOptions.builder()
                    .profile(H264Profile.HIGH_444_PREDICTIVE).chromaFormat(ChromaFormat.YUV440).build());
        }
    }

    @Nested
    @DisplayName("input conversion and cropping")
    class Conversion {
        static Stream<Arguments> conversions() {
            return Stream.of(
                    Arguments.of(ChromaFormat.YUV444, 8, ChromaFormat.YUV444, 10),
                    Arguments.of(ChromaFormat.YUV420, 8, ChromaFormat.YUV444, 8),
                    Arguments.of(ChromaFormat.YUV422, 12, ChromaFormat.YUV422, 10),
                    Arguments.of(ChromaFormat.YUV444, 10, ChromaFormat.YUV420, 12),
                    Arguments.of(ChromaFormat.YUV420, 10, ChromaFormat.MONOCHROME, 10));
        }

        @ParameterizedTest(name = "{0} {1}-bit into {2} {3}-bit")
        @MethodSource("conversions")
        @DisplayName("a lossless stream holds the input converted as ImageOps.toYuv16 converts it")
        void converted(ChromaFormat inputFormat, int inputDepth, ChromaFormat format, int depth) {
            var width = 48;
            var height = 32;
            List<Picture> inputs;
            if (inputDepth == 8) {
                inputs = new ArrayList<>();
                for (var picture : frames(width, height, inputFormat, 8, 3, 1, false)) {
                    inputs.add(ImageOps.toEightBit(picture));
                }
            } else {
                inputs = frames(width, height, inputFormat, inputDepth, 3, 1, false);
            }
            var options = options(H264Profile.HIGH_444_PREDICTIVE, format, depth).lossless(true).build();
            var run = run(width, height, options, inputs, true);
            var expected = new ArrayList<short[][]>();
            for (var input : inputs) {
                var color = input instanceof YuvPicture yuv ? yuv.color() : ((Yuv16Picture) input).color();
                expected.add(planes(ImageOps.toYuv16(input, format, color, depth)));
            }
            assertSame(expected, decodeSmalt(run.packets()), "Smalt");
        }

        static Stream<Arguments> sizes() {
            return Stream.of(
                    Arguments.of(ChromaFormat.YUV444, FieldOrder.PROGRESSIVE, 63, 37),
                    Arguments.of(ChromaFormat.YUV422, FieldOrder.PROGRESSIVE, 70, 51),
                    Arguments.of(ChromaFormat.YUV420, FieldOrder.TOP_FIRST, 70, 50),
                    Arguments.of(ChromaFormat.MONOCHROME, FieldOrder.BOTTOM_FIRST, 33, 35),
                    Arguments.of(ChromaFormat.YUV444, FieldOrder.TOP_FIRST, 17, 19));
        }

        @ParameterizedTest(name = "{0} {1} {2}x{3}")
        @MethodSource("sizes")
        @DisplayName("sizes that are not whole macroblocks are cropped to the display size the chroma format allows")
        void cropped(ChromaFormat format, FieldOrder order, int width, int height) {
            var profile = format == ChromaFormat.YUV420 ? H264Profile.MAIN : H264Profile.HIGH_444_PREDICTIVE;
            var options = options(profile, format, 8).fieldOrder(order).build();
            var run = run(width, height, options, frames(width, height, format, 8, 3, 5, order.isInterlaced()), false);
            // the decoder reuses its pictures' buffers, so each frame is copied before the next decode
            var planes = new ArrayList<short[][]>();
            var sizes = new ArrayList<int[]>();
            try (var decoder = new H264Decoder(TRACK, H264DecoderOptions.builder().highBitDepth(true).build())) {
                for (var packet : run.packets()) {
                    decoder.decode(packet.withTrackId(1), (frame, damaged) -> {
                        planes.add(planes(frame.picture()));
                        sizes.add(new int[]{frame.picture().width(), frame.picture().height()});
                    });
                }
                decoder.flush((frame, damaged) -> {
                    planes.add(planes(frame.picture()));
                    sizes.add(new int[]{frame.picture().width(), frame.picture().height()});
                });
            }
            var cropX = format == ChromaFormat.YUV420 || format == ChromaFormat.YUV422 ? 2 : 1;
            var cropY = (format == ChromaFormat.YUV420 ? 2 : 1) * (order.isInterlaced() ? 2 : 1);
            assertEquals(Math.ceilDiv(width, cropX) * cropX, sizes.getFirst()[0]);
            assertEquals(Math.ceilDiv(height, cropY) * cropY, sizes.getFirst()[1]);
            assertSame(run.encoderRecon(), run.decoded(), "the encoder's decoder");
            assertSame(run.encoderRecon(), planes, "Smalt");
        }
    }

    @Nested
    @DisplayName("in containers")
    class InContainers {
        static Stream<Arguments> streams() {
            return Stream.of(
                    Arguments.of(H264Profile.HIGH_10, ChromaFormat.YUV420, 10, FieldOrder.PROGRESSIVE, false),
                    Arguments.of(H264Profile.HIGH_422, ChromaFormat.YUV422, 10, FieldOrder.TOP_FIRST, false),
                    Arguments.of(H264Profile.HIGH_444_PREDICTIVE, ChromaFormat.YUV444, 12, FieldOrder.PROGRESSIVE, true),
                    Arguments.of(H264Profile.HIGH, ChromaFormat.MONOCHROME, 8, FieldOrder.BOTTOM_FIRST, false));
        }

        @ParameterizedTest(name = "{0} {1} {2}-bit {3} lossless {4}")
        @MethodSource("streams")
        @DisplayName("an MP4 of length-prefixed packets and the avcC record passes the validators and decodes in Smalt and ffmpeg")
        void mp4(H264Profile profile, ChromaFormat format, int depth, FieldOrder order, boolean lossless) {
            var width = 64;
            var height = 64;
            var options = options(profile, format, depth).packetFormat(H264PacketFormat.LENGTH_PREFIXED)
                    .fieldOrder(order).lossless(lossless).build();
            var packets = new ArrayList<Packet>();
            var decoded = new ArrayList<short[][]>();
            byte[] config;
            try (var encoder = new H264Encoder(width, height, options)) {
                config = encoder.codecConfig().orElseThrow();
                var pts = 0L;
                for (var picture : frames(width, height, format, depth, 6, 3, order.isInterlaced())) {
                    encoder.encode(new VideoFrame(picture, pts, 3000, false), packets::add);
                    pts += 3000;
                    decoded.add(encoder.wide().lastReconstruction());
                }
            }
            assertEquals(profile.profileIdc(), config[1] & 0xFF, "AVCProfileIndication");
            var track = VideoTrack.builder().id(1).codec(Codec.H264).timeBase(new Rational(1, 90_000)).width(width)
                    .height(height).frameRate(new Rational(30, 1)).codecConfig(config).build();
            var sink = com.github.auties00.smalt.io.MediaSink.memory();
            try (var muxer = new com.github.auties00.smalt.container.mp4.Mp4Muxer(sink)) {
                muxer.addTrack(track);
                for (var packet : packets) {
                    muxer.writePacket(packet.withTrackId(1));
                }
                muxer.finish();
            }
            var file = EncoderHarness.write("ext-mp4-" + profile + "-" + format + "-" + depth + "-" + order + ".mp4",
                    sink.toByteArray());
            Oracle.assertDecodesCleanly(file);
            var smalt = new ArrayList<short[][]>();
            try (var decoder = new H264Decoder(track, H264DecoderOptions.builder().highBitDepth(true).build())) {
                for (var packet : packets) {
                    decoder.decode(packet.withTrackId(1), (frame, damaged) -> smalt.add(planes(frame.picture())));
                }
                decoder.flush((frame, damaged) -> smalt.add(planes(frame.picture())));
            }
            assertSame(decoded, smalt, "Smalt");
        }
    }

    @Nested
    @DisplayName("rate control and the encoder contract")
    class Contract {
        static long bits(List<Packet> packets, int from, int to) {
            var sum = 0L;
            for (var i = from; i < to; i++) {
                sum += 8L * packets.get(i).size();
            }
            return sum;
        }

        @ParameterizedTest(name = "interlaced {0}")
        @MethodSource("com.github.auties00.smalt.video.h264.encoder.ExtendedFormatsTest#booleans")
        @DisplayName("a target bitrate is followed, also after setTargetBitrate, and the stream still decodes")
        void bitrate(boolean interlaced) {
            var width = 176;
            var height = 144;
            var builder = options(H264Profile.HIGH_10, ChromaFormat.YUV420, 10)
                    .rateControl(H264RateControl.AVERAGE_BITRATE).bitrate(400_000).keyframeInterval(30);
            if (interlaced) {
                builder.fieldOrder(FieldOrder.TOP_FIRST);
            }
            var sources = frames(width, height, ChromaFormat.YUV420, 10, 120, 21, interlaced);
            var packets = new ArrayList<Packet>();
            var decoded = new ArrayList<short[][]>();
            try (var encoder = new H264Encoder(width, height, builder.build())) {
                var pts = 0L;
                for (var i = 0; i < sources.size(); i++) {
                    if (i == 60) {
                        encoder.setTargetBitrate(150_000);
                    }
                    encoder.encode(new VideoFrame(sources.get(i), pts, 3000, false), packets::add);
                    decoded.add(encoder.wide().lastReconstruction());
                    pts += 3000;
                }
                assertEquals(120, encoder.stats().framesEncoded());
                assertEquals(150_000, encoder.stats().targetBitrate());
            }
            // 60 frames at 30 fps last two seconds
            var first = bits(packets, 0, 60) / 2.0;
            var second = bits(packets, 75, 120) / 1.5;
            assertTrue(first > 0.6 * 400_000 && first < 1.4 * 400_000, "first half at " + first + " bps");
            assertTrue(second > 0.5 * 150_000 && second < 1.5 * 150_000, "second half at " + second + " bps");
            assertSame(decoded, decodeSmalt(packets), "Smalt");
        }

        @Test
        @DisplayName("a key frame request yields an IDR picture, and the counters follow the packets")
        void keyframeRequest() {
            var options = options(H264Profile.HIGH_444_PREDICTIVE, ChromaFormat.YUV444, 12).keyframeInterval(100)
                    .build();
            var packets = new ArrayList<Packet>();
            try (var encoder = new H264Encoder(48, 32, options)) {
                var sources = frames(48, 32, ChromaFormat.YUV444, 12, 8, 3, false);
                for (var i = 0; i < sources.size(); i++) {
                    if (i == 5) {
                        encoder.requestKeyframe();
                        encoder.requestKeyframe();
                    }
                    encoder.encode(new VideoFrame(sources.get(i), i * 3000L, 3000, false), packets::add);
                }
                var stats = encoder.stats();
                assertEquals(8, stats.framesEncoded());
                assertEquals(2, stats.keyframesEncoded());
                assertEquals(2, stats.keyframeRequests());
                assertEquals(packets.stream().mapToLong(Packet::size).sum(), stats.bytesEncoded());
                assertEquals(0, stats.lastTemporalLayer());
            }
            for (var i = 0; i < packets.size(); i++) {
                assertEquals(i == 0 || i == 5, packets.get(i).keyframe(), "IDR picture at " + i);
                assertEquals(i == 0 || i == 5, StreamToolsTest.units(packets.get(i).data()).stream()
                        .anyMatch(unit -> (unit[0] & 0x1F) == 5), "IDR slice at " + i);
            }
        }

        @Test
        @DisplayName("a picture of another size, a timestamp that does not increase and a closed encoder are rejected")
        void rejected() {
            var options = options(H264Profile.HIGH_422, ChromaFormat.YUV422, 10).build();
            var encoder = new H264Encoder(32, 32, options);
            var picture = frames(32, 32, ChromaFormat.YUV422, 10, 1, 1, false).getFirst();
            var other = frames(48, 32, ChromaFormat.YUV422, 10, 1, 1, false).getFirst();
            assertThrows(IllegalArgumentException.class,
                    () -> encoder.encode(new VideoFrame(other, 0, 3000, false), packet -> { }));
            encoder.encode(new VideoFrame(picture, 3000, 3000, false), packet -> { });
            assertThrows(IllegalArgumentException.class,
                    () -> encoder.encode(new VideoFrame(picture, 3000, 3000, false), packet -> { }));
            encoder.close();
            assertThrows(IllegalStateException.class,
                    () -> encoder.encode(new VideoFrame(picture, 6000, 3000, false), packet -> { }));
        }
    }

    static Stream<Boolean> booleans() {
        return Stream.of(false, true);
    }

    static Stream<H264Profile> highProfiles() {
        return Stream.of(H264Profile.HIGH, H264Profile.HIGH_10, H264Profile.HIGH_422, H264Profile.HIGH_444_PREDICTIVE);
    }
}
