/* Ports webrtc-audio-processing 2.1 (modules/audio_processing/aec3/echo_canceller3.cc, block_processor.cc).
   Copyright (c) 2011, The WebRTC project authors; BSD 3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.audio.voice.internal.aec3;

import static com.github.auties00.smalt.audio.voice.internal.aec3.Aec3.BLOCK_SIZE;
import static com.github.auties00.smalt.audio.voice.internal.aec3.Aec3.FRAME_SIZE;
import static com.github.auties00.smalt.audio.voice.internal.aec3.Aec3.SUB_FRAME_LENGTH;

/**
 * Cancels the acoustic echo of a 16 kHz mono render signal in a 16 kHz mono capture signal,
 * WebRTC's AEC3 with its default configuration.
 *
 * <p>Render frames of 160 samples in the 16-bit range are queued by
 * {@link #analyzeRender(float[], int)}; {@link #processCapture(float[], int, boolean)} drains the
 * queue into the render buffer and processes the capture frame in place, in 64-sample blocks
 * (re-framed with a delay of 64 samples). {@link #analyzeCapture(float[], int)} flags a
 * saturated capture frame before the frame is filtered further. Instances are not thread-safe.
 *
 * @implNote This implementation ports {@code EchoCanceller3} of WebRTC's
 * {@code modules/audio_processing/aec3/echo_canceller3.cc} with {@code BlockProcessorImpl}
 * ({@code block_processor.cc}), {@code FrameBlocker} and {@code BlockFramer}, for one render and
 * one capture channel at 16 kHz with no field trials. The multichannel content detector, the API
 * call metrics, the block processor metrics and the echo return loss, its enhancement and the
 * delay that only those metrics report have no effect in that configuration and are omitted.
 */
public final class EchoCanceller3 {
    /**
     * The queued render frames, {@value Aec3#RENDER_TRANSFER_QUEUE_SIZE_FRAMES} slots of 160
     * samples.
     */
    private final float[] renderQueue = new float[Aec3.RENDER_TRANSFER_QUEUE_SIZE_FRAMES * FRAME_SIZE];

    /**
     * The slot of the oldest queued render frame.
     */
    private int renderQueueRead;

    /**
     * The number of queued render frames.
     */
    private int renderQueueCount;

    /**
     * The render samples not yet forming a block.
     */
    private final float[] renderBlockerBuffer = new float[BLOCK_SIZE];

    /**
     * The number of samples in {@link #renderBlockerBuffer}.
     */
    private int renderBlockerSize;

    /**
     * The capture samples not yet forming a block.
     */
    private final float[] captureBlockerBuffer = new float[BLOCK_SIZE];

    /**
     * The number of samples in {@link #captureBlockerBuffer}.
     */
    private int captureBlockerSize;

    /**
     * The processed samples not yet returned in a sub-frame.
     */
    private final float[] outputFramerBuffer = new float[BLOCK_SIZE];

    /**
     * The number of samples in {@link #outputFramerBuffer}, initially a block of zeros.
     */
    private int outputFramerSize = BLOCK_SIZE;

    /**
     * The scratch render block.
     */
    private final float[] renderBlock = new float[BLOCK_SIZE];

    /**
     * The scratch capture block.
     */
    private final float[] captureBlock = new float[BLOCK_SIZE];

    /**
     * Whether the last analyzed capture frame was saturated.
     */
    private boolean saturatedMicrophoneSignal;

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

    /**
     * Creates an echo canceller.
     *
     * <p>Flushing zeroes the tiny values the delay estimator's decimation filters decay into when
     * the render or the capture signal falls silent (see {@link Decimator}); the reference keeps
     * computing with them, as subnormal numbers, unless its platform flushes subnormals to zero.
     *
     * @param vectorize whether the Vector API kernels are used; the delay estimator's vector
     * kernel sums in a different order, so its output is not bit-identical to
     * the scalar one
     * @param flushTiny whether tiny decimation filter states and samples are zeroed;
     * {@code false} (with scalar kernels) reproduces the reference bit for bit
     */
    public EchoCanceller3(boolean vectorize, boolean flushTiny) {
        this.renderDelayBuffer = new RenderDelayBuffer(flushTiny);
        this.delayController = new RenderDelayController(Aec3Kernels.matchedFilter(vectorize), flushTiny);
        this.echoRemover = new EchoRemover(Aec3Kernels.adaptiveFilter(vectorize));
    }

    /**
     * Queues a render frame, or drops it when 100 frames are already queued.
     *
     * @param frame the samples in the 16-bit range
     * @param offset the index of the first of the 160 samples
     */
    public void analyzeRender(float[] frame, int offset) {
        if (renderQueueCount == Aec3.RENDER_TRANSFER_QUEUE_SIZE_FRAMES) {
            return;
        }
        var slot = (renderQueueRead + renderQueueCount) % Aec3.RENDER_TRANSFER_QUEUE_SIZE_FRAMES;
        System.arraycopy(frame, offset, renderQueue, slot * FRAME_SIZE, FRAME_SIZE);
        renderQueueCount++;
    }

    /**
     * Flags whether a capture frame is saturated.
     *
     * @param frame the samples in the 16-bit range
     * @param offset the index of the first of the 160 samples
     */
    public void analyzeCapture(float[] frame, int offset) {
        var saturated = false;
        for (var k = 0; k < FRAME_SIZE; ++k) {
            var y = frame[offset + k];
            if (y >= 32700.0f || y <= -32700.0f) {
                saturated = true;
                break;
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
        renderDelayBuffer.setAudioBufferDelay(delayMs);
    }

    /**
     * Removes the echo from a capture frame in place.
     *
     * @param frame the samples in the 16-bit range; receives the echo-free samples, delayed
     * by 64 samples
     * @param offset the index of the first of the 160 samples
     * @param levelChange whether the capture level changed
     */
    public void processCapture(float[] frame, int offset, boolean levelChange) {
        emptyRenderQueue();
        processCaptureSubFrame(frame, offset, levelChange);
        processCaptureSubFrame(frame, offset + SUB_FRAME_LENGTH, levelChange);
        if (captureBlockerSize == BLOCK_SIZE) {
            System.arraycopy(captureBlockerBuffer, 0, captureBlock, 0, BLOCK_SIZE);
            captureBlockerSize = 0;
            processCaptureBlock(levelChange, saturatedMicrophoneSignal, captureBlock);
            System.arraycopy(captureBlock, 0, outputFramerBuffer, 0, BLOCK_SIZE);
            outputFramerSize = BLOCK_SIZE;
        }
    }

    /**
     * Blocks one capture sub-frame, processes the block it completes, and frames the output back
     * into the sub-frame.
     *
     * @param frame the capture frame
     * @param offset the index of the first of the 80 samples of the sub-frame
     * @param levelChange whether the capture level changed
     */
    private void processCaptureSubFrame(float[] frame, int offset, boolean levelChange) {
        var samplesToBlock = BLOCK_SIZE - captureBlockerSize;
        System.arraycopy(captureBlockerBuffer, 0, captureBlock, 0, captureBlockerSize);
        System.arraycopy(frame, offset, captureBlock, BLOCK_SIZE - samplesToBlock, samplesToBlock);
        System.arraycopy(frame, offset + samplesToBlock, captureBlockerBuffer, 0, SUB_FRAME_LENGTH - samplesToBlock);
        captureBlockerSize = SUB_FRAME_LENGTH - samplesToBlock;
        processCaptureBlock(levelChange, saturatedMicrophoneSignal, captureBlock);
        var samplesToFrame = SUB_FRAME_LENGTH - outputFramerSize;
        System.arraycopy(outputFramerBuffer, 0, frame, offset, outputFramerSize);
        System.arraycopy(captureBlock, 0, frame, offset + outputFramerSize, samplesToFrame);
        System.arraycopy(captureBlock, samplesToFrame, outputFramerBuffer, 0, BLOCK_SIZE - samplesToFrame);
        outputFramerSize = BLOCK_SIZE - samplesToFrame;
    }

    /**
     * Moves the queued render frames into the render buffer, block by block.
     */
    private void emptyRenderQueue() {
        while (renderQueueCount > 0) {
            var frameOffset = renderQueueRead * FRAME_SIZE;
            bufferRenderSubFrame(renderQueue, frameOffset);
            bufferRenderSubFrame(renderQueue, frameOffset + SUB_FRAME_LENGTH);
            if (renderBlockerSize == BLOCK_SIZE) {
                System.arraycopy(renderBlockerBuffer, 0, renderBlock, 0, BLOCK_SIZE);
                renderBlockerSize = 0;
                bufferRender(renderBlock);
            }
            renderQueueRead = (renderQueueRead + 1) % Aec3.RENDER_TRANSFER_QUEUE_SIZE_FRAMES;
            renderQueueCount--;
        }
    }

    /**
     * Blocks one render sub-frame and buffers the block it completes.
     *
     * @param frame the render samples
     * @param offset the index of the first of the 80 samples of the sub-frame
     */
    private void bufferRenderSubFrame(float[] frame, int offset) {
        var samplesToBlock = BLOCK_SIZE - renderBlockerSize;
        System.arraycopy(renderBlockerBuffer, 0, renderBlock, 0, renderBlockerSize);
        System.arraycopy(frame, offset, renderBlock, BLOCK_SIZE - samplesToBlock, samplesToBlock);
        System.arraycopy(frame, offset + samplesToBlock, renderBlockerBuffer, 0, SUB_FRAME_LENGTH - samplesToBlock);
        renderBlockerSize = SUB_FRAME_LENGTH - samplesToBlock;
        bufferRender(renderBlock);
    }

    /**
     * Inserts a render block into the render buffer, {@code BlockProcessorImpl::BufferRender}.
     *
     * @param block the 64 render samples
     */
    private void bufferRender(float[] block) {
        renderEvent = renderDelayBuffer.insert(block, 0);
        renderProperlyStarted = true;
    }

    /**
     * Processes a capture block in place, {@code BlockProcessorImpl::ProcessCapture}.
     *
     * @param echoPathGainChange whether the echo path gain changed
     * @param captureSignalSaturation whether the capture signal is saturated
     * @param block the 64 capture samples
     */
    private void processCaptureBlock(boolean echoPathGainChange, boolean captureSignalSaturation, float[] block) {
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
        estimatedDelay = delayController.getDelay(renderDelayBuffer.lowRate, renderDelayBuffer.lowRateRead, block, 0);
        if (estimatedDelay >= 0) {
            if (renderDelayBuffer.alignFromDelay(estimatedDelay)) {
                delayChange = true;
            }
        }
        var clockDrift = delayController.hasClockdrift();
        echoRemover.processCapture(echoPathGainChange, delayChange, clockDrift, captureSignalSaturation, estimatedDelay,
                renderDelayBuffer.buffer, block, 0);
    }
}
