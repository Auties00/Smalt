package com.github.auties00.smalt.metadata;

import java.util.Objects;

/**
 * Names one XMP property or structure field and holds its value.
 *
 * @param namespace the namespace URI, such as {@link XmpPacket#DC}
 * @param name the local name, such as {@code "title"}
 * @param value the value of the property
 */
public record XmpProperty(String namespace, String name, XmpValue value) {
    /**
     * Validates the property.
     *
     * @throws IllegalArgumentException if the namespace or the name is empty, or the name is not an XML name
     */
    public XmpProperty {
        Objects.requireNonNull(namespace, "namespace");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(value, "value");
        if (namespace.isEmpty()) {
            throw new IllegalArgumentException("an XMP property needs a namespace");
        }
        if (!isName(name)) {
            throw new IllegalArgumentException("\"" + name + "\" is not a valid XMP property name");
        }
    }

    /**
     * Returns whether a string is a valid XML local name: a letter or underscore followed by
     * letters, digits, underscores, hyphens and dots.
     *
     * @param name the candidate
     * @return {@code true} when valid
     */
    static boolean isName(String name) {
        if (name.isEmpty()) {
            return false;
        }
        var first = name.charAt(0);
        if (!Character.isLetter(first) && first != '_') {
            return false;
        }
        for (var i = 1; i < name.length(); i++) {
            var c = name.charAt(i);
            if (!Character.isLetterOrDigit(c) && c != '_' && c != '-' && c != '.') {
                return false;
            }
        }
        return true;
    }
}
