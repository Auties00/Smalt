package com.github.auties00.smalt.internal.check;

import java.time.Duration;
import java.util.Collection;

/**
 * Validates the settings of option builders with one message format.
 *
 * <p>Every check returns its value when it holds and otherwise throws an
 * {@link IllegalArgumentException} whose message names the setting, the value and the rule:
 * {@snippet :
 * quality 120 is not in [0, 100]
 * thread count -1 is negative
 * bitrate 0 is not positive
 * channel layout is empty
 * }
 * The {@code name} argument is the setting as a lowercase phrase. A {@code double} check rejects
 * NaN. This class is thread-safe.
 */
public final class Checks {
    /**
     * Prevents instantiation.
     */
    private Checks() {
        throw new AssertionError();
    }

    /**
     * Checks that an {@code int} setting lies in a closed range.
     *
     * @param name the setting, for the message
     * @param value the value to check
     * @param min the smallest accepted value
     * @param max the largest accepted value
     * @return {@code value}
     * @throws IllegalArgumentException if {@code value} is below {@code min} or above {@code max}
     */
    public static int inRange(String name, int value, int min, int max) {
        if (value < min || value > max) {
            throw outOfRange(name, String.valueOf(value), String.valueOf(min), String.valueOf(max));
        }
        return value;
    }

    /**
     * Checks that a {@code long} setting lies in a closed range.
     *
     * @param name the setting, for the message
     * @param value the value to check
     * @param min the smallest accepted value
     * @param max the largest accepted value
     * @return {@code value}
     * @throws IllegalArgumentException if {@code value} is below {@code min} or above {@code max}
     */
    public static long inRange(String name, long value, long min, long max) {
        if (value < min || value > max) {
            throw outOfRange(name, String.valueOf(value), String.valueOf(min), String.valueOf(max));
        }
        return value;
    }

    /**
     * Checks that a {@code double} setting lies in a closed range.
     *
     * @param name the setting, for the message
     * @param value the value to check
     * @param min the smallest accepted value
     * @param max the largest accepted value
     * @return {@code value}
     * @throws IllegalArgumentException if {@code value} is NaN, below {@code min} or above
     * {@code max}
     */
    public static double inRange(String name, double value, double min, double max) {
        if (!(value >= min && value <= max)) {
            throw outOfRange(name, String.valueOf(value), String.valueOf(min), String.valueOf(max));
        }
        return value;
    }

    /**
     * Checks that a duration setting lies in a closed range.
     *
     * @param name the setting, for the message
     * @param value the value to check
     * @param min the shortest accepted duration
     * @param max the longest accepted duration
     * @return {@code value}
     * @throws IllegalArgumentException if {@code value} is shorter than {@code min} or longer than
     * {@code max}
     */
    public static Duration inRange(String name, Duration value, Duration min, Duration max) {
        if (value.compareTo(min) < 0 || value.compareTo(max) > 0) {
            throw outOfRange(name, value.toString(), min.toString(), max.toString());
        }
        return value;
    }

    /**
     * Checks that an {@code int} setting is greater than zero.
     *
     * @param name the setting, for the message
     * @param value the value to check
     * @return {@code value}
     * @throws IllegalArgumentException if {@code value} is zero or negative
     */
    public static int positive(String name, int value) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " " + value + " is not positive");
        }
        return value;
    }

    /**
     * Checks that a {@code long} setting is greater than zero.
     *
     * @param name the setting, for the message
     * @param value the value to check
     * @return {@code value}
     * @throws IllegalArgumentException if {@code value} is zero or negative
     */
    public static long positive(String name, long value) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " " + value + " is not positive");
        }
        return value;
    }

    /**
     * Checks that a {@code double} setting is greater than zero.
     *
     * @param name the setting, for the message
     * @param value the value to check
     * @return {@code value}
     * @throws IllegalArgumentException if {@code value} is NaN, zero or negative
     */
    public static double positive(String name, double value) {
        if (!(value > 0)) {
            throw new IllegalArgumentException(name + " " + value + " is not positive");
        }
        return value;
    }

    /**
     * Checks that a duration setting is longer than zero.
     *
     * @param name the setting, for the message
     * @param value the value to check
     * @return {@code value}
     * @throws IllegalArgumentException if {@code value} is zero or negative
     */
    public static Duration positive(String name, Duration value) {
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " " + value + " is not positive");
        }
        return value;
    }

    /**
     * Checks that an {@code int} setting is zero or greater.
     *
     * @param name the setting, for the message
     * @param value the value to check
     * @return {@code value}
     * @throws IllegalArgumentException if {@code value} is negative
     */
    public static int nonNegative(String name, int value) {
        if (value < 0) {
            throw new IllegalArgumentException(name + " " + value + " is negative");
        }
        return value;
    }

    /**
     * Checks that a {@code long} setting is zero or greater.
     *
     * @param name the setting, for the message
     * @param value the value to check
     * @return {@code value}
     * @throws IllegalArgumentException if {@code value} is negative
     */
    public static long nonNegative(String name, long value) {
        if (value < 0) {
            throw new IllegalArgumentException(name + " " + value + " is negative");
        }
        return value;
    }

    /**
     * Checks that a {@code double} setting is zero or greater.
     *
     * @param name the setting, for the message
     * @param value the value to check
     * @return {@code value}
     * @throws IllegalArgumentException if {@code value} is NaN or negative
     */
    public static double nonNegative(String name, double value) {
        if (!(value >= 0)) {
            throw new IllegalArgumentException(name + " " + value + (value < 0 ? " is negative" : " is not a number"));
        }
        return value;
    }

    /**
     * Checks that a duration setting is zero or longer.
     *
     * @param name the setting, for the message
     * @param value the value to check
     * @return {@code value}
     * @throws IllegalArgumentException if {@code value} is negative
     */
    public static Duration nonNegative(String name, Duration value) {
        if (value.isNegative()) {
            throw new IllegalArgumentException(name + " " + value + " is negative");
        }
        return value;
    }

    /**
     * Checks that a collection setting holds at least one element.
     *
     * @param name the setting, for the message
     * @param value the collection
     * @param <C> the collection type
     * @return {@code value}
     * @throws IllegalArgumentException if {@code value} is empty
     */
    public static <C extends Collection<?>> C notEmpty(String name, C value) {
        if (value.isEmpty()) {
            throw new IllegalArgumentException(name + " is empty");
        }
        return value;
    }

    /**
     * Checks that a text setting holds at least one character.
     *
     * @param name the setting, for the message
     * @param value the text
     * @return {@code value}
     * @throws IllegalArgumentException if {@code value} is empty
     */
    public static String notEmpty(String name, String value) {
        if (value.isEmpty()) {
            throw new IllegalArgumentException(name + " is empty");
        }
        return value;
    }

    /**
     * Checks that an array setting holds at least one element.
     *
     * @param name the setting, for the message
     * @param value the array
     * @return {@code value}
     * @throws IllegalArgumentException if {@code value} is empty
     */
    public static byte[] notEmpty(String name, byte[] value) {
        if (value.length == 0) {
            throw new IllegalArgumentException(name + " is empty");
        }
        return value;
    }

    /**
     * {@return the failure of a range check}
     *
     * @param name the setting, for the message
     * @param value the rejected value
     * @param min the smallest accepted value
     * @param max the largest accepted value
     */
    private static IllegalArgumentException outOfRange(String name, String value, String min, String max) {
        return new IllegalArgumentException(name + " " + value + " is not in [" + min + ", " + max + "]");
    }
}
