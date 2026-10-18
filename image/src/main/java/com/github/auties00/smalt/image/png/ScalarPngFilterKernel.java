package com.github.auties00.smalt.image.png;

/**
 * Implements the PNG filters with plain scalar loops; the reference every other
 * {@link PngFilterKernel} is tested against.
 */
final class ScalarPngFilterKernel implements PngFilterKernel {
    /**
     * Constructs the kernel.
     */
    ScalarPngFilterKernel() {
    }

    /**
     * Returns the Paeth predictor of PNG 3rd edition 9.4.
     *
     * @param a the byte to the left, from 0 to 255
     * @param b the byte above, from 0 to 255
     * @param c the byte above and to the left, from 0 to 255
     * @return whichever of {@code a}, {@code b}, {@code c} is closest to {@code a + b - c}, with
     *         ties broken in that order
     */
    static int paeth(int a, int b, int c) {
        var pa = Math.abs(b - c);
        var pb = Math.abs(a - c);
        var pc = Math.abs(a + b - c - c);
        if (pa <= pb && pa <= pc) {
            return a;
        }
        return pb <= pc ? b : c;
    }

    /**
     * Returns the filter cost of one filtered byte.
     *
     * @param value the filtered byte
     * @return the absolute value of the byte read as signed, from 0 to 128
     */
    static int cost(byte value) {
        return Math.abs((int) value);
    }

    @Override
    public void unfilterSub(byte[] raw, int rawOffset, byte[] out, int outOffset, int length, int distance) {
        var head = Math.min(distance, length);
        for (var i = 0; i < head; i++) {
            out[outOffset + i] = raw[rawOffset + i];
        }
        for (var i = head; i < length; i++) {
            out[outOffset + i] = (byte) (raw[rawOffset + i] + out[outOffset + i - distance]);
        }
    }

    @Override
    public void unfilterUp(byte[] raw, int rawOffset, byte[] out, int outOffset, byte[] prior, int priorOffset,
                           int length) {
        for (var i = 0; i < length; i++) {
            out[outOffset + i] = (byte) (raw[rawOffset + i] + prior[priorOffset + i]);
        }
    }

    @Override
    public void unfilterAverage(byte[] raw, int rawOffset, byte[] out, int outOffset, byte[] prior, int priorOffset,
                                int length, int distance) {
        var head = Math.min(distance, length);
        for (var i = 0; i < head; i++) {
            out[outOffset + i] = (byte) (raw[rawOffset + i] + ((prior[priorOffset + i] & 0xFF) >>> 1));
        }
        for (var i = head; i < length; i++) {
            var a = out[outOffset + i - distance] & 0xFF;
            var b = prior[priorOffset + i] & 0xFF;
            out[outOffset + i] = (byte) (raw[rawOffset + i] + ((a + b) >>> 1));
        }
    }

    @Override
    public void unfilterPaeth(byte[] raw, int rawOffset, byte[] out, int outOffset, byte[] prior, int priorOffset,
                              int length, int distance) {
        var head = Math.min(distance, length);
        for (var i = 0; i < head; i++) {
            out[outOffset + i] = (byte) (raw[rawOffset + i] + prior[priorOffset + i]);
        }
        for (var i = head; i < length; i++) {
            var a = out[outOffset + i - distance] & 0xFF;
            var b = prior[priorOffset + i] & 0xFF;
            var c = prior[priorOffset + i - distance] & 0xFF;
            out[outOffset + i] = (byte) (raw[rawOffset + i] + paeth(a, b, c));
        }
    }

    @Override
    public void unfilterAveragePair(byte[] raw, int firstOffset, int secondOffset, byte[] first, int firstOut,
                                    byte[] second, int secondOut, byte[] prior, int priorOffset, int length,
                                    int distance) {
        unfilterAverage(raw, firstOffset, first, firstOut, prior, priorOffset, length, distance);
        unfilterAverage(raw, secondOffset, second, secondOut, first, firstOut, length, distance);
    }

    @Override
    public void unfilterPaethPair(byte[] raw, int firstOffset, int secondOffset, byte[] first, int firstOut,
                                  byte[] second, int secondOut, byte[] prior, int priorOffset, int length,
                                  int distance) {
        unfilterPaeth(raw, firstOffset, first, firstOut, prior, priorOffset, length, distance);
        unfilterPaeth(raw, secondOffset, second, secondOut, first, firstOut, length, distance);
    }

    @Override
    public void expandRgb(byte[] source, int sourceOffset, byte[] target, int targetOffset, int count) {
        for (var i = 0; i < count; i++) {
            var s = sourceOffset + i * 3;
            var t = targetOffset + i * 4;
            target[t] = source[s];
            target[t + 1] = source[s + 1];
            target[t + 2] = source[s + 2];
            target[t + 3] = (byte) 0xFF;
        }
    }

    @Override
    public int filterNone(byte[] row, int offset, int length, byte[] out, int outOffset) {
        var sum = 0;
        for (var i = 0; i < length; i++) {
            var value = row[offset + i];
            out[outOffset + i] = value;
            sum += cost(value);
        }
        return sum;
    }

    @Override
    public int filterSub(byte[] row, int offset, int length, int distance, byte[] out, int outOffset) {
        var sum = 0;
        var head = Math.min(distance, length);
        for (var i = 0; i < head; i++) {
            var value = row[offset + i];
            out[outOffset + i] = value;
            sum += cost(value);
        }
        for (var i = head; i < length; i++) {
            var value = (byte) (row[offset + i] - row[offset + i - distance]);
            out[outOffset + i] = value;
            sum += cost(value);
        }
        return sum;
    }

    @Override
    public int filterUp(byte[] row, int offset, byte[] prior, int priorOffset, int length, byte[] out, int outOffset) {
        var sum = 0;
        for (var i = 0; i < length; i++) {
            var value = (byte) (row[offset + i] - prior[priorOffset + i]);
            out[outOffset + i] = value;
            sum += cost(value);
        }
        return sum;
    }

    @Override
    public int filterAverage(byte[] row, int offset, byte[] prior, int priorOffset, int length, int distance,
                             byte[] out, int outOffset) {
        var sum = 0;
        var head = Math.min(distance, length);
        for (var i = 0; i < head; i++) {
            var value = (byte) (row[offset + i] - ((prior[priorOffset + i] & 0xFF) >>> 1));
            out[outOffset + i] = value;
            sum += cost(value);
        }
        for (var i = head; i < length; i++) {
            var a = row[offset + i - distance] & 0xFF;
            var b = prior[priorOffset + i] & 0xFF;
            var value = (byte) (row[offset + i] - ((a + b) >>> 1));
            out[outOffset + i] = value;
            sum += cost(value);
        }
        return sum;
    }

    @Override
    public int filterPaeth(byte[] row, int offset, byte[] prior, int priorOffset, int length, int distance,
                           byte[] out, int outOffset) {
        var sum = 0;
        var head = Math.min(distance, length);
        for (var i = 0; i < head; i++) {
            var value = (byte) (row[offset + i] - prior[priorOffset + i]);
            out[outOffset + i] = value;
            sum += cost(value);
        }
        for (var i = head; i < length; i++) {
            var a = row[offset + i - distance] & 0xFF;
            var b = prior[priorOffset + i] & 0xFF;
            var c = prior[priorOffset + i - distance] & 0xFF;
            var value = (byte) (row[offset + i] - paeth(a, b, c));
            out[outOffset + i] = value;
            sum += cost(value);
        }
        return sum;
    }
}
