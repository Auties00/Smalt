package com.github.auties00.smalt.internal.provider.image;

import com.github.auties00.smalt.image.DecodedImage;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.ImageFormat;
import com.github.auties00.smalt.image.ImageInfo;
import com.github.auties00.smalt.image.Images;
import com.github.auties00.smalt.image.PageReader;
import com.github.auties00.smalt.image.exr.ExrDecoder;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.spi.ImageCodecProvider;

import java.util.Optional;

/**
 * Provides {@link ExrDecoder} to {@link Images} for OpenEXR images; every part opens as a page.
 */
public final class ExrCodecProvider implements ImageCodecProvider {
    /**
     * Constructs the provider; {@link java.util.ServiceLoader} calls this constructor.
     */
    public ExrCodecProvider() {
    }

    @Override
    public boolean decodes(ImageFormat format) {
        return format == ImageFormat.EXR;
    }

    @Override
    public ImageInfo probe(MediaSource source) {
        return ExrDecoder.probe(source);
    }

    @Override
    public DecodedImage decode(MediaSource source, ImageDecoderOptions options) {
        return ExrDecoder.decode(source, options);
    }

    @Override
    public Optional<PageReader> openPages(MediaSource source, ImageDecoderOptions options) {
        return Optional.of(ExrDecoder.openPages(source, options));
    }
}
