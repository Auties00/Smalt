package com.github.auties00.smalt.video.theora;

import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.internal.codec.FrameClock;
import com.github.auties00.smalt.internal.header.XiphLacing;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.video.VideoEncoder;
import com.github.auties00.smalt.video.VideoFrame;
import com.github.auties00.smalt.video.theora.internal.TheoraFrameEncoder;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Encodes pictures as Theora video (the Theora I specification, bitstream version 3.2.1), every frame an intra
 * frame.
 *
 * <p>The stream codes the picture at the top left of a coded frame of whole 16x16 macro blocks with the VP3
 * quantization matrices of libtheora at the options' {@linkplain TheoraEncoderOptions#quality() quality index},
 * and one static Huffman code for every token table. {@link #codecConfig()} holds the identification, comment and
 * setup headers, Xiph-laced as {@link Codec#THEORA} specifies, ready for the Ogg and Matroska muxers. Any Theora
 * decoder reads the stream; it decodes to the same samples in {@link TheoraDecoder} and in libtheora.
 *
 * <p>A {@link YuvPicture} of the options' chroma format is coded as it is, without its alpha plane; any other
 * picture is converted with {@link ImageOps#toYuv(Picture, ChromaFormat, ColorInfo) ImageOps.toYuv} and
 * {@link TheoraEncoderOptions#color()}. Every packet holds one key frame, so {@link #requestKeyframe()} has
 * nothing to do.
 *
 * <p>Instances are not thread-safe.
 */
public final class TheoraEncoder implements VideoEncoder {
    private final int width;
    private final int height;
    private final TheoraEncoderOptions options;
    private final TheoraFrameEncoder frames;
    private final FrameClock clock;
    private final byte[] configuration;

    /**
     * Constructs an encoder with the {@link TheoraEncoderOptions#defaults()}.
     *
     * @param width the picture width, from 1 to 1048560
     * @param height the picture height, from 1 to 1048560
     * @throws IllegalArgumentException if a dimension is out of range
     */
    public TheoraEncoder(int width, int height) {
        this(width, height, TheoraEncoderOptions.defaults());
    }

    /**
     * Constructs an encoder.
     *
     * @param width the picture width, from 1 to 1048560
     * @param height the picture height, from 1 to 1048560
     * @param options the encoding options
     * @throws IllegalArgumentException if a dimension is out of range
     */
    public TheoraEncoder(int width, int height, TheoraEncoderOptions options) {
        this.options = Objects.requireNonNull(options, "options");
        if (width < 1 || height < 1 || width > 0xFFFF0 || height > 0xFFFF0) {
            throw new IllegalArgumentException("Theora pictures are 1 to 1048560 samples wide and high, not "
                    + width + "x" + height);
        }
        this.width = width;
        this.height = height;
        var pixelFormat = switch (options.chromaFormat()) {
            case YUV420 -> 0;
            case YUV422 -> 2;
            default -> 3;
        };
        var aspect = options.sampleAspectRatio();
        this.frames = new TheoraFrameEncoder(width, height, pixelFormat, options.quality(),
                options.frameRate().numerator(), options.frameRate().denominator(),
                aspect.map(Rational::numerator).orElse(0L), aspect.map(Rational::denominator).orElse(0L), "Smalt");
        this.configuration = XiphLacing.lace(frames.headers());
        this.clock = new FrameClock(options.frameRate(), options.timeBase());
    }

    /**
     * {@return the encoding options}
     */
    public TheoraEncoderOptions options() {
        return options;
    }

    @Override
    public Codec codec() {
        return Codec.THEORA;
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
     * @return the three Theora headers, Xiph-laced
     */
    @Override
    public Optional<byte[]> codecConfig() {
        return Optional.of(configuration.clone());
    }

    /**
     * Encodes one picture into one key frame packet.
     *
     * @param frame the frame; its picture must be of the encoder's size
     * @param output receives the packet
     * @throws IllegalArgumentException if the picture size differs from the encoder's or the timestamp does not
     *                                  follow the previous frame's
     */
    @Override
    public void encode(VideoFrame frame, Consumer<Packet> output) {
        Objects.requireNonNull(frame, "frame");
        Objects.requireNonNull(output, "output");
        var picture = frame.picture();
        if (picture.width() != width || picture.height() != height) {
            throw new IllegalArgumentException("picture is " + picture.width() + "x" + picture.height()
                    + ", encoder expects " + width + "x" + height);
        }
        var timing = clock.resolve(frame);
        var yuv = picture instanceof YuvPicture candidate && candidate.chromaFormat() == options.chromaFormat()
                ? candidate : ImageOps.toYuv(picture, options.chromaFormat(), options.color());
        var data = frames.encode(yuv);
        clock.commit(timing.pts());
        output.accept(new Packet(0, data, timing.pts(), timing.pts(), timing.duration(), true));
    }

    /**
     * Does nothing: every frame is a key frame.
     */
    @Override
    public void requestKeyframe() {
    }

    /**
     * Does nothing: every packet is output by the call that encodes its picture.
     *
     * @param output unused
     */
    @Override
    public void flush(Consumer<Packet> output) {
        Objects.requireNonNull(output, "output");
    }
}
