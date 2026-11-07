package com.github.auties00.smalt.image.behavior;

import com.github.auties00.smalt.testkit.container.ReaderAsserts;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Decodes the video tracks of PNG, TIFF and GIF frames and of HAP, the image-based video codecs of this artifact,
 * through the I/O behavior harness ({@link ReaderAsserts}) over the container samples of
 * {@code tools/testdata/behavior/make-intermediate-samples.sh} (core's {@code src/test/resources/samples}), comparing
 * each with its per-frame CRC dump under {@code src/test/resources/dumps}.
 */
@DisplayName("PNG, TIFF, GIF and HAP video decoder I/O behaviors")
class ImageVideoBehaviorTest {
    private static final Path SAMPLES = Path.of("..", "core", "src", "test", "resources", "samples");

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"mp4/png-rgba.mov", "mp4/tiff.mov", "mp4/gif.mov", "mp4/hap-alpha.mov", "avi/png.avi",
            "avi/hap-q.avi", "matroska/png.mkv", "matroska/hap.mkv"})
    @DisplayName("samples: identical frames under every source behavior and clean faults")
    void sample(String sample) throws IOException {
        ReaderAsserts.video(sample, Files.readAllBytes(SAMPLES.resolve(sample))).run();
    }
}
