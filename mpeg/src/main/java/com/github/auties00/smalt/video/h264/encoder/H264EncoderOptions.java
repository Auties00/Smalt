package com.github.auties00.smalt.video.h264.encoder;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.MatrixCoefficients;
import com.github.auties00.smalt.internal.check.Checks;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.video.FieldOrder;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;

/**
 * Holds the settings of an {@link H264Encoder}. Instances are immutable and thread-safe; create them
 * with {@link #builder()} or a preset such as {@link #defaults()}.
 *
 * <p>The picture size is not an option: it is given to the {@link H264Encoder} constructor.
 *
 * @implNote This implementation chose the look-ahead depth, the B picture pyramid, the macroblock
 * tree strength, the B picture quantizer offset, the IDR size limits of real-time mode and the
 * rounding and decimation thresholds of the quantizer by Bjontegaard rate on PSNR-Y and SSIM-Y over
 * standard test sequences; {@code tools/docs/h264-encoder-tuning.md} records the measurements.
 */
public final class H264EncoderOptions {
    /**
     * The smallest H.264 quantizer, the finest quantization, which {@link #quality()} and
     * {@link #minQuantizer()} accept.
     */
    public static final int MIN_QUANTIZER = 0;

    /**
     * The largest H.264 quantizer, the coarsest quantization, which {@link #quality()} and
     * {@link #maxQuantizer()} accept.
     */
    public static final int MAX_QUANTIZER = 51;

    /**
     * The largest number of worker threads.
     */
    private static final int MAX_THREADS = 64;

    /**
     * The largest look-ahead depth in pictures.
     */
    private static final int MAX_LOOKAHEAD = 120;

    /**
     * The largest number of consecutive B pictures.
     */
    private static final int MAX_B_FRAMES = 8;

    /**
     * The largest number of slices per picture.
     */
    private static final int MAX_SLICES = 256;

    /**
     * The smallest slice size limit in bytes.
     */
    private static final int MIN_SLICE_BYTES = 64;

    /**
     * The longest intra refresh cycle in frames.
     */
    private static final int MAX_REFRESH_PERIOD = 4096;

    /**
     * The largest number of temporal layers.
     */
    private static final int MAX_TEMPORAL_LAYERS = 4;

    /**
     * The settings of {@link #forFile()}, which {@link #defaults()} also returns.
     */
    private static final H264EncoderOptions FILE = new Builder().build();

    /**
     * The settings of {@link #forRealtime()}.
     */
    private static final H264EncoderOptions REALTIME = new Builder()
            .profile(H264Profile.CONSTRAINED_BASELINE)
            .rateControl(H264RateControl.CONSTANT_BITRATE)
            .bufferSize(Duration.ofMillis(500))
            .quality(26)
            .noPeriodicKeyframes()
            .speed(H264EncoderSpeed.FASTEST)
            .color(ColorInfo.BT601_LIMITED)
            .packetFormat(H264PacketFormat.ANNEX_B)
            .quantizers(12, MAX_QUANTIZER)
            .realtime(true)
            .lookahead(0)
            .bFrames(0)
            .build();

    private final Rational frameRate;

    private final Rational timeBase;

    private final H264Profile profile;

    /**
     * The signaled level, or {@code null} for the lowest level that fits.
     */
    private final H264Level level;

    private final H264RateControl rateControl;

    private final long bitrate;

    /**
     * The rate at which the buffering verifier fills, or 0 for the default of the mode.
     */
    private final long maxBitrate;

    /**
     * The buffering verifier size, or {@code null} for the default of the mode.
     */
    private final Duration bufferSize;

    private final int quality;

    private final OptionalInt keyframeInterval;

    private final H264EncoderSpeed speed;

    private final ColorInfo color;

    private final int threads;

    private final H264PacketFormat packetFormat;

    private final int minQuantizer;

    private final int maxQuantizer;

    private final boolean realtime;

    private final int lookahead;

    private final int bFrames;

    private final MediaLimits limits;

    private final ChromaFormat chromaFormat;

    private final int bitDepth;

    private final boolean lossless;

    private final FieldOrder fieldOrder;

    private final H264FieldCoding fieldCoding;

    private final int slices;

    /**
     * The slice size limit in bytes, or 0 for none.
     */
    private final int maxSliceBytes;

    /**
     * The intra refresh period in frames, or 0 for none.
     */
    private final int intraRefresh;

    private final int temporalLayers;

    private final Rational sampleAspectRatio;

    private final boolean fixedFrameRate;

    private final boolean cavlc;

    private H264EncoderOptions(Builder builder) {
        this.frameRate = builder.frameRate;
        this.timeBase = builder.timeBase;
        this.profile = builder.profile;
        this.level = builder.level;
        this.rateControl = builder.rateControl;
        this.bitrate = builder.bitrate;
        this.maxBitrate = builder.maxBitrate;
        this.bufferSize = builder.bufferSize;
        this.quality = builder.quality;
        this.keyframeInterval = builder.keyframeInterval;
        this.speed = builder.speed;
        this.color = builder.color;
        this.threads = builder.threads;
        this.packetFormat = builder.packetFormat;
        this.minQuantizer = builder.minQuantizer;
        this.maxQuantizer = builder.maxQuantizer;
        this.realtime = builder.realtime;
        this.lookahead = builder.lookahead;
        this.bFrames = builder.bFrames;
        this.limits = builder.limits;
        this.chromaFormat = builder.chromaFormat;
        this.bitDepth = builder.bitDepth;
        this.lossless = builder.lossless;
        this.fieldOrder = builder.fieldOrder;
        this.fieldCoding = builder.fieldCoding;
        this.slices = builder.slices;
        this.maxSliceBytes = builder.maxSliceBytes;
        this.intraRefresh = builder.intraRefresh;
        this.temporalLayers = builder.temporalLayers;
        this.sampleAspectRatio = builder.sampleAspectRatio;
        this.fixedFrameRate = builder.fixedFrameRate;
        this.cavlc = builder.cavlc;
    }

    /**
     * Returns the general-purpose settings, which are those of {@link #forFile()}.
     *
     * @return the options
     */
    public static H264EncoderOptions defaults() {
        return FILE;
    }

    /**
     * Returns the settings for a file or an on-demand stream that a player downloads progressively.
     *
     * <p>The stream is {@link H264Profile#MAIN} at an {@link H264RateControl#AVERAGE_BITRATE} of
     * 1 Mbit/s whose buffering verifier fills at twice the bitrate and holds one second at that
     * rate, with an IDR picture every 60 frames (two seconds at the nominal 30 frames per second),
     * {@link H264EncoderSpeed#BALANCED}, limited-range BT.709 color, one thread, the lowest
     * fitting level, {@link H264PacketFormat#LENGTH_PREFIXED} packets and the 90 kHz time base
     * {@code 1/90000}. The encoder looks 20 pictures ahead ({@link #lookahead()}) and codes seven
     * B pictures between anchors ({@link #bFrames()}), so it holds the first 20 frames until
     * {@link H264Encoder#flush} or enough later frames arrive, and its packets leave in decoding
     * order with decoding timestamps before their presentation timestamps.
     *
     * @apiNote Set the frame rate and bitrate of the stream with {@link Builder#frameRate(Rational)}
     * and {@link Builder#bitrate(long)} on {@link #toBuilder()}, and scale the key frame interval
     * with the frame rate through {@link Builder#keyframeInterval(int)} when the frame rate is not 30.
     *
     * @return the options
     */
    public static H264EncoderOptions forFile() {
        return FILE;
    }

    /**
     * Returns the settings for real-time transmission, such as a video call.
     *
     * <p>The stream is {@link H264Profile#CONSTRAINED_BASELINE} (one slice per picture, CAVLC, I
     * and P pictures only, and a {@code bitstream_restriction} announcing zero reordered frames,
     * so decoders output every picture as soon as it is decoded) at a
     * {@link H264RateControl#CONSTANT_BITRATE} of 1 Mbit/s with a half-second buffer and
     * {@link #realtime()} tuning, with no periodic IDR (callers use
     * {@link H264Encoder#requestKeyframe()} when a receiver loses sync),
     * {@link H264EncoderSpeed#FASTEST}, the quantizer window from 12 to 51,
     * {@link H264PacketFormat#ANNEX_B} packets, limited-range BT.601 color (the matrix camera and
     * RTC pipelines convert with), one thread, the lowest fitting level, the nominal 30 frames per
     * second and the 90 kHz RTP time base {@code 1/90000}.
     *
     * @return the options
     */
    public static H264EncoderOptions forRealtime() {
        return REALTIME;
    }

    /**
     * Returns a builder initialized with {@link #defaults()}.
     *
     * @return the builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns a builder initialized with these options.
     *
     * @return the builder
     */
    public Builder toBuilder() {
        return new Builder(this);
    }

    /**
     * {@return the nominal frame rate in frames per second, from 0.001 to 1000, written to the
     * stream's timing information and used by rate control when timestamps are missing; 30 by
     * default}
     *
     * <p>The numerator is at most {@code Integer.MAX_VALUE / 2}, so that it fits the stream timing
     * fields.
     */
    public Rational frameRate() {
        return frameRate;
    }

    /**
     * {@return the unit of the timestamps of accepted frames and produced packets, positive;
     * {@code 1/90000} by default}
     */
    public Rational timeBase() {
        return timeBase;
    }

    /**
     * {@return the profile, which selects the entropy coder and the coding tools;
     * {@link H264Profile#MAIN} by default}
     */
    public H264Profile profile() {
        return profile;
    }

    /**
     * {@return the level signaled in the stream, or empty when the encoder chooses the lowest level
     * that fits the picture size, frame rate, bitrate and buffer; empty by default}
     */
    public Optional<H264Level> level() {
        return Optional.ofNullable(level);
    }

    /**
     * {@return the rate control mode; {@link H264RateControl#AVERAGE_BITRATE} by default}
     *
     * <ul>
     *   <li>{@link H264RateControl#CONSTANT_QUALITY}: every frame is coded around the quantizer
     *       {@link #quality()}, key frames slightly finer; the bitrate, maximum bitrate and buffer
     *       are ignored.</li>
     *   <li>{@link H264RateControl#AVERAGE_BITRATE}: the long-term average approaches
     *       {@link #bitrate()} while a video buffering verifier of {@link #bufferSize()} filled at
     *       {@link #maxBitrate()} never underflows.</li>
     *   <li>{@link H264RateControl#CONSTANT_BITRATE}: as above with the maximum bitrate equal to the
     *       bitrate by default and a tighter per-frame budget, for real-time transmission.</li>
     * </ul>
     * Every frame quantizer, and every macroblock quantizer derived from it by adaptive
     * quantization, stays inside the window from {@link #minQuantizer()} to {@link #maxQuantizer()};
     * a window narrower than {@value #MIN_QUANTIZER} to {@value #MAX_QUANTIZER} can make a frame
     * miss its rate target.
     */
    public H264RateControl rateControl() {
        return rateControl;
    }

    /**
     * {@return the target bitrate in bits per second, from 1 to {@code Integer.MAX_VALUE}; 1000000 by
     * default}
     *
     * <p>{@link H264RateControl#CONSTANT_QUALITY} ignores it.
     */
    public long bitrate() {
        return bitrate;
    }

    /**
     * {@return the rate in bits per second at which the buffering verifier fills, from the bitrate to
     * {@code Integer.MAX_VALUE}, or empty for the default of the mode: the bitrate for
     * {@link H264RateControl#CONSTANT_BITRATE} and twice the bitrate for
     * {@link H264RateControl#AVERAGE_BITRATE}; empty by default}
     *
     * <p>{@link H264RateControl#CONSTANT_QUALITY} ignores it, and does not require it to reach the
     * bitrate.
     */
    public OptionalLong maxBitrate() {
        return maxBitrate == 0 ? OptionalLong.empty() : OptionalLong.of(maxBitrate);
    }

    /**
     * {@return the size of the buffering verifier, as positive playback time at the maximum bitrate
     * of at most {@code Integer.MAX_VALUE} milliseconds, or empty for the default of the mode: half a
     * second for {@link H264RateControl#CONSTANT_BITRATE} and two seconds for
     * {@link H264RateControl#AVERAGE_BITRATE}; one second by default}
     */
    public Optional<Duration> bufferSize() {
        return Optional.ofNullable(bufferSize);
    }

    /**
     * {@return the quantizer of {@link H264RateControl#CONSTANT_QUALITY}, from {@value #MIN_QUANTIZER}
     * (best) to {@value #MAX_QUANTIZER} (worst); 23 by default}
     *
     * <p>Other modes ignore it.
     */
    public int quality() {
        return quality;
    }

    /**
     * {@return the most frames between two IDR pictures, positive, or empty for no periodic IDR; 60
     * by default}
     *
     * <p>Without periodic IDR pictures the first frame, scene cuts and
     * {@link H264Encoder#requestKeyframe()} still produce one.
     */
    public OptionalInt keyframeInterval() {
        return keyframeInterval;
    }

    /**
     * {@return the speed and compression trade-off; {@link H264EncoderSpeed#BALANCED} by default}
     */
    public H264EncoderSpeed speed() {
        return speed;
    }

    /**
     * {@return the color description written to the stream and used to convert RGB input;
     * {@link ColorInfo#BT709_LIMITED} by default}
     *
     * <p>RGB input converts with the luma weights of a weighted matrix, {@link MatrixCoefficients#UNSPECIFIED}
     * as BT.709, and with every other matrix as
     * {@link com.github.auties00.smalt.image.ops.ImageOps#toYuv(com.github.auties00.smalt.image.Picture,
     * com.github.auties00.smalt.image.ChromaFormat, ColorInfo) ImageOps.toYuv} converts it, which includes
     * {@link MatrixCoefficients#YCGCO}, the constant-luminance {@link MatrixCoefficients#BT2020_CL} and
     * {@link MatrixCoefficients#IDENTITY} (G, B and R coded as Y, Cb and Cr). The identity matrix needs the
     * {@link com.github.auties00.smalt.image.ChromaFormat#YUV444} {@linkplain #chromaFormat() chroma format},
     * as H.264 allows it only there. The range, primaries, transfer and matrix are written to the video
     * usability information, the chroma siting, when specified, as its {@code chroma_sample_loc_type}, and
     * the mastering display and content light level, when present, as SEI messages of every IDR access
     * unit.
     */
    public ColorInfo color() {
        return color;
    }

    /**
     * {@return the most threads of the module's shared worker pool that analyze the macroblock rows
     * of a picture at once, from 1 to 64; 1 by default}
     *
     * <p>Above 1 the rows are analyzed in parallel while the calling thread entropy codes them. The
     * output is the same for every thread count and scheduling.
     */
    public int threads() {
        return threads;
    }

    /**
     * {@return how the NAL units of a packet are delimited and where the parameter sets travel;
     * {@link H264PacketFormat#LENGTH_PREFIXED} by default}
     */
    public H264PacketFormat packetFormat() {
        return packetFormat;
    }

    /**
     * {@return the smallest quantizer any frame or macroblock is coded with, from
     * {@value #MIN_QUANTIZER} to {@link #maxQuantizer()}; {@value #MIN_QUANTIZER} by default}
     */
    public int minQuantizer() {
        return minQuantizer;
    }

    /**
     * {@return the largest quantizer any frame or macroblock is coded with, from
     * {@link #minQuantizer()} to {@value #MAX_QUANTIZER}; {@value #MAX_QUANTIZER} by default}
     */
    public int maxQuantizer() {
        return maxQuantizer;
    }

    /**
     * {@return whether the encoder is tuned for interactive transmission, where every frame must
     * leave the encoder promptly and at a bounded size; {@code false} by default}
     *
     * <p>Real-time tuning means:
     * <ul>
     *   <li>with a bitrate target, the first IDR picture is aimed at no more than five frame budgets
     *       (the bits one frame of the nominal duration may use at the target bitrate, so about
     *       167 ms of transmission at 30 frames per second) and the later ones at no more than seven,
     *       instead of filling most of the buffering verifier, so an IDR picture neither stalls the
     *       transmission nor drains the buffer the following pictures need;</li>
     *   <li>every macroblock of a picture is coded with the picture's quantizer: there is no
     *       adaptive quantization;</li>
     *   <li>no frame is coded a second time to correct the size the rate control model predicted for
     *       it; a frame is coded again only when it would overflow the buffering verifier;</li>
     *   <li>a frame whose timestamp does not increase is accepted rather than rejected (see
     *       {@link H264Encoder#encode(com.github.auties00.smalt.video.VideoFrame,
     *       java.util.function.Consumer)});</li>
     *   <li>{@link #lookahead()} and {@link #bFrames()} are ignored and treated as 0.</li>
     * </ul>
     */
    public boolean realtime() {
        return realtime;
    }

    /**
     * {@return the number of pictures the encoder analyzes ahead of the one it codes, from 0 to 120;
     * 20 by default}
     *
     * <p>With a look-ahead, a half-resolution analysis of the coming pictures estimates how much of
     * each macroblock later pictures reuse, and macroblocks that are reused longer get a finer
     * quantizer. This costs as many pictures of delay: the encoder emits nothing for the first
     * {@code lookahead} frames, and {@link H264Encoder#flush} emits the rest. It is ignored, and
     * treated as 0, in {@link #realtime()} mode, with more than one {@linkplain #temporalLayers() temporal
     * layer}, and in streams that are not 8-bit, 4:2:0, progressive and lossy.
     */
    public int lookahead() {
        return lookahead;
    }

    /**
     * {@return the number of consecutive B pictures the encoder codes between two anchor pictures,
     * from 0 to 8; 7 by default}
     *
     * <p>B pictures are predicted from the anchor pictures on both sides in display order, with
     * spatial direct, skip and 16x16 list 0, list 1 and bi-predicted macroblocks, and cost fewer
     * bits. With two or more, the middle picture of each interval is coded first and referenced by
     * the others (a B pyramid), recursively, so the decoder keeps up to three more reference frames
     * and the encoder uses fewer B pictures when the level's picture buffer is too small; P pictures
     * start their motion search from the motion the look-ahead tracked over the pictures in between.
     * B pictures reorder the packets, so packets carry a decoding timestamp earlier than the
     * presentation timestamp. They need a {@link #lookahead()} above their number, so the encoder
     * uses one fewer than the look-ahead when it is smaller, and a profile with B pictures: the
     * {@link H264Profile#CONSTRAINED_BASELINE} profile and {@link #realtime()} mode code none, and
     * neither do streams with {@link #intraRefresh()} or more than one {@linkplain #temporalLayers()
     * temporal layer}, nor streams that are not 8-bit, 4:2:0, progressive and lossy.
     */
    public int bFrames() {
        return bFrames;
    }

    /**
     * {@return the allocation limits the picture size is checked against;
     * {@link MediaLimits#defaults()} by default}
     */
    public MediaLimits limits() {
        return limits;
    }

    /**
     * {@return the chroma format of the stream; {@link ChromaFormat#YUV420} by default}
     *
     * <p>Every input picture is converted to it: Y'CbCr input of the same layout keeps its samples,
     * other layouts are resampled as
     * {@link com.github.auties00.smalt.image.ops.ImageOps#toYuv(com.github.auties00.smalt.image.Picture,
     * ChromaFormat, ColorInfo) ImageOps.toYuv} resamples them, and RGB input is converted with
     * {@link #color()}. {@link ChromaFormat#MONOCHROME} codes luma alone ({@code chroma_format_idc} 0).
     * {@link ChromaFormat#YUV440} is not an H.264 format. The formats each profile codes are listed in
     * {@link H264Profile}.
     */
    public ChromaFormat chromaFormat() {
        return chromaFormat;
    }

    /**
     * {@return the bit depth of the luma and chroma samples, from 8 to 14; 8 by default}
     *
     * <p>Input of another depth is scaled to it as
     * {@link com.github.auties00.smalt.image.ops.ImageOps#toYuv16(com.github.auties00.smalt.image.Picture,
     * ChromaFormat, ColorInfo, int) ImageOps.toYuv16} scales it: a limited-range sample by shifting, so an 8-bit
     * sample {@code v} becomes {@code v << (bitDepth - 8)}, a full-range sample by requantizing to the new
     * largest value. The depths each profile codes are listed in {@link H264Profile}.
     */
    public int bitDepth() {
        return bitDepth;
    }

    /**
     * {@return whether the stream is coded losslessly; {@code false} by default}
     *
     * <p>A lossless stream sets {@code qpprime_y_zero_transform_bypass_flag} and codes every
     * macroblock with the quantizer {@code QP'Y} 0, so the residual bypasses the transform and the
     * decoded pictures equal the input exactly, once converted to the stream's
     * {@linkplain #chromaFormat() chroma format} and {@linkplain #bitDepth() bit depth}. The rate
     * control mode, bitrate, quality and quantizer window are ignored. It needs
     * {@link H264Profile#HIGH_444_PREDICTIVE}.
     */
    public boolean lossless() {
        return lossless;
    }

    /**
     * {@return the field order of interlaced input, or {@link FieldOrder#PROGRESSIVE} for progressive
     * input; {@link FieldOrder#PROGRESSIVE} by default}
     *
     * <p>An interlaced input picture is a frame of two interleaved fields: rows 0, 2, 4 and so on
     * form the top field, the other rows the bottom field, and {@link FieldOrder#TOP_FIRST} means
     * the top field was sampled first. Such frames are coded as {@link #fieldCoding()} says, with
     * picture order counts that give the fields their order, and the coded height is rounded up to a
     * multiple of 32. Interlaced coding needs a profile other than
     * {@link H264Profile#CONSTRAINED_BASELINE} and a level from 2.1 to 4.1.
     */
    public FieldOrder fieldOrder() {
        return fieldOrder;
    }

    /**
     * {@return how interlaced frames are coded; {@link H264FieldCoding#MBAFF} by default}
     *
     * <p>Progressive streams ignore it.
     */
    public H264FieldCoding fieldCoding() {
        return fieldCoding;
    }

    /**
     * {@return the number of slices each picture is divided into, from 1 to 256; 1 by default}
     *
     * <p>Each slice covers whole rows of macroblocks (of macroblock pairs, for interlaced frames
     * coded as {@link H264FieldCoding#MBAFF}), the rows divided as evenly as possible; a picture with
     * fewer rows has one slice per row. Slices are decoded independently: no prediction crosses a
     * slice boundary, so a lost slice does not damage the others, at some cost in compression.
     * {@link #maxSliceBytes()} may divide the slices further.
     */
    public int slices() {
        return slices;
    }

    /**
     * {@return the largest size of a slice NAL unit in bytes, at least 64, or empty for no limit; empty
     * by default}
     *
     * <p>A slice ends before the macroblock that would make its NAL unit larger, counting the NAL
     * unit header and the emulation prevention bytes but not the length prefix or start code, and the
     * next slice starts with that macroblock; only a slice of a single macroblock can exceed the limit.
     * The limit suits transports that carry each NAL unit in one datagram. With a limit, the
     * macroblocks of a picture are decided on the calling thread as they are coded, whatever
     * {@link #threads()} says.
     */
    public OptionalInt maxSliceBytes() {
        return maxSliceBytes == 0 ? OptionalInt.empty() : OptionalInt.of(maxSliceBytes);
    }

    /**
     * {@return the number of frames over which periodic intra refresh sweeps the picture, from 2 to
     * 4096, or empty for no intra refresh; empty by default}
     *
     * <p>With intra refresh the encoder codes an IDR picture first and on
     * {@link H264Encoder#requestKeyframe()}, and P pictures otherwise: neither
     * {@link #keyframeInterval()} nor scene cuts produce IDR pictures. Each P picture codes a band of
     * {@code ceil(columns / frames)} macroblock columns as intra macroblocks, the bands of a cycle
     * covering the picture from left to right, so a cycle lasts {@code ceil(columns / band)} pictures,
     * at most the given number; the columns that a cycle refreshed predict only from columns it
     * refreshed. The first picture of each cycle carries a recovery point SEI message, so a decoder
     * that starts there, or recovers from a loss there, outputs exact pictures from the last picture
     * of the cycle on. The size of every picture stays close to the size of a P picture, which suits
     * real-time transmission. The stream uses one reference frame and no B pictures, and it cannot be
     * combined with {@link #temporalLayers()}.
     */
    public OptionalInt intraRefresh() {
        return intraRefresh == 0 ? OptionalInt.empty() : OptionalInt.of(intraRefresh);
    }

    /**
     * {@return the number of temporal layers, from 1 to 4; 1 by default}
     *
     * <p>With {@code n} layers the frames repeat a pattern of {@code 2^(n - 1)} frames whose first
     * frame is in layer 0: with two layers 0, 1; with three 0, 2, 1, 2; with four 0, 3, 2, 3, 1, 3,
     * 2, 3. A frame of layer {@code t} above 0 is predicted from the latest frame of a lower layer, a
     * frame of layer 0 from the latest frame of layer 0, and the frames of the highest layer are not
     * reference pictures, so the frames of any layer and the layers below it form a stream that
     * decodes on its own at a fraction of the frame rate. Each slice is preceded by a prefix NAL unit
     * ({@code nal_unit_type} 14) whose {@code temporal_id} names the layer, which decoders of the
     * profiles without the scalable extensions ignore, and {@link H264EncoderStats#lastTemporalLayer()}
     * reports the layer of the last packet. Frames of layer {@code t} are coded {@code 2 * t}
     * quantizer steps coarser. The stream uses no B pictures and no look-ahead, and more than one layer
     * cannot be combined with {@link #intraRefresh()}.
     */
    public int temporalLayers() {
        return temporalLayers;
    }

    /**
     * {@return the sample aspect ratio, the width of a sample over its height, written to the video
     * usability information; {@code 1/1} by default}
     *
     * <p>Both terms are from 1 to 65535. A ratio of ITU-T H.264 Table E-1 is written as its
     * {@code aspect_ratio_idc}, any other as {@code Extended_SAR}.
     */
    public Rational sampleAspectRatio() {
        return sampleAspectRatio;
    }

    /**
     * {@return whether the video usability information declares that pictures follow at the nominal
     * {@linkplain #frameRate() frame rate} ({@code fixed_frame_rate_flag}); {@code false} by default}
     */
    public boolean fixedFrameRate() {
        return fixedFrameRate;
    }

    /**
     * {@return whether a profile other than {@link H264Profile#CONSTRAINED_BASELINE}, which always
     * uses CAVLC, entropy codes with CAVLC instead of CABAC; {@code false} by default}
     *
     * <p>CAVLC costs more bits than CABAC and less decoding time. A CAVLC stream has no
     * {@linkplain #bFrames() B pictures}, and an 8-bit, 4:2:0, progressive, lossy CAVLC stream has no 8x8
     * transform either.
     */
    public boolean cavlc() {
        return cavlc;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof H264EncoderOptions that && frameRate.equals(that.frameRate)
                && timeBase.equals(that.timeBase) && profile == that.profile && level == that.level
                && rateControl == that.rateControl && bitrate == that.bitrate && maxBitrate == that.maxBitrate
                && Objects.equals(bufferSize, that.bufferSize) && quality == that.quality
                && keyframeInterval.equals(that.keyframeInterval) && speed == that.speed && color.equals(that.color)
                && threads == that.threads && packetFormat == that.packetFormat && minQuantizer == that.minQuantizer
                && maxQuantizer == that.maxQuantizer && realtime == that.realtime && lookahead == that.lookahead
                && bFrames == that.bFrames && limits.equals(that.limits) && chromaFormat == that.chromaFormat
                && bitDepth == that.bitDepth && lossless == that.lossless && fieldOrder == that.fieldOrder
                && fieldCoding == that.fieldCoding && slices == that.slices && maxSliceBytes == that.maxSliceBytes
                && intraRefresh == that.intraRefresh && temporalLayers == that.temporalLayers
                && sampleAspectRatio.equals(that.sampleAspectRatio) && fixedFrameRate == that.fixedFrameRate
                && cavlc == that.cavlc;
    }

    @Override
    public int hashCode() {
        return Objects.hash(frameRate, timeBase, profile, level, rateControl, bitrate, maxBitrate, bufferSize, quality,
                keyframeInterval, speed, color, threads, packetFormat, minQuantizer, maxQuantizer, realtime, lookahead,
                bFrames, limits, chromaFormat, bitDepth, lossless, fieldOrder, fieldCoding, slices, maxSliceBytes,
                intraRefresh, temporalLayers, sampleAspectRatio, fixedFrameRate, cavlc);
    }

    @Override
    public String toString() {
        return "H264EncoderOptions[frameRate=" + frameRate + ", timeBase=" + timeBase + ", profile=" + profile
                + ", level=" + (level == null ? "auto" : level) + ", rateControl=" + rateControl + ", bitrate="
                + bitrate + ", maxBitrate=" + (maxBitrate == 0 ? "default" : maxBitrate) + ", bufferSize="
                + (bufferSize == null ? "default" : bufferSize) + ", quality=" + quality + ", keyframeInterval="
                + keyframeInterval + ", speed=" + speed + ", color=" + color + ", threads=" + threads
                + ", packetFormat=" + packetFormat + ", quantizers=" + minQuantizer + ".." + maxQuantizer
                + ", realtime=" + realtime + ", lookahead=" + lookahead + ", bFrames=" + bFrames + ", limits=" + limits
                + ", chromaFormat=" + chromaFormat + ", bitDepth=" + bitDepth + ", lossless=" + lossless
                + ", fieldOrder=" + fieldOrder + ", fieldCoding=" + fieldCoding + ", slices=" + slices
                + ", maxSliceBytes=" + (maxSliceBytes == 0 ? "none" : maxSliceBytes) + ", intraRefresh="
                + (intraRefresh == 0 ? "none" : intraRefresh) + ", temporalLayers=" + temporalLayers
                + ", sampleAspectRatio=" + sampleAspectRatio + ", fixedFrameRate=" + fixedFrameRate + ", cavlc=" + cavlc
                + "]";
    }

    /**
     * A builder of {@link H264EncoderOptions}, starting from {@link H264EncoderOptions#defaults()}.
     *
     * <p>Setters reject only {@code null}; {@link #build()} checks the values and how they combine.
     * A builder may build repeatedly. Instances are not thread-safe.
     */
    public static final class Builder {
        private Rational frameRate;

        private Rational timeBase;

        private H264Profile profile;

        private H264Level level;

        private H264RateControl rateControl;

        private long bitrate;

        private long maxBitrate;

        private boolean explicitMaxBitrate;

        private Duration bufferSize;

        private int quality;

        private OptionalInt keyframeInterval;

        private H264EncoderSpeed speed;

        private ColorInfo color;

        private int threads;

        private H264PacketFormat packetFormat;

        private int minQuantizer;

        private int maxQuantizer;

        private boolean realtime;

        private int lookahead;

        private int bFrames;

        private MediaLimits limits;

        private ChromaFormat chromaFormat;

        private int bitDepth;

        private boolean lossless;

        private FieldOrder fieldOrder;

        private H264FieldCoding fieldCoding;

        private int slices;

        private int maxSliceBytes;

        private int intraRefresh;

        private int temporalLayers;

        private Rational sampleAspectRatio;

        private boolean fixedFrameRate;

        private boolean cavlc;

        private Builder() {
            frameRate = new Rational(30, 1);
            timeBase = new Rational(1, 90_000);
            profile = H264Profile.MAIN;
            rateControl = H264RateControl.AVERAGE_BITRATE;
            bitrate = 1_000_000;
            bufferSize = Duration.ofSeconds(1);
            quality = 23;
            keyframeInterval = OptionalInt.of(60);
            speed = H264EncoderSpeed.BALANCED;
            color = ColorInfo.BT709_LIMITED;
            threads = 1;
            packetFormat = H264PacketFormat.LENGTH_PREFIXED;
            minQuantizer = MIN_QUANTIZER;
            maxQuantizer = MAX_QUANTIZER;
            lookahead = 20;
            bFrames = 7;
            limits = MediaLimits.DEFAULT;
            chromaFormat = ChromaFormat.YUV420;
            bitDepth = 8;
            fieldOrder = FieldOrder.PROGRESSIVE;
            fieldCoding = H264FieldCoding.MBAFF;
            slices = 1;
            temporalLayers = 1;
            sampleAspectRatio = new Rational(1, 1);
        }

        private Builder(H264EncoderOptions from) {
            frameRate = from.frameRate;
            timeBase = from.timeBase;
            profile = from.profile;
            level = from.level;
            rateControl = from.rateControl;
            bitrate = from.bitrate;
            maxBitrate = from.maxBitrate;
            explicitMaxBitrate = from.maxBitrate != 0;
            bufferSize = from.bufferSize;
            quality = from.quality;
            keyframeInterval = from.keyframeInterval;
            speed = from.speed;
            color = from.color;
            threads = from.threads;
            packetFormat = from.packetFormat;
            minQuantizer = from.minQuantizer;
            maxQuantizer = from.maxQuantizer;
            realtime = from.realtime;
            lookahead = from.lookahead;
            bFrames = from.bFrames;
            limits = from.limits;
            chromaFormat = from.chromaFormat;
            bitDepth = from.bitDepth;
            lossless = from.lossless;
            fieldOrder = from.fieldOrder;
            fieldCoding = from.fieldCoding;
            slices = from.slices;
            maxSliceBytes = from.maxSliceBytes;
            intraRefresh = from.intraRefresh;
            temporalLayers = from.temporalLayers;
            sampleAspectRatio = from.sampleAspectRatio;
            fixedFrameRate = from.fixedFrameRate;
            cavlc = from.cavlc;
        }

        /**
         * Sets the nominal frame rate; see {@link H264EncoderOptions#frameRate()}.
         *
         * @param frameRate the value
         * @return this builder
         */
        public Builder frameRate(Rational frameRate) {
            this.frameRate = Objects.requireNonNull(frameRate, "frameRate");
            return this;
        }

        /**
         * Sets the time base of the timestamps; see {@link H264EncoderOptions#timeBase()}.
         *
         * @param timeBase the value
         * @return this builder
         */
        public Builder timeBase(Rational timeBase) {
            this.timeBase = Objects.requireNonNull(timeBase, "timeBase");
            return this;
        }

        /**
         * Sets the profile; see {@link H264EncoderOptions#profile()}.
         *
         * @param profile the value
         * @return this builder
         */
        public Builder profile(H264Profile profile) {
            this.profile = Objects.requireNonNull(profile, "profile");
            return this;
        }

        /**
         * Sets the signaled level; see {@link H264EncoderOptions#level()}.
         *
         * @param level the value
         * @return this builder
         */
        public Builder level(H264Level level) {
            this.level = Objects.requireNonNull(level, "level");
            return this;
        }

        /**
         * Lets the encoder signal the lowest level that fits the stream; see
         * {@link H264EncoderOptions#level()}.
         *
         * @return this builder
         */
        public Builder automaticLevel() {
            this.level = null;
            return this;
        }

        /**
         * Sets the rate control mode; see {@link H264EncoderOptions#rateControl()}.
         *
         * @param rateControl the value
         * @return this builder
         */
        public Builder rateControl(H264RateControl rateControl) {
            this.rateControl = Objects.requireNonNull(rateControl, "rateControl");
            return this;
        }

        /**
         * Sets the target bitrate; see {@link H264EncoderOptions#bitrate()}.
         *
         * @param bitrate the value
         * @return this builder
         */
        public Builder bitrate(long bitrate) {
            this.bitrate = bitrate;
            return this;
        }

        /**
         * Sets the rate at which the buffering verifier fills; see
         * {@link H264EncoderOptions#maxBitrate()}.
         *
         * @param maxBitrate the value
         * @return this builder
         */
        public Builder maxBitrate(long maxBitrate) {
            this.maxBitrate = maxBitrate;
            this.explicitMaxBitrate = true;
            return this;
        }

        /**
         * Lets the buffering verifier fill at the rate the rate control mode chooses; see
         * {@link H264EncoderOptions#maxBitrate()}.
         *
         * @return this builder
         */
        public Builder automaticMaxBitrate() {
            this.maxBitrate = 0;
            this.explicitMaxBitrate = false;
            return this;
        }

        /**
         * Sets the buffering verifier size; see {@link H264EncoderOptions#bufferSize()}.
         *
         * @param bufferSize the value
         * @return this builder
         */
        public Builder bufferSize(Duration bufferSize) {
            this.bufferSize = Objects.requireNonNull(bufferSize, "bufferSize");
            return this;
        }

        /**
         * Lets the buffering verifier take the size the rate control mode chooses; see
         * {@link H264EncoderOptions#bufferSize()}.
         *
         * @return this builder
         */
        public Builder automaticBufferSize() {
            this.bufferSize = null;
            return this;
        }

        /**
         * Sets the constant quality quantizer; see {@link H264EncoderOptions#quality()}.
         *
         * @param quality the value
         * @return this builder
         */
        public Builder quality(int quality) {
            this.quality = quality;
            return this;
        }

        /**
         * Sets the most frames between two IDR pictures; see
         * {@link H264EncoderOptions#keyframeInterval()}.
         *
         * @param frames the value
         * @return this builder
         */
        public Builder keyframeInterval(int frames) {
            this.keyframeInterval = OptionalInt.of(frames);
            return this;
        }

        /**
         * Codes IDR pictures only for the first frame, scene cuts and requests; see
         * {@link H264EncoderOptions#keyframeInterval()}.
         *
         * @return this builder
         */
        public Builder noPeriodicKeyframes() {
            this.keyframeInterval = OptionalInt.empty();
            return this;
        }

        /**
         * Sets the speed and compression trade-off; see {@link H264EncoderOptions#speed()}.
         *
         * @param speed the value
         * @return this builder
         */
        public Builder speed(H264EncoderSpeed speed) {
            this.speed = Objects.requireNonNull(speed, "speed");
            return this;
        }

        /**
         * Sets the color description; see {@link H264EncoderOptions#color()}.
         *
         * @param color the value
         * @return this builder
         */
        public Builder color(ColorInfo color) {
            this.color = Objects.requireNonNull(color, "color");
            return this;
        }

        /**
         * Sets the thread count; see {@link H264EncoderOptions#threads()}.
         *
         * @param threads the value
         * @return this builder
         */
        public Builder threads(int threads) {
            this.threads = threads;
            return this;
        }

        /**
         * Sets the packet format; see {@link H264EncoderOptions#packetFormat()}.
         *
         * @param packetFormat the value
         * @return this builder
         */
        public Builder packetFormat(H264PacketFormat packetFormat) {
            this.packetFormat = Objects.requireNonNull(packetFormat, "packetFormat");
            return this;
        }

        /**
         * Sets the quantizer window; see {@link H264EncoderOptions#minQuantizer()} and
         * {@link H264EncoderOptions#maxQuantizer()}.
         *
         * @param minQuantizer the smallest quantizer
         * @param maxQuantizer the largest quantizer
         * @return this builder
         */
        public Builder quantizers(int minQuantizer, int maxQuantizer) {
            this.minQuantizer = minQuantizer;
            this.maxQuantizer = maxQuantizer;
            return this;
        }

        /**
         * Sets whether the encoder is tuned for interactive transmission; see
         * {@link H264EncoderOptions#realtime()}.
         *
         * @param realtime the value
         * @return this builder
         */
        public Builder realtime(boolean realtime) {
            this.realtime = realtime;
            return this;
        }

        /**
         * Sets the look-ahead depth; see {@link H264EncoderOptions#lookahead()}.
         *
         * @param lookahead the value
         * @return this builder
         */
        public Builder lookahead(int lookahead) {
            this.lookahead = lookahead;
            return this;
        }

        /**
         * Sets the number of consecutive B pictures; see {@link H264EncoderOptions#bFrames()}.
         *
         * @param bFrames the value
         * @return this builder
         */
        public Builder bFrames(int bFrames) {
            this.bFrames = bFrames;
            return this;
        }

        /**
         * Sets the allocation limits; see {@link H264EncoderOptions#limits()}.
         *
         * @param limits the value
         * @return this builder
         */
        public Builder limits(MediaLimits limits) {
            this.limits = Objects.requireNonNull(limits, "limits");
            return this;
        }

        /**
         * Sets the chroma format of the stream; see {@link H264EncoderOptions#chromaFormat()}.
         *
         * @param chromaFormat the value
         * @return this builder
         */
        public Builder chromaFormat(ChromaFormat chromaFormat) {
            this.chromaFormat = Objects.requireNonNull(chromaFormat, "chromaFormat");
            return this;
        }

        /**
         * Sets the bit depth of the samples; see {@link H264EncoderOptions#bitDepth()}.
         *
         * @param bitDepth the value
         * @return this builder
         */
        public Builder bitDepth(int bitDepth) {
            this.bitDepth = bitDepth;
            return this;
        }

        /**
         * Sets whether the stream is coded losslessly; see {@link H264EncoderOptions#lossless()}.
         *
         * @param lossless the value
         * @return this builder
         */
        public Builder lossless(boolean lossless) {
            this.lossless = lossless;
            return this;
        }

        /**
         * Sets the field order of the input; see {@link H264EncoderOptions#fieldOrder()}.
         *
         * @param fieldOrder the value
         * @return this builder
         */
        public Builder fieldOrder(FieldOrder fieldOrder) {
            this.fieldOrder = Objects.requireNonNull(fieldOrder, "fieldOrder");
            return this;
        }

        /**
         * Sets how interlaced frames are coded; see {@link H264EncoderOptions#fieldCoding()}.
         *
         * @param fieldCoding the value
         * @return this builder
         */
        public Builder fieldCoding(H264FieldCoding fieldCoding) {
            this.fieldCoding = Objects.requireNonNull(fieldCoding, "fieldCoding");
            return this;
        }

        /**
         * Sets the number of slices per picture; see {@link H264EncoderOptions#slices()}.
         *
         * @param slices the value
         * @return this builder
         */
        public Builder slices(int slices) {
            this.slices = slices;
            return this;
        }

        /**
         * Sets the largest size of a slice NAL unit; see {@link H264EncoderOptions#maxSliceBytes()}.
         *
         * @param bytes the value
         * @return this builder
         */
        public Builder maxSliceBytes(int bytes) {
            this.maxSliceBytes = bytes;
            return this;
        }

        /**
         * Removes the limit on the size of a slice NAL unit; see
         * {@link H264EncoderOptions#maxSliceBytes()}.
         *
         * @return this builder
         */
        public Builder noMaxSliceBytes() {
            this.maxSliceBytes = 0;
            return this;
        }

        /**
         * Enables periodic intra refresh over a number of frames; see
         * {@link H264EncoderOptions#intraRefresh()}.
         *
         * @param frames the value
         * @return this builder
         */
        public Builder intraRefresh(int frames) {
            this.intraRefresh = frames;
            return this;
        }

        /**
         * Disables periodic intra refresh; see {@link H264EncoderOptions#intraRefresh()}.
         *
         * @return this builder
         */
        public Builder noIntraRefresh() {
            this.intraRefresh = 0;
            return this;
        }

        /**
         * Sets the number of temporal layers; see {@link H264EncoderOptions#temporalLayers()}.
         *
         * @param temporalLayers the value
         * @return this builder
         */
        public Builder temporalLayers(int temporalLayers) {
            this.temporalLayers = temporalLayers;
            return this;
        }

        /**
         * Sets the sample aspect ratio; see {@link H264EncoderOptions#sampleAspectRatio()}.
         *
         * @param sampleAspectRatio the value
         * @return this builder
         */
        public Builder sampleAspectRatio(Rational sampleAspectRatio) {
            this.sampleAspectRatio = Objects.requireNonNull(sampleAspectRatio, "sampleAspectRatio");
            return this;
        }

        /**
         * Sets whether the stream declares a fixed frame rate; see
         * {@link H264EncoderOptions#fixedFrameRate()}.
         *
         * @param fixedFrameRate the value
         * @return this builder
         */
        public Builder fixedFrameRate(boolean fixedFrameRate) {
            this.fixedFrameRate = fixedFrameRate;
            return this;
        }

        /**
         * Sets whether the stream is entropy coded with CAVLC; see {@link H264EncoderOptions#cavlc()}.
         *
         * @param cavlc the value
         * @return this builder
         */
        public Builder cavlc(boolean cavlc) {
            this.cavlc = cavlc;
            return this;
        }

        /**
         * Returns the options holding the settings of this builder.
         *
         * @return the options
         * @throws IllegalArgumentException if the frame rate is not in [0.001, 1000] or does not fit the
         * stream timing fields, the time base is not positive, the bitrate is not in
         * [1, {@code Integer.MAX_VALUE}], the maximum bitrate is not in [1, {@code Integer.MAX_VALUE}]
         * or is below the bitrate in a bitrate mode, the buffer size is not positive or exceeds
         * {@code Integer.MAX_VALUE} milliseconds, the quality is not in [0, 51], the key frame
         * interval is not positive, the thread count is not in [1, 64], the look-ahead is not in
         * [0, 120], the B picture count is not in [0, 8], the quantizer window is not inside [0, 51]
         * or is empty, the bit depth is not in [8, 14], the slice count is not in [1, 256], the slice
         * size limit is below 64 bytes, the intra refresh period is not in [2, 4096], the temporal
         * layers are not in [1, 4], the sample aspect ratio is not positive or a term exceeds 65535,
         * the chroma format is {@link ChromaFormat#YUV440}, the profile does not code the chroma
         * format, bit depth, lossless coding or interlaced coding (see {@link H264Profile}), the color
         * matrix is {@link MatrixCoefficients#IDENTITY} without {@link ChromaFormat#YUV444}, or intra
         * refresh is combined with more than one temporal layer
         */
        public H264EncoderOptions build() {
            var rate = frameRate.toDouble();
            if (!(rate >= 0.001 && rate <= 1000)) {
                throw new IllegalArgumentException("frame rate " + frameRate + " is not in [0.001, 1000]");
            }
            if (frameRate.numerator() > Integer.MAX_VALUE / 2 || frameRate.denominator() > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("frame rate " + frameRate
                        + " does not fit the stream timing fields");
            }
            if (timeBase.numerator() <= 0 || timeBase.denominator() <= 0) {
                throw new IllegalArgumentException("time base " + timeBase + " is not positive");
            }
            Checks.inRange("bitrate", bitrate, 1, Integer.MAX_VALUE);
            if (explicitMaxBitrate) {
                Checks.inRange("maximum bitrate", maxBitrate, 1, Integer.MAX_VALUE);
                if (rateControl != H264RateControl.CONSTANT_QUALITY && maxBitrate < bitrate) {
                    throw new IllegalArgumentException("maximum bitrate " + maxBitrate + " is below the bitrate "
                            + bitrate);
                }
            }
            if (bufferSize != null) {
                Checks.positive("buffer size", bufferSize);
                Checks.inRange("buffer size", bufferSize, Duration.ZERO, Duration.ofMillis(Integer.MAX_VALUE));
            }
            Checks.inRange("quality", quality, MIN_QUANTIZER, MAX_QUANTIZER);
            if (keyframeInterval.isPresent()) {
                Checks.positive("keyframe interval", keyframeInterval.getAsInt());
            }
            Checks.inRange("thread count", threads, 1, MAX_THREADS);
            Checks.inRange("look-ahead", lookahead, 0, MAX_LOOKAHEAD);
            Checks.inRange("B picture count", bFrames, 0, MAX_B_FRAMES);
            Checks.inRange("minimum quantizer", minQuantizer, MIN_QUANTIZER, MAX_QUANTIZER);
            Checks.inRange("maximum quantizer", maxQuantizer, minQuantizer, MAX_QUANTIZER);
            Checks.inRange("bit depth", bitDepth, 8, 14);
            Checks.inRange("slice count", slices, 1, MAX_SLICES);
            if (maxSliceBytes != 0) {
                Checks.inRange("slice size limit", maxSliceBytes, MIN_SLICE_BYTES, Integer.MAX_VALUE);
            }
            if (intraRefresh != 0) {
                Checks.inRange("intra refresh period", intraRefresh, 2, MAX_REFRESH_PERIOD);
            }
            Checks.inRange("temporal layers", temporalLayers, 1, MAX_TEMPORAL_LAYERS);
            if (sampleAspectRatio.numerator() <= 0 || sampleAspectRatio.denominator() <= 0
                    || sampleAspectRatio.numerator() > 65535 || sampleAspectRatio.denominator() > 65535) {
                throw new IllegalArgumentException("sample aspect ratio " + sampleAspectRatio
                        + " is not positive with terms of at most 65535");
            }
            if (chromaFormat == ChromaFormat.YUV440) {
                throw new IllegalArgumentException("4:4:0 is not an H.264 chroma format");
            }
            var formatOk = switch (chromaFormat) {
                case YUV420 -> true;
                case MONOCHROME -> profile.high();
                case YUV422 -> profile == H264Profile.HIGH_422 || profile == H264Profile.HIGH_444_PREDICTIVE;
                default -> profile == H264Profile.HIGH_444_PREDICTIVE;
            };
            if (!formatOk) {
                throw new IllegalArgumentException("the " + profile + " profile does not code " + chromaFormat);
            }
            if (bitDepth > profile.maxBitDepth()) {
                throw new IllegalArgumentException("the " + profile + " profile does not code " + bitDepth
                        + "-bit samples");
            }
            if (lossless && profile != H264Profile.HIGH_444_PREDICTIVE) {
                throw new IllegalArgumentException("lossless coding needs the HIGH_444_PREDICTIVE profile");
            }
            if (fieldOrder.isInterlaced() && profile == H264Profile.CONSTRAINED_BASELINE) {
                throw new IllegalArgumentException("the CONSTRAINED_BASELINE profile does not code interlaced video");
            }
            if (color.matrix() == MatrixCoefficients.IDENTITY && chromaFormat != ChromaFormat.YUV444) {
                throw new IllegalArgumentException("the identity color matrix needs the YUV444 chroma format");
            }
            if (intraRefresh != 0 && temporalLayers > 1) {
                throw new IllegalArgumentException("intra refresh cannot be combined with temporal layers");
            }
            return new H264EncoderOptions(this);
        }
    }
}
