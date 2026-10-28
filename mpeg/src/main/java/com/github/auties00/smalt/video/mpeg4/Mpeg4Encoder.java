package com.github.auties00.smalt.video.mpeg4;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.image.ops.YuvConverter;
import com.github.auties00.smalt.internal.codec.FrameClock;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.internal.task.ParallelBands;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.video.VideoEncoder;
import com.github.auties00.smalt.video.VideoFrame;
import com.github.auties00.smalt.video.mpeg4.internal.Frame;
import com.github.auties00.smalt.video.mpeg4.internal.encoder.Mpeg4EncoderCore;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Encodes pictures into an MPEG-4 Part 2 Visual stream (ISO/IEC 14496-2) of the Simple or Advanced Simple profile,
 * the {@code mp4v} video of MP4 and 3GP files and the DivX and Xvid video of AVI files.
 *
 * <p>The stream is one rectangular, progressive, 8-bit video object layer. It holds I-VOPs, P-VOPs predicted from
 * the reference before them with one or four half-sample (or, in the Advanced Simple profile, quarter-sample)
 * vectors per macroblock, and in the Advanced Simple profile up to {@link Mpeg4EncoderOptions#bFrames()} B-VOPs
 * between references, predicted forward, backward, bidirectionally or in direct mode. Intra macroblocks use DC and
 * AC prediction; blocks use the H.263 quantization method or, in the Advanced Simple profile, the MPEG method with
 * the default or downloaded matrices; I- and P-VOPs may be coded in video packets, data partitioned and with
 * reversible codes for the error resilience of a lossy channel. The first picture, every
 * {@link Mpeg4EncoderOptions#keyframeInterval()}-th picture and the picture after a {@link #requestKeyframe()} are
 * I-VOPs, and the B-VOPs before an I-VOP predict from the references before it, so a decoder can start at every
 * I-VOP.
 *
 * <p>Each packet holds one VOP; the first also starts with the visual object sequence, visual object and video
 * object layer headers that {@link #codecConfig()} returns. Without B-VOPs every frame gives its packet before
 * {@link #encode(VideoFrame, Consumer)} returns and the packets' decoding timestamps equal their presentation
 * timestamps. With B-VOPs the pictures between references are held until the next reference is coded, packets come
 * out in decoding order with decoding timestamps one nominal frame duration before the presentation timestamps of
 * the pictures in presentation order, and {@link #flush(Consumer)} codes the pictures still held, the last as a
 * P-VOP. B-VOP packets carry {@link com.github.auties00.smalt.track.PacketFlag#DISCARDABLE}.
 *
 * <p>Frames may be given in any {@link Picture} representation of exactly the encoder's size: a picture in 8-bit
 * 4:2:0 with the stream's matrix and range is coded as it is, any other is converted to it first. Timestamps are in
 * {@link #timeBase()} units and must increase; a frame without a timestamp is placed one nominal frame after the
 * previous one, and a frame without a duration lasts one nominal frame. The decoded pictures equal those of
 * {@link Mpeg4Decoder}'s decode of the stream, sample for sample.
 *
 * <p>With {@link Mpeg4EncoderOptions#threads()} 0 or above 1 the motion analysis of a picture runs on threads of the
 * library's shared pool together with the calling thread (see
 * {@link com.github.auties00.smalt.MediaRuntime MediaRuntime}); the stream depends only on the picture size, the
 * options and the input, never on the threads.
 *
 * <p>Instances are not thread-safe, except {@link #requestKeyframe()}, which may be called from any thread at any
 * time.
 */
public final class Mpeg4Encoder implements VideoEncoder {
    /**
     * The largest picture side the video object layer header carries.
     */
    private static final int MAX_DIMENSION = 8191;

    private final Mpeg4EncoderOptions options;
    private final int width;
    private final int height;
    private final ColorInfo color;
    private final Mpeg4EncoderCore core;
    private final FrameClock clock;
    private final YuvConverter converter;
    private final AtomicBoolean keyframeRequested = new AtomicBoolean();
    private final ParallelBands.Operation operation = new ParallelBands.Operation();
    private boolean closed;

    /**
     * Constructs an encoder for a picture size with the {@link Mpeg4EncoderOptions#defaults()}.
     *
     * @param width the picture width in pixels, from 1 to 8191
     * @param height the picture height in pixels, from 1 to 8191
     * @throws IllegalArgumentException if a dimension is outside 1 to 8191, the largest the video object layer
     * header carries
     * @throws MediaException.LimitExceeded if the picture exceeds the default limits
     */
    public Mpeg4Encoder(int width, int height) {
        this(width, height, Mpeg4EncoderOptions.defaults());
    }

    /**
     * Constructs an encoder for a picture size.
     *
     * @param width the picture width in pixels, from 1 to 8191
     * @param height the picture height in pixels, from 1 to 8191
     * @param options the encoder settings
     * @throws IllegalArgumentException if a dimension is outside 1 to 8191, the largest the video object layer
     * header carries
     * @throws MediaException.LimitExceeded if the picture exceeds the options' limits
     */
    public Mpeg4Encoder(int width, int height, Mpeg4EncoderOptions options) {
        this.options = Objects.requireNonNull(options, "options");
        if (width < 1 || height < 1 || width > MAX_DIMENSION || height > MAX_DIMENSION) {
            throw new IllegalArgumentException("invalid picture size " + width + "x" + height);
        }
        LimitChecks.checkPicture(options.limits(), width, height);
        this.width = width;
        this.height = height;
        this.color = options.color().orElse(width > 1024 || height > 576 ? ColorInfo.BT709_LIMITED
                : ColorInfo.BT601_LIMITED);
        this.converter = YuvConverter.create(ChromaFormat.YUV420, color);
        this.clock = new FrameClock(options.frameRate(), options.timeBase());
        var threads = options.threads() == 0 ? ParallelBands.THREADS : options.threads();
        this.core = new Mpeg4EncoderCore(new Mpeg4EncoderCore.Settings(width, height,
                options.profile() == Mpeg4Profile.ADVANCED_SIMPLE, options.bFrames(), options.quarterSample(),
                options.mpegQuantization(), options.intraMatrix().orElse(null), options.interMatrix().orElse(null),
                options.fourMotionVectors(), options.acPrediction(), options.videoPacketSize().orElse(0),
                options.dataPartitioning(), options.reversibleVlc(), options.searchRange(), threads, options.bitrate(), options.frameRate(),
                options.fixedQuantizer().orElse(0), options.minQuantizer(), options.maxQuantizer(),
                options.keyframeInterval().orElse(0), options.timeBase(), timeResolution(options), color));
    }

    /**
     * Returns the {@code vop_time_increment_resolution} of a stream's settings.
     *
     * @param options the settings
     * @return the resolution, from 1 to 65535
     */
    private static int timeResolution(Mpeg4EncoderOptions options) {
        var timeBase = options.timeBase().reduce();
        if (timeBase.numerator() == 1 && timeBase.denominator() <= 65535) {
            return (int) timeBase.denominator();
        }
        var rate = options.frameRate().reduce();
        return (int) Math.min(65535, Math.max(1, rate.numerator()));
    }

    /**
     * {@return the options of this encoder}
     */
    public Mpeg4EncoderOptions options() {
        return options;
    }

    @Override
    public Codec codec() {
        return Codec.MPEG4_PART2;
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
     * @return the visual object sequence, visual object and video object layer headers, which the first packet
     * carries too
     */
    @Override
    public Optional<byte[]> codecConfig() {
        return Optional.of(core.headers());
    }

    /**
     * {@inheritDoc}
     *
     * <p>Without B-VOPs exactly one packet is emitted before this method returns. With B-VOPs a picture that is to
     * become a B-VOP is held and nothing is emitted; a reference emits its packet followed by those of the B-VOPs
     * held before it.
     *
     * @throws IllegalArgumentException if the picture size differs from the encoder's or the timestamp does not
     * increase
     * @throws IllegalStateException if the encoder is closed
     */
    @Override
    public void encode(VideoFrame frame, Consumer<Packet> output) {
        Objects.requireNonNull(frame, "frame");
        Objects.requireNonNull(output, "output");
        try (var _ = ParallelBands.operation(operation)) {
            if (closed) {
                throw new IllegalStateException("encoder is closed");
            }
            var picture = frame.picture();
            if (picture.width() != width || picture.height() != height) {
                throw new IllegalArgumentException("picture is " + picture.width() + "x" + picture.height()
                        + ", encoder expects " + width + "x" + height);
            }
            var timing = clock.resolve(frame);
            var yuv = toYuv420(picture);
            core.encode(yuv, timing.pts(), timing.duration(), keyframeRequested.getAndSet(false), output);
            clock.commit(timing.pts());
        }
    }

    /**
     * Returns a picture in the stream's representation, 8-bit 4:2:0 with its matrix and range.
     *
     * @param picture the picture to code
     * @return {@code picture} itself when it is in that representation already, otherwise a converted copy, valid
     * until the next call
     */
    private YuvPicture toYuv420(Picture picture) {
        if (picture instanceof YuvPicture yuv && yuv.chromaFormat() == ChromaFormat.YUV420 && yuv.bitDepth() == 8
                && yuv.color().range() == color.range()
                && yuv.color().matrix().effective() == color.matrix().effective()) {
            return yuv;
        }
        return converter.convert(picture);
    }

    /**
     * {@inheritDoc}
     *
     * <p>The pictures held for B-VOPs are coded, the last as a P-VOP; without B-VOPs nothing is emitted. The encoder
     * stays usable, later pictures continuing the stream.
     *
     * @throws IllegalStateException if the encoder is closed
     */
    @Override
    public void flush(Consumer<Packet> output) {
        Objects.requireNonNull(output, "output");
        try (var _ = ParallelBands.operation(operation)) {
            if (closed) {
                throw new IllegalStateException("encoder is closed");
            }
            core.flush(output);
        }
    }

    /**
     * Makes the next picture passed to {@link #encode(VideoFrame, Consumer)} an I-VOP.
     *
     * <p>This method may be called from any thread, also while another thread encodes a frame: the request is then
     * taken by that frame or by the next one, and is never lost. Pictures held for B-VOPs before it are coded
     * against the references before it.
     */
    @Override
    public void requestKeyframe() {
        keyframeRequested.set(true);
    }

    /**
     * Changes the target bitrate from the next picture on, for example when a congestion controller reports a new
     * estimate.
     *
     * @param bitsPerSecond the new target, in bits per second; positive
     * @throws IllegalArgumentException if {@code bitsPerSecond} is not positive
     * @throws IllegalStateException if the encoder is closed or codes with a fixed quantizer
     */
    @Override
    public void setTargetBitrate(long bitsPerSecond) {
        if (bitsPerSecond <= 0) {
            throw new IllegalArgumentException("bitrate " + bitsPerSecond + " is not positive");
        }
        if (closed) {
            throw new IllegalStateException("encoder is closed");
        }
        if (options.fixedQuantizer().isPresent()) {
            throw new IllegalStateException("the encoder codes with a fixed quantizer");
        }
        core.retarget(bitsPerSecond);
    }

    /**
     * {@return the quantizer of the VOP coded last, from 1 to 31, or 0 before the first}
     */
    public int lastQuantizer() {
        return core.lastQuantizer();
    }

    /**
     * Copies the reconstruction of the VOP coded last as planar 4:2:0 samples of the picture size, for tests.
     *
     * @return the samples, or {@code null} before the first VOP
     */
    byte[] lastReconstruction() {
        return copyPlanes(core.lastReconstruction(), width, height);
    }

    /**
     * Copies the displayed area of a picture's planes.
     *
     * @param frame the planes, or {@code null}
     * @param width the displayed width
     * @param height the displayed height
     * @return the samples, luma then both chroma planes, or {@code null} for no planes
     */
    static byte[] copyPlanes(Frame frame, int width, int height) {
        if (frame == null) {
            return null;
        }
        var chromaWidth = (width + 1) >> 1;
        var chromaHeight = (height + 1) >> 1;
        var out = new byte[width * height + 2 * chromaWidth * chromaHeight];
        var at = 0;
        for (var row = 0; row < height; row++, at += width) {
            System.arraycopy(frame.y, frame.lumaOrigin + row * frame.lumaStride, out, at, width);
        }
        for (var row = 0; row < chromaHeight; row++, at += chromaWidth) {
            System.arraycopy(frame.u, frame.chromaOrigin + row * frame.chromaStride, out, at, chromaWidth);
        }
        for (var row = 0; row < chromaHeight; row++, at += chromaWidth) {
            System.arraycopy(frame.v, frame.chromaOrigin + row * frame.chromaStride, out, at, chromaWidth);
        }
        return out;
    }

    /**
     * {@inheritDoc}
     *
     * <p>The encoder cannot be used afterwards; pictures still held for B-VOPs are dropped. It holds no threads
     * between calls, so closing it releases no thread.
     */
    @Override
    public void close() {
        closed = true;
    }
}
