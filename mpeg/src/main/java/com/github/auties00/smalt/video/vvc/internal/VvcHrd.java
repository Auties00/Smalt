/* Ports VVdeC 3.3.0 (source/Lib/DecoderLib/HLSyntaxReader.cpp). Copyright (c) 2018-2026, Fraunhofer-Gesellschaft
 * zur Foerderung der angewandten Forschung e.V. & The VVdeC Authors; Clear BSD License. Modified for Smalt. */
package com.github.auties00.smalt.video.vvc.internal;

import com.github.auties00.smalt.video.h264.internal.H264BitReader;

/**
 * Reads the timing and HRD parameters of a sequence parameter set (ITU-T H.266 7.3.5), keeping only the timing.
 */
final class VvcHrd {
    /**
     * {@code num_units_in_tick}, or 0 when unknown.
     */
    long numUnitsInTick;

    /**
     * {@code time_scale}, or 0 when unknown.
     */
    long timeScale;

    /**
     * {@code elemental_duration_in_tc_minus1 + 1} of the highest sub-layer, or 0 when the picture rate is not fixed.
     */
    int elementalDuration;

    /**
     * Prevents instantiation; {@link #skipGeneralAndOls} creates the instances.
     */
    private VvcHrd() {
    }

    /**
     * Reads {@code general_timing_hrd_parameters( )} and {@code ols_timing_hrd_parameters( )} of a sequence
     * parameter set.
     *
     * @param r the reader
     * @param maxSubLayers {@code sps_max_sublayers_minus1 + 1}
     * @return the timing
     */
    static VvcHrd skipGeneralAndOls(H264BitReader r, int maxSubLayers) {
        var hrd = new VvcHrd();
        hrd.numUnitsInTick = r.readLong(32);
        hrd.timeScale = r.readLong(32);
        var nal = r.readFlag();
        var vcl = r.readFlag();
        var du = false;
        var cpbCnt = 0;
        if (nal || vcl) {
            r.readFlag();
            du = r.readFlag();
            if (du) {
                r.readBits(8);
            }
            r.readBits(8);
            if (du) {
                r.readBits(4);
            }
            cpbCnt = r.readUe(31, "hrd_cpb_cnt_minus1");
        }
        var subLayerCpb = maxSubLayers > 1 && r.readFlag();
        var first = subLayerCpb ? 0 : maxSubLayers - 1;
        for (var i = first; i < maxSubLayers; i++) {
            var fixedGeneral = r.readFlag();
            var fixedWithinCvs = fixedGeneral || r.readFlag();
            hrd.elementalDuration = 0;
            if (fixedWithinCvs) {
                hrd.elementalDuration = r.readUe(2047, "elemental_duration_in_tc_minus1") + 1;
            } else if ((nal || vcl) && cpbCnt == 0) {
                r.readFlag();
            }
            for (var k = 0; k < 2; k++) {
                if (k == 0 ? nal : vcl) {
                    for (var j = 0; j <= cpbCnt; j++) {
                        r.readUe();
                        r.readUe();
                        if (du) {
                            r.readUe();
                            r.readUe();
                        }
                        r.readFlag();
                    }
                }
            }
        }
        return hrd;
    }
}
