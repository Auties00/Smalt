package com.github.auties00.smalt.audio.aac;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.audio.AudioBuffer;
import com.github.auties00.smalt.audio.AudioDecoder;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.audio.aac.internal.AudioSpecificConfig;
import com.github.auties00.smalt.audio.aac.internal.FrameDecoder;
import com.github.auties00.smalt.audio.aac.internal.SamplingTables;
import com.github.auties00.smalt.internal.codec.ConsumerFailure;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * Decodes MPEG-4 AAC Low Complexity audio and its HE-AAC extensions: spectral band replication
 * (HE-AAC) and parametric stereo (HE-AAC v2).
 *
 * <p>The track's {@link AudioTrack#codecConfig()} must hold the {@code AudioSpecificConfig};
 * each {@link Packet} holds one {@code raw_data_block} (for ADTS streams, the frame without its
 * header), or several back to back for a CRC-less multi-block ADTS frame. Every raw data block
 * decodes to one block of 1024 samples per channel, or 960 for streams whose configuration sets
 * the frame length flag, twice that when spectral band replication doubles the rate. The first
 * block is output too: it holds the priming of the transform and the filterbanks, which the
 * container removes through the track's {@link AudioTrack#skipSamples()} rather than the
 * decoder.
 *
 * <p>Output channels follow the WAVE order of {@link ChannelLayout} (the 5.1 configuration, coded
 * center, left, right, surrounds, LFE, is output front left, front right, center, LFE, back
 * left, back right). A program configuration element, in the {@code AudioSpecificConfig} or in
 * band, positions its channels by element position; channels it cannot position are output last
 * with an unknown speaker. Samples are floats scaled so that a full-scale 16-bit decoder output
 * spans {@code [-1, 1]}.
 *
 * <p>Supported: every window sequence and shape, section and scalefactor coding, pulses, TNS,
 * all eleven spectrum codebooks with escapes, M/S and intensity stereo, perceptual noise
 * substitution (with a fixed-seed generator, so decoding is deterministic), coupling channel
 * elements (dependently switched before or after TNS, and independently switched), 1024- and
 * 960-sample frames, sampling frequencies from 7350 Hz to 96 kHz as well as explicit
 * frequencies, channel configurations 1 to 7, 11, 12 and 14, and program configurations in the
 * {@code AudioSpecificConfig} or in band. A block whose elements do not match the configured
 * layout is decoded with a layout inferred from its elements, which the returned
 * {@link AudioBuffer} reports.
 *
 * <p>Spectral band replication is decoded in the high quality (complex) mode, from 1024- and
 * 960-sample frames, on every channel element that carries it; parametric stereo is decoded by
 * the baseline decoder of ISO/IEC 14496-3 Annex 8.A (parameters on 34 bands are mapped onto 20,
 * the phase parameters are skipped) and turns a mono core into two channels. The output rate and
 * layout are fixed when the decoder is created, from the configuration and the track:
 * <ul>
 *   <li>Explicit signaling (object types 5 and 29, or the backward-compatible sync extension)
 *   decides. An extension rate above the core rate (normally twice it) gives dual-rate output at
 *   twice the core rate; an extension rate equal to the core rate gives the downsampled mode,
 *   which decodes the extension at the core rate. An explicit absence decodes the core only.</li>
 *   <li>Without signaling (implicit signaling, the usual case for ADTS), a track that declares
 *   twice the core rate is decoded at dual rate. A track that declares no rate (0) is decided on
 *   the first packet, as players do: extension data in the first raw data block gives dual-rate
 *   output, otherwise the core is decoded; {@link #sampleRate()} and {@link #layout()} report the
 *   core format until then. A track that declares the core rate is decided on the first packet
 *   too, but keeps the core rate: extension data in the first block is decoded in the
 *   downsampled mode.</li>
 *   <li>Dual-rate output is used only when twice the core rate is at most 96 kHz; a higher core
 *   rate decodes the extension in the downsampled mode.</li>
 *   <li>A mono core with spectral band replication is decoded to two channels, which stay equal
 *   until parametric stereo data arrives, unless the configuration signals that no parametric
 *   stereo is present, or the core rate is kept for a track that declares it without
 *   signaling.</li>
 * </ul>
 * {@link AacDecoderOptions.Builder#spectralBandReplication(boolean)} turns the extensions off. Once
 * spectral band replication is on, every packet runs through the QMF filterbanks, with or
 * without extension data, so the output delay never changes within a stream; corrupt extension
 * data is ignored until the next valid payload. Dynamic range
 * control data is ignored. Unsupported object types raise {@link MediaException.Unsupported}
 * naming them ({@code "aac main"}, {@code "aac ssr"}, {@code "aac ltp"}, {@code "aac scalable"},
 * {@code "aac er lc"}, {@code "aac er ltp"}, {@code "aac er bsac"}, {@code "aac ld"},
 * {@code "aac eld"}, {@code "usac"}), as do the 22.2 configuration
 * ({@code "aac channel configuration 13"}) and SSR gain control data ({@code "aac gain control"}).
 *
 * <p>Instances are not thread-safe.
 */
public final class AacDecoder implements AudioDecoder {
    /**
     * The frame decoder.
     */
    private final FrameDecoder decoder;

    /**
     * The parsed stream configuration.
     */
    private final AudioSpecificConfig config;

    /**
     * Constructs a decoder for a track under {@link AacDecoderOptions#defaults()}.
     *
     * <p>When the track carries no configuration, a plain Low Complexity stream is assumed at the
     * track's declared sampling rate and layout.
     *
     * @param track the AAC track
     * @throws IllegalArgumentException if the track is not an AAC track
     * @throws MediaException.Malformed if the {@code AudioSpecificConfig} is invalid, or absent while
     * the track declares no sampling rate
     * @throws MediaException.Unsupported if the configuration uses an unsupported object type or
     * channel configuration
     * @throws MediaException.LimitExceeded if the configuration declares more channels than the
     * default limits allow
     */
    public AacDecoder(AudioTrack track) {
        this(track, AacDecoderOptions.defaults());
    }

    /**
     * Constructs a decoder for a track under explicit options.
     *
     * <p>When the track carries no configuration, a plain Low Complexity stream is assumed at the
     * track's declared sampling rate and layout.
     *
     * @param track the AAC track
     * @param options the resource limits and the extensions to decode
     * @throws IllegalArgumentException if the track is not an AAC track
     * @throws MediaException.Malformed if the {@code AudioSpecificConfig} is invalid, or absent while
     * the track declares no sampling rate
     * @throws MediaException.Unsupported if the configuration uses an unsupported feature
     * @throws MediaException.LimitExceeded if the configuration declares more channels than the
     * options' limits allow
     */
    public AacDecoder(AudioTrack track, AacDecoderOptions options) {
        Objects.requireNonNull(track, "track");
        Objects.requireNonNull(options, "options");
        var limits = options.limits();
        if (track.codec() != Codec.AAC) {
            throw new IllegalArgumentException(track.codec() + " is not AAC");
        }
        try {
            this.config = track.codecConfig().isPresent()
                    ? AudioSpecificConfig.parse(track.codecConfig().get())
                    : implicitConfig(track);
            var fallback = track.layout().orElse(ChannelLayout.STEREO);
            var mode = sbrMode(config, track.sampleRate(), options.spectralBandReplication());
            var stereo = mode != FrameDecoder.SbrMode.OFF && mode != FrameDecoder.SbrMode.DETECT_DOWNSAMPLED
                    && !(config.psSignaled() && !config.ps());
            this.decoder = new FrameDecoder(config, limits, fallback, mode, stereo);
        } catch (MediaException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new MediaException.Malformed("invalid AAC configuration", exception);
        }
    }

    /**
     * Chooses how the frame decoder treats spectral band replication.
     *
     * @param config the stream configuration
     * @param declaredRate the sampling rate the track declares, or 0
     * @param enabled whether the options enable the extensions
     * @return the mode
     */
    private static FrameDecoder.SbrMode sbrMode(AudioSpecificConfig config, int declaredRate, boolean enabled) {
        var core = config.sampleRate();
        var dual = 2L * core <= 96000;
        if (!enabled || (config.sbrSignaled() && !config.sbr())) {
            return FrameDecoder.SbrMode.OFF;
        }
        if (config.sbr()) {
            return config.extensionSampleRate() > core && dual ? FrameDecoder.SbrMode.DUAL_RATE
                    : FrameDecoder.SbrMode.DOWNSAMPLED;
        }
        if (declaredRate == 2L * core && dual) {
            return FrameDecoder.SbrMode.DUAL_RATE;
        }
        if (declaredRate == 0) {
            return dual ? FrameDecoder.SbrMode.DETECT_DUAL_RATE : FrameDecoder.SbrMode.DETECT_DOWNSAMPLED;
        }
        return FrameDecoder.SbrMode.DETECT_DOWNSAMPLED;
    }

    /**
     * Builds the configuration of a track that carries none.
     *
     * @param track the track without a codec configuration
     * @return a Low Complexity configuration at the declared rate and layout
     * @throws MediaException.Malformed if the track declares no sampling rate
     */
    private static AudioSpecificConfig implicitConfig(AudioTrack track) {
        if (track.sampleRate() <= 0) {
            throw new MediaException.Malformed("AAC track carries neither an AudioSpecificConfig nor a sampling rate");
        }
        var channels = track.layout().map(ChannelLayout::channels).orElse(2);
        var configuration = switch (channels) {
            case 1, 2, 3, 4, 5, 6 -> channels;
            case 8 -> 7;
            default -> 0;
        };
        var rate = track.sampleRate();
        return new AudioSpecificConfig(AudioSpecificConfig.AOT_LC, rate,
                SamplingTables.tableIndexFor(rate),
                configuration, null, 1024, false, false, 0, false, false);
    }

    /**
     * {@inheritDoc}
     *
     * <p>With spectral band replication at dual rate this is twice the core rate. When the
     * decision is left to the first packet, this is the core rate until then.
     */
    @Override
    public int sampleRate() {
        return decoder.sampleRate();
    }

    /**
     * {@inheritDoc}
     *
     * <p>When the configuration leaves the layout to the bitstream, this is the track's declared
     * layout (stereo when it declares none) until the first packet is decoded. A mono core with
     * spectral band replication reports stereo (see the class description).
     */
    @Override
    public ChannelLayout layout() {
        return decoder.layout();
    }

    /**
     * {@return the number of samples per channel each raw data block decodes to: 1024, or 960 when
     * the configuration sets the frame length flag, doubled when spectral band replication doubles
     * the rate}
     */
    public int frameLength() {
        return decoder.frameLength();
    }

    /**
     * Returns whether the track's configuration signals spectral band replication (HE-AAC).
     *
     * <p>Both explicit and backward-compatible signaling count. A stream may carry the extension
     * without signaling it; {@link #decodesSpectralBandReplication()} tells whether the output
     * includes it.
     *
     * @return {@code true} when the configuration signals spectral band replication
     */
    public boolean spectralBandReplicationSignaled() {
        return config.sbr();
    }

    /**
     * {@return whether the track's configuration signals parametric stereo (HE-AAC v2)}
     */
    public boolean parametricStereoSignaled() {
        return config.ps();
    }

    /**
     * Returns whether this decoder decodes spectral band replication, so the output includes the
     * replicated high band.
     *
     * <p>When the configuration leaves the decision to the first packet, this is {@code false}
     * until that packet is decoded.
     *
     * @return {@code true} when the output includes the replicated high band
     */
    public boolean decodesSpectralBandReplication() {
        return decoder.sbrActive();
    }

    /**
     * {@inheritDoc}
     *
     * <p>A packet holding several {@code raw_data_block}s back to back, as a CRC-less ADTS frame
     * with more than one block does, yields one block of samples per raw data block. An exception
     * thrown by {@code output} propagates unchanged.
     *
     * @throws MediaException.Malformed if the packet is corrupt
     * @throws MediaException.Unsupported if the packet uses a coding tool the decoder lacks, such as SSR gain
     * control data
     */
    @Override
    public void decode(Packet packet, Consumer<AudioBuffer> output) {
        Objects.requireNonNull(packet, "packet");
        Objects.requireNonNull(output, "output");
        var data = packet.data();
        ConsumerFailure.<AudioBuffer>run("AAC packet", output, emit -> decoder.decode(data, 0, data.length, emit));
    }

    /**
     * {@inheritDoc}
     *
     * <p>AAC buffers nothing beyond the overlap of the last frame, which belongs to the priming
     * of a frame that never comes, so this emits nothing.
     */
    @Override
    public void flush(Consumer<AudioBuffer> output) {
        Objects.requireNonNull(output, "output");
    }
}
