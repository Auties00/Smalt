package com.github.auties00.smalt.internal.codec;

import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.audio.Speaker;

import java.util.ArrayList;
import java.util.Comparator;

/**
 * Reads the access unit header and the major sync of Dolby TrueHD streams, as the Dolby TrueHD bitstream format
 * defines them (the {@code format_info} of the major sync is the one an ISO base media {@code dmlp} box repeats).
 *
 * <p>An access unit starts with a 4-bit check nibble, its 12-bit length in 16-bit words and a 16-bit input timing in
 * samples; a major sync follows that header when the unit is a restart point.
 *
 * <p>Arguments are not checked; values outside the documented ranges give unspecified results.
 */
public final class TrueHdConfigs {
    /**
     * The size of an access unit header and a major sync's {@code format_sync} and {@code format_info}, in bytes.
     */
    public static final int MAJOR_SYNC_SIZE = 12;

    /**
     * The speakers of the bits of the 13-bit 8-channel presentation assignment, a pair for the bits that name a
     * pair.
     */
    private static final Speaker[][] ASSIGNMENT = {
            {Speaker.FRONT_LEFT, Speaker.FRONT_RIGHT},
            {Speaker.FRONT_CENTER},
            {Speaker.LOW_FREQUENCY},
            {Speaker.SIDE_LEFT, Speaker.SIDE_RIGHT},
            {Speaker.TOP_FRONT_LEFT, Speaker.TOP_FRONT_RIGHT},
            {Speaker.FRONT_LEFT_OF_CENTER, Speaker.FRONT_RIGHT_OF_CENTER},
            {Speaker.BACK_LEFT, Speaker.BACK_RIGHT},
            {Speaker.BACK_CENTER},
            {Speaker.TOP_CENTER},
            {Speaker.UNKNOWN, Speaker.UNKNOWN},
            {Speaker.UNKNOWN, Speaker.UNKNOWN},
            {Speaker.TOP_FRONT_CENTER},
            {Speaker.UNKNOWN}
    };

    /**
     * Prevents instantiation.
     */
    private TrueHdConfigs() {
        throw new AssertionError();
    }

    /**
     * Returns the length of the access unit that starts at an offset.
     *
     * @param data the bytes
     * @param offset the index of the unit's first byte, with at least 2 bytes after it
     * @return the length in bytes, 0 for a padding word
     */
    public static int unitLength(byte[] data, int offset) {
        return ((data[offset] & 0x0F) << 8 | data[offset + 1] & 0xFF) * 2;
    }

    /**
     * Returns the input timing of the access unit that starts at an offset.
     *
     * @param data the bytes
     * @param offset the index of the unit's first byte, with at least 4 bytes after it
     * @return the timing in samples, modulo 65536
     */
    public static int inputTiming(byte[] data, int offset) {
        return (data[offset + 2] & 0xFF) << 8 | data[offset + 3] & 0xFF;
    }

    /**
     * Returns whether the access unit that starts at an offset opens with a TrueHD major sync
     * ({@code format_sync} {@code 0xF8726FBA}).
     *
     * @param data the bytes
     * @param offset the index of the unit's first byte, with at least {@value #MAJOR_SYNC_SIZE} bytes after it
     * @return {@code true} for a major sync
     */
    public static boolean isMajorSync(byte[] data, int offset) {
        return data[offset + 4] == (byte) 0xF8 && data[offset + 5] == 0x72 && data[offset + 6] == 0x6F
                && data[offset + 7] == (byte) 0xBA;
    }

    /**
     * Returns the sampling rate a major sync declares.
     *
     * @param data the bytes
     * @param offset the index of the unit's first byte
     * @return the rate in hertz, or 0 for a reserved code
     */
    public static int sampleRate(byte[] data, int offset) {
        var code = (data[offset + 8] & 0xFF) >> 4;
        return switch (code) {
            case 0, 1, 2 -> 48000 << code;
            case 8, 9, 10 -> 44100 << (code - 8);
            default -> 0;
        };
    }

    /**
     * Returns the number of samples per channel of each access unit of a stream: 40 at 44.1 and 48 kHz, doubling
     * with each doubling of the rate.
     *
     * @param data the bytes
     * @param offset the index of a major sync unit's first byte
     * @return the sample count, or 0 for a reserved rate code
     */
    public static int unitSamples(byte[] data, int offset) {
        var code = (data[offset + 8] & 0xFF) >> 4;
        return code <= 2 || code >= 8 && code <= 10 ? 40 << (code & 7) : 0;
    }

    /**
     * Returns the channel layout of the richest presentation a major sync declares: the 8-channel presentation, or
     * the 6-channel one when that is empty, or stereo, in WAVE order.
     *
     * @param data the bytes
     * @param offset the index of the unit's first byte
     * @return the layout
     */
    public static ChannelLayout layout(byte[] data, int offset) {
        var info = (data[offset + 8] & 0xFF) << 24 | (data[offset + 9] & 0xFF) << 16 | (data[offset + 10] & 0xFF) << 8
                | data[offset + 11] & 0xFF;
        var assignment = info & 0x1FFF;
        if (assignment == 0) {
            assignment = info >>> 15 & 0x1F;
        }
        if (assignment == 0) {
            return ChannelLayout.STEREO;
        }
        var speakers = new ArrayList<Speaker>();
        for (var bit = 0; bit < ASSIGNMENT.length; bit++) {
            if ((assignment >>> bit & 1) != 0) {
                speakers.addAll(java.util.List.of(ASSIGNMENT[bit]));
            }
        }
        speakers.sort(Comparator.comparingInt(speaker -> speaker == Speaker.UNKNOWN ? Integer.MAX_VALUE
                : speaker.ordinal()));
        return new ChannelLayout(speakers);
    }
}
