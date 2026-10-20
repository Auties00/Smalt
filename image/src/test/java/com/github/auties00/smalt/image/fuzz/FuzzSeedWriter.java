package com.github.auties00.smalt.image.fuzz;

import com.github.auties00.smalt.image.Orientation;
import com.github.auties00.smalt.image.color.ColorProfile;
import com.github.auties00.smalt.image.exif.ExifData;
import com.github.auties00.smalt.image.exif.ExifValue;
import com.github.auties00.smalt.image.metadata.ImageMetadataEdit;
import com.github.auties00.smalt.image.metadata.ImageMetadataEditor;
import com.github.auties00.smalt.image.metadata.IptcData;
import com.github.auties00.smalt.image.webp.WebpContainer;
import com.github.auties00.smalt.metadata.XmpPacket;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.fuzz.FuzzSeeds;
import com.github.auties00.smalt.track.Codec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static com.github.auties00.smalt.testkit.fuzz.FuzzSeeds.copy;
import static com.github.auties00.smalt.testkit.fuzz.FuzzSeeds.ffmpeg;
import static com.github.auties00.smalt.testkit.fuzz.FuzzSeeds.vector;
import static com.github.auties00.smalt.testkit.fuzz.FuzzSeeds.video;
import static com.github.auties00.smalt.testkit.fuzz.FuzzSeeds.write;

/**
 * Writes the committed seeds of the image fuzz targets (tools/fuzz/README.md): small images made by ffmpeg, a
 * few of this module's committed test vectors, libjxl conformance files (BSD-3-Clause, listed in
 * {@code tools/testdata/vectors.tsv}), and metadata blocks built with Smalt. Runs only with
 * {@code -Dsmalt.fuzz.writeSeeds=true}.
 */
@EnabledIfSystemProperty(named = "smalt.fuzz.writeSeeds", matches = "true")
class FuzzSeedWriter {
    private static final Path VECTORS = Path.of("src", "test", "resources", "com", "github", "auties00", "smalt",
            "image");

    private static final String XMP = "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><rdf:RDF "
            + "xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\"><rdf:Description rdf:about=\"\" "
            + "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:exif=\"http://ns.adobe.com/exif/1.0/\" "
            + "exif:GPSLatitude=\"45,1.5N\"><dc:title><rdf:Alt><rdf:li xml:lang=\"x-default\">Seed</rdf:li>"
            + "</rdf:Alt></dc:title></rdf:Description></rdf:RDF></x:xmpmeta>";

    @Test
    @DisplayName("writes the seeds of the JPEG 2000, JPEG XL and WebP container targets from the decoder seeds")
    void entryPoints() {
        var seeds = FuzzSeeds.directory(ImageFuzzTest.class, "jp2").getParent();
        for (var seed : List.of("valid-codestream.j2k", "valid-jp2.jp2")) {
            copy(ImageFuzzTest.class, "jp2Channels", seed, seeds.resolve("jp2").resolve(seed));
        }
        for (var seed : List.of("valid-grayscale.jxl", "valid-alpha-triangles.jxl", "valid-jpeg-reconstruction.jxl")) {
            copy(ImageFuzzTest.class, "jxlFloat", seed, seeds.resolve("jxl").resolve(seed));
        }
        copy(ImageFuzzTest.class, "jxlJpeg", "valid-jpeg-reconstruction.jxl",
                seeds.resolve("jxl").resolve("valid-jpeg-reconstruction.jxl"));
        for (var seed : List.of("valid-lossy.webp", "valid-lossless-alpha.webp", "valid-animated.webp")) {
            copy(ImageMetadataFuzzTest.class, "webpContainer", seed, seeds.resolve("webp").resolve(seed));
        }
        var lossy = Fixtures.read(seeds.resolve("webp").resolve("valid-lossy.webp"));
        var tagged = WebpContainer.withXmp(WebpContainer.withIccProfile(WebpContainer.withExif(lossy,
                ExifData.EMPTY.withOrientation(Orientation.ROTATE_90).toBytes(ByteOrder.BIG_ENDIAN)),
                ColorProfile.displayP3().toByteArray()),
                XMP.getBytes(StandardCharsets.UTF_8));
        write(ImageMetadataFuzzTest.class, "webpContainer", "valid-metadata.webp", tagged);
    }

    @Test
    @DisplayName("writes the seeds of the image decoder targets")
    void images() {
        var still = video(16, 16, 1);
        copy(ImageFuzzTest.class, "jpeg", "valid-baseline-420.jpg", VECTORS.resolve("jpeg/cjpeg/33x65-yuv420-baseline.jpg"));
        copy(ImageFuzzTest.class, "jpeg", "valid-progressive-arithmetic-422.jpg",
                VECTORS.resolve("jpeg/cjpeg/33x65-yuv422-progressive-arithmetic.jpg"));
        copy(ImageFuzzTest.class, "jpeg", "valid-progressive-gray.jpg",
                VECTORS.resolve("jpeg/cjpeg/17x1-monochrome-progressive.jpg"));
        copy(ImageFuzzTest.class, "jpeg", "valid-12bit-444.jpg", VECTORS.resolve("jpeg/cjpeg12/17x1-yuv444-progressive.jpg"));
        copy(ImageFuzzTest.class, "jpeg", "valid-lossless-rgb.jpg", VECTORS.resolve("jpeg/lossless/61x43-rgb-p8-psv1.jpg"));
        copy(ImageFuzzTest.class, "jpeg", "valid-restart-arithmetic.jpg",
                VECTORS.resolve("jpeg/cjpeg/227x149-monochrome-arithmetic-rst3.jpg"));

        copy(ImageFuzzTest.class, "png", "valid-rgba.png", ffmpeg("png", still, "-frames:v", "1", "-update", "1",
                "-pix_fmt", "rgba"));
        copy(ImageFuzzTest.class, "png", "valid-palette.png", ffmpeg("png", still, "-frames:v", "1", "-update", "1",
                "-pix_fmt", "pal8"));
        copy(ImageFuzzTest.class, "png", "valid-gray16.png", ffmpeg("png", still, "-frames:v", "1", "-update", "1",
                "-pix_fmt", "gray16be"));
        copy(ImageFuzzTest.class, "png", "valid-animated.apng", ffmpeg("apng", video(16, 16, 3), "-plays", "0",
                "-pix_fmt", "rgb24"));

        copy(ImageFuzzTest.class, "gif", "valid-animated.gif", ffmpeg("gif", video(16, 16, 3)));
        copy(ImageFuzzTest.class, "gif", "valid-still.gif", ffmpeg("gif", still, "-frames:v", "1"));

        copy(ImageFuzzTest.class, "webp", "valid-lossy.webp", ffmpeg("webp", still, "-frames:v", "1", "-c:v",
                "libwebp", "-quality", "50"));
        copy(ImageFuzzTest.class, "webp", "valid-lossless-alpha.webp", ffmpeg("webp", still, "-frames:v", "1",
                "-c:v", "libwebp", "-lossless", "1", "-pix_fmt", "yuva420p"));
        copy(ImageFuzzTest.class, "webp", "valid-animated.webp", ffmpeg("webp", video(16, 16, 3), "-c:v",
                "libwebp_anim", "-quality", "30", "-loop", "0"));

        copy(ImageFuzzTest.class, "bmp", "valid-24bit.bmp", ffmpeg("bmp", still, "-frames:v", "1", "-update", "1"));
        copy(ImageFuzzTest.class, "bmp", "valid-palette.bmp", ffmpeg("bmp", still, "-frames:v", "1", "-update", "1",
                "-pix_fmt", "pal8"));
        copy(ImageFuzzTest.class, "bmp", "valid-bgra.bmp", ffmpeg("bmp", still, "-frames:v", "1", "-update", "1",
                "-pix_fmt", "bgra"));

        copy(ImageFuzzTest.class, "tiff", "valid-deflate.tif", ffmpeg("tif", still, "-frames:v", "1", "-update", "1",
                "-compression_algo", "deflate"));
        copy(ImageFuzzTest.class, "tiff", "valid-lzw-gray16.tif", ffmpeg("tif", still, "-frames:v", "1", "-update",
                "1", "-compression_algo", "lzw", "-pix_fmt", "gray16le"));
        copy(ImageFuzzTest.class, "tiff", "valid-packbits-yuv.tif", ffmpeg("tif", still, "-frames:v", "1", "-update",
                "1", "-compression_algo", "packbits", "-pix_fmt", "yuv420p"));
        copy(ImageFuzzTest.class, "tiff", "valid-jpeg12-tables.tif", VECTORS.resolve("tiff/jpeg12/226x149-gray-strips16-tables.tif"));
        copy(ImageFuzzTest.class, "tiff", "valid-webp-tiles.tif", VECTORS.resolve("tiff/codecs/webp-rgb8-tiles.tif"));

        copy(ImageFuzzTest.class, "ico", "valid-icon.ico", ffmpeg("ico", still, "-frames:v", "1", "-update", "1"));
        copy(ImageFuzzTest.class, "ico", "valid-bmp-icon.ico", ffmpeg("ico", still, "-frames:v", "1", "-update", "1",
                "-c:v", "bmp", "-pix_fmt", "bgra"));

        copy(ImageFuzzTest.class, "jp2", "valid-jp2.jp2", ffmpeg("jp2", video(32, 32, 1), "-frames:v", "1",
                "-update", "1", "-c:v", "libopenjpeg", "-numresolution", "3"));
        copy(ImageFuzzTest.class, "jp2", "valid-codestream.j2k", ffmpeg("j2k", still, "-frames:v", "1", "-update",
                "1", "-c:v", "jpeg2000", "-pred", "dwt53"));

        copy(ImageFuzzTest.class, "jxl", "valid-alpha-triangles.jxl", vector("jxl-conformance-alpha_triangles.jxl"));
        copy(ImageFuzzTest.class, "jxl", "valid-blendmodes.jxl", vector("jxl-conformance-blendmodes.jxl"));
        copy(ImageFuzzTest.class, "jxl", "valid-grayscale.jxl", vector("jxl-conformance-grayscale.jxl"));
        copy(ImageFuzzTest.class, "jxl", "valid-jpeg-reconstruction.jxl", vector("jxl-conformance-grayscale_jpeg.jxl"));
    }

    @Test
    @DisplayName("writes the seeds of the metadata and JPEG transform targets")
    void metadata() {
        var still = video(16, 16, 1);
        var jpeg = Fixtures.read(ffmpeg("jpg", video(32, 24, 1), "-frames:v", "1", "-update", "1", "-q:v", "20"));
        var exif = ExifData.EMPTY
                .with(ExifData.Directory.IFD0, 0x010F, ExifValue.ofAscii("Smalt"))
                .with(ExifData.Directory.IFD0, 0x0110, ExifValue.ofAscii("Seed"))
                .with(ExifData.Directory.EXIF, 0x9003, ExifValue.ofAscii("2026:10:18 00:00:00"))
                .with(ExifData.Directory.EXIF, 0x829A, ExifValue.ofRational(1, 125))
                .with(ExifData.Directory.GPS, 0x0001, ExifValue.ofAscii("N"))
                .with(ExifData.Directory.GPS, 0x0002, ExifValue.ofRational(45, 1))
                .withOrientation(Orientation.ROTATE_90)
                .withThumbnail(jpeg);
        write(ImageMetadataFuzzTest.class, "exif", "valid-big-endian.tiff", exif.toBytes(ByteOrder.BIG_ENDIAN));
        write(ImageMetadataFuzzTest.class, "exif", "valid-little-endian.tiff", exif.toBytes(ByteOrder.LITTLE_ENDIAN));
        var iptc = IptcData.of(List.of(
                new IptcData.DataSet(1, 90, new byte[]{0x1B, 0x25, 0x47}),
                new IptcData.DataSet(2, 5, "seed".getBytes(StandardCharsets.UTF_8)),
                new IptcData.DataSet(2, 25, "fuzz".getBytes(StandardCharsets.UTF_8)),
                new IptcData.DataSet(2, 90, "Milan".getBytes(StandardCharsets.UTF_8)),
                new IptcData.DataSet(2, 120, "caption".getBytes(StandardCharsets.UTF_8))));
        write(ImageMetadataFuzzTest.class, "iptc", "valid-records.iim", iptc.toBytes());

        var full = ImageMetadataEdit.builder()
                .exif(exif)
                .xmp(XmpPacket.parse(XMP))
                .iptc(iptc)
                .comments(List.of("seed"))
                .build();
        var tagged = ImageMetadataEditor.apply(jpeg, full);
        write(ImageMetadataFuzzTest.class, "metadata", "valid-jpeg-all.jpg", tagged);
        write(ImageMetadataFuzzTest.class, "metadata", "valid-png-text.png", ImageMetadataEditor.apply(
                Fixtures.read(ffmpeg("png", still, "-frames:v", "1", "-update", "1")), ImageMetadataEdit.builder()
                        .exif(exif.withoutThumbnail())
                        .xmp(XmpPacket.parse(XMP))
                        .text(Map.of("Comment", "seed", "Author", "Smalt"))
                        .build()));
        write(ImageMetadataFuzzTest.class, "metadata", "valid-webp-exif-xmp.webp", ImageMetadataEditor.apply(
                Fixtures.read(ffmpeg("webp", still, "-frames:v", "1", "-c:v", "libwebp")), ImageMetadataEdit.builder()
                        .exif(exif.withoutThumbnail())
                        .xmp(XmpPacket.parse(XMP))
                        .build()));
        write(ImageMetadataFuzzTest.class, "metadata", "valid-tiff-exif.tif", ImageMetadataEditor.apply(
                Fixtures.read(ffmpeg("tif", still, "-frames:v", "1", "-update", "1")), ImageMetadataEdit.builder()
                        .exif(exif.withoutThumbnail())
                        .xmp(XmpPacket.parse(XMP))
                        .build()));
        write(ImageMetadataFuzzTest.class, "metadata", "valid-gif-comment.gif", ImageMetadataEditor.apply(
                Fixtures.read(ffmpeg("gif", still, "-frames:v", "1")), ImageMetadataEdit.builder()
                        .comments(List.of("seed"))
                        .xmp(XmpPacket.parse(XMP))
                        .build()));

        write(ImageMetadataFuzzTest.class, "jpegTransform", "valid-exif-thumbnail.jpg", tagged);
        copy(ImageMetadataFuzzTest.class, "jpegTransform", "valid-progressive-arithmetic-422.jpg",
                VECTORS.resolve("jpeg/cjpeg/33x65-yuv422-progressive-arithmetic.jpg"));
        copy(ImageMetadataFuzzTest.class, "jpegTransform", "valid-baseline-411-restart.jpg",
                VECTORS.resolve("jpeg/cjpeg/227x149-yuv411-baseline-rst3.jpg"));
    }

    @Test
    @DisplayName("writes the seeds of the video decoder targets")
    void videos() {
        var mjpeg = ffmpeg("avi", video(32, 32, 3), "-c:v", "mjpeg", "-q:v", "20");
        write(ImageVideoFuzzTest.class, "mjpeg", "valid-avi.bin", FuzzSeeds.packets(mjpeg, Codec.MJPEG, 0, 3));
        write(ImageVideoFuzzTest.class, "mjpeg", "valid-lossless.bin", FuzzSeeds.packets(
                VECTORS.resolve("jpeg/mjpeg/ljpeg-yuvj420p-pred1.avi"), Codec.MJPEG, 0, 2));
        var vp8 = ffmpeg("ivf", video(32, 32, 4), "-c:v", "libvpx", "-deadline", "realtime", "-b:v", "50k");
        write(ImageVideoFuzzTest.class, "vp8", "valid-ivf.bin", FuzzSeeds.packets(vp8, Codec.VP8, 0, 4));
        var vp8Alpha = ffmpeg("webm", video(32, 32, 3), "-c:v", "libvpx", "-deadline", "realtime", "-b:v", "50k",
                "-auto-alt-ref", "1", "-lag-in-frames", "3", "-error-resilient", "1");
        write(ImageVideoFuzzTest.class, "vp8", "valid-altref.bin", FuzzSeeds.packets(vp8Alpha, Codec.VP8, 0, 3));
    }
}
