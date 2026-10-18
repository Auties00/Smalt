package com.github.auties00.smalt.audio.voice;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.SplittableRandom;

/**
 * Deterministic echo-cancellation and noise-suppression scenarios at 16 kHz: formant-synthesized
 * far-end and near-end talkers, synthetic room impulse responses (a bulk delay, a unit direct
 * path and an exponentially decaying tail with RT60 200 ms), white or pink noise. Every value is
 * computed with {@link StrictMath} and {@link SplittableRandom}, so the signals, and therefore
 * the golden digests of the processed output, are identical on every platform and JVM.
 */
final class VoiceScenarios {
    static final int RATE = 16000;
    static final int FRAME = 160;

    /**
     * One scenario: the 16-bit render (far-end reference) and capture (microphone) streams, plus
     * the clean components of the capture for the quality metrics.
     */
    record Scenario(String name, short[] render, short[] capture, double[] echo, double[] near, double[] noise,
                    int nearFrom) {
        int frames() {
            return capture.length / FRAME;
        }
    }

    private VoiceScenarios() {
    }

    /**
     * The suite of the exit criteria: far-end only at 20-500 ms, double talk, echo-path change,
     * drift, noise, silence, clipping, and digital silence between talk spurts.
     */
    static List<Scenario> suite(int seconds) {
        var list = new ArrayList<Scenario>();
        for (var delay : new int[]{20, 100, 250, 500}) {
            list.add(farEndOnly(seconds, delay));
        }
        list.add(doubleTalk(seconds, 60));
        list.add(pathChange(seconds));
        list.add(drift(seconds, 100));
        list.add(drift(seconds, -100));
        list.add(noiseOnly(seconds));
        list.add(silence(seconds));
        list.add(clipping(seconds));
        list.add(pauses(seconds));
        return list;
    }

    static Scenario farEndOnly(int seconds, int delayMs) {
        var length = seconds * RATE;
        var far = farTalker(length);
        var echo = echoOf(far, delayMs, 11);
        var noise = white(length, -65, 3);
        return new Scenario("far-end " + delayMs + " ms", pcm(far), pcm(add(echo, noise)), echo, null, noise, length);
    }

    static Scenario doubleTalk(int seconds, int delayMs) {
        var length = seconds * RATE;
        var far = farTalker(length);
        var echo = echoOf(far, delayMs, 11);
        var nearFrom = length / 3;
        var near = nearTalker(length, nearFrom);
        var noise = white(length, -65, 3);
        return new Scenario("double talk", pcm(far), pcm(add(add(echo, near), noise)), echo, near, noise, nearFrom);
    }

    static Scenario pathChange(int seconds) {
        var length = seconds * RATE;
        var far = farTalker(length);
        var first = echoOf(far, 60, 11);
        var second = echoOf(far, 120, 29);
        var echo = new double[length];
        for (var i = 0; i < length; i++) {
            echo[i] = i < length / 2 ? first[i] : second[i];
        }
        var noise = white(length, -65, 3);
        return new Scenario("echo-path change", pcm(far), pcm(add(echo, noise)), echo, null, noise, length);
    }

    static Scenario drift(int seconds, int ppm) {
        var length = seconds * RATE;
        var far = farTalker(length);
        var drifted = new double[length];
        var ratio = 1 + ppm * 1e-6;
        for (var i = 0; i < length; i++) {
            var t = i * ratio;
            var k = (int) t;
            var f = t - k;
            drifted[i] = k + 1 < length ? far[k] * (1 - f) + far[k + 1] * f : 0;
        }
        var echo = echoOf(drifted, 60, 11);
        var noise = white(length, -65, 3);
        return new Scenario("drift " + (ppm > 0 ? "+" : "") + ppm + " ppm", pcm(far), pcm(add(echo, noise)), echo, null,
                noise, length);
    }

    static Scenario noiseOnly(int seconds) {
        var length = seconds * RATE;
        var noise = pink(length, -40, 5);
        return new Scenario("noise only", new short[length], pcm(noise), new double[length], null, noise, length);
    }

    static Scenario silence(int seconds) {
        var length = seconds * RATE;
        return new Scenario("silence", new short[length], new short[length], new double[length], null, new double[length],
                length);
    }

    /**
     * Far-end talk spurts of 2 s separated by 3 s of digital silence, echoed at 100 ms, with a
     * capture of echo and noise that is itself digitally silent (a muted microphone) in the last
     * fifth: the filters of the reference decay into subnormal numbers in every pause.
     */
    static Scenario pauses(int seconds) {
        var length = seconds * RATE;
        var far = farTalker(length);
        for (var i = 0; i < length; i++) {
            if ((i / RATE) % 5 >= 2) {
                far[i] = 0;
            }
        }
        var echo = echoOf(far, 100, 11);
        var noise = white(length, -65, 3);
        var capture = add(echo, noise);
        for (var i = length - length / 5; i < length; i++) {
            capture[i] = 0;
        }
        return new Scenario("render pauses", pcm(far), pcm(capture), echo, null, noise, length);
    }

    static Scenario clipping(int seconds) {
        var length = seconds * RATE;
        var far = scale(farTalker(length), StrictMath.pow(10, 14 / 20.0));
        var echo = scale(echoOf(far, 60, 11), StrictMath.pow(10, 12 / 20.0));
        var noise = white(length, -65, 3);
        return new Scenario("clipping", pcm(far), pcm(add(echo, noise)), echo, null, noise, length);
    }

    /**
     * A near-end talker alone whose level steps from -45 dBFS to -18 dBFS and then to -33 dBFS,
     * over noise at -65 dBFS: the case the gain controller adapts to.
     */
    static Scenario levelChanges(int seconds) {
        var length = seconds * RATE;
        var talker = nearTalker(length, 0);
        var levels = new double[]{-45, -18, -33};
        var out = new double[length];
        for (var i = 0; i < length; i++) {
            var step = Math.min(2, 3 * i / length);
            out[i] = talker[i] * StrictMath.pow(10, (levels[step] + 24) / 20.0);
        }
        var noise = white(length, -65, 7);
        return new Scenario("level changes", new short[length], pcm(add(out, noise)), new double[length], out, noise, 0);
    }

    static double[] farTalker(int length) {
        return normalizeActive(speech(length, 0x2545F4914F6CDD1DL, 105, 1.0), -20);
    }

    static double[] nearTalker(int length, int from) {
        var talker = normalizeActive(speech(length, 0x9E3779B97F4A7C15L, 190, 0.85), -24);
        var out = new double[length];
        System.arraycopy(talker, 0, out, from, length - from);
        return out;
    }

    /**
     * Synthesizes speech with a source-filter model: syllables of a glottal pulse train gliding in
     * pitch, or of noise, shaped by three formant resonators, with pauses between phrases.
     */
    static double[] speech(int length, long seed, int basePitch, double formantScale) {
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
            var syllableLength = RATE * (120 + (int) ((seed >>> 33) % 180)) / 1000;
            var silent = syllable % 9 == 8 || syllable % 23 == 22;
            var voiced = syllable % 4 != 3;
            var vowel = vowels[(int) ((seed >>> 40) % vowels.length)];
            for (var k = 0; k < 3; k++) {
                var r = StrictMath.exp(-Math.PI * bandwidths[k] / RATE);
                a1[k] = 2 * r * StrictMath.cos(2 * Math.PI * StrictMath.min(vowel[k] * formantScale, 0.45 * RATE) / RATE);
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
                    pulse += (pitch + 25 * StrictMath.sin(Math.PI * i / syllableLength)) / RATE;
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

    /** Scales x so that its active RMS (10 ms frames above -50 dB of the peak) is the given dBFS. */
    static double[] normalizeActive(double[] x, double dbfs) {
        var peak = 0.0;
        for (var v : x) {
            peak = StrictMath.max(peak, StrictMath.abs(v));
        }
        var threshold = peak * peak * 1e-5;
        var sum = 0.0;
        long count = 0;
        for (var f = 0; f + FRAME <= x.length; f += FRAME) {
            var e = 0.0;
            for (var i = 0; i < FRAME; i++) {
                e += x[f + i] * x[f + i];
            }
            if (e / FRAME > threshold) {
                sum += e;
                count += FRAME;
            }
        }
        var rms = StrictMath.sqrt(sum / count);
        return scale(x, StrictMath.pow(10, dbfs / 20) / rms);
    }

    static double[] echoOf(double[] far, int delayMs, long seed) {
        return scale(convolve(far, rir(delayMs, 200, -8, seed)), StrictMath.pow(10, -6 / 20.0));
    }

    /** A bulk delay, a unit direct path, then an exponentially decaying Gaussian tail of the given energy. */
    static double[] rir(int delayMs, double rt60Ms, double tailDb, long seed) {
        var delay = delayMs * RATE / 1000;
        var tailLength = (int) (rt60Ms * 1.2 * RATE / 1000);
        var h = new double[delay + 1 + tailLength];
        h[delay] = 1.0;
        var random = new SplittableRandom(seed);
        var tau = rt60Ms / 1000.0 / 6.9;
        var tail = new double[tailLength];
        var energy = 0.0;
        for (var i = 0; i < tailLength; i++) {
            var t = (i + 1) / (double) RATE;
            tail[i] = gauss(random) * StrictMath.exp(-t / tau);
            energy += tail[i] * tail[i];
        }
        var gain = StrictMath.sqrt(StrictMath.pow(10, tailDb / 10) / energy);
        for (var i = 0; i < tailLength; i++) {
            h[delay + 1 + i] = tail[i] * gain;
        }
        return h;
    }

    static double gauss(SplittableRandom random) {
        var u1 = random.nextDouble();
        var u2 = random.nextDouble();
        return StrictMath.sqrt(-2 * StrictMath.log(u1 + 1e-300)) * StrictMath.cos(2 * Math.PI * u2);
    }

    static double[] white(int length, double dbfs, long seed) {
        var random = new SplittableRandom(seed);
        var amplitude = StrictMath.pow(10, dbfs / 20);
        var y = new double[length];
        for (var i = 0; i < length; i++) {
            y[i] = amplitude * gauss(random);
        }
        return y;
    }

    /** Pink-ish noise (Paul Kellet's filter) at the given RMS. */
    static double[] pink(int length, double dbfs, long seed) {
        var random = new SplittableRandom(seed);
        var y = new double[length];
        double b0 = 0, b1 = 0, b2 = 0, b3 = 0, b4 = 0, b5 = 0, b6 = 0;
        for (var i = 0; i < length; i++) {
            var w = gauss(random);
            b0 = 0.99886 * b0 + w * 0.0555179;
            b1 = 0.99332 * b1 + w * 0.0750759;
            b2 = 0.96900 * b2 + w * 0.1538520;
            b3 = 0.86650 * b3 + w * 0.3104856;
            b4 = 0.55000 * b4 + w * 0.5329522;
            b5 = -0.7616 * b5 - w * 0.0168980;
            y[i] = b0 + b1 + b2 + b3 + b4 + b5 + b6 + w * 0.5362;
            b6 = w * 0.115926;
        }
        var energy = 0.0;
        for (var v : y) {
            energy += v * v;
        }
        return scale(y, StrictMath.pow(10, dbfs / 20) / StrictMath.sqrt(energy / length));
    }

    /** Linear convolution truncated to x.length, by FFT overlap-add. */
    static double[] convolve(double[] x, double[] h) {
        var m = h.length;
        var size = Integer.highestOneBit(Math.max(4 * m, 4096) - 1) << 1;
        var step = size - m + 1;
        var hr = new double[size];
        var hi = new double[size];
        System.arraycopy(h, 0, hr, 0, m);
        fft(hr, hi, false);
        var y = new double[x.length + m];
        var br = new double[size];
        var bi = new double[size];
        for (var position = 0; position < x.length; position += step) {
            Arrays.fill(br, 0);
            Arrays.fill(bi, 0);
            var count = Math.min(step, x.length - position);
            System.arraycopy(x, position, br, 0, count);
            fft(br, bi, false);
            for (var k = 0; k < size; k++) {
                var re = br[k] * hr[k] - bi[k] * hi[k];
                var im = br[k] * hi[k] + bi[k] * hr[k];
                br[k] = re;
                bi[k] = im;
            }
            fft(br, bi, true);
            for (var k = 0; k < size && position + k < y.length; k++) {
                y[position + k] += br[k];
            }
        }
        return Arrays.copyOf(y, x.length);
    }

    /** In-place radix-2 complex FFT; the inverse is scaled by 1/n. */
    static void fft(double[] re, double[] im, boolean inverse) {
        var n = re.length;
        for (int i = 1, j = 0; i < n; i++) {
            var bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) {
                j ^= bit;
            }
            j ^= bit;
            if (i < j) {
                var t = re[i];
                re[i] = re[j];
                re[j] = t;
                t = im[i];
                im[i] = im[j];
                im[j] = t;
            }
        }
        for (var length = 2; length <= n; length <<= 1) {
            var angle = 2 * Math.PI / length * (inverse ? 1 : -1);
            var wr = StrictMath.cos(angle);
            var wi = StrictMath.sin(angle);
            for (var i = 0; i < n; i += length) {
                double cr = 1;
                double ci = 0;
                for (var k = 0; k < length / 2; k++) {
                    var a = i + k;
                    var b = i + k + length / 2;
                    var tr = re[b] * cr - im[b] * ci;
                    var ti = re[b] * ci + im[b] * cr;
                    re[b] = re[a] - tr;
                    im[b] = im[a] - ti;
                    re[a] += tr;
                    im[a] += ti;
                    var ncr = cr * wr - ci * wi;
                    ci = cr * wi + ci * wr;
                    cr = ncr;
                }
            }
        }
        if (inverse) {
            for (var i = 0; i < n; i++) {
                re[i] /= n;
                im[i] /= n;
            }
        }
    }

    static double[] add(double[] a, double[] b) {
        var y = new double[a.length];
        for (var i = 0; i < y.length; i++) {
            y[i] = a[i] + b[i];
        }
        return y;
    }

    static double[] scale(double[] x, double gain) {
        var y = new double[x.length];
        for (var i = 0; i < x.length; i++) {
            y[i] = x[i] * gain;
        }
        return y;
    }

    /** Quantizes to 16-bit with rounding and saturation. */
    static short[] pcm(double[] x) {
        var s = new short[x.length];
        for (var i = 0; i < x.length; i++) {
            var v = StrictMath.round(x[i] * 32768.0);
            s[i] = (short) StrictMath.max(Short.MIN_VALUE, StrictMath.min(Short.MAX_VALUE, v));
        }
        return s;
    }
}
