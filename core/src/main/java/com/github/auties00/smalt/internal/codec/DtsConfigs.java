package com.github.auties00.smalt.internal.codec;

import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.audio.Speaker;
import com.github.auties00.smalt.internal.bits.MsbBitReader;

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
     * Returns whether the core frame whose header starts at an offset signals the X96 extension ({@code EXT_AUDIO}
     * set with {@code EXT_AUDIO_ID} 2), which doubles its sampling rate.
     *
     * @param data the bytes
     * @param offset the index of the sync word, with at least {@value #HEADER_SIZE} bytes after it
     * @return whether the frame carries X96
     */
    public static boolean hasX96(byte[] data, int offset) {
        return bits(data, offset, 80, 3) == 2 && bits(data, offset, 83, 1) == 1;
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
     * The speakers of each bit of the DTS-HD loudspeaker mask (ETSI TS 102 114 Table 7-10), a bit standing for a
     * speaker or a pair.
     */
    private static final Speaker[][] MASK_SPEAKERS = {
            {Speaker.FRONT_CENTER},
            {Speaker.FRONT_LEFT, Speaker.FRONT_RIGHT},
            {Speaker.SIDE_LEFT, Speaker.SIDE_RIGHT},
            {Speaker.LOW_FREQUENCY},
            {Speaker.BACK_CENTER},
            {Speaker.TOP_FRONT_LEFT, Speaker.TOP_FRONT_RIGHT},
            {Speaker.BACK_LEFT, Speaker.BACK_RIGHT},
            {Speaker.TOP_FRONT_CENTER},
            {Speaker.TOP_CENTER},
            {Speaker.FRONT_LEFT_OF_CENTER, Speaker.FRONT_RIGHT_OF_CENTER},
            {Speaker.WIDE_LEFT, Speaker.WIDE_RIGHT},
            {Speaker.SURROUND_DIRECT_LEFT, Speaker.SURROUND_DIRECT_RIGHT},
            {Speaker.LOW_FREQUENCY_2},
            {Speaker.TOP_SIDE_LEFT, Speaker.TOP_SIDE_RIGHT},
            {Speaker.TOP_BACK_CENTER},
            {Speaker.TOP_BACK_LEFT, Speaker.TOP_BACK_RIGHT}
    };

    /**
     * Returns the channel layout of a DTS-HD loudspeaker mask (ETSI TS 102 114 Table 7-10), as the {@code ddts} box
     * of MP4 and the asset descriptors carry it: the speakers of its bits in WAVE order. The side surround pair
     * ({@code 0x0800}) is reported as {@link Speaker#SIDE_LEFT} and {@link Speaker#SIDE_RIGHT} when the mask has no
     * surround pair ({@code 0x0004}).
     *
     * @param mask the 16-bit mask
     * @return the layout, or {@code null} for an empty mask
     */
    public static ChannelLayout layoutOfMask(int mask) {
        var speakers = new ArrayList<Speaker>();
        for (var bit = 0; bit < 16; bit++) {
            if ((mask >> bit & 1) == 0) {
                continue;
            }
            for (var speaker : MASK_SPEAKERS[bit]) {
                if (bit == 11 && (mask & 0x0004) == 0) {
                    speaker = speaker == Speaker.SURROUND_DIRECT_LEFT ? Speaker.SIDE_LEFT : Speaker.SIDE_RIGHT;
                }
                speakers.add(speaker);
            }
        }
        if (speakers.isEmpty()) {
            return null;
        }
        speakers.sort(null);
        return new ChannelLayout(speakers);
    }

    /**
     * The extension substream sync word.
     */
    public static final int SUBSTREAM_SYNC = 0x64582025;

    /**
     * The sample rates of the asset descriptor ({@code nuMaxSampleRate}, ETSI TS 102 114 Table 7-9).
     */
    private static final int[] ASSET_RATES = {8000, 16000, 32000, 64000, 128000, 22050, 44100, 88200, 176400, 352800,
            12000, 24000, 48000, 96000, 192000, 384000};

    /**
     * The reference clock rates of the extension substream header ({@code nuRefClockCode}).
     */
    private static final int[] CLOCK_RATES = {32000, 44100, 48000, 0};

    /**
     * What the header and first asset descriptor of a DTS-HD extension substream frame tell about the stream.
     *
     * @param clockRate the reference clock rate in hertz
     * @param frameDuration the frame duration in reference clock periods
     * @param sampleRate the largest sample rate of the asset in hertz
     * @param speakerMask the loudspeaker mask of the asset (ETSI TS 102 114 Table 7-10), or 0
     * @param channels the channel count of the asset
     * @param bits the bit resolution of the asset
     */
    public record Substream(int clockRate, int frameDuration, int sampleRate, int speakerMask, int channels,
                            int bits) {
    }

    /**
     * Returns whether an extension substream sync word starts at an offset.
     *
     * @param data the bytes
     * @param offset the index of the first byte, with at least 4 bytes after it
     * @return {@code true} for {@code 0x64582025}
     */
    public static boolean isSubstreamSync(byte[] data, int offset) {
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
     * Returns the size of the extension substream frame whose header starts at an offset, after checking the
     * header CRC.
     *
     * @param data the bytes
     * @param offset the index of the sync word
     * @param available the number of bytes from the offset
     * @return the frame size in bytes; {@code 0} when more bytes are needed to check the header, {@code -1} when
     * the header is invalid
     */
    public static int substreamSize(byte[] data, int offset, int available) {
        if (available < 12) {
            return 0;
        }
        if (!isSubstreamSync(data, offset)) {
            return -1;
        }
        var wide = (data[offset + 5] & 0x20) != 0;
        var header = wide ? (int) bits(data, offset, 43, 12) + 1 : (int) bits(data, offset, 43, 8) + 1;
        var size = wide ? (int) bits(data, offset, 55, 20) + 1 : (int) bits(data, offset, 51, 16) + 1;
        if (header < 16 || size < header) {
            return -1;
        }
        if (available < header) {
            return 0;
        }
        var crc = 0xFFFF;
        for (var i = offset + 5; i < offset + header; i++) {
            crc ^= (data[i] & 0xFF) << 8;
            for (var bit = 0; bit < 8; bit++) {
                crc = (crc & 0x8000) != 0 ? (crc << 1 ^ 0x1021) & 0xFFFF : crc << 1 & 0xFFFF;
            }
        }
        return crc == 0 ? size : -1;
    }

    /**
     * Describes an extension substream frame from its header and its first asset descriptor.
     *
     * @param data the bytes
     * @param offset the index of the sync word
     * @param length the frame size
     * @return the description, or {@code null} when the frame carries no static fields
     */
    public static Substream describeSubstream(byte[] data, int offset, int length) {
        var reader = new MsbBitReader(data, offset, length);
        reader.skip(32 + 8);
        var index = reader.read(2);
        var wide = reader.readFlag();
        reader.skip(wide ? 12 + 20 : 8 + 16);
        if (!reader.readFlag()) {
            return null;
        }
        var clock = CLOCK_RATES[reader.read(2)];
        var duration = 512 * (reader.read(3) + 1);
        if (reader.readFlag()) {
            reader.skip(36);
        }
        var presentations = reader.read(3) + 1;
        var assets = reader.read(3) + 1;
        var active = new int[presentations];
        for (var p = 0; p < presentations; p++) {
            active[p] = reader.read(index + 1);
        }
        for (var p = 0; p < presentations; p++) {
            for (var s = 0; s <= index; s++) {
                if ((active[p] >> s & 1) != 0) {
                    reader.skip(8);
                }
            }
        }
        if (reader.readFlag()) {
            reader.skip(2);
            var maskBits = (reader.read(2) + 1) << 2;
            var configs = reader.read(2) + 1;
            reader.skip((long) maskBits * configs);
        }
        reader.skip((long) assets * (wide ? 20 : 16));
        reader.skip(9 + 3);
        if (reader.readFlag()) {
            reader.skip(4);
        }
        if (reader.readFlag()) {
            reader.skip(24);
        }
        if (reader.readFlag()) {
            reader.skip((reader.read(10) + 1) * 8L);
        }
        var bits = reader.read(5) + 1;
        var rate = ASSET_RATES[reader.read(4)];
        var channels = reader.read(8) + 1;
        var mask = 0;
        if (reader.readFlag()) {
            if (channels > 2) {
                reader.skip(1);
            }
            if (channels > 6) {
                reader.skip(1);
            }
            if (reader.readFlag()) {
                mask = reader.read((reader.read(2) + 1) << 2);
            }
        }
        if (reader.overrun() || clock == 0) {
            return null;
        }
        return new Substream(clock, duration, rate, mask, channels, bits);
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
