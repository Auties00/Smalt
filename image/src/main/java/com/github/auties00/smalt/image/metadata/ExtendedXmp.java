package com.github.auties00.smalt.image.metadata;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.jpeg.internal.JpegXmp;
import com.github.auties00.smalt.metadata.XmpPacket;

import java.util.ArrayList;
import java.util.List;

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
     * segment, as {@link JpegXmp#payloads(XmpPacket)} does.
     *
     * @param packet the XMP packet
     * @return the segments, the main one first
     * @throws MediaException.LimitExceeded if the packet cannot be split to fit
     */
    static List<byte[]> segments(XmpPacket packet) {
        var result = new ArrayList<byte[]>();
        for (var payload : JpegXmp.payloads(packet)) {
            result.add(JpegFile.segment(0xE1, new byte[0], payload, 0, payload.length));
        }
        return result;
    }
}
