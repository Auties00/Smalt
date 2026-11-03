package com.github.auties00.smalt.image.jpeg.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.internal.io.ByteArrays;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.metadata.XmpPacket;

import com.github.auties00.smalt.metadata.XmpProperty;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

/**
 * Recognizes the XMP {@code APP1} segments of a JPEG stream, merges a main packet with its Extended XMP
 * packet and splits a packet too large for one segment into both, as XMP specification part 3 section
 * 1.1.3.1 describes.
 *
 * <p>The main packet names the extended one by the MD5 digest of its serialization, as 32
 * upper-case hexadecimal digits, in its {@code xmpNote:HasExtendedXMP} property. The extended
 * packet is stored in chunks, each prefixed by the digest, the full length and the chunk's
 * offset; chunks may come in any order and may repeat. This class is thread-safe.
 */
public final class JpegXmp {
    /**
     * The identifier of the main XMP {@code APP1} segment.
     */
    public static final byte[] XMP_ID = "http://ns.adobe.com/xap/1.0/\0".getBytes(StandardCharsets.ISO_8859_1);

    /**
     * The identifier of the Extended XMP {@code APP1} segments.
     */
    public static final byte[] EXTENDED_XMP_ID = "http://ns.adobe.com/xmp/extension/\0"
            .getBytes(StandardCharsets.ISO_8859_1);

    /**
     * The size of the header of an Extended XMP chunk after its identifier: digest, full length
     * and offset.
     */
    public static final int CHUNK_HEADER = 32 + 4 + 4;

    /**
     * The property that names the extended packet.
     */
    public static final String HAS_EXTENDED = "HasExtendedXMP";

    /**
     * The largest payload of a JPEG segment, after its length field.
     */
    public static final int MAX_PAYLOAD = 65533;

    /**
     * The largest main packet, the payload of a segment after the XMP identifier.
     */
    public static final int MAX_MAIN = MAX_PAYLOAD - XMP_ID.length;

    /**
     * The largest data of an Extended XMP chunk.
     */
    private static final int MAX_CHUNK = MAX_PAYLOAD - EXTENDED_XMP_ID.length - CHUNK_HEADER;

    /**
     * Prevents instantiation.
     */
    private JpegXmp() {
        throw new AssertionError();
    }

    /**
     * {@return whether a segment payload starts with an identifier}
     *
     * @param data the array holding the payload
     * @param offset the index of the payload's first byte
     * @param length the payload length
     * @param id the identifier
     */
    public static boolean startsWith(byte[] data, int offset, int length, byte[] id) {
        if (length < id.length) {
            return false;
        }
        for (var i = 0; i < id.length; i++) {
            if (data[offset + i] != id[i]) {
                return false;
            }
        }
        return true;
    }

    /**
     * Parses a main packet and merges the properties of its extended packet.
     *
     * <p>A main packet that does not parse yields {@code null}. An extended packet is merged only
     * when the chunks naming it agree on its length and together cover every byte of it; one that
     * is incomplete or does not parse is ignored.
     *
     * @param main the main packet bytes
     * @param chunks the payloads of the Extended XMP segments, identifier included
     * @param limits the resource bounds of the extended packet
     * @return the merged packet, or {@code null}
     */
    public static XmpPacket merge(byte[] main, List<byte[]> chunks, MediaLimits limits) {
        XmpPacket packet;
        try {
            packet = XmpPacket.parse(main);
        } catch (MediaException exception) {
            return null;
        }
        return merge(packet, chunks, limits);
    }

    /**
     * Merges a main packet with its extended packet into packet bytes.
     *
     * @param main the main packet bytes
     * @param chunks the payloads of the Extended XMP segments, identifier included
     * @param limits the resource bounds of the extended packet
     * @return {@code main} itself when it does not parse, names no extended packet, or its extended
     * packet is incomplete, does not parse or exceeds the limits; otherwise the serialization of
     * the merged packet
     */
    public static byte[] mergeBytes(byte[] main, List<byte[]> chunks, MediaLimits limits) {
        if (chunks.isEmpty()) {
            return main;
        }
        XmpPacket packet;
        try {
            packet = XmpPacket.parse(main);
        } catch (MediaException exception) {
            return main;
        }
        try {
            var merged = merge(packet, chunks, limits);
            return merged == packet ? main : merged.toBytes();
        } catch (MediaException.LimitExceeded exception) {
            return main;
        }
    }

    /**
     * Merges the properties of the extended packet a parsed main packet names.
     *
     * @param packet the main packet
     * @param chunks the payloads of the Extended XMP segments, identifier included
     * @param limits the resource bounds of the extended packet
     * @return {@code packet} itself when nothing is merged, otherwise the merged packet
     */
    private static XmpPacket merge(XmpPacket packet, List<byte[]> chunks, MediaLimits limits) {
        var guid = packet.text(XmpPacket.XMP_NOTE, HAS_EXTENDED).orElse(null);
        if (guid == null || chunks.isEmpty()) {
            return packet;
        }
        byte[] extended = null;
        var ranges = new ArrayList<long[]>();
        for (var chunk : chunks) {
            var at = EXTENDED_XMP_ID.length;
            if (chunk.length < at + CHUNK_HEADER) {
                continue;
            }
            var id = new String(chunk, at, 32, StandardCharsets.ISO_8859_1);
            if (!id.equalsIgnoreCase(guid)) {
                continue;
            }
            var total = ByteArrays.u32be(chunk, at + 32);
            var offset = ByteArrays.u32be(chunk, at + 36);
            var length = chunk.length - at - CHUNK_HEADER;
            if (extended == null) {
                LimitChecks.checkAllocation(limits, total);
                extended = new byte[(int) total];
            }
            if (total != extended.length || offset + length > extended.length) {
                continue;
            }
            System.arraycopy(chunk, at + CHUNK_HEADER, extended, (int) offset, length);
            ranges.add(new long[]{offset, offset + length});
        }
        if (extended == null || !covers(ranges, extended.length)) {
            return packet;
        }
        try {
            var properties = XmpPacket.parse(extended);
            var merged = packet.without(XmpPacket.XMP_NOTE, HAS_EXTENDED);
            for (var property : properties.properties()) {
                if (merged.get(property.namespace(), property.name()).isEmpty()) {
                    merged = merged.with(property.namespace(), property.name(), property.value());
                }
            }
            return merged;
        } catch (MediaException exception) {
            return packet;
        }
    }

    /**
     * Returns whether byte ranges together cover a whole array.
     *
     * @param ranges the half-open ranges, as start and end; sorted in place
     * @param length the array length
     * @return whether every index below {@code length} lies in a range
     */
    private static boolean covers(List<long[]> ranges, long length) {
        ranges.sort(Comparator.comparingLong(range -> range[0]));
        var reach = 0L;
        for (var range : ranges) {
            if (range[0] > reach) {
                return false;
            }
            reach = Math.max(reach, range[1]);
        }
        return reach >= length;
    }

    /**
     * Serializes a packet into the payloads of JPEG {@code APP1} segments, identifiers included, splitting
     * it when it exceeds one segment.
     *
     * <p>The largest properties move to the extended packet, the fewest that make the main packet,
     * which then names the extended one, fit a segment.
     *
     * @param packet the XMP packet
     * @return the payloads, the main one first
     * @throws MediaException.LimitExceeded if the packet cannot be split to fit
     */
    public static List<byte[]> payloads(XmpPacket packet) {
        var cleaned = packet.without(XmpPacket.XMP_NOTE, HAS_EXTENDED);
        var full = cleaned.toBytes();
        if (full.length <= MAX_MAIN) {
            return List.of(concat(XMP_ID, full, 0, full.length));
        }
        var properties = new ArrayList<Sized>();
        for (var property : cleaned.properties()) {
            properties.add(new Sized(property, size(property)));
        }
        properties.sort(Comparator.comparingInt(Sized::size).reversed());
        var low = Math.min(1, properties.size());
        var high = properties.size();
        while (low < high) {
            var middle = (low + high) >>> 1;
            if (fits(withoutLargest(cleaned, properties, middle))) {
                high = middle;
            } else {
                low = middle + 1;
            }
        }
        var main = withoutLargest(cleaned, properties, low);
        var extended = XmpPacket.empty();
        for (var i = 0; i < low; i++) {
            var property = properties.get(i).property();
            extended = extended.with(property.namespace(), property.name(), property.value());
        }
        var extendedBytes = extended.toBytes();
        var guid = md5(extendedBytes);
        var mainBytes = main.with(XmpPacket.XMP_NOTE, HAS_EXTENDED, guid).toBytes();
        if (mainBytes.length > MAX_MAIN) {
            throw new MediaException.LimitExceeded("XMP packet cannot be split to fit a JPEG segment");
        }
        var result = new ArrayList<byte[]>();
        result.add(concat(XMP_ID, mainBytes, 0, mainBytes.length));
        for (var offset = 0; offset < extendedBytes.length; offset += MAX_CHUNK) {
            var length = Math.min(MAX_CHUNK, extendedBytes.length - offset);
            var prefix = new byte[EXTENDED_XMP_ID.length + CHUNK_HEADER];
            System.arraycopy(EXTENDED_XMP_ID, 0, prefix, 0, EXTENDED_XMP_ID.length);
            var at = EXTENDED_XMP_ID.length;
            System.arraycopy(guid.getBytes(StandardCharsets.ISO_8859_1), 0, prefix, at, 32);
            ByteArrays.put32be(prefix, at + 32, extendedBytes.length);
            ByteArrays.put32be(prefix, at + 36, offset);
            result.add(concat(prefix, extendedBytes, offset, length));
        }
        return result;
    }

    /**
     * {@return a prefix followed by a range of bytes}
     *
     * @param prefix the bytes written first
     * @param data the array holding the range
     * @param offset the index of the range
     * @param length the length of the range
     */
    private static byte[] concat(byte[] prefix, byte[] data, int offset, int length) {
        var result = new byte[prefix.length + length];
        System.arraycopy(prefix, 0, result, 0, prefix.length);
        System.arraycopy(data, offset, result, prefix.length, length);
        return result;
    }

    /**
     * Returns whether a main packet, once it names an extended packet, fits a segment.
     *
     * @param main the main packet
     * @return whether its serialization fits
     */
    private static boolean fits(XmpPacket main) {
        return main.with(XmpPacket.XMP_NOTE, HAS_EXTENDED, "00000000000000000000000000000000").toBytes().length
                <= MAX_MAIN;
    }

    /**
     * Removes the largest properties from a packet.
     *
     * @param packet the XMP packet
     * @param properties its properties, largest first
     * @param count the number of properties to remove
     * @return the packet without the first {@code count} properties
     */
    private static XmpPacket withoutLargest(XmpPacket packet, List<Sized> properties, int count) {
        var removed = new HashSet<XmpProperty>();
        for (var i = 0; i < count; i++) {
            removed.add(properties.get(i).property());
        }
        return packet.withoutIf(removed::contains);
    }

    /**
     * Holds a property with the size it serializes to alone.
     *
     * @param property the XMP property
     * @param size the size of a packet holding only it
     */
    private record Sized(XmpProperty property, int size) {
    }

    /**
     * Estimates the serialized size of a property.
     *
     * @param property the XMP property
     * @return the size of a packet holding only it
     */
    private static int size(XmpProperty property) {
        return XmpPacket.empty().with(property.namespace(), property.name(), property.value()).toBytes().length;
    }

    /**
     * Computes the MD5 digest of bytes as 32 upper-case hexadecimal digits.
     *
     * @param data the bytes
     * @return the digest
     */
    private static String md5(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(data)).toUpperCase(Locale.ROOT);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("MD5 is a required algorithm", exception);
        }
    }
}
