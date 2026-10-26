package com.github.auties00.smalt.image.metadata;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.jpeg.internal.JpegXmp;
import com.github.auties00.smalt.internal.io.ByteArrays;
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
 * Splits XMP packets too large for one JPEG segment into a main packet and an Extended XMP
 * packet, and merges them back, as XMP specification part 3 section 1.1.3.1 describes.
 *
 * <p>The main packet names the extended one by the MD5 digest of its serialization, as 32
 * upper-case hexadecimal digits, in its {@code xmpNote:HasExtendedXMP} property. The extended
 * packet is stored in chunks, each prefixed by the digest, the full length and the chunk's
 * offset; chunks may come in any order and may repeat.
 */
final class ExtendedXmp {
    /**
     * The largest main packet, the payload of a segment after the XMP identifier.
     */
    private static final int MAX_MAIN = JpegFile.MAX_PAYLOAD - JpegFile.XMP_ID.length;

    /**
     * The size of the header of an Extended XMP chunk after its identifier: digest, full length
     * and offset.
     */
    private static final int CHUNK_HEADER = 32 + 4 + 4;

    /**
     * The largest data of an Extended XMP chunk.
     */
    private static final int MAX_CHUNK = JpegFile.MAX_PAYLOAD - JpegFile.EXTENDED_XMP_ID.length - CHUNK_HEADER;

    /**
     * The property that names the extended packet.
     */
    private static final String HAS_EXTENDED = "HasExtendedXMP";

    /**
     * Prevents instantiation.
     */
    private ExtendedXmp() {
        throw new AssertionError();
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
    static XmpPacket merge(byte[] main, List<byte[]> chunks, MediaLimits limits) {
        return JpegXmp.merge(main, chunks, limits);
    }

    /**
     * Serializes a packet into JPEG {@code APP1} segments, splitting it when it exceeds one
     * segment.
     *
     * <p>The largest properties move to the extended packet, the fewest that make the main packet,
     * which then names the extended one, fit a segment.
     *
     * @param packet the XMP packet
     * @return the segments, the main one first
     * @throws MediaException.LimitExceeded if the packet cannot be split to fit
     */
    static List<byte[]> segments(XmpPacket packet) {
        var cleaned = packet.without(XmpPacket.XMP_NOTE, HAS_EXTENDED);
        var full = cleaned.toBytes();
        if (full.length <= MAX_MAIN) {
            return List.of(JpegFile.segment(0xE1, JpegFile.XMP_ID, full, 0, full.length));
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
        result.add(JpegFile.segment(0xE1, JpegFile.XMP_ID, mainBytes, 0, mainBytes.length));
        for (var offset = 0; offset < extendedBytes.length; offset += MAX_CHUNK) {
            var length = Math.min(MAX_CHUNK, extendedBytes.length - offset);
            var prefix = new byte[JpegFile.EXTENDED_XMP_ID.length + CHUNK_HEADER];
            System.arraycopy(JpegFile.EXTENDED_XMP_ID, 0, prefix, 0, JpegFile.EXTENDED_XMP_ID.length);
            var at = JpegFile.EXTENDED_XMP_ID.length;
            System.arraycopy(guid.getBytes(StandardCharsets.ISO_8859_1), 0, prefix, at, 32);
            ByteArrays.put32be(prefix, at + 32, extendedBytes.length);
            ByteArrays.put32be(prefix, at + 36, offset);
            result.add(JpegFile.segment(0xE1, prefix, extendedBytes, offset, length));
        }
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
