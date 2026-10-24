package com.github.auties00.smalt.video;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.track.Codec;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;

/**
 * Describes how the bytes of an uncompressed video frame, a packet of a {@link Codec#RAW_VIDEO} track, map to
 * samples: the {@linkplain RawPixelFormat pixel format}, the bit depth of planar formats, the byte order of their
 * 16-bit samples, the alignment of every row, the row order and, for {@link RawPixelFormat#PAL8}, the palette.
 *
 * <p>A frame stores the planes of its pixel format one after the other (packed formats have one plane). Each row of
 * a plane starts at a multiple of the {@linkplain #rowAlignment() row alignment} from the start of its plane, and
 * the rows of a plane are stored top to bottom, or bottom to top when {@link #bottomUp()} is set. The frame size is
 * the sum of the plane sizes; {@link #frameSize(int, int)} computes it.
 *
 * <p>A {@link Codec#RAW_VIDEO} track's {@linkplain com.github.auties00.smalt.track.VideoTrack#codecConfig() codec
 * configuration} is {@link #toCodecConfig()}, which {@link #fromCodecConfig(byte[])} reads back:
 * {@snippet :
 * u8[4]  magic "SMRV"
 * u8     version, 1
 * u8     length of the pixel format name
 * u8[n]  the RawPixelFormat constant name, ASCII
 * u8     bit depth, 8 to 16
 * u8     flags: bit 0 big-endian samples, bit 1 bottom-up rows
 * u16be  row alignment in bytes
 * u16be  palette entries, 0 to 256
 * u32be  each palette entry, 0xAARRGGBB
 * }
 *
 * <p>Instances are immutable and thread-safe.
 */
public final class RawVideoFormat {
    /**
     * The magic number that starts a codec configuration.
     */
    private static final byte[] MAGIC = {'S', 'M', 'R', 'V'};

    /**
     * The version of the codec configuration layout.
     */
    private static final int VERSION = 1;

    /**
     * The largest row alignment, in bytes.
     */
    private static final int MAX_ALIGNMENT = 4096;

    private final RawPixelFormat pixelFormat;

    private final int bitDepth;

    private final boolean bigEndian;

    private final int rowAlignment;

    private final boolean bottomUp;

    private final int[] palette;

    private RawVideoFormat(RawPixelFormat pixelFormat, int bitDepth, boolean bigEndian, int rowAlignment,
                           boolean bottomUp, int[] palette) {
        this.pixelFormat = pixelFormat;
        this.bitDepth = bitDepth;
        this.bigEndian = bigEndian;
        this.rowAlignment = rowAlignment;
        this.bottomUp = bottomUp;
        this.palette = palette;
    }

    /**
     * Returns a format of a pixel format at its default bit depth, with rows one after the other (an alignment of
     * one byte), top to bottom, little-endian, and for {@link RawPixelFormat#PAL8} a palette of 256 opaque gray
     * levels.
     *
     * @param pixelFormat the pixel format
     * @return the format
     */
    public static RawVideoFormat of(RawPixelFormat pixelFormat) {
        Objects.requireNonNull(pixelFormat, "pixelFormat");
        return new RawVideoFormat(pixelFormat, pixelFormat.defaultBitDepth(), false, 1, false,
                pixelFormat == RawPixelFormat.PAL8 ? grayPalette() : null);
    }

    /**
     * Returns a format of a planar pixel format at a bit depth, otherwise as {@link #of(RawPixelFormat)}.
     *
     * @param pixelFormat the pixel format
     * @param bitDepth the bit depth of every sample, from 8 to 16
     * @return the format
     * @throws IllegalArgumentException if the depth is outside 8 to 16, or the format's depth is fixed and differs
     */
    public static RawVideoFormat of(RawPixelFormat pixelFormat, int bitDepth) {
        return of(pixelFormat).withBitDepth(bitDepth);
    }

    /**
     * {@return the pixel format}
     */
    public RawPixelFormat pixelFormat() {
        return pixelFormat;
    }

    /**
     * {@return the bit depth of every component, from 8 to 16}
     */
    public int bitDepth() {
        return bitDepth;
    }

    /**
     * Returns whether the two-byte samples of a planar format deeper than 8 bits are big-endian.
     *
     * @return {@code true} for big-endian samples; always {@code false} at a depth of 8 and for the packed formats,
     * whose byte order their name gives
     */
    public boolean bigEndian() {
        return bigEndian;
    }

    /**
     * {@return the row alignment in bytes: every row of a plane starts at a multiple of it from the start of the
     * plane, from 1 to 4096}
     */
    public int rowAlignment() {
        return rowAlignment;
    }

    /**
     * {@return whether the rows are stored bottom to top}
     */
    public boolean bottomUp() {
        return bottomUp;
    }

    /**
     * Returns the palette of a {@link RawPixelFormat#PAL8} format.
     *
     * @return the entries as {@code 0xAARRGGBB}, a copy of 1 to 256 entries, or empty for other formats; an index
     * past the last entry decodes to opaque black
     */
    public Optional<int[]> palette() {
        return palette == null ? Optional.empty() : Optional.of(palette.clone());
    }

    /**
     * Returns this format at another bit depth.
     *
     * @param bitDepth the bit depth, from 8 to 16
     * @return the format
     * @throws IllegalArgumentException if the depth is outside 8 to 16, or the format's depth is fixed and differs
     */
    public RawVideoFormat withBitDepth(int bitDepth) {
        if (bitDepth < 8 || bitDepth > 16) {
            throw new IllegalArgumentException("bit depth " + bitDepth + " is outside 8 to 16");
        }
        if (!pixelFormat.hasVariableDepth() && bitDepth != pixelFormat.defaultBitDepth()) {
            throw new IllegalArgumentException(pixelFormat + " has a fixed depth of " + pixelFormat.defaultBitDepth());
        }
        return new RawVideoFormat(pixelFormat, bitDepth, bitDepth > 8 && bigEndian, rowAlignment, bottomUp, palette);
    }

    /**
     * Returns this format with another byte order of its two-byte samples.
     *
     * @param bigEndian whether the samples are big-endian
     * @return the format
     * @throws IllegalArgumentException if {@code bigEndian} is set and the format is not planar at a depth above 8
     */
    public RawVideoFormat withBigEndian(boolean bigEndian) {
        if (bigEndian && (!pixelFormat.hasVariableDepth() || bitDepth == 8)) {
            throw new IllegalArgumentException("only planar formats deeper than 8 bits have a byte order");
        }
        return new RawVideoFormat(pixelFormat, bitDepth, bigEndian, rowAlignment, bottomUp, palette);
    }

    /**
     * Returns this format with another row alignment.
     *
     * @param bytes the alignment in bytes, from 1 to 4096
     * @return the format
     * @throws IllegalArgumentException if the alignment is outside 1 to 4096
     */
    public RawVideoFormat withRowAlignment(int bytes) {
        if (bytes < 1 || bytes > MAX_ALIGNMENT) {
            throw new IllegalArgumentException("row alignment " + bytes + " is outside 1 to " + MAX_ALIGNMENT);
        }
        return new RawVideoFormat(pixelFormat, bitDepth, bigEndian, bytes, bottomUp, palette);
    }

    /**
     * Returns this format with another row order.
     *
     * @param bottomUp whether the rows are stored bottom to top
     * @return the format
     */
    public RawVideoFormat withBottomUp(boolean bottomUp) {
        return new RawVideoFormat(pixelFormat, bitDepth, bigEndian, rowAlignment, bottomUp, palette);
    }

    /**
     * Returns this format with another palette.
     *
     * @param palette the entries as {@code 0xAARRGGBB}, 1 to 256; copied
     * @return the format
     * @throws IllegalArgumentException if the format is not {@link RawPixelFormat#PAL8} or the palette has no entry
     * or more than 256
     */
    public RawVideoFormat withPalette(int[] palette) {
        Objects.requireNonNull(palette, "palette");
        if (pixelFormat != RawPixelFormat.PAL8) {
            throw new IllegalArgumentException(pixelFormat + " has no palette");
        }
        if (palette.length == 0 || palette.length > 256) {
            throw new IllegalArgumentException("a palette has 1 to 256 entries, not " + palette.length);
        }
        return new RawVideoFormat(pixelFormat, bitDepth, bigEndian, rowAlignment, bottomUp, palette.clone());
    }

    /**
     * Returns the number of planes of a frame.
     *
     * @return 1 for the packed and palette formats, 2 for the semi-planar ones, 3 for planar YUV, 4 with alpha
     */
    public int planes() {
        return switch (pixelFormat.kind()) {
            case PLANAR -> pixelFormat == RawPixelFormat.GRAY ? 1 : pixelFormat.hasAlpha() ? 4 : 3;
            case SEMI_PLANAR -> 2;
            default -> 1;
        };
    }

    /**
     * Returns the bytes of one row of a plane, before alignment.
     *
     * @param plane the plane, from 0 to {@link #planes()} minus one
     * @param width the frame width in pixels, positive
     * @return the row size in bytes
     * @throws IndexOutOfBoundsException if the plane does not exist
     */
    public long rowBytes(int plane, int width) {
        Objects.checkIndex(plane, planes());
        var sample = bitDepth > 8 ? 2L : 1L;
        return switch (pixelFormat.kind()) {
            case PLANAR -> sample * (plane == 0 || plane == 3 ? width : chromaWidth(width));
            case SEMI_PLANAR -> plane == 0 ? width : 2L * ((width + 1) / 2);
            case PACKED_422 -> 4L * ((width + 1) / 2);
            case V210 -> 128L * ((width + 47) / 48);
            case RGB -> switch (pixelFormat) {
                case RGB24, BGR24 -> 3L * width;
                case RGB565LE, RGB565BE, RGB555LE, RGB555BE -> 2L * width;
                case RGB48BE, RGB48LE -> 6L * width;
                case RGBA64BE, ARGB64BE -> 8L * width;
                default -> 4L * width;
            };
            case PALETTE -> width;
        };
    }

    /**
     * Returns the number of rows of a plane.
     *
     * @param plane the plane, from 0 to {@link #planes()} minus one
     * @param height the frame height in pixels, positive
     * @return the row count
     * @throws IndexOutOfBoundsException if the plane does not exist
     */
    public int rows(int plane, int height) {
        Objects.checkIndex(plane, planes());
        if (plane == 0 || plane == 3) {
            return height;
        }
        return switch (pixelFormat) {
            case YUV420P, YVU420P, YUVA420P, YUV440P, NV12, NV21 -> (height + 1) / 2;
            default -> height;
        };
    }

    /**
     * Returns the distance between the starts of two rows of a plane: the row size rounded up to the alignment.
     *
     * @param plane the plane, from 0 to {@link #planes()} minus one
     * @param width the frame width in pixels, positive
     * @return the stride in bytes
     * @throws IndexOutOfBoundsException if the plane does not exist
     */
    public long stride(int plane, int width) {
        var bytes = rowBytes(plane, width);
        return (bytes + rowAlignment - 1) / rowAlignment * rowAlignment;
    }

    /**
     * Returns the size of one frame.
     *
     * @param width the frame width in pixels, positive
     * @param height the frame height in pixels, positive
     * @return the size in bytes
     * @throws IllegalArgumentException if a dimension is not positive
     */
    public long frameSize(int width, int height) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("invalid frame size " + width + "x" + height);
        }
        var total = 0L;
        for (var plane = 0; plane < planes(); plane++) {
            total += stride(plane, width) * rows(plane, height);
        }
        return total;
    }

    /**
     * Returns the width of a chroma plane of a planar format.
     *
     * @param width the frame width
     * @return the chroma width
     */
    private int chromaWidth(int width) {
        return switch (pixelFormat) {
            case YUV420P, YVU420P, YUVA420P, YUV422P -> (width + 1) / 2;
            case YUV411P -> (width + 3) / 4;
            default -> width;
        };
    }

    /**
     * Returns the codec configuration of a {@link Codec#RAW_VIDEO} track in this format, laid out as the class
     * description shows.
     *
     * @return the configuration, a new array
     */
    public byte[] toCodecConfig() {
        var name = pixelFormat.name().getBytes(StandardCharsets.US_ASCII);
        var entries = palette == null ? 0 : palette.length;
        var out = new byte[4 + 1 + 1 + name.length + 1 + 1 + 2 + 2 + 4 * entries];
        System.arraycopy(MAGIC, 0, out, 0, 4);
        var at = 4;
        out[at++] = VERSION;
        out[at++] = (byte) name.length;
        System.arraycopy(name, 0, out, at, name.length);
        at += name.length;
        out[at++] = (byte) bitDepth;
        out[at++] = (byte) ((bigEndian ? 1 : 0) | (bottomUp ? 2 : 0));
        out[at++] = (byte) (rowAlignment >>> 8);
        out[at++] = (byte) rowAlignment;
        out[at++] = (byte) (entries >>> 8);
        out[at++] = (byte) entries;
        for (var i = 0; i < entries; i++) {
            out[at++] = (byte) (palette[i] >>> 24);
            out[at++] = (byte) (palette[i] >>> 16);
            out[at++] = (byte) (palette[i] >>> 8);
            out[at++] = (byte) palette[i];
        }
        return out;
    }

    /**
     * Reads the codec configuration of a {@link Codec#RAW_VIDEO} track.
     *
     * @param config the configuration, laid out as the class description shows
     * @return the format
     * @throws MediaException.Malformed if the configuration is truncated or invalid
     * @throws MediaException.Unsupported if it names a pixel format or layout version this release does not know
     */
    public static RawVideoFormat fromCodecConfig(byte[] config) {
        Objects.requireNonNull(config, "config");
        if (config.length < 6 || !Arrays.equals(config, 0, 4, MAGIC, 0, 4)) {
            throw new MediaException.Malformed("not a raw video configuration");
        }
        if (config[4] != VERSION) {
            throw new MediaException.Unsupported("raw video", "raw video configuration version " + config[4]);
        }
        var nameLength = config[5] & 0xFF;
        if (config.length < 6 + nameLength + 6) {
            throw new MediaException.Malformed("truncated raw video configuration");
        }
        var name = new String(config, 6, nameLength, StandardCharsets.US_ASCII);
        RawPixelFormat pixelFormat;
        try {
            pixelFormat = RawPixelFormat.valueOf(name);
        } catch (IllegalArgumentException unknown) {
            throw new MediaException.Unsupported("raw video", "unknown raw pixel format " + name);
        }
        var at = 6 + nameLength;
        var depth = config[at++] & 0xFF;
        var flags = config[at++] & 0xFF;
        var alignment = (config[at] & 0xFF) << 8 | (config[at + 1] & 0xFF);
        var entries = (config[at + 2] & 0xFF) << 8 | (config[at + 3] & 0xFF);
        at += 4;
        if (config.length < at + 4L * entries) {
            throw new MediaException.Malformed("truncated raw video palette");
        }
        try {
            var format = of(pixelFormat).withBitDepth(depth).withRowAlignment(alignment)
                    .withBottomUp((flags & 2) != 0);
            if ((flags & 1) != 0) {
                format = format.withBigEndian(true);
            }
            if (entries > 0) {
                var palette = new int[entries];
                for (var i = 0; i < entries; i++) {
                    palette[i] = (config[at] & 0xFF) << 24 | (config[at + 1] & 0xFF) << 16
                            | (config[at + 2] & 0xFF) << 8 | (config[at + 3] & 0xFF);
                    at += 4;
                }
                format = format.withPalette(palette);
            }
            return format;
        } catch (IllegalArgumentException invalid) {
            throw new MediaException.Malformed("invalid raw video configuration: " + invalid.getMessage());
        }
    }

    /**
     * Returns a palette of 256 opaque gray levels, from black to white.
     *
     * @return the palette
     */
    private static int[] grayPalette() {
        var palette = new int[256];
        for (var i = 0; i < 256; i++) {
            palette[i] = 0xFF000000 | i << 16 | i << 8 | i;
        }
        return palette;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof RawVideoFormat that && pixelFormat == that.pixelFormat && bitDepth == that.bitDepth
                && bigEndian == that.bigEndian && rowAlignment == that.rowAlignment && bottomUp == that.bottomUp
                && Arrays.equals(palette, that.palette);
    }

    @Override
    public int hashCode() {
        return Objects.hash(pixelFormat, bitDepth, bigEndian, rowAlignment, bottomUp, Arrays.hashCode(palette));
    }

    @Override
    public String toString() {
        return "RawVideoFormat[" + pixelFormat + ", " + bitDepth + " bits" + (bigEndian ? ", big-endian" : "")
                + ", rows aligned to " + rowAlignment + (bottomUp ? ", bottom-up" : "")
                + (palette != null ? ", " + palette.length + " palette entries" : "") + "]";
    }
}
