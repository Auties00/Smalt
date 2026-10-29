/* Ports libjpeg-turbo (jccolor.c, cmyk.h, rdppm.c rescaling). Copyright (C) 1991-1996, Thomas G. Lane;
   Copyright (C) 2009-2025, D. R. Commander; IJG License. Modified for Smalt. */
package com.github.auties00.smalt.image.jpeg.internal;

import com.github.auties00.smalt.image.Channel16Picture;
import com.github.auties00.smalt.image.Channel8Picture;
import com.github.auties00.smalt.image.ChannelPicture;
import com.github.auties00.smalt.image.ChannelType;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.PixelLayout;
import com.github.auties00.smalt.image.Rgba16Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.image.jpeg.JpegEncoderOptions.ColorSpace;
import com.github.auties00.smalt.image.ops.ImageOps;

/**
 * Supplies the rows of a picture as full-resolution samples of a frame's components, at the frame's
 * precision and in its color space: the input of the encoder's downsampling stage.
 *
 * <p>Samples of a source of another bit depth {@code d} are rescaled to the precision {@code P} as
 * {@code (v * (2^P - 1) + (2^d - 1) / 2) / (2^d - 1)}. RGB becomes YCbCr and gray with the fixed-point
 * BT.601 transform of T.81's JFIF companion, ink amounts become inverted CMYK samples, RGB becomes
 * inverted CMYK with the naive transform {@code K = 1 - max(R, G, B)}, and YCCK is the YCbCr of the
 * complemented C, M and Y with K passed through. Each call fills the first {@code width} entries of each
 * component row.
 */
abstract sealed class SampleRows permits SampleRows.Ycc8, SampleRows.FromRgb, SampleRows.FromCmyk,
        SampleRows.FromYcc {
    /**
     * The fraction bits of the color transform constants.
     */
    private static final int SCALE_BITS = 16;

    /**
     * {@code FIX(0.29900)}.
     */
    private static final long R_Y = 19595;

    /**
     * {@code FIX(0.58700)}.
     */
    private static final long G_Y = 38470;

    /**
     * {@code FIX(0.11400)}.
     */
    private static final long B_Y = 7471;

    /**
     * {@code FIX(0.16874)}.
     */
    private static final long R_CB = 11059;

    /**
     * {@code FIX(0.33126)}.
     */
    private static final long G_CB = 21709;

    /**
     * {@code FIX(0.5)}.
     */
    private static final long HALF = 32768;

    /**
     * {@code FIX(0.41869)}.
     */
    private static final long G_CR = 27439;

    /**
     * {@code FIX(0.08131)}.
     */
    private static final long B_CR = 5329;

    /**
     * The picture width.
     */
    final int width;

    /**
     * The largest sample value, {@code 2^precision - 1}.
     */
    final int max;

    /**
     * Constructs a row source.
     *
     * @param width the picture width
     * @param precision the sample precision
     */
    SampleRows(int width, int precision) {
        this.width = width;
        this.max = (1 << precision) - 1;
    }

    /**
     * Fills one row of every component.
     *
     * @param row the picture row
     * @param out the destination rows, one per component, each at least {@code width} long
     */
    abstract void fill(int row, short[][] out);

    /**
     * Returns a row source over the same picture that may fill rows concurrently with this one.
     *
     * @return this source when it keeps no scratch state, otherwise a new one
     */
    SampleRows copy() {
        return this;
    }

    /**
     * Returns the rows of a picture for a frame.
     *
     * @param picture the picture to read
     * @param layout the frame
     * @return the rows
     */
    static SampleRows of(Picture picture, FrameLayout layout) {
        var precision = layout.precision;
        var space = layout.colorSpace;
        var gray = layout.count == 1;
        if (space == ColorSpace.YCBCR) {
            if (precision == 8) {
                return new Ycc8(picture, gray);
            }
            if (picture instanceof Yuv16Picture yuv && YccRows.isJpegYcc(yuv)
                    && (gray || yuv.chromaFormat() == ChromaFormat.YUV444 || !yuv.chromaFormat().hasChroma())) {
                return new FromYcc(yuv.width(), precision, gray, yuv.bitDepth(), yuv.y(), yuv.u(), yuv.v(), null, null,
                        null, yuv.yStride(), yuv.uvStride(), yuv.chromaFormat().hasChroma());
            }
            if (picture instanceof YuvPicture yuv && YccRows.isJpegYcc(yuv)
                    && (gray || yuv.chromaFormat() == ChromaFormat.YUV444 || !yuv.chromaFormat().hasChroma())) {
                return new FromYcc(yuv.width(), precision, gray, 8, null, null, null, yuv.y(), yuv.u(), yuv.v(),
                        yuv.yStride(), yuv.uvStride(), yuv.chromaFormat().hasChroma());
            }
        }
        if ((space == ColorSpace.CMYK || space == ColorSpace.YCCK) && picture instanceof ChannelPicture channels
                && isCmyk(channels.layout())) {
            var samples = channels instanceof Channel8Picture || channels instanceof Channel16Picture ? channels
                    : ImageOps.toChannel16(channels);
            return new FromCmyk(samples, precision, space == ColorSpace.YCCK);
        }
        var rgb = switch (picture) {
            case RgbaPicture rgba -> (Picture) rgba;
            case Rgba16Picture rgba -> rgba;
            default -> picture.bitDepth() > 8 ? ImageOps.toRgba16(picture) : ImageOps.toRgba(picture);
        };
        return new FromRgb(rgb, precision, space, gray);
    }

    /**
     * Returns whether a channel layout holds exactly the four process inks, in CMYK order, optionally
     * followed by alpha.
     *
     * @param layout the channels of a channel picture
     * @return {@code true} for CMYK and CMYK with alpha
     */
    static boolean isCmyk(PixelLayout layout) {
        return layout.colorChannels() == 4 && layout.channel(0).type() == ChannelType.CYAN
                && layout.channel(1).type() == ChannelType.MAGENTA && layout.channel(2).type() == ChannelType.YELLOW
                && layout.channel(3).type() == ChannelType.BLACK;
    }

    /**
     * Returns the table that rescales samples of one bit depth to another.
     *
     * @param depth the source bit depth
     * @param precision the target precision
     * @return the table indexed by source sample, or {@code null} when the depths are equal
     */
    static int[] rescaleTable(int depth, int precision) {
        if (depth == precision) {
            return null;
        }
        var maxIn = (1 << depth) - 1;
        var maxOut = (1L << precision) - 1;
        var table = new int[maxIn + 1];
        for (var value = 0; value <= maxIn; value++) {
            table[value] = (int) ((value * maxOut + maxIn / 2) / maxIn);
        }
        return table;
    }

    /**
     * Writes the YCbCr of one RGB sample.
     *
     * @param r the red sample
     * @param g the green sample
     * @param b the blue sample
     * @param center the chroma offset, {@code 2^(precision - 1)}
     * @param y the luma destination
     * @param cb the Cb destination
     * @param cr the Cr destination
     * @param x the destination index
     */
    static void toYcc(long r, long g, long b, long center, short[] y, short[] cb, short[] cr, int x) {
        var offset = (center << SCALE_BITS) + (1L << (SCALE_BITS - 1)) - 1;
        y[x] = (short) ((R_Y * r + G_Y * g + B_Y * b + (1L << (SCALE_BITS - 1))) >> SCALE_BITS);
        cb[x] = (short) ((-R_CB * r - G_CB * g + HALF * b + offset) >> SCALE_BITS);
        cr[x] = (short) ((HALF * r - G_CR * g - B_CR * b + offset) >> SCALE_BITS);
    }

    /**
     * Returns the luma of one RGB sample.
     *
     * @param r the red sample
     * @param g the green sample
     * @param b the blue sample
     * @return the luma
     */
    static int toGray(long r, long g, long b) {
        return (int) ((R_Y * r + G_Y * g + B_Y * b + (1L << (SCALE_BITS - 1))) >> SCALE_BITS);
    }

    /**
     * Supplies 8-bit YCbCr or gray rows through {@link YccRows}.
     */
    static final class Ycc8 extends SampleRows {
        /**
         * The source rows.
         */
        private final YccRows rows;

        /**
         * Whether only luma is produced.
         */
        private final boolean gray;

        /**
         * The scratch luma row.
         */
        private final byte[] y;

        /**
         * The scratch Cb row, or {@code null} for gray.
         */
        private final byte[] cb;

        /**
         * The scratch Cr row, or {@code null} for gray.
         */
        private final byte[] cr;

        /**
         * Constructs the rows.
         *
         * @param picture the picture to convert
         * @param gray whether only luma is produced
         */
        Ycc8(Picture picture, boolean gray) {
            this(YccRows.of(picture), picture.width(), gray);
        }

        /**
         * Constructs the rows over a row source.
         *
         * @param rows the source
         * @param width the picture width
         * @param gray whether only luma is produced
         */
        private Ycc8(YccRows rows, int width, boolean gray) {
            super(width, 8);
            this.rows = rows;
            this.gray = gray;
            this.y = new byte[width];
            this.cb = gray ? null : new byte[width];
            this.cr = gray ? null : new byte[width];
        }

        @Override
        SampleRows copy() {
            return new Ycc8(rows.copy(), width, gray);
        }

        @Override
        void fill(int row, short[][] out) {
            rows.fill(row, y, cb, cr);
            widen(y, out[0]);
            if (!gray) {
                widen(cb, out[1]);
                widen(cr, out[2]);
            }
        }

        /**
         * Copies unsigned bytes into shorts.
         *
         * @param from the bytes
         * @param to the destination
         */
        private void widen(byte[] from, short[] to) {
            for (var x = 0; x < width; x++) {
                to[x] = (short) (from[x] & 0xFF);
            }
        }
    }

    /**
     * Converts RGB pixels to the frame's color space.
     */
    static final class FromRgb extends SampleRows {
        /**
         * The 8-bit source, or {@code null}.
         */
        private final RgbaPicture rgba;

        /**
         * The 16-bit source, or {@code null}.
         */
        private final Rgba16Picture rgba16;

        /**
         * The rescaling table of the source samples, or {@code null} when they are at the precision.
         */
        private final int[] rescale;

        /**
         * The target color space.
         */
        private final ColorSpace space;

        /**
         * Whether a YCbCr target is gray.
         */
        private final boolean gray;

        /**
         * Constructs the rows.
         *
         * @param picture an {@link RgbaPicture} or {@link Rgba16Picture}
         * @param precision the target precision in bits
         * @param space the target color space
         * @param gray whether a YCbCr target is gray
         */
        FromRgb(Picture picture, int precision, ColorSpace space, boolean gray) {
            super(picture.width(), precision);
            this.rgba = picture instanceof RgbaPicture eight ? eight : null;
            this.rgba16 = picture instanceof Rgba16Picture sixteen ? sixteen : null;
            this.rescale = rescaleTable(rgba != null ? 8 : 16, precision);
            this.space = space;
            this.gray = gray;
        }

        @Override
        void fill(int row, short[][] out) {
            var center = (long) (max + 1) >> 1;
            for (var x = 0; x < width; x++) {
                int r;
                int g;
                int b;
                if (rgba != null) {
                    var i = row * rgba.stride() + 4 * x;
                    var pixels = rgba.pixels();
                    r = pixels[i] & 0xFF;
                    g = pixels[i + 1] & 0xFF;
                    b = pixels[i + 2] & 0xFF;
                } else {
                    var i = row * rgba16.stride() + 4 * x;
                    var pixels = rgba16.pixels();
                    r = pixels[i] & 0xFFFF;
                    g = pixels[i + 1] & 0xFFFF;
                    b = pixels[i + 2] & 0xFFFF;
                }
                if (rescale != null) {
                    r = rescale[r];
                    g = rescale[g];
                    b = rescale[b];
                }
                switch (space) {
                    case RGB -> {
                        out[0][x] = (short) r;
                        out[1][x] = (short) g;
                        out[2][x] = (short) b;
                    }
                    case YCBCR -> {
                        if (gray) {
                            out[0][x] = (short) toGray(r, g, b);
                        } else {
                            toYcc(r, g, b, center, out[0], out[1], out[2], x);
                        }
                    }
                    case CMYK, YCCK -> {
                        var maxval = (double) max;
                        var c = 1.0 - r / maxval;
                        var m = 1.0 - g / maxval;
                        var y = 1.0 - b / maxval;
                        var k = Math.min(Math.min(c, m), y);
                        if (k == 1.0) {
                            c = 0.0;
                            m = 0.0;
                            y = 0.0;
                        } else {
                            c = (c - k) / (1.0 - k);
                            m = (m - k) / (1.0 - k);
                            y = (y - k) / (1.0 - k);
                        }
                        var storedC = (int) (maxval - c * maxval + 0.5);
                        var storedM = (int) (maxval - m * maxval + 0.5);
                        var storedY = (int) (maxval - y * maxval + 0.5);
                        var storedK = (int) (maxval - k * maxval + 0.5);
                        if (space == ColorSpace.CMYK) {
                            out[0][x] = (short) storedC;
                            out[1][x] = (short) storedM;
                            out[2][x] = (short) storedY;
                        } else {
                            toYcc(max - storedC, max - storedM, max - storedY, center, out[0], out[1], out[2], x);
                        }
                        out[3][x] = (short) storedK;
                    }
                }
            }
        }
    }

    /**
     * Converts ink amounts to inverted CMYK samples or to YCCK.
     */
    static final class FromCmyk extends SampleRows {
        /**
         * The source, a {@link Channel8Picture} or {@link Channel16Picture}.
         */
        private final ChannelPicture picture;

        /**
         * The rescaling table of the source samples, or {@code null} when they are at the precision.
         */
        private final int[] rescale;

        /**
         * Whether the target is YCCK.
         */
        private final boolean ycck;

        /**
         * Constructs the rows.
         *
         * @param picture the source, with the inks first
         * @param precision the target precision in bits
         * @param ycck whether the target is YCCK
         */
        FromCmyk(ChannelPicture picture, int precision, boolean ycck) {
            super(picture.width(), precision);
            this.picture = picture;
            this.rescale = rescaleTable(picture.bitDepth(), precision);
            this.ycck = ycck;
        }

        @Override
        void fill(int row, short[][] out) {
            var center = (long) (max + 1) >> 1;
            var channels = picture.channelCount();
            var base = row * picture.stride();
            for (var x = 0; x < width; x++) {
                var i = base + x * channels;
                int c;
                int m;
                int y;
                int k;
                if (picture instanceof Channel8Picture eight) {
                    var samples = eight.pixels();
                    c = samples[i] & 0xFF;
                    m = samples[i + 1] & 0xFF;
                    y = samples[i + 2] & 0xFF;
                    k = samples[i + 3] & 0xFF;
                } else {
                    var samples = ((Channel16Picture) picture).pixels();
                    c = samples[i] & 0xFFFF;
                    m = samples[i + 1] & 0xFFFF;
                    y = samples[i + 2] & 0xFFFF;
                    k = samples[i + 3] & 0xFFFF;
                }
                if (rescale != null) {
                    c = rescale[c];
                    m = rescale[m];
                    y = rescale[y];
                    k = rescale[k];
                }
                if (ycck) {
                    toYcc(c, m, y, center, out[0], out[1], out[2], x);
                } else {
                    out[0][x] = (short) (max - c);
                    out[1][x] = (short) (max - m);
                    out[2][x] = (short) (max - y);
                }
                out[3][x] = (short) (max - k);
            }
        }
    }

    /**
     * Rescales full-range BT.601 planar samples without a matrix conversion: the luma of any such picture, or
     * all three planes of a 4:4:4 one.
     */
    static final class FromYcc extends SampleRows {
        /**
         * Whether only luma is produced.
         */
        private final boolean gray;

        /**
         * The rescaling table, or {@code null} when the source is at the precision.
         */
        private final int[] rescale;

        /**
         * The 16-bit planes, or {@code null}.
         */
        private final short[] y16;

        /**
         * The 16-bit Cb plane, or {@code null}.
         */
        private final short[] u16;

        /**
         * The 16-bit Cr plane, or {@code null}.
         */
        private final short[] v16;

        /**
         * The 8-bit luma plane, or {@code null}.
         */
        private final byte[] y8;

        /**
         * The 8-bit Cb plane, or {@code null}.
         */
        private final byte[] u8;

        /**
         * The 8-bit Cr plane, or {@code null}.
         */
        private final byte[] v8;

        /**
         * The luma stride.
         */
        private final int yStride;

        /**
         * The chroma stride.
         */
        private final int uvStride;

        /**
         * Whether the source has chroma planes.
         */
        private final boolean chroma;

        /**
         * The source bit depth.
         */
        private final int depth;

        /**
         * Constructs the rows.
         *
         * @param width the picture width
         * @param precision the target precision in bits
         * @param gray whether only luma is produced
         * @param depth the source bit depth
         * @param y16 the 16-bit luma plane, or {@code null}
         * @param u16 the 16-bit Cb plane, or {@code null}
         * @param v16 the 16-bit Cr plane, or {@code null}
         * @param y8 the 8-bit luma plane, or {@code null}
         * @param u8 the 8-bit Cb plane, or {@code null}
         * @param v8 the 8-bit Cr plane, or {@code null}
         * @param yStride the luma stride
         * @param uvStride the chroma stride
         * @param chroma whether the source has full-resolution chroma planes
         */
        FromYcc(int width, int precision, boolean gray, int depth, short[] y16, short[] u16, short[] v16, byte[] y8,
                byte[] u8, byte[] v8, int yStride, int uvStride, boolean chroma) {
            super(width, precision);
            this.gray = gray;
            this.depth = depth;
            this.rescale = rescaleTable(depth, precision);
            this.y16 = y16;
            this.u16 = u16;
            this.v16 = v16;
            this.y8 = y8;
            this.u8 = u8;
            this.v8 = v8;
            this.yStride = yStride;
            this.uvStride = uvStride;
            this.chroma = chroma;
        }

        @Override
        void fill(int row, short[][] out) {
            var mask = (1 << depth) - 1;
            for (var x = 0; x < width; x++) {
                var luma = y16 != null ? y16[row * yStride + x] & mask : y8[row * yStride + x] & 0xFF;
                out[0][x] = (short) (rescale == null ? luma : rescale[luma]);
                if (gray) {
                    continue;
                }
                int cb;
                int cr;
                if (!chroma) {
                    cb = (mask + 1) >> 1;
                    cr = cb;
                } else if (u16 != null) {
                    cb = u16[row * uvStride + x] & mask;
                    cr = v16[row * uvStride + x] & mask;
                } else {
                    cb = u8[row * uvStride + x] & 0xFF;
                    cr = v8[row * uvStride + x] & 0xFF;
                }
                out[1][x] = (short) (rescale == null ? cb : rescale[cb]);
                out[2][x] = (short) (rescale == null ? cr : rescale[cr]);
            }
        }
    }
}
