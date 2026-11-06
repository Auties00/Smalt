package com.github.auties00.smalt.video.h264.encoder.internal;

import java.util.Arrays;

/**
 * Keeps a CABAC picture within the bin count constraint of ITU-T H.264 clause 7.4.2.10 by
 * appending {@code cabac_zero_word}s (clause 7.3.2.10) to its slice NAL units, and keeps the room
 * those words need inside a slice size limit.
 *
 * <p>The constraint bounds the bins of a picture by {@code 32 / 3} times its slice data bytes plus
 * {@code RawMbBits * PicSizeInMbs / 32}. Each {@code cabac_zero_word} adds the three bytes
 * {@code 00 00 03} to a NAL unit (the word and its emulation prevention byte), so a picture that
 * codes too many bins per byte gets the fewest words that meet the constraint, the {@code k} of the
 * byte stuffing process of clause 9.3.4.6. The bytes counted are those of the slice NAL units as
 * assembled, header byte and emulation prevention bytes included, as the constraint counts them.
 *
 * <p>Without a size limit the picture's last slice carries every word. With a limit the words fill
 * the room the limit leaves in the picture's slices, from the last slice backwards, and
 * {@link #fits(long, long, long)} lets the slice writer close a slice early enough that the room
 * is there: it holds while the slices closed so far and the open one could take the words the
 * picture coded so far would need if it ended there. The words a picture needs at its end are
 * those of its last check, or fewer, so they always fit unless the picture's last slice holds a
 * single macroblock, which no check covers; that slice then carries the rest past the limit. Instances
 * are not thread-safe.
 */
final class CabacZeroWords {
    /**
     * The bytes a {@code cabac_zero_word} adds to a NAL unit: {@code 0x0000} and its emulation
     * prevention byte.
     */
    static final int WORD_BYTES = 3;

    /**
     * Whether the picture is CABAC coded.
     */
    private boolean cabac;

    /**
     * The slice size limit in bytes, or 0 for none.
     */
    private int limit;

    /**
     * The picture's {@code RawMbBits * PicSizeInMbs}.
     */
    private long rawBits;

    /**
     * The bins of the picture's slices closed so far.
     */
    private long bins;

    /**
     * The NAL unit bytes of the picture's slices closed so far, header and emulation prevention
     * bytes included.
     */
    private long bytes;

    /**
     * The bytes the limit leaves in the picture's slices closed so far.
     */
    private long room;

    /**
     * The access unit position of each of the picture's slice NAL units closed so far.
     */
    private int[] units = new int[16];

    /**
     * The number of the picture's slices closed so far.
     */
    private int count;

    /**
     * Starts a picture.
     *
     * @param cabac whether the picture is CABAC coded; a CAVLC picture never gets words
     * @param limit the slice size limit in bytes, or 0 for none
     * @param rawBits the picture's {@code RawMbBits * PicSizeInMbs}
     */
    void startPicture(boolean cabac, int limit, long rawBits) {
        this.cabac = cabac;
        this.limit = limit;
        this.rawBits = rawBits;
        bins = 0;
        bytes = 0;
        room = 0;
        count = 0;
    }

    /**
     * Returns whether the room the limit leaves in the picture's slices, the open one included,
     * could take the words the picture would need if it ended now.
     *
     * @param sliceBins the bins of the open slice
     * @param sliceBytes a lower bound of the size of the open slice's NAL unit
     * @param sizeBound an upper bound of the size of the open slice's NAL unit
     * @return whether the open slice may keep its last macroblock; always {@code true} without a
     * limit or for CAVLC
     */
    boolean fits(long sliceBins, long sliceBytes, long sizeBound) {
        if (!cabac || limit <= 0) {
            return true;
        }
        var words = words(bins + sliceBins, bytes + sliceBytes, rawBits);
        return words == 0 || (long) WORD_BYTES * words <= room + whole(limit - sizeBound);
    }

    /**
     * Records a slice of the picture, the NAL unit appended last.
     *
     * @param nal the access unit holding the slice
     * @param sliceBins the slice's bins
     */
    void closed(NalWriter nal, long sliceBins) {
        if (count == units.length) {
            units = Arrays.copyOf(units, 2 * count);
        }
        var unit = nal.unitCount() - 1;
        units[count++] = unit;
        var length = nal.unitLength(unit);
        bins += sliceBins;
        bytes += length;
        if (limit > 0) {
            room += whole(limit - length);
        }
    }

    /**
     * Appends the words the picture needs to its slices, after its last slice is recorded.
     *
     * @param nal the access unit holding the slices
     * @return the number of words appended
     */
    int finishPicture(NalWriter nal) {
        if (!cabac || count == 0) {
            return 0;
        }
        var total = words(bins, bytes, rawBits);
        var left = total;
        for (var i = count - 1; i >= 0 && left > 0; i--) {
            var unit = units[i];
            var words = limit <= 0 ? left : Math.min(left, Math.max(0, limit - nal.unitLength(unit)) / WORD_BYTES);
            if (words > 0) {
                nal.appendZeroWords(unit, words);
                left -= words;
            }
        }
        if (left > 0) {
            nal.appendZeroWords(units[count - 1], left);
        }
        return total;
    }

    /**
     * {@return the bins of the picture's slices recorded so far}
     */
    long bins() {
        return bins;
    }

    /**
     * Returns the bytes of the whole words that fit in the room a slice leaves.
     *
     * @param room the bytes the limit leaves in a slice, negative when the slice exceeds it
     * @return the room rounded down to whole words, at least 0
     */
    private static long whole(long room) {
        return room <= 0 ? 0 : room / WORD_BYTES * WORD_BYTES;
    }

    /**
     * Returns the fewest {@code cabac_zero_word}s that make a picture meet the constraint, in exact
     * arithmetic: {@code Ceil((Ceil(3 * (32 * bins - rawBits) / 1024) - bytes) / 3)}, or 0 when it
     * already does.
     *
     * @param bins the picture's bins
     * @param bytes the bytes of the picture's slice NAL units
     * @param rawBits the picture's {@code RawMbBits * PicSizeInMbs}
     * @return the number of words, 0 when the picture meets the constraint
     */
    static int words(long bins, long bytes, long rawBits) {
        var scaled = 96 * bins - 3 * rawBits;
        if (scaled <= 1024 * bytes) {
            return 0;
        }
        return (int) Math.ceilDiv(Math.ceilDiv(scaled, 1024) - bytes, WORD_BYTES);
    }
}
