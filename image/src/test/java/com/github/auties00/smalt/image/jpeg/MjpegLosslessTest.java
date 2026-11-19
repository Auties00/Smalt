package com.github.auties00.smalt.image.jpeg;

import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Interlacing;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.VideoTrack;
import com.github.auties00.smalt.video.VideoDecoderOptions;
import com.github.auties00.smalt.video.VideoDecoders;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * Decodes lossless (SOF3) Motion-JPEG through {@link MjpegDecoder}: the ffmpeg {@code ljpeg} files of
 * {@code jpeg/mjpeg/} (see {@code tools/testdata/mjpeg/make-vectors.sh}) decode to exactly the frames they were
 * coded from, whose MD5 {@code mjpeg-md5.csv} holds, and ffmpeg's encoder output of further layouts and predictors
 * decodes to its source when ffmpeg is present.
 */
@DisplayName("MjpegDecoder lossless frames")
class MjpegLosslessTest {
    record Vector(String file, String pixelFormat, int width, int height, int frames, String md5) {
        @Override
        public String toString() {
            return file;
        }
    }

    static List<Vector> vectors() {
        var vectors = new ArrayList<Vector>();
        try (var reader = new BufferedReader(new InputStreamReader(
                MjpegLosslessTest.class.getResourceAsStream("mjpeg-md5.csv"), StandardCharsets.US_ASCII))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                var f = line.split(";");
                vectors.add(new Vector(f[0], f[1], Integer.parseInt(f[2]), Integer.parseInt(f[3]),
                        Integer.parseInt(f[4]), f[5]));
            }
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
        return vectors;
    }

    private static byte[] resource(String name) {
        try (var input = MjpegLosslessTest.class.getResourceAsStream(name)) {
            return input.readAllBytes();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    // The frames in the layout of ffmpeg's rawvideo for a pixel format
    static byte[] raw(List<Picture> frames, String pixelFormat) {
        var out = new ByteArrayOutputStream();
        for (var frame : frames) {
            switch (pixelFormat) {
                case "bgr24", "bgra" -> {
                    var rgba = assertInstanceOf(RgbaPicture.class, frame);
                    var alpha = pixelFormat.equals("bgra");
                    for (var y = 0; y < rgba.height(); y++) {
                        for (var x = 0; x < rgba.width(); x++) {
                            var at = y * rgba.stride() + 4 * x;
                            out.write(rgba.pixels()[at + 2]);
                            out.write(rgba.pixels()[at + 1]);
                            out.write(rgba.pixels()[at]);
                            if (alpha) {
                                out.write(rgba.pixels()[at + 3]);
                            }
                        }
                    }
                }
                default -> out.writeBytes(MotionJpegTesting.planes(assertInstanceOf(YuvPicture.class, frame)));
            }
        }
        return out.toByteArray();
    }

    private static List<Picture> decodeFile(byte[] file, VideoDecoderOptions options) {
        var stream = MotionJpegTesting.demux(file);
        assertEquals(Codec.MJPEG, stream.track().codec());
        var frames = new ArrayList<Picture>();
        try (var decoder = VideoDecoders.create(stream.track(), options)) {
            for (var packet : stream.packets()) {
                decoder.decode(packet, frame -> frames.add(frame.picture()));
            }
        }
        return frames;
    }

    private static String md5(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(data));
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError(exception);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("vectors")
    @DisplayName("decode ffmpeg's lossless JPEG files to exactly the frames they were coded from")
    void vector(Vector vector) {
        for (var options : List.of(VideoDecoderOptions.defaults(), VideoDecoderOptions.builder().highBitDepth(true)
                .build())) {
            var frames = decodeFile(resource("mjpeg/" + vector.file()), options);
            assertEquals(vector.frames(), frames.size());
            for (var frame : frames) {
                assertEquals(vector.width(), frame.width());
                assertEquals(vector.height(), frame.height());
            }
            assertEquals(vector.md5(), md5(raw(frames, vector.pixelFormat())), vector.file());
        }
    }

    static Stream<Arguments> live() {
        var arguments = Stream.<Arguments>builder();
        var formats = new String[]{"yuvj420p", "yuvj422p", "yuvj444p", "bgr24", "bgra", "bgr0"};
        for (var format : formats) {
            for (var predictor = 1; predictor <= 3; predictor++) {
                arguments.add(Arguments.of(format, predictor, predictor == 2 ? "35x21" : "64x36"));
            }
        }
        return arguments.build();
    }

    @ParameterizedTest(name = "{0}, predictor {1}, {2}")
    @MethodSource("live")
    @DisplayName("decode what ffmpeg's lossless JPEG encoder writes to exactly its source frames")
    void ffmpeg(String pixelFormat, int predictor, String size) {
        var input = List.of("-f", "lavfi", "-i", "testsrc2=size=64x48:rate=5");
        // testsrc2 renders even sizes only, so the size is cropped from a larger picture
        var frames = List.of("-frames:v", "2", "-vf", "format=rgb24,crop=" + size.replace('x', ':') + ":1:3",
                "-pix_fmt", pixelFormat);
        var coded = new ArrayList<>(frames);
        coded.addAll(List.of("-c:v", "ljpeg", "-pred", Integer.toString(predictor)));
        var file = Fixtures.ffmpeg("avi", input, coded);
        var rawOutput = new ArrayList<>(frames);
        rawOutput.addAll(List.of("-f", "rawvideo"));
        var source = Fixtures.read(Fixtures.ffmpeg("raw", input, rawOutput));
        var decoded = decodeFile(Fixtures.read(file), VideoDecoderOptions.defaults());
        var format = pixelFormat.equals("bgr0") ? "bgr0" : pixelFormat;
        var raw = raw(decoded, format.equals("bgr0") ? "bgra" : format);
        if (format.equals("bgr0")) {
            // bgr0 leaves its fourth byte unspecified: compare the colors only
            for (var i = 3; i < raw.length; i += 4) {
                raw[i] = source[i];
            }
        }
        assertArrayEquals(source, raw);
    }

    @Test
    @DisplayName("decode 12-bit lossless frames like the still decoder, at 12 bits under highBitDepth")
    void twelveBit() {
        var jpeg = resource("lossless/61x43-gray-p12-psv1.jpg");
        var wide = assertInstanceOf(Yuv16Picture.class, new MjpegDecoder(VideoDecoderOptions.builder().highBitDepth(true)
                .build()).decodeFrame(jpeg, 0, jpeg.length));
        assertEquals(12, wide.bitDepth());
        var still = JpegDecoder.decode(MediaSource.of(jpeg), ImageDecoderOptions.builder().highBitDepth(true).build());
        assertArrayEquals(MotionJpegTesting.planes(still.picture()), MotionJpegTesting.planes(wide));
        var narrow = assertInstanceOf(YuvPicture.class, new MjpegDecoder().decodeFrame(jpeg, 0, jpeg.length));
        assertArrayEquals(MotionJpegTesting.planes(JpegDecoder.decode(MediaSource.of(jpeg)).picture()),
                MotionJpegTesting.planes(narrow));
    }

    @Test
    @DisplayName("weave two lossless fields exactly")
    void fields() {
        var file = resource("mjpeg/ljpeg-yuvj420p-pred1.avi");
        var packets = MotionJpegTesting.demux(file).packets();
        var a = packets.get(0).data();
        var b = packets.get(1).data();
        var frame = MotionJpegTesting.concat(a, b);
        var woven = new MjpegDecoder().decodeFrame(frame, 0, frame.length);
        var top = (YuvPicture) new MjpegDecoder().decodeFrame(a, 0, a.length);
        var bottom = (YuvPicture) new MjpegDecoder().decodeFrame(b, 0, b.length);
        assertArrayEquals(MotionJpegTesting.planes(MotionJpegTesting.weave(top, bottom, 58)),
                MotionJpegTesting.planes(woven));
    }

    private static void assertRowsFrom(RgbaPicture expected, int parity, RgbaPicture woven) {
        for (var y = parity; y < woven.height(); y += 2) {
            assertArrayEquals(Arrays.copyOfRange(expected.pixels(), (y >> 1) * expected.stride(),
                            (y >> 1) * expected.stride() + 4 * expected.width()),
                    Arrays.copyOfRange(woven.pixels(), y * woven.stride(), y * woven.stride() + 4 * woven.width()),
                    "row " + y);
        }
    }

    @Test
    @DisplayName("weave two RGB lossless fields, and double a single one in an interlaced track")
    void rgbFields() {
        var packets = MotionJpegTesting.demux(resource("mjpeg/ljpeg-bgr24-pred1.avi")).packets();
        var a = packets.get(0).data();
        var b = packets.get(1).data();
        var top = assertInstanceOf(RgbaPicture.class, new MjpegDecoder().decodeFrame(a, 0, a.length));
        var bottom = assertInstanceOf(RgbaPicture.class, new MjpegDecoder().decodeFrame(b, 0, b.length));
        var frame = MotionJpegTesting.concat(a, b);
        var woven = assertInstanceOf(RgbaPicture.class, new MjpegDecoder().decodeFrame(frame, 0, frame.length));
        assertEquals(2 * top.height(), woven.height());
        assertRowsFrom(top, 0, woven);
        assertRowsFrom(bottom, 1, woven);
        var track = VideoTrack.builder().id(1).codec(Codec.MJPEG).timeBase(new Rational(1, 25)).width(top.width())
                .height(2 * top.height()).interlacing(Interlacing.INTERLACED).build();
        var pictures = new ArrayList<Picture>();
        new MjpegDecoder(track).decode(new Packet(1, a, 0, 0, 1, true), video -> pictures.add(video.picture()));
        var doubled = assertInstanceOf(RgbaPicture.class, pictures.getFirst());
        assertEquals(2 * top.height(), doubled.height());
        assertRowsFrom(top, 0, doubled);
        assertRowsFrom(top, 1, doubled);
    }
}
