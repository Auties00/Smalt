/* Ports libopus 1.5.2 (src/opus_multistream_encoder.c, src/opus_multistream.c). Copyright Xiph.Org Foundation;
   BSD 3-Clause License. Ports libopus 1.5.2 (src/opus_projection_encoder.c, src/mapping_matrix.c). Copyright
   Google Inc.; BSD 2-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.audio.opus.internal;

import com.github.auties00.smalt.audio.opus.internal.celt.SurroundAnalysis;
import com.github.auties00.smalt.internal.task.ParallelBands;

import static com.github.auties00.smalt.audio.opus.internal.OpusDefines.*;

/**
 * Encodes an Opus multistream packet (RFC 7845 section 5.1.1): the input channels are split into
 * coupled (stereo) and mono elementary streams by a channel mapping, each coded by its own
 * {@link OpusStreamEncoder}, and the stream packets are joined with self-delimited framing.
 *
 * <p>The encoder is created from a channel mapping family ({@code opus_multistream_surround_encoder_create}):
 * <ul>
 * <li>family 0: one mono or stereo stream;</li>
 * <li>family 1: the Vorbis channel order for 1 to 8 channels, with the LFE channel of 5.1, 6.1 and
 * 7.1 in its own narrowband CELT stream and, from three channels on, the surround masking analysis
 * that lowers the rate of the bands other channels mask, coupled streams forced to stereo CELT;</li>
 * <li>family 2: ambisonics, one mono stream per ambisonic channel plus an optional coupled
 * non-diegetic stereo stream, all CELT;</li>
 * <li>family 3: ambisonics of order 1 to 5, optionally followed by a non-diegetic stereo pair, mixed
 * by the precomputed matrix of the order into {@code channels / 2} coupled streams and, for an odd
 * count, one mono stream ({@code opus_projection_ambisonics_encoder_create}); the identification
 * header carries the demixing matrix;</li>
 * <li>family 255: one mono stream per channel, in input order.</li>
 * </ul>
 *
 * <p>Input is interleaved in coded channel order, the Vorbis order for family 1. With a variable
 * bitrate and room for the largest packet of every stream, the streams are coded in parallel; the
 * packets do not depend on the thread count. Instances are not thread-safe.
 *
 * @implNote This implementation picks the surround bandwidth from the total bitrate the automatic
 * or maximum setting stands for, where libopus 1.5.2 compares the {@code OPUS_AUTO} and
 * {@code OPUS_BITRATE_MAX} sentinels themselves with the per-channel thresholds and so codes every
 * stream in narrowband; it also lets a forced bandwidth replace, and a maximum bandwidth cap, the
 * surround choice, which libopus overrides on every packet. With an explicit bitrate and neither
 * bandwidth setting the packets are byte-identical to the reference's.
 */
public final class OpusMultistreamEncoder {
    /**
     * The largest packet one stream encoder may return: six 20 ms frames ({@code MS_FRAME_TMP}).
     */
    static final int MS_FRAME_TMP = 6 * 1275 + 12;

    /**
     * No surround or ambisonic processing ({@code MAPPING_TYPE_NONE}).
     */
    private static final int MAPPING_TYPE_NONE = 0;

    /**
     * Surround masking and LFE handling of family 1 with three or more channels
     * ({@code MAPPING_TYPE_SURROUND}).
     */
    private static final int MAPPING_TYPE_SURROUND = 1;

    /**
     * Ambisonics of family 2 ({@code MAPPING_TYPE_AMBISONICS}).
     */
    private static final int MAPPING_TYPE_AMBISONICS = 2;

    /**
     * The stream count, coupled stream count and mapping of family 1, indexed by channel count minus
     * one ({@code vorbis_mappings}).
     */
    private static final int[][] VORBIS_MAPPINGS = {
            {1, 0, 0},
            {1, 1, 0, 1},
            {2, 1, 0, 2, 1},
            {2, 2, 0, 1, 2, 3},
            {3, 2, 0, 4, 1, 2, 3},
            {4, 2, 0, 4, 1, 2, 3, 5},
            {4, 3, 0, 4, 1, 2, 3, 5, 6},
            {5, 3, 0, 6, 1, 2, 3, 4, 5, 7}
    };

    /**
     * The number of input channels.
     */
    private final int nb_channels;

    /**
     * The number of elementary streams.
     */
    private final int nb_streams;

    /**
     * The number of coupled streams, which come first.
     */
    private final int nb_coupled_streams;

    /**
     * The coded channel feeding each input channel: {@code 2s} and {@code 2s + 1} for the channels of
     * coupled stream {@code s}, {@code s + nb_coupled_streams} for mono stream {@code s}.
     */
    private final int[] mapping;

    /**
     * The mapping family.
     */
    private final int family;

    /**
     * The surround or ambisonic processing, one of the {@code MAPPING_TYPE_*} constants.
     */
    private final int mapping_type;

    /**
     * The index of the LFE stream, or {@code -1}.
     */
    private final int lfe_stream;

    /**
     * The input sampling rate.
     */
    private final int Fs;

    /**
     * The requested total bitrate, {@link OpusDefines#OPUS_AUTO} or {@link OpusDefines#OPUS_BITRATE_MAX}.
     */
    private int bitrate_bps = OPUS_AUTO;

    /**
     * The forced bandwidth, or {@link OpusDefines#OPUS_AUTO}.
     */
    private int user_bandwidth = OPUS_AUTO;

    /**
     * The maximum bandwidth.
     */
    private int max_bandwidth = OPUS_BANDWIDTH_FULLBAND;

    /**
     * The encoder of each stream.
     */
    private final OpusStreamEncoder[] encoders;

    /**
     * The mixing matrix of family 3, column-major Q15 with {@link #matrixRows} rows, or {@code null}.
     */
    private final short[] mixing;

    /**
     * The demixing matrix of family 3, column-major Q15 with {@link #matrixRows} rows, or {@code null}.
     */
    private final short[] demixing;

    /**
     * The number of rows of {@link #mixing} and {@link #demixing}.
     */
    private final int matrixRows;

    /**
     * The gain of {@link #demixing} in Q8 decibels.
     */
    private final int demixingGain;

    /**
     * The surround masking analysis, or {@code null} unless the mapping type is surround.
     */
    private final SurroundAnalysis surround;

    /**
     * The signal-to-mask ratio of every input channel ({@code bandSMR}).
     */
    private final float[] bandSMR;

    /**
     * The energy mask of each stream, 21 values per stream channel ({@code bandLogE}).
     */
    private final float[][] streamMask;

    /**
     * The interleaved input of each stream ({@code buf}).
     */
    private final float[][] streamBuf;

    /**
     * The packet of each stream before self-delimiting ({@code tmp_data}).
     */
    private final byte[][] streamData;

    /**
     * The packet length of each stream coded in parallel.
     */
    private final int[] streamLength;

    /**
     * The input channel of each mono stream, or of the left channel of each coupled stream.
     */
    private final int[] streamFirstChannel;

    /**
     * The input channel of the right channel of each coupled stream, or {@code -1} for a mono stream.
     */
    private final int[] streamSecondChannel;

    /**
     * The bitrate of each stream ({@code bitrates}).
     */
    private final int[] bitrates;

    /**
     * The repacketizer adding the self-delimiting lengths.
     */
    private final Repacketizer rp = new Repacketizer();

    /**
     * The 16-bit input converted to float.
     */
    private final float[] pcm_int16;

    /**
     * The total bitrate the last rate allocation started from.
     */
    private int allocatedBitrate;

    /**
     * The input of the frame whose streams are coded in parallel, or {@code null} between frames.
     */
    private float[] framePcm;

    /**
     * The index of the first sample of {@link #framePcm}.
     */
    private int framePcmOff;

    /**
     * The analysis frame size of the frame coded in parallel.
     */
    private int frameAnalysisSize;

    /**
     * The frame size of the frame coded in parallel.
     */
    private int frameSize;

    /**
     * The effective input depth of the frame coded in parallel.
     */
    private int frameLsbDepth;

    /**
     * The band of {@link ParallelBands#run} that runs {@link #encodeParallelStream(int)}.
     */
    private final ParallelBands.Band parallelStream;

    /**
     * Creates an encoder for a channel mapping family ({@code opus_multistream_surround_encoder_init}).
     *
     * @param Fs the input rate: 8000, 12000, 16000, 24000 or 48000
     * @param channels the number of input channels: 1 or 2 for family 0, 1 to 8 for family 1, a
     * square or a square plus two up to 227 for family 2, a square from 4 to 36 or such a square plus
     * two for family 3, 1 to 255 for family 255
     * @param family the mapping family: 0, 1, 2, 3 or 255
     * @param application one of the {@code OPUS_APPLICATION_*} constants
     * @throws IllegalArgumentException if the configuration is invalid or the family does not cover
     * the channel count
     */
    public OpusMultistreamEncoder(int Fs, int channels, int family, int application) {
        if (channels > 255 || channels < 1) {
            throw new IllegalArgumentException("invalid channel count " + channels);
        }
        int streams;
        int coupled;
        var map = new int[channels];
        var lfe = -1;
        var order = -1;
        if (family == 0) {
            if (channels == 1) {
                streams = 1;
                coupled = 0;
            } else if (channels == 2) {
                streams = 1;
                coupled = 1;
                map[1] = 1;
            } else {
                throw new IllegalArgumentException("mapping family 0 codes 1 or 2 channels, not " + channels);
            }
        } else if (family == 1 && channels <= 8) {
            var layout = VORBIS_MAPPINGS[channels - 1];
            streams = layout[0];
            coupled = layout[1];
            System.arraycopy(layout, 2, map, 0, channels);
            if (channels >= 6) {
                lfe = streams - 1;
            }
        } else if (family == 255) {
            streams = channels;
            coupled = 0;
            for (var i = 0; i < channels; i++) {
                map[i] = i;
            }
        } else if (family == 2) {
            var counts = ambisonicStreams(channels);
            if (counts == null) {
                throw new IllegalArgumentException("mapping family 2 does not code " + channels + " channels");
            }
            streams = counts[0];
            coupled = counts[1];
            for (var i = 0; i < streams - coupled; i++) {
                map[i] = i + coupled * 2;
            }
            for (var i = 0; i < coupled * 2; i++) {
                map[i + (streams - coupled)] = i;
            }
        } else if (family == 3) {
            order = projectionOrder(channels);
            if (order < 0) {
                throw new IllegalArgumentException("mapping family 3 does not code " + channels + " channels");
            }
            streams = (channels + 1) / 2;
            coupled = channels / 2;
            for (var i = 0; i < channels; i++) {
                map[i] = i;
            }
        } else {
            throw new IllegalArgumentException("mapping family " + family + " does not code " + channels + " channels");
        }
        if (channels > 2 && family == 1) {
            mapping_type = MAPPING_TYPE_SURROUND;
        } else if (family == 2) {
            mapping_type = MAPPING_TYPE_AMBISONICS;
        } else {
            mapping_type = MAPPING_TYPE_NONE;
        }
        this.family = family;
        this.nb_channels = channels;
        this.nb_streams = streams;
        this.nb_coupled_streams = coupled;
        this.mapping = map;
        this.lfe_stream = lfe;
        if (order > 0) {
            this.mixing = MappingMatrices.mixing(order);
            this.demixing = MappingMatrices.demixing(order);
            this.matrixRows = MappingMatrices.size(order);
            this.demixingGain = MappingMatrices.demixingGain(order);
        } else {
            this.mixing = null;
            this.demixing = null;
            this.matrixRows = 0;
            this.demixingGain = 0;
        }
        this.Fs = Fs;
        this.encoders = new OpusStreamEncoder[streams];
        for (var i = 0; i < streams; i++) {
            encoders[i] = new OpusStreamEncoder(Fs, i < coupled ? 2 : 1, application);
            if (i == lfe) {
                encoders[i].setLfe(true);
            }
        }
        this.surround = mapping_type == MAPPING_TYPE_SURROUND ? new SurroundAnalysis(channels) : null;
        this.bandSMR = new float[21 * channels];
        var maxFrame = Fs / 25 * 3;
        this.streamMask = new float[streams][42];
        this.streamBuf = new float[streams][];
        this.streamData = new byte[streams][MS_FRAME_TMP];
        this.streamLength = new int[streams];
        this.streamFirstChannel = new int[streams];
        this.streamSecondChannel = new int[streams];
        for (var s = 0; s < streams; s++) {
            if (s < coupled) {
                streamFirstChannel[s] = leftChannel(s);
                streamSecondChannel[s] = rightChannel(s);
                streamBuf[s] = new float[2 * maxFrame];
            } else {
                streamFirstChannel[s] = monoChannel(s);
                streamSecondChannel[s] = -1;
                streamBuf[s] = new float[maxFrame];
            }
        }
        this.bitrates = new int[streams];
        this.pcm_int16 = new float[maxFrame * channels];
        this.parallelStream = this::encodeParallelStream;
    }

    /**
     * Codes one stream of the frame the {@code frame*} fields describe, at the largest packet
     * budget, into {@link #streamLength}.
     *
     * @param s the stream index
     */
    private void encodeParallelStream(int s) {
        streamLength[s] = encodeStream(s, framePcm, framePcmOff, frameAnalysisSize, frameSize,
                s != nb_streams - 1 ? MS_FRAME_TMP - 2 : MS_FRAME_TMP, frameLsbDepth);
    }

    /**
     * Returns the ambisonic stream counts of a channel count ({@code validate_ambisonics}).
     *
     * @param channels the channel count
     * @return the stream count and the coupled stream count, or {@code null} when the count is not
     * an ambisonic order squared, optionally plus two non-diegetic channels
     */
    private static int[] ambisonicStreams(int channels) {
        if (channels < 1 || channels > 227) {
            return null;
        }
        var order_plus_one = (int) Math.sqrt(channels);
        var acn_channels = order_plus_one * order_plus_one;
        var nondiegetic_channels = channels - acn_channels;
        if (nondiegetic_channels != 0 && nondiegetic_channels != 2) {
            return null;
        }
        return new int[]{acn_channels + (nondiegetic_channels != 0 ? 1 : 0), nondiegetic_channels != 0 ? 1 : 0};
    }

    /**
     * Returns the ambisonic order mapping family 3 codes a channel count with
     * ({@code get_order_plus_one_from_channels}, limited to the orders with a precomputed matrix).
     *
     * @param channels the channel count
     * @return the order, from 1 to 5, or {@code -1} when the count is not {@code (order + 1)^2},
     * optionally plus two, for one of these orders
     */
    public static int projectionOrder(int channels) {
        if (ambisonicStreams(channels) == null) {
            return -1;
        }
        var order = (int) Math.sqrt(channels) - 1;
        return order >= 1 && order <= 5 ? order : -1;
    }

    /**
     * Returns whether mapping family 2 codes a channel count.
     *
     * @param channels the channel count
     * @return {@code true} for an ambisonic order squared, optionally plus two, up to 227
     */
    public static boolean isAmbisonicChannelCount(int channels) {
        return ambisonicStreams(channels) != null;
    }

    /**
     * Returns the input channel fed by the left channel of a coupled stream ({@code get_left_channel}).
     *
     * @param stream_id the stream index
     * @return the first such input channel, or {@code -1}
     */
    private int leftChannel(int stream_id) {
        for (var i = 0; i < nb_channels; i++) {
            if (mapping[i] == stream_id * 2) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Returns the input channel fed by the right channel of a coupled stream ({@code get_right_channel}).
     *
     * @param stream_id the stream index
     * @return the first such input channel, or {@code -1}
     */
    private int rightChannel(int stream_id) {
        for (var i = 0; i < nb_channels; i++) {
            if (mapping[i] == stream_id * 2 + 1) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Returns the input channel fed by a mono stream ({@code get_mono_channel}).
     *
     * @param stream_id the stream index
     * @return the first such input channel, or {@code -1}
     */
    private int monoChannel(int stream_id) {
        for (var i = 0; i < nb_channels; i++) {
            if (mapping[i] == stream_id + nb_coupled_streams) {
                return i;
            }
        }
        return -1;
    }

    /**
     * {@return the mapping family}
     */
    public int family() {
        return family;
    }

    /**
     * {@return the number of input channels}
     */
    public int channels() {
        return nb_channels;
    }

    /**
     * {@return the number of elementary streams}
     */
    public int streamCount() {
        return nb_streams;
    }

    /**
     * {@return the number of coupled (stereo) streams}
     */
    public int coupledStreamCount() {
        return nb_coupled_streams;
    }

    /**
     * {@return a copy of the channel mapping table: the coded channel feeding each input channel}
     */
    public int[] mapping() {
        return mapping.clone();
    }

    /**
     * Returns the demixing matrix a decoder applies to the decoded channels of a family 3 stream, as
     * the identification header stores it ({@code OPUS_PROJECTION_GET_DEMIXING_MATRIX}).
     *
     * @return {@code channels} rows by {@code streamCount() + coupledStreamCount()} columns of Q15
     * coefficients in column-major order, a new array
     * @throws IllegalStateException if the family is not 3
     */
    public short[] demixingMatrix() {
        if (demixing == null) {
            throw new IllegalStateException("mapping family " + family + " has no demixing matrix");
        }
        var columns = nb_streams + nb_coupled_streams;
        var out = new short[nb_channels * columns];
        for (var i = 0; i < columns; i++) {
            for (var j = 0; j < nb_channels; j++) {
                out[i * nb_channels + j] = demixing[matrixRows * i + j];
            }
        }
        return out;
    }

    /**
     * Returns the gain of the demixing matrix ({@code OPUS_PROJECTION_GET_DEMIXING_MATRIX_GAIN}).
     *
     * @return the gain in Q8 decibels, 0 unless the family is 3
     */
    public int demixingGain() {
        return demixingGain;
    }

    /**
     * {@return the input sampling rate}
     */
    public int sampleRate() {
        return Fs;
    }

    /**
     * Sets the target total bitrate ({@code OPUS_SET_BITRATE}).
     *
     * @param value the bitrate in bits per second, {@link OpusDefines#OPUS_AUTO} or
     * {@link OpusDefines#OPUS_BITRATE_MAX}; clamped to {@code [500, 300000]} per channel
     * @throws IllegalArgumentException if the value is not positive and not a special value
     */
    public void setBitrate(int value) {
        if (value != OPUS_AUTO && value != OPUS_BITRATE_MAX) {
            if (value <= 0) {
                throw new IllegalArgumentException("invalid bitrate " + value);
            }
            value = Math.min(300000 * nb_channels, Math.max(500 * nb_channels, value));
        }
        bitrate_bps = value;
    }

    /**
     * Returns the sum of the bitrates the streams target ({@code OPUS_GET_BITRATE}).
     *
     * @return the bitrate in bits per second
     */
    public int bitrate() {
        var value = 0;
        for (var encoder : encoders) {
            value += encoder.bitrate();
        }
        return value;
    }

    /**
     * Sets whether the bitrate is variable ({@code OPUS_SET_VBR}).
     *
     * @param value {@code true} for variable bitrate
     */
    public void setVbr(boolean value) {
        for (var encoder : encoders) {
            encoder.setVbr(value);
        }
    }

    /**
     * Sets whether the variable bitrate is constrained ({@code OPUS_SET_VBR_CONSTRAINT}).
     *
     * @param value {@code true} for constrained variable bitrate
     */
    public void setVbrConstraint(boolean value) {
        for (var encoder : encoders) {
            encoder.setVbrConstraint(value);
        }
    }

    /**
     * Sets the computational complexity of every stream ({@code OPUS_SET_COMPLEXITY}).
     *
     * @param value the complexity, 0 to 10
     * @throws IllegalArgumentException if the value is out of range
     */
    public void setComplexity(int value) {
        for (var encoder : encoders) {
            encoder.setComplexity(value);
        }
    }

    /**
     * Sets the signal type hint of every stream ({@code OPUS_SET_SIGNAL}).
     *
     * @param value {@link OpusDefines#OPUS_AUTO}, {@link OpusDefines#OPUS_SIGNAL_VOICE} or
     * {@link OpusDefines#OPUS_SIGNAL_MUSIC}
     * @throws IllegalArgumentException if the value is invalid
     */
    public void setSignal(int value) {
        for (var encoder : encoders) {
            encoder.setSignal(value);
        }
    }

    /**
     * Sets the maximum audio bandwidth of every stream ({@code OPUS_SET_MAX_BANDWIDTH}).
     *
     * @param value one of the {@code OPUS_BANDWIDTH_*} constants
     * @throws IllegalArgumentException if the value is invalid
     */
    public void setMaxBandwidth(int value) {
        for (var encoder : encoders) {
            encoder.setMaxBandwidth(value);
        }
        max_bandwidth = value;
    }

    /**
     * Forces the audio bandwidth of every stream ({@code OPUS_SET_BANDWIDTH}).
     *
     * @param value one of the {@code OPUS_BANDWIDTH_*} constants or {@link OpusDefines#OPUS_AUTO}
     * @throws IllegalArgumentException if the value is invalid
     */
    public void setBandwidth(int value) {
        for (var encoder : encoders) {
            encoder.setBandwidth(value);
        }
        user_bandwidth = value;
    }

    /**
     * Sets in-band forward error correction on every stream ({@code OPUS_SET_INBAND_FEC}).
     *
     * @param value 0 (off), 1 (on) or 2 (on, but not forcing SILK for music)
     * @throws IllegalArgumentException if the value is out of range
     */
    public void setInbandFec(int value) {
        for (var encoder : encoders) {
            encoder.setInbandFec(value);
        }
    }

    /**
     * Sets the expected packet loss of every stream ({@code OPUS_SET_PACKET_LOSS_PERC}).
     *
     * @param value the loss in percent, 0 to 100
     * @throws IllegalArgumentException if the value is out of range
     */
    public void setPacketLossPerc(int value) {
        for (var encoder : encoders) {
            encoder.setPacketLossPerc(value);
        }
    }

    /**
     * Sets discontinuous transmission on every stream ({@code OPUS_SET_DTX}).
     *
     * @param value {@code true} to emit tiny packets during silence
     */
    public void setDtx(boolean value) {
        for (var encoder : encoders) {
            encoder.setDtx(value);
        }
    }

    /**
     * Sets the effective bit depth of the input ({@code OPUS_SET_LSB_DEPTH}).
     *
     * @param value the depth, 8 to 24
     * @throws IllegalArgumentException if the value is out of range
     */
    public void setLsbDepth(int value) {
        for (var encoder : encoders) {
            encoder.setLsbDepth(value);
        }
    }

    /**
     * Disables inter-frame prediction in every stream ({@code OPUS_SET_PREDICTION_DISABLED}).
     *
     * @param value {@code true} to make every packet independently decodable
     */
    public void setPredictionDisabled(boolean value) {
        for (var encoder : encoders) {
            encoder.setPredictionDisabled(value);
        }
    }

    /**
     * Disables the stereo phase inversion of the coupled streams
     * ({@code OPUS_SET_PHASE_INVERSION_DISABLED}).
     *
     * @param value {@code true} to disable phase inversion
     */
    public void setPhaseInversionDisabled(boolean value) {
        for (var encoder : encoders) {
            encoder.setPhaseInversionDisabled(value);
        }
    }

    /**
     * Returns the encoder look-ahead ({@code OPUS_GET_LOOKAHEAD}).
     *
     * @return the number of samples at the input rate
     */
    public int lookahead() {
        return encoders[0].lookahead();
    }

    /**
     * Returns the final range of the last packet, the XOR of the streams' final ranges
     * ({@code OPUS_GET_FINAL_RANGE}).
     *
     * @return the unsigned 32-bit final range
     */
    public int finalRange() {
        var value = 0;
        for (var encoder : encoders) {
            value ^= encoder.finalRange();
        }
        return value;
    }

    /**
     * Resets every stream and the surround analysis, keeping the configuration
     * ({@code OPUS_RESET_STATE}).
     */
    public void reset() {
        if (surround != null) {
            surround.reset();
        }
        for (var encoder : encoders) {
            encoder.reset();
        }
    }

    /**
     * Returns the smallest packet the encoder produces for a frame size.
     *
     * @param frameSize the number of samples per channel
     * @return the size in bytes
     */
    public int smallestPacket(int frameSize) {
        var smallest = nb_streams * 2 - 1;
        if (Fs / frameSize == 10) {
            smallest += nb_streams;
        }
        return smallest;
    }

    /**
     * Splits the total bitrate between the streams of a surround or discrete mapping
     * ({@code surround_rate_allocation}).
     *
     * @param rate receives the bitrate of each stream
     * @param frame_size the frame size in samples
     */
    private void surroundRateAllocation(int[] rate, int frame_size) {
        var nb_lfe = lfe_stream != -1 ? 1 : 0;
        var nb_coupled = nb_coupled_streams;
        var nb_uncoupled = nb_streams - nb_coupled - nb_lfe;
        var nb_normal = 2 * nb_coupled + nb_uncoupled;
        var channel_offset = 40 * Math.max(50, Fs / frame_size);
        int bitrate;
        if (bitrate_bps == OPUS_AUTO) {
            bitrate = nb_normal * (channel_offset + Fs + 10000) + 8000 * nb_lfe;
        } else if (bitrate_bps == OPUS_BITRATE_MAX) {
            bitrate = nb_normal * 300000 + nb_lfe * 128000;
        } else {
            bitrate = bitrate_bps;
        }
        allocatedBitrate = bitrate;
        var lfe_offset = Math.min(bitrate / 20, 3000) + 15 * Math.max(50, Fs / frame_size);
        var stream_offset = (bitrate - channel_offset * nb_normal - lfe_offset * nb_lfe) / nb_normal / 2;
        stream_offset = Math.max(0, Math.min(20000, stream_offset));
        var coupled_ratio = 512;
        var lfe_ratio = 32;
        var total = (nb_uncoupled << 8) + coupled_ratio * nb_coupled + nb_lfe * lfe_ratio;
        var channel_rate = (int) (256 * (long) (bitrate - lfe_offset * nb_lfe
                - stream_offset * (nb_coupled + nb_uncoupled) - channel_offset * nb_normal) / total);
        for (var i = 0; i < nb_streams; i++) {
            if (i < nb_coupled_streams) {
                rate[i] = 2 * channel_offset + Math.max(0, stream_offset + (channel_rate * coupled_ratio >> 8));
            } else if (i != lfe_stream) {
                rate[i] = channel_offset + Math.max(0, stream_offset + channel_rate);
            } else {
                rate[i] = Math.max(0, lfe_offset + (channel_rate * lfe_ratio >> 8));
            }
        }
    }

    /**
     * Splits the total bitrate evenly between the streams of an ambisonic mapping
     * ({@code ambisonics_rate_allocation}).
     *
     * @param rate receives the bitrate of each stream
     * @param frame_size the frame size in samples
     */
    private void ambisonicsRateAllocation(int[] rate, int frame_size) {
        var channels = nb_streams + nb_coupled_streams;
        int total_rate;
        if (bitrate_bps == OPUS_AUTO) {
            total_rate = (nb_coupled_streams + nb_streams) * (Fs + 60 * Fs / frame_size) + nb_streams * 15000;
        } else if (bitrate_bps == OPUS_BITRATE_MAX) {
            total_rate = channels * 320000;
        } else {
            total_rate = bitrate_bps;
        }
        allocatedBitrate = total_rate;
        var per_stream_rate = total_rate / nb_streams;
        for (var i = 0; i < nb_streams; i++) {
            rate[i] = per_stream_rate;
        }
    }

    /**
     * Computes the bitrate of each stream ({@code rate_allocation}).
     *
     * @param rate receives the bitrate of each stream, at least 500
     * @param frame_size the frame size in samples
     * @return the sum of the stream bitrates
     */
    private int rateAllocation(int[] rate, int frame_size) {
        if (mapping_type == MAPPING_TYPE_AMBISONICS) {
            ambisonicsRateAllocation(rate, frame_size);
        } else {
            surroundRateAllocation(rate, frame_size);
        }
        var rate_sum = 0;
        for (var i = 0; i < nb_streams; i++) {
            rate[i] = Math.max(rate[i], 500);
            rate_sum += rate[i];
        }
        return rate_sum;
    }

    /**
     * Encodes one frame of interleaved float input into one multistream packet
     * ({@code opus_multistream_encode_float}).
     *
     * @param pcm the interleaved input in coded channel order, nominally in {@code [-1, 1]}
     * @param pcmOff the index of the first sample
     * @param frame_size the number of samples per channel: 2.5, 5, 10, 20, 40, 60, 80, 100 or 120 ms
     * @param data the output buffer
     * @param dataOff the index of the first output byte
     * @param max_data_bytes the space available
     * @return the packet length, or a negative error code
     */
    public int encodeFloat(float[] pcm, int pcmOff, int frame_size, byte[] data, int dataOff, int max_data_bytes) {
        return encodeNative(pcm, pcmOff, frame_size, data, dataOff, max_data_bytes, 24);
    }

    /**
     * Encodes one frame of interleaved 16-bit input into one multistream packet
     * ({@code opus_multistream_encode}).
     *
     * <p>Each sample is scaled by {@code 1/32768} and coded as 16-bit input; as with
     * {@link OpusStreamEncoder#encode(short[], int, int, byte[], int, int)} the packets equal the
     * reference's.
     *
     * @param pcm the interleaved input in coded channel order
     * @param pcmOff the index of the first sample
     * @param frame_size the number of samples per channel: 2.5, 5, 10, 20, 40, 60, 80, 100 or 120 ms
     * @param data the output buffer
     * @param dataOff the index of the first output byte
     * @param max_data_bytes the space available
     * @return the packet length, or a negative error code
     */
    public int encode(short[] pcm, int pcmOff, int frame_size, byte[] data, int dataOff, int max_data_bytes) {
        if (OpusStreamEncoder.frameSizeSelect(frame_size, Fs) <= 0) {
            return OPUS_BAD_ARG;
        }
        var count = frame_size * nb_channels;
        for (var i = 0; i < count; i++) {
            pcm_int16[i] = (1.0f / 32768) * pcm[pcmOff + i];
        }
        return encodeNative(pcm_int16, 0, frame_size, data, dataOff, max_data_bytes, 16);
    }

    /**
     * Encodes one frame ({@code opus_multistream_encode_native}).
     *
     * @param pcm the interleaved input in coded channel order
     * @param pcmOff the index of the first sample
     * @param analysis_frame_size the number of samples per channel
     * @param data the output buffer
     * @param dataOff the index of the first output byte
     * @param max_data_bytes the space available
     * @param lsb_depth the effective bit depth of the input
     * @return the packet length, or a negative error code
     */
    private int encodeNative(float[] pcm, int pcmOff, int analysis_frame_size, byte[] data, int dataOff,
                             int max_data_bytes, int lsb_depth) {
        var vbr = encoders[0].vbr();
        var frame_size = OpusStreamEncoder.frameSizeSelect(analysis_frame_size, Fs);
        if (frame_size <= 0) {
            return OPUS_BAD_ARG;
        }
        var smallest_packet = smallestPacket(frame_size);
        if (max_data_bytes < smallest_packet) {
            return OPUS_BUFFER_TOO_SMALL;
        }
        if (mapping_type == MAPPING_TYPE_SURROUND) {
            surround.analyze(pcm, pcmOff, bandSMR, frame_size, Fs);
        }
        var rate_sum = rateAllocation(bitrates, frame_size);
        if (!vbr) {
            if (bitrate_bps == OPUS_AUTO) {
                max_data_bytes = Math.min(max_data_bytes, 3 * rate_sum / (3 * 8 * Fs / frame_size));
            } else if (bitrate_bps != OPUS_BITRATE_MAX) {
                max_data_bytes = Math.min(max_data_bytes, Math.max(smallest_packet,
                        3 * bitrate_bps / (3 * 8 * Fs / frame_size)));
            }
        }
        for (var s = 0; s < nb_streams; s++) {
            var enc = encoders[s];
            enc.setBitrate(bitrates[s]);
            if (mapping_type == MAPPING_TYPE_SURROUND) {
                var equiv_rate = bitrate_bps == OPUS_AUTO || bitrate_bps == OPUS_BITRATE_MAX ? allocatedBitrate : bitrate_bps;
                if (frame_size * 50 < Fs) {
                    equiv_rate -= 60 * (Fs / frame_size - 50) * nb_channels;
                }
                int bandwidth;
                if (equiv_rate > 10000 * nb_channels) {
                    bandwidth = OPUS_BANDWIDTH_FULLBAND;
                } else if (equiv_rate > 7000 * nb_channels) {
                    bandwidth = OPUS_BANDWIDTH_SUPERWIDEBAND;
                } else if (equiv_rate > 5000 * nb_channels) {
                    bandwidth = OPUS_BANDWIDTH_WIDEBAND;
                } else {
                    bandwidth = OPUS_BANDWIDTH_NARROWBAND;
                }
                if (user_bandwidth != OPUS_AUTO) {
                    bandwidth = user_bandwidth;
                } else if (bandwidth > max_bandwidth) {
                    bandwidth = max_bandwidth;
                }
                enc.setBandwidth(bandwidth);
                if (s < nb_coupled_streams) {
                    enc.setForceMode(MODE_CELT_ONLY);
                    enc.setForceChannels(2);
                }
            } else if (mapping_type == MAPPING_TYPE_AMBISONICS) {
                enc.setForceMode(MODE_CELT_ONLY);
            }
        }
        var parallel = vbr && nb_streams > 1 && max_data_bytes >= nb_streams * MS_FRAME_TMP;
        if (parallel) {
            framePcm = pcm;
            framePcmOff = pcmOff;
            frameAnalysisSize = analysis_frame_size;
            frameSize = frame_size;
            frameLsbDepth = lsb_depth;
            try {
                ParallelBands.run(nb_streams, parallelStream);
            } finally {
                framePcm = null;
            }
        }
        var tot_size = 0;
        var out = dataOff;
        for (var s = 0; s < nb_streams; s++) {
            var curr_max = max_data_bytes - tot_size;
            curr_max -= Math.max(0, 2 * (nb_streams - s - 1) - 1);
            if (Fs / frame_size == 10) {
                curr_max -= nb_streams - s - 1;
            }
            curr_max = Math.min(curr_max, MS_FRAME_TMP);
            if (s != nb_streams - 1) {
                curr_max -= curr_max > 253 ? 2 : 1;
            }
            int len;
            if (parallel) {
                if (curr_max != (s != nb_streams - 1 ? MS_FRAME_TMP - 2 : MS_FRAME_TMP)) {
                    throw new IllegalStateException("stream " + s + " was coded with the wrong packet budget");
                }
                len = streamLength[s];
            } else {
                if (!vbr && s == nb_streams - 1) {
                    var rate = curr_max * (8 * Fs / frame_size);
                    if (rate > 0) {
                        encoders[s].setBitrate(rate);
                    }
                }
                len = encodeStream(s, pcm, pcmOff, analysis_frame_size, frame_size, curr_max, lsb_depth);
            }
            if (len < 0) {
                return len;
            }
            rp.init();
            if (rp.cat(streamData[s], 0, len) != OPUS_OK) {
                return OPUS_INTERNAL_ERROR;
            }
            len = rp.outRange(0, rp.nbFrames(), data, out, max_data_bytes - tot_size, s != nb_streams - 1,
                    !vbr && s == nb_streams - 1);
            if (len < 0) {
                return len;
            }
            out += len;
            tot_size += len;
        }
        return tot_size;
    }

    /**
     * Encodes the frame of one elementary stream into {@link #streamData} (the stream loop body of
     * {@code opus_multistream_encode_native}).
     *
     * <p>Streams touch only their own encoder and buffers, so several may be coded at once.
     *
     * @param s the stream index
     * @param pcm the interleaved input in coded channel order
     * @param pcmOff the index of the first sample
     * @param analysis_frame_size the number of samples per channel the analysis reads
     * @param frame_size the frame size in samples
     * @param curr_max the packet budget of the stream in bytes
     * @param lsb_depth the effective bit depth of the input
     * @return the stream packet length, or a negative error code
     */
    private int encodeStream(int s, float[] pcm, int pcmOff, int analysis_frame_size, int frame_size, int curr_max,
                             int lsb_depth) {
        var buf = streamBuf[s];
        var mask = streamMask[s];
        var c1 = streamFirstChannel[s];
        var c2 = streamSecondChannel[s];
        if (mixing != null) {
            mixChannelIn(buf, 0, c2 >= 0 ? 2 : 1, pcm, pcmOff, c1, frame_size);
            if (c2 >= 0) {
                mixChannelIn(buf, 1, 2, pcm, pcmOff, c2, frame_size);
            }
        } else if (c2 >= 0) {
            copyChannelIn(buf, 0, 2, pcm, pcmOff, c1, frame_size);
            copyChannelIn(buf, 1, 2, pcm, pcmOff, c2, frame_size);
            if (mapping_type == MAPPING_TYPE_SURROUND) {
                System.arraycopy(bandSMR, 21 * c1, mask, 0, 21);
                System.arraycopy(bandSMR, 21 * c2, mask, 21, 21);
            }
        } else {
            copyChannelIn(buf, 0, 1, pcm, pcmOff, c1, frame_size);
            if (mapping_type == MAPPING_TYPE_SURROUND) {
                System.arraycopy(bandSMR, 21 * c1, mask, 0, 21);
            }
        }
        var enc = encoders[s];
        if (mapping_type == MAPPING_TYPE_SURROUND) {
            enc.setEnergyMask(mask);
        }
        return enc.encodeNative(buf, 0, frame_size, streamData[s], 0, curr_max, lsb_depth, pcm, pcmOff,
                analysis_frame_size, c1, c2, nb_channels);
    }

    /**
     * Writes one mixed channel of a family 3 frame into an interleaved stream buffer: row {@code row}
     * of the mixing matrix applied to the input channels ({@code mapping_matrix_multiply_channel_in_float}).
     *
     * <p>16-bit input arrives here scaled by {@code 1/32768}; that scaling is exact and commutes with
     * every rounding of the sum, so the result equals {@code mapping_matrix_multiply_channel_in_short}.
     *
     * @param dst the stream buffer
     * @param dstOff the index of the channel's first sample
     * @param dstStride the number of interleaved stream channels
     * @param src the interleaved input
     * @param srcOff the index of the first input sample
     * @param row the mixed channel, a row of the mixing matrix
     * @param frameSize the number of samples
     */
    private void mixChannelIn(float[] dst, int dstOff, int dstStride, float[] src, int srcOff, int row,
                              int frameSize) {
        for (var i = 0; i < frameSize; i++) {
            var tmp = 0.0f;
            for (var col = 0; col < nb_channels; col++) {
                tmp += mixing[matrixRows * col + row] * src[srcOff + i * nb_channels + col];
            }
            dst[dstOff + i * dstStride] = (1.0f / 32768) * tmp;
        }
    }

    /**
     * Copies one input channel into an interleaved stream buffer ({@code opus_copy_channel_in_float}).
     *
     * @param dst the stream buffer
     * @param dstOff the index of the channel's first sample
     * @param dstStride the number of interleaved stream channels
     * @param src the interleaved input
     * @param srcOff the index of the first input sample
     * @param srcChannel the input channel
     * @param frameSize the number of samples
     */
    private void copyChannelIn(float[] dst, int dstOff, int dstStride, float[] src, int srcOff, int srcChannel,
                               int frameSize) {
        for (var i = 0; i < frameSize; i++) {
            dst[dstOff + i * dstStride] = src[srcOff + i * nb_channels + srcChannel];
        }
    }
}
