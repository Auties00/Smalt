package com.github.auties00.smalt.image.behavior;

import com.github.auties00.smalt.testkit.Conformance;
import com.github.auties00.smalt.testkit.container.ReaderAsserts;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Decodes VP8, Motion-JPEG and Motion JPEG 2000 video, the video codecs of this artifact, through the I/O behavior
 * harness ({@link ReaderAsserts}) over the container samples (core's {@code src/test/resources/samples}) and a
 * smoke-tier vector, comparing each with its per-frame CRC dump under {@code src/test/resources/dumps}.
 */
@DisplayName("VP8, Motion-JPEG and Motion JPEG 2000 decoder I/O behaviors")
class Vp8BehaviorTest {
    private static final Path SAMPLES = Path.of("..", "core", "src", "test", "resources", "samples");

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"ivf/vp8.ivf", "avi/mjpeg-pcm.avi", "mp4/jpeg2000.mov", "mp4/motion.mj2"})
    @DisplayName("samples: identical frames under every source behavior and clean faults")
    void sample(String sample) throws IOException {
        ReaderAsserts.video(sample, Files.readAllBytes(SAMPLES.resolve(sample))).run();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"vp80-00-comprehensive-001.ivf"})
    @DisplayName("smoke vectors: identical frames under every source behavior and clean faults")
    void vector(String name) throws IOException {
        ReaderAsserts.video("vectors/" + name, Files.readAllBytes(Conformance.fetchListed(name))).run();
    }
}
