package com.github.auties00.smalt.image.jpeg.internal;

import com.github.auties00.smalt.image.jpeg.JpegLsEncoder;
import com.github.auties00.smalt.image.jpeg.JpegLsEncoderOptions;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.Tolerance;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Compares the JPEG-LS codec with ffmpeg's as a black box: ffmpeg decodes the encoder's lossless and near-lossless
 * files to the samples Smalt decodes from them, and Smalt decodes ffmpeg's line-interleaved encodings (its
 * {@code pred} option, 0 to 2, is the near-lossless bound) to the samples ffmpeg decodes from them, bit for bit.
 * ffmpeg's decoder lacks sample-interleaved scans, which the CharLS and T.87 vectors cover instead.
 */
@DisplayName("JPEG-LS against ffmpeg")
class JpegLsDifferentialTest {
    private static final Duration TIMEOUT = Duration.ofMinutes(2);

    static Stream<Arguments> settings() {
        var cases = new ArrayList<Arguments>();
        for (var near = 0; near <= 3; near++) {
            cases.add(Arguments.of("gray", 1, 8, near, JpegLsEncoderOptions.Interleave.NONE));
            cases.add(Arguments.of("rgb24", 3, 8, near, JpegLsEncoderOptions.Interleave.LINE));
            cases.add(Arguments.of("rgb24", 3, 8, near, JpegLsEncoderOptions.Interleave.NONE));
            cases.add(Arguments.of("gray16le", 1, 16, near, JpegLsEncoderOptions.Interleave.NONE));
        }
        return cases.stream();
    }

    private static byte[] raw(int[][] planes, int bits) {
        var out = new ByteArrayOutputStream();
        for (var i = 0; i < planes[0].length; i++) {
            for (var plane : planes) {
                out.write(plane[i]);
                if (bits > 8) {
                    out.write(plane[i] >>> 8);
                }
            }
        }
        return out.toByteArray();
    }

    private static int[][] planes(byte[] raw, int components, int bits) {
        var bytes = bits > 8 ? 2 : 1;
        var count = raw.length / (bytes * components);
        var planes = new int[components][count];
        for (var i = 0; i < count; i++) {
            for (var c = 0; c < components; c++) {
                var at = (i * components + c) * bytes;
                planes[c][i] = bytes == 1 ? raw[at] & 0xFF : (raw[at] & 0xFF) | ((raw[at + 1] & 0xFF) << 8);
            }
        }
        return planes;
    }

    private static int[][] ffmpegDecode(byte[] jls, String pixelFormat, int components, int bits) {
        var decoded = Oracle.run(Oracle.Tool.FFMPEG, TIMEOUT, jls, List.of("-hide_banner", "-loglevel", "error",
                "-f", "jpegls_pipe", "-i", "-", "-frames:v", "1", "-f", "rawvideo", "-pix_fmt", pixelFormat, "-"));
        return planes(decoded, components, bits);
    }

    @ParameterizedTest(name = "{0}, NEAR {3}, {4}")
    @MethodSource("settings")
    @DisplayName("ffmpeg decodes the encoder's files to the samples the decoder reconstructs")
    void encoded(String pixelFormat, int components, int bits, int near, JpegLsEncoderOptions.Interleave interleave) {
        Oracle.assume(Oracle.Tool.FFMPEG);
        var source = JpegLsVectors.synthetic(53, 37, components, bits, 40 + near, null);
        var picture = components == 1
                ? bits == 8 ? JpegLsEncoderTest.gray(53, 37, source[0]) : JpegLsEncoderTest.gray16(53, 37, 16, source[0])
                : JpegLsEncoderTest.rgb(53, 37, source);
        var jls = JpegLsEncoder.encode(picture, JpegLsEncoderOptions.builder().nearLossless(near)
                .interleave(interleave).build());
        var ours = JpegLsVectors.decode(jls);
        var theirs = ffmpegDecode(jls, pixelFormat, components, bits);
        assertEquals(components, ours.length);
        for (var c = 0; c < components; c++) {
            Tolerance.exact().assertWithin("component " + c, theirs[c], ours[c]);
            Tolerance.maxAbs(near).assertWithin("component " + c + " against the source", source[c], ours[c]);
        }
    }

    static Stream<Arguments> ffmpegSettings() {
        var cases = new ArrayList<Arguments>();
        for (var near = 0; near <= 2; near++) {
            cases.add(Arguments.of("gray", 1, 8, near));
            cases.add(Arguments.of("rgb24", 3, 8, near));
            cases.add(Arguments.of("gray16le", 1, 16, near));
        }
        return cases.stream();
    }

    @ParameterizedTest(name = "{0}, NEAR {3}")
    @MethodSource("ffmpegSettings")
    @DisplayName("the decoder decodes ffmpeg's files to the samples ffmpeg decodes")
    void decoded(String pixelFormat, int components, int bits, int near) {
        Oracle.assume(Oracle.Tool.FFMPEG);
        var source = JpegLsVectors.synthetic(47, 31, components, bits, 60 + near, null);
        var jls = Oracle.run(Oracle.Tool.FFMPEG, TIMEOUT, raw(source, bits), List.of("-hide_banner", "-loglevel",
                "error", "-f", "rawvideo", "-pix_fmt", pixelFormat, "-s", "47x31", "-i", "-", "-frames:v", "1",
                "-c:v", "jpegls", "-pred", Integer.toString(near), "-f", "image2pipe", "-"));
        var theirs = ffmpegDecode(jls, pixelFormat, components, bits);
        var ours = JpegLsVectors.decode(jls);
        for (var c = 0; c < components; c++) {
            Tolerance.exact().assertWithin("component " + c, theirs[c], ours[c]);
        }
    }
}
