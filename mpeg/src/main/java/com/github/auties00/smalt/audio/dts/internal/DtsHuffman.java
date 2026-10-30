package com.github.auties00.smalt.audio.dts.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.internal.bits.MsbBitReader;

import java.util.HashMap;
import java.util.Map;

/**
 * A Huffman code book of the DTS core (ETSI TS 102 114 D.5), decoded through a lookup of the next bits.
 *
 * <p>Instances are immutable and thread-safe.
 */
public final class DtsHuffman {
    /**
     * The width of the first-level lookup, in bits.
     */
    private static final int FAST_BITS = 10;

    /**
     * Every code book, by its name in D.5 ({@code "A3"}, {@code "SA129"}, ...).
     */
    private static final Map<String, DtsHuffman> BOOKS = parse();

    /**
     * The first-level lookup by the next {@link #FAST_BITS} bits: {@code level << 8 | length}, or {@code 0} when the
     * code is longer.
     */
    private final int[] fast;

    /**
     * The codes longer than {@link #FAST_BITS}, by increasing length: lengths.
     */
    private final int[] longLengths;

    /**
     * The codes of {@link #longLengths}.
     */
    private final int[] longCodes;

    /**
     * The levels of {@link #longLengths}.
     */
    private final int[] longLevels;

    /**
     * The longest code, in bits.
     */
    private final int maxLength;

    /**
     * The smallest level the book codes.
     */
    private final int minLevel;

    /**
     * The largest level the book codes.
     */
    private final int maxLevel;

    /**
     * Constructs a code book.
     *
     * @param levels the level of each code
     * @param lengths the length of each code
     * @param codes the code of each entry, right-aligned
     */
    private DtsHuffman(int[] levels, int[] lengths, int[] codes) {
        var max = 0;
        var min = Integer.MAX_VALUE;
        var top = Integer.MIN_VALUE;
        var longCount = 0;
        for (var i = 0; i < levels.length; i++) {
            max = Math.max(max, lengths[i]);
            min = Math.min(min, levels[i]);
            top = Math.max(top, levels[i]);
            if (lengths[i] > FAST_BITS) {
                longCount++;
            }
        }
        this.maxLength = max;
        this.minLevel = min;
        this.maxLevel = top;
        this.fast = new int[1 << FAST_BITS];
        this.longLengths = new int[longCount];
        this.longCodes = new int[longCount];
        this.longLevels = new int[longCount];
        var next = 0;
        for (var length = FAST_BITS + 1; length <= max; length++) {
            for (var i = 0; i < levels.length; i++) {
                if (lengths[i] == length) {
                    longLengths[next] = length;
                    longCodes[next] = codes[i];
                    longLevels[next] = levels[i];
                    next++;
                }
            }
        }
        for (var i = 0; i < levels.length; i++) {
            if (lengths[i] <= FAST_BITS) {
                var shift = FAST_BITS - lengths[i];
                var first = codes[i] << shift;
                for (var j = 0; j < 1 << shift; j++) {
                    fast[first + j] = levels[i] << 8 | lengths[i];
                }
            }
        }
    }

    /**
     * Returns a code book by its name in ETSI TS 102 114 D.5.
     *
     * @param name the name, for example {@code "A3"}, {@code "B12"} or {@code "SA129"}
     * @return the code book
     * @throws IllegalArgumentException if no code book has the name
     */
    public static DtsHuffman book(String name) {
        var book = BOOKS.get(name);
        if (book == null) {
            throw new IllegalArgumentException("no DTS code book " + name);
        }
        return book;
    }

    /**
     * Decodes one level.
     *
     * @param reader the bit stream
     * @return the level
     * @throws MediaException.Malformed if the next bits start no code of the book
     */
    public int decode(MsbBitReader reader) {
        var entry = fast[reader.peek(FAST_BITS)];
        if (entry != 0) {
            reader.skip(entry & 0xFF);
            return entry >> 8;
        }
        var bits = reader.peek(maxLength);
        for (var i = 0; i < longLengths.length; i++) {
            var length = longLengths[i];
            if (bits >>> (maxLength - length) == longCodes[i]) {
                reader.skip(length);
                return longLevels[i];
            }
        }
        throw new MediaException.Malformed("invalid DTS Huffman code");
    }

    /**
     * {@return the smallest level the book codes}
     */
    public int minLevel() {
        return minLevel;
    }

    /**
     * {@return the largest level the book codes}
     */
    public int maxLevel() {
        return maxLevel;
    }

    /**
     * Reads every code book from {@link DtsCoreTables#HUFFMAN}.
     *
     * @return the books by name
     */
    private static Map<String, DtsHuffman> parse() {
        var books = new HashMap<String, DtsHuffman>();
        for (var line : DtsCoreTables.HUFFMAN.lines().toList()) {
            var text = line.strip();
            if (text.isEmpty()) {
                continue;
            }
            var colon = text.indexOf(':');
            var values = text.substring(colon + 1).split(",");
            var count = values.length / 3;
            var levels = new int[count];
            var lengths = new int[count];
            var codes = new int[count];
            for (var i = 0; i < count; i++) {
                levels[i] = Integer.parseInt(values[3 * i]);
                lengths[i] = Integer.parseInt(values[3 * i + 1]);
                codes[i] = Integer.parseInt(values[3 * i + 2]);
            }
            books.put(text.substring(0, colon), new DtsHuffman(levels, lengths, codes));
        }
        return Map.copyOf(books);
    }
}
