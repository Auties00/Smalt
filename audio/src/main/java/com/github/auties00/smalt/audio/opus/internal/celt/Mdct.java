/* Ports libopus 1.5.2 (celt/mdct.c). Copyright Xiph.Org Foundation, CSIRO and Jean-Marc Valin; BSD 3-Clause License.
   Modified for Smalt. */
package com.github.auties00.smalt.audio.opus.internal.celt;

/**
 * Computes the windowed forward and inverse MDCTs of CELT through an N/4-point complex FFT.
 */
public final class Mdct {
    private Mdct() {
        throw new AssertionError();
    }

    /**
     * Returns the offset in {@link MdctLookup#trig} of the twiddles of transform {@code shift}.
     *
     * @param l     the lookup
     * @param shift the halving shift
     * @return the twiddle offset
     */
    private static int trigOffset(MdctLookup l, int shift) {
        var N = l.n;
        var trig = 0;
        for (var i = 0; i < shift; i++) {
            N >>= 1;
            trig += N;
        }
        return trig;
    }

    /**
     * Computes a forward MDCT scaled by {@code 4/N}, trashing nothing but the scratch
     * ({@code clt_mdct_forward_c}).
     *
     * <p>Reads {@code N/2 + overlap} samples starting at {@code inOff} and writes {@code N/2}
     * coefficients to {@code out} with a stride of {@code stride}, where
     * {@code N = l.n >> shift}.
     *
     * @param l       the MDCT lookup
     * @param in      the windowed input signal
     * @param inOff   the index of the first input sample
     * @param out     receives the coefficients
     * @param outOff  the index of the first coefficient
     * @param window  the overlap window
     * @param overlap the overlap size
     * @param shift   the halving shift selecting the transform size
     * @param stride  the output stride
     * @param f       scratch of at least {@code N/2} floats
     * @param f2      scratch of at least {@code N/2} floats: the N/4-point FFT in split layout
     */
    public static void cltMdctForward(MdctLookup l, float[] in, int inOff, float[] out, int outOff, float[] window,
                                      int overlap, int shift, int stride, float[] f, float[] f2) {
        var st = l.kfft[shift];
        var scale = st.scale;
        var N = l.n >> shift;
        var trig = l.trig;
        var t = trigOffset(l, shift);
        var N2 = N >> 1;
        var N4 = N >> 2;

        var xp1 = inOff + (overlap >> 1);
        var xp2 = inOff + N2 - 1 + (overlap >> 1);
        var yp = 0;
        var wp1 = overlap >> 1;
        var wp2 = (overlap >> 1) - 1;
        var i = 0;
        for (; i < ((overlap + 3) >> 2); i++) {
            f[yp++] = window[wp2] * in[xp1 + N2] + window[wp1] * in[xp2];
            f[yp++] = window[wp1] * in[xp1] - window[wp2] * in[xp2 - N2];
            xp1 += 2;
            xp2 -= 2;
            wp1 += 2;
            wp2 -= 2;
        }
        wp1 = 0;
        wp2 = overlap - 1;
        for (; i < N4 - ((overlap + 3) >> 2); i++) {
            f[yp++] = in[xp2];
            f[yp++] = in[xp1];
            xp1 += 2;
            xp2 -= 2;
        }
        for (; i < N4; i++) {
            f[yp++] = -(window[wp1] * in[xp1 - N2]) + window[wp2] * in[xp2];
            f[yp++] = window[wp2] * in[xp1] + window[wp1] * in[xp2 + N2];
            xp1 += 2;
            xp2 -= 2;
            wp1 += 2;
            wp2 -= 2;
        }

        var bitrev = st.bitrev;
        yp = 0;
        for (i = 0; i < N4; i++) {
            var t0 = trig[t + i];
            var t1 = trig[t + N4 + i];
            var re = f[yp++];
            var im = f[yp++];
            var yr = re * t0 - im * t1;
            var yi = im * t0 + re * t1;
            var rev = bitrev[i];
            f2[rev] = scale * yr;
            f2[N4 + rev] = scale * yi;
        }

        CeltKernels.INSTANCE.fftSoa(st, f2, 0, N4);

        var yp1 = outOff;
        var yp2 = outOff + stride * (N2 - 1);
        for (i = 0; i < N4; i++) {
            var fr = f2[i];
            var fi = f2[N4 + i];
            var yr = fi * trig[t + N4 + i] - fr * trig[t + i];
            var yi = fr * trig[t + N4 + i] + fi * trig[t + i];
            out[yp1] = yr;
            out[yp2] = yi;
            yp1 += 2 * stride;
            yp2 -= 2 * stride;
        }
    }

    /**
     * Computes an inverse MDCT and the weighted overlap-add of its mirrored halves
     * ({@code clt_mdct_backward_c}).
     *
     * <p>Reads {@code N/2} coefficients from {@code in} with a stride of {@code stride} and writes
     * {@code N/2 + overlap} samples starting at {@code outOff}; the first {@code overlap} samples
     * are mixed with the tail already present there, so the caller keeps the previous frame's
     * overlap in place. The FFT runs in the scratch array, so {@code in} may lie anywhere,
     * including inside the output buffer.
     *
     * @param l       the MDCT lookup
     * @param in      the coefficients
     * @param inOff   the index of the first coefficient
     * @param out     the output signal
     * @param outOff  the index of the first output sample
     * @param window  the overlap window
     * @param overlap the overlap size
     * @param shift   the halving shift selecting the transform size
     * @param stride  the input stride
     * @param f       scratch of at least {@code N/2} floats: the N/4-point FFT in split layout
     */
    public static void cltMdctBackward(MdctLookup l, float[] in, int inOff, float[] out, int outOff, float[] window,
                                       int overlap, int shift, int stride, float[] f) {
        var N = l.n >> shift;
        var trig = l.trig;
        var t = trigOffset(l, shift);
        var N2 = N >> 1;
        var N4 = N >> 2;

        var xp1 = inOff;
        var xp2 = inOff + stride * (N2 - 1);
        var bitrev = l.kfft[shift].bitrev;
        for (var i = 0; i < N4; i++) {
            var rev = bitrev[i];
            var yr = in[xp2] * trig[t + i] + in[xp1] * trig[t + N4 + i];
            var yi = in[xp1] * trig[t + i] - in[xp2] * trig[t + N4 + i];
            f[N4 + rev] = yr;
            f[rev] = yi;
            xp1 += 2 * stride;
            xp2 -= 2 * stride;
        }

        CeltKernels.INSTANCE.fftSoa(l.kfft[shift], f, 0, N4);

        var yp0 = outOff + (overlap >> 1);
        var yp1 = outOff + (overlap >> 1) + N2 - 2;
        for (var i = 0; i < (N4 + 1) >> 1; i++) {
            var k1 = N4 - 1 - i;
            var re = f[N4 + i];
            var im = f[i];
            var t0 = trig[t + i];
            var t1 = trig[t + N4 + i];
            var yrA = re * t0 + im * t1;
            var yiA = re * t1 - im * t0;
            re = f[N4 + k1];
            im = f[k1];
            t0 = trig[t + (N4 - i - 1)];
            t1 = trig[t + (N2 - i - 1)];
            var yrB = re * t0 + im * t1;
            var yiB = re * t1 - im * t0;
            out[yp0] = yrA;
            out[yp1 + 1] = yiA;
            out[yp1] = yrB;
            out[yp0 + 1] = yiB;
            yp0 += 2;
            yp1 -= 2;
        }

        var xp = outOff + overlap - 1;
        var y = outOff;
        var wp1 = 0;
        var wp2 = overlap - 1;
        for (var i = 0; i < overlap / 2; i++) {
            var x1 = out[xp];
            var x2 = out[y];
            out[y++] = window[wp2] * x2 - window[wp1] * x1;
            out[xp--] = window[wp1] * x2 + window[wp2] * x1;
            wp1++;
            wp2--;
        }
    }
}
