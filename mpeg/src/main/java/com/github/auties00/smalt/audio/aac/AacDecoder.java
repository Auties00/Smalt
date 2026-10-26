package com.github.auties00.smalt.audio.aac;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.audio.AudioBuffer;
import com.github.auties00.smalt.audio.AudioDecoder;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.audio.aac.internal.AudioSpecificConfig;
import com.github.auties00.smalt.audio.aac.internal.DynamicRangeControl;
import com.github.auties00.smalt.audio.aac.internal.FrameDecoder;
import com.github.auties00.smalt.audio.aac.internal.SamplingTables;
import com.github.auties00.smalt.internal.check.Checks;
import com.github.auties00.smalt.internal.codec.ConsumerFailure;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Decodes MPEG-4 and MPEG-2 AAC audio of the Main, Low Complexity, Scalable Sample Rate (SSR) and
 * Long Term Prediction (LTP) object types, of the error resilient ER AAC LC and ER AAC LTP, and of
 * the low delay AAC-LD and AAC-ELD, with the HE-AAC extensions: spectral band replication (HE-AAC),
 * parametric stereo (HE-AAC v2) and the low delay spectral band replication of AAC-ELD.
 *
 * <p>The track's {@link AudioTrack#codecConfig()} must hold the {@code AudioSpecificConfig};
 * each {@link Packet} holds one {@code raw_data_block} (for ADTS streams, the frame without its
 * header), or several back to back for a CRC-less multi-block ADTS frame; an error resilient
 * packet holds one {@code er_raw_data_block}, whose extension payloads are skipped. A stream whose
 * configuration sets {@code epConfig} 1 stores each error sensitivity category instance of a
 * block in an access unit of its own (an MP4 file carries them as separate tracks); such blocks
 * are decoded by {@link #decode(List, Consumer)} from all their instances. Every raw data block
 * decodes to one block of 1024 samples per channel, or 960 for streams whose configuration sets
 * the frame length flag (512 and 480 for AAC-LD and AAC-ELD), twice that when spectral band
 * replication doubles the rate. The first block is output too: it holds the priming of the
 * transform and the filterbanks, which the container removes through the track's
 * {@link AudioTrack#skipSamples()} rather than the decoder.
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
 * <p>The other object types add their tools: AAC Main the backward-adaptive frequency-domain
 * prediction of ISO/IEC 13818-7 (with predictor reset groups and the interplay with intensity
 * stereo and noise substitution), AAC LTP and ER AAC LTP long term prediction (with TNS on the
 * predicted spectrum), and AAC SSR the four-band PQF filterbank with gain control. The error
 * resilient object types decode every error resilience tool: virtual codebooks (VCB11), reversible
 * variable length coded scalefactors (RVLC) and Huffman codeword reordering (HCR). AAC-LD adds the
 * low overlap window and long term prediction with the low delay lag; AAC-ELD the low delay
 * filterbank, the {@code ELDSpecificConfig} and low delay spectral band replication, at dual rate
 * or downsampled as the configuration signals. Error resilient streams decode with {@code epConfig}
 * 0 and 1. Spectral band replication or parametric stereo signaled around an error resilient
 * core (object type 5 or 29) is not decoded: the core decodes alone at its own rate.
 * Corrupt RVLC codewords and reordered spectral data are concealed rather than rejected: scalefactors
 * that cannot be decoded in either direction take the quieter of the values decoded around them or the
 * previous block's values, as {@code sf_concealment} allows, and spectral codewords that do not decode,
 * overrun their segment or exceed their codebook's range are muted; the rest of the block decodes
 * normally. Side information that leaves a block's own layout inconsistent still raises
 * {@link MediaException.Malformed}.
 *
 * <p>Spectral band replication is decoded in the high quality (complex) mode, from 1024- and
 * 960-sample frames, on every channel element that carries it; parametric stereo is decoded in
 * full (ISO/IEC 14496-3 subclause 8.6.4: 10, 20 and 34 stereo bands with their hybrid analysis,
 * the IPD and OPD phase parameters, mixing procedures A and B) and turns a mono core into two
 * channels. The output rate and
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
 * data is ignored until the next valid payload. Dynamic range control applies as
 * {@link AacDecoderOptions#compression()}, {@link AacDecoderOptions#cutScale()},
 * {@link AacDecoderOptions#boostScale()} and {@link AacDecoderOptions#targetLevel()} set it; by
 * default the full dynamic range is reproduced at the stream's own level. Every channel
 * configuration decodes, the 22.2 configuration 13 into the 24 speakers of
 * {@link #layout()}. Unsupported object types raise {@link MediaException.Unsupported}
 * naming them ({@code "aac scalable"}, {@code "aac er bsac"}, {@code "usac"}), as do error
 * resilient configurations that
 * signal the error protection tool ({@code "aac er epconfig 2"} and {@code "aac er epconfig 3"}),
 * and AAC-ELD with {@code epConfig} 1 ({@code "aac eld epconfig 1"}).
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
     * The data of the category instances of the block being decoded, reused block after block.
     */
    private byte[][] instanceData = new byte[0][];

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
                    && !(config.psSignaled() && !config.ps()) && config.lowDelaySbr() == null;
            var compression = options.compression();
            var dynamicRange = new DynamicRangeControl(compression != AacCompression.NONE,
                    compression == AacCompression.HEAVY, options.cutScale(), options.boostScale(),
                    options.targetLevel().orElse(-1), config.frameLength(), config.lowDelaySbr() != null);
            this.decoder = new FrameDecoder(config, limits, fallback, mode, stereo, dynamicRange);
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
        if (config.lowDelaySbr() != null && enabled) {
            return config.lowDelaySbr().dualRate() && dual ? FrameDecoder.SbrMode.DUAL_RATE
                    : FrameDecoder.SbrMode.DOWNSAMPLED;
        }
        if (!enabled || config.errorResilient() || (config.sbrSignaled() && !config.sbr())) {
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
                configuration, null, 1024, false, false, 0, false, false, 0, 0, null);
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
     * the configuration sets the frame length flag (512 or 480 for AAC-LD and AAC-ELD), doubled
     * when spectral band replication doubles the rate}
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
     * Returns whether the configuration stores each error sensitivity category instance of a block
     * in an access unit of its own ({@code epConfig} 1).
     *
     * <p>Such streams are decoded by {@link #decode(List, Consumer)}; {@link #decode(Packet, Consumer)}
     * rejects them.
     *
     * @return {@code true} when blocks come as several category instances
     */
    public boolean separateCategoryInstances() {
        return config.epConfig() == 1;
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
     * @throws MediaException.Unsupported if the configuration stores category instances separately
     * ({@code "aac er epconfig 1"}); {@link #decode(List, Consumer)} decodes such streams
     */
    @Override
    public void decode(Packet packet, Consumer<AudioBuffer> output) {
        Objects.requireNonNull(packet, "packet");
        Objects.requireNonNull(output, "output");
        if (separateCategoryInstances()) {
            throw new MediaException.Unsupported("aac er epconfig 1",
                    "error resilient AAC with separate category instances needs every instance of a block");
        }
        var data = packet.data();
        ConsumerFailure.<AudioBuffer>run("AAC packet", output, emit -> decoder.decode(data, 0, data.length, emit));
    }

    /**
     * Decodes one block of an {@code epConfig} 1 stream from its error sensitivity category
     * instances and hands the block's samples to {@code output}.
     *
     * <p>{@code instances} holds the access units of one block, in the order of their elementary
     * streams (the track order of an MP4 file): a channel pair element first contributes the
     * instance of its common side information; then every channel, in element order, the instance
     * of its side information, the instance of its RVLC codewords when the configuration codes
     * scalefactors with RVLC, the instance of its TNS data and the instance of its spectral data.
     * Instances after the last one the elements need, which carry extension payloads, are ignored.
     * The packets are read only during the call. An exception thrown by {@code output} propagates
     * unchanged.
     *
     * @param instances the category instances of the block
     * @param output receives the block's samples, valid only during the call
     * @throws NullPointerException if one of the packets of {@code instances} is {@code null}
     * @throws IllegalStateException if the configuration does not store category instances
     * separately
     * @throws MediaException.Malformed if an instance is corrupt or missing
     * @throws MediaException.LimitExceeded if the block declares more channels than the
     * configured limits allow
     */
    public void decode(List<Packet> instances, Consumer<AudioBuffer> output) {
        Objects.requireNonNull(instances, "instances");
        Objects.requireNonNull(output, "output");
        if (!separateCategoryInstances()) {
            throw new IllegalStateException("the configuration does not store category instances separately");
        }
        var count = instances.size();
        if (instanceData.length < count) {
            instanceData = new byte[count][];
        }
        var data = instanceData;
        for (var i = 0; i < count; i++) {
            data[i] = Objects.requireNonNull(instances.get(i), "instance").data();
        }
        try {
            ConsumerFailure.<AudioBuffer>run("AAC packet", output, emit -> decoder.decodeInstances(data, count, emit));
        } finally {
            Arrays.fill(data, 0, count, null);
        }
    }

    /**
     * {@inheritDoc}
     *
     * @return {@code true}
     */
    @Override
    public boolean supportsConcealment() {
        return true;
    }

    /**
     * {@inheritDoc}
     *
     * <p>The concealment unit is {@link #frameLength()}. Each unit is a block whose spectrum repeats, channel by
     * channel, the last one synthesized with random signs: at full amplitude for the first block lost in a row,
     * halved for each further one, and silent from the fifth on, the overlap of the transform smoothing every
     * join. A channel whose last block had eight short windows (outside AAC SSR) is concealed with a long stop window
     * whose lines interleave the short windows' lines, and one whose last block had a long start window with a long
     * stop window, so the window sequence stays valid; the others use a long window. The predictors of AAC Main and
     * the long term prediction history continue from the concealed signal. With spectral band replication, the high
     * band repeats the last envelope, 6 dB lower for each further block, until the core falls silent, and parametric
     * stereo keeps its last parameters. Before the first block, and after the channel layout changed, the span is
     * silence.
     *
     * @implNote This implementation substitutes spectra in the manner of the informative error concealment of
     * ISO/IEC 14496-3 but without its interpolation toward the next good block, which would delay the output by a
     * block.
     * @throws IllegalArgumentException {@inheritDoc}
     */
    @Override
    public int conceal(int samples, Consumer<AudioBuffer> output) {
        Checks.positive("samples", samples);
        Objects.requireNonNull(output, "output");
        var unit = frameLength();
        var units = Math.min(Math.max(1, (samples + unit / 2) / unit), Integer.MAX_VALUE / unit);
        ConsumerFailure.<AudioBuffer>run("AAC concealment", output, emit -> {
            for (var i = 0; i < units; i++) {
                emit.accept(decoder.conceal());
            }
        });
        return units * unit;
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
