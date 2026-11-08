package com.github.auties00.smalt.internal.tags;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.internal.edit.EditPlan;
import com.github.auties00.smalt.internal.header.Crc32Mpeg;
import com.github.auties00.smalt.internal.io.ByteArrays;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;

import java.util.ArrayList;
import java.util.List;

/**
 * Reads, builds and renumbers Ogg pages (RFC 3533).
 *
 * <p>A page is the capture pattern {@code OggS}, a version byte, a flags byte (continued packet,
 * first page, last page), a little-endian 64-bit granule position, serial number and sequence
 * number, a CRC-32, a segment count and that many lacing values, followed by the body. A packet
 * spans consecutive lacing values up to the first value below 255.
 */
final class OggPages {
    /**
     * The size of the fixed page header, before the lacing values.
     */
    static final int HEADER_SIZE = 27;

    /**
     * The flag of a page whose first packet continues from the previous page.
     */
    static final int CONTINUED = 0x01;

    /**
     * The flag of the first page of a logical stream.
     */
    static final int FIRST = 0x02;

    /**
     * The flag of the last page of a logical stream.
     */
    static final int LAST = 0x04;
    private OggPages() {
        throw new AssertionError();
    }

    /**
     * Computes the Ogg CRC of a page whose CRC field is zero.
     *
     * @param page the bytes
     * @param offset the index of the page's first byte
     * @param length the size of the page
     * @return the CRC
     */
    static int crc(byte[] page, int offset, int length) {
        return Crc32Mpeg.update(0, page, offset, length);
    }

    /**
     * Sets the sequence number of a page and recomputes its CRC.
     *
     * @param page the page bytes
     * @param offset the index of the page's first byte
     * @param length the size of the page
     * @param sequence the new sequence number
     */
    static void renumber(byte[] page, int offset, int length, long sequence) {
        ByteArrays.put32le(page, offset + 18, sequence);
        seal(page, offset, length);
    }

    /**
     * Writes the CRC of a page.
     *
     * @param page the page bytes
     * @param offset the index of the page's first byte
     * @param length the size of the page
     */
    static void seal(byte[] page, int offset, int length) {
        ByteArrays.put32le(page, offset + 22, 0);
        ByteArrays.put32le(page, offset + 22, crc(page, offset, length) & 0xFFFFFFFFL);
    }

    /**
     * Reads the page that starts at an offset.
     *
     * @param source the Ogg file
     * @param position the offset of the capture pattern
     * @return the page, or {@code null} when no complete page starts there
     */
    static Page read(MediaSource source, long position) {
        var header = TagIo.readAvailable(source, position, HEADER_SIZE);
        if (header.length < HEADER_SIZE || !TagIo.matches(header, 0, "OggS") || header[4] != 0) {
            return null;
        }
        var segments = header[26] & 0xFF;
        var lacing = TagIo.readAvailable(source, position + HEADER_SIZE, segments);
        if (lacing.length < segments) {
            return null;
        }
        var bodySize = 0;
        for (var value : lacing) {
            bodySize += value & 0xFF;
        }
        var length = HEADER_SIZE + segments + bodySize;
        if (position + length > source.size()) {
            return null;
        }
        return new Page(position, length, header[5] & 0xFF, ByteArrays.i64le(header, 6), (int) ByteArrays.u32le(header, 14),
                ByteArrays.u32le(header, 18), lacing);
    }

    /**
     * Lays packets out on pages of one logical stream, the last packet ending its page.
     *
     * @param packets the packets, in order
     * @param serial the serial number
     * @param firstSequence the sequence number of the first page
     * @param layout the number of lacing values of each page to reproduce, or {@code null} to fill pages with up to 255
     * lacing values; a layout whose pages do not each hold 1 to 255 of the packets' lacing values, all of them in
     * total, is not reproduced either
     * @return the pages
     */
    static List<byte[]> paginate(List<byte[]> packets, int serial, long firstSequence, int[] layout) {
        var lacing = new ArrayList<Integer>();
        var data = new java.io.ByteArrayOutputStream();
        for (var packet : packets) {
            var remaining = packet.length;
            while (remaining >= 255) {
                lacing.add(255);
                remaining -= 255;
            }
            lacing.add(remaining);
            data.writeBytes(packet);
        }
        if (layout != null && !covers(layout, lacing.size())) {
            layout = null;
        }
        var body = data.toByteArray();
        var pages = new ArrayList<byte[]>();
        var segment = 0;
        var bodyOffset = 0;
        var sequence = firstSequence;
        var continued = false;
        var pageIndex = 0;
        while (segment < lacing.size()) {
            var count = layout != null ? layout[pageIndex] : Math.min(255, lacing.size() - segment);
            var size = 0;
            for (var i = 0; i < count; i++) {
                size += lacing.get(segment + i);
            }
            var page = new byte[HEADER_SIZE + count + size];
            page[0] = 'O';
            page[1] = 'g';
            page[2] = 'g';
            page[3] = 'S';
            page[5] = (byte) (continued ? CONTINUED : 0);
            ByteArrays.put32le(page, 14, serial);
            ByteArrays.put32le(page, 18, sequence);
            page[26] = (byte) count;
            for (var i = 0; i < count; i++) {
                page[HEADER_SIZE + i] = (byte) (int) lacing.get(segment + i);
            }
            System.arraycopy(body, bodyOffset, page, HEADER_SIZE + count, size);
            seal(page, 0, page.length);
            pages.add(page);
            continued = lacing.get(segment + count - 1) == 255;
            segment += count;
            bodyOffset += size;
            sequence++;
            pageIndex++;
        }
        return pages;
    }

    /**
     * Returns whether a page layout lays out exactly a number of lacing values, each page holding 1 to 255.
     *
     * @param layout the number of lacing values of each page
     * @param values the number of lacing values to lay out
     * @return {@code true} when the layout fits
     */
    private static boolean covers(int[] layout, int values) {
        var total = 0L;
        for (var count : layout) {
            if (count < 1 || count > 255) {
                return false;
            }
            total += count;
        }
        return total == values;
    }

    /**
     * Holds the header of one page.
     *
     * @param offset the offset of the page
     * @param length the size of the page, header included
     * @param flags the header flags
     * @param granule the granule position
     * @param serial the serial number
     * @param sequence the sequence number
     * @param lacing the lacing values
     */
    record Page(long offset, long length, int flags, long granule, int serial, long sequence, byte[] lacing) {
        /**
         * {@return the offset of the page body}
         */
        long bodyOffset() {
            return offset + HEADER_SIZE + lacing.length;
        }

        /**
         * {@return the offset of the first byte after the page}
         */
        long end() {
            return offset + length;
        }
    }

    /**
     * Copies a range of pages, shifting the sequence numbers of one logical stream and
     * recomputing their CRCs.
     *
     * <p>Pages of other streams are copied unchanged; renumbering stops after the stream's last
     * page. Bytes that do not form a page are copied unchanged, and so is everything after them.
     */
    static final class Renumber extends EditPlan.Transform {
        /**
         * The offset of the first page.
         */
        private final long start;

        /**
         * The number of bytes covered.
         */
        private final long length;

        /**
         * The serial number of the renumbered stream.
         */
        private final int serial;

        /**
         * The amount added to the sequence numbers.
         */
        private final long delta;

        /**
         * Constructs the transformation.
         *
         * @param start the offset of the first page
         * @param length the number of bytes covered
         * @param serial the serial number of the renumbered stream
         * @param delta the amount added to the sequence numbers
         */
        Renumber(long start, long length, int serial, long delta) {
            this.start = start;
            this.length = length;
            this.serial = serial;
            this.delta = delta;
        }

        @Override
        public long length() {
            return length;
        }

        @Override
        public void writeTo(MediaSource source, MediaSink sink, byte[] buffer) {
            var position = start;
            var end = start + length;
            var renumbering = true;
            while (position < end) {
                var page = read(source, position);
                if (page == null || page.end() > end) {
                    EditPlan.copy(source, sink, position, end - position, buffer);
                    return;
                }
                if (renumbering && page.serial() == serial) {
                    var bytes = new byte[(int) page.length()];
                    source.readFully(position, bytes, 0, bytes.length);
                    renumber(bytes, 0, bytes.length, (page.sequence() + delta) & 0xFFFFFFFFL);
                    sink.write(bytes, 0, bytes.length);
                    if ((page.flags() & LAST) != 0) {
                        renumbering = false;
                    }
                } else {
                    EditPlan.copy(source, sink, position, page.length(), buffer);
                }
                position = page.end();
            }
        }
    }

    /**
     * Raises the failure for a structure this editor cannot rewrite.
     *
     * @param message the reason
     * @return never returns
     * @throws MediaException.Unsupported always
     */
    static MediaException.Unsupported unsupported(String message) {
        return new MediaException.Unsupported("ogg", message);
    }
}
