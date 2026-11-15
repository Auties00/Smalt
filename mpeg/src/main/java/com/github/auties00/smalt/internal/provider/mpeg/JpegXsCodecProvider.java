package com.github.auties00.smalt.internal.provider.mpeg;

import com.github.auties00.smalt.image.DecodedImage;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.ImageEncoderOptions;
import com.github.auties00.smalt.image.ImageFormat;
import com.github.auties00.smalt.image.ImageInfo;
import com.github.auties00.smalt.image.Images;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.jxs.JpegXsDecoder;
import com.github.auties00.smalt.image.jxs.JpegXsEncoder;
import com.github.auties00.smalt.image.jxs.JpegXsEncoderOptions;
import com.github.auties00.smalt.internal.codec.EncodeMetadata;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.spi.ImageCodecProvider;

/**
 * Provides {@link JpegXsDecoder} and {@link JpegXsEncoder} to {@link Images} for JPEG XS images.
 *
 * <p>An image is encoded as a JXS file: lossless for a lossless request, otherwise at a rate in bits per pixel that
 * grows with the quality ({@code 0.25 + 0.1175 * quality}, so 12 bits per pixel at quality 100), or at the
 * {@link JpegXsEncoderOptions#defaults()} rate when the options leave the quality to the format. A JXS file records
 * no ICC profile, EXIF block, XMP packet or orientation here, so a picture with a profile is converted to sRGB and
 * turned upright.
 */
public final class JpegXsCodecProvider implements ImageCodecProvider {
    /**
     * Constructs the provider; {@link java.util.ServiceLoader} calls this constructor.
     */
    public JpegXsCodecProvider() {
    }

    @Override
    public boolean decodes(ImageFormat format) {
        return format == ImageFormat.JPEG_XS;
    }

    @Override
    public ImageInfo probe(MediaSource source) {
        return JpegXsDecoder.probe(source);
    }

    @Override
    public DecodedImage decode(MediaSource source, ImageDecoderOptions options) {
        return JpegXsDecoder.decode(source, options);
    }

    @Override
    public boolean encodes(ImageFormat format) {
        return format == ImageFormat.JPEG_XS;
    }

    @Override
    public void encode(Picture picture, ImageFormat format, ImageEncoderOptions options, MediaSink sink) {
        var prepared = EncodeMetadata.color(picture, options, (profile, coded) -> false);
        var upright = EncodeMetadata.upright(prepared.picture(), options);
        JpegXsEncoder.encode(upright, settings(options), sink);
    }

    /**
     * Maps the generic encoder options onto the JPEG XS encoder's.
     *
     * @param options the generic options
     * @return the JPEG XS options, which write a JXS file
     */
    private static JpegXsEncoderOptions settings(ImageEncoderOptions options) {
        if (options.isLossless()) {
            return JpegXsEncoderOptions.forLossless();
        }
        var quality = options.quality();
        return quality.isPresent() ? JpegXsEncoderOptions.ofBitsPerPixel(0.25 + 0.1175 * quality.getAsInt())
                : JpegXsEncoderOptions.defaults();
    }
}
