package com.github.auties00.smalt.container.internal;

import java.util.ArrayList;

/**
 * Resolves the URIs of adaptive streaming manifests against the manifest or base URL that names them.
 */
public final class Uris {
    private Uris() {
        throw new AssertionError();
    }

    /**
     * Resolves a URI against a base, as RFC 3986 clause 5.2 does for the references manifests hold.
     *
     * <p>A reference with a scheme is returned as it is. Against a base with a scheme and an authority, such as
     * {@code https://cdn.example/v/index.m3u8}, a network-path reference ({@code //host/a.ts}) takes the base's
     * scheme and an absolute-path reference ({@code /a.ts}) its scheme and authority; against a relative base they
     * are returned as they are. Any other reference names the path next to the last segment of the base, the base's
     * query and fragment left out, and a reference that is only a query ({@code ?token=1}) replaces the base's
     * query. Dot segments ({@code .} and {@code ..}) are removed from the path where they can be, never from a
     * query or fragment.
     *
     * @param base the location the reference is relative to: a file such as {@code media/a.m3u8}, a directory
     * ending in a slash, or an absolute URL; empty for the first manifest
     * @param reference the URI
     * @return the resolved URI
     */
    public static String resolve(String base, String reference) {
        var colon = reference.indexOf(':');
        var delimiter = firstOf(reference, "/?#");
        if (colon > 0 && (delimiter < 0 || colon < delimiter)) {
            return reference;
        }
        var scheme = base.indexOf("://");
        var hasScheme = scheme > 0 && firstOf(base.substring(0, scheme), "/?#") < 0;
        var baseEnd = firstOf(base, "?#");
        var basePath = baseEnd < 0 ? base : base.substring(0, baseEnd);
        var authorityEnd = !hasScheme ? 0 : basePath.indexOf('/', scheme + 3) < 0 ? basePath.length()
                : basePath.indexOf('/', scheme + 3);
        if (reference.startsWith("//")) {
            return hasScheme ? base.substring(0, scheme + 1) + reference : reference;
        }
        if (reference.startsWith("/")) {
            return hasScheme ? basePath.substring(0, authorityEnd) + normalize(reference) : reference;
        }
        if (reference.startsWith("?")) {
            return basePath + reference;
        }
        var prefix = basePath.substring(0, authorityEnd);
        var directoryPath = basePath.substring(authorityEnd);
        var directory = directoryPath.substring(0, directoryPath.lastIndexOf('/') + 1);
        if (hasScheme && directory.isEmpty()) {
            directory = "/";
        }
        return prefix + normalize(directory + reference);
    }

    /**
     * Removes the dot segments of a path, leaving its query and fragment as they are.
     *
     * @param uri a path with an optional query and fragment
     * @return the path without the dot segments it can lose; {@code ..} segments above a relative path's start
     * are kept
     */
    private static String normalize(String uri) {
        var end = firstOf(uri, "?#");
        var path = end < 0 ? uri : uri.substring(0, end);
        var suffix = end < 0 ? "" : uri.substring(end);
        var absolute = path.startsWith("/");
        var parts = new ArrayList<String>();
        var segments = (absolute ? path.substring(1) : path).split("/", -1);
        for (var index = 0; index < segments.length; index++) {
            var segment = segments[index];
            var last = index == segments.length - 1;
            if (segment.equals(".")) {
                if (last) {
                    parts.add("");
                }
                continue;
            }
            if (segment.equals("..") && (absolute || !parts.isEmpty() && !parts.getLast().equals(".."))) {
                if (!parts.isEmpty()) {
                    parts.removeLast();
                }
                if (last) {
                    parts.add("");
                }
                continue;
            }
            parts.add(segment);
        }
        return (absolute ? "/" : "") + String.join("/", parts) + suffix;
    }

    /**
     * Returns the index of the first of some characters in a string.
     *
     * @param text the string to search
     * @param characters the characters to find
     * @return the index, or -1 when none occurs
     */
    private static int firstOf(String text, String characters) {
        for (var index = 0; index < text.length(); index++) {
            if (characters.indexOf(text.charAt(index)) >= 0) {
                return index;
            }
        }
        return -1;
    }
}
