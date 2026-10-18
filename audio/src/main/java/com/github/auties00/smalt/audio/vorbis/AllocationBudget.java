package com.github.auties00.smalt.audio.vorbis;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.internal.limits.LimitChecks;

/**
 * Tracks the memory one Vorbis stream commits while its headers are parsed and its decoder is set
 * up.
 *
 * <p>A setup header of a few bytes can declare codebooks with millions of entries, lattice tables
 * of billions of values and residue partitions in the millions. Every allocation whose size the
 * stream controls is charged here before it is made, and the running total is checked against
 * {@link LimitChecks#checkAllocation(MediaLimits, long)}, so the sum of all such buffers, not only each one,
 * stays within the caller's bound.
 */
final class AllocationBudget {
    /**
     * The limits the running total is checked against.
     */
    private final MediaLimits limits;

    /**
     * The number of bytes charged so far.
     */
    private long total;

    /**
     * Constructs an empty budget.
     *
     * @param limits the limits the running total is checked against
     */
    AllocationBudget(MediaLimits limits) {
        this.limits = limits;
    }

    /**
     * Charges an allocation of {@code count} elements of {@code elementBytes} bytes each.
     *
     * @param count        the number of elements; non-negative
     * @param elementBytes the size of one element in bytes; positive
     * @throws MediaException.LimitExceeded if the running total exceeds the limits
     * @throws MediaException.Malformed     if {@code count} is negative
     */
    void charge(long count, int elementBytes) {
        if (count < 0) {
            throw new MediaException.Malformed("negative Vorbis allocation of " + count + " elements");
        }
        if (count > Long.MAX_VALUE / 64) {
            throw new MediaException.LimitExceeded("Vorbis allocation of " + count + " elements exceeds the limits");
        }
        total += count * elementBytes;
        LimitChecks.checkAllocation(limits, total);
    }

    /**
     * Returns the limits the budget checks against.
     *
     * @return the limits
     */
    MediaLimits limits() {
        return limits;
    }
}
