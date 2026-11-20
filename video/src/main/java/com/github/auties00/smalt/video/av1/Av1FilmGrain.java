package com.github.auties00.smalt.video.av1;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * A set of AV1 film grain synthesis parameters: the grain a decoder adds to the reconstructed
 * pictures of the frames that signal it (AV1 specification sections 5.9.30 and 7.18.3).
 *
 * <p>The grain of each plane is an autoregressive pattern scaled by a piecewise linear function of
 * the sample intensity. Scaling functions are given by their points, whose values and scalings are
 * 8-bit numbers at every bit depth; the luma function has at most 14 points, each chroma function
 * at most 10, and the values of a function's points increase strictly. The autoregressive filter of
 * lag {@code L} has {@code 2 * L * (L + 1)} luma coefficients and, for each chroma plane, one more:
 * the weight of the co-located luma grain. Coefficients are integers from -128 to 127 in units of
 * {@code 2^-arCoefficientShift}.
 *
 * <p>The random seed of a set is the seed of the first frame an {@link Av1Encoder} signals the set
 * on, at the start of a stream; each later frame advances the seed. Instances are immutable and
 * thread-safe; create them with {@link #builder()}.
 */
public final class Av1FilmGrain {
    /**
     * One point of a scaling function.
     *
     * @param value the sample intensity at the 8-bit scale, from 0 to 255
     * @param scaling the scaling of the grain at that intensity, from 0 to 255
     */
    public record ScalingPoint(int value, int scaling) {
        /**
         * Validates the components.
         *
         * @throws IllegalArgumentException if a component is not in [0, 255]
         */
        public ScalingPoint {
            if (value < 0 || value > 255 || scaling < 0 || scaling > 255) {
                throw new IllegalArgumentException("scaling point out of range: " + value + ", " + scaling);
            }
        }
    }

    private final int randomSeed;

    private final List<ScalingPoint> lumaPoints;

    private final List<ScalingPoint> cbPoints;

    private final List<ScalingPoint> crPoints;

    private final boolean chromaScalingFromLuma;

    private final int scalingShift;

    private final int arCoefficientLag;

    private final int[] lumaArCoefficients;

    private final int[] cbArCoefficients;

    private final int[] crArCoefficients;

    private final int arCoefficientShift;

    private final int grainScaleShift;

    private final int cbMultiplier;

    private final int cbLumaMultiplier;

    private final int cbOffset;

    private final int crMultiplier;

    private final int crLumaMultiplier;

    private final int crOffset;

    private final boolean overlap;

    private final boolean clipToRestrictedRange;

    private Av1FilmGrain(Builder builder) {
        this.randomSeed = builder.randomSeed;
        this.lumaPoints = List.copyOf(builder.lumaPoints);
        this.cbPoints = List.copyOf(builder.cbPoints);
        this.crPoints = List.copyOf(builder.crPoints);
        this.chromaScalingFromLuma = builder.chromaScalingFromLuma;
        this.scalingShift = builder.scalingShift;
        this.arCoefficientLag = builder.arCoefficientLag;
        this.lumaArCoefficients = builder.lumaArCoefficients.clone();
        this.cbArCoefficients = builder.cbArCoefficients.clone();
        this.crArCoefficients = builder.crArCoefficients.clone();
        this.arCoefficientShift = builder.arCoefficientShift;
        this.grainScaleShift = builder.grainScaleShift;
        this.cbMultiplier = builder.cbMultiplier;
        this.cbLumaMultiplier = builder.cbLumaMultiplier;
        this.cbOffset = builder.cbOffset;
        this.crMultiplier = builder.crMultiplier;
        this.crLumaMultiplier = builder.crLumaMultiplier;
        this.crOffset = builder.crOffset;
        this.overlap = builder.overlap;
        this.clipToRestrictedRange = builder.clipToRestrictedRange;
    }

    /**
     * Returns a builder of grain without any scaling point, which adds no grain.
     *
     * @return the builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns a builder initialized with these parameters.
     *
     * @return the builder
     */
    public Builder toBuilder() {
        return new Builder(this);
    }

    /**
     * {@return the random seed of the grain pattern, from 0 to 65535; 0 by default}
     */
    public int randomSeed() {
        return randomSeed;
    }

    /**
     * {@return the points of the luma scaling function, at most 14 of increasing value; none by
     * default, which adds no luma grain}
     */
    public List<ScalingPoint> lumaPoints() {
        return lumaPoints;
    }

    /**
     * {@return the points of the Cb scaling function, at most 10 of increasing value; none by
     * default, which adds no Cb grain unless {@link #chromaScalingFromLuma()} is set}
     */
    public List<ScalingPoint> cbPoints() {
        return cbPoints;
    }

    /**
     * {@return the points of the Cr scaling function, at most 10 of increasing value; none by
     * default, which adds no Cr grain unless {@link #chromaScalingFromLuma()} is set}
     */
    public List<ScalingPoint> crPoints() {
        return crPoints;
    }

    /**
     * {@return whether the chroma grain is scaled by the luma scaling function, in which case
     * there are no chroma points; {@code false} by default}
     */
    public boolean chromaScalingFromLuma() {
        return chromaScalingFromLuma;
    }

    /**
     * {@return the right shift applied to the scaled grain, from 8 to 11; 8 by default}
     */
    public int scalingShift() {
        return scalingShift;
    }

    /**
     * {@return the lag of the autoregressive filter, from 0 to 3; 0 by default}
     */
    public int arCoefficientLag() {
        return arCoefficientLag;
    }

    /**
     * {@return the luma autoregressive coefficients, {@code 2 * lag * (lag + 1)} of them; the
     * array is a copy}
     */
    public int[] lumaArCoefficients() {
        return lumaArCoefficients.clone();
    }

    /**
     * {@return the Cb autoregressive coefficients, {@code 2 * lag * (lag + 1) + 1} of them, the
     * last weighting the luma grain; the array is a copy}
     */
    public int[] cbArCoefficients() {
        return cbArCoefficients.clone();
    }

    /**
     * {@return the Cr autoregressive coefficients, {@code 2 * lag * (lag + 1) + 1} of them, the
     * last weighting the luma grain; the array is a copy}
     */
    public int[] crArCoefficients() {
        return crArCoefficients.clone();
    }

    /**
     * {@return the precision of the autoregressive coefficients, the base-2 logarithm of their
     * unit's inverse, from 6 to 9; 6 by default}
     */
    public int arCoefficientShift() {
        return arCoefficientShift;
    }

    /**
     * {@return the right shift of the Gaussian values the grain is generated from, from 0 to 3; 0
     * by default}
     */
    public int grainScaleShift() {
        return grainScaleShift;
    }

    /**
     * {@return the weight of the Cb sample in the intensity that indexes the Cb scaling function,
     * from 0 to 255 for a weight of {@code (cbMultiplier - 128) / 64}; 128 by default, a weight of
     * zero}
     */
    public int cbMultiplier() {
        return cbMultiplier;
    }

    /**
     * {@return the weight of the co-located average luma sample in the intensity that indexes the Cb
     * scaling function, from 0 to 255 for a weight of {@code (cbLumaMultiplier - 128) / 64}; 192 by
     * default, a weight of one}
     */
    public int cbLumaMultiplier() {
        return cbLumaMultiplier;
    }

    /**
     * {@return the offset of the intensity that indexes the Cb scaling function, from 0 to 511 for
     * an offset of {@code cbOffset - 256} at the 8-bit scale; 256 by default, no offset}
     */
    public int cbOffset() {
        return cbOffset;
    }

    /**
     * {@return the weight of the Cr sample in the intensity that indexes the Cr scaling function,
     * from 0 to 255 as for {@link #cbMultiplier()}; 128 by default}
     */
    public int crMultiplier() {
        return crMultiplier;
    }

    /**
     * {@return the weight of the co-located average luma sample in the intensity that indexes the Cr
     * scaling function, from 0 to 255 as for {@link #cbLumaMultiplier()}; 192 by default}
     */
    public int crLumaMultiplier() {
        return crLumaMultiplier;
    }

    /**
     * {@return the offset of the intensity that indexes the Cr scaling function, from 0 to 511 as
     * for {@link #cbOffset()}; 256 by default}
     */
    public int crOffset() {
        return crOffset;
    }

    /**
     * {@return whether the grain of neighboring 32x32 blocks is blended across their edges;
     * {@code true} by default}
     */
    public boolean overlap() {
        return overlap;
    }

    /**
     * {@return whether the samples with grain are clipped to the limited range instead of the
     * full range; {@code false} by default}
     */
    public boolean clipToRestrictedRange() {
        return clipToRestrictedRange;
    }

    /**
     * {@return whether these parameters add grain to any plane}
     */
    public boolean addsGrain() {
        return !lumaPoints.isEmpty() || !cbPoints.isEmpty() || !crPoints.isEmpty() || chromaScalingFromLuma;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Av1FilmGrain that && randomSeed == that.randomSeed && lumaPoints.equals(that.lumaPoints)
                && cbPoints.equals(that.cbPoints) && crPoints.equals(that.crPoints)
                && chromaScalingFromLuma == that.chromaScalingFromLuma && scalingShift == that.scalingShift
                && arCoefficientLag == that.arCoefficientLag
                && Arrays.equals(lumaArCoefficients, that.lumaArCoefficients)
                && Arrays.equals(cbArCoefficients, that.cbArCoefficients)
                && Arrays.equals(crArCoefficients, that.crArCoefficients)
                && arCoefficientShift == that.arCoefficientShift && grainScaleShift == that.grainScaleShift
                && cbMultiplier == that.cbMultiplier && cbLumaMultiplier == that.cbLumaMultiplier
                && cbOffset == that.cbOffset && crMultiplier == that.crMultiplier
                && crLumaMultiplier == that.crLumaMultiplier && crOffset == that.crOffset
                && overlap == that.overlap && clipToRestrictedRange == that.clipToRestrictedRange;
    }

    @Override
    public int hashCode() {
        var h = Objects.hash(randomSeed, lumaPoints, cbPoints, crPoints, chromaScalingFromLuma, scalingShift,
                arCoefficientLag, arCoefficientShift, grainScaleShift, cbMultiplier, cbLumaMultiplier, cbOffset,
                crMultiplier, crLumaMultiplier, crOffset, overlap, clipToRestrictedRange);
        h = 31 * h + Arrays.hashCode(lumaArCoefficients);
        h = 31 * h + Arrays.hashCode(cbArCoefficients);
        return 31 * h + Arrays.hashCode(crArCoefficients);
    }

    @Override
    public String toString() {
        return "Av1FilmGrain[seed=" + randomSeed + ", luma=" + lumaPoints + ", cb=" + cbPoints + ", cr=" + crPoints
                + ", lag=" + arCoefficientLag + "]";
    }

    /**
     * Builds {@link Av1FilmGrain} parameters. Instances are not thread-safe.
     */
    public static final class Builder {
        private int randomSeed;

        private List<ScalingPoint> lumaPoints = List.of();

        private List<ScalingPoint> cbPoints = List.of();

        private List<ScalingPoint> crPoints = List.of();

        private boolean chromaScalingFromLuma;

        private int scalingShift = 8;

        private int arCoefficientLag;

        private int[] lumaArCoefficients = new int[0];

        private int[] cbArCoefficients = new int[1];

        private int[] crArCoefficients = new int[1];

        private int arCoefficientShift = 6;

        private int grainScaleShift;

        private int cbMultiplier = 128;

        private int cbLumaMultiplier = 192;

        private int cbOffset = 256;

        private int crMultiplier = 128;

        private int crLumaMultiplier = 192;

        private int crOffset = 256;

        private boolean overlap = true;

        private boolean clipToRestrictedRange;

        private Builder() {
        }

        private Builder(Av1FilmGrain grain) {
            this.randomSeed = grain.randomSeed;
            this.lumaPoints = grain.lumaPoints;
            this.cbPoints = grain.cbPoints;
            this.crPoints = grain.crPoints;
            this.chromaScalingFromLuma = grain.chromaScalingFromLuma;
            this.scalingShift = grain.scalingShift;
            this.arCoefficientLag = grain.arCoefficientLag;
            this.lumaArCoefficients = grain.lumaArCoefficients;
            this.cbArCoefficients = grain.cbArCoefficients;
            this.crArCoefficients = grain.crArCoefficients;
            this.arCoefficientShift = grain.arCoefficientShift;
            this.grainScaleShift = grain.grainScaleShift;
            this.cbMultiplier = grain.cbMultiplier;
            this.cbLumaMultiplier = grain.cbLumaMultiplier;
            this.cbOffset = grain.cbOffset;
            this.crMultiplier = grain.crMultiplier;
            this.crLumaMultiplier = grain.crLumaMultiplier;
            this.crOffset = grain.crOffset;
            this.overlap = grain.overlap;
            this.clipToRestrictedRange = grain.clipToRestrictedRange;
        }

        /**
         * Sets the random seed; see {@link Av1FilmGrain#randomSeed()}.
         *
         * @param seed the seed, from 0 to 65535
         * @return this builder
         */
        public Builder randomSeed(int seed) {
            this.randomSeed = seed;
            return this;
        }

        /**
         * Sets the luma scaling function; see {@link Av1FilmGrain#lumaPoints()}.
         *
         * @param points the points, at most 14 of increasing value
         * @return this builder
         */
        public Builder lumaPoints(List<ScalingPoint> points) {
            this.lumaPoints = List.copyOf(points);
            return this;
        }

        /**
         * Sets the chroma scaling functions; see {@link Av1FilmGrain#cbPoints()} and
         * {@link Av1FilmGrain#crPoints()}.
         *
         * @param cb the points of the Cb function, at most 10 of increasing value
         * @param cr the points of the Cr function, at most 10 of increasing value
         * @return this builder
         */
        public Builder chromaPoints(List<ScalingPoint> cb, List<ScalingPoint> cr) {
            this.cbPoints = List.copyOf(cb);
            this.crPoints = List.copyOf(cr);
            return this;
        }

        /**
         * Sets whether the chroma grain follows the luma scaling function; see
         * {@link Av1FilmGrain#chromaScalingFromLuma()}.
         *
         * @param fromLuma whether the luma function scales chroma
         * @return this builder
         */
        public Builder chromaScalingFromLuma(boolean fromLuma) {
            this.chromaScalingFromLuma = fromLuma;
            return this;
        }

        /**
         * Sets the scaling shift; see {@link Av1FilmGrain#scalingShift()}.
         *
         * @param shift the shift, from 8 to 11
         * @return this builder
         */
        public Builder scalingShift(int shift) {
            this.scalingShift = shift;
            return this;
        }

        /**
         * Sets the autoregressive filter; see {@link Av1FilmGrain#arCoefficientLag()}.
         *
         * @param lag the lag, from 0 to 3
         * @param shift the coefficient precision, from 6 to 9
         * @param luma the luma coefficients, {@code 2 * lag * (lag + 1)} of them
         * @param cb the Cb coefficients, {@code 2 * lag * (lag + 1) + 1} of them
         * @param cr the Cr coefficients, {@code 2 * lag * (lag + 1) + 1} of them
         * @return this builder
         */
        public Builder arCoefficients(int lag, int shift, int[] luma, int[] cb, int[] cr) {
            this.arCoefficientLag = lag;
            this.arCoefficientShift = shift;
            this.lumaArCoefficients = luma.clone();
            this.cbArCoefficients = cb.clone();
            this.crArCoefficients = cr.clone();
            return this;
        }

        /**
         * Sets the grain scale shift; see {@link Av1FilmGrain#grainScaleShift()}.
         *
         * @param shift the shift, from 0 to 3
         * @return this builder
         */
        public Builder grainScaleShift(int shift) {
            this.grainScaleShift = shift;
            return this;
        }

        /**
         * Sets how the Cb scaling function is indexed; see {@link Av1FilmGrain#cbMultiplier()}.
         *
         * @param multiplier the Cb weight, from 0 to 255
         * @param lumaMultiplier the luma weight, from 0 to 255
         * @param offset the offset, from 0 to 511
         * @return this builder
         */
        public Builder cbCombination(int multiplier, int lumaMultiplier, int offset) {
            this.cbMultiplier = multiplier;
            this.cbLumaMultiplier = lumaMultiplier;
            this.cbOffset = offset;
            return this;
        }

        /**
         * Sets how the Cr scaling function is indexed; see {@link Av1FilmGrain#crMultiplier()}.
         *
         * @param multiplier the Cr weight, from 0 to 255
         * @param lumaMultiplier the luma weight, from 0 to 255
         * @param offset the offset, from 0 to 511
         * @return this builder
         */
        public Builder crCombination(int multiplier, int lumaMultiplier, int offset) {
            this.crMultiplier = multiplier;
            this.crLumaMultiplier = lumaMultiplier;
            this.crOffset = offset;
            return this;
        }

        /**
         * Sets whether the grain of neighboring blocks is blended; see {@link Av1FilmGrain#overlap()}.
         *
         * @param overlap whether the edges are blended
         * @return this builder
         */
        public Builder overlap(boolean overlap) {
            this.overlap = overlap;
            return this;
        }

        /**
         * Sets whether samples with grain are clipped to the limited range; see
         * {@link Av1FilmGrain#clipToRestrictedRange()}.
         *
         * @param restricted whether the limited range applies
         * @return this builder
         */
        public Builder clipToRestrictedRange(boolean restricted) {
            this.clipToRestrictedRange = restricted;
            return this;
        }

        /**
         * Returns the parameters holding the settings of this builder.
         *
         * @return the parameters
         * @throws IllegalArgumentException if the seed is not in [0, 65535], there are more than 14
         * luma or 10 chroma points, the values of a function's points do not increase strictly,
         * chroma points are given with chroma scaling from luma, the scaling shift is not in
         * [8, 11], the lag is not in [0, 3], a coefficient array has the wrong length or a
         * coefficient is not in [-128, 127], the coefficient shift is not in [6, 9], the grain scale
         * shift is not in [0, 3], a multiplier is not in [0, 255] or an offset is not in [0, 511]
         */
        public Av1FilmGrain build() {
            range("random seed", randomSeed, 0, 65535);
            points("luma", lumaPoints, 14);
            points("cb", cbPoints, 10);
            points("cr", crPoints, 10);
            if (chromaScalingFromLuma && (!cbPoints.isEmpty() || !crPoints.isEmpty())) {
                throw new IllegalArgumentException("chroma scaling from luma takes no chroma points");
            }
            range("scaling shift", scalingShift, 8, 11);
            range("ar coefficient lag", arCoefficientLag, 0, 3);
            var n = 2 * arCoefficientLag * (arCoefficientLag + 1);
            coefficients("luma", lumaArCoefficients, n);
            coefficients("cb", cbArCoefficients, n + 1);
            coefficients("cr", crArCoefficients, n + 1);
            range("ar coefficient shift", arCoefficientShift, 6, 9);
            range("grain scale shift", grainScaleShift, 0, 3);
            range("cb multiplier", cbMultiplier, 0, 255);
            range("cb luma multiplier", cbLumaMultiplier, 0, 255);
            range("cb offset", cbOffset, 0, 511);
            range("cr multiplier", crMultiplier, 0, 255);
            range("cr luma multiplier", crLumaMultiplier, 0, 255);
            range("cr offset", crOffset, 0, 511);
            return new Av1FilmGrain(this);
        }

        private static void range(String name, int value, int min, int max) {
            if (value < min || value > max) {
                throw new IllegalArgumentException(name + " " + value + " is not in [" + min + ", " + max + "]");
            }
        }

        private static void points(String name, List<ScalingPoint> points, int max) {
            if (points.size() > max) {
                throw new IllegalArgumentException(points.size() + " " + name + " points, at most " + max);
            }
            for (var i = 1; i < points.size(); i++) {
                if (points.get(i).value() <= points.get(i - 1).value()) {
                    throw new IllegalArgumentException(name + " point values do not increase at " + i);
                }
            }
        }

        private static void coefficients(String name, int[] coefficients, int count) {
            if (coefficients.length != count) {
                throw new IllegalArgumentException(coefficients.length + " " + name + " coefficients, need " + count);
            }
            for (var c : coefficients) {
                range(name + " coefficient", c, -128, 127);
            }
        }
    }
}
