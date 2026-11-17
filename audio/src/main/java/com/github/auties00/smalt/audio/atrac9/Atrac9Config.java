/* Ports LibAtrac9 (C/src/decinit.c) at commit efca2e3. Copyright 2018 Alex Barney; MIT License. Modified for
   Smalt. */
package com.github.auties00.smalt.audio.atrac9;

import com.github.auties00.smalt.MediaException;

/**
 * The stream configuration of ATRAC9, the 32-bit word that the {@code .at9} format chunk carries.
 *
 * {@snippet :
 * u8  header              0xFE
 * u4  sampleRateIndex     the rate of SAMPLE_RATES; 8 and above code the high-rate tools
 * u3  channelConfigIndex  the blocks of CHANNEL_CONFIGS, 0 to 5
 * u1  validation          0
 * u11 frameBytes - 1
 * u2  superframeIndex     2^superframeIndex frames per superframe
 * u3  unused
 * }
 *
 * <p>Instances are immutable and thread-safe.
 *
 * @param sampleRateIndex the sampling rate index, from 0 to 15
 * @param channelConfigIndex the channel configuration index, from 0 to 5
 * @param frameBytes the size of one frame, from 1 to 2048 bytes
 * @param superframeIndex the base-2 logarithm of the frames per superframe, from 0 to 3
 */
record Atrac9Config(int sampleRateIndex, int channelConfigIndex, int frameBytes, int superframeIndex) {
    /**
     * Parses a configuration word.
     *
     * @param data the bytes holding the word, most significant byte first
     * @param offset the index of its first byte
     * @return the configuration
     * @throws MediaException.Malformed if the header byte or the validation bit is wrong, or the channel
     * configuration index is above 5
     */
    static Atrac9Config parse(byte[] data, int offset) {
        var word = (data[offset] & 0xFF) << 24 | (data[offset + 1] & 0xFF) << 16 | (data[offset + 2] & 0xFF) << 8
                | data[offset + 3] & 0xFF;
        if (word >>> 24 != 0xFE || (word >>> 16 & 1) != 0) {
            throw new MediaException.Malformed(String.format("invalid ATRAC9 configuration %08x", word));
        }
        var channelConfig = word >>> 17 & 7;
        if (channelConfig >= Atrac9Tables.CHANNEL_CONFIGS.length) {
            throw new MediaException.Malformed("ATRAC9 channel configuration " + channelConfig);
        }
        return new Atrac9Config(word >>> 20 & 15, channelConfig, (word >>> 5 & 0x7FF) + 1, word >>> 3 & 3);
    }

    int channelCount() {
        return Atrac9Tables.CHANNEL_COUNTS[channelConfigIndex];
    }

    int sampleRate() {
        return Atrac9Tables.SAMPLE_RATES[sampleRateIndex];
    }

    /**
     * {@return whether the stream uses the coding tools of the high sampling rates (index 8 and above)}
     */
    boolean highSampleRate() {
        return sampleRateIndex > 7;
    }

    int framesPerSuperframe() {
        return 1 << superframeIndex;
    }

    int frameSamplesPower() {
        return Atrac9Tables.FRAME_SAMPLES_POWER[sampleRateIndex];
    }

    /**
     * {@return the samples per channel of a frame: 64, 128 or 256}
     */
    int frameSamples() {
        return 1 << frameSamplesPower();
    }

    int superframeBytes() {
        return frameBytes << superframeIndex;
    }

    int superframeSamples() {
        return frameSamples() << superframeIndex;
    }
}
