package com.github.auties00.smalt.container.ts;

import java.util.Arrays;

/**
 * Indexes the video object units, audio packs and subpicture units of a DVD-Video cell being written, to fill the
 * search information ({@code VOBU_SRI}) and synchronous information ({@code SYNCI}) of each unit's data search
 * information packet.
 *
 * <p>Units are filled in the order they were added, each once. A unit's block is final once {@link #isResolvable}
 * holds for it or the cell is {@linkplain #finish() finished}; filling it earlier (a held window that must be
 * written) addresses the newest known unit where a search target lies beyond it. Times are 90 kHz ticks in one
 * unwrapped time line; block numbers count the packs of the cell from its first. Instances are not thread-safe.
 */
final class DvdSearchIndex {
    /**
     * The offset of {@code VOBU_SRI} in the data search information, after its substream byte.
     */
    static final int SRI_OFFSET = 234;

    /**
     * The length of {@code VOBU_SRI} (42 entries of four bytes) followed by {@code SYNCI} (eight audio entries of two
     * bytes and 32 subpicture entries of four bytes).
     */
    static final int BLOCK_LENGTH = 168 + 16 + 128;

    /**
     * The spans of the search pointers, in half seconds, the closest first.
     */
    private static final int[] SPANS = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 20, 60, 120, 240};

    /**
     * The longest search span, in 90 kHz ticks: 120 seconds.
     */
    static final long HORIZON = 240 * 45_000L;

    /**
     * The farthest audio pack, in blocks, that a synchronous audio address reaches.
     */
    static final int AUDIO_REACH = 0x3FFE;

    /**
     * A search pointer to no unit.
     */
    private static final int NO_UNIT = 0x3FFFFFFF;

    /**
     * The video pointers' value for no unit with video.
     */
    private static final int NO_VIDEO = 0xBFFFFFFF;

    /**
     * The bit of a valid search pointer.
     */
    private static final int VALID = 0x80000000;

    /**
     * The bit of a span pointer with units between its target and the closer span's.
     */
    private static final int BETWEEN = 0x40000000;

    /**
     * The audio entry of a stream with no more audio for the unit.
     */
    private static final int NO_AUDIO = 0x3FFF;

    /**
     * The subpicture entry of a stream with no subpicture for the unit.
     */
    private static final int NO_SUBPICTURE = 0x3FFFFFFF;

    /**
     * The subpicture entry of a stream whose subpicture starts within the unit.
     */
    private static final int SUBPICTURE_WITHIN = 0x7FFFFFFF;

    /**
     * The absolute index of the unit in slot 0 of the unit arrays.
     */
    private int base;

    /**
     * The number of units added.
     */
    private int count;

    /**
     * The block number of each unit's navigation pack.
     */
    private long[] lbns = new long[256];

    /**
     * The block number of each unit's last pack.
     */
    private long[] lastLbns = new long[256];

    /**
     * The start presentation time of each unit ({@code vobu_s_ptm}).
     */
    private long[] starts = new long[256];

    /**
     * Whether each unit carries video.
     */
    private boolean[] videos = new boolean[256];

    /**
     * The start of the first unit, or {@link Long#MIN_VALUE}.
     */
    private long cellStart = Long.MIN_VALUE;

    /**
     * The latest end of presentation of the units.
     */
    private long cellEnd = Long.MIN_VALUE;

    /**
     * The absolute index of the next unit to fill.
     */
    private int next;

    /**
     * The absolute index of the last unit with video before {@link #next}, or {@code -1}.
     */
    private int previousVideo = -1;

    /**
     * The block number of that unit.
     */
    private long previousVideoLbn;

    /**
     * Whether the cell is finished.
     */
    private boolean finished;

    /**
     * Whether the cell has a video stream, so that a unit's next unit with video may still be added.
     */
    private boolean videoExpected;

    /**
     * The absolute index of the newest unit with video, or {@code -1}.
     */
    private int newestVideo = -1;

    /**
     * The audio packs of each audio stream number, or {@code null} for an absent stream.
     */
    private final Records[] audio = new Records[8];

    /**
     * The subpicture units of each subpicture stream number, or {@code null} for an absent stream.
     */
    private final Records[] subpictures = new Records[32];

    /**
     * Declares an audio stream.
     *
     * @param number its DVD-Video audio stream number, from 0 to 7
     */
    void declareAudio(int number) {
        audio[number] = new Records();
    }

    /**
     * Declares a subpicture stream.
     *
     * @param number its subpicture stream number, from 0 to 31
     */
    void declareSubpicture(int number) {
        subpictures[number] = new Records();
    }

    /**
     * Declares that the cell has a video stream: a unit is then not resolvable before a later unit with video is
     * added, so that its next-unit-with-video pointer is exact.
     */
    void expectVideo() {
        videoExpected = true;
    }

    /**
     * Records an audio pack in which at least one audio frame starts.
     *
     * @param number the audio stream number
     * @param lbn the block number of the pack
     * @param first the presentation time of the first frame that starts in it
     */
    void audioPack(int number, long lbn, long first) {
        audio[number].add(lbn, first, Long.MIN_VALUE);
    }

    /**
     * Records the first pack of a subpicture unit.
     *
     * @param number the subpicture stream number
     * @param lbn the block number of the pack
     * @param pts the presentation time of the unit
     * @param end the end of its display, or {@link Long#MIN_VALUE} when it lasts until the next unit
     */
    void subpictureUnit(int number, long lbn, long pts, long end) {
        subpictures[number].add(lbn, pts, end);
    }

    /**
     * Adds a closed unit.
     *
     * @param lbn the block number of its navigation pack
     * @param lastLbn the block number of its last pack
     * @param start its start presentation time
     * @param end its end presentation time
     * @param video whether it carries video
     * @return its absolute index
     */
    int addUnit(long lbn, long lastLbn, long start, long end, boolean video) {
        var slot = count - base;
        if (slot == lbns.length) {
            var capacity = lbns.length * 2;
            lbns = Arrays.copyOf(lbns, capacity);
            lastLbns = Arrays.copyOf(lastLbns, capacity);
            starts = Arrays.copyOf(starts, capacity);
            videos = Arrays.copyOf(videos, capacity);
        }
        lbns[slot] = lbn;
        lastLbns[slot] = lastLbn;
        starts[slot] = start;
        videos[slot] = video;
        if (video) {
            newestVideo = count;
        }
        if (cellStart == Long.MIN_VALUE) {
            cellStart = start;
        }
        cellEnd = Math.max(cellEnd, end);
        return count++;
    }

    /**
     * Marks the cell finished: no unit, audio pack or subpicture unit follows.
     */
    void finish() {
        finished = true;
    }

    /**
     * {@return the number of units added}
     */
    int count() {
        return count;
    }

    /**
     * {@return the absolute index of the next unit to fill}
     */
    int next() {
        return next;
    }

    /**
     * {@return the start presentation time of a unit}
     *
     * @param index the absolute index of a unit not yet discarded
     */
    long startOf(int index) {
        return starts[index - base];
    }

    /**
     * {@return the block number of a unit's navigation pack}
     *
     * @param index the absolute index of a unit not yet discarded
     */
    long lbnOf(int index) {
        return lbns[index - base];
    }

    /**
     * {@return whether a unit carries video}
     *
     * @param index the absolute index of a unit not yet discarded
     */
    boolean hasVideo(int index) {
        return videos[index - base];
    }

    /**
     * Returns whether the block of the next unit to fill no longer depends on what follows.
     *
     * @param packs the number of packs written so far
     * @return {@code true} when a later unit starts past the farthest search span, a later unit has video if the cell
     * has a video stream, and every audio stream has its synchronous pack or no pack within reach
     */
    boolean isResolvable(long packs) {
        if (next >= count) {
            return false;
        }
        if (finished) {
            return true;
        }
        var start = startOf(next);
        if (startOf(count - 1) <= start + HORIZON || videoExpected && newestVideo <= next) {
            return false;
        }
        var lbn = lbnOf(next);
        for (var records : audio) {
            if (records != null && records.presented(start) < 0 && packs - lbn <= AUDIO_REACH) {
                return false;
            }
        }
        return true;
    }

    /**
     * Writes the search and synchronous information of the next unit and advances to the one after it.
     *
     * @param out the array to write {@link #BLOCK_LENGTH} bytes to
     * @param offset the index of the first byte
     * @throws IllegalStateException if no unit is left to fill
     */
    void fill(byte[] out, int offset) {
        if (next >= count) {
            throw new IllegalStateException("no video object unit to fill");
        }
        var index = next;
        var lbn = lbnOf(index);
        var start = startOf(index);
        var newest = count - 1;
        var nextVideo = NO_VIDEO;
        for (var unit = index + 1; unit <= newest; unit++) {
            if (hasVideo(unit)) {
                nextVideo = VALID | (int) (lbnOf(unit) - lbn);
                break;
            }
        }
        put32(out, offset, nextVideo);
        var target = index + 1;
        var valid = index < newest;
        var edge = valid;
        for (var span = 0; span < SPANS.length; span++) {
            var time = start + SPANS[span] * 45_000L;
            var value = NO_UNIT;
            if (valid && finished && time >= cellEnd) {
                valid = false;
            }
            if (valid) {
                var closer = target;
                while (target < newest && startOf(target + 1) <= time) {
                    target++;
                }
                value = VALID | (target - closer > 1 ? BETWEEN : 0) | (int) (lbnOf(target) - lbn);
            } else if (edge) {
                edge = false;
                value = NO_UNIT | (target < newest ? BETWEEN : 0);
            }
            put32(out, offset + 4 * (19 - span), value);
        }
        put32(out, offset + 80, index < newest ? VALID | (int) (lbnOf(index + 1) - lbn) : NO_UNIT);
        put32(out, offset + 84, index > base ? VALID | (int) (lbn - lbnOf(index - 1)) : NO_UNIT);
        target = index - 1;
        valid = index > base;
        edge = valid;
        for (var span = 0; span < SPANS.length; span++) {
            var time = start - SPANS[span] * 45_000L;
            var value = NO_UNIT;
            if (valid && time < cellStart) {
                valid = false;
            }
            if (valid) {
                var closer = target;
                while (target > base && startOf(target) > time) {
                    target--;
                }
                value = VALID | (closer - target > 1 ? BETWEEN : 0) | (int) (lbn - lbnOf(target));
            } else if (edge) {
                edge = false;
                value = NO_UNIT | (target > 0 ? BETWEEN : 0);
            }
            put32(out, offset + 88 + 4 * span, value);
        }
        put32(out, offset + 164, previousVideo >= 0 ? VALID | (int) (lbn - previousVideoLbn) : NO_VIDEO);
        var synci = offset + 168;
        for (var number = 0; number < audio.length; number++) {
            var value = 0;
            var records = audio[number];
            if (records != null) {
                var found = records.presented(start);
                value = NO_AUDIO;
                if (found >= 0) {
                    var distance = records.lbn(found) - lbn;
                    if (distance > 0 && distance <= AUDIO_REACH) {
                        value = (int) distance;
                    } else if (distance < 0 && -distance <= AUDIO_REACH) {
                        value = 0x8000 | (int) -distance;
                    }
                }
            }
            out[synci + 2 * number] = (byte) (value >>> 8);
            out[synci + 2 * number + 1] = (byte) value;
        }
        var end = index < newest ? startOf(index + 1) : cellEnd;
        var lastLbn = lastLbns[index - base];
        for (var number = 0; number < subpictures.length; number++) {
            var value = 0;
            var records = subpictures[number];
            if (records != null) {
                value = NO_SUBPICTURE;
                var found = records.inEffect(start, end);
                if (found >= 0) {
                    var distance = records.lbn(found) - lbn;
                    if (distance > 0 && records.lbn(found) <= lastLbn) {
                        value = SUBPICTURE_WITHIN;
                    } else if (distance > 0 && distance < NO_SUBPICTURE) {
                        value = (int) distance;
                    } else if (distance < 0 && -distance < NO_SUBPICTURE) {
                        value = VALID | (int) -distance;
                    }
                }
            }
            put32(out, synci + 16 + 4 * number, value);
        }
        if (hasVideo(index)) {
            previousVideo = index;
            previousVideoLbn = lbn;
        }
        next++;
    }

    /**
     * Discards what no unit still to fill can address.
     *
     * @param bound a presentation time no unit not yet added starts before
     */
    void discard(long bound) {
        for (var unit = next; unit < count; unit++) {
            bound = Math.min(bound, startOf(unit));
        }
        var keep = next - 1;
        while (keep > base && startOf(keep) > bound - HORIZON) {
            keep--;
        }
        var drop = Math.min(keep, next - 1) - base;
        if (drop >= 1024 || drop > 0 && drop >= (count - base) / 2) {
            var kept = count - base - drop;
            System.arraycopy(lbns, drop, lbns, 0, kept);
            System.arraycopy(lastLbns, drop, lastLbns, 0, kept);
            System.arraycopy(starts, drop, starts, 0, kept);
            System.arraycopy(videos, drop, videos, 0, kept);
            base += drop;
        }
        for (var records : audio) {
            if (records != null) {
                records.dropBefore(bound);
            }
        }
        for (var records : subpictures) {
            if (records != null) {
                records.dropBefore(bound);
            }
        }
    }

    /**
     * Stores a big-endian 32-bit value.
     *
     * @param out the array
     * @param offset the index of the first byte
     * @param value the 32 bits to store
     */
    private static void put32(byte[] out, int offset, int value) {
        out[offset] = (byte) (value >>> 24);
        out[offset + 1] = (byte) (value >>> 16);
        out[offset + 2] = (byte) (value >>> 8);
        out[offset + 3] = (byte) value;
    }

    /**
     * Holds the packs of one stream, in increasing presentation time.
     */
    private static final class Records {
        private long[] lbns = new long[64];
        private long[] times = new long[64];
        private long[] ends = new long[64];
        private int head;
        private int size;

        /**
         * Appends a record.
         *
         * @param lbn the block number of the pack
         * @param time the presentation time of the first audio frame, or of the subpicture unit, that starts in it
         * @param end the end of display of the subpicture unit, or {@link Long#MIN_VALUE} when it lasts until the next
         * one (always for audio)
         */
        void add(long lbn, long time, long end) {
            if (head + size == lbns.length) {
                if (head > 0) {
                    System.arraycopy(lbns, head, lbns, 0, size);
                    System.arraycopy(times, head, times, 0, size);
                    System.arraycopy(ends, head, ends, 0, size);
                    head = 0;
                }
                if (size == lbns.length) {
                    lbns = Arrays.copyOf(lbns, size * 2);
                    times = Arrays.copyOf(times, size * 2);
                    ends = Arrays.copyOf(ends, size * 2);
                }
            }
            lbns[head + size] = lbn;
            times[head + size] = time;
            ends[head + size] = end;
            size++;
        }

        /**
         * Returns the audio pack in which the last frame starting at or before a time starts, even when that frame
         * ended before the time (in a gap), or else the next pack.
         *
         * @param time the presentation time
         * @return the index of the record, or {@code -1} when neither is known
         */
        int presented(long time) {
            var after = firstAtOrAfter(time + 1);
            var at = (after < 0 ? size : after) - 1;
            if (at >= 0) {
                return at;
            }
            return size > 0 ? 0 : -1;
        }

        /**
         * {@return the block number of a record}
         *
         * @param index the index of the record among those kept
         */
        long lbn(int index) {
            return lbns[head + index];
        }

        /**
         * {@return the index of the first record at or after a time, or {@code -1}}
         *
         * @param time the presentation time
         */
        int firstAtOrAfter(long time) {
            var low = 0;
            var high = size;
            while (low < high) {
                var middle = (low + high) >>> 1;
                if (times[head + middle] < time) {
                    low = middle + 1;
                } else {
                    high = middle;
                }
            }
            return low < size ? low : -1;
        }

        /**
         * Returns the subpicture unit displayed at a unit's start, or else the first that starts during the unit.
         *
         * @param start the start of the unit
         * @param end the end of the unit
         * @return the index of the record, or {@code -1}
         */
        int inEffect(long start, long end) {
            var first = firstAtOrAfter(start);
            var later = first < 0 ? size : first;
            if (later < size && times[head + later] == start) {
                return later;
            }
            var before = later - 1;
            if (before >= 0 && (ends[head + before] == Long.MIN_VALUE || ends[head + before] > start)) {
                return before;
            }
            return later < size && times[head + later] < end ? later : -1;
        }

        /**
         * Drops the records no time at or after a bound needs, keeping the last one before it, whose frame or unit may
         * still be presented.
         *
         * @param bound the presentation time
         */
        void dropBefore(long bound) {
            while (size > 1 && times[head + 1] < bound) {
                head++;
                size--;
            }
        }
    }
}
