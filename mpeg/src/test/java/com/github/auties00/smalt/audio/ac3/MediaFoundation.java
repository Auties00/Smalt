package com.github.auties00.smalt.audio.ac3;

import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.Skip;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Drives the Dolby Digital Plus decoder of Windows Media Foundation through the
 * {@code tools/testdata/mf-oracle/MfAudioDecode} driver, which {@code -Dsmalt.test.mfAudioDecode}
 * names, and lines its output up with Smalt's: the driver's output starts 32 samples before
 * Smalt's decode of the same stream, and ends one frame earlier.
 */
final class MediaFoundation {
    /**
     * The samples the decoder outputs ahead of Smalt's decode.
     */
    static final int LEAD = 32;

    /**
     * The operational mode that reproduces a stream without dialogue normalization or dynamic range
     * control: custom mode 0, analog dialogue normalization.
     */
    static final int CUSTOM = 3;

    /**
     * The line mode: dialogue normalization to -31 dBFS and the dynamic range words.
     */
    static final int LINE = 1;

    private MediaFoundation() {
    }

    /**
     * A decode: interleaved samples aligned with Smalt's decode.
     *
     * @param samples the interleaved samples
     * @param channels the channel count
     * @param mask the WAVE channel mask
     */
    record Decoded(float[] samples, int channels, int mask) {
        int frames() {
            return samples.length / channels;
        }
    }

    /**
     * Returns the driver, skipping the test without one.
     *
     * @return the driver executable
     */
    static Path driver() {
        var setting = System.getProperty("smalt.test.mfAudioDecode");
        Skip.unless(setting != null && Files.isRegularFile(Path.of(setting)), "opt-in:mf-decode",
                "-Dsmalt.test.mfAudioDecode names no Media Foundation audio driver");
        return Path.of(setting);
    }

    /**
     * Decodes a file.
     *
     * @param driver the driver
     * @param input the stream
     * @param directory a scratch directory
     * @param channels the channel count to ask for, or {@code -1} for the decoder's choice
     * @param mode the operational mode
     * @param scale the dynamic range scale factors, from 0 to 100
     * @return the decode, aligned with Smalt's
     */
    static Decoded decode(Path driver, Path input, Path directory, int channels, int mode, int scale) {
        try {
            var output = Files.createTempFile(directory, "mf", ".f32");
            var arguments = new ArrayList<>(List.of(input.toString(), output.toString(),
                    channels < 0 ? "-" : Integer.toString(channels), Integer.toString(mode), Integer.toString(scale),
                    Integer.toString(scale)));
            var result = Oracle.execute(driver, Duration.ofMinutes(5), null, arguments);
            var stdout = new String(result.stdout(), StandardCharsets.US_ASCII).trim().split("\\s+");
            if (result.exitCode() != 0 || stdout.length < 4) {
                throw new AssertionError("Media Foundation could not decode " + input + ": " + result.stderr());
            }
            var count = Integer.parseInt(stdout[0]);
            var mask = Integer.parseInt(stdout[2]);
            var raw = Files.readAllBytes(output);
            var all = new float[raw.length / 4];
            ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(all);
            var aligned = new float[Math.max(0, all.length - LEAD * count)];
            System.arraycopy(all, LEAD * count, aligned, 0, aligned.length);
            return new Decoded(aligned, count, mask);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    /**
     * Writes bytes to a file of a directory.
     *
     * @param directory the directory
     * @param name the file name
     * @param data the bytes
     * @return the file
     */
    static Path write(Path directory, String name, byte[] data) {
        try {
            return Files.write(directory.resolve(name), data);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    /**
     * Low-pass filters every channel of interleaved samples with a 255-tap windowed-sinc filter, so
     * that a comparison leaves out the bands decoders synthesize differently (the noise of spectral
     * extension).
     *
     * @param samples the interleaved samples
     * @param channels the channel count
     * @param cutoff the cutoff as a fraction of the sampling rate
     * @return the filtered samples
     */
    static float[] lowPass(float[] samples, int channels, double cutoff) {
        var taps = new double[255];
        var sum = 0.0;
        for (var k = 0; k < taps.length; k++) {
            var n = k - 127;
            var sinc = n == 0 ? 2 * cutoff : Math.sin(2 * Math.PI * cutoff * n) / (Math.PI * n);
            taps[k] = sinc * (0.42 - 0.5 * Math.cos(2 * Math.PI * k / 254) + 0.08 * Math.cos(4 * Math.PI * k / 254));
            sum += taps[k];
        }
        var frames = samples.length / channels;
        var out = new float[samples.length];
        for (var ch = 0; ch < channels; ch++) {
            for (var i = 0; i < frames; i++) {
                var acc = 0.0;
                for (var k = 0; k < taps.length; k++) {
                    var j = i + k - 127;
                    if (j >= 0 && j < frames) {
                        acc += taps[k] * samples[j * channels + ch];
                    }
                }
                out[i * channels + ch] = (float) (acc / sum);
            }
        }
        return out;
    }

    /**
     * Returns the SNR in dB of each frame of {@code actual} against {@code expected}, over their
     * common length, measuring the signal of a frame as at least a fraction of the mean power of the
     * frames.
     *
     * @param expected the reference, interleaved
     * @param actual the compared samples, interleaved
     * @param channels the channel count
     * @param frame the samples per channel of a frame
     * @param floor the fraction of the mean frame power below which a frame's power counts as it
     * @return the SNR of each frame, infinite for an exact frame
     */
    static double[] frameSnr(float[] expected, float[] actual, int channels, int frame, double floor) {
        var frames = Math.min(expected.length, actual.length) / channels / frame;
        var result = new double[frames];
        var mean = 0.0;
        for (var i = 0; i < frames * frame * channels; i++) {
            mean += (double) expected[i] * expected[i];
        }
        mean /= Math.max(1, frames);
        for (var f = 0; f < frames; f++) {
            var signal = 0.0;
            var noise = 0.0;
            for (var i = f * frame * channels; i < (f + 1) * frame * channels; i++) {
                signal += (double) expected[i] * expected[i];
                var d = (double) expected[i] - actual[i];
                noise += d * d;
            }
            result[f] = noise == 0 ? Double.POSITIVE_INFINITY : 10 * Math.log10(Math.max(signal, floor * mean) / noise);
        }
        return result;
    }

    /**
     * Returns the amplitude of a tone in a channel of interleaved samples (a Goertzel filter over a
     * Hann window).
     *
     * @param samples the interleaved samples
     * @param channels the channel count
     * @param channel the channel
     * @param from the first sample per channel
     * @param length the samples per channel
     * @param frequency the tone frequency as a fraction of the sampling rate
     * @return the amplitude
     */
    static double tone(float[] samples, int channels, int channel, int from, int length, double frequency) {
        var re = 0.0;
        var im = 0.0;
        var window = 0.0;
        for (var n = 0; n < length; n++) {
            var w = 0.5 - 0.5 * Math.cos(2 * Math.PI * n / length);
            var x = samples[(from + n) * channels + channel] * w;
            re += x * Math.cos(2 * Math.PI * frequency * n);
            im -= x * Math.sin(2 * Math.PI * frequency * n);
            window += w;
        }
        return 2 * Math.hypot(re, im) / window;
    }
}
