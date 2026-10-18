package com.github.auties00.smalt.internal.header;

import java.util.Arrays;
import java.util.List;

/**
 * Packs and unpacks packets with Xiph lacing, the form Matroska {@code CodecPrivate} elements and
 * other out-of-band configurations give the Vorbis and Theora header packets.
 *
 * {@snippet :
 * count - 1           one byte
 * size of packet i    for every packet but the last: a run of 255 bytes, then the remainder
 * packet bytes        every packet, in order; the last fills the rest
 * }
 *
 * <p>This class is thread-safe.
 */
public final class XiphLacing {
    /**
     * Prevents instantiation.
     */
    private XiphLacing() {
        throw new AssertionError();
    }

    /**
     * Packs a list of packets.
     *
     * @param packets the packets, from 1 to 256
     * @return the laced bytes
     * @throws IllegalArgumentException if there are no packets or more than 256
     */
    public static byte[] lace(List<byte[]> packets) {
        if (packets.isEmpty() || packets.size() > 256) {
            throw new IllegalArgumentException("Xiph lacing holds 1 to 256 packets, not " + packets.size());
        }
        var size = 1L;
        for (var i = 0; i < packets.size(); i++) {
            size += packets.get(i).length;
            if (i < packets.size() - 1) {
                size += packets.get(i).length / 255 + 1;
            }
        }
        var result = new byte[Math.toIntExact(size)];
        result[0] = (byte) (packets.size() - 1);
        var offset = 1;
        for (var i = 0; i < packets.size() - 1; i++) {
            var length = packets.get(i).length;
            while (length >= 255) {
                result[offset++] = (byte) 255;
                length -= 255;
            }
            result[offset++] = (byte) length;
        }
        for (var packet : packets) {
            System.arraycopy(packet, 0, result, offset, packet.length);
            offset += packet.length;
        }
        return result;
    }

    /**
     * Packs packets given as arguments or an array.
     *
     * @param packets the packets, from 1 to 256
     * @return the laced bytes
     * @throws IllegalArgumentException if there are no packets or more than 256
     */
    public static byte[] lace(byte[]... packets) {
        return lace(Arrays.asList(packets));
    }

    /**
     * Unpacks laced packets.
     *
     * <p>The caller checks the packet count it expects on the result and reports a mismatch, or a
     * {@code null}, with its own exception.
     *
     * @param laced the laced bytes
     * @return the packets, as many as the first byte declares, or {@code null} when {@code laced}
     * is empty, a size runs past the end, or the sizes exceed the bytes that follow
     */
    public static byte[][] unlace(byte[] laced) {
        if (laced.length == 0) {
            return null;
        }
        var count = (laced[0] & 0xFF) + 1;
        var position = 1;
        var sizes = new long[count];
        var total = 0L;
        for (var i = 0; i < count - 1; i++) {
            long size = 0;
            int lace;
            do {
                if (position >= laced.length) {
                    return null;
                }
                lace = laced[position++] & 0xFF;
                size += lace;
            } while (lace == 255);
            sizes[i] = size;
            total += size;
        }
        sizes[count - 1] = laced.length - position - total;
        if (sizes[count - 1] < 0) {
            return null;
        }
        var packets = new byte[count][];
        for (var i = 0; i < count; i++) {
            packets[i] = Arrays.copyOfRange(laced, position, position + (int) sizes[i]);
            position += (int) sizes[i];
        }
        return packets;
    }
}
