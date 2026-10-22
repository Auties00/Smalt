package com.github.auties00.smalt.image.jpeg.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.internal.io.ByteArrays;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.metadata.XmpPacket;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Recognizes the XMP {@code APP1} segments of a JPEG stream and merges a main packet with its
 * Extended XMP packet, as XMP specification part 3 section 1.1.3.1 describes.
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
}
