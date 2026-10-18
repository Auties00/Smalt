package com.github.auties00.smalt.internal.provider.image;

import com.github.auties00.smalt.image.DecodedImage;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.ImageEncoderOptions;
import com.github.auties00.smalt.image.ImageFormat;
import com.github.auties00.smalt.image.ImageInfo;
import com.github.auties00.smalt.image.Images;
import com.github.auties00.smalt.image.PageReader;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.ico.IcoDecoder;
import com.github.auties00.smalt.image.ico.IcoEncoder;
import com.github.auties00.smalt.image.ico.IcoEncoderOptions;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.spi.ImageCodecProvider;

import java.util.Optional;

/**
 * Provides {@link IcoDecoder} and {@link IcoEncoder} to {@link Images} for icon and cursor files.
 *
 * <p>Decoding returns the largest entry (or the smallest one the size hint accepts), and every
 * entry opens as a page. An encoded picture becomes a one-entry icon at its own size, or, when it
 * is larger than 256 pixels on a side, a 256-pixel square entry it is scaled into.
 */
public final class IcoCodecProvider implements ImageCodecProvider {
    /**
     * Constructs the provider; {@link java.util.ServiceLoader} calls this constructor.
     */
    public IcoCodecProvider() {
    }

    @Override
    public boolean decodes(ImageFormat format) {
        return format == ImageFormat.ICO;
    }

    @Override
    public ImageInfo probe(MediaSource source) {
        return IcoDecoder.probe(source);
    }

    @Override
    public DecodedImage decode(MediaSource source, ImageDecoderOptions options) {
        return IcoDecoder.decode(source, options);
    }

    @Override
    public Optional<PageReader> openPages(MediaSource source, ImageDecoderOptions options) {
        return Optional.of(IcoDecoder.openPages(source, options));
    }

    @Override
    public boolean encodes(ImageFormat format) {
        return format == ImageFormat.ICO;
    }

    @Override
    public void encode(Picture picture, ImageFormat format, ImageEncoderOptions options, MediaSink sink) {
        var settings = picture.width() > 256 || picture.height() > 256
                ? IcoEncoderOptions.builder().sizes(256).build()
                : IcoEncoderOptions.defaults();
        IcoEncoder.encode(picture, settings, sink);
    }
}
