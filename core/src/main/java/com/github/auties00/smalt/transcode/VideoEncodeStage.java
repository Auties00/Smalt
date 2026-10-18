package com.github.auties00.smalt.transcode;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.internal.plane.PictureCopy;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.VideoTrack;
import com.github.auties00.smalt.video.VideoDecoder;
import com.github.auties00.smalt.video.VideoEncoder;
import com.github.auties00.smalt.video.VideoEncoderOptions;
import com.github.auties00.smalt.video.VideoEncoders;
import com.github.auties00.smalt.video.VideoFrame;

/**
 * Decodes one video track (or receives the frames of an animation) and encodes it again.
 *
 * <p>Frames arrive in presentation order with timestamps in the input time base. The stage keeps
 * the frame displayed at the start of the kept range: the last frame at or before the start is
 * copied and held until a later frame shows it is the one on screen at the start, then it is
 * output at the start with its remaining duration. Frames at or after the end are dropped, and
 * the output track declares the kept duration so the MP4 edit list ends exactly there. Frames
 * whose timestamps do not increase are dropped.
 *
 * <p>Kept frames go through the {@link PictureOps} planned from the first frame, then through the
 * frame-rate conversion when one is requested: the output gets one frame per tick of the target
 * rate, each showing the last input frame at or before the tick, so frames are dropped when the
 * input is faster and repeated when it is slower. The encoder is created on the first output
 * frame, when the output size is known, and the output track is declared with its configuration.
 *
 * <p>An instance is not thread-safe.
 */
final class VideoEncodeStage implements Stage {
    /**
     * The frame rate assumed for a track that declares none.
     */
    private static final Rational DEFAULT_FRAME_RATE = new Rational(30, 1);

    /**
     * The bits per pixel and frame of the default bitrate.
     */
    private static final double DEFAULT_BITS_PER_PIXEL = 0.1;

    /**
     * The lowest default bitrate, in bits per second.
     */
    private static final int MIN_DEFAULT_BITRATE = 100_000;

    /**
     * The highest default bitrate, in bits per second.
     */
    private static final int MAX_DEFAULT_BITRATE = 50_000_000;

    private final VideoTrack source;

    /**
     * The decoder of the input track, or {@code null} when frames are supplied directly.
     */
    private final VideoDecoder decoder;

    private final Codec codec;

    private final VideoTranscodeOptions options;

    /**
     * Whether the orientation is applied to the pixels.
     */
    private final boolean applyOrientation;

    /**
     * The constant output rate, or {@code null} to keep the input timing.
     */
    private final Rational frameRate;

    private final int outputId;

    private final OutputMuxer output;

    private final Cancellation cancellation;

    private final Rational timeBase;

    /**
     * The start of the kept range in the input time base.
     */
    private final long start;

    /**
     * The end of the kept range in the input time base, or {@code -1}.
     */
    private final long end;

    /**
     * The nominal duration of one input frame in the input time base, at least {@code 1}.
     */
    private final long nominalDuration;

    /**
     * The operations, planned from the first frame.
     */
    private PictureOps ops;

    /**
     * The encoder, created on the first output frame.
     */
    private VideoEncoder encoder;

    /**
     * The output track, once declared.
     */
    private VideoTrack declared;

    /**
     * A copy of the last frame before the start, or {@code null}.
     */
    private Picture pending;

    /**
     * The last accepted presentation timestamp, or {@link Long#MIN_VALUE}.
     */
    private long lastPts = Long.MIN_VALUE;

    /**
     * The end of the last output frame on the output timeline, in the input time base.
     */
    private long outputEnd;

    private boolean emitted;

    /**
     * The last processed picture, repeated by the frame-rate conversion, or {@code null}.
     */
    private Picture repeat;

    /**
     * The index of the next frame-rate tick to fill.
     */
    private long nextTick;

    /**
     * The last timestamp given to the encoder, or {@link Long#MIN_VALUE}.
     */
    private long lastEncoderPts = Long.MIN_VALUE;

    private boolean done;

    /**
     * Constructs a stage.
     *
     * @param source the input track; for an animation, a track in microseconds
     * @param decoder the decoder of the input track, or {@code null} when frames are supplied
     * through {@link #acceptFrame(VideoFrame)}
     * @param codec the codec to produce
     * @param options the video settings
     * @param applyOrientation whether the orientation is applied to the pixels
     * @param frameRate the constant output rate, or {@code null} to keep the input timing
     * @param outputId the output track identifier
     * @param output the muxer receiving the packets
     * @param startMicros the start of the kept range, in microseconds
     * @param endMicros the end of the kept range in microseconds, or {@code -1} for the end of the track
     * @param cancellation checked between frames
     */
    VideoEncodeStage(VideoTrack source, VideoDecoder decoder, Codec codec, VideoTranscodeOptions options,
                     boolean applyOrientation, Rational frameRate, int outputId, OutputMuxer output, long startMicros,
                     long endMicros, Cancellation cancellation) {
        this.source = source;
        this.decoder = decoder;
        this.codec = codec;
        this.options = options;
        this.applyOrientation = applyOrientation;
        this.frameRate = frameRate;
        this.outputId = outputId;
        this.output = output;
        this.cancellation = cancellation;
        this.timeBase = source.timeBase();
        this.start = Rational.MICROSECONDS.rescale(startMicros, timeBase);
        this.end = endMicros < 0 ? -1 : Rational.MICROSECONDS.rescale(endMicros, timeBase);
        var rate = source.frameRate().orElse(DEFAULT_FRAME_RATE);
        this.nominalDuration = Math.max(1, rate.invert().rescale(1, timeBase));
    }

    @Override
    public int sourceTrackId() {
        return source.id();
    }

    @Override
    public void accept(Packet packet) {
        if (done) {
            return;
        }
        decoder.decode(packet, this::acceptFrame);
    }

    /**
     * Processes one frame in presentation order.
     *
     * <p>The frame's picture is used only during the call.
     *
     * @param frame the frame, with timestamps in the input time base
     */
    void acceptFrame(VideoFrame frame) {
        if (done) {
            return;
        }
        cancellation.check();
        var pts = frame.pts();
        if (pts == Packet.NO_TIMESTAMP) {
            pts = lastPts == Long.MIN_VALUE ? 0 : lastPts + nominalDuration;
        }
        if (lastPts != Long.MIN_VALUE && pts <= lastPts) {
            return;
        }
        lastPts = pts;
        if (end >= 0 && pts >= end) {
            done = true;
            return;
        }
        if (pts < start) {
            pending = PictureCopy.copy(frame.picture(), pending);
            return;
        }
        if (pending != null) {
            if (pts > start) {
                show(pending, start, pts - start);
            }
            pending = null;
        }
        show(frame.picture(), pts, frame.duration() > 0 ? frame.duration() : nominalDuration);
    }

    /**
     * Outputs a kept frame.
     *
     * @param picture the picture, valid during the call
     * @param pts the presentation timestamp in the input time base, at or after the start
     * @param duration the display duration in the input time base
     */
    private void show(Picture picture, long pts, long duration) {
        var time = pts - start;
        if (end >= 0) {
            duration = Math.min(duration, end - pts);
        }
        duration = Math.max(duration, 1);
        if (ops == null) {
            ops = PictureOps.plan(picture.width(), picture.height(), source.orientation(), applyOrientation,
                    options.crop().orElse(null), options::sizePlan, options.filter(), options.background(),
                    options.chromaFormat().orElse(null), options.color().orElse(null));
        }
        var processed = ops.apply(picture);
        if (frameRate == null) {
            encode(processed, time, duration);
        } else {
            fillTicks(time);
            repeat = processed == picture ? PictureCopy.copy(processed, repeat == picture ? null : repeat) : processed;
            if (!emitted) {
                fillTicks(time + 1);
            }
        }
        emitted = true;
        outputEnd = Math.max(outputEnd, time + duration);
    }

    /**
     * Encodes the repeated picture for every frame-rate tick before a time; before the first
     * picture is known, nothing is encoded.
     *
     * @param time the output time in the input time base
     */
    private void fillTicks(long time) {
        if (repeat == null) {
            return;
        }
        var tick = frameRate.invert();
        while (true) {
            var tickTime = tick.rescale(nextTick, timeBase);
            if (tickTime >= time) {
                return;
            }
            var next = tick.rescale(nextTick + 1, timeBase);
            encode(repeat, tickTime, Math.max(1, next - tickTime));
            nextTick++;
        }
    }

    /**
     * Encodes one output picture.
     *
     * @param picture the processed picture
     * @param time the output time in the input time base
     * @param duration the display duration in the input time base
     */
    private void encode(Picture picture, long time, long duration) {
        if (encoder == null) {
            start(picture);
        }
        var encoderTime = encoder.timeBase();
        var pts = timeBase.rescale(time, encoderTime);
        if (lastEncoderPts != Long.MIN_VALUE && pts <= lastEncoderPts) {
            pts = lastEncoderPts + 1;
        }
        lastEncoderPts = pts;
        var length = Math.max(1, timeBase.rescale(duration, encoderTime));
        encoder.encode(new VideoFrame(picture, pts, length, false), this::write);
    }

    /**
     * Creates the encoder for the first output picture and declares the output track.
     *
     * @param picture the first processed picture
     */
    private void start(Picture picture) {
        var width = picture.width();
        var height = picture.height();
        var rate = frameRate != null ? frameRate : source.frameRate().orElse(DEFAULT_FRAME_RATE);
        var bitrate = options.bitrate().orElseGet(() -> defaultBitrate(width, height, rate));
        var encoderOptions = VideoEncoderOptions.of(width, height, rate, bitrate).toBuilder().threads(options.threads())
                .build();
        encoder = VideoEncoders.create(codec, encoderOptions);
        var duration = end >= 0 ? timeBase.rescale(end - start, encoder.timeBase()) : -1;
        declared = VideoTrack.builder()
                .id(outputId)
                .codec(encoder.codec())
                .timeBase(encoder.timeBase())
                .codecConfig(encoder.codecConfig().orElse(null))
                .language(source.language().orElse(null))
                .width(width)
                .height(height)
                .orientation(ops.outputOrientation())
                .frameRate(rate)
                .color(options.color().orElse(null))
                .duration(duration)
                .build();
        output.declare(declared);
    }

    /**
     * {@return the default bitrate of a picture size and rate, in bits per second}
     *
     * @param width the picture width in pixels
     * @param height the picture height in pixels
     * @param rate the frame rate
     */
    private static int defaultBitrate(int width, int height, Rational rate) {
        var bits = DEFAULT_BITS_PER_PIXEL * width * height * rate.toDouble();
        return (int) Math.clamp(Math.round(bits), MIN_DEFAULT_BITRATE, MAX_DEFAULT_BITRATE);
    }

    /**
     * Writes one encoder packet to the output track.
     *
     * @param packet an encoded packet
     */
    private void write(Packet packet) {
        output.write(packet.withTrackId(outputId));
    }

    @Override
    public void finish() {
        if (decoder != null && !done) {
            decoder.flush(this::acceptFrame);
        }
        if (pending != null) {
            var duration = end >= 0 ? end - start : nominalDuration;
            show(pending, start, duration);
            pending = null;
        }
        if (!emitted) {
            throw new MediaException.Malformed("the video track has no decodable frame in the kept range");
        }
        if (frameRate != null) {
            fillTicks(end >= 0 ? end - start : outputEnd);
        }
        encoder.flush(this::write);
        done = true;
    }

    @Override
    public boolean done() {
        return done;
    }

    @Override
    public long positionMicros() {
        return timeBase.toMicros(outputEnd);
    }

    @Override
    public TranscodedStream result() {
        return new TranscodedStream(source.id(), declared, false);
    }

    @Override
    public void close() {
        try {
            if (decoder != null) {
                decoder.close();
            }
        } finally {
            if (encoder != null) {
                encoder.close();
            }
        }
    }
}
