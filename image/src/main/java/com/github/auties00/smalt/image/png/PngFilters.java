package com.github.auties00.smalt.image.png;

/**
 * Filters scanlines for the PNG and APNG encoders: one filter type, or the cheapest of the five.
 *
 * <p>A filtered scanline is written with its filter type byte first, as it is stored in the image
 * data. Arguments are not checked; values outside the documented ranges give unspecified results.
 */
final class PngFilters {
    /**
     * The number of filter types of PNG filter method 0.
     */
    static final int TYPES = 5;

    /**
     * Prevents instantiation.
     */
    private PngFilters() {
        throw new AssertionError();
    }

    /**
     * {@return the filter type byte a fixed filter selection writes, or -1 for
     * {@link PngEncoderOptions.Filter#ADAPTIVE}}
     *
     * @param filter the selection
     */
    static int type(PngEncoderOptions.Filter filter) {
        return switch (filter) {
            case NONE -> 0;
            case SUB -> 1;
            case UP -> 2;
            case AVERAGE -> 3;
            case PAETH -> 4;
            case ADAPTIVE -> -1;
        };
    }

    /**
     * Applies one filter type to a scanline.
     *
     * @param kernel the filter kernel
     * @param type the filter type, from 0 to 4
     * @param row the scanline
     * @param rowOffset the index of its first byte
     * @param prior the prior scanline, unfiltered; all zero for the first scanline
     * @param priorOffset the index of its first byte
     * @param rowBytes the scanline length in bytes
     * @param distance the filter distance in bytes, from 1 to 8
     * @param target receives the type byte followed by the filtered bytes
     * @return the filter cost of the filtered bytes
     */
    static int filter(PngFilterKernel kernel, int type, byte[] row, int rowOffset, byte[] prior, int priorOffset,
                      int rowBytes, int distance, byte[] target) {
        target[0] = (byte) type;
        return switch (type) {
            case 0 -> kernel.filterNone(row, rowOffset, rowBytes, target, 1);
            case 1 -> kernel.filterSub(row, rowOffset, rowBytes, distance, target, 1);
            case 2 -> kernel.filterUp(row, rowOffset, prior, priorOffset, rowBytes, target, 1);
            case 3 -> kernel.filterAverage(row, rowOffset, prior, priorOffset, rowBytes, distance, target, 1);
            default -> kernel.filterPaeth(row, rowOffset, prior, priorOffset, rowBytes, distance, target, 1);
        };
    }

    /**
     * Filters a scanline with every filter type and returns the one of least cost, the lowest type
     * on a tie.
     *
     * @param kernel the filter kernel
     * @param row the scanline
     * @param rowOffset the index of its first byte
     * @param prior the prior scanline, unfiltered; all zero for the first scanline
     * @param priorOffset the index of its first byte
     * @param rowBytes the scanline length in bytes
     * @param distance the filter distance in bytes, from 1 to 8
     * @param candidates {@value #TYPES} arrays of at least {@code rowBytes + 1} bytes, one per type
     * @return the element of {@code candidates} holding the chosen filtered scanline
     */
    static byte[] filterAdaptive(PngFilterKernel kernel, byte[] row, int rowOffset, byte[] prior, int priorOffset,
                                 int rowBytes, int distance, byte[][] candidates) {
        var best = 0;
        var bestCost = Integer.MAX_VALUE;
        for (var type = 0; type < TYPES; type++) {
            var cost = filter(kernel, type, row, rowOffset, prior, priorOffset, rowBytes, distance, candidates[type]);
            if (cost < bestCost) {
                bestCost = cost;
                best = type;
            }
        }
        return candidates[best];
    }
}
