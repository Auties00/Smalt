/* Ports jxl-oxide 0.12.6 (crates/jxl-render: render_noise). Copyright Wonwoo Choi and the jxl-oxide contributors;
   MIT License. Modified for Smalt. */
package com.github.auties00.smalt.image.jxl.internal.render;

/**
 * Synthesizes the photon noise of a frame (ISO/IEC 18181-1 section K.3): a per-group stream of
 * XorShift128+ random numbers, high-pass filtered by a 5x5 convolution, scaled by the noise
 * strength of the local intensity and added to the X, Y and B planes.
 *
 * @implNote This implementation runs the convolution over the whole frame with mirrored borders,
 * which is what the per-group convolution with neighboring groups computes.
 */
public final class Noise {
    /**
     * The number of interleaved generators.
     */
    private static final int N = 8;

    /**
     * Prevents instantiation.
     */
    private Noise() {
        throw new AssertionError();
    }

    /**
     * Adds noise to the color planes of a frame.
     *
     * @param x the X plane
     * @param y the Y plane
     * @param b the B plane
     * @param width the frame width
     * @param height the frame height
     * @param groupDim the group dimension
     * @param visibleFrames the number of displayed frames before this one, plus one if it is
     * displayed
     * @param invisibleFrames the number of frames since the last displayed one
     * @param lut the eight noise strengths
     * @param corrX the X from Y correlation
     * @param corrB the B from Y correlation
     */
    public static void apply(float[] x, float[] y, float[] b, int width, int height, int groupDim, long visibleFrames,
                             long invisibleFrames, float[] lut, float corrX, float corrB) {
        var seed0 = (visibleFrames << 32) + invisibleFrames;
        var raw = new float[3][width * height];
        var groupsPerRow = (width + groupDim - 1) / groupDim;
        var groupRows = (height + groupDim - 1) / groupDim;
        for (var gy = 0; gy < groupRows; gy++) {
            for (var gx = 0; gx < groupsPerRow; gx++) {
                var x0 = gx * groupDim;
                var y0 = gy * groupDim;
                var gw = Math.min(groupDim, width - x0);
                var gh = Math.min(groupDim, height - y0);
                fillGroup(raw, width, x0, y0, gw, gh, seed0, ((long) x0 << 32) + y0);
            }
        }
        var noise = new float[3][];
        for (var c = 0; c < 3; c++) {
            noise[c] = convolve(raw[c], width, height);
        }
        var table = new float[9];
        System.arraycopy(lut, 0, table, 0, 8);
        table[8] = lut[7];
        for (var i = 0; i < width * height; i++) {
            var gx = x[i];
            var gyv = y[i];
            var inX = gx + gyv;
            var inY = gyv - gx;
            var scaledX = Math.max(0.0f, inX * 3.0f);
            var scaledY = Math.max(0.0f, inY * 3.0f);
            var intX = Math.min((int) scaledX, 7);
            var fracX = scaledX - intX;
            var intY = Math.min((int) scaledY, 7);
            var fracY = scaledY - intY;
            var sx = (table[intX + 1] - table[intX]) * fracX + table[intX];
            var sy = (table[intY + 1] - table[intY]) * fracY + table[intY];
            var nx = 0.22f * sx * (0.0078125f * noise[0][i] + 0.9921875f * noise[2][i]);
            var ny = 0.22f * sy * (0.0078125f * noise[1][i] + 0.9921875f * noise[2][i]);
            x[i] += corrX * (nx + ny) + nx - ny;
            y[i] += nx + ny;
            b[i] += corrB * (nx + ny);
        }
    }

    /**
     * Fills the random samples of one group, channel after channel.
     *
     * @param raw the three noise planes
     * @param stride the plane width
     * @param x0 the group's left edge
     * @param y0 the group's top edge
     * @param width the group width
     * @param height the group height
     * @param seed0 the frame seed
     * @param seed1 the group seed
     */
    private static void fillGroup(float[][] raw, int stride, int x0, int y0, int width, int height, long seed0,
                                  long seed1) {
        var s0 = new long[N];
        var s1 = new long[N];
        s0[0] = splitMix64(seed0 + 0x9E3779B97F4A7C15L);
        s1[0] = splitMix64(seed1 + 0x9E3779B97F4A7C15L);
        for (var i = 1; i < N; i++) {
            s0[i] = splitMix64(s0[i - 1]);
            s1[i] = splitMix64(s1[i - 1]);
        }
        var widthN2 = (width + N * 2 - 1) / (N * 2);
        var rowLength = widthN2 * N * 2;
        var row = new float[rowLength];
        for (var c = 0; c < 3; c++) {
            var plane = raw[c];
            for (var y = 0; y < height; y++) {
                for (var batch = 0; batch < widthN2; batch++) {
                    for (var i = 0; i < N; i++) {
                        var a = s0[i];
                        var bv = s1[i];
                        var value = a + bv;
                        s0[i] = bv;
                        a ^= a << 23;
                        s1[i] = a ^ (bv ^ (a >>> 18) ^ (bv >>> 5));
                        var low = (int) value;
                        var high = (int) (value >>> 32);
                        row[batch * N * 2 + i * 2] = Float.intBitsToFloat((low >>> 9) | 0x3F800000);
                        row[batch * N * 2 + i * 2 + 1] = Float.intBitsToFloat((high >>> 9) | 0x3F800000);
                    }
                }
                System.arraycopy(row, 0, plane, (y0 + y) * stride + x0, width);
            }
        }
    }

    /**
     * Mixes a 64-bit value (SplitMix64).
     *
     * @param z the value
     * @return the mixed value
     */
    private static long splitMix64(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    /**
     * Applies the 5x5 high-pass convolution with mirrored borders.
     *
     * @param plane the random samples
     * @param width the plane width in samples
     * @param height the plane height in samples
     * @return the filtered samples
     */
    private static float[] convolve(float[] plane, int width, int height) {
        var out = new float[width * height];
        var rows = new int[5];
        var cols = new int[width + 4];
        for (var x = 0; x < width + 4; x++) {
            cols[x] = RestorationFilters.mirror(x - 2, width);
        }
        for (var y = 0; y < height; y++) {
            for (var i = 0; i < 5; i++) {
                rows[i] = RestorationFilters.mirror(y + i - 2, height) * width;
            }
            for (var x = 0; x < width; x++) {
                var sum = 0.0f;
                for (var dy = 0; dy < 5; dy++) {
                    var row = rows[dy];
                    for (var dx = 0; dx < 5; dx++) {
                        sum += plane[row + cols[x + dx]] * 0.16f;
                    }
                }
                out[y * width + x] = sum - plane[rows[2] + x] * 4.0f;
            }
        }
        return out;
    }
}
