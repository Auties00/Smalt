package com.github.auties00.smalt.audio.aac.internal;

/**
 * Computes the AAC transform windows: the sine window and the Kaiser-Bessel-derived (KBD)
 * window of ISO/IEC 14496-3 subclause 4.6.11.3.
 *
 * <p>Every window is returned whole, {@code n} samples for a transform of {@code n / 2} lines;
 * both windows are symmetric and satisfy the Princen-Bradley condition
 * {@code w[i]^2 + w[i + n/2]^2 = 1}.
 */
public final class Windows {
    /**
     * The window shape identifier of the sine window.
     */
    public static final int SINE = 0;

    /**
     * The window shape identifier of the KBD window.
     */
    public static final int KBD = 1;

    /**
     * The KBD alpha of long windows (2048 and 1920 samples).
     */
    public static final double LONG_KBD_ALPHA = 4;

    /**
     * The KBD alpha of short windows (256 and 240 samples).
     */
    public static final double SHORT_KBD_ALPHA = 6;

    /**
     * Prevents instantiation.
     */
    private Windows() {
        throw new AssertionError();
    }

    /**
     * Returns the sine window {@code w[i] = sin(pi / n (i + 1/2))}.
     *
     * @param n the window length, even
     * @return the window
     */
    public static float[] sine(int n) {
        var window = new float[n];
        for (var i = 0; i < n; i++) {
            window[i] = (float) Math.sin(Math.PI / n * (i + 0.5));
        }
        return window;
    }

    /**
     * Returns the Kaiser-Bessel-derived window.
     *
     * @param n     the window length, even
     * @param alpha the Kaiser alpha
     * @return the window
     */
    public static float[] kbd(int n, double alpha) {
        var half = n / 2;
        var kaiser = new double[half + 1];
        var quarter = n / 4.0;
        var total = 0.0;
        for (var i = 0; i <= half; i++) {
            var x = (i - quarter) / quarter;
            kaiser[i] = besselI0(Math.PI * alpha * Math.sqrt(Math.max(0, 1 - x * x)));
            total += kaiser[i];
        }
        var window = new float[n];
        var sum = 0.0;
        for (var i = 0; i < half; i++) {
            sum += kaiser[i];
            var value = (float) Math.sqrt(sum / total);
            window[i] = value;
            window[n - 1 - i] = value;
        }
        return window;
    }

    /**
     * Returns the window of a shape.
     *
     * @param shape the shape, {@link #SINE} or {@link #KBD}
     * @param n     the window length
     * @return the window
     */
    public static float[] of(int shape, int n) {
        var shortWindow = n <= 256;
        return shape == KBD ? kbd(n, shortWindow ? SHORT_KBD_ALPHA : LONG_KBD_ALPHA) : sine(n);
    }

    /**
     * Evaluates the zeroth-order modified Bessel function of the first kind by its power series.
     *
     * @param x the argument
     * @return {@code I0(x)}
     */
    private static double besselI0(double x) {
        var sum = 1.0;
        var term = 1.0;
        var halfX = x / 2;
        for (var k = 1; k < 200; k++) {
            term *= halfX / k;
            var squared = term * term;
            sum += squared;
            if (squared < sum * 1e-20) {
                break;
            }
        }
        return sum;
    }
}
