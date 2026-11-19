package com.github.auties00.smalt.lint;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.analysis.AnalyzerException;

import java.io.File;
import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * The static lint of Smalt's Vector API kernels: checks the compiled classes against the {@link Rule rules} and
 * prints every finding as {@code rule Owner.method(File.java:line): what; how to fix}.
 *
 * <p>Usage: {@code KernelLint [--baseline <tsv>] [--update-baseline] [--no-floor] <class directory or jar>...}, or
 * {@code --scan-classpath} for every Smalt artifact on the class path (main classes only: test class directories,
 * test jars and the test kit are left out). The exit status is 0 when nothing is reported and 1 otherwise.
 *
 * <p>A finding is accepted in one of two ways only:
 * <ul>
 *   <li>a {@code KernelLintSuppress} annotation (smalt-core's {@code internal.simd}) on the method, naming the rule
 *       and giving a reason; a suppression that names an unknown rule, gives no reason, or suppresses nothing is a
 *       finding itself, and a method carrying one is also checked for the calls the build's forbidden-apis bans,
 *       which skip such a method whole ({@link BannedCalls}), so that it accepts only the rules it names;</li>
 *   <li>a row {@code rule<TAB>binary.Name#method<TAB>count} of the baseline ({@code tools/lint/kernel-baseline.tsv}),
 *       which may only shrink: a method with more findings of a rule than its row allows fails, and so does a row
 *       that allows more than the method has ({@code --update-baseline} lowers such rows and never raises one).</li>
 * </ul>
 * Unless {@code --no-floor} is given, the run also fails when it finds fewer than {@value #MIN_CLASSES} classes using
 * the Vector API or fewer than {@value #MIN_CALLS} calls of it, so that a filter bug that skips the library cannot
 * pass.
 */
public final class KernelLint {
    /**
     * The largest bytecode length, in bytes, of a method calling the Vector API. A house limit below HotSpot's own
     * ({@code HugeMethodLimit}, 8000 bytes, past which C2 does not compile a method at all): the larger a kernel, the
     * likelier C2 runs out of inlining budget before it reaches the Vector API calls.
     */
    static final int MAX_KERNEL_BYTES = 2000;

    /** The fewest classes using the Vector API that a run over the library must find. */
    static final int MIN_CLASSES = 10;

    /** The fewest Vector API calls that a run over the library must analyze. */
    static final int MIN_CALLS = 1000;

    private static final String SUPPRESS = "Lcom/github/auties00/smalt/internal/simd/KernelLintSuppress;";

    private KernelLint() {
        throw new AssertionError();
    }

    /**
     * The outcome of a run.
     *
     * @param findings every finding, before suppressions and the baseline
     * @param reported the findings that fail the run
     * @param classes the classes using the Vector API that were analyzed
     * @param calls the Vector API calls that were analyzed
     * @param baseline the baseline after the run, lowered where the library improved
     */
    public record Result(List<Finding> findings, List<Finding> reported, int classes, int calls,
                         Map<String, Integer> baseline) {
    }

    /**
     * Runs the lint from the command line.
     *
     * @param arguments the options and the class directories and jars
     * @throws IOException if a class or the baseline cannot be read or written
     */
    public static void main(String... arguments) throws IOException {
        Path baseline = null;
        var update = false;
        var floor = true;
        var roots = new ArrayList<Path>();
        for (var index = 0; index < arguments.length; index++) {
            switch (arguments[index]) {
                case "--baseline" -> baseline = Path.of(arguments[++index]);
                case "--update-baseline" -> update = true;
                case "--no-floor" -> floor = false;
                case "--scan-classpath" -> roots.addAll(classPathRoots(System.getProperty("java.class.path")));
                default -> roots.add(Path.of(arguments[index]));
            }
        }
        var allowed = baseline == null ? Map.<String, Integer>of() : readBaseline(baseline);
        var result = run(roots, allowed);
        for (var finding : result.reported()) {
            System.out.println(finding.format());
        }
        var problems = new ArrayList<String>();
        if (floor && result.classes() < MIN_CLASSES) {
            problems.add("found only " + result.classes() + " classes using the Vector API under " + roots);
        }
        if (floor && result.calls() < MIN_CALLS) {
            problems.add("analyzed only " + result.calls() + " Vector API calls under " + roots);
        }
        problems.forEach(System.out::println);
        if (update && baseline != null) {
            writeBaseline(baseline, result.baseline());
        }
        var rules = result.reported().stream().map(Finding::rule).distinct().count();
        var classes = result.reported().stream().map(Finding::owner).distinct().count();
        System.out.println("kernel lint: " + result.reported().size() + " findings of " + rules + " rules in " + classes
                + " classes (" + result.classes() + " classes and " + result.calls() + " Vector API calls analyzed)");
        if (!result.reported().isEmpty() || !problems.isEmpty()) {
            System.exit(1);
        }
    }

    /**
     * Lints the classes under some roots.
     *
     * @param roots class directories and jars
     * @param baseline the accepted findings per {@code rule<TAB>binary.Name#method}
     * @return the result
     * @throws IOException if a class cannot be read
     */
    public static Result run(List<Path> roots, Map<String, Integer> baseline) throws IOException {
        var findings = new ArrayList<Finding>();
        var suppressions = new LinkedHashMap<String, Suppression>();
        var models = new ArrayList<ClassModel>();
        var classes = 0;
        var calls = 0;
        for (var bytes : classFiles(roots)) {
            models.add(ClassFile.of().parse(bytes));
            var node = new ClassNode();
            new ClassReader(bytes).accept(node, 0);
            for (var method : node.methods) {
                if (suppressions(node, method, suppressions)) {
                    findings.addAll(BannedCalls.check(node, method));
                }
            }
            if (!usesVectors(node)) {
                continue;
            }
            classes++;
            for (var method : node.methods) {
                for (var instruction : method.instructions) {
                    if (instruction instanceof MethodInsnNode call && call.owner.startsWith("jdk/incubator/vector/")) {
                        calls++;
                    }
                }
                try {
                    findings.addAll(MethodRules.check(node, method, codeLength(bytes, method.name, method.desc)));
                } catch (AnalyzerException exception) {
                    throw new IllegalStateException("cannot analyze " + node.name + "." + method.name, exception);
                }
            }
        }
        var wide = new WideSpecies(models);
        findings.addAll(wide.findings());
        findings.sort(Comparator.comparing(Finding::owner).thenComparing(Finding::method)
                .thenComparingInt(Finding::line).thenComparing(finding -> finding.rule().id()));
        return apply(findings, suppressions, baseline, classes, calls);
    }

    /** A {@code KernelLintSuppress} on a method. */
    private record Suppression(Finding place, List<String> rules, String reason, Map<String, Boolean> used) {
    }

    /** Applies the suppressions and the baseline. */
    private static Result apply(List<Finding> findings, Map<String, Suppression> suppressions,
                                Map<String, Integer> baseline, int classes, int calls) {
        var unsuppressed = new ArrayList<Finding>();
        var reported = new ArrayList<Finding>();
        for (var finding : findings) {
            var suppression = suppressions.get(finding.methodKey());
            if (suppression != null && suppression.rules().contains(finding.rule().id())
                    && !suppression.reason().isBlank()) {
                suppression.used().put(finding.rule().id(), true);
            } else {
                unsuppressed.add(finding);
            }
        }
        for (var suppression : suppressions.values()) {
            var place = suppression.place();
            for (var id : suppression.rules()) {
                if (Rule.of(id) == null || suppression.reason().isBlank()) {
                    reported.add(new Finding(Rule.BAD_SUPPRESSION, place.owner(), place.method(), place.file(),
                            place.line(), "(" + id + ")"));
                } else if (!suppression.used().containsKey(id)) {
                    reported.add(new Finding(Rule.STALE_SUPPRESSION, place.owner(), place.method(), place.file(),
                            place.line(), "(" + id + ")"));
                }
            }
        }
        var groups = new TreeMap<String, List<Finding>>();
        for (var finding : unsuppressed) {
            groups.computeIfAbsent(finding.rule().id() + "\t" + finding.methodKey(), ignored -> new ArrayList<>())
                    .add(finding);
        }
        var lowered = new TreeMap<String, Integer>();
        groups.forEach((key, group) -> {
            var allowed = baseline.getOrDefault(key, 0);
            if (group.size() > allowed) {
                reported.addAll(group);
            }
            if (allowed > 0) {
                lowered.put(key, Math.min(allowed, group.size()));
            }
        });
        baseline.forEach((key, allowed) -> {
            var found = groups.getOrDefault(key, List.of()).size();
            if (found < allowed) {
                var tab = key.indexOf('\t');
                var hash = key.indexOf('#', tab);
                reported.add(new Finding(Rule.STALE_BASELINE, key.substring(tab + 1, hash), key.substring(hash + 1),
                        null, -1, "(" + key.substring(0, tab) + ": " + allowed + " allowed, " + found + " found)"));
            }
        });
        lowered.values().removeIf(count -> count == 0);
        return new Result(List.copyOf(findings), List.copyOf(reported), classes, calls, lowered);
    }

    /** Records a method's {@code KernelLintSuppress}, if it has one, and returns whether it has. */
    private static boolean suppressions(ClassNode owner, org.objectweb.asm.tree.MethodNode method,
                                        Map<String, Suppression> into) {
        if (method.invisibleAnnotations == null) {
            return false;
        }
        var found = false;
        var line = -1;
        for (var instruction : method.instructions) {
            if (instruction instanceof org.objectweb.asm.tree.LineNumberNode number) {
                line = number.line;
                break;
            }
        }
        for (var annotation : method.invisibleAnnotations) {
            if (!annotation.desc.equals(SUPPRESS)) {
                continue;
            }
            var rules = new ArrayList<String>();
            var reason = "";
            if (annotation.values != null) {
                for (var index = 0; index + 1 < annotation.values.size(); index += 2) {
                    var name = (String) annotation.values.get(index);
                    var value = annotation.values.get(index + 1);
                    if (name.equals("value") && value instanceof List<?> list) {
                        list.forEach(rule -> rules.add(String.valueOf(rule)));
                    } else if (name.equals("reason")) {
                        reason = String.valueOf(value);
                    }
                }
            }
            var place = new Finding(Rule.STALE_SUPPRESSION, owner.name.replace('/', '.'), method.name, owner.sourceFile,
                    line, "");
            into.put(place.methodKey(), new Suppression(place, List.copyOf(rules), reason, new HashMap<>()));
            found = true;
        }
        return found;
    }

    /** Returns the bytecode length of a method, read with the ClassFile API. */
    private static int codeLength(byte[] bytes, String name, String descriptor) {
        for (var method : ClassFile.of().parse(bytes).methods()) {
            if (method.methodName().equalsString(name) && method.methodType().equalsString(descriptor)) {
                return method.code().map(code -> ((java.lang.classfile.attribute.CodeAttribute) code).codeLength())
                        .orElse(0);
            }
        }
        return 0;
    }

    private static boolean usesVectors(ClassNode node) {
        for (var method : node.methods) {
            for (var instruction : method.instructions) {
                if (instruction instanceof MethodInsnNode call && call.owner.startsWith("jdk/incubator/vector/")) {
                    return true;
                }
            }
            if (method.desc.contains("Ljdk/incubator/vector/")) {
                return true;
            }
        }
        return false;
    }

    /** Returns the class files of Smalt's main code under the roots. */
    private static List<byte[]> classFiles(List<Path> roots) throws IOException {
        var files = new ArrayList<byte[]>();
        for (var root : roots) {
            if (Files.isDirectory(root)) {
                walk(root, files);
            } else if (Files.isRegularFile(root)) {
                try (var jar = FileSystems.newFileSystem(root)) {
                    walk(jar.getPath("/"), files);
                }
            }
        }
        return files;
    }

    private static void walk(Path root, List<byte[]> into) throws IOException {
        var base = root.resolve("com").resolve("github").resolve("auties00").resolve("smalt");
        if (!Files.isDirectory(base)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(base)) {
            for (var path : paths.sorted().toList()) {
                var relative = base.relativize(path).toString().replace('\\', '/');
                if (relative.endsWith(".class") && !relative.startsWith("testkit/")
                        && !path.getFileName().toString().equals("module-info.class")) {
                    into.add(Files.readAllBytes(path));
                }
            }
        }
    }

    /**
     * Returns the Smalt main artifacts of a class path: jars named {@code smalt-*.jar} other than test jars and this
     * lint, and {@code target/classes} directories.
     */
    static List<Path> classPathRoots(String classPath) {
        var roots = new ArrayList<Path>();
        for (var entry : classPath.split(File.pathSeparator)) {
            var path = Path.of(entry);
            var name = path.getFileName() == null ? "" : path.getFileName().toString();
            var jar = name.startsWith("smalt-") && name.endsWith(".jar") && !name.endsWith("-tests.jar")
                    && !name.startsWith("smalt-kernel-lint");
            var classes = name.equals("classes") && path.getParent() != null
                    && path.getParent().getFileName().toString().equals("target")
                    && !path.getParent().getParent().getFileName().toString().equals("kernel-lint");
            if (jar || classes) {
                roots.add(path);
            }
        }
        return roots;
    }

    /**
     * Reads a baseline.
     *
     * @param file the TSV file; a missing file is an empty baseline
     * @return the allowed counts per {@code rule<TAB>binary.Name#method}
     * @throws IOException if the file cannot be read
     */
    static Map<String, Integer> readBaseline(Path file) throws IOException {
        var baseline = new TreeMap<String, Integer>();
        if (!Files.exists(file)) {
            return baseline;
        }
        for (var line : Files.readAllLines(file)) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            var fields = line.split("\t");
            if (fields.length != 3) {
                throw new IllegalArgumentException("malformed baseline row: " + line);
            }
            baseline.put(fields[0] + "\t" + fields[1], Integer.parseInt(fields[2]));
        }
        return baseline;
    }

    private static void writeBaseline(Path file, Map<String, Integer> baseline) throws IOException {
        var lines = new ArrayList<String>();
        lines.add("# Accepted kernel lint findings: rule, binary.Name#method, count. Shrink-only: see KernelLint.");
        new TreeSet<>(baseline.keySet()).forEach(key -> lines.add(key + "\t" + baseline.get(key)));
        Files.write(file, lines);
    }
}
