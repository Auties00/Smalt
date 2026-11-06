/* Ports libopus 1.5.2 (dnn/nnet.h, dnn/parse_lpcnet_weights.c). Copyright Mozilla and Amazon;
   BSD 3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.audio.opus.internal.dnn;

/**
 * A generic, optionally sparse affine transformation ({@code LinearLayer}).
 *
 * <p>The weights are stored column by column: the weight from input {@code j} to output {@code i} of a dense
 * float layer is {@code float_weights[j * nb_outputs + i]}. An int8 layer stores blocks of 8 outputs by 4
 * inputs, row-major within the block, with one {@link #scale} per output. A sparse layer lists, for each group
 * of 8 outputs, the number of 4-input blocks and their first input in {@link #weights_idx}.
 *
 * <p>Instances are immutable and thread-safe.
 */
final class LinearLayer {
    /**
     * The number of weights of one sparse block ({@code SPARSE_BLOCK_SIZE}).
     */
    private static final int SPARSE_BLOCK_SIZE = 32;

    /**
     * The bias of each output, or {@code null}.
     */
    final float[] bias;

    /**
     * The int8 weights, or {@code null}.
     */
    final byte[] weights;

    /**
     * The float weights, or {@code null}; used in preference to {@link #weights}.
     */
    final float[] float_weights;

    /**
     * The sparse block index, or {@code null} for a dense layer.
     */
    final int[] weights_idx;

    /**
     * The diagonal of a GRU recurrent layer, {@code 3 * nb_inputs} values, or {@code null}.
     */
    final float[] diag;

    /**
     * The scale of each output of an int8 layer, or {@code null}.
     */
    final float[] scale;

    /**
     * The number of inputs.
     */
    final int nb_inputs;

    /**
     * The number of outputs.
     */
    final int nb_outputs;

    /**
     * Constructs a layer from its arrays.
     */
    private LinearLayer(float[] bias, byte[] weights, float[] floatWeights, int[] weightsIdx, float[] diag,
                        float[] scale, int nbInputs, int nbOutputs) {
        this.bias = bias;
        this.weights = weights;
        this.float_weights = floatWeights;
        this.weights_idx = weightsIdx;
        this.diag = diag;
        this.scale = scale;
        this.nb_inputs = nbInputs;
        this.nb_outputs = nbOutputs;
    }

    /**
     * Reads a layer from a blob, checking every named array as {@code linear_init} does; the {@code subias}
     * array, which only the unsigned dot-product kernels use, is checked but not kept.
     *
     * @param blob the weight blob
     * @param bias the bias array name, or {@code null}
     * @param subias the unsigned-input bias array name, or {@code null}
     * @param weights the int8 weight array name, or {@code null}
     * @param floatWeights the optional float weight array name, or {@code null}
     * @param weightsIdx the sparse index array name, or {@code null}
     * @param diag the diagonal array name, or {@code null}
     * @param scale the scale array name, required with {@code weights}
     * @param nbInputs the number of inputs
     * @param nbOutputs the number of outputs
     * @return the layer
     * @throws IllegalStateException if a named array is missing or has the wrong size
     */
    static LinearLayer of(WeightBlob blob, String bias, String subias, String weights, String floatWeights,
                          String weightsIdx, String diag, String scale, int nbInputs, int nbOutputs) {
        var b = bias == null ? null : blob.floats(bias, nbOutputs);
        if (subias != null) {
            blob.floats(subias, nbOutputs);
        }
        byte[] w = null;
        float[] fw = null;
        int[] idx = null;
        if (weightsIdx != null) {
            idx = blob.ints(weightsIdx);
            var totalBlocks = checkIndex(idx, nbInputs, nbOutputs, weightsIdx);
            if (weights != null) {
                w = blob.bytes(weights, SPARSE_BLOCK_SIZE * totalBlocks);
            }
            if (floatWeights != null) {
                fw = blob.optionalFloats(floatWeights, SPARSE_BLOCK_SIZE * totalBlocks);
            }
        } else {
            if (weights != null) {
                w = blob.bytes(weights, nbInputs * nbOutputs);
            }
            if (floatWeights != null) {
                fw = blob.optionalFloats(floatWeights, nbInputs * nbOutputs);
            }
        }
        var d = diag == null ? null : blob.floats(diag, nbOutputs);
        var s = weights == null ? null : blob.floats(scale, nbOutputs);
        return new LinearLayer(b, w, fw, idx, d, s, nbInputs, nbOutputs);
    }

    /**
     * Checks a sparse block index and counts its blocks ({@code find_idx_check}).
     *
     * @return the total number of 4-input blocks
     * @throws IllegalStateException if the index is missing or does not cover the outputs in groups of 8
     */
    private static int checkIndex(int[] idx, int nbIn, int nbOut, String name) {
        if (idx == null) {
            throw new IllegalStateException("missing DNN weight array " + name);
        }
        var total = 0;
        var pos = 0;
        var remain = idx.length;
        while (remain > 0) {
            var blocks = idx[pos++];
            if (blocks < 0 || remain < blocks + 1) {
                throw new IllegalStateException("malformed DNN sparse index " + name);
            }
            for (var i = 0; i < blocks; i++) {
                var p = idx[pos++];
                if (p + 3 >= nbIn || (p & 3) != 0) {
                    throw new IllegalStateException("malformed DNN sparse index " + name);
                }
            }
            nbOut -= 8;
            remain -= blocks + 1;
            total += blocks;
        }
        if (nbOut != 0) {
            throw new IllegalStateException("malformed DNN sparse index " + name);
        }
        return total;
    }
}
