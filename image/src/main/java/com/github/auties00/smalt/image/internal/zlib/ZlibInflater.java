package com.github.auties00.smalt.image.internal.zlib;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.zip.Adler32;
import java.util.zip.DataFormatException;

/**
 * Decompresses zlib streams (RFC 1950, with deflate data of RFC 1951) incrementally, with the
 * interface of {@link java.util.zip.Inflater}: the caller supplies input in pieces and asks for
 * output in pieces.
 *
 * <p>The input is copied into an internal buffer and the output is produced into an internal
 * window of 32 KiB of history plus 256 KiB of new data, from which {@link #inflate} copies to the
 * caller. A stream that is malformed in any way {@link java.util.zip.Inflater} rejects is
 * rejected with a {@link DataFormatException}, at the point where the bytes before the error have
 * been produced. The Adler-32 check is made when the stream ends.
 *
 * <p>Instances are not thread-safe.
 *
 * @implNote This implementation follows the design of libdeflate's decompressor (MIT): a 64-bit
 * bit buffer refilled without a branch from an unaligned 64-bit load, fused entries that carry
 * a literal, or a length or distance base and its extra-bit count, in a table with an 11-bit
 * primary lookup for literals and lengths and an 8-bit one for distances (longer codes go through
 * second-level tables), and matches copied eight bytes at a time. A symbol is decoded only when
 * the whole symbol is in the input, so a stream may be fed in pieces of any size.
 */
public final class ZlibInflater {
    /**
     * The little-endian 64-bit view of byte arrays.
     */
    private static final VarHandle LONG = MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.LITTLE_ENDIAN);

    /**
     * The window kept behind the output position for matches.
     */
    private static final int HISTORY = 32768;

    /**
     * The new output produced between two hand-outs to the caller.
     */
    private static final int CHUNK = 256 * 1024;

    /**
     * The room past the decoding limit for the longest match plus the width of a chunked copy.
     */
    private static final int SLACK = 258 + 8;

    /**
     * The capacity of the output window.
     */
    private static final int WINDOW = HISTORY + CHUNK + SLACK;

    /**
     * The decoding stops for a refill of the caller's output at this output position.
     */
    private static final int LIMIT = HISTORY + CHUNK;

    /**
     * The bytes after the input that the bit-buffer refill may read.
     */
    private static final int PADDING = 8;

    /**
     * The bits indexing the primary literal and length table.
     */
    private static final int LIT_ROOT = 11;

    /**
     * The mask of the primary literal and length index.
     */
    private static final int LIT_MASK = (1 << LIT_ROOT) - 1;

    /**
     * The bits indexing the primary distance table.
     */
    private static final int DIST_ROOT = 8;

    /**
     * The mask of the primary distance index.
     */
    private static final int DIST_MASK = (1 << DIST_ROOT) - 1;

    /**
     * The bits indexing the table of the code length code.
     */
    private static final int PRE_ROOT = 7;

    /**
     * The largest literal and length table: the primary entries and all secondary tables.
     */
    private static final int LIT_TABLE_SIZE = 2048 + 1500;

    /**
     * The largest distance table.
     */
    private static final int DIST_TABLE_SIZE = 256 + 600;

    /**
     * The flag of a literal entry (the sign bit).
     */
    private static final int LITERAL = 1 << 31;

    /**
     * The flag of a literal or length table entry that is not a literal or a length.
     */
    private static final int SPECIAL = 1 << 30;

    /**
     * The flag of a special entry that points to a secondary table.
     */
    private static final int SUBTABLE = 1 << 29;

    /**
     * The flag of a special entry that is not a valid code.
     */
    private static final int INVALID = 1 << 28;

    /**
     * The kind of table: literals and lengths.
     */
    private static final int KIND_LITLEN = 0;

    /**
     * The kind of table: distances.
     */
    private static final int KIND_DIST = 1;

    /**
     * The kind of table: code lengths.
     */
    private static final int KIND_PRE = 2;

    /**
     * The order the code length code lengths are stored in.
     */
    private static final int[] PRE_ORDER = {16, 17, 18, 0, 8, 7, 9, 6, 10, 5, 11, 4, 12, 3, 13, 2, 14, 1, 15};

    /**
     * The base match length of each length symbol.
     */
    private static final int[] LENGTH_BASE = {3, 4, 5, 6, 7, 8, 9, 10, 11, 13, 15, 17, 19, 23, 27, 31, 35, 43, 51, 59,
            67, 83, 99, 115, 131, 163, 195, 227, 258};

    /**
     * The extra bits of each length symbol.
     */
    private static final int[] LENGTH_EXTRA = {0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 2, 2, 3, 3, 3, 3, 4, 4, 4, 4,
            5, 5, 5, 5, 0};

    /**
     * The base distance of each distance symbol.
     */
    private static final int[] DIST_BASE = {1, 2, 3, 4, 5, 7, 9, 13, 17, 25, 33, 49, 65, 97, 129, 193, 257, 385, 513,
            769, 1025, 1537, 2049, 3073, 4097, 6145, 8193, 12289, 16385, 24577};

    /**
     * The extra bits of each distance symbol.
     */
    private static final int[] DIST_EXTRA = {0, 0, 0, 0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 6, 7, 7, 8, 8, 9, 9, 10, 10,
            11, 11, 12, 12, 13, 13};

    /**
     * The literal and length table of the fixed Huffman code.
     */
    private static final int[] FIXED_LITLEN = new int[LIT_TABLE_SIZE];

    /**
     * The distance table of the fixed Huffman code.
     */
    private static final int[] FIXED_DIST = new int[DIST_TABLE_SIZE];

    static {
        var lens = new byte[288];
        Arrays.fill(lens, 0, 144, (byte) 8);
        Arrays.fill(lens, 144, 256, (byte) 9);
        Arrays.fill(lens, 256, 280, (byte) 7);
        Arrays.fill(lens, 280, 288, (byte) 8);
        new Builder().build(FIXED_LITLEN, lens, 0, 288, LIT_ROOT, KIND_LITLEN);
        Arrays.fill(lens, 0, 32, (byte) 5);
        new Builder().build(FIXED_DIST, lens, 0, 32, DIST_ROOT, KIND_DIST);
    }

    /**
     * The decoder state: the zlib header comes next.
     */
    private static final int ZLIB_HEADER = 0;

    /**
     * The decoder state: a block header comes next.
     */
    private static final int BLOCK_HEADER = 1;

    /**
     * The decoder state: inside a stored block.
     */
    private static final int STORED = 2;

    /**
     * The decoder state: inside a Huffman-coded block.
     */
    private static final int HUFFMAN = 3;

    /**
     * The decoder state: the Adler-32 trailer comes next.
     */
    private static final int TRAILER = 4;

    /**
     * The decoder state: the stream has ended.
     */
    private static final int DONE = 5;

    /**
     * The input bytes not yet loaded into the bit buffer, from {@link #inPos} to {@link #inEnd}.
     */
    private byte[] in = new byte[4096 + PADDING];

    /**
     * The index of the next input byte.
     */
    private int inPos;

    /**
     * The end of the valid input.
     */
    private int inEnd;

    /**
     * The bit buffer, least significant bit first.
     */
    private long bitBuf;

    /**
     * The number of valid bits in the bit buffer.
     */
    private int bitCount;

    /**
     * The output window.
     */
    private final byte[] out = new byte[WINDOW];

    /**
     * The index in the window of the next byte to produce.
     */
    private int outPos;

    /**
     * The index in the window of the next byte to hand to the caller.
     */
    private int readPos;

    /**
     * The index in the window of the oldest byte a match may refer to.
     */
    private int histStart;

    /**
     * The decoder state.
     */
    private int state;

    /**
     * Whether the current block is the last.
     */
    private boolean lastBlock;

    /**
     * The bytes left of the current stored block.
     */
    private int storedLeft;

    /**
     * Whether the last decoding step stopped for lack of input.
     */
    private boolean starved;

    /**
     * Whether the zlib header asked for a preset dictionary.
     */
    private boolean dictionary;

    /**
     * The checksum of the bytes handed to the caller.
     */
    private final Adler32 adler = new Adler32();

    /**
     * The literal and length table of the current dynamic block.
     */
    private final int[] litTable = new int[LIT_TABLE_SIZE];

    /**
     * The distance table of the current dynamic block.
     */
    private final int[] distTable = new int[DIST_TABLE_SIZE];

    /**
     * The table of the current block's literal and lengths: this block's own or the fixed one.
     */
    private int[] activeLit = FIXED_LITLEN;

    /**
     * The distance table of the current block.
     */
    private int[] activeDist = FIXED_DIST;

    /**
     * The table of the code length code.
     */
    private final int[] preTable = new int[1 << PRE_ROOT];

    /**
     * The code lengths of a dynamic block header.
     */
    private final byte[] lens = new byte[288 + 32];

    /**
     * The table builder.
     */
    private final Builder builder = new Builder();

    /**
     * The bit buffer, bit count and input position saved at the start of a unit that must be
     * decoded whole.
     */
    private long savedBits;

    /**
     * The bit count saved at the start of a unit.
     */
    private int savedCount;

    /**
     * The input position saved at the start of a unit.
     */
    private int savedPos;

    /**
     * Constructs a decompressor for a zlib stream: header, deflate data and Adler-32 trailer.
     */
    public ZlibInflater() {
        reset();
    }

    /**
     * Prepares for a new stream, discarding all buffered input and output.
     */
    public void reset() {
        inPos = 0;
        inEnd = 0;
        bitBuf = 0;
        bitCount = 0;
        outPos = HISTORY;
        readPos = HISTORY;
        histStart = HISTORY;
        state = ZLIB_HEADER;
        lastBlock = false;
        storedLeft = 0;
        starved = false;
        dictionary = false;
        adler.reset();
    }

    /**
     * Supplies more input. The bytes are copied; the previous input must have been consumed
     * as far as the decompressor is able to, which is the case when {@link #needsInput()} is
     * {@code true}.
     *
     * @param data the compressed bytes
     * @param offset the index of the first byte
     * @param length the number of bytes
     */
    public void setInput(byte[] data, int offset, int length) {
        var remaining = inEnd - inPos;
        var needed = remaining + length + PADDING;
        if (in.length < needed) {
            var grown = new byte[Math.max(needed, in.length * 2)];
            System.arraycopy(in, inPos, grown, 0, remaining);
            in = grown;
        } else if (inPos > 0) {
            System.arraycopy(in, inPos, in, 0, remaining);
        }
        System.arraycopy(data, offset, in, remaining, length);
        inPos = 0;
        inEnd = remaining + length;
        Arrays.fill(in, inEnd, inEnd + PADDING, (byte) 0);
        starved = false;
    }

    /**
     * {@return the number of input bytes not yet loaded into the bit buffer}
     */
    public int getRemaining() {
        return inEnd - inPos;
    }

    /**
     * {@return whether the decompressor needs more input to go on: all output produced so far was
     * handed out and the input is exhausted before the end of the stream}
     */
    public boolean needsInput() {
        return starved && readPos == outPos && state != DONE;
    }

    /**
     * {@return whether the zlib header asked for a preset dictionary, which is not supported}
     */
    public boolean needsDictionary() {
        return dictionary;
    }

    /**
     * {@return whether the stream has ended and all its output was handed out}
     */
    public boolean finished() {
        return state == DONE && readPos == outPos;
    }

    /**
     * Decompresses into an array and returns the number of bytes produced.
     *
     * <p>A return of {@code 0} means that the stream ended, that input is needed, or that a
     * preset dictionary is.
     *
     * @param dst the destination
     * @param offset the index of the first byte to write
     * @param length the most bytes to write
     * @return the number of bytes written
     * @throws DataFormatException if the stream is malformed
     */
    public int inflate(byte[] dst, int offset, int length) throws DataFormatException {
        var total = 0;
        while (length > 0) {
            if (readPos < outPos) {
                var count = Math.min(length, outPos - readPos);
                adler.update(out, readPos, count);
                System.arraycopy(out, readPos, dst, offset, count);
                readPos += count;
                offset += count;
                length -= count;
                total += count;
                continue;
            }
            if (state == DONE || dictionary || starved) {
                break;
            }
            if (outPos > LIMIT) {
                System.arraycopy(out, outPos - HISTORY, out, 0, HISTORY);
                outPos = HISTORY;
                readPos = HISTORY;
                histStart = 0;
            }
            decode();
        }
        return total;
    }

    /**
     * Decodes until the output window is full, the input is exhausted, or the stream ends.
     *
     * @throws DataFormatException if the stream is malformed
     */
    private void decode() throws DataFormatException {
        while (true) {
            switch (state) {
                case ZLIB_HEADER -> {
                    if (!zlibHeader()) {
                        return;
                    }
                }
                case BLOCK_HEADER -> {
                    if (!blockHeader()) {
                        return;
                    }
                }
                case STORED -> {
                    if (!stored()) {
                        return;
                    }
                }
                case HUFFMAN -> {
                    if (!huffman()) {
                        return;
                    }
                }
                case TRAILER -> {
                    if (!trailer()) {
                        return;
                    }
                }
                default -> {
                    return;
                }
            }
        }
    }

    /**
     * Marks the start of a unit that is decoded whole or not at all.
     */
    private void checkpoint() {
        savedBits = bitBuf;
        savedCount = bitCount;
        savedPos = inPos;
    }

    /**
     * Undoes a unit that ran out of input.
     */
    private void rollback() {
        bitBuf = savedBits;
        bitCount = savedCount;
        inPos = savedPos;
        starved = true;
    }

    /**
     * Loads input bytes into the bit buffer while there is room and input.
     */
    private void fill() {
        var bits = bitBuf;
        var count = bitCount;
        var pos = inPos;
        var end = inEnd;
        while (count <= 55 && pos < end) {
            bits |= (in[pos++] & 0xFFL) << count;
            count += 8;
        }
        bitBuf = bits;
        bitCount = count;
        inPos = pos;
    }

    /**
     * Reads bits that a unit needs; fails by returning a negative number when the input is
     * exhausted.
     *
     * @param count the number of bits, at most 24
     * @return the bits, or {@code -1}
     */
    private int bits(int count) {
        if (bitCount < count) {
            fill();
            if (bitCount < count) {
                return -1;
            }
        }
        var value = (int) bitBuf & ((1 << count) - 1);
        bitBuf >>>= count;
        bitCount -= count;
        return value;
    }

    /**
     * Reads the two-byte zlib header.
     *
     * @return {@code false} when the input is exhausted
     * @throws DataFormatException if the header is invalid
     */
    private boolean zlibHeader() throws DataFormatException {
        checkpoint();
        var cmf = bits(8);
        var flg = bits(8);
        if (flg < 0) {
            rollback();
            return false;
        }
        if ((cmf & 0xF) != 8) {
            throw new DataFormatException("unknown compression method");
        }
        if ((cmf >> 4) > 7) {
            throw new DataFormatException("invalid window size");
        }
        if (((cmf << 8) | flg) % 31 != 0) {
            throw new DataFormatException("incorrect header check");
        }
        if ((flg & 0x20) != 0) {
            dictionary = true;
            state = BLOCK_HEADER;
            return false;
        }
        state = BLOCK_HEADER;
        return true;
    }

    /**
     * Reads a block header and, for a dynamic block, its code lengths.
     *
     * @return {@code false} when the input is exhausted
     * @throws DataFormatException if the header is invalid
     */
    private boolean blockHeader() throws DataFormatException {
        checkpoint();
        var header = bits(3);
        if (header < 0) {
            rollback();
            return false;
        }
        var last = (header & 1) != 0;
        switch (header >> 1) {
            case 0 -> {
                var skip = bitCount & 7;
                bitBuf >>>= skip;
                bitCount -= skip;
                var length = bits(16);
                var inverse = bits(16);
                if (inverse < 0) {
                    rollback();
                    return false;
                }
                if ((length ^ 0xFFFF) != inverse) {
                    throw new DataFormatException("invalid stored block lengths");
                }
                storedLeft = length;
                lastBlock = last;
                state = STORED;
                return true;
            }
            case 1 -> {
                activeLit = FIXED_LITLEN;
                activeDist = FIXED_DIST;
                lastBlock = last;
                state = HUFFMAN;
                return true;
            }
            case 2 -> {
                if (!dynamicTables()) {
                    rollback();
                    return false;
                }
                activeLit = litTable;
                activeDist = distTable;
                lastBlock = last;
                state = HUFFMAN;
                return true;
            }
            default -> throw new DataFormatException("invalid block type");
        }
    }

    /**
     * Reads the code lengths of a dynamic block and builds its tables.
     *
     * @return {@code false} when the input is exhausted
     * @throws DataFormatException if the code lengths are invalid
     */
    private boolean dynamicTables() throws DataFormatException {
        var hlit = bits(5);
        var hdist = bits(5);
        var hclen = bits(4);
        if (hclen < 0) {
            return false;
        }
        hlit += 257;
        hdist += 1;
        hclen += 4;
        if (hlit > 286 || hdist > 30) {
            throw new DataFormatException("too many length or distance symbols");
        }
        var pre = new byte[19];
        for (var i = 0; i < hclen; i++) {
            var length = bits(3);
            if (length < 0) {
                return false;
            }
            pre[PRE_ORDER[i]] = (byte) length;
        }
        if (builder.build(preTable, pre, 0, 19, PRE_ROOT, KIND_PRE) < 0) {
            throw new DataFormatException("invalid code lengths set");
        }
        var total = hlit + hdist;
        var index = 0;
        while (index < total) {
            if (bitCount < PRE_ROOT) {
                fill();
            }
            var entry = preTable[(int) bitBuf & ((1 << PRE_ROOT) - 1)];
            var used = entry & 0xFF;
            if (used > bitCount) {
                return false;
            }
            bitBuf >>>= used;
            bitCount -= used;
            var symbol = entry >>> 16;
            if (symbol < 16) {
                lens[index++] = (byte) symbol;
                continue;
            }
            int repeat;
            byte value;
            if (symbol == 16) {
                if (index == 0) {
                    throw new DataFormatException("invalid bit length repeat");
                }
                value = lens[index - 1];
                repeat = bits(2);
                if (repeat < 0) {
                    return false;
                }
                repeat += 3;
            } else if (symbol == 17) {
                value = 0;
                repeat = bits(3);
                if (repeat < 0) {
                    return false;
                }
                repeat += 3;
            } else {
                value = 0;
                repeat = bits(7);
                if (repeat < 0) {
                    return false;
                }
                repeat += 11;
            }
            if (index + repeat > total) {
                throw new DataFormatException("invalid bit length repeat");
            }
            Arrays.fill(lens, index, index + repeat, value);
            index += repeat;
        }
        if (lens[256] == 0) {
            throw new DataFormatException("invalid code -- missing end-of-block");
        }
        if (builder.build(litTable, lens, 0, hlit, LIT_ROOT, KIND_LITLEN) < 0) {
            throw new DataFormatException("invalid literal/lengths set");
        }
        if (builder.build(distTable, lens, hlit, hdist, DIST_ROOT, KIND_DIST) < 0) {
            throw new DataFormatException("invalid distances set");
        }
        return true;
    }

    /**
     * Copies the bytes of a stored block.
     *
     * @return {@code false} when the output window is full or the input is exhausted
     */
    private boolean stored() {
        if (bitCount >= 8) {
            // bytes still in the bit buffer come first
            while (storedLeft > 0 && bitCount >= 8 && outPos <= LIMIT) {
                out[outPos++] = (byte) bitBuf;
                bitBuf >>>= 8;
                bitCount -= 8;
                storedLeft--;
            }
        }
        if (storedLeft > 0 && bitCount == 0) {
            bitBuf = 0;
            var count = Math.min(Math.min(storedLeft, inEnd - inPos), LIMIT + 1 - outPos);
            if (count > 0) {
                System.arraycopy(in, inPos, out, outPos, count);
                inPos += count;
                outPos += count;
                storedLeft -= count;
            }
        }
        if (storedLeft == 0) {
            state = lastBlock ? TRAILER : BLOCK_HEADER;
            return true;
        }
        if (outPos > LIMIT) {
            return false;
        }
        starved = true;
        return false;
    }

    /**
     * Checks the Adler-32 trailer.
     *
     * @return {@code false} when the input is exhausted or the output not yet handed out
     * @throws DataFormatException if the checksum does not match
     */
    private boolean trailer() throws DataFormatException {
        if (readPos < outPos) {
            return false;
        }
        var skip = bitCount & 7;
        bitBuf >>>= skip;
        bitCount -= skip;
        checkpoint();
        var b0 = bits(8);
        var b1 = bits(8);
        var b2 = bits(8);
        var b3 = bits(8);
        if (b3 < 0) {
            rollback();
            return false;
        }
        var expected = (b0 << 24) | (b1 << 16) | (b2 << 8) | b3;
        if (expected != (int) adler.getValue()) {
            throw new DataFormatException("incorrect data check");
        }
        state = DONE;
        return true;
    }

    /**
     * Decodes the symbols of a Huffman-coded block.
     *
     * @return {@code true} when the block ended, {@code false} when the output window is full or
     * the input is exhausted
     * @throws DataFormatException if a code or a distance is invalid
     */
    private boolean huffman() throws DataFormatException {
        final var in = this.in;
        final var out = this.out;
        final var lit = activeLit;
        final var dist = activeDist;
        var bb = bitBuf;
        var bc = bitCount;
        var ip = inPos;
        var op = outPos;
        final var history = histStart;
        final var fastIn = inEnd - 8;
        var ended = false;
        try {
            while (ip <= fastIn && op <= LIMIT) {
                bb |= (long) LONG.get(in, ip) << bc;
                ip += (63 - bc) >>> 3;
                bc |= 56;
                var e = lit[(int) bb & LIT_MASK];
                if (e >= 0 && (e & SUBTABLE) != 0) {
                    bb >>>= LIT_ROOT;
                    bc -= LIT_ROOT;
                    e = lit[((e >>> 12) & 0xFFFF) + ((int) bb & ((1 << ((e >>> 8) & 15)) - 1))];
                }
                if (e < 0) {
                    out[op++] = (byte) (e >>> 16);
                    var n = e & 0xFF;
                    bb >>>= n;
                    bc -= n;
                    e = lit[(int) bb & LIT_MASK];
                    if (e < 0) {
                        out[op++] = (byte) (e >>> 16);
                        n = e & 0xFF;
                        bb >>>= n;
                        bc -= n;
                        e = lit[(int) bb & LIT_MASK];
                        if (e < 0) {
                            out[op++] = (byte) (e >>> 16);
                            n = e & 0xFF;
                            bb >>>= n;
                            bc -= n;
                        }
                    }
                    continue;
                }
                if ((e & SPECIAL) != 0) {
                    if ((e & INVALID) != 0) {
                        throw new DataFormatException("invalid literal/length code");
                    }
                    var n = e & 0xFF;
                    bb >>>= n;
                    bc -= n;
                    ended = true;
                    break;
                }
                var n = e & 0xFF;
                var x = (e >>> 8) & 15;
                var length = (e >>> 16) + (((int) bb >>> (n - x)) & ((1 << x) - 1));
                bb >>>= n;
                bc -= n;
                var d = dist[(int) bb & DIST_MASK];
                if (d < 0) {
                    bb >>>= DIST_ROOT;
                    bc -= DIST_ROOT;
                    d = dist[((d >>> 16) & 0x7FFF) + ((int) bb & ((1 << ((d >>> 8) & 15)) - 1))];
                }
                n = d & 0xFF;
                x = (d >>> 8) & 15;
                var distance = ((d >>> 16) & 0x7FFF) + (((int) bb >>> (n - x)) & ((1 << x) - 1));
                bb >>>= n;
                bc -= n;
                if (Integer.compareUnsigned(distance - 1, op - history) >= 0) {
                    throw new DataFormatException("invalid distance too far back");
                }
                op = copy(out, op, distance, length);
            }
            if (!ended) {
                while (op <= LIMIT) {
                    while (bc <= 55 && ip < inEnd) {
                        bb |= (in[ip++] & 0xFFL) << bc;
                        bc += 8;
                    }
                    var saveBits = bb;
                    var saveCount = bc;
                    var savePos = ip;
                    var e = lit[(int) bb & LIT_MASK];
                    var skipped = 0;
                    if (e >= 0 && (e & SUBTABLE) != 0) {
                        skipped = LIT_ROOT;
                        e = lit[((e >>> 12) & 0xFFFF) + (((int) (bb >>> LIT_ROOT)) & ((1 << ((e >>> 8) & 15)) - 1))];
                    }
                    var n = (e & 0xFF) + skipped;
                    if (n > bc) {
                        starved = true;
                        break;
                    }
                    if (e < 0) {
                        out[op++] = (byte) (e >>> 16);
                        bb >>>= n;
                        bc -= n;
                        continue;
                    }
                    if ((e & SPECIAL) != 0) {
                        if ((e & INVALID) != 0) {
                            throw new DataFormatException("invalid literal/length code");
                        }
                        bb >>>= n;
                        bc -= n;
                        ended = true;
                        break;
                    }
                    var x = (e >>> 8) & 15;
                    var length = (e >>> 16) + (((int) (bb >>> skipped) >>> ((e & 0xFF) - x)) & ((1 << x) - 1));
                    bb >>>= n;
                    bc -= n;
                    var d = dist[(int) bb & DIST_MASK];
                    var dskipped = 0;
                    if (d < 0) {
                        dskipped = DIST_ROOT;
                        d = dist[((d >>> 16) & 0x7FFF) + (((int) (bb >>> DIST_ROOT)) & ((1 << ((d >>> 8) & 15)) - 1))];
                    }
                    var dn = (d & 0xFF) + dskipped;
                    if (dn > bc) {
                        bb = saveBits;
                        bc = saveCount;
                        ip = savePos;
                        starved = true;
                        break;
                    }
                    var dx = (d >>> 8) & 15;
                    var distance = ((d >>> 16) & 0x7FFF)
                            + (((int) (bb >>> dskipped) >>> ((d & 0xFF) - dx)) & ((1 << dx) - 1));
                    bb >>>= dn;
                    bc -= dn;
                    if (Integer.compareUnsigned(distance - 1, op - history) >= 0) {
                        throw new DataFormatException("invalid distance too far back");
                    }
                    op = copy(out, op, distance, length);
                }
            }
        } finally {
            bitBuf = bb;
            bitCount = bc;
            inPos = ip;
            outPos = op;
        }
        if (ended) {
            state = lastBlock ? TRAILER : BLOCK_HEADER;
            return true;
        }
        return false;
    }

    /**
     * Copies a match to the output.
     *
     * @param out the output window
     * @param op the output position
     * @param distance the distance of the match, at most the output position
     * @param length the length of the match
     * @return the output position after the match
     */
    private static int copy(byte[] out, int op, int distance, int length) {
        var end = op + length;
        if (distance >= 8) {
            var src = op - distance;
            do {
                LONG.set(out, op, (long) LONG.get(out, src));
                src += 8;
                op += 8;
            } while (op < end);
            return end;
        }
        if (distance == 1) {
            var value = out[op - 1];
            while (op < end) {
                out[op++] = value;
            }
            return end;
        }
        var src = op - distance;
        while (op < end) {
            out[op++] = out[src++];
        }
        return end;
    }

    /**
     * Builds decoding tables from code lengths.
     */
    private static final class Builder {
        /**
         * The number of codes of each length.
         */
        private final int[] count = new int[16];

        /**
         * The next canonical code of each length.
         */
        private final int[] next = new int[16];

        /**
         * The bit-reversed code of each symbol.
         */
        private final int[] reversed = new int[288];

        /**
         * The longest code of the codes starting with each primary index, as an offset by the
         * root, or {@code 0}.
         */
        private final int[] groupLength = new int[2048];

        /**
         * The primary indexes with a secondary table.
         */
        private final int[] groups = new int[2048];

        /**
         * Builds the table of a code.
         *
         * @param table the destination
         * @param lens the code lengths
         * @param offset the index in {@code lens} of the first symbol
         * @param num the number of symbols
         * @param root the number of bits of the primary table
         * @param kind the kind of table
         * @return the number of entries used, or {@code -1} when the lengths are not a valid code
         */
        int build(int[] table, byte[] lens, int offset, int num, int root, int kind) {
            Arrays.fill(count, 0);
            var max = 0;
            for (var i = 0; i < num; i++) {
                var length = lens[offset + i];
                count[length]++;
                if (length > max) {
                    max = length;
                }
            }
            count[0] = 0;
            var left = 1;
            for (var length = 1; length <= 15; length++) {
                left <<= 1;
                left -= count[length];
                if (left < 0) {
                    return -1;
                }
            }
            if (left > 0 && (kind == KIND_PRE || max != 1) && max != 0) {
                return -1;
            }
            if (kind == KIND_PRE && max == 0) {
                return -1;
            }
            var primary = 1 << root;
            var invalid = invalid(kind);
            if (left > 0) {
                Arrays.fill(table, 0, primary, invalid);
            }
            var code = 0;
            next[0] = 0;
            for (var length = 1; length <= 15; length++) {
                code = (code + count[length - 1]) << 1;
                next[length] = code;
            }
            var groupCount = 0;
            for (var symbol = 0; symbol < num; symbol++) {
                var length = lens[offset + symbol];
                if (length == 0) {
                    continue;
                }
                var value = next[length]++;
                var rev = Integer.reverse(value) >>> (32 - length);
                reversed[symbol] = rev;
                if (length <= root) {
                    var entry = entry(kind, symbol, length);
                    for (var i = rev; i < primary; i += 1 << length) {
                        table[i] = entry;
                    }
                } else {
                    var index = rev & (primary - 1);
                    if (groupLength[index] == 0) {
                        groups[groupCount++] = index;
                    }
                    if (length - root > groupLength[index]) {
                        groupLength[index] = length - root;
                    }
                }
            }
            var used = primary;
            for (var g = 0; g < groupCount; g++) {
                var index = groups[g];
                var width = groupLength[index];
                table[index] = pointer(kind, used, width, root);
                if (left > 0) {
                    Arrays.fill(table, used, used + (1 << width), invalid);
                }
                used += 1 << width;
                groupLength[index] = used - (1 << width);
            }
            if (groupCount > 0) {
                for (var symbol = 0; symbol < num; symbol++) {
                    var length = lens[offset + symbol];
                    if (length <= root) {
                        continue;
                    }
                    var rev = reversed[symbol];
                    var index = rev & (primary - 1);
                    var start = groupLength[index];
                    var width = ((table[index] >>> 8) & 15);
                    var entry = entry(kind, symbol, length - root);
                    for (var i = rev >>> root; i < 1 << width; i += 1 << (length - root)) {
                        table[start + i] = entry;
                    }
                }
                for (var g = 0; g < groupCount; g++) {
                    groupLength[groups[g]] = 0;
                }
            }
            return used;
        }

        /**
         * Returns the entry of an unused code.
         *
         * @param kind the kind of table
         * @return the entry
         */
        private static int invalid(int kind) {
            return switch (kind) {
                case KIND_LITLEN -> SPECIAL | INVALID | 1;
                case KIND_DIST -> 1;
                default -> 0x7FFF_0000 | 1;
            };
        }

        /**
         * Returns the entry of a symbol.
         *
         * @param kind the kind of table
         * @param symbol the literal, length or distance symbol the entry decodes to
         * @param length the bits of the code left to consume at this table level
         * @return the entry
         */
        private static int entry(int kind, int symbol, int length) {
            switch (kind) {
                case KIND_LITLEN -> {
                    if (symbol < 256) {
                        return LITERAL | (symbol << 16) | length;
                    }
                    if (symbol == 256) {
                        return SPECIAL | length;
                    }
                    if (symbol > 285) {
                        return SPECIAL | INVALID | length;
                    }
                    var extra = LENGTH_EXTRA[symbol - 257];
                    return (LENGTH_BASE[symbol - 257] << 16) | (extra << 8) | (length + extra);
                }
                case KIND_DIST -> {
                    if (symbol > 29) {
                        return length;
                    }
                    var extra = DIST_EXTRA[symbol];
                    return (DIST_BASE[symbol] << 16) | (extra << 8) | (length + extra);
                }
                default -> {
                    return (symbol << 16) | length;
                }
            }
        }

        /**
         * Returns the entry that points to a secondary table.
         *
         * @param kind the kind of table
         * @param start the index of the secondary table
         * @param width the bits indexing the secondary table
         * @param root the bits consumed by the primary table
         * @return the entry
         */
        private static int pointer(int kind, int start, int width, int root) {
            return kind == KIND_LITLEN
                    ? SPECIAL | SUBTABLE | (start << 12) | (width << 8) | root
                    : LITERAL | (start << 16) | (width << 8) | root;
        }
    }
}
