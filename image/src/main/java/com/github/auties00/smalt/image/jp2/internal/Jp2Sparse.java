package com.github.auties00.smalt.image.jp2.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.internal.io.ForwardOnlySource;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.io.MediaSource;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Reads the parts of a JPEG 2000 file that the decode of some of its tiles needs: every box, the main header and
 * every tile-part header of its codestream, and the bodies of the tile-parts of the tiles needed, the other bodies
 * left out.
 *
 * <p>The result is a file of the same boxes whose codestream lists every tile-part, those of the tiles not needed
 * with an empty body, so it parses as the whole file does and decodes the tiles needed as the whole file does. A
 * raw codestream, or a JP2 or JPX file whose one codestream lies in a contiguous codestream box, reads this way;
 * other files, and sources that read their input once, are read whole.
 */
public final class Jp2Sparse {
    /**
     * The start of tile-part marker.
     */
    private static final int SOT = 0xFF90;

    /**
     * The start of data marker.
     */
    private static final int SOD = 0xFF93;

    /**
     * The end of codestream marker.
     */
    private static final int EOC = 0xFFD9;

    /**
     * The start of codestream marker.
     */
    private static final int SOC = 0xFF4F;

    /**
     * The image and tile size marker.
     */
    private static final int SIZ = 0xFF51;

    /**
     * The most boxes a file is walked through before it is read whole.
     */
    private static final int MAX_BOXES = 4096;

    /**
     * Prevents instantiation.
     */
    private Jp2Sparse() {
        throw new AssertionError();
    }

    /**
     * Reads a file without the tile-part bodies of the tiles a decode does not need.
     *
     * @param source the file
     * @param limits the bound on what is read
     * @param needed given the file with every tile-part body empty, returns whether each tile of its codestream is
     * needed, or {@code null} when every tile is
     * @return the file with the bodies of the tiles not needed left out, or {@code null} when the file must be read
     * whole: a source that reads its input once or of unknown size, a file of another layout, or every tile needed
     * @throws MediaException.Malformed if the box structure or the codestream headers are invalid
     * @throws MediaException.LimitExceeded if what is read exceeds the limits
     * @throws MediaException.Io if reading the source fails
     */
    public static byte[] read(MediaSource source, MediaLimits limits, Function<byte[], boolean[]> needed) {
        var size = source.size();
        if (size < 16 || source instanceof ForwardOnlySource) {
            return null;
        }
        var reader = new Reader(source, size, limits);
        var boxes = new ArrayList<long[]>();
        long codestreamStart;
        long codestreamEnd;
        if (reader.u16(0) == SOC) {
            codestreamStart = 0;
            codestreamEnd = size;
        } else {
            codestreamStart = -1;
            codestreamEnd = -1;
            var position = 0L;
            while (position < size) {
                if (boxes.size() >= MAX_BOXES || size - position < 8) {
                    return null;
                }
                var length = reader.u32(position);
                var type = (int) reader.u32(position + 4);
                var header = 8L;
                if (length == 1) {
                    if (size - position < 16) {
                        return null;
                    }
                    length = reader.u64(position + 8);
                    header = 16;
                } else if (length == 0) {
                    length = size - position;
                }
                if (length < header || length > size - position) {
                    return null;
                }
                if (type == fourCc("ftbl") || type == fourCc("jp2c") && codestreamStart >= 0) {
                    return null;
                }
                if (type == fourCc("jp2c")) {
                    codestreamStart = position + header;
                    codestreamEnd = position + length;
                }
                boxes.add(new long[]{position, length, header, type});
                position += length;
            }
            if (codestreamStart < 0) {
                return null;
            }
        }
        var codestream = ScannedCodestream.scan(reader, codestreamStart, codestreamEnd);
        if (codestream == null) {
            return null;
        }
        var headersOnly = assemble(reader, boxes, codestream, null);
        var wanted = needed.apply(headersOnly);
        if (wanted == null || every(wanted)) {
            return null;
        }
        return assemble(reader, boxes, codestream, wanted);
    }

    /**
     * Returns whether every tile is needed.
     *
     * @param wanted whether each tile is needed
     * @return {@code true} when no tile is left out
     */
    private static boolean every(boolean[] wanted) {
        for (var tile : wanted) {
            if (!tile) {
                return false;
            }
        }
        return true;
    }

    /**
     * Builds the file from its boxes and its codestream.
     *
     * @param reader the reader of the source
     * @param boxes the position, length, header length and type of each box, empty for a raw codestream
     * @param codestream the scanned codestream
     * @param wanted whether each tile is needed, or {@code null} for no tile-part body at all
     * @return the file
     */
    private static byte[] assemble(Reader reader, List<long[]> boxes, ScannedCodestream codestream,
                                   boolean[] wanted) {
        var stream = codestream.write(reader, wanted);
        if (boxes.isEmpty()) {
            return stream;
        }
        var out = new ByteArrayOutputStream();
        for (var box : boxes) {
            if ((int) box[3] == fourCc("jp2c")) {
                var length = 8L + stream.length;
                if (length > 0xFFFFFFFFL) {
                    writeU32(out, 1);
                    writeU32(out, box[3]);
                    writeU32(out, (length + 8) >>> 32);
                    writeU32(out, (length + 8) & 0xFFFFFFFFL);
                } else {
                    writeU32(out, length);
                    writeU32(out, box[3]);
                }
                out.writeBytes(stream);
            } else {
                out.writeBytes(reader.bytes(box[0], box[1]));
            }
            reader.checkTotal(out.size());
        }
        return out.toByteArray();
    }

    /**
     * Returns the code of a four-character box type.
     *
     * @param type the box type, four ASCII letters
     * @return its big-endian code
     */
    private static int fourCc(String type) {
        return type.charAt(0) << 24 | type.charAt(1) << 16 | type.charAt(2) << 8 | type.charAt(3);
    }

    /**
     * Writes a big-endian 32-bit value.
     *
     * @param out the destination
     * @param value the value, below {@code 2^32}
     */
    private static void writeU32(ByteArrayOutputStream out, long value) {
        out.write((int) (value >>> 24));
        out.write((int) (value >>> 16));
        out.write((int) (value >>> 8));
        out.write((int) value);
    }

    /**
     * Reads big-endian values and byte ranges of a source, bounded by the limits.
     */
    private static final class Reader {
        /**
         * The source.
         */
        private final MediaSource source;

        /**
         * The size of the source.
         */
        private final long size;

        /**
         * The bound on what is read.
         */
        private final MediaLimits limits;

        /**
         * A scratch buffer for values.
         */
        private final byte[] scratch = new byte[8];

        /**
         * Constructs the reader.
         *
         * @param source the file read
         * @param size its size
         * @param limits the bound on what is read
         */
        private Reader(MediaSource source, long size, MediaLimits limits) {
            this.source = source;
            this.size = size;
            this.limits = limits;
        }

        /**
         * Reads bytes of the source.
         *
         * @param position the offset of the first byte
         * @param length the number of bytes
         * @return the bytes
         * @throws MediaException.Malformed if the range lies past the end of the source
         * @throws MediaException.LimitExceeded if the range exceeds the limits
         */
        private byte[] bytes(long position, long length) {
            if (position < 0 || length < 0 || position + length > size) {
                throw new MediaException.Malformed("truncated JPEG 2000 file");
            }
            LimitChecks.checkAllocation(limits, length);
            var data = new byte[(int) length];
            source.readFully(position, data, 0, data.length);
            return data;
        }

        /**
         * Checks the size of the file built so far against the limits.
         *
         * @param total the size in bytes
         * @throws MediaException.LimitExceeded if it exceeds the limits
         */
        private void checkTotal(long total) {
            LimitChecks.checkAllocation(limits, total);
        }

        /**
         * Reads a big-endian value.
         *
         * @param position the offset of its first byte
         * @param count the number of bytes, at most 8
         * @return the value
         */
        private long value(long position, int count) {
            if (position < 0 || position + count > size) {
                throw new MediaException.Malformed("truncated JPEG 2000 file");
            }
            source.readFully(position, scratch, 0, count);
            var value = 0L;
            for (var i = 0; i < count; i++) {
                value = value << 8 | (scratch[i] & 0xFF);
            }
            return value;
        }

        /**
         * {@return the big-endian 16-bit value at a position}
         *
         * @param position the offset
         */
        private int u16(long position) {
            return (int) value(position, 2);
        }

        /**
         * {@return the big-endian 32-bit value at a position}
         *
         * @param position the offset
         */
        private long u32(long position) {
            return value(position, 4);
        }

        /**
         * {@return the big-endian 64-bit value at a position}
         *
         * @param position the offset
         */
        private long u64(long position) {
            return value(position, 8);
        }
    }

    /**
     * Holds the main header and the tile-part headers of a codestream, and where the tile-part bodies lie.
     *
     * @param mainHeader the bytes from {@code SOC} to the first {@code SOT}
     * @param parts the tile-parts, in codestream order
     */
    private record ScannedCodestream(byte[] mainHeader, List<TilePart> parts) {
        /**
         * Scans the headers of a codestream as the codestream parser reads them.
         *
         * <p>The scan keeps every tile-part the parser keeps: it stops where the parser stops reading tile-parts
         * (at {@code EOC}, at the end of the codestream, and at the first tile-part whose header is invalid), so
         * the codestream it writes parses to the same tile-parts. A main header the parser rejects, or a
         * tile-part of a tile the image does not have, makes the scan give up.
         *
         * @param reader the reader of the source
         * @param start the offset of {@code SOC}
         * @param end the end of the codestream
         * @return the headers, or {@code null} when the codestream must be read whole
         */
        private static ScannedCodestream scan(Reader reader, long start, long end) {
            if (end - start < 4 || reader.u16(start) != SOC || reader.u16(start + 2) != SIZ) {
                return null;
            }
            var tiles = tileCount(reader, start + 2, end);
            if (tiles <= 0) {
                return null;
            }
            var position = start + 2;
            while (true) {
                if (end - position < 2) {
                    return null;
                }
                var marker = reader.u16(position);
                if (marker == SOT) {
                    break;
                }
                if ((marker & 0xFF00) != 0xFF00 || marker < 0xFF30 || marker == EOC) {
                    return null;
                }
                if (marker <= 0xFF3F) {
                    position += 2;
                    continue;
                }
                var length = segmentLength(reader, position, end);
                if (length < 0) {
                    return null;
                }
                position += 2 + length;
            }
            var mainHeader = reader.bytes(start, position - start);
            var parts = new ArrayList<TilePart>();
            while (end - position >= 2) {
                var marker = reader.u16(position);
                if (marker != SOT || end - position < 12 || reader.u16(position + 2) != 10) {
                    break;
                }
                var tile = reader.u16(position + 4);
                var partLength = reader.u32(position + 6);
                if (tile >= tiles) {
                    return null;
                }
                if (partLength != 0 && partLength < 14) {
                    break;
                }
                var partEnd = partLength == 0 ? end : Math.min(end, position + partLength);
                var cursor = position + 12;
                var body = -1L;
                var valid = true;
                while (end - cursor >= 2) {
                    var headerMarker = reader.u16(cursor);
                    if (headerMarker == SOD) {
                        body = cursor + 2;
                        break;
                    }
                    if ((headerMarker & 0xFF00) != 0xFF00 || headerMarker < 0xFF30) {
                        valid = false;
                        break;
                    }
                    if (headerMarker <= 0xFF3F) {
                        cursor += 2;
                        continue;
                    }
                    var length = segmentLength(reader, cursor, end);
                    if (length < 0) {
                        valid = false;
                        break;
                    }
                    cursor += 2 + length;
                }
                if (!valid || body < 0) {
                    break;
                }
                if (partLength == 0) {
                    partEnd = end;
                    if (partEnd - body >= 2 && reader.u16(partEnd - 2) == EOC) {
                        partEnd -= 2;
                    }
                }
                if (partEnd < body) {
                    partEnd = body;
                }
                parts.add(new TilePart(tile, reader.bytes(position, body - position), body, partEnd - body));
                if (partLength == 0 || partEnd >= end) {
                    break;
                }
                position = partEnd;
            }
            return parts.isEmpty() ? null : new ScannedCodestream(mainHeader, parts);
        }

        /**
         * Returns the length of a marker segment as the codestream parser accepts it.
         *
         * @param reader the reader of the source
         * @param position the offset of the marker
         * @param end the end of the codestream
         * @return the length, or {@code -1} when the parser rejects it
         */
        private static int segmentLength(Reader reader, long position, long end) {
            if (end - position < 4) {
                return -1;
            }
            var length = reader.u16(position + 2);
            return length < 2 || length > end - position - 2 ? -1 : length;
        }

        /**
         * Returns the number of tiles the {@code SIZ} segment of a codestream declares.
         *
         * @param reader the reader of the source
         * @param position the offset of the {@code SIZ} marker
         * @param end the end of the codestream
         * @return the tile count, or {@code -1} when the segment is invalid
         */
        private static long tileCount(Reader reader, long position, long end) {
            var length = segmentLength(reader, position, end);
            if (length < 38) {
                return -1;
            }
            var x1 = reader.u32(position + 6);
            var y1 = reader.u32(position + 10);
            var tileWidth = reader.u32(position + 22);
            var tileHeight = reader.u32(position + 26);
            var tileX0 = reader.u32(position + 30);
            var tileY0 = reader.u32(position + 34);
            if (tileWidth == 0 || tileHeight == 0 || tileX0 >= x1 || tileY0 >= y1) {
                return -1;
            }
            return Math.ceilDiv(x1 - tileX0, tileWidth) * Math.ceilDiv(y1 - tileY0, tileHeight);
        }

        /**
         * Writes the codestream with the bodies of the tiles needed.
         *
         * @param reader the reader of the source
         * @param wanted whether each tile is needed, or {@code null} for no body at all
         * @return the codestream bytes, ending with {@code EOC}
         */
        private byte[] write(Reader reader, boolean[] wanted) {
            var out = new ByteArrayOutputStream();
            out.writeBytes(mainHeader);
            for (var part : parts) {
                var keep = wanted != null && part.tile() < wanted.length && wanted[part.tile()];
                var length = part.header().length + (keep ? part.bodyLength() : 0);
                var header = part.header().clone();
                header[6] = (byte) (length >>> 24);
                header[7] = (byte) (length >>> 16);
                header[8] = (byte) (length >>> 8);
                header[9] = (byte) length;
                out.writeBytes(header);
                if (keep) {
                    out.writeBytes(reader.bytes(part.body(), part.bodyLength()));
                }
                reader.checkTotal(out.size());
            }
            out.write(EOC >> 8);
            out.write(EOC & 0xFF);
            return out.toByteArray();
        }
    }

    /**
     * Describes one tile-part of a codestream.
     *
     * @param tile the index of its tile
     * @param header its bytes from {@code SOT} to {@code SOD}, both included
     * @param body the offset of its body in the source
     * @param bodyLength the length of its body
     */
    private record TilePart(int tile, byte[] header, long body, long bodyLength) {
    }
}
