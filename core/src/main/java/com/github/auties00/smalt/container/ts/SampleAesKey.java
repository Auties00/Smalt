package com.github.auties00.smalt.container.ts;

import java.util.Arrays;
import java.util.Objects;

/**
 * An AES-128 key and initialization vector that decrypt the elementary streams of a transport stream encrypted in
 * the HLS {@code SAMPLE-AES} format. Instances are immutable and thread-safe.
 *
 * @see SampleAesKeys
 */
public final class SampleAesKey {
    /**
     * The length of the key and of the initialization vector, in bytes.
     */
    private static final int LENGTH = 16;

    private final byte[] key;
    private final byte[] initializationVector;

    private SampleAesKey(byte[] key, byte[] initializationVector) {
        this.key = key;
        this.initializationVector = initializationVector;
    }

    /**
     * Returns a key.
     *
     * @param key the 16-byte AES-128 key, copied
     * @param initializationVector the 16-byte initialization vector, copied: the {@code IV} attribute of the HLS
     * {@code EXT-X-KEY} tag, or the media sequence number of the segment as a 128-bit big-endian integer
     * @return the key
     * @throws NullPointerException if an argument is {@code null}
     * @throws IllegalArgumentException if the key or the initialization vector is not 16 bytes long
     */
    public static SampleAesKey of(byte[] key, byte[] initializationVector) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(initializationVector, "initializationVector");
        if (key.length != LENGTH) {
            throw new IllegalArgumentException("a SAMPLE-AES key is 16 bytes, not " + key.length);
        }
        if (initializationVector.length != LENGTH) {
            throw new IllegalArgumentException("a SAMPLE-AES initialization vector is 16 bytes, not "
                    + initializationVector.length);
        }
        return new SampleAesKey(key.clone(), initializationVector.clone());
    }

    /**
     * {@return a copy of the 16-byte AES-128 key}
     */
    public byte[] key() {
        return key.clone();
    }

    /**
     * {@return a copy of the 16-byte initialization vector}
     */
    public byte[] initializationVector() {
        return initializationVector.clone();
    }

    /**
     * Returns the key without copying it, for the decryptor.
     *
     * @return the key bytes, which the caller does not modify
     */
    byte[] keyBytes() {
        return key;
    }

    /**
     * Returns the initialization vector without copying it, for the decryptor.
     *
     * @return the initialization vector bytes, which the caller does not modify
     */
    byte[] ivBytes() {
        return initializationVector;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof SampleAesKey that && Arrays.equals(key, that.key)
                && Arrays.equals(initializationVector, that.initializationVector);
    }

    @Override
    public int hashCode() {
        return 31 * Arrays.hashCode(key) + Arrays.hashCode(initializationVector);
    }

    /**
     * {@inheritDoc}
     *
     * <p>The string does not show the key.
     */
    @Override
    public String toString() {
        return "SampleAesKey[key=<16 bytes>, initializationVector=<16 bytes>]";
    }
}
