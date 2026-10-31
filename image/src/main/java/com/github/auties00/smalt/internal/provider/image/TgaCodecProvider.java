package com.github.auties00.smalt.internal.provider.image;

import com.github.auties00.smalt.image.DecodedImage;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.ImageEncoderOptions;
import com.github.auties00.smalt.image.ImageFormat;
import com.github.auties00.smalt.image.ImageInfo;
import com.github.auties00.smalt.image.Images;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.tga.TgaDecoder;
import com.github.auties00.smalt.image.tga.TgaEncoder;
import com.github.auties00.smalt.image.tga.TgaEncoderOptions;
import com.github.auties00.smalt.internal.check.LosslessDepth;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.spi.ImageCodecProvider;

/**
 * Provides {@link TgaDecoder} and {@link TgaEncoder} to {@link Images} for TGA images.
 *
 * <p>A picture is converted to sRGB, turned upright and written run-length coded at 8 bits per channel with the
 * {@link TgaEncoderOptions#defaults()}, whatever the quality; a lossless request for a deeper picture is honored
 * only when narrowing it to 8 bits loses nothing.
 */
public final class TgaCodecProvider implements ImageCodecProvider {
    /**
     * Constructs the provider; {@link java.util.ServiceLoader} calls this constructor.
     */
    public TgaCodecProvider() {
    }

    @Override
    public boolean decodes(ImageFormat format) {
        return format == ImageFormat.TGA;
    }

    @Override
    public ImageInfo probe(MediaSource source) {
        return TgaDecoder.probe(source);
    }

    @Override
    public DecodedImage decode(MediaSource source, ImageDecoderOptions options) {
        return TgaDecoder.decode(source, options);
    }

    @Override
    public boolean encodes(ImageFormat format) {
        return format == ImageFormat.TGA;
    }

    @Override
    public void encode(Picture picture, ImageFormat format, ImageEncoderOptions options, MediaSink sink) {
        var prepared = RasterEncoding.srgb(picture, options);
        if (options.isLossless()) {
            prepared = LosslessDepth.fit(prepared, 8, "tga");
        }
        TgaEncoder.encode(prepared, TgaEncoderOptions.defaults(), sink);
    }
}
