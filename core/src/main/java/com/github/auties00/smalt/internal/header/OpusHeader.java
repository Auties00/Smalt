package com.github.auties00.smalt.internal.header;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.internal.limits.LimitChecks;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Holds the fields of an Ogg Opus identification header, {@code OpusHead} (RFC 7845 section 5.1).
 *
 * <p>{@link #parse} checks the structure every reader of the header relies on; the checks only a
 * decoder needs (the supported mapping families, the channel limit of family 1) are left to it.
 * {@link #parseLenient} checks only what is needed to read the fields. Instances are immutable
 * and thread-safe.
 *
 * @param version the encapsulation version; its major version (high four bits) is 0
 * @param channels the number of output channels, from 1 to 255
 * @param preSkip the number of 48 kHz samples to discard from the start of the decoded output
 * @param inputSampleRate the sampling rate of the original input in hertz, informational, or 0
 * @param outputGain the gain to apply to the output, in Q7.8 decibels
 * @param family the channel mapping family
 * @param streamCount the number of elementary streams: 1 for family 0
 * @param coupledCount the number of coupled (stereo) streams: {@code channels - 1} for family 0
 * @param mapping the channel mapping table after the two stream counts: for family 3 the demixing matrix
 * (RFC 8486 section 3.2, {@code channels} rows and {@code streamCount + coupledCount} columns of Q15 values
 * in column-major order, two little-endian bytes each), for every other family but 0 one entry per output
 * channel (the decoded channel that feeds it, or 255 for silence), and an empty array for family 0
 */
public record OpusHeader(int version, int channels, int preSkip, long inputSampleRate, int outputGain, int family,
                         int streamCount, int coupledCount, byte[] mapping) {
    /**
     * The size of a header without a channel mapping table.
     */
    public static final int BASE_SIZE = 19;

    /**
     * The magic signature that starts the header.
     */
    private static final byte[] MAGIC = "OpusHead".getBytes(StandardCharsets.US_ASCII);

    /**
     * Copies the mapping table, so that the record owns it.
     */
    public OpusHeader {
        mapping = mapping.clone();
    }

    /**
     * Parses a header and checks its structure.
     *
     * <p>Beyond the checks of {@link #parseLenient}, the header must declare at most two channels
     * in family 0, and in the other families at least one stream, at most as many coupled
     * streams as streams and at most 255 decoded channels, and in families other than 3 a mapping
     * table whose entries name a decoded channel or 255. Family 1 with more than eight channels is
     * accepted.
     *
     * @param data the header bytes
     * @param limits the bounds the channel count is checked against
     * @return the header
     * @throws MediaException.Malformed if the header is truncated, lacks its signature or is
     * inconsistent
     * @throws MediaException.Unsupported if the major version is not 0
     * @throws MediaException.LimitExceeded if the channel count exceeds
     * {@link MediaLimits#maxChannels()}
     */
    public static OpusHeader parse(byte[] data, MediaLimits limits) {
        var header = parseLenient(data);
        var channels = header.channels;
        if (header.family == 0) {
            if (channels > 2) {
                throw new MediaException.Malformed("OpusHead mapping family 0 with " + channels + " channels");
            }
        } else {
            var streams = header.streamCount;
            var coupled = header.coupledCount;
            if (streams == 0 || coupled > streams || streams + coupled > 255) {
                throw new MediaException.Malformed("OpusHead declares " + streams + " streams, " + coupled
                        + " coupled");
            }
            for (var i = 0; header.family != 3 && i < channels; i++) {
                var entry = header.mapping[i] & 0xFF;
                if (entry != 255 && entry >= streams + coupled) {
                    throw new MediaException.Malformed("OpusHead maps channel " + i + " to missing channel " + entry);
                }
            }
        }
        LimitChecks.checkChannels(limits, channels);
        return header;
    }

    /**
     * Parses a header, checking only what reading its fields needs.
     *
     * <p>The checks run in this order: the size and the signature, the major version, the
     * presence of the whole mapping table when the family is not 0 (for family 3, the stream counts
     * and a demixing matrix of their sum times the channel count coefficients), and a non-zero channel
     * count.
     *
     * @param data the header bytes
     * @return the header
     * @throws MediaException.Malformed if the header is truncated, lacks its signature or
     * declares zero channels
     * @throws MediaException.Unsupported if the major version is not 0
     */
    public static OpusHeader parseLenient(byte[] data) {
        if (data.length < BASE_SIZE || !Arrays.equals(data, 0, MAGIC.length, MAGIC, 0, MAGIC.length)) {
            throw new MediaException.Malformed("invalid OpusHead");
        }
        var version = data[8] & 0xFF;
        if (version >> 4 != 0) {
            throw new MediaException.Unsupported("opus", "unsupported OpusHead version " + version);
        }
        var channels = data[9] & 0xFF;
        var family = data[18] & 0xFF;
        if (family != 0 && (data.length < BASE_SIZE + 2
                || data.length < BASE_SIZE + 2 + tableSize(family, channels, data[19] & 0xFF, data[20] & 0xFF))) {
            throw new MediaException.Malformed("OpusHead channel mapping truncated");
        }
        if (channels == 0) {
            throw new MediaException.Malformed("OpusHead declares zero channels");
        }
        var preSkip = (data[10] & 0xFF) | (data[11] & 0xFF) << 8;
        var inputSampleRate = (data[12] & 0xFFL) | (data[13] & 0xFFL) << 8 | (data[14] & 0xFFL) << 16
                | (data[15] & 0xFFL) << 24;
        var outputGain = (short) ((data[16] & 0xFF) | (data[17] & 0xFF) << 8);
        if (family == 0) {
            return new OpusHeader(version, channels, preSkip, inputSampleRate, outputGain, 0, 1, channels - 1,
                    new byte[0]);
        }
        var streams = data[19] & 0xFF;
        var coupled = data[20] & 0xFF;
        return new OpusHeader(version, channels, preSkip, inputSampleRate, outputGain, family, streams, coupled,
                Arrays.copyOfRange(data, BASE_SIZE + 2, BASE_SIZE + 2 + tableSize(family, channels, streams, coupled)));
    }

    /**
     * Returns the size of the channel mapping table that follows the two stream counts.
     *
     * @param family the channel mapping family, not 0
     * @param channels the output channel count
     * @param streams the stream count
     * @param coupled the coupled stream count
     * @return {@code 2 * channels * (streams + coupled)} for family 3, otherwise {@code channels}
     */
    public static int tableSize(int family, int channels, int streams, int coupled) {
        return family == 3 ? 2 * channels * (streams + coupled) : channels;
    }

    /**
     * {@return a copy of the channel mapping table, empty for family 0}
     */
    @Override
    public byte[] mapping() {
        return mapping.clone();
    }

    /**
     * {@return the decoded channel that feeds an output channel, or 255 for silence}
     *
     * @param channel the output channel in coded order, from 0 to {@code channels - 1}
     * @throws IllegalStateException if the family is 3, whose channels are mixed by a matrix
     */
    public int mapping(int channel) {
        if (family == 3) {
            throw new IllegalStateException("mapping family 3 mixes its channels with a demixing matrix");
        }
        return family == 0 ? channel : mapping[channel] & 0xFF;
    }

    /**
     * {@return a coefficient of the demixing matrix of family 3, a signed Q15 value}
     *
     * @param row the output channel, from 0 to {@code channels - 1}
     * @param column the decoded channel, from 0 to {@code streamCount + coupledCount - 1}
     * @throws IllegalStateException if the family is not 3
     */
    public int demixing(int row, int column) {
        if (family != 3) {
            throw new IllegalStateException("mapping family " + family + " has no demixing matrix");
        }
        var index = 2 * (column * channels + row);
        return (short) ((mapping[index] & 0xFF) | (mapping[index + 1] & 0xFF) << 8);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Two headers are equal when every field is, the mapping tables compared by content.
     */
    @Override
    public boolean equals(Object other) {
        return other instanceof OpusHeader that && version == that.version && channels == that.channels
                && preSkip == that.preSkip && inputSampleRate == that.inputSampleRate
                && outputGain == that.outputGain && family == that.family && streamCount == that.streamCount
                && coupledCount == that.coupledCount && Arrays.equals(mapping, that.mapping);
    }

    @Override
    public int hashCode() {
        var hash = 31 * version + channels;
        hash = 31 * hash + preSkip;
        hash = 31 * hash + Long.hashCode(inputSampleRate);
        hash = 31 * hash + outputGain;
        hash = 31 * hash + family;
        hash = 31 * hash + streamCount;
        hash = 31 * hash + coupledCount;
        return 31 * hash + Arrays.hashCode(mapping);
    }

    @Override
    public String toString() {
        return "OpusHeader[version=" + version + ", channels=" + channels + ", preSkip=" + preSkip
                + ", inputSampleRate=" + inputSampleRate + ", outputGain=" + outputGain + ", family=" + family
                + ", streamCount=" + streamCount + ", coupledCount=" + coupledCount + ", mapping="
                + Arrays.toString(mapping) + "]";
    }
}
