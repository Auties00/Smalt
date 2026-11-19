package com.github.auties00.smalt.internal.provider.image;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.AnimationReader;
import com.github.auties00.smalt.image.AnimationWriter;
import com.github.auties00.smalt.image.DecodedImage;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.ImageEncoderOptions;
import com.github.auties00.smalt.image.ImageFormat;
import com.github.auties00.smalt.image.ImageInfo;
import com.github.auties00.smalt.image.Images;
import com.github.auties00.smalt.image.Orientation;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.gif.GifAnimationEncoder;
import com.github.auties00.smalt.image.gif.GifDecoder;
import com.github.auties00.smalt.image.gif.GifEncoder;
import com.github.auties00.smalt.image.gif.GifEncoderOptions;
import com.github.auties00.smalt.internal.check.LosslessDepth;
import com.github.auties00.smalt.internal.codec.EncodeMetadata;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.metadata.XmpPacket;
import com.github.auties00.smalt.spi.ImageCodecProvider;

import java.util.Optional;

/**
 * Provides {@link GifDecoder} to {@link Images} for GIF images.
 *
 * <p>It also encodes GIF images for {@link Images} with {@link GifEncoder}, and animations with
 * {@link GifAnimationEncoder}, starting from {@link GifEncoderOptions#defaults()}: a lossless
 * request selects {@link GifEncoderOptions#lossless()}, which fails on pictures a GIF cannot hold
 * exactly, and a quality {@code q} limits the palette to {@code round(256 * q / 100)} colors (at
 * least 2), so lower qualities give smaller files. The XMP packet is recorded with a normal orientation,
 * since a GIF holds its pictures upright, and serialized again in UTF-8 when it holds a zero byte (a UTF-16
 * or UTF-32 packet), which the GIF extension cannot carry; such a packet that does not parse is left out.
 */
public final class GifCodecProvider implements ImageCodecProvider {
    /**
     * Constructs the provider; {@link java.util.ServiceLoader} calls this constructor.
     */
    public GifCodecProvider() {
    }

    @Override
    public boolean decodes(ImageFormat format) {
        return format == ImageFormat.GIF;
    }

    @Override
    public ImageInfo probe(MediaSource source) {
        return GifDecoder.probe(source);
    }

    @Override
    public DecodedImage decode(MediaSource source, ImageDecoderOptions options) {
        return GifDecoder.decode(source, options);
    }

    @Override
    public Optional<AnimationReader> openAnimation(MediaSource source, ImageDecoderOptions options) {
        return Optional.of(GifDecoder.openAnimation(source, options));
    }

    @Override
    public boolean encodes(ImageFormat format) {
        return format == ImageFormat.GIF;
    }

    @Override
    public void encode(Picture picture, ImageFormat format, ImageEncoderOptions options, MediaSink sink) {
        var prepared = EncodeMetadata.color(picture, options, EncodeMetadata::rgbOnly);
        picture = EncodeMetadata.upright(prepared.picture(), options);
        if (options.isLossless()) {
            picture = LosslessDepth.fit(picture, 8, "gif");
        }
        var builder = settings(options).toBuilder();
        if (prepared.profile() != null) {
            builder.colorProfile(prepared.profile());
        }
        xmp(options).ifPresent(builder::xmp);
        GifEncoder.encode(picture, builder.build(), sink);
    }

    @Override
    public boolean encodesAnimation(ImageFormat format) {
        return format == ImageFormat.GIF;
    }

    @Override
    public AnimationWriter openAnimationWriter(ImageFormat format, int width, int height, ImageEncoderOptions options,
                                               MediaSink sink) {
        EncodeMetadata.requireUpright(options, "gif");
        var color = EncodeMetadata.animationColor(options, true);
        var builder = settings(options).toBuilder().loopCount(options.loopCount());
        if (color.profile() != null) {
            builder.colorProfile(color.profile());
        }
        xmp(options).ifPresent(builder::xmp);
        var encoder = new GifAnimationEncoder(width, height, builder.build());
        return color.wrap(new GifAnimationWriter(encoder, width, height, sink));
    }

    /**
     * Maps the format-independent options onto GIF settings.
     *
     * @param options the format-independent settings: lossless coding or a quality from 0 to 100
     * @return the GIF settings
     */
    private static GifEncoderOptions settings(ImageEncoderOptions options) {
        if (options.isLossless()) {
            return GifEncoderOptions.lossless();
        }
        var quality = options.quality();
        if (quality.isEmpty()) {
            return GifEncoderOptions.defaults();
        }
        return GifEncoderOptions.builder().maxColors(Math.clamp(Math.round(256 * quality.getAsInt() / 100.0f), 2, 256))
                .build();
    }

    /**
     * {@return the XMP packet a GIF records for the options: the packet with a normal orientation, serialized
     * again in UTF-8 when it holds a zero byte, or empty for none or for such a packet that does not parse}
     *
     * @param options the format-independent settings
     */
    private static Optional<byte[]> xmp(ImageEncoderOptions options) {
        return EncodeMetadata.xmp(options, Orientation.NORMAL).flatMap(packet -> {
            for (var value : packet) {
                if (value == 0) {
                    try {
                        return Optional.of(XmpPacket.parse(packet).toBytes());
                    } catch (MediaException exception) {
                        return Optional.empty();
                    }
                }
            }
            return Optional.of(packet);
        });
    }
}
