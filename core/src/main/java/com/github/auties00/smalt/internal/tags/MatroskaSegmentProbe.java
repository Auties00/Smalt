package com.github.auties00.smalt.internal.tags;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.internal.io.SourceBytes;
import com.github.auties00.smalt.io.MediaSource;

import java.util.Arrays;
import java.util.HexFormat;

/**
 * Reads the identifiers of the first segment of a Matroska file without demuxing it: its {@code SegmentUUID} and the
 * {@code PrevUUID} of its {@code Info}, which a resolver of linked segments matches against.
 *
 * <p>Only the element headers before the {@code Info} are read, up to the first {@code Cluster}; a file that is not
 * Matroska, or whose {@code Info} lies past the first clusters, has no identifiers.
 */
public final class MatroskaSegmentProbe {
    /**
     * The largest number of top-level elements walked before giving up.
     */
    private static final int MAX_ELEMENTS = 256;

    /**
     * The largest {@code Info} element read.
     */
    private static final int MAX_INFO = 1 << 20;

    /**
     * The largest {@code EBML} header read.
     */
    private static final int MAX_HEADER = 4096;

    private MatroskaSegmentProbe() {
        throw new AssertionError();
    }

    /**
     * Holds the identifiers of a segment, each as 32 lowercase hexadecimal digits.
     *
     * @param segmentUuid the {@code SegmentUUID}, or {@code null} when absent
     * @param prevUuid the {@code PrevUUID}, or {@code null} when absent
     */
    public record Identifiers(String segmentUuid, String prevUuid) {
    }

    /**
     * Reads the identifiers of the first segment of a source.
     *
     * @param source the source, not closed
     * @return the identifiers, or {@code null} when the source is not a Matroska file or its {@code Info} cannot be
     * found
     */
    public static Identifiers probe(MediaSource source) {
        try {
            var size = source.size();
            var head = read(source, 0, 16);
            var header = Ebml.header(head, 0, head.length);
            if (header == null || header.id() != EbmlIds.EBML || header.size() == Ebml.UNKNOWN
                    || header.size() > MAX_HEADER) {
                return null;
            }
            var position = header.length() + header.size();
            for (var count = 0; count < MAX_ELEMENTS; count++) {
                var bytes = read(source, position, 16);
                var element = Ebml.header(bytes, 0, bytes.length);
                if (element == null) {
                    return null;
                }
                if (element.id() == EbmlIds.SEGMENT) {
                    position += element.length();
                    break;
                }
                if (element.size() == Ebml.UNKNOWN) {
                    return null;
                }
                position += element.length() + element.size();
                if (position < 0 || size >= 0 && position >= size) {
                    return null;
                }
            }
            for (var count = 0; count < MAX_ELEMENTS; count++) {
                if (position < 0 || size >= 0 && position >= size) {
                    return null;
                }
                var bytes = read(source, position, 16);
                var element = Ebml.header(bytes, 0, bytes.length);
                if (element == null || element.id() == EbmlIds.CLUSTER || element.size() == Ebml.UNKNOWN) {
                    return null;
                }
                if (element.id() == EbmlIds.INFO) {
                    if (element.size() > MAX_INFO) {
                        return null;
                    }
                    var info = read(source, position + element.length(), (int) element.size());
                    if (info.length < element.size()) {
                        return null;
                    }
                    return identifiers(info);
                }
                position += element.length() + element.size();
            }
            return null;
        } catch (MediaException exception) {
            return null;
        }
    }

    /**
     * Reads the identifiers of an {@code Info} element.
     *
     * @param info the element data
     * @return the identifiers
     */
    private static Identifiers identifiers(byte[] info) {
        String segment = null;
        String previous = null;
        for (var child : Ebml.children(info, 0, info.length)) {
            if (child.size() != 16) {
                continue;
            }
            var hex = HexFormat.of().formatHex(info, child.data(), child.end());
            if (child.id() == EbmlIds.SEGMENT_UUID && segment == null) {
                segment = hex;
            } else if (child.id() == EbmlIds.PREV_UUID && previous == null) {
                previous = hex;
            }
        }
        return new Identifiers(segment, previous);
    }

    /**
     * Reads bytes of a source, fewer at its end.
     *
     * @param source the source to read
     * @param position the offset of the first byte
     * @param length the number of bytes wanted
     * @return the bytes read
     */
    private static byte[] read(MediaSource source, long position, int length) {
        var bytes = new byte[length];
        var read = SourceBytes.readAvailable(source, position, bytes, 0, length);
        return read == length ? bytes : Arrays.copyOf(bytes, Math.max(read, 0));
    }
}
