package com.github.auties00.smalt.audio.dts.internal;

import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.Scratch;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Measures, through ffmpeg's decoder used as a black box, the gains the 7-bit downmix coefficient codes and the
 * 6-bit downmix scale codes of the XXCH channel set header stand for, and checks them against {@link DownmixCodes}.
 *
 * <p>Each code gets a frame whose core is silent but for what the code acts on: an XXCH channel carrying an
 * impulse whose embedded downmix into the centre channel the decoder undoes (the centre then holds minus the
 * coefficient times the extension channel), or a left channel impulse, mapped with a zero coefficient, that the
 * decoder divides by the downmix scale. Two silent frames without XXCH follow each; the decoder undoes the downmix
 * of the output of the frame that carries it, so the gains are measured on that frame's 256 samples. A code ffmpeg
 * rejects (it then drops the extension) must be one {@link DownmixCodes} rejects.
 */
final class DtsDownmixCodesTest {
    private static final int CORE_MASK = 0x1F;
    private static final int LSR = 0x80;

    @Test
    @DisplayName("the downmix coefficient and scale codes stand for the gains ffmpeg applies")
    void codes() throws IOException {
        Oracle.assume(Oracle.Tool.FFMPEG);
        var report = new StringBuilder();
        var mismatches = 0;
        for (var code = 0; code < 128; code++) {
            var pcm = decode(List.of(frame(true, code, 61, false), silent(), silent()), 6, false);
            Float expected;
            try {
                expected = DownmixCodes.coefficient(code);
            } catch (RuntimeException exception) {
                expected = null;
            }
            if (pcm == null) {
                report.append("coefficient ").append(code).append(" rejected\n");
                mismatches += expected == null ? 0 : 1;
                continue;
            }
            double cx = 0;
            double xx = 0;
            for (var i = 0; i < 256; i++) {
                var c = pcm[i * 6];
                var x = pcm[i * 6 + 5];
                cx += c * x;
                xx += x * x;
            }
            var measured = -cx / xx;
            report.append("coefficient ").append(code).append(' ').append(measured).append('\n');
            if (expected == null || Math.abs(measured - expected) > 1e-4 * Math.abs(expected) + 1e-6) {
                mismatches++;
            }
        }
        for (var code = 0; code < 64; code++) {
            var frames = List.of(frame(false, 0, code, true), silent(), silent());
            var pcm = decode(frames, 6, false);
            Float expected;
            try {
                expected = 1 / DownmixCodes.inverseScale(code);
            } catch (RuntimeException exception) {
                expected = null;
            }
            if (pcm == null) {
                report.append("scale ").append(code).append(" rejected\n");
                mismatches += expected == null ? 0 : 1;
                continue;
            }
            var core = decode(frames, 5, true);
            double fullCore = 0;
            double coreCore = 0;
            for (var i = 0; i < 256; i++) {
                fullCore += pcm[i * 6 + 1] * (double) core[i * 5 + 1];
                coreCore += core[i * 5 + 1] * (double) core[i * 5 + 1];
            }
            var measured = coreCore / fullCore;
            report.append("scale ").append(code).append(' ').append(measured).append('\n');
            if (expected == null || Math.abs(measured - expected) > 1e-4 * expected) {
                mismatches++;
            }
        }
        System.out.print(report.toString().lines().map(line -> "DTS-DMX: " + line + "\n")
                .collect(java.util.stream.Collectors.joining()));
        assertTrue(mismatches == 0, mismatches + " codes differ from ffmpeg:\n" + report);
    }

    private static byte[] silent() {
        var frame = new CoreStreamWriter.Frame(new CoreStreamWriter.Channels(5));
        frame.subframes.add(new CoreStreamWriter.Subframe(1));
        return CoreStreamWriter.write(frame);
    }

    /**
     * Writes a frame whose XXCH channel (left surround in rear) is downmixed into the centre.
     *
     * @param extensionImpulse whether the impulse is in the extension channel rather than in the centre
     * @param coefficientCode the 7-bit coefficient code of the centre
     * @param scaleCode the 6-bit scale code
     * @param scaleOnly whether the coefficient is left out of the map, to measure the scale alone
     */
    private static byte[] frame(boolean extensionImpulse, int coefficientCode, int scaleCode, boolean scaleOnly) {
        var primary = new CoreStreamWriter.Channels(5);
        var frame = new CoreStreamWriter.Frame(primary);
        var subframe = new CoreStreamWriter.Subframe(1);
        if (!extensionImpulse) {
            impulse(subframe, 1);
        }
        frame.subframes.add(subframe);
        var set = new XxchStreamWriter.Set(new CoreStreamWriter.Channels(1));
        set.layoutMask = LSR;
        set.downmixCoefficients = true;
        set.downmixEmbedded = true;
        set.scaleCode = scaleCode;
        set.mapMasks[0] = scaleOnly ? 0x2 : 0x1;
        set.coefficientCodes[0][0] = coefficientCode;
        var extension = new CoreStreamWriter.Subframe(1);
        if (extensionImpulse) {
            impulse(extension, 0);
        }
        set.subframes.add(extension);
        frame.extension = true;
        frame.extensionType = 6;
        frame.extensionData = XxchStreamWriter.write(List.of(set), 9, CORE_MASK, false);
        return CoreStreamWriter.write(frame);
    }

    private static void impulse(CoreStreamWriter.Subframe subframe, int ch) {
        subframe.allocation[ch][3] = 11;
        subframe.scaleIndex[ch][3][0] = 60;
        subframe.codes[ch][3][0] = 100;
    }

    /**
     * Decodes frames with ffmpeg, or returns {@code null} when ffmpeg drops any of them.
     */
    private static float[] decode(List<byte[]> frames, int channels, boolean coreOnly) throws IOException {
        var stream = new ByteArrayOutputStream();
        for (var frame : frames) {
            stream.writeBytes(frame);
        }
        var file = Scratch.directory("dts-downmix").resolve("codes.dts");
        Files.write(file, stream.toByteArray());
        var arguments = new ArrayList<>(List.of("-cpuflags", "0", "-v", "error", "-f", "dts",
                "-channel_order", "coded"));
        if (coreOnly) {
            arguments.addAll(List.of("-core_only", "1"));
        }
        arguments.addAll(List.of("-i", file.toString(), "-f", "f32le", "-"));
        var raw = Oracle.run(Oracle.Tool.FFMPEG, Duration.ofMinutes(2), arguments);
        var samples = new float[raw.length / 4];
        ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(samples);
        if (samples.length < frames.size() * 256 * channels) {
            return null;
        }
        return samples;
    }
}
