package com.github.auties00.smalt.video.vp9;

import com.github.auties00.smalt.track.Rational;

import java.time.Duration;

/**
 * A picture size together with the {@link Vp9EncoderOptions} of an encoder, the unit the VP9
 * encoder tests configure. The positional factory takes the settings in the order of libvpx's
 * real-time configuration (buffers in milliseconds, {@code 0} for "no periodic key frame") and
 * translates them exactly into options.
 *
 * @param width   the picture width
 * @param height  the picture height
 * @param options the options
 */
public record Vp9Setup(int width, int height, Vp9EncoderOptions options) {
    public static Vp9Setup of(int width, int height, Rational frameRate, Rational timeBase, int bitrate,
                              int minQuantizer, int maxQuantizer, int keyframeInterval, int speed,
                              boolean errorResilient, boolean adaptiveQuantization, int temporalLayers,
                              boolean dropFrames, int bufferMillis, int initialBufferMillis, int optimalBufferMillis,
                              int tileColumnsLog2, int threads) {
        var builder = Vp9EncoderOptions.builder()
                .frameRate(frameRate)
                .timeBase(timeBase)
                .bitrate(bitrate)
                .quantizers(minQuantizer, maxQuantizer)
                .speed(Vp9EncoderSpeed.of(speed))
                .errorResilient(errorResilient)
                .adaptiveQuantization(adaptiveQuantization)
                .temporalLayers(temporalLayers)
                .dropFrames(dropFrames)
                .bufferSize(Duration.ofMillis(bufferMillis))
                .initialBufferLevel(Duration.ofMillis(initialBufferMillis))
                .optimalBufferLevel(Duration.ofMillis(optimalBufferMillis))
                .tileColumnsLog2(tileColumnsLog2)
                .threads(threads);
        if (keyframeInterval == 0) {
            builder.noPeriodicKeyframes();
        } else {
            builder.keyframeInterval(keyframeInterval);
        }
        return new Vp9Setup(width, height, builder.build());
    }

    /**
     * Returns the real-time preset at a size, frame rate and bitrate.
     */
    public static Vp9Setup realtime(int width, int height, Rational frameRate, int bitrate) {
        return new Vp9Setup(width, height, Vp9EncoderOptions.forRealtime().toBuilder().frameRate(frameRate)
                .bitrate(bitrate).build());
    }

    public Vp9Setup withBitrate(int bitrate) {
        return new Vp9Setup(width, height, options.toBuilder().bitrate(bitrate).build());
    }

    public Rational frameRate() {
        return options.frameRate();
    }

    public Rational timeBase() {
        return options.timeBase();
    }

    public int bitrate() {
        return (int) options.bitrate();
    }

    public int minQuantizer() {
        return options.minQuantizer();
    }

    public int maxQuantizer() {
        return options.maxQuantizer();
    }

    public int keyframeInterval() {
        return options.keyframeInterval().orElse(0);
    }

    public int speed() {
        return options.speed().level();
    }

    public boolean errorResilient() {
        return options.errorResilient();
    }

    public boolean adaptiveQuantization() {
        return options.adaptiveQuantization();
    }

    public int temporalLayers() {
        return options.temporalLayers();
    }

    public boolean dropFrames() {
        return options.dropFrames();
    }

    public int bufferMillis() {
        return (int) options.bufferSize().toMillis();
    }

    public int initialBufferMillis() {
        return (int) options.initialBufferLevel().toMillis();
    }

    public int optimalBufferMillis() {
        return (int) options.optimalBufferLevel().toMillis();
    }

    public int tileColumnsLog2() {
        return options.tileColumnsLog2();
    }

    public int threads() {
        return options.threads();
    }

    /**
     * Creates an encoder with these settings.
     */
    public Vp9Encoder encoder() {
        return new Vp9Encoder(width, height, options);
    }
}
