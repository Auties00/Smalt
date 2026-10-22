/* Ports libjpeg-turbo (jcparam.c, jcmaster.c). Copyright (C) 1991-1998, Thomas G. Lane, Guido Vollbeding;
   Copyright (C) 2009-2024, D. R. Commander; IJG License. Modified for Smalt. */
package com.github.auties00.smalt.image.jpeg.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.PixelDensity;
import com.github.auties00.smalt.image.Rgba16Picture;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.image.color.ColorProfile;
import com.github.auties00.smalt.image.internal.density.DensityTerms;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.image.jpeg.JpegEncoderOptions;
import com.github.auties00.smalt.image.jpeg.JpegEncoderOptions.ColorSpace;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Encodes a picture with {@link JpegEncoderOptions}: resolves the frame (precision, components,
 * sampling factors, quantization tables, scan script and file header) as libjpeg's {@code jpeg_set_defaults},
 * {@code jpeg_set_colorspace} and {@code cjpeg} switches do, and codes it with {@link JpegWriter} (baseline
 * YCbCr or gray), {@link DctFrameEncoder} and {@link CoefficientWriter} (every other DCT frame) or
 * {@link LosslessFrameEncoder}.
 */
public final class JpegEncoding {
    /**
     * The largest width or height of a JPEG frame.
     */
    private static final int MAX_DIMENSION = 65535;

    /**
     * The most data units an interleaved MCU may hold (T.81 B.2.3).
     */
    private static final int MAX_UNITS_IN_MCU = 10;

    /**
     * Prevents instantiation.
     */
    private JpegEncoding() {
        throw new AssertionError();
    }

    /**
     * Encodes a picture.
     *
     * @param picture the picture; its alpha channel is ignored
     * @param options the settings
     * @return the JPEG file
     * @throws IllegalArgumentException if the picture is wider or taller than 65535 pixels, or the settings
     * do not fit the picture: a point transform not below the automatic precision, sampling factors that do
     * not divide the largest ones or give an MCU of more than 10 data units, a progressive bit position
     * beyond the precision's limit, or a lossless restart interval that is not a whole number of MCU rows
     * @throws MediaException.LimitExceeded if the color profile needs more than 255 {@code APP2} segments
     */
    public static byte[] encode(Picture picture, JpegEncoderOptions options) {
        return encode(picture, options, true);
    }

    /**
     * Encodes a picture, through {@link JpegWriter} when allowed and the frame is baseline YCbCr or gray, and
     * otherwise through the general path.
     *
     * @param picture the picture; its alpha channel is ignored
     * @param options the settings
     * @param baselineWriter whether {@link JpegWriter} may write a baseline frame
     * @return the JPEG file
     */
    static byte[] encode(Picture picture, JpegEncoderOptions options, boolean baselineWriter) {
        var width = picture.width();
        var height = picture.height();
        if (width > MAX_DIMENSION || height > MAX_DIMENSION) {
            throw new IllegalArgumentException("picture size " + width + "x" + height + " exceeds the JPEG limit of "
                    + MAX_DIMENSION + " pixels per side");
        }
        var lossless = options.lossless();
        var precision = options.precision().orElse(lossless ? Math.clamp(picture.bitDepth(), 2, 16) : 8);
        if (precision == 8 && (picture instanceof Rgba16Picture
                || picture instanceof Yuv16Picture yuv && YccRows.isJpegYcc(yuv))) {
            picture = ImageOps.toEightBit(picture);
        }
        var layout = layout(width, height, precision, options);
        var scans = scans(layout, options);
        var icc = options.colorProfile().map(ColorProfile::toByteArray).orElse(null);
        var quantTables = lossless ? null : quantTables(layout, options);
        if (baselineWriter && isBaseline(layout, options, scans, quantTables)) {
            var lumaTable = quantTables[0];
            var chromaTable = quantTables.length > 1 && quantTables[1] != null ? quantTables[1] : quantTables[0];
            var format = layout.count == 1 ? ChromaFormat.MONOCHROME
                    : layout.h[0] == 1 ? ChromaFormat.YUV444 : layout.v[0] == 1 ? ChromaFormat.YUV422 : ChromaFormat.YUV420;
            return JpegWriter.encode(picture, lumaTable, chromaTable, format, options.optimizeHuffman(),
                    options.restartInterval(), icc, jfifPayload(options.density()));
        }
        var markers = new ArrayList<MarkerSegment>();
        if (icc != null) {
            for (var segment : JpegIcc.segments(icc)) {
                markers.add(new MarkerSegment(0xE2, Arrays.copyOfRange(segment, 4, segment.length)));
            }
        }
        var header = header(layout, options.density());
        var planes = planes(picture, layout);
        var rows = planes == null ? SampleRows.of(picture, layout) : null;
        if (lossless) {
            return new LosslessFrameEncoder(layout, rows, planes, options.restartInterval(), options.arithmetic())
                    .write(header, markers, scans);
        }
        var coefficients = DctFrameEncoder.transform(layout, rows, planes, quantTables);
        var components = new CoefficientWriter.Component[layout.count];
        for (var c = 0; c < layout.count; c++) {
            components[c] = new CoefficientWriter.Component(layout.ids[c], layout.h[c], layout.v[c],
                    layout.quantSlots[c], layout.tableSlots[c], layout.tableSlots[c], coefficients[c],
                    layout.unitsWide[c], layout.unitsHigh[c]);
        }
        var writer = new CoefficientWriter(width, height, components, quantTables, options.restartInterval(), precision);
        return writer.write(header, markers, scans, options.progressive(), options.arithmetic(),
                options.optimizeHuffman());
    }

    /**
     * Returns whether a planar picture holds JPEG's own samples, full range with a BT.601 matrix, which a
     * YCbCr frame codes without conversion.
     *
     * @param picture the planar picture to check
     * @return {@code true} when its samples need no conversion
     */
    public static boolean isJpegYcc(YuvPicture picture) {
        return YccRows.isJpegYcc(picture);
    }

    /**
     * Returns whether a 16-bit planar picture holds JPEG's own samples, full range with a BT.601 matrix, which
     * a YCbCr frame codes without conversion.
     *
     * @param picture the 16-bit planar picture to check
     * @return {@code true} when its samples need no matrix conversion
     */
    public static boolean isJpegYcc(Yuv16Picture picture) {
        return YccRows.isJpegYcc(picture);
    }

    /**
     * Resolves the components of a frame.
     *
     * @param width the frame width
     * @param height the frame height
     * @param precision the sample precision in bits
     * @param options the settings
     * @return the layout
     * @throws IllegalArgumentException if a sampling factor does not divide the largest one, or the point
     * transform is not below the precision
     */
    static FrameLayout layout(int width, int height, int precision, JpegEncoderOptions options) {
        var lossless = options.lossless();
        if (lossless) {
            if (precision < 2 || precision > 16) {
                throw new IllegalArgumentException("a lossless JPEG file has 2 to 16 bits per sample, not "
                        + precision);
            }
            if (options.pointTransform() >= precision) {
                throw new IllegalArgumentException("point transform " + options.pointTransform()
                        + " must be below the precision " + precision);
            }
        }
        var space = options.colorSpace();
        var format = options.chromaFormat();
        var lumaH = 1 << format.shiftX();
        var lumaV = 1 << format.shiftY();
        int[] ids;
        int[] h;
        int[] v;
        int[] slots;
        switch (space) {
            case YCBCR -> {
                if (format.hasChroma()) {
                    ids = new int[]{1, 2, 3};
                    h = new int[]{lumaH, 1, 1};
                    v = new int[]{lumaV, 1, 1};
                    slots = new int[]{0, 1, 1};
                } else {
                    ids = new int[]{1};
                    h = new int[]{1};
                    v = new int[]{1};
                    slots = new int[]{0};
                }
            }
            case RGB -> {
                ids = new int[]{'R', 'G', 'B'};
                h = new int[]{1, 1, 1};
                v = new int[]{1, 1, 1};
                slots = new int[]{0, 0, 0};
            }
            case CMYK -> {
                ids = new int[]{'C', 'M', 'Y', 'K'};
                h = new int[]{1, 1, 1, 1};
                v = new int[]{1, 1, 1, 1};
                slots = new int[]{0, 0, 0, 0};
            }
            case YCCK -> {
                ids = new int[]{1, 2, 3, 4};
                h = new int[]{lumaH, 1, 1, lumaH};
                v = new int[]{lumaV, 1, 1, lumaV};
                slots = new int[]{0, 1, 1, 0};
            }
            default -> throw new IllegalStateException();
        }
        var count = ids.length;
        var factors = options.samplingFactors();
        if (!factors.isEmpty()) {
            for (var c = 0; c < count; c++) {
                h[c] = c < factors.size() ? factors.get(c).horizontal() : 1;
                v[c] = c < factors.size() ? factors.get(c).vertical() : 1;
            }
        }
        var tableSlots = slots.clone();
        var quantSlots = slots.clone();
        var chosen = options.quantizationSlots();
        if (!chosen.isEmpty()) {
            for (var c = 0; c < count; c++) {
                quantSlots[c] = chosen.get(Math.min(c, chosen.size() - 1));
            }
        }
        var maxH = Arrays.stream(h).max().orElse(1);
        var maxV = Arrays.stream(v).max().orElse(1);
        for (var c = 0; c < count; c++) {
            if (maxH % h[c] != 0 || maxV % v[c] != 0) {
                throw new IllegalArgumentException("sampling factors " + h[c] + "x" + v[c] + " of component " + c
                        + " do not divide the largest factors " + maxH + "x" + maxV);
            }
        }
        return new FrameLayout(width, height, precision, lossless, space, ids, h, v, quantSlots, tableSlots);
    }

    /**
     * Resolves the scans of a frame: the options' script or the default one of the process.
     *
     * @param layout the frame
     * @param options the settings
     * @return the scans
     * @throws IllegalArgumentException if a bit position or point transform exceeds the precision's limit, or
     * an interleaved scan has more than 10 data units per MCU
     */
    static List<CoefficientWriter.Scan> scans(FrameLayout layout, JpegEncoderOptions options) {
        var scans = new ArrayList<CoefficientWriter.Scan>();
        var all = new int[layout.count];
        for (var c = 0; c < all.length; c++) {
            all[c] = c;
        }
        if (!options.scanScript().isEmpty()) {
            for (var scan : options.scanScript()) {
                var components = scan.components().stream().mapToInt(Integer::intValue).toArray();
                scans.add(new CoefficientWriter.Scan(components, scan.spectralStart(), scan.spectralEnd(),
                        scan.successiveHigh(), scan.successiveLow()));
            }
        } else if (options.lossless()) {
            scans.add(new CoefficientWriter.Scan(all, options.predictor(), 0, 0, options.pointTransform()));
        } else if (options.progressive()) {
            scans.addAll(CoefficientWriter.progressiveScript(layout.count,
                    layout.count == 3 && layout.colorSpace == ColorSpace.YCBCR));
        } else {
            scans.add(new CoefficientWriter.Scan(all, 0, 63, 0, 0));
        }
        var maxBit = layout.precision == 12 ? 13 : 10;
        for (var scan : scans) {
            if (options.lossless() ? scan.al() >= layout.precision
                    : options.progressive() && (scan.ah() > maxBit || scan.al() > maxBit)) {
                throw new IllegalArgumentException("scan bit position " + Math.max(scan.ah(), scan.al())
                        + " exceeds the limit of " + layout.precision + "-bit samples");
            }
            if (scan.components().length > 1 && layout.unitsPerMcu(scan.components()) > MAX_UNITS_IN_MCU) {
                throw new IllegalArgumentException("an interleaved scan of these sampling factors has "
                        + layout.unitsPerMcu(scan.components()) + " data units per MCU, more than "
                        + MAX_UNITS_IN_MCU);
            }
        }
        return scans;
    }

    /**
     * Returns the scaled quantization table of each slot the components use, as
     * {@code jpeg_add_quant_table} scales it.
     *
     * @param layout the frame
     * @param options the settings
     * @return the tables, natural order, {@code null} for unused slots
     */
    static int[][] quantTables(FrameLayout layout, JpegEncoderOptions options) {
        var tables = new int[4][];
        var custom = options.quantizationTables();
        for (var c = 0; c < layout.count; c++) {
            var slot = layout.quantSlots[c];
            if (tables[slot] != null) {
                continue;
            }
            int[] base;
            if (slot < custom.size()) {
                base = custom.get(slot).toArray();
            } else {
                base = slot == 0 ? JpegTables.STD_LUMINANCE_QUANT : JpegTables.STD_CHROMINANCE_QUANT;
            }
            var quality = slot == 0 ? options.quality() : options.chromaQuality().orElse(options.quality());
            tables[slot] = scale(base, quality, options.baselineQuantization());
        }
        return tables;
    }

    /**
     * Scales a base table by a quality.
     *
     * @param base the base table, natural order
     * @param quality the quality, from 0 to 100
     * @param baseline whether the values are limited to 255
     * @return the table
     */
    static int[] scale(int[] base, int quality, boolean baseline) {
        var q = Math.clamp(quality, 1, 100);
        var factor = q < 50 ? 5000 / q : 200 - q * 2;
        var table = new int[64];
        for (var i = 0; i < 64; i++) {
            var value = ((long) base[i] * factor + 50) / 100;
            value = Math.clamp(value, 1, 32767);
            if (baseline) {
                value = Math.min(value, 255);
            }
            table[i] = (int) value;
        }
        return table;
    }

    /**
     * Returns whether a frame is the baseline YCbCr or gray frame of {@link JpegWriter}.
     *
     * @param layout the frame
     * @param options the settings
     * @param scans the resolved scan script
     * @param quantTables the quantization tables, or {@code null} for a lossless frame
     * @return {@code true} when {@link JpegWriter} writes it
     */
    private static boolean isBaseline(FrameLayout layout, JpegEncoderOptions options, List<CoefficientWriter.Scan> scans,
                                      int[][] quantTables) {
        if (layout.lossless || options.progressive() || options.arithmetic() || layout.precision != 8
                || layout.colorSpace != ColorSpace.YCBCR || scans.size() != 1 || scans.getFirst().components().length
                != layout.count) {
            return false;
        }
        if (layout.count == 1) {
            if (layout.h[0] != 1 || layout.v[0] != 1 || layout.quantSlots[0] != 0) {
                return false;
            }
        } else {
            var standard = (layout.h[0] == 1 && layout.v[0] == 1) || (layout.h[0] == 2 && layout.v[0] <= 2);
            if (!standard || layout.h[1] != 1 || layout.v[1] != 1 || layout.h[2] != 1 || layout.v[2] != 1
                    || layout.quantSlots[0] != 0 || layout.quantSlots[1] != 1 || layout.quantSlots[2] != 1) {
                return false;
            }
        }
        for (var table : quantTables) {
            if (table == null) {
                continue;
            }
            for (var value : table) {
                if (value > 255) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Returns the file header of a frame: JFIF for YCbCr and gray, Adobe for the other color spaces and for a
     * lossless 9-bit YCbCr frame of three components sampled 1x1, which readers take for reversibly
     * transformed RGB when it carries a JFIF header.
     *
     * @param layout the frame
     * @param density the JFIF density
     * @return the header
     */
    static CoefficientWriter.Header header(FrameLayout layout, PixelDensity density) {
        var jfif = jfifDensity(density);
        if (isReversibleRgbShape(layout)) {
            return new CoefficientWriter.Header(false, 1, 1, 0, 1, 1, true, 1);
        }
        return switch (layout.colorSpace) {
            case YCBCR -> new CoefficientWriter.Header(true, 1, 1, jfif[0], jfif[1], jfif[2], false, 0);
            case RGB, CMYK -> new CoefficientWriter.Header(false, 1, 1, 0, 1, 1, true, 0);
            case YCCK -> new CoefficientWriter.Header(false, 1, 1, 0, 1, 1, true, 2);
        };
    }

    /**
     * Returns whether a frame has the shape of the lossless RGB files of a reversible color transform: a
     * lossless YCbCr frame of 9-bit precision with three components, all sampled 1x1.
     *
     * @param layout the frame
     * @return {@code true} for such a frame
     */
    private static boolean isReversibleRgbShape(FrameLayout layout) {
        if (!layout.lossless || layout.precision != 9 || layout.colorSpace != ColorSpace.YCBCR || layout.count != 3) {
            return false;
        }
        for (var c = 0; c < layout.count; c++) {
            if (layout.h[c] != 1 || layout.v[c] != 1) {
                return false;
            }
        }
        return true;
    }

    /**
     * Returns the payload of a JFIF 1.01 {@code APP0} segment without a thumbnail.
     *
     * @param density the pixel density of the JFIF segment
     * @return the payload
     */
    private static byte[] jfifPayload(PixelDensity density) {
        var jfif = jfifDensity(density);
        var x = jfif[1];
        var y = jfif[2];
        return new byte[]{'J', 'F', 'I', 'F', 0, 1, 1, (byte) jfif[0], (byte) (x >> 8), (byte) x,
                (byte) (y >> 8), (byte) y, 0, 0};
    }

    /**
     * Returns the JFIF units and densities of a pixel density, as {@link JpegEncoderOptions#density()}
     * describes them.
     *
     * @param density the pixel density to write
     * @return the units byte (0 for an aspect ratio, 1 for inches, 2 for centimeters), then the horizontal and
     * vertical densities, from 1 to 65535
     * @throws IllegalArgumentException if the header cannot hold the density
     */
    public static int[] jfifDensity(PixelDensity density) {
        var unit = switch (density.unit()) {
            case ASPECT_RATIO -> 0;
            case DOTS_PER_INCH -> 1;
            case DOTS_PER_CENTIMETER, DOTS_PER_METER -> 2;
        };
        var terms = unit == 0 ? DensityTerms.ratio(density, 0xFFFF)
                : DensityTerms.rounded(density, unit == 1 ? PixelDensity.Unit.DOTS_PER_INCH
                : PixelDensity.Unit.DOTS_PER_CENTIMETER, 0xFFFF);
        return new int[]{unit, (int) terms[0], (int) terms[1]};
    }

    /**
     * Returns the planes of a planar picture that is already in the frame's color space and subsampling, so
     * that they are coded without resampling.
     *
     * @param picture the picture to code
     * @param layout the frame
     * @return the planes, or {@code null} when the picture needs conversion
     */
    private static ComponentBands.Planes planes(Picture picture, FrameLayout layout) {
        if (layout.colorSpace != ColorSpace.YCBCR || layout.count != 3) {
            return null;
        }
        ChromaFormat format;
        if (picture instanceof YuvPicture yuv && YccRows.isJpegYcc(yuv)) {
            format = yuv.chromaFormat();
        } else if (picture instanceof Yuv16Picture yuv && YccRows.isJpegYcc(yuv)) {
            format = yuv.chromaFormat();
        } else {
            return null;
        }
        if (!format.hasChroma() || layout.h[0] != 1 << format.shiftX() || layout.v[0] != 1 << format.shiftY()
                || layout.h[1] != 1 || layout.v[1] != 1 || layout.h[2] != 1 || layout.v[2] != 1) {
            return null;
        }
        return switch (picture) {
            case YuvPicture yuv -> new ComponentBands.Planes(null, new byte[][]{yuv.y(), yuv.u(), yuv.v()},
                    new int[]{yuv.yStride(), yuv.uvStride(), yuv.uvStride()}, SampleRows.rescaleTable(8,
                    layout.precision), 0xFF);
            case Yuv16Picture yuv -> new ComponentBands.Planes(new short[][]{yuv.y(), yuv.u(), yuv.v()}, null,
                    new int[]{yuv.yStride(), yuv.uvStride(), yuv.uvStride()}, SampleRows.rescaleTable(yuv.bitDepth(),
                    layout.precision), (1 << yuv.bitDepth()) - 1);
            default -> null;
        };
    }
}
