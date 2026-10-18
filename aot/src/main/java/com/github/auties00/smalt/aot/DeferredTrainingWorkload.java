package com.github.auties00.smalt.aot;

import java.util.Objects;
import java.util.concurrent.Callable;

/**
 * A training workload whose setup runs once, when a training run creates its workloads or at the
 * first {@link #round()}, and whose rounds are those of the workload the setup returns.
 *
 * <p>Instances are not thread-safe.
 */
final class DeferredTrainingWorkload implements TrainingWorkload {
    private final String name;
    private final Callable<? extends TrainingWorkload> setup;
    private TrainingWorkload workload;

    /**
     * The failure of the setup, rethrown by every later {@link #create()}, or {@code null} while
     * the setup has not failed.
     */
    private Exception failure;

    DeferredTrainingWorkload(String name, Callable<? extends TrainingWorkload> setup) {
        this.name = Objects.requireNonNull(name, "name cannot be null");
        this.setup = Objects.requireNonNull(setup, "setup cannot be null");
    }

    /**
     * Runs the setup unless it already ran, and returns the workload it created.
     *
     * @return the workload the setup returned
     * @throws Exception what the setup threw, on this and every later call; a
     * {@link NullPointerException} if it returned {@code null}
     */
    TrainingWorkload create() throws Exception {
        if (workload != null) {
            return workload;
        }
        if (failure != null) {
            throw failure;
        }
        try {
            workload = Objects.requireNonNull(setup.call(), "setup returned null");
            return workload;
        } catch (Exception exception) {
            failure = exception;
            throw exception;
        }
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public void round() throws Exception {
        create().round();
    }

    @Override
    public String toString() {
        return name;
    }
}
