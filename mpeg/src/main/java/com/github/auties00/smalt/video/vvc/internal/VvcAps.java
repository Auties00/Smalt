/* Ports VVdeC 3.3.0 (source/Lib/DecoderLib/HLSyntaxReader.cpp). Copyright (c) 2018-2026, Fraunhofer-Gesellschaft
 * zur Foerderung der angewandten Forschung e.V. & The VVdeC Authors; Clear BSD License. Modified for Smalt. */
package com.github.auties00.smalt.video.vvc.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.video.h264.internal.H264BitReader;

/**
 * Holds a parsed adaptation parameter set (ITU-T H.266 7.3.2.6): adaptive loop filter coefficients, a luma
 * mapping with chroma scaling model or scaling lists.
 */
public final class VvcAps {
    /**
     * Constructs an empty set, which {@link #parse} fills.
     */
    public VvcAps() {
    }

    /**
     * The {@code aps_params_type} of an adaptive loop filter set.
     */
    public static final int ALF = 0;
    /**
     * The {@code aps_params_type} of a luma mapping with chroma scaling set.
     */
    public static final int LMCS = 1;
    /**
     * The {@code aps_params_type} of a scaling list set.
     */
    public static final int SCALING = 2;

    /**
     * {@code aps_params_type}: {@link #ALF}, {@link #LMCS} or {@link #SCALING}.
     */
    public int type;
    /**
     * {@code aps_adaptation_parameter_set_id}.
     */
    public int id;
    /**
     * {@code aps_chroma_present_flag}.
     */
    public boolean chromaPresent;

    /**
     * {@code alf_luma_filter_signal_flag}.
     */
    public boolean alfLumaSignalled;
    /**
     * {@code alf_chroma_filter_signal_flag}.
     */
    public boolean alfChromaSignalled;
    /**
     * {@code alf_cc_cb_filter_signal_flag} and {@code alf_cc_cr_filter_signal_flag}.
     */
    public final boolean[] ccAlfSignalled = new boolean[2];
    /**
     * {@code alf_luma_clip_flag}.
     */
    public boolean alfLumaClip;
    /**
     * {@code alf_luma_num_filters_signalled_minus1 + 1}.
     */
    public int alfLumaNumFilters;
    /**
     * The filter index of each of the 25 luma classes.
     */
    public final int[] alfLumaFilterIdx = new int[25];
    /**
     * The 12 coefficients of each signalled luma filter.
     */
    public final int[][] alfLumaCoeff = new int[25][12];
    /**
     * The clipping index of each coefficient of each signalled luma filter.
     */
    public final int[][] alfLumaClipIdx = new int[25][12];
    /**
     * {@code alf_chroma_clip_flag}.
     */
    public boolean alfChromaClip;
    /**
     * {@code alf_chroma_num_alt_filters_minus1 + 1}.
     */
    public int alfChromaNumAlts;
    /**
     * The 6 coefficients of each alternative chroma filter.
     */
    public final int[][] alfChromaCoeff = new int[8][6];
    /**
     * The clipping index of each coefficient of each alternative chroma filter.
     */
    public final int[][] alfChromaClipIdx = new int[8][6];
    /**
     * {@code alf_cc_cb_filters_signalled_minus1 + 1} and {@code alf_cc_cr_filters_signalled_minus1 + 1}.
     */
    public final int[] ccAlfCount = new int[2];
    /**
     * The 7 coefficients of each cross-component filter, indexed by component (Cb, Cr) and filter.
     */
    public final int[][][] ccAlfCoeff = new int[2][4][7];

    /**
     * {@code lmcs_min_bin_idx}.
     */
    public int lmcsMinBinIdx;
    /**
     * {@code LmcsMaxBinIdx}: 15 less {@code lmcs_delta_max_bin_idx}.
     */
    public int lmcsMaxBinIdx;
    /**
     * {@code lmcsDeltaCW} of each bin, signed.
     */
    public final int[] lmcsDeltaCw = new int[16];
    /**
     * {@code lmcsDeltaCrs}, signed.
     */
    public int lmcsDeltaCrs;

    /**
     * The scaling list of a {@link #SCALING} set, or {@code null}.
     */
    public VvcScalingList scalingList;

    /**
     * Parses an adaptation parameter set RBSP.
     *
     * @param r the reader, positioned after the NAL unit header
     * @return the parsed set, or {@code null} for a reserved type
     * @throws MediaException.Malformed if a syntax element is out of range
     */
    public static VvcAps parse(H264BitReader r) {
        var a = new VvcAps();
        a.type = r.readBits(3);
        a.id = r.readBits(5);
        a.chromaPresent = r.readFlag();
        switch (a.type) {
            case ALF -> a.parseAlf(r);
            case LMCS -> a.parseLmcs(r);
            case SCALING -> a.scalingList = VvcScalingList.parse(r, a.chromaPresent);
            default -> {
                return null;
            }
        }
        if (r.overrun()) {
            throw new MediaException.Malformed("truncated vvc adaptation parameter set");
        }
        return a;
    }

    /**
     * Parses the {@code alf_data( )} of an adaptive loop filter set.
     *
     * @param r the reader, positioned at the data
     */
    private void parseAlf(H264BitReader r) {
        if (id > 7) {
            throw new MediaException.Malformed("vvc ALF aps_adaptation_parameter_set_id out of range: " + id);
        }
        alfLumaSignalled = r.readFlag();
        if (chromaPresent) {
            alfChromaSignalled = r.readFlag();
            ccAlfSignalled[0] = r.readFlag();
            ccAlfSignalled[1] = r.readFlag();
        }
        if (alfLumaSignalled) {
            alfLumaClip = r.readFlag();
            alfLumaNumFilters = r.readUe(24, "alf_luma_num_filters_signalled_minus1") + 1;
            if (alfLumaNumFilters > 1) {
                var bits = VvcSps.ceilLog2(alfLumaNumFilters);
                for (var i = 0; i < 25; i++) {
                    alfLumaFilterIdx[i] = VvcSps.check(r.readBits(bits), 0, alfLumaNumFilters - 1,
                            "alf_luma_coeff_delta_idx");
                }
            }
            readCoefficients(r, alfLumaCoeff, alfLumaNumFilters, 12);
            if (alfLumaClip) {
                for (var f = 0; f < alfLumaNumFilters; f++) {
                    for (var j = 0; j < 12; j++) {
                        alfLumaClipIdx[f][j] = r.readBits(2);
                    }
                }
            }
        }
        if (alfChromaSignalled) {
            alfChromaClip = r.readFlag();
            alfChromaNumAlts = r.readUe(7, "alf_chroma_num_alt_filters_minus1") + 1;
            for (var alt = 0; alt < alfChromaNumAlts; alt++) {
                readCoefficients(r, alfChromaCoeff, alt, alt + 1, 6);
                if (alfChromaClip) {
                    for (var j = 0; j < 6; j++) {
                        alfChromaClipIdx[alt][j] = r.readBits(2);
                    }
                }
            }
        }
        for (var c = 0; c < 2; c++) {
            if (ccAlfSignalled[c]) {
                ccAlfCount[c] = r.readUe(3, "alf_cc_filters_signalled_minus1") + 1;
                for (var f = 0; f < ccAlfCount[c]; f++) {
                    for (var i = 0; i < 7; i++) {
                        var code = r.readBits(3);
                        var value = 0;
                        if (code != 0) {
                            value = 1 << (code - 1);
                            if (r.readFlag()) {
                                value = -value;
                            }
                        }
                        ccAlfCoeff[c][f][i] = value;
                    }
                }
            }
        }
    }

    /**
     * Reads the coefficients of filters {@code 0} to {@code count - 1}.
     *
     * @param r the reader
     * @param coeff the coefficients of each filter
     * @param count the number of filters
     * @param taps the coefficients per filter
     */
    private static void readCoefficients(H264BitReader r, int[][] coeff, int count, int taps) {
        readCoefficients(r, coeff, 0, count, taps);
    }

    /**
     * Reads the coefficients of filters {@code from} to {@code to - 1}: an absolute value {@code uvlc} and a sign bit
     * when nonzero.
     *
     * @param r the reader
     * @param coeff the coefficients of each filter
     * @param from the first filter
     * @param to the filter after the last one
     * @param taps the coefficients per filter
     */
    private static void readCoefficients(H264BitReader r, int[][] coeff, int from, int to, int taps) {
        for (var f = from; f < to; f++) {
            for (var j = 0; j < taps; j++) {
                var value = r.readUe(128, "alf_coeff_abs");
                if (value != 0 && r.readFlag()) {
                    value = -value;
                }
                if (value > 127) {
                    throw new MediaException.Malformed("vvc ALF coefficient out of range");
                }
                coeff[f][j] = value;
            }
        }
    }

    /**
     * Parses the {@code lmcs_data( )} of a luma mapping with chroma scaling set.
     *
     * @param r the reader, positioned at the data
     */
    private void parseLmcs(H264BitReader r) {
        if (id > 3) {
            throw new MediaException.Malformed("vvc LMCS aps_adaptation_parameter_set_id out of range: " + id);
        }
        lmcsMinBinIdx = r.readUe(15, "lmcs_min_bin_idx");
        lmcsMaxBinIdx = 15 - r.readUe(15, "lmcs_delta_max_bin_idx");
        if (lmcsMaxBinIdx < lmcsMinBinIdx) {
            throw new MediaException.Malformed("vvc lmcs_delta_max_bin_idx out of range");
        }
        var bits = r.readUe(14, "lmcs_delta_cw_prec_minus1") + 1;
        for (var i = lmcsMinBinIdx; i <= lmcsMaxBinIdx; i++) {
            var value = r.readBits(bits);
            if (value != 0 && r.readFlag()) {
                value = -value;
            }
            lmcsDeltaCw[i] = value;
        }
        if (chromaPresent) {
            var value = r.readBits(3);
            if (value != 0 && r.readFlag()) {
                value = -value;
            }
            lmcsDeltaCrs = value;
        }
    }
}
