package com.github.auties00.smalt.video.behavior;

import com.github.auties00.smalt.image.avif.AvifDecoder;
import com.github.auties00.smalt.image.gainmap.GainMap;
import com.github.auties00.smalt.testkit.Conformance;
import com.github.auties00.smalt.testkit.container.ReaderAsserts;
import com.github.auties00.smalt.testkit.image.ImageDecoderAsserts;
import com.github.auties00.smalt.testkit.io.BehaviorSamples;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Runs the VP9, AV1, ProRes and DNxHR decoders ({@link ReaderAsserts}) and the AVIF image decoder
 * ({@link ImageDecoderAsserts}) of this artifact through the I/O behavior harness over the container samples
 * (core's {@code src/test/resources/samples}), the AVIF samples of derived images and sample transforms (this
 * module's {@code src/test/resources/samples}, made by {@code tools/testdata/behavior/make-avif-samples.py}) and
 * smoke-tier vectors, comparing each with its golden dump under {@code src/test/resources/dumps}.
 */
@DisplayName("video and AVIF decoder I/O behaviors")
class VideoDecoderBehaviorTest {
    private static final Path SAMPLES = Path.of("..", "core", "src", "test", "resources", "samples");

    private static final Path AVIF_SAMPLES = Path.of("src", "test", "resources", "samples");

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"matroska/vp9-vorbis.webm", "matroska/vp9-alpha.webm", "ivf/av1.ivf", "mp4/prores.mov", "mp4/dnxhr.mov"})
    @DisplayName("video samples: identical frames under every source behavior and clean faults")
    void sample(String sample) throws IOException {
        ReaderAsserts.video(sample, Files.readAllBytes(SAMPLES.resolve(sample))).run();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"vp90-2-02-size-64x64.webm", "av1-av1-1-b8-03-sizeup.mkv", "av1-av1-1-b8-01-size-16x16.ivf"})
    @DisplayName("video smoke vectors: identical frames under every source behavior and clean faults")
    void video(String name) throws IOException {
        ReaderAsserts.video("vectors/" + name, Files.readAllBytes(Conformance.fetchListed(name))).run();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"avif-Microsoft-Monochrome.avif", "avif-Microsoft-still_picture.avif",
            "avif-Microsoft-reduced_still_picture_header.avif", "avif-Microsoft-bbb_alpha_inverted.avif",
            "avif-Microsoft-Tomsk_with_thumbnails.avif", "avif-Netflix-avis-alpha_video.avif",
            "avif-libavif-weld_sato_12B_8B_q0.avif"})
    @DisplayName("AVIF smoke vectors: identical pictures under every source behavior and clean faults")
    void avif(String name) throws IOException {
        ImageDecoderAsserts.of("vectors/" + name, Files.readAllBytes(Conformance.fetchListed(name))).run();
    }

    @Test
    @DisplayName("the AVIF samples are the files pinned in tools/testdata/behavior/samples.tsv")
    void pinned() {
        BehaviorSamples.assertPinned(AVIF_SAMPLES);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"avif/derived.avif", "avif/sato.avif"})
    @DisplayName("AVIF samples: identical pictures under every source behavior and clean faults")
    void avifSample(String sample) throws IOException {
        ImageDecoderAsserts.of(sample, Files.readAllBytes(AVIF_SAMPLES.resolve(sample))).run();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"valid-generic-brand.avif"})
    @DisplayName("AVIF fuzz seeds with only the generic HEIF brands: identical pictures under every source behavior")
    void avifSeed(String name) throws IOException {
        ImageDecoderAsserts.of("avif/" + name, Files.readAllBytes(Path.of("src", "test", "resources", "com", "github",
                "auties00", "smalt", "video", "fuzz", "VideoFuzzTestInputs", "avif").resolve(name))).run();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"libavif-seine_sdr_gainmap_srgb.avif", "libavif-color_grid_alpha_grid_gainmap_nogrid.avif",
            "valid-tmap-multichannel.avif"})
    @DisplayName("AVIF gain maps: identical pictures, gain maps and auxiliary images under every source behavior")
    void avifGainMap(String name) throws IOException {
        var data = name.startsWith("libavif-") ? Files.readAllBytes(Conformance.fetchListed(name))
                : Files.readAllBytes(Path.of("src", "test", "resources", "com", "github", "auties00", "smalt", "video",
                "fuzz", "VideoFuzzTestInputs", "avifGainMap").resolve(name));
        ImageDecoderAsserts.of("gainmap/" + name, data)
                .decoder("gain map", source -> AvifDecoder.decodeGainMap(source).map(GainMap::image).orElse(null))
                .decoder("auxiliary images", source -> AvifDecoder.auxiliaryImages(source).toString())
                .run();
    }
}
