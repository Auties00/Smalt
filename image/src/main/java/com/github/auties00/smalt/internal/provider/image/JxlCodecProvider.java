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
import com.github.auties00.smalt.image.jxl.JxlAnimationEncoder;
import com.github.auties00.smalt.image.jxl.JxlDecoder;
import com.github.auties00.smalt.image.jxl.JxlEncoder;
import com.github.auties00.smalt.image.jxl.JxlEncoderOptions;
import com.github.auties00.smalt.internal.codec.EncodeMetadata;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.spi.ImageCodecProvider;

import java.util.Optional;

/**
 * Provides {@link JxlDecoder} and {@link JxlEncoder} to {@link Images} for JPEG XL files.
 *
 * <p>Decoding returns the first displayed frame, and every displayed frame opens as an animation. Encoding is
 * lossless when the options ask for it, and otherwise lossy at the distance {@code cjxl -q} gives the requested
 * quality, or at distance 1 when the options leave the quality to the format; animations are encoded with
 * {@link JxlAnimationEncoder} the same way, with the requested loop count. Color profiles of the picture's color
 * model are embedded, EXIF and XMP metadata go in their container boxes and the orientation in the codestream
 * header.
 */
public final class JxlCodecProvider implements ImageCodecProvider {
    /**
     * Constructs the provider; {@link java.util.ServiceLoader} calls this constructor.
     */
    public JxlCodecProvider() {
    }

    @Override
    public boolean decodes(ImageFormat format) {
        return format == ImageFormat.JXL;
    }

    @Override
    public ImageInfo probe(MediaSource source) {
        return JxlDecoder.probe(source);
    }

    @Override
    public DecodedImage decode(MediaSource source, ImageDecoderOptions options) {
        return JxlDecoder.decode(source, options);
    }

    @Override
    public Optional<AnimationReader> openAnimation(MediaSource source, ImageDecoderOptions options) {
        return Optional.of(JxlDecoder.openAnimation(source, options));
    }

    @Override
    public boolean encodes(ImageFormat format) {
        return format == ImageFormat.JXL;
    }

    @Override
    public void encode(Picture picture, ImageFormat format, ImageEncoderOptions options, MediaSink sink) {
        var prepared = EncodeMetadata.color(picture, options,
                (profile, p) -> EncodeMetadata.describes(profile, EncodeMetadata.codedModel(p)));
        var builder = settings(options).toBuilder();
        if (prepared.profile() != null) {
            builder.colorProfile(prepared.profile());
        }
        EncodeMetadata.exif(options).ifPresent(builder::exif);
        EncodeMetadata.xmp(options).ifPresent(builder::xmp);
        builder.orientation(EncodeMetadata.displayOrientation(options));
        JxlEncoder.encode(prepared.picture(), builder.build(), sink);
    }

    @Override
    public boolean encodesAnimation(ImageFormat format) {
        return format == ImageFormat.JXL;
    }

    @Override
    public AnimationWriter openAnimationWriter(ImageFormat format, int width, int height, ImageEncoderOptions options,
                                               MediaSink sink) {
        var color = EncodeMetadata.animationColor(options, true);
        var builder = settings(options).toBuilder().loopCount(options.loopCount())
                .orientation(EncodeMetadata.displayOrientation(options));
        if (color.profile() != null) {
            builder.colorProfile(color.profile());
        }
        EncodeMetadata.exif(options).ifPresent(builder::exif);
        EncodeMetadata.xmp(options).ifPresent(builder::xmp);
        return color.wrap(JxlAnimationEncoder.openAnimationWriter(width, height, builder.build(), sink));
    }

    private static JxlEncoderOptions settings(ImageEncoderOptions options) {
        if (options.isLossless()) {
            return JxlEncoderOptions.lossless();
        }
        var quality = options.quality();
        return quality.isPresent() ? JxlEncoderOptions.ofQuality(quality.getAsInt()) : JxlEncoderOptions.defaults();
    }
}
