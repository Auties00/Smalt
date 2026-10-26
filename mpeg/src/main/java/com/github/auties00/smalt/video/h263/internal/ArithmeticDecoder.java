package com.github.auties00.smalt.video.h263.internal;

import com.github.auties00.smalt.video.mpeg4.internal.BitReader;

/**
 * Decodes the symbols of the Syntax-based Arithmetic Coding mode (ITU-T H.263 Annex E).
 *
 * <p>The decoder reads its bits through the start code emulation prevention of E.5: the first one after every run of
 * 14 zero bits is a stuffing bit and is dropped, while a zero after such a run marks a start code, from which on the
 * decoder reads zeros without consuming the stream. A segment of arithmetic coded data starts after every header with
 * {@link #start(BitReader)} ({@code decoder_reset} of E.3) and ends at the next start code, whose position
 * {@link #nextStartCode(int)} finds.
 *
 * <p>An instance is not thread-safe.
 */
final class ArithmeticDecoder {
    /**
     * The first quarter of the code range.
     */
    private static final int Q1 = 16384;

    /**
     * The half of the code range.
     */
    private static final int Q2 = 32768;

    /**
     * The third quarter of the code range.
     */
    private static final int Q3 = 49152;

    /**
     * The largest code value.
     */
    private static final int TOP = 65535;

    /**
     * The zero bits after which the next bit is a stuffing bit or part of a start code.
     */
    private static final int STUFFING_RUN = 14;

    /**
     * The stream.
     */
    private BitReader reader;

    /**
     * The low end of the code interval.
     */
    private int low;

    /**
     * The high end of the code interval.
     */
    private int high;

    /**
     * The code value.
     */
    private int code;

    /**
     * The zero bits read since the last one bit.
     */
    private int zeros;

    /**
     * Whether a start code was reached, after which the decoder reads zeros.
     */
    private boolean stopped;

    /**
     * The bit position where the arithmetic coded data of the segment starts.
     */
    private long origin;

    /**
     * Creates a decoder.
     */
    ArithmeticDecoder() {
    }

    /**
     * Starts decoding the arithmetic coded data at the reader's position.
     *
     * @param reader the stream, positioned after a header
     */
    void start(BitReader reader) {
        this.reader = reader;
        origin = reader.position();
        zeros = 0;
        for (var at = origin - 1; at >= 0 && zeros < STUFFING_RUN && reader.peekAt(at, 1) == 0; at--) {
            zeros++;
        }
        stopped = false;
        low = 0;
        high = TOP;
        code = 0;
        for (var i = 0; i < 16; i++) {
            code = (code << 1) | bit();
        }
    }

    /**
     * Decodes one symbol ({@code decode_a_symbol} of E.3).
     *
     * @param model the cumulative frequencies of the symbols
     * @return the index of the symbol
     */
    int decode(int[] model) {
        var length = high - low + 1;
        var cum = (-1 + (code - low + 1) * model[0]) / length;
        var index = 1;
        var last = model.length - 1;
        while (index < last && model[index] > cum) {
            index++;
        }
        high = low - 1 + (length * model[index - 1]) / model[0];
        low += (length * model[index]) / model[0];
        while (true) {
            if (high < Q2) {
                // both ends in the lower half: no offset
            } else if (low >= Q2) {
                code -= Q2;
                low -= Q2;
                high -= Q2;
            } else if (low >= Q1 && high < Q3) {
                code -= Q1;
                low -= Q1;
                high -= Q1;
            } else {
                break;
            }
            low <<= 1;
            high = (high << 1) | 1;
            code = (code << 1) | bit();
        }
        return index - 1;
    }

    /**
     * Returns the next bit of the arithmetic coded data, dropping the stuffing bits and reading zeros once a start
     * code is reached.
     *
     * @return the bit
     */
    private int bit() {
        if (stopped) {
            return 0;
        }
        var value = reader.readBit();
        if (zeros == STUFFING_RUN) {
            if (value == 0) {
                stopped = true;
                reader.seek(reader.position() - 1);
                return 0;
            }
            zeros = 0;
            value = reader.readBit();
        }
        zeros = value == 0 ? zeros + 1 : 0;
        return value;
    }

    /**
     * Returns whether the decoder reached a start code.
     *
     * @return whether it stopped
     */
    boolean stopped() {
        return stopped;
    }

    /**
     * Finds the start code that ends the arithmetic coded data read so far: the first run of 16 zero bits followed by
     * a one bit from the code window on.
     *
     * @param limit the most bits searched after the window
     * @return the bit position of the start code (its 16 zero bits before the one), or {@code -1} when none follows
     * within the limit
     */
    long nextStartCode(int limit) {
        var from = Math.max(origin, reader.position() - 16);
        var end = Math.min(reader.limit(), reader.position() + limit);
        var run = 0;
        for (var at = from; at < end + 17; at++) {
            if (reader.peekAt(at, 1) == 0) {
                run++;
            } else {
                if (run >= 16) {
                    return at - 16;
                }
                run = 0;
                if (at > end) {
                    return -1;
                }
            }
        }
        return -1;
    }
}
