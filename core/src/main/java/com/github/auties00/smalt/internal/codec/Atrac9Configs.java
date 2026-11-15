package com.github.auties00.smalt.internal.codec;

import com.github.auties00.smalt.audio.ChannelLayout;

/**
 * Reads the ATRAC9 configuration word that the containers carry: the stream geometry a demuxer and a muxer need,
 * without decoding.
 *
 * {@snippet :
 * u8  header              0xFE
 * u4  sampleRateIndex     11025, 12000, 16000, 22050, 24000, 32000, 44100, 48000, 44100, 48000, 64000, 88200,
 *                         96000, 128000, 176400, 192000 Hz
 * u3  channelConfigIndex  mono, dual mono, stereo, 5.1, 7.1, 4.0
 * u1  validation          0
 * u11 frameBytes - 1
 * u2  superframeIndex     2^superframeIndex frames per superframe
 * u3  unused
 * }
 *
 * <p>Instances are immutable and thread-safe.
 *
 * @param word the configuration word, most significant byte first
 */
public record Atrac9Configs(int word) {
    /**
     * The size of the configuration word in bytes.
     */
    public static final int SIZE = 4;

    private static final int[] RATES = {
            11025, 12000, 16000, 22050, 24000, 32000, 44100, 48000,
            44100, 48000, 64000, 88200, 96000, 128000, 176400, 192000
    };

    private static final int[] CHANNELS = {1, 2, 2, 6, 8, 4};

    /**
     * Returns the configuration of a 4-byte word, or of the 12 bytes that follow the {@code WAVEFORMATEXTENSIBLE}
     * fields of an {@code .at9} format (a version, the word and a reserved field).
     *
     * @param config the bytes
     * @return the configuration, or {@code null} when the bytes are of another size, the header byte is not
     * {@code 0xFE}, the validation bit is set or the channel configuration index is above 5
     */
    public static Atrac9Configs parse(byte[] config) {
        var offset = switch (config.length) {
            case SIZE -> 0;
            case 12 -> 4;
            default -> -1;
        };
        if (offset < 0) {
            return null;
        }
        var word = (config[offset] & 0xFF) << 24 | (config[offset + 1] & 0xFF) << 16
                | (config[offset + 2] & 0xFF) << 8 | config[offset + 3] & 0xFF;
        if (word >>> 24 != 0xFE || (word >>> 16 & 1) != 0 || (word >>> 17 & 7) >= CHANNELS.length) {
            return null;
        }
        return new Atrac9Configs(word);
    }

    /**
     * {@return the configuration word in its 4 bytes, most significant first}
     */
    public byte[] bytes() {
        return new byte[]{(byte) (word >>> 24), (byte) (word >>> 16), (byte) (word >>> 8), (byte) word};
    }

    /**
     * {@return the sampling rate in hertz}
     */
    public int sampleRate() {
        return RATES[word >>> 20 & 15];
    }

    /**
     * {@return the channel count}
     */
    public int channels() {
        return CHANNELS[word >>> 17 & 7];
    }

    /**
     * {@return the layout of the channels in block order: mono, stereo, 5.1, 7.1 or quad, without a declared one}
     */
    public ChannelLayout layout() {
        return ChannelLayout.defaultFor(channels());
    }

    /**
     * {@return the size of a superframe in bytes, from 1 to 16384}
     */
    public int superframeBytes() {
        return ((word >>> 5 & 0x7FF) + 1) << (word >>> 3 & 3);
    }

    /**
     * {@return the samples per channel of a superframe: frames of 64, 128 or 256 samples by rate, 1 to 8 of them}
     */
    public int superframeSamples() {
        var rate = (word >>> 20 & 15) % 8;
        return 1 << (rate < 2 ? 6 : rate < 5 ? 7 : 8) << (word >>> 3 & 3);
    }
}
