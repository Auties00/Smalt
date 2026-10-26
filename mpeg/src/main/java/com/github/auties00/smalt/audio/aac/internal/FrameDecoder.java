package com.github.auties00.smalt.audio.aac.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.audio.AudioBuffer;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.audio.aac.internal.sbr.SbrElement;

import com.github.auties00.smalt.internal.limits.LimitChecks;
import java.util.Arrays;
import java.util.function.Consumer;

/**
 * Decodes AAC {@code raw_data_block}s of the Main, Low Complexity, SSR and LTP object types, with
 * their spectral band replication and parametric stereo extensions, and the {@code er_raw_data_block}s
 * of ER AAC LC, ER AAC LTP, AAC-LD and AAC-ELD, into planar float samples.
 *
 * <p>Each block is decoded in two phases. The first parses every syntactic element in order,
 * inverse-quantizes the channel streams and applies the element-level tools (M/S stereo,
 * perceptual noise substitution, intensity stereo). The second assigns the parsed channels to
 * output channels through the active {@link ChannelMapping} and, channel by channel, adds the
 * dependently switched coupling channels that apply before TNS, applies TNS, adds those that
 * apply after it, runs the synthesis filterbank into the output buffer (whose channels are in
 * WAVE order) and adds the independently switched coupling channels. When a block's elements
 * do not fit the configured mapping, the mapping is replaced by one inferred from the block
 * and the filterbank memory is reset.
 *
 * <p>Data stream elements and fill elements are skipped, except the spectral band replication
 * payloads of fill elements while the {@link SbrMode} decodes them; program configuration
 * elements replace the mapping. When spectral band replication is decoded, every channel element
 * runs through an {@link SbrElement}: the filterbank writes the core samples of each coded
 * channel to a scratch buffer, and the element turns them into the output samples, at twice the
 * core rate unless the mode is downsampled. A single channel element that is the whole layout
 * outputs two channels when parametric stereo is enabled.
 *
 * <p>The object type adds its tools: AAC Main runs a {@link FrequencyPredictor} bank per element
 * channel after M/S decoding and noise substitution and before intensity stereo, AAC LTP and ER AAC
 * LTP a {@link LongTermPredictor} per coded channel before TNS, and AAC SSR synthesizes through the
 * {@link SsrSynthesis} filterbank. An error resilient block holds the channel elements its channel
 * configuration lists, without element identifiers, followed by extension payloads, which are
 * skipped, except the low delay spectral band replication of AAC-ELD, which follows the elements; it
 * fills the whole packet. With {@code epConfig} 1 the block comes as one access unit per error
 * sensitivity category instance, which {@link #decodeInstances} reads in turn. AAC-LD runs the 512- or
 * 480-sample transform with the sine or the low overlap window and long term prediction; AAC-ELD runs
 * the {@link LowDelayFilterbank}, and its channel pairs always share their {@code ics_info}.
 */
public final class FrameDecoder {
    /**
     * The largest number of raw data blocks a packet may hold; ADTS frames carry at most four.
     */
    public static final int MAX_BLOCKS = 8;

    /**
     * The seed of the noise generator.
     */
    private static final int NOISE_SEED = 0x1F2E3D4C;

    /**
     * The number of concealed blocks in a row that are heard; later ones are silent.
     */
    private static final int AUDIBLE_CONCEALMENT = 4;

    /**
     * The seed of the generator of the signs of concealed spectra.
     */
    private static final int CONCEALMENT_SEED = 0x6C078965;

    /**
     * The amplitude ratio of a long-window line to a short-window line of the same signal.
     */
    private static final float SHORT_TO_LONG = (float) Math.sqrt(8);

    /**
     * The extension type of a fill element holding spectral band replication data.
     */
    private static final int EXT_SBR_DATA = 13;

    /**
     * The extension type of a fill element holding spectral band replication data with a CRC.
     */
    private static final int EXT_SBR_DATA_CRC = 14;

    /**
     * The dynamic range control, or {@code null} when it changes no sample.
     */
    private final DynamicRangeControl dynamicRange;

    /**
     * The QMF gains of the channels of the element being replicated.
     */
    private final float[][] elementGains = new float[2][];

    /**
     * Selects how a decoder treats spectral band replication.
     */
    public enum SbrMode {
        /**
         * Decodes the Low Complexity core only and skips the extension payloads.
         */
        OFF,

        /**
         * Decodes spectral band replication and outputs twice the core rate.
         */
        DUAL_RATE,

        /**
         * Decodes spectral band replication with the downsampled synthesis filterbank, keeping
         * the core rate.
         */
        DOWNSAMPLED,

        /**
         * Decides on the first raw data block: {@link #DUAL_RATE} when it carries spectral band
         * replication data, {@link #OFF} otherwise.
         */
        DETECT_DUAL_RATE,

        /**
         * Decides on the first raw data block: {@link #DOWNSAMPLED} when it carries spectral
         * band replication data, {@link #OFF} otherwise.
         */
        DETECT_DOWNSAMPLED
    }

    /**
     * The number of samples per frame.
     */
    private final int frameLength;

    /**
     * The sampling table index.
     */
    private final int tableIndex;

    /**
     * The core audio object type.
     */
    private final int objectType;

    /**
     * Whether blocks use the error resilient syntax.
     */
    private final boolean errorResilient;

    /**
     * The element types of an error resilient block, in order, or {@code null} for the other
     * object types.
     */
    private final int[] errorResilientElements;

    /**
     * Whether channels carry long term prediction.
     */
    private final boolean longTermPrediction;

    /**
     * Whether the object type is AAC-ELD.
     */
    private final boolean enhancedLowDelay;

    /**
     * The error resilience tools of the configuration.
     */
    private final int resilience;

    /**
     * The AAC-ELD filterbank, or {@code null} for the other object types.
     */
    private final LowDelayFilterbank lowDelayFilterbank;

    /**
     * The low delay spectral band replication of an AAC-ELD configuration, or {@code null}.
     */
    private final AudioSpecificConfig.LowDelaySbr lowDelaySbr;

    /**
     * Whether each error sensitivity category instance of a block comes in its own access unit
     * ({@code epConfig} 1).
     */
    private final boolean separateCategories;

    /**
     * Whether channel streams carry RVLC codewords, which form a category instance of their own.
     */
    private final boolean reversibleScalefactors;

    /**
     * The readers of the category instances of the current block, reused block after block.
     */
    private AacBitReader[] instances = new AacBitReader[0];

    /**
     * The number of category instances of the current block.
     */
    private int instanceCount;

    /**
     * The index of the next category instance to read.
     */
    private int instanceCursor;

    /**
     * The AAC-ELD filterbank memory of each coded channel, empty for the other object types.
     */
    private float[][] lowDelayStates = new float[0][];

    /**
     * The SSR filterbank, or {@code null} for the other object types.
     */
    private final SsrSynthesis ssrSynthesis;

    /**
     * The AAC Main predictor bank of each channel of each element, indexed
     * {@code (type * 16 + tag) * 2 + channel}, or {@code null} until the channel is first predicted.
     */
    private final FrequencyPredictor[] predictors = new FrequencyPredictor[4 * 16 * 2];

    /**
     * The long term predictor of each coded channel, empty for the other object types.
     */
    private LongTermPredictor[] longTermPredictors = new LongTermPredictor[0];

    /**
     * The SSR filterbank memory of each coded channel, empty for the other object types.
     */
    private SsrSynthesis.State[] ssrStates = new SsrSynthesis.State[0];

    /**
     * The core sampling rate.
     */
    private final int coreRate;

    /**
     * The output sampling rate.
     */
    private int sampleRate;

    /**
     * The number of output samples per channel and block.
     */
    private int outputLength;

    /**
     * How spectral band replication is treated; the detecting modes change to their outcome on
     * the first block.
     */
    private SbrMode sbrMode;

    /**
     * Whether a single channel element that is the whole layout outputs two channels through
     * parametric stereo once spectral band replication is decoded.
     */
    private final boolean parametricStereo;

    /**
     * The spectral band replication decoder of each channel element, by position in the block,
     * or {@code null} until the element is first decoded.
     */
    private SbrElement[] sbrElements = new SbrElement[8];

    /**
     * The element type each entry of {@link #sbrElements} was created for.
     */
    private int[] sbrTypes = new int[8];

    /**
     * Whether the current block carried spectral band replication data.
     */
    private boolean sbrInBlock;

    /**
     * The core samples of each coded channel when spectral band replication is decoded.
     */
    private float[][] coreSamples = new float[0][];

    /**
     * The first coded channel of each channel element of the current block.
     */
    private int[] elementChannels = new int[8];

    /**
     * The core sample buffers handed to an element.
     */
    private final float[][] elementCore = new float[2][];

    /**
     * The output planes handed to an element.
     */
    private final float[][] elementOutput = new float[2][];

    /**
     * The resource limits.
     */
    private final MediaLimits limits;

    /**
     * The DSP kernel.
     */
    private final AacKernel kernel;

    /**
     * The synthesis filterbank.
     */
    private final Synthesis synthesis;

    /**
     * The active channel mapping, or {@code null} until one is known.
     */
    private ChannelMapping mapping;

    /**
     * The layout reported while no mapping is known.
     */
    private final ChannelLayout fallbackLayout;

    /**
     * The parsed channel streams of the current block, in parse order.
     */
    private ChannelStream[] streams = new ChannelStream[0];

    /**
     * The number of streams parsed in the current block.
     */
    private int streamCount;

    /**
     * The type of each channel element of the current block.
     */
    private int[] elementTypes = new int[8];

    /**
     * The instance tag of each channel element of the current block.
     */
    private int[] elementTags = new int[8];

    /**
     * The index of the first stream of each channel element of the current block.
     */
    private int[] elementStreams = new int[8];

    /**
     * The number of channel elements of the current block.
     */
    private int elementCount;

    /**
     * The stream assigned to each coded channel.
     */
    private ChannelStream[] channelStreams = new ChannelStream[0];

    /**
     * The overlap memory of each coded channel.
     */
    private float[][] overlaps = new float[0][];

    /**
     * The window shape of the previous frame of each coded channel.
     */
    private int[] previousShapes = new int[0];

    /**
     * The spectrum each coded channel synthesized last, which concealment repeats; a concealed block of short
     * windows leaves its lines interleaved into a long window here.
     */
    private float[][] lastSpectra = new float[0][];

    /**
     * The window sequence each coded channel synthesized last.
     */
    private int[] lastSequences = new int[0];

    /**
     * Whether a block was decoded with the current mapping, so a lost block can be concealed from it.
     */
    private boolean concealable;

    /**
     * The number of blocks concealed since the last decoded one.
     */
    private int concealedRun;

    /**
     * The state of the generator of the signs of concealed spectra.
     */
    private int concealmentNoise = CONCEALMENT_SEED;

    /**
     * The number of channel elements of the last decoded block.
     */
    private int lastElementCount;

    /**
     * The type of each channel element of the last decoded block.
     */
    private int[] lastElementTypes = new int[8];

    /**
     * The instance tag of each channel element of the last decoded block.
     */
    private int[] lastElementTags = new int[8];

    /**
     * The first coded channel of each channel element of the last decoded block.
     */
    private int[] lastElementChannels = new int[8];

    /**
     * The output buffer.
     */
    private AudioBuffer output;

    /**
     * The M/S flags of the channel pair being decoded, indexed like band arrays.
     */
    private final boolean[] msUsed = new boolean[8 * ChannelStream.MAX_BANDS];

    /**
     * The noise generator seed of each band and window of the left channel of the pair being
     * decoded, indexed {@code window * MAX_BANDS + band}.
     */
    private final int[] noiseSeeds = new int[8 * ChannelStream.MAX_BANDS];

    /**
     * The noise generator state.
     */
    private int noiseState = NOISE_SEED;

    /**
     * The coupling channel decoder of each element instance tag, created on first use; each
     * keeps the synthesis memory of its independently switched channel across frames.
     */
    private final CouplingChannel[] couplings = new CouplingChannel[16];

    /**
     * The coupling channels of the current block, in parse order.
     */
    private final CouplingChannel[] frameCouplings = new CouplingChannel[16];

    /**
     * The number of coupling channels in the current block.
     */
    private int couplingCount;

    /**
     * The target streams of the coupling channels of the current block, two slots per target
     * element: left (or single) then right, {@code null} where no channel is coupled.
     */
    private final ChannelStream[][] couplingTargets = new ChannelStream[16][16];

    /**
     * The gain list of each coupling target slot.
     */
    private final int[][] couplingLists = new int[16][16];

    /**
     * Creates a decoder for a configuration.
     *
     * @param config the stream configuration
     * @param limits the resource limits
     * @param fallbackLayout the layout to report while the configuration leaves it to the
     * bitstream and no block was decoded yet
     * @param sbrMode how spectral band replication is treated
     * @param parametricStereo whether a mono layout outputs two channels through parametric
     * stereo once spectral band replication is decoded
     * @throws MediaException.LimitExceeded if the configuration exceeds the limits
     */
    public FrameDecoder(AudioSpecificConfig config, MediaLimits limits, ChannelLayout fallbackLayout, SbrMode sbrMode,
                        boolean parametricStereo, DynamicRangeControl dynamicRange) {
        this.dynamicRange = dynamicRange != null && dynamicRange.active() ? dynamicRange : null;
        this.frameLength = config.frameLength();
        this.tableIndex = config.tableIndex();
        this.objectType = config.objectType();
        this.errorResilient = config.errorResilient();
        this.errorResilientElements = errorResilient ? errorResilientElements(config.channelConfiguration()) : null;
        this.longTermPrediction = config.longTermPrediction();
        this.enhancedLowDelay = config.objectType() == AudioSpecificConfig.AOT_ER_ELD;
        this.resilience = config.resilience();
        this.lowDelaySbr = config.lowDelaySbr();
        this.separateCategories = config.epConfig() == 1;
        this.reversibleScalefactors = (config.resilience() & AudioSpecificConfig.RVLC_SCALEFACTORS) != 0;
        this.coreRate = config.sampleRate();
        this.sbrMode = sbrMode;
        this.parametricStereo = parametricStereo;
        this.sampleRate = sbrMode == SbrMode.DUAL_RATE ? 2 * coreRate : coreRate;
        this.outputLength = sbrMode == SbrMode.DUAL_RATE ? 2 * frameLength : frameLength;
        this.limits = limits;
        this.kernel = AacKernels.INSTANCE;
        this.synthesis = new Synthesis(frameLength, kernel, config.objectType() == AudioSpecificConfig.AOT_ER_LD);
        this.lowDelayFilterbank = enhancedLowDelay ? new LowDelayFilterbank(frameLength, kernel) : null;
        this.ssrSynthesis = objectType == AudioSpecificConfig.AOT_SSR ? new SsrSynthesis(kernel) : null;
        this.fallbackLayout = fallbackLayout;
        if (config.programConfig() != null) {
            useMapping(ChannelMapping.ofProgram(config.programConfig(), limits));
        } else if (config.channelConfiguration() != 0) {
            useMapping(ChannelMapping.ofConfiguration(config.channelConfiguration(), limits));
        }
    }

    /**
     * Returns the output sampling rate.
     *
     * @return the rate in hertz
     */
    public int sampleRate() {
        return sampleRate;
    }

    /**
     * Returns the output layout of the last decoded block, or the configured one before any.
     *
     * @return the layout in WAVE order
     */
    public ChannelLayout layout() {
        if (mapping == null) {
            return stereoOutput(fallbackLayout.channels() == 1) ? ChannelLayout.STEREO : fallbackLayout;
        }
        return stereoOutput(mapping.channels() == 1) ? ChannelLayout.STEREO : mapping.layout();
    }

    /**
     * Returns whether a layout outputs two channels through parametric stereo.
     *
     * @param mono whether the coded layout is a single channel
     * @return {@code true} when a mono layout is decoded as stereo
     */
    private boolean stereoOutput(boolean mono) {
        return mono && parametricStereo && sbrActive();
    }

    /**
     * Returns whether spectral band replication is decoded.
     *
     * @return {@code true} in the dual-rate and downsampled modes
     */
    public boolean sbrActive() {
        return sbrMode == SbrMode.DUAL_RATE || sbrMode == SbrMode.DOWNSAMPLED;
    }

    /**
     * Returns the number of samples per channel each block decodes to.
     *
     * @return the frame length of the configuration (1024, 960, 512 or 480), or twice it at dual rate
     */
    public int frameLength() {
        return outputLength;
    }

    /**
     * Decodes every raw data block of a packet and hands each decoded block to {@code output}.
     *
     * <p>A packet normally holds one {@code raw_data_block}; a CRC-less ADTS frame with several
     * blocks arrives as one packet holding them back to back, each ending with its end marker
     * and byte alignment. Blocks are decoded until the packet is exhausted; trailing zero bytes
     * after a block are taken as padding. Blocks decoded before a corrupt one have already been
     * handed over when the exception is raised.
     *
     * @param data the array holding the packet
     * @param offset the index of the first byte
     * @param length the number of bytes
     * @param output receives each block's samples, valid only during the call
     * @throws MediaException.Malformed if a block is corrupt, the packet is empty, or it
     * holds more than {@link #MAX_BLOCKS} blocks
     * @throws MediaException.LimitExceeded if a block declares too many channels
     * @throws IllegalStateException if the configuration sets {@code epConfig} 1
     */
    public void decode(byte[] data, int offset, int length, Consumer<AudioBuffer> output) {
        if (separateCategories) {
            throw new IllegalStateException("the configuration stores category instances separately");
        }
        if (length == 0) {
            throw new MediaException.Malformed("empty AAC packet");
        }
        var reader = new AacBitReader(data, offset, length);
        for (var block = 0; ; block++) {
            if (block == MAX_BLOCKS) {
                throw new MediaException.Malformed("AAC packet holds more than " + MAX_BLOCKS + " raw data blocks");
            }
            output.accept(decodeBlock(reader));
            reader.alignToByte();
            if (onlyPadding(data, offset + length, reader)) {
                return;
            }
        }
    }

    /**
     * Decodes one error resilient block whose error sensitivity category instances come in separate
     * access units, as {@code epConfig} 1 stores them, and hands the decoded block to {@code output}.
     *
     * <p>Each channel element contributes its instances in order: a channel pair first the instance
     * of its common side information, then every channel the instance of its side information (the
     * element tag of a single channel element included), of its RVLC codewords when the configuration
     * codes them, of its TNS data and of its spectral data. Instances beyond the last one the
     * elements read, which hold extension payloads, are skipped.
     *
     * @param data the category instances, in the order of their elementary streams, in its first
     * {@code count} entries; read only during the call
     * @param count the number of category instances
     * @param output receives the block's samples, valid only during the call
     * @throws IllegalStateException if the configuration does not set {@code epConfig} 1
     * @throws MediaException.Malformed if an instance is corrupt or missing
     * @throws MediaException.LimitExceeded if the block declares too many channels
     */
    public void decodeInstances(byte[][] data, int count, Consumer<AudioBuffer> output) {
        if (!separateCategories) {
            throw new IllegalStateException("the configuration does not store category instances separately");
        }
        if (instances.length < count) {
            var grown = Arrays.copyOf(instances, count);
            for (var i = instances.length; i < count; i++) {
                grown[i] = new AacBitReader(new byte[0]);
            }
            instances = grown;
        }
        for (var i = 0; i < count; i++) {
            var instance = data[i];
            instances[i].reset(instance, 0, instance.length);
        }
        instanceCount = count;
        instanceCursor = 0;
        if (count == 0) {
            throw new MediaException.Malformed("AAC block without category instances");
        }
        output.accept(decodeBlock(instances[0]));
    }

    /**
     * Returns the reader of the next category instance of the current block.
     *
     * @return the reader
     * @throws MediaException.Malformed if the block has no instance left
     */
    private AacBitReader nextInstance() {
        if (instanceCursor == instanceCount) {
            throw new MediaException.Malformed("AAC block lacks an error sensitivity category instance");
        }
        return instances[instanceCursor++];
    }

    /**
     * Parses the channel stream of one channel, from one source or, with {@code epConfig} 1, from
     * its category instances after the side information instance.
     *
     * @param stream the channel stream that receives the parsed channel
     * @param main the source of the side information
     * @param commonWindow whether {@code ics_info} was already read for a channel pair
     * @param intensityAllowed whether intensity stereo codebooks may appear
     * @throws MediaException.Malformed if a category instance is truncated or missing
     */
    private void parseChannel(ChannelStream stream, AacBitReader main, boolean commonWindow, boolean intensityAllowed) {
        if (!separateCategories) {
            stream.parse(main, commonWindow, intensityAllowed);
            return;
        }
        var reversible = reversibleScalefactors ? nextInstance() : main;
        var tns = nextInstance();
        var spectral = nextInstance();
        stream.parse(main, reversible, tns, spectral, commonWindow, intensityAllowed);
        main.checkNotOverrun();
        reversible.checkNotOverrun();
        tns.checkNotOverrun();
        spectral.checkNotOverrun();
    }

    /**
     * Returns whether the reader stands at the end of the packet or before zero bytes only.
     *
     * @param data the packet array
     * @param end the index after the packet's last byte
     * @param reader the reader, byte-aligned
     * @return {@code true} when no further block follows
     */
    private static boolean onlyPadding(byte[] data, int end, AacBitReader reader) {
        var remaining = reader.bitsLeft();
        if (remaining < 8) {
            return true;
        }
        for (var index = end - (int) (remaining >>> 3); index < end; index++) {
            if (data[index] != 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * Decodes one raw data block from the reader's position up to and including its end marker.
     *
     * @param reader the bit source
     * @return the decoded samples, valid until the next block
     */
    private AudioBuffer decodeBlock(AacBitReader reader) {
        streamCount = 0;
        elementCount = 0;
        couplingCount = 0;
        sbrInBlock = false;
        ChannelMapping announced = null;
        if (errorResilient) {
            readErrorResilientElements(reader);
        } else {
            announced = readElements(reader);
        }
        if (sbrMode == SbrMode.DETECT_DUAL_RATE || sbrMode == SbrMode.DETECT_DOWNSAMPLED) {
            decideSbr();
        }
        if (announced != null && (mapping == null || !announced.layout().equals(mapping.layout()))) {
            useMapping(announced);
        }
        if (elementCount == 0) {
            return silence();
        }
        if (mapping == null || !mapping.matches(elementTypes, elementTags, elementCount)) {
            useMapping(ChannelMapping.infer(Arrays.copyOf(elementTypes, elementCount), limits));
        }
        assignChannels();
        prepareCouplings();
        var sbr = sbrActive();
        for (var channel = 0; channel < channelStreams.length; channel++) {
            var stream = channelStreams[channel];
            if (longTermPrediction) {
                longTermPredictors[channel].predict(stream, overlaps[channel], previousShapes[channel], synthesis);
            }
            if (couplingCount != 0) {
                coupleSpectrum(stream, false);
            }
            stream.applyTns();
            if (couplingCount != 0) {
                coupleSpectrum(stream, true);
            }
            if (dynamicRange != null && !sbr) {
                dynamicRange.applySpectrum(channel, stream.spectrum());
            }
            System.arraycopy(stream.spectrum(), 0, lastSpectra[channel], 0, frameLength);
            lastSequences[channel] = stream.windowSequence();
            var plane = sbr ? coreSamples[channel] : output.plane(mapping.outputIndex(channel));
            synthesizeChannel(channel, stream, plane);
            if (couplingCount != 0) {
                coupleTime(stream, plane);
            }
            if (longTermPrediction) {
                longTermPredictors[channel].update(plane, 0);
            }
        }
        if (sbr) {
            applySbr();
        }
        keepElements();
        output.setFrames(outputLength);
        return output;
    }

    /**
     * Runs the synthesis filterbank of the object type over a channel's spectrum.
     *
     * @param channel the coded channel
     * @param stream the channel's stream
     * @param plane receives the channel's core samples
     */
    private void synthesizeChannel(int channel, ChannelStream stream, float[] plane) {
        if (ssrSynthesis != null) {
            ssrSynthesis.synthesize(stream, ssrStates[channel], plane, 0);
        } else if (lowDelayFilterbank != null) {
            lowDelayFilterbank.synthesize(stream.spectrum(), lowDelayStates[channel], plane, 0);
        } else {
            synthesis.synthesize(stream.spectrum(), stream.windowSequence(), previousShapes[channel],
                    stream.windowShape(), overlaps[channel], plane, 0);
        }
        previousShapes[channel] = stream.windowShape();
    }

    /**
     * Keeps the channel elements of the block just decoded for concealing the blocks after it.
     */
    private void keepElements() {
        if (lastElementTypes.length < elementCount) {
            lastElementTypes = new int[elementTypes.length];
            lastElementTags = new int[elementTypes.length];
            lastElementChannels = new int[elementTypes.length];
        }
        System.arraycopy(elementTypes, 0, lastElementTypes, 0, elementCount);
        System.arraycopy(elementTags, 0, lastElementTags, 0, elementCount);
        System.arraycopy(elementChannels, 0, lastElementChannels, 0, elementCount);
        lastElementCount = elementCount;
        concealable = true;
        concealedRun = 0;
    }

    /**
     * Conceals one lost block from the blocks decoded before it.
     *
     * <p>Each coded channel repeats the spectrum it synthesized last with random signs: at full amplitude for the
     * first block lost in a row, halved for each further one, and silent from the fifth on. A channel whose last
     * block had eight short windows is concealed with a long stop window whose lines interleave the short windows'
     * lines, scaled to the long transform, and a channel whose last block had a long start window with a long stop
     * window, so the window sequence joins the last block's; other channels use a long window. AAC Main predictors
     * and long term prediction histories continue from the concealed signal. With spectral band replication, the
     * high band repeats the last envelope, 6 dB lower for each further block, until the core falls silent. Before
     * the first block, or after a change of channel layout, the block is silent.
     *
     * @return the concealed samples, valid until the next block
     */
    public AudioBuffer conceal() {
        if (!concealable) {
            return silence();
        }
        concealedRun = Math.min(concealedRun + 1, AUDIBLE_CONCEALMENT + 1);
        var gain = concealedRun <= AUDIBLE_CONCEALMENT ? 1.0f / (1 << (concealedRun - 1)) : 0;
        streamCount = 0;
        couplingCount = 0;
        sbrInBlock = false;
        elementCount = lastElementCount;
        System.arraycopy(lastElementTypes, 0, elementTypes, 0, elementCount);
        System.arraycopy(lastElementTags, 0, elementTags, 0, elementCount);
        System.arraycopy(lastElementChannels, 0, elementChannels, 0, elementCount);
        var state = concealmentNoise;
        for (var channel = 0; channel < channelStreams.length; channel++) {
            var stream = channelStreams[channel];
            var source = lastSpectra[channel];
            var target = stream.spectrum();
            var previous = lastSequences[channel];
            int sequence;
            if (previous == ChannelStream.EIGHT_SHORT_SEQUENCE && ssrSynthesis == null) {
                var windowLength = frameLength / 8;
                for (var k = 0; k < frameLength; k++) {
                    target[k] = SHORT_TO_LONG * source[(k & 7) * windowLength + (k >> 3)];
                }
                System.arraycopy(target, 0, source, 0, frameLength);
                sequence = ChannelStream.LONG_STOP_SEQUENCE;
            } else {
                sequence = previous == ChannelStream.EIGHT_SHORT_SEQUENCE ? previous
                        : previous == ChannelStream.LONG_START_SEQUENCE ? ChannelStream.LONG_STOP_SEQUENCE
                        : ChannelStream.ONLY_LONG_SEQUENCE;
            }
            for (var k = 0; k < frameLength; k++) {
                state = state * 1664525 + 1013904223;
                var value = gain * source[k];
                target[k] = state < 0 ? -value : value;
            }
            stream.prepareConcealment(sequence, previousShapes[channel]);
            lastSequences[channel] = sequence;
        }
        concealmentNoise = state;
        for (var element = 0; element < elementCount; element++) {
            var type = elementTypes[element];
            var first = elementChannels[element];
            predict(channelStreams[first], type, elementTags[element], 0);
            if (type == ElementId.CPE) {
                predict(channelStreams[first + 1], type, elementTags[element], 1);
            }
        }
        var sbr = sbrActive();
        for (var channel = 0; channel < channelStreams.length; channel++) {
            var plane = sbr ? coreSamples[channel] : output.plane(mapping.outputIndex(channel));
            synthesizeChannel(channel, channelStreams[channel], plane);
            if (longTermPrediction) {
                longTermPredictors[channel].update(plane, 0);
            }
        }
        if (sbr) {
            for (var element = 0; element < elementCount; element++) {
                sbrElement(element, elementTypes[element]).conceal(gain != 0, concealedRun > 1);
            }
            applySbr();
        }
        output.setFrames(outputLength);
        return output;
    }

    /**
     * Reads the syntactic elements of a {@code raw_data_block} up to its end marker.
     *
     * @param reader the bit source
     * @return the mapping of the last program configuration element, or {@code null} when the block
     * holds none
     */
    private ChannelMapping readElements(AacBitReader reader) {
        ChannelMapping announced = null;
        while (true) {
            var id = reader.read(3);
            if (id == ElementId.END) {
                return announced;
            }
            switch (id) {
                case ElementId.SCE, ElementId.LFE -> decodeSingle(reader, id);
                case ElementId.CPE -> decodePair(reader);
                case ElementId.CCE -> decodeCoupling(reader);
                case ElementId.DSE -> readDataStream(reader);
                case ElementId.PCE -> announced = ChannelMapping.ofProgram(ProgramConfig.parse(reader), limits);
                case ElementId.FIL -> readFill(reader);
                default -> throw new AssertionError(id);
            }
            reader.checkNotOverrun();
        }
    }

    /**
     * Returns the element types of an error resilient block of a channel configuration
     * (ISO/IEC 14496-3 Table 4.19).
     *
     * @param channelConfiguration the channel configuration
     * @return the element types, in order
     * @throws MediaException.Malformed if the configuration is reserved in error resilient payloads
     */
    private static int[] errorResilientElements(int channelConfiguration) {
        return switch (channelConfiguration) {
            case 1 -> new int[]{ElementId.SCE};
            case 2 -> new int[]{ElementId.CPE};
            case 3 -> new int[]{ElementId.SCE, ElementId.CPE};
            case 4 -> new int[]{ElementId.SCE, ElementId.CPE, ElementId.SCE};
            case 5 -> new int[]{ElementId.SCE, ElementId.CPE, ElementId.CPE};
            case 6 -> new int[]{ElementId.SCE, ElementId.CPE, ElementId.CPE, ElementId.LFE};
            case 7 -> new int[]{ElementId.SCE, ElementId.CPE, ElementId.CPE, ElementId.CPE, ElementId.LFE};
            default -> throw new MediaException.Malformed("error resilient AAC reserves channel configuration "
                    + channelConfiguration);
        };
    }

    /**
     * Reads the channel elements of an error resilient block, in the order the channel
     * configuration lists them, and skips the extension payloads that fill the rest of the packet.
     *
     * @param reader the bit source
     * @throws MediaException.Malformed if the elements are truncated
     */
    private void readErrorResilientElements(AacBitReader reader) {
        for (var type : errorResilientElements) {
            if (type == ElementId.CPE) {
                decodePair(reader);
            } else {
                decodeSingle(reader, type);
            }
            reader.checkNotOverrun();
        }
        if (separateCategories) {
            return;
        }
        if (lowDelaySbr != null && sbrActive()) {
            for (var element = 0; element < elementCount; element++) {
                if (elementTypes[element] != ElementId.LFE) {
                    sbrInBlock = true;
                    sbrElement(element, elementTypes[element]).parseLowDelay(reader, Math.max(0, reader.bitsLeft()),
                            lowDelaySbr.crc());
                }
            }
        }
        if (dynamicRange != null && reader.bitsLeft() >= 8) {
            readErrorResilientExtensions(reader);
        }
        reader.skip(Math.max(0, reader.bitsLeft()));
    }

    /**
     * Settles a detecting {@link SbrMode} after the first block was parsed.
     */
    private void decideSbr() {
        if (sbrInBlock) {
            sbrMode = sbrMode == SbrMode.DETECT_DUAL_RATE ? SbrMode.DUAL_RATE : SbrMode.DOWNSAMPLED;
        } else {
            sbrMode = SbrMode.OFF;
            Arrays.fill(sbrElements, null);
        }
        sampleRate = sbrMode == SbrMode.DUAL_RATE ? 2 * coreRate : coreRate;
        outputLength = sbrMode == SbrMode.DUAL_RATE ? 2 * frameLength : frameLength;
        if (mapping != null) {
            var current = mapping;
            mapping = null;
            useMapping(current);
        }
    }

    /**
     * Runs every channel element of the block through its spectral band replication decoder,
     * from the core samples to the output planes.
     */
    private void applySbr() {
        var stereo = stereoOutput(mapping.channels() == 1);
        for (var element = 0; element < elementCount; element++) {
            var type = elementTypes[element];
            var sbr = sbrElement(element, type);
            var channel = elementChannels[element];
            var pair = type == ElementId.CPE;
            elementCore[0] = coreSamples[channel];
            elementCore[1] = pair ? coreSamples[channel + 1] : null;
            elementOutput[0] = output.plane(mapping.outputIndex(channel));
            if (pair) {
                elementOutput[1] = output.plane(mapping.outputIndex(channel + 1));
            } else if (stereo) {
                elementOutput[1] = output.plane(1);
            } else {
                elementOutput[1] = null;
            }
            float[][] gains = null;
            if (dynamicRange != null) {
                gains = elementGains;
                gains[0] = dynamicRange.advanceQmf(channel, channelStreams[channel].windowSequence()
                        == ChannelStream.EIGHT_SHORT_SEQUENCE);
                gains[1] = pair ? dynamicRange.advanceQmf(channel + 1, channelStreams[channel + 1].windowSequence()
                        == ChannelStream.EIGHT_SHORT_SEQUENCE) : null;
            }
            sbr.decode(elementCore, elementOutput, stereo, gains);
        }
    }

    /**
     * Returns the spectral band replication decoder of a channel element, creating it when the
     * element at that position is new or changed type.
     *
     * @param element the element's position in the block
     * @param type the element type
     * @return the decoder
     */
    private SbrElement sbrElement(int element, int type) {
        if (element >= sbrElements.length) {
            sbrElements = Arrays.copyOf(sbrElements, element + 8);
            sbrTypes = Arrays.copyOf(sbrTypes, element + 8);
        }
        var current = sbrElements[element];
        if (current == null || sbrTypes[element] != type) {
            current = new SbrElement(type == ElementId.CPE ? 2 : 1, coreRate, frameLength,
                    sbrMode == SbrMode.DOWNSAMPLED || sbrMode == SbrMode.DETECT_DOWNSAMPLED,
                    parametricStereo && type == ElementId.SCE, lowDelaySbr != null);
            if (lowDelaySbr != null && type != ElementId.LFE) {
                var header = 0;
                for (var previous = 0; previous < element; previous++) {
                    header += elementTypes[previous] == ElementId.LFE ? 0 : 1;
                }
                if (header < lowDelaySbr.headerOffsets().length) {
                    current.configure(lowDelaySbr.config(), lowDelaySbr.headerOffsets()[header]);
                }
            }
            sbrElements[element] = current;
            sbrTypes[element] = type;
        }
        return current;
    }

    /**
     * Returns a block of silence in the current layout, for blocks without channel elements.
     *
     * @return the output buffer, zeroed
     */
    private AudioBuffer silence() {
        if (output == null) {
            useMapping(ChannelMapping.infer(new int[]{
                    fallbackLayout.channels() == 1 ? ElementId.SCE : ElementId.CPE}, limits));
        }
        for (var plane : output.planes()) {
            Arrays.fill(plane, 0, outputLength, 0);
        }
        output.setFrames(outputLength);
        return output;
    }

    /**
     * Switches to a mapping, resetting the filterbank memory when the channel count changes.
     *
     * @param next the new mapping
     */
    private void useMapping(ChannelMapping next) {
        var channels = next.channels();
        if (mapping == null || mapping.channels() != channels || !mapping.layout().equals(next.layout())) {
            var predictionBytes = longTermPrediction ? 4L * (2048 + 10L * frameLength)
                    : ssrSynthesis != null ? 4L * SsrSynthesis.STATE_FLOATS
                    : lowDelayFilterbank != null ? 4L * LowDelayFilterbank.stateLength(frameLength) : 0;
            LimitChecks.checkAllocation(limits, (long) channels * (frameLength * 8L + outputLength * 4L + predictionBytes));
            overlaps = new float[channels][frameLength];
            previousShapes = new int[channels];
            lastSpectra = new float[channels][frameLength];
            lastSequences = new int[channels];
            concealable = false;
            if (longTermPrediction) {
                longTermPredictors = new LongTermPredictor[channels];
                for (var channel = 0; channel < channels; channel++) {
                    longTermPredictors[channel] = new LongTermPredictor(frameLength, kernel,
                            objectType == AudioSpecificConfig.AOT_ER_LD);
                }
            }
            if (ssrSynthesis != null) {
                ssrStates = new SsrSynthesis.State[channels];
                for (var channel = 0; channel < channels; channel++) {
                    ssrStates[channel] = new SsrSynthesis.State();
                }
            }
            if (lowDelayFilterbank != null) {
                lowDelayStates = new float[channels][LowDelayFilterbank.stateLength(frameLength)];
            }
            channelStreams = new ChannelStream[channels];
            coreSamples = new float[channels][frameLength];
            var layout = stereoOutput(channels == 1) ? ChannelLayout.STEREO : next.layout();
            output = new AudioBuffer(layout, sampleRate, outputLength);
            if (mapping != null) {
                Arrays.fill(sbrElements, null);
            }
        }
        mapping = next;
    }

    /**
     * Assigns the parsed streams to coded channels through the active mapping.
     */
    private void assignChannels() {
        var ordinals = new int[4];
        if (elementChannels.length < elementCount) {
            elementChannels = new int[elementTypes.length];
        }
        for (var element = 0; element < elementCount; element++) {
            var type = elementTypes[element];
            var channel = mapping.channelOf(type, elementTags[element], ordinals[type]++);
            elementChannels[element] = channel;
            channelStreams[channel] = streams[elementStreams[element]];
            if (type == ElementId.CPE) {
                channelStreams[channel + 1] = streams[elementStreams[element] + 1];
            }
        }
    }

    /**
     * Returns the next free stream, growing the pool when needed.
     *
     * @return the stream
     * @throws MediaException.LimitExceeded if the block holds more channels than the limits allow
     */
    private ChannelStream nextStream() {
        if (streamCount == streams.length) {
            LimitChecks.checkChannels(limits, streamCount + 1);
            streams = Arrays.copyOf(streams, streamCount + 2);
            for (var i = streamCount; i < streams.length; i++) {
                streams[i] = new ChannelStream(frameLength, tableIndex, objectType, resilience, kernel);
            }
        }
        return streams[streamCount++];
    }

    /**
     * Records a channel element of the current block.
     *
     * @param type the element type
     * @param tag the element instance tag
     * @param firstStream the index of its first stream
     */
    private void addElement(int type, int tag, int firstStream) {
        if (elementCount == elementTypes.length) {
            elementTypes = Arrays.copyOf(elementTypes, elementCount * 2);
            elementTags = Arrays.copyOf(elementTags, elementCount * 2);
            elementStreams = Arrays.copyOf(elementStreams, elementCount * 2);
        }
        elementTypes[elementCount] = type;
        elementTags[elementCount] = tag;
        elementStreams[elementCount] = firstStream;
        elementCount++;
    }

    /**
     * Decodes a single channel element or an LFE element.
     *
     * @param reader the bit source of the block, unused when the category instances come separately
     * @param type the element type
     */
    private void decodeSingle(AacBitReader reader, int type) {
        var source = separateCategories ? nextInstance() : reader;
        var tag = enhancedLowDelay ? 0 : source.read(4);
        var first = streamCount;
        var stream = nextStream();
        parseChannel(stream, source, false, false);
        fillNoise(stream, null, false);
        predict(stream, type, tag, 0);
        addElement(type, tag, first);
    }

    /**
     * Runs the AAC Main predictor bank of one channel of an element; does nothing for the other
     * object types.
     *
     * <p>Banks belong to the element type and instance tag, so they follow an element whatever its
     * position in the block.
     *
     * @param stream the channel's stream
     * @param type the element type
     * @param tag the element instance tag
     * @param channel the channel within the element, 0 or 1
     */
    private void predict(ChannelStream stream, int type, int tag, int channel) {
        if (objectType != AudioSpecificConfig.AOT_MAIN) {
            return;
        }
        var index = (type * 16 + tag) * 2 + channel;
        var predictor = predictors[index];
        if (predictor == null) {
            predictor = new FrequencyPredictor(frameLength, tableIndex);
            predictors[index] = predictor;
        }
        predictor.apply(stream);
    }

    /**
     * Decodes a channel pair element and applies its stereo tools.
     *
     * @param element the bit source of the block, unused when the category instances come separately
     * @throws MediaException.Malformed if the M/S mask mode is reserved
     */
    private void decodePair(AacBitReader element) {
        var reader = separateCategories ? nextInstance() : element;
        var tag = enhancedLowDelay ? 0 : reader.read(4);
        var first = streamCount;
        var left = nextStream();
        var right = nextStream();
        var commonWindow = enhancedLowDelay || reader.readFlag();
        var msMode = 0;
        if (commonWindow) {
            left.readInfo(reader, right);
            right.copyInfo(left);
            msMode = reader.read(2);
            if (msMode == 3) {
                throw new MediaException.Malformed("reserved AAC M/S mask mode");
            }
            for (var group = 0; group < left.groupCount(); group++) {
                var base = group * ChannelStream.MAX_BANDS;
                for (var band = 0; band < left.maxBands(); band++) {
                    msUsed[base + band] = msMode == 2 || (msMode == 1 && reader.readFlag());
                }
            }
        }
        reader.checkNotOverrun();
        parseChannel(left, separateCategories ? nextInstance() : reader, commonWindow, false);
        parseChannel(right, separateCategories ? nextInstance() : reader, commonWindow, true);
        if (msMode != 0) {
            applyMidSide(left, right);
        }
        fillNoise(left, null, false);
        fillNoise(right, left, msMode != 0);
        predict(left, ElementId.CPE, tag, 0);
        predict(right, ElementId.CPE, tag, 1);
        applyIntensity(left, right, msMode != 0);
        addElement(ElementId.CPE, tag, first);
    }

    /**
     * Applies M/S stereo to every flagged band coded in both channels.
     *
     * @param left the mid channel, becoming left
     * @param right the side channel, becoming right
     */
    private void applyMidSide(ChannelStream left, ChannelStream right) {
        var offsets = left.bandOffsets();
        var windowLength = left.windowLength();
        var l = left.spectrum();
        var r = right.spectrum();
        var firstWindow = 0;
        for (var group = 0; group < left.groupCount(); group++) {
            var base = group * ChannelStream.MAX_BANDS;
            var lastWindow = firstWindow + left.groupLength(group);
            for (var band = 0; band < left.maxBands(); band++) {
                var index = base + band;
                if (!msUsed[index] || left.bandType(index) >= Codebooks.NOISE_HCB
                        || right.bandType(index) >= Codebooks.NOISE_HCB) {
                    continue;
                }
                for (var window = firstWindow; window < lastWindow; window++) {
                    kernel.midSide(l, r, window * windowLength + offsets[band], window * windowLength + offsets[band + 1]);
                }
            }
            firstWindow = lastWindow;
        }
    }

    /**
     * Fills the noise bands of a stream with scaled pseudo-random noise.
     *
     * <p>When {@code partner} is given and {@code correlated} is set, a band that is a noise band
     * in both channels and flagged for M/S reuses the partner's noise vector, as ISO/IEC 14496-3
     * subclause 4.6.13 requires.
     *
     * @param stream the stream to fill
     * @param partner the left channel of the pair when {@code stream} is the right one, or
     * {@code null}
     * @param correlated whether M/S flags are present
     */
    private void fillNoise(ChannelStream stream, ChannelStream partner, boolean correlated) {
        var offsets = stream.bandOffsets();
        var windowLength = stream.windowLength();
        var spectrum = stream.spectrum();
        var firstWindow = 0;
        for (var group = 0; group < stream.groupCount(); group++) {
            var base = group * ChannelStream.MAX_BANDS;
            var lastWindow = firstWindow + stream.groupLength(group);
            for (var band = 0; band < stream.maxBands(); band++) {
                var index = base + band;
                if (stream.bandType(index) != Codebooks.NOISE_HCB) {
                    continue;
                }
                var reuse = partner != null && correlated && msUsed[index]
                        && partner.bandType(index) == Codebooks.NOISE_HCB && partner.sameWindows(stream);
                var gain = Dequantizer.gain(stream.bandValue(index));
                for (var window = firstWindow; window < lastWindow; window++) {
                    var seedIndex = window * ChannelStream.MAX_BANDS + band;
                    int state;
                    if (reuse) {
                        state = noiseSeeds[seedIndex];
                    } else {
                        state = noiseState;
                        if (partner == null) {
                            noiseSeeds[seedIndex] = state;
                        }
                    }
                    var start = window * windowLength + offsets[band];
                    var end = window * windowLength + offsets[band + 1];
                    var energy = 0.0;
                    for (var k = start; k < end; k++) {
                        state = state * 1664525 + 1013904223;
                        var value = (float) state;
                        spectrum[k] = value;
                        energy += (double) value * value;
                    }
                    if (!reuse) {
                        noiseState = state;
                    }
                    var scale = (float) (gain / Math.sqrt(energy));
                    for (var k = start; k < end; k++) {
                        spectrum[k] *= scale;
                    }
                }
            }
            firstWindow = lastWindow;
        }
    }

    /**
     * Reconstructs the intensity bands of the right channel from the left channel.
     *
     * @implNote This implementation lets the M/S flags invert the intensity phase whenever an
     * M/S mask is present, including mask mode 2 where every band is flagged. ISO/IEC 14496-3
     * subclause 4.6.8.2.3 words the inversion for mask mode 1; the conformance streams exercise
     * only modes 0 and 1, and ffmpeg's encoder, the common producer of intensity bands under
     * mask mode 2, codes them for the inverting reading its own decoder implements.
     *
     * @param left the left channel
     * @param right the right channel
     * @param msInvert whether an M/S mask is present, whose flags invert the intensity phase
     */
    private void applyIntensity(ChannelStream left, ChannelStream right, boolean msInvert) {
        var offsets = right.bandOffsets();
        var windowLength = right.windowLength();
        var l = left.spectrum();
        var r = right.spectrum();
        var firstWindow = 0;
        for (var group = 0; group < right.groupCount(); group++) {
            var base = group * ChannelStream.MAX_BANDS;
            var lastWindow = firstWindow + right.groupLength(group);
            for (var band = 0; band < right.maxBands(); band++) {
                var index = base + band;
                var type = right.bandType(index);
                if (type != Codebooks.INTENSITY_HCB && type != Codebooks.INTENSITY_HCB2) {
                    continue;
                }
                var inPhase = type == Codebooks.INTENSITY_HCB;
                if (msInvert && msUsed[index]) {
                    inPhase = !inPhase;
                }
                var scale = Dequantizer.gain(-right.bandValue(index));
                if (!inPhase) {
                    scale = -scale;
                }
                for (var window = firstWindow; window < lastWindow; window++) {
                    var end = window * windowLength + offsets[band + 1];
                    for (var k = window * windowLength + offsets[band]; k < end; k++) {
                        r[k] = l[k] * scale;
                    }
                }
            }
            firstWindow = lastWindow;
        }
    }

    /**
     * Parses a coupling channel element.
     *
     * @param reader the bit source
     * @throws MediaException.Malformed if two coupling channel elements of a block share a tag
     */
    private void decodeCoupling(AacBitReader reader) {
        var tag = reader.read(4);
        var coupling = couplings[tag];
        if (coupling == null) {
            coupling = new CouplingChannel(frameLength, tableIndex, objectType, kernel);
            couplings[tag] = coupling;
        }
        for (var i = 0; i < couplingCount; i++) {
            if (frameCouplings[i] == coupling) {
                throw new MediaException.Malformed("two AAC coupling channel elements share tag " + tag);
            }
        }
        coupling.parse(reader, tag);
        if (longTermPrediction && !coupling.independent()) {
            throw new MediaException.Malformed("dependently switched AAC coupling channel in an LTP stream");
        }
        fillNoise(coupling.stream(), null, false);
        coupling.predict();
        frameCouplings[couplingCount++] = coupling;
    }

    /**
     * Applies the coupling channels' own TNS, synthesizes the independently switched ones and
     * resolves every coupling target to a parsed stream and gain list.
     *
     * <p>Targets name an element by type and instance tag; a target absent from the block is
     * ignored.
     */
    private void prepareCouplings() {
        var independentChannel = channelStreams.length;
        for (var i = 0; i < couplingCount; i++) {
            var coupling = frameCouplings[i];
            coupling.predictLongTerm(synthesis);
            coupling.stream().applyTns();
            if (coupling.independent()) {
                if (dynamicRange != null && !sbrActive()) {
                    dynamicRange.applySpectrum(independentChannel, coupling.stream().spectrum());
                }
                independentChannel++;
                coupling.synthesize(synthesis, ssrSynthesis);
            }
            var targets = couplingTargets[i];
            var lists = couplingLists[i];
            Arrays.fill(targets, null);
            var list = 0;
            for (var target = 0; target < coupling.targetCount(); target++) {
                var pair = coupling.targetIsPair(target);
                var first = findElementStream(pair ? ElementId.CPE : ElementId.SCE, coupling.targetTag(target));
                if (!pair) {
                    if (first >= 0) {
                        targets[2 * target] = streams[first];
                        lists[2 * target] = list;
                    }
                    list++;
                    continue;
                }
                var select = coupling.targetSelect(target);
                if (select != 1) {
                    if (first >= 0) {
                        targets[2 * target] = streams[first];
                        lists[2 * target] = list;
                    }
                    if (select != 0) {
                        list++;
                    }
                }
                if (select != 2) {
                    if (first >= 0) {
                        targets[2 * target + 1] = streams[first + 1];
                        lists[2 * target + 1] = list;
                    }
                    list++;
                }
            }
        }
    }

    /**
     * Returns the first stream of the block's element of a type and tag.
     *
     * @param type the element type
     * @param tag the instance tag
     * @return the stream index, or {@code -1} when the block has no such element
     */
    private int findElementStream(int type, int tag) {
        for (var element = 0; element < elementCount; element++) {
            if (elementTypes[element] == type && elementTags[element] == tag) {
                return elementStreams[element];
            }
        }
        return -1;
    }

    /**
     * Adds the dependently switched coupling channels that target a stream to its spectrum.
     *
     * @param stream the target stream
     * @param afterTns whether to apply the couplings of the after-TNS domain rather than the
     * before-TNS one
     */
    private void coupleSpectrum(ChannelStream stream, boolean afterTns) {
        for (var i = 0; i < couplingCount; i++) {
            var coupling = frameCouplings[i];
            if (coupling.independent() || coupling.afterTns() != afterTns) {
                continue;
            }
            var targets = couplingTargets[i];
            for (var slot = 0; slot < 2 * coupling.targetCount(); slot++) {
                if (targets[slot] == stream) {
                    coupling.addSpectrum(couplingLists[i][slot], stream.spectrum());
                }
            }
        }
    }

    /**
     * Adds the independently switched coupling channels that target a stream to its output.
     *
     * @param stream the target stream
     * @param plane the stream's output samples
     */
    private void coupleTime(ChannelStream stream, float[] plane) {
        for (var i = 0; i < couplingCount; i++) {
            var coupling = frameCouplings[i];
            if (!coupling.independent()) {
                continue;
            }
            var targets = couplingTargets[i];
            for (var slot = 0; slot < 2 * coupling.targetCount(); slot++) {
                if (targets[slot] == stream) {
                    coupling.addTimeSignal(couplingLists[i][slot], plane, frameLength);
                }
            }
        }
    }

    /**
     * Reads a data stream element: hands its bytes to the dynamic range control, which takes the
     * heavy compression value of ETSI TS 101 154 ancillary data from them, or skips them.
     *
     * @param reader the bit source
     */
    private void readDataStream(AacBitReader reader) {
        reader.read(4);
        var align = reader.readFlag();
        var count = reader.read(8);
        if (count == 255) {
            count += reader.read(8);
        }
        if (align) {
            reader.alignToByte();
        }
        if (dynamicRange != null && count <= reader.bitsLeft() / 8) {
            dynamicRange.readAncillary(reader, count);
        } else {
            reader.skip(8L * count);
        }
    }

    /**
     * Reads a fill element: hands a spectral band replication payload that follows a single
     * channel or channel pair element to that element's decoder, and skips every other payload.
     *
     * @param reader the bit source
     */
    private void readFill(AacBitReader reader) {
        var count = reader.read(4);
        if (count == 15) {
            count += reader.read(8) - 1;
        }
        if (count <= 0) {
            return;
        }
        var bits = 8L * count;
        var previous = elementCount == 0 ? -1 : elementTypes[elementCount - 1];
        if (dynamicRange == null
                && (sbrMode == SbrMode.OFF || (previous != ElementId.SCE && previous != ElementId.CPE))) {
            reader.skip(bits);
            return;
        }
        var end = reader.bitPosition() + bits;
        while (count > 0) {
            var type = reader.read(4);
            if (type == DynamicRangeControl.EXT_DYNAMIC_RANGE && dynamicRange != null) {
                var used = dynamicRange.read(reader, 8L * count - 4);
                if (used >= count) {
                    reader.seek(end);
                    return;
                }
                count -= used;
                continue;
            }
            if ((type != EXT_SBR_DATA && type != EXT_SBR_DATA_CRC) || sbrMode == SbrMode.OFF
                    || (previous != ElementId.SCE && previous != ElementId.CPE)) {
                reader.skip(8L * count - 4);
                return;
            }
            sbrInBlock = true;
            sbrElement(elementCount - 1, previous).parse(reader, 8L * count - 4, type == EXT_SBR_DATA_CRC);
            return;
        }
    }

    /**
     * Reads the extension payloads that end an error resilient block, ISO/IEC 14496-3 Table 4.19,
     * handing dynamic range information to the dynamic range control and skipping the rest.
     *
     * @param reader the bit source
     */
    private void readErrorResilientExtensions(AacBitReader reader) {
        var count = reader.bitsLeft() / 8;
        while (count >= 1) {
            var type = reader.read(4);
            if (type != DynamicRangeControl.EXT_DYNAMIC_RANGE) {
                break;
            }
            var used = dynamicRange.read(reader, 8 * count - 4);
            if (used >= count) {
                break;
            }
            count -= used;
        }
    }
}
