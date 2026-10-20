package com.github.auties00.smalt.video.h264.internal;

/**
 * Decodes bins with the H.264 binary arithmetic decoding engine (ITU-T H.264 9.3.3.2).
 *
 * <p>The engine keeps {@code codIOffset} together with up to 31 look-ahead bits in one 64-bit
 * window, {@code window = codIOffset * 2^bits + nextBits}, so renormalization only decrements
 * {@code bits} and a refill loads 32 bits at once. The window is stored complemented
 * ({@link #inverted} holds {@code ~window}), which turns the comparison of the offset with the
 * scaled most probable symbol range into one addition. The context states live in a flat byte
 * array in the combined {@code (pStateIdx << 1) | valMPS} form of {@link CabacTables}.
 *
 * <p>Reading past the end of the slice data yields zero bits; the decoder detects the overrun
 * with {@link #overrun()} at macroblock boundaries.
 */
public final class CabacReader {
    /**
     * The LPS range of {@link CabacTables#LPS_RANGE}, indexed {@code (pStateIdx << 3) | (range >> 6)}
     * (the range is 256 to 510, so {@code range >> 6} is 4 to 7): every index a state byte can form
     * is in range, and the index costs one shift and one OR on the range.
     */
    private static final int[] LPS = new int[512];

    /**
     * The renormalized LPS range and its renormalization shift for each {@link #LPS} entry:
     * {@code (lps << shift) | shift << 9}, so that an LPS decision needs no leading-zero count.
     */
    private static final int[] LPS_RENORM = new int[512];

    /**
     * The combined state transition of {@link CabacTables#TRANSITION}, padded to 512 entries.
     */
    private static final byte[] TRANSITION = new byte[512];

    static {
        for (var index = 0; index < 512; index++) {
            var lps = CabacTables.LPS_RANGE[((index >> 3) << 2) | (index & 3)];
            var shift = CabacTables.RENORM_SHIFT[lps];
            LPS[index] = lps;
            LPS_RENORM[index] = (lps << shift) | (shift << 9);
            TRANSITION[index] = CabacTables.TRANSITION[index & 0xFF];
        }
    }

    /**
     * The context states, indexed by {@code ctxIdx}.
     */
    public final byte[] states = new byte[1024];

    /**
     * The buffer holding the slice data, followed by zero padding.
     */
    private byte[] data;

    /**
     * The index of the first byte of the arithmetic-coded data.
     */
    private int start;

    /**
     * The index one past the last payload byte.
     */
    private int end;

    /**
     * The index of the next byte to load into the window.
     */
    private int next;

    /**
     * The largest index at which a 32-bit load stays inside {@link #data}.
     */
    private int loadLimit;

    /**
     * The complement of the offset window: {@code ~(codIOffset * 2^bits + nextBits)}.
     */
    private long inverted;

    /**
     * The number of look-ahead bits below {@code codIOffset} in the window.
     */
    private int bits;

    /**
     * The {@code codIRange}, from 256 to 510 between decisions.
     */
    private int range;

    /**
     * Constructs an engine; {@link #init(byte[], int, int)} must be called before decoding.
     */
    public CabacReader() {
        this.data = new byte[H264BitReader.PADDING];
    }

    /**
     * Initializes the decoding engine at a byte position (ITU-T H.264 9.3.1.2).
     *
     * @param data the buffer; at least {@link H264BitReader#PADDING} zero bytes must follow
     * {@code end}
     * @param position the index of the first byte of arithmetic-coded data
     * @param end the index one past the last payload byte
     */
    public void init(byte[] data, int position, int end) {
        this.data = data;
        this.start = position;
        this.end = end;
        this.next = position;
        this.loadLimit = data.length - 4;
        this.inverted = ~0L;
        this.bits = -9;
        this.range = 510;
        inverted = (inverted << 32) | complementedWord();
        bits += 32;
    }

    /**
     * Returns the complement of the next 32 bits of the payload, all ones past its end, and
     * advances past them.
     *
     * @return the complemented bits as a non-negative {@code long}
     */
    private long complementedWord() {
        var at = next;
        next = at + 4;
        var source = data;
        return at > loadLimit ? 0xFFFFFFFFL : (((source[at] & 0xFFL) << 24) | ((source[at + 1] & 0xFF) << 16)
                | ((source[at + 2] & 0xFF) << 8) | (source[at + 3] & 0xFF)) ^ 0xFFFFFFFFL;
    }

    /**
     * Decodes one context-coded bin (ITU-T H.264 9.3.3.2.1).
     *
     * @implNote This implementation decodes without a data-dependent branch: the sign of the sum
     * of the scaled MPS range and the complemented offset yields an all-ones mask for the LPS,
     * which selects the new range, the offset correction and the state transition
     * arithmetically. The renormalized LPS range and shift come from a table looked up in
     * parallel with the LPS range, and the MPS shift is one exactly when the MPS range is below
     * 256, which keeps the loop-carried range and offset dependency short. Only the rare 32-bit
     * refill branches; its load is written out here rather than called, so that the JIT compiler
     * does not keep a call in the hot path of the callers it inlines this method into.
     * @param ctxIdx the context index
     * @return the bin value, 0 or 1
     */
    public int decision(int ctxIdx) {
        var st = states;
        var nwin = inverted;
        var b = bits;
        long rng = range;
        var state = st[ctxIdx] & 0xFF;
        var index = ((state << 2) & 0x1F8) | (int) (rng >> 6);
        var mps = rng - LPS[index];
        var scaled = mps << b;
        var mask = (scaled + nwin) >> 63;
        nwin += scaled & mask;
        var taken = (int) mask;
        var renorm = LPS_RENORM[index];
        var mpsShift = (int) (mps >> 8) ^ 1;
        var mpsRange = mps << mpsShift;
        rng = mpsRange ^ ((mpsRange ^ (renorm & 0x1FF)) & mask);
        b -= mpsShift ^ ((mpsShift ^ (renorm >>> 9)) & taken);
        st[ctxIdx] = TRANSITION[(state << 1) - taken];
        if (b < 0) {
            var at = next;
            next = at + 4;
            var source = data;
            nwin = (nwin << 32) | (at > loadLimit ? 0xFFFFFFFFL : (((source[at] & 0xFFL) << 24)
                    | ((source[at + 1] & 0xFF) << 16) | ((source[at + 2] & 0xFF) << 8) | (source[at + 3] & 0xFF)) ^ 0xFFFFFFFFL);
            b += 32;
        }
        inverted = nwin;
        bits = b;
        range = (int) rng;
        return (state ^ taken) & 1;
    }

    /**
     * Decodes one bypass-coded bin (ITU-T H.264 9.3.3.2.3).
     *
     * @return the bin value, 0 or 1
     */
    public int bypass() {
        var nwin = inverted;
        var b = bits - 1;
        if (b < 0) {
            var at = next;
            next = at + 4;
            var source = data;
            nwin = (nwin << 32) | (at > loadLimit ? 0xFFFFFFFFL : (((source[at] & 0xFFL) << 24)
                    | ((source[at + 1] & 0xFF) << 16) | ((source[at + 2] & 0xFF) << 8) | (source[at + 3] & 0xFF)) ^ 0xFFFFFFFFL);
            b += 32;
        }
        var scaled = (long) range << b;
        var one = (scaled + nwin) >> 63;
        inverted = nwin + (scaled & one);
        bits = b;
        return (int) one & 1;
    }

    /**
     * Decodes the terminating bin of {@code end_of_slice_flag} and of the I_PCM
     * {@code mb_type} (ITU-T H.264 9.3.3.2.2).
     *
     * @return the bin value, 0 or 1
     */
    public int terminate() {
        range -= 2;
        var scaled = (long) range << bits;
        if (scaled + inverted < 0) {
            return 1;
        }
        if (range < 256) {
            range <<= 1;
            if (--bits < 0) {
                inverted = (inverted << 32) | complementedWord();
                bits += 32;
            }
        }
        return 0;
    }

    /**
     * Returns the index of the first byte after the bits the engine consumed, rounded up to a
     * byte boundary: the position of the PCM samples after an I_PCM {@code mb_type}.
     *
     * @return the byte index
     */
    public int alignedBytePosition() {
        var consumed = (long) (next - start) * 8 - bits;
        return start + (int) ((consumed + 7) >>> 3);
    }

    /**
     * Returns whether the engine read past the end of the payload by more than the few bytes the
     * arithmetic decoder may legitimately look ahead.
     *
     * @return {@code true} when the slice data is truncated
     */
    public boolean overrun() {
        var consumedBits = (long) (next - start) * 8 - bits;
        return consumedBits > (long) (end - start) * 8 + 16;
    }

    /**
     * Returns the context states for direct manipulation by residual decoding loops.
     *
     * @return the state array
     */
    public byte[] states() {
        return states;
    }

    /**
     * The coding state reached after a coefficient level equal to 1, indexed by the state before
     * it; states 0 to 3 count the levels equal to 1 seen with no level above 1, states 4 to 7
     * count the levels above 1.
     */
    private static final byte[] LEVEL_NEXT_ONE = {1, 2, 3, 3, 4, 5, 6, 7};

    /**
     * The coding state reached after a coefficient level above 1.
     */
    private static final byte[] LEVEL_NEXT_GT1 = {4, 4, 4, 4, 5, 6, 7, 7};

    /**
     * The context increment of the first {@code coeff_abs_level_minus1} bin per coding state:
     * {@code numDecodAbsLevelGt1 != 0 ? 0 : min(4, 1 + numDecodAbsLevelEq1)}.
     */
    private static final byte[] LEVEL_FIRST_CTX = {1, 2, 3, 4, 0, 0, 0, 0};

    /**
     * The context increment of the other {@code coeff_abs_level_minus1} bins per coding state:
     * {@code 5 + min(4, numDecodAbsLevelGt1)}, before the cap of the block category.
     */
    private static final byte[] LEVEL_REST_CTX = {5, 5, 5, 5, 6, 7, 8, 9};

    /**
     * The significance map contexts of each block category 0 to 5, one entry per coefficient
     * position of the block list: the {@code ctxIdx} of {@code significant_coeff_flag} in the low
     * 16 bits and of {@code last_significant_coeff_flag} in the high 16 bits (ITU-T H.264
     * 9.3.3.1.3; 4:2:0 chroma DC blocks use {@code min(i, 2)} for both).
     */
    private static final int[][] SIGNIFICANCE = new int[6][];

    /**
     * The significance map contexts of field-coded blocks, laid out as {@link #SIGNIFICANCE}.
     */
    private static final int[][] SIGNIFICANCE_FIELD = new int[6][];

    /**
     * The level contexts of each block category 0 to 5, one entry per coding state: the
     * {@code ctxIdx} of the first {@code coeff_abs_level_minus1} bin in the low 16 bits and of
     * the other bins in the high 16 bits (ITU-T H.264 9.3.3.1.3; the increment of the other bins
     * is capped at 8 for chroma DC blocks and at 9 otherwise).
     */
    private static final int[][] LEVEL_CONTEXTS = new int[6][];

    static {
        for (var category = 0; category < 6; category++) {
            var significance = new int[category == 5 ? 63 : 15];
            var fieldSignificance = new int[significance.length];
            for (var i = 0; i < significance.length; i++) {
                int sigInc;
                int fieldSigInc;
                int lastInc;
                if (category == 5) {
                    sigInc = CabacTables.SIG_8X8[i];
                    fieldSigInc = CabacTables.SIG_8X8_FIELD[i];
                    lastInc = CabacTables.LAST_8X8[i];
                } else if (category == 3) {
                    sigInc = Math.min(i, 2);
                    fieldSigInc = sigInc;
                    lastInc = sigInc;
                } else {
                    sigInc = i;
                    fieldSigInc = i;
                    lastInc = i;
                }
                significance[i] = (CabacTables.SIG_OFFSET[category] + sigInc)
                        | ((CabacTables.LAST_OFFSET[category] + lastInc) << 16);
                fieldSignificance[i] = (CabacTables.SIG_OFFSET_FIELD[category] + fieldSigInc)
                        | ((CabacTables.LAST_OFFSET_FIELD[category] + lastInc) << 16);
            }
            SIGNIFICANCE[category] = significance;
            SIGNIFICANCE_FIELD[category] = fieldSignificance;
            var levels = new int[8];
            var restCap = category == 3 ? 8 : 9;
            for (var state = 0; state < 8; state++) {
                levels[state] = (CabacTables.ABS_OFFSET[category] + LEVEL_FIRST_CTX[state])
                        | ((CabacTables.ABS_OFFSET[category] + Math.min(LEVEL_REST_CTX[state], restCap)) << 16);
            }
            LEVEL_CONTEXTS[category] = levels;
        }
    }

    /**
     * Decodes the significance map and the levels of one residual block whose
     * {@code coded_block_flag} is 1 (ITU-T H.264 7.3.5.3.3, 9.3.2.3 and 9.3.3.1.3).
     *
     * <p>The coefficients are returned in reverse scan order: {@code indices[k]} is the position
     * in the block coefficient list (from 0) of the non-zero level {@code levels[k]}, with
     * {@code k = 0} the lowest-frequency coefficient.
     *
     * @implNote This implementation decodes the significance map and the levels in two methods
     * that keep the engine state in local variables and repeat the decision code inline: each
     * loop then has few enough live values for the JIT compiler to keep the loop-carried range,
     * offset and bit count in registers. Every decision, sign and suffix bin is decoded without a
     * data-dependent branch (see {@link #decision(int)}); only the loop exits depend on the
     * decoded values.
     *
     * @param category the {@code ctxBlockCat}, 0 to 5
     * @param maxCoeff the {@code maxNumCoeff} of the block, from 4 to 64
     * @param field whether the block belongs to a field macroblock, whose significance map uses
     * the field-coded contexts
     * @param indices receives the coefficient positions
     * @param levels receives the signed levels
     * @return the number of non-zero coefficients, at least 1
     * @throws com.github.auties00.smalt.MediaException.Malformed if a level is out of
     * range
     */
    public int residualBlock(int category, int maxCoeff, boolean field, int[] indices, int[] levels) {
        var count = significanceMap((field ? SIGNIFICANCE_FIELD : SIGNIFICANCE)[category], maxCoeff - 1, indices);
        decodeLevels(LEVEL_CONTEXTS[category], count, levels);
        return count;
    }

    /**
     * Decodes a significance map (ITU-T H.264 7.3.5.3.3).
     *
     * @param contexts the significance map contexts of the block category
     * @param end {@code maxNumCoeff - 1}, the position that is significant when no earlier
     * one was the last
     * @param indices receives the positions of the significant coefficients
     * @return the number of significant coefficients, at least 1
     */
    private int significanceMap(int[] contexts, int end, int[] indices) {
        var st = states;
        var nwin = inverted;
        var b = bits;
        long rng = range;
        var count = 0;
        for (var i = 0; i < end; i++) {
            var pair = contexts[i];
            var sigCtx = pair & 0xFFFF;
            var state = st[sigCtx] & 0xFF;
            var index = ((state << 2) & 0x1F8) | (int) (rng >> 6);
            var mps = rng - LPS[index];
            var scaled = mps << b;
            var mask = (scaled + nwin) >> 63;
            nwin += scaled & mask;
            var taken = (int) mask;
            var renorm = LPS_RENORM[index];
            var mpsShift = (int) (mps >> 8) ^ 1;
            var mpsRange = mps << mpsShift;
            rng = mpsRange ^ ((mpsRange ^ (renorm & 0x1FF)) & mask);
            b -= mpsShift ^ ((mpsShift ^ (renorm >>> 9)) & taken);
            st[sigCtx] = TRANSITION[(state << 1) - taken];
            if (b < 0) {
                var at = next;
                next = at + 4;
                var source = data;
                nwin = (nwin << 32) | (at > loadLimit ? 0xFFFFFFFFL : (((source[at] & 0xFFL) << 24)
                        | ((source[at + 1] & 0xFF) << 16) | ((source[at + 2] & 0xFF) << 8) | (source[at + 3] & 0xFF)) ^ 0xFFFFFFFFL);
                b += 32;
            }
            if (((state ^ taken) & 1) == 0) {
                continue;
            }
            indices[count++] = i;
            var lastCtx = pair >>> 16;
            state = st[lastCtx] & 0xFF;
            index = ((state << 2) & 0x1F8) | (int) (rng >> 6);
            mps = rng - LPS[index];
            scaled = mps << b;
            mask = (scaled + nwin) >> 63;
            nwin += scaled & mask;
            taken = (int) mask;
            renorm = LPS_RENORM[index];
            mpsShift = (int) (mps >> 8) ^ 1;
            mpsRange = mps << mpsShift;
            rng = mpsRange ^ ((mpsRange ^ (renorm & 0x1FF)) & mask);
            b -= mpsShift ^ ((mpsShift ^ (renorm >>> 9)) & taken);
            st[lastCtx] = TRANSITION[(state << 1) - taken];
            if (b < 0) {
                var at = next;
                next = at + 4;
                var source = data;
                nwin = (nwin << 32) | (at > loadLimit ? 0xFFFFFFFFL : (((source[at] & 0xFFL) << 24)
                        | ((source[at + 1] & 0xFF) << 16) | ((source[at + 2] & 0xFF) << 8) | (source[at + 3] & 0xFF)) ^ 0xFFFFFFFFL);
                b += 32;
            }
            if (((state ^ taken) & 1) != 0) {
                inverted = nwin;
                bits = b;
                range = (int) rng;
                return count;
            }
        }
        indices[count++] = end;
        inverted = nwin;
        bits = b;
        range = (int) rng;
        return count;
    }

    /**
     * Decodes the levels of the significant coefficients of a block, from the last one to the
     * first (ITU-T H.264 7.3.5.3.3 and 9.3.2.3).
     *
     * <p>The unary bins after the first of a level all use one context, whose state is kept in
     * a local variable across them and stored once.
     *
     * @param contexts the level contexts of the block category
     * @param count the number of significant coefficients
     * @param levels receives the signed levels, the first coefficient at index 0
     * @throws com.github.auties00.smalt.MediaException.Malformed if a level is out of
     * range
     */
    private void decodeLevels(int[] contexts, int count, int[] levels) {
        var st = states;
        var nwin = inverted;
        var b = bits;
        long rng = range;
        var levelState = 0;
        for (var k = count - 1; k >= 0; k--) {
            var pair = contexts[levelState];
            var firstCtx = pair & 0xFFFF;
            var state = st[firstCtx] & 0xFF;
            var index = ((state << 2) & 0x1F8) | (int) (rng >> 6);
            var mps = rng - LPS[index];
            var scaled = mps << b;
            var mask = (scaled + nwin) >> 63;
            nwin += scaled & mask;
            var taken = (int) mask;
            var renorm = LPS_RENORM[index];
            var mpsShift = (int) (mps >> 8) ^ 1;
            var mpsRange = mps << mpsShift;
            rng = mpsRange ^ ((mpsRange ^ (renorm & 0x1FF)) & mask);
            b -= mpsShift ^ ((mpsShift ^ (renorm >>> 9)) & taken);
            st[firstCtx] = TRANSITION[(state << 1) - taken];
            if (b < 0) {
                var at = next;
                next = at + 4;
                var source = data;
                nwin = (nwin << 32) | (at > loadLimit ? 0xFFFFFFFFL : (((source[at] & 0xFFL) << 24)
                        | ((source[at + 1] & 0xFF) << 16) | ((source[at + 2] & 0xFF) << 8) | (source[at + 3] & 0xFF)) ^ 0xFFFFFFFFL);
                b += 32;
            }
            int level;
            if (((state ^ taken) & 1) == 0) {
                level = 1;
                levelState = LEVEL_NEXT_ONE[levelState];
            } else {
                var restCtx = pair >>> 16;
                var restState = st[restCtx] & 0xFF;
                level = 2;
                while (level < 15) {
                    index = ((restState << 2) & 0x1F8) | (int) (rng >> 6);
                    mps = rng - LPS[index];
                    scaled = mps << b;
                    mask = (scaled + nwin) >> 63;
                    nwin += scaled & mask;
                    taken = (int) mask;
                    renorm = LPS_RENORM[index];
                    mpsShift = (int) (mps >> 8) ^ 1;
                    mpsRange = mps << mpsShift;
                    rng = mpsRange ^ ((mpsRange ^ (renorm & 0x1FF)) & mask);
                    b -= mpsShift ^ ((mpsShift ^ (renorm >>> 9)) & taken);
                    var more = (restState ^ taken) & 1;
                    restState = TRANSITION[(restState << 1) - taken] & 0xFF;
                    if (b < 0) {
                        var at = next;
                        next = at + 4;
                        var source = data;
                        nwin = (nwin << 32) | (at > loadLimit ? 0xFFFFFFFFL : (((source[at] & 0xFFL) << 24)
                                | ((source[at + 1] & 0xFF) << 16) | ((source[at + 2] & 0xFF) << 8) | (source[at + 3] & 0xFF)) ^ 0xFFFFFFFFL);
                        b += 32;
                    }
                    if (more == 0) {
                        break;
                    }
                    level++;
                }
                st[restCtx] = (byte) restState;
                if (level >= 15) {
                    inverted = nwin;
                    bits = b;
                    range = (int) rng;
                    level += levelSuffix();
                    nwin = inverted;
                    b = bits;
                }
                levelState = LEVEL_NEXT_GT1[levelState];
            }
            if (--b < 0) {
                var at = next;
                next = at + 4;
                var source = data;
                nwin = (nwin << 32) | (at > loadLimit ? 0xFFFFFFFFL : (((source[at] & 0xFFL) << 24)
                        | ((source[at + 1] & 0xFF) << 16) | ((source[at + 2] & 0xFF) << 8) | (source[at + 3] & 0xFF)) ^ 0xFFFFFFFFL);
                b += 32;
            }
            var signScaled = rng << b;
            var negative = (signScaled + nwin) >> 63;
            nwin += signScaled & negative;
            levels[k] = (level ^ (int) negative) - (int) negative;
        }
        inverted = nwin;
        bits = b;
        range = (int) rng;
    }

    /**
     * Decodes the zero-order Exp-Golomb bypass suffix of a {@code coeff_abs_level_minus1} whose
     * unary prefix reached 14 (ITU-T H.264 9.3.2.3).
     *
     * @return the suffix value to add to the level
     * @throws com.github.auties00.smalt.MediaException.Malformed if the suffix is out of
     * range
     */
    private int levelSuffix() {
        var nwin = inverted;
        var b = bits;
        long rng = range;
        var value = 0;
        var exponent = 0;
        while (true) {
            if (--b < 0) {
                nwin = (nwin << 32) | complementedWord();
                b += 32;
            }
            var bypassScaled = rng << b;
            if (bypassScaled + nwin >= 0) {
                break;
            }
            nwin += bypassScaled;
            value += 1 << exponent;
            if (++exponent > 24) {
                throw new com.github.auties00.smalt.MediaException.Malformed(
                        "coeff_abs_level_minus1 out of range");
            }
        }
        while (exponent-- > 0) {
            if (--b < 0) {
                nwin = (nwin << 32) | complementedWord();
                b += 32;
            }
            var bypassScaled = rng << b;
            var one = (bypassScaled + nwin) >> 63;
            nwin += bypassScaled & one;
            value += (int) one & (1 << exponent);
        }
        inverted = nwin;
        bits = b;
        return value;
    }
}
