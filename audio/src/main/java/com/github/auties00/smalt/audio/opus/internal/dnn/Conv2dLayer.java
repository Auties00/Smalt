/* Ports libopus 1.5.2 (dnn/nnet.h, dnn/parse_lpcnet_weights.c). Copyright Mozilla and Amazon;
   BSD 3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.audio.opus.internal.dnn;

/**
 * A two-dimensional convolution over time and frequency that produces one frame per call
 * ({@code Conv2dLayer}).
 *
 * <p>The kernel is indexed {@code [out_channel][in_channel][ktime][kheight]}. Instances are immutable and
 * thread-safe.
 */
final class Conv2dLayer {
    /**
     * The bias of each output channel, or {@code null}.
     */
    final float[] bias;

    /**
     * The kernel weights.
     */
    final float[] float_weights;

    /**
     * The number of input channels.
     */
    final int in_channels;

    /**
     * The number of output channels.
     */
    final int out_channels;

    /**
     * The kernel length along time, in frames.
     */
    final int ktime;

    /**
     * The kernel length along frequency, in bins.
     */
    final int kheight;

    /**
     * Constructs a layer from its arrays.
     */
    private Conv2dLayer(float[] bias, float[] floatWeights, int inChannels, int outChannels, int ktime, int kheight) {
        this.bias = bias;
        this.float_weights = floatWeights;
        this.in_channels = inChannels;
        this.out_channels = outChannels;
        this.ktime = ktime;
        this.kheight = kheight;
    }

    /**
     * Reads a layer from a blob ({@code conv2d_init}).
     *
     * @param blob the weight blob
     * @param bias the bias array name, or {@code null}
     * @param floatWeights the kernel array name
     * @param inChannels the number of input channels
     * @param outChannels the number of output channels
     * @param ktime the kernel length along time
     * @param kheight the kernel length along frequency
     * @return the layer
     * @throws IllegalStateException if an array is missing or has the wrong size
     */
    static Conv2dLayer of(WeightBlob blob, String bias, String floatWeights, int inChannels, int outChannels,
                          int ktime, int kheight) {
        var b = bias == null ? null : blob.floats(bias, outChannels);
        var w = blob.optionalFloats(floatWeights, inChannels * outChannels * ktime * kheight);
        if (w == null) {
            throw new IllegalStateException("missing DNN weight array " + floatWeights);
        }
        return new Conv2dLayer(b, w, inChannels, outChannels, ktime, kheight);
    }
}
