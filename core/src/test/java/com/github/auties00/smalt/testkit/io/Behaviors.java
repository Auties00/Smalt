package com.github.auties00.smalt.testkit.io;

import com.github.auties00.smalt.MediaException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.function.ThrowingSupplier;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * Helpers shared by the I/O behavior harnesses: time limits, the read indices at which faults are
 * injected, and the recognition of an injected fault in a failure.
 */
public final class Behaviors {
    private Behaviors() {
        throw new AssertionError();
    }

    /**
     * Runs a computation under a time limit, so that a parser that loops fails instead of hanging the
     * build.
     *
     * @param <T> the type of the result
     * @param timeout the limit
     * @param name the name of the input, for the failure message
     * @param body the computation; its unchecked exceptions propagate unchanged
     * @return the result
     */
    public static <T> T withTimeout(Duration timeout, String name, ThrowingSupplier<T> body) {
        return Assertions.assertTimeoutPreemptively(timeout, body, () -> name + ": no result within " + timeout
                + " (the parser loops or is far too slow)");
    }

    /**
     * Returns the read indices at which a fault is injected: every index below {@code reads} when there
     * are at most {@code bound} of them, else the first half of the bound and the rest spread evenly up
     * to the last read.
     *
     * @param reads the number of reads of the reference run
     * @param bound the largest number of indices
     * @return the indices, ascending and distinct
     */
    public static List<Long> faultIndices(long reads, int bound) {
        var indices = new ArrayList<Long>();
        if (reads <= bound) {
            for (var i = 0L; i < reads; i++) {
                indices.add(i);
            }
            return indices;
        }
        var head = bound / 2;
        for (var i = 0L; i < head; i++) {
            indices.add(i);
        }
        var rest = bound - head;
        for (var i = 1; i <= rest; i++) {
            var index = head + (reads - 1 - head) * i / rest;
            if (index > indices.getLast()) {
                indices.add(index);
            }
        }
        return indices;
    }

    /**
     * Returns whether a failure carries an injected fault among its causes or suppressed exceptions.
     *
     * @param failure the failure
     * @return {@code true} when a {@link SimulatedSource.InjectedFault} is reachable from {@code failure}
     */
    public static boolean causedByInjectedFault(Throwable failure) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        var pending = new ArrayList<Throwable>();
        pending.add(failure);
        while (!pending.isEmpty()) {
            var current = pending.removeLast();
            if (current == null || !seen.add(current)) {
                continue;
            }
            if (current instanceof SimulatedSource.InjectedFault) {
                return true;
            }
            pending.add(current.getCause());
            pending.addAll(List.of(current.getSuppressed()));
        }
        return false;
    }

    /**
     * Returns whether a failure is a clean outcome of an injected fault: a {@link MediaException.Io}
     * whose causes include the fault.
     *
     * @param failure the failure
     * @return {@code true} for such a failure
     */
    public static boolean isCleanFault(Throwable failure) {
        return failure instanceof MediaException.Io && causedByInjectedFault(failure);
    }
}
