package com.github.auties00.smalt.image.color;

import java.util.Arrays;
import java.util.Objects;

/**
 * A color of a named color profile ({@code namedColor2Type}, ICC.1:2022 section 10.17): its name, its value in the
 * profile connection space and its device coordinates.
 *
 * <p>{@link ColorProfile#namedColors()} lists them in index order. A {@link ColorTransform} whose source is the
 * named color profile takes {@link #index()} as its one source sample.
 *
 * <p>Instances are immutable and thread-safe. Two colors are equal when every component is.
 */
public final class NamedColor {
    /**
     * The position in the list of the profile.
     */
    private final int index;

    /**
     * The prefix the profile gives every name.
     */
    private final String prefix;

    /**
     * The root name.
     */
    private final String rootName;

    /**
     * The suffix the profile gives every name.
     */
    private final String suffix;

    /**
     * The connection space of the values, {@link ProfileColorSpace#LAB} or {@link ProfileColorSpace#XYZ}.
     */
    private final ProfileColorSpace connectionSpace;

    /**
     * The connection space values in natural units.
     */
    private final double[] connectionSpaceValues;

    /**
     * The device coordinates, {@code 0..1}.
     */
    private final double[] deviceValues;

    /**
     * Constructs a color.
     *
     * @param index the position in the list
     * @param prefix the prefix of the name
     * @param rootName the root name
     * @param suffix the suffix of the name
     * @param connectionSpace the connection space
     * @param connectionSpaceValues the connection space values, owned by the new instance
     * @param deviceValues the device coordinates, owned by the new instance
     */
    NamedColor(int index, String prefix, String rootName, String suffix, ProfileColorSpace connectionSpace,
               double[] connectionSpaceValues, double[] deviceValues) {
        this.index = index;
        this.prefix = prefix;
        this.rootName = rootName;
        this.suffix = suffix;
        this.connectionSpace = connectionSpace;
        this.connectionSpaceValues = connectionSpaceValues;
        this.deviceValues = deviceValues;
    }

    /**
     * Decodes color {@code index} of a {@code namedColor2Type} tag.
     *
     * @param colors the colors of the tag
     * @param index the color index
     * @param xyz whether the connection space is XYZ rather than L*a*b*
     * @return the color
     */
    static NamedColor of(IccReader.NamedColors colors, int index, boolean xyz) {
        var pcs = new double[3];
        for (var c = 0; c < 3; c++) {
            var value = colors.pcs()[index * 3 + c];
            if (xyz) {
                pcs[c] = value / 32768.0;
            } else {
                pcs[c] = c == 0 ? value * 100.0 / 65280.0 : value / 256.0 - 128.0;
            }
        }
        var device = new double[colors.devices()];
        for (var c = 0; c < device.length; c++) {
            device[c] = colors.device()[index * device.length + c] / 65535.0;
        }
        return new NamedColor(index, colors.prefix(), colors.roots()[index], colors.suffix(),
                xyz ? ProfileColorSpace.XYZ : ProfileColorSpace.LAB, pcs, device);
    }

    /**
     * {@return the position of the color in {@link ColorProfile#namedColors()}, the source sample that selects it
     * in a {@link ColorTransform}}
     */
    public int index() {
        return index;
    }

    /**
     * {@return the full name: the profile's prefix, the root name and the profile's suffix}
     */
    public String name() {
        return prefix + rootName + suffix;
    }

    /**
     * {@return the root name, without the prefix and suffix the profile gives every name}
     */
    public String rootName() {
        return rootName;
    }

    /**
     * {@return the connection space of {@link #connectionSpaceValues()}: {@link ProfileColorSpace#LAB} or
     * {@link ProfileColorSpace#XYZ}}
     */
    public ProfileColorSpace connectionSpace() {
        return connectionSpace;
    }

    /**
     * Returns the value of the color in the profile connection space.
     *
     * <p>L*a*b* values are {@code L*} from 0 to 100 and {@code a*} and {@code b*} from -128 to about 127.996 (the
     * 16-bit encoding of ICC version 2, which named colors keep in every version); XYZ values are relative to a
     * {@code Y} of 1 for the white, from 0 to about 1.99997.
     *
     * @return a copy of the three values
     */
    public double[] connectionSpaceValues() {
        return connectionSpaceValues.clone();
    }

    /**
     * Returns the device coordinates of the color, in the order of the channels of the profile's data color space.
     *
     * @return a copy of the coordinates as fractions of the 16-bit range, {@code 0} to {@code 1}; empty when the
     * profile gives none
     */
    public double[] deviceValues() {
        return deviceValues.clone();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof NamedColor that && index == that.index && prefix.equals(that.prefix)
                && rootName.equals(that.rootName) && suffix.equals(that.suffix)
                && connectionSpace == that.connectionSpace
                && Arrays.equals(connectionSpaceValues, that.connectionSpaceValues)
                && Arrays.equals(deviceValues, that.deviceValues);
    }

    @Override
    public int hashCode() {
        return Objects.hash(index, prefix, rootName, suffix, connectionSpace, Arrays.hashCode(connectionSpaceValues),
                Arrays.hashCode(deviceValues));
    }

    @Override
    public String toString() {
        return "NamedColor[" + index + ", " + name() + ", " + connectionSpace + " "
                + Arrays.toString(connectionSpaceValues) + ", device " + Arrays.toString(deviceValues) + "]";
    }
}
