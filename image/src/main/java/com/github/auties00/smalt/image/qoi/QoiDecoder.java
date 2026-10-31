package com.github.auties00.smalt.image.qoi;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.DecodedImage;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.ImageFormat;
import com.github.auties00.smalt.image.ImageInfo;
import com.github.auties00.smalt.image.Orientation;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.internal.raster.RasterOutput;
import com.github.auties00.smalt.internal.io.ByteArrays;
import com.github.auties00.smalt.internal.io.SourceBytes;
import com.github.auties00.smalt.internal.limits.Guards;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.internal.region.ImageRegions;
import com.github.auties00.smalt.internal.task.ParallelBands;
import com.github.auties00.smalt.io.MediaSource;

import java.util.Objects;

/**
 * Decodes Quite OK Image (QOI) files to packed RGBA.
 *
 * <p>The decoder implements the QOI specification 1.0: the index, difference, luma, run, RGB and RGBA chunks, the
 * running pixel starting at opaque black and the 64-entry index of hashed pixels. The output equals that of the
 * reference decoder {@code qoi.h} byte for byte; a file of three channels decodes opaque, whatever alpha its chunks
 * carry. The end marker is not required. A file whose chunks end before every pixel is coded is malformed.
 *
 * <p>A file whose colorspace byte is 1 (all channels linear) reports linear light in the BT.709 primaries as its
 * {@link DecodedImage#colorProfile()}; one whose byte is 0 (sRGB with linear alpha) reports none, the pixels being
 * sRGB. {@link ImageDecoderOptions#convertToSrgb()} converts a linear file to sRGB.
 *
 * <p>This class is thread-safe.
 */
public final class QoiDecoder {
    private static final String WHAT = "QOI image";

    /**
     * The size of the header.
     */
    static final int HEADER = 14;

    private QoiDecoder() {
        throw new AssertionError();
    }

    /**
     * Summarizes a QOI file from its header.
     *
     * <p>{@link ImageInfo#hasAlpha()} is {@code true} when the header declares four channels.
     *
     * @param source the input, read from offset zero
     * @return the summary
     * @throws MediaException.Malformed if the header is invalid
     * @throws MediaException.Io if reading the source fails
     */
    public static ImageInfo probe(MediaSource source) {
        Objects.requireNonNull(source, "source");
        try (var _ = ParallelBands.operation()) {
            return Guards.malformed(WHAT, () -> {
                var header = new byte[HEADER];
                if (SourceBytes.readAvailable(source, 0, header, 0, HEADER) < HEADER) {
                    throw new MediaException.Malformed("the QOI header is truncated");
                }
                var parsed = Header.parse(header);
                return new ImageInfo(ImageFormat.QOI, parsed.width, parsed.height, parsed.channels == 4, false, 1,
                        Orientation.NORMAL);
            });
        }
    }

    /**
     * Decodes a QOI file under the {@link ImageDecoderOptions#defaults()}.
     *
     * @param source the input, read from offset zero
     * @return the decoded image
     * @throws MediaException.Malformed if the header is invalid or the chunks end early
     * @throws MediaException.LimitExceeded if the image exceeds the default limits
     * @throws MediaException.Io if reading the source fails
     * @see #decode(MediaSource, ImageDecoderOptions)
     */
    public static DecodedImage decode(MediaSource source) {
        return decode(source, ImageDecoderOptions.defaults());
    }

    /**
     * Decodes a QOI file.
     *
     * <p>The size hint, the bit depth and the channel choices of {@code options} are ignored.
     *
     * @param source the input, read from offset zero
     * @param options the limits, the region and the sRGB conversion choice
     * @return the decoded image
     * @throws IllegalArgumentException if the region does not lie inside the image
     * @throws MediaException.Malformed if the header is invalid or the chunks end early
     * @throws MediaException.LimitExceeded if the image exceeds the limits of {@code options}
     * @throws MediaException.Io if reading the source fails
     */
    public static DecodedImage decode(MediaSource source, ImageDecoderOptions options) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(options, "options");
        try (var _ = ParallelBands.operation()) {
            return ImageRegions.decode(options, null, whole -> Guards.malformed(WHAT, () -> {
                var data = SourceBytes.readAll(source, whole.limits(), false);
                return decode(data, whole);
            }));
        }
    }

    /**
     * Decodes a whole file held in memory.
     */
    private static DecodedImage decode(byte[] data, ImageDecoderOptions options) {
        if (data.length < HEADER) {
            throw new MediaException.Malformed("the QOI header is truncated");
        }
        var header = Header.parse(data);
        MediaLimits limits = options.limits();
        LimitChecks.checkPicture(limits, header.width, header.height);
        var pixelCount = (long) header.width * header.height;
        LimitChecks.checkAllocation(limits, pixelCount * 4);
        var picture = RgbaPicture.allocate(header.width, header.height, header.channels == 4);
        var pixels = picture.pixels();
        var index = new int[64];
        var r = 0;
        var g = 0;
        var b = 0;
        var a = 255;
        var run = 0;
        var position = HEADER;
        var end = pixels.length;
        for (var out = 0; out < end; out += 4) {
            if (run > 0) {
                run--;
            } else {
                if (position >= data.length) {
                    throw new MediaException.Malformed("the QOI chunks end after " + out / 4 + " of " + pixelCount
                            + " pixels");
                }
                var tag = data[position++] & 0xFF;
                if (tag == 0xFE) {
                    need(data, position, 3);
                    r = data[position] & 0xFF;
                    g = data[position + 1] & 0xFF;
                    b = data[position + 2] & 0xFF;
                    position += 3;
                } else if (tag == 0xFF) {
                    need(data, position, 4);
                    r = data[position] & 0xFF;
                    g = data[position + 1] & 0xFF;
                    b = data[position + 2] & 0xFF;
                    a = data[position + 3] & 0xFF;
                    position += 4;
                } else {
                    switch (tag >>> 6) {
                        case 0 -> {
                            var entry = index[tag];
                            r = entry >>> 24;
                            g = (entry >>> 16) & 0xFF;
                            b = (entry >>> 8) & 0xFF;
                            a = entry & 0xFF;
                        }
                        case 1 -> {
                            r = (r + ((tag >>> 4) & 3) - 2) & 0xFF;
                            g = (g + ((tag >>> 2) & 3) - 2) & 0xFF;
                            b = (b + (tag & 3) - 2) & 0xFF;
                        }
                        case 2 -> {
                            need(data, position, 1);
                            var second = data[position++] & 0xFF;
                            var dg = (tag & 0x3F) - 32;
                            r = (r + dg - 8 + ((second >>> 4) & 0x0F)) & 0xFF;
                            g = (g + dg) & 0xFF;
                            b = (b + dg - 8 + (second & 0x0F)) & 0xFF;
                        }
                        default -> run = tag & 0x3F;
                    }
                }
                index[(r * 3 + g * 5 + b * 7 + a * 11) & 63] = r << 24 | g << 16 | b << 8 | a;
            }
            pixels[out] = (byte) r;
            pixels[out + 1] = (byte) g;
            pixels[out + 2] = (byte) b;
            pixels[out + 3] = header.channels == 4 ? (byte) a : (byte) 255;
        }
        var profile = header.colorspace == 1 ? RasterOutput.linearRgb() : null;
        return RasterOutput.finish(picture, ImageFormat.QOI, Orientation.NORMAL, profile, null, options);
    }

    /**
     * Checks that a chunk's operand bytes are present.
     */
    private static void need(byte[] data, int position, int count) {
        if (position + count > data.length) {
            throw new MediaException.Malformed("a QOI chunk is truncated");
        }
    }

    /**
     * The fields of a QOI header.
     *
     * @param width the width, positive
     * @param height the height, positive
     * @param channels 3 or 4
     * @param colorspace 0 for sRGB with linear alpha, 1 for all channels linear
     */
    record Header(int width, int height, int channels, int colorspace) {
        /**
         * Parses and checks a header.
         */
        static Header parse(byte[] data) {
            if (data[0] != 'q' || data[1] != 'o' || data[2] != 'i' || data[3] != 'f') {
                throw new MediaException.Malformed("the file does not start with the QOI magic");
            }
            var width = ByteArrays.u32be(data, 4);
            var height = ByteArrays.u32be(data, 8);
            var channels = data[12] & 0xFF;
            var colorspace = data[13] & 0xFF;
            if (width == 0 || height == 0 || width > Integer.MAX_VALUE || height > Integer.MAX_VALUE) {
                throw new MediaException.Malformed("invalid QOI size " + width + "x" + height);
            }
            if (channels != 3 && channels != 4) {
                throw new MediaException.Malformed("invalid QOI channel count " + channels);
            }
            if (colorspace > 1) {
                throw new MediaException.Malformed("invalid QOI colorspace " + colorspace);
            }
            return new Header((int) width, (int) height, channels, colorspace);
        }
    }
}
