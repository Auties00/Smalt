package com.github.auties00.smalt.testkit.jit;

import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedMethod;
import jdk.jfr.consumer.RecordingFile;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Predicate;

/**
 * Reads the compilation events of a {@link KernelGuardChild}'s flight recording: failed compilations of Smalt methods
 * ({@code jdk.Compilation} with {@code succeded} false, and the message of the matching {@code jdk.CompilationFailure}),
 * and deoptimizations of a kernel during the measured round ({@code jdk.Deoptimization}).
 */
final class CompilationEvents {
    private CompilationEvents() {
        throw new AssertionError();
    }

    /**
     * Returns the problems the recording shows.
     *
     * @param recording the flight recording
     * @param kernels the kernel classes' binary names
     * @param test whether a binary class name is one of the tests
     * @param measureStart the start of the measured round, or {@code null} if it did not start
     * @param measureEnd the end of the measured round, or {@code null}
     * @return the problems, empty when there are none
     * @throws IOException if the recording cannot be read
     */
    static List<String> problems(Path recording, Set<String> kernels, Predicate<String> test, Instant measureStart,
                                 Instant measureEnd) throws IOException {
        var events = RecordingFile.readAllEvents(recording);
        var messages = new HashMap<Long, String>();
        for (var event : events) {
            if (event.getEventType().getName().equals("jdk.CompilationFailure")) {
                messages.put(event.getLong("compileId"), event.getString("failureMessage"));
            }
        }
        var failed = new TreeMap<String, String>();
        var deoptimized = new TreeMap<String, Integer>();
        for (var event : events) {
            switch (event.getEventType().getName()) {
                case "jdk.Compilation" -> {
                    if (!event.getBoolean("succeded") && smalt(event.getValue("method"), test)) {
                        failed.putIfAbsent(name(event.getValue("method")) + " at level " + event.getShort("compileLevel"),
                                messages.getOrDefault(event.getLong("compileId"), "no message"));
                    }
                }
                case "jdk.Deoptimization" -> {
                    if (measureStart != null && within(event, measureStart, measureEnd)
                            && kernel(event.getValue("method"), kernels)) {
                        deoptimized.merge(name(event.getValue("method")) + " (" + event.getString("reason") + ", "
                                + event.getString("action") + ", line " + event.getInt("lineNumber") + ")", 1, Integer::sum);
                    }
                }
                default -> {
                }
            }
        }
        var problems = new ArrayList<String>();
        failed.forEach((method, message) -> problems.add("compilation of " + method + " failed: " + message));
        deoptimized.forEach((method, count) -> problems.add("deoptimized during the measured round: " + method
                + (count > 1 ? " x" + count : "")));
        return problems;
    }

    private static boolean within(RecordedEvent event, Instant start, Instant end) {
        var time = event.getStartTime();
        return !time.isBefore(start) && (end == null || !time.isAfter(end));
    }

    private static boolean smalt(RecordedMethod method, Predicate<String> test) {
        if (method == null) {
            return false;
        }
        var type = method.getType().getName();
        return type.startsWith("com.github.auties00.smalt.") && !test.test(type)
                && !type.startsWith("com.github.auties00.smalt.testkit.");
    }

    private static boolean kernel(RecordedMethod method, Set<String> kernels) {
        if (method == null) {
            return false;
        }
        var type = method.getType().getName();
        var dollar = type.indexOf('$');
        return kernels.contains(dollar < 0 ? type : type.substring(0, dollar));
    }

    private static String name(RecordedMethod method) {
        var type = method.getType().getName();
        return type.substring(type.lastIndexOf('.') + 1) + "." + method.getName() + method.getDescriptor();
    }
}
