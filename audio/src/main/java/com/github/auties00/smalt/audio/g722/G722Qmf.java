package com.github.auties00.smalt.audio.g722;

/**
 * The 24-tap quadrature mirror filters of ITU-T G.722 (11/88) section 5, which split 16 kHz samples into the two
 * 8 kHz sub-bands and merge them back.
 *
 * <p>Arguments are not checked; values outside the documented ranges give unspecified results. Instances are not
 * thread-safe.
 */
final class G722Qmf {
    private static final int[] H = {3, -11, -11, 53, 12, -156, 32, 362, -210, -805, 951, 3876, 3876, 951, -805,
            -210, 362, 32, -156, 12, 53, -11, -11, 3};

    /**
     * The transmit filter's input, newest first.
     */
    private final int[] x = new int[24];

    /**
     * The receive filter's difference signals, newest first.
     */
    private final int[] xd = new int[12];

    /**
     * The receive filter's sum signals, newest first.
     */
    private final int[] xs = new int[12];

    /**
     * The lower sub-band sample of the last {@link #analyze} call.
     */
    int xl;

    /**
     * The higher sub-band sample of the last {@link #analyze} call.
     */
    int xh;

    /**
     * The first output sample of the last {@link #synthesize} call.
     */
    int out1;

    /**
     * The second output sample of the last {@link #synthesize} call.
     */
    int out2;

    /**
     * Splits two consecutive input samples into one sample of each sub-band, left in {@link #xl} and {@link #xh}.
     *
     * @param first the earlier sample, 15-bit, from -16384 to 16383
     * @param second the later sample, 15-bit
     */
    void analyze(int first, int second) {
        System.arraycopy(x, 0, x, 2, 22);
        x[0] = second;
        x[1] = first;
        long xa = 0;
        long xb = 0;
        for (var i = 0; i < 24; i += 2) {
            xa += (long) H[i] * x[i];
            xb += (long) H[i + 1] * x[i + 1];
        }
        xl = (int) Math.max(-16384, Math.min(16383, (xa + xb) >> 13));
        xh = (int) Math.max(-16384, Math.min(16383, (xa - xb) >> 13));
    }

    /**
     * Merges one sample of each sub-band into two output samples, left in {@link #out1} and {@link #out2}.
     *
     * @param rl the lower sub-band sample, from -16384 to 16383
     * @param rh the higher sub-band sample, from -16384 to 16383
     */
    void synthesize(int rl, int rh) {
        System.arraycopy(xd, 0, xd, 1, 11);
        System.arraycopy(xs, 0, xs, 1, 11);
        xd[0] = rl - rh;
        xs[0] = rl + rh;
        long wd1 = 0;
        long wd2 = 0;
        for (var i = 0; i < 12; i++) {
            wd1 += (long) H[2 * i] * xd[i];
            wd2 += (long) H[2 * i + 1] * xs[i];
        }
        out1 = (int) Math.max(-16384, Math.min(16383, wd1 >> 12));
        out2 = (int) Math.max(-16384, Math.min(16383, wd2 >> 12));
    }
}
