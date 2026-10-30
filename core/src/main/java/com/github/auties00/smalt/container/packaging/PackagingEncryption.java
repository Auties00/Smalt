package com.github.auties00.smalt.container.packaging;

import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;

/**
 * Holds the encryption settings of a {@link Packager}: the HLS method, the key and where players fetch it. Instances
 * are immutable and thread-safe; create them with {@link #builder()} or a preset such as
 * {@link #forSampleAes(byte[], byte[], String)}.
 *
 * <p>Only clear keys are written: an application that serves the key through a digital rights management system
 * supplies that system's key URI, key format and protection system boxes itself.
 */
public final class PackagingEncryption {
    /**
     * Enumerates the encryption methods.
     */
    public enum Method {
        /**
         * Whole media segments encrypted with AES-128 in CBC mode and PKCS#7 padding, HLS {@code METHOD=AES-128}
         * (RFC 8216 clause 4.3.2.4), for transport stream and CMAF segments; initialization segments stay clear. HLS
         * only: DASH has no such method.
         */
        AES_128,

        /**
         * The samples encrypted, HLS {@code METHOD=SAMPLE-AES}: transport stream segments in the SAMPLE-AES format of
         * {@link com.github.auties00.smalt.container.ts.TsMuxerOptions#sampleAesKey()} (H.264, AAC, AC-3 and E-AC-3),
         * CMAF segments with the ISO/IEC 23001-7 {@code cbcs} scheme, which DASH manifests declare too.
         */
        SAMPLE_AES,

        /**
         * CMAF samples encrypted with the ISO/IEC 23001-7 {@code cenc} scheme, HLS {@code METHOD=SAMPLE-AES-CTR},
         * the scheme of DASH. CMAF segments only.
         */
        SAMPLE_AES_CTR
    }

    private final Method method;
    private final byte[] keyId;
    private final byte[] key;
    private final String keyUri;

    /**
     * The initialization vector, or {@code null} for the default of the method.
     */
    private final byte[] initializationVector;

    /**
     * The {@code KEYFORMAT}, or {@code null} for none.
     */
    private final String keyFormat;

    /**
     * The name of the key file the packager writes, or {@code null} for none.
     */
    private final String keyFileName;

    private PackagingEncryption(Method method, byte[] keyId, byte[] key, String keyUri, byte[] initializationVector,
                                String keyFormat, String keyFileName) {
        this.method = method;
        this.keyId = keyId;
        this.key = key;
        this.keyUri = keyUri;
        this.initializationVector = initializationVector;
        this.keyFormat = keyFormat;
        this.keyFileName = keyFileName;
    }

    /**
     * Returns HLS AES-128 segment encryption with one key, the initialization vector of each segment its media
     * sequence number.
     *
     * @param key the 16-byte AES key, copied
     * @param keyUri the URI of the key in the playlists
     * @return the settings
     * @throws IllegalArgumentException if the key is not 16 bytes long
     */
    public static PackagingEncryption forAes128(byte[] key, String keyUri) {
        return builder().method(Method.AES_128).key(key).keyUri(keyUri).build();
    }

    /**
     * Returns SAMPLE-AES encryption with one key and a random initialization vector.
     *
     * @param keyId the 16-byte key identifier of CMAF segments and DASH manifests, copied
     * @param key the 16-byte AES key, copied
     * @param keyUri the URI of the key in the playlists
     * @return the settings
     * @throws IllegalArgumentException if the key identifier or the key is not 16 bytes long
     */
    public static PackagingEncryption forSampleAes(byte[] keyId, byte[] key, String keyUri) {
        return builder().method(Method.SAMPLE_AES).keyId(keyId).key(key).keyUri(keyUri).build();
    }

    /**
     * Returns SAMPLE-AES-CTR ({@code cenc}) encryption of CMAF segments with one key and random initialization
     * vectors.
     *
     * @param keyId the 16-byte key identifier, copied
     * @param key the 16-byte AES key, copied
     * @param keyUri the URI of the key in the playlists
     * @return the settings
     * @throws IllegalArgumentException if the key identifier or the key is not 16 bytes long
     */
    public static PackagingEncryption forSampleAesCtr(byte[] keyId, byte[] key, String keyUri) {
        return builder().method(Method.SAMPLE_AES_CTR).keyId(keyId).key(key).keyUri(keyUri).build();
    }

    /**
     * Returns a builder of the {@link Method#SAMPLE_AES} method, whose key must be set.
     *
     * @return the builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns a builder initialized with these settings.
     *
     * @return the builder
     */
    public Builder toBuilder() {
        var builder = new Builder();
        builder.method = method;
        builder.keyId = keyId;
        builder.key = key;
        builder.keyUri = keyUri;
        builder.initializationVector = initializationVector;
        builder.keyFormat = keyFormat;
        builder.keyFileName = keyFileName;
        return builder;
    }

    /**
     * {@return the encryption method; {@link Method#SAMPLE_AES} by default}
     */
    public Method method() {
        return method;
    }

    /**
     * {@return the 16-byte key identifier, a copy, or empty without one; empty by default}
     *
     * <p>CMAF segments name it in their track encryption boxes and DASH manifests in their {@code cenc:default_KID};
     * transport stream segments do not carry it.
     */
    public Optional<byte[]> keyId() {
        return Optional.ofNullable(keyId).map(byte[]::clone);
    }

    /**
     * {@return the 16-byte AES key, a copy}
     */
    public byte[] key() {
        return key.clone();
    }

    /**
     * {@return the URI of the key in the {@code EXT-X-KEY} tags of the media playlists, or empty without one; empty
     * by default}
     */
    public Optional<String> keyUri() {
        return Optional.ofNullable(keyUri);
    }

    /**
     * {@return the initialization vector, a copy, or empty for the default of the method; empty by default}
     *
     * <p>With {@link Method#AES_128} it is the initialization vector of every segment, written as the {@code IV}
     * attribute; by default each segment's is its media sequence number as a 128-bit big-endian integer and the
     * playlists carry no {@code IV}. With {@link Method#SAMPLE_AES} it is the initialization vector of every sample of
     * transport stream segments and the constant one of CMAF segments, written as the {@code IV} attribute; by
     * default a random one. With {@link Method#SAMPLE_AES_CTR} it is the 8-byte or 16-byte initialization vector of
     * each CMAF track's first sample; by default a random one.
     */
    public Optional<byte[]> initializationVector() {
        return Optional.ofNullable(initializationVector).map(byte[]::clone);
    }

    /**
     * {@return the {@code KEYFORMAT} of the {@code EXT-X-KEY} tags, or empty to write none, which players read as
     * {@code identity}; empty by default}
     */
    public Optional<String> keyFormat() {
        return Optional.ofNullable(keyFormat);
    }

    /**
     * {@return the name of a file holding the 16 bytes of the key that the packager writes through its output next
     * to the segments, or empty to write none; empty by default}
     *
     * @apiNote A key file published with the segments lets anyone who reads them decrypt them; write one only for
     * testing, or for an output whose key file the application moves behind its own access control.
     */
    public Optional<String> keyFileName() {
        return Optional.ofNullable(keyFileName);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof PackagingEncryption that && method == that.method && Arrays.equals(keyId, that.keyId)
                && Arrays.equals(key, that.key) && Objects.equals(keyUri, that.keyUri)
                && Arrays.equals(initializationVector, that.initializationVector)
                && Objects.equals(keyFormat, that.keyFormat) && Objects.equals(keyFileName, that.keyFileName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(method, Arrays.hashCode(keyId), keyUri, keyFormat, keyFileName);
    }

    /**
     * {@inheritDoc}
     *
     * <p>The key is not shown.
     */
    @Override
    public String toString() {
        return "PackagingEncryption[method=" + method + ", keyId=" + (keyId == null ? "none" : HexFormat.of()
                .formatHex(keyId)) + ", keyUri=" + keyUri + ", keyFormat=" + keyFormat + ", keyFileName="
                + keyFileName + "]";
    }

    /**
     * Assembles {@link PackagingEncryption} setting by setting.
     *
     * <p>Setters reject {@code null} and store their values unchecked; {@link #build()} checks them. Instances are not
     * thread-safe.
     */
    public static final class Builder {
        private Method method = Method.SAMPLE_AES;
        private byte[] keyId;
        private byte[] key;
        private String keyUri;
        private byte[] initializationVector;
        private String keyFormat;
        private String keyFileName;

        private Builder() {
        }

        /**
         * Sets the encryption method; see {@link PackagingEncryption#method()}.
         *
         * @param method the value
         * @return this builder
         */
        public Builder method(Method method) {
            this.method = Objects.requireNonNull(method, "method");
            return this;
        }

        /**
         * Sets the key identifier; see {@link PackagingEncryption#keyId()}.
         *
         * @param keyId the value, copied
         * @return this builder
         */
        public Builder keyId(byte[] keyId) {
            this.keyId = keyId.clone();
            return this;
        }

        /**
         * Removes the key identifier, the default; see {@link PackagingEncryption#keyId()}.
         *
         * @return this builder
         */
        public Builder noKeyId() {
            this.keyId = null;
            return this;
        }

        /**
         * Sets the key; see {@link PackagingEncryption#key()}.
         *
         * @param key the value, copied
         * @return this builder
         */
        public Builder key(byte[] key) {
            this.key = key.clone();
            return this;
        }

        /**
         * Sets the URI of the key; see {@link PackagingEncryption#keyUri()}.
         *
         * @param uri the value
         * @return this builder
         */
        public Builder keyUri(String uri) {
            this.keyUri = Objects.requireNonNull(uri, "uri");
            return this;
        }

        /**
         * Writes no key URI, the default; see {@link PackagingEncryption#keyUri()}.
         *
         * @return this builder
         */
        public Builder noKeyUri() {
            this.keyUri = null;
            return this;
        }

        /**
         * Sets the initialization vector; see {@link PackagingEncryption#initializationVector()}.
         *
         * @param vector the value, copied
         * @return this builder
         */
        public Builder initializationVector(byte[] vector) {
            this.initializationVector = vector.clone();
            return this;
        }

        /**
         * Uses the default initialization vectors of the method; see
         * {@link PackagingEncryption#initializationVector()}.
         *
         * @return this builder
         */
        public Builder automaticInitializationVector() {
            this.initializationVector = null;
            return this;
        }

        /**
         * Sets the key format; see {@link PackagingEncryption#keyFormat()}.
         *
         * @param format the value
         * @return this builder
         */
        public Builder keyFormat(String format) {
            this.keyFormat = Objects.requireNonNull(format, "format");
            return this;
        }

        /**
         * Writes no key format, the default; see {@link PackagingEncryption#keyFormat()}.
         *
         * @return this builder
         */
        public Builder noKeyFormat() {
            this.keyFormat = null;
            return this;
        }

        /**
         * Sets the name of the key file the packager writes; see {@link PackagingEncryption#keyFileName()}.
         *
         * @param name the value
         * @return this builder
         */
        public Builder keyFileName(String name) {
            this.keyFileName = Objects.requireNonNull(name, "name");
            return this;
        }

        /**
         * Writes no key file, the default; see {@link PackagingEncryption#keyFileName()}.
         *
         * @return this builder
         */
        public Builder noKeyFileName() {
            this.keyFileName = null;
            return this;
        }

        /**
         * Builds the settings.
         *
         * @return the settings
         * @throws IllegalArgumentException if the key is missing or not 16 bytes long, the key identifier is not 16
         * bytes long, the initialization vector has a size the method does not take, the key URI or key format
         * holds a double quote or a line break, or the key file name is empty or holds a path separator
         */
        public PackagingEncryption build() {
            if (key == null || key.length != 16) {
                throw new IllegalArgumentException("the key must be 16 bytes" + (key == null ? "" : ", not " + key.length));
            }
            if (keyId != null && keyId.length != 16) {
                throw new IllegalArgumentException("the key identifier must be 16 bytes, not " + keyId.length);
            }
            if (initializationVector != null) {
                var size = initializationVector.length;
                if (size != 16 && !(method == Method.SAMPLE_AES_CTR && size == 8)) {
                    throw new IllegalArgumentException("a " + method + " initialization vector cannot be " + size
                            + " bytes");
                }
            }
            for (var text : new String[]{keyUri, keyFormat}) {
                if (text != null && (text.indexOf('"') >= 0 || text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0)) {
                    throw new IllegalArgumentException("a quoted playlist attribute cannot hold \"" + text + "\"");
                }
            }
            if (keyFileName != null && (keyFileName.isEmpty() || keyFileName.indexOf('/') >= 0
                    || keyFileName.indexOf('\\') >= 0)) {
                throw new IllegalArgumentException("the key file name \"" + keyFileName
                        + "\" is empty or holds a path separator");
            }
            return new PackagingEncryption(method, keyId, key, keyUri, initializationVector, keyFormat, keyFileName);
        }
    }
}
