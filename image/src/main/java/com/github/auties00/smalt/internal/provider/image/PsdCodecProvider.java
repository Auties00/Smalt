package com.github.auties00.smalt.internal.provider.image;

import com.github.auties00.smalt.image.DecodedImage;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.ImageFormat;
import com.github.auties00.smalt.image.ImageInfo;
import com.github.auties00.smalt.image.Images;
import com.github.auties00.smalt.image.psd.PsdDecoder;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.spi.ImageCodecProvider;

/**
 * Provides {@link PsdDecoder} to {@link Images} for the composite images of Photoshop documents.
 */
public final class PsdCodecProvider implements ImageCodecProvider {
    /**
     * Constructs the provider; {@link java.util.ServiceLoader} calls this constructor.
     */
    public PsdCodecProvider() {
    }

    @Override
    public boolean decodes(ImageFormat format) {
        return format == ImageFormat.PSD;
    }

    @Override
    public ImageInfo probe(MediaSource source) {
        return PsdDecoder.probe(source);
    }

    @Override
    public DecodedImage decode(MediaSource source, ImageDecoderOptions options) {
        return PsdDecoder.decode(source, options);
    }
}
