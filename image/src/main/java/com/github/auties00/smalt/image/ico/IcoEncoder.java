package com.github.auties00.smalt.image.ico;

import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.image.ops.ResizeFilter;
import com.github.auties00.smalt.image.png.PngEncoder;
import com.github.auties00.smalt.image.png.PngEncoderOptions;
import com.github.auties00.smalt.internal.io.ByteArrays;
import com.github.auties00.smalt.io.MediaSink;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Encodes pictures as Windows icon (ICO) or cursor (CUR) files.
 *
 * <p>Each entry is stored as a 32-bit BGRA bitmap, whose AND mask marks the fully transparent
 * pixels for readers that ignore alpha, or as a PNG file, as {@link IcoEncoderOptions#encoding()}
 * selects. A single picture becomes one entry of its own size, or, with
 * {@link IcoEncoderOptions#sizes()}, one square entry per size: the picture is scaled to
 * fit the square (area averaging when shrinking, bicubic when enlarging) and centered on a
 * transparent background. A list of pictures becomes one entry per picture. An entry is at most
 * 256 by 256 pixels, as the directory cannot describe more. With a hotspot the file is a cursor,
 * and the hotspot is scaled and offset with the picture into every entry.
 *
 * <p>This class is thread-safe.
 */
public final class IcoEncoder {
    /**
     * Prevents instantiation.
     */
    private IcoEncoder() {
        throw new AssertionError();
    }

    /**
     * Encodes a picture as a one-entry icon with the {@link IcoEncoderOptions#defaults()}.
     *
     * @param picture the picture, at most 256 by 256 pixels
     * @return the complete file
     * @throws IllegalArgumentException if the picture is larger than 256 by 256 pixels
     * @see #encode(Picture, IcoEncoderOptions)
     */
    public static byte[] encode(Picture picture) {
        return encode(picture, IcoEncoderOptions.defaults());
    }

    /**
     * Encodes a picture as an icon or cursor.
     *
     * @param picture the picture; at most 256 by 256 pixels unless the options set sizes
     * @param options the entry encoding, the entry sizes and the cursor hotspot
     * @return the complete file
     * @throws IllegalArgumentException if an entry would be larger than 256 by 256 pixels
     */
    public static byte[] encode(Picture picture, IcoEncoderOptions options) {
        var sink = MediaSink.memory();
        encode(picture, options, sink);
        return sink.toByteArray();
    }

    /**
     * Encodes a picture as an icon or cursor and writes it to a sink.
     *
     * <p>The bytes written are those {@link #encode(Picture, IcoEncoderOptions)} returns.
     *
     * @param picture the picture; at most 256 by 256 pixels unless the options set sizes
     * @param options the entry encoding, the entry sizes and the cursor hotspot
     * @param sink the output, written from its position; not closed
     * @throws IllegalArgumentException if an entry would be larger than 256 by 256 pixels
     * @throws com.github.auties00.smalt.MediaException.Io if writing the sink fails
     */
    public static void encode(Picture picture, IcoEncoderOptions options, MediaSink sink) {
        Objects.requireNonNull(picture, "picture");
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(sink, "sink");
        var rgba = ImageOps.toRgba(picture);
        var sizes = options.sizes();
        var entries = new ArrayList<Entry>();
        if (sizes.length == 0) {
            entries.add(new Entry(rgba, options.hotspot().orElse(null)));
        } else {
            for (var size : sizes) {
                entries.add(scaled(rgba, size, options.hotspot().orElse(null)));
            }
        }
        write(entries, options, sink);
    }

    /**
     * Encodes pictures as a multi-entry icon or cursor.
     *
     * @param pictures the pictures, one entry each, each at most 256 by 256 pixels
     * @param options the entry encoding and the cursor hotspot; the sizes must be empty
     * @return the complete file
     * @throws NullPointerException if a picture is {@code null}
     * @throws IllegalArgumentException if {@code pictures} is empty, a picture is larger than 256
     * by 256 pixels, or the options set sizes
     */
    public static byte[] encode(List<? extends Picture> pictures, IcoEncoderOptions options) {
        var sink = MediaSink.memory();
        encode(pictures, options, sink);
        return sink.toByteArray();
    }

    /**
     * Encodes pictures as a multi-entry icon or cursor and writes it to a sink.
     *
     * <p>A cursor's hotspot applies to every entry as given.
     *
     * @param pictures the pictures, one entry each, each at most 256 by 256 pixels
     * @param options the entry encoding and the cursor hotspot; the sizes must be empty
     * @param sink the output, written from its position; not closed
     * @throws NullPointerException if a picture is {@code null}
     * @throws IllegalArgumentException if {@code pictures} is empty, a picture is larger than 256
     * by 256 pixels, or the options set sizes
     * @throws com.github.auties00.smalt.MediaException.Io if writing the sink fails
     */
    public static void encode(List<? extends Picture> pictures, IcoEncoderOptions options, MediaSink sink) {
        Objects.requireNonNull(pictures, "pictures");
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(sink, "sink");
        if (pictures.isEmpty()) {
            throw new IllegalArgumentException("an icon file needs at least one entry");
        }
        if (options.sizes().length > 0) {
            throw new IllegalArgumentException("sizes apply to a single picture, not to a list");
        }
        var entries = new ArrayList<Entry>();
        for (var picture : pictures) {
            entries.add(new Entry(ImageOps.toRgba(Objects.requireNonNull(picture, "picture")), options.hotspot().orElse(null)));
        }
        write(entries, options, sink);
    }

    /**
     * Holds one entry to write.
     *
     * @param picture the pixels, at most 256 by 256
     * @param hotspot the cursor hotspot in the entry, or {@code null} for an icon
     */
    private record Entry(RgbaPicture picture, IcoEntry.Hotspot hotspot) {
    }

    /**
     * Scales a picture into a square entry.
     *
     * @param picture the source pixels
     * @param size the side of the entry in pixels
     * @param hotspot the hotspot in the source pixels, or {@code null}
     * @return the entry, with the hotspot moved into it
     */
    private static Entry scaled(RgbaPicture picture, int size, IcoEntry.Hotspot hotspot) {
        var width = picture.width();
        var height = picture.height();
        var scale = Math.min((double) size / width, (double) size / height);
        var filter = scale < 1 ? ResizeFilter.AREA : ResizeFilter.BICUBIC;
        var fitted = width == size && height == size ? picture
                : (RgbaPicture) ImageOps.fitInto(picture, size, size, filter, 0);
        IcoEntry.Hotspot moved = null;
        if (hotspot != null) {
            var w = Math.max(1, (int) Math.round(width * scale));
            var h = Math.max(1, (int) Math.round(height * scale));
            var x = (int) Math.min(size - 1, Math.round(hotspot.x() * scale) + (size - w) / 2);
            var y = (int) Math.min(size - 1, Math.round(hotspot.y() * scale) + (size - h) / 2);
            moved = new IcoEntry.Hotspot(Math.max(0, x), Math.max(0, y));
        }
        return new Entry(fitted, moved);
    }

    /**
     * Writes the directory and the entries.
     *
     * @param entries the entries, in directory order
     * @param options the entry encoding and the file type
     * @param sink the output
     * @throws IllegalArgumentException if an entry is larger than 256 by 256 pixels or there are
     * more than 65535 entries
     */
    private static void write(List<Entry> entries, IcoEncoderOptions options, MediaSink sink) {
        if (entries.size() > 0xFFFF) {
            throw new IllegalArgumentException("an icon file holds at most 65535 entries");
        }
        var images = new ArrayList<byte[]>(entries.size());
        for (var entry : entries) {
            var picture = entry.picture();
            if (picture.width() > 256 || picture.height() > 256) {
                throw new IllegalArgumentException("icon entries are at most 256x256 pixels, not "
                        + picture.width() + "x" + picture.height());
            }
            var png = switch (options.encoding()) {
                case PNG -> true;
                case BMP -> false;
                case AUTO -> picture.width() >= 256 || picture.height() >= 256;
            };
            images.add(png ? PngEncoder.encode(picture, PngEncoderOptions.defaults()) : bitmap(picture));
        }
        var header = new byte[6 + 16 * entries.size()];
        ByteArrays.put16le(header, 2, options.isCursor() ? 2 : 1);
        ByteArrays.put16le(header, 4, entries.size());
        var offset = (long) header.length;
        for (var i = 0; i < entries.size(); i++) {
            var entry = entries.get(i);
            var picture = entry.picture();
            var at = 6 + 16 * i;
            header[at] = (byte) (picture.width() == 256 ? 0 : picture.width());
            header[at + 1] = (byte) (picture.height() == 256 ? 0 : picture.height());
            if (entry.hotspot() != null) {
                ByteArrays.put16le(header, at + 4, Math.min(picture.width() - 1, entry.hotspot().x()));
                ByteArrays.put16le(header, at + 6, Math.min(picture.height() - 1, entry.hotspot().y()));
            } else {
                ByteArrays.put16le(header, at + 4, 1);
                ByteArrays.put16le(header, at + 6, 32);
            }
            ByteArrays.put32le(header, at + 8, images.get(i).length);
            ByteArrays.put32le(header, at + 12, offset);
            offset += images.get(i).length;
        }
        sink.write(header);
        for (var image : images) {
            sink.write(image);
        }
    }

    /**
     * Encodes an entry as a 32-bit bitmap with an AND mask.
     *
     * @param picture the pixels
     * @return the bitmap header and data
     */
    private static byte[] bitmap(RgbaPicture picture) {
        var width = picture.width();
        var height = picture.height();
        var maskRow = ((width + 31) / 32) * 4;
        var xorSize = width * 4 * height;
        var data = new byte[40 + xorSize + maskRow * height];
        ByteArrays.put32le(data, 0, 40);
        ByteArrays.put32le(data, 4, width);
        ByteArrays.put32le(data, 8, height * 2L);
        ByteArrays.put16le(data, 12, 1);
        ByteArrays.put16le(data, 14, 32);
        ByteArrays.put32le(data, 20, xorSize + (long) maskRow * height);
        var pixels = picture.pixels();
        var stride = picture.stride();
        var opaque = !picture.hasAlpha();
        for (var row = 0; row < height; row++) {
            var y = height - 1 - row;
            var out = 40 + row * width * 4;
            var mask = 40 + xorSize + row * maskRow;
            for (var x = 0; x < width; x++) {
                var at = y * stride + x * 4;
                var alpha = opaque ? (byte) 0xFF : pixels[at + 3];
                data[out] = pixels[at + 2];
                data[out + 1] = pixels[at + 1];
                data[out + 2] = pixels[at];
                data[out + 3] = alpha;
                out += 4;
                if (alpha == 0) {
                    data[mask + (x >>> 3)] |= (byte) (0x80 >>> (x & 7));
                }
            }
        }
        return data;
    }
}
