package com.github.auties00.smalt.image.raw;

import com.github.auties00.smalt.image.Chromaticity;
import com.github.auties00.smalt.image.color.ColorProfile;

/**
 * A color space a raw image can be rendered into.
 *
 * <p>The decoded image's {@link com.github.auties00.smalt.image.DecodedImage#colorProfile()}
 * names the space, so color-managed consumers display every choice correctly.
 */
public enum RawColorSpace {
    /**
     * sRGB (IEC 61966-2-1) with its transfer function: what untagged images are assumed to be.
     */
    SRGB,

    /**
     * Display P3: the sRGB transfer function over the wider DCI-P3 primaries with a D65 white.
     */
    DISPLAY_P3,

    /**
     * Adobe RGB (1998), with its 563/256 gamma.
     */
    ADOBE_RGB,

    /**
     * ProPhoto RGB (ROMM RGB, D50 white), with its 1.8 gamma: wide enough for every color a
     * camera records.
     */
    PROPHOTO_RGB,

    /**
     * The sRGB primaries with a linear transfer function, for processing.
     */
    LINEAR_SRGB,

    /**
     * The ITU-R BT.2020 primaries with a linear transfer function, for processing without
     * clipping saturated colors.
     */
    LINEAR_REC2020;

    /**
     * {@return the ICC profile of the color space}
     */
    public ColorProfile profile() {
        return switch (this) {
            case SRGB -> ColorProfile.srgb();
            case DISPLAY_P3 -> ColorProfile.displayP3();
            case ADOBE_RGB -> ColorProfile.adobeRgb();
            case PROPHOTO_RGB -> ColorProfile.proPhotoRgb();
            case LINEAR_SRGB -> ColorProfile.rgb(new Chromaticity(0.64, 0.33), new Chromaticity(0.30, 0.60),
                    new Chromaticity(0.15, 0.06), new Chromaticity(0.3127, 0.3290), 1.0);
            case LINEAR_REC2020 -> ColorProfile.rgb(new Chromaticity(0.708, 0.292), new Chromaticity(0.170, 0.797),
                    new Chromaticity(0.131, 0.046), new Chromaticity(0.3127, 0.3290), 1.0);
        };
    }

    /**
     * {@return whether the color space encodes light linearly: {@code true} for
     * {@link #LINEAR_SRGB} and {@link #LINEAR_REC2020}}
     */
    public boolean isLinear() {
        return this == LINEAR_SRGB || this == LINEAR_REC2020;
    }
}
