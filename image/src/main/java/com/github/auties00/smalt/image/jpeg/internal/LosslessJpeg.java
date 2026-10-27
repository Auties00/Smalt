package com.github.auties00.smalt.image.jpeg.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.internal.io.ByteArrays;
import com.github.auties00.smalt.internal.limits.LimitChecks;

import java.util.Arrays;

/**
 * Decodes a lossless JPEG stream (ITU-T T.81 annex H, processes 14 and 15, {@code SOF3} and
 * {@code SOF11}): the coding of the sensor data of DNG files, Canon CR2 files and several other
 * raw formats.
 *
 * <p>The decoder supports Huffman and arithmetic coding, every predictor (1 to 7), the point
 * transform, restart intervals, samples of 2 to 16 bits, frames of 1 to 255 components with any
 * sampling factors, and frames coded in one scan or in several, each scan coding one component or
 * interleaving up to four. Tables, arithmetic conditioning and the restart interval may change
 * between scans. Streams whose first component is subsampled, as Canon writes its mRAW and sRAW
 * data, are opened with {@link #LosslessJpeg(byte[], int, int, boolean)} and decoded with
 * {@link #decodeUnits}.
 *
 * <p>A component of factors {@code h} and {@code v} is {@code ceil(X * h / Hmax)} samples wide and
 * {@code ceil(Y * v / Vmax)} rows high, for a frame of {@code X} by {@code Y} samples and the largest
 * factors {@code Hmax} and {@code Vmax}. {@link #decodeComponents} hands over the rows of each
 * component; {@link #decode}, for frames whose components all have that full size, the rows of the
 * frame with the components of each column interleaved in frame order, rows of {@code X * N}
 * samples for {@code N} components.
 *
 * <p>Within a scan, the first line of the scan and of every restart interval is predicted from the
 * left, its first sample from {@code 2^(P - Pt - 1)}, and the first sample of every other line from
 * the sample above. T.81 requires restart intervals of whole rows of minimum coded units; an interval
 * that starts within a row is decoded with the samples of that row from the first sample of the
 * interval on, in each component, as its first line. Samples are shifted left by the point transform.
 * Entropy-coded data never makes the decode fail: a stream that ends early or is corrupt decodes to
 * its end with zero bits (Huffman) or zero bytes (arithmetic) supplied in place of the missing data.
 * Damage to the headers of a scan after the first one ends the frame there, and the components no
 * scan has coded by then decode as zero samples.
 *
 * <p>Instances are not thread-safe.
 *
 * @implNote This implementation follows the specification directly. Huffman codes of up to
 * {@link #LOOKUP_BITS} bits, which are nearly all of them, decode with one table lookup; longer ones
 * walk the canonical code lengths. Arithmetic-coded scans run through {@link ArithmeticScanDecoder}
 * with the two-dimensional statistical model of T.81 H.1.2.3.
 */
public final class LosslessJpeg implements LosslessSamples {
    /**
     * The number of leading bits the fast Huffman table resolves.
     */
    private static final int LOOKUP_BITS = 10;

    /**
     * The frame width in columns.
     */
    private final int width;

    /**
     * The frame height in rows.
     */
    private final int height;

    /**
     * The number of frame components.
     */
    private final int components;

    /**
     * The sample precision in bits.
     */
    private final int precision;

    /**
     * Whether the frame is arithmetic coded ({@code SOF11}, {@code SOF15}).
     */
    private final boolean arithmetic;

    /**
     * Whether the frame is a differential frame of hierarchical coding ({@code SOF7}, {@code SOF15}), whose
     * differences are coded without prediction and decoded modulo {@code 2^16}.
     */
    private final boolean differential;

    /**
     * The identifier of each component, in frame order.
     */
    private final int[] ids;

    /**
     * The horizontal and vertical sampling factors of each component, in frame order.
     */
    private final int[][] sampling;

    /**
     * The largest horizontal sampling factor.
     */
    private final int hMax;

    /**
     * The largest vertical sampling factor.
     */
    private final int vMax;

    /**
     * The stream.
     */
    private final byte[] data;

    /**
     * The index after the stream.
     */
    private final int end;

    /**
     * The resource bounds of the buffers an arithmetic-coded scan or {@link #decode} allocates.
     */
    private final MediaLimits limits;

    /**
     * The first scan.
     */
    private final Scan first;

    /**
     * The tables in force at the first scan, copied before each pass over the later scans.
     */
    private final Tables firstTables;

    /**
     * Receives the decoded rows.
     */
    @FunctionalInterface
    public interface RowSink {
        /**
         * Receives one decoded row.
         *
         * @param row the row index, from 0 to the frame height minus one
         * @param samples the samples, components interleaved; the array is reused for the next row
         */
        void row(int row, int[] samples);
    }

    /**
     * Receives the decoded rows of single components.
     */
    @FunctionalInterface
    public interface ComponentSink {
        /**
         * Receives one decoded row of one component.
         *
         * @param component the component, in frame order
         * @param row the row index within the component, from 0 to its height minus one
         * @param samples the samples of the row in its first {@link #componentWidth} elements; the
         * array is reused
         */
        void row(int component, int row, int[] samples);
    }

    /**
     * Parses the headers of a stream up to its first scan.
     *
     * @param data the bytes holding the stream
     * @param offset the index of its {@code SOI} marker
     * @param length the length of the stream
     * @throws MediaException.Malformed if the headers are corrupt
     * @throws MediaException.Unsupported if the stream is not a lossless JPEG this decoder handles
     */
    public LosslessJpeg(byte[] data, int offset, int length) {
        this(data, offset, length, false);
    }

    /**
     * Parses the headers of a stream up to its first scan, optionally accepting sampling factors
     * other than one.
     *
     * @param data the bytes holding the stream
     * @param offset the index of its {@code SOI} marker
     * @param length the length of the stream
     * @param subsampled whether sampling factors of up to 2 are accepted, for {@link #decodeUnits}
     * @throws MediaException.Malformed if the headers are corrupt
     * @throws MediaException.Unsupported if the stream is not a lossless JPEG this decoder handles
     */
    public LosslessJpeg(byte[] data, int offset, int length, boolean subsampled) {
        this(data, offset, length, subsampled ? 2 : 1, 0, MediaLimits.DEFAULT);
    }

    /**
     * Parses the headers of a stream up to its first scan, taking the frame height from a
     * {@code DNL} segment the caller has read when the frame header declares zero rows.
     *
     * @param data the bytes holding the stream
     * @param offset the index of its {@code SOI} marker
     * @param length the length of the stream
     * @param maxFactor the largest sampling factor accepted: 1, 2 for {@link #decodeUnits}, or 4 for
     * {@link #decodeComponents}
     * @param lines the frame height to use when the frame header declares zero rows, or 0 for none
     * @param limits the resource bounds of the buffers the decode allocates
     * @throws MediaException.Malformed if the headers are corrupt
     * @throws MediaException.Unsupported if the stream is not a lossless JPEG this decoder handles
     */
    LosslessJpeg(byte[] data, int offset, int length, int maxFactor, int lines, MediaLimits limits) {
        this(data, offset, length, -1, false, maxFactor, lines, limits);
    }

    /**
     * Parses the headers of one frame of a hierarchical image up to its first scan, walking the stream from its
     * {@code SOI} for the tables in force there and skipping the frames before it.
     *
     * <p>The rows the decoder hands over are the samples of a non-differential frame, and for a differential
     * frame the decoded differences modulo {@code 2^16}, shifted left by the point transform.
     *
     * @param data the bytes holding the stream
     * @param offset the index of its {@code SOI} marker
     * @param length the length of the stream
     * @param frameStart the index of the {@code 0xFF} of the frame's SOF marker
     * @param differential whether the frame is differential ({@code SOF7}, {@code SOF15})
     * @param lines the frame height to use when the frame header declares zero rows, or 0 for none
     * @param limits the resource bounds of the buffers the decode allocates
     * @throws MediaException.Malformed if the headers are corrupt
     * @throws MediaException.Unsupported if the frame is not a lossless frame this decoder handles
     */
    LosslessJpeg(byte[] data, int offset, int length, int frameStart, boolean differential, int lines,
                 MediaLimits limits) {
        this(data, offset, length, frameStart, differential, 4, lines, limits);
    }

    /**
     * Parses the headers of a stream, or of one frame of a hierarchical stream, up to its first scan.
     *
     * @param data the bytes holding the stream
     * @param offset the index of its {@code SOI} marker
     * @param length the length of the stream
     * @param frameStart the index of the {@code 0xFF} of the SOF marker of the frame to decode, or {@code -1} for
     * the only frame
     * @param differential whether the frame is differential
     * @param maxFactor the largest sampling factor accepted
     * @param lines the frame height to use when the frame header declares zero rows, or 0 for none
     * @param limits the resource bounds of the buffers the decode allocates
     */
    private LosslessJpeg(byte[] data, int offset, int length, int frameStart, boolean differential, int maxFactor,
                         int lines, MediaLimits limits) {
        this.differential = differential;
        if (offset < 0 || length < 4 || offset > data.length - length) {
            throw new MediaException.Malformed("truncated lossless JPEG stream");
        }
        this.data = data;
        this.end = offset + length;
        this.limits = limits;
        if ((data[offset] & 0xFF) != 0xFF || (data[offset + 1] & 0xFF) != 0xD8) {
            throw new MediaException.Malformed("lossless JPEG stream does not start with SOI");
        }
        var tables = new Tables();
        var frameWidth = -1;
        var frameHeight = -1;
        var frameComponents = 0;
        var framePrecision = 0;
        var frameArithmetic = false;
        int[] frameIds = null;
        int[][] factors = null;
        var foreign = false;
        var position = offset + 2;
        while (true) {
            var marker = nextMarker(position);
            if (marker < 0) {
                throw new MediaException.Malformed("lossless JPEG stream ends before its scan");
            }
            position = (int) (marker >>> 8);
            var code = (int) (marker & 0xFF);
            var sof = code >= 0xC0 && code <= 0xCF && code != 0xC4 && code != 0xC8 && code != 0xCC;
            if (frameStart >= 0 && sof && position - 2 != frameStart) {
                foreign = true;
                position = segmentEnd(position, code);
                continue;
            }
            if (frameStart >= 0 && code == 0xDA && (frameIds == null || foreign)) {
                position = scanEnd(segmentEnd(position, code));
                continue;
            }
            if (sof && differential && (code == 0xC7 || code == 0xCF)) {
                code -= 4;
            }
            if (code == 0xD9) {
                throw new MediaException.Malformed("lossless JPEG stream ends before its scan");
            }
            if (!hasLength(code)) {
                continue;
            }
            var bodyEnd = segmentEnd(position, code);
            var body = position + 2;
            switch (code) {
                case 0xC3, 0xCB -> {
                    foreign = false;
                    if (frameIds != null) {
                        throw new MediaException.Malformed("duplicate lossless JPEG frame header");
                    }
                    var segment = bodyEnd - position;
                    if (segment < 8) {
                        throw new MediaException.Malformed("truncated SOF" + (code - 0xC0) + " segment");
                    }
                    frameArithmetic = code == 0xCB;
                    framePrecision = data[body] & 0xFF;
                    frameHeight = ByteArrays.u16be(data, body + 1);
                    frameWidth = ByteArrays.u16be(data, body + 3);
                    frameComponents = data[body + 5] & 0xFF;
                    if (frameComponents < 1 || segment != 8 + 3 * frameComponents) {
                        throw new MediaException.Malformed("invalid SOF" + (code - 0xC0) + " component count "
                                + frameComponents);
                    }
                    if (framePrecision < 2 || framePrecision > 16) {
                        throw new MediaException.Malformed("invalid lossless JPEG precision " + framePrecision);
                    }
                    frameIds = new int[frameComponents];
                    factors = new int[frameComponents][];
                    for (var component = 0; component < frameComponents; component++) {
                        frameIds[component] = data[body + 6 + 3 * component] & 0xFF;
                        var sampling = data[body + 7 + 3 * component] & 0xFF;
                        factors[component] = new int[]{sampling >> 4, sampling & 15};
                        var valid = factors[component][0] >= 1 && factors[component][0] <= maxFactor
                                && factors[component][1] >= 1 && factors[component][1] <= maxFactor;
                        if (!valid) {
                            throw new MediaException.Unsupported("jpeg lossless",
                                    "lossless JPEG sampling factors of " + (sampling >> 4) + "x" + (sampling & 15)
                                            + " are not supported");
                        }
                    }
                }
                case 0xC0, 0xC1, 0xC2, 0xC5, 0xC6, 0xC7, 0xC9, 0xCA, 0xCD, 0xCE, 0xCF ->
                        throw new MediaException.Unsupported("jpeg", "the stream is not a lossless JPEG (SOF"
                                + (code - 0xC0) + ")");
                case 0xDA -> {
                    if (frameIds == null) {
                        throw new MediaException.Malformed("lossless JPEG scan before its frame header");
                    }
                    if (frameHeight == 0) {
                        frameHeight = lines;
                    }
                    if (frameWidth <= 0 || frameHeight <= 0) {
                        throw new MediaException.Malformed("lossless JPEG frame of " + frameWidth + "x" + frameHeight);
                    }
                    this.width = frameWidth;
                    this.height = frameHeight;
                    this.components = frameComponents;
                    this.precision = framePrecision;
                    this.arithmetic = frameArithmetic;
                    this.ids = frameIds;
                    this.sampling = factors;
                    var horizontal = 1;
                    var vertical = 1;
                    for (var factor : factors) {
                        horizontal = Math.max(horizontal, factor[0]);
                        vertical = Math.max(vertical, factor[1]);
                    }
                    this.hMax = horizontal;
                    this.vMax = vertical;
                    this.first = parseScan(body, bodyEnd, tables, new boolean[frameComponents]);
                    this.firstTables = tables;
                    return;
                }
                default -> parseTables(code, body, bodyEnd, tables);
            }
            position = bodyEnd;
        }
    }

    /**
     * Holds the table definitions and the restart interval in force at a point of the stream.
     */
    private static final class Tables {
        /**
         * The Huffman table of each slot, or {@code null} when undefined.
         */
        final DhtTable[] huffman = new DhtTable[4];

        /**
         * The lower arithmetic conditioning bound {@code L} of each slot.
         */
        final int[] lower = new int[16];

        /**
         * The upper arithmetic conditioning bound {@code U} of each slot.
         */
        final int[] upper = new int[16];

        /**
         * The restart interval in minimum coded units, or {@code 0}.
         */
        int restartInterval;

        /**
         * Constructs the tables in force after {@code SOI}: no Huffman table, the default
         * conditioning bounds {@code L = 0} and {@code U = 1}, and no restart interval.
         */
        Tables() {
            Arrays.fill(upper, 1);
        }

        /**
         * {@return a copy that later definitions do not affect}
         */
        Tables copy() {
            var copy = new Tables();
            System.arraycopy(huffman, 0, copy.huffman, 0, huffman.length);
            System.arraycopy(lower, 0, copy.lower, 0, lower.length);
            System.arraycopy(upper, 0, copy.upper, 0, upper.length);
            copy.restartInterval = restartInterval;
            return copy;
        }
    }

    /**
     * Holds one scan header.
     *
     * @param components the frame index of each scan component, in scan order
     * @param huffman the Huffman table of each scan component, or {@code null} entries for an
     * arithmetic-coded scan
     * @param slots the table selector of each scan component
     * @param lower the lower conditioning bound of each scan component
     * @param upper the upper conditioning bound of each scan component
     * @param predictor the predictor selection value, from 1 to 7, or 0 for the differences of a differential frame
     * @param pointTransform the point transform, below the precision
     * @param restartInterval the restart interval in minimum coded units, or {@code 0}
     * @param start the index of the first entropy-coded byte
     */
    private record Scan(int[] components, DhtTable[] huffman, int[] slots, int[] lower, int[] upper, int predictor,
                        int pointTransform, int restartInterval, int start) {
    }

    /**
     * Finds the next marker from an index, skipping bytes that are not part of one.
     *
     * @param from the index to search from
     * @return the index after the marker code shifted left by 8 bits, or-ed with the marker code,
     * or {@code -1} at the end of the stream
     */
    private long nextMarker(int from) {
        var position = from;
        while (position < end) {
            if ((data[position] & 0xFF) != 0xFF) {
                position++;
                continue;
            }
            while (position < end && (data[position] & 0xFF) == 0xFF) {
                position++;
            }
            if (position >= end) {
                return -1;
            }
            var code = data[position] & 0xFF;
            position++;
            if (code != 0) {
                return ((long) position << 8) | code;
            }
        }
        return -1;
    }

    /**
     * Returns whether a marker code starts a segment with a length field.
     *
     * @param code the marker code
     * @return {@code false} for {@code SOI}, {@code EOI}, {@code TEM} and the restart markers
     */
    private static boolean hasLength(int code) {
        return code != 0x01 && code != 0xD8 && code != 0xD9 && (code < 0xD0 || code > 0xD7);
    }

    /**
     * Reads the length field of a segment and checks it against the stream.
     *
     * @param at the index of the length field
     * @param code the marker code, named in the failure message
     * @return the index after the segment
     * @throws MediaException.Malformed if the segment is truncated
     */
    private int segmentEnd(int at, int code) {
        if (at + 2 > end) {
            throw new MediaException.Malformed("truncated lossless JPEG segment 0x" + Integer.toHexString(code));
        }
        var segment = ByteArrays.u16be(data, at);
        if (segment < 2 || at + segment > end) {
            throw new MediaException.Malformed("truncated lossless JPEG segment 0x" + Integer.toHexString(code));
        }
        return at + segment;
    }

    /**
     * Applies a table-defining segment ({@code DHT}, {@code DAC}, {@code DRI}); any other segment
     * is skipped.
     *
     * @param code the marker code
     * @param body the index of the segment's first byte after the length field
     * @param bodyEnd the index after the segment
     * @param tables the tables to update
     * @throws MediaException.Malformed if the segment is invalid
     */
    private void parseTables(int code, int body, int bodyEnd, Tables tables) {
        switch (code) {
            case 0xC4 -> {
                var at = body;
                while (at < bodyEnd) {
                    var index = data[at] & 0x0F;
                    var tableClass = (data[at] & 0xF0) >> 4;
                    if (tableClass > 1 || index > 3 || at + 17 > bodyEnd) {
                        throw new MediaException.Malformed("invalid lossless JPEG Huffman table");
                    }
                    var total = 0;
                    for (var bits = 1; bits <= 16; bits++) {
                        total += data[at + bits] & 0xFF;
                    }
                    if (at + 17 + total > bodyEnd || total > 256) {
                        throw new MediaException.Malformed("truncated lossless JPEG Huffman table");
                    }
                    // some encoders also define AC tables, which no lossless scan uses
                    if (tableClass == 0) {
                        tables.huffman[index] = new DhtTable(data, at + 1, at + 17, total);
                    }
                    at += 17 + total;
                }
            }
            case 0xCC -> {
                if ((bodyEnd - body) % 2 != 0) {
                    throw new MediaException.Malformed("invalid lossless JPEG DAC segment");
                }
                for (var at = body; at < bodyEnd; at += 2) {
                    var tableClass = (data[at] & 0xF0) >> 4;
                    var slot = data[at] & 0x0F;
                    var value = data[at + 1] & 0xFF;
                    if (tableClass > 1) {
                        throw new MediaException.Malformed("invalid lossless JPEG DAC table class " + tableClass);
                    }
                    // AC conditioning has no use in a lossless scan
                    if (tableClass == 0) {
                        if ((value & 15) > (value >> 4)) {
                            throw new MediaException.Malformed("invalid lossless JPEG conditioning value " + value);
                        }
                        tables.lower[slot] = value & 15;
                        tables.upper[slot] = value >> 4;
                    }
                }
            }
            case 0xDD -> {
                if (bodyEnd - body != 2) {
                    throw new MediaException.Malformed("invalid DRI segment");
                }
                tables.restartInterval = ByteArrays.u16be(data, body);
            }
            default -> {
            }
        }
    }

    /**
     * Parses a scan header.
     *
     * @param body the index of the header's first byte after the length field
     * @param bodyEnd the index after the header
     * @param tables the tables in force
     * @param coded which frame components earlier scans coded, updated with the components of
     * this scan
     * @return the scan
     * @throws MediaException.Malformed if the header is invalid
     */
    private Scan parseScan(int body, int bodyEnd, Tables tables, boolean[] coded) {
        if (bodyEnd <= body) {
            throw new MediaException.Malformed("empty lossless JPEG scan header");
        }
        var count = data[body] & 0xFF;
        if (count < 1 || count > 4 || bodyEnd - body != 4 + 2 * count) {
            throw new MediaException.Malformed("invalid lossless JPEG scan of " + count + " components");
        }
        var order = new int[count];
        var huffman = new DhtTable[count];
        var slots = new int[count];
        var lower = new int[count];
        var upper = new int[count];
        for (var k = 0; k < count; k++) {
            var id = data[body + 1 + 2 * k] & 0xFF;
            var component = 0;
            while (component < components && ids[component] != id) {
                component++;
            }
            var repeated = component < components && coded[component];
            for (var earlier = 0; earlier < k; earlier++) {
                repeated |= order[earlier] == component;
            }
            if (component == components || repeated) {
                throw new MediaException.Malformed("lossless JPEG scan codes an unknown or already coded component "
                        + id);
            }
            order[k] = component;
            var slot = (data[body + 2 + 2 * k] & 0xFF) >> 4;
            slots[k] = slot;
            if (arithmetic) {
                lower[k] = tables.lower[slot];
                upper[k] = tables.upper[slot];
            } else {
                if (slot > 3 || tables.huffman[slot] == null) {
                    throw new MediaException.Malformed("lossless JPEG scan references an undefined table");
                }
                huffman[k] = tables.huffman[slot];
            }
        }
        var select = data[body + 1 + 2 * count] & 0xFF;
        var transform = data[body + 3 + 2 * count] & 0x0F;
        if (differential) {
            select = 0;
        } else if (select < 1 || select > 7) {
            throw new MediaException.Malformed("invalid lossless JPEG predictor " + select);
        }
        if (transform >= precision) {
            throw new MediaException.Malformed("invalid lossless JPEG point transform " + transform);
        }
        for (var component : order) {
            coded[component] = true;
        }
        return new Scan(order, huffman, slots, lower, upper, select, transform, tables.restartInterval, bodyEnd);
    }

    /**
     * Parses the segments after a scan up to the next scan header.
     *
     * @param scanEnd the index of the marker that ended the previous scan
     * @param tables the tables in force, updated by the segments
     * @param coded which frame components earlier scans coded
     * @return the next scan, or {@code null} at {@code EOI}, at the end of the stream, or at a
     * segment that cannot continue the frame
     */
    private Scan nextScan(int scanEnd, Tables tables, boolean[] coded) {
        var position = scanEnd;
        try {
            while (true) {
                var marker = nextMarker(position);
                if (marker < 0) {
                    return null;
                }
                position = (int) (marker >>> 8);
                var code = (int) (marker & 0xFF);
                if (code == 0xD9 || code == 0xD8) {
                    return null;
                }
                if (!hasLength(code)) {
                    continue;
                }
                var bodyEnd = segmentEnd(position, code);
                if (code == 0xDA) {
                    return parseScan(position + 2, bodyEnd, tables, coded);
                }
                if (code >= 0xC0 && code <= 0xCF && code != 0xC4 && code != 0xCC) {
                    return null;
                }
                parseTables(code, position + 2, bodyEnd, tables);
                position = bodyEnd;
            }
        } catch (MediaException.Malformed exception) {
            return null;
        }
    }

    /**
     * Returns the index of the marker that ends the entropy-coded data of a scan: the first marker
     * that is neither a stuffed zero nor a restart marker.
     *
     * @param from the index of the first entropy-coded byte
     * @return the index of the marker's {@code 0xFF}, or the end of the stream
     */
    private int scanEnd(int from) {
        var position = from;
        while (position + 1 < end) {
            if ((data[position] & 0xFF) == 0xFF) {
                var next = data[position + 1] & 0xFF;
                if (next != 0x00 && next != 0xFF && (next < 0xD0 || next > 0xD7)) {
                    return position;
                }
            }
            position++;
        }
        return end;
    }

    /**
     * {@return the frame width in columns}
     */
    public int width() {
        return width;
    }

    /**
     * {@return the frame height in rows}
     */
    public int height() {
        return height;
    }

    /**
     * {@return the number of frame components, from 1 to 255}
     */
    public int components() {
        return components;
    }

    /**
     * {@return the sample precision in bits, from 2 to 16}
     */
    public int precision() {
        return precision;
    }

    /**
     * {@return whether the frame is arithmetic coded ({@code SOF11})}
     */
    public boolean arithmetic() {
        return arithmetic;
    }

    /**
     * Returns the sampling factors of a component.
     *
     * @param component the component, in frame order
     * @return a new array holding the horizontal and the vertical factor
     */
    public int[] sampling(int component) {
        return sampling[component].clone();
    }

    /**
     * Returns the width of a component, {@code ceil(X * h / Hmax)}.
     *
     * @param component the component, in frame order
     * @return the width in samples
     */
    public int componentWidth(int component) {
        return (int) (((long) width * sampling[component][0] + hMax - 1) / hMax);
    }

    /**
     * Returns the height of a component, {@code ceil(Y * v / Vmax)}.
     *
     * @param component the component, in frame order
     * @return the height in rows
     */
    public int componentHeight(int component) {
        return (int) (((long) height * sampling[component][1] + vMax - 1) / vMax);
    }

    /**
     * Returns whether every component has the size of the frame, which {@link #decode} requires.
     *
     * @return {@code true} when every component has the largest sampling factors
     */
    public boolean fullResolution() {
        for (var factor : sampling) {
            if (factor[0] != hMax || factor[1] != vMax) {
                return false;
            }
        }
        return true;
    }

    /**
     * Returns whether {@link #decode} streams the rows as it decodes them: the frame is Huffman
     * coded in a single scan whose minimum coded units hold one sample of each component.
     *
     * @return {@code true} for such a frame; {@code decode} otherwise first decodes every
     * component whole
     */
    public boolean streamsRows() {
        if (arithmetic || differential || first.components().length != components) {
            return false;
        }
        if (components == 1) {
            return true;
        }
        for (var factor : sampling) {
            if (factor[0] != 1 || factor[1] != 1) {
                return false;
            }
        }
        return true;
    }

    /**
     * Decodes a scan whose components may be subsampled, as Canon codes its mRAW and sRAW data:
     * one row of minimum coded units after another, each unit holding the samples of every
     * component in frame order, {@code h * v} samples of a component with sampling factors
     * {@code h} and {@code v}. A row of units covers the frame width divided by the first
     * component's horizontal factor, and there are as many rows as the frame height divided by its
     * vertical factor.
     *
     * <p>Only predictor 1 is accepted, with Canon's convention: each sample is predicted from the
     * previous sample of its component in coding order; the first sample of each component in the
     * first unit of a row is predicted from the same sample of the row above, and the first row
     * from {@code 2^(precision - 1)}.
     *
     * @param sink receives each row of units, the samples of each unit consecutive
     * @throws MediaException.Unsupported if the frame is arithmetic coded or coded in several scans,
     * or the scan uses another predictor, a point transform or restart intervals, none of which
     * Canon's convention defines
     */
    public void decodeUnits(RowSink sink) {
        if (arithmetic || first.components().length != components) {
            throw new MediaException.Unsupported("jpeg lossless", "Canon's unit convention defines no "
                    + (arithmetic ? "arithmetic coding" : "multi-scan frame"));
        }
        var predictor = first.predictor();
        if (predictor != 1 || first.pointTransform() != 0 || first.restartInterval() != 0) {
            throw new MediaException.Unsupported("jpeg lossless", "subsampled lossless JPEG with predictor " + predictor
                    + " is not supported");
        }
        var perUnit = 0;
        for (var factor : sampling) {
            perUnit += factor[0] * factor[1];
        }
        var units = width / sampling[0][0];
        var rows = height / sampling[0][1];
        var slots = new int[perUnit];
        var tablesOf = new DhtTable[perUnit];
        var slot = 0;
        for (var k = 0; k < components; k++) {
            var component = first.components()[k];
            for (var i = 0; i < sampling[component][0] * sampling[component][1]; i++) {
                slots[slot] = component;
                tablesOf[slot] = first.huffman()[k];
                slot++;
            }
        }
        var current = new int[units * perUnit];
        var firstOfRow = new int[perUnit];
        Arrays.fill(firstOfRow, 1 << (precision - 1));
        var last = new int[components];
        var reader = new BitReader(data, first.start(), end);
        var mask = (1 << precision) - 1;
        for (var row = 0; row < rows; row++) {
            var index = 0;
            for (var unit = 0; unit < units; unit++) {
                for (var k = 0; k < perUnit; k++) {
                    var component = slots[k];
                    var firstSample = unit == 0 && (k == 0 || slots[k - 1] != component);
                    var prediction = firstSample ? firstOfRow[k] : last[component];
                    var value = (prediction + reader.difference(tablesOf[k])) & mask;
                    current[index++] = value;
                    last[component] = value;
                    if (unit == 0) {
                        firstOfRow[k] = value;
                    }
                }
            }
            sink.row(row, current);
        }
    }

    /**
     * Decodes every scan, handing over the rows of each component.
     *
     * <p>The rows of a component arrive in order, each once; those of different components may
     * interleave in any order. The rows of a component that no scan codes are delivered last, as
     * zero samples.
     *
     * @param sink receives the rows
     * @throws MediaException.LimitExceeded if an arithmetic-coded scan needs a buffer beyond the
     * limits
     */
    public void decodeComponents(ComponentSink sink) {
        var tables = firstTables.copy();
        var coded = new boolean[components];
        for (var component : first.components()) {
            coded[component] = true;
        }
        var scan = first;
        var decoder = new ScanDecoder();
        while (true) {
            decoder.decode(scan, sink);
            var remaining = false;
            for (var done : coded) {
                remaining |= !done;
            }
            if (!remaining) {
                return;
            }
            var next = nextScan(decoder.end(scan), tables, coded);
            if (next == null) {
                break;
            }
            scan = next;
        }
        for (var component = 0; component < components; component++) {
            if (!coded[component]) {
                var zeros = new int[componentWidth(component)];
                for (var row = 0; row < componentHeight(component); row++) {
                    sink.row(component, row, zeros);
                }
            }
        }
    }

    /**
     * Decodes the scans of the frame, one at a time, reusing its buffers between them.
     */
    private final class ScanDecoder {
        /**
         * The unstuffed data of an arithmetic-coded scan, created on first use.
         */
        private EntropySegments segments;

        /**
         * The arithmetic decoder, created on first use.
         */
        private ArithmeticScanDecoder coder;

        /**
         * Decodes one scan.
         *
         * @param scan the scan header, with the tables in force
         * @param sink receives the rows of its components
         */
        void decode(Scan scan, ComponentSink sink) {
            var order = scan.components();
            var count = order.length;
            var interleaved = count > 1;
            int unitsWide;
            int unitsHigh;
            if (interleaved) {
                unitsWide = (width + hMax - 1) / hMax;
                unitsHigh = (height + vMax - 1) / vMax;
            } else {
                unitsWide = componentWidth(order[0]);
                unitsHigh = componentHeight(order[0]);
            }
            var hs = new int[count];
            var vs = new int[count];
            var heights = new int[count];
            // rows[k][0] is the last row of the previous unit row, rows[k][1 .. v] the rows of the current one
            var rows = new int[count][][];
            var categories = arithmetic ? new byte[count][][] : null;
            for (var k = 0; k < count; k++) {
                var component = order[k];
                hs[k] = interleaved ? sampling[component][0] : 1;
                vs[k] = interleaved ? sampling[component][1] : 1;
                heights[k] = componentHeight(component);
                rows[k] = new int[vs[k] + 1][unitsWide * hs[k]];
                if (arithmetic) {
                    categories[k] = new byte[vs[k] + 1][unitsWide * hs[k]];
                }
            }
            var transform = scan.pointTransform();
            var shifted = transform > 0 ? new int[unitsWide * hMax] : null;
            var initial = 1 << (precision - transform - 1);
            var mask = differential ? 0xFFFF : (1 << precision) - 1;
            var predictor = scan.predictor();
            var interval = scan.restartInterval();
            BitReader reader = null;
            int[] bounds = null;
            if (arithmetic) {
                if (segments == null) {
                    segments = new EntropySegments(limits);
                    coder = new ArithmeticScanDecoder(segments);
                }
                segments.load(data, scan.start(), end);
                coder.startLossless(scan.slots());
                bounds = new int[2 * count];
                for (var k = 0; k < count; k++) {
                    var lower = scan.lower()[k];
                    bounds[2 * k] = lower == 0 ? 0 : 1 << (lower - 1);
                    bounds[2 * k + 1] = 1 << scan.upper()[k];
                }
            } else {
                reader = new BitReader(data, scan.start(), end);
            }
            var restartRow = 0;
            var restartColumn = 0;
            var mcu = 0L;
            for (var unitRow = 0; unitRow < unitsHigh; unitRow++) {
                for (var unit = 0; unit < unitsWide; unit++) {
                    if (interval > 0 && mcu > 0 && mcu % interval == 0) {
                        if (arithmetic) {
                            coder.restartLossless();
                        } else {
                            reader.restart();
                        }
                        restartRow = unitRow;
                        restartColumn = unit;
                    }
                    mcu++;
                    for (var k = 0; k < count; k++) {
                        var h = hs[k];
                        var v = vs[k];
                        var table = scan.huffman()[k];
                        var component = rows[k];
                        var slot = scan.slots()[k];
                        for (var by = 0; by < v; by++) {
                            var current = component[by + 1];
                            var above = component[by];
                            var firstLine = unitRow == restartRow && by == 0;
                            var lineStart = restartColumn * h;
                            for (var bx = 0; bx < h; bx++) {
                                var x = unit * h + bx;
                                var fresh = firstLine && x >= lineStart;
                                int prediction;
                                if (predictor == 0) {
                                    prediction = 0;
                                } else if (fresh) {
                                    prediction = x == lineStart ? initial : current[x - 1];
                                } else if (x == 0) {
                                    prediction = above[0];
                                } else {
                                    var a = current[x - 1];
                                    var b = above[x];
                                    var c = above[x - 1];
                                    prediction = switch (predictor) {
                                        case 1 -> a;
                                        case 2 -> b;
                                        case 3 -> c;
                                        case 4 -> a + b - c;
                                        case 5 -> a + ((b - c) >> 1);
                                        case 6 -> b + ((a - c) >> 1);
                                        default -> (a + b) >> 1;
                                    };
                                }
                                int difference;
                                if (arithmetic) {
                                    var classes = categories[k];
                                    var left = x == 0 || (fresh && x == lineStart) ? 0 : classes[by + 1][x - 1];
                                    var up = fresh ? 0 : classes[by][x];
                                    difference = coder.losslessDifference(slot, 20 * left + 4 * up, up >= 3 ? 129 : 100);
                                    classes[by + 1][x] = classify(difference, bounds[2 * k], bounds[2 * k + 1]);
                                } else {
                                    difference = reader.difference(table);
                                }
                                current[x] = (prediction + difference) & mask;
                            }
                        }
                    }
                }
                for (var k = 0; k < count; k++) {
                    var v = vs[k];
                    var component = rows[k];
                    for (var by = 0; by < v; by++) {
                        var row = unitRow * v + by;
                        if (row >= heights[k]) {
                            break;
                        }
                        var samples = component[by + 1];
                        if (shifted != null) {
                            for (var x = 0; x < samples.length; x++) {
                                shifted[x] = samples[x] << transform;
                            }
                            samples = shifted;
                        }
                        sink.row(order[k], row, samples);
                    }
                    var last = component[v];
                    component[v] = component[0];
                    component[0] = last;
                    if (arithmetic) {
                        var classes = categories[k];
                        var lastClasses = classes[v];
                        classes[v] = classes[0];
                        classes[0] = lastClasses;
                    }
                }
            }
        }

        /**
         * Returns the index of the marker that ended the last scan decoded.
         *
         * @param scan that scan
         * @return the index of the marker's {@code 0xFF}, or the end of the stream
         */
        int end(Scan scan) {
            return arithmetic ? segments.scanEnd() : scanEnd(scan.start());
        }
    }

    /**
     * Classifies a decoded difference for the conditioning of T.81 F.1.4.4.1.2.
     *
     * @param difference the decoded difference, from -32768 to 32768
     * @param lower the largest magnitude classified as zero
     * @param upper the largest magnitude classified as small
     * @return 0 for zero, 1 for small positive, 2 for small negative, 3 for large positive and 4
     * for large negative
     */
    private static byte classify(int difference, int lower, int upper) {
        var magnitude = Math.abs(difference);
        if (magnitude <= lower) {
            return 0;
        }
        if (magnitude <= upper) {
            return (byte) (difference > 0 ? 1 : 2);
        }
        return (byte) (difference > 0 ? 3 : 4);
    }

    /**
     * Decodes every row of a frame whose components all have the frame's size.
     *
     * <p>When {@link #streamsRows()} holds, the rows are handed over as they decode; otherwise
     * every component is decoded first. A stream that ends early, or whose entropy-coded data is
     * corrupt, decodes to its end with zero bits supplied in place of the missing ones; the rows
     * are all delivered.
     *
     * @param sink receives the rows
     * @throws IllegalStateException if a component is subsampled ({@link #fullResolution()} is
     * {@code false})
     * @throws MediaException.LimitExceeded if the components decoded first exceed the limits
     */
    public void decode(RowSink sink) {
        if (!fullResolution()) {
            throw new IllegalStateException("the frame has subsampled components");
        }
        if (streamsRows()) {
            stream(sink);
            return;
        }
        var length = (long) width * height;
        LimitChecks.checkAllocation(limits, 2L * components * length);
        var planes = new short[components][(int) length];
        decodeComponents((component, row, samples) -> {
            var plane = planes[component];
            var at = row * width;
            for (var x = 0; x < width; x++) {
                plane[at + x] = (short) samples[x];
            }
        });
        var samples = new int[width * components];
        for (var y = 0; y < height; y++) {
            var at = y * width;
            for (var c = 0; c < components; c++) {
                var plane = planes[c];
                for (var x = 0; x < width; x++) {
                    samples[x * components + c] = plane[at + x] & 0xFFFF;
                }
            }
            sink.row(y, samples);
        }
    }

    /**
     * Decodes the single scan of a frame for which {@link #streamsRows()} holds, row by row.
     *
     * @param sink receives the rows
     */
    private void stream(RowSink sink) {
        var stride = width * components;
        var previous = new int[stride];
        var current = new int[stride];
        var reader = new BitReader(data, first.start(), end);
        var pointTransform = first.pointTransform();
        var predictor = first.predictor();
        var restartInterval = first.restartInterval();
        var order = first.components();
        var tables = new DhtTable[components];
        for (var k = 0; k < components; k++) {
            tables[k] = first.huffman()[k];
        }
        var initial = 1 << (precision - pointTransform - 1);
        var mask = (1 << precision) - 1;
        var mcusLeft = restartInterval;
        var restartRow = true;
        for (var row = 0; row < height; row++) {
            for (var column = 0; column < width; column++) {
                var base = column * components;
                if (restartInterval > 0 && mcusLeft == 0) {
                    reader.restart();
                    mcusLeft = restartInterval;
                    restartRow = true;
                    for (var k = 0; k < components; k++) {
                        current[base + order[k]] = (initial + reader.difference(tables[k])) & mask;
                    }
                    mcusLeft--;
                    continue;
                }
                for (var k = 0; k < components; k++) {
                    var index = base + order[k];
                    int prediction;
                    if (row == 0 && column == 0) {
                        prediction = initial;
                    } else if (row == 0 || (restartRow && column > 0)) {
                        prediction = current[index - components];
                    } else if (column == 0) {
                        prediction = previous[index];
                    } else {
                        var a = current[index - components];
                        var b = previous[index];
                        var c = previous[index - components];
                        prediction = switch (predictor) {
                            case 1 -> a;
                            case 2 -> b;
                            case 3 -> c;
                            case 4 -> a + b - c;
                            case 5 -> a + ((b - c) >> 1);
                            case 6 -> b + ((a - c) >> 1);
                            default -> (a + b) >> 1;
                        };
                    }
                    current[index] = (prediction + reader.difference(tables[k])) & mask;
                }
                if (restartInterval > 0) {
                    mcusLeft--;
                }
            }
            restartRow = false;
            if (pointTransform > 0) {
                var shifted = previous;
                for (var index = 0; index < stride; index++) {
                    shifted[index] = current[index] << pointTransform;
                }
                sink.row(row, shifted);
                previous = current;
                current = shifted;
            } else {
                sink.row(row, current);
                var swap = previous;
                previous = current;
                current = swap;
            }
        }
    }

    /**
     * Holds one Huffman table of a {@code DHT} segment: a lookup table for short codes and the
     * canonical code bounds for longer ones.
     */
    static final class DhtTable {
        /**
         * For each {@link #LOOKUP_BITS}-bit prefix, the code length in the low byte and the value
         * above it, or {@code 0} when the code is longer.
         */
        final int[] lookup = new int[1 << LOOKUP_BITS];

        /**
         * The largest code of each length, or {@code -1}.
         */
        final int[] maxCode = new int[18];

        /**
         * The value index of the first code of each length minus that code.
         */
        final int[] offset = new int[17];

        /**
         * The values in code order.
         */
        final int[] values;

        /**
         * Builds a table from the counts and values of a {@code DHT} segment.
         *
         * @param data the segment bytes
         * @param counts the index of the 16 code counts
         * @param valuesAt the index of the values
         * @param total the number of values
         */
        DhtTable(byte[] data, int counts, int valuesAt, int total) {
            values = new int[total];
            for (var index = 0; index < total; index++) {
                values[index] = data[valuesAt + index] & 0xFF;
            }
            var code = 0;
            var index = 0;
            for (var length = 1; length <= 16; length++) {
                var count = data[counts + length - 1] & 0xFF;
                offset[length] = index - code;
                if (count == 0) {
                    maxCode[length] = -1;
                } else {
                    for (var k = 0; k < count; k++) {
                        if (length <= LOOKUP_BITS) {
                            var shift = LOOKUP_BITS - length;
                            var first = code << shift;
                            for (var fill = 0; fill < (1 << shift); fill++) {
                                if (first + fill < lookup.length) {
                                    lookup[first + fill] = (values[index] << 8) | length;
                                }
                            }
                        }
                        code++;
                        index++;
                    }
                    maxCode[length] = code - 1;
                }
                code <<= 1;
            }
            maxCode[17] = Integer.MAX_VALUE;
        }
    }

    /**
     * Reads the entropy-coded bits of a scan, most significant first, removing the stuffed zero
     * bytes and stopping at markers.
     */
    static final class BitReader {
        /**
         * The stream.
         */
        private final byte[] data;

        /**
         * The index after the stream.
         */
        private final int end;

        /**
         * The index of the next byte to load.
         */
        private int position;

        /**
         * The buffered bits, left-aligned in the low {@link #count} bits.
         */
        private long buffer;

        /**
         * The number of buffered bits.
         */
        private int count;

        /**
         * Whether a marker was reached: zero bits are supplied from then on.
         */
        private boolean marker;

        /**
         * Constructs a reader.
         *
         * @param data the stream
         * @param start the first entropy-coded byte
         * @param end the index after the stream
         */
        BitReader(byte[] data, int start, int end) {
            this.data = data;
            this.position = start;
            this.end = end;
        }

        /**
         * Loads bytes until at least 32 bits are buffered.
         */
        private void fill() {
            while (count <= 56) {
                var next = 0;
                if (!marker && position < end) {
                    next = data[position] & 0xFF;
                    if (next == 0xFF) {
                        var following = position + 1 < end ? data[position + 1] & 0xFF : 0xD9;
                        if (following == 0x00) {
                            position += 2;
                        } else {
                            marker = true;
                            next = 0;
                        }
                    } else {
                        position++;
                    }
                }
                buffer = (buffer << 8) | next;
                count += 8;
            }
        }

        /**
         * Decodes one difference: a Huffman-coded category followed by that many bits.
         *
         * @param table the Huffman table
         * @return the signed difference
         */
        int difference(DhtTable table) {
            if (count < 32) {
                fill();
            }
            var peek = (int) (buffer >>> (count - LOOKUP_BITS)) & ((1 << LOOKUP_BITS) - 1);
            var entry = table.lookup[peek];
            int category;
            if (entry != 0) {
                count -= entry & 0xFF;
                category = entry >>> 8;
            } else {
                category = slowDecode(table);
            }
            if (category == 0) {
                return 0;
            }
            if (category >= 16) {
                return 32768;
            }
            if (count < category) {
                fill();
            }
            var bits = (int) (buffer >>> (count - category)) & ((1 << category) - 1);
            count -= category;
            return bits < (1 << (category - 1)) ? bits - (1 << category) + 1 : bits;
        }

        /**
         * Decodes a code longer than the lookup table resolves.
         *
         * @param table the Huffman table
         * @return the decoded value
         */
        private int slowDecode(DhtTable table) {
            var code = 0;
            for (var length = 1; length <= 16; length++) {
                code = (code << 1) | (int) ((buffer >>> (count - length)) & 1);
                if (table.maxCode[length] >= code) {
                    count -= length;
                    var index = code + table.offset[length];
                    return index >= 0 && index < table.values.length ? table.values[index] : 0;
                }
            }
            count -= 16;
            return 0;
        }

        /**
         * Skips to the byte after the next restart marker and empties the buffer.
         */
        void restart() {
            count = 0;
            buffer = 0;
            marker = false;
            while (position + 1 < end) {
                if ((data[position] & 0xFF) == 0xFF) {
                    var following = data[position + 1] & 0xFF;
                    if (following >= 0xD0 && following <= 0xD7) {
                        position += 2;
                        return;
                    }
                    if (following != 0x00 && following != 0xFF) {
                        return;
                    }
                }
                position++;
            }
        }
    }
}
