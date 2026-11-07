package com.github.auties00.smalt.fuzz;

import com.code_intelligence.jazzer.junit.FuzzTest;
import com.github.auties00.smalt.Media;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.probe.ProbeOptions;
import com.github.auties00.smalt.testkit.fuzz.Fuzz;

/**
 * Fuzz target of {@link Media#probe}: any input, of any format, is detected and described from its headers and
 * first frames.
 */
class ProbeFuzzTest {
    @FuzzTest
    void probe(byte[] data) {
        var run = Fuzz.start("probe");
        run.value("probe", () -> Media.probe(MediaSource.of(data), ProbeOptions.builder()
                .limits(Fuzz.LIMITS)
                .attachedPictures(true)
                .readFirstFrames(true)
                .build())).ifPresent(info -> run.output());
        run.step("headers", () -> Media.probe(MediaSource.of(data), ProbeOptions.headersOnly().toBuilder()
                .limits(Fuzz.LIMITS)
                .build()));
    }
}
