package com.github.auties00.smalt.video.dv.internal;

import com.github.auties00.smalt.internal.header.DifProfile;


/**
 * Maps the compressed macroblocks of a DV frame to their place in the DIF blocks and in the picture.
 *
 * <p>A frame is a list of video segments of five compressed macroblocks, in the order in which their DIF blocks
 * follow each other; the segments are independent of each other. Entry {@code s * 5 + q} describes the {@code q}th
 * compressed macroblock of segment {@code s}: the byte offset of its DIF block in the frame, the position of its
 * macroblock in the picture and the macroblock {@linkplain #kind(int) kind}. The rules are those of SMPTE ST 314
 * clauses 4.7.2.1, 5.1.3 to 5.1.6 and SMPTE ST 370 clauses 3.7.2.1, 4.1.3 to 4.1.6; the 625/50 4:2:0 macroblocks
 * of IEC 61834 sit as the 4:2:2 macroblocks of SMPTE ST 314 do, twice as tall.
 *
 * <p>Instances are immutable and thread-safe.
 */
public final class DvLayout {
    /**
     * A 4:1:1 macroblock of four luma blocks side by side, 32 by 8 samples.
     */
    public static final int KIND_411 = 0;

    /**
     * A 4:1:1 macroblock of the rightmost column, 16 by 16 samples, whose chroma blocks each hold a 4 by 16 area.
     */
    public static final int KIND_411_RIGHT = 1;

    /**
     * A 4:2:0 macroblock, 16 by 16 samples.
     */
    public static final int KIND_420 = 2;

    /**
     * An SD 4:2:2 macroblock of two luma blocks side by side, 16 by 8 samples.
     */
    public static final int KIND_422 = 3;

    /**
     * A DVCPRO HD macroblock, 16 by 16 samples, with two chroma blocks of each component one above the other.
     */
    public static final int KIND_HD = 4;

    /**
     * A DVCPRO HD macroblock of the bottom row of the 1080-line systems, 32 by 8 samples, with four luma blocks and
     * two chroma blocks of each component side by side.
     */
    public static final int KIND_HD_BOTTOM = 5;

    /**
     * The layouts already built, two per profile: the second for the 720-line frames of DIF channels 2 and 3.
     */
    private static final DvLayout[] LAYOUTS = new DvLayout[2 * DifProfile.values().length];

    private final DifProfile profile;
    private final int channelBase;
    private final int segments;
    private final int[] offsets;
    private final int[] xs;
    private final int[] ys;
    private final byte[] kinds;

    /**
     * Constructs the layout of a profile.
     *
     * @param profile the DV format
     * @param secondHalf whether the layout is that of the 720-line frames carried in DIF channels 2 and 3
     */
    private DvLayout(DifProfile profile, boolean secondHalf) {
        this.profile = profile;
        this.channelBase = secondHalf ? 2 : 0;
        if (profile == DifProfile.DVCPRO_HD_1080I50) {
            this.segments = 4 * 27 * 11 + 27;
        } else if (profile.highDefinition()) {
            this.segments = profile.channels() * 2 * 27 * 5;
        } else {
            this.segments = profile.channels() * profile.sequences() * 27;
        }
        this.offsets = new int[segments * 5];
        this.xs = new int[segments * 5];
        this.ys = new int[segments * 5];
        this.kinds = new byte[segments * 5];
        if (profile.highDefinition()) {
            buildHd();
        } else {
            buildSd();
        }
    }

    /**
     * Returns the layout of a profile.
     *
     * <p>The video segments of the 720-line frames that DIF channels 2 and 3 carry, the second video frame of a
     * four-channel DIF frame, are shuffled by those channel numbers (SMPTE ST 370 clause 3.7.2.1), so such a frame
     * has a layout of its own.
     *
     * @param profile the DV format
     * @param secondHalf for a 720-line profile, whether the frame is carried in DIF channels 2 and 3; ignored
     * otherwise
     * @return the layout, shared
     */
    public static DvLayout of(DifProfile profile, boolean secondHalf) {
        var index = 2 * profile.ordinal() + (secondHalf && profile.progressive() ? 1 : 0);
        synchronized (LAYOUTS) {
            var layout = LAYOUTS[index];
            if (layout == null) {
                layout = new DvLayout(profile, (index & 1) == 1);
                LAYOUTS[index] = layout;
            }
            return layout;
        }
    }

    /**
     * {@return the profile}
     */
    public DifProfile profile() {
        return profile;
    }

    /**
     * {@return the number of video segments}
     */
    public int segments() {
        return segments;
    }

    /**
     * Returns the byte offset of the DIF block of a compressed macroblock in the frame.
     *
     * @param entry the entry, {@code segment * 5 + index}
     * @return the offset of the block's first byte
     */
    public int offset(int entry) {
        return offsets[entry];
    }

    /**
     * Returns the left edge of the macroblock of an entry.
     *
     * @param entry the index of the compressed macroblock, {@code segment * 5 + index}
     * @return the luma column
     */
    public int x(int entry) {
        return xs[entry];
    }

    /**
     * Returns the top edge of the macroblock of an entry.
     *
     * @param entry the index of the compressed macroblock, {@code segment * 5 + index}
     * @return the luma row
     */
    public int y(int entry) {
        return ys[entry];
    }

    /**
     * Returns the kind of the macroblock of an entry.
     *
     * @param entry the index of the compressed macroblock, {@code segment * 5 + index}
     * @return one of the {@code KIND_} constants
     */
    public int kind(int entry) {
        return kinds[entry];
    }

    /**
     * Fills the entries of the 25 and 50 Mb/s profiles.
     */
    private void buildSd() {
        var sequences = profile.sequences();
        var channels = profile.channels();
        var rows = sequences * channels;
        var m = channels;
        int[] columns = {2, 1, 3, 0, 4};
        var segment = 0;
        for (var channel = 0; channel < channels; channel++) {
            for (var sequence = 0; sequence < sequences; sequence++) {
                var g = sequence * channels + channel;
                int[] superRows = {(g + 2 * m) % rows, (g + 6 * m) % rows, (g + 8 * m) % rows, g, (g + 4 * m) % rows};
                for (var k = 0; k < 27; k++) {
                    for (var q = 0; q < 5; q++) {
                        var entry = segment * 5 + q;
                        offsets[entry] = profile.blockOffset(channel, sequence, DifProfile.videoBlock(5 * k + q));
                        placeSd(entry, superRows[q], columns[q], k);
                    }
                    segment++;
                }
            }
        }
    }

    /**
     * Places an SD macroblock.
     *
     * @param entry the index of the compressed macroblock, {@code segment * 5 + index}
     * @param i the super block row
     * @param j the super block column
     * @param k the macroblock order in the super block
     */
    private void placeSd(int entry, int i, int j, int k) {
        switch (profile.sampling()) {
            case S411 -> place411(entry, i, j, k);
            case S420 -> {
                var column = k / 3;
                var row = column % 2 == 0 ? k % 3 : 2 - k % 3;
                set(entry, 144 * j + 16 * column, 48 * i + 16 * row, KIND_420);
            }
            case S422 -> {
                var column = k / 3;
                var row = column % 2 == 0 ? k % 3 : 2 - k % 3;
                set(entry, 144 * j + 16 * column, 24 * i + 8 * row, KIND_422);
            }
        }
    }

    /**
     * Places a 4:1:1 macroblock (SMPTE ST 314 figure 26).
     *
     * @param entry the index of the compressed macroblock, {@code segment * 5 + index}
     * @param i the super block row
     * @param j the super block column
     * @param k the macroblock order in the super block
     */
    private void place411(int entry, int i, int j, int k) {
        int[] firstColumn = {0, 4, 9, 13, 18};
        var base = firstColumn[j];
        if (j == 1 || j == 3) {
            if (k < 3) {
                set(entry, 32 * base, 48 * i + 8 * (3 + k), KIND_411);
                return;
            }
            var column = (k - 3) / 6;
            var r = (k - 3) % 6;
            var row = column % 2 == 0 ? 5 - r : r;
            set(entry, 32 * (base + 1 + column), 48 * i + 8 * row, KIND_411);
            return;
        }
        if (k < 24) {
            var column = k / 6;
            var r = k % 6;
            var row = column % 2 == 0 ? r : 5 - r;
            set(entry, 32 * (base + column), 48 * i + 8 * row, KIND_411);
            return;
        }
        if (j == 4) {
            set(entry, 704, 48 * i + 16 * (k - 24), KIND_411_RIGHT);
        } else {
            set(entry, 32 * (base + 4), 48 * i + 8 * (k - 24), KIND_411);
        }
    }

    /**
     * Fills the entries of the DVCPRO HD profiles.
     */
    private void buildHd() {
        var segment = 0;
        if (profile == DifProfile.DVCPRO_HD_1080I50) {
            for (var h = 0; h < 4; h++) {
                for (var k = 0; k < 27; k++) {
                    for (var i = 0; i < 11; i++) {
                        var r = (4 * h + i) % 11;
                        var first = (5 * i + 55 * k) % 135;
                        var sequence = (5 * i + 55 * k) / 135;
                        int[] superRows = {(r + 2) % 11, (r + 6) % 11, (r + 8) % 11, r, (r + 4) % 11};
                        int[] columns = {2, 1, 3, 0, 4};
                        for (var q = 0; q < 5; q++) {
                            var entry = segment * 5 + q;
                            offsets[entry] = profile.blockOffset(h, sequence, DifProfile.videoBlock(first + q));
                            placeHd(entry, h, superRows[q], columns[q], k);
                        }
                        segment++;
                    }
                }
            }
            for (var k = 0; k < 27; k++) {
                for (var q = 0; q < 5; q++) {
                    var entry = segment * 5 + q;
                    offsets[entry] = profile.blockOffset(0, 11, DifProfile.videoBlock(5 * k + q));
                    placeHd(entry, 0, 11, q, k);
                }
                segment++;
            }
            return;
        }
        var channels = profile.progressive() ? 2 : 4;
        for (var h = 0; h < channels; h++) {
            for (var s = 0; s < 2; s++) {
                for (var k = 0; k < 27; k++) {
                    for (var t = 0; t < 5; t++) {
                        var r = (4 * (h + channelBase) + s + 2 * t) % 10;
                        var first = (5 * t + 25 * k) % 135;
                        var sequence = (5 * t + 25 * k + 675 * s) / 135;
                        int[] superRows = {(r + 2) % 10, (r + 6) % 10, (r + 8) % 10, r, (r + 4) % 10};
                        int[] columns = {2, 1, 3, 0, 4};
                        for (var q = 0; q < 5; q++) {
                            var entry = segment * 5 + q;
                            offsets[entry] = profile.blockOffset(h, sequence, DifProfile.videoBlock(first + q));
                            placeHd(entry, h, superRows[q], columns[q], k);
                        }
                        segment++;
                    }
                }
            }
        }
    }

    /**
     * Places a DVCPRO HD macroblock (SMPTE ST 370 figures 20 to 31).
     *
     * @param entry the index of the compressed macroblock, {@code segment * 5 + index}
     * @param h the divided block
     * @param i the super block row
     * @param j the super block column
     * @param k the macroblock order in the super block
     */
    private void placeHd(int entry, int h, int i, int j, int k) {
        if (profile.progressive()) {
            var linear = 27 * i + k;
            var column = 6 * (2 * j + (h & 1)) + linear % 6;
            set(entry, 16 * column, 16 * (linear / 6), KIND_HD);
            return;
        }
        if (profile == DifProfile.DVCPRO_HD_1080I50) {
            if (i == 11) {
                var edge = 27 * j + k;
                if (edge < 90) {
                    set(entry, 16 * edge, 0, KIND_HD);
                } else {
                    set(entry, 32 * (edge - 90), 1072, KIND_HD_BOTTOM);
                }
                return;
            }
            var row = 2 * (3 * i + k / 9) + (h >> 1);
            var column = 9 * (2 * j + (h & 1)) + k % 9;
            set(entry, 16 * column, 16 * (row + 1), KIND_HD);
            return;
        }
        var row = 2 * (3 * i + k / 9) + (h >> 1);
        var column = 9 * (2 * j + (h & 1)) + k % 9;
        if (column < 80) {
            set(entry, 16 * column, 16 * (row + 4), KIND_HD);
            return;
        }
        var c = column - 80;
        if (row < 32) {
            set(entry, 16 * (10 * (row / 4) + c), 16 * (row % 4), KIND_HD);
        } else if (row < 56) {
            var t = row - 32;
            set(entry, 16 * (10 * (t / 3) + c), 16 * (64 + t % 3), KIND_HD);
        } else {
            set(entry, 32 * (10 * (row - 56) + c), 1072, KIND_HD_BOTTOM);
        }
    }

    /**
     * Records the place of an entry.
     *
     * @param entry the index of the compressed macroblock, {@code segment * 5 + index}
     * @param x the left edge
     * @param y the top edge
     * @param kind the macroblock kind
     */
    private void set(int entry, int x, int y, int kind) {
        xs[entry] = x;
        ys[entry] = y;
        kinds[entry] = (byte) kind;
    }
}
