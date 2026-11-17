/* Ports webrtc-audio-processing 2.1 (common_audio/resampler/sinc_resampler.cc, push_sinc_resampler.cc). Copyright
   (c) 2013, The WebRTC project authors; BSD 3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.audio.voice.internal;

/**
 * Resamples a stream of fixed-size frames by a constant ratio with a windowed-sinc kernel, one
 * frame in and one frame out per call.
 *
 * <p>The first output frame is preceded by half a kernel of silence, so the output lags the input
 * by 16 input samples plus the kernel's own group delay. Instances are not thread-safe.
 *
 * @implNote This implementation ports {@code SincResampler} with its scalar convolution
 * ({@code Convolve_C}) and {@code PushSincResampler} of WebRTC's {@code common_audio/resampler}.
 */
public final class PushSincResampler {
    /**
     * The number of taps of the kernel.
     */
    private static final int KERNEL_SIZE = 32;

    /**
     * The number of sub-sample offsets the kernel is tabulated for.
     */
    private static final int KERNEL_OFFSET_COUNT = 32;

    /**
     * The kernels of every sub-sample offset, the last one repeating offset 1.
     */
    private final float[] kernel = new float[KERNEL_SIZE * (KERNEL_OFFSET_COUNT + 1)];

    /**
     * The input samples, {@code requestFrames + KERNEL_SIZE} of them.
     */
    private final float[] inputBuffer;

    /**
     * The input-to-output sample rate ratio.
     */
    private final double ioRatio;

    /**
     * The number of input samples of a frame.
     */
    private final int requestFrames;

    /**
     * The number of output samples of a frame.
     */
    private final int destinationFrames;

    /**
     * The index of region 0, where the next input frame is written.
     */
    private int r0;

    /**
     * The index of region 3, the samples copied to the start when a block is consumed.
     */
    private int r3;

    /**
     * The number of input samples consumed per block.
     */
    private int blockSize;

    /**
     * The fractional position of the next output sample in the input block.
     */
    private double virtualSourceIdx;

    /**
     * Whether the first input frame has been read.
     */
    private boolean bufferPrimed;

    /**
     * Whether the next read is the first, which reads silence.
     */
    private boolean firstPass = true;

    /**
     * The frame being resampled.
     */
    private float[] source;

    /**
     * The index of the first sample of {@link #source}.
     */
    private int sourceOffset;

    /**
     * Creates a resampler from frames of {@code sourceFrames} samples to frames of
     * {@code destinationFrames} samples.
     *
     * @param sourceFrames the number of input samples per call
     * @param destinationFrames the number of output samples per call
     */
    public PushSincResampler(int sourceFrames, int destinationFrames) {
        this.ioRatio = sourceFrames * 1.0 / destinationFrames;
        this.requestFrames = sourceFrames;
        this.destinationFrames = destinationFrames;
        this.inputBuffer = new float[requestFrames + KERNEL_SIZE];
        updateRegions(false);
        initializeKernel();
    }

    /**
     * Resamples one frame.
     *
     * @param source the input samples
     * @param sourceOffset the index of the first of the {@code sourceFrames} input samples
     * @param destination receives the output samples
     * @param destinationOffset the index of the first of the {@code destinationFrames} output
     * samples
     */
    public void resample(float[] source, int sourceOffset, float[] destination, int destinationOffset) {
        this.source = source;
        this.sourceOffset = sourceOffset;
        if (firstPass) {
            run(chunkSize(), destination, destinationOffset);
        }
        run(destinationFrames, destination, destinationOffset);
        this.source = null;
    }

    /**
     * Reads the next input frame into the buffer, silence on the first read.
     *
     * @param destination the index in {@link #inputBuffer} receiving the frame
     */
    private void readInput(int destination) {
        if (firstPass) {
            java.util.Arrays.fill(inputBuffer, destination, destination + requestFrames, 0.f);
            firstPass = false;
            return;
        }
        System.arraycopy(source, sourceOffset, inputBuffer, destination, requestFrames);
    }

    /**
     * {@return the number of output samples one input block yields}
     */
    private int chunkSize() {
        return (int) (blockSize / ioRatio);
    }

    /**
     * Sets the regions of the input buffer for the first or a later load.
     *
     * @param secondLoad whether the first load has happened
     */
    private void updateRegions(boolean secondLoad) {
        r0 = secondLoad ? KERNEL_SIZE : KERNEL_SIZE / 2;
        r3 = r0 + requestFrames - KERNEL_SIZE;
        var r4 = r0 + requestFrames - KERNEL_SIZE / 2;
        blockSize = r4 - KERNEL_SIZE / 2;
    }

    /**
     * Fills the kernel table of a Blackman window over a scaled sinc.
     */
    private void initializeKernel() {
        var alpha = 0.16;
        var a0 = 0.5 * (1.0 - alpha);
        var a1 = 0.5;
        var a2 = 0.5 * alpha;
        var sincScaleFactor = (ioRatio > 1.0 ? 1.0 / ioRatio : 1.0) * 0.9;
        for (var offsetIdx = 0; offsetIdx <= KERNEL_OFFSET_COUNT; ++offsetIdx) {
            var subsampleOffset = (float) offsetIdx / KERNEL_OFFSET_COUNT;
            for (var i = 0; i < KERNEL_SIZE; ++i) {
                var idx = i + offsetIdx * KERNEL_SIZE;
                var preSinc = (float) (Math.PI * ((float) (i - KERNEL_SIZE / 2) - subsampleOffset));
                var x = (i - subsampleOffset) / KERNEL_SIZE;
                var window = (float) (a0 - a1 * StrictMath.cos(2.0 * Math.PI * x) + a2 * StrictMath.cos(4.0 * Math.PI * x));
                kernel[idx] = (float) (window * (preSinc == 0 ? sincScaleFactor
                        : StrictMath.sin(sincScaleFactor * preSinc) / preSinc));
            }
        }
    }

    /**
     * Produces output samples, reading input frames as the kernel needs them.
     *
     * @param frames the number of output samples
     * @param destination receives the samples
     * @param destinationOffset the index of the first output sample
     */
    private void run(int frames, float[] destination, int destinationOffset) {
        var remainingFrames = frames;
        if (!bufferPrimed && remainingFrames > 0) {
            readInput(r0);
            bufferPrimed = true;
        }
        var ratio = ioRatio;
        var input = inputBuffer;
        var kernel = this.kernel;
        var out = destinationOffset;
        while (remainingFrames > 0) {
            for (var i = (int) Math.ceil((blockSize - virtualSourceIdx) / ratio); i > 0; --i) {
                var sourceIdx = (int) virtualSourceIdx;
                var subsampleRemainder = virtualSourceIdx - sourceIdx;
                var virtualOffsetIdx = subsampleRemainder * KERNEL_OFFSET_COUNT;
                var offsetIdx = (int) virtualOffsetIdx;
                var k1 = offsetIdx * KERNEL_SIZE;
                var k2 = k1 + KERNEL_SIZE;
                var factor = virtualOffsetIdx - offsetIdx;
                var sum1 = 0.f;
                var sum2 = 0.f;
                for (var n = 0; n < KERNEL_SIZE; n++) {
                    var v = input[sourceIdx + n];
                    sum1 += v * kernel[k1 + n];
                    sum2 += v * kernel[k2 + n];
                }
                destination[out++] = (float) ((1.0 - factor) * sum1 + factor * sum2);
                virtualSourceIdx += ratio;
                if (--remainingFrames == 0) {
                    return;
                }
            }
            virtualSourceIdx -= blockSize;
            System.arraycopy(input, r3, input, 0, KERNEL_SIZE);
            if (r0 == KERNEL_SIZE / 2) {
                updateRegions(true);
            }
            readInput(r0);
        }
    }
}
