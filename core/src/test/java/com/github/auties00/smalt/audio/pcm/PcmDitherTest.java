package com.github.auties00.smalt.audio.pcm;

import com.github.auties00.smalt.audio.AudioBuffer;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.audio.DoubleAudioBuffer;
import com.github.auties00.smalt.audio.IntegerAudioBuffer;
import com.github.auties00.smalt.track.Codec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers the dither of {@link PcmEncoder}: on a -90 dBFS sine, triangular dither makes the error's
 * mean and power independent of the signal and removes the harmonics that plain rounding leaves;
 * the high-pass and noise-shaped variants move the noise out of the low band as designed; the output is
 * deterministic and independent of the block split; formats without dither are unchanged.
 */
@DisplayName("PcmEncoder dither")
class PcmDitherTest {
    private static final int RATE = 44_100;
    private static final int FRAMES = 1 << 18;
    private static final double SINE = 1000;

    /**
     * A 1 kHz sine at -90 dBFS: an amplitude of about one 16-bit LSB.
     */
    private static float[] quietSine() {
        var amplitude = Math.pow(10, -90 / 20.0);
        var plane = new float[FRAMES];
        for (var i = 0; i < FRAMES; i++) {
            plane[i] = (float) (amplitude * Math.sin(2 * Math.PI * SINE * i / RATE));
        }
        return plane;
    }

    private static PcmEncoder encoder(Codec codec, PcmDither dither) {
        return new PcmEncoder(PcmEncoderOptions.builder().codec(codec).format(RATE, ChannelLayout.MONO)
                .dither(dither).build());
    }

    /** Encodes mono float samples in blocks of the given sizes, cycled, and returns the bytes. */
    private static byte[] encode(PcmEncoder encoder, float[] samples, int... blocks) {
        var bytes = new ByteArrayOutputStream();
        var offset = 0;
        var index = 0;
        while (offset < samples.length) {
            var count = Math.min(blocks[index++ % blocks.length], samples.length - offset);
            var buffer = new AudioBuffer(ChannelLayout.MONO, RATE, new float[][]{Arrays.copyOfRange(samples, offset,
                    offset + count)}, count);
            encoder.encode(buffer, packet -> bytes.writeBytes(packet.data()));
            offset += count;
        }
        encoder.flush(packet -> bytes.writeBytes(packet.data()));
        return bytes.toByteArray();
    }

    private static int[] s16(byte[] bytes) {
        var codes = new int[bytes.length / 2];
        for (var i = 0; i < codes.length; i++) {
            codes[i] = (short) ((bytes[2 * i] & 0xFF) | bytes[2 * i + 1] << 8);
        }
        return codes;
    }

    /** Returns the requantization error in 16-bit LSB: code minus the scaled input. */
    private static double[] error(float[] samples, PcmDither dither) {
        var codes = s16(encode(encoder(Codec.PCM_S16LE, dither), samples, 4096));
        var error = new double[codes.length];
        for (var i = 0; i < codes.length; i++) {
            error[i] = codes[i] - samples[i] * 32768.0;
        }
        return error;
    }

    private static double mean(double[] values) {
        return Arrays.stream(values).average().orElseThrow();
    }

    private static double variance(double[] values) {
        var mean = mean(values);
        return Arrays.stream(values).map(value -> (value - mean) * (value - mean)).average().orElseThrow();
    }

    /** Returns the amplitude of the component of {@code values} at {@code frequency}, least squares. */
    private static double amplitude(double[] values, double frequency) {
        double s = 0;
        double c = 0;
        for (var i = 0; i < values.length; i++) {
            s += values[i] * Math.sin(2 * Math.PI * frequency * i / RATE);
            c += values[i] * Math.cos(2 * Math.PI * frequency * i / RATE);
        }
        return 2 * Math.hypot(s, c) / values.length;
    }

    /** Returns the mean power of {@code values} per FFT bin between two frequencies, Hann-windowed. */
    private static double bandPower(double[] values, double low, double high) {
        var size = 4096;
        var window = new double[size];
        for (var i = 0; i < size; i++) {
            window[i] = 0.5 - 0.5 * Math.cos(2 * Math.PI * i / size);
        }
        var first = (int) Math.ceil(low * size / RATE);
        var last = (int) Math.floor(high * size / RATE);
        double sum = 0;
        var count = 0;
        for (var start = 0; start + size <= values.length; start += size) {
            var re = new double[size];
            var im = new double[size];
            for (var i = 0; i < size; i++) {
                re[i] = values[start + i] * window[i];
            }
            fft(re, im);
            for (var bin = first; bin <= last; bin++) {
                sum += re[bin] * re[bin] + im[bin] * im[bin];
                count++;
            }
        }
        return sum / count;
    }

    private static void fft(double[] re, double[] im) {
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
            var angle = -2 * Math.PI / length;
            for (var i = 0; i < n; i += length) {
                for (var k = 0; k < length / 2; k++) {
                    var wr = Math.cos(angle * k);
                    var wi = Math.sin(angle * k);
                    var ur = re[i + k];
                    var ui = im[i + k];
                    var vr = re[i + k + length / 2] * wr - im[i + k + length / 2] * wi;
                    var vi = re[i + k + length / 2] * wi + im[i + k + length / 2] * wr;
                    re[i + k] = ur + vr;
                    im[i + k] = ui + vi;
                    re[i + k + length / 2] = ur - vr;
                    im[i + k + length / 2] = ui - vi;
                }
            }
        }
    }

    /**
     * Returns the spread (largest minus smallest) of the error power over eight classes of the input's
     * fractional position between two codes: zero when the error is independent of the signal.
     */
    private static double conditionalPowerSpread(float[] samples, double[] error) {
        var sums = new double[8];
        var counts = new int[8];
        for (var i = 0; i < samples.length; i++) {
            var scaled = samples[i] * 32768.0;
            var bin = (int) ((scaled - Math.floor(scaled)) * 8);
            sums[bin] += error[i] * error[i];
            counts[bin]++;
        }
        var low = Double.POSITIVE_INFINITY;
        var high = 0.0;
        for (var bin = 0; bin < 8; bin++) {
            var power = sums[bin] / counts[bin];
            low = Math.min(low, power);
            high = Math.max(high, power);
        }
        return high - low;
    }

    @Nested
    @DisplayName("on a -90 dBFS sine")
    class QuietSine {
        private final float[] sine = quietSine();

        @Test
        @DisplayName("triangular dither gives an error of power 1/4 LSB^2 independent of the signal")
        void triangular() {
            var error = error(sine, PcmDither.TRIANGULAR);
            assertEquals(0, mean(error), 0.01);
            assertEquals(0.25, variance(error), 0.01);
            assertTrue(conditionalPowerSpread(sine, error) < 0.03, "spread " + conditionalPowerSpread(sine, error));
            var none = error(sine, PcmDither.NONE);
            assertTrue(conditionalPowerSpread(sine, none) > 0.1, "rounding error follows the signal");
        }

        @Test
        @DisplayName("triangular dither keeps the fundamental and removes the odd harmonics of rounding")
        void harmonics() {
            var dithered = Arrays.stream(s16(encode(encoder(Codec.PCM_S16LE, PcmDither.TRIANGULAR), sine, 4096)))
                    .asDoubleStream().toArray();
            var rounded = Arrays.stream(s16(encode(encoder(Codec.PCM_S16LE, PcmDither.NONE), sine, 4096)))
                    .asDoubleStream().toArray();
            var expected = Math.pow(10, -90 / 20.0) * 32768;
            assertEquals(expected, amplitude(dithered, SINE), 0.01);
            // rounding a 1.04 LSB sine gives a three-level wave whose fifth and seventh harmonics are about
            // 0.2 LSB (its third is near a zero of the series)
            for (var harmonic = 5; harmonic <= 7; harmonic += 2) {
                assertTrue(amplitude(rounded, SINE * harmonic) > 0.1, "rounding harmonic " + harmonic);
            }
            for (var harmonic = 3; harmonic <= 7; harmonic += 2) {
                assertTrue(amplitude(dithered, SINE * harmonic) < 0.01, "dithered harmonic " + harmonic);
            }
        }

        @Test
        @DisplayName("high-pass triangular dither has the same power with less of it at low frequencies")
        void highPass() {
            var white = error(sine, PcmDither.TRIANGULAR);
            var error = error(sine, PcmDither.TRIANGULAR_HIGH_PASS);
            assertEquals(0.25, variance(error), 0.01);
            assertEquals(0, mean(error), 0.01);
            var lowRatio = 10 * Math.log10(bandPower(error, 20, 3000) / bandPower(white, 20, 3000));
            var highRatio = 10 * Math.log10(bandPower(error, 16000, 22000) / bandPower(white, 16000, 22000));
            assertTrue(lowRatio < -3, "low band " + lowRatio + " dB");
            assertTrue(highRatio > 1.0, "high band " + highRatio + " dB");
        }

        @Test
        @DisplayName("noise shaping lowers the noise by at least 12 dB from 2 to 5 kHz and raises it near Nyquist")
        void noiseShaped() {
            var white = error(sine, PcmDither.TRIANGULAR);
            var error = error(sine, PcmDither.NOISE_SHAPED);
            assertEquals(0, mean(error), 0.02);
            var mid = 10 * Math.log10(bandPower(error, 2000, 5000) / bandPower(white, 2000, 5000));
            var top = 10 * Math.log10(bandPower(error, 19000, 22000) / bandPower(white, 19000, 22000));
            assertTrue(mid < -12, "2-5 kHz " + mid + " dB");
            assertTrue(top > 12, "19-22 kHz " + top + " dB");
            assertTrue(variance(error) > 0.25);
        }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = PcmDither.class, names = "NONE", mode = EnumSource.Mode.EXCLUDE)
    @DisplayName("is deterministic and independent of the block split")
    void deterministic(PcmDither dither) {
        var sine = quietSine();
        var whole = encode(encoder(Codec.PCM_S16LE, dither), sine, FRAMES);
        assertArrayEquals(whole, encode(encoder(Codec.PCM_S16LE, dither), sine, 1000, 1, 37));
        assertArrayEquals(whole, encode(encoder(Codec.PCM_S16LE, dither), sine, 4096));
    }

    @Test
    @DisplayName("dithers 8 and 24-bit codes in their own LSB")
    void otherSizes() {
        var random = new java.util.SplittableRandom(2);
        var samples = new float[50_000];
        for (var i = 0; i < samples.length; i++) {
            samples[i] = (float) random.nextDouble(-0.5, 0.5);
        }
        var u8 = encode(encoder(Codec.PCM_U8, PcmDither.TRIANGULAR), samples, 4096);
        var errors8 = new double[samples.length];
        for (var i = 0; i < samples.length; i++) {
            errors8[i] = ((u8[i] & 0xFF) - 128) - samples[i] * 128.0;
        }
        assertEquals(0.25, variance(errors8), 0.015);
        var s24 = encode(encoder(Codec.PCM_S24BE, PcmDither.TRIANGULAR), samples, 4096);
        var errors24 = new double[samples.length];
        for (var i = 0; i < samples.length; i++) {
            var code = (s24[3 * i] << 16) | (s24[3 * i + 1] & 0xFF) << 8 | s24[3 * i + 2] & 0xFF;
            errors24[i] = code - samples[i] * 8388608.0;
        }
        // a float holds 24 significant bits, so the 24-bit grid is coarser than the input only near zero
        assertEquals(0.25, variance(errors24), 0.03);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = Codec.class, names = {"PCM_ALAW", "PCM_MULAW", "PCM_S32LE", "PCM_F32LE", "PCM_F64BE", "PCM_S64LE"})
    @DisplayName("leaves G.711, 32 and 64-bit integer and float formats undithered")
    void undithered(Codec codec) {
        var sine = Arrays.copyOf(quietSine(), 10_000);
        for (var i = 0; i < sine.length; i++) {
            sine[i] *= 3000;
        }
        assertArrayEquals(encode(encoder(codec, PcmDither.NONE), sine, 4096),
                encode(encoder(codec, PcmDither.NOISE_SHAPED), sine, 4096));
    }

    @Test
    @DisplayName("recovers from clipped full-scale input without the noise-shaping loop running away")
    void clipping() {
        var samples = new float[40_000];
        for (var i = 0; i < 20_000; i++) {
            samples[i] = (i / 50) % 2 == 0 ? 1.5f : -1.5f;
        }
        var sine = quietSine();
        System.arraycopy(sine, 0, samples, 20_000, 20_000);
        var codes = s16(encode(encoder(Codec.PCM_S16LE, PcmDither.NOISE_SHAPED), samples, 4096));
        for (var i = 0; i < 20_000; i++) {
            assertEquals(samples[i] > 0 ? 32767 : -32768, codes[i], 40, "clipped sample " + i);
        }
        for (var i = 20_100; i < 40_000; i++) {
            assertTrue(Math.abs(codes[i] - samples[i] * 32768.0) < 15, "sample " + i);
        }
    }

    @Test
    @DisplayName("dithers integer input wider than the format and double input like float input")
    void integerAndDouble() {
        var random = new java.util.SplittableRandom(4);
        var frames = 60_000;
        var integers = new int[frames];
        var doubles = new double[frames];
        for (var i = 0; i < frames; i++) {
            integers[i] = random.nextInt(-(1 << 20), 1 << 20);
            doubles[i] = integers[i] * 0x1p-23;
        }
        var bytes = new ByteArrayOutputStream();
        var encoder = encoder(Codec.PCM_S16LE, PcmDither.TRIANGULAR);
        encoder.encode(new IntegerAudioBuffer(ChannelLayout.MONO, RATE, 24, new int[][]{integers}, frames),
                packet -> bytes.writeBytes(packet.data()));
        encoder.flush(packet -> bytes.writeBytes(packet.data()));
        var fromIntegers = s16(bytes.toByteArray());
        var errors = new double[frames];
        for (var i = 0; i < frames; i++) {
            errors[i] = fromIntegers[i] - integers[i] / 256.0;
        }
        assertEquals(0.25, variance(errors), 0.01);
        var doubleBytes = new ByteArrayOutputStream();
        var other = encoder(Codec.PCM_S16LE, PcmDither.TRIANGULAR);
        other.encode(new DoubleAudioBuffer(ChannelLayout.MONO, RATE, new double[][]{doubles}, frames),
                packet -> doubleBytes.writeBytes(packet.data()));
        other.flush(packet -> doubleBytes.writeBytes(packet.data()));
        assertArrayEquals(bytes.toByteArray(), doubleBytes.toByteArray(), "the same scaled values and dither");
        // integers that fit the format are coded exactly
        var exact = new ByteArrayOutputStream();
        var narrow = encoder(Codec.PCM_S16LE, PcmDither.TRIANGULAR);
        var small = new int[]{-32768, -1, 0, 1, 32767};
        narrow.encode(new IntegerAudioBuffer(ChannelLayout.MONO, RATE, 16, new int[][]{small}, small.length),
                packet -> exact.writeBytes(packet.data()));
        narrow.flush(packet -> exact.writeBytes(packet.data()));
        assertArrayEquals(small, s16(exact.toByteArray()));
    }

    @Test
    @DisplayName("is part of the options' identity and defaults to none")
    void options() {
        var options = PcmEncoderOptions.defaults();
        assertEquals(PcmDither.NONE, options.dither());
        var dithered = options.toBuilder().dither(PcmDither.TRIANGULAR).build();
        assertNotEquals(options, dithered);
        assertEquals(dithered, dithered.toBuilder().build());
        assertTrue(dithered.toString().contains("TRIANGULAR"));
        assertThrows(NullPointerException.class, () -> options.toBuilder().dither(null));
    }
}
