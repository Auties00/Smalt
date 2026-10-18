package com.github.auties00.smalt.testkit;

import java.util.List;

/**
 * Builds test inputs from byte arrays.
 */
public final class Bytes {
    private Bytes() {
        throw new AssertionError();
    }

    /**
     * Returns the concatenation of byte arrays, in order.
     */
    public static byte[] concat(byte[]... parts) {
        return concat(List.of(parts));
    }

    /**
     * Returns the concatenation of byte arrays, in list order.
     */
    public static byte[] concat(List<byte[]> parts) {
        var length = 0;
        for (var part : parts) {
            length = Math.addExact(length, part.length);
        }
        var result = new byte[length];
        var offset = 0;
        for (var part : parts) {
            System.arraycopy(part, 0, result, offset, part.length);
            offset += part.length;
        }
        return result;
    }
}
