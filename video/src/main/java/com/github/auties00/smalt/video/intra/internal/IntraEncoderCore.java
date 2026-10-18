package com.github.auties00.smalt.video.intra.internal;

import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.internal.codec.FrameClock;
import com.github.auties00.smalt.internal.task.ParallelBands;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.video.VideoFrame;

import java.util.Objects;
import java.util.function.Function;

/**
 * Runs the calls of an intra-only video encoder (ProRes and VC-3): checks the picture size,
 * assigns the frame's timing with a {@link FrameClock} and codes the frame into one key-frame
 * packet.
 *
 * <p>A frame whose coding fails leaves the clock as it was. Instances are not thread-safe.
 */
public final class IntraEncoderCore {
    /**
     * The picture width.
     */
    private final int width;

    /**
     * The picture height.
     */
    private final int height;

    /**
     * The timestamps of the coded frames.
     */
    private final FrameClock clock;

    /**
     * Codes one picture of the encoder's size into one frame.
     */
    private final Function<Picture, byte[]> frames;

    /**
     * The operation the calls of the encoder form.
     */
    private final ParallelBands.Operation operation = new ParallelBands.Operation();

    /**
     * Constructs the core of an encoder.
     *
     * @param width the picture width
     * @param height the picture height
     * @param frameRate the nominal frame rate, which gives the timing of frames without one
     * @param timeBase the time base of the timestamps
     * @param frames codes one picture of the encoder's size into one frame
     */
    public IntraEncoderCore(int width, int height, Rational frameRate, Rational timeBase,
                            Function<Picture, byte[]> frames) {
        this.width = width;
        this.height = height;
        this.clock = new FrameClock(frameRate, timeBase);
        this.frames = frames;
    }

    /**
     * Encodes one frame into one key-frame packet.
     *
     * @param frame the picture and its timestamp
     * @return the packet of track 0, whose presentation and decoding times are the frame's timestamp
     * @throws IllegalArgumentException if the picture size differs from the encoder's or the
     * timestamp does not follow the previous one
     */
    public Packet encode(VideoFrame frame) {
        Objects.requireNonNull(frame, "frame");
        try (var _ = ParallelBands.operation(operation)) {
            var picture = frame.picture();
            if (picture.width() != width || picture.height() != height) {
                throw new IllegalArgumentException("picture is " + picture.width() + "x" + picture.height()
                        + ", encoder expects " + width + "x" + height);
            }
            var timing = clock.resolve(frame);
            var data = frames.apply(picture);
            clock.commit(timing.pts());
            return new Packet(0, data, timing.pts(), timing.pts(), timing.duration(), true);
        }
    }
}
