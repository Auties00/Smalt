package com.github.auties00.smalt.internal.provider.mpeg;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.AnimationReader;
import com.github.auties00.smalt.image.AnimationWriter;
import com.github.auties00.smalt.image.DecodedImage;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.ImageEncoderOptions;
import com.github.auties00.smalt.image.ImageFormat;
import com.github.auties00.smalt.image.ImageInfo;
import com.github.auties00.smalt.image.Images;
import com.github.auties00.smalt.image.PageReader;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.heic.HeicDecoder;
import com.github.auties00.smalt.image.heic.HeicEncoder;
import com.github.auties00.smalt.image.heic.HeicEncoderOptions;
import com.github.auties00.smalt.internal.codec.EncodeMetadata;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.spi.ImageCodecProvider;

import java.util.Optional;

/**
 * Provides {@link HeicDecoder} to {@link Images} for HEIC images and image sequences, and {@link HeicEncoder} for
 * encoding them: still images at the requested quality ({@link HeicEncoderOptions#defaults()} when the options
 * leave it to the format), and animations the same way with the requested loop count. A lossless request raises
 * {@link MediaException.Unsupported}.
 */
public final class HeicCodecProvider implements ImageCodecProvider {
    /**
     * Constructs the provider; {@link java.util.ServiceLoader} calls this constructor.
     */
    public HeicCodecProvider() {
    }

    @Override
    public boolean decodes(ImageFormat format) {
        return format == ImageFormat.HEIC;
    }

    @Override
    public ImageInfo probe(MediaSource source) {
        return HeicDecoder.probe(source);
    }

    @Override
    public DecodedImage decode(MediaSource source, ImageDecoderOptions options) {
        return HeicDecoder.decode(source, options);
    }

    @Override
    public boolean decodesRegions(ImageFormat format) {
        return true;
    }

    @Override
    public Optional<AnimationReader> openAnimation(MediaSource source, ImageDecoderOptions options) {
        return Optional.of(HeicDecoder.openAnimation(source, options));
    }

    @Override
    public Optional<PageReader> openPages(MediaSource source, ImageDecoderOptions options) {
        return Optional.of(HeicDecoder.openPages(source, options));
    }

    @Override
    public boolean encodes(ImageFormat format) {
        return format == ImageFormat.HEIC;
    }

    @Override
    public void encode(Picture picture, ImageFormat format, ImageEncoderOptions options, MediaSink sink) {
        var prepared = EncodeMetadata.color(picture, options, EncodeMetadata::rgbOnly);
        var builder = metadata(settings(options).toBuilder(), options);
        if (prepared.profile() != null) {
            builder.colorProfile(prepared.profile());
        }
        HeicEncoder.encode(prepared.picture(), builder.build(), sink);
    }

    @Override
    public boolean encodesAnimation(ImageFormat format) {
        return format == ImageFormat.HEIC;
    }

    @Override
    public AnimationWriter openAnimationWriter(ImageFormat format, int width, int height, ImageEncoderOptions options,
                                               MediaSink sink) {
        var color = EncodeMetadata.animationColor(options, true);
        var builder = metadata(settings(options).toBuilder().loopCount(options.loopCount()), options);
        if (color.profile() != null) {
            builder.colorProfile(color.profile());
        }
        return color.wrap(HeicEncoder.openAnimationWriter(width, height, builder.build(), sink));
    }

    /**
     * Adds the EXIF block, XMP packet and orientation of the generic options to the HEIC options: the orientation
     * as the {@code irot} and {@code imir} properties and in the EXIF block.
     *
     * @param builder the HEIC options
     * @param options the generic options
     * @return {@code builder}
     */
    private static HeicEncoderOptions.Builder metadata(HeicEncoderOptions.Builder builder,
                                                       ImageEncoderOptions options) {
        builder.orientation(EncodeMetadata.displayOrientation(options));
        EncodeMetadata.exif(options).ifPresent(builder::exif);
        EncodeMetadata.xmp(options).ifPresent(builder::xmp);
        return builder;
    }

    /**
     * Maps the generic encoder options onto the HEIC encoder's.
     *
     * @param options the generic options
     * @return the HEIC options
     * @throws MediaException.Unsupported if the options ask for lossless coding
     */
    private static HeicEncoderOptions settings(ImageEncoderOptions options) {
        if (options.isLossless()) {
            // TODO: lossless HEIC needs a lossless mode of the HEVC encoder (cu_transquant_bypass, and 4:4:4 in a
            //  range extension profile for RGB pictures)
            throw new MediaException.Unsupported("heic", "the HEIC encoder codes lossy images only");
        }
        var quality = options.quality();
        return quality.isPresent() ? HeicEncoderOptions.ofQuality(quality.getAsInt()) : HeicEncoderOptions.defaults();
    }
}
