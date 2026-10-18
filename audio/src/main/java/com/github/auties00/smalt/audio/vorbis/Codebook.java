/* Ports libvorbis 1.3.7 (lib/sharedbook.c). Copyright Xiph.Org Foundation; BSD 3-Clause. Modified for Smalt. */
package com.github.auties00.smalt.audio.vorbis;

import com.github.auties00.smalt.MediaException;

import com.github.auties00.smalt.audio.vorbis.internal.Ilog;
import java.util.Arrays;

/**
 * Holds one Vorbis codebook: a Huffman code over the codebook entries and, optionally, the vector
 * of values each entry stands for when the codebook is read in a VQ context.
 *
 * <p>Entries are assigned codewords in entry order, each the numerically lowest free codeword of
 * its length with the leftmost bit most significant (Vorbis I section 3.2.1). Codebooks whose
 * lengths overspecify or underspecify the Huffman tree are rejected, except the single-entry
 * codebook of the 2015-02-26 errata: one used entry of length one, whose reads always return that
 * entry and consume one bit whatever its value. A codebook without any used entry is accepted; a
 * read from it reports end-of-packet.
 *
 * <p>Decoding is table driven. The first {@link #fastBits} upcoming bits index a table giving the
 * entry and codeword length of every codeword up to that length; longer codewords fall back to a
 * binary search over their left-justified values, restricted to the codewords that share the
 * table prefix.
 *
 * @implNote This implementation assigns codewords with the marker algorithm of libvorbis'
 * {@code _make_words} (the running "next free codeword" of every length, pruned as leaves are
 * taken), finds long codewords with the sorted left-justified search of stb_vorbis, and narrows
 * that search per table slot like libvorbis' {@code dec_firsttable} hints.
 */
final class Codebook {
    /**
     * The 24-bit synchronization pattern that starts every packed codebook.
     */
    private static final int SYNC = 0x564342;

    /**
     * The largest number of bits the direct lookup table resolves.
     */
    private static final int MAX_FAST_BITS = 10;

    /**
     * The number of scalars in each value vector.
     */
    final int dimensions;

    /**
     * The number of entries, used or not.
     */
    final int entries;

    /**
     * The value vector lookup type: 0 for none, 1 for lattice, 2 for explicit.
     */
    final int lookupType;

    /**
     * The value vectors, {@link #dimensions} consecutive scalars per entry indexed by entry
     * number, or {@code null} when {@link #lookupType} is zero.
     */
    final float[] values;

    /**
     * The number of entries with a codeword.
     */
    final int usedEntries;

    /**
     * The length of the longest codeword, the most bits one read consumes.
     */
    final int maxLength;

    /**
     * The width in bits of the direct lookup table index.
     */
    final int fastBits;

    /**
     * The direct lookup table: {@code (entry << 5) | length} for codewords of at most
     * {@link #fastBits} bits, indexed by the upcoming bits least-significant first, and
     * {@code -1} for bit patterns that start a longer codeword.
     */
    final int[] fast;

    /**
     * The left-justified codewords longer than {@link #fastBits} bits with their sign bit
     * flipped, in ascending unsigned order, so that a signed comparison orders them.
     */
    private final int[] longCodes;

    /**
     * The entry of each codeword in {@link #longCodes}.
     */
    private final int[] longEntries;

    /**
     * The length of each codeword in {@link #longCodes}.
     */
    private final byte[] longLengths;

    /**
     * For each direct table slot that starts a longer codeword, the first index into
     * {@link #longCodes} of the codewords sharing that prefix.
     */
    private final int[] longLow;

    /**
     * For each direct table slot that starts a longer codeword, one past the last index into
     * {@link #longCodes} of the codewords sharing that prefix.
     */
    private final int[] longHigh;

    /**
     * Constructs a codebook from its parsed fields.
     *
     * @param dimensions the number of scalars per value vector
     * @param entries the number of entries
     * @param lookupType the value lookup type
     * @param values the value vectors, or {@code null}
     * @param usedEntries the number of used entries
     * @param maxLength the longest codeword length
     * @param fastBits the direct table width
     * @param fast the direct table
     * @param longCodes the sorted flipped left-justified long codewords
     * @param longEntries the entries of the long codewords
     * @param longLengths the lengths of the long codewords
     * @param longLow the first long codeword of each prefix slot
     * @param longHigh one past the last long codeword of each prefix slot
     */
    private Codebook(int dimensions, int entries, int lookupType, float[] values, int usedEntries, int maxLength,
                     int fastBits, int[] fast, int[] longCodes, int[] longEntries, byte[] longLengths, int[] longLow,
                     int[] longHigh) {
        this.dimensions = dimensions;
        this.entries = entries;
        this.lookupType = lookupType;
        this.values = values;
        this.usedEntries = usedEntries;
        this.maxLength = maxLength;
        this.fastBits = fastBits;
        this.fast = fast;
        this.longCodes = longCodes;
        this.longEntries = longEntries;
        this.longLengths = longLengths;
        this.longLow = longLow;
        this.longHigh = longHigh;
    }

    /**
     * Reads one packed codebook from the setup header (Vorbis I section 3.2.1).
     *
     * @param reader the setup header reader, positioned at the sync pattern
     * @param budget the allocation budget of the stream
     * @return the codebook
     * @throws MediaException.Malformed if the codebook is corrupt, truncated or its Huffman
     * tree is over- or underspecified
     * @throws MediaException.Unsupported if the lookup type is reserved
     * @throws MediaException.LimitExceeded if its tables exceed the allocation budget
     */
    static Codebook read(BitReader reader, AllocationBudget budget) {
        if (reader.read(24) != SYNC) {
            throw new MediaException.Malformed("invalid Vorbis codebook sync pattern");
        }
        var dimensions = reader.read(16);
        var entries = reader.read(24);
        VorbisSetup.checkNotTruncated(reader);
        budget.charge(entries, 1);
        var lengths = new byte[entries];
        var ordered = reader.readBit() == 1;
        if (!ordered) {
            var sparse = reader.readBit() == 1;
            for (var entry = 0; entry < entries; entry++) {
                if (!sparse || reader.readBit() == 1) {
                    lengths[entry] = (byte) (reader.read(5) + 1);
                }
                if (reader.overrun()) {
                    throw new MediaException.Malformed("truncated Vorbis codebook lengths");
                }
            }
        } else {
            var length = reader.read(5) + 1;
            var entry = 0;
            while (entry < entries) {
                if (length > 32) {
                    throw new MediaException.Malformed("Vorbis codebook codeword longer than 32 bits");
                }
                var number = reader.read(Ilog.ilog(entries - entry));
                if (reader.overrun()) {
                    throw new MediaException.Malformed("truncated Vorbis codebook lengths");
                }
                if (number > entries - entry) {
                    throw new MediaException.Malformed("Vorbis codebook length runs exceed the entry count");
                }
                Arrays.fill(lengths, entry, entry + number, (byte) length);
                entry += number;
                length++;
            }
        }
        var lookupType = reader.read(4);
        float[] values = null;
        if (lookupType == 1 || lookupType == 2) {
            values = readValues(reader, budget, lookupType, dimensions, entries, lengths);
        } else if (lookupType != 0) {
            throw new MediaException.Unsupported("vorbis codebook lookup type " + lookupType,
                    "Vorbis codebook lookup type " + lookupType + " is reserved");
        }
        VorbisSetup.checkNotTruncated(reader);
        return build(dimensions, entries, lookupType, values, lengths, budget);
    }

    /**
     * Reads the multiplicands of a lookup type 1 or 2 codebook and expands them into value
     * vectors (Vorbis I section 3.2.1, "VQ lookup table vector representation").
     *
     * @param reader the setup header reader, positioned after the lookup type
     * @param budget the allocation budget of the stream
     * @param lookupType the lookup type, 1 or 2
     * @param dimensions the number of scalars per vector
     * @param entries the number of entries
     * @param lengths the codeword length of each entry, zero for unused entries
     * @return the value vectors indexed by entry number
     * @throws MediaException.Malformed if the lookup is truncated or undefined
     * @throws MediaException.LimitExceeded if the tables exceed the allocation budget
     */
    private static float[] readValues(BitReader reader, AllocationBudget budget, int lookupType, int dimensions,
                                      int entries, byte[] lengths) {
        var minimum = float32Unpack(reader.read(32));
        var delta = float32Unpack(reader.read(32));
        var valueBits = reader.read(4) + 1;
        var sequence = reader.readBit() == 1;
        long lookupValues;
        if (lookupType == 1) {
            if (dimensions == 0) {
                throw new MediaException.Malformed("Vorbis lattice codebook with zero dimensions");
            }
            lookupValues = lookup1Values(entries, dimensions);
        } else {
            lookupValues = (long) entries * dimensions;
        }
        if (lookupValues * valueBits > reader.bitsLeft()) {
            throw new MediaException.Malformed("truncated Vorbis codebook multiplicands");
        }
        budget.charge(lookupValues, 4);
        var multiplicands = new int[(int) lookupValues];
        for (var i = 0; i < multiplicands.length; i++) {
            multiplicands[i] = reader.read(valueBits);
        }
        budget.charge((long) entries * dimensions, 4);
        var values = new float[entries * dimensions];
        for (var entry = 0; entry < entries; entry++) {
            if (lengths[entry] == 0) {
                continue;
            }
            var last = 0.0f;
            var base = entry * dimensions;
            if (lookupType == 1) {
                long divisor = 1;
                for (var i = 0; i < dimensions; i++) {
                    var offset = (int) ((entry / divisor) % lookupValues);
                    var value = multiplicands[offset] * delta + minimum + last;
                    if (sequence) {
                        last = value;
                    }
                    values[base + i] = value;
                    // Once the divisor exceeds the entry count every further quotient is zero.
                    divisor = divisor > entries ? divisor : divisor * lookupValues;
                }
            } else {
                for (var i = 0; i < dimensions; i++) {
                    var value = multiplicands[base + i] * delta + minimum + last;
                    if (sequence) {
                        last = value;
                    }
                    values[base + i] = value;
                }
            }
        }
        return values;
    }

    /**
     * Assigns codewords and builds the decoding tables.
     *
     * @param dimensions the number of scalars per vector
     * @param entries the number of entries
     * @param lookupType the lookup type
     * @param values the value vectors, or {@code null}
     * @param lengths the codeword length of each entry, zero for unused entries
     * @param budget the allocation budget of the stream
     * @return the codebook
     * @throws MediaException.Malformed if the Huffman tree is over- or underspecified
     * @throws MediaException.LimitExceeded if the tables exceed the allocation budget
     */
    static Codebook build(int dimensions, int entries, int lookupType, float[] values, byte[] lengths,
                          AllocationBudget budget) {
        var used = 0;
        var maxLength = 0;
        var firstUsed = -1;
        for (var entry = 0; entry < entries; entry++) {
            var length = lengths[entry];
            if (length != 0) {
                if (firstUsed < 0) {
                    firstUsed = entry;
                }
                used++;
                maxLength = Math.max(maxLength, length);
            }
        }
        if (used == 0) {
            return new Codebook(dimensions, entries, lookupType, values, 0, 0, 0, new int[]{-1}, new int[0], new int[0],
                    new byte[0], new int[1], new int[1]);
        }
        if (used == 1) {
            // Errata 20150226: a single-entry codebook must declare length one and always sinks one bit.
            if (lengths[firstUsed] != 1) {
                throw new MediaException.Malformed("Vorbis single-entry codebook with codeword length " + lengths[firstUsed]);
            }
            var code = firstUsed << 5 | 1;
            return new Codebook(dimensions, entries, lookupType, values, 1, 1, 1, new int[]{code, code}, new int[0],
                    new int[0], new byte[0], new int[2], new int[2]);
        }
        budget.charge(entries, 4);
        var codes = new int[entries];
        var marker = new long[33];
        for (var entry = 0; entry < entries; entry++) {
            int length = lengths[entry];
            if (length == 0) {
                continue;
            }
            var code = marker[length];
            if ((code >>> length) != 0) {
                throw new MediaException.Malformed("overspecified Vorbis codebook Huffman tree");
            }
            codes[entry] = (int) code;
            for (var j = length; j > 0; j--) {
                if ((marker[j] & 1) != 0) {
                    marker[j] = j == 1 ? marker[1] + 1 : marker[j - 1] << 1;
                    break;
                }
                marker[j]++;
            }
            for (var j = length + 1; j < 33; j++) {
                if ((marker[j] >>> 1) != code) {
                    break;
                }
                code = marker[j];
                marker[j] = marker[j - 1] << 1;
            }
        }
        for (var j = 1; j < 33; j++) {
            if ((marker[j] & (0xFFFFFFFFL >>> (32 - j))) != 0) {
                throw new MediaException.Malformed("underspecified Vorbis codebook Huffman tree");
            }
        }
        var fastBits = Math.min(maxLength, MAX_FAST_BITS);
        var fast = new int[1 << fastBits];
        Arrays.fill(fast, -1);
        var longCount = 0;
        for (var entry = 0; entry < entries; entry++) {
            int length = lengths[entry];
            if (length == 0) {
                continue;
            }
            if (length > fastBits) {
                longCount++;
                continue;
            }
            var reversed = Integer.reverse(codes[entry]) >>> (32 - length);
            var value = entry << 5 | length;
            for (var fill = reversed; fill < fast.length; fill += 1 << length) {
                fast[fill] = value;
            }
        }
        budget.charge(longCount, 9);
        var longKeys = new long[longCount];
        var index = 0;
        for (var entry = 0; entry < entries; entry++) {
            int length = lengths[entry];
            if (length > fastBits) {
                var justified = ((long) codes[entry] << (32 - length)) & 0xFFFFFFFFL;
                longKeys[index++] = (justified ^ 0x80000000L) << 32 | entry;
            }
        }
        Arrays.sort(longKeys);
        var longCodes = new int[longCount];
        var longEntries = new int[longCount];
        var longLengths = new byte[longCount];
        for (var i = 0; i < longCount; i++) {
            var entry = (int) longKeys[i];
            longCodes[i] = (int) (longKeys[i] >>> 32);
            longEntries[i] = entry;
            longLengths[i] = lengths[entry];
        }
        var longLow = new int[fast.length];
        var longHigh = new int[fast.length];
        for (var slot = 0; slot < fast.length; slot++) {
            if (fast[slot] >= 0) {
                continue;
            }
            // The slot's bits, read first-bit-first, are the codeword prefix; its long codewords are contiguous.
            var prefix = (long) (Integer.reverse(slot) >>> (32 - fastBits)) << (32 - fastBits);
            var low = (int) (prefix ^ 0x80000000L);
            var high = (int) ((prefix + (1L << (32 - fastBits)) - 1) ^ 0x80000000L);
            longLow[slot] = firstAbove(longCodes, low - 1L);
            longHigh[slot] = firstAbove(longCodes, high);
        }
        return new Codebook(dimensions, entries, lookupType, values, used, maxLength, fastBits, fast, longCodes,
                longEntries, longLengths, longLow, longHigh);
    }

    /**
     * Returns the index of the first element greater than a key in an ascending array.
     *
     * @param sorted the ascending array
     * @param key the key, compared as a signed value
     * @return the index, or the array length when no element is greater
     */
    private static int firstAbove(int[] sorted, long key) {
        var low = 0;
        var high = sorted.length;
        while (low < high) {
            var middle = (low + high) >>> 1;
            if (sorted[middle] <= key) {
                low = middle + 1;
            } else {
                high = middle;
            }
        }
        return low;
    }

    /**
     * Reads one codeword and returns its entry number (scalar context).
     *
     * <p>A codeword that does not fit in the remaining packet bits, and every read after one,
     * reports end-of-packet.
     *
     * @param reader the packet reader
     * @return the entry number, or {@code -1} at end-of-packet
     */
    int decode(BitReader reader) {
        var entry = decodeUnchecked(reader);
        return reader.bitsLeft() >= 0 ? entry : -1;
    }

    /**
     * Reads one codeword without checking for end-of-packet, for callers that verified that at
     * least {@link #maxLength} bits remain.
     *
     * <p>Past the end of the packet the reader supplies zero bits, so the result is then an
     * arbitrary entry; the caller detects that through the reader.
     *
     * @param reader the packet reader
     * @return the entry number, or {@code -1} for a codebook without used entries
     */
    int decodeUnchecked(BitReader reader) {
        var bits = fastBits;
        reader.ensure(bits);
        var slot = reader.peekCached(bits);
        var value = fast[slot];
        if (value >= 0) {
            reader.consume(value & 31);
            return value >>> 5;
        }
        return decodeLong(reader, slot);
    }

    /**
     * Reads a codeword longer than the direct table resolves, without checking for
     * end-of-packet.
     *
     * <p>At least 32 bits must be available to {@link BitReader#peek(int)}, which the reader
     * guarantees by padding with zeros past the end of the packet.
     *
     * @param reader the packet reader
     * @param slot the direct table slot of the upcoming bits
     * @return the entry number, or {@code -1} for an empty codebook
     */
    int decodeLong(BitReader reader, int slot) {
        var codes = longCodes;
        var low = longLow[slot];
        var count = longHigh[slot] - low;
        if (count <= 0) {
            return -1;
        }
        var key = Integer.reverse(reader.peek(32)) ^ Integer.MIN_VALUE;
        while (count > 1) {
            var half = count >>> 1;
            if (codes[low + half] <= key) {
                low += half;
                count -= half;
            } else {
                count = half;
            }
        }
        reader.consume(longLengths[low]);
        return longEntries[low];
    }

    /**
     * Returns whether reads from this codebook can return value vectors.
     *
     * @return {@code true} when the codebook has a value lookup and at least one dimension
     */
    boolean hasValues() {
        return values != null && dimensions > 0;
    }

    /**
     * Unpacks the codebook float format: a 21-bit mantissa, a sign bit and a 10-bit exponent
     * biased by 788 (Vorbis I section 9.2.2).
     *
     * @param packed the packed 32-bit value
     * @return the value, rounded to single precision
     */
    static float float32Unpack(int packed) {
        var mantissa = packed & 0x1FFFFF;
        var exponent = (packed >>> 21) & 0x3FF;
        return (float) Math.scalb((double) (packed < 0 ? -mantissa : mantissa), exponent - 788);
    }

    /**
     * Returns the greatest integer whose {@code dimensions}-th power does not exceed
     * {@code entries} (Vorbis I section 9.2.3).
     *
     * @param entries the number of entries; non-negative
     * @param dimensions the number of dimensions; positive
     * @return the number of lattice values per dimension
     */
    static long lookup1Values(int entries, int dimensions) {
        if (entries <= 1) {
            return entries;
        }
        var guess = (long) Math.floor(Math.exp(Math.log(entries) / dimensions));
        while (guess > 0 && power(guess, dimensions) > entries) {
            guess--;
        }
        while (power(guess + 1, dimensions) <= entries) {
            guess++;
        }
        return guess;
    }

    /**
     * Returns {@code base} raised to {@code exponent}, saturated just above 2^24.
     *
     * @param base the base; non-negative
     * @param exponent the exponent; positive
     * @return the power, or a value above 2^24 when it would exceed that
     */
    private static long power(long base, int exponent) {
        var result = 1L;
        for (var i = 0; i < exponent; i++) {
            result *= base;
            if (result > 1 << 24) {
                return (1 << 24) + 1L;
            }
        }
        return result;
    }
}
