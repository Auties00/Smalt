package com.github.auties00.smalt.container.mp4;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Holds the settings of the ISO/IEC 23001-7 Common Encryption a {@link FragmentedMp4Muxer} applies to the samples
 * of its audio and video tracks. Instances are immutable and thread-safe; create them with {@link #builder()} or a
 * preset such as {@link #forCenc(byte[], byte[])}.
 *
 * <p>An encrypted track's sample entry becomes {@code encv} or {@code enca} with a {@code sinf} box naming the
 * scheme, the key identifier and, for {@link Scheme#CBCS}, the constant initialization vector; each fragment carries
 * the initialization vectors and subsample maps of its samples in a {@code senc} box that {@code saiz} and
 * {@code saio} boxes locate. Video samples are encrypted by subsample where the codec's ISOBMFF binding defines
 * subsamples:
 * <ul>
 *   <li>H.264 and HEVC: NAL unit lengths, NAL unit headers, slice headers and every NAL unit other than a slice
 *       (H.264 slice data partitions included) stay clear;</li>
 *   <li>VP9: the uncompressed header of each frame and the superframe index stay clear, the compressed header and
 *       the tile data are protected;</li>
 *   <li>AV1: the tile data is protected, and every other byte (OBU headers, sequence, frame and tile group headers,
 *       tile sizes, OBUs without tiles) stays clear.</li>
 * </ul>
 * The protected part of each subsample is a whole number of 16-byte blocks, ending where the protected bytes end,
 * for the schemes other than {@link Scheme#CBCS} and for every VP9 track; under {@code CBCS} it starts right after
 * the clear bytes (the slice header, the uncompressed header, the start of a tile). A NAL unit, VP9 frame or AV1
 * sample whose headers cannot be read stays clear. The samples of the other video codecs are encrypted whole, with
 * one subsample covering the sample under {@link Scheme#CENS} and {@code CBCS} so that the pattern applies to it
 * whatever a reader assumes of a sample without a subsample map. Audio samples are encrypted whole. Text and data
 * tracks stay clear. The auxiliary information of one sample (its initialization vector and subsample map) is at
 * most 255 bytes, the size a {@code saiz} entry holds, so a video sample needing more subsamples (about 40 slices,
 * frames or tiles, a subsample having 6 bytes) is rejected with
 * {@link com.github.auties00.smalt.MediaException.Unsupported} rather than left partly clear.
 */
public final class CommonEncryption {
    /**
     * Enumerates the protection schemes of ISO/IEC 23001-7.
     */
    public enum Scheme {
        /**
         * AES-CTR over every protected byte ({@code cenc}), the scheme of DASH and of the Widevine and PlayReady
         * systems; HLS calls it {@code SAMPLE-AES-CTR}.
         */
        CENC,

        /**
         * AES-CBC over every whole protected block ({@code cbc1}).
         */
        CBC1,

        /**
         * AES-CTR over a pattern of the protected blocks of video ({@code cens}).
         */
        CENS,

        /**
         * AES-CBC over a pattern of the protected blocks of video with a constant initialization vector
         * ({@code cbcs}), the scheme of HLS {@code SAMPLE-AES} in fragmented MP4 and of FairPlay.
         */
        CBCS
    }

    /**
     * The system identifier of the W3C Common PSSH box ({@code 1077efec-c0b2-4d02-ace3-3c1e52e2fb4b}), which lists the
     * key identifiers of a file for the Clear Key system of Encrypted Media Extensions.
     */
    public static final String COMMON_SYSTEM_ID = "1077efecc0b24d02ace33c1e52e2fb4b";

    private final Scheme scheme;
    private final byte[] keyId;
    private final byte[] key;

    /**
     * The initialization vector, or {@code null} for a random one.
     */
    private final byte[] initializationVector;
    private final int cryptBlocks;
    private final int skipBlocks;
    private final List<byte[]> protectionSystems;
    private final boolean commonProtectionSystem;

    private CommonEncryption(Scheme scheme, byte[] keyId, byte[] key, byte[] initializationVector, int cryptBlocks,
                             int skipBlocks, List<byte[]> protectionSystems, boolean commonProtectionSystem) {
        this.scheme = scheme;
        this.keyId = keyId;
        this.key = key;
        this.initializationVector = initializationVector;
        this.cryptBlocks = cryptBlocks;
        this.skipBlocks = skipBlocks;
        this.protectionSystems = protectionSystems;
        this.commonProtectionSystem = commonProtectionSystem;
    }

    /**
     * Returns the {@link Scheme#CENC} encryption of one key, with random initialization vectors.
     *
     * @param keyId the 16-byte key identifier, copied
     * @param key the 16-byte AES key, copied
     * @return the settings
     * @throws IllegalArgumentException if the key identifier or the key is not 16 bytes long
     */
    public static CommonEncryption forCenc(byte[] keyId, byte[] key) {
        return builder().scheme(Scheme.CENC).keyId(keyId).key(key).build();
    }

    /**
     * Returns the {@link Scheme#CBCS} encryption of one key, with the 1:9 pattern and a random constant
     * initialization vector.
     *
     * @param keyId the 16-byte key identifier, copied
     * @param key the 16-byte AES key, copied
     * @return the settings
     * @throws IllegalArgumentException if the key identifier or the key is not 16 bytes long
     */
    public static CommonEncryption forCbcs(byte[] keyId, byte[] key) {
        return builder().scheme(Scheme.CBCS).keyId(keyId).key(key).build();
    }

    /**
     * Returns a builder of the {@link Scheme#CENC} scheme, whose key identifier and key must be set.
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
        builder.scheme = scheme;
        builder.keyId = keyId;
        builder.key = key;
        builder.initializationVector = initializationVector;
        builder.cryptBlocks = cryptBlocks;
        builder.skipBlocks = skipBlocks;
        builder.protectionSystems = protectionSystems;
        builder.commonProtectionSystem = commonProtectionSystem;
        return builder;
    }

    /**
     * {@return the protection scheme; {@link Scheme#CENC} by default}
     */
    public Scheme scheme() {
        return scheme;
    }

    /**
     * {@return the 16-byte key identifier, a copy}
     */
    public byte[] keyId() {
        return keyId.clone();
    }

    /**
     * {@return the 16-byte AES key, a copy}
     */
    public byte[] key() {
        return key.clone();
    }

    /**
     * {@return the initialization vector, a copy, or empty for a random one; empty by default}
     *
     * <p>With {@link Scheme#CBCS} it is the constant initialization vector of every sample, 16 bytes. With the other
     * schemes it is the initialization vector of each track's first sample, which the next samples count up from:
     * 8 or 16 bytes for {@link Scheme#CENC} and {@link Scheme#CENS}, whose 8-byte vectors grow by one per sample and
     * 16-byte vectors by the number of 16-byte blocks the previous sample spans, and 16 bytes for
     * {@link Scheme#CBC1}, which grows by one per sample. A random vector is 8 bytes for the counter schemes and 16
     * for the others, drawn from a strong random source for each track.
     */
    public Optional<byte[]> initializationVector() {
        return Optional.ofNullable(initializationVector).map(byte[]::clone);
    }

    /**
     * {@return the number of encrypted 16-byte blocks of the video pattern of {@link Scheme#CENS} and
     * {@link Scheme#CBCS}, from 1 to 15; 1 by default}
     *
     * <p>Within each protected range of a video sample, {@code cryptBlocks} encrypted blocks alternate with
     * {@link #skipBlocks()} clear ones. Audio is encrypted in whole blocks, without a pattern, as are the other
     * schemes.
     */
    public int cryptBlocks() {
        return cryptBlocks;
    }

    /**
     * {@return the number of clear 16-byte blocks of the video pattern, from 0 to 15; 9 by default}
     */
    public int skipBlocks() {
        return skipBlocks;
    }

    /**
     * {@return the protection system specific header boxes ({@code pssh}) written into the movie box, each a whole
     * box, copies; empty by default}
     */
    public List<byte[]> protectionSystems() {
        var copies = new ArrayList<byte[]>(protectionSystems.size());
        for (var box : protectionSystems) {
            copies.add(box.clone());
        }
        return List.copyOf(copies);
    }

    /**
     * {@return whether the movie box carries the W3C Common PSSH box of {@link #COMMON_SYSTEM_ID}, which lists the
     * key identifier; {@code true} by default}
     */
    public boolean commonProtectionSystem() {
        return commonProtectionSystem;
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof CommonEncryption that) || scheme != that.scheme || cryptBlocks != that.cryptBlocks
                || skipBlocks != that.skipBlocks || commonProtectionSystem != that.commonProtectionSystem
                || !Arrays.equals(keyId, that.keyId) || !Arrays.equals(key, that.key)
                || !Arrays.equals(initializationVector, that.initializationVector)
                || protectionSystems.size() != that.protectionSystems.size()) {
            return false;
        }
        for (var index = 0; index < protectionSystems.size(); index++) {
            if (!Arrays.equals(protectionSystems.get(index), that.protectionSystems.get(index))) {
                return false;
            }
        }
        return true;
    }

    @Override
    public int hashCode() {
        return Objects.hash(scheme, Arrays.hashCode(keyId), cryptBlocks, skipBlocks, commonProtectionSystem);
    }

    /**
     * {@inheritDoc}
     *
     * <p>The key is not shown.
     */
    @Override
    public String toString() {
        return "CommonEncryption[scheme=" + scheme + ", keyId=" + HexFormat.of().formatHex(keyId) + ", pattern="
                + cryptBlocks + ":" + skipBlocks + ", protectionSystems=" + protectionSystems.size()
                + ", commonProtectionSystem=" + commonProtectionSystem + "]";
    }

    /**
     * Assembles {@link CommonEncryption} setting by setting.
     *
     * <p>Setters reject {@code null} and store their values unchecked; {@link #build()} checks them. Instances are not
     * thread-safe.
     */
    public static final class Builder {
        private Scheme scheme = Scheme.CENC;
        private byte[] keyId;
        private byte[] key;
        private byte[] initializationVector;
        private int cryptBlocks = 1;
        private int skipBlocks = 9;
        private List<byte[]> protectionSystems = List.of();
        private boolean commonProtectionSystem = true;

        private Builder() {
        }

        /**
         * Sets the protection scheme; see {@link CommonEncryption#scheme()}.
         *
         * @param scheme the value
         * @return this builder
         */
        public Builder scheme(Scheme scheme) {
            this.scheme = Objects.requireNonNull(scheme, "scheme");
            return this;
        }

        /**
         * Sets the key identifier; see {@link CommonEncryption#keyId()}.
         *
         * @param keyId the value, copied
         * @return this builder
         */
        public Builder keyId(byte[] keyId) {
            this.keyId = keyId.clone();
            return this;
        }

        /**
         * Sets the key; see {@link CommonEncryption#key()}.
         *
         * @param key the value, copied
         * @return this builder
         */
        public Builder key(byte[] key) {
            this.key = key.clone();
            return this;
        }

        /**
         * Sets the initialization vector; see {@link CommonEncryption#initializationVector()}.
         *
         * @param vector the value, copied
         * @return this builder
         */
        public Builder initializationVector(byte[] vector) {
            this.initializationVector = vector.clone();
            return this;
        }

        /**
         * Draws a random initialization vector for each track, the default; see
         * {@link CommonEncryption#initializationVector()}.
         *
         * @return this builder
         */
        public Builder randomInitializationVector() {
            this.initializationVector = null;
            return this;
        }

        /**
         * Sets the video pattern; see {@link CommonEncryption#cryptBlocks()} and {@link CommonEncryption#skipBlocks()}.
         *
         * @param cryptBlocks the number of encrypted blocks
         * @param skipBlocks the number of clear blocks
         * @return this builder
         */
        public Builder pattern(int cryptBlocks, int skipBlocks) {
            this.cryptBlocks = cryptBlocks;
            this.skipBlocks = skipBlocks;
            return this;
        }

        /**
         * Sets the protection system specific header boxes; see {@link CommonEncryption#protectionSystems()}.
         *
         * @param boxes the {@code pssh} boxes, each copied
         * @return this builder
         */
        public Builder protectionSystems(List<byte[]> boxes) {
            var copies = new ArrayList<byte[]>(boxes.size());
            for (var box : boxes) {
                copies.add(Objects.requireNonNull(box, "box").clone());
            }
            this.protectionSystems = List.copyOf(copies);
            return this;
        }

        /**
         * Writes no protection system specific header box of the application, the default; see
         * {@link CommonEncryption#protectionSystems()}.
         *
         * @return this builder
         */
        public Builder noProtectionSystems() {
            this.protectionSystems = List.of();
            return this;
        }

        /**
         * Sets whether the W3C Common PSSH box is written; see {@link CommonEncryption#commonProtectionSystem()}.
         *
         * @param enabled the value
         * @return this builder
         */
        public Builder commonProtectionSystem(boolean enabled) {
            this.commonProtectionSystem = enabled;
            return this;
        }

        /**
         * Builds the settings.
         *
         * @return the settings
         * @throws IllegalArgumentException if the key identifier or the key is missing or not 16 bytes long, the
         * initialization vector has a size the scheme does not take, the pattern is out of range, or a protection
         * system box is not a whole {@code pssh} box
         */
        public CommonEncryption build() {
            if (keyId == null || keyId.length != 16) {
                throw new IllegalArgumentException("the key identifier must be 16 bytes"
                        + (keyId == null ? "" : ", not " + keyId.length));
            }
            if (key == null || key.length != 16) {
                throw new IllegalArgumentException("the key must be 16 bytes" + (key == null ? "" : ", not " + key.length));
            }
            if (initializationVector != null) {
                var size = initializationVector.length;
                var valid = switch (scheme) {
                    case CENC, CENS -> size == 8 || size == 16;
                    case CBC1, CBCS -> size == 16;
                };
                if (!valid) {
                    throw new IllegalArgumentException("a " + scheme + " initialization vector cannot be " + size
                            + " bytes");
                }
            }
            if (cryptBlocks < 1 || cryptBlocks > 15 || skipBlocks < 0 || skipBlocks > 15) {
                throw new IllegalArgumentException("pattern " + cryptBlocks + ":" + skipBlocks
                        + " is outside 1..15 encrypted and 0..15 clear blocks");
            }
            for (var box : protectionSystems) {
                if (box.length < 32 || ((box[0] & 0xFFL) << 24 | (box[1] & 0xFF) << 16 | (box[2] & 0xFF) << 8
                        | (box[3] & 0xFF)) != box.length || box[4] != 'p' || box[5] != 's' || box[6] != 's'
                        || box[7] != 'h') {
                    throw new IllegalArgumentException("a protection system box must be a whole pssh box");
                }
            }
            return new CommonEncryption(scheme, keyId, key, initializationVector, cryptBlocks, skipBlocks,
                    protectionSystems, commonProtectionSystem);
        }
    }
}
