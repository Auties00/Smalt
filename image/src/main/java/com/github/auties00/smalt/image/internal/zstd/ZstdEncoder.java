package com.github.auties00.smalt.image.internal.zstd;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * Compresses bytes into one Zstandard frame (RFC 8878).
 *
 * <p>The frame records its content size and has no content checksum. Up to 8 MiB of input it is a
 * single-segment frame; larger inputs get an 8 MiB window. Each block of up to 128 KiB is matched
 * with hash chains and repeat offsets, its literals are Huffman-coded when that is smaller, and its
 * sequences are coded with the predefined, a single-symbol or a transmitted FSE table, whichever is
 * smallest; a block that does not shrink is stored raw, and a block of one repeated byte as a run.
 * The output depends only on the input and the level.
 *
 * <p>This class is thread-safe; each call works on its own buffers.
 */
public final class ZstdEncoder {
    /** The shortest match. */
    private static final int MIN_MATCH = 4;

    /** The longest match a sequence can describe. */
    private static final int MAX_MATCH = 131074;

    /** The largest input written as a single-segment frame, and the window of larger inputs. */
    private static final int WINDOW = 1 << 23;

    /** The little-endian 64-bit view of byte arrays. */
    private static final VarHandle LONG = MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.LITTLE_ENDIAN);

    /** The little-endian 32-bit view of byte arrays. */
    private static final VarHandle INT = MethodHandles.byteArrayViewVarHandle(int[].class, ByteOrder.LITTLE_ENDIAN);

    /** The literal length code of each length below 64. */
    private static final int[] LL_CODE = new int[64];

    /** The match length code of each match length minus 3 below 128. */
    private static final int[] ML_CODE = new int[128];

    static {
        for (var length = 0; length < 64; length++) {
            var code = 0;
            while (code + 1 < ZstdDecoder.LL_BASE.length && ZstdDecoder.LL_BASE[code + 1] <= length) {
                code++;
            }
            LL_CODE[length] = code;
        }
        for (var base = 0; base < 128; base++) {
            var code = 0;
            while (code + 1 < ZstdDecoder.ML_BASE.length && ZstdDecoder.ML_BASE[code + 1] - 3 <= base) {
                code++;
            }
            ML_CODE[base] = code;
        }
    }

    /** The chain depth of each level, from 1 to 9. */
    private static final int[] DEPTH = {0, 1, 2, 4, 8, 16, 32, 64, 128, 256};

    /** The match length at which each level stops searching. */
    private static final int[] NICE = {0, 16, 24, 32, 48, 64, 128, 256, 1024, 4096};

    /** The predefined tables of the three sequence fields. */
    private static final CTable DEFAULT_LL = CTable.of(ZstdDecoder.LL_DEFAULT, 6);
    private static final CTable DEFAULT_OF = CTable.of(ZstdDecoder.OF_DEFAULT, 5);
    private static final CTable DEFAULT_ML = CTable.of(ZstdDecoder.ML_DEFAULT, 6);

    /** The input. */
    private final byte[] src;

    /** The first input byte and the end of the input. */
    private final int base;
    private final int end;

    /** The compression level, from 1 to 9. */
    private final int level;

    /** The largest match distance. */
    private final int maxDistance;

    /** The hash table: the last position plus one of each hash, relative to {@link #base}. */
    private final int[] head;
    private final int hashShift;

    /** The previous position plus one with the same hash, indexed by the position masked. */
    private final int[] chain;
    private final int chainMask;

    /** The next position to add to the hash chains. */
    private int nextInsert;

    /** The three most recent offsets. */
    private int rep1 = 1;
    private int rep2 = 4;
    private int rep3 = 8;

    /** The sequences of the current block. */
    private final int[] literalLengths;
    private final int[] matchLengths;
    private final int[] offsetValues;
    private int sequences;

    /** The literals of the current block. */
    private final byte[] literals;
    private int literalCount;

    /** The output. */
    private byte[] out;
    private int outPos;

    /** The encoded block, before the raw fallback is decided. */
    private final byte[] block;

    /**
     * Constructs an encoder for one input.
     *
     * @param src the input
     * @param offset the first byte
     * @param length the number of bytes
     * @param level the level, from 1 to 9
     */
    private ZstdEncoder(byte[] src, int offset, int length, int level) {
        this.src = src;
        this.base = offset;
        this.end = offset + length;
        this.level = level;
        this.maxDistance = length > WINDOW ? WINDOW - 1 : Integer.MAX_VALUE;
        var hashLog = Math.max(10, Math.min(17, 33 - Integer.numberOfLeadingZeros(Math.max(1, length))));
        this.head = new int[1 << hashLog];
        this.hashShift = 32 - hashLog;
        var chainSize = Integer.highestOneBit(Math.max(1, Math.min(length, WINDOW)) * 2 - 1);
        this.chain = new int[level > 1 ? chainSize : 1];
        this.chainMask = chain.length - 1;
        var blockSize = Math.min(length, ZstdDecoder.MAX_BLOCK);
        this.literalLengths = new int[blockSize / MIN_MATCH + 1];
        this.matchLengths = new int[literalLengths.length];
        this.offsetValues = new int[literalLengths.length];
        this.literals = new byte[blockSize];
        this.block = new byte[blockSize * 5 + 1024];
        this.out = new byte[length + length / 64 + 64];
        this.nextInsert = offset;
    }

    /**
     * Compresses bytes into one frame.
     *
     * @param src the input
     * @param offset the first byte
     * @param length the number of bytes
     * @param level the level, from 1 (fastest) to 9 (smallest)
     * @return the frame
     * @throws IllegalArgumentException if the level is not in [1, 9]
     */
    public static byte[] compress(byte[] src, int offset, int length, int level) {
        if (level < 1 || level > 9) {
            throw new IllegalArgumentException("level " + level + " is not in [1, 9]");
        }
        java.util.Objects.checkFromIndexSize(offset, length, src.length);
        var encoder = new ZstdEncoder(src, offset, length, level);
        encoder.frame();
        return Arrays.copyOf(encoder.out, encoder.outPos);
    }

    /**
     * Writes the frame.
     */
    private void frame() {
        var length = end - base;
        var singleSegment = length <= WINDOW;
        int fcsFlag;
        int fcsBytes;
        if (singleSegment && length < 256) {
            fcsFlag = 0;
            fcsBytes = 1;
        } else if (singleSegment && length < 65792) {
            fcsFlag = 1;
            fcsBytes = 2;
        } else {
            fcsFlag = 2;
            fcsBytes = 4;
        }
        writeInt(ZstdDecoder.MAGIC);
        writeByte(fcsFlag << 6 | (singleSegment ? 0x20 : 0));
        if (!singleSegment) {
            writeByte((23 - 10) << 3);
        }
        var fcs = fcsBytes == 2 ? length - 256 : length;
        for (var i = 0; i < fcsBytes; i++) {
            writeByte(fcs >>> (8 * i));
        }
        if (length == 0) {
            writeBlockHeader(true, 0, 0);
            return;
        }
        for (var start = base; start < end; start += ZstdDecoder.MAX_BLOCK) {
            var blockEnd = Math.min(end, start + ZstdDecoder.MAX_BLOCK);
            block(start, blockEnd, blockEnd == end);
        }
    }

    /**
     * Writes one block.
     *
     * @param start the first input byte of the block
     * @param blockEnd the end of the block
     * @param last whether this is the last block
     */
    private void block(int start, int blockEnd, boolean last) {
        var size = blockEnd - start;
        var first = src[start];
        var run = true;
        for (var i = start + 1; i < blockEnd && run; i++) {
            run = src[i] == first;
        }
        if (run && size > 1) {
            insertUpTo(blockEnd);
            writeBlockHeader(last, 1, size);
            writeByte(first);
            return;
        }
        var saved1 = rep1;
        var saved2 = rep2;
        var saved3 = rep3;
        parse(start, blockEnd);
        var compressed = entropy();
        if (compressed > 0 && compressed < size) {
            writeBlockHeader(last, 2, compressed);
            ensure(compressed);
            System.arraycopy(block, 0, out, outPos, compressed);
            outPos += compressed;
        } else {
            rep1 = saved1;
            rep2 = saved2;
            rep3 = saved3;
            writeBlockHeader(last, 0, size);
            ensure(size);
            System.arraycopy(src, start, out, outPos, size);
            outPos += size;
        }
    }

    /**
     * Finds the sequences and literals of a block.
     *
     * @param start the first input byte
     * @param blockEnd the end of the block
     */
    private void parse(int start, int blockEnd) {
        sequences = 0;
        literalCount = 0;
        var anchor = start;
        var pos = start;
        var last = blockEnd - MIN_MATCH;
        var lazy = level >= 4;
        while (pos <= last) {
            var match = search(pos, blockEnd, pos == anchor);
            if (match == 0) {
                insertUpTo(pos + 1);
                pos++;
                continue;
            }
            if (lazy) {
                while (pos + 1 <= last) {
                    insertUpTo(pos + 1);
                    var next = search(pos + 1, blockEnd, false);
                    if ((next >>> 32) <= (match >>> 32) + 1) {
                        break;
                    }
                    match = next;
                    pos++;
                }
            }
            var length = (int) (match >>> 32);
            var offset = (int) match;
            emit(anchor, pos, offset, length);
            pos += length;
            anchor = pos;
            if (level <= 2 && length > 16) {
                nextInsert = Math.max(nextInsert, pos - 2);
            }
            insertUpTo(pos);
        }
        var rest = blockEnd - anchor;
        System.arraycopy(src, anchor, literals, literalCount, rest);
        literalCount += rest;
        insertUpTo(blockEnd);
    }

    /**
     * Finds the best match at a position.
     *
     * @param pos the position, at least {@value #MIN_MATCH} bytes before the block end
     * @param blockEnd the end of the block
     * @param noLiterals whether the sequence would have no literals
     * @return the match length in the high 32 bits and the offset in the low 32 bits, or 0 for none
     */
    private long search(int pos, int blockEnd, boolean noLiterals) {
        var limit = Math.min(blockEnd, pos + MAX_MATCH);
        var bestLength = 0;
        var bestOffset = 0;
        var repeat = noLiterals ? rep2 : rep1;
        if (repeat <= pos - base && repeat <= maxDistance) {
            var length = matchLength(pos - repeat, pos, limit);
            if (length >= MIN_MATCH) {
                bestLength = length;
                bestOffset = repeat;
            }
        }
        var nice = NICE[level];
        if (bestLength < nice) {
            var candidate = head[hash(pos)] - 1;
            var depth = DEPTH[level];
            var relative = pos - base;
            while (candidate >= 0 && depth-- > 0) {
                var distance = relative - candidate;
                if (distance <= 0 || distance > maxDistance) {
                    break;
                }
                var at = base + candidate;
                if (bestLength < limit - pos && src[at + bestLength] == src[pos + bestLength]) {
                    var length = matchLength(at, pos, limit);
                    // a repeat offset is cheaper, so a new offset must be longer by two to replace it
                    if (length > bestLength + (bestOffset == repeat && bestLength > 0 ? 1 : 0)) {
                        bestLength = length;
                        bestOffset = distance;
                        if (length >= nice) {
                            break;
                        }
                    }
                }
                if (level == 1) {
                    break;
                }
                var next = chain[candidate & chainMask] - 1;
                if (next >= candidate) {
                    break;
                }
                candidate = next;
            }
        }
        if (bestLength < MIN_MATCH) {
            return 0;
        }
        return (long) bestLength << 32 | bestOffset;
    }

    /**
     * Returns the number of equal bytes at two positions.
     *
     * @param from the earlier position
     * @param pos the later position
     * @param limit the end of the bytes to compare at the later position
     * @return the match length
     */
    private int matchLength(int from, int pos, int limit) {
        var length = 0;
        var max = limit - pos;
        while (length + 8 <= max) {
            var difference = (long) LONG.get(src, from + length) ^ (long) LONG.get(src, pos + length);
            if (difference != 0) {
                return length + (Long.numberOfTrailingZeros(difference) >>> 3);
            }
            length += 8;
        }
        while (length < max && src[from + length] == src[pos + length]) {
            length++;
        }
        return length;
    }

    /**
     * Returns the hash of the four bytes at a position.
     *
     * @param pos the position
     * @return the hash
     */
    private int hash(int pos) {
        return ((int) INT.get(src, pos) * 0x9E3779B1) >>> hashShift;
    }

    /**
     * Adds the positions before a bound to the hash chains.
     *
     * @param bound the first position not to add
     */
    private void insertUpTo(int bound) {
        var stop = Math.min(bound, end - MIN_MATCH + 1);
        for (var p = nextInsert; p < stop; p++) {
            var h = hash(p);
            var relative = p - base;
            if (level > 1) {
                chain[relative & chainMask] = head[h];
            }
            head[h] = relative + 1;
        }
        nextInsert = Math.max(nextInsert, bound);
    }

    /**
     * Records a sequence and updates the repeat offsets as a decoder does.
     *
     * @param anchor the first literal
     * @param pos the start of the match
     * @param offset the match distance
     * @param length the match length
     */
    private void emit(int anchor, int pos, int offset, int length) {
        var literalLength = pos - anchor;
        System.arraycopy(src, anchor, literals, literalCount, literalLength);
        literalCount += literalLength;
        int value;
        if (literalLength > 0) {
            value = offset == rep1 ? 1 : offset == rep2 ? 2 : offset == rep3 ? 3 : offset + 3;
        } else {
            value = offset == rep2 ? 1 : offset == rep3 ? 2 : offset == rep1 - 1 ? 3 : offset + 3;
        }
        if (value > 3) {
            rep3 = rep2;
            rep2 = rep1;
            rep1 = offset;
        } else {
            var index = value - (literalLength == 0 ? 0 : 1);
            if (index == 1) {
                rep2 = rep1;
                rep1 = offset;
            } else if (index >= 2) {
                rep3 = rep2;
                rep2 = rep1;
                rep1 = offset;
            }
        }
        literalLengths[sequences] = literalLength;
        matchLengths[sequences] = length;
        offsetValues[sequences] = value;
        sequences++;
    }

    /**
     * Codes the literals and sequences of the current block into {@link #block}.
     *
     * @return the size of the block content, or -1 when it cannot be coded
     */
    private int entropy() {
        var writer = new BitWriter(block, 0);
        var pos = literalsSection(writer);
        if (pos < 0) {
            return -1;
        }
        return sequencesSection(pos);
    }

    /**
     * Writes the literals section.
     *
     * @param writer a writer over {@link #block}
     * @return the position after the section
     */
    private int literalsSection(BitWriter writer) {
        var count = literalCount;
        if (count > 0) {
            var first = literals[0];
            var same = true;
            for (var i = 1; i < count && same; i++) {
                same = literals[i] == first;
            }
            if (same && count > 2) {
                var pos = rawLiteralsHeader(1, count);
                block[pos] = first;
                return pos + 1;
            }
        }
        if (count >= 32) {
            var coded = huffmanLiterals(writer);
            if (coded > 0) {
                return coded;
            }
        }
        var pos = rawLiteralsHeader(0, count);
        System.arraycopy(literals, 0, block, pos, count);
        return pos + count;
    }

    /**
     * Writes the header of raw or run literals at the start of {@link #block}.
     *
     * @param type 0 for raw, 1 for a run
     * @param count the number of literals
     * @return the position after the header
     */
    private int rawLiteralsHeader(int type, int count) {
        if (count < 32) {
            block[0] = (byte) (type | count << 3);
            return 1;
        }
        if (count < 4096) {
            block[0] = (byte) (type | 1 << 2 | count << 4);
            block[1] = (byte) (count >>> 4);
            return 2;
        }
        block[0] = (byte) (type | 3 << 2 | count << 4);
        block[1] = (byte) (count >>> 4);
        block[2] = (byte) (count >>> 12);
        return 3;
    }

    /**
     * Writes Huffman-coded literals at the start of {@link #block}.
     *
     * @param writer a writer over {@link #block}
     * @return the position after the section, or -1 when raw literals are smaller or the code cannot
     * be described
     */
    private int huffmanLiterals(BitWriter writer) {
        var count = literalCount;
        var histogram = new int[256];
        for (var i = 0; i < count; i++) {
            histogram[literals[i] & 0xFF]++;
        }
        var lengths = Huffman.lengths(histogram, ZstdDecoder.MAX_HUFFMAN_BITS);
        if (lengths == null) {
            return -1;
        }
        var maxBits = 0;
        var lastSymbol = 0;
        var bitsTotal = 0L;
        for (var s = 0; s < 256; s++) {
            if (lengths[s] > 0) {
                maxBits = Math.max(maxBits, lengths[s]);
                lastSymbol = s;
                bitsTotal += (long) histogram[s] * lengths[s];
            }
        }
        var weights = new int[256];
        for (var s = 0; s <= lastSymbol; s++) {
            weights[s] = lengths[s] == 0 ? 0 : maxBits + 1 - lengths[s];
        }
        var codes = new int[256];
        var at = 0;
        for (var weight = 1; weight <= maxBits; weight++) {
            for (var s = 0; s <= lastSymbol; s++) {
                if (weights[s] == weight) {
                    codes[s] = at >>> (weight - 1);
                    at += 1 << (weight - 1);
                }
            }
        }
        var streams = count <= 1023 ? 1 : 4;
        var estimate = 6 + (bitsTotal + 7) / 8 + 8;
        if (estimate + lastSymbol / 2 + 8 >= count) {
            return -1;
        }
        var headerSize = streams == 1 ? 3 : count <= 1023 ? 3 : count <= 16383 ? 4 : 5;
        var pos = headerSize;
        var description = treeDescription(weights, lastSymbol, pos);
        if (description < 0) {
            return -1;
        }
        pos = description;
        if (streams == 1) {
            pos = huffmanStream(writer, pos, 0, count, codes, lengths);
        } else {
            var segment = (count + 3) / 4;
            var jump = pos;
            pos += 6;
            for (var k = 0; k < 4; k++) {
                var from = k * segment;
                var to = Math.min(count, from + segment);
                var streamStart = pos;
                pos = huffmanStream(writer, pos, from, to, codes, lengths);
                if (k < 3) {
                    var size = pos - streamStart;
                    if (size > 0xFFFF) {
                        return -1;
                    }
                    block[jump + 2 * k] = (byte) size;
                    block[jump + 2 * k + 1] = (byte) (size >>> 8);
                }
            }
        }
        var compressed = pos - headerSize;
        if (streams == 1 && compressed > 1023) {
            return -1;
        }
        var raw = count + (count < 32 ? 1 : count < 4096 ? 2 : 3);
        if (pos >= raw) {
            return -1;
        }
        var sizeFormat = streams == 1 ? 0 : headerSize == 3 ? 1 : headerSize == 4 ? 2 : 3;
        var maxField = sizeFormat <= 1 ? 1023 : sizeFormat == 2 ? 16383 : 262143;
        if (compressed > maxField) {
            return -1;
        }
        var header = 2L | (long) sizeFormat << 2 | (long) count << 4
                | (long) compressed << (sizeFormat <= 1 ? 14 : sizeFormat == 2 ? 18 : 22);
        for (var i = 0; i < headerSize; i++) {
            block[i] = (byte) (header >>> (8 * i));
        }
        return pos;
    }

    /**
     * Writes a Huffman tree description: the direct form or the FSE-compressed form, whichever is
     * smaller.
     *
     * @param weights the weight of each symbol up to the last
     * @param lastSymbol the last symbol, whose weight is not written
     * @param pos the position in {@link #block}
     * @return the position after the description, or -1 when no form can describe the weights
     */
    private int treeDescription(int[] weights, int lastSymbol, int pos) {
        var direct = lastSymbol <= 128 ? 1 + (lastSymbol + 1) / 2 : Integer.MAX_VALUE;
        var compressed = lastSymbol >= 2 ? FseWeights.encode(weights, lastSymbol, block, pos + 1) : -1;
        if (compressed > 0 && compressed <= 127 && compressed + 1 < direct) {
            block[pos] = (byte) compressed;
            return pos + 1 + compressed;
        }
        if (direct == Integer.MAX_VALUE) {
            return -1;
        }
        block[pos] = (byte) (127 + lastSymbol);
        for (var i = 0; i < lastSymbol; i += 2) {
            var high = weights[i];
            var low = i + 1 < lastSymbol ? weights[i + 1] : 0;
            block[pos + 1 + i / 2] = (byte) (high << 4 | low);
        }
        return pos + direct;
    }

    /**
     * Writes one Huffman-coded stream, symbols in reverse so that a decoder reads them forward.
     *
     * @param writer a writer over {@link #block}
     * @param pos the stream position
     * @param from the first literal
     * @param to the end of the literals
     * @param codes the code of each symbol
     * @param lengths the code length of each symbol
     * @return the position after the stream
     */
    private int huffmanStream(BitWriter writer, int pos, int from, int to, int[] codes, int[] lengths) {
        writer.reset(pos);
        for (var i = to - 1; i >= from; i--) {
            var symbol = literals[i] & 0xFF;
            writer.add(codes[symbol], lengths[symbol]);
        }
        return writer.close();
    }

    /**
     * Writes the sequences section.
     *
     * @param pos the position in {@link #block}
     * @return the end of the block content
     */
    private int sequencesSection(int pos) {
        var count = sequences;
        if (count < 128) {
            block[pos++] = (byte) count;
        } else if (count < 0x7F00) {
            block[pos++] = (byte) ((count >>> 8) + 0x80);
            block[pos++] = (byte) count;
        } else {
            block[pos++] = (byte) 0xFF;
            block[pos++] = (byte) (count - 0x7F00);
            block[pos++] = (byte) ((count - 0x7F00) >>> 8);
        }
        if (count == 0) {
            return pos;
        }
        var llCodes = new int[count];
        var mlCodes = new int[count];
        var ofCodes = new int[count];
        var llHistogram = new int[36];
        var mlHistogram = new int[53];
        var ofHistogram = new int[32];
        for (var n = 0; n < count; n++) {
            var ll = literalLengths[n];
            llCodes[n] = ll < 64 ? LL_CODE[ll] : 31 - Integer.numberOfLeadingZeros(ll) + 19;
            var mlBase = matchLengths[n] - 3;
            mlCodes[n] = mlBase < 128 ? ML_CODE[mlBase] : 31 - Integer.numberOfLeadingZeros(mlBase) + 36;
            ofCodes[n] = 31 - Integer.numberOfLeadingZeros(offsetValues[n]);
            llHistogram[llCodes[n]]++;
            mlHistogram[mlCodes[n]]++;
            ofHistogram[ofCodes[n]]++;
        }
        var modesAt = pos++;
        var llChoice = new Choice();
        var ofChoice = new Choice();
        var mlChoice = new Choice();
        pos = choose(llHistogram, count, 9, DEFAULT_LL, ZstdDecoder.LL_DEFAULT, 6, llChoice, pos);
        pos = choose(ofHistogram, count, 8, DEFAULT_OF, ZstdDecoder.OF_DEFAULT, 5, ofChoice, pos);
        pos = choose(mlHistogram, count, 9, DEFAULT_ML, ZstdDecoder.ML_DEFAULT, 6, mlChoice, pos);
        block[modesAt] = (byte) (llChoice.mode << 6 | ofChoice.mode << 4 | mlChoice.mode << 2);
        var writer = new BitWriter(block, pos);
        var ll = llChoice.table;
        var of = ofChoice.table;
        var ml = mlChoice.table;
        var last = count - 1;
        var mlState = ml == null ? 0 : ml.initial(mlCodes[last]);
        var ofState = of == null ? 0 : of.initial(ofCodes[last]);
        var llState = ll == null ? 0 : ll.initial(llCodes[last]);
        extras(writer, last, llCodes[last], mlCodes[last], ofCodes[last]);
        for (var n = last - 1; n >= 0; n--) {
            if (of != null) {
                ofState = of.encode(writer, ofState, ofCodes[n]);
            }
            if (ml != null) {
                mlState = ml.encode(writer, mlState, mlCodes[n]);
            }
            if (ll != null) {
                llState = ll.encode(writer, llState, llCodes[n]);
            }
            extras(writer, n, llCodes[n], mlCodes[n], ofCodes[n]);
        }
        if (ml != null) {
            writer.add(mlState, ml.log);
        }
        if (of != null) {
            writer.add(ofState, of.log);
        }
        if (ll != null) {
            writer.add(llState, ll.log);
        }
        return writer.close();
    }

    /**
     * Writes the extra bits of one sequence.
     *
     * @param writer the bit writer
     * @param n the sequence
     * @param llCode the literal length code
     * @param mlCode the match length code
     * @param ofCode the offset code
     */
    private void extras(BitWriter writer, int n, int llCode, int mlCode, int ofCode) {
        writer.add(literalLengths[n] - ZstdDecoder.LL_BASE[llCode], ZstdDecoder.LL_BITS[llCode]);
        writer.add(matchLengths[n] - ZstdDecoder.ML_BASE[mlCode], ZstdDecoder.ML_BITS[mlCode]);
        writer.add(offsetValues[n] - (1 << ofCode), ofCode);
    }

    /**
     * Holds the compression mode and table chosen for one sequence field.
     */
    private static final class Choice {
        /** The compression mode: 0 predefined, 1 single symbol, 2 transmitted table. */
        int mode;

        /** The table, or {@code null} for a single symbol. */
        CTable table;
    }

    /**
     * Chooses how to code one sequence field and writes its table description.
     *
     * @param histogram the count of each code
     * @param count the number of sequences
     * @param maxLog the largest accuracy log of a transmitted table
     * @param predefined the predefined table
     * @param defaults the predefined distribution
     * @param defaultLog the predefined accuracy log
     * @param choice receives the choice
     * @param pos the position in {@link #block}
     * @return the position after the table description
     */
    private int choose(int[] histogram, int count, int maxLog, CTable predefined, short[] defaults, int defaultLog,
                       Choice choice, int pos) {
        var distinct = 0;
        var maxSymbol = 0;
        var only = 0;
        for (var s = 0; s < histogram.length; s++) {
            if (histogram[s] > 0) {
                distinct++;
                maxSymbol = s;
                only = s;
            }
        }
        if (distinct == 1) {
            choice.mode = 1;
            choice.table = null;
            block[pos] = (byte) only;
            return pos + 1;
        }
        var predefinedCost = Double.MAX_VALUE;
        if (maxSymbol < defaults.length) {
            predefinedCost = 0;
            for (var s = 0; s <= maxSymbol; s++) {
                if (histogram[s] > 0) {
                    var probability = defaults[s] == -1 ? 1 : defaults[s];
                    predefinedCost += histogram[s] * (defaultLog - log2(probability));
                }
            }
        }
        var log = Math.min(maxLog, Math.max(5, Math.max(32 - Integer.numberOfLeadingZeros(distinct) + 1,
                32 - Integer.numberOfLeadingZeros(count - 1) - 2)));
        var normalized = CTable.normalize(histogram, maxSymbol, count, log);
        var transmittedCost = 0.0;
        for (var s = 0; s <= maxSymbol; s++) {
            if (histogram[s] > 0) {
                transmittedCost += histogram[s] * (log - log2(normalized[s]));
            }
        }
        var description = new BitWriter(block, pos);
        var descriptionEnd = CTable.writeCounts(description, normalized, maxSymbol, log);
        transmittedCost += 8.0 * (descriptionEnd - pos);
        if (predefinedCost <= transmittedCost) {
            choice.mode = 0;
            choice.table = predefined;
            return pos;
        }
        choice.mode = 2;
        choice.table = CTable.of(normalized, maxSymbol, log);
        return descriptionEnd;
    }

    private static double log2(int value) {
        return Math.log(value) / Math.log(2);
    }

    /**
     * Writes a block header.
     *
     * @param last whether this is the last block
     * @param type the block type
     * @param size the block size field
     */
    private void writeBlockHeader(boolean last, int type, int size) {
        var header = (last ? 1 : 0) | type << 1 | size << 3;
        writeByte(header);
        writeByte(header >>> 8);
        writeByte(header >>> 16);
    }

    private void writeInt(int value) {
        for (var i = 0; i < 4; i++) {
            writeByte(value >>> (8 * i));
        }
    }

    private void writeByte(int value) {
        ensure(1);
        out[outPos++] = (byte) value;
    }

    private void ensure(int count) {
        if (outPos + count > out.length) {
            out = Arrays.copyOf(out, Math.max(outPos + count, out.length + (out.length >>> 1)));
        }
    }

    /**
     * Writes bits forward, little-endian, into a byte array.
     */
    static final class BitWriter {
        /** The destination. */
        private final byte[] buf;

        /** The next byte to write. */
        private int pos;

        /** The pending bits and their number. */
        private long bits;
        private int count;

        /**
         * Constructs a writer.
         *
         * @param buf the destination
         * @param pos the first byte to write
         */
        BitWriter(byte[] buf, int pos) {
            this.buf = buf;
            this.pos = pos;
        }

        /**
         * Restarts the writer at a position.
         *
         * @param position the first byte to write
         */
        void reset(int position) {
            pos = position;
            bits = 0;
            count = 0;
        }

        /**
         * Appends the low bits of a value.
         *
         * @param value the bits to append, above bit {@code n} ignored
         * @param n the number of bits, from 0 to 32
         */
        void add(long value, int n) {
            bits |= (value & ((1L << n) - 1)) << count;
            count += n;
            if (count >= 32) {
                buf[pos] = (byte) bits;
                buf[pos + 1] = (byte) (bits >>> 8);
                buf[pos + 2] = (byte) (bits >>> 16);
                buf[pos + 3] = (byte) (bits >>> 24);
                pos += 4;
                bits >>>= 32;
                count -= 32;
            }
        }

        /**
         * Writes the pending bits, padded with zeros to a byte.
         *
         * @return the position after the last byte
         */
        int flush() {
            while (count > 0) {
                buf[pos++] = (byte) bits;
                bits >>>= 8;
                count -= 8;
            }
            count = 0;
            bits = 0;
            return pos;
        }

        /**
         * Ends a backward-read stream: a 1 bit, then zero padding to a byte.
         *
         * @return the position after the stream
         */
        int close() {
            add(1, 1);
            return flush();
        }
    }

    /**
     * Holds an FSE encoding table.
     */
    static final class CTable {
        /** The accuracy log. */
        final int log;

        /** The next state of each (symbol, state) slot. */
        private final int[] states;

        /** The bit count offset of each symbol. */
        private final int[] deltaBits;

        /** The slot offset of each symbol. */
        private final int[] deltaFind;

        private CTable(int log, int[] states, int[] deltaBits, int[] deltaFind) {
            this.log = log;
            this.states = states;
            this.deltaBits = deltaBits;
            this.deltaFind = deltaFind;
        }

        /**
         * Builds a table from a normalized distribution.
         *
         * @param normalized the normalized counts, -1 for a probability below one
         * @param log the accuracy log
         * @return the table
         */
        static CTable of(short[] normalized, int log) {
            return of(normalized, normalized.length - 1, log);
        }

        /**
         * Builds a table from a normalized distribution.
         *
         * @param normalized the normalized counts, -1 for a probability below one
         * @param maxSymbol the last symbol
         * @param log the accuracy log
         * @return the table
         */
        static CTable of(short[] normalized, int maxSymbol, int log) {
            var size = 1 << log;
            var symbolOf = new int[size];
            var cumulative = new int[maxSymbol + 2];
            var high = size - 1;
            for (var s = 0; s <= maxSymbol; s++) {
                if (normalized[s] == -1) {
                    cumulative[s + 1] = cumulative[s] + 1;
                    symbolOf[high--] = s;
                } else {
                    cumulative[s + 1] = cumulative[s] + normalized[s];
                }
            }
            var step = (size >>> 1) + (size >>> 3) + 3;
            var mask = size - 1;
            var position = 0;
            for (var s = 0; s <= maxSymbol; s++) {
                for (var i = 0; i < normalized[s]; i++) {
                    symbolOf[position] = s;
                    do {
                        position = (position + step) & mask;
                    } while (position > high);
                }
            }
            var states = new int[size];
            var next = Arrays.copyOf(cumulative, cumulative.length);
            for (var u = 0; u < size; u++) {
                states[next[symbolOf[u]]++] = size + u;
            }
            var deltaBits = new int[maxSymbol + 1];
            var deltaFind = new int[maxSymbol + 1];
            var total = 0;
            for (var s = 0; s <= maxSymbol; s++) {
                var n = normalized[s];
                if (n == 0) {
                    deltaBits[s] = ((log + 1) << 16) - size;
                } else if (n == -1 || n == 1) {
                    deltaBits[s] = (log << 16) - size;
                    deltaFind[s] = total - 1;
                    total++;
                } else {
                    var maxBitsOut = log - (31 - Integer.numberOfLeadingZeros(n - 1));
                    var minStatePlus = n << maxBitsOut;
                    deltaBits[s] = (maxBitsOut << 16) - minStatePlus;
                    deltaFind[s] = total - n;
                    total += n;
                }
            }
            return new CTable(log, states, deltaBits, deltaFind);
        }

        /**
         * Returns the state that starts coding with a symbol without writing bits.
         *
         * @param symbol the first symbol to code
         * @return the state
         */
        int initial(int symbol) {
            var nbBits = (deltaBits[symbol] + (1 << 15)) >>> 16;
            var value = (nbBits << 16) - deltaBits[symbol];
            return states[(value >>> nbBits) + deltaFind[symbol]];
        }

        /**
         * Codes a symbol.
         *
         * @param writer the bit writer
         * @param state the current state
         * @param symbol the symbol to code, one the table holds
         * @return the next state
         */
        int encode(BitWriter writer, int state, int symbol) {
            var nbBits = (state + deltaBits[symbol]) >>> 16;
            writer.add(state, nbBits);
            return states[(state >>> nbBits) + deltaFind[symbol]];
        }

        /**
         * Normalizes counts to a table size, keeping every present symbol at one or more.
         *
         * @param histogram the counts
         * @param maxSymbol the last present symbol
         * @param total the sum of the counts
         * @param log the accuracy log
         * @return the normalized counts
         */
        static short[] normalize(int[] histogram, int maxSymbol, int total, int log) {
            var size = 1 << log;
            var normalized = new short[maxSymbol + 1];
            var sum = 0;
            var largest = 0;
            for (var s = 0; s <= maxSymbol; s++) {
                if (histogram[s] == 0) {
                    continue;
                }
                var value = (int) Math.max(1, Math.round((double) histogram[s] * size / total));
                normalized[s] = (short) value;
                sum += value;
                if (histogram[s] > histogram[largest]) {
                    largest = s;
                }
            }
            var difference = size - sum;
            if (difference >= 0 || normalized[largest] + difference >= 1) {
                normalized[largest] += (short) difference;
                if (normalized[largest] >= 1) {
                    return normalized;
                }
                normalized[largest] -= (short) difference;
            }
            while (difference < 0) {
                var pick = -1;
                for (var s = 0; s <= maxSymbol; s++) {
                    if (normalized[s] > 1 && (pick < 0 || normalized[s] > normalized[pick])) {
                        pick = s;
                    }
                }
                normalized[pick]--;
                difference++;
            }
            return normalized;
        }

        /**
         * Writes a table description.
         *
         * @param writer a writer at the description position
         * @param normalized the normalized counts
         * @param maxSymbol the last symbol
         * @param log the accuracy log
         * @return the position after the description
         */
        static int writeCounts(BitWriter writer, short[] normalized, int maxSymbol, int log) {
            writer.add(log - 5, 4);
            var remaining = 1 << log;
            var s = 0;
            while (remaining > 0 && s <= maxSymbol) {
                var n = normalized[s];
                var value = n + 1;
                var nbBits = 32 - Integer.numberOfLeadingZeros(remaining + 1);
                var lowMask = (1 << (nbBits - 1)) - 1;
                var threshold = (1 << nbBits) - 1 - (remaining + 1);
                if (value < threshold) {
                    writer.add(value, nbBits - 1);
                } else if (value <= lowMask) {
                    writer.add(value, nbBits);
                } else {
                    writer.add(value + threshold, nbBits);
                }
                remaining -= n < 0 ? 1 : n;
                s++;
                if (n == 0) {
                    var zeros = 0;
                    while (s + zeros <= maxSymbol && normalized[s + zeros] == 0) {
                        zeros++;
                    }
                    s += zeros;
                    while (zeros >= 3) {
                        writer.add(3, 2);
                        zeros -= 3;
                    }
                    writer.add(zeros, 2);
                }
            }
            return writer.flush();
        }
    }

    /**
     * Compresses Huffman weights with an FSE table and two interleaved states.
     */
    static final class FseWeights {
        private FseWeights() {
        }

        /**
         * Writes the FSE-compressed weights.
         *
         * @param weights the Huffman weight of each symbol
         * @param count the number of weights, at least 2
         * @param dst the destination
         * @param pos the position in the destination
         * @return the number of bytes written, or -1 when the weights cannot be coded this way
         */
        static int encode(int[] weights, int count, byte[] dst, int pos) {
            var histogram = new int[ZstdDecoder.MAX_HUFFMAN_BITS + 1];
            var maxSymbol = 0;
            var distinct = 0;
            for (var i = 0; i < count; i++) {
                if (histogram[weights[i]]++ == 0) {
                    distinct++;
                }
                maxSymbol = Math.max(maxSymbol, weights[i]);
            }
            if (distinct < 2) {
                return -1;
            }
            var log = Math.min(6, Math.max(5, 32 - Integer.numberOfLeadingZeros(distinct) + 1));
            var normalized = CTable.normalize(histogram, maxSymbol, count, log);
            var table = CTable.of(normalized, maxSymbol, log);
            var writer = new BitWriter(dst, pos);
            var start = CTable.writeCounts(writer, normalized, maxSymbol, log);
            writer.reset(start);
            var i = count;
            int state1;
            int state2;
            if ((count & 1) != 0) {
                state1 = table.initial(weights[--i]);
                state2 = table.initial(weights[--i]);
                state1 = table.encode(writer, state1, weights[--i]);
            } else {
                state2 = table.initial(weights[--i]);
                state1 = table.initial(weights[--i]);
            }
            while (i > 0) {
                state2 = table.encode(writer, state2, weights[--i]);
                state1 = table.encode(writer, state1, weights[--i]);
            }
            writer.add(state2, log);
            writer.add(state1, log);
            return writer.close() - pos;
        }
    }

    /**
     * Builds length-limited Huffman codes.
     */
    static final class Huffman {
        private Huffman() {
        }

        /**
         * Returns code lengths for symbol counts, no longer than a limit, forming a complete code.
         *
         * @param histogram the count of each symbol
         * @param limit the longest code
         * @return the length of each symbol, 0 when absent, or {@code null} with fewer than two symbols
         */
        static int[] lengths(int[] histogram, int limit) {
            var n = 0;
            var symbols = new int[histogram.length];
            for (var s = 0; s < histogram.length; s++) {
                if (histogram[s] > 0) {
                    symbols[n++] = s;
                }
            }
            if (n < 2) {
                return null;
            }
            var sorted = new long[n];
            for (var i = 0; i < n; i++) {
                sorted[i] = (long) histogram[symbols[i]] << 16 | symbols[i];
            }
            Arrays.sort(sorted);
            var weight = new long[2 * n - 1];
            var parent = new int[2 * n - 1];
            for (var i = 0; i < n; i++) {
                weight[i] = sorted[i] >>> 16;
            }
            var leaf = 0;
            var node = n;
            var next = n;
            for (var k = 0; k < n - 1; k++) {
                var children = new int[2];
                for (var c = 0; c < 2; c++) {
                    if (leaf < n && (node >= next || weight[leaf] <= weight[node])) {
                        children[c] = leaf++;
                    } else {
                        children[c] = node++;
                    }
                }
                weight[next] = weight[children[0]] + weight[children[1]];
                parent[children[0]] = next;
                parent[children[1]] = next;
                next++;
            }
            var depth = new int[2 * n - 1];
            for (var i = 2 * n - 3; i >= 0; i--) {
                depth[i] = depth[parent[i]] + 1;
            }
            var lengths = new int[histogram.length];
            for (var i = 0; i < n; i++) {
                lengths[(int) (sorted[i] & 0xFFFF)] = Math.min(depth[i], limit);
            }
            var full = 1L << limit;
            var kraft = 0L;
            for (var s = 0; s < histogram.length; s++) {
                if (lengths[s] > 0) {
                    kraft += 1L << (limit - lengths[s]);
                }
            }
            // lengthen the least frequent symbols below the limit until the code fits
            while (kraft > full) {
                var pick = -1;
                for (var i = 0; i < n; i++) {
                    var s = (int) (sorted[i] & 0xFFFF);
                    if (lengths[s] < limit && (pick < 0 || lengths[s] > lengths[pick])) {
                        pick = s;
                    }
                }
                kraft -= 1L << (limit - lengths[pick] - 1);
                lengths[pick]++;
            }
            // shorten the most frequent symbols while the code has room, so that it is complete
            while (kraft < full) {
                var changed = false;
                for (var i = n - 1; i >= 0 && kraft < full; i--) {
                    var s = (int) (sorted[i] & 0xFFFF);
                    if (lengths[s] > 1 && kraft + (1L << (limit - lengths[s])) <= full) {
                        kraft += 1L << (limit - lengths[s]);
                        lengths[s]--;
                        changed = true;
                    }
                }
                if (!changed) {
                    return null;
                }
            }
            return lengths;
        }
    }
}
