/* Ports the ISO/IEC 23003-3:2019 reference software (usac_arith_dec.c: decode2, map_context, get_state,
   get_pk, ari_decode). Copyright Fraunhofer IIS, VoiceAge Corp. and ISO/IEC 2008; ISO/IEC reference software
   licence (use in products conforming to ISO/IEC 23003). Modified for Smalt. */
package com.github.auties00.smalt.audio.aac.internal.usac;

import com.github.auties00.smalt.audio.aac.internal.AacBitReader;

/**
 * Decodes the context-adaptive arithmetic coded spectra of USAC ({@code ac_spectral_data} and the TCX
 * spectra, ISO/IEC 23003-3 subclause 7.4) and keeps the context of one channel between frames.
 *
 * <p>Instances are not thread-safe.
 */
final class ArithmeticDecoder {
    /**
     * The number of bits of the coder's registers.
     */
    private static final int CODE_BITS = 16;

    /**
     * The precision of the cumulative frequencies.
     */
    private static final int STAT_BITS = 14;

    private static final int Q4 = (1 << CODE_BITS) - 1;
    private static final int Q1 = Q4 / 4 + 1;
    private static final int Q2 = 2 * Q1;
    private static final int Q3 = 3 * Q1;

    /**
     * The escape symbol of the 2-tuple models.
     */
    private static final int ESCAPE = 16;

    /**
     * The largest number of 2-tuples of a spectrum (1024 lines), plus the context margin.
     */
    private static final int CAPACITY = 1024 / 2 + 4;

    /**
     * The context magnitude of each 2-tuple, offset by 2.
     */
    private final int[] context = new int[CAPACITY];

    /**
     * The context magnitude each 2-tuple had in the previous spectrum, offset by 2.
     */
    private final int[] previous = new int[CAPACITY];

    private final int[] scratch = new int[CAPACITY];
    private final int[] scratchPrevious = new int[CAPACITY];

    /**
     * The number of lines of the previously decoded spectrum.
     */
    private int previousSize;

    /**
     * The reader of the spectrum being decoded.
     */
    private AacBitReader reader;

    private int low;
    private int high;
    private int value;
    private long position;

    /**
     * Resets the context, as {@code arith_reset_flag} or an independent frame requests.
     */
    void reset() {
        java.util.Arrays.fill(context, 0);
        java.util.Arrays.fill(previous, 0);
    }

    /**
     * Prepares the context for a spectrum of {@code size} lines: resets it or maps the previous
     * spectrum's context to the new resolution.
     *
     * @param size the number of lines of the spectrum or of one of its windows
     * @param reset whether the context is reset
     */
    void prepare(int size, boolean reset) {
        if (reset) {
            reset();
        } else {
            map(size, previousSize);
        }
        previousSize = size;
    }

    /**
     * Marks a spectrum that codes no lines: every context magnitude becomes 1.
     *
     * @param size the number of lines of the spectrum or window
     */
    void markEmpty(int size) {
        for (var i = 0; i < size / 2; i++) {
            context[i + 2] = 1;
        }
    }

    /**
     * {@return the number of lines of the previously decoded spectrum}
     */
    int previousSize() {
        return previousSize;
    }

    /**
     * Sets the number of lines the context was built for.
     *
     * @param size the number of lines
     */
    void setPreviousSize(int size) {
        previousSize = size;
    }

    /**
     * Maps the context of a spectrum of {@code from} lines to one of {@code size} lines.
     */
    private void map(int size, int from) {
        var count = from / 2 + 4;
        System.arraycopy(context, 0, scratch, 0, count);
        System.arraycopy(previous, 0, scratchPrevious, 0, count);
        var ratio = (float) from / (float) size;
        for (var i = 0; i < size / 2; i++) {
            var k = (int) ((float) i * ratio);
            context[2 + i] = scratch[2 + k];
            previous[2 + i] = scratchPrevious[2 + k];
        }
        context[size / 2 + 2] = scratch[from / 2 + 2];
        previous[size / 2 + 2] = scratchPrevious[from / 2 + 2];
        context[size / 2 + 3] = scratch[from / 2 + 3];
        previous[size / 2 + 3] = scratchPrevious[from / 2 + 3];
    }

    /**
     * Decodes the 2-tuples of one spectrum or window ({@code decode2}).
     *
     * <p>{@code count} 2-tuples are coded; the remaining ones up to {@code total} are zero. The reader is
     * left after the sign bits.
     *
     * @param reader the bit source, at the first bit of the spectral data
     * @param out receives the quantized lines
     * @param offset the index of the first line in {@code out}
     * @param count the number of coded 2-tuples
     * @param total the number of 2-tuples of the spectrum or window
     */
    void decode(AacBitReader reader, int[] out, int offset, int count, int total) {
        for (var i = 0; i < total; i++) {
            previous[i + 2] = context[i + 2];
            context[i + 2] = 1;
            out[offset + 2 * i] = 0;
            out[offset + 2 * i + 1] = 0;
        }
        this.reader = reader;
        position = reader.bitPosition();
        var v = 0;
        for (var i = 0; i < CODE_BITS; i++) {
            v = v << 1 | readBit();
        }
        low = 0;
        high = Q4;
        value = v;
        var state = previous[2] << 12;
        for (var i = 0; i < count; i++) {
            var q = i + 2;
            state = state >> 4;
            state += previous[q + 1] << 12;
            state = (state & 0xFFF0) + context[q - 1];
            var s = state;
            if (i > 3 && context[q - 1] + context[q - 2] + context[q - 3] < 5) {
                s += 0x10000;
            }
            int r;
            var lev = 0;
            var escapes = 0;
            while (true) {
                r = symbol(ArithmeticTables.MODELS[model(s + (escapes << 17))], ESCAPE + 1);
                if (r < ESCAPE) {
                    break;
                }
                lev++;
                escapes = Math.min(lev, 7);
            }
            if (r == 0) {
                if (escapes > 0) {
                    break;
                }
                context[q] = 1;
                continue;
            }
            var b = r >> 2;
            var a = r & 3;
            for (var l = 0; l < lev; l++) {
                var model = a == 0 ? 1 : b == 0 ? 0 : 2;
                var bits = symbol(ArithmeticTables.LSB_MODELS[model], 4);
                a = a << 1 | bits & 1;
                b = b << 1 | bits >> 1 & 1;
            }
            out[offset + 2 * i] = a;
            out[offset + 2 * i + 1] = b;
            context[q] = Math.min(a + b + 1, 0xF);
        }
        position -= CODE_BITS - 2;
        for (var i = 0; i < total; i++) {
            if (out[offset + 2 * i] != 0 && reader.bitAt(position++) == 0) {
                out[offset + 2 * i] = -out[offset + 2 * i];
            }
            if (out[offset + 2 * i + 1] != 0 && reader.bitAt(position++) == 0) {
                out[offset + 2 * i + 1] = -out[offset + 2 * i + 1];
            }
        }
        reader.seek(position);
        this.reader = null;
    }

    /**
     * Looks up the model of a context state ({@code get_pk}).
     */
    private static int model(int s) {
        var min = -1;
        var max = ArithmeticTables.HASH_KEYS.length - 1;
        while (max - min > 1) {
            var i = min + (max - min) / 2;
            var key = ArithmeticTables.HASH_KEYS[i];
            if (s < key) {
                max = i;
            } else if (s > key) {
                min = i;
            } else {
                return ArithmeticTables.HASH_MODELS[i];
            }
        }
        return ArithmeticTables.GAP_MODELS[max];
    }

    /**
     * Decodes one symbol under a cumulative frequency table ({@code ari_decode}).
     */
    private int symbol(int[] frequencies, int length) {
        var range = high - low + 1;
        var cum = ((value - low + 1 << STAT_BITS) - 1) / range;
        var p = -1;
        var cfl = length;
        do {
            var q = p + (cfl >> 1);
            if (frequencies[q] > cum) {
                p = q;
                cfl++;
            }
            cfl >>= 1;
        } while (cfl > 1);
        var symbol = p + 1;
        if (symbol > 0) {
            high = low + (range * frequencies[symbol - 1] >> STAT_BITS) - 1;
        }
        low += range * frequencies[symbol] >> STAT_BITS;
        while (true) {
            if (high < Q2) {
                // lower half: nothing to subtract
            } else if (low >= Q2) {
                value -= Q2;
                low -= Q2;
                high -= Q2;
            } else if (low >= Q1 && high < Q3) {
                value -= Q1;
                low -= Q1;
                high -= Q1;
            } else {
                break;
            }
            low += low;
            high += high + 1;
            value = value << 1 | readBit();
        }
        return symbol;
    }

    private int readBit() {
        return reader.bitAt(position++);
    }
}
