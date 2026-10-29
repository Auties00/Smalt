package com.github.auties00.smalt.internal.provider.image;

import com.github.auties00.smalt.MediaException;
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
import com.github.auties00.smalt.image.color.ColorProfile;
import com.github.auties00.smalt.image.color.ProfileColorSpace;
import com.github.auties00.smalt.image.jpeg.JpegDecoder;
import com.github.auties00.smalt.image.jpeg.JpegEncoder;
import com.github.auties00.smalt.image.jpeg.JpegEncoderOptions;
import com.github.auties00.smalt.image.jpeg.JpegEncoderOptions.ColorSpace;
import com.github.auties00.smalt.image.jpeg.internal.JpegEncoding;
import com.github.auties00.smalt.image.jpeg.internal.JpegXmp;
import com.github.auties00.smalt.internal.check.LosslessDepth;
import com.github.auties00.smalt.internal.codec.EncodeMetadata;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.metadata.XmpPacket;
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
 *           is coded only when its samples are exactly 8-bit ones. A gray color profile codes the picture in
 *           gray and a CMYK one in CMYK, so that the profile describes the coded samples; the EXIF block
 *           records the orientation, and an XMP packet too large for one segment that does not parse, which
 *           cannot be split into Extended XMP segments, is left out.
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
    public boolean decodesRegions(ImageFormat format) {
        return true;
    }

    @Override
    public boolean encodes(ImageFormat format) {
        return format == ImageFormat.JPEG;
    }

    @Override
    public void encode(Picture picture, ImageFormat format, ImageEncoderOptions options, MediaSink sink) {
        var lossless = options.isLossless();
        var prepared = EncodeMetadata.color(picture, options, (profile, coded) -> records(profile, coded, lossless));
        var input = prepared.picture();
        JpegEncoderOptions.Builder builder;
        if (lossless) {
            input = LosslessDepth.fit(input, 16, "jpeg");
            builder = lossless(input).toBuilder();
        } else {
            builder = JpegEncoderOptions.ofQuality(options.quality().orElse(DEFAULT_QUALITY)).toBuilder();
        }
        var profile = prepared.profile();
        if (profile != null) {
            builder.colorProfile(profile);
            if (profile.colorSpace() == ProfileColorSpace.GRAY) {
                builder.colorSpace(ColorSpace.YCBCR).chromaFormat(ChromaFormat.MONOCHROME);
            } else if (profile.colorSpace() == ProfileColorSpace.CMYK) {
                builder.colorSpace(ColorSpace.CMYK);
            }
        }
        EncodeMetadata.exif(options).ifPresent(builder::exif);
        EncodeMetadata.xmp(options).filter(JpegCodecProvider::fits).ifPresent(builder::xmp);
        JpegEncoder.encode(input, builder.build(), sink);
    }

    /**
     * {@return whether a JPEG file can record an XMP packet: one that fits an {@code APP1} segment, or one
     * that parses, which is split into Extended XMP segments}
     *
     * @param xmp the serialized packet
     */
    private static boolean fits(byte[] xmp) {
        if (xmp.length <= JpegXmp.MAX_MAIN) {
            return true;
        }
        try {
            XmpPacket.parse(xmp);
            return true;
        } catch (MediaException exception) {
            return false;
        }
    }

    /**
     * {@return whether a JPEG file records a profile for a picture: a CMYK profile for CMYK inks, coded in
     * CMYK; a gray profile for any other picture, coded in gray; an RGB profile for any other picture that is
     * not coded in gray}
     *
     * @param profile the profile of the picture
     * @param picture the picture to encode
     * @param lossless whether the coding is lossless, which keeps the gray coding of a gray planar picture
     */
    private static boolean records(ColorProfile profile, Picture picture, boolean lossless) {
        var cmyk = EncodeMetadata.isCmyk(picture);
        return switch (profile.colorSpace()) {
            case CMYK -> cmyk;
            case GRAY -> !cmyk;
            case RGB -> !cmyk && !(lossless && codedGray(picture));
            default -> false;
        };
    }

    /**
     * {@return whether lossless coding codes a picture in gray: a monochrome planar picture with JPEG's
     * samples}
     *
     * @param picture the picture to encode
     */
    private static boolean codedGray(Picture picture) {
        return switch (picture) {
            case YuvPicture yuv -> yuv.chromaFormat() == ChromaFormat.MONOCHROME && JpegEncoding.isJpegYcc(yuv);
            case Yuv16Picture yuv -> yuv.chromaFormat() == ChromaFormat.MONOCHROME && JpegEncoding.isJpegYcc(yuv);
            default -> false;
        };
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
