/* Ports libopus 1.5.2 (src/opus_decoder.c). Copyright Xiph.Org Foundation and Skype Limited; BSD 3-Clause License.
   Modified for Smalt. */
package com.github.auties00.smalt.audio.opus.internal;

import com.github.auties00.smalt.audio.opus.internal.celt.CeltDecoder;
import com.github.auties00.smalt.audio.opus.internal.celt.MathOps;
import com.github.auties00.smalt.audio.opus.internal.celt.RangeDecoder;
import com.github.auties00.smalt.audio.opus.internal.silk.SilkDecControl;
import com.github.auties00.smalt.audio.opus.internal.silk.SilkDecoder;

import static com.github.auties00.smalt.audio.opus.internal.OpusDefines.*;

/**
 * Decodes one Opus elementary stream: the SILK, CELT and hybrid frames of RFC 6716, the
 * transitions between them, and packet-loss concealment.
 *
 * <p>The decoder runs natively at 8, 12, 16, 24 or 48 kHz with one or two output channels and
 * produces interleaved float samples nominally in {@code [-1, 1]}. It never throws on packet
 * content: an invalid packet yields a negative {@code OPUS_*} error code.
 *
 * @implNote This implementation omits the deep PLC, DRED and OSCE extensions and allocates the stack buffers of
 * {@code opus_decode_frame} once per decoder; the single level of recursion {@code opus_decode_frame} performs for mode
 * transitions never uses a buffer its caller holds.
 */
public final class OpusStreamDecoder {
    /**
     * The number of output channels.
     */
    final int channels;

    /**
     * The output sampling rate in hertz.
     */
    final int Fs;

    /**
     * The control structure shared with the SILK decoder.
     */
    private final SilkDecControl DecControl = new SilkDecControl();

    /**
     * The output gain in Q8 dB.
     */
    private int decode_gain;

    /**
     * The SILK decoder.
     */
    private final SilkDecoder silk_dec;

    /**
     * The CELT decoder.
     */
    private final CeltDecoder celt_dec;

    /**
     * The number of coded channels of the current packet.
     */
    private int stream_channels;

    /**
     * The audio bandwidth of the current packet.
     */
    private int bandwidth;

    /**
     * The coding mode of the current packet.
     */
    private int mode;

    /**
     * The coding mode of the previous frame, or {@code 0} before the first frame.
     */
    private int prev_mode;

    /**
     * The frame size of the current packet in samples.
     */
    private int frame_size;

    /**
     * Whether the previous frame ended with a SILK-to-CELT redundancy frame.
     */
    private boolean prev_redundancy;

    /**
     * The number of samples produced by the last decode call.
     */
    private int last_packet_duration;

    /**
     * The soft-clipping continuation state of each channel.
     */
    private final float[] softclip_mem = new float[2];

    /**
     * The final range of the last decoded packet, unsigned.
     */
    private int rangeFinal;

    /**
     * The range decoder shared by SILK and CELT within a frame.
     */
    private final RangeDecoder dec = new RangeDecoder();

    /**
     * The SILK output of a frame ({@code pcm_silk}).
     */
    private final short[] pcm_silk;

    /**
     * The concealed audio used to cross-fade a mode transition ({@code pcm_transition}).
     */
    private final float[] pcm_transition;

    /**
     * The decoded 5 ms redundancy frame ({@code redundant_audio}).
     */
    private final float[] redundant_audio;

    /**
     * The number of samples of SILK output reported by one SILK decode call.
     */
    private final int[] silk_frame_size = new int[1];

    /**
     * Creates a decoder ({@code opus_decoder_init}).
     *
     * @param Fs       the output sampling rate: 8000, 12000, 16000, 24000 or 48000
     * @param channels the number of output channels: 1 or 2
     * @throws IllegalArgumentException if a parameter is out of range
     */
    public OpusStreamDecoder(int Fs, int channels) {
        if ((Fs != 48000 && Fs != 24000 && Fs != 16000 && Fs != 12000 && Fs != 8000)
                || (channels != 1 && channels != 2)) {
            throw new IllegalArgumentException("invalid Opus decoder configuration: " + Fs + " Hz, " + channels + " channels");
        }
        this.stream_channels = this.channels = channels;
        this.Fs = Fs;
        DecControl.API_sampleRate = Fs;
        DecControl.nChannelsAPI = channels;
        silk_dec = new SilkDecoder();
        celt_dec = new CeltDecoder(Fs, channels);
        prev_mode = 0;
        frame_size = Fs / 400;
        var maxFrame = Fs / 25 * 3;
        pcm_silk = new short[maxFrame * channels];
        pcm_transition = new float[Fs / 200 * channels];
        redundant_audio = new float[Fs / 200 * channels];
        pcm_int16 = new float[maxFrame * channels];
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
     * Returns the number of output channels.
     *
     * @return the channel count
     */
    public int channels() {
        return channels;
    }

    /**
     * Sets the output gain ({@code OPUS_SET_GAIN}).
     *
     * @param value the gain in Q8 dB, between -32768 and 32767
     * @throws IllegalArgumentException if the value is out of range
     */
    public void setGain(int value) {
        if (value < -32768 || value > 32767) {
            throw new IllegalArgumentException("gain out of range: " + value);
        }
        decode_gain = value;
    }

    /**
     * Returns the final range of the last decoded packet ({@code OPUS_GET_FINAL_RANGE}).
     *
     * @return the unsigned 32-bit final range
     */
    public int finalRange() {
        return rangeFinal;
    }

    /**
     * Returns the number of samples the last decode call produced
     * ({@code OPUS_GET_LAST_PACKET_DURATION}).
     *
     * @return the sample count per channel
     */
    public int lastPacketDuration() {
        return last_packet_duration;
    }

    /**
     * Returns the pitch period of the last decoded frame ({@code OPUS_GET_PITCH}).
     *
     * @return the pitch in samples at 48 kHz
     */
    public int pitch() {
        if (prev_mode == MODE_CELT_ONLY) {
            return celt_dec.getPitch();
        }
        return DecControl.prevPitchLag;
    }

    /**
     * Resets the decoder to its freshly created state, keeping the configuration
     * ({@code OPUS_RESET_STATE}).
     */
    public void reset() {
        stream_channels = channels;
        bandwidth = 0;
        mode = 0;
        prev_mode = 0;
        frame_size = Fs / 400;
        prev_redundancy = false;
        last_packet_duration = 0;
        softclip_mem[0] = 0;
        softclip_mem[1] = 0;
        rangeFinal = 0;
        celt_dec.resetState();
        silk_dec.resetDecoder();
    }

    /**
     * Cross-fades two signals with the squared CELT window ({@code smooth_fade}).
     *
     * @param in1      the fading-out signal
     * @param in1Off   the index of its first sample
     * @param in2      the fading-in signal
     * @param in2Off   the index of its first sample
     * @param out      receives the cross-fade, may alias an input
     * @param outOff   the index of the first output sample
     * @param overlap  the number of samples per channel
     * @param channels the number of interleaved channels
     * @param window   the CELT window at 48 kHz
     * @param Fs       the sampling rate of the signals
     */
    private static void smoothFade(float[] in1, int in1Off, float[] in2, int in2Off, float[] out, int outOff,
                                   int overlap, int channels, float[] window, int Fs) {
        var inc = 48000 / Fs;
        for (var c = 0; c < channels; c++) {
            for (var i = 0; i < overlap; i++) {
                var w = window[i * inc] * window[i * inc];
                out[outOff + i * channels + c] = w * in2[in2Off + i * channels + c]
                        + (1.0f - w) * in1[in1Off + i * channels + c];
            }
        }
    }

    /**
     * Decodes one frame, or conceals one when {@code data} is {@code null}
     * ({@code opus_decode_frame}).
     *
     * @param data       the buffer holding the frame payload, or {@code null} to conceal
     * @param dataOff    the index of the first payload byte
     * @param len        the payload length
     * @param pcm        receives the interleaved samples
     * @param pcmOff     the index of the first output sample
     * @param frameSize  the number of samples per channel available in {@code pcm}
     * @param decodeFec  whether to decode the in-band FEC of the frame instead of the frame
     * @return the number of samples per channel produced, or a negative error code
     */
    private int decodeFrame(byte[] data, int dataOff, int len, float[] pcm, int pcmOff, int frameSize, boolean decodeFec) {
        var silk_ret = 0;
        var celt_ret = 0;
        var F20 = Fs / 50;
        var F10 = F20 >> 1;
        var F5 = F10 >> 1;
        var F2_5 = F5 >> 1;
        var transition = false;
        var redundancy = false;
        var redundancy_bytes = 0;
        var celt_to_silk = false;
        var redundant_rng = 0;
        if (frameSize < F2_5) {
            return OPUS_BUFFER_TOO_SMALL;
        }
        frameSize = Math.min(frameSize, Fs / 25 * 3);
        if (len <= 1) {
            data = null;
            frameSize = Math.min(frameSize, frame_size);
        }
        int audiosize;
        int mode;
        int bandwidth;
        if (data != null) {
            audiosize = frame_size;
            mode = this.mode;
            bandwidth = this.bandwidth;
            dec.init(data, dataOff, len);
        } else {
            audiosize = frameSize;
            mode = prev_redundancy ? MODE_CELT_ONLY : prev_mode;
            bandwidth = 0;
            if (mode == 0) {
                for (var i = 0; i < audiosize * channels; i++) {
                    pcm[pcmOff + i] = 0;
                }
                return audiosize;
            }
            if (audiosize > F20) {
                do {
                    var ret = decodeFrame(null, 0, 0, pcm, pcmOff, Math.min(audiosize, F20), false);
                    if (ret < 0) {
                        return ret;
                    }
                    pcmOff += ret * channels;
                    audiosize -= ret;
                } while (audiosize > 0);
                return frameSize;
            } else if (audiosize < F20) {
                if (audiosize > F10) {
                    audiosize = F10;
                } else if (mode != MODE_SILK_ONLY && audiosize > F5 && audiosize < F10) {
                    audiosize = F5;
                }
            }
        }
        if (data != null && prev_mode > 0 && (
                (mode == MODE_CELT_ONLY && prev_mode != MODE_CELT_ONLY && !prev_redundancy)
                        || (mode != MODE_CELT_ONLY && prev_mode == MODE_CELT_ONLY))) {
            transition = true;
        }
        if (transition && mode == MODE_CELT_ONLY) {
            decodeFrame(null, 0, 0, pcm_transition, 0, Math.min(F5, audiosize), false);
        }
        if (audiosize > frameSize) {
            return OPUS_BAD_ARG;
        } else {
            frameSize = audiosize;
        }
        if (mode != MODE_CELT_ONLY) {
            if (prev_mode == MODE_CELT_ONLY) {
                silk_dec.resetDecoder();
            }
            DecControl.payloadSize_ms = Math.max(10, 1000 * audiosize / Fs);
            if (data != null) {
                DecControl.nChannelsInternal = stream_channels;
                if (mode == MODE_SILK_ONLY) {
                    if (bandwidth == OPUS_BANDWIDTH_NARROWBAND) {
                        DecControl.internalSampleRate = 8000;
                    } else if (bandwidth == OPUS_BANDWIDTH_MEDIUMBAND) {
                        DecControl.internalSampleRate = 12000;
                    } else {
                        DecControl.internalSampleRate = 16000;
                    }
                } else {
                    DecControl.internalSampleRate = 16000;
                }
            }
            var lost_flag = data == null ? 1 : 2 * (decodeFec ? 1 : 0);
            var decoded_samples = 0;
            var pcmPtr = 0;
            do {
                var first_frame = decoded_samples == 0 ? 1 : 0;
                silk_ret = silk_dec.decode(DecControl, lost_flag, first_frame, dec, pcm_silk, pcmPtr, silk_frame_size);
                if (silk_ret != 0) {
                    if (lost_flag != 0) {
                        silk_frame_size[0] = frameSize;
                        for (var i = 0; i < frameSize * channels; i++) {
                            pcm_silk[pcmPtr + i] = 0;
                        }
                    } else {
                        return OPUS_INTERNAL_ERROR;
                    }
                }
                pcmPtr += silk_frame_size[0] * channels;
                decoded_samples += silk_frame_size[0];
            } while (decoded_samples < frameSize);
        }
        var start_band = 0;
        if (!decodeFec && mode != MODE_CELT_ONLY && data != null
                && dec.tell() + 17 + 20 * (mode == MODE_HYBRID ? 1 : 0) <= 8 * len) {
            if (mode == MODE_HYBRID) {
                redundancy = dec.decBitLogp(12) != 0;
            } else {
                redundancy = true;
            }
            if (redundancy) {
                celt_to_silk = dec.decBitLogp(1) != 0;
                redundancy_bytes = mode == MODE_HYBRID
                        ? dec.decUint(256) + 2
                        : len - ((dec.tell() + 7) >> 3);
                len -= redundancy_bytes;
                if (len * 8 < dec.tell()) {
                    len = 0;
                    redundancy_bytes = 0;
                    redundancy = false;
                }
                dec.storage -= redundancy_bytes;
            }
        }
        if (mode != MODE_CELT_ONLY) {
            start_band = 17;
        }
        if (redundancy) {
            transition = false;
        }
        if (transition && mode != MODE_CELT_ONLY) {
            decodeFrame(null, 0, 0, pcm_transition, 0, Math.min(F5, audiosize), false);
        }
        if (bandwidth != 0) {
            var endband = switch (bandwidth) {
                case OPUS_BANDWIDTH_NARROWBAND -> 13;
                case OPUS_BANDWIDTH_MEDIUMBAND, OPUS_BANDWIDTH_WIDEBAND -> 17;
                case OPUS_BANDWIDTH_SUPERWIDEBAND -> 19;
                default -> 21;
            };
            celt_dec.setEndBand(endband);
        }
        celt_dec.setChannels(stream_channels);
        if (redundancy && celt_to_silk) {
            celt_dec.setStartBand(0);
            celt_dec.decodeWithEc(data, dataOff + len, redundancy_bytes, redundant_audio, 0, F5, null, 0);
            redundant_rng = celt_dec.getFinalRange();
        }
        celt_dec.setStartBand(start_band);
        if (mode != MODE_SILK_ONLY) {
            var celt_frame_size = Math.min(F20, frameSize);
            if (mode != prev_mode && prev_mode > 0 && !prev_redundancy) {
                celt_dec.resetState();
            }
            celt_ret = celt_dec.decodeWithEc(decodeFec ? null : data, dataOff, len, pcm, pcmOff, celt_frame_size, dec, 0);
        } else {
            for (var i = 0; i < frameSize * channels; i++) {
                pcm[pcmOff + i] = 0;
            }
            if (prev_mode == MODE_HYBRID && !(redundancy && celt_to_silk && prev_redundancy)) {
                celt_dec.setStartBand(0);
                celt_dec.decodeWithEc(SILENCE, 0, 2, pcm, pcmOff, F2_5, null, 0);
            }
        }
        if (mode != MODE_CELT_ONLY) {
            for (var i = 0; i < frameSize * channels; i++) {
                pcm[pcmOff + i] = pcm[pcmOff + i] + (1.f / 32768.f) * pcm_silk[i];
            }
        }
        var window = celt_dec.getMode().window;
        if (redundancy && !celt_to_silk) {
            celt_dec.resetState();
            celt_dec.setStartBand(0);
            celt_dec.decodeWithEc(data, dataOff + len, redundancy_bytes, redundant_audio, 0, F5, null, 0);
            redundant_rng = celt_dec.getFinalRange();
            smoothFade(pcm, pcmOff + channels * (frameSize - F2_5), redundant_audio, channels * F2_5,
                    pcm, pcmOff + channels * (frameSize - F2_5), F2_5, channels, window, Fs);
        }
        if (redundancy && celt_to_silk && (prev_mode != MODE_SILK_ONLY || prev_redundancy)) {
            for (var c = 0; c < channels; c++) {
                for (var i = 0; i < F2_5; i++) {
                    pcm[pcmOff + channels * i + c] = redundant_audio[channels * i + c];
                }
            }
            smoothFade(redundant_audio, channels * F2_5, pcm, pcmOff + channels * F2_5,
                    pcm, pcmOff + channels * F2_5, F2_5, channels, window, Fs);
        }
        if (transition) {
            if (audiosize >= F5) {
                for (var i = 0; i < channels * F2_5; i++) {
                    pcm[pcmOff + i] = pcm_transition[i];
                }
                smoothFade(pcm_transition, channels * F2_5, pcm, pcmOff + channels * F2_5,
                        pcm, pcmOff + channels * F2_5, F2_5, channels, window, Fs);
            } else {
                smoothFade(pcm_transition, 0, pcm, pcmOff, pcm, pcmOff, F2_5, channels, window, Fs);
            }
        }
        if (decode_gain != 0) {
            var gain = MathOps.celtExp2(6.48814081e-4f * decode_gain);
            for (var i = 0; i < frameSize * channels; i++) {
                pcm[pcmOff + i] = pcm[pcmOff + i] * gain;
            }
        }
        if (len <= 1) {
            rangeFinal = 0;
        } else {
            rangeFinal = dec.rng ^ redundant_rng;
        }
        prev_mode = mode;
        prev_redundancy = redundancy && !celt_to_silk;
        return celt_ret < 0 ? celt_ret : audiosize;
    }

    /**
     * The two-byte CELT frame that decodes to silence, used to fade out the CELT overlap on a
     * hybrid-to-SILK transition.
     */
    private static final byte[] SILENCE = {(byte) 0xFF, (byte) 0xFF};

    /**
     * Decodes one packet, or conceals {@code frameSize} samples when {@code len} is zero
     * ({@code opus_decode_native}).
     *
     * @param data          the buffer holding the packet, or {@code null} to conceal
     * @param dataOff       the index of the table-of-contents byte
     * @param len           the packet length, or {@code 0} to conceal
     * @param pcm           receives the interleaved samples
     * @param pcmOff        the index of the first output sample
     * @param frameSize     the number of samples per channel available in {@code pcm}; when
     *                      concealing, the number to produce, a multiple of 2.5 ms
     * @param decodeFec     whether to decode the in-band FEC data instead of the packet
     * @param selfDelimited whether the packet uses self-delimited framing
     * @param packetOffset  receives the number of bytes the packet occupies at index 0, or
     *                      {@code null}
     * @param softClip      whether to soft-clip the output
     * @return the number of samples per channel produced, or a negative error code
     */
    public int decodeNative(byte[] data, int dataOff, int len, float[] pcm, int pcmOff, int frameSize,
                            boolean decodeFec, boolean selfDelimited, int[] packetOffset, boolean softClip) {
        if ((decodeFec || len == 0 || data == null) && frameSize % (Fs / 400) != 0) {
            return OPUS_BAD_ARG;
        }
        if (len == 0 || data == null) {
            var pcm_count = 0;
            do {
                var ret = decodeFrame(null, 0, 0, pcm, pcmOff + pcm_count * channels, frameSize - pcm_count, false);
                if (ret < 0) {
                    return ret;
                }
                pcm_count += ret;
            } while (pcm_count < frameSize);
            last_packet_duration = pcm_count;
            return pcm_count;
        } else if (len < 0) {
            return OPUS_BAD_ARG;
        }
        var toc0 = data[dataOff] & 0xFF;
        var packet_mode = OpusPacket.getMode(toc0);
        var packet_bandwidth = OpusPacket.getBandwidth(toc0);
        var packet_frame_size = OpusPacket.getSamplesPerFrame(toc0, Fs);
        var packet_stream_channels = OpusPacket.getNbChannels(toc0);
        var layout = this.layout;
        var count = OpusPacket.parseImpl(data, dataOff, len, selfDelimited, layout);
        if (count < 0) {
            return count;
        }
        if (packetOffset != null) {
            packetOffset[0] = layout.packetOffset;
        }
        if (decodeFec) {
            if (frameSize < packet_frame_size || packet_mode == MODE_CELT_ONLY || this.mode == MODE_CELT_ONLY) {
                return decodeNative(null, 0, 0, pcm, pcmOff, frameSize, false, false, null, softClip);
            }
            var duration_copy = last_packet_duration;
            if (frameSize - packet_frame_size != 0) {
                var ret = decodeNative(null, 0, 0, pcm, pcmOff, frameSize - packet_frame_size, false, false, null, softClip);
                if (ret < 0) {
                    last_packet_duration = duration_copy;
                    return ret;
                }
            }
            this.mode = packet_mode;
            this.bandwidth = packet_bandwidth;
            this.frame_size = packet_frame_size;
            this.stream_channels = packet_stream_channels;
            var ret = decodeFrame(data, dataOff + layout.frames[0], layout.size[0],
                    pcm, pcmOff + channels * (frameSize - packet_frame_size), packet_frame_size, true);
            if (ret < 0) {
                return ret;
            }
            last_packet_duration = frameSize;
            return frameSize;
        }
        if (count * packet_frame_size > frameSize) {
            return OPUS_BUFFER_TOO_SMALL;
        }
        this.mode = packet_mode;
        this.bandwidth = packet_bandwidth;
        this.frame_size = packet_frame_size;
        this.stream_channels = packet_stream_channels;
        var nb_samples = 0;
        for (var i = 0; i < count; i++) {
            var ret = decodeFrame(data, dataOff + layout.frames[i], layout.size[i], pcm,
                    pcmOff + nb_samples * channels, frameSize - nb_samples, false);
            if (ret < 0) {
                return ret;
            }
            nb_samples += ret;
        }
        last_packet_duration = nb_samples;
        if (softClip) {
            OpusPacket.softClip(pcm, pcmOff, nb_samples, channels, softclip_mem);
        } else {
            softclip_mem[0] = 0;
            softclip_mem[1] = 0;
        }
        return nb_samples;
    }

    /**
     * Decodes one packet into 16-bit samples, or conceals when {@code len} is zero
     * ({@code opus_decode}).
     *
     * <p>For a packet decoded normally {@code frameSize} is the capacity of {@code pcm}: it is first
     * lowered to the packet's own duration, so a packet that fits decodes whole and a longer one
     * fails with {@link OpusDefines#OPUS_BUFFER_TOO_SMALL}. For a concealment or a forward error
     * correction decode it is the number of samples to produce, a multiple of 2.5 ms. The output is
     * soft-clipped and rounded to 16 bits.
     *
     * @param data      the buffer holding the packet, or {@code null} to conceal
     * @param dataOff   the index of the table-of-contents byte
     * @param len       the packet length, or {@code 0} to conceal
     * @param pcm       receives the interleaved samples
     * @param pcmOff    the index of the first output sample
     * @param frameSize the capacity of {@code pcm} in samples per channel, or the number of samples
     *                  to conceal or recover
     * @param decodeFec whether to decode the in-band FEC data instead of the packet
     * @return the number of samples per channel produced, or a negative error code
     */
    public int decode(byte[] data, int dataOff, int len, short[] pcm, int pcmOff, int frameSize, boolean decodeFec) {
        if (frameSize <= 0) {
            return OPUS_BAD_ARG;
        }
        if (data != null && len > 0 && !decodeFec) {
            var nb_samples = OpusPacket.getNbSamples(data, dataOff, len, Fs);
            if (nb_samples > 0) {
                frameSize = Math.min(frameSize, nb_samples);
            } else {
                return OPUS_INVALID_PACKET;
            }
        }
        var need = frameSize * channels;
        if (pcm_int16.length < need) {
            pcm_int16 = new float[need];
        }
        var out = pcm_int16;
        var ret = decodeNative(data, dataOff, len, out, 0, frameSize, decodeFec, false, null, true);
        if (ret > 0) {
            var count = ret * channels;
            for (var i = 0; i < count; i++) {
                pcm[pcmOff + i] = MathOps.float2int16(out[i]);
            }
        }
        return ret;
    }

    /**
     * The float output of {@link #decode(byte[], int, int, short[], int, int, boolean)} before it
     * is rounded to 16 bits ({@code out} of {@code opus_decode}); sized for 120 ms and grown when a
     * longer concealment is requested.
     */
    private float[] pcm_int16;

    /**
     * The frame layout of the packet being decoded.
     */
    private final OpusPacket.Layout layout = new OpusPacket.Layout();
}
