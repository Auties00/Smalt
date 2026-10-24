package com.github.auties00.smalt.transcode;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.container.internal.ConfigChanges;
import com.github.auties00.smalt.container.internal.DiscardedFrames;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.internal.codec.DecodeOrderTimestamps;
import com.github.auties00.smalt.internal.codec.VideoConfigs;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.internal.plane.PictureCopy;
import com.github.auties00.smalt.subtitle.SubtitleCue;
import com.github.auties00.smalt.subtitle.SubtitleDecoder;
import com.github.auties00.smalt.subtitle.SubtitleMarkup;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Interlacing;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.Track;
import com.github.auties00.smalt.track.VideoTrack;
import com.github.auties00.smalt.video.FieldOrder;
import com.github.auties00.smalt.video.VideoDecoder;
import com.github.auties00.smalt.video.VideoDecoderOptions;
import com.github.auties00.smalt.video.VideoDecoders;
import com.github.auties00.smalt.video.VideoEncoder;
import com.github.auties00.smalt.video.VideoEncoderOptions;
import com.github.auties00.smalt.video.VideoEncoders;
import com.github.auties00.smalt.video.VideoFrame;

import java.util.List;

/**
 * Decodes one video track (or receives the frames of an animation) and encodes it again.
 *
 * <p>Frames arrive in presentation order with timestamps in the input time base. The stage keeps
 * the frame displayed at the start of the kept range: the last frame at or before the start is
 * copied and held until a later frame shows it is the one on screen at the start, then it is
 * output at the start with its remaining duration. Frames at or after the end are dropped, and
 * the output track declares the kept duration so the MP4 edit list ends exactly there. Frames
 * whose timestamps do not increase are dropped, and so are the frames of packets flagged
 * {@link com.github.auties00.smalt.track.PacketFlag#DISCARD}. A packet that changes the codec
 * configuration drains the decoder and continues with one created from the new configuration.
 *
 * <p>Kept frames then go through the steps the options ask for, in this order: inverse telecine
 * ({@link FieldMatcher}), deinterlacing ({@link DeinterlaceStep}), the {@link PictureOps} planned
 * from the first frame (with the sample aspect ratio the track declares or its configuration
 * signals) and the {@link VideoColor} conversion, the constant-rate conversion ({@link RateStep}),
 * the overlays ({@link OverlayStep}) and telecine ({@link TelecineStep}). The scan of a frame is the
 * field order its decoder reports, else the one the options assume, else the one the track declares.
 * The encoder is created on the first output frame, when the output size and color are known, and
 * the output track is declared with its configuration.
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
     * The decoder of the input track, or {@code null} when frames are supplied directly; replaced
     * when a packet changes the codec configuration.
     */
    private VideoDecoder decoder;

    /**
     * The options {@link #decoder} was created with, or {@code null} when frames are supplied
     * directly.
     */
    private final VideoDecoderOptions decoderOptions;

    /**
     * The codec configuration of {@link #decoder}, or {@code null} when it has none.
     */
    private byte[] config;

    /**
     * The timestamps of the discarded packets whose frames have not been dropped yet.
     */
    private final DiscardedFrames discarded = new DiscardedFrames();

    /**
     * The presentation timestamps restored for decoding-order stamped packets, or {@code null} when
     * the codec does not reorder frames.
     */
    private final DecodeOrderTimestamps reordered;

    private final Codec codec;

    private final VideoTranscodeOptions options;

    /**
     * Whether the orientation is applied to the pixels.
     */
    private final boolean applyOrientation;

    /**
     * Whether non-square samples are resampled to square ones.
     */
    private final boolean applyAspect;

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
     * The cues burned in, known before the first frame, or {@code null} without burned-in subtitles.
     */
    private final List<SubtitleCue> burnCues;

    private final SubtitleMarkup burnMarkup;

    /**
     * The decoder of the subtitle packets delivered with the input, or {@code null}.
     */
    private final SubtitleDecoder burnDecoder;

    /**
     * The first step of the processing, built on the first kept frame.
     */
    private FrameSink head;

    /**
     * The overlay step, or {@code null} without overlays.
     */
    private OverlayStep overlays;

    /**
     * The step of the geometric operations and color conversion, built with {@link #head}.
     */
    private PictureStep pictureStep;

    /**
     * The rate the encoder and the output track declare, set when the steps are built.
     */
    private Rational outputRate;

    /**
     * The scan the output track declares, or {@code null} to declare none.
     */
    private Interlacing outputScan;

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
     * The scan of {@link #pending}.
     */
    private FieldOrder pendingScan = FieldOrder.PROGRESSIVE;

    /**
     * The last accepted presentation timestamp, or {@link Long#MIN_VALUE}.
     */
    private long lastPts = Long.MIN_VALUE;

    /**
     * The end of the last kept frame on the output timeline, in the input time base.
     */
    private long outputEnd;

    private boolean emitted;

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
     * @param decoderOptions the options {@code decoder} was created with, or {@code null} without a
     * decoder
     * @param codec the codec to produce
     * @param options the video settings
     * @param applyOrientation whether the orientation is applied to the pixels
     * @param applyAspect whether non-square samples are resampled to square ones rather than their
     * ratio kept
     * @param frameRate the constant output rate, or {@code null} to keep the input timing
     * @param outputId the output track identifier
     * @param output the muxer receiving the packets
     * @param startMicros the start of the kept range, in microseconds
     * @param endMicros the end of the kept range in microseconds, or {@code -1} for the end of the track
     * @param cancellation checked between frames
     * @param burnCues the cues burned in that are known before the transcode, or {@code null} without
     * burned-in subtitles
     * @param burnMarkup the markup of the burned-in cues
     * @param burnTrack the subtitle track whose packets {@link #acceptSubtitle(Packet)} receives, or
     * {@code null}
     */
    VideoEncodeStage(VideoTrack source, VideoDecoder decoder, VideoDecoderOptions decoderOptions, Codec codec,
                     VideoTranscodeOptions options, boolean applyOrientation, boolean applyAspect, Rational frameRate,
                     int outputId, OutputMuxer output, long startMicros, long endMicros, Cancellation cancellation,
                     List<SubtitleCue> burnCues, SubtitleMarkup burnMarkup, Track burnTrack) {
        this.source = source;
        this.decoder = decoder;
        this.decoderOptions = decoderOptions;
        this.config = source.codecConfig().orElse(null);
        this.codec = codec;
        this.options = options;
        this.applyOrientation = applyOrientation;
        this.applyAspect = applyAspect;
        this.frameRate = frameRate;
        this.outputId = outputId;
        this.output = output;
        this.cancellation = cancellation;
        this.timeBase = source.timeBase();
        this.start = Rational.MICROSECONDS.rescale(startMicros, timeBase);
        this.end = endMicros < 0 ? -1 : Rational.MICROSECONDS.rescale(endMicros, timeBase);
        var rate = source.frameRate().orElse(DEFAULT_FRAME_RATE);
        this.nominalDuration = Math.max(1, rate.invert().rescale(1, timeBase));
        this.reordered = DecodeOrderTimestamps.of(source.codec());
        this.burnCues = burnCues;
        this.burnMarkup = burnMarkup;
        this.burnDecoder = burnTrack == null ? null : new SubtitleDecoder(burnTrack);
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
        var newConfig = ConfigChanges.changed(packet, config);
        if (newConfig != null) {
            decoder.flush(this::acceptFrame);
            var replacement = VideoDecoders.create(source.toBuilder().codecConfig(newConfig).build(), decoderOptions);
            decoder.close();
            decoder = replacement;
            config = newConfig;
        }
        discarded.note(packet);
        if (reordered != null) {
            reordered.note(packet);
        }
        decoder.decode(packet, this::acceptFrame);
    }

    /**
     * Receives a packet of the subtitle track burned in, for an input whose cues could not be read
     * before the transcode.
     *
     * @param packet a packet of the track given at construction
     */
    void acceptSubtitle(Packet packet) {
        if (burnDecoder == null || done) {
            return;
        }
        var cue = burnDecoder.decode(packet);
        if (overlays != null) {
            overlays.addCue(cue);
        } else {
            burnCues.add(cue);
        }
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
        var pts = reordered != null ? reordered.presentation(frame.pts()) : frame.pts();
        if (pts != Packet.NO_TIMESTAMP && discarded.drop(pts)) {
            return;
        }
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
        var scan = scanOf(frame);
        if (pts < start) {
            pending = PictureCopy.copy(frame.picture(), pending);
            pendingScan = scan;
            return;
        }
        if (pending != null) {
            if (pts > start) {
                show(pending, start, pts - start, pendingScan);
            }
            pending = null;
        }
        show(frame.picture(), pts, frame.duration() > 0 ? frame.duration() : nominalDuration, scan);
    }

    /**
     * Returns the scan of a frame: the field order its decoder reports, else the one the options
     * assume, else the one the track declares, else progressive.
     *
     * @param frame the decoded frame
     * @return the scan
     */
    private FieldOrder scanOf(VideoFrame frame) {
        var reported = frame.sideData().fieldOrder();
        if (reported.isPresent()) {
            return reported.get();
        }
        if (options.fieldOrder().isPresent()) {
            return options.fieldOrder().get();
        }
        var declared = source.interlacing().orElse(Interlacing.PROGRESSIVE);
        if (!declared.isInterlaced()) {
            return FieldOrder.PROGRESSIVE;
        }
        return declared == Interlacing.BOTTOM_FIELD_FIRST || declared == Interlacing.BOTTOM_FIELD_FIRST_SEPARATED
                ? FieldOrder.BOTTOM_FIRST : FieldOrder.TOP_FIRST;
    }

    /**
     * Outputs a kept frame.
     *
     * @param picture the picture, valid during the call
     * @param pts the presentation timestamp in the input time base, at or after the start
     * @param duration the display duration in the input time base
     * @param scan the scan of the frame
     */
    private void show(Picture picture, long pts, long duration, FieldOrder scan) {
        var time = pts - start;
        if (end >= 0) {
            duration = Math.min(duration, end - pts);
        }
        duration = Math.max(duration, 1);
        if (head == null) {
            build(scan);
        }
        head.accept(picture, time, duration, scan);
        emitted = true;
        outputEnd = Math.max(outputEnd, time + duration);
    }

    /**
     * Builds the processing steps for a stream whose first kept frame has a scan.
     *
     * @param scan the scan of the first kept frame
     */
    private void build(FieldOrder scan) {
        var sourceRate = source.frameRate().orElse(DEFAULT_FRAME_RATE);
        var pulldown = options.pulldown();
        var inverse = pulldown == Pulldown.INVERSE_TELECINE;
        var deinterlacer = options.deinterlacer().orElse(null);
        var rate = inverse ? scale(sourceRate, 4, 5) : sourceRate;
        var doubling = !inverse && deinterlacer != null && options.fieldRate() && scan.isInterlaced();
        if (doubling) {
            rate = scale(rate, 2, 1);
        }
        var constant = frameRate;
        if (constant == null && pulldown == Pulldown.TELECINE) {
            constant = rate;
        }
        var encodedRate = constant != null ? constant : rate;
        outputRate = pulldown == Pulldown.TELECINE ? scale(encodedRate, 5, 4) : encodedRate;
        if (pulldown == Pulldown.TELECINE) {
            outputScan = Interlacing.TOP_FIELD_FIRST;
        } else if (deinterlacer != null || inverse) {
            outputScan = Interlacing.PROGRESSIVE;
        } else {
            outputScan = source.interlacing().orElse(null);
        }
        FrameSink sink = new EncodeSink();
        if (pulldown == Pulldown.TELECINE) {
            sink = new TelecineStep(sink, encodedRate, timeBase);
        }
        var burnIn = options.subtitleBurnIn().orElse(null);
        if (burnIn != null || options.watermarkPicture() != null) {
            overlays = new OverlayStep(sink, burnIn == null ? null : burnCues, burnMarkup,
                    burnIn == null ? null : burnIn.style(), options.watermarkPicture(), options.watermarkOptions(),
                    options.colorConversion(), timeBase, start);
            sink = overlays;
        }
        if (constant != null) {
            sink = new RateStep(sink, constant, timeBase, options.frameRateConversion());
        }
        pictureStep = new PictureStep(sink);
        sink = pictureStep;
        if (deinterlacer != null) {
            sink = new DeinterlaceStep(sink, deinterlacer, doubling);
        }
        if (inverse) {
            sink = new FieldMatcher(sink, sourceRate, timeBase);
        }
        head = sink;
    }

    /**
     * {@return a rate scaled by a fraction, in lowest terms}
     *
     * @param rate the rate to scale
     * @param numerator the numerator of the factor, positive
     * @param denominator the denominator of the factor, positive
     */
    private static Rational scale(Rational rate, long numerator, long denominator) {
        var top = Math.multiplyExact(rate.numerator(), numerator);
        var bottom = Math.multiplyExact(rate.denominator(), denominator);
        var gcd = gcd(top, bottom);
        return new Rational(top / gcd, bottom / gcd);
    }

    /**
     * {@return the greatest common divisor of two positive numbers}
     *
     * @param a the first number
     * @param b the second number
     */
    private static long gcd(long a, long b) {
        while (b != 0) {
            var t = a % b;
            a = b;
            b = t;
        }
        return a;
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
            show(pending, start, duration, pendingScan);
            pending = null;
        }
        if (!emitted) {
            throw new MediaException.Malformed("the video track has no decodable frame in the kept range");
        }
        head.finish(end >= 0 ? end - start : outputEnd);
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

    /**
     * Applies the geometric operations and the color conversion.
     */
    private final class PictureStep implements FrameSink {
        private final FrameSink next;

        /**
         * The operations, planned from the first frame.
         */
        private PictureOps ops;

        private VideoColor color;

        PictureStep(FrameSink next) {
            this.next = next;
        }

        /**
         * {@inheritDoc}
         *
         * @throws MediaException.LimitExceeded if a picture resampled to square samples exceeds the
         * decoder limits, as a sample aspect ratio far from square can make it
         */
        @Override
        public void accept(Picture picture, long time, long duration, FieldOrder scan) {
            if (ops == null) {
                var aspect = VideoConfigs.sampleAspect(source);
                ops = PictureOps.plan(picture.width(), picture.height(), source.orientation(), applyOrientation,
                        options.crop().orElse(null), options::sizePlan, options.filter(), options.background(),
                        null, null, aspect, applyAspect);
                if (applyAspect && aspect != null && aspect.numerator() != aspect.denominator()) {
                    LimitChecks.checkPicture(decoderOptions != null ? decoderOptions.limits() : MediaLimits.DEFAULT,
                            ops.resampledWidth(), ops.resampledHeight());
                }
                color = new VideoColor(options.color().orElse(null), options.chromaFormat().orElse(null),
                        options.bitDepth().orElse(0), options.colorConversion(), source.color().orElse(null));
            }
            next.accept(color.apply(ops.apply(picture)), time, duration, scan);
        }

        @Override
        public void finish(long end) {
            next.finish(end);
        }
    }

    /**
     * Encodes the output frames, creating the encoder on the first.
     */
    private final class EncodeSink implements FrameSink {
        @Override
        public void accept(Picture picture, long time, long duration, FieldOrder scan) {
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
            encoder.encode(new VideoFrame(picture, pts, length, false), VideoEncodeStage.this::write);
        }

        /**
         * Creates the encoder for the first output picture and declares the output track.
         *
         * @param picture the first output picture
         */
        private void start(Picture picture) {
            var width = picture.width();
            var height = picture.height();
            ColorInfo pictureColor = null;
            var depth = 8;
            if (picture instanceof YuvPicture yuv) {
                pictureColor = yuv.color();
            } else if (picture instanceof Yuv16Picture yuv) {
                pictureColor = yuv.color();
                depth = yuv.bitDepth();
            }
            var bitrate = options.bitrate().orElseGet(() -> defaultBitrate(width, height, outputRate));
            var encoderBuilder = VideoEncoderOptions.of(width, height, outputRate, bitrate).toBuilder()
                    .threads(options.threads())
                    .bitDepth(depth);
            if (pictureColor != null) {
                encoderBuilder.color(pictureColor);
            }
            if (!options.periodicKeyframes()) {
                encoderBuilder.noPeriodicKeyframes();
            } else {
                options.keyframeInterval().ifPresent(encoderBuilder::keyframeInterval);
            }
            encoder = VideoEncoders.create(codec, encoderBuilder.build());
            var duration = end >= 0 ? timeBase.rescale(end - start, encoder.timeBase()) : -1;
            var declaredColor = pictureColor != null && (options.color().isPresent() || source.color().isPresent()
                    || pictureColor.transfer().isHdr()) ? pictureColor : null;
            var builder = VideoTrack.builder()
                    .id(outputId)
                    .codec(encoder.codec())
                    .timeBase(encoder.timeBase())
                    .codecConfig(encoder.codecConfig().orElse(null))
                    .language(source.language().orElse(null))
                    .width(width)
                    .height(height)
                    .orientation(pictureStep.ops.outputOrientation())
                    .frameRate(outputRate)
                    .color(declaredColor)
                    .duration(duration);
            if (outputScan != null) {
                builder.interlacing(outputScan);
            }
            var aspect = pictureStep.ops.outputSampleAspect();
            if (aspect != null) {
                // TODO: hand the ratio to encoders that signal one in band (the H.264 VUI writes 1:1) once
                //  VideoEncoderOptions carries it, so that a later remux into a container without the field keeps it
                builder.sampleAspectRatio(aspect);
            }
            declared = builder.build();
            output.declare(declared);
        }

        @Override
        public void finish(long end) {
            encoder.flush(VideoEncodeStage.this::write);
        }
    }
}
