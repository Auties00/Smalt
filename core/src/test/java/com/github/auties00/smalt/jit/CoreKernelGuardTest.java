package com.github.auties00.smalt.jit;

import com.github.auties00.smalt.internal.simd.Simd;
import com.github.auties00.smalt.testkit.Skip;
import com.github.auties00.smalt.testkit.jit.KernelGuard;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Runs the deterministic kernel JIT guard ({@link KernelGuard}) on this module's Vector API kernel families, under
 * the scenarios {@code smalt.jit.scenarios} names ({@code pr} by default).
 */
@DisplayName("kernel JIT guard")
class CoreKernelGuardTest {
    @TestFactory
    @DisplayName("every kernel family runs compiled by C2, allocation-free and equal to its scalar twin")
    Stream<DynamicTest> families() {
        Skip.unlessVector(Simd.ENABLED);
        return KernelGuard.ofModule(test -> false).tests();
    }

    @Test
    @DisplayName("every kernel class has an equivalence test the guard runs")
    void covered() {
        // VectorProbe only reads the preferred vector width; it computes nothing
        var guard = KernelGuard.ofModule(test -> false);
        assertEquals(Set.of("com.github.auties00.smalt.internal.simd.VectorProbe"), KernelGuard.untested(List.of(Path.of("target", "classes")), guard.families()));
    }
}
