package com.github.auties00.smalt.image.avif;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.ImageFormat;
import com.github.auties00.smalt.image.Images;
import com.github.auties00.smalt.image.Orientation;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.image.color.ColorProfile;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.internal.heif.HeifFile;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.video.av1.internal.SequenceHeader;
import com.github.auties00.smalt.video.av1.internal.encoder.StillTesting;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the AVIF encoder: files it writes decode with the module's AVIF decoder and with
 * ffmpeg's libaom, to the source exactly when lossless and closely otherwise, with their alpha,
 * grid, depth, orientation, color profile and metadata intact.
 */
class AvifEncoderTest {
    private static YuvPicture foreman() {
        var planes = StillTesting.foreman(0);
        var y = new byte[352 * 288];
        var u = new byte[176 * 144];
        var v = new byte[176 * 144];
        for (var i = 0; i < y.length; i++) {
            y[i] = (byte) planes.data()[0][i];
        }
        for (var i = 0; i < u.length; i++) {
            u[i] = (byte) planes.data()[1][i];
            v[i] = (byte) planes.data()[2][i];
        }
        return YuvPicture.builder().size(352, 288).chromaFormat(ChromaFormat.YUV420).color(ColorInfo.BT709_LIMITED)
                .luma(y, 352).chroma(u, v, 176).build();
    }

    private static RgbaPicture rgba(int width, int height, boolean alpha) {
        var rgba = ImageOps.toRgba(foreman());
        var out = RgbaPicture.allocate(width, height, alpha);
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                var s = (y % 288) * rgba.stride() + (x % 352) * 4;
                var d = y * out.stride() + x * 4;
                System.arraycopy(rgba.pixels(), s, out.pixels(), d, 3);
                out.pixels()[d + 3] = (byte) (alpha ? (x * 255 / Math.max(1, width - 1)) : 255);
            }
        }
        return out;
    }

    private static double psnr(byte[] a, int aStride, byte[] b, int bStride, int width, int height, int step) {
        long sse = 0;
        long n = 0;
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width * step; x++) {
                var d = (a[y * aStride + x] & 0xFF) - (b[y * bStride + x] & 0xFF);
                sse += (long) d * d;
                n++;
            }
        }
        return sse == 0 ? 99 : 10 * Math.log10(255.0 * 255 * n / sse);
    }

    private static byte[] ffmpegDecode(byte[] avif, String pixFmt) throws Exception {
        return ffmpegDecode(avif, pixFmt, List.of());
    }

    private static byte[] ffmpegDecode(byte[] avif, String pixFmt, List<String> map) throws Exception {
        Oracle.assume(Oracle.Tool.FFMPEG);
        var file = Files.createTempFile("avifenc", ".avif");
        try {
            Files.write(file, avif);
            var args = new ArrayList<>(List.of("-v", "error", "-xerror", "-c:v", "libaom-av1", "-i",
                    file.toString()));
            args.addAll(map);
            args.addAll(List.of("-frames:v", "1", "-f", "rawvideo", "-pix_fmt", pixFmt, "-"));
            return Oracle.run(Oracle.Tool.FFMPEG, Duration.ofMinutes(2), args);
        } finally {
            Files.deleteIfExists(file);
        }
    }

    private static byte[] planes(YuvPicture picture) {
        var raw = new ByteArrayOutputStream();
        var cw = (picture.width() + 1) / 2;
        var ch = (picture.height() + 1) / 2;
        for (var y = 0; y < picture.height(); y++) {
            raw.write(picture.y(), y * picture.yStride(), picture.width());
        }
        for (var y = 0; y < ch; y++) {
            raw.write(picture.u(), y * picture.uvStride(), cw);
        }
        for (var y = 0; y < ch; y++) {
            raw.write(picture.v(), y * picture.uvStride(), cw);
        }
        return raw.toByteArray();
    }

    @Nested
    @DisplayName("still images")
    class Still {
        @ParameterizedTest(name = "quality {0}")
        @DisplayName("a 4:2:0 picture decodes close to the source with Smalt and ffmpeg alike")
        @CsvSource({"30, 30", "60, 35", "90, 42"})
        void yuv(int quality, double minPsnr) throws Exception {
            var picture = foreman();
            var file = AvifEncoder.encode(picture, AvifEncoderOptions.lossy(quality));
            assertEquals(Optional.of(ImageFormat.AVIF), ImageFormat.detect(file, file.length));
            var decoded = (YuvPicture) AvifDecoder.decode(MediaSource.of(file)).picture();
            var psnr = psnr(picture.y(), 352, decoded.y(), decoded.yStride(), 352, 288, 1);
            assertTrue(psnr > minPsnr, "psnr " + psnr);
            var ffmpeg = ffmpegDecode(file, "yuv420p");
            var raw = new ByteArrayOutputStream();
            for (var y = 0; y < 288; y++) {
                raw.write(decoded.y(), y * decoded.yStride(), 352);
            }
            for (var y = 0; y < 144; y++) {
                raw.write(decoded.u(), y * decoded.uvStride(), 176);
            }
            for (var y = 0; y < 144; y++) {
                raw.write(decoded.v(), y * decoded.uvStride(), 176);
            }
            assertArrayEquals(raw.toByteArray(), ffmpeg);
        }

        @Test
        @DisplayName("a lossless RGB picture decodes exactly")
        void losslessRgb() {
            var picture = rgba(97, 61, false);
            var file = AvifEncoder.encode(picture, AvifEncoderOptions.lossless());
            var decoded = ImageOps.toRgba(AvifDecoder.decode(MediaSource.of(file)).picture());
            for (var y = 0; y < 61; y++) {
                for (var x = 0; x < 97; x++) {
                    for (var c = 0; c < 3; c++) {
                        assertEquals(picture.pixels()[y * picture.stride() + x * 4 + c],
                                decoded.pixels()[y * decoded.stride() + x * 4 + c], "pixel " + x + "," + y);
                    }
                }
            }
        }

        @Test
        @DisplayName("transparency becomes a lossless alpha item")
        void alpha() {
            var picture = rgba(200, 120, true);
            var file = AvifEncoder.encode(picture, AvifEncoderOptions.lossy(70));
            var decoded = ImageOps.toRgba(AvifDecoder.decode(MediaSource.of(file)).picture());
            assertTrue(decoded.hasAlpha());
            for (var y = 0; y < 120; y++) {
                for (var x = 0; x < 200; x++) {
                    assertEquals(picture.pixels()[y * picture.stride() + x * 4 + 3],
                            decoded.pixels()[y * decoded.stride() + x * 4 + 3]);
                }
            }
            assertTrue(psnr(picture.pixels(), picture.stride(), decoded.pixels(), decoded.stride(), 200, 120, 4) > 30);
        }

        @Test
        @DisplayName("an image grid decodes like the single image")
        void grid() throws Exception {
            var picture = foreman();
            var file = AvifEncoder.encode(picture, AvifEncoderOptions.lossy(70).toBuilder().gridTileSize(128).build());
            var decoded = (YuvPicture) AvifDecoder.decode(MediaSource.of(file)).picture();
            assertEquals(352, decoded.width());
            assertEquals(288, decoded.height());
            assertTrue(psnr(picture.y(), 352, decoded.y(), decoded.yStride(), 352, 288, 1) > 36);
            // ffmpeg 7.1 exposes the grid's images as separate streams: compare each with its region
            for (var row = 0; row < 3; row++) {
                for (var col = 0; col < 3; col++) {
                    var tile = ffmpegDecode(file, "yuv420p", List.of("-map", "0:" + (row * 3 + col)));
                    assertEquals(128 * 128 * 3 / 2, tile.length);
                    var w = Math.min(128, 352 - col * 128);
                    var h = Math.min(128, 288 - row * 128);
                    var offset = row * 128 * decoded.yStride() + col * 128;
                    var region = java.util.Arrays.copyOfRange(decoded.y(), offset, decoded.y().length);
                    assertEquals(99, psnr(tile, 128, region, decoded.yStride(), w, h, 1));
                }
            }
        }

        @ParameterizedTest(name = "{0} at {1} bits")
        @DisplayName("deep pictures keep their depth and format")
        @CsvSource({"YUV420, 10", "YUV444, 10", "YUV422, 12", "MONOCHROME, 10"})
        void deep(ChromaFormat format, int depth) {
            var planes = StillTesting.convert(StillTesting.foreman(0), switch (format) {
                case MONOCHROME -> SequenceHeader.LAYOUT_I400;
                case YUV420 -> SequenceHeader.LAYOUT_I420;
                case YUV422 -> SequenceHeader.LAYOUT_I422;
                case YUV444 -> SequenceHeader.LAYOUT_I444;
            }, depth);
            var builder = Yuv16Picture.builder().size(352, 288).chromaFormat(format).bitDepth(depth)
                    .color(ColorInfo.BT709_LIMITED).luma(planes.data()[0], planes.strides()[0]);
            if (format != ChromaFormat.MONOCHROME) {
                builder.chroma(planes.data()[1], planes.data()[2], planes.strides()[1]);
            }
            var picture = builder.build();
            var file = AvifEncoder.encode(picture, AvifEncoderOptions.lossy(80));
            var decoded = AvifDecoder.decode(MediaSource.of(file), ImageDecoderOptions.builder().highBitDepth(true)
                    .build())
                    .picture();
            var deep = (Yuv16Picture) decoded;
            assertEquals(depth, deep.bitDepth());
            assertEquals(format, deep.chromaFormat());
            long sse = 0;
            for (var y = 0; y < 288; y++) {
                for (var x = 0; x < 352; x++) {
                    var d = planes.data()[0][y * 352 + x] - deep.y()[y * deep.yStride() + x];
                    sse += (long) d * d;
                }
            }
            var max = (1 << depth) - 1.0;
            var psnr = 10 * Math.log10(max * max * 352 * 288 / Math.max(1, sse));
            assertTrue(psnr > 38, "psnr " + psnr);
        }

        @ParameterizedTest
        @DisplayName("the orientation survives the round trip")
        @EnumSource(Orientation.class)
        void orientation(Orientation orientation) {
            var file = AvifEncoder.encode(rgba(64, 48, false), AvifEncoderOptions.lossy(50).toBuilder()
                    .orientation(orientation).build());
            assertEquals(orientation, AvifDecoder.decode(MediaSource.of(file)).orientation());
        }

        @Test
        @DisplayName("a grid of more than 256 images per side exceeds the limits")
        void gridLimit() {
            var limits = MediaLimits.builder().maxDimension(20_000).build();
            var options = AvifEncoderOptions.lossy(50).toBuilder().gridTileSize(64).limits(limits).build();
            assertThrows(MediaException.LimitExceeded.class,
                    () -> AvifEncoder.encode(RgbaPicture.allocate(257 * 64, 64, false), options));
        }

        @Test
        @DisplayName("the ICC profile and the Exif block are embedded")
        void metadata() {
            var profile = ColorProfile.displayP3();
            var exif = new byte[]{'E', 'x', 'i', 'f', 0, 0, 'I', 'I', 42, 0, 8, 0, 0, 0, 0, 0};
            var file = AvifEncoder.encode(rgba(64, 48, false), AvifEncoderOptions.lossy(50).toBuilder()
                    .colorProfile(profile)
                    .exif(exif).xmp("<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"/>".getBytes()).build());
            var decoded = AvifDecoder.decode(MediaSource.of(file));
            assertArrayEquals(profile.toByteArray(), decoded.iccProfile().orElseThrow());
            assertTrue(decoded.exif().isPresent());
        }

        @Test
        @DisplayName("an HDR profile is embedded in its ICC form and decodes back as high dynamic range")
        void hdrProfile() {
            var profile = ColorProfile.of(new ColorInfo(com.github.auties00.smalt.image.MatrixCoefficients.BT2020_NCL,
                    com.github.auties00.smalt.image.ColorRange.LIMITED, com.github.auties00.smalt.image.ColorPrimaries.BT2020,
                    com.github.auties00.smalt.image.TransferCharacteristics.PQ));
            var file = AvifEncoder.encode(rgba(64, 48, false), AvifEncoderOptions.lossy(50).toBuilder()
                    .colorProfile(profile).build());
            var decoded = AvifDecoder.decode(MediaSource.of(file));
            assertArrayEquals(profile.toByteArray(), decoded.iccProfile().orElseThrow());
            assertTrue(decoded.colorProfile().orElseThrow().isHighDynamicRange());
            assertEquals(profile, decoded.colorProfile().orElseThrow());
        }

        @ParameterizedTest(name = "speed {0}")
        @DisplayName("every speed writes a stream ffmpeg decodes like Smalt")
        @CsvSource({"0", "2", "4", "6", "8", "10"})
        void speeds(int speed) throws Exception {
            var picture = foreman();
            var file = AvifEncoder.encode(picture, AvifEncoderOptions.lossy(55).toBuilder().speed(speed).build());
            var decoded = (YuvPicture) AvifDecoder.decode(MediaSource.of(file)).picture();
            assertTrue(psnr(picture.y(), 352, decoded.y(), decoded.yStride(), 352, 288, 1) > 33);
            assertArrayEquals(planes(decoded), ffmpegDecode(file, "yuv420p"));
        }

        @ParameterizedTest(name = "{0}x{1} tiles {2}x{3}")
        @DisplayName("tiled images decode with Smalt and ffmpeg alike")
        @CsvSource({"1100, 700, -1, -1", "600, 1100, -1, -1", "352, 288, 1, 1", "352, 288, 2, 0"})
        void tiles(int width, int height, int colsLog2, int rowsLog2) throws Exception {
            var picture = ImageOps.toYuv(rgba(width, height, false), ChromaFormat.YUV420, ColorInfo.JPEG);
            var options = colsLog2 < 0 ? AvifEncoderOptions.lossy(60).toBuilder().speed(8).build()
                    : AvifEncoderOptions.lossy(60).toBuilder().speed(8).tiles(colsLog2, rowsLog2).build();
            var file = AvifEncoder.encode(picture, options);
            var decoded = (YuvPicture) AvifDecoder.decode(MediaSource.of(file)).picture();
            assertTrue(psnr(picture.y(), picture.yStride(), decoded.y(), decoded.yStride(), width, height, 1) > 30);
            assertArrayEquals(planes(decoded), ffmpegDecode(file, "yuv420p"));
        }

        @ParameterizedTest(name = "case {0}")
        @DisplayName("random sizes, formats and qualities decode with Smalt and ffmpeg alike")
        @CsvSource({"1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12"})
        void random(int seed) throws Exception {
            var random = new java.util.Random(seed * 7919L);
            var width = 1 + random.nextInt(150);
            var height = 1 + random.nextInt(150);
            var alpha = random.nextBoolean();
            var picture = rgba(width, height, alpha);
            var quality = random.nextInt(101);
            var speed = random.nextInt(11);
            var options = AvifEncoderOptions.lossy(quality).toBuilder().speed(speed).build();
            if (random.nextInt(3) == 0) {
                options = options.toBuilder().chromaFormat(ChromaFormat.YUV444).build();
            }
            var file = AvifEncoder.encode(picture, options);
            var decoded = ImageOps.toRgba(AvifDecoder.decode(MediaSource.of(file)).picture());
            assertEquals(width, decoded.width());
            assertEquals(height, decoded.height());
            assertEquals(alpha, decoded.hasAlpha());
            var min = quality == 100 ? 99 : quality >= 50 ? 24 : 12;
            var psnr = psnr(picture.pixels(), picture.stride(), decoded.pixels(), decoded.stride(), width, height, 4);
            assertTrue(psnr >= min, "psnr " + psnr + " at quality " + quality + " speed " + speed);
            ffmpegDecode(file, "rgba");
        }

        @Test
        @DisplayName("Images encodes AVIF through the provider")
        void registry() {
            assertTrue(Images.isEncodable(ImageFormat.AVIF));
            assertTrue(Images.isAnimationEncodable(ImageFormat.AVIF));
        }
    }

    @Nested
    @DisplayName("animations")
    class Animation {
        @Test
        @DisplayName("an image sequence decodes frame by frame with its durations")
        void sequence() throws Exception {
            var out = new ByteArrayOutputStream();
            var sink = MediaSink.of(out);
            try (var writer = AvifEncoder.openAnimationWriter(96, 64, AvifEncoderOptions.lossy(60), sink)) {
                for (var i = 0; i < 4; i++) {
                    var frame = rgba(96, 64, i >= 2);
                    writer.write(frame, 40_000L * (i + 1));
                }
                writer.finish();
            }
            var file = out.toByteArray();
            try (var reader = AvifDecoder.openAnimation(MediaSource.of(file))) {
                var count = 0;
                for (var frame = reader.read(); frame != null; frame = reader.read()) {
                    assertEquals(40_000L * (count + 1), frame.durationMicros());
                    count++;
                }
                assertEquals(4, count);
            }
            assertEquals(96 * 64 * 4, ffmpegDecode(file, "rgba").length);
        }

        private static RgbaPicture panned(RgbaPicture source, int width, int height, int shift) {
            var out = RgbaPicture.allocate(width, height, false);
            for (var y = 0; y < height; y++) {
                System.arraycopy(source.pixels(), (y + shift / 2) * source.stride() + shift * 4, out.pixels(),
                        y * out.stride(), width * 4);
            }
            return out;
        }

        private static byte[] animation(AvifEncoderOptions options, List<RgbaPicture> frames) throws Exception {
            var out = new ByteArrayOutputStream();
            try (var writer = AvifEncoder.openAnimationWriter(frames.getFirst().width(), frames.getFirst()
                    .height(), options,
                    MediaSink.of(out))) {
                for (var frame : frames) {
                    writer.write(frame, 40_000);
                }
                writer.finish();
            }
            return out.toByteArray();
        }

        private static boolean hasBox(byte[] file, String type) {
            var name = type.getBytes(StandardCharsets.US_ASCII);
            for (var i = 0; i + 4 <= file.length; i++) {
                if (Arrays.equals(file, i, i + 4, name, 0, 4)) {
                    return true;
                }
            }
            return false;
        }

        @Test
        @DisplayName("a failure of the sink reaches the caller unchanged")
        void sinkFailure() {
            var sink = MediaSink.of(new OutputStream() {
                @Override
                public void write(int b) {
                    throw new IllegalStateException("sink closed");
                }

                @Override
                public void write(byte[] b, int off, int len) {
                    throw new IllegalStateException("sink closed");
                }
            });
            var writer = AvifEncoder.openAnimationWriter(64, 48, AvifEncoderOptions.lossy(60).toBuilder().speed(10)
                    .interFrames(false).build(), sink);
            writer.write(rgba(64, 48, false), 40_000);
            var failure = assertThrows(IllegalStateException.class, writer::finish);
            assertEquals("sink closed", failure.getMessage());
        }

        @ParameterizedTest(name = "inter frames {0}")
        @CsvSource({"true", "false"})
        @DisplayName("an image sequence carries the Exif and XMP metadata, describing its primary item")
        void metadata(boolean interFrames) throws Exception {
            var exif = new byte[]{'E', 'x', 'i', 'f', 0, 0, 'I', 'I', 42, 0, 8, 0, 0, 0, 0, 0};
            var xmp = "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"/>".getBytes(StandardCharsets.UTF_8);
            var options = AvifEncoderOptions.lossy(60).toBuilder().speed(10).interFrames(interFrames).exif(exif)
                    .xmp(xmp).build();
            var data = animation(options, List.of(rgba(64, 48, false), rgba(64, 48, true)));
            var source = MediaSource.of(data);
            var file = HeifFile.parse(source, MediaLimits.DEFAULT);
            var primary = file.primaryItem().id();
            var exifItem = file.items().stream().filter(item -> item.type().equals("Exif")).findFirst().orElseThrow();
            var xmpItem = file.items().stream().filter(item -> item.type().equals("mime")).findFirst().orElseThrow();
            assertTrue(exifItem.references("cdsc", primary));
            assertTrue(xmpItem.references("cdsc", primary));
            assertEquals("application/rdf+xml", xmpItem.contentType());
            var exifData = file.readItem(source, exifItem, MediaLimits.DEFAULT);
            assertArrayEquals(new byte[]{0, 0, 0, 6}, Arrays.copyOf(exifData, 4));
            assertArrayEquals(exif, Arrays.copyOfRange(exifData, 4, exifData.length));
            assertArrayEquals(xmp, file.readItem(source, xmpItem, MediaLimits.DEFAULT));
            try (var reader = AvifDecoder.openAnimation(MediaSource.of(data))) {
                assertEquals(2, reader.frameCount());
            }
        }

        @Test
        @DisplayName("predicted frames shrink a panning sequence, which decodes with Smalt and ffmpeg alike")
        void predicted() throws Exception {
            var source = ImageOps.toRgba(foreman());
            var frames = new ArrayList<RgbaPicture>();
            for (var i = 0; i < 10; i++) {
                frames.add(panned(source, 160, 96, i * 3));
            }
            var predicted = animation(AvifEncoderOptions.lossy(60), frames);
            var keys = animation(AvifEncoderOptions.lossy(60).toBuilder().interFrames(false).build(), frames);
            assertTrue(hasBox(predicted, "stss"));
            assertFalse(hasBox(keys, "stss"));
            assertTrue(predicted.length * 2 < keys.length, predicted.length + " vs " + keys.length + " bytes");
            try (var reader = AvifDecoder.openAnimation(MediaSource.of(predicted))) {
                assertEquals(10, reader.frameCount());
                var count = 0;
                for (var frame = reader.read(); frame != null; frame = reader.read()) {
                    var original = frames.get(count);
                    var decoded = (RgbaPicture) frame.picture();
                    var psnr = psnr(original.pixels(), original.stride(), decoded.pixels(), decoded.stride(), 160, 96, 4);
                    assertTrue(psnr > 28, "frame " + count + " psnr " + psnr);
                    assertEquals(40_000, frame.durationMicros());
                    count++;
                }
                assertEquals(10, count);
            }
            Oracle.assume(Oracle.Tool.FFMPEG);
            var file = Files.createTempFile("avisenc", ".avif");
            try {
                Files.write(file, predicted);
                // stream 0 is the primary still item, stream 1 the image sequence
                var raw = Oracle.run(Oracle.Tool.FFMPEG, Duration.ofMinutes(2), List.of("-v", "error", "-xerror",
                        "-c:v", "libaom-av1", "-i", file.toString(), "-map", "0:1", "-f", "rawvideo", "-pix_fmt",
                        "rgba", "-"));
                var frameSize = 160 * 96 * 4;
                assertEquals(frameSize * 10, raw.length);
                for (var i = 0; i < 10; i++) {
                    var original = frames.get(i);
                    var psnr = psnr(original.pixels(), original.stride(),
                            Arrays.copyOfRange(raw, i * frameSize, (i + 1) * frameSize), 160 * 4, 160, 96, 4);
                    assertTrue(psnr > 26, "ffmpeg frame " + i + " psnr " + psnr);
                }
            } finally {
                Files.deleteIfExists(file);
            }
        }
    }

    @Nested
    @DisplayName("presets")
    class Presets {
        @Test
        @DisplayName("the presets hold the documented quality, speed and format")
        void presets() {
            var web = AvifEncoderOptions.forWeb();
            assertEquals(60, web.quality());
            assertEquals(8, web.speed());
            var distribution = AvifEncoderOptions.forDistribution();
            assertEquals(70, distribution.quality());
            assertEquals(4, distribution.speed());
            var archive = AvifEncoderOptions.forArchive();
            assertEquals(90, archive.quality());
            assertEquals(2, archive.speed());
            assertEquals(Optional.of(ChromaFormat.YUV444), archive.chromaFormat());
            assertEquals(10, archive.bitDepth().orElseThrow());
            assertTrue(AvifEncoderOptions.defaults().interFrames());
            var off = AvifEncoderOptions.builder().interFrames(false).build();
            assertFalse(off.interFrames());
            assertEquals(off, off.toBuilder().build());
            assertNotEquals(off, AvifEncoderOptions.defaults());
        }
    }
}
