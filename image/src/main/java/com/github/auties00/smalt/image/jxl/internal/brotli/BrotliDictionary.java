/* Ports Brotli (java/org/brotli/dec, the transform table; c/common/dictionary.bin). Copyright the Brotli Authors;
   MIT License. Modified for Smalt. */
package com.github.auties00.smalt.image.jxl.internal.brotli;

import com.github.auties00.smalt.MediaException;

import java.io.EOFException;
import java.io.FileNotFoundException;
import java.io.IOException;

/**
 * Holds the static dictionary of Brotli (RFC 7932 appendix A) and applies its word transforms
 * (appendix B).
 *
 * <p>The 122,784-byte dictionary is loaded on first use from the {@code dictionary.bin} resource
 * next to this class.
 */
final class BrotliDictionary {
    /**
     * The shortest dictionary word.
     */
    static final int MIN_WORD_LENGTH = 4;

    /**
     * The longest dictionary word.
     */
    static final int MAX_WORD_LENGTH = 24;

    /**
     * The base-2 logarithm of the number of words of each length, by length.
     */
    static final int[] SIZE_BITS = {0, 0, 0, 0, 10, 10, 11, 11, 10, 10, 10, 10, 10, 9, 9, 8, 7, 7, 8, 7, 7, 6, 6, 5, 5};

    /**
     * The offset of the words of each length in the dictionary, by length.
     */
    static final int[] OFFSETS = offsets();

    /**
     * The number of word transforms.
     */
    static final int TRANSFORM_COUNT = 121;

    /**
     * The size of the dictionary.
     */
    private static final int SIZE = 122_784;

    /**
     * The transform type that copies the word unchanged.
     */
    private static final int IDENTITY = 0;

    /**
     * The transform type that upper-cases the first character.
     */
    private static final int UPPERCASE_FIRST = 10;

    /**
     * The transform type that upper-cases every character.
     */
    private static final int UPPERCASE_ALL = 11;

    /**
     * The prefixes and suffixes, separated by {@code #}.
     */
    private static final String PREFIX_SUFFIX = "# #s #, #e #.# the #.com/#\u00C2\u00A0# of # and"
            + " # in # to #\"#\">#\n#]# for # a # that #. # with #'# from # by #. The # on # as # is #ing"
            + " #\n\t#:#ed #(# at #ly #=\"# of the #. This #,# not #er #al #='#ful #ive #less #est #ize #"
            + "ous #";

    /**
     * The transforms, three characters each (prefix index, type, suffix index), offset by 32.
     */
    private static final String TRANSFORMS = "     !! ! ,  *!  &!  \" !  ) *   * -  ! # !  #!*!  "
            + "+  ,$ !  -  %  .  / #   0  1 .  \"   2  3!*   4%  ! # /   5  6  7  8 0  1 &   $   9 +   : "
            + " ;  < '  !=  >  ?! 4  @ 4  2  &   A *# (   B  C& ) %  ) !*# *-% A +! *.  D! %'  & E *6  F "
            + " G% ! *A *%  H! D  I!+!  J!+   K +- *4! A  L!*4  M  N +6  O!*% +.! K *G  P +%(  ! G *D +D "
            + " Q +# *K!*G!+D!+# +G +A +4!+% +K!+4!*D!+K!*K";

    /**
     * The prefix and suffix bytes.
     */
    private static final byte[] AFFIXES = new byte[167];

    /**
     * The start of each prefix and suffix in {@link #AFFIXES}, plus the end.
     */
    private static final int[] AFFIX_HEADS = new int[51];

    static {
        var index = 1;
        var length = 0;
        for (var position = 0; position < PREFIX_SUFFIX.length(); position++) {
            var c = PREFIX_SUFFIX.charAt(position);
            if (c == '#') {
                AFFIX_HEADS[index++] = length;
            } else {
                AFFIXES[length++] = (byte) c;
            }
        }
    }

    /**
     * Prevents instantiation.
     */
    private BrotliDictionary() {
        throw new AssertionError();
    }

    /**
     * Returns the dictionary, loading it on first use.
     *
     * @return the dictionary bytes
     * @throws MediaException.Io if the resource cannot be read
     */
    static byte[] data() {
        return Holder.DATA;
    }

    /**
     * Writes a transformed dictionary word.
     *
     * @param destination the output, with room for the word plus 37 bytes
     * @param offset the write position
     * @param dictionary the static dictionary bytes
     * @param wordOffset the start of the word
     * @param wordLength the word length
     * @param transform the transform index
     * @return the number of bytes written
     */
    static int transform(byte[] destination, int offset, byte[] dictionary, int wordOffset, int wordLength, int transform) {
        var out = offset;
        var prefix = TRANSFORMS.charAt(transform * 3) - 32;
        var type = TRANSFORMS.charAt(transform * 3 + 1) - 32;
        var suffix = TRANSFORMS.charAt(transform * 3 + 2) - 32;
        for (var index = AFFIX_HEADS[prefix]; index < AFFIX_HEADS[prefix + 1]; index++) {
            destination[out++] = AFFIXES[index];
        }
        var omitFirst = type >= 12 && type <= 20 ? type - 11 : 0;
        var omitLast = type >= 1 && type <= 9 ? type : 0;
        omitFirst = Math.min(omitFirst, wordLength);
        var length = Math.max(0, wordLength - omitFirst - omitLast);
        System.arraycopy(dictionary, wordOffset + omitFirst, destination, out, length);
        out += length;
        if (type == UPPERCASE_FIRST || type == UPPERCASE_ALL) {
            var position = out - length;
            var left = type == UPPERCASE_FIRST ? Math.min(length, 1) : length;
            while (left > 0) {
                var c = destination[position] & 0xFF;
                if (c < 0xC0) {
                    if (c >= 'a' && c <= 'z') {
                        destination[position] ^= 32;
                    }
                    position += 1;
                    left -= 1;
                } else if (c < 0xE0) {
                    destination[position + 1] ^= 32;
                    position += 2;
                    left -= 2;
                } else {
                    destination[position + 2] ^= 5;
                    position += 3;
                    left -= 3;
                }
            }
        } else if (type != IDENTITY && omitFirst == 0 && omitLast == 0) {
            throw new MediaException.Malformed("invalid Brotli transform type " + type);
        }
        for (var index = AFFIX_HEADS[suffix]; index < AFFIX_HEADS[suffix + 1]; index++) {
            destination[out++] = AFFIXES[index];
        }
        return out - offset;
    }

    /**
     * Computes the offset of the words of each length.
     *
     * @return the offsets
     */
    private static int[] offsets() {
        var offsets = new int[SIZE_BITS.length];
        var position = 0;
        for (var length = 0; length < SIZE_BITS.length; length++) {
            offsets[length] = position;
            if (SIZE_BITS[length] != 0) {
                position += length << SIZE_BITS[length];
            }
        }
        return offsets;
    }

    /**
     * Loads the dictionary on first use.
     */
    private static final class Holder {
        /**
         * The dictionary bytes.
         */
        private static final byte[] DATA = load();

        /**
         * Prevents instantiation.
         */
        private Holder() {
            throw new AssertionError();
        }

        /**
         * Reads the dictionary resource.
         *
         * @return the bytes
         * @throws MediaException.Io if the resource is missing or truncated
         */
        private static byte[] load() {
            try (var in = BrotliDictionary.class.getResourceAsStream("dictionary.bin")) {
                if (in == null) {
                    throw new MediaException.Io("the Brotli dictionary resource is missing", new FileNotFoundException("dictionary.bin"));
                }
                var data = in.readAllBytes();
                if (data.length != SIZE) {
                    throw new MediaException.Io("the Brotli dictionary resource is corrupt", new EOFException("dictionary.bin"));
                }
                return data;
            } catch (IOException exception) {
                throw new MediaException.Io("cannot read the Brotli dictionary", exception);
            }
        }
    }
}
