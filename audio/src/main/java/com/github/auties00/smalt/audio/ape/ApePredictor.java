/* Ports Monkey's Audio SDK 13.45 (Source/MACLib/NewPredictor.cpp, ScaledFirstOrderFilter.h, Interim.cpp,
   Old/NewPredictorOld.cpp). Copyright (c) 2000 - 2026 Matthew T. Ashland; BSD 3-Clause License. Modified for
   Smalt. */
package com.github.auties00.smalt.audio.ape;

import com.github.auties00.smalt.MediaException;

/**
 * The prediction stages of one Monkey's Audio channel: the neural-network filters of the compression level, then the
 * adaptive predictor of version 3.950 and later ({@code CPredictorDecompress3950toCurrent}, in 32-bit arithmetic for
 * samples of up to 24 bits and 64-bit arithmetic for 32-bit samples, and its interim variant) or of versions 3.930
 * to 3.950 ({@code CPredictorDecompress3930to3950}).
 *
 * <p>Instances are not thread-safe.
 */
abstract sealed class ApePredictor {
    static final int WINDOW = 256;

    static final int HISTORY = 8;

    /**
     * Restores the state of the start of a frame.
     */
    abstract void flush();

    /**
     * Decodes the next sample of the channel from its residual and, for the second channel of a stereo pair, the
     * cross-channel input.
     *
     * @param residual the entropy-decoded residual
     * @param cross the other channel's last sample, or 0
     * @return the sample
     */
    abstract int decompress(long residual, long cross);

    /**
     * Returns the predictor of a file version, compression level and sample size.
     *
     * @throws MediaException.Malformed if the compression level is not a Monkey's Audio level
     */
    static ApePredictor of(int version, int level, int bits, boolean interim) {
        if (version < 3950) {
            return new Old(version, level);
        }
        return bits < 32 ? new Current(version, level, bits, interim) : new Current64(version, level);
    }

    private static int[][] filterShape(int level, boolean allowInsane) {
        return switch (level) {
            case 1000 -> new int[0][];
            case 2000 -> new int[][]{{16, 11}};
            case 3000 -> new int[][]{{64, 11}};
            case 4000 -> new int[][]{{256, 13}, {32, 10}};
            case 5000 -> {
                if (!allowInsane) {
                    throw new MediaException.Malformed("APE compression level " + level);
                }
                yield new int[][]{{1024 + 256, 15}, {256, 13}, {16, 11}};
            }
            default -> throw new MediaException.Malformed("APE compression level " + level);
        };
    }

    private static int adapt(int value) {
        return value != 0 ? ((value >> 30) & 2) - 1 : 0;
    }

    private static long adapt(long value) {
        return value != 0 ? ((value >> 30) & 2) - 1 : 0;
    }

    /**
     * The predictor of version 3.950 and later for samples of up to 24 bits.
     */
    static final class Current extends ApePredictor {
        private final ApeNnFilter[] filters;

        private final boolean wide;

        private final boolean interim;

        private final int[] predictionA = new int[WINDOW + HISTORY];

        private final int[] predictionB = new int[WINDOW + HISTORY];

        private final int[] adaptA = new int[WINDOW + HISTORY];

        private final int[] adaptB = new int[WINDOW + HISTORY];

        private final int[] ma = new int[8];

        private final int[] mb = new int[8];

        private int index;

        private int position;

        private int lastValueA;

        private int stageA;

        private int stageB;

        Current(int version, int level, int bits, boolean interim) {
            var shape = filterShape(level, true);
            this.filters = new ApeNnFilter[shape.length];
            for (var i = 0; i < shape.length; i++) {
                filters[i] = new ApeNnFilter(shape[i][0], shape[i][1], version, interim);
            }
            this.wide = bits > 16;
            this.interim = interim;
            flush();
        }

        @Override
        void flush() {
            for (var filter : filters) {
                filter.flush();
            }
            java.util.Arrays.fill(ma, 0);
            java.util.Arrays.fill(mb, 0);
            java.util.Arrays.fill(predictionA, 0, HISTORY + 1, 0);
            java.util.Arrays.fill(predictionB, 0, HISTORY + 1, 0);
            java.util.Arrays.fill(adaptA, 0, HISTORY + 1, 0);
            java.util.Arrays.fill(adaptB, 0, HISTORY + 1, 0);
            ma[0] = 360;
            ma[1] = 317;
            ma[2] = -109;
            ma[3] = 98;
            stageA = 0;
            stageB = 0;
            lastValueA = 0;
            index = 0;
            position = HISTORY;
        }

        @Override
        int decompress(long residual, long cross) {
            if (index == WINDOW) {
                roll(predictionA);
                roll(predictionB);
                roll(adaptA);
                roll(adaptB);
                position = HISTORY;
                index = 0;
            }
            var a = (int) residual;
            var b = (int) cross;
            for (var i = filters.length - 1; i >= 0; i--) {
                a = filters[i].decompress(a);
            }
            var p = position;
            predictionA[p] = lastValueA;
            predictionA[p - 1] = predictionA[p] - predictionA[p - 1];
            var stageInput = b;
            predictionB[p] = stageInput - ((stageB * 31) >> 5);
            stageB = stageInput;
            predictionB[p - 1] = predictionB[p] - predictionB[p - 1];
            int current;
            if (!wide) {
                var sumA = predictionA[p] * ma[0] + predictionA[p - 1] * ma[1] + predictionA[p - 2] * ma[2]
                        + predictionA[p - 3] * ma[3];
                var sumB = predictionB[p] * mb[0] + predictionB[p - 1] * mb[1] + predictionB[p - 2] * mb[2]
                        + predictionB[p - 3] * mb[3] + predictionB[p - 4] * mb[4];
                current = a + ((sumA + (sumB >> 1)) >> 10);
            } else {
                var sumA = (long) predictionA[p] * ma[0] + (long) predictionA[p - 1] * ma[1]
                        + (long) predictionA[p - 2] * ma[2] + (long) predictionA[p - 3] * ma[3];
                var sumB = (long) predictionB[p] * mb[0] + (long) predictionB[p - 1] * mb[1]
                        + (long) predictionB[p - 2] * mb[2] + (long) predictionB[p - 3] * mb[3]
                        + (long) predictionB[p - 4] * mb[4];
                if (interim) {
                    current = a + (int) ((sumA + (sumB >> 1)) >> 10);
                } else {
                    current = a + (((int) sumA + ((int) sumB >> 1)) >> 10);
                }
            }
            adaptA[p] = adapt(predictionA[p]);
            adaptA[p - 1] = adapt(predictionA[p - 1]);
            adaptB[p] = adapt(predictionB[p]);
            adaptB[p - 1] = adapt(predictionB[p - 1]);
            var direction = (a < 0 ? 1 : 0) - (a > 0 ? 1 : 0);
            ma[0] += adaptA[p] * direction;
            ma[1] += adaptA[p - 1] * direction;
            ma[2] += adaptA[p - 2] * direction;
            ma[3] += adaptA[p - 3] * direction;
            mb[0] += adaptB[p] * direction;
            mb[1] += adaptB[p - 1] * direction;
            mb[2] += adaptB[p - 2] * direction;
            mb[3] += adaptB[p - 3] * direction;
            mb[4] += adaptB[p - 4] * direction;
            stageA = current + ((stageA * 31) >> 5);
            lastValueA = current;
            position++;
            index++;
            return stageA;
        }
    }

    /**
     * The predictor of version 3.950 and later for 32-bit samples.
     */
    static final class Current64 extends ApePredictor {
        private final ApeNnFilter64[] filters;

        private final long[] predictionA = new long[WINDOW + HISTORY];

        private final long[] predictionB = new long[WINDOW + HISTORY];

        private final long[] adaptA = new long[WINDOW + HISTORY];

        private final long[] adaptB = new long[WINDOW + HISTORY];

        private final long[] ma = new long[8];

        private final long[] mb = new long[8];

        private int index;

        private int position;

        private long lastValueA;

        private int stageA;

        private int stageB;

        Current64(int version, int level) {
            var shape = filterShape(level, true);
            this.filters = new ApeNnFilter64[shape.length];
            for (var i = 0; i < shape.length; i++) {
                filters[i] = new ApeNnFilter64(shape[i][0], shape[i][1], version);
            }
            flush();
        }

        @Override
        void flush() {
            for (var filter : filters) {
                filter.flush();
            }
            java.util.Arrays.fill(ma, 0);
            java.util.Arrays.fill(mb, 0);
            java.util.Arrays.fill(predictionA, 0, HISTORY + 1, 0);
            java.util.Arrays.fill(predictionB, 0, HISTORY + 1, 0);
            java.util.Arrays.fill(adaptA, 0, HISTORY + 1, 0);
            java.util.Arrays.fill(adaptB, 0, HISTORY + 1, 0);
            ma[0] = 360;
            ma[1] = 317;
            ma[2] = -109;
            ma[3] = 98;
            stageA = 0;
            stageB = 0;
            lastValueA = 0;
            index = 0;
            position = HISTORY;
        }

        @Override
        int decompress(long residual, long cross) {
            if (index == WINDOW) {
                roll(predictionA);
                roll(predictionB);
                roll(adaptA);
                roll(adaptB);
                position = HISTORY;
                index = 0;
            }
            var a = residual;
            var b = cross;
            for (var i = filters.length - 1; i >= 0; i--) {
                a = filters[i].decompress(a);
            }
            var p = position;
            predictionA[p] = lastValueA;
            predictionA[p - 1] = predictionA[p] - predictionA[p - 1];
            var stageInput = (int) b;
            predictionB[p] = (long) stageInput - (((long) stageB * 31) >> 5);
            stageB = stageInput;
            predictionB[p - 1] = predictionB[p] - predictionB[p - 1];
            var sumA = predictionA[p] * ma[0] + predictionA[p - 1] * ma[1] + predictionA[p - 2] * ma[2]
                    + predictionA[p - 3] * ma[3];
            var sumB = predictionB[p] * mb[0] + predictionB[p - 1] * mb[1] + predictionB[p - 2] * mb[2]
                    + predictionB[p - 3] * mb[3] + predictionB[p - 4] * mb[4];
            var current = a + ((sumA + (sumB >> 1)) >> 10);
            adaptA[p] = adapt(predictionA[p]);
            adaptA[p - 1] = adapt(predictionA[p - 1]);
            adaptB[p] = adapt(predictionB[p]);
            adaptB[p - 1] = adapt(predictionB[p - 1]);
            var direction = (a < 0 ? 1 : 0) - (a > 0 ? 1 : 0);
            ma[0] += adaptA[p] * direction;
            ma[1] += adaptA[p - 1] * direction;
            ma[2] += adaptA[p - 2] * direction;
            ma[3] += adaptA[p - 3] * direction;
            mb[0] += adaptB[p] * direction;
            mb[1] += adaptB[p - 1] * direction;
            mb[2] += adaptB[p - 2] * direction;
            mb[3] += adaptB[p - 3] * direction;
            mb[4] += adaptB[p - 4] * direction;
            stageA = (int) (current + (((long) stageA * 31) >> 5));
            lastValueA = current;
            position++;
            index++;
            return stageA;
        }
    }

    /**
     * The predictor of versions 3.930 to 3.950.
     */
    static final class Old extends ApePredictor {
        private final ApeNnFilter[] filters;

        private final int[] buffer = new int[HISTORY + WINDOW];

        private final int[] m = new int[8];

        private int position;

        private int index;

        private int lastValue;

        Old(int version, int level) {
            var shape = filterShape(level, false);
            this.filters = new ApeNnFilter[shape.length];
            for (var i = 0; i < shape.length; i++) {
                filters[i] = new ApeNnFilter(shape[i][0], shape[i][1], version, false);
            }
            flush();
        }

        @Override
        void flush() {
            for (var filter : filters) {
                filter.flush();
            }
            java.util.Arrays.fill(buffer, 0, HISTORY + 1, 0);
            java.util.Arrays.fill(m, 0);
            m[0] = 360;
            m[1] = 317;
            m[2] = -109;
            m[3] = 98;
            position = HISTORY;
            lastValue = 0;
            index = 0;
        }

        @Override
        int decompress(long residual, long cross) {
            if (index == WINDOW) {
                System.arraycopy(buffer, WINDOW, buffer, 0, HISTORY);
                position = HISTORY;
                index = 0;
            }
            var input = (int) residual;
            for (var i = filters.length - 1; i >= 0; i--) {
                input = filters[i].decompress(input);
            }
            var p = position;
            var p1 = buffer[p - 1];
            var p2 = buffer[p - 1] - buffer[p - 2];
            var p3 = buffer[p - 2] - buffer[p - 3];
            var p4 = buffer[p - 3] - buffer[p - 4];
            buffer[p] = input + ((p1 * m[0] + p2 * m[1] + p3 * m[2] + p4 * m[3]) >> 9);
            var direction = (input < 0 ? 1 : 0) - (input > 0 ? 1 : 0);
            m[0] += (((p1 >> 30) & 2) - 1) * direction;
            m[1] += (((p2 >> 30) & 2) - 1) * direction;
            m[2] += (((p3 >> 30) & 2) - 1) * direction;
            m[3] += (((p4 >> 30) & 2) - 1) * direction;
            var result = buffer[p] + ((lastValue * 31) >> 5);
            lastValue = result;
            index++;
            position++;
            return result;
        }
    }

    static void roll(int[] values) {
        System.arraycopy(values, WINDOW, values, 0, HISTORY);
    }

    static void roll(long[] values) {
        System.arraycopy(values, WINDOW, values, 0, HISTORY);
    }
}
