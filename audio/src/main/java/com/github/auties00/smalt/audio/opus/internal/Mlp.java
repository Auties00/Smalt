/* Ports libopus 1.5.2 (src/mlp.c, src/mlp.h). Copyright Xiph.Org Foundation and Skype Limited; BSD 3-Clause License.
   Modified for Smalt. */
package com.github.auties00.smalt.audio.opus.internal;

/**
 * Evaluates the small quantized neural network that classifies analysis frames as speech or
 * music.
 *
 * @implNote This implementation keeps the reference's evaluation order of every multiply-add, so results match bit for
 * bit.
 */
final class Mlp {
    /**
     * The scale of the 8-bit weights ({@code WEIGHTS_SCALE}).
     */
    static final float WEIGHTS_SCALE = 1.f / 128;

    /**
     * The largest number of neurons in a layer ({@code MAX_NEURONS}).
     */
    static final int MAX_NEURONS = 32;

    /**
     * Describes a fully connected layer ({@code AnalysisDenseLayer}).
     *
     * @param bias          the bias of each neuron
     * @param input_weights the weights, {@code nb_inputs} rows of {@code nb_neurons}
     * @param nb_inputs     the number of inputs
     * @param nb_neurons    the number of neurons
     * @param sigmoid       whether the activation is a sigmoid rather than a tanh
     */
    record DenseLayer(byte[] bias, byte[] input_weights, int nb_inputs, int nb_neurons, boolean sigmoid) {
    }

    /**
     * Describes a gated recurrent unit layer ({@code AnalysisGRULayer}).
     *
     * @param bias              the biases of the update, reset and output gates
     * @param input_weights     the input weights, {@code nb_inputs} rows of {@code 3 * nb_neurons}
     * @param recurrent_weights the state weights, {@code nb_neurons} rows of {@code 3 * nb_neurons}
     * @param nb_inputs         the number of inputs
     * @param nb_neurons        the number of neurons
     */
    record GruLayer(byte[] bias, byte[] input_weights, byte[] recurrent_weights, int nb_inputs, int nb_neurons) {
    }

    private Mlp() {
        throw new AssertionError();
    }

    /**
     * Returns a rational approximation of {@code tanh(x)}, clamped to {@code [-1, 1]}
     * ({@code tansig_approx}).
     *
     * @param x the argument
     * @return the approximation
     */
    static float tansigApprox(float x) {
        final var N0 = 952.52801514f;
        final var N1 = 96.39235687f;
        final var N2 = 0.60863042f;
        final var D0 = 952.72399902f;
        final var D1 = 413.36801147f;
        final var D2 = 11.88600922f;
        var X2 = x * x;
        var num = (N2 * X2 + N1) * X2 + N0;
        var den = (D2 * X2 + D1) * X2 + D0;
        num = num * x / den;
        var clamped = 1.f < num ? 1.f : num;
        return -1.f > clamped ? -1.f : clamped;
    }

    /**
     * Returns an approximation of the logistic sigmoid ({@code sigmoid_approx}).
     *
     * @param x the argument
     * @return the approximation
     */
    static float sigmoidApprox(float x) {
        return .5f + .5f * tansigApprox(.5f * x);
    }

    /**
     * Accumulates a matrix-vector product into {@code out} ({@code gemm_accum}).
     *
     * <p>The loops are interchanged relative to the reference so the inner loop runs over
     * contiguous weights and vectorizes, but every output still accumulates its terms in
     * increasing input order, so the results are bit-identical.
     *
     * @param out the accumulators, {@code rows} of them
     * @param outOff the index of the first accumulator
     * @param weights the 8-bit weights, {@code rows} of them per input
     * @param weightsOff the index of the first weight
     * @param rows the number of outputs
     * @param cols the number of inputs
     * @param col_stride the distance between consecutive inputs' weight rows
     * @param x the inputs
     * @param xOff the index of the first input
     */
    private static void gemmAccum(float[] out, int outOff, byte[] weights, int weightsOff, int rows, int cols,
                                  int col_stride, float[] x, int xOff) {
        for (var j = 0; j < cols; j++) {
            var xj = x[xOff + j];
            var row = weightsOff + j * col_stride;
            for (var i = 0; i < rows; i++) {
                out[outOff + i] += weights[row + i] * xj;
            }
        }
    }

    /**
     * Evaluates a dense layer ({@code analysis_compute_dense}).
     *
     * @param layer the dense layer to evaluate
     * @param output receives the {@code nb_neurons} outputs
     * @param input the {@code nb_inputs} inputs
     */
    static void computeDense(DenseLayer layer, float[] output, float[] input) {
        var M = layer.nb_inputs();
        var N = layer.nb_neurons();
        for (var i = 0; i < N; i++) {
            output[i] = layer.bias()[i];
        }
        gemmAccum(output, 0, layer.input_weights(), 0, N, M, N, input, 0);
        for (var i = 0; i < N; i++) {
            output[i] *= WEIGHTS_SCALE;
        }
        if (layer.sigmoid()) {
            for (var i = 0; i < N; i++) {
                output[i] = sigmoidApprox(output[i]);
            }
        } else {
            for (var i = 0; i < N; i++) {
                output[i] = tansigApprox(output[i]);
            }
        }
    }

    /**
     * Advances a GRU layer by one step ({@code analysis_compute_gru}).
     *
     * @param gru     the layer
     * @param state   the {@code nb_neurons} state values, updated in place
     * @param input   the {@code nb_inputs} inputs
     * @param scratch at least {@code 4 * MAX_NEURONS} floats of scratch space
     */
    static void computeGru(GruLayer gru, float[] state, float[] input, float[] scratch) {
        var M = gru.nb_inputs();
        var N = gru.nb_neurons();
        var stride = 3 * N;
        var bias = gru.bias();
        var z = 0;
        var r = MAX_NEURONS;
        var h = 2 * MAX_NEURONS;
        var tmp = 3 * MAX_NEURONS;
        for (var i = 0; i < N; i++) {
            scratch[z + i] = bias[i];
        }
        gemmAccum(scratch, z, gru.input_weights(), 0, N, M, stride, input, 0);
        gemmAccum(scratch, z, gru.recurrent_weights(), 0, N, N, stride, state, 0);
        for (var i = 0; i < N; i++) {
            scratch[z + i] = sigmoidApprox(WEIGHTS_SCALE * scratch[z + i]);
        }
        for (var i = 0; i < N; i++) {
            scratch[r + i] = bias[N + i];
        }
        gemmAccum(scratch, r, gru.input_weights(), N, N, M, stride, input, 0);
        gemmAccum(scratch, r, gru.recurrent_weights(), N, N, N, stride, state, 0);
        for (var i = 0; i < N; i++) {
            scratch[r + i] = sigmoidApprox(WEIGHTS_SCALE * scratch[r + i]);
        }
        for (var i = 0; i < N; i++) {
            scratch[h + i] = bias[2 * N + i];
        }
        for (var i = 0; i < N; i++) {
            scratch[tmp + i] = state[i] * scratch[r + i];
        }
        gemmAccum(scratch, h, gru.input_weights(), 2 * N, N, M, stride, input, 0);
        gemmAccum(scratch, h, gru.recurrent_weights(), 2 * N, N, N, stride, scratch, tmp);
        for (var i = 0; i < N; i++) {
            scratch[h + i] = scratch[z + i] * state[i] + (1 - scratch[z + i]) * tansigApprox(WEIGHTS_SCALE * scratch[h + i]);
        }
        for (var i = 0; i < N; i++) {
            state[i] = scratch[h + i];
        }
    }
}
