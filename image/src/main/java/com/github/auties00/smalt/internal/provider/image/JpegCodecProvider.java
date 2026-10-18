package com.github.auties00.smalt.internal.provider.image;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.DecodedImage;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.ImageEncoderOptions;
import com.github.auties00.smalt.image.ImageFormat;
import com.github.auties00.smalt.image.ImageInfo;
import com.github.auties00.smalt.image.Images;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.jpeg.JpegDecoder;
import com.github.auties00.smalt.image.jpeg.JpegEncoder;
import com.github.auties00.smalt.image.jpeg.JpegEncoderOptions;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.spi.ImageCodecProvider;

/**
 * Provides {@link JpegDecoder} to {@link Images} for JPEG images, and {@link JpegEncoder} for
 * encoding them.
 *
 * @implNote This implementation maps the codec-independent encoder options onto
 *           {@link JpegEncoderOptions#ofQuality(int)}, at quality {@value #DEFAULT_QUALITY} when the
 *           options leave it to the format, and rejects lossless coding.
 */
public final class JpegCodecProvider implements ImageCodecProvider {
    /**
     * The quality used when the options leave it to the format, libjpeg's default.
     */
    private static final int DEFAULT_QUALITY = 75;

    /**
     * Constructs the provider; {@link java.util.ServiceLoader} calls this constructor.
     */
    public JpegCodecProvider() {
    }

    @Override
    public boolean decodes(ImageFormat format) {
        return format == ImageFormat.JPEG;
    }

    @Override
    public ImageInfo probe(MediaSource source) {
        return JpegDecoder.probe(source);
    }

    @Override
    public DecodedImage decode(MediaSource source, ImageDecoderOptions options) {
        return JpegDecoder.decode(source, options);
    }

    @Override
    public boolean encodes(ImageFormat format) {
        return format == ImageFormat.JPEG;
    }

    @Override
    public void encode(Picture picture, ImageFormat format, ImageEncoderOptions options, MediaSink sink) {
        if (options.isLossless()) {
            throw new MediaException.Unsupported("jpeg lossless", "JPEG images cannot be coded losslessly");
        }
        JpegEncoder.encode(picture, JpegEncoderOptions.ofQuality(options.quality().orElse(DEFAULT_QUALITY)), sink);
    }
}
