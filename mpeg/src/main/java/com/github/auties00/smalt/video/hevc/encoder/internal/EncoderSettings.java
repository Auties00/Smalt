package com.github.auties00.smalt.video.hevc.encoder.internal;

import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.video.hevc.encoder.HevcEncoderOptions;
import com.github.auties00.smalt.video.hevc.encoder.HevcEncoderSpeed;
import com.github.auties00.smalt.video.hevc.encoder.HevcLevel;
import com.github.auties00.smalt.video.hevc.encoder.HevcPacketFormat;
import com.github.auties00.smalt.video.hevc.encoder.HevcPictureHash;
import com.github.auties00.smalt.video.hevc.encoder.HevcProfile;
import com.github.auties00.smalt.video.hevc.encoder.HevcRateControl;
import com.github.auties00.smalt.video.hevc.internal.HevcSei;

import java.math.BigInteger;
import java.time.Duration;

/**
 * The resolved settings of one encoder: the picture size with its {@link HevcEncoderOptions}, reduced to the
 * values the engine works with, and the coding structure the parameter sets announce.
 *
 * <p>Instances come only from {@link #of(int, int, HevcEncoderOptions)}, which validates them, and are
 * immutable.
 */
public final class EncoderSettings {
    /**
     * The largest accepted picture side in pixels.
     */
    private static final int MAX_DIMENSION = 16_888;

    private static final BigInteger NANOS_PER_SECOND = BigInteger.valueOf(1_000_000_000L);

    /**
     * The options the settings were resolved from.
     */
    final HevcEncoderOptions options;

    /**
     * The picture width the decoder outputs, the input width rounded up to an even number.
     */
    final int width;

    /**
     * The picture height the decoder outputs, the input height rounded up to an even number.
     */
    final int height;

    /**
     * The input width in pixels.
     */
    final int inputWidth;

    /**
     * The input height in pixels.
     */
    final int inputHeight;

    /**
     * {@code pic_width_in_luma_samples}, a multiple of 8.
     */
    final int codedWidth;

    /**
     * {@code pic_height_in_luma_samples}, a multiple of 8.
     */
    final int codedHeight;

    /**
     * The width of the chroma planes of a coded picture.
     */
    final int chromaWidth;

    /**
     * The height of the chroma planes of a coded picture.
     */
    final int chromaHeight;

    /**
     * The luma and chroma bit depth, 8 to 10.
     */
    final int bitDepth;

    /**
     * {@code CtbLog2SizeY}.
     */
    final int log2CtbSize;

    /**
     * {@code PicWidthInCtbsY}.
     */
    final int widthInCtbs;

    /**
     * {@code PicHeightInCtbsY}.
     */
    final int heightInCtbs;

    /**
     * {@code max_transform_hierarchy_depth_intra}.
     */
    final int maxTransformDepthIntra;

    /**
     * {@code max_transform_hierarchy_depth_inter}.
     */
    final int maxTransformDepthInter;

    /**
     * Whether the stream holds exactly one intra picture.
     */
    final boolean stillPicture;

    /**
     * {@code general_level_idc}.
     */
    final int levelIdc;

    /**
     * {@code general_tier_flag}.
     */
    final boolean highTier;

    /**
     * The rate control mode.
     */
    final HevcRateControl rateControl;

    /**
     * The target bitrate in bits per second, 0 for constant quality.
     */
    final int bitrate;

    /**
     * The rate at which the buffering verifier fills in bits per second, 0 for constant quality.
     */
    final int maxBitrate;

    /**
     * The size of the buffering verifier in bits, 0 for constant quality.
     */
    final int bufferBits;

    /**
     * The number of consecutive B pictures between anchor pictures.
     */
    final int bFrames;

    /**
     * The number of reference pictures of a P picture.
     */
    final int references;

    /**
     * The most frames between two IDR pictures, 0 for no periodic IDR picture.
     */
    final int keyframeInterval;

    /**
     * {@code sps_max_dec_pic_buffering_minus1 + 1}.
     */
    final int maxDecPicBuffering;

    /**
     * {@code sps_max_num_reorder_pics}.
     */
    final int maxNumReorder;

    /**
     * The {@code hash_type} of the decoded picture hash SEI messages, or -1 for none.
     */
    final int hashType;

    /**
     * Whether packets are Annex B access units rather than length-prefixed NAL units.
     */
    final boolean annexB;

    /**
     * The color description.
     */
    final ColorInfo color;

    /**
     * The speed and compression trade-off.
     */
    final HevcEncoderSpeed speed;

    private EncoderSettings(int inputWidth, int inputHeight, HevcEncoderOptions options, int levelIdc,
                            boolean highTier, int bitrate, int maxBitrate, int bufferBits, int bFrames,
                            int references, int maxDecPicBuffering, int maxNumReorder) {
        this.options = options;
        this.inputWidth = inputWidth;
        this.inputHeight = inputHeight;
        this.width = (inputWidth + 1) & ~1;
        this.height = (inputHeight + 1) & ~1;
        this.codedWidth = (inputWidth + 7) & ~7;
        this.codedHeight = (inputHeight + 7) & ~7;
        this.chromaWidth = codedWidth >> 1;
        this.chromaHeight = codedHeight >> 1;
        this.bitDepth = options.bitDepth();
        this.log2CtbSize = 6;
        this.widthInCtbs = (codedWidth + 63) >> 6;
        this.heightInCtbs = (codedHeight + 63) >> 6;
        this.maxTransformDepthIntra = 1;
        this.maxTransformDepthInter = 1;
        this.stillPicture = options.profile().stillPicture();
        this.levelIdc = levelIdc;
        this.highTier = highTier;
        this.rateControl = stillPicture ? HevcRateControl.CONSTANT_QUALITY : options.rateControl();
        this.bitrate = bitrate;
        this.maxBitrate = maxBitrate;
        this.bufferBits = bufferBits;
        this.bFrames = bFrames;
        this.references = references;
        this.keyframeInterval = options.keyframeInterval().orElse(0);
        this.maxDecPicBuffering = maxDecPicBuffering;
        this.maxNumReorder = maxNumReorder;
        this.hashType = switch (options.pictureHash()) {
            case NONE -> -1;
            case MD5 -> HevcSei.HASH_MD5;
            case CRC -> HevcSei.HASH_CRC;
            case CHECKSUM -> HevcSei.HASH_CHECKSUM;
        };
        this.annexB = options.packetFormat() == HevcPacketFormat.ANNEX_B;
        this.color = options.color();
        this.speed = options.speed();
    }

    /**
     * Resolves the settings of an encoder of a picture size.
     *
     * @param width the picture width in pixels
     * @param height the picture height in pixels
     * @param options the encoder options
     * @return the settings
     * @throws IllegalArgumentException if the size is not positive or exceeds every HEVC level, or the level
     * of the options is too small for the stream
     * @throws com.github.auties00.smalt.MediaException.LimitExceeded if the picture exceeds the options'
     * limits
     */
    public static EncoderSettings of(int width, int height, HevcEncoderOptions options) {
        if (width <= 0 || height <= 0 || width > MAX_DIMENSION || height > MAX_DIMENSION) {
            throw new IllegalArgumentException("invalid picture size " + width + "x" + height);
        }
        LimitChecks.checkPicture(options.limits(), width, height);
        var codedWidth = (width + 7) & ~7;
        var codedHeight = (height + 7) & ~7;
        var still = options.profile().stillPicture();
        var quality = still || options.rateControl() == HevcRateControl.CONSTANT_QUALITY;
        var bitrate = quality ? 0 : (int) options.bitrate();
        var maxBitrate = quality ? 0 : (int) options.maxBitrate().orElse(
                options.rateControl() == HevcRateControl.CONSTANT_BITRATE ? bitrate
                        : (int) Math.min(Integer.MAX_VALUE, 2L * bitrate));
        var bufferTime = options.bufferSize().orElse(options.rateControl() == HevcRateControl.CONSTANT_BITRATE
                ? Duration.ofMillis(500) : Duration.ofSeconds(1));
        var bufferBits = quality ? 0 : bufferBits(bufferTime, maxBitrate);
        var bFrames = still || options.realtime() ? 0 : options.bFrames();
        var references = still ? 0 : switch (options.speed()) {
            case FASTEST, FAST -> 1;
            case BALANCED -> 2;
            case SLOW -> 3;
        };
        var pyramid = 0;
        for (var span = bFrames + 1; span > 1; span = (span + 1) >> 1) {
            pyramid++;
        }
        var reorder = bFrames == 0 ? 0 : pyramid;
        // the anchors kept for P pictures, the pyramid's references and the current picture
        var dpb = still ? 1 : Math.max(references, 1) + Math.max(0, pyramid - 1) + reorder + 1;
        var lumaPs = (long) codedWidth * codedHeight;
        var sampleRate = still ? 0 : (long) Math.ceil(lumaPs * options.frameRate().toDouble());
        var peak = Math.max(bitrate, maxBitrate);
        int levelIdc;
        boolean highTier;
        var level = options.level().orElse(null);
        if (level != null) {
            if (!fits(level, codedWidth, codedHeight, lumaPs, sampleRate)) {
                throw new IllegalArgumentException("picture size " + width + "x" + height + " at "
                        + options.frameRate() + " frames per second exceeds " + level);
            }
            highTier = peak > level.maxBitrate(false) && level.maxBitrate(true) > 0;
            levelIdc = level.levelIdc();
            dpb = Math.min(dpb, maxDpb(level, lumaPs));
        } else {
            HevcLevel chosen = null;
            var tier = false;
            for (var candidate : HevcLevel.values()) {
                if (!fits(candidate, codedWidth, codedHeight, lumaPs, sampleRate)) {
                    continue;
                }
                if (peak <= candidate.maxBitrate(false) && bufferBits <= candidate.maxBuffer(false)) {
                    chosen = candidate;
                    break;
                }
                if (peak <= candidate.maxBitrate(true) && bufferBits <= candidate.maxBuffer(true)) {
                    chosen = candidate;
                    tier = true;
                    break;
                }
            }
            if (chosen == null) {
                chosen = HevcLevel.LEVEL_6_2;
                if (!fits(chosen, codedWidth, codedHeight, lumaPs, 0)) {
                    throw new IllegalArgumentException("picture size " + width + "x" + height
                            + " exceeds every HEVC level");
                }
                tier = peak > chosen.maxBitrate(false);
            }
            levelIdc = chosen.levelIdc();
            highTier = tier;
            dpb = Math.min(dpb, maxDpb(chosen, lumaPs));
        }
        while (dpb < Math.max(references, 1) + Math.max(0, pyramid - 1) + reorder + 1 && references > 1) {
            references--;
        }
        return new EncoderSettings(width, height, options, levelIdc, highTier, bitrate, maxBitrate, bufferBits,
                bFrames, references, Math.max(1, dpb), reorder);
    }

    /**
     * Returns whether a picture size and sample rate fit a level.
     *
     * @param level the level whose limits apply
     * @param codedWidth the coded width
     * @param codedHeight the coded height
     * @param lumaPs the coded picture size in luma samples
     * @param sampleRate the luma samples per second, 0 when there is no rate
     * @return {@code true} when the level allows them
     */
    private static boolean fits(HevcLevel level, int codedWidth, int codedHeight, long lumaPs, long sampleRate) {
        var maxSide = Math.sqrt(8.0 * level.maxLumaPictureSize());
        return lumaPs <= level.maxLumaPictureSize() && codedWidth <= maxSide && codedHeight <= maxSide
                && sampleRate <= level.maxLumaSampleRate();
    }

    /**
     * Returns {@code maxDpbSize} of A.4.2 for a picture size.
     *
     * @param level the level whose limits apply
     * @param lumaPs the coded picture size in luma samples
     * @return the largest decoded picture buffer, in pictures
     */
    private static int maxDpb(HevcLevel level, long lumaPs) {
        long max = level.maxLumaPictureSize();
        if (lumaPs <= max >> 2) {
            return 16;
        }
        if (lumaPs <= max >> 1) {
            return 12;
        }
        if (lumaPs <= (3 * max) >> 2) {
            return 8;
        }
        return 6;
    }

    /**
     * Returns the bits a buffer of a playback time holds at a rate.
     *
     * @param size the playback time
     * @param rate the rate in bits per second
     * @return the bits, at least one, or 0 when the rate is 0
     */
    private static int bufferBits(Duration size, int rate) {
        if (rate == 0) {
            return 0;
        }
        var bits = BigInteger.valueOf(rate).multiply(BigInteger.valueOf(size.toNanos())).divide(NANOS_PER_SECOND);
        return (int) Math.clamp(bits.min(BigInteger.valueOf(Integer.MAX_VALUE)).longValue(), 1, Integer.MAX_VALUE);
    }

    /**
     * {@return the nominal frame rate}
     */
    Rational frameRate() {
        return options.frameRate();
    }

    /**
     * {@return the time base of timestamps}
     */
    Rational timeBase() {
        return options.timeBase();
    }

    /**
     * {@return the profile}
     */
    HevcProfile profile() {
        return options.profile();
    }

    /**
     * {@return the width the encoder accepts}
     */
    public int inputWidth() {
        return inputWidth;
    }

    /**
     * {@return the height the encoder accepts}
     */
    public int inputHeight() {
        return inputHeight;
    }
}
