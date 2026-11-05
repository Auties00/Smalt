package com.github.auties00.smalt.image.exif;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;

/**
 * Holds the typed value of one EXIF (TIFF) directory entry.
 *
 * <p>TIFF 6.0 defines twelve field types, TIFF Technical Note 1 adds the {@code IFD} type and
 * EXIF 3.0 the {@code UTF-8} type. They are grouped here by how a caller consumes them: text
 * ({@link Ascii}, {@link Utf8}), integers of any width and signedness, sub-directory offsets
 * included ({@link Integers}), fractions ({@link Rationals}), IEEE floating point numbers
 * ({@link Reals}), and opaque bytes ({@link Undefined}). Every variant remembers its original
 * {@link Type}, so a value can be written back without loss. Array components are copied on the
 * way in and out, which keeps every value immutable.
 */
public sealed interface ExifValue permits ExifValue.Ascii, ExifValue.Utf8, ExifValue.Integers, ExifValue.Rationals,
        ExifValue.Reals, ExifValue.Undefined {
    /**
     * Returns the TIFF field type the value was stored with.
     *
     * @return the field type
     */
    Type type();

    /**
     * Returns the number of elements of the value.
     *
     * <p>For {@link Ascii} this is the number of characters, without the terminating NUL; for
     * {@link Utf8} the number of UTF-8 bytes, without the terminating NUL; for {@link Undefined}
     * the number of bytes.
     *
     * @return the element count, non-negative
     */
    int count();

    /**
     * Returns a {@link Type#SHORT} value.
     *
     * @param values the elements, each from 0 to 65535; copied
     * @return the value
     * @throws IllegalArgumentException if an element is outside 0..65535
     */
    static Integers ofShort(int... values) {
        var elements = new long[values.length];
        for (var i = 0; i < values.length; i++) {
            if (values[i] < 0 || values[i] > 0xFFFF) {
                throw new IllegalArgumentException("SHORT element " + values[i] + " is not in [0, 65535]");
            }
            elements[i] = values[i];
        }
        return new Integers(Type.SHORT, elements);
    }

    /**
     * Returns a {@link Type#LONG} value.
     *
     * @param values the elements, each from 0 to 4294967295; copied
     * @return the value
     * @throws IllegalArgumentException if an element is outside 0..4294967295
     */
    static Integers ofLong(long... values) {
        for (var value : values) {
            if (value < 0 || value > 0xFFFF_FFFFL) {
                throw new IllegalArgumentException("LONG element " + value + " is not in [0, 4294967295]");
            }
        }
        return new Integers(Type.LONG, values);
    }

    /**
     * Returns a {@link Type#RATIONAL} value holding one fraction.
     *
     * @param numerator the numerator, from 0 to 4294967295
     * @param denominator the denominator, from 0 to 4294967295; 0 is stored as given
     * @return the value
     * @throws IllegalArgumentException if a term is outside 0..4294967295
     */
    static Rationals ofRational(long numerator, long denominator) {
        if (numerator < 0 || numerator > 0xFFFF_FFFFL || denominator < 0 || denominator > 0xFFFF_FFFFL) {
            throw new IllegalArgumentException("RATIONAL " + numerator + "/" + denominator
                    + " has a term not in [0, 4294967295]");
        }
        return new Rationals(Type.RATIONAL, new long[]{numerator}, new long[]{denominator});
    }

    /**
     * Returns an {@link Type#ASCII} value.
     *
     * <p>Each character is stored as one byte, so the text must be ISO-8859-1 without NUL; plain
     * ASCII is what every reader decodes the same way.
     *
     * @param value the text, without the terminating NUL
     * @return the value
     * @throws IllegalArgumentException if a character is NUL or above U+00FF
     */
    static Ascii ofAscii(String value) {
        for (var i = 0; i < value.length(); i++) {
            var c = value.charAt(i);
            if (c == 0 || c > 0xFF) {
                throw new IllegalArgumentException("character U+" + String.format("%04X", (int) c) + " at index " + i
                        + " cannot be stored in an ASCII field");
            }
        }
        return new Ascii(value);
    }

    /**
     * Returns a {@link Type#UTF8} value, the EXIF 3.0 text type that holds any Unicode text.
     *
     * @apiNote Readers written before EXIF 3.0 skip entries of this type; prefer
     * {@link #ofAscii(String)} for plain ASCII text.
     * @param value the text, without the terminating NUL
     * @return the value
     * @throws IllegalArgumentException if a character is NUL or {@code value} holds an unpaired
     * surrogate
     */
    static Utf8 ofUtf8(String value) {
        return new Utf8(value);
    }

    /**
     * Enumerates the field types used by EXIF, with their code and element size: the twelve of
     * TIFF 6.0, the {@code IFD} type of TIFF Technical Note 1 and the {@code UTF-8} type of
     * EXIF 3.0.
     */
    enum Type {
        /**
         * An 8-bit unsigned integer (code 1).
         */
        BYTE(1, 1),

        /**
         * An 8-bit byte holding a 7-bit ASCII code, NUL terminated (code 2).
         */
        ASCII(2, 1),

        /**
         * A 16-bit unsigned integer (code 3).
         */
        SHORT(3, 2),

        /**
         * A 32-bit unsigned integer (code 4).
         */
        LONG(4, 4),

        /**
         * Two {@link #LONG} values, a numerator and a denominator (code 5).
         */
        RATIONAL(5, 8),

        /**
         * An 8-bit signed integer (code 6).
         */
        SBYTE(6, 1),

        /**
         * An 8-bit byte with a meaning defined by the tag (code 7).
         */
        UNDEFINED(7, 1),

        /**
         * A 16-bit signed integer (code 8).
         */
        SSHORT(8, 2),

        /**
         * A 32-bit signed integer (code 9).
         */
        SLONG(9, 4),

        /**
         * Two {@link #SLONG} values, a numerator and a denominator (code 10).
         */
        SRATIONAL(10, 8),

        /**
         * A single precision IEEE 754 number (code 11).
         */
        FLOAT(11, 4),

        /**
         * A double precision IEEE 754 number (code 12).
         */
        DOUBLE(12, 8),

        /**
         * A 32-bit unsigned offset of a sub-directory (code 13, TIFF Technical Note 1).
         */
        IFD(13, 4),

        /**
         * An 8-bit byte of a NUL-terminated UTF-8 string (code 129, EXIF 3.0).
         */
        UTF8(129, 1);

        /**
         * The constants indexed by code, with {@code null} for unassigned codes.
         */
        private static final Type[] BY_CODE = byCode();

        /**
         * The TIFF type code.
         */
        private final int code;

        /**
         * The size in bytes of one element.
         */
        private final int size;

        /**
         * Constructs a constant.
         *
         * @param code the TIFF type code
         * @param size the size in bytes of one element
         */
        Type(int code, int size) {
            this.code = code;
            this.size = size;
        }

        /**
         * Returns the TIFF type code.
         *
         * @return a value from 1 to 13, or 129
         */
        public int code() {
            return code;
        }

        /**
         * Returns the size in bytes of one element.
         *
         * @return 1, 2, 4 or 8
         */
        public int size() {
            return size;
        }

        /**
         * Returns the constant for a TIFF type code, or {@code null} when the code is not one of the
         * listed types.
         *
         * @param code the type code read from a directory entry
         * @return the matching constant, or {@code null}
         */
        static Type find(int code) {
            return code >= 1 && code < BY_CODE.length ? BY_CODE[code] : null;
        }

        /**
         * Returns the constant for a TIFF type code.
         *
         * @param code the type code, from 1 to 13, or 129
         * @return the matching constant
         * @throws IllegalArgumentException if {@code code} is not one of the listed type codes
         */
        public static Type of(int code) {
            var type = find(code);
            if (type == null) {
                throw new IllegalArgumentException("TIFF type code " + code + " is not in [1, 13] or 129");
            }
            return type;
        }

        /**
         * Builds the lookup table behind {@link #find(int)}.
         *
         * @return the constants indexed by code
         */
        private static Type[] byCode() {
            var table = new Type[130];
            for (var type : values()) {
                table[type.code] = type;
            }
            return table;
        }
    }

    /**
     * Holds an {@link Type#ASCII} value.
     *
     * <p>The text ends at the first NUL byte; bytes are mapped one to one to characters
     * (ISO-8859-1), so non-ASCII bytes written by careless writers survive unchanged.
     *
     * @param value the text, without the terminating NUL
     */
    record Ascii(String value) implements ExifValue {
        /**
         * Validates the value.
         *
         * @throws NullPointerException if {@code value} is {@code null}
         */
        public Ascii {
            Objects.requireNonNull(value, "value");
        }

        /**
         * Decodes a NUL-terminated TIFF string.
         *
         * @param data   the array holding the string
         * @param offset the index of the first byte
         * @param length the number of bytes of the field, including any NUL terminator
         * @return the decoded value
         */
        static Ascii decode(byte[] data, int offset, int length) {
            var end = offset;
            var limit = offset + length;
            while (end < limit && data[end] != 0) {
                end++;
            }
            return new Ascii(new String(data, offset, end - offset, StandardCharsets.ISO_8859_1));
        }

        @Override
        public Type type() {
            return Type.ASCII;
        }

        @Override
        public int count() {
            return value.length();
        }
    }

    /**
     * Holds a {@link Type#UTF8} value (EXIF 3.0).
     *
     * <p>The text ends at the first NUL byte. Bytes that are not well-formed UTF-8 decode to
     * U+FFFD, so only a well-formed value is written back byte for byte.
     *
     * @param value the text, without the terminating NUL
     */
    record Utf8(String value) implements ExifValue {
        /**
         * Validates the value.
         *
         * @throws NullPointerException if {@code value} is {@code null}
         * @throws IllegalArgumentException if a character is NUL or {@code value} holds an unpaired
         * surrogate
         */
        public Utf8 {
            Objects.requireNonNull(value, "value");
            for (var i = 0; i < value.length(); i++) {
                var c = value.charAt(i);
                if (c == 0) {
                    throw new IllegalArgumentException("NUL at index " + i + " cannot be stored in a UTF-8 field");
                }
                if (Character.isHighSurrogate(c) && i + 1 < value.length() && Character.isLowSurrogate(value.charAt(i + 1))) {
                    i++;
                } else if (Character.isSurrogate(c)) {
                    throw new IllegalArgumentException("unpaired surrogate at index " + i);
                }
            }
        }

        /**
         * Decodes a NUL-terminated UTF-8 string.
         *
         * @param data   the array holding the string
         * @param offset the index of the first byte
         * @param length the number of bytes of the field, including any NUL terminator
         * @return the decoded value
         */
        static Utf8 decode(byte[] data, int offset, int length) {
            var end = offset;
            var limit = offset + length;
            while (end < limit && data[end] != 0) {
                end++;
            }
            return new Utf8(new String(data, offset, end - offset, StandardCharsets.UTF_8));
        }

        /**
         * {@return the UTF-8 bytes of the text followed by the NUL terminator}
         */
        byte[] encode() {
            var text = value.getBytes(StandardCharsets.UTF_8);
            return Arrays.copyOf(text, text.length + 1);
        }

        @Override
        public Type type() {
            return Type.UTF8;
        }

        @Override
        public int count() {
            return value.getBytes(StandardCharsets.UTF_8).length;
        }
    }

    /**
     * Holds an integer value of type {@link Type#BYTE}, {@link Type#SHORT}, {@link Type#LONG},
     * {@link Type#SBYTE}, {@link Type#SSHORT}, {@link Type#SLONG} or {@link Type#IFD}.
     *
     * <p>Unsigned types are zero-extended and signed types sign-extended into {@code long}
     * elements, so every stored value is represented exactly. An {@link Type#IFD} element is the
     * offset of a sub-directory, which stays valid only through an edit that keeps the block's
     * layout ({@link ExifWriter#rewrite(byte[], ExifData)}).
     *
     * @param type   the integer field type
     * @param values the elements
     */
    record Integers(Type type, long[] values) implements ExifValue {
        /**
         * Validates the value and copies the elements.
         *
         * @throws NullPointerException     if an argument is {@code null}
         * @throws IllegalArgumentException if {@code type} is not an integer type
         */
        public Integers {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(values, "values");
            switch (type) {
                case BYTE, SHORT, LONG, SBYTE, SSHORT, SLONG, IFD -> {
                }
                default -> throw new IllegalArgumentException(type + " is not an integer type");
            }
            values = values.clone();
        }

        /**
         * Returns a copy of the elements.
         *
         * @return the elements
         */
        @Override
        public long[] values() {
            return values.clone();
        }

        /**
         * Returns one element.
         *
         * @param index the element index
         * @return the element
         * @throws IndexOutOfBoundsException if {@code index} is out of range
         */
        public long get(int index) {
            return values[index];
        }

        @Override
        public int count() {
            return values.length;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Integers that && type == that.type && Arrays.equals(values, that.values);
        }

        @Override
        public int hashCode() {
            return 31 * type.hashCode() + Arrays.hashCode(values);
        }

        @Override
        public String toString() {
            return "Integers[type=" + type + ", values=" + Arrays.toString(values) + "]";
        }
    }

    /**
     * Holds a fraction value of type {@link Type#RATIONAL} or {@link Type#SRATIONAL}.
     *
     * <p>Unsigned fractions are zero-extended and signed fractions sign-extended into
     * {@code long} elements. A zero denominator is kept as stored.
     *
     * @param type the fraction field type
     * @param numerators the numerators, as many as denominators
     * @param denominators the denominators, as many as numerators
     */
    record Rationals(Type type, long[] numerators, long[] denominators) implements ExifValue {
        /**
         * Validates the value and copies the elements.
         *
         * @throws NullPointerException     if an argument is {@code null}
         * @throws IllegalArgumentException if {@code type} is not a fraction type or the arrays
         *                                  differ in length
         */
        public Rationals {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(numerators, "numerators");
            Objects.requireNonNull(denominators, "denominators");
            if (type != Type.RATIONAL && type != Type.SRATIONAL) {
                throw new IllegalArgumentException(type + " is not a fraction type");
            }
            if (numerators.length != denominators.length) {
                throw new IllegalArgumentException("numerator and denominator counts differ");
            }
            numerators = numerators.clone();
            denominators = denominators.clone();
        }

        /**
         * Returns a copy of the numerators.
         *
         * @return the numerators
         */
        @Override
        public long[] numerators() {
            return numerators.clone();
        }

        /**
         * Returns a copy of the denominators.
         *
         * @return the denominators
         */
        @Override
        public long[] denominators() {
            return denominators.clone();
        }

        /**
         * Returns one element as a floating point number.
         *
         * @param index the element index
         * @return the quotient, or {@link Double#NaN} when the denominator is zero
         * @throws IndexOutOfBoundsException if {@code index} is out of range
         */
        public double doubleValue(int index) {
            var denominator = denominators[index];
            return denominator == 0 ? Double.NaN : (double) numerators[index] / denominator;
        }

        @Override
        public int count() {
            return numerators.length;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Rationals that && type == that.type
                    && Arrays.equals(numerators, that.numerators) && Arrays.equals(denominators, that.denominators);
        }

        @Override
        public int hashCode() {
            return (31 * type.hashCode() + Arrays.hashCode(numerators)) * 31 + Arrays.hashCode(denominators);
        }

        @Override
        public String toString() {
            return "Rationals[type=" + type + ", numerators=" + Arrays.toString(numerators)
                    + ", denominators=" + Arrays.toString(denominators) + "]";
        }
    }

    /**
     * Holds a floating point value of type {@link Type#FLOAT} or {@link Type#DOUBLE}.
     *
     * @param type   the floating point field type
     * @param values the elements, widened to {@code double}
     */
    record Reals(Type type, double[] values) implements ExifValue {
        /**
         * Validates the value and copies the elements.
         *
         * @throws NullPointerException     if an argument is {@code null}
         * @throws IllegalArgumentException if {@code type} is not a floating point type
         */
        public Reals {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(values, "values");
            if (type != Type.FLOAT && type != Type.DOUBLE) {
                throw new IllegalArgumentException(type + " is not a floating point type");
            }
            values = values.clone();
        }

        /**
         * Returns a copy of the elements.
         *
         * @return the elements
         */
        @Override
        public double[] values() {
            return values.clone();
        }

        @Override
        public int count() {
            return values.length;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Reals that && type == that.type && Arrays.equals(values, that.values);
        }

        @Override
        public int hashCode() {
            return 31 * type.hashCode() + Arrays.hashCode(values);
        }

        @Override
        public String toString() {
            return "Reals[type=" + type + ", values=" + Arrays.toString(values) + "]";
        }
    }

    /**
     * Holds an {@link Type#UNDEFINED} value, whose bytes are interpreted by the tag.
     *
     * @param bytes the raw bytes
     */
    record Undefined(byte[] bytes) implements ExifValue {
        /**
         * Validates the value and copies the bytes.
         *
         * @throws NullPointerException if {@code bytes} is {@code null}
         */
        public Undefined {
            Objects.requireNonNull(bytes, "bytes");
            bytes = bytes.clone();
        }

        /**
         * Returns a copy of the bytes.
         *
         * @return the bytes
         */
        @Override
        public byte[] bytes() {
            return bytes.clone();
        }

        @Override
        public Type type() {
            return Type.UNDEFINED;
        }

        @Override
        public int count() {
            return bytes.length;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Undefined that && Arrays.equals(bytes, that.bytes);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(bytes);
        }

        @Override
        public String toString() {
            return "Undefined[bytes=" + bytes.length + "]";
        }
    }
}
