package com.github.auties00.smalt.image.pnm.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.Channel;
import com.github.auties00.smalt.image.ChannelType;
import com.github.auties00.smalt.image.PixelLayout;
import com.github.auties00.smalt.image.pnm.PnmVariant;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Locale;

/**
 * The header of one Netpbm or float map image and where its raster lies.
 *
 * @param variant the image kind
 * @param plain whether the raster is plain (ASCII decimal) rather than raw binary
 * @param width the width in pixels, positive
 * @param height the height in pixels, positive
 * @param depth the samples per pixel
 * @param maxval the largest sample value of an integer raster, from 1 to 65535; 1 for a bitmap and 0 for floats
 * @param tupleType the PAM tuple type, upper case, or the empty string
 * @param layout the channels the samples decode to
 * @param littleEndian whether the samples of a float map are little-endian
 * @param scale the absolute value of a float map's scale factor, or 0 for integer rasters
 * @param start the offset of the header's first byte
 * @param raster the offset of the raster's first byte
 * @param end the offset just past the raster
 */
public record PnmHeader(PnmVariant variant, boolean plain, int width, int height, int depth, int maxval,
                        String tupleType, PixelLayout layout, boolean littleEndian, double scale, long start,
                        long raster, long end) {
    /**
     * The largest number of characters of a header field.
     */
    private static final int MAX_TOKEN = 64;

    /**
     * The largest number of PAM header lines.
     */
    private static final int MAX_LINES = 4096;

    /**
     * {@return whether the samples are 16-bit IEEE 754 or 32-bit IEEE 754 floating point}
     */
    public boolean isFloat() {
        return variant == PnmVariant.PFM || variant == PnmVariant.PHM;
    }

    /**
     * {@return the bytes of one raw sample: 1 or 2 for integers, 2 or 4 for floats, and 0 for a bitmap}
     */
    public int sampleBytes() {
        return switch (variant) {
            case PBM -> 0;
            case PFM -> 4;
            case PHM -> 2;
            default -> maxval > 255 ? 2 : 1;
        };
    }

    /**
     * {@return the bytes of one raw row}
     */
    public long rowBytes() {
        if (variant == PnmVariant.PBM) {
            return (width + 7L) / 8;
        }
        return (long) width * depth * sampleBytes();
    }

    /**
     * {@return the significant bits of a sample: 1 for a bitmap, 16 or 32 for floats, the bits of maxval
     * otherwise}
     */
    public int bitDepth() {
        return switch (variant) {
            case PBM -> 1;
            case PFM -> 32;
            case PHM -> 16;
            default -> 32 - Integer.numberOfLeadingZeros(maxval);
        };
    }

    /**
     * Parses the header of the image starting at an offset and locates its raster.
     *
     * <p>The raster of a raw image ends after its rows; that of a plain image after its last sample, found by
     * reading the samples.
     *
     * @param data the file
     * @param offset the offset of the image's magic
     * @return the header
     * @throws MediaException.Malformed if the header is invalid or the raster is truncated
     * @throws MediaException.Unsupported if the magic is not one of the family's
     */
    public static PnmHeader parse(byte[] data, int offset) {
        var cursor = new Cursor(data, offset);
        if (cursor.remaining() < 2 || data[offset] != 'P') {
            throw new MediaException.Malformed("the image does not start with a Netpbm magic number");
        }
        var kind = data[offset + 1];
        cursor.position = offset + 2;
        if (!cursor.atSpace()) {
            throw new MediaException.Malformed("the Netpbm magic number is not followed by white space");
        }
        return switch (kind) {
            case '1', '4' -> classic(cursor, offset, PnmVariant.PBM, kind == '1', 1, PixelLayout.GRAY);
            case '2', '5' -> classic(cursor, offset, PnmVariant.PGM, kind == '2', 1, PixelLayout.GRAY);
            case '3', '6' -> classic(cursor, offset, PnmVariant.PPM, kind == '3', 3, PixelLayout.RGB);
            case '7' -> pam(cursor, offset);
            case 'F', 'f' -> floats(cursor, offset, PnmVariant.PFM, kind == 'F');
            case 'H', 'h' -> floats(cursor, offset, PnmVariant.PHM, kind == 'H');
            default -> throw new MediaException.Unsupported("pnm", "unknown Netpbm magic P" + (char) (kind & 0xFF));
        };
    }

    /**
     * Returns the offset of the next image of a file, after skipping the white space that follows an image.
     *
     * @param data the file
     * @param end the end of the previous image's raster
     * @return the offset of the next image's magic, or {@code -1} when no image follows
     */
    public static int next(byte[] data, long end) {
        var position = (int) end;
        while (position < data.length && isSpace(data[position])) {
            position++;
        }
        if (position + 2 >= data.length || data[position] != 'P' || !isSpace(data[position + 2])) {
            return -1;
        }
        return switch (data[position + 1]) {
            case '1', '2', '3', '4', '5', '6', '7', 'F', 'f', 'H', 'h' -> position;
            default -> -1;
        };
    }

    /**
     * Parses a PBM, PGM or PPM header.
     */
    private static PnmHeader classic(Cursor cursor, int start, PnmVariant variant, boolean plain, int depth,
                                     PixelLayout layout) {
        var width = cursor.positive("width");
        var height = cursor.positive("height");
        var maxval = 1;
        if (variant != PnmVariant.PBM) {
            maxval = cursor.positive("maxval");
            if (maxval > 65535) {
                throw new MediaException.Malformed("maxval " + maxval + " exceeds 65535");
            }
        }
        if (!cursor.atSpace()) {
            throw new MediaException.Malformed("the Netpbm header does not end with white space");
        }
        var raster = cursor.position + 1L;
        var header = new PnmHeader(variant, plain, width, height, depth, maxval, "", layout, false, 0, start, raster,
                raster);
        return header.withEnd(plain ? plainEnd(cursor.data, raster, header) : rawEnd(cursor.data, header));
    }

    /**
     * Parses a PAM header, from the line after {@code P7} to the line {@code ENDHDR}.
     */
    private static PnmHeader pam(Cursor cursor, int start) {
        var width = -1;
        var height = -1;
        var depth = -1;
        var maxval = -1;
        var tuple = new StringBuilder();
        var data = cursor.data;
        for (var lines = 0; ; lines++) {
            if (lines >= MAX_LINES || cursor.position >= data.length) {
                throw new MediaException.Malformed("the PAM header has no ENDHDR line");
            }
            var lineEnd = cursor.position;
            while (lineEnd < data.length && data[lineEnd] != '\n') {
                lineEnd++;
            }
            var line = new String(data, cursor.position, lineEnd - cursor.position, StandardCharsets.ISO_8859_1).strip();
            cursor.position = Math.min(data.length, lineEnd + 1);
            if (line.isEmpty() || line.charAt(0) == '#') {
                continue;
            }
            var space = 0;
            while (space < line.length() && !isSpace((byte) line.charAt(space))) {
                space++;
            }
            var key = line.substring(0, space);
            var value = line.substring(space).strip();
            switch (key) {
                case "ENDHDR" -> {
                    if (lineEnd >= data.length) {
                        throw new MediaException.Malformed("the PAM header ends without a raster");
                    }
                    if (width <= 0 || height <= 0 || depth <= 0 || maxval <= 0) {
                        throw new MediaException.Malformed("the PAM header lacks WIDTH, HEIGHT, DEPTH or MAXVAL");
                    }
                    if (maxval > 65535) {
                        throw new MediaException.Malformed("maxval " + maxval + " exceeds 65535");
                    }
                    var type = tuple.toString().toUpperCase(Locale.ROOT);
                    var layout = pamLayout(type, depth);
                    var raster = (long) cursor.position;
                    var header = new PnmHeader(PnmVariant.PAM, false, width, height, depth, maxval, type, layout,
                            false, 0, start, raster, raster);
                    return header.withEnd(rawEnd(data, header));
                }
                case "WIDTH" -> width = number(value, "WIDTH");
                case "HEIGHT" -> height = number(value, "HEIGHT");
                case "DEPTH" -> depth = number(value, "DEPTH");
                case "MAXVAL" -> maxval = number(value, "MAXVAL");
                case "TUPLTYPE" -> {
                    if (tuple.length() + value.length() > MAX_TOKEN * 16) {
                        throw new MediaException.Malformed("the PAM tuple type is too long");
                    }
                    if (!tuple.isEmpty()) {
                        tuple.append(' ');
                    }
                    tuple.append(value);
                }
                default -> throw new MediaException.Malformed("unknown PAM header line " + key);
            }
        }
    }

    /**
     * Parses a PFM or PHM header.
     */
    private static PnmHeader floats(Cursor cursor, int start, PnmVariant variant, boolean color) {
        var width = cursor.positive("width");
        var height = cursor.positive("height");
        var token = cursor.token("scale");
        double scale;
        try {
            scale = Double.parseDouble(token);
        } catch (NumberFormatException exception) {
            throw new MediaException.Malformed("invalid float map scale " + token, exception);
        }
        if (scale == 0 || !Double.isFinite(scale)) {
            throw new MediaException.Malformed("invalid float map scale " + token);
        }
        if (!cursor.atSpace()) {
            throw new MediaException.Malformed("the float map header does not end with white space");
        }
        var raster = cursor.position + 1L;
        var depth = color ? 3 : 1;
        var header = new PnmHeader(variant, false, width, height, depth, 0, "", color ? PixelLayout.RGB
                : PixelLayout.GRAY, scale < 0, Math.abs(scale), start, raster, raster);
        return header.withEnd(rawEnd(cursor.data, header));
    }

    /**
     * Maps a PAM tuple type to the layout of its samples; an unknown tuple type is read by its depth.
     */
    private static PixelLayout pamLayout(String type, int depth) {
        var known = switch (type) {
            case "BLACKANDWHITE", "GRAYSCALE" -> PixelLayout.GRAY;
            case "BLACKANDWHITE_ALPHA", "GRAYSCALE_ALPHA" -> PixelLayout.GRAY_ALPHA;
            case "RGB" -> PixelLayout.RGB;
            case "RGB_ALPHA" -> PixelLayout.RGBA;
            case "CMYK" -> PixelLayout.CMYK;
            case "CMYK_ALPHA" -> PixelLayout.CMYKA;
            default -> null;
        };
        if (known != null && known.count() == depth) {
            return known;
        }
        if (known != null && known.count() < depth) {
            var channels = new ArrayList<>(known.channels());
            while (channels.size() < depth) {
                channels.add(Channel.of(ChannelType.UNSPECIFIED));
            }
            return PixelLayout.of(channels.toArray(Channel[]::new));
        }
        return switch (depth) {
            case 1 -> PixelLayout.GRAY;
            case 2 -> PixelLayout.GRAY_ALPHA;
            case 3 -> PixelLayout.RGB;
            case 4 -> PixelLayout.RGBA;
            default -> {
                var channels = new Channel[depth];
                for (var index = 0; index < depth; index++) {
                    channels[index] = Channel.of(ChannelType.UNSPECIFIED);
                }
                yield PixelLayout.of(channels);
            }
        };
    }

    /**
     * Parses a decimal header value.
     */
    private static int number(String value, String what) {
        if (value.isEmpty() || value.length() > 10) {
            throw new MediaException.Malformed("invalid PAM " + what + " " + value);
        }
        var result = 0L;
        for (var index = 0; index < value.length(); index++) {
            var c = value.charAt(index);
            if (c < '0' || c > '9') {
                throw new MediaException.Malformed("invalid PAM " + what + " " + value);
            }
            result = result * 10 + (c - '0');
        }
        if (result <= 0 || result > Integer.MAX_VALUE) {
            throw new MediaException.Malformed("invalid PAM " + what + " " + value);
        }
        return (int) result;
    }

    /**
     * Returns the end of a raw raster.
     *
     * @throws MediaException.Malformed if the file ends before it
     */
    private static long rawEnd(byte[] data, PnmHeader header) {
        var end = header.raster + header.rowBytes() * header.height;
        if (header.rowBytes() > Integer.MAX_VALUE || end > data.length) {
            throw new MediaException.Malformed("the Netpbm raster is truncated");
        }
        return end;
    }

    /**
     * Returns the end of a plain raster, after its last sample.
     *
     * @throws MediaException.Malformed if the file ends before it
     */
    private static long plainEnd(byte[] data, long raster, PnmHeader header) {
        var samples = (long) header.width * header.height * header.depth;
        var position = (int) raster;
        if (header.variant == PnmVariant.PBM) {
            for (var count = 0L; count < samples; count++) {
                position = skipSpace(data, position);
                if (position >= data.length || (data[position] != '0' && data[position] != '1')) {
                    throw new MediaException.Malformed("the plain PBM raster is truncated or invalid");
                }
                position++;
            }
            return position;
        }
        for (var count = 0L; count < samples; count++) {
            position = skipSpace(data, position);
            if (position >= data.length || data[position] < '0' || data[position] > '9') {
                throw new MediaException.Malformed("the plain Netpbm raster is truncated or invalid");
            }
            while (position < data.length && data[position] >= '0' && data[position] <= '9') {
                position++;
            }
        }
        return position;
    }

    /**
     * Skips white space and comments in a plain raster.
     */
    public static int skipSpace(byte[] data, int position) {
        while (position < data.length) {
            if (isSpace(data[position])) {
                position++;
            } else if (data[position] == '#') {
                while (position < data.length && data[position] != '\n' && data[position] != '\r') {
                    position++;
                }
            } else {
                break;
            }
        }
        return position;
    }

    /**
     * {@return this header with another raster end}
     */
    private PnmHeader withEnd(long end) {
        return new PnmHeader(variant, plain, width, height, depth, maxval, tupleType, layout, littleEndian, scale,
                start, raster, end);
    }

    /**
     * {@return whether a byte is Netpbm white space}
     *
     * @param value the byte
     */
    public static boolean isSpace(byte value) {
        return value == ' ' || (value >= 9 && value <= 13);
    }

    /**
     * Reads the white-space separated fields of a header, skipping comments.
     */
    private static final class Cursor {
        final byte[] data;
        int position;

        Cursor(byte[] data, int position) {
            this.data = data;
            this.position = position;
        }

        int remaining() {
            return data.length - position;
        }

        boolean atSpace() {
            return position < data.length && isSpace(data[position]);
        }

        /**
         * Reads the next field, leaving the cursor on the byte after it.
         */
        String token(String what) {
            position = skipSpace(data, position);
            var begin = position;
            while (position < data.length && !isSpace(data[position]) && data[position] != '#') {
                if (position - begin >= MAX_TOKEN) {
                    throw new MediaException.Malformed("the Netpbm " + what + " field is too long");
                }
                position++;
            }
            if (position == begin) {
                throw new MediaException.Malformed("the Netpbm header ends before its " + what);
            }
            return new String(data, begin, position - begin, StandardCharsets.ISO_8859_1);
        }

        /**
         * Reads a positive decimal field.
         */
        int positive(String what) {
            var token = token(what);
            var value = 0L;
            for (var index = 0; index < token.length(); index++) {
                var c = token.charAt(index);
                if (c < '0' || c > '9' || index >= 10) {
                    throw new MediaException.Malformed("invalid Netpbm " + what + " " + token);
                }
                value = value * 10 + (c - '0');
            }
            if (value <= 0 || value > Integer.MAX_VALUE) {
                throw new MediaException.Malformed("invalid Netpbm " + what + " " + token);
            }
            return (int) value;
        }
    }
}
