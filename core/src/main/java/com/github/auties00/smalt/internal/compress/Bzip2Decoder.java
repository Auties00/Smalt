/* Ports bzip2 1.0.8 (decompress.c, huffman.c, bzlib.c, randtable.c, crctable.c). Copyright (C) 1996-2019 Julian R
 * Seward; bzip2 license (BSD-style). Modified for Smalt. */
package com.github.auties00.smalt.internal.compress;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.internal.limits.LimitChecks;

import java.util.Arrays;

/**
 * Decompresses bzip2 streams held in memory.
 *
 * <p>A stream is the {@code BZh} header, its blocks (each a Burrows-Wheeler transformed, move-to-front and
 * Huffman coded run of up to 900000 bytes, checked by its CRC) and the end marker with the combined CRC.
 * Concatenated streams decompress to the concatenation of their contents; bytes after the last stream that do not
 * start another one are ignored, as {@code bzip2} does. Blocks in the randomized form of bzip2 0.9.0 are
 * de-randomized.
 */
public final class Bzip2Decoder {
    /**
     * The magic number opening a block.
     */
    private static final long BLOCK_MAGIC = 0x314159265359L;

    /**
     * The magic number opening the end of a stream.
     */
    private static final long END_MAGIC = 0x177245385090L;

    /**
     * The most Huffman tables of a block.
     */
    private static final int MAX_GROUPS = 6;

    /**
     * The number of symbols coded with one Huffman table before the next selector applies.
     */
    private static final int GROUP_SIZE = 50;

    /**
     * The most selectors a block uses; an encoder may write more, which are read and ignored.
     */
    private static final int MAX_SELECTORS = 18002;

    /**
     * The largest alphabet: 256 byte values, {@code RUNA}/{@code RUNB} in place of the first, and the end of block.
     */
    private static final int MAX_ALPHA_SIZE = 258;

    /**
     * The longest Huffman code.
     */
    private static final int MAX_CODE_LENGTH = 20;

    /**
     * The size of the per-length tables, indexed up to the longest code length plus two.
     */
    private static final int CODE_TABLE_SIZE = 23;

    /**
     * The symbol adding one times the current power of two to a zero run.
     */
    private static final int RUNA = 0;

    /**
     * The symbol adding two times the current power of two to a zero run.
     */
    private static final int RUNB = 1;

    /**
     * The table of the randomized block form, {@code BZ2_rNums}.
     */
    private static final int[] RANDOM_NUMBERS = {
            619, 720, 127, 481, 931, 816, 813, 233, 566, 247, 985, 724, 205, 454, 863, 491,
            741, 242, 949, 214, 733, 859, 335, 708, 621, 574, 73, 654, 730, 472, 419, 436,
            278, 496, 867, 210, 399, 680, 480, 51, 878, 465, 811, 169, 869, 675, 611, 697,
            867, 561, 862, 687, 507, 283, 482, 129, 807, 591, 733, 623, 150, 238, 59, 379,
            684, 877, 625, 169, 643, 105, 170, 607, 520, 932, 727, 476, 693, 425, 174, 647,
            73, 122, 335, 530, 442, 853, 695, 249, 445, 515, 909, 545, 703, 919, 874, 474,
            882, 500, 594, 612, 641, 801, 220, 162, 819, 984, 589, 513, 495, 799, 161, 604,
            958, 533, 221, 400, 386, 867, 600, 782, 382, 596, 414, 171, 516, 375, 682, 485,
            911, 276, 98, 553, 163, 354, 666, 933, 424, 341, 533, 870, 227, 730, 475, 186,
            263, 647, 537, 686, 600, 224, 469, 68, 770, 919, 190, 373, 294, 822, 808, 206,
            184, 943, 795, 384, 383, 461, 404, 758, 839, 887, 715, 67, 618, 276, 204, 918,
            873, 777, 604, 560, 951, 160, 578, 722, 79, 804, 96, 409, 713, 940, 652, 934,
            970, 447, 318, 353, 859, 672, 112, 785, 645, 863, 803, 350, 139, 93, 354, 99,
            820, 908, 609, 772, 154, 274, 580, 184, 79, 626, 630, 742, 653, 282, 762, 623,
            680, 81, 927, 626, 789, 125, 411, 521, 938, 300, 821, 78, 343, 175, 128, 250,
            170, 774, 972, 275, 999, 639, 495, 78, 352, 126, 857, 956, 358, 619, 580, 124,
            737, 594, 701, 612, 669, 112, 134, 694, 363, 992, 809, 743, 168, 974, 944, 375,
            748, 52, 600, 747, 642, 182, 862, 81, 344, 805, 988, 739, 511, 655, 814, 334,
            249, 515, 897, 955, 664, 981, 649, 113, 974, 459, 893, 228, 433, 837, 553, 268,
            926, 240, 102, 654, 459, 51, 686, 754, 806, 760, 493, 403, 415, 394, 687, 700,
            946, 670, 656, 610, 738, 392, 760, 799, 887, 653, 978, 321, 576, 617, 626, 502,
            894, 679, 243, 440, 680, 879, 194, 572, 640, 724, 926, 56, 204, 700, 707, 151,
            457, 449, 797, 195, 791, 558, 945, 679, 297, 59, 87, 824, 713, 663, 412, 693,
            342, 606, 134, 108, 571, 364, 631, 212, 174, 643, 304, 329, 343, 97, 430, 751,
            497, 314, 983, 374, 822, 928, 140, 206, 73, 263, 980, 736, 876, 478, 430, 305,
            170, 514, 364, 692, 829, 82, 855, 953, 676, 246, 369, 970, 294, 750, 807, 827,
            150, 790, 288, 923, 804, 378, 215, 828, 592, 281, 565, 555, 710, 82, 896, 831,
            547, 261, 524, 462, 293, 465, 502, 56, 661, 821, 976, 991, 658, 869, 905, 758,
            745, 193, 768, 550, 608, 933, 378, 286, 215, 979, 792, 961, 61, 688, 793, 644,
            986, 403, 106, 366, 905, 644, 372, 567, 466, 434, 645, 210, 389, 550, 919, 135,
            780, 773, 635, 389, 707, 100, 626, 958, 165, 504, 920, 176, 193, 713, 857, 265,
            203, 50, 668, 108, 645, 990, 626, 197, 510, 357, 358, 850, 858, 364, 936, 638
    };

    /**
     * The CRC-32 table of bzip2 (polynomial {@code 0x04C11DB7}, most significant bit first).
     */
    private static final int[] CRC_TABLE = crcTable();

    private final byte[] input;
    private final int end;
    private final MediaLimits limits;
    private int position;
    private long bitBuffer;
    private int bitCount;
    private byte[] output;
    private int outputSize;
    private int[] tt = new int[0];
    private final byte[] selectors = new byte[MAX_SELECTORS];
    private final int[][] limit = new int[MAX_GROUPS][CODE_TABLE_SIZE];
    private final int[][] base = new int[MAX_GROUPS][CODE_TABLE_SIZE];
    private final int[][] perm = new int[MAX_GROUPS][MAX_ALPHA_SIZE];
    private final int[] minLengths = new int[MAX_GROUPS];

    private Bzip2Decoder(byte[] input, int offset, int length, MediaLimits limits) {
        this.input = input;
        this.position = offset;
        this.end = offset + length;
        this.limits = limits;
        this.output = new byte[(int) Math.max(64, Math.min(length * 4L, 1 << 20))];
    }

    /**
     * Decompresses one or more concatenated bzip2 streams.
     *
     * @param data the compressed bytes
     * @param offset the index of the first compressed byte
     * @param length the number of compressed bytes
     * @param limits the bound of the decompressed size and of the block buffer
     * @return the decompressed bytes
     * @throws MediaException.Malformed if the data is not a bzip2 stream, is truncated or fails its CRC
     * @throws MediaException.LimitExceeded if the decompressed bytes exceed the allocation limit
     */
    public static byte[] decode(byte[] data, int offset, int length, MediaLimits limits) {
        if (offset < 0 || length < 0 || offset > data.length - length) {
            throw new IndexOutOfBoundsException("range [" + offset + ", " + offset + " + " + length + ") out of "
                    + data.length);
        }
        var decoder = new Bzip2Decoder(data, offset, length, limits);
        decoder.decodeStreams();
        return Arrays.copyOf(decoder.output, decoder.outputSize);
    }

    /**
     * Returns whether bytes start a bzip2 stream: {@code BZh} and a block size digit.
     *
     * @param data the bytes
     * @param offset the index of the first byte
     * @param length the number of bytes
     * @return whether a stream header starts there
     */
    public static boolean isStream(byte[] data, int offset, int length) {
        return length >= 4 && data[offset] == 'B' && data[offset + 1] == 'Z' && data[offset + 2] == 'h'
                && data[offset + 3] >= '1' && data[offset + 3] <= '9';
    }

    private void decodeStreams() {
        if (!isStream(input, position, end - position)) {
            throw new MediaException.Malformed("bzip2: no stream header");
        }
        while (isStream(input, position, end - position)) {
            var blockSize = (input[position + 3] - '0') * 100000;
            position += 4;
            bitBuffer = 0;
            bitCount = 0;
            var combined = 0;
            while (true) {
                var magic = bits(48);
                if (magic == BLOCK_MAGIC) {
                    var stored = (int) bits(32);
                    var computed = decodeBlock(blockSize);
                    if (computed != stored) {
                        throw new MediaException.Malformed("bzip2: block CRC mismatch");
                    }
                    combined = Integer.rotateLeft(combined, 1) ^ computed;
                } else if (magic == END_MAGIC) {
                    if ((int) bits(32) != combined) {
                        throw new MediaException.Malformed("bzip2: stream CRC mismatch");
                    }
                    break;
                } else {
                    throw new MediaException.Malformed("bzip2: bad block header");
                }
            }
        }
    }

    /**
     * Decodes one block into the output.
     *
     * @param maxBlock the largest block the stream header allows
     * @return the CRC of the decoded bytes
     */
    private int decodeBlock(int maxBlock) {
        var randomized = bits(1) != 0;
        var origin = (int) bits(24);
        if (origin > 10 + maxBlock) {
            throw new MediaException.Malformed("bzip2: block origin out of range");
        }
        var used = (int) bits(16);
        var symbolToByte = new byte[256];
        var inUse = 0;
        for (var i = 0; i < 16; i++) {
            if ((used & (0x8000 >>> i)) != 0) {
                var map = (int) bits(16);
                for (var j = 0; j < 16; j++) {
                    if ((map & (0x8000 >>> j)) != 0) {
                        symbolToByte[inUse++] = (byte) (i * 16 + j);
                    }
                }
            }
        }
        if (inUse == 0) {
            throw new MediaException.Malformed("bzip2: block uses no byte value");
        }
        var alphaSize = inUse + 2;
        var groups = (int) bits(3);
        if (groups < 2 || groups > MAX_GROUPS) {
            throw new MediaException.Malformed("bzip2: " + groups + " Huffman tables");
        }
        var selectorCount = (int) bits(15);
        if (selectorCount < 1) {
            throw new MediaException.Malformed("bzip2: block has no selector");
        }
        var order = new byte[MAX_GROUPS];
        for (var v = 0; v < groups; v++) {
            order[v] = (byte) v;
        }
        for (var i = 0; i < selectorCount; i++) {
            var j = 0;
            while (bits(1) != 0) {
                if (++j >= groups) {
                    throw new MediaException.Malformed("bzip2: selector out of range");
                }
            }
            if (i < MAX_SELECTORS) {
                var selected = order[j];
                System.arraycopy(order, 0, order, 1, j);
                order[0] = selected;
                selectors[i] = selected;
            }
        }
        selectorCount = Math.min(selectorCount, MAX_SELECTORS);
        var lengths = new int[alphaSize];
        for (var t = 0; t < groups; t++) {
            var current = (int) bits(5);
            var minLength = 32;
            var maxLength = 0;
            for (var i = 0; i < alphaSize; i++) {
                while (true) {
                    if (current < 1 || current > MAX_CODE_LENGTH) {
                        throw new MediaException.Malformed("bzip2: Huffman code length out of range");
                    }
                    if (bits(1) == 0) {
                        break;
                    }
                    current += bits(1) == 0 ? 1 : -1;
                }
                lengths[i] = current;
                minLength = Math.min(minLength, current);
                maxLength = Math.max(maxLength, current);
            }
            createDecodeTables(limit[t], base[t], perm[t], lengths, minLength, maxLength, alphaSize);
            minLengths[t] = minLength;
        }

        var endOfBlock = inUse + 1;
        var counts = new int[256];
        var mtf = new byte[256];
        for (var i = 0; i < 256; i++) {
            mtf[i] = (byte) i;
        }
        var blockLength = 0;
        var groupIndex = -1;
        var groupPosition = 0;
        var table = 0;
        var symbol = 0;
        var run = -1;
        var runWeight = 1;
        while (true) {
            if (groupPosition == 0) {
                if (++groupIndex >= selectorCount) {
                    throw new MediaException.Malformed("bzip2: block runs past its selectors");
                }
                groupPosition = GROUP_SIZE;
                table = selectors[groupIndex];
            }
            groupPosition--;
            var codeLength = minLengths[table];
            var code = (int) bits(codeLength);
            var tableLimit = limit[table];
            while (code > tableLimit[codeLength]) {
                if (++codeLength > MAX_CODE_LENGTH) {
                    throw new MediaException.Malformed("bzip2: invalid Huffman code");
                }
                code = (code << 1) | (int) bits(1);
            }
            var index = code - base[table][codeLength];
            if (index < 0 || index >= MAX_ALPHA_SIZE) {
                throw new MediaException.Malformed("bzip2: invalid Huffman code");
            }
            symbol = perm[table][index];
            if (symbol == RUNA || symbol == RUNB) {
                if (runWeight >= 2 * 1024 * 1024) {
                    throw new MediaException.Malformed("bzip2: run too long");
                }
                run += (symbol + 1) * runWeight;
                runWeight <<= 1;
                continue;
            }
            if (run >= 0) {
                var value = symbolToByte[mtf[0] & 0xFF] & 0xFF;
                var length = run + 1;
                if (length > maxBlock - blockLength) {
                    throw new MediaException.Malformed("bzip2: block longer than its stream allows");
                }
                ensureBlock(blockLength + length);
                Arrays.fill(tt, blockLength, blockLength + length, value);
                counts[value] += length;
                blockLength += length;
                run = -1;
                runWeight = 1;
            }
            if (symbol == endOfBlock) {
                break;
            }
            if (blockLength >= maxBlock) {
                throw new MediaException.Malformed("bzip2: block longer than its stream allows");
            }
            var position = symbol - 1;
            var front = mtf[position];
            System.arraycopy(mtf, 0, mtf, 1, position);
            mtf[0] = front;
            var value = symbolToByte[front & 0xFF] & 0xFF;
            ensureBlock(blockLength + 1);
            tt[blockLength++] = value;
            counts[value]++;
        }
        if (origin >= blockLength) {
            throw new MediaException.Malformed("bzip2: block origin out of range");
        }
        var starts = new int[256];
        var sum = 0;
        for (var i = 0; i < 256; i++) {
            starts[i] = sum;
            sum += counts[i];
        }
        for (var i = 0; i < blockLength; i++) {
            var value = tt[i] & 0xFF;
            tt[starts[value]++] |= i << 8;
        }
        return unpack(blockLength, tt[origin] >>> 8, randomized);
    }

    /**
     * Walks the inverse transform of a block, undoes its initial run-length coding and appends the bytes to the
     * output.
     *
     * @param blockLength the number of bytes of the block
     * @param start the first link of the inverse transform
     * @param randomized whether the block is in the randomized form
     * @return the CRC of the appended bytes
     */
    private int unpack(int blockLength, int start, boolean randomized) {
        var crc = -1;
        var link = start;
        var randomLeft = 0;
        var randomIndex = 0;
        var previous = -1;
        var repeats = 0;
        for (var i = 0; i < blockLength; i++) {
            link = tt[link];
            var value = link & 0xFF;
            link >>>= 8;
            if (randomized) {
                if (randomLeft == 0) {
                    randomLeft = RANDOM_NUMBERS[randomIndex];
                    randomIndex = (randomIndex + 1) & 511;
                }
                randomLeft--;
                if (randomLeft == 1) {
                    value ^= 1;
                }
            }
            if (repeats == 4) {
                ensureOutput(value);
                Arrays.fill(output, outputSize, outputSize + value, (byte) previous);
                for (var k = 0; k < value; k++) {
                    crc = (crc << 8) ^ CRC_TABLE[((crc >>> 24) ^ previous) & 0xFF];
                }
                outputSize += value;
                repeats = 0;
                previous = -1;
                continue;
            }
            repeats = value == previous ? repeats + 1 : 1;
            previous = value;
            ensureOutput(1);
            output[outputSize++] = (byte) value;
            crc = (crc << 8) ^ CRC_TABLE[((crc >>> 24) ^ value) & 0xFF];
        }
        if (repeats == 4) {
            throw new MediaException.Malformed("bzip2: block ends inside a run");
        }
        return ~crc;
    }

    /**
     * Builds the canonical Huffman decoding tables of one group, as {@code BZ2_hbCreateDecodeTables} does.
     */
    private static void createDecodeTables(int[] limit, int[] base, int[] perm, int[] lengths, int minLength,
                                           int maxLength, int alphaSize) {
        var count = 0;
        for (var i = minLength; i <= maxLength; i++) {
            for (var j = 0; j < alphaSize; j++) {
                if (lengths[j] == i) {
                    perm[count++] = j;
                }
            }
        }
        Arrays.fill(base, 0);
        for (var i = 0; i < alphaSize; i++) {
            base[lengths[i] + 1]++;
        }
        for (var i = 1; i < CODE_TABLE_SIZE; i++) {
            base[i] += base[i - 1];
        }
        Arrays.fill(limit, 0);
        var code = 0;
        for (var i = minLength; i <= maxLength; i++) {
            code += base[i + 1] - base[i];
            limit[i] = code - 1;
            code <<= 1;
        }
        for (var i = minLength + 1; i <= maxLength; i++) {
            base[i] = ((limit[i - 1] + 1) << 1) - base[i];
        }
    }

    /**
     * Reads up to 48 bits, most significant first.
     *
     * @param count the number of bits
     * @return the bits
     * @throws MediaException.Malformed if the input ends first
     */
    private long bits(int count) {
        while (bitCount < count) {
            if (position >= end) {
                throw new MediaException.Malformed("bzip2: stream is truncated");
            }
            bitBuffer = (bitBuffer << 8) | (input[position++] & 0xFF);
            bitCount += 8;
        }
        bitCount -= count;
        return (bitBuffer >>> bitCount) & ((1L << count) - 1);
    }

    /**
     * Grows the block buffer to hold a number of bytes.
     *
     * @param size the number of bytes
     */
    private void ensureBlock(int size) {
        if (size > tt.length) {
            var grown = (int) Math.max(size, Math.min(Math.max(4096L, tt.length * 2L), 900000));
            LimitChecks.checkAllocation(limits, grown * 4L);
            tt = Arrays.copyOf(tt, grown);
        }
    }

    /**
     * Grows the output to take more bytes, within the allocation limit.
     *
     * @param more the number of bytes to add
     * @throws MediaException.LimitExceeded if the output would exceed the allocation limit
     */
    private void ensureOutput(int more) {
        var needed = (long) outputSize + more;
        if (needed > output.length) {
            LimitChecks.checkAllocation(limits, needed);
            var cap = Math.min(limits.maxBytes(), Integer.MAX_VALUE - 16L);
            output = Arrays.copyOf(output, (int) Math.max(needed, Math.min(output.length * 2L, cap)));
        }
    }

    private static int[] crcTable() {
        var table = new int[256];
        for (var i = 0; i < 256; i++) {
            var crc = i << 24;
            for (var bit = 0; bit < 8; bit++) {
                crc = (crc & 0x80000000) != 0 ? (crc << 1) ^ 0x04C11DB7 : crc << 1;
            }
            table[i] = crc;
        }
        return table;
    }
}
