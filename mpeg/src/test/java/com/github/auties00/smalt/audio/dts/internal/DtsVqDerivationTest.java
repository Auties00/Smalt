package com.github.auties00.smalt.audio.dts.internal;

import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.Scratch;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Re-derives the two vector code books of the DTS core (ETSI TS 102 114 D.10), which the specification does not
 * print, from ffmpeg's decoder used as a black box, and checks them against {@code dts-vq.bin.gz}.
 *
 * <p>Synthetic core frames select every code book entry: an ADPCM subband carries a single quantized impulse, so its
 * first five reconstructed samples give the four prediction coefficients; a high frequency VQ subband gives its 32
 * vector elements next to a plain subband that calibrates the output scale. The subband samples are recovered from
 * the PCM by least squares through this package's own synthesis filter bank, every frame followed by silence long
 * enough to clear the filter history. Every recovered value must land on its integer grid (coefficients times 2^13,
 * elements times 2^4), which checks the model as well as the measurement. With {@code -Dsmalt.test.updateDtsVq=true}
 * the test writes the resource instead of comparing.
 */
final class DtsVqDerivationTest {
    private static final Path RESOURCE = Path.of("src/main/resources/com/github/auties00/smalt/audio/dts/internal/"
            + "dts-vq.bin.gz");

    /**
     * The step size of ABITS 11 times 2^22.
     */
    private static final double STEP_11 = DtsCoreTables.STEP_LOSSY[11] / (double) (1 << 22);

    /**
     * The ratio of ffmpeg's output to this package's synthesis, measured on the first impulse.
     */
    private static double gain;

    @Test
    @DisplayName("ffmpeg's decoder reproduces every entry of the two vector code books")
    void derive() throws IOException {
        Oracle.assume(Oracle.Tool.FFMPEG);
        var adpcm = deriveAdpcm();
        var highFrequency = deriveHighFrequency();
        System.out.printf("DTS-GAIN: %.9f%n", gain);
        var bytes = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(new GZIPOutputStream(bytes))) {
            for (var value : adpcm) {
                out.writeShort(value);
            }
            out.write(highFrequency);
        }
        if (Boolean.getBoolean("smalt.test.updateDtsVq")) {
            Files.createDirectories(RESOURCE.getParent());
            Files.write(RESOURCE, bytes.toByteArray());
            return;
        }
        var resource = DtsVqDerivationTest.class.getResourceAsStream("dts-vq.bin.gz");
        if (resource == null) {
            var encoded = java.util.Base64.getEncoder().encodeToString(bytes.toByteArray());
            for (var i = 0; i < encoded.length(); i += 100) {
                System.out.println("DTS-VQ-BASE64:" + encoded.substring(i, Math.min(encoded.length(), i + 100)));
            }
            fail("dts-vq.bin.gz is missing; the derived code books are printed above");
        }
        try (InputStream in = new GZIPInputStream(resource)) {
            var stored = in.readAllBytes();
            var expected = new ByteArrayOutputStream();
            try (var out = new DataOutputStream(expected)) {
                for (var value : adpcm) {
                    out.writeShort(value);
                }
                out.write(highFrequency);
            }
            assertArrayEquals(expected.toByteArray(), stored);
        }
    }

    /**
     * Derives the 4096 ADPCM coefficient vectors, 160 per frame (5 channels of 32 subbands).
     */
    private static short[] deriveAdpcm() throws IOException {
        var result = new short[4096 * 4];
        var frames = new ArrayList<byte[]>();
        var scaleIndex = 100;
        var code = 40;
        var amplitude = code * STEP_11 * DtsCoreTables.RMS7[scaleIndex];
        var groups = (4096 + 159) / 160;
        for (var group = 0; group < groups; group++) {
            var channels = new CoreStreamWriter.Channels(5);
            var subframe = new CoreStreamWriter.Subframe(1);
            for (var ch = 0; ch < 5; ch++) {
                for (var n = 0; n < 32; n++) {
                    var vector = (group * 160 + ch * 32 + n) % 4096;
                    subframe.predictionMode[ch][n] = 1;
                    subframe.predictionVector[ch][n] = vector;
                    subframe.allocation[ch][n] = 11;
                    subframe.scaleIndex[ch][n][0] = scaleIndex;
                    subframe.codes[ch][n][0] = code;
                }
            }
            frames.add(frame(channels, subframe));
            frames.add(silentFrame(1));
            frames.add(silentFrame(1));
        }
        var pcm = decode(frames, 5);
        var solver = new Solver(8, 24);
        var perFrame = 256;
        for (var group = 0; group < groups; group++) {
            for (var ch = 0; ch < 5; ch++) {
                var window = new double[24 * 32];
                for (var i = 0; i < window.length; i++) {
                    window[i] = pcm[(group * 3 * perFrame + i) * 5 + ch];
                }
                var samples = solver.solve(window);
                for (var n = 0; n < 32; n++) {
                    var vector = group * 160 + ch * 32 + n;
                    if (vector >= 4096) {
                        continue;
                    }
                    var s = new double[8];
                    for (var m = 0; m < 8; m++) {
                        s[m] = samples[n * 8 + m];
                    }
                    var s0 = s[0];
                    if (vector == 0) {
                        gain = s0 / amplitude;
                    }
                    assertEquals(gain, s0 / amplitude, 1e-4 * gain, "impulse of vector " + vector);
                    for (var k = 0; k < 4; k++) {
                        var residual = s[k + 1];
                        for (var j = 0; j < k; j++) {
                            residual -= result[vector * 4 + j] / 8192.0 * s[k - j];
                        }
                        var scaled = residual / s0 * 8192;
                        var rounded = Math.round(scaled);
                        if (Math.abs(scaled - rounded) > 0.05) {
                            fail("ADPCM vector " + vector + " coefficient " + k + " off its grid: " + scaled);
                        }
                        result[vector * 4 + k] = (short) rounded;
                    }
                    var predicted = s[4];
                    for (var m = 5; m < 8; m++) {
                        predicted = 0;
                        for (var k = 0; k < 4; k++) {
                            predicted += result[vector * 4 + k] / 8192.0 * s[m - 1 - k];
                        }
                        if (Math.abs(s[m]) >= gain * ((1 << 23) - 16)) {
                            break;
                        }
                        assertEquals(predicted, s[m], gain * 4 + 1e-4 * Math.abs(s[m]),
                                "ADPCM vector " + vector + " predicts sample " + m);
                    }
                }
            }
        }
        return result;
    }

    /**
     * Derives the 1024 high frequency vectors, 155 per frame (5 channels of 31 subbands), subband 0 of each channel
     * calibrating the output scale.
     */
    private static byte[] deriveHighFrequency() throws IOException {
        var result = new byte[1024 * 32];
        var frames = new ArrayList<byte[]>();
        var calibrationScale = 40;
        var vectorScale = 60;
        var code = 50;
        var amplitude = code * STEP_11 * DtsCoreTables.RMS7[calibrationScale];
        var groups = (1024 + 154) / 155;
        for (var group = 0; group < groups; group++) {
            var channels = new CoreStreamWriter.Channels(5);
            var subframe = new CoreStreamWriter.Subframe(4);
            for (var ch = 0; ch < 5; ch++) {
                channels.vqStart[ch] = 1;
                subframe.allocation[ch][0] = 11;
                subframe.scaleIndex[ch][0][0] = calibrationScale;
                subframe.codes[ch][0][0] = code;
                for (var n = 1; n < 32; n++) {
                    subframe.scaleIndex[ch][n][0] = vectorScale;
                    subframe.vector[ch][n] = (group * 155 + ch * 31 + n - 1) % 1024;
                }
            }
            frames.add(frame(channels, subframe));
            frames.add(silentFrame(4));
        }
        var pcm = decode(frames, 5);
        var solver = new Solver(32, 64);
        var perFrame = 1024;
        for (var group = 0; group < groups; group++) {
            for (var ch = 0; ch < 5; ch++) {
                var window = new double[64 * 32];
                for (var i = 0; i < window.length; i++) {
                    window[i] = pcm[(group * 2 * perFrame + i) * 5 + ch];
                }
                var samples = solver.solve(window);
                assertEquals(gain, samples[0] / amplitude, 1e-4 * gain, "calibration of group " + group);
                var unit = gain * DtsCoreTables.RMS7[vectorScale] / 16.0;
                for (var n = 1; n < 32; n++) {
                    var vector = group * 155 + ch * 31 + n - 1;
                    if (vector >= 1024) {
                        continue;
                    }
                    for (var m = 0; m < 32; m++) {
                        var scaled = samples[n * 32 + m] / unit;
                        var rounded = Math.round(scaled);
                        if (Math.abs(scaled - rounded) > 0.02 || rounded < -128 || rounded > 127) {
                            fail("high frequency vector " + vector + " element " + m + " off its grid: " + scaled);
                        }
                        result[vector * 32 + m] = (byte) rounded;
                    }
                }
            }
        }
        return result;
    }

    private static byte[] frame(CoreStreamWriter.Channels channels, CoreStreamWriter.Subframe subframe) {
        var frame = new CoreStreamWriter.Frame(channels);
        frame.perfect = false;
        frame.subframes.add(subframe);
        return CoreStreamWriter.write(frame);
    }

    private static byte[] silentFrame(int subsubframes) {
        var channels = new CoreStreamWriter.Channels(5);
        var frame = new CoreStreamWriter.Frame(channels);
        frame.perfect = false;
        frame.subframes.add(new CoreStreamWriter.Subframe(subsubframes));
        return CoreStreamWriter.write(frame);
    }

    /**
     * Decodes frames with ffmpeg to interleaved floats in coding channel order.
     */
    private static float[] decode(List<byte[]> frames, int channels) throws IOException {
        var stream = new ByteArrayOutputStream();
        for (var frame : frames) {
            stream.writeBytes(frame);
        }
        var file = Scratch.directory("dts-vq").resolve("frames-" + frames.size() + ".dts");
        Files.write(file, stream.toByteArray());
        var raw = Oracle.run(Oracle.Tool.FFMPEG, Duration.ofMinutes(2), List.of("-cpuflags", "0", "-v", "error", "-f", "dts",
                "-channel_order", "coded", "-i", file.toString(), "-f", "f32le", "-"));
        var samples = new float[raw.length / 4];
        ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(samples);
        var total = 0;
        for (var frame : frames) {
            total += ((frame[4] & 0x01) << 6 | (frame[5] & 0xFC) >> 2) + 1;
        }
        assertEquals(total * 32 * channels, samples.length, "ffmpeg output length");
        assertTrue(samples.length > 0);
        return samples;
    }

    /**
     * Least squares recovery of the subband samples of one channel from its PCM, through the 32-band synthesis
     * filter bank: the unknowns are {@code active} samples of each of the 32 subbands, the PCM spans {@code window}
     * subband samples.
     */
    private static final class Solver {
        private final int active;
        private final int window;
        private final double[][] columns;
        private final double[][] cholesky;

        Solver(int active, int window) {
            this.active = active;
            this.window = window;
            var unknowns = 32 * active;
            columns = new double[unknowns][];
            for (var n = 0; n < 32; n++) {
                for (var m = 0; m < active; m++) {
                    columns[n * active + m] = response(n, m);
                }
            }
            var gram = new double[unknowns][unknowns];
            for (var i = 0; i < unknowns; i++) {
                for (var j = 0; j <= i; j++) {
                    var sum = 0.0;
                    var a = columns[i];
                    var b = columns[j];
                    for (var k = 0; k < a.length; k++) {
                        sum += a[k] * b[k];
                    }
                    gram[i][j] = sum;
                    gram[j][i] = sum;
                }
            }
            cholesky = decompose(gram);
        }

        private double[] response(int band, int sample) {
            var qmf = new Qmf32();
            var bands = new float[32][SubbandBuffer.HISTORY + window];
            bands[band][SubbandBuffer.HISTORY + sample] = 1;
            var out = new float[32 * window];
            qmf.synthesize(bands, 0, window, false, out, 0, 1.0f / (1 << 23));
            var column = new double[out.length];
            for (var i = 0; i < out.length; i++) {
                column[i] = out[i];
            }
            return column;
        }

        double[] solve(double[] pcm) {
            var unknowns = columns.length;
            var rhs = new double[unknowns];
            for (var i = 0; i < unknowns; i++) {
                var sum = 0.0;
                var a = columns[i];
                for (var k = 0; k < a.length; k++) {
                    sum += a[k] * pcm[k];
                }
                rhs[i] = sum;
            }
            for (var i = 0; i < unknowns; i++) {
                var sum = rhs[i];
                for (var k = 0; k < i; k++) {
                    sum -= cholesky[i][k] * rhs[k];
                }
                rhs[i] = sum / cholesky[i][i];
            }
            for (var i = unknowns - 1; i >= 0; i--) {
                var sum = rhs[i];
                for (var k = i + 1; k < unknowns; k++) {
                    sum -= cholesky[k][i] * rhs[k];
                }
                rhs[i] = sum / cholesky[i][i];
            }
            return rhs;
        }

        private static double[][] decompose(double[][] a) {
            var n = a.length;
            var l = new double[n][n];
            for (var i = 0; i < n; i++) {
                for (var j = 0; j <= i; j++) {
                    var sum = a[i][j];
                    for (var k = 0; k < j; k++) {
                        sum -= l[i][k] * l[j][k];
                    }
                    if (i == j) {
                        l[i][i] = Math.sqrt(sum);
                    } else {
                        l[i][j] = sum / l[j][j];
                    }
                }
            }
            return l;
        }
    }
}
