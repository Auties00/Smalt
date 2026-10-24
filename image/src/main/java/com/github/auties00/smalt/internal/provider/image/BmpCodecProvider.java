package com.github.auties00.smalt.internal.provider.image;

import com.github.auties00.smalt.image.DecodedImage;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.ImageEncoderOptions;
import com.github.auties00.smalt.image.ImageFormat;
import com.github.auties00.smalt.image.ImageInfo;
import com.github.auties00.smalt.image.Images;
import com.github.auties00.smalt.image.PageReader;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.bmp.BmpDecoder;
import com.github.auties00.smalt.image.bmp.BmpEncoder;
import com.github.auties00.smalt.image.bmp.BmpEncoderOptions;
import com.github.auties00.smalt.internal.check.LosslessDepth;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.spi.ImageCodecProvider;

import java.util.Optional;

/**
 * Provides {@link BmpDecoder} and {@link BmpEncoder} to {@link Images} for BMP images.
 *
 * <p>Every bitmap of an OS/2 bitmap array opens as a page. A picture is encoded with the
 * {@link BmpEncoderOptions#defaults()}, whose automatic layout keeps an 8-bit picture lossless, whatever
 * the quality; a lossless request for a deeper picture is honored only when narrowing it to 8 bits loses
 * nothing.
 */
public final class BmpCodecProvider implements ImageCodecProvider {
    /**
     * Constructs the provider; {@link java.util.ServiceLoader} calls this constructor.
     */
    public BmpCodecProvider() {
    }

    @Override
    public boolean decodes(ImageFormat format) {
        return format == ImageFormat.BMP;
    }

    @Override
    public ImageInfo probe(MediaSource source) {
        return BmpDecoder.probe(source);
    }

    @Override
    public DecodedImage decode(MediaSource source, ImageDecoderOptions options) {
        return BmpDecoder.decode(source, options);
    }

    @Override
    public Optional<PageReader> openPages(MediaSource source, ImageDecoderOptions options) {
        return Optional.of(BmpDecoder.openPages(source, options));
    }

    @Override
    public boolean encodes(ImageFormat format) {
        return format == ImageFormat.BMP;
    }

    @Override
    public void encode(Picture picture, ImageFormat format, ImageEncoderOptions options, MediaSink sink) {
        if (options.isLossless()) {
            picture = LosslessDepth.fit(picture, 8, "bmp");
        }
        BmpEncoder.encode(picture, BmpEncoderOptions.defaults(), sink);
    }
}
