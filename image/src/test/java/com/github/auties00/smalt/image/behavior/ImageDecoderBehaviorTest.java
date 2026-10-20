package com.github.auties00.smalt.image.behavior;

import com.github.auties00.smalt.testkit.Conformance;
import com.github.auties00.smalt.testkit.image.ImageDecoderAsserts;
import com.github.auties00.smalt.testkit.image.RasterTesting;
import com.github.auties00.smalt.testkit.io.BehaviorSamples;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/**
 * Runs every image decoder of this artifact through the I/O behavior harness ({@link ImageDecoderAsserts}):
 * the samples of {@code src/test/resources/samples} (made by {@code tools/testdata/behavior/make-image-samples.sh}
 * and pinned in {@code samples.tsv} there), a selection of the committed codec vectors, PngSuite images and
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
            "jpeg/cjpeg12/17x1-yuv411-progressive-arithmetic.jpg",
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

    /** The PngSuite images, covering every color type, depth, interlacing and ancillary chunk family. */
    private static final List<String> PNGSUITE = List.of(
            "basn0g01.png", "basn0g16.png", "basn2c16.png", "basn3p02.png", "basn4a08.png", "basn6a16.png",
            "basi0g04.png", "basi3p08.png", "basi6a08.png", "tbbn3p08.png", "s09i3p02.png", "g04n2c08.png",
            "ccwn2c08.png", "cdun2c08.png", "exif2c08.png");

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

    static Stream<String> pngSuite() {
        return PNGSUITE.stream();
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
    @MethodSource("resources")
    @DisplayName("codec vectors: identical pictures under every source behavior and clean faults")
    void resource(String file) throws IOException {
        ImageDecoderAsserts.of("resources/" + file, Files.readAllBytes(RESOURCES.resolve(file))).run();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("pngSuite")
    @DisplayName("PngSuite: identical pictures under every source behavior and clean faults")
    void pngSuite(String name) {
        var files = RasterTesting.untarGz(Conformance.fetchListed("PngSuite-2017jul19.tgz"));
        ImageDecoderAsserts.of("pngsuite/" + name, files.get(name)).run();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("vectors")
    @DisplayName("smoke vectors: identical pictures under every source behavior and clean faults")
    void vector(String name) throws IOException {
        ImageDecoderAsserts.of("vectors/" + name, Files.readAllBytes(Conformance.fetchListed(name))).run();
    }
}
