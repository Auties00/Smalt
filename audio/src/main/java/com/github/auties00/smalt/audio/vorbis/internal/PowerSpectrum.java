package com.github.auties00.smalt.audio.vorbis.internal;

/**
 * Computes the power spectrum of a real block with a mixed-radix real FFT, in the layout the
 * tone masking reads.
 *
 * <p>An instance keeps work buffers and is not thread-safe.
 *
 * @implNote This implementation ports the forward path of libvorbis 1.3.7's {@code smallft.c}
 * ({@code drfti1}, {@code drftf1}, {@code dradf2}, {@code dradf4}; BSD-3, itself derived from
 * FFTPACK), restricted to the radix 2 and 4 stages that power-of-two Vorbis block sizes use, so
 * the spectrum is computed with the same float operations as libvorbis.
 */
public final class PowerSpectrum {
    /**
     * The transform size.
     */
    private final int n;

    /**
     * The twiddle factors.
     */
    private final float[] wa;

    /**
     * The factorization: size, factor count, factors.
     */
    private final int[] ifac = new int[32];

    /**
     * The data buffer.
     */
    private final float[] c;

    /**
     * The work buffer.
     */
    private final float[] ch;

    /**
     * The output.
     */
    private final float[] out;

    /**
     * Creates a transform ({@code drft_init}).
     *
     * @param n the block size, a power of two
     */
    public PowerSpectrum(int n) {
        this.n = n;
        this.wa = new float[n];
        this.c = new float[n];
        this.ch = new float[n];
        this.out = new float[n / 2];
        initialize();
    }

    /**
     * Factors the size and computes the twiddles ({@code drfti1}).
     */
    private void initialize() {
        int[] ntryh = {4, 2, 3, 5};
        var tpi = 6.28318530717958648f;
        var ntry = 0;
        var j = -1;
        var nl = n;
        var nf = 0;
        outer:
        while (true) {
            j++;
            if (j < 4) {
                ntry = ntryh[j];
            } else {
                ntry += 2;
            }
            while (true) {
                var nq = nl / ntry;
                var nr = nl - ntry * nq;
                if (nr != 0) {
                    continue outer;
                }
                nf++;
                ifac[nf + 1] = ntry;
                nl = nq;
                if (ntry == 2 && nf != 1) {
                    for (var i = 1; i < nf; i++) {
                        var ib = nf - i + 1;
                        ifac[ib + 1] = ifac[ib];
                    }
                    ifac[2] = 2;
                }
                if (nl == 1) {
                    break outer;
                }
            }
        }
        ifac[0] = n;
        ifac[1] = nf;
        var argh = tpi / n;
        var is = 0;
        var nfm1 = nf - 1;
        var l1 = 1;
        if (nfm1 == 0) {
            return;
        }
        for (var k1 = 0; k1 < nfm1; k1++) {
            var ip = ifac[k1 + 2];
            var ld = 0;
            var l2 = l1 * ip;
            var ido = n / l2;
            var ipm = ip - 1;
            for (j = 0; j < ipm; j++) {
                ld += l1;
                var i = is;
                var argld = (float) ld * argh;
                var fi = 0.f;
                for (var ii = 2; ii < ido; ii += 2) {
                    fi += 1.f;
                    var arg = fi * argld;
                    wa[i++] = (float) Math.cos(arg);
                    wa[i++] = (float) Math.sin(arg);
                }
                is += ido;
            }
            l1 = l2;
        }
    }

    /**
     * Transforms a block.
     *
     * @param data the {@code n} samples
     * @return element 0 holds the real DC value, element {@code k} from 1 to {@code n/2 - 1} the
     *         power {@code re^2 + im^2} of bin {@code k}; the array is reused by the next call
     */
    public float[] compute(float[] data) {
        System.arraycopy(data, 0, c, 0, n);
        forward();
        out[0] = c[0];
        for (var j = 1; j < n - 1; j += 2) {
            out[(j + 1) >> 1] = c[j] * c[j] + c[j + 1] * c[j + 1];
        }
        return out;
    }

    /**
     * Runs the stages ({@code drftf1}).
     */
    private void forward() {
        var nf = ifac[1];
        var na = 1;
        var l2 = n;
        var iw = n;
        for (var k1 = 0; k1 < nf; k1++) {
            var kh = nf - k1;
            var ip = ifac[kh + 1];
            var l1 = l2 / ip;
            var ido = n / l2;
            iw -= (ip - 1) * ido;
            na = 1 - na;
            if (ip == 4) {
                var ix2 = iw + ido;
                var ix3 = ix2 + ido;
                if (na != 0) {
                    radix4(ido, l1, ch, c, iw, ix2, ix3);
                } else {
                    radix4(ido, l1, c, ch, iw, ix2, ix3);
                }
            } else if (ip == 2) {
                if (na != 0) {
                    radix2(ido, l1, ch, c, iw);
                } else {
                    radix2(ido, l1, c, ch, iw);
                }
            } else {
                throw new IllegalStateException("non power-of-two Vorbis block size " + n);
            }
            l2 = l1;
        }
        if (na == 1) {
            return;
        }
        System.arraycopy(ch, 0, c, 0, n);
    }

    /**
     * Returns a twiddle: libvorbis passes each stage a twiddle pointer one entry before the
     * stage's first twiddle, so offset {@code o} and index {@code i} read entry {@code o + i - 1}.
     *
     * @param offset the stage offset in the twiddle array
     * @param index  the index
     * @return the twiddle
     */
    private float twiddle(int offset, int index) {
        return wa[offset + index - 1];
    }

    /**
     * Runs a radix-2 stage ({@code dradf2}).
     *
     * @param ido the stage length
     * @param l1  the stage count
     * @param cc  the input
     * @param ch  the output
     * @param w1  the twiddle offset
     */
    private void radix2(int ido, int l1, float[] cc, float[] ch, int w1) {
        int t0;
        int t1;
        int t2;
        int t3;
        int t4;
        int t5;
        int t6;
        t1 = 0;
        t0 = t2 = l1 * ido;
        t3 = ido << 1;
        for (var k = 0; k < l1; k++) {
            ch[t1 << 1] = cc[t1] + cc[t2];
            ch[(t1 << 1) + t3 - 1] = cc[t1] - cc[t2];
            t1 += ido;
            t2 += ido;
        }
        if (ido < 2) {
            return;
        }
        if (ido != 2) {
            t1 = 0;
            t2 = t0;
            for (var k = 0; k < l1; k++) {
                t3 = t2;
                t4 = (t1 << 1) + (ido << 1);
                t5 = t1;
                t6 = t1 + t1;
                for (var i = 2; i < ido; i += 2) {
                    t3 += 2;
                    t4 -= 2;
                    t5 += 2;
                    t6 += 2;
                    var tr2 = twiddle(w1, i - 2) * cc[t3 - 1] + twiddle(w1, i - 1) * cc[t3];
                    var ti2 = twiddle(w1, i - 2) * cc[t3] - twiddle(w1, i - 1) * cc[t3 - 1];
                    ch[t6] = cc[t5] + ti2;
                    ch[t4] = ti2 - cc[t5];
                    ch[t6 - 1] = cc[t5 - 1] + tr2;
                    ch[t4 - 1] = cc[t5 - 1] - tr2;
                }
                t1 += ido;
                t2 += ido;
            }
            if (ido % 2 == 1) {
                return;
            }
        }
        t3 = t2 = (t1 = ido) - 1;
        t2 += t0;
        for (var k = 0; k < l1; k++) {
            ch[t1] = -cc[t2];
            ch[t1 - 1] = cc[t3];
            t1 += ido << 1;
            t2 += ido;
            t3 += ido;
        }
    }

    /**
     * Runs a radix-4 stage ({@code dradf4}).
     *
     * @param ido the stage length
     * @param l1  the stage count
     * @param cc  the input
     * @param ch  the output
     * @param w1  the first twiddle offset
     * @param w2  the second twiddle offset
     * @param w3  the third twiddle offset
     */
    private void radix4(int ido, int l1, float[] cc, float[] ch, int w1, int w2, int w3) {
        var hsqt2 = .70710678118654752f;
        var t0 = l1 * ido;
        var t1 = t0;
        var t4 = t1 << 1;
        var t2 = t1 + (t1 << 1);
        var t3 = 0;
        int t5;
        int t6;
        for (var k = 0; k < l1; k++) {
            var tr1 = cc[t1] + cc[t2];
            var tr2 = cc[t3] + cc[t4];
            ch[t5 = t3 << 2] = tr1 + tr2;
            ch[(ido << 2) + t5 - 1] = tr2 - tr1;
            ch[(t5 += (ido << 1)) - 1] = cc[t3] - cc[t4];
            ch[t5] = cc[t2] - cc[t1];
            t1 += ido;
            t2 += ido;
            t3 += ido;
            t4 += ido;
        }
        if (ido < 2) {
            return;
        }
        if (ido != 2) {
            t1 = 0;
            for (var k = 0; k < l1; k++) {
                t2 = t1;
                t4 = t1 << 2;
                t5 = (t6 = ido << 1) + t4;
                for (var i = 2; i < ido; i += 2) {
                    t3 = (t2 += 2);
                    t4 += 2;
                    t5 -= 2;
                    t3 += t0;
                    var cr2 = twiddle(w1, i - 2) * cc[t3 - 1] + twiddle(w1, i - 1) * cc[t3];
                    var ci2 = twiddle(w1, i - 2) * cc[t3] - twiddle(w1, i - 1) * cc[t3 - 1];
                    t3 += t0;
                    var cr3 = twiddle(w2, i - 2) * cc[t3 - 1] + twiddle(w2, i - 1) * cc[t3];
                    var ci3 = twiddle(w2, i - 2) * cc[t3] - twiddle(w2, i - 1) * cc[t3 - 1];
                    t3 += t0;
                    var cr4 = twiddle(w3, i - 2) * cc[t3 - 1] + twiddle(w3, i - 1) * cc[t3];
                    var ci4 = twiddle(w3, i - 2) * cc[t3] - twiddle(w3, i - 1) * cc[t3 - 1];
                    var tr1 = cr2 + cr4;
                    var tr4 = cr4 - cr2;
                    var ti1 = ci2 + ci4;
                    var ti4 = ci2 - ci4;
                    var ti2 = cc[t2] + ci3;
                    var ti3 = cc[t2] - ci3;
                    var tr2 = cc[t2 - 1] + cr3;
                    var tr3 = cc[t2 - 1] - cr3;
                    ch[t4 - 1] = tr1 + tr2;
                    ch[t4] = ti1 + ti2;
                    ch[t5 - 1] = tr3 - ti4;
                    ch[t5] = tr4 - ti3;
                    ch[t4 + t6 - 1] = ti4 + tr3;
                    ch[t4 + t6] = tr4 + ti3;
                    ch[t5 + t6 - 1] = tr2 - tr1;
                    ch[t5 + t6] = ti1 - ti2;
                }
                t1 += ido;
            }
            if ((ido & 1) != 0) {
                return;
            }
        }
        t2 = (t1 = t0 + ido - 1) + (t0 << 1);
        t3 = ido << 2;
        t4 = ido;
        t5 = ido << 1;
        t6 = ido;
        for (var k = 0; k < l1; k++) {
            var ti1 = -hsqt2 * (cc[t1] + cc[t2]);
            var tr1 = hsqt2 * (cc[t1] - cc[t2]);
            ch[t4 - 1] = tr1 + cc[t6 - 1];
            ch[t4 + t5 - 1] = cc[t6 - 1] - tr1;
            ch[t4] = ti1 - cc[t1 + t0];
            ch[t4 + t5] = ti1 + cc[t1 + t0];
            t1 += ido;
            t2 += ido;
            t4 += t3;
            t6 += ido;
        }
    }
}
