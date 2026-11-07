package com.github.auties00.smalt.container.ts;

import java.util.Objects;
import java.util.Optional;

/**
 * Supplies the keys that decrypt a transport stream whose elementary streams are encrypted in the HLS
 * {@code SAMPLE-AES} format, by position in the stream: the segments of an HLS media playlist read as one stream may
 * each have their own key and initialization vector.
 *
 * <p>The {@link TsDemuxer} asks for the key of each encrypted unit (an H.264 slice, an ADTS frame, an AC-3 syncframe
 * or an E-AC-3 frame) with the offset of the transport packet that starts the PES packet in which the unit starts,
 * from the thread that reads the stream only. An implementation answers quickly: it is called once per unit.
 */
@FunctionalInterface
public interface SampleAesKeys {
    /**
     * Returns the key of the PES packet whose first transport packet starts at an offset.
     *
     * @param offset the offset of that transport packet from the start of the source, in bytes
     * @return the key, or an empty {@code Optional} when no key is known for that part of the stream
     */
    Optional<SampleAesKey> find(long offset);

    /**
     * Returns the keys that know no key, the default of {@link TsDemuxerOptions}: the demuxer then rejects the
     * encrypted streams as unsupported.
     *
     * @return the keys
     */
    static SampleAesKeys none() {
        return SampleAesKeyTable.NONE;
    }

    /**
     * Returns one key for the whole stream.
     *
     * @param key the key and initialization vector of every encrypted unit
     * @return the keys
     * @throws NullPointerException if {@code key} is {@code null}
     */
    static SampleAesKeys of(SampleAesKey key) {
        Objects.requireNonNull(key, "key");
        return new SampleAesKeyTable(new long[]{0}, new SampleAesKey[]{key});
    }

    /**
     * Returns keys that change at given offsets: each key applies from its offset up to the next offset.
     *
     * @param offsets the offsets at which the keys start, in increasing order, copied
     * @param keys the key of each offset, or {@code null} for a clear part, copied
     * @return the keys, which know no key before the first offset
     * @throws NullPointerException if an array is {@code null}
     * @throws IllegalArgumentException if the arrays differ in length or the offsets are negative or not
     * increasing
     */
    static SampleAesKeys ranges(long[] offsets, SampleAesKey[] keys) {
        Objects.requireNonNull(offsets, "offsets");
        Objects.requireNonNull(keys, "keys");
        if (offsets.length != keys.length) {
            throw new IllegalArgumentException(offsets.length + " offsets for " + keys.length + " keys");
        }
        for (var index = 0; index < offsets.length; index++) {
            if (offsets[index] < 0 || index > 0 && offsets[index] <= offsets[index - 1]) {
                throw new IllegalArgumentException("the offsets of SAMPLE-AES keys must be increasing and not "
                        + "negative");
            }
        }
        return new SampleAesKeyTable(offsets.clone(), keys.clone());
    }
}
