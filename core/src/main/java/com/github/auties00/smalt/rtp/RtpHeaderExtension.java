package com.github.auties00.smalt.rtp;

import com.github.auties00.smalt.MediaException;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Holds the header extension of an RTP packet (RFC 3550 section 5.3.1): a 16-bit profile-defined value and data
 * whose length is a multiple of four bytes.
 *
 * <p>The general mechanism of RFC 8285 packs elements, each with a local identifier, into the data: the one-byte
 * form (profile {@code 0xBEDE}, identifiers 1 to 14, element sizes 1 to 16) and the two-byte form (profiles
 * {@code 0x1000} to {@code 0x100F}, identifiers 1 to 255, element sizes 0 to 255). {@link #of(Map)} builds such an
 * extension and {@link #elements()} reads one.
 *
 * <p>The data array is shared, not copied. Equality compares the profile and the data contents.
 *
 * <p>Instances are immutable and thread-safe, provided the data is not modified.
 *
 * @param profile the profile-defined 16-bit value, from 0 to 65535
 * @param data the extension data, a multiple of 4 bytes and at most {@code 4 * 65535} bytes
 */
public record RtpHeaderExtension(int profile, byte[] data) {
    /**
     * The profile value of the RFC 8285 one-byte header form.
     */
    public static final int ONE_BYTE_PROFILE = 0xBEDE;

    /**
     * The profile value of the RFC 8285 two-byte header form, whose low 4 bits are application bits.
     */
    public static final int TWO_BYTE_PROFILE = 0x1000;

    /**
     * Validates the fields.
     *
     * @throws IllegalArgumentException if {@code profile} is outside its range or the data length is not a multiple
     * of 4 or is too large
     */
    public RtpHeaderExtension {
        Objects.requireNonNull(data, "data");
        if (profile < 0 || profile > 0xFFFF) {
            throw new IllegalArgumentException("extension profile " + profile + " is not between 0 and 65535");
        }
        if ((data.length & 3) != 0 || data.length > 4 * 0xFFFF) {
            throw new IllegalArgumentException("extension data of " + data.length
                    + " bytes is not a multiple of 4 below 262144");
        }
    }

    /**
     * Returns an RFC 8285 extension holding elements, in the one-byte form when every identifier is from 1 to 14
     * and every element from 1 to 16 bytes, otherwise in the two-byte form.
     *
     * @param elements the element data by identifier, in the order to write them; the data arrays are copied
     * @return the extension, zero-padded to a multiple of four bytes
     * @throws NullPointerException if a key or value of {@code elements} is {@code null}
     * @throws IllegalArgumentException if {@code elements} is empty, an identifier is not from 1 to 255, or an
     * element is longer than 255 bytes
     */
    public static RtpHeaderExtension of(Map<Integer, byte[]> elements) {
        if (elements.isEmpty()) {
            throw new IllegalArgumentException("no extension elements");
        }
        var oneByte = true;
        var size = 0;
        for (var entry : elements.entrySet()) {
            int id = Objects.requireNonNull(entry.getKey(), "identifier");
            var value = Objects.requireNonNull(entry.getValue(), "element");
            if (id < 1 || id > 255) {
                throw new IllegalArgumentException("extension element identifier " + id + " is not from 1 to 255");
            }
            if (value.length > 255) {
                throw new IllegalArgumentException("extension element of " + value.length + " bytes is too long");
            }
            if (id > 14 || value.length == 0 || value.length > 16) {
                oneByte = false;
            }
            size += value.length;
        }
        size += (oneByte ? 1 : 2) * elements.size();
        var data = new byte[(size + 3) & ~3];
        var position = 0;
        for (var entry : elements.entrySet()) {
            int id = entry.getKey();
            var value = entry.getValue();
            if (oneByte) {
                data[position++] = (byte) ((id << 4) | (value.length - 1));
            } else {
                data[position++] = (byte) id;
                data[position++] = (byte) value.length;
            }
            System.arraycopy(value, 0, data, position, value.length);
            position += value.length;
        }
        return new RtpHeaderExtension(oneByte ? ONE_BYTE_PROFILE : TWO_BYTE_PROFILE, data);
    }

    /**
     * {@return whether the extension uses one of the two RFC 8285 element forms}
     */
    public boolean hasElements() {
        return profile == ONE_BYTE_PROFILE || (profile & 0xFFF0) == TWO_BYTE_PROFILE;
    }

    /**
     * Reads the RFC 8285 elements of the extension.
     *
     * <p>Padding bytes between elements are skipped. In the one-byte form, identifier 15 ends the parsing, as the
     * RFC requires. When an identifier repeats, the last element wins.
     *
     * @return the element data by identifier, in the order they appear, unmodifiable
     * @throws IllegalStateException if the extension does not use an RFC 8285 form ({@link #hasElements()} is
     * {@code false})
     * @throws MediaException.Malformed if an element runs past the end of the data
     */
    public Map<Integer, byte[]> elements() {
        if (!hasElements()) {
            throw new IllegalStateException("extension profile " + profile + " is not an RFC 8285 form");
        }
        var oneByte = profile == ONE_BYTE_PROFILE;
        var elements = new LinkedHashMap<Integer, byte[]>();
        var position = 0;
        while (position < data.length) {
            var header = data[position] & 0xFF;
            if (header == 0) {
                position++;
                continue;
            }
            int id;
            int length;
            if (oneByte) {
                id = header >>> 4;
                if (id == 15) {
                    break;
                }
                length = (header & 0x0F) + 1;
                position++;
            } else {
                if (position + 1 >= data.length) {
                    throw new MediaException.Malformed("two-byte extension element header is truncated");
                }
                id = header;
                length = data[position + 1] & 0xFF;
                position += 2;
            }
            if (position + length > data.length) {
                throw new MediaException.Malformed("extension element " + id + " of " + length
                        + " bytes runs past the extension");
            }
            elements.put(id, Arrays.copyOfRange(data, position, position + length));
            position += length;
        }
        return Collections.unmodifiableMap(elements);
    }

    /**
     * Returns one RFC 8285 element of the extension.
     *
     * @param id the element identifier, from 1 to 255
     * @return a copy of the element data, or an empty optional when the extension does not use an RFC 8285 form or
     * holds no such element
     * @throws MediaException.Malformed if an element runs past the end of the data
     */
    public Optional<byte[]> element(int id) {
        if (!hasElements()) {
            return Optional.empty();
        }
        return Optional.ofNullable(elements().get(id));
    }

    @Override
    public boolean equals(Object object) {
        return object instanceof RtpHeaderExtension that && profile == that.profile && Arrays.equals(data, that.data);
    }

    @Override
    public int hashCode() {
        return 31 * profile + Arrays.hashCode(data);
    }

    @Override
    public String toString() {
        return "RtpHeaderExtension[profile=0x" + Integer.toHexString(profile) + ", data=" + data.length + " bytes]";
    }
}
