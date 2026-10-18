package com.github.auties00.smalt.audio.opus;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.audio.Speaker;
import com.github.auties00.smalt.internal.header.OpusHeader;
import com.github.auties00.smalt.internal.header.VorbisChannelOrder;

import java.nio.charset.StandardCharsets;
import java.util.Collections;

/**
 * The fields of an Ogg Opus identification header, {@code OpusHead} (RFC 7845 section 5.1), and the channel
 * routing the decoder derives from them.
 *
 * <p>Families 0 and 1 use the Vorbis channel order, which {@link #wavePosition(int)} maps to the WAVE order of
 * {@link ChannelLayout#defaultFor(int)}; family 255, the ambisonic family 2 and every reserved family are decoded
 * like 255, with {@link Speaker#UNKNOWN} positions in coded order. Family 3 (a demixing matrix) is not supported.
 * The record owns its mapping table: the constructor and {@link #mapping()} copy it.
 *
 * @param version the encapsulation version; its major version (high four bits) is 0
 * @param channels the number of output channels, from 1 to 255
 * @param preSkip the number of samples at 48 kHz to discard from the decoder output
 * @param inputSampleRate the sampling rate of the original input in hertz, informational
 * @param outputGain the gain to apply to the output, in Q7.8 decibels
 * @param mappingFamily the channel mapping family
 * @param streamCount the number of elementary streams
 * @param coupledCount the number of coupled (stereo) streams
 * @param mapping the decoded channel feeding each output channel in coded order, or 255 for silence
 */
record OpusHead(int version, int channels, int preSkip, long inputSampleRate, int outputGain,
                int mappingFamily, int streamCount, int coupledCount, int[] mapping) {
    private static final byte[] MAGIC = "OpusHead".getBytes(StandardCharsets.US_ASCII);

    OpusHead {
        mapping = mapping.clone();
    }

    /**
     * Parses an identification header and checks that this decoder supports it.
     *
     * @param data the header bytes
     * @param limits the bounds the channel count is checked against
     * @return the header
     * @throws MediaException.Malformed if the header is truncated or inconsistent, or declares more than eight
     * channels in family 1
     * @throws MediaException.Unsupported if the major version is not 0 or the mapping family is 3
     * @throws MediaException.LimitExceeded if the channel count exceeds {@link MediaLimits#maxChannels()}
     */
    static OpusHead parse(byte[] data, MediaLimits limits) {
        var header = OpusHeader.parse(data, limits);
        var channels = header.channels();
        var family = header.family();
        if (family == 3) {
            throw new MediaException.Unsupported("opus channel mapping family 3",
                    "Opus projection (demixing matrix) streams are not supported");
        }
        if (family == 1 && channels > VorbisChannelOrder.MAX_CHANNELS) {
            throw new MediaException.Malformed("OpusHead mapping family 1 with " + channels + " channels");
        }
        var mapping = new int[channels];
        for (var i = 0; i < channels; i++) {
            mapping[i] = header.mapping(i);
        }
        return new OpusHead(header.version(), channels, header.preSkip(), header.inputSampleRate(),
                header.outputGain(), family, header.streamCount(), header.coupledCount(), mapping);
    }

    /**
     * Returns the header of a single elementary stream with mapping family 0 and no output gain.
     *
     * @param channels the number of channels, 1 or 2
     * @param preSkip the pre-skip, in samples at 48 kHz
     * @param inputSampleRate the original sampling rate in hertz
     * @return the header
     */
    static OpusHead family0(int channels, int preSkip, long inputSampleRate) {
        return family0(channels, preSkip, inputSampleRate, 0);
    }

    /**
     * Returns the header of a single elementary stream with mapping family 0 and the given output gain.
     *
     * @param channels the number of channels, 1 or 2
     * @param preSkip the pre-skip, in samples at 48 kHz
     * @param inputSampleRate the original sampling rate in hertz
     * @param outputGain the output gain, in Q7.8 decibels
     * @return the header
     */
    static OpusHead family0(int channels, int preSkip, long inputSampleRate, int outputGain) {
        var mapping = channels == 1 ? new int[]{0} : new int[]{0, 1};
        return new OpusHead(1, channels, preSkip, inputSampleRate, outputGain, 0, 1, channels - 1, mapping);
    }

    /**
     * {@return a copy of the mapping table}
     */
    @Override
    public int[] mapping() {
        return mapping.clone();
    }

    /**
     * {@return the header bytes, with a mapping table unless the family is 0}
     */
    byte[] toBytes() {
        var size = mappingFamily == 0 ? OpusHeader.BASE_SIZE : OpusHeader.BASE_SIZE + 2 + channels;
        var out = new byte[size];
        System.arraycopy(MAGIC, 0, out, 0, MAGIC.length);
        out[8] = (byte) version;
        out[9] = (byte) channels;
        out[10] = (byte) preSkip;
        out[11] = (byte) (preSkip >> 8);
        out[12] = (byte) inputSampleRate;
        out[13] = (byte) (inputSampleRate >> 8);
        out[14] = (byte) (inputSampleRate >> 16);
        out[15] = (byte) (inputSampleRate >> 24);
        out[16] = (byte) outputGain;
        out[17] = (byte) (outputGain >> 8);
        out[18] = (byte) mappingFamily;
        if (mappingFamily != 0) {
            out[19] = (byte) streamCount;
            out[20] = (byte) coupledCount;
            for (var i = 0; i < channels; i++) {
                out[21 + i] = (byte) mapping[i];
            }
        }
        return out;
    }

    /**
     * {@return whether the coded channel order is the Vorbis order, that is, the family is 0 or 1}
     */
    boolean vorbisOrder() {
        return mappingFamily == 0 || mappingFamily == 1;
    }

    /**
     * {@return the output channel layout: the WAVE-order layout for families 0 and 1, otherwise unknown
     * positions}
     */
    ChannelLayout layout() {
        if (vorbisOrder()) {
            return ChannelLayout.defaultFor(channels);
        }
        return new ChannelLayout(Collections.nCopies(channels, Speaker.UNKNOWN));
    }

    /**
     * {@return the output plane of a coded channel, in {@link #layout()} order}
     *
     * @param codedChannel the channel index in coded order, from 0 to {@code channels - 1}
     */
    int wavePosition(int codedChannel) {
        return vorbisOrder() ? VorbisChannelOrder.waveIndex(channels, codedChannel) : codedChannel;
    }
}
