package com.github.auties00.smalt.lint;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.ToolProvider;
import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the kernel lint against fixture kernels compiled at test time: every line marked {@code // expect: <rule>}
 * must be reported with that rule, and no other line may be reported. The fixtures hold a good and a bad kernel per
 * rule, the two joins the linear analysis this lint replaces missed (a ternary whose exact arm falls through, an
 * if/else assignment), lambdas, switch loops, handlers inside loops and operators held in locals; the kernel-size
 * fixtures are generated just over and just under the limit.
 */
@DisplayName("kernel lint")
class KernelLintTest {
    private static final Pattern EXPECT = Pattern.compile("// expect: ([a-z, -]+)$");
    private static final Path FIXTURES = Path.of("src", "test", "fixtures");

    @TempDir
    static Path work;

    private static Path classes;
    private static KernelLint.Result result;

    @BeforeAll
    static void compile() throws IOException {
        var sources = Files.createDirectories(work.resolve("sources"));
        try (Stream<Path> files = Files.walk(FIXTURES)) {
            for (var file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                var target = sources.resolve(FIXTURES.relativize(file).toString());
                Files.createDirectories(target.getParent());
                Files.copy(file, target);
            }
        }
        var generated = sources.resolve("com/github/auties00/smalt/fixture/VectorSizesLarge.java");
        Files.writeString(generated, largeKernels());
        classes = Files.createDirectories(work.resolve("classes"));
        List<String> paths;
        try (Stream<Path> files = Files.walk(sources)) {
            paths = files.filter(path -> path.toString().endsWith(".java")).map(Path::toString).toList();
        }
        var arguments = new ArrayList<>(List.of("--release", "26", "--add-modules", "jdk.incubator.vector", "-g",
                "-nowarn", "-XDsuppressNotes", "-d", classes.toString()));
        arguments.addAll(paths);
        var errors = new java.io.ByteArrayOutputStream();
        var status = ToolProvider.getSystemJavaCompiler().run(null, errors, errors, arguments.toArray(String[]::new));
        assertEquals(0, status, "the fixtures do not compile: " + errors);
        result = KernelLint.run(List.of(classes), Map.of());
    }

    /**
     * Returns a class with one kernel just over the size limit and one just under it; the line of the first
     * statement of the large one carries the marker.
     */
    private static String largeKernels() {
        var source = new StringBuilder("""
                package com.github.auties00.smalt.fixture;

                import jdk.incubator.vector.IntVector;
                import jdk.incubator.vector.VectorOperators;
                import jdk.incubator.vector.VectorSpecies;

                final class VectorSizesLarge {
                    static final VectorSpecies<Integer> S = IntVector.SPECIES_128;
                    @SuppressWarnings("unchecked")
                    static final Class<IntVector> INTS = (Class<IntVector>) S.vectorType();
                """);
        for (var kernel : List.of("over", "under")) {
            source.append("    static void ").append(kernel).append("(int[] a) {\n");
            source.append("        var v = INTS.cast(IntVector.fromArray(S, a, 0));")
                    .append(kernel.equals("over") ? " // expect: kernel-size" : "").append('\n');
            // each statement is 18 bytes of bytecode (getstatic, aload, invokevirtual, checkcast, getstatic, aload,
            // invokevirtual, astore); sizes() checks the two straddle the limit
            var statements = kernel.equals("over") ? 112 : 107;
            for (var i = 0; i < statements; i++) {
                source.append("        v = INTS.cast(v).lanewise(VectorOperators.ADD, v);\n");
            }
            source.append("        INTS.cast(v).intoArray(a, 4);\n    }\n");
        }
        return source.append("}\n").toString();
    }

    /** Returns the markers of the fixtures, as {@code File.java:line rule}. */
    private static Set<String> expected() throws IOException {
        var markers = new TreeSet<String>();
        try (Stream<Path> files = Files.walk(work.resolve("sources"))) {
            for (var file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                var lines = Files.readAllLines(file);
                for (var index = 0; index < lines.size(); index++) {
                    var matcher = EXPECT.matcher(lines.get(index));
                    if (matcher.find()) {
                        for (var rule : matcher.group(1).split(",")) {
                            markers.add(file.getFileName() + ":" + (index + 1) + " " + rule.strip());
                        }
                    }
                }
            }
        }
        return markers;
    }

    private static Set<String> reported() {
        var found = new TreeSet<String>();
        for (var finding : result.reported()) {
            found.add(finding.file() + ":" + finding.line() + " " + finding.rule().id());
        }
        return found;
    }

    @Test
    @DisplayName("reports exactly the marked lines, with the marked rules")
    void markers() throws IOException {
        assertEquals(expected(), reported(), String.join("\n", result.reported().stream().map(Finding::format).toList()));
    }

    @Test
    @DisplayName("marks every rule at least once and leaves a good kernel for each")
    void coverage() throws IOException {
        var rules = new TreeSet<String>();
        for (var marker : expected()) {
            rules.add(marker.substring(marker.indexOf(' ') + 1));
        }
        var all = new TreeSet<String>();
        for (var rule : Rule.values()) {
            all.add(rule.id());
        }
        all.remove(Rule.STALE_BASELINE.id());
        assertEquals(all, rules);
    }

    @Test
    @DisplayName("checks, in suppressed methods, exactly the methods the forbidden-apis signature files ban")
    void bans() throws IOException {
        var vector = new TreeSet<String>();
        for (var owner : BannedCalls.VECTOR_CLASSES) {
            for (var name : BannedCalls.CONVENIENCE) {
                vector.add(owner.replace('/', '.') + "#" + name);
            }
        }
        var listed = new TreeSet<String>();
        var math = new TreeSet<String>();
        for (var file : List.of("forbidden-vector.txt", "forbidden-math.txt")) {
            for (var line : Files.readAllLines(Path.of("..", "tools", "lint", file))) {
                if (line.isBlank() || line.startsWith("#") || line.startsWith("@")) {
                    continue;
                }
                var signature = line.strip().substring(0, line.strip().indexOf('('));
                if (signature.startsWith("java.lang.Math#")) {
                    math.add(signature.substring("java.lang.Math#".length()));
                } else if (!signature.contains("#reinterpretAs")) {
                    listed.add(signature);
                }
            }
        }
        // the signature files list only the methods a class declares; the lint's product of classes and names
        // covers them
        assertTrue(vector.containsAll(listed), "listed but not checked: " + difference(listed, vector));
        assertEquals(new TreeSet<>(BannedCalls.TRANSCENDENTAL), math);
        var names = new TreeSet<String>();
        listed.forEach(signature -> names.add(signature.substring(signature.indexOf('#') + 1)));
        assertEquals(new TreeSet<>(BannedCalls.CONVENIENCE), names);
    }

    private static Set<String> difference(Set<String> left, Set<String> right) {
        var result = new TreeSet<>(left);
        result.removeAll(right);
        return result;
    }

    @Test
    @DisplayName("the generated kernels straddle the size limit")
    void sizes() throws IOException {
        var model = ClassFile.of().parse(classes.resolve("com/github/auties00/smalt/fixture/VectorSizesLarge.class"));
        for (var method : model.methods()) {
            var length = method.code().map(code -> ((java.lang.classfile.attribute.CodeAttribute) code).codeLength())
                    .orElse(0);
            if (method.methodName().equalsString("over")) {
                assertTrue(length > KernelLint.MAX_KERNEL_BYTES && length < KernelLint.MAX_KERNEL_BYTES + 100,
                        "over: " + length);
            } else if (method.methodName().equalsString("under")) {
                assertTrue(length <= KernelLint.MAX_KERNEL_BYTES && length > KernelLint.MAX_KERNEL_BYTES - 100,
                        "under: " + length);
            }
        }
    }

    @Test
    @DisplayName("the fixtures are valid class files")
    void verify() throws IOException {
        try (Stream<Path> files = Files.walk(classes)) {
            for (var file : files.filter(path -> path.toString().endsWith(".class")).toList()) {
                assertEquals(List.of(), ClassFile.of().verify(file), file.toString());
            }
        }
    }

    @Nested
    @DisplayName("baseline")
    class Baseline {
        private static final String ROW = "exact-receiver\tcom.github.auties00.smalt.fixture.VectorReceivers#loadReceiver";

        @Test
        @DisplayName("a row allowing the findings of a method accepts them")
        void accepts() throws IOException {
            var run = KernelLint.run(List.of(classes), Map.of(ROW, 1));
            assertTrue(run.reported().stream().noneMatch(finding -> finding.method().equals("loadReceiver")));
            assertEquals(Map.of(ROW, 1), run.baseline());
        }

        @Test
        @DisplayName("a row allowing more findings than there are is stale")
        void stale() throws IOException {
            var run = KernelLint.run(List.of(classes), Map.of(ROW, 2));
            assertTrue(run.reported().stream().anyMatch(finding -> finding.rule() == Rule.STALE_BASELINE
                    && finding.method().equals("loadReceiver")), String.valueOf(run.reported()));
            assertEquals(Map.of(ROW, 1), run.baseline(), "the update lowers the row");
        }

        @Test
        @DisplayName("a row allowing fewer findings than there are reports all of them")
        void excess() throws IOException {
            var row = "exact-receiver\tcom.github.auties00.smalt.fixture.VectorReceivers#join";
            var run = KernelLint.run(List.of(classes), Map.of(row, 0));
            assertTrue(run.reported().stream().anyMatch(finding -> finding.method().equals("join")));
        }

        @Test
        @DisplayName("a row naming a method that has no findings is stale")
        void vanished() throws IOException {
            var row = "carried-receiver\tcom.github.auties00.smalt.fixture.VectorLoops#recast";
            var run = KernelLint.run(List.of(classes), Map.of(row, 1));
            assertTrue(run.reported().stream().anyMatch(finding -> finding.rule() == Rule.STALE_BASELINE
                    && finding.method().equals("recast")));
            assertEquals(Map.of(), run.baseline(), "the update drops the row");
        }
    }

    @Test
    @DisplayName("reads the Smalt artifacts of a class path and leaves the test jars and the lint out")
    void classPath() {
        var separator = java.io.File.pathSeparator;
        var roots = KernelLint.classPathRoots(String.join(separator, "lib/smalt-core-0.1.0.jar",
                "lib/smalt-core-0.1.0-tests.jar", "lib/smalt-kernel-lint-0.1.0.jar", "lib/asm-9.9.1.jar",
                "image/target/classes", "image/target/test-classes", "kernel-lint/target/classes"));
        assertEquals(List.of(Path.of("lib/smalt-core-0.1.0.jar"), Path.of("image/target/classes")), roots);
    }
}
