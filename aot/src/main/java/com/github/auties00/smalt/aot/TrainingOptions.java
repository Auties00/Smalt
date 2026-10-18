package com.github.auties00.smalt.aot;

import com.github.auties00.smalt.internal.check.Checks;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Holds the settings of a {@link SmaltTraining} run. Instances are immutable and thread-safe; create them with
 * {@link #builder()} or a preset such as {@link #defaults()}.
 */
public final class TrainingOptions {
    /**
     * The package of Smalt's classes, tracked by default.
     */
    private static final String SMALT_PACKAGE = "com.github.auties00.smalt";

    /**
     * The package of the Vector API, tracked by default.
     */
    private static final String VECTOR_PACKAGE = "jdk.incubator.vector";

    private static final TrainingOptions DEFAULTS = new Builder().build();

    private static final TrainingOptions REALTIME = new Builder().modes(EnumSet.of(TrainingMode.REALTIME)).build();

    private final Duration maxDuration;

    private final Set<TrainingFamily> families;

    private final Set<TrainingMode> modes;

    private final List<TrainingWorkload> workloads;

    private final List<String> trackedPackages;

    private TrainingOptions(Builder builder) {
        this.maxDuration = builder.maxDuration;
        var families = EnumSet.noneOf(TrainingFamily.class);
        families.addAll(builder.families);
        var modes = EnumSet.noneOf(TrainingMode.class);
        modes.addAll(builder.modes);
        this.families = Collections.unmodifiableSet(families);
        this.modes = Collections.unmodifiableSet(modes);
        this.workloads = List.copyOf(builder.workloads);
        this.trackedPackages = List.copyOf(builder.trackedPackages);
    }

    /**
     * Returns the default options: every {@link TrainingFamily} in every {@link TrainingMode} and no application
     * workload, for at most 15 minutes, tracking the compilations of Smalt's classes and of the Vector API.
     *
     * @return the default options
     */
    public static TrainingOptions defaults() {
        return DEFAULTS;
    }

    /**
     * Returns the options that run the {@link TrainingMode#REALTIME} workloads of every {@link TrainingFamily}:
     * the real-time video and audio encoders and decoders, the voice processor and the capture conversion kernels,
     * otherwise as {@link #defaults()}.
     *
     * @apiNote An application whose latency-critical work is real-time coding trains this way in less time than
     * with {@link #defaults()}; {@link Builder#families(Set)} narrows it to the artifacts whose codecs the
     * application uses.
     * @return the options
     */
    public static TrainingOptions forRealtime() {
        return REALTIME;
    }

    /**
     * Returns a builder initialized with {@link #defaults()}.
     *
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns a builder initialized with these options.
     *
     * @return a new builder
     */
    public Builder toBuilder() {
        return new Builder(this);
    }

    /**
     * {@return the longest time the run spends running workloads, positive; 15 minutes by default}
     *
     * <p>The time counts from the end of the setup, which creates the workloads; the run then waits up to
     * 20 seconds more for the JIT compiler to finish the compilations it queued.
     */
    public Duration maxDuration() {
        return maxDuration;
    }

    /**
     * {@return the families of Smalt's workloads the run exercises, unmodifiable; every {@link TrainingFamily} by
     * default}
     *
     * <p>When empty, the run exercises the application's {@link #workloads()} only.
     */
    public Set<TrainingFamily> families() {
        return families;
    }

    /**
     * {@return the coding modes of Smalt's workloads the run exercises, unmodifiable and never empty; every
     * {@link TrainingMode} by default}
     *
     * <p>A workload of a selected family runs only when its mode is one of these.
     */
    public Set<TrainingMode> modes() {
        return modes;
    }

    /**
     * {@return the application's own workloads, unmodifiable; none by default}
     *
     * <p>The run exercises them in this order, after Smalt's, in every cycle.
     */
    public List<TrainingWorkload> workloads() {
        return workloads;
    }

    /**
     * {@return the packages whose compilations measure the run's coverage, unmodifiable; Smalt's package
     * ({@code com.github.auties00.smalt}) and the Vector API's ({@code jdk.incubator.vector}) by default}
     *
     * <p>A cycle that compiles no further method of a class in one of these packages, or in one of their
     * subpackages, at the highest tier ends the run.
     */
    public List<String> trackedPackages() {
        return trackedPackages;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof TrainingOptions that && maxDuration.equals(that.maxDuration)
                && families.equals(that.families) && modes.equals(that.modes) && workloads.equals(that.workloads)
                && trackedPackages.equals(that.trackedPackages);
    }

    @Override
    public int hashCode() {
        return Objects.hash(maxDuration, families, modes, workloads, trackedPackages);
    }

    @Override
    public String toString() {
        return "TrainingOptions[maxDuration=" + maxDuration + ", families=" + families + ", modes=" + modes
                + ", workloads="
                + workloads.stream().map(TrainingWorkload::name).toList() + ", trackedPackages=" + trackedPackages
                + "]";
    }

    /**
     * A builder of {@link TrainingOptions}.
     *
     * <p>Setters reject only {@code null}, also as an element of a collection or array; {@link #build()} checks the
     * ranges. Instances are not thread-safe.
     */
    public static final class Builder {
        private Duration maxDuration = Duration.ofMinutes(15);

        private Set<TrainingFamily> families = EnumSet.allOf(TrainingFamily.class);

        private Set<TrainingMode> modes = EnumSet.allOf(TrainingMode.class);

        private List<TrainingWorkload> workloads = List.of();

        private List<String> trackedPackages = List.of(SMALT_PACKAGE, VECTOR_PACKAGE);

        private Builder() {
        }

        private Builder(TrainingOptions options) {
            this.maxDuration = options.maxDuration;
            this.families = options.families;
            this.modes = options.modes;
            this.workloads = options.workloads;
            this.trackedPackages = options.trackedPackages;
        }

        /**
         * Sets the longest time to spend running workloads; see {@link TrainingOptions#maxDuration()}.
         *
         * @param maxDuration the value
         * @return this builder
         */
        public Builder maxDuration(Duration maxDuration) {
            this.maxDuration = Objects.requireNonNull(maxDuration, "maxDuration");
            return this;
        }

        /**
         * Sets the families of Smalt's workloads; see {@link TrainingOptions#families()}.
         *
         * @param families the value, empty to run the application's workloads only
         * @return this builder
         * @throws NullPointerException if an element of {@code families} is {@code null}
         */
        public Builder families(Set<TrainingFamily> families) {
            this.families = copy(families, TrainingFamily.class, "families");
            return this;
        }

        /**
         * Sets the coding modes of Smalt's workloads; see {@link TrainingOptions#modes()}.
         *
         * @param modes the value
         * @return this builder
         * @throws NullPointerException if an element of {@code modes} is {@code null}
         */
        public Builder modes(Set<TrainingMode> modes) {
            this.modes = copy(modes, TrainingMode.class, "modes");
            return this;
        }

        /**
         * Sets the application's own workloads, replacing the ones this builder holds; see
         * {@link TrainingOptions#workloads()}.
         *
         * @param workloads the value
         * @return this builder
         * @throws NullPointerException if an element of {@code workloads} is {@code null}
         */
        public Builder workloads(List<TrainingWorkload> workloads) {
            this.workloads = List.copyOf(Objects.requireNonNull(workloads, "workloads"));
            return this;
        }

        /**
         * Appends application workloads to the ones this builder holds; see {@link TrainingOptions#workloads()}.
         *
         * <p>The builder of {@link TrainingOptions#builder()} holds none, so that a run of Smalt's families plus an
         * application's own workloads needs no list.
         *
         * @apiNote An application that adds its own workloads usually also adds its own top-level package to the
         * tracked ones, so that the run lasts until the application's code is compiled:
         * {@snippet :
         * var options = TrainingOptions.builder()
         *         .addWorkloads(TrainingWorkload.of("my-pipeline", MyApp::processSample))
         *         .addTrackedPackages("com.example.app")
         *         .build();
         * }
         * @param workloads the workloads, run in this order after the ones already held
         * @return this builder
         * @throws NullPointerException if an element of {@code workloads} is {@code null}
         */
        public Builder addWorkloads(TrainingWorkload... workloads) {
            this.workloads = append(this.workloads, workloads, "workloads");
            return this;
        }

        /**
         * Sets the packages whose compilations measure the run's coverage, replacing the ones this builder holds;
         * see {@link TrainingOptions#trackedPackages()}.
         *
         * @param trackedPackages the value
         * @return this builder
         * @throws NullPointerException if an element of {@code trackedPackages} is {@code null}
         */
        public Builder trackedPackages(List<String> trackedPackages) {
            this.trackedPackages = List.copyOf(Objects.requireNonNull(trackedPackages, "trackedPackages"));
            return this;
        }

        /**
         * Appends packages to the tracked ones this builder holds; see {@link TrainingOptions#trackedPackages()}.
         *
         * <p>The builder of {@link TrainingOptions#builder()} holds Smalt's package and the Vector API's, so that an
         * application adds its own without listing them again.
         *
         * @param trackedPackages the package names, such as {@code com.example.app}
         * @return this builder
         * @throws NullPointerException if an element of {@code trackedPackages} is {@code null}
         */
        public Builder addTrackedPackages(String... trackedPackages) {
            this.trackedPackages = append(this.trackedPackages, trackedPackages, "trackedPackages");
            return this;
        }

        /**
         * Returns the options holding the settings of this builder.
         *
         * @return the options
         * @throws IllegalArgumentException if the maximum duration is not positive, the modes are empty or a
         * tracked package name is blank
         */
        public TrainingOptions build() {
            Checks.positive("maximum duration", maxDuration);
            Checks.notEmpty("modes", modes);
            for (var name : trackedPackages) {
                if (name.isBlank()) {
                    throw new IllegalArgumentException("tracked package \"" + name + "\" is blank");
                }
            }
            return new TrainingOptions(this);
        }

        /**
         * Returns a modifiable copy of a set of constants, rejecting a {@code null} set or element.
         */
        private static <E extends Enum<E>> Set<E> copy(Set<E> values, Class<E> type, String name) {
            Objects.requireNonNull(values, name);
            var copy = EnumSet.noneOf(type);
            for (var value : values) {
                copy.add(Objects.requireNonNull(value, name + " cannot contain null"));
            }
            return copy;
        }

        /**
         * Returns an unmodifiable list of the held elements followed by the added ones, rejecting a {@code null}
         * array or element.
         */
        private static <T> List<T> append(Collection<T> held, T[] added, String name) {
            Objects.requireNonNull(added, name);
            var result = new ArrayList<T>(held.size() + added.length);
            result.addAll(held);
            for (var element : added) {
                result.add(Objects.requireNonNull(element, name + " cannot contain null"));
            }
            return List.copyOf(result);
        }
    }
}
