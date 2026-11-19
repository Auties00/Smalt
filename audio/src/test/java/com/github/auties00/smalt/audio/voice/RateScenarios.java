package com.github.auties00.smalt.audio.voice;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

/**
 * Deterministic voice scenarios at any of the voice processor's rates and channel counts, built like
 * {@link VoiceScenarios}: formant-synthesized talkers, synthetic room impulse responses and white
 * or pink noise, every value computed with {@link StrictMath} and {@link SplittableRandom}. Each
 * capture channel hears every render channel through its own room response; with distinct render
 * content the loudspeakers play two different talkers (true stereo), otherwise the same signal.
 * Streams are 16-bit and interleaved.
 */
public final class RateScenarios {
    /**
     * One scenario: the interleaved 16-bit render and capture streams.
     */
    public record Streams(String name, int rate, int renderChannels, int captureChannels, short[] render,
                   short[] capture) {
        public int frameSamples() {
            return rate / 100;
        }

        public int frames() {
            return capture.length / (frameSamples() * captureChannels);
        }
    }

    private RateScenarios() {
    }

    /**
     * The far-end talker, echoed at 80 ms (plus 7 ms per further channel pair), over -65 dBFS
     * noise.
     */
    public static Streams farEnd(int seconds, int rate, int renderChannels, int captureChannels, boolean distinctRender) {
        return build("far-end", seconds, rate, renderChannels, captureChannels, distinctRender, false, 80);
    }

    /**
     * The far-end talker echoed at 60 ms with a near-end talker from a third of the stream on.
     */
    public static Streams doubleTalk(int seconds, int rate, int renderChannels, int captureChannels, boolean distinctRender) {
        return build("double talk", seconds, rate, renderChannels, captureChannels, distinctRender, true, 60);
    }

    /**
     * A near-end talker alone whose level steps from -45 to -18 and then to -33 dBFS, over noise
     * at -65 dBFS, with silence on the render side.
     */
    public static Streams levelChanges(int seconds, int rate, int renderChannels, int captureChannels) {
        var length = seconds * rate;
        var levels = new double[]{-45, -18, -33};
        var capture = new double[captureChannels][];
        for (var c = 0; c < captureChannels; c++) {
            var talker = talker(length, rate, 0x9E3779B97F4A7C15L, 190, 0.85, -24);
            var noise = white(length, -65, 7 + c);
            var out = new double[length];
            var channelGain = StrictMath.pow(10, -2.0 * c / 20);
            for (var i = 0; i < length; i++) {
                var step = Math.min(2, 3 * i / length);
                out[i] = talker[i] * StrictMath.pow(10, (levels[step] + 24) / 20.0) * channelGain + noise[i];
            }
            capture[c] = out;
        }
        return new Streams("level changes", rate, renderChannels, captureChannels,
                interleave(new double[renderChannels][length]), interleave(capture));
    }

    /**
     * Pink noise at -40 dBFS only, independent in every capture channel.
     */
    public static Streams noiseOnly(int seconds, int rate, int renderChannels, int captureChannels) {
        var length = seconds * rate;
        var capture = new double[captureChannels][];
        for (var c = 0; c < captureChannels; c++) {
            capture[c] = pink(length, -40, 5 + 31L * c);
        }
        return new Streams("noise only", rate, renderChannels, captureChannels,
                interleave(new double[renderChannels][length]), interleave(capture));
    }

    /**
     * A loud near-end talker, 16 dB above full scale at its peaks in the middle third, so that the
     * capture clips and the analog controllers lower the volume.
     */
    public static Streams clipping(int seconds, int rate, int renderChannels, int captureChannels) {
        var length = seconds * rate;
        var capture = new double[captureChannels][];
        for (var c = 0; c < captureChannels; c++) {
            var talker = talker(length, rate, 0x9E3779B97F4A7C15L, 190, 0.85, -24);
            var noise = white(length, -65, 9 + c);
            var out = new double[length];
            for (var i = 0; i < length; i++) {
                var db = i >= length / 3 && i < 2 * length / 3 ? 16 + 24 : -6;
                out[i] = talker[i] * StrictMath.pow(10, db / 20.0) + noise[i];
            }
            capture[c] = out;
        }
        return new Streams("clipping", rate, renderChannels, captureChannels,
                interleave(new double[renderChannels][length]), interleave(capture));
    }

    /**
     * Digital silence on both sides.
     */
    static Streams silence(int seconds, int rate, int renderChannels, int captureChannels) {
        var length = seconds * rate;
        return new Streams("silence", rate, renderChannels, captureChannels, new short[length * renderChannels],
                new short[length * captureChannels]);
    }

    /**
     * Returns the scenarios of an echo canceller check: far-end, double talk and noise.
     */
    static List<Streams> echoSuite(int seconds, int rate, int renderChannels, int captureChannels,
                                   boolean distinctRender) {
        var list = new ArrayList<Streams>();
        list.add(farEnd(seconds, rate, renderChannels, captureChannels, distinctRender));
        list.add(doubleTalk(seconds, rate, renderChannels, captureChannels, distinctRender));
        list.add(noiseOnly(seconds, rate, renderChannels, captureChannels));
        return list;
    }

    private static Streams build(String name, int seconds, int rate, int renderChannels, int captureChannels,
                                 boolean distinctRender, boolean near, int delayMs) {
        var length = seconds * rate;
        var render = new double[renderChannels][];
        var first = talker(length, rate, 0x2545F4914F6CDD1DL, 105, 1.0, -20);
        var second = distinctRender ? talker(length, rate, 0x632BE59BD9B4E019L, 140, 0.92, -22) : first;
        for (var r = 0; r < renderChannels; r++) {
            render[r] = r % 2 == 0 ? first : second;
        }
        var capture = new double[captureChannels][];
        for (var c = 0; c < captureChannels; c++) {
            var mix = white(length, -65, 3 + 17L * c);
            for (var r = 0; r < renderChannels; r++) {
                var path = scale(convolve(render[r], rir(rate, delayMs + 7 * (c + r), 200, -8, 11 + 13L * c + 5L * r)),
                        StrictMath.pow(10, (-6 - 3.0 * r) / 20.0));
                mix = add(mix, path);
            }
            if (near) {
                var talker = talker(length, rate, 0x9E3779B97F4A7C15L, 190, 0.85, -24);
                var from = length / 3;
                var gain = StrictMath.pow(10, -1.5 * c / 20);
                for (var i = from; i < length; i++) {
                    mix[i] += talker[i - from] * gain;
                }
            }
            capture[c] = mix;
        }
        return new Streams(name, rate, renderChannels, captureChannels, interleave(render), interleave(capture));
    }

    private static double[] talker(int length, int rate, long seed, int basePitch, double formantScale, double dbfs) {
        return normalizeActive(speech(length, rate, seed, basePitch, formantScale), rate, dbfs);
    }

    /**
     * Synthesizes speech with the source-filter model of {@link VoiceScenarios#speech}, at any rate.
     */
    static double[] speech(int length, int rate, long seed, int basePitch, double formantScale) {
        var out = new double[length];
        int[][] vowels = {{730, 1090, 2440}, {270, 2290, 3010}, {300, 870, 2240}, {530, 1840, 2480}, {570, 840, 2410}};
        double[] bandwidths = {80, 100, 140};
        var y1 = new double[3];
        var y2 = new double[3];
        var a1 = new double[3];
        var a2 = new double[3];
        var pulse = 0.0;
        var n = 0;
        for (var syllable = 0; n < length; syllable++) {
            seed = seed * 6364136223846793005L + 1442695040888963407L;
            var syllableLength = rate * (120 + (int) ((seed >>> 33) % 180)) / 1000;
            var silent = syllable % 9 == 8 || syllable % 23 == 22;
            var voiced = syllable % 4 != 3;
            var vowel = vowels[(int) ((seed >>> 40) % vowels.length)];
            for (var k = 0; k < 3; k++) {
                var r = StrictMath.exp(-Math.PI * bandwidths[k] / rate);
                a1[k] = 2 * r * StrictMath.cos(2 * Math.PI * StrictMath.min(vowel[k] * formantScale, 0.45 * rate) / rate);
                a2[k] = -r * r;
            }
            var pitch = basePitch + 12 * (syllable % 7);
            for (var i = 0; i < syllableLength && n < length; i++, n++) {
                seed = seed * 6364136223846793005L + 1442695040888963407L;
                var noise = ((seed >>> 40) / (double) (1L << 24)) - 0.5;
                double excitation;
                if (silent) {
                    excitation = 0;
                } else if (voiced) {
                    pulse += (pitch + 25 * StrictMath.sin(Math.PI * i / syllableLength)) / rate;
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
                out[n] = x * StrictMath.sin(Math.PI * i / syllableLength);
            }
        }
        return out;
    }

    private static double[] normalizeActive(double[] x, int rate, double dbfs) {
        var frame = rate / 100;
        var peak = 0.0;
        for (var v : x) {
            peak = StrictMath.max(peak, StrictMath.abs(v));
        }
        var threshold = peak * peak * 1e-5;
        var sum = 0.0;
        long count = 0;
        for (var f = 0; f + frame <= x.length; f += frame) {
            var e = 0.0;
            for (var i = 0; i < frame; i++) {
                e += x[f + i] * x[f + i];
            }
            if (e / frame > threshold) {
                sum += e;
                count += frame;
            }
        }
        var rms = StrictMath.sqrt(sum / count);
        return scale(x, StrictMath.pow(10, dbfs / 20) / rms);
    }

    private static double[] rir(int rate, int delayMs, double rt60Ms, double tailDb, long seed) {
        var delay = delayMs * rate / 1000;
        var tailLength = (int) (rt60Ms * 1.2 * rate / 1000);
        var h = new double[delay + 1 + tailLength];
        h[delay] = 1.0;
        var random = new SplittableRandom(seed);
        var tau = rt60Ms / 1000.0 / 6.9;
        var tail = new double[tailLength];
        var energy = 0.0;
        for (var i = 0; i < tailLength; i++) {
            var t = (i + 1) / (double) rate;
            tail[i] = VoiceScenarios.gauss(random) * StrictMath.exp(-t / tau);
            energy += tail[i] * tail[i];
        }
        var gain = StrictMath.sqrt(StrictMath.pow(10, tailDb / 10) / energy);
        for (var i = 0; i < tailLength; i++) {
            h[delay + 1 + i] = tail[i] * gain;
        }
        return h;
    }

    private static double[] white(int length, double dbfs, long seed) {
        return VoiceScenarios.white(length, dbfs, seed);
    }

    private static double[] pink(int length, double dbfs, long seed) {
        return VoiceScenarios.pink(length, dbfs, seed);
    }

    private static double[] convolve(double[] x, double[] h) {
        return VoiceScenarios.convolve(x, h);
    }

    private static double[] add(double[] a, double[] b) {
        return VoiceScenarios.add(a, b);
    }

    private static double[] scale(double[] x, double gain) {
        return VoiceScenarios.scale(x, gain);
    }

    /**
     * Interleaves channels and quantizes them to 16 bits with rounding and saturation.
     */
    static short[] interleave(double[][] channels) {
        var count = channels.length;
        var length = channels[0].length;
        var out = new short[count * length];
        for (var c = 0; c < count; c++) {
            var pcm = VoiceScenarios.pcm(channels[c]);
            for (var i = 0; i < length; i++) {
                out[i * count + c] = pcm[i];
            }
        }
        return out;
    }

    /**
     * Wraps a 16 kHz mono {@link VoiceScenarios.Scenario} as streams.
     */
    static Streams of(VoiceScenarios.Scenario scenario) {
        return new Streams(scenario.name(), VoiceScenarios.RATE, 1, 1, scenario.render(), scenario.capture());
    }
}
