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
 * The fields of an Ogg Opus identification header, {@code OpusHead} (RFC 7845 section 5.1, RFC 8486
 * section 3.2), and the channel routing the decoder derives from them.
 *
 * <p>Families 0 and 1 use the Vorbis channel order, which {@link #wavePosition(int)} maps to the WAVE order of
 * {@link ChannelLayout#defaultFor(int)}; family 255, the ambisonic families 2 and 3 and every reserved family
 * have {@link Speaker#UNKNOWN} positions in coded order. Family 3 has no mapping table: its output channels are
 * the product of the demixing matrix and the decoded channels. The record owns its mapping table and demixing
 * matrix: the constructor and the accessors copy them.
 *
 * @param version the encapsulation version; its major version (high four bits) is 0
 * @param channels the number of output channels, from 1 to 255
 * @param preSkip the number of samples at 48 kHz to discard from the decoder output
 * @param inputSampleRate the sampling rate of the original input in hertz, informational
 * @param outputGain the gain to apply to the output, in Q7.8 decibels
 * @param mappingFamily the channel mapping family
 * @param streamCount the number of elementary streams
 * @param coupledCount the number of coupled (stereo) streams
 * @param mapping the decoded channel feeding each output channel in coded order, or 255 for silence; empty for
 * family 3
 * @param demixing the demixing matrix of family 3, {@code channels} rows by {@code streamCount + coupledCount}
 * columns of Q15 coefficients in column-major order; empty for the other families
 */
record OpusHead(int version, int channels, int preSkip, long inputSampleRate, int outputGain,
                int mappingFamily, int streamCount, int coupledCount, int[] mapping, short[] demixing) {
    private static final byte[] MAGIC = "OpusHead".getBytes(StandardCharsets.US_ASCII);

    OpusHead {
        mapping = mapping.clone();
        demixing = demixing.clone();
    }

    /**
     * Constructs a header of a family other than 3, which has no demixing matrix.
     */
    OpusHead(int version, int channels, int preSkip, long inputSampleRate, int outputGain, int mappingFamily,
             int streamCount, int coupledCount, int[] mapping) {
        this(version, channels, preSkip, inputSampleRate, outputGain, mappingFamily, streamCount, coupledCount,
                mapping, new short[0]);
    }

    /**
     * Parses an identification header and checks that this decoder supports it.
     *
     * @param data the header bytes
     * @param limits the bounds the channel count is checked against
     * @return the header
     * @throws MediaException.Malformed if the header is truncated or inconsistent, or declares more than eight
     * channels in family 1
     * @throws MediaException.Unsupported if the major version is not 0
     * @throws MediaException.LimitExceeded if the channel count exceeds {@link MediaLimits#maxChannels()}
     */
    static OpusHead parse(byte[] data, MediaLimits limits) {
        var header = OpusHeader.parse(data, limits);
        var channels = header.channels();
        var family = header.family();
        if (family == 1 && channels > VorbisChannelOrder.MAX_CHANNELS) {
            throw new MediaException.Malformed("OpusHead mapping family 1 with " + channels + " channels");
        }
        if (family == 3) {
            var columns = header.streamCount() + header.coupledCount();
            var demixing = new short[channels * columns];
            for (var column = 0; column < columns; column++) {
                for (var row = 0; row < channels; row++) {
                    demixing[column * channels + row] = (short) header.demixing(row, column);
                }
            }
            return new OpusHead(header.version(), channels, header.preSkip(), header.inputSampleRate(),
                    header.outputGain(), 3, header.streamCount(), header.coupledCount(), new int[0], demixing);
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
     * Returns the header of a multistream stream with a channel mapping table and no output gain.
     *
     * @param channels the number of channels, from 1 to 255
     * @param preSkip the pre-skip, in samples at 48 kHz
     * @param inputSampleRate the original sampling rate in hertz
     * @param mappingFamily the channel mapping family, neither 0 nor 3
     * @param streamCount the number of elementary streams
     * @param coupledCount the number of coupled streams
     * @param mapping the coded channel feeding each output channel
     * @return the header
     */
    static OpusHead multistream(int channels, int preSkip, long inputSampleRate, int mappingFamily, int streamCount,
                                int coupledCount, int[] mapping) {
        return new OpusHead(1, channels, preSkip, inputSampleRate, 0, mappingFamily, streamCount, coupledCount, mapping);
    }

    /**
     * Returns the header of a family 3 (projection) stream, whose output gain is the gain of its
     * demixing matrix.
     *
     * @param channels the number of output channels
     * @param preSkip the pre-skip, in samples at 48 kHz
     * @param inputSampleRate the original sampling rate in hertz
     * @param streamCount the number of elementary streams
     * @param coupledCount the number of coupled streams
     * @param demixing the demixing matrix, column-major Q15
     * @param gain the gain of the demixing matrix, in Q7.8 decibels
     * @return the header
     */
    static OpusHead projection(int channels, int preSkip, long inputSampleRate, int streamCount, int coupledCount,
                               short[] demixing, int gain) {
        return new OpusHead(1, channels, preSkip, inputSampleRate, gain, 3, streamCount, coupledCount, new int[0],
                demixing);
    }

    /**
     * {@return a copy of the mapping table}
     */
    @Override
    public int[] mapping() {
        return mapping.clone();
    }

    /**
     * {@return a copy of the demixing matrix}
     */
    @Override
    public short[] demixing() {
        return demixing.clone();
    }

    /**
     * {@return the number of decoded channels, the sum of the stream and coupled stream counts}
     */
    int decodedChannels() {
        return streamCount + coupledCount;
    }

    /**
     * {@return the header bytes, with a channel mapping table unless the family is 0}
     */
    byte[] toBytes() {
        var table = mappingFamily == 0 ? 0 : 2 + (mappingFamily == 3 ? 2 * demixing.length : channels);
        var out = new byte[OpusHeader.BASE_SIZE + table];
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
            if (mappingFamily == 3) {
                for (var i = 0; i < demixing.length; i++) {
                    out[21 + 2 * i] = (byte) demixing[i];
                    out[22 + 2 * i] = (byte) (demixing[i] >> 8);
                }
            } else {
                for (var i = 0; i < channels; i++) {
                    out[21 + i] = (byte) mapping[i];
                }
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
     * {@return whether the channels are ambisonic, that is, the family is 2 or 3}
     */
    boolean ambisonic() {
        return mappingFamily == 2 || mappingFamily == 3;
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
