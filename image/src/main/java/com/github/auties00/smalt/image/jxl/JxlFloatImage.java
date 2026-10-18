package com.github.auties00.smalt.image.jxl;

import com.github.auties00.smalt.image.Orientation;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Holds a JPEG XL image decoded at full floating-point precision: its color channels and extra
 * channels as separate planes, with the color space of the color planes.
 *
 * <p>Samples are nominal values: {@code 0.0} to {@code 1.0} for in-range samples, possibly outside
 * that range for out-of-gamut colors and for floating-point images (high dynamic range, wide
 * gamut). The color planes are in the color space the {@link #iccProfile()} describes: the
 * image's own encoding, or, for an XYB-coded image whose original color space is an ICC profile,
 * linear sRGB (linear gray for grayscale images). Alpha is stored as the file stores it, straight
 * or premultiplied ({@link #alphaPremultiplied()}). The planes are not oriented: {@link #orientation()}
 * still has to be applied to display them upright.
 *
 * <p>Equality compares the planes and the profile by identity, as records compare arrays.
 * Instances are not thread-safe; the planes and the profile are shared, not copied.
 *
 * @param width the width in pixels, before orientation
 * @param height the height in pixels, before orientation
 * @param colorChannels the number of color planes: 1 for gray, 3 for RGB (CMY for a CMYK
 * image, whose black is an extra channel)
 * @param extraChannelTypes the type of each extra plane, in plane order
 * @param planes the planes, row by row: the color planes first, then the extra planes
 * @param alphaPremultiplied whether the first alpha channel is premultiplied into the color
 * @param iccProfile the ICC profile describing the color planes, always present
 * @param orientation the display orientation
 */
public record JxlFloatImage(int width, int height, int colorChannels, List<JxlChannelType> extraChannelTypes,
                            float[][] planes, boolean alphaPremultiplied, byte[] iccProfile,
                            Orientation orientation) {
    /**
     * Validates the image.
     *
     * @throws NullPointerException if a reference component is {@code null}
     * @throws IllegalArgumentException if the plane count or a plane size does not match
     */
    public JxlFloatImage {
        Objects.requireNonNull(extraChannelTypes, "extraChannelTypes");
        Objects.requireNonNull(planes, "planes");
        Objects.requireNonNull(iccProfile, "iccProfile");
        Objects.requireNonNull(orientation, "orientation");
        extraChannelTypes = List.copyOf(extraChannelTypes);
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("invalid size " + width + "x" + height);
        }
        if (colorChannels != 1 && colorChannels != 3) {
            throw new IllegalArgumentException("invalid color channel count " + colorChannels);
        }
        if (planes.length != colorChannels + extraChannelTypes.size()) {
            throw new IllegalArgumentException("expected " + (colorChannels + extraChannelTypes.size())
                    + " planes, got " + planes.length);
        }
        for (var plane : planes) {
            if (plane == null || plane.length != width * height) {
                throw new IllegalArgumentException("plane of the wrong size");
            }
        }
    }

    /**
     * Returns one plane, shared with this image.
     *
     * @param index the plane index: the color planes first, from 0 to {@link #colorChannels()}
     * minus 1, then the extra planes in {@link #extraChannelTypes()} order
     * @return the plane, {@code width * height} samples row by row
     * @throws IndexOutOfBoundsException if {@code index} is negative or not less than the plane count
     */
    public float[] plane(int index) {
        return planes[Objects.checkIndex(index, planes.length)];
    }

    /**
     * Returns the plane of the first extra channel of a type.
     *
     * @param type the channel type
     * @return the plane, or empty when the image has no such channel
     * @throws NullPointerException if {@code type} is {@code null}
     */
    public Optional<float[]> extraChannel(JxlChannelType type) {
        Objects.requireNonNull(type, "type");
        var index = extraChannelTypes.indexOf(type);
        return index < 0 ? Optional.empty() : Optional.of(planes[colorChannels + index]);
    }
}
