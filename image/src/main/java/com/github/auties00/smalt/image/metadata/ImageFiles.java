package com.github.auties00.smalt.image.metadata;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.ImageFormat;
import com.github.auties00.smalt.image.Images;
import com.github.auties00.smalt.internal.limits.Guards;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.io.MediaSource;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * Opens the {@link ImageFile} that matches the format of a source, and holds the helpers the
 * format handlers share.
 */
final class ImageFiles {
    /**
     * Prevents instantiation.
     */
    private ImageFiles() {
        throw new AssertionError();
    }

    /**
     * Detects the format of a source and parses its structure.
     *
     * @param source the image file
     * @param limits the resource bounds
     * @return the image file
     * @throws MediaException.Unsupported if the format is not recognized
     * @throws MediaException.Malformed if the structure is corrupt
     */
    static ImageFile open(MediaSource source, MediaLimits limits) {
        var header = readAvailable(source, 0, ImageFormat.DETECTION_LENGTH);
        var format = Images.detect(source)
                .orElseThrow(() -> new MediaException.Unsupported("unknown", "the input is not a recognized image"));
        return switch (format) {
            case JPEG -> new JpegFile(source, limits);
            case PNG -> new PngFile(source, limits);
            case WEBP -> new WebpFile(source, limits);
            case GIF -> new GifFile(source, limits);
            case HEIC, AVIF -> new HeifImageFile(source, limits, format);
            case TIFF -> new TiffFile(source, limits);
            case DNG -> new TiffFile(source, limits, ImageFormat.DNG, null);
            case RAW -> RawImageFile.open(source, limits, header);
            case BMP -> new BmpFile(source, limits);
            case JXL -> new JxlFile(source, limits);
            default -> throw new MediaException.Unsupported(format.name().toLowerCase(Locale.ROOT),
                    "metadata of " + format.name().toLowerCase(Locale.ROOT) + " files cannot be read");
        };
    }

    /**
     * Runs an operation on an image file under the decoder policy of {@link Guards}: runtime
     * failures caused by corrupt input become {@link MediaException.Malformed}, exhausted memory or
     * stack {@link MediaException.LimitExceeded}.
     *
     * @param operation the work to run
     * @param <T> the result type
     * @return the result
     */
    static <T> T guard(Supplier<T> operation) {
        return Guards.malformed("image structure", operation);
    }

    /**
     * Reads a range into a new array after checking its size against the limits.
     *
     * @param source the image file
     * @param position the offset of the first byte
     * @param length the number of bytes
     * @param limits the resource bounds
     * @return the bytes
     * @throws MediaException.LimitExceeded if the length exceeds the allocation limit
     * @throws MediaException.Malformed if the source ends before the range does
     */
    static byte[] read(MediaSource source, long position, long length, MediaLimits limits) {
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
     * @param source the image file
     * @param position the offset of the first byte
     * @param length the largest number of bytes
     * @return the bytes read
     */
    static byte[] readAvailable(MediaSource source, long position, int length) {
        var available = (int) Math.max(0, Math.min(length, source.size() - position));
        var bytes = new byte[available];
        if (available > 0) {
            source.readFully(position, bytes, 0, available);
        }
        return bytes;
    }

    /**
     * Decodes text that may be UTF-8 or ISO-8859-1, dropping trailing NUL characters.
     *
     * @param data the bytes
     * @param offset the index of the first byte
     * @param length the number of bytes
     * @return the text, UTF-8 when the bytes are valid UTF-8
     */
    static String text(byte[] data, int offset, int length) {
        var end = offset + length;
        while (end > offset && data[end - 1] == 0) {
            end--;
        }
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(data, offset, end - offset)).toString();
        } catch (CharacterCodingException exception) {
            return new String(data, offset, end - offset, StandardCharsets.ISO_8859_1);
        }
    }

    /**
     * Raises the failure of an edit a format cannot store.
     *
     * @param format the detected image format
     * @param what what cannot be stored
     * @return never returns
     * @throws MediaException.Unsupported always
     */
    static MediaException.Unsupported unsupported(ImageFormat format, String what) {
        var name = format.name().toLowerCase(Locale.ROOT);
        return new MediaException.Unsupported(name, name + " files cannot store " + what);
    }
}
