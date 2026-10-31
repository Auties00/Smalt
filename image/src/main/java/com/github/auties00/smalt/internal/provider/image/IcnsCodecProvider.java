package com.github.auties00.smalt.internal.provider.image;

import com.github.auties00.smalt.image.DecodedImage;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.ImageEncoderOptions;
import com.github.auties00.smalt.image.ImageFormat;
import com.github.auties00.smalt.image.ImageInfo;
import com.github.auties00.smalt.image.Images;
import com.github.auties00.smalt.image.PageReader;
import com.github.auties00.smalt.image.PageWriter;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.icns.IcnsDecoder;
import com.github.auties00.smalt.image.icns.IcnsEncoder;
import com.github.auties00.smalt.image.icns.IcnsEncoderOptions;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.spi.ImageCodecProvider;

import java.util.List;
import java.util.Optional;

/**
 * Provides {@link IcnsDecoder} and {@link IcnsEncoder} to {@link Images} for Apple icon images.
 *
 * <p>Decoding returns the largest image (or the smallest one the size hint accepts), and every image opens as a
 * page. An encoded picture is converted to 8-bit sRGB, turned upright and becomes a one-image family: at its own
 * size when it is square and of a size an icon family holds, otherwise scaled into the smallest such square at least
 * as large as its longer side (at most 1024 pixels); a page writer writes one such image per page.
 */
public final class IcnsCodecProvider implements ImageCodecProvider {
    /**
     * Constructs the provider; {@link java.util.ServiceLoader} calls this constructor.
     */
    public IcnsCodecProvider() {
    }

    @Override
    public boolean decodes(ImageFormat format) {
        return format == ImageFormat.ICNS;
    }

    @Override
    public ImageInfo probe(MediaSource source) {
        return IcnsDecoder.probe(source);
    }

    @Override
    public DecodedImage decode(MediaSource source, ImageDecoderOptions options) {
        return IcnsDecoder.decode(source, options);
    }

    @Override
    public Optional<PageReader> openPages(MediaSource source, ImageDecoderOptions options) {
        return Optional.of(IcnsDecoder.openPages(source, options));
    }

    @Override
    public boolean encodes(ImageFormat format) {
        return format == ImageFormat.ICNS;
    }

    @Override
    public void encode(Picture picture, ImageFormat format, ImageEncoderOptions options, MediaSink sink) {
        IcnsEncoder.encode(List.of(IcnsPageWriter.prepare(picture, options)), IcnsEncoderOptions.defaults(), sink);
    }

    @Override
    public boolean encodesPages(ImageFormat format) {
        return format == ImageFormat.ICNS;
    }

    @Override
    public PageWriter openPageWriter(ImageFormat format, ImageEncoderOptions options, MediaSink sink) {
        return new IcnsPageWriter(sink, options);
    }
}
