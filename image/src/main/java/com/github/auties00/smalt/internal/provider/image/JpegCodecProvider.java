package com.github.auties00.smalt.internal.provider.image;

import com.github.auties00.smalt.image.ChannelPicture;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorModel;
import com.github.auties00.smalt.image.DecodedImage;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.ImageEncoderOptions;
import com.github.auties00.smalt.image.ImageFormat;
import com.github.auties00.smalt.image.ImageInfo;
import com.github.auties00.smalt.image.Images;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.image.jpeg.JpegDecoder;
import com.github.auties00.smalt.image.jpeg.JpegEncoder;
import com.github.auties00.smalt.image.jpeg.JpegEncoderOptions;
import com.github.auties00.smalt.image.jpeg.JpegEncoderOptions.ColorSpace;
import com.github.auties00.smalt.image.jpeg.internal.JpegEncoding;
import com.github.auties00.smalt.internal.check.LosslessDepth;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.spi.ImageCodecProvider;

/**
 * Provides {@link JpegDecoder} to {@link Images} for JPEG images, and {@link JpegEncoder} for
 * encoding them.
 *
 * @implNote This implementation maps the codec-independent encoder options onto
 *           {@link JpegEncoderOptions#ofQuality(int)}, at quality {@value #DEFAULT_QUALITY} when the
 *           options leave it to the format, and a lossless request onto {@link JpegEncoderOptions#ofLossless()}
 *           in the color space that keeps the picture's samples: RGB for RGB pictures and for planar pictures
 *           of another range or matrix than JPEG's, YCbCr in the picture's own subsampling for JPEG's planar
 *           pictures, gray for gray channel pictures and CMYK for CMYK ones; a picture deeper than 16 bits
 *           is coded only when its samples are exactly 8-bit ones.
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
            var fitted = LosslessDepth.fit(picture, 16, "jpeg");
            JpegEncoder.encode(fitted, lossless(fitted), sink);
            return;
        }
        JpegEncoder.encode(picture, JpegEncoderOptions.ofQuality(options.quality().orElse(DEFAULT_QUALITY)), sink);
    }

    /**
     * Returns the lossless options that keep a picture's samples.
     *
     * @param picture the picture to code
     * @return the options
     */
    private static JpegEncoderOptions lossless(Picture picture) {
        var builder = JpegEncoderOptions.ofLossless().toBuilder();
        switch (picture) {
            case YuvPicture yuv when JpegEncoding.isJpegYcc(yuv) ->
                    builder.colorSpace(ColorSpace.YCBCR).chromaFormat(yuv.chromaFormat());
            case Yuv16Picture yuv when JpegEncoding.isJpegYcc(yuv) ->
                    builder.colorSpace(ColorSpace.YCBCR).chromaFormat(yuv.chromaFormat());
            case ChannelPicture channels when channels.layout().colorModel() == ColorModel.GRAY ->
                    builder.colorSpace(ColorSpace.YCBCR).chromaFormat(ChromaFormat.MONOCHROME);
            case ChannelPicture channels when channels.layout().colorModel() == ColorModel.CMYK ->
                    builder.colorSpace(ColorSpace.CMYK);
            default -> {
            }
        }
        return builder.build();
    }
}
