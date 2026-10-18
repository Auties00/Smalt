package com.github.auties00.smalt.video.h264.encoder.internal;

import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.video.h264.encoder.H264EncoderOptions;
import com.github.auties00.smalt.video.h264.encoder.H264EncoderSpeed;
import com.github.auties00.smalt.video.h264.encoder.H264Level;
import com.github.auties00.smalt.video.h264.encoder.H264PacketFormat;
import com.github.auties00.smalt.video.h264.encoder.H264Profile;
import com.github.auties00.smalt.video.h264.encoder.H264RateControl;
import com.github.auties00.smalt.video.h264.internal.H264Levels;

import java.math.BigInteger;
import java.time.Duration;

/**
 * The resolved settings of one encoder: the picture size together with its
 * {@link H264EncoderOptions}, with the optional settings reduced to the values the engine works
 * with. Instances come only from {@link #of}, which validates them.
 *
 * @param width the picture width in pixels, from 1 to 16384
 * @param height the picture height in pixels, from 1 to 16384
 * @param frameRate the nominal frame rate in frames per second
 * @param timeBase the unit of the timestamps of accepted frames and produced packets
 * @param profile the H.264 profile
 * @param level the {@code level_idc} to signal, or 0 to choose the lowest level that fits
 * @param rateControl the rate control mode
 * @param bitrate the target bitrate in bits per second; 0 for
 * {@link H264RateControl#CONSTANT_QUALITY}
 * @param maxBitrate the rate at which the buffering verifier fills in bits per second, or 0 for
 * the default of the mode
 * @param bufferSize the buffering verifier size in bits, or 0 for the default of the mode
 * @param quality the quantizer of {@link H264RateControl#CONSTANT_QUALITY}, from 0 to 51
 * @param keyframeInterval the maximum number of frames between two IDR pictures, or 0 for no
 * periodic IDR
 * @param speed the speed and compression trade-off
 * @param color the color description
 * @param threads the most threads that analyze the macroblock rows of a picture, from 1 to 64
 * @param packetFormat how the NAL units of a packet are delimited
 * @param minQuantizer the smallest quantizer
 * @param maxQuantizer the largest quantizer
 * @param realtime whether the encoder is tuned for interactive transmission
 * @param lookahead the number of pictures analyzed ahead of the one being coded
 * @param bFrames the number of consecutive B pictures between anchors
 */
public record EncoderConfig(int width, int height, Rational frameRate, Rational timeBase, H264Profile profile,
                            int level, H264RateControl rateControl, int bitrate, int maxBitrate, int bufferSize,
                            int quality, int keyframeInterval, H264EncoderSpeed speed, ColorInfo color, int threads,
                            H264PacketFormat packetFormat, int minQuantizer, int maxQuantizer, boolean realtime,
                            int lookahead, int bFrames) {
    /**
     * The largest accepted picture side in pixels.
     */
    private static final int MAX_DIMENSION = 16_384;

    private static final BigInteger NANOS_PER_SECOND = BigInteger.valueOf(1_000_000_000L);

    /**
     * Resolves the settings of an encoder of a picture size.
     *
     * <p>The buffering verifier size is the playback time of {@link H264EncoderOptions#bufferSize()}
     * at the effective maximum bitrate, rounded down to whole bits and at least one bit.
     *
     * @param width the picture width in pixels
     * @param height the picture height in pixels
     * @param options the encoder settings, validated by their builder
     * @return the settings
     * @throws IllegalArgumentException if the picture size is outside 1 to 16384 or exceeds every
     * H.264 level
     * @throws com.github.auties00.smalt.MediaException.LimitExceeded if the picture exceeds the
     * options' limits
     */
    public static EncoderConfig of(int width, int height, H264EncoderOptions options) {
        if (width <= 0 || height <= 0 || width > MAX_DIMENSION || height > MAX_DIMENSION) {
            throw new IllegalArgumentException("invalid picture size " + width + "x" + height);
        }
        LimitChecks.checkPicture(options.limits(), width, height);
        if ((long) ((width + 15) >> 4) * ((height + 15) >> 4) > H264Levels.HIGHEST.maxFs()) {
            throw new IllegalArgumentException("picture size " + width + "x" + height + " exceeds every H.264 level");
        }
        var rateControl = options.rateControl();
        var quality = rateControl == H264RateControl.CONSTANT_QUALITY;
        var bitrate = quality ? 0 : (int) options.bitrate();
        var maxBitrate = (int) options.maxBitrate().orElse(0);
        var bufferSize = options.bufferSize()
                .map(size -> bufferBits(size, effectiveMaxBitrate(rateControl, bitrate, maxBitrate)))
                .orElse(0);
        return new EncoderConfig(width, height, options.frameRate(), options.timeBase(), options.profile(),
                options.level().map(H264Level::levelIdc).orElse(0), rateControl, bitrate, maxBitrate, bufferSize,
                options.quality(), options.keyframeInterval().orElse(0), options.speed(), options.color(),
                options.threads(), options.packetFormat(), options.minQuantizer(), options.maxQuantizer(),
                options.realtime(), options.lookahead(), options.bFrames());
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
     * Returns the rate at which the buffering verifier fills, resolving the 0 default.
     *
     * @param rateControl the rate control mode
     * @param bitrate the target bitrate
     * @param maxBitrate the explicit maximum bitrate, or 0
     * @return the maximum bitrate in bits per second, or 0 for
     * {@link H264RateControl#CONSTANT_QUALITY} without an explicit value
     */
    private static int effectiveMaxBitrate(H264RateControl rateControl, int bitrate, int maxBitrate) {
        if (maxBitrate != 0 || rateControl == H264RateControl.CONSTANT_QUALITY) {
            return maxBitrate;
        }
        return rateControl == H264RateControl.CONSTANT_BITRATE
                ? bitrate
                : (int) Math.min(Integer.MAX_VALUE, 2L * bitrate);
    }

    /**
     * {@return the rate at which the buffering verifier fills, in bits per second, resolving the 0
     * default; 0 for {@link H264RateControl#CONSTANT_QUALITY} without an explicit value}
     */
    public int effectiveMaxBitrate() {
        return effectiveMaxBitrate(rateControl, bitrate, maxBitrate);
    }

    /**
     * {@return the buffering verifier size in bits, resolving the 0 default; 0 for
     * {@link H264RateControl#CONSTANT_QUALITY} without an explicit value}
     */
    public int effectiveBufferSize() {
        if (bufferSize != 0 || rateControl == H264RateControl.CONSTANT_QUALITY) {
            return bufferSize;
        }
        var rate = (long) effectiveMaxBitrate();
        return (int) Math.max(1, Math.min(Integer.MAX_VALUE,
                rateControl == H264RateControl.CONSTANT_BITRATE ? rate / 2 : 2 * rate));
    }
}
