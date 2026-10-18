/* Ports webrtc-audio-processing 2.1 (common_audio/third_party/ooura/fft_size_128/ooura_fft.cc,
   modules/audio_processing/aec3/aec3_fft.cc). Copyright Takuya Ooura, 1996-2001, and (c) 2011, The WebRTC project
   authors; Ooura notice and BSD 3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.audio.voice.internal.aec3;

import java.util.Arrays;

/**
 * Computes the 128-point real transforms of AEC3, with the rectangular, Hanning and
 * square-root Hanning windowings its components use.
 *
 * <p>A spectrum is written to, or read from, the 65 bins at {@code offset} of a pair of real and
 * imaginary arrays, so that ring buffers of spectra can be flat arrays. The forward transform is
 * {@code X[k] = sum x[j] exp(2 pi i j k / 128)}; the inverse returns 64 times the signal.
 * Instances are not thread-safe.
 *
 * @implNote This implementation ports the C path of WebRTC's
 * {@code common_audio/third_party/ooura/fft_size_128/ooura_fft.cc} (Ooura's split-radix
 * real transform specialized to 128 points, with the constant tables of
 * {@code ooura_fft_tables_common.h}) and {@code modules/audio_processing/aec3/aec3_fft.cc}.
 */
final class Aec3Fft {
    /**
     * The twiddle factors and real-transform cosines.
     *
     * @implNote This implementation copies {@code rdft_w} of {@code ooura_fft_tables_common.h}.
     */
    private static final float[] RDFT_W = {
            1.0000000000f, 0.0000000000f, 0.7071067691f, 0.7071067691f, 0.9238795638f,
            0.3826834559f, 0.3826834559f, 0.9238795638f, 0.9807852507f, 0.1950903237f,
            0.5555702448f, 0.8314695954f, 0.8314695954f, 0.5555702448f, 0.1950903237f,
            0.9807852507f, 0.9951847196f, 0.0980171412f, 0.6343933344f, 0.7730104327f,
            0.8819212914f, 0.4713967443f, 0.2902846634f, 0.9569403529f, 0.9569403529f,
            0.2902846634f, 0.4713967443f, 0.8819212914f, 0.7730104327f, 0.6343933344f,
            0.0980171412f, 0.9951847196f, 0.7071067691f, 0.4993977249f, 0.4975923598f,
            0.4945882559f, 0.4903926253f, 0.4850156307f, 0.4784701765f, 0.4707720280f,
            0.4619397819f, 0.4519946277f, 0.4409606457f, 0.4288643003f, 0.4157347977f,
            0.4016037583f, 0.3865052164f, 0.3704755902f, 0.3535533845f, 0.3357794881f,
            0.3171966672f, 0.2978496552f, 0.2777851224f, 0.2570513785f, 0.2356983721f,
            0.2137775421f, 0.1913417280f, 0.1684449315f, 0.1451423317f, 0.1214900985f,
            0.0975451618f, 0.0733652338f, 0.0490085706f, 0.0245338380f
    };

    /**
     * The third-power twiddles of the first half of each radix-4 group.
     *
     * @implNote This implementation copies {@code rdft_wk3ri_first} of
     * {@code ooura_fft_tables_common.h}.
     */
    private static final float[] WK3RI_FIRST = {
            1.000000000f, 0.000000000f, 0.382683456f, 0.923879564f,
            0.831469536f, 0.555570245f, -0.195090353f, 0.980785251f,
            0.956940353f, 0.290284693f, 0.098017156f, 0.995184720f,
            0.634393334f, 0.773010492f, -0.471396863f, 0.881921172f
    };

    /**
     * The third-power twiddles of the second half of each radix-4 group.
     *
     * @implNote This implementation copies {@code rdft_wk3ri_second} of
     * {@code ooura_fft_tables_common.h}.
     */
    private static final float[] WK3RI_SECOND = {
            -0.707106769f, 0.707106769f, -0.923879564f, -0.382683456f,
            -0.980785251f, 0.195090353f, -0.555570245f, -0.831469536f,
            -0.881921172f, 0.471396863f, -0.773010492f, -0.634393334f,
            -0.995184720f, -0.098017156f, -0.290284693f, -0.956940353f
    };

    /**
     * The 64-point Hanning window of the zero-padded transform.
     *
     * @implNote This implementation copies {@code kHanning64} of {@code aec3_fft.cc}.
     */
    private static final float[] HANNING_64 = {
            0.f, 0.00248461f, 0.00991376f, 0.0222136f, 0.03926189f,
            0.06088921f, 0.08688061f, 0.11697778f, 0.15088159f, 0.1882551f,
            0.22872687f, 0.27189467f, 0.31732949f, 0.36457977f, 0.41317591f,
            0.46263495f, 0.51246535f, 0.56217185f, 0.61126047f, 0.65924333f,
            0.70564355f, 0.75f, 0.79187184f, 0.83084292f, 0.86652594f,
            0.89856625f, 0.92664544f, 0.95048443f, 0.96984631f, 0.98453864f,
            0.99441541f, 0.99937846f, 0.99937846f, 0.99441541f, 0.98453864f,
            0.96984631f, 0.95048443f, 0.92664544f, 0.89856625f, 0.86652594f,
            0.83084292f, 0.79187184f, 0.75f, 0.70564355f, 0.65924333f,
            0.61126047f, 0.56217185f, 0.51246535f, 0.46263495f, 0.41317591f,
            0.36457977f, 0.31732949f, 0.27189467f, 0.22872687f, 0.1882551f,
            0.15088159f, 0.11697778f, 0.08688061f, 0.06088921f, 0.03926189f,
            0.0222136f, 0.00991376f, 0.00248461f, 0.f
    };

    /**
     * The 128-point square-root Hanning window of the overlapped transforms.
     *
     * @implNote This implementation copies {@code kSqrtHanning128} of {@code aec3_fft.cc}.
     */
    static final float[] SQRT_HANNING_128 = {
            0.00000000000000f, 0.02454122852291f, 0.04906767432742f, 0.07356456359967f,
            0.09801714032956f, 0.12241067519922f, 0.14673047445536f, 0.17096188876030f,
            0.19509032201613f, 0.21910124015687f, 0.24298017990326f, 0.26671275747490f,
            0.29028467725446f, 0.31368174039889f, 0.33688985339222f, 0.35989503653499f,
            0.38268343236509f, 0.40524131400499f, 0.42755509343028f, 0.44961132965461f,
            0.47139673682600f, 0.49289819222978f, 0.51410274419322f, 0.53499761988710f,
            0.55557023301960f, 0.57580819141785f, 0.59569930449243f, 0.61523159058063f,
            0.63439328416365f, 0.65317284295378f, 0.67155895484702f, 0.68954054473707f,
            0.70710678118655f, 0.72424708295147f, 0.74095112535496f, 0.75720884650648f,
            0.77301045336274f, 0.78834642762661f, 0.80320753148064f, 0.81758481315158f,
            0.83146961230255f, 0.84485356524971f, 0.85772861000027f, 0.87008699110871f,
            0.88192126434835f, 0.89322430119552f, 0.90398929312344f, 0.91420975570353f,
            0.92387953251129f, 0.93299279883474f, 0.94154406518302f, 0.94952818059304f,
            0.95694033573221f, 0.96377606579544f, 0.97003125319454f, 0.97570213003853f,
            0.98078528040323f, 0.98527764238894f, 0.98917650996478f, 0.99247953459871f,
            0.99518472667220f, 0.99729045667869f, 0.99879545620517f, 0.99969881869620f,
            1.00000000000000f, 0.99969881869620f, 0.99879545620517f, 0.99729045667869f,
            0.99518472667220f, 0.99247953459871f, 0.98917650996478f, 0.98527764238894f,
            0.98078528040323f, 0.97570213003853f, 0.97003125319454f, 0.96377606579544f,
            0.95694033573221f, 0.94952818059304f, 0.94154406518302f, 0.93299279883474f,
            0.92387953251129f, 0.91420975570353f, 0.90398929312344f, 0.89322430119552f,
            0.88192126434835f, 0.87008699110871f, 0.85772861000027f, 0.84485356524971f,
            0.83146961230255f, 0.81758481315158f, 0.80320753148064f, 0.78834642762661f,
            0.77301045336274f, 0.75720884650648f, 0.74095112535496f, 0.72424708295147f,
            0.70710678118655f, 0.68954054473707f, 0.67155895484702f, 0.65317284295378f,
            0.63439328416365f, 0.61523159058063f, 0.59569930449243f, 0.57580819141785f,
            0.55557023301960f, 0.53499761988710f, 0.51410274419322f, 0.49289819222978f,
            0.47139673682600f, 0.44961132965461f, 0.42755509343028f, 0.40524131400499f,
            0.38268343236509f, 0.35989503653499f, 0.33688985339222f, 0.31368174039889f,
            0.29028467725446f, 0.26671275747490f, 0.24298017990326f, 0.21910124015687f,
            0.19509032201613f, 0.17096188876030f, 0.14673047445536f, 0.12241067519922f,
            0.09801714032956f, 0.07356456359967f, 0.04906767432742f, 0.02454122852291f
    };

    /**
     * The scratch buffer of one transform.
     */
    private final float[] buffer = new float[Aec3.FFT_LENGTH];

    /**
     * Creates a transform.
     */
    Aec3Fft() {
    }

    /**
     * Transforms 128 samples and stores the spectrum.
     *
     * @param x the samples; overwritten with the packed transform
     * @param re receives the real parts
     * @param im receives the imaginary parts
     * @param offset the index of bin 0 in {@code re} and {@code im}
     */
    static void fft(float[] x, float[] re, float[] im, int offset) {
        forward(x);
        re[offset] = x[0];
        re[offset + Aec3.FFT_LENGTH_BY_2] = x[1];
        im[offset] = 0;
        im[offset + Aec3.FFT_LENGTH_BY_2] = 0;
        for (int k = 1, j = 2; k < Aec3.FFT_LENGTH_BY_2; ++k) {
            re[offset + k] = x[j++];
            im[offset + k] = x[j++];
        }
    }

    /**
     * Transforms a spectrum back into 128 samples, unscaled.
     *
     * @param re the real parts
     * @param im the imaginary parts
     * @param offset the index of bin 0 in {@code re} and {@code im}
     * @param x receives 64 times the signal
     */
    static void ifft(float[] re, float[] im, int offset, float[] x) {
        x[0] = re[offset];
        x[1] = re[offset + Aec3.FFT_LENGTH_BY_2];
        for (int k = 1, j = 2; k < Aec3.FFT_LENGTH_BY_2; ++k) {
            x[j++] = re[offset + k];
            x[j++] = im[offset + k];
        }
        inverse(x);
    }

    /**
     * Transforms 64 samples multiplied by the 64-point Hanning window and preceded by 64 zeros
     * ({@code ZeroPaddedFft} with the Hanning window).
     *
     * @param x the samples
     * @param xOffset the index of the first sample
     * @param out receives the spectrum
     */
    void zeroPaddedFft(float[] x, int xOffset, FftData out) {
        var fft = buffer;
        Arrays.fill(fft, 0, Aec3.FFT_LENGTH_BY_2, 0.f);
        for (var i = 0; i < Aec3.FFT_LENGTH_BY_2; i++) {
            fft[Aec3.FFT_LENGTH_BY_2 + i] = x[xOffset + i] * HANNING_64[i];
        }
        fft(fft, out.re, out.im, 0);
    }

    /**
     * Transforms 64 new samples preceded by 64 old ones, with a rectangular window.
     *
     * @param x the new samples
     * @param xOffset the index of the first new sample
     * @param xOld the old samples
     * @param oldOffset the index of the first old sample
     * @param re receives the real parts
     * @param im receives the imaginary parts
     * @param offset the index of bin 0 in {@code re} and {@code im}
     */
    void paddedFft(float[] x, int xOffset, float[] xOld, int oldOffset, float[] re, float[] im, int offset) {
        var fft = buffer;
        System.arraycopy(xOld, oldOffset, fft, 0, Aec3.FFT_LENGTH_BY_2);
        System.arraycopy(x, xOffset, fft, Aec3.FFT_LENGTH_BY_2, Aec3.FFT_LENGTH_BY_2);
        fft(fft, re, im, offset);
    }

    /**
     * Transforms 64 new samples preceded by 64 old ones, square-root Hanning windowed, and
     * then replaces the old samples with the new ones.
     *
     * @param v the new samples
     * @param vOffset the index of the first new sample
     * @param vOld the old samples; receives a copy of the new ones
     * @param out receives the spectrum
     */
    void windowedPaddedFft(float[] v, int vOffset, float[] vOld, FftData out) {
        var fft = buffer;
        for (var i = 0; i < Aec3.FFT_LENGTH_BY_2; i++) {
            fft[i] = vOld[i] * SQRT_HANNING_128[i];
        }
        for (var i = 0; i < Aec3.FFT_LENGTH_BY_2; i++) {
            fft[Aec3.FFT_LENGTH_BY_2 + i] = v[vOffset + i] * SQRT_HANNING_128[Aec3.FFT_LENGTH_BY_2 + i];
        }
        fft(fft, out.re, out.im, 0);
        System.arraycopy(v, vOffset, vOld, 0, Aec3.FFT_LENGTH_BY_2);
    }

    /**
     * Computes the forward complex transform and the real post-processing in place,
     * {@code OouraFft::Fft} in the reference.
     *
     * @param a the 128 samples
     */
    static void forward(float[] a) {
        bitReverse(a);
        complexForward(a);
        realForwardPost(a);
        var xi = a[0] - a[1];
        a[0] += a[1];
        a[1] = xi;
    }

    /**
     * Computes the real pre-processing and the inverse complex transform in place,
     * {@code OouraFft::InverseFft} in the reference.
     *
     * @param a the packed spectrum
     */
    static void inverse(float[] a) {
        a[1] = 0.5f * (a[0] - a[1]);
        a[0] -= a[1];
        realInversePre(a);
        bitReverse(a);
        complexInverse(a);
    }

    /**
     * Permutes 64 interleaved complex values into bit-reversed order, {@code bitrv2_128}.
     *
     * @param a the data
     */
    private static void bitReverse(float[] a) {
        for (var k = 0; k < 4; k++) {
            var ipk = (k & 1) * 64 + (k >> 1) * 32;
            for (var j = 0; j < k; j++) {
                var ipj = (j & 1) * 64 + (j >> 1) * 32;
                var j1 = 2 * j + ipk;
                var k1 = 2 * k + ipj;
                swap(a, j1, k1);
                j1 += 8;
                k1 += 16;
                swap(a, j1, k1);
                j1 += 8;
                k1 -= 8;
                swap(a, j1, k1);
                j1 += 8;
                k1 += 16;
                swap(a, j1, k1);
            }
            var j1 = 2 * k + 8 + ipk;
            var k1 = j1 + 8;
            swap(a, j1, k1);
        }
    }

    /**
     * Swaps the complex values at two float indices.
     *
     * @param a the data
     * @param i the index of the real part of the first value
     * @param j the index of the real part of the second value
     */
    private static void swap(float[] a, int i, int j) {
        var xr = a[i];
        var xi = a[i + 1];
        a[i] = a[j];
        a[i + 1] = a[j + 1];
        a[j] = xr;
        a[j + 1] = xi;
    }

    /**
     * Runs the forward complex butterflies, {@code cftfsub_128}.
     *
     * @param a the bit-reversed data
     */
    private static void complexForward(float[] a) {
        firstStage(a);
        middleStage(a);
        var l = 32;
        for (var j = 0; j < l; j += 2) {
            var j1 = j + l;
            var j2 = j1 + l;
            var j3 = j2 + l;
            var x0r = a[j] + a[j1];
            var x0i = a[j + 1] + a[j1 + 1];
            var x1r = a[j] - a[j1];
            var x1i = a[j + 1] - a[j1 + 1];
            var x2r = a[j2] + a[j3];
            var x2i = a[j2 + 1] + a[j3 + 1];
            var x3r = a[j2] - a[j3];
            var x3i = a[j2 + 1] - a[j3 + 1];
            a[j] = x0r + x2r;
            a[j + 1] = x0i + x2i;
            a[j2] = x0r - x2r;
            a[j2 + 1] = x0i - x2i;
            a[j1] = x1r - x3i;
            a[j1 + 1] = x1i + x3r;
            a[j3] = x1r + x3i;
            a[j3 + 1] = x1i - x3r;
        }
    }

    /**
     * Runs the inverse complex butterflies, {@code cftbsub_128}.
     *
     * @param a the bit-reversed data
     */
    private static void complexInverse(float[] a) {
        firstStage(a);
        middleStage(a);
        var l = 32;
        for (var j = 0; j < l; j += 2) {
            var j1 = j + l;
            var j2 = j1 + l;
            var j3 = j2 + l;
            var x0r = a[j] + a[j1];
            var x0i = -a[j + 1] - a[j1 + 1];
            var x1r = a[j] - a[j1];
            var x1i = -a[j + 1] + a[j1 + 1];
            var x2r = a[j2] + a[j3];
            var x2i = a[j2 + 1] + a[j3 + 1];
            var x3r = a[j2] - a[j3];
            var x3i = a[j2 + 1] - a[j3 + 1];
            a[j] = x0r + x2r;
            a[j + 1] = x0i - x2i;
            a[j2] = x0r - x2r;
            a[j2 + 1] = x0i + x2i;
            a[j1] = x1r - x3i;
            a[j1 + 1] = x1i - x3r;
            a[j3] = x1r + x3i;
            a[j3 + 1] = x1i + x3r;
        }
    }

    /**
     * Runs the first radix-4 stage, {@code cft1st_128_C}.
     *
     * @param a the data
     */
    private static void firstStage(float[] a) {
        var x0r = a[0] + a[2];
        var x0i = a[1] + a[3];
        var x1r = a[0] - a[2];
        var x1i = a[1] - a[3];
        var x2r = a[4] + a[6];
        var x2i = a[5] + a[7];
        var x3r = a[4] - a[6];
        var x3i = a[5] - a[7];
        a[0] = x0r + x2r;
        a[1] = x0i + x2i;
        a[4] = x0r - x2r;
        a[5] = x0i - x2i;
        a[2] = x1r - x3i;
        a[3] = x1i + x3r;
        a[6] = x1r + x3i;
        a[7] = x1i - x3r;
        var wk1r = RDFT_W[2];
        x0r = a[8] + a[10];
        x0i = a[9] + a[11];
        x1r = a[8] - a[10];
        x1i = a[9] - a[11];
        x2r = a[12] + a[14];
        x2i = a[13] + a[15];
        x3r = a[12] - a[14];
        x3i = a[13] - a[15];
        a[8] = x0r + x2r;
        a[9] = x0i + x2i;
        a[12] = x2i - x0i;
        a[13] = x0r - x2r;
        x0r = x1r - x3i;
        x0i = x1i + x3r;
        a[10] = wk1r * (x0r - x0i);
        a[11] = wk1r * (x0r + x0i);
        x0r = x3i + x1r;
        x0i = x3r - x1i;
        a[14] = wk1r * (x0i - x0r);
        a[15] = wk1r * (x0i + x0r);
        var k1 = 0;
        for (var j = 16; j < 128; j += 16) {
            k1 += 2;
            var k2 = 2 * k1;
            var wk2r = RDFT_W[k1];
            var wk2i = RDFT_W[k1 + 1];
            wk1r = RDFT_W[k2];
            var wk1i = RDFT_W[k2 + 1];
            var wk3r = WK3RI_FIRST[k1];
            var wk3i = WK3RI_FIRST[k1 + 1];
            x0r = a[j] + a[j + 2];
            x0i = a[j + 1] + a[j + 3];
            x1r = a[j] - a[j + 2];
            x1i = a[j + 1] - a[j + 3];
            x2r = a[j + 4] + a[j + 6];
            x2i = a[j + 5] + a[j + 7];
            x3r = a[j + 4] - a[j + 6];
            x3i = a[j + 5] - a[j + 7];
            a[j] = x0r + x2r;
            a[j + 1] = x0i + x2i;
            x0r -= x2r;
            x0i -= x2i;
            a[j + 4] = wk2r * x0r - wk2i * x0i;
            a[j + 5] = wk2r * x0i + wk2i * x0r;
            x0r = x1r - x3i;
            x0i = x1i + x3r;
            a[j + 2] = wk1r * x0r - wk1i * x0i;
            a[j + 3] = wk1r * x0i + wk1i * x0r;
            x0r = x1r + x3i;
            x0i = x1i - x3r;
            a[j + 6] = wk3r * x0r - wk3i * x0i;
            a[j + 7] = wk3r * x0i + wk3i * x0r;
            wk1r = RDFT_W[k2 + 2];
            wk1i = RDFT_W[k2 + 3];
            wk3r = WK3RI_SECOND[k1];
            wk3i = WK3RI_SECOND[k1 + 1];
            x0r = a[j + 8] + a[j + 10];
            x0i = a[j + 9] + a[j + 11];
            x1r = a[j + 8] - a[j + 10];
            x1i = a[j + 9] - a[j + 11];
            x2r = a[j + 12] + a[j + 14];
            x2i = a[j + 13] + a[j + 15];
            x3r = a[j + 12] - a[j + 14];
            x3i = a[j + 13] - a[j + 15];
            a[j + 8] = x0r + x2r;
            a[j + 9] = x0i + x2i;
            x0r -= x2r;
            x0i -= x2i;
            a[j + 12] = -wk2i * x0r - wk2r * x0i;
            a[j + 13] = -wk2i * x0i + wk2r * x0r;
            x0r = x1r - x3i;
            x0i = x1i + x3r;
            a[j + 10] = wk1r * x0r - wk1i * x0i;
            a[j + 11] = wk1r * x0i + wk1i * x0r;
            x0r = x1r + x3i;
            x0i = x1i - x3r;
            a[j + 14] = wk3r * x0r - wk3i * x0i;
            a[j + 15] = wk3r * x0i + wk3i * x0r;
        }
    }

    /**
     * Runs the middle radix-4 stage, {@code cftmdl_128_C}.
     *
     * @param a the data
     */
    private static void middleStage(float[] a) {
        var l = 8;
        var m = 32;
        for (var j0 = 0; j0 < l; j0 += 2) {
            var j1 = j0 + 8;
            var j2 = j0 + 16;
            var j3 = j0 + 24;
            var x0r = a[j0] + a[j1];
            var x0i = a[j0 + 1] + a[j1 + 1];
            var x1r = a[j0] - a[j1];
            var x1i = a[j0 + 1] - a[j1 + 1];
            var x2r = a[j2] + a[j3];
            var x2i = a[j2 + 1] + a[j3 + 1];
            var x3r = a[j2] - a[j3];
            var x3i = a[j2 + 1] - a[j3 + 1];
            a[j0] = x0r + x2r;
            a[j0 + 1] = x0i + x2i;
            a[j2] = x0r - x2r;
            a[j2 + 1] = x0i - x2i;
            a[j1] = x1r - x3i;
            a[j1 + 1] = x1i + x3r;
            a[j3] = x1r + x3i;
            a[j3 + 1] = x1i - x3r;
        }
        var wk1r = RDFT_W[2];
        for (var j0 = m; j0 < l + m; j0 += 2) {
            var j1 = j0 + 8;
            var j2 = j0 + 16;
            var j3 = j0 + 24;
            var x0r = a[j0] + a[j1];
            var x0i = a[j0 + 1] + a[j1 + 1];
            var x1r = a[j0] - a[j1];
            var x1i = a[j0 + 1] - a[j1 + 1];
            var x2r = a[j2] + a[j3];
            var x2i = a[j2 + 1] + a[j3 + 1];
            var x3r = a[j2] - a[j3];
            var x3i = a[j2 + 1] - a[j3 + 1];
            a[j0] = x0r + x2r;
            a[j0 + 1] = x0i + x2i;
            a[j2] = x2i - x0i;
            a[j2 + 1] = x0r - x2r;
            x0r = x1r - x3i;
            x0i = x1i + x3r;
            a[j1] = wk1r * (x0r - x0i);
            a[j1 + 1] = wk1r * (x0r + x0i);
            x0r = x3i + x1r;
            x0i = x3r - x1i;
            a[j3] = wk1r * (x0i - x0r);
            a[j3 + 1] = wk1r * (x0i + x0r);
        }
        var k1 = 0;
        var m2 = 2 * m;
        for (var k = m2; k < 128; k += m2) {
            k1 += 2;
            var k2 = 2 * k1;
            var wk2r = RDFT_W[k1];
            var wk2i = RDFT_W[k1 + 1];
            wk1r = RDFT_W[k2];
            var wk1i = RDFT_W[k2 + 1];
            var wk3r = WK3RI_FIRST[k1];
            var wk3i = WK3RI_FIRST[k1 + 1];
            for (var j0 = k; j0 < l + k; j0 += 2) {
                var j1 = j0 + 8;
                var j2 = j0 + 16;
                var j3 = j0 + 24;
                var x0r = a[j0] + a[j1];
                var x0i = a[j0 + 1] + a[j1 + 1];
                var x1r = a[j0] - a[j1];
                var x1i = a[j0 + 1] - a[j1 + 1];
                var x2r = a[j2] + a[j3];
                var x2i = a[j2 + 1] + a[j3 + 1];
                var x3r = a[j2] - a[j3];
                var x3i = a[j2 + 1] - a[j3 + 1];
                a[j0] = x0r + x2r;
                a[j0 + 1] = x0i + x2i;
                x0r -= x2r;
                x0i -= x2i;
                a[j2] = wk2r * x0r - wk2i * x0i;
                a[j2 + 1] = wk2r * x0i + wk2i * x0r;
                x0r = x1r - x3i;
                x0i = x1i + x3r;
                a[j1] = wk1r * x0r - wk1i * x0i;
                a[j1 + 1] = wk1r * x0i + wk1i * x0r;
                x0r = x1r + x3i;
                x0i = x1i - x3r;
                a[j3] = wk3r * x0r - wk3i * x0i;
                a[j3 + 1] = wk3r * x0i + wk3i * x0r;
            }
            wk1r = RDFT_W[k2 + 2];
            wk1i = RDFT_W[k2 + 3];
            wk3r = WK3RI_SECOND[k1];
            wk3i = WK3RI_SECOND[k1 + 1];
            for (var j0 = k + m; j0 < l + (k + m); j0 += 2) {
                var j1 = j0 + 8;
                var j2 = j0 + 16;
                var j3 = j0 + 24;
                var x0r = a[j0] + a[j1];
                var x0i = a[j0 + 1] + a[j1 + 1];
                var x1r = a[j0] - a[j1];
                var x1i = a[j0 + 1] - a[j1 + 1];
                var x2r = a[j2] + a[j3];
                var x2i = a[j2 + 1] + a[j3 + 1];
                var x3r = a[j2] - a[j3];
                var x3i = a[j2 + 1] - a[j3 + 1];
                a[j0] = x0r + x2r;
                a[j0 + 1] = x0i + x2i;
                x0r -= x2r;
                x0i -= x2i;
                a[j2] = -wk2i * x0r - wk2r * x0i;
                a[j2 + 1] = -wk2i * x0i + wk2r * x0r;
                x0r = x1r - x3i;
                x0i = x1i + x3r;
                a[j1] = wk1r * x0r - wk1i * x0i;
                a[j1 + 1] = wk1r * x0i + wk1i * x0r;
                x0r = x1r + x3i;
                x0i = x1i - x3r;
                a[j3] = wk3r * x0r - wk3i * x0i;
                a[j3 + 1] = wk3r * x0i + wk3i * x0r;
            }
        }
    }

    /**
     * Converts the complex transform into the real transform, {@code rftfsub_128_C}.
     *
     * @param a the data
     */
    private static void realForwardPost(float[] a) {
        for (int j1 = 1, j2 = 2; j2 < 64; j1 += 1, j2 += 2) {
            var k2 = 128 - j2;
            var k1 = 32 - j1;
            var wkr = 0.5f - RDFT_W[32 + k1];
            var wki = RDFT_W[32 + j1];
            var xr = a[j2] - a[k2];
            var xi = a[j2 + 1] + a[k2 + 1];
            var yr = wkr * xr - wki * xi;
            var yi = wkr * xi + wki * xr;
            a[j2] -= yr;
            a[j2 + 1] -= yi;
            a[k2] += yr;
            a[k2 + 1] -= yi;
        }
    }

    /**
     * Converts the real spectrum into a complex one before the inverse butterflies,
     * {@code rftbsub_128_C}.
     *
     * @param a the data
     */
    private static void realInversePre(float[] a) {
        a[1] = -a[1];
        for (int j1 = 1, j2 = 2; j2 < 64; j1 += 1, j2 += 2) {
            var k2 = 128 - j2;
            var k1 = 32 - j1;
            var wkr = 0.5f - RDFT_W[32 + k1];
            var wki = RDFT_W[32 + j1];
            var xr = a[j2] - a[k2];
            var xi = a[j2 + 1] + a[k2 + 1];
            var yr = wkr * xr + wki * xi;
            var yi = wkr * xi - wki * xr;
            a[j2] = a[j2] - yr;
            a[j2 + 1] = yi - a[j2 + 1];
            a[k2] = yr + a[k2];
            a[k2 + 1] = yi - a[k2 + 1];
        }
        a[65] = -a[65];
    }
}
