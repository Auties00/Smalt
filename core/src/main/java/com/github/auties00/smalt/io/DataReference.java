package com.github.auties00.smalt.io;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * A reference from a media file to another file holding some of its data: an entry of the data reference box
 * ({@code dref}) of an ISO base media or QuickTime file that is not the file itself (ISO/IEC 14496-12 clause
 * 8.7.2, the QuickTime File Format's data reference atoms).
 *
 * <p>The samples of a track, or the extents of a HEIF item, that use such an entry lie at their offsets in the
 * referenced file; a {@link DataReferenceResolver} supplies that file.
 *
 * @param kind the kind of the entry
 * @param location the location: the URL of a {@code url } entry, the location of a {@code urn } entry, or the
 * path an alias records (the POSIX path when it has one, otherwise its Mac OS path with {@code :} separators);
 * empty when the entry states none
 * @param name the name of a {@code urn } entry or the file name of an alias, empty for a {@code url } entry or
 * when the entry states none
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
        ALIAS
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
     * Returns the name of the referenced file without its directories: the file name of an alias, or else the
     * last segment of the location, with the {@code %XX} escapes of a URL decoded as UTF-8.
     *
     * @return the file name, empty when the reference names no file
     */
    public String fileName() {
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
