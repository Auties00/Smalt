package com.github.auties00.smalt.audio.dts.internal;

import com.github.auties00.smalt.MediaException;

/**
 * Decodes the downmix coefficient and scale codes of the XXCH extension (ETSI TS 102 114 clause 6.5.3) through the
 * downmix tables of D.11.
 *
 * <p>Both codes index every fourth entry of {@code DmixTable}, from index 0 for code 1: the table runs in steps of
 * 0.5 dB from -60 dB to -30 dB, 0.25 dB up to -15 dB and 0.125 dB up to 0 dB, so the codes step by 2, 1 and 0.5 dB.
 * The specification gives the table but not this mapping, which is the one the deployed decoders apply.
 */
final class DownmixCodes {
    /**
     * The lowest {@code DmixTable} index of a downmix scale (-40 dB).
     */
    private static final int MINIMUM_SCALE_INDEX = 40;

    /**
     * The highest {@code DmixTable} index (0 dB).
     */
    private static final int MAXIMUM_INDEX = 240;

    /**
     * Prevents instantiation.
     */
    private DownmixCodes() {
    }

    /**
     * {@return the downmix coefficient of a 7-bit code: a sign bit (set for positive) and an index of 1 to 61, 0 for
     * minus infinity}
     *
     * @param code the 7-bit code of {@code DownMixCoeffs}
     * @throws MediaException.Malformed if the index is outside the table
     */
    static float coefficient(int code) {
        var index = code & 0x3F;
        if (index == 0) {
            return 0;
        }
        var table = (index - 1) * 4;
        if (table > MAXIMUM_INDEX) {
            throw new MediaException.Malformed("invalid DTS downmix coefficient code " + code);
        }
        var magnitude = DtsCoreTables.DMIX[table] / 32768.0f;
        return (code & 0x40) != 0 ? magnitude : -magnitude;
    }

    /**
     * {@return the inverse of the downmix scale of a 6-bit code, from 11 (-40 dB) to 61 (0 dB), from the
     * {@code InvDmixTbl} column of D.11}
     *
     * @param code the 6-bit code of {@code nDmixScaleFactor}
     * @throws MediaException.Malformed if the code is outside that range
     */
    static float inverseScale(int code) {
        var table = (code - 1) * 4;
        if (table < MINIMUM_SCALE_INDEX || table > MAXIMUM_INDEX) {
            throw new MediaException.Malformed("invalid DTS downmix scale code " + code);
        }
        return DtsCoreTables.INV_DMIX[table - MINIMUM_SCALE_INDEX] / 65536.0f;
    }
}
