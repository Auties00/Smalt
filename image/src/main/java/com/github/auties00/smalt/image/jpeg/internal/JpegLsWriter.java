/* Ports CharLS (commit 1153c25: charls_jpegls_encoder.cpp, jpeg_stream_writer.cpp, scan_encoder.hpp,
 * scan_encoder_core.hpp, scan_encoder_impl.hpp, color_transform.hpp). Copyright (c) 2007 Jan de Vaan and Victor
 * Derks (Team CharLS); BSD 3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.image.jpeg.internal;

import com.github.auties00.smalt.internal.io.ByteWriter;
import com.github.auties00.smalt.internal.task.ParallelBands;

import java.util.Arrays;

/**
 * Writes a JPEG-LS stream (ITU-T T.87) from planar samples.
 *
 * <p>The stream is {@code SOI}, an optional SPIFF header ({@code APP8} with its end-of-directory entry), the
 * {@code APP8 "mrfx"} segment of an HP color transform, {@code SOF55} (with an oversize-dimension {@code LSE}
 * segment when a dimension exceeds 65535), an {@code LSE} segment of preset coding parameters when they differ
 * from the defaults, a {@code DRI} segment when restart intervals are used, the scans (one per component when not
 * interleaved), and {@code EOI}. Without restart intervals the bytes equal CharLS's for the same samples and
 * settings.
 */
public final class JpegLsWriter {
    /**
     * Prevents instantiation.
     */
    private JpegLsWriter() {
        throw new AssertionError();
    }

    /**
     * Holds the samples and the settings of a stream.
     *
     * @param width the frame width
     * @param height the frame height
     * @param precision the sample precision, from 2 to 16
     * @param horizontal the horizontal sampling factor of each component, from 1 to 4
     * @param vertical the vertical sampling factor of each component, from 1 to 4
     * @param planes the samples of each component, {@code ceil(width * h / Hmax)} by
     * {@code ceil(height * v / Vmax)} in raster order, each from 0 to {@code 2^precision - 1}
     * @param interleave the interleave mode: 0 none, 1 line, 2 sample
     * @param near the near-lossless bound
     * @param presets {@code T1}, {@code T2}, {@code T3} and {@code RESET}, 0 for a default
     * @param transform the HP color transform, 0 for none or 1 to 3
     * @param restartInterval the restart interval in minimum coded units, or 0
     * @param spiffColorSpace the color space of the SPIFF header to write, or {@code -1} for none
     * @param maxval the largest sample value {@code MAXVAL}, from 1 to {@code 2^precision - 1}, or 0 for
     * {@code 2^precision - 1}
     */
    public record Stream(int width, int height, int precision, int[] horizontal, int[] vertical, int[][] planes,
                         int interleave, int near, int[] presets, int transform, int restartInterval,
                         int spiffColorSpace, int maxval) {
    }

    /**
     * Encodes a stream.
     *
     * <p>Arguments are not checked; values outside the documented ranges give unspecified results.
     *
     * @param stream the samples and settings
     * @return the JPEG-LS stream
     */
    public static byte[] encode(Stream stream) {
        var count = stream.planes().length;
        var out = new ByteWriter(64 + 2 * stream.width() * stream.height() * count);
        marker(out, 0xD8);
        if (stream.spiffColorSpace() >= 0) {
            marker(out, 0xE8);
            out.u16be(32);
            out.bytes(new byte[]{'S', 'P', 'I', 'F', 'F', 0, 2, 0, 0});
            out.u8(count);
            out.u32be(stream.height());
            out.u32be(stream.width());
            out.u8(stream.spiffColorSpace());
            out.u8(stream.precision());
            out.u8(6);
            out.u8(0);
            out.u32be(1);
            out.u32be(1);
            marker(out, 0xE8);
            out.u16be(8);
            out.bytes(new byte[]{0, 0, 0, 1, (byte) 0xFF, (byte) 0xD8});
        }
        if (stream.transform() != 0) {
            marker(out, 0xE8);
            out.u16be(7);
            out.bytes(new byte[]{'m', 'r', 'f', 'x', (byte) stream.transform()});
        }
        var oversize = stream.width() > 65535 || stream.height() > 65535;
        marker(out, 0xF7);
        out.u16be(8 + 3 * count);
        out.u8(stream.precision());
        out.u16be(oversize ? 0 : stream.height());
        out.u16be(oversize ? 0 : stream.width());
        out.u8(count);
        for (var c = 0; c < count; c++) {
            out.u8(c + 1);
            out.u8((stream.horizontal()[c] << 4) | stream.vertical()[c]);
            out.u8(0);
        }
        if (oversize) {
            marker(out, 0xF8);
            out.u16be(12);
            out.u8(4);
            out.u8(4);
            out.u32be(stream.height());
            out.u32be(stream.width());
        }
        var maxval = stream.maxval() != 0 ? stream.maxval() : (1 << stream.precision()) - 1;
        var thresholds = resolve(stream.presets(), maxval, stream.near());
        var defaults = JpegLsModel.defaults(maxval, stream.near());
        if (!Arrays.equals(thresholds, defaults) || maxval != (1 << stream.precision()) - 1) {
            marker(out, 0xF8);
            out.u16be(13);
            out.u8(1);
            out.u16be(maxval);
            for (var value : thresholds) {
                out.u16be(value);
            }
        }
        if (stream.restartInterval() > 0) {
            var interval = stream.restartInterval();
            marker(out, 0xDD);
            if (interval <= 0xFFFF) {
                out.u16be(4);
                out.u16be(interval);
            } else {
                out.u16be(6);
                out.u32be(interval);
            }
        }
        var model = new JpegLsModel(maxval, stream.near(), thresholds[0], thresholds[1], thresholds[2],
                thresholds[3]);
        if (stream.interleave() == 0) {
            var scans = new ByteWriter[count];
            var work = 64L * stream.width() * stream.height() * count;
            var bands = ParallelBands.bands(count, work);
            ParallelBands.run(bands, band -> {
                var last = ParallelBands.start(band + 1, bands, count);
                var scanModel = band == 0 ? model : new JpegLsModel(model.maxval, model.near, thresholds[0],
                        thresholds[1], thresholds[2], thresholds[3]);
                for (var c = ParallelBands.start(band, bands, count); c < last; c++) {
                    var scan = new ByteWriter(64 + 2 * stream.planes()[c].length);
                    new ScanEncoder(stream, scanModel, scan, new int[]{c}).encode();
                    scans[c] = scan;
                }
            });
            for (var c = 0; c < count; c++) {
                scanHeader(out, new int[]{c}, stream.near(), 0);
                out.bytes(scans[c].buffer(), 0, scans[c].size());
            }
        } else {
            var all = new int[count];
            for (var c = 0; c < count; c++) {
                all[c] = c;
            }
            scanHeader(out, all, stream.near(), stream.interleave());
            new ScanEncoder(stream, model, out, all).encode();
        }
        marker(out, 0xD9);
        return out.toByteArray();
    }

    /**
     * Returns the default thresholds and reset value of ITU-T T.87 C.2.4.1.1.1.
     *
     * @param maxval the largest sample value
     * @param near the near-lossless bound
     * @return {@code T1}, {@code T2}, {@code T3} and {@code RESET}
     */
    public static int[] defaults(int maxval, int near) {
        return JpegLsModel.defaults(maxval, near);
    }

    /**
     * Resolves the thresholds and reset value, defaults included.
     *
     * @param presets the requested values, 0 for a default
     * @param maxval the largest sample value
     * @param near the near-lossless bound
     * @return {@code T1}, {@code T2}, {@code T3} and {@code RESET}
     */
    static int[] resolve(int[] presets, int maxval, int near) {
        var defaults = JpegLsModel.defaults(maxval, near);
        var resolved = new int[4];
        for (var k = 0; k < 4; k++) {
            resolved[k] = presets[k] != 0 ? presets[k] : defaults[k];
        }
        return resolved;
    }

    /**
     * Writes a marker.
     *
     * @param out the output
     * @param code the marker code
     */
    private static void marker(ByteWriter out, int code) {
        out.u8(0xFF);
        out.u8(code);
    }

    /**
     * Writes a scan header.
     *
     * @param out the output
     * @param components the components of the scan
     * @param near the near-lossless bound
     * @param interleave the interleave mode
     */
    private static void scanHeader(ByteWriter out, int[] components, int near, int interleave) {
        marker(out, 0xDA);
        out.u16be(6 + 2 * components.length);
        out.u8(components.length);
        for (var component : components) {
            out.u8(component + 1);
            out.u8(0);
        }
        out.u8(near);
        out.u8(interleave);
        out.u8(0);
    }

    /**
     * Writes bits most significant first, stuffing a zero bit after every {@code 0xFF} byte (T.87 C.1.2).
     */
    private static final class BitWriter {
        /**
         * The output.
         */
        private final ByteWriter out;

        /**
         * The bits of the byte being assembled, right-aligned.
         */
        private int current;

        /**
         * The number of bits in {@link #current}.
         */
        private int filled;

        /**
         * The number of bits the byte being assembled holds: 7 after {@code 0xFF}, else 8.
         */
        private int capacity = 8;

        /**
         * Whether the last byte written was {@code 0xFF}.
         */
        private boolean afterFf;

        /**
         * Constructs a writer.
         *
         * @param out the output
         */
        BitWriter(ByteWriter out) {
            this.out = out;
        }

        /**
         * Appends bits.
         *
         * @param value the bits, right-aligned
         * @param bits the number of bits, from 0 to 32
         */
        void bits(long value, int bits) {
            while (bits > 0) {
                var take = Math.min(bits, capacity - filled);
                var chunk = (int) (value >>> (bits - take)) & ((1 << take) - 1);
                current = (current << take) | chunk;
                filled += take;
                bits -= take;
                if (filled == capacity) {
                    emit();
                }
            }
        }

        /**
         * Appends zero bits.
         *
         * @param count the number of bits
         */
        void zeros(int count) {
            while (count > 0) {
                var take = Math.min(count, 32);
                bits(0, take);
                count -= take;
            }
        }

        /**
         * Writes the byte being assembled.
         */
        private void emit() {
            out.u8(current);
            afterFf = current == 0xFF;
            capacity = afterFf ? 7 : 8;
            current = 0;
            filled = 0;
        }

        /**
         * Pads the data with zero bits to a byte boundary, adding a zero byte when the last byte is
         * {@code 0xFF}.
         */
        void flush() {
            if (filled > 0) {
                current <<= capacity - filled;
                emit();
            }
            if (afterFf) {
                out.u8(0);
                afterFf = false;
                capacity = 8;
            }
        }
    }

    /**
     * Encodes the samples of one scan (T.87 annex A, and annex B for interleaved scans).
     */
    private static final class ScanEncoder {
        /**
         * The stream.
         */
        private final Stream stream;

        /**
         * The context model.
         */
        private final JpegLsModel model;

        /**
         * The output.
         */
        private final ByteWriter out;

        /**
         * The bit writer.
         */
        private final BitWriter writer;

        /**
         * The components of the scan.
         */
        private final int[] components;

        /**
         * The largest horizontal sampling factor.
         */
        private final int hMax;

        /**
         * The largest vertical sampling factor.
         */
        private final int vMax;

        /**
         * The signed context of each component of the sample being coded in a sample-interleaved scan.
         */
        private final int[] contexts = new int[4];

        /**
         * Constructs the encoder of a scan.
         *
         * @param stream the samples and settings of the stream
         * @param model the context model, reset by the encoder
         * @param out the output
         * @param components the components of the scan
         */
        ScanEncoder(Stream stream, JpegLsModel model, ByteWriter out, int[] components) {
            this.stream = stream;
            this.model = model;
            this.out = out;
            this.writer = new BitWriter(out);
            this.components = components;
            var horizontal = 1;
            var vertical = 1;
            for (var c = 0; c < stream.planes().length; c++) {
                horizontal = Math.max(horizontal, stream.horizontal()[c]);
                vertical = Math.max(vertical, stream.vertical()[c]);
            }
            this.hMax = horizontal;
            this.vMax = vertical;
        }

        /**
         * {@return the width of a component}
         *
         * @param component the component, in frame order
         */
        private int columns(int component) {
            return (int) (((long) stream.width() * stream.horizontal()[component] + hMax - 1) / hMax);
        }

        /**
         * {@return the height of a component}
         *
         * @param component the component, in frame order
         */
        private int rows(int component) {
            return (int) (((long) stream.height() * stream.vertical()[component] + vMax - 1) / vMax);
        }

        /**
         * Encodes the scan.
         */
        void encode() {
            model.reset();
            switch (stream.interleave()) {
                case 0 -> encodeSingle();
                case 1 -> encodeLines();
                default -> encodeSamples();
            }
            writer.flush();
        }

        /**
         * Starts a restart interval: ends the data of the previous one and writes the restart marker.
         *
         * @param index the index of the interval, from 1
         * @param lines the lines to clear
         */
        private void restart(int index, int[][]... lines) {
            writer.flush();
            out.u8(0xFF);
            out.u8(0xD0 + ((index - 1) & 7));
            model.reset();
            for (var pair : lines) {
                Arrays.fill(pair[0], 0);
                Arrays.fill(pair[1], 0);
            }
        }

        /**
         * Encodes a scan of one component.
         */
        private void encodeSingle() {
            var component = components[0];
            var columns = columns(component);
            var rows = rows(component);
            var plane = stream.planes()[component];
            var lines = new int[][]{new int[columns + 2], new int[columns + 2]};
            var interval = stream.restartInterval();
            for (var row = 0; row < rows; row++) {
                if (interval > 0 && row > 0 && row % interval == 0) {
                    restart(row / interval, lines);
                }
                var previous = lines[row & 1];
                var current = lines[(row & 1) ^ 1];
                System.arraycopy(plane, row * columns, current, 1, columns);
                encodeLine(previous, current, columns);
            }
        }

        /**
         * Encodes a line-interleaved scan, completing the last minimum coded unit with copies of each
         * component's last line.
         */
        private void encodeLines() {
            var count = components.length;
            var columns = new int[count];
            var rows = new int[count];
            var lines = new int[count][][];
            var runIndexes = new int[count];
            for (var k = 0; k < count; k++) {
                columns[k] = columns(components[k]);
                rows[k] = rows(components[k]);
                lines[k] = new int[][]{new int[columns[k] + 2], new int[columns[k] + 2]};
            }
            var transformed = stream.transform() != 0 ? new int[3][columns[0]] : null;
            var units = (stream.height() + vMax - 1) / vMax;
            var interval = stream.restartInterval();
            for (var unit = 0; unit < units; unit++) {
                if (interval > 0 && unit > 0 && unit % interval == 0) {
                    restart(unit / interval, lines);
                    Arrays.fill(runIndexes, 0);
                }
                if (transformed != null) {
                    transform(unit, transformed);
                }
                for (var k = 0; k < count; k++) {
                    var component = components[k];
                    var v = stream.vertical()[component];
                    for (var line = 0; line < v; line++) {
                        var previous = lines[k][0];
                        var current = lines[k][1];
                        var row = Math.min(unit * v + line, rows[k] - 1);
                        if (transformed != null) {
                            System.arraycopy(transformed[k], 0, current, 1, columns[k]);
                        } else {
                            System.arraycopy(stream.planes()[component], row * columns[k], current, 1, columns[k]);
                        }
                        model.runIndex = runIndexes[k];
                        encodeLine(previous, current, columns[k]);
                        runIndexes[k] = model.runIndex;
                        lines[k][0] = current;
                        lines[k][1] = previous;
                    }
                }
            }
        }

        /**
         * Encodes a sample-interleaved scan.
         */
        private void encodeSamples() {
            var count = components.length;
            var columns = columns(components[0]);
            var rows = rows(components[0]);
            var lines = new int[][]{new int[count * (columns + 2)], new int[count * (columns + 2)]};
            var transformed = stream.transform() != 0 ? new int[3][columns] : null;
            var interval = stream.restartInterval();
            for (var row = 0; row < rows; row++) {
                if (interval > 0 && row > 0 && row % interval == 0) {
                    restart(row / interval, lines);
                }
                var previous = lines[row & 1];
                var current = lines[(row & 1) ^ 1];
                if (transformed != null) {
                    transform(row, transformed);
                }
                for (var k = 0; k < count; k++) {
                    var plane = transformed != null ? transformed[k] : stream.planes()[components[k]];
                    var base = transformed != null ? 0 : row * columns;
                    for (var x = 0; x < columns; x++) {
                        current[(x + 1) * count + k] = plane[base + x];
                    }
                }
                encodeInterleaved(previous, current, columns, count);
            }
        }

        /**
         * Applies the forward HP color transform to one row of the three components.
         *
         * @param row the row of the three planes
         * @param transformed receives the transformed rows
         */
        private void transform(int row, int[][] transformed) {
            var bits = stream.precision();
            var mask = (1 << bits) - 1;
            var bias = 1 << (bits - 1);
            var quarter = 1 << (bits - 2);
            var columns = transformed[0].length;
            var base = row * columns;
            var red = stream.planes()[0];
            var green = stream.planes()[1];
            var blue = stream.planes()[2];
            for (var x = 0; x < columns; x++) {
                var r = red[base + x];
                var g = green[base + x];
                var b = blue[base + x];
                switch (stream.transform()) {
                    case 1 -> {
                        transformed[0][x] = (r - g + bias) & mask;
                        transformed[1][x] = g;
                        transformed[2][x] = (b - g + bias) & mask;
                    }
                    case 2 -> {
                        transformed[0][x] = (r - g + bias) & mask;
                        transformed[1][x] = g;
                        transformed[2][x] = (b - ((r + g) >> 1) + bias) & mask;
                    }
                    default -> {
                        var v2 = (b - g + bias) & mask;
                        var v3 = (r - g + bias) & mask;
                        transformed[0][x] = (g + ((v2 + v3) >> 2) - quarter) & mask;
                        transformed[1][x] = v2;
                        transformed[2][x] = v3;
                    }
                }
            }
        }

        /**
         * Encodes one line of a component, replacing each sample by its reconstruction.
         *
         * @param previous the previous reconstructed line, samples at 1 to {@code columns}
         * @param current the line to encode, samples at 1 to {@code columns}
         * @param columns the number of samples
         */
        private void encodeLine(int[] previous, int[] current, int columns) {
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
                    current[index] = regular(qs, current[index], JpegLsModel.predict(ra, rb, rc));
                    index++;
                } else {
                    index += run(previous, current, index, columns, 1);
                    rb = previous[index - 1];
                    rd = previous[index];
                }
            }
        }

        /**
         * Encodes one line of a sample-interleaved scan, replacing each sample by its reconstruction.
         *
         * @param previous the previous reconstructed line, sample {@code x} of component {@code k} at
         * {@code x * count + k}
         * @param current the line to encode, in the same layout
         * @param columns the number of samples per component
         * @param count the number of components
         */
        private void encodeInterleaved(int[] previous, int[] current, int columns, int count) {
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
                    var at = index * count + k;
                    current[at] = regular(contexts[k], current[at], JpegLsModel.predict(ra, rb, rc));
                }
                index++;
            }
        }

        /**
         * Encodes a sample in regular mode (T.87 A.4 to A.6).
         *
         * @param qs the signed context
         * @param sample the source sample
         * @param prediction the median edge detector prediction
         * @return the reconstructed sample
         */
        private int regular(int qs, int sample, int prediction) {
            var sign = qs >> 31;
            var q = (qs ^ sign) - sign;
            var k = model.golomb(q);
            var corrected = model.correct(prediction, q, sign);
            var error = model.errorValue(((sample - corrected) ^ sign) - sign);
            var mapped = model.errorCorrection(q, k) ^ error;
            mappedError(k, mapped >= 0 ? 2 * mapped : -2 * mapped - 1, model.limit);
            model.update(q, error);
            return model.reconstruct(corrected, (error ^ sign) - sign);
        }

        /**
         * Writes a mapped error value as a limited-length Golomb code (T.87 A.5.3).
         *
         * @param k the Golomb parameter
         * @param mapped the mapped error value
         * @param limit the code length limit
         */
        private void mappedError(int k, int mapped, int limit) {
            var high = mapped >>> k;
            var escape = limit - model.qbpp - 1;
            if (high < escape) {
                writer.zeros(high);
                writer.bits(1, 1);
                writer.bits(mapped & ((1L << k) - 1), k);
                return;
            }
            writer.zeros(escape);
            writer.bits(1, 1);
            writer.bits((mapped - 1) & ((1L << model.qbpp) - 1), model.qbpp);
        }

        /**
         * Encodes a run and the sample that interrupts it (T.87 A.7).
         *
         * @param previous the previous reconstructed line
         * @param current the line being encoded
         * @param start the index of the first sample of the run
         * @param columns the number of samples per line
         * @param count the number of components interleaved per sample
         * @return the number of samples encoded
         */
        private int run(int[] previous, int[] current, int start, int columns, int count) {
            var remaining = columns - start + 1;
            var left = (start - 1) * count;
            var length = 0;
            while (length < remaining && near(current, (start + length) * count, left, count)) {
                for (var k = 0; k < count; k++) {
                    current[(start + length) * count + k] = current[left + k];
                }
                length++;
            }
            var endOfLine = length == remaining;
            var pending = length;
            while (pending >= 1 << JpegLsModel.J[model.runIndex]) {
                writer.bits(1, 1);
                pending -= 1 << JpegLsModel.J[model.runIndex];
                model.incrementRun();
            }
            if (endOfLine) {
                if (pending != 0) {
                    writer.bits(1, 1);
                }
                return length;
            }
            writer.bits(pending, JpegLsModel.J[model.runIndex] + 1);
            var stop = start + length;
            if (count == 1) {
                current[stop] = interruption(current[stop], current[start - 1], previous[stop]);
            } else {
                for (var k = 0; k < count; k++) {
                    var ra = current[left + k];
                    var rb = previous[stop * count + k];
                    var sign = ((rb - ra) >> 31) | 1;
                    var error = model.errorValue(sign * (current[stop * count + k] - rb));
                    runError(0, error);
                    current[stop * count + k] = model.reconstruct(rb, error * sign);
                }
            }
            model.decrementRun();
            return length + 1;
        }

        /**
         * {@return whether every component of a sample is within {@code NEAR} of the run value}
         *
         * @param line the line holding the samples
         * @param at the index of the sample's first component
         * @param left the index of the run value's first component
         * @param count the number of components
         */
        private boolean near(int[] line, int at, int left, int count) {
            for (var k = 0; k < count; k++) {
                if (!model.near(line[at + k], line[left + k])) {
                    return false;
                }
            }
            return true;
        }

        /**
         * Encodes the sample interrupting a run of one component (T.87 A.7.2).
         *
         * @param sample the source sample
         * @param ra the run value
         * @param rb the reconstructed sample above
         * @return the reconstructed sample
         */
        private int interruption(int sample, int ra, int rb) {
            if (model.near(ra, rb)) {
                var error = model.errorValue(sample - ra);
                runError(1, error);
                return model.reconstruct(ra, error);
            }
            var sign = ((rb - ra) >> 31) | 1;
            var error = model.errorValue((sample - rb) * sign);
            runError(0, error);
            return model.reconstruct(rb, error * sign);
        }

        /**
         * Writes the error value of a run-interruption sample (T.87 code segments A.20 to A.23).
         *
         * @param type the run-interruption type
         * @param error the error value
         */
        private void runError(int type, int error) {
            var k = model.runGolomb(type);
            var map = model.runMap(type, error, k);
            var mapped = 2 * Math.abs(error) - type - (map ? 1 : 0);
            mappedError(k, mapped, model.limit - JpegLsModel.J[model.runIndex] - 1);
            model.runUpdate(type, error, mapped);
        }
    }
}
