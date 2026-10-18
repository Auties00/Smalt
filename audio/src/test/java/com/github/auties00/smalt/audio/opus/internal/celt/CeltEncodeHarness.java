package com.github.auties00.smalt.audio.opus.internal.celt;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Generates the deterministic test signal of the encoder tests and encodes it frame by frame with
 * {@link CeltEncoder}, exactly as the C harness {@code ref/harness-celt/encceltt.c} does with
 * {@code celt_encode_with_ec}, producing an opus_demo-style {@code .bit} stream (length, final
 * range, payload).
 */
final class CeltEncodeHarness {
    private CeltEncodeHarness() {
    }

    /** One encoder configuration; the field order matches the C harness arguments. */
    record Config(int rate, int channels, int frame, int bitrate, int vbr, int cvbr, int complexity, int prediction,
                  int analysis, int lsbDepth, int startBand, int lfe, int mask, int silk, int phaseInv,
                  int streamChannels, int lossPerc) {
        String args() {
            return rate + " " + channels + " " + frame + " " + bitrate + " " + vbr + " " + cvbr + " " + complexity
                    + " " + prediction + " " + analysis + " " + lsbDepth + " " + startBand + " " + lfe + " " + mask
                    + " " + silk + " " + phaseInv + " " + streamChannels + " " + lossPerc;
        }

        String name() {
            return args().replace(' ', '_');
        }
    }

    /**
     * Returns a 3-second test signal mixing gliding harmonics, a noise burst, clicks, silence and
     * a loud passage, interleaved, computed with {@link StrictMath} so it is identical everywhere.
     */
    static float[] signal(int rate, int channels, double seconds) {
        var n = (int) (rate * seconds);
        var out = new float[n * channels];
        var seed = 0x12345678L;
        var phase = 0.0;
        var phase2 = 0.0;
        for (var i = 0; i < n; i++) {
            var t = (double) i / rate;
            var f0 = 110.0 * StrictMath.pow(4.0, t / seconds);
            phase += 2 * StrictMath.PI * f0 / rate;
            phase2 += 2 * StrictMath.PI * 1234.5 / rate;
            var tone = 0.30 * StrictMath.sin(phase) + 0.15 * StrictMath.sin(2 * phase + 0.3)
                    + 0.08 * StrictMath.sin(3 * phase + 1.1) + 0.04 * StrictMath.sin(7 * phase);
            seed = (seed * 6364136223846793005L + 1442695040888963407L);
            var noise = ((seed >>> 33) / (double) (1L << 31)) - 0.5;
            var sample = tone;
            if (t >= 1.0 && t < 1.3) {
                sample += 0.4 * noise;
            }
            var clickPos = t % 0.37;
            if (clickPos < 32.0 / rate) {
                sample += 0.8 * StrictMath.exp(-clickPos * rate / 6.0);
            }
            if (t >= 2.0 && t < 2.2) {
                sample = 0;
            }
            if (t >= 2.5 && t < 2.7) {
                sample *= 3.5;
            }
            out[i * channels] = (float) sample;
            if (channels == 2) {
                var delayed = i >= 17 ? out[(i - 17) * channels] : 0f;
                out[i * channels + 1] = (float) (0.7 * delayed + 0.1 * StrictMath.sin(phase2) + 0.02 * noise);
            }
        }
        return out;
    }

    static byte[] toLittleEndian(float[] samples) {
        return CeltOnlyHarness.toLittleEndian(samples);
    }

    /** Encodes the signal with the configuration and returns the .bit stream. */
    static byte[] encode(Config config, float[] pcm) {
        var st = new CeltEncoder(config.rate(), config.channels());
        st.setSignaling(0);
        st.setComplexity(config.complexity());
        st.setBitrate(config.bitrate());
        st.setVbr(config.vbr());
        st.setVbrConstraint(config.cvbr());
        st.setPrediction(config.prediction());
        st.setLsbDepth(config.lsbDepth());
        st.setStartBand(config.startBand());
        st.setLfe(config.lfe());
        st.setPhaseInversionDisabled(config.phaseInv() != 0);
        st.setChannels(config.streamChannels());
        st.setPacketLossPerc(config.lossPerc());
        if (config.mask() != 0) {
            var mask = new float[42];
            for (var i = 0; i < 42; i++) {
                mask[i] = (float) ((i * 7) % 13) / 8.0f - 1.0f;
            }
            st.setEnergyMask(mask);
        }
        var out = new ByteArrayOutputStream();
        var buf = new byte[1275];
        var enc = new RangeEncoder();
        var frameSamples = config.frame() * config.channels();
        var info = new AnalysisInfo();
        var silk = new SilkInfo();
        var n = 0;
        for (var off = 0; off + frameSamples <= pcm.length; off += frameSamples) {
            if (config.analysis() != 0) {
                info.clear();
                info.valid = 1;
                info.tonality = (float) (n % 11) / 10.0f;
                info.tonality_slope = (float) ((n % 7) - 3) / 20.0f;
                info.noisiness = (float) (n % 5) / 5.0f;
                info.activity = (float) (n % 9) / 8.0f;
                info.music_prob = (float) (n % 13) / 12.0f;
                info.music_prob_min = info.music_prob / 2;
                info.music_prob_max = info.music_prob;
                info.bandwidth = 12 + (n % 9);
                info.activity_probability = (float) (n % 6) / 5.0f;
                info.max_pitch_ratio = (float) (4 + (n % 5)) / 8.0f;
                for (var i = 0; i < 19; i++) {
                    info.leak_boost[i] = (n * 3 + i * 5) % 64;
                }
                st.setAnalysis(info);
            }
            if (config.silk() != 0) {
                silk.signalType = n % 3;
                silk.offset = (n % 2) != 0 ? 60 : 140;
                st.setSilkInfo(silk);
            }
            java.util.Arrays.fill(buf, (byte) 0);
            enc.init(buf, 0, 1275);
            var ret = st.encodeWithEc(pcm, off, config.frame(), null, 0, 1275, enc);
            if (ret < 0) {
                throw new IllegalStateException("encode error " + ret + " at frame " + n);
            }
            var header = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN);
            header.putInt(ret);
            header.putInt(st.getFinalRange());
            out.writeBytes(header.array());
            out.write(buf, 0, ret);
            n++;
        }
        return out.toByteArray();
    }
}
