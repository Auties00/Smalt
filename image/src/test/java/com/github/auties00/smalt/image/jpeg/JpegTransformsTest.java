package com.github.auties00.smalt.image.jpeg;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.Orientation;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.exif.ExifData;
import com.github.auties00.smalt.image.exif.ExifReader;
import com.github.auties00.smalt.image.exif.ExifValue;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.image.ops.Region;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.io.MediaSink;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Tests the lossless JPEG transforms: byte identity with libjpeg-turbo's {@code jpegtran} (when it
 * is on {@code PATH}) over every transform, crop, trim, grayscale, progressive, Huffman, copy and
 * restart switch and a range of subsamplings, sizes and source codings; algebraic identities of the
 * transforms; the EXIF orientation and thumbnail handling of {@link JpegTransforms#autoOrient};
 * and robustness against hostile input.
 */
class JpegTransformsTest {
    private static final Path SCRATCH = scratch();

    private static Path scratch() {
        try {
            return Files.createTempDirectory("smalt-jpegtran");
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static Optional<Path> tool(String name) {
        var path = System.getenv("PATH");
        if (path == null) {
            return Optional.empty();
        }
        for (var directory : path.split(java.io.File.pathSeparator)) {
            for (var candidate : List.of(name, name + ".exe")) {
                try {
                    var file = Path.of(directory, candidate);
                    if (Files.isRegularFile(file)) {
                        return Optional.of(file);
                    }
                } catch (RuntimeException ignored) {
                    // a malformed PATH entry
                }
            }
        }
        return Optional.empty();
    }

    private static byte[] run(String name, List<String> arguments, byte[] input) {
        var executable = tool(name);
        assumeTrue(executable.isPresent(), name + " is not on PATH");
        try {
            var in = Files.createTempFile(SCRATCH, "in", ".bin");
            var out = Files.createTempFile(SCRATCH, "out", ".bin");
            Files.write(in, input);
            var command = new ArrayList<String>();
            command.add(executable.get().toString());
            command.addAll(arguments);
            command.add("-outfile");
            command.add(out.toString());
            command.add(in.toString());
            var process = new ProcessBuilder(command).redirectErrorStream(true).start();
            var log = process.getInputStream().readAllBytes();
            if (!process.waitFor(60, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new AssertionError(name + " timed out");
            }
            if (process.exitValue() != 0) {
                throw new ToolFailure(name + " failed: " + new String(log));
            }
            var result = Files.readAllBytes(out);
            Files.delete(in);
            Files.delete(out);
            return result;
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
    }

    private static final class ToolFailure extends RuntimeException {
        ToolFailure(String message) {
            super(message);
        }
    }

    private static RgbaPicture picture(int width, int height, int seed) {
        var random = new Random(seed);
        var picture = RgbaPicture.allocate(width, height, false);
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                var i = (y * width + x) * 4;
                // texture plus noise, so refinement scans and long runs both occur
                picture.pixels()[i] = (byte) (128 + 90 * Math.sin(x * 0.11 + seed) + random.nextInt(24));
                picture.pixels()[i + 1] = (byte) (128 + 90 * Math.cos(y * 0.07) + random.nextInt(24));
                picture.pixels()[i + 2] = (byte) ((x * 3 + y * 2) + random.nextInt(24));
                picture.pixels()[i + 3] = (byte) 255;
            }
        }
        return picture;
    }

    private static byte[] encode(int width, int height, ChromaFormat format, int restart) {
        var options = JpegEncoderOptions.ofQuality(88).toBuilder().chromaFormat(format).optimizeHuffman(false)
                .restartInterval(restart).build();
        return JpegEncoder.encode(picture(width, height, width * 31 + height), options);
    }

    private static byte[] ppm(int width, int height) {
        var picture = picture(width, height, 7);
        var rgb = new byte[width * height * 3];
        for (var i = 0; i < width * height; i++) {
            System.arraycopy(picture.pixels(), i * 4, rgb, i * 3, 3);
        }
        var header = ("P6\n" + width + " " + height + "\n255\n").getBytes();
        var out = new byte[header.length + rgb.length];
        System.arraycopy(header, 0, out, 0, header.length);
        System.arraycopy(rgb, 0, out, header.length, rgb.length);
        return out;
    }

    private static Map<String, byte[]> sources() {
        var sources = new LinkedHashMap<String, byte[]>();
        sources.put("testorig", JpegTestVectors.testorig());
        sources.put("testimgari", JpegTestVectors.testimgari());
        sources.put("testprog", JpegTestVectors.ijgTestprog());
        sources.put("420 61x47", encode(61, 47, ChromaFormat.YUV420, 0));
        sources.put("422 75x33 restart", encode(75, 33, ChromaFormat.YUV422, 5));
        sources.put("444 41x29", encode(41, 29, ChromaFormat.YUV444, 0));
        sources.put("gray 53x37", encode(53, 37, ChromaFormat.MONOCHROME, 0));
        sources.put("420 64x48", encode(64, 48, ChromaFormat.YUV420, 0));
        sources.put("exif first", withExif(encode(50, 34, ChromaFormat.YUV420, 0), 6, true));
        if (tool("cjpeg").isPresent()) {
            var image = ppm(45, 39);
            sources.put("cjpeg rgb", run("cjpeg", List.of("-rgb"), image));
            sources.put("cjpeg 411", run("cjpeg", List.of("-sample", "4x1,1x1,1x1"), image));
            sources.put("cjpeg 1x2", run("cjpeg", List.of("-sample", "1x2,1x1,1x1"), image));
            sources.put("cjpeg 2x1 chroma", run("cjpeg", List.of("-sample", "2x2,2x1,1x2"), image));
            sources.put("cjpeg q100", run("cjpeg", List.of("-quality", "100", "-sample", "1x1"), image));
            sources.put("cjpeg comment icc", withSegments(run("cjpeg", List.of("-optimize"), image)));
        }
        return sources;
    }

    private static final List<List<String>> SWITCHES = List.of(
            List.of(),
            List.of("-optimize"),
            List.of("-progressive"),
            List.of("-rotate", "90"),
            List.of("-rotate", "180"),
            List.of("-rotate", "270"),
            List.of("-flip", "horizontal"),
            List.of("-flip", "vertical"),
            List.of("-transpose"),
            List.of("-transverse"),
            List.of("-rotate", "90", "-trim"),
            List.of("-rotate", "180", "-trim"),
            List.of("-rotate", "270", "-trim"),
            List.of("-flip", "horizontal", "-trim"),
            List.of("-flip", "vertical", "-trim"),
            List.of("-transverse", "-trim"),
            List.of("-crop", "20x18+9+5"),
            List.of("-crop", "20x18+9+5", "-rotate", "90"),
            List.of("-crop", "17x13+16+16", "-flip", "horizontal"),
            List.of("-crop", "11x11+3+0", "-flip", "horizontal"),
            List.of("-crop", "21x19+1+17", "-transverse", "-trim"),
            List.of("-grayscale"),
            List.of("-grayscale", "-rotate", "270", "-progressive"),
            List.of("-rotate", "90", "-progressive"),
            List.of("-transpose", "-optimize", "-restart", "3B"),
            List.of("-flip", "vertical", "-progressive", "-restart", "2B"),
            List.of("-copy", "all", "-rotate", "90"),
            List.of("-copy", "all", "-crop", "20x18+9+5"),
            List.of("-copy", "none"),
            List.of("-copy", "icc", "-flip", "horizontal"),
            List.of("-perfect", "-rotate", "180")
    );

    static Stream<Arguments> pairs() {
        var list = new ArrayList<Arguments>();
        for (var entry : sources().entrySet()) {
            for (var switches : SWITCHES) {
                list.add(Arguments.of(entry.getKey() + " " + String.join(" ", switches), entry.getValue(), switches));
            }
        }
        return list.stream();
    }

    private static JpegTransformOptions options(List<String> switches) {
        var options = JpegTransformOptions.jpegtran();
        for (var i = 0; i < switches.size(); i++) {
            switch (switches.get(i)) {
                case "-optimize" -> options = options.toBuilder().optimizeHuffman(true).build();
                case "-progressive" -> options = options.toBuilder().progressive(true).build();
                case "-trim" -> options = options.toBuilder().trim(true).build();
                case "-perfect" -> options = options.toBuilder().perfect(true).build();
                case "-grayscale" -> options = options.toBuilder().grayscale(true).build();
                case "-transpose" -> options = options.toBuilder().transform(Orientation.TRANSPOSE).build();
                case "-transverse" -> options = options.toBuilder().transform(Orientation.TRANSVERSE).build();
                case "-rotate" -> options = options.toBuilder()
                        .transform(Orientation.ofRotation(Integer.parseInt(switches.get(++i)), false)).build();
                case "-flip" -> options = options.toBuilder().transform(switches.get(++i).equals("horizontal")
                        ? Orientation.FLIP_HORIZONTAL : Orientation.FLIP_VERTICAL).build();
                case "-restart" -> options = options.toBuilder().restartInterval(Integer.parseInt(switches.get(++i)
                        .replace("B", ""))).build();
                case "-copy" -> options = options.toBuilder().markerCopy(switch (switches.get(++i)) {
                    case "all" -> JpegMarkerCopy.ALL;
                    case "none" -> JpegMarkerCopy.NONE;
                    case "icc" -> JpegMarkerCopy.ICC;
                    default -> JpegMarkerCopy.COMMENTS;
                }).build();
                case "-crop" -> {
                    var spec = switches.get(++i).split("[x+]");
                    options = options.toBuilder()
                            .crop(new Region(Integer.parseInt(spec[2]), Integer.parseInt(spec[3]),
                            Integer.parseInt(spec[0]), Integer.parseInt(spec[1]))).build();
                }
                default -> throw new AssertionError(switches.get(i));
            }
        }
        return options;
    }

    @Nested
    @DisplayName("against jpegtran")
    class Jpegtran {
        @ParameterizedTest(name = "{0}")
        @MethodSource("com.github.auties00.smalt.image.jpeg.JpegTransformsTest#pairs")
        @DisplayName("writes the same bytes as jpegtran")
        void identical(String name, byte[] source, List<String> switches) {
            byte[] expected;
            try {
                expected = run("jpegtran", switches, source);
            } catch (ToolFailure failure) {
                assertThrows(MediaException.class, () -> JpegTransforms.transform(MediaSource.of(source), options(switches)));
                return;
            }
            var actual = JpegTransforms.transform(MediaSource.of(source), options(switches));
            if (!Arrays.equals(expected, actual)) {
                var at = Arrays.mismatch(expected, actual);
                throw new AssertionError(name + ": " + actual.length + " bytes instead of " + expected.length
                        + ", first difference at " + at);
            }
        }
    }

    @Nested
    @DisplayName("transform algebra")
    class Algebra {
        @Test
        @DisplayName("four quarter turns of a perfect image give back the original coefficients")
        void fourTurns() {
            var source = encode(64, 48, ChromaFormat.YUV420, 0);
            var options = JpegTransformOptions.jpegtran().toBuilder().transform(Orientation.ROTATE_90).build();
            var rotated = source;
            for (var i = 0; i < 4; i++) {
                rotated = JpegTransforms.transform(MediaSource.of(rotated), options);
            }
            var identity = JpegTransforms.transform(MediaSource.of(source), JpegTransformOptions.jpegtran());
            assertArrayEquals(identity, rotated);
        }

        @Test
        @DisplayName("two flips, two transposes and two half turns cancel out")
        void involutions() {
            var source = encode(48, 64, ChromaFormat.YUV422, 0);
            var identity = JpegTransforms.transform(MediaSource.of(source), JpegTransformOptions.jpegtran());
            for (var transform : List.of(Orientation.FLIP_HORIZONTAL, Orientation.FLIP_VERTICAL,
                    Orientation.TRANSPOSE, Orientation.TRANSVERSE, Orientation.ROTATE_180)) {
                var options = JpegTransformOptions.jpegtran().toBuilder().transform(transform).build();
                var twice = JpegTransforms.transform(MediaSource.of(JpegTransforms.transform(MediaSource.of(source), options)), options);
                assertArrayEquals(identity, twice, transform.name());
            }
        }

        @Test
        @DisplayName("progressive and sequential codings of the same coefficients decode identically")
        void codings() {
            var source = encode(61, 47, ChromaFormat.YUV420, 0);
            var progressive = JpegTransforms.progressive(MediaSource.of(source));
            var baseline = JpegTransforms.sequential(MediaSource.of(progressive));
            var direct = JpegTransforms.sequential(MediaSource.of(source));
            assertArrayEquals(direct, baseline);
            assertTrue(direct.length < source.length, "optimized tables shrink a standard-table file");
            var decodedSource = (YuvPicture) JpegDecoder.decode(MediaSource.of(source), ImageDecoderOptions.defaults()).picture();
            var decodedProgressive = (YuvPicture) JpegDecoder.decode(MediaSource.of(progressive), ImageDecoderOptions.defaults()).picture();
            assertArrayEquals(JpegTestSupport.planar(decodedSource), JpegTestSupport.planar(decodedProgressive));
        }

        @Test
        @DisplayName("rotation swaps the dimensions and trimming drops the partial MCU")
        void dimensions() {
            var source = encode(61, 47, ChromaFormat.YUV420, 0);
            var rotated = JpegTransforms.rotate(MediaSource.of(source), 90);
            var info = JpegDecoder.probe(MediaSource.of(rotated));
            assertEquals(32, info.width());
            assertEquals(61, info.height());
            var untrimmed = JpegTransforms.transform(MediaSource.of(source),
                    JpegTransformOptions.of(Orientation.ROTATE_90).toBuilder().trim(false).build());
            var full = JpegDecoder.probe(MediaSource.of(untrimmed));
            assertEquals(47, full.width());
            assertEquals(61, full.height());
            assertThrows(MediaException.Unsupported.class, () -> JpegTransforms.transform(MediaSource.of(source),
                    JpegTransformOptions.of(Orientation.ROTATE_90).toBuilder().perfect(true).build()));
        }

        @Test
        @DisplayName("a crop outside the image or a grayscale conversion of RGB is rejected")
        void rejects() {
            var source = encode(32, 32, ChromaFormat.YUV420, 0);
            assertThrows(IllegalArgumentException.class, () -> JpegTransforms.crop(MediaSource.of(source), new Region(30, 0, 8, 8)));
            assertThrows(IllegalArgumentException.class, () -> new Region(-1, 0, 8, 8));
            assertThrows(IllegalArgumentException.class, () -> JpegTransforms.rotate(MediaSource.of(source), 45));
            assumeTrue(tool("cjpeg").isPresent());
            var rgb = run("cjpeg", List.of("-rgb"), ppm(16, 16));
            assertThrows(MediaException.Unsupported.class, () -> JpegTransforms.grayscale(MediaSource.of(rgb)));
        }
    }

    @Nested
    @DisplayName("EXIF handling")
    class Exif {
        @Test
        @DisplayName("auto-orientation rotates the image and its thumbnail and resets the orientation")
        void autoOrient() {
            var source = withExif(encode(64, 48, ChromaFormat.YUV420, 0), 6, true);
            var upright = JpegTransforms.autoOrient(MediaSource.of(source));
            var info = JpegDecoder.probe(MediaSource.of(upright));
            assertEquals(48, info.width());
            assertEquals(64, info.height());
            var exif = exifBlock(upright);
            assertEquals(Orientation.NORMAL, ExifReader.orientation(exif));
            var thumbnail = ExifReader.parse(exif).thumbnail().orElseThrow();
            var thumbnailInfo = JpegDecoder.probe(MediaSource.of(thumbnail));
            assertEquals(16, thumbnailInfo.width());
            assertEquals(24, thumbnailInfo.height());
            assertEquals(Orientation.NORMAL, JpegDecoder.probe(MediaSource.of(upright)).orientation());
            if (tool("jpegtran").isPresent()) {
                var expected = run("jpegtran", List.of("-copy", "all", "-rotate", "90", "-trim", "-optimize"), source);
                assertArrayEquals(withoutApp1(expected), withoutApp1(upright));
            }
        }

        @Test
        @DisplayName("an image already upright is only re-encoded")
        void alreadyUpright() {
            var source = withExif(encode(40, 24, ChromaFormat.YUV444, 0), 1, false);
            var upright = JpegTransforms.autoOrient(MediaSource.of(source));
            var info = JpegDecoder.probe(MediaSource.of(upright));
            assertEquals(40, info.width());
            assertEquals(24, info.height());
            assertArrayEquals(exifBlock(source), exifBlock(upright));
        }

        @Test
        @DisplayName("a rotation keeps the EXIF segment and records the new size in it")
        void imageSize() {
            var source = withExif(encode(64, 48, ChromaFormat.YUV420, 0), 1, false);
            var rotated = JpegTransforms.rotate(MediaSource.of(source), 270);
            var exif = ExifReader.parse(exifBlock(rotated));
            var width = (ExifValue.Integers) exif.get(ExifData.Directory.EXIF, 0xA002).orElseThrow();
            var height = (ExifValue.Integers) exif.get(ExifData.Directory.EXIF, 0xA003).orElseThrow();
            assertEquals(48L, width.get(0));
            assertEquals(64L, height.get(0));
            assertEquals(ExifValue.Type.LONG, width.type());
        }
    }

    @Nested
    @DisplayName("robustness")
    class Robustness {
        @Test
        @DisplayName("mutated files fail only with media exceptions, promptly")
        void mutations() {
            var base = encode(40, 30, ChromaFormat.YUV420, 2);
            var progressive = JpegTransforms.progressive(MediaSource.of(base));
            var random = new Random(29);
            assertTimeoutPreemptively(Duration.ofSeconds(60), () -> {
                for (var round = 0; round < 400; round++) {
                    var data = (round % 2 == 0 ? base : progressive).clone();
                    var flips = 1 + random.nextInt(8);
                    for (var i = 0; i < flips; i++) {
                        data[random.nextInt(data.length)] = (byte) random.nextInt(256);
                    }
                    if (random.nextInt(4) == 0) {
                        data = Arrays.copyOf(data, random.nextInt(data.length));
                    }
                    var transform = Orientation.values()[random.nextInt(Orientation.values().length)];
                    var options = JpegTransformOptions.of(transform).toBuilder().progressive(random.nextBoolean())
                            .build();
                    try {
                        JpegTransforms.transform(MediaSource.of(data), options);
                    } catch (MediaException expected) {
                        // hostile input is reported as a media exception
                    }
                }
            });
        }

        @Test
        @DisplayName("non-JPEG input is rejected as malformed")
        void garbage() {
            assertThrows(MediaException.class, () -> JpegTransforms.transform(MediaSource.of(new byte[0]), JpegTransformOptions.defaults()));
            assertThrows(MediaException.class, () -> JpegTransforms.rotate(MediaSource.of("not a jpeg".getBytes()), 90));
            assertFalse(JpegTransformOptions.defaults().equals(JpegTransformOptions.jpegtran()));
            assertEquals(JpegTransformOptions.defaults(), JpegTransformOptions.builder().build());
        }
    }

    private static byte[] tiff(int orientation, int width, int height, byte[] thumbnail) {
        var buffer = ByteBuffer.allocate(512 + (thumbnail == null ? 0 : thumbnail.length)).order(ByteOrder.LITTLE_ENDIAN);
        buffer.put((byte) 'I').put((byte) 'I').putShort((short) 42).putInt(8);
        // IFD0 at 8: orientation, Exif pointer; next IFD (thumbnail) after the Exif directory
        var exifOffset = 8 + 2 + 2 * 12 + 4;
        var ifd1Offset = exifOffset + 2 + 2 * 12 + 4;
        var thumbOffset = ifd1Offset + 2 + 2 * 12 + 4;
        buffer.putShort((short) 2);
        buffer.putShort((short) 0x0112).putShort((short) 3).putInt(1).putShort((short) orientation).putShort((short) 0);
        buffer.putShort((short) 0x8769).putShort((short) 4).putInt(1).putInt(exifOffset);
        buffer.putInt(thumbnail == null ? 0 : ifd1Offset);
        buffer.putShort((short) 2);
        buffer.putShort((short) 0xA002).putShort((short) 3).putInt(1).putShort((short) width).putShort((short) 0);
        buffer.putShort((short) 0xA003).putShort((short) 3).putInt(1).putShort((short) height).putShort((short) 0);
        buffer.putInt(0);
        if (thumbnail != null) {
            buffer.putShort((short) 2);
            buffer.putShort((short) 0x0201).putShort((short) 4).putInt(1).putInt(thumbOffset);
            buffer.putShort((short) 0x0202).putShort((short) 4).putInt(1).putInt(thumbnail.length);
            buffer.putInt(0);
            buffer.put(thumbnail);
        }
        return Arrays.copyOf(buffer.array(), buffer.position());
    }

    private static byte[] withExif(byte[] jpeg, int orientation, boolean thumbnail) {
        var info = JpegDecoder.probe(MediaSource.of(jpeg));
        var thumb = thumbnail ? encode(24, 16, ChromaFormat.YUV420, 0) : null;
        var block = tiff(orientation, info.width(), info.height(), thumb);
        var out = new ByteArrayOutputStream();
        out.write(0xFF);
        out.write(0xD8);
        out.write(0xFF);
        out.write(0xE1);
        var length = block.length + 8;
        out.write(length >> 8);
        out.write(length);
        out.writeBytes("Exif\0\0".getBytes());
        out.writeBytes(block);
        out.write(jpeg, 2, jpeg.length - 2);
        return out.toByteArray();
    }

    private static byte[] withSegments(byte[] jpeg) {
        var out = new ByteArrayOutputStream();
        out.write(jpeg, 0, 2);
        var comment = "hello jpegtran".getBytes();
        out.write(0xFF);
        out.write(0xFE);
        out.write((comment.length + 2) >> 8);
        out.write(comment.length + 2);
        out.writeBytes(comment);
        var icc = new byte[14 + 40];
        System.arraycopy("ICC_PROFILE ".getBytes(), 0, icc, 0, 12);
        icc[12] = 1;
        icc[13] = 1;
        out.write(0xFF);
        out.write(0xE2);
        out.write((icc.length + 2) >> 8);
        out.write(icc.length + 2);
        out.writeBytes(icc);
        out.write(jpeg, 2, jpeg.length - 2);
        return out.toByteArray();
    }

    private static byte[] exifBlock(byte[] jpeg) {
        var position = 2;
        while (position + 4 <= jpeg.length) {
            var marker = jpeg[position + 1] & 0xFF;
            var length = ((jpeg[position + 2] & 0xFF) << 8) | (jpeg[position + 3] & 0xFF);
            if (marker == 0xE1) {
                return Arrays.copyOfRange(jpeg, position + 10, position + 2 + length);
            }
            if (marker == 0xDA) {
                break;
            }
            position += 2 + length;
        }
        throw new AssertionError("no EXIF segment");
    }

    private static byte[] withoutApp1(byte[] jpeg) {
        var out = new ByteArrayOutputStream();
        out.write(jpeg, 0, 2);
        var position = 2;
        while (position + 4 <= jpeg.length) {
            var marker = jpeg[position + 1] & 0xFF;
            if (marker == 0xDA) {
                break;
            }
            var length = ((jpeg[position + 2] & 0xFF) << 8) | (jpeg[position + 3] & 0xFF);
            if (marker != 0xE1) {
                out.write(jpeg, position, 2 + length);
            }
            position += 2 + length;
        }
        out.write(jpeg, position, jpeg.length - position);
        return out.toByteArray();
    }

    @Nested
    @DisplayName("sink-last transform and sequential")
    class SinkLastAndSequential {
        private static byte[] progressiveJpeg() {
            var picture = RgbaPicture.allocate(40, 24, false);
            var pixels = picture.pixels();
            for (var i = 0; i < pixels.length; i += 4) {
                pixels[i] = (byte) i;
                pixels[i + 1] = (byte) (i >> 2);
                pixels[i + 2] = (byte) (i >> 4);
                pixels[i + 3] = (byte) 255;
            }
            return JpegTransforms.progressive(MediaSource.of(JpegEncoder.encode(picture)));
        }

        @Test
        @DisplayName("transform(source, sink, options) writes what transform(source, options) returns")
        void sinkLast() {
            var jpeg = progressiveJpeg();
            var options = JpegTransformOptions.of(Orientation.ROTATE_90);
            var sink = MediaSink.memory();
            JpegTransforms.transform(MediaSource.of(jpeg), sink, options);
            assertArrayEquals(JpegTransforms.transform(MediaSource.of(jpeg), options), sink.toByteArray());
        }

        @Test
        @DisplayName("sequential(source) transforms under the defaults")
        void sequential() {
            var jpeg = progressiveJpeg();
            assertArrayEquals(JpegTransforms.transform(MediaSource.of(jpeg), JpegTransformOptions.defaults()),
                    JpegTransforms.sequential(MediaSource.of(jpeg)));
        }
    }
}
