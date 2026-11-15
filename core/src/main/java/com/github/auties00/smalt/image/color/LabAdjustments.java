package com.github.auties00.smalt.image.color;

import com.github.auties00.smalt.internal.check.Checks;

import java.util.Objects;

/**
 * Holds the settings of an abstract profile that adjusts colors in L*a*b*, which
 * {@link ColorProfile#labAdjustment(LabAdjustments)} builds. Instances are immutable and thread-safe; create them with
 * {@link #builder()} or a preset such as {@link #defaults()}.
 */
public final class LabAdjustments {
    /**
     * The lowest color temperature of a white balance, in kelvins.
     */
    public static final int MIN_TEMPERATURE = 4000;

    /**
     * The highest color temperature of a white balance, in kelvins.
     */
    public static final int MAX_TEMPERATURE = 25000;

    private static final LabAdjustments DEFAULTS = new LabAdjustments(0, 1, 0, 0, 0, 0, 17);

    private final double brightness;
    private final double contrast;
    private final double hue;
    private final double saturation;
    private final int sourceTemperature;
    private final int targetTemperature;
    private final int gridPoints;

    private LabAdjustments(double brightness, double contrast, double hue, double saturation, int sourceTemperature,
                           int targetTemperature, int gridPoints) {
        this.brightness = brightness;
        this.contrast = contrast;
        this.hue = hue;
        this.saturation = saturation;
        this.sourceTemperature = sourceTemperature;
        this.targetTemperature = targetTemperature;
        this.gridPoints = gridPoints;
    }

    /**
     * Returns the adjustments that change nothing, sampled on 17 points per channel.
     *
     * @return the default adjustments
     */
    public static LabAdjustments defaults() {
        return DEFAULTS;
    }

    /**
     * Returns a builder initialized with {@link #defaults()}.
     *
     * @return the builder
     */
    public static Builder builder() {
        return new Builder(DEFAULTS);
    }

    /**
     * Returns a builder initialized with these options.
     *
     * @return the builder
     */
    public Builder toBuilder() {
        return new Builder(this);
    }

    /**
     * {@return the amount added to L* after the contrast, from -100 to 100; {@code 0} by default}
     */
    public double brightness() {
        return brightness;
    }

    /**
     * {@return the factor L* is multiplied by, from 0 to 10; {@code 1} by default}
     */
    public double contrast() {
        return contrast;
    }

    /**
     * {@return the angle added to the hue angle, in degrees, from -360 to 360; {@code 0} by default}
     */
    public double hue() {
        return hue;
    }

    /**
     * {@return the amount added to the chroma C*, from -200 to 200; {@code 0} by default}
     */
    public double saturation() {
        return saturation;
    }

    /**
     * {@return the color temperature, in kelvins, of the white that the white balance moves to the
     * {@linkplain #targetTemperature() target}, or {@code 0} for no white balance, the default}
     *
     * <p>The white of each temperature is the CIE daylight locus point of that correlated color temperature; colors
     * are moved by reading them as L*a*b* relative to the source white and writing them relative to the target
     * white.
     */
    public int sourceTemperature() {
        return sourceTemperature;
    }

    /**
     * {@return the color temperature, in kelvins, that the white balance moves the source white to, or {@code 0} for
     * no white balance, the default}
     */
    public int targetTemperature() {
        return targetTemperature;
    }

    /**
     * {@return the number of points per L*a*b* channel of the profile's lookup table, from 2 to 100; 17 by default}
     *
     * <p>More points follow the adjustments more closely and make a larger profile, growing with the cube of the
     * count.
     */
    public int gridPoints() {
        return gridPoints;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof LabAdjustments that && Double.compare(brightness, that.brightness) == 0
                && Double.compare(contrast, that.contrast) == 0 && Double.compare(hue, that.hue) == 0
                && Double.compare(saturation, that.saturation) == 0 && sourceTemperature == that.sourceTemperature
                && targetTemperature == that.targetTemperature && gridPoints == that.gridPoints;
    }

    @Override
    public int hashCode() {
        return Objects.hash(brightness, contrast, hue, saturation, sourceTemperature, targetTemperature, gridPoints);
    }

    @Override
    public String toString() {
        return "LabAdjustments[brightness=" + brightness + ", contrast=" + contrast + ", hue=" + hue + ", saturation="
                + saturation + ", sourceTemperature=" + sourceTemperature + ", targetTemperature="
                + targetTemperature + ", gridPoints=" + gridPoints + "]";
    }

    /**
     * Builds {@link LabAdjustments}.
     *
     * <p>Setters store their value; {@link #build()} checks the ranges. Instances are not thread-safe.
     */
    public static final class Builder {
        private double brightness;
        private double contrast;
        private double hue;
        private double saturation;
        private boolean whiteBalance;
        private int sourceTemperature;
        private int targetTemperature;
        private int gridPoints;

        private Builder(LabAdjustments adjustments) {
            this.brightness = adjustments.brightness;
            this.contrast = adjustments.contrast;
            this.hue = adjustments.hue;
            this.saturation = adjustments.saturation;
            this.whiteBalance = adjustments.sourceTemperature != 0;
            this.sourceTemperature = adjustments.sourceTemperature;
            this.targetTemperature = adjustments.targetTemperature;
            this.gridPoints = adjustments.gridPoints;
        }

        /**
         * Sets the brightness; see {@link LabAdjustments#brightness()}.
         *
         * @param brightness the value
         * @return this builder
         */
        public Builder brightness(double brightness) {
            this.brightness = brightness;
            return this;
        }

        /**
         * Sets the contrast; see {@link LabAdjustments#contrast()}.
         *
         * @param contrast the value
         * @return this builder
         */
        public Builder contrast(double contrast) {
            this.contrast = contrast;
            return this;
        }

        /**
         * Sets the hue rotation; see {@link LabAdjustments#hue()}.
         *
         * @param hue the value
         * @return this builder
         */
        public Builder hue(double hue) {
            this.hue = hue;
            return this;
        }

        /**
         * Sets the saturation; see {@link LabAdjustments#saturation()}.
         *
         * @param saturation the value
         * @return this builder
         */
        public Builder saturation(double saturation) {
            this.saturation = saturation;
            return this;
        }

        /**
         * Sets the white balance; see {@link LabAdjustments#sourceTemperature()} and
         * {@link LabAdjustments#targetTemperature()}.
         *
         * @param source the temperature of the white to move, in kelvins
         * @param target the temperature to move it to, in kelvins
         * @return this builder
         */
        public Builder whiteBalance(int source, int target) {
            this.whiteBalance = true;
            this.sourceTemperature = source;
            this.targetTemperature = target;
            return this;
        }

        /**
         * Removes the white balance; see {@link LabAdjustments#sourceTemperature()}.
         *
         * @return this builder
         */
        public Builder noWhiteBalance() {
            this.whiteBalance = false;
            this.sourceTemperature = 0;
            this.targetTemperature = 0;
            return this;
        }

        /**
         * Sets the number of lookup table points per channel; see {@link LabAdjustments#gridPoints()}.
         *
         * @param gridPoints the value
         * @return this builder
         */
        public Builder gridPoints(int gridPoints) {
            this.gridPoints = gridPoints;
            return this;
        }

        /**
         * Returns adjustments holding the settings of this builder.
         *
         * @return the adjustments
         * @throws IllegalArgumentException if a value is NaN or outside its range, or a white balance temperature is
         * outside {@value LabAdjustments#MIN_TEMPERATURE} to {@value LabAdjustments#MAX_TEMPERATURE} kelvins
         */
        public LabAdjustments build() {
            Checks.inRange("brightness", brightness, -100.0, 100.0);
            Checks.inRange("contrast", contrast, 0.0, 10.0);
            Checks.inRange("hue", hue, -360.0, 360.0);
            Checks.inRange("saturation", saturation, -200.0, 200.0);
            Checks.inRange("grid points", gridPoints, 2, 100);
            if (whiteBalance) {
                Checks.inRange("source temperature", sourceTemperature, MIN_TEMPERATURE, MAX_TEMPERATURE);
                Checks.inRange("target temperature", targetTemperature, MIN_TEMPERATURE, MAX_TEMPERATURE);
            }
            return new LabAdjustments(brightness, contrast, hue, saturation, sourceTemperature, targetTemperature,
                    gridPoints);
        }
    }
}
