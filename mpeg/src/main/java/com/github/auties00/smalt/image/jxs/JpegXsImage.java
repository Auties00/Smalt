package com.github.auties00.smalt.image.jxs;

import com.github.auties00.smalt.image.ColorInfo;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The decoded components of a JPEG XS image, at their stored precision and sampling.
 *
 * <p>The components are those the codestream codes after the inverse color transformation: R, G and B for a
 * codestream that applies the reversible color transform, the four color filter array samples of each 2x2 quad for a
 * Bayer codestream (Star-Tetrix transform), and the stored components otherwise (Y', Cb and Cr, gray, alpha and any
 * further components). The samples are unsigned, from 0 to {@code 2^precision - 1}.
 *
 * <p>Instances are not thread-safe: the sample arrays are shared, not copied, and the caller owns them.
 *
 * @param width the width of the image: the sampling grid, or twice its width for a Bayer codestream
 * @param height the height of the image: the sampling grid, or twice its height for a Bayer codestream
 * @param components the components, in codestream order
 * @param colorTransform the color transformation the codestream applied
 * @param cfaPattern the color filter array pattern of a Bayer codestream, or empty for any other
 * @param profileCode the profile code {@code Ppih} of the picture header
 * @param level the level and sublevel {@code Plev} of the picture header
 * @param color the color description of the JXS file's color specification box, or empty when the input is a raw
 * codestream or declares the color space otherwise
 * @param iccProfile the ICC profile of the JXS file's color specification box, or empty when it embeds none
 */
public record JpegXsImage(int width, int height, List<Component> components, ColorTransform colorTransform,
                          Optional<CfaPattern> cfaPattern, int profileCode, int level, Optional<ColorInfo> color,
                          Optional<byte[]> iccProfile) {
    /**
     * Validates the image.
     *
     * @throws IllegalArgumentException if a dimension is not positive or there are no components
     */
    public JpegXsImage {
        Objects.requireNonNull(components, "components");
        Objects.requireNonNull(colorTransform, "colorTransform");
        Objects.requireNonNull(cfaPattern, "cfaPattern");
        Objects.requireNonNull(color, "color");
        Objects.requireNonNull(iccProfile, "iccProfile");
        if (width < 1 || height < 1 || components.isEmpty()) {
            throw new IllegalArgumentException("the image is " + width + "x" + height + " with "
                    + components.size() + " components");
        }
        components = List.copyOf(components);
    }

    /**
     * {@return the profile of the picture header, or empty for a code {@link JpegXsProfile} does not name}
     */
    public Optional<JpegXsProfile> profile() {
        return JpegXsProfile.of(profileCode);
    }

    /**
     * Returns the color filter array mosaic of a Bayer image: each component's samples at their position in the
     * 2x2 quads, a {@link #width()} by {@link #height()} plane.
     *
     * @return the mosaic, row by row, a new array, or empty when the image is not a Bayer image
     */
    public Optional<short[]> mosaic() {
        if (cfaPattern.isEmpty()) {
            return Optional.empty();
        }
        var quadWidth = width / 2;
        var quadHeight = height / 2;
        var out = new short[width * height];
        for (var c = 0; c < 4; c++) {
            var component = components.get(c);
            var ox = component.mosaicX;
            var oy = component.mosaicY;
            var samples = component.samples;
            for (var y = 0; y < quadHeight; y++) {
                var row = (2 * y + oy) * width + ox;
                var from = y * quadWidth;
                for (var x = 0; x < quadWidth; x++) {
                    out[row + 2 * x] = samples[from + x];
                }
            }
        }
        return Optional.of(out);
    }

    /**
     * One decoded component.
     *
     * @param width the width in samples
     * @param height the height in samples
     * @param precision the bit precision of the samples, from 1 to 16
     * @param sx the horizontal sampling factor relative to the sampling grid, 1 or 2
     * @param sy the vertical sampling factor relative to the sampling grid, 1 or 2
     * @param mosaicX the horizontal position of the component in each 2x2 quad of a Bayer image, 0 or 1, or 0 for any
     * other image
     * @param mosaicY the vertical position of the component in each 2x2 quad of a Bayer image, 0 or 1, or 0 for any
     * other image
     * @param samples the samples, row by row, as unsigned 16-bit values
     */
    public record Component(int width, int height, int precision, int sx, int sy, int mosaicX, int mosaicY,
                            short[] samples) {
        /**
         * Validates the component.
         *
         * @throws IllegalArgumentException if a dimension is not positive, the precision is outside 1 to 16 or the
         * sample array is shorter than {@code width * height}
         */
        public Component {
            Objects.requireNonNull(samples, "samples");
            if (width < 1 || height < 1 || precision < 1 || precision > 16
                    || samples.length < (long) width * height) {
                throw new IllegalArgumentException("invalid component " + width + "x" + height + " of " + precision
                        + " bits with " + samples.length + " samples");
            }
        }
    }

    /**
     * Enumerates the inverse color transformations of a JPEG XS codestream.
     */
    public enum ColorTransform {
        /**
         * No transformation ({@code Cpih} 0): the components are stored as they are presented.
         */
        NONE,
        /**
         * The reversible color transform of ISO/IEC 21122-1 annex F ({@code Cpih} 1), from R, G and B.
         */
        RCT,
        /**
         * The Star-Tetrix transform of color filter array data ({@code Cpih} 3).
         */
        STAR_TETRIX
    }

    /**
     * Enumerates the color filter array patterns a Bayer codestream registers its components with, named by the
     * colors of the 2x2 quad row by row.
     */
    public enum CfaPattern {
        /**
         * Red, green, green, blue.
         */
        RGGB,
        /**
         * Blue, green, green, red.
         */
        BGGR,
        /**
         * Green, red, blue, green.
         */
        GRBG,
        /**
         * Green, blue, red, green.
         */
        GBRG
    }
}
