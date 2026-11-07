package com.github.auties00.smalt.container.ts;

import java.util.Arrays;
import java.util.Optional;

/**
 * The {@link SampleAesKeys} of {@link SampleAesKeys#none()}, {@link SampleAesKeys#of(SampleAesKey)} and
 * {@link SampleAesKeys#ranges(long[], SampleAesKey[])}: keys by increasing start offset.
 *
 * @param offsets the offset at which each key starts, increasing
 * @param keys the key of each offset, {@code null} for a clear part
 */
record SampleAesKeyTable(long[] offsets, SampleAesKey[] keys) implements SampleAesKeys {
    /**
     * The keys that know no key.
     */
    static final SampleAesKeyTable NONE = new SampleAesKeyTable(new long[0], new SampleAesKey[0]);

    @Override
    public Optional<SampleAesKey> find(long offset) {
        var index = Arrays.binarySearch(offsets, offset);
        if (index < 0) {
            index = -index - 2;
        }
        return index < 0 ? Optional.empty() : Optional.ofNullable(keys[index]);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof SampleAesKeyTable that && Arrays.equals(offsets, that.offsets)
                && Arrays.equals(keys, that.keys);
    }

    @Override
    public int hashCode() {
        return 31 * Arrays.hashCode(offsets) + Arrays.hashCode(keys);
    }

    @Override
    public String toString() {
        return "SampleAesKeys[" + keys.length + " ranges]";
    }
}
