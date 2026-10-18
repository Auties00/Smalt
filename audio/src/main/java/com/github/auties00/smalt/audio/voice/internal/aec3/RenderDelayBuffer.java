package com.github.auties00.smalt.audio.voice.internal.aec3;

import static com.github.auties00.smalt.audio.voice.internal.aec3.Aec3.BLOCK_SIZE;
import static com.github.auties00.smalt.audio.voice.internal.aec3.Aec3.FFT_LENGTH_BY_2_PLUS_1;

/**
 * Buffers the render blocks of AEC3, at full rate for the echo remover and decimated for the
 * delay estimator, and aligns the read positions with the capture signal by the estimated
 * delay.
 *
 * <p>{@link #insert(float[], int)} returns {@link #EVENT_NONE} or {@link #EVENT_RENDER_OVERRUN};
 * {@link #prepareCaptureProcessing()} additionally returns {@link #EVENT_RENDER_UNDERRUN}.
 *
 * @implNote This implementation ports {@code RenderDelayBufferImpl} of WebRTC's
 * {@code modules/audio_processing/aec3/render_delay_buffer.cc} for one render channel, one band
 * and a render gain of 0 dB. The API call jitter counters, which only feed log messages, are
 * omitted.
 */
final class RenderDelayBuffer {
    /**
     * No buffering event occurred.
     */
    static final int EVENT_NONE = 0;

    /**
     * More render than capture data arrived; the buffer was reset.
     */
    static final int EVENT_RENDER_OVERRUN = 1;

    /**
     * Less render than capture data arrived.
     */
    static final int EVENT_RENDER_UNDERRUN = 2;

    /**
     * The number of samples of a decimated sub-block.
     */
    private static final int SUB_BLOCK_SIZE = BLOCK_SIZE / Aec3.DOWN_SAMPLING_FACTOR;

    /**
     * The size of the decimated ring, {@code GetDownSampledBufferSize(4, 5)}.
     */
    static final int LOW_RATE_SIZE = SUB_BLOCK_SIZE
            * (Aec3.MATCHED_FILTER_ALIGNMENT_SHIFT_SIZE_SUB_BLOCKS * Aec3.NUM_FILTERS + Aec3.MATCHED_FILTER_WINDOW_SIZE_SUB_BLOCKS + 1);

    /**
     * The minimum number of blocks between the read position and the oldest data, the refined
     * filter length.
     */
    private static final int BUFFER_HEADROOM = Aec3.REFINED_LENGTH_BLOCKS;

    /**
     * The full-rate rings.
     */
    final RenderBuffer buffer = new RenderBuffer();

    /**
     * The decimated ring, most recent sample first within each sub-block, followed by a mirror
     * of its first {@link MatchedFilter#FILTER_LENGTH} samples so that every matched filter
     * window starting inside the ring is contiguous.
     */
    final float[] lowRate = new float[LOW_RATE_SIZE + MatchedFilter.FILTER_LENGTH];

    /**
     * The write index of the decimated ring.
     */
    int lowRateWrite;

    /**
     * The read index of the decimated ring.
     */
    int lowRateRead;

    /**
     * The decimator of the render signal.
     */
    private final Decimator decimator;

    /**
     * The transform of the render blocks.
     */
    private final Aec3Fft fft = new Aec3Fft();

    /**
     * The scratch buffer of a decimated sub-block.
     */
    private final float[] renderDs = new float[SUB_BLOCK_SIZE];

    /**
     * The current delay in blocks, or {@code -1} when none has been set since the last reset.
     */
    private int delay = Aec3.DEFAULT_DELAY;

    /**
     * Whether the render signal was found active since the last capture block.
     */
    private boolean renderActivity;

    /**
     * The number of active render blocks seen while looking for activity.
     */
    private int renderActivityCounter;

    /**
     * The externally reported audio buffer delay in blocks, or {@code -1} when none was
     * reported.
     */
    private int externalAudioBufferDelay = -1;

    /**
     * The smallest latency seen in the current excess-render detection interval.
     */
    private int minLatencyBlocks;

    /**
     * The number of capture blocks of the current excess-render detection interval.
     */
    private int excessRenderDetectionCounter;

    /**
     * Creates a buffer and resets it.
     *
     * @param flushTiny whether the decimator zeroes tiny filter states and samples; {@code false}
     *                  reproduces the reference bit for bit
     */
    RenderDelayBuffer(boolean flushTiny) {
        this.decimator = new Decimator(flushTiny);
        reset();
    }

    /**
     * Resets the read positions: one sub-block behind the write position for the decimated
     * ring, and the external delay (or the default delay) for the full-rate rings.
     */
    void reset() {
        minLatencyBlocks = 0;
        excessRenderDetectionCounter = 0;
        lowRateRead = lowRateOffsetIndex(lowRateWrite, SUB_BLOCK_SIZE);
        if (externalAudioBufferDelay >= 0) {
            var headroom = 2;
            int audioBufferDelayToSet;
            if (externalAudioBufferDelay <= headroom) {
                audioBufferDelayToSet = 1;
            } else {
                audioBufferDelayToSet = externalAudioBufferDelay - headroom;
            }
            audioBufferDelayToSet = Math.min(audioBufferDelayToSet, maxDelay());
            applyTotalDelay(audioBufferDelayToSet);
            delay = computeDelay();
        } else {
            applyTotalDelay(Aec3.DEFAULT_DELAY);
            delay = -1;
        }
    }

    /**
     * Inserts a render block.
     *
     * @param block  the samples
     * @param offset the index of the first of the 64 samples
     * @return {@link #EVENT_RENDER_OVERRUN} when the buffer overran and was reset, otherwise
     *         {@link #EVENT_NONE}
     */
    int insert(float[] block, int offset) {
        var previousWrite = buffer.blockWrite;
        incrementWriteIndices();
        var event = renderOverrun() ? EVENT_RENDER_OVERRUN : EVENT_NONE;
        if (!renderActivity) {
            renderActivityCounter += detectActiveRender(block, offset) ? 1 : 0;
            renderActivity = renderActivityCounter >= 20;
        }
        insertBlock(block, offset, previousWrite);
        if (event != EVENT_NONE) {
            reset();
        }
        return event;
    }

    /**
     * Advances the read positions to the render data of the next capture block.
     *
     * @return the buffering event of the call
     */
    int prepareCaptureProcessing() {
        var event = EVENT_NONE;
        if (detectExcessRenderBlocks()) {
            reset();
            event = EVENT_RENDER_OVERRUN;
        } else if (renderUnderrun()) {
            incrementReadIndices();
            if (delay > 0) {
                delay = delay - 1;
            }
            event = EVENT_RENDER_UNDERRUN;
        } else {
            lowRateRead = lowRateOffsetIndex(lowRateRead, -SUB_BLOCK_SIZE);
            incrementReadIndices();
        }
        buffer.renderActivity = renderActivity;
        if (renderActivity) {
            renderActivityCounter = 0;
            renderActivity = false;
        }
        return event;
    }

    /**
     * Aligns the full-rate read positions with a delay estimate.
     *
     * @param newDelay the estimated delay, in blocks
     * @return whether the delay changed
     */
    boolean alignFromDelay(int newDelay) {
        if (delay >= 0 && delay == newDelay) {
            return false;
        }
        delay = newDelay;
        var totalDelay = bufferLatency() + delay;
        totalDelay = Math.min(maxDelay(), Math.max(totalDelay, 0));
        applyTotalDelay(totalDelay);
        return true;
    }

    /**
     * Records an externally reported audio buffer delay, used by the next reset.
     *
     * @param delayMs the delay in milliseconds, rounded down to blocks
     */
    void setAudioBufferDelay(int delayMs) {
        externalAudioBufferDelay = delayMs / 4;
    }

    /**
     * Returns the delay between the render read position and the capture signal, excluding the
     * buffered latency.
     *
     * @return the delay in blocks
     */
    int computeDelay() {
        var latencyBlocks = bufferLatency();
        var internalDelay = buffer.spectrumRead >= buffer.spectrumWrite
                ? buffer.spectrumRead - buffer.spectrumWrite
                : RenderBuffer.SIZE + buffer.spectrumRead - buffer.spectrumWrite;
        return internalDelay - latencyBlocks;
    }

    /**
     * Returns the largest delay the buffer can apply.
     *
     * @return the maximum delay in blocks
     */
    private static int maxDelay() {
        return RenderBuffer.SIZE - 1 - BUFFER_HEADROOM;
    }

    /**
     * Sets the full-rate read positions at a total delay from the write positions.
     *
     * @param totalDelay the delay in blocks
     */
    private void applyTotalDelay(int totalDelay) {
        buffer.blockRead = RenderBuffer.offsetIndex(buffer.blockWrite, -totalDelay);
        buffer.spectrumRead = RenderBuffer.offsetIndex(buffer.spectrumWrite, totalDelay);
        buffer.fftRead = RenderBuffer.offsetIndex(buffer.fftWrite, totalDelay);
    }

    /**
     * Writes a block, its decimation, its spectrum and its power spectrum at the write
     * positions.
     *
     * @param block         the samples
     * @param offset        the index of the first sample
     * @param previousWrite the block ring index of the previous block
     */
    private void insertBlock(float[] block, int offset, int previousWrite) {
        var b = buffer;
        var writeOffset = b.blockWrite * BLOCK_SIZE;
        System.arraycopy(block, offset, b.blocks, writeOffset, BLOCK_SIZE);
        decimator.decimate(b.blocks, writeOffset, renderDs);
        for (var i = 0; i < SUB_BLOCK_SIZE; i++) {
            lowRate[lowRateWrite + i] = renderDs[SUB_BLOCK_SIZE - 1 - i];
        }
        if (lowRateWrite < MatchedFilter.FILTER_LENGTH) {
            System.arraycopy(lowRate, lowRateWrite, lowRate, LOW_RATE_SIZE + lowRateWrite, SUB_BLOCK_SIZE);
        }
        var spectrumOffset = b.fftWrite * FFT_LENGTH_BY_2_PLUS_1;
        fft.paddedFft(b.blocks, writeOffset, b.blocks, previousWrite * BLOCK_SIZE, b.fftRe, b.fftIm, spectrumOffset);
        var powerOffset = b.spectrumWrite * FFT_LENGTH_BY_2_PLUS_1;
        for (var k = 0; k < FFT_LENGTH_BY_2_PLUS_1; k++) {
            var re = b.fftRe[spectrumOffset + k];
            var im = b.fftIm[spectrumOffset + k];
            b.spectra[powerOffset + k] = re * re + im * im;
        }
    }

    /**
     * Returns whether a render block is loud enough to count as active.
     *
     * @param x      the samples
     * @param offset the index of the first sample
     * @return whether the block energy exceeds the active render limit
     */
    private static boolean detectActiveRender(float[] x, int offset) {
        var energy = 0.f;
        for (var i = 0; i < BLOCK_SIZE; i++) {
            energy = energy + x[offset + i] * x[offset + i];
        }
        return energy > (Aec3.ACTIVE_RENDER_LIMIT * Aec3.ACTIVE_RENDER_LIMIT) * Aec3.FFT_LENGTH_BY_2;
    }

    /**
     * Returns whether the render data persistently ran ahead of the capture data over the last
     * detection interval.
     *
     * @return whether excess render blocks were detected
     */
    private boolean detectExcessRenderBlocks() {
        var excessRenderDetected = false;
        var latencyBlocks = bufferLatency();
        minLatencyBlocks = Math.min(minLatencyBlocks, latencyBlocks);
        if (++excessRenderDetectionCounter >= Aec3.EXCESS_RENDER_DETECTION_INTERVAL_BLOCKS) {
            excessRenderDetected = minLatencyBlocks > Aec3.MAX_ALLOWED_EXCESS_RENDER_BLOCKS;
            minLatencyBlocks = latencyBlocks;
            excessRenderDetectionCounter = 0;
        }
        return excessRenderDetected;
    }

    /**
     * Returns the number of unread sub-blocks of the decimated ring.
     *
     * @return the latency in blocks
     */
    private int bufferLatency() {
        var latencySamples = (LOW_RATE_SIZE + lowRateRead - lowRateWrite) % LOW_RATE_SIZE;
        return latencySamples / SUB_BLOCK_SIZE;
    }

    /**
     * Advances every write index by one block.
     */
    private void incrementWriteIndices() {
        lowRateWrite = lowRateOffsetIndex(lowRateWrite, -SUB_BLOCK_SIZE);
        buffer.blockWrite = RenderBuffer.incIndex(buffer.blockWrite);
        buffer.spectrumWrite = RenderBuffer.decIndex(buffer.spectrumWrite);
        buffer.fftWrite = RenderBuffer.decIndex(buffer.fftWrite);
    }

    /**
     * Advances the full-rate read indices by one block, unless they reached the write
     * positions.
     */
    private void incrementReadIndices() {
        if (buffer.blockRead != buffer.blockWrite) {
            buffer.blockRead = RenderBuffer.incIndex(buffer.blockRead);
            buffer.spectrumRead = RenderBuffer.decIndex(buffer.spectrumRead);
            buffer.fftRead = RenderBuffer.decIndex(buffer.fftRead);
        }
    }

    /**
     * Returns whether a write position caught up with its read position.
     *
     * @return whether the render data overran the buffer
     */
    private boolean renderOverrun() {
        return lowRateRead == lowRateWrite || buffer.blockRead == buffer.blockWrite;
    }

    /**
     * Returns whether the decimated ring has no unread data.
     *
     * @return whether the render data underran the buffer
     */
    private boolean renderUnderrun() {
        return lowRateRead == lowRateWrite;
    }

    /**
     * Returns a decimated ring index moved by an offset, wrapped.
     *
     * @param index  an index
     * @param offset the offset, at least {@code -LOW_RATE_SIZE}
     * @return the wrapped index
     */
    private static int lowRateOffsetIndex(int index, int offset) {
        return (LOW_RATE_SIZE + index + offset) % LOW_RATE_SIZE;
    }
}
