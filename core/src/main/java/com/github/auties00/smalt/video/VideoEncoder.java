package com.github.auties00.smalt.video;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;

import java.util.Optional;
import java.util.function.Consumer;

/**
 * Encodes pictures into the packets of one video track.
 *
 * <p>Frames are fed in presentation order through {@link #encode(VideoFrame, Consumer)} with
 * timestamps in {@link #timeBase()} units; packets come out in decoding order carrying both
 * timestamps. {@link #flush(Consumer)} drains every delayed packet once the input ends.
 *
 * @implSpec
 * Implementations must accept pictures of exactly {@link #width()} by {@link #height()} pixels in
 * any {@link com.github.auties00.smalt.image.Picture} representation, converting color as
 * needed, and must not retain the frame after {@link #encode(VideoFrame, Consumer)} returns unless
 * they copied it.
 */
public interface VideoEncoder extends AutoCloseable {
    /**
     * Returns the coding format produced.
     *
     * @return the codec
     */
    Codec codec();

    /**
     * Returns the coded width.
     *
     * @return the width in pixels
     */
    int width();

    /**
     * Returns the coded height.
     *
     * @return the height in pixels
     */
    int height();

    /**
     * Returns the unit of the timestamps of accepted frames and produced packets.
     *
     * @return the time base
     */
    Rational timeBase();

    /**
     * Returns the out-of-band decoder configuration a container must store for this stream.
     *
     * @return the configuration in the form described by
     *         {@link com.github.auties00.smalt.track.Track#codecConfig()}, or empty when
     *         the codec needs none
     */
    Optional<byte[]> codecConfig();

    /**
     * Encodes one frame and emits every packet that became ready.
     *
     * @param frame  the next frame in presentation order
     * @param output receives zero or more packets
     * @throws IllegalArgumentException if the picture size differs from the encoder's
     */
    void encode(VideoFrame frame, Consumer<Packet> output);

    /**
     * Emits every packet the encoder still holds after the last frame.
     *
     * @param output receives zero or more packets
     */
    void flush(Consumer<Packet> output);

    /**
     * Requests that the next frame passed to {@link #encode(VideoFrame, Consumer)} be coded as a
     * keyframe, from which a decoder can start without any earlier packet.
     *
     * <p>Requests made before that frame is encoded collapse into one keyframe. An encoder whose
     * every frame is a keyframe accepts the request and changes nothing.
     *
     * @implSpec The default implementation throws {@link MediaException.Unsupported}; an
     * implementation that can force keyframes overrides it.
     * @throws MediaException.Unsupported if the encoder cannot force a keyframe
     */
    default void requestKeyframe() {
        throw new MediaException.Unsupported("keyframe request",
                codec().displayName() + " encoder cannot force a keyframe");
    }

    /**
     * Changes the target bitrate of the frames encoded from now on, without restarting the stream.
     *
     * <p>How quickly the coded rate follows the new target depends on the codec's rate control.
     *
     * @implSpec The default implementation throws {@link MediaException.Unsupported}; an
     * implementation that can retarget a running stream overrides it.
     * @param bitsPerSecond the new target, in bits per second; positive
     * @throws IllegalArgumentException if {@code bitsPerSecond} is not positive or the codec cannot
     * reach it
     * @throws IllegalStateException if the encoder runs without a target bitrate, for example in a
     * constant-quality mode
     * @throws MediaException.Unsupported if the encoder cannot change its bitrate while running
     */
    default void setTargetBitrate(long bitsPerSecond) {
        throw new MediaException.Unsupported("bitrate change",
                codec().displayName() + " encoder cannot change its bitrate while running");
    }

    /**
     * Releases the encoder's resources, including any worker threads.
     *
     * <p>The default implementation holds none and does nothing.
     */
    @Override
    default void close() {
    }
}
