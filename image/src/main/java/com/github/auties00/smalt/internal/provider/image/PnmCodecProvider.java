package com.github.auties00.smalt.internal.provider.image;

import com.github.auties00.smalt.image.ChannelFloatPicture;
import com.github.auties00.smalt.image.DecodedImage;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.ImageEncoderOptions;
import com.github.auties00.smalt.image.ImageFormat;
import com.github.auties00.smalt.image.ImageInfo;
import com.github.auties00.smalt.image.Images;
import com.github.auties00.smalt.image.PageReader;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.pnm.PnmDecoder;
import com.github.auties00.smalt.image.pnm.PnmEncoder;
import com.github.auties00.smalt.image.pnm.PnmEncoderOptions;
import com.github.auties00.smalt.image.pnm.PnmVariant;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.spi.ImageCodecProvider;

import java.util.Optional;

/**
 * Provides {@link PnmDecoder} and {@link PnmEncoder} to {@link Images} for Netpbm images and portable float maps.
 *
 * <p>Every image of a file opens as a page. A floating-point picture is encoded as a PFM of linear light, converted
 * from the options' color profile (sRGB without one); any other picture is converted to sRGB and encoded with the
 * automatic variant of {@link PnmEncoderOptions#defaults()}, losslessly at its own depth up to 16 bits, whatever the
 * quality.
 */
public final class PnmCodecProvider implements ImageCodecProvider {
    /**
     * Constructs the provider; {@link java.util.ServiceLoader} calls this constructor.
     */
    public PnmCodecProvider() {
    }

    @Override
    public boolean decodes(ImageFormat format) {
        return format == ImageFormat.PNM;
    }

    @Override
    public ImageInfo probe(MediaSource source) {
        return PnmDecoder.probe(source);
    }

    @Override
    public DecodedImage decode(MediaSource source, ImageDecoderOptions options) {
        return PnmDecoder.decode(source, options);
    }

    @Override
    public Optional<PageReader> openPages(MediaSource source, ImageDecoderOptions options) {
        return Optional.of(PnmDecoder.openPages(source, options));
    }

    @Override
    public boolean encodes(ImageFormat format) {
        return format == ImageFormat.PNM;
    }

    @Override
    public void encode(Picture picture, ImageFormat format, ImageEncoderOptions options, MediaSink sink) {
        if (picture instanceof ChannelFloatPicture) {
            PnmEncoder.encode(RasterEncoding.linear(picture, options), PnmEncoderOptions.of(PnmVariant.PFM), sink);
            return;
        }
        PnmEncoder.encode(RasterEncoding.srgb(picture, options), PnmEncoderOptions.defaults(), sink);
    }
}
