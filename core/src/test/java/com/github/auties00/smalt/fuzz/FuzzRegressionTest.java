package com.github.auties00.smalt.fuzz;

import com.github.auties00.smalt.testkit.fuzz.FuzzRegressions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicNode;
import org.junit.jupiter.api.TestFactory;

import java.util.stream.Stream;

@DisplayName("core fuzz targets: seeds and saved findings")
class FuzzRegressionTest {
    @TestFactory
    @DisplayName("replays every seed and finding, and accepts every valid seed")
    Stream<DynamicNode> replay() {
        return FuzzRegressions.replay(DemuxerFuzzTest.class, ProbeFuzzTest.class, TagFuzzTest.class,
                SubtitleFuzzTest.class, ColorFuzzTest.class, PcmFuzzTest.class, RemuxFuzzTest.class,
                GainMapFuzzTest.class, CompressionFuzzTest.class, ElementaryFuzzTest.class);
    }
}
