package com.github.auties00.smalt.image.raw.internal;

/**
 * Interpolates Bayer rows with plain loops: the reference implementation of {@link BayerKernel}.
 */
final class ScalarBayerKernel implements BayerKernel {
    /**
     * Constructs the kernel.
     */
    ScalarBayerKernel() {
    }

    @Override
    public void row(float[] data, int up, int mid, int down, int count, int pair, float[] r, float[] g, float[] b,
                    int out) {
        for (var index = 0; index < count; index++) {
            var x = data[mid + index];
            var left = data[mid + index - 1];
            var right = data[mid + index + 1];
            var above = data[up + index];
            var below = data[down + index];
            var odd = (index & 1) != 0;
            var greenSite = (pair == RED_GREEN || pair == BLUE_GREEN) == odd;
            var at = out + index;
            if (greenSite) {
                var h = (left + right) * 0.5f;
                var v = (above + below) * 0.5f;
                g[at] = x;
                if (pair == RED_GREEN || pair == GREEN_RED) {
                    r[at] = h;
                    b[at] = v;
                } else {
                    b[at] = h;
                    r[at] = v;
                }
            } else {
                var p = (((above + left) + right) + below) * 0.25f;
                var c = (((data[up + index - 1] + data[up + index + 1]) + data[down + index - 1])
                        + data[down + index + 1]) * 0.25f;
                g[at] = p;
                if (pair == RED_GREEN || pair == GREEN_RED) {
                    r[at] = x;
                    b[at] = c;
                } else {
                    b[at] = x;
                    r[at] = c;
                }
            }
        }
    }
}
