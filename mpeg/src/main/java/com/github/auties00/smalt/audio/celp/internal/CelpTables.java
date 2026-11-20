package com.github.auties00.smalt.audio.celp.internal;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * The normative tables of the MPEG-4 CELP decoder (ISO/IEC 14496-3 subpart 3, Annex 3.C): the LSP vector
 * quantizer code books, the gain code books and the adaptive codebook interpolation filters.
 *
 * <p>The tables are read from {@code celp-tables.txt} next to this class, which holds the integer codewords
 * as the standard prints them with the power of two each one is divided by. Each table is a row-major array
 * of {@code rows * columns} values.
 */
final class CelpTables {
    /**
     * The first stage code books of the narrowband LSP quantizer, split vectors 0 and 1 (Tables 3.C.1, 3.C.2).
     */
    static final double[][] NB_LSP1;

    /**
     * The second stage code books without interframe prediction of the narrowband LSP quantizer (Tables 3.C.3,
     * 3.C.4).
     */
    static final double[][] NB_D;

    /**
     * The second stage code books with interframe prediction of the narrowband LSP quantizer (Tables 3.C.5,
     * 3.C.6).
     */
    static final double[][] NB_PD;

    /**
     * The first stage code books of the lower and the upper half of the wideband LSP quantizer (Tables 3.C.10,
     * 3.C.11, 3.C.16, 3.C.17), indexed {@code half * 2 + split}.
     */
    static final double[][] WB_LSP1;

    /**
     * The second stage code books without interframe prediction of the wideband LSP quantizer (Tables 3.C.12,
     * 3.C.13, 3.C.18, 3.C.19), indexed {@code half * 2 + split}.
     */
    static final double[][] WB_D;

    /**
     * The second stage code books with interframe prediction of the wideband LSP quantizer (Tables 3.C.14,
     * 3.C.15, 3.C.20, 3.C.21), indexed {@code half * 2 + split}.
     */
    static final double[][] WB_PD;

    /**
     * The gain code book of the 8 kHz multi-pulse coder for 80-sample subframes (Table 3.C.7): eight columns, the
     * adaptive and the fixed codebook gain of each signal mode.
     */
    static final double[] NB_GAIN_80;

    /**
     * The gain code book of the 8 kHz multi-pulse coder for 40-sample subframes (Table 3.C.8).
     */
    static final double[] NB_GAIN_40;

    /**
     * The gain code book of the 16 kHz multi-pulse coder for 80-sample subframes (Table 3.C.22).
     */
    static final double[] WB_GAIN_80;

    /**
     * The gain code book of the 16 kHz multi-pulse coder for 40-sample subframes (Table 3.C.23).
     */
    static final double[] WB_GAIN_40;

    /**
     * The adaptive codebook interpolation filter at 8 kHz (Table 3.C.9), 37 taps from the center outward.
     */
    static final double[] NB_INTERPOLATION;

    /**
     * The adaptive codebook interpolation filter at 16 kHz (Table 3.C.24), 67 taps from the center outward.
     */
    static final double[] WB_INTERPOLATION;

    /**
     * The gain code book of the bitrate scalable enhancement layers (Table 3.C.25), one column per signal mode.
     */
    static final double[] ENHANCEMENT_GAIN;

    /**
     * The LSP code books of the bandwidth extension tool (Tables 3.C.26 to 3.C.31).
     */
    static final double[][] BWS_LSP;

    /**
     * The widths of the vectors of {@link #BWS_LSP}.
     */
    static final int[] BWS_LSP_DIMENSIONS = {10, 10, 5, 5, 5, 5};

    /**
     * The moving average prediction coefficients of the bandwidth extension LSPs (Table 3.C.32), three columns
     * (the current and the two previous frames) per LSP.
     */
    static final double[] BWS_MA_PREDICTION;

    /**
     * The narrowband to wideband LSP prediction coefficients (Table 3.C.33).
     */
    static final double[] BWS_NW_PREDICTION;

    /**
     * The adaptive and second fixed codebook gain code book of the bandwidth extension tool (Table 3.C.34).
     */
    static final double[] BWS_GAIN;

    /**
     * The first fixed codebook gain code book of the bandwidth extension tool (Table 3.C.35), one column per
     * signal mode.
     */
    static final double[] BWS_GAIN_N;

    static {
        var tables = load();
        NB_LSP1 = new double[][]{tables.get("nb_lsp1_0"), tables.get("nb_lsp1_1")};
        NB_D = new double[][]{tables.get("nb_d_0"), tables.get("nb_d_1")};
        NB_PD = new double[][]{tables.get("nb_pd_0"), tables.get("nb_pd_1")};
        WB_LSP1 = new double[][]{tables.get("wbl_lsp1_0"), tables.get("wbl_lsp1_1"), tables.get("wbu_lsp1_0"),
                tables.get("wbu_lsp1_1")};
        WB_D = new double[][]{tables.get("wbl_d_0"), tables.get("wbl_d_1"), tables.get("wbu_d_0"),
                tables.get("wbu_d_1")};
        WB_PD = new double[][]{tables.get("wbl_pd_0"), tables.get("wbl_pd_1"), tables.get("wbu_pd_0"),
                tables.get("wbu_pd_1")};
        NB_GAIN_80 = tables.get("nb_gain_80");
        NB_GAIN_40 = tables.get("nb_gain_40");
        WB_GAIN_80 = tables.get("wb_gain_80");
        WB_GAIN_40 = tables.get("wb_gain_40");
        NB_INTERPOLATION = tables.get("nb_int_fil");
        WB_INTERPOLATION = tables.get("wb_int_fil");
        ENHANCEMENT_GAIN = tables.get("enh_gain");
        BWS_LSP = new double[6][];
        for (var i = 0; i < 6; i++) {
            BWS_LSP[i] = tables.get("bws_lsp_" + i);
        }
        BWS_MA_PREDICTION = tables.get("bws_ma");
        BWS_NW_PREDICTION = tables.get("bws_nw");
        BWS_GAIN = tables.get("bws_gain");
        BWS_GAIN_N = tables.get("bws_gain_n");
    }

    /**
     * Prevents instantiation.
     */
    private CelpTables() {
    }

    /**
     * Reads every table of the resource, scaled to its real values.
     */
    private static Map<String, double[]> load() {
        var tables = new HashMap<String, double[]>();
        try (InputStream raw = CelpTables.class.getResourceAsStream("celp-tables.txt")) {
            if (raw == null) {
                throw new IllegalStateException("celp-tables.txt is missing");
            }
            var in = new BufferedReader(new InputStreamReader(raw, StandardCharsets.US_ASCII));
            String line;
            while ((line = in.readLine()) != null) {
                if (line.isEmpty() || line.charAt(0) == '#') {
                    continue;
                }
                var header = line.split(" ");
                var rows = Integer.parseInt(header[1]);
                var columns = Integer.parseInt(header[2]);
                var exponents = header[3].split(",");
                var values = new double[rows * columns];
                for (var row = 0; row < rows; row++) {
                    var fields = in.readLine().split(" ");
                    if (fields.length != columns) {
                        throw new IllegalStateException("celp-tables.txt: row " + row + " of " + header[0]);
                    }
                    for (var column = 0; column < columns; column++) {
                        var exponent = Integer.parseInt(exponents[exponents.length == 1 ? 0 : column]);
                        values[row * columns + column] = Integer.parseInt(fields[column]) / (double) (1L << exponent);
                    }
                }
                tables.put(header[0], values);
            }
        } catch (IOException exception) {
            throw new UncheckedIOException("cannot read the CELP tables", exception);
        }
        return tables;
    }
}
