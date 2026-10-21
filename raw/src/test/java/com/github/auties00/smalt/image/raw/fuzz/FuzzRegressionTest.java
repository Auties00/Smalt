package com.github.auties00.smalt.image.raw.fuzz;

import com.github.auties00.smalt.testkit.fuzz.FuzzRegressions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicNode;
import org.junit.jupiter.api.TestFactory;

import java.util.Set;
import java.util.stream.Stream;

@DisplayName("raw fuzz targets: seeds and saved findings")
class FuzzRegressionTest {
    // the CRX sensor data of CR3 files is not decoded (see Cr3Parser), so no CR3 input is accepted by every step
    private static final Set<String> PARTIAL = Set.of("RawFuzzTest.cr3");

    @TestFactory
    @DisplayName("replays every seed and finding, and accepts every valid seed")
    Stream<DynamicNode> replay() {
        return FuzzRegressions.replay(PARTIAL, RawFuzzTest.class);
    }
}
