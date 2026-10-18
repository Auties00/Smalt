package com.github.auties00.smalt.capture.internal.convert;

import java.util.SplittableRandom;

/**
 * Runs the capture conversion kernels on synthetic 640x360 frames, for the training workloads of
 * JDK ahead-of-time caches.
 *
 * <p>A round converts one BGRX and one XRGB screen frame, one YUY2 and one UYVY camera frame, the
 * chroma plane of one NV12 frame, and the chroma of one 4:2:2 Motion-JPEG picture, through
 * {@link ConversionKernel#INSTANCE}. The kernels are plain Java, so a round runs whatever the
 * platform of the training run. This class is not thread-safe.
 */
public final class CaptureTrainingRound {
    private static final int WIDTH = 640;
    private static final int HEIGHT = 360;
    private static final ConversionKernel KERNEL = ConversionKernel.INSTANCE;
    private static final RgbLayout XRGB = new RgbLayout(1, 2, 3);
    private static final byte[] RGB = new byte[WIDTH * HEIGHT * 4];
    private static final byte[] PACKED = new byte[WIDTH * HEIGHT * 2];
    private static final byte[] Y = new byte[WIDTH * HEIGHT];
    private static final byte[] U = new byte[WIDTH * HEIGHT / 4];
    private static final byte[] V = new byte[WIDTH * HEIGHT / 4];

    static {
        var random = new SplittableRandom(3);
        random.nextBytes(RGB);
        random.nextBytes(PACKED);
    }

    private CaptureTrainingRound() {
        throw new AssertionError();
    }

    /**
     * Converts every synthetic frame once.
     */
    public static void round() {
        var chromaWidth = WIDTH / 2;
        for (var layout : new RgbLayout[]{RgbLayout.BGRX, XRGB}) {
            for (var row = 0; row < HEIGHT; row += 2) {
                var upper = row * 4 * WIDTH;
                var lower = upper + 4 * WIDTH;
                KERNEL.rgbToLuma(RGB, upper, WIDTH, layout, YuvCoefficients.BT709_LIMITED, Y, row * WIDTH);
                KERNEL.rgbToLuma(RGB, lower, WIDTH, layout, YuvCoefficients.BT709_LIMITED, Y, (row + 1) * WIDTH);
                KERNEL.rgbToChroma(RGB, upper, lower, chromaWidth, layout, YuvCoefficients.BT709_LIMITED, U, V,
                        row / 2 * chromaWidth);
            }
        }
        for (var lumaIndex = 0; lumaIndex < 2; lumaIndex++) {
            var cbIndex = 1 - lumaIndex;
            for (var row = 0; row < HEIGHT; row += 2) {
                var upper = row * 2 * WIDTH;
                var lower = upper + 2 * WIDTH;
                KERNEL.packedLuma(PACKED, upper, WIDTH, lumaIndex, Y, row * WIDTH);
                KERNEL.packedLuma(PACKED, lower, WIDTH, lumaIndex, Y, (row + 1) * WIDTH);
                KERNEL.packedChroma(PACKED, upper, lower, chromaWidth, cbIndex, cbIndex + 2, U, V, row / 2 * chromaWidth);
            }
        }
        for (var row = 0; row < HEIGHT / 2; row++) {
            KERNEL.deinterleave(PACKED, row * WIDTH, chromaWidth, U, V, row * chromaWidth);
            KERNEL.averageRows(PACKED, 2 * row * WIDTH, PACKED, (2 * row + 1) * WIDTH, chromaWidth, U, row * chromaWidth);
        }
    }
}
