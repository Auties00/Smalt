/* Ports the ISO/IEC 23003-3:2019 reference software (usac_imdct.c fd2buffer). Copyright Fraunhofer IIS,
   VoiceAge Corp. and ISO/IEC 2008; ISO/IEC reference software licence (use in products conforming to
   ISO/IEC 23003). Modified for Smalt. */
package com.github.auties00.smalt.audio.aac.internal.usac;

import java.util.Arrays;

/**
 * Runs the frequency domain synthesis of USAC: the inverse MDCT of every window, the windowing with the
 * transitions of ISO/IEC 23003-3 subclause 7.9, and overlap-add ({@code fd2buffer}).
 *
 * <p>The overlap of a channel holds the unwindowed second half of the previous frame's transform; its
 * falling window is applied once the current window sequence is known. Instances hold scratch memory and are
 * not thread-safe.
 */
final class FdSynthesis {
    private final int length;
    private final Imdct imdct;
    private final float[] transform;
    private final float[] buffer;

    /**
     * Constructs the synthesis of a frame length.
     *
     * @param length the core frame length, 768 or 1024
     * @param imdct the inverse transforms
     */
    FdSynthesis(int length, Imdct imdct) {
        this.length = length;
        this.imdct = imdct;
        this.transform = new float[2 * length];
        this.buffer = new float[2 * length];
    }

    /**
     * Synthesizes one frame.
     *
     * @param spectrum the spectrum, window after window
     * @param sequence the window sequence
     * @param shape the window shape of the frame
     * @param previousShape the window shape of the previous frame
     * @param afterLpd whether the previous frame was coded in the linear prediction domain
     * @param fac the time domain forward aliasing cancellation signal, {@code 2 * lfac} samples, or
     * {@code null}
     * @param overlap the overlap of the channel, at least {@code length} samples; updated
     * @param out receives the frame's {@code length} samples
     * @param outOffset the index of the first output sample
     */
    void synthesize(float[] spectrum, int sequence, int shape, int previousShape, boolean afterLpd, float[] fac,
                    float[] overlap, float[] out, int outOffset) {
        var n = length;
        var nShort = n / 8;
        var flat = (n - nShort) / 2;
        var transition = nShort;
        var lfac = 0;
        if (afterLpd) {
            lfac = sequence == FdChannel.EIGHT_SHORT ? n / 16 : n / 8;
            flat = (n - 2 * lfac) / 2;
            transition = 2 * lfac;
        }
        var kbd = shape == 1;
        var previousKbd = previousShape == 1;
        var longWindow = UsacTables.window(n, kbd);
        var longPrevious = UsacTables.window(n, previousKbd);
        var shortWindow = UsacTables.window(nShort, kbd);
        var transitionWindow = UsacTables.window(transition, previousKbd);
        var t = transform;
        var o = buffer;
        System.arraycopy(overlap, 0, o, 0, n);
        Arrays.fill(o, n, 2 * n, 0f);
        switch (sequence) {
            case FdChannel.ONLY_LONG, FdChannel.LONG_START -> {
                imdct.inverse(spectrum, 0, n, t, 0);
                for (var i = 0; i < n; i++) {
                    o[i] = o[i] * longPrevious[n - 1 - i] + t[i] * longPrevious[i];
                }
                System.arraycopy(t, n, o, n, n);
            }
            case FdChannel.LONG_STOP, FdChannel.STOP_START -> {
                imdct.inverse(spectrum, 0, n, t, 0);
                for (var i = 0; i < transition; i++) {
                    t[flat + i] *= transitionWindow[i];
                }
                if (fac != null) {
                    Arrays.fill(t, 0, flat + lfac, 0f);
                    for (var i = 0; i < 2 * lfac; i++) {
                        t[flat + lfac + i] += fac[i];
                    }
                    Arrays.fill(o, flat + lfac, flat + lfac + flat + lfac, 0f);
                } else {
                    Arrays.fill(t, 0, flat, 0f);
                    for (var i = 0; i < transition; i++) {
                        o[flat + i] *= transitionWindow[transition - 1 - i];
                    }
                    Arrays.fill(o, flat + transition, flat + transition + flat, 0f);
                }
                for (var i = 0; i < n; i++) {
                    o[i] += t[i];
                }
                System.arraycopy(t, n, o, n, n);
            }
            default -> {
                var p = flat;
                var previous = transitionWindow;
                var previousLength = transition;
                for (var k = 0; k < 8; k++) {
                    imdct.inverse(spectrum, k * nShort, nShort, t, 0);
                    for (var i = 0; i < nShort; i++) {
                        t[i] *= previous[i];
                    }
                    if (k == 0) {
                        if (fac != null) {
                            Arrays.fill(t, 0, lfac, 0f);
                            Arrays.fill(o, p + lfac, p + lfac + flat + lfac, 0f);
                        } else {
                            Arrays.fill(o, p + previousLength, p + previousLength + flat, 0f);
                            for (var i = 0; i < previousLength; i++) {
                                o[p + i] *= previous[previousLength - 1 - i];
                            }
                        }
                    }
                    for (var i = 0; i < nShort; i++) {
                        o[p + i] += t[i];
                    }
                    if (k < 7) {
                        for (var i = 0; i < nShort; i++) {
                            o[p + nShort + i] += t[nShort + i] * shortWindow[nShort - 1 - i];
                        }
                    } else {
                        for (var i = 0; i < nShort; i++) {
                            o[p + nShort + i] += t[nShort + i];
                        }
                    }
                    p += nShort;
                    previous = shortWindow;
                    previousLength = nShort;
                }
                if (fac != null) {
                    for (var i = 0; i < 2 * lfac; i++) {
                        o[flat + lfac + i] += fac[i];
                    }
                }
                Arrays.fill(o, 2 * n - flat, 2 * n, 0f);
            }
        }
        System.arraycopy(o, 0, out, outOffset, n);
        System.arraycopy(o, n, overlap, 0, n);
    }
}
