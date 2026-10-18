package com.github.auties00.smalt.aot;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the value types of the training API: options, reports and application workloads.
 */
class TrainingApiTest {
    @Nested
    @DisplayName("TrainingOptions")
    class Options {
        @Test
        @DisplayName("defaults run every family in every mode for at most 15 minutes and track Smalt and the Vector API")
        void defaults() {
            var options = TrainingOptions.defaults();
            assertEquals(EnumSet.allOf(TrainingFamily.class), options.families());
            assertEquals(EnumSet.allOf(TrainingMode.class), options.modes());
            assertEquals(Duration.ofMinutes(15), options.maxDuration());
            assertEquals(List.of(), options.workloads());
            assertEquals(List.of("com.github.auties00.smalt", "jdk.incubator.vector"), options.trackedPackages());
        }

        @Test
        @DisplayName("the real-time preset runs every family in the real-time mode only")
        void realtime() {
            assertEquals(EnumSet.allOf(TrainingFamily.class), TrainingOptions.forRealtime().families());
            assertEquals(EnumSet.of(TrainingMode.REALTIME), TrainingOptions.forRealtime().modes());
        }

        @Test
        @DisplayName("the builder sets every setting and toBuilder() round-trips the options")
        void builds() {
            var workload = TrainingWorkload.of("app", () -> {
            });
            var options = TrainingOptions.builder()
                    .maxDuration(Duration.ofSeconds(30))
                    .families(Set.of(TrainingFamily.IMAGE))
                    .modes(Set.of(TrainingMode.FILE))
                    .workloads(List.of(workload))
                    .trackedPackages(List.of("com.example"))
                    .build();
            assertEquals(Duration.ofSeconds(30), options.maxDuration());
            assertEquals(Set.of(TrainingFamily.IMAGE), options.families());
            assertEquals(Set.of(TrainingMode.FILE), options.modes());
            assertEquals(List.of(workload), options.workloads());
            assertEquals(List.of("com.example"), options.trackedPackages());
            assertEquals(Duration.ofMinutes(15), TrainingOptions.defaults().maxDuration());
            assertEquals(options, options.toBuilder().build());
            assertEquals(options.hashCode(), options.toBuilder().build().hashCode());
            assertNotEquals(options, TrainingOptions.defaults());
            assertSame(TrainingOptions.defaults(), TrainingOptions.defaults());
        }

        @Test
        @DisplayName("the options copy the collections they are given")
        void copies() {
            var families = EnumSet.of(TrainingFamily.IMAGE);
            var modes = EnumSet.of(TrainingMode.REALTIME);
            var packages = new ArrayList<>(List.of("com.example"));
            var options = TrainingOptions.builder().families(families).modes(modes).trackedPackages(packages).build();
            families.add(TrainingFamily.TRANSCODE);
            modes.add(TrainingMode.FILE);
            packages.add("org.example");
            assertEquals(Set.of(TrainingFamily.IMAGE), options.families());
            assertEquals(Set.of(TrainingMode.REALTIME), options.modes());
            assertEquals(List.of("com.example"), options.trackedPackages());
            assertThrows(UnsupportedOperationException.class, () -> options.families().add(TrainingFamily.TRANSCODE));
            assertThrows(UnsupportedOperationException.class, () -> options.modes().add(TrainingMode.FILE));
        }

        @Test
        @DisplayName("invalid settings are rejected")
        void invalid() {
            var defaults = TrainingOptions.defaults();
            assertThrows(IllegalArgumentException.class, () -> defaults.toBuilder().maxDuration(Duration.ZERO).build());
            assertThrows(IllegalArgumentException.class,
                    () -> defaults.toBuilder().maxDuration(Duration.ofSeconds(-1)).build());
            assertThrows(NullPointerException.class, () -> defaults.toBuilder().maxDuration(null).build());
            assertThrows(NullPointerException.class, () -> defaults.toBuilder().families(null).build());
            assertThrows(NullPointerException.class, () -> defaults.toBuilder()
                    .families(new java.util.HashSet<>(Arrays.asList(TrainingFamily.IMAGE, null))).build());
            assertThrows(NullPointerException.class, () -> defaults.toBuilder().modes(null).build());
            assertThrows(NullPointerException.class, () -> defaults.toBuilder()
                    .modes(new java.util.HashSet<>(Arrays.asList(TrainingMode.FILE, null))).build());
            assertThrows(IllegalArgumentException.class, () -> defaults.toBuilder().modes(Set.of()).build());
            assertThrows(NullPointerException.class,
                    () -> defaults.toBuilder().workloads(Arrays.asList((TrainingWorkload) null)).build());
            assertThrows(IllegalArgumentException.class,
                    () -> defaults.toBuilder().trackedPackages(List.of(" ")).build());
        }
        @Test
        @DisplayName("addWorkloads and addTrackedPackages append to what the builder holds")
        void append() {
            var first = TrainingWorkload.of("first", () -> {
            });
            var second = TrainingWorkload.of("second", () -> {
            });
            var options = TrainingOptions.builder()
                    .addWorkloads(first)
                    .addWorkloads(second)
                    .addTrackedPackages("com.example.app", "org.example.lib")
                    .build();
            assertEquals(List.of(first, second), options.workloads());
            assertEquals(List.of("com.github.auties00.smalt", "jdk.incubator.vector", "com.example.app",
                    "org.example.lib"), options.trackedPackages());
            assertEquals(TrainingOptions.defaults().families(), options.families());
            assertEquals(TrainingOptions.defaults().modes(), options.modes());
            assertEquals(List.of(), TrainingOptions.defaults().workloads());

            var replaced = TrainingOptions.builder()
                    .trackedPackages(List.of("com.example")).addTrackedPackages("org.example")
                    .workloads(List.of(first)).addWorkloads()
                    .build();
            assertEquals(List.of("com.example", "org.example"), replaced.trackedPackages());
            assertEquals(List.of(first), replaced.workloads());
            assertEquals(options, options.toBuilder().addWorkloads().addTrackedPackages().build());
        }

        @Test
        @DisplayName("an append does not change options built or lists given before it")
        void appendCopies() {
            var packages = new ArrayList<>(List.of("com.example"));
            var builder = TrainingOptions.builder().trackedPackages(packages);
            var before = builder.build();
            builder.addTrackedPackages("org.example");
            assertEquals(List.of("com.example"), packages);
            assertEquals(List.of("com.example"), before.trackedPackages());
            assertEquals(List.of("com.example", "org.example"), builder.build().trackedPackages());
        }

        @Test
        @DisplayName("an append rejects null elements at once and blank names when built")
        void appendInvalid() {
            var builder = TrainingOptions.builder();
            assertThrows(NullPointerException.class, () -> builder.addWorkloads((TrainingWorkload) null));
            assertThrows(NullPointerException.class, () -> builder.addWorkloads((TrainingWorkload[]) null));
            assertThrows(NullPointerException.class, () -> builder.addTrackedPackages("com.example", null));
            assertThrows(NullPointerException.class, () -> builder.addTrackedPackages((String[]) null));
            assertEquals(TrainingOptions.defaults(), builder.build());
            assertThrows(IllegalArgumentException.class,
                    () -> TrainingOptions.builder().addTrackedPackages(" ").build());
            assertThrows(NullPointerException.class,
                    () -> TrainingOptions.builder().workloads(null).addWorkloads(TrainingWorkload.of("x", () -> {
                    })).build());
        }
    }

    @Nested
    @DisplayName("TrainingReport")
    class Report {
        @Test
        @DisplayName("copies its collections and summarizes the run on one line")
        void summary() {
            var report = new TrainingReport(Duration.ofSeconds(12), Duration.ofSeconds(2), Map.of("b", 3, "a", 4),
                    List.of("c: java.lang.IllegalStateException"), 4, true);
            var summary = report.summary();
            assertTrue(summary.startsWith("2 workloads, 7 rounds in 4 cycles (converged) in 12.0 s (setup 2.0 s), 1 skipped"),
                    summary);
            assertTrue(summary.endsWith("{a=4, b=3}"), summary);
            assertThrows(UnsupportedOperationException.class, () -> report.rounds().put("d", 1));
        }

        @Test
        @DisplayName("rejects missing values and a negative cycle count")
        void invalid() {
            assertThrows(NullPointerException.class, () -> new TrainingReport(null, Duration.ZERO, Map.of(), List.of(), 0, false));
            assertThrows(IllegalArgumentException.class,
                    () -> new TrainingReport(Duration.ZERO, Duration.ZERO, Map.of(), List.of(), -1, false));
        }
    }

    @Nested
    @DisplayName("TrainingMedia")
    class Media {
        @Test
        @DisplayName("synthesizes deterministic media of the requested shape")
        void shapes() {
            var video = TrainingMedia.video(64, 32, 3);
            assertEquals(3, video.size());
            assertEquals(64, video.getFirst().width());
            assertTrue(Arrays.equals(video.get(1).y(), TrainingMedia.video(64, 32, 3).get(1).y()));
            assertEquals(8000, TrainingMedia.speech(8000, 1).length);
            assertEquals(2, TrainingMedia.music(8000, 0.5).length);
            var gif = TrainingMedia.animatedGif(240, 180, 2);
            assertEquals("GIF89a", new String(gif, 0, 6, java.nio.charset.StandardCharsets.US_ASCII));
            for (var file : List.of(TrainingMedia.mp4(64, 32, 0.2), TrainingMedia.m4a(0.2))) {
                assertEquals("ftyp", new String(file, 4, 4, java.nio.charset.StandardCharsets.US_ASCII));
            }
        }

        @Test
        @DisplayName("rejects shapes it cannot synthesize")
        void invalid() {
            assertThrows(IllegalArgumentException.class, () -> TrainingMedia.video(0, 32, 3));
            assertThrows(IllegalArgumentException.class, () -> TrainingMedia.speech(1000, 1));
            assertThrows(IllegalArgumentException.class, () -> TrainingMedia.music(48000, 0));
            assertThrows(IllegalArgumentException.class, () -> TrainingMedia.mp4(63, 32, 1));
            assertThrows(IllegalArgumentException.class, () -> TrainingMedia.m4a(-1));
            assertThrows(IllegalArgumentException.class, () -> TrainingMedia.animatedGif(200, 180, 2));
        }
    }

    @Nested
    @DisplayName("TrainingWorkload")
    class Workload {
        @Test
        @DisplayName("of runs its action as the round and reports its name")
        void of() throws Exception {
            var runs = new int[1];
            var workload = TrainingWorkload.of("count", () -> runs[0]++);
            workload.round();
            workload.round();
            assertEquals(2, runs[0]);
            assertEquals("count", workload.name());
            assertEquals("count", workload.toString());
            assertThrows(NullPointerException.class, () -> TrainingWorkload.of(null, () -> {
            }));
            assertThrows(NullPointerException.class, () -> TrainingWorkload.of("x", null));
        }

        @Test
        @DisplayName("deferred runs its setup once, at the first round, and then the rounds of the workload it returns")
        void deferred() throws Exception {
            var setups = new int[1];
            var runs = new int[1];
            var workload = TrainingWorkload.deferred("lazy", () -> {
                setups[0]++;
                return TrainingWorkload.of("ignored", () -> runs[0]++);
            });
            assertEquals("lazy", workload.name());
            assertEquals("lazy", workload.toString());
            assertEquals(0, setups[0]);
            workload.round();
            workload.round();
            assertEquals(1, setups[0]);
            assertEquals(2, runs[0]);
            assertThrows(NullPointerException.class, () -> TrainingWorkload.deferred(null, () -> workload));
            assertThrows(NullPointerException.class, () -> TrainingWorkload.deferred("x", null));
        }

        @Test
        @DisplayName("a deferred workload whose setup fails throws the failure from every round without retrying")
        void deferredFailure() {
            var setups = new int[1];
            var failure = new IllegalStateException("no device");
            var workload = TrainingWorkload.deferred("broken", () -> {
                setups[0]++;
                throw failure;
            });
            assertSame(failure, assertThrows(IllegalStateException.class, workload::round));
            assertSame(failure, assertThrows(IllegalStateException.class, workload::round));
            assertEquals(1, setups[0]);
            var empty = TrainingWorkload.deferred("empty", () -> null);
            assertThrows(NullPointerException.class, empty::round);
        }
    }
}
