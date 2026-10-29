package com.github.auties00.smalt.audio.aac;

import com.github.auties00.smalt.audio.AudioBuffer;
import com.github.auties00.smalt.audio.AudioEncoder;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.audio.aac.internal.AacKernels;
import com.github.auties00.smalt.audio.aac.internal.AudioSpecificConfig;
import com.github.auties00.smalt.audio.aac.internal.EncoderEngine;
import com.github.auties00.smalt.audio.aac.internal.LowDelayAnalysis;
import com.github.auties00.smalt.audio.aac.internal.sbr.SbrEncoder;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Encodes planar float samples into MPEG-4 AAC packets: AAC Low Complexity, HE-AAC, HE-AAC v2, AAC-LD or AAC-ELD,
 * as {@link AacEncoderOptions#profile()} selects.
 *
 * <p>Each packet is one {@code raw_data_block} (without an ADTS header) of {@link #frameSize()} samples
 * per channel, and {@link #codecConfig()} is the {@code AudioSpecificConfig} a container stores. Packet
 * timestamps count samples from the first input sample; decoders output {@link #delay()} priming samples
 * before the first input sample, which the container signals, for example with an MP4 edit list. The
 * encoder buffers input of any length and emits a packet whenever a frame and its look-ahead are complete;
 * {@link #flush(Consumer)} pads the input with silence and emits packets until every input sample is
 * covered.
 *
 * <p>HE-AAC codes an AAC Low Complexity core at half the sampling rate, with frames of
 * {@link AacEncoderOptions#frameLength()} core samples, so each packet covers twice as many input samples;
 * the band from the core's crossover to the stop frequency
 * ({@link AacEncoderOptions#effectiveBandwidth()}) is coded as spectral band replication data in a fill
 * element after each single channel and channel pair element. LFE channels have no spectral band replication.
 * HE-AAC v2 codes a stereo input as a mono core and its spectral band replication, with parametric stereo
 * data carrying the intensity differences and coherences of 20 stereo bands (10 below 21 kbit/s). The
 * {@code AudioSpecificConfig} signals the extensions as {@link AacEncoderOptions#signaling()} selects; an
 * ADTS muxer writes the core's configuration, the implicit form. A decoder without spectral band
 * replication plays the core of an implicitly or backward-compatibly signaled stream at half the rate.
 *
 * <p>AAC-LD and AAC-ELD code frames of 512 or 480 samples with long windows only and no look-ahead, in the error
 * resilient syntax without resilience tools or error protection (no element identifiers; constant bitrate frames
 * end in zero padding instead of fill elements). AAC-LD uses the sine window, AAC-ELD the low delay filterbank of
 * ISO/IEC 14496-3 subclause 4.6.20, without low delay spectral band replication. Neither has an ADTS form.
 *
 * <p>The layout is coded as channel configuration {@link AacEncoderOptions#channelConfiguration()}:
 * the configuration's channel elements in their ISO/IEC 14496-3 order or, for configuration 0, the
 * elements of a program config element that the {@code AudioSpecificConfig} carries. Each input
 * channel is coded on the element that feeds its loudspeaker, so a decoder outputs the same
 * channels in WAVE order
 * ({@link com.github.auties00.smalt.audio.Speaker} declaration order). LFE channels are coded with
 * long windows only, band-limited to 240 Hz. ADTS headers carry only channel configurations 1 to 7
 * and 1024-sample frames; the other streams need a container that stores the
 * {@code AudioSpecificConfig}, such as MP4 or Matroska.
 *
 * <p>Coding tools: window switching between long and eight short windows (grouped) driven by a
 * transient detector, sine and Kaiser-Bessel-derived windows, a psychoacoustic model with
 * spreading, threshold in quiet and pre-echo control, TNS on transients and tonal-in-time
 * spectra, per-band M/S stereo, scalefactors refined against the measured quantization error,
 * sections chosen by dynamic programming, and a bit reservoir in constant bitrate mode. Perceptual
 * noise substitution and intensity stereo are not used. Input samples outside {@code [-1, 1]}
 * are clipped; NaN samples are coded as silence.
 *
 * <p>Instances are not thread-safe.
 */
public final class AacEncoder implements AudioEncoder {
    /**
     * The options in use: those the encoder was created with, with the bitrate of the last
     * {@link #setTargetBitrate(long)}.
     */
    private AacEncoderOptions options;

    /**
     * The number of input samples per channel of one packet.
     */
    private final int frameLength;

    /**
     * The encoding engine of AAC Low Complexity, or {@code null} for HE-AAC.
     */
    private final EncoderEngine engine;

    /**
     * The encoder of HE-AAC, or {@code null} for AAC Low Complexity.
     */
    private final SbrEncoder sbr;

    /**
     * The {@code AudioSpecificConfig}.
     */
    private final byte[] audioSpecificConfig;

    /**
     * The number of input samples per channel received.
     */
    private long received;

    /**
     * Whether {@link #flush(Consumer)} ran.
     */
    private boolean flushed;

    /**
     * Constructs an encoder under {@link AacEncoderOptions#defaults()}: 48 kHz stereo at 128 kbit/s,
     * constant bitrate.
     */
    public AacEncoder() {
        this(AacEncoderOptions.defaults());
    }

    /**
     * Constructs an encoder under explicit options.
     *
     * @param options the format, bitrate and bandwidth
     */
    public AacEncoder(AacEncoderOptions options) {
        this.options = Objects.requireNonNull(options, "options");
        var layout = options.coding();
        var vbr = options.bitrateMode() == AacBitrateMode.VBR;
        if (options.profile() == AacProfile.LC) {
            this.frameLength = options.frameLength();
            this.engine = new EncoderEngine(options.sampleRate(), frameLength, layout, options.bitrate(), vbr,
                    options.effectiveBandwidth(), AacKernels.INSTANCE);
            this.sbr = null;
            this.audioSpecificConfig = AudioSpecificConfig.lowComplexity(options.sampleRate(),
                    layout.channelConfiguration(), frameLength, layout.programConfig());
        } else if (options.profile() == AacProfile.LD || options.profile() == AacProfile.ELD) {
            var objectType = options.profile() == AacProfile.LD ? AudioSpecificConfig.AOT_ER_LD
                    : AudioSpecificConfig.AOT_ER_ELD;
            this.frameLength = options.frameLength() / 2;
            this.engine = new EncoderEngine(options.sampleRate(), frameLength, layout, options.bitrate(),
                    options.bitrate(), vbr, options.effectiveBandwidth(), objectType, AacKernels.INSTANCE);
            this.sbr = null;
            // TODO: low delay SBR for AAC-ELD (ldSbrPresentFlag): a CLDFB analysis of the input, the LD time grid
            this.audioSpecificConfig = AudioSpecificConfig.lowDelay(objectType, options.sampleRate(),
                    layout.channelConfiguration(), frameLength);
        } else {
            var ps = options.profile() == AacProfile.HE_V2;
            this.frameLength = 2 * options.frameLength();
            this.engine = null;
            this.sbr = new SbrEncoder(options.sampleRate(), options.frameLength(), layout, options.bitrate(), vbr,
                    ps, AacKernels.INSTANCE);
            var signaling = switch (options.signaling()) {
                case IMPLICIT -> 0;
                case BACKWARD_COMPATIBLE -> 1;
                case HIERARCHICAL -> 2;
            };
            this.audioSpecificConfig = AudioSpecificConfig.highEfficiency(options.sampleRate() / 2,
                    options.sampleRate(), ps ? 1 : layout.channelConfiguration(), options.frameLength(),
                    ps ? null : layout.programConfig(), ps, signaling);
        }
    }

    /**
     * {@return the options in use: those the encoder was created with, with the bitrate of the last
     * {@link #setTargetBitrate(long)} when one was set}
     */
    public AacEncoderOptions options() {
        return options;
    }

    /**
     * {@inheritDoc}
     *
     * <p>The target must fit the format as {@link AacEncoderOptions#bitrate()} requires. In constant bitrate mode
     * the frames coded from now on average exactly the new bitrate, the bit reservoir keeping its fullness up to the
     * capacity of the new rate; in variable bitrate mode the new bitrate is the nominal one the coding quality is
     * derived from. With AAC Low Complexity, AAC-LD and AAC-ELD an automatic bandwidth follows the new
     * bitrate, and {@link #options()} reports it. With HE-AAC and HE-AAC v2 the target must stay in the range
     * of {@link AacEncoderOptions#bitrate()} for the configured profile and layout; the core takes the new bitrate,
     * while the spectral band replication and parametric stereo keep the frequency range and the stereo bands
     * they started with, so the stream may then code another range than the
     * {@link AacEncoderOptions#effectiveBandwidth()} that {@link #options()} reports for the new bitrate.
     *
     * @throws IllegalArgumentException if {@code bitsPerSecond} is outside the range of bitrates the format allows
     * @throws IllegalStateException if the encoder was flushed
     */
    @Override
    public void setTargetBitrate(long bitsPerSecond) {
        if (flushed) {
            throw new IllegalStateException("the encoder was flushed");
        }
        if (bitsPerSecond <= 0 || bitsPerSecond > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("invalid bitrate " + bitsPerSecond);
        }
        var retargeted = options.toBuilder().bitrate((int) bitsPerSecond).build();
        options = retargeted;
        if (engine != null) {
            engine.retarget(retargeted.bitrate(), retargeted.effectiveBandwidth());
        } else {
            sbr.retarget(retargeted.bitrate());
        }
    }

    @Override
    public Codec codec() {
        return Codec.AAC;
    }

    @Override
    public int sampleRate() {
        return options.sampleRate();
    }

    @Override
    public ChannelLayout layout() {
        return options.layout();
    }

    /**
     * {@inheritDoc}
     *
     * @return {@link AacEncoderOptions#frameLength()}, 1024 or 960; twice that for HE-AAC and half for AAC-LD and
     * AAC-ELD
     */
    @Override
    public int frameSize() {
        return frameLength;
    }

    /**
     * {@inheritDoc}
     *
     * <p>For AAC Low Complexity and AAC-LD the delay is exactly one frame, {@link #frameSize()} samples: the
     * first packet's window starts one frame before the first input sample. For AAC-ELD it is three quarters
     * of a frame, the delay of the low delay filterbank. For HE-AAC it is one frame plus the
     * delays of the QMF filterbanks of the encoder and of a decoder conforming to ISO/IEC 14496-3: 1539
     * samples more.
     */
    @Override
    public int delay() {
        if (sbr != null) {
            return sbr.delay();
        }
        return options.profile() == AacProfile.ELD ? LowDelayAnalysis.delay(frameLength) : frameLength;
    }

    /**
     * {@inheritDoc}
     *
     * @return the {@code AudioSpecificConfig}: for AAC Low Complexity two bytes, followed by the
     * program config element when {@link AacEncoderOptions#channelConfiguration()} is 0; for HE-AAC the
     * form {@link AacEncoderOptions#signaling()} selects; for AAC-LD and AAC-ELD the error resilient form
     * without resilience tools
     */
    @Override
    public Optional<byte[]> codecConfig() {
        return Optional.of(audioSpecificConfig.clone());
    }

    /**
     * {@inheritDoc}
     *
     * @throws IllegalStateException if the encoder was flushed
     */
    @Override
    public void encode(AudioBuffer input, Consumer<Packet> output) {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(output, "output");
        if (flushed) {
            throw new IllegalStateException("the encoder was flushed");
        }
        if (input.sampleRate() != options.sampleRate() || !input.layout().equals(options.layout())) {
            throw new IllegalArgumentException("input " + input.sampleRate() + " Hz " + input.layout().speakers()
                    + " does not match the encoder's " + options.sampleRate() + " Hz " + options.layout().speakers());
        }
        var planes = input.planes();
        var offset = 0;
        var remaining = input.frames();
        received += remaining;
        while (remaining > 0) {
            var count = Math.min(remaining, capacity());
            if (engine != null) {
                engine.push(planes, offset, count);
            } else {
                sbr.push(planes, offset, count);
            }
            offset += count;
            remaining -= count;
            drain(output);
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>After a flush the encoder accepts no further input.
     */
    @Override
    public void flush(Consumer<Packet> output) {
        Objects.requireNonNull(output, "output");
        if (flushed) {
            return;
        }
        flushed = true;
        if (received == 0) {
            return;
        }
        var needed = (received + delay() + frameLength - 1) / frameLength;
        while (frames() < needed) {
            if (!frameReady()) {
                if (engine != null) {
                    engine.pad(engine.capacity());
                } else {
                    sbr.pad(sbr.capacity());
                }
                continue;
            }
            emit(output);
        }
    }

    /**
     * Returns the number of input samples per channel the encoder takes before it must encode.
     *
     * @return the free capacity
     */
    private int capacity() {
        return engine != null ? engine.capacity() : sbr.capacity();
    }

    /**
     * Returns whether the next frame is ready to encode.
     *
     * @return {@code true} when a packet can be emitted
     */
    private boolean frameReady() {
        return engine != null ? engine.frameReady() : sbr.frameReady();
    }

    /**
     * Returns the number of packets emitted.
     *
     * @return the packet count
     */
    private long frames() {
        return engine != null ? engine.frames() : sbr.frames();
    }

    /**
     * Encodes every complete frame.
     *
     * @param output receives the packets
     */
    private void drain(Consumer<Packet> output) {
        while (frameReady()) {
            emit(output);
        }
    }

    /**
     * Encodes the next frame and hands its packet to {@code output}.
     *
     * @param output receives the packet
     */
    private void emit(Consumer<Packet> output) {
        var timestamp = frames() * frameLength;
        var block = engine != null ? engine.encodeFrame() : sbr.encodeFrame();
        output.accept(new Packet(0, block, timestamp, timestamp, frameLength, true));
    }
}
