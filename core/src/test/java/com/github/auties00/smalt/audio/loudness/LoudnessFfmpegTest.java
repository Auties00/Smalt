package com.github.auties00.smalt.audio.loudness;

import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Conformance;
import com.github.auties00.smalt.testkit.Oracle;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Compares the meter with ffmpeg used as a black box: its {@code ebur128} filter (integrated
 * loudness, loudness range at 10 Hz, true peak oversampled to 192 kHz), which passes the EBU
 * minimum requirements and is the reference, and its {@code loudnorm} filter, whose first-pass
 * measurement is checked loosely only (it reads EBU Tech 3341 case 5 at -23.14 LUFS). The inputs are real music from the
 * FLAC conformance corpus (decoded by ffmpeg to float WAV, so both meters see the same samples)
 * and synthesized speech-like material: bursts of shaped noise separated by pauses.
 */
@DisplayName("Loudness against ffmpeg")
class LoudnessFfmpegTest {
    private static final Pattern EBUR128_I = Pattern.compile("I:\\s+(-?[0-9.]+|-inf) LUFS");

    private static final Pattern EBUR128_LRA = Pattern.compile("LRA:\\s+(-?[0-9.]+) LU");

    private static final Pattern EBUR128_PEAK = Pattern.compile("Peak:\\s+(-?[0-9.]+|-inf) dBFS");

    private static final Pattern LOUDNORM = Pattern.compile("\"(input_i|input_tp|input_lra)\"\\s*:\\s*\"(-?[0-9.]+|-inf)\"");

    static Stream<Arguments> inputs() {
        var inputs = new ArrayList<Arguments>();
        for (var name : List.of("24", "25", "26", "27", "53", "55", "28", "33", "21", "41")) {
            inputs.add(Arguments.of("music " + name, "flac-cellar-subset-" + name + ".flac"));
        }
        inputs.add(Arguments.of("speech-like 48 kHz mono", null));
        return inputs.stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("inputs")
    @DisplayName("agrees on integrated loudness, loudness range and true peak")
    void agrees(String name, String vector, @TempDir Path directory) throws IOException {
        Oracle.assume(Oracle.Tool.FFMPEG);
        var wav = directory.resolve("input.wav");
        if (vector == null) {
            Files.write(wav, Signals.wav(speechLike(), 48_000));
        } else {
            var source = Conformance.fetchListed(vector);
            run(List.of("-v", "error", "-y", "-i", source.toString(), "-c:a", "pcm_f32le", wav.toString()));
        }
        var stats = Loudness.measure(MediaSource.of(wav));
        var ebur128 = run(List.of("-hide_banner", "-nostats", "-i", wav.toString(), "-af", "ebur128=peak=true:framelog=quiet",
                "-f", "null", "-"));
        var loudnorm = run(List.of("-hide_banner", "-nostats", "-i", wav.toString(), "-af", "loudnorm=print_format=json",
                "-f", "null", "-"));
        var summary = ebur128.substring(ebur128.lastIndexOf("Summary:"));
        var integrated = value(EBUR128_I, summary);
        var range = value(EBUR128_LRA, summary);
        var peak = value(EBUR128_PEAK, summary);
        var json = LOUDNORM.matcher(loudnorm);
        double normI = Double.NaN;
        double normTp = Double.NaN;
        double normLra = Double.NaN;
        while (json.find()) {
            var parsed = parse(json.group(2));
            switch (json.group(1)) {
                case "input_i" -> normI = parsed;
                case "input_tp" -> normTp = parsed;
                default -> normLra = parsed;
            }
        }
        System.out.printf("%s: smalt I=%.2f LRA=%.2f TP=%.2f | ebur128 I=%.1f LRA=%.1f TP=%.1f | loudnorm I=%.2f LRA=%.2f TP=%.2f%n",
                name, stats.integrated(), stats.loudnessRange(), stats.truePeakDb(), integrated, range, peak, normI, normLra, normTp);
        assertEquals(integrated, stats.integrated(), 0.1, "integrated against ebur128");
        // loudnorm's first pass is only a sanity bound: it reads EBU Tech 3341 case 5 at -23.14 LUFS
        assertEquals(normI, stats.integrated(), 1.5, "integrated against loudnorm");
        assertEquals(range, stats.loudnessRange(), 0.5, "loudness range against ebur128");
        assertEquals(peak, stats.truePeakDb(), 0.3, "true peak against ebur128");
        assertTrue(stats.truePeakDb() >= stats.samplePeakDb());
    }

    /**
     * Builds 40 s of speech-like mono audio: syllable-length bursts of low-pass noise with an
     * irregular envelope, separated by short and long pauses.
     */
    private static float[][] speechLike() {
        var rate = 48_000;
        var samples = new float[40 * rate];
        var random = new Random(11);
        var position = 0;
        var low = 0.0;
        while (position < samples.length) {
            var burst = (int) (rate * (0.08 + random.nextDouble() * 0.25));
            var level = Math.pow(10, (-28 + random.nextGaussian() * 6) / 20);
            for (var index = 0; index < burst && position + index < samples.length; index++) {
                low += 0.15 * (random.nextGaussian() - low);
                var envelope = Math.sin(Math.PI * index / burst);
                samples[position + index] = (float) (level * 4 * low * envelope);
            }
            position += burst;
            position += (int) (rate * (random.nextDouble() < 0.15 ? 0.6 + random.nextDouble() : 0.03 + random.nextDouble() * 0.1));
        }
        return new float[][]{samples};
    }

    private static double value(Pattern pattern, String text) {
        var matcher = pattern.matcher(text);
        assertTrue(matcher.find(), "no match for " + pattern + " in " + text);
        return parse(matcher.group(1));
    }

    private static double parse(String value) {
        return value.equals("-inf") ? Double.NEGATIVE_INFINITY : Double.parseDouble(value);
    }

    private static String run(List<String> arguments) {
        var command = new ArrayList<String>();
        command.add(Oracle.find(Oracle.Tool.FFMPEG).orElseThrow().toString());
        command.addAll(arguments);
        try {
            var process = new ProcessBuilder(command).redirectErrorStream(true).start();
            process.getOutputStream().close();
            var out = new ByteArrayOutputStream();
            try (var input = process.getInputStream()) {
                input.transferTo(out);
            }
            if (!process.waitFor(5, TimeUnit.MINUTES) || process.exitValue() != 0) {
                throw new IllegalStateException("ffmpeg failed: " + out.toString(StandardCharsets.UTF_8));
            }
            return out.toString(StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }
}
