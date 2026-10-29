package com.github.auties00.smalt.property;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.AnimationFrame;
import com.github.auties00.smalt.image.Channel8Picture;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ImageEncoderOptions;
import com.github.auties00.smalt.image.ImageFormat;
import com.github.auties00.smalt.image.Images;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.image.metadata.ImageMetadataEdit;
import com.github.auties00.smalt.image.metadata.ImageMetadataEditor;
import com.github.auties00.smalt.internal.heif.HeifFile;
import com.github.auties00.smalt.internal.heif.HeifProperty;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.metadata.XmpPacket;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.Tolerance;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Re-reads the images Smalt writes with the formats' reference decoders: libpng (through
 * {@code tools/testdata/png-oracle}), libjpeg-turbo's djpeg, libwebp's dwebp, OpenJPEG's opj_decompress, libtiff's
 * tools and ffmpeg's decoders (dav1d for AVIF, its HEVC decoder for HEIC), and requires the pixels to equal Smalt's own decode (or the source, for
 * lossless coding) within the tolerance declared for the format; JPEG-LS, which no image format constant names,
 * through ffmpeg's decoder. Metadata edits are checked the same way: a reference
 * decoder reads the edited file without a warning and to exactly the pixels of the original.
 */
@DisplayName("Images decoded by the reference decoders")
class ReferenceImageDecodeTest {
    /** Lossless formats and normative decoders: the reference decode equals the source or Smalt's decode exactly. */
    private static final Tolerance EXACT = Tolerance.exact();

    private static final int WIDTH = 61;

    private static final int HEIGHT = 37;

    private static final Path DIRECTORY = Path.of("target", "reference-images");

    /** The image formats Smalt encodes whose reference decoding this class covers, and how. */
    private static final Set<ImageFormat> COVERED = Set.of(ImageFormat.PNG, ImageFormat.JPEG, ImageFormat.WEBP,
            ImageFormat.GIF, ImageFormat.BMP, ImageFormat.TIFF, ImageFormat.ICO, ImageFormat.JP2, ImageFormat.AVIF,
            ImageFormat.ANI, ImageFormat.HEIC);

    /** A picture with gradients, edges and noise, and with alpha when asked. */
    static RgbaPicture source(boolean alpha) {
        var pixels = new byte[WIDTH * HEIGHT * 4];
        var random = new Random(17);
        for (var y = 0; y < HEIGHT; y++) {
            for (var x = 0; x < WIDTH; x++) {
                var i = (y * WIDTH + x) * 4;
                var edge = ((x / 8) + (y / 8)) % 2 == 0 ? 60 : 0;
                pixels[i] = (byte) Math.min(255, x * 255 / WIDTH + edge);
                pixels[i + 1] = (byte) Math.min(255, y * 255 / HEIGHT + random.nextInt(16));
                pixels[i + 2] = (byte) (128 + 100 * Math.sin((x + 2 * y) / 7.0));
                pixels[i + 3] = alpha ? (byte) Math.min(255, (x * 3 + y * 5) % 256) : (byte) 255;
            }
        }
        return RgbaPicture.of(WIDTH, HEIGHT, WIDTH * 4, pixels, alpha);
    }

    static Path save(String name, byte[] data) {
        try {
            Files.createDirectories(DIRECTORY);
            var file = DIRECTORY.resolve(name);
            Files.write(file, data);
            return file.toAbsolutePath();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    static byte[] read(Path file) {
        try {
            return Files.readAllBytes(file);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    /** Returns the RGBA samples of a decoded picture, without row padding; RGB and gray gain an opaque alpha. */
    static byte[] rgba(Picture picture) {
        var width = picture.width();
        var height = picture.height();
        var out = new byte[width * height * 4];
        switch (picture) {
            case RgbaPicture rgba -> {
                for (var y = 0; y < height; y++) {
                    System.arraycopy(rgba.pixels(), y * rgba.stride(), out, y * width * 4, width * 4);
                }
            }
            case Channel8Picture channels -> {
                var count = channels.channelCount();
                for (var y = 0; y < height; y++) {
                    for (var x = 0; x < width; x++) {
                        var at = y * channels.stride() + x * count;
                        var i = (y * width + x) * 4;
                        for (var c = 0; c < 3; c++) {
                            out[i + c] = channels.pixels()[at + (count >= 3 ? c : 0)];
                        }
                        out[i + 3] = count == 4 || count == 2 ? channels.pixels()[at + count - 1] : (byte) 255;
                    }
                }
            }
            default -> throw new AssertionError("no RGBA view of " + picture.getClass().getSimpleName());
        }
        return out;
    }

    /** Returns RGBA samples with the color of every transparent pixel cleared. */
    static byte[] visible(byte[] rgba) {
        var out = rgba.clone();
        for (var i = 0; i < out.length; i += 4) {
            if (out[i + 3] == 0) {
                out[i] = out[i + 1] = out[i + 2] = 0;
            }
        }
        return out;
    }

    /** Returns the Y', Cb and Cr planes of a picture, one after the other, without row padding. */
    static byte[] planes(YuvPicture yuv) {
        var out = new ByteArrayOutputStream();
        for (var y = 0; y < yuv.height(); y++) {
            out.write(yuv.y(), y * yuv.yStride(), yuv.width());
        }
        if (yuv.chromaFormat() != ChromaFormat.MONOCHROME) {
            for (var plane : List.of(yuv.u(), yuv.v())) {
                for (var y = 0; y < yuv.chromaHeight(); y++) {
                    out.write(plane, y * yuv.uvStride(), yuv.chromaWidth());
                }
            }
        }
        return out.toByteArray();
    }

    /** Converts an {@code hvcC} record and a length-prefixed payload into an Annex B stream. */
    static byte[] annexB(byte[] hvcC, byte[] payload) {
        var out = new ByteArrayOutputStream();
        var lengthSize = (hvcC[21] & 3) + 1;
        var position = 23;
        for (var array = 0; array < (hvcC[22] & 0xFF); array++) {
            var count = ((hvcC[position + 1] & 0xFF) << 8) | (hvcC[position + 2] & 0xFF);
            position += 3;
            for (var n = 0; n < count; n++) {
                var length = ((hvcC[position] & 0xFF) << 8) | (hvcC[position + 1] & 0xFF);
                out.writeBytes(new byte[]{0, 0, 0, 1});
                out.write(hvcC, position + 2, length);
                position += 2 + length;
            }
        }
        for (var offset = 0; offset < payload.length; ) {
            var length = 0;
            for (var i = 0; i < lengthSize; i++) {
                length = (length << 8) | (payload[offset + i] & 0xFF);
            }
            out.writeBytes(new byte[]{0, 0, 0, 1});
            out.write(payload, offset + lengthSize, length);
            offset += lengthSize + length;
        }
        return out.toByteArray();
    }

    /** Returns the top left rectangle of tight 8-bit 4:2:0 planes. */
    static byte[] crop420(byte[] planes, int width, int height, int cropWidth, int cropHeight) {
        var out = new ByteArrayOutputStream();
        for (var y = 0; y < cropHeight; y++) {
            out.write(planes, y * width, cropWidth);
        }
        var chromaWidth = (width + 1) / 2;
        var chromaHeight = (height + 1) / 2;
        for (var plane = 0; plane < 2; plane++) {
            var base = width * height + plane * chromaWidth * chromaHeight;
            for (var y = 0; y < (cropHeight + 1) / 2; y++) {
                out.write(planes, base + y * chromaWidth, (cropWidth + 1) / 2);
            }
        }
        return out.toByteArray();
    }

    static byte[] smalt(byte[] file) {
        return rgba(Images.decode(MediaSource.of(file)).picture());
    }

    /** Decodes a file with ffmpeg's decoder of its format into tight RGBA frames. */
    static byte[] ffmpegRgba(Path file, int frames) {
        return Oracle.decodeVideo(file, "rgba", frames);
    }

    /** Reads the samples of a binary PNM or PAM file into RGBA, opaque for PPM and PGM. */
    static byte[] pnm(byte[] data) {
        var position = 0;
        var fields = new ArrayList<String>();
        var magic = new String(data, 0, 2, StandardCharsets.US_ASCII);
        var pam = magic.equals("P7");
        var width = 0;
        var height = 0;
        var depth = magic.equals("P5") ? 1 : 3;
        if (pam) {
            var text = new String(data, 0, Math.min(data.length, 512), StandardCharsets.US_ASCII);
            var end = text.indexOf("ENDHDR\n");
            for (var line : text.substring(3, end).split("\n")) {
                var parts = line.trim().split("\\s+");
                switch (parts[0]) {
                    case "WIDTH" -> width = Integer.parseInt(parts[1]);
                    case "HEIGHT" -> height = Integer.parseInt(parts[1]);
                    case "DEPTH" -> depth = Integer.parseInt(parts[1]);
                    case "MAXVAL" -> assertEquals("255", parts[1], "8-bit PAM");
                    default -> {
                    }
                }
            }
            position = end + 7;
        } else {
            position = 2;
            while (fields.size() < 3) {
                while (Character.isWhitespace(data[position])) {
                    position++;
                }
                if (data[position] == '#') {
                    while (data[position] != '\n') {
                        position++;
                    }
                    continue;
                }
                var start = position;
                while (!Character.isWhitespace(data[position])) {
                    position++;
                }
                fields.add(new String(data, start, position - start, StandardCharsets.US_ASCII));
            }
            width = Integer.parseInt(fields.get(0));
            height = Integer.parseInt(fields.get(1));
            assertEquals("255", fields.get(2), "8-bit PNM");
            position++;
        }
        var out = new byte[width * height * 4];
        for (var pixel = 0; pixel < width * height; pixel++) {
            var at = position + pixel * depth;
            for (var c = 0; c < 3; c++) {
                out[pixel * 4 + c] = data[at + (depth >= 3 ? c : 0)];
            }
            out[pixel * 4 + 3] = depth == 4 || depth == 2 ? data[at + depth - 1] : (byte) 255;
        }
        return out;
    }

    static byte[] dwebp(Path file) {
        var out = file.resolveSibling(file.getFileName() + ".pam");
        Oracle.run(Oracle.Tool.DWEBP, Duration.ofMinutes(1), List.of(file.toString(), "-pam", "-o", out.toString()));
        return pnm(read(out));
    }

    static byte[] djpegLuma(Path file) {
        return Arrays.copyOf(pnmGray(Oracle.run(Oracle.Tool.DJPEG, Duration.ofMinutes(1), List.of("-dct", "int",
                "-grayscale", "-pnm", file.toString()))), WIDTH * HEIGHT);
    }

    private static byte[] pnmGray(byte[] pgm) {
        var rgba = pnm(pgm);
        var out = new byte[rgba.length / 4];
        for (var i = 0; i < out.length; i++) {
            out[i] = rgba[i * 4];
        }
        return out;
    }

    static byte[] pngdump(Path file) {
        return Oracle.run(Oracle.Tool.PNGDUMP, Duration.ofMinutes(1), List.of("pixels", file.toString()));
    }

    static byte[] opj(Path file) {
        var out = file.resolveSibling(file.getFileName() + ".ppm");
        Oracle.run(Oracle.Tool.OPJ_DECOMPRESS, Duration.ofMinutes(1), List.of("-quiet", "-i", file.toString(), "-o",
                out.toString()));
        return pnm(read(out));
    }

    @Test
    @DisplayName("every image format Smalt encodes has a reference decoder here")
    void declared() {
        var missing = new ArrayList<ImageFormat>();
        for (var format : ImageFormat.values()) {
            if (Images.isEncodable(format) && !COVERED.contains(format)) {
                missing.add(format);
            }
        }
        assertEquals(List.of(), missing, "add a reference decode of these encoders");
    }

    @Nested
    @DisplayName("encoder output")
    class Encoders {
        @ParameterizedTest(name = "alpha {0}")
        @ValueSource(booleans = {false, true})
        @DisplayName("PNG decodes in libpng to the source exactly")
        void png(boolean alpha) {
            var source = source(alpha);
            var file = save("png-" + alpha + ".png", Images.encode(source, ImageFormat.PNG));
            var info = new String(Oracle.run(Oracle.Tool.PNGDUMP, Duration.ofMinutes(1), List.of("info",
                    file.toString())), StandardCharsets.UTF_8);
            assertFalse(info.contains("warning"), info);
            EXACT.assertWithin("libpng decode", rgba(source), pngdump(file));
        }

        @Test
        @DisplayName("JPEG luma decodes in djpeg -dct int exactly as in Smalt")
        void jpeg() {
            var file = save("jpeg.jpg", Images.encode(source(false), ImageFormat.JPEG));
            var picture = (YuvPicture) Images.decode(MediaSource.of(read(file))).picture();
            var luma = new byte[WIDTH * HEIGHT];
            for (var y = 0; y < HEIGHT; y++) {
                System.arraycopy(picture.y(), y * picture.yStride(), luma, y * WIDTH, WIDTH);
            }
            EXACT.assertWithin("djpeg luma", djpegLuma(file), luma);
            // ffmpeg's IDCT is not libjpeg's: T.81 leaves its rounding to the decoder
            Tolerance.psnr(45).and(Tolerance.maxAbs(3)).assertWithin("ffmpeg luma",
                    Arrays.copyOf(Oracle.decodeVideo(file, List.of(), picture.chromaFormat() == ChromaFormat.YUV444
                            ? "yuvj444p" : picture.chromaFormat() == ChromaFormat.YUV422 ? "yuvj422p" : "yuvj420p", 1),
                            WIDTH * HEIGHT), luma);
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"lossless", "lossless-alpha", "lossy", "lossy-alpha"})
        @DisplayName("WebP decodes in dwebp exactly as in Smalt, and losslessly to the source")
        void webp(String mode) {
            var alpha = mode.endsWith("alpha");
            var lossless = mode.startsWith("lossless");
            var source = source(alpha);
            var options = lossless ? ImageEncoderOptions.lossless() : ImageEncoderOptions.ofQuality(80);
            var file = save("webp-" + mode + ".webp", Images.encode(source, ImageFormat.WEBP, options));
            var reference = dwebp(file);
            var decoded = Images.decode(MediaSource.of(read(file))).picture();
            if (decoded instanceof YuvPicture yuv) {
                // a lossy picture decodes to Y'CbCr: dwebp's raw samples, before its conversion to RGB
                var raw = file.resolveSibling(file.getFileName() + ".yuv");
                Oracle.run(Oracle.Tool.DWEBP, Duration.ofMinutes(1), List.of(file.toString(), "-yuv", "-o",
                        raw.toString()));
                var planes = planes(yuv);
                EXACT.assertWithin("dwebp Y'CbCr", Arrays.copyOf(read(raw), planes.length), planes);
                if (yuv.hasAlpha()) {
                    var alphaPlane = new byte[WIDTH * HEIGHT];
                    for (var y = 0; y < HEIGHT; y++) {
                        System.arraycopy(yuv.alpha(), y * yuv.alphaStride(), alphaPlane, y * WIDTH, WIDTH);
                    }
                    var referenceAlpha = new byte[WIDTH * HEIGHT];
                    for (var i = 0; i < referenceAlpha.length; i++) {
                        referenceAlpha[i] = reference[i * 4 + 3];
                    }
                    EXACT.assertWithin("dwebp alpha", referenceAlpha, alphaPlane);
                }
            } else {
                EXACT.assertWithin("dwebp", reference, rgba(decoded));
            }
            if (lossless) {
                // lossless coding keeps every visible sample; the color under a transparent pixel is free
                EXACT.assertWithin("source", visible(rgba(source)), visible(reference));
            }
            var info = new String(Oracle.run("webpinfo", Duration.ofMinutes(1), null, List.of(file.toString())),
                    StandardCharsets.UTF_8);
            assertTrue(info.contains("No error detected"), info);
        }

        @ParameterizedTest(name = "NEAR {0}")
        @ValueSource(ints = {0, 2})
        @DisplayName("JPEG-LS decodes in ffmpeg to Smalt's decode exactly, and to the source within NEAR")
        void jpegLs(int near) {
            var source = source(false);
            // ffmpeg's JPEG-LS decoder lacks sample-interleaved scans
            for (var interleave : List.of(com.github.auties00.smalt.image.jpeg.JpegLsEncoderOptions.Interleave.NONE,
                    com.github.auties00.smalt.image.jpeg.JpegLsEncoderOptions.Interleave.LINE)) {
                var file = save("jpegls-" + near + "-" + interleave + ".jls",
                        com.github.auties00.smalt.image.jpeg.JpegLsEncoder.encode(source,
                                com.github.auties00.smalt.image.jpeg.JpegLsEncoderOptions.builder().nearLossless(near)
                                        .interleave(interleave).build()));
                EXACT.assertWithin("ffmpeg JPEG-LS " + interleave, ffmpegRgba(file, 1), smalt(read(file)));
                Tolerance.maxAbs(near).assertWithin("JPEG-LS source " + interleave, rgba(source), smalt(read(file)));
            }
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(value = ImageFormat.class, names = {"GIF", "BMP", "TIFF", "ICO"})
        @DisplayName("GIF, BMP, TIFF and ICO decode in ffmpeg to Smalt's decode exactly")
        void ffmpeg(ImageFormat format) {
            var source = source(false);
            var extension = format.name().toLowerCase(Locale.ROOT);
            var file = save("still." + extension, Images.encode(source, format));
            EXACT.assertWithin("ffmpeg " + extension, ffmpegRgba(file, 1), smalt(read(file)));
            if (format != ImageFormat.GIF) {
                EXACT.assertWithin(extension + " source", rgba(source), smalt(read(file)));
            }
        }

        @Test
        @DisplayName("TIFF passes libtiff's reader without a warning")
        void tiff() {
            var file = save("still-alpha.tiff", Images.encode(source(true), ImageFormat.TIFF));
            var result = Oracle.execute(Oracle.Tool.TIFFINFO, Duration.ofMinutes(1), null, List.of("-D", file.toString()));
            assertEquals(0, result.exitCode(), result.stderr());
            assertFalse(result.stderr().contains("Warning") || result.stderr().contains("Error"), result.stderr());
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"lossless", "lossy"})
        @DisplayName("JPEG 2000 decodes in OpenJPEG exactly as in Smalt, and losslessly to the source")
        void jp2(String mode) {
            var source = source(false);
            var options = mode.equals("lossless") ? ImageEncoderOptions.lossless() : ImageEncoderOptions.ofQuality(60);
            var file = save("jp2-" + mode + ".jp2", Images.encode(source, ImageFormat.JP2, options));
            var reference = opj(file);
            EXACT.assertWithin("opj_decompress", reference, smalt(read(file)));
            if (mode.equals("lossless")) {
                EXACT.assertWithin("source", rgba(source), reference);
            }
        }

        @Test
        @DisplayName("AVIF decodes in dav1d through ffmpeg exactly as in Smalt")
        void avif() {
            var file = save("still.avif", Images.encode(source(false), ImageFormat.AVIF));
            var picture = Images.decode(MediaSource.of(read(file))).picture();
            if (picture instanceof YuvPicture yuv) {
                var format = switch (yuv.chromaFormat()) {
                    case YUV444 -> "yuv444p";
                    case YUV422 -> "yuv422p";
                    case MONOCHROME -> "gray";
                    default -> "yuv420p";
                };
                EXACT.assertWithin("dav1d", Oracle.decodeVideo(file, List.of("-c:v", "libdav1d"), format, 1),
                        planes(yuv));
            } else {
                // Smalt converts to RGB; ffmpeg's conversion rounds differently
                Tolerance.psnr(40).assertWithin("dav1d", Oracle.decodeVideo(file, List.of("-c:v", "libdav1d"), "rgba",
                        1), rgba(picture));
            }
        }

        @Test
        @DisplayName("HEIC decodes in ffmpeg's HEVC decoder exactly as in Smalt")
        void heic() {
            var bytes = Images.encode(source(false), ImageFormat.HEIC);
            var yuv = (YuvPicture) Images.decode(MediaSource.of(bytes)).picture();
            // ffmpeg 7.1 rejects every HEIF file whose items carry a clean aperture, which this odd-sized picture's
            // does, so its HEVC decoder reads the item's payload as an Annex B stream, and the coded picture, one
            // sample larger on each odd side, is cropped here as the clean aperture crops it
            var file = HeifFile.parse(MediaSource.of(bytes), MediaLimits.DEFAULT, true);
            var item = file.primaryItem();
            var hvcC = item.property(HeifProperty.HevcConfiguration.class).record();
            var stream = save("still-heic.hevc", annexB(hvcC, file.readItem(MediaSource.of(bytes), item,
                    MediaLimits.DEFAULT)));
            var raw = Oracle.decodeVideo(stream, List.of("-f", "hevc"), "yuv420p", 1);
            EXACT.assertWithin("ffmpeg hevc", crop420(raw, (WIDTH + 1) & ~1, (HEIGHT + 1) & ~1, WIDTH, HEIGHT),
                    planes(yuv));
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(value = ImageFormat.class, names = {"PNG", "GIF"})
        @DisplayName("animated PNG and GIF decode in ffmpeg frame by frame exactly as in Smalt")
        void animations(ImageFormat format) {
            var extension = format == ImageFormat.PNG ? "apng" : "gif";
            var sink = MediaSink.memory();
            var frames = new ArrayList<byte[]>();
            try (var writer = Images.openAnimationWriter(format, WIDTH, HEIGHT, sink)) {
                for (var index = 0; index < 4; index++) {
                    var frame = source(false);
                    var pixels = frame.pixels().clone();
                    for (var i = 0; i < pixels.length; i += 4) {
                        pixels[i] = (byte) (pixels[i] + 40 * index);
                    }
                    writer.write(RgbaPicture.of(WIDTH, HEIGHT, WIDTH * 4, pixels, false), 100_000);
                }
                writer.finish();
            }
            var file = save("animation." + extension, sink.toByteArray());
            try (var source = MediaSource.of(file); var reader = Images.openAnimation(source)) {
                for (AnimationFrame frame = reader.read(); frame != null; frame = reader.read()) {
                    frames.add(rgba(frame.picture()));
                }
            }
            assertEquals(4, frames.size());
            var ours = new ByteArrayOutputStream();
            frames.forEach(ours::writeBytes);
            EXACT.assertFrames("ffmpeg " + extension, Oracle.decodeVideo(file, List.of("-c:v", extension), "rgba", 0),
                    ours.toByteArray(), WIDTH * HEIGHT * 4);
        }

        @Test
        @DisplayName("ANI frames, ICO files in a RIFF list, decode in ffmpeg exactly as in Smalt")
        void ani() {
            var sink = MediaSink.memory();
            try (var writer = Images.openAnimationWriter(ImageFormat.ANI, 32, 32, sink)) {
                for (var index = 0; index < 3; index++) {
                    var pixels = new byte[32 * 32 * 4];
                    var random = new Random(index);
                    random.nextBytes(pixels);
                    for (var i = 3; i < pixels.length; i += 4) {
                        pixels[i] = (byte) 255;
                    }
                    writer.write(RgbaPicture.of(32, 32, 32 * 4, pixels, false), 100_000);
                }
                writer.finish();
            }
            var data = sink.toByteArray();
            var icons = new ArrayList<byte[]>();
            for (var at = 12; at + 8 <= data.length; ) {
                var id = new String(data, at, 4, StandardCharsets.US_ASCII);
                var size = (data[at + 4] & 0xFF) | (data[at + 5] & 0xFF) << 8 | (data[at + 6] & 0xFF) << 16
                        | (data[at + 7] & 0xFF) << 24;
                if (id.equals("LIST")) {
                    at += 12;
                    continue;
                }
                if (id.equals("icon")) {
                    icons.add(Arrays.copyOfRange(data, at + 8, at + 8 + size));
                }
                at += 8 + size + (size & 1);
            }
            assertEquals(3, icons.size(), "icon chunks");
            var frames = new ArrayList<byte[]>();
            try (var reader = Images.openAnimation(MediaSource.of(data))) {
                for (var frame = reader.read(); frame != null; frame = reader.read()) {
                    frames.add(rgba(frame.picture()));
                }
            }
            for (var index = 0; index < icons.size(); index++) {
                var icon = save("ani-frame-" + index + ".ico", icons.get(index));
                EXACT.assertWithin("frame " + index, ffmpegRgba(icon, 1), frames.get(index));
            }
        }
    }

    @Nested
    @DisplayName("metadata edits")
    class MetadataEdits {
        private static final ImageMetadataEdit EDIT = ImageMetadataEdit.builder()
                .xmp(XmpPacket.parse("<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><rdf:RDF xmlns:rdf="
                        + "\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\"><rdf:Description rdf:about=\"\" "
                        + "xmlns:dc=\"http://purl.org/dc/elements/1.1/\"><dc:format>image</dc:format>"
                        + "</rdf:Description></rdf:RDF></x:xmpmeta>"))
                .build();

        private void check(String name, byte[] original, java.util.function.Function<Path, byte[]> reference) {
            check(name, original, EDIT, reference);
        }

        private void check(String name, byte[] original, ImageMetadataEdit edit,
                           java.util.function.Function<Path, byte[]> reference) {
            var originalFile = save("edit-original-" + name, original);
            var edited = ImageMetadataEditor.apply(original, edit);
            assertFalse(Arrays.equals(original, edited), "the edit changed the file");
            var editedFile = save("edit-xmp-" + name, edited);
            var stripped = save("edit-stripped-" + name, ImageMetadataEditor.stripAll(edited));
            var expected = reference.apply(originalFile);
            EXACT.assertWithin(name + " after an XMP edit", expected, reference.apply(editedFile));
            EXACT.assertWithin(name + " after stripping", expected, reference.apply(stripped));
        }

        @Test
        @DisplayName("JPEG edits keep djpeg's pixels")
        void jpeg() {
            check("jpeg.jpg", Images.encode(source(false), ImageFormat.JPEG), file -> pnm(Oracle.run(Oracle.Tool.DJPEG,
                    Duration.ofMinutes(1), List.of("-dct", "int", "-pnm", file.toString()))));
        }

        @Test
        @DisplayName("PNG edits keep libpng's pixels and raise no libpng warning")
        void png() {
            check("png.png", Images.encode(source(true), ImageFormat.PNG), file -> {
                var info = new String(Oracle.run(Oracle.Tool.PNGDUMP, Duration.ofMinutes(1), List.of("info",
                        file.toString())), StandardCharsets.UTF_8);
                assertFalse(info.contains("warning"), info);
                return pngdump(file);
            });
        }

        @Test
        @DisplayName("WebP edits keep dwebp's pixels")
        void webp() {
            check("webp.webp", Images.encode(source(true), ImageFormat.WEBP, ImageEncoderOptions.lossless()),
                    ReferenceImageDecodeTest::dwebp);
        }

        @Test
        @DisplayName("TIFF edits keep ffmpeg's pixels and libtiff reads them without a warning")
        void tiff() {
            check("tiff.tiff", Images.encode(source(false), ImageFormat.TIFF), file -> {
                var result = Oracle.execute(Oracle.Tool.TIFFINFO, Duration.ofMinutes(1), null, List.of("-D",
                        file.toString()));
                assertEquals(0, result.exitCode(), result.stderr());
                assertFalse(result.stderr().contains("Warning") || result.stderr().contains("Error"), result.stderr());
                return ffmpegRgba(file, 1);
            });
        }

        @Test
        @DisplayName("GIF edits keep ffmpeg's pixels")
        void gif() {
            check("gif.gif", Images.encode(source(false), ImageFormat.GIF), file -> ffmpegRgba(file, 1));
        }

        @Test
        @DisplayName("AVIF edits, which move the coded items, keep dav1d's pixels")
        void avif() {
            check("avif.avif", Images.encode(source(false), ImageFormat.AVIF), file -> Oracle.decodeVideo(file,
                    List.of("-c:v", "libdav1d"), "rgba", 1));
        }

        @Test
        @DisplayName("JPEG 2000 edits, which add uuid boxes and rewrite the JP2 header, keep opj_decompress's pixels")
        void jp2() {
            var edit = EDIT.toBuilder().colorProfile(com.github.auties00.smalt.image.color.ColorProfile.srgb()).build();
            check("jp2.jp2", Images.encode(source(false), ImageFormat.JP2), edit, ReferenceImageDecodeTest::opj);
        }

        @Test
        @DisplayName("BMP profile edits, which turn the header into a V5 one, keep ffmpeg's pixels")
        void bmp() {
            var edit = ImageMetadataEdit.builder().colorProfile(com.github.auties00.smalt.image.color.ColorProfile.srgb())
                    .build();
            check("bmp.bmp", Images.encode(source(false), ImageFormat.BMP), edit, file -> ffmpegRgba(file, 1));
        }
    }
}
