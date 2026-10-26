/* Ports libaom 3.12.1 (av1/encoder: the one-pass real-time path, the good-quality frame structure and the
   all-intra mode). Copyright (c) 2016, Alliance for Open Media; BSD 2-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.video.av1;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaRuntime;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.image.avif.internal.EncodeImage;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.image.ops.YuvConverter;
import com.github.auties00.smalt.internal.codec.FrameClock;
import com.github.auties00.smalt.internal.codec.RealtimeEncoderClock;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.internal.task.ParallelBands;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.video.VideoEncoder;
import com.github.auties00.smalt.video.VideoFrame;
import com.github.auties00.smalt.video.av1.internal.DequantTables;
import com.github.auties00.smalt.video.av1.internal.Obu;
import com.github.auties00.smalt.video.av1.internal.SequenceHeader;
import com.github.auties00.smalt.video.av1.internal.encoder.Av1Engine;
import com.github.auties00.smalt.video.av1.internal.encoder.Av1GoodEncoder;
import com.github.auties00.smalt.video.av1.internal.encoder.Av1RtEncoder;
import com.github.auties00.smalt.video.av1.internal.encoder.FilmGrainEstimator;
import com.github.auties00.smalt.video.av1.internal.encoder.FilmGrains;
import com.github.auties00.smalt.video.av1.internal.encoder.StreamFormat;
import com.github.auties00.smalt.video.av1.internal.encoder.StreamTiming;
import com.github.auties00.smalt.video.av1.internal.encoder.StillFrameEncoder;
import com.github.auties00.smalt.video.av1.internal.encoder.StillSettings;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Encodes pictures to an AV1 stream: low-delay at a constant bitrate for real-time
 * communication and live streaming, with a lookahead at a constant quality or variable bitrate for
 * files and on-demand video, or intra-only at a constant quality, as
 * {@link Av1EncoderOptions#usage()} selects.
 *
 * <p><b>Real time</b> ({@link Av1EncoderUsage#REALTIME}, {@link Av1EncoderOptions#forRealtime()}).
 * The encoder codes frames without reordering: every picture yields at most one packet, immediately,
 * holding one temporal unit (a temporal delimiter, the sequence header on key frames, and one frame)
 * in the low-overhead bitstream format that MP4, Matroska, IVF and the AV1 RTP payload format
 * carry. The first frame is a key frame, as are frames following a call to
 * {@link #requestKeyframe()}, periodic ones when {@link Av1EncoderOptions#keyframeInterval()} is
 * set, and the first frame after a size change through {@link #reconfigure(int, int)} that the
 * references cannot predict. When frame dropping is enabled the rate control may
 * drop a frame whose buffer is empty, or a coded frame so large that it would empty the buffer;
 * {@link #encodeFrame} then returns an empty result and {@link #encode} produces no packet. With
 * {@link Av1EncoderOptions#maxKeyframeBitratePercent()} set, a key frame's size target is capped
 * and a key frame that overshoots it by more than half is coded again at a coarser quantizer.
 * With {@link Av1EncoderOptions#temporalLayers()} above one, each frame belongs to a temporal layer
 * that its OBU extension headers and {@link Av1EncoderStats#lastTemporalLayer()} report, and the
 * sequence header lists one operating point per layer subset.
 *
 * <p><b>Good quality</b> ({@link Av1EncoderUsage#GOOD_QUALITY}, {@link Av1EncoderOptions#ofQuality(int)},
 * {@link Av1EncoderOptions#ofBitrate(long)}, {@link Av1EncoderOptions#forArchive()}). Pictures
 * are copied into a lookahead of {@link Av1EncoderOptions#lookahead()} frames and coded a frame
 * group at a time: {@link #encode} produces no packet until the lookahead fills, then the packets
 * of a whole group, and {@link #flush} the rest. Each group of up to 16 frames is coded as a
 * pyramid: its last frame first, temporally filtered and hidden, then the frames before it,
 * predicting from up to seven earlier and later frames and from averages of a past and a future
 * one; every packet still holds exactly one shown frame, in display order, with the hidden frames
 * it depends on, so packets need no reordering. Key frames are coded with the still image search,
 * at the first picture, on request, at the key frame interval and at detected scene cuts. With a
 * {@link Av1EncoderOptions#quality()} the quantizers follow a constant quality, finer for the
 * frames the group predicts from most; without one, each group's quantizers are scaled to spend
 * {@link Av1EncoderOptions#bitrate()}. Frames are never dropped. {@link #encodeFrame} and
 * {@link #reconfigure(int, int)} are not available in this usage.
 *
 * <p><b>Intra only</b> ({@link Av1EncoderUsage#ALL_INTRA}, {@link Av1EncoderOptions#forIntraOnly(int)}).
 * Every frame is a key frame coded at the constant quality with the still image search the AVIF
 * encoder uses, and frames are never dropped.
 *
 * <p>Every usage codes the options' picture format: 4:2:0, 4:2:2, 4:4:4 or monochrome
 * ({@link Av1EncoderOptions#chromaFormat()}), at 8, 10 or 12 bits ({@link Av1EncoderOptions#bitDepth()}),
 * in the main, high or professional profile. In the real-time and good-quality usages, a picture is
 * coded as it is when it is a {@link YuvPicture} (at 8 bits) or a {@link Yuv16Picture} of the
 * options' depth (above), in the options' chroma format and with the matrix and range of
 * {@link Av1EncoderOptions#color()}, and is converted to that format, depth and color first
 * otherwise; the intra-only usage converts pictures the same way. The stream signals the options'
 * color.
 *
 * <p>An encoder is used by one thread at a time, except {@link #requestKeyframe()}, which any
 * thread may call at any time; a request is never lost. Packet timestamps are the frames'
 * timestamps; frames without a timestamp are numbered at the nominal frame rate in the configured
 * time base.
 *
 * @implNote This implementation reconstructs every frame with the module's AV1 decoder kernels,
 * so its references are exactly what any decoder reconstructs. With several threads, the
 * superblock rows of inter frames run in a wavefront, tile columns run in parallel and CDEF
 * filters rows in parallel; the stream does not depend on the thread count.
 */
public final class Av1Encoder implements VideoEncoder {
    /**
     * The most threads an automatic thread count ({@link Av1EncoderOptions#threads()} 0) takes.
     */
    private static final int MAX_AUTOMATIC_THREADS = 64;

    /**
     * The current options.
     */
    private Av1EncoderOptions options;

    /**
     * The picture width.
     */
    private int width;

    /**
     * The picture height.
     */
    private int height;

    /**
     * The coding engine of the usage: the real-time loop, the good-quality loop or the intra-only
     * frame encoder.
     */
    private Av1Engine engine;

    /**
     * The pool scope of the encoder's work.
     */
    private final ParallelBands.Operation operation = new ParallelBands.Operation();

    /**
     * The timestamps, the key frame request and the counters of the coded frames.
     */
    private final RealtimeEncoderClock clock;

    /**
     * The converter of the real-time usage's pictures, owning their converted copies; the
     * good-quality lookahead keeps its pictures, so its conversions are new pictures.
     */
    private final YuvConverter converter;

    /**
     * Whether {@link #close()} was called.
     */
    private boolean closed;

    /**
     * The film grain seed of the next intra-only frame, or -1 before the first.
     */
    private int intraGrainSeed = -1;

    /**
     * The denoiser and grain estimator of {@link Av1EncoderOptions#denoiseLevel()}, or {@code null}.
     */
    private FilmGrainEstimator estimator;

    /**
     * The film grain the last {@link #prepare} estimated or looked up, or {@code null}.
     */
    private Av1FilmGrain preparedGrain;

    /**
     * Converts a picture to the coded format and, with denoising, denoises it, leaving its film
     * grain in {@link #preparedGrain}.
     *
     * @param picture the source picture, in any format the encoder accepts
     * @param converter the converter of an 8-bit conversion, or {@code null} to allocate
     * @param pts the frame's timestamp, for the film grain table
     * @return the picture to code
     */
    private Picture prepare(Picture picture, YuvConverter converter, long pts) {
        var coded = toCoded(picture, converter);
        if (options.denoiseLevel().isEmpty()) {
            preparedGrain = tableGrain(pts);
            return coded;
        }
        if (estimator == null) {
            estimator = new FilmGrainEstimator(options.chromaFormat(), options.bitDepth(),
                    options.denoiseLevel().getAsInt());
        }
        var result = estimator.run(coded, threads(options));
        preparedGrain = result.grain();
        return result.picture();
    }

    /**
     * Constructs an encoder for a picture size.
     *
     * @param width the picture width in pixels, positive
     * @param height the picture height in pixels, positive
     * @param options the encoder settings
     * @throws IllegalArgumentException if a dimension is not positive, the grain of an entry of the
     * {@linkplain Av1EncoderOptions#filmGrain() film grain table} cannot be coded in the picture
     * format (in 4:2:0, one chroma plane has scaling points and the other none, or there are chroma
     * points but no luma points), or the {@linkplain Av1EncoderOptions#firstPassStatistics() first
     * pass statistics} measured pictures of another size
     * @throws MediaException.LimitExceeded if the picture exceeds the options' limits
     */
    public Av1Encoder(int width, int height, Av1EncoderOptions options) {
        this.options = Objects.requireNonNull(options, "options");
        options.filmGrain().ifPresent(table -> table.entries().forEach(entry -> entry.grain()
                .ifPresent(grain -> StreamFormat.checkFilmGrain(grain, options.chromaFormat()))));
        this.engine = switch (options.usage()) {
            case ALL_INTRA -> newStill(width, height, options);
            case GOOD_QUALITY -> newGood(width, height, options);
            case REALTIME -> newRealtime(width, height, options);
        };
        this.width = width;
        this.height = height;
        this.clock = new RealtimeEncoderClock(options.frameRate(), options.timeBase());
        this.converter = YuvConverter.create(options.chromaFormat(), options.color());
        apply(options);
    }

    /**
     * Constructs an encoder for a picture size with the {@link Av1EncoderOptions#defaults()}.
     *
     * @param width the picture width in pixels, positive
     * @param height the picture height in pixels, positive
     * @throws IllegalArgumentException if a dimension is not positive
     * @throws MediaException.LimitExceeded if the picture exceeds the default limits
     */
    public Av1Encoder(int width, int height) {
        this(width, height, Av1EncoderOptions.defaults());
    }

    /**
     * Creates the intra-only frame encoder of a picture size.
     *
     * @param width the picture width
     * @param height the picture height
     * @param options the options, with a quality
     * @return the encoder
     */
    private static StillFrameEncoder newStill(int width, int height, Av1EncoderOptions options) {
        checkSize(width, height, options);
        var settings = StillSettings.of(DequantTables.qualityToQindex(options.quality().orElseThrow()),
                options.speed().level(Av1EncoderUsage.ALL_INTRA)).tiles(options.tileColumnsLog2(), 0)
                .threads(threads(options)).filmGrain(signalsGrain(options))
                .screenContent(options.screenContent() == Av1ScreenContent.ALWAYS)
                .detectScreenContent(options.screenContent() == Av1ScreenContent.DETECT);
        return new StillFrameEncoder(width, height, SequenceHeader.layoutOf(options.chromaFormat()),
                options.bitDepth(), options.color(), settings, options.limits());
    }

    /**
     * Creates the good-quality encoding loop of a picture size.
     *
     * @param width the picture width
     * @param height the picture height
     * @param options the encoder settings
     * @return the loop
     */
    private static Av1GoodEncoder newGood(int width, int height, Av1EncoderOptions options) {
        checkSize(width, height, options);
        var good = new Av1GoodEncoder(width, height, streamFormat(options), options.limits());
        var statistics = options.firstPassStatistics().orElse(null);
        if (statistics != null) {
            if (statistics.width() != width || statistics.height() != height) {
                throw new IllegalArgumentException("first pass statistics of " + statistics.width() + "x"
                        + statistics.height() + " pictures for an encoder of " + width + "x" + height);
            }
            var frames = statistics.frameCount();
            var totalBits = (long) (options.bitrate() * (frames / options.frameRate().toDouble()));
            good.twoPass(statistics.values(), frames, totalBits);
        }
        return good;
    }

    /**
     * Creates the real-time encoding loop of a picture size.
     *
     * @param width the picture width
     * @param height the picture height
     * @param options the encoder settings
     * @return the loop
     */
    private static Av1RtEncoder newRealtime(int width, int height, Av1EncoderOptions options) {
        checkSize(width, height, options);
        return new Av1RtEncoder(width, height, streamFormat(options), options.temporalLayers(), options.limits());
    }

    /**
     * Returns the stream format of the options' predicted usages.
     *
     * @param options the encoder settings
     * @return the format
     */
    private static StreamFormat streamFormat(Av1EncoderOptions options) {
        return new StreamFormat(options.chromaFormat(), options.bitDepth(), options.color(), signalsGrain(options),
                options.screenContent(), new StreamTiming(options.timingInfo(), options.timeBase(), options.frameRate()),
                options.superResolution().orElse(8));
    }

    /**
     * Returns whether the options' stream signals film grain.
     *
     * @param options the encoder settings
     * @return {@code true} with a film grain table or denoising
     */
    private static boolean signalsGrain(Av1EncoderOptions options) {
        return options.filmGrain().isPresent() || options.denoiseLevel().isPresent();
    }

    /**
     * Returns the film grain of a frame from the options' table.
     *
     * @param pts the frame's timestamp in the time base
     * @return the grain, or {@code null} for none
     */
    private Av1FilmGrain tableGrain(long pts) {
        var table = options.filmGrain().orElse(null);
        if (table == null) {
            return null;
        }
        var base = options.timeBase();
        var units = Math.multiplyExact(pts, (long) base.numerator());
        var seconds = Math.floorDiv(units, base.denominator());
        var nanos = Math.floorMod(units, (long) base.denominator()) * 1_000_000_000L / base.denominator();
        return seconds < 0 ? null : table.lookup(Duration.ofSeconds(seconds, nanos)).orElse(null);
    }

    /**
     * Checks a picture size against the options' limits.
     *
     * @param width the picture width
     * @param height the picture height
     * @param options the encoder settings
     * @throws IllegalArgumentException if a dimension is not positive
     * @throws MediaException.LimitExceeded if the picture exceeds the limits
     */
    private static void checkSize(int width, int height, Av1EncoderOptions options) {
        if (width < 1 || height < 1) {
            throw new IllegalArgumentException("invalid picture size " + width + "x" + height);
        }
        LimitChecks.checkPicture(options.limits(), width, height);
    }

    /**
     * Passes the options' rate control and coding settings to the encoding loop.
     *
     * @param options the settings to apply
     */
    private void apply(Av1EncoderOptions options) {
        clock.clock().setFrameRate(options.frameRate());
        var minQindex = DequantTables.quantizerToQindex(options.minQuantizer());
        var maxQindex = DequantTables.quantizerToQindex(options.maxQuantizer());
        switch (engine) {
            case Av1GoodEncoder good -> good.configure(new Av1GoodEncoder.Settings(
                    options.speed().level(Av1EncoderUsage.GOOD_QUALITY),
                    options.quality().isPresent() ? DequantTables.qualityToQindex(options.quality().getAsInt()) : -1,
                    options.bitrate(), options.frameRate().toDouble(), minQindex, maxQindex,
                    options.keyframeInterval().orElse(0), options.lookahead(), options.tileColumnsLog2(),
                    threads(options)));
            case Av1RtEncoder realtime -> realtime.configure(new Av1RtEncoder.Settings(options.bitrate(),
                    options.frameRate().toDouble(), minQindex, maxQindex, options.keyframeInterval().orElse(0),
                    options.speed().level(), options.errorResilient(), options.dropFrames() ? 30 : 0,
                    (int) options.bufferSize().toMillis(), (int) options.initialBufferLevel().toMillis(),
                    (int) options.optimalBufferLevel().toMillis(), options.tileColumnsLog2(), threads(options),
                    options.adaptiveQuantization(), options.maxKeyframeBitratePercent().orElse(0)));
            case StillFrameEncoder _ -> {
            }
        }
    }

    /**
     * Returns the number of threads the options ask for, with the automatic count resolved.
     *
     * @param options the encoder settings
     * @return the thread count, at least 1
     */
    private static int threads(Av1EncoderOptions options) {
        return options.threads() == 0 ? Math.min(MAX_AUTOMATIC_THREADS, MediaRuntime.parallelism()) : options.threads();
    }

    /**
     * {@return the current options, as last set by the constructor or {@link #reconfigure(Av1EncoderOptions)}}
     */
    public Av1EncoderOptions options() {
        return options;
    }

    @Override
    public Codec codec() {
        return Codec.AV1;
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
     * <p>The record is an {@code av1C} codec configuration record holding the sequence header OBU;
     * it changes after a {@link #reconfigure(int, int)} that needs a new sequence header.
     */
    @Override
    public Optional<byte[]> codecConfig() {
        return Optional.of(engine.codecConfigurationRecord());
    }

    /**
     * {@inheritDoc}
     *
     * @throws IllegalArgumentException if the picture size differs from the encoder's or the
     * timestamp does not follow the previous one
     * @throws IllegalStateException if the encoder is closed
     */
    @Override
    public void encode(VideoFrame frame, Consumer<Packet> output) {
        Objects.requireNonNull(output, "output");
        if (engine instanceof Av1GoodEncoder good) {
            encodeGood(good, frame, output);
            return;
        }
        encodeFrame(frame).ifPresent(output);
    }

    /**
     * Adds a picture to the good-quality lookahead, passing on the packets of every frame group it
     * completes.
     *
     * @param good the good-quality loop
     * @param frame the picture and its timestamp
     * @param output the receiver of the packets
     */
    private void encodeGood(Av1GoodEncoder good, VideoFrame frame, Consumer<Packet> output) {
        Objects.requireNonNull(frame, "frame");
        try (var _ = ParallelBands.operation(operation)) {
            checkOpen();
            checkSize(frame);
            var timing = clock.clock().resolve(frame);
            var forced = clock.takeKeyframe(false);
            clock.clock().commit(timing.pts());
            var coded = prepare(frame.picture(), null, timing.pts());
            good.encode(coded, timing.pts(), timing.duration(), forced, preparedGrain,
                    unit -> output.accept(packet(unit)));
        }
    }

    /**
     * Counts a good-quality temporal unit and wraps it in a packet.
     *
     * @param unit the temporal unit
     * @return the packet
     */
    private Packet packet(Av1GoodEncoder.Unit unit) {
        return clock.delivered(unit.data(), new FrameClock.Timing(unit.pts(), unit.duration()), unit.key());
    }

    /**
     * Checks that the encoder is open.
     *
     * @throws IllegalStateException if the encoder is closed
     */
    private void checkOpen() {
        if (closed) {
            throw new IllegalStateException("encoder is closed");
        }
    }

    /**
     * Checks that a picture has the encoder's size.
     *
     * @param frame the frame about to be coded
     * @throws IllegalArgumentException if the picture size differs from the encoder's
     */
    private void checkSize(VideoFrame frame) {
        var picture = frame.picture();
        if (picture.width() != width || picture.height() != height) {
            throw new IllegalArgumentException("picture is " + picture.width() + "x" + picture.height()
                    + ", encoder expects " + width + "x" + height);
        }
    }

    /**
     * Encodes one picture and returns its packet, in the real-time and intra-only usages.
     *
     * <p>The frame is a key frame when it is the first, {@link #requestKeyframe()} was called since
     * the last frame, the interval of {@link Av1EncoderOptions#keyframeInterval()} is due, or it is
     * the first after a {@link #reconfigure(int, int)} that the references cannot predict.
     *
     * @param frame the picture and its timestamp
     * @return the packet, or empty when the rate control dropped the frame
     * @throws IllegalArgumentException if the picture size differs from the encoder's or the
     * timestamp does not follow the previous one
     * @throws IllegalStateException if the encoder is closed or codes in the good-quality usage,
     * whose packets {@link #encode} passes on
     */
    public Optional<Packet> encodeFrame(VideoFrame frame) {
        Objects.requireNonNull(frame, "frame");
        try (var _ = ParallelBands.operation(operation)) {
            checkOpen();
            if (engine instanceof Av1GoodEncoder) {
                throw new IllegalStateException("a good-quality encoder holds frames back; use encode(frame, output) "
                        + "and requestKeyframe()");
            }
            checkSize(frame);
            var timing = clock.clock().resolve(frame);
            var forced = clock.takeKeyframe(false);
            var picture = frame.picture();
            var realtime = engine instanceof Av1RtEncoder rt ? rt : null;
            var coded = prepare(picture, realtime != null ? converter : null, timing.pts());
            var data = realtime != null ? realtime.encode(coded, forced, preparedGrain, timing.pts())
                    : encodeIntra((StillFrameEncoder) engine, coded, preparedGrain);
            if (data == null) {
                clock.dropped(timing);
                return Optional.empty();
            }
            return Optional.of(clock.coded(data, timing, realtime == null || realtime.lastKeyFrame()));
        }
    }

    /**
     * Codes a picture as an intra-only key frame.
     *
     * @param still the intra-only frame encoder
     * @param picture the picture to code
     * @param grain the frame's film grain, or {@code null} for none
     * @return the temporal unit: a temporal delimiter, the sequence header and the frame
     */
    private byte[] encodeIntra(StillFrameEncoder still, Picture picture, Av1FilmGrain grain) {
        var seq = still.sequenceHeader();
        if (seq.filmGrainPresent) {
            var seed = intraGrainSeed >= 0 ? intraGrainSeed : grain != null ? grain.randomSeed() : 0;
            var data = grain == null ? null : FilmGrains.toData(grain, seq);
            if (data != null) {
                data.seed = seed;
            }
            still.filmGrain(data);
            intraGrainSeed = FilmGrains.nextSeed(seed);
        }
        var image = EncodeImage.of(picture, options.chromaFormat(), options.bitDepth(), options.color(), false, false);
        var chromaWidth = image.chromaWidth();
        var obus = still.encode(image.planes(), new int[]{image.width(), chromaWidth, chromaWidth});
        var data = new byte[obus.length + 2];
        // A temporal delimiter OBU header with obu_has_size_field set, then its zero payload size.
        data[0] = (byte) (Obu.OBU_TD << 3 | 0x02);
        System.arraycopy(obus, 0, data, 2, obus.length);
        return data;
    }

    /**
     * Returns a picture as the real-time and good-quality engines code it: in the options' chroma
     * format with the matrix and range of the options' color, a {@link YuvPicture} at 8 bits and a
     * {@link Yuv16Picture} of the options' depth above.
     *
     * @param picture the picture to code
     * @param converter the converter that writes an 8-bit conversion into its own picture, or
     * {@code null} to allocate a new one
     * @return the picture itself when it already has that format, depth, matrix and range,
     * otherwise a converted copy, which {@code converter} overwrites on its next call
     */
    private Picture toCoded(Picture picture, YuvConverter converter) {
        var color = options.color();
        var format = options.chromaFormat();
        var depth = options.bitDepth();
        if (depth == 8) {
            if (picture instanceof YuvPicture yuv && yuv.chromaFormat() == format && sameSamples(yuv.color(), color)) {
                return yuv;
            }
            return converter != null ? converter.convert(picture) : ImageOps.toYuv(picture, format, color);
        }
        if (picture instanceof Yuv16Picture yuv && yuv.chromaFormat() == format && yuv.bitDepth() == depth
                && sameSamples(yuv.color(), color)) {
            return yuv;
        }
        return ImageOps.toYuv16(picture, format, color, depth);
    }

    /**
     * Returns whether two color descriptions give the same Y'CbCr samples for the same image.
     *
     * @param first one description
     * @param second the other description
     * @return {@code true} when the matrix and the range match
     */
    private static boolean sameSamples(ColorInfo first, ColorInfo second) {
        return first.matrix() == second.matrix() && first.range() == second.range();
    }

    /**
     * Codes the pictures a good-quality encoder holds back and passes on their packets; the
     * other usages hold no pictures back. The encoder stays usable: later pictures continue the
     * stream in new frame groups, predicting from the frames already coded.
     *
     * @param output the receiver of the packets
     * @throws IllegalStateException if the encoder is closed
     */
    @Override
    public void flush(Consumer<Packet> output) {
        Objects.requireNonNull(output, "output");
        checkOpen();
        if (!(engine instanceof Av1GoodEncoder good)) {
            return;
        }
        try (var _ = ParallelBands.operation(operation)) {
            good.flush(unit -> output.accept(packet(unit)));
        }
    }

    /**
     * {@inheritDoc}
     * <p>Any thread may call this at any time. In the good-quality usage, the next picture passed
     * to {@link #encode} becomes a key frame; in the intra-only usage, every frame already is one.
     */
    @Override
    public void requestKeyframe() {
        clock.requestKeyframe();
    }

    /**
     * Changes the target bitrate; the next frame's target follows it. In the real-time usage the
     * buffer model keeps its fullness, clipped to the new buffer size.
     *
     * @param bitrate the bitrate in bits per second, positive
     * @throws IllegalArgumentException if {@code bitrate} is not positive
     * @throws IllegalStateException if the encoder is closed or codes at a constant
     * {@link Av1EncoderOptions#quality()}
     */
    @Override
    public void setTargetBitrate(long bitrate) {
        checkOpen();
        if (options.quality().isPresent()) {
            throw new IllegalStateException("an av1 encoder coding at a constant quality has no target bitrate");
        }
        reconfigure(options.toBuilder().bitrate(bitrate).build());
    }

    /**
     * Changes the settings of a running encoder; the next frame uses them.
     *
     * @param options the new options
     * @throws IllegalArgumentException if the time base, color description, temporal layers,
     * limits, usage, picture format, film grain table, denoising level, first pass statistics,
     * timing information or super-resolution differ from the current options, the frame rate differs while
     * {@linkplain Av1EncoderOptions#timingInfo() timing information} is signaled, or the
     * {@linkplain Av1EncoderOptions#screenContent() screen content tools} differ outside the
     * intra-only usage
     * @throws IllegalStateException if the encoder is closed
     */
    public void reconfigure(Av1EncoderOptions options) {
        Objects.requireNonNull(options, "options");
        checkOpen();
        var current = this.options;
        if (!options.timeBase().equals(current.timeBase()) || !options.color().equals(current.color())
                || !options.limits().equals(current.limits()) || options.temporalLayers() != current.temporalLayers()
                || options.usage() != current.usage()
                || options.chromaFormat() != current.chromaFormat() || options.bitDepth() != current.bitDepth()
                || !options.filmGrain().equals(current.filmGrain())
                || !options.denoiseLevel().equals(current.denoiseLevel())
                || !options.firstPassStatistics().equals(current.firstPassStatistics())
                || options.usage() != Av1EncoderUsage.ALL_INTRA && options.screenContent() != current.screenContent()
                || options.timingInfo() != current.timingInfo()
                || !options.superResolution().equals(current.superResolution())
                || options.timingInfo() != Av1TimingInfo.NONE && !options.frameRate().equals(current.frameRate())) {
            throw new IllegalArgumentException("the time base, color description, temporal layers, limits, "
                    + "usage, picture format, film grain, denoising, first pass statistics, timing information "
                    + "and super-resolution of a running av1 encoder, the frame rate it signals and the screen "
                    + "content tools of a predicted one cannot change");
        }
        if (engine instanceof StillFrameEncoder still && stillSettingsChange(current, options)) {
            var next = newStill(width, height, options);
            still.close();
            engine = next;
        }
        this.options = options;
        apply(options);
    }

    /**
     * Returns whether two options give the intra-only frame encoder different settings.
     *
     * @param current the options the frame encoder was built with
     * @param next the new options
     * @return {@code true} when the quality, speed, tile columns, thread count or screen content
     * tools differ
     */
    private static boolean stillSettingsChange(Av1EncoderOptions current, Av1EncoderOptions next) {
        return !current.quality().equals(next.quality()) || !current.speed().equals(next.speed())
                || current.tileColumnsLog2() != next.tileColumnsLog2() || threads(current) != threads(next)
                || current.screenContent() != next.screenContent();
    }

    /**
     * Changes the picture size.
     *
     * <p>In the real-time usage, a size no larger in either dimension than the size of the
     * stream's sequence header continues the stream without a key frame: the frames signal their
     * size in their headers and the next frame predicts from the references of the old size with
     * scaled motion compensation, unless a reference is more than twice the new size or less than
     * a sixteenth of it, which makes it a key frame. A larger size, and every change in the
     * intra-only usage, codes the next frame as a key frame with a new sequence header. The rate
     * control keeps what it learned: its buffer restarts at the optimal level, and its quantizer
     * history and rate model are rescaled for the new size.
     *
     * @param width the new width
     * @param height the new height
     * @throws IllegalArgumentException if a dimension is not positive
     * @throws IllegalStateException if the encoder is closed or codes in the good-quality usage,
     * whose lookahead holds pictures of the old size; flush it and create a new encoder instead
     * @throws MediaException.LimitExceeded if the picture exceeds the options' limits
     */
    public void reconfigure(int width, int height) {
        checkOpen();
        if (engine instanceof Av1GoodEncoder) {
            // TODO: change the good-quality picture size within the sequence size from scaled references,
            // as the real-time encoder does
            throw new IllegalStateException("a good-quality encoder cannot change its picture size; flush it and "
                    + "create a new encoder");
        }
        if (width == this.width && height == this.height) {
            return;
        }
        if (engine instanceof Av1RtEncoder realtime) {
            checkSize(width, height, options);
            if (!realtime.resize(width, height)) {
                var next = newRealtime(width, height, options);
                next.inheritRateControl(realtime);
                realtime.close();
                engine = next;
            }
        } else {
            var next = newStill(width, height, options);
            engine.close();
            engine = next;
        }
        this.width = width;
        this.height = height;
        apply(options);
    }

    /**
     * {@return a snapshot of the encoder's statistics}
     */
    public Av1EncoderStats stats() {
        return switch (engine) {
            case Av1GoodEncoder good -> new Av1EncoderStats(clock.framesEncoded(), clock.keyFramesEncoded(), 0,
                    clock.bytesEncoded(), clock.lastFrameBytes(), good.lastQindex(), good.lastKeyFrame(), 0, 0);
            case StillFrameEncoder _ -> new Av1EncoderStats(clock.framesEncoded(), clock.keyFramesEncoded(),
                    clock.framesDropped(), clock.bytesEncoded(), clock.lastFrameBytes(),
                    DequantTables.qualityToQindex(options.quality().orElseThrow()), clock.framesEncoded() > 0, 0, 0);
            case Av1RtEncoder realtime -> new Av1EncoderStats(clock.framesEncoded(), clock.keyFramesEncoded(),
                    clock.framesDropped(), clock.bytesEncoded(), clock.lastFrameBytes(), realtime.lastQindex(),
                    realtime.lastKeyFrame(), realtime.lastTemporalId(), realtime.bufferLevel());
        };
    }

    /**
     * {@inheritDoc}
     * <p>Closing twice has no effect.
     */
    @Override
    public void close() {
        if (!closed) {
            closed = true;
            engine.close();
        }
    }
}
