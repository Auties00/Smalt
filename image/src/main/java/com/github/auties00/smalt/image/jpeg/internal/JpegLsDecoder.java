/* Ports CharLS (commit 1153c25: jpeg_stream_reader.cpp, scan_decoder_impl.hpp, scan_decoder_core.hpp,
 * color_transform.hpp). Copyright (c) 2007 Jan de Vaan and Victor Derks (Team CharLS); BSD 3-Clause License.
 * Modified for Smalt. */
package com.github.auties00.smalt.image.jpeg.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.internal.task.ParallelBands;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Decodes a JPEG-LS stream (ITU-T T.87, {@code SOF55}).
 *
 * <p>The decoder supports samples of 2 to 16 bits, lossless and near-lossless coding, frames of 1 to 255
 * components with sampling factors from 1 to 4, scans of one component and line-interleaved or
 * sample-interleaved scans of up to four, the preset coding parameters ({@code MAXVAL}, {@code T1}, {@code T2},
 * {@code T3}, {@code RESET}) of {@code LSE} segments, which may change between scans, restart intervals of 2 to
 * 4 bytes, frame sizes defined by the oversize-dimension {@code LSE} segment or, for the height, by a {@code DNL}
 * segment after the first scan, the point transform, and the sample mapping tables of {@code LSE} segments
 * (with their continuations). A mapping table of 1 or 2-byte entries maps the samples of a component to 8 or
 * 16-bit values; a single-component frame whose table has 3-byte entries is a palette image of 8-bit red, green
 * and blue components. The color transforms HP1, HP2 and HP3 that HP's and CharLS's encoders signal in an
 * {@code APP8 "mrfx"} segment are inverted, so a frame using one decodes to red, green and blue. The last
 * minimum coded unit of a line-interleaved scan holds the lines that complete it (T.87 B.4), which are decoded and
 * dropped.
 *
 * <p>Entropy-coded data never makes the decode fail: data that ends early or is corrupt decodes with zero bits
 * in place of the missing ones. Damage to the headers of a scan after the first one ends the frame there, and
 * the components no scan has coded by then decode as zero samples.
 *
 * <p>The scans of a frame coded one component per scan are decoded on several threads; the output does not depend
 * on the number of threads.
 *
 * <p>Instances are not thread-safe.
 */
public final class JpegLsDecoder implements LosslessSamples {
    /**
     * The stream.
     */
    private final byte[] data;

    /**
     * The index after the stream.
     */
    private final int end;

    /**
     * The resource bounds of the buffers the decode allocates.
     */
    private final MediaLimits limits;

    /**
     * The coded sample precision {@code P}.
     */
    private final int precision;

    /**
     * The frame width.
     */
    private int width;

    /**
     * The frame height.
     */
    private int height;

    /**
     * The identifier of each frame component.
     */
    private final int[] ids;

    /**
     * The horizontal sampling factor of each frame component.
     */
    private final int[] hs;

    /**
     * The vertical sampling factor of each frame component.
     */
    private final int[] vs;

    /**
     * The largest horizontal sampling factor.
     */
    private final int hMax;

    /**
     * The largest vertical sampling factor.
     */
    private final int vMax;

    /**
     * The scans, in stream order.
     */
    private final List<Scan> scans;

    /**
     * The HP color transform, 0 for none or 1 to 3.
     */
    private final int colorTransform;

    /**
     * The mapping table of each frame component, or {@code null} entries for none.
     */
    private final MappingTable[] mapping;

    /**
     * Whether the frame is a palette image: one component mapped through 3-byte entries.
     */
    private final boolean palette;

    /**
     * The precision of the decoded samples.
     */
    private final int outputPrecision;

    /**
     * Holds one scan header with the parameters in force at it.
     *
     * @param components the frame index of each scan component, in scan order
     * @param tables the mapping table identifier of each scan component, 0 for none
     * @param near the near-lossless bound
     * @param interleave the interleave mode: 0 none, 1 line, 2 sample
     * @param pointTransform the point transform
     * @param maxval the largest sample value
     * @param thresholds {@code T1}, {@code T2}, {@code T3} and {@code RESET}
     * @param restartInterval the restart interval in minimum coded units, or 0
     * @param start the index of the first entropy-coded byte
     */
    private record Scan(int[] components, int[] tables, int near, int interleave, int pointTransform, int maxval,
                        int[] thresholds, int restartInterval, int start) {
    }

    /**
     * Holds a mapping table.
     *
     * @param entrySize the number of bytes of an entry, {@code Wt}
     * @param values the entries, each read big-endian
     * @param rgb the red, green and blue values of each entry of a 3-byte table, or {@code null}
     */
    private record MappingTable(int entrySize, int[] values, int[][] rgb) {
    }

    /**
     * Parses the whole marker structure of a stream: the frame header, the preset parameters and mapping tables,
     * and every scan header.
     *
     * @param data the bytes holding the stream
     * @param offset the index of its {@code SOI} marker
     * @param length the length of the stream
     * @param limits the resource bounds of the buffers the decode allocates
     * @throws MediaException.Malformed if the headers before the first scan are corrupt, the frame has no height
     * or no scan, or a scan refers to an undefined or too short mapping table
     * @throws MediaException.Unsupported if the stream uses JPEG-LS part 2 extensions, a mapping table with
     * entries of other sizes than 1, 2 or 3 bytes, mapped components of different precisions, or a color
     * transform other than HP1, HP2 and HP3
     */
    public JpegLsDecoder(byte[] data, int offset, int length, MediaLimits limits) {
        if (offset < 0 || length < 4 || offset > data.length - length) {
            throw new MediaException.Malformed("truncated JPEG-LS stream");
        }
        if ((data[offset] & 0xFF) != 0xFF || (data[offset + 1] & 0xFF) != 0xD8) {
            throw new MediaException.Malformed("JPEG-LS stream does not start with SOI");
        }
        this.data = data;
        this.end = offset + length;
        this.limits = limits;
        var parser = new Parser(offset + 2);
        parser.run();
        if (parser.ids == null) {
            throw new MediaException.Malformed("JPEG-LS stream has no frame header");
        }
        if (parser.scans.isEmpty()) {
            throw new MediaException.Malformed("JPEG-LS stream has no scan");
        }
        if (parser.width <= 0 || parser.height <= 0) {
            throw new MediaException.Malformed("JPEG-LS frame of " + parser.width + "x" + parser.height
                    + " samples");
        }
        this.precision = parser.precision;
        this.width = parser.width;
        this.height = parser.height;
        this.ids = parser.ids;
        this.hs = parser.hs;
        this.vs = parser.vs;
        var horizontal = 1;
        var vertical = 1;
        for (var k = 0; k < ids.length; k++) {
            horizontal = Math.max(horizontal, hs[k]);
            vertical = Math.max(vertical, vs[k]);
        }
        this.hMax = horizontal;
        this.vMax = vertical;
        this.scans = parser.scans;
        this.colorTransform = parser.colorTransform;
        this.mapping = new MappingTable[ids.length];
        var mappedPrecision = 0;
        var unmapped = false;
        var paletteTable = false;
        for (var scan : scans) {
            for (var k = 0; k < scan.components().length; k++) {
                var tid = scan.tables()[k];
                if (tid == 0) {
                    continue;
                }
                var table = parser.tables.get(tid);
                if (table == null) {
                    throw new MediaException.Malformed("JPEG-LS scan refers to the undefined mapping table " + tid);
                }
                var entries = table.values().length;
                if (entries <= scan.maxval()) {
                    throw new MediaException.Malformed("JPEG-LS mapping table " + tid + " has " + entries
                            + " entries, fewer than MAXVAL + 1 = " + (scan.maxval() + 1));
                }
                mapping[scan.components()[k]] = table;
            }
        }
        for (var component = 0; component < ids.length; component++) {
            var table = mapping[component];
            if (table == null) {
                unmapped = true;
                continue;
            }
            var bits = table.entrySize() == 3 ? 8 : 8 * table.entrySize();
            if (mappedPrecision != 0 && mappedPrecision != bits) {
                throw new MediaException.Unsupported("jpeg-ls mapping table",
                        "JPEG-LS components mapped to different precisions are not supported");
            }
            mappedPrecision = bits;
            paletteTable |= table.entrySize() == 3;
        }
        if (paletteTable && ids.length != 1) {
            throw new MediaException.Unsupported("jpeg-ls mapping table",
                    "3-byte JPEG-LS mapping table entries are supported only as the palette of a one-component frame");
        }
        if (mappedPrecision != 0 && unmapped && mappedPrecision != precision) {
            throw new MediaException.Unsupported("jpeg-ls mapping table",
                    "JPEG-LS frames whose mapped and unmapped components differ in precision are not supported");
        }
        this.palette = paletteTable;
        this.outputPrecision = mappedPrecision != 0 ? mappedPrecision : precision;
        if (colorTransform != 0) {
            checkColorTransform();
        }
    }

    /**
     * Returns whether a stream is a JPEG-LS stream: its first frame header, after {@code SOI} and any table and
     * miscellaneous segments, is {@code SOF55}.
     *
     * @param data the bytes holding the stream
     * @param offset the index of its {@code SOI} marker
     * @param length the length of the stream
     * @return {@code true} for a JPEG-LS stream; {@code false} for any other input, a truncated one included
     */
    public static boolean isJpegLs(byte[] data, int offset, int length) {
        var end = offset + length;
        if (offset < 0 || length < 4 || end > data.length || (data[offset] & 0xFF) != 0xFF
                || (data[offset + 1] & 0xFF) != 0xD8) {
            return false;
        }
        var at = offset + 2;
        while (at + 4 <= end) {
            if ((data[at] & 0xFF) != 0xFF) {
                return false;
            }
            var code = data[at + 1] & 0xFF;
            if (code == 0xFF) {
                at++;
                continue;
            }
            if (code == 0xF7) {
                return true;
            }
            if ((code >= 0xC0 && code <= 0xCF && code != 0xC4 && code != 0xC8 && code != 0xCC) || code == 0xDA
                    || code == 0xD9) {
                return false;
            }
            at += 2 + (((data[at + 2] & 0xFF) << 8) | (data[at + 3] & 0xFF));
        }
        return false;
    }

    /**
     * Checks that the frame can carry the HP color transform it signals.
     *
     * @throws MediaException.Malformed if it cannot
     */
    private void checkColorTransform() {
        var valid = ids.length == 3 && (precision == 8 || precision == 16) && hMax == 1 && vMax == 1
                && scans.size() == 1 && scans.getFirst().components().length == 3
                && scans.getFirst().interleave() != 0 && scans.getFirst().near() == 0;
        if (!valid) {
            throw new MediaException.Malformed("the JPEG-LS frame cannot carry the HP" + colorTransform
                    + " color transform it signals");
        }
    }

    /**
     * Walks the marker segments of a stream.
     */
    private final class Parser {
        /**
         * The index of the next byte.
         */
        private int position;

        /**
         * The sample precision.
         */
        int precision;

        /**
         * The frame width, 0 until defined.
         */
        int width;

        /**
         * The frame height, 0 until defined.
         */
        int height;

        /**
         * The component identifiers, or {@code null} before the frame header.
         */
        int[] ids;

        /**
         * The horizontal sampling factors.
         */
        int[] hs;

        /**
         * The vertical sampling factors.
         */
        int[] vs;

        /**
         * The raw preset coding parameters: {@code MAXVAL}, {@code T1}, {@code T2}, {@code T3}, {@code RESET},
         * 0 meaning the default.
         */
        final int[] presets = new int[5];

        /**
         * The mapping tables by identifier.
         */
        final Map<Integer, MappingTable> tables = new HashMap<>();

        /**
         * The identifier of the last mapping table specified, which a continuation extends, or 0.
         */
        int lastTable;

        /**
         * The restart interval in force.
         */
        int restartInterval;

        /**
         * The HP color transform.
         */
        int colorTransform;

        /**
         * The scans read so far.
         */
        final List<Scan> scans = new ArrayList<>();

        /**
         * Which frame components a scan has coded.
         */
        boolean[] coded;

        /**
         * Constructs a parser.
         *
         * @param position the index after {@code SOI}
         */
        Parser(int position) {
            this.position = position;
        }

        /**
         * Parses segments up to {@code EOI} or the end of the stream; once a scan was read, a corrupt segment
         * ends the parse instead of failing it.
         */
        void run() {
            while (true) {
                try {
                    if (!next()) {
                        return;
                    }
                } catch (MediaException.Malformed exception) {
                    if (scans.isEmpty()) {
                        throw exception;
                    }
                    return;
                }
            }
        }

        /**
         * Parses one marker segment, or the entropy-coded data of a scan after its header.
         *
         * @return {@code false} at {@code EOI} or the end of the stream
         */
        private boolean next() {
            var code = nextMarker();
            if (code < 0 || code == 0xD9) {
                return false;
            }
            if (code == 0xD8) {
                throw new MediaException.Malformed("duplicate JPEG-LS SOI marker");
            }
            if (code == 0x01 || (code >= 0xD0 && code <= 0xD7)) {
                return true;
            }
            if (position + 2 > end) {
                throw new MediaException.Malformed("truncated JPEG-LS segment 0x" + Integer.toHexString(code));
            }
            var length = u16(position);
            if (length < 2 || position + length > end) {
                throw new MediaException.Malformed("truncated JPEG-LS segment 0x" + Integer.toHexString(code));
            }
            var body = position + 2;
            var bodyEnd = position + length;
            switch (code) {
                case 0xF7 -> frame(body, bodyEnd);
                case 0xF8 -> presets(body, bodyEnd);
                case 0xDD -> restartInterval = variable(body, bodyEnd, "DRI");
                case 0xDC -> {
                    var lines = variable(body, bodyEnd, "DNL");
                    if (height == 0) {
                        height = lines;
                    }
                }
                case 0xDA -> {
                    scan(body, bodyEnd);
                    position = scanEnd(bodyEnd);
                    return true;
                }
                case 0xE8 -> {
                    if (bodyEnd - body == 5 && data[body] == 'm' && data[body + 1] == 'r' && data[body + 2] == 'f'
                            && data[body + 3] == 'x') {
                        colorTransform = data[body + 4] & 0xFF;
                        if (colorTransform > 3) {
                            throw new MediaException.Unsupported("jpeg-ls color transform",
                                    "the JPEG-LS color transform " + colorTransform + " is not supported");
                        }
                    }
                }
                case 0xF9 -> throw new MediaException.Unsupported("jpeg-ls extensions",
                        "JPEG-LS part 2 (ITU-T T.870) streams are not supported");
                default -> {
                    var sof = code >= 0xC0 && code <= 0xCF && code != 0xC4 && code != 0xC8 && code != 0xCC;
                    if (sof) {
                        throw new MediaException.Malformed("a JPEG-LS stream holds a second frame header");
                    }
                }
            }
            position = bodyEnd;
            return true;
        }

        /**
         * Finds the next marker, skipping bytes that are not part of one.
         *
         * @return the marker code, with {@link #position} just after it, or {@code -1} at the end
         */
        private int nextMarker() {
            while (position < end) {
                if ((data[position] & 0xFF) != 0xFF) {
                    position++;
                    continue;
                }
                var p = position + 1;
                while (p < end && (data[p] & 0xFF) == 0xFF) {
                    p++;
                }
                if (p >= end) {
                    position = end;
                    return -1;
                }
                var code = data[p] & 0xFF;
                position = p + 1;
                if (code != 0) {
                    return code;
                }
            }
            return -1;
        }

        /**
         * Parses the frame header.
         *
         * @param body the index of the segment's first byte after the length field
         * @param bodyEnd the index after the segment
         */
        private void frame(int body, int bodyEnd) {
            if (ids != null) {
                throw new MediaException.Malformed("duplicate JPEG-LS frame header");
            }
            if (bodyEnd - body < 6) {
                throw new MediaException.Malformed("truncated SOF55 segment");
            }
            precision = data[body] & 0xFF;
            if (precision < 2 || precision > 16) {
                throw new MediaException.Malformed("invalid JPEG-LS sample precision " + precision);
            }
            var lines = u16(body + 1);
            var columns = u16(body + 3);
            if (lines != 0) {
                height = lines;
            }
            if (columns != 0) {
                width = columns;
            }
            var count = data[body + 5] & 0xFF;
            if (count == 0 || bodyEnd - body != 6 + 3 * count) {
                throw new MediaException.Malformed("SOF55 segment does not match its " + count + " components");
            }
            ids = new int[count];
            hs = new int[count];
            vs = new int[count];
            coded = new boolean[count];
            for (var k = 0; k < count; k++) {
                var at = body + 6 + 3 * k;
                ids[k] = data[at] & 0xFF;
                hs[k] = (data[at + 1] & 0xFF) >>> 4;
                vs[k] = data[at + 1] & 15;
                if (hs[k] < 1 || hs[k] > 4 || vs[k] < 1 || vs[k] > 4) {
                    throw new MediaException.Malformed("invalid JPEG-LS sampling factors " + hs[k] + "x" + vs[k]);
                }
                for (var other = 0; other < k; other++) {
                    if (ids[other] == ids[k]) {
                        throw new MediaException.Malformed("duplicate JPEG-LS component identifier " + ids[k]);
                    }
                }
            }
        }

        /**
         * Parses an {@code LSE} segment.
         *
         * @param body the index of the segment's first byte after the length field
         * @param bodyEnd the index after the segment
         */
        private void presets(int body, int bodyEnd) {
            if (bodyEnd <= body) {
                throw new MediaException.Malformed("empty JPEG-LS LSE segment");
            }
            var id = data[body] & 0xFF;
            switch (id) {
                case 1 -> {
                    if (bodyEnd - body != 11) {
                        throw new MediaException.Malformed("invalid JPEG-LS preset coding parameters segment");
                    }
                    for (var k = 0; k < 5; k++) {
                        presets[k] = u16(body + 1 + 2 * k);
                    }
                }
                case 2, 3 -> {
                    if (bodyEnd - body < 3) {
                        throw new MediaException.Malformed("truncated JPEG-LS mapping table segment");
                    }
                    var tid = data[body + 1] & 0xFF;
                    var wt = data[body + 2] & 0xFF;
                    if (tid == 0 || wt == 0) {
                        throw new MediaException.Malformed("invalid JPEG-LS mapping table " + tid + " of " + wt
                                + "-byte entries");
                    }
                    if (wt > 3) {
                        throw new MediaException.Unsupported("jpeg-ls mapping table", "JPEG-LS mapping tables of "
                                + wt + "-byte entries are not supported");
                    }
                    var previous = id == 3 ? tables.get(tid) : null;
                    if (id == 3 && (previous == null || tid != lastTable || previous.entrySize() != wt)) {
                        throw new MediaException.Malformed("JPEG-LS mapping table continuation without its table "
                                + tid);
                    }
                    tables.put(tid, table(wt, previous, body + 3, bodyEnd));
                    lastTable = tid;
                }
                case 4 -> {
                    if (bodyEnd - body < 2) {
                        throw new MediaException.Malformed("truncated JPEG-LS oversize dimension segment");
                    }
                    var size = data[body + 1] & 0xFF;
                    if (size < 2 || size > 4 || bodyEnd - body != 2 + 2 * size) {
                        throw new MediaException.Malformed("invalid JPEG-LS oversize dimension segment");
                    }
                    var lines = unsigned(body + 2, size);
                    var columns = unsigned(body + 2 + size, size);
                    if (lines > Integer.MAX_VALUE || columns > Integer.MAX_VALUE) {
                        throw new MediaException.LimitExceeded("JPEG-LS frame of " + columns + "x" + lines
                                + " samples");
                    }
                    if (lines != 0) {
                        height = (int) lines;
                    }
                    if (columns != 0) {
                        width = (int) columns;
                    }
                }
                default -> throw new MediaException.Unsupported("jpeg-ls extensions", "JPEG-LS preset parameters of"
                        + " type " + id + " (ITU-T T.870) are not supported");
            }
        }

        /**
         * Builds a mapping table, extending a previous one.
         *
         * @param wt the entry size
         * @param previous the table to extend, or {@code null}
         * @param from the index of the first entry byte
         * @param to the index after the last entry byte
         * @return the table
         */
        private MappingTable table(int wt, MappingTable previous, int from, int to) {
            var added = (to - from) / wt;
            var kept = previous == null ? 0 : previous.values().length;
            if ((long) kept + added > 65536) {
                throw new MediaException.Malformed("JPEG-LS mapping table of more than 65536 entries");
            }
            var values = previous == null ? new int[added] : Arrays.copyOf(previous.values(), kept + added);
            var rgb = wt != 3 ? null : previous == null ? new int[3][added]
                    : new int[][]{Arrays.copyOf(previous.rgb()[0], kept + added),
                    Arrays.copyOf(previous.rgb()[1], kept + added), Arrays.copyOf(previous.rgb()[2], kept + added)};
            for (var k = 0; k < added; k++) {
                var at = from + k * wt;
                var value = 0;
                for (var b = 0; b < wt; b++) {
                    value = (value << 8) | (data[at + b] & 0xFF);
                }
                values[kept + k] = value;
                if (rgb != null) {
                    for (var b = 0; b < 3; b++) {
                        rgb[b][kept + k] = data[at + b] & 0xFF;
                    }
                }
            }
            return new MappingTable(wt, values, rgb);
        }

        /**
         * Reads a {@code DRI} or {@code DNL} value of 2 to 4 bytes.
         *
         * @param body the index of the value
         * @param bodyEnd the index after the segment
         * @param name the segment name, for the failure message
         * @return the value
         */
        private int variable(int body, int bodyEnd, String name) {
            var size = bodyEnd - body;
            if (size < 2 || size > 4) {
                throw new MediaException.Malformed("invalid JPEG-LS " + name + " segment length " + (size + 2));
            }
            var value = unsigned(body, size);
            if (value > Integer.MAX_VALUE) {
                throw new MediaException.LimitExceeded("JPEG-LS " + name + " value " + value);
            }
            return (int) value;
        }

        /**
         * Parses a scan header and records the scan with the parameters in force.
         *
         * @param body the index of the segment's first byte after the length field
         * @param bodyEnd the index after the segment
         */
        private void scan(int body, int bodyEnd) {
            if (ids == null) {
                throw new MediaException.Malformed("JPEG-LS scan before the frame header");
            }
            if (width == 0) {
                throw new MediaException.Malformed("JPEG-LS frame width undefined at the first scan");
            }
            if (bodyEnd <= body) {
                throw new MediaException.Malformed("empty JPEG-LS scan header");
            }
            var count = data[body] & 0xFF;
            if (count < 1 || count > 4 || bodyEnd - body != 4 + 2 * count) {
                throw new MediaException.Malformed("invalid JPEG-LS scan of " + count + " components");
            }
            var order = new int[count];
            var tables = new int[count];
            for (var k = 0; k < count; k++) {
                var id = data[body + 1 + 2 * k] & 0xFF;
                var component = 0;
                while (component < ids.length && ids[component] != id) {
                    component++;
                }
                var repeated = component < ids.length && coded[component];
                for (var earlier = 0; earlier < k; earlier++) {
                    repeated |= order[earlier] == component;
                }
                if (component == ids.length || repeated) {
                    throw new MediaException.Malformed("JPEG-LS scan codes an unknown or already coded component "
                            + id);
                }
                order[k] = component;
                tables[k] = data[body + 2 + 2 * k] & 0xFF;
            }
            var near = data[body + 1 + 2 * count] & 0xFF;
            var interleave = data[body + 2 + 2 * count] & 0xFF;
            var pointTransform = data[body + 3 + 2 * count] & 15;
            var maxval = presets[0] != 0 ? presets[0] : (1 << precision) - 1;
            if (maxval >= 1 << precision) {
                throw new MediaException.Malformed("JPEG-LS MAXVAL " + maxval + " exceeds the precision of "
                        + precision + " bits");
            }
            if (near > Math.min(255, maxval / 2)) {
                throw new MediaException.Malformed("invalid JPEG-LS NEAR " + near + " for MAXVAL " + maxval);
            }
            if (interleave > 2 || (count > 1 && interleave == 0)) {
                throw new MediaException.Malformed("invalid JPEG-LS interleave mode " + interleave + " for "
                        + count + " components");
            }
            if (count == 1) {
                interleave = 0;
            }
            if (pointTransform >= precision) {
                throw new MediaException.Malformed("invalid JPEG-LS point transform " + pointTransform);
            }
            if (interleave == 2) {
                for (var k = 1; k < count; k++) {
                    if (hs[order[k]] != hs[order[0]] || vs[order[k]] != vs[order[0]]) {
                        throw new MediaException.Malformed("a sample-interleaved JPEG-LS scan codes components of"
                                + " different sizes");
                    }
                }
            }
            for (var k = 0; k < count; k++) {
                if (tables[k] != 0 && pointTransform != 0) {
                    throw new MediaException.Malformed("a JPEG-LS scan maps samples and applies a point transform");
                }
            }
            var thresholds = thresholds(maxval, near);
            for (var component : order) {
                coded[component] = true;
            }
            scans.add(new Scan(order, tables, near, interleave, pointTransform, maxval, thresholds,
                    restartInterval, bodyEnd));
        }

        /**
         * Resolves the thresholds and reset value in force for a scan, defaults included, and checks them
         * (T.87 table C.1).
         *
         * @param maxval the largest sample value
         * @param near the near-lossless bound
         * @return {@code T1}, {@code T2}, {@code T3} and {@code RESET}
         */
        private int[] thresholds(int maxval, int near) {
            var defaults = JpegLsModel.defaults(maxval, near);
            var t1 = presets[1] != 0 ? presets[1] : defaults[0];
            var t2 = presets[2] != 0 ? presets[2] : defaults[1];
            var t3 = presets[3] != 0 ? presets[3] : defaults[2];
            var reset = presets[4] != 0 ? presets[4] : defaults[3];
            var valid = t1 >= near + 1 && t1 <= maxval && t2 >= t1 && t2 <= maxval && t3 >= t2 && t3 <= maxval
                    && reset >= 3 && reset <= Math.max(255, maxval);
            if (!valid) {
                throw new MediaException.Malformed("invalid JPEG-LS preset coding parameters T1=" + t1 + " T2=" + t2
                        + " T3=" + t3 + " RESET=" + reset);
            }
            return new int[]{t1, t2, t3, reset};
        }

        /**
         * Returns the index of the marker that ends the entropy-coded data of a scan: the first {@code 0xFF}
         * followed by a byte with its high bit set that is not a restart marker.
         *
         * @param from the index of the first entropy-coded byte
         * @return the index of the marker's {@code 0xFF}, or the end of the stream
         */
        private int scanEnd(int from) {
            var at = from;
            while (at + 1 < end) {
                if ((data[at] & 0xFF) == 0xFF) {
                    var next = data[at + 1] & 0xFF;
                    if (next >= 0x80 && next != 0xFF && (next < 0xD0 || next > 0xD7)) {
                        return at;
                    }
                }
                at++;
            }
            return end;
        }
    }

    /**
     * Reads a big-endian 16-bit value.
     *
     * @param at the index of the first byte
     * @return the value
     */
    private int u16(int at) {
        return ((data[at] & 0xFF) << 8) | (data[at + 1] & 0xFF);
    }

    /**
     * Reads a big-endian unsigned value.
     *
     * @param at the index of the first byte
     * @param size the number of bytes, from 1 to 4
     * @return the value
     */
    private long unsigned(int at, int size) {
        var value = 0L;
        for (var k = 0; k < size; k++) {
            value = (value << 8) | (data[at + k] & 0xFF);
        }
        return value;
    }

    /**
     * {@return the frame width}
     */
    public int width() {
        return width;
    }

    /**
     * {@return the frame height, defined by the frame header, an oversize dimension segment or a {@code DNL}
     * segment}
     */
    public int height() {
        return height;
    }

    /**
     * {@return the coded sample precision {@code P}}
     */
    public int codedPrecision() {
        return precision;
    }

    /**
     * {@return the precision of the decoded samples: {@code P}, or 8 or 16 for mapped components}
     */
    public int outputPrecision() {
        return outputPrecision;
    }

    /**
     * {@return the number of decoded components: three for a palette image, else the frame's}
     */
    public int outputComponents() {
        return palette ? 3 : ids.length;
    }

    /**
     * {@return whether the frame is a palette image of red, green and blue components}
     */
    public boolean palette() {
        return palette;
    }

    /**
     * {@return the HP color transform the stream signals, 0 for none or 1 to 3}
     */
    public int colorTransform() {
        return colorTransform;
    }

    /**
     * Returns the sampling factors of a decoded component.
     *
     * @param component the decoded component, in frame order
     * @return a new array holding the horizontal and the vertical factor
     */
    public int[] sampling(int component) {
        var coded = palette ? 0 : component;
        return new int[]{hs[coded], vs[coded]};
    }

    /**
     * {@return the identifier of a decoded component: the frame's, or {@code 'R'}, {@code 'G'} and {@code 'B'}
     * for a palette image}
     *
     * @param component the decoded component, in frame order
     */
    public int id(int component) {
        return palette ? "RGB".charAt(component) : ids[component];
    }

    @Override
    public int componentWidth(int component) {
        var coded = palette ? 0 : component;
        return (int) (((long) width * hs[coded] + hMax - 1) / hMax);
    }

    /**
     * Returns the height of a decoded component, {@code ceil(Y * v / Vmax)}.
     *
     * @param component the decoded component, in frame order
     * @return the height in rows
     */
    public int componentHeight(int component) {
        var coded = palette ? 0 : component;
        return (int) (((long) height * vs[coded] + vMax - 1) / vMax);
    }

    @Override
    public boolean fullResolution() {
        return hMax == 1 && vMax == 1;
    }

    @Override
    public boolean streamsRows() {
        return fullResolution() && scans.size() == 1 && scans.getFirst().components().length == ids.length
                && (ids.length == 1 || scans.getFirst().interleave() != 0);
    }

    @Override
    public void decode(LosslessJpeg.RowSink sink) {
        if (!fullResolution()) {
            throw new IllegalStateException("the frame has subsampled components");
        }
        var count = outputComponents();
        var row = new int[width * count];
        if (streamsRows()) {
            var filled = new int[1];
            decodeComponents((component, line, samples) -> {
                for (var x = 0; x < width; x++) {
                    row[x * count + component] = samples[x];
                }
                if (++filled[0] == count) {
                    filled[0] = 0;
                    sink.row(line, row);
                }
            });
            return;
        }
        var length = (long) width * height;
        LimitChecks.checkAllocation(limits, 2L * count * length);
        var planes = new short[count][(int) length];
        decodeComponents((component, line, samples) -> {
            var plane = planes[component];
            var at = line * width;
            for (var x = 0; x < width; x++) {
                plane[at + x] = (short) samples[x];
            }
        });
        for (var y = 0; y < height; y++) {
            var at = y * width;
            for (var c = 0; c < count; c++) {
                var plane = planes[c];
                for (var x = 0; x < width; x++) {
                    row[x * count + c] = plane[at + x] & 0xFFFF;
                }
            }
            sink.row(y, row);
        }
    }

    @Override
    public void decodeComponents(LosslessJpeg.ComponentSink sink) {
        var coded = new boolean[ids.length];
        for (var scan : scans) {
            for (var component : scan.components()) {
                coded[component] = true;
            }
        }
        var count = scans.size();
        var work = 0L;
        for (var scan : scans) {
            for (var component : scan.components()) {
                work += 64L * componentWidth(component) * componentHeight(component);
            }
        }
        var bands = colorTransform == 0 && !palette ? ParallelBands.bands(count, work) : 1;
        ParallelBands.run(bands, band -> {
            var output = new Output(sink);
            var last = ParallelBands.start(band + 1, bands, count);
            for (var index = ParallelBands.start(band, bands, count); index < last; index++) {
                new ScanDecoder(scans.get(index), output).decode();
            }
        });
        for (var component = 0; component < ids.length; component++) {
            if (!coded[component]) {
                var zeros = new int[componentWidth(component)];
                var rows = componentHeight(component);
                for (var row = 0; row < rows; row++) {
                    if (palette) {
                        for (var c = 0; c < 3; c++) {
                            sink.row(c, row, zeros);
                        }
                    } else {
                        sink.row(component, row, zeros);
                    }
                }
            }
        }
    }

    /**
     * Turns decoded lines into output rows: applies the point transform, the mapping tables and the color
     * transform.
     */
    private final class Output {
        /**
         * Receives the rows.
         */
        private final LosslessJpeg.ComponentSink sink;

        /**
         * The row handed over, reused.
         */
        private final int[] row;

        /**
         * The red, green and blue rows of a palette image or a color-transformed frame, reused.
         */
        private final int[][] rgb;

        /**
         * Constructs the output stage.
         *
         * @param sink receives the rows
         */
        Output(LosslessJpeg.ComponentSink sink) {
            this.sink = sink;
            var widest = 0;
            for (var component = 0; component < ids.length; component++) {
                widest = Math.max(widest, componentWidth(component));
            }
            this.row = new int[widest];
            this.rgb = palette || colorTransform != 0 ? new int[3][widest] : null;
        }

        /**
         * Hands over one decoded line of a component.
         *
         * @param component the frame component
         * @param line the line index within the component
         * @param samples the decoded samples
         * @param from the index of the first sample
         * @param step the distance between samples
         * @param count the number of samples
         * @param pointTransform the point transform of the scan
         */
        void line(int component, int line, int[] samples, int from, int step, int count, int pointTransform) {
            var table = mapping[component];
            if (palette) {
                var colors = table.rgb();
                var limit = colors[0].length - 1;
                for (var x = 0; x < count; x++) {
                    var index = Math.min(samples[from + x * step], limit);
                    rgb[0][x] = colors[0][index];
                    rgb[1][x] = colors[1][index];
                    rgb[2][x] = colors[2][index];
                }
                for (var c = 0; c < 3; c++) {
                    sink.row(c, line, rgb[c]);
                }
                return;
            }
            if (table != null) {
                var values = table.values();
                var limit = values.length - 1;
                for (var x = 0; x < count; x++) {
                    row[x] = values[Math.min(samples[from + x * step], limit)];
                }
            } else {
                for (var x = 0; x < count; x++) {
                    row[x] = samples[from + x * step] << pointTransform;
                }
            }
            sink.row(component, line, row);
        }

        /**
         * Hands over one decoded line of each of the three components of a color-transformed frame, after
         * inverting the transform.
         *
         * @param line the line index
         * @param samples the decoded samples of each component
         * @param from the index of each component's first sample
         * @param step the distance between samples
         * @param count the number of samples
         */
        void transformed(int line, int[][] samples, int[] from, int step, int count) {
            var bits = precision;
            var mask = (1 << bits) - 1;
            var bias = 1 << (bits - 1);
            var quarter = 1 << (bits - 2);
            var s0 = samples[0];
            var s1 = samples[1];
            var s2 = samples[2];
            for (var x = 0; x < count; x++) {
                var v1 = s0[from[0] + x * step];
                var v2 = s1[from[1] + x * step];
                var v3 = s2[from[2] + x * step];
                switch (colorTransform) {
                    case 1 -> {
                        rgb[0][x] = (v1 + v2 - bias) & mask;
                        rgb[1][x] = v2;
                        rgb[2][x] = (v3 + v2 - bias) & mask;
                    }
                    case 2 -> {
                        var r = (v1 + v2 - bias) & mask;
                        rgb[0][x] = r;
                        rgb[1][x] = v2;
                        rgb[2][x] = (v3 + ((r + v2) >> 1) - bias) & mask;
                    }
                    default -> {
                        var g = v1 - ((v3 + v2) >> 2) + quarter;
                        rgb[0][x] = (v3 + g - bias) & mask;
                        rgb[1][x] = g & mask;
                        rgb[2][x] = (v2 + g - bias) & mask;
                    }
                }
            }
            for (var c = 0; c < 3; c++) {
                sink.row(c, line, rgb[c]);
            }
        }
    }

    /**
     * Decodes the entropy-coded data of one scan.
     */
    private final class ScanDecoder {
        /**
         * The scan.
         */
        private final Scan scan;

        /**
         * The output stage.
         */
        private final Output output;

        /**
         * The context model.
         */
        private final JpegLsModel model;

        /**
         * The bit reader.
         */
        private final JpegLsBitReader reader;

        /**
         * The signed context of each component of the sample being decoded in a sample-interleaved scan.
         */
        private final int[] contexts = new int[4];

        /**
         * Constructs the decoder of a scan.
         *
         * @param scan the scan header with the parameters in force
         * @param output the output stage
         */
        ScanDecoder(Scan scan, Output output) {
            this.scan = scan;
            this.output = output;
            var thresholds = scan.thresholds();
            this.model = new JpegLsModel(scan.maxval(), scan.near(), thresholds[0], thresholds[1], thresholds[2],
                    thresholds[3]);
            this.reader = new JpegLsBitReader(data, scan.start(), end);
        }

        /**
         * Decodes the scan.
         */
        void decode() {
            switch (scan.interleave()) {
                case 0 -> decodeSingle();
                case 1 -> decodeLines();
                default -> decodeSamples();
            }
        }

        /**
         * Decodes a scan of one component, one line per minimum coded unit.
         */
        private void decodeSingle() {
            var component = scan.components()[0];
            var columns = componentWidth(component);
            var rows = componentHeight(component);
            var lines = allocateLines(1, columns);
            var interval = scan.restartInterval();
            var previous = lines[0];
            var current = lines[1];
            for (var row = 0; row < rows; row++) {
                if (interval > 0 && row > 0 && row % interval == 0) {
                    restart(lines);
                }
                decodeLine(previous, current, columns);
                output.line(component, row, current, 1, 1, columns, scan.pointTransform());
                var swap = previous;
                previous = current;
                current = swap;
                lines[0] = previous;
                lines[1] = current;
            }
        }

        /**
         * Decodes a line-interleaved scan: each minimum coded unit holds {@code v} lines of each component in
         * scan order.
         */
        private void decodeLines() {
            var order = scan.components();
            var count = order.length;
            var columns = new int[count];
            var rows = new int[count];
            var lines = new int[count][][];
            var runIndexes = new int[count];
            for (var k = 0; k < count; k++) {
                columns[k] = componentWidth(order[k]);
                rows[k] = componentHeight(order[k]);
                lines[k] = allocateLines(1, columns[k]);
            }
            var units = (height + vMax - 1) / vMax;
            var interval = scan.restartInterval();
            var transform = colorTransform != 0;
            var pending = transform ? new int[3][] : null;
            var from = transform ? new int[]{1, 1, 1} : null;
            for (var unit = 0; unit < units; unit++) {
                if (interval > 0 && unit > 0 && unit % interval == 0) {
                    restart(lines);
                    Arrays.fill(runIndexes, 0);
                }
                for (var k = 0; k < count; k++) {
                    var component = order[k];
                    for (var line = 0; line < vs[component]; line++) {
                        var previous = lines[k][0];
                        var current = lines[k][1];
                        model.runIndex = runIndexes[k];
                        decodeLine(previous, current, columns[k]);
                        runIndexes[k] = model.runIndex;
                        var row = unit * vs[component] + line;
                        if (row < rows[k]) {
                            if (transform) {
                                pending[k] = current;
                            } else {
                                output.line(component, row, current, 1, 1, columns[k], scan.pointTransform());
                            }
                        }
                        lines[k][0] = current;
                        lines[k][1] = previous;
                    }
                }
                if (transform && unit < rows[0]) {
                    output.transformed(unit, pending, from, 1, columns[0]);
                }
            }
        }

        /**
         * Decodes a sample-interleaved scan, one line of every component per minimum coded unit.
         */
        private void decodeSamples() {
            var order = scan.components();
            var count = order.length;
            var columns = componentWidth(order[0]);
            var rows = componentHeight(order[0]);
            var lines = allocateLines(count, columns);
            var interval = scan.restartInterval();
            var transform = colorTransform != 0;
            var samples = transform ? new int[3][] : null;
            var from = transform ? new int[]{count, count + 1, count + 2} : null;
            var previous = lines[0];
            var current = lines[1];
            for (var row = 0; row < rows; row++) {
                if (interval > 0 && row > 0 && row % interval == 0) {
                    restart(lines);
                    previous = lines[0];
                    current = lines[1];
                }
                decodeInterleaved(previous, current, columns, count);
                if (transform) {
                    samples[0] = current;
                    samples[1] = current;
                    samples[2] = current;
                    output.transformed(row, samples, from, count, columns);
                } else {
                    for (var k = 0; k < count; k++) {
                        output.line(order[k], row, current, count + k, count, columns, scan.pointTransform());
                    }
                }
                var swap = previous;
                previous = current;
                current = swap;
                lines[0] = previous;
                lines[1] = current;
            }
        }

        /**
         * Allocates the previous and current line of a component or of an interleaved group.
         *
         * @param count the number of components interleaved per sample
         * @param columns the number of samples per line
         * @return the two lines, each with one sample position before and after the line
         */
        private int[][] allocateLines(int count, int columns) {
            LimitChecks.checkAllocation(limits, 8L * count * (columns + 2));
            return new int[][]{new int[count * (columns + 2)], new int[count * (columns + 2)]};
        }

        /**
         * Starts a restart interval: skips the restart marker and resets the model and the lines.
         *
         * @param lines the previous and current lines of each component to clear
         */
        private void restart(int[][]... lines) {
            reader.restart();
            model.reset();
            for (var pair : lines) {
                Arrays.fill(pair[0], 0);
                Arrays.fill(pair[1], 0);
            }
        }

        /**
         * Decodes one line of a component (T.87 A.8 for one sample after another).
         *
         * @param previous the previous line, samples at 1 to {@code columns}
         * @param current the line to decode, samples at 1 to {@code columns}
         * @param columns the number of samples
         */
        private void decodeLine(int[] previous, int[] current, int columns) {
            previous[columns + 1] = previous[columns];
            current[0] = previous[1];
            var index = 1;
            var rb = previous[0];
            var rd = previous[1];
            while (index <= columns) {
                var ra = current[index - 1];
                var rc = rb;
                rb = rd;
                rd = previous[index + 1];
                var qs = model.context(ra, rb, rc, rd);
                if (qs != 0) {
                    current[index] = regular(qs, JpegLsModel.predict(ra, rb, rc));
                    index++;
                } else {
                    index += run(previous, current, index, columns, 1);
                    rb = previous[index - 1];
                    rd = previous[index];
                }
            }
        }

        /**
         * Decodes one line of a sample-interleaved scan.
         *
         * @param previous the previous line, sample {@code x} of component {@code k} at {@code x * count + k}
         * @param current the line to decode, in the same layout
         * @param columns the number of samples per component
         * @param count the number of components
         */
        private void decodeInterleaved(int[] previous, int[] current, int columns, int count) {
            for (var k = 0; k < count; k++) {
                previous[(columns + 1) * count + k] = previous[columns * count + k];
                current[k] = previous[count + k];
            }
            var index = 1;
            var contexts = this.contexts;
            while (index <= columns) {
                var all = 0;
                for (var k = 0; k < count; k++) {
                    var ra = current[(index - 1) * count + k];
                    var rc = previous[(index - 1) * count + k];
                    var rb = previous[index * count + k];
                    var rd = previous[(index + 1) * count + k];
                    contexts[k] = model.context(ra, rb, rc, rd);
                    all |= contexts[k];
                }
                if (all == 0) {
                    index += run(previous, current, index, columns, count);
                    continue;
                }
                for (var k = 0; k < count; k++) {
                    var ra = current[(index - 1) * count + k];
                    var rc = previous[(index - 1) * count + k];
                    var rb = previous[index * count + k];
                    current[index * count + k] = regular(contexts[k], JpegLsModel.predict(ra, rb, rc));
                }
                index++;
            }
        }

        /**
         * Decodes a sample in regular mode (T.87 A.4 to A.6 inverted).
         *
         * @param qs the signed context
         * @param prediction the median edge detector prediction
         * @return the reconstructed sample
         */
        private int regular(int qs, int prediction) {
            var sign = qs >> 31;
            var q = (qs ^ sign) - sign;
            var corrected = model.correct(prediction, q, sign);
            var k = model.golomb(q);
            var mapped = mappedError(k, model.limit);
            var error = -(mapped & 1) ^ (mapped >>> 1);
            if (k == 0) {
                error ^= model.errorCorrection(q, 0);
            }
            model.update(q, error);
            return model.reconstruct(corrected, (error ^ sign) - sign);
        }

        /**
         * Decodes a Golomb-coded mapped error value with its escape code (T.87 A.5.3).
         *
         * @param k the Golomb parameter
         * @param limit the code length limit
         * @return the mapped error value
         */
        private int mappedError(int k, int limit) {
            var cap = limit - model.qbpp - 1;
            var unary = reader.unary(cap);
            if (unary < cap) {
                if (k == 0) {
                    return unary;
                }
                var value = ((long) unary << k) + reader.bits(k);
                return (int) Math.min(value, 1 << 20);
            }
            return reader.bits(model.qbpp) + 1;
        }

        /**
         * Decodes a run and the sample that interrupts it (T.87 A.7 inverted).
         *
         * @param previous the previous line
         * @param current the line being decoded
         * @param start the index of the first sample of the run
         * @param columns the number of samples per line
         * @param count the number of components interleaved per sample
         * @return the number of samples decoded
         */
        private int run(int[] previous, int[] current, int start, int columns, int count) {
            var remaining = columns - start + 1;
            var length = 0;
            while (reader.bit() != 0) {
                var segment = Math.min(1 << JpegLsModel.J[model.runIndex], remaining - length);
                length += segment;
                if (segment == 1 << JpegLsModel.J[model.runIndex]) {
                    model.incrementRun();
                }
                if (length == remaining) {
                    break;
                }
            }
            if (length != remaining) {
                var bits = JpegLsModel.J[model.runIndex];
                if (bits > 0) {
                    length += reader.bits(bits);
                }
                length = Math.min(length, remaining);
            }
            var left = (start - 1) * count;
            for (var i = 0; i < length; i++) {
                for (var k = 0; k < count; k++) {
                    current[(start + i) * count + k] = current[left + k];
                }
            }
            var stop = start + length;
            if (stop > columns) {
                return length;
            }
            if (count == 1) {
                current[stop] = interruption(current[start - 1], previous[stop]);
            } else {
                for (var k = 0; k < count; k++) {
                    var ra = current[left + k];
                    var rb = previous[stop * count + k];
                    var error = runError(0);
                    current[stop * count + k] = model.reconstruct(rb, error * (((rb - ra) >> 31) | 1));
                }
            }
            model.decrementRun();
            return length + 1;
        }

        /**
         * Decodes the sample interrupting a run of one component (T.87 A.7.2 inverted).
         *
         * @param ra the sample to the left, the value of the run
         * @param rb the sample above
         * @return the reconstructed sample
         */
        private int interruption(int ra, int rb) {
            if (model.near(ra, rb)) {
                return model.reconstruct(ra, runError(1));
            }
            var error = runError(0);
            return model.reconstruct(rb, error * (((rb - ra) >> 31) | 1));
        }

        /**
         * Decodes the error value of a run-interruption sample.
         *
         * @param type the run-interruption type
         * @return the error value
         */
        private int runError(int type) {
            var k = model.runGolomb(type);
            var mapped = mappedError(k, model.limit - JpegLsModel.J[model.runIndex] - 1);
            var error = model.runError(type, mapped + type, k);
            model.runUpdate(type, error, mapped);
            return error;
        }
    }
}
