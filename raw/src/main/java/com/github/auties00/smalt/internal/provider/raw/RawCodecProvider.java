package com.github.auties00.smalt.internal.provider.raw;

import com.github.auties00.smalt.image.DecodedImage;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.ImageFormat;
import com.github.auties00.smalt.image.ImageInfo;
import com.github.auties00.smalt.image.Images;
import com.github.auties00.smalt.image.raw.RawDecoder;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.spi.ImageCodecProvider;

/**
 * Provides {@link RawDecoder} to {@link Images} for DNG and camera raw images.
 *
 * <p>Images decode with the
 * {@linkplain com.github.auties00.smalt.image.raw.RawDecoderOptions#defaults() default rendering}: 8-bit
 * sRGB, or 16-bit when the options ask for a high bit depth; a size hint is met from the embedded
 * preview or a half-size rendering when either is large enough.
 */
public final class RawCodecProvider implements ImageCodecProvider {
    /**
     * Constructs the provider; {@link java.util.ServiceLoader} calls this constructor.
     */
    public RawCodecProvider() {
    }

    @Override
    public boolean decodes(ImageFormat format) {
        return format == ImageFormat.DNG || format == ImageFormat.RAW;
    }

    @Override
    public ImageInfo probe(MediaSource source) {
        return RawDecoder.probe(source);
    }

    @Override
    public DecodedImage decode(MediaSource source, ImageDecoderOptions options) {
        return RawDecoder.decode(source, options);
    }
}
