package com.github.auties00.smalt.lint;

import de.thetaphi.forbiddenapis.Checker;
import de.thetaphi.forbiddenapis.Logger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Checks the forbidden-apis bans the build runs ({@code tools/lint/forbidden-vector.txt},
 * {@code tools/lint/forbidden-math.txt} and the {@code vector-confined} signature of the smalt-parent pom) against
 * classes compiled at test time: each banned call is reported at its line with its rule id, a call the
 * {@code KernelLintSuppress} annotation covers is not, a class named {@code Vector*} may name the Vector API while any
 * other class may not, and the transcendental {@code Math} methods are banned while {@code StrictMath} and the exact
 * {@code Math} methods are not.
 */
@DisplayName("forbidden-apis signatures")
class ForbiddenSignaturesTest {
    private static final Path SIGNATURES = Path.of("..", "tools", "lint", "forbidden-vector.txt");
    private static final Path MATH_SIGNATURES = Path.of("..", "tools", "lint", "forbidden-math.txt");

    @TempDir
    static Path work;

    private static Path classes;

    @BeforeAll
    static void compile() throws IOException {
        var sources = Files.createDirectories(work.resolve("sources/com/github/auties00/smalt/bans"));
        var annotations = Files.createDirectories(work.resolve("sources/com/github/auties00/smalt/internal/simd"));
        Files.copy(Path.of("src/test/fixtures/com/github/auties00/smalt/internal/simd/KernelLintSuppress.java"),
                annotations.resolve("KernelLintSuppress.java"));
        Files.writeString(sources.resolve("VectorBans.java"), """
                package com.github.auties00.smalt.bans;

                import com.github.auties00.smalt.internal.simd.KernelLintSuppress;
                import jdk.incubator.vector.IntVector;
                import jdk.incubator.vector.VectorSpecies;

                final class VectorBans {
                    static final VectorSpecies<Integer> S = IntVector.SPECIES_128;

                    static void convenience(int[] a) {
                        var v = IntVector.fromArray(S, a, 0);
                        v.add(v).intoArray(a, 4);
                    }

                    static void reinterpret(int[] a, byte[] b) {
                        IntVector.fromArray(S, a, 0).reinterpretAsBytes().intoArray(b, 0);
                    }

                    @KernelLintSuppress(value = "vector-convenience", reason = "the fixture's accepted call")
                    static void suppressed(int[] a) {
                        var v = IntVector.fromArray(S, a, 0);
                        v.mul(v).intoArray(a, 4);
                    }
                }
                """);
        Files.writeString(sources.resolve("Kernels.java"), """
                package com.github.auties00.smalt.bans;

                import jdk.incubator.vector.IntVector;

                final class Kernels {
                    static void selector(int[] a) {
                        IntVector.fromArray(IntVector.SPECIES_128, a, 0).intoArray(a, 4);
                    }
                }
                """);
        Files.writeString(sources.resolve("MathBans.java"), """
                package com.github.auties00.smalt.bans;

                final class MathBans {
                    static double banned(double x) {
                        return Math.pow(x, 2.4)
                                + Math.sin(x) + Math.atan2(x, 1) + Math.log10(x);
                    }

                    static double allowed(double x) {
                        return StrictMath.pow(x, 2.4) + StrictMath.sin(x) + Math.sqrt(x) + Math.fma(x, x, x)
                                + Math.floor(x) + Math.abs(x) + Math.max(x, 1);
                    }
                }
                """);
        classes = Files.createDirectories(work.resolve("classes"));
        List<String> paths;
        try (Stream<Path> files = Files.walk(work.resolve("sources"))) {
            paths = files.filter(path -> path.toString().endsWith(".java")).map(Path::toString).toList();
        }
        var arguments = new ArrayList<>(List.of("--release", "26", "--add-modules", "jdk.incubator.vector", "-g",
                "-nowarn", "-XDsuppressNotes", "-d", classes.toString()));
        arguments.addAll(paths);
        var errors = new ByteArrayOutputStream();
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, errors, errors, arguments.toArray(String[]::new)),
                "the fixtures do not compile: " + errors);
    }

    /** Runs the checker as one execution of the build runs it, and returns the violations as {@code File:line id}. */
    private static TreeSet<String> check(String signatures, Path signaturesFile, String... classFiles)
            throws Exception {
        try (var loader = new URLClassLoader(new java.net.URL[]{classes.toUri().toURL()},
                ForbiddenSignaturesTest.class.getClassLoader())) {
            var checker = new Checker(new Logger() {
                @Override
                public void error(String message) {
                }

                @Override
                public void warn(String message) {
                }

                @Override
                public void info(String message) {
                }

                @Override
                public void debug(String message) {
                }
            }, loader, EnumSet.of(Checker.Option.FAIL_ON_MISSING_CLASSES,
                    Checker.Option.FAIL_ON_UNRESOLVABLE_SIGNATURES));
            checker.addSuppressAnnotation("**.KernelLintSuppress");
            if (signaturesFile != null) {
                checker.parseSignaturesFile(signaturesFile.toFile());
            } else {
                checker.parseSignaturesString(signatures);
            }
            checker.addClassesToCheck(classes.toFile(), classFiles);
            var found = new TreeSet<String>();
            for (var result : checker.runWithResults()) {
                for (var violation : result.getViolations()) {
                    var id = java.util.regex.Pattern.compile("\\[([a-z]+-[a-z]+)]").matcher(violation.description);
                    found.add(result.getSourceFile() + ":" + violation.lineNo + " "
                            + (id.find() ? id.group(1) : violation.description));
                }
            }
            return found;
        }
    }

    @Test
    @DisplayName("ban the convenience methods and reinterpretAs*, unless suppressed")
    void bans() throws Exception {
        var found = check(null, SIGNATURES, "com/github/auties00/smalt/bans/VectorBans.class",
                "com/github/auties00/smalt/bans/Kernels.class");
        assertEquals(new TreeSet<>(List.of("VectorBans.java:12 vector-convenience", "VectorBans.java:16 vector-reinterpret")),
                found);
    }

    @Test
    @DisplayName("list exactly the convenience and reinterpretAs* methods the running JDK's Vector API declares")
    void complete() throws Exception {
        var convenience = java.util.Set.of("abs", "add", "and", "div", "eq", "fma", "lt", "max", "min", "mul", "neg",
                "not", "or", "pow", "sqrt", "sub");
        var expected = new TreeSet<String>();
        for (var name : List.of("Vector", "ByteVector", "ShortVector", "IntVector", "LongVector", "FloatVector",
                "DoubleVector")) {
            var type = Class.forName("jdk.incubator.vector." + name);
            for (var method : type.getDeclaredMethods()) {
                if (java.lang.reflect.Modifier.isPublic(method.getModifiers())
                        && (convenience.contains(method.getName()) || method.getName().startsWith("reinterpretAs"))) {
                    // reinterpretAs* takes no arguments; the convenience methods have several overloads
                    expected.add(type.getName() + "#" + method.getName()
                            + (method.getName().startsWith("reinterpretAs") ? "()" : "(**)"));
                }
            }
        }
        var listed = new TreeSet<String>();
        for (var line : Files.readAllLines(SIGNATURES)) {
            if (!line.isBlank() && !line.startsWith("#") && !line.startsWith("@")) {
                listed.add(line.strip());
            }
        }
        assertEquals(expected, listed);
    }

    @Test
    @DisplayName("confine the Vector API to the Vector* classes")
    void confined() throws Exception {
        var pom = Files.readString(Path.of("..", "pom.xml"));
        var start = pom.indexOf("<![CDATA[", pom.indexOf("<id>vector-confined</id>")) + "<![CDATA[".length();
        var signatures = pom.substring(start, pom.indexOf("]]>", start));
        var found = check(signatures, null, "com/github/auties00/smalt/bans/Kernels.class");
        assertEquals(new TreeSet<>(List.of("Kernels.java:7 vector-confined")), found);
    }

    @Test
    @DisplayName("ban the transcendental Math methods and allow StrictMath and the exact Math methods")
    void strictMath() throws Exception {
        var found = check(null, MATH_SIGNATURES, "com/github/auties00/smalt/bans/MathBans.class");
        assertEquals(new TreeSet<>(List.of("MathBans.java:5 strict-math", "MathBans.java:6 strict-math")), found);
    }

    @Test
    @DisplayName("the build runs the strict-math ban over the main code")
    void strictMathWired() throws Exception {
        var pom = Files.readString(Path.of("..", "pom.xml"));
        var execution = pom.indexOf("<id>strict-math</id>");
        assertEquals(true, execution > 0 && pom.indexOf("tools/lint/forbidden-math.txt", execution) > 0,
                "the smalt-parent pom has no strict-math execution");
    }
}
