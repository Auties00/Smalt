package com.github.auties00.smalt.video.ffv1;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.container.avi.AviMuxer;
import com.github.auties00.smalt.container.matroska.MatroskaMuxer;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.Rgba16Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.Scratch;
import com.github.auties00.smalt.testkit.Tolerance;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.VideoTrack;
import com.github.auties00.smalt.video.FieldOrder;
import com.github.auties00.smalt.video.FrameSideData;
import com.github.auties00.smalt.video.VideoDecoderOptions;
import com.github.auties00.smalt.video.VideoDecoders;
import com.github.auties00.smalt.video.VideoFrame;
import com.github.auties00.smalt.video.intra.IntraTesting;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Decodes FFV1 streams made by ffmpeg's encoder for every version, coder, context model, slice layout and pixel
 * format it produces, and compares every sample with ffmpeg's decode: FFV1 is lossless, so the comparison is exact.
 * Smalt's encoder is checked the other way: ffmpeg's decode of every coding mode Smalt writes, in Matroska and AVI,
 * must return the source samples exactly.
 */
@DisplayName("FFV1 against ffmpeg")
class Ffv1DifferentialTest {
    /** FFV1 is lossless: every sample must match. */
    static final Tolerance EXACT = Tolerance.exact();

    static final int WIDTH = 161;

    static final int HEIGHT = 123;

    static java.nio.file.Path sample(String pixelFormat, int width, int height, List<String> codec) {
        return sample(pixelFormat, width, height, "", codec);
    }

    static java.nio.file.Path sample(String pixelFormat, int width, int height, String filters, List<String> codec) {
        var output = new ArrayList<>(List.of("-filter_complex", "[0][1]blend=all_mode=average,scale=" + width + ":"
                + height + ",format=" + pixelFormat + filters, "-frames:v", "3", "-c:v", "ffv1"));
        output.addAll(codec);
        output.addAll(List.of("-an", "-f", "matroska"));
        return Fixtures.ffmpeg("mkv", List.of("-f", "lavfi", "-i", "testsrc2=size=320x240:rate=25",
                "-f", "lavfi", "-i", "cellauto=size=320x240:rate=25"), output);
    }

    static Stream<Arguments> streams() {
        var cases = new ArrayList<Arguments>();
        String[][] formats = {
                {"yuv420p", "8"}, {"yuv422p", "8"}, {"yuv444p", "8"}, {"yuv440p", "8"}, {"yuv410p", "8"},
                {"yuv411p", "8"}, {"gray", "8"}, {"yuva420p", "8"}, {"yuva444p", "8"}, {"yuv420p10le", "10"},
                {"yuv422p12le", "12"}, {"yuv444p16le", "16"}, {"gray10le", "10"}, {"gray16le", "16"},
                {"yuva444p10le", "10"}, {"yuva420p16le", "16"}, {"gbrp", "8"}, {"bgr0", "8"}, {"gbrap", "8"},
                {"gbrp9le", "9"}, {"gbrp10le", "10"}, {"gbrp12le", "12"}, {"gbrp14le", "14"}, {"gbrp16le", "16"},
                {"gbrap10le", "10"}, {"gbrap12le", "12"}, {"gbrap16le", "16"}};
        for (var level : new int[]{0, 1, 3}) {
            for (var format : formats) {
                var depth = Integer.parseInt(format[1]);
                if (level == 0 && (depth > 8 || format[0].startsWith("gbrap") || format[0].equals("bgr0"))) {
                    continue;
                }
                for (var coder : level == 3 ? new String[]{"0", "1", "2"} : new String[]{"0", "2"}) {
                    if (depth > 8 && coder.equals("0") && level == 3) {
                        continue;
                    }
                    var options = new ArrayList<>(List.of("-level", Integer.toString(level), "-coder", coder,
                            "-pix_fmt", format[0], "-g", "2"));
                    if (level == 3) {
                        options.addAll(List.of("-slices", format[0].startsWith("gbr") ? "6" : "4", "-slicecrc", "1"));
                    }
                    cases.add(Arguments.of("level " + level + " coder " + coder + " " + format[0], format[0], depth,
                            options));
                }
            }
        }
        cases.add(Arguments.of("level 3 large context 12 slices", "yuv420p", 8,
                List.of("-level", "3", "-coder", "1", "-context", "1", "-slices", "12", "-pix_fmt", "yuv420p")));
        cases.add(Arguments.of("level 3 no slice CRC 16 slices", "yuv422p10le", 10,
                List.of("-level", "3", "-coder", "2", "-slicecrc", "0", "-slices", "16", "-pix_fmt", "yuv422p10le")));
        cases.add(Arguments.of("level 1 large context golomb", "yuv444p", 8,
                List.of("-level", "1", "-coder", "0", "-context", "1", "-pix_fmt", "yuv444p", "-g", "3")));
        cases.add(Arguments.of("level 3 inter frames", "gbrp10le", 10,
                List.of("-level", "3", "-coder", "1", "-g", "3", "-slices", "4", "-pix_fmt", "gbrp10le")));
        return cases.stream();
    }

    /** Lays a decoded picture out as ffmpeg's raw video of a pixel format. */
    static byte[] raw(Picture picture, String pixelFormat, int depth) {
        var out = new ByteArrayOutputStream();
        var withAlpha = pixelFormat.startsWith("yuva") || pixelFormat.startsWith("gbrap");
        switch (picture) {
            case RgbaPicture rgba -> {
                if (pixelFormat.equals("bgr0")) {
                    for (var y = 0; y < rgba.height(); y++) {
                        for (var x = 0; x < rgba.width(); x++) {
                            var at = y * rgba.stride() + x * 4;
                            out.write(rgba.pixels()[at + 2]);
                            out.write(rgba.pixels()[at + 1]);
                            out.write(rgba.pixels()[at]);
                            out.write(0);
                        }
                    }
                } else {
                    for (var channel : withAlpha ? new int[]{1, 2, 0, 3} : new int[]{1, 2, 0}) {
                        for (var y = 0; y < rgba.height(); y++) {
                            for (var x = 0; x < rgba.width(); x++) {
                                out.write(rgba.pixels()[y * rgba.stride() + x * 4 + channel]);
                            }
                        }
                    }
                }
            }
            case Rgba16Picture rgba -> {
                var max = (1 << depth) - 1;
                for (var channel : withAlpha ? new int[]{1, 2, 0, 3} : new int[]{1, 2, 0}) {
                    for (var y = 0; y < rgba.height(); y++) {
                        for (var x = 0; x < rgba.width(); x++) {
                            var v16 = rgba.pixels()[y * rgba.stride() + x * 4 + channel] & 0xFFFF;
                            var v = (int) Math.round(v16 * (double) max / 65535);
                            out.write(v & 0xFF);
                            out.write(v >>> 8);
                        }
                    }
                }
            }
            case Yuv16Picture yuv -> {
                var gray = pixelFormat.startsWith("gray");
                var copy = IntraTesting.raw(yuv, withAlpha);
                if (gray) {
                    out.write(copy, 0, yuv.width() * yuv.height() * 2);
                } else {
                    out.writeBytes(copy);
                }
            }
            case YuvPicture yuv -> {
                var gray = pixelFormat.startsWith("gray");
                var copy = IntraTesting.raw(yuv, withAlpha);
                if (gray) {
                    out.write(copy, 0, yuv.width() * yuv.height());
                } else {
                    out.writeBytes(copy);
                }
            }
            default -> throw new IllegalArgumentException(picture.toString());
        }
        return out.toByteArray();
    }

    /**
     * Repeats the chroma samples of ffmpeg's 4:1:1 or 4:1:0 raw frames as the decoder does, to 4:2:2 or 4:2:0.
     */
    static byte[] widenChroma(byte[] frames, String pixelFormat, int width, int height) {
        var cw = (width + 3) / 4;
        var ch = pixelFormat.equals("yuv410p") ? (height + 3) / 4 : height;
        var tw = (width + 1) / 2;
        var th = pixelFormat.equals("yuv410p") ? (height + 1) / 2 : height;
        var frame = width * height + 2 * cw * ch;
        var out = new ByteArrayOutputStream();
        for (var at = 0; at + frame <= frames.length; at += frame) {
            out.write(frames, at, width * height);
            for (var plane = 0; plane < 2; plane++) {
                var base = at + width * height + plane * cw * ch;
                for (var y = 0; y < th; y++) {
                    var sy = pixelFormat.equals("yuv410p") ? y / 2 : y;
                    for (var x = 0; x < tw; x++) {
                        out.write(frames[base + sy * cw + x / 2]);
                    }
                }
            }
        }
        return out.toByteArray();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("streams")
    @DisplayName("decodes ffmpeg's streams sample for sample as ffmpeg does")
    void decodesFfmpeg(String label, String pixelFormat, int depth, List<String> options) {
        // ffmpeg slices a vertically subsampled frame only on chroma rows, which an odd height prevents
        var height = verticallySubsampled(pixelFormat) ? 124 : HEIGHT;
        var file = sample(pixelFormat, WIDTH, height, options);
        var stream = IntraTesting.demux(file);
        assertEquals(Codec.FFV1, stream.track().codec(), label);
        var decoder = VideoDecoders.create(stream.track(), VideoDecoderOptions.builder().highBitDepth(true).build());
        assertInstanceOf(Ffv1Decoder.class, decoder);
        var ours = new ByteArrayOutputStream();
        for (var packet : stream.packets()) {
            decoder.decode(packet, frame -> ours.writeBytes(raw(frame.picture(), pixelFormat, depth)));
        }
        var expected = IntraTesting.ffmpegDecode(file, pixelFormat, 0);
        if (pixelFormat.equals("yuv411p") || pixelFormat.equals("yuv410p")) {
            expected = widenChroma(expected, pixelFormat, WIDTH, height);
        }
        var actual = ours.toByteArray();
        assertEquals(expected.length, actual.length, label);
        EXACT.check(label, Tolerance.Difference.of(expected, actual));
    }

    static boolean verticallySubsampled(String pixelFormat) {
        return pixelFormat.contains("420") || pixelFormat.contains("440") || pixelFormat.contains("410");
    }

    @Test
    @DisplayName("reads the field order and sample aspect ratio of version 3 slices")
    void sliceHeaderFields() {
        var file = sample("yuv420p", 64, 48, ",setsar=4/3,setfield=tff", List.of("-level", "3", "-slices", "4",
                "-flags", "+ilme+ildct", "-top", "1"));
        var stream = IntraTesting.demux(file);
        var decoder = new Ffv1Decoder(stream.track());
        decoder.decode(stream.packets().getFirst(), frame -> { });
        assertEquals(new Rational(4, 3), decoder.sampleAspectRatio().orElseThrow());
        assertEquals(FieldOrder.TOP_FIRST, decoder.fieldOrder().orElseThrow());
    }

    static Picture source(int width, int height, String layout, int depth, boolean alpha, long seed) {
        var random = new Random(seed);
        var max = (1 << depth) - 1;
        switch (layout) {
            case "rgb" -> {
                if (depth == 8) {
                    var picture = RgbaPicture.allocate(width, height, alpha);
                    var pixels = picture.pixels();
                    for (var y = 0; y < height; y++) {
                        for (var x = 0; x < width; x++) {
                            var at = y * picture.stride() + x * 4;
                            pixels[at] = (byte) (x * 255 / width + random.nextInt(8));
                            pixels[at + 1] = (byte) (y * 255 / height);
                            pixels[at + 2] = (byte) ((x ^ y) + random.nextInt(4));
                            pixels[at + 3] = (byte) (alpha ? (x + y) * 3 : 255);
                        }
                    }
                    return picture;
                }
                var picture = Rgba16Picture.allocate(width, height, alpha);
                var pixels = picture.pixels();
                for (var y = 0; y < height; y++) {
                    for (var x = 0; x < width; x++) {
                        var at = y * picture.stride() + x * 4;
                        int[] values = {(x * max / width + random.nextInt(8)) & max, y * max / height,
                                ((x * 37 ^ y * 11) + random.nextInt(16)) & max, alpha ? (x + y) * 5 & max : max};
                        for (var c = 0; c < 4; c++) {
                            pixels[at + c] = (short) Math.round(values[c] * 65535.0 / max);
                        }
                    }
                }
                return picture;
            }
            default -> {
                var format = switch (layout) {
                    case "420" -> ChromaFormat.YUV420;
                    case "422" -> ChromaFormat.YUV422;
                    case "440" -> ChromaFormat.YUV440;
                    case "444" -> ChromaFormat.YUV444;
                    default -> ChromaFormat.MONOCHROME;
                };
                if (depth == 8) {
                    var picture = YuvPicture.allocate(width, height, format, alpha, ColorInfo.BT601_LIMITED);
                    fill(picture.y(), picture.yStride(), width, height, 255, random);
                    if (format != ChromaFormat.MONOCHROME) {
                        fill(picture.u(), picture.uvStride(), picture.chromaWidth(), picture.chromaHeight(), 255, random);
                        fill(picture.v(), picture.uvStride(), picture.chromaWidth(), picture.chromaHeight(), 255, random);
                    }
                    if (alpha) {
                        fill(picture.alpha(), picture.alphaStride(), width, height, 255, random);
                    }
                    return picture;
                }
                var picture = Yuv16Picture.allocate(width, height, format, depth, alpha, ColorInfo.BT601_LIMITED);
                fill(picture.y(), picture.yStride(), width, height, max, random);
                if (format != ChromaFormat.MONOCHROME) {
                    fill(picture.u(), picture.uvStride(), picture.chromaWidth(), picture.chromaHeight(), max, random);
                    fill(picture.v(), picture.uvStride(), picture.chromaWidth(), picture.chromaHeight(), max, random);
                }
                if (alpha) {
                    fill(picture.alpha(), picture.alphaStride(), width, height, max, random);
                }
                return picture;
            }
        }
    }

    static void fill(byte[] plane, int stride, int width, int height, int max, Random random) {
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                plane[y * stride + x] = (byte) Math.min(max, (x * 3 + y * 2) % 200 + random.nextInt(40));
            }
        }
    }

    static void fill(short[] plane, int stride, int width, int height, int max, Random random) {
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                plane[y * stride + x] = (short) (((x * 13 + y * 7) * (max / 255 + 1) + random.nextInt(max / 16 + 2))
                        & max);
            }
        }
    }

    static Stream<Arguments> encodings() {
        var cases = new ArrayList<Arguments>();
        Object[][] layouts = {{"420", 8, false, "yuv420p"}, {"422", 8, false, "yuv422p"}, {"444", 8, true, "yuva444p"},
                {"440", 8, false, "yuv440p"}, {"gray", 8, false, "gray"}, {"420", 10, false, "yuv420p10le"},
                {"444", 16, true, "yuva444p16le"}, {"gray", 16, false, "gray16le"}, {"rgb", 8, false, "gbrp"},
                {"rgb", 8, true, "gbrap"}, {"rgb", 10, false, "gbrp10le"}, {"rgb", 16, true, "gbrap16le"},
                {"rgb", 12, false, "gbrp12le"}, {"422", 12, false, "yuv422p12le"}};
        for (var version : new int[]{0, 1, 3}) {
            for (var coder : Ffv1Coder.values()) {
                for (var layout : layouts) {
                    var depth = (int) layout[1];
                    var rgbAlpha = layout[0].equals("rgb") && (boolean) layout[2];
                    if (version == 0 && (depth > 8 || rgbAlpha)) {
                        continue;
                    }
                    cases.add(Arguments.of("v" + version + " " + coder + " " + layout[3], version, coder, layout[0],
                            depth, layout[2], layout[3]));
                }
            }
        }
        return cases.stream();
    }

    static Ffv1EncoderOptions options(int version, Ffv1Coder coder, String layout, int depth, boolean alpha) {
        var builder = Ffv1EncoderOptions.builder().version(version).coder(coder).bitDepth(depth).alpha(alpha)
                .keyframeInterval(2);
        if (layout.equals("rgb")) {
            builder.rgb(true);
        } else {
            builder.chromaFormat(switch (layout) {
                case "420" -> ChromaFormat.YUV420;
                case "422" -> ChromaFormat.YUV422;
                case "440" -> ChromaFormat.YUV440;
                case "444" -> ChromaFormat.YUV444;
                default -> ChromaFormat.MONOCHROME;
            });
        }
        if (version == 3) {
            builder.slices(4);
        }
        return builder.build();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("encodings")
    @DisplayName("writes streams ffmpeg decodes to the source samples, which Smalt's decoder returns too")
    void encodesLosslessly(String label, int version, Ffv1Coder coder, String layout, int depth, boolean alpha,
                           String pixelFormat) throws IOException {
        var options = options(version, coder, layout, depth, alpha);
        var encoder = new Ffv1Encoder(WIDTH, HEIGHT, options);
        var pictures = List.of(source(WIDTH, HEIGHT, layout, depth, alpha, 1), source(WIDTH, HEIGHT, layout, depth,
                alpha, 2), source(WIDTH, HEIGHT, layout, depth, alpha, 3));
        var packets = new ArrayList<Packet>();
        for (var i = 0; i < pictures.size(); i++) {
            encoder.encode(new VideoFrame(pictures.get(i), i * 3000L, 3000L, true,
                    FrameSideData.NONE), packet -> packets.add(packet.withTrackId(1)));
        }
        var track = VideoTrack.builder().id(1).codec(Codec.FFV1).timeBase(encoder.timeBase()).width(WIDTH)
                .height(HEIGHT).frameRate(new Rational(30, 1)).codecConfig(encoder.codecConfig().orElse(null)).build();
        var expected = new ByteArrayOutputStream();
        pictures.forEach(picture -> expected.writeBytes(raw(picture, pixelFormat, depth)));
        var decoder = new Ffv1Decoder(track, VideoDecoderOptions.builder().highBitDepth(true).build());
        var ours = new ByteArrayOutputStream();
        for (var packet : packets) {
            decoder.decode(packet, frame -> ours.writeBytes(raw(frame.picture(), pixelFormat, depth)));
        }
        EXACT.check(label + ", Smalt's decode", Tolerance.Difference.of(expected.toByteArray(), ours.toByteArray()));
        var sink = MediaSink.memory();
        var muxer = new MatroskaMuxer(sink);
        muxer.addTrack(track);
        packets.forEach(muxer::writePacket);
        muxer.finish();
        var file = Scratch.directory("ffv1").resolve("ffv1-" + label.replace(' ', '-') + ".mkv");
        Files.write(file, sink.toByteArray());
        // ffmpeg decodes 8-bit RGB with alpha to bgra, and its conversion to gbrap rounds odd alpha values up
        var decoded = pixelFormat.equals("gbrap") ? bgraToGbrap(IntraTesting.ffmpegDecode(file, "bgra", 0))
                : IntraTesting.ffmpegDecode(file, pixelFormat, 0);
        EXACT.check(label + ", ffmpeg's decode", Tolerance.Difference.of(expected.toByteArray(), decoded));
    }

    static byte[] bgraToGbrap(byte[] bgra) {
        var frame = WIDTH * HEIGHT;
        var out = new byte[bgra.length];
        for (var base = 0; base < bgra.length; base += frame * 4) {
            for (var i = 0; i < frame; i++) {
                var at = base + i * 4;
                out[base + i] = bgra[at + 1];
                out[base + frame + i] = bgra[at];
                out[base + 2 * frame + i] = bgra[at + 2];
                out[base + 3 * frame + i] = bgra[at + 3];
            }
        }
        return out;
    }

    @Test
    @DisplayName("writes AVI files ffmpeg decodes losslessly")
    void avi() throws IOException {
        var options = options(3, Ffv1Coder.RANGE_CUSTOM_STATES, "422", 10, false);
        var encoder = new Ffv1Encoder(WIDTH, HEIGHT, options);
        var picture = source(WIDTH, HEIGHT, "422", 10, false, 9);
        var packets = new ArrayList<Packet>();
        for (var i = 0; i < 2; i++) {
            encoder.encode(new VideoFrame(picture, i * 3000L, 3000L, true,
                    FrameSideData.NONE), packet -> packets.add(packet.withTrackId(1)));
        }
        var track = VideoTrack.builder().id(1).codec(Codec.FFV1).timeBase(encoder.timeBase()).width(WIDTH)
                .height(HEIGHT).frameRate(new Rational(30, 1)).codecConfig(encoder.codecConfig().orElseThrow()).build();
        var sink = MediaSink.memory();
        var muxer = new AviMuxer(sink);
        muxer.addTrack(track);
        packets.forEach(muxer::writePacket);
        muxer.finish();
        var file = Scratch.directory("ffv1").resolve("ffv1.avi");
        Files.write(file, sink.toByteArray());
        var expected = new ByteArrayOutputStream();
        expected.writeBytes(raw(picture, "yuv422p10le", 10));
        expected.writeBytes(raw(picture, "yuv422p10le", 10));
        EXACT.check("AVI", Tolerance.Difference.of(expected.toByteArray(),
                IntraTesting.ffmpegDecode(file, "yuv422p10le", 0)));
        var stream = IntraTesting.demux(file);
        assertEquals(Codec.FFV1, stream.track().codec());
        assertArrayEquals(encoder.codecConfig().orElseThrow(), stream.track().codecConfig().orElseThrow());
    }

    @ParameterizedTest(name = "level {0}")
    @org.junit.jupiter.params.provider.ValueSource(ints = {1, 3})
    @DisplayName("reads ffmpeg's QuickTime files, the configuration record of version 3 from the glbl box")
    void quickTime(int level) {
        var file = Fixtures.ffmpeg("mov", List.of("-f", "lavfi", "-i", "testsrc2=size=" + WIDTH + "x" + HEIGHT
                + ":rate=25"), List.of("-frames:v", "3", "-c:v", "ffv1", "-level", Integer.toString(level), "-g", "2",
                "-pix_fmt", "yuv444p"));
        var stream = IntraTesting.demux(file);
        assertEquals(Codec.FFV1, stream.track().codec());
        assertEquals(level == 3, stream.track().codecConfig().isPresent());
        var decoder = VideoDecoders.create(stream.track(), VideoDecoderOptions.defaults());
        var ours = new ByteArrayOutputStream();
        for (var packet : stream.packets()) {
            decoder.decode(packet, frame -> ours.writeBytes(raw(frame.picture(), "yuv444p", 8)));
        }
        EXACT.check("QuickTime level " + level, Tolerance.Difference.of(IntraTesting.ffmpegDecode(file, "yuv444p", 0),
                ours.toByteArray()));
    }

    @Nested
    @DisplayName("robustness")
    class Robustness {
        @Test
        @DisplayName("raises MediaException.Malformed for corrupt frames and recovers at the next key frame")
        void corrupt() {
            var options = options(3, Ffv1Coder.RANGE, "420", 8, false);
            var encoder = new Ffv1Encoder(64, 48, options.toBuilder().keyframeInterval(1).build());
            var packets = new ArrayList<Packet>();
            for (var i = 0; i < 3; i++) {
                encoder.encode(new VideoFrame(source(64, 48, "420", 8, false, i), i * 3000L, 3000L, true,
                        FrameSideData.NONE), packets::add);
            }
            var track = VideoTrack.builder().id(1).codec(Codec.FFV1).timeBase(encoder.timeBase()).width(64).height(48)
                    .codecConfig(encoder.codecConfig().orElseThrow()).build();
            var random = new Random(5);
            for (var trial = 0; trial < 100; trial++) {
                var decoder = new Ffv1Decoder(track);
                var data = packets.get(1).data().clone();
                data[random.nextInt(data.length)] ^= (byte) (1 + random.nextInt(255));
                var corrupted = packets.get(1).withData(data);
                assertThrows(MediaException.Malformed.class, () -> decoder.decode(corrupted, frame -> { }));
                var ok = new boolean[1];
                decoder.decode(packets.get(2), frame -> ok[0] = true);
                assertTrue(ok[0]);
            }
        }

        @Test
        @DisplayName("raises MediaException.Malformed for an empty or truncated frame")
        void truncated() {
            var encoder = new Ffv1Encoder(64, 48, options(1, Ffv1Coder.GOLOMB_RICE, "444", 8, false));
            var packets = new ArrayList<Packet>();
            encoder.encode(new VideoFrame(source(64, 48, "444", 8, false, 1), 0, 3000L, true,
                    FrameSideData.NONE), packets::add);
            var track = VideoTrack.builder().id(1).codec(Codec.FFV1).timeBase(encoder.timeBase()).width(64).height(48)
                    .build();
            var data = packets.getFirst().data();
            for (var length : new int[]{0, 1}) {
                var decoder = new Ffv1Decoder(track);
                var cut = packets.getFirst().withData(java.util.Arrays.copyOf(data, length));
                assertThrows(MediaException.Malformed.class, () -> decoder.decode(cut, frame -> { }));
            }
            // a truncated version 1 frame has no CRC: it decodes or fails cleanly
            for (var length = 2; length < data.length; length += Math.max(1, data.length / 40)) {
                var decoder = new Ffv1Decoder(track);
                var cut = packets.getFirst().withData(java.util.Arrays.copyOf(data, length));
                try {
                    decoder.decode(cut, frame -> { });
                } catch (MediaException.Malformed expected) {
                    // the clean failure
                }
            }
        }
    }
}
