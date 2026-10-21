package com.github.auties00.smalt.internal.provider.image;

import com.github.auties00.smalt.image.AnimationReader;
import com.github.auties00.smalt.image.AnimationWriter;
import com.github.auties00.smalt.image.DecodedImage;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.ImageEncoderOptions;
import com.github.auties00.smalt.image.ImageFormat;
import com.github.auties00.smalt.image.ImageInfo;
import com.github.auties00.smalt.image.Images;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.webp.WebpAnimationEncoder;
import com.github.auties00.smalt.image.webp.WebpAnimationEncoderOptions;
import com.github.auties00.smalt.image.webp.WebpDecoder;
import com.github.auties00.smalt.image.webp.WebpEncoder;
import com.github.auties00.smalt.image.webp.WebpEncoderOptions;
import com.github.auties00.smalt.internal.check.LosslessDepth;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.spi.ImageCodecProvider;

import java.util.Optional;

/**
 * Provides {@link WebpDecoder} to {@link Images} for WebP images.
 *
 * <p>It also encodes WebP images for {@link Images}: losslessly with the
 * {@code cwebp -lossless} defaults, or lossily at the requested quality
 * ({@value #DEFAULT_QUALITY} by default) with the {@code cwebp} defaults. Animations are encoded
 * with {@link WebpAnimationEncoder} the same way, with the {@code gif2webp} defaults of
 * {@link WebpAnimationEncoderOptions#lossless()} or {@link WebpAnimationEncoderOptions#lossy(int)}
 * and the requested loop count.
 */
public final class WebpCodecProvider implements ImageCodecProvider {
    /**
     * The lossy quality used when the options leave it to the format, {@code cwebp}'s default.
     */
    private static final int DEFAULT_QUALITY = 75;

    /**
     * Constructs the provider; {@link java.util.ServiceLoader} calls this constructor.
     */
    public WebpCodecProvider() {
    }

    @Override
    public boolean decodes(ImageFormat format) {
        return format == ImageFormat.WEBP;
    }

    @Override
    public ImageInfo probe(MediaSource source) {
        return WebpDecoder.probe(source);
    }

    @Override
    public DecodedImage decode(MediaSource source, ImageDecoderOptions options) {
        return WebpDecoder.decode(source, options);
    }

    @Override
    public Optional<AnimationReader> openAnimation(MediaSource source, ImageDecoderOptions options) {
        return Optional.of(WebpDecoder.openAnimation(source, options));
    }

    @Override
    public boolean encodes(ImageFormat format) {
        return format == ImageFormat.WEBP;
    }

    @Override
    public void encode(Picture picture, ImageFormat format, ImageEncoderOptions options, MediaSink sink) {
        if (options.isLossless()) {
            picture = LosslessDepth.fit(picture, 8, "webp");
        }
        var settings = options.isLossless()
                ? WebpEncoderOptions.lossless()
                : WebpEncoderOptions.lossy(options.quality().orElse(DEFAULT_QUALITY));
        WebpEncoder.encode(picture, settings, sink);
    }

    @Override
    public boolean encodesAnimation(ImageFormat format) {
        return format == ImageFormat.WEBP;
    }

    @Override
    public AnimationWriter openAnimationWriter(ImageFormat format, int width, int height, ImageEncoderOptions options,
                                               MediaSink sink) {
        var settings = options.isLossless()
                ? WebpAnimationEncoderOptions.lossless()
                : WebpAnimationEncoderOptions.lossy(options.quality().orElse(DEFAULT_QUALITY));
        var encoder = new WebpAnimationEncoder(width, height, settings.toBuilder().loopCount(options.loopCount())
                .build());
        return new WebpAnimationWriter(encoder, width, height, sink);
    }
}
