package com.github.auties00.smalt.image.tiff;

import com.github.auties00.smalt.testkit.image.ImageDecoderAsserts;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

/**
 * Runs the rarer TIFF layouts of {@link TiffVariantSamples} through the I/O behavior harness
 * ({@link ImageDecoderAsserts}), each compared with its golden dump under
 * {@code src/test/resources/dumps/tiff-variants}; the committed YCbCr and multi-ink vectors run in
 * {@code ImageDecoderBehaviorTest}.
 */
@DisplayName("TIFF photometric and sample variants under every source behavior")
class TiffVariantsBehaviorTest {
    static Stream<String> names() {
        return TiffVariantSamples.files().keySet().stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("names")
    @DisplayName("identical pictures under every source behavior and clean faults")
    void variant(String name) {
        ImageDecoderAsserts.of("tiff-variants/" + name, TiffVariantSamples.files().get(name)).run();
    }
}
