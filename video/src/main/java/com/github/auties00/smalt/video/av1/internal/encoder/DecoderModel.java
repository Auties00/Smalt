package com.github.auties00.smalt.video.av1.internal.encoder;

import com.github.auties00.smalt.video.av1.internal.FrameHeader;
import com.github.auties00.smalt.video.av1.internal.SequenceHeader;

import java.util.Arrays;

/**
 * Simulates the decoder model of the AV1 specification's Annex E in decoding schedule mode for the
 * first operating point of a stream, as the stream is coded: the smoothing buffer the decodable
 * frame groups arrive in at the level's bitrate, the frame buffers they are decoded into and the
 * presentation of the shown frames.
 *
 * <p>Times are in seconds since the first bit of the first frame group arrives. The model gives
 * the earliest removal time the next frame group may take without breaking a conformance
 * requirement it can avoid (the minimum decode time, a smoothing buffer underflow, a missing free
 * frame buffer, the decoder buffer delay of a key frame), and counts the requirements the removal
 * times it is given break.
 *
 * <p>An instance is used by one thread at a time.
 */
final class DecoderModel {
    /**
     * The number of frame buffers ({@code BUFFER_POOL_MAX_SIZE}).
     */
    private static final int POOL = 10;

    /**
     * The number of reference slots.
     */
    private static final int SLOTS = 8;

    /**
     * The decoding clock tick ({@code DecCT}), in seconds.
     */
    private final double decodingTick;

    /**
     * The display clock tick ({@code DispCT}), in seconds.
     */
    private final double displayTick;

    /**
     * The bitrate the smoothing buffer fills at, in bits per second; infinite without a level.
     */
    private final double bitrate;

    /**
     * The shortest time between two frame headers, in seconds; 0 without a level.
     */
    private final double headerInterval;

    /**
     * The luma samples decoded per second; infinite without a level.
     */
    private final double decodeRate;

    /**
     * The decoder buffer delay, in seconds: the removal time of the first frame group.
     */
    private final double decoderDelay;

    /**
     * The sum of the decoder and encoder buffer delays, in seconds.
     */
    private final double bufferDelay;

    /**
     * The frames the buffer pool holds when the first frame is presented
     * ({@code initial_display_delay_minus_1 + 1}).
     */
    private final int initialFrames;

    /**
     * The luma samples of an inter frame's decoding time: the largest frame size.
     */
    private final double interSamples;

    /**
     * The reference slots holding each frame buffer, per buffer ({@code DecoderRefCount}).
     */
    private final int[] decoderRefs = new int[POOL];

    /**
     * The pending presentations of each frame buffer ({@code PlayerRefCount}).
     */
    private final int[] playerRefs = new int[POOL];

    /**
     * The last presentation time of each frame buffer ({@code PresentationTimes}).
     */
    private final double[] presentations = new double[POOL];

    /**
     * The frame buffer of each reference slot, or -1 ({@code VBI}).
     */
    private final int[] slots = new int[SLOTS];

    /**
     * Whether the frame of each reference slot is a key frame.
     */
    private final boolean[] slotKeys = new boolean[SLOTS];

    /**
     * The frame groups removed so far.
     */
    private long groups;

    /**
     * The removal time of the last frame group.
     */
    private double removal;

    /**
     * The decoding time of the last frame group.
     */
    private double decodeTime;

    /**
     * The arrival time of the last bit of the last frame group.
     */
    private double lastBit;

    /**
     * The time the decoder finishes the last frame group.
     */
    private double time;

    /**
     * The presentation time of the first shown frame, or -1 until the buffer pool holds the
     * initial frames ({@code InitialPresentationDelay}).
     */
    private double initialPresentation = -1;

    /**
     * The timestamp of the first shown frame, in display ticks.
     */
    private long firstShownPts;

    /**
     * Whether a frame was shown.
     */
    private boolean shown;

    /**
     * The conformance requirements the removal times broke.
     */
    private int violations;

    /**
     * Creates the model of the first operating point of a sequence that signals the decoder model.
     *
     * @param seq the sequence header, with its timing, decoder model, level and size set
     */
    DecoderModel(SequenceHeader seq) {
        this.decodingTick = (double) seq.numUnitsInDecodingTick / seq.timeScale;
        this.displayTick = (double) seq.numUnitsInTick / seq.timeScale;
        var limits = LevelLimits.of((seq.majorLevel[0] - 2) * 4 + seq.minorLevel[0]);
        this.bitrate = limits != null ? limits.bitrate(seq.profile) : Double.POSITIVE_INFINITY;
        this.headerInterval = limits != null ? 1.0 / limits.maxHeaderRate() : 0;
        this.decodeRate = limits != null ? limits.maxDecodeRate() : Double.POSITIVE_INFINITY;
        this.decoderDelay = seq.decoderBufferDelay[0] / 90_000.0;
        this.bufferDelay = (seq.decoderBufferDelay[0] + seq.encoderBufferDelay[0]) / 90_000.0;
        this.initialFrames = seq.initialDisplayDelay[0];
        this.interSamples = (double) seq.maxWidth * seq.maxHeight;
        Arrays.fill(slots, -1);
        Arrays.fill(presentations, -1);
    }

    /**
     * {@return the decoder buffer delay in seconds, the removal time of the first frame group}
     */
    double decoderDelay() {
        return decoderDelay;
    }

    /**
     * {@return the decoding clock tick in seconds}
     */
    double decodingTick() {
        return decodingTick;
    }

    /**
     * {@return the display clock tick in seconds}
     */
    double displayTick() {
        return displayTick;
    }

    /**
     * {@return whether no frame group was removed yet}
     */
    boolean empty() {
        return groups == 0;
    }

    /**
     * {@return the arrival time of the last bit of the last frame group}
     */
    double lastBit() {
        return lastBit;
    }

    /**
     * {@return the conformance requirements the removal times broke so far}
     */
    int violations() {
        return violations;
    }

    /**
     * Returns the earliest removal time of the next frame group that keeps the requirements the
     * removal time decides: the minimum decode time after the last group, the arrival of the
     * group's last bit, a free frame buffer and, for a key frame, a decoder buffer delay's distance
     * from the arrival of the last group.
     *
     * @param bits the size of the group, in bits
     * @param key whether the group's frame is a key frame
     * @return the time, or negative infinity for the first group, whose removal time is fixed
     */
    double earliestRemoval(long bits, boolean key) {
        if (groups == 0) {
            return Double.NEGATIVE_INFINITY;
        }
        var earliest = Math.max(removal + Math.max(decodeTime, headerInterval), lastBit + bits / bitrate);
        if (key) {
            earliest = Math.max(earliest, lastBit + decoderDelay);
        }
        var freed = Double.POSITIVE_INFINITY;
        for (var i = 0; i < POOL; i++) {
            if (decoderRefs[i] == 0) {
                if (playerRefs[i] == 0) {
                    return earliest;
                }
                freed = Math.min(freed, presentations[i]);
            }
        }
        return Math.max(earliest, freed);
    }

    /**
     * Removes a frame group from the smoothing buffer and decodes its frame.
     *
     * @param at the removal time; ignored for the first group, removed after the decoder buffer
     * delay
     * @param bits the size of the group, in bits
     * @param h the frame header of the group's frame
     * @param pts the timestamp of the frame, in display ticks
     */
    void decode(double at, long bits, FrameHeader h, long pts) {
        var first = groups == 0;
        var key = h.frameType == FrameHeader.KEY;
        var r = first ? decoderDelay : at;
        if (!first && (r < removal + Math.max(decodeTime, headerInterval)
                || key && h.showFrame && r < lastBit + decoderDelay)) {
            violations++;
        }
        var firstBit = first ? 0 : Math.max(lastBit, r - bufferDelay);
        lastBit = firstBit + bits / bitrate;
        if (lastBit > r) {
            violations++;
        }
        for (var i = 0; i < POOL; i++) {
            if (playerRefs[i] > 0 && presentations[i] <= r) {
                playerRefs[i] = 0;
                if (decoderRefs[i] == 0) {
                    presentations[i] = -1;
                }
            }
        }
        time = r;
        if (h.showFrame && initialPresentation >= 0 && time > presentation(pts)) {
            violations++;
        }
        var cfbi = -1;
        for (var i = 0; i < POOL && cfbi < 0; i++) {
            if (decoderRefs[i] == 0 && playerRefs[i] == 0) {
                cfbi = i;
            }
        }
        removal = r;
        decodeTime = (h.isKeyOrIntra() ? (double) h.upscaledWidth * h.height : interSamples) / decodeRate;
        time += decodeTime;
        groups++;
        if (cfbi < 0) {
            violations++;
            return;
        }
        for (var i = 0; i < SLOTS; i++) {
            if ((h.refreshFrameFlags & (1 << i)) != 0) {
                if (slots[i] >= 0) {
                    decoderRefs[slots[i]]--;
                }
                slots[i] = cfbi;
                slotKeys[i] = key;
                decoderRefs[cfbi]++;
            }
        }
        if (initialPresentation < 0 && framesInPool() >= initialFrames) {
            initialPresentation = time;
        }
        if (h.showFrame) {
            show(cfbi, pts);
        }
    }

    /**
     * Shows the frame of a reference slot through a {@code show_existing_frame} header.
     *
     * @param slot the reference slot
     * @param pts the timestamp of the frame, in display ticks
     */
    void showExisting(int slot, long pts) {
        var idx = slots[slot];
        if (idx < 0) {
            violations++;
            return;
        }
        if (slotKeys[slot]) {
            for (var i = 0; i < SLOTS; i++) {
                if (slots[i] >= 0) {
                    decoderRefs[slots[i]]--;
                }
                slots[i] = idx;
                slotKeys[i] = true;
                decoderRefs[idx]++;
            }
        }
        show(idx, pts);
    }

    /**
     * Presents the frame of a frame buffer once the presentation schedule started.
     *
     * @param idx the frame buffer
     * @param pts the timestamp of the frame, in display ticks
     */
    private void show(int idx, long pts) {
        if (!shown) {
            shown = true;
            firstShownPts = pts;
        }
        if (initialPresentation < 0) {
            return;
        }
        var at = presentation(pts);
        if (time > at) {
            violations++;
        }
        presentations[idx] = at;
        playerRefs[idx]++;
    }

    /**
     * Returns the presentation time of a shown frame.
     *
     * @param pts the timestamp of the frame, in display ticks
     * @return the time
     */
    private double presentation(long pts) {
        return initialPresentation + (pts - (shown ? firstShownPts : pts)) * displayTick;
    }

    /**
     * {@return the frame buffers that hold a reference or a pending presentation}
     */
    private int framesInPool() {
        var n = 0;
        for (var i = 0; i < POOL; i++) {
            if (decoderRefs[i] != 0 || playerRefs[i] != 0) {
                n++;
            }
        }
        return n;
    }
}
