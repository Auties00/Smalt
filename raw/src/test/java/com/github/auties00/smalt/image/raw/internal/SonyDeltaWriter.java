package com.github.auties00.smalt.image.raw.internal;

import java.io.ByteArrayOutputStream;

/**
 * Writes the variable-length coding of {@link SonyDeltaCoding} for the tests, one difference at a time or from a whole
 * image.
 */
final class SonyDeltaWriter {
    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    private int current;
    private int count;

    /** Appends bits given as a string of zeros and ones; other characters are ignored. */
    SonyDeltaWriter bits(String text) {
        for (var index = 0; index < text.length(); index++) {
            var character = text.charAt(index);
            if (character == '0' || character == '1') {
                put(character - '0', 1);
            }
        }
        return this;
    }

    private void put(int value, int length) {
        for (var bit = length - 1; bit >= 0; bit--) {
            current = current << 1 | (value >>> bit & 1);
            if (++count == 8) {
                bytes.write(current);
                current = 0;
                count = 0;
            }
        }
    }

    /** Appends a difference. */
    SonyDeltaWriter difference(int difference) {
        var category = 32 - Integer.numberOfLeadingZeros(Math.abs(difference));
        switch (category) {
            case 0 -> put(0b011, 3);
            case 1 -> put(0b11, 2);
            case 2 -> put(0b10, 2);
            case 3 -> put(0b010, 3);
            default -> put(1, category - 1);
        }
        if (category != 0) {
            put(difference < 0 ? difference + (1 << category) - 1 : difference, category);
        }
        return this;
    }

    /** Pads the last byte with zeros and returns the stream. */
    byte[] finish() {
        while (count != 0) {
            put(0, 1);
        }
        return bytes.toByteArray();
    }

    /**
     * Encodes an image in the stream's order, with the given value in the columns to its right and the rows below it.
     *
     * @param image the samples, row by row, of 0 to 4095
     * @param width the image's width
     * @param height the image's height
     * @param streamWidth the stream's columns
     * @param hidden the value of the samples outside the image
     * @return the stream
     */
    static byte[] encode(short[] image, int width, int height, int streamWidth, int hidden) {
        var writer = new SonyDeltaWriter();
        var previous = 0;
        for (var x = streamWidth - 1; x >= 0; x--) {
            for (var phase = 0; phase < 2; phase++) {
                for (var y = phase; y < height + SonyDeltaCoding.EXTRA_ROWS; y += 2) {
                    var value = x < width && y < height ? image[y * width + x] : hidden;
                    writer.difference(value - previous);
                    previous = value;
                }
            }
        }
        return writer.finish();
    }
}
