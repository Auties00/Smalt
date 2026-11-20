package com.github.auties00.smalt.video.h264.encoder.internal;

import java.util.Arrays;

/**
 * The per-frame records of the first pass of a two-pass encode, in display order: how each frame was
 * coded (its picture type, its quantizer and its size) and how long it lasts.
 *
 * <p>Instances are immutable and thread-safe.
 */
public final class PassRecords {
    /**
     * The picture type of an IDR picture.
     */
    public static final int INTRA = 0;

    /**
     * The picture type of a P picture.
     */
    public static final int PREDICTED = 1;

    /**
     * The picture type of a B picture.
     */
    public static final int BIDIRECTIONAL = 2;

    /**
     * The number of picture types.
     */
    static final int TYPES = 3;

    /**
     * The smallest quantizer a record may hold: the smallest frame quantizer, 0, less the largest temporal
     * layer offset.
     */
    public static final int MIN_QP = -6;

    /**
     * The largest quantizer a record may hold.
     */
    public static final int MAX_QP = 51;

    private final byte[] types;

    /**
     * The quantizer each frame was coded with, without the offset of its temporal layer.
     */
    private final int[] qps;

    /**
     * The size of each coded frame, in bits.
     */
    private final long[] bits;

    /**
     * The duration of each frame, in seconds.
     */
    private final double[] seconds;

    /**
     * Constructs records, taking ownership of the arrays.
     *
     * @param types the picture type of each frame, {@link #INTRA}, {@link #PREDICTED} or {@link #BIDIRECTIONAL}
     * @param qps the quantizer of each frame, from {@link #MIN_QP} to {@link #MAX_QP}
     * @param bits the size of each frame in bits, positive
     * @param seconds the duration of each frame in seconds, positive and finite
     * @throws IllegalArgumentException if the arrays differ in length or hold a value out of range
     */
    public PassRecords(byte[] types, int[] qps, long[] bits, double[] seconds) {
        var frames = types.length;
        if (qps.length != frames || bits.length != frames || seconds.length != frames) {
            throw new IllegalArgumentException("record arrays differ in length");
        }
        for (var i = 0; i < frames; i++) {
            if (types[i] < INTRA || types[i] > BIDIRECTIONAL) {
                throw new IllegalArgumentException("frame " + i + " has picture type " + types[i]);
            }
            if (qps[i] < MIN_QP || qps[i] > MAX_QP) {
                throw new IllegalArgumentException("frame " + i + " has quantizer " + qps[i]);
            }
            if (bits[i] <= 0) {
                throw new IllegalArgumentException("frame " + i + " has size " + bits[i]);
            }
            if (!(seconds[i] > 0) || !Double.isFinite(seconds[i])) {
                throw new IllegalArgumentException("frame " + i + " has duration " + seconds[i]);
            }
        }
        this.types = types;
        this.qps = qps;
        this.bits = bits;
        this.seconds = seconds;
    }

    /**
     * {@return the number of frames}
     */
    public int frames() {
        return types.length;
    }

    /**
     * Returns the picture type of a frame.
     *
     * @param frame the display index
     * @return {@link #INTRA}, {@link #PREDICTED} or {@link #BIDIRECTIONAL}
     */
    public int type(int frame) {
        return types[frame];
    }

    /**
     * Returns the quantizer of a frame, without the offset of its temporal layer.
     *
     * @param frame the display index
     * @return the quantizer
     */
    public int qp(int frame) {
        return qps[frame];
    }

    /**
     * Returns the coded size of a frame.
     *
     * @param frame the display index
     * @return the size in bits
     */
    public long bits(int frame) {
        return bits[frame];
    }

    /**
     * Returns the duration of a frame.
     *
     * @param frame the display index
     * @return the duration in seconds
     */
    public double seconds(int frame) {
        return seconds[frame];
    }

    /**
     * {@return the total duration of the frames, in seconds}
     */
    public double duration() {
        var sum = 0.0;
        for (var value : seconds) {
            sum += value;
        }
        return sum;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof PassRecords that && Arrays.equals(types, that.types) && Arrays.equals(qps, that.qps)
                && Arrays.equals(bits, that.bits) && Arrays.equals(seconds, that.seconds);
    }

    @Override
    public int hashCode() {
        return 31 * (31 * (31 * Arrays.hashCode(types) + Arrays.hashCode(qps)) + Arrays.hashCode(bits))
                + Arrays.hashCode(seconds);
    }

    /**
     * Collects the records of a first pass as its frames are coded, in coding order.
     *
     * <p>Instances are not thread-safe.
     */
    public static final class Recorder {
        private byte[] types = new byte[64];

        private int[] qps = new int[64];

        private long[] bits = new long[64];

        private double[] seconds = new double[64];

        /**
         * The number of frames whose display index is below the largest recorded so far, plus one.
         */
        private int count;

        /**
         * The number of records.
         */
        private int recorded;

        /**
         * Records a coded frame.
         *
         * @param frame the display index, at most {@code Integer.MAX_VALUE - 1}
         * @param type the picture type
         * @param qp the quantizer, without the offset of the frame's temporal layer
         * @param size the coded size in bits
         * @param duration the duration in seconds
         */
        public void record(long frame, int type, int qp, long size, double duration) {
            var index = Math.toIntExact(frame);
            if (index >= types.length) {
                var length = Math.max(index + 1, 2 * types.length);
                types = Arrays.copyOf(types, length);
                qps = Arrays.copyOf(qps, length);
                bits = Arrays.copyOf(bits, length);
                seconds = Arrays.copyOf(seconds, length);
            }
            types[index] = (byte) type;
            qps[index] = Math.clamp(qp, MIN_QP, MAX_QP);
            bits[index] = Math.max(1, size);
            seconds[index] = duration;
            count = Math.max(count, index + 1);
            recorded++;
        }

        /**
         * Returns the records of the frames coded so far.
         *
         * @return the records
         * @throws IllegalStateException if a frame before the last one recorded has no record
         */
        public PassRecords finish() {
            if (recorded != count) {
                throw new IllegalStateException(count - recorded + " frames have no first pass record");
            }
            return new PassRecords(Arrays.copyOf(types, count), Arrays.copyOf(qps, count), Arrays.copyOf(bits, count),
                    Arrays.copyOf(seconds, count));
        }
    }
}
