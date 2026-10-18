package com.github.auties00.smalt.video.av1.internal;

/**
 * Decodes the large constant tables of the AV1 coder, stored as base-64 text (RFC 4648 alphabet,
 * most significant digit first) to keep class initializers small.
 *
 * <p>Arguments are not checked; text outside the alphabet gives unspecified values.
 */
public final class TableText {
    /**
     * Prevents instantiation.
     */
    private TableText() {
        throw new AssertionError();
    }

    /**
     * Decodes a table of unsigned values, {@code chars} digits per value.
     *
     * @param text the encoded table
     * @param chars the digits per value, from 1 to 6; with 6 digits each value keeps its low 32 bits
     * @return the values
     */
    public static int[] decode(String text, int chars) {
        var values = new int[text.length() / chars];
        for (int i = 0, p = 0; i < values.length; i++) {
            var v = 0;
            for (var k = 0; k < chars; k++) {
                v = v << 6 | digit(text.charAt(p++));
            }
            values[i] = v;
        }
        return values;
    }

    /**
     * Decodes a table of 16-bit unsigned values, {@code chars} digits per value.
     *
     * @param text the encoded table
     * @param chars the digits per value, from 1 to 3
     * @return the values
     */
    public static char[] decodeChars(String text, int chars) {
        var values = new char[text.length() / chars];
        for (int i = 0, p = 0; i < values.length; i++) {
            var v = 0;
            for (var k = 0; k < chars; k++) {
                v = v << 6 | digit(text.charAt(p++));
            }
            values[i] = (char) v;
        }
        return values;
    }

    /**
     * Returns the value of one base-64 digit.
     *
     * @param c the digit, from the RFC 4648 alphabet
     * @return the value, from 0 to 63
     */
    private static int digit(char c) {
        if (c >= 'A' && c <= 'Z') {
            return c - 'A';
        }
        if (c >= 'a' && c <= 'z') {
            return c - 'a' + 26;
        }
        if (c >= '0' && c <= '9') {
            return c - '0' + 52;
        }
        return c == '+' ? 62 : 63;
    }
}
