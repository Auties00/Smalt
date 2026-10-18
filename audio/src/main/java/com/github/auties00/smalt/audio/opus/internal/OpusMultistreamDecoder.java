/* Ports libopus 1.5.2 (src/opus_multistream_decoder.c, src/opus_multistream.c). Copyright Xiph.Org Foundation and
   Skype Limited; BSD 3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.audio.opus.internal;

import static com.github.auties00.smalt.audio.opus.internal.OpusDefines.*;

/**
 * Decodes an Opus multistream packet: several elementary streams, coupled (stereo) or mono,
 * whose decoded channels a mapping table routes to output channels (RFC 7845 section 5.1.1).
 *
 * <p>Decoded channels are written into planar output arrays through a caller-supplied routing
 * table, so a reordering from the codec's channel order to the caller's never costs a copy.
 */
public final class OpusMultistreamDecoder {
    /**
     * The number of output channels.
     */
    private final int nb_channels;

    /**
     * The number of elementary streams.
     */
    private final int nb_streams;

    /**
     * The number of coupled (stereo) streams, which come first.
     */
    private final int nb_coupled_streams;

    /**
     * The decoded channel feeding each output channel, or {@code 255} for a silent channel.
     */
    private final int[] mapping;

    /**
     * The decoder of each stream.
     */
    private final OpusStreamDecoder[] decoders;

    /**
     * The output sampling rate in hertz.
     */
    private final int Fs;

    /**
     * The interleaved output of one stream ({@code buf}).
     */
    private final float[] buf;

    /**
     * Receives the number of bytes a self-delimited stream packet occupies.
     */
    private final int[] packetOffset = new int[1];

    /**
     * The frame layout used to validate the packet.
     */
    private final OpusPacket.Layout layout = new OpusPacket.Layout();

    /**
     * Creates a decoder ({@code opus_multistream_decoder_init}).
     *
     * @param Fs             the output sampling rate: 8000, 12000, 16000, 24000 or 48000
     * @param channels       the number of output channels, 1 to 255
     * @param streams        the number of elementary streams
     * @param coupledStreams the number of coupled streams
     * @param mapping        the decoded channel of each output channel, or {@code 255}
     * @throws IllegalArgumentException if the layout is invalid
     */
    public OpusMultistreamDecoder(int Fs, int channels, int streams, int coupledStreams, int[] mapping) {
        if (channels > 255 || channels < 1 || coupledStreams > streams || streams < 1 || coupledStreams < 0
                || streams > 255 - coupledStreams || mapping.length < channels) {
            throw new IllegalArgumentException("invalid Opus multistream layout");
        }
        this.nb_channels = channels;
        this.nb_streams = streams;
        this.nb_coupled_streams = coupledStreams;
        this.mapping = new int[channels];
        System.arraycopy(mapping, 0, this.mapping, 0, channels);
        if (!validateLayout()) {
            throw new IllegalArgumentException("invalid Opus channel mapping");
        }
        this.Fs = Fs;
        this.decoders = new OpusStreamDecoder[streams];
        for (var i = 0; i < streams; i++) {
            decoders[i] = new OpusStreamDecoder(Fs, i < coupledStreams ? 2 : 1);
        }
        this.buf = new float[2 * (Fs / 25 * 3)];
    }

    /**
     * Checks that every mapping entry names an existing decoded channel or is silent
     * ({@code validate_layout}).
     *
     * @return whether the layout is valid
     */
    private boolean validateLayout() {
        var max_channel = nb_streams + nb_coupled_streams;
        if (max_channel > 255) {
            return false;
        }
        for (var i = 0; i < nb_channels; i++) {
            if (mapping[i] >= max_channel && mapping[i] != 255) {
                return false;
            }
        }
        return true;
    }

    /**
     * Returns the next output channel fed by the left channel of a coupled stream
     * ({@code get_left_channel}).
     *
     * @param stream_id the stream index
     * @param prev      the previous channel found, or {@code -1}
     * @return the output channel, or {@code -1} when there are no more
     */
    private int getLeftChannel(int stream_id, int prev) {
        for (var i = prev < 0 ? 0 : prev + 1; i < nb_channels; i++) {
            if (mapping[i] == stream_id * 2) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Returns the next output channel fed by the right channel of a coupled stream
     * ({@code get_right_channel}).
     *
     * @param stream_id the stream index
     * @param prev      the previous channel found, or {@code -1}
     * @return the output channel, or {@code -1} when there are no more
     */
    private int getRightChannel(int stream_id, int prev) {
        for (var i = prev < 0 ? 0 : prev + 1; i < nb_channels; i++) {
            if (mapping[i] == stream_id * 2 + 1) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Returns the next output channel fed by a mono stream ({@code get_mono_channel}).
     *
     * @param stream_id the stream index
     * @param prev      the previous channel found, or {@code -1}
     * @return the output channel, or {@code -1} when there are no more
     */
    private int getMonoChannel(int stream_id, int prev) {
        for (var i = prev < 0 ? 0 : prev + 1; i < nb_channels; i++) {
            if (mapping[i] == stream_id + nb_coupled_streams) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Returns the output sampling rate.
     *
     * @return the rate in hertz
     */
    public int sampleRate() {
        return Fs;
    }

    /**
     * Returns the largest number of samples per channel one packet can decode to (120 ms).
     *
     * @return the sample count
     */
    public int maxFrameSize() {
        return Fs / 25 * 3;
    }

    /**
     * Sets the output gain of every stream ({@code OPUS_SET_GAIN}).
     *
     * @param gain the gain in Q8 dB, between -32768 and 32767
     */
    public void setGain(int gain) {
        for (var decoder : decoders) {
            decoder.setGain(gain);
        }
    }

    /**
     * Returns the number of elementary streams.
     *
     * @return the stream count, at least 1
     */
    public int streamCount() {
        return nb_streams;
    }

    /**
     * Returns the decoder of one elementary stream, which shares its state with this decoder.
     *
     * @param index the stream index, from 0 to {@link #streamCount()} exclusive
     * @return the stream decoder
     * @throws ArrayIndexOutOfBoundsException if {@code index} is out of range
     */
    public OpusStreamDecoder stream(int index) {
        return decoders[index];
    }

    /**
     * Resets every stream decoder ({@code OPUS_RESET_STATE}).
     */
    public void reset() {
        for (var decoder : decoders) {
            decoder.reset();
        }
    }

    /**
     * Returns the final range of the last packet, the XOR of the streams' final ranges.
     *
     * @return the unsigned 32-bit final range
     */
    public int finalRange() {
        var value = 0;
        for (var decoder : decoders) {
            value ^= decoder.finalRange();
        }
        return value;
    }

    /**
     * Returns the number of samples per channel the last call produced.
     *
     * @return the sample count
     */
    public int lastPacketDuration() {
        return decoders[0].lastPacketDuration();
    }

    /**
     * Checks that every stream of a multistream packet parses and that all streams have the
     * same duration ({@code opus_multistream_packet_validate}).
     *
     * @param data the buffer holding the packet
     * @param off  the index of the first byte
     * @param len  the packet length
     * @return the duration in samples, or a negative error code
     */
    private int packetValidate(byte[] data, int off, int len) {
        var samples = 0;
        for (var s = 0; s < nb_streams; s++) {
            if (len <= 0) {
                return OPUS_INVALID_PACKET;
            }
            var count = OpusPacket.parseImpl(data, off, len, s != nb_streams - 1, layout);
            if (count < 0) {
                return count;
            }
            var tmp_samples = OpusPacket.getNbSamples(data, off, layout.packetOffset, Fs);
            if (s != 0 && samples != tmp_samples) {
                return OPUS_INVALID_PACKET;
            }
            samples = tmp_samples;
            off += layout.packetOffset;
            len -= layout.packetOffset;
        }
        return samples;
    }

    /**
     * Decodes one multistream packet, or conceals {@code frameSize} samples when {@code len} is
     * zero, into planar output ({@code opus_multistream_decode_native} with a float channel
     * copier).
     *
     * @param data      the buffer holding the packet
     * @param off       the index of the first byte
     * @param len       the packet length, or {@code 0} to conceal
     * @param out       the output planes, indexed by {@code route}
     * @param outOff    the index of the first output sample in every plane
     * @param route     maps each output channel of the stream layout to a plane of {@code out}
     * @param frameSize the number of samples per channel available; when concealing, the number
     *                  to produce, a multiple of 2.5 ms
     * @param softClip  whether to soft-clip the output of each stream
     * @return the number of samples per channel produced, or a negative error code
     */
    public int decode(byte[] data, int off, int len, float[][] out, int outOff, int[] route, int frameSize, boolean softClip) {
        if (frameSize <= 0) {
            return OPUS_BAD_ARG;
        }
        frameSize = Math.min(frameSize, Fs / 25 * 3);
        var do_plc = len == 0;
        if (len < 0) {
            return OPUS_BAD_ARG;
        }
        if (!do_plc && len < 2 * nb_streams - 1) {
            return OPUS_INVALID_PACKET;
        }
        if (!do_plc) {
            var ret = packetValidate(data, off, len);
            if (ret < 0) {
                return ret;
            } else if (ret > frameSize) {
                return OPUS_BUFFER_TOO_SMALL;
            }
        }
        for (var s = 0; s < nb_streams; s++) {
            var dec = decoders[s];
            if (!do_plc && len <= 0) {
                return OPUS_INTERNAL_ERROR;
            }
            packetOffset[0] = 0;
            var ret = dec.decodeNative(do_plc ? null : data, off, len, buf, 0, frameSize, false,
                    s != nb_streams - 1, packetOffset, softClip);
            if (!do_plc) {
                off += packetOffset[0];
                len -= packetOffset[0];
            }
            if (ret <= 0) {
                return ret;
            }
            frameSize = ret;
            if (s < nb_coupled_streams) {
                var prev = -1;
                int chan;
                while ((chan = getLeftChannel(s, prev)) != -1) {
                    copyChannelOut(out[route[chan]], outOff, buf, 0, 2, frameSize);
                    prev = chan;
                }
                prev = -1;
                while ((chan = getRightChannel(s, prev)) != -1) {
                    copyChannelOut(out[route[chan]], outOff, buf, 1, 2, frameSize);
                    prev = chan;
                }
            } else {
                var prev = -1;
                int chan;
                while ((chan = getMonoChannel(s, prev)) != -1) {
                    copyChannelOut(out[route[chan]], outOff, buf, 0, 1, frameSize);
                    prev = chan;
                }
            }
        }
        for (var c = 0; c < nb_channels; c++) {
            if (mapping[c] == 255) {
                var plane = out[route[c]];
                for (var i = 0; i < frameSize; i++) {
                    plane[outOff + i] = 0;
                }
            }
        }
        return frameSize;
    }

    /**
     * Copies one decoded channel into an output plane ({@code opus_copy_channel_out_float}).
     *
     * @param dst       the output plane
     * @param dstOff    the index of the first output sample
     * @param src       the interleaved decoded samples
     * @param srcOff    the index of the channel's first sample
     * @param srcStride the number of interleaved channels
     * @param frameSize the number of samples
     */
    private static void copyChannelOut(float[] dst, int dstOff, float[] src, int srcOff, int srcStride, int frameSize) {
        for (var i = 0; i < frameSize; i++) {
            dst[dstOff + i] = src[srcOff + i * srcStride];
        }
    }
}
