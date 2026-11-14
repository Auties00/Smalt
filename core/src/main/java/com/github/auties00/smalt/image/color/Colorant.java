package com.github.auties00.smalt.image.color;

import java.util.Arrays;
import java.util.Objects;

/**
 * A colorant of an ICC profile's colorant table ({@code colorantTableType}, ICC.1:2022 section 10.5): the name of
 * the ink or primary of one device channel and its color in the profile connection space.
 *
 * <p>{@link ColorProfile#colorants()} lists the colorants of a profile's device channels in channel order, and
 * {@link ColorProfile#outputColorants()} those of a device link's output channels. The names identify the inks of a
 * multi-ink ({@link ProfileColorSpace#INK}) profile, whose color space signature does not imply them.
 *
 * <p>Instances are immutable and thread-safe. Two colorants are equal when every component is.
 */
public final class Colorant {
    /**
     * The name of the colorant.
     */
    private final String name;

    /**
     * The connection space of the values, {@link ProfileColorSpace#LAB} or {@link ProfileColorSpace#XYZ}.
     */
    private final ProfileColorSpace connectionSpace;

    /**
     * The connection space values in natural units.
     */
    private final double[] connectionSpaceValues;

    /**
     * Constructs a colorant.
     *
     * @param name the name of the ink or primary
     * @param connectionSpace the connection space
     * @param connectionSpaceValues the connection space values, owned by the new instance
     */
    Colorant(String name, ProfileColorSpace connectionSpace, double[] connectionSpaceValues) {
        this.name = name;
        this.connectionSpace = connectionSpace;
        this.connectionSpaceValues = connectionSpaceValues;
    }

    /**
     * Decodes colorant {@code index} of a {@code colorantTableType} tag.
     *
     * @param colorants the colorants of the tag
     * @param index the colorant index
     * @param xyz whether the connection space is XYZ rather than L*a*b*
     * @return the colorant
     */
    static Colorant of(IccReader.Colorants colorants, int index, boolean xyz) {
        var pcs = new double[3];
        for (var c = 0; c < 3; c++) {
            var value = colorants.pcs()[index * 3 + c];
            if (xyz) {
                pcs[c] = value / 32768.0;
            } else {
                pcs[c] = c == 0 ? value * 100.0 / 65535.0 : value * 255.0 / 65535.0 - 128.0;
            }
        }
        return new Colorant(colorants.names()[index], xyz ? ProfileColorSpace.XYZ : ProfileColorSpace.LAB, pcs);
    }

    /**
     * Returns the name of the colorant.
     *
     * @return the name as the profile stores it (7-bit ASCII by the specification, read as ISO 8859-1; up to 32
     * characters, 31 when the field is terminated as the specification requires), for example {@code "Orange"};
     * empty when the profile leaves it blank
     */
    public String name() {
        return name;
    }

    /**
     * {@return the connection space of {@link #connectionSpaceValues()}: {@link ProfileColorSpace#LAB} or
     * {@link ProfileColorSpace#XYZ}}
     */
    public ProfileColorSpace connectionSpace() {
        return connectionSpace;
    }

    /**
     * Returns the relative colorimetric color of the colorant alone at full strength.
     *
     * @return a new array of three values: L* {@code 0..100}, a* and b* {@code -128..127}, or D50-relative X, Y and
     * Z with the white at Y {@code 1.0}, decoded from the 16-bit connection space encoding of ICC.1:2022 section
     * 6.3.4.2
     */
    public double[] connectionSpaceValues() {
        return connectionSpaceValues.clone();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Colorant that && name.equals(that.name) && connectionSpace == that.connectionSpace
                && Arrays.equals(connectionSpaceValues, that.connectionSpaceValues);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, connectionSpace, Arrays.hashCode(connectionSpaceValues));
    }

    @Override
    public String toString() {
        return "Colorant[" + name + ", " + connectionSpace + " " + Arrays.toString(connectionSpaceValues) + "]";
    }
}
