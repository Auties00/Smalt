package com.github.auties00.smalt.internal.io;

import java.nio.charset.StandardCharsets;

/**
 * Converts four-character codes (box types, chunk identifiers, codec tags) between their text and
 * their big-endian 32-bit integer form.
 *
 * <p>The integer form puts the first character in the most significant byte, as a big-endian
 * read of the four bytes gives it.
 */
public final class FourCc {
    /**
     * Prevents instantiation.
     */
    private FourCc() {
        throw new AssertionError();
    }

    /**
     * Returns the integer form of a four-character code.
     *
     * <p>Each character contributes its low 8 bits, so ISO-8859-1 text round-trips through
     * {@link #toString(int)}.
     *
     * @param code the four characters
     * @return the big-endian integer
     * @throws IllegalArgumentException if {@code code} is not four characters long
     */
    public static int of(String code) {
        if (code.length() != 4) {
            throw new IllegalArgumentException("not a four-character code: " + code);
        }
        return (code.charAt(0) & 0xFF) << 24 | (code.charAt(1) & 0xFF) << 16
                | (code.charAt(2) & 0xFF) << 8 | (code.charAt(3) & 0xFF);
    }

    /**
     * Returns the four characters of a code, each byte as one ISO-8859-1 character.
     *
     * @param code the big-endian integer
     * @return the four characters
     */
    public static String toString(int code) {
        var bytes = new byte[]{(byte) (code >>> 24), (byte) (code >>> 16), (byte) (code >>> 8), (byte) code};
        return new String(bytes, StandardCharsets.ISO_8859_1);
    }

    /**
     * Returns the printable form of a code for diagnostics.
     *
     * @param code the big-endian integer
     * @return the four characters, with each byte outside printable ASCII ({@code 0x20} to
     *         {@code 0x7E}) written as {@code '?'}
     */
    public static String printable(int code) {
        var chars = new char[4];
        for (var index = 0; index < 4; index++) {
            var value = (code >>> (24 - 8 * index)) & 0xFF;
            chars[index] = value >= 0x20 && value < 0x7F ? (char) value : '?';
        }
        return new String(chars);
    }
}
