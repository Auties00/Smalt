package com.github.auties00.smalt.internal.provider.image;

import com.github.auties00.smalt.image.DecodedImage;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.ImageEncoderOptions;
import com.github.auties00.smalt.image.ImageFormat;
import com.github.auties00.smalt.image.ImageInfo;
import com.github.auties00.smalt.image.Images;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.qoi.QoiDecoder;
import com.github.auties00.smalt.image.qoi.QoiEncoder;
import com.github.auties00.smalt.image.qoi.QoiEncoderOptions;
import com.github.auties00.smalt.internal.check.LosslessDepth;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.spi.ImageCodecProvider;

/**
 * Provides {@link QoiDecoder} and {@link QoiEncoder} to {@link Images} for QOI images.
 *
 * <p>A picture is converted to sRGB, turned upright and coded losslessly at 8 bits, whatever the quality; a
 * lossless request for a deeper picture is honored only when narrowing it to 8 bits loses nothing.
 */
public final class QoiCodecProvider implements ImageCodecProvider {
    /**
     * Constructs the provider; {@link java.util.ServiceLoader} calls this constructor.
     */
    public QoiCodecProvider() {
    }

    @Override
    public boolean decodes(ImageFormat format) {
        return format == ImageFormat.QOI;
    }

    @Override
    public ImageInfo probe(MediaSource source) {
        return QoiDecoder.probe(source);
    }

    @Override
    public DecodedImage decode(MediaSource source, ImageDecoderOptions options) {
        return QoiDecoder.decode(source, options);
    }

    @Override
    public boolean encodes(ImageFormat format) {
        return format == ImageFormat.QOI;
    }

    @Override
    public void encode(Picture picture, ImageFormat format, ImageEncoderOptions options, MediaSink sink) {
        var prepared = RasterEncoding.srgb(picture, options);
        if (options.isLossless()) {
            prepared = LosslessDepth.fit(prepared, 8, "qoi");
        }
        QoiEncoder.encode(prepared, QoiEncoderOptions.defaults(), sink);
    }
}
