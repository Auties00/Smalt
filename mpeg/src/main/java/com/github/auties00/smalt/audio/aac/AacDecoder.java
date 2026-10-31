package com.github.auties00.smalt.audio.aac;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.audio.AudioBuffer;
import com.github.auties00.smalt.audio.AudioDecoder;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.audio.aac.internal.AudioSpecificConfig;
import com.github.auties00.smalt.audio.aac.internal.DynamicRangeControl;
import com.github.auties00.smalt.audio.aac.internal.FrameDecoder;
import com.github.auties00.smalt.audio.aac.internal.SamplingTables;
import com.github.auties00.smalt.audio.aac.internal.mps.MpsConfig;
import com.github.auties00.smalt.audio.aac.internal.usac.UsacConfig;
import com.github.auties00.smalt.audio.aac.internal.usac.UsacDecoder;
import com.github.auties00.smalt.internal.check.Checks;
import com.github.auties00.smalt.internal.codec.ConsumerFailure;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Decodes MPEG-4 and MPEG-2 AAC audio of the Main, Low Complexity, Scalable Sample Rate (SSR), Long Term
 * Prediction (LTP) and scalable object types, of the error resilient ER AAC LC, ER AAC LTP, ER AAC scalable and
 * ER BSAC, of the low delay AAC-LD and AAC-ELD, and of USAC (MPEG-D Unified Speech and Audio Coding), with the
 * HE-AAC extensions: spectral band replication (HE-AAC), parametric stereo (HE-AAC v2), the low delay spectral
 * band replication of AAC-ELD and MPEG Surround.
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
 * 0 and 1. Spectral band replication or parametric stereo signaled around an error resilient or
 * scalable core (object type 5 or 29) is not decoded: the core decodes alone at its own rate.
 * Corrupt RVLC codewords and reordered spectral data are concealed rather than rejected: scalefactors
 * that cannot be decoded in either direction take the quieter of the values decoded around them or the
 * previous block's values, as {@code sf_concealment} allows, and spectral codewords that do not decode,
 * overrun their segment or exceed their codebook's range are muted; the rest of the block decodes
 * normally. Side information that leaves a block's own layout inconsistent still raises
 * {@link MediaException.Malformed}.
 *
 * <p>ER BSAC (object type 22, the bit-sliced arithmetic coding of ISO/IEC 14496-3 subclause 4.5.2.6)
 * decodes every layer up to {@link AacDecoderOptions#maxLayer()}, with its multichannel extension
 * elements (the front, surround, outside and LFE channels that follow the base element) and its spectral
 * band replication payloads, which decode like those of HE-AAC. A frame is the concatenation of the
 * access units of every elementary stream of the stream: a stream that carries its layers or its
 * {@code epConfig} 1 categories in separate elementary streams (separate MP4 tracks) is decoded by
 * {@link #decode(List, Consumer)}, which concatenates them in stream order, and a stream of one
 * elementary stream by {@link #decode(Packet, Consumer)}. The window, M/S and intensity stereo, noise
 * substitution and TNS tools follow the reference decoder of ISO/IEC 14496-5, in double precision.
 *
 * <p>Scalable AAC (object types 6 and 20, ISO/IEC 14496-3 subclause 4.5.2.2) carries each coding layer in an
 * elementary stream with a configuration of its own: mono layers, stereo layers, or mono layers under stereo
 * layers, which the frequency selective switch joins. A decoder built from the tracks of every layer
 * ({@link #AacDecoder(List, AacDecoderOptions)}) decodes, through {@link #decode(List, Consumer)}, the layers each
 * frame holds up to {@link AacDecoderOptions#maxLayer()}, and outputs the channels of that layer; a frame that lacks
 * the stereo layers of a stereo output outputs its mono layers in both channels. A decoder built from the base
 * layer's track alone decodes the base layer. Every tool of the layers decodes: long term prediction of the first
 * layer, M/S and intensity stereo, noise substitution, TNS of the first layer and of the first stereo layer above a
 * mono one, and, for object type 20, the error resilience tools of each layer with {@code epConfig} 0 and 1. Layers
 * over a CELP or TwinVQ core raise {@link MediaException.Unsupported} ({@code "aac scalable core"}).
 *
 * <p>USAC (object type 42, ISO/IEC 23003-3) decodes with its frequency domain coder (arithmetic coded spectra,
 * noise filling, complex prediction stereo, the time-warped transform, 768- and 1024-sample frames) and its linear
 * prediction domain coder (ACELP and TCX with forward aliasing cancellation), enhanced spectral band replication
 * (the QMF based harmonic transposer, predictive vector coding and inter-TES, at the 2:1, 8:3 and 4:1 ratios),
 * MPEG Surround 2-1-2 with residual coding, {@code AudioPreRoll} and the MPEG-D DRC decoder of ISO/IEC 23003-4,
 * whose loudness normalization and DRC set selection follow {@link AacDecoderOptions#targetLevel()} and
 * {@link AacDecoderOptions#drcEffect()}. An element that uses the time-warped transform ({@code tw_mdct}, outside
 * the Extended HE AAC profile) and switches to the linear prediction domain coder raises
 * {@link MediaException.Unsupported} ({@code "usac tw-mdct lpd"}).
 *
 * <p>MPEG Surround (ISO/IEC 23003-1, object type 30) upmixes the decoded downmix of a Main, Low Complexity, SSR or
 * LTP stream, with or without spectral band replication, while {@link AacDecoderOptions#mpegSurround()} is on.
 * The spatial side information comes either in the {@code sac_extension_data()} payloads of the downmix's fill
 * elements, whose {@code SpatialSpecificConfig} starts the upmix, or in a separate MPEG Surround elementary stream
 * (an object type 30 {@code AudioSpecificConfig}): a decoder built from the downmix track and that track
 * ({@link #AacDecoder(List, AacDecoderOptions)}) decodes each block through {@link #decode(List, Consumer)} from the
 * access units of both. Every tree configuration decodes (5-1-5 with a mono downmix, 5-2-5 with a stereo one,
 * 7-2-7 and 7-5-7 to 7.1), with residual coding, arbitrary downmix gains and residuals, smoothing, the
 * decorrelators, the subband domain temporal processing and the guided envelope shaping. The 5.1 output follows
 * {@link ChannelLayout#SURROUND_5_1}; the 7.1 trees output front left, front right, center, LFE, back left, back
 * right and either the left and right of center (7-2-7 and 7-5-7 with front channels) or the side channels (with
 * surround channels). With spectral band replication at dual rate, the upmix takes the 64-band QMF matrices of the
 * extension directly, without a second filterbank, and leads the time domain upmix by 577 samples. A configuration
 * the decoder does not support (the arbitrary tree extension is decoded without its extra channels; 3D audio
 * downmixes are not supported) leaves the downmix as the output, as a decoder without MPEG Surround would.
 * A spatial frame lasts exactly one block: a configuration whose frame length differs from the block is not
 * upmixed. The upmix starts at the first spatial frame that does not depend on the frames before it (its
 * {@code bsIndependencyFlag} is set), so a decoder that starts within a stream outputs the downmix until then.
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
 *   stereo is present, the core rate is kept for a track that declares it without
 *   signaling, or MPEG Surround upmixes the mono core.</li>
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
 * naming them ({@code "mpeg-4 audio object type 8"}), as do error resilient configurations that
 * signal the error protection tool ({@code "aac er epconfig 2"} and {@code "aac er epconfig 3"}),
 * and AAC-ELD with {@code epConfig} 1 ({@code "aac eld epconfig 1"}).
 *
 * <p>Instances are not thread-safe.
 */
public final class AacDecoder implements AudioDecoder {
    /**
     * The frame decoder, or {@code null} for USAC.
     */
    private final FrameDecoder decoder;

    /**
     * The USAC decoder, or {@code null} for the other object types.
     */
    private final UsacDecoder usac;

    /**
     * The parsed stream configuration, or {@code null} for USAC.
     */
    private final AudioSpecificConfig config;

    /**
     * The data of the category instances of the block being decoded, reused block after block.
     */
    private byte[][] instanceData = new byte[0][];

    /**
     * The index of the track of a separate MPEG Surround elementary stream whose access units carry the spatial
     * frames, or -1.
     */
    private final int surroundTrack;

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
        this(List.of(Objects.requireNonNull(track, "track")), options);
    }

    /**
     * Constructs a decoder for an audio object carried in several elementary streams under
     * {@link AacDecoderOptions#defaults()}.
     *
     * @param tracks the tracks of the object's elementary streams, in stream order, the base layer first
     * @throws IllegalArgumentException if {@code tracks} is empty or a track is not an AAC track
     * @throws MediaException.Malformed if a configuration is invalid, or the configurations of a scalable
     * object do not describe its layers
     * @throws MediaException.Unsupported if the configuration uses an unsupported feature
     * @throws MediaException.LimitExceeded if the configuration declares more channels than the
     * default limits allow
     * @see #AacDecoder(List, AacDecoderOptions)
     */
    public AacDecoder(List<AudioTrack> tracks) {
        this(tracks, AacDecoderOptions.defaults());
    }

    /**
     * Constructs a decoder for an audio object carried in several elementary streams under explicit options.
     *
     * <p>The first track describes the stream as {@link #AacDecoder(AudioTrack, AacDecoderOptions)} takes it.
     * A scalable AAC object (object types 6 and 20) carries each coding layer in an elementary stream with a
     * configuration of its own, and with {@code epConfig} 1 each error sensitivity category instance of a layer:
     * the decoder reads the configuration of every track and decodes the layers they describe, which
     * {@link #decode(List, Consumer)} then receives an access unit of each. A downmix of a Main, Low Complexity,
     * SSR or LTP stream may be followed by the track of its MPEG Surround elementary stream (object type 30): the
     * first such track configures the upmix while {@link AacDecoderOptions#mpegSurround()} is on, and unless its
     * configuration embeds the spatial frames in the downmix, {@link #decode(List, Consumer)} then receives the
     * access units of both. Every other track is ignored, and every other object type takes its configuration
     * from the first track alone, so this constructor then equals the single-track one.
     *
     * @param tracks the tracks of the object's elementary streams, in stream order, the base layer first
     * @param options the resource limits and the extensions to decode
     * @throws IllegalArgumentException if {@code tracks} is empty or a track is not an AAC track
     * @throws NullPointerException if a track of {@code tracks} is {@code null}
     * @throws MediaException.Malformed if a configuration is invalid, or the configurations of a scalable
     * object do not describe its layers
     * @throws MediaException.Unsupported if the configuration uses an unsupported feature, including scalable
     * AAC over a CELP or TwinVQ core ({@code "aac scalable core"})
     * @throws MediaException.LimitExceeded if the configuration declares more channels than the
     * options' limits allow
     */
    public AacDecoder(List<AudioTrack> tracks, AacDecoderOptions options) {
        Objects.requireNonNull(tracks, "tracks");
        Objects.requireNonNull(options, "options");
        if (tracks.isEmpty()) {
            throw new IllegalArgumentException("no track");
        }
        for (var other : tracks) {
            if (Objects.requireNonNull(other, "track").codec() != Codec.AAC) {
                throw new IllegalArgumentException(other.codec() + " is not AAC");
            }
        }
        var track = tracks.getFirst();
        var limits = options.limits();
        if (tracks.size() > 1 && track.codecConfig().isPresent() && isCore(track.codecConfig().get())) {
            for (var i = 1; i < tracks.size(); i++) {
                var layer = tracks.get(i).codecConfig();
                if (layer.isPresent() && isScalable(layer.get())) {
                    // TODO: the CELP (object types 8 and 24) and TwinVQ (7 and 21) core layers of scalable AAC need
                    //  decoders of those object types, which Smalt does not have.
                    throw new MediaException.Unsupported("aac scalable core",
                            "scalable AAC over a CELP or TwinVQ core layer is not supported");
                }
            }
        }
        if (track.codec() != Codec.AAC) {
            throw new IllegalArgumentException(track.codec() + " is not AAC");
        }
        try {
            if (track.codecConfig().isPresent() && AudioSpecificConfig.objectTypeOf(track.codecConfig().get()) == 42) {
                this.config = null;
                this.decoder = null;
                this.surroundTrack = -1;
                var target = options.targetLevel();
                var request = new UsacDecoder.DrcRequest(target.isPresent() ? -target.getAsInt() / 4.0f : Float.NaN,
                        effectRequest(options.drcEffect()), options.boostScale(), options.cutScale());
                this.usac = new UsacDecoder(UsacConfig.parseAudioSpecificConfig(track.codecConfig().get()), limits,
                        request);
                return;
            }
            this.usac = null;
            this.config = track.codecConfig().isPresent()
                    ? AudioSpecificConfig.parse(track.codecConfig().get())
                    : implicitConfig(track);
            var configs = new ArrayList<AudioSpecificConfig>();
            configs.add(config);
            if (config.scalable()) {
                for (var i = 1; i < tracks.size(); i++) {
                    var layer = tracks.get(i).codecConfig();
                    if (layer.isEmpty()) {
                        throw new MediaException.Malformed("scalable AAC track without an AudioSpecificConfig");
                    }
                    configs.add(AudioSpecificConfig.parse(layer.get()));
                }
            }
            var fallback = track.layout().orElse(ChannelLayout.STEREO);
            var mode = sbrMode(config, track.sampleRate(), options.spectralBandReplication());
            var stereo = mode != FrameDecoder.SbrMode.OFF && mode != FrameDecoder.SbrMode.DETECT_DOWNSAMPLED
                    && !(config.psSignaled() && !config.ps()) && config.lowDelaySbr() == null;
            var compression = options.compression();
            var dynamicRange = new DynamicRangeControl(compression != AacCompression.NONE,
                    compression == AacCompression.HEAVY, options.cutScale(), options.boostScale(),
                    options.targetLevel().orElse(-1), config.frameLength(), config.lowDelaySbr() != null);
            this.decoder = new FrameDecoder(configs, limits, fallback, mode, stereo, dynamicRange,
                    options.maxLayer().orElse(-1));
            var surroundIndex = -1;
            if (options.mpegSurround() && carriesSurround(config)) {
                MpsConfig surround = null;
                for (var i = 1; i < tracks.size() && surroundIndex < 0 && surround == null; i++) {
                    var other = tracks.get(i).codecConfig();
                    if (other.isPresent() && AudioSpecificConfig.objectTypeOf(other.get())
                            == MpsConfig.AOT_MPEG_SURROUND) {
                        try {
                            var stream = MpsConfig.readAudioSpecificConfig(other.get());
                            surround = stream.config();
                            surroundIndex = stream.embedded() ? -1 : i;
                        } catch (MediaException.Unsupported _) {
                            // the downmix is output and the spatial frames are dropped
                            surroundIndex = MpsConfig.embeddedPayload(other.get()) ? -1 : i;
                            break;
                        }
                    }
                }
                decoder.enableSurround(surround);
            }
            this.surroundTrack = surroundIndex;
        } catch (MediaException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new MediaException.Malformed("invalid AAC configuration", exception);
        }
    }

    /**
     * Returns whether the blocks of a configuration may carry MPEG Surround payloads in fill elements: those of
     * AAC Main, Low Complexity, SSR and LTP, with or without spectral band replication.
     *
     * @param config the stream configuration
     * @return {@code true} for the object types whose raw data blocks have fill elements
     */
    private static boolean carriesSurround(AudioSpecificConfig config) {
        return !config.errorResilient() && !config.bsac() && !config.scalable();
    }

    /**
     * Returns whether a configuration describes a CELP or TwinVQ layer, the core layers of scalable AAC.
     *
     * @param config the {@code AudioSpecificConfig}
     * @return {@code true} for object types 7, 8, 21 and 24
     */
    private static boolean isCore(byte[] config) {
        var type = AudioSpecificConfig.objectTypeOf(config);
        return type == 7 || type == 8 || type == 21 || type == 24;
    }

    /**
     * Returns whether a configuration describes a scalable AAC layer.
     *
     * @param config the {@code AudioSpecificConfig}
     * @return {@code true} for object types 6 and 20
     */
    private static boolean isScalable(byte[] config) {
        var type = AudioSpecificConfig.objectTypeOf(config);
        return type == AudioSpecificConfig.AOT_SCALABLE || type == AudioSpecificConfig.AOT_ER_SCALABLE;
    }

    /**
     * Packs the effect request of the MPEG-D DRC decoder: the requested effect type, followed by the fall-backs of
     * ISO/IEC 23003-4 Annex E.2.2 for the first six types, one per 4 bits from the least significant.
     *
     * @param effect the requested effect
     * @return the packed effect types
     */
    private static long effectRequest(AacDrcEffect effect) {
        int[] types = switch (effect) {
            case NONE -> new int[]{0};
            case NIGHT -> new int[]{1, 6, 2, 3, 4, 5};
            case NOISY -> new int[]{2, 6, 1, 3, 4, 5};
            case LIMITED -> new int[]{3, 6, 1, 2, 4, 5};
            case LOW_LEVEL -> new int[]{4, 6, 2, 1, 3, 5};
            case DIALOG -> new int[]{5, 6, 1, 2, 3, 4};
            case GENERAL_COMPRESSION -> new int[]{6, 1, 2, 3, 4, 5};
            case EXPANDED -> new int[]{7};
            case ARTISTIC -> new int[]{8};
        };
        var packed = 0L;
        for (var i = 0; i < types.length; i++) {
            packed |= (long) types[i] << (4 * i);
        }
        return packed;
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
        if (!enabled || config.errorResilient() || config.scalable() || (config.sbrSignaled() && !config.sbr())) {
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
                configuration, null, 1024, false, false, 0, false, false, 0, 0, null, 0, -1);
    }

    /**
     * {@inheritDoc}
     *
     * <p>With spectral band replication at dual rate this is twice the core rate. When the
     * decision is left to the first packet, this is the core rate until then.
     */
    @Override
    public int sampleRate() {
        return usac != null ? usac.sampleRate() : decoder.sampleRate();
    }

    /**
     * {@inheritDoc}
     *
     * <p>When the configuration leaves the layout to the bitstream, this is the track's declared
     * layout (stereo when it declares none) until the first packet is decoded. A mono core with
     * spectral band replication reports stereo (see the class description). An MPEG Surround upmix reports
     * its own layout while its blocks are upmixed: from the start for a separate elementary stream, from the
     * first block whose fill elements carry the configuration for embedded side information. A block that is not
     * upmixed (before the first independent spatial frame, or under a configuration the decoder does not support)
     * reports the downmix layout.
     */
    @Override
    public ChannelLayout layout() {
        return usac != null ? usac.layout() : decoder.layout();
    }

    /**
     * {@return the number of samples per channel each raw data block decodes to: 1024, or 960 when
     * the configuration sets the frame length flag (512 or 480 for AAC-LD and AAC-ELD), doubled
     * when spectral band replication doubles the rate}
     */
    public int frameLength() {
        return usac != null ? usac.frameLength() : decoder.frameLength();
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
        return config != null && config.sbr();
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
        return config != null && config.epConfig() == 1;
    }

    /**
     * {@return whether the track's configuration signals parametric stereo (HE-AAC v2)}
     */
    public boolean parametricStereoSignaled() {
        return config != null && config.ps();
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
        return usac != null ? usac.sbrActive() : decoder.sbrActive();
    }

    /**
     * {@inheritDoc}
     *
     * <p>A packet holding several {@code raw_data_block}s back to back, as a CRC-less ADTS frame
     * with more than one block does, yields one block of samples per raw data block. A decoder built with
     * a separate MPEG Surround elementary stream decodes the packet as a block whose spatial frame is
     * missing: the upmix keeps the parameters of the last frame, and the downmix is the output before the
     * first one ({@link #decode(List, Consumer)} takes both access units). An exception thrown by
     * {@code output} propagates unchanged.
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
        if (usac != null) {
            ConsumerFailure.<AudioBuffer>run("USAC packet", output, emit -> usac.decode(data, 0, data.length, emit));
            return;
        }
        ConsumerFailure.<AudioBuffer>run("AAC packet", output, emit -> decoder.decode(data, 0, data.length, emit));
    }

    /**
     * Decodes one block of an {@code epConfig} 1 stream from its error sensitivity category
     * instances, one frame of an ER BSAC or scalable AAC stream from the access units of its elementary
     * streams, or one block of a downmix and its MPEG Surround spatial frame from the access units of their
     * elementary streams, and hands the block's samples to {@code output}.
     *
     * <p>{@code instances} holds the access units of one block, in the order of their elementary
     * streams (the track order of an MP4 file): a channel pair element first contributes the
     * instance of its common side information; then every channel, in element order, the instance
     * of its side information, the instance of its RVLC codewords when the configuration codes
     * scalefactors with RVLC, the instance of its TNS data and the instance of its spectral data.
     * Instances after the last one the elements need, which carry extension payloads, are ignored.
     * For ER BSAC, {@code instances} holds the access unit of each elementary stream of the frame, in
     * stream order (the base layer first); they are concatenated into the frame, and the layers of
     * missing trailing streams decode as absent. For scalable AAC, {@code instances} holds the access unit of each
     * layer, or with {@code epConfig} 1 of each category instance of each layer, in the order of the tracks the
     * decoder was built from; the layers from the first missing one on are not decoded. For a separate MPEG
     * Surround elementary stream, {@code instances} holds the access units of the tracks the decoder was built
     * from, the downmix first; units of other tracks are ignored. The packets are read only during the call. An
     * exception thrown by {@code output} propagates unchanged.
     *
     * @param instances the category instances of the block
     * @param output receives the block's samples, valid only during the call
     * @throws NullPointerException if one of the packets of {@code instances} is {@code null}
     * @throws IllegalStateException if the configuration neither stores category instances
     * separately nor is ER BSAC or scalable AAC, nor comes with a separate MPEG Surround elementary stream
     * @throws MediaException.Malformed if an instance is corrupt or missing
     * @throws MediaException.LimitExceeded if the block declares more channels than the
     * configured limits allow
     */
    public void decode(List<Packet> instances, Consumer<AudioBuffer> output) {
        Objects.requireNonNull(instances, "instances");
        Objects.requireNonNull(output, "output");
        if (surroundTrack >= 0) {
            decodeSurround(instances, output);
            return;
        }
        if (!separateCategoryInstances() && (config == null || !(config.bsac() || config.scalable()))) {
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
     * Decodes one block of a downmix whose spatial frames come in a separate MPEG Surround elementary stream.
     *
     * @param units the access unit of each track the decoder was built from, in track order
     * @param output receives the block's samples
     */
    private void decodeSurround(List<Packet> units, Consumer<AudioBuffer> output) {
        if (units.size() <= surroundTrack) {
            throw new MediaException.Malformed("MPEG Surround block lacks the access unit of its spatial frame");
        }
        var core = Objects.requireNonNull(units.getFirst(), "instance").data();
        var spatial = Objects.requireNonNull(units.get(surroundTrack), "instance").data();
        ConsumerFailure.<AudioBuffer>run("AAC packet", output, emit -> {
            decoder.addSurroundFrame(spatial, 0, spatial.length);
            decoder.decode(core, 0, core.length, emit);
        });
    }

    /**
     * {@inheritDoc}
     *
     * @return {@code true}, except for a USAC stream (object type 42), which does not conceal
     */
    @Override
    public boolean supportsConcealment() {
        return usac == null;
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
     * stereo keeps its last parameters; with MPEG Surround, the block is upmixed with the parameters of the last
     * spatial frame, and so are the blocks after the loss until a spatial frame whose {@code bsIndependencyFlag} is
     * set. Before the first block, and after the channel layout changed, the span is silence. A USAC
     * stream (object type 42) does not conceal.
     *
     * @implNote This implementation substitutes spectra in the manner of the informative error concealment of
     * ISO/IEC 14496-3 but without its interpolation toward the next good block, which would delay the output by a
     * block.
     * @throws IllegalArgumentException if {@code samples} is not positive
     * @throws MediaException.Unsupported if the stream is USAC ({@code "usac concealment"})
     */
    @Override
    public int conceal(int samples, Consumer<AudioBuffer> output) {
        Checks.positive("samples", samples);
        Objects.requireNonNull(output, "output");
        if (usac != null) {
            // TODO: USAC needs concealment of its own (FD spectra, LPD excitation, eSBR, MPS212 and DRC state); its
            //  decoder keeps no history of the last good frame yet.
            throw new MediaException.Unsupported("usac concealment", "USAC streams cannot conceal lost packets");
        }
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
     * of a frame that never comes, so this emits nothing, except for a USAC stream decoded through
     * the MPEG-D DRC decoder: this emits the samples its delay still holds.
     */
    @Override
    public void flush(Consumer<AudioBuffer> output) {
        Objects.requireNonNull(output, "output");
        if (usac != null) {
            ConsumerFailure.<AudioBuffer>run("USAC flush", output, usac::flush);
        }
    }
}
