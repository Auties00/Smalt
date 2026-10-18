package com.github.auties00.smalt.aot;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs short trainings of application workloads only, which check the cycle loop without the cost
 * of Smalt's own workloads (covered by {@code WorkloadsTest}).
 */
class SmaltTrainingTest {
    private static TrainingOptions applicationOnly(List<TrainingWorkload> workloads) {
        return TrainingOptions.builder().families(Set.of()).workloads(workloads)
                .maxDuration(Duration.ofSeconds(60)).build();
    }

    @Test
    @DisplayName("a run of application workloads goes through at least three cycles and counts every round")
    void application() {
        var runs = new int[2];
        var report = SmaltTraining.train(applicationOnly(List.of(
                TrainingWorkload.of("first", () -> runs[0]++),
                TrainingWorkload.of("second", () -> runs[1]++))));
        assertTrue(report.cycles() >= 3, report.summary());
        assertEquals(report.cycles(), runs[0]);
        assertEquals(report.cycles(), runs[1]);
        assertEquals(report.cycles(), report.rounds().get("first"));
        assertEquals(report.cycles(), report.rounds().get("second"));
        assertTrue(report.skipped().isEmpty(), report.summary());
    }

    @Test
    @DisplayName("a workload whose round throws is reported once and not run again, and the others go on")
    void failure() {
        var runs = new int[2];
        var report = SmaltTraining.train(applicationOnly(List.of(
                TrainingWorkload.of("broken", () -> {
                    runs[0]++;
                    throw new IllegalStateException("boom");
                }),
                TrainingWorkload.of("healthy", () -> runs[1]++))));
        assertEquals(1, runs[0]);
        assertEquals(report.cycles(), runs[1]);
        assertFalse(report.rounds().containsKey("broken"));
        assertEquals(1, report.skipped().size());
        assertTrue(report.skipped().getFirst().startsWith("broken: java.lang.IllegalStateException: boom"),
                report.skipped().toString());
    }

    @Test
    @DisplayName("deferred workloads are set up before the first cycle and a failed setup is reported as skipped")
    void deferred() {
        var setups = new int[2];
        var runs = new int[1];
        var options = TrainingOptions.builder().families(Set.of()).maxDuration(Duration.ofSeconds(60))
                .addWorkloads(
                        TrainingWorkload.deferred("unavailable", () -> {
                            setups[0]++;
                            throw new IllegalStateException("no device");
                        }),
                        TrainingWorkload.deferred("ready", () -> {
                            setups[1]++;
                            return TrainingWorkload.of("ignored", () -> runs[0]++);
                        }))
                .build();
        var report = SmaltTraining.train(options);
        assertEquals(1, setups[0]);
        assertEquals(1, setups[1]);
        assertEquals(report.cycles(), runs[0]);
        assertEquals(Set.of("ready"), report.rounds().keySet());
        assertEquals(List.of("unavailable: java.lang.IllegalStateException: no device"), report.skipped());
    }

    @Test
    @DisplayName("the time limit counts from the end of the setup")
    void deadlineAfterSetup() {
        var runs = new int[1];
        var options = TrainingOptions.builder().families(Set.of()).maxDuration(Duration.ofMillis(50))
                .addWorkloads(TrainingWorkload.deferred("slow setup", () -> {
                    Thread.sleep(300);
                    return TrainingWorkload.of("ignored", () -> runs[0]++);
                }))
                .build();
        var report = SmaltTraining.train(options);
        assertTrue(report.setup().toMillis() >= 300, report.summary());
        assertTrue(report.cycles() >= 1, report.summary());
        assertEquals(report.cycles(), runs[0]);
    }

    @Test
    @DisplayName("workloads that share a name are reported under distinct names")
    void duplicateNames() {
        var report = SmaltTraining.train(applicationOnly(List.of(
                TrainingWorkload.of("same", () -> {
                }),
                TrainingWorkload.of("same", () -> {
                }))));
        assertEquals(Set.of("same", "same#2"), report.rounds().keySet());
    }

    @Test
    @DisplayName("a run that selects nothing is rejected")
    void empty() {
        assertThrows(IllegalArgumentException.class,
                () -> SmaltTraining.train(TrainingOptions.builder().families(Set.of()).build()));
        assertThrows(NullPointerException.class, () -> SmaltTraining.train(null));
    }

    @Test
    @DisplayName("the command line rejects unknown options, missing values and unknown families and modes")
    void commandLine() {
        assertThrows(IllegalArgumentException.class, () -> SmaltTraining.main(new String[]{"--unknown"}));
        assertThrows(IllegalArgumentException.class, () -> SmaltTraining.main(new String[]{"--max-seconds"}));
        assertThrows(IllegalArgumentException.class, () -> SmaltTraining.main(new String[]{"--families", "NOPE"}));
        assertThrows(IllegalArgumentException.class, () -> SmaltTraining.main(new String[]{"--modes", "NOPE"}));
        assertThrows(IllegalArgumentException.class, () -> SmaltTraining.main(new String[]{"--then"}));
    }
}
