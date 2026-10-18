package com.github.auties00.smalt.video.h264.encoder;

import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.video.h264.encoder.internal.EncoderConfig;

import java.time.Duration;

/**
 * A picture size together with the {@link H264EncoderOptions} of an encoder, the unit the encoder
 * tests configure. The positional factories take the settings in the order of the stream
 * parameters the tests were written against (bitrates and buffer sizes in bits, {@code 0} for
 * "default" or "none") and translate them exactly into options; {@link #resolved()} exposes the
 * values the engine derives from them.
 *
 * @param width   the picture width
 * @param height  the picture height
 * @param options the options
 */
public record H264Setup(int width, int height, H264EncoderOptions options) {
    /**
     * Translates positional stream parameters with length-prefixed packets, the full quantizer
     * window and real-time tuning off.
     */
    public static H264Setup of(int width, int height, Rational frameRate, Rational timeBase, H264Profile profile,
                               int level, H264RateControl rateControl, int bitrate, int maxBitrate, int bufferSize,
                               int quality, int keyframeInterval, H264EncoderSpeed speed, ColorInfo color,
                               int threads) {
        return of(width, height, frameRate, timeBase, profile, level, rateControl, bitrate, maxBitrate, bufferSize,
                quality, keyframeInterval, speed, color, threads, H264PacketFormat.LENGTH_PREFIXED, 0, 51, false);
    }

    /**
     * Translates every positional stream parameter.
     */
    public static H264Setup of(int width, int height, Rational frameRate, Rational timeBase, H264Profile profile,
                               int level, H264RateControl rateControl, int bitrate, int maxBitrate, int bufferSize,
                               int quality, int keyframeInterval, H264EncoderSpeed speed, ColorInfo color,
                               int threads, H264PacketFormat packetFormat, int minQuantizer, int maxQuantizer,
                               boolean realtime) {
        var builder = H264EncoderOptions.builder()
                .frameRate(frameRate)
                .timeBase(timeBase)
                .profile(profile)
                .rateControl(rateControl)
                .quality(quality)
                .speed(speed)
                .color(color)
                .threads(threads)
                .packetFormat(packetFormat)
                .quantizers(minQuantizer, maxQuantizer)
                .realtime(realtime)
                .lookahead(0)
                .bFrames(0)
                .automaticBufferSize();
        if (keyframeInterval == 0) {
            builder.noPeriodicKeyframes();
        } else {
            builder.keyframeInterval(keyframeInterval);
        }
        if (level != 0) {
            builder.level(level(level));
        }
        if (rateControl != H264RateControl.CONSTANT_QUALITY || bitrate != 0) {
            builder.bitrate(bitrate);
        }
        if (maxBitrate != 0) {
            builder.maxBitrate(maxBitrate);
        }
        if (bufferSize != 0) {
            var rate = maxBitrate != 0 ? maxBitrate : switch (rateControl) {
                case CONSTANT_BITRATE -> bitrate;
                case AVERAGE_BITRATE -> (int) Math.min(Integer.MAX_VALUE, 2L * bitrate);
                case CONSTANT_QUALITY -> throw new IllegalArgumentException("a buffer needs a maximum bitrate");
            };
            // the smallest playback time whose bits at the rate round down to exactly bufferSize
            var nanos = Math.ceilDiv((long) bufferSize * 1_000_000_000L, rate);
            builder.bufferSize(Duration.ofNanos(nanos));
        }
        return new H264Setup(width, height, builder.build());
    }

    /**
     * Returns the level with a {@code level_idc}.
     */
    private static H264Level level(int idc) {
        for (var level : H264Level.values()) {
            if (level.levelIdc() == idc) {
                return level;
            }
        }
        throw new IllegalArgumentException("unknown level " + idc);
    }

    /**
     * Returns the real-time preset at a size, frame rate and bitrate.
     */
    public static H264Setup realtime(int width, int height, Rational frameRate, int bitrate) {
        return new H264Setup(width, height, H264EncoderOptions.forRealtime().toBuilder().frameRate(frameRate)
                .bitrate(bitrate).build());
    }

    /**
     * Returns the file preset at a size, frame rate and bitrate, with an IDR picture every two
     * seconds and the time base {@code 1/frameRate}: the settings the codec registry uses for
     * files.
     */
    public static H264Setup file(int width, int height, Rational frameRate, int bitrate) {
        var keyframes = (int) Math.max(1, Math.min(Integer.MAX_VALUE, Math.round(2 * frameRate.toDouble())));
        return new H264Setup(width, height, H264EncoderOptions.forFile().toBuilder()
                .frameRate(frameRate)
                .timeBase(frameRate.invert())
                .bitrate(bitrate)
                .keyframeInterval(keyframes)
                .build());
    }

    public H264Setup withThreads(int threads) {
        return new H264Setup(width, height, options.toBuilder().threads(threads).build());
    }

    public H264Setup withColor(ColorInfo color) {
        return new H264Setup(width, height, options.toBuilder().color(color).build());
    }

    public H264Setup withKeyframeInterval(int keyframeInterval) {
        return new H264Setup(width, height, keyframeInterval == 0
                ? options.toBuilder().noPeriodicKeyframes().build()
                : options.toBuilder().keyframeInterval(keyframeInterval).build());
    }

    public H264Setup withQuantizerWindow(int minQuantizer, int maxQuantizer) {
        return new H264Setup(width, height, options.toBuilder().quantizers(minQuantizer, maxQuantizer).build());
    }

    public H264Setup withPacketFormat(H264PacketFormat packetFormat) {
        return new H264Setup(width, height, options.toBuilder().packetFormat(packetFormat).build());
    }

    public H264Setup withRealtime(boolean realtime) {
        return new H264Setup(width, height, options.toBuilder().realtime(realtime).build());
    }

    public Rational frameRate() {
        return options.frameRate();
    }

    public Rational timeBase() {
        return options.timeBase();
    }

    public H264RateControl rateControl() {
        return options.rateControl();
    }

    /**
     * Returns the settings the engine derives from the size and options.
     */
    public EncoderConfig resolved() {
        return EncoderConfig.of(width, height, options);
    }

    public int effectiveMaxBitrate() {
        return resolved().effectiveMaxBitrate();
    }

    public int effectiveBufferSize() {
        return resolved().effectiveBufferSize();
    }

    /**
     * Creates an encoder with these settings.
     */
    public H264Encoder encoder() {
        return new H264Encoder(width, height, options);
    }
}
