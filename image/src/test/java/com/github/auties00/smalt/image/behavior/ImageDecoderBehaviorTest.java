package com.github.auties00.smalt.image.behavior;

import com.github.auties00.smalt.image.gainmap.GainMap;
import com.github.auties00.smalt.image.jpeg.JpegDecoder;
import com.github.auties00.smalt.image.jxl.JxlDecoder;
import com.github.auties00.smalt.image.jxl.JxlGainMap;
import com.github.auties00.smalt.testkit.Conformance;
import com.github.auties00.smalt.testkit.image.ImageDecoderAsserts;
import com.github.auties00.smalt.testkit.image.RasterTesting;
import com.github.auties00.smalt.testkit.image.RegionAsserts;
import com.github.auties00.smalt.testkit.io.BehaviorSamples;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

/**
 * Runs every image decoder of this artifact through the I/O behavior harness ({@link ImageDecoderAsserts}):
 * the samples of {@code src/test/resources/samples} (made by {@code tools/testdata/behavior/make-image-samples.sh}
 * and pinned in {@code samples.tsv} there), a selection of the committed codec vectors, PngSuite and BMP Suite
 * images, the lossless streams of the ITU-T T.83 compliance data, JPEG-LS streams of the ITU-T T.87 data set and
 * smoke-tier vectors, each compared with its golden dump under {@code src/test/resources/dumps}.
 */
@DisplayName("image decoder I/O behaviors")
class ImageDecoderBehaviorTest {
    private static final Path SAMPLES = Path.of("src", "test", "resources", "samples");

    private static final Path RESOURCES = Path.of("src", "test", "resources", "com", "github", "auties00", "smalt",
            "image");

    /** Committed codec vectors, relative to the image resource directory. */
    private static final List<String> RESOURCE_FILES = List.of(
            "jpeg/lossless/61x43-gray-p12-psv6.jpg",
            "jpeg/lossless/variants/37x29-ycbcr420-p8-sof11-psv4-r19.jpg",
            "jpeg/lossless/variants/37x29-ycbcr422-p12-3scans.jpg",
            "jpeg/lossless/variants/23x17-5ch-p8-sof11-2scans.jpg",
            "jpeg/lossless/variants/23x17-5ch-baseline.jpg",
            "jpeg/lossless/variants/45x31-rgb-p12-psv5-cjpeg-scans.jpg",
            "jpeg/cjpeg12/17x1-yuv411-progressive-arithmetic.jpg",
            "jpeg/jpegls/charls/rgb8-ilv0.jls",
            "jpeg/jpegls/charls/rgb8-ilv2-near5.jls",
            "jpeg/jpegls/charls/gray12-near2.jls",
            "jpeg/jpegls/charls/palette8.jls",
            "jpeg/jpegls/charls/mapped8to16.jls",
            "jpeg/jpegls/charls/rgb16-hp3-ilv2.jls",
            "jpeg/jpegls/charls/ycbcr8-spiff.jls",
            "jpeg/jpegls/charls/five8-ilv0.jls",
            "jpeg/jpegls/referenceA/ilv1-sub420-near1-ri2.jls",
            "jpeg/jpegls/referenceA/ilv0-sub.jls",
            "jpeg/jpegls/referenceA/ilv1-dnl.jls",
            "jpeg/hierarchical/dct8-ycc420-2.jpg",
            "jpeg/hierarchical/dct8-gray-3-arith.jpg",
            "jpeg/hierarchical/dct8-gray-2-prog.jpg",
            "jpeg/hierarchical/dct8-gray-2-dnl.jpg",
            "jpeg/hierarchical/dct12-gray-final-lossless.jpg",
            "jpeg/hierarchical/dct8-rgb-same-size-lossless-arith.jpg",
            "jpeg/hierarchical/lossless8-rgb-2.jpg",
            "jpeg/hierarchical/lossless16-gray-2-arith.jpg",
            "tiff/jpeg12/226x149-ycbcr422-tiles64-tables.tif",
            "tiff/codecs/webp-rgba8-strips.tif",
            "tiff/codecs/j2k-gray12-tiles.tif",
            "tiff/codecs/jxl-rgb8-tiles.tif",
            "tiff/ycbcr/ycbcr42-8-zip.tif",
            "tiff/ycbcr/ycbcr44-16-lzw.tif",
            "tiff/inks/inks5-8.tif",
            "tiff/inks/inks6-16a.tif",
            "tiff/compressions/zstd-rgb8-tiles.tif",
            "tiff/compressions/lzma-gray16-predictor-be.tif",
            "tiff/compressions/lerc-rgba8-mask.tif",
            "tiff/compressions/lerc-float32-lossy-zstd.tif",
            "tiff/compressions/jbig-bilevel.tif",
            "tiff/compressions/pixarlog-rgb8.tif",
            "tiff/compressions/next-gray2.tif");

    /**
     * The JPEG XL feature vectors of {@code tools/testdata/jxl-features}, relative to the image resource directory,
     * also run through the preview, gain map and box decodes.
     */
    private static final List<String> JXL_FEATURES = List.of(
            "jxl/features/preview-vardct.jxl",
            "jxl/features/preview-modular.jxl",
            "jxl/features/progressive.jxl",
            "jxl/features/progressive-lossless.jxl",
            "jxl/features/spot-xyb.jxl",
            "jxl/features/gray-icc.jxl",
            "jxl/features/gainmap-brob.jxl",
            "jxl/features/indexed.jxl");

    /** The PngSuite images, covering every color type, depth, interlacing and ancillary chunk family. */
    private static final List<String> PNGSUITE = List.of(
            "basn0g01.png", "basn0g16.png", "basn2c16.png", "basn3p02.png", "basn4a08.png", "basn6a16.png",
            "basi0g04.png", "basi3p08.png", "basi6a08.png", "tbbn3p08.png", "s09i3p02.png", "g04n2c08.png",
            "ccwn2c08.png", "cdun2c08.png", "exif2c08.png");

    /** The BMP Suite images, covering every compression, header version, bit-field layout and the bitmap array. */
    private static final List<String> BMPSUITE = List.of(
            "g/pal1.bmp", "g/pal4rle.bmp", "g/pal8os2.bmp", "g/pal8topdown.bmp", "g/pal8v5.bmp", "g/rgb16-565.bmp",
            "g/rgb32bf.bmp", "q/pal1huffmsb.bmp", "q/pal4rlecut.bmp", "q/pal8rletrns.bmp", "q/pal8os2v2-16.bmp",
            "q/rgb24rle24.bmp", "q/rgb24jpeg.bmp", "q/rgb24png.bmp", "q/rgba32-1010102.bmp", "q/rgba32abf.bmp",
            "q/rgba64.bmp", "x/ba-bm.bmp");

    /** The smoke-tier vectors, by manifest name. */
    private static final List<String> VECTORS = List.of(
            "ijg6b-testimg.jpg",
            "ijg6b-testprog.jpg",
            "ljt-testimgari.jpg",
            "ljt-testorig12.jpg",
            "skia-image-gradient_displayp3.jpeg",
            "opj-p0_01.j2k",
            "opj-p0_03.j2k",
            "jxl-conformance-grayscale.jxl",
            "jxl-conformance-alpha_triangles.jxl",
            "jxl-conformance-animation_spline.jxl",
            "jxl-conformance-upsampling.jxl",
            "jxl-conformance-grayscale_jpeg.jxl",
            "jxl-conformance-blendmodes.jxl",
            "libwebp-test-data-vp80-00-comprehensive-001.webp",
            "libwebp-test-data-lossless_vec_1_0.webp",
            "libwebp-test-data-alpha_filter_1.webp",
            "libwebp-test-data-alpha_no_compression.webp",
            "libwebp-test-data-small_13x1.webp",
            "apng-philip-000.png",
            "apng-philip-036.png");

    static Stream<String> samples() {
        return BehaviorSamples.list(SAMPLES).stream();
    }

    static Stream<String> resources() {
        return RESOURCE_FILES.stream();
    }

    static Stream<String> jxlFeatures() {
        return JXL_FEATURES.stream();
    }

    static Stream<String> pngSuite() {
        return PNGSUITE.stream();
    }

    static Stream<String> bmpSuite() {
        return BMPSUITE.stream();
    }

    static Stream<String> vectors() {
        return VECTORS.stream();
    }

    @Test
    @DisplayName("the samples are the files pinned in tools/testdata/behavior/samples.tsv")
    void pinned() {
        BehaviorSamples.assertPinned(SAMPLES);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("samples")
    @DisplayName("samples: identical pictures under every source behavior and clean faults")
    void sample(String sample) throws IOException {
        ImageDecoderAsserts.of(sample, Files.readAllBytes(SAMPLES.resolve(sample))).run();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("samples")
    @DisplayName("samples decoded by region: identical pictures under every source behavior and clean faults")
    void sampleRegion(String sample) throws IOException {
        RegionAsserts.behaviors("region/" + sample, Files.readAllBytes(SAMPLES.resolve(sample)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("resources")
    @DisplayName("codec vectors decoded by region: identical pictures under every source behavior")
    void resourceRegion(String file) throws IOException {
        RegionAsserts.behaviors("region/resources/" + file, Files.readAllBytes(RESOURCES.resolve(file)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("resources")
    @DisplayName("codec vectors: identical pictures under every source behavior and clean faults")
    void resource(String file) throws IOException {
        ImageDecoderAsserts.of("resources/" + file, Files.readAllBytes(RESOURCES.resolve(file))).run();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("jxlFeatures")
    @DisplayName("JPEG XL features: identical pictures, previews, gain maps and boxes under every source behavior")
    void jxlFeature(String file) throws IOException {
        ImageDecoderAsserts.of("resources/" + file, Files.readAllBytes(RESOURCES.resolve(file)))
                .decoder("preview", source -> JxlDecoder.decodePreview(source).orElse(null))
                .decoder("gain map", source -> JxlDecoder.decodeGainMap(source).map(JxlGainMap::image).orElse(null))
                .decoder("boxes", source -> JxlDecoder.readBoxes(source).stream()
                        .map(box -> box.type() + (box.compressed() ? " brob " : " ") + box.content().length).toList())
                .run();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("pngSuite")
    @DisplayName("PngSuite: identical pictures under every source behavior and clean faults")
    void pngSuite(String name) {
        var files = RasterTesting.untarGz(Conformance.fetchListed("PngSuite-2017jul19.tgz"));
        ImageDecoderAsserts.of("pngsuite/" + name, files.get(name)).run();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("bmpSuite")
    @DisplayName("BMP Suite: identical pictures under every source behavior and clean faults")
    void bmpSuite(String name) throws IOException {
        var files = new HashMap<String, byte[]>();
        try (var input = new ZipInputStream(Files.newInputStream(Conformance.fetchListed("bmpsuite-2.8.zip")))) {
            for (var entry = input.getNextEntry(); entry != null; entry = input.getNextEntry()) {
                files.put(entry.getName().substring(entry.getName().indexOf('/') + 1), input.readAllBytes());
            }
        }
        ImageDecoderAsserts.of("bmpsuite/" + name.replace('/', '-'), files.get(name)).run();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"T83_process14/O1.JPG", "T83_process14/O2.JPG", "T83_process15/P1.JPG",
            "T83_process15/P2.JPG"})
    @DisplayName("ITU-T T.83 lossless streams: identical pictures under every source behavior and clean faults")
    void t83(String entry) throws IOException {
        try (var zip = new ZipFile(Conformance.fetchListed("itu-t83-data-files.zip").toFile());
             var input = zip.getInputStream(zip.getEntry(entry))) {
            ImageDecoderAsserts.of("t83/" + entry.substring(entry.indexOf('/') + 1), input.readAllBytes()).run();
        }
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"T87_test-1-2-3-4-5-6/T8C0E0.JLS", "T87_test-1-2-3-4-5-6/T8C1E3.JLS",
            "T87_test-1-2-3-4-5-6/T8C2E0.JLS", "T87_test-7-8/T8SSE3.JLS", "T87_test-9-10/T8NDE0.JLS",
            "T87_test-11-12/T16E3.JLS"})
    @DisplayName("ITU-T T.87 JPEG-LS streams: identical pictures under every source behavior and clean faults")
    void t87(String entry) throws IOException {
        try (var zip = new ZipFile(Conformance.fetchListed("itu-t87-data-set.zip").toFile());
             var input = zip.getInputStream(zip.getEntry(entry))) {
            ImageDecoderAsserts.of("t87/" + entry.substring(entry.indexOf('/') + 1), input.readAllBytes()).run();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("vectors")
    @DisplayName("smoke vectors: identical pictures under every source behavior and clean faults")
    void vector(String name) throws IOException {
        ImageDecoderAsserts.of("vectors/" + name, Files.readAllBytes(Conformance.fetchListed(name))).run();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"libavif-paris_exif_xmp_gainmap_littleendian.jpg", "libavif-seine_sdr_gainmap_srgb.jpg",
            "ImageFuzzTestInputs/jpegGainMap/valid-ultrahdr-gray.jpg",
            "ImageFuzzTestInputs/jpegGainMap/valid-ultrahdr-multichannel.jpg"})
    @DisplayName("JPEG gain maps: identical pictures, gain maps and auxiliary images under every source behavior")
    void jpegGainMap(String name) throws IOException {
        var data = name.startsWith("libavif-") ? Files.readAllBytes(Conformance.fetchListed(name))
                : Files.readAllBytes(Path.of("src", "test", "resources", "com", "github", "auties00", "smalt", "image",
                "fuzz").resolve(name));
        ImageDecoderAsserts.of("gainmap/" + name.substring(name.lastIndexOf('/') + 1), data)
                .decoder("gain map", source -> JpegDecoder.decodeGainMap(source).map(GainMap::image).orElse(null))
                .decoder("auxiliary images", source -> JpegDecoder.auxiliaryImages(source).toString())
                .run();
    }
}
