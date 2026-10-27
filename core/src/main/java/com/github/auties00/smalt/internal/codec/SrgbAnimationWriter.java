package com.github.auties00.smalt.internal.codec;

import com.github.auties00.smalt.image.AnimationWriter;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.color.ColorTransform;

import java.util.Objects;

/**
 * Converts the frames written to an animation writer to sRGB, for a format that cannot record the color
 * profile of the frames.
 *
 * <p>Instances are not thread-safe.
 */
public final class SrgbAnimationWriter implements AnimationWriter {
    /**
     * The writer the converted frames go to.
     */
    private final AnimationWriter writer;

    /**
     * The conversion of the frames, into one reused picture.
     */
    private final EncodeMetadata.FrameConverter converter;

    /**
     * Constructs the writer.
     *
     * @param writer the writer the converted frames go to; closed with this one
     * @param transform the conversion of every frame to sRGB
     */
    public SrgbAnimationWriter(AnimationWriter writer, ColorTransform transform) {
        this.writer = writer;
        this.converter = new EncodeMetadata.FrameConverter(transform);
    }

    @Override
    public int width() {
        return writer.width();
    }

    @Override
    public int height() {
        return writer.height();
    }

    @Override
    public void write(RgbaPicture picture, long durationMicros) {
        writer.write(converter.convert(Objects.requireNonNull(picture, "picture")), durationMicros);
    }

    @Override
    public void finish() {
        writer.finish();
    }

    @Override
    public void close() {
        writer.close();
    }
}
