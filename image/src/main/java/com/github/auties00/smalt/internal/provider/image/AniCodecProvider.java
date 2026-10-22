package com.github.auties00.smalt.internal.provider.image;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.AnimationReader;
import com.github.auties00.smalt.image.AnimationWriter;
import com.github.auties00.smalt.image.DecodedImage;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.ImageEncoderOptions;
import com.github.auties00.smalt.image.ImageFormat;
import com.github.auties00.smalt.image.ImageInfo;
import com.github.auties00.smalt.image.Images;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.ico.AniDecoder;
import com.github.auties00.smalt.image.ico.AniEncoder;
import com.github.auties00.smalt.image.ico.AniEncoderOptions;
import com.github.auties00.smalt.image.ico.IcoEncoderOptions;
import com.github.auties00.smalt.internal.check.LosslessDepth;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.spi.ImageCodecProvider;

import java.util.Optional;

/**
 * Provides {@link AniDecoder} and {@link AniEncoder} to {@link Images} for animated cursors.
 *
 * <p>Decoding returns the first step and opens the steps as an animation. An encoded picture becomes
 * a one-frame animated cursor at its own size, and an animation one step per frame; a picture or
 * canvas larger than 256 pixels on a side is scaled into 256-pixel square entries, as an icon is. An
 * animated cursor always plays forever, so a loop count other than 0 is unsupported.
 */
public final class AniCodecProvider implements ImageCodecProvider {
    /**
     * The frame options that scale a large picture into a 256-pixel square entry.
     */
    private static final AniEncoderOptions SCALED = AniEncoderOptions.builder()
            .frameOptions(IcoEncoderOptions.builder().sizes(256).build()).build();

    /**
     * Constructs the provider; {@link java.util.ServiceLoader} calls this constructor.
     */
    public AniCodecProvider() {
    }

    @Override
    public boolean decodes(ImageFormat format) {
        return format == ImageFormat.ANI;
    }

    @Override
    public ImageInfo probe(MediaSource source) {
        return AniDecoder.probe(source);
    }

    @Override
    public DecodedImage decode(MediaSource source, ImageDecoderOptions options) {
        return AniDecoder.decode(source, options);
    }

    @Override
    public Optional<AnimationReader> openAnimation(MediaSource source, ImageDecoderOptions options) {
        return Optional.of(AniDecoder.openAnimation(source, options));
    }

    @Override
    public boolean encodes(ImageFormat format) {
        return format == ImageFormat.ANI;
    }

    @Override
    public void encode(Picture picture, ImageFormat format, ImageEncoderOptions options, MediaSink sink) {
        if (options.isLossless()) {
            picture = LosslessDepth.fit(picture, 8, "ani");
        }
        AniEncoder.encode(picture, settings(picture.width(), picture.height()), sink);
    }

    @Override
    public boolean encodesAnimation(ImageFormat format) {
        return format == ImageFormat.ANI;
    }

    @Override
    public AnimationWriter openAnimationWriter(ImageFormat format, int width, int height, ImageEncoderOptions options,
                                               MediaSink sink) {
        if (options.loopCount() != 0) {
            throw new MediaException.Unsupported("ani", "an animated cursor always plays forever, not "
                    + options.loopCount() + " times");
        }
        return AniEncoder.openAnimationWriter(width, height, settings(width, height), sink);
    }

    /**
     * Returns the settings for pictures of a size.
     *
     * @param width the picture width
     * @param height the picture height
     * @return the defaults, or settings scaling into a 256-pixel square entry when a side exceeds 256
     */
    private static AniEncoderOptions settings(int width, int height) {
        return width > 256 || height > 256 ? SCALED : AniEncoderOptions.defaults();
    }
}
