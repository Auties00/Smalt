package com.github.auties00.smalt.image.color;

import java.util.ArrayList;
import java.util.List;

/**
 * Chains {@link Stage}s into a color transform evaluated on normalized values.
 *
 * <p>A pipeline is mutable while it is assembled ({@link #append}, {@link #prepend},
 * {@link #simplify}) and is then only evaluated. Evaluation allocates nothing but its two
 * ping-pong buffers per call, so a pipeline that is no longer modified is safe to evaluate from
 * several threads.
 *
 * @implNote This implementation follows Little CMS 2.17's {@code cmsPipeline} ({@code cmslut.c}) for evaluation,
 * including the conversion of 16-bit samples to and from {@code float} of {@code _LUTeval16}, and its
 * {@code PreOptimize} ({@code cmsopt.c}) for simplification.
 */
final class Pipeline {
    /**
     * The stages in evaluation order.
     */
    private final List<Stage> stages;

    /**
     * The number of inputs.
     */
    private final int inputs;

    /**
     * The number of outputs.
     */
    private final int outputs;

    /**
     * Creates an empty pipeline.
     *
     * @param inputs the number of inputs
     * @param outputs the number of outputs
     */
    Pipeline(int inputs, int outputs) {
        this.stages = new ArrayList<>();
        this.inputs = inputs;
        this.outputs = outputs;
    }

    /**
     * Returns the number of inputs.
     *
     * @return the input count
     */
    int inputs() {
        return inputs;
    }

    /**
     * Returns the number of outputs.
     *
     * @return the output count
     */
    int outputs() {
        return outputs;
    }

    /**
     * Returns the stages.
     *
     * @return the live list of stages
     */
    List<Stage> stages() {
        return stages;
    }

    /**
     * Appends a stage.
     *
     * @param stage the stage to run after the current last stage
     * @return this pipeline
     */
    Pipeline append(Stage stage) {
        stages.add(stage);
        return this;
    }

    /**
     * Prepends a stage.
     *
     * @param stage the stage to run before the current first stage
     * @return this pipeline
     */
    Pipeline prepend(Stage stage) {
        stages.addFirst(stage);
        return this;
    }

    /**
     * Appends every stage of another pipeline.
     *
     * @param other the pipeline
     * @return this pipeline
     */
    Pipeline append(Pipeline other) {
        stages.addAll(other.stages);
        return this;
    }

    /**
     * Returns a copy of this pipeline with the given channel counts.
     *
     * @param inputs the number of inputs
     * @param outputs the number of outputs
     * @return the copy
     */
    Pipeline copy(int inputs, int outputs) {
        var copy = new Pipeline(inputs, outputs);
        copy.stages.addAll(stages);
        return copy;
    }

    /**
     * Evaluates the pipeline on normalized values.
     *
     * @param in the inputs
     * @param out receives the outputs
     */
    void evaluate(float[] in, float[] out) {
        var width = Math.max(16, Math.max(inputs, outputs));
        var a = new float[width];
        var b = new float[width];
        System.arraycopy(in, 0, a, 0, inputs);
        for (var stage : stages) {
            stage.evaluate(a, b);
            var swap = a;
            a = b;
            b = swap;
        }
        System.arraycopy(a, 0, out, 0, outputs);
    }

    /**
     * Evaluates the pipeline on 16-bit samples.
     *
     * @param in the input samples
     * @param out receives the output samples
     */
    void evaluate16(int[] in, int[] out) {
        var floats = new float[Math.max(16, inputs)];
        for (var i = 0; i < inputs; i++) {
            floats[i] = in[i] / 65535.0f;
        }
        var result = new float[Math.max(16, outputs)];
        evaluate(floats, result);
        for (var i = 0; i < outputs; i++) {
            out[i] = ColorMath.saturateWord(result[i] * 65535.0);
        }
    }

    /**
     * Removes stages that cancel or that leave values unchanged, and multiplies adjacent 3x3
     * matrices without offsets, until nothing changes.
     */
    void simplify() {
        boolean changed;
        do {
            changed = stages.removeIf(stage -> stage.role == Stage.Role.IDENTITY);
            changed |= removePairs(Stage.Kind.XYZ_TO_LAB, null, Stage.Kind.LAB_TO_XYZ, null);
            changed |= removePairs(Stage.Kind.LAB_TO_XYZ, null, Stage.Kind.XYZ_TO_LAB, null);
            changed |= removePairs(null, Stage.Role.LAB_V4_TO_V2, null, Stage.Role.LAB_V2_TO_V4);
            changed |= removePairs(null, Stage.Role.LAB_V2_TO_V4, null, Stage.Role.LAB_V4_TO_V2);
            changed |= multiplyMatrices();
        } while (changed);
    }

    /**
     * Removes every adjacent pair of stages matching two descriptions.
     *
     * @param firstKind the kind of the first stage, or {@code null} to match its role
     * @param firstRole the role of the first stage, when {@code firstKind} is {@code null}
     * @param secondKind the kind of the second stage, or {@code null} to match its role
     * @param secondRole the role of the second stage, when {@code secondKind} is {@code null}
     * @return whether a pair was removed
     */
    private boolean removePairs(Stage.Kind firstKind, Stage.Role firstRole, Stage.Kind secondKind, Stage.Role secondRole) {
        var removed = false;
        var i = 0;
        while (i + 1 < stages.size()) {
            var first = stages.get(i);
            var second = stages.get(i + 1);
            if (implementsMatch(first, firstKind, firstRole) && implementsMatch(second, secondKind, secondRole)) {
                stages.remove(i + 1);
                stages.remove(i);
                removed = true;
            } else {
                i++;
            }
        }
        return removed;
    }

    /**
     * Returns whether a stage implements a described operation.
     *
     * @param stage the stage to test
     * @param kind the kind to match, or {@code null} to match the role
     * @param role the role to match when {@code kind} is {@code null}
     * @return {@code true} on a match
     */
    private static boolean implementsMatch(Stage stage, Stage.Kind kind, Stage.Role role) {
        if (kind != null) {
            return stage.kind() == kind && stage.role == Stage.Role.PLAIN;
        }
        return stage.role == role;
    }

    /**
     * Multiplies adjacent plain 3x3 matrices; stops at the first pair where either has an
     * offset.
     *
     * @return whether a pair was multiplied
     */
    private boolean multiplyMatrices() {
        var any = false;
        var i = 0;
        while (i + 1 < stages.size()) {
            var first = stages.get(i);
            var second = stages.get(i + 1);
            if (first instanceof Stage.Matrix m1 && first.role == Stage.Role.PLAIN
                    && second instanceof Stage.Matrix m2 && second.role == Stage.Role.PLAIN) {
                if (m1.offset != null || m2.offset != null || m1.inputs != 3 || m1.outputs != 3
                        || m2.inputs != 3 || m2.outputs != 3) {
                    return false;
                }
                var product = ColorMath.multiply(m2.coefficients, m1.coefficients);
                stages.remove(i + 1);
                stages.remove(i);
                if (!isNearIdentity(product)) {
                    stages.add(i, new Stage.Matrix(product, null));
                }
                any = true;
            } else {
                i++;
            }
        }
        return any;
    }

    /**
     * Returns whether every element of a matrix is within {@code 0.00001} of the identity.
     *
     * @param m the matrix
     * @return {@code true} if the matrix is the identity within that tolerance
     */
    private static boolean isNearIdentity(double[] m) {
        for (var i = 0; i < 9; i++) {
            var expected = i % 4 == 0 ? 1.0 : 0.0;
            if (Math.abs(m[i] - expected) >= 0.00001f) {
                return false;
            }
        }
        return true;
    }
}
