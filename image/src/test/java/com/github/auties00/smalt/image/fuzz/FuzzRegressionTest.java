package com.github.auties00.smalt.image.fuzz;

import com.github.auties00.smalt.testkit.fuzz.FuzzRegressions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicNode;
import org.junit.jupiter.api.TestFactory;

import java.util.stream.Stream;

@DisplayName("image fuzz targets: seeds and saved findings")
class FuzzRegressionTest {
    @TestFactory
    @DisplayName("replays every seed and finding, and accepts every valid seed")
    Stream<DynamicNode> replay() {
        return FuzzRegressions.replay(ImageFuzzTest.class, ImageMetadataFuzzTest.class, ImageVideoFuzzTest.class);
    }
}
