package com.github.auties00.smalt.internal.provider.video;

import com.github.auties00.smalt.image.AnimationReader;
import com.github.auties00.smalt.image.AnimationWriter;
import com.github.auties00.smalt.image.DecodedImage;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.ImageEncoderOptions;
import com.github.auties00.smalt.image.ImageFormat;
import com.github.auties00.smalt.image.ImageInfo;
import com.github.auties00.smalt.image.Images;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.spi.ImageCodecProvider;
import com.github.auties00.smalt.image.avif.AvifDecoder;
import com.github.auties00.smalt.image.avif.AvifEncoder;
import com.github.auties00.smalt.image.avif.AvifEncoderOptions;
import com.github.auties00.smalt.internal.check.LosslessDepth;

import java.util.Optional;

/**
 * Provides {@link AvifDecoder} to {@link Images} for AVIF images and image sequences, and
 * {@link AvifEncoder} for encoding them: still images losslessly or at the requested quality
 * (libavif's default, 60, when the options leave it to the format) with the
 * {@link AvifEncoderOptions#defaults()} otherwise, and animations the same way with the requested
 * loop count.
 */
public final class AvifCodecProvider implements ImageCodecProvider {
    /**
     * Constructs the provider; {@link java.util.ServiceLoader} calls this constructor.
     */
    public AvifCodecProvider() {
    }

    @Override
    public boolean decodes(ImageFormat format) {
        return format == ImageFormat.AVIF;
    }

    @Override
    public ImageInfo probe(MediaSource source) {
        return AvifDecoder.probe(source);
    }

    @Override
    public DecodedImage decode(MediaSource source, ImageDecoderOptions options) {
        return AvifDecoder.decode(source, options);
    }

    @Override
    public Optional<AnimationReader> openAnimation(MediaSource source, ImageDecoderOptions options) {
        return Optional.of(AvifDecoder.openAnimation(source, options));
    }

    @Override
    public boolean encodes(ImageFormat format) {
        return format == ImageFormat.AVIF;
    }

    @Override
    public void encode(Picture picture, ImageFormat format, ImageEncoderOptions options, MediaSink sink) {
        if (options.isLossless()) {
            picture = LosslessDepth.fit(picture, 12, "avif");
        }
        AvifEncoder.encode(picture, settings(options), sink);
    }

    @Override
    public boolean encodesAnimation(ImageFormat format) {
        return format == ImageFormat.AVIF;
    }

    @Override
    public AnimationWriter openAnimationWriter(ImageFormat format, int width, int height, ImageEncoderOptions options,
                                               MediaSink sink) {
        return AvifEncoder.openAnimationWriter(width, height,
                settings(options).toBuilder().loopCount(options.loopCount()).build(), sink);
    }

    /**
     * Maps the generic encoder options onto the AVIF encoder's.
     *
     * @param options the generic options
     * @return the AVIF options
     */
    private static AvifEncoderOptions settings(ImageEncoderOptions options) {
        if (options.isLossless()) {
            return AvifEncoderOptions.lossless();
        }
        var quality = options.quality();
        return quality.isPresent() ? AvifEncoderOptions.lossy(quality.getAsInt()) : AvifEncoderOptions.defaults();
    }
}
