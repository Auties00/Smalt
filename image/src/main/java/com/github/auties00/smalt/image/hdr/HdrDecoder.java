package com.github.auties00.smalt.image.hdr;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.ChannelFloatPicture;
import com.github.auties00.smalt.image.Chromaticity;
import com.github.auties00.smalt.image.DecodedImage;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.ImageFormat;
import com.github.auties00.smalt.image.ImageInfo;
import com.github.auties00.smalt.image.Orientation;
import com.github.auties00.smalt.image.PixelLayout;
import com.github.auties00.smalt.image.color.ColorProfile;
import com.github.auties00.smalt.image.internal.raster.RasterOutput;
import com.github.auties00.smalt.internal.io.SourceBytes;
import com.github.auties00.smalt.internal.limits.Guards;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.internal.region.ImageRegions;
import com.github.auties00.smalt.internal.task.ParallelBands;
import com.github.auties00.smalt.io.MediaSource;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Objects;

/**
 * Decodes Radiance RGBE (HDR) images to floating-point pixels.
 *
 * <p>Supported: the {@code 32-bit_rle_rgbe} and {@code 32-bit_rle_xyze} formats, flat scanlines, the adaptive
 * run-length scanlines of widths 8 to 32767 and the old run-length scheme of repeated pixels, and the eight
 * orientations of the resolution string, the pixels being returned top row first and left to right. A pixel
 * {@code (R, G, B, E)} decodes to {@code (R, G, B) * 2^(E - 136)}, and to zero when {@code E} is 0. The
 * {@code EXPOSURE} and {@code COLORCORR} lines are not applied: the samples are returned as stored. Scanlines that
 * end early leave the remaining pixels zero.
 *
 * <p>The samples are linear light. {@link DecodedImage#colorProfile()} reports the primaries of the
 * {@code PRIMARIES} line, or by default those of Radiance (red 0.640, 0.330, green 0.290, 0.600, blue 0.150, 0.060,
 * the equal-energy white 1/3, 1/3), with a linear tone curve; an XYZE image reports
 * {@link ColorProfile#xyz()}. With {@link ImageDecoderOptions#keepChannels()} the image decodes to an RGB
 * {@link ChannelFloatPicture}; otherwise to packed RGBA of 8 bits, or 16 bits with
 * {@link ImageDecoderOptions#highBitDepth()}, values clamped to 0..1 and scaled without a transfer function.
 * {@link ImageDecoderOptions#convertToSrgb()} converts the floating-point samples to sRGB first.
 *
 * <p>This class is thread-safe.
 */
public final class HdrDecoder {
    private static final String WHAT = "Radiance image";

    /**
     * The longest header the decoder reads.
     */
    private static final int MAX_HEADER = 1 << 16;

    private HdrDecoder() {
        throw new AssertionError();
    }

    /**
     * Summarizes a Radiance image from its header.
     *
     * @param source the input, read from offset zero
     * @return the summary
     * @throws MediaException.Malformed if the header is invalid
     * @throws MediaException.Unsupported if the pixel format is neither RGBE nor XYZE
     * @throws MediaException.Io if reading the source fails
     */
    public static ImageInfo probe(MediaSource source) {
        Objects.requireNonNull(source, "source");
        try (var _ = ParallelBands.operation()) {
            return Guards.malformed(WHAT, () -> {
                var head = new byte[MAX_HEADER];
                var length = SourceBytes.readAvailable(source, 0, head, 0, head.length);
                var header = Header.parse(head, length);
                return new ImageInfo(ImageFormat.HDR, header.width, header.height, false, false, 1,
                        Orientation.NORMAL);
            });
        }
    }

    /**
     * Decodes a Radiance image under the {@link ImageDecoderOptions#defaults()}.
     *
     * @param source the input, read from offset zero
     * @return the decoded image
     * @throws MediaException.Malformed if the header or a scanline is invalid
     * @throws MediaException.Unsupported if the pixel format is neither RGBE nor XYZE
     * @throws MediaException.LimitExceeded if the image exceeds the default limits
     * @throws MediaException.Io if reading the source fails
     * @see #decode(MediaSource, ImageDecoderOptions)
     */
    public static DecodedImage decode(MediaSource source) {
        return decode(source, ImageDecoderOptions.defaults());
    }

    /**
     * Decodes a Radiance image.
     *
     * <p>The size hint of {@code options} is ignored.
     *
     * @param source the input, read from offset zero
     * @param options the limits, the region, the bit depth, the channel and the sRGB conversion choices
     * @return the decoded image
     * @throws IllegalArgumentException if the region does not lie inside the image
     * @throws MediaException.Malformed if the header or a scanline is invalid
     * @throws MediaException.Unsupported if the pixel format is neither RGBE nor XYZE
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
        var header = Header.parse(data, data.length);
        MediaLimits limits = options.limits();
        var width = header.width;
        var height = header.height;
        LimitChecks.checkPicture(limits, width, height);
        LimitChecks.checkAllocation(limits, (long) width * height * 16);
        var lineLength = header.columnMajor ? height : width;
        var lines = header.columnMajor ? width : height;
        var rgbe = new byte[lineLength * 4];
        var picture = ChannelFloatPicture.allocate(width, height, PixelLayout.RGB);
        var pixels = picture.pixels();
        var position = header.dataOffset;
        for (var line = 0; line < lines && position < data.length; line++) {
            java.util.Arrays.fill(rgbe, (byte) 0);
            position = scanline(data, position, rgbe, lineLength);
            for (var index = 0; index < lineLength; index++) {
                int x;
                int y;
                if (header.columnMajor) {
                    x = header.flipX ? width - 1 - line : line;
                    y = header.flipY ? height - 1 - index : index;
                } else {
                    y = header.flipY ? height - 1 - line : line;
                    x = header.flipX ? width - 1 - index : index;
                }
                var at = 4 * index;
                var exponent = rgbe[at + 3] & 0xFF;
                var out = (y * width + x) * 3;
                if (exponent == 0) {
                    continue;
                }
                var scale = Math.scalb(1f, exponent - 136);
                pixels[out] = (rgbe[at] & 0xFF) * scale;
                pixels[out + 1] = (rgbe[at + 1] & 0xFF) * scale;
                pixels[out + 2] = (rgbe[at + 2] & 0xFF) * scale;
            }
        }
        return RasterOutput.finish(picture, ImageFormat.HDR, Orientation.NORMAL, header.profile(), null, options);
    }

    /**
     * Reads one scanline into RGBE quadruples, returning the position after it; a scanline cut short by the end of
     * the file leaves its remaining pixels zero.
     */
    private static int scanline(byte[] data, int position, byte[] rgbe, int length) {
        if (length >= 8 && length <= 0x7FFF && position + 4 <= data.length && data[position] == 2
                && data[position + 1] == 2 && (data[position + 2] & 0x80) == 0) {
            var declared = (data[position + 2] & 0xFF) << 8 | (data[position + 3] & 0xFF);
            if (declared != length) {
                throw new MediaException.Malformed("a Radiance scanline declares width " + declared + ", not "
                        + length);
            }
            position += 4;
            for (var component = 0; component < 4; component++) {
                var x = 0;
                while (x < length) {
                    if (position >= data.length) {
                        return position;
                    }
                    var count = data[position++] & 0xFF;
                    if (count > 128) {
                        count -= 128;
                        if (count > length - x) {
                            throw new MediaException.Malformed("a Radiance run overruns its scanline");
                        }
                        if (position >= data.length) {
                            return position;
                        }
                        var value = data[position++];
                        for (var index = 0; index < count; index++) {
                            rgbe[4 * (x++) + component] = value;
                        }
                    } else {
                        if (count == 0 || count > length - x) {
                            throw new MediaException.Malformed("a Radiance literal overruns its scanline");
                        }
                        for (var index = 0; index < count && position < data.length; index++) {
                            rgbe[4 * (x++) + component] = data[position++];
                        }
                    }
                }
            }
            return position;
        }
        var x = 0;
        var shift = 0;
        while (x < length && position + 4 <= data.length) {
            var r = data[position];
            var g = data[position + 1];
            var b = data[position + 2];
            var e = data[position + 3];
            position += 4;
            if (r == 1 && g == 1 && b == 1) {
                if (x == 0 || shift > 24) {
                    throw new MediaException.Malformed("a Radiance repeat has no pixel to repeat");
                }
                var count = (long) (e & 0xFF) << shift;
                if (count > length - x) {
                    throw new MediaException.Malformed("a Radiance repeat overruns its scanline");
                }
                for (var index = 0L; index < count; index++, x++) {
                    System.arraycopy(rgbe, 4 * (x - 1), rgbe, 4 * x, 4);
                }
                shift += 8;
            } else {
                rgbe[4 * x] = r;
                rgbe[4 * x + 1] = g;
                rgbe[4 * x + 2] = b;
                rgbe[4 * x + 3] = e;
                x++;
                shift = 0;
            }
        }
        return position;
    }

    /**
     * The fields of a Radiance header.
     */
    private static final class Header {
        int width;
        int height;
        boolean columnMajor;
        boolean flipX;
        boolean flipY;
        boolean xyz;
        double[] primaries;
        int dataOffset;

        ColorProfile profile() {
            if (xyz) {
                return ColorProfile.xyz();
            }
            var p = primaries != null ? primaries : new double[]{0.640, 0.330, 0.290, 0.600, 0.150, 0.060,
                    1.0 / 3, 1.0 / 3};
            try {
                return ColorProfile.rgb(new Chromaticity(p[0], p[1]), new Chromaticity(p[2], p[3]),
                        new Chromaticity(p[4], p[5]), new Chromaticity(p[6], p[7]), 1.0);
            } catch (IllegalArgumentException exception) {
                return null;
            }
        }

        static Header parse(byte[] data, int length) {
            if (length < 2 || data[0] != '#' || data[1] != '?') {
                throw new MediaException.Malformed("the file does not start with the Radiance magic #?");
            }
            var header = new Header();
            var position = 0;
            var format = (String) null;
            var limit = Math.min(length, MAX_HEADER);
            while (true) {
                var end = position;
                while (end < limit && data[end] != '\n') {
                    end++;
                }
                if (end >= limit) {
                    throw new MediaException.Malformed("the Radiance header has no end");
                }
                var line = new String(data, position, end - position, StandardCharsets.ISO_8859_1).strip();
                position = end + 1;
                if (line.isEmpty()) {
                    break;
                }
                if (line.startsWith("FORMAT=")) {
                    format = line.substring(7).strip();
                } else if (line.startsWith("PRIMARIES=")) {
                    var parts = line.substring(10).strip().split("\\s+");
                    if (parts.length == 8) {
                        try {
                            var values = new double[8];
                            for (var index = 0; index < 8; index++) {
                                values[index] = Double.parseDouble(parts[index]);
                            }
                            header.primaries = values;
                        } catch (NumberFormatException ignored) {
                            // a damaged PRIMARIES line leaves the default primaries
                        }
                    }
                }
            }
            if (format != null && !format.equals("32-bit_rle_rgbe") && !format.equals("32-bit_rle_xyze")) {
                throw new MediaException.Unsupported("hdr", "Radiance pixel format " + format);
            }
            header.xyz = "32-bit_rle_xyze".equals(format);
            var end = position;
            while (end < limit && data[end] != '\n') {
                end++;
            }
            if (end >= length) {
                throw new MediaException.Malformed("the Radiance resolution line is truncated");
            }
            var fields = new String(data, position, end - position, StandardCharsets.ISO_8859_1).strip()
                    .toUpperCase(Locale.ROOT).split("\\s+");
            if (fields.length != 4 || fields[0].length() != 2 || fields[2].length() != 2) {
                throw new MediaException.Malformed("invalid Radiance resolution line");
            }
            var firstAxis = fields[0].charAt(1);
            var secondAxis = fields[2].charAt(1);
            if (!((firstAxis == 'Y' && secondAxis == 'X') || (firstAxis == 'X' && secondAxis == 'Y'))) {
                throw new MediaException.Malformed("invalid Radiance resolution line");
            }
            var first = dimension(fields[1]);
            var second = dimension(fields[3]);
            header.columnMajor = firstAxis == 'X';
            var ySign = header.columnMajor ? fields[2].charAt(0) : fields[0].charAt(0);
            var xSign = header.columnMajor ? fields[0].charAt(0) : fields[2].charAt(0);
            if ((ySign != '+' && ySign != '-') || (xSign != '+' && xSign != '-')) {
                throw new MediaException.Malformed("invalid Radiance resolution line");
            }
            header.flipY = ySign == '+';
            header.flipX = xSign == '-';
            header.width = header.columnMajor ? first : second;
            header.height = header.columnMajor ? second : first;
            header.dataOffset = end + 1;
            return header;
        }

        static int dimension(String text) {
            try {
                var value = Integer.parseInt(text);
                if (value <= 0) {
                    throw new MediaException.Malformed("invalid Radiance dimension " + text);
                }
                return value;
            } catch (NumberFormatException exception) {
                throw new MediaException.Malformed("invalid Radiance dimension " + text, exception);
            }
        }
    }
}
