package com.github.auties00.smalt.video.prores;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.video.VideoEncoder;
import com.github.auties00.smalt.video.VideoFrame;
import com.github.auties00.smalt.video.intra.internal.IntraEncoderCore;
import com.github.auties00.smalt.video.prores.internal.ProResFrameEncoder;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Encodes pictures into Apple ProRes frames, following SMPTE RDD 36.
 *
 * <p>Every flavor of {@link ProResProfile} encodes, with its quantization matrices and target data
 * rate: the 422 flavors code 4:2:2 Y'CbCr at 10 bits and the 4444 flavors 4:4:4 Y'CbCr at 12 bits,
 * with the input's alpha channel coded losslessly when the input has one (see
 * {@link ProResEncoderOptions#alpha()}). Pictures of any representation are accepted and converted
 * as needed: Y'CbCr pictures keep their matrix and are resampled to the flavor's layout and
 * depth, RGB pictures are converted with the matrix of {@link ProResEncoderOptions#color()}. With
 * an interlaced {@link ProResEncoderOptions#fieldOrder()}, every frame is coded as two field
 * pictures.
 *
 * <p>Every frame is independent: each {@link #encode(VideoFrame, Consumer)} emits one key-frame
 * packet carrying the frame's timestamp (or, without one, the timestamp following the previous
 * frame at the nominal frame rate) and duration, and {@link #flush(Consumer)} has nothing to
 * drain. {@link #codecConfig()} is the flavor's QuickTime sample entry type, which a QuickTime
 * muxer stores as the sample entry and a Matroska muxer as the {@code V_PRORES} codec private data.
 *
 * <p>Instances are not thread-safe. Large frames are encoded in parallel slice rows on the
 * module's shared helper threads unless {@link ProResEncoderOptions#threads()} is {@code 1}; the
 * output does not depend on the threading.
 *
 * @apiNote ProRes is an Apple technology that Apple licenses; see the licensing note of the
 * project's format documentation before distributing ProRes files made with this encoder.
 */
public final class ProResEncoder implements VideoEncoder {
    /**
     * The largest frame dimension, the limit of the 16-bit size fields of the frame header.
     */
    private static final int MAX_DIMENSION = 65535;

    private final int width;

    private final int height;

    private final ProResEncoderOptions options;

    /**
     * The calls of this encoder.
     */
    private final IntraEncoderCore core;

    /**
     * Constructs an encoder for a picture size under the {@link ProResEncoderOptions#defaults()}.
     *
     * @param width the picture width, from 1 to 65535
     * @param height the picture height, from 1 to 65535
     * @throws IllegalArgumentException if a dimension is out of range
     * @throws MediaException.LimitExceeded if the picture exceeds the default limits
     */
    public ProResEncoder(int width, int height) {
        this(width, height, ProResEncoderOptions.defaults());
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
    public ProResEncoder(int width, int height, ProResEncoderOptions options) {
        this.options = Objects.requireNonNull(options, "options");
        if (width < 1 || height < 1 || width > MAX_DIMENSION || height > MAX_DIMENSION) {
            throw new IllegalArgumentException("invalid picture size " + width + "x" + height);
        }
        LimitChecks.checkPicture(options.limits(), width, height);
        this.width = width;
        this.height = height;
        this.core = new IntraEncoderCore(width, height, options.frameRate(), options.timeBase(),
                new ProResFrameEncoder(width, height, options)::encode);
    }

    /**
     * {@return the encoding options}
     */
    public ProResEncoderOptions options() {
        return options;
    }

    /**
     * {@inheritDoc}
     *
     * @return {@link Codec#PRORES}
     */
    @Override
    public Codec codec() {
        return Codec.PRORES;
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
     * @return the flavor's four-character sample entry type in ASCII, for example {@code apch}
     */
    @Override
    public Optional<byte[]> codecConfig() {
        return Optional.of(options.profile().fourCc().getBytes(StandardCharsets.US_ASCII));
    }

    /**
     * Encodes one frame into one key-frame packet.
     *
     * @param frame the next frame in presentation order
     * @param output receives the packet
     * @throws IllegalArgumentException if the picture size differs from the encoder's or the
     * timestamp does not follow the previous one
     * @throws MediaException.Unsupported if the picture is RGB and the matrix of
     * {@link ProResEncoderOptions#color()} is not defined by two luma weights
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
     * @return the key-frame packet
     * @throws IllegalArgumentException if the picture size differs from the encoder's or the
     * timestamp does not follow the previous one
     * @throws MediaException.Unsupported if the picture is RGB and the matrix of
     * {@link ProResEncoderOptions#color()} is not defined by two luma weights
     */
    public Packet encodeFrame(VideoFrame frame) {
        return core.encode(frame);
    }

    /**
     * Does nothing: every ProRes frame is coded independently, so every packet is a key frame.
     */
    @Override
    public void requestKeyframe() {
    }

    /**
     * Does nothing: ProRes frames are never held back.
     *
     * @param output receives no packet
     */
    @Override
    public void flush(Consumer<Packet> output) {
        Objects.requireNonNull(output, "output");
    }
}
