package com.github.auties00.smalt.internal.tags;

import com.github.auties00.smalt.Media;
import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.container.AttachedPicture;
import com.github.auties00.smalt.image.ImageFormat;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.probe.ProbeOptions;

import java.util.Locale;

/**
 * Holds the helpers shared by the tag codecs: bounded reads, ASCII matching and the picture
 * facts that the tag formats store.
 */
public final class TagIo {
    private TagIo() {
        throw new AssertionError();
    }

    /**
     * Reads a range of a source into a new array after checking its size against the limits.
     *
     * @param source the input
     * @param position the offset of the first byte
     * @param length the number of bytes
     * @param limits the resource bounds
     * @return the bytes
     * @throws MediaException.LimitExceeded if {@code length} exceeds the allocation limit
     * @throws MediaException.Malformed if the source ends before the range does
     */
    public static byte[] read(MediaSource source, long position, long length, MediaLimits limits) {
        LimitChecks.checkAllocation(limits, length);
        if (position < 0 || position + length > source.size()) {
            throw new MediaException.Malformed("range " + position + "+" + length + " runs past the end of the "
                    + source.size() + " byte input");
        }
        var bytes = new byte[(int) length];
        source.readFully(position, bytes, 0, bytes.length);
        return bytes;
    }

    /**
     * Reads up to {@code length} bytes, fewer when the source ends first.
     *
     * @param source the input
     * @param position the offset of the first byte
     * @param length the largest number of bytes
     * @return the bytes read, possibly fewer than requested
     */
    public static byte[] readAvailable(MediaSource source, long position, int length) {
        var available = (int) Math.max(0, Math.min(length, source.size() - position));
        var bytes = new byte[available];
        if (available > 0) {
            source.readFully(position, bytes, 0, available);
        }
        return bytes;
    }

    /**
     * Returns whether bytes at an offset spell an ASCII string.
     *
     * @param data the bytes
     * @param offset the index of the first byte
     * @param text the expected characters
     * @return {@code true} on a match within the array
     */
    public static boolean matches(byte[] data, int offset, String text) {
        if (offset < 0 || offset + text.length() > data.length) {
            return false;
        }
        for (var i = 0; i < text.length(); i++) {
            if (data[offset + i] != (byte) text.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Describes the geometry of a picture for the FLAC {@code PICTURE} block.
     *
     * <p>The width, height and bits per pixel come from the picture's headers; a picture that
     * cannot be probed yields zeros, which readers accept.
     *
     * @param picture the picture to describe
     * @return width, height, bits per pixel and palette size, in this order
     */
    public static int[] geometry(AttachedPicture picture) {
        var data = picture.data();
        try {
            var info = Media.probe(MediaSource.of(data), ProbeOptions.headersOnly());
            var image = info.image();
            if (image.isEmpty()) {
                return new int[4];
            }
            var stream = image.get();
            var depth = stream.bitDepth().orElse(8);
            var channels = stream.format() == ImageFormat.GIF ? 1 : stream.hasAlpha() ? 4 : 3;
            return new int[]{stream.width(), stream.height(), depth * channels, 0};
        } catch (MediaException | IllegalArgumentException exception) {
            return new int[4];
        }
    }

    /**
     * Returns the media type of a picture, sniffing it from the data when the picture names none.
     *
     * @param picture the picture to describe
     * @return the media type, possibly empty when unknown
     */
    public static String mediaType(AttachedPicture picture) {
        if (!picture.mediaType().isEmpty()) {
            return picture.mediaType();
        }
        return picture.format().map(ImageFormat::mimeType).orElse("");
    }

    /**
     * Returns the usual file extension of a picture.
     *
     * @param picture the picture to describe
     * @return the extension without dot, such as {@code "jpg"}
     */
    public static String extension(AttachedPicture picture) {
        var format = picture.format();
        if (format.isPresent()) {
            return switch (format.get()) {
                case JPEG -> "jpg";
                default -> format.get().name().toLowerCase(Locale.ROOT);
            };
        }
        var type = mediaType(picture);
        var slash = type.indexOf('/');
        return slash >= 0 && slash + 1 < type.length() ? type.substring(slash + 1) : "bin";
    }
}
