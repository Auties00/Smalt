package com.github.auties00.smalt.internal.codec;

import com.github.auties00.smalt.metadata.XmpPacket;
import com.github.auties00.smalt.metadata.XmpProperty;

import java.util.Locale;
import java.util.Set;

/**
 * Recognizes the XMP properties that record where a picture was taken, for the metadata editor and the
 * image transcode, which remove them.
 *
 * <p>This class is thread-safe.
 */
public final class XmpLocations {
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
    private XmpLocations() {
        throw new AssertionError();
    }

    /**
     * Returns whether a property records a location.
     *
     * @param property the top-level property
     * @return {@code true} for the {@code GPS} properties of the EXIF namespaces and the place names of the
     * Photoshop ({@code City}, {@code State}, {@code Country}) and IPTC ({@code Location},
     * {@code CountryCode}, {@code LocationCreated}, {@code LocationShown}) schemas
     */
    public static boolean isLocation(XmpProperty property) {
        var namespace = property.namespace();
        var name = property.name();
        return switch (namespace) {
            case XmpPacket.EXIF, XmpPacket.EXIF_EX -> name.toUpperCase(Locale.ROOT).startsWith("GPS");
            case XmpPacket.PHOTOSHOP -> PHOTOSHOP.contains(name);
            case XmpPacket.IPTC_CORE -> IPTC_CORE.contains(name);
            case XmpPacket.IPTC_EXTENSION -> IPTC_EXTENSION.contains(name);
            default -> false;
        };
    }
}
