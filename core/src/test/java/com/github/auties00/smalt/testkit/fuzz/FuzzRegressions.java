package com.github.auties00.smalt.testkit.fuzz;

import org.junit.jupiter.api.DynamicContainer;
import org.junit.jupiter.api.DynamicNode;
import org.junit.jupiter.api.DynamicTest;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Replays the seeds and the saved findings of fuzz targets as plain tests.
 *
 * <p>For every fuzz test method (a method annotated with Jazzer's {@code @FuzzTest}) of the given classes, the
 * replay runs every seed of {@link FuzzSeeds#directory} and every input saved under {@code
 * src/test/resources/fuzz-regressions/<Class>/<method>/}, each under a deadline of {@link #DEADLINE}. A seed
 * named {@code valid-*} must be accepted by every step of the target and produce at least one output
 * ({@link FuzzRun#output()}), and every target must have one, so a target that silently rejects or ignores
 * everything fails. Replays are thorough ({@link Fuzz#thorough(boolean)}): every sampled step runs. The replay
 * does not need Jazzer's agent.
 */
public final class FuzzRegressions {
    /** The deadline of one input, generous for a cold JIT on a slow runner. */
    public static final Duration DEADLINE = Duration.ofSeconds(60);

    /** The directory of the saved findings among the test resources. */
    public static final String DIRECTORY = "fuzz-regressions";

    private static final String FUZZ_TEST = "com.code_intelligence.jazzer.junit.FuzzTest";

    private FuzzRegressions() {
        throw new AssertionError();
    }

    /**
     * Returns the replay tests of the fuzz targets of some classes.
     *
     * @param fuzzClasses the classes declaring fuzz tests, each with a no-argument constructor
     * @return one container per fuzz test method
     */
    public static Stream<DynamicNode> replay(Class<?>... fuzzClasses) {
        return replay(Set.of(), fuzzClasses);
    }

    /**
     * Returns the replay tests of the fuzz targets of some classes, some of which have no valid seed yet.
     *
     * @param partial the targets, as {@code Class.method}, whose seeds need not include a valid one because no
     * small valid input of their format is available; each must still have a seed
     * @param fuzzClasses the classes declaring fuzz tests, each with a no-argument constructor
     * @return one container per fuzz test method
     */
    public static Stream<DynamicNode> replay(Set<String> partial, Class<?>... fuzzClasses) {
        var nodes = new ArrayList<DynamicNode>();
        for (var fuzzClass : fuzzClasses) {
            var methods = Arrays.stream(fuzzClass.getDeclaredMethods())
                    .filter(FuzzRegressions::isFuzzTest)
                    .sorted(Comparator.comparing(Method::getName))
                    .toList();
            if (methods.isEmpty()) {
                throw new IllegalArgumentException(fuzzClass.getName() + " declares no fuzz test");
            }
            for (var method : methods) {
                var name = fuzzClass.getSimpleName() + "." + method.getName();
                nodes.add(DynamicContainer.dynamicContainer(name, tests(fuzzClass, method, partial.contains(name))));
            }
        }
        return nodes.stream();
    }

    /**
     * Lists the fuzz test methods of a class.
     *
     * @param fuzzClass the class
     * @return the names of its fuzz test methods
     */
    public static List<String> fuzzTests(Class<?> fuzzClass) {
        return Arrays.stream(fuzzClass.getDeclaredMethods())
                .filter(FuzzRegressions::isFuzzTest)
                .map(Method::getName)
                .sorted()
                .toList();
    }

    private static boolean isFuzzTest(Method method) {
        return Arrays.stream(method.getAnnotations())
                .anyMatch(annotation -> annotation.annotationType().getName().equals(FUZZ_TEST));
    }

    private static Stream<DynamicNode> tests(Class<?> fuzzClass, Method method, boolean partial) {
        var seeds = files(FuzzSeeds.directory(fuzzClass, method.getName()));
        var findings = files(Path.of("src", "test", "resources", DIRECTORY, fuzzClass.getSimpleName(), method.getName()));
        var tests = new ArrayList<DynamicNode>();
        if (partial) {
            tests.add(DynamicTest.dynamicTest("has a seed", () -> assertFalse(seeds.isEmpty(),
                    "no seed in " + FuzzSeeds.directory(fuzzClass, method.getName()))));
        } else {
            tests.add(DynamicTest.dynamicTest("has a valid seed", () -> assertTrue(
                    seeds.stream().anyMatch(seed -> seed.getFileName().toString().startsWith(FuzzSeeds.VALID)),
                    "no " + FuzzSeeds.VALID + "* seed in " + FuzzSeeds.directory(fuzzClass, method.getName()))));
        }
        for (var seed : seeds) {
            var valid = seed.getFileName().toString().startsWith(FuzzSeeds.VALID);
            tests.add(DynamicTest.dynamicTest("seed " + seed.getFileName(), seed.toUri(), () -> {
                var run = invoke(fuzzClass, method, Files.readAllBytes(seed));
                if (valid) {
                    assertTrue(run.fullyAccepted() && run.outputs() > 0, run::toString);
                }
            }));
        }
        for (var finding : findings) {
            tests.add(DynamicTest.dynamicTest("finding " + finding.getFileName(), finding.toUri(),
                    () -> invoke(fuzzClass, method, Files.readAllBytes(finding))));
        }
        return tests.stream();
    }

    private static FuzzRun invoke(Class<?> fuzzClass, Method method, byte[] input) {
        return assertTimeoutPreemptively(DEADLINE, () -> {
            Fuzz.clear();
            Fuzz.thorough(true);
            try {
                var constructor = fuzzClass.getDeclaredConstructor();
                constructor.setAccessible(true);
                method.setAccessible(true);
                method.invoke(constructor.newInstance(), (Object) input);
            } catch (InvocationTargetException exception) {
                throw exception.getCause();
            } finally {
                Fuzz.thorough(false);
            }
            var run = Fuzz.last();
            assertFalse(run.isEmpty(), "the fuzz test did not start a run");
            return run.get();
        });
    }

    private static List<Path> files(Path directory) {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (var stream = Files.list(directory)) {
            return stream.filter(Files::isRegularFile).sorted().toList();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
