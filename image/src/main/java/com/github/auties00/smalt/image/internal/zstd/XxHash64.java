package com.github.auties00.smalt.image.internal.zstd;

/**
 * Computes the 64-bit xxHash (XXH64) of a byte sequence fed in pieces, with seed zero, as the
 * Zstandard content checksum uses it.
 *
 * <p>Instances are not thread-safe.
 */
final class XxHash64 {
    private static final long P1 = 0x9E3779B185EBCA87L;
    private static final long P2 = 0xC2B2AE3D27D4EB4FL;
    private static final long P3 = 0x165667B19E3779F9L;
    private static final long P4 = 0x85EBCA77C2B2AE63L;
    private static final long P5 = 0x27D4EB2F165667C5L;

    /** The four accumulators. */
    private long v1 = P1 + P2;
    private long v2 = P2;
    private long v3 = 0;
    private long v4 = -P1;

    /** The bytes not yet folded into the accumulators. */
    private final byte[] pending = new byte[32];
    private int pendingCount;

    /** The total number of bytes. */
    private long total;

    /**
     * Adds bytes.
     *
     * @param data the bytes
     * @param offset the first byte
     * @param length the number of bytes
     */
    void update(byte[] data, int offset, int length) {
        total += length;
        var end = offset + length;
        if (pendingCount > 0) {
            var take = Math.min(32 - pendingCount, length);
            System.arraycopy(data, offset, pending, pendingCount, take);
            pendingCount += take;
            offset += take;
            if (pendingCount < 32) {
                return;
            }
            stripe(pending, 0);
            pendingCount = 0;
        }
        while (end - offset >= 32) {
            stripe(data, offset);
            offset += 32;
        }
        System.arraycopy(data, offset, pending, 0, end - offset);
        pendingCount = end - offset;
    }

    /**
     * {@return the hash of the bytes added so far}
     */
    long digest() {
        long hash;
        if (total >= 32) {
            hash = Long.rotateLeft(v1, 1) + Long.rotateLeft(v2, 7) + Long.rotateLeft(v3, 12) + Long.rotateLeft(v4, 18);
            hash = merge(hash, v1);
            hash = merge(hash, v2);
            hash = merge(hash, v3);
            hash = merge(hash, v4);
        } else {
            hash = P5;
        }
        hash += total;
        var i = 0;
        for (; i + 8 <= pendingCount; i += 8) {
            hash ^= round(0, longLE(pending, i));
            hash = Long.rotateLeft(hash, 27) * P1 + P4;
        }
        if (i + 4 <= pendingCount) {
            hash ^= (intLE(pending, i) & 0xFFFFFFFFL) * P1;
            hash = Long.rotateLeft(hash, 23) * P2 + P3;
            i += 4;
        }
        for (; i < pendingCount; i++) {
            hash ^= (pending[i] & 0xFFL) * P5;
            hash = Long.rotateLeft(hash, 11) * P1;
        }
        hash ^= hash >>> 33;
        hash *= P2;
        hash ^= hash >>> 29;
        hash *= P3;
        hash ^= hash >>> 32;
        return hash;
    }

    /**
     * Folds a 32-byte stripe into the accumulators.
     *
     * @param data the bytes
     * @param offset the first byte of the stripe
     */
    private void stripe(byte[] data, int offset) {
        v1 = round(v1, longLE(data, offset));
        v2 = round(v2, longLE(data, offset + 8));
        v3 = round(v3, longLE(data, offset + 16));
        v4 = round(v4, longLE(data, offset + 24));
    }

    private static long round(long accumulator, long input) {
        accumulator += input * P2;
        accumulator = Long.rotateLeft(accumulator, 31);
        return accumulator * P1;
    }

    private static long merge(long hash, long value) {
        hash ^= round(0, value);
        return hash * P1 + P4;
    }

    private static long longLE(byte[] data, int offset) {
        return (intLE(data, offset) & 0xFFFFFFFFL) | (long) intLE(data, offset + 4) << 32;
    }

    private static int intLE(byte[] data, int offset) {
        return ZstdDecoder.intLE(data, offset);
    }
}
