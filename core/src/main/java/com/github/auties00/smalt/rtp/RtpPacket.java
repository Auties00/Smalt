package com.github.auties00.smalt.rtp;

import com.github.auties00.smalt.MediaException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Holds one RTP data packet of RFC 3550 section 5.1: the fixed header fields, the contributing sources, the
 * optional header extension and the payload.
 *
 * <p>The version is always 2. Padding is not represented: {@link #parse(byte[], int, int)} strips it and
 * {@link #toByteArray()} writes none. Unsigned 32-bit fields ({@link #timestamp()}, {@link #ssrc()} and the
 * contributing sources) are held in a {@code long}, from 0 to {@code 2^32 - 1}.
 *
 * <p>The payload array is shared, not copied, so neither producers nor consumers may modify it after the packet is
 * created. Equality compares the header fields, the extension and the payload contents.
 *
 * <p>Instances are immutable and thread-safe, provided the payload is not modified.
 *
 * @param marker the marker bit, whose meaning the payload format defines
 * @param payloadType the payload type, from 0 to 127
 * @param sequenceNumber the sequence number, from 0 to 65535
 * @param timestamp the RTP timestamp in units of the payload format's clock, from 0 to {@code 2^32 - 1}
 * @param ssrc the synchronization source identifier, from 0 to {@code 2^32 - 1}
 * @param csrcs the contributing source identifiers, at most 15, each from 0 to {@code 2^32 - 1}, unmodifiable
 * @param extension the header extension, or {@code null} for none
 * @param payload the payload, without padding
 */
public record RtpPacket(boolean marker, int payloadType, int sequenceNumber, long timestamp, long ssrc,
                        List<Long> csrcs, RtpHeaderExtension extension, byte[] payload) {
    /**
     * The size in bytes of the fixed header, without contributing sources or extension.
     */
    public static final int HEADER_SIZE = 12;

    /**
     * The largest unsigned 32-bit value.
     */
    private static final long UINT32 = 0xFFFF_FFFFL;

    /**
     * Validates the fields and makes the contributing sources unmodifiable.
     *
     * @throws NullPointerException if an element of {@code csrcs} is {@code null}
     * @throws IllegalArgumentException if a field is outside its range or there are more than 15 contributing
     * sources
     */
    public RtpPacket {
        Objects.requireNonNull(payload, "payload");
        csrcs = List.copyOf(csrcs);
        if (payloadType < 0 || payloadType > 127) {
            throw new IllegalArgumentException("payload type " + payloadType + " is not between 0 and 127");
        }
        if (sequenceNumber < 0 || sequenceNumber > 0xFFFF) {
            throw new IllegalArgumentException("sequence number " + sequenceNumber + " is not between 0 and 65535");
        }
        checkUnsigned("timestamp", timestamp);
        checkUnsigned("ssrc", ssrc);
        if (csrcs.size() > 15) {
            throw new IllegalArgumentException(csrcs.size() + " contributing sources, at most 15 allowed");
        }
        for (var csrc : csrcs) {
            checkUnsigned("csrc", csrc);
        }
    }

    /**
     * Constructs a packet without contributing sources or header extension.
     *
     * @param marker the marker bit
     * @param payloadType the payload type, from 0 to 127
     * @param sequenceNumber the sequence number, from 0 to 65535
     * @param timestamp the RTP timestamp, from 0 to {@code 2^32 - 1}
     * @param ssrc the synchronization source identifier, from 0 to {@code 2^32 - 1}
     * @param payload the payload, shared and not copied
     * @throws IllegalArgumentException if a field is outside its range
     */
    public RtpPacket(boolean marker, int payloadType, int sequenceNumber, long timestamp, long ssrc, byte[] payload) {
        this(marker, payloadType, sequenceNumber, timestamp, ssrc, List.of(), null, payload);
    }

    private static void checkUnsigned(String name, long value) {
        if (value < 0 || value > UINT32) {
            throw new IllegalArgumentException(name + " " + value + " is not an unsigned 32-bit value");
        }
    }

    /**
     * Parses a whole datagram as an RTP packet.
     *
     * @param datagram the received UDP payload, exactly one RTP packet
     * @return the packet, whose payload is a copy
     * @throws MediaException.Malformed if the datagram is not a well-formed RTP version 2 packet
     */
    public static RtpPacket parse(byte[] datagram) {
        return parse(datagram, 0, datagram.length);
    }

    /**
     * Parses a range of an array as an RTP packet.
     *
     * <p>The padding the {@code P} bit announces is removed; a padding count of zero or one larger than what
     * follows the header is malformed.
     *
     * @param data the array holding the datagram
     * @param offset the first byte of the datagram
     * @param length the datagram size in bytes
     * @return the packet, whose payload and extension data are copies
     * @throws IndexOutOfBoundsException if the range is outside {@code data}
     * @throws MediaException.Malformed if the range is not a well-formed RTP version 2 packet
     */
    public static RtpPacket parse(byte[] data, int offset, int length) {
        Objects.checkFromIndexSize(offset, length, data.length);
        if (length < HEADER_SIZE) {
            throw new MediaException.Malformed("RTP packet of " + length + " bytes is shorter than its header");
        }
        var first = data[offset] & 0xFF;
        if (first >>> 6 != 2) {
            throw new MediaException.Malformed("RTP version " + (first >>> 6) + " is not 2");
        }
        var padded = (first & 0x20) != 0;
        var extended = (first & 0x10) != 0;
        var count = first & 0x0F;
        var second = data[offset + 1] & 0xFF;
        var sequence = ((data[offset + 2] & 0xFF) << 8) | (data[offset + 3] & 0xFF);
        var timestamp = readUnsigned(data, offset + 4);
        var ssrc = readUnsigned(data, offset + 8);
        var end = offset + length;
        var position = offset + HEADER_SIZE;
        if (position + 4L * count > end) {
            throw new MediaException.Malformed("RTP packet of " + length + " bytes is too short for " + count
                    + " contributing sources");
        }
        List<Long> csrcs;
        if (count == 0) {
            csrcs = List.of();
        } else {
            var list = new ArrayList<Long>(count);
            for (var index = 0; index < count; index++) {
                list.add(readUnsigned(data, position));
                position += 4;
            }
            csrcs = list;
        }
        RtpHeaderExtension extension = null;
        if (extended) {
            if (position + 4 > end) {
                throw new MediaException.Malformed("RTP header extension is truncated");
            }
            var profile = ((data[position] & 0xFF) << 8) | (data[position + 1] & 0xFF);
            var words = ((data[position + 2] & 0xFF) << 8) | (data[position + 3] & 0xFF);
            position += 4;
            if (position + 4L * words > end) {
                throw new MediaException.Malformed("RTP header extension of " + words + " words is truncated");
            }
            var body = new byte[words * 4];
            System.arraycopy(data, position, body, 0, body.length);
            position += body.length;
            extension = new RtpHeaderExtension(profile, body);
        }
        if (padded) {
            if (end == position) {
                throw new MediaException.Malformed("RTP packet announces padding but has no payload");
            }
            var padding = data[end - 1] & 0xFF;
            if (padding == 0 || padding > end - position) {
                throw new MediaException.Malformed("RTP padding of " + padding + " bytes does not fit the packet");
            }
            end -= padding;
        }
        var payload = new byte[end - position];
        System.arraycopy(data, position, payload, 0, payload.length);
        return new RtpPacket((second & 0x80) != 0, second & 0x7F, sequence, timestamp, ssrc, csrcs, extension,
                payload);
    }

    private static long readUnsigned(byte[] data, int position) {
        return ((data[position] & 0xFFL) << 24) | ((data[position + 1] & 0xFF) << 16)
                | ((data[position + 2] & 0xFF) << 8) | (data[position + 3] & 0xFF);
    }

    /**
     * {@return the size in bytes of the serialized packet: the header, the contributing sources, the extension and
     * the payload}
     */
    public int size() {
        return HEADER_SIZE + 4 * csrcs.size() + (extension == null ? 0 : 4 + extension.data().length)
                + payload.length;
    }

    /**
     * Serializes the packet as RFC 3550 lays it out, without padding.
     *
     * @return a new array of {@link #size()} bytes
     */
    public byte[] toByteArray() {
        var out = new byte[size()];
        write(out, 0);
        return out;
    }

    /**
     * Serializes the packet into an array, without padding.
     *
     * @param out the destination
     * @param offset the first byte to write
     * @return the number of bytes written, {@link #size()}
     * @throws IndexOutOfBoundsException if {@code out} has fewer than {@link #size()} bytes from {@code offset}
     */
    public int write(byte[] out, int offset) {
        var size = size();
        Objects.checkFromIndexSize(offset, size, out.length);
        out[offset] = (byte) (0x80 | (extension != null ? 0x10 : 0) | csrcs.size());
        out[offset + 1] = (byte) ((marker ? 0x80 : 0) | payloadType);
        out[offset + 2] = (byte) (sequenceNumber >>> 8);
        out[offset + 3] = (byte) sequenceNumber;
        writeUnsigned(out, offset + 4, timestamp);
        writeUnsigned(out, offset + 8, ssrc);
        var position = offset + HEADER_SIZE;
        for (var csrc : csrcs) {
            writeUnsigned(out, position, csrc);
            position += 4;
        }
        if (extension != null) {
            var body = extension.data();
            out[position] = (byte) (extension.profile() >>> 8);
            out[position + 1] = (byte) extension.profile();
            out[position + 2] = (byte) (body.length >>> 10);
            out[position + 3] = (byte) (body.length >>> 2);
            System.arraycopy(body, 0, out, position + 4, body.length);
            position += 4 + body.length;
        }
        System.arraycopy(payload, 0, out, position, payload.length);
        return size;
    }

    private static void writeUnsigned(byte[] out, int position, long value) {
        out[position] = (byte) (value >>> 24);
        out[position + 1] = (byte) (value >>> 16);
        out[position + 2] = (byte) (value >>> 8);
        out[position + 3] = (byte) value;
    }

    /**
     * Returns a copy of this packet with another sequence number.
     *
     * @param sequenceNumber the new sequence number, from 0 to 65535
     * @return the modified packet, sharing the payload
     * @throws IllegalArgumentException if {@code sequenceNumber} is outside its range
     */
    public RtpPacket withSequenceNumber(int sequenceNumber) {
        return new RtpPacket(marker, payloadType, sequenceNumber, timestamp, ssrc, csrcs, extension, payload);
    }

    /**
     * Returns a copy of this packet with another header extension.
     *
     * @param extension the new extension, or {@code null} for none
     * @return the modified packet, sharing the payload
     */
    public RtpPacket withExtension(RtpHeaderExtension extension) {
        return new RtpPacket(marker, payloadType, sequenceNumber, timestamp, ssrc, csrcs, extension, payload);
    }

    @Override
    public boolean equals(Object object) {
        return object instanceof RtpPacket that && marker == that.marker && payloadType == that.payloadType
                && sequenceNumber == that.sequenceNumber && timestamp == that.timestamp && ssrc == that.ssrc
                && csrcs.equals(that.csrcs) && Objects.equals(extension, that.extension)
                && Arrays.equals(payload, that.payload);
    }

    @Override
    public int hashCode() {
        return Objects.hash(marker, payloadType, sequenceNumber, timestamp, ssrc, csrcs, extension)
                + 31 * Arrays.hashCode(payload);
    }

    @Override
    public String toString() {
        return "RtpPacket[marker=" + marker + ", payloadType=" + payloadType + ", sequenceNumber=" + sequenceNumber
                + ", timestamp=" + timestamp + ", ssrc=" + ssrc + ", csrcs=" + csrcs + ", extension=" + extension
                + ", payload=" + payload.length + " bytes]";
    }
}
