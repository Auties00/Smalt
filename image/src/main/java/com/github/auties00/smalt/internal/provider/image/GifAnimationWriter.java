package com.github.auties00.smalt.internal.provider.image;

import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.gif.GifAnimationEncoder;
import com.github.auties00.smalt.io.MediaSink;

/**
 * Presents a {@link GifAnimationEncoder} as an
 * {@link com.github.auties00.smalt.image.AnimationWriter AnimationWriter} that writes to a sink.
 *
 * <p>GIF stores frame delays in hundredths of a second. Each frame's delay is the difference
 * between its end time and its start time, both rounded to the nearest hundredth, halves up.
 */
public final class GifAnimationWriter extends TimedAnimationWriter {
    /**
     * The largest delay GIF stores, in hundredths of a second.
     */
    private static final long MAX_DELAY = 65_535;

    /**
     * The encoder, of the canvas size.
     */
    private final GifAnimationEncoder encoder;

    /**
     * Constructs the writer.
     *
     * @param encoder the encoder, of the canvas size
     * @param width the canvas width
     * @param height the canvas height
     * @param sink the output, or {@code null} when {@link #finish()} is never called
     */
    public GifAnimationWriter(GifAnimationEncoder encoder, int width, int height, MediaSink sink) {
        super(width, height, sink);
        this.encoder = encoder;
    }

    @Override
    void writeFrame(RgbaPicture picture, long durationMicros, long startMicros, long endMicros) {
        var centiseconds = roundToCentiseconds(endMicros) - roundToCentiseconds(startMicros);
        if (centiseconds > MAX_DELAY) {
            throw new IllegalArgumentException("frame duration " + durationMicros + " exceeds what GIF stores");
        }
        encoder.addFrame(picture, (int) centiseconds);
    }

    @Override
    public void finish() {
        encoder.finish(sink);
    }

    /**
     * Rounds a non-negative time to the nearest hundredth of a second, halves up.
     *
     * @param micros the time in microseconds
     * @return the time in hundredths of a second
     */
    private static long roundToCentiseconds(long micros) {
        return micros / 10_000 + (micros % 10_000 >= 5_000 ? 1 : 0);
    }
}
