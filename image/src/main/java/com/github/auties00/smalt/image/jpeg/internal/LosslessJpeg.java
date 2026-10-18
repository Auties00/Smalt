package com.github.auties00.smalt.image.jpeg.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.internal.io.ByteArrays;

import java.util.Arrays;

/**
 * Decodes a lossless JPEG stream (ITU-T T.81 annex H, process 14, {@code SOF3}): the coding of the
 * sensor data of DNG files, Canon CR2 files and several other raw formats.
 *
 * <p>The decoder supports every predictor (1 to 7), the point transform, restart intervals, one to
 * four interleaved components with sampling factors of one, and samples of 2 to 16 bits. The
 * decoded samples are handed over a row at a time, the components of each column interleaved, in
 * the order the frame header lists them; a frame of {@code X} columns and {@code N} components
 * thus yields rows of {@code X * N} samples. Streams whose first component is subsampled, as Canon
 * writes its mRAW and sRAW data, are opened with {@link #LosslessJpeg(byte[], int, int, boolean)}
 * and decoded with {@link #decodeUnits}.
 *
 * <p>Instances are not thread-safe.
 *
 * @implNote This implementation follows the specification directly. Huffman codes of up to
 * {@link #LOOKUP_BITS} bits, which are nearly all of them, decode with one table lookup; longer ones
 * walk the canonical code lengths.
 */
public final class LosslessJpeg {
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
     * The number of interleaved components.
     */
    private final int components;

    /**
     * The sample precision in bits.
     */
    private final int precision;

    /**
     * The stream.
     */
    private final byte[] data;

    /**
     * The index of the first entropy-coded byte of the scan.
     */
    private final int scanStart;

    /**
     * The index after the stream.
     */
    private final int end;

    /**
     * The predictor selection value, from 1 to 7.
     */
    private final int predictor;

    /**
     * The point transform.
     */
    private final int pointTransform;

    /**
     * The restart interval in MCUs, or {@code 0}.
     */
    private final int restartInterval;

    /**
     * The Huffman table of each component, in frame order.
     */
    private final DhtTable[] tables;

    /**
     * The horizontal and vertical sampling factors of each component, in frame order.
     */
    private final int[][] sampling;

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
        this(data, offset, length, subsampled, 0);
    }

    /**
     * Parses the headers of a stream up to its first scan, taking the frame height from a
     * {@code DNL} segment the caller has read when the frame header declares zero rows.
     *
     * @param data the bytes holding the stream
     * @param offset the index of its {@code SOI} marker
     * @param length the length of the stream
     * @param subsampled whether sampling factors of up to 2 are accepted, for {@link #decodeUnits}
     * @param lines the frame height to use when the frame header declares zero rows, or 0 for none
     * @throws MediaException.Malformed if the headers are corrupt
     * @throws MediaException.Unsupported if the stream is not a lossless JPEG this decoder handles
     */
    LosslessJpeg(byte[] data, int offset, int length, boolean subsampled, int lines) {
        if (offset < 0 || length < 4 || offset > data.length - length) {
            throw new MediaException.Malformed("truncated lossless JPEG stream");
        }
        this.data = data;
        this.end = offset + length;
        if ((data[offset] & 0xFF) != 0xFF || (data[offset + 1] & 0xFF) != 0xD8) {
            throw new MediaException.Malformed("lossless JPEG stream does not start with SOI");
        }
        var defined = new DhtTable[4];
        var frameWidth = -1;
        var frameHeight = -1;
        var frameComponents = 0;
        var framePrecision = 0;
        int[] ids = null;
        int[][] factors = null;
        var interval = 0;
        var position = offset + 2;
        while (true) {
            while (position < end && (data[position] & 0xFF) != 0xFF) {
                position++;
            }
            while (position < end && (data[position] & 0xFF) == 0xFF) {
                position++;
            }
            if (position + 2 >= end) {
                throw new MediaException.Malformed("lossless JPEG stream ends before its scan");
            }
            var marker = data[position] & 0xFF;
            var segment = ByteArrays.u16be(data, position + 1);
            var body = position + 3;
            if (segment < 2 || body + segment - 2 > end) {
                throw new MediaException.Malformed("truncated lossless JPEG segment 0x" + Integer.toHexString(marker));
            }
            var bodyEnd = body + segment - 2;
            switch (marker) {
                case 0xC3 -> {
                    if (segment < 8) {
                        throw new MediaException.Malformed("truncated SOF3 segment");
                    }
                    framePrecision = data[body] & 0xFF;
                    frameHeight = ByteArrays.u16be(data, body + 1);
                    frameWidth = ByteArrays.u16be(data, body + 3);
                    frameComponents = data[body + 5] & 0xFF;
                    if (frameComponents < 1 || frameComponents > 4 || segment != 8 + 3 * frameComponents) {
                        throw new MediaException.Malformed("invalid SOF3 component count " + frameComponents);
                    }
                    if (framePrecision < 2 || framePrecision > 16) {
                        throw new MediaException.Malformed("invalid lossless JPEG precision " + framePrecision);
                    }
                    ids = new int[frameComponents];
                    factors = new int[frameComponents][];
                    for (var component = 0; component < frameComponents; component++) {
                        ids[component] = data[body + 6 + 3 * component] & 0xFF;
                        var sampling = data[body + 7 + 3 * component] & 0xFF;
                        factors[component] = new int[]{sampling >> 4, sampling & 15};
                        var valid = subsampled ? factors[component][0] >= 1 && factors[component][0] <= 2
                                && factors[component][1] >= 1 && factors[component][1] <= 2 : sampling == 0x11;
                        if (!valid) {
                            throw new MediaException.Unsupported("jpeg lossless",
                                    "lossless JPEG sampling factors of " + (sampling >> 4) + "x" + (sampling & 15)
                                            + " are not supported");
                        }
                    }
                }
                case 0xC0, 0xC1, 0xC2, 0xC5, 0xC6, 0xC7, 0xC9, 0xCA, 0xCB, 0xCD, 0xCE, 0xCF ->
                        throw new MediaException.Unsupported("jpeg", "the stream is not a lossless Huffman JPEG (SOF"
                                + (marker - 0xC0) + ")");
                case 0xC4 -> {
                    var at = body;
                    while (at < bodyEnd) {
                        var index = data[at] & 0x0F;
                        if ((data[at] & 0xF0) != 0 || index > 3 || at + 17 > bodyEnd) {
                            throw new MediaException.Malformed("invalid lossless JPEG Huffman table");
                        }
                        var total = 0;
                        for (var bits = 1; bits <= 16; bits++) {
                            total += data[at + bits] & 0xFF;
                        }
                        if (at + 17 + total > bodyEnd || total > 256) {
                            throw new MediaException.Malformed("truncated lossless JPEG Huffman table");
                        }
                        defined[index] = new DhtTable(data, at + 1, at + 17, total);
                        at += 17 + total;
                    }
                }
                case 0xDD -> {
                    if (segment != 4) {
                        throw new MediaException.Malformed("invalid DRI segment");
                    }
                    interval = ByteArrays.u16be(data, body);
                }
                case 0xDA -> {
                    if (ids == null) {
                        throw new MediaException.Malformed("lossless JPEG scan before its frame header");
                    }
                    var scanComponents = data[body] & 0xFF;
                    if (scanComponents != frameComponents || segment != 6 + 2 * scanComponents) {
                        throw new MediaException.Unsupported("jpeg lossless",
                                "non-interleaved lossless JPEG scans are not supported");
                    }
                    var tablesOf = new DhtTable[frameComponents];
                    for (var component = 0; component < scanComponents; component++) {
                        var id = data[body + 1 + 2 * component] & 0xFF;
                        var table = (data[body + 2 + 2 * component] & 0xFF) >> 4;
                        if (id != ids[component] || table > 3 || defined[table] == null) {
                            throw new MediaException.Malformed("lossless JPEG scan references an undefined table");
                        }
                        tablesOf[component] = defined[table];
                    }
                    var select = data[body + 1 + 2 * scanComponents] & 0xFF;
                    var transform = data[body + 3 + 2 * scanComponents] & 0x0F;
                    if (select < 1 || select > 7) {
                        throw new MediaException.Malformed("invalid lossless JPEG predictor " + select);
                    }
                    if (transform >= framePrecision) {
                        throw new MediaException.Malformed("invalid lossless JPEG point transform " + transform);
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
                    this.predictor = select;
                    this.pointTransform = transform;
                    this.restartInterval = interval;
                    this.tables = tablesOf;
                    this.sampling = factors;
                    this.scanStart = bodyEnd;
                    return;
                }
                case 0xD9 -> throw new MediaException.Malformed("lossless JPEG stream ends before its scan");
                default -> {
                }
            }
            position = bodyEnd;
        }
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
     * {@return the number of interleaved components, from 1 to 4}
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
     * Returns the sampling factors of a component.
     *
     * @param component the component, in frame order
     * @return a new array holding the horizontal and the vertical factor
     */
    public int[] sampling(int component) {
        return sampling[component].clone();
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
     * @throws MediaException.Unsupported if the scan uses another predictor, a point transform or
     * restart intervals
     */
    public void decodeUnits(RowSink sink) {
        if (predictor != 1 || pointTransform != 0 || restartInterval != 0) {
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
        for (var component = 0; component < components; component++) {
            for (var k = 0; k < sampling[component][0] * sampling[component][1]; k++) {
                slots[slot] = component;
                tablesOf[slot] = tables[component];
                slot++;
            }
        }
        var current = new int[units * perUnit];
        var firstOfRow = new int[perUnit];
        Arrays.fill(firstOfRow, 1 << (precision - 1));
        var last = new int[components];
        var reader = new BitReader(data, scanStart, end);
        var mask = (1 << precision) - 1;
        for (var row = 0; row < rows; row++) {
            var index = 0;
            for (var unit = 0; unit < units; unit++) {
                for (var k = 0; k < perUnit; k++) {
                    var component = slots[k];
                    var first = unit == 0 && (k == 0 || slots[k - 1] != component);
                    var prediction = first ? firstOfRow[k] : last[component];
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
     * Decodes every row of the scan.
     *
     * <p>A stream that ends early, or whose entropy-coded data is corrupt, decodes to its end with
     * zero bits supplied in place of the missing ones; the rows are all delivered.
     *
     * @param sink receives the rows
     */
    public void decode(RowSink sink) {
        var stride = width * components;
        var previous = new int[stride];
        var current = new int[stride];
        var reader = new BitReader(data, scanStart, end);
        var initial = 1 << (precision - pointTransform - 1);
        var mask = (1 << precision) - 1;
        var mcusLeft = restartInterval;
        var restartRow = true;
        var restartColumnZero = true;
        for (var row = 0; row < height; row++) {
            for (var column = 0; column < width; column++) {
                if (restartInterval > 0 && mcusLeft == 0) {
                    reader.restart();
                    mcusLeft = restartInterval;
                    restartRow = true;
                    restartColumnZero = column == 0;
                    for (var component = 0; component < components; component++) {
                        current[column * components + component] = decodeSample(reader, component, initial, mask);
                    }
                    mcusLeft--;
                    continue;
                }
                var base = column * components;
                for (var component = 0; component < components; component++) {
                    var index = base + component;
                    int prediction;
                    if (row == 0 && column == 0) {
                        prediction = initial;
                    } else if (restartRow && row > 0 && column == 0 && !restartColumnZero) {
                        prediction = previous[index];
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
                    current[index] = (prediction + reader.difference(tables[component])) & mask;
                }
                if (restartInterval > 0) {
                    mcusLeft--;
                }
            }
            if (restartRow && row > 0) {
                restartRow = false;
            } else if (row == 0) {
                restartRow = false;
            }
            restartColumnZero = false;
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
     * Decodes the first sample of a restart interval, predicted from the initial value.
     *
     * @param reader the bits
     * @param component the component index
     * @param initial the initial prediction
     * @param mask the sample mask
     * @return the sample
     */
    private int decodeSample(BitReader reader, int component, int initial, int mask) {
        return (initial + reader.difference(tables[component])) & mask;
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
