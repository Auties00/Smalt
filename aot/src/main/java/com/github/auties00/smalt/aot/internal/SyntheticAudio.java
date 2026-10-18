package com.github.auties00.smalt.aot.internal;

import com.github.auties00.smalt.audio.AudioBuffer;

import java.util.function.Consumer;

/**
 * Synthesizes the speech and music the audio training workloads code, deterministically.
 */
final class SyntheticAudio {
    /**
     * Prevents instantiation.
     */
    private SyntheticAudio() {
        throw new AssertionError();
    }

    /**
     * Synthesizes speech-like mono audio with a source-filter model: syllables of a glottal pulse
     * train gliding in pitch, or of noise, shaped by three formant resonators, with phrases
     * separated by digital silence.
     *
     * @param rate the sample rate in hertz
     * @param seconds the duration in seconds
     * @return the samples, peaking at 0.5
     */
    static float[] speech(int rate, double seconds) {
        var out = new float[(int) (rate * seconds)];
        int[][] vowels = {{730, 1090, 2440}, {270, 2290, 3010}, {300, 870, 2240}, {530, 1840, 2480}, {570, 840, 2410}};
        double[] bandwidths = {80, 100, 140};
        var y1 = new double[3];
        var y2 = new double[3];
        var a1 = new double[3];
        var a2 = new double[3];
        var seed = 0x2545F4914F6CDD1DL;
        var pulse = 0.0;
        var n = 0;
        for (var syllable = 0; n < out.length; syllable++) {
            seed = seed * 6364136223846793005L + 1442695040888963407L;
            var length = rate * (120 + (int) ((seed >>> 33) % 180)) / 1000;
            var silent = syllable % 9 == 8;
            var voiced = syllable % 4 != 3;
            var vowel = vowels[(int) ((seed >>> 40) % vowels.length)];
            for (var k = 0; k < 3; k++) {
                var r = Math.exp(-Math.PI * bandwidths[k] / rate);
                a1[k] = 2 * r * Math.cos(2 * Math.PI * Math.min(vowel[k], 0.45 * rate) / rate);
                a2[k] = -r * r;
            }
            var pitch = 95 + 12 * (syllable % 7);
            for (var i = 0; i < length && n < out.length; i++, n++) {
                seed = seed * 6364136223846793005L + 1442695040888963407L;
                var noise = ((seed >>> 40) / (double) (1L << 24)) - 0.5;
                double excitation;
                if (silent) {
                    excitation = 0;
                } else if (voiced) {
                    pulse += (pitch + 25 * Math.sin(Math.PI * i / length)) / rate;
                    excitation = 0.03 * noise;
                    if (pulse >= 1) {
                        pulse -= 1;
                        excitation += 1;
                    }
                } else {
                    excitation = 0.4 * noise;
                }
                var x = excitation;
                for (var k = 0; k < 3; k++) {
                    var y = x + a1[k] * y1[k] + a2[k] * y2[k];
                    y2[k] = y1[k];
                    y1[k] = y;
                    x = y * (1 + a2[k]);
                }
                out[n] = (float) (x * Math.sin(Math.PI * i / length));
            }
        }
        normalize(out, 0.5f);
        return out;
    }

    /**
     * Synthesizes stereo music: a four-chord progression of harmonic notes plucked on every half
     * second, a bass line and a noise hi-hat, panned differently in each channel.
     *
     * @param rate the sample rate in hertz
     * @param seconds the duration in seconds
     * @return the left and right planes, each peaking at 0.45
     */
    static float[][] music(int rate, double seconds) {
        var frames = (int) (rate * seconds);
        var left = new float[frames];
        var right = new float[frames];
        int[][] chords = {{57, 60, 64}, {53, 57, 60}, {48, 52, 55}, {55, 59, 62}};
        var phases = new double[4][6];
        var seed = 0x9E3779B97F4A7C15L;
        var hat = 0.0;
        for (var t = 0; t < frames; t++) {
            var chord = chords[(t / rate) % chords.length];
            var sinceBeat = (t % (rate / 2)) / (double) rate;
            var envelope = Math.exp(-4 * sinceBeat);
            double l = 0;
            double r = 0;
            for (var note = 0; note < 4; note++) {
                var midi = note < 3 ? chord[note] + 12 : chord[0] - 12;
                var frequency = 440 * Math.pow(2, (midi - 69) / 12.0);
                double value = 0;
                for (var h = 0; h < 6; h++) {
                    phases[note][h] += frequency * (h + 1) / rate;
                    phases[note][h] -= Math.floor(phases[note][h]);
                    value += Math.sin(2 * Math.PI * phases[note][h]) / (h + 1);
                }
                var pan = 0.2 + 0.2 * note;
                l += value * envelope * (1 - pan);
                r += value * envelope * pan;
            }
            seed = seed * 6364136223846793005L + 1442695040888963407L;
            var noise = ((seed >>> 40) / (double) (1L << 24)) - 0.5;
            var hatEnvelope = Math.exp(-40 * ((t % (rate / 8)) / (double) rate));
            var highPassed = noise - hat;
            hat = noise;
            left[t] = (float) (l + 0.3 * highPassed * hatEnvelope);
            right[t] = (float) (r + 0.2 * highPassed * hatEnvelope);
        }
        normalize(left, 0.45f);
        normalize(right, 0.45f);
        return new float[][]{left, right};
    }

    /**
     * Copies planar samples into a block, a block's capacity at a time, and passes each block to a
     * consumer.
     *
     * @param planes the samples, one plane per channel of {@code block}
     * @param block the buffer, refilled for every block
     * @param consumer receives each filled block
     */
    static void feed(float[][] planes, AudioBuffer block, Consumer<AudioBuffer> consumer) {
        var total = planes[0].length;
        for (var position = 0; position < total; position += block.capacity()) {
            var count = Math.min(block.capacity(), total - position);
            for (var channel = 0; channel < planes.length; channel++) {
                System.arraycopy(planes[channel], position, block.plane(channel), 0, count);
            }
            block.setFrames(count);
            consumer.accept(block);
        }
    }

    /**
     * Scales samples in place so that their largest magnitude is a given peak.
     *
     * @param samples the samples, scaled in place
     * @param peak the largest absolute sample after scaling
     */
    private static void normalize(float[] samples, float peak) {
        var max = 1e-9f;
        for (var sample : samples) {
            max = Math.max(max, Math.abs(sample));
        }
        var scale = peak / max;
        for (var i = 0; i < samples.length; i++) {
            samples[i] *= scale;
        }
    }
}
