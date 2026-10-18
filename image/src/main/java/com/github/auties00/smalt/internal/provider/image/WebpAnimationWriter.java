package com.github.auties00.smalt.internal.provider.image;

import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.webp.WebpAnimationEncoder;
import com.github.auties00.smalt.image.webp.internal.anim.AnimationEncoderCore;
import com.github.auties00.smalt.io.MediaSink;

/**
 * Presents a {@link WebpAnimationEncoder} as an
 * {@link com.github.auties00.smalt.image.AnimationWriter AnimationWriter} that writes to a sink.
 *
 * <p>WebP stores frame durations in whole milliseconds, below {@link AnimationEncoderCore#MAX_DURATION}.
 * Each frame's duration is the difference between its end time and its start time, both rounded to
 * the nearest millisecond, halves up.
 */
public final class WebpAnimationWriter extends TimedAnimationWriter {
    /**
     * The encoder, of the canvas size.
     */
    private final WebpAnimationEncoder encoder;

    /**
     * Constructs the writer.
     *
     * @param encoder the encoder, of the canvas size
     * @param width the canvas width
     * @param height the canvas height
     * @param sink the output, or {@code null} when {@link #finish()} is never called
     */
    public WebpAnimationWriter(WebpAnimationEncoder encoder, int width, int height, MediaSink sink) {
        super(width, height, sink);
        this.encoder = encoder;
    }

    @Override
    void writeFrame(RgbaPicture picture, long durationMicros, long startMicros, long endMicros) {
        var millis = roundToMillis(endMicros) - roundToMillis(startMicros);
        if (millis >= AnimationEncoderCore.MAX_DURATION) {
            throw new IllegalArgumentException("frame duration " + durationMicros + " exceeds what WebP stores");
        }
        encoder.addFrame(picture, (int) millis);
    }

    @Override
    public void finish() {
        encoder.finish(sink);
    }

    /**
     * Rounds a non-negative time to the nearest millisecond, halves up.
     *
     * @param micros the time in microseconds
     * @return the time in milliseconds
     */
    private static long roundToMillis(long micros) {
        return micros / 1000 + (micros % 1000 >= 500 ? 1 : 0);
    }
}
