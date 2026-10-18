package com.github.auties00.smalt.internal.heif;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Describes one item of a HEIF file: its type from {@code infe}, its location from
 * {@code iloc}, its properties from {@code ipma} and the references it makes from {@code iref}.
 *
 * <p>Items are created by {@link HeifFile#parse} only for the entries of the {@code iinf} box;
 * locations, associations and references that name other identifiers are ignored. Items are not
 * modified after parsing; instances are immutable and thread-safe.
 */
public final class HeifItem {
    /**
     * The item identifier, never {@code 0}.
     */
    private final int id;

    /**
     * The four-character item type, for example {@code "av01"}, {@code "grid"} or
     * {@code "Exif"}.
     */
    private final String type;

    /**
     * The MIME content type of a {@code mime} item, or the empty string.
     */
    private final String contentType;

    /**
     * Whether the {@code iloc} box located the item.
     */
    boolean located;

    /**
     * The {@code iloc} construction method: {@code 0} for file offsets, {@code 1} for offsets in
     * the {@code idat} box, {@code 2} for offsets in the data of another item.
     */
    int constructionMethod;

    /**
     * The {@code iloc} data reference index; {@code 0} means this file.
     */
    int dataReferenceIndex;

    /**
     * The offset of each extent, the base offset included.
     */
    long[] extentOffsets = new long[0];

    /**
     * The length of each extent.
     */
    long[] extentLengths = new long[0];

    /**
     * The one-based {@code iloc} item reference index of each extent, used by construction
     * method {@code 2}.
     */
    int[] extentReferences = new int[0];

    /**
     * Whether an {@code ipma} entry associated properties with the item.
     */
    boolean associated;

    /**
     * The associated properties, in association order.
     */
    final List<HeifProperty> properties = new ArrayList<>();

    /**
     * The indices in {@link #properties} of the associations marked essential.
     */
    final BitSet essential = new BitSet();

    /**
     * The identifiers this item references, by reference type, in file order.
     */
    final Map<String, int[]> references = new LinkedHashMap<>();

    /**
     * Constructs an item from its {@code infe} entry.
     *
     * @param id the item identifier
     * @param type the item type
     * @param contentType the MIME content type, or the empty string
     */
    HeifItem(int id, String type, String contentType) {
        this.id = id;
        this.type = type;
        this.contentType = contentType;
    }

    /**
     * Returns the item identifier.
     *
     * @return the identifier, never {@code 0}
     */
    public int id() {
        return id;
    }

    /**
     * Returns the item type.
     *
     * @return the four-character type, for example {@code "av01"}
     */
    public String type() {
        return type;
    }

    /**
     * Returns the MIME content type of a {@code mime} item.
     *
     * @return the content type, or the empty string for other items
     */
    public String contentType() {
        return contentType;
    }

    /**
     * Returns whether the item has a location.
     *
     * @return {@code true} when the {@code iloc} box lists the item
     */
    public boolean isLocated() {
        return located;
    }

    /**
     * Returns the total size of the item's data.
     *
     * @return the sum of the extent lengths, saturated at {@link Long#MAX_VALUE}
     */
    public long size() {
        var total = 0L;
        for (var length : extentLengths) {
            total += length;
            if (total < 0) {
                return Long.MAX_VALUE;
            }
        }
        return total;
    }

    /**
     * Returns the associated properties in association order.
     *
     * @return the properties, unmodifiable
     */
    public List<HeifProperty> properties() {
        return List.copyOf(properties);
    }

    /**
     * Returns the first associated property of a kind.
     *
     * @param kind the property class
     * @param <T> the property type
     * @return the property, or {@code null} when none is associated
     */
    public <T extends HeifProperty> T property(Class<T> kind) {
        for (var property : properties) {
            if (kind.isInstance(property)) {
                return kind.cast(property);
            }
        }
        return null;
    }

    /**
     * Returns the type of the first essential property whose type a reader does not support.
     *
     * @param supported the property types the reader implements
     * @return the type, or {@code null} when every essential property is supported
     */
    public String unsupportedEssential(Set<String> supported) {
        for (var i = 0; i < properties.size(); i++) {
            var property = properties.get(i);
            if (essential.get(i) && (property instanceof HeifProperty.Unknown || !supported.contains(property.type()))) {
                return property.type();
            }
        }
        return null;
    }

    /**
     * Returns the identifiers this item references with one reference type.
     *
     * @param referenceType the four-character reference type, for example {@code "dimg"}
     * @return the identifiers in file order, empty when there is none; the array is a copy
     */
    public int[] references(String referenceType) {
        var targets = references.get(referenceType);
        return targets == null ? new int[0] : targets.clone();
    }

    /**
     * Returns whether this item references another with one reference type.
     *
     * @param referenceType the four-character reference type
     * @param target the referenced identifier
     * @return {@code true} when the reference exists
     */
    public boolean references(String referenceType, int target) {
        var targets = references.get(referenceType);
        if (targets != null) {
            for (var value : targets) {
                if (value == target) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Appends referenced identifiers of one type.
     *
     * @param referenceType the reference type
     * @param targets the identifiers to append
     */
    void addReferences(String referenceType, int[] targets) {
        var existing = references.get(referenceType);
        if (existing == null) {
            references.put(referenceType, targets);
        } else {
            var merged = Arrays.copyOf(existing, existing.length + targets.length);
            System.arraycopy(targets, 0, merged, existing.length, targets.length);
            references.put(referenceType, merged);
        }
    }

    @Override
    public String toString() {
        return "HeifItem[id=" + id + ", type=" + type + ", size=" + size() + "]";
    }
}
