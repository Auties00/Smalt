package com.github.auties00.smalt.container;

import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Supplies the content keys that decrypt the protected tracks of a media file: the ISO/IEC 23001-7 Common
 * Encryption schemes of MP4 files ({@code cenc}, {@code cbc1}, {@code cens} and {@code cbcs}) and the WebM
 * encryption of Matroska files. Each key is an AES-128 key, found by the key identifier the file names for a track
 * or for a group of its samples: the 16-byte {@code KID} of a {@code tenc} box or a {@code seig} sample group, or
 * the {@code ContentEncKeyID} of a Matroska track, which may have another length (WebM packagers write 16 bytes).
 * The keys of {@link #of(byte[], byte[])} and {@link #ofHex(Map)} answer 16-byte identifiers only, and
 * {@link #single(byte[])} answers every identifier.
 *
 * <p>A demuxer asks for each key identifier once, the first time a sample needs it, and may be called from the
 * thread that reads the file only; a resolver shared by several demuxers may be called from several threads at
 * once. Only clear keys are supported: the protection system specific data of a file ({@code pssh} boxes) is not
 * interpreted, so a key held by a digital rights management system must be obtained by the application.
 */
@FunctionalInterface
public interface DecryptionKeys {
    /**
     * Returns the key of a key identifier.
     *
     * @param keyId the key identifier, 16 bytes for MP4 files, which the caller may modify after the call
     * @return the 16-byte AES key, or an empty {@code Optional} when the key is not known
     */
    Optional<byte[]> find(byte[] keyId);

    /**
     * Returns the keys that know no key, the default of {@link DemuxerOptions}: the demuxers then reject protected
     * tracks as unsupported.
     *
     * @return the keys
     */
    static DecryptionKeys none() {
        return KeyTable.NONE;
    }

    /**
     * Returns one key, for one key identifier.
     *
     * @param keyId the 16-byte key identifier, copied
     * @param key the 16-byte AES key, copied
     * @return the keys
     * @throws IllegalArgumentException if the key identifier or the key is not 16 bytes long
     */
    static DecryptionKeys of(byte[] keyId, byte[] key) {
        var table = new LinkedHashMap<String, byte[]>();
        table.put(KeyTable.name(keyId, "key identifier"), KeyTable.check(key, "key").clone());
        return new KeyTable(table, null);
    }

    /**
     * Returns keys given as hexadecimal strings, each key identifier mapped to its key, as packaging tools print
     * them (for example {@code 30313233343536373839303132333435} to {@code ebdd62f16814d27b68ef122afce4ae3c}).
     *
     * @param keys the keys by key identifier, 32 hexadecimal digits each, upper or lower case
     * @return the keys
     * @throws IllegalArgumentException if a key identifier or a key is not 32 hexadecimal digits
     */
    static DecryptionKeys ofHex(Map<String, String> keys) {
        var table = new LinkedHashMap<String, byte[]>();
        for (var entry : keys.entrySet()) {
            var keyId = KeyTable.parse(Objects.requireNonNull(entry.getKey(), "keyId"), "key identifier");
            var key = KeyTable.parse(Objects.requireNonNull(entry.getValue(), "key"), "key");
            table.put(HexFormat.of().formatHex(keyId), key);
        }
        return new KeyTable(table, null);
    }

    /**
     * Returns one key for every key identifier, for files protected with a single key whose identifier the
     * application does not know.
     *
     * @param key the 16-byte AES key, copied
     * @return the keys
     * @throws IllegalArgumentException if the key is not 16 bytes long
     */
    static DecryptionKeys single(byte[] key) {
        return new KeyTable(Map.of(), KeyTable.check(key, "key").clone());
    }
}
