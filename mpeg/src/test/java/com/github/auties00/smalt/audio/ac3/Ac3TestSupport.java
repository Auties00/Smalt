package com.github.auties00.smalt.audio.ac3;

import com.github.auties00.smalt.audio.AudioBuffer;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.testkit.Conformance;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Stream splitting, decoding drivers, ffmpeg black-box decodes and the pinned FATE sample streams
 * shared by the AC-3 tests.
 */
final class Ac3TestSupport {
    private static final String FATE = "https://fate-suite.ffmpeg.org/";

    /**
     * The FATE samples: cache name, suite path, SHA-256.
     */
    static final List<Sample> SAMPLES = List.of(
            new Sample("ac3-monsters_inc_2.0_192_small.ac3", "ac3/monsters_inc_2.0_192_small.ac3",
                    "4df071457c23d1a39fe52322892bc9333921ad6dfc8f87ffa0797bdfb5101bae"),
            new Sample("ac3-monsters_inc_5.1_448_small.ac3", "ac3/monsters_inc_5.1_448_small.ac3",
                    "74fefeef070f663378a0864beb064cf88ee1186152f7ac320984af15be2f22db"),
            new Sample("ac3-millers_crossing_4.0.ac3", "ac3/millers_crossing_4.0.ac3",
                    "faa06c7b1ccbc5cf3c7897afb50004dd3d49feaa19d26bdeb03dc9bb4ec20802"),
            new Sample("eac3-csi_miami_5.1_256_spx_small.eac3", "eac3/csi_miami_5.1_256_spx_small.eac3",
                    "84f50317a49a98509bf174ace814b39860e6ab2a1d61bdbd10ada9081f2bb2b3"),
            new Sample("eac3-csi_miami_stereo_128_spx_small.eac3", "eac3/csi_miami_stereo_128_spx_small.eac3",
                    "adc397f12472353237f7dc8139c2223937abc23eff9cd80ac2e3471814a04c3a"),
            new Sample("eac3-matrix2_commentary1_stereo_192_small.eac3", "eac3/matrix2_commentary1_stereo_192_small.eac3",
                    "ea095d557bd6787266f4b7276a2be818a7fbc924997748be89d9ead962a3391c"),
            new Sample("eac3-serenity_english_5.1_1536_small.eac3", "eac3/serenity_english_5.1_1536_small.eac3",
                    "3d81c22610a2602b7799ec5e59c2677e8ab23e03e4abc03c2a16b683aa3d5f0a"),
            new Sample("eac3-the_great_wall_7.1.eac3", "eac3/the_great_wall_7.1.eac3",
                    "d698981ddc1c10e65d234dd6a02ff2b0f4c88787595c9d3e5adf7f097f2c9e50"));

    private Ac3TestSupport() {
    }

    record Sample(String name, String path, String sha256) {
        Path fetch() {
            return Conformance.fetch(name, FATE + path, sha256);
        }

        @Override
        public String toString() {
            return name;
        }
    }

    static byte[] read(Path path) {
        try {
            return Files.readAllBytes(path);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    /**
     * Splits a raw AC-3 or E-AC-3 stream into access units: one AC-3 frame, or an independent
     * E-AC-3 frame of substream 0 with every frame up to the next one.
     */
    static List<byte[]> units(byte[] stream) {
        var units = new ArrayList<byte[]>();
        var position = 0;
        var start = -1;
        while (position + 6 <= stream.length) {
            if (stream[position] != 0x0B || stream[position + 1] != 0x77) {
                position++;
                continue;
            }
            var bsid = (stream[position + 5] & 0xFF) >> 3;
            int length;
            var startsUnit = true;
            if (bsid <= 10) {
                var fscod = (stream[position + 4] & 0xFF) >> 6;
                var code = stream[position + 4] & 0x3F;
                if (fscod == 3 || code >= 38) {
                    position++;
                    continue;
                }
                length = com.github.auties00.smalt.internal.codec.Ac3Configs.FRAME_WORDS[code][fscod] * 2;
            } else {
                length = ((((stream[position + 2] & 0x07) << 8) | (stream[position + 3] & 0xFF)) + 1) * 2;
                var type = (stream[position + 2] & 0xFF) >> 6;
                var id = (stream[position + 2] >> 3) & 7;
                startsUnit = type != 1 && id == 0;
            }
            if (position + length > stream.length) {
                break;
            }
            if (startsUnit && start >= 0) {
                units.add(Arrays.copyOfRange(stream, start, position));
                start = -1;
            }
            if (start < 0) {
                start = position;
            }
            position += length;
        }
        if (start >= 0) {
            units.add(Arrays.copyOfRange(stream, start, position));
        }
        return units;
    }

    /**
     * Returns the samples per channel an access unit codes: 256 per audio block of its first
     * frame.
     */
    static int samples(byte[] unit) {
        if (((unit[5] & 0xFF) >> 3) <= 10) {
            return 1536;
        }
        var fscod = (unit[4] & 0xFF) >> 6;
        return fscod == 3 ? 1536 : 256 * new int[]{1, 2, 3, 6}[(unit[4] >> 4) & 3];
    }

    static AudioTrack track(Codec codec) {
        return AudioTrack.builder().codec(codec).timeBase(Rational.timeBaseOf(48000)).sampleRate(48000).build();
    }

    /**
     * Decodes a raw stream; returns the planes and records the layout of the first buffer.
     */
    static Decoded decode(byte[] stream, Ac3DecoderOptions options) {
        var units = units(stream);
        var codec = ((stream[5] & 0xFF) >> 3) > 10 ? Codec.EAC3 : Codec.AC3;
        var planes = new ArrayList<float[]>();
        var layout = new ChannelLayout[1];
        var total = new int[1];
        try (var decoder = new Ac3Decoder(track(codec), options)) {
            var timestamp = 0L;
            for (var unit : units) {
                decoder.decode(new Packet(0, unit, timestamp, timestamp, samples(unit), true), buffer -> append(planes, layout,
                        total, buffer));
                timestamp += samples(unit);
            }
            return new Decoded(layout[0], planes, total[0], decoder.sampleRate());
        }
    }

    private static void append(List<float[]> planes, ChannelLayout[] layout, int[] total, AudioBuffer buffer) {
        if (layout[0] == null) {
            layout[0] = buffer.layout();
            for (var ch = 0; ch < buffer.channels(); ch++) {
                planes.add(new float[0]);
            }
        }
        for (var ch = 0; ch < Math.min(planes.size(), buffer.channels()); ch++) {
            var old = planes.get(ch);
            var grown = Arrays.copyOf(old, total[0] + buffer.frames());
            System.arraycopy(buffer.plane(ch), 0, grown, total[0], buffer.frames());
            planes.set(ch, grown);
        }
        total[0] += buffer.frames();
    }

    record Decoded(ChannelLayout layout, List<float[]> planes, int frames, int sampleRate) {
        float[] interleaved() {
            var channels = planes.size();
            var out = new float[frames * channels];
            for (var i = 0; i < frames; i++) {
                for (var ch = 0; ch < channels; ch++) {
                    out[i * channels + ch] = planes.get(ch)[i];
                }
            }
            return out;
        }
    }

    /**
     * Decodes a file with ffmpeg to interleaved float samples.
     */
    static float[] ffmpeg(Path input, String... options) {
        var arguments = new ArrayList<>(List.of("-v", "error"));
        arguments.addAll(List.of(options));
        arguments.addAll(List.of("-i", input.toString(), "-map", "0:a:0", "-f", "f32le", "-"));
        var raw = Oracle.run(Oracle.Tool.FFMPEG, Duration.ofMinutes(5), arguments);
        var samples = new float[raw.length / 4];
        ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(samples);
        return samples;
    }

    /**
     * Returns the SNR in dB of {@code actual} against {@code expected} over their common length,
     * and the largest absolute difference.
     */
    static double[] compare(float[] expected, float[] actual) {
        var length = Math.min(expected.length, actual.length);
        var signal = 0.0;
        var noise = 0.0;
        var max = 0.0;
        for (var i = 0; i < length; i++) {
            signal += (double) expected[i] * expected[i];
            var difference = (double) expected[i] - actual[i];
            noise += difference * difference;
            max = Math.max(max, Math.abs(difference));
        }
        var snr = noise == 0 ? Double.POSITIVE_INFINITY : 10 * Math.log10(signal / noise);
        return new double[]{snr, max};
    }

    /**
     * Compares two interleaved decodes in the frequency domain with 1024-point Hann frames and a
     * hop of 512: the SNR in dB of {@code actual} against {@code expected} over the bins below MDCT
     * coefficient {@code below}, and the largest difference in dB between the long-term energies of
     * the 24-bin bands from MDCT coefficient {@code lo} to {@code hi}.
     */
    static double[] spectralAgreement(float[] expected, float[] actual, int channels, int below, int lo, int hi) {
        var fft = new com.github.auties00.smalt.audio.ac3.internal.Fft(1024,
                com.github.auties00.smalt.audio.ac3.internal.Ac3Kernels.scalar());
        var window = new float[1024];
        for (var i = 0; i < 1024; i++) {
            window[i] = (float) (0.5 - 0.5 * Math.cos(2 * Math.PI * i / 1024));
        }
        var xr = new float[1024];
        var xi = new float[1024];
        var yr = new float[1024];
        var yi = new float[1024];
        var er = new float[1024];
        var ei = new float[1024];
        var bands = (hi - lo) * 2 / 24;
        var expectedEnergy = new double[bands];
        var actualEnergy = new double[bands];
        var signal = 0.0;
        var noise = 0.0;
        var frames = Math.min(expected.length, actual.length) / channels;
        for (var ch = 0; ch < channels; ch++) {
            for (var start = 0; start + 1024 <= frames; start += 512) {
                for (var i = 0; i < 1024; i++) {
                    var e = expected[(start + i) * channels + ch];
                    var a = actual[(start + i) * channels + ch];
                    xr[i] = e * window[i];
                    yr[i] = a * window[i];
                    er[i] = (e - a) * window[i];
                    xi[i] = 0;
                    yi[i] = 0;
                    ei[i] = 0;
                }
                fft.forward(xr, xi);
                fft.forward(yr, yi);
                fft.forward(er, ei);
                for (var k = 1; k < 2 * below; k++) {
                    signal += (double) xr[k] * xr[k] + (double) xi[k] * xi[k];
                    noise += (double) er[k] * er[k] + (double) ei[k] * ei[k];
                }
                for (var band = 0; band < bands; band++) {
                    for (var k = 2 * lo + 24 * band; k < 2 * lo + 24 * band + 24; k++) {
                        expectedEnergy[band] += (double) xr[k] * xr[k] + (double) xi[k] * xi[k];
                        actualEnergy[band] += (double) yr[k] * yr[k] + (double) yi[k] * yi[k];
                    }
                }
            }
        }
        var distance = 0.0;
        for (var band = 0; band < bands; band++) {
            if (expectedEnergy[band] > 1e-9) {
                distance = Math.max(distance, Math.abs(10 * Math.log10(actualEnergy[band] / expectedEnergy[band])));
            }
        }
        return new double[]{noise == 0 ? Double.POSITIVE_INFINITY : 10 * Math.log10(signal / noise), distance};
    }

    /**
     * Returns per-channel SNRs of interleaved signals.
     */
    static double[] channelSnr(float[] expected, float[] actual, int channels) {
        var result = new double[channels];
        var length = Math.min(expected.length, actual.length) / channels;
        for (var ch = 0; ch < channels; ch++) {
            var signal = 0.0;
            var noise = 0.0;
            for (var i = 0; i < length; i++) {
                var e = expected[i * channels + ch];
                var d = (double) e - actual[i * channels + ch];
                signal += (double) e * e;
                noise += d * d;
            }
            result[ch] = noise == 0 ? Double.POSITIVE_INFINITY : 10 * Math.log10(signal / Math.max(noise, 1e-30));
        }
        return result;
    }
}
