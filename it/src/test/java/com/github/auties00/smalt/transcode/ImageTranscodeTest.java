package com.github.auties00.smalt.transcode;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.AnimationWriter;
import com.github.auties00.smalt.image.ImageEncoderOptions;
import com.github.auties00.smalt.image.ImageFormat;
import com.github.auties00.smalt.image.Images;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.image.ops.Region;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.Oracle;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers the image transcode: format conversion with scaling, flattening, animated inputs, the
 * byte budget, pass-through, and crops of EXIF-oriented pictures, checked against ffmpeg.
 */
class ImageTranscodeTest {
    static Path photo() {
        return Fixtures.testImage("jpg", 1600, 1200, List.of("-q:v", "3"));
    }

    static Path transparent() {
        return Fixtures.ffmpeg("png", List.of("-f", "lavfi", "-i", "testsrc2=size=300x200:rate=1"), List.of("-frames:v", "1",
                "-vf", "format=rgba,geq=r='r(X,Y)':g='g(X,Y)':b='b(X,Y)':a='if(lt(X,150),255,64)'"));
    }

    static Path animation(String extension, List<String> options) {
        return Fixtures.ffmpeg(extension, List.of("-f", "lavfi", "-i", "testsrc2=size=160x120:rate=10:duration=1"), options);
    }

    static ImageTranscodeResult transcode(Path input, Path output, ImageTranscodeOptions options) {
        try (var in = MediaSource.of(input); var out = MediaSink.of(output)) {
            return Transcoder.transcodeImage(in, out, options);
        }
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({"WEBP, webp", "PNG, png", "JPEG, jpg"})
    @DisplayName("converts and scales a photo to every encodable format")
    void convert(ImageFormat format, String extension, @TempDir Path directory) {
        var output = directory.resolve("out." + extension);
        var result = transcode(photo(), output, ImageTranscodeOptions.of(format).toBuilder().maxLongEdge(800).build());
        assertEquals(format, result.format());
        assertEquals(800, result.width());
        assertEquals(600, result.height());
        var expected = Oracle.decodeVideo(Fixtures.ffmpeg("png", List.of("-i", photo().toString()),
                List.of("-vf", "scale=800:600:flags=lanczos")), "gray", 1);
        assertTrue(TranscodeBehaviorTest.meanAbsoluteDifference(expected, Oracle.decodeVideo(output, "gray", 1)) < 4);
    }

    @Test
    @DisplayName("flattens transparency on the background for formats without alpha and keeps it otherwise")
    void flatten(@TempDir Path directory) {
        var jpeg = directory.resolve("out.jpg");
        transcode(transparent(), jpeg, ImageTranscodeOptions.of(ImageFormat.JPEG).toBuilder().background(0x000000)
                .build());
        var source = Oracle.decodeVideo(transparent(), "rgba", 1);
        var flattened = Oracle.decodeVideo(jpeg, "rgb24", 1);
        var error = 0L;
        for (var pixel = 0; pixel < 300 * 200; pixel++) {
            var alpha = source[pixel * 4 + 3] & 0xFF;
            for (var channel = 0; channel < 3; channel++) {
                var expected = (source[pixel * 4 + channel] & 0xFF) * alpha / 255;
                error += Math.abs(expected - (flattened[pixel * 3 + channel] & 0xFF));
            }
        }
        assertTrue(error / (300.0 * 200 * 3) < 4, "blended on black");
        var png = directory.resolve("out.png");
        transcode(transparent(), png, ImageTranscodeOptions.of(ImageFormat.PNG).toBuilder().maxLongEdge(150).build());
        var kept = Oracle.decodeVideo(png, "rgba", 1);
        assertEquals(64, kept[(10 * 150 + 120) * 4 + 3] & 0xFF, 2, "alpha kept");
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({"TIFF, tif", "ICO, ico", "JP2, jp2"})
    @DisplayName("keeps transparency for every other output format that stores it")
    void keepsAlpha(ImageFormat format, String extension, @TempDir Path directory) {
        var output = directory.resolve("out." + extension);
        transcode(transparent(), output, ImageTranscodeOptions.of(format).toBuilder().maxLongEdge(150).build());
        try (var source = MediaSource.of(output)) {
            var picture = ImageOps.toRgba(Images.decode(source).picture());
            assertEquals(150, picture.width());
            assertEquals(64, picture.pixels()[10 * picture.stride() + 120 * 4 + 3] & 0xFF, 2, "alpha kept");
            assertEquals(255, picture.pixels()[10 * picture.stride() + 20 * 4 + 3] & 0xFF, 2, "opaque kept");
        }
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = ';', value = {"gif;", "png;-plays 0 -f apng", "webp;-vf format=bgra -c:v libwebp_anim -loop 0 -lossless 1"})
    @DisplayName("writes the first frame of an animation as a still image")
    void firstFrame(String extension, String options, @TempDir Path directory) {
        var input = animation(extension, options == null ? List.of() : List.of(options.split(" ")));
        var output = directory.resolve("out.png");
        var result = transcode(input, output, ImageTranscodeOptions.of(ImageFormat.PNG).toBuilder()
                .animation(AnimationMode.FIRST_FRAME).build());
        assertEquals(1, result.frames());
        // ffmpeg cannot decode animated WebP: compare with the source frame, converted to RGB the same way
        var reference = extension.equals("webp") ? animation("png", List.of("-vf", "format=bgra", "-frames:v", "1")) : input;
        var expected = Oracle.decodeVideo(reference, "rgb24", 1);
        assertTrue(TranscodeBehaviorTest.meanAbsoluteDifference(expected, Oracle.decodeVideo(output, "rgb24", 1)) < 1);
    }

    @ParameterizedTest(name = "{0} to {1}")
    @CsvSource(delimiter = ';', value = {"gif;GIF;gif", "gif;PNG;apng", "png;GIF;gif", "png;PNG;apng"})
    @DisplayName("writes animations as animated GIF and APNG through the registry")
    void animatedOutput(String input, ImageFormat format, String extension, @TempDir Path directory) {
        var source = animation(input, input.equals("png") ? List.of("-plays", "0", "-f", "apng") : List.of());
        var output = directory.resolve("out." + extension);
        var result = transcode(source, output, ImageTranscodeOptions.of(format).toBuilder().maxLongEdge(80).build());
        assertTrue(result.animated());
        assertEquals(10, result.frames());
        Oracle.assertDecodesCleanly(output);
        assertEquals(10, Oracle.decodeVideo(output, "rgb24", 0).length / (80 * 60 * 3));
        try (var reader = Images.openAnimation(MediaSource.of(Fixtures.read(output)))) {
            var frames = 0;
            var duration = 0L;
            for (var frame = reader.read(); frame != null; frame = reader.read()) {
                frames++;
                duration += frame.durationMicros();
            }
            assertEquals(10, frames);
            assertEquals(1_000_000, duration);
        }
    }

    @Test
    @DisplayName("streams every scaled frame of an animation into an animation encoder")
    void animated(@TempDir Path directory) throws Exception {
        var frames = new ArrayList<long[]>();
        var writers = new AnimationWriters() {
            @Override
            public boolean supports(ImageFormat format) {
                return format == ImageFormat.WEBP;
            }

            @Override
            public AnimationWriter open(ImageFormat format, int width, int height, ImageEncoderOptions options,
                                        MediaSink sink) {
                return new AnimationWriter() {
                    @Override
                    public int width() {
                        return width;
                    }

                    @Override
                    public int height() {
                        return height;
                    }

                    @Override
                    public void write(RgbaPicture picture, long durationMicros) {
                        assertEquals(width, picture.width());
                        assertEquals(height, picture.height());
                        frames.add(new long[]{picture.width(), picture.height(), durationMicros});
                        sink.write(new byte[]{1});
                    }

                    @Override
                    public void finish() {
                    }

                    @Override
                    public void close() {
                    }
                };
            }
        };
        var input = animation("gif", List.of());
        var output = MediaSink.memory();
        ImageTranscodeResult result;
        try (var in = MediaSource.of(input)) {
            result = new ImageTranscode(in, output, ImageTranscodeOptions.of(ImageFormat.WEBP).toBuilder()
                    .maxLongEdge(80).build(), writers).run();
        }
        assertEquals(10, result.frames());
        assertTrue(result.animated());
        assertEquals(80, result.width());
        assertEquals(60, result.height());
        assertEquals(10, frames.size());
        assertEquals(1_000_000, frames.stream().mapToLong(frame -> frame[2]).sum());
        assertEquals(10, result.bytes());
    }

    @Test
    @DisplayName("steps quality and then size down until the output fits the byte budget")
    void budget(@TempDir Path directory) {
        var output = directory.resolve("out.jpg");
        var result = transcode(photo(), output, ImageTranscodeOptions.of(ImageFormat.JPEG).toBuilder().maxBytes(40_000)
                .build());
        assertTrue(result.bytes() <= 40_000);
        assertEquals(result.bytes(), output.toFile().length());
        var tiny = transcode(photo(), directory.resolve("tiny.jpg"), ImageTranscodeOptions.of(ImageFormat.JPEG)
                .toBuilder().maxBytes(4_000).build());
        assertTrue(tiny.bytes() <= 4_000);
        assertTrue(tiny.width() < 1600, "shrunk " + tiny.width());
        assertThrows(MediaException.LimitExceeded.class, () -> transcode(photo(), directory.resolve("none.png"),
                ImageTranscodeOptions.of(ImageFormat.PNG).toBuilder().maxBytes(10).build()));
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({"WEBP, webp", "GIF, gif", "PNG, apng"})
    @DisplayName("fits an animation into a byte budget keeping every frame and the duration")
    void animatedBudget(ImageFormat format, String extension, @TempDir Path directory) {
        var input = animation("gif", List.of());
        var free = transcode(input, directory.resolve("free." + extension), ImageTranscodeOptions.of(format));
        assertTrue(free.animated());
        assertEquals(10, free.frames());
        var budget = free.bytes() / 3;
        var output = directory.resolve("out." + extension);
        var result = transcode(input, output, ImageTranscodeOptions.of(format).toBuilder().maxBytes(budget).build());
        assertTrue(result.bytes() <= budget, result.bytes() + " > " + budget);
        assertEquals(result.bytes(), output.toFile().length());
        assertEquals(10, result.frames());
        try (var reader = Images.openAnimation(MediaSource.of(Fixtures.read(output)))) {
            assertEquals(result.width(), reader.width());
            assertEquals(result.height(), reader.height());
            var frames = 0;
            var duration = 0L;
            for (var frame = reader.read(); frame != null; frame = reader.read()) {
                frames++;
                duration += frame.durationMicros();
            }
            assertEquals(10, frames);
            assertEquals(1_000_000, duration);
        }
        if (format == ImageFormat.PNG) {
            assertTrue(result.width() < 160, "a lossless animation shrinks: " + result.width());
        }
        if (format != ImageFormat.WEBP) {
            Oracle.assertDecodesCleanly(output);
            assertEquals(10, Oracle.decodeVideo(output, "rgb24", 0).length / (result.width() * result.height() * 3));
        }
        assertThrows(MediaException.LimitExceeded.class, () -> transcode(input, directory.resolve("none." + extension),
                ImageTranscodeOptions.of(format).toBuilder().maxBytes(8).build()));
    }

    @Test
    @DisplayName("steps an animation's quality, then its size, then keeps fewer frames until it fits the budget")
    void animatedBudgetLadder() {
        // a fake encoder whose output grows with the canvas, the quality and the frame count, so the
        // ladder's outcome is exact: 16 + width * height * quality / 7500 bytes a frame
        var attempts = new ArrayList<int[]>();
        var frames = new ArrayList<Long>();
        var writers = new AnimationWriters() {
            @Override
            public boolean supports(ImageFormat format) {
                return format == ImageFormat.WEBP;
            }

            @Override
            public AnimationWriter open(ImageFormat format, int width, int height, ImageEncoderOptions options,
                                        MediaSink sink) {
                var quality = options.quality().orElse(75);
                attempts.add(new int[]{width, height, quality});
                frames.clear();
                return new AnimationWriter() {
                    @Override
                    public int width() {
                        return width;
                    }

                    @Override
                    public int height() {
                        return height;
                    }

                    @Override
                    public void write(RgbaPicture picture, long durationMicros) {
                        assertEquals(width, picture.width());
                        frames.add(durationMicros);
                        sink.write(new byte[16 + width * height * quality / 7500]);
                    }

                    @Override
                    public void finish() {
                    }

                    @Override
                    public void close() {
                    }
                };
            }
        };
        var input = Fixtures.read(animation("gif", List.of()));
        // 10 frames at 160x120: 208 bytes a frame at quality 75, 80 at 25 and 54 at 15
        var quality = run(input, writers, 10 * 70);
        assertEquals(10, quality.frames());
        assertEquals(160, quality.width());
        assertArrayEquals(new int[]{160, 120, 15}, attempts.getLast());
        assertEquals(List.of(75, 65, 55, 45, 35, 25, 15), attempts.stream().map(attempt -> attempt[2]).toList());
        attempts.clear();
        // 16 bytes a frame of overhead: ten frames never fit 100 bytes, five frames of 20 bytes do
        var decimated = run(input, writers, 100);
        assertEquals(5, decimated.frames());
        assertEquals(List.of(200_000L, 200_000L, 200_000L, 200_000L, 200_000L), frames);
        assertTrue(decimated.bytes() <= 100);
        assertTrue(Math.max(decimated.width(), decimated.height()) < 160);
        // a single frame takes 16 bytes
        assertThrows(MediaException.LimitExceeded.class, () -> run(input, writers, 15));
    }

    @Test
    @DisplayName("keeps the right frames when it drops frames of an animation at its full size")
    void animatedBudgetKeptFrames() {
        // 16x12 is already the smallest size the budget shrinks to, so the frames are dropped at full
        // size, where the kept frame is the reader's own picture until the next kept one is read
        var input = Fixtures.read(Fixtures.ffmpeg("gif", List.of("-f", "lavfi", "-i",
                "nullsrc=size=16x12:rate=10:duration=0.8,format=rgb24,geq=r='N*30':g='255-N*30':b='X*16'"), List.of()));
        var source = new ArrayList<byte[]>();
        try (var reader = Images.openAnimation(MediaSource.of(input))) {
            for (var frame = reader.read(); frame != null; frame = reader.read()) {
                source.add(ImageOps.toRgba(frame.picture()).pixels().clone());
            }
        }
        assertEquals(8, source.size());
        for (var index = 1; index < 8; index++) {
            assertFalse(java.util.Arrays.equals(source.get(index - 1), source.get(index)), "frame " + index + " changes");
        }
        var written = new ArrayList<byte[]>();
        var durations = new ArrayList<Long>();
        var writers = new AnimationWriters() {
            @Override
            public boolean supports(ImageFormat format) {
                return format == ImageFormat.WEBP;
            }

            @Override
            public AnimationWriter open(ImageFormat format, int width, int height, ImageEncoderOptions options,
                                        MediaSink sink) {
                written.clear();
                durations.clear();
                return new AnimationWriter() {
                    @Override
                    public int width() {
                        return width;
                    }

                    @Override
                    public int height() {
                        return height;
                    }

                    @Override
                    public void write(RgbaPicture picture, long durationMicros) {
                        written.add(picture.pixels().clone());
                        durations.add(durationMicros);
                        sink.write(new byte[20]);
                    }

                    @Override
                    public void finish() {
                    }

                    @Override
                    public void close() {
                    }
                };
            }
        };
        // eight frames of 20 bytes pass 100 bytes, four fit
        var result = run(input, writers, 100);
        assertEquals(4, result.frames());
        assertEquals(16, result.width());
        assertEquals(List.of(200_000L, 200_000L, 200_000L, 200_000L), durations);
        for (var index = 0; index < 4; index++) {
            assertArrayEquals(source.get(index * 2), written.get(index), "kept frame " + index);
        }
    }

    private static ImageTranscodeResult run(byte[] input, AnimationWriters writers, long budget) {
        var output = MediaSink.memory();
        var result = new ImageTranscode(MediaSource.of(input), output, ImageTranscodeOptions.of(ImageFormat.WEBP)
                .toBuilder().maxBytes(budget).build(), writers).run();
        assertEquals(result.bytes(), output.size());
        return result;
    }

    @Test
    @DisplayName("transcodes between files on disk as between a source and a sink")
    void pathOverload(@TempDir Path directory) throws Exception {
        var options = ImageTranscodeOptions.builder().format(ImageFormat.PNG).maxSize(200, 200).build();
        var viaStreams = directory.resolve("streams.png");
        var expected = transcode(photo(), viaStreams, options);
        var viaPaths = directory.resolve("paths.png");
        var actual = Transcoder.transcodeImage(photo(), viaPaths, options);
        assertEquals(expected.width(), actual.width());
        assertEquals(expected.height(), actual.height());
        assertArrayEquals(Files.readAllBytes(viaStreams), Files.readAllBytes(viaPaths));
    }

    @Test
    @DisplayName("copies an input that needs no change byte for byte")
    void passthrough(@TempDir Path directory) throws Exception {
        var output = directory.resolve("out.jpg");
        var result = transcode(photo(), output, ImageTranscodeOptions.defaults());
        assertTrue(result.passthrough());
        assertArrayEquals(Files.readAllBytes(photo()), Files.readAllBytes(output));
        var encoded = transcode(photo(), directory.resolve("encoded.jpg"), ImageTranscodeOptions.builder()
                .passthrough(false).build());
        assertFalse(encoded.passthrough());
    }

    @Test
    @DisplayName("turns an EXIF-oriented photo upright and crops it in displayed coordinates")
    void exifCrop(@TempDir Path directory) throws Exception {
        var base = Fixtures.testImage("jpg", 320, 240, List.of("-q:v", "2"));
        var oriented = directory.resolve("oriented.jpg");
        Files.write(oriented, withExifOrientation(Files.readAllBytes(base), 6));
        var output = directory.resolve("out.png");
        var result = transcode(oriented, output, ImageTranscodeOptions.of(ImageFormat.PNG).toBuilder()
                .crop(new Region(20, 40, 200, 160)).build());
        assertEquals(200, result.width());
        assertEquals(160, result.height());
        var expected = Oracle.decodeVideo(Fixtures.ffmpeg("png", List.of("-i", base.toString()),
                List.of("-vf", "transpose=1,crop=200:160:20:40")), "gray", 1);
        assertTrue(TranscodeBehaviorTest.meanAbsoluteDifference(expected, Oracle.decodeVideo(output, "gray", 1)) < 2);
    }

    /**
     * Inserts an EXIF segment holding only an orientation tag after the SOI marker of a JPEG.
     */
    static byte[] withExifOrientation(byte[] jpeg, int orientation) {
        var tiff = new byte[]{'I', 'I', 42, 0, 8, 0, 0, 0, 1, 0, 0x12, 0x01, 3, 0, 1, 0, 0, 0, (byte) orientation, 0, 0, 0, 0, 0, 0, 0};
        var payload = new ByteArrayOutputStream();
        payload.writeBytes(new byte[]{'E', 'x', 'i', 'f', 0, 0});
        payload.writeBytes(tiff);
        var segment = payload.toByteArray();
        var out = new ByteArrayOutputStream();
        out.write(jpeg, 0, 2);
        out.write(0xFF);
        out.write(0xE1);
        out.write((segment.length + 2) >> 8);
        out.write((segment.length + 2) & 0xFF);
        out.writeBytes(segment);
        out.write(jpeg, 2, jpeg.length - 2);
        return out.toByteArray();
    }
}
