package com.github.auties00.smalt.testkit.jit;

import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;

import jdk.jfr.FlightRecorder;

import javax.management.ObjectName;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;

/**
 * The child JVM of a {@link KernelGuard} run: runs one family's equivalence tests in fixed rounds and writes what
 * the {@link KernelMeter} saw.
 *
 * <p>The rounds are: an optional {@linkplain VectorShapes#pollute(int) profile pollution}; a counting round; the warm-up
 * rounds, in which every metered call repeats until its entry point has about {@link KernelMeter#target()} calls per
 * round; a check of which entry points C2 has compiled ({@code Compiler.codelist}); and the measured round, in which
 * every kernel call must allocate nothing while the tests check its output against the scalar twin. The only
 * argument is a properties file naming the tests, the kernels, the counts and the result file.
 */
public final class KernelGuardChild {
    /** The largest repetition factor of the extra warm-up rounds (at most three rounds, at 4, 16 and 64). */
    private static final int MAX_FACTOR = 64;

    private KernelGuardChild() {
        throw new AssertionError();
    }

    /**
     * Runs the rounds.
     *
     * @param arguments the path of the properties file
     * @throws Exception if the properties or the result cannot be read or written
     */
    public static void main(String... arguments) throws Exception {
        var settings = new Properties();
        try (var reader = Files.newBufferedReader(Path.of(arguments[0]))) {
            settings.load(reader);
        }
        var tests = List.of(settings.getProperty("tests").split(","));
        KernelMeter.kernels(Set.of(settings.getProperty("kernels").split(",")));
        if (!com.github.auties00.smalt.internal.simd.Simd.WIDE) {
            KernelMeter.unmetered(Set.copyOf(Files.readAllLines(Path.of(arguments[0]).resolveSibling("wide-methods.txt"))));
        }
        KernelMeter.target(Integer.parseInt(settings.getProperty("target", "12000")),
                Integer.parseInt(settings.getProperty("cap", "20000")));
        var warmRounds = Integer.parseInt(settings.getProperty("warmRounds", "2"));
        var pollution = Integer.parseInt(settings.getProperty("pollute", "0"));
        var out = new ArrayList<String>();
        out.add("start\t" + Instant.now());
        if (pollution > 0) {
            VectorShapes.pollute(pollution);
            out.add("polluted\t" + pollution);
        }
        KernelMeter.mode(KernelMeter.Mode.COUNT);
        out.addAll(run(tests, "count"));
        var kernels = Set.of(settings.getProperty("kernels").split(","));
        KernelMeter.mode(KernelMeter.Mode.WARM);
        for (var round = 1; round <= warmRounds; round++) {
            out.addAll(run(tests, "warm" + round));
        }
        // A helper that only some calls reach can still run on its first-tier code when the measured round starts:
        // while a measured call allocates or an entry point has no C2 code, warm up again with four times the
        // repetitions and measure again, at most three times. Only the last measured round counts.
        var vectorMethods = Set.copyOf(Files.readAllLines(Path.of(arguments[0]).resolveSibling("vector-methods.txt")));
        Set<String> compiledBefore;
        Instant measureStart;
        Instant measureEnd;
        for (var factor = 1; ; factor *= 4) {
            if (factor > 1) {
                out.add("extra-warm\t" + factor + "\t" + String.join(" ", stuck(vectorMethods)));
                KernelMeter.multiplier(factor);
                KernelMeter.mode(KernelMeter.Mode.WARM);
                out.addAll(run(tests, "warm-x" + factor));
            }
            KernelMeter.mode(KernelMeter.Mode.OFF);
            compiledBefore = compiled();
            KernelMeter.clearMeasurements();
            measureStart = Instant.now();
            KernelMeter.mode(KernelMeter.Mode.MEASURE);
            out.addAll(run(tests, factor == 1 ? "measure" : "measure-x" + factor));
            KernelMeter.mode(KernelMeter.Mode.OFF);
            measureEnd = Instant.now();
            var known = compiledBefore;
            var steady = KernelMeter.entries().stream().allMatch(entry -> entry.skipped()
                    || (entry.allocatingCalls() == 0 && known.contains(entry.declaring() + "." + entry.method())));
            if (steady || factor >= MAX_FACTOR) {
                if (!steady) {
                    var stuck = stuck(vectorMethods);
                    if (!stuck.isEmpty()) {
                        out.add("problem\tkernel methods still without C2 code: " + String.join(" ", stuck));
                    }
                }
                break;
            }
        }
        var compiledAfter = compiled();
        var recordings = FlightRecorder.getFlightRecorder().getRecordings();
        if (recordings.isEmpty()) {
            out.add("problem\tthe child has no flight recording");
        } else {
            var recording = Path.of(settings.getProperty("recording"));
            recordings.getFirst().dump(recording);
            var instrumented = List.of(settings.getProperty("instrumented").split(","));
            for (var problem : CompilationEvents.problems(recording, kernels, type -> instrumented.stream()
                    .anyMatch(test -> type.equals(test) || type.startsWith(test + "$")), measureStart, measureEnd)) {
                out.add("problem\t" + clean(problem));
            }
        }
        for (var entry : KernelMeter.entries()) {
            if (entry.skipped()) {
                out.add("skipped\t" + entry.kernel() + "." + entry.method());
                continue;
            }
            var key = entry.declaring() + "." + entry.method();
            out.add(String.join("\t", "entry", entry.kernel(), entry.method(), Long.toString(entry.counted()),
                    Long.toString(entry.warmCalls()), Long.toString(entry.calls()), Long.toString(entry.allocatingCalls()),
                    Long.toString(entry.bytes()), Long.toString(entry.maxBytes()),
                    Boolean.toString(compiledBefore.contains(key)), Boolean.toString(compiledAfter.contains(key)),
                    clean(entry.firstAllocatingSite()), clean(entry.unrestorable())));
        }
        out.add("site-calls\t" + KernelMeter.siteCalls());
        out.add("done");
        Files.write(Path.of(settings.getProperty("result")), out);
    }

    /** Runs the tests once and returns a line per failure. */
    private static List<String> run(List<String> tests, String round) {
        var request = LauncherDiscoveryRequestBuilder.request()
                .selectors(tests.stream().map(DiscoverySelectors::selectClass).toList())
                .configurationParameter("junit.jupiter.execution.parallel.enabled", "false")
                .build();
        var listener = new SummaryGeneratingListener();
        var started = System.nanoTime();
        LauncherFactory.create().execute(request, listener);
        var millis = (System.nanoTime() - started) / 1_000_000;
        var summary = listener.getSummary();
        var lines = new ArrayList<String>();
        lines.add("round\t" + round + "\t" + summary.getTestsSucceededCount() + "\t" + summary.getTestsFailedCount()
                + "\t" + summary.getTestsSkippedCount() + "\t" + summary.getTestsAbortedCount() + "\t" + millis + " ms");
        for (var failure : summary.getFailures()) {
            var trace = new java.io.StringWriter();
            failure.getException().printStackTrace(new java.io.PrintWriter(trace));
            lines.add("failure\t" + round + "\t" + clean(failure.getTestIdentifier().getUniqueId()) + "\t"
                    + clean(trace.toString().lines().limit(12).reduce((a, b) -> a + " | " + b).orElse("")));
        }
        return lines;
    }

    /** Returns the methods that have a C2 (level 4) nmethod in use, as {@code Class.name(descriptor)}. */
    private static Set<String> compiled() throws Exception {
        var methods = new HashSet<String>();
        codelist().forEach((method, levels) -> {
            if (levels.contains(4)) {
                methods.add(method);
            }
        });
        return methods;
    }

    /** Returns the kernel methods calling the Vector API that have first-tier code in use and no C2 code. */
    private static Set<String> stuck(Set<String> vectorMethods) throws Exception {
        var methods = new java.util.TreeSet<String>();
        codelist().forEach((method, levels) -> {
            if (vectorMethods.contains(method) && !levels.contains(4)) {
                methods.add(method);
            }
        });
        return methods;
    }

    /** Returns the compilation levels of the nmethods in use, per method ({@code Compiler.codelist}). */
    private static java.util.Map<String, Set<Integer>> codelist() throws Exception {
        var listing = (String) ManagementFactory.getPlatformMBeanServer().invoke(
                new ObjectName("com.sun.management:type=DiagnosticCommand"), "compilerCodelist",
                new Object[]{null}, new String[]{String[].class.getName()});
        var methods = new java.util.HashMap<String, Set<Integer>>();
        for (var line : listing.lines().toList()) {
            var fields = line.strip().split(" ");
            if (fields.length >= 4 && fields[2].equals("0") && fields[3].contains("(")) {
                methods.computeIfAbsent(fields[3], ignored -> new HashSet<>()).add(Integer.parseInt(fields[1]));
            }
        }
        return methods;
    }

    private static String clean(String text) {
        return text == null ? "" : text.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
    }
}
