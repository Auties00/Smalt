package com.github.auties00.smalt.image.raw.internal;

import java.util.Arrays;

/**
 * Codes one tile of a 16-bit image as a lossless JPEG stream (ITU-T T.81 process 14, {@code SOF3}), the form
 * DNG's compression 7 stores: predictor 1, no point transform, and Huffman tables optimized for the tile by the
 * procedure of T.81 Annex K.2.
 *
 * <p>The frame interleaves {@code components} components of one sample each per MCU, so a frame line holds
 * {@code tileWidth * samplesPerPixel} consecutive samples of a tile row: a color filter array tile of one sample
 * per photosite is coded as two components of half the tile width, which predicts each photosite from the one of
 * the same color two columns to its left. Samples of a tile beyond the image's right or bottom edge repeat the last
 * column or row. An instance holds the buffers of one thread and is reused for its tiles.
 *
 * <p>Instances are not thread-safe. Arguments are not checked; values outside the documented ranges give
 * unspecified results.
 */
final class LosslessJpegWriter {
    /**
     * The number of difference categories, {@code SSSS} 0 to 16.
     */
    private static final int CATEGORIES = 17;

    /**
     * The longest Huffman code T.81 allows.
     */
    private static final int MAX_CODE_LENGTH = 16;

    /**
     * The most bytes one sample and the end of the stream can add to the output: 32 bits of code and additional
     * bits, doubled by byte stuffing, the flushed last byte with its stuffing, and the end of image marker.
     */
    private static final int MAX_SAMPLE_BYTES = 12;

    /**
     * The differences of the tile, as category in the low 5 bits and the additional bits above them.
     */
    private int[] coded = new int[0];

    /**
     * The occurrences of each category in each component.
     */
    private final long[][] counts = new long[4][CATEGORIES];

    /**
     * The code of each category in each component.
     */
    private final int[][] codes = new int[4][CATEGORIES];

    /**
     * The code length of each category in each component.
     */
    private final int[][] lengths = new int[4][CATEGORIES];

    /**
     * The number of codes of each length (index 1 to 16) of each component's table.
     */
    private final int[][] bits = new int[4][MAX_CODE_LENGTH + 1];

    /**
     * The categories of each component's table, in code order.
     */
    private final int[][] values = new int[4][CATEGORIES];

    /**
     * The number of categories each component's table codes.
     */
    private final int[] valueCount = new int[4];

    /**
     * The output.
     */
    private byte[] out = new byte[0];

    /**
     * The number of bytes written to {@link #out}.
     */
    private int size;

    /**
     * The bits waiting to be written, in the low {@link #pending} bits.
     */
    private long buffer;

    /**
     * The number of bits in {@link #buffer}.
     */
    private int pending;

    /**
     * Codes one tile.
     *
     * @param samples the image's samples, row by row with the samples of a pixel interleaved
     * @param imageWidth the image width in pixels
     * @param imageHeight the image height in pixels
     * @param samplesPerPixel the samples of each pixel, 1 to 3
     * @param left the left column of the tile
     * @param top the top row of the tile
     * @param tileWidth the tile width in pixels, even when {@code samplesPerPixel} is 1
     * @param tileHeight the tile height in pixels
     * @return the lossless JPEG stream
     */
    byte[] encode(short[] samples, int imageWidth, int imageHeight, int samplesPerPixel, int left, int top,
                  int tileWidth, int tileHeight) {
        var components = samplesPerPixel == 1 ? 2 : samplesPerPixel;
        var line = tileWidth * samplesPerPixel;
        var count = line * tileHeight;
        if (coded.length < count) {
            coded = new int[count];
        }
        for (var c = 0; c < components; c++) {
            Arrays.fill(counts[c], 0);
        }
        predict(samples, imageWidth, imageHeight, samplesPerPixel, left, top, line, tileHeight, components);
        for (var c = 0; c < components; c++) {
            table(c);
        }
        size = 0;
        buffer = 0;
        pending = 0;
        // the headers take at most 1024 bytes; the data starts at two bytes per sample and grows on demand
        var capacity = 1024 + count * 2L;
        if (out.length < capacity) {
            out = new byte[(int) Math.min(Integer.MAX_VALUE - 16, capacity)];
        }
        header(line / components, tileHeight, components);
        for (var index = 0; index < count; index++) {
            if (out.length - size < MAX_SAMPLE_BYTES) {
                grow();
            }
            var value = coded[index];
            var category = value & 31;
            var component = index % components;
            put(codes[component][category], lengths[component][category]);
            if (category != 0 && category != 16) {
                put(value >>> 5, category);
            }
        }
        flush();
        marker(0xD9);
        return Arrays.copyOf(out, size);
    }

    /**
     * Predicts the samples of the tile and records their differences and the category counts.
     *
     * @param samples the image's samples
     * @param imageWidth the image width
     * @param imageHeight the image height
     * @param spp the samples of each pixel
     * @param left the left column of the tile
     * @param top the top row of the tile
     * @param line the samples of a tile row
     * @param tileHeight the tile height
     * @param components the components of the frame
     */
    private void predict(short[] samples, int imageWidth, int imageHeight, int spp, int left, int top, int line,
                         int tileHeight, int components) {
        var available = Math.min(line, (imageWidth - left) * spp);
        for (var row = 0; row < tileHeight; row++) {
            var y = Math.min(top + row, imageHeight - 1);
            var base = (y * imageWidth + left) * spp;
            var lastPixel = base + available - spp;
            var target = row * line;
            for (var k = 0; k < line; k++) {
                var x = k < available ? base + k : lastPixel + k % spp;
                var sample = samples[x] & 0xFFFF;
                int prediction;
                if (k >= components) {
                    prediction = sampleAt(samples, base, lastPixel, available, spp, k - components);
                } else if (row == 0) {
                    prediction = 1 << 15;
                } else {
                    var above = Math.min(top + row - 1, imageHeight - 1);
                    var aboveBase = (above * imageWidth + left) * spp;
                    prediction = sampleAt(samples, aboveBase, aboveBase + available - spp, available, spp, k);
                }
                var difference = (sample - prediction) & 0xFFFF;
                int value;
                int category;
                if (difference == 0x8000) {
                    category = 16;
                    value = 16;
                } else {
                    if (difference > 0x8000) {
                        difference -= 0x10000;
                    }
                    var magnitude = Math.abs(difference);
                    category = 32 - Integer.numberOfLeadingZeros(magnitude);
                    var extra = difference < 0 ? difference - 1 : difference;
                    value = category | ((extra & ((1 << category) - 1)) << 5);
                }
                counts[k % components][category]++;
                coded[target + k] = value;
            }
        }
    }

    /**
     * Returns a sample of a tile row, repeating the row's last pixel past the image's edge.
     *
     * @param samples the image's samples
     * @param base the index of the row's first sample in the tile
     * @param lastPixel the index of the first sample of the row's last pixel in the image
     * @param available the samples of the row inside the image
     * @param spp the samples of each pixel
     * @param k the sample's index in the tile row
     * @return the unsigned sample
     */
    private static int sampleAt(short[] samples, int base, int lastPixel, int available, int spp, int k) {
        return samples[k < available ? base + k : lastPixel + k % spp] & 0xFFFF;
    }

    /**
     * Builds the optimal Huffman table of a component from its category counts (T.81 Annex K.2) and the codes it
     * assigns (T.81 Annex C).
     *
     * @param component the component index, from 0 to 3
     */
    private void table(int component) {
        var frequency = new long[CATEGORIES + 1];
        System.arraycopy(counts[component], 0, frequency, 0, CATEGORIES);
        // the reserved symbol keeps every code from being all ones
        frequency[CATEGORIES] = 1;
        var codeSize = new int[CATEGORIES + 1];
        var others = new int[CATEGORIES + 1];
        Arrays.fill(others, -1);
        while (true) {
            var v1 = -1;
            var v2 = -1;
            for (var v = 0; v <= CATEGORIES; v++) {
                if (frequency[v] == 0) {
                    continue;
                }
                if (v1 < 0 || frequency[v] <= frequency[v1]) {
                    v2 = v1;
                    v1 = v;
                } else if (v2 < 0 || frequency[v] <= frequency[v2]) {
                    v2 = v;
                }
            }
            if (v2 < 0) {
                break;
            }
            frequency[v1] += frequency[v2];
            frequency[v2] = 0;
            codeSize[v1]++;
            while (others[v1] >= 0) {
                v1 = others[v1];
                codeSize[v1]++;
            }
            others[v1] = v2;
            codeSize[v2]++;
            while (others[v2] >= 0) {
                v2 = others[v2];
                codeSize[v2]++;
            }
        }
        var lengthCounts = new int[2 * (CATEGORIES + 1) + 1];
        for (var v = 0; v <= CATEGORIES; v++) {
            if (codeSize[v] > 0) {
                lengthCounts[codeSize[v]]++;
            }
        }
        for (var i = lengthCounts.length - 1; i > MAX_CODE_LENGTH; i--) {
            while (lengthCounts[i] > 0) {
                var j = i - 2;
                while (lengthCounts[j] == 0) {
                    j--;
                }
                lengthCounts[i] -= 2;
                lengthCounts[i - 1]++;
                lengthCounts[j + 1] += 2;
                lengthCounts[j]--;
            }
        }
        var longest = MAX_CODE_LENGTH;
        while (lengthCounts[longest] == 0) {
            longest--;
        }
        lengthCounts[longest]--;
        var tableBits = bits[component];
        System.arraycopy(lengthCounts, 0, tableBits, 0, MAX_CODE_LENGTH + 1);
        var tableValues = values[component];
        var n = 0;
        for (var length = 1; length <= 2 * (CATEGORIES + 1); length++) {
            for (var v = 0; v < CATEGORIES; v++) {
                if (codeSize[v] == length) {
                    tableValues[n++] = v;
                }
            }
        }
        valueCount[component] = n;
        Arrays.fill(lengths[component], 0);
        var code = 0;
        var index = 0;
        for (var length = 1; length <= MAX_CODE_LENGTH; length++) {
            for (var i = 0; i < tableBits[length]; i++) {
                var v = tableValues[index++];
                codes[component][v] = code++;
                lengths[component][v] = length;
            }
            code <<= 1;
        }
    }

    /**
     * Writes the start of image, the Huffman tables, the frame header and the scan header.
     *
     * @param frameWidth the samples per frame line of each component
     * @param frameHeight the frame lines
     * @param components the number of components, 2 or 3
     */
    private void header(int frameWidth, int frameHeight, int components) {
        marker(0xD8);
        var tables = 0;
        for (var c = 0; c < components; c++) {
            tables += 17 + valueCount[c];
        }
        marker(0xC4);
        u16(2 + tables);
        for (var c = 0; c < components; c++) {
            out[size++] = (byte) c;
            for (var length = 1; length <= MAX_CODE_LENGTH; length++) {
                out[size++] = (byte) bits[c][length];
            }
            for (var i = 0; i < valueCount[c]; i++) {
                out[size++] = (byte) values[c][i];
            }
        }
        marker(0xC3);
        u16(8 + 3 * components);
        out[size++] = 16;
        u16(frameHeight);
        u16(frameWidth);
        out[size++] = (byte) components;
        for (var c = 0; c < components; c++) {
            out[size++] = (byte) c;
            out[size++] = 0x11;
            out[size++] = 0;
        }
        marker(0xDA);
        u16(6 + 2 * components);
        out[size++] = (byte) components;
        for (var c = 0; c < components; c++) {
            out[size++] = (byte) c;
            out[size++] = (byte) (c << 4);
        }
        out[size++] = 1;
        out[size++] = 0;
        out[size++] = 0;
    }

    /**
     * Enlarges the output buffer by half, keeping its content; a tile of at most 4096 by 4096 pixels of three
     * samples never needs more than {@code Integer.MAX_VALUE} bytes.
     */
    private void grow() {
        out = Arrays.copyOf(out, out.length + out.length / 2 + MAX_SAMPLE_BYTES);
    }

    /**
     * Writes a marker.
     *
     * @param code the marker code after {@code FF}
     */
    private void marker(int code) {
        out[size++] = (byte) 0xFF;
        out[size++] = (byte) code;
    }

    /**
     * Writes a big-endian 16-bit value.
     *
     * @param value the value, from 0 to 65535
     */
    private void u16(int value) {
        out[size++] = (byte) (value >>> 8);
        out[size++] = (byte) value;
    }

    /**
     * Appends bits to the entropy-coded data, stuffing a zero byte after each {@code FF}.
     *
     * @param value the bits, in the low {@code length} bits
     * @param length the number of bits, from 0 to 16
     */
    private void put(int value, int length) {
        buffer = (buffer << length) | (value & ((1L << length) - 1));
        pending += length;
        while (pending >= 8) {
            pending -= 8;
            var b = (int) (buffer >>> pending) & 0xFF;
            out[size++] = (byte) b;
            if (b == 0xFF) {
                out[size++] = 0;
            }
        }
    }

    /**
     * Pads the last byte of the entropy-coded data with one bits.
     */
    private void flush() {
        if (pending > 0) {
            put((1 << (8 - pending)) - 1, 8 - pending);
        }
    }
}
