package com.github.auties00.smalt.internal.codec;

import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.audio.Speaker;

import java.util.ArrayList;
import java.util.List;

/**
 * Reads the core frame header of DTS Coherent Acoustics (ETSI TS 102 114 clause 5.3) in its 16-bit big-endian form,
 * which starts with the sync word {@code 0x7FFE8001}, and converts frames from the 14-bit form, which stores 14
 * bitstream bits in each 16-bit word.
 *
 * <p>Arguments are not checked; values outside the documented ranges give unspecified results.
 */
public final class DtsConfigs {
    /**
     * The size of the header fields read, sync word included, in bytes.
     */
    public static final int HEADER_SIZE = 11;

    /**
     * The smallest valid core frame, in bytes; {@code FSIZE} values below 95 are invalid.
     */
    public static final int MIN_FRAME_SIZE = 96;

    /**
     * The sampling rates of the {@code SFREQ} codes, 0 for an invalid code.
     */
    private static final int[] RATES = {0, 8000, 16000, 32000, 0, 0, 11025, 22050, 44100, 0, 0, 12000, 24000, 48000, 0,
            0};

    /**
     * The speakers of the channel arrangements of {@code AMODE} 0 to 9, in WAVE order; the arrangements above 9 are
     * described by their channel count alone.
     */
    private static final Speaker[][] ARRANGEMENTS = {
            {Speaker.FRONT_CENTER},
            {Speaker.FRONT_LEFT, Speaker.FRONT_RIGHT},
            {Speaker.FRONT_LEFT, Speaker.FRONT_RIGHT},
            {Speaker.FRONT_LEFT, Speaker.FRONT_RIGHT},
            {Speaker.FRONT_LEFT, Speaker.FRONT_RIGHT},
            {Speaker.FRONT_LEFT, Speaker.FRONT_RIGHT, Speaker.FRONT_CENTER},
            {Speaker.FRONT_LEFT, Speaker.FRONT_RIGHT, Speaker.BACK_CENTER},
            {Speaker.FRONT_LEFT, Speaker.FRONT_RIGHT, Speaker.FRONT_CENTER, Speaker.BACK_CENTER},
            {Speaker.FRONT_LEFT, Speaker.FRONT_RIGHT, Speaker.SIDE_LEFT, Speaker.SIDE_RIGHT},
            {Speaker.FRONT_LEFT, Speaker.FRONT_RIGHT, Speaker.FRONT_CENTER, Speaker.SIDE_LEFT, Speaker.SIDE_RIGHT}
    };

    /**
     * The channel counts of {@code AMODE} 10 to 15.
     */
    private static final int[] WIDE_CHANNELS = {6, 6, 6, 7, 8, 8};

    /**
     * Prevents instantiation.
     */
    private DtsConfigs() {
        throw new AssertionError();
    }

    /**
     * Returns whether a 16-bit big-endian core sync word starts at an offset.
     *
     * @param data the bytes
     * @param offset the index of the first byte, with at least 4 bytes after it
     * @return {@code true} for {@code 0x7FFE8001}
     */
    public static boolean isSync(byte[] data, int offset) {
        return data[offset] == 0x7F && data[offset + 1] == (byte) 0xFE && data[offset + 2] == (byte) 0x80
                && data[offset + 3] == 0x01;
    }

    /**
     * Returns whether a 14-bit big-endian core sync word starts at an offset: 14 bitstream bits in each 16-bit
     * word, {@code 0x1FFFE800 0x07Fx}.
     *
     * @param data the bytes
     * @param offset the index of the first byte, with at least 6 bytes after it
     * @return {@code true} for the 14-bit sync word
     */
    public static boolean isPackedSync(byte[] data, int offset) {
        return data[offset] == 0x1F && data[offset + 1] == (byte) 0xFF && data[offset + 2] == (byte) 0xE8
                && data[offset + 3] == 0x00 && data[offset + 4] == 0x07 && (data[offset + 5] & 0xF0) == 0xF0;
    }

    /**
     * Returns the number of stored bytes a frame takes in the 14-bit form.
     *
     * @param bytes the length of the frame in the 16-bit form
     * @return the stored length, whole 16-bit words of 14 bits each
     */
    public static int packedSize(int bytes) {
        return (bytes * 8 + 13) / 14 * 2;
    }

    /**
     * Converts big-endian 16-bit words of 14 bitstream bits each into the 16-bit form.
     *
     * @param stored the stored words
     * @param from the index of the first word
     * @param target receives the bytes
     * @param offset the index of the first byte written
     * @param length the number of bytes to produce; the words holding them must follow {@code from}
     */
    public static void unpack(byte[] stored, int from, byte[] target, int offset, int length) {
        var bits = 0L;
        var count = 0;
        var word = from;
        for (var index = 0; index < length; index++) {
            while (count < 8) {
                bits = bits << 14 | ((stored[word] & 0x3F) << 8 | stored[word + 1] & 0xFF);
                count += 14;
                word += 2;
            }
            count -= 8;
            target[offset + index] = (byte) (bits >>> count);
        }
    }

    /**
     * Returns the size of the core frame whose header starts at an offset ({@code FSIZE + 1}).
     *
     * @param data the bytes
     * @param offset the index of the sync word, with at least {@value #HEADER_SIZE} bytes after it
     * @return the size in bytes, from 1 to 16384; below {@value #MIN_FRAME_SIZE} for an invalid header
     */
    public static int frameSize(byte[] data, int offset) {
        return (int) bits(data, offset, 46, 14) + 1;
    }

    /**
     * Returns the number of samples per channel of the core frame whose header starts at an offset
     * ({@code (NBLKS + 1) * 32}).
     *
     * @param data the bytes
     * @param offset the index of the sync word, with at least {@value #HEADER_SIZE} bytes after it
     * @return the sample count
     */
    public static int samples(byte[] data, int offset) {
        return ((int) bits(data, offset, 39, 7) + 1) * 32;
    }

    /**
     * Returns the sampling rate of the core frame whose header starts at an offset.
     *
     * @param data the bytes
     * @param offset the index of the sync word, with at least {@value #HEADER_SIZE} bytes after it
     * @return the rate in hertz, or 0 for an invalid {@code SFREQ} code
     */
    public static int sampleRate(byte[] data, int offset) {
        return RATES[(int) bits(data, offset, 66, 4)];
    }

    /**
     * Returns the channel layout of the core frame whose header starts at an offset: the speakers of its
     * {@code AMODE} arrangement followed by the low frequency effects channel when {@code LFF} signals one.
     *
     * @param data the bytes
     * @param offset the index of the sync word, with at least {@value #HEADER_SIZE} bytes after it
     * @return the layout, or {@code null} for a user-defined arrangement ({@code AMODE} above 15) or an invalid
     * {@code LFF}
     */
    public static ChannelLayout layout(byte[] data, int offset) {
        var arrangement = (int) bits(data, offset, 60, 6);
        var lfe = (int) bits(data, offset, 85, 2);
        if (arrangement > 15 || lfe == 3) {
            return null;
        }
        var speakers = new ArrayList<Speaker>();
        if (arrangement < ARRANGEMENTS.length) {
            speakers.addAll(List.of(ARRANGEMENTS[arrangement]));
            if (lfe != 0) {
                var position = speakers.size() > 2 && speakers.get(2) == Speaker.FRONT_CENTER ? 3 : 2;
                speakers.add(Math.min(position, speakers.size()), Speaker.LOW_FREQUENCY);
            }
            return new ChannelLayout(speakers);
        }
        return ChannelLayout.defaultFor(WIDE_CHANNELS[arrangement - 10] + (lfe != 0 ? 1 : 0));
    }

    /**
     * The size of the header fields of a DTS-HD extension substream that {@link #extensionSize(byte[], int)} reads,
     * sync word included, in bytes.
     */
    public static final int EXTENSION_HEADER_SIZE = 10;

    /**
     * Returns whether a DTS-HD extension substream starts at an offset.
     *
     * @param data the bytes
     * @param offset the index of the first byte, with at least 4 bytes after it
     * @return {@code true} for the sync word {@code 0x64582025}
     */
    public static boolean isExtensionSync(byte[] data, int offset) {
        return data[offset] == 0x64 && data[offset + 1] == 0x58 && data[offset + 2] == 0x20 && data[offset + 3] == 0x25;
    }

    /**
     * Returns the size of the DTS-HD extension substream that starts at an offset ({@code nuExtSSFsize + 1}).
     *
     * @param data the bytes
     * @param offset the index of the substream's sync word, with at least {@value #EXTENSION_HEADER_SIZE} bytes
     * after it
     * @return the size in bytes, sync word included
     */
    public static int extensionSize(byte[] data, int offset) {
        // after the sync word: UserDefinedBits (8), nExtSSIndex (2), bHeaderSizeType (1), then the header size and
        // the substream size, 8 and 16 bits or 12 and 20 bits, each coded minus one
        var wide = bits(data, offset, 42, 1) != 0;
        return wide ? (int) bits(data, offset, 55, 20) + 1 : (int) bits(data, offset, 51, 16) + 1;
    }

    /**
     * Returns header bits counted from the first bit of the sync word.
     *
     * @param data the bytes
     * @param offset the index of the sync word
     * @param first the index of the first bit
     * @param count the number of bits, at most 32
     * @return the bits, right-aligned
     */
    private static long bits(byte[] data, int offset, int first, int count) {
        var value = 0L;
        for (var bit = first; bit < first + count; bit++) {
            value = value << 1 | (data[offset + (bit >>> 3)] >> (7 - (bit & 7)) & 1);
        }
        return value;
    }
}
