package com.github.auties00.smalt.image.jpeg.internal;

import com.github.auties00.smalt.image.Orientation;
import com.github.auties00.smalt.image.exif.ExifData;
import com.github.auties00.smalt.image.exif.ExifValue;

/**
 * Transforms the uncompressed thumbnail of an EXIF block (TIFF strips described by IFD1) the way a
 * lossless JPEG transform moves the main image: rotates or flips its pixels exactly, and converts
 * it to grayscale.
 *
 * <p>Thumbnails of 8-bit or 16-bit samples, chunky or planar, in grayscale, RGB, or YCbCr without
 * subsampling, are transformed; any other layout is reported as not transformable. A grayscale
 * conversion keeps the JFIF luma of RGB samples, and the luma of YCbCr samples stretched from its
 * {@code ReferenceBlackWhite} range to the full sample range of a {@code BlackIsZero} thumbnail.
 */
final class UncompressedThumbnail {
    /**
     * The tag of {@code ImageWidth}.
     */
    private static final int IMAGE_WIDTH = 0x0100;

    /**
     * The tag of {@code ImageLength}.
     */
    private static final int IMAGE_LENGTH = 0x0101;

    /**
     * The tag of {@code BitsPerSample}.
     */
    private static final int BITS_PER_SAMPLE = 0x0102;

    /**
     * The tag of {@code PhotometricInterpretation}.
     */
    private static final int PHOTOMETRIC = 0x0106;

    /**
     * The tag of {@code SamplesPerPixel}.
     */
    private static final int SAMPLES_PER_PIXEL = 0x0115;

    /**
     * The tag of {@code PlanarConfiguration}.
     */
    private static final int PLANAR_CONFIGURATION = 0x011C;

    /**
     * The tags a grayscale thumbnail drops: {@code ExtraSamples} and {@code SampleFormat}, which
     * count the samples of the old layout, and {@code YCbCrCoefficients},
     * {@code YCbCrSubSampling}, {@code YCbCrPositioning} and {@code ReferenceBlackWhite}.
     */
    private static final int[] COLOR_TAGS = {0x0152, 0x0153, 0x0211, 0x0212, 0x0213, 0x0214};

    /**
     * The tag of {@code ReferenceBlackWhite}.
     */
    private static final int REFERENCE_BLACK_WHITE = 0x0214;

    /**
     * The tag of {@code YCbCrSubSampling}.
     */
    private static final int YCBCR_SUBSAMPLING = 0x0212;

    /**
     * The largest number of samples a transformed thumbnail may hold.
     */
    private static final long MAX_SAMPLES = 1L << 28;

    /**
     * Prevents instantiation.
     */
    private UncompressedThumbnail() {
        throw new AssertionError();
    }

    /**
     * Transforms the uncompressed thumbnail of an EXIF block.
     *
     * @param exif the block's directories, with an uncompressed thumbnail
     *             ({@link ExifData#hasUncompressedThumbnail()})
     * @param transform the geometric transform, named by the orientation it corrects
     * @param grayscale whether to keep only the luma of the thumbnail
     * @param little whether the block stores 16-bit samples little-endian
     * @return the directories with the transformed thumbnail, or {@code null} when the thumbnail's
     * layout is not one this class transforms or its samples are cut short
     */
    static ExifData transform(ExifData exif, Orientation transform, boolean grayscale, boolean little) {
        var ifd = exif.thumbnailIfd();
        var samples = exif.thumbnail().orElse(null);
        var width = first(ifd.get(IMAGE_WIDTH), -1);
        var height = first(ifd.get(IMAGE_LENGTH), -1);
        var channels = (int) first(ifd.get(SAMPLES_PER_PIXEL), 1);
        var photometric = (int) first(ifd.get(PHOTOMETRIC), -1);
        var planar = first(ifd.get(PLANAR_CONFIGURATION), 1) == 2;
        if (samples == null || width <= 0 || height <= 0 || width > MAX_SAMPLES || height > MAX_SAMPLES || channels < 1
                || channels > 16) {
            return null;
        }
        var bits = bitsPerSample(ifd.get(BITS_PER_SAMPLE), channels);
        if (bits != 8 && bits != 16) {
            return null;
        }
        switch (photometric) {
            case 0, 1 -> {
            }
            case 2 -> {
                if (channels < 3) {
                    return null;
                }
            }
            case 6 -> {
                if (channels < 3 || !(ifd.get(YCBCR_SUBSAMPLING) instanceof ExifValue.Integers subsampling)
                        || subsampling.count() != 2 || subsampling.get(0) != 1 || subsampling.get(1) != 1) {
                    return null;
                }
            }
            default -> {
                return null;
            }
        }
        var bytes = bits / 8;
        var pixels = width * height;
        if (pixels * channels > MAX_SAMPLES || samples.length < pixels * channels * bytes) {
            return null;
        }
        var outWidth = transform.swapsDimensions() ? height : width;
        var outHeight = transform.swapsDimensions() ? width : height;
        var gray = grayscale && photometric >= 2;
        var outChannels = gray ? 1 : channels;
        var output = new byte[(int) (pixels * outChannels * bytes)];
        var w = (int) width;
        var h = (int) height;
        var ow = (int) outWidth;
        var maximum = (1 << bits) - 1;
        var black = 0.0;
        var white = (double) maximum;
        // YCbCr luma spans the ReferenceBlackWhite range, which a BlackIsZero thumbnail stretches to full range
        if (gray && photometric == 6 && ifd.get(REFERENCE_BLACK_WHITE) instanceof ExifValue.Rationals reference
                && reference.count() >= 2 && reference.doubleValue(1) > reference.doubleValue(0)) {
            black = reference.doubleValue(0);
            white = reference.doubleValue(1);
        }
        var rescale = black != 0 || white != maximum;
        for (var y = 0; y < h; y++) {
            for (var x = 0; x < w; x++) {
                var to = target(transform, x, y, w, h, ow);
                var from = (long) y * w + x;
                if (gray) {
                    int luma;
                    if (photometric == 6) {
                        luma = sample(samples, planar, from, 0, channels, pixels, bytes, little);
                        if (rescale) {
                            luma = (int) Math.max(0, Math.min(maximum, Math.round((luma - black) * maximum / (white - black))));
                        }
                    } else {
                        luma = luma(samples, planar, from, channels, pixels, bytes, little);
                    }
                    put(output, to, luma, bytes, little);
                } else {
                    for (var c = 0; c < channels; c++) {
                        var source = planar ? (c * pixels + from) * bytes : (from * channels + c) * bytes;
                        var destination = planar ? ((long) c * pixels + to) * bytes : ((long) to * channels + c) * bytes;
                        System.arraycopy(samples, (int) source, output, (int) destination, bytes);
                    }
                }
            }
        }
        var result = exif.with(ExifData.Directory.THUMBNAIL, IMAGE_WIDTH, dimension(ifd.get(IMAGE_WIDTH), outWidth))
                .with(ExifData.Directory.THUMBNAIL, IMAGE_LENGTH, dimension(ifd.get(IMAGE_LENGTH), outHeight));
        if (gray) {
            result = result.with(ExifData.Directory.THUMBNAIL, SAMPLES_PER_PIXEL,
                            new ExifValue.Integers(ExifValue.Type.SHORT, new long[]{1}))
                    .with(ExifData.Directory.THUMBNAIL, BITS_PER_SAMPLE,
                            new ExifValue.Integers(ExifValue.Type.SHORT, new long[]{bits}))
                    .with(ExifData.Directory.THUMBNAIL, PHOTOMETRIC,
                            new ExifValue.Integers(ExifValue.Type.SHORT, new long[]{1}))
                    .without(ExifData.Directory.THUMBNAIL, PLANAR_CONFIGURATION);
            for (var tag : COLOR_TAGS) {
                result = result.without(ExifData.Directory.THUMBNAIL, tag);
            }
        }
        return result.withUncompressedThumbnail(output);
    }

    /**
     * Returns where a pixel lands under a transform.
     *
     * @param transform the transform, named by the orientation it corrects
     * @param x the column of the pixel
     * @param y the row of the pixel
     * @param w the width before the transform
     * @param h the height before the transform
     * @param ow the width after the transform
     * @return the index of the pixel after the transform, row by row
     */
    private static int target(Orientation transform, int x, int y, int w, int h, int ow) {
        return switch (transform) {
            case NORMAL -> y * ow + x;
            case FLIP_HORIZONTAL -> y * ow + w - 1 - x;
            case ROTATE_180 -> (h - 1 - y) * ow + w - 1 - x;
            case FLIP_VERTICAL -> (h - 1 - y) * ow + x;
            case TRANSPOSE -> x * ow + y;
            case ROTATE_90 -> x * ow + h - 1 - y;
            case TRANSVERSE -> (w - 1 - x) * ow + h - 1 - y;
            case ROTATE_270 -> (w - 1 - x) * ow + y;
        };
    }

    /**
     * Computes the luma of an RGB pixel with the JFIF weights, rounded.
     *
     * @param samples the thumbnail samples
     * @param planar whether the samples are planar
     * @param pixel the pixel index
     * @param channels the number of samples per pixel
     * @param pixels the number of pixels
     * @param bytes the bytes per sample, 1 or 2
     * @param little whether 16-bit samples are little-endian
     * @return the luma, in the sample range
     */
    private static int luma(byte[] samples, boolean planar, long pixel, int channels, long pixels, int bytes,
                            boolean little) {
        var r = sample(samples, planar, pixel, 0, channels, pixels, bytes, little);
        var g = sample(samples, planar, pixel, 1, channels, pixels, bytes, little);
        var b = sample(samples, planar, pixel, 2, channels, pixels, bytes, little);
        // 19595, 38470 and 7471 are 0.299, 0.587 and 0.114 in 16-bit fixed point; they sum to 65536
        return (int) ((19595L * r + 38470L * g + 7471L * b + 32768) >>> 16);
    }

    /**
     * Reads one sample.
     *
     * @param samples the thumbnail samples
     * @param planar whether the samples are planar
     * @param pixel the pixel index
     * @param channel the channel index
     * @param channels the number of samples per pixel
     * @param pixels the number of pixels
     * @param bytes the bytes per sample, 1 or 2
     * @param little whether 16-bit samples are little-endian
     * @return the sample value
     */
    private static int sample(byte[] samples, boolean planar, long pixel, int channel, int channels, long pixels,
                              int bytes, boolean little) {
        var at = (int) ((planar ? channel * pixels + pixel : pixel * channels + channel) * bytes);
        if (bytes == 1) {
            return samples[at] & 0xFF;
        }
        var first = samples[at] & 0xFF;
        var second = samples[at + 1] & 0xFF;
        return little ? first | second << 8 : first << 8 | second;
    }

    /**
     * Writes one sample of a single-channel output.
     *
     * @param output the output samples
     * @param pixel the pixel index
     * @param value the sample value
     * @param bytes the bytes per sample, 1 or 2
     * @param little whether 16-bit samples are little-endian
     */
    private static void put(byte[] output, int pixel, int value, int bytes, boolean little) {
        if (bytes == 1) {
            output[pixel] = (byte) value;
            return;
        }
        output[2 * pixel] = (byte) (little ? value : value >>> 8);
        output[2 * pixel + 1] = (byte) (little ? value >>> 8 : value);
    }

    /**
     * Returns the common bit depth of the samples.
     *
     * @param value the {@code BitsPerSample} entry, or {@code null} for the TIFF default of 1
     * @param channels the number of samples per pixel
     * @return the depth, or {@code -1} when the channels differ in depth or the entry is not an
     * integer of one or {@code channels} elements
     */
    private static int bitsPerSample(ExifValue value, int channels) {
        if (value == null) {
            return 1;
        }
        if (!(value instanceof ExifValue.Integers integers) || (integers.count() != 1 && integers.count() != channels)) {
            return -1;
        }
        var bits = integers.get(0);
        for (var i = 1; i < integers.count(); i++) {
            if (integers.get(i) != bits) {
                return -1;
            }
        }
        return (int) bits;
    }

    /**
     * Returns a dimension entry, in the type of the entry it replaces when the value fits it.
     *
     * @param old the entry replaced, or {@code null}
     * @param value the dimension
     * @return the entry
     */
    private static ExifValue dimension(ExifValue old, long value) {
        var type = old != null && old.type() == ExifValue.Type.SHORT && value <= 0xFFFF ? ExifValue.Type.SHORT
                : ExifValue.Type.LONG;
        return new ExifValue.Integers(type, new long[]{value});
    }

    /**
     * Returns the first element of an integer entry.
     *
     * @param value the entry, or {@code null}
     * @param fallback the result when the entry is absent, empty or not an integer
     * @return the element
     */
    private static long first(ExifValue value, long fallback) {
        return value instanceof ExifValue.Integers integers && integers.count() > 0 ? integers.get(0) : fallback;
    }
}
