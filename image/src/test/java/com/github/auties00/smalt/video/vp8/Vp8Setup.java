package com.github.auties00.smalt.video.vp8;

import com.github.auties00.smalt.track.Rational;

import java.time.Duration;
import java.util.OptionalInt;

/**
 * A picture size together with the {@link Vp8EncoderOptions} of an encoder, the unit the VP8
 * encoder tests configure. The positional factories take the settings in the order of libvpx's
 * real-time configuration (buffers in milliseconds, {@code 0} for "no periodic key frame") and
 * translate them exactly into options.
 *
 * @param width   the picture width
 * @param height  the picture height
 * @param options the options
 */
record Vp8Setup(int width, int height, Vp8EncoderOptions options) {
    static Vp8Setup of(int width, int height, Rational frameRate, Rational timeBase, int bitrate, int minQuantizer,
                       int maxQuantizer, int keyframeInterval, int speed, boolean errorResilient, int temporalLayers,
                       boolean dropFrames, int bufferMillis, int initialBufferMillis, int optimalBufferMillis,
                       int tokenPartitions, int threads) {
        return of(width, height, frameRate, timeBase, bitrate, minQuantizer, maxQuantizer, keyframeInterval, speed,
                errorResilient, temporalLayers, dropFrames, bufferMillis, initialBufferMillis, optimalBufferMillis,
                tokenPartitions, threads, 0);
    }

    static Vp8Setup of(int width, int height, Rational frameRate, Rational timeBase, int bitrate, int minQuantizer,
                       int maxQuantizer, int keyframeInterval, int speed, boolean errorResilient, int temporalLayers,
                       boolean dropFrames, int bufferMillis, int initialBufferMillis, int optimalBufferMillis,
                       int tokenPartitions, int threads, int encodeBreakout) {
        var builder = Vp8EncoderOptions.builder();
        if (keyframeInterval == 0) {
            builder.noPeriodicKeyframes();
        } else {
            builder.keyframeInterval(keyframeInterval);
        }
        return new Vp8Setup(width, height, builder
                .frameRate(frameRate)
                .timeBase(timeBase)
                .bitrate(bitrate)
                .quantizers(minQuantizer, maxQuantizer)
                .speed(Vp8EncoderSpeed.of(speed))
                .errorResilient(errorResilient)
                .temporalLayers(temporalLayers)
                .dropFrames(dropFrames)
                .bufferSize(Duration.ofMillis(bufferMillis))
                .initialBufferLevel(Duration.ofMillis(initialBufferMillis))
                .optimalBufferLevel(Duration.ofMillis(optimalBufferMillis))
                .tokenPartitions(tokenPartitions)
                .threads(threads)
                .encodeBreakout(encodeBreakout)
                .rateTolerance(100, 100)
                .noMaxKeyframeBitrate()
                .build());
    }

    /**
     * Returns the real-time preset at a size, frame rate and bitrate, with WebRTC's key frame
     * target limit for the frame rate.
     */
    static Vp8Setup realtime(int width, int height, Rational frameRate, int bitrate) {
        var limit = Math.clamp(Math.round(600 * frameRate.toDouble() / 20), 100, Integer.MAX_VALUE);
        return new Vp8Setup(width, height, Vp8EncoderOptions.forRealtime().toBuilder()
                .frameRate(frameRate)
                .bitrate(bitrate)
                .maxKeyframeBitratePercent(limit)
                .build());
    }

    Vp8Setup withBitrate(int bitrate) {
        return new Vp8Setup(width, height, options.toBuilder().bitrate(bitrate).build());
    }

    Vp8Setup withEncodeBreakout(int encodeBreakout) {
        return new Vp8Setup(width, height, options.toBuilder().encodeBreakout(encodeBreakout).build());
    }

    Vp8Setup withRateTolerance(int undershootPercent, int overshootPercent) {
        return new Vp8Setup(width, height, options.toBuilder().rateTolerance(undershootPercent, overshootPercent)
                .build());
    }

    Vp8Setup withMaxKeyframeBitratePercent(int percent) {
        return new Vp8Setup(width, height, options.toBuilder().maxKeyframeBitratePercent(percent).build());
    }

    Rational frameRate() {
        return options.frameRate();
    }

    Rational timeBase() {
        return options.timeBase();
    }

    int bitrate() {
        return (int) options.bitrate();
    }

    int minQuantizer() {
        return options.minQuantizer();
    }

    int maxQuantizer() {
        return options.maxQuantizer();
    }

    int keyframeInterval() {
        return options.keyframeInterval().orElse(0);
    }

    int speed() {
        return options.speed().level();
    }

    boolean errorResilient() {
        return options.errorResilient();
    }

    int temporalLayers() {
        return options.temporalLayers();
    }

    boolean dropFrames() {
        return options.dropFrames();
    }

    int bufferMillis() {
        return (int) options.bufferSize().toMillis();
    }

    int initialBufferMillis() {
        return (int) options.initialBufferLevel().toMillis();
    }

    int optimalBufferMillis() {
        return (int) options.optimalBufferLevel().toMillis();
    }

    int tokenPartitions() {
        return options.tokenPartitions();
    }

    int threads() {
        return options.threads();
    }

    int encodeBreakout() {
        return options.encodeBreakout();
    }

    int undershootPercent() {
        return options.undershootPercent();
    }

    int overshootPercent() {
        return options.overshootPercent();
    }

    OptionalInt maxKeyframeBitratePercent() {
        return options.maxKeyframeBitratePercent();
    }

    /**
     * Creates an encoder with these settings.
     */
    Vp8Encoder encoder() {
        return new Vp8Encoder(width, height, options);
    }
}
