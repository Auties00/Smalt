/* Ports libFLAC 1.4 (src/libFLAC/md5.c, FLAC__MD5Accumulate). Copyright (C) 2000-2009 Josh Coalson, (C) 2011-2025
   Xiph.Org Foundation; BSD 3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.audio.flac;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Computes the {@code STREAMINFO} MD5 signature of FLAC samples: the MD5 of the samples interleaved, little-endian,
 * in {@code (bitsPerSample + 7) / 8} bytes each.
 *
 * <p>Instances are not thread-safe.
 */
final class FlacMd5 {
    /**
     * Stores two bytes as a little-endian {@code short}.
     */
    private static final VarHandle SHORT_LE = MethodHandles.byteArrayViewVarHandle(short[].class, ByteOrder.LITTLE_ENDIAN);

    /**
     * Stores four bytes as a little-endian {@code int}.
     */
    private static final VarHandle INT_LE = MethodHandles.byteArrayViewVarHandle(int[].class, ByteOrder.LITTLE_ENDIAN);

    private final MessageDigest md5;

    /**
     * The bytes of one block laid out for the digest, grown on demand.
     */
    private byte[] buffer;

    /**
     * Creates a digest with room for blocks of a given size without growing.
     *
     * @param bytes the initial buffer size in bytes
     */
    FlacMd5(int bytes) {
        try {
            this.md5 = MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("MD5 is not available", exception);
        }
        this.buffer = new byte[bytes];
    }

    /**
     * Adds a block of samples to the signature.
     *
     * @param planes the samples, one array per channel
     * @param channels the number of channels to add, the first of {@code planes}
     * @param frames the number of samples per channel
     * @param bitsPerSample the sample size, from 1 to 32
     */
    void update(int[][] planes, int channels, int frames, int bitsPerSample) {
        var bytes = (bitsPerSample + 7) / 8;
        var stride = channels * bytes;
        var size = frames * stride;
        if (buffer.length < size) {
            buffer = new byte[size];
        }
        var buffer = this.buffer;
        for (var c = 0; c < channels; c++) {
            var samples = planes[c];
            var position = c * bytes;
            switch (bytes) {
                case 1 -> {
                    for (var i = 0; i < frames; i++, position += stride) {
                        buffer[position] = (byte) samples[i];
                    }
                }
                case 2 -> {
                    for (var i = 0; i < frames; i++, position += stride) {
                        SHORT_LE.set(buffer, position, (short) samples[i]);
                    }
                }
                case 3 -> {
                    for (var i = 0; i < frames; i++, position += stride) {
                        var value = samples[i];
                        buffer[position] = (byte) value;
                        buffer[position + 1] = (byte) (value >> 8);
                        buffer[position + 2] = (byte) (value >> 16);
                    }
                }
                default -> {
                    for (var i = 0; i < frames; i++, position += stride) {
                        INT_LE.set(buffer, position, samples[i]);
                    }
                }
            }
        }
        md5.update(buffer, 0, size);
    }

    /**
     * Completes the signature and resets the digest.
     *
     * @return the 16-byte signature
     */
    byte[] digest() {
        return md5.digest();
    }
}
