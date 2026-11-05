package com.github.auties00.smalt.audio.dts.internal;

import com.github.auties00.smalt.MediaException;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.GZIPInputStream;

/**
 * The two vector code books of the DTS core (ETSI TS 102 114 D.10), which the published specification does not
 * print: the 4096 ADPCM prediction coefficient vectors and the 1024 high frequency subband vectors.
 *
 * <p>The books are read from {@code dts-vq.bin.gz} next to this class: 4096 times four big-endian 16-bit ADPCM
 * entries (coefficient times 2^13), then 1024 times 32 signed bytes (subband sample times 2^4). The entries were
 * measured from ffmpeg's decoder used as a black box, by least squares over synthesized frames that select every
 * entry; the test {@code DtsVqDerivationTest} measures them again and checks the resource.
 */
final class DtsVqTables {
    /**
     * The number of ADPCM coefficient vectors.
     */
    static final int ADPCM_VECTORS = 4096;

    /**
     * The number of high frequency vectors.
     */
    static final int HIGH_FREQUENCY_VECTORS = 1024;

    /**
     * The ADPCM coefficients times 2^13, four per vector.
     */
    private static final short[] ADPCM = new short[ADPCM_VECTORS * 4];

    /**
     * The high frequency vector elements times 16, 32 per vector.
     */
    private static final byte[] HIGH_FREQUENCY = new byte[HIGH_FREQUENCY_VECTORS * 32];

    static {
        try (InputStream raw = DtsVqTables.class.getResourceAsStream("dts-vq.bin.gz")) {
            if (raw == null) {
                throw new IllegalStateException("dts-vq.bin.gz is missing");
            }
            var in = new DataInputStream(new GZIPInputStream(raw));
            for (var i = 0; i < ADPCM.length; i++) {
                ADPCM[i] = in.readShort();
            }
            in.readFully(HIGH_FREQUENCY);
        } catch (IOException exception) {
            throw new MediaException.Io("cannot read the DTS vector code books", exception);
        }
    }

    /**
     * {@return one ADPCM prediction coefficient, times 2^13}
     *
     * @param vector the vector index, from 0 to 4095
     * @param k the coefficient, from 0 (the newest sample) to 3
     */
    static int adpcm(int vector, int k) {
        return ADPCM[vector * 4 + k];
    }

    /**
     * {@return one element of a high frequency vector, times 16}
     *
     * @param vector the vector index, from 0 to 1023
     * @param m the element, from 0 to 31
     */
    static int highFrequency(int vector, int m) {
        return HIGH_FREQUENCY[vector * 32 + m];
    }

    /**
     * Prevents instantiation.
     */
    private DtsVqTables() {
    }
}
