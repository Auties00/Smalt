package com.github.auties00.smalt.image.jpeg.internal;

import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorRange;
import com.github.auties00.smalt.image.MatrixCoefficients;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.YuvPicture;

/**
 * Supplies the rows of a picture as full-resolution, full-range BT.601 Y'CbCr samples, the input
 * of the encoder's downsampling stage.
 *
 * <p>Each call fills the first {@code width} entries of the three row arrays for one picture row;
 * a monochrome consumer passes {@code null} chroma rows.
 */
abstract sealed class YccRows permits YccRows.FromRgba, YccRows.FromYuv, YccRows.FromOtherYuv {
    /**
     * Constructs a row source.
     */
    YccRows() {
    }

    /**
     * Fills one row.
     *
     * @param row the picture row
     * @param y the destination luma samples
     * @param cb the destination Cb samples, or {@code null} when only luma is needed
     * @param cr the destination Cr samples, or {@code null} when only luma is needed
     */
    abstract void fill(int row, byte[] y, byte[] cb, byte[] cr);

    /**
     * Returns a row source over the same picture that may fill rows concurrently with this one.
     *
     * @return this source when it keeps no scratch state, otherwise a new one
     */
    YccRows copy() {
        return this;
    }

    /**
     * Converts an RGBA picture with libjpeg's integer transform.
     */
    static final class FromRgba extends YccRows {
        /**
         * The picture.
         */
        private final RgbaPicture picture;

        /**
         * The color conversion kernel.
         */
        private final ColorKernel kernel;

        /**
         * Constructs the rows of an RGBA picture.
         *
         * @param picture the source pixels
         * @param kernel the color conversion kernel
         */
        FromRgba(RgbaPicture picture, ColorKernel kernel) {
            this.picture = picture;
            this.kernel = kernel;
        }

        @Override
        void fill(int row, byte[] y, byte[] cb, byte[] cr) {
            var offset = row * picture.stride();
            if (cb == null) {
                kernel.toGray(picture.pixels(), offset, picture.width(), y);
            } else {
                kernel.toYcc(picture.pixels(), offset, picture.width(), y, cb, cr);
            }
        }
    }

    /**
     * Reads a full-range BT.601 planar picture, upsampling its chroma to full resolution with
     * libjpeg's triangle filter.
     */
    static final class FromYuv extends YccRows {
        /**
         * The picture.
         */
        private final YuvPicture picture;

        /**
         * The full-resolution Cb plane, or {@code null} for a monochrome picture.
         */
        private final byte[] cbPlane;

        /**
         * The full-resolution Cr plane, or {@code null} for a monochrome picture.
         */
        private final byte[] crPlane;

        /**
         * Constructs the rows of a planar picture.
         *
         * @param picture the picture, full range with a BT.601 matrix
         */
        FromYuv(YuvPicture picture) {
            this.picture = picture;
            var format = picture.chromaFormat();
            if (!format.hasChroma()) {
                cbPlane = null;
                crPlane = null;
            } else if (format == ChromaFormat.YUV444) {
                cbPlane = JpegWriter.tight(picture.u(), picture.uvStride(), picture.width(), picture.height());
                crPlane = JpegWriter.tight(picture.v(), picture.uvStride(), picture.width(), picture.height());
            } else {
                var hMax = 1 << format.shiftX();
                var vMax = 1 << format.shiftY();
                cbPlane = Upsampler.upsample(picture.u(), picture.uvStride(), picture.chromaWidth(), picture.chromaHeight(),
                        1, 1, hMax, vMax, picture.width(), picture.height(), true);
                crPlane = Upsampler.upsample(picture.v(), picture.uvStride(), picture.chromaWidth(), picture.chromaHeight(),
                        1, 1, hMax, vMax, picture.width(), picture.height(), true);
            }
        }

        @Override
        void fill(int row, byte[] y, byte[] cb, byte[] cr) {
            var width = picture.width();
            System.arraycopy(picture.y(), row * picture.yStride(), y, 0, width);
            if (cb == null) {
                return;
            }
            if (cbPlane == null) {
                java.util.Arrays.fill(cb, 0, width, (byte) 128);
                java.util.Arrays.fill(cr, 0, width, (byte) 128);
            } else {
                System.arraycopy(cbPlane, row * width, cb, 0, width);
                System.arraycopy(crPlane, row * width, cr, 0, width);
            }
        }
    }

    /**
     * Converts a planar picture with a different matrix or a limited range to full-range BT.601,
     * through R'G'B'.
     *
     * <p>Chroma is taken from the co-sited or covering sample (nearest neighbor). Supported
     * matrices are those defined by a pair of luma weights (BT.601, BT.470BG, BT.709, FCC,
     * SMPTE 240M, BT.2020 non-constant luminance, unspecified as BT.601), identity (GBR) and
     * YCgCo.
     */
    static final class FromOtherYuv extends YccRows {
        /**
         * The picture.
         */
        private final YuvPicture picture;

        /**
         * The red luma weight, or {@code -1} for identity and {@code -2} for YCgCo.
         */
        private final double kr;

        /**
         * The blue luma weight.
         */
        private final double kb;

        /**
         * Whether the samples use the limited range.
         */
        private final boolean limited;

        /**
         * The packed RGBA scratch row.
         */
        private final byte[] rgba;

        /**
         * The color conversion kernel.
         */
        private final ColorKernel kernel;

        /**
         * Constructs the converting rows.
         *
         * @param picture the source samples, in a matrix other than BT.601
         * @param kernel the color conversion kernel
         * @throws IllegalArgumentException if the picture's matrix has no R'G'B' conversion here
         */
        FromOtherYuv(YuvPicture picture, ColorKernel kernel) {
            this.picture = picture;
            this.kernel = kernel;
            this.limited = picture.color().range() == ColorRange.LIMITED;
            this.rgba = new byte[picture.width() * 4];
            var matrix = picture.color().matrix();
            switch (matrix) {
                case BT601, BT470BG, UNSPECIFIED -> {
                    kr = 0.299;
                    kb = 0.114;
                }
                case BT709 -> {
                    kr = 0.2126;
                    kb = 0.0722;
                }
                case FCC -> {
                    kr = 0.30;
                    kb = 0.11;
                }
                case SMPTE240M -> {
                    kr = 0.212;
                    kb = 0.087;
                }
                case BT2020_NCL -> {
                    kr = 0.2627;
                    kb = 0.0593;
                }
                case IDENTITY -> {
                    kr = -1;
                    kb = 0;
                }
                case YCGCO -> {
                    kr = -2;
                    kb = 0;
                }
                default -> throw new IllegalArgumentException("cannot encode a picture with the " + matrix
                        + " matrix to JPEG; convert it to RGB first");
            }
        }

        @Override
        YccRows copy() {
            return new FromOtherYuv(picture, kernel);
        }

        /**
         * Returns whether these rows convert a matrix.
         *
         * @param matrix the matrix coefficients of a picture
         * @return {@code true} for the matrices of two fixed luma weights (unspecified included), identity and YCgCo
         */
        static boolean converts(MatrixCoefficients matrix) {
            return switch (matrix) {
                case BT601, BT470BG, UNSPECIFIED, BT709, FCC, SMPTE240M, BT2020_NCL, IDENTITY, YCGCO -> true;
                default -> false;
            };
        }

        /**
         * Returns whether a matrix needs no conversion to JPEG's BT.601.
         *
         * @param matrix the matrix coefficients of a picture
         * @return {@code true} for BT.601, BT.470BG and unspecified
         */
        static boolean isBt601(MatrixCoefficients matrix) {
            var resolved = matrix.effective();
            return resolved == MatrixCoefficients.BT601 || resolved == MatrixCoefficients.BT470BG;
        }

        @Override
        void fill(int row, byte[] y, byte[] cb, byte[] cr) {
            var format = picture.chromaFormat();
            var width = picture.width();
            var yRow = row * picture.yStride();
            var cRow = (row >> format.shiftY()) * picture.uvStride();
            for (var x = 0; x < width; x++) {
                var luma = picture.y()[yRow + x] & 0xFF;
                double u;
                double v;
                if (format.hasChroma()) {
                    var cx = x >> format.shiftX();
                    u = (picture.u()[cRow + cx] & 0xFF) - 128.0;
                    v = (picture.v()[cRow + cx] & 0xFF) - 128.0;
                } else {
                    u = 0;
                    v = 0;
                }
                double yn;
                if (limited) {
                    yn = (luma - 16.0) / 219.0;
                    u /= 224.0;
                    v /= 224.0;
                } else {
                    yn = luma / 255.0;
                    u /= 255.0;
                    v /= 255.0;
                }
                double r;
                double g;
                double b;
                if (kr == -1) {
                    g = yn;
                    b = limited ? (u * 224.0 + 112.0) / 219.0 : u + 128.0 / 255.0;
                    r = limited ? (v * 224.0 + 112.0) / 219.0 : v + 128.0 / 255.0;
                } else if (kr == -2) {
                    var t = yn - u;
                    g = yn + u;
                    b = t - v;
                    r = t + v;
                } else {
                    r = yn + 2 * (1 - kr) * v;
                    b = yn + 2 * (1 - kb) * u;
                    g = (yn - kr * r - kb * b) / (1 - kr - kb);
                }
                rgba[4 * x] = (byte) toSample(r);
                rgba[4 * x + 1] = (byte) toSample(g);
                rgba[4 * x + 2] = (byte) toSample(b);
                rgba[4 * x + 3] = (byte) 255;
            }
            if (cb == null) {
                kernel.toGray(rgba, 0, width, y);
            } else {
                kernel.toYcc(rgba, 0, width, y, cb, cr);
            }
        }

        /**
         * Converts a normalized component to an 8-bit sample.
         *
         * @param value the component, nominally 0 to 1
         * @return the rounded and clamped sample
         */
        private static int toSample(double value) {
            var scaled = (int) Math.round(value * 255.0);
            return scaled < 0 ? 0 : Math.min(scaled, 255);
        }
    }
}
