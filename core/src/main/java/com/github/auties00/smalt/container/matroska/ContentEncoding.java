package com.github.auties00.smalt.container.matroska;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;

import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.internal.tags.EbmlIds;
import java.util.Arrays;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * Describes one compression step of a track's {@code ContentEncodings}, per RFC 9559 clause
 * 5.1.4.1.31, and undoes it.
 *
 * <p>Header stripping ({@code ContentCompAlgo} 3) prepends the stripped bytes to every frame;
 * zlib ({@code ContentCompAlgo} 0) inflates every frame, bounded by the allocation limit. Other
 * algorithms and every encryption raise {@link MediaException.Unsupported}.
 *
 * @param order the {@code ContentEncodingOrder}; encodings are undone from the highest order down
 * @param scope the {@code ContentEncodingScope} bit mask: 1 for frames, 2 for the codec private data
 * @param algorithm the {@code ContentCompAlgo}
 * @param settings the {@code ContentCompSettings}, the stripped bytes for header stripping
 */
record ContentEncoding(long order, long scope, long algorithm, byte[] settings) {
    /**
     * The zlib compression algorithm.
     */
    static final int ZLIB = 0;

    /**
     * The header stripping compression algorithm.
     */
    static final int HEADER_STRIPPING = 3;

    /**
     * The scope bit applying the encoding to frames.
     */
    static final int SCOPE_FRAMES = 1;

    /**
     * The scope bit applying the encoding to the codec private data.
     */
    static final int SCOPE_PRIVATE = 2;

    /**
     * Parses a {@code ContentEncoding} element.
     *
     * @param encoding the element data
     * @return the encoding
     * @throws MediaException.Unsupported if the encoding encrypts or uses an unsupported compression algorithm
     */
    static ContentEncoding parse(EbmlCursor encoding) {
        var order = 0L;
        var scope = 1L;
        var type = 0L;
        var algorithm = 0L;
        var settings = new byte[0];
        var compression = false;
        while (encoding.hasMore()) {
            var element = encoding.next();
            var body = element.payload();
            switch ((int) element.id()) {
                case EbmlIds.CONTENT_ENCODING_ORDER -> order = body.unsigned();
                case EbmlIds.CONTENT_ENCODING_SCOPE -> scope = body.unsigned();
                case EbmlIds.CONTENT_ENCODING_TYPE -> type = body.unsigned();
                case EbmlIds.CONTENT_COMPRESSION -> {
                    compression = true;
                    while (body.hasMore()) {
                        var child = body.next();
                        if (child.id() == EbmlIds.CONTENT_COMP_ALGO) {
                            algorithm = child.payload().unsigned();
                        } else if (child.id() == EbmlIds.CONTENT_COMP_SETTINGS) {
                            settings = child.payload().bytes();
                        }
                    }
                }
                case EbmlIds.CONTENT_ENCRYPTION -> type = 1;
                default -> {
                }
            }
        }
        if (type != 0) {
            throw new MediaException.Unsupported("matroska encryption", "the track is encrypted (ContentEncodingType " + type + ")");
        }
        if (!compression) {
            algorithm = ZLIB;
        }
        if (algorithm != ZLIB && algorithm != HEADER_STRIPPING) {
            throw new MediaException.Unsupported("matroska compression " + algorithm,
                    "ContentCompAlgo " + algorithm + " (bzlib or lzo) is not supported");
        }
        return new ContentEncoding(order, scope, algorithm, settings);
    }

    /**
     * {@return whether this encoding applies to frames}
     */
    boolean appliesToFrames() {
        return (scope & SCOPE_FRAMES) != 0;
    }

    /**
     * {@return whether this encoding applies to the codec private data}
     */
    boolean appliesToPrivate() {
        return (scope & SCOPE_PRIVATE) != 0;
    }

    /**
     * Undoes this encoding on one frame or on the codec private data.
     *
     * @param data the encoded bytes
     * @param offset the index of the first encoded byte
     * @param length the number of encoded bytes
     * @param limits the limits bounding the decoded size
     * @return the decoded bytes
     * @throws MediaException.Malformed if the zlib stream is corrupt or truncated
     * @throws MediaException.LimitExceeded if the decoded size exceeds the allocation limit
     */
    byte[] decode(byte[] data, int offset, int length, MediaLimits limits) {
        if (algorithm == HEADER_STRIPPING) {
            LimitChecks.checkAllocation(limits, (long) settings.length + length);
            var result = new byte[settings.length + length];
            System.arraycopy(settings, 0, result, 0, settings.length);
            System.arraycopy(data, offset, result, settings.length, length);
            return result;
        }
        return inflate(data, offset, length, limits);
    }

    /**
     * Inflates a zlib stream.
     *
     * @param data the compressed bytes
     * @param offset the index of the first compressed byte
     * @param length the number of compressed bytes
     * @param limits the limits bounding the inflated size
     * @return the inflated bytes
     */
    private static byte[] inflate(byte[] data, int offset, int length, MediaLimits limits) {
        var inflater = new Inflater();
        try {
            inflater.setInput(data, offset, length);
            var output = new byte[(int) Math.max(64, Math.min(length * 4L, 1 << 20))];
            var size = 0;
            while (!inflater.finished()) {
                if (size == output.length) {
                    var grown = Math.min((long) output.length * 2, Math.min(limits.maxBytes(), Integer.MAX_VALUE - 16L));
                    if (grown <= output.length) {
                        throw new MediaException.LimitExceeded("inflated frame exceeds the " + limits.maxBytes() + " byte limit");
                    }
                    output = Arrays.copyOf(output, (int) grown);
                }
                var produced = inflater.inflate(output, size, output.length - size);
                size += produced;
                if (produced == 0 && (inflater.needsInput() || inflater.needsDictionary())) {
                    throw new MediaException.Malformed("zlib-compressed frame is truncated");
                }
            }
            return Arrays.copyOf(output, size);
        } catch (DataFormatException exception) {
            throw new MediaException.Malformed("corrupt zlib-compressed frame", exception);
        } finally {
            inflater.end();
        }
    }
}
