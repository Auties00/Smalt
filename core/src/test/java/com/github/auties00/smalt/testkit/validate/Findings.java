package com.github.auties00.smalt.testkit.validate;

import org.junit.jupiter.api.Assertions;

import java.util.ArrayList;
import java.util.List;

/**
 * The problems a validator found in one file, each with its location.
 *
 * <p>Instances are not thread-safe.
 */
public final class Findings {
    private final String file;
    private final List<String> problems = new ArrayList<>();

    /**
     * Constructs an empty list of findings.
     *
     * @param file the validated file, for the failure message
     */
    public Findings(String file) {
        this.file = file;
    }

    /**
     * Records a problem.
     *
     * @param where the location, for example a box path with its offset
     * @param problem what is wrong
     */
    public void add(String where, String problem) {
        problems.add(where + ": " + problem);
    }

    /**
     * Records a problem when a condition does not hold.
     *
     * @param condition the condition
     * @param where the location
     * @param problem what is wrong when the condition does not hold
     */
    public void require(boolean condition, String where, String problem) {
        if (!condition) {
            add(where, problem);
        }
    }

    /** {@return the problems found so far} */
    public List<String> problems() {
        return List.copyOf(problems);
    }

    /**
     * Fails the calling test when any problem was found, listing them.
     *
     * @param validator the validator, for the failure message
     */
    public void assertNone(String validator) {
        if (!problems.isEmpty()) {
            var shown = problems.size() > 40 ? problems.subList(0, 40) : problems;
            Assertions.fail(validator + " rejects " + file + " (" + problems.size() + " problems):\n  "
                    + String.join("\n  ", shown) + (problems.size() > shown.size() ? "\n  ..." : ""));
        }
    }
}
