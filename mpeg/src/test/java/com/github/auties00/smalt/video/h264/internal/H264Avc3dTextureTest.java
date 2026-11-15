package com.github.auties00.smalt.video.h264.internal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Checks the sample-level helpers of the 3D-AVC texture tools: the quarter-sample luma interpolation the adaptive
 * luminance compensation templates read, against a direct transcription of the interpolation equations of ITU-T
 * H.264 8.4.2.2.1 over clamped sample positions, and the compensation itself against hand-computed scalings.
 */
@DisplayName("H.264 3D-AVC texture tool helpers")
class H264Avc3dTextureTest {
    private static final int MB_WIDTH = 3;

    private static final int MB_HEIGHT = 2;

    private static DecoderFrame frame(long seed) {
        var frame = new DecoderFrame(MB_WIDTH, MB_HEIGHT, 1);
        var random = new Random(seed);
        for (var y = 0; y < MB_HEIGHT * 16; y++) {
            for (var x = 0; x < MB_WIDTH * 16; x++) {
                frame.luma[frame.lumaOrigin + y * frame.lumaStride + x] = (byte) random.nextInt(256);
            }
        }
        frame.extendBorders(0, MB_HEIGHT * 16, 0, MB_HEIGHT * 8);
        return frame;
    }

    private static int pel(DecoderFrame frame, int x, int y) {
        var cx = Math.clamp(x, 0, MB_WIDTH * 16 - 1);
        var cy = Math.clamp(y, 0, MB_HEIGHT * 16 - 1);
        return frame.luma[frame.lumaOrigin + cy * frame.lumaStride + cx] & 0xFF;
    }

    private static int clip(int value) {
        return Math.clamp(value, 0, 255);
    }

    private static int tap(int e, int f, int g, int h, int i, int j) {
        return e - 5 * f + 20 * g + 20 * h - 5 * i + j;
    }

    /** The unrounded horizontal half-sample sum b1 right of (x, y). */
    private static int b1(DecoderFrame f, int x, int y) {
        return tap(pel(f, x - 2, y), pel(f, x - 1, y), pel(f, x, y), pel(f, x + 1, y), pel(f, x + 2, y),
                pel(f, x + 3, y));
    }

    /** The unrounded vertical half-sample sum h1 below (x, y). */
    private static int h1(DecoderFrame f, int x, int y) {
        return tap(pel(f, x, y - 2), pel(f, x, y - 1), pel(f, x, y), pel(f, x, y + 1), pel(f, x, y + 2),
                pel(f, x, y + 3));
    }

    /** The sample at a quarter-sample position, by equations 8-243 to 8-261. */
    private static int reference(DecoderFrame f, int x4, int y4) {
        var x = x4 >> 2;
        var y = y4 >> 2;
        var g = pel(f, x, y);
        var hInt = pel(f, x + 1, y);
        var m = pel(f, x, y + 1);
        var b = clip((b1(f, x, y) + 16) >> 5);
        var h = clip((h1(f, x, y) + 16) >> 5);
        var s = clip((b1(f, x, y + 1) + 16) >> 5);
        var mHalf = clip((h1(f, x + 1, y) + 16) >> 5);
        var j1 = tap(b1(f, x, y - 2), b1(f, x, y - 1), b1(f, x, y), b1(f, x, y + 1), b1(f, x, y + 2), b1(f, x, y + 3));
        var j = clip((j1 + 512) >> 10);
        return switch ((y4 & 3) * 4 + (x4 & 3)) {
            case 0 -> g;
            case 1 -> (g + b + 1) >> 1;
            case 2 -> b;
            case 3 -> (b + hInt + 1) >> 1;
            case 4 -> (g + h + 1) >> 1;
            case 5 -> (b + h + 1) >> 1;
            case 6 -> (b + j + 1) >> 1;
            case 7 -> (b + mHalf + 1) >> 1;
            case 8 -> h;
            case 9 -> (h + j + 1) >> 1;
            case 10 -> j;
            case 11 -> (j + mHalf + 1) >> 1;
            case 12 -> (h + m + 1) >> 1;
            case 13 -> (h + s + 1) >> 1;
            case 14 -> (j + s + 1) >> 1;
            default -> (mHalf + s + 1) >> 1;
        };
    }

    @ParameterizedTest(name = "seed {0}")
    @ValueSource(longs = {1, 2, 3})
    @DisplayName("interpolates every quarter-sample position as the equations of 8.4.2.2.1, inside and across the border")
    void lumaSample(long seed) {
        var frame = frame(seed);
        for (var y4 = -12; y4 < (MB_HEIGHT * 16 + 3) * 4; y4++) {
            for (var x4 = -12; x4 < (MB_WIDTH * 16 + 3) * 4; x4++) {
                assertEquals(reference(frame, x4, y4), Avc3dTexture.lumaSample(frame, x4, y4),
                        "position " + x4 + "," + y4);
            }
        }
    }

    private static DecoderFrame flat(int value) {
        var frame = new DecoderFrame(MB_WIDTH, MB_HEIGHT, 1);
        java.util.Arrays.fill(frame.luma, (byte) value);
        return frame;
    }

    @Test
    @DisplayName("scales the prediction by the ratio of the decoded and reference template sums")
    void scales() {
        var ref = flat(100);
        var current = flat(120);
        var stride = current.lumaStride;
        var block = current.lumaOrigin + 16 * stride + 16;
        for (var y = 0; y < 8; y++) {
            for (var x = 0; x < 8; x++) {
                current.luma[block + y * stride + x] = (byte) (90 + x + y);
            }
        }
        Avc3dTexture.compensateLuminance(ref, 0, current.luma, block, stride, 16, 16, 8, 8, MB_WIDTH * 16,
                MB_HEIGHT * 16);
        var refSum = 1 + 16 * 100;
        var decSum = 1 + 16 * 120;
        var factor = ((decSum << 15) + (refSum >> 1)) / refSum;
        for (var y = 0; y < 8; y++) {
            for (var x = 0; x < 8; x++) {
                var expected = Math.min(255, ((90 + x + y) * factor + (1 << 14)) >> 15);
                assertEquals(expected, current.luma[block + y * stride + x] & 0xFF, "sample " + x + "," + y);
            }
        }
    }

    @Test
    @DisplayName("leaves the prediction when the template sums agree to four bits, or the templates leave the pictures")
    void unchanged() {
        var ref = flat(100);
        var current = flat(100);
        var stride = current.lumaStride;
        var block = current.lumaOrigin + 16 * stride + 16;
        for (var y = 0; y < 8; y++) {
            for (var x = 0; x < 8; x++) {
                current.luma[block + y * stride + x] = (byte) (40 + 9 * x + 5 * y);
            }
        }
        var before = current.luma.clone();
        Avc3dTexture.compensateLuminance(ref, 0, current.luma, block, stride, 16, 16, 8, 8, MB_WIDTH * 16,
                MB_HEIGHT * 16);
        assertArrayEquals(before, current.luma);
        var bright = flat(200);
        var corner = bright.lumaOrigin;
        before = bright.luma.clone();
        Avc3dTexture.compensateLuminance(ref, 0, bright.luma, corner, stride, 0, 0, 8, 8, MB_WIDTH * 16,
                MB_HEIGHT * 16);
        assertArrayEquals(before, bright.luma);
        var mv = (-1 << 16) | 0;
        Avc3dTexture.compensateLuminance(ref, mv, bright.luma, block, stride, 16, 1, 8, 8, MB_WIDTH * 16,
                MB_HEIGHT * 16);
        assertArrayEquals(before, bright.luma);
    }

    @Test
    @DisplayName("leaves out template pairs that differ by more than 30")
    void threshold() {
        var ref = flat(100);
        var current = flat(125);
        var stride = current.lumaStride;
        var block = current.lumaOrigin + 16 * stride + 16;
        for (var x = 0; x < 4; x++) {
            current.luma[block - stride + x] = (byte) 200;
        }
        for (var y = 0; y < 8; y++) {
            for (var x = 0; x < 8; x++) {
                current.luma[block + y * stride + x] = (byte) 80;
            }
        }
        Avc3dTexture.compensateLuminance(ref, 0, current.luma, block, stride, 16, 16, 8, 8, MB_WIDTH * 16,
                MB_HEIGHT * 16);
        var refSum = 1 + 12 * 100;
        var decSum = 1 + 12 * 125;
        var factor = ((decSum << 15) + (refSum >> 1)) / refSum;
        assertEquals((80 * factor + (1 << 14)) >> 15, current.luma[block] & 0xFF);
    }
}
