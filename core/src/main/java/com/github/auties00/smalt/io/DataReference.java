package com.github.auties00.smalt.io;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * A reference from a media file to another file holding some of its data: an entry of the data reference box
 * ({@code dref}) of an ISO base media or QuickTime file that is not the file itself (ISO/IEC 14496-12 clause
 * 8.7.2, the QuickTime File Format's data reference atoms), or a Matroska segment that a linked segment plays
 * (RFC 9559 section 17).
 *
 * <p>The samples of a track, or the extents of a HEIF item, that use such an entry lie at their offsets in the
 * referenced file; a {@link DataReferenceResolver} supplies that file. A Matroska segment reference names the
 * segment by its {@code SegmentUUID}, wherever its file is.
 *
 * @param kind the kind of the entry
 * @param location the location: the URL of a {@code url } entry, the location of a {@code urn } entry, the
 * path an alias records (the POSIX path when it has one, otherwise its Mac OS path with {@code :} separators), or
 * the segment UUID of a Matroska reference as 32 lowercase hexadecimal digits; empty when the entry states none
 * @param name the name of a {@code urn } entry, the file name of an alias, or the file name a Matroska segment
 * records for the referenced segment ({@code PrevFilename} or {@code NextFilename}); empty for a {@code url } entry
 * or when the entry states none
 */
public record DataReference(Kind kind, String location, String name) {
    /**
     * The kinds of data reference entries.
     */
    public enum Kind {
        /**
         * A {@code url } entry, whose location is a URL, usually relative to the referencing file.
         */
        URL,
        /**
         * A {@code urn } entry: a name, and optionally a location.
         */
        URN,
        /**
         * A QuickTime {@code alis} entry: a Mac OS alias record, which names the file and the path it was at.
         */
        ALIAS,
        /**
         * The Matroska segment whose {@code SegmentUUID} is the location: the segment an ordered chapter plays, or
         * the segment before or after the referencing one in a hard-linked chain ({@code PrevUUID},
         * {@code NextUUID}).
         */
        MATROSKA_SEGMENT,
        /**
         * The Matroska segment whose {@code PrevUUID} is the location, which follows that segment in a hard-linked
         * chain whose segments name only the segment before them.
         */
        MATROSKA_NEXT_SEGMENT
    }

    /**
     * Constructs a data reference.
     *
     * @param kind the kind of the entry
     * @param location the location, empty when the entry states none
     * @param name the name, empty when the entry states none
     */
    public DataReference {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(name, "name");
    }

    /**
     * Returns the name of the referenced file without its directories: the file name of an alias or of a Matroska
     * segment, or else the last segment of the location, with the {@code %XX} escapes of a URL decoded as UTF-8.
     *
     * @return the file name, empty when the reference names no file
     */
    public String fileName() {
        if (kind == Kind.MATROSKA_SEGMENT || kind == Kind.MATROSKA_NEXT_SEGMENT) {
            var slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
            return name.substring(slash + 1);
        }
        if (kind == Kind.ALIAS && !name.isEmpty()) {
            return name;
        }
        var path = kind == Kind.ALIAS ? location : decodedPath();
        var end = path.length();
        var slash = Math.max(path.lastIndexOf('/', end - 1), kind == Kind.ALIAS ? path.lastIndexOf(':', end - 1) : -1);
        return path.substring(slash + 1, end);
    }

    /**
     * Returns the path of a URL location, without its query or fragment, with its {@code %XX} escapes decoded as
     * UTF-8; a malformed escape is kept as it is.
     *
     * @return the decoded path
     */
    String decodedPath() {
        var path = location;
        var cut = path.length();
        for (var index = 0; index < path.length(); index++) {
            var c = path.charAt(index);
            if (c == '?' || c == '#') {
                cut = index;
                break;
            }
        }
        path = path.substring(0, cut);
        if (path.indexOf('%') < 0) {
            return path;
        }
        var out = new ByteArrayOutputStream(path.length());
        for (var index = 0; index < path.length(); index++) {
            var c = path.charAt(index);
            if (c == '%' && index + 2 < path.length() && Character.digit(path.charAt(index + 1), 16) >= 0
                    && Character.digit(path.charAt(index + 2), 16) >= 0) {
                out.write(Character.digit(path.charAt(index + 1), 16) * 16 + Character.digit(path.charAt(index + 2), 16));
                index += 2;
            } else {
                var codePoint = path.codePointAt(index);
                var bytes = Character.toString(codePoint).getBytes(StandardCharsets.UTF_8);
                out.write(bytes, 0, bytes.length);
                index += Character.charCount(codePoint) - 1;
            }
        }
        return out.toString(StandardCharsets.UTF_8);
    }
}
