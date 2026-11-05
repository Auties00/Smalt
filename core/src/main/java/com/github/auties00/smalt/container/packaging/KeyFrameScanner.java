package com.github.auties00.smalt.container.packaging;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Finds the key frames of a written segment as its bytes pass, for the byte ranges of HLS I-frame playlists:
 * the transport stream packets of each video PES packet that starts at a random access point, or the start of
 * each movie fragment through the end of its first sample.
 *
 * <p>Offsets count from the first byte observed. Instances are not thread-safe.
 */
abstract sealed class KeyFrameScanner {
    /**
     * A key frame found.
     *
     * @param offset the offset of its first byte: the first transport packet of its PES packet, or the
     * {@code moof} of its fragment
     * @param end the offset after its last byte
     */
    record Found(long offset, long end) {
    }

    /**
     * The key frames found, in order.
     */
    final List<Found> found = new ArrayList<>();

    /**
     * The number of bytes observed.
     */
    long position;

    private KeyFrameScanner() {
    }

    /**
     * {@return a scanner of transport stream packets}
     */
    static Ts transportStream() {
        return new Ts();
    }

    /**
     * {@return a scanner of ISO base media boxes}
     */
    static Boxes boxes() {
        return new Boxes();
    }

    /**
     * Observes bytes written.
     *
     * @param data the bytes, read from their position to their limit; the buffer is not moved
     */
    abstract void observe(ByteBuffer data);

    /**
     * Ends the observed stream, completing a key frame still open.
     */
    abstract void end();

    /**
     * Scans 188-byte transport stream packets: the program association and map tables locate the first video
     * stream, whose PES packets that start with the random access indicator are the key frames.
     */
    static final class Ts extends KeyFrameScanner {
        /**
         * The size of a transport packet.
         */
        private static final int PACKET = 188;

        /**
         * The packet being assembled.
         */
        private final byte[] packet = new byte[PACKET];

        /**
         * The number of bytes of the packet assembled.
         */
        private int filled;

        /**
         * The PID of the program map table, or -1 before the association table.
         */
        private int pmtPid = -1;

        /**
         * The PID of the video stream, or -1 before the map table.
         */
        private int videoPid = -1;

        /**
         * The offset of the key frame being read, or -1.
         */
        private long start = -1;

        /**
         * The offset after the last packet of the key frame being read.
         */
        private long last;

        /**
         * The offset after the program tables that open the stream, or -1 while they continue.
         */
        long tablesEnd = -1;

        @Override
        void observe(ByteBuffer data) {
            for (var index = data.position(); index < data.limit(); ) {
                var take = Math.min(PACKET - filled, data.limit() - index);
                data.get(index, packet, filled, take);
                filled += take;
                index += take;
                if (filled == PACKET) {
                    packet(position);
                    position += PACKET;
                    filled = 0;
                }
            }
        }

        /**
         * Reads one packet.
         *
         * @param offset the offset of the packet
         */
        private void packet(long offset) {
            if ((packet[0] & 0xFF) != 0x47) {
                return;
            }
            var pid = (packet[1] & 0x1F) << 8 | packet[2] & 0xFF;
            var unitStart = (packet[1] & 0x40) != 0;
            var control = (packet[3] >> 4) & 3;
            var payload = 4;
            var randomAccess = false;
            if ((control & 2) != 0) {
                var length = packet[4] & 0xFF;
                randomAccess = length > 0 && (packet[5] & 0x40) != 0;
                payload = 5 + length;
            }
            if (tablesEnd < 0 && pid != 0 && (pmtPid < 0 || pid != pmtPid)) {
                tablesEnd = offset;
            }
            if ((control & 1) == 0 || payload >= PACKET) {
                return;
            }
            if (pid == 0 && unitStart) {
                association(payload);
            } else if (pid == pmtPid && unitStart) {
                map(payload);
            } else if (pid == videoPid) {
                if (unitStart) {
                    close();
                    if (randomAccess) {
                        start = offset;
                    }
                }
                if (start >= 0) {
                    last = offset + PACKET;
                }
            }
        }

        /**
         * Reads the program association table for the PID of the first program's map table.
         *
         * @param payload the index of the payload in the packet
         */
        private void association(int payload) {
            var at = payload + 1 + (packet[payload] & 0xFF);
            if (at + 8 > PACKET || packet[at] != 0) {
                return;
            }
            var end = Math.min(PACKET, at + 3 + ((packet[at + 1] & 0x0F) << 8 | packet[at + 2] & 0xFF) - 4);
            for (var entry = at + 8; entry + 4 <= end; entry += 4) {
                var program = (packet[entry] & 0xFF) << 8 | packet[entry + 1] & 0xFF;
                if (program != 0) {
                    pmtPid = (packet[entry + 2] & 0x1F) << 8 | packet[entry + 3] & 0xFF;
                    return;
                }
            }
        }

        /**
         * Reads the program map table for the PID of its first video stream.
         *
         * @param payload the index of the payload in the packet
         */
        private void map(int payload) {
            if (videoPid >= 0) {
                return;
            }
            var at = payload + 1 + (packet[payload] & 0xFF);
            if (at + 12 > PACKET || packet[at] != 2) {
                return;
            }
            var end = Math.min(PACKET, at + 3 + ((packet[at + 1] & 0x0F) << 8 | packet[at + 2] & 0xFF) - 4);
            var entry = at + 12 + ((packet[at + 10] & 0x0F) << 8 | packet[at + 11] & 0xFF);
            while (entry + 5 <= end) {
                var type = packet[entry] & 0xFF;
                var pid = (packet[entry + 1] & 0x1F) << 8 | packet[entry + 2] & 0xFF;
                var infoLength = (packet[entry + 3] & 0x0F) << 8 | packet[entry + 4] & 0xFF;
                if (isVideo(type, entry + 5, Math.min(end, entry + 5 + infoLength))) {
                    videoPid = pid;
                    return;
                }
                entry += 5 + infoLength;
            }
        }

        /**
         * Returns whether a stream type, with its descriptors, is video.
         *
         * @param type the {@code stream_type}
         * @param from the index of the first descriptor
         * @param to the index after the descriptors
         * @return {@code true} for MPEG-1, MPEG-2, MPEG-4 Part 2, H.264 (clear or SAMPLE-AES) and HEVC streams, and
         * private streams with the {@code AV01} registration
         */
        private boolean isVideo(int type, int from, int to) {
            if (type == 0x01 || type == 0x02 || type == 0x10 || type == 0x1B || type == 0x24 || type == 0xDB) {
                return true;
            }
            if (type != 0x06) {
                return false;
            }
            for (var at = from; at + 2 <= to; at += 2 + (packet[at + 1] & 0xFF)) {
                if ((packet[at] & 0xFF) == 0x05 && (packet[at + 1] & 0xFF) >= 4 && at + 6 <= to
                        && packet[at + 2] == 'A' && packet[at + 3] == 'V' && packet[at + 4] == '0'
                        && packet[at + 5] == '1') {
                    return true;
                }
            }
            return false;
        }

        /**
         * Completes the key frame being read.
         */
        private void close() {
            if (start >= 0) {
                found.add(new Found(start, last));
                start = -1;
            }
        }

        @Override
        void end() {
            close();
            if (tablesEnd < 0) {
                tablesEnd = position;
            }
        }
    }

    /**
     * Scans top-level ISO base media boxes: each {@code moof} whose first track fragment starts with a sync sample
     * is a key frame, from the {@code moof} through the end of that sample.
     */
    static final class Boxes extends KeyFrameScanner {
        /**
         * The largest {@code moof} read.
         */
        private static final int MAX_MOOF = 16 << 20;

        /**
         * The header of the box being read.
         */
        private final byte[] header = new byte[16];

        /**
         * The number of header bytes read.
         */
        private int headerFilled;

        /**
         * The number of bytes of the current box left to pass, or -1 while its header is read.
         */
        private long remaining = -1;

        /**
         * The offset of the current box.
         */
        private long boxStart;

        /**
         * The {@code moof} being collected, or {@code null}.
         */
        private byte[] moof;

        /**
         * The number of bytes of the {@code moof} collected.
         */
        private int moofFilled;

        /**
         * Whether a box extends to the end of the stream, which ends the scan.
         */
        private boolean done;

        @Override
        void observe(ByteBuffer data) {
            var index = data.position();
            while (index < data.limit() && !done) {
                if (remaining < 0) {
                    var take = Math.min(needed() - headerFilled, data.limit() - index);
                    data.get(index, header, headerFilled, take);
                    headerFilled += take;
                    index += take;
                    position += take;
                    if (headerFilled == needed()) {
                        startBox();
                    }
                    continue;
                }
                var take = (int) Math.min(remaining, data.limit() - index);
                if (moof != null) {
                    data.get(index, moof, moofFilled, take);
                    moofFilled += take;
                }
                index += take;
                position += take;
                remaining -= take;
                if (remaining == 0) {
                    if (moof != null) {
                        fragment();
                    }
                    moof = null;
                    remaining = -1;
                    headerFilled = 0;
                }
            }
        }

        /**
         * {@return the header bytes of the current box: 16 for a 64-bit size, 8 otherwise}
         */
        private int needed() {
            return headerFilled >= 4 && u32(header, 0) == 1 ? 16 : 8;
        }

        /**
         * Starts the payload of a box whose header was read.
         */
        private void startBox() {
            var size = u32(header, 0);
            var length = headerFilled;
            if (size == 1) {
                size = (u32(header, 8) << 32) | u32(header, 12);
            }
            boxStart = position - length;
            if (size == 0 || size < length) {
                done = true;
                return;
            }
            remaining = size - length;
            var moofType = header[4] == 'm' && header[5] == 'o' && header[6] == 'o' && header[7] == 'f';
            if (moofType && size <= MAX_MOOF) {
                moof = new byte[(int) size];
                System.arraycopy(header, 0, moof, 0, length);
                moofFilled = length;
            }
            if (remaining == 0) {
                remaining = -1;
                headerFilled = 0;
                moof = null;
            }
        }

        /**
         * Reads a collected {@code moof}: the size and flags of the first sample of its first track fragment.
         */
        private void fragment() {
            var traf = child(moof, 8, moof.length, "traf");
            if (traf < 0) {
                return;
            }
            var trafEnd = traf + (int) u32(moof, traf);
            var tfhd = child(moof, traf + 8, trafEnd, "tfhd");
            var trun = child(moof, traf + 8, trafEnd, "trun");
            if (tfhd < 0 || trun < 0 || trun + 16 > trafEnd) {
                return;
            }
            var tfhdFlags = (int) u32(moof, tfhd + 8) & 0xFFFFFF;
            var at = tfhd + 16;
            var defaultSize = -1L;
            var defaultFlags = 0L;
            if ((tfhdFlags & 0x01) != 0) {
                at += 8;
            }
            if ((tfhdFlags & 0x02) != 0) {
                at += 4;
            }
            if ((tfhdFlags & 0x08) != 0) {
                at += 4;
            }
            if ((tfhdFlags & 0x10) != 0) {
                defaultSize = u32(moof, at);
                at += 4;
            }
            if ((tfhdFlags & 0x20) != 0) {
                defaultFlags = u32(moof, at);
            }
            var flags = (int) u32(moof, trun + 8) & 0xFFFFFF;
            var count = u32(moof, trun + 12);
            at = trun + 16;
            var dataOffset = 0L;
            if ((flags & 0x01) != 0) {
                dataOffset = (int) u32(moof, at);
                at += 4;
            }
            var sampleFlags = defaultFlags;
            if ((flags & 0x04) != 0) {
                sampleFlags = u32(moof, at);
                at += 4;
            }
            if ((flags & 0x100) != 0) {
                at += 4;
            }
            var size = defaultSize;
            if ((flags & 0x200) != 0 && at + 4 <= trafEnd) {
                size = u32(moof, at);
                at += 4;
            }
            if ((flags & 0x400) != 0 && (flags & 0x04) == 0 && at + 4 <= trafEnd) {
                sampleFlags = u32(moof, at);
            }
            if (count == 0 || size < 0 || dataOffset <= 0 || (sampleFlags & 0x10000) != 0) {
                return;
            }
            found.add(new Found(boxStart, boxStart + dataOffset + size));
        }

        /**
         * Returns the offset of the first child box of a type.
         *
         * @param data the box bytes
         * @param from the offset of the first child
         * @param to the offset after the last child
         * @param type the four-character type
         * @return the offset of the child, or -1
         */
        private static int child(byte[] data, int from, int to, String type) {
            for (var at = from; at + 8 <= to; ) {
                var size = u32(data, at);
                if (size < 8 || at + size > to) {
                    return -1;
                }
                if (data[at + 4] == type.charAt(0) && data[at + 5] == type.charAt(1) && data[at + 6] == type.charAt(2)
                        && data[at + 7] == type.charAt(3)) {
                    return at;
                }
                at += (int) size;
            }
            return -1;
        }

        @Override
        void end() {
        }

        /**
         * Reads a big-endian unsigned 32-bit value.
         *
         * @param data the bytes
         * @param at the offset of the value
         * @return the value
         */
        private static long u32(byte[] data, int at) {
            return (data[at] & 0xFFL) << 24 | (data[at + 1] & 0xFFL) << 16 | (data[at + 2] & 0xFFL) << 8
                    | data[at + 3] & 0xFFL;
        }
    }
}
