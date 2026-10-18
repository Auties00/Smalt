package com.github.auties00.smalt.transcode;

import com.github.auties00.smalt.image.AnimationWriter;
import com.github.auties00.smalt.image.ImageEncoderOptions;
import com.github.auties00.smalt.image.ImageFormat;
import com.github.auties00.smalt.image.Images;
import com.github.auties00.smalt.io.MediaSink;

/**
 * Opens the animation encoders an image transcode writes animated output with.
 *
 * <p>The image transcode receives an instance instead of reaching a registry itself, so the
 * animation path can be driven by any encoder.
 */
interface AnimationWriters {
    /**
     * The writers the codec artifacts provide, through
     * {@link Images#openAnimationWriter(ImageFormat, int, int, ImageEncoderOptions, MediaSink)}.
     */
    AnimationWriters REGISTRY = new AnimationWriters() {
        @Override
        public boolean supports(ImageFormat format) {
            return Images.isAnimationEncodable(format);
        }

        @Override
        public AnimationWriter open(ImageFormat format, int width, int height, ImageEncoderOptions options,
                                    MediaSink sink) {
            return Images.openAnimationWriter(format, width, height, options, sink);
        }
    };

    /**
     * Returns whether animations of a format can be written.
     *
     * @param format the image format of the animation
     * @return {@code true} when {@link #open(ImageFormat, int, int, ImageEncoderOptions, MediaSink)}
     * accepts it
     */
    boolean supports(ImageFormat format);

    /**
     * Opens a writer for an animation.
     *
     * @param format the image format, one {@link #supports(ImageFormat)} accepts
     * @param width the canvas width in pixels
     * @param height the canvas height in pixels
     * @param options the encoder settings
     * @param sink the output; not closed by the writer
     * @return the writer, which the caller finishes and closes
     */
    AnimationWriter open(ImageFormat format, int width, int height, ImageEncoderOptions options, MediaSink sink);
}
