package com.github.auties00.smalt.aot;

import com.github.auties00.smalt.aot.internal.CompilationWatch;
import com.github.auties00.smalt.aot.internal.Workloads;

import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Runs Smalt's representative media workloads, and the application's own, inside the training run
 * of a JDK ahead-of-time cache.
 *
 * <p>A JDK AOT cache (JEP 483, 514, 515) is recorded from a training run of the application, with
 * the class path, module path, {@code --add-modules} and {@code --enable-native-access} options the
 * application runs with in production, on the same JDK build. It stores the classes the training
 * loaded and linked, the method profiles it collected and, on JDKs that support it, the code the
 * JIT compiler produced, so that production starts with them instead of in the interpreter. A
 * library cannot ship a cache, since a cache belongs to one JDK build and one class path, so Smalt
 * ships this training workload instead.
 *
 * <p>{@link #train(TrainingOptions)} creates the workloads of the requested
 * {@link TrainingFamily families} in the requested {@link TrainingMode modes} and the
 * application's {@link TrainingWorkload workloads}, then
 * runs cycles of one round of every workload in a fixed order. After each cycle it waits for the
 * JIT compiler to go idle, and it ends after a cycle in which no further method of the tracked
 * packages was compiled at the highest tier, so that it does the same work on a fast and on a slow
 * machine; the time limit only bounds it. The compilations are counted from the Flight Recorder's
 * {@code jdk.Compilation} events, streamed in the training JVM; without the Flight Recorder the
 * run goes through a fixed number of cycles. Finally the run waits for the JIT compiler to finish
 * the compilations it queued, so that the cache records them.
 *
 * <p>The cache only helps where its JDK can use it. On JDK 26 to 28, a JVM that resolves the
 * incubating {@code jdk.incubator.vector} module does not archive its boot layer, so a cache
 * recorded with {@code --add-modules jdk.incubator.vector} holds parsed classes only, without
 * linked classes, profiles or compiled code: production then runs Smalt's Vector API kernels
 * uncompiled for its first seconds, with correct output but slowly. A JDK built from the Leyden
 * {@code premain} branch with the Vector API patches
 * ({@code https://github.com/Auties00/leyden/tree/premain-vector-aot}) stores linked classes,
 * profiles and compiled vector code, and production runs compiled kernels from its first frame.
 *
 * @apiNote An application on the class path can train without code changes, running this class as
 *          the main class of the training run and its own main after it:
 * {@snippet :
 * JDK_AOT_VM_OPTIONS=-XX:+BackgroundCompilation \
 * java -Xbatch --add-modules jdk.incubator.vector -XX:AOTCacheOutput=app.aot \
 *      -cp app.jar:smalt-core.jar:...:smalt-aot.jar \
 *      com.github.auties00.smalt.aot.SmaltTraining --then com.example.Main --self-test
 * java --add-modules jdk.incubator.vector -XX:AOTCache=app.aot \
 *      -cp app.jar:smalt-core.jar:...:smalt-aot.jar com.example.Main
 * }
 *          An application on the module path, whose launcher must name its own main module, invokes
 *          {@link #train(TrainingOptions)} from a training mode of its main method instead, adding
 *          its own workloads through {@link TrainingOptions.Builder#addWorkloads(TrainingWorkload...)}.
 *          {@code -Xbatch} makes the training run compile in the foreground, so that two training
 *          runs record the same compilations; the cache-creation phase that the one-step
 *          {@code -XX:AOTCacheOutput} workflow starts inherits the option and produces worse code
 *          with it, so {@code JDK_AOT_VM_OPTIONS} turns background compilation back on there.
 *          Production needs neither. A production run without a cache, or with one the JVM
 *          rejects (another JDK build, another class path), runs correctly, only with the slower
 *          start of a fresh JVM.
 */
public final class SmaltTraining {
    /**
     * How long the JIT compiler must stay idle after the last cycle before the run counts its
     * compilations as finished, in nanoseconds.
     */
    private static final long QUIET_NANOS = TimeUnit.MILLISECONDS.toNanos(1500);

    /**
     * How long the JIT compiler must stay idle after each cycle, in nanoseconds.
     */
    private static final long CYCLE_QUIET_NANOS = TimeUnit.MILLISECONDS.toNanos(500);

    /**
     * The longest a wait for an idle JIT compiler lasts, in nanoseconds.
     */
    private static final long MAX_DRAIN_NANOS = TimeUnit.SECONDS.toNanos(20);

    /**
     * The fewest cycles a run goes through before it may end.
     */
    private static final int MIN_CYCLES = 3;

    /**
     * The cycles a run goes through when it cannot observe the JIT compiler's compilations.
     */
    private static final int FALLBACK_CYCLES = 8;

    /**
     * Prevents instantiation.
     */
    private SmaltTraining() {
        throw new AssertionError();
    }

    /**
     * Runs a training with {@link TrainingOptions#defaults()}: every family of Smalt's workloads.
     *
     * @return what the run did
     */
    public static TrainingReport train() {
        return train(TrainingOptions.defaults());
    }

    /**
     * Runs a training: Smalt's workloads of the selected families and modes and the application's
     * own, in cycles, until a cycle compiles no further method of the tracked packages or the time
     * limit passes.
     *
     * <p>Smalt's workloads are created first, each once, which already runs its codecs' setup; a
     * workload that cannot be created on this platform is reported as skipped. The setup of the
     * application's {@linkplain TrainingWorkload#deferred deferred} workloads runs next, in the order
     * given, and one that fails is reported as skipped the same way. Every cycle runs one
     * round of every workload, Smalt's in the order of {@link TrainingFamily} and then the
     * application's in the order given, and waits for the JIT compiler to be idle. The run ends
     * after a cycle, at least the third, in which no further method of a class in
     * {@link TrainingOptions#trackedPackages()} was compiled at the highest tier, or once
     * {@link TrainingOptions#maxDuration()} has passed since the setup ended; without the Flight
     * Recorder, which reports the compilations, it ends after the eighth cycle. A workload whose round
     * throws is reported as skipped and not run again; the run goes on. A name that two workloads
     * share is reported with a {@code #2}, {@code #3}, ... suffix for the later ones.
     *
     * @param options the families, modes, application workloads, tracked packages and time limit of the run
     * @return what the run did
     * @throws IllegalArgumentException if the options select no family and no workload
     */
    public static TrainingReport train(TrainingOptions options) {
        Objects.requireNonNull(options, "options cannot be null");
        if (options.families().isEmpty() && options.workloads().isEmpty()) {
            throw new IllegalArgumentException("the options select no family and no workload");
        }
        var start = System.nanoTime();
        var workloads = new ArrayList<TrainingWorkload>();
        var skipped = new ArrayList<String>();
        for (var family : options.families()) {
            for (var factory : Workloads.of(family)) {
                if (!options.modes().contains(factory.mode())) {
                    continue;
                }
                try {
                    workloads.add(factory.create());
                } catch (Exception | LinkageError exception) {
                    skipped.add(factory.name() + ": " + exception);
                }
            }
        }
        for (var workload : options.workloads()) {
            if (workload instanceof DeferredTrainingWorkload deferred) {
                try {
                    deferred.create();
                } catch (Exception | LinkageError exception) {
                    skipped.add(deferred.name() + ": " + exception);
                    continue;
                }
            }
            workloads.add(workload);
        }
        var setupEnd = System.nanoTime();
        var setup = Duration.ofNanos(setupEnd - start);
        var names = uniqueNames(workloads);
        var rounds = new int[workloads.size()];
        var failed = new boolean[workloads.size()];
        var deadline = setupEnd + options.maxDuration().toNanos();
        CompilationWatch watch;
        try {
            watch = CompilationWatch.start(options.trackedPackages());
        } catch (RuntimeException | LinkageError exception) {
            watch = null;
        }
        var cycles = 0;
        var converged = false;
        try {
            while (System.nanoTime() < deadline) {
                var before = watch == null ? 0 : watch.count();
                for (var i = 0; i < workloads.size(); i++) {
                    if (failed[i]) {
                        continue;
                    }
                    try {
                        workloads.get(i).round();
                        rounds[i]++;
                    } catch (Exception | LinkageError exception) {
                        skipped.add(names.get(i) + ": " + exception);
                        failed[i] = true;
                    }
                }
                cycles++;
                awaitCompilerQuiet(CYCLE_QUIET_NANOS);
                if (watch == null) {
                    if (cycles >= FALLBACK_CYCLES) {
                        break;
                    }
                    continue;
                }
                watch.sync();
                if (cycles >= MIN_CYCLES && watch.count() == before) {
                    converged = true;
                    break;
                }
            }
        } finally {
            if (watch != null) {
                watch.close();
            }
        }
        var roundsByName = new LinkedHashMap<String, Integer>();
        for (var i = 0; i < workloads.size(); i++) {
            if (!failed[i]) {
                roundsByName.put(names.get(i), rounds[i]);
            }
        }
        awaitCompilerQuiet(QUIET_NANOS);
        return new TrainingReport(Duration.ofNanos(System.nanoTime() - start), setup, roundsByName, skipped, cycles,
                converged);
    }

    /**
     * Returns the names of the workloads, a later workload whose name an earlier one already has
     * getting a {@code #2}, {@code #3}, ... suffix.
     *
     * @param workloads the workloads of the run, in run order
     * @return the names, in the order of the workloads
     */
    private static List<String> uniqueNames(List<TrainingWorkload> workloads) {
        var seen = new HashSet<String>();
        var names = new ArrayList<String>(workloads.size());
        for (var workload : workloads) {
            var base = String.valueOf(workload.name());
            var name = base;
            for (var copy = 2; !seen.add(name); copy++) {
                name = base + "#" + copy;
            }
            names.add(name);
        }
        return names;
    }

    /**
     * Waits until the JIT compiler has been idle for a while, or for at most
     * {@link #MAX_DRAIN_NANOS}, so that the compilations the training queued complete.
     *
     * <p>Idleness is read from the total compilation time the JVM reports; a JVM that does not
     * report it is not waited for.
     *
     * @param quietNanos how long the compiler must stay idle, in nanoseconds
     */
    private static void awaitCompilerQuiet(long quietNanos) {
        var compilation = ManagementFactory.getCompilationMXBean();
        if (compilation == null || !compilation.isCompilationTimeMonitoringSupported()) {
            return;
        }
        var limit = System.nanoTime() + MAX_DRAIN_NANOS;
        var last = compilation.getTotalCompilationTime();
        var quietSince = System.nanoTime();
        while (System.nanoTime() < limit && System.nanoTime() - quietSince < quietNanos) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return;
            }
            var now = compilation.getTotalCompilationTime();
            if (now != last) {
                last = now;
                quietSince = System.nanoTime();
            }
        }
    }

    /**
     * Runs a training from the command line, then optionally the main method of another class in
     * the same JVM.
     *
     * <p>Options:
     * <ul>
     * <li>{@code --max-seconds N}: the longest time to spend running workloads (default 900);</li>
     * <li>{@code --families A,B}: the {@link TrainingFamily} constants to run, case-insensitive
     *     (default all);</li>
     * <li>{@code --modes A,B}: the {@link TrainingMode} constants to run, case-insensitive (default
     *     all);</li>
     * <li>{@code --then <class> [args...]}: after the training, runs the {@code main} method of the
     *     named class with the remaining arguments, so that one training run covers Smalt and the
     *     application.</li>
     * </ul>
     *
     * <p>The run prints a one-line summary to the standard error stream, and warns there when the
     * JVM is not recording an AOT cache or does not resolve {@code jdk.incubator.vector}: a cache
     * recorded without the Vector API does not match a vectorized production run.
     *
     * @param args the command-line arguments
     * @throws IllegalArgumentException if an option is unknown, is missing its value, or names no
     * family or mode
     * @throws Throwable what the {@code main} method named by {@code --then} throws, or
     * the failure to find it
     */
    public static void main(String[] args) throws Throwable {
        var builder = TrainingOptions.builder();
        String then = null;
        var thenArgs = new String[0];
        for (var i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--max-seconds" -> builder.maxDuration(Duration.ofSeconds(Long.parseLong(value(args, ++i))));
                case "--families" -> {
                    var families = EnumSet.noneOf(TrainingFamily.class);
                    for (var name : value(args, ++i).split(",")) {
                        families.add(TrainingFamily.valueOf(name.strip().toUpperCase(Locale.ROOT)));
                    }
                    builder.families(families);
                }
                case "--modes" -> {
                    var modes = EnumSet.noneOf(TrainingMode.class);
                    for (var name : value(args, ++i).split(",")) {
                        modes.add(TrainingMode.valueOf(name.strip().toUpperCase(Locale.ROOT)));
                    }
                    builder.modes(modes);
                }
                case "--then" -> {
                    then = value(args, ++i);
                    thenArgs = Arrays.copyOfRange(args, i + 1, args.length);
                    i = args.length;
                }
                default -> throw new IllegalArgumentException("unknown option " + args[i]);
            }
        }
        var arguments = ManagementFactory.getRuntimeMXBean().getInputArguments();
        if (arguments.stream().noneMatch(argument -> argument.startsWith("-XX:AOTCacheOutput")
                || argument.startsWith("-XX:AOTMode=record") || argument.startsWith("-XX:AOTConfiguration"))) {
            System.err.println("smalt-aot: this JVM is not recording an AOT cache; add -XX:AOTCacheOutput=<file>.aot");
        }
        if (ModuleLayer.boot().findModule("jdk.incubator.vector").isEmpty()) {
            System.err.println("smalt-aot: jdk.incubator.vector is not resolved; train with the --add-modules option production uses");
        }
        var report = train(builder.build());
        System.err.println("smalt-aot: " + report.summary());
        if (then != null) {
            var main = Class.forName(then, true, ClassLoader.getSystemClassLoader()).getMethod("main", String[].class);
            try {
                main.invoke(null, (Object) thenArgs);
            } catch (java.lang.reflect.InvocationTargetException exception) {
                throw exception.getCause();
            }
        }
    }

    /**
     * Returns the value of a command-line option.
     *
     * @param args the command-line arguments
     * @param index the index of the value
     * @return the value
     * @throws IllegalArgumentException if the option is the last argument
     */
    private static String value(String[] args, int index) {
        if (index >= args.length) {
            throw new IllegalArgumentException(args[index - 1] + " needs a value");
        }
        return args[index];
    }
}
