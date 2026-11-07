package com.github.auties00.smalt.image.webp.internal.alpha;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.webp.internal.lossless.Vp8lDecoder;
import com.github.auties00.smalt.internal.limits.LimitChecks;

/**
 * Decodes the payload of a WebP {@code ALPH} chunk to an 8-bit alpha plane.
 *
 * <p>The payload starts with one header byte: bits 0-1 hold the compression method (0 raw,
 * 1 lossless), bits 2-3 the filter method, bits 4-5 the pre-processing hint (0 none, 1 quantized
 * levels) and bits 6-7 are reserved and must be zero. Raw data is {@code width * height} filtered
 * bytes; lossless data is a headerless VP8L stream whose green channel holds the filtered
 * samples.
 *
 * @implNote This implementation follows libwebp's {@code src/dec/alpha_dec.c} (BSD-3). The
 * quantized-levels hint only enables libwebp's optional alpha dithering, which its decoders leave
 * off by default; this implementation never dithers, so its output matches {@code dwebp}.
 */
public final class AlphaDecoder {
    /**
     * The raw compression method.
     */
    public static final int METHOD_RAW = 0;

    /**
     * The lossless (VP8L) compression method.
     */
    public static final int METHOD_LOSSLESS = 1;

    /**
     * Prevents instantiation.
     */
    private AlphaDecoder() {
        throw new AssertionError();
    }

    /**
     * Decodes an {@code ALPH} payload.
     *
     * @param data   the array holding the payload
     * @param offset the index of the header byte
     * @param length the payload size in bytes
     * @param width  the image width
     * @param height the image height
     * @param limits the resource bounds
     * @return the alpha plane of {@code width * height} bytes
     * @throws MediaException.Malformed     if the header is invalid or the data is corrupt or
     *                                      truncated
     * @throws MediaException.LimitExceeded if the plane exceeds {@code limits}
     */
    public static byte[] decode(byte[] data, int offset, int length, int width, int height, MediaLimits limits) {
        return decode(data, offset, length, width, height, limits, height);
    }

    /**
     * Decodes the top rows of an {@code ALPH} payload, as {@link #decode(byte[], int, int, int, int, MediaLimits)}
     * decodes all of them; the coded data below the rows is not decoded.
     *
     * @param data   the array holding the payload
     * @param offset the index of the header byte
     * @param length the payload size in bytes
     * @param width  the image width
     * @param height the image height
     * @param limits the resource bounds
     * @param rows   the number of rows to decode, from 1 to {@code height}
     * @return the alpha plane of {@code width * rows} bytes
     * @throws MediaException.Malformed     if the header is invalid or the data is corrupt or
     *                                      truncated
     * @throws MediaException.LimitExceeded if the plane exceeds {@code limits}
     */
    public static byte[] decode(byte[] data, int offset, int length, int width, int height, MediaLimits limits,
                                int rows) {
        if (length <= 1) {
            throw new MediaException.Malformed("ALPH chunk of " + length + " bytes has no data");
        }
        var header = data[offset] & 0xFF;
        var method = header & 3;
        var filter = (header >> 2) & 3;
        var preProcessing = (header >> 4) & 3;
        var reserved = (header >> 6) & 3;
        if (method > METHOD_LOSSLESS || preProcessing > 1 || reserved != 0) {
            throw new MediaException.Malformed("invalid ALPH header 0x" + Integer.toHexString(header));
        }
        var size = (long) width * height;
        var shown = Math.min(rows, height);
        LimitChecks.checkAllocation(limits, (long) width * shown);
        var plane = new byte[width * shown];
        if (method == METHOD_RAW) {
            if (length - 1 < size) {
                throw new MediaException.Malformed("raw ALPH data of " + (length - 1) + " bytes is shorter than " + size);
            }
            var src = offset + 1;
            for (var y = 0; y < shown; y++) {
                var line = y * width;
                AlphaFilters.unfilter(filter, y == 0 ? null : plane, line - width, data, src + line, plane, line, width);
            }
        } else {
            Vp8lDecoder.decodeAlpha(data, offset + 1, length - 1, width, height, filter, plane, limits, shown);
        }
        return plane;
    }
}
