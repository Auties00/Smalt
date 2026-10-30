package com.github.auties00.smalt.video.h264.encoder.internal;

/**
 * Holds the planes a reference frame or field offers motion compensation in the extended engine:
 * its padded sample planes and, for each component interpolated like luma, the three half-sample
 * planes of ITU-T H.264 8.4.2.2.1.
 *
 * <p>The half-sample value {@code b} between samples {@code (x, y)} and {@code (x + 1, y)} is
 * stored at {@code (x, y)} of {@link #halfH}, {@code h} between {@code (x, y)} and
 * {@code (x, y + 1)} at {@code (x, y)} of {@link #halfV}, and {@code j} at the center of the four
 * at {@code (x, y)} of {@link #halfC}. They are computed over the padded area except its outer
 * three samples, which motion vectors never reach.
 */
final class WideReference {
    /**
     * The padded planes of the reference, indexed by component.
     */
    final WidePlane[] planes;

    /**
     * The horizontal half-sample planes of the components interpolated like luma.
     */
    final short[][] halfH = new short[3][];

    /**
     * The vertical half-sample planes of the components interpolated like luma.
     */
    final short[][] halfV = new short[3][];

    /**
     * The center half-sample planes of the components interpolated like luma.
     */
    final short[][] halfC = new short[3][];

    /**
     * The parity of the field, 0 for the top field and 1 for the bottom one, or -1 for a frame.
     */
    final int parity;

    /**
     * The frame the reference belongs to.
     */
    final WideFrame frame;

    /**
     * Wraps the padded planes of a frame or field.
     *
     * @param frame the frame the planes belong to
     * @param planes the padded planes
     * @param parity the field parity, or -1 for the frame
     */
    WideReference(WideFrame frame, WidePlane[] planes, int parity) {
        this.frame = frame;
        this.planes = planes;
        this.parity = parity;
    }

    /**
     * Computes the half-sample planes of the components interpolated like luma.
     *
     * @param components the number of components interpolated like luma: 1, or 3 for 4:4:4
     * @param max the largest sample value
     * @param scratch scratch space of at least one padded row of intermediate values
     */
    void interpolate(int components, int max, int[] scratch) {
        for (var c = 0; c < components; c++) {
            var plane = planes[c];
            var size = plane.data.length;
            if (halfH[c] == null || halfH[c].length != size) {
                halfH[c] = new short[size];
                halfV[c] = new short[size];
                halfC[c] = new short[size];
            }
            interpolate(plane, halfH[c], halfV[c], halfC[c], max, scratch);
        }
    }

    /**
     * Computes the half-sample planes of one plane.
     *
     * @param plane the padded plane
     * @param h the horizontal half-sample plane
     * @param v the vertical half-sample plane
     * @param center the center half-sample plane
     * @param max the largest sample value
     * @param temp scratch space of at least {@code plane.stride} values
     */
    private static void interpolate(WidePlane plane, short[] h, short[] v, short[] center, int max, int[] temp) {
        var data = plane.data;
        var stride = plane.stride;
        var rows = plane.data.length / stride;
        for (var y = 3; y < rows - 3; y++) {
            var row = y * stride;
            for (var x = 2; x < stride - 3; x++) {
                var i = row + x;
                h[i] = (short) clip((tap(data[i - 2], data[i - 1], data[i], data[i + 1], data[i + 2], data[i + 3]) + 16)
                        >> 5, max);
                var vertical = tap(data[i - 2 * stride], data[i - stride], data[i], data[i + stride],
                        data[i + 2 * stride], data[i + 3 * stride]);
                v[i] = (short) clip((vertical + 16) >> 5, max);
            }
            for (var x = 0; x < stride; x++) {
                var i = row + x;
                temp[x] = tap(data[i - 2 * stride], data[i - stride], data[i], data[i + stride], data[i + 2 * stride],
                        data[i + 3 * stride]);
            }
            for (var x = 2; x < stride - 3; x++) {
                center[row + x] = (short) clip((tap(temp[x - 2], temp[x - 1], temp[x], temp[x + 1], temp[x + 2],
                        temp[x + 3]) + 512) >> 10, max);
            }
        }
    }

    /**
     * Applies the six-tap filter {@code (1, -5, 20, 20, -5, 1)}.
     *
     * @param a the first input
     * @param b the second input
     * @param c the third input
     * @param d the fourth input
     * @param e the fifth input
     * @param f the sixth input
     * @return the unscaled sum
     */
    private static int tap(int a, int b, int c, int d, int e, int f) {
        return a - 5 * b + 20 * c + 20 * d - 5 * e + f;
    }

    /**
     * Clips a value to the sample range.
     *
     * @param value the filtered value
     * @param max the largest sample value
     * @return the clipped value
     */
    private static int clip(int value, int max) {
        return value < 0 ? 0 : Math.min(value, max);
    }

    /**
     * Predicts a block of a component interpolated like luma at a quarter-sample vector (ITU-T H.264
     * 8.4.2.2.1).
     *
     * @param c the component
     * @param x the block's horizontal position
     * @param y the block's vertical position
     * @param mvx the horizontal vector in quarter samples
     * @param mvy the vertical vector in quarter samples
     * @param w the block width
     * @param h the block height
     * @param dst the destination
     * @param dstOffset the index of the destination's top-left sample
     * @param dstStride the destination row stride
     */
    void predictLuma(int c, int x, int y, int mvx, int mvy, int w, int h, short[] dst, int dstOffset,
                     int dstStride) {
        var plane = planes[c];
        var stride = plane.stride;
        var base = plane.origin + (y + (mvy >> 2)) * stride + x + (mvx >> 2);
        var fx = mvx & 3;
        var fy = mvy & 3;
        var full = plane.data;
        var hh = halfH[c];
        var vv = halfV[c];
        var cc = halfC[c];
        short[] a;
        var offsetA = base;
        short[] b = null;
        var offsetB = base;
        switch (fy * 4 + fx) {
            case 0 -> a = full;
            case 1 -> {
                a = full;
                b = hh;
            }
            case 2 -> a = hh;
            case 3 -> {
                a = full;
                offsetA = base + 1;
                b = hh;
            }
            case 4 -> {
                a = full;
                b = vv;
            }
            case 5 -> {
                a = hh;
                b = vv;
            }
            case 6 -> {
                a = hh;
                b = cc;
            }
            case 7 -> {
                a = hh;
                b = vv;
                offsetB = base + 1;
            }
            case 8 -> a = vv;
            case 9 -> {
                a = vv;
                b = cc;
            }
            case 10 -> a = cc;
            case 11 -> {
                a = cc;
                b = vv;
                offsetB = base + 1;
            }
            case 12 -> {
                a = full;
                offsetA = base + stride;
                b = vv;
            }
            case 13 -> {
                a = vv;
                b = hh;
                offsetB = base + stride;
            }
            case 14 -> {
                a = cc;
                b = hh;
                offsetB = base + stride;
            }
            default -> {
                a = vv;
                offsetA = base + 1;
                b = hh;
                offsetB = base + stride;
            }
        }
        for (var row = 0; row < h; row++) {
            var s = row * stride;
            var d = dstOffset + row * dstStride;
            if (b == null) {
                System.arraycopy(a, offsetA + s, dst, d, w);
            } else {
                for (var col = 0; col < w; col++) {
                    dst[d + col] = (short) ((a[offsetA + s + col] + b[offsetB + s + col] + 1) >> 1);
                }
            }
        }
    }

    /**
     * Predicts a block of a subsampled chroma component by bilinear interpolation (ITU-T H.264
     * 8.4.2.2.2).
     *
     * @param c the component, 1 or 2
     * @param x the block's horizontal position in chroma samples
     * @param y the block's vertical position in chroma samples
     * @param mvx the horizontal chroma vector in eighth samples
     * @param mvy the vertical chroma vector in eighth samples
     * @param w the block width
     * @param h the block height
     * @param dst the destination
     * @param dstOffset the index of the destination's top-left sample
     * @param dstStride the destination row stride
     */
    void predictChroma(int c, int x, int y, int mvx, int mvy, int w, int h, short[] dst, int dstOffset,
                       int dstStride) {
        var plane = planes[c];
        var data = plane.data;
        var stride = plane.stride;
        var base = plane.origin + (y + (mvy >> 3)) * stride + x + (mvx >> 3);
        var fx = mvx & 7;
        var fy = mvy & 7;
        var wa = (8 - fx) * (8 - fy);
        var wb = fx * (8 - fy);
        var wc = (8 - fx) * fy;
        var wd = fx * fy;
        for (var row = 0; row < h; row++) {
            var s = base + row * stride;
            var d = dstOffset + row * dstStride;
            for (var col = 0; col < w; col++) {
                var i = s + col;
                dst[d + col] = (short) ((wa * data[i] + wb * data[i + 1] + wc * data[i + stride]
                        + wd * data[i + stride + 1] + 32) >> 6);
            }
        }
    }
}
