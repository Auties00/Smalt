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
     * Resolves a URI against a base.
     *
     * @param base the location the reference is relative to: a file such as {@code media/a.m3u8}, a directory
     * ending in a slash, or an absolute URL; empty for the first manifest
     * @param reference the URI
     * @return {@code reference} when it has a scheme or starts with a slash, otherwise the path it names next to
     * the last segment of {@code base}, with {@code .} and {@code ..} segments removed where they can be
     */
    public static String resolve(String base, String reference) {
        var colon = reference.indexOf(':');
        var slash = reference.indexOf('/');
        if (colon > 0 && (slash < 0 || colon < slash) || reference.startsWith("/")) {
            return reference;
        }
        var scheme = base.indexOf("://");
        var directory = base.substring(0, base.lastIndexOf('/') + 1);
        if (scheme >= 0 && directory.length() <= scheme + 3) {
            directory = base + "/";
        }
        var parts = new ArrayList<String>();
        var prefix = "";
        var path = directory + reference;
        if (scheme >= 0) {
            var root = path.indexOf('/', scheme + 3);
            prefix = root < 0 ? path : path.substring(0, root + 1);
            path = root < 0 ? "" : path.substring(root + 1);
        }
        for (var segment : path.split("/", -1)) {
            if (segment.equals(".")) {
                continue;
            }
            if (segment.equals("..") && !parts.isEmpty() && !parts.getLast().equals("..")) {
                parts.removeLast();
                continue;
            }
            parts.add(segment);
        }
        return prefix + String.join("/", parts);
    }
}
