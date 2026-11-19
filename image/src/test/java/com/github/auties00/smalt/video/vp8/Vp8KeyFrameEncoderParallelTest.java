package com.github.auties00.smalt.video.vp8;

import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.internal.task.ParallelBands;
import com.github.auties00.smalt.video.vp8.internal.Vp8KeyFrameEncoder;
import com.github.auties00.smalt.video.vp8.internal.Vp8KeyFrameSettings;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.Random;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

/**
 * Encodes synthetic pictures large enough for the wavefront token loop on one thread and on every
 * thread the module has, and requires the same bytes and the same reconstruction.
 */
@DisplayName("key-frame encoder is independent of its thread count")
class Vp8KeyFrameEncoderParallelTest {
    static YuvPicture picture(int width, int height, long seed) {
        var random = new Random(seed);
        var cw = (width + 1) / 2;
        var ch = (height + 1) / 2;
        var y = new byte[width * height];
        var u = new byte[cw * ch];
        var v = new byte[cw * ch];
        for (var j = 0; j < height; j++) {
            for (var i = 0; i < width; i++) {
                var base = 128 + (int) (90 * Math.sin(i * 0.013 + j * 0.007) * Math.cos(j * 0.021));
                var detail = ((i / 7 + j / 5) & 1) == 0 ? 25 : -25;
                var noise = random.nextInt(((i >> 6) + (j >> 6)) % 5 * 8 + 1);
                y[j * width + i] = (byte) Math.clamp(base + (((i >> 5) + (j >> 5)) % 3 == 0 ? detail : 0) + noise, 16, 235);
            }
        }
        for (var j = 0; j < ch; j++) {
            for (var i = 0; i < cw; i++) {
                u[j * cw + i] = (byte) Math.clamp(128 + (int) (50 * Math.sin(i * 0.02)) + random.nextInt(9), 16, 240);
                v[j * cw + i] = (byte) Math.clamp(128 + (int) (50 * Math.cos(j * 0.03)) + random.nextInt(9), 16, 240);
            }
        }
        return YuvPicture.builder().size(width, height).chromaFormat(ChromaFormat.YUV420).color(ColorInfo.BT601_LIMITED)
                .luma(y, width)
                .chroma(u, v, cw)
                .build();
    }

    static Stream<Arguments> cases() {
        var cases = new ArrayList<Arguments>();
        for (var size : new int[][]{{1001, 777}, {530, 520}, {400, 1200}, {2049, 130}}) {
            for (var quality : new int[]{5, 50, 75, 100}) {
                for (var method : new int[]{3, 4, 6}) {
                    cases.add(Arguments.of(size[0], size[1], quality, method, 4, 1, 60));
                }
            }
        }
        cases.add(Arguments.of(1001, 777, 75, 4, 4, 2, 60));
        cases.add(Arguments.of(1001, 777, 75, 4, 4, 8, 0));
        cases.add(Arguments.of(1001, 777, 75, 4, 1, 1, 60));
        cases.add(Arguments.of(1001, 777, 90, 5, 2, 4, 60));
        return cases.stream();
    }

    static Stream<Arguments> modes() {
        var cases = new ArrayList<Arguments>();
        for (var method : new int[]{1, 3, 4, 6}) {
            cases.add(Arguments.of(method, new Vp8KeyFrameSettings(75, method, 4, 60, 0, 1, 50, false, true, 1, 0)));
            cases.add(Arguments.of(method, new Vp8KeyFrameSettings(40, method, 3, 0, 3, 2, 80, true, true, 1, 0)));
            cases.add(Arguments.of(method, new Vp8KeyFrameSettings(75, method, 4, 60, 0, 1, 50, false, false, 3, 0)));
            cases.add(Arguments.of(method, new Vp8KeyFrameSettings(75, method, 4, 60, 0, 4, 50, false, true, 6, 38)));
        }
        return cases.stream();
    }

    @ParameterizedTest(name = "m{0} {1}")
    @MethodSource("modes")
    @DisplayName("automatic filtering, passes and PSNR targets do not depend on the thread count")
    void sameOutputModes(int method, Vp8KeyFrameSettings settings) {
        try (var _ = ParallelBands.operation()) {
            var picture = picture(1001, 777, method * 17L);
            var single = new Vp8KeyFrameEncoder(picture, settings);
            single.limitThreads(1);
            var parallel = new Vp8KeyFrameEncoder(picture, settings);
            assertArrayEquals(single.encode(), parallel.encode());
        }
    }

    @ParameterizedTest(name = "{0}x{1} q{2} m{3} segments {4} partitions {5} filter {6}")
    @MethodSource("cases")
    @DisplayName("the bytes and the reconstruction do not depend on the thread count")
    void sameOutput(int width, int height, int quality, int method, int segments, int partitions, int filter) {
        try (var _ = ParallelBands.operation()) {
            var picture = picture(width, height, quality * 31L + method);
            var settings = new Vp8KeyFrameSettings(quality, method, segments, filter, 0, partitions, 50);
            var single = new Vp8KeyFrameEncoder(picture, settings);
            single.limitThreads(1);
            single.captureReconstruction();
            var parallel = new Vp8KeyFrameEncoder(picture, settings);
            parallel.captureReconstruction();
            assertArrayEquals(single.encode(), parallel.encode());
            assertArrayEquals(single.reconstruction(), parallel.reconstruction());
        }
    }
}
