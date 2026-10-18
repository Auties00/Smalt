package com.github.auties00.smalt.video.av1.internal;

/**
 * Holds the entropy decoding state of one tile: its CDFs, arithmetic decoder, position and the
 * quantizer and loop filter adjustments its superblocks signaled.
 *
 * @implNote This implementation mirrors dav1d's {@code Dav1dTileState} ({@code src/internal.h}).
 */
public final class TileState {
    /**
     * The adaptive CDFs of the tile.
     */
    public final char[] cdf = new char[Cdf.SIZE];

    /**
     * The arithmetic decoder.
     */
    public final Msac msac = new Msac();

    /**
     * The first column of the tile, in 4-sample units.
     */
    public int colStart;

    /**
     * The column after the tile, in 4-sample units.
     */
    public int colEnd;

    /**
     * The first row of the tile, in 4-sample units.
     */
    public int rowStart;

    /**
     * The row after the tile, in 4-sample units.
     */
    public int rowEnd;

    /**
     * The tile column index.
     */
    public int col;

    /**
     * The tile row index.
     */
    public int row;

    /**
     * The dequantizers of the superblock-specific quantizer index, as
     * {@code [segment 8][plane 3][dc/ac 2]}.
     */
    public final int[] dqmem = new int[8 * 3 * 2];

    /**
     * The dequantizers in use: the frame's or {@link #dqmem}.
     */
    public int[] dq;

    /**
     * The quantizer index of the last superblock.
     */
    public int lastQidx;

    /**
     * The loop filter deltas of the last superblock.
     */
    public final int[] lastDeltaLf = new int[4];

    /**
     * The loop filter levels derived from {@link #lastDeltaLf}.
     */
    public final byte[] lflvlmem = new byte[LoopFilterMasks.LF_VALUES_LENGTH];

    /**
     * The loop filter levels in use: the frame's or {@link #lflvlmem}.
     */
    public byte[] lflvl;

    /**
     * The reference restoration unit of each plane for the delta coding of the next unit.
     */
    public final RestorationUnit[] lrRef = new RestorationUnit[3];

    /**
     * Creates an empty state.
     */
    public TileState() {
    }
}
