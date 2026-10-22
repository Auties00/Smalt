package com.github.auties00.smalt.mpeg.behavior;

import com.github.auties00.smalt.testkit.Conformance;
import com.github.auties00.smalt.testkit.container.ReaderAsserts;
import com.github.auties00.smalt.testkit.image.ImageDecoderAsserts;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Runs the H.264, HEVC, MPEG-4 Part 2 and H.263 video decoders and the AAC, AC-3, E-AC-3 and AMR audio decoders
 * ({@link ReaderAsserts}) and the HEIC image decoder ({@link ImageDecoderAsserts}) of this artifact through
 * the I/O behavior harness over the container samples (core's {@code src/test/resources/samples}) and
 * smoke-tier vectors, comparing each with its golden dump under {@code src/test/resources/dumps}.
 */
@DisplayName("MPEG and Dolby decoder I/O behaviors")
class MpegDecoderBehaviorTest {
    private static final Path SAMPLES = Path.of("..", "core", "src", "test", "resources", "samples");

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"mp4/faststart.mp4", "mp4/fragmented.mp4", "mp4/hevc.mp4", "mp4/h264-422-10.mov",
            "ts/h264-aac.ts", "avi/mpeg4-mp3.avi", "mp4/h263-amr.3gp", "mp4/quicktime.mov"})
    @DisplayName("video samples: identical frames under every source behavior and clean faults")
    void video(String sample) throws IOException {
        ReaderAsserts.video(sample, Files.readAllBytes(SAMPLES.resolve(sample))).run();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"mp4/moov-last.mp4", "adts/aac.aac", "ts/h264-ac3.m2ts", "ac3/ac3.ac3", "ac3/eac3.eac3",
            "amr/nb.amr", "amr/wb.awb", "mp4/h263-amr.3gp", "wav/loas.wav", "wav/aac-raw.wav", "wav/ac3.wav",
            "wav/iec61937-ac3.wav", "wav/iec61937-aac.wav", "wav/amr.wav"})
    @DisplayName("audio samples: identical samples under every source behavior and clean faults")
    void audio(String sample) throws IOException {
        ReaderAsserts.audio(sample, Files.readAllBytes(SAMPLES.resolve(sample))).run();
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
            "heic-pillow-tests-images-heif_other-L_exif_xmp_iptc.heic"})
    @DisplayName("HEIC smoke vectors: identical pictures under every source behavior and clean faults")
    void heic(String name) throws IOException {
        ImageDecoderAsserts.of("vectors/" + name, Files.readAllBytes(Conformance.fetchListed(name))).run();
    }
}
