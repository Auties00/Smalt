/* Ports opencore-amr 0.1.6 (dec_gain.cpp, d_gain_c.cpp, d_gain_p.cpp). Copyright PacketVideo; Apache License 2.0.
   Modified for Smalt. */
package com.github.auties00.smalt.audio.amr.internal.nb;

import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.add;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.lMult;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.lShl;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.lShr;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.mpy3216;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.mult;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.round;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.shl;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.shrR;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.sub;

/**
 * Decodes the pitch and codebook gains of AMR-NB subframes from their quantization indices.
 *
 * <p>Every index a frame can carry addresses inside its table, so no input can read outside it.
 */
final class GainDecoder {
    /**
     * The largest index into the {@code MR475} gain table (pairs of pitch and code gains).
     */
    private static final short MR475_INDEX_LIMIT = Constants.MR475_VQ_SIZE * 4 - 2;

    /**
     * Prevents instantiation.
     */
    private GainDecoder() {
    }

    /**
     * Decodes the jointly quantized pitch and codebook gains of {@code MR475}, {@code MR515},
     * {@code MR59}, {@code MR67}, {@code MR74} and {@code MR102} ({@code Dec_gain}).
     *
     * @param pred the gain predictor, updated
     * @param mode the codec mode, one of the {@link Mode} constants
     * @param index the gain index
     * @param code the innovative codevector of the subframe
     * @param evenSubfr 1 for an even subframe, which selects the {@code MR475} half
     * @param scratch scratch for 4 predictor outputs
     * @param overflow the flag raised on saturation
     * @return the pitch gain (upper 16 bits) and the codebook gain (lower 16 bits)
     */
    static int decGain(GcPred pred, int mode, short index, short[] code, int evenSubfr, short[] scratch, Flag overflow) {
        short gainPit;
        short gCode;
        short quaEnerMR122;
        short quaEner;
        index = shl(index, (short) 2);
        if (mode == Mode.MR102 || mode == Mode.MR74 || mode == Mode.MR67) {
            var table = GainTables.TABLE_GAIN_HIGHRATES;
            gainPit = table[index];
            gCode = table[index + 1];
            quaEnerMR122 = table[index + 2];
            quaEner = table[index + 3];
        } else if (mode == Mode.MR475) {
            index = (short) (index + ((1 ^ evenSubfr) << 1));
            if (index > MR475_INDEX_LIMIT) {
                index = MR475_INDEX_LIMIT;
            }
            var table = GainTables.TABLE_GAIN_MR475;
            gainPit = table[index];
            gCode = table[index + 1];
            var log = FixedMath.log2(gCode);
            var exp = (short) (log >> 16);
            var frac = (short) log;
            exp -= 12;
            var temp1 = shrR(frac, (short) 5, overflow);
            var temp2 = shl(exp, (short) 10);
            quaEnerMR122 = add(temp1, temp2, overflow);
            var product = mpy3216(exp, frac, (short) 24660, overflow);
            product = lShl(product, (short) 13);
            quaEner = round(product, overflow);
        } else {
            var table = GainTables.TABLE_GAIN_LOWRATES;
            gainPit = table[index];
            gCode = table[index + 1];
            quaEnerMR122 = table[index + 2];
            quaEner = table[index + 3];
        }
        pred.gcPred(mode, code, 0, scratch, 0, overflow);
        var exp = scratch[0];
        var frac = scratch[1];
        var gcode0 = (short) FixedMath.pow2((short) 14, frac, overflow);
        var product = lMult(gCode, gcode0, overflow);
        product = lShr(product, (short) (10 - exp));
        var gainCode = (short) (product >> 16);
        pred.update(quaEnerMR122, quaEner);
        return (gainPit << 16) | (gainCode & 0xffff);
    }

    /**
     * Decodes the scalar quantized codebook gain of {@code MR795} and {@code MR122}
     * ({@code d_gain_code}).
     *
     * @param pred the gain predictor, updated
     * @param mode the codec mode, one of the {@link Mode} constants
     * @param index the gain index
     * @param code the innovative codevector of the subframe
     * @param scratch scratch for 4 predictor outputs
     * @param overflow the flag raised on saturation
     * @return the codebook gain
     */
    static short decGainCode(GcPred pred, int mode, short index, short[] code, short[] scratch, Flag overflow) {
        pred.gcPred(mode, code, 0, scratch, 0, overflow);
        var exp = scratch[0];
        var frac = scratch[1];
        index &= 31;
        var position = index + (index << 1);
        var table = GainTables.QUA_GAIN_CODE;
        short gainCode;
        if (sub((short) mode, (short) Mode.MR122, overflow) == 0) {
            var gcode0 = (short) FixedMath.pow2(exp, frac, overflow);
            gcode0 = shl(gcode0, (short) 4);
            gainCode = shl(mult(gcode0, table[position], overflow), (short) 1);
        } else {
            var gcode0 = (short) FixedMath.pow2((short) 14, frac, overflow);
            var product = lMult(table[position], gcode0, overflow);
            product = lShr(product, sub((short) 9, exp, overflow));
            gainCode = (short) (product >> 16);
        }
        pred.update(table[position + 1], table[position + 2]);
        return gainCode;
    }

    /**
     * Decodes the scalar quantized pitch gain of {@code MR795} and {@code MR122}
     * ({@code d_gain_pitch}).
     *
     * @param mode the codec mode, one of the {@link Mode} constants
     * @param index the 4-bit gain index
     * @return the pitch gain, Q14
     */
    static short decGainPitch(int mode, short index) {
        var gain = GainTables.QUA_GAIN_PITCH[index];
        if (mode == Mode.MR122) {
            gain &= (short) 0xFFFC;
        }
        return gain;
    }
}
