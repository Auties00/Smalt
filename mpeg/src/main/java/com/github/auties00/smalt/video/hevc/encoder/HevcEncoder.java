package com.github.auties00.smalt.video.hevc.encoder;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.video.VideoEncoder;
import com.github.auties00.smalt.video.VideoFrame;
import com.github.auties00.smalt.video.hevc.encoder.internal.EncoderEngine;
import com.github.auties00.smalt.video.hevc.encoder.internal.EncoderSettings;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Encodes pictures into an HEVC / H.265 elementary stream.
 *
 * <p>The stream has the {@linkplain HevcEncoderOptions#profile() profile}, {@linkplain
 * HevcEncoderOptions#bitDepth() bit depth}, level and rate control mode of its {@link HevcEncoderOptions}, 4:2:0
 * chroma, 64x64 coding tree blocks and one slice per picture whose rows of coding tree blocks are wavefront
 * substreams, so that decoders may decode them in parallel. The deblocking filter and the sample adaptive
 * offset are on. Without {@linkplain HevcEncoderOptions#bFrames() B pictures} the encoder codes I and P
 * pictures only: every packet's presentation and decoding timestamps are equal and each input frame produces
 * its packet before {@link #encode(VideoFrame, Consumer)} returns. With B pictures it holds each group of
 * frames until the frame after them arrives, codes that one first as a P picture and the group after it, so
 * packets leave in decoding order with a decoding timestamp before the presentation timestamp, and
 * {@link #flush(Consumer)} emits what is still held. No frame is ever dropped. In the
 * {@link HevcPacketFormat#LENGTH_PREFIXED} format, packets hold the frame's NAL units, each preceded by its
 * four-byte big-endian length, and parameter sets travel out of band in {@link #codecConfig()}, an
 * {@code HEVCDecoderConfigurationRecord} with a four-byte NAL length size. In the {@link HevcPacketFormat#ANNEX_B}
 * format, packets are Annex B access units with four-byte start codes, every IDR access unit starts with the
 * video, sequence and picture parameter sets, and {@link #codecConfig()} is empty:
 * {@snippet :
 * IDR packet:     00 00 00 01 40 01 <VPS> 00 00 00 01 42 01 <SPS> 00 00 00 01 44 01 <PPS>
 *                 00 00 00 01 26 01 <IDR slice>
 * P or B packet:  00 00 00 01 02 01 <slice>       (00 00 00 01 00 01 <slice> when no picture references it)
 * }
 * The first packet, every packet after {@link #requestKeyframe()}, every {@code keyframeInterval}-th packet and
 * the first packet of a detected scene change are IDR access units flagged as key frames; packets no later
 * picture references are flagged discardable. A {@linkplain HevcEncoderOptions#pictureHash() decoded picture
 * hash} follows each picture's slice in a suffix SEI message, and the mastering display and content light
 * level of the {@linkplain HevcEncoderOptions#color() color description} precede the slice of every IDR picture
 * in a prefix SEI message.
 *
 * <p>Frames may be given in any {@link com.github.auties00.smalt.image.Picture} representation of exactly the
 * encoder's size. 4:2:0 Y'CbCr samples of the stream's depth are coded as they are; other Y'CbCr layouts are
 * resampled to 4:2:0 and other depths are scaled to the stream's without converting their range or matrix
 * (the stream signals the configured ones, so Y'CbCr input is expected to use them); RGB is converted with the
 * configured color matrix and range, ignoring alpha. Timestamps are in {@link #timeBase()} units and must
 * increase; a frame whose timestamp is {@link Packet#NO_TIMESTAMP} is placed one nominal frame after the
 * previous one. In {@link HevcEncoderOptions#realtime()} mode a timestamp that does not increase is accepted:
 * the packet is stamped one time base unit after the previous one.
 *
 * <p>The reconstruction of every picture equals the output of a conforming decoder of the stream, which a
 * decoder can check against the decoded picture hash. The output depends only on the picture size, the
 * options apart from the thread count, and the input: every thread count produces the same stream.
 *
 * <p>Instances are not thread-safe, except {@link #requestKeyframe()}, {@link #setTargetBitrate(long)} and
 * {@link #stats()}, which may be called from any thread, also while another thread encodes a frame.
 *
 * @implNote This implementation reuses the normative processes of its HEVC decoder (intra and inter prediction,
 * the inverse transform, motion vector prediction, the deblocking filter and the sample adaptive offset) to
 * reconstruct what it codes. The arithmetic coder, its bit estimates, the quantization with sign data hiding and
 * the sample adaptive offset decision follow the HM reference encoder in the ports named in the headers of the
 * internal files, and the Lagrange multipliers HM's configurations; the mode decision, the motion search and the
 * rate control are Smalt's own.
 */
public final class HevcEncoder implements VideoEncoder {
    private final HevcEncoderOptions options;

    private final EncoderSettings settings;

    private final EncoderEngine engine;

    /**
     * Constructs an encoder for a picture size with {@link HevcEncoderOptions#defaults()}.
     *
     * <p>Odd sizes are handled as {@link #HevcEncoder(int, int, HevcEncoderOptions)} describes.
     *
     * @param width the picture width in pixels, from 1 to 16888
     * @param height the picture height in pixels, from 1 to 16888
     * @throws IllegalArgumentException if the size is out of range or exceeds every HEVC level
     * @throws MediaException.LimitExceeded if the picture exceeds the default limits
     */
    public HevcEncoder(int width, int height) {
        this(width, height, HevcEncoderOptions.defaults());
    }

    /**
     * Constructs an encoder for a picture size.
     *
     * <p>Any positive size is accepted. The coded picture is rounded up to whole 8x8 coding blocks and the
     * conformance window restores the display size, in units of two samples; a side that is not even is
     * displayed rounded up to the next even number, the extra column or row repeating the last one.
     *
     * @param width the picture width in pixels, from 1 to 16888
     * @param height the picture height in pixels, from 1 to 16888
     * @param options the encoder settings
     * @throws IllegalArgumentException if the size is out of range or exceeds every HEVC level, or the level of
     * the options is too small for the picture size and frame rate
     * @throws MediaException.LimitExceeded if the picture exceeds the options' limits
     */
    public HevcEncoder(int width, int height, HevcEncoderOptions options) {
        this.options = Objects.requireNonNull(options, "options");
        this.settings = EncoderSettings.of(width, height, options);
        this.engine = new EncoderEngine(settings);
    }

    /**
     * {@return the options this encoder was constructed with}
     */
    public HevcEncoderOptions options() {
        return options;
    }

    /**
     * Returns the encoding engine; a test hook for this package.
     *
     * @return the engine
     */
    EncoderEngine engine() {
        return engine;
    }

    @Override
    public Codec codec() {
        return Codec.HEVC;
    }

    @Override
    public int width() {
        return settings.inputWidth();
    }

    @Override
    public int height() {
        return settings.inputHeight();
    }

    @Override
    public Rational timeBase() {
        return options.timeBase();
    }

    /**
     * {@inheritDoc}
     *
     * @return the {@code HEVCDecoderConfigurationRecord} holding the video, sequence and picture parameter sets
     * and, when the color description carries high-dynamic-range metadata, its SEI message, a fresh copy on
     * every call, or empty for {@link HevcPacketFormat#ANNEX_B} packets, which carry the parameter sets in band
     */
    @Override
    public Optional<byte[]> codecConfig() {
        if (options.packetFormat() == HevcPacketFormat.ANNEX_B) {
            return Optional.empty();
        }
        return Optional.of(engine.configurationRecord());
    }

    /**
     * {@inheritDoc}
     *
     * <p>Without B pictures the encoder emits exactly one packet per frame, before this method returns. With B
     * pictures it emits nothing until a group of frames is complete and then one packet per frame of the group,
     * and {@link #flush(Consumer)} emits the rest.
     *
     * @param frame the next frame in presentation order
     * @param output receives the packets that became ready
     * @throws IllegalArgumentException if the picture size differs from the encoder's or, outside
     * {@link HevcEncoderOptions#realtime()} mode, the timestamp does not increase
     * @throws IllegalStateException if the encoder is closed, or the profile is a still picture profile and
     * the stream already holds its picture
     */
    @Override
    public void encode(VideoFrame frame, Consumer<Packet> output) {
        Objects.requireNonNull(frame, "frame");
        Objects.requireNonNull(output, "output");
        engine.encode(frame.picture(), frame.pts(), Math.max(0, frame.duration()), output);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Codes the frames still held for a group of B pictures, the last of them as a P picture. Without B
     * pictures the encoder holds none.
     *
     * @param output receives the packets of the held frames
     */
    @Override
    public void flush(Consumer<Packet> output) {
        Objects.requireNonNull(output, "output");
        engine.flush(output);
    }

    /**
     * Makes the next frame given to {@link #encode} an IDR picture, so a receiver that lost packets can resume
     * decoding from it.
     *
     * <p>This method may be called from any thread. A request is never lost: when it arrives while a frame is
     * being given to the encoder, either that frame is an IDR picture or the next frame is. Several requests
     * before the next frame produce one IDR picture. The frames held for B pictures are coded before it.
     */
    @Override
    public void requestKeyframe() {
        engine.requestKeyframe();
    }

    /**
     * Changes the target bitrate from the next frame on, for example when a congestion controller reports a new
     * estimate.
     *
     * <p>This method may be called from any thread, also while another thread encodes a frame; the frame being
     * encoded keeps the previous target, and when several calls arrive before the next frame, the last one wins.
     * The buffering verifier keeps its duration and the maximum bitrate keeps its ratio to the bitrate.
     *
     * @param bitsPerSecond the new target bitrate in bits per second, from 1 to {@link Integer#MAX_VALUE}
     * @throws IllegalArgumentException if {@code bitsPerSecond} is not positive or exceeds
     * {@link Integer#MAX_VALUE}
     * @throws IllegalStateException if the encoder uses {@link HevcRateControl#CONSTANT_QUALITY} or a still
     * picture profile
     */
    @Override
    public void setTargetBitrate(long bitsPerSecond) {
        if (bitsPerSecond <= 0 || bitsPerSecond > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("bitrate must be between 1 and " + Integer.MAX_VALUE + ", got "
                    + bitsPerSecond);
        }
        if (options.rateControl() == HevcRateControl.CONSTANT_QUALITY || options.profile().stillPicture()) {
            throw new IllegalStateException("a constant quality or still picture encoder has no target bitrate");
        }
        engine.setTargetBitrate(bitsPerSecond);
    }

    /**
     * Returns the encoder's counters.
     *
     * <p>This method may be called from any thread; while another thread encodes a frame, the counters may be
     * read in the middle of their update, each being exact for some recent frame.
     *
     * @return a snapshot of the counters
     */
    public HevcEncoderStats stats() {
        return new HevcEncoderStats(engine.frameCount(), engine.keyframeCount(), engine.keyframeRequestCount(),
                engine.byteCount(), engine.lastQp(), engine.targetBitrate());
    }

    /**
     * {@inheritDoc}
     *
     * <p>The encoder cannot be used afterwards; frames still held are dropped unless {@link #flush(Consumer)}
     * was called. It holds no threads between calls, so closing it releases no thread.
     */
    @Override
    public void close() {
        engine.close();
    }
}
