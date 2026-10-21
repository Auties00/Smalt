package com.github.auties00.smalt.image.ico;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.DecodedImage;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.ImageFormat;
import com.github.auties00.smalt.image.ImageInfo;
import com.github.auties00.smalt.image.Orientation;
import com.github.auties00.smalt.image.PageReader;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.bmp.BmpDecoder;
import com.github.auties00.smalt.image.bmp.internal.BmpHeader;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.image.png.PngDecoder;
import com.github.auties00.smalt.internal.io.ByteArrays;
import com.github.auties00.smalt.internal.io.SourceBytes;
import com.github.auties00.smalt.internal.limits.Guards;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.io.MediaSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Decodes Windows icon (ICO) and cursor (CUR) files to packed RGBA8.
 *
 * <p>A file is a directory of images, each stored either as a PNG file or as a bitmap without its
 * file header whose height counts the color rows and a 1-bit AND mask below them. Bitmap entries
 * of every depth {@link BmpDecoder} reads are supported (1, 4, 8, 16, 24 and 32 bits per pixel);
 * their transparency is the alpha channel of a 32-bit bitmap whose alpha is not all zero, and the
 * AND mask otherwise (a set bit is a transparent pixel). PNG entries decode as {@link PngDecoder}
 * decodes them, with their ICC profile and EXIF. A bitmap entry is a packed bitmap: its pixel data
 * follows its header, color masks and color table as {@link BmpDecoder} sizes them, and its AND
 * mask, of rows padded to 4 bytes, follows the pixel data.
 *
 * <p>{@link #decode(MediaSource, ImageDecoderOptions)} decodes the largest entry (the most pixels,
 * then the most bits per pixel), or, when the options carry a size hint, the smallest entry at
 * least that large; {@link #entries(MediaSource)} lists every entry and
 * {@link #decode(MediaSource, int, ImageDecoderOptions)} decodes any of them. Cursor entries report
 * their hotspot in {@link IcoEntry#hotspot()}.
 *
 * <p>Directory sizes of zero mean 256 pixels; a directory entry whose image lies outside the file
 * is malformed. The number of entries is checked against {@link MediaLimits#maxFrames()}.
 *
 * <p>This class is thread-safe.
 */
public final class IcoDecoder {
    /**
     * The PNG file signature.
     */
    private static final byte[] PNG_SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};

    /**
     * The input named in failure messages.
     */
    private static final String WHAT = "icon file";

    /**
     * The size of the BMP file header synthesized in front of a bitmap entry.
     */
    private static final int FILE_HEADER = 14;

    /**
     * Prevents instantiation.
     */
    private IcoDecoder() {
        throw new AssertionError();
    }

    /**
     * Summarizes the entry {@link #decode(MediaSource)} decodes.
     *
     * <p>{@link ImageInfo#hasAlpha()} is {@code true}: any entry may carry transparency, through its
     * alpha channel or its AND mask, which only decoding confirms.
     *
     * @param source the input, read from offset zero
     * @return the summary; {@link ImageInfo#frameCount()} is the number of entries
     * @throws MediaException.Malformed if the directory is invalid
     * @throws MediaException.LimitExceeded if the entry count exceeds the default frame limit
     * @throws MediaException.Io if reading the source fails
     */
    public static ImageInfo probe(MediaSource source) {
        var entries = entries(source);
        var entry = entries.get(choose(entries, 0, 0));
        return info(entry, entries.size());
    }

    /**
     * Lists the entries of an icon or cursor file under the {@link ImageDecoderOptions#defaults()}.
     *
     * @param source the input, read from offset zero
     * @return the entries, in directory order, at least one
     * @throws MediaException.Malformed if the directory is invalid
     * @throws MediaException.LimitExceeded if the entry count exceeds the default frame limit
     * @throws MediaException.Io if reading the source fails
     * @see #entries(MediaSource, ImageDecoderOptions)
     */
    public static List<IcoEntry> entries(MediaSource source) {
        return entries(source, ImageDecoderOptions.defaults());
    }

    /**
     * Lists the entries of an icon or cursor file, reading it under the limits of
     * {@code options}.
     *
     * @param source the input, read from offset zero
     * @param options the limits; the other options are ignored
     * @return the entries, in directory order, at least one
     * @throws MediaException.Malformed if the directory is invalid
     * @throws MediaException.LimitExceeded if the entry count exceeds the frame limit
     * @throws MediaException.Io if reading the source fails
     */
    public static List<IcoEntry> entries(MediaSource source, ImageDecoderOptions options) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(options, "options");
        return Guards.malformed(WHAT, () -> Directory.read(source, options.limits()).entries());
    }

    /**
     * Decodes the largest entry under the {@link ImageDecoderOptions#defaults()}.
     *
     * @param source the input, read from offset zero
     * @return the decoded entry
     * @throws MediaException.Malformed if the file or the entry is invalid
     * @throws MediaException.Unsupported if the entry uses a feature its decoder lacks
     * @throws MediaException.LimitExceeded if the entry exceeds the limits or decoding runs out of
     * memory
     * @throws MediaException.Io if reading the source fails
     * @see #decode(MediaSource, ImageDecoderOptions)
     */
    public static DecodedImage decode(MediaSource source) {
        return decode(source, ImageDecoderOptions.defaults());
    }

    /**
     * Decodes the entry that best fits the options: the largest one, or, with a size hint, the
     * smallest one at least {@link ImageDecoderOptions#minWidth()} by
     * {@link ImageDecoderOptions#minHeight()} (the largest when none is).
     *
     * @param source the input, read from offset zero
     * @param options the limits, the size hint and the bit depth choice (for PNG entries)
     * @return the decoded entry
     * @throws MediaException.Malformed if the file or the entry is invalid
     * @throws MediaException.Unsupported if the entry uses a feature its decoder lacks
     * @throws MediaException.LimitExceeded if the entry exceeds the limits or decoding runs out of
     * memory
     * @throws MediaException.Io if reading the source fails
     */
    public static DecodedImage decode(MediaSource source, ImageDecoderOptions options) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(options, "options");
        return Guards.malformed(WHAT, () -> {
            var directory = Directory.read(source, options.limits());
            var index = choose(directory.entries(), options.minWidth(), options.minHeight());
            return directory.decode(index, options.toBuilder().fullSize().build());
        });
    }

    /**
     * Decodes one entry.
     *
     * @param source the input, read from offset zero
     * @param index the entry index, from zero to the entry count minus one
     * @param options the limits and the bit depth choice (for PNG entries); the size hint is
     * ignored
     * @return the decoded entry
     * @throws IndexOutOfBoundsException if {@code index} is out of range
     * @throws MediaException.Malformed if the file or the entry is invalid
     * @throws MediaException.Unsupported if the entry uses a feature its decoder lacks
     * @throws MediaException.LimitExceeded if the entry exceeds the limits or decoding runs out of
     * memory
     * @throws MediaException.Io if reading the source fails
     */
    public static DecodedImage decode(MediaSource source, int index, ImageDecoderOptions options) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(options, "options");
        var directory = Guards.malformed(WHAT, () -> Directory.read(source, options.limits()));
        Objects.checkIndex(index, directory.entries().size());
        return Guards.malformed(WHAT, () -> directory.decode(index, options.toBuilder().fullSize().build()));
    }

    /**
     * Opens the entries of an icon or cursor file as pages, in directory order.
     *
     * <p>Each page decodes its entry at the entry's own size: the size hint of {@code options} is
     * ignored. Page summaries report transparency as {@link #probe(MediaSource)} does.
     *
     * @param source the input, read from offset zero
     * @param options the limits and the bit depth choice applied to every entry
     * @return the page reader
     * @throws MediaException.Malformed if the directory is invalid
     * @throws MediaException.LimitExceeded if the entry count exceeds the frame limit
     * @throws MediaException.Io if reading the source fails
     */
    public static PageReader openPages(MediaSource source, ImageDecoderOptions options) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(options, "options");
        var directory = Guards.malformed(WHAT, () -> Directory.read(source, options.limits()));
        var full = options.toBuilder().fullSize().build();
        return new PageReader() {
            @Override
            public int pageCount() {
                return directory.entries().size();
            }

            @Override
            public ImageInfo probe(int index) {
                return info(directory.entries().get(Objects.checkIndex(index, pageCount())), pageCount());
            }

            @Override
            public DecodedImage read(int index) {
                Objects.checkIndex(index, pageCount());
                return Guards.malformed(WHAT, () -> directory.decode(index, full));
            }
        };
    }

    /**
     * Opens the entries of an icon or cursor file as pages, in directory order, under the
     * {@link ImageDecoderOptions#defaults()}.
     *
     * <p>Each page decodes its entry at the entry's own size.
     *
     * @param source the input, read from offset zero
     * @return the page reader
     * @throws MediaException.Malformed if the directory is invalid
     * @throws MediaException.LimitExceeded if the entry count exceeds the default frame limit
     * @throws MediaException.Io if reading the source fails
     * @see #openPages(MediaSource, ImageDecoderOptions)
     */
    public static PageReader openPages(MediaSource source) {
        return openPages(source, ImageDecoderOptions.defaults());
    }

    /**
     * Picks the entry to decode.
     *
     * @param entries the entries to choose from
     * @param minWidth the smallest acceptable width, or zero
     * @param minHeight the smallest acceptable height, or zero
     * @return the index of the chosen entry: with a size hint, the acceptable entry with the
     * fewest pixels; otherwise, or when no entry is acceptable, the entry with the most
     * pixels; ties go to the most bits per pixel, then to the first entry
     */
    static int choose(List<IcoEntry> entries, int minWidth, int minHeight) {
        var best = -1;
        if (minWidth > 0 || minHeight > 0) {
            for (var i = 0; i < entries.size(); i++) {
                var entry = entries.get(i);
                if (entry.width() >= minWidth && entry.height() >= minHeight
                        && (best < 0 || better(entry, entries.get(best), true))) {
                    best = i;
                }
            }
            if (best >= 0) {
                return best;
            }
        }
        for (var i = 0; i < entries.size(); i++) {
            if (best < 0 || better(entries.get(i), entries.get(best), false)) {
                best = i;
            }
        }
        return best;
    }

    /**
     * Returns whether an entry is a better choice than the current one.
     *
     * @param candidate the entry that may replace {@code current}
     * @param current the current choice
     * @param smaller whether fewer pixels are better
     * @return {@code true} when the candidate has fewer (or, without {@code smaller}, more) pixels,
     * or as many and more bits per pixel
     */
    private static boolean better(IcoEntry candidate, IcoEntry current, boolean smaller) {
        var pixels = Long.compare((long) candidate.width() * candidate.height(), (long) current.width() * current.height());
        if (pixels != 0) {
            return smaller ? pixels < 0 : pixels > 0;
        }
        return candidate.bitDepth() > current.bitDepth();
    }

    /**
     * Summarizes an entry; every entry may carry transparency.
     *
     * @param entry the entry to summarize
     * @param count the number of entries
     * @return the summary
     * @throws MediaException.Malformed if the entry has no valid size
     */
    private static ImageInfo info(IcoEntry entry, int count) {
        if (entry.width() <= 0 || entry.height() <= 0) {
            throw new MediaException.Malformed("the icon entry has no valid size");
        }
        return new ImageInfo(ImageFormat.ICO, entry.width(), entry.height(), true, false, count, Orientation.NORMAL);
    }

    /**
     * Holds the parsed directory of a file.
     *
     * @param source the input
     * @param limits the resource bounds checked while decoding entries
     * @param entries the entry descriptions
     * @param offsets the file offset of each entry's image
     * @param sizes the size of each entry's image in bytes, clipped to the file
     */
    record Directory(MediaSource source, MediaLimits limits, List<IcoEntry> entries, long[] offsets, long[] sizes) {
        /**
         * Reads the directory.
         *
         * @param source the input
         * @param limits the resource bounds the directory is checked against
         * @return the directory
         * @throws MediaException.Malformed if the directory is invalid
         * @throws MediaException.LimitExceeded if the entry count exceeds the frame limit
         */
        static Directory read(MediaSource source, MediaLimits limits) {
            var header = new byte[6];
            if (SourceBytes.readAvailable(source, 0, header, 0, 6) < 6 || header[0] != 0 || header[1] != 0
                    || (header[2] != 1 && header[2] != 2) || header[3] != 0) {
                throw new MediaException.Malformed("invalid icon header");
            }
            var cursor = header[2] == 2;
            var count = ByteArrays.u16le(header, 4);
            if (count == 0) {
                throw new MediaException.Malformed("the icon file has no entries");
            }
            LimitChecks.checkFrames(limits, count);
            var table = new byte[count * 16];
            if (SourceBytes.readAvailable(source, 6, table, 0, table.length) < table.length) {
                throw new MediaException.Malformed("truncated icon directory");
            }
            var size = source.size();
            var entries = new ArrayList<IcoEntry>(count);
            var offsets = new long[count];
            var sizes = new long[count];
            for (var i = 0; i < count; i++) {
                var at = i * 16;
                var width = table[at] & 0xFF;
                var height = table[at + 1] & 0xFF;
                var planes = ByteArrays.u16le(table, at + 4);
                var bits = ByteArrays.u16le(table, at + 6);
                var length = ByteArrays.u32le(table, at + 8);
                var offset = ByteArrays.u32le(table, at + 12);
                if (offset < 6 + 16L * count || length == 0 || (size >= 0 && offset >= size)) {
                    throw new MediaException.Malformed("icon entry " + i + " lies outside the file");
                }
                offsets[i] = offset;
                sizes[i] = size >= 0 ? Math.min(length, size - offset) : length;
                width = width == 0 ? 256 : width;
                height = height == 0 ? 256 : height;
                var probe = new byte[26];
                var read = SourceBytes.readAvailable(source, offset, probe, 0, probe.length);
                var encoding = IcoEntry.Encoding.BMP;
                var depth = cursor ? 0 : bits;
                if (read >= 26 && isPng(probe)) {
                    encoding = IcoEntry.Encoding.PNG;
                    width = (int) Math.min(Integer.MAX_VALUE, ByteArrays.u32be(probe, 16));
                    height = (int) Math.min(Integer.MAX_VALUE, ByteArrays.u32be(probe, 20));
                    var channels = switch (probe[25]) {
                        case 0, 3 -> 1;
                        case 4 -> 2;
                        case 2 -> 3;
                        default -> 4;
                    };
                    depth = (probe[24] & 0xFF) * channels;
                } else if (read >= 16) {
                    var headerSize = ByteArrays.u32le(probe, 0);
                    if (headerSize == 12) {
                        width = ByteArrays.u16le(probe, 4);
                        height = ByteArrays.u16le(probe, 6) / 2;
                        depth = ByteArrays.u16le(probe, 10);
                    } else if (headerSize >= 40) {
                        width = (int) Math.min(Integer.MAX_VALUE, Math.abs((long) ByteArrays.i32le(probe, 4)));
                        height = (int) Math.min(Integer.MAX_VALUE, Math.abs((long) ByteArrays.i32le(probe, 8)) / 2);
                        depth = ByteArrays.u16le(probe, 14);
                    }
                }
                var hotspot = cursor ? Optional.of(new IcoEntry.Hotspot(planes, bits)) : Optional.<IcoEntry.Hotspot>empty();
                entries.add(new IcoEntry(i, width, height, depth, encoding, hotspot));
            }
            return new Directory(source, limits, List.copyOf(entries), offsets, sizes);
        }

        /**
         * Decodes one entry.
         *
         * @param index the entry index
         * @param options the decoding options, without size hint
         * @return the decoded entry
         * @throws MediaException.Malformed if the entry is invalid
         */
        DecodedImage decode(int index, ImageDecoderOptions options) {
            LimitChecks.checkAllocation(limits, sizes[index]);
            var bytes = new byte[(int) sizes[index]];
            var read = SourceBytes.readAvailable(source, offsets[index], bytes, 0, bytes.length);
            if (read < bytes.length) {
                bytes = Arrays.copyOf(bytes, read);
            }
            if (bytes.length >= 8 && isPng(bytes)) {
                var png = PngDecoder.decode(MediaSource.of(bytes), options);
                return new DecodedImage(png.picture(), ImageFormat.ICO, Orientation.NORMAL, png.sourceWidth(),
                        png.sourceHeight(), png.iccProfile(), png.exif());
            }
            return decodeBitmap(bytes, options);
        }

        /**
         * Decodes a bitmap entry and applies its AND mask.
         *
         * <p>The entry is decoded by {@link BmpDecoder} behind a synthesized file header whose
         * height is the stored height halved; the AND mask starts where the pixel data that
         * {@link BmpHeader} locates ends.
         *
         * @param dib the bitmap header and data
         * @param options the decoding options
         * @return the decoded entry
         * @throws MediaException.Malformed if the bitmap is invalid
         */
        private DecodedImage decodeBitmap(byte[] dib, ImageDecoderOptions options) {
            if (dib.length < 16) {
                throw new MediaException.Malformed("truncated icon bitmap");
            }
            var headerSize = ByteArrays.u32le(dib, 0);
            if (headerSize != 12 && (headerSize < 40 || headerSize > dib.length)) {
                throw new MediaException.Malformed("invalid icon bitmap header size " + headerSize);
            }
            var core = headerSize == 12;
            var width = core ? ByteArrays.u16le(dib, 4) : ByteArrays.i32le(dib, 4);
            var total = core ? ByteArrays.u16le(dib, 6) : Math.abs(ByteArrays.i32le(dib, 8));
            var height = total / 2;
            var masked = height > 0;
            if (!masked) {
                height = total;
            }
            if (width <= 0 || height <= 0) {
                throw new MediaException.Malformed("invalid icon bitmap size " + width + "x" + height);
            }
            var file = new byte[FILE_HEADER + dib.length];
            file[0] = 'B';
            file[1] = 'M';
            ByteArrays.put32le(file, 2, file.length);
            System.arraycopy(dib, 0, file, FILE_HEADER, dib.length);
            if (core) {
                ByteArrays.put16le(file, FILE_HEADER + 6, height);
            } else {
                ByteArrays.put32le(file, FILE_HEADER + 8, height);
            }
            var header = BmpHeader.read(MediaSource.of(file));
            var decoded = BmpDecoder.decode(MediaSource.of(file), options);
            var picture = decoded.picture() instanceof RgbaPicture rgba ? rgba : ImageOps.toRgba(decoded.picture());
            var uncompressed = header.compression() == BmpHeader.RGB || header.compression() == BmpHeader.BITFIELDS;
            if (masked && uncompressed && !picture.hasAlpha()) {
                var maskStart = header.dataOffset() - FILE_HEADER + header.rowStride() * height;
                var maskRow = (width + 31L) / 32 * 4;
                picture = applyMask(picture, dib, maskStart, maskRow);
            }
            return new DecodedImage(picture, ImageFormat.ICO, Orientation.NORMAL, width, height, decoded.iccProfile(),
                    Optional.empty());
        }

        /**
         * Makes the pixels the AND mask sets transparent.
         *
         * @param picture the opaque picture
         * @param dib the bitmap header and data
         * @param maskStart the offset of the mask in {@code dib}
         * @param maskRow the size of one mask row
         * @return the picture with its transparency, unchanged when the mask is absent or empty
         */
        private static RgbaPicture applyMask(RgbaPicture picture, byte[] dib, long maskStart, long maskRow) {
            var width = picture.width();
            var height = picture.height();
            if (maskStart < 0 || maskStart + maskRow * height > dib.length) {
                return picture;
            }
            var pixels = picture.pixels();
            var stride = picture.stride();
            var transparent = false;
            for (var row = 0; row < height; row++) {
                var y = height - 1 - row;
                var base = (int) (maskStart + row * maskRow);
                for (var x = 0; x < width; x++) {
                    if ((dib[base + (x >>> 3)] & (0x80 >>> (x & 7))) != 0) {
                        var at = y * stride + x * 4;
                        pixels[at] = 0;
                        pixels[at + 1] = 0;
                        pixels[at + 2] = 0;
                        pixels[at + 3] = 0;
                        transparent = true;
                    }
                }
            }
            return transparent ? RgbaPicture.of(width, height, stride, pixels, true) : picture;
        }
    }

    /**
     * Returns whether bytes start with the PNG signature.
     *
     * @param data the bytes, at least 8
     * @return {@code true} for a PNG file
     */
    private static boolean isPng(byte[] data) {
        for (var i = 0; i < PNG_SIGNATURE.length; i++) {
            if (data[i] != PNG_SIGNATURE[i]) {
                return false;
            }
        }
        return true;
    }
}
