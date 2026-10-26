/* Ports the ISO/IEC 23003-3:2019 reference software (sony_pvcdec.c, sony_pvclib.c and usac_pvcGetData of
   ct_envextr.c). Copyright Sony Corporation and ISO/IEC 2008; ISO/IEC reference software licence (use in
   products conforming to ISO/IEC 23003). Modified for Smalt. */
package com.github.auties00.smalt.audio.aac.internal.usac;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.audio.aac.internal.AacBitReader;

import java.util.Arrays;

/**
 * Decodes predictive vector coding ({@code pvcEnvelope()}, ISO/IEC 23003-3 subclause 7.5.6), which predicts the
 * SBR envelope of a speech frame from the energies of the core band.
 *
 * <p>Instances are mutable and not thread-safe.
 */
final class EsbrPvc {
    private static final int TIME_SLOTS = 16;
    private static final float LOW_LIMIT = 0.1f;
    private static final int LOW_GROUPS = 3;

    private static final float[] SCALING_MODE1 = {1.0f / 256.0f, 1.0f / 256.0f, 1.0f / 128.0f, 1.0f / 2.0f};
    private static final float[] SCALING_MODE2 = {1.0f / 128.0f, 1.0f / 128.0f, 1.0f / 64.0f, 1.0f / 1.0f};

    /**
     * The {@code bs_varlen} of the frame being decoded, in time slots.
     */
    int currentVarlen;

    /**
     * The {@code bs_varlen} read with the frame, which extends it into the next frame.
     */
    int nextVarlen;

    private int mode;
    private int division;
    private int noiseMode;
    private final int[] ids = new int[TIME_SLOTS];
    private int previousId;
    private int previousMode;
    private int previousBand = 0xFF;
    private boolean previousFlag;
    private int previousRate = 0xFF;
    private final float[] energies = new float[(TIME_SLOTS + 16 - 1) * 3];
    private final float[] smoothed = new float[3];
    private final float[] high = new float[8];

    /**
     * Updates the state when a header switches predictive vector coding on or off.
     *
     * @param pvcMode the header's {@code bs_pvc_mode}
     */
    void updateMode(int pvcMode) {
        if (previousMode == 0 && pvcMode != 0) {
            previousId = 0;
        }
        previousMode = pvcMode;
    }

    /**
     * Reads {@code pvcEnvelope()}.
     *
     * @param reader the bit source
     * @param pvcMode the header's {@code bs_pvc_mode}
     * @param independent the frame's {@code usacIndependencyFlag}
     * @throws MediaException.Unsupported if the frame uses the reserved mode 3
     */
    void read(AacBitReader reader, int pvcMode, boolean independent) {
        mode = pvcMode;
        if (pvcMode == 3) {
            throw new MediaException.Unsupported("usac pvc mode 3", "USAC predictive vector coding mode 3 is reserved");
        }
        var idBits = 7;
        division = reader.read(3);
        noiseMode = reader.read(1);
        if (division <= 3) {
            var reuse = !independent && reader.readFlag();
            ids[0] = reuse ? previousId : reader.read(idBits);
            var k = 1;
            var sum = 0;
            for (var i = 0; i < division; i++) {
                int bits;
                if (sum >= 13) {
                    bits = 1;
                } else if (sum >= 11) {
                    bits = 2;
                } else if (sum >= 7) {
                    bits = 3;
                } else {
                    bits = 4;
                }
                var length = reader.read(bits) + 1;
                sum += length;
                for (var j = 1; j < length; j++, k++) {
                    if (k >= TIME_SLOTS) {
                        throw new MediaException.Malformed("USAC predictive vector coding lengths exceed the frame");
                    }
                    ids[k] = ids[k - 1];
                }
                if (k >= TIME_SLOTS) {
                    throw new MediaException.Malformed("USAC predictive vector coding lengths exceed the frame");
                }
                ids[k++] = reader.read(idBits);
            }
            for (; k < TIME_SLOTS; k++) {
                ids[k] = ids[k - 1];
            }
        } else {
            int grids;
            int fixed;
            switch (division) {
                case 4 -> {
                    grids = 2;
                    fixed = 8;
                }
                case 5 -> {
                    grids = 4;
                    fixed = 4;
                }
                case 6 -> {
                    grids = 8;
                    fixed = 2;
                }
                default -> {
                    grids = 16;
                    fixed = 1;
                }
            }
            var grid = independent || reader.readFlag();
            ids[0] = grid ? reader.read(idBits) : previousId;
            var k = 1;
            for (var j = 1; j < fixed; j++, k++) {
                ids[k] = ids[k - 1];
            }
            for (var i = 1; i < grids; i++) {
                if (reader.readFlag()) {
                    ids[k++] = reader.read(idBits);
                } else {
                    ids[k] = ids[k - 1];
                    k++;
                }
                for (var j = 1; j < fixed; j++, k++) {
                    ids[k] = ids[k - 1];
                }
            }
        }
    }

    /**
     * Predicts the high band energies of one frame ({@code pvc_decode_frame}).
     *
     * @param active whether the frame uses predictive vector coding
     * @param rate the number of QMF slots per time slot, 2 or 4
     * @param band the first subband of the replicated range
     * @param firstSlot the first time slot the frame predicts
     * @param low the core band energies, {@code 64 / rate} subbands per time slot for 16 time slots
     * @param out receives the predicted energies, 64 subbands per time slot for 16 time slots
     */
    void decode(boolean active, int rate, int band, int firstSlot, float[] low, float[] out) {
        if (active) {
            if (band > 64 / rate) {
                throw new MediaException.Malformed("USAC predictive vector coding starts above the core band");
            }
            int highGroups;
            int width;
            byte[] table1;
            byte[] table2;
            float[] scaling;
            int split0;
            int split1;
            float[] smoothing;
            if (mode == 1) {
                highGroups = 8;
                width = 8 / rate;
                table1 = EsbrTables.TABLE1_MODE1;
                table2 = EsbrTables.TABLE2_MODE1;
                scaling = SCALING_MODE1;
                split0 = 17;
                split1 = 68;
                smoothing = noiseMode != 0 ? EsbrTables.SMOOTHING_SHORT_MODE1 : EsbrTables.SMOOTHING_LONG_MODE1;
            } else {
                highGroups = 6;
                width = 12 / rate;
                table1 = EsbrTables.TABLE1_MODE2;
                table2 = EsbrTables.TABLE2_MODE2;
                scaling = SCALING_MODE2;
                split0 = 16;
                split1 = 52;
                smoothing = noiseMode != 0 ? EsbrTables.SMOOTHING_SHORT_MODE2 : EsbrTables.SMOOTHING_LONG_MODE2;
            }
            previousId = ids[TIME_SLOTS - 1];
            group(rate, band, low, firstSlot);
            for (var t = 0; t < TIME_SLOTS; t++) {
                for (var g = 0; g < LOW_GROUPS; g++) {
                    var sum = 0.0f;
                    for (var ti = 0; ti < smoothing.length; ti++) {
                        sum += energies[(t + 16 - 1 - ti) * 3 + g] * smoothing[ti];
                    }
                    smoothed[g] = sum;
                }
                var id2 = ids[t];
                var id1 = id2 < split0 ? 0 : id2 < split1 ? 1 : 2;
                var base1 = id1 * LOW_GROUPS * highGroups;
                var base2 = id2 * highGroups;
                for (var g = 0; g < highGroups; g++) {
                    high[g] = (float) table2[base2 + g] * scaling[LOW_GROUPS];
                }
                for (var kb = 0; kb < LOW_GROUPS; kb++) {
                    for (var g = 0; g < highGroups; g++) {
                        var value = (float) table1[base1 + kb * highGroups + g] * scaling[kb];
                        high[g] += value * smoothed[kb];
                    }
                }
                var start = band;
                var end = Math.min(start + width - 1, 63);
                for (var g = 0; g < highGroups; g++) {
                    var energy = (float) StrictMath.pow(10.0f, high[g] / 10);
                    for (var k = start; k <= end; k++) {
                        out[t * 64 + k] = energy;
                    }
                    start += width;
                    if (g >= highGroups - 2) {
                        end = 63;
                    } else {
                        end = Math.min(start + width - 1, 63);
                    }
                }
            }
            System.arraycopy(energies, TIME_SLOTS * 3, energies, 0, (16 - 1) * 3);
        } else {
            Arrays.fill(out, 0, 1024, 0.0f);
        }
        previousBand = band;
        previousFlag = active;
        previousRate = rate;
    }

    private void group(int rate, int band, float[] low, int firstSlot) {
        var lowBands = 64 / rate;
        var width = 8 / rate;
        var floor = 10 * (float) StrictMath.log10(LOW_LIMIT);
        for (var t = 0; t < TIME_SLOTS; t++) {
            for (var g = 0; g < LOW_GROUPS; g++) {
                var start = band - width * LOW_GROUPS + width * g;
                var end = start + width - 1;
                float energy;
                if (start >= 0) {
                    energy = 0.0f;
                    for (var i = start; i <= end; i++) {
                        energy += low[t * lowBands + i];
                    }
                    energy = energy / width;
                } else {
                    energy = LOW_LIMIT;
                }
                energies[(t + 16 - 1) * 3 + g] = energy > LOW_LIMIT ? 10 * (float) StrictMath.log10(energy) : floor;
            }
        }
        if (!previousFlag || band * rate != previousBand * previousRate) {
            for (var t = 0; t < 16 - 1 + firstSlot; t++) {
                for (var g = 0; g < LOW_GROUPS; g++) {
                    energies[t * 3 + g] = energies[(16 - 1 + firstSlot) * 3 + g];
                }
            }
        }
    }
}
