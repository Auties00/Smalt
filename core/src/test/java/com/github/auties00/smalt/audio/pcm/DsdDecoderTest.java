package com.github.auties00.smalt.audio.pcm;

import com.github.auties00.smalt.audio.AudioDecoderOptions;
import com.github.auties00.smalt.audio.AudioDecoders;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.audio.IntegerAudioBuffer;
import com.github.auties00.smalt.container.dsd.DsdSamples;
import com.github.auties00.smalt.internal.dsd.DsdDecimator;
import com.github.auties00.smalt.internal.dsd.DsdOverPcmDecoder;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the conversion of DSD to PCM: the decimation filter's response, the decoded samples against a direct
 * double-precision convolution with the same filter (within -120 dB), a modulated sine's amplitude and in-band noise,
 * the independence of the output from the packetization and the bit order, and the decoding of DSD over PCM.
 */
@DisplayName("DSD decoder")
class DsdDecoderTest {
    static AudioTrack track(Codec codec, int rate, int channels) {
        return AudioTrack.builder().codec(codec).timeBase(Rational.timeBaseOf(rate)).sampleRate(rate)
                .layout(ChannelLayout.defaultFor(channels)).bitsPerSample(1).build();
    }

    /**
     * Packs planes most significant bit first into the layout of a codec.
     */
    static byte[] pack(Codec codec, byte[][] planes, int from, int bytes) {
        var channels = planes.length;
        var out = new byte[bytes * channels];
        var reverse = codec == Codec.DSD_LSBF || codec == Codec.DSD_LSBF_PLANAR;
        var planar = codec == Codec.DSD_LSBF_PLANAR || codec == Codec.DSD_MSBF_PLANAR;
        for (var c = 0; c < channels; c++) {
            for (var i = 0; i < bytes; i++) {
                var b = planes[c][from + i];
                if (reverse) {
                    b = (byte) (Integer.reverse(b & 0xFF) >>> 24);
                }
                out[planar ? c * bytes + i : i * channels + c] = b;
            }
        }
        return out;
    }

    static double[][] decode(Codec codec, int rate, byte[][] planes, int packetBytes, AudioDecoderOptions options) {
        var channels = planes.length;
        var track = track(codec, rate, channels);
        var out = new ArrayList<double[]>();
        for (var c = 0; c < channels; c++) {
            out.add(new double[0]);
        }
        var lengths = new int[1];
        try (var decoder = new DsdDecoder(track, options)) {
            var consumer = (java.util.function.Consumer<com.github.auties00.smalt.audio.DoubleAudioBuffer>) block -> {
                for (var c = 0; c < channels; c++) {
                    var grown = java.util.Arrays.copyOf(out.get(c), lengths[0] + block.frames());
                    System.arraycopy(block.plane(c), 0, grown, lengths[0], block.frames());
                    out.set(c, grown);
                }
                lengths[0] += block.frames();
            };
            for (var at = 0; at < planes[0].length; at += packetBytes) {
                var bytes = Math.min(packetBytes, planes[0].length - at);
                decoder.decodeDoubles(new Packet(0, pack(codec, planes, at, bytes), at * 8L, at * 8L, bytes * 8L,
                        true), consumer);
            }
            decoder.flushDoubles(consumer);
        }
        return out.toArray(double[][]::new);
    }

    /**
     * Convolves a DSD stream with the decimation filter directly, at double precision.
     */
    static double[] reference(byte[] plane, int ratio) {
        var taps = DsdDecimator.coefficients(ratio);
        var half = taps.length / 2;
        var bits = plane.length * 8;
        var out = new double[bits / ratio];
        for (var k = 0; k < out.length; k++) {
            var sum = 0.0;
            for (var j = 0; j < taps.length; j++) {
                var t = k * ratio - half + j;
                int bit;
                if (t < 0 || t >= bits) {
                    var phase = Math.floorMod(t, 8);
                    bit = (DsdDecimator.SILENCE >>> (7 - phase)) & 1;
                } else {
                    bit = (plane[t >>> 3] >>> (7 - (t & 7))) & 1;
                }
                sum += bit == 1 ? taps[j] : -taps[j];
            }
            out[k] = sum;
        }
        return out;
    }

    @ParameterizedTest(name = "{0} Hz, ratio {1}")
    @CsvSource({"2822400, 16", "2822400, 8", "5644800, 32", "5644800, 16", "11289600, 64", "3072000, 16"})
    @DisplayName("decodes a random stream within -120 dB of a direct convolution with the filter")
    void matchesConvolution(int rate, int ratio) {
        var planes = DsdSamples.random(2, 3000, rate + ratio);
        var options = AudioDecoderOptions.builder().preferredSampleRate(rate / ratio).build();
        var decoded = decode(Codec.DSD_MSBF, rate, planes, 1000, options);
        for (var c = 0; c < 2; c++) {
            var expected = reference(planes[c], ratio);
            assertEquals(expected.length, decoded[c].length);
            var peak = 0.0;
            for (var i = 0; i < expected.length; i++) {
                peak = Math.max(peak, Math.abs(expected[i] - decoded[c][i]));
            }
            assertTrue(peak < 1e-6, "error " + peak);
        }
    }

    @ParameterizedTest(name = "ratio {0}")
    @CsvSource({"8", "16", "32", "64"})
    @DisplayName("designs a filter flat in its pass band and 120 dB down in its stop band")
    void filterResponse(int ratio) {
        var taps = DsdDecimator.coefficients(ratio);
        var sum = 0.0;
        for (var tap : taps) {
            sum += tap;
        }
        assertEquals(1.0, sum, 1e-12);
        for (var i = 0; i < taps.length / 2; i++) {
            assertEquals(taps[i], taps[taps.length - 1 - i], 1e-15, "linear phase");
        }
        // pass band: up to 0.4 of the output rate, in cycles per DSD sample
        for (var f = 0.0; f <= 0.4 / ratio; f += 0.4 / ratio / 50) {
            var gain = 20 * Math.log10(magnitude(taps, f));
            assertTrue(Math.abs(gain) < 0.001, "pass band gain " + gain + " dB at " + f);
        }
        for (var f = 0.5 / ratio; f <= 0.5; f += 0.5 / ratio / 4) {
            var gain = 20 * Math.log10(magnitude(taps, f));
            assertTrue(gain < -119.5, "stop band gain " + gain + " dB at " + f);
        }
    }

    static double magnitude(double[] taps, double frequency) {
        var real = 0.0;
        var imaginary = 0.0;
        for (var n = 0; n < taps.length; n++) {
            real += taps[n] * StrictMath.cos(2 * StrictMath.PI * frequency * n);
            imaginary -= taps[n] * StrictMath.sin(2 * StrictMath.PI * frequency * n);
        }
        return Math.hypot(real, imaginary);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = Codec.class, names = {"DSD_LSBF", "DSD_MSBF", "DSD_LSBF_PLANAR", "DSD_MSBF_PLANAR"})
    @DisplayName("decodes every layout and packetization to the same samples")
    void layouts(Codec codec) {
        var planes = DsdSamples.random(3, 5000, 7);
        var expected = decode(Codec.DSD_MSBF, 2822400, planes, 4096, AudioDecoderOptions.defaults());
        for (var size : new int[]{1, 13, 4096, 5000}) {
            var decoded = decode(codec, 2822400, planes, size, AudioDecoderOptions.defaults());
            for (var c = 0; c < 3; c++) {
                assertArrayEquals(expected[c], decoded[c], codec + " in packets of " + size);
            }
        }
    }

    @Test
    @DisplayName("decodes a modulated 1 kHz sine at its amplitude with in-band noise 60 dB below it")
    void sine() {
        var rate = 2822400;
        var planes = DsdSamples.modulate(rate, 2822400 / 8 / 4, 0.5, 1000);
        var decoded = decode(Codec.DSD_MSBF, rate, planes, 4096, AudioDecoderOptions.defaults())[0];
        var outputRate = rate / 16;
        assertEquals(planes[0].length * 8 / 16, decoded.length);
        // least squares fit of the sine over the central part, away from the stream's ends
        var from = 2000;
        var to = decoded.length - 2000;
        double ss = 0, sc = 0, cc = 0, ys = 0, yc = 0;
        for (var i = from; i < to; i++) {
            var phase = 2 * Math.PI * 1000 * i / outputRate;
            var s = Math.sin(phase);
            var c = Math.cos(phase);
            ss += s * s;
            sc += s * c;
            cc += c * c;
            ys += decoded[i] * s;
            yc += decoded[i] * c;
        }
        var determinant = ss * cc - sc * sc;
        var a = (ys * cc - yc * sc) / determinant;
        var b = (yc * ss - ys * sc) / determinant;
        assertEquals(0.5, Math.hypot(a, b), 0.005);
        assertTrue(Math.abs(b) < 0.01, "phase offset " + b);
        // the residual's power below 20 kHz, through a Goertzel bank
        var residual = new double[to - from];
        for (var i = from; i < to; i++) {
            var phase = 2 * Math.PI * 1000 * i / outputRate;
            residual[i - from] = decoded[i] - a * Math.sin(phase) - b * Math.cos(phase);
        }
        var noise = 0.0;
        var n = residual.length;
        for (var k = 1; k < (long) n * 20000 / outputRate; k += 7) {
            var re = 0.0;
            var im = 0.0;
            for (var i = 0; i < n; i++) {
                re += residual[i] * Math.cos(2 * Math.PI * k * i / n);
                im -= residual[i] * Math.sin(2 * Math.PI * k * i / n);
            }
            noise += (re * re + im * im) * 7 * 2 / ((double) n * n);
        }
        var snr = 10 * Math.log10(0.125 / noise);
        assertTrue(snr > 60, "in-band SNR " + snr + " dB");
    }

    @Test
    @DisplayName("selects the decimation ratio from the preferred rate")
    void ratios() {
        assertEquals(16, DsdDecimator.ratioFor(2822400, OptionalInt.empty()));
        assertEquals(64, DsdDecimator.ratioFor(2822400, OptionalInt.of(44100)));
        assertEquals(32, DsdDecimator.ratioFor(2822400, OptionalInt.of(48000)));
        assertEquals(8, DsdDecimator.ratioFor(2822400, OptionalInt.of(1_000_000)));
        assertEquals(128, DsdDecimator.ratioFor(5644800, OptionalInt.of(44100)));
        var track = track(Codec.DSD_LSBF_PLANAR, 5644800, 2);
        try (var decoder = AudioDecoders.create(track, AudioDecoderOptions.builder().preferredSampleRate(88200)
                .build())) {
            assertEquals(88200, decoder.sampleRate());
        }
    }

    @Nested
    @DisplayName("DSD over PCM")
    class OverPcm {
        /**
         * Packs DSD into DoP 1.1 samples of 24-bit little-endian PCM: two DSD bytes per channel and frame under an
         * alternating marker.
         */
        byte[] dop(byte[][] planes) {
            var channels = planes.length;
            var frames = planes[0].length / 2;
            var out = new byte[frames * channels * 3];
            for (var i = 0; i < frames; i++) {
                var marker = (i & 1) == 0 ? 0x05 : 0xFA;
                for (var c = 0; c < channels; c++) {
                    var at = (i * channels + c) * 3;
                    out[at] = planes[c][2 * i + 1];
                    out[at + 1] = planes[c][2 * i];
                    out[at + 2] = (byte) marker;
                }
            }
            return out;
        }

        @Test
        @DisplayName("decodes DoP in 24-bit PCM as the DSD it carries when asked")
        void decodes() {
            var planes = DsdSamples.random(2, 8000, 11);
            var expected = decode(Codec.DSD_MSBF, 2822400, planes, 8000, AudioDecoderOptions.defaults());
            var pcm = AudioTrack.builder().codec(Codec.PCM_S24LE).timeBase(Rational.timeBaseOf(176400))
                    .sampleRate(176400).layout(ChannelLayout.STEREO).bitsPerSample(24).build();
            var options = AudioDecoderOptions.builder().dsdOverPcm(true).build();
            try (var decoder = AudioDecoders.create(pcm, options)) {
                assertInstanceOf(DsdOverPcmDecoder.class, decoder);
                assertFalse(decoder.supportsIntegerOutput());
                var data = dop(planes);
                var decoded = new List[]{new ArrayList<Double>(), new ArrayList<Double>()};
                java.util.function.Consumer<com.github.auties00.smalt.audio.DoubleAudioBuffer> sink = block -> {
                    assertEquals(176400, block.sampleRate());
                    for (var c = 0; c < 2; c++) {
                        for (var i = 0; i < block.frames(); i++) {
                            decoded[c].add(block.plane(c)[i]);
                        }
                    }
                };
                decoder.decodeDoubles(new Packet(0, data, 0, 0, data.length / 6, true), sink);
                decoder.flushDoubles(sink);
                assertTrue(((DsdOverPcmDecoder) decoder).isDsd());
                for (var c = 0; c < 2; c++) {
                    var values = new double[decoded[c].size()];
                    for (var i = 0; i < values.length; i++) {
                        values[i] = (Double) decoded[c].get(i);
                    }
                    assertArrayEquals(expected[c], values);
                }
            }
        }

        @Test
        @DisplayName("passes PCM without markers through unchanged")
        void passesPcm() {
            var pcm = AudioTrack.builder().codec(Codec.PCM_S24LE).timeBase(Rational.timeBaseOf(48000))
                    .sampleRate(48000).layout(ChannelLayout.STEREO).bitsPerSample(24).build();
            var data = new byte[600 * 6];
            new java.util.Random(3).nextBytes(data);
            var packet = new Packet(0, data, 0, 0, 600, true);
            float[][] plain = new float[2][];
            try (var decoder = new PcmDecoder(pcm)) {
                decoder.decode(packet, block -> {
                    plain[0] = java.util.Arrays.copyOf(block.plane(0), block.frames());
                    plain[1] = java.util.Arrays.copyOf(block.plane(1), block.frames());
                });
            }
            try (var decoder = AudioDecoders.create(pcm, AudioDecoderOptions.builder().dsdOverPcm(true).build())) {
                decoder.decode(packet, block -> {
                    assertArrayEquals(plain[0], java.util.Arrays.copyOf(block.plane(0), block.frames()));
                    assertArrayEquals(plain[1], java.util.Arrays.copyOf(block.plane(1), block.frames()));
                });
                assertFalse(((DsdOverPcmDecoder) decoder).isDsd());
            }
        }

        @Test
        @DisplayName("recognizes the alternating markers only when every frame and channel carries them")
        void markers() {
            var block = new IntegerAudioBuffer(ChannelLayout.STEREO, 176400, 24, 4);
            block.setFrames(4);
            var markers = new int[]{0x05, 0xFA, 0x05, 0xFA};
            for (var i = 0; i < 4; i++) {
                block.plane(0)[i] = (markers[i] << 16 | 0x1234) << 8 >> 8;
                block.plane(1)[i] = (markers[i] << 16 | 0x4321) << 8 >> 8;
            }
            assertTrue(DsdOverPcmDecoder.carriesDsd(block));
            block.plane(1)[2] = 0x123456;
            assertFalse(DsdOverPcmDecoder.carriesDsd(block));
        }

        @Test
        @DisplayName("leaves decoders alone unless asked")
        void optIn() {
            var pcm = AudioTrack.builder().codec(Codec.PCM_S24LE).timeBase(Rational.timeBaseOf(176400))
                    .sampleRate(176400).layout(ChannelLayout.STEREO).bitsPerSample(24).build();
            try (var decoder = AudioDecoders.create(pcm)) {
                assertInstanceOf(PcmDecoder.class, decoder);
            }
            var sixteen = AudioTrack.builder().codec(Codec.PCM_S16LE).timeBase(Rational.timeBaseOf(44100))
                    .sampleRate(44100).layout(ChannelLayout.STEREO).bitsPerSample(16).build();
            try (var decoder = AudioDecoders.create(sixteen, AudioDecoderOptions.builder().dsdOverPcm(true)
                    .build())) {
                assertInstanceOf(PcmDecoder.class, decoder);
            }
        }
    }
}
