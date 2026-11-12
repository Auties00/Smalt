package com.github.auties00.smalt.testkit.jit;

import org.junit.jupiter.api.DynamicTest;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.constantpool.ClassEntry;
import java.lang.reflect.AccessFlag;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;

/**
 * Checks, deterministically, that every Vector API kernel family runs compiled by C2 without allocating, and with
 * the scalar twin's output, under each configuration of a fixed {@linkplain Scenario scenario matrix}.
 *
 * <p>A family is a set of kernel classes (top-level classes named {@code Vector*} that use {@code jdk.incubator.vector})
 * together with the equivalence tests that call them. Each family runs in a child JVM per scenario
 * ({@link KernelGuardChild}), started with {@code -Xbatch} (a compilation completes before the requesting thread goes
 * on, so that compilations happen at the same points in every run), a single-threaded driver, the tests excluded from
 * compilation (so that each kernel is compiled on its own) and the {@link KernelMeterAgent} instrumenting the tests'
 * kernel calls. After a fixed warm-up the family fails if:
 * <ul>
 *   <li>a test fails in any round, the measured one included, where every kernel runs compiled;</li>
 *   <li>an entry point of a kernel has no C2 code (level 4) when the measured round starts;</li>
 *   <li>a measured kernel call allocates anything at all;</li>
 *   <li>{@code PrintInlining}, scoped to Smalt's methods, reports a vector intrinsic whose last outcome is a failure
 *       ({@code failed to inline (intrinsic)}, ...), outside the stress scenarios (see
 *       {@link #intrinsicFailures(Path)});</li>
 *   <li>a compilation of a Smalt method fails (JFR {@code jdk.CompilationFailure}), or a kernel is deoptimized
 *       during the measured round (JFR {@code jdk.Deoptimization}).</li>
 * </ul>
 * There are no calibrated bounds and no retries: a failure prints the scenario, its seed and the full command line,
 * which reproduces it. When a measured call allocates or an entry point has no C2 code yet, the child warms up again
 * with 4, 16 and at most 64 times the repetitions and measures again, the same way in every run (compilation is
 * synchronous); only the last measured round decides, and the result names the extra warm-up rounds it needed.
 */
public final class KernelGuard {
    private static final String SMALT = "com/github/auties00/smalt/";
    private static final Pattern COMPILE_LINE = Pattern.compile("^\\s*\\d+\\s+(\\d+)?\\s*[%sbn! ]*\\s*(\\d)\\s+(\\S+)");
    private static final String SMALT_TRAINING = "com.github.auties00.smalt.aot.SmaltTraining";
    private static final Object AGENT_LOCK = new Object();
    private static final Map<String, String> JARRED = new HashMap<>();
    private static Path agent;

    private final List<Family> families;
    private final String classPath;
    private final Path work;
    private final Settings settings;

    private KernelGuard(List<Family> families, String classPath, Path work, Settings settings) {
        this.families = families;
        this.classPath = classPath;
        this.work = work;
        this.settings = settings;
    }

    /**
     * A kernel family: the kernel classes and the test classes that call them.
     *
     * @param name the family name, the kernels' simple names joined with {@code +}
     * @param kernels the kernel classes' binary names
     * @param types the binary names of the kernels and their Smalt supertypes, whose calls the tests instrument
     * @param tests the test classes' binary names (top-level)
     * @param instrumented the top-level test classes whose kernel calls are metered: the tests and the test classes
     *        they extend
     * @param callers the test methods that call a kernel, as {@code package/Class.method}, which the child runs
     *        interpreted so that every kernel compiles on its own
     * @param vectorMethods the methods of the kernel classes (nested classes included) that call the Vector API, as
     *        {@code Class.name(descriptor)}
     * @param setup the methods of the kernel classes marked {@code KernelSetup}, as {@code name(descriptor)}, whose
     *        calls are not metered
     * @param wideMethods the methods of the kernel classes, as {@code Class.name(descriptor)}, that use 256-bit or
     *        wider vectors, gathers or scatters without testing {@code Simd.WIDE} themselves: their kernel is selected
     *        only where that holds, so they are not metered where it does not
     * @param gated the kernel classes the library creates only where {@code Simd.WIDE} holds, whose calls are not
     *        metered where it does not
     */
    public record Family(String name, Set<String> kernels, Set<String> types, List<String> tests,
                         Set<String> instrumented, Set<String> callers, Set<String> vectorMethods,
                         Set<String> setup, Set<String> wideMethods, Set<String> gated) {
    }

    /**
     * A configuration of the child JVM.
     *
     * @param name the scenario name
     * @param flags the JVM options it adds
     * @param pollute the rounds of {@link VectorShapes#pollute(int)} to run first, or 0
     * @param seed the stress seed, or 0
     * @param cached whether the child starts from a JDK AOT cache that a training child recorded first
     */
    public record Scenario(String name, List<String> flags, int pollute, int seed, boolean cached) {
        private Scenario(String name, List<String> flags, int pollute, int seed) {
            this(name, flags, pollute, seed, false);
        }

        /** {@return the default tiered compilation} */
        public static Scenario tiered() {
            return new Scenario("tiered", List.of(), 0, 0);
        }

        /** {@return C2 only, profiling in the interpreter} */
        public static Scenario c2Only() {
            return new Scenario("c2-only", List.of("-XX:-TieredCompilation"), 0, 0);
        }

        /**
         * Returns a run started from a JDK AOT cache (JEP 483, 515) recorded by a training child of the same family:
         * the training runs {@code SmaltTraining} first when {@code smalt-aot} is on the class path, then the family's
         * tests, so that the measured child compiles the kernels from the profiles a trained application starts with.
         * The children run on the class path with every directory packed into a jar, since a cache records classes
         * from jars only, and the measured child fails to start when it cannot use the cache.
         *
         * <p>On JDK 26 a JVM that resolves {@code jdk.incubator.vector} does not archive its boot layer, so the cache
         * holds parsed classes without linked classes or profiles and the kernels compile as under {@link #tiered()};
         * the result then notes it. The scenario is in neither {@link #pullRequest()} nor {@link #nightly()} for that
         * reason: it is for a JDK whose caches keep profiles with the Vector API.
         *
         * @return the scenario
         */
        public static Scenario aot() {
            return new Scenario("aot", List.of(), 0, 0, true);
        }

        /**
         * Returns a run whose Vector API profiles first see every shape and element type.
         *
         * @param tiered whether tiered compilation stays on
         * @return the scenario
         */
        public static Scenario polluted(boolean tiered) {
            return new Scenario(tiered ? "polluted" : "polluted-c2-only",
                    tiered ? List.of() : List.of("-XX:-TieredCompilation"), 3000, 0);
        }

        /**
         * Returns a run with C2's randomized stress modes under a fixed seed.
         *
         * @param seed the seed, at least 1
         * @return the scenario
         */
        public static Scenario stress(int seed) {
            return new Scenario("stress-" + seed, List.of("-XX:+StressIGVN", "-XX:+StressIncrementalInlining",
                    "-XX:+StressCCP", "-XX:+StressGCM", "-XX:+StressLCM", "-XX:+StressMacroExpansion",
                    "-XX:StressSeed=" + seed), 0, seed);
        }

        /**
         * Returns a run with narrower vectors.
         *
         * @param name the scenario name
         * @param flags the options that narrow the vectors
         * @return the scenario
         */
        public static Scenario narrow(String name, String... flags) {
            return new Scenario(name, List.of(flags), 0, 0);
        }

        /**
         * Returns the scenarios every pull request runs: tiered, C2 only, a polluted profile, one stress seed, and each
         * vector width narrower than the host's that the platform can run ({@link #widths()}).
         *
         * @return the scenarios
         */
        public static List<Scenario> pullRequest() {
            var scenarios = new ArrayList<>(List.of(tiered(), c2Only(), polluted(true), stress(1)));
            scenarios.addAll(widths());
            return List.copyOf(scenarios);
        }

        /**
         * Returns the nightly scenarios: those of {@link #pullRequest()}, a polluted profile under C2 only, and more
         * stress seeds.
         *
         * @return the scenarios
         */
        public static List<Scenario> nightly() {
            var scenarios = new ArrayList<>(pullRequest());
            scenarios.add(polluted(false));
            for (var seed = 2; seed <= 6; seed++) {
                scenarios.add(stress(seed));
            }
            return List.copyOf(scenarios);
        }

        /**
         * Returns the vector widths narrower than the host's: on x86 AVX2 when the host has AVX-512, 256-bit vectors
         * when it has wider ones, and 128-bit vectors (the width of ARM NEON); on arm64 with SVE wider than 128 bits,
         * 128-bit vectors.
         *
         * @return the scenarios
         */
        public static List<Scenario> widths() {
            var flags = HostFlags.get();
            var scenarios = new ArrayList<Scenario>();
            var x86 = flags.containsKey("UseAVX");
            var maxVector = Integer.parseInt(flags.getOrDefault("MaxVectorSize", "16"));
            if (x86 && Integer.parseInt(flags.get("UseAVX")) >= 3) {
                scenarios.add(narrow("avx2", "-XX:UseAVX=2"));
            }
            if (maxVector > 32) {
                scenarios.add(narrow("vector-256", "-XX:MaxVectorSize=32"));
            }
            if (maxVector > 16) {
                scenarios.add(narrow("vector-128", "-XX:MaxVectorSize=16"));
            }
            return scenarios;
        }

        /**
         * Returns the scenarios a comma-separated list names ({@code tiered}, {@code c2-only}, {@code polluted},
         * {@code polluted-c2-only}, {@code stress-<seed>}, {@code avx2}, {@code avx3}, {@code vector-256},
         * {@code vector-128}, {@code aot}, {@code pr}, {@code nightly}).
         *
         * @param names the names
         * @return the scenarios
         */
        public static List<Scenario> parse(String names) {
            var scenarios = new LinkedHashMap<String, Scenario>();
            for (var name : names.split(",")) {
                var trimmed = name.strip();
                var parsed = switch (trimmed) {
                    case "" -> List.<Scenario>of();
                    case "pr" -> pullRequest();
                    case "nightly" -> nightly();
                    case "tiered" -> List.of(tiered());
                    case "c2-only" -> List.of(c2Only());
                    case "polluted" -> List.of(polluted(true));
                    case "polluted-c2-only" -> List.of(polluted(false));
                    case "avx2" -> List.of(narrow("avx2", "-XX:UseAVX=2"));
                    case "avx3" -> List.of(narrow("avx3", "-XX:UseAVX=3"));
                    case "vector-256" -> List.of(narrow("vector-256", "-XX:MaxVectorSize=32"));
                    case "vector-128" -> List.of(narrow("vector-128", "-XX:MaxVectorSize=16"));
                    case "aot" -> List.of(aot());
                    default -> {
                        if (trimmed.startsWith("stress-")) {
                            yield List.of(stress(Integer.parseInt(trimmed.substring("stress-".length()))));
                        }
                        throw new IllegalArgumentException("unknown scenario " + trimmed);
                    }
                };
                for (var scenario : parsed) {
                    scenarios.putIfAbsent(scenario.name(), scenario);
                }
            }
            return List.copyOf(scenarios.values());
        }
    }

    /**
     * The counts and options of a run.
     *
     * @param target the calls per entry point and warm-up round
     * @param cap the most repetitions of one call
     * @param warmRounds the warm-up rounds
     * @param timeout the time limit of one child
     * @param extraFlags JVM options every child gets, after the scenario's
     */
    public record Settings(int target, int cap, int warmRounds, Duration timeout, List<String> extraFlags) {
        /** {@return the settings every guard uses unless a test overrides them} */
        public static Settings defaults() {
            return new Settings(12_000, 20_000, 2, Duration.ofMinutes(30), List.of());
        }

        /**
         * Returns these settings with more JVM options.
         *
         * @param flags the options
         * @return the settings
         */
        public Settings withFlags(String... flags) {
            var all = new ArrayList<>(extraFlags);
            all.addAll(List.of(flags));
            return new Settings(target, cap, warmRounds, timeout, List.copyOf(all));
        }
    }

    /**
     * The outcome of one family under one scenario.
     *
     * @param family the family
     * @param scenario the scenario
     * @param command the child's command line
     * @param problems what failed, empty when the family passed
     * @param entries the entry points measured
     * @param log the directory holding the child's output
     */
    public record Result(Family family, Scenario scenario, List<String> command, List<String> problems,
                         List<String> entries, Path log) {
        /** {@return whether the family passed} */
        public boolean passed() {
            return problems.isEmpty();
        }

        /** {@return a description for a failure message: the problems, the scenario, its seed and the command} */
        public String describe() {
            return "kernel family " + family.name() + " failed under scenario " + scenario.name()
                    + (scenario.seed() != 0 ? " (stress seed " + scenario.seed() + ")" : "") + ":\n  "
                    + String.join("\n  ", problems) + "\nreproduce with:\n  "
                    + String.join(" ", command.stream().map(KernelGuard::quote).toList())
                    + "\nlogs: " + log;
        }
    }

    /**
     * Returns the guard of the module whose tests are running: the kernels under {@code target/classes} and the tests
     * under {@code target/test-classes} of the working directory, run with the test class path.
     *
     * @param exclude the test classes to leave out (fixtures of the guard's own tests)
     * @return the guard
     */
    public static KernelGuard ofModule(Predicate<String> exclude) {
        return of(List.of(Path.of("target", "classes")), List.of(Path.of("target", "test-classes")), exclude,
                testClassPath(), Path.of("target", "kernel-guard"), Settings.defaults());
    }

    /**
     * Returns a guard over the given class roots.
     *
     * @param kernelRoots the directories holding the kernel classes
     * @param testRoots the directories holding the test classes
     * @param exclude the test classes to leave out
     * @param classPath the children's class path
     * @param work the directory for the children's files
     * @param settings the counts and options
     * @return the guard
     */
    public static KernelGuard of(List<Path> kernelRoots, List<Path> testRoots, Predicate<String> exclude,
                                 String classPath, Path work, Settings settings) {
        return new KernelGuard(discover(kernelRoots, testRoots, exclude), classPath, work.toAbsolutePath(), settings);
    }

    /** {@return the class path of the running tests} */
    public static String testClassPath() {
        return System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
    }

    /** {@return the families found} */
    public List<Family> families() {
        return families;
    }

    /**
     * Returns this guard with other settings.
     *
     * @param newSettings the settings
     * @return the guard
     */
    public KernelGuard with(Settings newSettings) {
        return new KernelGuard(families, classPath, work, newSettings);
    }

    /**
     * Returns this guard restricted to the families a pattern matches.
     *
     * @param pattern a regular expression matched against the family names
     * @return the guard
     */
    public KernelGuard only(String pattern) {
        var regex = Pattern.compile(pattern);
        return new KernelGuard(families.stream().filter(family -> regex.matcher(family.name()).find()).toList(),
                classPath, work, settings);
    }

    /**
     * Returns a dynamic test per family and scenario: the scenarios of {@code smalt.jit.scenarios} ({@code pr} by
     * default, see {@link Scenario#parse(String)}) and the families {@code smalt.jit.families} matches (all by default).
     *
     * <p>The children start when this method returns, {@code smalt.jit.parallelism} at a time (by default half the
     * processors, at most four); each child is single-threaded and compiles synchronously, so running several at once
     * changes only how long they take.
     *
     * @return the tests
     */
    public Stream<DynamicTest> tests() {
        var scenarios = Scenario.parse(System.getProperty("smalt.jit.scenarios", "pr"));
        var selected = only(System.getProperty("smalt.jit.families", ""));
        if (selected.families.isEmpty()) {
            throw new IllegalStateException("no kernel family found");
        }
        var parallelism = Integer.getInteger("smalt.jit.parallelism",
                Math.clamp(java.lang.Runtime.getRuntime().availableProcessors() / 2, 1, 4));
        var pool = java.util.concurrent.Executors.newFixedThreadPool(parallelism, task -> {
            var thread = new Thread(task, "kernel-guard");
            thread.setDaemon(true);
            return thread;
        });
        var tests = new ArrayList<DynamicTest>();
        for (var family : selected.families) {
            for (var scenario : scenarios) {
                var result = pool.submit(() -> selected.run(family, scenario));
                tests.add(DynamicTest.dynamicTest(family.name() + " [" + scenario.name() + "]", () -> {
                    var outcome = result.get();
                    if (!outcome.passed()) {
                        throw new AssertionError(outcome.describe());
                    }
                }));
            }
        }
        pool.shutdown();
        return tests.stream();
    }

    /**
     * Runs one family under one scenario.
     *
     * @param family the family
     * @param scenario the scenario
     * @return the result
     * @throws IOException if the child's files cannot be written or read
     * @throws InterruptedException if interrupted while waiting for the child
     */
    public Result run(Family family, Scenario scenario) throws IOException, InterruptedException {
        var directory = work.resolve(family.name().replace('+', '_')).resolve(scenario.name());
        Files.createDirectories(directory);
        var properties = directory.resolve("child.properties");
        var result = directory.resolve("result.tsv");
        Files.deleteIfExists(result);
        var trainingProperties = directory.resolve("training.properties");
        var trainingResult = directory.resolve("training.tsv");
        for (var run : List.of(List.of(properties, result, directory.resolve("child.jfr")),
                List.of(trainingProperties, trainingResult, directory.resolve("training.jfr")))) {
            Files.writeString(run.get(0), String.join("\n",
                    "tests=" + String.join(",", family.tests()),
                    "instrumented=" + String.join(",", family.instrumented()),
                    "kernels=" + String.join(",", family.kernels()),
                    "target=" + settings.target(), "cap=" + settings.cap(), "warmRounds=" + settings.warmRounds(),
                    "pollute=" + scenario.pollute(),
                    "result=" + run.get(1).toString().replace('\\', '/'),
                    "recording=" + run.get(2).toString().replace('\\', '/')) + "\n");
        }
        Files.write(directory.resolve("vector-methods.txt"), family.vectorMethods());
        var wide = new ArrayList<>(family.wideMethods());
        family.gated().forEach(kernel -> wide.add("class " + kernel));
        Files.write(directory.resolve("wide-methods.txt"), wide);
        var agentConfig = directory.resolve("agent.txt");
        var agentLines = new ArrayList<String>();
        family.types().forEach(type -> agentLines.add("type " + type));
        family.instrumented().forEach(test -> agentLines.add("test " + test));
        family.setup().forEach(method -> agentLines.add("setup " + method));
        Files.write(agentConfig, agentLines);
        // The test kit runs interpreted; the test methods calling a kernel compile without inlining it, so that each
        // kernel compiles on its own; C2's inlining and intrinsic decisions are printed for every other compilation of
        // a Smalt method.
        var directives = directory.resolve("directives.json");
        var callers = new ArrayList<String>();
        for (var caller : family.callers()) {
            callers.add("\"" + caller + "\"");
        }
        var kernels = new ArrayList<String>();
        for (var type : family.types()) {
            kernels.add("\"-" + type.replace('.', '/') + ".*\"");
        }
        Files.writeString(directives, "[\n"
                + "  { match: [\"com/github/auties00/smalt/testkit/*.*\"], Exclude: true },\n"
                + (callers.isEmpty() ? "" : "  { match: [" + String.join(", ", callers) + "], inline: ["
                + String.join(", ", kernels) + "] },\n")
                + "  { match: [\"com/github/auties00/smalt/*.*\"], c2: { PrintInlining: true, PrintIntrinsics: true } }\n"
                + "]\n");
        var settingsFile = directory.resolve("events.jfc");
        Files.writeString(settingsFile, """
                <?xml version="1.0" encoding="UTF-8"?>
                <configuration version="2.0">
                  <event name="jdk.Compilation"><setting name="enabled">true</setting><setting name="threshold">0 ms</setting></event>
                  <event name="jdk.CompilationFailure"><setting name="enabled">true</setting></event>
                  <event name="jdk.Deoptimization"><setting name="enabled">true</setting><setting name="stackTrace">false</setting></event>
                </configuration>
                """);
        var command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "--add-modules", "jdk.incubator.vector", "-XX:+UseSerialGC", "-Xmx1g", "-Xbatch",
                "-XX:+UnlockDiagnosticVMOptions", "-XX:CompilerDirectivesFile=" + directives,
                "-XX:+PrintCompilation", "-XX:+DisplayVMOutputToStderr",
                "-XX:StartFlightRecording=settings=" + settingsFile,
                "-javaagent:" + agentJar() + "=" + agentConfig,
                "-Dsmalt.threads=1", "-Djunit.jupiter.execution.parallel.enabled=false"));
        command.addAll(scenario.flags());
        command.addAll(settings.extraFlags());
        var problems = new ArrayList<String>();
        var entries = new ArrayList<String>();
        var childClassPath = classPath;
        var cache = directory.resolve("child.aot");
        var aotLog = directory.resolve("aot.log");
        if (scenario.cached()) {
            childClassPath = jarredClassPath();
            Files.deleteIfExists(cache);
            Files.deleteIfExists(trainingResult);
            var training = new ArrayList<>(command);
            training.addAll(List.of("-XX:AOTCacheOutput=" + cache, "-cp", childClassPath));
            if (smaltTrainingPresent()) {
                training.addAll(List.of(SMALT_TRAINING, "--max-seconds",
                        Integer.toString(Integer.getInteger("smalt.jit.aotTrainingSeconds", 60)), "--then"));
            }
            training.addAll(List.of(KernelGuardChild.class.getName(), trainingProperties.toString()));
            var trainingLog = directory.resolve("training-vm.log");
            var trainer = new ProcessBuilder(training).directory(directory.toFile())
                    .redirectOutput(directory.resolve("training-stdout.log").toFile())
                    .redirectError(trainingLog.toFile()).start();
            if (!trainer.waitFor(settings.timeout().toSeconds(), TimeUnit.SECONDS)) {
                trainer.destroyForcibly().waitFor();
            }
            if (!Files.exists(cache)) {
                problems.add("the training child recorded no AOT cache (exit code "
                        + (trainer.isAlive() ? "none" : trainer.exitValue()) + "); last output: " + tail(trainingLog, 15)
                        + "\ntraining command:\n  " + String.join(" ", training.stream().map(KernelGuard::quote).toList()));
                return new Result(family, scenario, List.copyOf(training), List.copyOf(problems), List.of(), directory);
            }
            command.addAll(List.of("-XX:AOTCache=" + cache, "-XX:AOTMode=on", "-Xlog:aot:file=" + aotLog.getFileName()));
        }
        command.addAll(List.of("-cp", childClassPath, KernelGuardChild.class.getName(), properties.toString()));
        var stdout = directory.resolve("stdout.log");
        var stderr = directory.resolve("vm.log");
        var process = new ProcessBuilder(command).directory(directory.toFile()).redirectOutput(stdout.toFile())
                .redirectError(stderr.toFile()).start();
        if (!process.waitFor(settings.timeout().toSeconds(), TimeUnit.SECONDS)) {
            process.destroyForcibly().waitFor();
            problems.add("the child did not finish within " + settings.timeout().toMinutes() + " minutes");
        }
        if (scenario.cached() && Files.exists(aotLog)
                && Files.readString(aotLog, java.nio.charset.StandardCharsets.ISO_8859_1)
                .contains("Using AOT-linked classes: false")) {
            entries.add("the AOT cache holds parsed classes only, without linked classes or profiles: this JDK does"
                    + " not archive the boot layer of a JVM that resolves jdk.incubator.vector");
        }
        var lines = Files.exists(result) ? Files.readAllLines(result) : List.<String>of();
        if (!lines.contains("done")) {
            problems.add("the child ended without a result (exit code " + (process.isAlive() ? "none" : process.exitValue())
                    + "); last output: " + tail(stderr, 15));
        }
        var measured = 0;
        var skipped = 0;
        var siteCalls = 0L;
        var measuredTests = -1L;
        for (var line : lines) {
            var fields = line.split("\t", -1);
            switch (fields[0]) {
                case "problem" -> problems.add(fields[1]);
                case "site-calls" -> siteCalls = Long.parseLong(fields[1]);
                case "extra-warm" -> entries.add("extra warm-up x" + fields[1] + " for: "
                        + (fields.length > 2 && !fields[2].isEmpty() ? fields[2] : "allocating calls"));
                case "round" -> {
                    // the last measured round decides: succeeded, failed, skipped and aborted tests
                    if (fields[1].startsWith("measure")) {
                        measuredTests = Long.parseLong(fields[2]) + Long.parseLong(fields[3]);
                    }
                }
                case "skipped" -> {
                    skipped++;
                    var dot = fields[1].lastIndexOf('.', fields[1].indexOf('('));
                    entries.add(simple(fields[1].substring(0, dot)) + fields[1].substring(dot)
                            + " not metered: needs vectors wider than the platform's");
                }
                case "failure" -> problems.add("test failed in round " + fields[1] + ": " + fields[2] + ": " + fields[3]);
                case "entry" -> {
                    var kernel = fields[1];
                    var method = fields[2];
                    var calls = Long.parseLong(fields[5]);
                    var allocating = Long.parseLong(fields[6]);
                    var bytes = Long.parseLong(fields[7]);
                    var name = simple(kernel) + "." + method;
                    entries.add(name + " counted=" + fields[3] + " warm=" + fields[4] + " measured=" + calls
                            + " c2=" + fields[9]);
                    measured += calls > 0 ? 1 : 0;
                    if (!Boolean.parseBoolean(fields[9])) {
                        problems.add(name + " has no C2 code when the measured round starts"
                                + (fields[12].isEmpty() ? "" : " (its calls cannot be repeated: " + fields[12] + ")"));
                    }
                    if (allocating > 0) {
                        problems.add(name + " allocated in " + allocating + " of " + calls + " measured calls ("
                                + bytes / allocating + " bytes per allocating call, at most " + fields[8]
                                + "), first at " + fields[11]);
                    }
                }
                default -> {
                }
            }
        }
        if (lines.contains("done") && siteCalls == 0 && measuredTests == 0) {
            entries.add("every test skipped itself: the kernels need vectors wider than the platform's");
        } else if (lines.contains("done") && siteCalls == 0) {
            problems.add("no instrumented kernel call ran: the tests call no kernel method the agent instruments");
        } else if (lines.contains("done") && measured == 0 && skipped == 0) {
            entries.add("no vector kernel was selected: at this vector width the tests' kernels are scalar");
        }
        if (scenario.seed() == 0) {
            problems.addAll(intrinsicFailures(stderr));
        }
        return new Result(family, scenario, List.copyOf(command), List.copyOf(problems), List.copyOf(entries),
                directory);
    }

    /**
     * Returns the vector intrinsics that {@code PrintInlining} reports as failed in the C2 compilations of Smalt
     * methods, each with the compilation it follows in the log.
     *
     * <p>A call of a {@code VectorSupport} method fails when the last outcome {@code PrintInlining} prints for it is a
     * failure: C2 then calls the allocating fallback. The {@code PrintIntrinsics} lines ({@code ** missing constant},
     * {@code ** unbox failed}) do not decide by themselves: C2 tries a vector intrinsic again, late, when its
     * arguments were not yet constants or unboxed values the first time, and the retry usually succeeds (it prints
     * {@code late inline succeeded} on the same call). Under the stress modes the check does not run: random late
     * inlining can use up the retries of an intrinsic whose arguments fold late, a property of the mode; the
     * allocation and output checks still decide what the compiled code does.
     */
    private static List<String> intrinsicFailures(Path log) throws IOException {
        if (!Files.exists(log)) {
            return List.of();
        }
        var found = new TreeMap<String, Set<String>>();
        var compilation = "an unknown compilation";
        try (var lines = Files.lines(log, java.nio.charset.StandardCharsets.ISO_8859_1)) {
            for (var line : (Iterable<String>) lines::iterator) {
                var compile = COMPILE_LINE.matcher(line);
                if (!line.contains("@ ") && compile.find()) {
                    if ("4".equals(compile.group(2))) {
                        compilation = compile.group(3);
                    }
                    continue;
                }
                var stripped = line.strip();
                var failed = false;
                if (stripped.contains("jdk.internal.vm.vector.VectorSupport::")) {
                    var outcomes = stripped.split(" {3}");
                    var last = outcomes[outcomes.length - 1].strip();
                    failed = last.startsWith("failed to inline") && !last.endsWith("trying again");
                }
                if (failed) {
                    found.computeIfAbsent(compilation, ignored -> new TreeSet<>()).add(stripped);
                }
            }
        }
        var problems = new ArrayList<String>();
        found.forEach((method, messages) -> problems.add("vector intrinsic failed in the C2 compilation of " + method
                + ": " + String.join(" / ", messages.stream().limit(4).toList())
                + (messages.size() > 4 ? " (+" + (messages.size() - 4) + " more)" : "")));
        return problems;
    }

    /** Discovers the kernel families. */
    private static List<Family> discover(List<Path> kernelRoots, List<Path> testRoots, Predicate<String> exclude) {
        var mainModels = new HashMap<String, ClassModel>();
        for (var root : kernelRoots) {
            classes(root).forEach(model -> mainModels.put(model.thisClass().asInternalName(), model));
        }
        var kernels = kernels(mainModels);
        var members = new HashMap<String, Set<String>>();
        var typesOf = new HashMap<String, Set<String>>();
        for (var kernel : kernels) {
            var nestedTypes = new TreeSet<String>();
            mainModels.keySet().stream().filter(name -> name.equals(kernel) || name.startsWith(kernel + "$"))
                    .forEach(nestedTypes::add);
            members.put(kernel, nestedTypes);
            var types = new LinkedHashSet<String>();
            nestedTypes.forEach(member -> supertypes(member, mainModels, types));
            typesOf.put(kernel, types);
        }
        var factories = factories(mainModels, kernels);
        var testModels = new TreeMap<String, ClassModel>();
        for (var root : testRoots) {
            classes(root).forEach(model -> testModels.put(model.thisClass().asInternalName(), model));
        }
        // A test class joins the families of the kernel classes it names; families sharing a test merge.
        var parent = new HashMap<String, String>();
        for (var kernel : kernels) {
            parent.put(kernel, kernel);
        }
        var testsOf = new TreeMap<String, Set<String>>();
        var nested = new TreeMap<String, Set<String>>();
        var junit = new TreeSet<String>();
        testModels.forEach((name, model) -> {
            var outer = name.contains("$") ? name.substring(0, name.indexOf('$')) : name;
            nested.computeIfAbsent(outer, ignored -> new TreeSet<>()).add(name.replace('/', '.'));
            if (junitTests(model)) {
                junit.add(outer);
            }
        });
        testModels.forEach((name, model) -> {
            var outer = name.contains("$") ? name.substring(0, name.indexOf('$')) : name;
            if (!junit.contains(outer) || exclude.test(outer.replace('/', '.'))) {
                return;
            }
            var called = new TreeSet<String>();
            for (var entry : model.constantPool()) {
                if (entry instanceof ClassEntry type && kernels.contains(outer(type.asInternalName()))) {
                    called.add(outer(type.asInternalName()));
                }
            }
            for (var method : model.methods()) {
                method.code().ifPresent(code -> {
                    for (var element : code) {
                        if (element instanceof java.lang.classfile.instruction.InvokeInstruction invoke) {
                            called.addAll(factories.getOrDefault(key(invoke), Set.of()));
                        }
                    }
                });
            }
            for (var kernel : called) {
                testsOf.computeIfAbsent(outer, ignored -> new TreeSet<>()).add(kernel);
            }
        });
        // A kernel class that another kernel calls (a helper of static methods) belongs to the caller's family.
        mainModels.forEach((name, model) -> {
            var caller = outer(name);
            if (!kernels.contains(caller)) {
                return;
            }
            for (var entry : model.constantPool()) {
                if (entry instanceof ClassEntry type && kernels.contains(outer(type.asInternalName()))
                        && !outer(type.asInternalName()).equals(caller)) {
                    parent.put(find(parent, outer(type.asInternalName())), find(parent, caller));
                }
            }
        });
        testsOf.forEach((test, called) -> {
            var first = find(parent, called.iterator().next());
            for (var kernel : called) {
                parent.put(find(parent, kernel), first);
            }
        });
        var groups = new TreeMap<String, Set<String>>();
        for (var kernel : kernels) {
            groups.computeIfAbsent(find(parent, kernel), ignored -> new TreeSet<>()).add(kernel);
        }
        var families = new ArrayList<Family>();
        for (var group : groups.values()) {
            var tests = new TreeSet<String>();
            testsOf.forEach((test, called) -> {
                if (called.stream().anyMatch(group::contains)) {
                    tests.add(test);
                }
            });
            if (tests.isEmpty()) {
                continue;
            }
            var types = new TreeSet<String>();
            group.forEach(kernel -> types.addAll(typesOf.get(kernel)));
            var instrumented = new TreeSet<String>();
            for (var test : tests) {
                // a test class extending another runs the superclass's test methods, which call the kernels
                for (var type = test; type != null && testModels.containsKey(type) && instrumented.add(type); ) {
                    type = testModels.get(type).superclass().map(ClassEntry::asInternalName).map(KernelGuard::outer)
                            .orElse(null);
                }
            }
            var callers = new TreeSet<String>();
            for (var test : instrumented) {
                for (var type : nested.getOrDefault(test, Set.of())) {
                    callers.addAll(callers(testModels.get(type.replace('.', '/')), types));
                }
            }
            var name = String.join("+", group.stream().map(KernelGuard::simple).toList());
            var kernelClasses = new TreeSet<String>();
            group.forEach(kernel -> kernelClasses.addAll(members.get(kernel)));
            var vectorMethods = new TreeSet<String>();
            var setup = new TreeSet<String>();
            var familyModels = new ArrayList<ClassModel>();
            mainModels.forEach((type, model) -> {
                if (group.contains(outer(type))) {
                    vectorMethods.addAll(vectorMethods(model));
                    setup.addAll(setupMethods(model));
                    familyModels.add(model);
                }
            });
            families.add(new Family(name, dotted(kernelClasses), dotted(types), List.copyOf(dotted(tests)),
                    dotted(instrumented), Set.copyOf(callers), Set.copyOf(vectorMethods), Set.copyOf(setup),
                    WideMethods.of(familyModels), WideMethods.gated(List.copyOf(mainModels.values()),
                    dotted(kernelClasses))));
        }
        return List.copyOf(families);
    }

    /**
     * Returns the kernel classes under the roots that no test calls, which the guard cannot cover.
     *
     * @param kernelRoots the directories holding the kernel classes
     * @param families the families found under them
     * @return the binary names of the uncovered kernel classes
     */
    public static Set<String> untested(List<Path> kernelRoots, List<Family> families) {
        var covered = new TreeSet<String>();
        families.forEach(family -> covered.addAll(family.kernels()));
        var models = new HashMap<String, ClassModel>();
        for (var root : kernelRoots) {
            classes(root).forEach(model -> models.put(model.thisClass().asInternalName(), model));
        }
        var missing = new TreeSet<String>();
        for (var kernel : kernels(models)) {
            if (!covered.contains(kernel.replace('/', '.'))) {
                missing.add(kernel.replace('/', '.'));
            }
        }
        return missing;
    }

    /**
     * Returns the kernel classes among the given classes: the top-level classes named {@code Vector*}, other than
     * interfaces, that call the Vector API themselves or through a nested class.
     */
    private static Set<String> kernels(Map<String, ClassModel> models) {
        var kernels = new TreeSet<String>();
        models.forEach((name, model) -> {
            var outer = outer(name);
            var top = models.get(outer);
            if (simple(outer).startsWith("Vector") && top != null && !top.flags().has(AccessFlag.INTERFACE)
                    && usesVectors(model)) {
                kernels.add(outer);
            }
        });
        return kernels;
    }

    /**
     * Returns the static methods of the given classes that create kernels, keyed as {@code owner.name(descriptor)},
     * with the kernels each creates: a method instantiating a kernel class, or calling such a method (a selector's
     * {@code vector()} calling a holder's {@code create()}). A test calling one tests the kernels it creates.
     */
    private static Map<String, Set<String>> factories(Map<String, ClassModel> models, Set<String> kernels) {
        var direct = new HashMap<String, Set<String>>();
        var calls = new HashMap<String, Set<String>>();
        models.forEach((name, model) -> {
            for (var method : model.methods()) {
                if (!method.flags().has(AccessFlag.STATIC) || method.methodName().equalsString("<clinit>")
                        || !method.methodType().stringValue().contains(")L")) {
                    continue;
                }
                var key = name + "." + method.methodName().stringValue() + method.methodType().stringValue();
                method.code().ifPresent(code -> {
                    for (var element : code) {
                        if (element instanceof java.lang.classfile.instruction.NewObjectInstruction created
                                && kernels.contains(outer(created.className().asInternalName()))) {
                            direct.computeIfAbsent(key, ignored -> new TreeSet<>())
                                    .add(outer(created.className().asInternalName()));
                        } else if (element instanceof java.lang.classfile.instruction.InvokeInstruction invoke
                                && invoke.opcode() == java.lang.classfile.Opcode.INVOKESTATIC) {
                            calls.computeIfAbsent(key, ignored -> new TreeSet<>()).add(key(invoke));
                        }
                    }
                });
            }
        });
        var factories = new HashMap<String, Set<String>>(direct);
        for (var changed = true; changed; ) {
            changed = false;
            for (var entry : calls.entrySet()) {
                for (var callee : entry.getValue()) {
                    var created = factories.get(callee);
                    if (created != null && factories.computeIfAbsent(entry.getKey(), ignored -> new TreeSet<>())
                            .addAll(created)) {
                        changed = true;
                    }
                }
            }
        }
        return factories;
    }

    private static String key(java.lang.classfile.instruction.InvokeInstruction invoke) {
        return invoke.owner().asInternalName() + "." + invoke.name().stringValue() + invoke.typeSymbol().descriptorString();
    }

    private static String outer(String name) {
        var dollar = name.indexOf('$');
        return dollar < 0 ? name : name.substring(0, dollar);
    }

    private static String find(Map<String, String> parent, String node) {
        var root = node;
        while (!parent.get(root).equals(root)) {
            root = parent.get(root);
        }
        return root;
    }

    private static void supertypes(String type, Map<String, ClassModel> models, Set<String> into) {
        if (!type.startsWith(SMALT) || !into.add(type)) {
            return;
        }
        var model = models.get(type);
        if (model == null) {
            return;
        }
        model.superclass().ifPresent(entry -> supertypes(entry.asInternalName(), models, into));
        for (var face : model.interfaces()) {
            supertypes(face.asInternalName(), models, into);
        }
    }

    /** Returns whether a class declares a JUnit Jupiter test method. */
    private static boolean junitTests(ClassModel model) {
        for (var method : model.methods()) {
            for (var attribute : method.attributes()) {
                if (attribute instanceof java.lang.classfile.attribute.RuntimeVisibleAnnotationsAttribute annotations
                        && annotations.annotations().stream().anyMatch(annotation -> annotation.className()
                        .stringValue().startsWith("Lorg/junit/jupiter/"))) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Returns the methods of a test class that call a kernel type the way {@link KernelMeterAgent} instruments, as
     * {@code package/Class.method}.
     */
    private static Set<String> callers(ClassModel model, Set<String> types) {
        var methods = new TreeSet<String>();
        for (var method : model.methods()) {
            method.code().ifPresent(code -> {
                for (var element : code) {
                    if (element instanceof java.lang.classfile.instruction.InvokeInstruction invoke
                            && invoke.opcode() != java.lang.classfile.Opcode.INVOKESPECIAL
                            && types.contains(invoke.owner().asInternalName())) {
                        methods.add(model.thisClass().asInternalName() + "." + method.methodName().stringValue());
                        break;
                    }
                }
            });
        }
        return methods;
    }

    /** Returns the methods of a class marked {@code KernelSetup}, as {@code name(descriptor)}. */
    private static Set<String> setupMethods(ClassModel model) {
        var methods = new TreeSet<String>();
        for (var method : model.methods()) {
            for (var attribute : method.attributes()) {
                if (attribute instanceof java.lang.classfile.attribute.RuntimeInvisibleAnnotationsAttribute annotations
                        && annotations.annotations().stream().anyMatch(annotation -> annotation.className()
                        .equalsString("Lcom/github/auties00/smalt/internal/simd/KernelSetup;"))) {
                    methods.add(method.methodName().stringValue() + method.methodType().stringValue());
                }
            }
        }
        return methods;
    }

    /** Returns the methods of a class that call the Vector API, as {@code Class.name(descriptor)}. */
    private static Set<String> vectorMethods(ClassModel model) {
        var methods = new TreeSet<String>();
        for (var method : model.methods()) {
            method.code().ifPresent(code -> {
                for (var element : code) {
                    if (element instanceof java.lang.classfile.instruction.InvokeInstruction invoke
                            && invoke.owner().asInternalName().startsWith("jdk/incubator/vector/")) {
                                methods.add(model.thisClass().asInternalName().replace('/', '.') + "."
                                + method.methodName().stringValue() + method.methodType().stringValue());
                        break;
                    }
                }
            });
        }
        return methods;
    }

    private static boolean usesVectors(ClassModel model) {
        for (var entry : model.constantPool()) {
            if (entry instanceof ClassEntry type && type.asInternalName().startsWith("jdk/incubator/vector/")) {
                return true;
            }
        }
        return false;
    }

    private static List<ClassModel> classes(Path root) {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (var files = Files.walk(root)) {
            var models = new ArrayList<ClassModel>();
            for (var file : files.filter(path -> path.toString().endsWith(".class")
                    && !path.getFileName().toString().equals("module-info.class")).toList()) {
                models.add(ClassFile.of().parse(Files.readAllBytes(file)));
            }
            return models;
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static Set<String> dotted(Collection<String> names) {
        var result = new TreeSet<String>();
        names.forEach(name -> result.add(name.replace('/', '.')));
        return result;
    }

    private static String simple(String name) {
        return name.substring(Math.max(name.lastIndexOf('.'), name.lastIndexOf('/')) + 1);
    }

    private static String tail(Path file, int count) throws IOException {
        if (!Files.exists(file)) {
            return "(none)";
        }
        var lines = Files.readAllLines(file, java.nio.charset.StandardCharsets.ISO_8859_1).stream()
                .filter(line -> !line.isBlank() && !COMPILE_LINE.matcher(line).find()).toList();
        return String.join(" | ", lines.subList(Math.max(0, lines.size() - count), lines.size()));
    }

    private static String quote(String argument) {
        return argument.contains(" ") ? "\"" + argument + "\"" : argument;
    }

    /** {@return whether {@code smalt-aot}'s training entry point is on the class path} */
    private static boolean smaltTrainingPresent() {
        try {
            Class.forName(SMALT_TRAINING, false, KernelGuard.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException exception) {
            return false;
        }
    }

    /**
     * Returns the children's class path with every directory replaced by a jar of its contents, written once per JVM
     * under the work directory: a JDK AOT cache records classes from jars only.
     */
    private String jarredClassPath() throws IOException {
        synchronized (JARRED) {
            var key = classPath + java.io.File.pathSeparator + work;
            var known = JARRED.get(key);
            if (known != null) {
                return known;
            }
            var directory = work.resolve("aot-class-path");
            Files.createDirectories(directory);
            var entries = new ArrayList<String>();
            var index = 0;
            for (var entry : classPath.split(java.io.File.pathSeparator)) {
                if (entry.isBlank()) {
                    continue;
                }
                var path = Path.of(entry);
                if (!Files.isDirectory(path)) {
                    entries.add(entry);
                    continue;
                }
                var jar = directory.resolve(index++ + "-" + path.getFileName() + ".jar");
                try (var out = new JarOutputStream(Files.newOutputStream(jar));
                     var files = Files.walk(path)) {
                    for (var file : (Iterable<Path>) files.sorted()::iterator) {
                        var name = path.relativize(file).toString().replace('\\', '/');
                        if (name.isEmpty()) {
                            continue;
                        }
                        if (Files.isDirectory(file)) {
                            out.putNextEntry(new ZipEntry(name + "/"));
                        } else {
                            out.putNextEntry(new ZipEntry(name));
                            Files.copy(file, out);
                        }
                        out.closeEntry();
                    }
                }
                entries.add(jar.toString());
            }
            var jarred = String.join(java.io.File.pathSeparator, entries);
            JARRED.put(key, jarred);
            return jarred;
        }
    }

    /** Returns the agent jar, written once per JVM from the agent's classes on the class path. */
    private static Path agentJar() throws IOException {
        synchronized (AGENT_LOCK) {
            if (agent != null) {
                return agent;
            }
            var jar = Files.createTempFile("smalt-kernel-meter", ".jar");
            jar.toFile().deleteOnExit();
            var manifest = new Manifest();
            manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
            manifest.getMainAttributes().put(new Attributes.Name("Premain-Class"), KernelMeterAgent.class.getName());
            try (var out = new JarOutputStream(Files.newOutputStream(jar), manifest)) {
                // The agent's classes stay on the children's class path; the jar only names the premain class.
                out.putNextEntry(new ZipEntry("META-INF/smalt-kernel-meter"));
                out.closeEntry();
            }
            agent = jar;
            return agent;
        }
    }

    /** The JVM flags of the host, read once from {@code -XX:+PrintFlagsFinal}. */
    private static final class HostFlags {
        private static Map<String, String> flags;

        static synchronized Map<String, String> get() {
            if (flags != null) {
                return flags;
            }
            try {
                var process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                        "-XX:+PrintFlagsFinal", "-version").redirectErrorStream(true).start();
                var output = new String(process.getInputStream().readAllBytes());
                process.waitFor();
                var parsed = new HashMap<String, String>();
                for (var line : output.lines().toList()) {
                    var fields = line.strip().split("\\s+");
                    if (fields.length >= 4 && fields[2].equals("=")) {
                        parsed.put(fields[1], fields[3]);
                    }
                }
                flags = Map.copyOf(parsed);
                return flags;
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(exception);
            }
        }
    }
}
