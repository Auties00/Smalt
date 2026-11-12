package com.github.auties00.smalt.audio.g726;

/**
 * The ADPCM state machine of ITU-T G.726 (12/90) section 4, shared by the encoder and the decoder.
 *
 * <p>Variables keep the Recommendation's fixed-point representations and widths (Table 6/G.726), held unsigned in
 * an {@code int} with the Recommendation's modular arithmetic, so the coder is bit-exact with the digital test
 * sequences of its Appendix II. One call of {@link #estimate()} per sample precedes {@link #quantize(int)} (encoder)
 * and {@link #update(int)}.
 *
 * <p>Arguments are not checked; values outside the documented ranges give unspecified results. Instances are not
 * thread-safe.
 */
final class G726Codec {
    private static final int[][] THRESHOLDS = {
            {261},
            {8, 218, 331},
            {-124, 80, 178, 246, 300, 349, 400},
            {-122, -16, 68, 139, 198, 250, 298, 339, 378, 413, 445, 475, 502, 528, 553}};
    private static final int[][] DQLN = {
            {116, 365},
            {2048, 135, 273, 373},
            {2048, 4, 135, 213, 273, 323, 373, 425},
            {2048, 4030, 28, 104, 169, 224, 274, 318, 358, 395, 429, 459, 488, 514, 539, 566}};
    private static final int[][] WI = {
            {4074, 439},
            {4092, 30, 137, 582},
            {4084, 18, 41, 64, 112, 198, 355, 1122},
            {14, 14, 24, 39, 40, 41, 58, 100, 141, 179, 219, 280, 358, 440, 529, 696}};
    private static final int[][] FI = {
            {0, 7},
            {0, 1, 2, 7},
            {0, 0, 0, 1, 1, 1, 3, 7},
            {0, 0, 0, 0, 0, 1, 1, 1, 1, 1, 2, 3, 4, 5, 6, 6}};

    /**
     * The code size in bits, from 2 to 5.
     */
    final int bits;
    private final int[] thresholds;
    private final int[] dqln;
    private final int[] wi;
    private final int[] fi;
    private int a1;
    private int a2;
    private final int[] b = new int[6];
    private final int[] dq = new int[6];
    private final int[] bNext = new int[6];
    private int sr1;
    private int sr2;
    private int ap;
    private int dms;
    private int dml;
    private int yu;
    private int yl;
    private int td;
    private int pk1;
    private int pk2;

    /**
     * The signal estimate of the current sample, 15-bit two's complement.
     */
    int se;

    /**
     * The partial signal estimate of the current sample, 15-bit two's complement.
     */
    private int sez;

    /**
     * The quantizer scale factor of the current sample, 13 bits.
     */
    int y;

    /**
     * Constructs a coder in the reset state of section 4.3.
     *
     * @param bits the code size, from 2 to 5
     */
    G726Codec(int bits) {
        this.bits = bits;
        this.thresholds = THRESHOLDS[bits - 2];
        this.dqln = DQLN[bits - 2];
        this.wi = WI[bits - 2];
        this.fi = FI[bits - 2];
        reset();
    }

    /**
     * Puts the coder in the reset state of section 4.3.
     */
    void reset() {
        a1 = 0;
        a2 = 0;
        for (var k = 0; k < 6; k++) {
            b[k] = 0;
            dq[k] = 32;
        }
        sr1 = 32;
        sr2 = 32;
        ap = 0;
        dms = 0;
        dml = 0;
        yu = 544;
        yl = 34816;
        td = 0;
        pk1 = 0;
        pk2 = 0;
    }

    /**
     * Computes the signal estimate and the scale factor of the next sample (FMULT, ACCUM, LIMA, MIX).
     */
    void estimate() {
        var wa1 = fmult(a1, sr1);
        var wa2 = fmult(a2, sr2);
        var sezi = 0;
        for (var k = 0; k < 6; k++) {
            sezi = (sezi + fmult(b[k], dq[k])) & 0xFFFF;
        }
        var sei = (((sezi + wa2) & 0xFFFF) + wa1) & 0xFFFF;
        sez = sezi >> 1;
        se = sei >> 1;
        var al = ap >= 256 ? 64 : ap >> 2;
        var dif = (yu + 16384 - (yl >> 6)) & 16383;
        var difs = dif >> 13;
        var difm = difs == 0 ? dif : (16384 - dif) & 8191;
        var prodm = (difm * al) >> 6;
        var prod = difs == 0 ? prodm : (16384 - prodm) & 16383;
        y = ((yl >> 6) + prod) & 8191;
    }

    /**
     * Quantizes the difference between a sample and the estimate (SUBTA, LOG, SUBTB, QUAN).
     *
     * @param sl the 14-bit sample, from -8192 to 8191
     * @return the code
     */
    int quantize(int sl) {
        var d = ((sl & 0xFFFF) + 65536 - extend(se)) & 0xFFFF;
        var k = level(d);
        var full = (1 << bits) - 1;
        if (d >> 15 != 0) {
            return full - k;
        }
        return k == 0 && bits != 2 ? full : k;
    }

    /**
     * Returns the quantizer level of a difference signal: the number of decision thresholds of the
     * {@code DLN} it reaches.
     *
     * @param d the difference, 16-bit two's complement
     * @return the level, from 0 to the number of thresholds
     */
    int level(int d) {
        var ds = d >> 15;
        var dqm = ds == 0 ? d : (65536 - d) & 32767;
        var exp = Math.max(0, 31 - Integer.numberOfLeadingZeros(dqm));
        var mant = ((dqm << 7) >> exp) & 127;
        var dl = (exp << 7) + mant;
        var dln = (dl + 4096 - (y >> 2)) & 4095;
        var signed = dln >= 2048 ? dln - 4096 : dln;
        var k = 0;
        for (var threshold : thresholds) {
            if (signed >= threshold) {
                k++;
            }
        }
        return k;
    }

    /**
     * Reconstructs the signal of a code and adapts the state (RECONST through TRIGB and the delays).
     *
     * @param code the code, {@link #bits} bits
     * @return the reconstructed signal, 16-bit two's complement
     */
    int update(int code) {
        var half = 1 << (bits - 1);
        var full = (1 << bits) - 1;
        var dqs = code >> (bits - 1);
        var im = dqs == 0 ? code & (half - 1) : (full - code) & (half - 1);
        var dql = (dqln[im] + (y >> 2)) & 4095;
        var dex = (dql >> 7) & 15;
        var dqt = 128 + (dql & 127);
        var dqmag = dql >> 11 == 0 ? (dqt << 7) >> (14 - dex) : 0;

        var dif = ((wi[im] << 5) + 131072 - y) & 131071;
        var difsx = dif >> 16 == 0 ? dif >> 5 : (dif >> 5) + 4096;
        var yut = (y + difsx) & 8191;
        var geul = ((yut + 11264) & 16383) >> 13;
        var gell = ((yut + 15840) & 16383) >> 13;
        var yup = gell == 1 ? 544 : geul == 0 ? 5120 : yut;
        dif = (yup + ((1048576 - yl) >> 6)) & 16383;
        difsx = dif >> 13 == 0 ? dif : dif + 507904;
        var ylp = (yl + difsx) & 524287;

        var dqi = dqs == 0 ? dqmag : (65536 - dqmag) & 0xFFFF;
        var sr = (dqi + extend(se)) & 0xFFFF;
        var dqsez = (dqi + extend(sez)) & 0xFFFF;
        var pk0 = dqsez >> 15;
        var sigpk = dqsez == 0;

        var pks1 = pk0 ^ pk1;
        var pks2 = pk0 ^ pk2;
        var uga2a = pks2 == 0 ? 16384 : 114688;
        int fa1;
        if (a1 >> 15 == 0) {
            fa1 = a1 <= 8191 ? a1 << 2 : 8191 << 2;
        } else {
            fa1 = a1 >= 57345 ? (a1 << 2) & 131071 : 24577 << 2;
        }
        var fa = pks1 == 1 ? fa1 : (131072 - fa1) & 131071;
        var uga2b = (uga2a + fa) & 131071;
        var uga2 = sigpk ? 0 : uga2b >> 16 == 0 ? uga2b >> 7 : (uga2b >> 7) + 64512;
        var ula2 = a2 >> 15 == 0 ? (65536 - (a2 >> 7)) & 0xFFFF : (65536 - ((a2 >> 7) + 65024)) & 0xFFFF;
        var a2t = (a2 + ((uga2 + ula2) & 0xFFFF)) & 0xFFFF;
        int a2p;
        if (a2t >= 32768 && a2t <= 53248) {
            a2p = 53248;
        } else if (a2t >= 12288 && a2t <= 32767) {
            a2p = 12288;
        } else {
            a2p = a2t;
        }

        var uga1 = sigpk ? 0 : pks1 == 0 ? 192 : 65344;
        var ula1 = a1 >> 15 == 0 ? (65536 - (a1 >> 8)) & 0xFFFF : (65536 - ((a1 >> 8) + 65280)) & 0xFFFF;
        var a1t = (a1 + ((uga1 + ula1) & 0xFFFF)) & 0xFFFF;
        var a1ul = (15360 + 65536 - a2p) & 0xFFFF;
        var a1ll = (a2p + 65536 - 15360) & 0xFFFF;
        int a1p;
        if (a1t >= 32768 && a1t <= a1ll) {
            a1p = a1ll;
        } else if (a1t >= a1ul && a1t <= 32767) {
            a1p = a1ul;
        } else {
            a1p = a1t;
        }

        var leak = bits == 5 ? 9 : 8;
        var leakSign = bits == 5 ? 65408 : 65280;
        for (var k = 0; k < 6; k++) {
            var un = dqs ^ (dq[k] >> 10);
            var bn = b[k];
            var ugb = dqmag == 0 ? 0 : un == 0 ? 128 : 65408;
            var ulb = bn >> 15 == 0 ? (65536 - (bn >> leak)) & 0xFFFF : (65536 - ((bn >> leak) + leakSign)) & 0xFFFF;
            bNext[k] = (bn + ((ugb + ulb) & 0xFFFF)) & 0xFFFF;
        }

        var tdp = a2p >= 32768 && a2p < 53760 ? 1 : 0;
        var ylint = yl >> 15;
        var ylfrac = (yl >> 10) & 31;
        var thr1 = (32 + ylfrac) << ylint;
        var thr2 = ylint > 9 ? 31 << 10 : thr1;
        var dqthr = (thr2 + (thr2 >> 1)) >> 1;
        var tr = dqmag > dqthr && td == 1;

        var f = fi[im];
        dif = ((f << 9) + 8192 - dms) & 8191;
        difsx = dif >> 12 == 0 ? dif >> 5 : (dif >> 5) + 3840;
        var dmsp = (difsx + dms) & 4095;
        dif = ((f << 11) + 32768 - dml) & 32767;
        difsx = dif >> 14 == 0 ? dif >> 7 : (dif >> 7) + 16128;
        var dmlp = (difsx + dml) & 16383;
        dif = ((dmsp << 2) + 32768 - dmlp) & 32767;
        var difm = dif >> 14 == 0 ? dif : (32768 - dif) & 16383;
        var dthr = dmlp >> 3;
        var ax = y >= 1536 && difm < dthr && tdp == 0 ? 0 : 1;
        dif = ((ax << 9) + 2048 - ap) & 2047;
        difsx = dif >> 10 == 0 ? dif >> 4 : (dif >> 4) + 896;
        var app = (difsx + ap) & 1023;

        if (tr) {
            a1 = 0;
            a2 = 0;
            for (var k = 0; k < 6; k++) {
                b[k] = 0;
            }
            td = 0;
            ap = 256;
        } else {
            a1 = a1p;
            a2 = a2p;
            System.arraycopy(bNext, 0, b, 0, 6);
            td = tdp;
            ap = app;
        }
        var srs = sr >> 15;
        var srmag = srs == 0 ? sr : (65536 - sr) & 32767;
        sr2 = sr1;
        sr1 = toFloat(srmag, srs);
        System.arraycopy(dq, 0, dq, 1, 5);
        dq[0] = toFloat(dqmag, dqs);
        pk2 = pk1;
        pk1 = pk0;
        dms = dmsp;
        dml = dmlp;
        yu = yup;
        yl = ylp;
        return sr;
    }

    /**
     * Sign-extends a 15-bit two's complement value to 16 bits.
     *
     * @param value the 15-bit value
     * @return the value, 16-bit two's complement
     */
    private static int extend(int value) {
        return value >> 14 == 0 ? value : 32768 + value;
    }

    /**
     * Multiplies a 16-bit predictor coefficient by an 11-bit floating-point signal (FMULT).
     *
     * @param an the coefficient, 16-bit two's complement
     * @param srn the signal, the floating-point form of {@link #toFloat(int, int)}
     * @return the product, 16-bit two's complement
     */
    private static int fmult(int an, int srn) {
        var ans = an >> 15;
        var anmag = ans == 0 ? an >> 2 : (16384 - (an >> 2)) & 8191;
        var anexp = 32 - Integer.numberOfLeadingZeros(anmag);
        var anmant = anmag == 0 ? 1 << 5 : (anmag << 6) >> anexp;
        var srns = srn >> 10;
        var srnexp = (srn >> 6) & 15;
        var srnmant = srn & 63;
        var was = srns ^ ans;
        var waexp = srnexp + anexp;
        var wamant = ((srnmant * anmant) + 48) >> 4;
        var wamag = waexp <= 26 ? (wamant << 7) >> (26 - waexp) : ((wamant << 7) << (waexp - 26)) & 32767;
        return was == 0 ? wamag : (65536 - wamag) & 0xFFFF;
    }

    /**
     * Converts a magnitude and sign to the 11-bit floating-point form of the predictor's delay lines (FLOATA,
     * FLOATB).
     *
     * @param magnitude the magnitude, 15 bits
     * @param sign the sign bit
     * @return the sign, 4-bit exponent and 6-bit mantissa
     */
    private static int toFloat(int magnitude, int sign) {
        var exp = 32 - Integer.numberOfLeadingZeros(magnitude);
        var mant = magnitude == 0 ? 1 << 5 : (magnitude << 6) >> exp;
        return (sign << 10) + (exp << 6) + mant;
    }
}
