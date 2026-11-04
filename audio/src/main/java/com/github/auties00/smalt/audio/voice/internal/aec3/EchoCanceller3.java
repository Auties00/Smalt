/* Ports webrtc-audio-processing 2.1 (modules/audio_processing/aec3/echo_canceller3.cc, block_processor.cc,
   frame_blocker.cc, block_framer.cc, multi_channel_content_detector.cc, config_selector.cc). Copyright (c) 2011, The
   WebRTC project authors; BSD 3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.audio.voice.internal.aec3;

import static com.github.auties00.smalt.audio.voice.internal.aec3.Aec3.BLOCK_SIZE;
import static com.github.auties00.smalt.audio.voice.internal.aec3.Aec3.FRAME_SIZE;
import static com.github.auties00.smalt.audio.voice.internal.aec3.Aec3.SUB_FRAME_LENGTH;

/**
 * Cancels the acoustic echo of a render signal in a capture signal, WebRTC's AEC3 with its
 * default configuration, for one to three 16 kHz bands and any number of render and capture
 * channels.
 *
 * <p>Render frames of 160 samples per band and channel, in the 16-bit range, are queued by
 * {@link #analyzeRender(float[][][])}; {@link #processCapture(float[][][], boolean)} drains the
 * queue into the render buffer and processes the capture frame in place, in 64-sample blocks
 * (re-framed with a delay of 64 samples). {@link #analyzeCapture(float[][], int, int)} flags a
 * saturated capture frame before the frame is filtered further.
 *
 * <p>Several render channels are modeled separately only once they carry different signals for
 * two seconds; until then they are reduced to the first one, or to their average while they
 * differ, and a change of the decision restarts the canceller with the configuration of the new
 * render layout. Instances are not thread-safe.
 *
 * @implNote This implementation ports {@code EchoCanceller3} of WebRTC's
 * {@code modules/audio_processing/aec3/echo_canceller3.cc} with {@code BlockProcessorImpl}
 * ({@code block_processor.cc}), {@code FrameBlocker}, {@code BlockFramer},
 * {@code MultiChannelContentDetector} and {@code ConfigSelector}, with no field trials. The API
 * call metrics, the block processor metrics and the echo return loss, its enhancement and the
 * delay that only those metrics report have no effect and are omitted.
 */
public final class EchoCanceller3 {
    /**
     * The number of frames of render content that must differ before it counts as multichannel.
     */
    private static final int STEREO_DETECTION_HYSTERESIS_FRAMES = (int) (2.0f * 100);

    /**
     * The number of frames without differing content after which multichannel content is
     * forgotten.
     */
    private static final int STEREO_DETECTION_TIMEOUT_FRAMES = 300 * 100;

    /**
     * The number of bands.
     */
    private final int numBands;

    /**
     * The number of render channels given to {@link #analyzeRender(float[][][])}.
     */
    private final int numRenderInputChannels;

    /**
     * The number of capture channels.
     */
    private final int numCaptureChannels;

    /**
     * The matched filter kernel.
     */
    private final MatchedFilterKernel matchedFilterKernel;

    /**
     * The adaptive filter kernel.
     */
    private final AdaptiveFilterKernel adaptiveFilterKernel;

    /**
     * Whether tiny values are flushed.
     */
    private final boolean flushTiny;

    /**
     * The queued render frames, each band by band and channel by channel, 160 samples each.
     */
    private final float[] renderQueue;

    /**
     * The samples of one queued render frame.
     */
    private final int renderFrameSize;

    /**
     * The slot of the oldest queued render frame.
     */
    private int renderQueueRead;

    /**
     * The number of queued render frames.
     */
    private int renderQueueCount;

    /**
     * The render frame being buffered.
     */
    private final float[] renderFrame;

    /**
     * The number of render channels the canceller models.
     */
    private int numRenderChannelsToAec;

    /**
     * The render samples not yet forming a block, per band and modeled channel.
     */
    private float[] renderBlockerBuffer;

    /**
     * The number of samples in each render blocker buffer.
     */
    private int renderBlockerSize;

    /**
     * The capture samples not yet forming a block, per band and channel.
     */
    private final float[] captureBlockerBuffer;

    /**
     * The number of samples in each capture blocker buffer.
     */
    private int captureBlockerSize;

    /**
     * The processed samples not yet returned in a sub-frame, per band and channel.
     */
    private final float[] outputFramerBuffer;

    /**
     * The number of samples in each output framer buffer, initially a block of zeros.
     */
    private int outputFramerSize = BLOCK_SIZE;

    /**
     * The scratch render block.
     */
    private float[] renderBlock;

    /**
     * The scratch capture block.
     */
    private final float[] captureBlock;

    /**
     * Whether the last analyzed capture frame was saturated.
     */
    private boolean saturatedMicrophoneSignal;

    /**
     * Whether the render channels currently differ persistently.
     */
    private boolean persistentMultichannelContentDetected;

    /**
     * Whether the render channels differ in the current frames, without being persistent yet.
     */
    private boolean temporaryMultichannelContentDetected;

    /**
     * The number of consecutive frames with differing render channels.
     */
    private long consecutiveFramesWithStereo;

    /**
     * The number of frames since the render channels last differed.
     */
    private long framesSinceStereoDetectedLast;

    /**
     * The block processor.
     */
    private BlockProcessor blockProcessor;

    /**
     * Creates an echo canceller.
     *
     * <p>Flushing zeroes the tiny values the delay estimator's decimation filters decay into when
     * the render or the capture signal falls silent (see {@link Decimator}); the reference keeps
     * computing with them, as subnormal numbers, unless its platform flushes subnormals to zero.
     *
     * @param numBands the number of bands, 1 to 3
     * @param numRenderChannels the number of render channels
     * @param numCaptureChannels the number of capture channels
     * @param vectorize whether the Vector API kernels are used; the delay estimator's vector
     * kernel sums in a different order, so its output is not bit-identical to the scalar one
     * @param flushTiny whether tiny decimation filter states and samples are zeroed;
     * {@code false} (with scalar kernels) reproduces the reference bit for bit
     */
    public EchoCanceller3(int numBands, int numRenderChannels, int numCaptureChannels, boolean vectorize,
                          boolean flushTiny) {
        this.numBands = numBands;
        this.numRenderInputChannels = numRenderChannels;
        this.numCaptureChannels = numCaptureChannels;
        this.matchedFilterKernel = Aec3Kernels.matchedFilter(vectorize);
        this.adaptiveFilterKernel = Aec3Kernels.adaptiveFilter(vectorize);
        this.flushTiny = flushTiny;
        this.renderFrameSize = numBands * numRenderChannels * FRAME_SIZE;
        this.renderQueue = new float[Aec3.RENDER_TRANSFER_QUEUE_SIZE_FRAMES * renderFrameSize];
        this.renderFrame = new float[renderFrameSize];
        this.captureBlockerBuffer = new float[numBands * numCaptureChannels * BLOCK_SIZE];
        this.outputFramerBuffer = new float[numBands * numCaptureChannels * BLOCK_SIZE];
        this.captureBlock = new float[numBands * numCaptureChannels * BLOCK_SIZE];
        initialize();
    }

    /**
     * Creates the render blocker and the block processor for the current render layout
     * ({@code EchoCanceller3::Initialize}).
     */
    private void initialize() {
        numRenderChannelsToAec = persistentMultichannelContentDetected ? numRenderInputChannels : 1;
        var config = persistentMultichannelContentDetected ? Aec3Config.MULTICHANNEL : Aec3Config.DEFAULT;
        renderBlockerBuffer = new float[numBands * numRenderChannelsToAec * BLOCK_SIZE];
        renderBlockerSize = 0;
        renderBlock = new float[numBands * numRenderChannelsToAec * BLOCK_SIZE];
        blockProcessor = new BlockProcessor(config, numBands, numRenderChannelsToAec, numCaptureChannels,
                matchedFilterKernel, adaptiveFilterKernel, flushTiny);
    }

    /**
     * Queues a render frame, or drops it when 100 frames are already queued.
     *
     * @param frame the bands of every render channel, {@code [channel][band]}, 160 samples each in
     * the 16-bit range
     */
    public void analyzeRender(float[][][] frame) {
        if (renderQueueCount == Aec3.RENDER_TRANSFER_QUEUE_SIZE_FRAMES) {
            return;
        }
        var slot = (renderQueueRead + renderQueueCount) % Aec3.RENDER_TRANSFER_QUEUE_SIZE_FRAMES;
        var base = slot * renderFrameSize;
        for (var band = 0; band < numBands; band++) {
            for (var ch = 0; ch < numRenderInputChannels; ch++) {
                System.arraycopy(frame[ch][band], 0, renderQueue, base + (band * numRenderInputChannels + ch) * FRAME_SIZE,
                        FRAME_SIZE);
            }
        }
        renderQueueCount++;
    }

    /**
     * Flags whether a capture frame is saturated, on the full band of every channel.
     *
     * @param frame the full-band samples of every channel, in the 16-bit range
     * @param numChannels the number of channels to examine
     * @param length the number of samples per channel
     */
    public void analyzeCapture(float[][] frame, int numChannels, int length) {
        var saturated = false;
        for (var ch = 0; ch < numChannels && !saturated; ch++) {
            var channel = frame[ch];
            for (var k = 0; k < length; ++k) {
                var y = channel[k];
                if (y >= 32700.0f || y <= -32700.0f) {
                    saturated = true;
                    break;
                }
            }
        }
        saturatedMicrophoneSignal = saturated;
    }

    /**
     * Reports the externally measured audio buffer delay, used as the initial alignment of the
     * render buffer.
     *
     * @param delayMs the delay in milliseconds
     */
    public void setAudioBufferDelay(int delayMs) {
        blockProcessor.setAudioBufferDelay(delayMs);
    }

    /**
     * Removes the echo from a capture frame in place.
     *
     * @param frame the bands of every capture channel, {@code [channel][band]}, 160 samples each in
     * the 16-bit range; receive the echo-free samples, delayed by 64 samples
     * @param levelChange whether the capture level changed
     */
    public void processCapture(float[][][] frame, boolean levelChange) {
        emptyRenderQueue();
        var echoPathGainChange = levelChange || temporaryMultichannelContentDetected;
        processCaptureSubFrame(frame, 0, echoPathGainChange);
        processCaptureSubFrame(frame, SUB_FRAME_LENGTH, echoPathGainChange);
        if (captureBlockerSize == BLOCK_SIZE) {
            System.arraycopy(captureBlockerBuffer, 0, captureBlock, 0, captureBlock.length);
            captureBlockerSize = 0;
            blockProcessor.processCapture(echoPathGainChange, saturatedMicrophoneSignal, captureBlock);
            System.arraycopy(captureBlock, 0, outputFramerBuffer, 0, captureBlock.length);
            outputFramerSize = BLOCK_SIZE;
        }
    }

    /**
     * Blocks one capture sub-frame, processes the block it completes, and frames the output back
     * into the sub-frame.
     *
     * @param frame the capture frame
     * @param offset the index of the first of the 80 samples of the sub-frame in every band
     * @param echoPathGainChange whether the echo path gain changed
     */
    private void processCaptureSubFrame(float[][][] frame, int offset, boolean echoPathGainChange) {
        var samplesToBlock = BLOCK_SIZE - captureBlockerSize;
        for (var band = 0; band < numBands; band++) {
            for (var ch = 0; ch < numCaptureChannels; ch++) {
                var b = (band * numCaptureChannels + ch) * BLOCK_SIZE;
                var subFrame = frame[ch][band];
                System.arraycopy(captureBlockerBuffer, b, captureBlock, b, captureBlockerSize);
                System.arraycopy(subFrame, offset, captureBlock, b + BLOCK_SIZE - samplesToBlock, samplesToBlock);
                System.arraycopy(subFrame, offset + samplesToBlock, captureBlockerBuffer, b,
                        SUB_FRAME_LENGTH - samplesToBlock);
            }
        }
        captureBlockerSize = SUB_FRAME_LENGTH - samplesToBlock;
        blockProcessor.processCapture(echoPathGainChange, saturatedMicrophoneSignal, captureBlock);
        var samplesToFrame = SUB_FRAME_LENGTH - outputFramerSize;
        for (var band = 0; band < numBands; band++) {
            for (var ch = 0; ch < numCaptureChannels; ch++) {
                var b = (band * numCaptureChannels + ch) * BLOCK_SIZE;
                var subFrame = frame[ch][band];
                System.arraycopy(outputFramerBuffer, b, subFrame, offset, outputFramerSize);
                System.arraycopy(captureBlock, b, subFrame, offset + outputFramerSize, samplesToFrame);
                System.arraycopy(captureBlock, b + samplesToFrame, outputFramerBuffer, b, BLOCK_SIZE - samplesToFrame);
            }
        }
        outputFramerSize = BLOCK_SIZE - samplesToFrame;
    }

    /**
     * Moves the queued render frames into the render buffer, block by block.
     */
    private void emptyRenderQueue() {
        while (renderQueueCount > 0) {
            System.arraycopy(renderQueue, renderQueueRead * renderFrameSize, renderFrame, 0, renderFrameSize);
            renderQueueRead = (renderQueueRead + 1) % Aec3.RENDER_TRANSFER_QUEUE_SIZE_FRAMES;
            renderQueueCount--;
            if (updateDetection(renderFrame)) {
                initialize();
            }
            bufferRenderSubFrame(renderFrame, 0);
            bufferRenderSubFrame(renderFrame, SUB_FRAME_LENGTH);
            if (renderBlockerSize == BLOCK_SIZE) {
                System.arraycopy(renderBlockerBuffer, 0, renderBlock, 0, renderBlock.length);
                renderBlockerSize = 0;
                blockProcessor.bufferRender(renderBlock);
            }
        }
    }

    /**
     * Updates the multichannel content detection with a render frame
     * ({@code MultiChannelContentDetector::UpdateDetection}).
     *
     * @param frame the render frame
     * @return whether the persistent detection changed
     */
    private boolean updateDetection(float[] frame) {
        var previous = persistentMultichannelContentDetected;
        var stereoDetectedInFrame = hasStereoContent(frame);
        consecutiveFramesWithStereo = stereoDetectedInFrame ? consecutiveFramesWithStereo + 1 : 0;
        framesSinceStereoDetectedLast = stereoDetectedInFrame ? 0 : framesSinceStereoDetectedLast + 1;
        if (consecutiveFramesWithStereo > STEREO_DETECTION_HYSTERESIS_FRAMES) {
            persistentMultichannelContentDetected = true;
        }
        if (framesSinceStereoDetectedLast >= STEREO_DETECTION_TIMEOUT_FRAMES) {
            persistentMultichannelContentDetected = false;
        }
        temporaryMultichannelContentDetected = !persistentMultichannelContentDetected && stereoDetectedInFrame;
        return previous != persistentMultichannelContentDetected;
    }

    /**
     * {@return whether the first two render channels of a frame differ anywhere}
     *
     * @param frame the render frame
     */
    private boolean hasStereoContent(float[] frame) {
        if (numRenderInputChannels < 2) {
            return false;
        }
        for (var band = 0; band < numBands; band++) {
            var first = band * numRenderInputChannels * FRAME_SIZE;
            var second = first + FRAME_SIZE;
            for (var k = 0; k < FRAME_SIZE; k++) {
                if (Math.abs(frame[first + k] - frame[second + k]) > 0.0f) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Blocks one render sub-frame, reduced to the modeled channels, and buffers the block it
     * completes.
     *
     * @param frame the render frame
     * @param offset the index of the first of the 80 samples of the sub-frame in every band
     */
    private void bufferRenderSubFrame(float[] frame, int offset) {
        var channels = numRenderChannelsToAec;
        if (numRenderInputChannels > channels && temporaryMultichannelContentDetected) {
            var oneByNumChannels = 1.0f / numRenderInputChannels;
            for (var band = 0; band < numBands; band++) {
                var first = band * numRenderInputChannels * FRAME_SIZE + offset;
                for (var ch = 1; ch < numRenderInputChannels; ch++) {
                    var other = (band * numRenderInputChannels + ch) * FRAME_SIZE + offset;
                    for (var k = 0; k < SUB_FRAME_LENGTH; k++) {
                        frame[first + k] += frame[other + k];
                    }
                }
                for (var k = 0; k < SUB_FRAME_LENGTH; k++) {
                    frame[first + k] *= oneByNumChannels;
                }
            }
        }
        var samplesToBlock = BLOCK_SIZE - renderBlockerSize;
        for (var band = 0; band < numBands; band++) {
            for (var ch = 0; ch < channels; ch++) {
                var b = (band * channels + ch) * BLOCK_SIZE;
                var source = (band * numRenderInputChannels + ch) * FRAME_SIZE + offset;
                System.arraycopy(renderBlockerBuffer, b, renderBlock, b, renderBlockerSize);
                System.arraycopy(frame, source, renderBlock, b + BLOCK_SIZE - samplesToBlock, samplesToBlock);
                System.arraycopy(frame, source + samplesToBlock, renderBlockerBuffer, b, SUB_FRAME_LENGTH - samplesToBlock);
            }
        }
        renderBlockerSize = SUB_FRAME_LENGTH - samplesToBlock;
        blockProcessor.bufferRender(renderBlock);
    }

    /**
     * Buffers render blocks, estimates the echo path delay and removes the echo from capture
     * blocks ({@code BlockProcessorImpl}).
     */
    private static final class BlockProcessor {
        /**
         * The render delay buffer.
         */
        private final RenderDelayBuffer renderDelayBuffer;

        /**
         * The render delay controller.
         */
        private final RenderDelayController delayController;

        /**
         * The echo remover.
         */
        private final EchoRemover echoRemover;

        /**
         * Whether capture processing has started after the first render block.
         */
        private boolean captureProperlyStarted;

        /**
         * Whether a render block has been buffered.
         */
        private boolean renderProperlyStarted;

        /**
         * The buffering event of the last render block.
         */
        private int renderEvent = RenderDelayBuffer.EVENT_NONE;

        /**
         * The current delay estimate in blocks, or {@code -1}.
         */
        private int estimatedDelay = -1;

        BlockProcessor(Aec3Config config, int numBands, int numRenderChannels, int numCaptureChannels,
                       MatchedFilterKernel matchedFilterKernel, AdaptiveFilterKernel adaptiveFilterKernel,
                       boolean flushTiny) {
            this.renderDelayBuffer = new RenderDelayBuffer(numBands, numRenderChannels, flushTiny);
            this.delayController = new RenderDelayController(matchedFilterKernel, numCaptureChannels, flushTiny);
            this.echoRemover = new EchoRemover(config, numBands, numRenderChannels, numCaptureChannels,
                    adaptiveFilterKernel);
        }

        void setAudioBufferDelay(int delayMs) {
            renderDelayBuffer.setAudioBufferDelay(delayMs);
        }

        /**
         * Inserts a render block into the render buffer ({@code BufferRender}).
         *
         * @param block the render block
         */
        void bufferRender(float[] block) {
            renderEvent = renderDelayBuffer.insert(block);
            renderProperlyStarted = true;
        }

        /**
         * Processes a capture block in place ({@code ProcessCapture}).
         *
         * @param echoPathGainChange whether the echo path gain changed
         * @param captureSignalSaturation whether the capture signal is saturated
         * @param block the capture block
         */
        void processCapture(boolean echoPathGainChange, boolean captureSignalSaturation, float[] block) {
            if (renderProperlyStarted) {
                if (!captureProperlyStarted) {
                    captureProperlyStarted = true;
                    renderDelayBuffer.reset();
                    delayController.reset(true);
                }
            } else {
                return;
            }
            var delayChange = false;
            if (renderEvent == RenderDelayBuffer.EVENT_RENDER_OVERRUN) {
                delayChange = true;
                delayController.reset(true);
            }
            renderEvent = RenderDelayBuffer.EVENT_NONE;
            var bufferEvent = renderDelayBuffer.prepareCaptureProcessing();
            if (bufferEvent == RenderDelayBuffer.EVENT_RENDER_UNDERRUN) {
                delayController.reset(false);
            }
            estimatedDelay = delayController.getDelay(renderDelayBuffer.lowRate, renderDelayBuffer.lowRateRead, block);
            if (estimatedDelay >= 0) {
                if (renderDelayBuffer.alignFromDelay(estimatedDelay)) {
                    delayChange = true;
                }
            }
            var clockDrift = delayController.hasClockdrift();
            echoRemover.processCapture(echoPathGainChange, delayChange, clockDrift, captureSignalSaturation,
                    estimatedDelay, renderDelayBuffer.buffer, block);
        }
    }
}
