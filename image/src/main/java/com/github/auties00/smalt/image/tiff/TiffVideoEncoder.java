package com.github.auties00.smalt.image.tiff;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.internal.codec.FrameClock;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.internal.task.ParallelBands;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.video.VideoEncoder;
import com.github.auties00.smalt.video.VideoFrame;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Encodes pictures into TIFF video ({@link Codec#TIFF}), every frame an independent TIFF file.
 *
 * <p>Each frame is exactly the single-image file {@link TiffEncoder#encode(com.github.auties00.smalt.image.Picture,
 * TiffEncoderOptions)} writes for the frame's picture under {@link TiffVideoEncoderOptions#coding()}; with a lossless
 * compression it keeps the picture's samples and alpha, and pictures are converted as {@code TiffEncoder} converts
 * them. Every {@link #encode(VideoFrame, Consumer)} emits one key-frame packet carrying
 * the frame's timestamp (or, without one, the timestamp following the previous frame at the nominal frame rate) and
 * duration; {@link #flush(Consumer)} has nothing to drain and the stream needs no configuration. The packets suit the
 * containers that carry {@link Codec#TIFF}: QuickTime and Matroska.
 *
 * <p>Instances are not thread-safe.
 */
public final class TiffVideoEncoder implements VideoEncoder {
    /**
     * The largest frame dimension.
     */
    private static final int MAX_DIMENSION = 65535;

    private final int width;

    private final int height;

    private final TiffVideoEncoderOptions options;

    /**
     * The timestamps of the coded frames.
     */
    private final FrameClock clock;

    /**
     * The operation the calls of this encoder form.
     */
    private final ParallelBands.Operation operation = new ParallelBands.Operation();

    /**
     * Constructs an encoder for a picture size under the {@link TiffVideoEncoderOptions#defaults()}.
     *
     * @param width the picture width, from 1 to 65535
     * @param height the picture height, from 1 to 65535
     * @throws IllegalArgumentException if a dimension is out of range
     * @throws MediaException.LimitExceeded if the picture exceeds the default limits
     */
    public TiffVideoEncoder(int width, int height) {
        this(width, height, TiffVideoEncoderOptions.defaults());
    }

    /**
     * Constructs an encoder for a picture size.
     *
     * @param width the picture width, from 1 to 65535
     * @param height the picture height, from 1 to 65535
     * @param options the encoding options
     * @throws IllegalArgumentException if a dimension is out of range
     * @throws MediaException.LimitExceeded if the picture exceeds the options' limits
     */
    public TiffVideoEncoder(int width, int height, TiffVideoEncoderOptions options) {
        this.options = Objects.requireNonNull(options, "options");
        if (width < 1 || height < 1 || width > MAX_DIMENSION || height > MAX_DIMENSION) {
            throw new IllegalArgumentException("invalid picture size " + width + "x" + height);
        }
        LimitChecks.checkPicture(options.limits(), width, height);
        this.width = width;
        this.height = height;
        this.clock = new FrameClock(options.frameRate(), options.timeBase());
    }

    /**
     * {@return the encoding options}
     */
    public TiffVideoEncoderOptions options() {
        return options;
    }

    /**
     * {@inheritDoc}
     *
     * @return {@link Codec#TIFF}
     */
    @Override
    public Codec codec() {
        return Codec.TIFF;
    }

    @Override
    public int width() {
        return width;
    }

    @Override
    public int height() {
        return height;
    }

    @Override
    public Rational timeBase() {
        return options.timeBase();
    }

    /**
     * {@inheritDoc}
     *
     * @return empty: TIFF frames carry their whole configuration
     */
    @Override
    public Optional<byte[]> codecConfig() {
        return Optional.empty();
    }

    /**
     * Encodes one frame into one key-frame packet.
     *
     * @param frame the next frame in presentation order
     * @param output receives the packet
     * @throws IllegalArgumentException if the picture size differs from the encoder's or the timestamp does not
     * follow the previous one
     */
    @Override
    public void encode(VideoFrame frame, Consumer<Packet> output) {
        Objects.requireNonNull(frame, "frame");
        Objects.requireNonNull(output, "output");
        output.accept(encodeFrame(frame));
    }

    /**
     * Encodes one frame and returns its packet.
     *
     * @param frame the picture and its timestamp
     * @return the key-frame packet of track 0, whose presentation and decoding times are the frame's timestamp
     * @throws IllegalArgumentException if the picture size differs from the encoder's or the timestamp does not
     * follow the previous one
     */
    public Packet encodeFrame(VideoFrame frame) {
        Objects.requireNonNull(frame, "frame");
        try (var _ = ParallelBands.operation(operation)) {
            var picture = frame.picture();
            if (picture.width() != width || picture.height() != height) {
                throw new IllegalArgumentException("picture is " + picture.width() + "x" + picture.height()
                        + ", encoder expects " + width + "x" + height);
            }
            var timing = clock.resolve(frame);
            var data = TiffEncoder.encode(picture, options.coding());
            clock.commit(timing.pts());
            return new Packet(0, data, timing.pts(), timing.pts(), timing.duration(), true);
        }
    }

    /**
     * Does nothing: every TIFF frame is coded independently, so every packet is a key frame.
     */
    @Override
    public void requestKeyframe() {
    }

    /**
     * Does nothing: TIFF frames are never held back.
     *
     * @param output receives no packet
     */
    @Override
    public void flush(Consumer<Packet> output) {
        Objects.requireNonNull(output, "output");
    }
}
