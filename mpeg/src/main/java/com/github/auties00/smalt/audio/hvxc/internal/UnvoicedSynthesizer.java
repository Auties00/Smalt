/* Ports hvxcSynUVDec.c, hvxcUVExtGenDec.c and IPC_posfil_u, IPC_posfil_u_LD of hvxcFltDec.c of the ISO/IEC 14496-3
 * reference software (MPEG-4 Audio, subpart 2, HVXC). Copyright Sony Corporation; ISO/IEC 14496-3 reference software
 * licence. Modified for Smalt. */
package com.github.auties00.smalt.audio.hvxc.internal;

import static com.github.auties00.smalt.audio.hvxc.internal.HvxcDsp.FRAME;
import static com.github.auties00.smalt.audio.hvxc.internal.HvxcDsp.LD_LEN;
import static com.github.auties00.smalt.audio.hvxc.internal.HvxcDsp.ORDER;

/**
 * Synthesizes the unvoiced component of HVXC speech (ISO/IEC 14496-3 subclause 2.5.7): the stochastic excitation of
 * the time domain decoder, faded at voicing transitions, through the LPC synthesis filter and its postfilter.
 *
 * <p>Instances keep the state of one decoder and are not thread-safe.
 */
final class UnvoicedSynthesizer {
    /**
     * The fade-in and the fade-out of the excitation at a transition ({@code w_celp_up}, {@code w_celp_down}).
     */
    private static final float[] FADE_UP = new float[FRAME + LD_LEN];

    private static final float[] FADE_DOWN = new float[FRAME + LD_LEN];

    static {
        var st1 = FRAME / 2;
        var st2 = st1 + 30;
        for (var i = st1; i < st2; i++) {
            FADE_UP[i] = (float) (i - st1 + 1) / 30.0f;
        }
        for (var i = st2; i < FRAME + LD_LEN; i++) {
            FADE_UP[i] = 1.0f;
        }
        st1 = FRAME / 2 - 30;
        st2 = FRAME / 2;
        for (var i = 0; i < st1; i++) {
            FADE_DOWN[i] = 1.0f;
        }
        for (var i = st1; i < st2; i++) {
            FADE_DOWN[i] = (float) (1. - (float) (i - st1) / 30.0f);
        }
    }

    private final boolean lowDelay;

    private final boolean postFilter;

    private final float[] oldResidual = new float[FRAME / 2];

    private final float[] synthesisMemory = new float[ORDER + 1];

    private final float[] pfMem1 = new float[ORDER + 1];

    private final float[] pfMem2 = new float[ORDER + 1];

    private float pfHpm;

    private float pfOldGain;

    private final float[] pfFirOld = new float[ORDER + 1];

    private final float[] pfIirOld = new float[ORDER + 1];

    private final float[] highPassState = new float[4];

    private final float[] excitation = new float[FRAME + LD_LEN];

    private final float[] suv = new float[FRAME];

    private final float[][] lsp = new float[2][ORDER + 1];

    private final float[][] alpha = new float[2][ORDER + 1];

    private final double[] work1 = new double[11];

    private final double[] work2 = new double[11];

    private final double[] work3 = new double[11];

    private final float[] fac05 = new float[ORDER + 1];

    private final float[] fac08 = new float[ORDER + 1];

    private final float[][] alphaFir = new float[2][ORDER + 1];

    private final float[][] alphaIir = new float[2][ORDER + 1];

    private final float[] mem1Old = new float[ORDER + 1];

    private final float[] mem2Old = new float[ORDER + 1];

    private final float[] resi = new float[FRAME];

    private final float[] pos = new float[FRAME];

    private final float[] rest = new float[FRAME];

    private final float[] synHp = new float[FRAME];

    /**
     * Constructs the synthesizer of one decoder.
     *
     * @param lowDelay whether the decoder runs in the low delay mode
     * @param postFilter whether the postfilter runs
     */
    UnvoicedSynthesizer(boolean lowDelay, boolean postFilter) {
        this.lowDelay = lowDelay;
        this.postFilter = postFilter;
        HvxcFilters.powers(fac05, fac08);
    }

    /**
     * Synthesizes one frame of unvoiced speech ({@code td_synt}).
     *
     * @param residual the excitation of the frame, 160 samples
     * @param vuv the voicing of the previous and the current frame, 0 for unvoiced
     * @param qLsp the LSPs of the previous and the current frame, halved
     * @param output receives 160 samples
     */
    void synthesize(float[] residual, int[] vuv, float[][] qLsp, float[] output) {
        var vUv = (vuv[0] != 0 ? 2 : 0) + (vuv[1] != 0 ? 1 : 0);
        System.arraycopy(oldResidual, 0, excitation, 0, FRAME / 2);
        System.arraycopy(residual, 0, excitation, FRAME / 2, FRAME / 2);
        System.arraycopy(residual, FRAME / 2, oldResidual, 0, FRAME / 2);
        var shift = 0;
        if (lowDelay) {
            System.arraycopy(residual, FRAME / 2, excitation, FRAME, LD_LEN);
            shift = LD_LEN;
        }
        for (var i = 0; i < FRAME; i++) {
            suv[i] = switch (vUv) {
                case 0 -> excitation[i + shift];
                case 1 -> excitation[i + shift] * FADE_DOWN[i + shift];
                case 2 -> excitation[i + shift] * FADE_UP[i + shift];
                default -> 0.0f;
            };
        }
        for (var j = 0; j < 2; j++) {
            lsp[j][0] = 0.0f;
            System.arraycopy(qLsp[j], 0, lsp[j], 1, ORDER);
            HvxcDsp.lspToLpc(lsp[j], alpha[j], work1, work2, work3);
            alpha[j][0] = 1.0f;
            for (var i = 1; i < ORDER + 1; i++) {
                alpha[j][i] = -alpha[j][i];
            }
        }
        var first = lowDelay ? FRAME / 2 - LD_LEN : FRAME / 2;
        var mem = synthesisMemory;
        for (var i = 0; i < FRAME; i++) {
            var a = alpha[i < first ? 0 : 1];
            var out2 = 0.0f;
            for (var j = ORDER; j > 0; j--) {
                out2 += mem[j] * a[j];
                mem[j] = mem[j - 1];
            }
            out2 = suv[i] - out2;
            mem[1] = out2;
            output[i] = out2;
        }
        if (postFilter) {
            postFilter(output, first);
        }
    }

    /**
     * Runs the postfilter of unvoiced speech over one frame in place ({@code IPC_posfil_u}, {@code IPC_posfil_u_LD},
     * ISO/IEC 14496-3 Annex 2.B, informative).
     *
     * @param first the length of the first subframe
     */
    private void postFilter(float[] syn, int first) {
        for (var j = 0; j < 2; j++) {
            for (var i = 0; i <= ORDER; i++) {
                alphaFir[j][i] = alpha[j][i] * fac05[i];
                alphaIir[j][i] = alpha[j][i] * fac08[i];
            }
        }
        var start = 0;
        for (var ii = 0; ii < 2; ii++) {
            var length = ii == 0 ? first : FRAME - first;
            for (var i = 0; i < length; i++) {
                synHp[i] = (float) (syn[start + i] - 0.1 * pfHpm);
                pfHpm = syn[start + i];
            }
            HvxcFilters.postFilterSubframe(synHp, 0, length, start, alphaFir[ii], alphaIir[ii], pfMem1, pfMem2,
                    pfFirOld, pfIirOld, mem1Old, mem2Old, resi, pos, rest);
            start += length;
        }
        pfOldGain = HvxcFilters.applyGain(syn, pos, rest, 20, pfOldGain);
        HvxcFilters.iir2(syn, highPassState, HvxcFilters.UNVOICED_HIGH_PASS);
    }
}
