package com.github.auties00.smalt.audio.twinvq.internal;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * The normative tables of the TwinVQ decoder (ISO/IEC 14496-3 Annex 4.A.4): the LSP, interleaved vector quantization,
 * Bark-scale envelope and periodic peak component code books.
 *
 * <p>The tables are read from {@code twinvq-tables.txt} next to this class, each a row-major array of the values the
 * standard prints.
 */
final class TwinVqTables {
    /**
     * The number of elements of an LSP code vector and of a row of MA prediction coefficients.
     */
    static final int LSP_ORDER = 20;

    /**
     * The first row of the second stage in the LSP code books, after the 64 first stage vectors.
     */
    static final int LSP_SECOND_STAGE = 64;

    /**
     * The first row of the MA prediction coefficients in the LSP code books, after the 16 second stage vectors.
     */
    static final int LSP_PREDICTION = 80;

    /**
     * The number of vectors of an interleaved vector quantization code book.
     */
    static final int SHAPE_VECTORS = 32;

    /**
     * The number of elements of an envelope code vector.
     */
    static final int ENVELOPE_LENGTH = 6;

    /**
     * The number of elements of a periodic peak component code vector.
     */
    static final int PERIODIC_LENGTH = 10;

    /**
     * The LSP code book of the core coder (Table 4.A.19) and of the scalable coder (Table 4.A.20): 64 first stage
     * vectors, 16 second stage vectors and 2 rows of MA prediction coefficients.
     */
    static final double[] LSP_CORE;

    static final double[] LSP_SCALABLE;

    /**
     * The interleaved vector quantization code books of conjugate channels 0 and 1 of the core coder, long (Tables
     * 4.A.21 and 4.A.22, 20 elements) and short windows (Tables 4.A.23 and 4.A.24, 17 elements).
     */
    static final double[][] SHAPE_CORE_LONG;

    static final double[][] SHAPE_CORE_SHORT;

    /**
     * The interleaved vector quantization code books of conjugate channels 0 and 1 of the scalable coder, long (Tables
     * 4.A.25 and 4.A.26, 28 elements) and short windows (Tables 4.A.27 and 4.A.28, 24 elements).
     */
    static final double[][] SHAPE_SCALABLE_LONG;

    static final double[][] SHAPE_SCALABLE_SHORT;

    /**
     * The Bark-scale envelope code book (Table 4.A.29): 64 vectors of {@link #ENVELOPE_LENGTH} elements.
     */
    static final double[] ENVELOPE;

    /**
     * The periodic peak component code book (Table 4.A.30): the 64 vectors of conjugate channel 0 and the 64 of
     * conjugate channel 1, of {@link #PERIODIC_LENGTH} elements.
     */
    static final double[] PERIODIC;

    static {
        var tables = load();
        LSP_CORE = tables.get("LSP_CORE");
        LSP_SCALABLE = tables.get("LSP_SCALABLE");
        SHAPE_CORE_LONG = new double[][]{tables.get("SHAPE_CORE_LONG_0"), tables.get("SHAPE_CORE_LONG_1")};
        SHAPE_CORE_SHORT = new double[][]{tables.get("SHAPE_CORE_SHORT_0"), tables.get("SHAPE_CORE_SHORT_1")};
        SHAPE_SCALABLE_LONG = new double[][]{tables.get("SHAPE_SCALABLE_LONG_0"), tables.get("SHAPE_SCALABLE_LONG_1")};
        SHAPE_SCALABLE_SHORT = new double[][]{tables.get("SHAPE_SCALABLE_SHORT_0"),
                tables.get("SHAPE_SCALABLE_SHORT_1")};
        ENVELOPE = tables.get("ENVELOPE");
        PERIODIC = tables.get("PERIODIC");
    }

    private TwinVqTables() {
    }

    /**
     * Reads the table file: a line {@code # NAME COUNT} opens each table, whose values follow separated by spaces.
     *
     * @return the tables by name
     * @throws IllegalStateException if the file is missing or a table is short of values
     */
    private static Map<String, double[]> load() {
        var tables = new HashMap<String, double[]>();
        try (InputStream raw = TwinVqTables.class.getResourceAsStream("twinvq-tables.txt")) {
            if (raw == null) {
                throw new IllegalStateException("twinvq-tables.txt is missing");
            }
            var in = new BufferedReader(new InputStreamReader(raw, StandardCharsets.US_ASCII));
            double[] table = null;
            var filled = 0;
            String line;
            while ((line = in.readLine()) != null) {
                if (line.isEmpty()) {
                    continue;
                }
                var fields = line.split(" ");
                if (fields[0].equals("#")) {
                    if (table != null && filled != table.length) {
                        throw new IllegalStateException("twinvq-tables.txt has a short table");
                    }
                    table = new double[Integer.parseInt(fields[2])];
                    tables.put(fields[1], table);
                    filled = 0;
                    continue;
                }
                if (table == null) {
                    throw new IllegalStateException("twinvq-tables.txt has values before a table");
                }
                for (var field : fields) {
                    table[filled++] = Double.parseDouble(field);
                }
            }
            if (table != null && filled != table.length) {
                throw new IllegalStateException("twinvq-tables.txt has a short table");
            }
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
        return tables;
    }
}
