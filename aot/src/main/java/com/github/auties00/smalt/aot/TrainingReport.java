package com.github.auties00.smalt.aot;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Describes what a {@link SmaltTraining} run did.
 *
 * @param elapsed the wall time of the run, the setup and the final wait for the JIT compiler included
 * @param setup the part of {@code elapsed} spent creating Smalt's workloads and their inputs and running the
 * setup of the application's {@linkplain TrainingWorkload#deferred deferred} workloads
 * @param rounds the rounds run by each workload that lasted to the end of the run, by name
 * @param skipped the workloads that could not be created or failed, each with its error
 * @param cycles the cycles of one round of every workload the run went through
 * @param converged whether the run ended because a cycle compiled no further method of the tracked packages,
 * rather than at its time limit or after the fixed number of cycles of a JVM without the Flight Recorder
 */
public record TrainingReport(Duration elapsed, Duration setup, Map<String, Integer> rounds, List<String> skipped,
                             int cycles, boolean converged) {
    /**
     * Creates a report.
     *
     * @param elapsed   the wall time of the run
     * @param setup     the time spent creating the workloads
     * @param rounds    the rounds each workload ran
     * @param skipped   the workloads that did not run to the end
     * @param cycles    the cycles the run went through
     * @param converged whether the run converged
     * @throws NullPointerException     if an argument is {@code null}
     * @throws IllegalArgumentException if {@code cycles} is negative
     */
    public TrainingReport {
        Objects.requireNonNull(elapsed, "elapsed cannot be null");
        Objects.requireNonNull(setup, "setup cannot be null");
        rounds = Map.copyOf(rounds);
        skipped = List.copyOf(skipped);
        if (cycles < 0) {
            throw new IllegalArgumentException("cycles cannot be negative: " + cycles);
        }
    }

    /**
     * Returns a one-line summary of the run: the workloads, rounds and cycles, whether it
     * converged, its duration, the skipped workloads and the rounds of each workload.
     *
     * @return the summary
     */
    public String summary() {
        var total = rounds.values().stream().mapToLong(Integer::longValue).sum();
        return String.format(Locale.ROOT, "%d workloads, %d rounds in %d cycles (%s) in %.1f s (setup %.1f s), %d skipped%s %s",
                rounds.size(), total, cycles, converged ? "converged" : "not converged",
                elapsed.toMillis() / 1000.0, setup.toMillis() / 1000.0, skipped.size(),
                skipped.isEmpty() ? "" : " " + skipped, new TreeMap<>(rounds));
    }
}
