/* Ports the HVXC code books of the ISO/IEC 14496-3 reference software (hvxcCbAm.h, hvxcCbAm4k.h, hvxcCbCelp.h,
 * hvxcCbCelp4k.h, hvxcCbLsp.h, inc_lsp_575.tbl). Copyright Sony Corporation, Matsushita Communication Industrial,
 * Robert Bosch GmbH; ISO/IEC 14496-3 reference software licence. Modified for Smalt. */
package com.github.auties00.smalt.audio.hvxc.internal;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * The normative code books of the HVXC decoder (ISO/IEC 14496-3 subpart 2, Annex 2.E).
 *
 * <p>The tables are read from {@code hvxc-tables.txt} next to this class, which holds each value in the decimal
 * spelling of the reference software; every table is a row-major array.
 */
final class HvxcTables {
    /**
     * The first stage LSP code book (Table 2.E.13): 32 vectors of 10 LSPs.
     */
    static final float[] LSP;

    /**
     * The second stage LSP code books with interframe prediction (Tables 2.E.14, 2.E.15): 64 vectors of the lower
     * five LSPs, then 16 of the upper five.
     */
    static final float[] LSP_PREDICTED;

    /**
     * The second stage LSP code books without interframe prediction (Tables 2.E.16, 2.E.17), laid out like
     * {@link #LSP_PREDICTED}.
     */
    static final float[] LSP_DIFFERENCE;

    /**
     * The LSP enhancement code book of the 4 kbit/s layer (Table 2.E.18): 256 vectors of 10 LSPs.
     */
    static final float[] LSP_ENHANCEMENT;

    /**
     * The spectral gain code book of the base layer (Table 2.E.2), 32 gains.
     */
    static final float[] AM_GAIN;

    /**
     * The first and the second spectral shape code books of the base layer (Tables 2.E.3, 2.E.4), 16 vectors of
     * 44 values each.
     */
    static final float[] AM_SHAPE1;

    static final float[] AM_SHAPE2;

    /**
     * The spectral code books of the 4 kbit/s layer (Tables 2.E.5 to 2.E.8): 128 vectors of 2 values, 1024, 512 and
     * 64 vectors of 4 values.
     */
    static final float[] AM_4K_SHAPE0;

    static final float[] AM_4K_SHAPE1;

    static final float[] AM_4K_SHAPE2;

    static final float[] AM_4K_SHAPE3;

    /**
     * The stochastic excitation gain and shape code books of the base layer (Tables 2.E.9, 2.E.10): 16 gains and 64
     * shapes of 80 samples.
     */
    static final float[] CELP_GAIN;

    static final float[] CELP_SHAPE;

    /**
     * The stochastic excitation gain and shape code books of the 4 kbit/s layer (Tables 2.E.11, 2.E.12): 8 gains and
     * 32 shapes of 40 samples.
     */
    static final float[] CELP_4K_GAIN;

    static final float[] CELP_4K_SHAPE;

    static {
        var tables = load();
        LSP = tables.get("lsp");
        LSP_PREDICTED = tables.get("lspPredicted");
        LSP_DIFFERENCE = tables.get("lspDifference");
        LSP_ENHANCEMENT = tables.get("lspEnhancement");
        AM_GAIN = tables.get("amGain");
        AM_SHAPE1 = tables.get("amShape1");
        AM_SHAPE2 = tables.get("amShape2");
        AM_4K_SHAPE0 = tables.get("am4kShape0");
        AM_4K_SHAPE1 = tables.get("am4kShape1");
        AM_4K_SHAPE2 = tables.get("am4kShape2");
        AM_4K_SHAPE3 = tables.get("am4kShape3");
        CELP_GAIN = tables.get("celpGain");
        CELP_SHAPE = tables.get("celpShape");
        CELP_4K_GAIN = tables.get("celp4kGain");
        CELP_4K_SHAPE = tables.get("celp4kShape");
    }

    /**
     * Prevents instantiation.
     */
    private HvxcTables() {
    }

    /**
     * Reads every table of the resource.
     */
    private static Map<String, float[]> load() {
        var tables = new HashMap<String, float[]>();
        try (InputStream raw = HvxcTables.class.getResourceAsStream("hvxc-tables.txt")) {
            if (raw == null) {
                throw new IllegalStateException("hvxc-tables.txt is missing");
            }
            var in = new BufferedReader(new InputStreamReader(raw, StandardCharsets.US_ASCII));
            String line;
            while ((line = in.readLine()) != null) {
                if (line.isEmpty() || line.charAt(0) == '#') {
                    continue;
                }
                var header = line.split(" ");
                var values = new float[Integer.parseInt(header[1])];
                var filled = 0;
                while (filled < values.length) {
                    var row = in.readLine();
                    if (row == null) {
                        throw new IllegalStateException("hvxc-tables.txt: " + header[0] + " is truncated");
                    }
                    for (var field : row.split(" ")) {
                        values[filled++] = Float.parseFloat(field);
                    }
                }
                tables.put(header[0], values);
            }
        } catch (IOException exception) {
            throw new UncheckedIOException("cannot read the HVXC tables", exception);
        }
        return tables;
    }
}
