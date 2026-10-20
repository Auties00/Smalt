/* Ports LibTIFF 4.7.1 (tif_predict.c). Copyright Sam Leffler, Silicon Graphics, Inc.; LibTIFF license.
   Modified for Smalt. */
package com.github.auties00.smalt.image.tiff.internal;

import com.github.auties00.smalt.internal.io.ByteArrays;

/**
 * Applies and reverses the TIFF predictors on rows of decompressed samples.
 *
 * <p>The horizontal predictor (2, TIFF 6.0 section 14) stores each sample as its difference from
 * the same sample of the previous pixel, modulo the sample size; it works on 8-, 16-, 32- and
 * 64-bit samples, the wider ones in the file's byte order. The floating-point predictor (3, Adobe
 * Photoshop TIFF Technical Note 3) splits each row of floating-point samples into byte planes,
 * most significant plane first, and stores each byte as its difference from the byte one pixel
 * before in the same plane. Arguments are not checked; values outside the documented ranges give
 * unspecified results.
 */
public final class Predictor {
    /**
     * Prevents instantiation.
     */
    private Predictor() {
        throw new AssertionError();
    }

    /**
     * Reverses the horizontal predictor on rows of samples.
     *
     * @param data the rows, modified in place
     * @param offset the index of the first row
     * @param rowBytes the distance between rows
     * @param rows the number of rows
     * @param stride the number of samples per pixel in the rows
     * @param width the number of pixels per row
     * @param bytesPerSample the sample size: 1, 2, 4 or 8
     * @param little whether multi-byte samples are little-endian
     */
    public static void undoHorizontal(byte[] data, int offset, int rowBytes, int rows, int stride, int width,
                                      int bytesPerSample, boolean little) {
        undoHorizontalSamples(data, offset, rowBytes, rows, stride, width * stride, bytesPerSample, little);
    }

    /**
     * Reverses the horizontal predictor on rows holding a number of samples that need not be a
     * whole number of pixels, such as the rows of data units of subsampled YCbCr: each sample
     * from index {@code stride} on adds the sample {@code stride} positions before it.
     *
     * @param data the rows, modified in place
     * @param offset the index of the first row
     * @param rowBytes the distance between rows
     * @param rows the number of rows
     * @param stride the distance in samples between a sample and the one it is predicted from
     * @param count the number of samples per row
     * @param bytesPerSample the sample size: 1, 2, 4 or 8
     * @param little whether multi-byte samples are little-endian
     */
    public static void undoHorizontalSamples(byte[] data, int offset, int rowBytes, int rows, int stride, int count,
                                             int bytesPerSample, boolean little) {
        for (var row = 0; row < rows; row++) {
            var base = offset + row * rowBytes;
            switch (bytesPerSample) {
                case 1 -> {
                    var end = base + count;
                    for (var i = base + stride; i < end; i++) {
                        data[i] += data[i - stride];
                    }
                }
                case 2 -> {
                    for (var i = stride; i < count; i++) {
                        var at = base + i * 2;
                        put16(data, at, get16(data, at, little) + get16(data, at - stride * 2, little), little);
                    }
                }
                case 4 -> {
                    for (var i = stride; i < count; i++) {
                        var at = base + i * 4;
                        var value = ByteArrays.u32(data, at, little) + ByteArrays.u32(data, at - stride * 4, little);
                        ByteArrays.put32(data, at, value, little);
                    }
                }
                default -> {
                    for (var i = stride; i < count; i++) {
                        var at = base + i * 8;
                        var value = i64(data, at, little) + i64(data, at - stride * 8, little);
                        if (little) {
                            ByteArrays.put64le(data, at, value);
                        } else {
                            ByteArrays.put64be(data, at, value);
                        }
                    }
                }
            }
        }
    }

    /**
     * Applies the horizontal predictor to rows of samples.
     *
     * @param data the rows, modified in place
     * @param offset the index of the first row
     * @param rowBytes the distance between rows
     * @param rows the number of rows
     * @param stride the number of samples per pixel in the rows
     * @param width the number of pixels per row
     * @param bytesPerSample the sample size: 1 or 2
     * @param little whether 16-bit samples are little-endian
     */
    public static void applyHorizontal(byte[] data, int offset, int rowBytes, int rows, int stride, int width,
                                       int bytesPerSample, boolean little) {
        var count = width * stride;
        for (var row = 0; row < rows; row++) {
            var base = offset + row * rowBytes;
            if (bytesPerSample == 1) {
                for (var i = base + count - 1; i >= base + stride; i--) {
                    data[i] -= data[i - stride];
                }
            } else {
                for (var i = count - 1; i >= stride; i--) {
                    var at = base + i * 2;
                    put16(data, at, get16(data, at, little) - get16(data, at - stride * 2, little), little);
                }
            }
        }
    }

    /**
     * Reverses the floating-point predictor on rows of samples, leaving each sample big-endian.
     *
     * <p>Each row holds {@code samples} samples of {@code bytesPerSample} bytes, as byte planes;
     * a byte is predicted from the byte {@code stride} positions before it in the row, so
     * {@code stride} is the number of samples per pixel. Bytes of a row past its samples are left
     * as they are.
     *
     * @param data the rows, modified in place
     * @param offset the index of the first row
     * @param rowBytes the distance between rows, at least {@code samples * bytesPerSample}
     * @param rows the number of rows
     * @param stride the distance in bytes between a byte and the byte it is predicted from
     * @param samples the number of samples per row
     * @param bytesPerSample the sample size: 2, 3, 4 or 8
     * @param scratch a buffer of at least {@code samples * bytesPerSample} bytes
     */
    public static void undoFloatingPoint(byte[] data, int offset, int rowBytes, int rows, int stride, int samples,
                                         int bytesPerSample, byte[] scratch) {
        var count = samples * bytesPerSample;
        for (var row = 0; row < rows; row++) {
            var base = offset + row * rowBytes;
            var end = base + count;
            for (var i = base + stride; i < end; i++) {
                data[i] += data[i - stride];
            }
            System.arraycopy(data, base, scratch, 0, count);
            for (var sample = 0; sample < samples; sample++) {
                var at = base + sample * bytesPerSample;
                for (var plane = 0; plane < bytesPerSample; plane++) {
                    data[at + plane] = scratch[plane * samples + sample];
                }
            }
        }
    }

    /**
     * Reverses the horizontal predictor on rows of pixels whose samples differ in size: each
     * sample adds the same sample of the previous pixel, modulo its own size.
     *
     * @param data the rows, modified in place
     * @param offset the index of the first row
     * @param rowBytes the distance between rows
     * @param rows the number of rows
     * @param width the number of pixels per row
     * @param sampleBytes the size of each sample of a pixel: 1, 2, 4 or 8
     * @param little whether multi-byte samples are little-endian
     */
    public static void undoHorizontalMixed(byte[] data, int offset, int rowBytes, int rows, int width,
                                           int[] sampleBytes, boolean little) {
        var pixelBytes = 0;
        for (var size : sampleBytes) {
            pixelBytes += size;
        }
        for (var row = 0; row < rows; row++) {
            var base = offset + row * rowBytes;
            for (var x = 1; x < width; x++) {
                var at = base + x * pixelBytes;
                for (var size : sampleBytes) {
                    var previous = at - pixelBytes;
                    switch (size) {
                        case 1 -> data[at] += data[previous];
                        case 2 -> put16(data, at, get16(data, at, little) + get16(data, previous, little), little);
                        case 4 -> ByteArrays.put32(data, at,
                                ByteArrays.u32(data, at, little) + ByteArrays.u32(data, previous, little), little);
                        default -> {
                            var value = i64(data, at, little) + i64(data, previous, little);
                            if (little) {
                                ByteArrays.put64le(data, at, value);
                            } else {
                                ByteArrays.put64be(data, at, value);
                            }
                        }
                    }
                    at += size;
                }
            }
        }
    }

    /**
     * {@return a 16-bit sample in either byte order}
     *
     * @param data the bytes
     * @param at the index of the sample
     * @param little whether the sample is little-endian
     */
    private static int get16(byte[] data, int at, boolean little) {
        // byte loads keep the loop-carried dependency of the 16-bit predictor loops faster than
        // the 16-bit array views of ByteArrays
        return little ? (data[at] & 0xFF) | (data[at + 1] & 0xFF) << 8 : (data[at] & 0xFF) << 8 | (data[at + 1] & 0xFF);
    }

    /**
     * Writes the low 16 bits of a sample in either byte order.
     *
     * @param data the bytes
     * @param at the index of the sample
     * @param value the sample; bits above 16 are ignored
     * @param little whether the sample is little-endian
     */
    private static void put16(byte[] data, int at, int value, boolean little) {
        data[at + (little ? 0 : 1)] = (byte) value;
        data[at + (little ? 1 : 0)] = (byte) (value >>> 8);
    }

    /**
     * {@return a 64-bit sample in either byte order}
     *
     * @param data the bytes
     * @param at the index of the sample
     * @param little whether the sample is little-endian
     */
    private static long i64(byte[] data, int at, boolean little) {
        return little ? ByteArrays.i64le(data, at) : ByteArrays.i64be(data, at);
    }
}
