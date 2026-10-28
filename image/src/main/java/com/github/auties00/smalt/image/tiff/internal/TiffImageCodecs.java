package com.github.auties00.smalt.image.tiff.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.image.jp2.Jp2Channel;
import com.github.auties00.smalt.image.jp2.Jp2Decoder;
import com.github.auties00.smalt.image.jp2.Jp2DecoderOptions;
import com.github.auties00.smalt.image.jxl.JxlDecoder;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.image.webp.WebpDecoder;
import com.github.auties00.smalt.image.webp.internal.color.YuvConverter;
import com.github.auties00.smalt.io.MediaSource;

/**
 * Decodes the TIFF strips and tiles that hold a complete image of another format (WebP, JPEG XL
 * and JPEG 2000) into the stored sample layout, so that the predictor and the sample conversion
 * then treat them as any other decompressed strip or tile.
 *
 * <p>Each method fills the first {@code rows} rows of {@code chunk}, {@code rowBytes} bytes each,
 * with {@code columns} pixels of interleaved samples of {@code bits} bits, multi-byte samples in
 * the file's byte order and other sizes packed from the most significant bit. The caller zero-fills
 * the chunk first: when the coded image is smaller than the strip or tile, the samples it does not
 * cover stay zero, and samples beyond the strip or tile are dropped. A coded image with more pixels
 * than a full strip or tile exceeds the limits before it is decoded.
 */
final class TiffImageCodecs {
    /**
     * The decoder settings.
     */
    private final ImageDecoderOptions imageOptions;

    /**
     * The JPEG 2000 decoder settings.
     */
    private final Jp2DecoderOptions jp2Options;

    /**
     * Constructs the codecs of one page.
     *
     * @param limits the resource bounds of the page
     * @param chunkWidth the width of a full strip or tile
     * @param chunkHeight the height of a full strip or tile
     */
    TiffImageCodecs(MediaLimits limits, int chunkWidth, int chunkHeight) {
        var area = Math.min(limits.maxPixels(), (long) chunkWidth * chunkHeight);
        var bounded = limits.toBuilder().maxPixels(area).build();
        this.imageOptions = ImageDecoderOptions.builder().limits(bounded).build();
        this.jp2Options = Jp2DecoderOptions.builder().limits(bounded).build();
    }

    /**
     * Decodes a WebP strip or tile into 8-bit RGB or RGBA samples.
     *
     * <p>Lossy images are converted to RGB with the fancy chroma upsampling of the WebP decoder's
     * own RGBA output; an image without alpha gives a fourth sample of 255.
     *
     * @param data the WebP file
     * @param length the number of bytes
     * @param chunk the zero-filled destination
     * @param rowBytes the size of one destination row
     * @param columns the pixels per destination row
     * @param rows the number of destination rows
     * @param samples the samples per pixel, 3 or 4; a fourth sample receives the WebP alpha
     * @throws MediaException.Malformed if the data is not a valid WebP image
     * @throws MediaException.LimitExceeded if the image has more pixels than a strip or tile
     */
    void webp(byte[] data, int length, byte[] chunk, int rowBytes, int columns, int rows, int samples) {
        var decoded = WebpDecoder.decode(MediaSource.of(data, 0, length), imageOptions).picture();
        byte[] pixels;
        int stride;
        if (decoded instanceof YuvPicture yuv) {
            stride = yuv.width() * 4;
            pixels = new byte[stride * yuv.height()];
            YuvConverter.toRgba(yuv, pixels, 0, stride);
        } else {
            var rgba = ImageOps.toRgba(decoded);
            pixels = rgba.pixels();
            stride = rgba.stride();
        }
        var copyRows = Math.min(rows, decoded.height());
        var copyColumns = Math.min(columns, decoded.width());
        for (var row = 0; row < copyRows; row++) {
            var source = row * stride;
            var target = row * rowBytes;
            if (samples == 4) {
                System.arraycopy(pixels, source, chunk, target, copyColumns * 4);
            } else {
                for (var x = 0; x < copyColumns; x++, source += 4, target += 3) {
                    chunk[target] = pixels[source];
                    chunk[target + 1] = pixels[source + 1];
                    chunk[target + 2] = pixels[source + 2];
                }
            }
        }
    }

    /**
     * Decodes a JPEG XL strip or tile, a bare codestream or a container.
     *
     * <p>Sample {@code i} of each pixel takes channel {@code i} of the image: the color channels,
     * then the extra channels in their coded order. Integer samples scale the nominal range from 0
     * to 1 onto {@code 0} to {@code 2^bits - 1}, rounded and clamped; floating-point samples keep
     * their values, as 16-bit or 32-bit IEEE numbers.
     *
     * @param data the JPEG XL data
     * @param length the number of bytes
     * @param chunk the zero-filled destination
     * @param rowBytes the size of one destination row
     * @param columns the pixels per destination row
     * @param rows the number of destination rows
     * @param samples the samples per pixel
     * @param bits the bits per sample: 1 to 16 for integers, 16 or 32 for floating point
     * @param floating whether the samples are floating point
     * @param little whether multi-byte samples are little-endian
     * @param scratch a row of at least {@code columns * samples} values, or a shorter one to replace
     * @return the scratch row, possibly replaced by a larger one
     * @throws MediaException.Malformed if the data is not a valid JPEG XL image or has fewer
     * channels than {@code samples}
     * @throws MediaException.LimitExceeded if the image has more pixels than a strip or tile
     */
    int[] jpegXl(byte[] data, int length, byte[] chunk, int rowBytes, int columns, int rows, int samples, int bits,
                 boolean floating, boolean little, int[] scratch) {
        var image = JxlDecoder.decodeFloat(MediaSource.of(data, 0, length), imageOptions);
        var planes = image.planes();
        if (planes.length < samples) {
            throw new MediaException.Malformed("a JPEG XL TIFF strip or tile has " + planes.length
                    + " channels, the image " + samples + " samples per pixel");
        }
        var copyRows = Math.min(rows, image.height());
        var copyColumns = Math.min(columns, image.width());
        var count = copyColumns * samples;
        var values = scratch.length < count ? new int[count] : scratch;
        var maximum = (float) ((1L << bits) - 1);
        for (var row = 0; row < copyRows; row++) {
            var source = row * image.width();
            for (var sample = 0; sample < samples; sample++) {
                var plane = planes[sample];
                if (floating && bits == 16) {
                    for (var x = 0; x < copyColumns; x++) {
                        values[x * samples + sample] = Float.floatToFloat16(plane[source + x]) & 0xFFFF;
                    }
                } else if (floating) {
                    for (var x = 0; x < copyColumns; x++) {
                        values[x * samples + sample] = Float.floatToRawIntBits(plane[source + x]);
                    }
                } else {
                    for (var x = 0; x < copyColumns; x++) {
                        var value = Math.round(plane[source + x] * maximum);
                        values[x * samples + sample] = Math.clamp(value, 0, (int) maximum);
                    }
                }
            }
            pack(values, count, chunk, row * rowBytes, bits, little);
        }
        return values;
    }

    /**
     * Decodes a JPEG 2000 strip or tile, a codestream or a JP2 file.
     *
     * <p>Sample {@code i} of each pixel takes the low {@code bits} bits of channel {@code i} of the
     * image, as stored (neither scaled to {@code bits} nor offset); a subsampled channel gives each
     * pixel its nearest sample to the upper left. When {@code ycbcr} is set the first three channels are Y, Cb and
     * Cr, converted to 8-bit red, green and blue.
     *
     * @param data the JPEG 2000 data
     * @param length the number of bytes
     * @param chunk the zero-filled destination
     * @param rowBytes the size of one destination row
     * @param columns the pixels per destination row
     * @param rows the number of destination rows
     * @param samples the samples per pixel
     * @param bits the bits per sample, from 1 to 32
     * @param little whether multi-byte samples are little-endian
     * @param ycbcr the conversion of Y, Cb and Cr channels to RGB, or {@code null} to keep the
     * channels
     * @param scratch a row of at least {@code columns * samples} values, or a shorter one to replace
     * @return the scratch row, possibly replaced by a larger one
     * @throws MediaException.Malformed if the data is not a valid JPEG 2000 image or has fewer
     * channels than {@code samples}
     * @throws MediaException.Unsupported if the data uses a JPEG 2000 feature the decoder lacks
     * @throws MediaException.LimitExceeded if the image has more pixels than a strip or tile
     */
    int[] jpeg2000(byte[] data, int length, byte[] chunk, int rowBytes, int columns, int rows, int samples, int bits,
                   boolean little, YCbCrTables ycbcr, int[] scratch) {
        var image = Jp2Decoder.decodeChannels(MediaSource.of(data, 0, length), jp2Options);
        var channels = image.channels();
        if (channels.size() < samples) {
            throw new MediaException.Malformed("a JPEG 2000 TIFF strip or tile has " + channels.size()
                    + " channels, the image " + samples + " samples per pixel");
        }
        var copyRows = Math.min(rows, image.height());
        var copyColumns = Math.min(columns, image.width());
        var count = copyColumns * samples;
        var values = scratch.length < count ? new int[count] : scratch;
        var mask = bits == 32 ? -1 : (int) ((1L << bits) - 1);
        for (var row = 0; row < copyRows; row++) {
            var y = (int) (image.y0() + row);
            for (var sample = 0; sample < samples; sample++) {
                var channel = channels.get(sample);
                var plane = channel.samples();
                var wide = channel.wideSamples();
                var line = rowStart(channel, y);
                for (var x = 0; x < copyColumns; x++) {
                    var at = line + column(channel, (int) (image.x0() + x));
                    values[x * samples + sample] = (wide != null ? (int) wide[at] : plane[at]) & mask;
                }
            }
            if (ycbcr != null) {
                for (var at = 0; at < count; at += samples) {
                    var rgb = ycbcr.toRgb(clamp8(values[at]), clamp8(values[at + 1]), clamp8(values[at + 2]));
                    values[at] = rgb >>> 16;
                    values[at + 1] = (rgb >>> 8) & 0xFF;
                    values[at + 2] = rgb & 0xFF;
                }
            }
            pack(values, count, chunk, row * rowBytes, bits, little);
        }
        return values;
    }

    /**
     * Returns the index of the first sample of the channel row covering a reference grid row.
     *
     * @param channel the decoded JPEG 2000 channel
     * @param y the row on the reference grid
     * @return the index into the channel's samples
     */
    private static int rowStart(Jp2Channel channel, int y) {
        var row = Math.clamp((long) y / channel.subsamplingY() - channel.y0(), 0, channel.height() - 1);
        return row * channel.width();
    }

    /**
     * Returns the channel column covering a reference grid column.
     *
     * @param channel the decoded JPEG 2000 channel
     * @param x the column on the reference grid
     * @return the column within the channel
     */
    private static int column(Jp2Channel channel, int x) {
        return Math.clamp((long) x / channel.subsamplingX() - channel.x0(), 0, channel.width() - 1);
    }

    /**
     * Clamps a sample to the 8-bit range.
     *
     * @param value the sample
     * @return the clamped sample
     */
    private static int clamp8(int value) {
        return Math.clamp(value, 0, 255);
    }

    /**
     * Writes one row of samples in the stored layout.
     *
     * @param values the samples, each within {@code bits} bits
     * @param count the number of samples
     * @param chunk the destination
     * @param offset the index of the row's first byte
     * @param bits the bits per sample, from 1 to 32
     * @param little whether 16-, 24- and 32-bit samples are little-endian
     */
    static void pack(int[] values, int count, byte[] chunk, int offset, int bits, boolean little) {
        switch (bits) {
            case 8 -> {
                for (var i = 0; i < count; i++) {
                    chunk[offset + i] = (byte) values[i];
                }
            }
            case 16, 24, 32 -> {
                var bytes = bits / 8;
                for (var i = 0; i < count; i++) {
                    var value = values[i];
                    var at = offset + i * bytes;
                    for (var b = 0; b < bytes; b++) {
                        chunk[at + (little ? b : bytes - 1 - b)] = (byte) (value >>> (8 * b));
                    }
                }
            }
            default -> {
                var accumulator = 0L;
                var pending = 0;
                var at = offset;
                var mask = (1L << bits) - 1;
                for (var i = 0; i < count; i++) {
                    accumulator = accumulator << bits | (values[i] & mask);
                    pending += bits;
                    while (pending >= 8) {
                        pending -= 8;
                        chunk[at++] = (byte) (accumulator >>> pending);
                    }
                }
                if (pending > 0) {
                    chunk[at] = (byte) (accumulator << (8 - pending));
                }
            }
        }
    }
}
