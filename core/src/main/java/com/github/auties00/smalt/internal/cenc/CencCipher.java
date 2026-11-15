/* Ports Shaka Packager v3.9.3 (packager/media/base/aes_encryptor.cc, aes_pattern_cryptor.cc, the counter
 * increment and the partial pattern rule). Copyright 2014 Google LLC; BSD 3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.internal.cenc;

import com.github.auties00.smalt.MediaException;

import javax.crypto.Cipher;
import javax.crypto.ShortBufferException;
import javax.crypto.spec.SecretKeySpec;
import java.security.GeneralSecurityException;
import java.util.Arrays;

/**
 * Encrypts and decrypts samples in place with one AES-128 key, as ISO/IEC 23001-7 clauses 9 and 10 define for the
 * {@link CencScheme} schemes.
 *
 * <p>A sample is either protected whole or divided into subsamples, each a run of clear bytes followed by a run of
 * protected bytes. The rules of each scheme:
 * <ul>
 *   <li>{@code cenc}: AES-CTR over the protected bytes taken together, partial blocks included;</li>
 *   <li>{@code cbc1}: AES-CBC over the whole blocks of each protected run, the chain continuing from one run to the
 *       next, the bytes after the last whole block of a run left clear;</li>
 *   <li>{@code cens}: AES-CTR over the blocks a pattern of {@code crypt} encrypted blocks followed by {@code skip}
 *       clear blocks selects in each protected run, the key stream continuing over the encrypted blocks only;</li>
 *   <li>{@code cbcs}: AES-CBC over the blocks the pattern selects, the chain restarting from the initialization vector
 *       at each protected run.</li>
 * </ul>
 * A pattern of 0:0 encrypts every whole block; the bytes after the last whole block of a run stay clear whatever
 * the pattern. The counter block of AES-CTR is the initialization vector, an 8-byte one followed by eight zero bytes,
 * whose last eight bytes count the blocks as an unsigned 64-bit big-endian number that wraps without carrying into the
 * first eight. An 8-byte initialization vector of AES-CBC is followed by eight zero bytes. Instances are not
 * thread-safe.
 */
public final class CencCipher {
    /**
     * The AES block size in bytes.
     */
    private static final int BLOCK = 16;

    /**
     * The flag of a span that starts a new cipher block chain.
     */
    private static final int RESTART = 1;

    private final Cipher encryptor;
    private final Cipher decryptor;

    /**
     * The encrypted spans of the sample being transformed, as triples of start, length and flags.
     */
    private int[] spans = new int[48];

    /**
     * The number of spans.
     */
    private int spanCount;

    /**
     * The key stream of AES-CTR, or the ciphertext copy of AES-CBC decryption.
     */
    private byte[] stream = new byte[1024];

    private final byte[] counter = new byte[BLOCK];
    private final byte[] chain = new byte[BLOCK];
    private final byte[] block = new byte[BLOCK];

    /**
     * Constructs a cipher over a key.
     *
     * @param key the AES-128 key, 16 bytes, copied
     * @throws IllegalArgumentException if the key is not 16 bytes long
     */
    public CencCipher(byte[] key) {
        if (key.length != BLOCK) {
            throw new IllegalArgumentException("an AES-128 key is 16 bytes, not " + key.length);
        }
        try {
            var spec = new SecretKeySpec(key.clone(), "AES");
            encryptor = Cipher.getInstance("AES/ECB/NoPadding");
            encryptor.init(Cipher.ENCRYPT_MODE, spec);
            decryptor = Cipher.getInstance("AES/ECB/NoPadding");
            decryptor.init(Cipher.DECRYPT_MODE, spec);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("AES is not available", exception);
        }
    }

    /**
     * Decrypts a sample in place.
     *
     * @param scheme the protection scheme
     * @param data the array holding the sample
     * @param offset the index of the first byte of the sample
     * @param length the sample size in bytes
     * @param iv the array holding the initialization vector
     * @param ivOffset the index of its first byte
     * @param ivLength its length, 8 or 16 bytes
     * @param subsamples the clear and protected byte counts of each subsample, in pairs, or {@code null} when the
     * whole sample is protected
     * @param subsampleOffset the index of the first pair's clear count
     * @param subsampleCount the number of pairs; zero means the whole sample is protected
     * @param crypt the number of encrypted blocks of the pattern, from 0 to 15
     * @param skip the number of clear blocks of the pattern, from 0 to 15
     * @throws MediaException.Malformed if the subsamples do not cover the sample exactly
     */
    public void decrypt(CencScheme scheme, byte[] data, int offset, int length, byte[] iv, int ivOffset, int ivLength,
                        int[] subsamples, int subsampleOffset, int subsampleCount, int crypt, int skip) {
        transform(false, scheme, data, offset, length, iv, ivOffset, ivLength, subsamples, subsampleOffset,
                subsampleCount, crypt, skip);
    }

    /**
     * Encrypts a sample in place; the arguments are those of {@link #decrypt}.
     *
     * @param scheme the protection scheme
     * @param data the array holding the sample
     * @param offset the index of the first byte of the sample
     * @param length the sample size in bytes
     * @param iv the array holding the initialization vector
     * @param ivOffset the index of its first byte
     * @param ivLength its length, 8 or 16 bytes
     * @param subsamples the clear and protected byte counts of each subsample, in pairs, or {@code null} when the
     * whole sample is protected
     * @param subsampleOffset the index of the first pair's clear count
     * @param subsampleCount the number of pairs; zero means the whole sample is protected
     * @param crypt the number of encrypted blocks of the pattern, from 0 to 15
     * @param skip the number of clear blocks of the pattern, from 0 to 15
     * @throws MediaException.Malformed if the subsamples do not cover the sample exactly
     */
    public void encrypt(CencScheme scheme, byte[] data, int offset, int length, byte[] iv, int ivOffset, int ivLength,
                        int[] subsamples, int subsampleOffset, int subsampleCount, int crypt, int skip) {
        transform(true, scheme, data, offset, length, iv, ivOffset, ivLength, subsamples, subsampleOffset,
                subsampleCount, crypt, skip);
    }

    /**
     * Restarts the AES-CBC chain that {@link #cbcChained(boolean, byte[], int, int)} continues.
     *
     * @param iv the 16-byte initialization vector the chain starts from
     */
    public void restartChain(byte[] iv) {
        System.arraycopy(iv, 0, chain, 0, BLOCK);
    }

    /**
     * Encrypts or decrypts whole AES-CBC blocks in place, continuing the chain of the previous call (or the one
     * {@link #restartChain(byte[])} started), with no padding: the bytes after the last whole block are left clear
     * and do not enter the chain.
     *
     * @param encrypt {@code true} to encrypt
     * @param data the array holding the bytes
     * @param offset the index of the first byte
     * @param length the number of bytes
     */
    public void cbcChained(boolean encrypt, byte[] data, int offset, int length) {
        var whole = length & -BLOCK;
        if (encrypt) {
            cbcEncrypt(data, offset, whole);
        } else {
            cbcDecrypt(data, offset, whole);
        }
    }

    /**
     * Transforms a sample in place.
     */
    private void transform(boolean encrypt, CencScheme scheme, byte[] data, int offset, int length, byte[] iv,
                           int ivOffset, int ivLength, int[] subsamples, int subsampleOffset, int subsampleCount,
                           int crypt, int skip) {
        if (ivLength != 8 && ivLength != BLOCK) {
            throw new MediaException.Malformed("initialization vector of " + ivLength + " bytes");
        }
        if (crypt == 0 && skip == 0) {
            crypt = 1;
        }
        spanCount = 0;
        if (subsamples == null || subsampleCount == 0) {
            addProtected(scheme, offset, length, crypt, skip);
        } else {
            var position = (long) offset;
            var end = (long) offset + length;
            for (var index = 0; index < subsampleCount; index++) {
                long clear = subsamples[subsampleOffset + 2 * index];
                var protectedBytes = Integer.toUnsignedLong(subsamples[subsampleOffset + 2 * index + 1]);
                if (clear < 0 || position + clear + protectedBytes > end) {
                    throw new MediaException.Malformed("the subsamples of a protected sample cover more than its "
                            + length + " bytes");
                }
                position += clear;
                addProtected(scheme, (int) position, (int) protectedBytes, crypt, skip);
                position += protectedBytes;
            }
            if (position != end) {
                throw new MediaException.Malformed("the subsamples of a protected sample cover " + (position - offset)
                        + " of its " + length + " bytes");
            }
        }
        if (spanCount == 0) {
            return;
        }
        Arrays.fill(chain, (byte) 0);
        System.arraycopy(iv, ivOffset, chain, 0, ivLength);
        if (scheme.isCbc()) {
            System.arraycopy(chain, 0, counter, 0, BLOCK);
            for (var span = 0; span < spanCount; span++) {
                if ((spans[3 * span + 2] & RESTART) != 0) {
                    System.arraycopy(counter, 0, chain, 0, BLOCK);
                }
                if (encrypt) {
                    cbcEncrypt(data, spans[3 * span], spans[3 * span + 1]);
                } else {
                    cbcDecrypt(data, spans[3 * span], spans[3 * span + 1]);
                }
            }
        } else {
            ctr(data);
        }
    }

    /**
     * Adds the encrypted spans of one protected run.
     */
    private void addProtected(CencScheme scheme, int start, int length, int crypt, int skip) {
        if (length <= 0) {
            return;
        }
        switch (scheme) {
            case CENC -> addSpan(start, length, 0);
            case CBC1 -> {
                if (length >= BLOCK) {
                    addSpan(start, length & -BLOCK, 0);
                }
            }
            case CENS, CBCS -> {
                var flags = scheme == CencScheme.CBCS ? RESTART : 0;
                if (skip == 0) {
                    if (length >= BLOCK) {
                        addSpan(start, length & -BLOCK, flags);
                    }
                    return;
                }
                var position = start;
                var remaining = length;
                var cryptBytes = crypt * BLOCK;
                var skipBytes = skip * BLOCK;
                while (remaining >= BLOCK) {
                    var encrypted = Math.min(cryptBytes, remaining & -BLOCK);
                    addSpan(position, encrypted, flags);
                    flags = 0;
                    position += encrypted;
                    remaining -= encrypted;
                    var skipped = Math.min(skipBytes, remaining);
                    position += skipped;
                    remaining -= skipped;
                }
            }
        }
    }

    /**
     * Appends one encrypted span.
     */
    private void addSpan(int start, int length, int flags) {
        if (3 * spanCount + 3 > spans.length) {
            spans = Arrays.copyOf(spans, spans.length * 2);
        }
        spans[3 * spanCount] = start;
        spans[3 * spanCount + 1] = length;
        spans[3 * spanCount + 2] = flags;
        spanCount++;
    }

    /**
     * Applies the AES-CTR key stream that starts at {@link #chain} to the spans.
     */
    private void ctr(byte[] data) {
        var total = 0L;
        for (var span = 0; span < spanCount; span++) {
            total += spans[3 * span + 1];
        }
        if (total > Integer.MAX_VALUE - BLOCK) {
            throw new MediaException.Malformed("protected sample of " + total + " bytes");
        }
        var streamLength = (int) ((total + BLOCK - 1) & -BLOCK);
        if (stream.length < streamLength) {
            stream = new byte[Math.max(streamLength, stream.length * 2)];
        }
        System.arraycopy(chain, 0, counter, 0, BLOCK);
        for (var position = 0; position < streamLength; position += BLOCK) {
            System.arraycopy(counter, 0, stream, position, BLOCK);
            for (var index = BLOCK - 1; index >= 8; index--) {
                if (++counter[index] != 0) {
                    break;
                }
            }
        }
        ecb(encryptor, stream, 0, streamLength, stream, 0);
        var used = 0;
        for (var span = 0; span < spanCount; span++) {
            var start = spans[3 * span];
            var length = spans[3 * span + 1];
            for (var index = 0; index < length; index++) {
                data[start + index] ^= stream[used + index];
            }
            used += length;
        }
    }

    /**
     * Encrypts whole blocks with AES-CBC, continuing the chain in {@link #chain}.
     */
    private void cbcEncrypt(byte[] data, int start, int length) {
        for (var position = start; position < start + length; position += BLOCK) {
            for (var index = 0; index < BLOCK; index++) {
                block[index] = (byte) (data[position + index] ^ chain[index]);
            }
            ecb(encryptor, block, 0, BLOCK, data, position);
            System.arraycopy(data, position, chain, 0, BLOCK);
        }
    }

    /**
     * Decrypts whole blocks with AES-CBC, continuing the chain in {@link #chain}.
     */
    private void cbcDecrypt(byte[] data, int start, int length) {
        if (length == 0) {
            return;
        }
        if (stream.length < length) {
            stream = new byte[Math.max(length, stream.length * 2)];
        }
        System.arraycopy(data, start, stream, 0, length);
        ecb(decryptor, stream, 0, length, data, start);
        for (var index = 0; index < BLOCK; index++) {
            data[start + index] ^= chain[index];
        }
        for (var index = BLOCK; index < length; index++) {
            data[start + index] ^= stream[index - BLOCK];
        }
        System.arraycopy(stream, length - BLOCK, chain, 0, BLOCK);
    }

    /**
     * Runs AES over whole blocks.
     */
    private static void ecb(Cipher cipher, byte[] input, int inputOffset, int length, byte[] output, int outputOffset) {
        try {
            cipher.update(input, inputOffset, length, output, outputOffset);
        } catch (ShortBufferException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
