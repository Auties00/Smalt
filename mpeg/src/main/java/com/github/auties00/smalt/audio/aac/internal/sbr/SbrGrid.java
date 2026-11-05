package com.github.auties00.smalt.audio.aac.internal.sbr;

import com.github.auties00.smalt.internal.bits.MsbBitWriter;

/**
 * Builds and writes the time grid of one channel's spectral band replication frame ({@code sbr_grid}): the frame
 * class, the envelope borders in time slots, the frequency resolution of each envelope, the transient pointer and
 * the noise floor borders the decoder derives from them.
 *
 * <p>A frame starts where the previous frame's last envelope ended, at most three slots into the frame, and may
 * end up to three slots past its own end. Without a transient the grid holds one envelope, or two of about equal
 * length when the spectrum changes within the frame; a transient gets a two-slot envelope starting at its slot,
 * preceded by an envelope up to it and followed by envelopes of up to eight slots. Instances are not thread-safe.
 */
final class SbrGrid {
    /**
     * The length of the envelope that starts at a transient, in time slots.
     */
    private static final int TRANSIENT_LENGTH = 2;

    /**
     * The longest relative border the syntax codes, in time slots.
     */
    private static final int MAX_RELATIVE = 8;

    /**
     * The frame class: {@link SbrFrameData#FIXFIX}, {@link SbrFrameData#FIXVAR}, {@link SbrFrameData#VARFIX} or
     * {@link SbrFrameData#VARVAR}.
     */
    int frameClass;

    /**
     * The number of envelopes, from 1 to 5.
     */
    int envelopes;

    /**
     * The envelope borders in time slots, {@link #envelopes} + 1 of them.
     */
    final int[] borders = new int[SbrFrameData.MAX_ENVELOPES + 1];

    /**
     * The frequency resolution of each envelope: 0 low, 1 high.
     */
    final int[] resolution = new int[SbrFrameData.MAX_ENVELOPES];

    /**
     * The coded transient pointer {@code bs_pointer}.
     */
    int pointer;

    /**
     * The index of the envelope starting at a transient, or -1.
     */
    int transientEnvelope;

    /**
     * The number of noise floor envelopes, 1 or 2.
     */
    int noiseEnvelopes;

    /**
     * The noise floor borders in time slots.
     */
    final int[] noiseBorders = new int[3];

    /**
     * The number of relative borders coded from the leading border.
     */
    private int leadingCount;

    /**
     * The number of relative borders coded from the trailing border.
     */
    private int trailingCount;

    /**
     * Creates an empty grid.
     */
    SbrGrid() {
    }

    /**
     * Builds the grid of a frame.
     *
     * @param timeSlots the number of time slots per frame, 16 or 15
     * @param start the first slot, where the previous frame ended, from 0 to 3
     * @param attack the slot of a transient, from {@code start} to {@code timeSlots - 1}, or -1 for none
     * @param split whether to code the frame without transient as two envelopes
     * @param lowResolution whether every envelope uses the low frequency resolution
     */
    void build(int timeSlots, int start, int attack, boolean split, boolean lowResolution) {
        transientEnvelope = -1;
        pointer = 0;
        leadingCount = 0;
        trailingCount = 0;
        if (attack < 0) {
            if (start == 0) {
                frameClass = SbrFrameData.FIXFIX;
                envelopes = split ? 2 : 1;
                var step = (timeSlots + (envelopes >> 1)) / envelopes;
                for (var l = 0; l < envelopes; l++) {
                    borders[l] = l * step;
                }
                borders[envelopes] = timeSlots;
            } else {
                frameClass = SbrFrameData.VARFIX;
                borders[0] = start;
                var length = timeSlots - start;
                if (split && length >= 4) {
                    envelopes = 2;
                    leadingCount = 1;
                    var half = Math.clamp((length / 2) & ~1, 2, MAX_RELATIVE);
                    borders[1] = start + half;
                } else {
                    envelopes = 1;
                }
                borders[envelopes] = timeSlots;
            }
        } else {
            var end = timeSlots;
            while (end < attack + TRANSIENT_LENGTH || ((end - attack) & 1) != 0) {
                end++;
            }
            var count = 0;
            var lead = attack > start ? 1 : 0;
            borders[0] = start;
            if (lead == 1) {
                borders[1] = attack;
            }
            var position = attack + TRANSIENT_LENGTH;
            borders[lead + 1] = position;
            count = lead + 1;
            var rest = end - position;
            if (rest > 0) {
                var pieces = (rest + MAX_RELATIVE - 1) / MAX_RELATIVE;
                for (var p = 0; p < pieces; p++) {
                    var remaining = pieces - p;
                    var length = (((end - position) / remaining) + 1) & ~1;
                    length = Math.clamp(length, 2, MAX_RELATIVE);
                    if (p == pieces - 1) {
                        length = end - position;
                    }
                    position += length;
                    count++;
                    borders[count] = position;
                }
            }
            envelopes = count;
            frameClass = start == 0 ? SbrFrameData.FIXVAR : SbrFrameData.VARVAR;
            trailingCount = envelopes - 1;
            // bs_pointer has ceil(log2(envelopes + 1)) bits, too few to mark the first of one or three envelopes
            var marked = envelopes + 1 - lead;
            if (marked < 1 << (32 - Integer.numberOfLeadingZeros(envelopes))) {
                pointer = marked;
                transientEnvelope = lead;
            }
        }
        for (var l = 0; l < envelopes; l++) {
            var length = borders[l + 1] - borders[l];
            resolution[l] = lowResolution || (envelopes > 2 && length < 4) ? 0 : 1;
        }
        noiseBorders[0] = borders[0];
        if (envelopes > 1) {
            var middle = switch (frameClass) {
                case SbrFrameData.FIXFIX -> envelopes >> 1;
                case SbrFrameData.VARFIX -> pointer == 0 ? 1 : pointer == 1 ? envelopes - 1 : pointer - 1;
                default -> pointer <= 1 ? envelopes - 1 : envelopes + 1 - pointer;
            };
            noiseEnvelopes = 2;
            noiseBorders[1] = borders[middle];
            noiseBorders[2] = borders[envelopes];
        } else {
            noiseEnvelopes = 1;
            noiseBorders[1] = borders[1];
        }
    }

    /**
     * {@return the slot the frame ends at, at or past the frame's end}
     */
    int end() {
        return borders[envelopes];
    }

    /**
     * Copies another grid.
     *
     * @param other the grid to copy
     */
    void copy(SbrGrid other) {
        frameClass = other.frameClass;
        envelopes = other.envelopes;
        System.arraycopy(other.borders, 0, borders, 0, borders.length);
        System.arraycopy(other.resolution, 0, resolution, 0, resolution.length);
        pointer = other.pointer;
        transientEnvelope = other.transientEnvelope;
        noiseEnvelopes = other.noiseEnvelopes;
        System.arraycopy(other.noiseBorders, 0, noiseBorders, 0, noiseBorders.length);
        leadingCount = other.leadingCount;
        trailingCount = other.trailingCount;
    }

    /**
     * Returns whether another grid has the same borders and resolutions.
     *
     * @param other the other grid
     * @return {@code true} when both channels can share the grid
     */
    boolean sameAs(SbrGrid other) {
        if (frameClass != other.frameClass || envelopes != other.envelopes || pointer != other.pointer) {
            return false;
        }
        for (var l = 0; l <= envelopes; l++) {
            if (borders[l] != other.borders[l]) {
                return false;
            }
        }
        for (var l = 0; l < envelopes; l++) {
            if (resolution[l] != other.resolution[l]) {
                return false;
            }
        }
        return true;
    }

    /**
     * Writes {@code sbr_grid}.
     *
     * @param writer the destination
     * @param timeSlots the number of time slots per frame
     */
    void write(MsbBitWriter writer, int timeSlots) {
        writer.write(frameClass, 2);
        var pointerBits = 32 - Integer.numberOfLeadingZeros(envelopes);
        switch (frameClass) {
            case SbrFrameData.FIXFIX -> {
                writer.write(Integer.numberOfTrailingZeros(envelopes), 2);
                writer.write(resolution[0], 1);
            }
            case SbrFrameData.FIXVAR -> {
                writer.write(borders[envelopes] - timeSlots, 2);
                writer.write(envelopes - 1, 2);
                for (var k = 0; k < envelopes - 1; k++) {
                    var l = envelopes - 1 - k;
                    writer.write((borders[l + 1] - borders[l] - 2) >> 1, 2);
                }
                writer.write(pointer, pointerBits);
                for (var l = 0; l < envelopes; l++) {
                    writer.write(resolution[envelopes - 1 - l], 1);
                }
            }
            case SbrFrameData.VARFIX -> {
                writer.write(borders[0], 2);
                writer.write(envelopes - 1, 2);
                for (var l = 1; l < envelopes; l++) {
                    writer.write((borders[l] - borders[l - 1] - 2) >> 1, 2);
                }
                writer.write(pointer, pointerBits);
                for (var l = 0; l < envelopes; l++) {
                    writer.write(resolution[l], 1);
                }
            }
            default -> {
                writer.write(borders[0], 2);
                writer.write(borders[envelopes] - timeSlots, 2);
                writer.write(leadingCount, 2);
                writer.write(trailingCount, 2);
                for (var l = 1; l <= leadingCount; l++) {
                    writer.write((borders[l] - borders[l - 1] - 2) >> 1, 2);
                }
                for (var k = 0; k < trailingCount; k++) {
                    var l = envelopes - 1 - k;
                    writer.write((borders[l + 1] - borders[l] - 2) >> 1, 2);
                }
                writer.write(pointer, pointerBits);
                for (var l = 0; l < envelopes; l++) {
                    writer.write(resolution[l], 1);
                }
            }
        }
    }

    /**
     * Returns the number of bits {@link #write} writes.
     *
     * @return the bits
     */
    int bits() {
        var pointerBits = 32 - Integer.numberOfLeadingZeros(envelopes);
        return switch (frameClass) {
            case SbrFrameData.FIXFIX -> 2 + 2 + 1;
            case SbrFrameData.FIXVAR, SbrFrameData.VARFIX -> 2 + 2 + 2 + 2 * (envelopes - 1) + pointerBits
                    + envelopes;
            default -> 2 + 2 + 2 + 2 + 2 + 2 * (leadingCount + trailingCount) + pointerBits + envelopes;
        };
    }
}
