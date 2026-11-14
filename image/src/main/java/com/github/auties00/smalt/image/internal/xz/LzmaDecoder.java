/* Ports XZ for Java 1.10 (lzma/LZMADecoder.java, lzma/LZMACoder.java, lz/LZDecoder.java,
 * rangecoder/RangeDecoder.java). Copyright The XZ for Java authors and contributors; 0BSD. Modified for Smalt. */
package com.github.auties00.smalt.image.internal.xz;

import java.util.Arrays;
import java.util.zip.DataFormatException;

/**
 * Decodes LZMA data, the range-coded LZ77 layer of LZMA and LZMA2, into a caller buffer that is also
 * the dictionary.
 *
 * <p>The dictionary is the output written since its last reset, at most the dictionary size; a
 * distance reaching beyond it is invalid. Instances are not thread-safe.
 */
final class LzmaDecoder {
    private static final int STATES = 12;
    private static final int POS_STATES_MAX = 1 << 4;
    private static final int MATCH_LEN_MIN = 2;
    private static final int DIST_STATES = 4;
    private static final int DIST_SLOTS = 1 << 6;
    private static final int DIST_MODEL_START = 4;
    private static final int DIST_MODEL_END = 14;
    private static final int ALIGN_BITS = 4;
    private static final int LOW_SYMBOLS = 1 << 3;
    private static final int MID_SYMBOLS = 1 << 3;
    private static final int HIGH_SYMBOLS = 1 << 8;
    private static final int BIT_MODEL_TOTAL_BITS = 11;
    private static final int BIT_MODEL_TOTAL = 1 << BIT_MODEL_TOTAL_BITS;
    private static final short PROB_INIT = (short) (BIT_MODEL_TOTAL / 2);
    private static final int MOVE_BITS = 5;
    private static final int TOP_MASK = 0xFF000000;

    /** The probabilities. */
    private final short[] isMatch = new short[STATES * POS_STATES_MAX];
    private final short[] isRep = new short[STATES];
    private final short[] isRep0 = new short[STATES];
    private final short[] isRep1 = new short[STATES];
    private final short[] isRep2 = new short[STATES];
    private final short[] isRep0Long = new short[STATES * POS_STATES_MAX];
    private final short[][] distSlots = new short[DIST_STATES][DIST_SLOTS];
    private final short[][] distSpecial = {new short[2], new short[2], new short[4], new short[4], new short[8],
            new short[8], new short[16], new short[16], new short[32], new short[32]};
    private final short[] distAlign = new short[1 << ALIGN_BITS];
    private final LengthProbabilities matchLengths = new LengthProbabilities();
    private final LengthProbabilities repLengths = new LengthProbabilities();
    private short[] literals = new short[0];

    /** The literal context bits, literal position mask and position mask. */
    private int lc;
    private int literalPosMask;
    private int posMask;

    /** The state machine and the four most recent distances, minus one. */
    private int state;
    private int rep0;
    private int rep1;
    private int rep2;
    private int rep3;

    /** The range decoder. */
    private byte[] in;
    private int inPos;
    private int inEnd;
    private int range;
    private int code;

    /** The output, the first byte of the dictionary and the dictionary size. */
    private byte[] out;
    private int outPos;
    private int dictStart;
    private int dictSize;

    /**
     * Sets the literal and position parameters; the probabilities need a {@link #reset()} afterward.
     *
     * @param lc the literal context bits
     * @param lp the literal position bits
     * @param pb the position bits
     */
    void properties(int lc, int lp, int pb) {
        this.lc = lc;
        this.literalPosMask = (1 << lp) - 1;
        this.posMask = (1 << pb) - 1;
        var size = 0x300 << (lc + lp);
        if (literals.length != size) {
            literals = new short[size];
        }
    }

    /**
     * Resets the probabilities, the state and the distances.
     */
    void reset() {
        state = 0;
        rep0 = 0;
        rep1 = 0;
        rep2 = 0;
        rep3 = 0;
        Arrays.fill(isMatch, PROB_INIT);
        Arrays.fill(isRep, PROB_INIT);
        Arrays.fill(isRep0, PROB_INIT);
        Arrays.fill(isRep1, PROB_INIT);
        Arrays.fill(isRep2, PROB_INIT);
        Arrays.fill(isRep0Long, PROB_INIT);
        for (var slots : distSlots) {
            Arrays.fill(slots, PROB_INIT);
        }
        for (var special : distSpecial) {
            Arrays.fill(special, PROB_INIT);
        }
        Arrays.fill(distAlign, PROB_INIT);
        Arrays.fill(literals, PROB_INIT);
        matchLengths.reset();
        repLengths.reset();
    }

    /**
     * Sets the output and the dictionary.
     *
     * @param out the output
     * @param dictStart the first byte of the dictionary in the output
     * @param dictSize the dictionary size
     */
    void output(byte[] out, int dictStart, int dictSize) {
        this.out = out;
        this.dictStart = dictStart;
        this.dictSize = dictSize;
    }

    /**
     * Starts the range decoder on its five initial bytes.
     *
     * @param src the input
     * @param pos the first byte
     * @param end the end of the input the range decoder may read
     * @throws DataFormatException if the initial bytes are missing or invalid
     */
    void start(byte[] src, int pos, int end) throws DataFormatException {
        if (end - pos < 5 || src[pos] != 0) {
            throw new DataFormatException("invalid LZMA range coder start");
        }
        in = src;
        code = (src[pos + 1] & 0xFF) << 24 | (src[pos + 2] & 0xFF) << 16 | (src[pos + 3] & 0xFF) << 8
                | (src[pos + 4] & 0xFF);
        range = 0xFFFFFFFF;
        inPos = pos + 5;
        inEnd = end;
    }

    /** {@return the output position the last call of {@link #decode} reached, including one that failed} */
    int position() {
        return outPos;
    }

    /** {@return the next input byte the range decoder would read} */
    int inputPosition() {
        return inPos;
    }

    /** {@return whether the range decoder ended cleanly: the code is zero} */
    boolean finished() {
        return code == 0;
    }

    /**
     * Decodes until an output position.
     *
     * @param pos the output position
     * @param limit the position to stop at
     * @param end the end of the data the stream describes, past which a match is invalid; at least
     * {@code limit}
     * @param endMarker whether the end marker may occur
     * @return the output position reached, below {@code limit} only when the end marker was read
     * @throws DataFormatException if the data is invalid
     */
    int decode(int pos, int limit, int end, boolean endMarker) throws DataFormatException {
        outPos = pos;
        while (outPos < limit) {
            var posState = (outPos - dictStart) & posMask;
            if (bit(isMatch, state * POS_STATES_MAX + posState) == 0) {
                literal();
                continue;
            }
            int length;
            if (bit(isRep, state) == 0) {
                length = match(posState);
                if (rep0 == -1) {
                    if (!endMarker) {
                        throw new DataFormatException("LZMA end marker where none is allowed");
                    }
                    normalize();
                    return outPos;
                }
            } else {
                length = repMatch(posState);
            }
            var full = Math.min(outPos - dictStart, dictSize);
            if (Integer.compareUnsigned(rep0, full) >= 0) {
                throw new DataFormatException("LZMA distance beyond the dictionary");
            }
            if (length > end - outPos) {
                throw new DataFormatException("LZMA match past the end of the data");
            }
            var copy = Math.min(length, limit - outPos);
            var from = outPos - rep0 - 1;
            if (rep0 + 1 >= copy) {
                System.arraycopy(out, from, out, outPos, copy);
            } else {
                for (var i = 0; i < copy; i++) {
                    out[outPos + i] = out[from + i];
                }
            }
            outPos += copy;
        }
        normalize();
        return outPos;
    }

    private void literal() throws DataFormatException {
        var prev = outPos > dictStart ? out[outPos - 1] & 0xFF : 0;
        var base = 0x300 * ((prev >> (8 - lc)) + (((outPos - dictStart) & literalPosMask) << lc));
        var symbol = 1;
        if (state < 7) {
            do {
                symbol = (symbol << 1) | bit(literals, base + symbol);
            } while (symbol < 0x100);
        } else {
            var at = outPos - rep0 - 1;
            var matchByte = at >= dictStart ? out[at] & 0xFF : 0;
            var offset = 0x100;
            do {
                matchByte <<= 1;
                var matchBit = matchByte & offset;
                var bit = bit(literals, base + offset + matchBit + symbol);
                symbol = (symbol << 1) | bit;
                offset &= (0 - bit) ^ ~matchBit;
            } while (symbol < 0x100);
        }
        out[outPos++] = (byte) symbol;
        state = state <= 3 ? 0 : state <= 9 ? state - 3 : state - 6;
    }

    private int match(int posState) throws DataFormatException {
        state = state < 7 ? 7 : 10;
        rep3 = rep2;
        rep2 = rep1;
        rep1 = rep0;
        var length = matchLengths.decode(posState);
        var slot = tree(distSlots[length < DIST_STATES + MATCH_LEN_MIN ? length - MATCH_LEN_MIN : DIST_STATES - 1]);
        if (slot < DIST_MODEL_START) {
            rep0 = slot;
        } else {
            var limit = (slot >> 1) - 1;
            rep0 = (2 | (slot & 1)) << limit;
            if (slot < DIST_MODEL_END) {
                rep0 |= reverseTree(distSpecial[slot - DIST_MODEL_START]);
            } else {
                rep0 |= direct(limit - ALIGN_BITS) << ALIGN_BITS;
                rep0 |= reverseTree(distAlign);
            }
        }
        return length;
    }

    private int repMatch(int posState) throws DataFormatException {
        if (bit(isRep0, state) == 0) {
            if (bit(isRep0Long, state * POS_STATES_MAX + posState) == 0) {
                state = state < 7 ? 9 : 11;
                return 1;
            }
        } else {
            int distance;
            if (bit(isRep1, state) == 0) {
                distance = rep1;
            } else {
                if (bit(isRep2, state) == 0) {
                    distance = rep2;
                } else {
                    distance = rep3;
                    rep3 = rep2;
                }
                rep2 = rep1;
            }
            rep1 = rep0;
            rep0 = distance;
        }
        state = state < 7 ? 8 : 11;
        return repLengths.decode(posState);
    }

    private void normalize() throws DataFormatException {
        if ((range & TOP_MASK) == 0) {
            if (inPos >= inEnd) {
                throw new DataFormatException("truncated LZMA data");
            }
            code = (code << 8) | (in[inPos++] & 0xFF);
            range <<= 8;
        }
    }

    private int bit(short[] probs, int index) throws DataFormatException {
        normalize();
        int prob = probs[index];
        var bound = (range >>> BIT_MODEL_TOTAL_BITS) * prob;
        if (Integer.compareUnsigned(code, bound) < 0) {
            range = bound;
            probs[index] = (short) (prob + ((BIT_MODEL_TOTAL - prob) >>> MOVE_BITS));
            return 0;
        }
        range -= bound;
        code -= bound;
        probs[index] = (short) (prob - (prob >>> MOVE_BITS));
        return 1;
    }

    private int tree(short[] probs) throws DataFormatException {
        var symbol = 1;
        do {
            symbol = (symbol << 1) | bit(probs, symbol);
        } while (symbol < probs.length);
        return symbol - probs.length;
    }

    private int tree(short[] probs, int base, int size) throws DataFormatException {
        var symbol = 1;
        do {
            symbol = (symbol << 1) | bit(probs, base + symbol);
        } while (symbol < size);
        return symbol - size;
    }

    private int reverseTree(short[] probs) throws DataFormatException {
        var symbol = 1;
        var i = 0;
        var result = 0;
        do {
            var bit = bit(probs, symbol);
            symbol = (symbol << 1) | bit;
            result |= bit << i++;
        } while (symbol < probs.length);
        return result;
    }

    private int direct(int count) throws DataFormatException {
        var result = 0;
        do {
            normalize();
            range >>>= 1;
            var t = (code - range) >>> 31;
            code -= range & (t - 1);
            result = (result << 1) | (1 - t);
        } while (--count != 0);
        return result;
    }

    /**
     * Holds the probabilities of a length decoder.
     */
    private final class LengthProbabilities {
        private final short[] choice = new short[2];
        private final short[] low = new short[POS_STATES_MAX * LOW_SYMBOLS];
        private final short[] mid = new short[POS_STATES_MAX * MID_SYMBOLS];
        private final short[] high = new short[HIGH_SYMBOLS];

        void reset() {
            Arrays.fill(choice, PROB_INIT);
            Arrays.fill(low, PROB_INIT);
            Arrays.fill(mid, PROB_INIT);
            Arrays.fill(high, PROB_INIT);
        }

        int decode(int posState) throws DataFormatException {
            if (bit(choice, 0) == 0) {
                return tree(low, posState * LOW_SYMBOLS, LOW_SYMBOLS) + MATCH_LEN_MIN;
            }
            if (bit(choice, 1) == 0) {
                return tree(mid, posState * MID_SYMBOLS, MID_SYMBOLS) + MATCH_LEN_MIN + LOW_SYMBOLS;
            }
            return tree(high) + MATCH_LEN_MIN + LOW_SYMBOLS + MID_SYMBOLS;
        }
    }
}
