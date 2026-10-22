package com.github.auties00.smalt.image.fuzz;

import com.github.auties00.smalt.image.Orientation;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.bmp.BmpEncoder;
import com.github.auties00.smalt.image.bmp.BmpEncoderOptions;
import com.github.auties00.smalt.image.color.ColorProfile;
import com.github.auties00.smalt.image.exif.ExifData;
import com.github.auties00.smalt.image.exif.ExifValue;
import com.github.auties00.smalt.image.ico.AniEncoder;
import com.github.auties00.smalt.image.ico.AniEncoderOptions;
import com.github.auties00.smalt.image.ico.AniStep;
import com.github.auties00.smalt.image.ico.IcoEncoderOptions;
import com.github.auties00.smalt.image.internal.CompressionSamples;
import com.github.auties00.smalt.image.internal.zstd.ZstdEncoder;
import com.github.auties00.smalt.image.jp2.Jp2CodeBlockStyle;
import com.github.auties00.smalt.image.jp2.Jp2Encoder;
import com.github.auties00.smalt.image.jp2.Jp2EncoderOptions;
import com.github.auties00.smalt.image.jp2.Jp2Format;
import com.github.auties00.smalt.image.metadata.ImageMetadataEdit;
import com.github.auties00.smalt.image.metadata.ImageMetadataEditor;
import com.github.auties00.smalt.image.metadata.IptcData;
import com.github.auties00.smalt.image.metadata.MetadataSeeds;
import com.github.auties00.smalt.image.tiff.TiffVariantSamples;
import com.github.auties00.smalt.image.webp.WebpContainer;
import com.github.auties00.smalt.internal.io.ByteArrays;
import com.github.auties00.smalt.metadata.XmpPacket;
import com.github.auties00.smalt.testkit.Conformance;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.fuzz.FuzzSeeds;
import com.github.auties00.smalt.track.Codec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.IOException;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.zip.ZipInputStream;

import static com.github.auties00.smalt.testkit.fuzz.FuzzSeeds.copy;
import static com.github.auties00.smalt.testkit.fuzz.FuzzSeeds.ffmpeg;
import static com.github.auties00.smalt.testkit.fuzz.FuzzSeeds.vector;
import static com.github.auties00.smalt.testkit.fuzz.FuzzSeeds.video;
import static com.github.auties00.smalt.testkit.fuzz.FuzzSeeds.write;

/**
 * Writes the committed seeds of the image fuzz targets (tools/fuzz/README.md): small images made by ffmpeg, a
 * few of this module's committed test vectors, libjxl conformance files (BSD-3-Clause, listed in
 * {@code tools/testdata/vectors.tsv}), small public-domain BMP Suite images, and metadata blocks and BMP files
 * built with Smalt. Runs only with
 * {@code -Dsmalt.fuzz.writeSeeds=true}.
 */
@EnabledIfSystemProperty(named = "smalt.fuzz.writeSeeds", matches = "true",
        disabledReason = "[opt-in:fuzz-seeds] runs only with -Dsmalt.fuzz.writeSeeds=true")
class FuzzSeedWriter {
    private static final Path VECTORS = Path.of("src", "test", "resources", "com", "github", "auties00", "smalt",
            "image");

    private static final Path SAMPLES = Path.of("src", "test", "resources", "samples");

    /** Returns a square picture with transparent and opaque pixels, varied by a seed. */
    private static RgbaPicture seedPicture(int size, int seed) {
        var picture = RgbaPicture.allocate(size, size, true);
        var pixels = picture.pixels();
        for (var i = 0; i < size * size; i++) {
            pixels[i * 4] = (byte) (i * 7 + seed * 40);
            pixels[i * 4 + 1] = (byte) (i / size * 9);
            pixels[i * 4 + 2] = (byte) (seed * 90);
            pixels[i * 4 + 3] = (byte) (i % 5 == 0 ? 0 : 255);
        }
        return picture;
    }

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
        // the JPEG XL feature vectors of tools/testdata/jxl-features: a preview, a progressive file, a gain map in a
        // brob box and an animation with a frame index
        for (var vector : List.of("preview-vardct", "progressive", "gainmap-brob", "indexed")) {
            copy(ImageFuzzTest.class, "jxlFeatures", "valid-" + vector + ".jxl",
                    VECTORS.resolve("jxl/features/" + vector + ".jxl"));
        }
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
    @DisplayName("writes the HTJ2K seeds of the JPEG 2000 targets, coded with Jp2Encoder")
    void highThroughput() {
        var picture = seedPicture(40, 3);
        var lossless = Jp2EncoderOptions.lossless().toBuilder().highThroughput(true).decompositionLevels(2)
                .codeBlockSize(16, 16).build();
        var codestream = Jp2Encoder.encode(picture, lossless.toBuilder().format(Jp2Format.CODESTREAM).build());
        var jph = Jp2Encoder.encode(picture, lossless);
        var layered = Jp2Encoder.encode(picture, Jp2EncoderOptions.lossy(6).toBuilder().highThroughput(true)
                .compressionRatios(24, 6).decompositionLevels(2).codeBlockSize(16, 16)
                .codeBlockStyles(Set.of(Jp2CodeBlockStyle.VERTICALLY_CAUSAL)).format(Jp2Format.CODESTREAM).build());
        for (var target : List.of("jp2", "jp2Channels")) {
            write(ImageFuzzTest.class, target, "valid-ht-codestream.j2k", codestream);
            write(ImageFuzzTest.class, target, "valid-ht.jph", jph);
            write(ImageFuzzTest.class, target, "valid-ht-layered.j2k", layered);
        }
    }

    @Test
    @DisplayName("writes the seeds of the BMP variants: small BMP Suite files and files built with BmpEncoder")
    void bmpVariants() throws IOException {
        // the public-domain BMP Suite images of a few kilobytes, none of which embeds an ICC profile
        var suite = new LinkedHashMap<String, byte[]>();
        try (var input = new ZipInputStream(Files.newInputStream(Conformance.fetchListed("bmpsuite-2.8.zip")))) {
            for (var entry = input.getNextEntry(); entry != null; entry = input.getNextEntry()) {
                suite.put(entry.getName().substring(entry.getName().indexOf('/') + 1), input.readAllBytes());
            }
        }
        write(ImageFuzzTest.class, "bmp", "valid-huffman.bmp", suite.get("q/pal1huffmsb.bmp"));
        write(ImageFuzzTest.class, "bmp", "valid-png-payload.bmp", suite.get("q/rgb24png.bmp"));
        write(ImageFuzzTest.class, "bmp", "valid-rle4.bmp", suite.get("g/pal4rle.bmp"));
        var random = new Random(7);
        var pixels = new byte[8 * 4 * 4];
        random.nextBytes(pixels);
        var picture = RgbaPicture.of(8, 4, 32, pixels, true);
        var profiled = BmpEncoder.encode(picture, BmpEncoderOptions.builder().colorProfile(ColorProfile.displayP3()).build());
        write(ImageFuzzTest.class, "bmp", "valid-v5-profile.bmp", profiled);
        var argb = BmpEncoder.encode(picture);
        var wide = argb.clone();
        ByteArrays.put32le(wide, 54, 0x3FF0_0000);
        ByteArrays.put32le(wide, 58, 0x000F_FC00);
        ByteArrays.put32le(wide, 62, 0x0000_03FF);
        ByteArrays.put32le(wide, 66, 0xC000_0000);
        write(ImageFuzzTest.class, "bmp", "valid-bitfields-1010102.bmp", wide);
        var cmyk = BmpEncoder.encode(picture, BmpEncoderOptions.builder().pixelFormat(BmpEncoderOptions.PixelFormat.XRGB_8888)
                .build());
        cmyk[30] = 11;
        write(ImageFuzzTest.class, "bmp", "valid-cmyk.bmp", cmyk);
        write(ImageFuzzTest.class, "bmp", "valid-rle8-encoder.bmp", BmpEncoder.encode(picture, BmpEncoderOptions.builder()
                .runLength(true).build()));
        var calibrated = BmpEncoder.encode(picture, BmpEncoderOptions.builder().pixelFormat(BmpEncoderOptions.PixelFormat.PALETTE_8)
                .headerVersion(BmpEncoderOptions.HeaderVersion.V4).build());
        var endpoints = new double[]{0.64, 0.33, 0.03, 0.30, 0.60, 0.10, 0.15, 0.06, 0.79};
        ByteArrays.put32le(calibrated, 14 + 56, 0);
        for (var i = 0; i < 9; i++) {
            ByteArrays.put32le(calibrated, 14 + 60 + i * 4, Math.round(endpoints[i] * (1 << 30)));
        }
        for (var i = 0; i < 3; i++) {
            ByteArrays.put32le(calibrated, 14 + 96 + i * 4, Math.round(2.2 * 65536));
        }
        write(ImageFuzzTest.class, "bmp", "valid-calibrated.bmp", calibrated);
        write(ImageFuzzTest.class, "bmp", "valid-rgba64.bmp", bmp(64, 4, 2, 0, 64, bytes(random, 4 * 2 * 8)));
        // OS/2 2.x RLE24: a run, a padded absolute run, an end of line, a delta, a clipped run and an end of bitmap
        write(ImageFuzzTest.class, "bmp", "valid-rle24.bmp", bmp(64, 6, 2, 4, 24, new byte[]{3, 0x30, 0x20, 0x10, 0, 3,
                1, 2, 3, 4, 5, 6, 7, 8, 9, 0, 0, 0, 0, 2, 1, 0, 9, (byte) 0xAA, (byte) 0xBB, (byte) 0xCC, 0, 1}));
        var array = new byte[2 * 14 + argb.length + profiled.length];
        var at = 0;
        for (var entry : new byte[][]{argb, profiled}) {
            array[at] = 'B';
            array[at + 1] = 'A';
            ByteArrays.put32le(array, at + 2, 14 + 14 + ByteArrays.i32le(entry, 14));
            ByteArrays.put32le(array, at + 6, entry == argb ? 14 + argb.length : 0);
            System.arraycopy(entry, 0, array, at + 14, entry.length);
            ByteArrays.put32le(array, at + 14 + 10, ByteArrays.i32le(entry, 10) + at + 14);
            at += 14 + entry.length;
        }
        write(ImageFuzzTest.class, "bmp", "valid-array.bmp", array);
        write(ImageMetadataFuzzTest.class, "metadata", "valid-bmp-profile.bmp", profiled);
        var core = new byte[14 + 12 + 4 * 3 + 4 * 4];
        core[0] = 'B';
        core[1] = 'M';
        ByteArrays.put32le(core, 2, core.length);
        ByteArrays.put32le(core, 10, 14 + 12 + 4 * 3);
        ByteArrays.put32le(core, 14, 12);
        ByteArrays.put16le(core, 18, 4);
        ByteArrays.put16le(core, 20, 4);
        ByteArrays.put16le(core, 22, 1);
        ByteArrays.put16le(core, 24, 8);
        System.arraycopy(bytes(random, 12), 0, core, 26, 12);
        for (var i = 0; i < 16; i++) {
            core[38 + i] = (byte) (i % 4);
        }
        write(ImageMetadataFuzzTest.class, "metadata", "valid-bmp-core.bmp", core);
    }

    // a bottom-up bitmap with an OS/2 2.x or Windows header of the given size, compression and depth
    private static byte[] bmp(int headerSize, int width, int height, int compression, int bitCount, byte[] data) {
        var file = new byte[14 + headerSize + data.length];
        file[0] = 'B';
        file[1] = 'M';
        ByteArrays.put32le(file, 2, file.length);
        ByteArrays.put32le(file, 10, 14 + headerSize);
        ByteArrays.put32le(file, 14, headerSize);
        ByteArrays.put32le(file, 18, width);
        ByteArrays.put32le(file, 22, height);
        ByteArrays.put16le(file, 26, 1);
        ByteArrays.put16le(file, 28, bitCount);
        ByteArrays.put32le(file, 30, compression);
        ByteArrays.put32le(file, 34, data.length);
        System.arraycopy(data, 0, file, 14 + headerSize, data.length);
        return file;
    }

    private static byte[] bytes(Random random, int length) {
        var data = new byte[length];
        random.nextBytes(data);
        return data;
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
        copy(ImageFuzzTest.class, "jpeg", "valid-lossless-arithmetic-420.jpg",
                VECTORS.resolve("jpeg/lossless/variants/37x29-ycbcr420-p8-sof11-psv4-r19.jpg"));
        copy(ImageFuzzTest.class, "jpeg", "valid-lossless-multiscan-422.jpg",
                VECTORS.resolve("jpeg/lossless/variants/37x29-ycbcr422-p12-3scans.jpg"));
        copy(ImageFuzzTest.class, "jpeg", "valid-lossless-five-components.jpg",
                VECTORS.resolve("jpeg/lossless/variants/23x17-5ch-p8-sof11-2scans.jpg"));
        copy(ImageFuzzTest.class, "jpeg", "valid-five-components.jpg",
                VECTORS.resolve("jpeg/lossless/variants/23x17-5ch-baseline.jpg"));

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

        copy(ImageFuzzTest.class, "ani", "valid-animated.ani", SAMPLES.resolve("ico/animated.ani"));
        write(ImageFuzzTest.class, "ani", "valid-cursor.ani", AniEncoder.encode(List.of(seedPicture(16, 0),
                seedPicture(16, 1)), List.of(new AniStep(1, 2), new AniStep(0, 5), new AniStep(1, 2)),
                AniEncoderOptions.builder().frameOptions(IcoEncoderOptions.builder().sizes(8, 16).hotspot(2, 3).build())
                        .title("seed").build()));

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
    @DisplayName("writes the seeds of the TIFF target for YCbCr data units, ink sets and the rarer sample layouts")
    void tiffVariants() {
        copy(ImageFuzzTest.class, "tiff", "valid-ycbcr22-lzw-predictor.tif", VECTORS.resolve("tiff/ycbcr/ycbcr22-8-lzw.tif"));
        copy(ImageFuzzTest.class, "tiff", "valid-inks5-icc.tif", VECTORS.resolve("tiff/inks/inks5-8.tif"));
        for (var file : TiffVariantSamples.files().entrySet()) {
            write(ImageFuzzTest.class, "tiff", "valid-" + file.getKey(), file.getValue());
        }
    }

    @Test
    @DisplayName("writes the seeds of the TIFF compressions and of the general-purpose decoder targets")
    void compressions() {
        for (var name : List.of("jbig-bilevel", "next-gray2", "lerc-int8", "lerc-rgba8-mask", "lerc-float32-lossy-zstd",
                "lzma-gray16-predictor-be", "zstd-gray16-predictor-be", "pixarlog-gray8", "pixarlog-imagecodecs-rgba8",
                "lerc-lerc1-float32-mask")) {
            copy(ImageFuzzTest.class, "tiff", "valid-" + name + ".tif", VECTORS.resolve("tiff/compressions/" + name + ".tif"));
        }
        var internal = VECTORS.resolve("internal");
        var text = CompressionSamples.sample(new Random(1), 3000);
        write(ImageFuzzTest.class, "zstd", "valid-huffman-fse.zst", ZstdEncoder.compress(text, 0, text.length, 7));
        for (var name : List.of("short-l3.zst", "zeros-l3.zst")) {
            copy(ImageFuzzTest.class, "zstd", "valid-" + name, internal.resolve("zstd/frames/" + name));
        }
        copy(ImageFuzzTest.class, "zstd", "valid-rle-first-block.zst", internal.resolve("zstd/golden/rle-first-block.zst"));
        copy(ImageFuzzTest.class, "xz", "valid-one.xz", internal.resolve("xz/files/one.xz"));
        write(ImageFuzzTest.class, "xz", "valid-delta-x86.xz", Oracle.run("xz", Duration.ofMinutes(1), text,
                List.of("-q", "-c", "--delta=dist=2", "--x86", "--lzma2=preset=6")));
        write(ImageFuzzTest.class, "xz", "valid-lzma-alone.lzma", Oracle.run("xz", Duration.ofMinutes(1), text,
                List.of("-q", "-c", "--format=lzma", "-3")));
        for (var name : List.of("int8-lossless", "uint8-v2", "uint8-depth4-v4", "float32-lossy", "uint16-v3",
                "float32-constant-depth3", "lerc1-count-tiles", "lerc1-bands3")) {
            copy(ImageFuzzTest.class, "lerc", "valid-" + name + ".lerc", internal.resolve("lerc/blobs/" + name + ".lerc"));
        }
        for (var name : List.of("tiny-deep", "seq-at", "prog-at", "seq-newlen", "seq-tpbon-stripes")) {
            copy(ImageFuzzTest.class, "jbig", "valid-" + name + ".jbg", internal.resolve("jbig/entities/" + name + ".jbg"));
        }
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

        copy(ImageMetadataFuzzTest.class, "metadata", "valid-gif-icc.gif", SAMPLES.resolve("gif/icc.gif"));

        write(ImageMetadataFuzzTest.class, "jpegTransform", "valid-exif-thumbnail.jpg", tagged);
        copy(ImageMetadataFuzzTest.class, "jpegTransform", "valid-progressive-arithmetic-422.jpg",
                VECTORS.resolve("jpeg/cjpeg/33x65-yuv422-progressive-arithmetic.jpg"));
        copy(ImageMetadataFuzzTest.class, "jpegTransform", "valid-baseline-411-restart.jpg",
                VECTORS.resolve("jpeg/cjpeg/227x149-yuv411-baseline-rst3.jpg"));
    }

    @Test
    @DisplayName("writes the seeds of the raw, JPEG XL, HEIF sequence and extended EXIF metadata layouts")
    void extendedMetadata() {
        write(ImageMetadataFuzzTest.class, "exif", "valid-extended-big-endian.tiff",
                MetadataSeeds.extendedExif(ByteOrder.BIG_ENDIAN));
        write(ImageMetadataFuzzTest.class, "exif", "valid-extended-little-endian.tiff",
                MetadataSeeds.extendedExif(ByteOrder.LITTLE_ENDIAN));
        write(ImageMetadataFuzzTest.class, "metadata", "valid-raw-orf.orf", MetadataSeeds.orf());
        write(ImageMetadataFuzzTest.class, "metadata", "valid-raw-rw2.rw2", MetadataSeeds.rw2());
        write(ImageMetadataFuzzTest.class, "metadata", "valid-raw-raf.raf", MetadataSeeds.raf());
        write(ImageMetadataFuzzTest.class, "metadata", "valid-raw-cr3.cr3", MetadataSeeds.cr3());
        write(ImageMetadataFuzzTest.class, "metadata", "valid-raw-mrw.mrw", MetadataSeeds.mrw());
        write(ImageMetadataFuzzTest.class, "metadata", "valid-raw-tiff.nef", MetadataSeeds.tiffRaw());
        write(ImageMetadataFuzzTest.class, "metadata", "valid-dng.dng", MetadataSeeds.dng());
        write(ImageMetadataFuzzTest.class, "metadata", "valid-heif-sequence.avif", MetadataSeeds.heifSequence());
        write(ImageMetadataFuzzTest.class, "jpegTransform", "valid-uncompressed-thumbnail.jpg",
                MetadataSeeds.uncompressedThumbnailJpeg());
        copy(ImageMetadataFuzzTest.class, "metadata", "valid-jxl-reconstruction.jxl",
                VECTORS.resolve("jxl/reconstruction/tagged.jxl"));
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
