package com.github.auties00.smalt.internal.quantize;

/**
 * Implements {@link PaletteSearchKernel} with plain integer arithmetic; the reference the vector
 * kernel must match.
 */
public final class ScalarPaletteSearchKernel implements PaletteSearchKernel {
    /**
     * Constructs the kernel.
     */
    public ScalarPaletteSearchKernel() {
    }

    @Override
    public int nearest(int red, int green, int blue, int[] reds, int[] greens, int[] blues, int count) {
        var best = Integer.MAX_VALUE;
        for (var i = 0; i < count; i++) {
            var dr = red - reds[i];
            var dg = green - greens[i];
            var db = blue - blues[i];
            best = Math.min(best, (dr * dr + dg * dg + db * db) << 8 | i);
        }
        return best;
    }

    @Override
    public void bounds(int red, int green, int blue, int span, int[] reds, int[] greens, int[] blues, int count,
                       int[] near, int[] far) {
        var last = span - 1;
        for (var i = 0; i < count; i++) {
            var r = reds[i];
            var g = greens[i];
            var b = blues[i];
            var nr = Math.max(0, Math.max(red - r, r - (red + last)));
            var ng = Math.max(0, Math.max(green - g, g - (green + last)));
            var nb = Math.max(0, Math.max(blue - b, b - (blue + last)));
            var fr = Math.max(r - red, red + last - r);
            var fg = Math.max(g - green, green + last - g);
            var fb = Math.max(b - blue, blue + last - b);
            near[i] = nr * nr + ng * ng + nb * nb;
            far[i] = fr * fr + fg * fg + fb * fb;
        }
    }
}
