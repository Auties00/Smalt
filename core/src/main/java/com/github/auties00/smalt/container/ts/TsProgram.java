package com.github.auties00.smalt.container.ts;

import java.util.List;
import java.util.Objects;

/**
 * Describes one program of an MPEG transport stream, as its program association and program map
 * tables declare it (ISO/IEC 13818-1 clauses 2.4.4.3 and 2.4.4.8).
 *
 * <p>Instances are immutable and thread-safe.
 *
 * @param number the {@code program_number}, from 1 to 65535
 * @param pmtPid the PID of the program map table
 * @param pcrPid the PID whose packets carry the program clock reference, or {@code 0x1FFF} when the
 * program has none
 * @param elementaryPids the PIDs of the program's elementary streams, in program map order;
 * unmodifiable
 */
public record TsProgram(int number, int pmtPid, int pcrPid, List<Integer> elementaryPids) {
    /**
     * Validates the description and makes the PID list unmodifiable.
     *
     * @throws NullPointerException if {@code elementaryPids} or one of its elements is {@code null}
     */
    public TsProgram {
        elementaryPids = List.copyOf(Objects.requireNonNull(elementaryPids, "elementaryPids"));
    }
}
