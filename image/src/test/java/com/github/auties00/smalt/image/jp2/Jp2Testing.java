package com.github.auties00.smalt.image.jp2;

import com.github.auties00.smalt.image.RgbaPicture;

import java.nio.charset.StandardCharsets;

/**
 * Reads the reference image formats of the JPEG 2000 conformance data (PGX, PGM and PPM) and
 * measures the peak and mean squared errors the conformance tables bound.
 */
final class Jp2Testing {
    private Jp2Testing() {
        throw new AssertionError();
    }

    /** A plane of reference samples. */
    record Plane(int width, int height, int depth, boolean signed, int[] samples) {
    }

    /** Parses a PGX file: {@code PG ML|LM [+|-] depth width height} then the samples. */
    static Plane pgx(byte[] data) {
        var end = 0;
        while (data[end] != '\n') {
            end++;
        }
        var header = new String(data, 0, end, StandardCharsets.ISO_8859_1).trim();
        var tokens = header.replace("PG", "").replace("+", " + ").replace("-", " - ").trim().split("\\s+");
        var bigEndian = tokens[0].equals("ML");
        var index = 1;
        var signed = false;
        if (tokens[index].equals("+") || tokens[index].equals("-")) {
            signed = tokens[index].equals("-");
            index++;
        }
        var depth = Integer.parseInt(tokens[index++]);
        var width = Integer.parseInt(tokens[index++]);
        var height = Integer.parseInt(tokens[index]);
        var bytes = depth <= 8 ? 1 : depth <= 16 ? 2 : 4;
        var samples = new int[width * height];
        var position = end + 1;
        for (var i = 0; i < samples.length; i++) {
            long value = 0;
            for (var k = 0; k < bytes; k++) {
                var b = data[position + (bigEndian ? k : bytes - 1 - k)] & 0xFF;
                value = (value << 8) | b;
            }
            position += bytes;
            if (signed && bytes < 4) {
                var bits = bytes * 8;
                if ((value & (1L << (bits - 1))) != 0) {
                    value -= 1L << bits;
                }
            }
            samples[i] = (int) value;
        }
        return new Plane(width, height, depth, signed, samples);
    }

    /** Parses a PGM or PPM file, binary (P5, P6) or ASCII (P2, P3), into one or three planes. */
    static Plane[] pnm(byte[] data) {
        var position = new int[]{2};
        var magic = data[1];
        var width = token(data, position);
        var height = token(data, position);
        var max = token(data, position);
        var ascii = magic == '2' || magic == '3';
        if (!ascii) {
            position[0]++;
        }
        var channels = magic == '6' || magic == '3' ? 3 : 1;
        var bytes = max > 255 ? 2 : 1;
        var planes = new Plane[channels];
        var samples = new int[channels][width * height];
        for (var i = 0; i < width * height; i++) {
            for (var c = 0; c < channels; c++) {
                if (ascii) {
                    samples[c][i] = token(data, position);
                    continue;
                }
                var value = data[position[0]++] & 0xFF;
                if (bytes == 2) {
                    value = (value << 8) | (data[position[0]++] & 0xFF);
                }
                samples[c][i] = value;
            }
        }
        var depth = 32 - Integer.numberOfLeadingZeros(max);
        for (var c = 0; c < channels; c++) {
            planes[c] = new Plane(width, height, depth, false, samples[c]);
        }
        return planes;
    }

    private static int token(byte[] data, int[] position) {
        while (Character.isWhitespace(data[position[0]]) || data[position[0]] == '#') {
            if (data[position[0]] == '#') {
                while (data[position[0]] != '\n') {
                    position[0]++;
                }
            }
            position[0]++;
        }
        var value = 0;
        while (Character.isDigit(data[position[0]])) {
            value = value * 10 + (data[position[0]++] - '0');
        }
        return value;
    }

    /**
     * Upsamples a channel to a reference grid area as {@code opj_decompress -upsample} and the
     * Kakadu references do: positions before the channel's first sample are zero, later ones
     * replicate the sample covering them.
     */
    static int[] upsample(Jp2Image image, Jp2Channel channel, int width, int height) {
        if (channel.width() == width && channel.height() == height) {
            return channel.samples();
        }
        var dx = channel.subsamplingX();
        var dy = channel.subsamplingY();
        var xoff = dx * channel.x0() - image.x0();
        var yoff = dy * channel.y0() - image.y0();
        var result = new int[width * height];
        for (var y = yoff; y < height; y++) {
            var row = Math.min((y - yoff) / dy, channel.height() - 1);
            for (var x = xoff; x < width; x++) {
                result[y * width + x] = channel.samples()[row * channel.width() + Math.min((x - xoff) / dx, channel.width() - 1)];
            }
        }
        return result;
    }

    /** Returns one channel of an RGBA picture as samples. */
    static int[] channel(RgbaPicture picture, int channel) {
        var result = new int[picture.width() * picture.height()];
        var pixels = picture.pixels();
        for (var y = 0; y < picture.height(); y++) {
            for (var x = 0; x < picture.width(); x++) {
                result[y * picture.width() + x] = pixels[y * picture.stride() + 4 * x + channel] & 0xFF;
            }
        }
        return result;
    }

    /** Returns the largest absolute difference. */
    static long peak(int[] expected, int[] actual) {
        var peak = 0L;
        for (var i = 0; i < expected.length; i++) {
            peak = Math.max(peak, Math.abs((long) expected[i] - actual[i]));
        }
        return peak;
    }

    /** Returns the mean squared error. */
    static double mse(int[] expected, int[] actual) {
        var sum = 0.0;
        for (var i = 0; i < expected.length; i++) {
            var d = (double) expected[i] - actual[i];
            sum += d * d;
        }
        return sum / expected.length;
    }
}
