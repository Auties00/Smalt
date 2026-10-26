package com.github.auties00.smalt.testkit;

import org.junit.jupiter.api.Assertions;
import org.opentest4j.TestAbortedException;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Skips the calling test with a stable reason id, the only way Smalt's tests skip.
 *
 * <p>A skip message has the form {@code [family:name] detail}: the id in brackets is stable, the detail is free
 * text. {@code tools/testdata/skips.py} reads the ids from the surefire reports, counts them per test class and
 * compares the counts with {@code tools/testdata/skip-baseline.tsv}, so a skip that grows on CI fails the build
 * while one that only changes its wording does not. JUnit's own conditions ({@code @EnabledOnOs} and the like) carry
 * the same prefix in their {@code disabledReason}. The families are those of {@link Family}.
 *
 * <p>Two switches turn an absent environment into a failure where CI provisions it:
 * <ul>
 *   <li>{@code smalt.test.requireOracles} (or {@code SMALT_TEST_REQUIRE_ORACLES}), {@code true} for every oracle
 *       tool or a comma-separated list of executable names, makes {@link Oracle} fail instead of skipping when one
 *       of them is missing;</li>
 *   <li>{@code smalt.capture.requireDevices} (or {@code SMALT_CAPTURE_REQUIRE_DEVICES}), {@code true} or a
 *       comma-separated list of device kinds such as {@code screen}, {@code camera} or {@code audio-loopback},
 *       makes {@link #unlessDevice} fail instead of skipping.</li>
 * </ul>
 */
public final class Skip {
    /**
     * The families of skip reasons.
     *
     * <p>Environment families ({@link #ORACLE}, {@link #DEVICE}, {@link #ENV}) describe what a machine lacks; CI
     * provisions them, so their baseline counts may only shrink. Structural families describe which tests a
     * configuration selects and change only with the tests themselves.
     */
    public enum Family {
        /** A reference tool, or a feature of one (an ffmpeg encoder or filter), is missing. */
        ORACLE("oracle", true),
        /** A capture or playback device is missing or denied. */
        DEVICE("device", true),
        /** A property of the running JVM or machine is missing (thread count, allocation counter, memory). */
        ENV("env", true),
        /** Test data is not available: a conformance vector, a clip, or the output of an oracle script. */
        DATA("data", false),
        /** The Vector API is disabled or narrower than a test needs. */
        SIMD("simd", false),
        /** The test targets another operating system or architecture. */
        PLATFORM("platform", false),
        /** The test runs only when a property or a locally built driver asks for it. */
        OPT_IN("opt-in", false),
        /** A parameter set or an input does not apply to the case under test. */
        INPUT("input", false);

        private final String id;
        private final boolean environment;

        Family(String id, boolean environment) {
            this.id = id;
            this.environment = environment;
        }

        /** {@return the family id, the part of a reason id before the colon} */
        public String id() {
            return id;
        }

        /** {@return whether CI provisions what this family lacks, so that its skips may only shrink} */
        public boolean environment() {
            return environment;
        }
    }

    /** The syntax of a reason id. */
    private static final Pattern REASON = Pattern.compile("([a-z-]+):[a-z0-9][a-z0-9._+-]*");

    /** The prefix every skip message starts with. */
    private static final Pattern PREFIX = Pattern.compile("^\\[(" + REASON.pattern() + ")] ");

    private Skip() {
        throw new AssertionError();
    }

    /**
     * Returns the skip message of a reason, checking the reason id.
     *
     * @param reason the reason id, {@code family:name}
     * @param detail the free-text detail
     * @return the message, {@code [reason] detail}
     * @throws IllegalArgumentException if the reason is not a well-formed id of a known family
     */
    public static String message(String reason, String detail) {
        family(reason);
        return "[" + reason + "] " + detail;
    }

    /**
     * Returns the family of a reason id.
     *
     * @param reason the reason id
     * @return the family
     * @throws IllegalArgumentException if the reason is not a well-formed id of a known family
     */
    public static Family family(String reason) {
        var matcher = REASON.matcher(reason);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("malformed skip reason id: " + reason);
        }
        for (var family : Family.values()) {
            if (family.id.equals(matcher.group(1))) {
                return family;
            }
        }
        throw new IllegalArgumentException("unknown skip reason family: " + reason);
    }

    /**
     * Returns the reason id a skip message starts with.
     *
     * @param message the message, or {@code null}
     * @return the reason id, or {@code null} when the message carries none
     */
    public static String reasonOf(String message) {
        if (message == null) {
            return null;
        }
        var matcher = PREFIX.matcher(message);
        return matcher.find() ? matcher.group(1) : null;
    }

    /**
     * Skips the calling test unless a condition holds.
     *
     * @param condition the condition the test needs
     * @param reason the reason id
     * @param detail what is missing
     */
    public static void unless(boolean condition, String reason, String detail) {
        var message = message(reason, detail);
        if (!condition) {
            throw new TestAbortedException(message);
        }
    }

    /**
     * Skips the calling test when a condition holds.
     *
     * @param condition the condition that makes the test inapplicable
     * @param reason the reason id
     * @param detail why the test does not apply
     */
    public static void when(boolean condition, String reason, String detail) {
        unless(!condition, reason, detail);
    }

    /**
     * Skips the calling test now.
     *
     * @param reason the reason id
     * @param detail why the test cannot run
     * @return never returns normally; declared so that a caller can write {@code throw Skip.now(...)}
     */
    public static TestAbortedException now(String reason, String detail) {
        throw new TestAbortedException(message(reason, detail));
    }

    /**
     * Skips the calling test unless the Vector API kernels run; the test kit cannot read the internal selector, so
     * the caller passes it ({@code Simd.ENABLED}, false under {@code -Dsmalt.simd=false} or without the incubator
     * module).
     *
     * @param enabled whether the vector kernels are selected
     */
    public static void unlessVector(boolean enabled) {
        unless(enabled, "simd:disabled", "the Vector API is disabled");
    }

    /**
     * Skips the calling test unless a capture device of a kind is present; fails instead when
     * {@code smalt.capture.requireDevices} names the kind.
     *
     * @param present whether the device is present and usable
     * @param kind the device kind, for example {@code screen}, {@code camera}, {@code audio-input},
     *        {@code audio-output} or {@code audio-loopback}
     * @param detail what is missing
     */
    public static void unlessDevice(boolean present, String kind, String detail) {
        if (present) {
            return;
        }
        if (requires("smalt.capture.requireDevices", "SMALT_CAPTURE_REQUIRE_DEVICES", kind)) {
            Assertions.fail(message("device:" + kind, detail + " (smalt.capture.requireDevices requires it)"));
        }
        throw now("device:" + kind, detail);
    }

    /**
     * Returns a setting of the test environment: the system property, or the environment variable when the
     * property is not set, which lets CI pass values that contain spaces.
     *
     * @param property the system property
     * @param variable the environment variable
     * @return the value, or the empty string when neither is set
     */
    public static String setting(String property, String variable) {
        var value = System.getProperty(property);
        if (value == null) {
            value = System.getenv(variable);
        }
        return value == null ? "" : value;
    }

    /**
     * Returns whether a requirement switch names an item: the switch is {@code true} (every item) or a
     * comma-separated list containing it.
     *
     * @param property the system property
     * @param variable the environment variable read when the property is not set
     * @param item the item, compared without case
     * @return whether the item is required
     */
    public static boolean requires(String property, String variable, String item) {
        var value = System.getProperty(property);
        if (value == null) {
            value = System.getenv(variable);
        }
        if (value == null || value.isBlank() || value.equalsIgnoreCase("false")) {
            return false;
        }
        if (value.equalsIgnoreCase("true")) {
            return true;
        }
        Set<String> items = Arrays.stream(value.split(","))
                .map(entry -> entry.trim().toLowerCase(Locale.ROOT))
                .filter(entry -> !entry.isEmpty())
                .collect(Collectors.toSet());
        return items.contains(item.toLowerCase(Locale.ROOT));
    }
}
