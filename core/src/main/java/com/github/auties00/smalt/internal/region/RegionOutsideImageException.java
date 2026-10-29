package com.github.auties00.smalt.internal.region;

import java.io.Serial;

/**
 * Signals that the region of {@link com.github.auties00.smalt.image.ImageDecoderOptions#region()} does not lie
 * inside the image being decoded, a caller error that the decoder guards pass through unchanged.
 */
public final class RegionOutsideImageException extends IllegalArgumentException {
    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * Constructs the exception.
     *
     * @param message the description of the region and the image
     */
    public RegionOutsideImageException(String message) {
        super(message);
    }
}
