package com.github.auties00.smalt.container.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.io.DataReference;
import com.github.auties00.smalt.io.DataReferenceResolver;
import com.github.auties00.smalt.io.MediaSource;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Presents the segments of an HLS media playlist or a DASH representation (an initialization section followed by
 * media segments, each a file or a byte range of one) as one source, the files opened through a resolver and the
 * segments encrypted with HLS {@code AES-128} decrypted.
 *
 * <p>Every file is resolved once when the source is built, to learn its size, and again when it is read after the
 * {@value #OPEN_FILES} files read most recently, which stay open until {@link #close()}. This class is
 * thread-safe.
 */
public final class SegmentSource implements MediaSource {
    /**
     * One part of the source.
     *
     * @param uri the location of the file, relative to the manifest or absolute
     * @param offset the offset of the part in the file
     * @param length the length of the part, or -1 for the rest of the file
     * @param key the AES-128 key of a part encrypted whole in CBC mode with PKCS#7 padding, or {@code null}
     * @param iv the initialization vector of an encrypted part, or {@code null}
     */
    public record Part(String uri, long offset, long length, byte[] key, byte[] iv) {
        /**
         * Validates the part.
         *
         * @throws IllegalArgumentException if the offset is negative, the length below -1, or a key or an
         * initialization vector is given without the other or is not 16 bytes
         */
        public Part {
            Objects.requireNonNull(uri, "uri");
            if (offset < 0 || length < -1) {
                throw new IllegalArgumentException("byte range " + length + "@" + offset);
            }
            if ((key == null) != (iv == null) || key != null && (key.length != 16 || iv.length != 16)) {
                throw new IllegalArgumentException("an AES-128 part needs a 16-byte key and initialization vector");
            }
        }

        /**
         * {@return a clear part of a whole file}
         *
         * @param uri the location of the file
         */
        public static Part of(String uri) {
            return new Part(uri, 0, -1, null, null);
        }
    }

    /**
     * The number of files kept open.
     */
    private static final int OPEN_FILES = 8;

    private final List<Part> parts;
    private final DataReferenceResolver resolver;
    private final MediaLimits limits;

    /**
     * The offset of each part in this source, followed by the total size.
     */
    private final long[] starts;

    /**
     * The files open, most recently read last.
     */
    private final LinkedHashMap<String, MediaSource> open = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, MediaSource> eldest) {
            if (size() > OPEN_FILES) {
                eldest.getValue().close();
                return true;
            }
            return false;
        }
    };

    /**
     * The index of the encrypted part decrypted last, or -1.
     */
    private int decryptedPart = -1;

    /**
     * The plaintext of that part.
     */
    private byte[] decrypted;

    /**
     * Whether the source was closed.
     */
    private boolean closed;

    /**
     * Builds the source, resolving every file to learn the sizes.
     *
     * @param parts the parts, in order
     * @param resolver opens the files
     * @param limits bounds the decrypted part held in memory
     * @throws MediaException.Unsupported if a file cannot be resolved
     * @throws MediaException.Malformed if a byte range lies outside its file, or an encrypted part is not whole
     * blocks or its padding is invalid
     * @throws MediaException.Io if reading a file fails
     */
    public SegmentSource(List<Part> parts, DataReferenceResolver resolver, MediaLimits limits) {
        this.parts = List.copyOf(parts);
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.limits = Objects.requireNonNull(limits, "limits");
        this.starts = new long[this.parts.size() + 1];
        try {
            for (var index = 0; index < this.parts.size(); index++) {
                starts[index + 1] = Math.addExact(starts[index], sizeOf(this.parts.get(index)));
            }
        } catch (RuntimeException exception) {
            close();
            throw exception;
        }
    }

    /**
     * Returns the size of a part in this source.
     *
     * @param part the part to measure
     * @return the size, after decryption
     */
    private long sizeOf(Part part) {
        var file = file(part.uri());
        var fileSize = file.size();
        var length = part.length() >= 0 ? part.length() : fileSize >= 0 ? fileSize - part.offset() : -1;
        if (length < 0 || fileSize >= 0 && part.offset() + length > fileSize) {
            throw new MediaException.Malformed("the byte range " + part.length() + "@" + part.offset() + " of "
                    + part.uri() + " lies outside the file");
        }
        if (part.key() == null) {
            return length;
        }
        if (length == 0 || length % 16 != 0) {
            throw new MediaException.Malformed("the AES-128 segment " + part.uri() + " is not whole blocks");
        }
        var tail = new byte[length >= 32 ? 32 : 16];
        file.readFully(part.offset() + length - tail.length, tail, 0, tail.length);
        var iv = tail.length == 32 ? Arrays.copyOfRange(tail, 0, 16) : part.iv();
        var last = cipher(part.key(), iv, false, tail, tail.length - 16, 16);
        var pad = last[15] & 0xFF;
        if (pad < 1 || pad > 16) {
            throw new MediaException.Malformed("the AES-128 segment " + part.uri() + " has invalid padding");
        }
        return length - pad;
    }

    /**
     * Returns a file, resolving it when it is not open.
     *
     * @param uri the location of the file
     * @return the file
     * @throws MediaException.Unsupported if the resolver cannot supply it
     */
    private synchronized MediaSource file(String uri) {
        if (closed) {
            throw new IllegalStateException("the segment source is closed");
        }
        var file = open.get(uri);
        if (file == null) {
            file = resolver.resolve(new DataReference(DataReference.Kind.URL, uri, "")).orElseThrow(
                    () -> new MediaException.Unsupported("segment", "the segment file " + uri + " cannot be"
                            + " resolved: give the demuxer a DataReferenceResolver (DemuxerOptions.dataReferences)"));
            open.put(uri, file);
        }
        return file;
    }

    @Override
    public long size() {
        return starts[parts.size()];
    }

    @Override
    public synchronized int read(long position, byte[] buffer, int offset, int length) {
        Objects.checkFromIndexSize(offset, length, buffer.length);
        if (position < 0) {
            throw new IndexOutOfBoundsException("position " + position);
        }
        if (length == 0) {
            return 0;
        }
        if (position >= size()) {
            return -1;
        }
        var index = Arrays.binarySearch(starts, position);
        if (index < 0) {
            index = -index - 2;
        }
        while (starts[index + 1] == starts[index]) {
            index++;
        }
        var part = parts.get(index);
        var within = position - starts[index];
        var take = (int) Math.min(length, starts[index + 1] - position);
        if (part.key() != null) {
            var plain = decrypt(index);
            System.arraycopy(plain, (int) within, buffer, offset, take);
            return take;
        }
        var file = file(part.uri());
        var read = file.read(part.offset() + within, buffer, offset, take);
        if (read < 0) {
            throw new MediaException.Malformed("the segment file " + part.uri() + " ended before its byte range");
        }
        return read;
    }

    /**
     * Returns the plaintext of an encrypted part.
     *
     * @param index the index of the part
     * @return the plaintext
     */
    private byte[] decrypt(int index) {
        if (decryptedPart == index) {
            return decrypted;
        }
        var part = parts.get(index);
        var plainSize = starts[index + 1] - starts[index];
        var cipherSize = (plainSize / 16 + 1) * 16;
        LimitChecks.checkAllocation(limits, cipherSize);
        var data = new byte[(int) cipherSize];
        file(part.uri()).readFully(part.offset(), data, 0, data.length);
        decrypted = cipher(part.key(), part.iv(), true, data, 0, data.length);
        decryptedPart = index;
        return decrypted;
    }

    /**
     * Decrypts AES-128 in CBC mode.
     *
     * @param key the 16-byte AES key
     * @param iv the initialization vector
     * @param padded whether the data ends with PKCS#7 padding to remove
     * @param data the ciphertext
     * @param offset the index of the first byte
     * @param length the number of bytes, whole blocks
     * @return the plaintext
     * @throws MediaException.Malformed if the padding is invalid
     */
    private static byte[] cipher(byte[] key, byte[] iv, boolean padded, byte[] data, int offset, int length) {
        try {
            var cipher = Cipher.getInstance(padded ? "AES/CBC/PKCS5Padding" : "AES/CBC/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
            return cipher.doFinal(data, offset, length);
        } catch (javax.crypto.BadPaddingException exception) {
            throw new MediaException.Malformed("an AES-128 segment has invalid padding", exception);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("AES is not available", exception);
        }
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        for (var file : open.values()) {
            file.close();
        }
        open.clear();
        decrypted = null;
    }
}
