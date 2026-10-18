package com.github.auties00.smalt.internal.tiff;

import com.github.auties00.smalt.internal.io.ByteArrays;

/**
 * The field types of TIFF directory entries (TIFF 6.0 section 2, plus the BigTIFF types) and the
 * decoding of their values.
 *
 * <p>This class is thread-safe.
 */
public final class TiffTypes {
    /**
     * {@code BYTE}: an unsigned 8-bit integer.
     */
    public static final int BYTE = 1;

    /**
     * {@code ASCII}: an 8-bit character, the last one NUL.
     */
    public static final int ASCII = 2;

    /**
     * {@code SHORT}: an unsigned 16-bit integer.
     */
    public static final int SHORT = 3;

    /**
     * {@code LONG}: an unsigned 32-bit integer.
     */
    public static final int LONG = 4;

    /**
     * {@code RATIONAL}: two {@code LONG}s, a numerator and a denominator.
     */
    public static final int RATIONAL = 5;

    /**
     * {@code SBYTE}: a signed 8-bit integer.
     */
    public static final int SBYTE = 6;

    /**
     * {@code UNDEFINED}: an opaque byte.
     */
    public static final int UNDEFINED = 7;

    /**
     * {@code SSHORT}: a signed 16-bit integer.
     */
    public static final int SSHORT = 8;

    /**
     * {@code SLONG}: a signed 32-bit integer.
     */
    public static final int SLONG = 9;

    /**
     * {@code SRATIONAL}: two {@code SLONG}s, a numerator and a denominator.
     */
    public static final int SRATIONAL = 10;

    /**
     * {@code FLOAT}: an IEEE single-precision number.
     */
    public static final int FLOAT = 11;

    /**
     * {@code DOUBLE}: an IEEE double-precision number.
     */
    public static final int DOUBLE = 12;

    /**
     * {@code IFD}: a 32-bit directory offset.
     */
    public static final int IFD = 13;

    /**
     * {@code LONG8}: an unsigned 64-bit integer (BigTIFF).
     */
    public static final int LONG8 = 16;

    /**
     * {@code SLONG8}: a signed 64-bit integer (BigTIFF).
     */
    public static final int SLONG8 = 17;

    /**
     * {@code IFD8}: a 64-bit directory offset (BigTIFF).
     */
    public static final int IFD8 = 18;

    /**
     * Prevents instantiation.
     */
    private TiffTypes() {
        throw new AssertionError();
    }

    /**
     * Returns the size of one value of a field type.
     *
     * @param type the type code
     * @return the size in bytes, or 0 for an unknown type
     */
    public static int size(int type) {
        return switch (type) {
            case BYTE, ASCII, SBYTE, UNDEFINED -> 1;
            case SHORT, SSHORT -> 2;
            case LONG, SLONG, FLOAT, IFD -> 4;
            case RATIONAL, SRATIONAL, DOUBLE, LONG8, SLONG8, IFD8 -> 8;
            default -> 0;
        };
    }

    /**
     * Returns whether a field type holds integers.
     *
     * @param type the type code
     * @return {@code true} for the byte, short, long, 64-bit and offset types, signed or not, and
     * {@code UNDEFINED}
     */
    public static boolean isInteger(int type) {
        return switch (type) {
            case BYTE, SBYTE, UNDEFINED, SHORT, SSHORT, LONG, SLONG, IFD, LONG8, SLONG8, IFD8 -> true;
            default -> false;
        };
    }

    /**
     * Decodes one integer value; signed types are sign-extended, and a 64-bit value keeps its
     * bits.
     *
     * @param data the value bytes
     * @param at the index of the value
     * @param type an integer type, as {@link #isInteger(int)} accepts
     * @param little whether the structure is little endian
     * @return the value
     * @throws IllegalArgumentException if {@code type} is not an integer type
     */
    public static long integer(byte[] data, int at, int type, boolean little) {
        return switch (type) {
            case BYTE, UNDEFINED -> data[at] & 0xFF;
            case SBYTE -> data[at];
            case SHORT -> ByteArrays.u16(data, at, little);
            case SSHORT -> (short) ByteArrays.u16(data, at, little);
            case LONG, IFD -> ByteArrays.u32(data, at, little);
            case SLONG -> (int) ByteArrays.u32(data, at, little);
            case LONG8, SLONG8, IFD8 -> little ? ByteArrays.i64le(data, at) : ByteArrays.i64be(data, at);
            default -> throw new IllegalArgumentException("TIFF type " + type + " is not an integer type");
        };
    }

    /**
     * Decodes one numeric value as a {@code double}; a rational with a zero denominator is 0.
     *
     * @param data the value bytes
     * @param at the index of the value
     * @param type a numeric type: an integer type, a rational type, {@code FLOAT} or {@code DOUBLE}
     * @param little whether the structure is little endian
     * @return the value
     * @throws IllegalArgumentException if {@code type} is not a numeric type
     */
    public static double number(byte[] data, int at, int type, boolean little) {
        return switch (type) {
            case RATIONAL -> ratio(ByteArrays.u32(data, at, little), ByteArrays.u32(data, at + 4, little));
            case SRATIONAL -> ratio((int) ByteArrays.u32(data, at, little), (int) ByteArrays.u32(data, at + 4, little));
            case FLOAT -> Float.intBitsToFloat((int) ByteArrays.u32(data, at, little));
            case DOUBLE -> Double.longBitsToDouble(little ? ByteArrays.i64le(data, at) : ByteArrays.i64be(data, at));
            default -> {
                if (!isInteger(type)) {
                    throw new IllegalArgumentException("TIFF type " + type + " is not a numeric type");
                }
                yield integer(data, at, type, little);
            }
        };
    }

    /**
     * {@return a quotient, or 0 for a zero denominator}
     *
     * @param numerator the dividend
     * @param denominator the divisor
     */
    private static double ratio(long numerator, long denominator) {
        return denominator == 0 ? 0 : (double) numerator / denominator;
    }
}
