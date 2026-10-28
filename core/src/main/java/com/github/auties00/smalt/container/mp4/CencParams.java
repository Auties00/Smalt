package com.github.auties00.smalt.container.mp4;

import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * Describes how a group of samples is protected: the defaults of a track encryption box ({@code tenc}) or one entry
 * of a sample encryption information group ({@code seig}), ISO/IEC 23001-7 clauses 8.2 and 6.
 *
 * <p>A multi-key entry (ISO/IEC 23001-7:2023) lists several keys, each with its own initialization vector size or
 * constant initialization vector; the subsamples of its samples name the key that protects them, by its 1-based
 * index in {@code keys}, and the single-key fields then describe the first key.
 *
 * @param isProtected whether the samples are encrypted
 * @param ivSize the size of the per-sample initialization vectors, 0, 8 or 16 bytes; 0 means the samples use
 * {@code constantIv}
 * @param keyId the 16-byte key identifier
 * @param constantIv the initialization vector shared by every sample, 8 or 16 bytes, or {@code null} when the
 * samples carry their own
 * @param crypt the number of encrypted blocks of the pattern, from 0 to 15
 * @param skip the number of clear blocks of the pattern, from 0 to 15
 * @param keys the keys of a multi-key entry, each a single-key description, or {@code null} for a single key
 */
record CencParams(boolean isProtected, int ivSize, byte[] keyId, byte[] constantIv, int crypt, int skip,
                  List<CencParams> keys) {
    /**
     * {@return whether the samples are protected with several keys}
     */
    boolean multiKey() {
        return keys != null;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof CencParams that && isProtected == that.isProtected && ivSize == that.ivSize
                && crypt == that.crypt && skip == that.skip && Arrays.equals(keyId, that.keyId)
                && Arrays.equals(constantIv, that.constantIv) && Objects.equals(keys, that.keys);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(keyId) * 31 + ivSize;
    }

    @Override
    public String toString() {
        return "CencParams[protected=" + isProtected + ", ivSize=" + ivSize + ", keyId="
                + HexFormat.of().formatHex(keyId) + ", pattern=" + crypt + ":" + skip
                + (keys != null ? ", keys=" + keys.size() : "") + "]";
    }
}
