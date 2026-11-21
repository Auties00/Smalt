package com.github.auties00.smalt.internal.provider.image;

import com.github.auties00.smalt.image.DecodedImage;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.ImageEncoderOptions;
import com.github.auties00.smalt.image.ImageFormat;
import com.github.auties00.smalt.image.ImageInfo;
import com.github.auties00.smalt.image.Images;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.hdr.HdrDecoder;
import com.github.auties00.smalt.image.hdr.HdrEncoder;
import com.github.auties00.smalt.image.hdr.HdrEncoderOptions;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.spi.ImageCodecProvider;

/**
 * Provides {@link HdrDecoder} and {@link HdrEncoder} to {@link Images} for Radiance RGBE images.
 *
 * <p>A picture is converted from the options' color profile (sRGB without one) to linear light in the BT.709
 * primaries and written with the {@link HdrEncoderOptions#defaults()}; the format is lossy for every picture (an
 * 8-bit mantissa shared under one exponent), so a lossless request is unsupported.
 */
public final class HdrCodecProvider implements ImageCodecProvider {
    /**
     * Constructs the provider; {@link java.util.ServiceLoader} calls this constructor.
     */
    public HdrCodecProvider() {
    }

    @Override
    public boolean decodes(ImageFormat format) {
        return format == ImageFormat.HDR;
    }

    @Override
    public ImageInfo probe(MediaSource source) {
        return HdrDecoder.probe(source);
    }

    @Override
    public DecodedImage decode(MediaSource source, ImageDecoderOptions options) {
        return HdrDecoder.decode(source, options);
    }

    @Override
    public boolean encodes(ImageFormat format) {
        return format == ImageFormat.HDR;
    }

    @Override
    public void encode(Picture picture, ImageFormat format, ImageEncoderOptions options, MediaSink sink) {
        if (options.isLossless()) {
            throw new MediaException.Unsupported("hdr", "Radiance RGBE coding is not lossless");
        }
        HdrEncoder.encode(RasterEncoding.linear(picture, options), HdrEncoderOptions.defaults(), sink);
    }
}
