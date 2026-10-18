package com.github.auties00.smalt.image.metadata;

import com.github.auties00.smalt.metadata.XmpPacket;
import com.github.auties00.smalt.metadata.XmpProperty;

import java.util.Locale;
import java.util.Set;

/**
 * Recognizes the XMP properties that record where a picture was taken.
 */
final class Locations {
    /**
     * The Photoshop properties that name a place.
     */
    private static final Set<String> PHOTOSHOP = Set.of("City", "State", "Country");

    /**
     * The IPTC Core properties that name a place.
     */
    private static final Set<String> IPTC_CORE = Set.of("Location", "CountryCode");

    /**
     * The IPTC Extension properties that name a place.
     */
    private static final Set<String> IPTC_EXTENSION = Set.of("LocationCreated", "LocationShown");

    /**
     * Prevents instantiation.
     */
    private Locations() {
        throw new AssertionError();
    }

    /**
     * Returns whether a property records a location.
     *
     * @param property the top-level property
     * @return {@code true} for the {@code exif:GPS*} coordinates and the place names of the
     *         Photoshop and IPTC schemas
     */
    static boolean isLocation(XmpProperty property) {
        var namespace = property.namespace();
        var name = property.name();
        if (namespace.equals(XmpPacket.EXIF) || namespace.equals(XmpPacket.EXIF_EX)) {
            return name.toUpperCase(Locale.ROOT).startsWith("GPS");
        }
        if (namespace.equals(XmpPacket.PHOTOSHOP)) {
            return PHOTOSHOP.contains(name);
        }
        if (namespace.equals(XmpPacket.IPTC_CORE)) {
            return IPTC_CORE.contains(name);
        }
        if (namespace.equals(XmpPacket.IPTC_EXTENSION)) {
            return IPTC_EXTENSION.contains(name);
        }
        return false;
    }
}
