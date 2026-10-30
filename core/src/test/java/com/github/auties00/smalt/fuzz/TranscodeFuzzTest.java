package com.github.auties00.smalt.fuzz;

import com.code_intelligence.jazzer.junit.FuzzTest;
import com.github.auties00.smalt.audio.AudioReader;
import com.github.auties00.smalt.audio.AudioReaderOptions;
import com.github.auties00.smalt.audio.process.Biquad;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.fuzz.Fuzz;
import com.github.auties00.smalt.testkit.fuzz.FuzzPackets;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.transcode.AudioTranscodeOptions;
import com.github.auties00.smalt.transcode.Fade;
import com.github.auties00.smalt.transcode.OutputContainer;
import com.github.auties00.smalt.transcode.SilenceTrim;
import com.github.auties00.smalt.transcode.TranscodeInput;
import com.github.auties00.smalt.transcode.TranscodeOptions;
import com.github.auties00.smalt.transcode.TranscodeOutput;
import com.github.auties00.smalt.transcode.TranscodeResult;
import com.github.auties00.smalt.transcode.Transcoder;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Fuzz targets of the transcodes that read several files or change the timing: a concatenation of the files an
 * input holds, separated by {@code FUZZ-TAG}, a combination of them as separate inputs written to two outputs, and
 * one file through the speed, pitch, filter, fade and silence steps. Every output must decode back to the duration
 * the transcode reports.
 */
class TranscodeFuzzTest {
    /**
     * The most files a fuzz input is split into.
     */
    private static final int MAX_FILES = 4;

    @FuzzTest
    void concat(byte[] data) {
        var run = Fuzz.start("transcode concat");
        var sources = new ArrayList<MediaSource>();
        for (var part : split(data, FuzzPackets.MARKER)) {
            sources.add(MediaSource.of(part));
        }
        var options = TranscodeOptions.of(OutputContainer.WAV).toBuilder().limits(Fuzz.LIMITS)
                .audio(AudioTranscodeOptions.builder().codec(Codec.PCM_F32LE).build()).build();
        var sink = MediaSink.memory();
        transcode(run, () -> Transcoder.concat(sources, sink, options)).ifPresent(result -> {
            check(sink.toByteArray(), result);
            run.output();
        });
    }

    @FuzzTest
    void combine(byte[] data) {
        var run = Fuzz.start("transcode combine");
        var inputs = new ArrayList<TranscodeInput>();
        for (var part : split(data, FuzzPackets.MARKER)) {
            inputs.add(TranscodeInput.of(MediaSource.of(part)));
        }
        var first = MediaSink.memory();
        var second = MediaSink.memory();
        var options = TranscodeOptions.of(OutputContainer.MATROSKA).toBuilder().limits(Fuzz.LIMITS).noVideo()
                .audio(AudioTranscodeOptions.builder().allTracks().codec(Codec.PCM_S16LE).build()).build();
        var outputs = List.of(new TranscodeOutput(first, options), new TranscodeOutput(second, options.toBuilder()
                .audio(AudioTranscodeOptions.builder().codec(Codec.PCM_S16LE).sampleRate(8_000).build()).build()));
        transcode(run, () -> Transcoder.transcode(inputs, outputs)).ifPresent(results -> {
            if (results.size() != 2) {
                throw new AssertionError("two outputs gave " + results.size() + " results");
            }
            if (!results.getFirst().streams().isEmpty() && results.getFirst().streams().getFirst().track().codec()
                    == Codec.PCM_S16LE) {
                check(first.toByteArray(), results.getFirst());
            }
            run.output();
        });
    }

    @FuzzTest
    void effects(byte[] data) {
        var run = Fuzz.start("transcode effects");
        var audio = AudioTranscodeOptions.builder().codec(Codec.PCM_F32LE);
        var job = TranscodeOptions.of(OutputContainer.WAV).toBuilder().limits(Fuzz.LIMITS);
        if (run.sampled(data, 0, 3)) {
            job.speed(1.5);
            audio.pitch(0.8);
        }
        if (run.sampled(data, 1, 3)) {
            audio.filters(List.of(Biquad.highPass(100), Biquad.peaking(1_000, 1, 6)))
                    .fades(List.of(Fade.in(10_000), Fade.out(20_000)));
        }
        if (run.sampled(data, 2, 3)) {
            job.silenceTrim(SilenceTrim.shorteningPauses(10_000));
        }
        var options = job.audio(audio.build()).build();
        var sink = MediaSink.memory();
        transcode(run, () -> Transcoder.transcode(MediaSource.of(data), sink, options)).ifPresent(result -> {
            check(sink.toByteArray(), result);
            run.output();
        });
    }

    /**
     * Runs a transcode as a step, an {@link IllegalArgumentException} (options the input cannot satisfy, files of a
     * concatenation whose tracks differ) being a rejection.
     */
    private static <T> java.util.Optional<T> transcode(com.github.auties00.smalt.testkit.fuzz.FuzzRun run,
                                                       java.util.function.Supplier<T> action) {
        try {
            return run.value("transcode", action);
        } catch (IllegalArgumentException exception) {
            run.reject("transcode: " + exception.getMessage());
            return java.util.Optional.empty();
        }
    }

    /**
     * Checks that an output decodes to the duration its transcode reports.
     */
    private static void check(byte[] output, TranscodeResult result) {
        var frames = 0L;
        int rate;
        try (var reader = AudioReader.open(MediaSource.of(output), AudioReaderOptions.builder()
                .limits(Fuzz.LIMITS.toBuilder().maxFrames(Integer.MAX_VALUE).build()).build())) {
            rate = reader.sampleRate();
            for (var block = reader.read(); block != null; block = reader.read()) {
                frames += block.frames();
            }
        } catch (com.github.auties00.smalt.MediaException exception) {
            throw new AssertionError("the output does not decode", exception);
        }
        var micros = Math.round(frames * 1e6 / rate);
        if (Math.abs(micros - result.durationMicros()) > 1_000_000L / rate + 1) {
            throw new AssertionError("the output holds " + micros + " us, the transcode reports "
                    + result.durationMicros());
        }
    }

    /**
     * Splits a fuzz input at a marker into at most {@value #MAX_FILES} files.
     */
    private static List<byte[]> split(byte[] data, byte[] marker) {
        var parts = new ArrayList<byte[]>();
        var start = 0;
        for (var index = 0; index + marker.length <= data.length && parts.size() < MAX_FILES - 1; index++) {
            if (Arrays.equals(data, index, index + marker.length, marker, 0, marker.length)) {
                parts.add(Arrays.copyOfRange(data, start, index));
                start = index + marker.length;
                index = start - 1;
            }
        }
        parts.add(Arrays.copyOfRange(data, start, data.length));
        return parts;
    }
}
