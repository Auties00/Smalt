package com.github.auties00.smalt.testkit.fuzz;

import com.github.auties00.smalt.MediaException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * The run of one fuzz target over one input: the steps it took and the {@link MediaException}s they threw.
 *
 * <p>A step that throws a {@code MediaException} is rejected and the run goes on, unless the exception hides
 * an internal failure ({@link Fuzz#check}); any other throwable propagates. Instances are not thread-safe.
 */
public final class FuzzRun {
    private final String target;
    private final boolean thorough;
    private final List<String> rejections = new ArrayList<>();
    private int accepted;
    private long outputs;
    private String skipped;

    FuzzRun(String target, boolean thorough) {
        this.target = target;
        this.thorough = thorough;
    }

    /**
     * Returns whether one of several costly alternative steps runs on an input.
     *
     * <p>A thorough run ({@link Fuzz#thorough(boolean)}) runs every alternative; otherwise exactly one runs, chosen
     * by a hash of the input, so the choice is the same every time the input runs.
     *
     * @param data the input
     * @param choice the alternative, from 0 to {@code choices - 1}
     * @param choices the number of alternatives
     * @return {@code true} if the alternative runs
     */
    public boolean sampled(byte[] data, int choice, int choices) {
        return thorough || Integer.remainderUnsigned(Arrays.hashCode(data), choices) == choice;
    }

    /**
     * Runs one step of the target.
     *
     * @param name the name of the step, for messages
     * @param action the step
     * @return {@code true} if the step completed, {@code false} if it threw a {@link MediaException}
     */
    public boolean step(String name, Runnable action) {
        try {
            action.run();
            accepted++;
            return true;
        } catch (MediaException exception) {
            rejected(name, exception);
            return false;
        }
    }

    /**
     * Runs one step of the target that computes a value.
     *
     * @param <T> the type of the value
     * @param name the name of the step, for messages
     * @param action the step
     * @return the value, or empty if the step threw a {@link MediaException} or returned {@code null}
     */
    public <T> Optional<T> value(String name, Supplier<T> action) {
        try {
            var value = action.get();
            accepted++;
            return Optional.ofNullable(value);
        } catch (MediaException exception) {
            rejected(name, exception);
            return Optional.empty();
        }
    }

    /**
     * Runs one step whose support depends on the format of the input, such as a metadata edit a format cannot
     * store: a {@link MediaException.Unsupported} is not a rejection.
     *
     * @param <T> the type of the value
     * @param name the name of the step, for messages
     * @param action the step
     * @return the value, or empty if the step threw a {@link MediaException} or returned {@code null}
     */
    public <T> Optional<T> optional(String name, Supplier<T> action) {
        try {
            var value = action.get();
            accepted++;
            return Optional.ofNullable(value);
        } catch (MediaException.Unsupported exception) {
            Fuzz.check(target + ": " + name, exception);
            return Optional.empty();
        } catch (MediaException exception) {
            rejected(name, exception);
            return Optional.empty();
        }
    }

    private void rejected(String name, MediaException exception) {
        Fuzz.check(target + ": " + name, exception);
        rejections.add(name + ": " + exception);
    }

    /**
     * Records that the input is not of the kind the target reads, so the target stopped before its steps.
     *
     * @param reason why the input was skipped
     */
    public void skip(String reason) {
        skipped = reason;
    }

    /**
     * Records a rejection that did not come from an exception, for example an output the target checks.
     *
     * @param reason what was rejected
     */
    public void reject(String reason) {
        rejections.add(reason);
    }

    /**
     * Counts one output of the entry point: a decoded picture, frame or audio buffer, or a demuxed packet.
     */
    public void output() {
        outputs++;
    }

    /**
     * {@return the outputs counted by {@link #output()}}
     */
    public long outputs() {
        return outputs;
    }

    /**
     * {@return whether the target ran at least one step and every step completed}
     */
    public boolean fullyAccepted() {
        return skipped == null && rejections.isEmpty() && accepted > 0;
    }

    /**
     * {@return the steps that completed}
     */
    public int accepted() {
        return accepted;
    }

    /**
     * {@return the rejected steps, each as its name and the exception}
     */
    public List<String> rejections() {
        return List.copyOf(rejections);
    }

    @Override
    public String toString() {
        if (skipped != null) {
            return target + ": skipped (" + skipped + ")";
        }
        return target + ": " + accepted + " steps accepted, " + outputs + " outputs, rejected " + rejections;
    }
}
