package com.github.auties00.smalt.video.vp8;

import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.video.vp8.internal.Vp8;
import com.github.auties00.smalt.video.vp8.internal.Vp8KeyFrameSettings;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * Encodes YUV pictures with {@link Vp8#encodeKeyFrame} and with {@code cwebp -s} (libwebp fed the
 * very same YUV samples) and requires byte-identical VP8 frames: the encoder is a faithful port of
 * libwebp's.
 */
@DisplayName("Vp8.encodeKeyFrame is byte-identical to cwebp")
class Vp8EncoderExactTest {
    static YuvPicture picture(byte[] i420, int width, int height) {
        var cw = (width + 1) / 2;
        var ch = (height + 1) / 2;
        var y = new byte[width * height];
        var u = new byte[cw * ch];
        var v = new byte[cw * ch];
        System.arraycopy(i420, 0, y, 0, y.length);
        System.arraycopy(i420, y.length, u, 0, u.length);
        System.arraycopy(i420, y.length + u.length, v, 0, v.length);
        return YuvPicture.builder().size(width, height).chromaFormat(ChromaFormat.YUV420).color(ColorInfo.BT601_LIMITED)
                .luma(y, width)
                .chroma(u, v, cw)
                .build();
    }

    static void assertSameAsCwebp(String source, int width, int height, Vp8KeyFrameSettings options) {
        var yuv = WebpTools.yuv(source, width, height);
        var args = new ArrayList<>(List.of("-q", Float.toString(options.quality()), "-m", Integer.toString(options.method()),
                "-segments", Integer.toString(options.segments()), "-f", Integer.toString(options.filterStrength()),
                "-sharpness", Integer.toString(options.sharpness()), "-sns", Integer.toString(options.snsStrength())));
        if (options.simpleFilter()) {
            args.add("-nostrong");
        }
        if (options.autoFilter()) {
            args.add("-af");
        }
        if (options.passes() > 1) {
            args.addAll(List.of("-pass", Integer.toString(options.passes())));
        }
        if (options.targetPsnr() > 0) {
            args.addAll(List.of("-psnr", Float.toString(options.targetPsnr())));
        }
        var expected = Ivf.vp8Chunk(WebpTools.cwebpYuv(yuv, width, height, args));
        var actual = Vp8.encodeKeyFrame(picture(yuv, width, height), options);
        var length = Math.min(expected.length, actual.length);
        for (var i = 0; i < length; i++) {
            if (expected[i] != actual[i]) {
                fail("first difference at byte " + i + " of " + expected.length + "/" + actual.length
                        + " (" + args + ")");
            }
        }
        if (expected.length != actual.length && !(expected.length == actual.length + 1 && expected[actual.length] == 0)) {
            fail("length " + actual.length + " vs cwebp " + expected.length + " (" + args + ")");
        }
    }

    static Stream<Arguments> qualityMethod() {
        var arguments = new ArrayList<Arguments>();
        for (var q : new int[]{0, 10, 25, 50, 75, 90, 99, 100}) {
            for (var m = 0; m <= 6; m++) {
                arguments.add(Arguments.of(q, m));
            }
        }
        return arguments.stream();
    }

    static Stream<Arguments> modes() {
        var arguments = new ArrayList<Arguments>();
        for (var m : new int[]{0, 2, 3, 4, 6}) {
            arguments.add(Arguments.of("af", new Vp8KeyFrameSettings(75, m, 4, 60, 0, 1, 50, false, true, 1, 0)));
            arguments.add(Arguments.of("af, no filter", new Vp8KeyFrameSettings(60, m, 4, 0, 2, 1, 50, false, true, 1, 0)));
            arguments.add(Arguments.of("af simple", new Vp8KeyFrameSettings(40, m, 3, 30, 4, 1, 70, true, true, 1, 0)));
            arguments.add(Arguments.of("simple", new Vp8KeyFrameSettings(80, m, 4, 50, 0, 1, 50, true, false, 1, 0)));
            arguments.add(Arguments.of("3 passes", new Vp8KeyFrameSettings(75, m, 4, 60, 0, 1, 50, false, false, 3, 0)));
            arguments.add(Arguments.of("psnr 34", new Vp8KeyFrameSettings(75, m, 4, 60, 0, 1, 50, false, false, 6, 34)));
            arguments.add(Arguments.of("psnr 41.5 from q10", new Vp8KeyFrameSettings(10, m, 2, 20, 1, 1, 30, false, false, 6, 41.5f)));
            arguments.add(Arguments.of("psnr 37, 2 passes, af", new Vp8KeyFrameSettings(75, m, 4, 60, 0, 1, 50, false, true, 2, 37)));
        }
        return arguments.stream();
    }

    @ParameterizedTest(name = "{0}: {1}")
    @MethodSource("modes")
    @DisplayName("testsrc2 320x240 with automatic and simple filtering, passes and PSNR targets")
    void modes(String name, Vp8KeyFrameSettings settings) {
        assertSameAsCwebp("testsrc2=size=320x240:rate=1", 320, 240, settings);
    }

    @ParameterizedTest(name = "{0}: {1}")
    @MethodSource("modes")
    @DisplayName("odd-sized mandelbrot 201x133 with the same modes")
    void oddModes(String name, Vp8KeyFrameSettings settings) {
        assertSameAsCwebp("mandelbrot=size=201x133:rate=1", 201, 133, settings);
    }

    @ParameterizedTest(name = "q{0} m{1}")
    @MethodSource("qualityMethod")
    @DisplayName("testsrc2 320x240 at every method")
    void testsrc(int quality, int method) {
        assertSameAsCwebp("testsrc2=size=320x240:rate=1", 320, 240,
                new Vp8KeyFrameSettings(quality, method, 4, 60, 0, 1, 50));
    }
}
