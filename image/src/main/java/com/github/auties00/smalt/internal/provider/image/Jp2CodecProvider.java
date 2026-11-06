package com.github.auties00.smalt.internal.provider.image;

import com.github.auties00.smalt.image.AnimationReader;
import com.github.auties00.smalt.image.DecodedImage;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.ImageEncoderOptions;
import com.github.auties00.smalt.image.ImageFormat;
import com.github.auties00.smalt.image.ImageInfo;
import com.github.auties00.smalt.image.Images;
import com.github.auties00.smalt.image.PageReader;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.jp2.Jp2Decoder;
import com.github.auties00.smalt.image.jp2.Jp2Encoder;
import com.github.auties00.smalt.image.jp2.Jp2EncoderOptions;
import com.github.auties00.smalt.internal.codec.EncodeMetadata;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.spi.ImageCodecProvider;

import java.util.Optional;

/**
 * Provides {@link Jp2Decoder} and {@link Jp2Encoder} to {@link Images} for JPEG 2000 images.
 *
 * <p>An encoded picture is a JP2 file: lossless when the options ask for it or set no quality,
 * as {@code opj_compress} codes by default; otherwise lossy with the 9-7 wavelet at a compression
 * ratio of {@code 1 + (100 - quality) / 2} (6 at quality 90, 13.5 at 75, 26 at 50), lossless at
 * quality 100. The file records the color profile when it describes the coded samples, the EXIF block (parsed
 * and written again, which moves its values) with the orientation, and the XMP packet; a block or packet that
 * does not parse is left out.
 */
public final class Jp2CodecProvider implements ImageCodecProvider {
    /**
     * Constructs the provider; {@link java.util.ServiceLoader} calls this constructor.
     */
    public Jp2CodecProvider() {
    }

    @Override
    public boolean decodes(ImageFormat format) {
        return format == ImageFormat.JP2;
    }

    @Override
    public ImageInfo probe(MediaSource source) {
        return Jp2Decoder.probe(source);
    }

    @Override
    public DecodedImage decode(MediaSource source, ImageDecoderOptions options) {
        return Jp2Decoder.decode(source, options);
    }

    @Override
    public boolean decodesRegions(ImageFormat format) {
        return true;
    }

    @Override
    public Optional<PageReader> openPages(MediaSource source, ImageDecoderOptions options) {
        return Optional.of(Jp2Decoder.openPages(source, options));
    }

    @Override
    public Optional<AnimationReader> openAnimation(MediaSource source, ImageDecoderOptions options) {
        return Optional.of(Jp2Decoder.openAnimation(source, options));
    }

    @Override
    public boolean encodes(ImageFormat format) {
        return format == ImageFormat.JP2;
    }

    @Override
    public void encode(Picture picture, ImageFormat format, ImageEncoderOptions options, MediaSink sink) {
        Jp2EncoderOptions settings;
        if (options.isLossless() || options.quality().isEmpty() || options.quality().getAsInt() >= 100) {
            settings = Jp2EncoderOptions.lossless();
        } else {
            settings = Jp2EncoderOptions.lossy(1 + (100 - options.quality().getAsInt()) / 2.0);
        }
        var prepared = EncodeMetadata.color(picture, options, EncoderMetadata::recordsCoded);
        var builder = settings.toBuilder();
        if (prepared.profile() != null) {
            builder.colorProfile(prepared.profile());
        }
        EncodeMetadata.exif(options).map(block -> EncoderMetadata.exif(block, true)).ifPresent(builder::exif);
        EncodeMetadata.xmp(options).map(EncoderMetadata::xmp).ifPresent(builder::xmp);
        Jp2Encoder.encode(prepared.picture(), builder.build(), sink);
    }
}
