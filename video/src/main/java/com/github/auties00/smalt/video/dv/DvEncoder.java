package com.github.auties00.smalt.video.dv;

import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.video.VideoEncoder;
import com.github.auties00.smalt.video.VideoFrame;
import com.github.auties00.smalt.video.dv.internal.DvFrameEncoder;
import com.github.auties00.smalt.video.intra.internal.IntraEncoderCore;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Encodes DV video: the 25 Mb/s DV of IEC 61834-2, DVCPRO and DVCPRO50 (SMPTE ST 314) and DVCPRO HD (SMPTE
 * ST 370), in the {@link DvProfile} its options name.
 *
 * <p>Pictures must have the profile's size and may be any {@link com.github.auties00.smalt.image.Picture}: they
 * are converted to 8-bit limited-range Y'CbCr with the BT.601 matrix for the SD profiles and BT.709 for DVCPRO HD,
 * in the profile's chroma layout (4:1:1 chroma averages two 4:2:2 samples). Every frame becomes one key-frame
 * packet of {@link DvProfile#frameSize()} bytes: a complete DIF frame whose header, subcode (a drop-frame time code
 * at 60 Hz, counting from zero), VAUX (the signal type and the 4:3 or 16:9 display) and audio sections are filled,
 * the audio sections without audio. Each video segment takes the finest quantization at which its five
 * macroblocks fit, refined macroblock by macroblock.
 *
 * <p>A 720-line packet is one video frame; the even frames of the stream take DIF channels 0 and 1, the odd frames
 * channels 2 and 3, so that two consecutive packets form the four-channel frame of SMPTE ST 370.
 *
 * <p>Instances are not thread-safe. The video segments of a frame are coded on the module's shared helper threads
 * unless {@link DvEncoderOptions#threads()} is {@code 1}; the output does not depend on the threading.
 */
public final class DvEncoder implements VideoEncoder {
    private final DvEncoderOptions options;
    private final DvProfile profile;
    private final Rational timeBase;
    private final IntraEncoderCore core;
    private long frames;

    /**
     * Constructs an encoder with the {@link DvEncoderOptions#defaults()}.
     */
    public DvEncoder() {
        this(DvEncoderOptions.defaults());
    }

    /**
     * Constructs an encoder.
     *
     * @param options the encoding options
     */
    public DvEncoder(DvEncoderOptions options) {
        this.options = Objects.requireNonNull(options, "options");
        this.profile = options.profile();
        var frameRate = profile.frameRate();
        this.timeBase = options.timeBase().orElse(new Rational(frameRate.denominator(), frameRate.numerator()));
        var encoder = new DvFrameEncoder(profile.dif(), options.wide(), options.adaptiveDct(), options.threads());
        this.core = new IntraEncoderCore(profile.width(), profile.height(), frameRate, timeBase,
                picture -> encoder.encode(picture, frames));
    }

    /**
     * {@return the encoding options}
     */
    public DvEncoderOptions options() {
        return options;
    }

    @Override
    public Codec codec() {
        return Codec.DV;
    }

    @Override
    public int width() {
        return profile.width();
    }

    @Override
    public int height() {
        return profile.height();
    }

    @Override
    public Rational timeBase() {
        return timeBase;
    }

    /**
     * {@inheritDoc}
     *
     * <p>DV carries no configuration, so the result is always empty.
     */
    @Override
    public Optional<byte[]> codecConfig() {
        return Optional.empty();
    }

    /**
     * Encodes one frame into one key-frame packet.
     *
     * @param frame the picture, of the profile's size, and its timestamp
     * @param output receives the packet of track 0
     * @throws IllegalArgumentException if the picture size differs from the profile's or the timestamp does not
     * follow the previous one
     */
    @Override
    public void encode(VideoFrame frame, Consumer<Packet> output) {
        Objects.requireNonNull(output, "output");
        var packet = core.encode(frame);
        frames++;
        output.accept(packet);
    }

    /**
     * Does nothing: every frame is emitted by the {@link #encode(VideoFrame, Consumer)} call that receives it.
     *
     * @param output not called
     */
    @Override
    public void flush(Consumer<Packet> output) {
        Objects.requireNonNull(output, "output");
    }
}
