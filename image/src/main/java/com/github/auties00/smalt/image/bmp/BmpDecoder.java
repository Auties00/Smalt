package com.github.auties00.smalt.image.bmp;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.DecodedImage;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.ImageFormat;
import com.github.auties00.smalt.image.ImageInfo;
import com.github.auties00.smalt.image.Orientation;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.bmp.internal.BmpHeader;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.internal.io.SourceBytes;
import com.github.auties00.smalt.internal.limits.Guards;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.io.MediaSource;

import java.util.Objects;
import java.util.Optional;

/**
 * Decodes Windows and OS/2 bitmaps (BMP) to packed RGBA8.
 *
 * <p>Supported: the OS/2 1.x core header, OS/2 2.x headers, and the {@code BITMAPINFOHEADER} family
 * up to V5; bottom-up and top-down (negative height) row order; 1-, 2-, 4- and 8-bit palettes;
 * 16-bit pixels (5-5-5 by default, any contiguous masks with {@code BI_BITFIELDS}); 24-bit pixels;
 * 32-bit pixels ({@code BI_RGB} or masks, including {@code BI_ALPHABITFIELDS}); {@code BI_RLE8} and
 * {@code BI_RLE4} with end-of-line, end-of-bitmap, delta and absolute escapes; and an ICC profile
 * embedded by a V5 header. Unsupported (raising {@link MediaException.Unsupported} naming the
 * feature): embedded JPEG or PNG data, CMYK, OS/2 Huffman and RLE24 compression, and 64-bit
 * pixels.
 *
 * <p>Channel values are scaled to 8 bits: a mask of {@code n <= 8} bits maps value {@code v} to
 * {@code round(v * 255 / (2^n - 1))}, a wider mask keeps its top 8 bits. The alpha
 * channel of 32-bit {@code BI_RGB} pixels, and any alpha mask, is used only when some pixel has a
 * non-zero alpha; when all are zero the image is opaque (many writers leave the byte zero). A
 * palette's fourth byte is ignored. An index past the palette paints opaque black.
 *
 * <p>Damaged files decode as far as they can: the file size and image size fields are ignored; a
 * zero pixel data offset means the data follows the color table; a color table overlapping the
 * pixel data is shortened; the last row may lack its padding; pixel data that ends early leaves the missing
 * pixels transparent; run-length pixels that are skipped (by a delta, an early end of line or an
 * early end of bitmap) are transparent, and runs past the right edge are clipped. A file whose
 * pixel data is entirely missing raises {@link MediaException.Malformed}.
 *
 * <p>This class is thread-safe.
 */
public final class BmpDecoder {
    /**
     * The largest embedded ICC profile read, in bytes.
     */
    private static final int MAX_ICC_PROFILE = 32 << 20;

    /**
     * Prevents instantiation.
     */
    private BmpDecoder() {
        throw new AssertionError();
    }

    /**
     * Reads the headers of a BMP file without decoding pixels.
     *
     * <p>{@link ImageInfo#hasAlpha()} is {@code true} for 32-bit {@code BI_RGB} pixels, an active
     * alpha mask, and run-length encoding: the pixels may carry transparency, which only decoding
     * confirms. The size is reported without checking it against any limit.
     *
     * @param source the input, read from offset zero
     * @return the image summary
     * @throws MediaException.Malformed if the headers are truncated or invalid
     * @throws MediaException.Unsupported if the file uses a feature listed on the class as
     * unsupported
     * @throws MediaException.Io if reading the source fails
     */
    public static ImageInfo probe(MediaSource source) {
        Objects.requireNonNull(source, "source");
        return Guards.malformed("BMP file", () -> {
            var header = BmpHeader.read(source);
            return new ImageInfo(ImageFormat.BMP, header.width(), header.height(), header.declaresAlpha(), false, 1,
                    Orientation.NORMAL);
        });
    }

    /**
     * Decodes a BMP image at full size under the {@link ImageDecoderOptions#defaults()}.
     *
     * @param source the input, read from offset zero
     * @return the decoded image
     * @throws MediaException.Malformed if the headers are invalid or the pixel data is missing
     * @throws MediaException.Unsupported if the file uses a feature listed on the class as
     * unsupported
     * @throws MediaException.LimitExceeded if the image exceeds the default limits or decoding runs
     * out of memory
     * @throws MediaException.Io if reading the source fails
     * @see #decode(MediaSource, ImageDecoderOptions)
     */
    public static DecodedImage decode(MediaSource source) {
        return decode(source, ImageDecoderOptions.defaults());
    }

    /**
     * Decodes a BMP file.
     *
     * <p>The picture's {@link RgbaPicture#hasAlpha()} is {@code true} when an alpha channel is in
     * use (some alpha non-zero), when run-length decoding left pixels unset, or when the pixel data
     * ended early. The reduced-size hint of {@code options} is ignored.
     *
     * @param source the input, read from offset zero
     * @param options the limits and the sRGB conversion; the size hint is ignored
     * @return the decoded image
     * @throws MediaException.Malformed if the headers are invalid or the pixel data is missing
     * @throws MediaException.Unsupported if the file uses a feature listed on the class as
     * unsupported
     * @throws MediaException.LimitExceeded if the image exceeds the limits of {@code options} or
     * decoding runs out of memory
     * @throws MediaException.Io if reading the source fails
     */
    public static DecodedImage decode(MediaSource source, ImageDecoderOptions options) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(options, "options");
        return Guards.malformed("BMP file", () -> {
            var limits = options.limits();
            var header = BmpHeader.read(source);
            var width = header.width();
            var height = header.height();
            LimitChecks.checkPicture(limits, width, height);
            LimitChecks.checkAllocation(limits, (long) width * height * 4);
            var pixels = new byte[width * height * 4];
            var alpha = switch (header.compression()) {
                case BmpHeader.RLE8, BmpHeader.RLE4 -> decodeRunLength(source, header, pixels, limits);
                default -> decodeRows(source, header, pixels, limits);
            };
            var picture = RgbaPicture.of(width, height, width * 4, pixels, alpha);
            var image = new DecodedImage(picture, ImageFormat.BMP, Orientation.NORMAL, width, height,
                    Optional.ofNullable(readProfile(source, header, limits)), Optional.empty());
            return options.convertToSrgb() ? ImageOps.toSrgb(image) : image;
        });
    }

    /**
     * Decodes uncompressed or bit-field pixels, row by row.
     *
     * @param source the input
     * @param header the headers
     * @param pixels the destination RGBA canvas, zero-filled
     * @param limits the limits checked before allocating the row buffer
     * @return whether the decoded picture carries transparency
     * @throws MediaException.Malformed if no pixel data is present
     */
    private static boolean decodeRows(MediaSource source, BmpHeader header, byte[] pixels, MediaLimits limits) {
        var width = header.width();
        var height = header.height();
        var stride = header.rowStride();
        var rowBytes = ((long) width * header.bitCount() + 7) / 8;
        LimitChecks.checkAllocation(limits, rowBytes);
        var row = new byte[(int) rowBytes];
        var channels = BmpChannels.of(header);
        var decodedPixels = 0L;
        var nonZeroAlpha = 0;
        for (var stored = 0; stored < height; stored++) {
            var read = SourceBytes.readAvailable(source, header.dataOffset() + stored * stride, row, 0, row.length);
            if (read <= 0) {
                if (stored == 0) {
                    throw new MediaException.Malformed("BMP pixel data is missing");
                }
                break;
            }
            var count = (int) Math.min(width, (long) read * 8 / header.bitCount());
            var y = header.topDown() ? stored : height - 1 - stored;
            nonZeroAlpha |= channels.convert(row, count, pixels, y * width * 4);
            decodedPixels += count;
            if (count < width) {
                break;
            }
        }
        var complete = decodedPixels == (long) width * height;
        if (header.alphaMaskActive() && nonZeroAlpha == 0) {
            makeOpaque(pixels, header, decodedPixels);
            return !complete;
        }
        return header.alphaMaskActive() || !complete;
    }

    /**
     * Sets alpha 255 on every decoded pixel, used when the alpha channel is zero everywhere.
     *
     * @param pixels the canvas
     * @param header the headers
     * @param decoded the number of pixels decoded, in stored order
     */
    private static void makeOpaque(byte[] pixels, BmpHeader header, long decoded) {
        var width = header.width();
        var height = header.height();
        for (var index = 0L; index < decoded; index++) {
            var stored = (int) (index / width);
            var y = header.topDown() ? stored : height - 1 - stored;
            pixels[(int) (((long) y * width + index % width) * 4 + 3)] = (byte) 0xFF;
        }
    }

    /**
     * Decodes {@code BI_RLE8} or {@code BI_RLE4} pixels.
     *
     * @param source the input
     * @param header the headers
     * @param pixels the destination RGBA canvas, zero-filled
     * @param limits the limits checked before buffering the compressed data
     * @return whether the decoded picture carries transparency (some pixel was not set)
     * @throws MediaException.Malformed if no pixel data is present
     */
    private static boolean decodeRunLength(MediaSource source, BmpHeader header, byte[] pixels, MediaLimits limits) {
        var size = source.size();
        var available = size < 0 ? limits.maxBytes() : size - header.dataOffset();
        if (available <= 0) {
            throw new MediaException.Malformed("BMP pixel data is missing");
        }
        // one two-byte code per pixel plus an end of line per row is the longest useful encoding
        var useful = (long) header.width() * header.height() * 2 + (long) header.height() * 2 + 4096;
        var bound = Math.min(Math.min(available, useful), Integer.MAX_VALUE - 16);
        LimitChecks.checkAllocation(limits, bound);
        var data = new byte[(int) bound];
        var length = SourceBytes.readAvailable(source, header.dataOffset(), data, 0, data.length);
        if (length <= 0) {
            throw new MediaException.Malformed("BMP pixel data is missing");
        }
        var written = BmpRunLength.decode(data, length, header, pixels);
        return written < (long) header.width() * header.height();
    }

    /**
     * Reads the ICC profile embedded by a V5 header.
     *
     * @param source the input
     * @param header the headers
     * @param limits the limits bounding the profile size
     * @return the profile, or {@code null} when absent, out of bounds or too large
     */
    private static byte[] readProfile(MediaSource source, BmpHeader header, MediaLimits limits) {
        if (header.iccOffset() < 0 || header.iccSize() <= 0 || header.iccSize() > Math.min(MAX_ICC_PROFILE, limits.maxBytes())) {
            return null;
        }
        var profile = new byte[(int) header.iccSize()];
        return SourceBytes.readAvailable(source, header.iccOffset(), profile, 0, profile.length) == profile.length ? profile
                : null;
    }
}
