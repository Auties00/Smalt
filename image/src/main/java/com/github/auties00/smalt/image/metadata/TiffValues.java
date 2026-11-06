package com.github.auties00.smalt.image.metadata;

import com.github.auties00.smalt.image.exif.ExifValue;
import com.github.auties00.smalt.internal.io.ByteArrays;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Converts the values of TIFF directory entries between their stored bytes and {@link ExifValue}.
 *
 * <p>Only the twelve TIFF 6.0 field types have an {@link ExifValue}; the BigTIFF 64-bit types and
 * unknown types do not.
 */
final class TiffValues {
    /**
     * Prevents instantiation.
     */
    private TiffValues() {
        throw new AssertionError();
    }

    /**
     * Returns the {@link ExifValue} type of a field type code.
     *
     * @param code the field type code of an entry
     * @return the type, or {@code null} when the code is neither a TIFF 6.0 type nor the EXIF 3.0
     * {@code UTF-8} type
     */
    static ExifValue.Type type(int code) {
        return (code >= 1 && code <= 12) || code == ExifValue.Type.UTF8.code() ? ExifValue.Type.of(code) : null;
    }

    /**
     * Decodes the stored bytes of an entry.
     *
     * @param type the field type
     * @param data the value bytes, exactly the count times the type size
     * @param little whether the file stores integers little endian
     * @return the value
     */
    static ExifValue decode(ExifValue.Type type, byte[] data, boolean little) {
        var count = data.length / type.size();
        return switch (type) {
            case ASCII -> {
                var end = 0;
                while (end < data.length && data[end] != 0) {
                    end++;
                }
                yield new ExifValue.Ascii(new String(data, 0, end, StandardCharsets.ISO_8859_1));
            }
            case UTF8 -> {
                var end = 0;
                while (end < data.length && data[end] != 0) {
                    end++;
                }
                yield new ExifValue.Utf8(new String(data, 0, end, StandardCharsets.UTF_8));
            }
            case UNDEFINED -> new ExifValue.Undefined(data);
            case BYTE, SBYTE, SHORT, SSHORT, LONG, SLONG, IFD -> {
                var values = new long[count];
                for (var i = 0; i < count; i++) {
                    values[i] = switch (type) {
                        case BYTE -> data[i] & 0xFF;
                        case SBYTE -> data[i];
                        case SHORT -> ByteArrays.u16(data, 2 * i, little);
                        case SSHORT -> (short) ByteArrays.u16(data, 2 * i, little);
                        case LONG, IFD -> ByteArrays.u32(data, 4 * i, little);
                        default -> (int) ByteArrays.u32(data, 4 * i, little);
                    };
                }
                yield new ExifValue.Integers(type, values);
            }
            case RATIONAL, SRATIONAL -> {
                var numerators = new long[count];
                var denominators = new long[count];
                for (var i = 0; i < count; i++) {
                    var numerator = ByteArrays.u32(data, 8 * i, little);
                    var denominator = ByteArrays.u32(data, 8 * i + 4, little);
                    numerators[i] = type == ExifValue.Type.SRATIONAL ? (int) numerator : numerator;
                    denominators[i] = type == ExifValue.Type.SRATIONAL ? (int) denominator : denominator;
                }
                yield new ExifValue.Rationals(type, numerators, denominators);
            }
            case FLOAT, DOUBLE -> {
                var values = new double[count];
                for (var i = 0; i < count; i++) {
                    values[i] = type == ExifValue.Type.FLOAT
                            ? Float.intBitsToFloat((int) ByteArrays.u32(data, 4 * i, little))
                            : Double.longBitsToDouble(little ? ByteArrays.i64le(data, 8 * i) : ByteArrays.i64be(data, 8 * i));
                }
                yield new ExifValue.Reals(type, values);
            }
        };
    }

    /**
     * Encodes a value as an entry stores it; text gains its terminating NUL.
     *
     * @param value the value to store
     * @param little whether to write integers little endian
     * @return the bytes, a whole number of elements of the value's type
     */
    static byte[] encode(ExifValue value, boolean little) {
        return switch (value) {
            case ExifValue.Ascii ascii -> {
                var text = ascii.value().getBytes(StandardCharsets.ISO_8859_1);
                yield Arrays.copyOf(text, text.length + 1);
            }
            case ExifValue.Utf8 utf8 -> {
                var text = utf8.value().getBytes(StandardCharsets.UTF_8);
                yield Arrays.copyOf(text, text.length + 1);
            }
            case ExifValue.Undefined undefined -> undefined.bytes();
            case ExifValue.Integers integers -> {
                var size = integers.type().size();
                var values = integers.values();
                var bytes = new byte[values.length * size];
                for (var i = 0; i < values.length; i++) {
                    switch (size) {
                        case 1 -> bytes[i] = (byte) values[i];
                        case 2 -> ByteArrays.put16(bytes, 2 * i, (int) values[i], little);
                        default -> ByteArrays.put32(bytes, 4 * i, values[i], little);
                    }
                }
                yield bytes;
            }
            case ExifValue.Rationals rationals -> {
                var numerators = rationals.numerators();
                var denominators = rationals.denominators();
                var bytes = new byte[numerators.length * 8];
                for (var i = 0; i < numerators.length; i++) {
                    ByteArrays.put32(bytes, 8 * i, numerators[i], little);
                    ByteArrays.put32(bytes, 8 * i + 4, denominators[i], little);
                }
                yield bytes;
            }
            case ExifValue.Reals reals -> {
                var values = reals.values();
                var single = reals.type() == ExifValue.Type.FLOAT;
                var bytes = new byte[values.length * (single ? 4 : 8)];
                for (var i = 0; i < values.length; i++) {
                    if (single) {
                        ByteArrays.put32(bytes, 4 * i, Float.floatToRawIntBits((float) values[i]) & 0xFFFFFFFFL, little);
                    } else {
                        put64(bytes, 8 * i, Double.doubleToRawLongBits(values[i]), little);
                    }
                }
                yield bytes;
            }
        };
    }

    /**
     * Writes a 64-bit integer.
     *
     * @param data the destination
     * @param offset the index of the first byte
     * @param value the 64-bit integer
     * @param little whether to write little endian
     */
    static void put64(byte[] data, int offset, long value, boolean little) {
        if (little) {
            ByteArrays.put64le(data, offset, value);
        } else {
            ByteArrays.put64be(data, offset, value);
        }
    }
}
