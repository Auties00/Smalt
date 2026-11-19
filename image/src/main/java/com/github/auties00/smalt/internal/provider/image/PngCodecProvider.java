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
import com.github.auties00.smalt.image.color.ColorProfile;
import com.github.auties00.smalt.image.color.ProfileColorSpace;
import com.github.auties00.smalt.image.png.ApngEncoder;
import com.github.auties00.smalt.image.png.ApngEncoderOptions;
import com.github.auties00.smalt.image.png.PngDecoder;
import com.github.auties00.smalt.image.png.PngEncoder;
import com.github.auties00.smalt.image.png.PngEncoderOptions;
import com.github.auties00.smalt.internal.codec.EncodeMetadata;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.spi.ImageCodecProvider;

import java.util.Optional;

/**
 * Provides {@link PngDecoder} to {@link Images} for PNG and APNG images.
 *
 * <p>It also encodes PNG images for {@link Images}, at the default compression level whatever
 * the quality, since PNG is lossless, and animations as APNG with {@link ApngEncoder} at the
 * {@link ApngEncoderOptions#defaults()} and the requested loop count.
 */
public final class PngCodecProvider implements ImageCodecProvider {
    /**
     * Constructs the provider; {@link java.util.ServiceLoader} calls this constructor.
     */
    public PngCodecProvider() {
    }

    @Override
    public boolean decodes(ImageFormat format) {
        return format == ImageFormat.PNG;
    }

    @Override
    public ImageInfo probe(MediaSource source) {
        return PngDecoder.probe(source);
    }

    @Override
    public DecodedImage decode(MediaSource source, ImageDecoderOptions options) {
        return PngDecoder.decode(source, options);
    }

    @Override
    public Optional<AnimationReader> openAnimation(MediaSource source, ImageDecoderOptions options) {
        return Optional.of(PngDecoder.openAnimation(source, options));
    }

    @Override
    public boolean encodes(ImageFormat format) {
        return format == ImageFormat.PNG;
    }

    @Override
    public void encode(Picture picture, ImageFormat format, ImageEncoderOptions options, MediaSink sink) {
        var prepared = EncodeMetadata.color(picture, options, PngCodecProvider::records);
        var builder = PngEncoderOptions.builder();
        if (prepared.profile() != null) {
            builder.colorProfile(prepared.profile());
        }
        EncodeMetadata.exif(options).ifPresent(builder::exif);
        EncodeMetadata.xmp(options).ifPresent(builder::xmp);
        PngEncoder.encode(prepared.picture(), builder.build(), sink);
    }

    /**
     * {@return whether a PNG datastream records a profile for a picture: an RGB or gray profile for a
     * picture that does not hold inks}
     *
     * @param profile the profile of the picture
     * @param picture the picture to encode
     */
    private static boolean records(ColorProfile profile, Picture picture) {
        return !EncodeMetadata.isCmyk(picture)
                && (profile.colorSpace() == ProfileColorSpace.RGB || profile.colorSpace() == ProfileColorSpace.GRAY);
    }

    @Override
    public boolean encodesAnimation(ImageFormat format) {
        return format == ImageFormat.PNG;
    }

    @Override
    public AnimationWriter openAnimationWriter(ImageFormat format, int width, int height, ImageEncoderOptions options,
                                               MediaSink sink) {
        var color = EncodeMetadata.animationColor(options, true);
        var builder = ApngEncoderOptions.builder().loopCount(options.loopCount());
        if (color.profile() != null) {
            builder.colorProfile(color.profile());
        }
        EncodeMetadata.exif(options).ifPresent(builder::exif);
        EncodeMetadata.xmp(options).ifPresent(builder::xmp);
        var encoder = new ApngEncoder(width, height, builder.build());
        return color.wrap(new ApngAnimationWriter(encoder, width, height, sink));
    }
}
