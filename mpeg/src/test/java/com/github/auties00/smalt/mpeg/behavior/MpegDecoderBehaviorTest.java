package com.github.auties00.smalt.mpeg.behavior;

import com.github.auties00.smalt.image.gainmap.GainMap;
import com.github.auties00.smalt.image.heic.HeicDecoder;
import com.github.auties00.smalt.testkit.Conformance;
import com.github.auties00.smalt.testkit.container.ReaderAsserts;
import com.github.auties00.smalt.testkit.image.ImageDecoderAsserts;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Runs the H.264, HEVC, MPEG-4 Part 2 and H.263 video decoders and the AAC, AC-3, E-AC-3 and AMR audio decoders
 * ({@link ReaderAsserts}) and the HEIC image decoder ({@link ImageDecoderAsserts}) of this artifact through
 * the I/O behavior harness over the container samples (core's {@code src/test/resources/samples}), smoke-tier
 * vectors, the HEIF fuzz seeds of AVC, uncompressed and layered HEVC items, the HEVC screen content coding and
 * separate color plane vectors and the multi-layer HEVC vectors ({@link HevcSamples}), comparing each with its golden
 * dump under {@code src/test/resources/dumps}.
 */
@DisplayName("MPEG and Dolby decoder I/O behaviors")
class MpegDecoderBehaviorTest {
    private static final Path SAMPLES = Path.of("..", "core", "src", "test", "resources", "samples");

    private static final Path HEIF_SEEDS = Path.of("src", "test", "resources", "com", "github", "auties00", "smalt",
            "mpeg", "fuzz", "MpegFuzzTestInputs", "heic");

    private static final Path HEVC_VECTORS = Path.of("src", "test", "resources", "com", "github", "auties00", "smalt",
            "video", "hevc");

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"mp4/faststart.mp4", "mp4/fragmented.mp4", "mp4/hevc.mp4", "mp4/h264-422-10.mov",
            "mp4/h264-444.mp4", "ts/h264-aac.ts", "avi/mpeg4-mp3.avi", "mp4/h263-amr.3gp", "mp4/quicktime.mov",
            "mp4/dolby-vision-p84.mp4", "matroska/dolby-vision-p84.mkv", "ts/dolby-vision-p84.ts"})
    @DisplayName("video samples: identical frames under every source behavior and clean faults")
    void video(String sample) throws IOException {
        ReaderAsserts.video(sample, Files.readAllBytes(SAMPLES.resolve(sample))).run();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"mp4/moov-last.mp4", "adts/aac.aac", "ts/h264-ac3.m2ts", "ac3/ac3.ac3", "ac3/eac3.eac3",
            "amr/nb.amr", "amr/wb.awb", "amr/nb-stereo.amr", "amr/wb-3ch.awb", "mp4/h263-amr.3gp", "wav/loas.wav",
            "wav/aac-raw.wav", "wav/ac3.wav", "wav/iec61937-ac3.wav", "wav/iec61937-aac.wav", "wav/amr.wav"})
    @DisplayName("audio samples: identical samples under every source behavior and clean faults")
    void audio(String sample) throws IOException {
        ReaderAsserts.audio(sample, Files.readAllBytes(SAMPLES.resolve(sample))).run();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"surround-7.1.4", "stereo-coupled"})
    @DisplayName("E-AC-3 tools of Smalt's encoder (dependent substreams, pre-noise data, enhanced coupling, metadata): identical samples under every source behavior")
    void eac3Tools(String name) throws IOException {
        ReaderAsserts.audio("ac3/smalt-" + name + ".ec3", com.github.auties00.smalt.audio.ac3.Eac3ToolStreams.stream(name))
                .run();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"mpegff-male_amr122.3gp", "mpeg4audio-al00_08.mp4", "eac3-csi_miami_5.1_256_spx_small.eac3"})
    @DisplayName("audio smoke vectors: identical samples under every source behavior and clean faults")
    void audioVector(String name) throws IOException {
        ReaderAsserts.audio("vectors/" + name, Files.readAllBytes(Conformance.fetchListed(name))).run();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"heic-pillow-tests-images-heif-L_8__29x100.heif",
            "heic-pillow-tests-images-heif-RGBA_10__29x100.heif", "heic-nokia-MIAF002.heic", "heic-nokia-C053.heic",
            "heic-pillow-tests-images-heif_other-L_exif_xmp_iptc.heic", "heic-nokia-multilayer004.heic"})
    @DisplayName("HEIC smoke vectors: identical pictures under every source behavior and clean faults")
    void heic(String name) throws IOException {
        ImageDecoderAsserts.of("vectors/" + name, Files.readAllBytes(Conformance.fetchListed(name))).run();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"valid-avc.heic", "valid-unci-rgb3.heic", "valid-unci-v210.heic", "valid-unci-zlib.heic",
            "valid-lhv1.heic"})
    @DisplayName("HEIF items of other codecs (the HEIC fuzz seeds): identical pictures under every source behavior")
    void heifItems(String name) throws IOException {
        ImageDecoderAsserts.of("heif-items/" + name, Files.readAllBytes(HEIF_SEEDS.resolve(name))).run();
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({"scc/scc-intra-444-8.hevc, 192, 128, 3, 8", "scc/scc-ld-444-8.hevc, 192, 128, 3, 8",
            "scc/scc-ra-420-10.hevc, 192, 128, 1, 10", "planes/planes-intra-8.bit, 128, 96, 3, 8",
            "planes/planes-ld-8.bit, 128, 96, 3, 8", "planes/planes-ra-10.bit, 128, 96, 3, 10"})
    @DisplayName("HEVC screen content and separate color plane vectors in MP4: identical frames under every source behavior")
    void hevcExtensions(String vector, int width, int height, int chromaFormatIdc, int bitDepth) throws IOException {
        var stream = Files.readAllBytes(HEVC_VECTORS.resolve(vector));
        ReaderAsserts.video("hevc/" + vector + ".mp4", HevcSamples.mp4(stream, width, height, chromaFormatIdc,
                bitDepth)).run();
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({"multilayer/shvc-2x-ld.hevc, 64, 48", "multilayer/mvhevc-ld.hevc, 128, 96",
            "multilayer/alpha-ld.hevc, 128, 96"})
    @DisplayName("multi-layer HEVC in MP4: identical base layer frames, alpha attached, under every source behavior")
    void hevcLayers(String vector, int width, int height) throws IOException {
        var stream = Files.readAllBytes(HEVC_VECTORS.resolve(vector));
        ReaderAsserts.video("hevc/" + vector + ".mp4", HevcSamples.mp4Layered(stream, width, height)).run();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"planes/planes-intra-8.bit"})
    @DisplayName("HEIC item coded with separate color planes: identical pictures under every source behavior")
    void heicSeparatePlanes(String vector) throws IOException {
        var stream = Files.readAllBytes(HEVC_VECTORS.resolve(vector));
        ImageDecoderAsserts.of("heif-items/" + vector + ".heic", HevcSamples.heic(stream, 128, 96, 3, 8)).run();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"valid-portrait.heic"})
    @DisplayName("HEIC gain maps and auxiliary images (the HEIC gain map fuzz seeds) under every source behavior")
    void heicGainMap(String name) throws IOException {
        var data = Files.readAllBytes(HEIF_SEEDS.resolveSibling("heicGainMap").resolve(name));
        ImageDecoderAsserts.of("gainmap/" + name, data)
                .decoder("gain map", source -> HeicDecoder.decodeGainMap(source).map(GainMap::image).orElse(null))
                .decoder("auxiliary images", source -> HeicDecoder.auxiliaryImages(source).toString())
                .decoder("depth map", source -> HeicDecoder.decodeAuxiliaryImage(source, 0).picture())
                .run();
    }
}
