package com.github.auties00.smalt.aot.internal;

import com.github.auties00.smalt.aot.TrainingFamily;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs the workloads that train the decoders without a Smalt encoder until the optimizing compiler has compiled the
 * decoders' hot code (each entry a class, or classes separated by {@code |} of which one suffices), which shows that
 * each workload reaches the code it is meant to train: a workload that decoded nothing, or failed before its decoder
 * ran, leaves the decoder's classes uncompiled.
 */
@DisplayName("training compilations")
class TrainingCompilationTest {
    private static final String RAW = "com.github.auties00.smalt.image.raw.internal.";

    // the longest each workload runs before its decoders must be compiled
    private static final long LIMIT_NANOS = 60_000_000_000L;

    static Stream<Arguments> workloads() {
        return Stream.of(
                Arguments.of(TrainingFamily.RAW, "raw-decode-iiq", List.of(RAW + "IiqDecoder", RAW + "IiqCalibration")),
                Arguments.of(TrainingFamily.RAW, "raw-decode-nef", List.of(RAW + "NefParser")),
                Arguments.of(TrainingFamily.RAW, "raw-decode-cr2", List.of(RAW + "Cr2Parser")),
                Arguments.of(TrainingFamily.RAW, "raw-decode-pef", List.of(RAW + "PefParser")),
                Arguments.of(TrainingFamily.RAW, "raw-decode-arw", List.of(RAW + "ArwParser")),
                Arguments.of(TrainingFamily.RAW, "raw-decode-orf", List.of(RAW + "OlympusDecoder")),
                Arguments.of(TrainingFamily.RAW, "raw-decode-rw2", List.of(RAW + "PanasonicDecoder")),
                Arguments.of(TrainingFamily.RAW, "raw-decode-erf-mef-srw",
                        List.of(RAW + "PackedRows|" + RAW + "Unpack")),
                Arguments.of(TrainingFamily.RAW, "raw-decode-dcr", List.of(RAW + "KodakDecoder")),
                Arguments.of(TrainingFamily.RAW, "raw-decode-mos", List.of(RAW + "TiffSamples|" + RAW + "Unpack")),
                Arguments.of(TrainingFamily.RAW, "raw-decode-3fr", List.of(RAW + "HasselbladJpeg")),
                Arguments.of(TrainingFamily.IMAGE, "dds-decode",
                        List.of("com.github.auties00.smalt.image.dds.internal.BcnBlocks")),
                Arguments.of(TrainingFamily.IMAGE, "psd-decode",
                        List.of("com.github.auties00.smalt.image.psd.PsdDecoder")),
                Arguments.of(TrainingFamily.IMAGE, "exr-decode",
                        List.of("com.github.auties00.smalt.image.exr.internal.ExrPiz"
                                        + "|com.github.auties00.smalt.image.exr.internal.ExrHuffman",
                                "com.github.auties00.smalt.image.exr.internal.ExrDwa")),
                Arguments.of(TrainingFamily.IMAGE, "jxr-decode",
                        List.of("com.github.auties00.smalt.image.jxr.internal.JxrTransform")),
                Arguments.of(TrainingFamily.MPEG, "h263-decode-optional-modes",
                        List.of("com.github.auties00.smalt.video.h263.internal.H263PlusStream")),
                Arguments.of(TrainingFamily.AUDIO, "dst-decode",
                        List.of("com.github.auties00.smalt.audio.dst.DstFrameDecoder")),
                Arguments.of(TrainingFamily.MPEG, "usac-decode",
                        List.of("com.github.auties00.smalt.audio.aac.internal.usac.ArithmeticDecoder")),
                Arguments.of(TrainingFamily.TRANSCODE, "bitmap-subtitles",
                        List.of("com.github.auties00.smalt.internal.subtitle.bitmap.PgsStream")),
                Arguments.of(TrainingFamily.TRANSCODE, "asf-files",
                        List.of("com.github.auties00.smalt.container.asf.AsfDemuxer")),
                Arguments.of(TrainingFamily.TRANSCODE, "vobsub-files",
                        List.of("com.github.auties00.smalt.container.subtitle.VobSubDemuxer"
                                        + "|com.github.auties00.smalt.container.subtitle.VobSubMuxer",
                                "com.github.auties00.smalt.internal.subtitle.bitmap.DvdSubtitleStream")),
                Arguments.of(TrainingFamily.TRANSCODE, "joined-inputs",
                        List.of("com.github.auties00.smalt.transcode.MergedDemuxer",
                                "com.github.auties00.smalt.transcode.SegmentedAudioDecoder")));
    }

    @ParameterizedTest(name = "{1}")
    @MethodSource("workloads")
    @DisplayName("a workload gets its decoders compiled by the optimizing compiler")
    void compiles(TrainingFamily family, String name, List<String> classes) throws Exception {
        var factory = Workloads.of(family).stream().filter(candidate -> candidate.name().equals(name)).findFirst()
                .orElseThrow();
        var packages = new ArrayList<String>();
        for (var alternatives : classes) {
            for (var type : alternatives.split("\\|")) {
                packages.add(type.substring(0, type.lastIndexOf('.')));
            }
        }
        try (var watch = CompilationWatch.start(packages)) {
            var workload = factory.create();
            var start = System.nanoTime();
            var missing = new ArrayList<>(classes);
            while (!missing.isEmpty() && System.nanoTime() - start < LIMIT_NANOS) {
                workload.round();
                watch.sync();
                // an entry of alternatives is met by any one of them: a small method the compiler inlines into its
                // caller has no compilation of its own
                missing.removeIf(alternatives -> Arrays.stream(alternatives.split("\\|")).anyMatch(watch::compiled));
            }
            assertTrue(missing.isEmpty(), name + " left " + missing + " uncompiled");
        }
    }
}
