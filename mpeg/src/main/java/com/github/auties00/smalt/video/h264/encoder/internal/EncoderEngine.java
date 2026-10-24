package com.github.auties00.smalt.video.h264.encoder.internal;

import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.track.Packet;

/**
 * Codes the frames of an H.264 encoder: {@link EncoderCore} for 8-bit 4:2:0 progressive lossy
 * streams, {@link WideCore} for every other stream.
 *
 * <p>Implementations are not thread-safe, except {@link #requestKeyframe()},
 * {@link #setTargetBitrate(int)} and the counters, which may be called from any thread.
 */
public interface EncoderEngine {
    /**
     * Returns an engine for a configuration.
     *
     * @param config the configuration
     * @return the engine
     * @throws IllegalArgumentException if an explicit level is too small for the stream
     */
    static EncoderEngine of(EncoderConfig config) {
        return config.extended() ? new WideCore(config) : new EncoderCore(config);
    }

    /**
     * Encodes one frame.
     *
     * @param picture the picture, exactly the configured size
     * @param pts the presentation timestamp in time-base units, or {@link Packet#NO_TIMESTAMP}
     * @param duration the frame duration in time-base units, or {@code 0} when unknown
     * @return the packet of the oldest frame that is ready, or {@code null} while frames are held
     * @throws IllegalArgumentException if the picture size differs from the configured size or the
     * timestamp does not increase outside real-time mode
     * @throws IllegalStateException if the encoder is closed
     */
    Packet encode(Picture picture, long pts, long duration);

    /**
     * Returns the timestamp the last frame given to {@link #encode(Picture, long, long)} was stamped with: its own,
     * or the one the engine chose for a missing or non-increasing timestamp.
     *
     * @return the timestamp in time-base units, or {@link Packet#NO_TIMESTAMP} before the first frame
     */
    long lastSubmittedPts();

    /**
     * Encodes the frames still held, one per call.
     *
     * @return the next packet, or {@code null} when none is left
     * @throws IllegalStateException if the encoder is closed
     */
    Packet flush();

    /**
     * {@return the {@code AVCDecoderConfigurationRecord}, a copy}
     */
    byte[] configurationRecord();

    /**
     * Makes the next frame an IDR picture; callable from any thread.
     */
    void requestKeyframe();

    /**
     * Sets the target bitrate from the next frame on; callable from any thread.
     *
     * @param bitsPerSecond the bitrate, positive
     */
    void setTargetBitrate(int bitsPerSecond);

    /**
     * {@return the target bitrate rate control aims at, 0 for constant quality}
     */
    int targetBitrate();

    /**
     * {@return the number of frames encoded}
     */
    long frameCount();

    /**
     * {@return the number of IDR access units encoded}
     */
    long keyframeCount();

    /**
     * {@return the number of key frame requests received}
     */
    long keyframeRequestCount();

    /**
     * {@return the total size of the packets produced}
     */
    long byteCount();

    /**
     * {@return the quantizer of the last coded frame}
     */
    int lastQp();

    /**
     * {@return the temporal layer of the last coded frame}
     */
    int lastTemporalLayer();

    /**
     * Closes the engine, which rejects every later frame.
     */
    void close();
}
