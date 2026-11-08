/* Ports libopus 1.5.2 (dnn/nnet.c, dnn/nnet_arch.h, dnn/vec.h). Copyright Mozilla; BSD 3-Clause License.
   Modified for Smalt. */
package com.github.auties00.smalt.audio.opus.internal.dnn;

/**
 * Evaluates the layers of the Opus neural networks: affine transforms, activations, GRUs, causal
 * one-dimensional convolutions, gated linear units and two-dimensional convolutions.
 *
 * <p>Every result is bit-identical to the generic C code of libopus 1.5.2 ({@code vec.h} without SIMD), whose
 * int8 layers quantize their input to signed bytes. Arrays are addressed as {@code (array, offset)} pairs;
 * unless a method says otherwise, an output must not overlap an input. Arguments are not checked; values outside
 * the documented ranges give unspecified results. Instances own the scratch space of the computations and are
 * not thread-safe.
 */
final class Nnet {
    /**
     * The identity activation ({@code ACTIVATION_LINEAR}).
     */
    static final int ACTIVATION_LINEAR = 0;

    /**
     * The approximated logistic activation ({@code ACTIVATION_SIGMOID}).
     */
    static final int ACTIVATION_SIGMOID = 1;

    /**
     * The approximated hyperbolic tangent activation ({@code ACTIVATION_TANH}).
     */
    static final int ACTIVATION_TANH = 2;

    /**
     * The rectified linear activation ({@code ACTIVATION_RELU}).
     */
    static final int ACTIVATION_RELU = 3;

    /**
     * The softmax activation, which libopus evaluates as the identity ({@code SOFTMAX_HACK}).
     */
    static final int ACTIVATION_SOFTMAX = 4;

    /**
     * The swish activation, {@code x * sigmoid(x)} ({@code ACTIVATION_SWISH}).
     */
    static final int ACTIVATION_SWISH = 5;

    /**
     * The largest layer input ({@code MAX_INPUTS}).
     */
    private static final int MAX_INPUTS = 2048;

    /**
     * The largest GRU state the scratch space holds.
     */
    private static final int MAX_RNN_NEURONS = 512;

    /**
     * The largest two-dimensional convolution input ({@code MAX_CONV2D_INPUTS}).
     */
    private static final int MAX_CONV2D_INPUTS = 8192;

    /**
     * The gate and candidate pre-activations of a GRU.
     */
    private final float[] zrh = new float[3 * MAX_RNN_NEURONS];

    /**
     * The recurrent contribution of a GRU.
     */
    private final float[] recur = new float[3 * MAX_RNN_NEURONS];

    /**
     * The input of a convolution with its history, or the gate of a gated linear unit.
     */
    private final float[] tmp = new float[MAX_INPUTS];

    /**
     * The sigmoid of a swish activation.
     */
    private final float[] swish = new float[MAX_INPUTS];

    /**
     * The quantized input of an int8 layer.
     */
    private final byte[] quantized = new byte[MAX_INPUTS];

    /**
     * The input of a two-dimensional convolution with its history.
     */
    private final float[] conv2d = new float[MAX_CONV2D_INPUTS];

    /**
     * Computes a dense layer followed by an activation ({@code compute_generic_dense}).
     *
     * @param layer the affine transform
     * @param output receives {@code layer.nb_outputs} values
     * @param outputOffset the index of the first output
     * @param input the {@code layer.nb_inputs} inputs
     * @param inputOffset the index of the first input
     * @param activation the {@code ACTIVATION_*} constant
     */
    void dense(LinearLayer layer, float[] output, int outputOffset, float[] input, int inputOffset, int activation) {
        linear(layer, output, outputOffset, input, inputOffset);
        activation(output, outputOffset, output, outputOffset, layer.nb_outputs, activation);
    }

    /**
     * Updates a GRU state with one input frame ({@code compute_generic_gru}).
     *
     * @param inputWeights the input layer, {@code 3 * N} outputs
     * @param recurrentWeights the recurrent layer, {@code N} inputs and {@code 3 * N} outputs
     * @param state the {@code N} state values, updated in place
     * @param stateOffset the index of the first state value
     * @param input the inputs, which must not overlap the state
     * @param inputOffset the index of the first input
     */
    void gru(LinearLayer inputWeights, LinearLayer recurrentWeights, float[] state, int stateOffset, float[] input,
             int inputOffset) {
        var n = recurrentWeights.nb_inputs;
        linear(inputWeights, zrh, 0, input, inputOffset);
        linear(recurrentWeights, recur, 0, state, stateOffset);
        for (var i = 0; i < 2 * n; i++) {
            zrh[i] += recur[i];
        }
        activation(zrh, 0, zrh, 0, 2 * n, ACTIVATION_SIGMOID);
        for (var i = 0; i < n; i++) {
            zrh[2 * n + i] += recur[2 * n + i] * zrh[n + i];
        }
        activation(zrh, 2 * n, zrh, 2 * n, n, ACTIVATION_TANH);
        for (var i = 0; i < n; i++) {
            zrh[2 * n + i] = zrh[i] * state[stateOffset + i] + (1 - zrh[i]) * zrh[2 * n + i];
        }
        System.arraycopy(zrh, 2 * n, state, stateOffset, n);
    }

    /**
     * Computes a gated linear unit, {@code input * sigmoid(layer(input))} ({@code compute_glu}).
     *
     * @param layer the gate layer, as many inputs as outputs
     * @param output receives the outputs; may be the input
     * @param outputOffset the index of the first output
     * @param input the inputs
     * @param inputOffset the index of the first input
     */
    void glu(LinearLayer layer, float[] output, int outputOffset, float[] input, int inputOffset) {
        linear(layer, tmp, 0, input, inputOffset);
        activation(tmp, 0, tmp, 0, layer.nb_outputs, ACTIVATION_SIGMOID);
        for (var i = 0; i < layer.nb_outputs; i++) {
            output[outputOffset + i] = input[inputOffset + i] * tmp[i];
        }
    }

    /**
     * Computes one frame of a causal one-dimensional convolution ({@code compute_generic_conv1d}).
     *
     * @param layer the layer, whose input is the history followed by the new frame
     * @param output receives the outputs
     * @param outputOffset the index of the first output
     * @param mem the {@code layer.nb_inputs - inputSize} history values, updated; ignored when there are none
     * @param memOffset the index of the first history value
     * @param input the new frame
     * @param inputOffset the index of its first value
     * @param inputSize the frame size
     * @param activation the {@code ACTIVATION_*} constant
     */
    void conv1d(LinearLayer layer, float[] output, int outputOffset, float[] mem, int memOffset, float[] input,
                int inputOffset, int inputSize, int activation) {
        var history = layer.nb_inputs - inputSize;
        if (history != 0) {
            System.arraycopy(mem, memOffset, tmp, 0, history);
        }
        System.arraycopy(input, inputOffset, tmp, history, inputSize);
        linear(layer, output, outputOffset, tmp, 0);
        activation(output, outputOffset, output, outputOffset, layer.nb_outputs, activation);
        if (history != 0) {
            System.arraycopy(tmp, inputSize, mem, memOffset, history);
        }
    }

    /**
     * Computes one frame of a dilated causal one-dimensional convolution
     * ({@code compute_generic_conv1d_dilation}).
     *
     * @param layer the convolution kernel
     * @param output receives the outputs
     * @param outputOffset the index of the first output
     * @param mem the {@code inputSize * dilation * (ksize - 1)} history values, updated
     * @param memOffset the index of the first history value
     * @param input the new frame
     * @param inputOffset the index of its first value
     * @param inputSize the frame size
     * @param dilation the dilation in frames
     * @param activation the {@code ACTIVATION_*} constant
     */
    void conv1dDilation(LinearLayer layer, float[] output, int outputOffset, float[] mem, int memOffset,
                        float[] input, int inputOffset, int inputSize, int dilation, int activation) {
        var ksize = layer.nb_inputs / inputSize;
        if (dilation == 1) {
            System.arraycopy(mem, memOffset, tmp, 0, layer.nb_inputs - inputSize);
        } else {
            for (var i = 0; i < ksize - 1; i++) {
                System.arraycopy(mem, memOffset + i * inputSize * dilation, tmp, i * inputSize, inputSize);
            }
        }
        System.arraycopy(input, inputOffset, tmp, layer.nb_inputs - inputSize, inputSize);
        linear(layer, output, outputOffset, tmp, 0);
        activation(output, outputOffset, output, outputOffset, layer.nb_outputs, activation);
        if (dilation == 1) {
            System.arraycopy(tmp, inputSize, mem, memOffset, layer.nb_inputs - inputSize);
        } else {
            var total = inputSize * dilation * (ksize - 1);
            System.arraycopy(mem, memOffset + inputSize, mem, memOffset, total - inputSize);
            System.arraycopy(input, inputOffset, mem, memOffset + total - inputSize, inputSize);
        }
    }

    /**
     * Computes one frame of a two-dimensional convolution ({@code compute_conv2d}).
     *
     * @param conv the layer
     * @param out receives {@code out_channels} rows of {@code height} values, {@code hstride} apart
     * @param outOffset the index of the first output
     * @param mem the {@code (ktime - 1) * in_channels * (height + kheight - 1)} history values, updated
     * @param in the {@code in_channels * (height + kheight - 1)} values of the new frame
     * @param inOffset the index of its first value
     * @param height the number of output values per channel
     * @param hstride the distance between output rows
     * @param activation the {@code ACTIVATION_*} constant
     */
    void conv2d(Conv2dLayer conv, float[] out, int outOffset, float[] mem, float[] in, int inOffset, int height,
                int hstride, int activation) {
        var buf = conv2d;
        var timeStride = conv.in_channels * (height + conv.kheight - 1);
        System.arraycopy(mem, 0, buf, 0, (conv.ktime - 1) * timeStride);
        System.arraycopy(in, inOffset, buf, (conv.ktime - 1) * timeStride, timeStride);
        System.arraycopy(buf, timeStride, mem, 0, (conv.ktime - 1) * timeStride);
        var w = conv.float_weights;
        var inStride = height + conv.kheight - 1;
        if (conv.kheight == 3 && conv.ktime == 3) {
            for (var i = 0; i < conv.out_channels; i++) {
                var o = outOffset + i * hstride;
                for (var j = 0; j < height; j++) {
                    out[o + j] = 0;
                }
                for (var m = 0; m < conv.in_channels; m++) {
                    var wb = i * conv.in_channels * 9 + m * 9;
                    var i0 = m * inStride;
                    var i1 = conv.in_channels * inStride + m * inStride;
                    var i2 = 2 * conv.in_channels * inStride + m * inStride;
                    for (var j = 0; j < height; j++) {
                        out[o + j] += w[wb] * buf[i0 + j] + w[wb + 1] * buf[i0 + j + 1] + w[wb + 2] * buf[i0 + j + 2]
                                + w[wb + 3] * buf[i1 + j] + w[wb + 4] * buf[i1 + j + 1] + w[wb + 5] * buf[i1 + j + 2]
                                + w[wb + 6] * buf[i2 + j] + w[wb + 7] * buf[i2 + j + 1] + w[wb + 8] * buf[i2 + j + 2];
                    }
                }
            }
        } else {
            var kt = conv.ktime;
            var kh = conv.kheight;
            for (var i = 0; i < conv.out_channels; i++) {
                var o = outOffset + i * hstride;
                for (var j = 0; j < height; j++) {
                    out[o + j] = 0;
                }
                for (var m = 0; m < conv.in_channels; m++) {
                    for (var t = 0; t < kt; t++) {
                        for (var h = 0; h < kh; h++) {
                            var wv = w[i * conv.in_channels * kt * kh + m * kt * kh + t * kh + h];
                            var base = t * conv.in_channels * inStride + m * inStride + h;
                            for (var j = 0; j < height; j++) {
                                out[o + j] += wv * buf[base + j];
                            }
                        }
                    }
                }
            }
        }
        if (conv.bias != null) {
            for (var i = 0; i < conv.out_channels; i++) {
                for (var j = 0; j < height; j++) {
                    out[outOffset + i * hstride + j] += conv.bias[i];
                }
            }
        }
        for (var i = 0; i < conv.out_channels; i++) {
            activation(out, outOffset + i * hstride, out, outOffset + i * hstride, height, activation);
        }
    }

    /**
     * Computes an affine transform: the weighted sums, then the bias, then the diagonal term of a GRU recurrent
     * layer ({@code compute_linear_c}).
     *
     * @param linear the layer
     * @param out receives {@code linear.nb_outputs} values
     * @param outOffset the index of the first output
     * @param in the {@code linear.nb_inputs} inputs
     * @param inOffset the index of the first input
     */
    void linear(LinearLayer linear, float[] out, int outOffset, float[] in, int inOffset) {
        var m = linear.nb_inputs;
        var n = linear.nb_outputs;
        if (linear.float_weights != null) {
            if (linear.weights_idx != null) {
                sparseSgemv8x4(out, outOffset, linear.float_weights, linear.weights_idx, n, in, inOffset);
            } else {
                sgemv(out, outOffset, linear.float_weights, n, m, in, inOffset);
            }
        } else if (linear.weights != null) {
            if (linear.weights_idx != null) {
                sparseCgemv8x4(out, outOffset, linear.weights, linear.weights_idx, linear.scale, n, m, in, inOffset);
            } else {
                cgemv8x4(out, outOffset, linear.weights, linear.scale, n, m, in, inOffset);
            }
        } else {
            for (var i = 0; i < n; i++) {
                out[outOffset + i] = 0;
            }
        }
        var bias = linear.bias;
        if (bias != null) {
            for (var i = 0; i < n; i++) {
                out[outOffset + i] += bias[i];
            }
        }
        var diag = linear.diag;
        if (diag != null) {
            for (var i = 0; i < m; i++) {
                var x = in[inOffset + i];
                out[outOffset + i] += diag[i] * x;
                out[outOffset + i + m] += diag[i + m] * x;
                out[outOffset + i + 2 * m] += diag[i + 2 * m] * x;
            }
        }
    }

    /**
     * Multiplies a dense column-major float matrix by a vector; each output sums its terms in input order
     * ({@code sgemv}).
     */
    private static void sgemv(float[] out, int outOffset, float[] weights, int rows, int cols, float[] x, int xOffset) {
        for (var i = 0; i < rows; i++) {
            out[outOffset + i] = 0;
        }
        for (var j = 0; j < cols; j++) {
            var xj = x[xOffset + j];
            var w = j * rows;
            for (var i = 0; i < rows; i++) {
                out[outOffset + i] += weights[w + i] * xj;
            }
        }
    }

    /**
     * Multiplies a sparse float matrix of 8 by 4 blocks by a vector ({@code sparse_sgemv8x4}).
     */
    private static void sparseSgemv8x4(float[] out, int outOffset, float[] w, int[] idx, int rows, float[] x,
                                       int xOffset) {
        for (var i = 0; i < rows; i++) {
            out[outOffset + i] = 0;
        }
        var k = 0;
        var wp = 0;
        for (var i = 0; i < rows; i += 8) {
            var cols = idx[k++];
            var y = outOffset + i;
            for (var j = 0; j < cols; j++) {
                var pos = xOffset + idx[k++];
                for (var c = 0; c < 4; c++) {
                    var xj = x[pos + c];
                    var wc = wp + 8 * c;
                    for (var r = 0; r < 8; r++) {
                        out[y + r] += w[wc + r] * xj;
                    }
                }
                wp += 32;
            }
        }
    }

    /**
     * Quantizes a layer input to signed bytes, {@code (int) floor(0.5 + 127 * x)} wrapped to 8 bits as the C
     * conversion to {@code opus_int8} does.
     */
    private void quantize(float[] x, int xOffset, int cols) {
        var q = quantized;
        for (var i = 0; i < cols; i++) {
            q[i] = (byte) (int) StrictMath.floor(.5 + 127 * x[xOffset + i]);
        }
    }

    /**
     * Multiplies a dense int8 matrix of 8 by 4 blocks by a quantized vector, then scales each output
     * ({@code cgemv8x4}).
     */
    private void cgemv8x4(float[] out, int outOffset, byte[] w, float[] scale, int rows, int cols, float[] x,
                          int xOffset) {
        quantize(x, xOffset, cols);
        var q = quantized;
        for (var i = 0; i < rows; i++) {
            out[outOffset + i] = 0;
        }
        var wp = 0;
        for (var i = 0; i < rows; i += 8) {
            var y = outOffset + i;
            for (var j = 0; j < cols; j += 4) {
                int x0 = q[j];
                int x1 = q[j + 1];
                int x2 = q[j + 2];
                int x3 = q[j + 3];
                for (var r = 0; r < 8; r++) {
                    var b = wp + 4 * r;
                    out[y + r] += (float) (w[b] * x0 + w[b + 1] * x1 + w[b + 2] * x2 + w[b + 3] * x3);
                }
                wp += 32;
            }
        }
        for (var i = 0; i < rows; i++) {
            out[outOffset + i] *= scale[i];
        }
    }

    /**
     * Multiplies a sparse int8 matrix of 8 by 4 blocks by a quantized vector, then scales each output
     * ({@code sparse_cgemv8x4}).
     */
    private void sparseCgemv8x4(float[] out, int outOffset, byte[] w, int[] idx, float[] scale, int rows, int cols,
                                float[] x, int xOffset) {
        quantize(x, xOffset, cols);
        var q = quantized;
        for (var i = 0; i < rows; i++) {
            out[outOffset + i] = 0;
        }
        var k = 0;
        var wp = 0;
        for (var i = 0; i < rows; i += 8) {
            var blocks = idx[k++];
            var y = outOffset + i;
            for (var j = 0; j < blocks; j++) {
                var pos = idx[k++];
                int x0 = q[pos];
                int x1 = q[pos + 1];
                int x2 = q[pos + 2];
                int x3 = q[pos + 3];
                for (var r = 0; r < 8; r++) {
                    var b = wp + 4 * r;
                    out[y + r] += (float) (w[b] * x0 + w[b + 1] * x1 + w[b + 2] * x2 + w[b + 3] * x3);
                }
                wp += 32;
            }
        }
        for (var i = 0; i < rows; i++) {
            out[outOffset + i] *= scale[i];
        }
    }

    /**
     * Applies an activation; the output may be the input ({@code compute_activation_c}).
     *
     * @param output receives {@code n} values
     * @param outputOffset the index of the first output
     * @param input the {@code n} inputs
     * @param inputOffset the index of the first input
     * @param n the number of values
     * @param activation the {@code ACTIVATION_*} constant
     */
    void activation(float[] output, int outputOffset, float[] input, int inputOffset, int n, int activation) {
        switch (activation) {
            case ACTIVATION_SIGMOID -> {
                for (var i = 0; i < n; i++) {
                    output[outputOffset + i] = sigmoidApprox(input[inputOffset + i]);
                }
            }
            case ACTIVATION_TANH -> {
                for (var i = 0; i < n; i++) {
                    output[outputOffset + i] = tanhApprox(input[inputOffset + i]);
                }
            }
            case ACTIVATION_SWISH -> {
                for (var i = 0; i < n; i++) {
                    swish[i] = sigmoidApprox(input[inputOffset + i]);
                }
                for (var i = 0; i < n; i++) {
                    output[outputOffset + i] = input[inputOffset + i] * swish[i];
                }
            }
            case ACTIVATION_RELU -> {
                for (var i = 0; i < n; i++) {
                    var x = input[inputOffset + i];
                    output[outputOffset + i] = x < 0 ? 0 : x;
                }
            }
            default -> {
                if (output != input || outputOffset != inputOffset) {
                    System.arraycopy(input, inputOffset, output, outputOffset, n);
                }
            }
        }
    }

    /**
     * Approximates the hyperbolic tangent with a rational function clamped to {@code [-1, 1]}
     * ({@code tanh_approx}).
     *
     * @param x the argument
     * @return the approximation
     */
    static float tanhApprox(float x) {
        var x2 = x * x;
        var num = (0.60863042f * x2 + 96.39235687f) * x2 + 952.52801514f;
        var den = (11.88600922f * x2 + 413.36801147f) * x2 + 952.72399902f;
        num = num * x / den;
        var clamped = 1.f < num ? 1.f : num;
        return -1.f > clamped ? -1.f : clamped;
    }

    /**
     * Approximates the logistic function as {@code 0.5 + 0.5 * tanh(x / 2)} ({@code sigmoid_approx}).
     *
     * @param x the argument
     * @return the approximation
     */
    static float sigmoidApprox(float x) {
        return .5f + .5f * tanhApprox(.5f * x);
    }
}
