package com.github.auties00.smalt.video.h264.encoder;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.video.VideoFrame;
import com.github.auties00.smalt.video.h264.encoder.internal.EncoderConfig;
import com.github.auties00.smalt.video.h264.encoder.internal.EncoderEngine;
import com.github.auties00.smalt.video.h264.encoder.internal.PassRecords;

import java.util.Objects;

/**
 * Runs the first pass of a two-pass H.264 encode: codes every picture of a video quickly and produces the
 * {@link H264FirstPassStats} that the second pass, an {@link H264Encoder} configured with
 * {@link H264EncoderOptions.Builder#firstPassStats(H264FirstPassStats)}, reads to spend its bitrate over the
 * whole video.
 *
 * <p>The first pass codes the pictures as an {@link H264Encoder} with the same options would, with the same
 * picture types, IDR placement and B picture structure, but with the motion search and mode decision of
 * {@link H264EncoderSpeed#FASTEST}, and records the type, quantizer and size of every picture; it produces no
 * stream. Pass the same pictures, in the same order and with the same timestamps, to both passes. The
 * statistics do not depend on {@link H264EncoderOptions#threads()}. Instances are not thread-safe.
 *
 * @apiNote A two-pass encode spends its bits where the video needs them, which a single pass can only
 * estimate from its look-ahead, and hits a target size; it suits files whose pictures can be read twice. The
 * first pass costs about as much as a single pass at {@link H264EncoderSpeed#FASTEST}.
 */
public final class H264FirstPass {
    private final int width;

    private final int height;

    private final PassRecords.Recorder recorder = new PassRecords.Recorder();

    private final EncoderEngine engine;

    /**
     * The statistics once {@link #finish()} ended the pass, or {@code null} before.
     */
    private H264FirstPassStats stats;

    /**
     * Constructs the first pass of a video.
     *
     * @param width the picture width in pixels, from 1 to 16384
     * @param height the picture height in pixels, from 1 to 16384
     * @param options the options of the second pass; any {@linkplain H264EncoderOptions#firstPassStats() first
     * pass statistics} they hold are ignored
     * @throws IllegalArgumentException if the size is out of range or exceeds every H.264 level, the level of
     * the options is too small for the stream, the options do not code a lossy
     * {@link H264RateControl#AVERAGE_BITRATE} stream outside {@linkplain H264EncoderOptions#realtime() real-time}
     * mode, or the {@linkplain H264EncoderOptions#hrd() hypothetical reference decoder} they signal gets a rate
     * or buffer that {@link H264Encoder#H264Encoder(int, int, H264EncoderOptions)} rejects
     * @throws MediaException.LimitExceeded if the picture exceeds the options' limits
     */
    public H264FirstPass(int width, int height, H264EncoderOptions options) {
        Objects.requireNonNull(options, "options");
        if (options.rateControl() != H264RateControl.AVERAGE_BITRATE || options.realtime() || options.lossless()) {
            throw new IllegalArgumentException("a two-pass encode needs lossy AVERAGE_BITRATE rate control outside "
                    + "real-time mode");
        }
        this.width = width;
        this.height = height;
        this.engine = EncoderEngine.of(EncoderConfig.of(width, height, options).withPass(null, recorder));
    }

    /**
     * Codes the next picture of the video.
     *
     * @param frame the next frame in presentation order; a frame without a timestamp follows the previous one
     * by the nominal frame duration
     * @throws IllegalArgumentException if the picture size differs from the pass's or the timestamp does not
     * increase
     * @throws IllegalStateException if {@link #finish()} ended the pass
     */
    public void add(VideoFrame frame) {
        Objects.requireNonNull(frame, "frame");
        if (stats != null) {
            throw new IllegalStateException("the first pass is finished");
        }
        engine.encode(frame.picture(), frame.pts(), Math.max(0, frame.duration()), null);
    }

    /**
     * Ends the pass and returns the statistics of the pictures it was given.
     *
     * <p>The pictures still held for look-ahead are coded first. Later calls return the same statistics.
     *
     * @return the statistics
     */
    public H264FirstPassStats finish() {
        if (stats == null) {
            while (engine.flush() != null) {
                // the packets of a first pass are discarded
            }
            engine.close();
            stats = new H264FirstPassStats(width, height, recorder.finish());
        }
        return stats;
    }
}
