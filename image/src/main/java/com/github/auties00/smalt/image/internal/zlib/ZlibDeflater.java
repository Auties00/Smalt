/* Ports zlib 1.3.1 (deflate.c) and libdeflate (lib/deflate_compress.c). Copyright Jean-loup Gailly and Mark Adler,
   zlib License; Copyright 2016 Eric Biggers, MIT License. Modified for Smalt. */
package com.github.auties00.smalt.image.internal.zlib;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.zip.Adler32;

/**
 * Compresses bytes into raw deflate segments and zlib streams (RFC 1950 and RFC 1951) with a
 * hash-chain match finder, greedy or lazy parsing by level, and a Huffman block per run of
 * symbols that is stored, fixed or dynamic, whichever is smallest.
 *
 * <p>The output is a pure function of the input, the preset dictionary, the level and the
 * strategy flag: no timing or thread count enters it. Instances are not thread-safe; the static
 * methods use one instance per call.
 *
 * @implNote This implementation parses as zlib's {@code deflate_fast} and {@code deflate_slow} do,
 * with zlib's level table and rules for discarding short matches, and encodes blocks as
 * libdeflate does: buffered symbols, length-limited Huffman codes and a 64-bit bit accumulator.
 * Open blocks get split candidates from libdeflate's block split heuristic, and neighboring
 * segments merge back whenever one block over both is no larger, so a candidate never costs bytes.
 */
public final class ZlibDeflater {
    /**
     * The little-endian 64-bit view of byte arrays.
     */
    private static final VarHandle LONG = MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.LITTLE_ENDIAN);

    /**
     * The little-endian 32-bit view of byte arrays.
     */
    private static final VarHandle INT = MethodHandles.byteArrayViewVarHandle(int[].class, ByteOrder.LITTLE_ENDIAN);

    /**
     * The room the input array must have after the data and the output keeps after its fill.
     */
    private static final int SLACK = 16;

    /**
     * The number of positions the hash chains remember.
     */
    private static final int WINDOW = 32768;

    /**
     * The mask of {@link #WINDOW}.
     */
    private static final int WMASK = WINDOW - 1;

    /**
     * The largest distance a match may use, short of the window so that the chain slots in use stay valid.
     */
    private static final int MAX_DIST = WINDOW - 262;

    /**
     * The longest match.
     */
    private static final int MAX_MATCH = 258;

    /**
     * The symbols of a block before it is closed.
     */
    private static final int BLOCK_SYMBOLS = 1 << 15;

    /**
     * The most split candidates one buffered block holds.
     */
    private static final int MAX_CUTS = 24;

    /**
     * The symbols observed between two checks of the split heuristic.
     */
    private static final int CHECK_SYMBOLS = 512;

    /**
     * The fewest input bytes between two split candidates, and the fewest left to the end of the
     * data for a candidate.
     */
    private static final int MIN_SEGMENT = 10000;

    /**
     * The distribution change, in units of {@link #CHECK_SYMBOLS} symbols, that makes a split
     * candidate.
     */
    private static final int CUT_THRESHOLD = 100;

    /**
     * Marks a match in a buffered symbol.
     */
    private static final int MATCH = 1 << 30;

    /**
     * The good length of each level.
     */
    private static final int[] GOOD = {0, 4, 4, 4, 4, 8, 8, 8, 32, 32};

    /**
     * The lazy length (the insert limit for the greedy levels) of each level.
     */
    private static final int[] LAZY = {0, 4, 5, 6, 4, 16, 16, 32, 128, 258};

    /**
     * The nice length of each level.
     */
    private static final int[] NICE = {0, 8, 16, 32, 16, 32, 128, 128, 258, 258};

    /**
     * The chain limit of each level.
     */
    private static final int[] CHAIN = {0, 4, 8, 32, 16, 32, 128, 256, 1024, 4096};

    /**
     * The order in which the code length code lengths are sent.
     */
    private static final int[] CL_ORDER = {16, 17, 18, 0, 8, 7, 9, 6, 10, 5, 11, 4, 12, 3, 13, 2, 14, 1, 15};

    /**
     * The base length of each length code.
     */
    private static final int[] LEN_BASE = new int[29];

    /**
     * The number of extra bits of each length code.
     */
    private static final int[] LEN_EXTRA = {0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 2, 2, 3, 3, 3, 3, 4, 4, 4, 4, 5, 5, 5, 5, 0};

    /**
     * The length code (0 to 28) of each match length minus three.
     */
    private static final byte[] LEN_CODE = new byte[256];

    /**
     * The number of extra bits of each distance code.
     */
    private static final int[] DIST_EXTRA = new int[30];

    /**
     * The base distance minus one of each distance code.
     */
    private static final int[] DIST_BASE = new int[30];

    /**
     * The distance code of each distance minus one up to 256, then of each 128 distances.
     */
    private static final byte[] DIST_CODE = new byte[512];

    /**
     * The fixed literal and length code lengths.
     */
    private static final int[] FIXED_LIT_LENS = new int[288];

    /**
     * The fixed literal and length codes, bit-reversed for sending.
     */
    private static final int[] FIXED_LIT_CODES = new int[288];

    /**
     * The fixed distance codes, bit-reversed for sending (all five bits long).
     */
    private static final int[] FIXED_DIST_CODES = new int[30];

    static {
        var base = 3;
        for (var code = 0; code < 28; code++) {
            LEN_BASE[code] = base;
            for (var i = 0; i < 1 << LEN_EXTRA[code]; i++) {
                LEN_CODE[base - 3 + i] = (byte) code;
            }
            base += 1 << LEN_EXTRA[code];
        }
        LEN_BASE[28] = 258;
        LEN_CODE[255] = 28;
        var dist = 0;
        for (var code = 0; code < 16; code++) {
            DIST_BASE[code] = dist;
            DIST_EXTRA[code] = code < 4 ? 0 : (code >> 1) - 1;
            for (var i = 0; i < 1 << DIST_EXTRA[code]; i++) {
                DIST_CODE[dist++] = (byte) code;
            }
        }
        dist >>= 7;
        for (var code = 16; code < 30; code++) {
            DIST_EXTRA[code] = (code >> 1) - 1;
            DIST_BASE[code] = dist << 7;
            for (var i = 0; i < 1 << (DIST_EXTRA[code] - 7); i++) {
                DIST_CODE[256 + dist++] = (byte) code;
            }
        }
        for (var i = 0; i < 288; i++) {
            FIXED_LIT_LENS[i] = i < 144 ? 8 : i < 256 ? 9 : i < 280 ? 7 : 8;
        }
        assignCodes(FIXED_LIT_LENS, 288, FIXED_LIT_CODES, new int[16]);
        for (var i = 0; i < 30; i++) {
            FIXED_DIST_CODES[i] = Integer.reverse(i) >>> 27;
        }
    }

    /**
     * The input, with the dictionary before the data and {@link #SLACK} bytes after it.
     */
    private byte[] in;

    /**
     * The output buffer.
     */
    private byte[] out;

    /**
     * The next free byte of {@link #out}.
     */
    private int op;

    /**
     * The pending bits, least significant first.
     */
    private long acc;

    /**
     * The number of pending bits, below eight between calls.
     */
    private int nbits;

    /**
     * The hash heads, holding a position plus one.
     */
    private int[] head;

    /**
     * The previous position with the same hash, plus one, by position modulo the window.
     */
    private final int[] prev = new int[WINDOW];

    /**
     * The mask that keeps the bytes the hash covers: four for levels 4 to 6 and whenever short matches
     * are discarded anyway (fewer candidates to walk), three otherwise (zlib's hash, which finds the
     * three-byte matches).
     */
    private int hashMask;

    /**
     * The shift that reduces the multiplicative hash to the table size.
     */
    private int hashShift;

    /**
     * The start of the longest match found last.
     */
    private int matchPos;

    /**
     * The buffered symbols of the open block.
     */
    private final int[] syms = new int[BLOCK_SYMBOLS];

    /**
     * The number of buffered symbols.
     */
    private int nsyms;

    /**
     * The literal and length symbol frequencies of the open block.
     */
    private final int[] litFreq = new int[286];

    /**
     * The distance symbol frequencies of the open block.
     */
    private final int[] distFreq = new int[30];

    /**
     * The literal and length code lengths of the open block.
     */
    private final int[] litLens = new int[288];

    /**
     * The distance code lengths of the open block.
     */
    private final int[] distLens = new int[32];

    /**
     * The literal and length codes of the open block.
     */
    private final int[] litCodes = new int[288];

    /**
     * The distance codes of the open block.
     */
    private final int[] distCodes = new int[32];

    /**
     * The code length symbols of the header being built, each with its extra bits above bit 8.
     */
    private final int[] clSyms = new int[320];

    /**
     * The number of entries of {@link #clSyms}.
     */
    private int clCount;

    /**
     * The frequencies of the code length symbols.
     */
    private final int[] clFreq = new int[19];

    /**
     * The code lengths of the code length symbols.
     */
    private final int[] clLens = new int[19];

    /**
     * The code length codes.
     */
    private final int[] clCodes = new int[19];

    /**
     * The sorting keys of the Huffman builder.
     */
    private final long[] keys = new long[320];

    /**
     * The node weights of the Huffman builder.
     */
    private final int[] weight = new int[640];

    /**
     * The node parents of the Huffman builder.
     */
    private final int[] parent = new int[640];

    /**
     * The number of codes of each length in the Huffman builder.
     */
    private final int[] lenCount = new int[17];

    /**
     * The work array of the code assignment.
     */
    private final int[] nextCode = new int[17];

    /**
     * The counts of the ten symbol classes observed since the last split candidate or block
     * start: the literals by bits 1 and 5-7 of the byte, and the matches shorter than nine bytes
     * or not.
     */
    private final int[] obs = new int[10];

    /**
     * The same counts for the symbols observed since the last check.
     */
    private final int[] newObs = new int[10];

    /**
     * The sum of {@link #obs}.
     */
    private int numObs;

    /**
     * The sum of {@link #newObs}.
     */
    private int numNew;

    /**
     * The index in {@link #syms} of the first symbol not yet observed.
     */
    private int observed;

    /**
     * The index in {@link #syms} of each split candidate of the open block.
     */
    private final int[] cutSym = new int[MAX_CUTS];

    /**
     * The input position of each split candidate of the open block.
     */
    private final int[] cutPos = new int[MAX_CUTS];

    /**
     * The literal and length code counts of the open block at each split candidate.
     */
    private final int[][] cutLit = new int[MAX_CUTS][286];

    /**
     * The distance code counts of the open block at each split candidate.
     */
    private final int[][] cutDist = new int[MAX_CUTS][30];

    /**
     * The literal and length code counts at the last check of the split heuristic.
     */
    private final int[] seenLit = new int[286];

    /**
     * The number of split candidates of the open block.
     */
    private int cuts;

    /**
     * The number of literal and length codes sent by the last {@link #plan}.
     */
    private int planLit;

    /**
     * The number of distance codes sent by the last {@link #plan}.
     */
    private int planDist;

    /**
     * The number of code length code lengths sent by the last {@link #plan}.
     */
    private int planHclen;

    /**
     * The dynamic block bits of the last {@link #plan}.
     */
    private long planDynamic;

    /**
     * The fixed block bits of the last {@link #plan}.
     */
    private long planFixed;

    /**
     * The stored block bits of the last {@link #plan}.
     */
    private long planStored;

    /**
     * Creates a compressor.
     */
    private ZlibDeflater() {
    }

    /**
     * Compresses data as a zlib stream.
     *
     * @param data the bytes
     * @param offset the index of the first byte
     * @param length the number of bytes
     * @param level the level from 0 (stored) to 9 (smallest)
     * @return the stream
     */
    public static byte[] zlib(byte[] data, int offset, int length, int level) {
        return zlib(data, offset, length, level, false);
    }

    /**
     * Compresses data as a zlib stream, optionally discarding short matches as zlib's filtered
     * strategy does.
     *
     * @param data the bytes
     * @param offset the index of the first byte
     * @param length the number of bytes
     * @param level the level from 0 (stored) to 9 (smallest)
     * @param filtered {@code true} to discard matches of five bytes or fewer, as zlib does for filtered data
     * @return the stream
     */
    public static byte[] zlib(byte[] data, int offset, int length, int level, boolean filtered) {
        var buffer = new byte[length + SLACK];
        System.arraycopy(data, offset, buffer, 0, length);
        var effective = level < 0 ? 6 : level;
        var flags = effective < 2 ? 0 : effective < 6 ? 1 : effective == 6 ? 2 : 3;
        var header = (0x78 << 8) | (flags << 6);
        header += 31 - header % 31;
        var adler = new Adler32();
        adler.update(data, offset, length);
        var segment = new ZlibDeflater().run(buffer, 0, length, effective, filtered, true, length / 2 + 1024);
        var size = segment.length;
        var stream = new byte[size + 6];
        stream[0] = (byte) (header >>> 8);
        stream[1] = (byte) header;
        System.arraycopy(segment, 0, stream, 2, size);
        var sum = (int) adler.getValue();
        stream[size + 2] = (byte) (sum >>> 24);
        stream[size + 3] = (byte) (sum >>> 16);
        stream[size + 4] = (byte) (sum >>> 8);
        stream[size + 5] = (byte) sum;
        return stream;
    }

    /**
     * Compresses data into a raw deflate segment that continues a stream whose previous bytes are
     * the dictionary: matches may reach back into the dictionary, which is not sent.
     *
     * <p>A segment that is not the last ends with an empty stored block, so it ends on a byte
     * boundary and the next segment's bytes can follow it directly.
     *
     * @param buffer the dictionary, then the data, then at least 16 spare bytes
     * @param dictionary the number of dictionary bytes at the start of the buffer, up to 32 768
     * @param length the number of data bytes after the dictionary
     * @param level the level from 0 (stored) to 9 (smallest)
     * @param filtered {@code true} to discard matches of five bytes or fewer, as zlib does for filtered data
     * @param last {@code true} when this segment ends the stream
     * @return the segment
     * @throws IllegalArgumentException if the buffer lacks the spare bytes
     */
    public static byte[] segment(byte[] buffer, int dictionary, int length, int level, boolean filtered, boolean last) {
        if (buffer.length < dictionary + length + SLACK) {
            throw new IllegalArgumentException("buffer lacks spare bytes");
        }
        return new ZlibDeflater().run(buffer, dictionary, length, level < 0 ? 6 : level, filtered, last, length / 2 + 1024);
    }

    /**
     * Runs the compression.
     *
     * @param buffer the dictionary, data and spare bytes
     * @param start the dictionary length
     * @param length the data length
     * @param level the compression level, from 0 to 9
     * @param filtered the filtered strategy
     * @param last whether the data ends the stream
     * @param capacity the initial output capacity
     * @return the output
     */
    private byte[] run(byte[] buffer, int start, int length, int level, boolean filtered, boolean last, int capacity) {
        in = buffer;
        out = new byte[Math.max(capacity, 256) + SLACK];
        op = 0;
        var end = start + length;
        if (level == 0) {
            writeStored(start, end, last);
        } else {
            var bits = 10;
            while (bits < 16 && (1 << bits) < end) {
                bits++;
            }
            head = new int[1 << bits];
            hashShift = 32 - bits;
            hashMask = level > 3 && (filtered || level < 7) ? -1 : 0xFFFFFF;
            for (var p = 0; p < start; p++) {
                insert(p);
            }
            if (level <= 3) {
                greedy(start, end, level, last);
            } else {
                lazy(start, end, level, filtered, last);
            }
        }
        if (!last) {
            ensure(16);
            put(0, 3);
            alignAndFlush();
            put(0, 16);
            put(0xFFFF, 16);
        } else {
            alignAndFlush();
        }
        return Arrays.copyOf(out, op);
    }

    /**
     * Hashes the four bytes at a position and links the position into the chains.
     *
     * @param pos the position
     * @return the previous position with the same hash, or -1
     */
    private int insert(int pos) {
        var h = (((int) INT.get(in, pos) & hashMask) * 0x9E3779B1) >>> hashShift;
        var candidate = head[h];
        prev[pos & WMASK] = candidate;
        head[h] = pos + 1;
        return candidate - 1;
    }

    /**
     * Finds the longest match for a position by walking its hash chain.
     *
     * @param cur the position
     * @param cand the first candidate, before {@code cur}
     * @param best the length to beat
     * @param chain the number of candidates to try
     * @param nice the length that ends the search
     * @param maxLen the longest allowed match
     * @return the best length, at least {@code best}; {@link #matchPos} holds its start when it exceeds {@code best}
     */
    private int longest(int cur, int cand, int best, int chain, int nice, int maxLen) {
        final var in = this.in;
        final var prev = this.prev;
        if (best >= maxLen) {
            return best;
        }
        if (nice > maxLen) {
            nice = maxLen;
        }
        var limit = cur - MAX_DIST;
        do {
            if (in[cand + best] == in[cur + best] && in[cand] == in[cur]) {
                var len = 0;
                while (true) {
                    if (len + 8 > maxLen) {
                        while (len < maxLen && in[cand + len] == in[cur + len]) {
                            len++;
                        }
                        break;
                    }
                    var x = (long) LONG.get(in, cand + len) ^ (long) LONG.get(in, cur + len);
                    if (x != 0) {
                        len += Long.numberOfTrailingZeros(x) >>> 3;
                        break;
                    }
                    len += 8;
                }
                if (len > best) {
                    best = len;
                    matchPos = cand;
                    if (len >= nice) {
                        break;
                    }
                }
            }
            var next = prev[cand & WMASK] - 1;
            if (next < 0 || next < limit || next >= cand) {
                break;
            }
            cand = next;
        } while (--chain > 0);
        return best;
    }

    /**
     * Parses greedily, for levels 1 to 3.
     *
     * @param start the first data position
     * @param end the end of the data
     * @param level the compression level, from 1 to 3
     * @param last whether the data ends the stream
     */
    private void greedy(int start, int end, int level, boolean last) {
        var nice = NICE[level];
        var chainLimit = CHAIN[level];
        var maxInsert = LAZY[level];
        var blockStart = start;
        var segmentStart = start;
        var pos = start;
        while (pos < end) {
            var cand = insert(pos);
            var length = 0;
            if (cand >= 0 && pos - cand <= MAX_DIST) {
                var maxLen = Math.min(MAX_MATCH, end - pos);
                length = longest(pos, cand, 2, chainLimit, nice, maxLen);
            }
            if (length >= 3) {
                match(length, pos - matchPos);
                if (length <= maxInsert) {
                    for (var p = pos + 1; p < pos + length; p++) {
                        insert(p);
                    }
                }
                pos += length;
            } else {
                literal(in[pos] & 0xFF);
                pos++;
            }
            if (nsyms == BLOCK_SYMBOLS && pos < end) {
                flushBlock(blockStart, pos, false);
                blockStart = pos;
                segmentStart = pos;
            } else if (nsyms - observed >= CHECK_SYMBOLS && pos - segmentStart >= MIN_SEGMENT && end - pos >= MIN_SEGMENT
                    && cuts < MAX_CUTS && distributionChanged(pos - segmentStart)) {
                recordCut(pos);
                segmentStart = pos;
            }
        }
        flushBlock(blockStart, end, last);
    }

    /**
     * Parses lazily, for levels 4 to 9.
     *
     * @param start the first data position
     * @param end the end of the data
     * @param level the compression level, from 4 to 9
     * @param filtered the filtered strategy
     * @param last whether the data ends the stream
     */
    private void lazy(int start, int end, int level, boolean filtered, boolean last) {
        var nice = NICE[level];
        var chainLimit = CHAIN[level];
        var good = GOOD[level];
        var maxLazy = LAZY[level];
        var blockStart = start;
        var segmentStart = start;
        var pos = start;
        var covered = start;
        var matchLen = 2;
        var available = false;
        while (pos < end) {
            var cand = insert(pos);
            var prevLen = matchLen;
            var prevPos = matchPos;
            matchLen = 2;
            if (cand >= 0 && prevLen < maxLazy && pos - cand <= MAX_DIST) {
                var chain = prevLen >= good ? chainLimit >> 2 : chainLimit;
                var maxLen = Math.min(MAX_MATCH, end - pos);
                matchLen = longest(pos, cand, prevLen, chain, nice, maxLen);
                if (matchLen <= 5 && (filtered || (matchLen == 3 && pos - matchPos > 4096))) {
                    matchLen = 2;
                }
            }
            if (prevLen >= 3 && matchLen <= prevLen) {
                match(prevLen, pos - 1 - prevPos);
                var stop = pos - 1 + prevLen;
                for (var p = pos + 1; p < stop; p++) {
                    insert(p);
                }
                pos = stop;
                covered = stop;
                available = false;
                matchLen = 2;
            } else if (available) {
                literal(in[pos - 1] & 0xFF);
                covered = pos;
                pos++;
            } else {
                available = true;
                pos++;
                continue;
            }
            if (nsyms == BLOCK_SYMBOLS && covered < end) {
                flushBlock(blockStart, covered, false);
                blockStart = covered;
                segmentStart = covered;
            } else if (nsyms - observed >= CHECK_SYMBOLS && covered - segmentStart >= MIN_SEGMENT && end - covered >= MIN_SEGMENT
                    && cuts < MAX_CUTS && distributionChanged(covered - segmentStart)) {
                recordCut(covered);
                segmentStart = covered;
            }
        }
        if (available) {
            literal(in[end - 1] & 0xFF);
        }
        flushBlock(blockStart, end, last);
    }

    /**
     * Buffers a literal.
     *
     * @param value the byte value
     */
    private void literal(int value) {
        syms[nsyms++] = value;
        litFreq[value]++;

    }

    /**
     * Buffers a match.
     *
     * @param length the length, from 3 to 258
     * @param distance the distance, from 1 to the window
     */
    private void match(int length, int distance) {
        syms[nsyms++] = MATCH | ((length - 3) << 15) | (distance - 1);

        litFreq[257 + LEN_CODE[length - 3]]++;
        var d = distance - 1;
        distFreq[d < 256 ? DIST_CODE[d] : DIST_CODE[256 + (d >> 7)]]++;
    }

    /**
     * Makes sure the output has room for a number of bytes.
     *
     * @param bytes the bytes needed
     */
    private void ensure(int bytes) {
        if (out.length - op < bytes + SLACK) {
            out = Arrays.copyOf(out, Math.max(out.length * 2, op + bytes + SLACK));
        }
    }

    /**
     * Appends bits to the accumulator and moves whole bytes to the output.
     *
     * @param value the bits, below {@code 1 << count}
     * @param count the number of bits, up to 32
     */
    private void put(int value, int count) {
        acc |= ((long) value & 0xFFFFFFFFL) << nbits;
        nbits += count;
        LONG.set(out, op, acc);
        op += nbits >>> 3;
        acc >>>= nbits & ~7;
        nbits &= 7;
    }

    /**
     * Pads to a byte boundary.
     */
    private void alignAndFlush() {
        if (nbits > 0) {
            ensure(16);
            put(0, 8 - nbits);
        }
    }

    /**
     * Writes stored blocks for a range of the input.
     *
     * @param from the first byte
     * @param to the end
     * @param last whether this ends the stream
     */
    private void writeStored(int from, int to, boolean last) {
        var pos = from;
        do {
            var count = Math.min(65535, to - pos);
            var finalBlock = last && pos + count == to;
            ensure(count + 16);
            put(finalBlock ? 1 : 0, 3);
            alignAndFlush();
            put(count | ((~count & 0xFFFF) << 16), 32);
            System.arraycopy(in, pos, out, op, count);
            op += count;
            pos += count;
        } while (pos < to);
    }

    /**
     * Records a split candidate at the current end of the buffered symbols.
     *
     * @param position the input position of the candidate
     */
    private void recordCut(int position) {
        cutSym[cuts] = nsyms;
        cutPos[cuts] = position;
        System.arraycopy(litFreq, 0, cutLit[cuts], 0, 286);
        System.arraycopy(distFreq, 0, cutDist[cuts], 0, 30);
        cuts++;
    }

    /**
     * Tells whether the symbols observed since the last check differ enough from those before them
     * to start a new block, and folds them into the earlier ones when they do not. A candidate
     * resets the observations.
     *
     * @param length the input bytes since the block or the last candidate began
     * @return {@code true} when a split candidate starts here
     */
    private boolean distributionChanged(int length) {
        for (var v = 0; v < 256; v++) {
            newObs[((v >> 5) & 6) | (v & 1)] += litFreq[v] - seenLit[v];
        }
        for (var code = 257; code < 286; code++) {
            newObs[code < 263 ? 8 : 9] += litFreq[code] - seenLit[code];
        }
        System.arraycopy(litFreq, 0, seenLit, 0, 286);
        numNew = nsyms - observed;
        observed = nsyms;
        if (numObs > 0) {
            var delta = 0L;
            for (var i = 0; i < 10; i++) {
                var expected = (long) obs[i] * numNew;
                var actual = (long) newObs[i] * numObs;
                delta += Math.abs(actual - expected);
            }
            if (delta + (long) (length / 4096) * numObs >= (long) CUT_THRESHOLD * numObs) {
                Arrays.fill(obs, 0);
                Arrays.fill(newObs, 0);
                numObs = 0;
                numNew = 0;
                return true;
            }
        }
        for (var i = 0; i < 10; i++) {
            obs[i] += newObs[i];
            newObs[i] = 0;
        }
        numObs += numNew;
        numNew = 0;
        return false;
    }

    /**
     * Closes the open block over a range of the input, splitting it where that makes it smaller.
     *
     * @param from the first byte the block covers
     * @param to the end of the bytes it covers
     * @param last whether this is the final block of the stream
     */
    private void flushBlock(int from, int to, boolean last) {
        if (nsyms == 0 && !last) {
            return;
        }
        if (cuts == 0) {
            emitBlock(0, nsyms, from, to, litFreq, distFreq, last);
        } else {
            flushSplit(from, to, last);
        }
        nsyms = 0;
        observed = 0;
        Arrays.fill(seenLit, 0);
        cuts = 0;
        Arrays.fill(litFreq, 0);
        Arrays.fill(distFreq, 0);
        Arrays.fill(obs, 0);
        Arrays.fill(newObs, 0);
        numObs = 0;
        numNew = 0;
    }

    /**
     * Closes the open block as a run of blocks cut at its split candidates, merging neighboring
     * segments whenever the estimated size of one block over both is no larger than of two, and
     * keeping the single block when the exact sizes say that it is no larger than the run.
     *
     * @param from the first byte the block covers
     * @param to the end of the bytes it covers
     * @param last whether this is the final block of the stream
     */
    private void flushSplit(int from, int to, boolean last) {
        var segments = cuts + 1;
        var symAt = new int[segments + 1];
        var posAt = new int[segments + 1];
        posAt[0] = from;
        for (var c = 0; c < cuts; c++) {
            symAt[c + 1] = cutSym[c];
            posAt[c + 1] = cutPos[c];
        }
        symAt[segments] = nsyms;
        posAt[segments] = to;
        var lits = new int[segments][];
        var dists = new int[segments][];
        var starts = new int[segments + 1];
        var blocks = 0;
        var curLit = new int[286];
        var curDist = new int[30];
        segmentCounts(0, curLit, curDist);
        var curCost = estimate(curLit, curDist);
        var segLit = new int[286];
        var segDist = new int[30];
        var mergedLit = new int[286];
        var mergedDist = new int[30];
        starts[0] = 0;
        for (var k = 1; k < segments; k++) {
            segmentCounts(k, segLit, segDist);
            var segCost = estimate(segLit, segDist);
            for (var i = 0; i < 286; i++) {
                mergedLit[i] = curLit[i] + segLit[i];
            }
            for (var i = 0; i < 30; i++) {
                mergedDist[i] = curDist[i] + segDist[i];
            }
            var mergedCost = estimate(mergedLit, mergedDist);
            if (mergedCost <= curCost + segCost) {
                System.arraycopy(mergedLit, 0, curLit, 0, 286);
                System.arraycopy(mergedDist, 0, curDist, 0, 30);
                curCost = mergedCost;
            } else {
                lits[blocks] = curLit.clone();
                dists[blocks] = curDist.clone();
                blocks++;
                starts[blocks] = k;
                System.arraycopy(segLit, 0, curLit, 0, 286);
                System.arraycopy(segDist, 0, curDist, 0, 30);
                curCost = segCost;
            }
        }
        lits[blocks] = curLit;
        dists[blocks] = curDist;
        blocks++;
        starts[blocks] = segments;
        if (blocks > 1) {
            long whole = blockCost(litFreq, distFreq, to - from);
            long run = 0;
            for (var b = 0; b < blocks; b++) {
                run += blockCost(lits[b], dists[b], posAt[starts[b + 1]] - posAt[starts[b]]);
            }
            if (run >= whole) {
                blocks = 1;
            }
        }
        if (blocks == 1) {
            emitBlock(0, nsyms, from, to, litFreq, distFreq, last);
            return;
        }
        for (var b = 0; b < blocks; b++) {
            emitBlock(symAt[starts[b]], symAt[starts[b + 1]], posAt[starts[b]], posAt[starts[b + 1]], lits[b], dists[b],
                    last && b + 1 == blocks);
        }
    }

    /**
     * Estimates the bits of a dynamic block over the given frequencies from the entropy of the
     * symbols, the extra bits, and a header proportional to the symbols in use.
     *
     * @param lit the literal and length code counts, without the end of block
     * @param dist the distance code counts
     * @return the estimated bits
     */
    private static long estimate(int[] lit, int[] dist) {
        long total = 1;
        var used = 1;
        long extra = 0;
        for (var i = 0; i < 286; i++) {
            var f = lit[i];
            if (f != 0) {
                total += f;
                used++;
                if (i >= 257) {
                    extra += (long) f * LEN_EXTRA[i - 257];
                }
            }
        }
        long distTotal = 0;
        for (var i = 0; i < 30; i++) {
            var f = dist[i];
            if (f != 0) {
                distTotal += f;
                used++;
                extra += (long) f * DIST_EXTRA[i];
            }
        }
        long scaled = 0;
        var litLog = log2(total);
        for (var i = 0; i < 286; i++) {
            var f = lit[i];
            if (f != 0) {
                scaled += (long) f * (litLog - log2(f));
            }
        }
        scaled += litLog;
        if (distTotal != 0) {
            var distLog = log2(distTotal);
            for (var i = 0; i < 30; i++) {
                var f = dist[i];
                if (f != 0) {
                    scaled += (long) f * (distLog - log2(f));
                }
            }
        }
        return scaled / 256 + scaled / 256 / 64 + extra + 5L * used + 60;
    }

    /**
     * Returns the binary logarithm of a positive count in units of 1/256.
     *
     * @param value the count
     * @return {@code log2(value) * 256}, rounded
     */
    private static int log2(long value) {
        var shift = 63 - Long.numberOfLeadingZeros(value);
        var mantissa = shift >= 8 ? (int) (value >>> (shift - 8)) & 0xFF : (int) (value << (8 - shift)) & 0xFF;
        return (shift << 8) + MANTISSA_LOG[mantissa];
    }

    /**
     * The binary logarithm of {@code 1 + i / 256} in units of 1/256.
     */
    private static final int[] MANTISSA_LOG = new int[256];

    static {
        for (var i = 0; i < 256; i++) {
            MANTISSA_LOG[i] = (int) Math.round(256 * StrictMath.log(1 + i / 256.0) / StrictMath.log(2));
        }
    }

    /**
     * Sets the code frequencies of one segment of the open block from the counts recorded at its
     * bounding split candidates.
     *
     * @param segment the segment index
     * @param lit receives the literal and length code counts
     * @param dist receives the distance code counts
     */
    private void segmentCounts(int segment, int[] lit, int[] dist) {
        var upperLit = segment < cuts ? cutLit[segment] : litFreq;
        var upperDist = segment < cuts ? cutDist[segment] : distFreq;
        if (segment == 0) {
            System.arraycopy(upperLit, 0, lit, 0, 286);
            System.arraycopy(upperDist, 0, dist, 0, 30);
            return;
        }
        var lowerLit = cutLit[segment - 1];
        var lowerDist = cutDist[segment - 1];
        for (var i = 0; i < 286; i++) {
            lit[i] = upperLit[i] - lowerLit[i];
        }
        for (var i = 0; i < 30; i++) {
            dist[i] = upperDist[i] - lowerDist[i];
        }
    }

    /**
     * Returns the bits the cheapest block over the given frequencies takes.
     *
     * @param lit the literal and length code counts, without the end of block
     * @param dist the distance code counts
     * @param raw the input bytes the block covers
     * @return the bits
     */
    private long blockCost(int[] lit, int[] dist, int raw) {
        lit[256] = 1;
        plan(lit, dist, raw);
        lit[256] = 0;
        return Math.min(planStored, Math.min(planFixed, planDynamic));
    }

    /**
     * Builds the dynamic code for the given frequencies and sets the sizes of the three block
     * types in {@link #planDynamic}, {@link #planFixed} and {@link #planStored}.
     *
     * @param lit the literal and length code counts, with the end of block counted
     * @param dist the distance code counts
     * @param raw the input bytes the block covers
     */
    private void plan(int[] lit, int[] dist, int raw) {
        buildLengths(lit, 286, 15, litLens);
        buildLengths(dist, 30, 15, distLens);
        var hlit = 286;
        while (hlit > 257 && litLens[hlit - 1] == 0) {
            hlit--;
        }
        var hdist = 30;
        while (hdist > 1 && distLens[hdist - 1] == 0) {
            hdist--;
        }
        long extra = 0;
        long dynamic = 0;
        long fixed = 0;
        for (var i = 0; i < 286; i++) {
            var f = lit[i];
            if (f != 0) {
                dynamic += (long) f * litLens[i];
                fixed += (long) f * FIXED_LIT_LENS[i];
                if (i >= 257) {
                    extra += (long) f * LEN_EXTRA[i - 257];
                }
            }
        }
        for (var i = 0; i < 30; i++) {
            var f = dist[i];
            if (f != 0) {
                dynamic += (long) f * distLens[i];
                fixed += (long) f * 5;
                extra += (long) f * DIST_EXTRA[i];
            }
        }
        dynamic += extra + 3;
        fixed += extra + 3;
        var hclen = buildHeader(hlit, hdist);
        dynamic += 14 + 3L * hclen + headerBits();
        planLit = hlit;
        planDist = hdist;
        planHclen = hclen;
        planDynamic = dynamic;
        planFixed = fixed;
        planStored = 8L * raw + 40L * (raw / 65535 + 1) + 7;
    }

    /**
     * Writes one block over a range of the input and of the buffered symbols as the smallest of
     * stored, fixed and dynamic.
     *
     * @param symFrom the index of the block's first buffered symbol
     * @param symTo the end index of its symbols
     * @param from the first byte the block covers
     * @param to the end of the bytes it covers
     * @param lit the literal and length code counts of its symbols; the end of block is counted in place
     * @param dist the distance code counts of its symbols
     * @param last whether this is the final block of the stream
     */
    private void emitBlock(int symFrom, int symTo, int from, int to, int[] lit, int[] dist, boolean last) {
        var raw = to - from;
        ensure(raw + 5 * (raw / 65535 + 2) + 64);
        lit[256] = 1;
        plan(lit, dist, raw);
        var hlit = planLit;
        var hdist = planDist;
        var hclen = planHclen;
        if (planStored <= planDynamic && planStored <= planFixed) {
            writeStored(from, to, last);
        } else if (planFixed <= planDynamic) {
            put(last ? 3 : 2, 3);
            writeSymbols(symFrom, symTo, FIXED_LIT_LENS, FIXED_LIT_CODES, null, FIXED_DIST_CODES, true);
        } else {
            put((last ? 1 : 0) | (2 << 1), 3);
            put((hlit - 257) | ((hdist - 1) << 5) | ((hclen - 4) << 10), 14);
            for (var i = 0; i < hclen; i++) {
                put(clLens[CL_ORDER[i]], 3);
            }
            for (var i = 0; i < clCount; i++) {
                var s = clSyms[i] & 0xFF;
                var e = clSyms[i] >>> 8;
                put(clCodes[s], clLens[s]);
                if (s == 16) {
                    put(e, 2);
                } else if (s == 17) {
                    put(e, 3);
                } else if (s == 18) {
                    put(e, 7);
                }
            }
            assignCodes(litLens, 286, litCodes, nextCode);
            assignCodes(distLens, 30, distCodes, nextCode);
            writeSymbols(symFrom, symTo, litLens, litCodes, distLens, distCodes, false);
        }
    }

    /**
     * Sends a range of the buffered symbols and the end-of-block code.
     *
     * @param symFrom the index of the first symbol
     * @param symTo the end index
     * @param lens the literal and length code lengths
     * @param codes the literal and length codes
     * @param dLens the distance code lengths, unused for the fixed code
     * @param dCodes the distance codes
     * @param fixedDist {@code true} for the fixed code, whose distance codes are five bits
     */
    private void writeSymbols(int symFrom, int symTo, int[] lens, int[] codes, int[] dLens, int[] dCodes, boolean fixedDist) {
        final var syms = this.syms;
        final var out = this.out;
        var op = this.op;
        var acc = this.acc;
        var n = this.nbits;
        var lenValue = new int[256];
        var lenBits = new int[256];
        for (var l = 0; l < 256; l++) {
            var code = LEN_CODE[l];
            var sym = 257 + code;
            var extraBits = LEN_EXTRA[code];
            lenValue[l] = codes[sym] | ((l + 3 - LEN_BASE[code]) << lens[sym]);
            lenBits[l] = lens[sym] + extraBits;
        }
        for (var i = symFrom; i < symTo; i++) {
            var s = syms[i];
            if ((s & MATCH) == 0) {
                acc |= (long) codes[s] << n;
                n += lens[s];
            } else {
                var l = (s >>> 15) & 0xFF;
                acc |= (long) lenValue[l] << n;
                n += lenBits[l];
                var d = s & 0x7FFF;
                var dc = d < 256 ? DIST_CODE[d] : DIST_CODE[256 + (d >> 7)];
                var dBits = fixedDist ? 5 : dLens[dc];
                acc |= ((long) dCodes[dc] | ((long) (d - DIST_BASE[dc]) << dBits)) << n;
                n += dBits + DIST_EXTRA[dc];
            }
            LONG.set(out, op, acc);
            op += n >>> 3;
            acc >>>= n & ~7;
            n &= 7;
        }
        acc |= (long) codes[256] << n;
        n += lens[256];
        LONG.set(out, op, acc);
        op += n >>> 3;
        acc >>>= n & ~7;
        n &= 7;
        this.op = op;
        this.acc = acc;
        this.nbits = n;
    }

    /**
     * Run-length encodes the code lengths of the open block into the code length symbols and
     * builds the code that sends them.
     *
     * @param hlit the number of literal and length codes sent
     * @param hdist the number of distance codes sent
     * @return the number of code length code lengths sent
     */
    private int buildHeader(int hlit, int hdist) {
        Arrays.fill(clFreq, 0);
        clCount = 0;
        var total = hlit + hdist;
        var i = 0;
        while (i < total) {
            var v = i < hlit ? litLens[i] : distLens[i - hlit];
            var run = 1;
            while (i + run < total && (i + run < hlit ? litLens[i + run] : distLens[i + run - hlit]) == v) {
                run++;
            }
            i += run;
            if (v == 0) {
                while (run >= 11) {
                    var t = Math.min(run, 138);
                    clSyms[clCount++] = 18 | ((t - 11) << 8);
                    clFreq[18]++;
                    run -= t;
                }
                if (run >= 3) {
                    clSyms[clCount++] = 17 | ((run - 3) << 8);
                    clFreq[17]++;
                    run = 0;
                }
                for (; run > 0; run--) {
                    clSyms[clCount++] = 0;
                    clFreq[0]++;
                }
            } else {
                clSyms[clCount++] = v;
                clFreq[v]++;
                run--;
                while (run >= 3) {
                    var t = Math.min(run, 6);
                    clSyms[clCount++] = 16 | ((t - 3) << 8);
                    clFreq[16]++;
                    run -= t;
                }
                for (; run > 0; run--) {
                    clSyms[clCount++] = v;
                    clFreq[v]++;
                }
            }
        }
        buildLengths(clFreq, 19, 7, clLens);
        assignCodes(clLens, 19, clCodes, nextCode);
        var hclen = 19;
        while (hclen > 4 && clLens[CL_ORDER[hclen - 1]] == 0) {
            hclen--;
        }
        return hclen;
    }

    /**
     * Returns the bits the code length symbols of the header take.
     *
     * @return the bits, extra bits included
     */
    private long headerBits() {
        long bits = 0;
        for (var s = 0; s < 19; s++) {
            bits += (long) clFreq[s] * clLens[s];
        }
        return bits + 2L * clFreq[16] + 3L * clFreq[17] + 7L * clFreq[18];
    }

    /**
     * Builds length-limited Huffman code lengths.
     *
     * @param freq the symbol frequencies
     * @param n the number of symbols
     * @param maxLen the longest allowed code
     * @param lens receives the code length of each symbol, zero for unused symbols
     */
    private void buildLengths(int[] freq, int n, int maxLen, int[] lens) {
        Arrays.fill(lens, 0, n, 0);
        var m = 0;
        for (var s = 0; s < n; s++) {
            if (freq[s] > 0) {
                keys[m++] = ((long) freq[s] << 32) | s;
            }
        }
        for (var s = 0; m < 2; s++) {
            if (freq[s] == 0) {
                keys[m++] = (1L << 32) | s;
            }
        }
        Arrays.sort(keys, 0, m);
        for (var i = 0; i < m; i++) {
            weight[i] = (int) (keys[i] >>> 32);
        }
        var leaf = 0;
        var inner = m;
        var next = m;
        for (var k = 0; k < m - 1; k++) {
            var a = leaf < m && (inner >= next || weight[leaf] <= weight[inner]) ? leaf++ : inner++;
            var b = leaf < m && (inner >= next || weight[leaf] <= weight[inner]) ? leaf++ : inner++;
            weight[next] = weight[a] + weight[b];
            parent[a] = next;
            parent[b] = next;
            next++;
        }
        var root = next - 1;
        parent[root] = 0;
        var depth = weight;
        depth[root] = 0;
        Arrays.fill(lenCount, 0);
        for (var node = root - 1; node >= m; node--) {
            depth[node] = depth[parent[node]] + 1;
        }
        for (var i = 0; i < m; i++) {
            lenCount[Math.min(depth[parent[i]] + 1, maxLen)]++;
        }
        var kraft = 0;
        for (var l = 1; l <= maxLen; l++) {
            kraft += lenCount[l] << (maxLen - l);
        }
        while (kraft != 1 << maxLen) {
            lenCount[maxLen]--;
            for (var l = maxLen - 1; l > 0; l--) {
                if (lenCount[l] != 0) {
                    lenCount[l]--;
                    lenCount[l + 1] += 2;
                    break;
                }
            }
            kraft--;
        }
        var idx = 0;
        for (var l = maxLen; l > 0; l--) {
            for (var c = lenCount[l]; c > 0; c--) {
                lens[(int) keys[idx++] & 0xFFFF] = l;
            }
        }
    }

    /**
     * Assigns canonical codes, bit-reversed for sending least significant bit first.
     *
     * @param lens the code lengths
     * @param n the number of symbols
     * @param codes receives the codes
     * @param next a work array of 16 entries
     */
    private static void assignCodes(int[] lens, int n, int[] codes, int[] next) {
        var count = new int[16];
        for (var s = 0; s < n; s++) {
            count[lens[s]]++;
        }
        count[0] = 0;
        var code = 0;
        for (var l = 1; l < 16; l++) {
            code = (code + count[l - 1]) << 1;
            next[l] = code;
        }
        for (var s = 0; s < n; s++) {
            var l = lens[s];
            if (l != 0) {
                codes[s] = Integer.reverse(next[l]++) >>> (32 - l);
            }
        }
    }
}
