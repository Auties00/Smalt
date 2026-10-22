/* Ports Little CMS 2.17 (cmslut.c, cmsnamed.c). Copyright Marti Maria Saguer; MIT License. Modified for Smalt. */
package com.github.auties00.smalt.image.color;

import java.util.Arrays;

/**
 * Computes one step of a color transform pipeline on normalized {@code float} values.
 *
 * <p>Values travel between stages in the Little CMS normalized encoding: device values span
 * {@code 0..1}; CIE XYZ is divided by {@link ColorMath#MAX_ENCODEABLE_XYZ}; CIE L*a*b* is
 * {@code L / 100}, {@code (a + 128) / 255} and {@code (b + 128) / 255} (the ICC version 4
 * encoding). Inside a floating-point {@code multiProcessElementType} table, between the
 * normalization matrices {@link Matrix#fromLabFloat()}, {@link Matrix#toLabFloat()},
 * {@link Matrix#fromXyzFloat()} and {@link Matrix#toXyzFloat()}, XYZ and L*a*b* values are in
 * their natural units.
 *
 * <p>Instances are immutable and thread-safe.
 *
 * @implNote This implementation follows the stage evaluators of Little CMS 2.17 ({@code cmslut.c}): curves evaluate per
 * channel, matrices accumulate in double precision, lookup tables quantize their inputs to 16 bits and interpolate in
 * fixed point, floating-point lookup tables interpolate in single precision, and the L*a*b* conversions use the
 * version 4 encoding.
 */
abstract sealed class Stage permits Stage.Curves, Stage.Matrix, Stage.Clut, Stage.FloatClut, Stage.NamedColor,
        Stage.LabToXyz, Stage.XyzToLab {
    /**
     * Identifies the structure of a stage.
     */
    enum Kind {
        /**
         * A set of per-channel curves.
         */
        CURVES,

        /**
         * A matrix with an optional offset.
         */
        MATRIX,

        /**
         * A multidimensional lookup table.
         */
        CLUT,

        /**
         * The lookup of a color in a named color list.
         */
        NAMED_COLOR,

        /**
         * The L*a*b* to XYZ conversion.
         */
        LAB_TO_XYZ,

        /**
         * The XYZ to L*a*b* conversion.
         */
        XYZ_TO_LAB
    }

    /**
     * Identifies what a stage implements, which decides which stages cancel or merge.
     */
    enum Role {
        /**
         * An ordinary stage.
         */
        PLAIN,

        /**
         * A stage that leaves its input unchanged.
         */
        IDENTITY,

        /**
         * The L*a*b* version 2 to version 4 rescaling.
         */
        LAB_V2_TO_V4,

        /**
         * The L*a*b* version 4 to version 2 rescaling.
         */
        LAB_V4_TO_V2,

        /**
         * The scaling of natural L*a*b* values to the normalized encoding ({@code cmsSigLab2FloatPCS}).
         */
        LAB_FLOAT_TO_PCS,

        /**
         * The scaling of normalized L*a*b* values to natural units ({@code cmsSigFloatPCS2Lab}).
         */
        PCS_TO_LAB_FLOAT,

        /**
         * The scaling of natural XYZ values to the normalized encoding ({@code cmsSigXYZ2FloatPCS}).
         */
        XYZ_FLOAT_TO_PCS,

        /**
         * The scaling of normalized XYZ values to natural units ({@code cmsSigFloatPCS2XYZ}).
         */
        PCS_TO_XYZ_FLOAT
    }

    /**
     * The number of inputs.
     */
    final int inputs;

    /**
     * The number of outputs.
     */
    final int outputs;

    /**
     * What the stage implements.
     */
    final Role role;

    /**
     * Constructs a stage.
     *
     * @param inputs the number of inputs
     * @param outputs the number of outputs
     * @param role what the stage implements
     */
    private Stage(int inputs, int outputs, Role role) {
        this.inputs = inputs;
        this.outputs = outputs;
        this.role = role;
    }

    /**
     * Returns the structure of the stage.
     *
     * @return the kind
     */
    abstract Kind kind();

    /**
     * Evaluates the stage.
     *
     * @param in the inputs
     * @param out receives the outputs
     */
    abstract void evaluate(float[] in, float[] out);

    /**
     * Applies one curve per channel.
     */
    static final class Curves extends Stage {
        /**
         * The curves, one per channel.
         */
        final ToneCurve[] curves;

        /**
         * Creates a curve set.
         *
         * @param curves one curve per channel, shared
         * @param role what the stage implements
         */
        Curves(ToneCurve[] curves, Role role) {
            super(curves.length, curves.length, role);
            this.curves = curves.clone();
        }

        /**
         * Creates a curve set.
         *
         * @param curves one curve per channel, shared
         */
        Curves(ToneCurve... curves) {
            this(curves, Role.PLAIN);
        }

        /**
         * Creates a set of identity curves that pipeline simplification removes.
         *
         * @param channels the number of channels
         * @return the stage
         */
        static Curves identity(int channels) {
            var curves = new ToneCurve[channels];
            Arrays.fill(curves, ToneCurve.IDENTITY);
            return new Curves(curves, Role.IDENTITY);
        }

        @Override
        Kind kind() {
            return Kind.CURVES;
        }

        @Override
        void evaluate(float[] in, float[] out) {
            for (var i = 0; i < curves.length; i++) {
                out[i] = curves[i].evaluate(in[i]);
            }
        }
    }

    /**
     * Multiplies by a matrix and adds an optional offset.
     */
    static final class Matrix extends Stage {
        /**
         * The coefficients, {@code outputs} rows of {@code inputs} columns.
         */
        final double[] coefficients;

        /**
         * The offsets, one per output, or {@code null} for none.
         */
        final double[] offset;

        /**
         * Creates a matrix stage.
         *
         * @param inputs the number of inputs
         * @param outputs the number of outputs
         * @param coefficients the coefficients in row-major order
         * @param offset the offsets, or {@code null}
         * @param role what the stage implements
         */
        Matrix(int inputs, int outputs, double[] coefficients, double[] offset, Role role) {
            super(inputs, outputs, role);
            this.coefficients = coefficients.clone();
            this.offset = offset == null ? null : offset.clone();
        }

        /**
         * Creates a square 3x3 matrix stage.
         *
         * @param coefficients the coefficients in row-major order
         * @param offset the offsets, or {@code null}
         */
        Matrix(double[] coefficients, double[] offset) {
            this(3, 3, coefficients, offset, Role.PLAIN);
        }

        /**
         * Creates the rescaling from version 2 to version 4 L*a*b* encoding.
         *
         * @return the stage
         */
        static Matrix labV2ToV4() {
            var s = 65535.0 / 65280.0;
            return new Matrix(3, 3, new double[]{s, 0, 0, 0, s, 0, 0, 0, s}, null, Role.LAB_V2_TO_V4);
        }

        /**
         * Creates the rescaling from version 4 to version 2 L*a*b* encoding.
         *
         * @return the stage
         */
        static Matrix labV4ToV2() {
            var s = 65280.0 / 65535.0;
            return new Matrix(3, 3, new double[]{s, 0, 0, 0, s, 0, 0, 0, s}, null, Role.LAB_V4_TO_V2);
        }

        /**
         * Creates the scaling of natural L*a*b* values to the normalized encoding
         * ({@code _cmsStageNormalizeFromLabFloat}).
         *
         * @return the stage
         */
        static Matrix fromLabFloat() {
            return new Matrix(3, 3, new double[]{1.0 / 100.0, 0, 0, 0, 1.0 / 255.0, 0, 0, 0, 1.0 / 255.0},
                    new double[]{0, 128.0 / 255.0, 128.0 / 255.0}, Role.LAB_FLOAT_TO_PCS);
        }

        /**
         * Creates the scaling of normalized L*a*b* values to natural units ({@code _cmsStageNormalizeToLabFloat}).
         *
         * @return the stage
         */
        static Matrix toLabFloat() {
            return new Matrix(3, 3, new double[]{100.0, 0, 0, 0, 255.0, 0, 0, 0, 255.0}, new double[]{0, -128.0, -128.0},
                    Role.PCS_TO_LAB_FLOAT);
        }

        /**
         * Creates the scaling of natural XYZ values to the normalized encoding
         * ({@code _cmsStageNormalizeFromXyzFloat}).
         *
         * @return the stage
         */
        static Matrix fromXyzFloat() {
            var n = 32768.0 / 65535.0;
            return new Matrix(3, 3, new double[]{n, 0, 0, 0, n, 0, 0, 0, n}, null, Role.XYZ_FLOAT_TO_PCS);
        }

        /**
         * Creates the scaling of normalized XYZ values to natural units ({@code _cmsStageNormalizeToXyzFloat}).
         *
         * @return the stage
         */
        static Matrix toXyzFloat() {
            var n = 65535.0 / 32768.0;
            return new Matrix(3, 3, new double[]{n, 0, 0, 0, n, 0, 0, 0, n}, null, Role.PCS_TO_XYZ_FLOAT);
        }

        @Override
        Kind kind() {
            return Kind.MATRIX;
        }

        @Override
        void evaluate(float[] in, float[] out) {
            for (var i = 0; i < outputs; i++) {
                var sum = 0.0;
                for (var j = 0; j < inputs; j++) {
                    sum += in[j] * coefficients[i * inputs + j];
                }
                if (offset != null) {
                    sum += offset[i];
                }
                out[i] = (float) sum;
            }
        }
    }

    /**
     * Interpolates a multidimensional lookup table.
     */
    static final class Clut extends Stage {
        /**
         * The interpolator over the table.
         */
        final Interpolator interpolator;

        /**
         * The number of grid points per input.
         */
        final int[] grid;

        /**
         * The 16-bit table samples.
         */
        final int[] table;

        /**
         * Creates a table stage.
         *
         * @param grid the number of grid points per input
         * @param outputs the number of outputs
         * @param table the 16-bit samples
         * @param trilinear whether a three-input table interpolates trilinearly
         * @param role what the stage implements
         */
        Clut(int[] grid, int outputs, int[] table, boolean trilinear, Role role) {
            super(grid.length, outputs, role);
            this.grid = grid.clone();
            this.table = table;
            this.interpolator = new Interpolator(this.grid, outputs, table, trilinear);
        }

        /**
         * Returns a copy of this table that interpolates trilinearly.
         *
         * @return the stage
         */
        Clut trilinear() {
            return new Clut(grid, outputs, table, true, role);
        }

        @Override
        Kind kind() {
            return Kind.CLUT;
        }

        @Override
        void evaluate(float[] in, float[] out) {
            var in16 = new int[inputs];
            var out16 = new int[outputs];
            for (var i = 0; i < inputs; i++) {
                in16[i] = ColorMath.saturateWord(in[i] * 65535.0);
            }
            interpolator.evaluate(in16, out16);
            for (var i = 0; i < outputs; i++) {
                out[i] = out16[i] / 65535.0f;
            }
        }
    }

    /**
     * Interpolates a multidimensional lookup table of {@code float} samples, without quantizing its inputs.
     */
    static final class FloatClut extends Stage {
        /**
         * The interpolator over the table.
         */
        final FloatInterpolator interpolator;

        /**
         * Creates a table stage.
         *
         * @param grid the number of grid points per input, each at least two
         * @param outputs the number of outputs
         * @param table the samples, not copied
         */
        FloatClut(int[] grid, int outputs, float[] table) {
            super(grid.length, outputs, Role.PLAIN);
            this.interpolator = new FloatInterpolator(grid.clone(), outputs, table);
        }

        @Override
        Kind kind() {
            return Kind.CLUT;
        }

        @Override
        void evaluate(float[] in, float[] out) {
            interpolator.evaluate(in, out);
        }
    }

    /**
     * Looks up a color of a named color list: the input is the color index divided by 65535, the output its
     * connection space or device values divided by 65535 ({@code EvalNamedColorPCS}, {@code EvalNamedColor}).
     *
     * <p>An index at or past the end of the list gives zeros.
     */
    static final class NamedColor extends Stage {
        /**
         * The 16-bit values of each color, {@code outputs} per color.
         */
        private final int[] values;

        /**
         * Creates a lookup stage.
         *
         * @param outputs the number of values per color
         * @param values the 16-bit values of each color, {@code outputs} per color; not copied
         */
        NamedColor(int outputs, int[] values) {
            super(1, outputs, Role.PLAIN);
            this.values = values;
        }

        /**
         * {@return the number of colors}
         */
        int colors() {
            return values.length / outputs;
        }

        @Override
        Kind kind() {
            return Kind.NAMED_COLOR;
        }

        @Override
        void evaluate(float[] in, float[] out) {
            var index = ColorMath.saturateWord(in[0] * 65535.0);
            if (index >= colors()) {
                for (var o = 0; o < outputs; o++) {
                    out[o] = 0.0f;
                }
                return;
            }
            for (var o = 0; o < outputs; o++) {
                out[o] = (float) (values[index * outputs + o] / 65535.0);
            }
        }
    }

    /**
     * Converts encoded L*a*b* to encoded XYZ.
     */
    static final class LabToXyz extends Stage {
        /**
         * Creates the stage.
         */
        LabToXyz() {
            super(3, 3, Role.PLAIN);
        }

        @Override
        Kind kind() {
            return Kind.LAB_TO_XYZ;
        }

        @Override
        void evaluate(float[] in, float[] out) {
            var xyz = ColorMath.labToXyz(new double[]{in[0] * 100.0, in[1] * 255.0 - 128.0, in[2] * 255.0 - 128.0});
            out[0] = (float) (xyz[0] / ColorMath.MAX_ENCODEABLE_XYZ);
            out[1] = (float) (xyz[1] / ColorMath.MAX_ENCODEABLE_XYZ);
            out[2] = (float) (xyz[2] / ColorMath.MAX_ENCODEABLE_XYZ);
        }
    }

    /**
     * Converts encoded XYZ to encoded L*a*b*.
     */
    static final class XyzToLab extends Stage {
        /**
         * Creates the stage.
         */
        XyzToLab() {
            super(3, 3, Role.PLAIN);
        }

        @Override
        Kind kind() {
            return Kind.XYZ_TO_LAB;
        }

        @Override
        void evaluate(float[] in, float[] out) {
            var lab = ColorMath.xyzToLab(new double[]{
                    in[0] * ColorMath.MAX_ENCODEABLE_XYZ,
                    in[1] * ColorMath.MAX_ENCODEABLE_XYZ,
                    in[2] * ColorMath.MAX_ENCODEABLE_XYZ
            });
            out[0] = (float) (lab[0] / 100.0);
            out[1] = (float) ((lab[1] + 128.0) / 255.0);
            out[2] = (float) ((lab[2] + 128.0) / 255.0);
        }
    }
}
