package com.github.auties00.smalt.video.h264.encoder;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.video.VideoEncoder;
import com.github.auties00.smalt.video.VideoFrame;
import com.github.auties00.smalt.video.h264.encoder.internal.EncoderConfig;
import com.github.auties00.smalt.video.h264.encoder.internal.EncoderCore;
import com.github.auties00.smalt.video.h264.encoder.internal.EncoderEngine;
import com.github.auties00.smalt.video.h264.encoder.internal.WideCore;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Encodes pictures into an H.264 / MPEG-4 AVC elementary stream.
 *
 * <p>The stream has the {@linkplain H264EncoderOptions#chromaFormat() chroma format},
 * {@linkplain H264EncoderOptions#bitDepth() bit depth}, {@linkplain H264EncoderOptions#fieldOrder() field
 * structure}, profile, level and rate control mode of its {@link H264EncoderOptions}, and is
 * {@linkplain H264EncoderOptions#lossless() lossless} when they ask for it. Without a
 * {@linkplain H264EncoderOptions#lookahead() look-ahead} it
 * codes I and P pictures only: every packet's presentation and decoding timestamps are equal and
 * each input frame produces its packet before {@link #encode(VideoFrame, Consumer)} returns, so
 * {@link #flush(Consumer)} never has anything left to emit. With a look-ahead the encoder holds
 * that many frames before it codes the first, picks the quantizer of every macroblock from how
 * much of it the following pictures reuse and, with {@linkplain H264EncoderOptions#bFrames()
 * B pictures}, codes pictures out of presentation order, so packets leave in decoding order with
 * a decoding timestamp before the presentation timestamp and {@link #flush(Consumer)} emits what
 * is still held. No frame is ever dropped. In the {@link H264PacketFormat#LENGTH_PREFIXED} format,
 * packets hold the frame's NAL units, each preceded by its four-byte big-endian length, and
 * parameter sets travel out of band in {@link #codecConfig()}, an
 * {@code AVCDecoderConfigurationRecord} with a four-byte NAL length size. In the
 * {@link H264PacketFormat#ANNEX_B} format, packets are Annex B access units with four-byte start
 * codes, every IDR access unit starts with the sequence and picture parameter sets, and
 * {@link #codecConfig()} is empty:
 * {@snippet :
 * IDR packet: 00 00 00 01 67 <SPS> 00 00 00 01 68 <PPS> 00 00 00 01 65 <IDR slice>
 * P packet:   00 00 00 01 41 <slice>
 * B packet:   00 00 00 01 01 <slice>
 * }
 * The first packet, every packet after {@link #requestKeyframe()}, every
 * {@code keyframeInterval}-th packet and the first packet of a detected scene change are IDR
 * access units flagged as key frames.
 *
 * <p>Frames may be given in any {@link com.github.auties00.smalt.image.Picture}
 * representation of exactly the encoder's size. Y'CbCr samples of the stream's chroma format and
 * depth are coded as they are; other Y'CbCr layouts are resampled to the stream's chroma format
 * (an 8-bit 4:2:0 stream averages chroma down to 4:2:0) and other depths are scaled to the stream's
 * (see {@link H264EncoderOptions#bitDepth()}); RGBA is converted with the configured color matrix
 * and range, ignoring alpha. An interlaced stream takes frames whose rows interleave the two
 * fields. Timestamps are in {@link #timeBase()} units
 * and must increase; a frame whose timestamp is {@link Packet#NO_TIMESTAMP} is placed one nominal
 * frame after the previous one. Gaps between timestamps are accounted for by rate control. In
 * {@link H264EncoderOptions#realtime()} mode a timestamp that does not increase is accepted: the
 * packet is stamped one time base unit after the previous one and rate control treats the frame
 * as lasting one nominal frame.
 *
 * <p>The target bitrate can follow a congestion controller while frames are being encoded (see
 * {@link #setTargetBitrate(long)}), and {@link #stats()} reports the counters a sender needs.
 *
 * <p>Pictures are divided into {@linkplain H264EncoderOptions#slices() slices} as the options say, and
 * the {@linkplain H264EncoderOptions#temporalLayers() temporal layers} and
 * {@linkplain H264EncoderOptions#intraRefresh() intra refresh} options shape the prediction
 * structure. Streams that are not 8-bit, 4:2:0, progressive and lossy are coded with I and P
 * pictures, one motion vector per macroblock and no look-ahead, on the calling thread. For the others,
 * with {@link H264EncoderOptions#threads()} above 1, up to
 * that many helper threads of the module's shared worker pool analyze the macroblock rows of a
 * picture in a wavefront, each row two macroblocks behind the one above, while the calling thread
 * entropy codes the decided macroblocks in order and analyzes any row no helper has taken; the
 * same helpers share the look-ahead analysis, the deblocking filter and the reference
 * interpolation. The encoder starts no threads of its own: it takes only the helpers that
 * concurrent work of the module leaves free, and without any it encodes on the calling thread.
 * The output depends only on the picture size, the options apart from the thread count, and the
 * input: every thread count and every load of the pool produces the same stream.
 *
 * <p>Instances are not thread-safe, except {@link #requestKeyframe()},
 * {@link #setTargetBitrate(long)} and {@link #stats()}, which may be called from any thread, also
 * while another thread encodes a frame.
 *
 * @implNote This implementation's rate control is a complexity model in the manner of MPEG-2 Test
 * Model 5; the normative processes (prediction, transforms, deblocking, entropy coding) are
 * implemented from ITU-T H.264, and the motion search and mode decision follow the ported sources
 * named in the headers of the internal files.
 */
public final class H264Encoder implements VideoEncoder {
    private final H264EncoderOptions options;

    private final EncoderConfig config;

    private final EncoderEngine engine;

    /**
     * Constructs an encoder for a picture size with {@link H264EncoderOptions#defaults()}.
     *
     * <p>Odd sizes are handled as {@link #H264Encoder(int, int, H264EncoderOptions)} describes.
     *
     * @param width the picture width in pixels, from 1 to 16384
     * @param height the picture height in pixels, from 1 to 16384
     * @throws IllegalArgumentException if the size is out of range or exceeds every H.264 level
     * @throws MediaException.LimitExceeded if the picture exceeds the default limits
     */
    public H264Encoder(int width, int height) {
        this(width, height, H264EncoderOptions.defaults());
    }

    /**
     * Constructs an encoder for a picture size.
     *
     * <p>Any positive size is accepted. The coded picture is rounded up to whole 16x16 macroblocks
     * (interlaced pictures to whole macroblock pairs) and frame cropping restores the display size.
     * Cropping works in units of two samples horizontally for 4:2:0 and 4:2:2, and vertically in
     * units of two rows for 4:2:0 and one row otherwise, doubled for interlaced pictures; a side that
     * is not a multiple of its unit is displayed rounded up to the next multiple, the extra columns or
     * rows repeating the last one.
     *
     * @param width the picture width in pixels, from 1 to 16384
     * @param height the picture height in pixels, from 1 to 16384
     * @param options the encoder settings
     * @throws IllegalArgumentException if the size is out of range or exceeds every H.264 level, or
     * the level of the options is too small for the stream
     * @throws MediaException.LimitExceeded if the picture exceeds the options' limits
     */
    public H264Encoder(int width, int height, H264EncoderOptions options) {
        this.options = Objects.requireNonNull(options, "options");
        this.config = EncoderConfig.of(width, height, options);
        this.engine = EncoderEngine.of(config);
    }

    /**
     * {@return the options this encoder was constructed with}
     */
    public H264EncoderOptions options() {
        return options;
    }

    /**
     * Returns the encoding engine of 8-bit 4:2:0 progressive lossy streams; a test hook for this
     * package.
     *
     * @return the engine
     * @throws ClassCastException if the stream is coded by the extended engine
     */
    EncoderCore core() {
        return (EncoderCore) engine;
    }

    /**
     * Returns the encoding engine of the other streams; a test hook for this package.
     *
     * @return the engine
     * @throws ClassCastException if the stream is coded by the 8-bit engine
     */
    WideCore wide() {
        return (WideCore) engine;
    }

    @Override
    public Codec codec() {
        return Codec.H264;
    }

    @Override
    public int width() {
        return config.width();
    }

    @Override
    public int height() {
        return config.height();
    }

    @Override
    public Rational timeBase() {
        return config.timeBase();
    }

    /**
     * {@inheritDoc}
     *
     * @return the {@code AVCDecoderConfigurationRecord} holding the sequence and picture parameter
     * sets, a fresh copy on every call, or empty for {@link H264PacketFormat#ANNEX_B} packets, which
     * carry the parameter sets in band
     */
    @Override
    public Optional<byte[]> codecConfig() {
        if (options.packetFormat() == H264PacketFormat.ANNEX_B) {
            return Optional.empty();
        }
        return Optional.of(engine.configurationRecord());
    }

    /**
     * {@inheritDoc}
     *
     * <p>Without a look-ahead ({@link H264EncoderOptions#lookahead()} 0, and always in
     * {@link H264EncoderOptions#realtime()} mode) the encoder emits exactly one packet per frame,
     * before this method returns. With a look-ahead it emits nothing for the first
     * {@code lookahead} frames and then one packet per frame, and {@link #flush(Consumer)} emits the
     * rest. For an 8-bit stream, a high-bit-depth picture is first reduced to 8 bits with
     * {@link com.github.auties00.smalt.image.ops.ImageOps#toEightBit(com.github.auties00.smalt.image.Picture)
     * ImageOps.toEightBit}.
     *
     * @param frame the next frame in presentation order
     * @param output receives the packets that became ready
     * @throws IllegalArgumentException if the picture size differs from the encoder's or, outside
     * {@link H264EncoderOptions#realtime()} mode, the timestamp does not increase
     * @throws IllegalStateException if the encoder is closed
     */
    @Override
    public void encode(VideoFrame frame, Consumer<Packet> output) {
        Objects.requireNonNull(frame, "frame");
        Objects.requireNonNull(output, "output");
        var packet = engine.encode(frame.picture(), frame.pts(), Math.max(0, frame.duration()));
        if (packet != null) {
            output.accept(packet);
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>Encodes the pictures still held for look-ahead, oldest first. A real-time encoder holds
     * none.
     *
     * @param output receives the packets of the held pictures
     */
    @Override
    public void flush(Consumer<Packet> output) {
        Objects.requireNonNull(output, "output");
        for (var packet = engine.flush(); packet != null; packet = engine.flush()) {
            output.accept(packet);
        }
    }

    /**
     * Makes the next frame given to {@link #encode} an IDR picture, so a receiver that lost
     * packets can resume decoding from it.
     *
     * <p>This method may be called from any thread. A request is never lost: when it arrives
     * while a frame is being given to the encoder, either that frame is an IDR picture or the
     * next frame is. Several requests before the next frame produce one IDR picture. With a
     * look-ahead the IDR picture leaves the encoder once its turn comes, after the pictures
     * that precede it in presentation order.
     */
    @Override
    public void requestKeyframe() {
        engine.requestKeyframe();
    }

    /**
     * Changes the target bitrate from the next frame on, for example when a congestion
     * controller reports a new estimate.
     *
     * <p>This method may be called from any thread, also while another thread encodes a frame;
     * the frame being encoded keeps the previous target, and when several calls arrive before the
     * next frame, the last one wins. With a look-ahead the new target governs every picture coded
     * after the next frame is given to the encoder, which includes pictures of earlier frames
     * still held back. The buffering verifier keeps its duration and relative fullness, and the
     * maximum bitrate keeps its ratio to the bitrate, so the produced rate follows the new target
     * within a few frames, without dropping frames. A target above the maximum bitrate of the
     * stream's level is capped to it.
     *
     * @param bitsPerSecond the new target bitrate in bits per second, from 1 to
     * {@link Integer#MAX_VALUE}
     * @throws IllegalArgumentException if {@code bitsPerSecond} is not positive or exceeds
     * {@link Integer#MAX_VALUE}
     * @throws IllegalStateException if the encoder uses {@link H264RateControl#CONSTANT_QUALITY}
     */
    @Override
    public void setTargetBitrate(long bitsPerSecond) {
        if (bitsPerSecond <= 0 || bitsPerSecond > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("bitrate must be between 1 and " + Integer.MAX_VALUE + ", got "
                    + bitsPerSecond);
        }
        if (options.rateControl() == H264RateControl.CONSTANT_QUALITY) {
            throw new IllegalStateException("a constant quality encoder has no target bitrate");
        }
        engine.setTargetBitrate((int) bitsPerSecond);
    }

    /**
     * Returns the encoder's counters.
     *
     * <p>This method may be called from any thread; while another thread encodes a frame, the
     * counters may be read in the middle of their update, each being exact for some recent
     * frame.
     *
     * @return a snapshot of the counters
     */
    public H264EncoderStats stats() {
        return new H264EncoderStats(engine.frameCount(), engine.keyframeCount(), engine.keyframeRequestCount(),
                engine.byteCount(), engine.lastQp(), engine.targetBitrate(), engine.lastTemporalLayer());
    }

    /**
     * {@inheritDoc}
     *
     * <p>The encoder cannot be used afterwards. It holds no threads between calls, so closing it
     * releases no thread.
     */
    @Override
    public void close() {
        engine.close();
    }
}
