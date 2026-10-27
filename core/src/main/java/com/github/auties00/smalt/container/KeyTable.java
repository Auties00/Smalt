package com.github.auties00.smalt.container;

import java.util.Arrays;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;

/**
 * Implements the {@link DecryptionKeys} of the static factories: a table of keys by hexadecimal key identifier,
 * and an optional key for every other identifier. Instances are immutable and thread-safe.
 *
 * @param keys the keys, by lower-case hexadecimal key identifier; never modified
 * @param fallback the key of every other identifier, or {@code null}
 */
record KeyTable(Map<String, byte[]> keys, byte[] fallback) implements DecryptionKeys {
    /**
     * The keys of {@link DecryptionKeys#none()}.
     */
    static final KeyTable NONE = new KeyTable(Map.of(), null);

    @Override
    public Optional<byte[]> find(byte[] keyId) {
        var key = keyId.length == 16 ? keys.get(HexFormat.of().formatHex(keyId)) : null;
        if (key == null) {
            key = fallback;
        }
        return key == null ? Optional.empty() : Optional.of(key.clone());
    }

    /**
     * Checks that a key or key identifier is 16 bytes long.
     *
     * @param bytes the key or key identifier
     * @param what the name of the value, for the message
     * @return {@code bytes}
     * @throws IllegalArgumentException if it is not 16 bytes long
     */
    static byte[] check(byte[] bytes, String what) {
        if (bytes.length != 16) {
            throw new IllegalArgumentException("a " + what + " is 16 bytes, not " + bytes.length);
        }
        return bytes;
    }

    /**
     * Returns the table name of a key identifier.
     *
     * @param keyId the key identifier
     * @param what the name of the value, for the message
     * @return its lower-case hexadecimal form
     * @throws IllegalArgumentException if it is not 16 bytes long
     */
    static String name(byte[] keyId, String what) {
        return HexFormat.of().formatHex(check(keyId, what));
    }

    /**
     * Parses a hexadecimal key or key identifier.
     *
     * @param text the 32 hexadecimal digits
     * @param what the name of the value, for the message
     * @return the 16 bytes
     * @throws IllegalArgumentException if the text is not 32 hexadecimal digits
     */
    static byte[] parse(String text, String what) {
        if (text.length() != 32) {
            throw new IllegalArgumentException("a " + what + " is 32 hexadecimal digits, not \"" + text + "\"");
        }
        try {
            return HexFormat.of().parseHex(text);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("a " + what + " is 32 hexadecimal digits, not \"" + text + "\"",
                    exception);
        }
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof KeyTable that) || !keys.keySet().equals(that.keys.keySet())
                || !Arrays.equals(fallback, that.fallback)) {
            return false;
        }
        for (var entry : keys.entrySet()) {
            if (!Arrays.equals(entry.getValue(), that.keys.get(entry.getKey()))) {
                return false;
            }
        }
        return true;
    }

    @Override
    public int hashCode() {
        return keys.keySet().hashCode() * 31 + Arrays.hashCode(fallback);
    }

    @Override
    public String toString() {
        return "DecryptionKeys[" + keys.size() + " keys" + (fallback != null ? ", a key for any key ID" : "") + "]";
    }
}
