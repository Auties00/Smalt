/* Ports Brotli (java/org/brotli/dec, the reference Java decoder). Copyright the Brotli Authors; MIT License.
   Modified for Smalt. */
package com.github.auties00.smalt.image.jxl.internal.brotli;

import com.github.auties00.smalt.MediaException;

import java.util.Arrays;
import java.util.Objects;

/**
 * Decompresses a whole Brotli stream (RFC 7932) held in memory.
 *
 * <p>JPEG XL containers compress the JPEG reconstruction data ({@code jbrd}) and, optionally,
 * metadata boxes ({@code brob}) with Brotli. The decoder keeps the whole output, so a backward
 * reference reaches any earlier byte within the window; references beyond the window or before the
 * start of the output address the static dictionary. The large-window extension is rejected, as
 * RFC 7932 streams never use it.
 *
 * <p>An invalid or truncated stream raises {@link MediaException.Malformed}; output beyond the
 * caller's bound raises {@link MediaException.LimitExceeded}.
 *
 * @implNote This implementation decodes in one shot with the reference decoder's Huffman table
 * layout (an 8-bit root table whose entries pack the code length above the symbol), command lookup
 * table and context lookup table.
 */
public final class BrotliDecoder {
    /**
     * The number of literal symbols.
     */
    private static final int NUM_LITERAL_CODES = 256;

    /**
     * The number of insert-and-copy command symbols.
     */
    private static final int NUM_COMMAND_CODES = 704;

    /**
     * The number of block length symbols.
     */
    private static final int NUM_BLOCK_LENGTH_CODES = 26;

    /**
     * The number of distance symbols that reuse the last distances.
     */
    private static final int NUM_DISTANCE_SHORT_CODES = 16;

    /**
     * The longest prefix code length.
     */
    private static final int MAX_LENGTH = 15;

    /**
     * The width of the root Huffman table.
     */
    private static final int ROOT_BITS = 8;

    /**
     * The number of code length code lengths.
     */
    private static final int CODE_LENGTH_CODES = 18;

    /**
     * The order code length code lengths are stored in.
     */
    private static final int[] CODE_LENGTH_CODE_ORDER = {1, 2, 3, 4, 0, 5, 17, 6, 16, 7, 8, 9, 10, 11, 12, 13, 14, 15};

    /**
     * The static prefix code of the code length code lengths, as {@code bits << 16 | value} by the
     * next four input bits.
     */
    private static final int[] FIXED_TABLE = {
            0x020000, 0x020004, 0x020003, 0x030002, 0x020000, 0x020004, 0x020003, 0x040001,
            0x020000, 0x020004, 0x020003, 0x030002, 0x020000, 0x020004, 0x020003, 0x040005
    };

    /**
     * The ring index offset of each short distance code.
     */
    private static final int[] DISTANCE_SHORT_CODE_INDEX_OFFSET = {0, 3, 2, 1, 0, 0, 0, 0, 0, 0, 3, 3, 3, 3, 3, 3};

    /**
     * The value offset of each short distance code.
     */
    private static final int[] DISTANCE_SHORT_CODE_VALUE_OFFSET = {0, 0, 0, 0, -1, 1, -2, 2, -3, 3, -1, 1, -2, 2, -3, 3};

    /**
     * The base of each block length symbol.
     */
    private static final int[] BLOCK_LENGTH_OFFSET = {
            1, 5, 9, 13, 17, 25, 33, 41, 49, 65, 81, 97, 113, 145, 177, 209, 241, 305, 369, 497,
            753, 1265, 2289, 4337, 8433, 16625
    };

    /**
     * The extra bits of each block length symbol.
     */
    private static final int[] BLOCK_LENGTH_N_BITS = {
            2, 2, 2, 2, 3, 3, 3, 3, 4, 4, 4, 4, 5, 5, 5, 5, 6, 6, 7, 8, 9, 10, 11, 12, 13, 24
    };

    /**
     * The extra bits of each insert length code.
     */
    private static final int[] INSERT_LENGTH_N_BITS = {
            0, 0, 0, 0, 0, 0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 7, 8, 9, 10, 12, 14, 24
    };

    /**
     * The extra bits of each copy length code.
     */
    private static final int[] COPY_LENGTH_N_BITS = {
            0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 7, 8, 9, 10, 24
    };

    /**
     * The decoded command symbols, four entries each: the insert extra bits plus the copy extra
     * bits shifted by 8, the insert length base, the copy length base and the distance context (a
     * negative context means the last distance is reused implicitly).
     */
    private static final int[] COMMANDS = commandTable();

    /**
     * The context lookup table of the four literal context modes.
     */
    private static final int[] CONTEXT_LOOKUP = contextTable();

    /**
     * The input.
     */
    private final byte[] data;

    /**
     * The end of the input.
     */
    private final int end;

    /**
     * The largest output accepted.
     */
    private final long maxOutput;

    /**
     * The index of the next input byte to load into {@link #buffer}.
     */
    private int position;

    /**
     * The loaded input bits, next bit lowest.
     */
    private long buffer;

    /**
     * The number of valid bits in {@link #buffer}.
     */
    private int count;

    /**
     * The output.
     */
    private byte[] output;

    /**
     * The number of output bytes.
     */
    private int size;

    /**
     * The last four distances, a ring indexed by {@link #distanceIndex}.
     */
    private final int[] distances = {16, 15, 11, 4};

    /**
     * The index of the last distance in {@link #distances}.
     */
    private int distanceIndex = 3;

    /**
     * Creates a decoder.
     *
     * @param data the input
     * @param offset the start of the stream
     * @param length the length of the stream
     * @param maxOutput the largest output accepted
     */
    private BrotliDecoder(byte[] data, int offset, int length, long maxOutput) {
        this.data = data;
        this.position = offset;
        this.end = offset + length;
        this.maxOutput = maxOutput;
        this.output = new byte[(int) Math.min(Math.max(1024, (long) length * 4), Math.min(maxOutput, 1 << 24))];
    }

    /**
     * Decompresses a Brotli stream.
     *
     * @param data the input
     * @param offset the start of the stream
     * @param length the length of the stream
     * @param maxOutput the largest output accepted, in bytes
     * @return the decompressed bytes
     * @throws MediaException.Malformed if the stream is invalid or truncated
     * @throws MediaException.LimitExceeded if the output exceeds {@code maxOutput}
     */
    public static byte[] decompress(byte[] data, int offset, int length, long maxOutput) {
        Objects.checkFromIndexSize(offset, length, data.length);
        var decoder = new BrotliDecoder(data, offset, length, Math.min(maxOutput, Integer.MAX_VALUE - 64));
        decoder.decode();
        return Arrays.copyOf(decoder.output, decoder.size);
    }

    /**
     * Decodes the stream header and every meta-block.
     */
    private void decode() {
        var windowBits = windowBits();
        var maxBackwardDistance = (1 << windowBits) - 16;
        while (true) {
            var isLast = bits(1) != 0;
            if (isLast && bits(1) != 0) {
                break;
            }
            var nibbles = bits(2) + 4;
            if (nibbles == 7) {
                if (bits(1) != 0) {
                    throw malformed("reserved bit set");
                }
                var sizeBytes = bits(2);
                var length = 0;
                for (var index = 0; index < sizeBytes; index++) {
                    var value = bits(8);
                    if (value == 0 && index + 1 == sizeBytes && sizeBytes > 1) {
                        throw malformed("exuberant meta-block length");
                    }
                    length |= value << (index * 8);
                }
                if (sizeBytes > 0) {
                    length++;
                }
                alignToByte();
                skipBytes(length);
            } else {
                var length = 0;
                for (var index = 0; index < nibbles; index++) {
                    var value = bits(4);
                    if (value == 0 && index + 1 == nibbles && nibbles > 4) {
                        throw malformed("exuberant meta-block length");
                    }
                    length |= value << (index * 4);
                }
                length++;
                var uncompressed = !isLast && bits(1) != 0;
                ensureCapacity(length);
                if (uncompressed) {
                    alignToByte();
                    copyBytes(length);
                } else {
                    compressedMetaBlock(length, maxBackwardDistance);
                }
            }
            if (isLast) {
                break;
            }
        }
        alignToByte();
        if (consumed() > (long) end * 8) {
            throw malformed("truncated stream");
        }
    }

    /**
     * Reads the window size.
     *
     * @return the base-2 logarithm of the window size
     */
    private int windowBits() {
        if (bits(1) == 0) {
            return 16;
        }
        var n = bits(3);
        if (n != 0) {
            return 17 + n;
        }
        n = bits(3);
        if (n == 1) {
            throw malformed("large-window streams are not supported");
        }
        return n != 0 ? 8 + n : 17;
    }

    /**
     * Decodes one compressed meta-block.
     *
     * @param length the number of bytes it produces
     * @param maxBackwardDistance the largest distance within the window
     */
    private void compressedMetaBlock(int length, int maxBackwardDistance) {
        var types = new int[3];
        var typeTrees = new int[3][];
        var lengthTrees = new int[3][];
        var blockLengths = new int[3];
        var rings = new int[]{1, 0, 1, 0, 1, 0};
        for (var kind = 0; kind < 3; kind++) {
            types[kind] = varLenUint8() + 1;
            if (types[kind] > 1) {
                typeTrees[kind] = huffmanCode(types[kind] + 2, types[kind] + 2);
                lengthTrees[kind] = huffmanCode(NUM_BLOCK_LENGTH_CODES, NUM_BLOCK_LENGTH_CODES);
                blockLengths[kind] = blockLength(lengthTrees[kind]);
            } else {
                blockLengths[kind] = 1 << 28;
            }
        }
        var postfixBits = bits(2);
        var directCodes = bits(4) << postfixBits;
        var contextModes = new int[types[0]];
        for (var index = 0; index < types[0]; index++) {
            contextModes[index] = bits(2);
        }
        var contextMap = new byte[types[0] << 6];
        var literalTreeCount = contextMap(contextMap);
        var distanceContextMap = new byte[types[2] << 2];
        var distanceTreeCount = contextMap(distanceContextMap);
        var literalTrees = new int[literalTreeCount][];
        for (var index = 0; index < literalTreeCount; index++) {
            literalTrees[index] = huffmanCode(NUM_LITERAL_CODES, NUM_LITERAL_CODES);
        }
        var commandTrees = new int[types[1]][];
        for (var index = 0; index < types[1]; index++) {
            commandTrees[index] = huffmanCode(NUM_COMMAND_CODES, NUM_COMMAND_CODES);
        }
        var distanceAlphabet = NUM_DISTANCE_SHORT_CODES + directCodes + (48 << postfixBits);
        var distanceTrees = new int[distanceTreeCount][];
        for (var index = 0; index < distanceTreeCount; index++) {
            distanceTrees[index] = huffmanCode(distanceAlphabet, distanceAlphabet);
        }
        var distanceExtraBits = new int[distanceAlphabet];
        var distanceOffsets = new int[distanceAlphabet];
        distanceTable(distanceExtraBits, distanceOffsets, postfixBits, directCodes);

        var literalType = 0;
        var commandType = 0;
        var distanceType = 0;
        var remaining = length;
        while (remaining > 0) {
            if (position > end + 8) {
                throw malformed("truncated stream");
            }
            if (blockLengths[1] == 0) {
                commandType = blockSwitch(typeTrees[1], lengthTrees[1], types[1], rings, 2, blockLengths, 1);
            }
            blockLengths[1]--;
            var command = symbol(commandTrees[commandType]) << 2;
            var extra = COMMANDS[command];
            var insertLength = COMMANDS[command + 1] + bits(extra & 0xFF);
            var copyLength = COMMANDS[command + 2] + bits(extra >>> 8);
            var distanceContext = COMMANDS[command + 3];
            if (insertLength > remaining) {
                throw malformed("insert beyond the meta-block");
            }
            for (var index = 0; index < insertLength; index++) {
                if (blockLengths[0] == 0) {
                    literalType = blockSwitch(typeTrees[0], lengthTrees[0], types[0], rings, 0, blockLengths, 0);
                }
                blockLengths[0]--;
                var mode = contextModes[literalType] << 9;
                var previous1 = size > 0 ? output[size - 1] & 0xFF : 0;
                var previous2 = size > 1 ? output[size - 2] & 0xFF : 0;
                var context = CONTEXT_LOOKUP[mode + previous1] | CONTEXT_LOOKUP[mode + 256 + previous2];
                var tree = contextMap[(literalType << 6) + context] & 0xFF;
                output[size++] = (byte) symbol(literalTrees[tree]);
            }
            remaining -= insertLength;
            if (remaining <= 0) {
                break;
            }
            int distance;
            var code = distanceContext;
            if (distanceContext < 0) {
                distance = distances[distanceIndex];
            } else {
                if (blockLengths[2] == 0) {
                    distanceType = blockSwitch(typeTrees[2], lengthTrees[2], types[2], rings, 4, blockLengths, 2);
                }
                blockLengths[2]--;
                var tree = distanceContextMap[(distanceType << 2) + distanceContext] & 0xFF;
                code = symbol(distanceTrees[tree]);
                if (code < NUM_DISTANCE_SHORT_CODES) {
                    distance = distances[(distanceIndex + DISTANCE_SHORT_CODE_INDEX_OFFSET[code]) & 3]
                            + DISTANCE_SHORT_CODE_VALUE_OFFSET[code];
                    if (distance <= 0) {
                        throw malformed("invalid distance");
                    }
                } else {
                    var offset = (long) distanceOffsets[code] + ((long) bits(distanceExtraBits[code]) << postfixBits);
                    if (offset > Integer.MAX_VALUE) {
                        throw malformed("invalid distance");
                    }
                    distance = (int) offset;
                }
            }
            var maxDistance = Math.min(size, maxBackwardDistance);
            if (distance > maxDistance) {
                var written = dictionaryWord(distance - maxDistance - 1, copyLength, remaining);
                remaining -= written;
                continue;
            }
            if (code > 0) {
                distanceIndex = (distanceIndex + 1) & 3;
                distances[distanceIndex] = distance;
            }
            if (copyLength > remaining) {
                throw malformed("copy beyond the meta-block");
            }
            var source = size - distance;
            for (var index = 0; index < copyLength; index++) {
                output[size++] = output[source + index];
            }
            remaining -= copyLength;
        }
    }

    /**
     * Writes a transformed word of the static dictionary.
     *
     * @param address the dictionary address: the word index and the transform index
     * @param length the word length
     * @param remaining the bytes left in the meta-block
     * @return the number of bytes written
     */
    private int dictionaryWord(int address, int length, int remaining) {
        var dictionary = BrotliDictionary.data();
        if (length < BrotliDictionary.MIN_WORD_LENGTH || length > BrotliDictionary.MAX_WORD_LENGTH) {
            throw malformed("invalid dictionary reference");
        }
        var shift = BrotliDictionary.SIZE_BITS[length];
        var word = address & ((1 << shift) - 1);
        var transform = address >>> shift;
        if (transform >= BrotliDictionary.TRANSFORM_COUNT) {
            throw malformed("invalid dictionary transform");
        }
        var offset = BrotliDictionary.OFFSETS[length] + word * length;
        var written = BrotliDictionary.transform(output, size, dictionary, offset, length, transform);
        if (written > remaining) {
            throw malformed("dictionary word beyond the meta-block");
        }
        size += written;
        return written;
    }

    /**
     * Fills the extra bits and the base of every distance symbol.
     *
     * @param extraBits the extra bits, filled
     * @param offsets the bases, filled
     * @param postfixBits the number of postfix bits
     * @param directCodes the number of direct distance codes
     */
    private static void distanceTable(int[] extraBits, int[] offsets, int postfixBits, int directCodes) {
        var index = NUM_DISTANCE_SHORT_CODES;
        for (var code = 0; code < directCodes; code++) {
            offsets[index++] = code + 1;
        }
        var bits = 1;
        var half = 0;
        var postfix = 1 << postfixBits;
        while (index < extraBits.length) {
            var base = directCodes + ((((2 + half) << bits) - 4) << postfixBits) + 1;
            for (var code = 0; code < postfix && index < extraBits.length; code++) {
                extraBits[index] = bits;
                offsets[index++] = base + code;
            }
            bits += half;
            half ^= 1;
        }
    }

    /**
     * Reads a block switch command and returns the new block type.
     *
     * @param typeTree the block type code
     * @param lengthTree the block length code
     * @param typeCount the number of block types
     * @param rings the last two block types of each category
     * @param ringOffset the index of this category's two entries in {@code rings}
     * @param blockLengths the remaining block lengths, updated for this category
     * @param kind the category: 0 literals, 1 commands, 2 distances
     * @return the new block type
     */
    private int blockSwitch(int[] typeTree, int[] lengthTree, int typeCount, int[] rings, int ringOffset,
                            int[] blockLengths, int kind) {
        var type = symbol(typeTree);
        blockLengths[kind] = blockLength(lengthTree);
        if (type == 1) {
            type = rings[ringOffset + 1] + 1;
        } else if (type == 0) {
            type = rings[ringOffset];
        } else {
            type -= 2;
        }
        if (type >= typeCount) {
            type -= typeCount;
        }
        rings[ringOffset] = rings[ringOffset + 1];
        rings[ringOffset + 1] = type;
        return type;
    }

    /**
     * Reads a block length.
     *
     * @param tree the block length code
     * @return the length
     */
    private int blockLength(int[] tree) {
        var code = symbol(tree);
        return BLOCK_LENGTH_OFFSET[code] + bits(BLOCK_LENGTH_N_BITS[code]);
    }

    /**
     * Reads a context map.
     *
     * @param map the map, filled
     * @return the number of trees it references
     */
    private int contextMap(byte[] map) {
        var trees = varLenUint8() + 1;
        if (trees == 1) {
            return 1;
        }
        var maxRunLengthPrefix = bits(1) != 0 ? bits(4) + 1 : 0;
        var alphabet = trees + maxRunLengthPrefix;
        var table = huffmanCode(alphabet, alphabet);
        var index = 0;
        while (index < map.length) {
            var code = symbol(table);
            if (code == 0) {
                map[index++] = 0;
            } else if (code <= maxRunLengthPrefix) {
                var repeat = (1 << code) + bits(code);
                if (index + repeat > map.length) {
                    throw malformed("invalid context map");
                }
                index += repeat;
            } else {
                map[index++] = (byte) (code - maxRunLengthPrefix);
            }
        }
        if (bits(1) != 0) {
            var mtf = new int[256];
            for (var value = 0; value < 256; value++) {
                mtf[value] = value;
            }
            for (var position = 0; position < map.length; position++) {
                var mtfIndex = map[position] & 0xFF;
                var value = mtf[mtfIndex];
                map[position] = (byte) value;
                System.arraycopy(mtf, 0, mtf, 1, mtfIndex);
                mtf[0] = value;
            }
        }
        return trees;
    }

    /**
     * Reads a prefix code.
     *
     * @param alphabetMax the alphabet size the symbol widths of a simple code derive from
     * @param alphabetLimit the number of valid symbols
     * @return the lookup table
     */
    private int[] huffmanCode(int alphabetMax, int alphabetLimit) {
        var lengths = new int[alphabetLimit];
        var kind = bits(2);
        if (kind == 1) {
            var maxBits = 1 + (31 - Integer.numberOfLeadingZeros(alphabetMax - 1));
            var count = bits(2) + 1;
            var symbols = new int[4];
            for (var index = 0; index < count; index++) {
                var symbol = bits(maxBits);
                if (symbol >= alphabetLimit) {
                    throw malformed("prefix code symbol out of range");
                }
                for (var previous = 0; previous < index; previous++) {
                    if (symbols[previous] == symbol) {
                        throw malformed("duplicate prefix code symbol");
                    }
                }
                symbols[index] = symbol;
            }
            var histogram = count == 4 ? 4 + bits(1) : count;
            switch (histogram) {
                case 1 -> lengths[symbols[0]] = 1;
                case 2 -> {
                    lengths[symbols[0]] = 1;
                    lengths[symbols[1]] = 1;
                }
                case 3 -> {
                    lengths[symbols[0]] = 1;
                    lengths[symbols[1]] = 2;
                    lengths[symbols[2]] = 2;
                }
                case 4 -> {
                    for (var index = 0; index < 4; index++) {
                        lengths[symbols[index]] = 2;
                    }
                }
                default -> {
                    lengths[symbols[0]] = 1;
                    lengths[symbols[1]] = 2;
                    lengths[symbols[2]] = 3;
                    lengths[symbols[3]] = 3;
                }
            }
            return buildTable(lengths);
        }
        var codeLengthLengths = new int[CODE_LENGTH_CODES];
        var space = 32;
        var codes = 0;
        for (var index = kind; index < CODE_LENGTH_CODES; index++) {
            var entry = FIXED_TABLE[peek(4) & 15];
            skip(entry >>> 16);
            var value = entry & 0xFFFF;
            codeLengthLengths[CODE_LENGTH_CODE_ORDER[index]] = value;
            if (value != 0) {
                space -= 32 >> value;
                codes++;
                if (space <= 0) {
                    break;
                }
            }
        }
        if (space != 0 && codes != 1) {
            throw malformed("invalid code length code");
        }
        var codeLengthTable = buildTable(codeLengthLengths, 5);
        var symbol = 0;
        var previousLength = 8;
        var repeat = 0;
        var repeatLength = 0;
        space = 32768;
        while (symbol < alphabetLimit && space > 0) {
            var entry = codeLengthTable[peek(5) & 31];
            skip(entry >>> 16);
            var codeLength = entry & 0xFFFF;
            if (codeLength < 16) {
                repeat = 0;
                lengths[symbol++] = codeLength;
                if (codeLength != 0) {
                    previousLength = codeLength;
                    space -= 32768 >> codeLength;
                }
            } else {
                var extraBits = codeLength - 14;
                var newLength = codeLength == 16 ? previousLength : 0;
                if (repeatLength != newLength) {
                    repeat = 0;
                    repeatLength = newLength;
                }
                var oldRepeat = repeat;
                if (repeat > 0) {
                    repeat = (repeat - 2) << extraBits;
                }
                repeat += bits(extraBits) + 3;
                var delta = repeat - oldRepeat;
                if (symbol + delta > alphabetLimit) {
                    throw malformed("invalid code lengths");
                }
                for (var index = 0; index < delta; index++) {
                    lengths[symbol++] = repeatLength;
                }
                if (repeatLength != 0) {
                    space -= delta << (15 - repeatLength);
                }
            }
        }
        if (space != 0) {
            throw malformed("incomplete prefix code");
        }
        return buildTable(lengths);
    }

    /**
     * Builds the lookup table of a prefix code with an 8-bit root table.
     *
     * @param lengths the code length of each symbol
     * @return the table
     */
    private static int[] buildTable(int[] lengths) {
        return buildTable(lengths, ROOT_BITS);
    }

    /**
     * Builds the lookup table of a complete prefix code.
     *
     * <p>Each entry of the root table, indexed by the next {@code rootBits} input bits, holds the
     * code length above the symbol, or, for longer codes, the width of a second-level table above
     * its offset from the entry.
     *
     * @param lengths the code length of each symbol
     * @param rootBits the width of the root table
     * @return the table
     */
    private static int[] buildTable(int[] lengths, int rootBits) {
        var counts = new int[MAX_LENGTH + 1];
        for (var length : lengths) {
            counts[length]++;
        }
        var offsets = new int[MAX_LENGTH + 1];
        for (var length = 1; length < MAX_LENGTH; length++) {
            offsets[length + 1] = offsets[length] + counts[length];
        }
        var sorted = new int[lengths.length];
        for (var symbol = 0; symbol < lengths.length; symbol++) {
            if (lengths[symbol] != 0) {
                sorted[offsets[lengths[symbol]]++] = symbol;
            }
        }
        var tableSize = 1 << rootBits;
        if (offsets[MAX_LENGTH] == 1) {
            var table = new int[tableSize];
            Arrays.fill(table, sorted[0]);
            return table;
        }
        var table = new int[maxTableSize(counts, rootBits)];
        var key = 0;
        var symbol = 0;
        var step = 1;
        for (var length = 1; length <= rootBits; length++) {
            step <<= 1;
            while (counts[length] > 0) {
                replicate(table, key, step, tableSize, length << 16 | sorted[symbol++]);
                key = nextKey(key, length);
                counts[length]--;
            }
        }
        var mask = tableSize - 1;
        var low = -1;
        var current = 0;
        var subSize = tableSize;
        step = 1;
        for (var length = rootBits + 1; length <= MAX_LENGTH; length++) {
            step <<= 1;
            while (counts[length] > 0) {
                if ((key & mask) != low) {
                    current += subSize;
                    var subBits = nextTableBits(counts, length, rootBits);
                    subSize = 1 << subBits;
                    low = key & mask;
                    table[low] = (subBits + rootBits) << 16 | (current - low);
                }
                replicate(table, current + (key >> rootBits), step, subSize, (length - rootBits) << 16 | sorted[symbol++]);
                key = nextKey(key, length);
                counts[length]--;
            }
        }
        return table;
    }

    /**
     * Returns an upper bound of the table size of a code.
     *
     * @param counts the number of codes of each length
     * @param rootBits the width of the root table
     * @return the bound
     */
    private static int maxTableSize(int[] counts, int rootBits) {
        var size = 1 << rootBits;
        for (var length = rootBits + 1; length <= MAX_LENGTH; length++) {
            size += counts[length] << (MAX_LENGTH - rootBits);
        }
        return Math.min(size, (1 << rootBits) + (1 << MAX_LENGTH));
    }

    /**
     * Returns the bit-reversed increment of a bit-reversed code.
     *
     * @param key the reversed code
     * @param length the code length
     * @return the next reversed code
     */
    private static int nextKey(int key, int length) {
        var step = 1 << (length - 1);
        while ((key & step) != 0) {
            step >>= 1;
        }
        return (key & (step - 1)) + step;
    }

    /**
     * Stores an entry at every {@code step}-th slot of a table range.
     *
     * @param table the lookup table to fill
     * @param offset the start of the range
     * @param step the stride
     * @param end the length of the range, a multiple of {@code step}
     * @param item the entry
     */
    private static void replicate(int[] table, int offset, int step, int end, int item) {
        for (var position = end - step; position >= 0; position -= step) {
            table[offset + position] = item;
        }
    }

    /**
     * Returns the width of the next second-level table.
     *
     * @param counts the number of codes of each length still to place
     * @param length the length of the next code
     * @param rootBits the width of the root table
     * @return the width
     */
    private static int nextTableBits(int[] counts, int length, int rootBits) {
        var bits = length;
        var left = 1 << (bits - rootBits);
        while (bits < MAX_LENGTH) {
            left -= counts[bits];
            if (left <= 0) {
                break;
            }
            bits++;
            left <<= 1;
        }
        return bits - rootBits;
    }

    /**
     * Decodes one symbol.
     *
     * @param table the lookup table
     * @return the symbol
     */
    private int symbol(int[] table) {
        var bits = peek(MAX_LENGTH);
        var entry = table[bits & 0xFF];
        var length = entry >>> 16;
        if (length <= ROOT_BITS) {
            skip(length);
            return entry & 0xFFFF;
        }
        var index = (bits & 0xFF) + (entry & 0xFFFF) + ((bits & ((1 << length) - 1)) >>> ROOT_BITS);
        var second = table[index];
        skip((second >>> 16) + ROOT_BITS);
        return second & 0xFFFF;
    }

    /**
     * Reads a number from 0 to 255 coded in 1 to 11 bits.
     *
     * @return the number
     */
    private int varLenUint8() {
        if (bits(1) == 0) {
            return 0;
        }
        var n = bits(3);
        return n == 0 ? 1 : bits(n) + (1 << n);
    }

    /**
     * Grows the output to hold more bytes.
     *
     * @param more the number of bytes about to be written, plus a dictionary word's slack
     * @throws MediaException.LimitExceeded if the output would exceed the bound
     */
    private void ensureCapacity(int more) {
        var needed = (long) size + more + 64;
        if ((long) size + more > maxOutput) {
            throw new MediaException.LimitExceeded("Brotli output larger than " + maxOutput + " bytes");
        }
        if (needed > output.length) {
            var grown = Math.max(needed, Math.min((long) output.length * 2, maxOutput + 64));
            output = Arrays.copyOf(output, (int) grown);
        }
    }

    /**
     * Skips to the next byte boundary, whose padding bits must be zero.
     */
    private void alignToByte() {
        var padding = count & 7;
        if (padding != 0 && bits(padding) != 0) {
            throw malformed("non-zero padding bits");
        }
    }

    /**
     * Copies raw bytes from the input, at a byte boundary, to the output.
     *
     * @param length the number of bytes
     */
    private void copyBytes(int length) {
        var left = length;
        while (count >= 8 && left > 0) {
            output[size++] = (byte) bits(8);
            left--;
        }
        if (left > 0) {
            if (count != 0 || position + left > end) {
                throw malformed("truncated uncompressed meta-block");
            }
            System.arraycopy(data, position, output, size, left);
            position += left;
            size += left;
        }
    }

    /**
     * Skips raw bytes of the input, at a byte boundary.
     *
     * @param length the number of bytes
     */
    private void skipBytes(int length) {
        var left = length;
        while (count >= 8 && left > 0) {
            bits(8);
            left--;
        }
        if (left > 0) {
            if (count != 0 || position + left > end) {
                throw malformed("truncated metadata meta-block");
            }
            position += left;
        }
    }

    /**
     * Returns the number of input bits consumed, counted from the array start.
     *
     * @return the bit position
     */
    private long consumed() {
        return (long) position * 8 - count;
    }

    /**
     * Loads input bytes until the buffer holds at least 57 bits; past the end of the input zero
     * bytes are loaded, which {@link #decode()} detects.
     */
    private void fill() {
        while (count <= 56) {
            var value = position < end ? data[position] & 0xFF : 0;
            buffer |= (long) value << count;
            position++;
            count += 8;
        }
    }

    /**
     * Returns the next bits without consuming them.
     *
     * @param n the number of bits, at most 32
     * @return the bits
     */
    private int peek(int n) {
        if (count < n) {
            fill();
        }
        return (int) buffer;
    }

    /**
     * Consumes bits.
     *
     * @param n the number of bits
     */
    private void skip(int n) {
        buffer >>>= n;
        count -= n;
    }

    /**
     * Reads bits, least significant first.
     *
     * @param n the number of bits, at most 24
     * @return the value
     */
    private int bits(int n) {
        if (n == 0) {
            return 0;
        }
        if (count < n) {
            fill();
            if (position > end + 16) {
                throw malformed("truncated stream");
            }
        }
        var value = (int) (buffer & ((1L << n) - 1));
        skip(n);
        return value;
    }

    /**
     * Builds the failure of a malformed stream.
     *
     * @param reason what is wrong with the stream, for the message
     * @return the exception
     */
    private static MediaException.Malformed malformed(String reason) {
        return new MediaException.Malformed("invalid Brotli stream: " + reason);
    }

    /**
     * Builds the command lookup table.
     *
     * @return the table
     */
    private static int[] commandTable() {
        var insertOffsets = new int[24];
        var copyOffsets = new int[24];
        copyOffsets[0] = 2;
        for (var index = 0; index < 23; index++) {
            insertOffsets[index + 1] = insertOffsets[index] + (1 << INSERT_LENGTH_N_BITS[index]);
            copyOffsets[index + 1] = copyOffsets[index] + (1 << COPY_LENGTH_N_BITS[index]);
        }
        var table = new int[NUM_COMMAND_CODES * 4];
        for (var command = 0; command < NUM_COMMAND_CODES; command++) {
            var range = command >> 6;
            var distanceOffset = -4;
            if (range >= 2) {
                range -= 2;
                distanceOffset = 0;
            }
            var insertCode = (((0x29850 >> (range * 2)) & 3) << 3) | ((command >> 3) & 7);
            var copyCode = (((0x26244 >> (range * 2)) & 3) << 3) | (command & 7);
            var copyOffset = copyOffsets[copyCode];
            table[command * 4] = INSERT_LENGTH_N_BITS[insertCode] | (COPY_LENGTH_N_BITS[copyCode] << 8);
            table[command * 4 + 1] = insertOffsets[insertCode];
            table[command * 4 + 2] = copyOffset;
            table[command * 4 + 3] = distanceOffset + Math.min(copyOffset, 5) - 2;
        }
        return table;
    }

    /**
     * Builds the literal context lookup table: two 256-entry halves per context mode (LSB6, MSB6,
     * UTF8, signed), indexed by the last and the second-to-last byte.
     *
     * @return the table
     */
    private static int[] contextTable() {
        var lookup = new int[2048];
        var utfMap = "         !!  !                  \"#$##%#$&'##(#)#+++++++++"
                + "+((&*'##,---,---,-----,-----,-----&#'###.///.///./////./////./////&#'# ";
        var utfRle = "A/*  ':  & : $  \u0081 @";
        for (var index = 0; index < 256; index++) {
            lookup[index] = index & 0x3F;
            lookup[512 + index] = index >> 2;
            lookup[1792 + index] = 2 + (index >> 6);
        }
        for (var index = 0; index < 128; index++) {
            lookup[1024 + index] = 4 * (utfMap.charAt(index) - 32);
        }
        for (var index = 0; index < 64; index++) {
            lookup[1152 + index] = index & 1;
            lookup[1216 + index] = 2 + (index & 1);
        }
        var offset = 1280;
        for (var k = 0; k < 19; k++) {
            var value = k & 3;
            var repeat = utfRle.charAt(k) - 32;
            for (var index = 0; index < repeat; index++) {
                lookup[offset++] = value;
            }
        }
        for (var index = 0; index < 16; index++) {
            lookup[1792 + index] = 1;
            lookup[2032 + index] = 6;
        }
        lookup[1792] = 0;
        lookup[2047] = 7;
        for (var index = 0; index < 256; index++) {
            lookup[1536 + index] = lookup[1792 + index] << 3;
        }
        return lookup;
    }
}
