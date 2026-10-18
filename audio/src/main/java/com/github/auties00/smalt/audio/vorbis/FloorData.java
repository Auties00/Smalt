package com.github.auties00.smalt.audio.vorbis;

/**
 * Holds the floor values decoded for one channel of the current packet, between the floor decode
 * and the floor synthesis phases, plus the synthesis scratch of that channel.
 */
final class FloorData {
    /**
     * The floor 1 Y values in list order, as read from the packet.
     */
    final int[] values;

    /**
     * The floor 1 final Y values in list order, computed during synthesis.
     */
    final int[] finalValues;

    /**
     * The floor 1 step-2 flags in list order, computed during synthesis.
     */
    final boolean[] step2;

    /**
     * The floor 0 LSP coefficients.
     */
    final float[] coefficients;

    /**
     * The floor 0 amplitude, scaled to decibels.
     */
    float amplitude;

    /**
     * Allocates the value buffers.
     *
     * @param intCapacity   the number of floor 1 values to hold
     * @param floatCapacity the number of floor 0 coefficients to hold
     */
    FloorData(int intCapacity, int floatCapacity) {
        this.values = new int[intCapacity];
        this.finalValues = new int[intCapacity];
        this.step2 = new boolean[intCapacity];
        this.coefficients = new float[floatCapacity];
    }
}
