package com.github.auties00.smalt.audio.opus.internal.silk;

import com.github.auties00.smalt.audio.opus.internal.celt.RangeEncoder;

import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Drives {@link SilkEncoder} exactly like the C harness {@code ref/harness-silkenc/silkenc.c}
 * (which mirrors the SILK-only path of the Opus encoder), and synthesizes deterministic
 * speech-like test input.
 */
final class SilkEncoderHarness {
    private SilkEncoderHarness() {
    }

    /** One encoder configuration; field order matches the C harness arguments. */
    record Config(int apiFs, int chApi, int chInt, int maxInt, int minInt, int desiredInt, int payloadMs, int bitrate,
                  int complexity, int useCbr, int useDtx, int useFec, int loss, int prefillFirst, int activityMode,
                  int schedule, int signal) {
        String[] args() {
            return new String[]{Integer.toString(apiFs), Integer.toString(chApi), Integer.toString(chInt),
                    Integer.toString(maxInt), Integer.toString(minInt), Integer.toString(desiredInt),
                    Integer.toString(payloadMs), Integer.toString(bitrate), Integer.toString(complexity),
                    Integer.toString(useCbr), Integer.toString(useDtx), Integer.toString(useFec), Integer.toString(loss),
                    Integer.toString(prefillFirst), Integer.toString(activityMode), Integer.toString(schedule)};
        }

        String name() {
            return apiFs + "Hz_" + chApi + "x" + chInt + "_int" + maxInt + "-" + minInt + "-" + desiredInt + "_" + payloadMs
                    + "ms_" + bitrate + "bps_c" + complexity + (useCbr != 0 ? "_cbr" : "") + (useDtx != 0 ? "_dtx" : "")
                    + (useFec != 0 ? "_fec" + loss : "") + (prefillFirst != 0 ? "_prefill" : "")
                    + (activityMode != 0 ? "_act" : "") + (schedule != 0 ? "_sched" : "") + (signal != 0 ? "_sig" + signal : "");
        }
    }

    /**
     * Encodes interleaved PCM and returns the packets as {@code [int32 len][uint32 rng][payload]}
     * little-endian records, the format of the C harness.
     */
    static byte[] encode(short[] pcm, Config c) {
        var enc = new SilkEncoder();
        var ctl = new SilkEncControl();
        if (enc.initEncoder(ctl) != 0) {
            throw new IllegalStateException("init failed");
        }
        var n = c.payloadMs() * c.apiFs() / 1000;
        var frame = new short[n * c.chApi()];
        var buf = new byte[1275];
        var out = new ByteArrayOutputStream();
        var nBytes = new int[1];
        var rc = new RangeEncoder();
        var packet = 0;
        for (var pos = 0; pos + n * c.chApi() <= pcm.length; pos += n * c.chApi()) {
            System.arraycopy(pcm, pos, frame, 0, n * c.chApi());
            var phase = (packet / 20) % 4;
            int sBitrate = c.bitrate(), sDesired = c.desiredInt(), sChInt = c.chInt(), sComplexity = c.complexity(), sLoss = c.loss();
            int sCanSwitch = 0, sToMono = 0;
            if (c.schedule() != 0) {
                int[] rates = {c.bitrate(), c.bitrate() / 2, c.bitrate() * 2 > 40000 ? 40000 : c.bitrate() * 2, c.bitrate()};
                int[] des = {16000, 8000, 12000, 16000};
                sBitrate = rates[phase];
                sDesired = des[phase] > c.maxInt() ? c.maxInt() : des[phase];
                if (sDesired < c.minInt()) {
                    sDesired = c.minInt();
                }
                sCanSwitch = (packet % 20) == 19 ? 1 : 0;
                if (c.chApi() == 2 && c.chInt() == 2) {
                    sChInt = phase == 2 ? 1 : 2;
                    sToMono = phase == 1 && (packet % 20) == 19 ? 1 : 0;
                }
                sComplexity = phase == 2 ? (c.complexity() > 3 ? c.complexity() - 3 : 0) : c.complexity();
                sLoss = (c.loss() * (phase + 1)) % 50;
            }
            ctl.nChannelsAPI = c.chApi();
            ctl.nChannelsInternal = sChInt;
            ctl.API_sampleRate = c.apiFs();
            ctl.maxInternalSampleRate = c.maxInt();
            ctl.minInternalSampleRate = c.minInt();
            ctl.desiredInternalSampleRate = sDesired;
            ctl.payloadSize_ms = c.payloadMs();
            ctl.bitRate = sBitrate;
            ctl.packetLossPercentage = sLoss;
            ctl.complexity = sComplexity;
            ctl.useInBandFEC = c.useFec();
            ctl.LBRR_coded = c.useFec();
            ctl.useDTX = c.useDtx();
            ctl.useCBR = c.useCbr();
            ctl.toMono = sToMono;
            ctl.opusCanSwitch = sCanSwitch;
            ctl.reducedDependency = 0;
            var maxDataBytes = c.useCbr() != 0 ? sBitrate * c.payloadMs() / 8000 : 1275;
            maxDataBytes = Math.min(maxDataBytes, 1275);
            ctl.maxBits = (maxDataBytes - 1) * 8;
            var activity = c.activityMode() == 0 ? -1 : ((packet / 7) % 3 == 0 ? 0 : 1);
            if (c.prefillFirst() != 0 && packet == 0) {
                var pn = c.apiFs() / 100;
                var pre = new short[pn * c.chApi()];
                for (var i = 0; i < pre.length; i++) {
                    pre[i] = (short) (frame[i % frame.length] / 4);
                }
                var zero = new int[1];
                var r = enc.encode(ctl, pre, 0, pn, null, zero, 1, activity);
                if (r != 0) {
                    throw new IllegalStateException("prefill error " + r);
                }
            }
            rc.init(buf, 0, maxDataBytes - 1);
            nBytes[0] = maxDataBytes - 1;
            var ret = enc.encode(ctl, frame, 0, n, rc, nBytes, 0, activity);
            if (ret != 0) {
                throw new IllegalStateException("encode error " + ret + " at packet " + packet);
            }
            rc.encDone();
            var len = nBytes[0];
            writeLe(out, len);
            writeLe(out, rc.finalRange());
            out.write(buf, 0, Math.max(len, 0));
            packet++;
        }
        return out.toByteArray();
    }

    private static void writeLe(ByteArrayOutputStream out, int v) {
        out.write(v);
        out.write(v >>> 8);
        out.write(v >>> 16);
        out.write(v >>> 24);
    }

    static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Synthesizes deterministic speech-like PCM: voiced segments (glottal harmonics shaped by
     * moving formants, with vibrato), unvoiced noise bursts, and near-silence, at the given rate.
     * Uses only integer arithmetic and {@link StrictMath}, so every JVM produces the same samples.
     */
    static short[] speechLike(int fs, int channels, double seconds, long seed) {
        var n = (int) (fs * seconds);
        var out = new short[n * channels];
        var rng = seed;
        var t = 0;
        var segment = 0;
        double[] phases = new double[128];
        var lpState = 0.0;
        var hpState = 0.0;
        while (t < n) {
            rng = rng * 6364136223846793005L + 1442695040888963407L;
            var kind = (int) ((rng >>> 33) % 5);
            rng = rng * 6364136223846793005L + 1442695040888963407L;
            var len = (int) (fs * (0.12 + ((rng >>> 40) % 1000) / 3000.0));
            var f0 = 90 + (segment * 37 % 160);
            var f1 = 300 + (segment * 131 % 600);
            var f2 = 900 + (segment * 211 % 1500);
            var f3 = 2200 + (segment * 97 % 900);
            var amp = 0.08 + (segment % 7) * 0.05;
            for (var i = 0; i < len && t < n; i++, t++) {
                var env = Math.min(1.0, Math.min(i, len - i) / (0.02 * fs));
                double v;
                if (kind <= 2) {
                    var f = f0 * (1 + 0.03 * StrictMath.sin(2 * Math.PI * 5.5 * t / fs)) * (1 + 0.1 * i / (double) len);
                    v = 0;
                    var harmonics = (int) Math.min(phases.length, (fs / 2.0) / f);
                    for (var k = 1; k <= harmonics; k++) {
                        var hf = k * f;
                        phases[k - 1] += 2 * Math.PI * hf / fs;
                        if (phases[k - 1] > 2 * Math.PI) {
                            phases[k - 1] -= 2 * Math.PI;
                        }
                        var a = 1.0 / k * (formant(hf, f1, 90) + 0.7 * formant(hf, f2, 120) + 0.4 * formant(hf, f3, 200));
                        v += a * StrictMath.sin(phases[k - 1]);
                    }
                    v *= amp * 0.35;
                } else if (kind == 3) {
                    rng = rng * 6364136223846793005L + 1442695040888963407L;
                    var white = ((rng >>> 11) * 0x1.0p-53) * 2 - 1;
                    hpState = white - 0.85 * hpState;
                    v = hpState * amp * 0.5;
                } else {
                    rng = rng * 6364136223846793005L + 1442695040888963407L;
                    var white = ((rng >>> 11) * 0x1.0p-53) * 2 - 1;
                    lpState = 0.9 * lpState + 0.1 * white;
                    v = lpState * 0.002;
                }
                v *= env;
                var s = (int) Math.round(v * 32767);
                s = Math.max(-32768, Math.min(32767, s));
                out[t * channels] = (short) s;
                if (channels == 2) {
                    rng = rng * 6364136223846793005L + 1442695040888963407L;
                    var noise = (int) ((rng >>> 58) - 32);
                    var r = (int) Math.round(v * 32767 * (0.6 + 0.3 * StrictMath.sin(2 * Math.PI * 0.3 * t / fs))) + noise;
                    out[t * channels + 1] = (short) Math.max(-32768, Math.min(32767, r));
                }
            }
            segment++;
        }
        return out;
    }

    /**
     * Returns the test signal of the given kind: 0 speech-like, 1 music-like chords, 2 loud noise
     * with clipping, 3 near-silence with sparse clicks, 4 a logarithmic sine sweep, 5 a full-scale
     * 100 Hz square wave, 6 a full-scale DC offset, 7 full-scale alternating samples (Nyquist), 8
     * digital silence.
     */
    static short[] signal(int kind, int fs, int channels, double seconds, long seed) {
        if (kind == 0) {
            return speechLike(fs, channels, seconds, seed);
        }
        var n = (int) (fs * seconds);
        var out = new short[n * channels];
        var rng = seed;
        for (var t = 0; t < n; t++) {
            double v;
            switch (kind) {
                case 1 -> {
                    var chord = (t / (fs / 2)) % 4;
                    double[] roots = {220, 261.63, 196, 246.94};
                    var f = roots[chord];
                    v = 0;
                    for (var k = 1; k <= 6; k++) {
                        v += StrictMath.sin(2 * Math.PI * f * k * t / fs) / (k * k)
                                + 0.6 * StrictMath.sin(2 * Math.PI * f * 1.2599 * k * t / fs) / (k * k)
                                + 0.5 * StrictMath.sin(2 * Math.PI * f * 1.4983 * k * t / fs) / (k * k);
                    }
                    v *= 0.25;
                }
                case 2 -> {
                    rng = rng * 6364136223846793005L + 1442695040888963407L;
                    v = (((rng >>> 11) * 0x1.0p-53) * 2 - 1) * 1.4;
                }
                case 3 -> {
                    rng = rng * 6364136223846793005L + 1442695040888963407L;
                    v = ((rng >>> 40) % 997 == 0) ? 0.9 : (((rng >>> 11) * 0x1.0p-53) - 0.5) * 0.0005;
                }
                case 5 -> v = (t / (fs / 200)) % 2 == 0 ? 1.0 : -1.0;
                case 6 -> v = 1.0;
                case 7 -> v = (t & 1) == 0 ? 1.0 : -1.01;
                case 8 -> v = 0;
                default -> {
                    var start = 50.0;
                    var end = fs / 2.2;
                    var k = StrictMath.log(end / start) / seconds;
                    var phase = 2 * Math.PI * start * (StrictMath.exp(k * t / fs) - 1) / k;
                    v = 0.5 * StrictMath.sin(phase);
                }
            }
            var sample = (int) Math.round(v * 32767);
            sample = Math.max(-32768, Math.min(32767, sample));
            out[t * channels] = (short) sample;
            if (channels == 2) {
                out[t * channels + 1] = (short) Math.max(-32768, Math.min(32767, (int) Math.round(v * 32767 * 0.7)));
            }
        }
        return out;
    }

    private static double formant(double f, double center, double bandwidth) {
        var d = (f - center) / bandwidth;
        return 1.0 / (1.0 + d * d);
    }
}
