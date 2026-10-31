package com.github.auties00.smalt.jit;

import com.github.auties00.smalt.internal.simd.Simd;
import com.github.auties00.smalt.testkit.Skip;
import com.github.auties00.smalt.testkit.jit.KernelGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks that the deterministic kernel JIT guard can fail, on the fixture kernels of {@code jit.fixture}: a kernel
 * written to the rules passes every scenario, started from an AOT cache too; the same kernel fails with its binary-operation intrinsic disabled
 * ({@code ControlIntrinsic}) and with the Vector API intrinsics off ({@code -EnableVectorSupport}); a kernel whose
 * species-parameterized helper serves three shapes fails under C2 alone, in two runs alike; and a kernel whose vector
 * path computes the wrong result fails at every vector width the platform runs.
 */
@DisplayName("kernel JIT guard self-test")
class KernelGuardSelfTest {
    private static final String FIXTURES = "com.github.auties00.smalt.jit.fixture.";

    private KernelGuard guard;

    @BeforeEach
    void guard() {
        Skip.unlessVector(Simd.ENABLED);
        var classes = Path.of("target", "test-classes");
        guard = KernelGuard.of(List.of(classes), List.of(classes), test -> !test.startsWith(FIXTURES),
                KernelGuard.testClassPath(), Path.of("target", "kernel-guard-self"), KernelGuard.Settings.defaults());
    }

    private KernelGuard.Family family(String kernel) {
        return guard.families().stream().filter(family -> family.name().equals(kernel)).findFirst().orElseThrow();
    }

    private static boolean allocates(KernelGuard.Result result, String entry) {
        return result.problems().stream().anyMatch(problem -> problem.startsWith(entry) && problem.contains("allocated"));
    }

    @Test
    @DisplayName("finds the three fixture families, each with its own test")
    void families() {
        var names = guard.families().stream().map(KernelGuard.Family::name).sorted().toList();
        assertEquals(List.of("VectorBrokenKernel", "VectorCleanKernel", "VectorFragileKernel"), names);
    }

    @TestFactory
    @DisplayName("passes the clean kernel in every scenario, compiled by C2")
    Stream<DynamicTest> clean() {
        var scenarios = new ArrayList<>(List.of(KernelGuard.Scenario.tiered(), KernelGuard.Scenario.c2Only(),
                KernelGuard.Scenario.polluted(true), KernelGuard.Scenario.stress(1)));
        scenarios.addAll(KernelGuard.Scenario.widths());
        return scenarios.stream().map(scenario -> DynamicTest.dynamicTest(scenario.name(), () -> {
            var result = guard.run(family("VectorCleanKernel"), scenario);
            assertTrue(result.passed(), result::describe);
            assertTrue(result.entries().stream().anyMatch(entry -> entry.startsWith("VectorCleanKernel.mix")
                    && entry.contains("c2=true") && !entry.contains("measured=0")), String.valueOf(result.entries()));
        }));
    }

    @Test
    @DisplayName("passes the clean kernel started from an AOT cache its training child recorded")
    void aot() throws Exception {
        var result = guard.run(family("VectorCleanKernel"), KernelGuard.Scenario.aot());
        assertTrue(result.passed(), result::describe);
        assertTrue(result.command().stream().anyMatch(flag -> flag.startsWith("-XX:AOTCache=")), result::describe);
        var log = Files.readString(result.log().resolve("aot.log"), StandardCharsets.ISO_8859_1);
        assertTrue(log.contains("Opened AOT cache"), log);
        assertTrue(result.entries().stream().anyMatch(entry -> entry.startsWith("VectorCleanKernel.mix")
                && entry.contains("c2=true") && !entry.contains("measured=0")), String.valueOf(result.entries()));
    }

    @Test
    @DisplayName("fails the clean kernel when its binary-operation intrinsic is disabled")
    void controlIntrinsic() throws Exception {
        var disabled = guard.with(KernelGuard.Settings.defaults().withFlags(
                "-XX:CompileCommand=ControlIntrinsic," + FIXTURES + "VectorCleanKernel::*,-_VectorBinaryOp"));
        var result = disabled.run(family("VectorCleanKernel"), KernelGuard.Scenario.tiered());
        assertFalse(result.passed());
        assertTrue(allocates(result, "VectorCleanKernel.mix"), result::describe);
    }

    @Test
    @DisplayName("fails the clean kernel when the Vector API intrinsics are off")
    void noVectorSupport() throws Exception {
        var disabled = guard.with(KernelGuard.Settings.defaults().withFlags("-XX:+UnlockExperimentalVMOptions",
                "-XX:-EnableVectorSupport"));
        var result = disabled.run(family("VectorCleanKernel"), KernelGuard.Scenario.tiered());
        assertFalse(result.passed());
        assertTrue(allocates(result, "VectorCleanKernel.mix"), result::describe);
    }

    @Test
    @DisplayName("fails the fragile kernel under C2 alone, in two runs alike")
    void fragile() throws Exception {
        var first = guard.run(family("VectorFragileKernel"), KernelGuard.Scenario.c2Only());
        var second = guard.run(family("VectorFragileKernel"), KernelGuard.Scenario.c2Only());
        assertFalse(first.passed());
        assertTrue(first.problems().stream().anyMatch(problem -> problem.startsWith("VectorFragileKernel.add")
                && problem.contains("allocated")), first::describe);
        assertEquals(allocating(first), allocating(second), "the failure is the same in both runs");
    }

    private static List<String> allocating(KernelGuard.Result result) {
        return result.problems().stream().filter(problem -> problem.contains("allocated"))
                .map(problem -> problem.substring(0, problem.indexOf(' '))).sorted().toList();
    }

    @TestFactory
    @DisplayName("fails the broken kernel at every vector width")
    Stream<DynamicTest> broken() {
        var scenarios = new ArrayList<>(List.of(KernelGuard.Scenario.tiered()));
        scenarios.addAll(KernelGuard.Scenario.widths());
        return scenarios.stream().map(scenario -> DynamicTest.dynamicTest(scenario.name(), () -> {
            var result = guard.run(family("VectorBrokenKernel"), scenario);
            assertFalse(result.passed());
            assertTrue(result.problems().stream().anyMatch(problem -> problem.startsWith("test failed in round measure")),
                    result::describe);
        }));
    }
}
