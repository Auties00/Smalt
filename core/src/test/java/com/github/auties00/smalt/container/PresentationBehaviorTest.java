package com.github.auties00.smalt.container;

import com.github.auties00.smalt.container.dash.DashDemuxer;
import com.github.auties00.smalt.container.hls.HlsDemuxer;
import com.github.auties00.smalt.io.DataReferenceResolver;
import com.github.auties00.smalt.testkit.Scratch;
import com.github.auties00.smalt.testkit.container.DemuxerAsserts;
import com.github.auties00.smalt.testkit.container.DemuxerAsserts.SeekPrecision;
import com.github.auties00.smalt.testkit.container.PresentationSamples;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

/**
 * Runs the HLS and DASH demuxers through the I/O behavior harness ({@link DemuxerAsserts}): the manifest of each
 * presentation {@link PresentationSamples} packages from the committed samples is the source whose behavior varies,
 * and its segments are read through a resolver over the directory they are written to. Each is compared with its
 * golden dump under {@code src/test/resources/dumps/presentations}. Cues that started before a seek target and are
 * still shown are returned first, and a transport stream restarts every track at the earliest of their key frames
 * (the I-frame playlist's partial audio included), so the seeks are checked at or before the target.
 */
@DisplayName("HLS and DASH demuxers under every source behavior")
class PresentationBehaviorTest {
    static Stream<String> presentations() {
        return PresentationSamples.names().stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("presentations")
    @DisplayName("presentations: identical packets under every source behavior, clean faults, contract seeks")
    void presentation(String name) {
        var sample = PresentationSamples.of(name, Long.MAX_VALUE);
        var manifest = PresentationSamples.write(sample, Scratch.directory("presentation-behavior").resolve(name));
        var options = DemuxerOptions.builder().dataReferences(DataReferenceResolver.directory(manifest.getParent()))
                .build();
        DemuxerAsserts.of("presentations/" + name, sample.manifestBytes())
                .factory(source -> name.startsWith("dash") ? new DashDemuxer(source, options)
                        : new HlsDemuxer(source, options))
                .seekPrecision(SeekPrecision.AT_OR_BEFORE)
                .run();
    }
}
