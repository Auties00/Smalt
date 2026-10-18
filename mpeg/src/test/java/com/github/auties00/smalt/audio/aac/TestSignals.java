package com.github.auties00.smalt.audio.aac;

import com.github.auties00.smalt.audio.aac.internal.AacKernels;
import com.github.auties00.smalt.audio.aac.internal.Fft;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Random;

/**
 * Deterministic synthetic test signals (speech-like, music-like, transients, noise, silence) and
 * objective quality metrics (segmental SNR, bandwidth, pre-echo energy) for encoder tests.
 */
final class TestSignals {
    private TestSignals() {
    }

    enum Kind {
        SPEECH, MUSIC, TRANSIENTS, NOISE, SILENCE
    }

    /** Returns {@code channels} planes of a signal; channels differ so stereo tools are exercised. */
    static float[][] generate(Kind kind, int sampleRate, int channels, double seconds) {
        var length = (int) (sampleRate * seconds);
        var planes = new float[channels][length];
        for (var c = 0; c < channels; c++) {
            var random = new Random(1234 + 17L * c + kind.ordinal());
            switch (kind) {
                case SPEECH -> speech(planes[c], sampleRate, random, c);
                case MUSIC -> music(planes[c], sampleRate, random, c);
                case TRANSIENTS -> transients(planes[c], sampleRate, random, c);
                case NOISE -> noise(planes[c], random);
                case SILENCE -> {
                }
            }
        }
        return planes;
    }

    private static void speech(float[] out, int fs, Random random, int channel) {
        double phase = 0;
        double[] formants = {700, 1220, 2600};
        var state = new double[3][2];
        for (var i = 0; i < out.length; i++) {
            var t = (double) i / fs;
            var syllable = 0.5 + 0.5 * Math.sin(2 * Math.PI * 4 * t);
            var f0 = 120 + 40 * Math.sin(2 * Math.PI * 0.7 * t) + 10 * channel;
            phase += 2 * Math.PI * f0 / fs;
            double voiced = 0;
            for (var h = 1; h <= 30 && h * f0 < fs / 2.0; h++) {
                voiced += Math.sin(h * phase) / h;
            }
            var fricative = ((int) (t * 3) % 3 == 2) ? random.nextGaussian() * 0.3 : 0;
            var excitation = voiced * syllable * 0.3 + fricative;
            double sum = 0;
            for (var f = 0; f < 3; f++) {
                var w = 2 * Math.PI * formants[f] * (1 + 0.1 * Math.sin(2 * Math.PI * 1.3 * t + f)) / fs;
                var r = 0.97;
                var y = excitation + 2 * r * Math.cos(w) * state[f][0] - r * r * state[f][1];
                state[f][1] = state[f][0];
                state[f][0] = y;
                sum += y * (1 - r) * 0.5;
            }
            out[i] = (float) Math.max(-1, Math.min(1, sum * 0.6));
        }
    }

    private static void music(float[] out, int fs, Random random, int channel) {
        double[][] chords = {{261.63, 329.63, 392.0}, {220.0, 261.63, 329.63}, {174.61, 220.0, 261.63}, {196.0, 246.94, 293.66}};
        for (var i = 0; i < out.length; i++) {
            var t = (double) i / fs;
            var index = (int) (t / 0.5) % chords.length;
            var local = t % 0.5;
            double value = 0;
            for (var note : chords[index]) {
                for (var h = 1; h <= 8 && note * h < fs / 2.0; h++) {
                    value += Math.sin(2 * Math.PI * note * h * t + h + channel * 0.3) / (h * h) * 0.12;
                }
            }
            var piano = Math.exp(-local * 6) * Math.sin(2 * Math.PI * 523.25 * (1 + 0.001 * channel) * t) * 0.25;
            var noiseSection = ((int) (t / 2)) % 2 == 1 ? random.nextGaussian() * 0.03 : 0;
            out[i] = (float) (value + piano + noiseSection);
        }
    }

    private static void transients(float[] out, int fs, Random random, int channel) {
        for (var i = 0; i < out.length; i++) {
            var t = (double) i / fs;
            var sinceClick = t % 0.25;
            var click = sinceClick < 0.02 ? Math.exp(-sinceClick * 300) * (random.nextGaussian() * 0.4
                    + Math.sin(2 * Math.PI * 3000 * t) * 0.4) : 0;
            out[i] = (float) (click + 0.05 * Math.sin(2 * Math.PI * (220 + 20 * channel) * t));
        }
    }

    private static void noise(float[] out, Random random) {
        double b0 = 0, b1 = 0, b2 = 0;
        for (var i = 0; i < out.length; i++) {
            var white = random.nextGaussian() * 0.1;
            b0 = 0.99765 * b0 + white * 0.0990460;
            b1 = 0.96300 * b1 + white * 0.2965164;
            b2 = 0.57000 * b2 + white * 1.0526913;
            out[i] = (float) ((b0 + b1 + b2 + white * 0.1848) * 0.8);
        }
    }

    /** Returns the segmental SNR in dB over 2048-sample frames, clamped to [-10, 60], skipping silent frames. */
    static double segmentalSnr(float[][] reference, float[][] test) {
        double total = 0;
        var count = 0;
        var frame = 2048;
        for (var c = 0; c < reference.length; c++) {
            var length = Math.min(reference[c].length, test[c].length);
            for (var start = 0; start + frame <= length; start += frame) {
                double signal = 0;
                double noise = 0;
                for (var i = start; i < start + frame; i++) {
                    signal += (double) reference[c][i] * reference[c][i];
                    var d = (double) reference[c][i] - test[c][i];
                    noise += d * d;
                }
                if (signal / frame < 1e-7) {
                    continue;
                }
                var snr = 10 * Math.log10(signal / Math.max(noise, 1e-20));
                total += Math.max(-10, Math.min(60, snr));
                count++;
            }
        }
        return count == 0 ? Double.NaN : total / count;
    }

    /** Returns the frequency above which the decoded long-term spectrum stays 20 dB below the reference's. */
    static double bandwidth(float[] reference, float[] test, int sampleRate) {
        var n = 2048;
        var fft = new Fft(n, AacKernels.scalar());
        var refPower = new double[n / 2];
        var testPower = new double[n / 2];
        var re = new float[n];
        var im = new float[n];
        var length = Math.min(reference.length, test.length);
        for (var start = 0; start + n <= length; start += n / 2) {
            for (var which = 0; which < 2; which++) {
                var source = which == 0 ? reference : test;
                for (var i = 0; i < n; i++) {
                    var window = 0.5 - 0.5 * Math.cos(2 * Math.PI * i / n);
                    re[i] = (float) (source[start + i] * window);
                    im[i] = 0;
                }
                fft.transform(re, im);
                var target = which == 0 ? refPower : testPower;
                for (var k = 0; k < n / 2; k++) {
                    var p = fft.position(k);
                    target[k] += (double) re[p] * re[p] + (double) im[p] * im[p];
                }
            }
        }
        var peak = 0.0;
        for (var value : refPower) {
            peak = Math.max(peak, value);
        }
        var highest = 0;
        for (var k = 1; k < n / 2; k++) {
            if (refPower[k] > peak * 1e-7 && testPower[k] > refPower[k] * 0.01) {
                highest = k;
            }
        }
        return highest * (double) sampleRate / n;
    }

    /** Returns interleaved little-endian float WAV bytes. */
    static byte[] wav(float[][] planes, int sampleRate) {
        var channels = planes.length;
        var frames = planes[0].length;
        var data = ByteBuffer.allocate(frames * channels * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (var i = 0; i < frames; i++) {
            for (var c = 0; c < channels; c++) {
                data.putFloat(planes[c][i]);
            }
        }
        var out = new ByteArrayOutputStream();
        var header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        header.put("RIFF".getBytes()).putInt(36 + data.capacity()).put("WAVE".getBytes());
        header.put("fmt ".getBytes()).putInt(16).putShort((short) 3).putShort((short) channels).putInt(sampleRate)
                .putInt(sampleRate * channels * 4).putShort((short) (channels * 4)).putShort((short) 32);
        header.put("data".getBytes()).putInt(data.capacity());
        out.writeBytes(header.array());
        out.writeBytes(data.array());
        return out.toByteArray();
    }
}
